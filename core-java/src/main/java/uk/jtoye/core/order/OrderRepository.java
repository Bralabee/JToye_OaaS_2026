package uk.jtoye.core.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Order entities.
 * All queries are automatically tenant-scoped via RLS policies.
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * Find orders by status (tenant-scoped automatically).
     */
    List<Order> findByStatus(OrderStatus status);

    /**
     * Find orders by shop ID (tenant-scoped automatically).
     */
    List<Order> findByShopId(UUID shopId);

    /**
     * Find order by order number (tenant-scoped automatically).
     */
    Optional<Order> findByOrderNumber(String orderNumber);

    /**
     * Find orders by customer ID (tenant-scoped automatically).
     */
    List<Order> findByCustomerId(UUID customerId);

    /**
     * Find order by order number and customer email.
     * Used for guest order tracking — RLS policy requires matching session variables.
     */
    Optional<Order> findByOrderNumberAndCustomerEmail(String orderNumber, String customerEmail);

    /**
     * Find all orders by customer email, most recent first.
     * Used for customer order history — RLS policy requires matching session variable.
     */
    List<Order> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail);
}
