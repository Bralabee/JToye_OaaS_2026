package uk.jtoye.core.gdpr;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Durable, PII-free record that a UK-GDPR Article-17 erasure occurred
 * (Issue #84 [P1-2]).
 *
 * <p>This table is itself the audit artifact for erasures, so it is deliberately
 * NOT {@code @Audited} (no Envers {@code _aud} mirror) — an audit-of-the-audit
 * would only re-store the counts we already keep here.
 *
 * <p><b>No plaintext PII is ever stored.</b> The erased subject's email is kept
 * only as a one-way SHA-256 hex hash ({@code subjectEmailSha256}) so an operator
 * can later confirm "did we erase this person?" by hashing a supplied email and
 * comparing, without the record re-introducing the PII the erasure removed.
 */
@Entity
@Table(name = "erasure_records")
public class ErasureRecord implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subject_customer_id", nullable = false)
    private UUID subjectCustomerId;

    /** One-way SHA-256 hex digest of the erased email — never the plaintext. */
    @Column(name = "subject_email_sha256", length = 64)
    private String subjectEmailSha256;

    @Column(name = "orders_anonymised", nullable = false)
    private int ordersAnonymised;

    @Column(name = "reviews_anonymised", nullable = false)
    private int reviewsAnonymised;

    @Column(name = "aud_rows_scrubbed", nullable = false)
    private int audRowsScrubbed;

    @Column(name = "photos_deleted", nullable = false)
    private int photosDeleted;

    @Column(name = "erased_by", length = 255)
    private String erasedBy;

    @Column(name = "erased_at", nullable = false)
    private OffsetDateTime erasedAt;

    /** JPA no-arg constructor. */
    protected ErasureRecord() {
    }

    /**
     * Convenience all-args constructor. The {@code id} is assigned by the
     * application here (a fresh {@link UUID#randomUUID()}) rather than by a DB
     * default — the value is available to the caller immediately (returned in the
     * erasure response) without a round-trip to the database.
     */
    public ErasureRecord(UUID tenantId,
                         UUID subjectCustomerId,
                         String subjectEmailSha256,
                         int ordersAnonymised,
                         int reviewsAnonymised,
                         int audRowsScrubbed,
                         int photosDeleted,
                         String erasedBy,
                         OffsetDateTime erasedAt) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.subjectCustomerId = subjectCustomerId;
        this.subjectEmailSha256 = subjectEmailSha256;
        this.ordersAnonymised = ordersAnonymised;
        this.reviewsAnonymised = reviewsAnonymised;
        this.audRowsScrubbed = audRowsScrubbed;
        this.photosDeleted = photosDeleted;
        this.erasedBy = erasedBy;
        this.erasedAt = erasedAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    /**
     * Always {@code true}: an ErasureRecord is an append-only audit row that is
     * only ever inserted, never updated. Declaring it new lets Spring Data issue a
     * direct {@code persist} (INSERT) instead of a {@code merge} (SELECT-then-INSERT)
     * even though the id is pre-assigned.
     */
    @Override
    public boolean isNew() {
        return true;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getSubjectCustomerId() {
        return subjectCustomerId;
    }

    public String getSubjectEmailSha256() {
        return subjectEmailSha256;
    }

    public int getOrdersAnonymised() {
        return ordersAnonymised;
    }

    public int getReviewsAnonymised() {
        return reviewsAnonymised;
    }

    public int getAudRowsScrubbed() {
        return audRowsScrubbed;
    }

    public int getPhotosDeleted() {
        return photosDeleted;
    }

    public String getErasedBy() {
        return erasedBy;
    }

    public OffsetDateTime getErasedAt() {
        return erasedAt;
    }
}
