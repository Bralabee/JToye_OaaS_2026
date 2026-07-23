package uk.jtoye.core.security.access;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@link UserDirectory} — mirrors the V52
 * {@code user_directory} PRIMARY KEY {@code (tenant_id, user_id)}.
 *
 * <p>Used as the {@code @IdClass}; field names + types match the entity's
 * {@code @Id} fields exactly (JPA requirement).
 */
public class UserDirectoryId implements Serializable {

    private UUID tenantId;
    private UUID userId;

    public UserDirectoryId() {
    }

    public UserDirectoryId(UUID tenantId, UUID userId) {
        this.tenantId = tenantId;
        this.userId = userId;
    }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserDirectoryId that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, userId);
    }
}
