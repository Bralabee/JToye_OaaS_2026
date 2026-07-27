package uk.jtoye.core.media;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import uk.jtoye.core.product.dto.ProductDto;

import java.time.OffsetDateTime;
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

    /**
     * A cutoff far in the past, so no fixture is incidentally {@code delayed} — the delayed
     * boundary is exercised deliberately in its own two tests below, never as a side effect.
     */
    private static final OffsetDateTime NO_DELAY_CUTOFF = OffsetDateTime.now().minusYears(1);

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

    /** {@code createdAt} is {@code @CreationTimestamp} with no setter — set it directly for the fixture. */
    private MediaAsset createdAt(MediaAsset a, OffsetDateTime when) {
        ReflectionTestUtils.setField(a, "createdAt", when);
        return a;
    }

    @Test
    void pendingAssetMapsProcessingStatus() {
        MediaAssetDto dto = MediaAssetDto.from(
                asset(MediaAsset.Status.PENDING, false, null), null, null, NO_DELAY_CUTOFF);
        assertThat(dto.status()).isEqualTo(MediaAssetStatus.PENDING);
        assertThat(dto.flagged()).isFalse();
        assertThat(dto.failureReason()).isNull();
        assertThat(dto.url()).as("no servable derivative while PENDING").isNull();
    }

    @Test
    void failedAssetCarriesFailureReason() {
        MediaAssetDto dto = MediaAssetDto.from(
                asset(MediaAsset.Status.FAILED, false, "could not decode image"), null, null, NO_DELAY_CUTOFF);
        assertThat(dto.status()).isEqualTo(MediaAssetStatus.FAILED);
        assertThat(dto.failureReason()).isEqualTo("could not decode image");
    }

    // --- 27-01 / D-10: the two DERIVED bits, at their exact boundaries ----------------------

    @Test
    void redrivableIsTrueOnlyWhileTheQuarantineBytesAreStillRetained() {
        // Claimed and not yet reclaimed -> the bytes are on disk, Re-process can work.
        MediaAsset retained = asset(MediaAsset.Status.FAILED, false, "dispatch stalled");
        retained.setQuarantineExpiresAt(OffsetDateTime.now().plusHours(72));
        assertThat(MediaAssetDto.from(retained, null, null, NO_DELAY_CUTOFF).redrivable())
                .as("retained bytes are re-drivable").isTrue();

        // Reclaimed (swept, or discarded by a worker validation veto) -> nothing left to re-process.
        MediaAsset reclaimed = asset(MediaAsset.Status.FAILED, false, "not an image");
        reclaimed.setQuarantineExpiresAt(OffsetDateTime.now().plusHours(72));
        reclaimed.setQuarantineReclaimedAt(OffsetDateTime.now());
        assertThat(MediaAssetDto.from(reclaimed, null, null, NO_DELAY_CUTOFF).redrivable())
                .as("the sentinel is the negation of redrivable").isFalse();

        // Never claimed (every pre-V60 row, and every V53-backfilled ACTIVE asset).
        assertThat(MediaAssetDto.from(asset(MediaAsset.Status.FAILED, false, "old"), null, null, NO_DELAY_CUTOFF)
                .redrivable()).as("a never-claimed asset has no retained bytes").isFalse();
    }

    @Test
    void delayedIsTrueOnlyForAPendingAssetOlderThanTheCutoff() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(15);

        MediaAsset stalled = createdAt(asset(MediaAsset.Status.PENDING, false, null), cutoff.minusMinutes(1));
        assertThat(MediaAssetDto.from(stalled, null, null, cutoff).delayed())
                .as("a PENDING asset older than the cutoff has visibly stalled").isTrue();

        MediaAsset fresh = createdAt(asset(MediaAsset.Status.PENDING, false, null), cutoff.plusMinutes(1));
        assertThat(MediaAssetDto.from(fresh, null, null, cutoff).delayed())
                .as("a PENDING asset inside the grace is still legitimately in flight").isFalse();

        // Status is load-bearing too: an OLD failure is not "taking longer than usual".
        MediaAsset oldFailure = createdAt(asset(MediaAsset.Status.FAILED, false, "veto"), cutoff.minusHours(4));
        assertThat(MediaAssetDto.from(oldFailure, null, null, cutoff).delayed())
                .as("only PENDING can be delayed — a FAILED asset is terminal, not slow").isFalse();
    }

    @Test
    void flaggedActiveAssetMapsFlaggedTrueWithDerivativeUrls() {
        MediaAssetDto dto = MediaAssetDto.from(
                asset(MediaAsset.Status.ACTIVE, true, null),
                "http://minio/jtoye-images/t/media/x.webp",
                "http://minio/jtoye-images/t/media/x_thumb.webp",
                NO_DELAY_CUTOFF);
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
                "http://minio/.../x.webp", "http://minio/.../x_thumb.webp", NO_DELAY_CUTOFF);
        assertThat(dto.status()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(dto.flagged()).isFalse();
    }

    @Test
    void productDtoCarriesMediaAndRetainsFlatDualReadFields() {
        // IMG-04 contract: the product DTO exposes the per-entry media list AND keeps the
        // legacy flat imageUrl/imageUrls during the dual-read window (D-03a — no removal this phase).
        MediaAssetDto primary = MediaAssetDto.from(asset(MediaAsset.Status.ACTIVE, false, null),
                "http://minio/.../p.webp", "http://minio/.../p_thumb.webp", NO_DELAY_CUTOFF);
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
