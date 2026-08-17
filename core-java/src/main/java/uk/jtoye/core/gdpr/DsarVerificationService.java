package uk.jtoye.core.gdpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Turns proof of control of an address into an actionable request — the gate 31-05 left open on
 * purpose, and the ONLY way a {@code dsar_request} row becomes something the fan-out worker will
 * touch.
 *
 * <h2>The property this class holds</h2>
 *
 * An unverified erasure request is a weapon: a destructive, irreversible action aimed at an
 * arbitrary third party's data across every vendor on the platform, requiring nothing but a guessed
 * email address (threat T-31-05-02). 31-05 therefore shipped the intake INERT — everything lands
 * {@code PENDING_VERIFICATION} — and explicitly rejected defaulting to {@code VERIFIED}. The single
 * statement in {@link #verify(String)} is what closes that gap without reopening the threat: it
 * advances a row only when the caller presents a token that was sent to the address itself.
 *
 * <h2>Why the transition is one conditional UPDATE</h2>
 *
 * Read-then-write would let two concurrent submissions of one token both observe
 * {@code PENDING_VERIFICATION} and both advance it. The predicate lives in the WHERE clause instead,
 * so the database decides: status must still be {@code PENDING_VERIFICATION} and the expiry must
 * still be in the future, evaluated atomically against the row. A second submission updates zero
 * rows, which is how {@link Outcome#ALREADY_VERIFIED} is distinguished — from the row's state, never
 * from a race the application tried to referee.
 *
 * <p><b>No constant-time compare, and that is not an oversight.</b> {@code UnsubscribeTokenService}
 * needs one because it compares an attacker-supplied HMAC against a value it computes in Java, where
 * an early-exit {@code equals} leaks the matching prefix. Here the comparison is an equality lookup
 * on the SHA-256 of a 256-bit token drawn from {@link java.security.SecureRandom} — there is no
 * prefix to walk toward, because a one-bit change in the token changes the whole digest. The timing
 * channel would have to leak the digest, and knowing the digest does not yield the token.
 *
 * <h2>{@code dsar_request} has no row-level security, so nothing is pinned here</h2>
 *
 * That is V62's deliberate decision, not an omission — an anonymous subject lodges before any tenant
 * is known. This class touches ONLY that table. It reaches no tenant data at all, and it must never
 * be extended to: it runs on a REQUEST thread, and {@code ShopAccessService} records the rule that a
 * request thread never declares system authority. All cross-tenant reach belongs to
 * {@link DsarFanoutWorker}, which is a background entry point.
 */
@Service
public class DsarVerificationService {

    private static final Logger log = LoggerFactory.getLogger(DsarVerificationService.class);

    /** What happened, in terms that carry no information about any address. */
    public enum Outcome {
        /** The token matched an unexpired pending request, which is now actionable. */
        VERIFIED,
        /** The token already did its job. Replaying it must not re-arm anything. */
        ALREADY_VERIFIED,
        /** Unknown, malformed or expired. All three answer identically, on purpose. */
        INVALID
    }

    private final JdbcTemplate jdbcTemplate;

    public DsarVerificationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Advance the request this token identifies, if it is still eligible.
     *
     * @param token the readable token delivered to the subject's address
     */
    public Outcome verify(String token) {
        if (token == null || token.isBlank()) {
            return Outcome.INVALID;
        }

        String tokenDigest = DsarSubjectDigest.sha256Hex(token);

        int advanced = jdbcTemplate.update("""
                UPDATE dsar_request
                   SET status = 'VERIFIED', verified_at = NOW()
                 WHERE verification_token_sha256 = ?
                   AND status = 'PENDING_VERIFICATION'
                   AND verification_expires_at > NOW()
                """, tokenDigest);

        if (advanced > 0) {
            log.info("event=dsar_verified rows={}", advanced);
            return Outcome.VERIFIED;
        }

        // Zero rows advanced has two causes worth telling apart FOR THE HOLDER OF THE TOKEN: the
        // request has already moved on, or the token is unknown/expired. Somebody who did not send
        // the token cannot reach either answer, so this distinction discloses nothing — and it
        // matters to a subject who clicked the link twice and would otherwise be told their own
        // confirmation failed.
        Long alreadyMoved = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dsar_request "
                        + "WHERE verification_token_sha256 = ? AND status <> 'PENDING_VERIFICATION'",
                Long.class, tokenDigest);

        if (alreadyMoved != null && alreadyMoved > 0) {
            log.info("event=dsar_verification_replayed");
            return Outcome.ALREADY_VERIFIED;
        }

        // Unknown and expired deliberately collapse into one answer: separating them would tell an
        // attacker holding a stale or guessed value which of the two it was.
        log.info("event=dsar_verification_refused");
        return Outcome.INVALID;
    }
}
