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
 * #827 (owner ruling 2026-11-04, persona close-out; reworked in V93 after PR #1124 review round 1): the finance
 * figures split into "Paid online" and "Not paid online", on the summary, the ledger rows and the CSV export.
 *
 * <p><b>The channel is a fact recorded ON the row when it is written</b> ({@code paid_online_pennies}, V93), and
 * every read here sums or prints that stored figure. It is never re-derived from the order's CURRENT payment
 * status, which is what the first cut did and which was wrong twice: a deposit-split order (deposit by card,
 * balance in cash) was labelled wholly online, and an order completed at the counter then paid by a link flipped
 * to "Paid online" in a closed period.
 *
 * <p>The fixture therefore deliberately gives the orders payment statuses that DISAGREE with the ledger rows:
 * the counter-completed cash order's status is {@code CAPTURED} (a link was paid afterwards), the split order's
 * is {@code CAPTURED} (the deposit). Only the stored figure can tell the right answer, so a status-derived read
 * fails this class. The writers that store the figure are proven in {@code LedgerPaidOnlineChannelIntegrationTest}.
 *
 * <p>Every figure is exact because each test clears the ledger.
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
    private String splitOrderNumber;
    private String legacyOrderNumber;

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

        // The ledger rows, each with the channel recorded when it was written (V93):
        //   card sale 2000, wholly online;
        //   a cash order completed at the counter (1200, recorded 0 online) whose order is CAPTURED NOW because a
        //     payment link was paid afterwards: the closed period must not change;
        //   a card order captured and then refunded (900 sale, 900 reversal), online both ways;
        //   a deposit-split order: 10000 gross, 2000 deposit by card, the 8000 balance in cash (order CAPTURED);
        //   a legacy sale (500) whose channel was never recorded (NULL);
        //   a MANUAL row (700), never online.
        UUID card = seedOrder("CAPTURED", 2000);
        UUID cash = seedOrder("CAPTURED", 1200);
        UUID refunded = seedOrder("REFUNDED", 900);
        UUID split = seedOrder("CAPTURED", 10_000);
        UUID legacy = seedOrder("NONE", 500);
        cardOrderNumber = numberOf(card);
        cashOrderNumber = numberOf(cash);
        refundedOrderNumber = numberOf(refunded);
        splitOrderNumber = numberOf(split);
        legacyOrderNumber = numberOf(legacy);
        seedLedger(card, "SALE", 2000, null, 2000L);
        seedLedger(cash, "SALE", 1200, null, 0L);
        UUID refundedSale = seedLedger(refunded, "SALE", 900, null, 900L);
        seedLedger(refunded, "REVERSAL", -900, refundedSale, -900L);
        seedLedger(split, "SALE", 10_000, null, 2000L);
        seedLedger(legacy, "SALE", 500, null, null);
        inTenant(() -> jdbcTemplate.update(
                "INSERT INTO financial_transactions (id, tenant_id, created_at, amount_pennies, vat_rate, reference, "
                        + "entry_kind, paid_online_pennies) "
                        + "VALUES (?, ?, now(), 700, 'ZERO', 'Cash adjustment', 'MANUAL', 0)",
                UUID.randomUUID(), tenantId));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("V93: the summary sums the RECORDED channel: a deposit-split sale counts only its 2000p deposit as online; the three figures partition the revenue")
    void summarySplitsRevenueFromTheRecordedChannelAndTheSplitAddsUp() throws Exception {
        MvcResult result = perform(get(SUMMARY));
        JsonNode body = json(result, 200);
        String text = text(result);

        // 2000 + 1200 + (900 - 900) + 10000 + 500 + 700
        assertThat(body.path("totalRevenuePennies").asLong()).as(text).isEqualTo(14_400L);
        assertThat(body.path("paidOnlineRevenuePennies").asLong())
                .as("card sale 2000 + the split order's 2000 deposit; the refunded order nets to 0: " + text)
                .isEqualTo(4_000L);
        assertThat(body.path("paymentNotRecordedRevenuePennies").asLong())
                .as("the legacy sale whose channel was never recorded is in NEITHER figure: " + text).isEqualTo(500L);
        assertThat(body.path("notPaidOnlineRevenuePennies").asLong())
                .as("cash sale 1200 (its order is CAPTURED now, which must NOT matter) + the split order's 8000 cash "
                        + "balance + the MANUAL row 700: " + text)
                .isEqualTo(9_900L);
        assertThat(body.path("paidOnlineRevenuePennies").asLong()
                + body.path("notPaidOnlineRevenuePennies").asLong()
                + body.path("paymentNotRecordedRevenuePennies").asLong())
                .as("the three figures are a partition of the revenue: " + text)
                .isEqualTo(body.path("totalRevenuePennies").asLong());
    }

    @Test
    @DisplayName("V93: a shop-and-range scope splits too (the scoped aggregate), from the same stored figure")
    void scopedSummarySplits() throws Exception {
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/London")).toString();
        MvcResult result = perform(get(SUMMARY).param("shopId", shop.toString()).param("from", today)
                .param("to", today));
        JsonNode body = json(result, 200);
        String text = text(result);

        assertThat(body.path("totalRevenuePennies").asLong()).as("the MANUAL row has no shop: " + text)
                .isEqualTo(14_400L - 700L);
        assertThat(body.path("paidOnlineRevenuePennies").asLong()).as(text).isEqualTo(4_000L);
        assertThat(body.path("paymentNotRecordedRevenuePennies").asLong()).as(text).isEqualTo(500L);
        assertThat(body.path("notPaidOnlineRevenuePennies").asLong()).as(text).isEqualTo(1_200L + 8_000L);
    }

    @Test
    @DisplayName("V93: a closed period does not move when an order's payment status changes afterwards")
    void aLaterPaymentStatusChangeDoesNotMoveTheFigures() throws Exception {
        JsonNode before = json(perform(get(SUMMARY)), 200);

        // The counter-completed order's payment link is paid, a refund lands on the card order: statuses move.
        inTenant(() -> jdbcTemplate.update("UPDATE orders SET payment_status = 'CAPTURED' WHERE payment_status = 'NONE'"));
        inTenant(() -> jdbcTemplate.update("UPDATE orders SET payment_status = 'REFUNDED' WHERE order_number = ?",
                cardOrderNumber));

        JsonNode after = json(perform(get(SUMMARY)), 200);
        for (String field : List.of("totalRevenuePennies", "paidOnlineRevenuePennies", "notPaidOnlineRevenuePennies",
                "paymentNotRecordedRevenuePennies")) {
            assertThat(after.path(field).asLong()).as(field + " must not follow the order's current status")
                    .isEqualTo(before.path(field).asLong());
        }
    }

    @Test
    @DisplayName("V93: every ledger row states its recorded figure: card sale = amount, cash sale 0 (though its order is CAPTURED now), split = the deposit, reversal = the negative, legacy null")
    void ledgerRowsCarryPaidOnlinePennies() throws Exception {
        MvcResult result = perform(get(LIST));
        JsonNode body = json(result, 200);
        String text = text(result);
        JsonNode rows = body.path("content");
        assertThat(rows.size()).as(text).isEqualTo(7);
        int seen = 0;
        for (JsonNode row : rows) {
            assertThat(row.has("paidOnlinePennies")).as("every listed row states it: " + row).isTrue();
            String reference = row.path("description").asString();
            JsonNode figure = row.path("paidOnlinePennies");
            if (reference.equals("Order " + cardOrderNumber)) {
                assertThat(figure.asLong()).as(row.toString()).isEqualTo(2000L);
            } else if (reference.equals("Order " + cashOrderNumber)) {
                assertThat(figure.asLong()).as("counter completion stays cash: " + row).isZero();
            } else if (reference.equals("Order " + splitOrderNumber)) {
                assertThat(figure.asLong()).as("deposit only: " + row).isEqualTo(2000L);
                assertThat(row.path("amountPennies").asLong()).isEqualTo(10_000L);
            } else if (reference.equals("Order " + legacyOrderNumber)) {
                assertThat(figure.isNull()).as("not recorded is null, not 0: " + row).isTrue();
            } else if (reference.equals("Reversal of " + refundedOrderNumber)) {
                assertThat(figure.asLong()).as(row.toString()).isEqualTo(-900L);
            } else if (reference.equals("Cash adjustment")) {
                assertThat(figure.asLong()).as(row.toString()).isZero();
            } else {
                continue;
            }
            seen++;
        }
        assertThat(seen).as("every fixture row was found and checked: " + text).isEqualTo(6);
    }

    @Test
    @DisplayName("V93: the CSV has payment and paid_online_amount columns, last: 'Part paid online' for the split sale, 'Not recorded' with an empty amount for the legacy row")
    void csvCarriesThePaymentColumns() throws Exception {
        MvcResult result = perform(get(EXPORT));
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> lines = List.of(csv.split("\r\n"));

        assertThat(lines.get(0)).as(csv)
                .isEqualTo("date,reference,order_number,entry_kind,amount,vat_rate,vat_amount,shop,payment,paid_online_amount");
        List<String> rows = lines.subList(1, lines.size());
        assertThat(rows).as(csv).hasSize(7);
        assertThat(rows).as(csv).anyMatch(r -> r.contains("," + cardOrderNumber + ",SALE,20.00,") && r.endsWith(",Paid online,20.00"));
        assertThat(rows).as(csv).anyMatch(r -> r.contains("," + cashOrderNumber + ",SALE,12.00,") && r.endsWith(",Not paid online,0.00"));
        assertThat(rows).as(csv).anyMatch(r -> r.contains("," + splitOrderNumber + ",SALE,100.00,") && r.endsWith(",Part paid online,20.00"));
        assertThat(rows).as(csv).anyMatch(r -> r.contains("," + legacyOrderNumber + ",SALE,5.00,") && r.endsWith(",Not recorded,"));
        assertThat(rows).as(csv).anyMatch(r -> r.contains("," + refundedOrderNumber + ",REVERSAL,-9.00,") && r.endsWith(",Paid online,-9.00"));
        assertThat(rows).as(csv).anyMatch(r -> r.contains(",Cash adjustment,,MANUAL,7.00,") && r.endsWith(",Not paid online,0.00"));
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

    private UUID seedLedger(UUID order, String kind, long amount, UUID reverses, Long paidOnlinePennies) {
        UUID id = UUID.randomUUID();
        String number = numberOf(order);
        inTenant(() -> jdbcTemplate.update(
                "INSERT INTO financial_transactions (id, tenant_id, created_at, amount_pennies, vat_rate, reference, "
                        + "order_id, shop_id, entry_kind, reverses_transaction_id, paid_online_pennies) "
                        + "VALUES (?, ?, now(), ?, 'STANDARD', ?, ?, ?, ?, ?, ?)",
                id, tenantId, amount, ("REVERSAL".equals(kind) ? "Reversal of " : "Order ") + number,
                order, shop, kind, reverses, paidOnlinePennies));
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
