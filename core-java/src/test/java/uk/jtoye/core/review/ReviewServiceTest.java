package uk.jtoye.core.review;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.review.dto.CreateReviewRequest;
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
    @InjectMocks private ReviewService reviewService;

    private Shop shop;
    private Order order;
    private UUID shopId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
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
        order.setStatus(OrderStatus.COMPLETED);
        order.setCustomerEmail("test@example.com");
        order.setCustomerName("Test User");
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

    @Test
    @DisplayName("createReview succeeds for completed order")
    void createReview_succeeds() {
        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrderId(orderId);
        request.setFoodRating(5);
        request.setDeliveryRating(4);
        request.setComment("Great food!");

        when(shopRepository.findBySlugAndPublishedTrue("test-shop")).thenReturn(Optional.of(shop));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(reviewRepository.existsByOrderId(orderId)).thenReturn(false);
        when(reviewRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = reviewService.createReview("test-shop", "test@example.com", request);
        assertNotNull(result);
        assertEquals(5, result.getFoodRating());
        assertEquals(4, result.getDeliveryRating());
    }

    @Test
    @DisplayName("createReview rejects non-completed order")
    void createReview_rejectsNonCompletedOrder() {
        order.setStatus(OrderStatus.PENDING);
        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrderId(orderId);
        request.setFoodRating(5);

        when(shopRepository.findBySlugAndPublishedTrue("test-shop")).thenReturn(Optional.of(shop));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThrows(IllegalArgumentException.class,
                () -> reviewService.createReview("test-shop", "test@example.com", request));
    }

    @Test
    @DisplayName("createReview rejects wrong customer email")
    void createReview_rejectsWrongEmail() {
        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrderId(orderId);
        request.setFoodRating(5);

        when(shopRepository.findBySlugAndPublishedTrue("test-shop")).thenReturn(Optional.of(shop));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThrows(IllegalArgumentException.class,
                () -> reviewService.createReview("test-shop", "wrong@example.com", request));
    }

    @Test
    @DisplayName("createReview rejects duplicate review")
    void createReview_rejectsDuplicate() {
        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrderId(orderId);
        request.setFoodRating(5);

        when(shopRepository.findBySlugAndPublishedTrue("test-shop")).thenReturn(Optional.of(shop));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(reviewRepository.existsByOrderId(orderId)).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> reviewService.createReview("test-shop", "test@example.com", request));
    }
}
