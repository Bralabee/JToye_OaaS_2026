package uk.jtoye.core.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.customer.Customer;
import uk.jtoye.core.customer.CustomerRepository;
import uk.jtoye.core.onboarding.OnboardingState;
import uk.jtoye.core.onboarding.VendorOnboardingRepository;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.StorageService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reproducible dev/demo data seeder (UIX-05, phase 19).
 *
 * <p>Replaces the ad-hoc, hand-created rows in the shared dev Postgres volume
 * with a committed, idempotent, realistic multi-shop dataset. It exists because
 * every shop under a tenant used to render the same menu — 24 of 25 seeded
 * products carried a NULL {@code shop_id} and the storefront query matched
 * {@code (shop_id = :shopId OR shop_id IS NULL)}, so unassigned products bled
 * into (and duplicated across) every shop. 19-02 dropped the {@code IS NULL}
 * bleed and scoped the query strictly to the shop; this seeder makes the live
 * dev volume match that contract.
 *
 * <p><strong>Curated + pristine (UIX-05 / UI-SPEC Surface G #15):</strong> the
 * three demo shops are the ONLY published storefronts and each shows exactly its
 * own curated menu — realistic UK names, plausible prices, {@code featured}
 * "Popular" items and Halal/dietary tags — with <em>no duplicate line items</em>
 * and no placeholder junk ("Label Cake 057999", "Validation Shop"). To hold that
 * invariant against a dev volume that accumulated orphaned rows from years of E2E
 * runs, the seeder actively <em>repairs</em> on every startup:
 * <ul>
 *   <li>{@link #upsertShop}/{@link #upsertProduct} UPDATE existing rows (not just
 *       create) so curated shops/products are re-homed to the right shop and
 *       enriched (tags, logo, featured, dietary) even when they already exist;</li>
 *   <li>{@link #quarantineNonCurated} moves every non-curated product (legacy
 *       orphans, NULL-shop rows, mis-aligned junk) into a single UNPUBLISHED
 *       "Unsorted legacy items" archive shop, so no duplicate/placeholder row
 *       ever renders in a published storefront;</li>
 *   <li>{@link #unpublishNonCurated} un-publishes every non-curated shop so the
 *       directory shows exactly the three curated demo shops — EXCEPT a shop the
 *       Phase-18 onboarding state machine currently holds LIVE (WR-10): the
 *       machine is the sole authorised writer of {@code Shop.published} and the
 *       sweep must never undo a real onboarding go-live on restart.</li>
 * </ul>
 * Nothing is deleted (order_items reference products via snapshot + id), so this
 * is safe against the live dev volume.
 *
 * <p><strong>Product photography (#15 — reversed 260713-kds):</strong> curated
 * products now carry seeded, license-verified dish imagery. Each of the 21
 * bundled Wikimedia photos (CC0/CC-BY/CC-BY-SA, zero NC/ND — attributed in
 * {@code docs/CREDITS-demo-images.md}) is uploaded to MinIO via
 * {@link uk.jtoye.core.storage.StorageService#putSeedImage} at a deterministic
 * {@code <tenant>/products/seed/<filename>} key and its public URL stamped onto
 * the matching product's {@code image_url} by {@link #seedProductImages}. A
 * seeder-owns overwrite policy fills null/blank slots, re-affirms prior seed
 * URLs and replaces foreign/legacy URLs, but NEVER clobbers a genuine vendor
 * upload — a URL under the product's OWN upload folder
 * ({@code <publicUrl>/<tenant>/products/<thisProductId>/}) lacking the
 * {@code /products/seed/} marker. (A vendor upload always keys on the product's
 * own id, so a URL under a DIFFERENT entity id is a foreign/legacy artifact the
 * seeder owns.) SafeImage remains the fallback only when no seed image maps to a
 * product. Shop <em>branding</em> (a logo) is also seeded.
 *
 * <p><strong>Profile gating:</strong> restricted to the {@code dev} Spring
 * profile and only wired as an {@link ApplicationRunner} at dev startup.
 * Testcontainers integration tests boot under {@code test} so this bean is never
 * instantiated (no fixture/golden-file perturbation); prod never runs it. It is
 * deliberately NOT a Flyway migration for the same reason.
 *
 * <p><strong>Multi-tenancy:</strong> all writes are scoped to the default demo
 * tenant via {@link TenantContext}; {@code TenantSetLocalAspect} applies the RLS
 * GUC to every repository op inside the transaction (matching the
 * {@code ScheduledCleanupService} pattern — {@link TransactionTemplate} avoids the
 * self-invocation proxy trap that would run the seed with a NULL tenant).
 */
@Component
@Profile("dev")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** Default demo tenant seeded by V13 — matches the dev Keycloak tenant claim. */
    private static final UUID DEMO_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Second demo tenant (also seeded by V13; matches the tenant-b-user Keycloak
     * tenant claim). Given a single probe shop + product below so the #203 / AI-1
     * cross-tenant RLS proof can assert DISJOINT NON-EMPTY sets — token B sees the
     * probe product-id, token A does NOT — instead of a doubly-explained "empty for B".
     */
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** Distinct slug/SKU for tenant B's probe fixture (no collision with tenant-A rows). */
    private static final String TENANT_B_SHOP_SLUG = "tenant-b-probe";
    private static final String TENANT_B_PRODUCT_SKU = "TENANTB-PROBE-1";

    /** Slug of the hidden archive shop that absorbs every non-curated product. */
    private static final String ARCHIVE_SLUG = "unsorted-legacy-items";

    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final VendorOnboardingRepository onboardingRepository;
    private final StorageService storageService;
    private final TransactionTemplate transactionTemplate;

    public DemoDataSeeder(ShopRepository shopRepository,
                          ProductRepository productRepository,
                          CustomerRepository customerRepository,
                          VendorOnboardingRepository onboardingRepository,
                          StorageService storageService,
                          PlatformTransactionManager transactionManager) {
        this.shopRepository = shopRepository;
        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
        this.onboardingRepository = onboardingRepository;
        this.storageService = storageService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // FSA 14-allergen bit positions — MUST match the shared mask convention
    // (PublicStorefrontService.ALLERGEN_NAMES index i == bit i) used by the
    // storefront allergen list, the dashboard allergen column and the
    // customer allergen-warning check (QA-council FIX-7 / M4).
    private static final int A_GLUTEN = 1;            // bit 0
    private static final int A_CRUSTACEANS = 1 << 1;  // crayfish, prawns
    private static final int A_EGGS = 1 << 2;
    private static final int A_FISH = 1 << 3;
    private static final int A_PEANUTS = 1 << 4;      // yaji/suya spice
    private static final int A_MILK = 1 << 6;
    private static final int A_CELERY = 1 << 8;       // stock bases
    private static final int A_MUSTARD = 1 << 9;      // mayonnaise

    /**
     * A single curated menu item, incl. "Popular" (featured), dietary metadata
     * and PPDS allergen data (QA-council FIX-7 / M4): the FSA 14-allergen
     * {@code allergenMask} plus {@code **…**} markup on allergen ingredients
     * (the vendor markup convention {@code IngredientMarkupParser} parses into
     * label-emphasis spans — Natasha's Law requires allergens emboldened
     * INLINE in the ingredients list).
     */
    private record MenuItem(String sku, String title, String category, long pricePennies,
                            String ingredients, boolean featured, String dietaryTags,
                            int allergenMask) {}

    @Override
    public void run(ApplicationArguments args) {
        // TenantContext is set BEFORE the transaction so TenantSetLocalAspect
        // applies the correct RLS GUC to every repository op inside it.
        TenantContext.set(DEMO_TENANT);
        try {
            SeedResult result = transactionTemplate.execute(status -> seed());
            if (result != null) {
                log.info("DemoDataSeeder complete for tenant {}: {} shop(s) created, "
                                + "{} product(s) created, {} customer(s) created, "
                                + "{} non-curated product(s) quarantined, {} shop(s) unpublished, "
                                + "{} demo image(s) seeded.",
                        DEMO_TENANT, result.shopsCreated, result.productsCreated,
                        result.customersCreated, result.productsQuarantined, result.shopsUnpublished,
                        result.imagesSeeded);
            }
        } finally {
            TenantContext.clear();
        }

        // Tenant-B probe fixture (#203 / AI-1). A SEPARATE TenantContext.set(TENANT_B)
        // pins the RLS GUC to tenant B for these writes (mirrors the tenant-A block
        // above) so the row's tenant_id matches the RLS WITH CHECK. Its own
        // transaction — independent of the tenant-A seed — keeps the two tenants'
        // writes cleanly isolated.
        TenantContext.set(TENANT_B);
        try {
            transactionTemplate.execute(status -> {
                seedTenantB();
                return null;
            });
            log.info("DemoDataSeeder tenant-B probe seeded");
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Seed tenant B (…0002) with exactly ONE shop + ONE product — a minimal probe
     * fixture (NOT a full catalogue) whose sole purpose is to make the #203 / AI-1
     * cross-tenant RLS proof bidirectional and unfakeable: token B must see this
     * product-id while token A must NOT. Idempotent (upsert by slug / SKU) so
     * repeated dev boots never duplicate. Dev-only ({@code @Profile("dev")}); the
     * tenant-B rows are synthetic, PII-free and never seeded in staging/prod
     * (threat T-20-08 accepted).
     */
    private void seedTenantB() {
        Shop shop = shopRepository.findBySlug(TENANT_B_SHOP_SLUG).orElseGet(() -> {
            Shop s = new Shop();
            s.setTenantId(TENANT_B);
            s.setSlug(TENANT_B_SHOP_SLUG);
            return s;
        });
        shop.setName("Tenant B Probe Kitchen");
        shop.setAddress("1 Probe Lane, London");
        shop.setDescription("Cross-tenant RLS probe fixture for tenant B (dev only).");
        shop.setMinimumOrderPennies(0L);
        shop.setDeliveryFeePennies(0L);
        shop.setPublished(false);
        Shop savedShop = shopRepository.save(shop);

        Product product = productRepository.findBySku(TENANT_B_PRODUCT_SKU).orElseGet(() -> {
            Product p = new Product();
            p.setTenantId(TENANT_B);
            p.setSku(TENANT_B_PRODUCT_SKU);
            return p;
        });
        product.setTitle("Tenant B Probe Product");
        product.setIngredientsText("probe");
        product.setAllergenMask(0);
        product.setPricePennies(999L);
        product.setCategory("Probe");
        product.setAvailable(true);
        product.setFeatured(false);
        product.setShopId(savedShop.getId());
        productRepository.save(product);
    }

    private SeedResult seed() {
        SeedResult result = new SeedResult();

        List<Shop> shops = new ArrayList<>();
        shops.add(upsertShop(result, "Mama Ade's Kitchen", "mama-ades-kitchen",
                "48 Rye Lane, Peckham, London SE15 5BS",
                "Home-style West African cooking — jollof, egusi and pounded yam done properly.",
                "Nigerian, West African, Halal", "/brand/logo-mama-ades.png",
                350L, 2500L));
        shops.add(upsertShop(result, "Peckham Jollof Co.", "peckham-jollof-co",
                "12 Bellenden Road, Peckham, London SE15 4QA",
                "Smoky party jollof, suya and grilled tilapia to eat in or take away.",
                "Nigerian, Grill, Halal", "/brand/logo-peckham-jollof.png",
                299L, 3000L));
        shops.add(upsertShop(result, "Brixton Village Grill", "brixton-village-grill",
                "Unit 74, Brixton Village Market, London SW9 8PS",
                "Flame-grilled peri peri chicken, kebabs and loaded sides.",
                "Grill, Peri Peri, Halal", "/brand/logo-brixton-grill.png",
                399L, 2000L));

        // The hidden archive shop that absorbs every non-curated / orphan product.
        Shop archive = upsertArchiveShop(result);

        seedMenu(result, shops.get(0).getId(), shopOneMenu());
        seedMenu(result, shops.get(1).getId(), shopTwoMenu());
        seedMenu(result, shops.get(2).getId(), shopThreeMenu());

        // Repair the live dev volume so the curated storefronts stay pristine.
        Set<String> curatedSkus = allCuratedSkus();
        quarantineNonCurated(result, curatedSkus, archive.getId());

        Set<String> curatedSlugs = Set.of("mama-ades-kitchen", "peckham-jollof-co", "brixton-village-grill");
        unpublishNonCurated(result, curatedSlugs);

        upsertCustomer(result, "Aisha Bello", "aisha.bello@example.com", "07700 900123");
        upsertCustomer(result, "Tom Whitfield", "tom.whitfield@example.com", "07700 900456");
        upsertCustomer(result, "Chidi Okonkwo", "chidi.okonkwo@example.com", "07700 900789");
        upsertCustomer(result, "Priya Sharma", "priya.sharma@example.com", "07700 900234");
        upsertCustomer(result, "James Okafor", "james.okafor@example.com", "07700 900567");

        // ADDITIVE image-seeding step (260713-kds): now that the curated products
        // are persisted, upload each bundled dish photo to MinIO and stamp the
        // matching product's image_url under the seeder-owns overwrite policy.
        seedProductImages(result);

        return result;
    }

    /**
     * Curated menu keyed by shop slug — the SINGLE SOURCE OF TRUTH shared by the
     * seeding path ({@link #seed}), the image-seeding step ({@link #seedProductImages})
     * and the unit test (via {@link #curatedTitlesBySlug}). Keeping this one map
     * means the test cannot silently rot against a second literal title list.
     */
    private static Map<String, List<MenuItem>> curatedMenusBySlug() {
        return Map.of(
                "mama-ades-kitchen", shopOneMenu(),
                "peckham-jollof-co", shopTwoMenu(),
                "brixton-village-grill", shopThreeMenu());
    }

    /**
     * Test-visible view of the curated catalogue: slug → set of curated dish
     * titles. Derived from {@link #curatedMenusBySlug} so it can never diverge
     * from the real menu definitions. Used by {@code DemoImageManifestTest} to
     * assert every manifest entry maps to a real curated product (guarding the
     * Peckham-period / Mama-Ade's-apostrophe shop-name traps).
     */
    static Map<String, Set<String>> curatedTitlesBySlug() {
        return curatedMenusBySlug().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream().map(MenuItem::title).collect(Collectors.toSet())));
    }

    /**
     * Seed license-verified dish photography onto the curated demo catalogue
     * (260713-kds — reverses the phase 19-09 "no image_url" design note). For each
     * of the 21 manifest entries: resolve the target shop slug, find the curated
     * {@link MenuItem} whose title matches the manifest dish (to get its SKU), load
     * the persisted {@link Product}, upload the bundled image to MinIO via
     * {@link StorageService#putSeedImage} (idempotent, deterministic key), then
     * apply the SEEDER-OWNS OVERWRITE POLICY:
     * <ul>
     *   <li>null/blank image_url → set the seed URL (fill the gap);</li>
     *   <li>current already contains {@code /products/seed/} → set (re-affirm prior
     *       seed, idempotent) — checked BEFORE the vendor-prefix test so a prior
     *       seed is never misread as a vendor upload;</li>
     *   <li>current does NOT start with THIS product's own upload folder
     *       ({@code <publicUrl>/<tenant>/products/<thisProductId>/}) → set
     *       (foreign/legacy URL of unverifiable provenance — a vendor upload for
     *       a DIFFERENT entity id, or an env-only artifact — "ours to overwrite";
     *       this is what replaces the Peri Peri Chicken + Suya Platter legacy
     *       URLs, whose embedded entity id does not match the product's own id);</li>
     *   <li>current starts with the product's own upload folder AND lacks the
     *       seed marker → LEAVE UNTOUCHED (a genuine vendor upload for THIS
     *       product always wins).</li>
     * </ul>
     * A missing manifest, an unmatched dish, or an absent classpath image is logged
     * and skipped — never fatal to dev boot.
     */
    private void seedProductImages(SeedResult result) {
        List<DemoImageManifest.ManifestEntry> entries;
        try {
            entries = DemoImageManifest.load();
        } catch (RuntimeException e) {
            log.warn("Demo image manifest unavailable; skipping image seeding: {}", e.getMessage());
            return;
        }

        Map<String, List<MenuItem>> menusBySlug = curatedMenusBySlug();

        for (DemoImageManifest.ManifestEntry entry : entries) {
            String slug = DemoImageManifest.slugForShop(entry.shop());
            List<MenuItem> menu = menusBySlug.get(slug);
            if (menu == null) {
                log.warn("No curated menu for slug '{}' (manifest dish '{}'); skipping image", slug, entry.dish());
                continue;
            }
            MenuItem match = menu.stream()
                    .filter(m -> m.title().equalsIgnoreCase(entry.dish()))
                    .findFirst()
                    .orElse(null);
            if (match == null) {
                log.warn("Manifest dish '{}' not found in curated menu for '{}'; skipping image",
                        entry.dish(), slug);
                continue;
            }
            Product product = productRepository.findBySku(match.sku()).orElse(null);
            if (product == null) {
                log.warn("Curated product SKU {} ('{}') not persisted; skipping image",
                        match.sku(), entry.dish());
                continue;
            }

            byte[] bytes = DemoImageManifest.readImage(entry.filename());
            String seedUrl = storageService.putSeedImage(DEMO_TENANT, entry.filename(), bytes, "image/jpeg");

            // A genuine vendor upload for THIS product lives under its own id's
            // folder (<publicUrl>/<tenant>/products/<thisProductId>/); a URL under
            // /products/ but a DIFFERENT entity id is a foreign/legacy artifact.
            String ownUploadPrefix = storageService.productUploadUrlPrefix(DEMO_TENANT, product.getId());
            String current = product.getImageUrl();
            boolean apply;
            if (current == null || current.isBlank()) {
                apply = true;                                          // fill the gap
            } else if (current.contains(StorageService.SEED_URL_MARKER)) {
                apply = true;                                          // prior seed — re-affirm (idempotent)
            } else if (!current.startsWith(ownUploadPrefix)) {
                apply = true;                                          // foreign/legacy URL (not this product's own upload) — ours to overwrite
            } else {
                apply = false;                                        // genuine vendor upload for THIS product wins
            }

            if (apply && !seedUrl.equals(current)) {
                product.setImageUrl(seedUrl);
                productRepository.save(product);
                result.imagesSeeded++;
            }
        }
    }

    private static List<MenuItem> shopOneMenu() {
        return List.of(
                new MenuItem("MAK-JOL", "Jollof Rice", "Mains", 899L,
                        "long-grain rice, tomatoes, peppers, onions, **chicken stock (celery)**", true,
                        "Halal, Gluten-Free", A_CELERY),
                new MenuItem("MAK-EGU", "Egusi Soup", "Mains", 1050L,
                        "melon seeds, spinach, palm oil, beef, **dried fish**, **crayfish**", false,
                        "Halal", A_FISH | A_CRUSTACEANS),
                new MenuItem("MAK-PYE", "Pounded Yam & Egusi", "Mains", 1100L,
                        "pounded yam, **egusi soup (fish, crayfish)**, assorted meat", false,
                        "Halal", A_FISH | A_CRUSTACEANS),
                new MenuItem("MAK-PLA", "Fried Plantain", "Sides", 350L,
                        "ripe plantain, sunflower oil", false, "Vegan, Gluten-Free", 0),
                new MenuItem("MAK-MOI", "Moin Moin", "Sides", 400L,
                        "steamed black-eyed bean pudding, peppers, onions, **eggs**", false,
                        "Vegetarian, Gluten-Free", A_EGGS),
                new MenuItem("MAK-CHA", "Chapman", "Drinks", 450L,
                        "Fanta, Sprite, blackcurrant, cucumber, bitters", true, "Vegetarian", 0),
                new MenuItem("MAK-ZOB", "Zobo", "Drinks", 300L,
                        "hibiscus, ginger, pineapple", false, "Vegan", 0));
    }

    private static List<MenuItem> shopTwoMenu() {
        return List.of(
                new MenuItem("PJC-PJO", "Party Jollof Rice", "Mains", 950L,
                        "smoky long-grain rice, scotch bonnet, tomatoes, peppers", true, "Halal", 0),
                new MenuItem("PJC-SUY", "Suya Platter", "Mains", 1200L,
                        "grilled spiced beef skewers, **yaji (peanuts)**, red onion, tomato", true,
                        "Halal, Spicy", A_PEANUTS),
                new MenuItem("PJC-TIL", "Grilled Tilapia", "Mains", 1350L,
                        "whole **tilapia (fish)**, pepper marinade, served with dodo", false,
                        "Halal, Pescatarian", A_FISH),
                new MenuItem("PJC-PUF", "Puff Puff", "Sides", 300L,
                        "sweet fried **wheat flour** dough balls, sugar dusting", false,
                        "Vegetarian", A_GLUTEN),
                new MenuItem("PJC-DOD", "Dodo", "Sides", 350L,
                        "fried sweet plantain", false, "Vegan, Gluten-Free", 0),
                new MenuItem("PJC-PAL", "Palm Wine", "Drinks", 600L,
                        "fresh tapped palm wine", false, null, 0),
                new MenuItem("PJC-GIN", "Ginger Beer", "Drinks", 350L,
                        "fiery homemade ginger beer", false, "Vegan", 0));
    }

    private static List<MenuItem> shopThreeMenu() {
        return List.of(
                new MenuItem("BVG-PER", "Peri Peri Chicken", "Mains", 900L,
                        "flame-grilled chicken, peri peri marinade", true, "Halal, Spicy", 0),
                new MenuItem("BVG-LAM", "Lamb Kebab", "Mains", 1000L,
                        "marinated lamb skewers, **flatbread (wheat)**, salad", false,
                        "Halal", A_GLUTEN),
                new MenuItem("BVG-BEE", "Beef Suya Wrap", "Mains", 850L,
                        "spiced beef, red onion, **wheat wrap**, **yaji (peanuts)**", true,
                        "Halal, Spicy", A_GLUTEN | A_PEANUTS),
                new MenuItem("BVG-SWF", "Sweet Potato Fries", "Sides", 400L,
                        "sweet potato, sea salt, sunflower oil", false, "Vegan, Gluten-Free", 0),
                new MenuItem("BVG-COL", "Coleslaw", "Sides", 250L,
                        "cabbage, carrot, **mayonnaise (egg, mustard)**", false,
                        "Vegetarian, Gluten-Free", A_EGGS | A_MUSTARD),
                new MenuItem("BVG-MAN", "Mango Lassi", "Drinks", 400L,
                        "mango, **yoghurt (milk)**, cardamom", false, "Vegetarian", A_MILK),
                new MenuItem("BVG-SOB", "Sobo Punch", "Drinks", 350L,
                        "hibiscus punch, pineapple, orange", false, "Vegan", 0));
    }

    private Set<String> allCuratedSkus() {
        List<MenuItem> all = new ArrayList<>();
        all.addAll(shopOneMenu());
        all.addAll(shopTwoMenu());
        all.addAll(shopThreeMenu());
        return all.stream().map(MenuItem::sku).collect(Collectors.toSet());
    }

    private void seedMenu(SeedResult result, UUID shopId, List<MenuItem> items) {
        for (MenuItem item : items) {
            upsertProduct(result, shopId, item);
        }
    }

    /**
     * Upsert a curated shop by slug: create if absent, otherwise UPDATE its
     * fields so tags/logo/description/fees enrichment lands on the pre-existing
     * dev row too. Always published.
     */
    private Shop upsertShop(SeedResult result, String name, String slug, String address,
                            String description, String tags, String logoUrl,
                            long deliveryFeePennies, long freeDeliveryThresholdPennies) {
        Shop shop = shopRepository.findBySlug(slug).orElseGet(() -> {
            Shop s = new Shop();
            s.setTenantId(DEMO_TENANT);
            s.setSlug(slug);
            result.shopsCreated++;
            return s;
        });
        shop.setName(name);
        shop.setAddress(address);
        shop.setDescription(description);
        shop.setTags(tags);
        shop.setLogoUrl(logoUrl);
        shop.setDeliveryFeePennies(deliveryFeePennies);
        shop.setFreeDeliveryThresholdPennies(freeDeliveryThresholdPennies);
        shop.setMinimumOrderPennies(1000L);
        // DELIBERATE dev-only exception to the Phase-18 sole-writer rule (WR-10):
        // the onboarding state machine owns Shop.published, but the onboarding
        // aggregate is one-per-tenant (UNIQUE(tenant_id)) so it cannot take all
        // three curated demo shops live. These are bootstrap FIXTURES that must
        // render as published storefronts on a fresh dev volume; no gate has
        // validated them and none is claimed. @Profile("dev") keeps this bean —
        // and therefore this bypass — out of every non-dev environment.
        shop.setPublished(true);
        return shopRepository.save(shop);
    }

    /** The hidden holding shop for orphaned/legacy products. Never published. */
    private Shop upsertArchiveShop(SeedResult result) {
        Shop archive = shopRepository.findBySlug(ARCHIVE_SLUG).orElseGet(() -> {
            Shop s = new Shop();
            s.setTenantId(DEMO_TENANT);
            s.setSlug(ARCHIVE_SLUG);
            s.setName("Unsorted legacy items");
            s.setAddress("—");
            s.setDescription("Internal archive of legacy/orphaned demo products. Not a storefront.");
            s.setMinimumOrderPennies(0L);
            s.setDeliveryFeePennies(0L);
            result.shopsCreated++;
            return s;
        });
        archive.setPublished(false);
        return shopRepository.save(archive);
    }

    /**
     * Upsert a curated product by SKU (idempotent) and enrich it: re-home to the
     * correct shop and apply featured/dietary metadata even if the row already
     * exists (so a mis-aligned dev row is corrected, not skipped).
     */
    private void upsertProduct(SeedResult result, UUID shopId, MenuItem item) {
        Product product = productRepository.findBySku(item.sku()).orElseGet(() -> {
            Product p = new Product();
            p.setTenantId(DEMO_TENANT);
            p.setSku(item.sku());
            result.productsCreated++;
            return p;
        });
        // PPDS allergen data (QA-council FIX-7 / M4): ingredients_text keeps
        // the raw **allergen** markup (the vendor convention); the persisted
        // spans mirror ProductService's save-path cache and the PPDS label
        // re-parses the text fresh at render time. The mask drives the
        // storefront allergen list, dashboard column and allergen warnings.
        // Applied UNCONDITIONALLY so pre-existing dev rows (seeded with
        // mask=0) are repaired in place on restart, not skipped.
        var parsedIngredients = uk.jtoye.core.product.IngredientMarkupParser.parse(item.ingredients());
        // V41 durability data so the PPDS label endpoint can render for the
        // demo menu (a compliant label 422s without it): fresh-prepared food
        // gets a 2-day USE_BY — plausible for kitchen-made items and safe as
        // dev/demo fixture data.
        product.setShelfLifeDays(2);
        product.setDurabilityType("USE_BY");
        product.setTitle(item.title());
        product.setCategory(item.category());
        product.setPricePennies(item.pricePennies());
        product.setIngredientsText(item.ingredients());
        product.setAllergenMask(item.allergenMask());
        product.setAllergenSpans(parsedIngredients.spans());
        // Description is customer-facing prose — use the markup-stripped text.
        product.setDescription(item.title() + " — " + parsedIngredients.plainText());
        product.setAvailable(true);
        product.setShopId(shopId);
        product.setFeatured(item.featured());
        product.setDietaryTags(item.dietaryTags());
        // image_url is stamped separately by seedProductImages (#15, reversed
        // 260713-kds): curated products carry seeded, license-verified dish photos
        // under /products/seed/, governed by the seeder-owns overwrite policy.
        productRepository.save(product);
    }

    /**
     * Move every product whose SKU is not in the curated set — legacy orphans,
     * NULL-shop rows, and anything mis-assigned into a curated shop — into the
     * hidden archive shop. This is what guarantees "no duplicate line items" and
     * "no placeholder names" on the published storefronts (UIX-05). Idempotent:
     * once quarantined, a product's shop_id already equals the archive id.
     */
    private void quarantineNonCurated(SeedResult result, Set<String> curatedSkus, UUID archiveId) {
        for (Product p : productRepository.findAll()) {
            String sku = p.getSku();
            boolean curated = sku != null && curatedSkus.contains(sku);
            if (!curated && !archiveId.equals(p.getShopId())) {
                p.setShopId(archiveId);
                p.setFeatured(false);
                // Defence-in-depth (CR-01): archived products must not be
                // orderable by id through any storefront. The service-layer
                // product↔shop match in PublicStorefrontService.createGuestOrder
                // is the primary control; this makes the quarantined rows inert
                // even if that check ever regresses.
                p.setAvailable(false);
                productRepository.save(p);
                result.productsQuarantined++;
            }
        }
    }

    /**
     * Un-publish every non-curated (and non-archive) shop so the directory is
     * clean — EXCEPT shops taken LIVE through the real Phase-18 onboarding
     * state machine (WR-10). The machine is the sole authorised writer of
     * {@code Shop.published}; sweeping a LIVE-onboarded shop back to
     * unpublished on every dev restart would silently undo a developer's
     * onboarding E2E work and directly fight that invariant. (The curated-shop
     * force-publish above remains a documented dev-only bootstrap exception.)
     */
    private void unpublishNonCurated(SeedResult result, Set<String> curatedSlugs) {
        // One onboarding per tenant (UNIQUE(tenant_id)), so a single lookup
        // yields the only shop the state machine may currently hold LIVE.
        Set<UUID> liveOnboardedShopIds = onboardingRepository.findByTenantId(DEMO_TENANT)
                .filter(o -> o.getStatus() == OnboardingState.LIVE && o.getShopId() != null)
                .map(o -> Set.of(o.getShopId()))
                .orElse(Set.of());

        for (Shop s : shopRepository.findAll()) {
            String slug = s.getSlug();
            boolean keepPublished = (slug != null && curatedSlugs.contains(slug))
                    || liveOnboardedShopIds.contains(s.getId());
            if (!keepPublished && Boolean.TRUE.equals(s.getPublished())) {
                s.setPublished(false);
                shopRepository.save(s);
                result.shopsUnpublished++;
            }
        }
    }

    /** Upsert a customer by email (idempotent, tenant-scoped). */
    private void upsertCustomer(SeedResult result, String name, String email, String phone) {
        if (customerRepository.existsByEmail(email)) {
            return;
        }
        Customer customer = new Customer(name, email);
        customer.setTenantId(DEMO_TENANT);
        customer.setPhone(phone);
        customerRepository.save(customer);
        result.customersCreated++;
    }

    /** Mutable counter bag for the run summary log. */
    private static final class SeedResult {
        int shopsCreated;
        int productsCreated;
        int customersCreated;
        int productsQuarantined;
        int shopsUnpublished;
        int imagesSeeded;
    }
}
