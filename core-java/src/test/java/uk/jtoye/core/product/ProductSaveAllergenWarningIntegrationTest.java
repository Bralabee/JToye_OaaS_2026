package uk.jtoye.core.product;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.JwtRolesAndScopesConverter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.ShopGrants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * #787 (P0, D-09) backend acceptance: a product whose own ingredients name an allergen the
 * vendor did not tick must not pass silently as allergen-free.
 *
 * <p>The persona typed {@code rice, butter (MILK), pepper}, ticked nothing, and the storefront
 * said "No allergens". The save must SUCCEED (D-09: the vendor is warned, never blocked), the
 * response must carry a typed warning naming Milk, the stored {@code allergen_mask} must be
 * exactly what the vendor sent (the advisory flag is never merged into the declaration), and
 * the public storefront DTO must carry the disagreement so no surface renders "No allergens".
 *
 * <p>Every arm goes through the real HTTP endpoints on a Testcontainers Postgres (Flyway
 * schema), with a vendor token carrying {@code catalog:read catalog:write} mapped through the
 * production {@link JwtRolesAndScopesConverter}. Not {@code @Transactional}: each request
 * commits, so the SQL read-back sees what the API wrote.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ProductSaveAllergenWarningIntegrationTest {

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
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JsonMapper jsonMapper;

    /** Dedicated tenant and slug so a parallel fork's fixtures cannot collide. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000311061");
    private static final String SHOP_SLUG = "shop-311-06-allergen-warning";

    /** The persona's exact ingredients text (#787 repro). */
    private static final String REPRO_INGREDIENTS = "rice, butter (MILK), pepper";
    private static final int MILK = 1 << 6;
    private static final String WARNING_CODE = "UNDECLARED_INGREDIENT_ALLERGEN";

    private UUID shopId;
    /**
     * The vendor editing this shop's catalogue: an explicit OPERATOR SHOP_MANAGER on {@link #shopId}
     * (product create/update requires SHOP_MANAGER on the body's shop; reads need STAFF, which
     * SHOP_MANAGER satisfies). Phase 37-03 (D-06): not the day-one implicit GROUP_ADMIN.
     */
    private UUID vendorSub;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "31.1-06 Allergen Warning Tenant");
        shopId = seedShopIdempotent();
        vendorSub = UUID.randomUUID();
        ShopGrants.grantOperator(jdbcTemplate, TENANT_ID, vendorSub, shopId, "SHOP_MANAGER",
                "vendor-" + vendorSub + "@example.com");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // #787: the save succeeds, the response names MILK, the declaration is untouched
    // ------------------------------------------------------------------
    @Test
    @DisplayName("#787: POST 'rice, butter (MILK), pepper' with mask 0 -> 201 + one UNDECLARED_INGREDIENT_ALLERGEN warning naming Milk; stored allergen_mask stays 0")
    void createWithUndeclaredCapitalisedMilk_succeedsWithTypedWarning_andStoresTheVendorMask() throws Exception {
        String sku = "SKU-31106-CREATE-" + UUID.randomUUID();

        MvcResult created = perform(post("/api/v1/products"), productBody(sku, 0));
        assertThat(created.getResponse().getStatus()).as(text(created)).isEqualTo(201);

        JsonNode body = json(created);
        UUID productId = UUID.fromString(body.get("id").asString());
        assertSingleMilkWarning(body, "create response");

        // The prohibition, at the test tier: the advisory flag never reaches the declaration.
        assertThat(storedMask(productId)).as("products.allergen_mask after a warning-returning save")
                .isEqualTo(0);

        // The storefront reads the same disagreement from the server; the browser never parses.
        JsonNode publicProduct = publicProduct(productId);
        assertThat(names(publicProduct.get("undeclaredIngredientAllergens")))
                .as("public undeclaredIngredientAllergens for %s", productId)
                .containsExactly("Milk");
    }

    // ------------------------------------------------------------------
    // Pitfall 11: every ProductDto read carries the warning, derived on read
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Pitfall 11: GET by id and the vendor product list both carry the same Milk warning, derived on read")
    void everyVendorRead_carriesTheWarning() throws Exception {
        String sku = "SKU-31106-READ-" + UUID.randomUUID();
        UUID productId = createProduct(sku, 0);

        MvcResult byId = perform(get("/api/v1/products/{id}", productId), null);
        assertThat(byId.getResponse().getStatus()).as(text(byId)).isEqualTo(200);
        assertSingleMilkWarning(json(byId), "GET by id");

        MvcResult list = perform(get("/api/v1/products").param("size", "200"), null);
        assertThat(list.getResponse().getStatus()).as(text(list)).isEqualTo(200);
        JsonNode listed = null;
        for (JsonNode p : json(list).get("content")) {
            if (productId.toString().equals(p.get("id").asString())) {
                listed = p;
            }
        }
        assertThat(listed).as("product %s in the vendor list", productId).isNotNull();
        assertSingleMilkWarning(listed, "vendor list row");
    }

    // ------------------------------------------------------------------
    // The good path: ticking Milk clears the warning everywhere
    // ------------------------------------------------------------------
    @Test
    @DisplayName("PUT the same product with Milk ticked -> 200, allergenWarnings [] and public undeclaredIngredientAllergens []")
    void updateDeclaringMilk_clearsTheWarningAndThePublicFlag() throws Exception {
        String sku = "SKU-31106-UPDATE-" + UUID.randomUUID();
        UUID productId = createProduct(sku, 0);

        MvcResult updated = perform(put("/api/v1/products/{id}", productId), productBody(sku, MILK));
        assertThat(updated.getResponse().getStatus()).as(text(updated)).isEqualTo(200);

        JsonNode body = json(updated);
        assertThat(body.has("allergenWarnings")).as("allergenWarnings present on update: %s", body).isTrue();
        assertThat(body.get("allergenWarnings").isArray()).isTrue();
        assertThat(body.get("allergenWarnings").size()).as("warnings after declaring Milk").isZero();
        assertThat(storedMask(productId)).isEqualTo(MILK);

        JsonNode publicProduct = publicProduct(productId);
        assertThat(publicProduct.has("undeclaredIngredientAllergens"))
                .as("undeclaredIngredientAllergens present: %s", publicProduct).isTrue();
        assertThat(names(publicProduct.get("undeclaredIngredientAllergens"))).isEmpty();
    }

    // ------------------------------------------------------------------
    // Edge (PGC-787 idempotency): saving the same text twice changes nothing else
    // ------------------------------------------------------------------
    @Test
    @DisplayName("PGC-787 idempotency: the same PUT twice returns the same warnings and leaves the stored mask as sent")
    void sameSaveTwice_returnsTheSameWarnings_andChangesNothingElse() throws Exception {
        String sku = "SKU-31106-TWICE-" + UUID.randomUUID();
        UUID productId = createProduct(sku, 0);

        MvcResult first = perform(put("/api/v1/products/{id}", productId), productBody(sku, 0));
        MvcResult second = perform(put("/api/v1/products/{id}", productId), productBody(sku, 0));
        assertThat(first.getResponse().getStatus()).as(text(first)).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).as(text(second)).isEqualTo(200);

        assertSingleMilkWarning(json(first), "first PUT");
        assertThat(json(second).get("allergenWarnings"))
                .as("second PUT warnings equal the first")
                .isEqualTo(json(first).get("allergenWarnings"));
        assertThat(storedMask(productId)).isEqualTo(0);
    }

    // ---- assertions ---------------------------------------------------------

    private void assertSingleMilkWarning(JsonNode product, String where) {
        assertThat(product.has("allergenWarnings")).as("%s carries allergenWarnings: %s", where, product)
                .isTrue();
        JsonNode warnings = product.get("allergenWarnings");
        assertThat(warnings.isArray()).as("%s allergenWarnings is an array", where).isTrue();
        assertThat(warnings.size()).as("%s warning count: %s", where, warnings).isEqualTo(1);
        JsonNode w = warnings.get(0);
        assertThat(w.get("code").asString()).isEqualTo(WARNING_CODE);
        assertThat(w.get("allergenBit").asInt()).isEqualTo(6);
        assertThat(w.get("allergen").asString()).isEqualTo("Milk");
        assertThat(w.get("message").asString()).contains("Milk");
    }

    // ---- requests -----------------------------------------------------------

    /** Vendor token: the granted {@link #vendorSub}, this tenant, catalog read + write, through the real converter. */
    private RequestPostProcessor vendorJwt() {
        UUID sub = vendorSub;
        return jwt()
                .jwt(j -> j.subject(sub.toString())
                        .claim("tenant_id", TENANT_ID.toString())
                        .claim("scope", "catalog:read catalog:write"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    private Map<String, Object> productBody(String sku, int allergenMask) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", sku);
        body.put("title", "Party Jollof");
        body.put("ingredientsText", REPRO_INGREDIENTS);
        body.put("allergenMask", allergenMask);
        body.put("pricePennies", 899);
        body.put("category", "Mains");
        body.put("available", true);
        body.put("shopId", shopId);
        return body;
    }

    private UUID createProduct(String sku, int mask) throws Exception {
        MvcResult created = perform(post("/api/v1/products"), productBody(sku, mask));
        assertThat(created.getResponse().getStatus()).as(text(created)).isEqualTo(201);
        return UUID.fromString(json(created).get("id").asString());
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.with(vendorJwt()).accept(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    /** The public menu (anonymous) row for {@code productId}, searched across every category. */
    private JsonNode publicProduct(UUID productId) throws Exception {
        MvcResult menu = mockMvc.perform(get("/api/v1/public/shops/{slug}/products", SHOP_SLUG)
                .accept(MediaType.APPLICATION_JSON)).andReturn();
        assertThat(menu.getResponse().getStatus()).as(text(menu)).isEqualTo(200);
        JsonNode categories = json(menu);
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode products : categories) {
            for (JsonNode p : products) {
                if (productId.toString().equals(p.get("id").asString())) {
                    matches.add(p);
                }
            }
        }
        assertThat(matches).as("product %s on the public menu %s", productId, categories).hasSize(1);
        return matches.get(0);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String text(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (Exception e) {
            return "<unreadable body: " + e + ">";
        }
    }

    private static List<String> names(JsonNode array) {
        assertThat(array).as("a JSON array of allergen names").isNotNull();
        assertThat(array.isArray()).isTrue();
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    // ---- fixtures -----------------------------------------------------------

    private Integer storedMask(UUID productId) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT allergen_mask FROM products WHERE id = ? AND tenant_id = ?",
                Integer.class, productId, TENANT_ID));
    }

    private UUID seedShopIdempotent() {
        return inTenant(() -> {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, TENANT_ID, SHOP_SLUG);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                    id, TENANT_ID, "31.1-06 Allergen Warning Shop", SHOP_SLUG);
            return id;
        });
    }

    private <T> T inTenant(java.util.function.Supplier<T> read) {
        TenantContext.set(TENANT_ID);
        try {
            return read.get();
        } finally {
            TenantContext.clear();
        }
    }
}
