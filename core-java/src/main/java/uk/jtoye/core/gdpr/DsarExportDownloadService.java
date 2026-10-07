package uk.jtoye.core.gdpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.DsarExportUnavailableException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Hands an Article 15 export to the holder of its token, exactly once (31.1-17, D-01, #778).
 *
 * <h2>Why the consume is one statement</h2>
 *
 * Read-then-write would let two presses of one link both observe an unconsumed row and both
 * receive the document. Instead one {@code UPDATE} decides: it locks the row only while it is still
 * unconsumed, unpurged and unexpired, stamps {@code consumed_at} and NULLs the payload — V75's CHECK
 * {@code ck_dsar_access_export_consumed_payload} requires those two together — and hands back the
 * ciphertext it just removed. A concurrent second press blocks on the row lock, then re-checks the
 * row as the first press left it, finds it consumed, and matches nothing (threat T-31.1-63).
 *
 * <p>PostgreSQL 15's {@code RETURNING} yields the row as it is AFTER the update, where the payload is
 * already NULL. The ciphertext therefore comes back from a {@code FOR UPDATE} subquery joined in
 * {@code FROM}: the value read under the lock, before the same statement destroyed it.
 *
 * <p>The decrypt runs inside the same transaction: if the payload cannot be decrypted (a rotated
 * key), the consume rolls back and the subject's link is not silently spent on an error page.
 *
 * <h2>This is a REQUEST thread and it reaches no tenant data</h2>
 *
 * The document was assembled earlier by {@link DsarFanoutWorker}, the one background entry point
 * that holds cross-tenant reach, one pinned tenant at a time. This class touches only
 * {@code dsar_access_export}, which carries no row-level security by design (V75), declares no
 * system authority and looks nothing up in any tenant. {@code SystemPrincipalGuardTest} scans this
 * file to keep that true.
 *
 * <p>No constant-time compare, for the reason {@link DsarVerificationService} records: the lookup is
 * an equality match on the SHA-256 of a 256-bit random token, so there is no prefix to walk toward.
 *
 * <p>Nothing about the subject is logged: not the token, not its digest, not the request id, not a
 * byte of the document. The log line carries only the event.
 */
@Service
public class DsarExportDownloadService {

    private static final Logger log = LoggerFactory.getLogger(DsarExportDownloadService.class);

    static final String CONSUME_SQL = """
            UPDATE dsar_access_export e
               SET consumed_at = ?, payload_ciphertext = NULL
              FROM (SELECT id, dsar_request_id, payload_ciphertext
                      FROM dsar_access_export
                     WHERE token_sha256 = ?
                       AND consumed_at IS NULL
                       AND purged_at IS NULL
                       AND payload_ciphertext IS NOT NULL
                       AND expires_at > ?
                       FOR UPDATE) prior
             WHERE e.id = prior.id
            RETURNING prior.dsar_request_id, prior.payload_ciphertext
            """;

    private final JdbcTemplate jdbcTemplate;
    private final DsarCipher dsarCipher;
    private final Clock clock;

    public DsarExportDownloadService(JdbcTemplate jdbcTemplate, DsarCipher dsarCipher, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.dsarCipher = dsarCipher;
        this.clock = clock;
    }

    /**
     * Spend {@code token} and return the export document it unlocks.
     *
     * @param token the readable token from the emailed link's fragment
     * @return the export JSON exactly as it was stored
     * @throws DsarExportUnavailableException for a blank, unknown, expired, purged or used token —
     *         one exception, so every refusal answers identically
     */
    @Transactional
    public String consume(String token) {
        if (token == null || token.isBlank()) {
            throw refused();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Consumed> rows = jdbcTemplate.query(CONSUME_SQL,
                (rs, i) -> new Consumed(rs.getObject(1, UUID.class), rs.getBytes(2)),
                now, DsarSubjectDigest.sha256Hex(token), now);
        if (rows.isEmpty()) {
            throw refused();
        }
        Consumed consumed = rows.get(0);
        String document = dsarCipher.decrypt(DsarCipher.Purpose.ACCESS_EXPORT,
                consumed.requestId(), consumed.payload());
        log.info("event=dsar_export_downloaded");
        return document;
    }

    private static DsarExportUnavailableException refused() {
        log.info("event=dsar_export_download_refused");
        return new DsarExportUnavailableException();
    }

    private record Consumed(UUID requestId, byte[] payload) {
    }
}
