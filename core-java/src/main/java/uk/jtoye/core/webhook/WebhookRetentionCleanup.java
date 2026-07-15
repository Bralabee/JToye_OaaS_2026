package uk.jtoye.core.webhook;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled retention prune of webhook_delivery (#107). STUB — real
 * implementation lands in the GREEN commit.
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
        log.debug("webhook retention cleanup stub — no-op");
    }
}
