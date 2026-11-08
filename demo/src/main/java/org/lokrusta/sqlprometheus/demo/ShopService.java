package org.lokrusta.sqlprometheus.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.lokrusta.sqlprometheus.demo.api.model.CartItem;
import org.lokrusta.sqlprometheus.demo.api.model.CartResponse;
import org.lokrusta.sqlprometheus.demo.api.model.CategoryListResponse;
import org.lokrusta.sqlprometheus.demo.api.model.CategoryResponse;
import org.lokrusta.sqlprometheus.demo.api.model.CreateOrderFromCartRequest;
import org.lokrusta.sqlprometheus.demo.api.model.CreateOrderRequest;
import org.lokrusta.sqlprometheus.demo.api.model.CreateOrderResponse;
import org.lokrusta.sqlprometheus.demo.api.model.MutationResponse;
import org.lokrusta.sqlprometheus.demo.api.model.OrderItem;
import org.lokrusta.sqlprometheus.demo.api.model.OrderItemsResponse;
import org.lokrusta.sqlprometheus.demo.api.model.OrderListItemResponse;
import org.lokrusta.sqlprometheus.demo.api.model.OrderListResponse;
import org.lokrusta.sqlprometheus.demo.api.model.OrderResponse;
import org.lokrusta.sqlprometheus.demo.api.model.PatchProductDetailsRequest;
import org.lokrusta.sqlprometheus.demo.api.model.ProductAvailabilityResponse;
import org.lokrusta.sqlprometheus.demo.api.model.ProductListResponse;
import org.lokrusta.sqlprometheus.demo.api.model.ProductResponse;
import org.lokrusta.sqlprometheus.demo.api.model.ProductSummary;
import org.lokrusta.sqlprometheus.demo.api.model.PutCartRequest;
import org.lokrusta.sqlprometheus.demo.api.model.PutOrderItemsRequest;
import org.lokrusta.sqlprometheus.demo.api.model.PutUserProfileRequest;
import org.lokrusta.sqlprometheus.demo.api.model.UserProfileResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ShopService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private static final RowMapper<ProductSummary> PRODUCT = (rs, row) -> {
        ProductSummary product = new ProductSummary();
        product.setId(rs.getLong("id"));
        product.setName(rs.getString("name"));
        product.setCategoryId(rs.getInt("category_id"));
        product.setPrice(rs.getBigDecimal("price"));
        product.setStock(rs.getInt("stock"));
        product.setVersion(rs.getLong("version"));
        return product;
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ShopService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public CategoryListResponse categories() {
        List<CategoryResponse> items = jdbc.query(
                "SELECT id, name, version FROM ecom.categories ORDER BY id",
                (rs, row) -> {
                    CategoryResponse category = new CategoryResponse();
                    category.setId(rs.getInt("id"));
                    category.setName(rs.getString("name"));
                    category.setVersion(rs.getLong("version"));
                    return category;
                });
        CategoryListResponse response = new CategoryListResponse();
        response.setItems(items);
        return response;
    }

    public ProductListResponse products(Integer categoryId, BigDecimal priceFrom, BigDecimal priceTo,
                                        Integer limit, Integer offset) {
        int page = pageSize(limit);
        List<ProductSummary> rows = jdbc.query("""
                SELECT p.id, p.name, p.category_id, p.price, s.stock, p.version
                FROM ecom.products p
                JOIN ecom.product_stock s ON s.product_id = p.id
                WHERE (:categoryId IS NULL OR p.category_id = :categoryId)
                  AND (:priceFrom IS NULL OR p.price >= :priceFrom)
                  AND (:priceTo IS NULL OR p.price <= :priceTo)
                ORDER BY p.id
                LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource()
                .addValue("categoryId", categoryId)
                .addValue("priceFrom", priceFrom)
                .addValue("priceTo", priceTo)
                .addValue("limit", page + 1)
                .addValue("offset", pageOffset(offset)), PRODUCT);
        return page(rows, page);
    }

    public ProductListResponse search(String query, Integer limit, Integer offset) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query is required");
        }
        int page = pageSize(limit);
        List<ProductSummary> rows = jdbc.query("""
                SELECT p.id, p.name, p.category_id, p.price, s.stock, p.version
                FROM ecom.products p
                JOIN ecom.product_stock s ON s.product_id = p.id
                WHERE LOWER(p.name) LIKE LOWER(:pattern)
                ORDER BY p.id
                LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource()
                .addValue("pattern", "%" + query.trim() + "%")
                .addValue("limit", page + 1)
                .addValue("offset", pageOffset(offset)), PRODUCT);
        return page(rows, page);
    }

    public ProductResponse product(long productId) {
        List<ProductResponse> rows = jdbc.query("""
                SELECT p.id, p.name, p.category_id, p.price, s.stock, p.version,
                       d.description, d.attributes, d.version AS details_version
                FROM ecom.products p
                JOIN ecom.product_stock s ON s.product_id = p.id
                LEFT JOIN ecom.product_details d ON d.product_id = p.id
                WHERE p.id = :id
                """, Map.of("id", productId), (rs, row) -> {
            ProductResponse product = new ProductResponse();
            product.setId(rs.getLong("id"));
            product.setName(rs.getString("name"));
            product.setCategoryId(rs.getInt("category_id"));
            product.setPrice(rs.getBigDecimal("price"));
            product.setStock(rs.getInt("stock"));
            product.setVersion(rs.getLong("version"));
            product.setDescription(rs.getString("description"));
            product.setAttributes(readAttributes(rs.getString("attributes")));
            long detailsVersion = rs.getLong("details_version");
            product.setDetailsVersion(rs.wasNull() ? null : detailsVersion);
            return product;
        });
        if (rows.isEmpty()) {
            throw new ShopExceptions.NotFound("Product not found");
        }
        return rows.get(0);
    }

    public ProductAvailabilityResponse availability(long productId) {
        List<Integer> stock = jdbc.query(
                "SELECT stock FROM ecom.product_stock WHERE product_id = :id",
                Map.of("id", productId),
                (rs, row) -> rs.getInt("stock"));
        if (stock.isEmpty()) {
            throw new ShopExceptions.NotFound("Product not found");
        }
        ProductAvailabilityResponse response = new ProductAvailabilityResponse();
        response.setProductId(productId);
        response.setStock(stock.get(0));
        response.setAvailable(stock.get(0) > 0);
        return response;
    }

    @Transactional
    public MutationResponse patchDetails(long productId, PatchProductDetailsRequest request) {
        if (request.getDescription() == null && request.getAttributes() == null) {
            throw new IllegalArgumentException("No fields to update");
        }
        if (!exists("SELECT COUNT(*) FROM ecom.products WHERE id = :id", productId)) {
            throw new ShopExceptions.NotFound("Product not found");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", productId)
                .addValue("version", request.getVersion());
        List<String> assignments = new ArrayList<>();
        if (request.getDescription() != null) {
            assignments.add("description = :description");
            params.addValue("description", request.getDescription());
        }
        if (request.getAttributes() != null) {
            assignments.add("attributes = :attributes");
            params.addValue("attributes", writeAttributes(request.getAttributes()));
        }
        assignments.add("version = version + 1");
        int updated = jdbc.update(
                "UPDATE ecom.product_details SET " + String.join(", ", assignments)
                        + " WHERE product_id = :id AND version = :version",
                params);
        if (updated != 1) {
            throw missingOrStale("Product details", "SELECT COUNT(*) FROM ecom.product_details WHERE product_id = :id", productId);
        }
        return success();
    }

    public UserProfileResponse profile(long userId) {
        List<UserProfileResponse> rows = jdbc.query("""
                SELECT id, username, email, reg_date, version
                FROM ecom.users
                WHERE id = :id
                """, Map.of("id", userId), (rs, row) -> {
            UserProfileResponse profile = new UserProfileResponse();
            profile.setId(rs.getLong("id"));
            profile.setUsername(rs.getString("username"));
            profile.setEmail(rs.getString("email"));
            profile.setRegDate(readTime(rs, "reg_date"));
            profile.setVersion(rs.getLong("version"));
            return profile;
        });
        if (rows.isEmpty()) {
            throw new ShopExceptions.NotFound("User not found");
        }
        return rows.get(0);
    }

    @Transactional
    public MutationResponse updateProfile(long userId, PutUserProfileRequest request) {
        try {
            int updated = jdbc.update("""
                    UPDATE ecom.users
                    SET username = :username, email = :email, version = version + 1
                    WHERE id = :id AND version = :version
                    """, new MapSqlParameterSource()
                    .addValue("id", userId)
                    .addValue("username", request.getUsername())
                    .addValue("email", request.getEmail())
                    .addValue("version", request.getVersion()));
            if (updated != 1) {
                throw missingOrStale("User", "SELECT COUNT(*) FROM ecom.users WHERE id = :id", userId);
            }
        } catch (DataIntegrityViolationException e) {
            throw new ShopExceptions.Conflict("Username or email is already used");
        }
        return success();
    }

    @Transactional
    public CartResponse cart(long userId) {
        if (!exists("SELECT COUNT(*) FROM ecom.users WHERE id = :id", userId)) {
            throw new ShopExceptions.NotFound("User not found");
        }
        ensureCart(userId);
        return readCart(userId);
    }

    @Transactional
    public MutationResponse replaceCart(long userId, PutCartRequest request) {
        if (!exists("SELECT COUNT(*) FROM ecom.users WHERE id = :id", userId)) {
            throw new ShopExceptions.NotFound("User not found");
        }
        ensureCart(userId);
        Map<Long, Integer> items = merge(request.getItems());
        for (Long productId : items.keySet()) {
            requireProduct(productId);
        }
        int locked = jdbc.update("""
                UPDATE ecom.carts
                SET version = version + 1
                WHERE user_id = :userId AND version = :version
                """, Map.of("userId", userId, "version", request.getVersion()));
        if (locked != 1) {
            throw new ShopExceptions.Conflict("Cart version does not match");
        }
        long cartId = jdbc.queryForObject(
                "SELECT id FROM ecom.carts WHERE user_id = :userId",
                Map.of("userId", userId),
                Long.class);
        jdbc.update("DELETE FROM ecom.cart_items WHERE cart_id = :id", Map.of("id", cartId));
        insertCartItems(cartId, items);
        return success();
    }

    public OrderListResponse orders(long userId, Integer limit, Integer offset) {
        if (!exists("SELECT COUNT(*) FROM ecom.users WHERE id = :id", userId)) {
            throw new ShopExceptions.NotFound("User not found");
        }
        int page = pageSize(limit);
        List<OrderListItemResponse> rows = jdbc.query("""
                SELECT id, user_id, status, total, version
                FROM ecom.orders
                WHERE user_id = :userId
                ORDER BY created_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", page + 1)
                .addValue("offset", pageOffset(offset)), (rs, row) -> orderHeader(rs));
        boolean hasNext = rows.size() > page;
        OrderListResponse response = new OrderListResponse();
        response.setItems(hasNext ? rows.subList(0, page) : rows);
        response.setHasNext(hasNext);
        return response;
    }

    @Transactional
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        if (!exists("SELECT COUNT(*) FROM ecom.users WHERE id = :id", request.getUserId())) {
            return orderResult(null, "USER_NOT_FOUND", "User not found");
        }
        Map<Long, Integer> lines = merge(request.getItems());
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Order items are required");
        }
        return placeOrder(request.getUserId(), lines);
    }

    @Transactional
    public CreateOrderResponse createOrderFromCart(CreateOrderFromCartRequest request) {
        List<Long> carts = jdbc.query(
                "SELECT id, version FROM ecom.carts WHERE user_id = :userId",
                Map.of("userId", request.getUserId()),
                (rs, row) -> rs.getLong("id"));
        if (carts.isEmpty()) {
            throw new ShopExceptions.NotFound("Cart not found");
        }
        long cartId = carts.get(0);
        long version = jdbc.queryForObject(
                "SELECT version FROM ecom.carts WHERE id = :id",
                Map.of("id", cartId),
                Long.class);
        if (version != request.getCartVersion()) {
            throw new ShopExceptions.Conflict("Cart version does not match");
        }
        Map<Long, Integer> lines = new LinkedHashMap<>();
        jdbc.query("""
                SELECT product_id, quantity
                FROM ecom.cart_items
                WHERE cart_id = :id
                ORDER BY product_id
                """, Map.of("id", cartId), rs -> {
            lines.put(rs.getLong("product_id"), rs.getInt("quantity"));
        });
        if (lines.isEmpty()) {
            return orderResult(null, "EMPTY_CART", "Cart is empty");
        }
        CreateOrderResponse created = placeOrder(request.getUserId(), lines);
        jdbc.update("DELETE FROM ecom.cart_items WHERE cart_id = :id", Map.of("id", cartId));
        int cleared = jdbc.update("""
                UPDATE ecom.carts
                SET version = version + 1
                WHERE id = :id AND version = :version
                """, Map.of("id", cartId, "version", request.getCartVersion()));
        if (cleared != 1) {
            throw new ShopExceptions.Conflict("Cart version does not match");
        }
        return created;
    }

    public OrderResponse order(long orderId) {
        List<OrderListItemResponse> headers = jdbc.query("""
                SELECT id, user_id, status, total, version
                FROM ecom.orders
                WHERE id = :id
                """, Map.of("id", orderId), (rs, row) -> orderHeader(rs));
        if (headers.isEmpty()) {
            throw new ShopExceptions.NotFound("Order not found");
        }
        OrderListItemResponse header = headers.get(0);
        OrderResponse response = new OrderResponse();
        response.setId(header.getId());
        response.setUserId(header.getUserId());
        response.setStatus(header.getStatus());
        response.setTotal(header.getTotal());
        response.setVersion(header.getVersion());
        response.setItems(orderItems(orderId));
        return response;
    }

    public OrderItemsResponse orderItemsResponse(long orderId) {
        if (!exists("SELECT COUNT(*) FROM ecom.orders WHERE id = :id", orderId)) {
            throw new ShopExceptions.NotFound("Order not found");
        }
        OrderItemsResponse response = new OrderItemsResponse();
        response.setOrderId(orderId);
        response.setItems(orderItems(orderId));
        return response;
    }

    @Transactional
    public MutationResponse replaceOrderItems(long orderId, PutOrderItemsRequest request) {
        List<Long> versions = jdbc.query(
                "SELECT version FROM ecom.orders WHERE id = :id",
                Map.of("id", orderId),
                (rs, row) -> rs.getLong("version"));
        if (versions.isEmpty()) {
            throw new ShopExceptions.NotFound("Order not found");
        }
        if (versions.get(0) != request.getVersion()) {
            throw new ShopExceptions.Conflict("Order version does not match");
        }
        Map<Long, Integer> current = new LinkedHashMap<>();
        jdbc.query("""
                SELECT product_id, quantity
                FROM ecom.order_items
                WHERE order_id = :id
                """, Map.of("id", orderId), rs -> {
            current.put(rs.getLong("product_id"), rs.getInt("quantity"));
        });
        Map<Long, Integer> next = merge(request.getItems());
        Map<Long, Integer> union = new LinkedHashMap<>(current);
        next.forEach(union::putIfAbsent);
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (Map.Entry<Long, Integer> line : next.entrySet()) {
            Stock stock = stock(line.getKey());
            total = total.add(stock.price().multiply(BigDecimal.valueOf(line.getValue())))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        for (Long productId : union.keySet()) {
            int delta = next.getOrDefault(productId, 0) - current.getOrDefault(productId, 0);
            if (delta == 0) {
                continue;
            }
            Stock stock = stock(productId);
            if (delta > 0) {
                takeStock(stock, productId, delta);
            } else {
                returnStock(stock, productId, -delta);
            }
        }
        jdbc.update("DELETE FROM ecom.order_items WHERE order_id = :id", Map.of("id", orderId));
        insertOrderItems(orderId, next);
        int updated = jdbc.update("""
                UPDATE ecom.orders
                SET total = :total, version = version + 1
                WHERE id = :id AND version = :version
                """, new MapSqlParameterSource()
                .addValue("id", orderId)
                .addValue("total", total)
                .addValue("version", request.getVersion()));
        if (updated != 1) {
            throw new ShopExceptions.Conflict("Order version does not match");
        }
        MutationResponse response = success();
        response.setAffectedRows(next.size());
        return response;
    }

    private CreateOrderResponse placeOrder(long userId, Map<Long, Integer> lines) {
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (Map.Entry<Long, Integer> line : lines.entrySet()) {
            Stock stock = stock(line.getKey());
            takeStock(stock, line.getKey(), line.getValue());
            total = total.add(stock.price().multiply(BigDecimal.valueOf(line.getValue())))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO ecom.orders (user_id, status, total, version)
                VALUES (:userId, 'NEW', :total, 1)
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("total", total), keys, new String[]{"id"});
        long orderId = keys.getKey().longValue();
        insertOrderItems(orderId, lines);
        return orderResult(orderId, "SUCCESS", "OK");
    }

    private void takeStock(Stock stock, long productId, int quantity) {
        if (stock.stock() < quantity) {
            throw new ShopExceptions.InsufficientStock(
                    "Not enough stock for product " + productId + ": need " + quantity + ", have " + stock.stock());
        }
        int updated = jdbc.update("""
                UPDATE ecom.product_stock
                SET stock = stock - :quantity, version = version + 1
                WHERE product_id = :id AND version = :version AND stock >= :quantity
                """, Map.of("id", productId, "version", stock.version(), "quantity", quantity));
        if (updated != 1) {
            throw new ShopExceptions.Conflict("Stock version does not match for product " + productId);
        }
    }

    private void returnStock(Stock stock, long productId, int quantity) {
        int updated = jdbc.update("""
                UPDATE ecom.product_stock
                SET stock = stock + :quantity, version = version + 1
                WHERE product_id = :id AND version = :version
                """, Map.of("id", productId, "version", stock.version(), "quantity", quantity));
        if (updated != 1) {
            throw new ShopExceptions.Conflict("Stock version does not match for product " + productId);
        }
    }

    private Stock stock(long productId) {
        List<Stock> rows = jdbc.query("""
                SELECT s.stock, s.version, p.price
                FROM ecom.product_stock s
                JOIN ecom.products p ON p.id = s.product_id
                WHERE s.product_id = :id
                """, Map.of("id", productId), (rs, row) -> new Stock(
                rs.getInt("stock"), rs.getLong("version"), rs.getBigDecimal("price")));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Product not found: " + productId);
        }
        return rows.get(0);
    }

    private void insertOrderItems(long orderId, Map<Long, Integer> lines) {
        for (Map.Entry<Long, Integer> line : lines.entrySet()) {
            jdbc.update("""
                    INSERT INTO ecom.order_items (order_id, product_id, quantity, version)
                    VALUES (:orderId, :productId, :quantity, 1)
                    """, Map.of("orderId", orderId, "productId", line.getKey(), "quantity", line.getValue()));
        }
    }

    private void insertCartItems(long cartId, Map<Long, Integer> items) {
        for (Map.Entry<Long, Integer> item : items.entrySet()) {
            jdbc.update("""
                    INSERT INTO ecom.cart_items (cart_id, product_id, quantity, version)
                    VALUES (:cartId, :productId, :quantity, 1)
                    """, Map.of("cartId", cartId, "productId", item.getKey(), "quantity", item.getValue()));
        }
    }

    private List<OrderItem> orderItems(long orderId) {
        return jdbc.query("""
                SELECT product_id, quantity
                FROM ecom.order_items
                WHERE order_id = :id
                ORDER BY product_id
                """, Map.of("id", orderId), (rs, row) -> {
            OrderItem item = new OrderItem();
            item.setProductId(rs.getLong("product_id"));
            item.setQuantity(rs.getInt("quantity"));
            return item;
        });
    }

    private void ensureCart(long userId) {
        try {
            jdbc.update("""
                    INSERT INTO ecom.carts (user_id, version)
                    SELECT :userId, 1
                    WHERE NOT EXISTS (SELECT 1 FROM ecom.carts WHERE user_id = :userId)
                    """, Map.of("userId", userId));
        } catch (DataIntegrityViolationException ignored) {
            // A concurrent request already created the cart.
        }
    }

    private CartResponse readCart(long userId) {
        List<CartResponse> carts = jdbc.query("""
                SELECT id, user_id, version
                FROM ecom.carts
                WHERE user_id = :userId
                """, Map.of("userId", userId), (rs, row) -> {
            CartResponse cart = new CartResponse();
            cart.setCartId(rs.getLong("id"));
            cart.setUserId(rs.getLong("user_id"));
            cart.setVersion(rs.getLong("version"));
            return cart;
        });
        CartResponse cart = carts.get(0);
        List<CartItem> items = jdbc.query("""
                SELECT product_id, quantity
                FROM ecom.cart_items
                WHERE cart_id = :id
                ORDER BY product_id
                """, Map.of("id", cart.getCartId()), (rs, row) -> {
            CartItem item = new CartItem();
            item.setProductId(rs.getLong("product_id"));
            item.setQuantity(rs.getInt("quantity"));
            return item;
        });
        cart.setItems(items);
        return cart;
    }

    private void requireProduct(long productId) {
        if (!exists("SELECT COUNT(*) FROM ecom.products WHERE id = :id", productId)) {
            throw new IllegalArgumentException("Product not found: " + productId);
        }
    }

    private RuntimeException missingOrStale(String name, String existsSql, long id) {
        if (!exists(existsSql, id)) {
            return new ShopExceptions.NotFound(name + " not found");
        }
        return new ShopExceptions.Conflict(name + " version does not match");
    }

    private boolean exists(String sql, long id) {
        Number count = jdbc.queryForObject(sql, Map.of("id", id), Number.class);
        return count != null && count.intValue() > 0;
    }

    private Map<Long, Integer> merge(List<?> items) {
        Map<Long, Integer> merged = new LinkedHashMap<>();
        if (items == null) {
            return merged;
        }
        for (Object item : items) {
            Long productId;
            Integer quantity;
            if (item instanceof OrderItem orderItem) {
                productId = orderItem.getProductId();
                quantity = orderItem.getQuantity();
            } else if (item instanceof CartItem cartItem) {
                productId = cartItem.getProductId();
                quantity = cartItem.getQuantity();
            } else {
                continue;
            }
            if (productId == null || quantity == null || quantity <= 0) {
                continue;
            }
            merged.merge(productId, quantity, Integer::sum);
        }
        return merged;
    }

    private Map<String, Object> readAttributes(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return Map.of("value", json);
        }
    }

    private String writeAttributes(Object attributes) {
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Attributes are not valid JSON");
        }
    }

    private static OffsetDateTime readTime(ResultSet rs, String column) throws SQLException {
        try {
            OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
            if (value != null) {
                return value;
            }
        } catch (SQLException ignored) {
            // H2 may return a timestamp instead of an offset date-time.
        }
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static OrderListItemResponse orderHeader(ResultSet rs) throws SQLException {
        OrderListItemResponse order = new OrderListItemResponse();
        order.setId(rs.getLong("id"));
        order.setUserId(rs.getLong("user_id"));
        order.setStatus(rs.getString("status"));
        order.setTotal(rs.getBigDecimal("total"));
        order.setVersion(rs.getLong("version"));
        return order;
    }

    private static ProductListResponse page(List<ProductSummary> rows, int page) {
        boolean hasNext = rows.size() > page;
        ProductListResponse response = new ProductListResponse();
        response.setItems(hasNext ? new ArrayList<>(rows.subList(0, page)) : rows);
        response.setHasNext(hasNext);
        return response;
    }

    private static CreateOrderResponse orderResult(Long orderId, String code, String message) {
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(orderId);
        response.setCode(code);
        response.setMessage(message);
        return response;
    }

    private static MutationResponse success() {
        MutationResponse response = new MutationResponse();
        response.setCode("SUCCESS");
        response.setMessage("OK");
        response.setAffectedRows(1);
        return response;
    }

    private static int pageSize(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static int pageOffset(Integer offset) {
        return offset == null || offset < 0 ? 0 : offset;
    }

    private record Stock(int stock, long version, BigDecimal price) {
    }
}
