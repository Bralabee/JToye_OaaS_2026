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
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
    private static final String ALREADY_VOIDED_TYPE = "https://jtoye.uk/errors/order-already-voided";
    private static final String NOT_VOIDABLE_TYPE = "https://jtoye.uk/errors/order-not-voidable";
    /** The Group admin who voids, by JWT subject: the void records who did it. */
    private static final String ADMIN_SUB = "3710a0a0-0000-4000-8000-000000000001";

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

    // ==================================================================
    // Task 3 (D-10, owner ruling 2026-10-08: entry-kind): an order the books depend on is VOIDED, not
    // deleted. The order stays, records who voided it, when and why, and its sale is taken back out
    // of the ledger by a REVERSAL row in the same transaction.
    // ==================================================================

    @Test
    @DisplayName("D-10 void: a Group admin voids a COMPLETED order with a reason; the order still reads 200 with who, when and why, the ledger gains exactly one REVERSAL of its SALE, and revenue drops by the sale while expenses do not move")
    void completedOrder_voidedByGroupAdmin_reversesTheSale() throws Exception {
        UUID orderId = seedOrder(OrderStatus.COMPLETED);
        String orderNumber = orderNumber(orderId);
        UUID saleId = seedSaleRow(orderId, orderNumber);
        JsonNode before = summary();

        MvcResult voided = voidOrder(orderId, "{\"reason\":\"Test order\"}", adminJwt(ADMIN_SUB));
        String text = responseText(voided);
        JsonNode body = json(voided, 200);
        assertThat(body.path("voidReason").asString()).as(text).isEqualTo("Test order");
        assertThat(body.path("voidedBy").asString()).as(text).isEqualTo(ADMIN_SUB);
        assertThat(body.path("voidedAt").asString()).as(text).isNotBlank();
        assertThat(body.path("status").asString()).as("a void is not a status (CONTEXT Q6): " + text)
                .isEqualTo("COMPLETED");

        // The order is kept and reads the void back.
        MvcResult read = mockMvc.perform(get(orderUrl(orderId)).with(adminJwt())).andReturn();
        JsonNode order = json(read, 200);
        assertThat(order.path("voidReason").asString()).as(responseText(read)).isEqualTo("Test order");
        assertThat(order.path("voidedBy").asString()).as(responseText(read)).isEqualTo(ADMIN_SUB);
        assertThat(order.path("voidedAt").asString()).as(responseText(read)).isNotBlank();
        assertThat(order.path("status").asString()).as(responseText(read)).isEqualTo("COMPLETED");

        // Exactly one SALE and one REVERSAL for the order, and the reversal mirrors the sale.
        List<Map<String, Object>> rows = ledgerRowsOf(orderId);
        assertThat(rows).as("ledger rows of the order: " + rows).hasSize(2);
        Map<String, Object> sale = rows.stream().filter(r -> "SALE".equals(r.get("entry_kind"))).findFirst()
                .orElseThrow(() -> new AssertionError("no SALE row: " + rows));
        Map<String, Object> reversal = rows.stream().filter(r -> "REVERSAL".equals(r.get("entry_kind"))).findFirst()
                .orElseThrow(() -> new AssertionError("no REVERSAL row: " + rows));
        assertThat(sale.get("id")).isEqualTo(saleId);
        assertThat(((Number) reversal.get("amount_pennies")).longValue()).as(rows.toString()).isEqualTo(-1200L);
        assertThat(reversal.get("vat_rate")).as("the reversal copies the sale's VAT rate").isEqualTo("STANDARD");
        assertThat(reversal.get("reverses_transaction_id")).as("the reversal names its sale").isEqualTo(saleId);
        assertThat(reversal.get("reference")).isEqualTo("Reversal of " + orderNumber);
        assertThat(sumOfLedgerFor(orderId)).as("the order nets to zero in the ledger").isZero();

        // Revenue is SALE minus REVERSAL; the reversal is not an expense.
        JsonNode after = summary();
        assertThat(after.path("totalRevenuePennies").asLong())
                .as("revenue drops by the voided sale: before " + before + " after " + after)
                .isEqualTo(before.path("totalRevenuePennies").asLong() - 1200L);
        assertThat(after.path("totalExpensesPennies").asLong())
                .as("a reversal is not an expense: before " + before + " after " + after)
                .isEqualTo(before.path("totalExpensesPennies").asLong());
        assertThat(after.path("netAmountPennies").asLong())
                .isEqualTo(before.path("netAmountPennies").asLong() - 1200L);
        assertThat(after.path("transactionCount").asLong())
                .isEqualTo(before.path("transactionCount").asLong() + 1);

        // Stored by content, not only rendered.
        Map<String, Object> stored = orderVoidColumns(orderId);
        assertThat(stored.get("void_reason")).isEqualTo("Test order");
        assertThat(stored.get("voided_by")).isEqualTo(ADMIN_SUB);
        assertThat(stored.get("voided_at")).isNotNull();
    }

    @Test
    @DisplayName("D-10 void is idempotent by construction: the same reason again is 200 with the FIRST void, and no second reversal is written")
    void sameReasonAgain_returnsTheExistingVoid_andWritesNothing() throws Exception {
        UUID orderId = seedOrder(OrderStatus.COMPLETED);
        seedSaleRow(orderId, orderNumber(orderId));

        JsonNode first = json(voidOrder(orderId, "{\"reason\":\"Rang it up twice\"}", adminJwt(ADMIN_SUB)), 200);
        MvcResult again = voidOrder(orderId, "{\"reason\":\"Rang it up twice\"}", adminJwt());
        JsonNode second = json(again, 200);

        assertThat(second.path("voidedAt").asString()).as(responseText(again))
                .isEqualTo(first.path("voidedAt").asString());
        assertThat(second.path("voidedBy").asString()).as("the first voider stands: " + responseText(again))
                .isEqualTo(ADMIN_SUB);
        assertThat(ledgerRowsOf(orderId)).as("still one SALE and one REVERSAL").hasSize(2);
    }

    @Test
    @DisplayName("D-10 void: a different reason on a voided order is a typed 409 order-already-voided naming who voided it and when; nothing changes")
    void differentReason_isAlreadyVoided409() throws Exception {
        UUID orderId = seedOrder(OrderStatus.COMPLETED);
        seedSaleRow(orderId, orderNumber(orderId));
        JsonNode first = json(voidOrder(orderId, "{\"reason\":\"Customer cancelled at the counter\"}",
                adminJwt(ADMIN_SUB)), 200);

        MvcResult refused = voidOrder(orderId, "{\"reason\":\"Something else\"}", adminJwt());
        String text = responseText(refused);
        JsonNode problem = json(refused, 409);
        assertThat(problem.path("type").asString()).as(text).isEqualTo(ALREADY_VOIDED_TYPE);
        assertThat(problem.path("code").asString()).as(text).isEqualTo("ORDER_ALREADY_VOIDED");
        assertThat(problem.path("voidedBy").asString()).as(text).isEqualTo(ADMIN_SUB);
        assertThat(problem.path("voidedAt").asString()).as(text).isEqualTo(first.path("voidedAt").asString());

        assertThat(orderVoidColumns(orderId).get("void_reason")).isEqualTo("Customer cancelled at the counter");
        assertThat(ledgerRowsOf(orderId)).hasSize(2);
    }

    @Test
    @DisplayName("D-10 void is Group-admin only: a SHOP_MANAGER of the order's own shop gets the shop-access 403 and nothing is written")
    void shopManager_cannotVoid() throws Exception {
        UUID orderId = seedOrder(OrderStatus.COMPLETED);
        seedSaleRow(orderId, orderNumber(orderId));
        UUID manager = UUID.randomUUID();
        grantShopStaff(manager, shopId, "SHOP_MANAGER");
        StrictScopingGuard.set(shopAccessService, true);

        MvcResult refused = voidOrder(orderId, "{\"reason\":\"Test order\"}", staffJwt(manager));
        String text = responseText(refused);
        JsonNode problem = json(refused, 403);
        assertThat(problem.path("type").asString()).as(text).isEqualTo("https://jtoye.uk/errors/shop-access-denied");
        assertThat(orderVoidColumns(orderId).get("voided_at")).as("nothing written").isNull();
        assertThat(ledgerRowsOf(orderId)).as("no reversal").hasSize(1);
    }

    @ParameterizedTest(name = "reason body {0} -> 400")
    @ValueSource(strings = {"{\"reason\":\"\"}", "{\"reason\":\"   \"}", "{}"})
    @DisplayName("D-10 void requires a reason: a blank or missing reason is a 400 and nothing is written")
    void blankReason_is400(String body) throws Exception {
        UUID orderId = seedOrder(OrderStatus.COMPLETED);
        seedSaleRow(orderId, orderNumber(orderId));

        MvcResult refused = voidOrder(orderId, body, adminJwt());
        assertThat(refused.getResponse().getStatus()).as(responseText(refused)).isEqualTo(400);
        assertThat(orderVoidColumns(orderId).get("voided_at")).as("nothing written").isNull();
        assertThat(ledgerRowsOf(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("D-10 void: a reason longer than the 500-character column is a 400, not a database error")
    void overlongReason_is400() throws Exception {
        UUID orderId = seedOrder(OrderStatus.COMPLETED);

        MvcResult refused = voidOrder(orderId, "{\"reason\":\"" + "x".repeat(501) + "\"}", adminJwt());
        assertThat(refused.getResponse().getStatus()).as(responseText(refused)).isEqualTo(400);
        assertThat(orderVoidColumns(orderId).get("voided_at")).isNull();
    }

    @Test
    @DisplayName("D-10 void: a CONFIRMED order with no SALE is voided with no ledger row, and it leaves the kitchen board while an unvoided CONFIRMED order stays on it")
    void confirmedOrderWithoutSale_voids_andLeavesTheKitchenBoard() throws Exception {
        UUID voidedId = seedOrder(OrderStatus.CONFIRMED);
        UUID keptId = seedOrder(OrderStatus.CONFIRMED);

        MvcResult voided = voidOrder(voidedId, "{\"reason\":\"Duplicate ticket\"}", adminJwt(ADMIN_SUB));
        JsonNode body = json(voided, 200);
        assertThat(body.path("voidReason").asString()).as(responseText(voided)).isEqualTo("Duplicate ticket");
        assertThat(ledgerRowsOf(voidedId)).as("no sale, so no reversal").isEmpty();

        List<String> onBoard = kitchenBoardIds();
        assertThat(onBoard).as("positive control: the unvoided CONFIRMED order is on the board").contains(keptId.toString());
        assertThat(onBoard).as("the voided order is not").doesNotContain(voidedId.toString());
    }

    @ParameterizedTest(name = "{0} -> 409 order-not-voidable")
    @EnumSource(value = OrderStatus.class, names = {"DRAFT", "PENDING"})
    @DisplayName("D-10: a DRAFT or PENDING order is not voided (delete it instead): typed 409, nothing written")
    void draftOrPending_isNotVoidable(OrderStatus status) throws Exception {
        UUID orderId = seedOrder(status);

        MvcResult refused = voidOrder(orderId, "{\"reason\":\"Test order\"}", adminJwt());
        String text = responseText(refused);
        JsonNode problem = json(refused, 409);
        assertThat(problem.path("type").asString()).as(text).isEqualTo(NOT_VOIDABLE_TYPE);
        assertThat(problem.path("code").asString()).as(text).isEqualTo("ORDER_NOT_VOIDABLE");
        assertThat(orderVoidColumns(orderId).get("voided_at")).isNull();
    }

    @Test
    @DisplayName("AI-02: void needs the orders:write scope like every other order mutation")
    void voidWithoutWriteScope_is403() throws Exception {
        UUID orderId = seedOrder(OrderStatus.COMPLETED);
        RequestPostProcessor noScope = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"));

        MvcResult refused = voidOrder(orderId, "{\"reason\":\"Test order\"}", noScope);
        assertThat(refused.getResponse().getStatus()).as(responseText(refused)).isEqualTo(403);
        assertThat(orderVoidColumns(orderId).get("voided_at")).isNull();
    }

    private MvcResult voidOrder(UUID orderId, String body, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post(orderUrl(orderId) + "/void").with(auth)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode summary() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/financial-transactions/summary").with(adminJwt())).andReturn();
        return json(result, 200);
    }

    private List<String> kitchenBoardIds() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/orders/kitchen").param("shopId", shopId.toString())
                .param("size", "100").with(adminJwt())).andReturn();
        JsonNode page = json(result, 200);
        List<String> ids = new ArrayList<>();
        page.path("content").forEach(n -> ids.add(n.path("id").asString()));
        return ids;
    }

    private List<Map<String, Object>> ledgerRowsOf(UUID orderId) {
        return inTenant(() -> jdbcTemplate.queryForList(
                "SELECT * FROM financial_transactions WHERE order_id = ? ORDER BY created_at, id", orderId));
    }

    private long sumOfLedgerFor(UUID orderId) {
        Long n = inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_pennies), 0) FROM financial_transactions WHERE order_id = ?",
                Long.class, orderId));
        return n == null ? Long.MIN_VALUE : n;
    }

    private Map<String, Object> orderVoidColumns(UUID orderId) {
        return inTenant(() -> jdbcTemplate.queryForMap(
                "SELECT to_jsonb(o) ->> 'voided_at' AS voided_at, to_jsonb(o) ->> 'voided_by' AS voided_by, "
                        + "to_jsonb(o) ->> 'void_reason' AS void_reason FROM orders o WHERE o.id = ?", orderId));
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

    private UUID seedSaleRow(UUID orderId, String orderNumber) {
        UUID id = UUID.randomUUID();
        inTenant(() -> jdbcTemplate.update(
                "INSERT INTO financial_transactions (id, tenant_id, amount_pennies, vat_rate, reference, order_id) "
                        + "VALUES (?, ?, 1200, 'STANDARD', ?, ?)",
                id, TENANT_ID, "Order " + orderNumber, orderId));
        return id;
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
        return adminJwt(UUID.randomUUID().toString());
    }

    /** The same, with a known subject, for asserting who a void records. */
    private static RequestPostProcessor adminJwt(String subject) {
        return jwt().jwt(j -> j.subject(subject).claim("tenant_id", TENANT_ID.toString()))
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
