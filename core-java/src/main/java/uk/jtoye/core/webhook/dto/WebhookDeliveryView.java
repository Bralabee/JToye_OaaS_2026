package uk.jtoye.core.webhook.dto;

import uk.jtoye.core.webhook.WebhookDelivery;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read view of one webhook delivery attempt (COMMS-05). The {@code payload}
 * body is deliberately absent — the log lists attempts, it is not an envelope
 * archive, and the payload carries customer PII.
 *
 * <p>Lifted out of {@code WebhookDeliveryController} when the delivery read path
 * moved behind {@code WebhookDeliveryService} (#444). The OpenAPI schema name is
 * springdoc's simple class name, so it stays {@code WebhookDeliveryView} and the
 * published contract is unchanged.
 *
 * <p>Also the replay response body stored in {@code idempotency_keys} — Jackson
 * deserializes it back through the canonical record constructor, so field names
 * and order are part of that stored contract.
 */
public record WebhookDeliveryView(
        UUID id,
        UUID subscriptionId,
        UUID eventId,
        String eventType,
        String status,
        int attemptCount,
        Integer lastHttpStatus,
        String lastError,
        boolean replay,
        UUID replayOf,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static WebhookDeliveryView from(WebhookDelivery d) {
        return new WebhookDeliveryView(
                d.getId(),
                d.getSubscriptionId(),
                d.getEventId(),
                d.getEventType(),
                d.getStatus().name(),
                d.getAttemptCount(),
                d.getLastHttpStatus(),
                d.getLastError(),
                d.isReplay(),
                d.getReplayOf(),
                d.getNextAttemptAt(),
                d.getCreatedAt(),
                d.getUpdatedAt());
    }
}
