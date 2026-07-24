package uk.jtoye.core.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
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
 * IMG-02 / D-06 idempotency-contract proof (Task 3). The mutating media accept is an
 * agent-facing API surface, so it honours the uniform {@code Idempotency-Key} contract with
 * RFC 7807 typed errors:
 *
 * <ul>
 *   <li><b>Replay</b> — the SAME key + SAME body returns the ORIGINAL 202 + asset id and mints
 *       zero duplicate rows (a naive non-idempotent accept would quarantine twice — the test
 *       RED-proves the contract).</li>
 *   <li><b>Body mismatch</b> — the same key + a DIFFERENT file is 422
 *       ({@code .../errors/idempotency-payload-mismatch}).</li>
 *   <li><b>In-flight</b> — a reserved-but-uncompleted key is 409
 *       ({@code .../errors/idempotency-conflict}).</li>
 *   <li><b>Oversize</b> — 413 as {@code application/problem+json} with a stable type slug.</li>
 * </ul>
 *
 * <p>Driven through MockMvc so the {@code Idempotency-Key} header branch is exercised
 * end-to-end. NOT {@code @Transactional}: each accept commits so the replay observes the
 * committed reservation. {@link StorageService} is a {@code @SpyBean} (no live MinIO).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class MediaUploadIdempotencyTest {

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
    @SpyBean private StorageService storageService;

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000241");
    private UUID productId;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT, "Media Idem Tenant");
        UUID shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, delivery_fee_pennies, "
                        + "minimum_order_pennies, version) VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                shopId, TENANT, "Idem Shop " + shopId, "idem-shop-" + shopId.toString().substring(0, 8));
        productId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, allergen_mask, "
                        + "price_pennies, display_order, available, featured, shop_id, quantity_in_stock, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, 0, 0)",
                productId, TENANT, "SKU-IDEM-" + productId.toString().substring(0, 8), "Suya",
                "beef, spice", shopId);
        Mockito.doReturn("http://minio/quarantine-object")
                .when(storageService).putBytes(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

    private static RequestPostProcessor operatorJwt() {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT.toString())
                        .claim("scope", "catalog:read catalog:write"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    private static MockMultipartFile jpeg(int seed) {
        byte[] jpeg = new byte[80];
        jpeg[0] = (byte) 0xFF; jpeg[1] = (byte) 0xD8; jpeg[2] = (byte) 0xFF; jpeg[3] = (byte) 0xE0;
        for (int i = 4; i < jpeg.length; i++) jpeg[i] = (byte) ((i * 31 + seed) % 251);
        return new MockMultipartFile("file", "img-" + seed + ".jpg", "image/jpeg", jpeg);
    }

    // --- Replay: same key + same body -> original asset, no duplicate --------

    @Test
    void sameKeyReplaySameFile_returnsOriginalAsset_noSecondRow() throws Exception {
        String key = "media-replay-" + UUID.randomUUID();
        MockMultipartFile file = jpeg(1);

        MvcResult first = mockMvc.perform(multipart("/api/v1/products/{id}/image", productId)
                        .file(file).header("Idempotency-Key", key).with(operatorJwt()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String firstAssetId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.assetId");

        MvcResult second = mockMvc.perform(multipart("/api/v1/products/{id}/image", productId)
                        .file(jpeg(1)).header("Idempotency-Key", key).with(operatorJwt()))
                .andExpect(status().isAccepted())
                .andReturn();
        String secondAssetId = com.jayway.jsonpath.JsonPath.read(second.getResponse().getContentAsString(), "$.assetId");

        assertThat(secondAssetId).as("replay returns the ORIGINAL asset id").isEqualTo(firstAssetId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_asset WHERE product_id = ?", Integer.class, productId))
                .as("exactly one media_asset row despite the repeated key").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_event_outbox WHERE tenant_id = ?", Integer.class, TENANT))
                .as("exactly one outbox row (replay did not re-enqueue)").isEqualTo(1);
        Mockito.verify(storageService, Mockito.times(1))
                .putBytes(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

    // --- Body mismatch: same key + different body -> 422 --------------------

    @Test
    void sameKeyDifferentFile_returns422() throws Exception {
        String key = "media-mismatch-" + UUID.randomUUID();

        mockMvc.perform(multipart("/api/v1/products/{id}/image", productId)
                        .file(jpeg(2)).header("Idempotency-Key", key).with(operatorJwt()))
                .andExpect(status().isAccepted());

        mockMvc.perform(multipart("/api/v1/products/{id}/image", productId)
                        .file(jpeg(99)).header("Idempotency-Key", key).with(operatorJwt()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/idempotency-payload-mismatch"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_asset WHERE product_id = ?", Integer.class, productId))
                .as("the mismatched retry created no second asset").isEqualTo(1);
    }

    // --- In-flight: a reserved-but-uncompleted key -> 409 -------------------

    @Test
    void inFlightDuplicate_returns409() throws Exception {
        String key = "media-inflight-" + UUID.randomUUID();
        // Simulate a first request still in-flight: a reserved idempotency_keys row whose
        // response_status is still NULL. IdempotencyService.execute checks NULL-status (409)
        // BEFORE the request-hash, so a duplicate with this key is a deterministic conflict.
        jdbc.update("INSERT INTO idempotency_keys (tenant_id, endpoint, idempotency_key, request_hash) "
                        + "VALUES (?, ?, ?, ?)",
                TENANT, "media.upload", key, "0".repeat(64));

        mockMvc.perform(multipart("/api/v1/products/{id}/image", productId)
                        .file(jpeg(3)).header("Idempotency-Key", key).with(operatorJwt()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/idempotency-conflict"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_asset WHERE product_id = ?", Integer.class, productId))
                .as("an in-flight conflict creates no asset").isEqualTo(0);
    }

    // --- Oversize error is RFC 7807 problem+json ----------------------------

    @Test
    void oversizeError_isRfc7807ProblemJson() throws Exception {
        mockMvc.perform(multipart("/api/v1/products/{id}/image", productId)
                        .file(jpeg(4))
                        .header("Content-Length", "999999999")
                        .header("Idempotency-Key", "media-oversize-" + UUID.randomUUID())
                        .with(operatorJwt()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/payload-too-large"));
    }
}
