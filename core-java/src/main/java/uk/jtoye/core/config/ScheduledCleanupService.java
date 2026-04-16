package uk.jtoye.core.config;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    @Value("${cleanup.stale-draft-hours:24}")
    private int staleDraftHours;

    public ScheduledCleanupService(OrderRepository orderRepository, EntityManager entityManager) {
        this.orderRepository = orderRepository;
        this.entityManager = entityManager;
    }

    /**
     * Delete DRAFT orders older than configured threshold (default 24 hours).
     * DRAFT orders are incomplete — the customer abandoned checkout before payment.
     * Runs daily at 03:00 UTC.
     *
     * <p>SECURITY: Iterates per-tenant to ensure TenantContext is set before each
     * query, enforcing RLS. Without this, the query would run with NULL tenant
     * and return rows from ALL tenants (cross-tenant data destruction).
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupStaleDraftOrders() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(staleDraftHours);

        @SuppressWarnings("unchecked")
        List<UUID> tenantIds = entityManager
                .createNativeQuery("SELECT id FROM tenants")
                .getResultList();

        int totalCleaned = 0;
        for (UUID tenantId : tenantIds) {
            TenantContext.set(tenantId);
            try {
                List<Order> staleDrafts = orderRepository.findByStatus(OrderStatus.DRAFT).stream()
                        .filter(o -> o.getCreatedAt().isBefore(cutoff))
                        .toList();
                if (!staleDrafts.isEmpty()) {
                    orderRepository.deleteAll(staleDrafts);
                    totalCleaned += staleDrafts.size();
                    log.info("Cleaned up {} stale DRAFT orders for tenant {}", staleDrafts.size(), tenantId);
                }
            } finally {
                TenantContext.clear();
            }
        }

        if (totalCleaned == 0) {
            log.debug("No stale DRAFT orders to clean up across {} tenants", tenantIds.size());
        } else {
            log.info("Total cleaned: {} stale DRAFT orders across {} tenants (threshold: {} hours)",
                    totalCleaned, tenantIds.size(), staleDraftHours);
        }
    }
}
