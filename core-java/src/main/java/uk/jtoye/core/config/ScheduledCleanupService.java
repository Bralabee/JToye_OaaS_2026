package uk.jtoye.core.config;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.security.TenantContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled cleanup jobs for housekeeping.
 * Runs daily to clean up stale data that would otherwise accumulate.
 */
@Service
public class ScheduledCleanupService {
    private static final Logger log = LoggerFactory.getLogger(ScheduledCleanupService.class);

    private final OrderRepository orderRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    @Value("${cleanup.stale-draft-hours:24}")
    private int staleDraftHours;

    public ScheduledCleanupService(OrderRepository orderRepository,
                                   EntityManager entityManager,
                                   PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Delete DRAFT orders older than configured threshold (default 24 hours).
     * DRAFT orders are incomplete — the customer abandoned checkout before payment.
     * Runs daily at 03:00 UTC.
     *
     * <p>QA-council M1: each tenant is cleaned in its OWN transaction (below), not
     * one transaction spanning all tenants. The RLS tenant GUC is transaction-local
     * (TenantSetLocalAspect uses {@code set_config(...,true)}); under a single
     * transaction, tenant A's deferred cascade delete of {@code order_items}
     * flushed AFTER the GUC had switched to tenant B, so FORCE-RLS filtered those
     * rows to 0 → {@code StaleStateException} → the whole job rolled back and
     * cleaned nothing. Per-tenant transactions flush each tenant's deletes under
     * its own GUC before the next tenant begins.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupStaleDraftOrders() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(staleDraftHours);

        List<UUID> tenantIds = readTenantIds();

        int totalCleaned = 0;
        for (UUID tenantId : tenantIds) {
            totalCleaned += cleanupTenant(tenantId, cutoff);
        }

        if (totalCleaned == 0) {
            log.debug("No stale DRAFT orders to clean up across {} tenants", tenantIds.size());
        } else {
            log.info("Total cleaned: {} stale DRAFT orders across {} tenants (threshold: {} hours)",
                    totalCleaned, tenantIds.size(), staleDraftHours);
        }
    }

    private List<UUID> readTenantIds() {
        return transactionTemplate.execute(status -> {
            @SuppressWarnings("unchecked")
            List<UUID> ids = entityManager
                    .createNativeQuery("SELECT id FROM tenants")
                    .getResultList();
            return ids;
        });
    }

    /**
     * Clean one tenant's stale drafts in a dedicated transaction. TenantContext is
     * set before the transaction so TenantSetLocalAspect applies the correct RLS
     * GUC to the repository ops inside it, and cleared afterwards. TransactionTemplate
     * (rather than a {@code @Transactional} helper method) is used deliberately to
     * avoid the Spring self-invocation proxy trap, which would otherwise start no
     * transaction at all and run the query with a NULL tenant.
     */
    private int cleanupTenant(UUID tenantId, OffsetDateTime cutoff) {
        TenantContext.set(tenantId);
        try {
            Integer cleaned = transactionTemplate.execute(status -> {
                List<Order> staleDrafts = orderRepository.findByStatus(OrderStatus.DRAFT).stream()
                        .filter(o -> o.getCreatedAt().isBefore(cutoff))
                        .toList();
                if (staleDrafts.isEmpty()) {
                    return 0;
                }
                orderRepository.deleteAll(staleDrafts);
                log.info("Cleaned up {} stale DRAFT orders for tenant {}", staleDrafts.size(), tenantId);
                return staleDrafts.size();
            });
            return cleaned == null ? 0 : cleaned;
        } finally {
            TenantContext.clear();
        }
    }
}
