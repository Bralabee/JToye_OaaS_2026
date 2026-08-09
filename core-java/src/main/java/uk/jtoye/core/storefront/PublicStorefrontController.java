package uk.jtoye.core.storefront;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
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
import uk.jtoye.core.security.CustomerJwtVerifier;
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
    private final CustomerJwtVerifier customerJwtVerifier;

    public PublicStorefrontController(PublicStorefrontService storefrontService, ReviewService reviewService,
                                      CustomerJwtVerifier customerJwtVerifier) {
        this.storefrontService = storefrontService;
        this.reviewService = reviewService;
        this.customerJwtVerifier = customerJwtVerifier;
    }

    /**
     * Distance search is ADDITIVE (33-06 / #460 link 5).
     *
     * <p>With no {@code lat}/{@code lon}/{@code radiusKm} this endpoint is name-ascending over
     * every published shop, or a search when {@code q} is present. That unlocated default is what
     * the landing page and {@code /shop} depend on, so it is preserved rather than replaced — a
     * distance sort applied unconditionally would empty both surfaces for every visitor who has
     * not granted location.
     *
     * <p><b>What a postcode-shaped {@code q} changes (33-08 / #619).</b> This docblock used to
     * promise that with no coordinate the endpoint "behaves exactly as it did before", and that
     * sentence is no longer precisely true, so it is replaced rather than left standing. The
     * accurate statement has two halves:
     *
     * <ul>
     *   <li>a {@code q} that resolves to a GB postcode is served by proximity from that
     *       postcode's centroid, and <b>says so</b> in the {@code X-Search-Interpretation}
     *       response header — <b>including</b> when a shop's own text would have matched it;</li>
     *   <li>every other {@code q}, which is every non-postcode term and every postcode-shaped
     *       term the dataset does not know, behaves <b>exactly</b> as before.</li>
     * </ul>
     *
     * <p>The ordering was reversed at the 33-09 owner gate on 2026-08-09 (<em>"Interpretation-
     * first"</em>). It shipped in 33-08 with the postcode attempt last; the accepted cost of the
     * flip is that a shop literally named "SE22 Kitchen" no longer wins a search for {@code SE22}
     * unless it also sits inside the radius. See
     * {@link PublicStorefrontService#searchPublishedShops} for the full reasoning.
     *
     * <p>The header is emitted on {@code ?q=} responses only — never on the plain listing and
     * never on the {@code lat}/{@code lon} path — because it answers "how did you read my
     * {@code q}?" and with no {@code q} there is no question. See {@link SearchInterpretation}
     * for the grammar, which is a published contract.
     *
     * <p><b>Nothing is silently ignored.</b> A radius with no centre, one axis without the other,
     * and text search combined with distance search are all client errors returning an RFC 7807
     * typed 400. Accepting them and quietly dropping a parameter would tell the caller their filter
     * applied when it did not — the same defect class as a silent clamp.
     *
     * <p><b>lat and lon are personal data</b> (UK GDPR / ASVS V9). They are validated and used, and
     * they are never logged or persisted. Do not add them to a debug statement or an analytics
     * payload here or downstream — see {@code PublicStorefrontService.listPublishedShopsNear}.
     */
    @GetMapping("/shops")
    @Operation(summary = "List published shops",
            description = "Browse available shops. Optionally filter by search query, or order by "
                    + "distance from a coordinate. Supplying 'lat' and 'lon' returns published shops "
                    + "within 'radiusKm' (platform default when omitted, capped by the platform "
                    + "maximum), nearest first, each carrying 'distanceKm'. Coordinates are postcode "
                    + "centroids (~100 m, GB only), not door-level. 'lat' and 'lon' must be supplied "
                    + "together and cannot be combined with 'q'. With no coordinate the listing is "
                    + "name-ascending and 'distanceKm' is null. A 'q' that resolves to a GB "
                    + "postcode is read as a place and served by proximity from that postcode's "
                    + "centroid, nearest first, in preference to a text match on the same string; "
                    + "a 'q' that is not a postcode, or is a postcode the dataset does not know "
                    + "(Code-Point Open is GB only), is answered by the text search exactly as "
                    + "before. The 'X-Search-Interpretation' response header states which reading "
                    + "was applied.")
    @ApiResponse(responseCode = "200", description = "A page of published shops.",
            headers = @Header(name = SearchInterpretation.HEADER,
                    description = "How the server read 'q'. Present on ?q= responses only — absent "
                            + "from the plain listing and from the lat/lon distance path. Either "
                            + "the single token 'text', or "
                            + "'proximity; postcode=<KEY>; precision=<unit|district>; radiusKm=<n>'. "
                            + "Present even when the proximity result is empty, because "
                            + "\"no kitchens within the radius of SE22\" and \"nothing matches "
                            + "'SE22'\" are different answers.",
                    // implementation = String.class, not type = "string": the latter alone
                    // renders as an EMPTY schema object in the generated snapshot, which tells a
                    // machine consumer nothing. Verified by regenerating both ways.
                    schema = @Schema(implementation = String.class)))
    public Page<PublicShopDto> listShops(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon,
            @RequestParam(required = false) Double radiusKm,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable,
            HttpServletResponse response) {
        boolean located = lat != null || lon != null || radiusKm != null;
        if (located) {
            if (q != null && !q.isBlank()) {
                throw new IllegalArgumentException(
                        "'q' cannot be combined with a distance search ('lat'/'lon'/'radiusKm'); "
                                + "ranked text search and distance ordering are separate results");
            }
            return storefrontService.listPublishedShopsNear(lat, lon, radiusKm, pageable);
        }
        if (q != null && !q.isBlank()) {
            // NOTE for the reader who has just seen the guard above: this branch does NOT
            // contradict it. That guard refuses a coordinate the CALLER supplied alongside 'q'.
            // A postcode inside 'q' is a coordinate the SERVER derived, from the term the caller
            // asked us to interpret — there is no second, conflicting instruction to reconcile,
            // which is exactly what made the combination ambiguous. The guard stays.
            PublicStorefrontService.SearchOutcome outcome =
                    storefrontService.searchPublishedShops(q.trim(), pageable);
            response.setHeader(SearchInterpretation.HEADER, outcome.interpretation().headerValue());
            return outcome.page();
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
            description = "Paginated orders for a customer by email address, most recent first. The 'verify' parameter (a recent order number for this customer) is mandatory — without it the request is rejected to prevent email-based enumeration.")
    public ResponseEntity<Page<PublicOrderStatus>> getCustomerOrders(
            @RequestParam String email,
            @RequestParam(name = "verify") String verifyOrderNumber,
            @PageableDefault(size = 20) Pageable pageable) {
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
        return ResponseEntity.ok(storefrontService.getCustomerOrders(email, pageable));
    }

    /**
     * Issue #179 defect 1: order history for a LOGGED-IN customer.
     *
     * <p>The {@code /public/orders?email=&verify=} endpoint above requires a
     * recent order number as proof-of-ownership (AUDIT-W0-02) — a proof the
     * "My Orders" page cannot supply, because a customer session holds only the
     * email. This variant accepts a stronger proof instead: the customer's own
     * Keycloak access token (jtoye-customers realm), presented on the
     * {@code X-Customer-Token} header by the frontend's server-side proxy
     * (frontend/app/api/customer-orders/route.ts — the token lives in an
     * HttpOnly cookie and never touches browser JS).
     *
     * <p>Security: the email used for the lookup comes EXCLUSIVELY from the
     * cryptographically verified token ({@link CustomerJwtVerifier}: signature,
     * issuer, expiry, email_verified gate) — there is no email parameter on this
     * surface, so the AUDIT-W0-02 enumeration protection is not weakened: an
     * unauthenticated caller still cannot list orders by bare email anywhere.
     * The custom header (not {@code Authorization}) keeps customer tokens out of
     * the staff-realm resource-server filter, which would otherwise reject them
     * with a confusing 401 before reaching this handler.
     */
    @GetMapping("/orders/mine")
    @Operation(summary = "Customer order history (session-authenticated)",
            description = "Paginated orders for the logged-in customer, most recent first. Requires a valid "
                    + "jtoye-customers realm access token in the X-Customer-Token header; the customer email is "
                    + "taken from the verified token, never from a parameter.")
    public ResponseEntity<Page<PublicOrderStatus>> getMyOrders(
            @RequestHeader(name = "X-Customer-Token", required = false) String customerToken,
            @PageableDefault(size = 20) Pageable pageable) {
        // Throws ResponseStatusException → 401 on any verification failure;
        // the order query below is only ever reached with a proven email.
        String email = customerJwtVerifier.verifiedEmail(customerToken);
        return ResponseEntity.ok(storefrontService.getCustomerOrders(email, pageable));
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
