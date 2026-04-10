package com.checkout.order.service;

import com.checkout.order.controller.dto.CreateOrderRequest;
import com.checkout.order.controller.dto.OrderItemRequest;
import com.checkout.order.exception.OrderNotFoundException;
import com.checkout.order.model.Order;
import com.checkout.order.model.OrderItem;
import com.checkout.order.model.OrderStatus;
import com.checkout.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    // Intentional issue: synchronous RestTemplate used for external payment service calls
    private final RestTemplate restTemplate = new RestTemplate();

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Create a new order.
     *
     * INTENTIONAL ISSUE — N+1 query:
     * For each item in the order, we call validateInventory() which hits the
     * inventory service (or DB) individually. With 5 items, that's 5 separate calls.
     * Fix: collect all productIds upfront, call a batch inventory check once.
     */
    public Order createOrder(CreateOrderRequest request) {
        log.info("Creating order for customer={}", request.customerId());

        Order order = new Order();
        order.setCustomerId(request.customerId());

        List<OrderItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        // N+1: one call per item instead of one batch call for all items
        for (OrderItemRequest itemRequest : request.items()) {
            validateInventory(itemRequest.productId(), itemRequest.quantity()); // ← N+1 here
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProductId(itemRequest.productId());
            item.setQuantity(itemRequest.quantity());
            item.setUnitPrice(itemRequest.unitPrice());
            items.add(item);
            total = total.add(itemRequest.unitPrice().multiply(BigDecimal.valueOf(itemRequest.quantity())));
        }

        order.setItems(items);
        order.setTotalAmount(total);

        Order saved = orderRepository.save(order);
        log.info("Order created id={} total={}", saved.getId(), saved.getTotalAmount());
        return saved;
    }

    /**
     * Fetch a single order by ID.
     *
     * INTENTIONAL ISSUE — no caching:
     * High-read endpoints like GET /v1/orders/{id} hit the DB on every request.
     * Fix: @Cacheable with a short TTL (e.g., 30s) for read-heavy patterns.
     */
    @Transactional(readOnly = true)
    public Order getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /**
     * List orders for a customer with pagination.
     *
     * INTENTIONAL ISSUE — missing index on customer_id:
     * findByCustomerId does a sequential scan on the orders table.
     * Fix: Add index idx_orders_customer_id (see V2 migration).
     */
    @Transactional(readOnly = true)
    public Page<Order> listOrders(UUID customerId, Pageable pageable) {
        return orderRepository.findByCustomerId(customerId, pageable);
    }

    /**
     * Cancel an order.
     *
     * INTENTIONAL ISSUE — synchronous blocking payment call:
     * notifyPaymentService() uses RestTemplate which blocks the calling thread.
     * Under load, this exhausts the thread pool.
     * Fix: Replace with WebClient (non-blocking) or async event.
     */
    public Order cancelOrder(UUID orderId) {
        Order order = getOrder(orderId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return order;
        }
        if (order.getStatus() == OrderStatus.FULFILLED) {
            throw new IllegalStateException("Cannot cancel a fulfilled order");
        }
        order.setStatus(OrderStatus.CANCELLED);
        Order saved = orderRepository.save(order);
        applicationEventPublisher.publishEvent(new OrderCancelledEvent(saved));
        return saved;
    }

    /**
     * Get orders by status.
     *
     * INTENTIONAL ISSUE — full table scan:
     * findByStatus has no index on the status column.
     * With millions of orders, this scans the entire table.
     * Fix: Add partial index on status for non-terminal statuses.
     */
    @Transactional(readOnly = true)
    public List<Order> getOrdersByStatus(OrderStatus status) {
        return orderRepository.findByStatus(status); // ← full table scan
    }

    // Simulates a per-item inventory check — the N+1 bottleneck
    private void validateInventory(UUID productId, int quantity) {
        log.debug("Checking inventory for productId={} qty={}", productId, quantity);
        // In real code: hits inventory service REST endpoint individually per product
        // Simulated delay represents network + DB round trip
    }

    // Blocking synchronous payment service notification

}
