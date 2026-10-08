package uk.jtoye.core.security.access;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.security.access.StaffInviteService.IssueResult;
import uk.jtoye.core.security.access.dto.CreateStaffInviteRequest;
import uk.jtoye.core.security.access.dto.StaffInviteDto;

import java.util.List;
import java.util.UUID;

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

    @GetMapping
    @Operation(summary = "List invitations",
            description = "Every invitation of the business, newest first. status is computed at read time: "
                    + "OPEN (usable), EXPIRED, ACCEPTED or CANCELLED (cancelled, or replaced by a resend). The "
                    + "link token is never returned.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The invitations"),
            @ApiResponse(responseCode = "403", description = "Caller is not a GROUP_ADMIN (shop-access-denied)")
    })
    public ResponseEntity<List<StaffInviteDto>> list() {
        return ResponseEntity.ok(staffInviteService.list());
    }

    @PostMapping("/{id}/resend")
    @Operation(summary = "Send an invitation again",
            description = "Revokes the invitation (its link stops working) and issues a new one for the same "
                    + "email, shop and role, emailed with a new link. Deliberately not idempotent: each call "
                    + "kills the previous link.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "New invitation created and emailed"),
            @ApiResponse(responseCode = "400", description = "The invitation was already accepted"),
            @ApiResponse(responseCode = "403", description = "Caller is not a GROUP_ADMIN (shop-access-denied)"),
            @ApiResponse(responseCode = "404", description = "No invitation of this business has that id")
    })
    public ResponseEntity<StaffInviteDto> resend(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(staffInviteService.resend(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel an invitation",
            description = "The link stops working. Repeatable: cancelling a cancelled or accepted invitation "
                    + "changes nothing and still returns 204.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Cancelled (or already cancelled / accepted)"),
            @ApiResponse(responseCode = "403", description = "Caller is not a GROUP_ADMIN (shop-access-denied)"),
            @ApiResponse(responseCode = "404", description = "No invitation of this business has that id")
    })
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        staffInviteService.cancel(id);
        return ResponseEntity.noContent().build();
    }
}
