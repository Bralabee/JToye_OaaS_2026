package uk.jtoye.core.gdpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lapses DSAR requests whose subject never proved control of the address, and destroys the
 * encrypted address with them (D-19, 31.1-07).
 *
 * <h2>Why this exists</h2>
 *
 * V62 declared {@code EXPIRED} as a status and {@code DsarIntakeService} stamps
 * {@code verification_expires_at} (now + {@code jtoye.gdpr.dsar.verification-ttl-hours}), but until
 * this class nothing ever moved a row to {@code EXPIRED}: the expiry was checked only when somebody
 * presented a token. That was harmless while the row held a digest. Since V70 the row holds the
 * subject's address, encrypted, and an address lodged and never confirmed would otherwise sit in the
 * table for ever — including an address an attacker typed in about somebody else, who never asked
 * for anything. D-19 names EXPIRED as a terminal state at which the address is dropped; this is the
 * path that makes that state reachable.
 *
 * <h2>One statement, and why that is enough</h2>
 *
 * The status change, the {@code completed_at} stamp and the NULLing of
 * {@code subject_email_ciphertext} happen in ONE {@code UPDATE}, so there is no window in which a
 * row is EXPIRED and still holds the address — and V70's CHECK
 * {@code ck_dsar_request_ciphertext_terminal} would refuse that row if a future edit split them.
 * {@code dsar_request} carries no row-level security (V62 argues why), so no tenant is pinned and
 * none exists to pin. The predicate is selective: a request still inside its window, or one that
 * has already been verified, is not touched.
 *
 * <p>The interval is a {@code fixedDelayString} with an inline default, in the
 * {@code DsarFanoutWorker} shape, so the sweep still runs if the key is ever absent: a retention
 * job must not be switched off by omission. It logs a count and nothing else — no request id, no
 * digest, nothing about the subject.
 */
@Component
public class DsarRequestExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(DsarRequestExpirySweep.class);

    private static final String EXPIRE_SQL = """
            UPDATE dsar_request
               SET status = 'EXPIRED',
                   completed_at = NOW(),
                   subject_email_ciphertext = NULL
             WHERE status = 'PENDING_VERIFICATION'
               AND verification_expires_at < NOW()
            """;

    private final JdbcTemplate jdbcTemplate;

    public DsarRequestExpirySweep(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Move every lapsed, unverified request to EXPIRED and drop its encrypted address.
     *
     * @return how many requests were expired
     */
    @Scheduled(fixedDelayString = "${jtoye.gdpr.dsar.expiry-sweep-interval-ms:900000}")
    public int expireLapsedVerifications() {
        int expired = jdbcTemplate.update(EXPIRE_SQL);
        if (expired > 0) {
            log.info("event=dsar_requests_expired count={}", expired);
        }
        return expired;
    }
}
