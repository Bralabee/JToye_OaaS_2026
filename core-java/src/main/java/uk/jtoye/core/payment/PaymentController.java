package uk.jtoye.core.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/public/payments")
@Tag(name = "Payments", description = "Stripe payment processing endpoints (public, no auth required)")
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
}
