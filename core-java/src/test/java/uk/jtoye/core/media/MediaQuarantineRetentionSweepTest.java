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
        storageService = new StorageService(s3Client, properties);
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
}
