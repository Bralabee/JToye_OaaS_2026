package uk.jtoye.core.storefront;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.TenantAccessDeniedException;
import uk.jtoye.core.security.TenantContext;
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
    @DisplayName("getCustomerOrders returns orders for email")
    void getCustomerOrders() {
        Order order = new Order();
        setField(order, "id", UUID.randomUUID());
        order.setOrderNumber("ORD-HIST-001");
        order.setCustomerEmail("customer@test.com");
        order.setStatus(OrderStatus.COMPLETED);
        order.setTotalAmountPennies(2000L);
        order.setShopId(publishedShop.getId());
        order.setUpdatedAt(OffsetDateTime.now());

        when(orderRepository.findByCustomerEmailOrderByCreatedAtDesc("customer@test.com"))
                .thenReturn(List.of(order));
        when(shopRepository.findById(publishedShop.getId()))
                .thenReturn(Optional.of(publishedShop));

        var result = service.getCustomerOrders("customer@test.com");

        assertEquals(1, result.size());
        assertEquals("ORD-HIST-001", result.get(0).getOrderNumber());
        assertEquals("COMPLETED", result.get(0).getStatus());
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
