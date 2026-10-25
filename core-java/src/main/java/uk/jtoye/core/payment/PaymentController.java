package uk.jtoye.core.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public Stripe payment endpoints.
 *
 * <p><b>Versioning (issue #97 [P2-6]):</b> canonical path is
 * {@code /api/v1/public/payments/**}; the bare {@code /public/payments/**}
 * mapping is a legacy alias that MUST stay — the Stripe dashboard webhook is
 * configured against {@code /public/payments/webhook} and removing it would
 * silently drop payment events. Re-point Stripe at the versioned path before
 * ever retiring the alias.
 */
@RestController
@RequestMapping({"/public/payments", "/api/v1/public/payments"})
@Tag(name = "Payments", description = "Stripe payment processing endpoints (public, no auth required). Canonical prefix /api/v1/public/payments; bare /public/payments is a deprecated legacy alias kept for the configured Stripe webhook.")
public class PaymentController {
    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Stripe webhook endpoint.
     * Receives events from Stripe (payment_intent.succeeded, payment_intent.payment_failed, etc.)
     * Must receive raw body for signature verification.
     */
    @PostMapping("/webhook")
    @Operation(summary = "Stripe webhook", description = "Handles Stripe webhook events. Verifies signature before processing.")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        try {
            paymentService.handleWebhookEvent(payload, sigHeader);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            log.warn("Webhook rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Stripe "Connected accounts" webhook endpoint (PAY-03, plan 30-12).
     *
     * <p>Stripe sends Express {@code account.updated} and every direct-charge
     * {@code payment_intent.*} / {@code refund.*} event only to an endpoint registered for the
     * Connected-accounts scope, and signs it with that endpoint's own secret
     * ({@code stripe.connect-webhook-secret}). Register that endpoint at
     * {@code /api/v1/public/payments/webhook/connect}; the platform endpoint at {@code /webhook}
     * refuses any event that carries {@code account}. Raw body, as for {@code /webhook}.
     */
    @PostMapping("/webhook/connect")
    @Operation(summary = "Stripe Connected-accounts webhook",
            description = "Handles Stripe events from the Connected accounts scope (Express account.updated, "
                    + "direct-charge payment_intent.* and refund.*). Verifies the signature against the Connect "
                    + "endpoint's own secret and refuses an event without an account; refuses everything when "
                    + "that secret is not configured.")
    public ResponseEntity<Map<String, String>> handleConnectWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        try {
            paymentService.handleConnectWebhookEvent(payload, sigHeader);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            log.warn("Connect webhook rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
