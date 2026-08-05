package uk.jtoye.core.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Refund} entities.
 *
 * <p>All queries are tenant-scoped automatically via the {@code refunds_tenant_policy}
 * RLS policy (V36) — no explicit tenant predicate needed in JPQL.
 */
@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    /**
     * Endpoint-level idempotency lookup. RefundService consults this to short-
     * circuit on a client-supplied {@code X-Idempotency-Key} replay.
     */
    Optional<Refund> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /**
     * Refund history for an order, newest first. Used by the detail page and
     * by the already-REFUNDED short-circuit to return the latest refund.
     */
    List<Refund> findByOrderIdOrderByRequestedAtDesc(UUID orderId);

    /**
     * The same history for MANY orders in one query (#564).
     *
     * <p>The kitchen board builds an {@code OrderDetailDto} per ticket, and each of those
     * carries its refund history. Fetching that per order turns an 18-ticket board into 18
     * extra queries — an N+1 hiding behind the one this change removed, which would have
     * made the endpoint look cheap from the outside while costing the same inside.
     *
     * <p>Ordering is by order and then newest-first within each order, so the caller can
     * group without re-sorting. RLS scopes rows to the tenant exactly as the single-order
     * variant does.
     */
    List<Refund> findByOrderIdInOrderByOrderIdAscRequestedAtDesc(Collection<UUID> orderIds);

    /**
     * Inverse lookup used by the Stripe webhook handler when {@code refund_id}
     * is missing from metadata (defensive — pre-V36 refunds will not have it).
     */
    Optional<Refund> findByStripeRefundId(String stripeRefundId);

    /**
     * Sum of refund amounts for an order across non-terminal-failure statuses.
     * Used to compute remaining-refundable when a partial refund request comes
     * in. Returns 0 for orders with no refund history.
     *
     * <p>Excluded statuses: {@code failed}, {@code canceled} — they leave the
     * money on the original charge. Included statuses: {@code CREATING},
     * {@code pending}, {@code requires_action}, {@code succeeded} — all of
     * these have funds in flight or settled against the charge.
     */
    @Query("SELECT COALESCE(SUM(r.amountPennies), 0L) FROM Refund r " +
           "WHERE r.orderId = :orderId AND r.status IN " +
           "(uk.jtoye.core.payment.RefundStatus.CREATING, " +
           " uk.jtoye.core.payment.RefundStatus.pending, " +
           " uk.jtoye.core.payment.RefundStatus.requires_action, " +
           " uk.jtoye.core.payment.RefundStatus.succeeded)")
    long sumLiveAmountByOrderId(@Param("orderId") UUID orderId);
}
