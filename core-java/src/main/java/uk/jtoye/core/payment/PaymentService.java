package uk.jtoye.core.payment;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.net.Webhook;
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
        this.stripeProperties = stripeProperties;
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.paymentEventPublisher = paymentEventPublisher;
        this.financialTransactionService = financialTransactionService;
        this.jdbcTemplate = jdbcTemplate;
        this.refundService = refundService;
        this.stripeConnectService = stripeConnectService;
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
     * Returns the client secret for frontend confirmation.
     *
     * <p><b>Charge routing (issue #102, ADR-0001 Decision 2):</b> when the
     * order's tenant is a MARKETPLACE vendor with an ENABLED connected
     * account, the intent is created as a <em>destination charge</em> —
     * {@code transfer_data[destination]} routes the funds to the vendor's
     * account and {@code application_fee_amount} (from
     * {@code stripe.platform-fee-bps}) is retained by the platform.
     * WHITE_LABEL tenants and tenants without an enabled connected account
     * keep today's pooled-account behaviour unchanged (their direct-charge
     * flow is a future slice; see StripeConnectService).
     */
    @CircuitBreaker(name = "stripe")
    public String createPaymentIntent(Order order) throws StripeException {
        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(order.getTotalAmountPennies())
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

        PaymentIntent intent = PaymentIntent.create(builder.build());

        if (destination.isPresent()) {
            log.info("Created destination-charge PaymentIntent {} for order {} (amount: {} pennies, destination: {})",
                    intent.getId(), order.getOrderNumber(), order.getTotalAmountPennies(), destination.get());
        } else {
            log.info("Created PaymentIntent {} for order {} (amount: {} pennies)",
                    intent.getId(), order.getOrderNumber(), order.getTotalAmountPennies());
        }

        return intent.getClientSecret();
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
                    com.stripe.model.Charge charge = com.stripe.model.Charge.retrieve(intent.getLatestCharge());
                    if (charge.getPaymentMethodDetails() != null
                            && charge.getPaymentMethodDetails().getCard() != null) {
                        var card = charge.getPaymentMethodDetails().getCard();
                        paymentMethodDesc = card.getBrand() + " ending " + card.getLast4();
                    }
                } catch (StripeException e) {
                    log.warn("Could not retrieve charge details for PI {}", intent.getId(), e);
                }
            }

            order.setPaymentStatus(PaymentStatus.CAPTURED);
            order.setPaymentReference(intent.getId());
            order.setPaymentMethod(paymentMethodDesc);
            order.setStatus(OrderStatus.PENDING); // DRAFT → PENDING on successful payment
            order.setUpdatedAt(java.time.OffsetDateTime.now());
            orderRepository.save(order);

            // Record the state change in the transactional outbox (#93) —
            // joins the webhook transaction, published post-commit by the flusher.
            eventPublisher.publishStateChange(
                    order.getId(), order.getTenantId(), order.getShopId(), order.getOrderNumber(),
                    OrderStatus.DRAFT, OrderStatus.PENDING);

            // Publish payment succeeded event (for audit, analytics, reconciliation)
            paymentEventPublisher.publishSucceeded(
                    order.getId(), order.getTenantId(), order.getOrderNumber(),
                    intent.getId(), order.getTotalAmountPennies(), "gbp");

            // Create financial transaction record — canonical ledger owner for
            // card orders (Issue #81 BUG 3). Idempotent on orderId, so the later
            // COMPLETED transition is a no-op. Rate is the order's resolved
            // (predominant) rate, not a hardcoded STANDARD literal (BUG 2).
            financialTransactionService.createTransaction(
                    new CreateTransactionRequest(
                            order.getTotalAmountPennies(),
                            order.getVatRate(),
                            "Payment " + intent.getId() + " for Order " + order.getOrderNumber(),
                            order.getId()
                    ));

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

            order.setPaymentStatus(PaymentStatus.FAILED);
            order.setPaymentReference(intent.getId());
            order.setUpdatedAt(java.time.OffsetDateTime.now());
            orderRepository.save(order);

            String failureReason = intent.getLastPaymentError() != null
                    ? intent.getLastPaymentError().getMessage()
                    : "unknown";

            // Publish payment failed event
            paymentEventPublisher.publishFailed(
                    order.getId(), order.getTenantId(), order.getOrderNumber(),
                    intent.getId(), order.getTotalAmountPennies(), "gbp", failureReason);

            log.warn("Payment failed for order {} — PI: {}: {}",
                    order.getOrderNumber(), intent.getId(), failureReason);
        } finally {
            TenantContext.clear();
        }
    }
}
