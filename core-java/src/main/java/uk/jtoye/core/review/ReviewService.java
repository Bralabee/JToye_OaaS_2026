package uk.jtoye.core.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.InvalidReviewPhotoException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.ReviewOrderShopMismatchException;
import uk.jtoye.core.exception.TenantAccessDeniedException;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.review.dto.CreateReviewRequest;
import uk.jtoye.core.review.dto.OrderReviewRequest;
import uk.jtoye.core.review.dto.ReviewDto;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.StorageService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ReviewService {
    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final ShopRepository shopRepository;
    private final StorageService storageService;

    public ReviewService(ReviewRepository reviewRepository, OrderRepository orderRepository,
                         ShopRepository shopRepository, StorageService storageService) {
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
        this.shopRepository = shopRepository;
        this.storageService = storageService;
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

            return saveReview(shop, order, customerEmail, request.getFoodRating(), request.getDeliveryRating(),
                    request.getComment(), request.getPhotoUrls());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * #846 (persona close-out 2026-11-04): a SIGNED-IN customer reviews one of their own orders, named by its
     * number, with the reviewer identified by the verified customer token and never by a parameter. The
     * controller passes the email {@code CustomerJwtVerifier} proved; nothing the client sends can change it.
     *
     * <p>The shop's published slug pins the tenant (SEC-01) before the order is read, so RLS scopes the read.
     * An order that does not exist and an order that is another customer's are the SAME
     * {@link ResourceNotFoundException}: unlike {@link #createReview} (kept, unchanged, for the API clients that
     * address an order by id and name the email themselves), this surface is addressed by a guessable
     * order number, and a 400 "you can only review your own orders" would tell a stranger which numbers exist
     * (D-05, the VanishedRowStatus contract: never 403, never a distinguishable answer). The remaining gates
     * (this shop, COMPLETED, one per order) are shared with {@link #createReview} through {@link #saveReview}.
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
                    request.comment(), null);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The gates and the write both review endpoints share, run with the tenant already pinned and the
     * order already shown to belong to {@code customerEmail}.
     */
    private ReviewDto saveReview(Shop shop, Order order, String customerEmail, Integer foodRating,
                                 Integer deliveryRating, String comment, List<String> photoUrls) {
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

        requireOwnReviewPhotos(photoUrls, tenantId, order.getId());

        Review review = new Review();
        review.setTenantId(tenantId);
        review.setShopId(shop.getId());
        review.setOrderId(order.getId());
        review.setCustomerEmail(customerEmail);
        review.setCustomerName(order.getCustomerName());
        review.setFoodRating(foodRating);
        review.setDeliveryRating(deliveryRating);
        review.setComment(comment);
        review.setPhotoUrls(photoUrls);

        review = reviewRepository.save(review);
        log.info("Review created for shop {} order {} — food:{} delivery:{}",
                shop.getName(), order.getOrderNumber(), foodRating, deliveryRating);

        return toDto(review);
    }

    /**
     * Issue #771: every {@code photoUrls} entry must be a public storage URL whose key is this
     * review's own photo key — {@code <shop tenant>/reviews/<orderId>/<one plain name>}, the rule
     * {@link ReviewPhotoKeys} owns and GDPR erasure applies again. The URL-to-key parse is
     * {@link StorageService#publicKeyOf}, the same one {@code delete(url)} acts on.
     *
     * <p>No upload path writes review photos yet, so in practice only {@code null} or an empty list
     * is accepted today; the shape is the storage contract for whichever upload path comes first.
     * Object existence is not checked: a key naming a missing object renders a broken image and can
     * only ever delete itself. The first null, unparseable or non-conforming entry is refused; the
     * message names its index and the required shape, never the submitted URL.
     */
    private void requireOwnReviewPhotos(List<String> urls, UUID tenantId, UUID orderId) {
        if (urls == null) {
            return;
        }
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            Optional<String> key = url == null ? Optional.empty() : storageService.publicKeyOf(url);
            if (key.isEmpty() || !ReviewPhotoKeys.isReviewPhotoKey(key.get(), tenantId, orderId)) {
                throw new InvalidReviewPhotoException("photoUrls[" + i + "] is not a photo of this review: each "
                        + "entry must be a stored image under <tenant>/reviews/<orderId>/<name>");
            }
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
