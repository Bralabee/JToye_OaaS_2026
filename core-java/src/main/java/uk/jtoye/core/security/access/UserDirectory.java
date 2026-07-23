package uk.jtoye.core.security.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Login-populated grant-target picker (V52 {@code user_directory}, D-09): a
 * lightweight tenant-scoped row {@code (tenant_id, user_id, email, display_name,
 * last_seen)} upserted from the authenticated JWT so a GROUP_ADMIN can pick a
 * grant target by a human-recognisable identity (new staff appear after first
 * login).
 *
 * <p>Deliberately NOT Envers-audited (D-09) — unlike {@link ShopStaff}, there is
 * no {@code _aud} mirror; this is a mutable "last seen" cache, not an audit trail.
 * The {@code email} column is PII, so the V52 ENABLE+FORCE RLS policy is
 * load-bearing (proven cross-tenant in {@code ShopStaffRlsPolicyIntegrationTest}).
 *
 * <p>Composite PK {@code (tenant_id, user_id)} via {@link UserDirectoryId}. The
 * throttled write is NOT a JPA {@code save()} — it is the native
 * {@code UserDirectoryRepository.upsertSeen} ON CONFLICT DO UPDATE (D-09 throttle).
 */
@Entity
@Table(name = "user_directory")
@IdClass(UserDirectoryId.class)
public class UserDirectory {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Keycloak {@code sub}. */
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "last_seen", nullable = false)
    private OffsetDateTime lastSeen;

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public OffsetDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(OffsetDateTime lastSeen) { this.lastSeen = lastSeen; }
}
