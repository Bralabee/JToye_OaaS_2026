package uk.jtoye.core.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ProductMedia} join rows (V53 {@code product_media}).
 *
 * <p>Backs the two IMG-01 invariants: {@link #countByAssetId} is the reference
 * count for the delete-at-0 rule (a physical MinIO delete happens ONLY when no
 * {@code product_media} row references the asset), and {@link #repoint} is the
 * one-row copy-on-write UPDATE (editing a shared asset mints a new asset and
 * repoints only the affected row — D-01). {@link #findPrimaryActiveObjectKey}
 * powers the asset-first dual-read resolver (D-03a).
 */
public interface ProductMediaRepository extends JpaRepository<ProductMedia, UUID> {

    /** Reference count for the delete-at-0 rule (RESEARCH Pattern 2, IMG-01). */
    long countByAssetId(UUID assetId);

    /** All join rows referencing an asset (delete-cascade / repoint bookkeeping). */
    java.util.List<ProductMedia> findByAssetId(UUID assetId);

    /** All join rows for a product, primary first then by gallery order. */
    java.util.List<ProductMedia> findByProductIdOrderByPrimaryDescSortOrderAsc(UUID productId);

    /**
     * Copy-on-write repoint (D-01): the one-row UPDATE that swaps the asset a single
     * {@code product_media} row points at, leaving every other row untouched.
     * {@code clearAutomatically} so a subsequent read in the same persistence
     * context reflects the new {@code asset_id} rather than a stale cached row.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ProductMedia pm SET pm.assetId = :newAssetId WHERE pm.id = :rowId")
    void repoint(@Param("rowId") UUID rowId, @Param("newAssetId") UUID newAssetId);

    /**
     * Asset-first dual-read resolver (D-03a): the {@code object_key} of a product's
     * primary image IF it resolves to an ACTIVE {@code media_asset} derivative, else
     * empty (the caller then falls back to the flat {@code products.image_url}). The
     * V53 partial-unique index {@code uq_product_media_primary} guarantees at most one
     * primary row per product, so the result is single-valued. A primary that points
     * at a PENDING/FAILED asset resolves empty — exactly the fallback behaviour.
     */
    @Query("SELECT a.objectKey FROM ProductMedia pm, MediaAsset a "
            + "WHERE a.id = pm.assetId AND pm.productId = :productId AND pm.primary = true "
            + "AND a.status = uk.jtoye.core.media.MediaAsset.Status.ACTIVE")
    Optional<String> findPrimaryActiveObjectKey(@Param("productId") UUID productId);
}
