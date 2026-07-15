package uk.jtoye.core.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One durable delivery attempt-log row per {@code (subscription, event)}
 * (COMMS-05, V56).
 *
 * <p>Tenant-scoped under ENABLE+FORCE RLS ({@code webhook_delivery_tenant}
 * policy). The {@code payload} column stores the envelope serialized ONCE to a
 * stable string — the delivery worker signs and POSTs exactly these bytes so the
 * receiver's HMAC verify matches (Pitfall 6). A dedicated per-{@code (subscription,
 * event)} table (rather than the single ordered payment outbox) is what gives
 * per-subscription isolation: one failing endpoint's rows back off via
 * {@link #nextAttemptAt} independently and never head-of-line block a healthy
 * subscription.
 *
 * <p>{@code isReplay}/{@code replayOf} tag a manual replay as a NEW attempt that
 * leaves the original row's status history intact.
 */
@Entity
@Table(name = "webhook_delivery")
public class WebhookDelivery {

    /**
     * Delivery lifecycle. {@link #PENDING} is inserted by the fanout listener;
     * the worker flips it to {@link #DELIVERED} on 2xx, {@link #RETRYING} on a
     * transient failure (with {@link #nextAttemptAt} pushed out by backoff), or
     * {@link #FAILED} once {@code attemptCount} reaches the configured max.
     */
    public enum Status {
        PENDING,
        DELIVERED,
        RETRYING,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    /** Envelope id — the receiver dedupe key echoed as {@code X-JToye-Event-Id}. */
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    /** The exact envelope bytes to sign + POST (serialize once — Pitfall 6). */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt = OffsetDateTime.now();

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "is_replay", nullable = false)
    private boolean replay = false;

    @Column(name = "replay_of")
    private UUID replayOf;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public void setSubscriptionId(UUID subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public OffsetDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(OffsetDateTime nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Integer getLastHttpStatus() {
        return lastHttpStatus;
    }

    public void setLastHttpStatus(Integer lastHttpStatus) {
        this.lastHttpStatus = lastHttpStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public boolean isReplay() {
        return replay;
    }

    public void setReplay(boolean replay) {
        this.replay = replay;
    }

    public UUID getReplayOf() {
        return replayOf;
    }

    public void setReplayOf(UUID replayOf) {
        this.replayOf = replayOf;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
