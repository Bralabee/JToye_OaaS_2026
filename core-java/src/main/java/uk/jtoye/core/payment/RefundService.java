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
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * <p>The staff path inserts the row and calls Stripe in ONE transaction, so a Stripe error rolls the row
 * back with it (the {@code failed} marking is then a no-op in a new transaction that cannot see it). The
 * customer self-cancel path (#868) commits its row BEFORE Stripe is called: see {@link #reserveCancelRefunds}
 * and {@link #issueCancelRefunds}.
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
    private final StripeConnectService stripeConnectService;
    /** Null only through the legacy constructors: the refund is then tagged environment=unset. */
    private final StripeProperties stripeProperties;
    /** This bean's own proxy, so a self-call still gets its transaction advice (null outside Spring). */
    private RefundService self;

    public RefundService(RefundRepository refundRepository,
                         OrderRepository orderRepository,
                         OrderStateMachineService stateMachineService,
                         RefundMapper refundMapper,
                         StripeRefundClient stripeRefundClient,
                         RefundEventPublisher refundEventPublisher) {
        this(refundRepository, orderRepository, stateMachineService,
                refundMapper, stripeRefundClient, refundEventPublisher, null);
    }

    public RefundService(RefundRepository refundRepository,
                         OrderRepository orderRepository,
                         OrderStateMachineService stateMachineService,
                         RefundMapper refundMapper,
                         StripeRefundClient stripeRefundClient,
                         RefundEventPublisher refundEventPublisher,
                         StripeConnectService stripeConnectService) {
        this(refundRepository, orderRepository, stateMachineService, refundMapper,
                stripeRefundClient, refundEventPublisher, stripeConnectService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RefundService(RefundRepository refundRepository,
                         OrderRepository orderRepository,
                         OrderStateMachineService stateMachineService,
                         RefundMapper refundMapper,
                         StripeRefundClient stripeRefundClient,
                         RefundEventPublisher refundEventPublisher,
                         StripeConnectService stripeConnectService,
                         StripeProperties stripeProperties) {
        this.refundRepository = refundRepository;
        this.orderRepository = orderRepository;
        this.stateMachineService = stateMachineService;
        this.refundMapper = refundMapper;
        this.stripeRefundClient = stripeRefundClient;
        this.refundEventPublisher = refundEventPublisher;
        this.stripeConnectService = stripeConnectService;
        this.stripeProperties = stripeProperties;
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
    @Transactional(noRollbackFor = io.github.resilience4j.circuitbreaker.CallNotPermittedException.class)
    public RefundDto createRefund(UUID orderId, CreateRefundRequest request, String clientIdempotencyKey)
            throws StripeException {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        // The cancel prefix is what marks a CUSTOMER-cancel row (isCancelRow), so a staff-supplied key may
        // not carry it: refused before the replay lookup (which would otherwise hand a vendor a customer's
        // cancel row) and before any row is stored.
        if (clientIdempotencyKey != null && clientIdempotencyKey.startsWith(CANCEL_KEY_PREFIX)) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must not start with \"" + CANCEL_KEY_PREFIX + "\" (reserved)");
        }

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

        return executeRefund(order, tenantId, requested, request.reason(), request.note(),
                clientIdempotencyKey);
    }

    // ------------------------------------------------------------------
    // #868 customer self-cancel: reserve (inside the cancel's transaction) -> Stripe (no transaction) -> finish.
    // ------------------------------------------------------------------

    /**
     * Marks a Refund row as the one a customer's own cancellation made; its Stripe key shares the prefix.
     * Reserved: {@link #createRefund} refuses a client Idempotency-Key that starts with it.
     */
    static final String CANCEL_KEY_PREFIX = "cancel-";

    /** What Stripe is asked, built inside a short read transaction so the Stripe call itself holds none. */
    public record PreparedCancelRefund(RefundCreateParams params, RequestOptions options, long amountPennies,
                                       boolean alreadyAtStripe) { }

    /**
     * True when this order already has a refund attempt made for a customer's own cancellation. The cancel
     * service asks it of an order that is already CANCELLED but still shows its card payment as CAPTURED, to
     * tell "a refund is half done, finish it" from "staff cancelled it, nothing to do with this customer".
     */
    @Transactional(readOnly = true)
    public boolean hasCancelRefund(UUID orderId) {
        return refundRepository.findByOrderIdOrderByRequestedAtDesc(orderId).stream()
                .anyMatch(RefundService::isCancelRow);
    }

    /** One captured PaymentIntent of an order, and the amount that was actually paid through it. */
    public record CancelLeg(String paymentIntentId, long amountPennies) { }

    /**
     * What a customer's cancellation must refund, as one leg per captured PaymentIntent (owner ruling
     * 2026-11-04: refund what was actually paid). An EMPTY list means it cannot be determined and the cancel
     * must be refused:
     * <ul>
     *   <li>an ordinary card order: one leg, the order's intent, the order total;</li>
     *   <li>a deposit order whose balance is still due: one leg, the deposit intent, the deposit;</li>
     *   <li>a deposit order paid in full by a deposit that IS the total: one leg, the order total;</li>
     *   <li>a deposit order whose balance was also paid: two legs, the deposit intent ({@code
     *       deposit_payment_reference}, V92) and the balance intent ({@code payment_reference}), and no
     *       leg at all when the deposit intent was never recorded (paid before V92): nothing is guessed.</li>
     * </ul>
     */
    public static List<CancelLeg> cancelRefundLegs(Order order) {
        String ref = order.getPaymentReference();
        Long total = order.getTotalAmountPennies();
        if (ref == null || ref.isBlank() || total == null || total <= 0) {
            return List.of();
        }
        Long deposit = order.getDepositAmountPennies();
        Long balanceDue = order.getBalanceDuePennies();
        boolean balanceOutstanding = balanceDue != null && balanceDue > 0;
        if (deposit == null && !balanceOutstanding) {
            return List.of(new CancelLeg(ref, total));
        }
        if (deposit == null || deposit <= 0) {
            return List.of();
        }
        if (balanceOutstanding) {
            return List.of(new CancelLeg(ref, deposit));
        }
        if (deposit >= total) {
            return List.of(new CancelLeg(ref, total));
        }
        String depositRef = order.getDepositPaymentReference();
        if (depositRef == null || depositRef.isBlank() || depositRef.equals(ref)) {
            return List.of();
        }
        return List.of(new CancelLeg(depositRef, deposit), new CancelLeg(ref, total - deposit));
    }

    private static boolean isCancelRow(Refund r) {
        return r.getIdempotencyKey() != null && r.getIdempotencyKey().startsWith(CANCEL_KEY_PREFIX);
    }

    private static boolean isLiveStatus(Refund r) {
        return r.getStatus() != RefundStatus.failed && r.getStatus() != RefundStatus.canceled;
    }

    /**
     * Reserve the Refund rows for a customer-cancelled order's refund (#868, owner ruling 2026-11-04): one row
     * per captured PaymentIntent ({@link #cancelRefundLegs}). Runs INSIDE the cancel's transaction, so the
     * rows commit together with the cancel and the ledger reversal and BEFORE any Stripe call: whatever
     * happens to Stripe or to what follows, the {@code metadata.refund_id} Stripe is given names a committed
     * row the {@code refund.*} webhook can find (BL-04).
     *
     * <p>The Stripe idempotency key is the row's own stored key, so one row is one Stripe request with one set
     * of parameters. A retry of a leg whose attempt is not finished (row CREATING, pending, succeeded) gets the
     * SAME row back, hence the same key and identical parameters, and Stripe replays the refund it already made
     * instead of refusing the key for changed parameters. Only an attempt Stripe definitively refused
     * ({@code failed}/{@code canceled}) is replaced by a new row, and so by a new key. A deposit and a balance
     * are separate charges and so separate rows with separate keys.
     *
     * @return the ids of the rows to issue; call {@link #issueCancelRefunds} AFTER the cancel has committed
     */
    @Transactional
    public List<UUID> reserveCancelRefunds(UUID orderId) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        List<CancelLeg> legs = cancelRefundLegs(order);
        if (legs.isEmpty()) {
            throw new InvalidStateTransitionException(
                    "Cannot refund order " + orderId + " — the payments made for it cannot be determined");
        }
        List<Refund> history = refundRepository.findByOrderIdOrderByRequestedAtDesc(orderId).stream()
                .filter(RefundService::isCancelRow).toList();
        List<UUID> ids = new java.util.ArrayList<>();
        for (CancelLeg leg : legs) {
            Optional<Refund> unfinished = history.stream()
                    .filter(r -> leg.paymentIntentId().equals(r.getPaymentIntentId()))
                    .findFirst()
                    .filter(RefundService::isLiveStatus);
            if (unfinished.isPresent()) {
                ids.add(unfinished.get().getId());
                continue;
            }
            long remaining = order.getTotalAmountPennies() - refundRepository.sumLiveAmountByOrderId(orderId);
            if (remaining < leg.amountPennies()) {
                throw new IllegalArgumentException(
                        "Order " + orderId + " has only " + remaining + " left to refund, less than the "
                                + leg.amountPennies() + " paid through " + leg.paymentIntentId());
            }
            Refund refund = refundRepository.saveAndFlush(new Refund(
                    tenantId,
                    orderId,
                    leg.paymentIntentId(),
                    CANCEL_KEY_PREFIX + UUID.randomUUID().toString().replace("-", ""),
                    leg.amountPennies(),
                    RefundReason.REQUESTED_BY_CUSTOMER,
                    "Cancelled by the customer before the kitchen accepted the order"));
            log.info("Reserved cancel Refund row {} (status=CREATING) for order {} amount={}",
                    refund.getId(), orderId, leg.amountPennies());
            ids.add(refund.getId());
        }
        return ids;
    }

    /** What the customer's cancellation has refunded so far: the legs Stripe has accepted. */
    @Transactional(readOnly = true)
    public long cancelRefundedPennies(UUID orderId) {
        return refundRepository.findByOrderIdOrderByRequestedAtDesc(orderId).stream()
                .filter(RefundService::isCancelRow)
                .filter(RefundService::isLiveStatus)
                .filter(r -> r.getStripeRefundId() != null)
                .mapToLong(Refund::getAmountPennies)
                .sum();
    }

    /**
     * Issue every reserved leg. A failure of one leg does not stop the others (each is its own Stripe request
     * under its own key); the first failure is rethrown once all have been tried, so a deposit refund is never
     * held back by a balance refund that failed, and a repeat re-sends only what is unfinished.
     */
    public long issueCancelRefunds(List<UUID> refundIds) throws StripeException {
        long total = 0;
        StripeException stripeFailure = null;
        RuntimeException runtimeFailure = null;
        for (UUID id : refundIds) {
            try {
                total += issueCancelRefund(id);
            } catch (StripeException e) {
                if (stripeFailure == null && runtimeFailure == null) {
                    stripeFailure = e;
                }
            } catch (RuntimeException e) {
                if (stripeFailure == null && runtimeFailure == null) {
                    runtimeFailure = e;
                }
            }
        }
        if (stripeFailure != null) {
            throw stripeFailure;
        }
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
        return total;
    }

    /**
     * Make the Stripe refund for a row {@link #reserveCancelRefunds} committed, then record the result. NOT
     * transactional by design: the external call runs with no database transaction open, and each local step
     * is its own short transaction (through the proxy, so {@code REQUIRES_NEW} really applies). Call it with
     * {@code TenantContext} set, after the cancel's transaction has committed.
     *
     * <p>Failure handling:
     * <ul>
     *   <li>Stripe REFUSED the request (a 4xx other than 409/429): the row is marked {@code failed} in a
     *       committed transaction, so the next attempt is a new row with a new key.</li>
     *   <li>The outcome is UNKNOWN (connection error, 5xx, timeout, 409/429) or the breaker never sent it: the
     *       row stays CREATING, so the next attempt re-sends the identical request under the identical key and
     *       Stripe returns the refund if it was made.</li>
     *   <li>Stripe succeeded but a local write after it fails: nothing is lost; the row is committed and the
     *       {@code refund.*} webhook updates it, and a retry sends the same request again.</li>
     * </ul>
     *
     * @return the amount refunded, in pennies
     * @throws StripeException if Stripe refuses or cannot be reached
     */
    public long issueCancelRefund(UUID refundId) throws StripeException {
        PreparedCancelRefund call = self().prepareCancelRefundCall(refundId);
        com.stripe.model.Refund stripeRefund = null;
        if (!call.alreadyAtStripe()) {
            try {
                stripeRefund = stripeRefundClient.create(call.params(), call.options());
            } catch (StripeException | io.github.resilience4j.circuitbreaker.CallNotPermittedException ex) {
                log.warn("Stripe.Refund.create failed for cancel refund {}: {}", refundId, ex.getMessage());
                if (isDefinitiveRefusal(ex)) {
                    self().markRefundFailed(refundId, ex.getMessage());
                }
                throw ex;
            }
        }
        self().finishCancelRefund(refundId, stripeRefund);
        return call.amountPennies();
    }

    /** A 4xx Stripe answered (other than "retry me" 409/429) means no refund exists under that key. */
    private static boolean isDefinitiveRefusal(Exception ex) {
        if (!(ex instanceof StripeException se)) {
            return false;
        }
        Integer status = se.getStatusCode();
        return status != null && status >= 400 && status < 500 && status != 409 && status != 429;
    }

    @Transactional(readOnly = true)
    public PreparedCancelRefund prepareCancelRefundCall(UUID refundId) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Refund not found: " + refundId));
        Order order = orderRepository.findById(refund.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + refund.getOrderId()));
        StripeCall call = buildStripeCall(order, refund);
        return new PreparedCancelRefund(call.params(), call.options(), refund.getAmountPennies(),
                refund.getStripeRefundId() != null);
    }

    /**
     * Record a cancel refund's outcome: the Stripe id and status on the row, and, once every leg has its refund, the order's payment status.
     * {@code stripeRefund} is null when the row already carried a Stripe id (the webhook got there first), in
     * which case only the order is brought up to date. A status the webhook has already advanced is never
     * moved backwards by the create response.
     */
    @Transactional
    public void finishCancelRefund(UUID refundId, com.stripe.model.Refund stripeRefund) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Refund not found: " + refundId));
        if (stripeRefund != null) {
            refund.setStripeRefundId(stripeRefund.getId());
            if (refund.getStatus() == RefundStatus.CREATING) {
                refund.setStatus(parseStripeStatus(stripeRefund.getStatus()));
            }
            refund.setUpdatedAt(OffsetDateTime.now());
            refundRepository.save(refund);
        }
        Order order = orderRepository.findById(refund.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + refund.getOrderId()));
        // The order reads REFUNDED only when EVERY captured PaymentIntent has its refund; a deposit refunded
        // while the balance leg is still unfinished leaves it CAPTURED, which is what lets a repeat finish it.
        if (allLegsRefunded(order)) {
            order.setPaymentStatus(PaymentStatus.REFUNDED);
            order.setUpdatedAt(OffsetDateTime.now());
            orderRepository.save(order);
        }
        log.info("Cancel refund {} recorded for order {} (Stripe={})", refundId, order.getId(),
                refund.getStripeRefundId());
    }

    private boolean allLegsRefunded(Order order) {
        List<CancelLeg> legs = cancelRefundLegs(order);
        if (legs.isEmpty()) {
            return false;
        }
        List<Refund> rows = refundRepository.findByOrderIdOrderByRequestedAtDesc(order.getId()).stream()
                .filter(RefundService::isCancelRow)
                .filter(RefundService::isLiveStatus)
                .filter(r -> r.getStripeRefundId() != null)
                .toList();
        return legs.stream().allMatch(leg -> rows.stream()
                .anyMatch(r -> leg.paymentIntentId().equals(r.getPaymentIntentId())));
    }

    /** The call is routed through the Spring proxy when there is one; plain {@code this} in a unit test. */
    private RefundService self() {
        return self != null ? self : this;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@org.springframework.context.annotation.Lazy RefundService self) {
        this.self = self;
    }

    /**
     * Steps 6-8 of the staff refund flow ({@link #createRefund}).
     */
    private RefundDto executeRefund(Order order, UUID tenantId, long requested, RefundReason reason, String note,
                                    String clientIdempotencyKey) throws StripeException {
        UUID orderId = order.getId();

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
                reason,
                note
        );
        refund = refundRepository.saveAndFlush(refund);
        log.info("Created Refund row {} (status=CREATING) for order {} amount={}",
                refund.getId(), orderId, requested);

        // Step 7 — Call Stripe with the stored key (and connected-account routing for PAY-03 / GAP-GTM-01)
        com.stripe.model.Refund stripeRefund;
        try {
            StripeCall call = buildStripeCall(order, refund);
            stripeRefund = stripeRefundClient.create(call.params(), call.options());
        } catch (StripeException | io.github.resilience4j.circuitbreaker.CallNotPermittedException ex) {
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

    private record StripeCall(RefundCreateParams params, RequestOptions options) { }

    /**
     * The one place a Stripe refund request is assembled, from the stored row alone: amount, reason, ids and
     * idempotency key all come from the persisted {@link Refund}, so the same row always yields the same
     * parameters under the same key (Stripe refuses a reused key whose parameters differ), plus the
     * connected-account routing (PAY-03 / GAP-GTM-01).
     */
    private StripeCall buildStripeCall(Order order, Refund refund) {
        RefundCreateParams.Builder paramsBuilder = RefundCreateParams.builder()
                // The row's own intent: for a deposit-split order the deposit and the balance are separate charges.
                .setPaymentIntent(refund.getPaymentIntentId())
                .setAmount(refund.getAmountPennies())
                .setReason(RefundReason.toStripeReason(refund.getReason()))
                .putMetadata("refund_id", refund.getId().toString())
                .putMetadata("tenant_id", refund.getTenantId().toString())
                .putMetadata("order_id", refund.getOrderId().toString())
                // PAY-03 (plan 30-12): the refund's own environment, so another environment's
                // runtime acknowledges its refund.* events instead of retrying them (Pitfall 5).
                .putMetadata("environment", stripeProperties != null
                        ? stripeProperties.effectiveEnvironmentTag()
                        : StripeProperties.UNSET_ENVIRONMENT);
        RequestOptions.RequestOptionsBuilder optsBuilder = RequestOptions.builder()
                .setIdempotencyKey(refund.getIdempotencyKey());

        if (stripeConnectService != null) {
            Optional<SettlementRoutingDecision> routingOpt =
                    stripeConnectService.resolveSettlementRouting(order);
            if (routingOpt.isPresent()) {
                SettlementRoutingDecision decision = routingOpt.get();
                if (decision.usesDirectChargeHeader()) {
                    optsBuilder.setStripeAccount(decision.connectedAccountId());
                    if (decision.applicationFeePennies() > 0) {
                        paramsBuilder.setRefundApplicationFee(true);
                    }
                } else if (decision.usesDestinationTransfer()) {
                    paramsBuilder.setReverseTransfer(true);
                    if (decision.applicationFeePennies() > 0) {
                        paramsBuilder.setRefundApplicationFee(true);
                    }
                }
            }
        }
        return new StripeCall(paramsBuilder.build(), optsBuilder.build());
    }

    /**
     * Persist a Stripe failure to the Refund row in a brand-new transaction, so the failure is durable even
     * when the caller's transaction rolls back. It only has an effect on a row that is ALREADY COMMITTED, which
     * is what {@link #reserveCancelRefunds} guarantees for the customer-cancel path. Callers must reach it
     * through the Spring proxy ({@code self()}); a plain self-call never opens the new transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRefundFailed(UUID refundId, String reason) {
        refundRepository.findById(refundId)
                .filter(r -> r.getStatus() == RefundStatus.CREATING)   // never overwrite what a webhook already decided
                .ifPresent(r -> {
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
     * The same history for a whole page of orders, in ONE query (#564).
     *
     * <p>Called by the kitchen board, which builds an {@code OrderDetailDto} per ticket.
     * Doing this per order would put an N+1 back behind an endpoint whose entire purpose
     * is to remove one — cheap from the outside, unchanged on the inside.
     *
     * <p>Returns a map keyed by order id. Orders with no refunds are ABSENT rather than
     * mapped to an empty list: the caller has the authoritative order set and defaults
     * them, so inventing keys here would only duplicate that knowledge in two places.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<RefundDto>> findByOrderIds(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return refundRepository.findByOrderIdInOrderByOrderIdAscRequestedAtDesc(orderIds).stream()
                .collect(Collectors.groupingBy(
                        Refund::getOrderId,
                        // LinkedHashMap on the downstream is deliberate: the query orders
                        // newest-first WITHIN each order, and groupingBy preserves
                        // encounter order, so the per-order lists match what the
                        // single-order path returns. A plain toList() would too — this
                        // says so rather than leaving it to be rediscovered.
                        Collectors.collectingAndThen(Collectors.toList(), refundMapper::toDtoList)));
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
            // PAY-03 (plan 30-12): this is kept for SAME-environment events only. A refund created
            // by another environment sharing the Stripe account carries that environment's tag and
            // is acknowledged by PaymentService.dispatch before it reaches here, so the throw below
            // can no longer turn a foreign refund into a retry storm; for this runtime's own refund
            // a missing row is a real lag or bug, and the retry is what recovers it.
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
