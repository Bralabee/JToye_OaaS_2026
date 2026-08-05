package uk.jtoye.core.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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
     * Find orders by status, unpaged (tenant-scoped automatically).
     * Internal use only (ScheduledCleanupService full scan) — API paths must
     * use the paginated overload (Issue #95).
     */
    List<Order> findByStatus(OrderStatus status);

    /**
     * Find orders by status, paginated (tenant-scoped automatically).
     */
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /**
     * Find orders by shop ID, paginated (tenant-scoped automatically).
     */
    Page<Order> findByShopId(UUID shopId, Pageable pageable);

    /**
     * Find order by order number (tenant-scoped automatically).
     */
    Optional<Order> findByOrderNumber(String orderNumber);

    // Vendor-scoped access (Phase 23, VSA-02 / D-01): grant-set-narrowed read-scope
    // finders for the authenticated order lists. A non-GROUP_ADMIN sees only orders
    // whose shop_id is in their grant set — narrowed at the QUERY, never a post-hoc
    // filter. Callers guarantee a non-empty shopIds set (empty grant → deny-by-default
    // short-circuit). RLS still scopes every row to the tenant.
    Page<Order> findByShopIdIn(Collection<UUID> shopIds, Pageable pageable);

    Page<Order> findByStatusAndShopIdIn(OrderStatus status, Collection<UUID> shopIds, Pageable pageable);

    /**
     * Kitchen board query (#564): one shop's orders in the given statuses, paginated.
     *
     * <p>The board previously read this by fetching the shop's WHOLE order history a page
     * at a time and filtering for kitchen statuses in the browser — 43 lifetime rows for
     * 18 live tickets on the dev tenant, and a shop past the 20-page client bound could
     * exhaust it before reaching its live tickets. Filtering here means the result size is
     * bounded by what is ON the board, not by how long the shop has been trading.
     *
     * <p>RLS still scopes every row to the tenant; the shop-level grant check is the
     * caller's ({@code OrderService.getKitchenBoard}).
     */
    Page<Order> findByShopIdAndStatusIn(UUID shopId, Collection<OrderStatus> statuses, Pageable pageable);

    /**
     * Re-read a known page of orders with their line items attached, in ONE query (#564).
     *
     * <p><b>Why this is a second query and not an {@code @EntityGraph} on the paged one.</b>
     * A fetched {@code @OneToMany} cannot be paginated in SQL — one order yields one row
     * per item, so {@code LIMIT} would cut mid-order. Hibernate's answer is to drop the
     * limit, <b>read every matching row, and paginate in memory</b> (HHH000104). On this
     * endpoint that would be an unbounded read dressed as a paged one: the precise defect
     * #564 exists to remove, reintroduced one layer down and invisible from the response.
     *
     * <p>So the page is decided first (ids only, real SQL {@code LIMIT}), and this fetches
     * items for that page. Two queries, both bounded — instead of one query and N lazy
     * loads, or one query that quietly reads the table.
     */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id IN :ids")
    List<Order> findAllWithItemsByIdIn(@Param("ids") Collection<UUID> ids);

    Page<Order> findByCustomerIdAndShopIdIn(UUID customerId, Collection<UUID> shopIds, Pageable pageable);

    /**
     * Find orders by customer ID, unpaged (tenant-scoped automatically).
     * Internal use only (GdprService erasure must sweep ALL orders) — API
     * paths must use the paginated overload (Issue #95).
     */
    List<Order> findByCustomerId(UUID customerId);

    /**
     * Find orders by customer ID, paginated (tenant-scoped automatically).
     */
    Page<Order> findByCustomerId(UUID customerId, Pageable pageable);

    /**
     * Find order by order number and customer email.
     * Used for guest order tracking — RLS policy requires matching session variables.
     */
    Optional<Order> findByOrderNumberAndCustomerEmail(String orderNumber, String customerEmail);

    /**
     * Find all orders by customer email, most recent first, unpaged.
     * Internal use only (GdprService email sweep must cover ALL orders) — the
     * public order-history API uses the paginated overload (Issue #95).
     * RLS policy requires matching session variable.
     */
    List<Order> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail);

    /**
     * Find orders by customer email, most recent first, paginated.
     * Used for the public customer order-history endpoint — RLS policy
     * requires matching {@code app.customer_email} session variable.
     */
    Page<Order> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail, Pageable pageable);

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
            + "customer_phone = NULL, notes = NULL, "
            + "address_line1 = NULL, address_line2 = NULL, address_city = NULL, address_postcode = NULL "
            + "WHERE tenant_id = :tenantId AND (customer_id = :customerId OR customer_email = :email)",
            nativeQuery = true)
    int scrubOrdersAudit(@Param("tenantId") UUID tenantId,
                         @Param("customerId") UUID customerId,
                         @Param("email") String email,
                         @Param("redacted") String redacted);
}
