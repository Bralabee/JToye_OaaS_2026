package uk.jtoye.core.audit;

import jakarta.persistence.*;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

import java.util.UUID;

/**
 * Custom Envers revision entity with tenant context tracking.
 * Captures tenant_id for audit trail compliance and tenant isolation.
 */
@Entity
@Table(name = "revinfo")
@RevisionEntity(TenantRevisionListener.class)
public class RevInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "revinfo_seq")
    @SequenceGenerator(name = "revinfo_seq", sequenceName = "revinfo_seq", allocationSize = 1)
    @Column(name = "rev", nullable = false)
    @RevisionNumber
    private Integer rev;

    @Column(name = "revtstmp")
    @RevisionTimestamp
    private Long revtstmp;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "user_id", length = 255)
    private String userId;

    public Integer getRev() {
        return rev;
    }

    public void setRev(Integer rev) {
        this.rev = rev;
    }

    public Long getRevtstmp() {
        return revtstmp;
    }

    public void setRevtstmp(Long revtstmp) {
        this.revtstmp = revtstmp;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
