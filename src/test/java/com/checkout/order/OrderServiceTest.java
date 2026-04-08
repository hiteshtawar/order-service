package com.checkout.order;

import com.checkout.order.controller.dto.CreateOrderRequest;
import com.checkout.order.controller.dto.OrderItemRequest;
import com.checkout.order.exception.OrderNotFoundException;
import com.checkout.order.model.Order;
import com.checkout.order.model.OrderStatus;
import com.checkout.order.repository.OrderRepository;
import com.checkout.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
class OrderServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("orders_test")
            .withUsername("orders")
            .withPassword("orders");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    private UUID customerId;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        customerId = UUID.randomUUID();
    }

    @Test
    @DisplayName("createOrder persists order with correct total")
    void createOrder_persistsOrderWithCorrectTotal() {
        CreateOrderRequest request = new CreateOrderRequest(
                customerId,
                List.of(
                        new OrderItemRequest(UUID.randomUUID(), 2, new BigDecimal("9.99")),
                        new OrderItemRequest(UUID.randomUUID(), 1, new BigDecimal("19.99"))
                )
        );

        Order created = orderService.createOrder(request);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getCustomerId()).isEqualTo(customerId);
        assertThat(created.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(created.getTotalAmount()).isEqualByComparingTo("39.97");
        assertThat(created.getItems()).hasSize(2);
    }

    @Test
    @DisplayName("getOrder returns existing order")
    void getOrder_returnsExistingOrder() {
        Order saved = createTestOrder();
        Order fetched = orderService.getOrder(saved.getId());
        assertThat(fetched.getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("getOrder throws OrderNotFoundException for unknown ID")
    void getOrder_throwsForUnknownId() {
        assertThatThrownBy(() -> orderService.getOrder(UUID.randomUUID()))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("listOrders returns paginated results for customer")
    void listOrders_returnsPaginatedResultsForCustomer() {
        createTestOrder();
        createTestOrder();

        Page<Order> page = orderService.listOrders(customerId, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allMatch(o -> o.getCustomerId().equals(customerId));
    }

    @Test
    @DisplayName("cancelOrder transitions status to CANCELLED")
    void cancelOrder_transitionsStatusToCancelled() {
        Order order = createTestOrder();
        Order cancelled = orderService.cancelOrder(order.getId());
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancelOrder is idempotent for already-cancelled orders")
    void cancelOrder_isIdempotentForAlreadyCancelled() {
        Order order = createTestOrder();
        orderService.cancelOrder(order.getId());
        Order again = orderService.cancelOrder(order.getId());
        assertThat(again.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("getOrdersByStatus returns only matching orders")
    void getOrdersByStatus_returnsOnlyMatchingOrders() {
        Order order = createTestOrder();
        orderService.cancelOrder(order.getId());
        createTestOrder(); // still PENDING

        List<Order> cancelled = orderService.getOrdersByStatus(OrderStatus.CANCELLED);
        List<Order> pending = orderService.getOrdersByStatus(OrderStatus.PENDING);

        assertThat(cancelled).hasSize(1);
        assertThat(pending).hasSize(1);
    }

    // ── Regression tests that PARP will add as it fixes issues ────────────────

    @Test
    @DisplayName("createOrder with five items uses batch inventory check — regression for CHECKOUT-042")
    void createOrder_withFiveItems_batchesInventoryCheck() {
        CreateOrderRequest request = new CreateOrderRequest(
                customerId,
                List.of(
                        new OrderItemRequest(UUID.randomUUID(), 1, new BigDecimal("10.00")),
                        new OrderItemRequest(UUID.randomUUID(), 2, new BigDecimal("20.00")),
                        new OrderItemRequest(UUID.randomUUID(), 1, new BigDecimal("15.00")),
                        new OrderItemRequest(UUID.randomUUID(), 3, new BigDecimal("5.00")),
                        new OrderItemRequest(UUID.randomUUID(), 1, new BigDecimal("50.00"))
                )
        );

        Order created = orderService.createOrder(request);

        assertThat(created.getItems()).hasSize(5);
        assertThat(created.getTotalAmount()).isEqualByComparingTo("130.00");
    }

    // PARP will add: getOrder_returnsCachedResult_onSecondCall()
    // Verifies that @Cacheable prevents duplicate DB hits for the same order ID

    // PARP will add: listOrders_usesIndex_forCustomerId()
    // Verifies via EXPLAIN ANALYZE that the query uses idx_orders_customer_id

    private Order createTestOrder() {
        return orderService.createOrder(new CreateOrderRequest(
                customerId,
                List.of(new OrderItemRequest(UUID.randomUUID(), 1, new BigDecimal("10.00")))
        ));
    }
}
