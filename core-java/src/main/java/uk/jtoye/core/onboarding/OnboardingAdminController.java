package uk.jtoye.core.onboarding;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import uk.jtoye.core.onboarding.dto.AdminOnboardingDto;
import uk.jtoye.core.onboarding.dto.RejectOnboardingRequest;
import uk.jtoye.core.onboarding.dto.ResolveGateRequest;

import java.util.List;
import java.util.UUID;

/**
 * Admin approve/reject queue for vendor onboarding (#178 slice 2 / ADR-0001
 * Decision 1). MARKETPLACE onboardings always park at PENDING_APPROVAL for a
 * human (auto-approve is WHITE_LABEL-only by default), and this controller is
 * that human's surface: list what awaits review, approve, or reject with a
 * required reason. Every state change is driven through
 * {@link VendorOnboardingService}'s single canonical transition path — the state
 * machine (and its APPROVE guard re-checking every mandatory gate) stays the
 * sole authority; no endpoint writes a status directly.
 *
 * <p><strong>Scope — tenant-scoped admin, platform-wide queue is a follow-up.</strong>
 * The repo's RBAC (#83) has exactly one privileged realm role, {@code admin},
 * and every JWT carries a single {@code tenant_id} that FORCE-RLS pins all reads
 * to (V43 policies). There is no platform-admin identity that can see across
 * tenants, so this queue lists only the caller-tenant's pending application(s)
 * — the same trust boundary as the other {@code hasRole('admin')} surfaces
 * (finance, GDPR, refunds). A true cross-tenant platform queue needs a separate
 * platform-operator role plus a deliberate, audited RLS bypass (or per-tenant
 * fan-out) and is documented as follow-up work on #178.
 *
 * <p><strong>Gate-resolve authority is INTERIM (Phase 21 / D-01).</strong> The
 * {@code POST /{id}/gates/{gateType}/resolve} control lets a stuck MANUAL_REVIEW
 * gate be unstuck, and the {@code GET /reviews} queue surfaces those parked
 * applications. Per D-01 ("seams now, J'Toye console later") the resolver is the
 * tenant's OWN {@code admin} — the same tenant-scoped trust boundary as approve/
 * reject, NOT a cross-tenant J'Toye operator. The real platform-operator reviewer
 * (with cross-tenant oversight) is a deferred phase; this endpoint is the seam.
 */
@RestController
@RequestMapping("/onboarding/admin")
@PreAuthorize("hasRole('admin')")  // #83 RBAC pattern: approvals require the admin realm role
@Tag(name = "Onboarding Admin", description = "Admin approve/reject queue for vendor onboarding")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class OnboardingAdminController {

    private final VendorOnboardingService vendorOnboardingService;

    public OnboardingAdminController(VendorOnboardingService vendorOnboardingService) {
        this.vendorOnboardingService = vendorOnboardingService;
    }

    /**
     * List onboardings awaiting human approval (oldest submission first).
     * GET /onboarding/admin/pending
     */
    @GetMapping("/pending")
    @Operation(summary = "List pending approvals",
            description = "Onboardings in PENDING_APPROVAL for the caller's tenant, oldest submission first, "
                    + "each with its gate breakdown. Requires the admin realm role.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Pending applications (possibly empty)"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the admin role")
    })
    public ResponseEntity<List<AdminOnboardingDto>> pending() {
        return ResponseEntity.ok(vendorOnboardingService.listPendingApproval());
    }

    /**
     * Approve an onboarding (PENDING_APPROVAL → APPROVED).
     * POST /onboarding/admin/{id}/approve
     */
    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve onboarding",
            description = "Fires APPROVE (PENDING_APPROVAL -> APPROVED) via the state machine. The APPROVE "
                    + "guard re-checks that every mandatory gate is PASSED/WAIVED and vetoes (400) otherwise.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Onboarding approved"),
            @ApiResponse(responseCode = "400", description = "Illegal transition or gate guard veto"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the admin role"),
            @ApiResponse(responseCode = "404", description = "Onboarding not found")
    })
    public ResponseEntity<AdminOnboardingDto> approve(
            @Parameter(description = "Onboarding id") @PathVariable UUID id) {
        return ResponseEntity.ok(vendorOnboardingService.approve(id));
    }

    /**
     * Reject an onboarding with a required human reason.
     * POST /onboarding/admin/{id}/reject
     */
    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject onboarding",
            description = "Persists the required human reason on the aggregate (Envers-audited) and fires "
                    + "REJECT (VERIFYING/ACTION_REQUIRED/PENDING_APPROVAL -> REJECTED, terminal).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Onboarding rejected; reason recorded"),
            @ApiResponse(responseCode = "400", description = "Missing/blank reason or illegal transition"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the admin role"),
            @ApiResponse(responseCode = "404", description = "Onboarding not found")
    })
    public ResponseEntity<AdminOnboardingDto> reject(
            @Parameter(description = "Onboarding id") @PathVariable UUID id,
            @Parameter(description = "Rejection reason") @Valid @RequestBody RejectOnboardingRequest req) {
        return ResponseEntity.ok(vendorOnboardingService.reject(id, req.getReason()));
    }

    /**
     * Resolve a stuck onboarding gate (ONBD-03 / D-01 seam).
     * POST /onboarding/admin/{id}/gates/{gateType}/resolve
     *
     * <p>Writes the gate row (PASS→PASSED / WAIVE→WAIVED / FAIL→FAILED, Envers-audited)
     * and triggers the existing recompute after commit — the state machine advances
     * itself (GATES_PASSED / GATE_FAILED); this endpoint never writes status/published.
     * Interim resolver = the tenant's own admin (see class Javadoc).
     */
    @PostMapping("/{id}/gates/{gateType}/resolve")
    @Operation(summary = "Resolve an onboarding gate",
            description = "Overrides a gate row (PASS/WAIVE/FAIL) then lets the existing recompute advance the "
                    + "state machine. FAIL requires a reason. Interim resolver = the tenant's own admin (D-01).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Gate resolved; recompute triggered"),
            @ApiResponse(responseCode = "400", description = "FAIL with no reason, or invalid decision/body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the admin role"),
            @ApiResponse(responseCode = "404", description = "Onboarding or gate not found (incl. foreign tenant)")
    })
    public ResponseEntity<AdminOnboardingDto> resolveGate(
            @Parameter(description = "Onboarding id") @PathVariable UUID id,
            @Parameter(description = "Gate type") @PathVariable GateType gateType,
            @Parameter(description = "Gate-resolve decision + optional reason")
            @Valid @RequestBody ResolveGateRequest req) {
        return ResponseEntity.ok(
                vendorOnboardingService.resolveGate(id, gateType, req.getDecision(), req.getReason()));
    }
}
