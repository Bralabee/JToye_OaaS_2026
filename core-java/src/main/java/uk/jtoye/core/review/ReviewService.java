package uk.jtoye.core.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.review.dto.CreateReviewRequest;
import uk.jtoye.core.review.dto.ReviewDto;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.security.TenantContext;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ReviewService {
    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final ShopRepository shopRepository;

    public ReviewService(ReviewRepository reviewRepository, OrderRepository orderRepository,
                         ShopRepository shopRepository) {
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
        this.shopRepository = shopRepository;
    }

    public Page<ReviewDto> getShopReviews(String shopSlug, Pageable pageable) {
        Shop shop = shopRepository.findBySlugAndPublishedTrue(shopSlug)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopSlug));
        return reviewRepository.findByShopIdOrderByCreatedAtDesc(shop.getId(), pageable)
                .map(this::toDto);
    }

    public ShopRatingSummary getShopRating(UUID shopId) {
        long count = reviewRepository.countByShopId(shopId);
        Double avgRating = reviewRepository.avgFoodRatingByShopId(shopId);
        return new ShopRatingSummary(count, avgRating != null ? avgRating : 0.0);
    }

    @Transactional
    public ReviewDto createReview(String shopSlug, String customerEmail, CreateReviewRequest request) {
        Shop shop = shopRepository.findBySlugAndPublishedTrue(shopSlug)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopSlug));

        UUID tenantId = shop.getTenantId();
        TenantContext.set(tenantId);
        try {
            Order order = orderRepository.findById(request.getOrderId())
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

            // Validate: order belongs to this customer
            if (!customerEmail.equalsIgnoreCase(order.getCustomerEmail())) {
                throw new IllegalArgumentException("You can only review your own orders");
            }

            // Validate: order must be completed
            if (order.getStatus() != OrderStatus.COMPLETED) {
                throw new IllegalArgumentException("Can only review completed orders");
            }

            // Validate: no duplicate review
            if (reviewRepository.existsByOrderId(request.getOrderId())) {
                throw new IllegalArgumentException("You have already reviewed this order");
            }

            Review review = new Review();
            review.setTenantId(tenantId);
            review.setShopId(shop.getId());
            review.setOrderId(request.getOrderId());
            review.setCustomerEmail(customerEmail);
            review.setCustomerName(order.getCustomerName());
            review.setFoodRating(request.getFoodRating());
            review.setDeliveryRating(request.getDeliveryRating());
            review.setComment(request.getComment());
            review.setPhotoUrls(request.getPhotoUrls());

            review = reviewRepository.save(review);
            log.info("Review created for shop {} order {} — food:{} delivery:{}",
                    shop.getName(), order.getOrderNumber(), request.getFoodRating(), request.getDeliveryRating());

            return toDto(review);
        } finally {
            TenantContext.clear();
        }
    }

    private ReviewDto toDto(Review review) {
        ReviewDto dto = new ReviewDto();
        dto.setId(review.getId());
        dto.setCustomerName(review.getCustomerName());
        dto.setFoodRating(review.getFoodRating());
        dto.setDeliveryRating(review.getDeliveryRating());
        dto.setComment(review.getComment());
        dto.setPhotoUrls(review.getPhotoUrls());
        dto.setCreatedAt(review.getCreatedAt());
        return dto;
    }

    public record ShopRatingSummary(long reviewCount, double avgFoodRating) {}
}
