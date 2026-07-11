package uk.jtoye.core.storefront;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.TenantAccessDeniedException;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.payment.PaymentService;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.shop.DiscountType;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopAnnouncement;
import uk.jtoye.core.shop.ShopAnnouncementRepository;
import uk.jtoye.core.shop.ShopPromotion;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.storefront.dto.*;

import java.lang.reflect.Field;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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

        service = new PublicStorefrontService(shopRepository, productRepository, orderRepository, eventPublisher, entityManager, paymentService, promotionRepository, announcementRepository);

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
        when(paymentService.createPaymentIntent(any(Order.class))).thenReturn("cs_test_secret");
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
        when(paymentService.createPaymentIntent(any(Order.class))).thenReturn("cs_test_secret");
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
        when(paymentService.createPaymentIntent(any(Order.class))).thenReturn("cs_test_secret");
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
        when(paymentService.createPaymentIntent(any(Order.class))).thenReturn("cs_test_secret");
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
}
