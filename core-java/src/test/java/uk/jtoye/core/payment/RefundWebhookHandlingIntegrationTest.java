package uk.jtoye.core.payment;

import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Phase 17 VOPS-02 — refund webhook lifecycle integration test.
 *
 * <p>Covers the {@code refund.created} / {@code refund.updated} /
 * {@code refund.failed} / {@code charge.refunded} cases added to
 * {@link PaymentService#handleWebhookEvent} (Plan 17-03 Task 2). Uses the
 * same Testcontainers + signed-event-stub pattern as
 * {@link StripeWebhookIdempotencyIntegrationTest} so the Phase 16.1 dedup
 * guard is exercised end-to-end.
 *
 * <p><b>Hard guard:</b> the dedup INSERT must remain BEFORE the switch in
 * {@code PaymentService.handleWebhookEvent} — re-delivery of the same
 * {@code event.id} must short-circuit at the existing
 * {@code processed_stripe_events} guard. CORRECTION-2 LOCKED.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class RefundWebhookHandlingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Override every yml-set property so Testcontainers Postgres is actually
        // used. Mirrors StripeWebhookIdempotencyIntegrationTest's pattern.
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        // Disable RabbitMQ listener — we mock the publisher directly.
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("stripe.webhook-secret", () -> "whsec_test_refund");
    }

    @Autowired private PaymentService paymentService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrderRepository orderRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private ShopRepository shopRepository;

    // Replace AMQP collaborators so no broker is needed.
    @MockBean private OrderEventPublisher orderEventPublisher;
    @MockBean private PaymentEventPublisher paymentEventPublisher;
    // RefundEventPublisher writes to payment_event_outbox in the caller tx;
    // exercise it directly so we can assert outbox row counts.

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000b17");

    private Order seededOrder;
    private Refund seededRefund;

    @BeforeEach
    void seed() {
        // Hygienic — any prior test in the same Spring context may have left
        // rows behind. Wipe webhook + outbox + refund state so per-test counts
        // remain tight. orders + shops persist across tests via FK from
        // refunds; we re-seed both each time.
        jdbcTemplate.update("DELETE FROM processed_stripe_events");
        jdbcTemplate.update("DELETE FROM payment_event_outbox");
        jdbcTemplate.update("DELETE FROM refunds");
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "Tenant Refund");

        TenantContext.set(TENANT_ID);
        try {
            Shop shop = new Shop();
            shop.setTenantId(TENANT_ID);
            shop.setName("Refund Shop");
            shop.setSlug("refund-shop-" + UUID.randomUUID().toString().substring(0, 8));
            shop.setAddress("1 Refund Lane");
            Shop savedShop = shopRepository.save(shop);

            Order order = new Order();
            order.setTenantId(TENANT_ID);
            order.setShopId(savedShop.getId());
            order.setOrderNumber("ORD-REFUND-" + UUID.randomUUID().toString().substring(0, 8));
            order.setStatus(OrderStatus.REFUNDED);
            order.setPaymentStatus(PaymentStatus.REFUNDED);
            order.setPaymentReference("pi_test_" + UUID.randomUUID().toString().substring(0, 8));
            order.setCustomerEmail("refund@test.local");
            order.setCustomerName("Refund Test");
            order.setTotalAmountPennies(1000L);
            order.setSubtotalPennies(1000L);
            seededOrder = orderRepository.save(order);

            Refund refund = new Refund(
                    TENANT_ID,
                    seededOrder.getId(),
                    seededOrder.getPaymentReference(),
                    "test-idem-" + UUID.randomUUID().toString().substring(0, 8),
                    500L,
                    RefundReason.REQUESTED_BY_CUSTOMER,
                    "test note"
            );
            refund.setStatus(RefundStatus.CREATING);
            seededRefund = refundRepository.saveAndFlush(refund);
        } finally {
            TenantContext.clear();
        }
    }

    // ------------------------------------------------------------------
    // refund.created / refund.failed / refund.updated lifecycle
    // ------------------------------------------------------------------

    @Test
    void webhookRefundCreated_updatesLocalRefundStatusAndPersistsOutboxRow() {
        String eventId = "evt_refund_created_001";
        Event stubbed = buildRefundEvent(eventId, "refund.created",
                "re_test_xyz", "succeeded", null,
                seededRefund.getId(), TENANT_ID);

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(stubbed);
            paymentService.handleWebhookEvent("payload", "sig");
        }

        // Refund row was updated
        Refund after = refundRepository.findById(seededRefund.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RefundStatus.succeeded);
        assertThat(after.getStripeRefundId()).isEqualTo("re_test_xyz");

        // Outbox row was published with order.events exchange + REFUND_SUCCEEDED
        Long outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_outbox WHERE exchange = ? AND event_type = ?",
                Long.class, "order.events", "REFUND_SUCCEEDED");
        assertThat(outboxRows)
                .as("Successful refund webhook should persist exactly one outbox row "
                  + "to order.events with eventType=REFUND_SUCCEEDED")
                .isEqualTo(1L);
    }

    @Test
    void webhookRefundFailed_updatesStatusFailureReasonAndPublishesRefundFailed() {
        String eventId = "evt_refund_failed_001";
        Event stubbed = buildRefundEvent(eventId, "refund.failed",
                "re_test_fail", "failed", "card_declined",
                seededRefund.getId(), TENANT_ID);

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(stubbed);
            paymentService.handleWebhookEvent("payload", "sig");
        }

        Refund after = refundRepository.findById(seededRefund.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RefundStatus.failed);
        assertThat(after.getFailureReason()).isEqualTo("card_declined");
        assertThat(after.getStripeRefundId()).isEqualTo("re_test_fail");

        Long outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_outbox WHERE exchange = ? AND event_type = ?",
                Long.class, "order.events", "REFUND_FAILED");
        assertThat(outboxRows)
                .as("Failed refund webhook should publish REFUND_FAILED to order.events")
                .isEqualTo(1L);
    }

    @Test
    void webhookRefundPendingThenSucceeded_publishesUpdatedThenSucceeded() {
        // First delivery: pending -> publishes REFUND_UPDATED
        Event pendingEvent = buildRefundEvent("evt_refund_seq_pending", "refund.updated",
                "re_test_seq", "pending", null,
                seededRefund.getId(), TENANT_ID);
        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(pendingEvent);
            paymentService.handleWebhookEvent("payload-1", "sig-1");
        }

        // Second delivery: distinct event id, status flips to succeeded -> publishes REFUND_SUCCEEDED
        Event successEvent = buildRefundEvent("evt_refund_seq_success", "refund.updated",
                "re_test_seq", "succeeded", null,
                seededRefund.getId(), TENANT_ID);
        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(successEvent);
            paymentService.handleWebhookEvent("payload-2", "sig-2");
        }

        Refund after = refundRepository.findById(seededRefund.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RefundStatus.succeeded);

        Long updatedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_outbox WHERE event_type = ?",
                Long.class, "REFUND_UPDATED");
        Long succeededRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_outbox WHERE event_type = ?",
                Long.class, "REFUND_SUCCEEDED");
        assertThat(updatedRows)
                .as("First (pending) delivery should publish REFUND_UPDATED")
                .isEqualTo(1L);
        assertThat(succeededRows)
                .as("Second (succeeded) delivery should publish REFUND_SUCCEEDED")
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // Phase 16.1 dedup contract — re-delivery short-circuit
    // ------------------------------------------------------------------

    @Test
    void webhookRedeliveryWithSameEventId_doesNotMutateRefundTwice() {
        String eventId = "evt_refund_redelivery_001";
        Event stubbed = buildRefundEvent(eventId, "refund.created",
                "re_test_redeliv", "succeeded", null,
                seededRefund.getId(), TENANT_ID);

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(stubbed);

            paymentService.handleWebhookEvent("payload-1", "sig-1");
            // Capture state after first delivery
            Refund firstPass = refundRepository.findById(seededRefund.getId()).orElseThrow();
            OffsetDateTime updatedAfterFirst = firstPass.getUpdatedAt();
            Long firstOutboxRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM payment_event_outbox", Long.class);

            // Same event.id — must short-circuit at processed_stripe_events guard.
            paymentService.handleWebhookEvent("payload-2", "sig-2");

            Refund secondPass = refundRepository.findById(seededRefund.getId()).orElseThrow();
            Long secondOutboxRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM payment_event_outbox", Long.class);

            assertThat(secondPass.getUpdatedAt())
                    .as("Refund updatedAt must not change on dedup short-circuit")
                    .isEqualTo(updatedAfterFirst);
            assertThat(secondOutboxRows)
                    .as("Outbox must not gain a second row on dedup short-circuit")
                    .isEqualTo(firstOutboxRows);

            // processed_stripe_events still holds exactly one row for this id
            Long dedupRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM processed_stripe_events WHERE event_id = ?",
                    Long.class, eventId);
            assertThat(dedupRows).isEqualTo(1L);
        }
    }

    // ------------------------------------------------------------------
    // charge.refunded — UC-4 LOCKED documented no-op
    // ------------------------------------------------------------------

    @Test
    void webhookChargeRefunded_isDocumentedNoOpNoOutboxNoRefundChange() {
        String eventId = "evt_charge_refunded_001";
        Event stubbed = mock(Event.class);
        when(stubbed.getId()).thenReturn(eventId);
        when(stubbed.getType()).thenReturn("charge.refunded");

        OffsetDateTime before = refundRepository.findById(seededRefund.getId())
                .orElseThrow().getUpdatedAt();

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(stubbed);
            paymentService.handleWebhookEvent("payload", "sig");
        }

        // Refund row untouched
        OffsetDateTime after = refundRepository.findById(seededRefund.getId())
                .orElseThrow().getUpdatedAt();
        assertThat(after).isEqualTo(before);

        // Outbox stays empty
        Long outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_outbox", Long.class);
        assertThat(outboxRows).isEqualTo(0L);

        // Dedup row still inserted (proves we ran AFTER the dedup guard)
        Long dedupRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_stripe_events WHERE event_id = ?",
                Long.class, eventId);
        assertThat(dedupRows).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // Refund metadata absent — defensive lookup-by-stripe_refund_id
    // ------------------------------------------------------------------

    @Test
    void webhookRefundWithoutMetadata_findsByStripeRefundIdIfPresent() {
        // Pre-populate the seeded refund with a stripe_refund_id so the
        // fallback lookup-by-stripe_refund_id can find it.
        TenantContext.set(TENANT_ID);
        try {
            seededRefund.setStripeRefundId("re_externally_issued");
            refundRepository.saveAndFlush(seededRefund);
        } finally {
            TenantContext.clear();
        }

        String eventId = "evt_refund_no_meta_001";
        Event stubbed = buildRefundEvent(eventId, "refund.updated",
                "re_externally_issued", "succeeded", null,
                /* refundIdMetadata */ null, /* tenantIdMetadata */ null);

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(stubbed);
            paymentService.handleWebhookEvent("payload", "sig");
        }

        Refund after = refundRepository.findById(seededRefund.getId()).orElseThrow();
        assertThat(after.getStatus())
                .as("Defensive fallback should find the row via stripe_refund_id and apply status")
                .isEqualTo(RefundStatus.succeeded);
    }

    @Test
    void webhookRefundWithoutMetadataAndUnknownStripeId_logsWarningWithoutCrashing() {
        String eventId = "evt_refund_unknown_001";
        Event stubbed = buildRefundEvent(eventId, "refund.created",
                "re_totally_unknown", "succeeded", null,
                null, null);

        // Should not throw — handler logs and returns; dedup row stays committed
        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(stubbed);
            paymentService.handleWebhookEvent("payload", "sig");
        }

        // Original seeded refund untouched
        Refund after = refundRepository.findById(seededRefund.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RefundStatus.CREATING);

        // Dedup row exists, outbox empty
        Long dedupRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_stripe_events WHERE event_id = ?",
                Long.class, eventId);
        assertThat(dedupRows).isEqualTo(1L);
        Long outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_outbox", Long.class);
        assertThat(outboxRows).isEqualTo(0L);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Build a stubbed {@link Event} carrying a {@link com.stripe.model.Refund}
     * payload. metadata is populated only when the corresponding ID arg is
     * non-null — used to simulate dashboard-issued refunds with no metadata.
     */
    private Event buildRefundEvent(String eventId,
                                   String eventType,
                                   String stripeRefundId,
                                   String status,
                                   String failureReason,
                                   UUID localRefundId,
                                   UUID tenantId) {
        com.stripe.model.Refund refund = mock(com.stripe.model.Refund.class);
        when(refund.getId()).thenReturn(stripeRefundId);
        when(refund.getStatus()).thenReturn(status);
        when(refund.getFailureReason()).thenReturn(failureReason);

        Map<String, String> metadata = new HashMap<>();
        if (localRefundId != null) metadata.put("refund_id", localRefundId.toString());
        if (tenantId != null) metadata.put("tenant_id", tenantId.toString());
        // null arg combination simulates a Stripe-dashboard-issued refund
        // with no metadata at all (handler must locate by stripe_refund_id
        // or no-op gracefully).
        when(refund.getMetadata()).thenReturn(metadata.isEmpty() ? null : metadata);

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(refund));

        Event event = mock(Event.class);
        when(event.getId()).thenReturn(eventId);
        when(event.getType()).thenReturn(eventType);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        return event;
    }
}
