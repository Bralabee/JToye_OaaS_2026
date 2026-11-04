package uk.jtoye.core.review;

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
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.review.dto.OrderReviewRequest;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock private ReviewRepository reviewRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private ShopRepository shopRepository;
    private ReviewService reviewService;

    private Shop shop;
    private Order order;
    private UUID shopId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        // Phase 13 SEC-01 — clear TenantContext before each test to prevent
        // leakage from other unit-test classes that may run before this one
        // (e.g., PublicStorefrontServiceTest sets TenantContext via the same
        // helper). Without this, the tenant-match gate in resolvePublicShopForSlug
        // trips on a stale ThreadLocal and throws TenantAccessDeniedException
        // before order-validation logic runs — breaks createReviewForOrder_rejectsNonCompletedOrder.
        TenantContext.clear();

        shopId = UUID.randomUUID();
        orderId = UUID.randomUUID();

        shop = new Shop();
        try {
            var idField = Shop.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(shop, shopId);
        } catch (Exception e) { throw new RuntimeException(e); }
        shop.setTenantId(UUID.randomUUID());

        order = new Order();
        try {
            var idField = Order.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, orderId);
        } catch (Exception e) { throw new RuntimeException(e); }
        // 37-26 (D-21): the fixture order is placed AT the reviewed shop. Until the shop check
        // existed this was never declared, and a null shop id passed every gate.
        order.setShopId(shopId);
        order.setStatus(OrderStatus.COMPLETED);
        order.setCustomerEmail("test@example.com");
        order.setCustomerName("Test User");

        reviewService = new ReviewService(reviewRepository, orderRepository, shopRepository);
    }

    @AfterEach
    void tearDown() {
        // Phase 13 SEC-01 — ReviewService now sets TenantContext via
        // resolvePublicShopForSlug on the happy path, and the helper is
        // forbidden from clearing on failure (D-09). Without this cleanup,
        // TenantContext leaks across tests (each @BeforeEach creates a shop
        // with a fresh random tenantId, so the leaked value would trip the
        // tenant-match gate on subsequent tests).
        TenantContext.clear();
    }

    @Test
    @DisplayName("getShopReviews returns paginated reviews")
    void getShopReviews_returnsPaginatedReviews() {
        Review review = new Review();
        review.setFoodRating(5);
        review.setCustomerName("Jane");

        when(shopRepository.findBySlugAndPublishedTrue("test-shop"))
                .thenReturn(Optional.of(shop));
        when(reviewRepository.findByShopIdOrderByCreatedAtDesc(eq(shopId), any()))
                .thenReturn(new PageImpl<>(List.of(review)));

        Page<uk.jtoye.core.review.dto.ReviewDto> result =
                reviewService.getShopReviews("test-shop", PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        assertEquals(5, result.getContent().get(0).getFoodRating());
    }

    @Test
    @DisplayName("getShopReviews throws when shop not found")
    void getShopReviews_throwsWhenShopNotFound() {
        when(shopRepository.findBySlugAndPublishedTrue("nope")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> reviewService.getShopReviews("nope", PageRequest.of(0, 10)));
    }

    @Test
    @DisplayName("getShopRating returns summary")
    void getShopRating_returnsSummary() {
        when(reviewRepository.countByShopId(shopId)).thenReturn(5L);
        when(reviewRepository.avgFoodRatingByShopId(shopId)).thenReturn(4.2);

        var result = reviewService.getShopRating(shopId);
        assertEquals(5, result.reviewCount());
        assertEquals(4.2, result.avgFoodRating());
    }

    @Test
    @DisplayName("getShopRating handles null average")
    void getShopRating_handlesNullAverage() {
        when(reviewRepository.countByShopId(shopId)).thenReturn(0L);
        when(reviewRepository.avgFoodRatingByShopId(shopId)).thenReturn(null);

        var result = reviewService.getShopRating(shopId);
        assertEquals(0, result.reviewCount());
        assertEquals(0.0, result.avgFoodRating());
    }

    private OrderReviewRequest request(int food, Integer delivery, String comment) {
        return new OrderReviewRequest(food, delivery, comment);
    }

    private void stubOrder() {
        when(shopRepository.findBySlugAndPublishedTrue("test-shop")).thenReturn(Optional.of(shop));
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(order));
    }

    @Test
    @DisplayName("createReviewForOrder succeeds for the owner's completed order")
    void createReviewForOrder_succeeds() {
        stubOrder();
        when(reviewRepository.existsByOrderId(orderId)).thenReturn(false);
        when(reviewRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = reviewService.createReviewForOrder("test-shop", "ORD-1", "test@example.com",
                request(5, 4, "Great food!"));
        assertNotNull(result);
        assertEquals(5, result.getFoodRating());
        assertEquals(4, result.getDeliveryRating());
        ArgumentCaptor<Review> saved = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(saved.capture());
        assertEquals("test@example.com", saved.getValue().getCustomerEmail());
        assertNull(saved.getValue().getPhotoUrls(), "a new review carries no photos");
    }

    @Test
    @DisplayName("37-26 D-21 / UXT-005: createReviewForOrder refuses an order placed at ANOTHER shop of the tenant, before the COMPLETED and duplicate gates")
    void createReviewForOrder_refusesAnOrderFromAnotherShop() {
        order.setShopId(UUID.randomUUID()); // bought at shop A; reviewing the shop the slug resolves to
        stubOrder();
        // lenient: on the fixed tree the refusal fires before save; on the unfixed tree save is reached.
        lenient().when(reviewRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThrows(uk.jtoye.core.exception.ReviewOrderShopMismatchException.class,
                () -> reviewService.createReviewForOrder("test-shop", "ORD-1", "test@example.com", request(5, null, null)));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("createReviewForOrder rejects non-completed order")
    void createReviewForOrder_rejectsNonCompletedOrder() {
        order.setStatus(OrderStatus.PENDING);
        stubOrder();

        assertThrows(IllegalArgumentException.class,
                () -> reviewService.createReviewForOrder("test-shop", "ORD-1", "test@example.com", request(5, null, null)));
    }

    @Test
    @DisplayName("createReviewForOrder: another customer's order is the same not-found as an unknown one (no existence oracle)")
    void createReviewForOrder_wrongEmailIsNotFound() {
        stubOrder();

        assertThrows(ResourceNotFoundException.class,
                () -> reviewService.createReviewForOrder("test-shop", "ORD-1", "wrong@example.com", request(5, null, null)));
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("createReviewForOrder rejects duplicate review")
    void createReviewForOrder_rejectsDuplicate() {
        stubOrder();
        when(reviewRepository.existsByOrderId(orderId)).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> reviewService.createReviewForOrder("test-shop", "ORD-1", "test@example.com", request(5, null, null)));
    }
}
