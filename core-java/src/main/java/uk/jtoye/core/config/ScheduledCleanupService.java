package uk.jtoye.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Scheduled cleanup jobs for housekeeping.
 * Runs daily to clean up stale data that would otherwise accumulate.
 */
@Service
public class ScheduledCleanupService {
    private static final Logger log = LoggerFactory.getLogger(ScheduledCleanupService.class);

    private final OrderRepository orderRepository;

    @Value("${cleanup.stale-draft-hours:24}")
    private int staleDraftHours;

    public ScheduledCleanupService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Delete DRAFT orders older than configured threshold (default 24 hours).
     * DRAFT orders are incomplete — the customer abandoned checkout before payment.
     * Runs daily at 03:00 UTC.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupStaleDraftOrders() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(staleDraftHours);
        List<Order> staleDrafts = orderRepository.findByStatus(OrderStatus.DRAFT).stream()
                .filter(o -> o.getCreatedAt().isBefore(cutoff))
                .toList();

        if (staleDrafts.isEmpty()) {
            log.debug("No stale DRAFT orders to clean up");
            return;
        }

        orderRepository.deleteAll(staleDrafts);
        log.info("Cleaned up {} stale DRAFT orders older than {} hours", staleDrafts.size(), staleDraftHours);
    }
}
