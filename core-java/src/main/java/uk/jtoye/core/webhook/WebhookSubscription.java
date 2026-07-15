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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A vendor-registered outbound webhook endpoint (COMMS-04, V55).
 *
 * <p>Tenant-scoped under ENABLE+FORCE RLS ({@code webhook_subscription_tenant}
 * policy). {@code signingSecret} is stored plaintext and protected by FORCE RLS —
 * it is the HMAC key the delivery engine (22-05) re-signs every body with, so it
 * cannot be hashed. It is exposed to the caller in plaintext only once (create +
 * rotate) and is never carried on the read DTO.
 *
 * <p>{@code eventTypes} is persisted as a Postgres {@code TEXT[]} of
 * {@link WebhookEventType} names (mirrors the {@code Review.photoUrls} /
 * {@code Product.additionalImageUrls} array mapping); the service converts to/from
 * the typed enum at its boundary.
 */
@Entity
@Table(name = "webhook_subscription")
public class WebhookSubscription {

    /**
     * Subscription lifecycle. The delivery worker (22-05) delivers only for
     * {@link #ACTIVE}; {@link #PAUSED} is a manual pause and {@link #AUTO_PAUSED}
     * is the worker's consecutive-failure trip — the UI distinguishes the two.
     * {@link #REVOKED} is terminal.
     */
    public enum Status {
        ACTIVE,
        PAUSED,
        AUTO_PAUSED,
        REVOKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "target_url", nullable = false)
    private String targetUrl;

    @Column(name = "event_types", columnDefinition = "TEXT[]", nullable = false)
    private List<String> eventTypes = new ArrayList<>();

    @Column(name = "signing_secret", nullable = false)
    private String signingSecret;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

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

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public List<String> getEventTypes() {
        return eventTypes;
    }

    public void setEventTypes(List<String> eventTypes) {
        this.eventTypes = eventTypes;
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    public void setSigningSecret(String signingSecret) {
        this.signingSecret = signingSecret;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
