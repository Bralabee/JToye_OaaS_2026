package uk.jtoye.core.webhook;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.security.TenantContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled bounded-retention prune of {@code webhook_delivery} (#107,
 * T-22-05-05) — the {@code ScheduledCleanupService} shape: per-tenant, own
 * transaction each, {@link TenantContext} + GUC pinned so the delete is
 * RLS-scoped, a {@code TransactionTemplate} (not a {@code @Transactional} private
 * method) to dodge the Spring self-invocation NULL-tenant trap.
 *
 * <p><b>Scoped to {@code webhook_delivery} ONLY.</b> Suppression rows
 * ({@code notification_suppression}, 22-02) are deliberately NEVER time-pruned:
 * they are bounded by their UNIQUE key, and time-pruning a GDPR/PECR opt-out
 * would resurrect a suppressed recipient (SPEC AC #13).
 */
@Component
public class WebhookRetentionCleanup {

    private static final Logger log = LoggerFactory.getLogger(WebhookRetentionCleanup.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookProperties properties;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public WebhookRetentionCleanup(WebhookDeliveryRepository deliveryRepository,
                                   WebhookProperties properties,
                                   EntityManager entityManager,
                                   PlatformTransactionManager transactionManager) {
        this.deliveryRepository = deliveryRepository;
        this.properties = properties;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${webhook.delivery.retention-interval-ms:86400000}")
    public void pruneExpired() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(properties.getDelivery().getRetentionDays());
        List<UUID> tenantIds = listTenantIds();
        long total = 0;
        for (UUID tenantId : tenantIds) {
            try {
                total += pruneTenant(tenantId, cutoff);
            } catch (Exception e) {
                log.error("event=webhook_retention_failed tenant={} — continuing: {}",
                        tenantId, e.getMessage());
            }
        }
        if (total > 0) {
            log.info("event=webhook_retention_swept deleted={} olderThan={} tenants={}",
                    total, cutoff, tenantIds.size());
        }
    }

    private long pruneTenant(UUID tenantId, OffsetDateTime cutoff) {
        TenantContext.set(tenantId);
        try {
            Long deleted = transactionTemplate.execute(status -> {
                pinTenantGuc(tenantId);
                return deliveryRepository.deleteByCreatedAtBefore(cutoff);
            });
            long count = deleted == null ? 0 : deleted;
            if (count > 0) {
                log.info("event=webhook_retention_pruned tenant={} deleted={}", tenantId, count);
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
            try (var stmt = connection.prepareStatement(
                    "SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.toString());
                stmt.execute();
            }
        });
    }
}
