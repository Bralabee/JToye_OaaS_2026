package uk.jtoye.core.storage;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobContainerProperties;
import com.azure.storage.blob.models.PublicAccessType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An unreachable object store surfaces as {@link StorageUnavailableException} (36-RESEARCH
 * assumption A4, proven here rather than assumed). That mapping is what lets the demo seeder abort
 * image seeding once, instead of timing out on every manifest entry, and keeps a store outage from
 * being fatal to dev boot.
 *
 * <p>No container: the client is built through {@link StorageConfig} against a port that was bound
 * and immediately released, so nothing is listening there and the connection is refused.
 *
 * <p><b>Why {@code BlobEndpoint=} and not the emulator shorthand.</b> The SDK expands
 * {@code UseDevelopmentStorage=true;DevelopmentStorageProxyUri=...} from the proxy URI's scheme and
 * HOST only and always appends {@code :10000/devstoreaccount1}, so a port in the proxy URI is
 * silently ignored (verified in {@code StorageEmulatorConnectionString}'s bytecode and by building a
 * client: {@code getAccountUrl()} reports port 10000). That form would reach whatever is on 10000,
 * which is a running dev Azurite. The emulator-account form with an explicit {@code BlobEndpoint}
 * addresses the chosen port. Since 36-06 the shape rules accept a connection string only in an
 * emulator form, and the SDK needs a key alongside {@code AccountName}, so the key here is the
 * base64 of a single byte: a placeholder that authenticates nothing, not the emulator key (which
 * never appears in this repository).
 */
class AzureBlobObjectStoreTest {

    private BlobObjectStore store;

    @BeforeEach
    void buildStoreAgainstAClosedPort() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        StorageProperties properties = new StorageProperties();
        properties.getBlob().setAuthMode("connection-string");
        properties.getBlob().setConnectionString("AccountName=devstoreaccount1;AccountKey=eA==;BlobEndpoint=http://127.0.0.1:" + closedPort + "/devstoreaccount1");
        properties.getBlob().setMaxTries(1);
        properties.getBlob().setTryTimeoutSeconds(2);
        StorageConfig config = new StorageConfig();
        store = config.blobObjectStore(config.blobServiceClient(properties));
    }

    @Test
    @DisplayName("get from an unreachable store throws StorageUnavailableException, cause preserved")
    void getFromUnreachableStoreIsUnavailable() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> store.get("jtoye-images", "t/media/x.webp"))
                .isInstanceOf(StorageUnavailableException.class)
                .hasCauseInstanceOf(RuntimeException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("maxTries=1 must fail fast, not retry")
                .isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("put to an unreachable store throws StorageUnavailableException, cause preserved")
    void putToUnreachableStoreIsUnavailable() {
        assertThatThrownBy(() -> store.put("jtoye-images", "t/media/x.webp", new byte[]{1}, "image/webp", null))
                .isInstanceOf(StorageUnavailableException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    // ---- WR-06: only a TRANSPORT failure is "unavailable" ----------------------------------------
    //
    // The two tests above are the load-bearing half: they drive the real SDK into a refused
    // connection, so if its failure did not carry an IOException/TimeoutException in the cause
    // chain, the narrowed mapping below would stop calling it unavailable and they would fail.

    @Test
    @DisplayName("WR-06: an SDK validation error (IllegalArgumentException) propagates unchanged, not as 'unreachable'")
    void programmingErrorIsNotReportedAsUnavailable() {
        IllegalArgumentException bad = new IllegalArgumentException("blob name too long");

        assertThatThrownBy(() -> AzureBlobObjectStore.call("put c/k", () -> { throw bad; }))
                .isSameAs(bad);
    }

    @Test
    @DisplayName("WR-06: a NullPointerException propagates unchanged, not as 'unreachable'")
    void nullPointerIsNotReportedAsUnavailable() {
        NullPointerException npe = new NullPointerException("key");

        assertThatThrownBy(() -> AzureBlobObjectStore.call("get c/k", () -> { throw npe; }))
                .isSameAs(npe);
    }

    @Test
    @DisplayName("WR-06: transport shapes (IOException / TimeoutException anywhere in the chain) are unavailable")
    void transportShapesAreUnavailable() {
        List<RuntimeException> transport = List.of(
                new UncheckedIOException(new java.net.ConnectException("Connection refused")),
                new RuntimeException(new RuntimeException(new java.net.UnknownHostException("store"))),
                // Reactor's block(Duration) timeout: IllegalStateException caused by a TimeoutException.
                new IllegalStateException("Timeout on blocking read", new TimeoutException("30s")));
        for (RuntimeException failure : transport) {
            assertThatThrownBy(() -> AzureBlobObjectStore.call("get c/k", () -> { throw failure; }))
                    .as("%s", failure)
                    .isInstanceOf(StorageUnavailableException.class)
                    .hasCause(failure);
        }
    }

    @Test
    @DisplayName("WR-06: a cyclic cause chain is walked once and does not loop")
    void cyclicCauseChainTerminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(AzureBlobObjectStore.isTransportFailure(a)).isFalse();
    }

    @Test
    @DisplayName("WR-06: an unrecognised container access level is a configuration error, not 'unreachable'")
    void unrecognisedAccessLevelIsAConfigurationError() {
        BlobServiceClient client = mock(BlobServiceClient.class);
        BlobContainerClient container = mock(BlobContainerClient.class);
        when(client.getBlobContainerClient("jtoye-images")).thenReturn(container);
        when(container.getProperties()).thenReturn(new BlobContainerProperties(Map.of(), "etag", OffsetDateTime.now(),
                null, null, null, PublicAccessType.fromString("tenant-only"), false, false));

        assertThatThrownBy(() -> new AzureBlobObjectStore(client).containerAccess("jtoye-images"))
                .isInstanceOf(StorageConfigurationException.class)
                .hasMessageContaining("Unrecognised container access level");
    }
}
