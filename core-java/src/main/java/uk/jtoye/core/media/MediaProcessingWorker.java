package uk.jtoye.core.media;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.ai.ImageAnalysisResult;
import uk.jtoye.core.ai.ImageAnalysisService;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.media.exception.DecompressionBombException;
import uk.jtoye.core.media.exception.UnreadableImageException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.StorageService;

import java.util.Optional;
import java.util.UUID;

/**
 * The async worker half of the safe upload pipeline (IMG-02 worker side + IMG-03
 * gate strictness). A competing-consumer {@link RabbitListener} on the dedicated
 * {@code media.process} queue: each {@link MediaProcessingEvent} is handled by
 * exactly one replica, which normalizes the quarantined raw bytes into a validated
 * WebP derivative and drives the asset state machine {@code PENDING -> ACTIVE/FAILED}
 * (+ the flagged sub-state).
 *
 * <p><b>Tenant-GUC pin (the @Async-tenant landmine, T-24-17):</b> the worker runs
 * OFF the request thread, so {@link TenantContext} and the connection GUC are NOT
 * auto-populated — RLS would hide the PENDING row and every query would silently see
 * zero rows. The worker therefore pins the tenant GUC FIRST, using the exact idiom in
 * {@code OrderStateChangeListener} (set the {@link TenantContext} ThreadLocal AND
 * {@code set_config('app.current_tenant_id', ?, true)} on the session connection)
 * BEFORE any read/write of {@code media_asset}/{@code product_media}, and clears the
 * ThreadLocal in a {@code finally}.
 *
 * <p><b>Idempotent redelivery:</b> the DB is the source of truth (no {@code processed_*}
 * table). The worker re-reads the asset by id and SKIPs if it is no longer
 * {@code PENDING} — a redelivered event on an already-ACTIVE/FAILED asset is a no-op.
 *
 * <p><b>Pipeline stages (RESEARCH diagram a-j):</b> (a) read the quarantine bytes;
 * (b-e) {@link MediaNormalizer#normalize} sniffs the magic bytes, header-guards the
 * decompression bomb, decode-verifies, strips EXIF via re-decode and resizes;
 * (f) encodes a WebP derivative + thumbnail; (g) stores ONLY the derivative (Content-Type
 * from the PRODUCED type, never the client header); (h) flips to {@code ACTIVE};
 * (i) deletes the raw quarantine object; (j) CoW-on-success repoints/attaches the
 * {@code product_media} slot — done ONLY on success so a FAILED replacement never
 * clobbers the product's existing live image (D-04a). Any decode/bomb/allowlist/encode
 * failure is a D3 hard veto: {@code FAILED} + a vendor-visible {@code failure_reason},
 * never a served derivative and never a repoint.
 *
 * <p><b>Advisory vision (IMG-03 stage 6):</b> gated OFF by default behind
 * {@code jtoye.media.vision.enabled} (the Ollama provider is unreliable). When on, a
 * below-threshold content-relevance confidence FLAGS the ACTIVE asset for the vendor
 * review queue — never a reject (SPEC D3 "don't wrongly block legitimate rare dishes").
 */
