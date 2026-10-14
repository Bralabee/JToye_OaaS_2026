package uk.jtoye.core.integration;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.shop.dto.CreateShopRequest;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD)
@org.junit.jupiter.api.Tag("testcontainers")
class ShopControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID testTenantId;

    @BeforeEach
    void setup() {
        testTenantId = UUID.randomUUID();
        String uniqueTenantName = "Test Tenant " + testTenantId.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?)",
                testTenantId, uniqueTenantName);

        // Clean up ALL existing shops to ensure test isolation
        jdbcTemplate.update("DELETE FROM shops");
    }

    /**
     * Production-shaped auth: a UUID-subject Keycloak JWT carrying the realm-admin
     * authority — an implicit tenant-wide GROUP_ADMIN through the D-03 realm-admin bridge, which
     * stays honoured under strict-scoping ON (D-06) as well as OFF, mirroring
     * {@code ShopAccessEnforcementIntegrationTest.authenticate(sub, realmAdmin=true)}.
     * Replaces the pre-Phase-23 {@code WithMockUser}, whose non-JWT principal the
     * fail-closed {@code ShopAccessService} (23-08) now correctly denies. Every method
     * migrated here was a general authenticated operator (shop create/list), so admin =
     * the same effective access they were written to assert.
     */
    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j
                        .subject(UUID.randomUUID().toString())
                        .claim("email", "operator@example.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"));
    }

    @Test
    void healthEndpointShouldBePublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }

    @Test
    void listShopsWithoutAuthShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/shops"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createShopWithoutTenantHeaderShouldReturn400() throws Exception {
        CreateShopRequest request = new CreateShopRequest();
        request.setName("Test Shop");
        request.setAddress("123 Test St");

        mockMvc.perform(post("/api/v1/shops")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createShopWithValidTenantShouldSucceed() throws Exception {
        String uniqueShopName = "Test Shop " + UUID.randomUUID();
        CreateShopRequest request = new CreateShopRequest();
        request.setName(uniqueShopName);
        request.setAddress("123 Test St");

        mockMvc.perform(post("/api/v1/shops")
                        .with(adminJwt())
                        .header("X-Tenant-Id", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(uniqueShopName))
                .andExpect(jsonPath("$.address").value("123 Test St"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void listShopsShouldReturnPaginatedResults() throws Exception {
        // Create test shops
        for (int i = 1; i <= 5; i++) {
            CreateShopRequest request = new CreateShopRequest();
            request.setName("Shop " + i);
            request.setAddress("Address " + i);

            mockMvc.perform(post("/api/v1/shops")
                    .with(adminJwt())
                    .header("X-Tenant-Id", testTenantId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));
        }

        // Verify pagination
        mockMvc.perform(get("/api/v1/shops")
                        .with(adminJwt())
                        .header("X-Tenant-Id", testTenantId.toString())
                        .param("page", "0")
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.number").value(0));
    }

    @Test
    void createShopWithInvalidDataShouldReturnValidationError() throws Exception {
        CreateShopRequest request = new CreateShopRequest();
        // Missing required name field

        mockMvc.perform(post("/api/v1/shops")
                        .with(adminJwt())
                        .header("X-Tenant-Id", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    // ------------------------------------------------------------------
    // 37-48 (RWO-028 / UXT-028, owner ruling 2026-10-10): editing a shop keeps its web address. A slug changes
    // only when one is sent explicitly, and an explicit slug is validated (shape, reserved, taken). Slugs are
    // read back by SQL, never from the response alone.
    // ------------------------------------------------------------------


    private String createShopReturningId(String name, String slug) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", name);
        if (slug != null) {
            body.put("slug", slug);
        }
        String json = mockMvc.perform(post("/api/v1/shops")
                        .with(adminJwt())
                        .header("X-Tenant-Id", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).path("id").asString();
    }

    private org.springframework.test.web.servlet.ResultActions putShop(String id, java.util.Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(put("/api/v1/shops/" + id)
                .with(adminJwt())
                .header("X-Tenant-Id", testTenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String storedSlug(String id) {
        return jdbcTemplate.queryForObject("SELECT slug FROM shops WHERE id = ?::uuid", String.class, id);
    }

    private static java.util.Map<String, Object> body(Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void uxt028_updateWithoutSlug_keepsTheSlug_evenWhenTheNameChanges() throws Exception {
        String id = createShopReturningId("Keep Address Kitchen", null);
        String before = storedSlug(id);

        putShop(id, body("name", "Keep Address Kitchen", "description", "hours saved")).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(storedSlug(id)).as("an ordinary save keeps the slug").isEqualTo(before);

        putShop(id, body("name", "Renamed Kitchen")).andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(before));
        org.assertj.core.api.Assertions.assertThat(storedSlug(id)).as("a rename keeps the slug too").isEqualTo(before);
    }

    @Test
    void uxt028_blankSlug_keepsTheSlug() throws Exception {
        String id = createShopReturningId("Blank Slug Kitchen", null);
        String before = storedSlug(id);
        putShop(id, body("name", "Blank Slug Kitchen", "slug", "")).andExpect(status().isOk());
        putShop(id, body("name", "Blank Slug Kitchen", "slug", "   ")).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(storedSlug(id)).isEqualTo(before);
    }

    @Test
    void uxt028_explicitSlug_changesIt_andTheSameSlugIsANoOp() throws Exception {
        String id = createShopReturningId("Explicit Slug Kitchen", null);
        String fresh = "explicit-" + UUID.randomUUID().toString().substring(0, 8);
        putShop(id, body("name", "Explicit Slug Kitchen", "slug", fresh)).andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(fresh));
        org.assertj.core.api.Assertions.assertThat(storedSlug(id)).isEqualTo(fresh);

        putShop(id, body("name", "Explicit Slug Kitchen", "slug", fresh)).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(storedSlug(id)).as("its own slug is not 'taken'").isEqualTo(fresh);
    }

    @Test
    void uxt028_reservedSlug_isTheExistingTypedRefusal_andChangesNothing() throws Exception {
        String id = createShopReturningId("Reserved Slug Kitchen", null);
        String before = storedSlug(id);
        putShop(id, body("name", "Reserved Slug Kitchen", "slug", "orders"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/reserved-shop-slug"));
        org.assertj.core.api.Assertions.assertThat(storedSlug(id)).isEqualTo(before);
    }

    @Test
    void uxt028_slugAnotherShopHolds_is409ShopSlugTaken_onUpdateAndCreate() throws Exception {
        String held = "held-" + UUID.randomUUID().toString().substring(0, 8);
        createShopReturningId("Holder Kitchen", held);
        String id = createShopReturningId("Taker Kitchen", null);
        String before = storedSlug(id);

        putShop(id, body("name", "Taker Kitchen", "slug", held))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/shop-slug-taken"))
                .andExpect(jsonPath("$.code").value("SHOP_SLUG_TAKEN"))
                .andExpect(jsonPath("$.slug").value(held));
        org.assertj.core.api.Assertions.assertThat(storedSlug(id)).isEqualTo(before);

        mockMvc.perform(post("/api/v1/shops")
                        .with(adminJwt())
                        .header("X-Tenant-Id", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("name", "Second Taker", "slug", held))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/shop-slug-taken"));
    }

    @Test
    void uxt028_malformedOrOverlongSlug_is400OnTheSlugField_beforeAnyGate() throws Exception {
        String id = createShopReturningId("Shape Kitchen", null);
        String before = storedSlug(id);
        for (String bad : java.util.List.of("New-Slug", "new_slug", "new--slug", "-new", "new-", "new slug",
                "orders!", "a".repeat(101))) {
            putShop(id, body("name", "Shape Kitchen", "slug", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/validation"))
                    .andExpect(jsonPath("$.errors.slug").exists());
        }
        org.assertj.core.api.Assertions.assertThat(storedSlug(id)).isEqualTo(before);
        // The boundary itself is accepted: exactly 100 lower-case characters.
        putShop(id, body("name", "Shape Kitchen", "slug", "b".repeat(100))).andExpect(status().isOk());
    }
}
