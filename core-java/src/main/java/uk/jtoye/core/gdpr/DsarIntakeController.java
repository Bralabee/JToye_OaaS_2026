package uk.jtoye.core.gdpr;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.common.idempotency.Idempotent;
import uk.jtoye.core.gdpr.dto.DsarIntakeRequest;

/**
 * The consumer-facing UK-GDPR data-subject-request intake (Phase 31, D-16/D-17).
 *
 * <h2>What this is, and what it deliberately is not</h2>
 *
 * The shipped {@link GdprController} implements Articles 17 and 20 already, but every one of its
 * endpoints is vendor-admin, single-tenant, keyed by {@code customerId}, and behind an admin role
 * gate. A consumer has none of those things: no account with the platform, no idea which vendors
 * hold their data, and no credential. This controller is the missing half — an anonymous,
 * email-keyed, cross-tenant intake — and it is a SEPARATE class on purpose. The admin controller's
 * authorisation is a distinct, unweakened thing; nothing here inherits from it, relaxes it, or
 * widens who can trigger the Article 20 export (which carries the Article 9 allergen field).
 *
 * <h2>The 202 is unconditional and opaque, and that is the security property</h2>
 *
 * No lookup happens before the response. Not "a lookup whose result is ignored" — none at all,
 * because a lookup is something a future edit can start branching on. Match and no-match are the
 * same code path returning the same constant body, and the integration test compares those two
 * responses <em>byte for byte</em> rather than merely checking both are 202: a status-only
 * assertion passes while the body leaks the answer. "Which of your vendors holds this person's
 * email address" is exactly what the tenant wall exists to withhold, and an intake that answers it
 * is a cross-tenant enumeration oracle open to the internet.
 *
 * <h2>The request thread never declares system authority</h2>
 *
 * D-17's boundary — intake is a request, execution is background — is what reconciles a single
 * cross-tenant DSAR desk with a project that has twice refused a cross-tenant operator identity.
 * Nothing on this path declares itself internal, and nothing here reaches a gated service; the
 * cross-tenant reach belongs solely to plan 31-09's scheduled worker, which takes it one pinned
 * tenant at a time. {@code SystemPrincipalGuardTest} asserts that property both from inside a live
 * request and by scanning this file, so an edit that quietly declares system authority anywhere on
 * the intake path reds the build.
 *
 * <h2>Route</h2>
 *
 * {@code uk.jtoye.core.gdpr} is one of the {@code WebConfig} packages that receive the invisible
 * version prefix, so the mapping below is served at {@code /api/v1/public/gdpr/dsar} — matched by
 * the anonymous {@code /api/v1/public} allowance in the security chain, and by the tenant-less
 * public tier of the platform rate limiter. It is the URL the privacy notice and the accessibility
 * conformance statement point at, so it is a published contract, not an internal detail.
 */
@RestController
@RequestMapping("/public/gdpr")
@Tag(name = "Data Subject Requests",
        description = "Anonymous, cross-tenant UK GDPR data-subject request intake. No credential "
                + "required; responses are deliberately opaque.")
public class DsarIntakeController {

    private final DsarIntakeService dsarIntakeService;
    private final DsarIntakeRateLimiter rateLimiter;

    public DsarIntakeController(DsarIntakeService dsarIntakeService,
                                DsarIntakeRateLimiter rateLimiter) {
        this.dsarIntakeService = dsarIntakeService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/dsar")
    @Idempotent(endpoint = DsarIntakeService.ENDPOINT)
    @Operation(summary = "Lodge a data-subject request",
            description = "Records a UK GDPR Article 15/17 request against an email address. "
                    + "Always returns 202 with an identical, opaque body — the response never "
                    + "indicates whether any tenant holds the address. The address itself is "
                    + "stored only as a one-way SHA-256 digest. Supply an Idempotency-Key header "
                    + "to make a retried POST safe: a repeated key replays the original response "
                    + "and never queues a second request.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Request recorded (always, when valid)"),
            @ApiResponse(responseCode = "400", description = "Malformed or missing request body"),
            @ApiResponse(responseCode = "409", description = "A request with this Idempotency-Key is in flight"),
            @ApiResponse(responseCode = "422", description = "Idempotency-Key reused with a different payload"),
            @ApiResponse(responseCode = "429", description = "Too many requests from this client")
    })
    public ResponseEntity<DsarIntakeService.DsarIntakeAck> lodge(
            @Valid @RequestBody DsarIntakeRequest request,
            // Hidden from springdoc: IdempotencyHeaderCustomizer advertises the rich
            // Idempotency-Key parameter off @Idempotent, so documenting the raw header
            // here too would double-list it (the CustomerController convention).
            @Parameter(hidden = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {

        // Before the write, not after: the limiter exists to stop rows being queued at all.
        rateLimiter.checkAllowed(httpRequest);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(dsarIntakeService.lodge(request, idempotencyKey));
    }
}
