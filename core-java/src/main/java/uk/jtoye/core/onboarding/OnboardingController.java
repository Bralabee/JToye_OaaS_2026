package uk.jtoye.core.onboarding;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uk.jtoye.core.onboarding.dto.CreateOnboardingRequest;
import uk.jtoye.core.onboarding.dto.OnboardingDto;
import uk.jtoye.core.onboarding.dto.UpdateOnboardingRequest;

import java.net.URI;

/**
 * Vendor-facing onboarding endpoints. Thin controller — every method delegates
 * to {@link VendorOnboardingService}, which resolves the tenant server-side
 * ({@code CurrentTenant.require()}). No endpoint reads a tenant from the request;
 * the admin approve/reject queue lives on {@link OnboardingAdminController}
 * (#178 slice 2). Go-live is the vendor's guarded publish action (18-05).
 */
@RestController
@RequestMapping("/onboarding")
@Tag(name = "Onboarding", description = "Vendor onboarding lifecycle endpoints")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class OnboardingController {

    private final VendorOnboardingService vendorOnboardingService;

    public OnboardingController(VendorOnboardingService vendorOnboardingService) {
        this.vendorOnboardingService = vendorOnboardingService;
    }

    /**
     * Create a DRAFT onboarding for the authenticated tenant.
     * POST /onboarding
     */
    @PostMapping
    @Operation(summary = "Create onboarding",
            description = "Creates a DRAFT onboarding for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Onboarding created (DRAFT)"),
            @ApiResponse(responseCode = "400", description = "Validation error or missing tenant"),
            @ApiResponse(responseCode = "409", description = "An onboarding already exists for this tenant")
    })
    public ResponseEntity<OnboardingDto> create(
            @Parameter(description = "Onboarding creation request") @Valid @RequestBody CreateOnboardingRequest req) {
        OnboardingDto dto = vendorOnboardingService.createOnboarding(
                req.getModel(), req.getShopId(), req.getCompanyNumber());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/me")
                .build()
                .toUri();
        return ResponseEntity.created(location).body(dto);
    }

    /**
     * Submit the caller's onboarding (DRAFT → VERIFYING).
     * POST /onboarding/submit
     */
    @PostMapping("/submit")
    @Operation(summary = "Submit onboarding",
            description = "Submits the caller's onboarding (DRAFT -> VERIFYING) and kicks off the gate chain")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Onboarding submitted (VERIFYING)"),
            @ApiResponse(responseCode = "400", description = "Illegal transition (not in DRAFT)"),
            @ApiResponse(responseCode = "404", description = "No onboarding for this tenant")
    })
    public ResponseEntity<OnboardingDto> submit() {
        return ResponseEntity.ok(vendorOnboardingService.submit());
    }

    /**
     * Resubmit the caller's onboarding after a gate failure (ACTION_REQUIRED → VERIFYING).
     * POST /onboarding/resubmit
     */
    @PostMapping("/resubmit")
    @Operation(summary = "Resubmit onboarding",
            description = "Resets the flagged (FAILED/MANUAL_REVIEW) gates and re-runs the gate chain "
                    + "(ACTION_REQUIRED -> VERIFYING)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Onboarding resubmitted (VERIFYING)"),
            @ApiResponse(responseCode = "400", description = "Illegal transition (not in ACTION_REQUIRED)"),
            @ApiResponse(responseCode = "404", description = "No onboarding for this tenant")
    })
    public ResponseEntity<OnboardingDto> resubmit() {
        return ResponseEntity.ok(vendorOnboardingService.resubmit());
    }

    /**
     * Withdraw the caller's in-progress onboarding (any pre-live state → WITHDRAWN).
     * POST /onboarding/withdraw
     */
    @PostMapping("/withdraw")
    @Operation(summary = "Withdraw onboarding",
            description = "Withdraws the caller's onboarding from any pre-live state "
                    + "(DRAFT/VERIFYING/ACTION_REQUIRED/PENDING_APPROVAL/APPROVED -> WITHDRAWN). "
                    + "Terminal — restarting requires a new application.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Onboarding withdrawn (WITHDRAWN)"),
            @ApiResponse(responseCode = "400", description = "Illegal transition (already terminal / LIVE / SUSPENDED)"),
            @ApiResponse(responseCode = "404", description = "No onboarding for this tenant")
    })
    public ResponseEntity<OnboardingDto> withdraw() {
        return ResponseEntity.ok(vendorOnboardingService.withdraw());
    }

    /**
     * Take the caller's onboarding live (APPROVED → LIVE), publishing the shop.
     * POST /onboarding/go-live
     */
    @PostMapping("/go-live")
    @Operation(summary = "Go live",
            description = "Fires GO_LIVE for the caller's onboarding, publishing the shop. Rejected (400) "
                    + "unless every mandatory gate and the allergen-completeness gate are PASSED/WAIVED.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Onboarding live; shop published"),
            @ApiResponse(responseCode = "400", description = "Gates not satisfied or illegal transition (not in APPROVED)"),
            @ApiResponse(responseCode = "404", description = "No onboarding for this tenant")
    })
    public ResponseEntity<OnboardingDto> goLive() {
        return ResponseEntity.ok(vendorOnboardingService.goLive());
    }

    /**
     * Correct the caller's onboarding company number (blank = sole trader).
     * POST /onboarding/company-number
     *
     * <p>Chosen verb: POST to match the all-POST vendor surface — this controller
     * has no {@code @PatchMapping} precedent. The company number is re-validated
     * exactly like create and is only editable while the vendor is still building /
     * fixing the application (DRAFT / ACTION_REQUIRED); the service rejects any other
     * state with an RFC 7807 400.
     */
    @PostMapping("/company-number")
    @Operation(summary = "Update onboarding company number",
            description = "Corrects the caller's onboarding company number (blank/whitespace = sole trader). "
                    + "Permitted only in DRAFT or ACTION_REQUIRED; re-validated exactly like create. "
                    + "Fires no state-machine event — the state machine stays the sole writer of published.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Company number updated"),
            @ApiResponse(responseCode = "400", description = "Invalid state (not DRAFT/ACTION_REQUIRED) or malformed company number"),
            @ApiResponse(responseCode = "404", description = "No onboarding for this tenant")
    })
    public ResponseEntity<OnboardingDto> updateCompanyNumber(
            @Parameter(description = "Company-number correction request")
            @Valid @RequestBody UpdateOnboardingRequest req) {
        return ResponseEntity.ok(vendorOnboardingService.updateCompanyNumber(req.getCompanyNumber()));
    }

    /**
     * Read the caller's onboarding status plus its per-gate breakdown.
     * GET /onboarding/me
     */
    @GetMapping("/me")
    @Operation(summary = "Get my onboarding",
            description = "Returns the caller-tenant's onboarding status and per-gate breakdown")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Onboarding found"),
            @ApiResponse(responseCode = "404", description = "No onboarding for this tenant")
    })
    public ResponseEntity<OnboardingDto> me() {
        return ResponseEntity.ok(vendorOnboardingService.getMyOnboarding());
    }
}
