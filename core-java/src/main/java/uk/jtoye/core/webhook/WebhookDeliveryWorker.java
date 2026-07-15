package uk.jtoye.core.webhook;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scheduled outbound webhook delivery worker (COMMS-05). STUB — real
 * implementation lands in the GREEN commit.
 */
@Component
public class WebhookDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookSigner signer;
    private final WebhookUrlValidator urlValidator;
    private final WebhookProperties properties;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final WebClient webClient;

    public WebhookDeliveryWorker(WebhookDeliveryRepository deliveryRepository,
                                 WebhookSubscriptionRepository subscriptionRepository,
                                 WebhookSigner signer,
                                 WebhookUrlValidator urlValidator,
                                 WebhookProperties properties,
                                 EntityManager entityManager,
                                 PlatformTransactionManager transactionManager,
                                 WebClient.Builder webClientBuilder) {
        this.deliveryRepository = deliveryRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.signer = signer;
        this.urlValidator = urlValidator;
        this.properties = properties;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.webClient = webClientBuilder.build();
    }

    @Scheduled(fixedDelayString = "${webhook.delivery.interval-ms:5000}")
    public void deliverDue() {
        log.debug("webhook delivery worker stub — no-op");
    }
}
