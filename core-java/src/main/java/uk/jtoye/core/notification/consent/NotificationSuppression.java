package uk.jtoye.core.notification.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A tenant-scoped, per-category opt-out row (V54, ENABLE+FORCE RLS). Presence of
 * a row {@code (tenantId, recipient, category)} means the recipient has
 * unsubscribed from that category and MUST NOT be emailed it again — a GDPR/PECR
 * opt-out that never expires. Writes are idempotent against the
 * {@code UNIQUE (tenant_id, recipient, category)} key (see
 * {@link NotificationSuppressionRepository#insertIfAbsent}).
 */
@Entity
@Table(name = "notification_suppression")
public class NotificationSuppression {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "recipient", nullable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 16)
    private NotificationCategory category;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }

    public NotificationCategory getCategory() { return category; }
    public void setCategory(NotificationCategory category) { this.category = category; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}
