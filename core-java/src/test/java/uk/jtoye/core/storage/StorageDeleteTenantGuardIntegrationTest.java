package uk.jtoye.core.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.media.MediaNormalizer;
import uk.jtoye.core.media.MediaProperties;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.BlobObjectStore.ContainerAccess;
import uk.jtoye.core.testsupport.AzuriteTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-09 on real storage: {@link StorageService#delete(String)} deletes a URL-addressed object only
 * when the key's tenant segment is the caller's {@link TenantContext} tenant.
 *
 * <p>Every tenant's images live in ONE public container, and product, shop and review image URLs
 * are client-supplied. Before D-09 a delete by URL removed any key under the public prefix, so
 * tenant A could delete tenant B's image. The verdict here is read the way a browser reads it: an
 * anonymous GET of tenant B's URL after tenant A asked for it to be deleted. The same GET on a
 * blob the caller DOES own returns 404 after the delete, which is what shows the survival
 * assertions can see a deletion when one happens.
 *
 * <p>No Spring context, no stubbing: the real StorageService built through StorageConfig's own
 * bean methods against a digest-pinned Azurite (the {@code AzuriteStorageIntegrationTest} shape).
 */
@Testcontainers
@Tag("testcontainers")
class StorageDeleteTenantGuardIntegrationTest {

    @Container
    static final AzuriteContainer AZURITE = AzuriteTestSupport.newAzurite();

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static StorageProperties properties;
    private static StorageService storage;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @BeforeAll
    static void wireStorage() {
        properties = AzuriteTestSupport.storageProperties(AZURITE);
        StorageConfig config = new StorageConfig();
        BlobObjectStore store = config.blobObjectStore(config.blobServiceClient(properties));
        AzuriteTestSupport.createContainers(store, ContainerAccess.BLOB);
        storage = new StorageService(store, properties, new MediaNormalizer(new MediaProperties()));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Tenant B's image survives delete(url) called under tenant A; tenant A's own image is deleted")
    void foreignTenantBlobSurvivesAndOwnBlobIsDeleted() throws Exception {
        byte[] bBytes = webpBytes();
        String bUrl = storage.putBytes(tenantB + "/products/" + UUID.randomUUID() + "/x.webp", bBytes, "image/webp");
        String aUrl = storage.putBytes(tenantA + "/products/" + UUID.randomUUID() + "/x.webp", webpBytes(), "image/webp");
        assertThat(anonymousGet(bUrl).statusCode()).as("precondition: B's blob exists").isEqualTo(200);
        assertThat(anonymousGet(aUrl).statusCode()).as("precondition: A's blob exists").isEqualTo(200);

        TenantContext.set(tenantA);
        storage.delete(bUrl);
        storage.delete(aUrl);
        TenantContext.clear();

        HttpResponse<byte[]> b = anonymousGet(bUrl);
        assertThat(b.statusCode()).as("tenant B's blob after tenant A asked to delete %s", bUrl).isEqualTo(200);
        assertThat(b.body()).as("tenant B's bytes are intact").isEqualTo(bBytes);
        assertThat(anonymousGet(aUrl).statusCode())
                .as("positive control: tenant A's own blob IS deleted, so a deletion is visible to this probe")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("With no tenant context a URL delete is refused and the blob survives")
    void noTenantContextLeavesTheBlob() throws Exception {
        String bUrl = storage.putBytes(tenantB + "/products/" + UUID.randomUUID() + "/x.webp", webpBytes(), "image/webp");

        TenantContext.clear();
        storage.delete(bUrl);

        assertThat(anonymousGet(bUrl).statusCode()).as("blob after an unscoped delete of %s", bUrl).isEqualTo(200);
    }

    /**
     * Store-behaviour evidence, NOT evidence of the guard. Measured 2026-09-29: this test is green
     * with no D-09 guard at all and with only the dot-segment check removed, because Azurite and
     * the Java SDK address these keys literally (no path normalisation), so none of them reaches
     * tenant B's blob here. The guard's refusal of such keys is proven in {@code StorageServiceTest}
     * ({@code d09DotSegmentTraversalIsRefused}); this test would go red only if the store began
     * normalising paths AND that check were missing, which is the case it is kept for.
     */
    @Test
    @DisplayName("On Azurite, dot-segment and encoded keys under tenant A's segment never reach tenant B's blob")
    void traversalFromOwnSegmentIntoForeignKeyIsRefused() throws Exception {
        String suffix = "products/" + UUID.randomUUID() + "/x.webp";
        String bKey = tenantB + "/" + suffix;
        String bUrl = storage.putBytes(bKey, webpBytes(), "image/webp");
        String base = properties.getBlob().getPublicUrl() + "/";

        List<String> attacks = List.of(
                base + tenantA + "/../" + bKey,
                base + tenantA + "/./../" + bKey,
                base + tenantA + "/%2E%2E/" + bKey,
                base + tenantA + "/..%2F" + bKey,
                base + tenantA + "/%2e%2e%2f" + bKey);

        TenantContext.set(tenantA);
        for (String attack : attacks) {
            storage.delete(attack);
            assertThat(anonymousGet(bUrl).statusCode())
                    .as("tenant B's blob after tenant A asked to delete %s", attack)
                    .isEqualTo(200);
        }
    }

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
