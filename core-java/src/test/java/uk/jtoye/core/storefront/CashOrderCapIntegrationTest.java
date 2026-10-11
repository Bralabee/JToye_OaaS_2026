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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.GuestOrderAcknowledgements;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 37-27 (D-18, RWO-041 / UXT-041): one customer identity cannot flood a shop with open cash
 * orders, proven through the real storefront endpoint with the typed 429 and the facts read back by SQL.
 *
 * <p>The persona run placed many fake cash orders in seconds with throwaway details (UXT-041). Every arm
 * here goes through {@code POST /api/v1/public/shops/{slug}/orders}, the door the flood came through.
 * Real Postgres (Testcontainers): the counts are partial-index queries over the live rows, and the
 * idempotency reservation a refusal must roll back lives in {@code INSERT ... ON CONFLICT}. The class is
 * NOT {@code @Transactional}; each request commits. The test profile has no Stripe key, so every
 * storefront order takes the cash (COD) branch and is exactly the kind of order the cap is about.
 *
 * <p>The caps in force are the application defaults ({@code jtoye.storefront.cash-caps.per-email} 3).
 * An "open" order is PENDING, CONFIRMED, PREPARING or READY; completing one frees a slot.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class CashOrderCapIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;

    /** Dedicated tenant so a parallel fork's fixtures cannot collide on slug or SKU. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000003727");
    private static final String SHOP_SLUG = "shop-37-27-cash-caps";
    private static final String STOREFRONT_ENDPOINT = "storefront.orders.create";
    private static final String PROBLEM_TYPE = "https://jtoye.uk/errors/order-cap-exceeded";
    private static final String PROBLEM_CODE = "ORDER_CAP_EXCEEDED";
    private static final String RATE_LIMITED_TYPE = "https://jtoye.uk/errors/rate-limited";
    private static final int PER_EMAIL_CAP = 3;

    private UUID shopId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "37-27 Cash Caps Tenant");
        shopId = seedShopIdempotent(SHOP_SLUG, "37-27 Cash Caps Shop");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // The per-email cap: three open cash orders are the most one address may hold at a shop; the fourth
    // is a typed 429 that writes nothing and keeps no reservation; completing one frees the slot and the
    // SAME Idempotency-Key then succeeds (the reservation rolled back with the refusal, T-37-67 shape).
    // ------------------------------------------------------------------
    @Test
    @DisplayName("UXT-041: a fourth open cash order for the same email (case-folded) at a shop is refused 429 order-cap-exceeded axis EMAIL, writes nothing and no reservation; after one completes the same key is 201")
    void fourthOpenCashOrderForTheSameEmail_isRefusedTyped_andTheSameKeySucceedsOnceOneCompletes() throws Exception {
        UUID jollof = seedProduct("SKU-3727-EMAIL", "Jollof Rice", 1000L);
        String mixedCaseEmail = "Cap.Buyer." + UUID.randomUUID() + "@Example.com";
        String foldedEmail = mixedCaseEmail.toLowerCase(java.util.Locale.ROOT);
        String phone = "+447700900" + threeRandomDigits();

        for (int i = 1; i <= PER_EMAIL_CAP; i++) {
            MvcResult created = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", "email-open-" + i + "-" + UUID.randomUUID()),
                    storefrontBody(mixedCaseEmail, phone, List.of(line(jollof, 1))));
            assertThat(created.getResponse().getStatus()).as("order " + i + " of " + PER_EMAIL_CAP + ": " + responseText(created))
                    .isEqualTo(201);
        }
        assertThat(countOpenOrdersByFoldedEmail(foldedEmail)).as("three open cash orders are held").isEqualTo(PER_EMAIL_CAP);

        // The fourth, with the SAME address spelled differently: trimmed, lower-cased, it is the same person.
        String fourthKey = "email-fourth-" + UUID.randomUUID();
        MvcResult refused = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", fourthKey),
                storefrontBody(foldedEmail, phone, List.of(line(jollof, 1))));
        JsonNode problem = assertProblem(refused, 429, PROBLEM_TYPE, PROBLEM_CODE);
        String text = responseText(refused);
        assertThat(problem.path("axis").asString()).as(text).isEqualTo("EMAIL");
        assertThat(problem.path("type").asString()).as("Pitfall 8: a cap is not time-based and must never carry the auto-retried type")
                .isNotEqualTo(RATE_LIMITED_TYPE);
        assertThat(refused.getResponse().getHeader("Retry-After")).as("no Retry-After: nothing elapses, an order completes").isNull();
        assertThat(problem.path("detail").asString()).as(text)
                .isEqualTo("This customer has reached the limit of open cash orders (EMAIL). Try again once one is completed.");

        assertThat(countOrdersByFoldedEmail(foldedEmail)).as("the refused order was not written").isEqualTo(PER_EMAIL_CAP);
        assertThat(countReservationsForKey(fourthKey))
                .as("the refusal is thrown inside the reserved work, so the reservation rolled back")
                .isZero();

        // One of the three is completed (the kitchen finished it); the slot is free and the client retries the same key.
        completeOneOpenOrder(foldedEmail);
        assertThat(countOpenOrdersByFoldedEmail(foldedEmail)).isEqualTo(PER_EMAIL_CAP - 1);

        MvcResult accepted = perform(post(ordersUrl(SHOP_SLUG)).header("Idempotency-Key", fourthKey),
                storefrontBody(foldedEmail, phone, List.of(line(jollof, 1))));
        assertThat(accepted.getResponse().getStatus()).as(responseText(accepted)).isEqualTo(201);
        assertThat(countOrdersByFoldedEmail(foldedEmail)).isEqualTo(PER_EMAIL_CAP + 1);
        assertThat(countOpenOrdersByFoldedEmail(foldedEmail)).isEqualTo(PER_EMAIL_CAP);
    }

    // ------------------------------------------------------------------
    // V81 shape: the three D-18 columns, their audit mirrors, the CHECK, and the partial cap indexes. The
    // pseudonymous IP digest has NO audit mirror (D-27, T-37-70): a keyed digest of the client address
    // must never enter append-only history that erasure then has to chase.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("V81: orders gains contact_verified, cancel_reason_code (+CHECK) and client_ip_digest; orders_aud mirrors the first two and NOT the digest; the three partial cap indexes exist")
    void v81_columnsMirrorsAndIndexes_haveTheDeclaredShape() {
        Map<String, String> orders = columnTypes("orders");
        assertThat(orders).as("orders columns").containsEntry("contact_verified", "boolean")
                .containsEntry("cancel_reason_code", "character varying")
                .containsEntry("client_ip_digest", "character");
        assertThat(columnLength("orders", "cancel_reason_code")).isEqualTo(32);
        assertThat(columnLength("orders", "client_ip_digest")).isEqualTo(64);
        assertThat(columnDefault("orders", "contact_verified")).as("NULL means not recorded: no DEFAULT (the V63 rule)").isNull();

        Map<String, String> aud = columnTypes("orders_aud");
        assertThat(aud).as("orders_aud mirrors").containsEntry("contact_verified", "boolean")
                .containsEntry("cancel_reason_code", "character varying");
        assertThat(aud).as("D-27: the digest never enters audit history").doesNotContainKey("client_ip_digest");

        String check = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_orders_cancel_reason_code'", String.class);
        assertThat(check).as("every CancelReason value is in the CHECK now (the V43 lesson)")
                .contains("SUSPECTED_FAKE").contains("CANNOT_FULFIL").contains("CUSTOMER_REQUEST").contains("OTHER");

        for (String index : List.of("idx_orders_open_cash_email", "idx_orders_open_cash_phone", "idx_orders_open_cash_ip")) {
            String def = jdbcTemplate.queryForObject(
                    "SELECT indexdef FROM pg_indexes WHERE tablename = 'orders' AND indexname = ?", String.class, index);
            assertThat(def).as(index + " is partial on the open statuses and the cash predicate")
                    .contains("WHERE").contains("PENDING").contains("CONFIRMED").contains("PREPARING").contains("READY")
                    .contains("payment_status");
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'orders' AND indexname = 'idx_orders_open_cash_email'", String.class))
                .contains("lower(");
    }

    // ---- HTTP helpers ----

    private String ordersUrl(String slug) {
        return "/api/v1/public/shops/" + slug + "/orders";
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    private static Map<String, Object> line(UUID productId, int quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("quantity", quantity);
        return item;
    }

    /** A storefront body acknowledging the CURRENT allergen set of its products (D-05), as the checkout does. */
    private Map<String, Object> storefrontBody(String email, String phone, List<Map<String, Object>> items) {
        List<UUID> productIds = items.stream().map(i -> (UUID) i.get("productId")).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Cash Cap Buyer");
        body.put("customerEmail", email);
        body.put("customerPhone", phone);
        body.put("fulfilmentType", "COLLECTION");
        body.put("items", items);
        body.put("acknowledgedAllergenMask", GuestOrderAcknowledgements.currentMask(jdbcTemplate, TENANT_ID, productIds));
        return body;
    }

    private JsonNode assertProblem(MvcResult result, int status, String type, String code) throws Exception {
        String text = responseText(result);
        assertThat(result.getResponse().getStatus()).as(text).isEqualTo(status);
        JsonNode problem = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("type").asString()).as(text).isEqualTo(type);
        assertThat(problem.path("code").asString()).as(text).isEqualTo(code);
        return problem;
    }

    private static String responseText(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    private static String threeRandomDigits() {
        return String.format(java.util.Locale.ROOT, "%03d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1000));
    }

    // ---- SQL read helpers (superuser bootstrap role; tenant pinned for correctness) ----

    private <T> T inTenant(java.util.function.Supplier<T> read) {
        TenantContext.set(TENANT_ID);
        try {
            return read.get();
        } finally {
            TenantContext.clear();
        }
    }

    private long countOrdersByFoldedEmail(String foldedEmail) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE tenant_id = ? AND lower(customer_email) = ?",
                Long.class, TENANT_ID, foldedEmail)));
    }

    private long countOpenOrdersByFoldedEmail(String foldedEmail) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE tenant_id = ? AND lower(customer_email) = ? "
                        + "AND status IN ('PENDING','CONFIRMED','PREPARING','READY')",
                Long.class, TENANT_ID, foldedEmail)));
    }

    private void completeOneOpenOrder(String foldedEmail) {
        inTenant(() -> {
            UUID id = jdbcTemplate.queryForObject(
                    "SELECT id FROM orders WHERE tenant_id = ? AND lower(customer_email) = ? "
                            + "AND status IN ('PENDING','CONFIRMED','PREPARING','READY') ORDER BY created_at LIMIT 1",
                    UUID.class, TENANT_ID, foldedEmail);
            int updated = jdbcTemplate.update("UPDATE orders SET status = 'COMPLETED' WHERE tenant_id = ? AND id = ?", TENANT_ID, id);
            assertThat(updated).as("exactly one order completed").isEqualTo(1);
            return null;
        });
    }

    private long countReservationsForKey(String key) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE tenant_id = ? AND endpoint = ? AND idempotency_key = ?",
                Long.class, TENANT_ID, STOREFRONT_ENDPOINT, key)));
    }

    private Map<String, String> columnTypes(String table) {
        Map<String, String> types = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
                rs -> { types.put(rs.getString(1), rs.getString(2)); }, table);
        assertThat(types).as("the table " + table + " exists and has columns").isNotEmpty();
        return types;
    }

    private Integer columnLength(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
    }

    private String columnDefault(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                String.class, table, column);
    }

    // ---- Idempotent seed helpers (the OrderLimitsIntegrationTest pattern) ----

    private UUID seedShopIdempotent(String slug, String name) {
        return inTenant(() -> {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, TENANT_ID, slug);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            // No hours and LEGACY_ALWAYS_OPEN (37-20, D-25): the shop takes orders at any time, and no
            // minimum order, so the only refusal an arm can meet is the one under test.
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                    id, TENANT_ID, name, slug);
            return id;
        });
    }

    /** A fresh product per call (unique SKU suffix) at the test shop, with untracked stock. */
    private UUID seedProduct(String skuPrefix, String title, long pricePennies) {
        return seedProductAt(shopId, skuPrefix, title, pricePennies);
    }

    private UUID seedProductAt(UUID atShopId, String skuPrefix, String title, long pricePennies) {
        return inTenant(() -> {
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, 0, ?, 0, true, false, ?, NULL, 'STANDARD', 0)",
                    productId, TENANT_ID, skuPrefix + "-" + productId, title, "see label", pricePennies, atShopId);
            return productId;
        });
    }
}
