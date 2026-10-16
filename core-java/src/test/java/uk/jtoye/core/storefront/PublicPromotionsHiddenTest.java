package uk.jtoye.core.storefront;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.ShopGrants;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Phase 37-35 (D-17, RWO-008 / UXT-008): no public endpoint advertises a promotion, because checkout
 * applies none.
 *
 * <p>The persona run saw a "20% OFF" badge on a shop and paid full price: promotions were display-only
 * data. {@code ShopConfigDto.activePromotions} and {@code GET /public/shops/{slug}/promotions} published
 * every active row of {@code shop_promotions}, while the order path never read the table. D-17: no claim
 * without data behind it. Both public reads now answer an empty list for every shop, with no switch that
 * turns them back on; a real promotions engine is a separate, deferred capability (CONTEXT Deferred).
 *
 * <p>What is KEPT (Incremental Betterment): the vendor's promotions. The row stays in the table and the
 * authenticated {@code GET /api/v1/promotions?shopId=} still lists it to a manager of the shop, so the
 * dashboard CRUD loses nothing and a future engine starts from the vendor's own data. The 404 for an
 * unknown or unpublished slug is kept too, so the endpoint still tells "no such shop" from "no offer".
 *
 * <p>Falsifiability: on the unfixed tree the two public arms fail on the planned assertion (the seeded
 * "20% OFF" row is returned); the vendor arm and the 404 arm pass on both trees and are the controls
 * that stop "return nothing to anyone" from passing this class. Real Postgres (Testcontainers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class PublicPromotionsHiddenTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;

    private static final String LABEL = "20% OFF everything";

    private UUID tenant;
    private UUID shop;
    private UUID promotion;
    private String slug;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "37-35 promotions tenant " + tenant);
        shop = UUID.randomUUID();
        slug = "promo-3735-" + shop.toString().substring(0, 8);
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, address, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                        + "VALUES (?, ?, now(), ?, ?, '1 Test Street, London', true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                shop, tenant, "37-35 Promotions Shop", slug);
        promotion = UUID.randomUUID();
        // An ACTIVE promotion inside its window: exactly what findActiveByShopId returned publicly.
        jdbc.update("INSERT INTO shop_promotions (id, tenant_id, shop_id, label, discount_type, discount_percent, "
                        + "  valid_from, valid_until, active, created_at) "
                        + "VALUES (?, ?, ?, ?, 'PERCENTAGE', 20, NOW() - INTERVAL '1 day', NOW() + INTERVAL '30 days', true, NOW())",
                promotion, tenant, shop, LABEL);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("UXT-008: GET /public/shops/{slug}/promotions answers [] for a shop holding an active 20% promotion")
    void publicPromotions_isEmpty_evenWithAnActivePromotion() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/public/shops/" + slug + "/promotions")).andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
        JsonNode json = jsonMapper.readTree(body);
        assertThat(json.isArray()).as(body).isTrue();
        assertThat(json.size()).as("no promotion is advertised: " + body).isZero();
        assertThat(body).as("the label never reaches the public body").doesNotContain("20%").doesNotContain(LABEL);
    }

    @Test
    @DisplayName("UXT-008: the checkout config's activePromotions is [] for the same shop, and the body names no discount")
    void publicConfig_activePromotions_isEmpty() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/public/shops/" + slug + "/config")).andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
        JsonNode promos = jsonMapper.readTree(body).path("activePromotions");
        assertThat(promos.isArray()).as("activePromotions stays an array (the frontend contract): " + body).isTrue();
        assertThat(promos.size()).as("no promotion is advertised: " + body).isZero();
        assertThat(body).as("the label never reaches the public body").doesNotContain("20%").doesNotContain(LABEL);
    }

    @Test
    @DisplayName("KEPT: the vendor's own list still shows the promotion to a manager of the shop, and the row is untouched")
    void vendorPromotions_stillListTheRow() throws Exception {
        UUID manager = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenant, manager, shop, "SHOP_MANAGER", "manager-" + manager + "@example.com");

        MvcResult result = mockMvc.perform(get("/api/v1/promotions").param("shopId", shop.toString())
                        .with(vendor(tenant, manager)))
                .andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
        JsonNode content = jsonMapper.readTree(body).path("content");
        assertThat(content.size()).as(body).isEqualTo(1);
        assertThat(content.get(0).path("id").asString()).isEqualTo(promotion.toString());
        assertThat(content.get(0).path("label").asString()).isEqualTo(LABEL);
        assertThat(jdbc.queryForObject("SELECT active FROM shop_promotions WHERE id = ?", Boolean.class, promotion))
                .as("the stored promotion is not deactivated by hiding it").isTrue();
    }

    @Test
    @DisplayName("KEPT: an unknown slug is still a 404 on the public promotions read, not an empty list")
    void publicPromotions_unknownSlug_isStill404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/public/shops/no-such-shop-" + UUID.randomUUID() + "/promotions"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(404);
    }

    private static RequestPostProcessor vendor(UUID tenant, UUID subject) {
        return jwt().jwt(j -> j.subject(subject.toString()).claim("tenant_id", tenant.toString()));
    }
}
