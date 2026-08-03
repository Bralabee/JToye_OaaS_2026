package uk.jtoye.core.notification.consent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.security.TenantContext;

import java.util.Optional;
import java.util.UUID;

/**
 * Public, no-auth one-click unsubscribe endpoint (COMMS-03, GDPR/PECR).
 *
 * <p>Mounted under {@code {"/public","/api/v1/public"}} — the ONLY established
 * no-auth surface — so it inherits {@code permitAll} from {@code SecurityConfig}
 * ({@code /public/**} + {@code /api/v1/public/**}); no {@code SecurityConfig}
 * change is needed. The HMAC unsubscribe token is the sole authorization: there
 * is no session and no Bearer token.
 *
 * <p>Security posture:
 * <ul>
 *   <li><b>Forgery (T-22-02-01):</b> the token is verified with a constant-time
 *       compare ({@link UnsubscribeTokenService#verify}) before any write; a bad
 *       token writes nothing and returns {@code invalid}.</li>
 *   <li><b>Enumeration (T-22-02-02):</b> the response never reveals whether the
 *       address exists — a verified token always writes its bound recipient's
 *       opt-out (idempotently); there is no address lookup and no
 *       "address exists" signal.</li>
 *   <li><b>PII in logs (ASVS V7):</b> the {@code email} and {@code token} values
 *       are never logged by this application or echoed into the response body.
 *       They must also stay OUT OF THE REQUEST LINE wherever we control it —
 *       see the two POST variants below (issue #278).</li>
 * </ul>
 *
 * <h2>Why there are two POST variants (issue #278)</h2>
 *
 * <p>A query string is captured verbatim by every intermediary on the path:
 * nginx-ingress writes the whole request line as {@code $request} in its default
 * {@code log-format-upstream}, APM agents tag full-URL spans, and proxies keep
 * URLs long after bodies are discarded. So the browser-driven POST — the high
 * volume path, and the one whose shape we own end to end — now carries its
 * fields in a JSON <b>body</b>.
 *
 * <p>The query-param POST is NOT merely a legacy shim to be removed later; it is
 * a <b>permanent protocol requirement</b>. {@code EmailChannel} stamps the RFC
 * 8058 headers {@code List-Unsubscribe: <url>} +
 * {@code List-Unsubscribe-Post: List-Unsubscribe=One-Click}, and RFC 8058 §3.1
 * fixes the POST body a mail provider sends to the literal string
 * {@code List-Unsubscribe=One-Click}. There is therefore no body slot in which a
 * one-click POST could carry the token — the identifier can only live in the
 * URI. Deleting the {@code @RequestParam} variant would break one-click
 * unsubscribe for every message already in a customer's inbox <i>and</i> every
 * message sent afterwards, costing both PECR compliance and Gmail/Yahoo
 * bulk-sender deliverability. Both variants are kept, deliberately.
 *
 * <p>Dispatch is by {@code Content-Type}, which is unambiguous: only
 * {@code application/json} reaches {@link #unsubscribe}; a one-click POST
 * ({@code application/x-www-form-urlencoded}) and a bare POST with no
 * {@code Content-Type} both fall to {@link #unsubscribeOneClick}. The JSON
 * variant additionally accepts the old query params as a fallback so an
 * already-loaded browser tab running the previous bundle keeps working.
 *
 * <p>The write is tenant-scoped: the tenant is taken from the VERIFIED token and
 * pinned via {@link TenantContext} (try/finally) before the {@code @Transactional}
 * {@link SuppressionService#suppress}, so {@code TenantSetLocalAspect} sets the
 * RLS GUC and the row lands under the correct tenant.
 */
@RestController
@RequestMapping({"/public", "/api/v1/public"})
@Tag(name = "Public Unsubscribe", description = "No-auth, token-verified one-click unsubscribe (GDPR/PECR). Canonical prefix /api/v1/public.")
public class PublicUnsubscribeController {

    private static final Logger log = LoggerFactory.getLogger(PublicUnsubscribeController.class);

    // OpenAPI 3 cannot express two operations at one path+method, so springdoc
    // MERGES the two POST handlers into a single documented operation — taking
    // one method's @Operation text and the other's parameters (observed:
    // summary from the JSON handler, operationId `unsubscribeOneClick_1`).
    // Which one wins is a declaration-order detail nobody should have to know,
    // so both handlers carry the SAME text and the merged document is correct
    // whichever way springdoc resolves it. The four query params are
    // `required = false` on BOTH for the same reason: the merged operation
    // documents a JSON body AND four query params, and marking the params
    // required would tell an API consumer it must send both. It must send
    // exactly one of the two.
    private static final String POST_SUMMARY = "Unsubscribe (one-click POST)";

    private static final String POST_DESCRIPTION =
            "Verifies the HMAC token and, on match, records a tenant-scoped per-category suppression idempotently. "
                    + "Send the four fields EITHER as a JSON body (preferred — keeps the token and the recipient "
                    + "email out of the request line, and so out of every access log) OR as query parameters, which "
                    + "is what RFC 8058 one-click clients must do because RFC 8058 fixes their request body to "
                    + "'List-Unsubscribe=One-Click' and leaves no slot for the token. Supply one shape or the other; "
                    + "the JSON body wins if both are present. A request missing any field returns status 'invalid' "
                    + "without writing, exactly as a bad token does.";

    private final UnsubscribeTokenService tokenService;
    private final SuppressionService suppressionService;

    public PublicUnsubscribeController(UnsubscribeTokenService tokenService,
                                       SuppressionService suppressionService) {
        this.tokenService = tokenService;
        this.suppressionService = suppressionService;
    }

