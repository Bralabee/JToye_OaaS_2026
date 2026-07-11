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
 * ({@code CurrentTenant.require()}). No endpoint reads a tenant from the request,
 * and go-live / admin-queue endpoints are intentionally NOT here (go-live lands
 * in 18-05; the admin queue is deferred).
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
