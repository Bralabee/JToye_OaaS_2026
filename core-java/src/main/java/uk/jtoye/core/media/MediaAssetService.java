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

import java.time.OffsetDateTime;
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
    private final MediaProperties mediaProperties;
    private final ObjectMapper objectMapper;

    public MediaAssetService(MediaAssetRepository mediaAssetRepository,
                             ProductMediaRepository productMediaRepository,
                             MediaEventOutboxRepository mediaEventOutboxRepository,
                             ProductRepository productRepository,
                             ShopAccessService shopAccessService,
                             StorageService storageService,
                             MediaProperties mediaProperties,
                             ObjectMapper objectMapper) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.productMediaRepository = productMediaRepository;
        this.mediaEventOutboxRepository = mediaEventOutboxRepository;
        this.productRepository = productRepository;
        this.shopAccessService = shopAccessService;
        this.storageService = storageService;
        this.mediaProperties = mediaProperties;
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

        // Dedup on accept: (tenant_id, sha256) is unique, so at most one asset can exist for
        // these bytes. A dedup hit must still PLACE the reused asset against THIS product — the
        // worker's placeOnActive path is NEVER reached on a dedup hit, so returning here without
        // attaching leaves the target product imageless (CR-01 regression by omission).
        Optional<MediaAsset> existing = findDedup(tenantId, sha256);
        if (existing.isPresent()) {
            MediaAsset a = existing.get();
            switch (a.getStatus()) {
                case ACTIVE -> {
                    // Genuine CoW dedup share: attach/repoint this product's slot to the shared
                    // ACTIVE asset directly (two products with identical bytes each keep a
                    // product_media row -> ref-count grows).
                    placeAsset(tenantId, productId, a.getId(), placement.isPrimary(), placement.sortOrder());
                    log.info("Dedup (ACTIVE) on accept: product {} shares asset {}", productId, a.getId());
                }
                case PENDING -> {
                    // A worker is already in flight for these bytes (the asset's placement intent
                    // points at the FIRST product). Share the in-flight asset with THIS product
                    // now — its slot resolves to the derivative when the shared worker flips
                    // E->ACTIVE. The asset's OWN placement intent is left untouched (the worker
                    // still places the first product; different products never collide, a
                    // same-product re-slot is idempotent).
                    placeAsset(tenantId, productId, a.getId(), placement.isPrimary(), placement.sortOrder());
                    log.info("Dedup (PENDING) on accept: product {} shares in-flight asset {}",
                            productId, a.getId());
                }
                case FAILED -> {
                    // WR-01: a FAILED row must NOT permanently poison these bytes tenant-wide. The
                    // raw bytes are available on THIS accept, so reset the same (tenant, sha256) row
                    // to PENDING, re-quarantine + re-enqueue, and let the worker re-run.
                    return reprocessFailed(a, productId, rawBytes, sha256, uploadedBy, placement, tenantId);
                }
            }
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
        // 27-01 / D-03: claim the retained raw bytes for a declared horizon. Until this asset
        // reaches a terminal state, these bytes are the vendor's ONLY copy — the reaper can no
        // longer delete them, and MediaQuarantineRetentionSweep will not reclaim them before
        // quarantine_expires_at. reclaimedAt stays null: nothing has been deleted yet.
        asset.setQuarantineExpiresAt(
                OffsetDateTime.now().plusNanos(mediaProperties.getQuarantineRetentionMs() * 1_000_000L));
        asset.setQuarantineReclaimedAt(null);
        asset = mediaAssetRepository.saveAndFlush(asset);

        // Same-tx transactional outbox insert -> MediaEventOutboxFlusher publishes to
        // media.events after commit (dedicated path, no dispatch trap).
        MediaProcessingEvent event = new MediaProcessingEvent(tenantId, asset.getId());
        mediaEventOutboxRepository.save(new MediaEventOutbox(tenantId, asset.getId(), serialize(event)));

        log.info("Accepted upload for product {}: PENDING asset {} quarantined at {} + outbox row (tenant {})",
                productId, asset.getId(), objectKey, tenantId);
        return new MediaAcceptDto(asset.getId(), asset.getStatus().name());
    }

    /**
     * WR-01 FAILED-reprocess: a FAILED dedup match no longer permanently poisons its
     * {@code (tenant_id, sha256)} slot (the worker deletes the quarantine object on failure, so
     * without this a re-upload of identical bytes hit the FAILED row forever with the raw gone).
     * The raw bytes ARE available on THIS accept, so the SAME asset row (the unique index requires
     * one per tenant+content) is reset to {@code PENDING} with this upload's placement intent, the
     * raw bytes are re-quarantined, and a fresh {@code media_event_outbox} event is enqueued in the
     * SAME tx — so the worker re-runs and attaches the asset on success, exactly like a fresh upload.
     */
    private MediaAcceptDto reprocessFailed(MediaAsset asset, UUID productId, byte[] rawBytes,
                                           String sha256, UUID uploadedBy, MediaPlacement placement,
                                           UUID tenantId) {
        String contentType = storageService.detectContentType(rawBytes);
        if (contentType == null) {
            contentType = "application/octet-stream";
        }
        String objectKey = tenantId + "/quarantine/" + sha256 + extensionFor(contentType);
        storageService.putBytes(objectKey, rawBytes, contentType);   // re-quarantine the RAW bytes

        asset.setObjectKey(objectKey);
        asset.setContentType(contentType);
        asset.setBytes((long) rawBytes.length);
        asset.setWidth(null);
        asset.setHeight(null);
        asset.setStatus(MediaAsset.Status.PENDING);
        asset.setFailureReason(null);
        asset.setFlagged(false);
        asset.setUploadedBy(uploadedBy);
        asset.setProductId(productId);
        asset.setIsPrimary(placement.isPrimary());
        asset.setSortOrder(placement.sortOrder());
        // 27-01 / D-03: the bytes are RE-claimed on this path — a previously reclaimed asset gets a
        // fresh horizon and its sentinel cleared, because a new quarantine object now exists.
        asset.setQuarantineExpiresAt(
                OffsetDateTime.now().plusNanos(mediaProperties.getQuarantineRetentionMs() * 1_000_000L));
        asset.setQuarantineReclaimedAt(null);
        asset = mediaAssetRepository.saveAndFlush(asset);

        MediaProcessingEvent event = new MediaProcessingEvent(tenantId, asset.getId());
        mediaEventOutboxRepository.save(new MediaEventOutbox(tenantId, asset.getId(), serialize(event)));

        log.info("Dedup (FAILED) reprocess on accept: asset {} reset to PENDING + re-quarantined at {} "
                + "+ outbox row (product {}, tenant {})", asset.getId(), objectKey, productId, tenantId);
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
     * Attach-or-repoint a product's placement slot to {@code assetId} — the shared CoW placement
     * logic behind BOTH the worker's on-success placement (D-04a) AND the accept-time dedup share
     * (CR-01). If the product has no row at the target slot (the primary slot, or a gallery slot
     * matched by {@code sortOrder}), create it ({@link #attachPlacement}); otherwise repoint the
     * existing slot to {@code assetId} and release the displaced asset (physical delete at
     * ref-count 0). Idempotent when the slot already points at {@code assetId} (redelivery / dedup).
     */
    public void placeAsset(UUID tenantId, UUID productId, UUID assetId, boolean primary, int sortOrder) {
        Optional<ProductMedia> slot = primary
                ? productMediaRepository.findByProductIdAndPrimaryTrue(productId)
                : productMediaRepository.findFirstByProductIdAndPrimaryFalseAndSortOrder(productId, sortOrder);
        if (slot.isEmpty()) {
            attachPlacement(tenantId, productId, assetId, primary, sortOrder);
            return;
        }
        ProductMedia row = slot.get();
        UUID displaced = row.getAssetId();
        if (displaced.equals(assetId)) {
            return;   // already points at this asset (idempotent redelivery / dedup) — nothing to do.
        }
        repoint(row.getId(), assetId);
        releaseAsset(displaced);
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
     *
     * <p>WR-03: Keep MUTATES asset state, so it enforces the SAME VSA-02 shop-scoped write
     * gate ({@code SHOP_MANAGER}) as upload-accept and image-delete — otherwise a SHOP_MANAGER
     * of shop A could clear the content flag on shop B's image. The owning shop is resolved
     * from the asset's placement {@code product_id} (or, for a placed asset whose intent column
     * was never set, the {@code product_media -> product} join); a shared/legacy asset with no
     * resolvable shop falls back to the null-shop GROUP_ADMIN-only rule that {@link ShopAccessService#require}
     * already applies. The gate runs AFTER the RLS {@code findById} so a cross-tenant asset is
     * still a 404 (never a 403 oracle).
     */
    public MediaAssetDto dismissFlag(UUID assetId) {
        MediaAsset asset = mediaAssetRepository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Media asset not found: " + assetId));
        shopAccessService.require(resolveOwningShopId(asset), ShopRole.SHOP_MANAGER);
        asset.setFlagged(false);
        asset = mediaAssetRepository.saveAndFlush(asset);
        log.info("Dismissed content flag on asset {} (Keep) — stays {}", assetId, asset.getStatus());
        return toDto(asset);
    }

    /**
     * The shop that owns {@code asset}, for the VSA-02 shop-scoped write gate (WR-03), or
     * {@code null} for a shared/legacy asset with no resolvable product (the caller then
     * enforces the null-shop GROUP_ADMIN-only rule). Resolution order: the asset's placement
     * {@code product_id} intent first, then the {@code product_media -> product} join.
     */
    private UUID resolveOwningShopId(MediaAsset asset) {
        UUID productId = asset.getProductId();
        if (productId == null) {
            productId = productMediaRepository.findByAssetId(asset.getId()).stream()
                    .map(ProductMedia::getProductId)
                    .findFirst()
                    .orElse(null);
        }
        if (productId == null) {
            return null;   // shared/legacy asset -> require() applies the null-shop GROUP_ADMIN rule
        }
        return productRepository.findById(productId)
                .map(Product::getShopId)
                .orElse(null);
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
     * original flat key, not a pipeline {@code .webp} derivative) has no separate thumbnail,
     * so this returns {@code null} and the caller falls back to the full {@code url}.
     */
    private String thumbnailUrlFor(String objectKey) {
        String thumbKey = thumbnailKeyFor(objectKey);
        return thumbKey == null ? null : storageService.urlForKey(thumbKey);
    }

    /**
     * The sibling thumbnail object key for a pipeline-produced derivative, else {@code null}
     * (WR-05). Only a key under the 24-04 worker convention
     * ({@code <tenant>/media/<id>.webp}) has a paired {@code <id>_thumb.webp} sibling — a
     * backfilled ACTIVE asset (V53 wraps existing object keys as-is, e.g.
     * {@code <tenant>/products/<pid>/<uuid>.webp}) does NOT, even when its own key happens to
     * end in {@code .webp}. Advertising a {@code _thumb.webp} for such a key returns a URL that
     * 404s, so this returns {@code null} and the DTO mapping falls back to the full url.
     * Package-private + static so the convention is unit-tested directly.
     */
    static String thumbnailKeyFor(String objectKey) {
        // Require the pipeline's /media/ path segment — a bare .webp SUFFIX is not enough,
        // because a V53-backfilled original can be a .webp outside /media/ with no sibling.
        if (objectKey != null && objectKey.contains("/media/") && objectKey.endsWith(".webp")) {
            return objectKey.substring(0, objectKey.length() - ".webp".length()) + "_thumb.webp";
        }
        return null;
    }
}
