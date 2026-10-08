package uk.jtoye.core.order;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Phase 37-10 (D-10, RWO-018 / UXT-018, P0): an order the books depend on can no longer be deleted.
 *
 * <p>The persona run deleted a COMPLETED order as a shop manager. The DELETE returned 204, the order
 * then returned 404, and its {@code financial_transactions} row stayed behind pointing at it: the
 * ledger referenced an order nobody could open. {@code financial_transactions.order_id} carries no
 * foreign key (V40), so the database never refused it.
 *
 * <p>D-10: only DRAFT and PENDING orders are deletable. Every later status is refused with a typed 409
 * ({@code https://jtoye.uk/errors/order-not-deletable}, code {@code ORDER_NOT_DELETABLE}) that names the
 * status and tells the caller to void instead, and the order and its ledger row both stay.
 *
 * <p>Every arm drives the real HTTP endpoint and reads the stored rows back by SQL. Real Postgres 15
 * (Testcontainers), not {@code @Transactional}: each request commits. The bootstrap role is a
 * SUPERUSER, so these arms prove the application contract and the shop-access gate, not RLS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class OrderVoidIntegrationTest {

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

    /** Dedicated tenant so a parallel fork's fixtures cannot collide on slug or order number. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000371010");
    private static final String SHOP_SLUG = "shop-37-10-void";
    private static final String NOT_DELETABLE_TYPE = "https://jtoye.uk/errors/order-not-deletable";

    private UUID shopId;
    private Object bootedStrictScoping;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "37-10 Void Tenant");
        shopId = seedShopIdempotent(SHOP_SLUG, "37-10 Void Shop");
    }

    @AfterEach
    void tearDown() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // UXT-018's repro: a COMPLETED order with its ledger row. Deleting it must be refused, and both
    // the order and the ledger row must still be there afterwards.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("UXT-018: DELETE of a COMPLETED order with a ledger row is a typed 409 order-not-deletable; the order still reads 200 and the ledger row still resolves to it")
    void completedOrderWithLedgerRow_deleteIsRefused_andNothingDangles() throws Exception {
        UUID orderId = seedOrder(OrderStatus.COMPLETED);
        String orderNumber = orderNumber(orderId);
        seedSaleRow(orderId, orderNumber);

        MvcResult refused = mockMvc.perform(delete(orderUrl(orderId)).with(adminJwt())).andReturn();
        String text = responseText(refused);
        // Read BEFORE the status assertion, so a regression's report states the damage it did.
        int danglingAfterDelete = danglingLedgerRows();
        assertThat(refused.getResponse().getStatus())
                .as(text + " | ledger rows now pointing at a missing order: " + danglingAfterDelete)
                .isEqualTo(409);
        JsonNode problem = json(refused, 409);
        assertThat(problem.path("type").asString()).as(text).isEqualTo(NOT_DELETABLE_TYPE);
        assertThat(problem.path("code").asString()).as(text).isEqualTo("ORDER_NOT_DELETABLE");
        assertThat(problem.path("title").asString()).as(text).isEqualTo("Order can't be deleted");
        assertThat(problem.path("status").asString()).as("the order's status is named: " + text)
                .isEqualTo("COMPLETED");
        assertThat(problem.path("orderNumber").asString()).as(text).isEqualTo(orderNumber);
        assertThat(problem.path("detail").asString()).as("the detail tells the caller to void: " + text)
                .isEqualTo("Order " + orderNumber + " is COMPLETED, so it can't be deleted. "
                        + "Void it instead to keep your records.");

        // The order is still readable, and the ledger references only orders that exist.
        MvcResult read = mockMvc.perform(get(orderUrl(orderId)).with(adminJwt())).andReturn();
        assertThat(read.getResponse().getStatus()).as(responseText(read)).isEqualTo(200);
        assertThat(danglingLedgerRows()).as("no ledger row points at a missing order").isZero();
        assertThat(ledgerRowsFor(orderId)).as("the sale row is untouched").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // D-10: "only DRAFT/PENDING". Every later status is refused, whether or not it has a ledger row.
    // ------------------------------------------------------------------
    @ParameterizedTest(name = "{0} -> 409 order-not-deletable")
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "PREPARING", "READY", "CANCELLED", "REFUNDED"})
    @DisplayName("D-10: DELETE of a CONFIRMED-or-later order is a typed 409 naming its status, and the order stays")
    void postPendingOrder_deleteIsRefused(OrderStatus status) throws Exception {
        UUID orderId = seedOrder(status);

        MvcResult refused = mockMvc.perform(delete(orderUrl(orderId)).with(adminJwt())).andReturn();
        String text = responseText(refused);
        JsonNode problem = json(refused, 409);
        assertThat(problem.path("type").asString()).as(text).isEqualTo(NOT_DELETABLE_TYPE);
        assertThat(problem.path("status").asString()).as(text).isEqualTo(status.name());
        assertThat(orderExists(orderId)).as("the order is kept").isTrue();
    }

    // ------------------------------------------------------------------
    // Incremental betterment: the good that stays. DRAFT and PENDING orders delete exactly as before.
    // ------------------------------------------------------------------
    @ParameterizedTest(name = "{0} -> 204 and gone")
    @EnumSource(value = OrderStatus.class, names = {"DRAFT", "PENDING"})
    @DisplayName("D-10: DRAFT and PENDING orders still delete (204) and then read 404")
    void draftOrPendingOrder_stillDeletes(OrderStatus status) throws Exception {
        UUID orderId = seedOrder(status);

        MvcResult deleted = mockMvc.perform(delete(orderUrl(orderId)).with(adminJwt())).andReturn();
        assertThat(deleted.getResponse().getStatus()).as(responseText(deleted)).isEqualTo(204);
        assertThat(orderExists(orderId)).as("the order is gone").isFalse();
        MvcResult read = mockMvc.perform(get(orderUrl(orderId)).with(adminJwt())).andReturn();
        assertThat(read.getResponse().getStatus()).as(responseText(read)).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // The shop gate runs BEFORE the status check: a caller without SHOP_MANAGER on the order's shop
    // learns nothing about the order's status from the refusal.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("VSA-02 order: a STAFF-only user gets the shop-access 403, not the 409 that would disclose the status")
    void staffOnlyUser_getsShopAccess403_notTheStatus() throws Exception {
        UUID orderId = seedOrder(OrderStatus.COMPLETED);
        UUID staffUser = UUID.randomUUID();
        grantShopStaff(staffUser, shopId, "STAFF");
        StrictScopingGuard.set(shopAccessService, true);

        MvcResult refused = mockMvc.perform(delete(orderUrl(orderId)).with(staffJwt(staffUser))).andReturn();
        String text = responseText(refused);
        JsonNode problem = json(refused, 403);
        assertThat(problem.path("type").asString()).as(text).isEqualTo("https://jtoye.uk/errors/shop-access-denied");
        assertThat(orderExists(orderId)).isTrue();
    }

    // ---- fixtures ----

    private UUID seedOrder(OrderStatus status) {
        UUID id = UUID.randomUUID();
        inTenant(() -> jdbcTemplate.update(
                "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                        + "item_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'Void Customer', 'void-customer@example.test', "
                        + "1000, 'STANDARD', 200, 1200, 0, 1, now(), now())",
                id, TENANT_ID, shopId, "ORD-3710-" + id.toString().substring(0, 8), status.name()));
        return id;
    }

    private void seedSaleRow(UUID orderId, String orderNumber) {
        inTenant(() -> jdbcTemplate.update(
                "INSERT INTO financial_transactions (id, tenant_id, amount_pennies, vat_rate, reference, order_id) "
                        + "VALUES (?, ?, 1200, 'STANDARD', ?, ?)",
                UUID.randomUUID(), TENANT_ID, "Order " + orderNumber, orderId));
    }

    private String orderNumber(UUID orderId) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT order_number FROM orders WHERE id = ?", String.class, orderId));
    }

    private boolean orderExists(UUID orderId) {
        Integer n = inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE id = ?", Integer.class, orderId));
        return n != null && n > 0;
    }

    private int ledgerRowsFor(UUID orderId) {
        Integer n = inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_transactions WHERE order_id = ?", Integer.class, orderId));
        return n == null ? -1 : n;
    }

    /** Ledger rows of this tenant whose order_id names an order that no longer exists. */
    private int danglingLedgerRows() {
        Integer n = inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_transactions ft WHERE ft.tenant_id = ? AND ft.order_id IS NOT NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = ft.order_id)",
                Integer.class, TENANT_ID));
        return n == null ? -1 : n;
    }

    private void grantShopStaff(UUID userId, UUID shop, String role) {
        jdbcTemplate.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at) "
                + "VALUES (?, ?, ?, ?, ?, now())", UUID.randomUUID(), TENANT_ID, userId, shop, role);
    }

    private UUID seedShopIdempotent(String slug, String name) {
        return inTenant(() -> {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, TENANT_ID, slug);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                    id, TENANT_ID, name, slug);
            return id;
        });
    }

    private <T> T inTenant(Supplier<T> work) {
        TenantContext.set(TENANT_ID);
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    // ---- auth ----

    /** A realm admin of the tenant (implicit GROUP_ADMIN) with the order-write scope. */
    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", TENANT_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_orders:write"));
    }

    /** A shop user (no realm role) with the order-write scope; their shop role comes from shop_staff. */
    private static RequestPostProcessor staffJwt(UUID subject) {
        return jwt().jwt(j -> j.subject(subject.toString()).claim("tenant_id", TENANT_ID.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_orders:write"));
    }

    // ---- HTTP / JSON ----

    private static String orderUrl(UUID orderId) {
        return "/api/v1/orders/" + orderId;
    }

    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus()).as(responseText(result)).isEqualTo(expectedStatus);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String responseText(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }
}
