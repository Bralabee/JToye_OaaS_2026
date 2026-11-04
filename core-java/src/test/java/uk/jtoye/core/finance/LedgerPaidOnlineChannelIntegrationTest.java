package uk.jtoye.core.finance;

import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.finance.dto.CreateTransactionRequest;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.order.OrderService;
import uk.jtoye.core.payment.PaymentEventPublisher;
import uk.jtoye.core.payment.PaymentService;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.SystemPrincipal;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * V93 (#827, PR #1124 review round 1, findings 1+2+8): the ledger row records HOW it was paid when it is WRITTEN.
 * Every arm drives the REAL writers (the Stripe webhook handler for card settlement, {@code OrderService} for the
 * COMPLETED cash path, {@code createReversal} for a void) and reads the result back through the real endpoints,
 * with the application role downgraded to {@code NOSUPERUSER} so FORCE row-level security is genuinely enforced on
 * every statement (seeding included, with the tenant GUC pinned the way the app pins it).
 *
 * <ul>
 *   <li><b>Deposit by card, balance in cash</b>: one SALE of the full gross, of which only the deposit is online;
 *       summary, row and CSV say "part paid online"; its REVERSAL nets the online figure to zero. One SALE per
 *       order, so {@code uq_fin_tx_tenant_order} is intact (a second write is the idempotent no-op).</li>
 *   <li><b>Deposit and balance both by card</b>: the balance-stage settlement writes the SALE, wholly online.</li>
 *   <li><b>Full card payment</b>: wholly online at settlement; the later COMPLETED call changes nothing.</li>
 *   <li><b>Completed at the counter, link paid afterwards</b>: the SALE stays "not paid online" and the summary
 *       of the closed period is unchanged (the first cut flipped it to "Paid online").</li>
 *   <li><b>MANUAL</b>: never online.</li>
 * </ul>
 *
 * <p>Plain Spring context + Testcontainers Postgres; the Stripe signature check is stubbed (the handler under
 * test is everything after it) and the RabbitMQ publishers are mocks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
@uk.jtoye.core.testsupport.AsSystemHarness
class LedgerPaidOnlineChannelIntegrationTest {

    /** Generated per run: no credential literal in the tree (the container is throwaway). */
    private static final String DB_PW = UUID.randomUUID().toString();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword(DB_PW);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("stripe.webhook-secret", () -> "whsec_test_v93");
        registry.add("stripe.environment-tag", () -> "it-runtime");
    }

    private static final AtomicBoolean DOWNGRADED = new AtomicBoolean(false);

    @MockitoBean private OrderEventPublisher orderEventPublisher;
    @MockitoBean private PaymentEventPublisher paymentEventPublisher;

    @Autowired private PaymentService paymentService;
    @Autowired private OrderService orderService;
    @Autowired private FinancialTransactionService financialTransactionService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private MockMvc mockMvc;
    @Autowired private JsonMapper jsonMapper;

    private UUID tenantId;
    private UUID shopId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        if (DOWNGRADED.compareAndSet(false, true)) {
            jdbc.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
        }
        assertThat(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = ?",
                Boolean.class, postgres.getUsername()))
                .as("PRECONDITION: every assertion below is meaningless if the role still bypasses FORCE RLS")
                .isFalse();

        tenantId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", tenantId, "V93 Tenant " + suffix);
        shopId = UUID.randomUUID();
        inTenant(() -> jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                shopId, tenantId, "V93 Shop " + suffix, "v93-" + suffix));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------------------------------------------
    // Deposit by card, balance in cash
    // ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("deposit by card + balance in cash: ONE sale of 10000 with 2000 recorded online; the figures, the row and the CSV agree, and its reversal nets it to zero")
    void depositOnlineBalanceCashSplitsCorrectly() throws Exception {
        // The deposit was captured by the webhook: CAPTURED, deposit 2000, balance 8000 still due. The kitchen
        // takes the balance in cash and completes the order.
        UUID order = seedOrder("READY", "CAPTURED", 10_000, 2_000L, 8_000L);
        complete(order);

        List<Map<String, Object>> rows = ledger(order);
        assertThat(rows).as("exactly one ledger row for the order").hasSize(1);
        assertThat(rows.get(0).get("entry_kind")).isEqualTo("SALE");
        assertThat(((Number) rows.get(0).get("amount_pennies")).longValue()).as("full gross, one VAT figure").isEqualTo(10_000L);
        assertThat(((Number) rows.get(0).get("paid_online_pennies")).longValue())
                .as("only the deposit was taken online").isEqualTo(2_000L);

        JsonNode summary = summary();
        assertThat(summary.path("totalRevenuePennies").asLong()).isEqualTo(10_000L);
        assertThat(summary.path("paidOnlineRevenuePennies").asLong()).as(summary.toString()).isEqualTo(2_000L);
        assertThat(summary.path("notPaidOnlineRevenuePennies").asLong()).as(summary.toString()).isEqualTo(8_000L);
        assertThat(summary.path("paymentNotRecordedRevenuePennies").asLong()).isZero();

        JsonNode listed = list().path("content").get(0);
        assertThat(listed.path("paidOnlinePennies").asLong()).isEqualTo(2_000L);
        assertThat(csvRows()).anyMatch(r -> r.contains(",SALE,100.00,") && r.endsWith(",Part paid online,20.00"));

        // A second write for the same order is the idempotent no-op: still ONE sale (uq_fin_tx_tenant_order).
        TenantContext.set(tenantId);
        try {
            SystemPrincipal.asSystem(() -> inTx(() -> financialTransactionService.createTransaction(
                    new CreateTransactionRequest(10_000L, VatRate.STANDARD, "again", order, shopId), 10_000L)));
        } finally {
            TenantContext.clear();
        }
        assertThat(ledger(order)).hasSize(1);
        assertThat(((Number) ledger(order).get(0).get("paid_online_pennies")).longValue()).isEqualTo(2_000L);

        // Void: the REVERSAL takes back exactly what the sale recorded, in both columns.
        inTenant(() -> {
            FinancialTransaction sale = financialTransactionService.findSaleForOrder(order).orElseThrow();
            return financialTransactionService.createReversal(sale, "Reversal of split");
        });
        List<Map<String, Object>> after = ledger(order);
        assertThat(after).hasSize(2);
        Map<String, Object> reversal = after.stream().filter(r -> "REVERSAL".equals(r.get("entry_kind"))).findFirst().orElseThrow();
        assertThat(((Number) reversal.get("paid_online_pennies")).longValue()).isEqualTo(-2_000L);
        JsonNode netted = summary();
        assertThat(netted.path("totalRevenuePennies").asLong()).isZero();
        assertThat(netted.path("paidOnlineRevenuePennies").asLong()).as("online nets to zero too: " + netted).isZero();
        assertThat(netted.path("notPaidOnlineRevenuePennies").asLong()).as(netted.toString()).isZero();
    }

    @Test
    @DisplayName("deposit AND balance by card: the balance-stage settlement writes the sale, wholly online; the later COMPLETED call changes nothing")
    void depositAndBalanceBothOnline() throws Exception {
        UUID order = seedOrder("CONFIRMED", "CAPTURED", 10_000, 2_000L, 8_000L);
        deliver(succeeded("evt_bal_" + order, "pi_bal_" + order, order, Map.of("payment_stage", "BALANCE")));

        List<Map<String, Object>> rows = ledger(order);
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("paid_online_pennies")).longValue())
                .as("both intents went through the card").isEqualTo(10_000L);

        inTenant(() -> jdbc.update("UPDATE orders SET status = 'READY' WHERE id = ?", order));
        complete(order);
        assertThat(ledger(order)).as("COMPLETED is the idempotent no-op").hasSize(1);
        assertThat(((Number) ledger(order).get(0).get("paid_online_pennies")).longValue()).isEqualTo(10_000L);

        JsonNode summary = summary();
        assertThat(summary.path("paidOnlineRevenuePennies").asLong()).as(summary.toString()).isEqualTo(10_000L);
        assertThat(summary.path("notPaidOnlineRevenuePennies").asLong()).as(summary.toString()).isZero();
    }

    // ---------------------------------------------------------------------------------------------------
    // Full card payment and the counter-then-link case
    // ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a full card payment is recorded wholly online at settlement; completing the order afterwards writes nothing")
    void fullCardPaymentIsWhollyOnline() throws Exception {
        UUID order = seedOrder("DRAFT", "PENDING", 2_000, null, null);
        deliver(succeeded("evt_card_" + order, "pi_card_" + order, order, Map.of()));

        assertThat(((Number) ledger(order).get(0).get("paid_online_pennies")).longValue()).isEqualTo(2_000L);

        inTenant(() -> jdbc.update("UPDATE orders SET status = 'READY' WHERE id = ?", order));
        complete(order);
        assertThat(ledger(order)).hasSize(1);
        assertThat(((Number) ledger(order).get(0).get("paid_online_pennies")).longValue()).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("completed at the counter, link paid LATER: the sale stays 'not paid online' and the closed period's summary does not move")
    void counterCompletedThenLinkPaidLeavesTheClosedPeriodUnchanged() throws Exception {
        UUID order = seedOrder("READY", "NONE", 1_200, null, null);
        complete(order);

        assertThat(((Number) ledger(order).get(0).get("paid_online_pennies")).longValue())
                .as("nothing was captured when the row was written").isZero();
        JsonNode closed = summary();
        assertThat(closed.path("notPaidOnlineRevenuePennies").asLong()).isEqualTo(1_200L);
        assertThat(closed.path("paidOnlineRevenuePennies").asLong()).isZero();
        String csvBefore = String.join("\n", csvRows());

        // The payment link is paid afterwards: the webhook records the money on the order (CAPTURED)...
        deliver(succeeded("evt_link_" + order, "pi_link_" + order, order, Map.of()));
        assertThat(inTenant(() -> jdbc.queryForObject("SELECT payment_status FROM orders WHERE id = ?", String.class, order)))
                .as("the order itself IS captured now").isEqualTo("CAPTURED");

        // ...and the ledger row, and so the period already reported, is exactly what it was.
        assertThat(ledger(order)).hasSize(1);
        assertThat(((Number) ledger(order).get(0).get("paid_online_pennies")).longValue()).isZero();
        JsonNode reopened = summary();
        for (String field : List.of("totalRevenuePennies", "paidOnlineRevenuePennies", "notPaidOnlineRevenuePennies",
                "paymentNotRecordedRevenuePennies")) {
            assertThat(reopened.path(field).asLong()).as(field).isEqualTo(closed.path(field).asLong());
        }
        assertThat(String.join("\n", csvRows())).as("the CSV handed to the accountant still matches").isEqualTo(csvBefore);
    }

    @Test
    @DisplayName("a MANUAL ledger row is never online")
    void manualRowIsNeverOnline() throws Exception {
        TenantContext.set(tenantId);
        try {
            SystemPrincipal.asSystem(() -> inTx(() -> financialTransactionService.createTransaction(
                    new CreateTransactionRequest(700L, VatRate.ZERO, "Cash adjustment"))));
        } finally {
            TenantContext.clear();
        }
        JsonNode summary = summary();
        assertThat(summary.path("paidOnlineRevenuePennies").asLong()).isZero();
        assertThat(summary.path("notPaidOnlineRevenuePennies").asLong()).as(summary.toString()).isEqualTo(700L);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT paid_online_pennies FROM financial_transactions WHERE tenant_id = ?", Long.class, tenantId)))
                .isZero();
    }

    // ---------------------------------------------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------------------------------------------

    private UUID seedOrder(String status, String paymentStatus, long total, Long deposit, Long balanceDue) {
        UUID id = UUID.randomUUID();
        inTenant(() -> jdbc.update(
                "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                        + "item_count, payment_status, deposit_amount_pennies, balance_due_pennies, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'V93 Customer', 'v93@example.test', ?, 'STANDARD', 0, ?, 0, 1, ?, ?, ?, now(), now())",
                id, tenantId, shopId, "ORD-V93-" + id.toString().substring(0, 8), status, total, total, paymentStatus,
                deposit, balanceDue));
        return id;
    }

    private void complete(UUID order) {
        TenantContext.set(tenantId);
        try {
            SystemPrincipal.asSystem(() -> {
                orderService.completeOrder(order);
            });
        } finally {
            TenantContext.clear();
        }
    }

    private List<Map<String, Object>> ledger(UUID order) {
        return inTenant(() -> jdbc.queryForList(
                "SELECT entry_kind, amount_pennies, paid_online_pennies FROM financial_transactions "
                        + "WHERE order_id = ? ORDER BY entry_kind DESC", order));
    }

    /**
     * Runs {@code work} in a transaction with the tenant pinned the way the app pins it: TenantContext set, and
     * TenantSetLocalAspect writing the GUC before every JdbcTemplate call (it RESETS it when no context is set,
     * so a hand-written set_config here would be undone by the next statement).
     */
    private <T> T inTenant(Supplier<T> work) {
        TenantContext.set(tenantId);
        try {
            return new TransactionTemplate(txManager).execute(status -> work.get());
        } finally {
            TenantContext.clear();
        }
    }

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    private void deliver(Event event) {
        try (MockedStatic<Webhook> webhook = mockStatic(Webhook.class)) {
            webhook.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(event);
            paymentService.handleWebhookEvent("payload", "sig");
        }
    }

    private Event succeeded(String eventId, String intentId, UUID order, Map<String, String> extraMetadata) {
        Map<String, String> metadata = new HashMap<>(extraMetadata);
        metadata.put("order_id", order.toString());
        metadata.put("tenant_id", tenantId.toString());
        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(intentId);
        when(intent.getMetadata()).thenReturn(metadata);
        when(intent.getLatestCharge()).thenReturn(null);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(intent));
        Event event = mock(Event.class);
        when(event.getId()).thenReturn(eventId);
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        return event;
    }

    private RequestPostProcessor adminJwt() {
        UUID tenant = tenantId;
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"));
    }

    private JsonNode read(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path).with(adminJwt())).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode summary() throws Exception {
        return read("/api/v1/financial-transactions/summary");
    }

    private JsonNode list() throws Exception {
        return read("/api/v1/financial-transactions");
    }

    private List<String> csvRows() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/financial-transactions/export.csv").with(adminJwt())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> lines = List.of(csv.split("\r\n"));
        return lines.subList(1, lines.size());
    }
}
