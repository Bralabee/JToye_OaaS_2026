package uk.jtoye.core.payment;

import com.stripe.exception.StripeException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.payment.dto.BillingSubscriptionDto;
import uk.jtoye.core.payment.dto.BillingSubscriptionRequest;
import uk.jtoye.core.security.TenantContext;

import java.util.UUID;

/**
 * Vendor recurring subscription & commercial plan controller
 * ({@code GAP-GTM-05}, {@code PAY-02}, {@code B8}, {@code B9}, {@code D-B9}).
 */
@RestController
@RequestMapping({"/api/v1/billing/subscription", "/billing/subscription"})
@Tag(name = "Billing", description = "Plan-configurable recurring subscription billing & fee metering")
@SecurityRequirement(name = "bearer-jwt")
public class BillingController {

    private final StripeBillingService billingService;

    public BillingController(StripeBillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'TENANT_ADMIN', 'VENDOR', 'OPERATOR')")
    @Operation(summary = "Get current tenant billing plan and subscription status")
    public ResponseEntity<BillingSubscriptionDto> getSubscription() {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        return ResponseEntity.ok(billingService.getSubscription(tenantId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'TENANT_ADMIN', 'VENDOR', 'OPERATOR')")
    @Operation(summary = "Subscribe or switch the tenant's recurring billing plan (GAP-GTM-05 / PAY-02 / D-B9)")
    public ResponseEntity<BillingSubscriptionDto> subscribeOrUpdate(
            @Valid @RequestBody BillingSubscriptionRequest request) throws StripeException {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        return ResponseEntity.ok(billingService.subscribeOrUpdatePlan(tenantId, request));
    }

    @PostMapping("/complete-walk-setup")
    @PreAuthorize("hasAnyRole('ADMIN', 'TENANT_ADMIN', 'VENDOR', 'OPERATOR')")
    @Operation(summary = "Complete WALK_SETUP_STEPDOWN onboarding book and step down from £195/mo to 2.5% capped at £39/mo (B9 / D-B9)")
    public ResponseEntity<BillingSubscriptionDto> completeWalkSetup() throws StripeException {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        return ResponseEntity.ok(billingService.completeWalkSetup(tenantId));
    }

    @PostMapping("/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'TENANT_ADMIN', 'VENDOR', 'OPERATOR')")
    @Operation(summary = "Self-serve subscription cancellation with zero exit fee (B8)")
    public ResponseEntity<BillingSubscriptionDto> cancelSubscription(
            @RequestParam(name = "immediate", defaultValue = "true") boolean immediate) throws StripeException {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        return ResponseEntity.ok(billingService.cancelSubscription(tenantId, immediate));
    }
}
