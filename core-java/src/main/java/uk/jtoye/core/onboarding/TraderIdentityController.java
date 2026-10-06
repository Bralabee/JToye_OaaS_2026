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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.onboarding.dto.TraderIdentityDto;
import uk.jtoye.core.onboarding.dto.UpdateTraderIdentityRequest;

/**
 * The tenant's legal entity: the business details customers see before they order (#789). Thin
 * controller; {@link TraderIdentityService} resolves the tenant server-side and gates the write.
 * Served at {@code /api/v1/trader-identity} (this package is in {@code WebConfig.API_V1_PACKAGES}).
 *
 * <p>PUT, not POST: the resource is one-per-tenant and the write is a full replacement, so
 * repeating it is idempotent by construction and needs no Idempotency-Key.
 */
@RestController
@RequestMapping("/trader-identity")
@Tag(name = "Trader identity", description = "The tenant's legal entity shown to customers (CCR 2013 Sch 2, E-Commerce Regs 2002 reg 6)")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class TraderIdentityController {

    private final TraderIdentityService traderIdentityService;

    public TraderIdentityController(TraderIdentityService traderIdentityService) {
        this.traderIdentityService = traderIdentityService;
    }

    @GetMapping
    @Operation(summary = "Get the trader identity",
            description = "Returns the caller tenant's legal entity: legal name, entity type, geographic address, "
                    + "VAT number, and the company number read from the onboarding record.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Trader identity found"),
            @ApiResponse(responseCode = "404", description = "No trader identity is on file for this tenant (RFC 7807)")
    })
    public ResponseEntity<TraderIdentityDto> get() {
        return traderIdentityService.get()
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException(TraderIdentityService.NO_IDENTITY_DETAIL));
    }

    @PutMapping
    @Operation(summary = "Create or replace the trader identity",
            description = "Upserts the caller tenant's legal entity. GROUP_ADMIN only. Idempotent: the same body "
                    + "twice leaves one identity with the same values. The postcode is stored upper-case with one "
                    + "space; the VAT number upper-case with no spaces.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Trader identity saved"),
            @ApiResponse(responseCode = "400", description = "Field validation failed; errors names each field (RFC 7807)"),
            @ApiResponse(responseCode = "403", description = "Caller is not a tenant-wide GROUP_ADMIN (RFC 7807 shop-access-denied)")
    })
    public ResponseEntity<TraderIdentityDto> put(
            @Parameter(description = "The statutory trader details") @Valid @RequestBody UpdateTraderIdentityRequest request) {
        return ResponseEntity.ok(traderIdentityService.upsert(request));
    }
}
