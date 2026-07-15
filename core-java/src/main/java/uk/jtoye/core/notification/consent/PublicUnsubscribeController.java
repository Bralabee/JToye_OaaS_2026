package uk.jtoye.core.notification.consent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.security.TenantContext;

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
 *   <li><b>PII in logs (ASVS V7):</b> the {@code email} and {@code token} params
 *       are never logged or echoed into the response body.</li>
 * </ul>
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

    private final UnsubscribeTokenService tokenService;
    private final SuppressionService suppressionService;

    public PublicUnsubscribeController(UnsubscribeTokenService tokenService,
                                       SuppressionService suppressionService) {
        this.tokenService = tokenService;
        this.suppressionService = suppressionService;
    }

    /** One-click POST (RFC 8058 List-Unsubscribe-Post target). */
    @PostMapping("/unsubscribe")
    @Operation(summary = "Unsubscribe (one-click POST)",
            description = "Verifies the HMAC token and, on match, records a tenant-scoped per-category suppression idempotently.")
    public ResponseEntity<UnsubscribeResponse> unsubscribe(
            @RequestParam("tenant") UUID tenant,
            @RequestParam("email") String email,
            @RequestParam("category") NotificationCategory category,
            @RequestParam("token") String token) {
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

    /** Minimal, PII-free result. Never carries the email or token back. */
    public record UnsubscribeResponse(String status) {
    }
}
