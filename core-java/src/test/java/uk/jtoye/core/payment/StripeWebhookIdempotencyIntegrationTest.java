package uk.jtoye.core.payment;

import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUDIT-W0-03 regression: verify double-delivery of the same Stripe event_id
 * yields exactly one financial_transactions row, one publishStateChange call,
 * and one publishSucceeded call. The dedup is enforced via TOCTOU-safe
 * {@code INSERT ... ON CONFLICT DO NOTHING} against the
 * {@code processed_stripe_events} table created in V35.
 *
 * <p>Runs against Testcontainers Postgres 15 so the real Flyway migration is
 * exercised end-to-end. {@code Webhook.constructEvent} is stubbed via
 * {@link MockedStatic} (mockito-inline) so we can post a synthetic
 * {@code payment_intent.succeeded} event without holding a real Stripe
 * webhook secret. RabbitMQ collaborators are {@link MockitoBean}-replaced so
 * the test can assert exactly-once publish semantics without standing up a
 * broker.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@ExtendWith(OutputCaptureExtension.class)
class StripeWebhookIdempotencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // src/test/resources/application-test.yml defaults to H2; override every
        // property that yml file sets so Testcontainers Postgres is actually
        // used. Mirrors CrossTenantSpoofIntegrationTest's pattern.
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        // RabbitMQ stubs — OrderEventPublisher has a compile-time RabbitTemplate
        // dependency. Listener auto-startup is disabled and host points at a
        // dead port so context boots without a live broker. We additionally
        // @MockitoBean OrderEventPublisher + PaymentEventPublisher so no Rabbit
        // calls are issued during tests.
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        // Stripe needs *some* webhook secret because PaymentService passes it to
        // Webhook.constructEvent — but we mock the call so the value never matters.
        registry.add("stripe.webhook-secret", () -> "whsec_test_idempotency");
    }

    @Autowired private PaymentService paymentService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ShopRepository shopRepository;

    @MockitoBean private OrderEventPublisher orderEventPublisher;
    @MockitoBean private PaymentEventPublisher paymentEventPublisher;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000a04");

    private Order seededOrder;
    private UUID seededShopId;

    @BeforeEach
    void seed() {
        // Hygienic: prior tests in the same Spring context may have populated
        // these tables. The test asserts exact-row-counts for the seeded
        // event/order, so wipe state to keep the assertions tight.
        jdbcTemplate.update("DELETE FROM processed_stripe_events");
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "Tenant Idempotency");

        TenantContext.set(TENANT_ID);
        try {
            // Create a shop so the order's FK constraint is satisfied. shops.slug
            // is NOT NULL and globally unique — derive a stable, test-scoped value.
            Shop shop = new Shop();
            shop.setTenantId(TENANT_ID);
            shop.setName("Idempotency Shop");
            shop.setSlug("idempotency-shop-" + UUID.randomUUID().toString().substring(0, 8));
            shop.setAddress("1 Test Lane");
            Shop savedShop = shopRepository.save(shop);
            seededShopId = savedShop.getId();
        } finally {
            TenantContext.clear();
        }
        seededOrder = seedOrder(OrderStatus.DRAFT, null);
    }

    /** An order awaiting its card payment, in {@code status}, optionally carrying a PaymentIntent reference. */
    private Order seedOrder(OrderStatus status, String paymentReference) {
        TenantContext.set(TENANT_ID);
        try {
            Order order = new Order();
            order.setTenantId(TENANT_ID);
            order.setShopId(seededShopId);
            order.setOrderNumber("ORD-IDEMP-" + UUID.randomUUID().toString().substring(0, 8));
            order.setStatus(status);
            order.setPaymentStatus(PaymentStatus.PENDING);
            order.setPaymentReference(paymentReference);
            order.setCustomerEmail("idempotency@test.local");
            order.setCustomerName("Idempotency Test");
            order.setTotalAmountPennies(2500L);
            order.setSubtotalPennies(2500L);
            return orderRepository.save(order);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Load-bearing regression: same Stripe event_id delivered twice must
     * fire side-effects exactly once.
     */
    @Test
    void duplicateEventResultsInExactlyOneFinancialTransaction() {
        String eventId = "evt_test_idempotency_001";
        String paymentIntentId = "pi_test_idempotency_001";
        // Build the stubbed Event BEFORE entering MockedStatic.when(...) so the
        // inner Mockito.when() calls inside the helper do not nest under
        // Webhook.constructEvent's stubbing context (UnfinishedStubbingException).
        Event stubbedEvent = buildSucceededEvent(eventId, paymentIntentId,
                seededOrder.getId(), TENANT_ID);

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenReturn(stubbedEvent);

            paymentService.handleWebhookEvent("payload-1", "sig-1");
            // Second delivery: same event.id — must short-circuit at the dedup guard.
            paymentService.handleWebhookEvent("payload-2", "sig-2");
        }

        Long dedupRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_stripe_events WHERE event_id = ?",
                Long.class, eventId);
        assertThat(dedupRows)
                .as("processed_stripe_events should hold exactly one row for the duplicated event_id")
                .isEqualTo(1L);

        // financial_transactions.reference holds "Payment <pi_id> for Order <order_number>".
        Long ftRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM financial_transactions WHERE reference LIKE ?",
                Long.class, "%" + paymentIntentId + "%");
        assertThat(ftRows)
                .as("financial_transactions should hold exactly one row for the duplicated PaymentIntent")
                .isEqualTo(1L);

        verify(orderEventPublisher, times(1))
                .publishStateChange(any(), any(), any(), any(), any(), any());
        verify(paymentEventPublisher, times(1))
                .publishSucceeded(any(), any(), any(), any(), anyLong(), any());
    }

    /**
     * Sanity: a single delivery does insert a dedup row.
     */
    @Test
    void firstEventInsertsRow() {
        String eventId = "evt_first_only";
        String paymentIntentId = "pi_first_only";
        Event stubbedEvent = buildSucceededEvent(eventId, paymentIntentId,
                seededOrder.getId(), TENANT_ID);

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenReturn(stubbedEvent);
            paymentService.handleWebhookEvent("payload", "sig");
        }

        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_stripe_events WHERE event_id = ?",
                Long.class, eventId);
        assertThat(rows).isEqualTo(1L);
    }

    /**
     * Negative control: distinct event ids both run through — proves the
     * guard is keyed on event_id and not over-blocking.
     */
    @Test
    void distinctEventIdsBothProcess() {
        Event e1 = buildSucceededEvent("evt_distinct_1", "pi_distinct_1",
                seededOrder.getId(), TENANT_ID);
        Event e2 = buildSucceededEvent("evt_distinct_2", "pi_distinct_2",
                seededOrder.getId(), TENANT_ID);

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenReturn(e1);
            paymentService.handleWebhookEvent("payload-1", "sig-1");
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenReturn(e2);
            paymentService.handleWebhookEvent("payload-2", "sig-2");
        }

        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_stripe_events WHERE event_id IN (?, ?)",
                Long.class, "evt_distinct_1", "evt_distinct_2");
        assertThat(rows).isEqualTo(2L);

        // Both events are processed (each records the payment), but only the first finds the order in DRAFT.
        // Phase 30-05 (#958): the second used to reset the now-PENDING order to PENDING and announce DRAFT -> PENDING
        // again, which re-sends the customer's "Received" email; the state change is now published once.
        verify(paymentEventPublisher, times(2))
                .publishSucceeded(any(), any(), any(), any(), anyLong(), any());
        verify(orderEventPublisher, times(1))
                .publishStateChange(any(), any(), any(), any(), any(), any());
    }

    // --- Phase 30-05 (issue #958): a successful payment never moves an order backwards ---

    @Test
    void paymentOnDraftOrderMovesItToPending() {
        Order draft = seedOrder(OrderStatus.DRAFT, "pi_958_draft");
        Event event = buildSucceededEvent("evt_958_draft", "pi_958_draft", draft.getId(), TENANT_ID);

        deliver(event);

        assertThat(dbRow(draft.getId()))
                .containsEntry("status", "PENDING")
                .containsEntry("payment_status", "CAPTURED")
                .containsEntry("payment_reference", "pi_958_draft");
        verify(orderEventPublisher, times(1)).publishStateChange(eq(draft.getId()), eq(TENANT_ID), eq(seededShopId),
                eq(draft.getOrderNumber()), eq(OrderStatus.DRAFT), eq(OrderStatus.PENDING));
    }

    @Test
    void paymentOnConfirmedOrderKeepsItsStatus(CapturedOutput output) {
        // A payment link issued after the vendor accepted the order, paid late.
        Order confirmed = seedOrder(OrderStatus.CONFIRMED, "plink_958_confirmed");
        Event event = buildSucceededEvent("evt_958_confirmed", "pi_958_confirmed", confirmed.getId(), TENANT_ID);

        deliver(event);

        assertThat(dbRow(confirmed.getId()))
                .as("read straight from the row, not the entity cache")
                .containsEntry("status", "CONFIRMED")
                .containsEntry("payment_status", "CAPTURED")
                .containsEntry("payment_reference", "pi_958_confirmed");
        verify(orderEventPublisher, never()).publishStateChange(any(), any(), any(), any(), any(), any());
        verify(paymentEventPublisher, times(1))
                .publishSucceeded(eq(confirmed.getId()), any(), any(), eq("pi_958_confirmed"), anyLong(), any());
        assertThat(output.getOut() + output.getErr())
                .as("an open order paid late is normal, not a refund case")
                .doesNotContain("event=payment_on_closed_order order=" + confirmed.getOrderNumber());
    }

    @Test
    void paymentOnCancelledOrderKeepsItsStatus(CapturedOutput output) {
        // The vendor cancelled an unpaid order; the customer's payment lands afterwards.
        Order cancelled = seedOrder(OrderStatus.CANCELLED, "pi_958_cancelled");
        Event event = buildSucceededEvent("evt_958_cancelled", "pi_958_cancelled", cancelled.getId(), TENANT_ID);

        deliver(event);

        assertThat(dbRow(cancelled.getId()))
                .containsEntry("status", "CANCELLED")
                .containsEntry("payment_status", "CAPTURED")
                .containsEntry("payment_reference", "pi_958_cancelled");
        verify(orderEventPublisher, never()).publishStateChange(any(), any(), any(), any(), any(), any());
        // At WARN, not merely present: the test profile logs uk.jtoye at DEBUG, so a plain "contains" also passed
        // when the line was downgraded to debug (measured in the 30-05 break arm).
        assertThat(output.getOut() + output.getErr())
                .as("T-30-22: a captured payment on a closed order is logged at WARN for a refund decision")
                .containsPattern("WARN\\s+\\S*PaymentService - event=payment_on_closed_order order="
                        + java.util.regex.Pattern.quote(cancelled.getOrderNumber())
                        + " status=CANCELLED intent=pi_958_cancelled")
                .doesNotContain("idempotency@test.local");
    }

    // --- helpers -------------------------------------------------------

    private void deliver(Event event) {
        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(event);
            paymentService.handleWebhookEvent("payload", "sig");
        }
    }

    /** The order row as stored, read by SQL (the test superuser bypasses RLS). */
    private Map<String, Object> dbRow(UUID orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, payment_status, payment_reference FROM orders WHERE id = ?", orderId);
    }

    /**
     * Build a stub {@link Event} that mirrors the shape PaymentService
     * expects for {@code payment_intent.succeeded}. We mock the SDK's
     * {@code EventDataObjectDeserializer} to return a {@link PaymentIntent}
     * carrying the order/tenant metadata — same technique as
     * {@link PaymentServiceTest#handlePaymentSucceeded_transitionsOrder()}.
     */
    private Event buildSucceededEvent(String eventId, String paymentIntentId,
                                      UUID orderId, UUID tenantId) {
        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(paymentIntentId);
        when(intent.getMetadata()).thenReturn(Map.of(
                "order_id", orderId.toString(),
                "tenant_id", tenantId.toString()
        ));
        when(intent.getLatestCharge()).thenReturn(null);

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(intent));

        Event event = mock(Event.class);
        when(event.getId()).thenReturn(eventId);
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        return event;
    }
}
