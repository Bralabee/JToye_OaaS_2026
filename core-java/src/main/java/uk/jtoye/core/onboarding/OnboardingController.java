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
