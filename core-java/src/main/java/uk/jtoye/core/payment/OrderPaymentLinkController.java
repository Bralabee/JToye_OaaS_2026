package uk.jtoye.core.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Vendor-facing endpoint to issue single-use expiring payment links for verified-phone orders
 * (Phase 30, #461 / #462 / PAY-04 / B5).
 *
 * <p>Lives in {@code uk.jtoye.core.payment} alongside {@link RefundController} with an explicit
 * {@code /api/v1/orders} mapping (outside {@code WebConfig.API_V1_PACKAGES}).
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order management endpoints")
@SecurityRequirement(name = "bearer-jwt")
public class OrderPaymentLinkController {

    private final OrderPaymentLinkService paymentLinkService;

    public OrderPaymentLinkController(OrderPaymentLinkService paymentLinkService) {
        this.paymentLinkService = paymentLinkService;
    }

    public record IssuePaymentLinkRequest(String purpose, Integer ttlMinutes) {}

    @PreAuthorize("hasAuthority('SCOPE_orders:write')")
    @PostMapping("/{orderId}/payment-links")
    @Operation(
            summary = "Issue a single-use expiring payment link for an order",
            description = "Requires the customer's phone number on the order to be verified (#461 / #462). "
                    + "Supports UPFRONT, DEPOSIT, and BALANCE payment links and revokes any prior unused link "
                    + "for the same order and purpose."
    )
    public ResponseEntity<OrderPaymentLinkService.IssuedPaymentLinkDto> issuePaymentLink(
            @PathVariable UUID orderId,
            @RequestBody(required = false) IssuePaymentLinkRequest request) {
        String purpose = request != null ? request.purpose() : "UPFRONT";
        Integer ttlMinutes = request != null ? request.ttlMinutes() : null;
        OrderPaymentLinkService.IssuedPaymentLinkDto dto =
                paymentLinkService.issuePaymentLink(orderId, purpose, ttlMinutes);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }
}
