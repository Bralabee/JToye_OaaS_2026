package uk.jtoye.core.tenant;

import com.stripe.exception.StripeException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uk.jtoye.core.exception.InvalidStateTransitionException;
import uk.jtoye.core.payment.StripeConnectService;
import uk.jtoye.core.payment.dto.ConnectAccountDto;
import uk.jtoye.core.tenant.dto.CreateTenantRequest;
import uk.jtoye.core.tenant.dto.TenantDto;
import uk.jtoye.core.tenant.keycloak.KeycloakDeprovisionResult;
import uk.jtoye.core.tenant.keycloak.KeycloakDeprovisionService;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Platform-admin tenant lifecycle API (issue #102 [P2-11] AC1) — the
 * production replacement for "an engineer running SQL". NOT the dev-only
 * {@link DevTenantController}, which stays untouched (and dev/local-gated).
 *
 * <p><b>Authz:</b> class-level {@code @PreAuthorize("hasRole('admin')")} — the
 * repo's privileged-operator RBAC pattern (issue #83 P1-1), identical to the
 * refunds/finance/GDPR surfaces. A dedicated {@code platform-admin} realm role
 * split (separating platform operators from tenant admins) is a documented
 * follow-up; today {@code admin} is the platform's single privileged role.
 *
 * <p><b>Path:</b> hard-mapped at {@code /api/v1/admin/tenants} (the
 * {@code tenant} package is deliberately NOT in {@code WebConfig.API_V1_PACKAGES}
 * so {@code /dev/tenants} keeps its literal mapping — same precedent as
 * {@code RefundController}). {@code TenantStatusInterceptor} exempts this
 * surface so lifecycle management stays reachable even if the caller's own
 * tenant is suspended (no admin lockout).
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@PreAuthorize("hasRole('admin')")
@Tag(name = "Tenant Admin", description = "Platform-admin tenant lifecycle: create, suspend, reactivate, offboard, Stripe Connect linkage")
@SecurityRequirement(name = "bearer-jwt")
public class TenantAdminController {

    private final TenantLifecycleService lifecycleService;
    private final StripeConnectService stripeConnectService;
    private final KeycloakDeprovisionService keycloakDeprovisionService;

    public TenantAdminController(TenantLifecycleService lifecycleService,
                                 StripeConnectService stripeConnectService,
                                 KeycloakDeprovisionService keycloakDeprovisionService) {
        this.lifecycleService = lifecycleService;
        this.stripeConnectService = stripeConnectService;
        this.keycloakDeprovisionService = keycloakDeprovisionService;
    }

    @PostMapping
    @Operation(summary = "Create tenant",
            description = "Creates an ACTIVE tenant in the registry (no SQL required)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Tenant created"),
            @ApiResponse(responseCode = "409", description = "Tenant name already exists")
    })
    public ResponseEntity<TenantDto> create(@Valid @RequestBody CreateTenantRequest request) {
        TenantDto dto = lifecycleService.create(request);
        // House rule: build Location from the current request, never hand-build
        // the path (WebConfig API_V1_PREFIX javadoc).
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(dto.id()).toUri();
        return ResponseEntity.created(location).body(dto);
    }

    @GetMapping
    @Operation(summary = "List tenants", description = "Lists all tenants in the registry")
    public ResponseEntity<List<TenantDto>> list() {
        return ResponseEntity.ok(lifecycleService.list());
    }

    @GetMapping("/{tenantId}")
    @Operation(summary = "Get tenant", description = "Returns one tenant registry row")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Tenant found"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    public ResponseEntity<TenantDto> get(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(lifecycleService.get(tenantId));
    }

    @PostMapping("/{tenantId}/suspend")
    @Operation(summary = "Suspend tenant",
            description = "ACTIVE -> SUSPENDED. The tenant's API traffic is rejected (403) until reactivated.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Tenant suspended"),
            @ApiResponse(responseCode = "400", description = "Illegal transition (not ACTIVE)"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    public ResponseEntity<TenantDto> suspend(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(lifecycleService.suspend(tenantId));
    }

    @PostMapping("/{tenantId}/reactivate")
    @Operation(summary = "Reactivate tenant", description = "SUSPENDED -> ACTIVE. Restores API traffic.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Tenant reactivated"),
            @ApiResponse(responseCode = "400", description = "Illegal transition (not SUSPENDED)"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    public ResponseEntity<TenantDto> reactivate(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(lifecycleService.reactivate(tenantId));
    }

    @PostMapping("/{tenantId}/offboard")
    @Operation(summary = "Offboard tenant",
            description = "ACTIVE|SUSPENDED -> OFFBOARDED (terminal). API traffic permanently rejected. "
                    + "Keycloak user deprovisioning runs best-effort after the transaction commits "
                    + "(inert unless configured); a Keycloak outage never rolls back the offboard.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Tenant offboarded"),
            @ApiResponse(responseCode = "400", description = "Illegal transition (already OFFBOARDED)"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    public ResponseEntity<TenantDto> offboard(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(lifecycleService.offboard(tenantId));
    }

    /**
     * Re-trigger Keycloak deprovisioning for an already-OFFBOARDED tenant (issue
     * #102 remainder) — a recovery hook for when the after-commit sweep failed
     * (Keycloak was unreachable) or the feature was only enabled after offboard.
     * Idempotent: an already-deprovisioned tenant returns its existing marker.
     *
     * <p>Order of guards is deliberate so the two failure modes are distinct 400s
     * even with the feature off: OFFBOARDED-only is checked first (a non-terminal
     * tenant is never a valid target regardless of config), then not-configured.
     */
    @PostMapping("/{tenantId}/keycloak/deprovision")
    @Operation(summary = "Re-trigger Keycloak user deprovisioning",
            description = "Disables + logs out the OFFBOARDED tenant's Keycloak users across configured "
                    + "realms and stamps keycloak_deprovisioned_at on full success. Idempotent. "
                    + "Requires the tenant to be OFFBOARDED and the feature to be configured.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Deprovisioning run result (count + marker)"),
            @ApiResponse(responseCode = "400", description = "Tenant not OFFBOARDED, or Keycloak admin not configured"),
            @ApiResponse(responseCode = "403", description = "Caller is not a platform admin"),
            @ApiResponse(responseCode = "404", description = "Tenant not found")
    })
    public ResponseEntity<KeycloakDeprovisionResult> deprovisionKeycloak(@PathVariable UUID tenantId) {
        TenantDto tenant = lifecycleService.get(tenantId); // 404 if missing (reuses not-found path)
        if (tenant.status() != TenantStatus.OFFBOARDED) {
            throw new InvalidStateTransitionException(
                    "Tenant must be OFFBOARDED to deprovision Keycloak users (was " + tenant.status() + ")");
        }
        if (!keycloakDeprovisionService.configured()) {
            // Matches the Stripe not-configured precedent (IllegalStateException -> 400).
            throw new IllegalStateException("Keycloak admin is not configured — cannot deprovision users");
        }
        return ResponseEntity.ok(keycloakDeprovisionService.deprovision(tenantId));
    }

    /**
     * Create (or resume) the tenant's Stripe Express connected account and
     * return a fresh onboarding link (issue #102 AC2, ADR-0001 Decision 2).
     */
    @PostMapping("/{tenantId}/stripe/connect")
    @Operation(summary = "Create Stripe Connect account + onboarding link",
            description = "Creates an Express connected account for the tenant (idempotent: reuses an "
                    + "existing linked account) and returns a single-use Stripe-hosted onboarding link. "
                    + "Account capability state is then driven by the account.updated webhook.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Connected account ready; onboarding link returned"),
            @ApiResponse(responseCode = "400", description = "Tenant not ACTIVE or Stripe not configured"),
            @ApiResponse(responseCode = "404", description = "Tenant not found"),
            @ApiResponse(responseCode = "502", description = "Stripe API error")
    })
    public ResponseEntity<ConnectAccountDto> connectStripe(@PathVariable UUID tenantId) throws StripeException {
        return ResponseEntity.ok(stripeConnectService.createOrResumeExpressOnboarding(tenantId));
    }
}
