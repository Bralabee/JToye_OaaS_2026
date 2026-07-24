package uk.jtoye.core.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.JwtRolesAndScopesConverter;
import uk.jtoye.core.storage.StorageService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IMG-02 accept-side proof (Task 2). Drives the REAL {@link MediaUploadController} through
 * MockMvc + the security filter chain over Testcontainers Postgres:
 *
 * <ul>
 *   <li><b>Context boots with NO Ambiguous mapping</b> — the very fact this @SpringBootTest
 *       refreshes proves the old {@code ProductController.uploadImage} handler was retired;
 *       {@link #contextLoadsWithSingleImageUploadHandler} additionally asserts EXACTLY ONE
 *       handler owns {@code POST /api/v1/products/{id}/image}.</li>
 *   <li><b>Reject-early 413</b> before buffering (RFC 7807), proven by no quarantine PUT.</li>
 *   <li><b>Valid accept 202</b> + PENDING media_asset + same-tx media_event_outbox row.</li>
 *   <li><b>Missing Idempotency-Key -> 400.</b></li>
 * </ul>
 *
 * <p>{@link StorageService} is a {@code @SpyBean} so the quarantine PUT is asserted without a
 * live MinIO (the real {@code detectContentType} still runs on the raw bytes). NOT
 * {@code @Transactional}: the accept commits so the post-request row assertions observe it
 * (Testcontainers superuser bypasses RLS, mirroring the sibling media integration tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class MediaUploadControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;
    @SpyBean private StorageService storageService;

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000240");
    private UUID productId;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT, "Media Tenant");
        UUID shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, delivery_fee_pennies, "
                        + "minimum_order_pennies, version) VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                shopId, TENANT, "Media Shop " + shopId, "media-shop-" + shopId.toString().substring(0, 8));
        productId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, allergen_mask, "
                        + "price_pennies, display_order, available, featured, shop_id, quantity_in_stock, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, 0, 0)",
                productId, TENANT, "SKU-MEDIA-" + productId.toString().substring(0, 8), "Jollof Rice",
                "rice, tomato", shopId);
        // Quarantine PUT stubbed — no live MinIO; the real detectContentType still runs.
        Mockito.doReturn("http://minio/quarantine-object")
                .when(storageService).putBytes(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

    // Operator-shaped token (UUID subject + tenant claim + catalog:write) — under strict-scoping OFF
    // a UUID-subject caller is a day-one implicit GROUP_ADMIN, so the SHOP_MANAGER shop gate passes.
    private static RequestPostProcessor operatorJwt() {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT.toString())
                        .claim("scope", "catalog:read catalog:write"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    /** A minimal but magic-byte-valid JPEG (FF D8 FF ...) so detectContentType returns image/jpeg. */
    private static MockMultipartFile jpegPart() {
        byte[] jpeg = new byte[64];
        jpeg[0] = (byte) 0xFF; jpeg[1] = (byte) 0xD8; jpeg[2] = (byte) 0xFF; jpeg[3] = (byte) 0xE0;
        for (int i = 4; i < jpeg.length; i++) jpeg[i] = (byte) (i % 7);
        return new MockMultipartFile("file", "food.jpg", "image/jpeg", jpeg);
    }

    // --- Retirement / ambiguous-mapping proof -------------------------------

    @Test
    void contextLoadsWithSingleImageUploadHandler() {
        // RequestMappingInfo.toString() renders as e.g.
        // "{POST [/api/v1/products/{id}/image], consumes [multipart/form-data]}"; the bracketed
        // path uniquely distinguishes the exact route from /image/analyze and /images.
        long owners = handlerMapping.getHandlerMethods().keySet().stream()
                .filter(info -> {
                    String s = info.toString();
                    return s.contains("POST") && s.contains("[/api/v1/products/{id}/image]");
                })
                .count();
        assertThat(owners)
                .as("exactly ONE handler owns POST /api/v1/products/{id}/image (old ProductController.uploadImage retired)")
                .isEqualTo(1);
    }

    // --- Reject-early 413 (before buffering) --------------------------------

    @Test
    void rejectsOversizeBeforeBuffering() throws Exception {
        mockMvc.perform(multipart("/api/v1/products/{id}/image", productId)
                        .file(jpegPart())
                        .header("Content-Length", "999999999")   // declared far above the 5MB cap
                        .header("Idempotency-Key", "oversize-" + UUID.randomUUID())
                        .with(operatorJwt()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/payload-too-large"));

        // The MultipartFile was never quarantined and no PENDING row was created.
        Mockito.verify(storageService, Mockito.never())
                .putBytes(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
        assertThat(countAssets()).as("no media_asset row for a rejected oversize upload").isZero();
    }

    // --- WR-04: the reject-early gate uses the REQUEST budget, not the file cap ----

    @Test
    void nearLimitFileWithinRequestBudgetIsNotRejected() throws Exception {
        // A legitimate near-limit upload whose multipart envelope pushes the whole-request
        // Content-Length between the 5MB file cap and the 6MB max-request-size must NOT be
        // spuriously 413'd. The reject-early gate compares Content-Length against the REQUEST
        // budget (max-request-size), so a declared 5.5MB envelope is accepted (the tiny actual
        // file is well within the file cap). Pre-fix this compared against the 5MB file cap and
        // returned 413.
        mockMvc.perform(multipart("/api/v1/products/{id}/image", productId)
                        .file(jpegPart())
                        .header("Content-Length", "5500000")   // between max-file-size (5MB) and max-request-size (6MB)
                        .header("Idempotency-Key", "near-limit-" + UUID.randomUUID())
                        .with(operatorJwt()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // --- Valid accept: 202 + PENDING asset + same-tx outbox row -------------

    @Test
    void validUploadReturns202WithPendingAssetAndOutboxRow() throws Exception {
        mockMvc.perform(multipart("/api/v1/products/{id}/image", productId)
                        .file(jpegPart())
                        .header("Idempotency-Key", "accept-" + UUID.randomUUID())
                        .with(operatorJwt()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.assetId").exists())
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(countAssets()).as("one PENDING media_asset created").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM media_asset WHERE product_id = ? AND status = 'PENDING'",
                Integer.class, productId))
                .as("the asset carries the pending-placement product_id + PENDING status").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM media_event_outbox WHERE tenant_id = ? AND status = 'PENDING'",
                Integer.class, TENANT))
                .as("a same-tx media_event_outbox PENDING row was inserted").isEqualTo(1);
        Mockito.verify(storageService)
                .putBytes(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

    // --- Missing Idempotency-Key -> 400 -------------------------------------

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/products/{id}/image", productId)
                        .file(jpegPart())
                        .with(operatorJwt()))
                .andExpect(status().isBadRequest());
        assertThat(countAssets()).as("no asset created when the Idempotency-Key header is absent").isZero();
    }

    private Integer countAssets() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM media_asset WHERE tenant_id = ? AND product_id = ?",
                Integer.class, TENANT, productId);
    }
}
