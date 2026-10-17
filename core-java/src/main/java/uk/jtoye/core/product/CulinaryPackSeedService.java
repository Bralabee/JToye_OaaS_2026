package uk.jtoye.core.product;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.CulinaryDishTemplateDto;
import uk.jtoye.core.product.dto.CulinaryPackDto;
import uk.jtoye.core.product.dto.CulinaryPackSeedRequest;
import uk.jtoye.core.product.dto.CulinaryPackSeedResult;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.ShopRepository;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 1-Click Pan-African &amp; Caribbean Culinary Knowledge Base seeder (GTM-BLK-02).
 *
 * <p>Loads the pre-audited 52-dish / 10-cuisine catalogue from
 * {@code classpath:/seed/oaas_culinary_catalog.json} and seeds selected cuisine packs or dish
 * slugs into a target shop through the canonical {@link ProductService#createProduct} pipeline.
 *
 * <p><strong>Statutory &amp; FBO Responsibility Invariant (FIR 2014 Reg. 5 &amp; Reg. 8):</strong>
 * Seeded dishes are created as <b>drafts</b> ({@code available = false}) by default so the Food
 * Business Operator (FBO) inspects, adjusts, and confirms their exact kitchen recipe and price
 * before publishing to the storefront. Every template carries a pre-verified 14-bit
 * {@code allergenMask}, {@code mayContainMask}, inline {@code **bold**} allergen emphasis tokens,
 * and recommended refrigerated {@code shelfLifeDays} ({@code USE_BY}) for Natasha's Law PPDS labels.
 */
@Service
@Transactional
public class CulinaryPackSeedService {
    private static final Logger log = LoggerFactory.getLogger(CulinaryPackSeedService.class);
    private static final String CATALOG_RESOURCE = "/seed/oaas_culinary_catalog.json";
    private static final long DEFAULT_MAIN_PRICE_PENNIES = 950L;
    private static final long DEFAULT_SECONDARY_PRICE_PENNIES = 550L;

    private final ProductService productService;
    private final ProductRepository productRepository;
    private final ShopRepository shopRepository;
    private final ShopAccessService shopAccessService;
    private final List<CulinaryPackDto> packs;
    private final Map<String, CulinaryDishTemplateDto> dishesBySlug;

    public CulinaryPackSeedService(
            ProductService productService,
            ProductRepository productRepository,
            ShopRepository shopRepository,
            ShopAccessService shopAccessService,
            JsonMapper jsonMapper) {
        this.productService = productService;
        this.productRepository = productRepository;
        this.shopRepository = shopRepository;
        this.shopAccessService = shopAccessService;

        CatalogRoot root = loadCatalog(jsonMapper);
        Map<String, List<CulinaryDishTemplateDto>> grouped = new LinkedHashMap<>();
        Map<String, CulinaryDishTemplateDto> slugMap = new LinkedHashMap<>();

        for (RawDish raw : root.dishes()) {
            CulinaryDishTemplateDto dto = new CulinaryDishTemplateDto(
                    raw.slug(),
                    raw.name(),
                    raw.cuisineKey(),
                    raw.cuisineLabel(),
                    raw.region(),
                    raw.course(),
                    raw.description(),
                    raw.shelfLifeDays() > 0 ? raw.shelfLifeDays() : 3,
                    raw.allergenMask(),
                    raw.allergenNames() != null ? List.copyOf(raw.allergenNames()) : List.of(),
                    raw.mayContainMask(),
                    raw.mayContainNames() != null ? List.copyOf(raw.mayContainNames()) : List.of(),
                    raw.ingredientsText(),
                    raw.dietaryTags() != null ? List.copyOf(raw.dietaryTags()) : List.of()
            );
            grouped.computeIfAbsent(dto.cuisineKey(), k -> new ArrayList<>()).add(dto);
            slugMap.put(dto.slug().toLowerCase(Locale.ROOT), dto);
        }

        List<CulinaryPackDto> builtPacks = new ArrayList<>();
        for (Map.Entry<String, List<CulinaryDishTemplateDto>> entry : grouped.entrySet()) {
            List<CulinaryDishTemplateDto> list = List.copyOf(entry.getValue());
            CulinaryDishTemplateDto first = list.get(0);
            builtPacks.add(new CulinaryPackDto(
                    first.cuisineKey(),
                    first.cuisineLabel(),
                    first.region(),
                    list.size(),
                    list));
        }

        this.packs = List.copyOf(builtPacks);
        this.dishesBySlug = Map.copyOf(slugMap);
    }

    /**
     * List all available Pan-African and Caribbean culinary packs and their dish templates.
     */
    @Transactional(readOnly = true)
    public List<CulinaryPackDto> listPacks() {
        return packs;
    }

    /**
     * Idempotently seed a shop's product catalogue from selected cuisine packs or dish slugs.
     *
     * <p>Requires {@code SHOP_MANAGER} on {@code request.shopId()}. Locks the target shop row
     * ({@code SELECT ... FOR UPDATE}) before reading existing SKUs and titles so concurrent seed
     * requests serialize cleanly and skip already-created dishes.
     */
    public CulinaryPackSeedResult seedPack(CulinaryPackSeedRequest request) {
        UUID shopId = request.shopId();
        Set<String> requestedCuisines = normalizeSet(request.cuisineKeys());
        Set<String> requestedSlugs = normalizeSet(request.dishSlugs());
        if (requestedCuisines.isEmpty() && requestedSlugs.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one of cuisineKeys or dishSlugs must be provided to seed a culinary pack");
        }

        shopAccessService.require(shopId, ShopRole.SHOP_MANAGER);
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        shopRepository.lockByIdAndTenantId(shopId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        Set<String> existingSkus = new HashSet<>();
        Set<String> existingTitles = new HashSet<>();
        for (Product existing : productRepository
                .findByTenantIdAndShopIdOrderByDisplayOrderAscCreatedAtAsc(tenantId, shopId)) {
            existingSkus.add(existing.getSku());
            existingTitles.add(titleKey(existing.getTitle()));
        }

        List<CulinaryDishTemplateDto> selected = new ArrayList<>();
        Set<String> selectedSlugs = new LinkedHashSet<>();

        for (CulinaryPackDto pack : packs) {
            if (requestedCuisines.contains(pack.cuisineKey().toLowerCase(Locale.ROOT))) {
                for (CulinaryDishTemplateDto dish : pack.dishes()) {
                    if (selectedSlugs.add(dish.slug().toLowerCase(Locale.ROOT))) {
                        selected.add(dish);
                    }
                }
            }
        }

        List<CulinaryPackSeedResult.Item> items = new ArrayList<>();
        for (String slug : requestedSlugs) {
            CulinaryDishTemplateDto dish = dishesBySlug.get(slug);
            if (dish == null) {
                items.add(new CulinaryPackSeedResult.Item(
                        slug, slug, CulinaryPackSeedResult.Outcome.FAILED, null,
                        "Unknown dish template slug: " + slug));
                continue;
            }
            if (selectedSlugs.add(dish.slug().toLowerCase(Locale.ROOT))) {
                selected.add(dish);
            }
        }

        if (selected.isEmpty() && items.isEmpty()) {
            throw new IllegalArgumentException(
                    "No matching cuisineKeys or dishSlugs found in the Culinary Knowledge Base");
        }

        boolean markAvailable = Boolean.TRUE.equals(request.available());
        int orderIndex = existingTitles.size();

        for (CulinaryDishTemplateDto dish : selected) {
            String sku = seedSku(dish.slug(), shopId);
            String normalizedTitle = titleKey(dish.name());
            if (existingSkus.contains(sku) || existingTitles.contains(normalizedTitle)) {
                items.add(new CulinaryPackSeedResult.Item(
                        dish.slug(), dish.name(), CulinaryPackSeedResult.Outcome.SKIPPED, null,
                        "The target shop already has this dish"));
                continue;
            }
            if (productRepository.existsByTenantIdAndSku(tenantId, sku)) {
                items.add(new CulinaryPackSeedResult.Item(
                        dish.slug(), dish.name(), CulinaryPackSeedResult.Outcome.FAILED, null,
                        "SKU " + sku + " is already used by another product"));
                continue;
            }

            CreateProductRequest createReq = new CreateProductRequest();
            createReq.setSku(sku);
            createReq.setTitle(dish.name());
            createReq.setIngredientsText(dish.ingredientsText());
            createReq.setAllergenMask(dish.allergenMask());
            createReq.setMayContainMask(dish.mayContainMask());
            createReq.setPricePennies(resolvePricePennies(dish.course(), request.defaultPricePennies()));
            createReq.setVatRate(VatRate.STANDARD);
            createReq.setDescription(dish.description());
            createReq.setCategory(formatCategory(dish.course(), dish.cuisineLabel()));
            createReq.setDisplayOrder(orderIndex++);
            createReq.setAvailable(markAvailable);
            createReq.setFeatured(false);
            createReq.setDietaryTags(dish.dietaryTags().isEmpty() ? null : String.join(", ", dish.dietaryTags()));
            createReq.setShopId(shopId);
            createReq.setShelfLifeDays(dish.shelfLifeDays());
            createReq.setDurabilityType("USE_BY");

            UUID createdId = productService.createProduct(createReq).getId();
            existingSkus.add(sku);
            existingTitles.add(normalizedTitle);
            items.add(new CulinaryPackSeedResult.Item(
                    dish.slug(), dish.name(), CulinaryPackSeedResult.Outcome.CREATED, createdId, null));
        }

        CulinaryPackSeedResult result = CulinaryPackSeedResult.of(items);
        log.info("Seeded culinary pack into shop {}: {} created, {} skipped, {} failed",
                shopId, result.created(), result.skipped(), result.failed());
        return result;
    }

    static String seedSku(String dishSlug, UUID shopId) {
        String prefix = "CKB-" + dishSlug.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]+", "-");
        String suffix = "-" + shopId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String base = prefix.length() + suffix.length() > 100
                ? prefix.substring(0, 100 - suffix.length())
                : prefix;
        return base + suffix;
    }

    private static long resolvePricePennies(String course, Long overridePennies) {
        if (overridePennies != null && overridePennies > 0) {
            return overridePennies;
        }
        if ("main".equalsIgnoreCase(course) || "soup-stew".equalsIgnoreCase(course)) {
            return DEFAULT_MAIN_PRICE_PENNIES;
        }
        return DEFAULT_SECONDARY_PRICE_PENNIES;
    }

    private static String formatCategory(String course, String cuisineLabel) {
        if (course == null || course.isBlank()) {
            return cuisineLabel;
        }
        return switch (course.toLowerCase(Locale.ROOT)) {
            case "main" -> "Mains";
            case "soup-stew" -> "Soups & Stews";
            case "street-food" -> "Street Food & Small Chops";
            case "side" -> "Sides";
            case "snack" -> "Snacks & Pastries";
            case "dessert" -> "Desserts";
            case "breakfast" -> "Breakfast";
            case "drink" -> "Drinks";
            default -> cuisineLabel;
        };
    }

    private static String titleKey(String title) {
        return title == null ? "" : title.strip().toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalizeSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.strip().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static CatalogRoot loadCatalog(JsonMapper jsonMapper) {
        try (InputStream in = CulinaryPackSeedService.class.getResourceAsStream(CATALOG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled culinary catalog resource not found: " + CATALOG_RESOURCE);
            }
            return jsonMapper.readValue(in, CatalogRoot.class);
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("Failed to parse bundled culinary catalog: " + CATALOG_RESOURCE, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CatalogRoot(List<RawDish> dishes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawDish(
            String slug,
            String name,
            String cuisineKey,
            String cuisineLabel,
            String region,
            String course,
            String description,
            int shelfLifeDays,
            int allergenMask,
            List<String> allergenNames,
            int mayContainMask,
            List<String> mayContainNames,
            String ingredientsText,
            List<String> dietaryTags) {
    }
}
