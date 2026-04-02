package com.checkout.order.repository;

import com.checkout.order.model.Order;
import com.checkout.order.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    // Intentional issue: no @Query with JOIN FETCH → triggers N+1 when items are accessed
    Page<Order> findByCustomerId(UUID customerId, Pageable pageable);

    // Intentional issue: full table scan — no index on status column
    List<Order> findByStatus(OrderStatus status);

    // Intentional issue: inefficient count query used in pagination without index
    @Query("SELECT COUNT(o) FROM Order o WHERE o.customerId = :customerId")
    long countByCustomerId(UUID customerId);
}
