package org.lokrusta.sqlprometheus.demo;

import org.lokrusta.sqlprometheus.demo.api.CatalogApi;
import org.lokrusta.sqlprometheus.demo.api.OrderApi;
import org.lokrusta.sqlprometheus.demo.api.ProductApi;
import org.lokrusta.sqlprometheus.demo.api.UserApi;
import org.lokrusta.sqlprometheus.demo.api.model.CartResponse;
import org.lokrusta.sqlprometheus.demo.api.model.CategoryListResponse;
import org.lokrusta.sqlprometheus.demo.api.model.CreateOrderFromCartRequest;
import org.lokrusta.sqlprometheus.demo.api.model.CreateOrderRequest;
import org.lokrusta.sqlprometheus.demo.api.model.CreateOrderResponse;
import org.lokrusta.sqlprometheus.demo.api.model.MutationResponse;
import org.lokrusta.sqlprometheus.demo.api.model.OrderItemsResponse;
import org.lokrusta.sqlprometheus.demo.api.model.OrderListResponse;
import org.lokrusta.sqlprometheus.demo.api.model.OrderResponse;
import org.lokrusta.sqlprometheus.demo.api.model.PatchProductDetailsRequest;
import org.lokrusta.sqlprometheus.demo.api.model.ProductAvailabilityResponse;
import org.lokrusta.sqlprometheus.demo.api.model.ProductListResponse;
import org.lokrusta.sqlprometheus.demo.api.model.ProductResponse;
import org.lokrusta.sqlprometheus.demo.api.model.PutCartRequest;
import org.lokrusta.sqlprometheus.demo.api.model.PutOrderItemsRequest;
import org.lokrusta.sqlprometheus.demo.api.model.PutUserProfileRequest;
import org.lokrusta.sqlprometheus.demo.api.model.UserProfileResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
public class DemoApiController implements CatalogApi, ProductApi, UserApi, OrderApi {
    private final ActionExecutor actions;
    private final ShopService shop;

    public DemoApiController(ActionExecutor actions, ShopService shop) {
        this.actions = actions;
        this.shop = shop;
    }

    @Override
    public ResponseEntity<CategoryListResponse> getCategories() {
        return ResponseEntity.ok(actions.execute("getCategories", null, ignored -> shop.categories()));
    }

    @Override
    public ResponseEntity<ProductListResponse> getProducts(Integer categoryId, BigDecimal priceFrom, BigDecimal priceTo,
                                                           Integer limit, Integer offset) {
        return ResponseEntity.ok(actions.execute("getProducts", categoryId,
                ignored -> shop.products(categoryId, priceFrom, priceTo, limit, offset)));
    }

    @Override
    public ResponseEntity<ProductListResponse> searchProducts(String query, Integer limit, Integer offset) {
        return ResponseEntity.ok(actions.execute("searchProducts", query,
                ignored -> shop.search(query, limit, offset)));
    }

    @Override
    public ResponseEntity<ProductResponse> getProduct(Long productId) {
        return ResponseEntity.ok(actions.execute("getProduct", productId, ignored -> shop.product(productId)));
    }

    @Override
    public ResponseEntity<MutationResponse> patchProductDetails(Long productId, PatchProductDetailsRequest request) {
        return ResponseEntity.ok(actions.execute("patchProductDetails", request,
                ignored -> shop.patchDetails(productId, request)));
    }

    @Override
    public ResponseEntity<ProductAvailabilityResponse> getProductAvailability(Long productId) {
        return ResponseEntity.ok(actions.execute("getProductAvailability", productId,
                ignored -> shop.availability(productId)));
    }

    @Override
    public ResponseEntity<UserProfileResponse> getUserProfile(Long userId) {
        return ResponseEntity.ok(actions.execute("getUserProfile", userId, ignored -> shop.profile(userId)));
    }

    @Override
    public ResponseEntity<MutationResponse> putUserProfile(Long userId, PutUserProfileRequest request) {
        return ResponseEntity.ok(actions.execute("putUserProfile", request,
                ignored -> shop.updateProfile(userId, request)));
    }

    @Override
    public ResponseEntity<CartResponse> getUserCart(Long userId) {
        return ResponseEntity.ok(actions.execute("getUserCart", userId, ignored -> shop.cart(userId)));
    }

    @Override
    public ResponseEntity<MutationResponse> putUserCart(Long userId, PutCartRequest request) {
        return ResponseEntity.ok(actions.execute("putUserCart", request,
                ignored -> shop.replaceCart(userId, request)));
    }

    @Override
    public ResponseEntity<OrderListResponse> getUserOrders(Long userId, Integer limit, Integer offset) {
        return ResponseEntity.ok(actions.execute("getUserOrders", userId,
                ignored -> shop.orders(userId, limit, offset)));
    }

    @Override
    public ResponseEntity<CreateOrderResponse> createOrder(CreateOrderRequest request) {
        return ResponseEntity.ok(actions.execute("createOrder", request, ignored -> {
            try {
                return shop.createOrder(request);
            } catch (ShopExceptions.InsufficientStock stock) {
                return refused(stock);
            }
        }));
    }

    @Override
    public ResponseEntity<CreateOrderResponse> createOrderFromCart(CreateOrderFromCartRequest request) {
        return ResponseEntity.ok(actions.execute("createOrderFromCart", request, ignored -> {
            try {
                return shop.createOrderFromCart(request);
            } catch (ShopExceptions.InsufficientStock stock) {
                return refused(stock);
            }
        }));
    }

    @Override
    public ResponseEntity<OrderResponse> getOrder(Long orderId) {
        return ResponseEntity.ok(actions.execute("getOrder", orderId, ignored -> shop.order(orderId)));
    }

    @Override
    public ResponseEntity<OrderItemsResponse> getOrderItems(Long orderId) {
        return ResponseEntity.ok(actions.execute("getOrderItems", orderId, ignored -> shop.orderItemsResponse(orderId)));
    }

    @Override
    public ResponseEntity<MutationResponse> putOrderItems(Long orderId, PutOrderItemsRequest request) {
        return ResponseEntity.ok(actions.execute("putOrderItems", request, ignored -> {
            try {
                return shop.replaceOrderItems(orderId, request);
            } catch (ShopExceptions.InsufficientStock stock) {
                MutationResponse response = new MutationResponse();
                response.setCode("INSUFFICIENT_STOCK");
                response.setMessage(stock.getMessage());
                response.setAffectedRows(0);
                return response;
            }
        }));
    }

    private static CreateOrderResponse refused(ShopExceptions.InsufficientStock stock) {
        CreateOrderResponse response = new CreateOrderResponse();
        response.setCode("INSUFFICIENT_STOCK");
        response.setMessage(stock.getMessage());
        return response;
    }
}
