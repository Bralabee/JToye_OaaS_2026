package uk.jtoye.core.shop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 39-09 (RWO-028, owner ruling 2026-10-10): old-slug 301 aliases ({@code V86__shop_slug_alias.sql}).
 *
 * <p>Proves under {@code NOSUPERUSER NOBYPASSRLS} that:
 * <ol>
 *   <li>A deliberate shop web-address change records the previous slug in {@code shop_slug_alias},
 *       and anonymous requests to {@code GET /api/v1/public/shops/{oldSlug}} and
 *       {@code GET /public/shops/{oldSlug}} return HTTP 301 Moved Permanently pointing to the
 *       current canonical slug when the shop is published.</li>
 *   <li>Multi-hop renames ({@code A -> B -> C}) resolve every historical alias directly to {@code C}
 *       in a single hop.</li>
 *   <li>Renaming back to a shop's own previous slug ({@code A -> B -> A}) deletes the self-alias row
 *       for {@code A} so no redirect cycle exists.</li>
 *   <li>An unpublished shop's alias returns HTTP 404 Not Found and never discloses the shop's
 *       existence or current slug.</li>
 *   <li>Another shop (in the same tenant or a different tenant) cannot claim a slug held in
 *       {@code shop_slug_alias} on create or update — refused 409 {@code shop-slug-taken}.</li>
 *   <li>Deleting a shop cascades ({@code ON DELETE CASCADE}) and removes its alias rows.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ShopSlugAliasIntegrationTest {

    private static final String DOWNGRADED_APP_ROLE = "test";
    private static final AtomicBoolean DOWNGRADED = new AtomicBoolean(false);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername(DOWNGRADED_APP_ROLE)
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private ShopService shopService;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        if (DOWNGRADED.compareAndSet(false, true)) {
            jdbc.execute("ALTER ROLE \"" + DOWNGRADED_APP_ROLE + "\" NOSUPERUSER NOBYPASSRLS");
        }
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = ?", Boolean.class, DOWNGRADED_APP_ROLE))
                .as("PRECONDITION: app role must be NOSUPERUSER so FORCE RLS is genuinely enforced")
                .isFalse();

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?), (?, ?)",
                tenantA, "Tenant A " + tenantA, tenantB, "Tenant B " + tenantB);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j
                        .subject(UUID.randomUUID().toString())
                        .claim("email", "operator@example.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"));
    }

    private UUID createShop(UUID tenantId, String name, String slug, boolean published) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        if (slug != null) {
            payload.put("slug", slug);
        }
        String response = mockMvc.perform(post("/api/v1/shops")
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID shopId = UUID.fromString(jsonMapper.readTree(response).path("id").asString());
        if (published) {
            TenantContext.set(tenantId);
            try {
                shopService.setPublished(shopId, true);
            } finally {
                TenantContext.clear();
            }
        }
        return shopId;
    }

    @Test
    @DisplayName("Renaming a published shop records the old slug in shop_slug_alias and returns HTTP 301 on both public prefixes")
    void renamePublishedShop_recordsOldSlugInAliasTable_andPublicEndpointsReturn301ToCanonicalSlug() throws Exception {
        String oldSlug = "brixton-old-" + UUID.randomUUID().toString().substring(0, 8);
        String newSlug = "brixton-new-" + UUID.randomUUID().toString().substring(0, 8);
        UUID shopId = createShop(tenantA, "Brixton Kitchen", oldSlug, true);

        mockMvc.perform(put("/api/v1/shops/" + shopId)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("name", "Brixton Kitchen", "slug", newSlug))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(newSlug));

        UUID recordedShopId = jdbc.queryForObject(
                "SELECT shop_id FROM shop_slug_alias WHERE old_slug = ?", UUID.class, oldSlug);
        assertThat(recordedShopId).isEqualTo(shopId);

        mockMvc.perform(get("/api/v1/public/shops/" + oldSlug))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/api/v1/public/shops/" + newSlug))
                .andExpect(header().string("X-Shop-Canonical-Slug", newSlug));

        mockMvc.perform(get("/public/shops/" + oldSlug))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/public/shops/" + newSlug))
                .andExpect(header().string("X-Shop-Canonical-Slug", newSlug));

        mockMvc.perform(get("/api/v1/public/shops/" + newSlug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(newSlug))
                .andExpect(jsonPath("$.name").value("Brixton Kitchen"));
    }

    @Test
    @DisplayName("Multi-hop rename A -> B -> C resolves every historical alias directly to C in a single hop")
    void multiHopRename_resolvesEveryHistoricalAliasDirectlyToCurrentCanonicalSlugInOneHop() throws Exception {
        String slugA = "hop-a-" + UUID.randomUUID().toString().substring(0, 8);
        String slugB = "hop-b-" + UUID.randomUUID().toString().substring(0, 8);
        String slugC = "hop-c-" + UUID.randomUUID().toString().substring(0, 8);
        UUID shopId = createShop(tenantA, "Hop Kitchen", slugA, true);

        mockMvc.perform(put("/api/v1/shops/" + shopId)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("name", "Hop Kitchen", "slug", slugB))))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/shops/" + shopId)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("name", "Hop Kitchen", "slug", slugC))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/public/shops/" + slugA))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/api/v1/public/shops/" + slugC))
                .andExpect(header().string("X-Shop-Canonical-Slug", slugC));

        mockMvc.perform(get("/api/v1/public/shops/" + slugB))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/api/v1/public/shops/" + slugC))
                .andExpect(header().string("X-Shop-Canonical-Slug", slugC));
    }

    @Test
    @DisplayName("Renaming back to a shop's own previous slug A -> B -> A removes A from shop_slug_alias so no redirect loop exists")
    void renameBackToPreviousSlug_deletesSelfAliasAndRedirectsIntermediateSlugsToReclaimedSlug() throws Exception {
        String slugA = "cycle-a-" + UUID.randomUUID().toString().substring(0, 8);
        String slugB = "cycle-b-" + UUID.randomUUID().toString().substring(0, 8);
        UUID shopId = createShop(tenantA, "Cycle Kitchen", slugA, true);

        mockMvc.perform(put("/api/v1/shops/" + shopId)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("name", "Cycle Kitchen", "slug", slugB))))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/shops/" + shopId)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("name", "Cycle Kitchen", "slug", slugA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slugA));

        Integer countA = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shop_slug_alias WHERE old_slug = ?", Integer.class, slugA);
        Integer countB = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shop_slug_alias WHERE old_slug = ?", Integer.class, slugB);
        assertThat(countA).as("reclaimed slug A is deleted from shop_slug_alias").isZero();
        assertThat(countB).as("intermediate slug B is recorded in shop_slug_alias").isEqualTo(1);

        mockMvc.perform(get("/api/v1/public/shops/" + slugA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slugA));

        mockMvc.perform(get("/api/v1/public/shops/" + slugB))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/api/v1/public/shops/" + slugA))
                .andExpect(header().string("X-Shop-Canonical-Slug", slugA));
    }

    @Test
    @DisplayName("An unpublished shop's alias returns 404 Not Found and never leaks the shop's existence or current slug")
    void unpublishedShopAlias_returns404_andNeverDisclosesCanonicalSlug() throws Exception {
        String oldSlug = "unpub-old-" + UUID.randomUUID().toString().substring(0, 8);
        String newSlug = "unpub-new-" + UUID.randomUUID().toString().substring(0, 8);
        UUID shopId = createShop(tenantA, "Draft Kitchen", oldSlug, false);

        mockMvc.perform(put("/api/v1/shops/" + shopId)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("name", "Draft Kitchen", "slug", newSlug))))
                .andExpect(status().isOk());

        Integer aliasCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shop_slug_alias WHERE old_slug = ?", Integer.class, oldSlug);
        assertThat(aliasCount).isEqualTo(1);

        mockMvc.perform(get("/api/v1/public/shops/" + oldSlug))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().doesNotExist("X-Shop-Canonical-Slug"));
    }

    @Test
    @DisplayName("A retired slug in shop_slug_alias cannot be claimed by another shop in the same or another tenant (409 shop-slug-taken)")
    void retiredSlugHeldByAnotherShop_isRefused409ShopSlugTaken_acrossSameAndDifferentTenants() throws Exception {
        String retiredSlug = "retired-" + UUID.randomUUID().toString().substring(0, 8);
        String currentSlug = "current-" + UUID.randomUUID().toString().substring(0, 8);
        UUID holderId = createShop(tenantA, "Holder Shop", retiredSlug, false);

        mockMvc.perform(put("/api/v1/shops/" + holderId)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("name", "Holder Shop", "slug", currentSlug))))
                .andExpect(status().isOk());

        UUID sameTenantOtherShop = createShop(tenantA, "Same Tenant Other", null, false);
        mockMvc.perform(put("/api/v1/shops/" + sameTenantOtherShop)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("name", "Same Tenant Other", "slug", retiredSlug))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/shop-slug-taken"))
                .andExpect(jsonPath("$.code").value("SHOP_SLUG_TAKEN"))
                .andExpect(jsonPath("$.slug").value(retiredSlug));

        mockMvc.perform(post("/api/v1/shops")
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantB.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("name", "Cross Tenant Create", "slug", retiredSlug))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/shop-slug-taken"))
                .andExpect(jsonPath("$.code").value("SHOP_SLUG_TAKEN"))
                .andExpect(jsonPath("$.slug").value(retiredSlug));

        UUID tenantBShop = createShop(tenantB, "Cross Tenant Update", null, false);
        mockMvc.perform(put("/api/v1/shops/" + tenantBShop)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantB.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("name", "Cross Tenant Update", "slug", retiredSlug))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/shop-slug-taken"))
                .andExpect(jsonPath("$.code").value("SHOP_SLUG_TAKEN"))
                .andExpect(jsonPath("$.slug").value(retiredSlug));
    }

    @Test
    @DisplayName("Deleting a shop cascades (ON DELETE CASCADE) and removes its shop_slug_alias rows")
    void deletingShop_cascadesAndRemovesItsSlugAliases() throws Exception {
        String oldSlug = "cascade-old-" + UUID.randomUUID().toString().substring(0, 8);
        String newSlug = "cascade-new-" + UUID.randomUUID().toString().substring(0, 8);
        UUID shopId = createShop(tenantA, "Cascade Shop", oldSlug, false);

        mockMvc.perform(put("/api/v1/shops/" + shopId)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("name", "Cascade Shop", "slug", newSlug))))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_slug_alias WHERE old_slug = ?", Integer.class, oldSlug))
                .isEqualTo(1);

        mockMvc.perform(delete("/api/v1/shops/" + shopId)
                        .with(adminJwt())
                        .header("X-Tenant-Id", tenantA.toString()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_slug_alias WHERE old_slug = ?", Integer.class, oldSlug))
                .as("ON DELETE CASCADE removes alias rows when the owning shop is deleted")
                .isZero();
    }
}
