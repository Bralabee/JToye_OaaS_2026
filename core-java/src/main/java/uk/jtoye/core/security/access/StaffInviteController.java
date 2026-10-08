package uk.jtoye.core.security.access;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.security.access.StaffInviteService.IssueResult;
import uk.jtoye.core.security.access.dto.CreateStaffInviteRequest;
import uk.jtoye.core.security.access.dto.StaffInviteDto;

/**
 * Staff invitations (D-07, Phase 37-07): a Group admin invites a person by email with the exact grant
 * they will receive. GROUP_ADMIN only — the gate is {@link ShopAccessService#requireGroupAdmin()} in
 * every {@link StaffInviteService} method (the {@code StaffController} precedent), so any other caller
 * gets the typed 403 {@code shop-access-denied}.
 *
 * <p>Hard-mapped at {@code /api/v1/staff/invites}: the {@code security.access} package is not in
 * {@code WebConfig.API_V1_PACKAGES}. No MCP tool, by recorded reason: an agent must not mint human
 * staff (RESEARCH 37-B.3).
 */
@RestController
@RequestMapping("/api/v1/staff/invites")
@Tag(name = "Staff", description = "Vendor shop-staff management: list / grant / revoke / invite (GROUP_ADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
public class StaffInviteController {

    private final StaffInviteService staffInviteService;

    public StaffInviteController(StaffInviteService staffInviteService) {
        this.staffInviteService = staffInviteService;
    }

    @PostMapping
    @Operation(summary = "Invite a person with one grant",
            description = "Emails {email} a single-use link that expires (jtoye.staff.invite.ttl-hours, default "
                    + "72). Accepting it gives exactly {role} on {shopId}, or on every shop when shopId is null "
                    + "(always null for GROUP_ADMIN). Idempotent per (email, shop, role): while an invitation for "
                    + "the same triple is open, the call returns it with 200 and sends no second email. The "
                    + "address is trimmed and lower-cased.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Invitation created and emailed"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay of the open invitation for the same triple; no email"),
            @ApiResponse(responseCode = "400", description = "Invalid request (a shop-scoped GROUP_ADMIN, or not one valid email address)"),
            @ApiResponse(responseCode = "403", description = "Caller is not a GROUP_ADMIN (shop-access-denied)"),
            @ApiResponse(responseCode = "404", description = "The shop is not a shop of this business")
    })
    public ResponseEntity<StaffInviteDto> issue(@Valid @RequestBody CreateStaffInviteRequest request) {
        IssueResult result = staffInviteService.issue(request);
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.invite());
    }
}
