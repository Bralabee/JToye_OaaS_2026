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
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicBoolean;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.finance.FinancialTransactionService;
import uk.jtoye.core.finance.VatRate;
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

    public PaymentService(StripeProperties stripeProperties,
                         OrderRepository orderRepository,
                         OrderEventPublisher eventPublisher,
                         PaymentEventPublisher paymentEventPublisher,
                         FinancialTransactionService financialTransactionService) {
        this.stripeProperties = stripeProperties;
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.paymentEventPublisher = paymentEventPublisher;
        this.financialTransactionService = financialTransactionService;
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
     */
    @CircuitBreaker(name = "stripe")
    public String createPaymentIntent(Order order) throws StripeException {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(order.getTotalAmountPennies())
                .setCurrency("gbp")
                .setDescription("Order " + order.getOrderNumber())
                .putMetadata("order_id", order.getId().toString())
                .putMetadata("order_number", order.getOrderNumber())
                .putMetadata("tenant_id", order.getTenantId().toString())
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build()
                )
                .build();

        PaymentIntent intent = PaymentIntent.create(params);

        log.info("Created PaymentIntent {} for order {} (amount: {} pennies)",
                intent.getId(), order.getOrderNumber(), order.getTotalAmountPennies());

        return intent.getClientSecret();
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

        log.info("Received Stripe event: {} ({})", event.getType(), event.getId());

        switch (event.getType()) {
            case "payment_intent.succeeded" -> handlePaymentIntentSucceeded(event);
            case "payment_intent.payment_failed" -> handlePaymentIntentFailed(event);
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

            // Publish state change event
            eventPublisher.publishStateChange(
                    order.getId(), order.getTenantId(), order.getOrderNumber(),
                    OrderStatus.DRAFT, OrderStatus.PENDING);

            // Publish payment succeeded event (for audit, analytics, reconciliation)
            paymentEventPublisher.publishSucceeded(
                    order.getId(), order.getTenantId(), order.getOrderNumber(),
                    intent.getId(), order.getTotalAmountPennies(), "gbp");

            // Create financial transaction record
            financialTransactionService.createTransaction(
                    new CreateTransactionRequest(
                            order.getTotalAmountPennies(),
                            VatRate.STANDARD,
                            "Payment " + intent.getId() + " for Order " + order.getOrderNumber()
                    ));

            log.info("Payment succeeded for order {} — PI: {}, method: {}",
                    order.getOrderNumber(), intent.getId(), paymentMethodDesc);
        } finally {
            TenantContext.clear();
        }
    }

    private void handlePaymentIntentFailed(Event event) {
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
