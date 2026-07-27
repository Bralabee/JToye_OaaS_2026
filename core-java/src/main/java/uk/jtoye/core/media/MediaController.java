package uk.jtoye.core.media;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.common.idempotency.IdempotencyOutcome;
import uk.jtoye.core.common.idempotency.IdempotencyService;

import java.util.List;
import java.util.UUID;

/**
 * The vendor-facing media review/rejection surface (IMG-03 vendor-visible half + the
 * IMG-04 data-contract entry point). Lives in the un-prefixed {@code uk.jtoye.core.media}
 * package, so it HARD-mounts the full {@code /api/v1/media} path (the {@code media}
 * package is NOT in {@code WebConfig.API_V1_PACKAGES}; same pattern as
 * {@code RefundController} / {@code WebhookSubscriptionController} /
 * {@code MediaUploadController} — no {@code WebConfig} edit).
 *
 * <p>Three endpoints:
 * <ul>
 *   <li>{@code GET /review-queue} lists the assets that need vendor attention — FAILED
 *       (a vendor-visible rejection reason + re-upload), flagged-ACTIVE (content-relevance
 *       review: Keep or Replace), and (27-01 / D-10) a PENDING upload that has visibly
 *       stalled. Authenticated read surface, exactly mirroring the
 *       {@code ProductController} read endpoints (a scopeless/legacy vendor token still
 *       reads — see {@code ScopedCatalogAccessIntegrationTest}); tenant-isolated by RLS.</li>
 *   <li>{@code POST /{assetId}/keep} dismisses a content flag (Keep, D-04), gated on
 *       {@code SCOPE_catalog:write} like every {@code ProductController} mutation.</li>
 *   <li>{@code POST /{assetId}/reprocess} re-drives a stalled/failed upload from its RETAINED
 *       quarantine bytes (27-01 / D-04) — same write gate, plus the uniform
 *       {@code Idempotency-Key} contract because it enqueues work.</li>
 * </ul>
 *
 * <p><b>Re-process is not Replace.</b> Re-process re-runs the pipeline over the SAME bytes the
 * vendor already uploaded, and is offered exactly while those bytes are retained
 * ({@code MediaAssetDto.redrivable}). Replace — different bytes — is still deliberately NOT an
 * endpoint here: it is a re-upload through the 24-03 accept endpoint
 * ({@code POST /api/v1/products/{id}/image}); on worker success that mints a NEW asset and
 * repoints, and a FAILED replacement never clobbers the live image (D-04a).
 */
@RestController
@RequestMapping("/api/v1/media")
@Tag(name = "Media Review", description = "Vendor review/rejection queue for processed image assets (IMG-03/IMG-04)")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class MediaController {

    private final MediaAssetService mediaAssetService;
    private final IdempotencyService idempotencyService;

    public MediaController(MediaAssetService mediaAssetService, IdempotencyService idempotencyService) {
        this.mediaAssetService = mediaAssetService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/review-queue")
    @Operation(summary = "List the media review/rejection queue",
            description = "Returns the tenant's media assets that need vendor attention: FAILED uploads "
                    + "(each carries a vendor-visible failureReason; the vendor re-uploads) and "
                    + "flagged-ACTIVE assets (content-relevance review — the vendor Keeps or Replaces). "
                    + "Clean ACTIVE and in-flight PENDING assets are excluded. Authenticated read surface "
                    + "(mirrors the ProductController read endpoints); tenant-isolated by RLS.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The review queue (possibly empty)"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT")
    })
    public List<MediaAssetDto> reviewQueue() {
        return mediaAssetService.reviewQueue();
    }

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // mirrors ProductController mutations (issue #206)
    @PostMapping("/{assetId}/keep")
    @Operation(summary = "Keep a flagged asset (dismiss the content flag)",
            description = "Dismisses the content-relevance flag on a flagged ACTIVE asset (Keep, D-04): "
                    + "the asset stays ACTIVE, flagged clears, and it drops out of the review queue. "
                    + "Replace is NOT this endpoint — a replacement is a re-upload through "
                    + "POST /api/v1/products/{id}/image. Tenant-scoped: a foreign assetId returns 404.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Flag dismissed; the updated asset is returned"),
            @ApiResponse(responseCode = "403", description = "Missing catalog:write scope"),
            @ApiResponse(responseCode = "404", description = "Asset not found in the caller's tenant")
    })
    public MediaAssetDto keep(
            @Parameter(description = "Media asset ID") @PathVariable UUID assetId) {
        return mediaAssetService.dismissFlag(assetId);
    }

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // mirrors keep/upload (issue #206)
    @PostMapping("/{assetId}/reprocess")
    @Operation(summary = "Re-process a stalled or failed upload from its retained bytes",
            description = "Re-runs the normalization pipeline over the RAW bytes the vendor already "
                    + "uploaded — offered while media_asset.redrivable is true (the quarantine object "
                    + "is still retained, within jtoye.media.quarantine-retention-ms). Returns 202 with "
                    + "the asset back in PENDING; the client polls for PENDING -> ACTIVE/FAILED. "
                    + "This is NOT Replace: different bytes are a re-upload through "
                    + "POST /api/v1/products/{id}/image. Carries the uniform Idempotency-Key contract, "
                    + "so a retried click never enqueues twice. Tenant-scoped: a foreign assetId is 404, "
                    + "never 403 (no cross-tenant oracle).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Accepted — the asset is back in PENDING and re-queued"),
            @ApiResponse(responseCode = "400", description = "Missing Idempotency-Key or malformed request"),
            @ApiResponse(responseCode = "403", description = "Missing catalog:write scope or shop-role access"),
            @ApiResponse(responseCode = "404", description = "Asset not found in the caller's tenant"),
            @ApiResponse(responseCode = "409", description = "Bytes no longer retained (media.quarantine_not_retained), "
                    + "the asset is already ACTIVE (media.already_active), the re-process budget is exhausted "
                    + "(media.redrive_budget_exhausted), or an Idempotency-Key request is still in progress"),
            @ApiResponse(responseCode = "422", description = "Idempotency-Key reused with a different body")
    })
    public ResponseEntity<MediaAcceptDto> reprocess(
            @Parameter(description = "Media asset ID") @PathVariable UUID assetId,
            @RequestHeader("Idempotency-Key") String idemKey) {

        IdempotencyOutcome<MediaAcceptDto> outcome = idempotencyService.execute(
                "media.reprocess", idemKey, new RedriveRequest(assetId), MediaAcceptDto.class,
                () -> mediaAssetService.redriveFromQuarantine(assetId));

        // 202 on both the fresh re-drive and the replay (the stored body is echoed).
        // IdempotencyService stamps 201 internally; the controller owns the async 202 status,
        // exactly as MediaUploadController.accept does.
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(outcome.value());
    }

    /** The idempotency request fingerprint — hashed by IdempotencyService to detect body reuse. */
    private record RedriveRequest(UUID assetId) {
    }
}