@Component
public class MediaProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(MediaProcessingWorker.class);

    private static final int FAILURE_REASON_MAX = 500;

    private final MediaAssetRepository mediaAssetRepository;
    private final MediaAssetService mediaAssetService;
    private final MediaNormalizer mediaNormalizer;
    private final StorageService storageService;
    private final ImageAnalysisService imageAnalysisService;
    private final MediaProperties mediaProperties;
    private final EntityManager entityManager;

    public MediaProcessingWorker(MediaAssetRepository mediaAssetRepository,
                                 MediaAssetService mediaAssetService,
                                 MediaNormalizer mediaNormalizer,
                                 StorageService storageService,
                                 ImageAnalysisService imageAnalysisService,
                                 MediaProperties mediaProperties,
                                 EntityManager entityManager) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.mediaAssetService = mediaAssetService;
        this.mediaNormalizer = mediaNormalizer;
        this.storageService = storageService;
        this.imageAnalysisService = imageAnalysisService;
        this.mediaProperties = mediaProperties;
        this.entityManager = entityManager;
    }

    @RabbitListener(queues = RabbitMQConfig.MEDIA_EVENTS_QUEUE)
    @Transactional
    public void onMediaEvent(MediaProcessingEvent event) {
        // Tenant context FIRST — ThreadLocal AND DB session GUC. The worker runs off the
        // request thread, so without this pin RLS hides the PENDING row (T-24-17); the
        // set_config mirrors OrderStateChangeListener's hard-pin idiom.
        TenantContext.set(event.tenantId());
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, event.tenantId().toString());
                stmt.execute();
            }
        });

        try {
            MediaAsset asset = mediaAssetRepository.findById(event.assetId()).orElse(null);
            if (asset == null) {
                log.warn("event=media_process_skipped reason=asset_not_visible asset={} tenant={}",
                        event.assetId(), event.tenantId());
                return;
            }
            // DB is the source of truth — a redelivered event on a non-PENDING asset is a no-op.
            if (asset.getStatus() != MediaAsset.Status.PENDING) {
                log.info("event=media_process_skipped reason=not_pending asset={} status={}",
                        asset.getId(), asset.getStatus());
                return;
            }
            process(asset);
        } finally {
            TenantContext.clear();
        }
    }

    private void process(MediaAsset asset) {
        String quarantineKey = asset.getObjectKey();

        // (a) read the quarantined RAW bytes.
        byte[] raw;
        try {
            raw = storageService.getBytes(quarantineKey);
        } catch (RuntimeException e) {
            fail(asset, quarantineKey, "Could not read the quarantined upload");
            return;
        }

        // (b-f) sniff + bomb-guard + decode-verify + EXIF-strip + resize + WebP encode.
        MediaNormalizer.NormalizedImage normalized;
        try {
            normalized = mediaNormalizer.normalize(raw);
        } catch (DecompressionBombException | UnreadableImageException e) {
            // D3 hard veto (IMG-03): content-type spoof / bomb / undecodable / disallowed type.
            fail(asset, quarantineKey, e.getMessage());
            return;
        } catch (RuntimeException e) {
            // A compress/normalize failure is also a hard veto — never store a raw or partial artifact.
            fail(asset, quarantineKey, "Image could not be processed: " + e.getMessage());
            return;
        }

        // (g) store ONLY the validated derivative + thumbnail; Content-Type from the PRODUCED
        // type (image/webp), never the client header (closes the content-type-spoof vector).
        String derivativeKey = asset.getTenantId() + "/media/" + asset.getId() + ".webp";
        String thumbnailKey = asset.getTenantId() + "/media/" + asset.getId() + "_thumb.webp";
        storageService.putBytes(derivativeKey, normalized.derivativeBytes(), "image/webp");
        storageService.putBytes(thumbnailKey, normalized.thumbnailBytes(), "image/webp");

        // (h) drive PENDING -> ACTIVE with the produced metadata.
        asset.setObjectKey(derivativeKey);
        asset.setContentType("image/webp");
        asset.setWidth(normalized.width());
        asset.setHeight(normalized.height());
        asset.setBytes((long) normalized.derivativeBytes().length);
        asset.setStatus(MediaAsset.Status.ACTIVE);

        // Stage 6 (IMG-03 advisory): flag-not-block on low content-relevance.
        applyAdvisoryVision(asset, normalized.derivativeBytes());

        // Persist the ACTIVE (+ maybe flagged) row BEFORE the CoW repoint's @Modifying
        // context clear, or the dirty ACTIVE update would be discarded.
        mediaAssetRepository.saveAndFlush(asset);

        // (j) CoW-on-success (D-04a): place the freshly-ACTIVE asset. Runs ONLY here, so a
        // FAILED replacement (which returns above) never repoints — the product keeps its
        // existing live image.
        placeOnActive(asset);

        // (i) the raw quarantine object is no longer needed — only the derivative is served.
        storageService.deleteByKey(quarantineKey);

        log.info("event=media_process_active asset={} derivative={} size={}x{} bytes={} flagged={}",
                asset.getId(), derivativeKey, normalized.width(), normalized.height(),
                normalized.derivativeBytes().length, asset.isFlagged());
    }

    /**
     * Copy-on-write placement on success (D-04a). Uses the pending-placement intent the
     * accept captured ({@code product_id}/{@code is_primary}/{@code sort_order}) to either
     * create the slot's first {@code product_media} row or repoint the existing slot to the
     * new asset — releasing the displaced asset (physical delete at ref-count 0). Because it
     * is invoked ONLY once the asset is ACTIVE, a FAILED upload never reaches here.
     *
     * <p>Delegates to the shared {@link MediaAssetService#placeAsset} attach-or-repoint logic
     * (the SAME logic the accept-time dedup share reuses — CR-01), so the worker and the accept
     * path can never drift on how a placement slot is created vs repointed.
     */
    private void placeOnActive(MediaAsset asset) {
        UUID productId = asset.getProductId();
        if (productId == null) {
            return;   // no placement intent (e.g. a re-processed/backfilled asset) — nothing to place.
        }
        boolean primary = Boolean.TRUE.equals(asset.getIsPrimary());
        int sortOrder = asset.getSortOrder() == null ? 0 : asset.getSortOrder();
        mediaAssetService.placeAsset(asset.getTenantId(), productId, asset.getId(), primary, sortOrder);
    }

    /**
     * Advisory content-relevance gate (IMG-03 stage 6). No-op unless the advisory flag is
     * ON ({@code jtoye.media.vision.enabled}) AND the vision provider is available
     * ({@link ImageAnalysisService#isEnabled()}). A below-threshold confidence FLAGS the
     * ACTIVE asset for the review queue — never a reject (SPEC D3). An unreliable/absent
     * provider must never fail or block an upload, so any error leaves the asset ACTIVE and
     * unflagged.
     */
    private void applyAdvisoryVision(MediaAsset asset, byte[] derivativeBytes) {
        if (!mediaProperties.getVision().isEnabled() || !imageAnalysisService.isEnabled()) {
            return;   // advisory flag OFF (default) — vision never touches the asset.
        }
        try {
            Optional<ImageAnalysisResult> result = imageAnalysisService.analyze(derivativeBytes, "image/webp");
            double minConfidence = mediaProperties.getVision().getMinConfidence();
            boolean belowThreshold = result.isEmpty()
                    || result.get().getConfidence() == null
                    || result.get().getConfidence() < minConfidence;
            if (belowThreshold) {
                asset.setFlagged(true);   // ACTIVE + flagged -> vendor review queue (never a reject).
                log.info("event=media_flagged_for_review asset={} minConfidence={}", asset.getId(), minConfidence);
            }
        } catch (RuntimeException e) {
            log.warn("event=media_vision_stage_failed asset={} — leaving ACTIVE unflagged: {}",
                    asset.getId(), e.getMessage());
        }
    }

    /**
     * Terminal FAILED transition (IMG-03 hard veto): a vendor-visible {@code failure_reason},
     * no derivative stored, no repoint (the product keeps its existing ACTIVE asset — D-04a),
     * and the raw quarantine object deleted (this phase is re-upload-only; the raw is never
     * re-processed, so it must not linger).
     */
    private void fail(MediaAsset asset, String quarantineKey, String reason) {
        asset.setStatus(MediaAsset.Status.FAILED);
        asset.setFailureReason(truncate(reason));
        mediaAssetRepository.saveAndFlush(asset);
        storageService.deleteByKey(quarantineKey);
        log.info("event=media_process_failed asset={} reason={}", asset.getId(), reason);
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return "Image processing failed";
        }
        return reason.length() <= FAILURE_REASON_MAX ? reason : reason.substring(0, FAILURE_REASON_MAX);
    }
}
