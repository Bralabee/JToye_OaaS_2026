package uk.jtoye.core.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    Optional<Order> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /**
     * GDPR Article-17 scrub of pre-erasure PII from the append-only {@code orders_aud}
     * Envers history (Issue #84 [P1-2]). Redacts the subject's rows across BOTH the
     * customer-linked path (customer_id) AND the guest-order path (customer_email),
     * matching the live-row email sweep in GdprService.
     *
     * <p>{@code tenant_id} is an explicit WHERE predicate — mandatory defense-in-depth
     * per the multi-tenancy constraint: a native UPDATE on an {@code _aud} table must
     * never rely on RLS alone. The V42 {@code orders_aud_update_policy} gates the same
     * scope at the policy layer.
     *
     * @return number of audit rows scrubbed
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE orders_aud SET customer_name = :redacted, customer_email = NULL, "
            + "customer_phone = NULL, notes = NULL "
            + "WHERE tenant_id = :tenantId AND (customer_id = :customerId OR customer_email = :email)",
            nativeQuery = true)
    int scrubOrdersAudit(@Param("tenantId") UUID tenantId,
                         @Param("customerId") UUID customerId,
                         @Param("email") String email,
                         @Param("redacted") String redacted);
}
