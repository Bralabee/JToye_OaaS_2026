package uk.jtoye.core.config;

import com.stripe.exception.StripeException;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;
import uk.jtoye.core.payment.PaymentService;
import uk.jtoye.core.security.TenantContext;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Scheduled cleanup jobs for housekeeping.
 * Runs daily to clean up stale data that would otherwise accumulate.
 *
 * <p>issue #1038: a storefront card DRAFT carries a live Stripe PaymentIntent. Deleting
 * the order while that intent is still payable left it orphaned at Stripe (7 such intents
 * on staging, 2026-10-24): a customer completing it would be charged for an order that no
 * longer exists, and the kitchen would see nothing. So a card draft's intent is cancelled
 * at Stripe FIRST and the order deleted only once Stripe reports it canceled. The Stripe
 * network call is made with no transaction open, so no DB connection or row lock is held
 * across a Stripe round trip, and each delete is its own short transaction under the
 * tenant GUC. Any draft with money on it, or in doubt, is kept and retried at the next run.
 */
@Service
public class ScheduledCleanupService {
    private static final Logger log = LoggerFactory.getLogger(ScheduledCleanupService.class);

    private final OrderRepository orderRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final PaymentService paymentService;

    @Value("${cleanup.stale-draft-hours:24}")
    private int staleDraftHours;

    public ScheduledCleanupService(OrderRepository orderRepository,
                                   EntityManager entityManager,
                                   PlatformTransactionManager transactionManager,
                                   PaymentService paymentService) {
        this.orderRepository = orderRepository;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.paymentService = paymentService;
    }

    /** A stale card draft to cancel at Stripe, carried out of the read transaction by value. */
    private record CardDraft(UUID orderId, String orderNumber, String paymentIntentId, String connectedAccountId) {
    }

    /** What the tenant-pinned read transaction found and did. */
    private record TenantScan(int deletedWithoutPayment, List<CardDraft> cardDrafts, int kept) {
        static final TenantScan EMPTY = new TenantScan(0, List.of(), 0);
    }

    /**
     * Delete DRAFT orders older than configured threshold (default 24 hours).
     * DRAFT orders are incomplete — the customer abandoned checkout before payment.
     * Runs daily at 03:00 UTC.
     *
     * <p>QA-council M1: each tenant is cleaned in its OWN transaction (below), not
     * one transaction spanning all tenants. The RLS tenant GUC is transaction-local
     * (TenantSetLocalAspect uses {@code set_config(...,true)}); under a single
     * transaction, tenant A's deferred cascade delete of {@code order_items}
     * flushed AFTER the GUC had switched to tenant B, so FORCE-RLS filtered those
     * rows to 0 → {@code StaleStateException} → the whole job rolled back and
     * cleaned nothing. Per-tenant transactions flush each tenant's deletes under
     * its own GUC before the next tenant begins.
     *
     * <p>issue #1038: a draft with no payment is deleted as before; a card draft
     * ({@code pi_} reference) is deleted only after its PaymentIntent is cancelled at
     * Stripe, in a transaction of its own (see {@link #cleanupTenant}).
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupStaleDraftOrders() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(staleDraftHours);

        List<UUID> tenantIds = readTenantIds();

        int totalCleaned = 0;
        for (UUID tenantId : tenantIds) {
            totalCleaned += cleanupTenant(tenantId, cutoff);
        }

        if (totalCleaned == 0) {
            log.debug("No stale DRAFT orders to clean up across {} tenants", tenantIds.size());
        } else {
            log.info("Total cleaned: {} stale DRAFT orders across {} tenants (threshold: {} hours)",
                    totalCleaned, tenantIds.size(), staleDraftHours);
        }
    }

    private List<UUID> readTenantIds() {
        return transactionTemplate.execute(status -> {
            @SuppressWarnings("unchecked")
            List<UUID> ids = entityManager
                    .createNativeQuery("SELECT id FROM tenants")
                    .getResultList();
            return ids;
        });
    }

    /**
     * Clean one tenant's stale drafts. TenantContext is set for all three phases so
     * TenantSetLocalAspect applies the correct RLS GUC to every repository op inside a
     * transaction, and cleared afterwards. TransactionTemplate (rather than a
     * {@code @Transactional} helper method) is used deliberately to avoid the Spring
     * self-invocation proxy trap, which would otherwise start no transaction at all and
     * run the query with a NULL tenant.
     *
     * <ol>
     *   <li>Phase A, one read-write transaction: select the stale drafts, delete those
     *       with no payment, and resolve each card draft's direct-charge account HERE,
     *       because the routing reads {@code shops} and {@code vendor_onboarding} under
     *       FORCE RLS and resolves empty outside a pinned transaction (issue #1038).</li>
     *   <li>Phase B, no transaction: cancel each card draft's intent at Stripe.</li>
     *   <li>Phase C, one short transaction per cancelled draft: re-read it and delete it
     *       only if it is still a DRAFT with the same intent.</li>
     * </ol>
     */
    private int cleanupTenant(UUID tenantId, OffsetDateTime cutoff) {
        TenantContext.set(tenantId);
        try {
            TenantScan scan = transactionTemplate.execute(status -> scanTenant(tenantId, cutoff));
            if (scan == null) {
                scan = TenantScan.EMPTY;
            }

            int kept = scan.kept();
            List<CardDraft> cancelled = cancelAtStripe(tenantId, scan.cardDrafts());
            kept += scan.cardDrafts().size() - cancelled.size();

            int deletedAfterCancel = 0;
            for (CardDraft card : cancelled) {
                if (deleteCancelledDraft(card)) {
                    deletedAfterCancel++;
                } else {
                    kept++;
                }
            }

            int deleted = scan.deletedWithoutPayment() + deletedAfterCancel;
            if (deleted > 0 || kept > 0) {
                log.info("Cleaned up {} stale DRAFT orders for tenant {} "
                                + "(deletedWithoutPayment={} deletedAfterCancel={} kept={})",
                        deleted, tenantId, scan.deletedWithoutPayment(), deletedAfterCancel, kept);
            }
            return deleted;
        } finally {
            TenantContext.clear();
        }
    }