    /**
     * Canonical POST: the fields travel in a JSON body, so the token and the
     * recipient email never enter the request line and never reach an access
     * log (#278). This is what the confirmation page calls.
     *
     * <p>The four {@code @RequestParam}s are a compatibility fallback, used only
     * when the body is absent or null — an already-open browser tab running the
     * previous bundle posts {@code Content-Type: application/json} with a literal
     * {@code null} body and the fields in the query string. Without this it would
     * start failing mid-session. New traffic never takes that path.
     *
     * <p><b>{@code Optional<UnsubscribeRequest>}, deliberately — NOT
     * {@code @RequestBody(required = false)}.</b> They are not interchangeable
     * here. {@code RequestMappingHandlerMapping.updateConsumesCondition} copies
     * {@code required} onto the {@code consumes} condition as
     * {@code bodyRequired}, and {@code ConsumesRequestCondition.getMatchingCondition}
     * short-circuits to {@code EMPTY_CONDITION} for a request with no body when
     * {@code bodyRequired} is false. An empty consumes condition matches
     * <i>everything</i> and compares equal to {@link #unsubscribeOneClick}'s, so
     * a bodyless POST matched both handlers with identical specificity and
     * Spring threw {@code IllegalStateException: Ambiguous handler methods}.
     * (Observed, not theorised: it failed
     * {@code queryParamPost_withNoContentType_stillWorks} on the first run.)
     * {@code Optional} leaves {@code required} true — so the consumes condition
     * keeps its teeth and dispatch stays unambiguous — while
     * {@code RequestResponseBodyMethodProcessor.checkRequired} still tolerates a
     * null-valued body via {@code MethodParameter.isOptional()}.
     */
    @PostMapping(value = "/unsubscribe", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = POST_SUMMARY, description = POST_DESCRIPTION)
    public ResponseEntity<UnsubscribeResponse> unsubscribe(
            @RequestBody Optional<UnsubscribeRequest> body,
            @RequestParam(name = "tenant", required = false) UUID tenant,
            @RequestParam(name = "email", required = false) String email,
            @RequestParam(name = "category", required = false) NotificationCategory category,
            @RequestParam(name = "token", required = false) String token) {
        return body
                .map(b -> process(b.tenant(), b.email(), b.category(), b.token()))
                .orElseGet(() -> process(tenant, email, category, token));
    }

    /**
     * RFC 8058 one-click POST — the {@code List-Unsubscribe} target stamped into
     * every email by {@code EmailChannel}. The mail provider POSTs the fixed body
     * {@code List-Unsubscribe=One-Click} as {@code application/x-www-form-urlencoded},
     * so the identifying fields can only be in the URI. Kept permanently: see the
     * class Javadoc.
     *
     * <p>The params are {@code required = false} so the MERGED OpenAPI operation
     * can describe them honestly as optional — a JSON caller sends none of them.
     * The cost is that a malformed one-click POST now answers {@code invalid}
     * instead of a 400 naming the first missing parameter, which is the better
     * answer on a no-auth endpoint anyway: {@link #process} rejects an
     * incomplete request exactly as it rejects a bad token, revealing nothing.
     */
    @PostMapping("/unsubscribe")
    @Operation(summary = POST_SUMMARY, description = POST_DESCRIPTION)
    public ResponseEntity<UnsubscribeResponse> unsubscribeOneClick(
            @RequestParam(name = "tenant", required = false) UUID tenant,
            @RequestParam(name = "email", required = false) String email,
            @RequestParam(name = "category", required = false) NotificationCategory category,
            @RequestParam(name = "token", required = false) String token) {
        return process(tenant, email, category, token);
    }

    /** GET companion so the frontend confirmation page can call the same contract. */
    @GetMapping("/unsubscribe")
    @Operation(summary = "Unsubscribe (link GET)",
            description = "Same contract as the POST, for the browser confirmation page.")
    public ResponseEntity<UnsubscribeResponse> unsubscribeViaLink(
            @RequestParam("tenant") UUID tenant,
            @RequestParam("email") String email,
            @RequestParam("category") NotificationCategory category,
            @RequestParam("token") String token) {
        return process(tenant, email, category, token);
    }

    private ResponseEntity<UnsubscribeResponse> process(UUID tenant, String email,
                                                        NotificationCategory category, String token) {
        // A request missing any field can never verify. Answer exactly as a bad
        // token does — same status, no DB touch, nothing echoed back — so the
        // response distinguishes nothing (T-22-02-02) and no field name leaks.
        if (tenant == null || email == null || email.isBlank() || category == null || token == null) {
            log.warn("Unsubscribe rejected: incomplete request");
            return ResponseEntity.ok(new UnsubscribeResponse("invalid"));
        }

        if (!tokenService.verify(tenant, email, category, token)) {
            // Never log the email or the signed value (ASVS V7); category is not PII.
            log.warn("Unsubscribe rejected: signature verification failed for category {}", category);
            return ResponseEntity.ok(new UnsubscribeResponse("invalid"));
        }

        boolean firstTime;
        TenantContext.set(tenant);
        try {
            firstTime = suppressionService.suppress(tenant, email, category);
        } finally {
            TenantContext.clear();
        }
        return ResponseEntity.ok(new UnsubscribeResponse(firstTime ? "unsubscribed" : "already_unsubscribed"));
    }

    /**
     * JSON request body for the canonical POST (#278). Carrying these four
     * fields here rather than in the query string is the whole point: a body is
     * not part of the request line, so nginx {@code $request}, APM full-URL
     * spans, proxy logs and browser history never see the token or the email.
     */
    public record UnsubscribeRequest(UUID tenant, String email, NotificationCategory category, String token) {
    }

    /** Minimal, PII-free result. Never carries the email or token back. */
    public record UnsubscribeResponse(String status) {
    }
}
