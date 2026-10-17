package uk.jtoye.core.shop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Historical shop slug alias (Phase 39 / Plan 39-09, RWO-028, owner ruling 2026-10-10).
 *
 * <p>Maps a previous {@code shops.slug} ({@link #oldSlug}) to its owning shop ({@link #shopId})
 * so printed QR codes and shared links permanently redirect (HTTP 301) to the shop's current
 * canonical slug when a vendor deliberately changes a shop's web address.
 *
 * <p>Deliberately NOT {@code @Audited} and NOT tenant-scoped (exempted by addition in
 * {@code RlsContractTest.EXEMPT_TABLES}): anonymous storefront callers resolve old slugs before
 * any tenant is known, and slug uniqueness is global across all tenants.
 */
@Entity
@Table(name = "shop_slug_alias")
public class ShopSlugAlias {

    @Id
    @Column(name = "old_slug", nullable = false, length = 100)
    private String oldSlug;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ShopSlugAlias() {
    }

    public ShopSlugAlias(String oldSlug, UUID shopId) {
        this(oldSlug, shopId, OffsetDateTime.now());
    }

    public ShopSlugAlias(String oldSlug, UUID shopId, OffsetDateTime createdAt) {
        this.oldSlug = oldSlug;
        this.shopId = shopId;
        this.createdAt = createdAt != null ? createdAt : OffsetDateTime.now();
    }

    public String getOldSlug() {
        return oldSlug;
    }

    public void setOldSlug(String oldSlug) {
        this.oldSlug = oldSlug;
    }

    public UUID getShopId() {
        return shopId;
    }

    public void setShopId(UUID shopId) {
        this.shopId = shopId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
