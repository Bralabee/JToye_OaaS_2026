package uk.jtoye.core.webhook.dto;

import java.util.UUID;

/**
 * The answer to "send a test event" (#879): the ping is queued, not yet delivered. Read the outcome from the
 * delivery log ({@code GET /api/v1/webhooks/{id}/deliveries}) by {@code deliveryId}; {@code eventId} is the
 * {@code X-JToye-Event-Id} the receiver will see.
 *
 * <p>Also the body stored in {@code idempotency_keys} when the caller supplies an {@code Idempotency-Key}, so
 * the field names and order are part of that stored contract.
 */
public record WebhookTestEventView(UUID deliveryId, UUID eventId, String status) {
}
