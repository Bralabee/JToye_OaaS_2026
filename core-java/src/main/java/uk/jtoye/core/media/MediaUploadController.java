package uk.jtoye.core.media;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import uk.jtoye.core.common.idempotency.IdempotencyOutcome;
import uk.jtoye.core.common.idempotency.IdempotencyService;
import uk.jtoye.core.media.exception.PayloadTooLargeException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * The request-thread half of the safe async image pipeline (IMG-02 accept side).
 *
 * <p>This controller lives in the un-prefixed {@code uk.jtoye.core.media} package, so
 * it HARD-mounts the full {@code /api/v1/products} path (the {@code media} package is
 * NOT in {@code WebConfig.API_V1_PACKAGES}; same pattern as {@code RefundController} /
 * {@code WebhookSubscriptionController}). It is the SOLE owner of
 * {@code POST /api/v1/products/{id}/image} — the old synchronous
 * {@code ProductController.uploadImage} handler was retired in this same change, or the
 * ApplicationContext would fail to refresh with an "Ambiguous mapping" IllegalStateException.
 *
 * <p>The accept: (1) refuses an oversize body via the declared {@code Content-Length}
 * BEFORE buffering any {@code MultipartFile} byte (T-24-09 — a 2GB in-memory upload is
 * itself a DoS); (2) carries the uniform {@code Idempotency-Key} contract (D-06 / #204) so
 * a replay never duplicates an asset; (3) returns {@code 202 Accepted} with the asset id
 * and hands normalization to the async worker. Typed errors are RFC 7807 (413/409/422).
 */
@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Product Media", description = "Safe async product image upload (quarantine + normalize pipeline)")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class MediaUploadController {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadController.class);

    private final MediaAssetService mediaAssetService;
    private final IdempotencyService idempotencyService;
    private final MediaProperties mediaProperties;

    public MediaUploadController(MediaAssetService mediaAssetService,
                                 IdempotencyService idempotencyService,
                                 MediaProperties mediaProperties) {
        this.mediaAssetService = mediaAssetService;
        this.idempotencyService = idempotencyService;
        this.mediaProperties = mediaProperties;
    }

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // issue #206: catalog write scope, mirrors ProductController
    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a product image (async accept)",
            description = "Quarantines the raw upload and queues it for magic-byte sniffing, decode-verify, "
                    + "EXIF strip and WebP normalization. Returns 202 with the media_asset id; the client "
                    + "polls the product to observe PENDING -> ACTIVE/FAILED. Carries the uniform "
                    + "Idempotency-Key contract; oversize/replay errors are RFC 7807 typed.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Accepted — upload quarantined, normalization queued"),
            @ApiResponse(responseCode = "400", description = "Missing Idempotency-Key or malformed request"),
            @ApiResponse(responseCode = "403", description = "Missing catalog:write scope or shop-role access"),
            @ApiResponse(responseCode = "404", description = "Product not found"),
            @ApiResponse(responseCode = "409", description = "Idempotency-Key request already in progress"),
            @ApiResponse(responseCode = "413", description = "Upload exceeds the maximum permitted size"),
            @ApiResponse(responseCode = "422", description = "Idempotency-Key reused with a different body")
    })
    public ResponseEntity<MediaAcceptDto> accept(
            @Parameter(description = "Product ID") @PathVariable UUID id,
            @RequestHeader(value = "Content-Length", required = false) Long contentLength,
            @RequestHeader("Idempotency-Key") String idemKey,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "is_primary", required = false, defaultValue = "true") boolean isPrimary,
            @RequestParam(value = "sort_order", required = false, defaultValue = "0") int sortOrder,
            @AuthenticationPrincipal Jwt principal) {

        // Reject-early (T-24-09): refuse an oversize body BEFORE touching a single file byte.
        long maxBytes = mediaProperties.getMaxUploadBytes();
        if (contentLength != null && contentLength > maxBytes) {
            throw new PayloadTooLargeException(
                    "Upload exceeds the " + maxBytes + "-byte limit (declared Content-Length " + contentLength + ")");
        }

        // Size gate passed — now read the bounded bytes (also the second gate: Spring/Tomcat
        // multipart max-file-size aborts a genuinely oversize body with MaxUploadSizeExceededException).
        byte[] raw = readBytes(file);
        String sha256 = sha256Hex(raw);
        UUID uploadedBy = subjectAsUuid(principal);
        MediaAssetService.MediaPlacement placement = new MediaAssetService.MediaPlacement(isPrimary, sortOrder);

        // The idempotency request fingerprint incorporates the file content (sha256) + placement,
        // so same-key/different-file is a 422 body-mismatch, not a silent replay.
        MediaUploadRequest request = new MediaUploadRequest(id, sha256, isPrimary, sortOrder);
        IdempotencyOutcome<MediaAcceptDto> outcome = idempotencyService.execute(
                "media.upload", idemKey, request, MediaAcceptDto.class,
                () -> mediaAssetService.acceptQuarantineAndQueue(id, raw, sha256, uploadedBy, placement));

        // 202 Accepted on both the fresh accept and the replay (the stored body is echoed).
        // IdempotencyService stamps 201 internally; the controller owns the async 202 status.
        log.debug("Media accept for product {} -> asset {} ({})", id, outcome.value().assetId(), outcome.value().status());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(outcome.value());
    }

    /** The idempotency request fingerprint — hashed by IdempotencyService to detect body reuse. */
    private record MediaUploadRequest(UUID productId, String sha256, boolean isPrimary, int sortOrder) {
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read uploaded file");
        }
    }

    private static UUID subjectAsUuid(Jwt principal) {
        if (principal == null || principal.getSubject() == null) {
            return null;
        }
        try {
            return UUID.fromString(principal.getSubject());
        } catch (IllegalArgumentException e) {
            return null;   // non-UUID subject (e.g. a machine client) -> uploaded_by stays null
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
