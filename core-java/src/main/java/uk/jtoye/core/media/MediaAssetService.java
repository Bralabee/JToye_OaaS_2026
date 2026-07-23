package uk.jtoye.core.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.storage.StorageService;

import java.util.List;
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
    private final MediaEventOutboxRepository mediaEventOutboxRepository;
    private final ProductRepository productRepository;
    private final ShopAccessService shopAccessService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public MediaAssetService(MediaAssetRepository mediaAssetRepository,
                             ProductMediaRepository productMediaRepository,
                             MediaEventOutboxRepository mediaEventOutboxRepository,
                             ProductRepository productRepository,
                             ShopAccessService shopAccessService,
                             StorageService storageService,
                             ObjectMapper objectMapper) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.productMediaRepository = productMediaRepository;
        this.mediaEventOutboxRepository = mediaEventOutboxRepository;
        this.productRepository = productRepository;
        this.shopAccessService = shopAccessService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    /** Pending-placement intent captured at accept, consumed by the worker on ACTIVE (D-04a). */
    public record MediaPlacement(boolean isPrimary, int sortOrder) {
    }

    /**
     * The accept side of the safe async pipeline (IMG-02). Runs on the request thread
     * after the reject-early size gate; wrapped by
     * {@code IdempotencyService.execute("media.upload", ...)} so a replayed
     * {@code Idempotency-Key} never re-quarantines. In ONE transaction it:
     * <ol>
     *   <li>loads the product (RLS/tenant-scoped) — 404 if absent — and re-checks the
     *       VSA-02 shop-scoped write gate (image write = {@code SHOP_MANAGER}), preserving
     *       the boundary the retired synchronous handler enforced;</li>
     *   <li>short-circuits on sha256 dedup (the {@code uq_media_asset_tenant_sha} unique
     *       index guarantees at most one asset per tenant+content, so an identical raw
     *       upload returns the existing asset instead of storing duplicate bytes);</li>
     *   <li>PUTs the RAW bytes to a content-addressed quarantine key with the DETECTED
     *       content type (never the client header — the worker re-validates);</li>
     *   <li>INSERTs a {@code PENDING} {@code media_asset} carrying the pending-placement
     *       intent;</li>
     *   <li>INSERTs a {@code media_event_outbox} PENDING row in the SAME tx (transactional
     *       outbox) — the flusher publishes to {@code media.events} after commit.</li>
     * </ol>
     * The PENDING asset + the outbox row commit atomically or neither does.
     *
     * @param sha256 the caller-computed SHA-256 hex of {@code rawBytes} (also the
     *               idempotency request fingerprint) — reused here for dedup + the
     *               content-addressed quarantine key + the {@code media_asset.sha256} column
     */
    public MediaAcceptDto acceptQuarantineAndQueue(UUID productId,
                                                   byte[] rawBytes,
                                                   String sha256,
                                                   UUID uploadedBy,
                                                   MediaPlacement placement) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set for media accept"));

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        // Preserve the VSA-02 shop-scoped write boundary the retired sync handler enforced.
        shopAccessService.require(product.getShopId(), ShopRole.SHOP_MANAGER);

        // Dedup short-circuit: (tenant_id, sha256) is unique, so at most one asset can exist
        // for these bytes — return it rather than attempting a duplicate insert.
        Optional<MediaAsset> existing = findDedup(tenantId, sha256);
        if (existing.isPresent()) {
            MediaAsset a = existing.get();
            log.info("Dedup short-circuit on accept: product {} reuses existing asset {} (status {})",
                    productId, a.getId(), a.getStatus());
            return new MediaAcceptDto(a.getId(), a.getStatus().name());
        }

        String contentType = storageService.detectContentType(rawBytes);
        if (contentType == null) {
            // Not a recognised image — quarantine anyway; the worker fails it authoritatively.
            contentType = "application/octet-stream";
        }
        String objectKey = tenantId + "/quarantine/" + sha256 + extensionFor(contentType);

        storageService.putBytes(objectKey, rawBytes, contentType);   // quarantine the RAW bytes

        MediaAsset asset = new MediaAsset();
        asset.setTenantId(tenantId);
        asset.setObjectKey(objectKey);
        asset.setSha256(sha256);
        asset.setContentType(contentType);
        asset.setBytes((long) rawBytes.length);
        asset.setStatus(MediaAsset.Status.PENDING);
        asset.setUploadedBy(uploadedBy);
        asset.setProductId(productId);
        asset.setIsPrimary(placement.isPrimary());
        asset.setSortOrder(placement.sortOrder());
        asset = mediaAssetRepository.saveAndFlush(asset);

        // Same-tx transactional outbox insert -> MediaEventOutboxFlusher publishes to
        // media.events after commit (dedicated path, no dispatch trap).
        MediaProcessingEvent event = new MediaProcessingEvent(tenantId, asset.getId());
        mediaEventOutboxRepository.save(new MediaEventOutbox(tenantId, asset.getId(), serialize(event)));

        log.info("Accepted upload for product {}: PENDING asset {} quarantined at {} + outbox row (tenant {})",
                productId, asset.getId(), objectKey, tenantId);
        return new MediaAcceptDto(asset.getId(), asset.getStatus().name());
    }

    private String serialize(MediaProcessingEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize media processing event", e);
        }
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".bin";
        };
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
     * Create the FIRST {@code product_media} join row for a placement slot (24-04
     * worker CoW-on-success, D-04a): used when a product has no existing row at the
     * target slot, so the freshly-ACTIVE asset becomes the product's image with no
     * displaced asset to release. Called ONLY once the worker has flipped the asset
     * to {@code ACTIVE}, so a FAILED upload never creates a live link.
     */
    public void attachPlacement(UUID tenantId, UUID productId, UUID assetId, boolean primary, int sortOrder) {
        ProductMedia pm = new ProductMedia();
        pm.setTenantId(tenantId);
        pm.setProductId(productId);
        pm.setAssetId(assetId);
        pm.setPrimary(primary);
        pm.setSortOrder(sortOrder);
        productMediaRepository.save(pm);
        log.debug("Attached product_media {} -> asset {} (product {}, primary={}, sortOrder={})",
                pm.getId(), assetId, productId, primary, sortOrder);
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

    /**
     * The vendor review/rejection queue (IMG-03 vendor-visible half): every
     * {@code media_asset} that needs vendor attention — a {@code FAILED} upload
     * (rejection reason + re-upload) OR a {@code flagged} {@code ACTIVE} asset
     * (content-relevance review: Keep or Replace, D-04) — newest first. Tenant-scoped
     * by the RLS wall (the request thread pins the tenant GUC), so another tenant's
     * assets are invisible. Replace is NOT an action here — it is a re-upload through
     * the 24-03 accept endpoint ({@code POST /api/v1/products/{id}/image}).
     */
    @Transactional(readOnly = true)
    public List<MediaAssetDto> reviewQueue() {
        return mediaAssetRepository.findReviewQueue().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Keep (dismiss the content flag, D-04): clears {@code flagged} on a flagged
     * {@code ACTIVE} asset so it drops out of the review queue and stays the product's
     * live image ({@code status} is untouched — the vendor keeps the flagged image).
     * Tenant-scoped — a foreign {@code assetId} is invisible under RLS, so
     * {@code findById} is empty and the caller gets a 404 (no cross-tenant oracle,
     * T-24-20).
     */
    public MediaAssetDto dismissFlag(UUID assetId) {
        MediaAsset asset = mediaAssetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Media asset not found: " + assetId));
        asset.setFlagged(false);
        asset = mediaAssetRepository.saveAndFlush(asset);
        log.info("Dismissed content flag on asset {} (Keep) — stays {}", assetId, asset.getStatus());
        return toDto(asset);
    }

    /**
     * The product's media assets as DTOs in render order (IMG-04, 24-05): the
     * {@code is_primary} asset first, then the gallery by {@code sort_order}, each
     * carrying {@code status}/{@code flagged}/{@code failureReason} (+ resolved derivative
     * URLs for ACTIVE entries). Backs the product DTO {@code media} enrichment that
     * {@code ProductService} wires post-mapping. Tenant-scoped by the RLS wall. An
     * un-migrated product (no {@code product_media} rows) returns an empty list — the
     * caller still renders via the flat {@code imageUrl} (dual-read, D-03a).
     */
    @Transactional(readOnly = true)
    public List<MediaAssetDto> mediaForProduct(UUID productId) {
        return productMediaRepository.findAssetsForProduct(productId).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Map a {@link MediaAsset} to the vendor {@link MediaAssetDto}, resolving the
     * derivative + thumbnail URLs ONLY for an {@code ACTIVE} asset (a PENDING/FAILED
     * asset has no servable object — its quarantine bytes are gone or not yet produced).
     */
    private MediaAssetDto toDto(MediaAsset asset) {
        String url = null;
        String thumbnailUrl = null;
        if (asset.getStatus() == MediaAsset.Status.ACTIVE && asset.getObjectKey() != null) {
            url = storageService.urlForKey(asset.getObjectKey());
            thumbnailUrl = thumbnailUrlFor(asset.getObjectKey());
        }
        return MediaAssetDto.from(asset, url, thumbnailUrl);
    }

    /**
     * The thumbnail URL for a pipeline-produced derivative
     * ({@code <tenant>/media/<id>.webp} -&gt; {@code <tenant>/media/<id>_thumb.webp} — the
     * 24-04 worker convention). A backfilled ACTIVE asset (its {@code object_key} is the
     * original flat key, not a {@code .webp} derivative) has no separate thumbnail, so this
     * returns {@code null} and the caller falls back to the full {@code url}.
     */
    private String thumbnailUrlFor(String objectKey) {
        if (objectKey.endsWith(".webp")) {
            String base = objectKey.substring(0, objectKey.length() - ".webp".length());
            return storageService.urlForKey(base + "_thumb.webp");
        }
        return null;
    }
}
