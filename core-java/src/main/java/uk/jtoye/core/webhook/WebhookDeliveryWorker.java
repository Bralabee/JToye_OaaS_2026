package uk.jtoye.core.webhook;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import uk.jtoye.core.security.TenantContext;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled outbound webhook delivery worker (COMMS-05) — mirrors the proven
 * {@code PaymentEventOutboxFlusher} shape (per-tenant {@link TransactionTemplate},
 * {@code FOR UPDATE SKIP LOCKED} claim, {@code computeBackoffMillis} exponential
 * backoff) on the DEDICATED per-{@code (subscription,event)} {@code webhook_delivery}
 * table so one hostile endpoint's rows back off independently and NEVER
 * head-of-line block a healthy subscription.
 *
 * <p>Per claimed row: read the subscription's CURRENT secret, re-validate the
 * target URL (T-22-05-03 SSRF/DNS-rebinding), sign the STORED payload bytes with
 * a fresh timestamp, POST them (WebClient, config timeout) with the three headers,
 * and record the outcome:
 * <ul>
 *   <li>2xx → {@code DELIVERED} + reset the subscription's consecutive-failure counter;</li>
 *   <li>failure → {@code RETRYING} with {@code next_attempt_at} pushed out by
 *       bounded exponential backoff, or {@code FAILED} at {@code max-attempts};
 *       the subscription's consecutive-failure counter increments and, at
 *       {@code auto-pause-threshold}, the subscription flips to {@code AUTO_PAUSED}
 *       (subsequent claims for it are dropped in {@link #attemptDelivery}).</li>
 * </ul>
 *
 * <p>The RLS tenant GUC + {@link TenantContext} are pinned per tenant before each
 * claim; a {@code TransactionTemplate} (not a {@code @Transactional} private
 * method) avoids the Spring self-invocation NULL-tenant trap. Logs status only,
 * never the signing secret (T-22-05-06).
 */
@Component
public class WebhookDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

    static final String EVENT_ID_HEADER = "X-JToye-Event-Id";
    static final String EVENT_TYPE_HEADER = "X-JToye-Event-Type";

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

    /**
     * Exponential backoff with a cap: {@code base * 2^(attempts-1)}, clamped to
     * {@code capMs}. Copied verbatim from {@code PaymentEventOutboxFlusher} incl.
     * the loop-not-shift overflow guard (a single {@code base << (attempts-1)}
     * wraps to garbage — including exactly 0 — for large attempt counts).
     */
    static long computeBackoffMillis(int attempts, long baseMs, long capMs) {
        if (attempts < 1 || baseMs <= 0) {
            return Math.min(Math.max(baseMs, 0), capMs);
        }
        long backoff = baseMs;
        for (int i = 1; i < attempts && backoff < capMs; i++) {
            backoff <<= 1;
            if (backoff <= 0) { // overflow guard for absurdly large caps
                return capMs;
            }
        }
        return Math.min(backoff, capMs);
    }

    /**
     * Claim + deliver due rows per-tenant. Each tenant is drained in its OWN
     * transaction so the transaction-local RLS GUC always matches the tenant that
     * owns the claimed rows, and one tenant's failure neither rolls back nor
     * starves the others (the QA-council C1 fix symmetry).
     */
    @Scheduled(fixedDelayString = "${webhook.delivery.interval-ms:5000}")
    public void deliverDue() {
        for (UUID tenantId : listTenantIds()) {
            try {
                deliverForTenant(tenantId);
            } catch (Exception e) {
                log.error("event=webhook_delivery_tenant_failed tenant={} — continuing: {}",
                        tenantId, e.getMessage());
            }
        }
    }

    private void deliverForTenant(UUID tenantId) {
        TenantContext.set(tenantId);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                pinTenantGuc(tenantId);
                List<WebhookDelivery> due =
                        deliveryRepository.claimDueBatch(properties.getDelivery().getBatchSize());
                for (WebhookDelivery delivery : due) {
                    try {
                        attemptDelivery(delivery);
                    } catch (Exception e) {
                        // Per-subscription isolation: one delivery's unexpected error
                        // must never roll back the whole batch (that WOULD be a HOL block).
                        log.error("event=webhook_delivery_error delivery={}: {}",
                                delivery.getId(), e.getMessage());
                    }
                }
            });
        } finally {
            TenantContext.clear();
        }
    }

    private void attemptDelivery(WebhookDelivery delivery) {
        WebhookSubscription sub = subscriptionRepository.findById(delivery.getSubscriptionId()).orElse(null);
        if (sub == null || sub.getStatus() != WebhookSubscription.Status.ACTIVE) {
            // Paused / auto-paused / revoked / gone — stop attempting this row so a
            // paused subscription's backlog does not churn forever.
            delivery.setStatus(WebhookDelivery.Status.FAILED);
            delivery.setLastError("subscription not active: " + (sub == null ? "missing" : sub.getStatus()));
            deliveryRepository.save(delivery);
            return;
        }

        // Re-guard SSRF at egress (a subscription created before validation
        // tightened, or DNS-rebinding since create) — T-22-05-03.
        try {
            urlValidator.validate(sub.getTargetUrl());
        } catch (IllegalArgumentException e) {
            recordFailure(delivery, sub, null, "target url rejected: " + e.getMessage());
            return;
        }

        // Sign the EXACT stored bytes we POST (Pitfall 6) with the subscription's
        // CURRENT secret + a fresh timestamp.
        byte[] body = delivery.getPayload().getBytes(StandardCharsets.UTF_8);
        long ts = Instant.now().getEpochSecond();
        String signature = signer.sign(body, sub.getSigningSecret(), ts);

        try {
            Integer statusCode = webClient.post()
                    .uri(URI.create(sub.getTargetUrl()))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(WebhookSigner.SIGNATURE_HEADER, signature)
                    .header(EVENT_ID_HEADER, delivery.getEventId().toString())
                    .header(EVENT_TYPE_HEADER, delivery.getEventType())
                    .bodyValue(body)
                    .exchangeToMono(response -> response.releaseBody()
                            .thenReturn(response.statusCode().value()))
                    .block(Duration.ofSeconds(properties.getDelivery().getTimeoutSeconds()));

            int code = statusCode == null ? 0 : statusCode;
            if (code >= 200 && code < 300) {
                recordSuccess(delivery, sub, code);
            } else {
                recordFailure(delivery, sub, code, "HTTP " + code);
            }
        } catch (Exception e) {
            // Timeout / connection refused / etc. — record the failure class, NEVER
            // the secret or the full row (T-22-05-06).
            recordFailure(delivery, sub, null, e.getClass().getSimpleName());
        }
    }

    private void recordSuccess(WebhookDelivery delivery, WebhookSubscription sub, int statusCode) {
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setStatus(WebhookDelivery.Status.DELIVERED);
        delivery.setLastHttpStatus(statusCode);
        delivery.setLastError(null);
        deliveryRepository.save(delivery);

        if (sub.getConsecutiveFailures() != 0) {
            sub.setConsecutiveFailures(0); // a success clears the auto-pause counter
            subscriptionRepository.save(sub);
        }
        log.info("event=webhook_delivered delivery={} subscription={} http={}",
                delivery.getId(), sub.getId(), statusCode);
    }

    private void recordFailure(WebhookDelivery delivery, WebhookSubscription sub,
                               Integer statusCode, String error) {
        int attempts = delivery.getAttemptCount() + 1;
        delivery.setAttemptCount(attempts);
        delivery.setLastHttpStatus(statusCode);
        delivery.setLastError(truncate(error));

        int failures = sub.getConsecutiveFailures() + 1;
        sub.setConsecutiveFailures(failures);
        if (failures >= properties.getDelivery().getAutoPauseThreshold()
                && sub.getStatus() == WebhookSubscription.Status.ACTIVE) {
            sub.setStatus(WebhookSubscription.Status.AUTO_PAUSED);
            log.warn("event=webhook_subscription_auto_paused subscription={} consecutiveFailures={}",
                    sub.getId(), failures);
        }
        subscriptionRepository.save(sub);

        if (attempts >= properties.getDelivery().getMaxAttempts()) {
            delivery.setStatus(WebhookDelivery.Status.FAILED);
            log.warn("event=webhook_delivery_failed delivery={} attempts={} http={}",
                    delivery.getId(), attempts, statusCode);
        } else {
            delivery.setStatus(WebhookDelivery.Status.RETRYING);
            long backoff = computeBackoffMillis(attempts,
                    properties.getDelivery().getBackoffBaseMs(),
                    properties.getDelivery().getBackoffCapMs());
            delivery.setNextAttemptAt(OffsetDateTime.now().plusNanos(backoff * 1_000_000L));
        }
        deliveryRepository.save(delivery);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
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
