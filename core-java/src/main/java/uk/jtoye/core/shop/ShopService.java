package uk.jtoye.core.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.exception.InvalidOpeningScheduleException;
import uk.jtoye.core.exception.InvalidRequestFieldsException;
import uk.jtoye.core.exception.PublishStateNotAcceptedException;
import uk.jtoye.core.exception.ReservedSlugException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.ShopSlugTakenException;
import uk.jtoye.core.geo.PostcodeGeocoder;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.PauseOrdersRequest;
import uk.jtoye.core.shop.dto.ShopDto;
import uk.jtoye.core.storage.StorageService;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class ShopService {
    private static final Logger log = LoggerFactory.getLogger(ShopService.class);

    private final ShopRepository shopRepository;
    private final ShopMapper shopMapper;
    private final StorageService storageService;
    private final TenantCacheEvictor cacheEvictor;
    private final ShopAccessService shopAccessService;
    private final ShopCacheLoader shopCacheLoader;
    private final PostcodeGeocoder postcodeGeocoder;
    private final Clock clock;

    /**
     * Slugs that must never reach the {@code slug} column, because the storefront
     * serves a STATIC route at {@code /shop/<segment>} and Next.js resolves a static
     * segment before the dynamic {@code [slug]} one — so such a shop would be
     * permanently unreachable at its own URL with nothing to say why.
     *
     * <p>Configured rather than hardcoded because the list is a function of the
     * frontend route table, which changes as routes are added. Spring binds the
     * comma-separated value to this set; the default names every static segment that
     * exists under {@code frontend/app/shop/} today.
     *
     * <p>Compared case-insensitively after trimming — see {@link #assertSlugNotReserved}.
     *
     * <p>{@code account} (31.1-26, #838) is the signed-in customer's data-rights page. It must be in
     * this default AND in application.yml; ShopReservedSlugAccountTest reads both, and also walks
     * {@code frontend/app/shop/} so a new static route that is not reserved here turns it red.
     */
    @Value("${jtoye.shop.reserved-slugs:account,auth,orders,signin}")
    private Set<String> reservedSlugs;

    // WR-03 (phase-33 code review): the bounding box a CLIENT-SUPPLIED fallback
    // coordinate must lie inside. Approximate on purpose — it is a containment
    // bound, not a border: it covers all of Great Britain AND Northern Ireland
    // (the legitimate population the fallback exists for, since Code-Point Open
    // is GB-only and an NI postcode never geocodes). It applies ONLY to
    // precedence rule 2 (the client fallback); a geocoded centroid is by
    // construction inside it, and a persisted coordinate (rule 3) is never
    // retro-rejected. See applyCoordinate for why this exists.
    private static final double UK_MIN_LATITUDE = 49.8;
    private static final double UK_MAX_LATITUDE = 60.9;
    private static final double UK_MIN_LONGITUDE = -8.7;
    private static final double UK_MAX_LONGITUDE = 1.8;

    public ShopService(ShopRepository shopRepository,
                       ShopMapper shopMapper,
                       StorageService storageService,
                       TenantCacheEvictor cacheEvictor,
                       ShopAccessService shopAccessService,
                       ShopCacheLoader shopCacheLoader,
                       PostcodeGeocoder postcodeGeocoder,
                       Clock clock) {
        this.shopRepository = shopRepository;
        this.shopMapper = shopMapper;
        this.storageService = storageService;
        this.cacheEvictor = cacheEvictor;
        this.shopAccessService = shopAccessService;
        this.shopCacheLoader = shopCacheLoader;
        this.postcodeGeocoder = postcodeGeocoder;
        this.clock = clock;
    }

    // createShop inserts a brand-new entity; no existing cache key could match it,
    // so no eviction is required. (Previously @CacheEvict(allEntries=true) blew
    // away every tenant's cache on a single tenant's write.)
    public ShopDto createShop(CreateShopRequest request) {
        // VSA-02 (D-02): shop create is a group-wide action — GROUP_ADMIN only
        // (realm-admin bridges to GROUP_ADMIN). Deny-by-default for scoped users.
        shopAccessService.requireGroupAdmin();

        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating shop '{}' for tenant {}", request.getName(), tenantId);
        OpeningSchedule schedule = validatedSchedule(request);

        Shop shop = shopMapper.toEntity(request);
        shop.setTenantId(tenantId);
        // 37-20 (D-15/D-25): a new shop saved with hours is STRUCTURED; one saved without hours stays
        // unclassified (NULL), which takes no storefront orders until hours are saved. Never LEGACY_ALWAYS_OPEN.
        applySchedule(shop, schedule);

        // Auto-generate slug from name if not provided. A generated slug always carries
        // a random 8-char suffix, so it can never collide with a static storefront
        // segment; only an explicitly-supplied one can, hence the guard on that branch.
        if (shop.getSlug() == null || shop.getSlug().isBlank()) {
            shop.setSlug(generateSlug(request.getName()));
        } else {
            assertSlugNotReserved(shop.getSlug());
            // 37-48 (UXT-028): a typed 409, not the generic duplicate from idx_shops_slug.
            if (shopRepository.existsBySlug(shop.getSlug())) {
                throw new ShopSlugTakenException(shop.getSlug());
            }
        }

        // Sole-writer invariant (threat T-18-05-T): a brand-new shop is NEVER born
        // published. ShopMapper.toEntity copies CreateShopRequest.published onto the
        // entity, so a request body {"published": true} would otherwise create an
        // already-live shop that bypassed the onboarding GO_LIVE gate. Force false
        // unconditionally here — the ONLY path that may set published=true is
        // ShopService.setPublished, reached from the guarded GO_LIVE side effect in
        // VendorOnboardingService.
        shop.setPublished(false);
        if (shop.getMinimumOrderPennies() == null) {
            shop.setMinimumOrderPennies(0L);
        }
        // 37-48 (UXT-023): the mapper copies an absent request field as null over the entity defaults. A new shop
        // charges no fee and takes both types (what every shop did before V83) unless the request says otherwise.
        if (shop.getDeliveryFeePennies() == null) {
            shop.setDeliveryFeePennies(0L);
        }
        if (shop.getOffersDelivery() == null) {
            shop.setOffersDelivery(true);
        }
        if (shop.getOffersCollection() == null) {
            shop.setOffersCollection(true);
        }
        requireSomeFulfilmentOffered(shop);

        // #460 link 3a: derive the coordinate from the address BEFORE the row is written,
        // so a shop is never born invisible to every distance query. On create there is no
        // prior coordinate to preserve, hence the two nulls.
        applyCoordinate(shop, request, null, null);

        shop = saveTranslatingSlugConflict(shop);

        log.info("Created shop {} with ID {} for tenant {}", shop.getName(), shop.getId(), tenantId);

        return shopMapper.toDto(shop);
    }

    // BE-03 completion: scope the authenticated by-id read to the caller's tenant.
    // findById is RLS-only and shops_public_read permits published shops, so a
    // tenant could otherwise fetch another tenant's PUBLISHED shop by direct id.
    //
    // CR-01 (Phase 23-10): the shop-access gate runs HERE, on EVERY call, OUTSIDE
    // the @Cacheable boundary. Caching now lives on the separate ShopCacheLoader bean
    // (below), so the authorization decision is re-evaluated even on a cache HIT — a
    // shop cached by one authorized user is NOT served to a different, out-of-grant
    // user in the same tenant (TenantAwareCacheKeyGenerator keys by tenant only, with
    // no user component). Previously the gate lived inside the @Cacheable method body,
    // so a cache hit short-circuited it and poisoned the entry for every tenant user.
    // Do NOT re-inline @Cacheable onto this method: it reintroduces the bypass.
    @Transactional(readOnly = true)
    public Optional<ShopDto> getShopById(UUID shopId) {
        // VSA-02 (D-02 / D-13): a direct-hit by-id read requires at least STAFF on
        // the shop. A cross-shop direct hit yields the typed shop 403 (distinct from
        // the RLS 404). Runs before the cached load on every invocation.
        shopAccessService.require(shopId, ShopRole.STAFF);
        return shopCacheLoader.getShopById(shopId);
    }

    // QA-council BE-03: scope the authenticated "my shops" list to the caller's
    // tenant. Relying on RLS alone leaked other tenants' PUBLISHED shops here,
    // because the shops_public_read policy (V16) OR-permits published=true.
    @Transactional(readOnly = true)
    public Page<ShopDto> getAllShops(Pageable pageable) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        log.debug("Fetching shops for tenant {} with pagination: page {}, size {}",
                tenantId, pageable.getPageNumber(), pageable.getPageSize());
        // VSA-02 (D-01): read-scope to the caller's grant set at the QUERY. A
        // GROUP_ADMIN keeps the full tenant list; a scoped user sees only granted
        // shops (server-side narrowing); a fully-ungranted user sees nothing.
        if (shopAccessService.isGroupAdmin()) {
            return shopRepository.findByTenantId(tenantId, pageable)
                    .map(shopMapper::toDto);
        }
        Set<UUID> granted = shopAccessService.grantedShopIds();
        if (granted.isEmpty()) {
            return Page.empty(pageable);
        }
        return shopRepository.findByTenantIdAndIdIn(tenantId, granted, pageable)
                .map(shopMapper::toDto);
    }

    // QA-council BE-03: scope authenticated shop search to the caller's tenant
    // (same published-shop leak as getAllShops via the unscoped search query).
    @Transactional(readOnly = true)
    public List<ShopDto> search(String query) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        log.debug("Searching shops for tenant {} with query: {}", tenantId, query);
        // VSA-02 (D-01): read-scope search results to the caller's grant set at the
        // QUERY (not a post-hoc filter), mirroring getAllShops.
        if (shopAccessService.isGroupAdmin()) {
            return shopRepository.searchByTenant(tenantId, query).stream()
                    .map(shopMapper::toDto)
                    .toList();
        }
        Set<UUID> granted = shopAccessService.grantedShopIds();
        if (granted.isEmpty()) {
            return List.of();
        }
        return shopRepository.searchByTenantAndIdIn(tenantId, granted, query).stream()
                .map(shopMapper::toDto)
                .toList();
    }

    public ShopDto updateShop(UUID shopId, CreateShopRequest request) {
        // VSA-02 (D-02): shop update requires SHOP_MANAGER on the shop (path id).
        shopAccessService.require(shopId, ShopRole.SHOP_MANAGER);
        log.debug("Updating shop {}", shopId);

        // QA-council M3: scope the write to the caller's tenant. findById is
        // RLS-only and shops_public_read (V16) permits published shops, so a
        // cross-tenant PUT otherwise loaded another tenant's PUBLISHED shop, then
        // failed the FORCE-RLS write with a StaleStateException surfaced as 500.
        // findByIdAndTenantId (as getShopById already uses) yields a clean 404.
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        Shop shop = shopRepository.findByIdAndTenantId(shopId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));
        // 37-20: refuse a bad schedule before any field is copied, so a 400 changes nothing.
        OpeningSchedule schedule = validatedSchedule(request);

        // Sole-writer invariant (threat T-18-05-T): updateShop must NOT let a request
        // change `published`. ShopMapper.updateEntity (IGNORE-null strategy) copies a
        // non-null CreateShopRequest.published onto the entity, so a PUT
        // {"published": true} could flip a shop live outside the guarded GO_LIVE
        // transition. Snapshot the current value and restore it after the field copy,
        // so `published` stays owned solely by ShopService.setPublished (reached only
        // from the onboarding GO_LIVE/SUSPEND/REINSTATE side effect).
        Boolean publishedBeforeUpdate = shop.getPublished();
        // 37-48 (UXT-028): the slug before the field copy, which the mapper may overwrite with a blank.
        String slugBeforeUpdate = shop.getSlug();

        // #460 link 3a: snapshot the PERSISTED coordinate before the field copy. It is the
        // value applyCoordinate falls back to when a re-geocode misses — an address edit
        // that lands on an unknown postcode must not silently delete a coordinate the shop
        // already had, which would remove a live storefront from every distance result.
        Double latitudeBeforeUpdate = shop.getLatitude();
        Double longitudeBeforeUpdate = shop.getLongitude();

        // Issue #450 item 4 (F-L6-PUBLISHDROP / INT-06): the restore below keeps the
        // OUTCOME right, but on its own it made the RESPONSE a lie — {"published":true}
        // and {"published":false} both returned 200 with the shop unchanged, so a client
        // could not tell its instruction had been refused. Say so instead, with a typed
        // RFC 7807 409 (agent-readiness: machine-parseable errors, stable code).
        //
        // Only a request that asks to CHANGE the state is refused. `published` is a
        // nullable Boolean on both sides (N4), so both are normalised through
        // Boolean.TRUE.equals before comparison: null and false are the same state.
        // A request that omits the field, or echoes the state the shop is already in,
        // is a no-op and still succeeds — which is what the vendor shop-edit form sends
        // on every ordinary save, and what must keep working.
        if (request.getPublished() != null) {
            boolean requested = Boolean.TRUE.equals(request.getPublished());
            boolean current = Boolean.TRUE.equals(publishedBeforeUpdate);
            if (requested != current) {
                log.warn("Rejected publish-state change on shop {} via updateShop: requested={}, current={} "
                        + "(onboarding state machine is the sole writer)", shopId, requested, current);
                throw new PublishStateNotAcceptedException(requested, current);
            }
        }

        shopMapper.updateEntity(request, shop);
        // 37-48 (UXT-023): the mapper ignores nulls (BE-02), so an absent threshold keeps the stored one. An
        // EXPLICIT null is the vendor clearing it ("no free-delivery threshold"), and only that clears it.
        if (request.isFreeDeliveryThresholdPenniesSent() && request.getFreeDeliveryThresholdPennies() == null) {
            shop.setFreeDeliveryThresholdPennies(null);
        }
        // A single false merged with the OTHER flag already false offers nothing: refused before anything is saved.
        requireSomeFulfilmentOffered(shop);
        // 37-20 (D-15/D-25): a sent schedule replaces the stored one and makes the hours STRUCTURED (this ends
        // the LEGACY_ALWAYS_OPEN and NEEDS_REVIEW states); an absent one keeps both. The mapper never copies
        // the schedule, its source or the pause from a request.
        applySchedule(shop, schedule);
        // Defence in depth: the guard above already refuses any state-changing value, so
        // this restore is now a no-op on every path that reaches it. It stays because it
        // is the invariant's last line — if the mapper strategy or the guard is ever
        // loosened, `published` still cannot be written from a request body here.
        shop.setPublished(publishedBeforeUpdate);

        // 37-48 (RWO-028 / UXT-028, owner ruling 2026-10-10): the slug is the shop's public web address, printed
        // on menus and QR codes and shared as links, so an edit KEEPS it. Until 37-48 this block said "regenerate
        // slug if name changed", but it regenerated UNCONDITIONALLY: every save without a slug (an hours save, a
        // phone number) ran generateSlug(name) with a fresh random suffix, whether or not the name changed, and
        // broke every link to the shop. Now an absent, blank or unchanged slug keeps the stored one, and only an
        // explicit, different slug is validated (shape by bean validation; reserved route; held by another shop)
        // and applied. The mapper has already copied the request slug, so the kept value is restored explicitly.
        // Old-slug 301 aliases are deferred to Phase 39 (same ruling).
        //
        // The kept slug goes back on the entity BEFORE the taken check: the check is a query, and Hibernate
        // auto-flushes the managed shop before running it, so a requested slug left on the entity would hit
        // idx_shops_slug inside existsBySlugAndIdNot and surface as the generic duplicate (measured).
        String requestedSlug = request.getSlug();
        shop.setSlug(slugBeforeUpdate);
        if (requestedSlug != null && !requestedSlug.isBlank() && !requestedSlug.equals(slugBeforeUpdate)) {
            assertSlugNotReserved(requestedSlug);
            if (shopRepository.existsBySlugAndIdNot(requestedSlug, shopId)) {
                throw new ShopSlugTakenException(requestedSlug);
            }
            shop.setSlug(requestedSlug);
        }

        // Re-geocode from the FINAL address (the mapper has already applied any address
        // change). Unconditional rather than gated on "did the address change?": the same
        // call then also repairs a shop whose postcode was absent from the reference table
        // when it was created and has since been added, at the cost of one primary-key
        // lookup per update. `published` is NOT touched here — the sole-writer invariant
        // (threat T-18-05-T) is unaffected by this change; the only fields written are
        // latitude and longitude.
        applyCoordinate(shop, request, latitudeBeforeUpdate, longitudeBeforeUpdate);

        shop = saveTranslatingSlugConflict(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

        log.info("Updated shop {} with ID {}", shop.getName(), shop.getId());

        return shopMapper.toDto(shop);
    }

    public ShopDto uploadLogo(UUID shopId, MultipartFile file) {
        shopAccessService.require(shopId, ShopRole.SHOP_MANAGER);  // VSA-02 (D-02): media write = SHOP_MANAGER
        Shop shop = shopRepository.findByIdAndTenantId(shopId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        storageService.delete(shop.getLogoUrl());

        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        String url = storageService.uploadNamed(tenantId, "shops", shopId, "logo", file);
        shop.setLogoUrl(url);
        shop = shopRepository.saveAndFlush(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

        log.info("Uploaded logo for shop {}", shopId);
        return shopMapper.toDto(shop);
    }

    public ShopDto removeLogo(UUID shopId) {
        shopAccessService.require(shopId, ShopRole.SHOP_MANAGER);  // VSA-02 (D-02): media write = SHOP_MANAGER
        Shop shop = shopRepository.findByIdAndTenantId(shopId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        storageService.delete(shop.getLogoUrl());
        shop.setLogoUrl(null);
        shop = shopRepository.saveAndFlush(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

        log.info("Removed logo for shop {}", shopId);
        return shopMapper.toDto(shop);
    }

    public ShopDto uploadBanner(UUID shopId, MultipartFile file) {
        shopAccessService.require(shopId, ShopRole.SHOP_MANAGER);  // VSA-02 (D-02): media write = SHOP_MANAGER
        Shop shop = shopRepository.findByIdAndTenantId(shopId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        storageService.delete(shop.getBannerUrl());

        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        String url = storageService.uploadNamed(tenantId, "shops", shopId, "banner", file);
        shop.setBannerUrl(url);
        shop = shopRepository.saveAndFlush(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

        log.info("Uploaded banner for shop {}", shopId);
        return shopMapper.toDto(shop);
    }

    public ShopDto removeBanner(UUID shopId) {
        shopAccessService.require(shopId, ShopRole.SHOP_MANAGER);  // VSA-02 (D-02): media write = SHOP_MANAGER
        Shop shop = shopRepository.findByIdAndTenantId(shopId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        storageService.delete(shop.getBannerUrl());
        shop.setBannerUrl(null);
        shop = shopRepository.saveAndFlush(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

        log.info("Removed banner for shop {}", shopId);
        return shopMapper.toDto(shop);
    }

    /**
     * Set a shop's {@code published} flag. This is the SINGLE mutation point for
     * {@code published} and is reached ONLY from the vendor-onboarding state
     * machine's side effects (GO_LIVE → true, SUSPEND → false, REINSTATE → true)
     * in {@code VendorOnboardingService}. The onboarding state machine is the
     * <strong>sole authorised writer of {@code published=true}</strong>: no
     * controller or DTO path may flip a storefront live outside the guarded
     * GO_LIVE transition (threat T-18-02-T).
     *
     * <p>N4: {@code Shop.published} is a nullable {@code Boolean}; the primitive
     * {@code boolean} parameter autoboxes cleanly on {@code setPublished(published)},
     * so the entity field stays {@code Boolean} (no primitive migration).
     */
    public void setPublished(UUID shopId, boolean published) {
        Shop shop = shopRepository.findByIdAndTenantId(shopId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));
        shop.setPublished(published);
        shopRepository.saveAndFlush(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);
        log.info("Shop {} published flag set to {}", shopId, published);
    }

    /**
     * The request's opening schedule, validated (37-20, D-15), or {@code null} when the request carries none
     * (absent = keep). Every problem is collected per day ({@code openingSchedule.<day>}) and refused together
     * as a 400, before anything is written: an unknown day key, a window with no times, more than
     * {@link OpeningSchedule#MAX_WINDOWS_PER_DAY} windows on a day, and overlapping windows (within a day, or
     * an overnight window running into the next day's hours).
     */
    private static OpeningSchedule validatedSchedule(CreateShopRequest request) {
        Map<String, List<OpeningSchedule.TimeWindow>> requested = request.getOpeningSchedule();
        if (requested == null) {
            return null;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        for (Map.Entry<String, List<OpeningSchedule.TimeWindow>> day : requested.entrySet()) {
            String key = day.getKey();
            if (!OpeningSchedule.DAY_KEYS.contains(key)) {
                errors.put("openingSchedule." + key, key + " is not a day: use mon, tue, wed, thu, fri, sat or sun");
            } else if (day.getValue() != null && day.getValue().stream().anyMatch(java.util.Objects::isNull)) {
                errors.put("openingSchedule." + key, key + ": a window needs an open and a close time");
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidOpeningScheduleException(errors);
        }
        OpeningSchedule schedule = OpeningSchedule.fromJson(requested);
        for (String error : schedule.validationErrors()) {
            // validationErrors() starts every message with the day key it is about ("wed: ...").
            String key = error.substring(0, error.indexOf(':'));
            errors.merge("openingSchedule." + key, error, (first, next) -> first + "; " + next);
        }
        if (!errors.isEmpty()) {
            throw new InvalidOpeningScheduleException(errors);
        }
        return schedule;
    }

    /** Writes a validated schedule as the vendor's own (STRUCTURED); {@code null} keeps what is stored. */
    private static void applySchedule(Shop shop, OpeningSchedule schedule) {
        if (schedule == null) {
            return;
        }
        shop.setOpeningSchedule(schedule.toJson());
        shop.setHoursSource(HoursSource.STRUCTURED);
    }

    /**
     * Pause storefront orders (Phase 37-20, D-15): for 20 or 40 minutes from now, or until the vendor
     * reopens. Gated on STAFF of the shop, because the kitchen is who knows it is overwhelmed (T-37-51: an
     * ungranted user, or staff of another shop, is refused 403).
     *
     * <p>ABSOLUTE, never cumulative: the two pause columns are set outright, so a repeat leaves the same state
     * (a repeated timed pause re-arms from the repeat) and a retry cannot stack pauses. Only the storefront
     * checkout reads the pause; vendor, REST and MCP orders are not blocked (a phone order taken by staff is
     * legitimate), and MCP gets no pause tool (operational kitchen control stays human-in-the-loop).
     */
    public ShopDto pauseOrders(UUID shopId, PauseOrdersRequest request) {
        shopAccessService.require(shopId, ShopRole.STAFF);
        if (request == null || !request.isOneValidChoice()) {
            // The controller's @Valid refuses this first; this keeps a non-HTTP caller to the same contract.
            throw new IllegalArgumentException("send minutes 20 or 40, or untilReopened true, and not both");
        }
        Shop shop = shopRepository.findByIdAndTenantId(shopId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));
        if (request.isIndefinite()) {
            shop.setOrdersPausedIndefinitely(true);
            shop.setOrdersPausedUntil(null);
        } else {
            shop.setOrdersPausedIndefinitely(false);
            // Whole seconds: PostgreSQL keeps microseconds, so an untruncated value would make the response
            // (nanoseconds, from memory) differ from every later read of the stored pause end.
            shop.setOrdersPausedUntil(OffsetDateTime.now(clock).plusMinutes(request.getMinutes())
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        }
        shop = shopRepository.saveAndFlush(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);
        log.info("Paused storefront orders for shop {}: {}", shopId,
                request.isIndefinite() ? "until reopened" : "until " + shop.getOrdersPausedUntil());
        return shopMapper.toDto(shop);
    }

    /** End any pause on the shop (D-15 "Resume orders"). Absolute: resuming an unpaused shop is a no-op. */
    public ShopDto resumeOrders(UUID shopId) {
        shopAccessService.require(shopId, ShopRole.STAFF);
        Shop shop = shopRepository.findByIdAndTenantId(shopId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));
        shop.setOrdersPausedIndefinitely(false);
        shop.setOrdersPausedUntil(null);
        shop = shopRepository.saveAndFlush(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);
        log.info("Resumed storefront orders for shop {}", shopId);
        return shopMapper.toDto(shop);
    }

    public void deleteShop(UUID shopId) {
        // VSA-02 (D-02): shop delete is a group-wide action — GROUP_ADMIN only.
        shopAccessService.requireGroupAdmin();
        log.debug("Deleting shop {}", shopId);

        // QA-council M3: scope the delete to the caller's tenant (see updateShop).
        // A cross-tenant DELETE otherwise loaded another tenant's published shop
        // then failed the FORCE-RLS delete with a StaleStateException → 500.
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        Shop shop = shopRepository.findByIdAndTenantId(shopId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        // Clean up images from storage
        storageService.delete(shop.getLogoUrl());
        storageService.delete(shop.getBannerUrl());

        shopRepository.delete(shop);
        cacheEvictor.evictEntity("shops", "getShopById", shopId);

        log.info("Deleted shop {} with ID {}", shop.getName(), shop.getId());
    }

    /**
     * Decide the shop's coordinate. #460 link 3a, and the ONE place on the API write path
     * where that decision is made.
     *
     * <h2>THE PRECEDENCE RULE — stated so nobody has to infer it from the code</h2>
     *
     * <p><strong>The postcode is authoritative; a client-supplied coordinate is a FALLBACK,
     * never an override.</strong> In order:
     *
     * <ol>
     *   <li>If the address yields a postcode present in {@code postcode_centroid}, that
     *       centroid WINS and overwrites whatever the client sent.</li>
     *   <li>If it does not, the client's own {@code latitude}/{@code longitude} stand —
     *       PROVIDED the pair lies inside the UK bounding box. Range validation
     *       (±90/±180) bounds absurdity, not abuse: a geocode miss is under the vendor's
     *       control (append a suffix the end-anchored extractor cannot see, or use a
     *       well-formed non-existent unit), so an unbounded fallback let any
     *       {@code SHOP_MANAGER} place their shop at an arbitrary point on Earth and rank
     *       as "nearest kitchen" on the anonymous discovery surface (review WR-03). A
     *       pair outside the box is DISCARDED (falling through to rule 3) with a
     *       {@code event=client_coordinate_rejected} WARN; an accepted fallback leaves an
     *       {@code event=client_coordinate_accepted} WARN naming the shop, so operator
     *       review of unverified vendor-supplied positions is possible. The box includes
     *       Northern Ireland — the legitimate population this fallback exists for.
     *       Placement abuse WITHIN the UK box remains possible (central London from
     *       Belfast) and is the accepted residual: closing it needs the
     *       {@code coordinate_source} column / verified-override feature named below.</li>
     *   <li>If there is neither, the previously PERSISTED coordinate stands (update path).</li>
     *   <li>If there is none of the three, the coordinate stays {@code null}. Never
     *       {@code (0,0)}: Null Island is nearer the origin than any real GB shop, so a shop
     *       there becomes the nearest kitchen to every customer on the platform.</li>
     * </ol>
     *
     * <p>RESEARCH recommended this over the alternative ("accept the client's coordinate
     * whenever it is in range") because the platform's stated accuracy tolerance is the
     * postcode centroid (~100 m, decision D-1) and a vendor-typed coordinate has no
     * provenance at all — a dropped minus sign puts a Peckham kitchen in the North Sea and
     * nothing in the product can tell. What the code did BEFORE this change was the worst of
     * both: it silently accepted anything and geocoded nothing.
     *
     * <p>The deliberate trade-off: a vendor cannot pin a door-level coordinate more precise
     * than their postcode centroid while their postcode resolves. That is accepted for now —
     * D-1 already scopes this platform to centroid accuracy — and is a separate feature
     * (a verified vendor-supplied override), not an accident of this method.
     *
     * @param shop            the entity, already carrying the FINAL address and whatever the
     *                        client sent for latitude/longitude
     * @param request         the request as the client sent it — consulted so a LONE axis can
     *                        be told apart from a full pair, which the merged entity cannot
     *                        reveal once the IGNORE-null mapper has run (WR-02)
     * @param persistedLatitude  the coordinate held before this write, or {@code null} on create
     * @param persistedLongitude the coordinate held before this write, or {@code null} on create
     */
    private void applyCoordinate(Shop shop, CreateShopRequest request,
                                 Double persistedLatitude, Double persistedLongitude) {
        Optional<PostcodeGeocoder.Coordinate> located = postcodeGeocoder.locate(shop.getAddress());

        if (located.isPresent()) {
            shop.setLatitude(located.get().latitude());
            shop.setLongitude(located.get().longitude());
            return;
        }

        // A miss is normal, not an error: Northern Ireland is permanently absent (Code-Point
        // Open is GB-only), and so is any postcode that does not exist. The shop keeps its
        // storefront either way — it is simply absent from distance-ranked results. Log the
        // SLUG only: PostcodeGeocoder has already logged the extracted postcode, and the rest
        // of the address line is vendor-identifying detail that does not belong in a log
        // (threat T-33-05-04).
        log.warn("Shop '{}' could not be geocoded from its address — coordinates left as-is. "
                        + "The shop stays published and reachable, but is absent from distance-ranked "
                        + "results until its postcode resolves.",
                shop.getSlug());

        // WR-02 (phase-33 code review): a single axis is not a coordinate — never merge a
        // client half with a persisted half. The DTO's pairing constraint (coordinatePaired)
        // refuses this at the HTTP boundary; this is the service's own last line for any
        // caller that arrives unvalidated. It must consult the REQUEST, not the entity: on
        // update the IGNORE-null mapper has already copied the lone half onto the entity
        // next to the persisted other half, so the merged entity always looks like a full
        // pair. Reset the whole pair to the persisted state (null/null on create) so the
        // decision falls through to precedence rule 3 as if no client coordinate existed.
        if ((request.getLatitude() == null) != (request.getLongitude() == null)) {
            shop.setLatitude(persistedLatitude);
            shop.setLongitude(persistedLongitude);
        }

        // WR-03 (phase-33 code review): precedence rule 2 — the client's own pair may
        // stand, but only inside the UK bounding box, and never silently. See the
        // precedence docblock above for the threat (a vendor-forced geocode miss is a
        // self-placement vector in public ranking) and the accepted residual. The
        // check reads the REQUEST for the same reason as the pairing guard: after the
        // mapper, the entity cannot say whose pair it is holding.
        if (request.getLatitude() != null && request.getLongitude() != null) {
            boolean withinUk = request.getLatitude() >= UK_MIN_LATITUDE
                    && request.getLatitude() <= UK_MAX_LATITUDE
                    && request.getLongitude() >= UK_MIN_LONGITUDE
                    && request.getLongitude() <= UK_MAX_LONGITUDE;
            if (withinUk) {
                log.warn("event=client_coordinate_accepted shop='{}' latitude={} longitude={} — "
                                + "the address did not geocode and the client-supplied fallback was "
                                + "accepted. This is an UNVERIFIED vendor-supplied position on the "
                                + "public ranking surface; review if disputed.",
                        shop.getSlug(), request.getLatitude(), request.getLongitude());
            } else {
                // UF-33-01: integer-degree coarsening (~111 km). The rejected pair never
                // becomes public and could be a residential position, so the trace says
                // roughly where it pointed without fixing an address.
                log.warn("event=client_coordinate_rejected shop='{}' latitude~{} longitude~{} — "
                                + "the client-supplied fallback lies outside the UK bounding box and "
                                + "was discarded; the persisted coordinate (or none) stands. Values "
                                + "coarsened to integer degrees.",
                        shop.getSlug(), Math.round(request.getLatitude()),
                        Math.round(request.getLongitude()));
                shop.setLatitude(persistedLatitude);
                shop.setLongitude(persistedLongitude);
            }
        }

        // Never let a miss delete a coordinate the row already had.
        if (shop.getLatitude() == null && persistedLatitude != null) {
            shop.setLatitude(persistedLatitude);
        }
        if (shop.getLongitude() == null && persistedLongitude != null) {
            shop.setLongitude(persistedLongitude);
        }
    }

    // QA-council M3 (extended): the caller's tenant, required. Used to scope shop
    // writes so a cross-tenant request 404s BEFORE any side effect (e.g. a Blob
    // object delete) runs against another tenant's shop.
    private UUID requireTenantId() {
        return TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
    }

    /**
     * Reject a vendor-supplied slug that collides with a STATIC storefront route
     * segment. See {@link ReservedSlugException} for why this is a reachability bug
     * rather than a naming preference.
     *
     * <p>Compared case-insensitively against a trimmed value, because the collision is
     * decided by the URL path Next.js matches, and that match does not care how the
     * vendor capitalised or padded the field.
     */
    private void assertSlugNotReserved(String slug) {
        if (slug == null || reservedSlugs == null || reservedSlugs.isEmpty()) {
            return;
        }
        String candidate = slug.trim().toLowerCase();
        for (String reserved : reservedSlugs) {
            if (reserved != null && candidate.equals(reserved.trim().toLowerCase())) {
                throw new ReservedSlugException(
                        "Shop slug '" + slug + "' is reserved by a storefront route and would make the shop "
                                + "unreachable at /shop/" + candidate + ". Choose a different slug, or leave it "
                                + "blank to have one generated.");
            }
        }
    }

    /**
     * 37-48 (UXT-023): a shop must offer at least one fulfilment type, judged on the MERGED state (the request's
     * flags over the stored ones), so a partial update cannot leave a shop that takes no order. A typed 400 under
     * the same {@code fulfilmentOffered} key the request-level rule uses; the database's ck_shops_offers_some is
     * the last line.
     */
    private static void requireSomeFulfilmentOffered(Shop shop) {
        if (Boolean.FALSE.equals(shop.getOffersDelivery()) && Boolean.FALSE.equals(shop.getOffersCollection())) {
            throw new InvalidRequestFieldsException(
                    Map.of("fulfilmentOffered", CreateShopRequest.FULFILMENT_OFFERED_MESSAGE));
        }
    }

    /**
     * 37-48 (UXT-028): save and flush, reporting a unique violation on {@code idx_shops_slug} as the typed 409
     * {@code shop-slug-taken}. The pre-checks above cannot see another tenant's UNPUBLISHED shop (RLS), so the
     * index is the authority; without this a collision there was the generic {@code errors/duplicate}. Any
     * other integrity violation propagates unchanged to its existing mapping.
     */
    private Shop saveTranslatingSlugConflict(Shop shop) {
        try {
            return shopRepository.saveAndFlush(shop);
        } catch (DataIntegrityViolationException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t.getMessage() != null && t.getMessage().contains("idx_shops_slug")) {
                    throw new ShopSlugTakenException(shop.getSlug());
                }
            }
            throw e;
        }
    }

    private String generateSlug(String name) {
        String base = name.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        // Append short random suffix for uniqueness
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return base + "-" + suffix;
    }

    /**
     * Cached by-id shop loader (Phase 23-10, CR-01). Extracted onto its OWN Spring
     * bean so the {@code @Cacheable} boundary is separated from the authorization
     * gate: {@link ShopService#getShopById} runs {@code shopAccessService.require(...)}
     * on every call and only THEN delegates here for the (cached) data load. Because
     * this is a distinct bean, the call from {@code ShopService} crosses the Spring
     * proxy and the caching interceptor actually fires — this is deliberately NOT a
     * self-invocation (which would silently bypass the proxy and disable caching,
     * WR-01). The cached method keeps the name {@code getShopById} so the tenant-aware
     * cache key ({@code tenant:{tid}:getShopById:{shopId}}) and every existing
     * {@code TenantCacheEvictor.evictEntity("shops", "getShopById", id)} eviction stay
     * byte-for-byte unchanged (caching is relocated, never deleted — the performance
     * good is preserved).
     *
     * <p>This loader holds NO authorization: callers MUST gate BEFORE delegating here,
     * so a cache hit can never short-circuit the shop-access decision (CR-01).
     */
    @Component
    public static class ShopCacheLoader {
        private static final Logger loaderLog = LoggerFactory.getLogger(ShopCacheLoader.class);

        private final ShopRepository shopRepository;
        private final ShopMapper shopMapper;

        public ShopCacheLoader(ShopRepository shopRepository, ShopMapper shopMapper) {
            this.shopRepository = shopRepository;
            this.shopMapper = shopMapper;
        }

        /**
         * {@code unless = "#result == null"} IS correct here and DOES suppress the negative
         * entry: Spring unwraps the {@code Optional} before evaluating {@code unless}, so
         * {@code #result} is the {@code ShopDto} on a hit and {@code null} on a miss. Do NOT
         * rewrite it as {@code !#result.isPresent()} — that throws
         * {@code SpelEvaluationException EL1004E} on every successful lookup. See the fuller
         * note on {@code ProductService.ProductCacheLoader#getProductById} (issue #484);
         * both directions are pinned by {@code NegativeCachingOptionalEmptyTest}.
         */
        @Transactional(readOnly = true)
        @Cacheable(value = "shops", keyGenerator = "tenantAwareCacheKeyGenerator", unless = "#result == null")
        public Optional<ShopDto> getShopById(UUID shopId) {
            UUID tenantId = TenantContext.get()
                    .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
            loaderLog.debug("Fetching shop {} for tenant {}", shopId, tenantId);
            return shopRepository.findByIdAndTenantId(shopId, tenantId)
                    .map(shopMapper::toDto);
        }
    }
}
