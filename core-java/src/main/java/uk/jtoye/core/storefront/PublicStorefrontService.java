package uk.jtoye.core.storefront;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.common.idempotency.IdempotencyService;
import uk.jtoye.core.exception.MisconfiguredPlatformRadiusException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.TenantAccessDeniedException;
import uk.jtoye.core.geo.GeoBounds;
import uk.jtoye.core.geo.PostcodeGeocoder;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderAllergenSnapshot;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.order.OrderItem;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;
import uk.jtoye.core.finance.VatCalculator;
import uk.jtoye.core.payment.PaymentIntentResult;
import uk.jtoye.core.payment.PaymentService;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopAnnouncementRepository;
import uk.jtoye.core.shop.ShopPromotionRepository;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.shop.ShopWithDistance;
import uk.jtoye.core.storefront.dto.PublicAnnouncementDto;
import uk.jtoye.core.storefront.dto.PublicPromotionDto;
import uk.jtoye.core.storefront.dto.ShopConfigDto;
import uk.jtoye.core.storefront.dto.GuestOrderConfirmation;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;
import uk.jtoye.core.storefront.dto.PublicOrderStatus;
import uk.jtoye.core.storefront.dto.PublicProductDto;
import uk.jtoye.core.storefront.dto.PublicShopDto;


