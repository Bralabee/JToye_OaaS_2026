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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.order.OrderService;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.SystemPrincipal;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * #858 (owner ruling 2026-11-04, persona close-out): the price of a product is editable through
 * {@code PATCH /api/v1/products/{id}} as an absolute {@code pricePennies}, so changing a price is one quick
 * edit and an EPOS or agent can do it without sending the whole product (allergens and ingredients included).
 *
 * <p>What this pins, each arm against the real endpoint on real Postgres, read back by SQL:
 * <ul>
 *   <li>the price changes, and nothing else on the row does (allergen mask, spans, may-contain, ingredients,
 *       title, VAT, shop);</li>
 *   <li>validation matches the other fields and PUT's: negative and over-limit prices are a typed 400 naming
 *       {@code pricePennies} and change nothing;</li>
 *   <li>the same shop gate as every other PATCH field (SHOP_MANAGER on the product's own shop, and the
 *       catalog:write scope);</li>
 *   <li>the change is audited (Envers {@code products_aud});</li>
 *   <li>orders already placed keep the price they were placed at (the {@code order_items} snapshot), while an
 *       order placed afterwards takes the new price;</li>
 *   <li>the same PATCH twice leaves the same product.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class ProductPricePatchIntegrationTest {

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
    /** Everything on the row a price patch must NOT touch. */
    private static final String UNTOUCHED = "allergen_mask, allergen_spans::text AS allergen_spans, may_contain_mask, "
            + "ingredients_text, title, vat_rate, shop_id, available, quantity_in_stock";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private ShopAccessService shopAccessService;
    @Autowired private OrderService orderService;

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
                tenant, "858 Price Patch Tenant " + suffix);
        shopA = seedShop("pp-a-858-" + suffix, "858 Shop A " + suffix);
        managerOfA = UUID.randomUUID();
        grant(managerOfA, shopA, "SHOP_MANAGER");
        shopB = seedShop("pp-b-858-" + suffix, "858 Shop B " + suffix);
        managerOfB = UUID.randomUUID();
        grant(managerOfB, shopB, "SHOP_MANAGER");
    }

    @AfterEach
    void tearDown() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
        TenantContext.clear();
    }

    @Test
    @DisplayName("#858: PATCH {pricePennies} changes the price and nothing else on the row, by SQL")
    void patchPriceChangesOnlyThePrice() throws Exception {
        UUID product = seedProduct(shopA, "PP-ONLY-" + suffix, 900);
        Map<String, Object> before = untouchedRow(product);

        MvcResult result = patchProduct(userJwt(managerOfA), product, Map.of("pricePennies", 1250));

        JsonNode dto = json(result, 200);
        assertThat(dto.path("pricePennies").asLong()).as(text(result)).isEqualTo(1250L);
        assertThat(priceOf(product)).as("the stored price").isEqualTo(1250L);
        assertThat(untouchedRow(product))
                .as("allergen mask, spans, may-contain, ingredients, title, VAT, shop, availability and stock are exactly as before")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("#858: a price of 0 is allowed (a free item), a negative or over-limit price is a typed 400 naming pricePennies, nothing changes")
    void patchPriceValidation() throws Exception {
        UUID product = seedProduct(shopA, "PP-VAL-" + suffix, 900);

        MvcResult free = patchProduct(userJwt(managerOfA), product, Map.of("pricePennies", 0));
        assertThat(free.getResponse().getStatus()).as(text(free)).isEqualTo(200);
        assertThat(priceOf(product)).isZero();

        MvcResult negative = patchProduct(userJwt(managerOfA), product, Map.of("pricePennies", -1));
        JsonNode negativeProblem = json(negative, 400);
        assertThat(negativeProblem.path("type").asString()).as(text(negative)).isEqualTo(VALIDATION);
        assertThat(negativeProblem.path("errors").has("pricePennies")).as(text(negative)).isTrue();

        MvcResult huge = patchProduct(userJwt(managerOfA), product, Map.of("pricePennies", 1_000_000_001L));
        JsonNode hugeProblem = json(huge, 400);
        assertThat(hugeProblem.path("errors").has("pricePennies")).as(text(huge)).isTrue();

        assertThat(priceOf(product)).as("both refusals changed nothing").isZero();
    }

    @Test
    @DisplayName("#858: the price patch has the same gate as the other PATCH fields: another shop's manager and a token without catalog:write are refused, nothing changes")
    void patchPriceIsGated() throws Exception {
        UUID product = seedProduct(shopA, "PP-GATE-" + suffix, 900);

        MvcResult otherShop = patchProduct(userJwt(managerOfB), product, Map.of("pricePennies", 100));
        assertThat(json(otherShop, 403).path("type").asString()).as(text(otherShop)).isEqualTo(SHOP_ACCESS_DENIED);

        UUID t = tenant;
        RequestPostProcessor noScope = jwt().jwt(j -> j.subject(managerOfA.toString()).claim("tenant_id", t.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_catalog:read"));
        MvcResult noWrite = patchProduct(noScope, product, Map.of("pricePennies", 100));
        assertThat(json(noWrite, 403).path("type").asString()).as(text(noWrite)).isEqualTo(FORBIDDEN);

        assertThat(priceOf(product)).as("nothing changed").isEqualTo(900L);
    }

    @Test
    @DisplayName("#858: the price change is audited: products_aud holds a revision with the new price")
    void patchPriceIsAudited() throws Exception {
        UUID product = seedProduct(shopA, "PP-AUD-" + suffix, 900);

        MvcResult result = patchProduct(userJwt(managerOfA), product, Map.of("pricePennies", 1375));
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);

        Long revisions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM products_aud WHERE id = ? AND price_pennies = 1375", Long.class, product);
        assertThat(revisions).as("an audit revision carries the patched price").isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("#858: orders already placed keep the price they were placed at; an order placed after the patch takes the new price")
    void existingOrdersKeepTheirSnapshotPrices() throws Exception {
        UUID product = seedProduct(shopA, "PP-SNAP-" + suffix, 900);
        UUID oldOrder = seedOrderWithLine(shopA, product, 2, 900);

        MvcResult result = patchProduct(userJwt(managerOfA), product, Map.of("pricePennies", 1250));
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);

        Map<String, Object> oldLine = jdbcTemplate.queryForMap(
                "SELECT unit_price_pennies, total_price_pennies FROM order_items WHERE order_id = ?", oldOrder);
        assertThat(((Number) oldLine.get("unit_price_pennies")).longValue())
                .as("the placed order's unit price is the snapshot, not the product's new price").isEqualTo(900L);
        assertThat(((Number) oldLine.get("total_price_pennies")).longValue()).isEqualTo(1800L);
        assertThat(jdbcTemplate.queryForObject("SELECT total_amount_pennies FROM orders WHERE id = ?", Long.class,
                oldOrder)).as("and so is the order total").isEqualTo(1800L);

        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(product);
        item.setQuantity(1);
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopA);
        request.setCustomerName("After The Patch");
        request.setCustomerEmail("after-858@example.com");
        request.setCustomerPhone("07700900858");
        request.setFulfilmentType("COLLECTION");
        request.setItems(List.of(item));
        TenantContext.set(tenant);
        UUID newOrder;
        try {
            newOrder = SystemPrincipal.asSystem(() -> orderService.createOrder(request).getId());
        } finally {
            TenantContext.clear();
        }
        Long newUnit = jdbcTemplate.queryForObject(
                "SELECT unit_price_pennies FROM order_items WHERE order_id = ?", Long.class, newOrder);
        assertThat(newUnit).as("an order placed after the patch is priced at the new price").isEqualTo(1250L);
    }

    @Test
    @DisplayName("#858: the same price PATCH twice gives the same product (absolute value, idempotent), and PATCH with only available still leaves the price alone")
    void patchPriceIsIdempotentAndOptional() throws Exception {
        UUID product = seedProduct(shopA, "PP-IDEM-" + suffix, 900);
        Map<String, Object> body = Map.of("pricePennies", 1100, "available", false);

        MvcResult first = patchProduct(userJwt(managerOfA), product, body);
        assertThat(first.getResponse().getStatus()).as(text(first)).isEqualTo(200);
        Map<String, Object> afterFirst = untouchedRow(product);
        MvcResult second = patchProduct(userJwt(managerOfA), product, body);
        assertThat(second.getResponse().getStatus()).as(text(second)).isEqualTo(200);

        assertThat(priceOf(product)).isEqualTo(1100L);
        assertThat(untouchedRow(product)).isEqualTo(afterFirst);

        MvcResult availabilityOnly = patchProduct(userJwt(managerOfA), product, Map.of("available", true));
        assertThat(availabilityOnly.getResponse().getStatus()).as(text(availabilityOnly)).isEqualTo(200);
        assertThat(priceOf(product)).as("omitting the price keeps it").isEqualTo(1100L);
    }

    // ---- helpers ----

    private MvcResult patchProduct(RequestPostProcessor caller, UUID product, Map<String, Object> body) throws Exception {
        return mockMvc.perform(patch(PRODUCTS + product).with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new LinkedHashMap<>(body))))
                .andReturn();
    }

    private Map<String, Object> untouchedRow(UUID product) {
        return jdbcTemplate.queryForMap("SELECT " + UNTOUCHED + " FROM products WHERE id = ?", product);
    }

    private Long priceOf(UUID product) {
        return jdbcTemplate.queryForObject("SELECT price_pennies FROM products WHERE id = ?", Long.class, product);
    }

    private UUID seedShop(String slug, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                id, tenant, name, slug);
        return id;
    }

    private UUID seedProduct(UUID shop, String sku, long price) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO products (id, tenant_id, shop_id, sku, title, ingredients_text, "
                        + "allergen_mask, allergen_spans, may_contain_mask, price_pennies, vat_rate, "
                        + "quantity_in_stock, available) "
                        + "VALUES (?, ?, ?, ?, 'Suya', 'Beef, **milk**, yaji spice', 64, "
                        + "'[{\"start\": 6, \"end\": 10}]'::jsonb, 1024, ?, 'STANDARD', 50, true)",
                id, tenant, shop, sku, price);
        return id;
    }

    private UUID seedOrderWithLine(UUID shop, UUID product, int quantity, long unitPennies) {
        UUID order = UUID.randomUUID();
        long total = unitPennies * quantity;
        jdbcTemplate.update(
                "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                        + "item_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', 'Before The Patch', 'before-858@example.com', "
                        + "?, 'STANDARD', 0, ?, 0, 1, now(), now())",
                order, tenant, shop, "ORD-858-" + order.toString().substring(0, 8), total, total);
        jdbcTemplate.update(
                "INSERT INTO order_items (tenant_id, order_id, product_id, quantity, unit_price_pennies, "
                        + "total_price_pennies, product_name) VALUES (?, ?, ?, ?, ?, ?, 'Suya')",
                tenant, order, product, quantity, unitPennies, total);
        return order;
    }

    private void grant(UUID userId, UUID shop, String role) {
        jdbcTemplate.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at, grant_source) "
                + "VALUES (?, ?, ?, ?, ?, now(), 'OPERATOR')", UUID.randomUUID(), tenant, userId, shop, role);
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
