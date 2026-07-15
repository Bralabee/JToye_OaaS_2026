package uk.jtoye.core.webhook.dto;

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
        int consecutiveFailures,
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
