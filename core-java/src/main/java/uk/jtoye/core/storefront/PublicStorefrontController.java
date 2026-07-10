package uk.jtoye.core.storefront;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uk.jtoye.core.review.ReviewService;
import uk.jtoye.core.review.dto.CreateReviewRequest;
import uk.jtoye.core.review.dto.ReviewDto;
import uk.jtoye.core.storefront.dto.ShopConfigDto;
import uk.jtoye.core.storefront.dto.GuestOrderConfirmation;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;
import uk.jtoye.core.storefront.dto.PublicOrderStatus;
import uk.jtoye.core.storefront.dto.PublicAnnouncementDto;
import uk.jtoye.core.storefront.dto.PublicProductDto;
import uk.jtoye.core.storefront.dto.PublicPromotionDto;
import uk.jtoye.core.storefront.dto.PublicShopDto;

import java.util.List;
import java.util.Map;

/**
 * Public storefront API.
 *
 * <p><b>Versioning (issue #97 [P2-6]):</b> the canonical path is
 * {@code /api/v1/public/**}; the bare {@code /public/**} mapping is a legacy
 * alias kept for the deployed frontend/edge callers and MUST NOT be removed
 * until they migrate (removal would be a breaking change — announce +
 * deprecation window first). Both aliases serve identical handlers. New
 * clients should call {@code /api/v1/public/**}. The storefront package is
 * deliberately NOT in {@code WebConfig.API_V1_PACKAGES} — the alias pair is
 * explicit here so the legacy path survives.
 */
@RestController
@RequestMapping({"/public", "/api/v1/public"})
@Tag(name = "Public Storefront", description = "Public endpoints for customer-facing shop discovery, product browsing, order tracking, and reviews. Canonical prefix /api/v1/public; bare /public is a deprecated legacy alias.")
public class PublicStorefrontController {

    private final PublicStorefrontService storefrontService;
    private final ReviewService reviewService;

    public PublicStorefrontController(PublicStorefrontService storefrontService, ReviewService reviewService) {
        this.storefrontService = storefrontService;
        this.reviewService = reviewService;
    }

    @GetMapping("/shops")
    @Operation(summary = "List published shops", description = "Browse available shops. Optionally filter by search query.")
    public Page<PublicShopDto> listShops(
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        if (q != null && !q.isBlank()) {
            return storefrontService.searchPublishedShops(q.trim(), pageable);
        }
        return storefrontService.listPublishedShops(pageable);
    }

    @GetMapping("/shops/{slug}")
    @Operation(summary = "Get shop details", description = "Get full details of a published shop by its URL slug.")
    public ResponseEntity<PublicShopDto> getShop(@PathVariable String slug) {
        return ResponseEntity.ok(storefrontService.getShopBySlug(slug));
    }

    @GetMapping("/shops/{slug}/config")
    @Operation(summary = "Get shop config", description = "Server-driven content: announcements, featured products, active promotions.")
    public ResponseEntity<ShopConfigDto> getShopConfig(@PathVariable String slug) {
        return ResponseEntity.ok(storefrontService.getShopConfig(slug));
    }

    @GetMapping("/shops/{slug}/promotions")
    @Operation(summary = "Get active promotions", description = "Returns currently active promotions for a published shop.")
    public ResponseEntity<List<PublicPromotionDto>> getShopPromotions(@PathVariable String slug) {
        return ResponseEntity.ok(storefrontService.getActivePromotions(slug));
    }

    @GetMapping("/shops/{slug}/announcements")
    @Operation(summary = "Get active announcements", description = "Returns currently active announcements for a published shop.")
    public ResponseEntity<List<PublicAnnouncementDto>> getShopAnnouncements(@PathVariable String slug) {
        return ResponseEntity.ok(storefrontService.getActiveAnnouncements(slug));
    }

    @GetMapping("/shops/{slug}/products")
    @Operation(summary = "Get shop menu", description = "Get available products grouped by category for a published shop.")
    public ResponseEntity<Map<String, List<PublicProductDto>>> getShopProducts(@PathVariable String slug) {
        return ResponseEntity.ok(storefrontService.getShopProducts(slug));
    }

    @PostMapping("/shops/{slug}/orders")
    @Operation(summary = "Place a guest order", description = "Create an order as a guest customer. Prices are calculated server-side.")
    public ResponseEntity<GuestOrderConfirmation> createGuestOrder(
            @PathVariable String slug,
            @Valid @RequestBody GuestOrderRequest request) {
        GuestOrderConfirmation confirmation = storefrontService.createGuestOrder(slug, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(confirmation);
    }

    @GetMapping("/orders")
    @Operation(summary = "Customer order history",
            description = "List all orders for a customer by email address. The 'verify' parameter (a recent order number for this customer) is mandatory — without it the request is rejected to prevent email-based enumeration.")
    public ResponseEntity<List<PublicOrderStatus>> getCustomerOrders(
            @RequestParam String email,
            @RequestParam(name = "verify") String verifyOrderNumber) {
        // AUDIT-W0-02: 'verify' is mandatory. Spring's missing-required-param exception
        // already returns 400 for the absent case; we add an explicit guard for the
        // present-but-blank case so both surfaces look the same to the client.
        if (verifyOrderNumber == null || verifyOrderNumber.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "'verify' (a recent order number) is required");
        }
        // Throws ResourceNotFoundException → 404 if the (verify, email) pair does not
        // resolve to a real order — that is the proof-of-ownership gate.
        storefrontService.trackOrder(verifyOrderNumber, email);
        return ResponseEntity.ok(storefrontService.getCustomerOrders(email));
    }

    @GetMapping("/orders/{orderNumber}")
    @Operation(summary = "Track a guest order", description = "Look up order status by order number and email. Both must match.")
    public ResponseEntity<PublicOrderStatus> trackOrder(
            @PathVariable String orderNumber,
            @RequestParam String email) {
        return ResponseEntity.ok(storefrontService.trackOrder(orderNumber, email));
    }

    @GetMapping("/shops/{slug}/reviews")
    @Operation(summary = "Get shop reviews", description = "List verified customer reviews for a shop, newest first.")
    public Page<ReviewDto> getShopReviews(
            @PathVariable String slug,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return reviewService.getShopReviews(slug, pageable);
    }

    @PostMapping("/shops/{slug}/reviews")
    @Operation(summary = "Submit a review", description = "Leave a review for a completed order. One review per order.")
    public ResponseEntity<ReviewDto> createReview(
            @PathVariable String slug,
            @RequestParam String email,
            @Valid @RequestBody CreateReviewRequest request) {
        ReviewDto review = reviewService.createReview(slug, email, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(review);
    }
}
