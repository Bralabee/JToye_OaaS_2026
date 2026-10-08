package uk.jtoye.core.finance;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 37-11 (RWO-027 / UXT-027, roadmap SC-2): each shop has its own figures, without opening the
 * tenant ledger to non-admins.
 *
 * <p>The persona run: per-shop finance showed the TENANT's totals, and a site manager's request was refused
 * by the class-level {@code hasRole('admin')} gate, which the dashboard rendered as "No financial data yet".
 * Ledger rows carried no shop, so there was nothing to filter on.
 *
 * <p>The rule this pins:
 * <ul>
 *   <li>With {@code shopId}: the caller needs SHOP_MANAGER on that shop, and sees only that shop's rows.</li>
 *   <li>Without {@code shopId}: the caller needs Group admin (a realm admin, or a tenant-wide GROUP_ADMIN
 *       grant), and sees every row of the tenant, including rows with no shop (MANUAL entries).</li>
 *   <li>STAFF is refused on both (goods P2-TUN-15: money endpoints refuse a non-admin).</li>
 *   <li>Every response echoes the scope it filtered on, so the UI banner reads what the API did.</li>
 * </ul>
 *
 * <p>The ledger rows are written by the real writers (order completion, the void's reversal), so the
 * shop attribution is proven where it is made, not seeded. Each test runs in its own tenant, so totals are
 * exact. Real Postgres 15 (Testcontainers); the bootstrap role is a SUPERUSER, so these arms prove the
 * application contract and the shop gate, not RLS (the V79 backfill under RLS is
 * {@code LedgerShopBackfillMigrationIntegrationTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class FinanceShopScopeIntegrationTest {

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
    @Autowired ShopAccessService shopAccessService;

    private static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";
    private static final String SUMMARY = "/api/v1/financial-transactions/summary";
    private static final String LIST = "/api/v1/financial-transactions";

    private Object bootedStrictScoping;
    private UUID tenantId;
    private UUID shopA;
    private UUID shopB;
    private String shopAName;
    private UUID managerOfA;
    private UUID staffOfA;
    private UUID operatorGroupAdmin;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
        // Strict scoping ON (the shipped default): an ungranted user is NOT an implicit Group admin, so the
        // manager and staff arms below are what their grants say and nothing more.
        StrictScopingGuard.set(shopAccessService, true);
        // The bootstrap role is a SUPERUSER, which bypasses FORCE RLS, and the tenant-wide aggregates rely on
        // RLS for their tenant predicate (CQ-02). So a previous test's ledger rows would count here. Clear
        // the ledger (this class's own container) so every total below is exact.
        jdbcTemplate.update("DELETE FROM financial_transactions");

        tenantId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())",
                tenantId, "37-11 Finance Tenant " + suffix);
        shopAName = "37-11 Shop A " + suffix;
        shopA = seedShop("shop-a-3711-" + suffix, shopAName);
        shopB = seedShop("shop-b-3711-" + suffix, "37-11 Shop B " + suffix);

        managerOfA = UUID.randomUUID();
        grant(managerOfA, shopA, "SHOP_MANAGER", "OPERATOR");
        staffOfA = UUID.randomUUID();
        grant(staffOfA, shopA, "STAFF", "OPERATOR");
        operatorGroupAdmin = UUID.randomUUID();
        grant(operatorGroupAdmin, null, "GROUP_ADMIN", "OPERATOR");
    }

    @AfterEach
    void tearDown() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // UXT-027's repro: the manager of shop A asks for shop A's figures.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("UXT-027: the manager of shop A sees shop A's figures only, and the response names the scope it filtered on")
    void managerOfA_seesOwnShopSummary() throws Exception {
        Ledger ledger = seedThreeShopLedger();

        MvcResult result = perform(get(SUMMARY).param("shopId", shopA.toString()), userJwt(managerOfA));
        JsonNode body = json(result, 200);
        String text = responseText(result);
        assertThat(body.path("totalRevenuePennies").asLong()).as("shop A's revenue only: " + text)
                .isEqualTo(ledger.shopARevenue());
        assertThat(body.path("transactionCount").asInt()).as("shop A's rows only: " + text).isEqualTo(1);
        JsonNode scope = body.path("scope");
        assertThat(scope.isObject()).as("the response echoes its scope: " + text).isTrue();
        assertThat(scope.path("shopId").asString()).as(text).isEqualTo(shopA.toString());
        assertThat(scope.path("shopName").asString()).as(text).isEqualTo(shopAName);
    }

    @Test
    @DisplayName("T-37-30: the manager of shop A gets 403 shop-access-denied for shop B, and for the tenant-wide view")
    void managerOfA_isRefusedShopB_andTheTenantView() throws Exception {
        seedThreeShopLedger();

        MvcResult otherShop = perform(get(SUMMARY).param("shopId", shopB.toString()), userJwt(managerOfA));
        assertThat(json(otherShop, 403).path("type").asString()).as(responseText(otherShop))
                .isEqualTo(SHOP_ACCESS_DENIED);

        MvcResult tenantWide = perform(get(SUMMARY), userJwt(managerOfA));
        assertThat(json(tenantWide, 403).path("type").asString()).as(responseText(tenantWide))
                .isEqualTo(SHOP_ACCESS_DENIED);

        MvcResult otherShopList = perform(get(LIST).param("shopId", shopB.toString()), userJwt(managerOfA));
        assertThat(otherShopList.getResponse().getStatus()).as(responseText(otherShopList)).isEqualTo(403);
        MvcResult tenantWideList = perform(get(LIST), userJwt(managerOfA));
        assertThat(tenantWideList.getResponse().getStatus()).as(responseText(tenantWideList)).isEqualTo(403);
    }

    @Test
    @DisplayName("P2-TUN-15: STAFF of shop A is refused the money endpoints, with and without a shop")
    void staffOfA_isRefused() throws Exception {
        seedThreeShopLedger();

        for (MockHttpServletRequestBuilder request : List.of(
                get(SUMMARY).param("shopId", shopA.toString()),
                get(SUMMARY),
                get(LIST).param("shopId", shopA.toString()),
                get(LIST))) {
            MvcResult refused = perform(request, userJwt(staffOfA));
            assertThat(refused.getResponse().getStatus()).as(responseText(refused)).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("Group admin (realm admin): no shopId reads the whole tenant, rows with no shop included, and the scope says all shops")
    void realmAdmin_readsTenantTotals() throws Exception {
        Ledger ledger = seedThreeShopLedger();

        MvcResult result = perform(get(SUMMARY), adminJwt());
        JsonNode body = json(result, 200);
        String text = responseText(result);
        assertThat(body.path("totalRevenuePennies").asLong()).as("tenant revenue, the MANUAL row included: " + text)
                .isEqualTo(ledger.shopARevenue() + ledger.shopBRevenue() + ledger.manualRevenue());
        assertThat(body.path("transactionCount").asInt()).as(text).isEqualTo(3);
        JsonNode scope = body.path("scope");
        assertThat(scope.isObject()).as("the response echoes its scope: " + text).isTrue();
        assertThat(scope.path("shopId").isNull()).as("all shops: shopId is null: " + text).isTrue();
        assertThat(scope.path("shopName").isNull()).as("all shops: shopName is null: " + text).isTrue();
    }

    @Test
    @DisplayName("Group admin (realm admin) with shopId=B sees shop B only")
    void realmAdmin_withShopB_seesShopBOnly() throws Exception {
        Ledger ledger = seedThreeShopLedger();

        MvcResult result = perform(get(SUMMARY).param("shopId", shopB.toString()), adminJwt());
        JsonNode body = json(result, 200);
        String text = responseText(result);
        assertThat(body.path("totalRevenuePennies").asLong()).as(text).isEqualTo(ledger.shopBRevenue());
        assertThat(body.path("transactionCount").asInt()).as(text).isEqualTo(1);
        assertThat(body.path("scope").path("shopId").asString()).as(text).isEqualTo(shopB.toString());
    }

    @Test
    @DisplayName("Group admin by tenant-wide OPERATOR grant (no realm role) passes the tenant-wide gate; the manager does not")
    void operatorGroupAdmin_readsTenantTotals() throws Exception {
        Ledger ledger = seedThreeShopLedger();

        MvcResult result = perform(get(SUMMARY), userJwt(operatorGroupAdmin));
        JsonNode body = json(result, 200);
        assertThat(body.path("totalRevenuePennies").asLong()).as(responseText(result))
                .isEqualTo(ledger.shopARevenue() + ledger.shopBRevenue() + ledger.manualRevenue());

        MvcResult list = perform(get(LIST), userJwt(operatorGroupAdmin));
        assertThat(json(list, 200).path("totalElements").asInt()).as(responseText(list)).isEqualTo(3);
    }

    @Test
    @DisplayName("The list is scoped like the summary: the manager of A lists A's rows only, and the page names its scope")
    void managerOfA_listsOwnShopRows() throws Exception {
        Ledger ledger = seedThreeShopLedger();

        MvcResult result = perform(get(LIST).param("shopId", shopA.toString()), userJwt(managerOfA));
        JsonNode body = json(result, 200);
        String text = responseText(result);
        assertThat(body.path("totalElements").asInt()).as(text).isEqualTo(1);
        List<String> ids = new ArrayList<>();
        body.path("content").forEach(n -> ids.add(n.path("id").asString()));
        assertThat(ids).as(text).containsExactly(ledger.shopASale().toString());
        assertThat(body.path("content").get(0).path("shopId").asString()).as(text).isEqualTo(shopA.toString());
        assertThat(body.path("scope").path("shopId").asString()).as(text).isEqualTo(shopA.toString());
    }

    @Test
    @DisplayName("GET /{id} and POST (manual entry) stay Group-admin: the manager of A is refused both")
    void singleRowAndManualEntry_stayGroupAdmin() throws Exception {
        Ledger ledger = seedThreeShopLedger();

        MvcResult one = perform(get(LIST + "/" + ledger.shopASale()), userJwt(managerOfA));
        assertThat(one.getResponse().getStatus()).as(responseText(one)).isEqualTo(403);

        MvcResult manual = perform(post(LIST).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPennies\":100,\"vatRate\":\"ZERO\",\"description\":\"x\"}"),
                userJwt(managerOfA));
        assertThat(manual.getResponse().getStatus()).as(responseText(manual)).isEqualTo(403);
        assertThat(ledgerCount()).as("the refused manual entry wrote nothing").isEqualTo(3);
    }

    @Test
    @DisplayName("from/to (UK days, inclusive) filter the summary and are echoed in the scope")
    void dateRange_filtersAndIsEchoed() throws Exception {
        seedThreeShopLedger();
        // An old MANUAL row, outside any recent range.
        inTenant(() -> jdbcTemplate.update(
                "INSERT INTO financial_transactions (id, tenant_id, created_at, amount_pennies, vat_rate, reference) "
                        + "VALUES (?, ?, '2020-01-15T12:00:00Z', 9900, 'ZERO', 'Old manual row')",
                UUID.randomUUID(), tenantId));

        MvcResult old = perform(get(SUMMARY).param("from", "2020-01-01").param("to", "2020-12-31"), adminJwt());
        JsonNode oldBody = json(old, 200);
        assertThat(oldBody.path("totalRevenuePennies").asLong()).as(responseText(old)).isEqualTo(9900L);
        assertThat(oldBody.path("transactionCount").asInt()).as(responseText(old)).isEqualTo(1);
        assertThat(oldBody.path("scope").path("from").asString()).as(responseText(old)).isEqualTo("2020-01-01");
        assertThat(oldBody.path("scope").path("to").asString()).as(responseText(old)).isEqualTo("2020-12-31");

        MvcResult recent = perform(get(SUMMARY).param("from", "2021-01-01"), adminJwt());
        assertThat(json(recent, 200).path("transactionCount").asInt()).as(responseText(recent)).isEqualTo(3);
    }

    @Test
    @DisplayName("Writers: completing an order at shop A writes a SALE with shop A; voiding it writes a REVERSAL with shop A too")
    void writers_carryTheOrdersShop() throws Exception {
        UUID orderId = seedOrder(shopA, "READY", 1500L);
        MvcResult completed = perform(post("/api/v1/orders/" + orderId + "/complete"), adminJwt());
        assertThat(completed.getResponse().getStatus()).as(responseText(completed)).isEqualTo(200);

        List<Map<String, Object>> rows = ledgerRowsOf(orderId);
        assertThat(rows).as("one SALE: " + rows).hasSize(1);
        assertThat(rows.get(0).get("shop_id")).as("the SALE carries the order's shop: " + rows)
                .isEqualTo(shopA.toString());

        MvcResult voided = perform(post("/api/v1/orders/" + orderId + "/void")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Test order\"}"), adminJwt());
        assertThat(voided.getResponse().getStatus()).as(responseText(voided)).isEqualTo(200);

        List<Map<String, Object>> after = ledgerRowsOf(orderId);
        Map<String, Object> reversal = after.stream().filter(r -> "REVERSAL".equals(r.get("entry_kind")))
                .findFirst().orElseThrow(() -> new AssertionError("no REVERSAL: " + after));
        assertThat(reversal.get("shop_id")).as("the reversal copies its sale's shop: " + after)
                .isEqualTo(shopA.toString());

        // And the scoped summary nets the void: shop A's revenue is back to zero.
        MvcResult summary = perform(get(SUMMARY).param("shopId", shopA.toString()), userJwt(managerOfA));
        JsonNode body = json(summary, 200);
        assertThat(body.path("totalRevenuePennies").asLong()).as(responseText(summary)).isZero();
        assertThat(body.path("totalExpensesPennies").asLong()).as(responseText(summary)).isZero();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** The ids and amounts of the standard three-row ledger: one SALE at A, one SALE at B, one MANUAL row. */
    record Ledger(UUID shopASale, UUID shopBSale, long shopARevenue, long shopBRevenue, long manualRevenue) {}

    /**
     * Shop A's order (1200) and shop B's order (3000) are completed through the real endpoint, so their SALE
     * rows are written by OrderService; the MANUAL row (700) goes through the admin endpoint. No row is
     * seeded with a shop: the attribution is the writers' own.
     */
    private Ledger seedThreeShopLedger() throws Exception {
        UUID orderA = seedOrder(shopA, "READY", 1200L);
        UUID orderB = seedOrder(shopB, "READY", 3000L);
        for (UUID order : List.of(orderA, orderB)) {
            MvcResult done = perform(post("/api/v1/orders/" + order + "/complete"), adminJwt());
            assertThat(done.getResponse().getStatus()).as(responseText(done)).isEqualTo(200);
        }
        MvcResult manual = perform(post(LIST).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountPennies\":700,\"vatRate\":\"ZERO\",\"description\":\"Cash adjustment\"}"), adminJwt());
        assertThat(manual.getResponse().getStatus()).as(responseText(manual)).isEqualTo(201);
        UUID saleA = UUID.fromString((String) ledgerRowsOf(orderA).get(0).get("id"));
        UUID saleB = UUID.fromString((String) ledgerRowsOf(orderB).get(0).get("id"));
        return new Ledger(saleA, saleB, 1200L, 3000L, 700L);
    }

    private UUID seedOrder(UUID shop, String status, long total) {
        UUID id = UUID.randomUUID();
        inTenant(() -> jdbcTemplate.update(
                "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                        + "item_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'Finance Customer', 'finance-customer@example.test', "
                        + "?, 'STANDARD', 0, ?, 0, 1, now(), now())",
                id, tenantId, shop, "ORD-3711-" + id.toString().substring(0, 8), status, total, total));
        return id;
    }

    /** Ledger rows of an order, read through to_jsonb so a column that does not exist yet reads as null. */
    private List<Map<String, Object>> ledgerRowsOf(UUID orderId) {
        return inTenant(() -> jdbcTemplate.queryForList(
                "SELECT to_jsonb(ft) ->> 'id' AS id, to_jsonb(ft) ->> 'entry_kind' AS entry_kind, "
                        + "to_jsonb(ft) ->> 'shop_id' AS shop_id, ft.amount_pennies "
                        + "FROM financial_transactions ft WHERE ft.order_id = ? ORDER BY ft.created_at, ft.id",
                orderId));
    }

    private int ledgerCount() {
        Integer n = inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_transactions WHERE tenant_id = ?", Integer.class, tenantId));
        return n == null ? -1 : n;
    }

    private UUID seedShop(String slug, String name) {
        UUID id = UUID.randomUUID();
        inTenant(() -> jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                id, tenantId, name, slug));
        return id;
    }

    private void grant(UUID userId, UUID shop, String role, String source) {
        jdbcTemplate.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at, grant_source) "
                + "VALUES (?, ?, ?, ?, ?, now(), ?)", UUID.randomUUID(), tenantId, userId, shop, role, source);
    }

    private <T> T inTenant(Supplier<T> work) {
        TenantContext.set(tenantId);
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    // ------------------------------------------------------------------
    // Auth and HTTP
    // ------------------------------------------------------------------

    /** A realm admin of the tenant (implicit Group admin) with the order-write scope used by the fixtures. */
    private RequestPostProcessor adminJwt() {
        UUID tenant = tenantId;
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_orders:write"));
    }

    /** A vendor user with no realm role: what they may read comes from shop_staff alone. */
    private RequestPostProcessor userJwt(UUID subject) {
        UUID tenant = tenantId;
        return jwt().jwt(j -> j.subject(subject.toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_orders:write"));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(request.with(auth)).andReturn();
    }

    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus()).as(responseText(result)).isEqualTo(expectedStatus);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String responseText(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }
}
