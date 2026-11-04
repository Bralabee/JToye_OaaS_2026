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
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * #827 (owner ruling 2026-11-04, persona close-out): the finance figures split into "Paid online" and
 * "Not paid online", on the summary, the ledger rows and the CSV export.
 *
 * <p>The ledger carries no payment method, so the split is derived from the SALE's order: an order whose
 * payment was taken through the platform (Stripe: CAPTURED, or REFUNDED after being captured) is Paid online;
 * everything else (a cash order, whose payment status stays NONE; a MANUAL row with no order) is Not paid
 * online. A card order that was refunded stays on the online side, so its REVERSAL nets the online figure to
 * zero instead of leaving a positive sale on the cash side.
 *
 * <p>The ledger rows are seeded by SQL (the payment status is an order column, and the writers that set
 * it are covered by their own tests); every figure is exact because each test clears the ledger.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class FinancePaidOnlineSplitIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
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

    private static final String SUMMARY = "/api/v1/financial-transactions/summary";
    private static final String LIST = "/api/v1/financial-transactions";
    private static final String EXPORT = "/api/v1/financial-transactions/export.csv";

    private UUID tenantId;
    private UUID shop;
    private String cardOrderNumber;
    private String cashOrderNumber;
    private String refundedOrderNumber;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        // Superuser bootstrap role bypasses FORCE RLS and the unscoped aggregate leans on RLS for its tenant
        // predicate, so another test's rows would count: clear this class's own container.
        jdbcTemplate.update("DELETE FROM financial_transactions");
        tenantId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())",
                tenantId, "827 Finance Split Tenant " + suffix);
        shop = UUID.randomUUID();
        inTenant(() -> jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                shop, tenantId, "827 Shop " + suffix, "shop-827-" + suffix));

        // A card order captured through Stripe (2000), a cash order that is still NONE (1200), a card order
        // that was captured and then refunded (900 sale, 900 reversal), and a MANUAL row with no order (700).
        UUID card = seedOrder("CAPTURED", 2000);
        UUID cash = seedOrder("NONE", 1200);
        UUID refunded = seedOrder("REFUNDED", 900);
        cardOrderNumber = numberOf(card);
        cashOrderNumber = numberOf(cash);
        refundedOrderNumber = numberOf(refunded);
        seedLedger(card, "SALE", 2000, null);
        seedLedger(cash, "SALE", 1200, null);
        UUID refundedSale = seedLedger(refunded, "SALE", 900, null);
        seedLedger(refunded, "REVERSAL", -900, refundedSale);
        inTenant(() -> jdbcTemplate.update(
                "INSERT INTO financial_transactions (id, tenant_id, created_at, amount_pennies, vat_rate, reference, "
                        + "entry_kind) VALUES (?, ?, now(), 700, 'ZERO', 'Cash adjustment', 'MANUAL')",
                UUID.randomUUID(), tenantId));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("#827: the tenant summary splits revenue into paid online and not paid online, and the two add up to the revenue")
    void summarySplitsRevenueAndTheSplitAddsUp() throws Exception {
        MvcResult result = perform(get(SUMMARY));
        JsonNode body = json(result, 200);
        String text = text(result);

        assertThat(body.path("totalRevenuePennies").asLong()).as(text).isEqualTo(2000 + 1200 + 700);
        assertThat(body.path("paidOnlineRevenuePennies").asLong())
                .as("card sale 2000; the refunded card order's sale and reversal net to 0: " + text).isEqualTo(2000L);
        assertThat(body.path("notPaidOnlineRevenuePennies").asLong())
                .as("cash sale 1200 + the MANUAL row 700: " + text).isEqualTo(1900L);
        assertThat(body.path("paidOnlineRevenuePennies").asLong() + body.path("notPaidOnlineRevenuePennies").asLong())
                .as("the two figures are a partition of the revenue: " + text)
                .isEqualTo(body.path("totalRevenuePennies").asLong());
    }

    @Test
    @DisplayName("#827: a shop-and-range scope splits too (the scoped aggregate), and a MANUAL row with no shop is not in a shop's figures")
    void scopedSummarySplits() throws Exception {
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/London")).toString();
        MvcResult result = perform(get(SUMMARY).param("shopId", shop.toString()).param("from", today)
                .param("to", today));
        JsonNode body = json(result, 200);
        String text = text(result);

        assertThat(body.path("totalRevenuePennies").asLong()).as(text).isEqualTo(2000 + 1200);
        assertThat(body.path("paidOnlineRevenuePennies").asLong()).as(text).isEqualTo(2000L);
        assertThat(body.path("notPaidOnlineRevenuePennies").asLong()).as(text).isEqualTo(1200L);
    }

    @Test
    @DisplayName("#827: every ledger row says whether it was paid online: card sale true; cash sale, MANUAL row false; the refunded card order's reversal true")
    void ledgerRowsCarryPaidOnline() throws Exception {
        MvcResult result = perform(get(LIST));
        JsonNode body = json(result, 200);
        String text = text(result);
        JsonNode rows = body.path("content");
        assertThat(rows.size()).as(text).isEqualTo(5);
        int online = 0;
        int notOnline = 0;
        for (JsonNode row : rows) {
            assertThat(row.has("paidOnline")).as("every listed row states it: " + row).isTrue();
            boolean paidOnline = row.path("paidOnline").asBoolean();
            String reference = row.path("description").asString();
            if (reference.contains(cardOrderNumber)) {
                assertThat(paidOnline).as(row.toString()).isTrue();
            } else if (reference.contains(cashOrderNumber) || reference.equals("Cash adjustment")) {
                assertThat(paidOnline).as(row.toString()).isFalse();
            }
            if (paidOnline) online++; else notOnline++;
        }
        assertThat(online).as("card sale + refunded order's sale and reversal: " + text).isEqualTo(3);
        assertThat(notOnline).as("cash sale + MANUAL row: " + text).isEqualTo(2);
    }

    @Test
    @DisplayName("#827: the CSV has a payment column, last, reading 'Paid online' or 'Not paid online' per row")
    void csvCarriesThePaymentColumn() throws Exception {
        MvcResult result = perform(get(EXPORT));
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> lines = List.of(csv.split("\r\n"));

        assertThat(lines.get(0)).as(csv)
                .isEqualTo("date,reference,order_number,entry_kind,amount,vat_rate,vat_amount,shop,payment");
        List<String> rows = lines.subList(1, lines.size());
        assertThat(rows).as(csv).hasSize(5);
        assertThat(rows).as(csv).anyMatch(r -> r.contains("," + cardOrderNumber + ",SALE,20.00,") && r.endsWith(",Paid online"));
        assertThat(rows).as(csv).anyMatch(r -> r.contains("," + cashOrderNumber + ",SALE,12.00,") && r.endsWith(",Not paid online"));
        assertThat(rows).as(csv).anyMatch(r -> r.contains("," + refundedOrderNumber + ",REVERSAL,-9.00,") && r.endsWith(",Paid online"));
        assertThat(rows).as(csv).anyMatch(r -> r.contains(",Cash adjustment,,MANUAL,7.00,") && r.endsWith(",Not paid online"));
        assertThat(rows).as("no row is left without a label: " + csv)
                .allMatch(r -> r.endsWith(",Paid online") || r.endsWith(",Not paid online"));
    }

    // ---- fixtures ----

    private UUID seedOrder(String paymentStatus, long total) {
        UUID id = UUID.randomUUID();
        inTenant(() -> jdbcTemplate.update(
                "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                        + "item_count, payment_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', 'Split Customer', 'split-827@example.test', "
                        + "?, 'STANDARD', 0, ?, 0, 1, ?, now(), now())",
                id, tenantId, shop, "ORD-827-" + id.toString().substring(0, 8), total, total, paymentStatus));
        return id;
    }

    private String numberOf(UUID order) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT order_number FROM orders WHERE id = ?", String.class, order));
    }

    private UUID seedLedger(UUID order, String kind, long amount, UUID reverses) {
        UUID id = UUID.randomUUID();
        String number = numberOf(order);
        inTenant(() -> jdbcTemplate.update(
                "INSERT INTO financial_transactions (id, tenant_id, created_at, amount_pennies, vat_rate, reference, "
                        + "order_id, shop_id, entry_kind, reverses_transaction_id) "
                        + "VALUES (?, ?, now(), ?, 'STANDARD', ?, ?, ?, ?, ?)",
                id, tenantId, amount, ("REVERSAL".equals(kind) ? "Reversal of " : "Order ") + number,
                order, shop, kind, reverses));
        return id;
    }

    private <T> T inTenant(Supplier<T> work) {
        TenantContext.set(tenantId);
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    private RequestPostProcessor adminJwt() {
        UUID tenant = tenantId;
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(adminJwt())).andReturn();
    }

    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(expectedStatus);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String text(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }
}
