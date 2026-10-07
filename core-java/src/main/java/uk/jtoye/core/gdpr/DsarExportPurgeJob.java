package uk.jtoye.core.gdpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Destroys every Article 15 export nobody downloaded once its link has lapsed (31.1-17, D-01,
 * retention row R-15, threat T-31.1-64).
 *
 * <h2>Why this exists</h2>
 *
 * {@link DsarExportDownloadService} destroys an export the moment it is downloaded, and its expiry
 * predicate refuses a lapsed token. Neither touches an export whose link simply ran out: without
 * this sweep the encrypted copy of a person's data across every shop would sit in
 * {@code dsar_access_export} for ever, refused but kept. The published retention schedule promises
 * the copy is gone when the link expires ({@code jtoye.gdpr.dsar.export-link-ttl-hours}); this is
 * the code that keeps that promise.
 *
 * <h2>One statement</h2>
 *
 * The payload is NULLed and {@code purged_at} stamped together. The predicate leaves consumed rows
 * alone (their payload is already gone and {@code consumed_at} is the record of the download) and
 * never touches an export whose link is still live. {@code dsar_access_export} carries no row-level
 * security (V75), so no tenant is pinned and none exists to pin.
 *
 * <p>The interval is a {@code fixedDelayString} with an inline default, in the
 * {@link DsarRequestExpirySweep} shape, so absence of the key never switches a retention job off.
 * It logs a count and nothing else.
 */
@Component
public class DsarExportPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(DsarExportPurgeJob.class);

    static final String PURGE_SQL = """
            UPDATE dsar_access_export
               SET payload_ciphertext = NULL,
                   purged_at = ?
             WHERE consumed_at IS NULL
               AND purged_at IS NULL
               AND expires_at <= ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public DsarExportPurgeJob(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Purge every unconsumed export whose link has lapsed.
     *
     * @return how many exports were purged
     */
    @Scheduled(fixedDelayString = "${jtoye.gdpr.dsar.export-purge-interval-ms:3600000}")
    public int purgeExpiredExports() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int purged = jdbcTemplate.update(PURGE_SQL, now, now);
        if (purged > 0) {
            log.info("event=dsar_exports_purged count={}", purged);
        }
        return purged;
    }
}
