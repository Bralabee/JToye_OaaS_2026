package uk.jtoye.core.security.access;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.security.access.dto.AcceptStaffInviteRequest;
import uk.jtoye.core.security.access.dto.StaffInviteAcceptedDto;
import uk.jtoye.core.security.access.dto.StaffInvitePreviewDto;
import uk.jtoye.core.security.access.dto.StaffInviteRefRequest;

/**
 * Where an invited person follows their staff-invitation link (37-08; D-07, D-26). Anonymous by
 * necessity: they have no account yet, and the single-use token that reached only their mailbox IS the
 * authorisation. Under {@code /api/v1/public/**}, so the existing anonymous allowance and the per-IP
 * public rate limit apply.
 *
 * <h2>POST only, the token in the body</h2>
 *
 * The emailed link is {@code {accept-base-url}#token={tenantId}.{token}}: the token rides in the URL
 * fragment, which no browser sends to a server, and the page POSTs it here in a JSON body. There is no
 * GET form and no path form, so the token never appears in a request line, an access log, an APM span, a
 * proxy log or a {@code Referer} (the V75 DSAR-link rule), and a mail scanner that prefetches the link
 * cannot reach either endpoint. Both responses are {@code Cache-Control: no-store}.
 *
 * <h2>One refusal for every unusable link</h2>
 *
 * Expired, used, cancelled, wrong-business, malformed and unknown references all get the same 404 body.
 */
@RestController
@RequestMapping("/api/v1/public/staff-invites")
@Tag(name = "Staff invitations (public)",
        description = "Anonymous accept flow for a staff invitation link. The link's {tenantId}.{token} "
                + "reference is sent in the JSON body, never in the URL.")
public class PublicStaffInviteController {

    private final StaffInviteAcceptService acceptService;

    public PublicStaffInviteController(StaffInviteAcceptService acceptService) {
        this.acceptService = acceptService;
    }

    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Preview a staff invitation",
            description = "Returns the business, role, shop (null = all shops), inviter, invited email and "
                    + "account state (NEW, EXISTS_HERE, OTHER_BUSINESS) of an open invitation. Read-only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The invitation is open. Cache-Control: no-store."),
            @ApiResponse(responseCode = "404", description = "The link can't be used: expired, used, "
                    + "cancelled, another business's, malformed or unknown. One body for every cause."),
            @ApiResponse(responseCode = "429", description = "Per-IP public rate limit exceeded."),
            @ApiResponse(responseCode = "503", description = "The account service is not available.")
    })
    public ResponseEntity<StaffInvitePreviewDto> previewInvite(
            @RequestBody(required = false) StaffInviteRefRequest body) {
        StaffInvitePreviewDto preview = acceptService.preview(body == null ? null : body.ref());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preview);
    }

    @PostMapping(value = "/accept", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Accept a staff invitation",
            description = "Single use. For a NEW address, creates the account with the given names and "
                    + "password, then grants exactly the invited role and shop; for an address that already "
                    + "has an account in this business, grants it with no password. The grant is always the "
                    + "invitation's, never the request's. After a 201 the client starts the ordinary sign-in "
                    + "with the invited email as the login hint (D-26).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Accepted: the grant the server wrote. "
                    + "Cache-Control: no-store."),
            @ApiResponse(responseCode = "400", description = "A NEW account's names or password are missing "
                    + "or invalid. Nothing written; the invitation stays open."),
            @ApiResponse(responseCode = "404", description = "The link can't be used (one body for every "
                    + "cause)."),
            @ApiResponse(responseCode = "409", description = "The email already belongs to another business. "
                    + "Nothing written."),
            @ApiResponse(responseCode = "422", description = "The password was refused by the account "
                    + "service's policy; its message is in detail. The invitation stays open."),
            @ApiResponse(responseCode = "429", description = "Per-IP public rate limit exceeded."),
            @ApiResponse(responseCode = "503", description = "The account service is not available. The "
                    + "invitation stays open.")
    })
    public ResponseEntity<StaffInviteAcceptedDto> acceptInvite(
            @RequestBody(required = false) AcceptStaffInviteRequest body) {
        StaffInviteAcceptedDto accepted = acceptService.accept(body);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(accepted);
    }
}