import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PublicStorefrontService {
    private static final Logger log = LoggerFactory.getLogger(PublicStorefrontService.class);

    /**
     * The logical operation id the guest checkout reserves under in the V50 {@code idempotency_keys}
     * store (QA council 20260902-134741, Cluster E / adjudication A3). Deliberately NOT
     * {@code orders.create}: the store is shared with the dashboard create, which persists its
     * response body, and this path persists none — the same key arriving on both endpoints must
     * never resolve to one row.
     */
    public static final String GUEST_ORDER_ENDPOINT = "storefront.orders.create";

    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderEventPublisher eventPublisher;
    private final EntityManager entityManager;
    private final PaymentService paymentService;
    private final ShopPromotionRepository promotionRepository;
    private final ShopAnnouncementRepository announcementRepository;
    private final IdempotencyService idempotencyService;

    /**
     * The offline postcode geocoder (33-02), consulted FIRST for every {@code q} by
     * {@link #searchPublishedShops} — D-A, flipped at the 33-09 owner gate on 2026-08-09.
     *
     * <p>This text was left behind by that flip and said the opposite until 2026-08-09 (WR-01):
     * "used ONLY by the third search tier and only when the two text tiers have already returned
     * nothing". Both clauses were false, and this field declaration is the natural entry point
     * when tracing the dependency, so it read as evidence the flip had never happened.
     *
     * <p>Running it first is cheap rather than costly: {@link PostcodeGeocoder#locateSearchTerm}
     * applies its length bound and its anchored shape test BEFORE any lookup, so an ordinary food
     * search issues no query here at all. It is the search-side entry point
     * {@code locateSearchTerm}, never {@code locate} — see that class.
     */
    private final PostcodeGeocoder postcodeGeocoder;

    /**
     * Platform default and ceiling for the distance-search radius, READ from {@code jtoye.geo.*}
     * in {@code application.yml} — that block is owned and declared by 33-02, and this class only
     * consumes it. Q-2 settled the radius as a query parameter with a platform default, so both
     * values have to be operator-tunable without a code edit; a literal in this file would defeat
     * the decision.
     *
     * <p>Deliberately NO inline {@code :default} on either placeholder. An inline default makes a
     * missing key invisible — the recorded failure mode where eight outbox tunables were bound by
     * {@code @Value} defaults, appeared in no yml, and could only be changed by rebuilding the
     * image. If the key goes missing the context must fail to start, loudly.
     *
     * <p><strong>And a PRESENT key can be as wrong as a missing one (WR-03).</strong> These are
     * {@code ${GEO_DEFAULT_RADIUS_KM:5}} / {@code ${GEO_MAX_RADIUS_KM:50}} — operator-tunable
     * environment variables — so the constructor validates the pair on the same principle: an
     * unusable radius fails the context, it does not wait for a customer to find it. See
     * {@link #requireUsableRadius}.
     */
    private final double defaultRadiusKm;
    private final double maxRadiusKm;

    public PublicStorefrontService(ShopRepository shopRepository, ProductRepository productRepository,
                                   OrderRepository orderRepository, OrderEventPublisher eventPublisher,
                                   EntityManager entityManager, PaymentService paymentService,
                                   ShopPromotionRepository promotionRepository,
                                   ShopAnnouncementRepository announcementRepository,
                                   IdempotencyService idempotencyService,
                                   PostcodeGeocoder postcodeGeocoder,
                                   @Value("${jtoye.geo.default-radius-km}") double defaultRadiusKm,
                                   @Value("${jtoye.geo.max-radius-km}") double maxRadiusKm) {
        this.shopRepository = shopRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
        this.paymentService = paymentService;
        this.promotionRepository = promotionRepository;
        this.announcementRepository = announcementRepository;
        this.idempotencyService = idempotencyService;
        this.postcodeGeocoder = postcodeGeocoder;
        // WR-03 LAYER 1 — STARTUP. Validate the platform radius here, where a bad value is a
        // BeanCreationException at boot, rather than only where it becomes a query input. The
        // failure this closes is not hypothetical: GEO_DEFAULT_RADIUS_KM=0 previously produced a
        // genuine proximity-filtered page carrying `radiusKm=0.0`, which the storefront's parser
        // then correctly rejected and rendered as `No kitchens match "SE22"` — a page stating the
        // opposite of what the server did, from one environment variable.
        requireUsableRadius(defaultRadiusKm, maxRadiusKm);
        this.defaultRadiusKm = defaultRadiusKm;
        this.maxRadiusKm = maxRadiusKm;
    }

    /**
     * The single definition of "a radius this platform can answer with", applied at BOTH the point
     * it is configured and the point it reaches the query (WR-03).
     *
     * <p>{@code maxRadiusKm} is checked too, and not as decoration: it is
     * {@code ${GEO_MAX_RADIUS_KM:50}}, so it is as operator-settable as the default. A
     * {@code NaN} ceiling would make {@code radiusKm > maxRadiusKm} false for every input —
     * IEEE-754 comparisons against NaN are always false — and the ceiling would silently stop
     * existing rather than fail.
     *
     * <p>Package-private and static so the fail direction can be driven directly. A guard that has
     * only ever been observed passing is not evidence.
     *
     * @throws MisconfiguredPlatformRadiusException if the radius is not finite, not positive, or
     *         above a usable ceiling. Never {@code IllegalArgumentException}: this is the
     *         operator's fault, and the generic handler renders that as a 400 blaming the caller.
     */
    static void requireUsableRadius(double radiusKm, double maxRadiusKm) {
        if (!Double.isFinite(maxRadiusKm) || maxRadiusKm <= 0.0) {
            throw new MisconfiguredPlatformRadiusException(
                    "jtoye.geo.max-radius-km must be a finite number greater than 0, was: " + maxRadiusKm);
        }
        if (!Double.isFinite(radiusKm) || radiusKm <= 0.0) {
            throw new MisconfiguredPlatformRadiusException(
                    "jtoye.geo.default-radius-km must be a finite number greater than 0, was: " + radiusKm);
        }
        if (radiusKm > maxRadiusKm) {
            throw new MisconfiguredPlatformRadiusException(
                    "jtoye.geo.default-radius-km (" + radiusKm + ") must not exceed "
                            + "jtoye.geo.max-radius-km (" + maxRadiusKm + ")");
        }
    }

    /**
     * Get server-driven config for a shop: announcements, featured products, promotions.
     */
    public ShopConfigDto getShopConfig(String slug) {
        // SEC-01 tenant-match gate applied up-front (Phase 13). Helper sets
        // TenantContext only on success; caller still owns cleanup in finally.
        Shop shop = resolvePublicShopForSlug(slug);

        ShopConfigDto config = new ShopConfigDto();
        // Announcements from shop_announcements table (V29) — query active within date window
        List<ShopConfigDto.AnnouncementSummary> announcements = announcementRepository.findActiveByShopId(shop.getId()).stream()
                .map(a -> new ShopConfigDto.AnnouncementSummary(a.getTitle(), a.getBody(), a.getValidUntil()))
                .toList();
        config.setAnnouncements(announcements);

        // Fetch featured products (TenantContext was set by the helper above).
        try {
            List<PublicProductDto> featured = List.of();
            if (shop.getFeaturedProductIds() != null && !shop.getFeaturedProductIds().isEmpty()) {
                featured = productRepository.findAllById(shop.getFeaturedProductIds()).stream()
                        .filter(p -> Boolean.TRUE.equals(p.getAvailable()))
                        .map(this::toPublicProductDto)
                        .toList();
            }
            config.setFeaturedProducts(featured);
        } finally {
            TenantContext.clear();
        }

        // Fetch active promotions
        List<ShopConfigDto.PromotionDto> promos = promotionRepository.findActiveByShopId(shop.getId()).stream()
                .map(p -> new ShopConfigDto.PromotionDto(p.getLabel(), p.getDiscountType(), p.getDiscountPercent(), p.getDiscountAmountPennies(), p.getCategory(), p.getValidUntil()))
                .toList();
        config.setActivePromotions(promos);

        return config;
    }

    /**
     * Get active promotions for a published shop.
     */
    public List<PublicPromotionDto> getActivePromotions(String slug) {
        Shop shop = shopRepository.findBySlugAndPublishedTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + slug));

        return promotionRepository.findActiveByShopId(shop.getId()).stream()
                .map(p -> {
                    PublicPromotionDto dto = new PublicPromotionDto();
                    dto.setLabel(p.getLabel());
                    dto.setDiscountType(p.getDiscountType());
                    dto.setDiscountPercent(p.getDiscountPercent());
                    dto.setDiscountAmountPennies(p.getDiscountAmountPennies());
                    dto.setCategory(p.getCategory());
                    dto.setValidUntil(p.getValidUntil());
                    return dto;
                })
                .toList();
    }

    /**
     * Get active announcements for a published shop.
     */
    public List<PublicAnnouncementDto> getActiveAnnouncements(String slug) {
        Shop shop = shopRepository.findBySlugAndPublishedTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + slug));

        return announcementRepository.findActiveByShopId(shop.getId()).stream()
                .map(a -> {
                    PublicAnnouncementDto dto = new PublicAnnouncementDto();
                    dto.setTitle(a.getTitle());
                    dto.setBody(a.getBody());
                    dto.setValidUntil(a.getValidUntil());
                    return dto;
                })
                .toList();
    }

    /**
     * List published shops. The public RLS policy allows SELECT on published=true
     * without tenant context.
     */
    public Page<PublicShopDto> listPublishedShops(Pageable pageable) {
        log.debug("Listing published shops, page {}", pageable.getPageNumber());
        return shopRepository.findByPublishedTrue(pageable)
                .map(this::toPublicShopDto);
    }

    /**
     * List published shops ordered by real distance from a caller-supplied coordinate, filtered to
     * a radius (33-06 / #460 link 5).
     *
     * <h2>Validation is an ASVS V5 control, not a formality</h2>
     *
     * <p>These are new UNAUTHENTICATED numeric parameters that cross into a native SQL query.
     * Every one is range-checked here, before {@link GeoBounds} and before the repository, and an
     * out-of-range value is an {@link IllegalArgumentException} — which {@code GlobalExceptionHandler}
     * renders as an RFC 7807 {@code https://jtoye.uk/errors/invalid-argument} 400. Never a 500, and
     * never a silent clamp: a caller who asks for 500 km and is quietly given 50 has been told
     * something false about the results.
     *
     * <p>The finiteness check is not decoration. {@code lat=NaN} binds successfully and passes both
     * {@code < -90} and {@code > 90}, so the range comparisons alone would let it through to the
     * query.
     *
     * <h2>PRIVACY — do not log lat/lon (T-33-06-04, ASVS V9)</h2>
     *
     * <p>A precise device coordinate is personal data under UK GDPR. It is not written to any log
     * here, is not persisted anywhere, and must not be added to a debug statement, an analytics
     * payload or an access-log query string later. The debug line below deliberately records the
     * radius and the page only — those are not personal data. Note that the exception messages
     * raised here also name the permitted RANGE and never echo the value supplied, because a
     * {@code detail} string travels into client logs and error trackers.
     *
     * <h2>Accuracy, stated so a caller does not over-read it</h2>
     *
     * <p>Shop coordinates are postcode centroids (~100 m) from OS Code-Point Open, which is GB-only.
     * A Northern Ireland shop keeps its storefront and is permanently absent from these results;
     * that is a licence-containment choice recorded in 33-02's SOURCE.md, not a defect.
     */
    public Page<PublicShopDto> listPublishedShopsNear(Double latitude, Double longitude,
                                                     Double radiusKm, Pageable pageable) {
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException(
                    "'lat' and 'lon' must be supplied together to search by distance");
        }
        if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("'lat' must be a number between -90 and 90");
        }
        if (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("'lon' must be a number between -180 and 180");
        }

        // WR-03: the two branches raise DIFFERENT exception types, and that is the point. A radius
        // the caller sent is the caller's to fix (IllegalArgumentException -> typed 400). A radius
        // they did NOT send is the operator's (MisconfiguredPlatformRadiusException -> 500 + ERROR
        // log, raised in nearestPublished and already refused at startup). The old single branch
        // validated both with the caller-facing message, so a bad GEO_DEFAULT_RADIUS_KM told an
        // anonymous customer that the 'radiusKm' they never supplied was invalid.
        double radius;
        if (radiusKm != null) {
            if (!Double.isFinite(radiusKm) || radiusKm <= 0.0) {
                throw new IllegalArgumentException("'radiusKm' must be a number greater than 0");
            }
            if (radiusKm > maxRadiusKm) {
                // Named ceiling, no clamp. The caller must learn that their request was refused.
                throw new IllegalArgumentException(
                        "'radiusKm' must not exceed " + maxRadiusKm);
            }
            radius = radiusKm;
        } else {
            radius = defaultRadiusKm;
        }

        log.debug("Listing published shops by distance, radiusKm={}, page {} — coordinates deliberately not logged",
                radius, pageable.getPageNumber());

        return nearestPublished(latitude, longitude, radius, pageable);
    }

    /**
     * The distance query and its projection-to-DTO tail, shared by BOTH callers that need it:
     * the caller-supplied coordinate path ({@link #listPublishedShopsNear}) and the postcode
     * tier of {@link #searchPublishedShops}.
     *
     * <p>Extracted rather than duplicated, and the reason is specific rather than stylistic. The
     * projection cannot carry {@code shops.opening_hours} — that column is {@code jsonb} and a
     * native tuple hands back raw JSON that a projection getter cannot convert — so the entities
     * are re-resolved and mapped through the SAME {@code toPublicShopDto} the unlocated listing
     * uses. A second shop-mapping path would be the one quietly missing opening hours, and no
     * assertion on either path alone would see it (33-06's recorded reason).
     *
     * <p>Callers own COORDINATE validation. The RADIUS is validated here as well as by them, and
     * WR-03 is why that changed. This method previously trusted its radius on the reasoning that
     * "the radius is a platform value rather than a caller's" — but a platform value is
     * {@code ${GEO_DEFAULT_RADIUS_KM:5}}, an environment variable, and is exactly as capable of
     * being wrong as a caller's. The two entry points then disagreed about the same bad value:
     * {@code ?lat=&lon=} refused {@code 0} with a typed 400 while {@code ?q=SE22} answered
     * {@code HTTP 200} with an empty page and {@code radiusKm=0.0} in the interpretation header,
     * which the storefront reads as "not a proximity answer" and renders as
     * {@code No kitchens match "SE22"} — over results that WERE proximity-filtered.
     */
    private Page<PublicShopDto> nearestPublished(double latitude, double longitude,
                                                 double radiusKm, Pageable pageable) {
        // WR-03 LAYER 2 — QUERY INPUT. Unreachable in a booted context, because the constructor
        // refuses the same values at startup, and kept anyway: it is what makes the invariant
        // local to the query that depends on it, so a future caller reaching here by another
        // route cannot reintroduce the silent empty page. Loud (500 + ERROR log) rather than a
        // clamp — a clamp would hand back a page whose header states a radius nothing applied.
        requireUsableRadius(radiusKm, maxRadiusKm);

        // The box is computed HERE and passed as four more named parameters, which is what keeps
        // the prefilter leakproof and index-eligible under the RLS barrier — see the comment on
        // ShopRepository.findPublishedNear.
        GeoBounds box = GeoBounds.boxAround(latitude, longitude, radiusKm);

        // Unsorted on purpose: the query owns its ordering (nearest first, id as tiebreak) and a
        // client-supplied Sort must never reach a native ORDER BY (T-33-06-02).
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.unsorted());

        Page<ShopWithDistance> page = shopRepository.findPublishedNear(
                latitude, longitude,
                box.minLatitude(), box.maxLatitude(), box.minLongitude(), box.maxLongitude(),
                radiusKm, unsorted);

        // One extra query for the page's shops, then the SAME toPublicShopDto the unlocated
        // listing uses — so a located result differs from an unlocated one by exactly one field.
        // The projection cannot carry shops.opening_hours (jsonb); see ShopWithDistance.
        Map<UUID, Shop> byId = shopRepository.findAllById(
                        page.getContent().stream().map(ShopWithDistance::getId).toList())
                .stream()
                .collect(Collectors.toMap(Shop::getId, s -> s));

        // Page.map, never `new PageImpl<>(content, pageable, total)`: the hand-built form REWRITES
        // the total it is handed whenever offset + size exceeds it (recorded trap).
        return page.map(projection -> {
            Shop shop = byId.get(projection.getId());
            if (shop == null) {
                // Only reachable if a row disappeared between the two queries. Return what the
                // projection knows rather than a null element in the page.
                PublicShopDto sparse = new PublicShopDto();
                sparse.setSlug(projection.getSlug());
                sparse.setDistanceKm(projection.getDistanceKm());
                return sparse;
            }
            PublicShopDto dto = toPublicShopDto(shop);
            dto.setDistanceKm(projection.getDistanceKm());
            return dto;
        });
    }

    /**
     * Answer a search term as a PLACE if it names one, and as text otherwise (33-08 / #619).
     *
     * <h2>Interpretation-first, and the order is a product decision (D-A)</h2>
     *
     * <ol>
     *   <li><strong>the term read as a GB postcode</strong>, answered by distance</li>
     *   <li>ranked full-text search over {@code shops.search_vector}</li>
     *   <li>the pre-existing {@code LIKE} fallback for short queries</li>
     * </ol>
     *
     * <p><strong>This ordering was reversed at the 33-09 owner gate on 2026-08-09.</strong> It
     * shipped in 33-08 with the postcode attempt LAST, on the argument that every query which
     * already returned results would then take an untouched path. The owner's verdict was
     * <em>"Interpretation-first"</em>: a full postcode that happens to match a shop's own address
     * is a question about a PLACE, not about that shop. {@code SE15 5BS} must return every kitchen
     * near SE15 5BS, distance-ordered and disclosed as proximity — not only the one kitchen whose
     * address contains that string.
     *
     * <p>The old ordering's real defect was that a customer could not tell the two behaviours
     * apart from the input, only from the header: {@code SE22} was a locality question because
     * nothing matched the string, while {@code SE15 5BS} was a text question because something
     * did. Identical-looking inputs, opposite readings, decided by data the customer cannot see.
     *
     * <p><em>What this costs, stated plainly:</em> a shop literally named "SE22 Kitchen" no longer
     * wins a search for {@code SE22} unless it also sits within the radius of the SE22 centroid.
     * That is the accepted trade, and it is the case that separates the two orderings.
     *
     * <h2>The text path is byte-identical, and cheap to reach</h2>
     *
     * <p>{@link PostcodeGeocoder#locateSearchTerm} applies its length bound and its anchored
     * shape test BEFORE any lookup, so a non-postcode-shaped term — every ordinary food search on
     * the platform — returns empty having issued <strong>zero</strong> queries. Running it first
     * therefore costs a regex, not a round trip, and the text tiers below are reached in exactly
     * the state they were reached in before.
     *
     * <p>A postcode-shaped term that names no row in {@code postcode_centroid} — {@code ZZ99 9ZZ},
     * and every Northern Ireland postcode, since Code-Point Open is GB-only — also falls through
     * here, exactly as it did under the old ordering. The table is the authority, not the regex,
     * so a shape match alone never produces a proximity claim.
     *
     * <h2>An empty proximity page is a real answer</h2>
     *
     * <p>A postcode that resolves but has no kitchen within the radius returns an EMPTY page
     * carrying a {@code PROXIMITY} interpretation — never a downgrade to {@code TEXT}, which
     * would tell the customer their postcode was not understood when in fact it was.
     *
     * <p>The radius is {@code jtoye.geo.default-radius-km}, the same platform default the
     * "near you" row already uses (D-C). No second radius, and therefore no second number to
     * keep in step.
     */
    public SearchOutcome searchPublishedShops(String query, Pageable pageable) {
        log.debug("Searching published shops: '{}'", query);

        // TIER 1 — INTERPRETATION FIRST (D-A, flipped at the 33-09 owner gate). Cheap for a
        // non-postcode term: the length bound and the anchored shape test both run before any
        // lookup, so an ordinary food search issues no query here at all.
        Optional<PostcodeGeocoder.LocatedPostcode> located = postcodeGeocoder.locateSearchTerm(query);
        if (located.isPresent()) {
            PostcodeGeocoder.LocatedPostcode postcode = located.get();
            // Kind and page only. The coordinate is derived from the customer's postcode and is
            // not logged here, for the same reason a device coordinate is not logged on the
            // lat/lon path.
            log.debug("Search interpreted as PROXIMITY at {} precision, page {} — "
                            + "coordinates deliberately not logged",
                    postcode.precision(), pageable.getPageNumber());

            return new SearchOutcome(
                    nearestPublished(postcode.coordinate().latitude(), postcode.coordinate().longitude(),
                            defaultRadiusKm, pageable),
                    SearchInterpretation.proximity(postcode.key(), postcode.precision(), defaultRadiusKm));
        }

        // TIER 2 + 3 — the pre-existing text path, unchanged. Reached by every non-postcode term
        // and by every postcode-shaped term the dataset does not know.
        // Use full-text search for ranked results; fall back to LIKE for short queries
        if (query != null && query.length() >= 2) {
            // Use unsorted Pageable for native queries — ts_rank handles ordering
            Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.unsorted());
            Page<Shop> results = shopRepository.fullTextSearchPublished(query, unsorted);
            if (results.hasContent()) {
                return new SearchOutcome(results.map(this::toPublicShopDto), SearchInterpretation.text());
            }
        }

        return new SearchOutcome(
                shopRepository.searchPublished(query, pageable).map(this::toPublicShopDto),
                SearchInterpretation.text());
    }

    /**
     * A page of shops together with the server's statement about how the query was read.
     *
     * <p>Nested rather than free-standing because it is this method's return shape and has no
     * other caller. {@code Page<PublicShopDto>} itself is deliberately unchanged — every existing
     * consumer of {@code GET /public/shops} still receives exactly the body it received before.
     */
    public record SearchOutcome(Page<PublicShopDto> page, SearchInterpretation interpretation) {
    }

    /**
     * Get a single published shop by slug.
     */
    public PublicShopDto getShopBySlug(String slug) {
        log.debug("Fetching published shop: {}", slug);
        Shop shop = shopRepository.findBySlugAndPublishedTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + slug));
        return toPublicShopDto(shop);
    }

    /**
     * Get available products for a published shop, grouped by category.
     * Sets TenantContext from the shop's tenant_id so RLS allows product queries.
     * Filters strictly to products assigned to this shop (UIX-05 — no tenant-wide
     * fallback; every product belongs to exactly one shop).
     */
    public Map<String, List<PublicProductDto>> getShopProducts(String slug) {
        log.debug("Fetching products for shop: {}", slug);

        // SEC-01 gate + TenantContext set atomically (Phase 13).
        Shop shop = resolvePublicShopForSlug(slug);
        try {
            // Filter: products assigned to THIS shop only (UIX-05 — the shop_id IS NULL
            // "tenant-wide" bleed was removed so a second shop shows its own menu).
            List<Product> products = productRepository.findAvailableByShopOrderedByCategory(shop.getId());

            // Group by category, preserving order; uncategorized items go under "Other"
            return products.stream()
                    .map(this::toPublicProductDto)
                    .collect(Collectors.groupingBy(
                            p -> p.getCategory() != null ? p.getCategory() : "Other",
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * List orders for a customer email, paginated (Issue #95 — the previous
     * unbounded list allowed a single unauthenticated request to pull a
     * customer's entire order history). Sets session variable for RLS policy;
     * both the page SELECT and the COUNT run in this same transaction, so the
     * transaction-local {@code app.customer_email} GUC covers both.
     */
    public Page<PublicOrderStatus> getCustomerOrders(String email, Pageable pageable) {
        log.debug("Fetching order history for {}", email);

        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.customer_email', ?, true)")) {
                stmt.setString(1, email);
                stmt.execute();
            }
        });

        Page<Order> orders = orderRepository.findByCustomerEmailOrderByCreatedAtDesc(email, pageable);

        return orders.map(order -> {
            String shopName = shopRepository.findById(order.getShopId())
                    .map(Shop::getName)
                    .orElse("Unknown shop");

            PublicOrderStatus status = new PublicOrderStatus();
            status.setOrderNumber(order.getOrderNumber());
            status.setStatus(order.getStatus().name());
            status.setPaymentStatus(order.getPaymentStatus() != null ? order.getPaymentStatus().name() : "NONE");
            status.setShopName(shopName);
            status.setSubtotalPennies(order.getSubtotalPennies());
            status.setVatRate(order.getVatRate() != null ? order.getVatRate().name() : "ZERO");
            status.setVatAmountPennies(order.getVatAmountPennies() != null ? order.getVatAmountPennies() : 0L);
            status.setTotalAmountPennies(order.getTotalAmountPennies());
            status.setItemCount(order.getItemCount() != null ? order.getItemCount() : 0);
            status.setCreatedAt(order.getCreatedAt());
            status.setUpdatedAt(order.getUpdatedAt());
            return status;
        });
    }

    /**
     * Track a guest order by order number + email verification.
     * Sets RLS session variables so the tracking policy allows the SELECT.
     */
    public PublicOrderStatus trackOrder(String orderNumber, String email) {
        log.debug("Tracking order {} with email {}", orderNumber, email);

        // Set session variables for the RLS tracking policy
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.tracking_order_number', ?, true)")) {
                stmt.setString(1, orderNumber);
                stmt.execute();
            }
            try (var stmt = connection.prepareStatement("SELECT set_config('app.tracking_email', ?, true)")) {
                stmt.setString(1, email);
                stmt.execute();
            }
        });

        Order order = orderRepository.findByOrderNumberAndCustomerEmail(orderNumber, email)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found. Check your order number and email address."));

        // Look up shop name
        String shopName = shopRepository.findById(order.getShopId())
                .map(Shop::getName)
                .orElse("Unknown shop");

        PublicOrderStatus status = new PublicOrderStatus();
        status.setOrderNumber(order.getOrderNumber());
        status.setStatus(order.getStatus().name());
        status.setPaymentStatus(order.getPaymentStatus() != null ? order.getPaymentStatus().name() : "NONE");
        status.setShopName(shopName);
        status.setTotalAmountPennies(order.getTotalAmountPennies());
        status.setItemCount(order.getItemCount() != null ? order.getItemCount() : 0);
        status.setCreatedAt(order.getCreatedAt());
        status.setUpdatedAt(order.getUpdatedAt());
        return status;
    }

    /**
     * Create a guest order for a published shop.
     *
     * <p>Card path: persists the order as DRAFT, <em>then</em> creates a Stripe
     * PaymentIntent against the now-identified row, persists the intent id as
     * the order's {@code paymentReference}, and returns the client secret. The
     * order transitions to PENDING only after successful payment via webhook.
     * The persist-then-pay ordering is load-bearing — see the block comment at
     * the Stripe call and issue #538.
     *
     * <p>COD path (no Stripe key): the order goes straight to PENDING with
     * {@code PaymentStatus.NONE} and no client secret.
     *
     * <p>Transactional contract: this method is the OUTERMOST transaction
     * boundary (the controller is not transactional), so any unchecked
     * exception it throws rolls back everything written here — including a
     * DRAFT order whose PaymentIntent creation subsequently failed.
     */
    @Transactional
    public GuestOrderConfirmation createGuestOrder(String slug, GuestOrderRequest request,
                                                   String headerIdempotencyKey) {
        log.debug("Creating guest order for shop: {}", slug);

        // SEC-01 tenant-match gate BEFORE any write (Phase 13) — rejects
        // cross-tenant spoof with 403 before Order/OrderItem rows are minted.
        Shop shop = resolvePublicShopForSlug(slug);
        try {
            // Enforce opening hours — reject orders when shop is closed
            validateShopIsOpen(shop);

            String idempotencyKey = resolveGuestIdempotencyKey(request.getIdempotencyKey(), headerIdempotencyKey);
            if (idempotencyKey == null) {
                // No key from either source: the pre-existing, non-idempotent create (census).
                return placeGuestOrder(shop, request, null);
            }

            // Cluster E (API-3 / API-4 / INT-15, adjudication A3): the keyed path goes through the
            // platform's V50 store in its credential-safe form. The reservation row is the
            // serialisation point (a concurrent same-key request waits on it and then REPLAYS,
            // or is refused with the typed 409 — never the raw idx_orders_idempotency violation),
            // the request hash is what turns "same key, different basket" into a 422, and the
            // response body is never persisted: GuestOrderConfirmation.clientSecret is a Stripe
            // credential, re-fetched live on replay (WR-02). The reservation joins THIS
            // transaction, so a failed create (Stripe outage, stock, validation) rolls the key
            // back with the order and a genuine retry succeeds.
            return idempotencyService.executeWithoutStoringResponse(
                    GUEST_ORDER_ENDPOINT, idempotencyKey, request,
                    () -> placeGuestOrder(shop, request, idempotencyKey),
                    () -> replayGuestOrder(shop, idempotencyKey)).value();
        } finally {
            // Owned HERE, at the outermost boundary, and deliberately NOT inside placeGuestOrder:
            // the store's completion UPDATE runs after the work returns and goes through
            // JdbcTemplate, whose TenantSetLocalAspect advice re-reads TenantContext. Cleared
            // any earlier, that advice would issue SET LOCAL app.current_tenant_id TO DEFAULT
            // first and, under FORCE RLS on the non-superuser runtime role, the stamp would
            // match zero rows — the reservation would stay "in-flight" and every retry a 409.
            // The Testcontainers superuser cannot show that; IdempotencyService asserts the
            // stamped row count so the runtime role would fail loudly rather than silently.
            TenantContext.clear();
        }
    }

    /**
     * Two-argument form: no {@code Idempotency-Key} header (existing callers and tests). The body
     * field, when present, is still honoured — see {@link #createGuestOrder(String, GuestOrderRequest, String)}.
     */
    @Transactional
    public GuestOrderConfirmation createGuestOrder(String slug, GuestOrderRequest request) {
        return createGuestOrder(slug, request, null);
    }

    /**
     * Which key identifies this order intent. The request-body {@code idempotencyKey} is the
     * convention the storefront has always used and stays AUTHORITATIVE; the platform's
     * {@code Idempotency-Key} header (API-3) is an ADDITIVE source consulted only when the body
     * carries none. Neither present ⇒ {@code null}, the keyless create.
     *
     * <p>Package-private for direct unit-test access within {@code uk.jtoye.core.storefront}.
     */
    static String resolveGuestIdempotencyKey(String bodyKey, String headerKey) {
        if (bodyKey != null && !bodyKey.isBlank()) {
            if (headerKey != null && !headerKey.isBlank() && !headerKey.equals(bodyKey)) {
                log.debug("Guest order carries both a body idempotencyKey and a differing Idempotency-Key header; the body value is authoritative");
            }
            return bodyKey;
        }
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey;
        }
        return null;
    }

    /**
     * The replay half of the credential-safe store: the reservation for {@code idempotencyKey} is
     * complete with a matching hash, so the confirmation is re-derived from the ORDER ROW rather
     * than read back from the store (which holds no body). The order was written in the same
     * transaction that completed the reservation, so its absence is an invariant violation, not
     * a case to create afresh — creating here would mint the very duplicate the key exists to stop.
     */
    private GuestOrderConfirmation replayGuestOrder(Shop shop, String idempotencyKey) {
        Order existingOrder = orderRepository.findByTenantIdAndIdempotencyKey(shop.getTenantId(), idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Completed idempotency reservation has no order row for the guest key"));
        log.info("Idempotent replay for key '{}', returning existing order {}",
                idempotencyKey, existingOrder.getOrderNumber());
        return replayConfirmation(shop, existingOrder);
    }

    /**
     * WR-02: the paymentReference is the Stripe PaymentIntent ID (pi_...), NOT a client secret —
     * returning it in the clientSecret slot mounted Stripe Elements with an unusable value AND
     * disclosed the raw PI id to the guest. For a still-payable DRAFT order, re-fetch the REAL
     * client secret from Stripe so the retry resumes payment; otherwise return null and the
     * client renders the placed-order confirmation. This live re-fetch is WHY the store never
     * persists this DTO (adjudication A3).
     */
    private GuestOrderConfirmation replayConfirmation(Shop shop, Order existingOrder) {
        String existingClientSecret = null;
        if (existingOrder.getStatus() == OrderStatus.DRAFT
                && existingOrder.getPaymentReference() != null
                && paymentService.isConfigured()) {
            try {
                existingClientSecret = paymentService.retrieveClientSecret(
                        existingOrder.getPaymentReference());
            } catch (com.stripe.exception.StripeException e) {
                log.warn("Could not re-fetch client secret for idempotent retry of order {}",
                        existingOrder.getOrderNumber(), e);
            }
        }
        return new GuestOrderConfirmation(
                existingOrder.getOrderNumber(),
                existingOrder.getStatus().name(),
                existingOrder.getSubtotalPennies(),
                existingOrder.getDeliveryFeePennies(),
                existingOrder.getVatRate().name(),
                existingOrder.getVatAmountPennies(),
                existingOrder.getTotalAmountPennies(),
                shop.getName(),
                existingOrder.getItemCount(),
                existingClientSecret,
                List.of()
        );
    }

    /**
     * The create itself — runs inside {@link #createGuestOrder(String, GuestOrderRequest, String)}'s
     * transaction, as the reserved WORK when a key is present and directly when none is.
     * Does NOT touch {@code TenantContext}: the caller owns set and clear.
     */
    private GuestOrderConfirmation placeGuestOrder(Shop shop, GuestOrderRequest request, String idempotencyKey) {
        UUID tenantId = shop.getTenantId();
        // Legacy lookup on orders.idempotency_key (V24), RETAINED inside the reserved work.
        // Two reasons: an order placed with a key BEFORE the V50 reservation existed has no
        // store row, so its retry lands here and must still replay rather than collide on
        // idx_orders_idempotency; and it is the arm the WR-02 unit tests exercise.
        if (idempotencyKey != null) {
            Optional<Order> existing = orderRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent duplicate detected for key '{}', returning existing order {}",
                        idempotencyKey, existing.get().getOrderNumber());
                return replayConfirmation(shop, existing.get());
            }
        }

        Order order = new Order();
        order.setTenantId(tenantId);
        order.setShopId(shop.getId());
        order.setOrderNumber(generateOrderNumber(tenantId));
        order.setStatus(OrderStatus.DRAFT);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setCustomerName(request.getCustomerName());
        order.setCustomerEmail(request.getCustomerEmail());
        order.setCustomerPhone(request.getCustomerPhone());
        order.setNotes(request.getNotes());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            order.setIdempotencyKey(idempotencyKey);
        }
        order.setUpdatedAt(OffsetDateTime.now());

        // Resolve fulfilment type server-side (UIX-04). The client sends the
        // enum string; an unknown value is a 400, not a silent DELIVERY.
        FulfilmentType fulfilmentType = parseFulfilmentType(request.getFulfilmentType());
        order.setFulfilmentType(fulfilmentType);
        if (fulfilmentType == FulfilmentType.DELIVERY) {
            // Conditional-required: a delivery order MUST carry a UK address.
            if (isBlank(request.getAddressLine1())
                    || isBlank(request.getAddressCity())
                    || isBlank(request.getAddressPostcode())) {
                throw new IllegalArgumentException(
                        "Delivery address (line 1, city and postcode) is required for delivery orders.");
            }
            order.setAddressLine1(request.getAddressLine1());
            order.setAddressLine2(request.getAddressLine2());
            order.setAddressCity(request.getAddressCity());
            order.setAddressPostcode(request.getAddressPostcode());
        }
        // COLLECTION: no address persisted; the delivery fee is forced to £0 below.

        // Add items with server-side price lookup.
        //
        // allergenWarnings stays on the confirmation DTO and is always empty as of
        // 2026-07-30: the customer-supplied allergen mask that populated it was
        // special-category data (Art. 9) taken over an unauthenticated endpoint with
        // no consent capture, and was removed. The field is retained as the seam a
        // future *consented* warning path plugs into — the checkout UI already guards
        // on length, so an empty list renders nothing. See
        // docs/legal/article-9-allergen-basis.md.
        List<String> allergenWarnings = new ArrayList<>();
        // Collect each line's VAT-inclusive gross + server-resolved rate so
        // the order's predominant liability can be computed (Issue #81 BUG 2).
        // The client cannot supply a rate (no rate field on the request) —
        // it is always resolved from product.vat_rate server-side.
        List<VatCalculator.LineRate> lineRates = new ArrayList<>();

        for (GuestOrderItemRequest itemReq : request.getItems()) {
            Product product = productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + itemReq.getProductId()));

            // UIX-05 invariant (CR-01): an order for shop X may only contain
            // shop X's products. RLS scopes findById to the TENANT, not the
            // shop, so without this check an unauthenticated client could
            // order any product of the tenant — including items quarantined
            // into the unpublished archive shop — through this storefront.
            // Deliberately the SAME exception type + message shape as the
            // absent-row case above so the response does not disclose that a
            // product exists in another shop (no title, no shop id).
            if (!shop.getId().equals(product.getShopId())) {
                throw new ResourceNotFoundException(
                        "Product not found: " + itemReq.getProductId());
            }

            if (!Boolean.TRUE.equals(product.getAvailable())) {
                throw new IllegalArgumentException("Product is not available: " + product.getTitle());
            }

            // Validate stock
            if (!product.hasStock(itemReq.getQuantity())) {
                throw new IllegalArgumentException(
                        "Insufficient stock for '" + product.getTitle() + "': requested "
                                + itemReq.getQuantity() + ", available " + product.getQuantityInStock());
            }

            OrderItem item = new OrderItem(
                    product.getId(),
                    itemReq.getQuantity(),
                    product.getPricePennies() // Server-side price — never trust client
            );
            item.setTenantId(tenantId);
            // UIX-03 root-cause fix: snapshot the REAL product title (server-side,
            // authoritative) so OrderItem.productName never persists its
            // "Unknown Product" default onto the kitchen display / order detail.
            item.setProductName(product.getTitle());
            // LGL-03 / V63: the allergen mask is snapshotted for the SAME reason the title
            // is, at the same moment. A vendor who edits a product's allergen data after this
            // order is placed must not be able to change what the customer is recorded as
            // having acknowledged, or what the kitchen ticket shows. Under a read-time join
            // back to Product they would: the customer acknowledges set A, the kitchen sees
            // set B, and no record of A survives anywhere. The advisory reconciliation flags
            // are stored beside the declaration, never folded into it.
            OrderAllergenSnapshot.capture(item, product.getTitle(),
                    product.getAllergenMask(), product.getIngredientsText());
            order.addItem(item);
            lineRates.add(new VatCalculator.LineRate(
                    item.getTotalPricePennies(), product.getVatRate()));
        }

        // Resolve the order's single predominant VAT rate from the basket
        // (replaces the former hardcoded STANDARD). Delivery VAT then follows
        // this predominant liability via calculateTotal().
        order.setVatRate(VatCalculator.predominantRate(lineRates));

        // Calculate delivery fee — server-authoritative (client value is
        // preview-only and NEVER read). COLLECTION always costs £0; DELIVERY
        // uses the shop's fee, waived when the subtotal clears the free-delivery
        // threshold. Tampering with fulfilmentType to underpay is neutralised
        // because the total is recomputed here, not taken from the request.
        long itemSubtotal = order.getItems().stream()
                .mapToLong(item -> item.getTotalPricePennies())
                .sum();

        // WR-01: enforce the shop's advertised minimum order value on the
        // item subtotal (delivery fee excluded), server-side. The storefront
        // renders "Min order £X" and the checkout disables submit below it,
        // but those are advisory — this is the authoritative gate.
        if (shop.getMinimumOrderPennies() != null && shop.getMinimumOrderPennies() > 0
                && itemSubtotal < shop.getMinimumOrderPennies()) {
            throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
                    "Order is below this shop's minimum order value of £%.2f.",
                    shop.getMinimumOrderPennies() / 100.0));
        }

        long deliveryFee;
        if (fulfilmentType == FulfilmentType.COLLECTION) {
            deliveryFee = 0L;
        } else {
            deliveryFee = shop.getDeliveryFeePennies() != null ? shop.getDeliveryFeePennies() : 0L;
            if (shop.getFreeDeliveryThresholdPennies() != null
                    && itemSubtotal >= shop.getFreeDeliveryThresholdPennies()) {
                deliveryFee = 0L;
            }
        }
        order.setDeliveryFeePennies(deliveryFee);

        order.calculateTotal();

        // If Stripe is configured, create PaymentIntent (order stays DRAFT until payment succeeds).
        // If not configured, fall back to COD — order goes straight to PENDING.
        String clientSecret = null;
        if (paymentService.isConfigured()) {
            // ORDERING (issue #538) — PERSIST BEFORE PAYING.
            //
            // createPaymentIntent stamps this order's UUID into the intent's
            // `order_id` metadata; that metadata is the ONLY link the
            // payment_intent.succeeded webhook has back to this row. So the
            // row must have an identity before Stripe is asked to reference
            // it. Creating the intent first dereferenced a null id and 500'd
            // every checkout on every Stripe-configured environment — a defect
            // that stayed invisible because no deployed stack sets a key, so
            // every one of them silently took the COD branch below.
            //
            // saveAndFlush, not save: the INSERT (and with it the partial
            // unique index on (tenant_id, idempotency_key) from V24) is
            // resolved against the database BEFORE we ask Stripe for money,
            // so a racing duplicate checkout is rejected by Postgres rather
            // than turning into a second PaymentIntent.
            order = orderRepository.saveAndFlush(order);
            try {
                PaymentIntentResult intent = paymentService.createPaymentIntent(order);
                clientSecret = intent.clientSecret();
                // Persist the Stripe object id (dirty-checked into this same
                // transaction). Two things depend on it: the WR-02 idempotent
                // retry above, which can only re-fetch a client secret when
                // paymentReference is set — it was NEVER set on this path
                // before, so a retried card checkout could never resume
                // payment — and reconciliation, which until now had no local
                // column tying an unpaid order to its Stripe intent.
                // The webhook later writes the same id (PaymentService
                // handlePaymentIntentSucceeded/Failed), so this is not a new
                // value, only an earlier one.
                order.setPaymentReference(intent.paymentIntentId());
            } catch (com.stripe.exception.StripeException e) {
                log.error("Failed to create PaymentIntent for order {}", order.getOrderNumber(), e);
                // DELIBERATE: this unchecked throw rolls the order back.
                //
                // createGuestOrder is @Transactional and PublicStorefrontController
                // is not, so this is the OUTERMOST transaction boundary and Spring's
                // default rollback-on-RuntimeException applies to the saveAndFlush
                // above. Keeping the DRAFT row would be strictly worse than losing
                // it: the customer has not been charged (intent creation failed), but
                // the row carries their idempotency key, so their retry would hit the
                // short-circuit at the top of this method and get that order back with
                // no client secret — an order they can never pay for and we can never
                // fulfil. Rolling back lets the retry mint a fresh order and a fresh
                // intent. Proven by GuestCheckoutOnlinePaymentIntegrationTest
                // .cardCheckout_failedPaymentIntent_rollsBackTheOrder.
                //
                // Asymmetric-failure caveat: if Stripe actually created the intent and
                // the failure was on the response leg, that intent is orphaned. It is
                // harmless — its client secret never reaches a browser, so it is never
                // confirmed, and it expires uncaptured. No money moves.
                throw new RuntimeException("Payment processing unavailable. Please try again later.");
            }
        } else {
            // COD fallback — no online payment. UNCHANGED by #538: this
            // branch still mutates in place and is persisted by the single
            // save below, exactly as before.
            order.setStatus(OrderStatus.PENDING);
            order.setPaymentStatus(PaymentStatus.NONE);
            order.setPaymentMethod("Cash on Delivery");
        }

        order = orderRepository.save(order);

        // Issue #85 [P1-3]: NO eager stock decrement here.
        // The former "Deduct stock" for-loop was a naked read-modify-write with
        // no @Version retry — it double-decremented (once here, once again at
        // CONFIRM via OrderService.transitionOrder -> StockService.decrementForOrder)
        // and surfaced concurrent-checkout contention as a customer-facing 500.
        // Stock is now decremented EXACTLY ONCE at the CONFIRMED transition
        // through the retry-safe StockService (CQ-01), matching the admin
        // OrderService.createOrder path and restoring cancel-path restock
        // symmetry (restore fires only for oldStatus >= CONFIRMED, which is now
        // where the decrement also lives). The read-only product.hasStock(...)
        // guard above stays as an early UX availability check — it is NOT a
        // reservation.

        // Publish event for COD orders (Stripe orders get event on webhook).
        // Issue #93: OrderEventPublisher is outbox-backed — the event row
        // joins THIS transaction and the flusher only publishes committed
        // rows, so the former afterCommit TransactionSynchronization
        // wrapper is no longer needed (and would run the outbox INSERT
        // outside the transaction it must join).
        if (clientSecret == null) {
            eventPublisher.publishStateChange(
                    order.getId(), order.getTenantId(), order.getShopId(), order.getOrderNumber(),
                    OrderStatus.DRAFT, OrderStatus.PENDING);
        }

        log.info("Created guest order {} with {} items, total: {} pennies (VAT: {} {}) for shop {}{}",
                order.getOrderNumber(), order.getItems().size(),
                order.getTotalAmountPennies(), order.getVatAmountPennies(),
                order.getVatRate(), shop.getName(),
                clientSecret != null ? " (awaiting payment)" : " (COD)");

        return new GuestOrderConfirmation(
                order.getOrderNumber(),
                order.getStatus().name(),
                order.getSubtotalPennies(),
                order.getDeliveryFeePennies(),
                order.getVatRate().name(),
                order.getVatAmountPennies(),
                order.getTotalAmountPennies(),
                shop.getName(),
                order.getItems().size(),
                clientSecret,
                allergenWarnings
        );
    }

    /**
     * Resolve a public shop by slug with an application-layer tenant-match gate (SEC-01).
     *
     * <p>Loads the shop via {@link ShopRepository#findBySlugAndPublishedTrue(String)}.
     * If an upstream TenantContext is present (populated by {@code JwtTenantFilter}
     * from a JWT {@code tenant_id} claim) and it differs from {@code shop.getTenantId()},
     * throws {@link TenantAccessDeniedException} (mapped to HTTP 403 by
     * {@code GlobalExceptionHandler.handleAccessDenied}). On the happy path,
     * sets {@code TenantContext} to the slug-derived tenant and returns the shop.
     *
     * <p>Per ASVS V4.1.5, the thrown exception message does NOT contain tenant
     * UUIDs; those appear only in the structured SLF4J WARN log emitted here
     * ({@code event=tenant_spoof_attempt ...}).
     *
     * <p>Caller contract: callers retain responsibility for their existing
     * {@code finally { TenantContext.clear(); }} blocks. This helper only
     * SETS on success and MUST NOT clear on any path.
     *
     * <p>Package-private (NOT {@code private}) for direct unit test access within
     * {@code uk.jtoye.core.storefront} — see
     * {@code PublicStorefrontServiceTest.resolvePublicShopForSlug_*}.
     *
     * @throws ResourceNotFoundException if the slug is unknown or the shop is unpublished
     * @throws TenantAccessDeniedException if an upstream tenant contradicts the slug tenant
     */
    Shop resolvePublicShopForSlug(String slug) {
        Shop shop = shopRepository.findBySlugAndPublishedTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + slug));

        Optional<UUID> upstreamTenant = TenantContext.get();
        if (upstreamTenant.isPresent() && !upstreamTenant.get().equals(shop.getTenantId())) {
            // Structured audit log — parseable by Loki/ELK, alertable via Alertmanager (Phase 9).
            // Tenant UUIDs are NOT leaked to the 403 response body (ASVS V4.1.5).
            log.warn("event=tenant_spoof_attempt slug={} slugTenant={} upstreamTenant={} outcome=403",
                    slug, shop.getTenantId(), upstreamTenant.get());
            throw new TenantAccessDeniedException(
                    "Tenant mismatch between authenticated identity and requested shop");
        }

        TenantContext.set(shop.getTenantId());
        return shop;
    }

    /**
     * Parse the client-supplied fulfilment string into a {@link FulfilmentType},
     * server-authoritatively. An absent value defaults to DELIVERY (the safe,
     * fee-bearing choice); an unknown value is rejected with a 400 rather than
     * silently coerced.
     */
    private static FulfilmentType parseFulfilmentType(String raw) {
        if (isBlank(raw)) {
            return FulfilmentType.DELIVERY;
        }
        try {
            return FulfilmentType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid fulfilment type: " + raw
                    + " (expected DELIVERY or COLLECTION)");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String generateOrderNumber(UUID tenantId) {
        String tenantPrefix = tenantId.toString().replace("-", "").substring(0, 8).toUpperCase();
        String datePart = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return String.format("ORD-%s-%s-%s", tenantPrefix, datePart, randomSuffix);
    }

    private PublicShopDto toPublicShopDto(Shop shop) {
        PublicShopDto dto = new PublicShopDto();
        dto.setSlug(shop.getSlug());
        dto.setName(shop.getName());
        dto.setDescription(shop.getDescription());
        dto.setAddress(shop.getAddress());
        dto.setLogoUrl(shop.getLogoUrl());
        dto.setBannerUrl(shop.getBannerUrl());
        dto.setPhone(shop.getPhone());
        dto.setEmail(shop.getEmail());
        dto.setLatitude(shop.getLatitude());
        dto.setLongitude(shop.getLongitude());
        dto.setOpeningHours(shop.getOpeningHours());
        dto.setDeliveryInfo(shop.getDeliveryInfo());
        dto.setMinimumOrderPennies(shop.getMinimumOrderPennies());
        dto.setDeliveryFeePennies(shop.getDeliveryFeePennies());
        dto.setFreeDeliveryThresholdPennies(shop.getFreeDeliveryThresholdPennies());
        dto.setTags(shop.getTags());
        // QA-council FIX-6 (M3): disclose the payment mode BEFORE order
        // commit. Mirrors the exact gate createGuestOrder uses to decide
        // card-intent vs COD (paymentService.isConfigured()).
        dto.setAcceptsCardPayments(paymentService.isConfigured());
        return dto;
    }

    // ALLERGEN_NAMES / describeAllergens removed 2026-07-30 with the customer allergen
    // mask they formatted — dead once the Art. 9 intake was withdrawn. The 14-allergen
    // name list still lives on the frontend (ALLERGENS in frontend/types/api.ts) for
    // PRODUCT allergen display, which is product data, not personal data.

    private static final Pattern HOURS_PATTERN = Pattern.compile("(\\d{2}):(\\d{2})\\s*-\\s*(\\d{2}):(\\d{2})");
    private static final Map<DayOfWeek, String> DAY_KEYS = Map.of(
            DayOfWeek.MONDAY, "mon", DayOfWeek.TUESDAY, "tue", DayOfWeek.WEDNESDAY, "wed",
            DayOfWeek.THURSDAY, "thu", DayOfWeek.FRIDAY, "fri", DayOfWeek.SATURDAY, "sat",
            DayOfWeek.SUNDAY, "sun"
    );

    private static final ZoneId UK_ZONE = ZoneId.of("Europe/London");

    private void validateShopIsOpen(Shop shop) {
        Map<String, String> hours = shop.getOpeningHours();
        if (hours == null || hours.isEmpty()) {
            // No hours configured = always open
            return;
        }

        // Use UK timezone explicitly — opening hours are UK local times
        String dayKey = DAY_KEYS.get(LocalDate.now(UK_ZONE).getDayOfWeek());
        String todayHours = hours.get(dayKey);
        if (todayHours == null || todayHours.equalsIgnoreCase("closed")) {
            throw new IllegalArgumentException(
                    shop.getName() + " is closed today. Please check opening hours and try again later.");
        }

        Matcher m = HOURS_PATTERN.matcher(todayHours);
        if (!m.find()) {
            // Unparseable hours format — allow the order (fail open)
            return;
        }

        LocalTime open = LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        LocalTime close = LocalTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
        LocalTime now = LocalTime.now(UK_ZONE);

        // WR-06: an overnight window ("18:00 - 02:00", close < open — normal
        // for a takeaway) wraps past midnight. The old predicate
        // (now.isBefore(open) || !now.isBefore(close)) rejected EVERY time of
        // day for such windows, refusing orders during real trading hours.
        boolean overnight = close.isBefore(open);
        boolean openNow = overnight
                ? !now.isBefore(open) || now.isBefore(close)
                : !now.isBefore(open) && now.isBefore(close);
        if (!openNow) {
            throw new IllegalArgumentException(
                    shop.getName() + " is currently closed. Opening hours today: " + todayHours + ". Please try again later.");
        }
    }

    private PublicProductDto toPublicProductDto(Product product) {
        PublicProductDto dto = new PublicProductDto();
        dto.setId(product.getId());
        dto.setTitle(product.getTitle());
        dto.setDescription(product.getDescription());
        dto.setImageUrl(product.getImageUrl());
        dto.setIngredientsText(product.getIngredientsText());
        dto.setAllergenMask(product.getAllergenMask());
        dto.setPricePennies(product.getPricePennies());
        dto.setCategory(product.getCategory());
        dto.setDietaryTags(product.getDietaryTags());
        dto.setPreparationTimeMinutes(product.getPreparationTimeMinutes());
        dto.setFeatured(product.getFeatured());
        dto.setInStock(product.hasStock());

        // Build combined image URLs list: primary first, then additional
        List<String> allImages = new ArrayList<>();
        if (product.getImageUrl() != null && !product.getImageUrl().isBlank()) {
            allImages.add(product.getImageUrl());
        }
        if (product.getAdditionalImageUrls() != null) {
            allImages.addAll(product.getAdditionalImageUrls());
        }
        dto.setImageUrls(allImages);

        return dto;
    }
}
