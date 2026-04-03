package uk.jtoye.core.storefront;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
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
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.storefront.dto.*;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
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

        service = new PublicStorefrontService(shopRepository, productRepository, orderRepository, eventPublisher, entityManager);

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
}
