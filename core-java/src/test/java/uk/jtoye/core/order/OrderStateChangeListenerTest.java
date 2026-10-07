package uk.jtoye.core.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import uk.jtoye.core.config.BusinessMetricsService;
import uk.jtoye.core.notification.CustomerEmailContext;
import uk.jtoye.core.notification.EmailNotificationService;
import uk.jtoye.core.onboarding.TraderIdentityService;
import uk.jtoye.core.onboarding.TraderEntityType;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.storefront.dto.SellerIdentityDto;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderStateChangeListenerTest {

    private OrderStateChangeListener listener;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger listenerLogger;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private EmailNotificationService emailService;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Session hibernateSession;

    @Mock
    private BusinessMetricsService metrics;

    @Mock
    private SimpMessagingTemplate simpMessagingTemplate;

    @Mock
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private TraderIdentityService traderIdentityService;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(entityManager.unwrap(Session.class)).thenReturn(hibernateSession);
        java.sql.Connection mockConn = mock(java.sql.Connection.class);
        java.sql.PreparedStatement mockStmt = mock(java.sql.PreparedStatement.class);
        lenient().when(mockConn.prepareStatement(any(String.class))).thenReturn(mockStmt);
        lenient().doAnswer(inv -> { inv.<org.hibernate.jdbc.Work>getArgument(0).execute(mockConn); return null; }).when(hibernateSession).doWork(any());
        // FIX-2 dedup INSERT: default to "1 row inserted" (fresh delivery) so
        // the pre-existing side-effect tests exercise the full pipeline; the
        // duplicate-delivery test overrides this to 0.
        lenient().when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1);
        // #92: SSE broadcasting moved to OrderSseFanoutListener (per-instance
        // fan-out queue); this competing-consumer listener no longer touches SSE.
        listener = new OrderStateChangeListener(orderRepository, emailService, entityManager, metrics, simpMessagingTemplate, jdbcTemplate,
                shopRepository, traderIdentityService);
        listenerLogger = (Logger) LoggerFactory.getLogger(OrderStateChangeListener.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        listenerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        listenerLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("Should log state change for any transition")
    void handleOrderStateChange_logsTransition() {
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                UUID.randomUUID(), UUID.randomUUID(), "ORD-TEST-20260401-ABC",
                OrderStatus.DRAFT, OrderStatus.PENDING, OffsetDateTime.now()
        );

        listener.handleOrderStateChange(event);

        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("ORD-TEST-20260401-ABC")
                        && e.getFormattedMessage().contains("DRAFT")
                        && e.getFormattedMessage().contains("PENDING"));
    }

    @Test
    @DisplayName("Should handle COMPLETED status and send email")
    void handleOrderStateChange_completed() {
        UUID orderId = UUID.randomUUID();
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, UUID.randomUUID(), "ORD-TEST-20260401-CMP",
                OrderStatus.READY, OrderStatus.COMPLETED, OffsetDateTime.now()
        );

        Order order = new Order();
        order.setCustomerEmail("test@example.com");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        listener.handleOrderStateChange(event);

        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("COMPLETED"));

        verify(emailService).sendOrderCompletedNotification(eq(event), eq("test@example.com"), any(CustomerEmailContext.class));
    }

    @Test
    @DisplayName("Should handle CANCELLED status and send email")
    void handleOrderStateChange_cancelled() {
        UUID orderId = UUID.randomUUID();
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, UUID.randomUUID(), "ORD-TEST-20260401-CAN",
                OrderStatus.PENDING, OrderStatus.CANCELLED, OffsetDateTime.now()
        );

        Order order = new Order();
        order.setCustomerEmail("cancel@example.com");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        listener.handleOrderStateChange(event);

        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("CANCELLED"));

        verify(emailService).sendOrderCancelledNotification(eq(event), eq("cancel@example.com"), any(CustomerEmailContext.class));
    }

    @Test
    @DisplayName("Should handle missing order gracefully")
    void handleOrderStateChange_orderNotFound() {
        UUID orderId = UUID.randomUUID();
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, UUID.randomUUID(), "ORD-TEST-MISSING",
                OrderStatus.READY, OrderStatus.COMPLETED, OffsetDateTime.now()
        );

        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        listener.handleOrderStateChange(event);

        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("Should broadcast order state change to WebSocket topic")
    void handleOrderStateChange_broadcastsToWebSocket() {
        UUID orderId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, tenantId, "ORD-WS-001",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now()
        );

        Order order = new Order();
        order.setShopId(shopId);
        order.setCustomerEmail("ws@example.com");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        listener.handleOrderStateChange(event);

        String expectedTopic = "/topic/kitchen." + tenantId + "." + shopId;
        verify(simpMessagingTemplate).convertAndSend(expectedTopic, event);
    }

    @Test
    @DisplayName("Should skip WebSocket broadcast when order not found")
    void handleOrderStateChange_skipsWebSocketWhenOrderNotFound() {
        UUID orderId = UUID.randomUUID();
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, UUID.randomUUID(), "ORD-WS-MISSING",
                OrderStatus.READY, OrderStatus.COMPLETED, OffsetDateTime.now()
        );

        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        listener.handleOrderStateChange(event);

        verify(simpMessagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("Should continue pipeline when WebSocket broadcast fails")
    void handleOrderStateChange_webSocketFailureDoesNotBlockPipeline() {
        UUID orderId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, tenantId, "ORD-WS-FAIL",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now()
        );

        Order order = new Order();
        order.setShopId(shopId);
        order.setCustomerEmail("fail@example.com");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        doThrow(new RuntimeException("WebSocket down"))
                .when(simpMessagingTemplate).convertAndSend(anyString(), any(Object.class));

        listener.handleOrderStateChange(event);

        // Email pipeline should still execute despite WebSocket failure
        verify(emailService).sendOrderConfirmed(eq(event), eq("fail@example.com"), any(CustomerEmailContext.class));
    }

    @Test
    @DisplayName("FIX-2: duplicate delivery (dedup INSERT returns 0) skips ALL side effects")
    void handleOrderStateChange_duplicateDeliverySkipsAllSideEffects() {
        UUID orderId = UUID.randomUUID();
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, UUID.randomUUID(), "ORD-DUP-001",
                OrderStatus.DRAFT, OrderStatus.PENDING, OffsetDateTime.now()
        );

        // ON CONFLICT DO NOTHING hit — this (tenant, order, status) was
        // already processed by an earlier delivery.
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(0);

        listener.handleOrderStateChange(event);

        verifyNoInteractions(emailService);
        verifyNoInteractions(metrics);
        verifyNoInteractions(simpMessagingTemplate);
        verifyNoInteractions(orderRepository);
    }

    // =============================================================================================
    // 31.1-25 (#789/#785, Pitfall 8): the email context is built HERE, under the event's tenant
    // =============================================================================================

    private static final SellerIdentityDto SELLER = new SellerIdentityDto("Mama Ade Foods Ltd",
            TraderEntityType.COMPANY, "09876543", null, List.of("12 Market Street", "Birmingham", "B1 1AA"),
            "kitchen@x.example.com", null);

    private Order orderWithLines(UUID shopId, OrderChannel placedVia, Integer ackMask, String email) {
        Order order = new Order();
        order.setShopId(shopId);
        order.setCustomerEmail(email);
        order.setPlacedVia(placedVia);
        order.setAllergenAckMask(ackMask);
        order.setFulfilmentType(FulfilmentType.COLLECTION);
        OrderItem jollof = new OrderItem();
        jollof.setProductName("Jollof Rice");
        jollof.setAllergenMask(65);
        jollof.setAllergenFlagMask(0);
        OrderItem puff = new OrderItem();
        puff.setProductName("Puff Puff");
        puff.setAllergenMask(0);
        puff.setAllergenFlagMask(1 << 2);
        order.setItems(new java.util.ArrayList<>(List.of(jollof, puff)));
        return order;
    }

    @Test
    @DisplayName("Pitfall 8: shop, seller and allergen record are read while TenantContext is the event's tenant, "
            + "and handed to the send as one immutable context")
    void emailContextIsBuiltUnderTheEventsTenant() {
        UUID orderId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        OrderStateChangeEvent event = new OrderStateChangeEvent(orderId, tenantId, "ORD-CTX-1",
                OrderStatus.DRAFT, OrderStatus.PENDING, OffsetDateTime.now());
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(
                orderWithLines(shopId, OrderChannel.STOREFRONT, 65, "buyer@example.com")));
        Shop shop = new Shop();
        shop.setId(shopId);
        shop.setTenantId(tenantId);
        shop.setName("Mama Adé's Kitchen");
        shop.setEmail("kitchen@x.example.com");
        when(shopRepository.findByIdAndTenantId(shopId, tenantId)).thenReturn(Optional.of(shop));
        AtomicReference<Optional<UUID>> tenantAtSellerRead = new AtomicReference<>();
        when(traderIdentityService.findPublicSeller(tenantId, shop)).thenAnswer(inv -> {
            tenantAtSellerRead.set(TenantContext.get());
            return SELLER;
        });

        listener.handleOrderStateChange(event);

        assertThat(tenantAtSellerRead.get()).as("the seller was read under the event's tenant").contains(tenantId);
        ArgumentCaptor<CustomerEmailContext> ctx = ArgumentCaptor.forClass(CustomerEmailContext.class);
        verify(emailService).sendOrderConfirmation(eq(event), eq("buyer@example.com"), ctx.capture());
        verifyNoMoreInteractions(emailService);
        CustomerEmailContext c = ctx.getValue();
        assertThat(c.shopName()).isEqualTo("Mama Adé's Kitchen");
        assertThat(c.shopEmail()).isEqualTo("kitchen@x.example.com");
        assertThat(c.seller()).isSameAs(SELLER);
        assertThat(c.placedVia()).isEqualTo(OrderChannel.STOREFRONT);
        assertThat(c.acknowledgedAllergens()).containsExactly("Gluten", "Milk");
        assertThat(c.recordedAllergens()).containsExactly("Gluten", "Milk");
        assertThat(c.flags()).singleElement()
                .satisfies(f -> assertThat(f.productName() + "/" + f.allergenName()).isEqualTo("Puff Puff/Eggs"));
        assertThat(c.fulfilmentType()).isEqualTo(FulfilmentType.COLLECTION);
        assertThat(TenantContext.get()).as("the listener clears the tenant afterwards").isEmpty();
    }

    @Test
    @DisplayName("D-07: a vendor-placed order reaches the email with placedVia VENDOR and NO acknowledgement")
    void vendorPlacedOrderCarriesNoAcknowledgement() {
        UUID orderId = UUID.randomUUID();
        OrderStateChangeEvent event = new OrderStateChangeEvent(orderId, UUID.randomUUID(), "ORD-CTX-2",
                OrderStatus.DRAFT, OrderStatus.PENDING, OffsetDateTime.now());
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(
                orderWithLines(null, OrderChannel.VENDOR, null, "buyer@example.com")));

        listener.handleOrderStateChange(event);

        ArgumentCaptor<CustomerEmailContext> ctx = ArgumentCaptor.forClass(CustomerEmailContext.class);
        verify(emailService).sendOrderConfirmation(eq(event), anyString(), ctx.capture());
        assertThat(ctx.getValue().placedVia()).isEqualTo(OrderChannel.VENDOR);
        assertThat(ctx.getValue().acknowledgedAllergens()).as("null = not recorded, never 'none'").isNull();
        assertThat(ctx.getValue().recordedAllergens()).containsExactly("Gluten", "Milk");
        verifyNoInteractions(traderIdentityService);
    }

    @Test
    @DisplayName("a failed seller read still sends the email, with no seller (the 31.1-24 missing state)")
    void sellerReadFailureStillSends() {
        UUID orderId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        OrderStateChangeEvent event = new OrderStateChangeEvent(orderId, tenantId, "ORD-CTX-3",
                OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now());
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(
                orderWithLines(shopId, OrderChannel.STOREFRONT, 65, "buyer@example.com")));
        Shop shop = new Shop();
        shop.setId(shopId);
        shop.setTenantId(tenantId);
        shop.setName("Shop");
        when(shopRepository.findByIdAndTenantId(shopId, tenantId)).thenReturn(Optional.of(shop));
        when(traderIdentityService.findPublicSeller(tenantId, shop)).thenThrow(new IllegalStateException("db"));

        listener.handleOrderStateChange(event);

        ArgumentCaptor<CustomerEmailContext> ctx = ArgumentCaptor.forClass(CustomerEmailContext.class);
        verify(emailService).sendOrderConfirmed(eq(event), anyString(), ctx.capture());
        assertThat(ctx.getValue().seller()).isNull();
        assertThat(ctx.getValue().shopName()).isEqualTo("Shop");
    }

    @Test
    @DisplayName("goods P2-GRA-23: every status still sends exactly ONE email, through exactly one method")
    void everyTransitionSendsExactlyOneEmail() {
        record T(OrderStatus from, OrderStatus to) { }
        for (T t : List.of(new T(OrderStatus.DRAFT, OrderStatus.PENDING),
                new T(OrderStatus.PENDING, OrderStatus.CONFIRMED),
                new T(OrderStatus.CONFIRMED, OrderStatus.PREPARING),
                new T(OrderStatus.PREPARING, OrderStatus.READY),
                new T(OrderStatus.READY, OrderStatus.COMPLETED),
                new T(OrderStatus.PENDING, OrderStatus.CANCELLED))) {
            reset(emailService);
            UUID orderId = UUID.randomUUID();
            OrderStateChangeEvent event = new OrderStateChangeEvent(orderId, UUID.randomUUID(), "ORD-ONE",
                    t.from(), t.to(), OffsetDateTime.now());
            Order order = new Order();
            order.setCustomerEmail("one@example.com");
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

            listener.handleOrderStateChange(event);

            switch (t.to()) {
                case PENDING -> verify(emailService).sendOrderConfirmation(eq(event), eq("one@example.com"), any());
                case CONFIRMED -> verify(emailService).sendOrderConfirmed(eq(event), eq("one@example.com"), any());
                case PREPARING -> verify(emailService).sendOrderPreparing(eq(event), eq("one@example.com"), any());
                case READY -> verify(emailService).sendOrderReady(eq(event), eq("one@example.com"), any());
                case COMPLETED -> verify(emailService).sendOrderCompletedNotification(eq(event), eq("one@example.com"), any());
                case CANCELLED -> verify(emailService).sendOrderCancelledNotification(eq(event), eq("one@example.com"), any());
                default -> throw new AssertionError(t);
            }
            verifyNoMoreInteractions(emailService);
        }
    }

    @Test
    @DisplayName("T-31.1-88: the listener never logs the customer's address")
    void listenerNeverLogsTheRecipient() {
        Level previous = listenerLogger.getLevel();
        listenerLogger.setLevel(Level.DEBUG);
        try {
            UUID orderId = UUID.randomUUID();
            OrderStateChangeEvent event = new OrderStateChangeEvent(orderId, UUID.randomUUID(), "ORD-LOG-1",
                    OrderStatus.PENDING, OrderStatus.CONFIRMED, OffsetDateTime.now());
            Order order = new Order();
            order.setCustomerEmail("private.person@example.com");
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

            listener.handleOrderStateChange(event);

            assertThat(logAppender.list)
                    .as("non-vacuity: the send was logged with the order number")
                    .anyMatch(e -> e.getFormattedMessage().contains("Sending CONFIRMED notification for order ORD-LOG-1"));
            assertThat(logAppender.list).noneMatch(e -> e.getFormattedMessage().contains("private.person"));
        } finally {
            listenerLogger.setLevel(previous);
        }
    }
}
