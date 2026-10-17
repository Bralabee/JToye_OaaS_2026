package uk.jtoye.core.order;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import uk.jtoye.core.testsupport.SentMail;
import uk.jtoye.core.testsupport.StrictScopingGuard;
import uk.jtoye.core.webhook.WebhookFanoutListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 37-29 (D-18, RWO-041, roadmap SC-4): a vendor clears a flood of junk orders in ONE action, with a
 * reason kept on each order for their records.
 *
 * <p>{@code POST /api/v1/orders/bulk-cancel {orderIds[], reasonCode}} rejects each PENDING order through
 * the SAME cancel transition a single {@code POST /orders/{id}/cancel} uses — so stock bookkeeping, the
 * outbox event and the customer email are exactly what they are today — and reports one outcome per id:
 * {@code REJECTED} (it was PENDING and is now CANCELLED), {@code ALREADY_ACCEPTED} (the kitchen had taken
 * it; left alone), {@code ALREADY_CANCELLED} (nothing to do), {@code NOT_FOUND} (no such order the caller
 * may act on). Idempotent by construction: the same request again reports ALREADY_CANCELLED and writes
 * nothing, so a retried submit is safe without an Idempotency-Key.
 *
 * <p><b>Non-disclosing.</b> Each order needs STAFF on its OWN shop. An order of a shop the caller is not
 * granted, of another tenant, or that does not exist all read the same NOT_FOUND (T-37-74): the batch
 * must not become an oracle for which ids exist elsewhere.
 *
 * <p>Every arm drives the real HTTP endpoint and reads the stored rows back by SQL. Real Postgres 15
 * (Testcontainers), not {@code @Transactional}: each request commits. The bootstrap role is a SUPERUSER,
 * so these arms prove the application contract and the shop-access gate, not RLS (cross-tenant invisibility
 * of {@code findById} is the RLS wall the contract tests prove elsewhere).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class OrderBulkCancelIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // The no-leak arm reads the real cancellation email: the test profile turns the channel off, and
        // the sender is a mock at the SMTP boundary (no server), so Boot's mail health contributor is off too.
        registry.add("notification.email.enabled", () -> "true");
        registry.add("notification.email.from", () -> "noreply@jtoye.test");
        registry.add("notification.email.tracking-base-url", () -> "https://shop.jtoye.test");
        registry.add("management.health.mail.enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired ShopAccessService shopAccessService;
    /** The real listener that turns an order-state event into the customer email (no broker in this context). */
    @Autowired OrderStateChangeListener listener;
    /** The real fan-out that writes the signed webhook envelope to webhook_delivery. */
    @Autowired WebhookFanoutListener webhookFanout;
    /** The SMTP boundary: what EmailNotificationService hands to it is what the customer would receive. */
    @MockitoBean JavaMailSender mailSender;

    /** Dedicated tenant so a parallel fork's fixtures cannot collide on slug or order number. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000372900");
    private static final String SHOP_A_SLUG = "shop-37-29-bulk-a";
    private static final String SHOP_B_SLUG = "shop-37-29-bulk-b";
    private static final String BULK_CANCEL_URL = "/api/v1/orders/bulk-cancel";
    private static final String TOO_LARGE_TYPE = "https://jtoye.uk/errors/bulk-cancel-too-large";
    /** The default of jtoye.orders.bulk-cancel.max-ids; the test profile does not override it. */
    private static final int DEFAULT_MAX_IDS = 100;

    private UUID shopA;
    private UUID shopB;
    private Object bootedStrictScoping;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        Mockito.reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> SentMail.newMimeMessage());
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "37-29 Bulk Cancel Tenant");
        shopA = seedShopIdempotent(SHOP_A_SLUG, "37-29 Bulk Shop A");
        shopB = seedShopIdempotent(SHOP_B_SLUG, "37-29 Bulk Shop B");
    }

    @AfterEach
    void tearDown() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // SC-4: the flood. Three junk PENDING orders, one the kitchen already accepted, one id that is nothing.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("SC-4 / D-18: a batch of 3 PENDING + 1 CONFIRMED + 1 unknown id with reasonCode SUSPECTED_FAKE reports REJECTED x3, ALREADY_ACCEPTED, NOT_FOUND in order; the three rows are CANCELLED with the reason stored, the accepted one is untouched, product stock is unchanged and exactly three CANCELLED outbox events are written")
    void mixedBatch_rejectsThePendingOnes_andReportsTheRest() throws Exception {
        UUID product = seedProduct("Jollof (bulk)", 10);
        UUID pending1 = seedOrder(shopA, OrderStatus.PENDING, product);
        UUID pending2 = seedOrder(shopA, OrderStatus.PENDING, product);
        UUID pending3 = seedOrder(shopA, OrderStatus.PENDING, product);
        UUID confirmed = seedOrder(shopA, OrderStatus.CONFIRMED, product);
        UUID unknown = UUID.randomUUID();
        int stockBefore = stockOf(product);
        int eventsBefore = cancelledEventsFor(TENANT_ID);

        MvcResult result = bulkCancel(List.of(pending1, pending2, pending3, confirmed, unknown),
                "SUSPECTED_FAKE", adminJwt());
        String text = responseText(result);
        JsonNode body = json(result, 200);

        assertThat(outcomes(body)).as(text).containsExactly(
                pending1 + "=REJECTED", pending2 + "=REJECTED", pending3 + "=REJECTED",
                confirmed + "=ALREADY_ACCEPTED", unknown + "=NOT_FOUND");

        for (UUID id : List.of(pending1, pending2, pending3)) {
            Map<String, Object> row = orderRow(id);
            assertThat(row.get("status")).as("order " + id + ": " + row).isEqualTo("CANCELLED");
            assertThat(row.get("cancel_reason_code")).as("the reason is kept on order " + id + ": " + row)
                    .isEqualTo("SUSPECTED_FAKE");
            assertThat(cancelledEventsFor(id)).as("one CANCELLED outbox event for order " + id).isEqualTo(1);
        }
        Map<String, Object> accepted = orderRow(confirmed);
        assertThat(accepted.get("status")).as("the accepted order is left alone: " + accepted).isEqualTo("CONFIRMED");
        assertThat(accepted.get("cancel_reason_code")).as("no reason on an order that was not cancelled: " + accepted)
                .isNull();
        assertThat(cancelledEventsFor(confirmed)).as("no event for the accepted order").isZero();

        // A PENDING order has never taken stock (the decrement happens at CONFIRMED), so the single cancel
        // path restores nothing for it; the invariant a bulk reject must keep is therefore "unchanged".
        assertThat(stockOf(product)).as("stock is exactly what it was").isEqualTo(stockBefore);
        assertThat(cancelledEventsFor(TENANT_ID) - eventsBefore)
                .as("exactly three CANCELLED events were written for this batch").isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // Idempotent by construction: the same request again changes nothing and announces nothing.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("SC-4: the same request a second time reports ALREADY_CANCELLED for every rejected order, ALREADY_ACCEPTED and NOT_FOUND as before, and writes no further outbox event")
    void sameRequestAgain_isAlreadyCancelled_andWritesNoEvent() throws Exception {
        UUID product = seedProduct("Moi moi (bulk replay)", 10);
        UUID pending1 = seedOrder(shopA, OrderStatus.PENDING, product);
        UUID pending2 = seedOrder(shopA, OrderStatus.PENDING, product);
        UUID pending3 = seedOrder(shopA, OrderStatus.PENDING, product);
        UUID confirmed = seedOrder(shopA, OrderStatus.CONFIRMED, product);
        UUID unknown = UUID.randomUUID();
        List<UUID> ids = List.of(pending1, pending2, pending3, confirmed, unknown);

        json(bulkCancel(ids, "SUSPECTED_FAKE", adminJwt()), 200);
        int eventsAfterFirst = cancelledEventsFor(TENANT_ID);

        MvcResult again = bulkCancel(ids, "SUSPECTED_FAKE", adminJwt());
        String text = responseText(again);
        JsonNode body = json(again, 200);
        assertThat(outcomes(body)).as(text).containsExactly(
                pending1 + "=ALREADY_CANCELLED", pending2 + "=ALREADY_CANCELLED", pending3 + "=ALREADY_CANCELLED",
                confirmed + "=ALREADY_ACCEPTED", unknown + "=NOT_FOUND");
        assertThat(cancelledEventsFor(TENANT_ID)).as("no second transition, so no second event")
                .isEqualTo(eventsAfterFirst);
        for (UUID id : List.of(pending1, pending2, pending3)) {
            assertThat(cancelledEventsFor(id)).as("still exactly one event for order " + id).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // T-37-74: each order needs STAFF on its OWN shop, and a refusal says nothing.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T-37-74: a STAFF user of shop A rejects shop A's order but an order of shop B reads NOT_FOUND exactly like an unknown id, and shop B's order stays PENDING with no reason stored")
    void ungrantedShop_isNotFound_indistinguishableFromAnUnknownId() throws Exception {
        UUID product = seedProduct("Puff puff (bulk scope)", 10);
        UUID mine = seedOrder(shopA, OrderStatus.PENDING, product);
        UUID theirs = seedOrder(shopB, OrderStatus.PENDING, product);
        UUID unknown = UUID.randomUUID();
        UUID staffUser = UUID.randomUUID();
        grantShopStaff(staffUser, shopA, "STAFF");
        StrictScopingGuard.set(shopAccessService, true);

        MvcResult result = bulkCancel(List.of(mine, theirs, unknown), "CANNOT_FULFIL", staffJwt(staffUser));
        String text = responseText(result);
        JsonNode body = json(result, 200);
        assertThat(outcomes(body)).as(text).containsExactly(
                mine + "=REJECTED", theirs + "=NOT_FOUND", unknown + "=NOT_FOUND");

        // The two NOT_FOUND entries carry exactly the same fields: nothing distinguishes "exists elsewhere".
        JsonNode theirsEntry = body.path("results").get(1);
        JsonNode unknownEntry = body.path("results").get(2);
        assertThat(fieldNames(theirsEntry)).as(text).isEqualTo(fieldNames(unknownEntry));

        Map<String, Object> other = orderRow(theirs);
        assertThat(other.get("status")).as("shop B's order was not touched: " + other).isEqualTo("PENDING");
        assertThat(other.get("cancel_reason_code")).as(other.toString()).isNull();
        assertThat(cancelledEventsFor(theirs)).isZero();
        Map<String, Object> own = orderRow(mine);
        assertThat(own.get("status")).as("positive control, the granted shop's order: " + own).isEqualTo("CANCELLED");
        assertThat(own.get("cancel_reason_code")).isEqualTo("CANNOT_FULFIL");
    }

    // ------------------------------------------------------------------
    // The size cap is configuration (jtoye.orders.bulk-cancel.max-ids, default 100), refused with a typed 400.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-18: 101 ids is a typed 400 bulk-cancel-too-large naming the count and the limit of 100, while exactly 100 ids is accepted")
    void aboveTheConfiguredMaximum_isTyped400_andTheMaximumItselfIsAccepted() throws Exception {
        List<UUID> oneTooMany = IntStream.range(0, DEFAULT_MAX_IDS + 1).mapToObj(i -> UUID.randomUUID()).toList();

        MvcResult refused = bulkCancel(oneTooMany, "OTHER", adminJwt());
        String text = responseText(refused);
        JsonNode problem = json(refused, 400);
        assertThat(problem.path("type").asString()).as(text).isEqualTo(TOO_LARGE_TYPE);
        assertThat(problem.path("code").asString()).as(text).isEqualTo("BULK_CANCEL_TOO_LARGE");
        assertThat(problem.path("count").asInt()).as(text).isEqualTo(DEFAULT_MAX_IDS + 1);
        assertThat(problem.path("limit").asInt()).as(text).isEqualTo(DEFAULT_MAX_IDS);
        assertThat(problem.path("status").asInt()).as("status stays the HTTP code: " + text).isEqualTo(400);

        List<UUID> exactlyTheMaximum = oneTooMany.subList(0, DEFAULT_MAX_IDS);
        MvcResult accepted = bulkCancel(exactlyTheMaximum, "OTHER", adminJwt());
        JsonNode body = json(accepted, 200);
        assertThat(body.path("results").size()).as(responseText(accepted)).isEqualTo(DEFAULT_MAX_IDS);
        assertThat(outcomes(body)).as("unknown ids every one").allMatch(o -> o.endsWith("=NOT_FOUND"));
    }

    @Test
    @DisplayName("D-18: a bulk cancel without a reasonCode, or with a code outside the four, is a 400 and nothing is written")
    void missingOrUnknownReason_is400() throws Exception {
        UUID product = seedProduct("Chin chin (bulk validation)", 10);
        UUID pending = seedOrder(shopA, OrderStatus.PENDING, product);

        MvcResult noReason = mockMvc.perform(post(BULK_CANCEL_URL).with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderIds\":[\"" + pending + "\"]}")).andReturn();
        assertThat(noReason.getResponse().getStatus()).as(responseText(noReason)).isEqualTo(400);

        MvcResult badReason = bulkCancel(List.of(pending), "LOOKED_WRONG", adminJwt());
        assertThat(badReason.getResponse().getStatus()).as(responseText(badReason)).isEqualTo(400);

        Map<String, Object> row = orderRow(pending);
        assertThat(row.get("status")).as(row.toString()).isEqualTo("PENDING");
        assertThat(row.get("cancel_reason_code")).isNull();
    }

    @Test
    @DisplayName("AI-02: bulk cancel needs the orders:write scope like every other order mutation")
    void withoutWriteScope_is403_andNothingIsWritten() throws Exception {
        UUID product = seedProduct("Suya (bulk scope)", 10);
        UUID pending = seedOrder(shopA, OrderStatus.PENDING, product);
        RequestPostProcessor noScope = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"));

        MvcResult refused = bulkCancel(List.of(pending), "OTHER", noScope);
        assertThat(refused.getResponse().getStatus()).as(responseText(refused)).isEqualTo(403);
        assertThat(orderRow(pending).get("status")).isEqualTo("PENDING");
    }

    // ==================================================================
    // Task 2 (D-18, UI-SPEC D1 open item 2, T-37-75): the reason is kept, and never reaches the customer.
    // ==================================================================

    // ------------------------------------------------------------------
    // Every outbound artefact of a rejection is captured and scanned: the outbox event payload (the bytes the
    // broker carries, read by SQL), the customer cancellation email (the real listener and template, the sender
    // mocked at the SMTP boundary), the webhook envelope (the real fan-out, read from webhook_delivery.payload)
    // and the public tracking response (HTTP). The scan is for the code AND the word "fake" in any case: a
    // rewording that leaks the meaning without the token is still a leak. Each artefact first proves it is the
    // real thing (a control), because an empty string contains nothing.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T-37-75: after a SUSPECTED_FAKE bulk reject the outbox event, the customer cancellation email, the webhook envelope and the public tracking payload each say the order is cancelled and none carries the reason code or the word fake in any case")
    void reasonNeverReachesTheCustomer() throws Exception {
        UUID product = seedProduct("Egusi (no leak)", 10);
        UUID orderId = seedOrder(shopA, OrderStatus.PENDING, product);
        String orderNumber = orderNumberOf(orderId);
        String customerEmail = customerEmailOf(orderId);
        seedWebhookSubscription();

        json(bulkCancel(List.of(orderId), "SUSPECTED_FAKE", adminJwt()), 200);
        assertThat(orderRow(orderId).get("cancel_reason_code")).as("control: the reason IS on the row")
                .isEqualTo("SUSPECTED_FAKE");

        // 1. The order-state event as persisted to the outbox.
        String eventPayload = outboxPayloadFor(orderId);
        assertThat(eventPayload).as("control: this order's CANCELLED event")
                .contains(orderId.toString()).contains("\"newStatus\":\"CANCELLED\"");
        assertNoReason("outbox event payload", eventPayload);
        OrderStateChangeEvent event = jsonMapper.readValue(eventPayload, OrderStateChangeEvent.class);

        // 2. The customer email: the real listener and template, captured at the SMTP boundary and re-parsed
        //    from the wire bytes (headers and body alike).
        listener.handleOrderStateChange(event);
        MimeMessage sent = SentMail.reparse((MimeMessage) awaitSingleSend());
        assertThat(SentMail.recipients(sent, Message.RecipientType.TO)).as("control: it went to the customer")
                .containsExactly(customerEmail);
        assertThat(SentMail.text(sent)).as("control: it is the cancellation email")
                .contains("has been cancelled").contains(orderNumber);
        assertNoReason("customer cancellation email (headers and body)", SentMail.raw(sent));

        // 3. The webhook envelope: the real fan-out writes the exact bytes it will sign and POST.
        webhookFanout.onOrderState(event);
        String envelope = webhookPayloadFor(orderId);
        assertThat(envelope).as("control: the delivery row carries this order's cancelled event")
                .contains("\"type\":\"order.cancelled\"").contains(orderId.toString());
        assertNoReason("webhook envelope", envelope);

        // 4. The public tracking payload the customer's browser reads.
        MvcResult tracking = mockMvc.perform(get("/api/v1/public/orders/" + orderNumber)
                .param("email", customerEmail)).andReturn();
        String trackingBody = responseText(tracking);
        assertThat(tracking.getResponse().getStatus()).as(trackingBody).isEqualTo(200);
        assertThat(trackingBody).as("control: the customer sees it cancelled").contains("\"status\":\"CANCELLED\"");
        assertNoReason("public tracking payload", trackingBody);
    }

    // ------------------------------------------------------------------
    // The single cancel gains an optional body. Without one it is byte-for-byte the cancel it was.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-18: POST /orders/{id}/cancel with {reasonCode: OTHER} cancels and stores OTHER; with no body it cancels, stores NULL and restores stock exactly as before; with a code outside the four it is a 400 and the order is untouched")
    void singleCancel_storesTheReasonWhenGiven_andNothingWhenNot() throws Exception {
        UUID product = seedProduct("Akara (single cancel)", 10);
        UUID withReason = seedOrder(shopA, OrderStatus.PENDING, product);
        UUID withoutBody = seedOrder(shopA, OrderStatus.CONFIRMED, product);
        UUID badCode = seedOrder(shopA, OrderStatus.PENDING, product);
        int stockBefore = stockOf(product);

        MvcResult reasoned = mockMvc.perform(post(cancelUrl(withReason)).with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"OTHER\"}")).andReturn();
        assertThat(json(reasoned, 200).path("status").asString()).as(responseText(reasoned)).isEqualTo("CANCELLED");
        Map<String, Object> row = orderRow(withReason);
        assertThat(row.get("status")).as(row.toString()).isEqualTo("CANCELLED");
        assertThat(row.get("cancel_reason_code")).as("the reason is kept on the single cancel too: " + row)
                .isEqualTo("OTHER");

        MvcResult bare = mockMvc.perform(post(cancelUrl(withoutBody)).with(adminJwt())).andReturn();
        assertThat(json(bare, 200).path("status").asString()).as(responseText(bare)).isEqualTo("CANCELLED");
        Map<String, Object> bareRow = orderRow(withoutBody);
        assertThat(bareRow.get("status")).as(bareRow.toString()).isEqualTo("CANCELLED");
        assertThat(bareRow.get("cancel_reason_code")).as("no body records no reason, as before: " + bareRow).isNull();
        assertThat(cancelledEventsFor(withoutBody)).as("the single cancel still announces itself").isEqualTo(1);
        // A CONFIRMED order had taken its 2 units; the cancel gives them back (the same path a bulk reject uses).
        assertThat(stockOf(product)).as("stock restored by the CONFIRMED order's cancel").isEqualTo(stockBefore + 2);

        MvcResult refused = mockMvc.perform(post(cancelUrl(badCode)).with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"LOOKED_WRONG\"}")).andReturn();
        assertThat(refused.getResponse().getStatus()).as(responseText(refused)).isEqualTo(400);
        Map<String, Object> untouched = orderRow(badCode);
        assertThat(untouched.get("status")).as(untouched.toString()).isEqualTo("PENDING");
        assertThat(untouched.get("cancel_reason_code")).isNull();
    }

    /** The reason code, and the word behind it, in any case. */
    private static void assertNoReason(String surface, String artefact) {
        String lower = artefact.toLowerCase(Locale.ROOT);
        assertThat(lower).as(surface + " must not carry the reason code").doesNotContain("suspected_fake");
        assertThat(lower).as(surface + " must not carry the word fake in any case").doesNotContain("fake");
    }

    /** Wait for the listener's @Async send (up to 10 s), then insist on exactly one. */
    private Object awaitSingleSend() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<Invocation> sends = sends();
        while (sends.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            sends = sends();
        }
        assertThat(sends).as("the listener sent the customer email (waited 10 s for the @Async send)").isNotEmpty();
        Thread.sleep(500);
        sends = sends();
        assertThat(sends).as("exactly one customer email per transition").hasSize(1);
        return sends.get(0).getArgument(0);
    }

    private List<Invocation> sends() {
        return Mockito.mockingDetails(mailSender).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .toList();
    }

    private static String cancelUrl(UUID orderId) {
        return "/api/v1/orders/" + orderId + "/cancel";
    }

    private String orderNumberOf(UUID orderId) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT order_number FROM orders WHERE id = ?", String.class, orderId));
    }

    private String customerEmailOf(UUID orderId) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT customer_email FROM orders WHERE id = ?", String.class, orderId));
    }

    /** The one CANCELLED outbox payload naming the order. */
    private String outboxPayloadFor(UUID orderId) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT payload FROM payment_event_outbox WHERE tenant_id = ? AND event_type = 'ORDER_STATE_CHANGED' "
                        + "AND routing_key = 'order.state.cancelled' AND payload LIKE '%' || ? || '%'",
                String.class, TENANT_ID, orderId.toString()));
    }

    /** The one webhook delivery payload naming the order. */
    private String webhookPayloadFor(UUID orderId) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT payload FROM webhook_delivery WHERE tenant_id = ? AND payload LIKE '%' || ? || '%'",
                String.class, TENANT_ID, orderId.toString()));
    }

    /** An ACTIVE subscription to order-state events, so the fan-out writes a delivery row to read. */
    private void seedWebhookSubscription() {
        jdbcTemplate.update("INSERT INTO webhook_subscription "
                        + "(id, tenant_id, target_url, event_types, signing_secret, status) "
                        + "VALUES (?, ?, 'https://vendor.example.test/hook', ARRAY['ORDER_STATE_CHANGED'], "
                        + "'whsec_bulk_cancel_fixture', 'ACTIVE')",
                UUID.randomUUID(), TENANT_ID);
    }

    // ---- HTTP ----

    private MvcResult bulkCancel(List<UUID> ids, String reasonCode, RequestPostProcessor auth) throws Exception {
        String idList = ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        String body = "{\"orderIds\":[" + idList + "],\"reasonCode\":\"" + reasonCode + "\"}";
        return mockMvc.perform(post(BULK_CANCEL_URL).with(auth)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    /** "{orderId}={outcome}" per result, in response order. */
    private static List<String> outcomes(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.path("results").forEach(n -> out.add(n.path("orderId").asString() + "=" + n.path("outcome").asString()));
        return out;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    // ---- SQL reads ----

    private Map<String, Object> orderRow(UUID orderId) {
        return inTenant(() -> jdbcTemplate.queryForMap(
                "SELECT status, cancel_reason_code FROM orders WHERE id = ?", orderId));
    }

    private int stockOf(UUID productId) {
        Integer n = inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT quantity_in_stock FROM products WHERE id = ?", Integer.class, productId));
        return n == null ? Integer.MIN_VALUE : n;
    }

    /** CANCELLED order-state events in the outbox whose payload names the given id (an order id, or the tenant for all). */
    private int cancelledEventsFor(UUID id) {
        Integer n = inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_event_outbox WHERE tenant_id = ? AND event_type = 'ORDER_STATE_CHANGED' "
                        + "AND routing_key = 'order.state.cancelled' AND payload LIKE '%' || ? || '%'",
                Integer.class, TENANT_ID, id.equals(TENANT_ID) ? "\"newStatus\":\"CANCELLED\"" : id.toString()));
        return n == null ? -1 : n;
    }

    // ---- fixtures ----

    private UUID seedProduct(String title, int stock) {
        UUID id = UUID.randomUUID();
        inTenant(() -> jdbcTemplate.update(
                "INSERT INTO products (id, tenant_id, sku, title, ingredients_text, quantity_in_stock) "
                        + "VALUES (?, ?, ?, ?, 'fixture', ?)",
                id, TENANT_ID, "SKU-3729-" + id.toString().substring(0, 8), title, stock));
        return id;
    }

    private UUID seedOrder(UUID shop, OrderStatus status, UUID product) {
        UUID id = UUID.randomUUID();
        String suffix = id.toString().substring(0, 8);
        inTenant(() -> {
            jdbcTemplate.update(
                    "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                            + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                            + "item_count, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'Bulk Customer', ?, 1000, 'STANDARD', 200, 1200, 0, 1, now(), now())",
                    id, TENANT_ID, shop, "ORD-3729-" + suffix, status.name(), "bulk-" + suffix + "@example.test");
            jdbcTemplate.update(
                    "INSERT INTO order_items (id, tenant_id, order_id, product_id, product_name, quantity, "
                            + "unit_price_pennies, total_price_pennies) VALUES (?, ?, ?, ?, 'Bulk line', 2, 500, 1000)",
                    UUID.randomUUID(), TENANT_ID, id, product);
            return null;
        });
        return id;
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

    // ---- JSON ----

    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus()).as(responseText(result)).isEqualTo(expectedStatus);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String responseText(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }
}
