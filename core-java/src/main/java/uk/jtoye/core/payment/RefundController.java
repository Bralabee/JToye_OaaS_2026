package uk.jtoye.core.payment;

import com.stripe.exception.StripeException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.payment.dto.CreateRefundRequest;
import uk.jtoye.core.payment.dto.RefundDto;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Vendor-facing refund endpoints (VOPS-02).
 *
 * <p><b>Access control (issue #83 P1-1):</b> refunds now require the
 * {@code admin} realm role — the class-level
 * {@code @PreAuthorize("hasRole('admin')")} gate rejects any non-admin caller
 * with 403. This supersedes the Phase 17 UC-5 deferral, which previously
 * allowed any JWT-authenticated tenant user to refund. RLS still enforces
 * tenant scoping <em>in addition to</em> the role check (the two are
 * complementary — a role grants the capability, RLS bounds it to the caller's
 * tenant), and the refund target is validated server-side by
 * {@link RefundService}.
 *
 * <p><b>BL-01 fix:</b> hard-code the {@code /api/v1} prefix in the
 * {@code @RequestMapping} value rather than relying on
 * {@link uk.jtoye.core.config.WebConfig#configurePathMatch}. WebConfig only
 * applies the prefix to controllers in {@code uk.jtoye.core.{shop,product,
 * order,customer,finance,gdpr,sync}}, and this controller lives in
 * {@code uk.jtoye.core.payment} (which is intentionally excluded so the
 * {@link PaymentController} can keep its {@code /public/payments/webhook}
 * mapping for Stripe). Adding {@code uk.jtoye.core.payment} to WebConfig
 * would unintentionally rewrite {@code PaymentController} to
 * {@code /api/v1/public/payments/webhook} and break Stripe webhooks.
 */
@RestController
@RequestMapping("/api/v1/orders")
@PreAuthorize("hasRole('admin')")  // issue #83 P1-1: refunds require the admin realm role
@Tag(name = "Refunds", description = "Stripe refund issuance for vendor orders")
@SecurityRequirement(name = "bearer-jwt")
public class RefundController {

    private static final Logger log = LoggerFactory.getLogger(RefundController.class);

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /**
     * Issue a refund for an order. {@code Idempotency-Key} header is optional
     * but recommended; same key for two POSTs returns the same refund without
     * invoking Stripe twice (server-side dedup).
     */
    @PostMapping("/{orderId}/refund")
    @Operation(
            summary = "Issue a refund for an order",
            description = "Creates a Stripe refund and records it. Idempotency-Key header recommended; "
                        + "same key returns the same refund without invoking Stripe twice."
    )
    public ResponseEntity<RefundDto> createRefund(
            @PathVariable UUID orderId,
            @Valid @RequestBody CreateRefundRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) throws StripeException {
        RefundDto refund = refundService.createRefund(orderId, request, idempotencyKey);
        URI location = URI.create("/api/v1/orders/" + orderId + "/refunds/" + refund.id());
        // Always return 201 on success — the resource exists either way and
        // the Location header points to the same URI on replay. Frontend
        // treats 201 == success.
        log.info("Refund {} created for order {} (Stripe={}, status={})",
                refund.id(), orderId, refund.stripeRefundId(), refund.status());
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(refund);
    }

    /**
     * List refunds for an order, newest first.
     */
    @GetMapping("/{orderId}/refunds")
    @Operation(summary = "List refunds for an order", description = "Returns refunds newest-first (requestedAt DESC).")
    public ResponseEntity<List<RefundDto>> listRefunds(@PathVariable UUID orderId) {
        return ResponseEntity.ok(refundService.findByOrderId(orderId));
    }

    /**
     * Single-refund GET — the resource the POST's Location header points at
     * (issue #97: every 201 Location must dereference to a 200).
     */
    @GetMapping("/{orderId}/refunds/{refundId}")
    @Operation(
            summary = "Get a single refund for an order",
            description = "Dereferences the Location header returned by POST /orders/{orderId}/refund. "
                        + "404 when the refund does not exist under that order (or belongs to another tenant)."
    )
    public ResponseEntity<RefundDto> getRefund(
            @PathVariable UUID orderId,
            @PathVariable UUID refundId
    ) {
        return ResponseEntity.ok(refundService.findRefund(orderId, refundId));
    }
}
