package uk.jtoye.core.storefront;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.payment.StripeRefundClient;
import uk.jtoye.core.security.CustomerJwtVerifier;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * #868 (owner ruling 2026-11-04, persona close-out): a customer can cancel their own order while it is
 * still PENDING (the kitchen has not accepted it). A card-paid order is refunded in full through the
 * existing {@code RefundService} path; after PENDING the answer is a typed 409 telling them to contact
 * the shop.
 *
 * <p>Everything here runs as the application's own datasource role AFTER it is downgraded to
 * {@code NOSUPERUSER NOBYPASSRLS} (asserted), because the request is anonymous: ownership is proven by
 * the tracking pair (order number AND email) under {@code orders_guest_tracking}, and the write happens
 * only after the order's tenant has been pinned. The Testcontainers superuser would show green over a
 * cancel that FORCE RLS refuses in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class CustomerOrderCancelIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @MockitoBean private StripeRefundClient stripeRefundClient;
    @MockitoBean private CustomerJwtVerifier customerJwtVerifier;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired JsonMapper json;

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000868001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000868002");
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static boolean seeded;
    private static final java.util.Map<String, Seeded> ORD = new java.util.HashMap<>();

    @BeforeEach
    void seedOnceThenDowngrade() {
        if (!seeded) {
            jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, 'cancel-a', now()) ON CONFLICT DO NOTHING", TENANT_A);
            jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, 'cancel-b', now()) ON CONFLICT DO NOTHING", TENANT_B);
            UUID shopA = insertShop(TENANT_A, "cancel-shop-a");
            UUID shopB = insertShop(TENANT_B, "cancel-shop-b");
            ORD.put("cash", order(TENANT_A, shopA, "PENDING", "NONE", null, 1500L, null, null));
            Seeded card = order(TENANT_A, shopA, "PENDING", "CAPTURED", "pi_868_card", 1500L, null, null);
            insertSale(card, 1500L);
            ORD.put("card", card);
            ORD.put("cardFail", order(TENANT_A, shopA, "PENDING", "CAPTURED", "pi_868_fail", 900L, null, null));
            ORD.put("confirmed", order(TENANT_A, shopA, "CONFIRMED", "NONE", null, 1500L, null, null));
            ORD.put("deposit", order(TENANT_A, shopA, "PENDING", "CAPTURED", "pi_868_dep", 3000L, 900L, 2100L));
            ORD.put("mine", order(TENANT_A, shopA, "PENDING", "NONE", null, 1000L, null, null));
            ORD.put("theirs", order(TENANT_B, shopB, "PENDING", "NONE", null, 1000L, null, null));
            ORD.put("noCred", order(TENANT_A, shopA, "PENDING", "NONE", null, 1000L, null, null));
            ORD.put("token", order(TENANT_A, shopA, "PENDING", "NONE", null, 1000L, null, null));
            ORD.put("idem", order(TENANT_A, shopA, "PENDING", "CAPTURED", "pi_868_idem", 1200L, null, null));
            ORD.put("idemOther", order(TENANT_A, shopA, "PENDING", "NONE", null, 800L, null, null));
            ORD.put("repeat", order(TENANT_A, shopA, "PENDING", "NONE", null, 700L, null, null));
            for (String k : new String[]{"cardPost", "cardRetry", "cardRefused", "cardUnknown"}) {
                Seeded c = order(TENANT_A, shopA, "PENDING", "CAPTURED", "pi_868_" + k, 1100L, null, null);
                insertSale(c, 1100L);
                ORD.put(k, c);
            }
            for (String k : new String[]{"bothPaid", "bothPartial"}) {
                Seeded b = order(TENANT_A, shopA, "PENDING", "CAPTURED", "pi_868_bal_" + k, 3000L, 900L, 0L);
                jdbc.update("UPDATE orders SET deposit_payment_reference = ? WHERE id = ?", "pi_868_dep_" + k, b.id);
                insertSale(b, 3000L);
                ORD.put(k, b);
            }
            ORD.put("bothLegacy", order(TENANT_A, shopA, "PENDING", "CAPTURED", "pi_868_bal_legacy", 3000L, 900L, 0L));
            ORD.put("authorized", order(TENANT_A, shopA, "PENDING", "AUTHORIZED", "pi_868_auth", 1000L, null, null));
            ORD.put("payPending", order(TENANT_A, shopA, "PENDING", "PENDING", "pi_868_pend", 1000L, null, null));
            ORD.put("cross", order(TENANT_A, shopA, "PENDING", "NONE", null, 1000L, null, null));
            jdbc.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER NOBYPASSRLS");
            seeded = true;
        }
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class))
                .as("PRECONDITION: every arm below is meaningless if the datasource role still bypasses RLS")
                .isFalse();
        reset(stripeRefundClient, customerJwtVerifier);
        stripeSeen.clear();
        stripeCalls.clear();
    }

    // ------------------------------------------------------------------
    // Cash order (no money taken): cancelled, nobody refunded
    // ------------------------------------------------------------------
    @Test
    @DisplayName("a PENDING pay-on-delivery order is cancelled by its customer: CANCELLED, reason CUSTOMER_REQUEST, kitchen event written, no refund")
    void pendingCashOrder_isCancelled() throws Exception {
        Seeded o = ORD.get("cash");

        MvcResult res = cancel(o.number, "{\"email\":\"" + o.email + "\"}", null, null);

        assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(200);
        JsonNode out = json.readTree(raw(res));
        assertThat(out.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(out.path("refunded").asBoolean()).isFalse();
        assertThat(col(o, "status")).isEqualTo("CANCELLED");
        assertThat(col(o, "cancel_reason_code")).isEqualTo("CUSTOMER_REQUEST");
        assertThat(longQ(o.tenant, "SELECT count(*) FROM payment_event_outbox WHERE routing_key = 'order.state.cancelled' "
                + "AND payload LIKE ?", "%\"orderNumber\":\"" + o.number + "\"%"))
                .as("the kitchen board / email / webhook fan-out hears about it through the outbox, same as any cancel")
                .isEqualTo(1);
        verify(stripeRefundClient, never()).create(any(), any());
    }

    // ------------------------------------------------------------------
    // Card-paid order: refunded through RefundService, ledger reversed
    // ------------------------------------------------------------------
    @Test
    @DisplayName("a PENDING card-paid order is cancelled AND refunded in full through RefundService; the sale is reversed in the ledger")
    void pendingCardOrder_isCancelledAndRefunded() throws Exception {
        Seeded o = ORD.get("card");
        com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
        stripeRefund.setId("re_868_ok");
        stripeRefund.setStatus("succeeded");
        when(stripeRefundClient.create(any(), any())).thenReturn(stripeRefund);

        MvcResult res = cancel(o.number, "{\"email\":\"" + o.email + "\"}", "key-868-card-1", null);

        assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(200);
        JsonNode out = json.readTree(raw(res));
        assertThat(out.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(out.path("refunded").asBoolean()).isTrue();
        assertThat(out.path("refundAmountPennies").asLong()).isEqualTo(1500L);
        assertThat(col(o, "status")).isEqualTo("CANCELLED");
        assertThat(col(o, "payment_status")).isEqualTo("REFUNDED");
        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ? AND amount_pennies = 1500 "
                + "AND reason = 'REQUESTED_BY_CUSTOMER' AND status = 'succeeded'", o.id)).isEqualTo(1);
        assertThat(longQ(o.tenant, "SELECT count(*) FROM financial_transactions WHERE order_id = ? "
                + "AND entry_kind = 'REVERSAL' AND amount_pennies = -1500", o.id))
                .as("the sale is taken back out of the books, exactly as a void does").isEqualTo(1);
        verify(stripeRefundClient, times(1)).create(any(), any());
    }

    @Test
    @DisplayName("A1: a Stripe REFUSAL is recorded failed in a COMMITTED row, the customer is told the order is cancelled "
            + "but the refund did not complete (502), and the retry is a new row under a NEW Stripe key")
    void stripeRefusal_isRecordedFailed_andTheRetryUsesAFreshKey() throws Exception {
        Seeded o = ORD.get("cardRefused");
        when(stripeRefundClient.create(any(), any()))
                .thenThrow(new InvalidRequestException("charge_already_refunded", "req_1", "charge_already_refunded", null, 400, null));

        MvcResult res = cancel(o.number, "{\"email\":\"" + o.email + "\"}", null, null);

        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ? AND status = 'failed'", o.id))
                .as("the failure record must survive the request: it is what makes the next attempt a new key").isEqualTo(1);
        assertThat(col(o, "status")).as("the cancel is committed BEFORE Stripe is asked").isEqualTo("CANCELLED");
        assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(502);
        JsonNode problem = json.readTree(raw(res));
        assertThat(problem.path("type").asString()).endsWith("/errors/refund-failed");
        assertThat(problem.path("detail").asString()).contains("has been cancelled").doesNotContain("charge_already_refunded");
        verify(stripeRefundClient, times(1)).create(any(), any());

        fakeStripe();
        MvcResult retry = cancel(o.number, "{\"email\":\"" + o.email + "\"}", null, null);

        assertThat(retry.getResponse().getStatus()).as(body(retry)).isEqualTo(200);
        assertThat(json.readTree(raw(retry)).path("refunded").asBoolean()).isTrue();
        assertThat(col(o, "payment_status")).isEqualTo("REFUNDED");
        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ?", o.id)).isEqualTo(2);
        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ? AND status = 'succeeded'", o.id)).isEqualTo(1);
        assertThat(stripeCalls).hasSize(1);
        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ? AND idempotency_key = ?",
                o.id, stripeCalls.get(0).key())).as("the key Stripe saw is the surviving row's own stored key").isEqualTo(1);
        assertThat(longQ(o.tenant, "SELECT count(*) FROM financial_transactions WHERE order_id = ? AND entry_kind = 'REVERSAL'", o.id))
                .as("one reversal, not one per attempt").isEqualTo(1);
    }

    @Test
    @DisplayName("A1: an UNKNOWN Stripe outcome (connection error) leaves the row CREATING, and the retry re-sends the IDENTICAL request")
    void unknownStripeOutcome_retryResendsTheSameRequest() throws Exception {
        Seeded o = ORD.get("cardUnknown");
        when(stripeRefundClient.create(any(), any())).thenThrow(new ApiConnectionException("timeout", null));

        MvcResult res = cancel(o.number, "{\"email\":\"" + o.email + "\"}", null, null);
        assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(502);
        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ? AND status = 'CREATING'", o.id))
                .as("an unknown outcome is not a failure: the refund may exist at Stripe").isEqualTo(1);

        fakeStripe();
        MvcResult retry = cancel(o.number, "{\"email\":\"" + o.email + "\"}", null, null);

        assertThat(retry.getResponse().getStatus()).as(body(retry)).isEqualTo(200);
        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ?", o.id)).as("same row, no second").isEqualTo(1);
        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ? AND status = 'succeeded' "
                + "AND stripe_refund_id IS NOT NULL", o.id)).isEqualTo(1);
    }

    @Test
    @DisplayName("A2: a failure AFTER the Stripe call never leaves a PENDING, fulfillable order with money returned and no Refund row")
    void failureAfterStripe_neverLeavesAPendingOrderWithNoRefundRow() throws Exception {
        Seeded o = ORD.get("cardPost");
        fakeStripe();

        MvcResult res = withRefundedWriteBlocked(() ->
                cancel(o.number, "{\"email\":\"" + o.email + "\"}", "key-868-post-" + SEQ.incrementAndGet(), null));

        assertThat(stripeCalls).as("Stripe WAS asked, so the money is on its way back").hasSize(1);
        assertThat(col(o, "status"))
                .as("a refunded order must not be PENDING for the kitchen to accept")
                .isEqualTo("CANCELLED");
        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ?", o.id))
                .as("a committed Refund row exists for the refund.* webhook to find").isEqualTo(1);
        assertThat(strQ(o.tenant, "SELECT id::text FROM refunds WHERE order_id = ?", o.id))
                .as("metadata.refund_id sent to Stripe names that committed row")
                .isEqualTo(stripeCalls.get(0).params().get("metadata[refund_id]"));
        assertThat(longQ(o.tenant, "SELECT count(*) FROM payment_event_outbox WHERE routing_key = 'order.state.cancelled' "
                + "AND payload LIKE ?", "%\"orderNumber\":\"" + o.number + "\"%")).isEqualTo(1);
        assertThat(res.getResponse().getStatus()).as("the customer is told it did not finish: " + body(res)).isEqualTo(502);
    }

    @Test
    @DisplayName("A1: a retry after an attempt that Stripe refunded but we failed to record replays the SAME Stripe refund (not a 502)")
    void retryAfterRefundedButUnrecordedAttempt_replaysTheSameRefund() throws Exception {
        Seeded o = ORD.get("cardRetry");
        fakeStripe();
        String key = "key-868-retry-" + SEQ.incrementAndGet();

        MvcResult first = withRefundedWriteBlocked(() -> cancel(o.number, "{\"email\":\"" + o.email + "\"}", key, null));
        assertThat(first.getResponse().getStatus()).as("the first attempt did not complete").isNotEqualTo(200);

        MvcResult retry = cancel(o.number, "{\"email\":\"" + o.email + "\"}", key, null);

        assertThat(retry.getResponse().getStatus())
                .as("Stripe refuses a reused key whose parameters changed; the retry must not change them: " + body(retry))
                .isEqualTo(200);
        assertThat(json.readTree(raw(retry)).path("refunded").asBoolean()).isTrue();
        assertThat(col(o, "payment_status")).isEqualTo("REFUNDED");
        assertThat(col(o, "status")).isEqualTo("CANCELLED");
        assertThat(stripeCalls).hasSize(2);
        assertThat(stripeCalls.get(1).key()).isEqualTo(stripeCalls.get(0).key());
        assertThat(stripeCalls.get(1).params()).isEqualTo(stripeCalls.get(0).params());
        assertThat(stripeSeen).as("exactly ONE refund exists at Stripe").hasSize(1);
        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ?", o.id)).isEqualTo(1);
        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ? AND status = 'succeeded'", o.id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // After PENDING: refused, typed, the customer is told to contact the shop
    // ------------------------------------------------------------------
    @Test
    @DisplayName("once the kitchen has accepted (CONFIRMED) the answer is a typed 409 order-not-cancellable-by-customer and nothing changes")
    void confirmedOrder_isRefusedWithTheShopContactStatement() throws Exception {
        Seeded o = ORD.get("confirmed");

        MvcResult res = cancel(o.number, "{\"email\":\"" + o.email + "\"}", null, null);

        assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(409);
        JsonNode problem = json.readTree(raw(res));
        assertThat(problem.path("type").asString()).endsWith("/errors/order-not-cancellable-by-customer");
        assertThat(problem.path("orderStatus").asString()).isEqualTo("CONFIRMED");
        assertThat(problem.path("detail").asString()).containsIgnoringCase("contact the shop");
        assertThat(col(o, "status")).isEqualTo("CONFIRMED");
        verify(stripeRefundClient, never()).create(any(), any());
    }

    @Test
    @DisplayName("deposit order, balance still due: the customer is refunded the DEPOSIT actually paid, on the deposit's own PaymentIntent")
    void depositOrder_refundsTheDepositPaid() throws Exception {
        Seeded o = ORD.get("deposit");
        fakeStripe();

        MvcResult res = cancel(o.number, "{\"email\":\"" + o.email + "\"}", "key-868-dep-" + SEQ.incrementAndGet(), null);

        assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(200);
        JsonNode out = json.readTree(raw(res));
        assertThat(out.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(out.path("refunded").asBoolean()).isTrue();
        assertThat(out.path("refundAmountPennies").asLong()).as("what was paid, not the order total").isEqualTo(900L);
        assertThat(stripeCalls).hasSize(1);
        assertThat(stripeCalls.get(0).params().get("payment_intent")).isEqualTo("pi_868_dep");
        assertThat(stripeCalls.get(0).params().get("amount")).isEqualTo(900L);
        assertThat(col(o, "payment_status")).isEqualTo("REFUNDED");
        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ? AND amount_pennies = 900 "
                + "AND payment_intent_id = 'pi_868_dep' AND status = 'succeeded'", o.id)).isEqualTo(1);
        assertThat(longQ(o.tenant, "SELECT count(*) FROM financial_transactions WHERE order_id = ?", o.id))
                .as("a deposit books no sale until completion, so there is nothing to reverse").isZero();
    }

    @Test
    @DisplayName("deposit AND balance paid: two refunds, one per PaymentIntent, each for what it charged and each with its own key")
    void depositAndBalancePaid_refundsBothChargesSeparately() throws Exception {
        Seeded o = ORD.get("bothPaid");
        fakeStripe();
        String key = "key-868-both-" + SEQ.incrementAndGet();

        MvcResult res = cancel(o.number, "{\"email\":\"" + o.email + "\"}", key, null);

        assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(200);
        assertThat(json.readTree(raw(res)).path("refundAmountPennies").asLong()).isEqualTo(3000L);
        assertThat(stripeCalls).hasSize(2);
        assertThat(stripeCalls).extracting(c -> c.params().get("payment_intent"))
                .containsExactlyInAnyOrder("pi_868_dep_bothPaid", "pi_868_bal_bothPaid");
        assertThat(stripeCalls.stream().filter(c -> "pi_868_dep_bothPaid".equals(c.params().get("payment_intent")))
                .findFirst().orElseThrow().params().get("amount")).isEqualTo(900L);
        assertThat(stripeCalls.stream().filter(c -> "pi_868_bal_bothPaid".equals(c.params().get("payment_intent")))
                .findFirst().orElseThrow().params().get("amount")).isEqualTo(2100L);
        assertThat(stripeCalls.get(0).key()).as("a deposit and a balance are separate charges: separate keys")
                .isNotEqualTo(stripeCalls.get(1).key());
        assertThat(col(o, "payment_status")).isEqualTo("REFUNDED");
        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ? AND status = 'succeeded'", o.id)).isEqualTo(2);
        assertThat(longQ(o.tenant, "SELECT count(*) FROM financial_transactions WHERE order_id = ? "
                + "AND entry_kind = 'REVERSAL' AND amount_pennies = -3000", o.id)).isEqualTo(1);

        MvcResult replay = cancel(o.number, "{\"email\":\"" + o.email + "\"}", key, null);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(stripeCalls).as("a replay refunds nothing again").hasSize(2);
    }

    @Test
    @DisplayName("deposit refunded but the balance leg refused: order CANCELLED and still CAPTURED, 502; the repeat sends ONLY the balance leg")
    void balanceLegFails_depositLegIsNotRepeated() throws Exception {
        Seeded o = ORD.get("bothPartial");
        fakeStripe("pi_868_bal_bothPartial");

        MvcResult first = cancel(o.number, "{\"email\":\"" + o.email + "\"}", null, null);

        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ? AND status = 'succeeded' "
                + "AND payment_intent_id = 'pi_868_dep_bothPartial'", o.id)).isEqualTo(1);
        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ? AND status = 'failed' "
                + "AND payment_intent_id = 'pi_868_bal_bothPartial'", o.id)).isEqualTo(1);
        assertThat(col(o, "status")).isEqualTo("CANCELLED");
        assertThat(col(o, "payment_status")).as("not REFUNDED while a leg is unfinished").isEqualTo("CAPTURED");
        assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(502);

        stripeCalls.clear();
        fakeStripe();
        MvcResult retry = cancel(o.number, "{\"email\":\"" + o.email + "\"}", null, null);

        assertThat(retry.getResponse().getStatus()).as(body(retry)).isEqualTo(200);
        assertThat(stripeCalls).as("the deposit is already refunded; only the balance is sent").hasSize(1);
        assertThat(stripeCalls.get(0).params().get("payment_intent")).isEqualTo("pi_868_bal_bothPartial");
        assertThat(col(o, "payment_status")).isEqualTo("REFUNDED");
        assertThat(json.readTree(raw(retry)).path("refundAmountPennies").asLong()).isEqualTo(3000L);
    }

    @Test
    @DisplayName("a deposit order paid in both stages BEFORE the deposit intent was recorded is refused (409), never half-refunded")
    void bothStagesButDepositIntentUnknown_isRefused() throws Exception {
        Seeded o = ORD.get("bothLegacy");

        MvcResult res = cancel(o.number, "{\"email\":\"" + o.email + "\"}", null, null);

        assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(409);
        assertThat(json.readTree(raw(res)).path("reason").asString()).isEqualTo("PAYMENT_STATE");
        assertThat(col(o, "status")).isEqualTo("PENDING");
        verify(stripeRefundClient, never()).create(any(), any());
    }

    @Test
    @DisplayName("a payment that has not settled (AUTHORIZED, PENDING) is still refused with the contact-the-shop 409")
    void unsettledPayment_isRefused() throws Exception {
        for (String k : new String[]{"authorized", "payPending"}) {
            Seeded o = ORD.get(k);
            MvcResult res = cancel(o.number, "{\"email\":\"" + o.email + "\"}", null, null);
            assertThat(res.getResponse().getStatus()).as(k + ": " + body(res)).isEqualTo(409);
            assertThat(col(o, "status")).isEqualTo("PENDING");
        }
        verify(stripeRefundClient, never()).create(any(), any());
    }

    // ------------------------------------------------------------------
    // Ownership
    // ------------------------------------------------------------------
    @Test
    @DisplayName("the wrong email, or another tenant's order number, is a 404 and changes nothing (no oracle)")
    void wrongEmail_isNotFound() throws Exception {
        Seeded mine = ORD.get("mine");
        Seeded theirs = ORD.get("theirs");

        MvcResult wrong = cancel(mine.number, "{\"email\":\"someone-else@example.com\"}", null, null);
        MvcResult crossed = cancel(theirs.number, "{\"email\":\"" + mine.email + "\"}", null, null);

        assertThat(wrong.getResponse().getStatus()).as(body(wrong)).isEqualTo(404);
        assertThat(crossed.getResponse().getStatus()).as(body(crossed)).isEqualTo(404);
        assertThat(col(mine, "status")).isEqualTo("PENDING");
        assertThat(col(theirs, "status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("no email and no token is a 400, not an anonymous cancel")
    void noCredential_isBadRequest() throws Exception {
        Seeded o = ORD.get("noCred");

        MvcResult res = cancel(o.number, "{}", null, null);

        assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(400);
        assertThat(col(o, "status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a signed-in customer cancels with the verified token and NO email; the token's email is the only one used")
    void signedInCustomer_cancelsWithTheToken() throws Exception {
        Seeded o = ORD.get("token");
        when(customerJwtVerifier.verifiedEmail("good-token")).thenReturn(o.email);
        when(customerJwtVerifier.verifiedEmail("other-token")).thenReturn("not-the-owner@example.com");
        when(customerJwtVerifier.verifiedEmail("bad-token"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid token"));

        MvcResult other = cancel(o.number, "{}", null, "other-token");
        MvcResult bad = cancel(o.number, "{\"email\":\"" + o.email + "\"}", null, "bad-token");
        assertThat(other.getResponse().getStatus()).as("a token for someone else is the same 404").isEqualTo(404);
        assertThat(bad.getResponse().getStatus())
                .as("a bad token is a 401 and must not fall back to the body's email").isEqualTo(401);
        assertThat(col(o, "status")).isEqualTo("PENDING");

        MvcResult good = cancel(o.number, "{}", null, "good-token");
        assertThat(good.getResponse().getStatus()).as(body(good)).isEqualTo(200);
        assertThat(col(o, "status")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("F3: a signed-in customer cancelling an order placed under ANOTHER address with the guest pair is not a 404")
    void signedInCustomer_canCancelAnOrderTrackedWithTheGuestPair() throws Exception {
        Seeded o = ORD.get("cross");
        when(customerJwtVerifier.verifiedEmail("acct-token")).thenReturn("signed-in-account@example.com");

        MvcResult wrongGuest = cancel(o.number, "{\"email\":\"nobody@example.com\"}", null, "acct-token");
        assertThat(wrongGuest.getResponse().getStatus()).as("neither the account's address nor the body's is the owner's").isEqualTo(404);
        assertThat(col(o, "status")).isEqualTo("PENDING");

        MvcResult guestPair = cancel(o.number, "{\"email\":\"" + o.email + "\"}", null, "acct-token");

        assertThat(guestPair.getResponse().getStatus()).as(body(guestPair)).isEqualTo(200);
        assertThat(col(o, "status")).isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------------------
    // Idempotency
    // ------------------------------------------------------------------
    @Test
    @DisplayName("the same Idempotency-Key twice is one cancel and one refund; the same key for another order is a 422")
    void idempotencyKey_replaysAndIsScoped() throws Exception {
        Seeded o = ORD.get("idem");
        Seeded other = ORD.get("idemOther");
        com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
        stripeRefund.setId("re_868_idem");
        stripeRefund.setStatus("succeeded");
        when(stripeRefundClient.create(any(), any())).thenReturn(stripeRefund);
        String key = "key-868-idem-" + SEQ.incrementAndGet();

        MvcResult first = cancel(o.number, "{\"email\":\"" + o.email + "\"}", key, null);
        MvcResult replay = cancel(o.number, "{\"email\":\"" + o.email + "\"}", key, null);
        MvcResult crossed = cancel(other.number, "{\"email\":\"" + other.email + "\"}", key, null);

        assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).as(body(replay)).isEqualTo(200);
        assertThat(json.readTree(raw(replay)).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(longQ(o.tenant, "SELECT count(*) FROM refunds WHERE order_id = ?", o.id)).isEqualTo(1);
        assertThat(longQ(o.tenant, "SELECT count(*) FROM payment_event_outbox WHERE routing_key = 'order.state.cancelled' "
                + "AND payload LIKE ?", "%\"orderNumber\":\"" + o.number + "\"%")).isEqualTo(1);
        verify(stripeRefundClient, times(1)).create(any(), any());
        assertThat(crossed.getResponse().getStatus())
                .as("the same key on a different order is a payload mismatch").isEqualTo(422);
        assertThat(col(other, "status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("cancelling an already-cancelled order without a key is a 200 that changes nothing (a double tap is not an error)")
    void alreadyCancelled_isAHarmlessRepeat() throws Exception {
        Seeded o = ORD.get("repeat");

        MvcResult first = cancel(o.number, "{\"email\":\"" + o.email + "\"}", null, null);
        MvcResult second = cancel(o.number, "{\"email\":\"" + o.email + "\"}", null, null);

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).as(body(second)).isEqualTo(200);
        assertThat(json.readTree(raw(second)).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(longQ(o.tenant, "SELECT count(*) FROM payment_event_outbox WHERE routing_key = 'order.state.cancelled' "
                + "AND payload LIKE ?", "%\"orderNumber\":\"" + o.number + "\"%")).as("one event, not two").isEqualTo(1);
    }

    // ---- a Stripe double that keeps Stripe's idempotency rule, and a way to fail the local write after it ----

    private record StripeCallSeen(String key, Map<String, Object> params) { }

    private final Map<String, Map<String, Object>> stripeSeen = new ConcurrentHashMap<>();
    private final List<StripeCallSeen> stripeCalls = new ArrayList<>();

    /**
     * Stripe as it behaves for this purpose: a key seen before with the SAME parameters returns the refund
     * already made; the same key with DIFFERENT parameters is refused (IdempotencyException, 400).
     */
    private void fakeStripe() throws Exception {
        fakeStripe(null);
    }

    /** As {@link #fakeStripe()}, but Stripe REFUSES (400) any refund on {@code refusedIntent}. */
    private void fakeStripe(String refusedIntent) throws Exception {
        org.mockito.Mockito.doAnswer(inv -> {
            RefundCreateParams params = inv.getArgument(0);
            RequestOptions options = inv.getArgument(1);
            String key = options.getIdempotencyKey();
            Map<String, Object> canonical = params.toMap();
            // Flatten the metadata map so assertions can read metadata[refund_id].
            Map<String, Object> flat = new java.util.TreeMap<>();
            canonical.forEach((k, v) -> {
                if (v instanceof Map<?, ?> m) m.forEach((mk, mv) -> flat.put(k + "[" + mk + "]", mv));
                else flat.put(k, v);
            });
            stripeCalls.add(new StripeCallSeen(key, flat));
            if (refusedIntent != null && refusedIntent.equals(flat.get("payment_intent"))) {
                throw new InvalidRequestException("charge_already_refunded", "req_x", "charge_already_refunded", null, 400, null);
            }
            Map<String, Object> prev = stripeSeen.putIfAbsent(key, flat);
            if (prev != null && !prev.equals(flat)) {
                throw new IdempotencyException("Keys for idempotent requests can only be used with the same parameters "
                        + "they were first used with.", "req_idem", null, 400);
            }
            com.stripe.model.Refund refund = new com.stripe.model.Refund();
            refund.setId("re_" + key);
            refund.setStatus("succeeded");
            return refund;
        }).when(stripeRefundClient).create(any(), any());
    }

    /**
     * Runs {@code request} with a constraint on orders that refuses payment_status = 'REFUNDED': the one local
     * write that follows a successful Stripe refund. It is armed BEFORE the request (an ALTER TABLE issued
     * mid-request would wait for the request's own row locks) and dropped after, whatever happens.
     */
    private MvcResult withRefundedWriteBlocked(ThrowingSupplier<MvcResult> request) throws Exception {
        jdbc.execute("ALTER TABLE orders ADD CONSTRAINT it_868_no_refunded CHECK (payment_status <> 'REFUNDED') NOT VALID");
        try {
            return request.get();
        } finally {
            jdbc.execute("ALTER TABLE orders DROP CONSTRAINT it_868_no_refunded");
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    // ---- request + SQL helpers ----

    private MvcResult cancel(String number, String bodyJson, String idempotencyKey, String token) throws Exception {
        var req = post("/api/v1/public/orders/" + number + "/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson);
        if (idempotencyKey != null) req.header("Idempotency-Key", idempotencyKey);
        if (token != null) req.header("X-Customer-Token", token);
        return mockMvc.perform(req).andReturn();
    }

    private static String raw(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private static String body(MvcResult r) throws Exception {
        return "HTTP " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString();
    }

    private record Seeded(UUID id, UUID tenant, UUID shop, String number, String email) { }

    /** A column of the seeded order, read as the downgraded role with the order's tenant pinned. */
    private String col(Seeded o, String column) throws SQLException {
        return strQ(o.tenant, "SELECT " + column + " FROM orders WHERE id = ?", o.id);
    }

    private String strQ(UUID tenant, String sql, Object... params) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement pin = c.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                    pin.setString(1, tenant.toString());
                    pin.execute();
                }
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            } finally {
                c.rollback();
            }
        }
    }

    private long longQ(UUID tenant, String sql, Object... params) throws SQLException {
        return Long.parseLong(strQ(tenant, sql, params));
    }

    // ---- seeding (bootstrap superuser, before the downgrade) ----

    private UUID insertShop(UUID tenant, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                id, tenant, "868 " + slug, slug);
        return id;
    }

    private Seeded order(UUID tenant, UUID shop, String status, String paymentStatus, String paymentRef,
                         long total, Long deposit, Long balanceDue) {
        UUID id = UUID.randomUUID();
        int n = SEQ.incrementAndGet();
        String number = "ORD-868-" + n;
        String email = "cancel-" + n + "@example.com";
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                        + "item_count, placed_via, payment_status, payment_reference, deposit_amount_pennies, "
                        + "balance_due_pennies, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'Cancel Buyer', ?, ?, 'STANDARD', 0, ?, 0, 1, 'STOREFRONT', ?, ?, ?, ?, ?, ?)",
                id, tenant, shop, number, status, email, total, total, paymentStatus, paymentRef, deposit, balanceDue, now, now);
        return new Seeded(id, tenant, shop, number, email);
    }

    private void insertSale(Seeded o, long amount) {
        jdbc.update("INSERT INTO financial_transactions (id, tenant_id, created_at, amount_pennies, vat_rate, reference, "
                        + "order_id, entry_kind, shop_id) VALUES (?, ?, now(), ?, 'STANDARD', ?, ?, 'SALE', ?)",
                UUID.randomUUID(), o.tenant, amount, "Order " + o.number, o.id, o.shop);
    }
}