    /** Phase A: runs inside the tenant-pinned transaction. */
    private TenantScan scanTenant(UUID tenantId, OffsetDateTime cutoff) {
        List<Order> staleDrafts = orderRepository.findByStatus(OrderStatus.DRAFT).stream()
                .filter(o -> o.getCreatedAt().isBefore(cutoff))
                .toList();
        if (staleDrafts.isEmpty()) {
            return TenantScan.EMPTY;
        }

        List<Order> withoutPayment = new ArrayList<>();
        List<CardDraft> cardDrafts = new ArrayList<>();
        int kept = 0;
        for (Order order : staleDrafts) {
            String reference = order.getPaymentReference();
            if (reference == null && isNoPaymentStatus(order.getPaymentStatus())) {
                withoutPayment.add(order);
            } else if (reference != null && reference.startsWith("pi_")) {
                cardDrafts.add(new CardDraft(order.getId(), order.getOrderNumber(), reference,
                        paymentService.directChargeAccountFor(order).orElse(null)));
            } else {
                // Nothing written today produces this (both writers store a pi_ id); the
                // safe default for anything future is to keep the order and say so.
                kept++;
                log.warn("event=stale_draft_kept reason=unrecognised_payment order={} paymentStatus={} "
                                + "referencePrefix={} tenant={}",
                        order.getOrderNumber(), order.getPaymentStatus(), referencePrefix(reference), tenantId);
            }
        }

        if (!withoutPayment.isEmpty()) {
            orderRepository.deleteAll(withoutPayment);
        }
        return new TenantScan(withoutPayment.size(), cardDrafts, kept);
    }

    /**
     * NONE is the entity default; a NULL can only be a row written before the V22 column
     * default or an explicit null, and with no reference it carries no Stripe object either,
     * so it is cleaned exactly as before (issue #1038 keeps the no-payment path unchanged).
     */
    private static boolean isNoPaymentStatus(PaymentStatus status) {
        return status == null || status == PaymentStatus.NONE;
    }

    private static String referencePrefix(String reference) {
        if (reference == null) {
            return "none";
        }
        int underscore = reference.indexOf('_');
        return underscore > 0 ? reference.substring(0, underscore) : "none";
    }

    /**
     * Phase B: runs with NO transaction open (issue #1038). Returns the drafts whose intent
     * Stripe reports canceled; every other draft is kept with a WARN and retried next run.
     * The call goes through the injected PaymentService bean, so the stripe circuit breaker
     * applies; an open breaker throws CallNotPermittedException, a RuntimeException.
     */
    private List<CardDraft> cancelAtStripe(UUID tenantId, List<CardDraft> cardDrafts) {
        if (cardDrafts.isEmpty()) {
            return List.of();
        }
        if (!paymentService.isConfigured()) {
            log.warn("event=stale_draft_kept reason=stripe_not_configured count={} tenant={}",
                    cardDrafts.size(), tenantId);
            return List.of();
        }
        List<CardDraft> cancelled = new ArrayList<>();
        for (CardDraft card : cardDrafts) {
            String stripeStatus;
            try {
                stripeStatus = paymentService.cancelAbandonedPaymentIntent(
                        card.paymentIntentId(), card.connectedAccountId());
            } catch (StripeException | RuntimeException e) {
                String code = (e instanceof StripeException se) ? " code=" + se.getCode() : "";
                log.warn("event=stale_draft_kept reason=stripe_error order={} intent={} error={}{}",
                        card.orderNumber(), card.paymentIntentId(), e.getClass().getSimpleName(), code);
                continue;
            }
            if (PaymentService.STRIPE_STATUS_CANCELED.equals(stripeStatus)) {
                cancelled.add(card);
            } else {
                log.warn("event=stale_draft_kept reason=intent_not_cancelable status={} order={} intent={}",
                        stripeStatus, card.orderNumber(), card.paymentIntentId());
            }
        }
        return cancelled;
    }

    /**
     * Phase C: one short transaction per cancelled draft, under the same TenantContext.
     * The re-read guards against a webhook or a vendor having moved the order on while
     * Stripe was being called; a failure (for example Order's optimistic lock) keeps this
     * order and never aborts the tenant or the run.
     */
    private boolean deleteCancelledDraft(CardDraft card) {
        try {
            Boolean deleted = transactionTemplate.execute(status -> {
                Optional<Order> current = orderRepository.findById(card.orderId());
                if (current.isPresent()
                        && current.get().getStatus() == OrderStatus.DRAFT
                        && Objects.equals(current.get().getPaymentReference(), card.paymentIntentId())) {
                    orderRepository.delete(current.get());
                    return true;
                }
                log.warn("event=stale_draft_kept reason=changed_after_cancel order={}", card.orderNumber());
                return false;
            });
            return Boolean.TRUE.equals(deleted);
        } catch (RuntimeException e) {
            log.warn("event=stale_draft_kept reason=delete_failed order={} error={}",
                    card.orderNumber(), e.getClass().getSimpleName());
            return false;
        }
    }
}
