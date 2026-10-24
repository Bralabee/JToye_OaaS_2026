package uk.jtoye.core.payment;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.RefundCreateParams;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 30-10 (issue #108, PR #1018 review finding 1, T-30-38..T-30-41, T-30-126):
 * integration tests verifying that:
 * <ul>
 *   <li>Refund creation sits behind {@code stripe-refund} and returns a typed 503
 *       with {@code Retry-After: 30} and marks the stored refund row {@code FAILED}
 *       when the breaker is open.</li>
 *   <li>Webhook charge lookup sits behind {@code stripe-lookup} and falls back to
 *       {@code "Card"} while still settling the order as {@code CAPTURED} when
 *       {@code stripe-lookup} is open.</li>
 *   <li>Business 4xx rejections ({@link InvalidRequestException} 400) on refunds do
 *       not count as provider failures and keep both {@code stripe} and
 *       {@code stripe-refund} {@code CLOSED}.</li>
 *   <li>Transport failures ({@link ApiConnectionException}) on refunds open
 *       {@code stripe-refund} while leaving the checkout {@code stripe} breaker
 *       {@code CLOSED}.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class StripeBreakerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("stripe.webhook-secret", () -> "whsec_test_breaker_30_10");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private PaymentService paymentService;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ShopRepository shopRepository;

    @MockitoBean private OrderEventPublisher orderEventPublisher;
    @MockitoBean private PaymentEventPublisher paymentEventPublisher;
    @MockitoBean private RefundEventPublisher refundEventPublisher;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000301010");

    private UUID seededShopId;

    @BeforeEach
    void setUp() {
        resetStripeBreakers();
        jdbcTemplate.update("DELETE FROM processed_stripe_events");
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "Tenant 30-10 Stripe Breakers");

        TenantContext.set(TENANT_ID);
        try {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Shop shop = new Shop();
            shop.setTenantId(TENANT_ID);
            shop.setName("Breaker Test Shop " + suffix);
            shop.setSlug("breaker-shop-" + suffix);
            shop.setAddress("10 Breaker Street");
            seededShopId = shopRepository.save(shop).getId();
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void tearDown() {
        resetStripeBreakers();
        TenantContext.clear();
    }

    private void resetStripeBreakers() {
        for (CircuitBreaker cb : circuitBreakerRegistry.getAllCircuitBreakers()) {
            if (cb.getName().equals("stripe") || cb.getName().startsWith("stripe-")) {
                cb.reset();
            }
        }
    }

    @Test
    @DisplayName("stripe-refund OPEN -> POST /refund returns 503 payment-provider-unavailable with Retry-After 30 and marks refund FAILED")
    void openStripeRefundBreaker_returnsTyped503AndMarksRefundRowFailed() throws Exception {
        Order order = seedOrder(OrderStatus.CONFIRMED, PaymentStatus.CAPTURED, "pi_brk_ref_open", 2500L);
        circuitBreakerRegistry.circuitBreaker("stripe-refund").transitionToOpenState();

        String body = """
                {"amountPennies":500,"reason":"REQUESTED_BY_CUSTOMER","note":"breaker open test"}
                """;

        try (MockedStatic<com.stripe.model.Refund> refundMock = mockStatic(com.stripe.model.Refund.class)) {
            MvcResult res = mockMvc.perform(post("/api/v1/orders/" + order.getId() + "/refund")
                            .with(adminJwt())
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content(body))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "30"))
                    .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/payment-provider-unavailable"))
                    .andExpect(jsonPath("$.title").value("Payment provider unavailable"))
                    .andExpect(jsonPath("$.detail").value(
                            "Card payments are temporarily unavailable. Please try again shortly."))
                    .andReturn();

            assertThat(res.getResponse().getContentAsString())
                    .as("T-30-40: error body must never leak internal circuit-breaker name")
                    .doesNotContain("stripe-refund");
            refundMock.verifyNoInteractions();
        }

        Map<String, Object> refundRow = jdbcTemplate.queryForMap(
                "SELECT status, failure_reason FROM refunds WHERE order_id = ?", order.getId());
        assertThat(refundRow.get("status"))
                .isNotEqualTo(RefundStatus.CREATING.name())
                .isEqualTo(RefundStatus.failed.name());
        assertThat((String) refundRow.get("failure_reason")).isNotBlank();
    }

    @Test
    @DisplayName("stripe-lookup OPEN -> payment_intent.succeeded still settles order as CAPTURED with fallback payment method 'Card'")
    void openStripeLookupBreaker_webhookStillCapturesOrderWithCardFallback() {
        Order draft = seedOrder(OrderStatus.DRAFT, PaymentStatus.PENDING, "pi_brk_lookup_open", 2500L);
        circuitBreakerRegistry.circuitBreaker("stripe-lookup").transitionToOpenState();

        Event event = buildSucceededEventWithCharge(
                "evt_brk_lookup_001", "pi_brk_lookup_open", "ch_brk_lookup_001", draft.getId(), TENANT_ID);

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class);
             MockedStatic<com.stripe.model.Charge> chargeMock = mockStatic(com.stripe.model.Charge.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any())).thenReturn(event);
            paymentService.handleWebhookEvent("payload", "sig");
            chargeMock.verifyNoInteractions();
        }

        Map<String, Object> orderRow = jdbcTemplate.queryForMap(
                "SELECT status, payment_status, payment_method FROM orders WHERE id = ?", draft.getId());
        assertThat(orderRow)
                .containsEntry("status", "PENDING")
                .containsEntry("payment_status", "CAPTURED")
                .containsEntry("payment_method", "Card");
    }

    @Test
    @DisplayName("rejected-refunds-keep-breakers-closed: slidingWindowSize + 2 refunds throwing InvalidRequestException(400) keep both stripe and stripe-refund CLOSED")
    void rejectedRefunds_4xxInvalidRequest_keepBothStripeBreakersClosed() throws Exception {
        Order order = seedOrder(OrderStatus.CONFIRMED, PaymentStatus.CAPTURED, "pi_brk_400_ref", 50_000L);
        CircuitBreaker refundBreaker = circuitBreakerRegistry.circuitBreaker("stripe-refund");
        CircuitBreaker checkoutBreaker = circuitBreakerRegistry.circuitBreaker("stripe");
        int callCount = refundBreaker.getCircuitBreakerConfig().getSlidingWindowSize() + 2;

        String body = """
                {"amountPennies":100,"reason":"REQUESTED_BY_CUSTOMER","note":"4xx rejection arm"}
                """;

        java.util.List<Integer> statuses = new java.util.ArrayList<>();
        try (MockedStatic<com.stripe.model.Refund> refundMock = mockStatic(com.stripe.model.Refund.class)) {
            refundMock.when(() -> com.stripe.model.Refund.create(
                            any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new InvalidRequestException(
                            "Charge has already been refunded.", "charge",
                            "req_400_brk", "charge_already_refunded", 400, null));

            for (int i = 0; i < callCount; i++) {
                MvcResult res = mockMvc.perform(post("/api/v1/orders/" + order.getId() + "/refund")
                                .with(adminJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", "idem-400-" + i + "-" + UUID.randomUUID())
                                .content(body))
                        .andReturn();
                statuses.add(res.getResponse().getStatus());
            }
        }

        assertThat(checkoutBreaker.getState())
                .as("checkout breaker 'stripe' must stay CLOSED after 4xx refund rejections")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(refundBreaker.getState())
                .as("refund breaker 'stripe-refund' must stay CLOSED after 4xx refund rejections")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(statuses).containsOnly(502);
    }

    @Test
    @DisplayName("transport-failures-isolated: slidingWindowSize + 2 refunds throwing ApiConnectionException open stripe-refund while keeping stripe CLOSED")
    void transportFailuresOnRefund_openStripeRefundAndKeepStripeClosed() throws Exception {
        Order order = seedOrder(OrderStatus.CONFIRMED, PaymentStatus.CAPTURED, "pi_brk_conn_ref", 50_000L);
        CircuitBreaker refundBreaker = circuitBreakerRegistry.circuitBreaker("stripe-refund");
        CircuitBreaker checkoutBreaker = circuitBreakerRegistry.circuitBreaker("stripe");
        int callCount = refundBreaker.getCircuitBreakerConfig().getSlidingWindowSize() + 2;

        String body = """
                {"amountPennies":100,"reason":"REQUESTED_BY_CUSTOMER","note":"transport failure arm"}
                """;

        try (MockedStatic<com.stripe.model.Refund> refundMock = mockStatic(com.stripe.model.Refund.class)) {
            refundMock.when(() -> com.stripe.model.Refund.create(
                            any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new ApiConnectionException("IOException during API request to Stripe"));

            for (int i = 0; i < callCount; i++) {
                mockMvc.perform(post("/api/v1/orders/" + order.getId() + "/refund")
                                .with(adminJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", "idem-conn-" + i + "-" + UUID.randomUUID())
                                .content(body));
            }
        }

        assertThat(refundBreaker.getState())
                .as("refund breaker 'stripe-refund' must transition to OPEN after slidingWindowSize transport failures")
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(checkoutBreaker.getState())
                .as("checkout breaker 'stripe' must remain isolated and CLOSED when stripe-refund opens")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private Order seedOrder(OrderStatus status, PaymentStatus paymentStatus,
                            String paymentReference, long totalPennies) {
        TenantContext.set(TENANT_ID);
        try {
            Order order = new Order();
            order.setTenantId(TENANT_ID);
            order.setShopId(seededShopId);
            order.setOrderNumber("ORD-BRK-" + UUID.randomUUID().toString().substring(0, 8));
            order.setStatus(status);
            order.setPaymentStatus(paymentStatus);
            order.setPaymentReference(paymentReference);
            order.setCustomerEmail("breaker@test.local");
            order.setCustomerName("Breaker Test");
            order.setTotalAmountPennies(totalPennies);
            order.setSubtotalPennies(totalPennies);
            return orderRepository.save(order);
        } finally {
            TenantContext.clear();
        }
    }

    private static RequestPostProcessor adminJwt() {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", TENANT_ID.toString()))
                .authorities(
                        new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_orders:write"));
    }

    private Event buildSucceededEventWithCharge(String eventId, String paymentIntentId,
                                                String latestChargeId, UUID orderId, UUID tenantId) {
        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(paymentIntentId);
        when(intent.getMetadata()).thenReturn(Map.of(
                "order_id", orderId.toString(),
                "tenant_id", tenantId.toString()
        ));
        when(intent.getLatestCharge()).thenReturn(latestChargeId);

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(intent));

        Event event = mock(Event.class);
        when(event.getId()).thenReturn(eventId);
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        return event;
    }
}
