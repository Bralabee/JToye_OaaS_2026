package uk.jtoye.core.media;

import org.junit.jupiter.api.Test;
import uk.jtoye.core.product.dto.ProductDto;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMG-04 data-contract unit proof: the pure {@link MediaAssetDto#from} mapping surfaces the
 * exact per-entry fields the 24-06 UI renders — {@code status} (PENDING -> processing,
 * ACTIVE -> derivative, FAILED -> reason), {@code flagged} (needs-review badge),
 * {@code failureReason} (rejection copy) — and the {@link ProductDto} carries the media list
 * ALONGSIDE the legacy flat {@code imageUrl}/{@code additionalImageUrls} (dual-read, D-03a).
 *
 * <p>Pure unit test (no Spring / DB): the mapping is DB-free by design (the service resolves
 * URLs and passes them in), so this runs under {@code :core-java:test}.
 */
class MediaAssetDtoMappingTest {

    private MediaAsset asset(MediaAsset.Status status, boolean flagged, String failureReason) {
        MediaAsset a = new MediaAsset();
        a.setId(UUID.randomUUID());
        a.setStatus(status);
        a.setFlagged(flagged);
        a.setFailureReason(failureReason);
        a.setWidth(1600);
        a.setHeight(1200);
        return a;
    }

    @Test
    void pendingAssetMapsProcessingStatus() {
        MediaAssetDto dto = MediaAssetDto.from(asset(MediaAsset.Status.PENDING, false, null), null, null);
        assertThat(dto.status()).isEqualTo(MediaAssetStatus.PENDING);
        assertThat(dto.flagged()).isFalse();
        assertThat(dto.failureReason()).isNull();
        assertThat(dto.url()).as("no servable derivative while PENDING").isNull();
    }

    @Test
    void failedAssetCarriesFailureReason() {
        MediaAssetDto dto = MediaAssetDto.from(
                asset(MediaAsset.Status.FAILED, false, "could not decode image"), null, null);
        assertThat(dto.status()).isEqualTo(MediaAssetStatus.FAILED);
        assertThat(dto.failureReason()).isEqualTo("could not decode image");
    }

    @Test
    void flaggedActiveAssetMapsFlaggedTrueWithDerivativeUrls() {
        MediaAssetDto dto = MediaAssetDto.from(
                asset(MediaAsset.Status.ACTIVE, true, null),
                "http://minio/jtoye-images/t/media/x.webp",
                "http://minio/jtoye-images/t/media/x_thumb.webp");
        assertThat(dto.status()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(dto.flagged()).as("flagged-ACTIVE surfaces the needs-review bit").isTrue();
        assertThat(dto.url()).isEqualTo("http://minio/jtoye-images/t/media/x.webp");
        assertThat(dto.thumbnailUrl()).isEqualTo("http://minio/jtoye-images/t/media/x_thumb.webp");
        assertThat(dto.width()).isEqualTo(1600);
        assertThat(dto.height()).isEqualTo(1200);
    }

    @Test
    void cleanActiveAssetMapsActiveUnflagged() {
        MediaAssetDto dto = MediaAssetDto.from(
                asset(MediaAsset.Status.ACTIVE, false, null),
                "http://minio/.../x.webp", "http://minio/.../x_thumb.webp");
        assertThat(dto.status()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(dto.flagged()).isFalse();
    }

    @Test
    void productDtoCarriesMediaAndRetainsFlatDualReadFields() {
        // IMG-04 contract: the product DTO exposes the per-entry media list AND keeps the
        // legacy flat imageUrl/imageUrls during the dual-read window (D-03a — no removal this phase).
        MediaAssetDto primary = MediaAssetDto.from(asset(MediaAsset.Status.ACTIVE, false, null),
                "http://minio/.../p.webp", "http://minio/.../p_thumb.webp");
        ProductDto product = new ProductDto();
        product.setImageUrl("http://minio/.../flat.jpg");
        product.setAdditionalImageUrls(List.of("http://minio/.../g1.jpg"));
        product.setMedia(List.of(primary));

        assertThat(product.getMedia()).singleElement()
                .satisfies(m -> assertThat(m.status()).isEqualTo(MediaAssetStatus.ACTIVE));
        assertThat(product.getImageUrl()).as("flat imageUrl retained (dual-read)")
                .isEqualTo("http://minio/.../flat.jpg");
        assertThat(product.getAdditionalImageUrls()).as("flat gallery retained (dual-read)")
                .containsExactly("http://minio/.../g1.jpg");
    }

    @Test
    void flatOnlyProductHasNoMediaButKeepsUsableFlatImage() {
        // A not-yet-migrated product (only a flat image_url, no product_media rows) has an
        // empty media list; the flat imageUrl still renders (the UI treats it as ACTIVE-equivalent).
        ProductDto product = new ProductDto();
        product.setImageUrl("http://minio/.../legacy.jpg");
        product.setMedia(List.of());

        assertThat(product.getMedia()).isEmpty();
        assertThat(product.getImageUrl()).isEqualTo("http://minio/.../legacy.jpg");
    }

    // --- WR-05: thumbnail key is derived ONLY for pipeline-convention keys ------------------

    @Test
    void thumbnailKeyForPipelineDerivativeReturnsThumbSibling() {
        // A 24-04 worker derivative (<tenant>/media/<id>.webp) has a real _thumb.webp sibling.
        assertThat(MediaAssetService.thumbnailKeyFor(
                "00000000-0000-0000-0000-000000000001/media/abc123.webp"))
                .isEqualTo("00000000-0000-0000-0000-000000000001/media/abc123_thumb.webp");
    }

    @Test
    void thumbnailKeyForBackfilledWebpOutsideMediaPathReturnsNull() {
        // WR-05: a V53-backfilled ACTIVE asset whose key is a .webp ORIGINAL under the
        // products path (not the pipeline /media/ path) has NO thumbnail sibling — advertising
        // one returns a URL that 404s. thumbnailKeyFor must return null so the caller falls
        // back to the full url.
        assertThat(MediaAssetService.thumbnailKeyFor(
                "00000000-0000-0000-0000-000000000001/products/pid-9/original.webp"))
                .as("a backfilled .webp original outside /media/ has no thumbnail sibling")
                .isNull();
    }

    @Test
    void thumbnailKeyForNonWebpReturnsNull() {
        assertThat(MediaAssetService.thumbnailKeyFor(
                "00000000-0000-0000-0000-000000000001/media/abc123.jpg")).isNull();
        assertThat(MediaAssetService.thumbnailKeyFor(null)).isNull();
    }
}
