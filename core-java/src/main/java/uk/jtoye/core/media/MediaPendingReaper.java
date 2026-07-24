package uk.jtoye.core.media;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.StorageService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Crashed-worker orphan reaper (24-04). If a {@link MediaProcessingWorker} dies
 * mid-process, its {@code media_asset} row is left {@code PENDING} forever and its
 * raw quarantine object never cleaned up. This scheduled sweep flips any
 * {@code PENDING} row older than {@code jtoye.media.reaper-grace-ms} to
 * {@code FAILED} (vendor-visible reason: the vendor re-uploads — D-04, no auto-retry
 * this phase) and deletes its quarantine object.
 *
 * <p>Clones the {@code WebhookRetentionCleanup} shape EXACTLY: per-tenant, its own
 * transaction each, {@link TenantContext} + the connection GUC pinned so the
 * find/update is RLS-scoped (a bare query would see ZERO rows under FORCE RLS), and a
 * {@link TransactionTemplate} (not a {@code @Transactional} private method) to dodge
 * the Spring self-invocation NULL-tenant trap.
 */
@Component
public class MediaPendingReaper {

    private static final Logger log = LoggerFactory.getLogger(MediaPendingReaper.class);

    private final MediaAssetRepository mediaAssetRepository;
    private final MediaProperties properties;
    private final StorageService storageService;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public MediaPendingReaper(MediaAssetRepository mediaAssetRepository,
                              MediaProperties properties,
                              StorageService storageService,
                              EntityManager entityManager,
                              PlatformTransactionManager transactionManager) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.properties = properties;
        this.storageService = storageService;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${jtoye.media.reaper-interval-ms:600000}")
    public void reapOrphans() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusNanos(properties.getReaperGraceMs() * 1_000_000L);
        List<UUID> tenantIds = listTenantIds();
        long total = 0;
        for (UUID tenantId : tenantIds) {
            try {
                total += reapTenant(tenantId, cutoff);
            } catch (Exception e) {
                log.error("event=media_reaper_failed tenant={} — continuing: {}", tenantId, e.getMessage());
            }
        }
        if (total > 0) {
            log.info("event=media_reaper_swept reaped={} olderThan={} tenants={}", total, cutoff, tenantIds.size());
        }
    }

    private long reapTenant(UUID tenantId, OffsetDateTime cutoff) {
        TenantContext.set(tenantId);
        try {
            Integer reaped = transactionTemplate.execute(status -> {
                pinTenantGuc(tenantId);
                List<MediaAsset> stale = mediaAssetRepository.findStalePending(cutoff);
                for (MediaAsset asset : stale) {
                    storageService.deleteByKey(asset.getObjectKey());   // quarantine cleanup
                    asset.setStatus(MediaAsset.Status.FAILED);
                    asset.setFailureReason("Processing timed out — please re-upload");
                }
                return stale.size();
            });
            long count = reaped == null ? 0 : reaped;
            if (count > 0) {
                log.info("event=media_reaper_reaped tenant={} reaped={}", tenantId, count);
            }
            return count;
        } finally {
            TenantContext.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private List<UUID> listTenantIds() {
        return transactionTemplate.execute(status ->
                entityManager.createNativeQuery("SELECT id FROM tenants").getResultList());
    }

    private void pinTenantGuc(UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.toString());
                stmt.execute();
            }
        });
    }
}
