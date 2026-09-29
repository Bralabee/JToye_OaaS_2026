package uk.jtoye.core.product;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.BlobObjectStore;
import uk.jtoye.core.storage.BlobObjectStore.ContainerAccess;
import uk.jtoye.core.storage.StorageService;
import uk.jtoye.core.testsupport.AzuriteTestSupport;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-09 through the real attack path, with nothing stubbed: tenant A saves tenant B's public image
 * URL on its OWN product (the image URL is client-supplied and mapped straight through
 * {@code ProductMapper}), then removes that image or deletes the product. Before D-09 the
 * dual-read flat cleanup handed B's URL to {@code StorageService.delete(url)}, which removed B's
 * object from the shared public container.
 *
 * <p>Real Postgres with the Testcontainers role downgraded to NOSUPERUSER after seeding, so FORCE
 * RLS is enforced under the service calls (the {@code ShopImageCrossTenantIntegrationTest}
 * recipe), and a real Azurite with {@link StorageService} left unstubbed. The verdict is an
 * anonymous GET of B's URL — what a storefront browser sees. The same-tenant control shows the
 * probe does see a deletion when the caller owns the object.
 *
 * <p>{@code @AsSystemHarness}: the shop gate is scaffolding here (products with no shop), not the
 * subject; the subject is the tenant boundary at the storage delete, which this class asserts.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
@uk.jtoye.core.testsupport.AsSystemHarness
class ProductImageCrossTenantBlobDeleteIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final AzuriteContainer AZURITE = AzuriteTestSupport.newAzurite();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        AzuriteTestSupport.registerAzuriteProperties(registry, AZURITE);
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired private ProductService productService;
    @Autowired private ProductRepository productRepository;
    @Autowired private StorageService storageService;
    @Autowired private BlobObjectStore blobObjectStore;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;

    private UUID tenantA;
    private UUID tenantB;
    private byte[] bBytes;
    private String bUrl;
    private String aUrl;

    @BeforeEach
    void seed() {
        // The test profile keeps the boot probe off, so the fixture creates the containers.
        AzuriteTestSupport.createContainers(blobObjectStore, ContainerAccess.BLOB);

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "d09-A-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "d09-B-" + tenantB);

        bBytes = webpBytes();
        bUrl = storageService.putBytes(tenantB + "/products/" + UUID.randomUUID() + "/b.webp", bBytes, "image/webp");
        aUrl = storageService.putBytes(tenantA + "/products/" + UUID.randomUUID() + "/a.webp", webpBytes(), "image/webp");

        // Downgrade inside the test transaction (rolled back after each test): from here on FORCE
        // RLS applies to every statement the services issue.
        jdbc.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("removeImage: tenant A's product carrying tenant B's URL does not delete B's image")
    void removeImageOfForeignUrlLeavesTenantBsBlob() throws Exception {
        assertRlsIsEnforced();
        assertThat(anonymousGet(bUrl).statusCode()).as("precondition: B's image is served").isEqualTo(200);

        TenantContext.set(tenantA);
        UUID productId = createProductForTenantA(bUrl);
        productService.removeImage(productId);
        em.flush();
        em.clear();

        assertThat(productRepository.findById(productId).orElseThrow().getImageUrl())
                .as("the flat cleanup ran, so the delete line was reached").isNull();
        HttpResponse<byte[]> b = anonymousGet(bUrl);
        assertThat(b.statusCode()).as("tenant B's image %s after tenant A removed it from its own product", bUrl)
                .isEqualTo(200);
        assertThat(b.body()).isEqualTo(bBytes);
    }

    @Test
    @DisplayName("deleteProduct: tenant A deleting a product carrying tenant B's URL does not delete B's image")
    void deleteProductOfForeignUrlLeavesTenantBsBlob() throws Exception {
        assertRlsIsEnforced();

        TenantContext.set(tenantA);
        UUID productId = createProductForTenantA(bUrl);
        productService.deleteProduct(productId);
        em.flush();
        em.clear();

        assertThat(productRepository.findById(productId)).as("the product itself was deleted").isEmpty();
        assertThat(anonymousGet(bUrl).statusCode())
                .as("tenant B's image %s after tenant A deleted its own product", bUrl)
                .isEqualTo(200);
    }

    @Test
    @DisplayName("Control: the same removeImage on tenant A's own image deletes it (the probe sees a deletion)")
    void removeImageOfOwnUrlDeletesTheBlob() throws Exception {
        assertRlsIsEnforced();
        assertThat(anonymousGet(aUrl).statusCode()).as("precondition: A's image is served").isEqualTo(200);

        TenantContext.set(tenantA);
        UUID productId = createProductForTenantA(aUrl);
        productService.removeImage(productId);

        assertThat(anonymousGet(aUrl).statusCode()).as("tenant A's own image after removeImage").isEqualTo(404);
    }

    /**
     * The RLS wall is really up under these calls: the role is not a superuser, tenant A sees its
     * own seeded row and tenant B does not. The positive half matters: an unpinned or broken
     * query that sees nothing would make the negative half pass on its own.
     */
    private void assertRlsIsEnforced() {
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class))
                .as("the connection role is NOSUPERUSER, so FORCE RLS applies").isFalse();
        TenantContext.set(tenantA);
        UUID probe = createProductForTenantA(null);
        em.flush();
        em.clear();
        assertThat(productRepository.findById(probe)).as("tenant A sees its own row").isPresent();
        TenantContext.set(tenantB);
        em.clear();
        assertThat(productRepository.findById(probe)).as("tenant B does not see tenant A's row").isEmpty();
        TenantContext.clear();
    }

    private UUID createProductForTenantA(String imageUrl) {
        CreateProductRequest request = new CreateProductRequest();
        request.setSku("D09-" + UUID.randomUUID().toString().substring(0, 8));
        request.setTitle("D-09 probe");
        request.setIngredientsText("Yam (100%)");
        request.setAllergenMask(0);
        request.setPricePennies(100L);
        request.setImageUrl(imageUrl);
        return productService.createProduct(request).getId();
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
