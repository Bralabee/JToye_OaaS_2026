package uk.jtoye.core.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.TenantAccessDeniedException;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.review.dto.CreateReviewRequest;
import uk.jtoye.core.review.dto.ReviewDto;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.security.TenantContext;

import java.util.Optional;
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
        // SEC-01 (Phase 13) — same tenant-match gate as PublicStorefrontService.
        // Defense-in-depth even on read-only path; the helper sets TenantContext
        // so the caller owns the finally-clear.
        Shop shop = resolvePublicShopForSlug(shopSlug);
        try {
            return reviewRepository.findByShopIdOrderByCreatedAtDesc(shop.getId(), pageable)
                    .map(this::toDto);
        } finally {
            TenantContext.clear();
        }
    }

    public ShopRatingSummary getShopRating(UUID shopId) {
        long count = reviewRepository.countByShopId(shopId);
        Double avgRating = reviewRepository.avgFoodRatingByShopId(shopId);
        return new ShopRatingSummary(count, avgRating != null ? avgRating : 0.0);
    }

    @Transactional
    public ReviewDto createReview(String shopSlug, String customerEmail, CreateReviewRequest request) {
        // SEC-01 (Phase 13) — tenant-match gate BEFORE the Review row is written.
        // High-severity path (WRITE under setTenantId below).
        Shop shop = resolvePublicShopForSlug(shopSlug);

        UUID tenantId = shop.getTenantId();
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

    /**
     * Resolve a public shop by slug with tenant-match gate — duplicate of
     * {@code PublicStorefrontService#resolvePublicShopForSlug} scoped to
     * ReviewService.
     *
     * <p>Intentional duplication per Phase 13 D-07: pulling a shared utility
     * would cross service boundaries (storefront ↔ review) without providing
     * meaningful reuse at 2 consumers. Consolidate if a third {@code /public/**}
     * service acquires the same pattern.
     *
     * <p>The SLF4J WARN log uses {@code source=reviews} as a discriminator so
     * the log aggregator (Phase 9 Loki) can separate review-layer spoof attempts
     * from storefront-layer attempts without requiring the HTTP request URI.
     *
     * @see uk.jtoye.core.storefront.PublicStorefrontService#resolvePublicShopForSlug
     */
    private Shop resolvePublicShopForSlug(String slug) {
        Shop shop = shopRepository.findBySlugAndPublishedTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + slug));

        Optional<UUID> upstreamTenant = TenantContext.get();
        if (upstreamTenant.isPresent() && !upstreamTenant.get().equals(shop.getTenantId())) {
            log.warn("event=tenant_spoof_attempt slug={} slugTenant={} upstreamTenant={} outcome=403 source=reviews",
                    slug, shop.getTenantId(), upstreamTenant.get());
            throw new TenantAccessDeniedException(
                    "Tenant mismatch between authenticated identity and requested shop");
        }

        TenantContext.set(shop.getTenantId());
        return shop;
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
