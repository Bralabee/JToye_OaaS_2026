package uk.jtoye.core.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import uk.jtoye.core.storage.StorageProperties;
import uk.jtoye.core.storage.StorageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 27-01 Task 1 / AC-1.5 — {@code deleteByKeyChecked} must be able to report failure where
 * {@code deleteByKey} structurally cannot.
 *
 * <p><strong>Why this is load-bearing (F-5).</strong> {@code deleteByKey} catches every exception
 * and only {@code log.warn}s, so no caller can learn whether the delete worked.
 * {@code MediaQuarantineRetentionSweep} needs exactly that fact: "these bytes are gone" is the
 * ONLY termination condition of its {@code quarantine_reclaimed_at} sentinel. If the sentinel were
 * stamped unconditionally, a transient S3 error would strand the object forever — and, because
 * {@code deleteByKey} swallows the exception, nothing would ever complain.
 */
@ExtendWith(MockitoExtension.class)
class MediaQuarantineRetentionSweepTest {

    @Mock private S3Client s3Client;

    private StorageService storageService;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.getS3().setBucket("jtoye-images");
        // This test only exercises deleteByKeyChecked, which never touches the normalizer;
        // the collaborator is wired only because issue #445 made it a constructor dependency.
        storageService = new StorageService(s3Client, properties, new MediaNormalizer(new MediaProperties()));
    }

    @Test
    @DisplayName("AC-1.5: a failing S3 delete returns false and still never throws")
    void checkedDeleteReportsFailure() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("transient").build());

        assertThat(storageService.deleteByKeyChecked("t/quarantine/abc"))
                .as("a swallowed exception must surface as false, or the sweep stamps its "
                        + "sentinel on an object that is still there")
                .isFalse();

        assertThatCode(() -> storageService.deleteByKey("t/quarantine/abc"))
                .as("deleteByKey keeps its never-throws contract for every existing caller")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-1.5: a successful S3 delete returns true and still never throws")
    void checkedDeleteReportsSuccess() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        assertThat(storageService.deleteByKeyChecked("t/quarantine/abc")).isTrue();

        assertThatCode(() -> storageService.deleteByKey("t/quarantine/abc"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-1.5: a blank key is 'already gone', not a failure — and hits no S3 call")
    void blankKeyIsTreatedAsAlreadyGone() {
        assertThat(storageService.deleteByKeyChecked(null)).isTrue();
        assertThat(storageService.deleteByKeyChecked("  ")).isTrue();
    }

    // ==================================================================
    // The sweep's own behaviour. These cover the SWEEP, never the PREDICATE:
    // guards 1 and 3 live in the @Query and a mocked repository never executes
    // it, so their criteria are Testcontainers ones in
    // MediaDurabilityIntegrationTest. Guard 2 lives in this class's Java, which
    // is why AC-3.3(b) below is correctly a unit test.
    // ==================================================================
    @org.junit.jupiter.api.Nested
    @ExtendWith(MockitoExtension.class)
    class SweepBehaviour {

        @Mock private MediaAssetRepository mediaAssetRepository;
        @Mock private StorageService storage;
        @Mock private jakarta.persistence.EntityManager entityManager;
        @Mock private jakarta.persistence.Query tenantQuery;
        @Mock private org.hibernate.Session session;
        @Mock private org.springframework.transaction.PlatformTransactionManager txManager;

        private final MediaProperties properties = new MediaProperties();
        private final io.micrometer.core.instrument.MeterRegistry meters =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        private MediaQuarantineRetentionSweep sweep;
        private final java.util.UUID tenant = java.util.UUID.randomUUID();

        @BeforeEach
        void setUpSweep() {
            org.mockito.Mockito.lenient()
                    .when(entityManager.createNativeQuery("SELECT id FROM tenants")).thenReturn(tenantQuery);
            org.mockito.Mockito.lenient()
                    .when(tenantQuery.getResultList()).thenReturn(java.util.List.of(tenant));
            org.mockito.Mockito.lenient()
                    .when(entityManager.unwrap(org.hibernate.Session.class)).thenReturn(session);
            org.mockito.Mockito.lenient().doNothing().when(session).doWork(any());
            sweep = new MediaQuarantineRetentionSweep(mediaAssetRepository, properties, storage,
                    entityManager, txManager, provider(meters));
        }

        @Test
        @DisplayName("AC-3.1: an expired quarantine object IS reclaimed and the sentinel stamped")
        void expiredQuarantineIsReclaimedAndSentinelStamped() {
            String key = tenant + "/quarantine/abc.jpg";
            MediaAsset asset = quarantineAsset(key, MediaAsset.Status.FAILED);
            when(mediaAssetRepository.findReclaimableQuarantine(any(), any()))
                    .thenReturn(java.util.List.of(asset));
            when(mediaAssetRepository.findAllById(any())).thenReturn(java.util.List.of(asset));
            when(storage.deleteByKeyChecked(key)).thenReturn(true);

            sweep.sweep();

            org.mockito.Mockito.verify(storage).deleteByKeyChecked(key);
            assertThat(asset.getQuarantineReclaimedAt())
                    .as("this is the Incremental Betterment receipt — Phase 24's bounded-growth "
                            + "good is preserved, just on a declared horizon")
                    .isNotNull();
        }

        @Test
        @DisplayName("AC-3.3(b) guard 2: a non-/quarantine/ key is NEVER reclaimed")
        void nonQuarantinePathIsNeverReclaimed() {
            // A V53-backfilled key. FAILED + expired, so guards 1 and 3 do NOT block it —
            // guard 2 is the only thing standing between this fixture and the delete.
            String key = tenant + "/products/" + java.util.UUID.randomUUID() + "/x.jpg";
            MediaAsset asset = quarantineAsset(key, MediaAsset.Status.FAILED);
            when(mediaAssetRepository.findReclaimableQuarantine(any(), any()))
                    .thenReturn(java.util.List.of(asset));

            sweep.sweep();

            org.mockito.Mockito.verify(storage, org.mockito.Mockito.never())
                    .deleteByKeyChecked(org.mockito.ArgumentMatchers.anyString());
            assertThat(asset.getQuarantineReclaimedAt()).isNull();
        }

        @Test
        @DisplayName("AC-3.4: a FAILED delete does not stamp the sentinel, and is retried next tick")
        void failedDeleteIsNotSentinelStampedAndIsRetried() {
            String key = tenant + "/quarantine/flaky.jpg";
            MediaAsset asset = quarantineAsset(key, MediaAsset.Status.FAILED);
            when(mediaAssetRepository.findReclaimableQuarantine(any(), any()))
                    .thenReturn(java.util.List.of(asset));
            when(mediaAssetRepository.findAllById(any())).thenReturn(java.util.List.of(asset));
            when(storage.deleteByKeyChecked(key)).thenReturn(false, true);

            sweep.sweep();   // tick 1 — S3 error
            assertThat(asset.getQuarantineReclaimedAt())
                    .as("stamping here would strand an object that still exists, forever, and "
                            + "deleteByKey swallows the error so nothing would complain")
                    .isNull();
            assertThat(meters.counter("media.quarantine.reclaim_failed").count()).isEqualTo(1.0);

            sweep.sweep();   // tick 2 — S3 recovered
            assertThat(asset.getQuarantineReclaimedAt()).isNotNull();
            assertThat(meters.counter("media.quarantine.reclaim_failed").count())
                    .as("the failure counter must not advance on the successful tick")
                    .isEqualTo(1.0);
        }

        private MediaAsset quarantineAsset(String objectKey, MediaAsset.Status status) {
            MediaAsset a = new MediaAsset();
            a.setId(java.util.UUID.randomUUID());
            a.setTenantId(tenant);
            a.setObjectKey(objectKey);
            a.setSha256("d".repeat(64));
            a.setContentType("image/jpeg");
            a.setStatus(status);
            a.setQuarantineExpiresAt(java.time.OffsetDateTime.now().minusHours(1));
            return a;
        }

        private <T> org.springframework.beans.factory.ObjectProvider<T> provider(T bean) {
            @SuppressWarnings("unchecked")
            org.springframework.beans.factory.ObjectProvider<T> p =
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            org.mockito.Mockito.lenient().when(p.getIfAvailable()).thenReturn(bean);
            return p;
        }
    }
}
