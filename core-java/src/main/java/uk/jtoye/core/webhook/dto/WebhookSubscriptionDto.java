package uk.jtoye.core.webhook.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.webhook.WebhookEventType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read/list representation of a webhook subscription (COMMS-04).
 *
 * <p>Deliberately NEVER carries {@code signingSecret}: the secret is returned in
 * plaintext exactly once (on create + rotate) via {@link WithSecret} and is never
 * re-fetchable through GET/list.
 */
public record WebhookSubscriptionDto(
        UUID id,
        String targetUrl,
        List<WebhookEventType> eventTypes,
        String status,
        @Schema(description = "Consecutive deliveries that used all their attempts without a 2xx (37-43: deliveries, "
                + "not attempts). At webhook.delivery.auto-pause-after-failed-deliveries (default 3) the "
                + "subscription becomes AUTO_PAUSED; a delivered event resets it to 0.")
        int consecutiveFailures,
        @Schema(description = "Events kept for this endpoint while it is paused (delivery status HELD), counted by "
                + "the server. Resuming re-queues and delivers every one of them.")
        long heldCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /**
     * Create/rotate response only — the subscription plus its {@code signingSecret}
     * in plaintext, shown ONCE. Vendors must store the secret at this point; it is
     * never returned again.
     */
    public record WithSecret(
            WebhookSubscriptionDto subscription,
            String signingSecret
    ) {}
}
