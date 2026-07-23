package uk.jtoye.core.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.storage.StorageService;

import java.util.Optional;
import java.util.UUID;

/**
 * Copy-on-write + reference-counted asset operations (IMG-01). All operations are
 * tenant-scoped via the RLS wall (the caller pins the tenant GUC — request thread
 * or worker). This service owns the three durable invariants behind safe vendor
 * image sharing/dedup:
 *
 * <ul>
 *   <li><b>Copy-on-write repoint (D-01)</b> — editing a shared asset never mutates
 *       bytes; a new asset is minted and only the one affected {@code product_media}
 *       row is repointed ({@link #repoint}).</li>
 *   <li><b>Reference-counted delete (IMG-01)</b> — a physical MinIO delete happens
 *       ONLY when no {@code product_media} row still references the asset
 *       ({@link #releaseAsset}); a still-referenced asset is left intact.</li>
 *   <li><b>sha256 dedup (IMG-01)</b> — an identical raw upload within a tenant
 *       reuses the existing asset ({@link #findDedup}).</li>
 * </ul>
 *
 * <p>The vendor-triggered delete paths ({@code ProductService.removeImage} /
 * {@code removeAdditionalImage}) are wired to {@link #releaseAsset} in 24-05.
 */
@Service
@Transactional
public class MediaAssetService {

    private static final Logger log = LoggerFactory.getLogger(MediaAssetService.class);

    private final MediaAssetRepository mediaAssetRepository;
    private final ProductMediaRepository productMediaRepository;
    private final StorageService storageService;

    public MediaAssetService(MediaAssetRepository mediaAssetRepository,
                             ProductMediaRepository productMediaRepository,
                             StorageService storageService) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.productMediaRepository = productMediaRepository;
        this.storageService = storageService;
    }

    /**
     * Copy-on-write repoint (D-01): swap the asset a single {@code product_media}
     * row references to {@code newAssetId}, leaving every other row that shared the
     * old asset untouched. Callers that repoint AWAY from an asset should then call
     * {@link #releaseAsset} on the old asset id to reclaim it if it is now orphaned.
     */
    public void repoint(UUID productMediaRowId, UUID newAssetId) {
        productMediaRepository.repoint(productMediaRowId, newAssetId);
        log.debug("Repointed product_media {} to asset {}", productMediaRowId, newAssetId);
    }

    /**
     * Reference-counted delete (IMG-01): if no {@code product_media} row references
     * {@code oldAssetId}, physically delete its MinIO object AND remove the row.
     * A still-referenced asset is a no-op (neither the object nor the row is touched).
     */
    public void releaseAsset(UUID oldAssetId) {
        long refs = productMediaRepository.countByAssetId(oldAssetId);
        if (refs > 0) {
            log.debug("Asset {} still referenced by {} product_media row(s) — not deleting", oldAssetId, refs);
            return;
        }
        mediaAssetRepository.findById(oldAssetId).ifPresent(asset -> {
            storageService.deleteByKey(asset.getObjectKey());   // physical MinIO delete ONLY at ref-count 0
            mediaAssetRepository.delete(asset);
            log.info("Released asset {} (ref-count 0): deleted object {} and row", oldAssetId, asset.getObjectKey());
        });
    }

    /**
     * sha256 dedup short-circuit (IMG-01): the existing asset for an identical raw
     * upload within {@code tenantId}, if any. The V53 {@code uq_media_asset_tenant_sha}
     * unique index enforces the invariant that backs this lookup.
     */
    @Transactional(readOnly = true)
    public Optional<MediaAsset> findDedup(UUID tenantId, String sha256) {
        return mediaAssetRepository.findByTenantIdAndSha256(tenantId, sha256);
    }
}
