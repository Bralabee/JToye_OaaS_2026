package uk.jtoye.core.webhook;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The versioned event envelope delivered to a vendor endpoint (COMMS-05, D-05).
 *
 * <p>LOCKED wire contract (Assumption A4 resolved):
 * <pre>
 * { id, type, tenantId, occurredAt, version, data: { …full existing DTO } }
 * </pre>
 * {@code id} is the receiver dedupe key (echoed as {@code X-JToye-Event-Id});
 * {@code data} reuses the existing domain DTO (OrderDto / refund / onboarding /
 * payment) rather than a bespoke minimal shape — deliveries go to the vendor's
 * OWN endpoint carrying the vendor's OWN tenant data (D-05).
 *
 * <p>The envelope is serialized to bytes exactly ONCE; those exact bytes are
 * both signed and POSTed (Pitfall 6), so the field order here is the wire order.
 *
 * @param id         event id / dedupe key
 * @param type       machine event type (e.g. {@code order.ready})
 * @param tenantId   owning tenant
 * @param occurredAt event time (ISO-8601)
 * @param version    envelope schema version ({@code webhook.envelope.version})
 * @param data       the full existing domain DTO / event payload
 */
public record WebhookEventEnvelope(
        UUID id,
        String type,
        UUID tenantId,
        OffsetDateTime occurredAt,
        String version,
        Object data
) {}
