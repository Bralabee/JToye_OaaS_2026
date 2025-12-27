package uk.jtoye.core.audit;

import jakarta.persistence.*;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

/**
 * Custom Envers revision entity mapped to our Flyway-managed table `revinfo`
 * and sequence `revinfo_seq`.
 */
@Entity
@Table(name = "revinfo")
@RevisionEntity
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
}
