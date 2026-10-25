package uk.jtoye.core.payment;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.finance.FinancialTransactionService;
import uk.jtoye.core.finance.dto.CreateTransactionRequest;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;
import uk.jtoye.core.security.TenantContext;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /**
     * Guard so the static Stripe.apiKey assignment happens at most once per
     * JVM, even if multiple PaymentService beans are instantiated in different
     * Spring contexts (e.g. nested test slices). Also lets tests observe
     * idempotency without spilling the key to logs.
     */
    static final AtomicBoolean STRIPE_INITIALIZED = new AtomicBoolean(false);

    private final StripeProperties stripeProperties;
    private final OrderRepository orderRepository;
    private final OrderEventPublisher eventPublisher;
    private final PaymentEventPublisher paymentEventPublisher;
    private final FinancialTransactionService financialTransactionService;
    private final JdbcTemplate jdbcTemplate;
    private final RefundService refundService;
    private final StripeConnectService stripeConnectService;
    private final StripeBillingService stripeBillingService;

    // issue #98 [P2-7]: real payment-failure counter behind the Prometheus
    // PaymentFailureSpike alert (rate(jtoye_payment_failed_total[5m])).
    // Incremented at the natural detection point (handlePaymentIntentFailed).
    // Null-safe MeterRegistry, mirroring the RateLimitInterceptor precedent;
    // label-free to keep the series low-cardinality and PII-free (T-t6b-03).
    private final Counter paymentFailedCounter;

    public PaymentService(StripeProperties stripeProperties,
                         OrderRepository orderRepository,
                         OrderEventPublisher eventPublisher,
                         PaymentEventPublisher paymentEventPublisher,
                         FinancialTransactionService financialTransactionService,
                         JdbcTemplate jdbcTemplate,
                         RefundService refundService,
                         StripeConnectService stripeConnectService,
                         ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(stripeProperties, orderRepository, eventPublisher, paymentEventPublisher,
                financialTransactionService, jdbcTemplate, refundService, stripeConnectService,
                meterRegistryProvider, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentService(StripeProperties stripeProperties,
                         OrderRepository orderRepository,
                         OrderEventPublisher eventPublisher,
                         PaymentEventPublisher paymentEventPublisher,
                         FinancialTransactionService financialTransactionService,
                         JdbcTemplate jdbcTemplate,
                         RefundService refundService,
                         StripeConnectService stripeConnectService,
                         ObjectProvider<MeterRegistry> meterRegistryProvider,
                         StripeBillingService stripeBillingService) {
        this.stripeProperties = stripeProperties;
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.paymentEventPublisher = paymentEventPublisher;
        this.financialTransactionService = financialTransactionService;
        this.jdbcTemplate = jdbcTemplate;
        this.refundService = refundService;
        this.stripeConnectService = stripeConnectService;
        this.stripeBillingService = stripeBillingService;
        MeterRegistry reg = meterRegistryProvider.getIfAvailable();
        this.paymentFailedCounter = reg != null
                ? Counter.builder("jtoye.payment.failed")
                    .description("Stripe payment_intent.payment_failed webhook processed (issue #98 — PaymentFailureSpike signal)")
                    .register(reg)
                : null;
    }

    @PostConstruct
    void init() {
        if (stripeProperties.getApiKey() == null || stripeProperties.getApiKey().isBlank()) {
            log.warn("Stripe API key not configured — payment processing will fail");
            return;
        }
        // Idempotent: Stripe.apiKey is a static field, so we must only assign
        // it once per JVM. A second call is harmless but the guard makes the
        // intent explicit and keeps tests deterministic.
        if (STRIPE_INITIALIZED.compareAndSet(false, true)) {
            Stripe.apiKey = stripeProperties.getApiKey();
            // NEVER log the key itself — only the fact that it was loaded.
            log.info("Stripe API key configured (key redacted)");
        }
    }

    public boolean isConfigured() {
        return stripeProperties.getApiKey() != null && !stripeProperties.getApiKey().isBlank();
    }

    /**
     * Create a Stripe PaymentIntent for a DRAFT order.
     * Returns the PaymentIntent id (persist it) and the client secret (send it
     * to the browser) — see {@link PaymentIntentResult}.
     *
     * <p><b>The order MUST already be persisted (issue #538).</b> The intent's
     * {@code order_id} metadata is the only link the {@code
     * payment_intent.succeeded} webhook has back to the local row, and the
     * order id is also the Stripe idempotency key below. Calling this with a
     * transient order used to dereference a null UUID and 500 the checkout on
     * every Stripe-configured environment; the guard makes that a named,
     * greppable failure instead of a {@code NullPointerException} in a metadata
     * builder.
     *
     * <p><b>Idempotency.</b> {@code Stripe.maxNetworkRetries} defaults to 2 and
     * the SDK emits an {@code Idempotency-Key} header only when the caller
     * supplies one — it never generates one. Without a key, one checkout could
     * leave up to three PaymentIntents at Stripe. The order's UUID is the
     * natural key: it is stable for the lifetime of the order and is not reused
     * by a retried checkout (a failed attempt rolls its order back, so the retry
     * gets a fresh id and legitimately deserves a fresh intent).
     *
     * <p><b>Charge routing (issue #102, ADR-0001 Decision 2, ADR-0007 axis 1,
     * Phase 30 / V88):</b> {@link StripeConnectService#resolveSettlementRouting}
     * picks the mode. With an ENABLED connected account, the default
     * {@code DIRECT_CHARGE_VENDOR_MOR} charges on the vendor's own account
     * (the {@code Stripe-Account} header; the vendor is merchant of record and
     * {@code application_fee_amount} carries the plan's platform fee), and a
     * MARKETPLACE tenant or an explicit shop override uses the
     * {@code DESTINATION_CHARGE_PLATFORM_MOR} <em>destination charge</em>
     * ({@code transfer_data[destination]}, platform is merchant of record).
     * Both were proven in Stripe test mode (30-VERIFICATION.md). When the
     * tenant has NO enabled connected account the routing is empty and the
     * intent falls back to the platform account, which is why going live waits
     * for Connect onboarding.
     *
     * @throws IllegalStateException if the order has not been persisted
     */
    @CircuitBreaker(name = "stripe")
    public PaymentIntentResult createPaymentIntent(Order order) throws StripeException {
        if (order.getId() == null) {
            throw new IllegalStateException(
                    "createPaymentIntent requires a persisted order — order "
                            + order.getOrderNumber() + " has no id. Persist the order BEFORE "
                            + "creating its PaymentIntent (issue #538).");
        }

        java.util.Optional<SettlementRoutingDecision> routingOpt =
                stripeConnectService.resolveSettlementRouting(order);

        long chargeAmountPennies = routingOpt
                .map(SettlementRoutingDecision::chargeAmountPennies)
                .orElseGet(() -> order.getTotalAmountPennies() != null ? order.getTotalAmountPennies() : 0L);

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(chargeAmountPennies)
                .setCurrency(stripeProperties.getCurrency())
                .setDescription("Order " + order.getOrderNumber())
                .putMetadata("order_id", order.getId().toString())
                .putMetadata("order_number", order.getOrderNumber())
                .putMetadata("tenant_id", order.getTenantId().toString())
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build()
                );

        RequestOptions.RequestOptionsBuilder requestOptionsBuilder = RequestOptions.builder()
                .setIdempotencyKey(intentIdempotencyKey(order));

        if (routingOpt.isPresent()) {
            SettlementRoutingDecision decision = routingOpt.get();
            builder.putMetadata("settlement_mode", decision.mode().name());
            if (decision.depositAmountPennies() != null && decision.balanceDuePennies() != null) {
                order.setDepositAmountPennies(decision.depositAmountPennies());
                order.setBalanceDuePennies(decision.balanceDuePennies());
            }
            if (decision.applicationFeePennies() > 0) {
                builder.setApplicationFeeAmount(decision.applicationFeePennies());
            }
            if (decision.usesDirectChargeHeader()) {
                requestOptionsBuilder.setStripeAccount(decision.connectedAccountId());
            } else if (decision.usesDestinationTransfer()) {
                builder.setTransferData(PaymentIntentCreateParams.TransferData.builder()
                        .setDestination(decision.connectedAccountId())
                        .build());
            }
        } else {
            java.util.Optional<String> destination =
                    stripeConnectService.resolveDestinationAccount(order.getTenantId());
            destination.ifPresent(accountId -> {
                builder.setTransferData(PaymentIntentCreateParams.TransferData.builder()
                        .setDestination(accountId)
                        .build());
                long fee = stripeConnectService.applicationFeePennies(order.getTotalAmountPennies());
                if (fee > 0) {
                    builder.setApplicationFeeAmount(fee);
                }
            });
        }

        // See the "Idempotency" note above: without this header an SDK network
        // retry can create a second (and third) PaymentIntent for one checkout.
        RequestOptions requestOptions = requestOptionsBuilder.build();

        PaymentIntent intent = PaymentIntent.create(builder.build(), requestOptions);

        if (routingOpt.isPresent()) {
            SettlementRoutingDecision decision = routingOpt.get();
            log.info("Created {} PaymentIntent {} for order {} (chargeAmount: {} pennies, connectedAccount: {})",
                    decision.mode(), intent.getId(), order.getOrderNumber(),
                    chargeAmountPennies, decision.connectedAccountId());
        } else {
            log.info("Created PaymentIntent {} for order {} (amount: {} pennies)",
                    intent.getId(), order.getOrderNumber(), chargeAmountPennies);
        }

        return new PaymentIntentResult(intent.getId(), intent.getClientSecret());
    }

    /**
     * Stripe idempotency key for an order's PaymentIntent creation. Namespaced
     * so it can never collide with a key minted for some other Stripe resource
     * on the same account (Stripe scopes idempotency keys per account, not per
     * endpoint).
     *
     * <p>Package-private so {@code PaymentServiceTest} asserts the SAME
     * expression the production call uses, rather than a copy of it.
     */
    static String intentIdempotencyKey(Order order) {
        return "order-payment-intent-" + order.getId();
    }

    /**
     * Stripe idempotency key for a deposit order's remaining-balance PaymentIntent (GAP-GTM-04).
     */
    static String balanceIntentIdempotencyKey(Order order) {
        return "order-balance-payment-intent-" + order.getId();
    }

    /**
     * Create a Stripe PaymentIntent for the remaining balance due on a deposit-split order
     * (ADR-0007 §1.3 / GAP-GTM-04, PAY-04).
     *
     * @throws IllegalStateException if the order is not persisted or has no positive remaining balance due
     */
    @CircuitBreaker(name = "stripe")
    public PaymentIntentResult createBalancePaymentIntent(Order order) throws StripeException {
        if (order.getId() == null) {
            throw new IllegalStateException(
                    "createBalancePaymentIntent requires a persisted order — order "
                            + order.getOrderNumber() + " has no id.");
        }
        if (order.isVoided()
                || order.getStatus() == OrderStatus.DRAFT
                || order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.REFUNDED) {
            throw new IllegalStateException(
                    "Order " + order.getOrderNumber() + " is not eligible for balance payment in status "
                            + order.getStatus());
        }
        Long balanceDue = order.getBalanceDuePennies();
        if (balanceDue == null || balanceDue <= 0) {
            throw new IllegalStateException(
                    "Order " + order.getOrderNumber() + " has no remaining balance due.");
        }

        java.util.Optional<SettlementRoutingDecision> routingOpt =
                stripeConnectService.resolveSettlementRouting(order);

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(balanceDue)
                .setCurrency(stripeProperties.getCurrency())
                .setDescription("Remaining balance for Order " + order.getOrderNumber())
                .putMetadata("order_id", order.getId().toString())
                .putMetadata("order_number", order.getOrderNumber())
                .putMetadata("tenant_id", order.getTenantId().toString())
                .putMetadata("payment_stage", "BALANCE")
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build()
                );

        RequestOptions.RequestOptionsBuilder requestOptionsBuilder = RequestOptions.builder()
                .setIdempotencyKey(balanceIntentIdempotencyKey(order));

        if (routingOpt.isPresent()) {
            SettlementRoutingDecision decision = routingOpt.get();
            builder.putMetadata("settlement_mode", decision.mode().name());
            // A-2 (PR #949 round 1): the balance fee honours the tenant's BillingPlanMode
            // (0.5% / 0% / 6% / walk-capped), never the global stripe.platform-fee-bps.
            long fee = stripeConnectService.applicationFeePennies(order.getTenantId(), balanceDue);
            if (fee > 0) {
                builder.setApplicationFeeAmount(fee);
            }
            if (decision.usesDirectChargeHeader()) {
                requestOptionsBuilder.setStripeAccount(decision.connectedAccountId());
            } else if (decision.usesDestinationTransfer()) {
                builder.setTransferData(PaymentIntentCreateParams.TransferData.builder()
                        .setDestination(decision.connectedAccountId())
                        .build());
            }
        }

        PaymentIntent intent = PaymentIntent.create(builder.build(), requestOptionsBuilder.build());
        log.info("Created BALANCE PaymentIntent {} for order {} (balanceDue: {} pennies)",
                intent.getId(), order.getOrderNumber(), balanceDue);
        return new PaymentIntentResult(intent.getId(), intent.getClientSecret());
    }

    /**
     * Retrieve the client secret of an EXISTING PaymentIntent by its id (WR-02).
     * Used by the guest-order idempotency short-circuit so a retried checkout
     * can resume payment with a real {@code pi_..._secret_...} value — the
     * PaymentIntent id itself must never be handed to Stripe Elements.
     */
    @CircuitBreaker(name = "stripe")
    public String retrieveClientSecret(String paymentIntentId) throws StripeException {
        return PaymentIntent.retrieve(paymentIntentId).getClientSecret();
    }

    /**
     * Retrieve the client secret of an EXISTING PaymentIntent that belongs to {@code order}.
     * A-1 (PR #949 round 1): direct-charge and split-deposit intents live on the vendor's
     * connected account, so they must be retrieved with the {@code Stripe-Account} header —
     * a platform-account retrieve answers 404 {@code resource_missing}.
     */
    @CircuitBreaker(name = "stripe")
    public String retrieveClientSecret(Order order, String paymentIntentId) throws StripeException {
        if (order != null) {
            Optional<SettlementRoutingDecision> routing = stripeConnectService.resolveSettlementRouting(order);
            if (routing.isPresent() && routing.get().usesDirectChargeHeader()) {
                RequestOptions options = RequestOptions.builder()
                        .setStripeAccount(routing.get().connectedAccountId())
                        .build();
                return PaymentIntent.retrieve(paymentIntentId, options).getClientSecret();
            }
        }
        return retrieveClientSecret(paymentIntentId);
    }

    /** Stripe's status string for a cancelled PaymentIntent (issue #1038). */
    public static final String STRIPE_STATUS_CANCELED = "canceled";

    /**
     * The PaymentIntent statuses in which no money has moved and none is held, so the
     * intent may be cancelled as abandoned (issue #1038). {@code requires_capture} is
     * deliberately absent: it is an authorised hold the shop may still capture, and the
     * webhook owns that order. {@code succeeded} and {@code processing} have taken money.
     */
    private static final Set<String> CANCELABLE_UNPAID_STATUSES =
            Set.of("requires_payment_method", "requires_confirmation", "requires_action");

    /**
     * Stripe idempotency key for the stale-draft cancel of {@code paymentIntentId}
     * (issue #1038). Package-private so {@code PaymentServiceTest} asserts the SAME
     * expression the production call uses, like {@link #intentIdempotencyKey(Order)}.
     */
    static String abandonedIntentCancelIdempotencyKey(String paymentIntentId) {
        return "abandoned-draft-cancel-" + paymentIntentId;
    }

    /**
     * The connected account an order's PaymentIntent lives on, when its routing is a
     * direct charge (DIRECT_CHARGE_VENDOR_MOR or SPLIT_DEPOSIT_VENDOR_MOR); empty for a
     * platform-account intent, including a destination charge (issue #1038).
     *
     * <p>The caller MUST hold a tenant-pinned transaction. The routing reads {@code shops}
     * and {@code vendor_onboarding} under FORCE RLS, and its {@code @Transactional}
     * overload is self-invoked from the Order overload, so it opens no transaction of its
     * own; TenantSetLocalAspect pins the tenant GUC only inside one. Called without it,
     * every connected-account vendor would resolve empty, its intent would be looked up on
     * the platform account (404 {@code resource_missing}) and the draft kept for ever.
     * {@link #retrieveClientSecret(Order, String)} follows the same rule (A-1, PR #949).
     *
     * <p>Deliberately NOT {@code @Transactional}: a read-only participation must not be
     * able to change the flush behaviour of the caller's read-write transaction.
     */
    public Optional<String> directChargeAccountFor(Order order) {
        return stripeConnectService.resolveSettlementRouting(order)
                .filter(SettlementRoutingDecision::usesDirectChargeHeader)
                .map(SettlementRoutingDecision::connectedAccountId);
    }

    /**
     * Cancel an abandoned checkout's PaymentIntent as {@code abandoned}, if and only if no
     * money has moved and none is held, and return its Stripe status after the call
     * (issue #1038: the stale-draft cleanup deleted card drafts while their intents were
     * still payable, leaving 7 orphaned {@code requires_payment_method} intents on staging
     * 2026-10-24; a later payment would capture money for an order that no longer exists).
     *
     * <ul>
     *   <li>The intent is retrieved first: a cancel on an already-canceled intent errors,
     *       and an intent carrying money must be left alone without a failed call.</li>
     *   <li>{@code succeeded}, {@code processing}, {@code requires_capture} and any status
     *       not in {@link #CANCELABLE_UNPAID_STATUSES} are returned untouched.
     *       {@code requires_capture} is never cancelled: the hold is the shop's, and the
     *       webhook owns that order.</li>
     *   <li>The cancel rides an Idempotency-Key (AGENTS.md oaas-money rule 2). Stripe may
     *       replay a stored failure for the same key for about 24 h; that only delays a
     *       deletion by a run, it can never cause one.</li>
     * </ul>
     *
     * @param paymentIntentId    the order's {@code pi_...} reference
     * @param connectedAccountId the direct-charge account from {@link #directChargeAccountFor(Order)},
     *                           or {@code null} for a platform-account intent
     * @throws IllegalArgumentException if the reference is not a PaymentIntent id; thrown
     *                                  before any Stripe call
     */
    @CircuitBreaker(name = "stripe")
    public String cancelAbandonedPaymentIntent(String paymentIntentId, String connectedAccountId)
            throws StripeException {
        if (paymentIntentId == null || !paymentIntentId.startsWith("pi_")) {
            throw new IllegalArgumentException(
                    "issue #1038: only a PaymentIntent (pi_) reference can be cancelled as abandoned");
        }
        boolean onConnectedAccount = connectedAccountId != null && !connectedAccountId.isBlank();

        RequestOptions.RequestOptionsBuilder retrieveOptions = RequestOptions.builder();
        if (onConnectedAccount) {
            retrieveOptions.setStripeAccount(connectedAccountId);
        }
        PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId, retrieveOptions.build());

        String status = intent.getStatus();
        if (STRIPE_STATUS_CANCELED.equals(status)) {
            return status;
        }
        if (!CANCELABLE_UNPAID_STATUSES.contains(status)) {
            return status;
        }

        RequestOptions.RequestOptionsBuilder cancelOptions = RequestOptions.builder()
                .setIdempotencyKey(abandonedIntentCancelIdempotencyKey(paymentIntentId));
        if (onConnectedAccount) {
            cancelOptions.setStripeAccount(connectedAccountId);
        }
        PaymentIntent canceled = intent.cancel(
                PaymentIntentCancelParams.builder()
                        .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                        .build(),
                cancelOptions.build());

        log.info("event=abandoned_intent_canceled intent={} connectedAccount={}",
                paymentIntentId, onConnectedAccount ? connectedAccountId : "none");
        return canceled.getStatus();
    }

    /**
     * Process a Stripe webhook event.
     * Verifies the signature and dispatches to appropriate handler.
     */
    @Transactional
    public void handleWebhookEvent(String payload, String sigHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, stripeProperties.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid Stripe signature");
        } catch (Exception e) {
            log.error("Failed to parse Stripe webhook event", e);
            throw new IllegalArgumentException("Invalid webhook payload");
        }

        // AUDIT-W0-03 — TOCTOU-safe idempotency guard.
        //
        // Single SQL statement; INSERT ... ON CONFLICT DO NOTHING is atomic in
        // Postgres so concurrent webhook deliveries cannot both think they are
        // "first". 1 row affected => first delivery, proceed. 0 rows affected
        // => duplicate retry, return early so side effects fire exactly once.
        //
        // The INSERT sits INSIDE the existing @Transactional boundary on
        // purpose: if downstream side-effect processing throws, the dedup
        // row also rolls back, and Stripe's next retry gets a fresh shot.
        // Semantic is "successfully processed at least once", not "we saw
        // this event_id once". Runs AFTER signature verification so junk
        // payloads cannot pollute processed_stripe_events.
        int inserted = jdbcTemplate.update(
                "INSERT INTO processed_stripe_events (event_id) VALUES (?) ON CONFLICT (event_id) DO NOTHING",
                event.getId());
        if (inserted == 0) {
            log.info("Stripe event {} ({}) already processed — skipping", event.getId(), event.getType());
            return;
        }

        log.info("Received Stripe event: {} ({})", event.getType(), event.getId());

        switch (event.getType()) {
            case "payment_intent.succeeded" -> handlePaymentIntentSucceeded(event);
            case "payment_intent.payment_failed" -> handlePaymentIntentFailed(event);
            // Phase 17 VOPS-02 — refund webhook lifecycle. These cases sit AFTER
            // the Phase 16.1 dedup INSERT (above) inside the same switch, so
            // re-delivery of the same event.id short-circuits at the dedup
            // guard — we do NOT add a new dedup table per CORRECTION-2 LOCKED.
            case "refund.created", "refund.updated", "refund.failed" ->
                    refundService.handleStripeRefundEvent(event);
            // UC-4 LOCKED — refund.* events are the canonical surface as of
            // Stripe's 2024-10-28 unified-events changelog. charge.refunded
            // is redundant for our needs but Stripe still fires it; ignore
            // explicitly to avoid "unhandled event" log spam.
            case "charge.refunded" -> log.debug(
                    "Ignored Stripe event charge.refunded ({}); refund.* is canonical",
                    event.getId());
            // issue #102 (ADR-0001 Decision 2) — Connect connected-account
            // capability sync. Sits AFTER the same dedup guard, so re-delivered
            // account.updated events short-circuit above. In a live topology the
            // Connect webhook endpoint must be pointed at the SAME URL/secret as
            // the platform endpoint (or this handler split per-secret — deferred
            // to the keyed environment).
            case "account.updated" -> stripeConnectService.handleAccountUpdated(event);
            case "invoice.paid" -> {
                if (stripeBillingService != null) {
                    stripeBillingService.handleInvoicePaid(event);
                }
            }
            case "invoice.payment_failed" -> {
                if (stripeBillingService != null) {
                    stripeBillingService.handleInvoicePaymentFailed(event);
                }
            }
            case "customer.subscription.deleted" -> {
                if (stripeBillingService != null) {
                    stripeBillingService.handleSubscriptionDeleted(event);
                }
            }
            default -> log.debug("Unhandled Stripe event type: {}", event.getType());
        }
    }

    private void handlePaymentIntentSucceeded(Event event) {
        PaymentIntent intent = (PaymentIntent) event.getDataObjectDeserializer()
                .getObject().orElseThrow(() -> new IllegalStateException("Failed to deserialize PaymentIntent"));

        String orderId = intent.getMetadata().get("order_id");
        if (orderId == null) {
            log.warn("PaymentIntent {} has no order_id metadata — skipping", intent.getId());
            return;
        }

        // Set tenant context for RLS
        String tenantId = intent.getMetadata().get("tenant_id");
        if (tenantId != null) {
            TenantContext.set(java.util.UUID.fromString(tenantId));
        }

        try {
            Order order = orderRepository.findById(java.util.UUID.fromString(orderId))
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

            // Build payment method description
            String paymentMethodDesc = "Card";
            if (intent.getLatestCharge() != null) {
                try {
                    com.stripe.model.Charge charge = (event.getAccount() != null && !event.getAccount().isBlank())
                            ? com.stripe.model.Charge.retrieve(
                                    intent.getLatestCharge(),
                                    RequestOptions.builder().setStripeAccount(event.getAccount()).build())
                            : com.stripe.model.Charge.retrieve(intent.getLatestCharge());
                    if (charge.getPaymentMethodDetails() != null
                            && charge.getPaymentMethodDetails().getCard() != null) {
                        var card = charge.getPaymentMethodDetails().getCard();
                        paymentMethodDesc = card.getBrand() + " ending " + card.getLast4();
                    }
                } catch (StripeException e) {
                    log.warn("Could not retrieve charge details for PI {}", intent.getId(), e);
                }
            }

            boolean isBalanceStage = "BALANCE".equals(intent.getMetadata().get("payment_stage"));
            if (isBalanceStage) {
                long settledBalancePennies = (order.getBalanceDuePennies() != null && order.getBalanceDuePennies() > 0)
                        ? order.getBalanceDuePennies()
                        : (intent.getAmount() != null ? intent.getAmount() : 0L);
                order.setBalanceDuePennies(0L);
                order.setPaymentStatus(PaymentStatus.CAPTURED);
                order.setPaymentReference(intent.getId());
                order.setPaymentMethod(paymentMethodDesc);
                order.setUpdatedAt(java.time.OffsetDateTime.now());
                orderRepository.save(order);

                paymentEventPublisher.publishSucceeded(
                        order.getId(), order.getTenantId(), order.getOrderNumber(),
                        intent.getId(), settledBalancePennies, "gbp");

                if (!order.isVoided() && order.getTotalAmountPennies() != null) {
                    financialTransactionService.createTransaction(
                            new CreateTransactionRequest(
                                    order.getTotalAmountPennies(),
                                    order.getVatRate(),
                                    "Payment " + intent.getId() + " for Order " + order.getOrderNumber(),
                                    order.getId(),
                                    order.getShopId()
                            ));
                }

                log.info("Remaining balance payment succeeded for order {} — PI: {}, balance: {} pennies",
                        order.getOrderNumber(), intent.getId(), settledBalancePennies);
                return;
            }

            order.setPaymentStatus(PaymentStatus.CAPTURED);
            order.setPaymentReference(intent.getId());
            order.setPaymentMethod(paymentMethodDesc);
            // Phase 30-05 (issue #958): only a storefront card DRAFT moves (DRAFT → PENDING). A payment link can be
            // issued and paid at any open status, CONFIRMED and PREPARING included, and this used to reset such an
            // order to PENDING and announce DRAFT → PENDING again, which re-sent the customer's "Received" email
            // and put an accepted order back in the kitchen's New lane. Any other status keeps its value: the
            // money is recorded, the shop's progress is not undone (the asymmetric-ordering rule, AGENTS.md
            // oaas-money). The payment-succeeded event and the ledger row below are unchanged.
            OrderStatus statusAtPayment = order.getStatus();
            boolean movesDraftToPending = statusAtPayment == OrderStatus.DRAFT;
            if (movesDraftToPending) {
                order.setStatus(OrderStatus.PENDING);
            } else if (order.isVoided() || statusAtPayment == OrderStatus.CANCELLED
                    || statusAtPayment == OrderStatus.REFUNDED) {
                // T-30-22: money captured on an order the shop has closed. No automatic refund (refunds are the
                // vendor's decision); the row shows CAPTURED and this line makes it findable. No PII.
                log.warn("event=payment_on_closed_order order={} status={} intent={}",
                        order.getOrderNumber(), statusAtPayment, intent.getId());
            }
            order.setUpdatedAt(java.time.OffsetDateTime.now());
            orderRepository.save(order);

            if (movesDraftToPending) {
                // Record the state change in the transactional outbox (#93) —
                // joins the webhook transaction, published post-commit by the flusher.
                eventPublisher.publishStateChange(
                        order.getId(), order.getTenantId(), order.getShopId(), order.getOrderNumber(),
                        OrderStatus.DRAFT, OrderStatus.PENDING);
            }

            boolean isSplitDepositWithBalance = order.getDepositAmountPennies() != null
                    && order.getBalanceDuePennies() != null
                    && order.getBalanceDuePennies() > 0;
            long capturedAmountPennies = isSplitDepositWithBalance
                    ? order.getDepositAmountPennies()
                    : order.getTotalAmountPennies();

            // Publish payment succeeded event (for audit, analytics, reconciliation)
            paymentEventPublisher.publishSucceeded(
                    order.getId(), order.getTenantId(), order.getOrderNumber(),
                    intent.getId(), capturedAmountPennies, "gbp");

            // Create financial transaction record — canonical ledger owner for
            // full-payment card orders (Issue #81 BUG 3). Idempotent on orderId, so the later
            // COMPLETED transition is a no-op. Rate is the order's resolved
            // (predominant) rate, not a hardcoded STANDARD literal (BUG 2).
            // ADR-0007 §1.3 (R-4): for SPLIT_DEPOSIT_VENDOR_MOR catering orders where
            // balanceDuePennies > 0 remains to be collected prior to fulfilment, defer the
            // full-order SALE ledger row to the COMPLETED transition in OrderService so a
            // partial deposit never writes an overstated full-gross SALE row prematurely.
            if (!isSplitDepositWithBalance) {
                financialTransactionService.createTransaction(
                        new CreateTransactionRequest(
                                order.getTotalAmountPennies(),
                                order.getVatRate(),
                                "Payment " + intent.getId() + " for Order " + order.getOrderNumber(),
                                order.getId(),
                                order.getShopId()   // Phase 37-11: the SALE belongs to the order's shop
                        ));
            }

            log.info("Payment succeeded for order {} — PI: {}, method: {}",
                    order.getOrderNumber(), intent.getId(), paymentMethodDesc);
        } finally {
            TenantContext.clear();
        }
    }

    private void handlePaymentIntentFailed(Event event) {
        // issue #98 [P2-7]: emit the payment-failure counter at the natural
        // detection point — a Stripe payment_intent.payment_failed webhook. This
        // is the metric behind the PaymentFailureSpike alert. Null-safe;
        // label-free to keep the series low-cardinality and PII-free.
        if (paymentFailedCounter != null) {
            paymentFailedCounter.increment();
        }

        PaymentIntent intent = (PaymentIntent) event.getDataObjectDeserializer()
                .getObject().orElseThrow(() -> new IllegalStateException("Failed to deserialize PaymentIntent"));

        String orderId = intent.getMetadata().get("order_id");
        if (orderId == null) {
            log.warn("PaymentIntent {} has no order_id metadata — skipping", intent.getId());
            return;
        }

        String tenantId = intent.getMetadata().get("tenant_id");
        if (tenantId != null) {
            TenantContext.set(java.util.UUID.fromString(tenantId));
        }

        try {
            Order order = orderRepository.findById(java.util.UUID.fromString(orderId))
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

            boolean isBalanceStage = "BALANCE".equals(intent.getMetadata().get("payment_stage"));
            if (!isBalanceStage) {
                order.setPaymentStatus(PaymentStatus.FAILED);
                order.setPaymentReference(intent.getId());
                order.setUpdatedAt(java.time.OffsetDateTime.now());
                orderRepository.save(order);
            }

            String failureReason = intent.getLastPaymentError() != null
                    ? intent.getLastPaymentError().getMessage()
                    : "unknown";

            long failedAmountPennies = isBalanceStage
                    ? ((order.getBalanceDuePennies() != null && order.getBalanceDuePennies() > 0)
                            ? order.getBalanceDuePennies()
                            : (intent.getAmount() != null ? intent.getAmount() : order.getTotalAmountPennies()))
                    : order.getTotalAmountPennies();

            // Publish payment failed event
            paymentEventPublisher.publishFailed(
                    order.getId(), order.getTenantId(), order.getOrderNumber(),
                    intent.getId(), failedAmountPennies, "gbp", failureReason);

            log.warn("Payment failed for order {} — PI: {} (stage={}): {}",
                    order.getOrderNumber(), intent.getId(), isBalanceStage ? "BALANCE" : "INITIAL", failureReason);
        } finally {
            TenantContext.clear();
        }
    }
}
