package uk.jtoye.core.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.ReviewOrderShopMismatchException;
import uk.jtoye.core.exception.TenantAccessDeniedException;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.review.dto.OrderReviewRequest;
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

    /**
     * #846 (persona close-out 2026-11-04): a SIGNED-IN customer reviews one of their own orders, named by its
     * number, with the reviewer identified by the verified customer token and never by a parameter. The
     * controller passes the email {@code CustomerJwtVerifier} proved; nothing the client sends can change it.
     *
     * <p>The shop's published slug pins the tenant (SEC-01) before the order is read, so RLS scopes the read.
     * An order that does not exist and an order that is another customer's are the SAME
     * {@link ResourceNotFoundException}: this surface is addressed by a guessable order number, and a 400
     * "you can only review your own orders" would tell a stranger which numbers exist (D-05, the
     * VanishedRowStatus contract: never 403, never a distinguishable answer). The old unauthenticated
     * {@code POST /public/shops/{slug}/reviews?email=} that named the email itself was removed (#846, owner
     * ruling 2026-11-04); this is the only way to write a review.
     *
     * <p>Idempotent in effect rather than by key: {@code reviews.order_id} is UNIQUE (V27) and
     * {@code saveReview} refuses a second review first, so a replay can never create a second row.
     */
    @Transactional
    public ReviewDto createReviewForOrder(String shopSlug, String orderNumber, String customerEmail,
                                          OrderReviewRequest request) {
        Shop shop = resolvePublicShopForSlug(shopSlug);
        try {
            Order order = orderRepository.findByOrderNumber(orderNumber)
                    .filter(o -> customerEmail.equalsIgnoreCase(o.getCustomerEmail()))
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
            return saveReview(shop, order, customerEmail, request.foodRating(), request.deliveryRating(),
                    request.comment());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The gates and the write, run with the tenant already pinned and the order already shown to belong to
     * {@code customerEmail}. No photo is accepted on a new review: no upload path ever wrote review photos,
     * and the only endpoint that took a photo list was the removed email-in-URL one (#846).
     */
    private ReviewDto saveReview(Shop shop, Order order, String customerEmail, Integer foodRating,
                                 Integer deliveryRating, String comment) {
        UUID tenantId = shop.getTenantId();

        // 37-26 (D-21, UXT-005 P0): the order must have been placed AT THIS SHOP. RLS scopes
        // findById to the TENANT, not the shop, so without this a customer who bought once from
        // shop A could leave a "verified" review on every other shop of the tenant. The order is
        // a proof of purchase from the shop reviewed, or it is no proof at all. Typed 400, after
        // the ownership check (a stranger's order is refused as a stranger's first) and before
        // the status and one-per-order gates.
        if (!shop.getId().equals(order.getShopId())) {
            throw new ReviewOrderShopMismatchException();
        }

        // Validate: order must be completed
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new IllegalArgumentException("Can only review completed orders");
        }

        // Validate: no duplicate review
        if (reviewRepository.existsByOrderId(order.getId())) {
            throw new IllegalArgumentException("You have already reviewed this order");
        }

        Review review = new Review();
        review.setTenantId(tenantId);
        review.setShopId(shop.getId());
        review.setOrderId(order.getId());
        review.setCustomerEmail(customerEmail);
        review.setCustomerName(order.getCustomerName());
        review.setFoodRating(foodRating);
        review.setDeliveryRating(deliveryRating);
        review.setComment(comment);

        review = reviewRepository.save(review);
        log.info("Review created for shop {} order {} — food:{} delivery:{}",
                shop.getName(), order.getOrderNumber(), foodRating, deliveryRating);

        return toDto(review);
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

    /**
     * The PUBLIC review shape. Both callers are {@code /public/shops/{slug}/reviews} (the list and the
     * customer's own POST); no authenticated vendor view of reviews exists. The author is published as
     * {@link ReviewerDisplayName} ("Grace P."), never the stored full name (37-35, UXT-044).
     */
    private ReviewDto toDto(Review review) {
        ReviewDto dto = new ReviewDto();
        dto.setId(review.getId());
        dto.setCustomerName(ReviewerDisplayName.of(review.getCustomerName()));
        dto.setFoodRating(review.getFoodRating());
        dto.setDeliveryRating(review.getDeliveryRating());
        dto.setComment(review.getComment());
        dto.setPhotoUrls(review.getPhotoUrls());
        dto.setCreatedAt(review.getCreatedAt());
        return dto;
    }

    public record ShopRatingSummary(long reviewCount, double avgFoodRating) {}
}
