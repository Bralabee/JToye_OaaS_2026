package uk.jtoye.core.media;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.StorageService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bounded-retention reclamation of quarantined raw uploads (27-01 / D-03, D-08).
 *
 * <h2>Why this exists</h2>
 * {@link MediaPendingReaper} used to delete quarantine objects 15 minutes after upload, which
 * destroyed vendor data during any broker outage. It no longer deletes anything at all — so
 * <b>this is the only remaining component that reclaims quarantine bytes on a timeout-class
 * path</b>. Without it the quarantine prefix would grow without bound, and that boundedness was a
 * real, working good of Phase 24. The good is preserved and moved onto a declared 72-hour policy
 * horizon instead of being a 15-minute accident.
 *
 * <h2>Deliberately NOT gated</h2>
 * Unlike the reaper, this sweep is <b>unconditional</b>: it does not consult dispatch evidence and
 * is not gated on the suspension circuit. It is the backstop that keeps bucket growth bounded even
 * when every other gate is suspended — which is exactly why "forever suspended" is a survivable
 * state for the reaper. It is also what eventually collects the assets the reaper deliberately
 * refuses to classify (D-01 fails closed on an absent outbox row).
 *
 * <h2>Three independent guards, by design</h2>
 * <ol>
 *   <li>{@code status <> ACTIVE} — a live derivative is never a candidate. <b>Lives in the
 *       {@code @Query}</b>, so it is only falsifiable where the query actually runs
 *       (Testcontainers; against a mocked repository the stub does the filtering and editing the
 *       JPQL changes nothing).</li>
 *   <li>{@code objectKey} contains {@code /quarantine/} — <b>lives in this class's Java</b>, and is
 *       correctly unit-falsifiable. A derivative key is {@code <tenant>/media/<id>.webp} and a
 *       V53-backfilled key is {@code <tenant>/products/<pid>/<uuid>.<ext>}, so neither can match.</li>
 *   <li>{@code quarantine_reclaimed_at IS NULL} — the sentinel. In the {@code @Query}.</li>
 * </ol>
 * Each is independently breakable, and each has a fixture that the OTHER guards do not already
 * block — otherwise a "proof" that a guard works proves only that some other guard works.
 *
 * <h2>Why the sentinel is a column and not "null out quarantine_expires_at"</h2>
 * The legacy arm selects rows whose {@code quarantineExpiresAt} is <b>already null</b> (they
 * predate V60) — that is the arm's own precondition. Nulling it would therefore terminate nothing:
 * the same rows would be re-selected on every hourly tick forever, {@code deleteByKey} would be
 * re-called on already-deleted objects forever, and because {@code deleteByKey} swallows every
 * exception nothing would ever have complained. {@code quarantine_reclaimed_at} is a column no
 * selection predicate can already satisfy, so stamping it genuinely terminates the row.
 *
 * <h2>Delete between two transactions</h2>
 * The irreversible S3 delete sits <em>between</em> the selection transaction and the stamping
 * transaction, never inside one. That is the §3(b) lesson applied to the class that legitimately
 * does delete: the old reaper deleted inside its {@code TransactionTemplate} callback, so a
 * {@code @Version} conflict rolled back the DB writes while the objects stayed deleted. And the
 * sentinel is stamped ONLY for objects {@link StorageService#deleteByKeyChecked} confirmed gone, so
 * a transient S3 error leaves the row for the next tick — correct retry, not the re-selection loop
 * above, because a <em>successful</em> delete is what terminates it.
 *
 * <p>Structural clone of {@code WebhookRetentionCleanup}: per-tenant, own transaction each,
 * {@link TenantContext} + GUC pinned so every read/write is RLS-scoped (a bare query returns ZERO
 * rows under FORCE RLS, which looks exactly like "nothing to do"), and a
 * {@link TransactionTemplate} rather than a {@code @Transactional} private method.
 */
@Component
public class MediaQuarantineRetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(MediaQuarantineRetentionSweep.class);

    /** Guard 2. A derivative or a V53-backfilled key can never contain this segment. */
    static final String QUARANTINE_SEGMENT = "/quarantine/";

    private final MediaAssetRepository mediaAssetRepository;
    private final MediaProperties properties;
    private final StorageService storageService;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public MediaQuarantineRetentionSweep(MediaAssetRepository mediaAssetRepository,
                                         MediaProperties properties,
                                         StorageService storageService,
                                         EntityManager entityManager,
                                         PlatformTransactionManager transactionManager,
                                         ObjectProvider<MeterRegistry> meterRegistry) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.properties = properties;
        this.storageService = storageService;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${jtoye.media.retention-interval-ms:3600000}")
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime legacyCutoff = now.minusNanos(properties.getQuarantineRetentionMs() * 1_000_000L);
        List<UUID> tenantIds = listTenantIds();
        long total = 0;
        for (UUID tenantId : tenantIds) {
            try {
                total += sweepTenant(tenantId, now, legacyCutoff);
            } catch (Exception e) {
                log.error("event=media_quarantine_sweep_failed tenant={} — continuing: {}",
                        tenantId, e.getMessage());
            }
        }
        if (total > 0) {
            log.info("event=media_quarantine_swept reclaimed={} olderThan={} tenants={}",
                    total, legacyCutoff, tenantIds.size());
        }
    }

    private long sweepTenant(UUID tenantId, OffsetDateTime now, OffsetDateTime legacyCutoff) {
        TenantContext.set(tenantId);
        try {
            // (1) SELECT — no delete in here. Guard 2 is applied in Java, on purpose.
            List<Candidate> candidates = transactionTemplate.execute(status -> {
                pinTenantGuc(tenantId);
                List<Candidate> out = new ArrayList<>();
                for (MediaAsset asset : mediaAssetRepository.findReclaimableQuarantine(now, legacyCutoff)) {
                    String key = asset.getObjectKey();
                    if (key == null || !key.contains(QUARANTINE_SEGMENT)) continue;   // guard 2
                    out.add(new Candidate(asset.getId(), key));
                }
                return out;
            });
            if (candidates == null || candidates.isEmpty()) return 0;

            // (2) DELETE — between the two transactions, so a rolled-back batch can never leave
            //     objects deleted with their rows still claiming the bytes exist.
            List<UUID> reclaimed = new ArrayList<>();
            int failed = 0;
            for (Candidate c : candidates) {
                if (storageService.deleteByKeyChecked(c.objectKey())) {
                    reclaimed.add(c.assetId());
                } else {
                    failed++;
                    increment("media.quarantine.reclaim_failed");
                }
            }

            // (3) STAMP — only the ids whose delete was CONFIRMED. A failed delete leaves the row
            //     for the next tick instead of stranding an object that still exists.
            if (!reclaimed.isEmpty()) {
                transactionTemplate.executeWithoutResult(status -> {
                    pinTenantGuc(tenantId);
                    OffsetDateTime stampedAt = OffsetDateTime.now();
                    for (MediaAsset asset : mediaAssetRepository.findAllById(reclaimed)) {
                        asset.setQuarantineReclaimedAt(stampedAt);
                        increment("media.quarantine.reclaimed");
                    }
                });
            }

            if (!reclaimed.isEmpty() || failed > 0) {
                log.info("event=media_quarantine_reclaimed tenant={} reclaimed={} failed={} olderThan={}",
                        tenantId, reclaimed.size(), failed, legacyCutoff);
            }
            return reclaimed.size();
        } finally {
            TenantContext.clear();
        }
    }

    /** One reclaimable quarantine object: the asset to stamp and the key to delete. */
    private record Candidate(UUID assetId, String objectKey) {
    }

    private void increment(String counter) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) registry.counter(counter).increment();
    }

    @SuppressWarnings("unchecked")
    private List<UUID> listTenantIds() {
        return transactionTemplate.execute(status ->
                entityManager.createNativeQuery("SELECT id FROM tenants").getResultList());
    }

    private void pinTenantGuc(UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement(
                    "SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.toString());
                stmt.execute();
            }
        });
    }
}
