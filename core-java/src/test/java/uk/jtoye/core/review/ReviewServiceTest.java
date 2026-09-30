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
import uk.jtoye.core.exception.InvalidReviewPhotoException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.media.MediaNormalizer;
import uk.jtoye.core.media.MediaProperties;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.review.dto.CreateReviewRequest;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.storage.BlobObjectStore;
import uk.jtoye.core.storage.StorageProperties;
import uk.jtoye.core.storage.StorageService;

import java.util.ArrayList;
import java.util.Arrays;
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
    @Mock private BlobObjectStore blobObjectStore;
    private ReviewService reviewService;

    /** The public base URL of the REAL StorageService below; publicKeyOf is deliberately not stubbed. */
    private static final String BASE = "http://localhost:10000/devstoreaccount1/jtoye-images";

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
        // before order-validation logic runs — breaks createReview_rejectsNonCompletedOrder.
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
        order.setStatus(OrderStatus.COMPLETED);
        order.setCustomerEmail("test@example.com");
        order.setCustomerName("Test User");

        // #771: a REAL StorageService (the StorageServiceTest recipe), so the photo rule is exercised
        // through the same URL-to-key parse erasure uses, not a stub of it.
        StorageProperties properties = new StorageProperties();
        properties.getBlob().setPublicUrl(BASE);
        StorageService storageService = new StorageService(blobObjectStore, properties,
                new MediaNormalizer(new MediaProperties()));
        reviewService = new ReviewService(reviewRepository, orderRepository, shopRepository, storageService);
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

    // ---- #771: photoUrls must be this review's own photos -------------------------------------

    private String ownPhoto(String name) {
        return BASE + "/" + shop.getTenantId() + "/reviews/" + orderId + "/" + name;
    }

    private CreateReviewRequest requestWithPhotos(List<String> photoUrls) {
        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrderId(orderId);
        request.setFoodRating(5);
        request.setPhotoUrls(photoUrls);
        return request;
    }

    private void stubReviewableOrder() {
        when(shopRepository.findBySlugAndPublishedTrue("test-shop")).thenReturn(Optional.of(shop));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(reviewRepository.existsByOrderId(orderId)).thenReturn(false);
    }

    private InvalidReviewPhotoException assertRefused(String label, List<String> photoUrls) {
        stubReviewableOrder();
        InvalidReviewPhotoException e = assertThrows(InvalidReviewPhotoException.class,
                () -> reviewService.createReview("test-shop", "test@example.com", requestWithPhotos(photoUrls)),
                label);
        verify(reviewRepository, never()).save(any());
        return e;
    }

    @Test
    @DisplayName("#771: the shop's own product image URL is refused")
    void createReview_refusesTheShopsProductImage() {
        assertRefused("product image",
                List.of(BASE + "/" + shop.getTenantId() + "/products/" + UUID.randomUUID() + "/p.webp"));
    }

    @Test
    @DisplayName("#771: an external URL is refused")
    void createReview_refusesAnExternalUrl() {
        assertRefused("external", List.of("https://tracker.example.com/pixel.gif"));
    }

    @Test
    @DisplayName("#771: another tenant's review path is refused")
    void createReview_refusesAnotherTenantsReviewPath() {
        assertRefused("another tenant", List.of(BASE + "/" + UUID.randomUUID() + "/reviews/" + orderId + "/p.webp"));
    }

    @Test
    @DisplayName("#771: ANOTHER order's review path is refused")
    void createReview_refusesAnotherOrdersReviewPath() {
        assertRefused("another order",
                List.of(BASE + "/" + shop.getTenantId() + "/reviews/" + UUID.randomUUID() + "/p.webp"));
    }

    @Test
    @DisplayName("#771: a null entry is refused")
    void createReview_refusesANullEntry() {
        assertRefused("null entry", new ArrayList<>(Arrays.asList(ownPhoto("p.webp"), null)));
    }

    @Test
    @DisplayName("#771: the refusal names the index and never echoes the submitted URL")
    void createReview_refusalNamesTheIndexNotTheUrl() {
        String submitted = "https://tracker.example.com/pixel-" + UUID.randomUUID() + ".gif";
        InvalidReviewPhotoException e = assertRefused("index", List.of(ownPhoto("p.webp"), submitted));
        assertTrue(e.getMessage().contains("photoUrls[1]"), e.getMessage());
        assertFalse(e.getMessage().contains(submitted), "the detail must not echo the submitted URL");
        assertFalse(e.getMessage().contains("tracker.example.com"), "not even its host");
    }

    @Test
    @DisplayName("#771: a photo under this review's own order path is accepted and stored verbatim")
    void createReview_acceptsItsOwnOrderPhotoVerbatim() {
        String own = ownPhoto("p.webp");
        stubReviewableOrder();
        when(reviewRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = reviewService.createReview("test-shop", "test@example.com", requestWithPhotos(List.of(own)));

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(captor.capture());
        assertEquals(List.of(own), captor.getValue().getPhotoUrls());
        assertEquals(List.of(own), result.getPhotoUrls());
    }

    @Test
    @DisplayName("#771: photoUrls null and [] are accepted as before")
    void createReview_acceptsNoPhotos() {
        stubReviewableOrder();
        when(reviewRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertNotNull(reviewService.createReview("test-shop", "test@example.com", requestWithPhotos(null)));
        TenantContext.clear();
        assertNotNull(reviewService.createReview("test-shop", "test@example.com", requestWithPhotos(List.of())));
        verify(reviewRepository, times(2)).save(any());
    }
}
