package uk.jtoye.core.gdpr;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a data subject proves control of the address they named (Phase 31, plan 31-09).
 *
 * <p>Anonymous by necessity, not by relaxation: a consumer exercising a UK GDPR right has no
 * account with this platform and no credential to present. The token IS the authorisation, and it
 * reached them only because they hold the mailbox. Mounted under {@code /public/gdpr}, which
 * {@code WebConfig} serves at {@code /api/v1/public/gdpr/...} — inside the existing anonymous
 * allowance and the tenant-less public rate-limiting tier, so no security-chain change is needed.
 *
 * <h2>Two shapes for one contract, for the same reason the unsubscribe endpoint has two</h2>
 *
 * The JSON POST is canonical: issue #278 established that a token in a query string is captured
 * verbatim by nginx's {@code $request} logging, by APM full-URL spans, by proxies and by browser
 * history, so anything whose shape we own puts it in a body instead. But a link in an email can
 * only be followed with a GET, which has no body — exactly the constraint RFC 8058 imposes on
 * one-click unsubscribe. The GET companion is therefore permanent, not a shim, and the token's
 * short config-injected lifetime plus its single use are what bound the exposure.
 *
 * <h2>The response never varies with anything but the token</h2>
 *
 * {@code invalid} covers unknown, malformed and expired alike. Nothing here says whether an address
 * was ever lodged, whether any tenant holds it, or how many requests exist — the intake's opaque
 * 202 would be worthless if the verification endpoint answered the same question a step later.
 *
 * <h2>This is a REQUEST thread and it declares no system authority</h2>
 *
 * {@code ShopAccessService} records the rule — only background entry points declare — and D-17 makes
 * it the whole design of the DSAR desk: no human path ever gains cross-tenant reach. This class
 * touches only {@code dsar_request}, which holds no tenant data. {@code SystemPrincipalGuardTest}
 * scans this file to keep that true.
 */
@RestController
@RequestMapping("/public/gdpr")
@Tag(name = "Data Subject Requests",
        description = "Anonymous, cross-tenant UK GDPR data-subject request intake. No credential "
                + "required; responses are deliberately opaque.")
public class DsarVerificationController {

    private static final String SUMMARY = "Confirm a data-subject request";

    private static final String DESCRIPTION =
            "Confirms control of the email address a request was lodged against, using the "
                    + "single-use token emailed to that address. Until this succeeds the request is "
                    + "never actioned — an unverified erasure request would be a destructive action "
                    + "anybody could aim at anybody else. Send the token as a JSON body (preferred — "
                    + "it keeps the token out of the request line and so out of every access log) or "
                    + "as a query parameter, which is what a link in an email must do because a GET "
                    + "has no body. Unknown, malformed and expired tokens all answer 'invalid', "
                    + "revealing nothing.";

    private final DsarVerificationService verificationService;

    public DsarVerificationController(DsarVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /**
     * Canonical JSON POST. The {@code token} query parameter is a fallback for a caller that has
     * only the emailed URL to hand; the body wins when both are present.
     *
     * <p>{@code Optional<VerificationRequest>} rather than {@code @RequestBody(required = false)}:
     * the latter empties the {@code consumes} condition for a bodyless request, which then matches
     * every POST handler at this path with identical specificity and throws
     * {@code Ambiguous handler methods} — the failure {@code PublicUnsubscribeController} records
     * hitting for real.
     */
    @PostMapping(value = "/dsar/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = SUMMARY, description = DESCRIPTION)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Always. The body carries the outcome; a refusal is not an error.")
    })
    public ResponseEntity<VerificationResponse> verify(
            @RequestBody Optional<VerificationRequest> body,
            @Parameter(description = "Fallback for callers that only have the emailed link.")
            @RequestParam(name = "token", required = false) String token) {
        return body
                .map(b -> respond(b.token()))
                .orElseGet(() -> respond(token));
    }

    /**
     * The link in the verification email. Permanent, for the reason in the class javadoc: a click
     * is a GET and a GET has no body slot for the token.
     */
    @GetMapping("/dsar/verify")
    @Operation(summary = SUMMARY + " (emailed link)", description = DESCRIPTION)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Always. The body carries the outcome; a refusal is not an error.")
    })
    public ResponseEntity<VerificationResponse> verifyViaLink(
            @Parameter(description = "The single-use token from the verification email.")
            @RequestParam(name = "token", required = false) String token) {
        return respond(token);
    }

    private ResponseEntity<VerificationResponse> respond(String token) {
        DsarVerificationService.Outcome outcome = verificationService.verify(token);
        return ResponseEntity.ok(new VerificationResponse(
                outcome.name().toLowerCase(Locale.ROOT), detailFor(outcome)));
    }

    private static String detailFor(DsarVerificationService.Outcome outcome) {
        return switch (outcome) {
            case VERIFIED -> "Thank you. Your request is confirmed and will be actioned.";
            case ALREADY_VERIFIED -> "This request was already confirmed. Nothing further is needed.";
            case INVALID -> "This confirmation link is not valid, or it has expired. "
                    + "Lodge the request again to receive a new one.";
        };
    }

    /**
     * JSON body for the canonical POST. Carrying the token here rather than in the query string is
     * the whole point — a body is not part of the request line, so access logs, APM spans and
     * browser history never see it (#278).
     */
    @Schema(description = "The single-use token delivered to the subject's email address.")
    public record VerificationRequest(String token) {
    }

    /**
     * PII-free result. Never echoes the token, and never carries an address, a request identifier
     * or anything derived from whether a tenant holds data.
     */
    @Schema(description = "Outcome of a verification attempt. Carries no address and no request "
            + "reference.")
    public record VerificationResponse(
            @Schema(description = "One of verified, already_verified, invalid.", example = "verified")
            String status,
            @Schema(description = "Human-readable explanation.")
            String detail
    ) {
    }
}
