package uk.jtoye.core.media;

import java.util.UUID;

/**
 * The trivial AMQP payload the accept hands to the async worker (IMG-02): just the
 * tenant + the {@code media_asset} id. The worker ({@code MediaProcessingWorker}, 24-04)
 * pins the tenant GUC, re-reads the asset by id (the DB is the source of truth), and
 * skips if it is no longer PENDING — so redelivery is idempotent and the event carries
 * no processing state of its own.
 */
public record MediaProcessingEvent(UUID tenantId, UUID assetId) {
}
