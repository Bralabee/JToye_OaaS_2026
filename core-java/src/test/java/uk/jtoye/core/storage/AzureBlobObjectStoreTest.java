package uk.jtoye.core.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * which is a running dev Azurite. An endpoint-only connection string addresses the chosen port and
 * carries no credential at all.
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
        properties.getBlob().setConnectionString("BlobEndpoint=http://127.0.0.1:" + closedPort + "/devstoreaccount1");
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
}
