package uk.jtoye.core.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * The (product &lt;-&gt; asset) join (V53 {@code product_media}, D-01): one table
 * carries both the primary image ({@link #primary is_primary=true}) and the
 * ordered gallery ({@link #sortOrder}). Copy-on-write repoint is a one-row
 * {@code UPDATE product_media SET asset_id=<new>} on the affected row
 * (see {@link MediaAssetService#repoint}).
 *
 * <p>{@link #tenantId} is carried deliberately so the join row is itself
 * RLS-scoped — isolation does NOT lean on the FK to {@code products}. Un-audited
 * (RESEARCH Open-Q2): a high-churn derived link, no {@code _aud} mirror. Accessors
 * are hand-written (house rule — no Lombok / code-gen on entities).
 */
@Entity
@Table(name = "product_media")
public class ProductMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getProductId() { return productId; }
    public void setProductId(UUID productId) { this.productId = productId; }

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
