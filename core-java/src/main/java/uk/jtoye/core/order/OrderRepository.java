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
     *
     * <p>Phase 37-10 (D-10): a VOIDED order is excluded. A void is columns, not a status, so a voided
     * CONFIRMED order still has a kitchen status; without {@code VoidedAtIsNull} it would stay on the
     * board as a ticket nobody should cook.
     */
    Page<Order> findByShopIdAndStatusInAndVoidedAtIsNull(UUID shopId, Collection<OrderStatus> statuses,
                                                         Pageable pageable);

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

    /**
     * V87 (ADR-0007 / GAP-GTM-03): Consolidated kitchen "Cook Sheet" queries.
     * Fetches active, non-voided orders with line items for a shop, optionally narrowed
     * to a target scheduled date or scheduled fulfilment slot.
     */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items "
            + "WHERE o.shopId = :shopId AND o.status IN :statuses AND o.voidedAt IS NULL "
            + "ORDER BY o.createdAt ASC")
    List<Order> findCookSheetOrdersByShop(@Param("shopId") UUID shopId,
                                          @Param("statuses") Collection<OrderStatus> statuses);

    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items "
            + "WHERE o.shopId = :shopId AND o.status IN :statuses AND o.voidedAt IS NULL "
            + "AND o.scheduledForDate = :scheduledForDate "
            + "ORDER BY o.createdAt ASC")
    List<Order> findCookSheetOrdersByShopAndDate(@Param("shopId") UUID shopId,
                                                 @Param("statuses") Collection<OrderStatus> statuses,
                                                 @Param("scheduledForDate") java.time.LocalDate scheduledForDate);

    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items "
            + "WHERE o.shopId = :shopId AND o.status IN :statuses AND o.voidedAt IS NULL "
            + "AND o.fulfilmentSlotId = :fulfilmentSlotId "
            + "ORDER BY o.createdAt ASC")
    List<Order> findCookSheetOrdersByShopAndSlot(@Param("shopId") UUID shopId,
                                                 @Param("statuses") Collection<OrderStatus> statuses,
                                                 @Param("fulfilmentSlotId") UUID fulfilmentSlotId);

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
     * A subject's orders in ONE tenant, unpaged — the GDPR erasure sweep must reach them all.
     *
     * <p>The tenant predicate is explicit and is the first layer, not a courtesy: the
     * {@code orders_customer_history} / {@code orders_guest_tracking} SELECT policies let a session that
     * carries {@code app.customer_email} read that address's orders in EVERY tenant, so an email-only
     * lookup is one GUC away from reaching another tenant's rows (the #764 lesson for reviews).
     * This replaces the unpredicated {@code findByCustomerEmailOrderByCreatedAtDesc(String)} (#777).
     */
    List<Order> findByTenantIdAndCustomerEmail(UUID tenantId, String customerEmail);

    /**
     * Every distinct stored spelling of a customer address on ONE tenant's orders (#777).
     *
     * <p>The DSAR fan-out holds only a digest of the subject's address, so it compares each stored
     * spelling's {@code DsarSubjectDigest} in Java; the finders are exact-match, so every spelling that
     * normalises to the subject (case, surrounding whitespace) has to be found and erased separately.
     */
    @Query("SELECT DISTINCT o.customerEmail FROM Order o "
            + "WHERE o.tenantId = :tenantId AND o.customerEmail IS NOT NULL")
    List<String> findDistinctCustomerEmailsByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Find orders by customer email, most recent first, paginated.
     * Used for the public customer order-history endpoint — RLS policy
     * requires matching {@code app.customer_email} session variable.
     */
    Page<Order> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail, Pageable pageable);

    Optional<Order> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /**
     * How many OPEN cash orders one email address holds at a shop (Phase 37-27, D-18, UXT-041).
     *
     * <p>The predicates repeat V81's {@code idx_orders_open_cash_email} verbatim — open status, cash
     * ({@code payment_status = 'NONE'}, the COD branch), not voided, {@code lower(customer_email)} — so
     * the planner answers from the partial index. {@code tenant_id} is an explicit predicate beside RLS,
     * as every native query here carries. The caller ({@code CashOrderCaps}) passes the address already
     * trimmed and lower-cased.
     */
    @Query(value = "SELECT count(*) FROM orders WHERE tenant_id = :tenantId AND shop_id = :shopId "
            + "AND lower(customer_email) = :email "
            + "AND status IN ('PENDING', 'CONFIRMED', 'PREPARING', 'READY') "
            + "AND payment_status = 'NONE' AND voided_at IS NULL",
            nativeQuery = true)
    long countOpenCashOrdersByEmail(@Param("tenantId") UUID tenantId,
                                    @Param("shopId") UUID shopId,
                                    @Param("email") String email);

    /**
     * How many OPEN cash orders one client address holds at a shop (Phase 37-28, D-18 / D-27, UXT-041),
     * counted on the keyed digest {@code orders.client_ip_digest} — never on an address, which is stored
     * nowhere. The predicates repeat V81's {@code idx_orders_open_cash_ip} verbatim (open status, cash,
     * not voided, the digest) so the planner answers from the partial index; {@code tenant_id} is an
     * explicit predicate beside RLS. The caller ({@code CashOrderCaps}) passes the digest
     * {@code ClientIpDigester} computed for the request.
     */
    @Query(value = "SELECT count(*) FROM orders WHERE tenant_id = :tenantId AND shop_id = :shopId "
            + "AND client_ip_digest = :digest "
            + "AND status IN ('PENDING', 'CONFIRMED', 'PREPARING', 'READY') "
            + "AND payment_status = 'NONE' AND voided_at IS NULL",
            nativeQuery = true)
    long countOpenCashOrdersByClientIpDigest(@Param("tenantId") UUID tenantId,
                                             @Param("shopId") UUID shopId,
                                             @Param("digest") String digest);

    /**
     * The customer phones of every OPEN cash order at a shop (Phase 37-27, D-18), one entry per order,
     * as stored. Phone normalisation (digits only, UK 0 -> 44) lives in {@code CashOrderCaps}, which
     * folds these and counts the matches: the stored spellings differ, so no SQL equality can count
     * them, and an expression index would be a second normaliser to hold equal to the Java one.
     *
     * <p>Same predicates as V81's {@code idx_orders_open_cash_phone}, so this is an index-only scan
     * over what is ON the kitchen board for one shop — bounded by the board, not by the shop's history.
     */
    @Query(value = "SELECT customer_phone FROM orders WHERE tenant_id = :tenantId AND shop_id = :shopId "
            + "AND customer_phone IS NOT NULL "
            + "AND status IN ('PENDING', 'CONFIRMED', 'PREPARING', 'READY') "
            + "AND payment_status = 'NONE' AND voided_at IS NULL",
            nativeQuery = true)
    List<String> findOpenCashOrderPhones(@Param("tenantId") UUID tenantId, @Param("shopId") UUID shopId);

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
            + "customer_phone = NULL, notes = NULL, allergy_note = NULL, "
            + "address_line1 = NULL, address_line2 = NULL, address_city = NULL, address_postcode = NULL "
            + "WHERE tenant_id = :tenantId AND (customer_id = :customerId OR customer_email = :email)",
            nativeQuery = true)
    int scrubOrdersAudit(@Param("tenantId") UUID tenantId,
                         @Param("customerId") UUID customerId,
                         @Param("email") String email,
                         @Param("redacted") String redacted);

    /**
     * The {@link #scrubOrdersAudit} scrub for a subject reached by address alone: a guest storefront
     * subject with no {@code customers} row, or a further stored spelling of a customer's address
     * (#777). Same columns, same explicit {@code tenant_id} predicate, same V42 UPDATE policy.
     *
     * @return number of audit rows scrubbed
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE orders_aud SET customer_name = :redacted, customer_email = NULL, "
            + "customer_phone = NULL, notes = NULL, allergy_note = NULL, "
            + "address_line1 = NULL, address_line2 = NULL, address_city = NULL, address_postcode = NULL "
            + "WHERE tenant_id = :tenantId AND customer_email = :email",
            nativeQuery = true)
    int scrubOrdersAuditByEmail(@Param("tenantId") UUID tenantId,
                                @Param("email") String email,
                                @Param("redacted") String redacted);
}
