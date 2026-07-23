package uk.jtoye.core.media;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 * <p>Two endpoints:
 * <ul>
 *   <li>{@code GET /review-queue} lists the assets that need vendor attention — FAILED
 *       (a vendor-visible rejection reason + re-upload) and flagged-ACTIVE (content-relevance
 *       review: Keep or Replace). Authenticated read surface, exactly mirroring the
 *       {@code ProductController} read endpoints (a scopeless/legacy vendor token still
 *       reads — see {@code ScopedCatalogAccessIntegrationTest}); tenant-isolated by RLS.</li>
 *   <li>{@code POST /{assetId}/keep} dismisses a content flag (Keep, D-04), gated on
 *       {@code SCOPE_catalog:write} like every {@code ProductController} mutation.</li>
 * </ul>
 * Replace is deliberately NOT an endpoint here — a replacement is a re-upload through the
 * 24-03 accept endpoint ({@code POST /api/v1/products/{id}/image}); on worker success it
 * mints a new asset and repoints, and a FAILED replacement never clobbers the live image (D-04a).
 */
@RestController
@RequestMapping("/api/v1/media")
@Tag(name = "Media Review", description = "Vendor review/rejection queue for processed image assets (IMG-03/IMG-04)")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class MediaController {

    private final MediaAssetService mediaAssetService;

    public MediaController(MediaAssetService mediaAssetService) {
        this.mediaAssetService = mediaAssetService;
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
}
