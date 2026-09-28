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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    // Task 2: quarantine is private, and the public container cannot be listed (#626)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A quarantined upload lands in the PRIVATE container: no public URL, anonymous GET refused")
    void quarantinedUploadIsPrivate() throws Exception {
        String key = quarantineKey();
        byte[] raw = webpBytes();

        String url = storage.putBytes(key, raw, "image/jpeg");

        assertThat(url).as("a private object must not be given a public URL").isNull();
        // Probe the key in BOTH containers. Probing only the private one cannot fail on the
        // regression that matters: if routing ever sent a quarantine key to the public container,
        // the private URL is simply 404 (absent) and the raw bytes are served from the public one.
        for (String container : java.util.List.of(
                AzuriteTestSupport.QUARANTINE_CONTAINER, AzuriteTestSupport.PUBLIC_CONTAINER)) {
            String direct = blobEndpoint() + "/" + container + "/" + key;
            HttpResponse<byte[]> get = anonymousGet(direct);
            assertThat(get.statusCode()).as("anonymous GET of %s", direct).isNotEqualTo(200);
            assertThat(new String(get.body(), java.nio.charset.StandardCharsets.ISO_8859_1))
                    .as("the refused response must not carry the raw (un-stripped) bytes")
                    .doesNotContain(new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1));
        }
    }

    @Test
    @DisplayName("#626: the public container serves anonymous GET by URL but refuses an anonymous LIST")
    void publicContainerIsReadableByUrlButNotListable() throws Exception {
        String url = storage.putBytes(derivativeKey(), webpBytes(), "image/webp");
        assertThat(anonymousGet(url).statusCode())
                .as("control: an anonymous GET of a stored public derivative succeeds")
                .isEqualTo(200);

        String list = blobEndpoint() + "/" + AzuriteTestSupport.PUBLIC_CONTAINER + "?restype=container&comp=list";
        HttpResponse<byte[]> ls = anonymousGet(list);
        String body = new String(ls.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(ls.statusCode()).as("anonymous LIST of %s (body: %s)", list, body).isNotEqualTo(200);
        assertThat(body).as("no inventory may leak").doesNotContain("<EnumerationResults");
    }

    @Test
    @DisplayName("A quarantine key round-trips through the private container and is absent from the public one")
    void quarantineKeyRoundTripsThroughThePrivateContainer() throws Exception {
        String key = quarantineKey();
        byte[] raw = webpBytes();

        storage.putBytes(key, raw, "image/jpeg");

        assertThat(storage.getBytes(key)).as("authenticated read through StorageService").isEqualTo(raw);
        String inPublic = blobEndpoint() + "/" + AzuriteTestSupport.PUBLIC_CONTAINER + "/" + key;
        assertThat(anonymousGet(inPublic).statusCode())
                .as("the raw bytes must not ALSO exist in the public container")
                .isEqualTo(404);
        assertThat(storage.deleteByKeyChecked(key)).isTrue();
        assertThat(storage.deleteByKeyChecked(key)).as("absent counts as gone in the private container too").isTrue();
    }

    // ------------------------------------------------------------------
    // Task 3: pipeline semantics the media pipeline and the seeder depend on
    // ------------------------------------------------------------------

    @Test
    @DisplayName("deleteByKeyChecked of a key that was never written returns true (absent = gone)")
    void deleteOfNeverWrittenKeyCountsAsGone() {
        assertThat(storage.deleteByKeyChecked(derivativeKey())).isTrue();
        assertThat(storage.deleteByKeyChecked(quarantineKey())).isTrue();
    }

    @Test
    @DisplayName("putSeedImage writes a deterministic key ONCE: a second call with different bytes never overwrites")
    void seedImageIsNeverOverwritten() throws Exception {
        UUID tenant = UUID.randomUUID();
        byte[] first = jpegBytes();
        byte[] second = jpegBytes();
        assertThat(second).isNotEqualTo(first);

        String url1 = storage.putSeedImage(tenant, "dish.jpg", first, "image/jpeg");
        String url2 = storage.putSeedImage(tenant, "dish.jpg", second, "image/jpeg");

        assertThat(url2).as("the seed URL is stable").isEqualTo(url1);
        HttpResponse<byte[]> get = anonymousGet(url1);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body())
                .as("'immutable' is only honest if the bytes at a deterministic key are written once (#489)")
                .isEqualTo(first);
        assertThat(get.headers().firstValue("Cache-Control")).contains(IMMUTABLE);
    }

    @Test
    @DisplayName("get of a missing key is the service's answer (BlobStorageException 404), not 'store unavailable'")
    void missingKeyIsAServiceAnswerNotUnavailability() {
        String key = derivativeKey();

        assertThatThrownBy(() -> storage.getBytes(key))
                .isInstanceOf(com.azure.storage.blob.models.BlobStorageException.class)
                .isNotInstanceOf(StorageUnavailableException.class)
                .satisfies(e -> assertThat(((com.azure.storage.blob.models.BlobStorageException) e).getStatusCode())
                        .isEqualTo(404));
    }

    // ------------------------------------------------------------------

    private static String blobEndpoint() {
        return AzuriteTestSupport.blobEndpoint(AZURITE);
    }

    private static String quarantineKey() {
        return UUID.randomUUID() + "/quarantine/" + UUID.randomUUID().toString().replace("-", "") + ".jpg";
    }

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

    /** JPEG-magic-headed payload (putSeedImage sniffs the magic bytes) with a random body. */
    private static byte[] jpegBytes() {
        byte[] bytes = new byte[64];
        ThreadLocalRandom.current().nextBytes(bytes);
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private static HttpResponse<byte[]> anonymousGet(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
