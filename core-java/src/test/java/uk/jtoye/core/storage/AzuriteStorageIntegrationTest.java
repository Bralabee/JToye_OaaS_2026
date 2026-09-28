package uk.jtoye.core.storage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.media.MediaNormalizer;
import uk.jtoye.core.media.MediaProperties;
import uk.jtoye.core.storage.BlobObjectStore.ContainerAccess;
import uk.jtoye.core.testsupport.AzuriteTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 36 tracer: the REAL {@link StorageService}, built through {@link StorageConfig}'s own bean
 * factory methods, against a digest-pinned Azurite — SDK, emulator, split-horizon public URL and an
 * anonymous browser-style GET, end to end.
 *
 * <p>No Spring context and no stubbing: every media suite that existed before this phase replaces
 * storage with a {@code @SpyBean} and stubbed I/O, so none of them can say anything about what the
 * store actually does (36-RESEARCH Pitfall 1). This class is where that is proven.
 *
 * <p>"Anonymous" means a plain {@link HttpClient} request with NO {@code Authorization} header —
 * exactly what a browser loading an {@code <img>} sends.
 */
@Testcontainers
@Tag("testcontainers")
class AzuriteStorageIntegrationTest {

    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    @Container
    static final AzuriteContainer AZURITE = AzuriteTestSupport.newAzurite();

    private static StorageProperties properties;
    private static StorageService storage;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void wireStorageThroughTheConfigSwitch() {
        properties = AzuriteTestSupport.storageProperties(AZURITE);
        StorageConfig config = new StorageConfig();
        BlobObjectStore store = config.blobObjectStore(config.blobServiceClient(properties));
        AzuriteTestSupport.createContainers(store, ContainerAccess.BLOB);
        storage = new StorageService(store, properties, new MediaNormalizer(new MediaProperties()));
    }

    @Test
    @DisplayName("putBytes returns exactly storage.blob.public-url + '/' + key (never the SDK's own blob URL)")
    void putBytesReturnsThePublicUrlForTheKey() {
        String key = derivativeKey();

        String url = storage.putBytes(key, webpBytes(), "image/webp");

        String publicUrl = properties.getBlob().getPublicUrl();
        assertThat(url)
                .as("persisted URLs are composed from the configured browser origin (split horizon)")
                .startsWith(publicUrl + "/")
                .isEqualTo(publicUrl + "/" + key);
    }

    @Test
    @DisplayName("A stored derivative is anonymously readable at its URL with its bytes, type and immutable cache header")
    void storedDerivativeIsAnonymouslyReadableWithImmutableHeaders() throws Exception {
        String key = derivativeKey();
        byte[] bytes = webpBytes();

        String url = storage.putBytes(key, bytes, "image/webp");
        HttpResponse<byte[]> get = anonymousGet(url);

        assertThat(get.statusCode()).as("anonymous GET of %s", url).isEqualTo(200);
        assertThat(get.body()).isEqualTo(bytes);
        assertThat(get.headers().firstValue("Content-Type")).contains("image/webp");
        assertThat(get.headers().firstValue("Cache-Control")).contains(IMMUTABLE);
    }

    @Test
    @DisplayName("getBytes returns the bytes that were stored")
    void getBytesRoundTrips() {
        String key = derivativeKey();
        byte[] bytes = webpBytes();

        storage.putBytes(key, bytes, "image/webp");

        assertThat(storage.getBytes(key)).isEqualTo(bytes);
    }

    @Test
    @DisplayName("deleteByKeyChecked removes the object, and deleting it again still counts as gone")
    void checkedDeleteRemovesAndAbsentCountsAsGone() throws Exception {
        String key = derivativeKey();
        String url = storage.putBytes(key, webpBytes(), "image/webp");
        assertThat(anonymousGet(url).statusCode())
                .as("precondition: the object exists before the delete")
                .isEqualTo(200);

        assertThat(storage.deleteByKeyChecked(key)).as("first delete").isTrue();
        assertThat(anonymousGet(url).statusCode()).as("anonymous GET after delete").isEqualTo(404);
        assertThat(storage.deleteByKeyChecked(key))
                .as("a delete of an absent object is 'gone', or the quarantine sweep strands its sentinel")
                .isTrue();
    }

    // ------------------------------------------------------------------

    private static String derivativeKey() {
        return UUID.randomUUID() + "/media/" + UUID.randomUUID() + ".webp";
    }

    /** A RIFF/WEBP-headed payload with a random body, so no two tests store the same bytes. */
    private static byte[] webpBytes() {
        byte[] bytes = new byte[64];
        ThreadLocalRandom.current().nextBytes(bytes);
        byte[] header = {0x52, 0x49, 0x46, 0x46, 0x38, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50};
        System.arraycopy(header, 0, bytes, 0, header.length);
        return bytes;
    }

    private static HttpResponse<byte[]> anonymousGet(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
