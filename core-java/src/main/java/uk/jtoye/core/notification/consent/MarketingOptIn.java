package uk.jtoye.core.notification.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A tenant-scoped explicit marketing opt-in (V54, ENABLE+FORCE RLS). Presence of
 * a row {@code (tenantId, recipient)} is the PECR consent that unlocks
 * {@link NotificationCategory#MARKETING} sends; absence means marketing is
 * refused. Unique on {@code (tenant_id, recipient)}.
 */
@Entity
@Table(name = "marketing_opt_in")
public class MarketingOptIn {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "recipient", nullable = false)
    private String recipient;

    @Column(name = "opted_in_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime optedInAt;

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }

    public OffsetDateTime getOptedInAt() { return optedInAt; }
}
