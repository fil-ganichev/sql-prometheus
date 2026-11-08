package org.lokrusta.sqlprometheus.demo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShopApiTest {
    @Autowired
    private MockMvc http;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void catalogSearchAndMissingProduct() throws Exception {
        double before = count(ActionExecutor.OPERATION_CODE, ActionExecutor.PROCESS_ACTION, "getCategories", ActionExecutor.CODE, "SUCCESS");
        http.perform(get("/api/v1/catalog/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].name").value("Coffee"));
        assertEquals(before + 1, count(ActionExecutor.OPERATION_CODE, ActionExecutor.PROCESS_ACTION, "getCategories", ActionExecutor.CODE, "SUCCESS"));

        http.perform(get("/api/v1/catalog/products").param("categoryId", "1").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));

        http.perform(get("/api/v1/catalog/products/search").param("q", "House Blend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("House Blend Coffee Beans"))
                .andExpect(jsonPath("$.items[0].stock").value(40));

        http.perform(get("/api/v1/products/999"))
                .andExpect(status().isNotFound());
        assertTrue(count(ErrorHandler.SERVICE_ERROR, ErrorHandler.EXCEPTION, "NotFound") >= 1);
    }

    @Test
    void timerPublishesBucketsFromATenthOfAMillisecondToOneSecond() throws Exception {
        http.perform(get("/api/v1/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("House Blend Coffee Beans"))
                .andExpect(jsonPath("$.detailsVersion").value(1));

        Timer timer = meters.find(ActionExecutor.OPERATION_TIME)
                .tag(ActionExecutor.PROCESS_ACTION, "getProduct")
                .tag(ActionExecutor.CODE, "SUCCESS")
                .timer();
        assertNotNull(timer);
        assertTrue(timer.count() >= 1);
        boolean tenthOfAMillisecond = false;
        boolean oneSecond = false;
        StringBuilder boundaries = new StringBuilder();
        for (CountAtBucket bucket : timer.takeSnapshot().histogramCounts()) {
            double seconds = bucket.bucket(TimeUnit.SECONDS);
            boundaries.append(seconds).append(' ');
            if (Math.abs(seconds - 0.0001d) < 1e-12) {
                tenthOfAMillisecond = true;
            }
            if (Math.abs(seconds - 1.0d) < 1e-9) {
                oneSecond = true;
            }
        }
        assertTrue(tenthOfAMillisecond, boundaries.toString());
        assertTrue(oneSecond, boundaries.toString());
    }

    @Test
    void staleDetailsVersionIsAConflictAndIncrementsTheErrorMetric() throws Exception {
        double before = count(ActionExecutor.OPERATION_CODE, ActionExecutor.PROCESS_ACTION, "patchProductDetails", ActionExecutor.CODE, "ERROR");
        double errors = count(ErrorHandler.SERVICE_ERROR, ErrorHandler.EXCEPTION, "Conflict");
        http.perform(patch("/api/v1/products/2/details")
                        .contentType("application/json")
                        .content("{\"version\":999,\"description\":\"Updated espresso description\"}"))
                .andExpect(status().isConflict());
        assertEquals(before + 1, count(ActionExecutor.OPERATION_CODE, ActionExecutor.PROCESS_ACTION, "patchProductDetails", ActionExecutor.CODE, "ERROR"));
        assertEquals(errors + 1, count(ErrorHandler.SERVICE_ERROR, ErrorHandler.EXCEPTION, "Conflict"));

        http.perform(patch("/api/v1/products/2/details")
                        .contentType("application/json")
                        .content("{\"version\":1,\"description\":\"Updated espresso description\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        http.perform(get("/api/v1/products/2"))
                .andExpect(jsonPath("$.description").value("Updated espresso description"))
                .andExpect(jsonPath("$.detailsVersion").value(2));
    }

    @Test
    void profileCartAndOrderUseOptimisticVersions() throws Exception {
        http.perform(put("/api/v1/users/4/profile")
                        .contentType("application/json")
                        .content("{\"username\":\"anna.dawson\",\"email\":\"anna.dawson@example.com\",\"version\":999}"))
                .andExpect(status().isConflict());

        http.perform(get("/api/v1/users/2/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.items.length()").value(0));
        http.perform(put("/api/v1/users/2/cart")
                        .contentType("application/json")
                        .content("{\"version\":1,\"items\":[{\"productId\":8,\"quantity\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        http.perform(put("/api/v1/users/2/cart")
                        .contentType("application/json")
                        .content("{\"version\":1,\"items\":[{\"productId\":8,\"quantity\":1}]}"))
                .andExpect(status().isConflict());

        int stockBefore = jdbc.queryForObject("SELECT stock FROM ecom.product_stock WHERE product_id = 6", Integer.class);
        http.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content("{\"userId\":1,\"items\":[{\"productId\":6,\"quantity\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.orderId").isNumber());
        assertEquals(stockBefore - 2, jdbc.queryForObject(
                "SELECT stock FROM ecom.product_stock WHERE product_id = 6", Integer.class));

        http.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content("{\"userId\":1,\"items\":[{\"productId\":5,\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        int cartStock = jdbc.queryForObject("SELECT stock FROM ecom.product_stock WHERE product_id = 8", Integer.class);
        String created = http.perform(post("/api/v1/orders/from-cart")
                        .contentType("application/json")
                        .content("{\"userId\":2,\"cartVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(created.contains("\"orderId\""));
        assertEquals(cartStock - 2, jdbc.queryForObject(
                "SELECT stock FROM ecom.product_stock WHERE product_id = 8", Integer.class));
        http.perform(get("/api/v1/users/2/cart"))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.version").value(3));

        String orderId = created.replaceAll(".*\"orderId\":(\\d+).*", "$1");
        http.perform(get("/api/v1/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(33.60))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.version").value(1));
        http.perform(put("/api/v1/orders/" + orderId + "/items")
                        .contentType("application/json")
                        .content("{\"version\":1,\"items\":[{\"productId\":8,\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        http.perform(put("/api/v1/orders/" + orderId + "/items")
                        .contentType("application/json")
                        .content("{\"version\":1,\"items\":[{\"productId\":8,\"quantity\":3}]}"))
                .andExpect(status().isConflict());
        http.perform(get("/api/v1/users/1/orders").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    private double count(String name, String... tags) {
        var search = meters.find(name);
        for (int i = 0; i < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        Counter counter = search.counter();
        return counter == null ? 0 : counter.count();
    }
}
