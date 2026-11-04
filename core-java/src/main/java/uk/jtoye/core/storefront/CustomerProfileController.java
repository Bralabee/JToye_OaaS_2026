package uk.jtoye.core.storefront;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uk.jtoye.core.security.CustomerJwtVerifier;
import uk.jtoye.core.tenant.keycloak.CustomerProfileService;

/**
 * SW-JRD-07 / SW-SAM-11 (owner ruling 2026-11-04, persona close-out): a signed-in customer changes the
 * display name on their account.
 *
 * <p>The account is chosen by the VERIFIED {@code X-Customer-Token} and nothing else: the body carries
 * only the new name. The same header and verifier as {@code GET /public/orders/mine}; a missing or bad
 * token is a 401. A PUT of the full desired state, so it is idempotent by construction and needs no
 * Idempotency-Key: repeating it leaves the same name. No tenant is involved (the customer realm is
 * platform-wide and this touches no tenant row), so no TenantContext or RLS applies.
 *
 * <p>There is deliberately no MCP tool: it is a customer editing their own profile, and the MCP server
 * acts for a vendor under a vendor credential.
 */
@RestController
@RequestMapping({"/public/customer/profile", "/api/v1/public/customer/profile"})
@Tag(name = "Public Storefront")
public class CustomerProfileController {

    private final CustomerJwtVerifier customerJwtVerifier;
    private final CustomerProfileService profileService;

    public CustomerProfileController(CustomerJwtVerifier customerJwtVerifier, CustomerProfileService profileService) {
        this.customerJwtVerifier = customerJwtVerifier;
        this.profileService = profileService;
    }

    @Schema(description = "The new display name for the signed-in customer.")
    public record UpdateDisplayNameRequest(
            @NotNull @Size(min = 1, max = 100)
            @Schema(description = "The name to show on the account. Trimmed; 1 to 100 characters.", example = "Ada Lovelace")
            String displayName) { }

    @Schema(description = "The display name as saved.")
    public record DisplayNameResponse(String displayName) { }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Change the signed-in customer's display name",
            description = "Sets the name on the customer's sign-in account. The account is the one the verified "
                    + "X-Customer-Token proves; the body names no account. Idempotent (a PUT of the desired state).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The name as saved"),
            @ApiResponse(responseCode = "400", description = "A blank, over-long or control-character name"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid customer token"),
            @ApiResponse(responseCode = "404", description = "No account for this token"),
            @ApiResponse(responseCode = "503", description = "Name changes are unavailable right now "
                    + "(errors/profile-update-unavailable)")
    })
    public ResponseEntity<DisplayNameResponse> updateDisplayName(
            @Parameter(description = "The signed-in customer's jtoye-customers access token.")
            @RequestHeader(name = "X-Customer-Token", required = false) String customerToken,
            @Valid @RequestBody UpdateDisplayNameRequest request) {
        CustomerJwtVerifier.CustomerIdentity who = customerJwtVerifier.verifiedIdentity(customerToken);
        if (who.subject() == null || who.subject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer authentication required");
        }
        String saved = profileService.updateDisplayName(who.subject(), who.email(), request.displayName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new DisplayNameResponse(saved));
    }
}
