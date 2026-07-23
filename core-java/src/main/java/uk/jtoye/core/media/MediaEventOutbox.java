package uk.jtoye.core.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Durable outbox row for a single media-processing event (V58, IMG-02).
 *
 * <p>Written in the SAME transaction as the accept ({@code MediaAssetService
 * .acceptQuarantineAndQueue}: quarantine PUT + PENDING {@code media_asset} +
 * this row), then published to RabbitMQ by {@link MediaEventOutboxFlusher}. A
 * broker outage no longer drops the hand-off — the row simply stays PENDING
 * until the next flusher tick retries it (the standard transactional-outbox
 * contract; the worker is idempotent by re-reading the asset and skipping if it
 * is no longer PENDING).
 *
 * <p>A near-clone of {@code payment/PaymentEventOutbox} MINUS the per-row
 * {@code event_type}/{@code routing_key}/{@code exchange} columns: a DEDICATED
 * media outbox has exactly one destination exchange ({@code media.events}), so
 * the flusher needs no closed-set dispatch and the {@code outbox_flusher_dispatch_trap}
 * cannot occur. The payload is a serialized {@link MediaProcessingEvent}.
 */
@Entity
@Table(name = "media_event_outbox")
public class MediaEventOutbox {

    public enum Status {
        PENDING,
        SENT,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** The {@code media_asset} this event asks the worker to process. */
    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /**
     * Earliest instant the flusher may (re)try this row. Set to "now" on insert
     * so fresh rows are immediately eligible; pushed out with exponential backoff
     * on each failed publish attempt (mirrors PaymentEventOutbox / V46).
     */
    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt = OffsetDateTime.now();

    /**
     * TRUE when the payload itself is unrecoverable (JSON corruption) — retrying
     * can never succeed, so the resurrection pass must skip the row. FALSE FAILED
     * rows are retry-exhausted but retryable.
     */
    @Column(name = "poison", nullable = false)
    private boolean poison = false;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    public MediaEventOutbox() {
    }

    public MediaEventOutbox(UUID tenantId, UUID assetId, String payload) {
        this.tenantId = tenantId;
        this.assetId = assetId;
        this.payload = payload;
    }

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(OffsetDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public boolean isPoison() { return poison; }
    public void setPoison(boolean poison) { this.poison = poison; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getSentAt() { return sentAt; }
    public void setSentAt(OffsetDateTime sentAt) { this.sentAt = sentAt; }
}
