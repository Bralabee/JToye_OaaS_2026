package uk.jtoye.core.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A copy-on-write, reference-counted, tenant-scoped image asset (V53
 * {@code media_asset}) — the durable structural fix behind safe vendor image
 * sharing/dedup (IMG-01). A {@code Product} references an asset via
 * {@link ProductMedia} and never owns bytes; editing a shared asset mints a NEW
 * asset and repoints only the one affected {@code product_media} row (D-01); a
 * physical MinIO delete happens only at reference-count 0.
 *
 * <p>Dedup is per-tenant on {@link #sha256} of the RAW upload (V53
 * {@code uq_media_asset_tenant_sha}). The {@link #productId}/{@link #isPrimary}/
 * {@link #sortOrder} fields are the pending-placement <em>intent</em> captured at
 * accept time (24-03) and consumed by the async worker (24-04) to create/repoint
 * the {@code product_media} row ONLY once the asset reaches {@link Status#ACTIVE};
 * they are NULL on backfilled ACTIVE rows.
 *
 * <p>House conventions mirror {@code security/access/ShopStaff.java}: hand-written
 * accessors (no Lombok / code-gen on entities), {@code @Audited} (Envers ->
 * {@code media_asset_aud} mirror), {@code @GeneratedValue(UUID)},
 * {@code @CreationTimestamp}. Column names map to the snake_case names in V53.
 */
@Entity
@Table(name = "media_asset")
@Audited
public class MediaAsset {

    /** Processing lifecycle state (V53 CHECK constraint). */
    public enum Status { PENDING, ACTIVE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Server-generated MinIO key ({@code <tenant>/media/<id>.webp} for ACTIVE, quarantine key for PENDING). */
    @Column(name = "object_key", nullable = false)
    private String objectKey;

    /** SHA-256 (hex) of the RAW upload — the per-tenant dedup key. */
    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "content_type", nullable = false, length = 32)
    private String contentType;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "bytes")
    private Long bytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    /** IMG-03 content-relevance flag: an ACTIVE asset below the advisory vision threshold. */
    @Column(name = "flagged", nullable = false)
    private boolean flagged = false;

    /** IMG-03 vendor-visible rejection reason (set on FAILED). */
    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    /** Pending-placement intent (D-04a) — set at accept (24-03), read by the worker (24-04). NULL on backfill. */
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "is_primary")
    private Boolean isPrimary;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Count of HUMAN-INITIATED re-drives (V60, 27-01 / D-04). Incremented only by
     * {@code MediaAssetService.redriveFromQuarantine} and bounded by
     * {@code jtoye.media.max-process-attempts}, so a vendor cannot loop a permanently-broken
     * asset through the pipeline (T-27-03).
     *
     * <p>Deliberately NOT a publish-attempt counter — {@code media_event_outbox.attempts} is that —
     * and deliberately not {@code COUNT(*)} of outbox rows, which the WR-01 re-upload path also
     * increments. No scheduled component ever touches it: the reaper cannot enqueue (D-04), so
     * every increment corresponds to a real human pressing Re-process.
     */
    @Column(name = "process_attempts", nullable = false)
    private int processAttempts = 0;

    /**
     * When the retained raw quarantine bytes become reclaimable (V60, 27-01 / D-03, D-08).
     * NULL means "no retained raw bytes were ever claimed" — which is CORRECT for every
     * pre-V60 row, since V53-backfilled ACTIVE assets have no quarantine object at all.
     *
     * <p>Set at accept time to {@code now + jtoye.media.quarantine-retention-ms} (72 h default).
     * This is what converts the old <em>unbounded loss at 15 minutes</em> into
     * <em>bounded loss at 72 hours</em>.
     */
    @Column(name = "quarantine_expires_at")
    private OffsetDateTime quarantineExpiresAt;

    /**
     * THE SENTINEL (V60, 27-01 / D-03 M1). NON-NULL means "the quarantine object for this asset is
     * gone". Stamped by {@code MediaQuarantineRetentionSweep} only on a CONFIRMED delete
     * ({@code StorageService.deleteByKeyChecked} returning true), by the worker on success, and by
     * the worker's validation-veto discard.
     *
     * <p>It is a NEW column rather than "null out {@link #quarantineExpiresAt}" for a load-bearing
     * reason: the sweep's legacy arm selects rows whose {@code quarantineExpiresAt} is ALREADY
     * null, so nulling it would be a no-op and the same rows would be re-selected on every tick
     * forever — silently, because {@code deleteByKey} swallows every exception. No selection
     * predicate can already satisfy this column, so stamping it genuinely terminates the row.
     *
     * <p>It is also the negation of the {@code redrivable} DTO bit: bytes are recoverable iff
     * {@code quarantineExpiresAt IS NOT NULL AND quarantineReclaimedAt IS NULL}.
     */
    @Column(name = "quarantine_reclaimed_at")
    private OffsetDateTime quarantineReclaimedAt;

    /**
     * Optimistic-lock version (WR-02, V59). Guards the reaper/worker race so a stale reaper
     * write (PENDING -> FAILED) against a row the worker already advanced to ACTIVE fails fast
     * with an optimistic-lock exception instead of silently clobbering the live image. JPA-managed;
     * never mutated by callers. Legitimately NOT audited (Envers do-not-audit-optimistic-locking).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }

    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }

    public Long getBytes() { return bytes; }
    public void setBytes(Long bytes) { this.bytes = bytes; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public boolean isFlagged() { return flagged; }
    public void setFlagged(boolean flagged) { this.flagged = flagged; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public UUID getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(UUID uploadedBy) { this.uploadedBy = uploadedBy; }

    public UUID getProductId() { return productId; }
    public void setProductId(UUID productId) { this.productId = productId; }

    public Boolean getIsPrimary() { return isPrimary; }
    public void setIsPrimary(Boolean isPrimary) { this.isPrimary = isPrimary; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public int getProcessAttempts() { return processAttempts; }
    public void setProcessAttempts(int processAttempts) { this.processAttempts = processAttempts; }

    public OffsetDateTime getQuarantineExpiresAt() { return quarantineExpiresAt; }
    public void setQuarantineExpiresAt(OffsetDateTime quarantineExpiresAt) { this.quarantineExpiresAt = quarantineExpiresAt; }

    public OffsetDateTime getQuarantineReclaimedAt() { return quarantineReclaimedAt; }
    public void setQuarantineReclaimedAt(OffsetDateTime quarantineReclaimedAt) { this.quarantineReclaimedAt = quarantineReclaimedAt; }

    /** JPA-managed optimistic lock version (WR-02). Null until the entity is first flushed. */
    public Long getVersion() { return version; }
}
