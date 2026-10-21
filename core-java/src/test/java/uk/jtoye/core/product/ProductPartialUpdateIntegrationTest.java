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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Partial updates of a product: what an omitted field means.
 *
 * <p><b>QA-council cluster P1 (API-1 + API-3 rider).</b> {@code PUT /products/{id}} reuses
 * {@link CreateProductRequest} for a partial edit, and {@link ProductMapper#updateEntity} used to have no
 * {@code NullValuePropertyMappingStrategy.IGNORE}: any field the edit form omitted arrived as {@code null}
 * and MapStruct wrote it onto the entity, wiping fields the vendor never touched (and, for
 * {@code displayOrder}/{@code available}/{@code featured}, a NOT NULL DEFAULT column — SQLState 23502). The
 * adjudicated semantics is PARTIAL MERGE: an omitted/null field preserves the existing value, exactly
 * {@code ShopMapper.updateEntity}'s {@code @BeanMapping} for QA-council BE-02. {@code vatRate} needed its DTO
 * default removed so an omitted field actually arrives {@code null}.
 *
 * <p><b>Phase 37-39 (RWO-016 / UXT-016, P0).</b> {@code quantityInStock} used to be the one exception
 * ({@code SET_TO_NULL}), because the dashboard sent {@code trackInventory ? qty : null} and a DTO cannot tell an
 * absent field from an explicit null. So an integrator's partial PUT that did not mention stock (an EPOS
 * changing a title or a price) silently turned stock tracking OFF. The persona run measured it. The rule now:
 * <ul>
 *   <li>{@code trackInventory} absent/null: stock and tracking are kept; a {@code quantityInStock} that IS sent
 *       sets the count (and so turns tracking on);</li>
 *   <li>{@code trackInventory: false}: tracking stops and the stored count is cleared;</li>
 *   <li>{@code trackInventory: true}: tracking is on and {@code quantityInStock} is required (typed 400 when
 *       absent).</li>
 * </ul>
 *
 * <p>Real Postgres 15 (Testcontainers) and the real {@link ShopAccessService} with strict scoping ON: the PUT
 * arms go through HTTP as a shop manager of the product's own shop, with the catalogue write scope — the
 * integrator's real path. The bootstrap role is a SUPERUSER, so these arms prove the application contract and
 * the shop gate, not RLS. The legacy QA-council arms call the service directly as a realm admin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class ProductPartialUpdateIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String PRODUCTS = "/api/v1/products/";
    private static final String VALIDATION = "https://jtoye.uk/errors/validation";
    private static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";
    private static final String FORBIDDEN = "https://jtoye.uk/errors/forbidden";
    /** Every allergen fact on the row, plus the fields a stock/availability patch must never touch. */
    private static final String ALLERGEN_AND_CORE_COLUMNS = "allergen_mask, allergen_spans::text AS allergen_spans, "
            + "may_contain_mask, ingredients_text, title, price_pennies, vat_rate, shop_id";

    @Autowired private ProductService productService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private ShopAccessService shopAccessService;

    private Object bootedStrictScoping;
    private UUID tenant;
    private String suffix;
    private UUID shopA;
    private UUID managerOfA;
    private UUID shopB;
    private UUID managerOfB;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
        StrictScopingGuard.set(shopAccessService, true);

        tenant = UUID.randomUUID();
        suffix = tenant.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())",
                tenant, "37-39 Partial Update Tenant " + suffix);
        shopA = seedShop("pu-a-3739-" + suffix, "37-39 Shop A " + suffix);
        managerOfA = UUID.randomUUID();
        grant(managerOfA, shopA, "SHOP_MANAGER");
        shopB = seedShop("pu-b-3739-" + suffix, "37-39 Shop B " + suffix);
        managerOfB = UUID.randomUUID();
        grant(managerOfB, shopB, "SHOP_MANAGER");
    }

    @AfterEach
    void tearDown() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // 37-39 Task 1 (UXT-016): a PUT that does not mention stock keeps tracking on
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UXT-016: a tracked product (12 in stock) PUT with a title change only is still tracked with 12 in stock")
    void putWithoutStockFieldsKeepsTrackingAndCount() throws Exception {
        UUID product = seedProduct(shopA, "PU-TRACK-" + suffix, 12);

        Map<String, Object> body = requiredBody("PU-TRACK-" + suffix);
        body.put("title", "Suya (renamed by the EPOS)");
        MvcResult result = putProduct(managerOfA, product, body);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(stockOf(product))
                .as("a PUT that never mentions stock must not turn stock tracking off (it used to null the count)")
                .isEqualTo(12);
        assertThat(titleOf(product)).isEqualTo("Suya (renamed by the EPOS)");
    }

    @Test
    @DisplayName("UXT-016: PUT {trackInventory: false} stops tracking and clears the stored count")
    void putTrackInventoryFalseStopsTrackingAndClearsStock() throws Exception {
        UUID product = seedProduct(shopA, "PU-OFF-" + suffix, 12);

        Map<String, Object> body = requiredBody("PU-OFF-" + suffix);
        body.put("trackInventory", false);
        MvcResult result = putProduct(managerOfA, product, body);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(stockOf(product)).as("trackInventory false must stop tracking").isNull();
    }

    @Test
    @DisplayName("UXT-016: PUT {trackInventory: true} without quantityInStock is a typed 400 naming the field, and nothing changes")
    void putTrackInventoryTrueWithoutQuantityIsTyped400() throws Exception {
        UUID product = seedProduct(shopA, "PU-ON-NOQTY-" + suffix, null);

        Map<String, Object> body = requiredBody("PU-ON-NOQTY-" + suffix);
        body.put("title", "Must not be saved");
        body.put("trackInventory", true);
        MvcResult result = putProduct(managerOfA, product, body);

        JsonNode problem = json(result, 400);
        assertThat(problem.path("type").asString()).as(text(result)).isEqualTo(VALIDATION);
        assertThat(problem.path("errors").has("quantityInStock"))
                .as("the 400 names quantityInStock: %s", text(result)).isTrue();
        assertThat(stockOf(product)).as("nothing was saved").isNull();
        assertThat(titleOf(product)).as("nothing was saved").isNotEqualTo("Must not be saved");
    }

    @Test
    @DisplayName("UXT-016: PUT {trackInventory: true, quantityInStock: 5} tracks 5")
    void putTrackInventoryTrueWithQuantityTracks() throws Exception {
        UUID product = seedProduct(shopA, "PU-ON-" + suffix, null);

        Map<String, Object> body = requiredBody("PU-ON-" + suffix);
        body.put("trackInventory", true);
        body.put("quantityInStock", 5);
        MvcResult result = putProduct(managerOfA, product, body);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(stockOf(product)).isEqualTo(5);
    }

    @Test
    @DisplayName("UXT-016: PUT {trackInventory: false, quantityInStock: 5} contradicts itself: typed 400, nothing changes")
    void putTrackInventoryFalseWithQuantityIsTyped400() throws Exception {
        UUID product = seedProduct(shopA, "PU-OFF-QTY-" + suffix, 12);

        Map<String, Object> body = requiredBody("PU-OFF-QTY-" + suffix);
        body.put("trackInventory", false);
        body.put("quantityInStock", 5);
        MvcResult result = putProduct(managerOfA, product, body);

        JsonNode problem = json(result, 400);
        assertThat(problem.path("type").asString()).as(text(result)).isEqualTo(VALIDATION);
        assertThat(problem.path("errors").has("quantityInStock")).as(text(result)).isTrue();
        assertThat(stockOf(product)).as("nothing was saved").isEqualTo(12);
    }

    @Test
    @DisplayName("UXT-016: PUT with quantityInStock and no trackInventory sets the count (the pre-37-39 client shape still works)")
    void putQuantityWithoutTrackInventorySetsCount() throws Exception {
        UUID product = seedProduct(shopA, "PU-QTY-" + suffix, 12);

        Map<String, Object> body = requiredBody("PU-QTY-" + suffix);
        body.put("quantityInStock", 7);
        MvcResult result = putProduct(managerOfA, product, body);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(stockOf(product)).isEqualTo(7);
    }

    // ------------------------------------------------------------------
    // 37-39 Task 2 (UXT-016): PATCH /products/{id} — stock and availability only, never allergen data
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UXT-016: PATCH {available: false} makes the product unavailable and leaves every allergen fact unchanged by SQL")
    void patchAvailabilityLeavesAllergenDataUnchanged() throws Exception {
        UUID product = seedProduct(shopA, "PA-AVAIL-" + suffix, 12);
        Map<String, Object> before = allergenAndCoreRow(product);

        MvcResult result = patchProduct(userJwt(managerOfA), product, Map.of("available", false));

        JsonNode dto = json(result, 200);
        assertThat(dto.path("available").asBoolean()).as(text(result)).isFalse();
        assertThat(availableOf(product)).as("unavailable by SQL").isFalse();
        assertThat(allergenAndCoreRow(product))
                .as("allergen mask, emphasis spans, may-contain mask, ingredients, title, price, VAT and shop "
                        + "are exactly what they were before the PATCH")
                .isEqualTo(before);
        assertThat(stockOf(product)).as("a PATCH that does not mention stock keeps it").isEqualTo(12);
    }

    @Test
    @DisplayName("UXT-016: PATCH {allergenMask: 0} is a typed 400 naming allergenMask, and the declared mask stays 64 by SQL")
    void patchRefusesAllergenMaskByName() throws Exception {
        UUID product = seedProduct(shopA, "PA-MASK-" + suffix, 12);
        Map<String, Object> before = allergenAndCoreRow(product);

        MvcResult result = patchProduct(userJwt(managerOfA), product, Map.of("allergenMask", 0));

        JsonNode problem = json(result, 400);
        assertThat(problem.path("type").asString()).as(text(result)).isEqualTo(VALIDATION);
        assertThat(problem.path("errors").has("allergenMask"))
                .as("the 400 names the property it refused: %s", text(result)).isTrue();
        assertThat(allergenAndCoreRow(product)).as("nothing changed").isEqualTo(before);
    }

    @Test
    @DisplayName("UXT-016: PATCH mixing an accepted field with ingredientsText and mayContainMask is refused whole: 400 naming both, nothing changes")
    void patchWithAnyRefusedPropertyChangesNothing() throws Exception {
        UUID product = seedProduct(shopA, "PA-MIX-" + suffix, 12);
        Map<String, Object> before = allergenAndCoreRow(product);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("available", false);
        body.put("ingredientsText", "Beef, yaji spice");
        body.put("mayContainMask", 0);

        MvcResult result = patchProduct(userJwt(managerOfA), product, body);

        JsonNode problem = json(result, 400);
        assertThat(problem.path("type").asString()).as(text(result)).isEqualTo(VALIDATION);
        assertThat(problem.path("errors").has("ingredientsText")).as(text(result)).isTrue();
        assertThat(problem.path("errors").has("mayContainMask")).as(text(result)).isTrue();
        assertThat(availableOf(product)).as("the accepted field was not applied either").isTrue();
        assertThat(allergenAndCoreRow(product)).as("nothing changed").isEqualTo(before);
    }

    @Test
    @DisplayName("UXT-016: PATCH by a manager of another shop is the typed 403 and nothing changes")
    void patchByManagerOfAnotherShopIsForbidden() throws Exception {
        UUID product = seedProduct(shopA, "PA-XSHOP-" + suffix, 12);

        MvcResult result = patchProduct(userJwt(managerOfB), product, Map.of("available", false));

        JsonNode problem = json(result, 403);
        assertThat(problem.path("type").asString()).as(text(result)).isEqualTo(SHOP_ACCESS_DENIED);
        assertThat(availableOf(product)).as("nothing changed").isTrue();
    }

    @Test
    @DisplayName("UXT-016: PATCH without the catalog:write scope is the typed 403 and nothing changes")
    void patchWithoutCatalogWriteScopeIsForbidden() throws Exception {
        UUID product = seedProduct(shopA, "PA-SCOPE-" + suffix, 12);
        UUID t = tenant;
        RequestPostProcessor noScope = jwt().jwt(j -> j.subject(managerOfA.toString()).claim("tenant_id", t.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_catalog:read"));

        MvcResult result = patchProduct(noScope, product, Map.of("available", false));

        JsonNode problem = json(result, 403);
        assertThat(problem.path("type").asString()).as(text(result)).isEqualTo(FORBIDDEN);
        assertThat(availableOf(product)).as("nothing changed").isTrue();
    }

    @Test
    @DisplayName("UXT-016: PATCH stock follows the trackInventory rule: a quantity sets it, false clears it, true needs a quantity")
    void patchStockFollowsTheTrackInventoryRule() throws Exception {
        UUID product = seedProduct(shopA, "PA-STOCK-" + suffix, 12);

        MvcResult set = patchProduct(userJwt(managerOfA), product, Map.of("quantityInStock", 3));
        assertThat(json(set, 200).path("quantityInStock").asInt()).as(text(set)).isEqualTo(3);
        assertThat(stockOf(product)).isEqualTo(3);

        MvcResult off = patchProduct(userJwt(managerOfA), product, Map.of("trackInventory", false));
        assertThat(off.getResponse().getStatus()).as(text(off)).isEqualTo(200);
        assertThat(stockOf(product)).as("trackInventory false stops tracking").isNull();

        MvcResult onWithoutQty = patchProduct(userJwt(managerOfA), product, Map.of("trackInventory", true));
        JsonNode problem = json(onWithoutQty, 400);
        assertThat(problem.path("errors").has("quantityInStock")).as(text(onWithoutQty)).isTrue();
        assertThat(stockOf(product)).as("nothing changed").isNull();

        MvcResult on = patchProduct(userJwt(managerOfA), product, Map.of("trackInventory", true, "quantityInStock", 4));
        assertThat(on.getResponse().getStatus()).as(text(on)).isEqualTo(200);
        assertThat(stockOf(product)).isEqualTo(4);

        MvcResult negative = patchProduct(userJwt(managerOfA), product, Map.of("quantityInStock", -1));
        JsonNode negativeProblem = json(negative, 400);
        assertThat(negativeProblem.path("errors").has("quantityInStock")).as(text(negative)).isTrue();
        assertThat(stockOf(product)).as("nothing changed").isEqualTo(4);
    }

    @Test
    @DisplayName("UXT-016: the same PATCH twice gives the same product (absolute values, idempotent by construction)")
    void patchIsIdempotent() throws Exception {
        UUID product = seedProduct(shopA, "PA-IDEM-" + suffix, 12);
        Map<String, Object> body = Map.of("available", false, "quantityInStock", 2);

        MvcResult first = patchProduct(userJwt(managerOfA), product, body);
        JsonNode one = json(first, 200);
        Map<String, Object> afterFirst = allergenAndCoreRow(product);
        MvcResult second = patchProduct(userJwt(managerOfA), product, body);
        JsonNode two = json(second, 200);

        assertThat(two.path("available").asBoolean()).isEqualTo(one.path("available").asBoolean()).isFalse();
        assertThat(two.path("quantityInStock").asInt()).isEqualTo(one.path("quantityInStock").asInt()).isEqualTo(2);
        assertThat(allergenAndCoreRow(product)).isEqualTo(afterFirst);
        assertThat(stockOf(product)).isEqualTo(2);
        assertThat(availableOf(product)).isFalse();
    }

    // ------------------------------------------------------------------
    // QA-council P1 (API-1): omitted boxed fields on NOT-NULL-DEFAULT columns are preserved
    // ------------------------------------------------------------------

    @Test
    void putOmittingDisplayOrderAvailableFeaturedPreservesExistingValues() {
        ProductDto created = asGroupAdmin(() -> productService.createProduct(
                fullRequest("PU-SKU-1", VatRate.STANDARD, 7, false, true, 10, 3, "USE_BY")));

        CreateProductRequest edit = requiredFieldsOnly("PU-SKU-1");
        // displayOrder / available / featured / quantityInStock / shelfLifeDays / durabilityType
        // all deliberately left unset below — the exact shape of an edit-form PUT that never
        // renders those controls.

        ProductDto updated = asGroupAdmin(() -> productService.updateProduct(created.getId(), edit));

        assertThat(updated.getDisplayOrder()).as("displayOrder must survive an edit that omits it")
                .isEqualTo(7);
        assertThat(updated.getAvailable()).as("available must survive an edit that omits it")
                .isFalse();
        assertThat(updated.getFeatured()).as("featured must survive an edit that omits it")
                .isTrue();
    }

    @Test
    void putOmittingShelfLifeDaysAndDurabilityTypePreservesPpdsFields() {
        ProductDto created = asGroupAdmin(() -> productService.createProduct(
                fullRequest("PU-SKU-2", VatRate.STANDARD, 0, true, false, 10, 5, "BEST_BEFORE")));

        CreateProductRequest edit = requiredFieldsOnly("PU-SKU-2");

        ProductDto updated = asGroupAdmin(() -> productService.updateProduct(created.getId(), edit));

        assertThat(updated.getShelfLifeDays())
                .as("PPDS shelf-life must survive an edit form that never renders it")
                .isEqualTo(5);
        assertThat(updated.getDurabilityType())
                .as("PPDS durability type must survive an edit form that never renders it")
                .isEqualTo("BEST_BEFORE");
    }

    // ------------------------------------------------------------------
    // QA-council P1: vatRate — an omitted field must preserve, not reset to STANDARD
    // ------------------------------------------------------------------

    @Test
    void putWithoutVatRatePreservesZeroRatedProduct() {
        ProductDto created = asGroupAdmin(() -> productService.createProduct(
                fullRequest("PU-SKU-4", VatRate.ZERO, 0, true, false, 10, null, null)));
        assertThat(created.getVatRate()).isEqualTo(VatRate.ZERO);

        CreateProductRequest edit = requiredFieldsOnly("PU-SKU-4");
        // vatRate deliberately left unset — the edit form never renders it.

        ProductDto updated = asGroupAdmin(() -> productService.updateProduct(created.getId(), edit));

        assertThat(updated.getVatRate())
                .as("omitting vatRate on an edit must not silently reset a ZERO-rated product "
                        + "to STANDARD")
                .isEqualTo(VatRate.ZERO);
    }

    @Test
    void createWithoutVatRateDefaultsToStandard() {
        CreateProductRequest create = requiredFieldsOnly("PU-SKU-5");
        create.setShopId(shopA); // #959: a create names its shop
        // vatRate deliberately left unset on a CREATE (not an edit) — must still default.

        ProductDto created = asGroupAdmin(() -> productService.createProduct(create));

        assertThat(created.getVatRate())
                .as("a brand-new product must never be silently created with a null VAT rate")
                .isEqualTo(VatRate.STANDARD);
    }

    // ---- helpers -------------------------------------------------------

    /**
     * Runs a direct service call as a realm admin of the tenant (an implicit Group admin). Sets and clears the
     * tenant and the security context around the call, because a MockMvc request in the same test clears both
     * on the way out.
     */
    private <T> T asGroupAdmin(Supplier<T> call) {
        Jwt token = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", tenant.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        TenantContext.set(tenant);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token, List.of(
                new SimpleGrantedAuthority("ROLE_admin"), new SimpleGrantedAuthority("SCOPE_catalog:write"))));
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }

    private MvcResult putProduct(UUID user, UUID product, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put(PRODUCTS + product).with(userJwt(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult patchProduct(RequestPostProcessor caller, UUID product, Map<String, Object> body) throws Exception {
        return mockMvc.perform(patch(PRODUCTS + product).with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    private Map<String, Object> allergenAndCoreRow(UUID product) {
        return jdbcTemplate.queryForMap("SELECT " + ALLERGEN_AND_CORE_COLUMNS + " FROM products WHERE id = ?", product);
    }

    private Boolean availableOf(UUID product) {
        return jdbcTemplate.queryForObject("SELECT available FROM products WHERE id = ?", Boolean.class, product);
    }

    /** The fields bean validation requires on every PUT — the shape of an integrator's partial edit. */
    private Map<String, Object> requiredBody(String sku) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", sku);
        body.put("title", "Suya");
        body.put("ingredientsText", "Beef, **milk**, yaji spice");
        body.put("allergenMask", 64);
        body.put("pricePennies", 900);
        return body;
    }

    /** Only the fields validation actually requires on every PUT — the omitted-field shape. */
    private CreateProductRequest requiredFieldsOnly(String sku) {
        CreateProductRequest req = new CreateProductRequest();
        req.setSku(sku);
        req.setTitle("Test product " + sku);
        req.setIngredientsText("Water");
        req.setAllergenMask(0);
        req.setPricePennies(500L);
        return req;
    }

    private CreateProductRequest fullRequest(String sku, VatRate vatRate, int displayOrder,
            boolean available, boolean featured, Integer quantityInStock, Integer shelfLifeDays,
            String durabilityType) {
        CreateProductRequest req = requiredFieldsOnly(sku);
        req.setShopId(shopA); // #959: a create names its shop (the edit builder deliberately does not)
        req.setVatRate(vatRate);
        req.setDisplayOrder(displayOrder);
        req.setAvailable(available);
        req.setFeatured(featured);
        req.setQuantityInStock(quantityInStock);
        req.setShelfLifeDays(shelfLifeDays);
        req.setDurabilityType(durabilityType);
        return req;
    }

    private UUID seedShop(String slug, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                id, tenant, name, slug);
        return id;
    }

    /** Suya with a declared allergen (milk, 64), stored emphasis spans, a may-contain mask (1024) and a stock count. */
    private UUID seedProduct(UUID shop, String sku, Integer stock) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO products (id, tenant_id, shop_id, sku, title, ingredients_text, "
                        + "allergen_mask, allergen_spans, may_contain_mask, price_pennies, vat_rate, "
                        + "quantity_in_stock, available) "
                        + "VALUES (?, ?, ?, ?, 'Suya', 'Beef, **milk**, yaji spice', 64, "
                        + "'[{\"start\": 6, \"end\": 10}]'::jsonb, 1024, 900, 'STANDARD', ?, true)",
                id, tenant, shop, sku, stock);
        return id;
    }

    private void grant(UUID userId, UUID shop, String role) {
        jdbcTemplate.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at, grant_source) "
                + "VALUES (?, ?, ?, ?, ?, now(), 'OPERATOR')", UUID.randomUUID(), tenant, userId, shop, role);
    }

    private Integer stockOf(UUID product) {
        return jdbcTemplate.queryForObject("SELECT quantity_in_stock FROM products WHERE id = ?", Integer.class, product);
    }

    private String titleOf(UUID product) {
        return jdbcTemplate.queryForObject("SELECT title FROM products WHERE id = ?", String.class, product);
    }

    /** A vendor user with no realm role: what they may write comes from shop_staff alone. */
    private RequestPostProcessor userJwt(UUID subject) {
        UUID t = tenant;
        return jwt().jwt(j -> j.subject(subject.toString()).claim("tenant_id", t.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_catalog:write"));
    }

    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(expectedStatus);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String text(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }
}
