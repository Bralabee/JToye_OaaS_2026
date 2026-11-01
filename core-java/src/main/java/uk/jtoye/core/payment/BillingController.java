package uk.jtoye.core.payment;

import com.stripe.exception.StripeException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.payment.dto.BillingSubscriptionDto;
import uk.jtoye.core.payment.dto.BillingSubscriptionRequest;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;

import java.util.UUID;

/**
 * Vendor recurring subscription & commercial plan controller
 * ({@code GAP-GTM-05}, {@code PAY-02}, {@code B8}, {@code B9}, {@code D-B9}).
 *
 * <p><b>Access control (issue #1020):</b> every endpoint calls
 * {@link ShopAccessService#requireGroupAdmin()} as its first statement (matching
 * finance, staff and webhooks) so an honoured tenant-wide {@code GROUP_ADMIN} or a
 * Keycloak realm {@code admin} ({@code ROLE_admin}) is admitted, and every other
 * caller is refused with the typed 403 {@code https://jtoye.uk/errors/shop-access-denied}
 * ({@code requiredRole = "GROUP_ADMIN"}).
 */
@RestController
@RequestMapping({"/api/v1/billing/subscription", "/billing/subscription"})
@Tag(name = "Billing", description = "Plan-configurable recurring subscription billing & fee metering")
@SecurityRequirement(name = "bearer-jwt")
public class BillingController {

    private final StripeBillingService billingService;
    private final ShopAccessService shopAccessService;

    public BillingController(StripeBillingService billingService, ShopAccessService shopAccessService) {
        this.billingService = billingService;
        this.shopAccessService = shopAccessService;
    }

    @GetMapping
    @Operation(summary = "Get current tenant billing plan and subscription status")
    public ResponseEntity<BillingSubscriptionDto> getSubscription() {
        shopAccessService.requireGroupAdmin();
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        return ResponseEntity.ok(billingService.getSubscription(tenantId));
    }

    @PostMapping
    @Operation(summary = "Subscribe or switch the tenant's recurring billing plan (GAP-GTM-05 / PAY-02 / D-B9)")
    public ResponseEntity<BillingSubscriptionDto> subscribeOrUpdate(
            @Valid @RequestBody BillingSubscriptionRequest request) throws StripeException {
        shopAccessService.requireGroupAdmin();
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        return ResponseEntity.ok(billingService.subscribeOrUpdatePlan(tenantId, request));
    }

    @PostMapping("/complete-walk-setup")
    @Operation(summary = "Complete WALK_SETUP_STEPDOWN onboarding book and step down from £195/mo to 2.5% capped at £39/mo (B9 / D-B9)")
    public ResponseEntity<BillingSubscriptionDto> completeWalkSetup() throws StripeException {
        shopAccessService.requireGroupAdmin();
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        return ResponseEntity.ok(billingService.completeWalkSetup(tenantId));
    }

    @PostMapping("/cancel")
    @Operation(summary = "Self-serve subscription cancellation with zero exit fee (B8)")
    public ResponseEntity<BillingSubscriptionDto> cancelSubscription(
            @RequestParam(name = "immediate", defaultValue = "true") boolean immediate) throws StripeException {
        shopAccessService.requireGroupAdmin();
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        return ResponseEntity.ok(billingService.cancelSubscription(tenantId, immediate));
    }
}
