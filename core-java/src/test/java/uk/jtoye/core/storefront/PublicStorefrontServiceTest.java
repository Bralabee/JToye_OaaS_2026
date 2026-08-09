package uk.jtoye.core.storefront;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import uk.jtoye.core.exception.MisconfiguredPlatformRadiusException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.TenantAccessDeniedException;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.geo.PostcodeGeocoder;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.payment.PaymentIntentResult;
import uk.jtoye.core.payment.PaymentService;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.shop.DiscountType;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopAnnouncement;
import uk.jtoye.core.shop.ShopAnnouncementRepository;
import uk.jtoye.core.shop.ShopPromotion;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.shop.ShopWithDistance;
import uk.jtoye.core.storefront.dto.*;

import java.lang.reflect.Field;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublicStorefrontServiceTest {

    @Mock private ShopRepository shopRepository;
    @Mock private ProductRepository productRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderEventPublisher eventPublisher;
    @Mock private EntityManager entityManager;
    @Mock private Session hibernateSession;
    @Mock private PaymentService paymentService;
    @Mock private uk.jtoye.core.shop.ShopPromotionRepository promotionRepository;
    @Mock private ShopAnnouncementRepository announcementRepository;
    @Mock private PostcodeGeocoder postcodeGeocoder;

    private PublicStorefrontService service;

    private Shop publishedShop;
    private UUID tenantId;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(entityManager.unwrap(Session.class)).thenReturn(hibernateSession);
        java.sql.Connection mockConn = mock(java.sql.Connection.class);
        java.sql.PreparedStatement mockStmt = mock(java.sql.PreparedStatement.class);
        lenient().when(mockConn.prepareStatement(any(String.class))).thenReturn(mockStmt);
        lenient().doAnswer(inv -> {
            inv.<org.hibernate.jdbc.Work>getArgument(0).execute(mockConn);
            return null;
        }).when(hibernateSession).doWork(any());

        // 33-06: the last two arguments are jtoye.geo.default-radius-km / max-radius-km, injected
        // by @Value in production. The values here mirror application.yml's declared defaults so
        // this unit test exercises the same ceiling behaviour the running service has.
        // 33-08: postcodeGeocoder drives the THIRD search tier and is reached only when both text
        // tiers return empty, so most arms in this file never touch it.
        service = new PublicStorefrontService(shopRepository, productRepository, orderRepository, eventPublisher, entityManager, paymentService, promotionRepository, announcementRepository, postcodeGeocoder, 5.0, 50.0);

        tenantId = UUID.randomUUID();
        publishedShop = new Shop();
        setField(publishedShop, "id", UUID.randomUUID());
        publishedShop.setTenantId(tenantId);
        publishedShop.setName("Test Shop");
        publishedShop.setSlug("test-shop-abc12345");
        publishedShop.setPublished(true);
        publishedShop.setAddress("123 Test St");
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        // Clear ThreadLocal between tests — the new resolvePublicShopForSlug_*
        // tests pre-populate TenantContext, and the helper is forbidden from
        // clearing it (Plan D-09). Cleanup is the harness's responsibility.
        TenantContext.clear();
    }

    /**
     * Stubs the persist-then-pay card path (issue #538).
     *
     * <p>{@code saveAndFlush} assigns an id the way Hibernate's
     * {@code GenerationType.UUID} does, and the {@code createPaymentIntent} stub
     * <em>asserts</em> the order it receives actually has one. That assertion is
     * the point: it means a regression back to "create the intent first, save
     * afterwards" fails here, at unit speed, and not only in the Testcontainers
     * suite. Callers still stub {@code isConfigured()} themselves so each test
     * states which branch it is exercising.
     */
    private void stubCardCheckoutPersistThenPay() throws Exception {
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            setField(order, "id", UUID.randomUUID());
            return order;
        });
        when(paymentService.createPaymentIntent(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            assertNotNull(order.getId(),
                    "#538: the order must be persisted (id assigned) BEFORE its PaymentIntent is created");
            return new PaymentIntentResult("pi_test_1", "cs_test_secret");
        });
    }

    @Test
    @DisplayName("listPublishedShops returns published shops")
    void listPublishedShops() {
        Page<Shop> page = new PageImpl<>(List.of(publishedShop));
        when(shopRepository.findByPublishedTrue(any())).thenReturn(page);

        var result = service.listPublishedShops(PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        assertEquals("Test Shop", result.getContent().get(0).getName());
        assertEquals("test-shop-abc12345", result.getContent().get(0).getSlug());
    }

    @Test
    @DisplayName("getShopBySlug returns shop when published")
    void getShopBySlug_found() {
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));

        var result = service.getShopBySlug("test-shop-abc12345");

        assertEquals("Test Shop", result.getName());
        assertNull(result.getLogoUrl());
    }

    @Test
    @DisplayName("getShopBySlug throws when not found")
    void getShopBySlug_notFound() {
        when(shopRepository.findBySlugAndPublishedTrue("nonexistent"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.getShopBySlug("nonexistent"));
    }

    // QA-council FIX-6 (M3, run disc-20260712-010550): the public shop payload
    // must disclose the payment mode so checkout can render "How you'll pay"
    // BEFORE the customer commits a binding order. The field mirrors the exact
    // gate createGuestOrder uses (paymentService.isConfigured()).

    @Test
    @DisplayName("getShopBySlug exposes acceptsCardPayments=false when Stripe is unconfigured (COD mode)")
    void getShopBySlug_acceptsCardPaymentsFalse_whenStripeUnconfigured() {
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));
        when(paymentService.isConfigured()).thenReturn(false);

        var result = service.getShopBySlug("test-shop-abc12345");

        assertEquals(false, result.isAcceptsCardPayments());
    }

    @Test
    @DisplayName("getShopBySlug exposes acceptsCardPayments=true when Stripe is configured")
    void getShopBySlug_acceptsCardPaymentsTrue_whenStripeConfigured() {
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));
        when(paymentService.isConfigured()).thenReturn(true);

        var result = service.getShopBySlug("test-shop-abc12345");

        assertEquals(true, result.isAcceptsCardPayments());
    }

    @Test
    @DisplayName("getShopProducts groups by category and filters available")
    void getShopProducts() {
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));

        Product p1 = new Product();
        setField(p1, "id", UUID.randomUUID());
        p1.setTitle("Jollof Rice");
        p1.setCategory("Mains");
        p1.setIngredientsText("Rice, tomatoes");
        p1.setAllergenMask(0);
        p1.setPricePennies(899L);
        p1.setAvailable(true);

        Product p2 = new Product();
        setField(p2, "id", UUID.randomUUID());
        p2.setTitle("Chapman");
        p2.setCategory("Drinks");
        p2.setIngredientsText("Fanta, Sprite");
        p2.setAllergenMask(0);
        p2.setPricePennies(450L);
        p2.setAvailable(true);

        when(productRepository.findAvailableByShopOrderedByCategory(publishedShop.getId())).thenReturn(List.of(p1, p2));

        var result = service.getShopProducts("test-shop-abc12345");

        assertEquals(2, result.size());
        assertTrue(result.containsKey("Mains"));
        assertTrue(result.containsKey("Drinks"));
        assertEquals("Jollof Rice", result.get("Mains").get(0).getTitle());
        assertEquals(899L, result.get("Mains").get(0).getPricePennies());
    }

    @Test
    @DisplayName("trackOrder returns status when order number and email match")
    void trackOrder_success() {
        Order order = new Order();
        setField(order, "id", UUID.randomUUID());
        order.setOrderNumber("ORD-TEST-20260402-ABC");
        order.setCustomerEmail("test@example.com");
        order.setStatus(OrderStatus.PENDING);
        order.setTotalAmountPennies(1500L);
        order.setShopId(publishedShop.getId());
        order.setUpdatedAt(OffsetDateTime.now());

        when(orderRepository.findByOrderNumberAndCustomerEmail("ORD-TEST-20260402-ABC", "test@example.com"))
                .thenReturn(Optional.of(order));
        when(shopRepository.findById(publishedShop.getId()))
                .thenReturn(Optional.of(publishedShop));

        var result = service.trackOrder("ORD-TEST-20260402-ABC", "test@example.com");

        assertEquals("ORD-TEST-20260402-ABC", result.getOrderNumber());
        assertEquals("PENDING", result.getStatus());
        assertEquals("Test Shop", result.getShopName());
        assertEquals(1500L, result.getTotalAmountPennies());
    }

    @Test
    @DisplayName("trackOrder throws when order not found")
    void trackOrder_notFound() {
        when(orderRepository.findByOrderNumberAndCustomerEmail(any(), any()))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.trackOrder("BAD-ORDER", "wrong@email.com"));
    }

    @Test
    @DisplayName("getCustomerOrders returns paginated orders for email")
    void getCustomerOrders() {
        Order order = new Order();
        setField(order, "id", UUID.randomUUID());
        order.setOrderNumber("ORD-HIST-001");
        order.setCustomerEmail("customer@test.com");
        order.setStatus(OrderStatus.COMPLETED);
        order.setTotalAmountPennies(2000L);
        order.setShopId(publishedShop.getId());
        order.setUpdatedAt(OffsetDateTime.now());

        PageRequest pageable = PageRequest.of(0, 20);
        when(orderRepository.findByCustomerEmailOrderByCreatedAtDesc("customer@test.com", pageable))
                .thenReturn(new PageImpl<>(List.of(order), pageable, 1));
        when(shopRepository.findById(publishedShop.getId()))
                .thenReturn(Optional.of(publishedShop));

        Page<PublicOrderStatus> result = service.getCustomerOrders("customer@test.com", pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals("ORD-HIST-001", result.getContent().get(0).getOrderNumber());
        assertEquals("COMPLETED", result.getContent().get(0).getStatus());
    }

    @Test
    @DisplayName("createGuestOrder rejects order when shop is explicitly closed today")
    void createGuestOrder_rejectsWhenClosed() {
        // Set opening hours to "Closed" for today
        String[] dayKeys = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};
        String todayKey = dayKeys[java.time.LocalDate.now().getDayOfWeek().getValue() % 7];
        publishedShop.setOpeningHours(Map.of(todayKey, "Closed"));

        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));

        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Test");
        request.setCustomerEmail("test@example.com");
        request.setCustomerPhone("07700000000");
        request.setItems(List.of());

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.createGuestOrder("test-shop-abc12345", request));
        assertTrue(ex.getMessage().contains("closed"));
    }

    @Test
    @DisplayName("createGuestOrder rejects order outside opening hours")
    void createGuestOrder_rejectsOutsideHours() {
        // Set opening hours to a window that's definitely not now (00:01 - 00:02)
        String[] dayKeys = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};
        String todayKey = dayKeys[java.time.LocalDate.now().getDayOfWeek().getValue() % 7];
        publishedShop.setOpeningHours(Map.of(todayKey, "00:01 - 00:02"));

        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));

        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Test");
        request.setCustomerEmail("test@example.com");
        request.setCustomerPhone("07700000000");
        request.setItems(List.of());

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.createGuestOrder("test-shop-abc12345", request));
        assertTrue(ex.getMessage().contains("closed"));
    }

    private static final java.time.ZoneId UK_ZONE = java.time.ZoneId.of("Europe/London");
    private static final java.time.format.DateTimeFormatter HHMM =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    /** The opening-hours key for "today" in the UK zone the service evaluates. */
    private static String todayKeyUk() {
        String[] dayKeys = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};
        return dayKeys[LocalDate.now(UK_ZONE).getDayOfWeek().getValue() % 7];
    }

    @Test
    @DisplayName("createGuestOrder accepts an order inside a service window whose close time precedes its open time (WR-06 overnight)")
    void createGuestOrder_acceptsInsideOvernightWindow() throws Exception {
        // Window opens 1h ago and "closes" 2h ago: whenever close < open this is
        // an overnight window that CONTAINS now; when the subtraction wraps
        // midnight it degenerates to a plain window that also contains now. The
        // pre-fix predicate rejected the overnight shape at every time of day.
        java.time.LocalTime nowUk = java.time.LocalTime.now(UK_ZONE);
        String window = nowUk.minusHours(1).format(HHMM) + " - " + nowUk.minusHours(2).format(HHMM);
        publishedShop.setOpeningHours(Map.of(todayKeyUk(), window));
        publishedShop.setDeliveryFeePennies(0L);

        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));
        Product product = availableProduct("Midnight Suya", 1200L);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(paymentService.isConfigured()).thenReturn(true);
        stubCardCheckoutPersistThenPay();
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> service.createGuestOrder("test-shop-abc12345", deliveryRequest(product)),
                "an order inside the overnight trading window must be accepted");
    }

    @Test
    @DisplayName("createGuestOrder still rejects an order outside the window when close precedes open (WR-06 overnight)")
    void createGuestOrder_rejectsOutsideOvernightWindow() {
        // Window opens in 1h and closed 1h ago: now sits OUTSIDE it under both
        // the overnight and the plain (midnight-wrapped) interpretation.
        java.time.LocalTime nowUk = java.time.LocalTime.now(UK_ZONE);
        String window = nowUk.plusHours(1).format(HHMM) + " - " + nowUk.minusHours(1).format(HHMM);
        publishedShop.setOpeningHours(Map.of(todayKeyUk(), window));

        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));

        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Night Owl");
        request.setCustomerEmail("owl@example.com");
        request.setCustomerPhone("07700900005");
        request.setItems(List.of());

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.createGuestOrder("test-shop-abc12345", request));
        assertTrue(ex.getMessage().contains("closed"));
    }

    // ========================================================================
    // Phase 19 UIX-03 / UIX-04 — guest order name snapshot + fulfilment/address
    // ========================================================================

    /**
     * An available, unlimited-stock product with a STANDARD VAT rate, homed in
     * the ordered shop (CR-01: createGuestOrder rejects products whose shopId
     * does not match the storefront's shop).
     */
    private Product availableProduct(String title, long pricePennies) {
        Product product = new Product();
        setField(product, "id", UUID.randomUUID());
        product.setTitle(title);
        product.setPricePennies(pricePennies);
        product.setAvailable(true);
        product.setVatRate(VatRate.STANDARD);
        product.setAllergenMask(0);
        product.setShopId(publishedShop.getId());
        return product;
    }

    private GuestOrderItemRequest itemFor(Product product, int quantity) {
        GuestOrderItemRequest itemReq = new GuestOrderItemRequest();
        itemReq.setProductId(product.getId());
        itemReq.setQuantity(quantity);
        return itemReq;
    }

    private GuestOrderRequest deliveryRequest(Product product) {
        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Ada Lovelace");
        request.setCustomerEmail("ada@example.com");
        request.setCustomerPhone("07700900000");
        request.setFulfilmentType("DELIVERY");
        request.setAddressLine1("1 High Street");
        request.setAddressCity("London");
        request.setAddressPostcode("E1 6AN");
        request.setItems(List.of(itemFor(product, 2)));
        return request;
    }

    @Test
    @DisplayName("createGuestOrder snapshots the real product title (never 'Unknown Product') and persists fulfilment + address")
    void createGuestOrder_snapshotsProductNameAndPersistsFulfilment() throws Exception {
        publishedShop.setDeliveryFeePennies(0L);
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));
        Product product = availableProduct("Jollof Rice", 899L);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        // Stripe "configured" so the COD TransactionSynchronization branch is skipped.
        when(paymentService.isConfigured()).thenReturn(true);
        stubCardCheckoutPersistThenPay();
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createGuestOrder("test-shop-abc12345", deliveryRequest(product));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        Order saved = captor.getValue();

        assertEquals(1, saved.getItems().size());
        assertEquals("Jollof Rice", saved.getItems().get(0).getProductName(),
                "guest order must snapshot the real product title");
        assertNotEquals("Unknown Product", saved.getItems().get(0).getProductName());
        assertEquals(FulfilmentType.DELIVERY, saved.getFulfilmentType());
        assertEquals("1 High Street", saved.getAddressLine1());
        assertEquals("London", saved.getAddressCity());
        assertEquals("E1 6AN", saved.getAddressPostcode());
    }

    @Test
    @DisplayName("createGuestOrder forces £0 delivery fee for COLLECTION even when the shop charges a fee")
    void createGuestOrder_collectionForcesZeroFee() throws Exception {
        publishedShop.setDeliveryFeePennies(500L); // shop DOES charge for delivery
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));
        Product product = availableProduct("Chapman", 450L);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(paymentService.isConfigured()).thenReturn(true);
        stubCardCheckoutPersistThenPay();
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Grace Hopper");
        request.setCustomerEmail("grace@example.com");
        request.setCustomerPhone("07700900001");
        request.setFulfilmentType("COLLECTION");
        request.setItems(List.of(itemFor(product, 1)));

        service.createGuestOrder("test-shop-abc12345", request);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        Order saved = captor.getValue();

        assertEquals(FulfilmentType.COLLECTION, saved.getFulfilmentType());
        assertEquals(0L, saved.getDeliveryFeePennies(),
                "COLLECTION must force the delivery fee to £0, ignoring the shop fee");
        assertNull(saved.getAddressLine1(), "COLLECTION order must not persist an address");
    }

    @Test
    @DisplayName("createGuestOrder charges the shop's server-side delivery fee for DELIVERY (client value never trusted)")
    void createGuestOrder_deliveryUsesServerFee() throws Exception {
        publishedShop.setDeliveryFeePennies(500L);
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));
        Product product = availableProduct("Jollof Rice", 899L);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(paymentService.isConfigured()).thenReturn(true);
        stubCardCheckoutPersistThenPay();
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createGuestOrder("test-shop-abc12345", deliveryRequest(product));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertEquals(500L, captor.getValue().getDeliveryFeePennies(),
                "DELIVERY fee must come from the shop, computed server-side");
    }

    /** A persisted order as the idempotency lookup would return it (WR-02). */
    private Order existingOrder(OrderStatus status, String paymentReference) {
        Order order = new Order();
        setField(order, "id", UUID.randomUUID());
        order.setOrderNumber("ORD-EXISTING-0001");
        order.setStatus(status);
        order.setSubtotalPennies(1798L);
        order.setDeliveryFeePennies(0L);
        order.setVatRate(VatRate.STANDARD);
        order.setVatAmountPennies(300L);
        order.setTotalAmountPennies(1798L);
        order.setItemCount(2);
        order.setPaymentReference(paymentReference);
        return order;
    }

    private GuestOrderRequest idempotentRetryRequest() {
        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Retry Customer");
        request.setCustomerEmail("retry@example.com");
        request.setCustomerPhone("07700900004");
        request.setFulfilmentType("COLLECTION");
        request.setIdempotencyKey("retry-key-123");
        request.setItems(List.of());
        return request;
    }

    @Test
    @DisplayName("createGuestOrder idempotent retry of a payable DRAFT order re-fetches the REAL client secret, never the PaymentIntent id (WR-02)")
    void createGuestOrder_idempotentRetryDraft_refetchesRealClientSecret() throws Exception {
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));
        when(orderRepository.findByTenantIdAndIdempotencyKey(tenantId, "retry-key-123"))
                .thenReturn(Optional.of(existingOrder(OrderStatus.DRAFT, "pi_123")));
        when(paymentService.isConfigured()).thenReturn(true);
        when(paymentService.retrieveClientSecret("pi_123")).thenReturn("pi_123_secret_real");

        GuestOrderConfirmation confirmation =
                service.createGuestOrder("test-shop-abc12345", idempotentRetryRequest());

        assertEquals("pi_123_secret_real", confirmation.getClientSecret(),
                "retry must resume payment with the real client secret");
        assertNotEquals("pi_123", confirmation.getClientSecret(),
                "the raw PaymentIntent id must never occupy the clientSecret slot");
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("createGuestOrder idempotent retry of an already-paid order returns a null clientSecret (WR-02)")
    void createGuestOrder_idempotentRetryPaid_returnsNullClientSecret() {
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));
        when(orderRepository.findByTenantIdAndIdempotencyKey(tenantId, "retry-key-123"))
                .thenReturn(Optional.of(existingOrder(OrderStatus.PENDING, "pi_456")));

        GuestOrderConfirmation confirmation =
                service.createGuestOrder("test-shop-abc12345", idempotentRetryRequest());

        assertNull(confirmation.getClientSecret(),
                "a non-DRAFT duplicate must not disclose any payment reference");
        assertEquals("PENDING", confirmation.getStatus());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("createGuestOrder rejects an order below the shop's minimum order value (WR-01)")
    void createGuestOrder_rejectsBelowMinimumOrder() {
        publishedShop.setMinimumOrderPennies(1000L);
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));
        Product product = availableProduct("Zobo", 300L);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Min Checker");
        request.setCustomerEmail("min@example.com");
        request.setCustomerPhone("07700900003");
        request.setFulfilmentType("COLLECTION");
        request.setItems(List.of(itemFor(product, 1))); // 300 < 1000 minimum

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.createGuestOrder("test-shop-abc12345", request));
        assertTrue(ex.getMessage().contains("minimum order value of £10.00"));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("createGuestOrder rejects a product from another shop of the same tenant (CR-01 / UIX-05) without leaking its existence")
    void createGuestOrder_rejectsProductFromAnotherShop() {
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));

        // Same tenant, DIFFERENT shop (e.g. the unpublished archive shop) —
        // RLS alone would let this row through, the service check must not.
        Product foreignProduct = availableProduct("Label Cake 057999", 250L);
        foreignProduct.setShopId(UUID.randomUUID());
        when(productRepository.findById(foreignProduct.getId()))
                .thenReturn(Optional.of(foreignProduct));

        var ex = assertThrows(ResourceNotFoundException.class,
                () -> service.createGuestOrder("test-shop-abc12345", deliveryRequest(foreignProduct)));

        // Response must be indistinguishable from a nonexistent product: no
        // title, no shop detail — only the id the caller already supplied.
        assertFalse(ex.getMessage().contains("Label Cake"),
                "rejection must not leak the foreign product's title");
        assertTrue(ex.getMessage().contains(foreignProduct.getId().toString()));

        // No order row may be minted for the rejected request.
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("createGuestOrder rejects a DELIVERY order with no address (conditional-required)")
    void createGuestOrder_deliveryRequiresAddress() {
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));

        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("No Address");
        request.setCustomerEmail("no-address@example.com");
        request.setCustomerPhone("07700900002");
        request.setFulfilmentType("DELIVERY");
        // no address fields set
        request.setItems(List.of());

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.createGuestOrder("test-shop-abc12345", request));
        assertTrue(ex.getMessage().toLowerCase().contains("address"));
    }

    @Test
    @DisplayName("getActivePromotions returns filtered list with discount type info")
    void getActivePromotions_returnsFilteredList() {
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));

        ShopPromotion percentagePromo = new ShopPromotion();
        setField(percentagePromo, "id", UUID.randomUUID());
        percentagePromo.setLabel("10% Off");
        percentagePromo.setDiscountType(DiscountType.PERCENTAGE);
        percentagePromo.setDiscountPercent(10);
        percentagePromo.setCategory("All");
        percentagePromo.setValidUntil(OffsetDateTime.now().plusDays(30));

        ShopPromotion flatPromo = new ShopPromotion();
        setField(flatPromo, "id", UUID.randomUUID());
        flatPromo.setLabel("500p Off");
        flatPromo.setDiscountType(DiscountType.FLAT_AMOUNT);
        flatPromo.setDiscountAmountPennies(500);
        flatPromo.setCategory("Mains");
        flatPromo.setValidUntil(OffsetDateTime.now().plusDays(14));

        when(promotionRepository.findActiveByShopId(publishedShop.getId()))
                .thenReturn(List.of(percentagePromo, flatPromo));

        var result = service.getActivePromotions("test-shop-abc12345");

        assertEquals(2, result.size());
        assertEquals("10% Off", result.get(0).getLabel());
        assertEquals(DiscountType.PERCENTAGE, result.get(0).getDiscountType());
        assertEquals(10, result.get(0).getDiscountPercent());
        assertNull(result.get(0).getDiscountAmountPennies());
        assertEquals("500p Off", result.get(1).getLabel());
        assertEquals(DiscountType.FLAT_AMOUNT, result.get(1).getDiscountType());
        assertEquals(500, result.get(1).getDiscountAmountPennies());
        assertNull(result.get(1).getDiscountPercent());
    }

    @Test
    @DisplayName("getActiveAnnouncements returns filtered list")
    void getActiveAnnouncements_returnsFilteredList() {
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));

        ShopAnnouncement a1 = new ShopAnnouncement();
        setField(a1, "id", UUID.randomUUID());
        a1.setTitle("Holiday Hours");
        a1.setBody("Closed on Boxing Day.");
        a1.setValidUntil(OffsetDateTime.now().plusDays(7));

        ShopAnnouncement a2 = new ShopAnnouncement();
        setField(a2, "id", UUID.randomUUID());
        a2.setTitle("New Menu");
        a2.setBody("Check out our spring menu!");
        a2.setValidUntil(OffsetDateTime.now().plusDays(30));

        when(announcementRepository.findActiveByShopId(publishedShop.getId()))
                .thenReturn(List.of(a1, a2));

        var result = service.getActiveAnnouncements("test-shop-abc12345");

        assertEquals(2, result.size());
        assertEquals("Holiday Hours", result.get(0).getTitle());
        assertEquals("Closed on Boxing Day.", result.get(0).getBody());
        assertEquals("New Menu", result.get(1).getTitle());
    }

    @Test
    @DisplayName("getActivePromotions throws when shop not found")
    void getActivePromotions_shopNotFound_throws() {
        when(shopRepository.findBySlugAndPublishedTrue("nonexistent"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.getActivePromotions("nonexistent"));
    }

    @Test
    @DisplayName("getShopConfig announcements come from repository not shop entity")
    void getShopConfig_announcementsFromRepository() {
        when(shopRepository.findBySlugAndPublishedTrue("test-shop-abc12345"))
                .thenReturn(Optional.of(publishedShop));

        ShopAnnouncement a1 = new ShopAnnouncement();
        setField(a1, "id", UUID.randomUUID());
        a1.setTitle("Sale Today");
        a1.setBody("Everything 20% off!");
        a1.setValidUntil(OffsetDateTime.now().plusDays(1));

        when(announcementRepository.findActiveByShopId(publishedShop.getId()))
                .thenReturn(List.of(a1));
        when(promotionRepository.findActiveByShopId(publishedShop.getId()))
                .thenReturn(List.of());

        var result = service.getShopConfig("test-shop-abc12345");

        assertNotNull(result.getAnnouncements());
        assertEquals(1, result.getAnnouncements().size());
        assertEquals("Sale Today", result.getAnnouncements().get(0).title());
        assertEquals("Everything 20% off!", result.getAnnouncements().get(0).body());
        verify(announcementRepository).findActiveByShopId(publishedShop.getId());
    }

    // ========================================================================
    // SEC-01 (Phase 13) — resolvePublicShopForSlug helper coverage (SC-4)
    //
    // The helper is package-private (see PublicStorefrontService, Task 13-01-03)
    // specifically so these unit tests can invoke it directly without reflection.
    //
    // Tests compile-fail today because the helper does not exist yet — this is
    // the intentional RED state that Task 13-01-03 (GREEN) will turn green.
    // ========================================================================

    @Test
    @DisplayName("resolvePublicShopForSlug — no upstream tenant → sets context from slug, returns shop")
    void resolvePublicShopForSlug_whenNoUpstreamTenant_setsContextFromSlug() {
        when(shopRepository.findBySlugAndPublishedTrue(publishedShop.getSlug()))
                .thenReturn(Optional.of(publishedShop));

        Shop result = service.resolvePublicShopForSlug(publishedShop.getSlug());

        assertEquals(publishedShop, result);
        assertEquals(Optional.of(tenantId), TenantContext.get(),
                "TenantContext must be set to shop.tenantId on happy path");
    }

    @Test
    @DisplayName("resolvePublicShopForSlug — upstream tenant matches slug tenant → proceeds")
    void resolvePublicShopForSlug_whenUpstreamMatches_setsContextFromSlug() {
        when(shopRepository.findBySlugAndPublishedTrue(publishedShop.getSlug()))
                .thenReturn(Optional.of(publishedShop));
        TenantContext.set(tenantId);  // JWT-authenticated caller for the SAME tenant

        Shop result = service.resolvePublicShopForSlug(publishedShop.getSlug());

        assertEquals(publishedShop, result);
        assertEquals(Optional.of(tenantId), TenantContext.get());
    }

    @Test
    @DisplayName("resolvePublicShopForSlug — upstream tenant differs from slug tenant → throws TenantAccessDeniedException")
    void resolvePublicShopForSlug_whenUpstreamMismatches_throwsTenantAccessDeniedException() {
        UUID otherTenant = UUID.randomUUID();
        when(shopRepository.findBySlugAndPublishedTrue(publishedShop.getSlug()))
                .thenReturn(Optional.of(publishedShop));
        TenantContext.set(otherTenant);  // JWT-authenticated caller for a DIFFERENT tenant

        TenantAccessDeniedException ex = assertThrows(TenantAccessDeniedException.class,
                () -> service.resolvePublicShopForSlug(publishedShop.getSlug()));

        // Per D-04 (ASVS V4.1.5) — message is generic; does NOT contain the tenant UUIDs.
        assertFalse(ex.getMessage().contains(otherTenant.toString()),
                "Exception message must not leak upstream tenant UUID");
        assertFalse(ex.getMessage().contains(tenantId.toString()),
                "Exception message must not leak slug tenant UUID");

        // Per D-09 — helper must NOT clear; caller's pre-existing TenantContext is retained.
        assertEquals(Optional.of(otherTenant), TenantContext.get(),
                "helper must not clear or overwrite TenantContext on failure");
    }

    @Test
    @DisplayName("resolvePublicShopForSlug — slug unknown → throws ResourceNotFoundException (preserves existing behavior)")
    void resolvePublicShopForSlug_whenSlugUnknown_throwsResourceNotFoundException() {
        when(shopRepository.findBySlugAndPublishedTrue("missing-slug"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.resolvePublicShopForSlug("missing-slug"));
        assertTrue(TenantContext.get().isEmpty(),
                "TenantContext must remain empty when slug is unknown");
    }

    // =====================================================================================
    // 33-08 / #619 — the third search tier and the interpretation it discloses
    // =====================================================================================

    private ShopWithDistance projection(UUID id, String slug, double distanceKm) {
        return new ShopWithDistance() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public String getSlug() {
                return slug;
            }

            @Override
            public Double getDistanceKm() {
                return distanceKm;
            }
        };
    }

    @Nested
    @DisplayName("searchPublishedShops — interpretation runs FIRST (D-A, flipped at the 33-09 gate)")
    class PostcodeSearchTier {

        @Test
        @DisplayName("CA-A: an ordinary food search is offered to the geocoder, declined for free, "
                + "and answered by the text path exactly as before")
        void textHitIsUnchangedByTheFlip() {
            when(shopRepository.fullTextSearchPublished(eq("jollof"), any()))
                    .thenReturn(new PageImpl<>(List.of(publishedShop)));

            PublicStorefrontService.SearchOutcome outcome =
                    service.searchPublishedShops("jollof", PageRequest.of(0, 20));

            assertEquals(SearchInterpretation.Kind.TEXT, outcome.interpretation().kind());
            assertEquals(1, outcome.page().getTotalElements());

            // THE MECHANISM ASSERTION HAD TO CHANGE WITH THE ORDERING, and it is deliberately
            // replaced by a STRONGER one rather than deleted. Under the old third-tier ordering
            // this read `verifyNoInteractions(postcodeGeocoder)` — "the geocoder never ran" — and
            // that is now false by design: it runs first, for every term. What actually matters
            // is unchanged and is asserted directly: the DISTANCE QUERY is never issued for a
            // term that is not a postcode. A mere "interpretation is TEXT" assertion would still
            // pass if the proximity branch had run and lost.
            verify(postcodeGeocoder).locateSearchTerm("jollof");
            verify(shopRepository, never()).findPublishedNear(
                    anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(), anyDouble(), any());
        }

        @Test
        @DisplayName("D-A FLIP: a resolvable postcode is answered as a PLACE even though the text "
                + "search would have matched the same string")
        void aResolvablePostcodeBeatsItsOwnTextMatch() {
            UUID shopId = publishedShop.getId();
            // BOTH text tiers are stubbed to MATCH. That is what makes this arm a statement about
            // ORDERING rather than about an empty fixture: under the shipped-33-08 ordering this
            // returned TEXT and that shop, which is precisely the behaviour the owner reversed.
            lenient().when(shopRepository.fullTextSearchPublished(eq("SE155BS"), any()))
                    .thenReturn(new PageImpl<>(List.of(publishedShop)));
            lenient().when(shopRepository.searchPublished(eq("SE155BS"), any()))
                    .thenReturn(new PageImpl<>(List.of(publishedShop)));
            when(postcodeGeocoder.locateSearchTerm("SE155BS")).thenReturn(Optional.of(
                    new PostcodeGeocoder.LocatedPostcode(
                            new PostcodeGeocoder.Coordinate(51.472435, -0.070047),
                            "SE155BS", PostcodeGeocoder.Precision.UNIT)));
            when(shopRepository.findPublishedNear(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(), anyDouble(), any()))
                    .thenReturn(new PageImpl<>(List.of(projection(shopId, "test-shop-abc12345", 0.2))));
            when(shopRepository.findAllById(List.of(shopId))).thenReturn(List.of(publishedShop));

            PublicStorefrontService.SearchOutcome outcome =
                    service.searchPublishedShops("SE155BS", PageRequest.of(0, 20));

            assertEquals(SearchInterpretation.Kind.PROXIMITY, outcome.interpretation().kind());
            assertEquals("SE155BS", outcome.interpretation().postcode());
            assertEquals(PostcodeGeocoder.Precision.UNIT, outcome.interpretation().precision());

            // DECISIVE: neither text query was issued at all, so the answer cannot have come from
            // one that happened to lose. Both were stubbed to win.
            verify(shopRepository, never()).fullTextSearchPublished(eq("SE155BS"), any());
            verify(shopRepository, never()).searchPublished(eq("SE155BS"), any());
        }

        @Test
        @DisplayName("a resolvable postcode → PROXIMITY at the platform radius")
        void resolvablePostcodeUsesThePlatformRadius() {
            UUID shopId = publishedShop.getId();
            when(postcodeGeocoder.locateSearchTerm("SE22")).thenReturn(Optional.of(
                    new PostcodeGeocoder.LocatedPostcode(
                            new PostcodeGeocoder.Coordinate(51.454445, -0.072403),
                            "SE22", PostcodeGeocoder.Precision.DISTRICT)));
            when(shopRepository.findPublishedNear(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(), anyDouble(), any()))
                    .thenReturn(new PageImpl<>(List.of(projection(shopId, "test-shop-abc12345", 1.5))));
            when(shopRepository.findAllById(List.of(shopId))).thenReturn(List.of(publishedShop));

            PublicStorefrontService.SearchOutcome outcome =
                    service.searchPublishedShops("SE22", PageRequest.of(0, 20));

            assertEquals(SearchInterpretation.Kind.PROXIMITY, outcome.interpretation().kind());
            assertEquals("SE22", outcome.interpretation().postcode());
            assertEquals(PostcodeGeocoder.Precision.DISTRICT, outcome.interpretation().precision());
            assertEquals(5.0, outcome.interpretation().radiusKm());
            assertEquals(1, outcome.page().getContent().size());
            assertEquals(1.5, outcome.page().getContent().get(0).getDistanceKm());

            // D-C: the radius is jtoye.geo.default-radius-km, not a new key and not a literal.
            ArgumentCaptor<Double> radius = ArgumentCaptor.forClass(Double.class);
            verify(shopRepository).findPublishedNear(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(), radius.capture(), any());
            assertEquals(5.0, radius.getValue());
        }

        @Test
        @DisplayName("an EMPTY proximity page keeps its PROXIMITY interpretation, never downgraded to text")
        void emptyProximityPageKeepsItsInterpretation() {
            when(postcodeGeocoder.locateSearchTerm("SE22")).thenReturn(Optional.of(
                    new PostcodeGeocoder.LocatedPostcode(
                            new PostcodeGeocoder.Coordinate(51.454445, -0.072403),
                            "SE22", PostcodeGeocoder.Precision.DISTRICT)));
            when(shopRepository.findPublishedNear(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(), anyDouble(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            PublicStorefrontService.SearchOutcome outcome =
                    service.searchPublishedShops("SE22", PageRequest.of(0, 20));

            // "No kitchens within 3.1 miles of SE22" and "no kitchens match 'SE22'" are different
            // sentences. Downgrading here would tell the customer their postcode was not
            // understood, which is the one case where the disclosure matters most (D-B option D).
            assertTrue(outcome.page().isEmpty());
            assertEquals(SearchInterpretation.Kind.PROXIMITY, outcome.interpretation().kind());
            assertEquals("SE22", outcome.interpretation().postcode());
        }

        @Test
        @DisplayName("a postcode-shaped term outside Code-Point Open falls back to TEXT, never a "
                + "proximity claim on a branch that did not apply")
        void unresolvablePostcodeStaysText() {
            Page<Shop> empty = new PageImpl<>(List.of());
            when(shopRepository.fullTextSearchPublished(eq("BT1 5GS"), any())).thenReturn(empty);
            when(shopRepository.searchPublished(eq("BT1 5GS"), any())).thenReturn(empty);
            when(postcodeGeocoder.locateSearchTerm("BT1 5GS")).thenReturn(Optional.empty());

            PublicStorefrontService.SearchOutcome outcome =
                    service.searchPublishedShops("BT1 5GS", PageRequest.of(0, 20));

            assertEquals(SearchInterpretation.Kind.TEXT, outcome.interpretation().kind());
            assertTrue(outcome.page().isEmpty());
            verify(shopRepository, never()).findPublishedNear(
                    anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(), anyDouble(), any());
        }
    }

    /**
     * WR-03 — a misconfigured platform radius must be a LOUD failure, never a page that states the
     * opposite of what the server did.
     *
     * <p>The defect these arms close, restated so they cannot be weakened without noticing: the
     * {@code ?lat=&lon=} path validated its radius even when it was the platform default, and the
     * postcode tier did not. With {@code GEO_DEFAULT_RADIUS_KM=0} the two disagreed about the same
     * value — {@code ?lat=51.47&lon=-0.07} was a typed 400, while {@code ?q=SE22} was
     * <strong>HTTP 200 with an empty page</strong> carrying
     * {@code proximity; postcode=SE22; precision=district; radiusKm=0.0}. The storefront's parser
     * correctly rejects {@code radiusKm <= 0} and degrades to {@code text}, so the customer was
     * shown {@code No kitchens match "SE22"} over results that HAD been proximity-filtered and
     * from which text-matching kitchens had been excluded. One environment variable, no error, no
     * log, and the exact "row lying about itself" class 33-09 exists to close.
     */
    @Nested
    @DisplayName("WR-03: the platform radius is validated, so a bad one cannot produce a page that lies")
    class PlatformRadiusValidation {

        /** A service built with an arbitrary geo configuration; everything else is the shared mocks. */
        private PublicStorefrontService serviceWithRadii(double defaultRadiusKm, double maxRadiusKm) {
            return new PublicStorefrontService(shopRepository, productRepository, orderRepository,
                    eventPublisher, entityManager, paymentService, promotionRepository,
                    announcementRepository, postcodeGeocoder, defaultRadiusKm, maxRadiusKm);
        }

        @Test
        @DisplayName("CONTROL: the SHIPPED configuration (5 km / 50 km) constructs — the guard can pass")
        void theShippedConfigurationConstructsCleanly() {
            // Non-vacuity. Every arm below asserts a throw; without this one they would all still
            // pass if the guard rejected everything, including the values application.yml ships.
            assertNotNull(serviceWithRadii(5.0, 50.0));
        }

        @Test
        @DisplayName("FAIL DIRECTION: GEO_DEFAULT_RADIUS_KM=0 refuses to start — never a 200 carrying radiusKm=0.0")
        void aZeroPlatformRadiusRefusesToStart() {
            // THE row from the finding's table. Before the fix this value constructed happily and
            // was discovered by a customer; now it is a BeanCreationException at boot, which is
            // strictly louder than the 500 the query-input layer would raise.
            MisconfiguredPlatformRadiusException ex = assertThrows(
                    MisconfiguredPlatformRadiusException.class, () -> serviceWithRadii(0.0, 50.0));

            assertTrue(ex.getMessage().contains("jtoye.geo.default-radius-km"),
                    "the message must name the config key an operator has to change: " + ex.getMessage());
        }

        @Test
        @DisplayName("FAIL DIRECTION: a negative platform radius refuses to start")
        void aNegativePlatformRadiusRefusesToStart() {
            // Previously reached GeoBounds.boxAround, which threw IllegalArgumentException — a 400
            // blaming the caller for an operator's environment variable.
            assertThrows(MisconfiguredPlatformRadiusException.class, () -> serviceWithRadii(-1.0, 50.0));
        }

        @Test
        @DisplayName("FAIL DIRECTION: a non-finite platform radius refuses to start")
        void aNonFinitePlatformRadiusRefusesToStart() {
            // NaN passes every range comparison (IEEE-754 comparisons with NaN are all false), so
            // a bare `<= 0 || > max` pair would let it through to the query.
            assertThrows(MisconfiguredPlatformRadiusException.class,
                    () -> serviceWithRadii(Double.NaN, 50.0));
            assertThrows(MisconfiguredPlatformRadiusException.class,
                    () -> serviceWithRadii(Double.POSITIVE_INFINITY, 50.0));
        }

        @Test
        @DisplayName("FAIL DIRECTION: a default above the ceiling refuses to start — the 500 km row")
        void aPlatformRadiusAboveTheCeilingRefusesToStart() {
            // The finding's third row: ?lat=&lon= refused 500 km with a typed 400 naming the
            // ceiling, while ?q=SE22 quietly returned 500 km of results.
            MisconfiguredPlatformRadiusException ex = assertThrows(
                    MisconfiguredPlatformRadiusException.class, () -> serviceWithRadii(500.0, 50.0));

            assertTrue(ex.getMessage().contains("jtoye.geo.max-radius-km"),
                    "the message must name the ceiling it exceeded: " + ex.getMessage());
        }

        @Test
        @DisplayName("FAIL DIRECTION: a non-finite CEILING refuses to start, or the ceiling stops existing")
        void aNonFiniteCeilingRefusesToStart() {
            // GEO_MAX_RADIUS_KM is as operator-settable as the default. With a NaN ceiling every
            // `radiusKm > maxRadiusKm` is false, so the ceiling silently stops applying rather
            // than failing — the worst shape of all, because nothing reports it.
            assertThrows(MisconfiguredPlatformRadiusException.class,
                    () -> serviceWithRadii(5.0, Double.NaN));
            assertThrows(MisconfiguredPlatformRadiusException.class,
                    () -> serviceWithRadii(5.0, 0.0));
        }

        @Test
        @DisplayName("a misconfigured radius is NOT an IllegalArgumentException — it must not render as a 400")
        void theFailureIsNotRenderedAsTheCallersFault() {
            // The type is the contract. GlobalExceptionHandler maps IllegalArgumentException to a
            // 400 carrying ex.getMessage(), which would blame an anonymous customer for an
            // operator's env var AND echo internal config-key names back to them. This type maps
            // to 500 with a generic detail instead — the precedent MissingTenantContextException
            // set for exactly this mistake.
            Throwable ex = assertThrows(MisconfiguredPlatformRadiusException.class,
                    () -> serviceWithRadii(0.0, 50.0));

            assertFalse(ex instanceof IllegalArgumentException,
                    "must not inherit the 400 handler that blames the caller");
            assertTrue(ex instanceof IllegalStateException,
                    "kept as an IllegalStateException so existing catch sites still work");
        }

        @Test
        @DisplayName("THE FINDING ITSELF: the lat/lon path and the platform path now agree about every radius")
        void theTwoRadiusPathsAgreeAboutEveryValue() {
            // WR-03 is not "a value was unvalidated", it is "the two paths DISAGREED about the
            // same bad value". So this asserts agreement directly, across the whole table and
            // both directions: for every radius, the caller-supplied path accepts it if and only
            // if the platform path does. A future tightening of either side alone fails here.
            lenient().when(shopRepository.findPublishedNear(anyDouble(), anyDouble(), anyDouble(),
                            anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            PublicStorefrontService svc = serviceWithRadii(5.0, 50.0);

            for (double candidate : new double[]{
                    0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    0.0001, 1.0, 5.0, 50.0, 50.0001, 500.0, 100000.0}) {

                boolean callerPathRejects = false;
                try {
                    svc.listPublishedShopsNear(51.47, -0.07, candidate, PageRequest.of(0, 20));
                } catch (RuntimeException e) {
                    callerPathRejects = true;
                }

                boolean platformPathRejects = false;
                try {
                    PublicStorefrontService.requireUsableRadius(candidate, 50.0);
                } catch (MisconfiguredPlatformRadiusException e) {
                    platformPathRejects = true;
                }

                assertEquals(callerPathRejects, platformPathRejects,
                        "the two paths must agree about radius " + candidate
                                + " — a divergence here IS the WR-03 defect");
            }
        }

        @Test
        @DisplayName("CONTROL: the agreement arm is decisive — it rejects some values and accepts others")
        void theAgreementArmIsNotVacuous() {
            // Without this, theTwoRadiusPathsAgreeAboutEveryValue would still pass if BOTH paths
            // accepted everything (the pre-fix postcode tier's behaviour, applied to both).
            assertThrows(MisconfiguredPlatformRadiusException.class,
                    () -> PublicStorefrontService.requireUsableRadius(0.0, 50.0));
            assertDoesNotThrow(() -> PublicStorefrontService.requireUsableRadius(5.0, 50.0));
        }

        @Test
        @DisplayName("a proximity answer never carries a radius the parser would reject as not-a-proximity")
        void theEmittedInterpretationCarriesAUsableRadius() {
            // The last link in the chain the finding traced: whatever radius reaches the header
            // must be one frontend/lib/search-interpretation.ts accepts, or the page silently
            // states "No kitchens match" over proximity-filtered results.
            UUID shopId = publishedShop.getId();
            when(postcodeGeocoder.locateSearchTerm("SE22")).thenReturn(Optional.of(
                    new PostcodeGeocoder.LocatedPostcode(
                            new PostcodeGeocoder.Coordinate(51.454445, -0.072403),
                            "SE22", PostcodeGeocoder.Precision.DISTRICT)));
            when(shopRepository.findPublishedNear(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(), anyDouble(), any()))
                    .thenReturn(new PageImpl<>(List.of(projection(shopId, "test-shop-abc12345", 1.5))));
            when(shopRepository.findAllById(List.of(shopId))).thenReturn(List.of(publishedShop));

            PublicStorefrontService.SearchOutcome outcome =
                    serviceWithRadii(5.0, 50.0).searchPublishedShops("SE22", PageRequest.of(0, 20));

            assertEquals(SearchInterpretation.Kind.PROXIMITY, outcome.interpretation().kind());
            assertTrue(outcome.interpretation().radiusKm() > 0.0,
                    "radiusKm <= 0 is exactly what the storefront parser degrades to text on");
            assertTrue(outcome.interpretation().headerValue().startsWith("proximity; "),
                    "a proximity page must state proximity: " + outcome.interpretation().headerValue());
        }
    }

    @Nested
    @DisplayName("SearchInterpretation.headerValue — a published grammar, and a header-injection sink")
    class SearchInterpretationGrammar {

        @Test
        @DisplayName("a text interpretation is the single literal token 'text'")
        void textGrammar() {
            assertEquals("text", SearchInterpretation.text().headerValue());
        }

        @Test
        @DisplayName("a district proximity reads exactly as 33-09's parser expects")
        void districtGrammar() {
            String value = SearchInterpretation
                    .proximity("SE22", PostcodeGeocoder.Precision.DISTRICT, 5.0)
                    .headerValue();

            assertEquals("proximity; postcode=SE22; precision=district; radiusKm=5.0", value);
        }

        @Test
        @DisplayName("a unit proximity differs only in the precision token")
        void unitGrammar() {
            String value = SearchInterpretation
                    .proximity("SE155BS", PostcodeGeocoder.Precision.UNIT, 5.0)
                    .headerValue();

            assertEquals("proximity; postcode=SE155BS; precision=unit; radiusKm=5.0", value);
        }

        @Test
        @DisplayName("T-33-08-05: a key carrying CR/LF or ';' degrades to 'text', never splits the response")
        void hostileKeyCannotSplitTheResponse() {
            // Reached only if the upstream regex were ever loosened, which is exactly when a
            // defence in depth has to hold. The key is interpolated into a header value, so CR,
            // LF and ';' are the injection alphabet.
            for (String hostile : List.of("SE22\r\nX-Evil: 1", "SE22; precision=unit",
                    "SE22\nSet-Cookie: a=b", "se22", "", "S")) {
                String value = SearchInterpretation
                        .proximity(hostile, PostcodeGeocoder.Precision.DISTRICT, 5.0)
                        .headerValue();

                assertEquals("text", value, "hostile or malformed key must degrade to text: " + hostile);
                assertFalse(value.contains("\r"), "no CR may survive");
                assertFalse(value.contains("\n"), "no LF may survive");
            }
        }

        @Test
        @DisplayName("CONTROL: the same assertion PASSES a legitimate key, so the rejection above "
                + "is about the key and not about the method")
        void legitimateKeyIsNotRejected() {
            // Without this, the arm above would be satisfied by a headerValue() that returns
            // "text" unconditionally — which is precisely the RED stub it was written against.
            assertNotEquals("text", SearchInterpretation
                    .proximity("SE22", PostcodeGeocoder.Precision.DISTRICT, 5.0)
                    .headerValue());
        }

        @Test
        @DisplayName("no coordinate ever appears in the header value (T-33-08-04)")
        void headerCarriesNoCoordinate() {
            String value = SearchInterpretation
                    .proximity("SE22", PostcodeGeocoder.Precision.DISTRICT, 5.0)
                    .headerValue();

            // A header lands in proxy and access logs; 33-06 established coordinates do not reach
            // a log. The postcode does appear, and that is accepted — it is already in the request
            // URI's query string, so the header opens no new sink.
            assertFalse(value.contains("51.4"), "no latitude in the header");
            assertFalse(value.contains("-0.0"), "no longitude in the header");
            assertFalse(value.toLowerCase(Locale.ROOT).contains("lat"), "no lat token at all");
        }
    }
}
