package uk.jtoye.core.payment;

import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.InvalidStateTransitionException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderEvent;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStateMachineService;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;
import uk.jtoye.core.payment.dto.CreateRefundRequest;
import uk.jtoye.core.payment.dto.RefundDto;
import uk.jtoye.core.security.TenantContext;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 17 VOPS-02 — refund creation with stored-first idempotency.
 *
 * <p>Eight-step flow per UC-1 LOCKED:
 * <ol>
 *   <li>Endpoint-level dedup via client-supplied {@code X-Idempotency-Key}.</li>
 *   <li>Load + validate order.</li>
 *   <li>Already-REFUNDED short-circuit (idempotency lives here, not the
 *       state machine).</li>
 *   <li>Refundable-status check.</li>
 *   <li>Remaining-refundable amount check.</li>
 *   <li>Insert {@code Refund(status=CREATING)} BEFORE the Stripe call.</li>
 *   <li>Call {@code Stripe.Refund.create} with stored idempotency key.</li>
 *   <li>Update Refund + transition order via state machine.</li>
 * </ol>
 *
 * <p>On {@link StripeException} the row is updated to {@code status=failed}
 * via a {@code REQUIRES_NEW} transaction so the failure persists even when
 * the outer transaction rolls back — necessary for reconciliation.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private static final Set<OrderStatus> REFUNDABLE_STATUSES = EnumSet.of(
            OrderStatus.CONFIRMED,
            OrderStatus.PREPARING,
            OrderStatus.READY,
            OrderStatus.COMPLETED
    );

    private final RefundRepository refundRepository;
    private final OrderRepository orderRepository;
    private final OrderStateMachineService stateMachineService;
    private final RefundMapper refundMapper;
    private final StripeRefundClient stripeRefundClient;
    private final RefundEventPublisher refundEventPublisher;

    public RefundService(RefundRepository refundRepository,
                         OrderRepository orderRepository,
                         OrderStateMachineService stateMachineService,
                         RefundMapper refundMapper,
                         StripeRefundClient stripeRefundClient,
                         RefundEventPublisher refundEventPublisher) {
        this.refundRepository = refundRepository;
        this.orderRepository = orderRepository;
        this.stateMachineService = stateMachineService;
        this.refundMapper = refundMapper;
        this.stripeRefundClient = stripeRefundClient;
        this.refundEventPublisher = refundEventPublisher;
    }

    /**
     * Create a refund for an order. See class Javadoc for the 8-step flow.
     *
     * @param orderId               order to refund
     * @param request               refund request payload
     * @param clientIdempotencyKey  optional X-Idempotency-Key header value;
     *                              when present, a replay returns the existing
     *                              Refund without calling Stripe
     * @return RefundDto for either the newly-created or replayed Refund
     * @throws StripeException if Stripe rejects the create call
     */
    @Transactional
    public RefundDto createRefund(UUID orderId, CreateRefundRequest request, String clientIdempotencyKey)
            throws StripeException {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        // Step 1 — Endpoint-level dedup (X-Idempotency-Key)
        if (clientIdempotencyKey != null && !clientIdempotencyKey.isBlank()) {
            Optional<Refund> existing = refundRepository
                    .findByTenantIdAndIdempotencyKey(tenantId, clientIdempotencyKey);
            if (existing.isPresent()) {
                log.info("Refund replay via X-Idempotency-Key={}: returning existing refund {}",
                        clientIdempotencyKey, existing.get().getId());
                return refundMapper.toDto(existing.get());
            }
        }

        // Step 2 — Load + validate order
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        // Step 3 — Already-REFUNDED idempotency (UC LOCKED)
        if (order.getStatus() == OrderStatus.REFUNDED) {
            List<Refund> existing = refundRepository.findByOrderIdOrderByRequestedAtDesc(orderId);
            if (existing.isEmpty()) {
                throw new IllegalStateException(
                        "Order " + orderId + " is REFUNDED but has no Refund row — data drift");
            }
            log.info("Order {} already REFUNDED — short-circuit, returning latest refund {}",
                    orderId, existing.get(0).getId());
            return refundMapper.toDto(existing.get(0));
        }

        // Step 4 — Refundable status check
        if (!REFUNDABLE_STATUSES.contains(order.getStatus())) {
            throw new InvalidStateTransitionException(
                    "Cannot refund order in status " + order.getStatus()
                    + " — refunds require CONFIRMED, PREPARING, READY, or COMPLETED");
        }

        if (order.getPaymentReference() == null || order.getPaymentReference().isBlank()) {
            throw new InvalidStateTransitionException(
                    "Cannot refund order " + orderId + " — no Stripe payment_intent on record");
        }

        // Step 5 — Compute remaining + amount
        long alreadyRefunded = refundRepository.sumLiveAmountByOrderId(orderId);
        long remaining = order.getTotalAmountPennies() - alreadyRefunded;
        if (remaining <= 0) {
            throw new IllegalArgumentException(
                    "Order " + orderId + " has nothing left to refund (remaining=" + remaining + ")");
        }
        long requested = request.amountPennies() != null ? request.amountPennies() : remaining;
        if (requested <= 0) {
            throw new IllegalArgumentException("amountPennies must be positive");
        }
        if (requested > remaining) {
            throw new IllegalArgumentException(
                    "amountPennies " + requested + " exceeds remaining refundable " + remaining);
        }

        // Step 6 — Stored-first idempotency: persist BEFORE Stripe.
        // Server-generated key (32 hex chars) when client did not supply one.
        String serverIdemKey = clientIdempotencyKey != null && !clientIdempotencyKey.isBlank()
                ? clientIdempotencyKey
                : UUID.randomUUID().toString().replace("-", "");

        Refund refund = new Refund(
                tenantId,
                orderId,
                order.getPaymentReference(),
                serverIdemKey,
                requested,
                request.reason(),
                request.note()
        );
        refund = refundRepository.saveAndFlush(refund);
        log.info("Created Refund row {} (status=CREATING) for order {} amount={}",
                refund.getId(), orderId, requested);

        // Step 7 — Call Stripe with the stored key
        com.stripe.model.Refund stripeRefund;
        try {
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(order.getPaymentReference())
                    .setAmount(requested)
                    .setReason(RefundReason.toStripeReason(request.reason()))
                    .putMetadata("refund_id", refund.getId().toString())
                    .putMetadata("tenant_id", tenantId.toString())
                    .putMetadata("order_id", orderId.toString())
                    .build();
            RequestOptions opts = RequestOptions.builder()
                    .setIdempotencyKey(serverIdemKey)
                    .build();
            stripeRefund = stripeRefundClient.create(params, opts);
        } catch (StripeException ex) {
            log.warn("Stripe.Refund.create failed for refund {}: {}", refund.getId(), ex.getMessage());
            markRefundFailed(refund.getId(), ex.getMessage());
            throw ex;
        }

        // Step 8 — Update Refund + transition order
        refund.setStripeRefundId(stripeRefund.getId());
        refund.setStatus(parseStripeStatus(stripeRefund.getStatus()));
        refund.setUpdatedAt(OffsetDateTime.now());
        refund = refundRepository.save(refund);

        OrderStatus newStatus = stateMachineService.sendEvent(
                orderId, order.getStatus(), OrderEvent.REFUND_REQUESTED);
        order.setStatus(newStatus);
        order.setPaymentStatus(PaymentStatus.REFUNDED);
        order.setUpdatedAt(OffsetDateTime.now());
        orderRepository.save(order);

        log.info("Refund {} succeeded for order {} (Stripe={}, amount={})",
                refund.getId(), orderId, stripeRefund.getId(), requested);

        return refundMapper.toDto(refund);
    }

    /**
     * Persist a Stripe failure to the Refund row in a brand-new transaction so
     * the row is durable even when the outer @Transactional method rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRefundFailed(UUID refundId, String reason) {
        refundRepository.findById(refundId).ifPresent(r -> {
            r.setStatus(RefundStatus.failed);
            r.setFailureReason(reason);
            r.setUpdatedAt(OffsetDateTime.now());
            refundRepository.save(r);
        });
    }

    /**
     * Read-only refund history for an order (newest-first).
     */
    @Transactional(readOnly = true)
    public List<RefundDto> findByOrderId(UUID orderId) {
        return refundMapper.toDtoList(refundRepository.findByOrderIdOrderByRequestedAtDesc(orderId));
    }

    /**
     * Single-refund lookup for {@code GET /api/v1/orders/{orderId}/refunds/{refundId}}
     * — the resource the POST endpoint's Location header points at (issue #97).
     *
     * <p>Tenant scoping is enforced by the {@code refunds_tenant_policy} RLS
     * policy (V36); the {@code orderId} filter additionally guarantees the
     * URI hierarchy is honest — a refund fetched under the wrong order 404s
     * rather than leaking through a flat by-id lookup.
     *
     * @throws ResourceNotFoundException when no refund with that id exists
     *         under the given order (or the RLS policy hides it)
     */
    @Transactional(readOnly = true)
    public RefundDto findRefund(UUID orderId, UUID refundId) {
        Refund refund = refundRepository.findById(refundId)
                .filter(r -> orderId.equals(r.getOrderId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Refund not found: " + refundId + " for order " + orderId));
        return refundMapper.toDto(refund);
    }

    /**
     * Webhook handler for refund.* Stripe events. Called from
     * {@link PaymentService#handleWebhookEvent} AFTER the Phase 16.1
     * processed_stripe_events dedup guard, inside the same @Transactional.
     *
     * <p>Looks up the local Refund row by metadata.refund_id (set by us on
     * create) — falls back to lookup-by-stripe_refund_id for refunds we
     * already updated. Applies the wire status and persists.
     *
     * <p>Does NOT call the state machine — the order-status transition
     * happens on initial create (in {@link #createRefund}); the webhook only
     * updates the Refund row's wire status. Double-transitioning would throw
     * {@link InvalidStateTransitionException}.
     */
    public void handleStripeRefundEvent(com.stripe.model.Event event) {
        com.stripe.model.Refund stripeRefund = (com.stripe.model.Refund) event.getDataObjectDeserializer()
                .getObject()
                .orElseThrow(() -> new IllegalStateException(
                        "Failed to deserialize Refund object from event " + event.getId()));

        java.util.Map<String, String> metadata = stripeRefund.getMetadata();
        String localRefundIdStr = metadata != null ? metadata.get("refund_id") : null;
        String tenantIdStr      = metadata != null ? metadata.get("tenant_id")  : null;

        // BL-02 / BL-03 / BL-04 fix — externally-issued refunds (no metadata)
        // are explicitly out-of-scope per UC-5 LOCKED. Trying to look up the
        // local row by stripe_refund_id without TenantContext set means the
        // RLS predicate evaluates to UNKNOWN and the SELECT silently returns
        // empty — the very case this fallback was designed to handle. Any
        // subsequent INSERT into payment_event_outbox would also be rejected
        // by RLS (V33 ENABLE+FORCE).
        //
        // Log the event and return so the dedup row stays committed and
        // future redeliveries of the same event.id short-circuit at the
        // processed_stripe_events guard. If we ever ship internally-issued
        // refunds without our own metadata, that is a bug at create time —
        // not something this handler should paper over.
        if (localRefundIdStr == null || tenantIdStr == null) {
            log.warn("Refund webhook {} for Stripe refund {} is missing required metadata "
                   + "(refund_id={}, tenant_id={}) — ignoring per UC-5 LOCKED "
                   + "(externally-issued refunds out of scope)",
                    event.getId(), stripeRefund.getId(), localRefundIdStr, tenantIdStr);
            return;
        }

        TenantContext.set(UUID.fromString(tenantIdStr));
        try {
            UUID refundId = UUID.fromString(localRefundIdStr);
            // BL-04 — fail loud on missing-row in the metadata-bearing path.
            // Throwing rolls back the @Transactional in PaymentService (and
            // with it the processed_stripe_events dedup row), so Stripe
            // retries the event after our local row catches up. If the row
            // is permanently absent, Stripe gives up after its retry budget
            // and we keep the failure visible in logs.
            Refund refund = refundRepository.findById(refundId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Refund not found for webhook event " + event.getId() + ": " + refundId));
            applyStripeStatusToRefund(refund, stripeRefund, event.getType());
        } finally {
            TenantContext.clear();
        }
    }

    private void applyStripeStatusToRefund(Refund refund,
                                           com.stripe.model.Refund stripeRefund,
                                           String eventType) {
        RefundStatus newStatus = parseStripeStatus(stripeRefund.getStatus());
        refund.setStripeRefundId(stripeRefund.getId());
        refund.setStatus(newStatus);
        if (stripeRefund.getFailureReason() != null) {
            refund.setFailureReason(stripeRefund.getFailureReason());
        }
        refund.setUpdatedAt(OffsetDateTime.now());
        // WR-03: bind the saved instance to a distinct local rather than
        // overwriting the parameter. JPA save returns the merged managed
        // entity; using `persisted` from here on makes the "always read
        // post-save state" contract self-documenting and survives a future
        // refactor that passes a detached refund.
        Refund persisted = refundRepository.save(refund);

        // Look up the order to populate event payload — best effort, do not
        // fail the webhook if the order has been deleted.
        Order order = orderRepository.findById(persisted.getOrderId()).orElse(null);
        if (order == null) {
            // WR-06: surface the silent-null-orderNumber path so operators
            // can see when a refund references a missing/unreachable order.
            log.warn("Refund {} references missing/unreachable order {} — event will publish "
                   + "with null orderNumber", persisted.getId(), persisted.getOrderId());
        }
        String orderNumber = order != null ? order.getOrderNumber() : null;

        if ("refund.failed".equals(eventType) || newStatus == RefundStatus.failed) {
            refundEventPublisher.publishRefundFailed(
                    persisted.getId(), persisted.getOrderId(), persisted.getTenantId(), orderNumber,
                    persisted.getStripeRefundId(), persisted.getAmountPennies(), persisted.getCurrency(),
                    persisted.getFailureReason());
        } else if (newStatus == RefundStatus.succeeded) {
            refundEventPublisher.publishRefundSucceeded(
                    persisted.getId(), persisted.getOrderId(), persisted.getTenantId(), orderNumber,
                    persisted.getStripeRefundId(), persisted.getAmountPennies(), persisted.getCurrency(),
                    newStatus.name());
        } else {
            refundEventPublisher.publishRefundUpdated(
                    persisted.getId(), persisted.getOrderId(), persisted.getTenantId(), orderNumber,
                    persisted.getStripeRefundId(), persisted.getAmountPennies(), persisted.getCurrency(),
                    newStatus.name());
        }

        log.info("Applied Stripe refund event {} -> refund {} status={}",
                eventType, persisted.getId(), newStatus);
    }

    private static RefundStatus parseStripeStatus(String wire) {
        if (wire == null) {
            return RefundStatus.pending;
        }
        try {
            return RefundStatus.valueOf(wire);
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown Stripe refund status '{}' — defaulting to pending", wire);
            return RefundStatus.pending;
        }
    }
}
