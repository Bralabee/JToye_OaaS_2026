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

import java.util.ArrayList;
import java.util.List;
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
 * <p><strong>No product photography (#15):</strong> product cards deliberately
 * carry no {@code image_url} — the storefront renders the approved SafeImage
 * branded fallback tile. Shop <em>branding</em> (a logo) IS seeded so the
 * "populated images resolve naturalWidth&gt;0" contract is exercised on real data.
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

    /** Slug of the hidden archive shop that absorbs every non-curated product. */
    private static final String ARCHIVE_SLUG = "unsorted-legacy-items";

    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final VendorOnboardingRepository onboardingRepository;
    private final TransactionTemplate transactionTemplate;

    public DemoDataSeeder(ShopRepository shopRepository,
                          ProductRepository productRepository,
                          CustomerRepository customerRepository,
                          VendorOnboardingRepository onboardingRepository,
                          PlatformTransactionManager transactionManager) {
        this.shopRepository = shopRepository;
        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
        this.onboardingRepository = onboardingRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** A single curated menu item, incl. "Popular" (featured) + dietary metadata. */
    private record MenuItem(String sku, String title, String category, long pricePennies,
                            String ingredients, boolean featured, String dietaryTags) {}

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
                                + "{} non-curated product(s) quarantined, {} shop(s) unpublished.",
                        DEMO_TENANT, result.shopsCreated, result.productsCreated,
                        result.customersCreated, result.productsQuarantined, result.shopsUnpublished);
            }
        } finally {
            TenantContext.clear();
        }
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

        return result;
    }

    private List<MenuItem> shopOneMenu() {
        return List.of(
                new MenuItem("MAK-JOL", "Jollof Rice", "Mains", 899L,
                        "long-grain rice, tomatoes, peppers, onions, chicken stock", true, "Halal, Gluten-Free"),
                new MenuItem("MAK-EGU", "Egusi Soup", "Mains", 1050L,
                        "melon seeds, spinach, palm oil, beef, dried fish, crayfish", false, "Halal"),
                new MenuItem("MAK-PYE", "Pounded Yam & Egusi", "Mains", 1100L,
                        "pounded yam, egusi soup, assorted meat", false, "Halal"),
                new MenuItem("MAK-PLA", "Fried Plantain", "Sides", 350L,
                        "ripe plantain, sunflower oil", false, "Vegan, Gluten-Free"),
                new MenuItem("MAK-MOI", "Moin Moin", "Sides", 400L,
                        "steamed black-eyed bean pudding, peppers, onions", false, "Vegetarian, Gluten-Free"),
                new MenuItem("MAK-CHA", "Chapman", "Drinks", 450L,
                        "Fanta, Sprite, blackcurrant, cucumber, bitters", true, "Vegetarian"),
                new MenuItem("MAK-ZOB", "Zobo", "Drinks", 300L,
                        "hibiscus, ginger, pineapple", false, "Vegan"));
    }

    private List<MenuItem> shopTwoMenu() {
        return List.of(
                new MenuItem("PJC-PJO", "Party Jollof Rice", "Mains", 950L,
                        "smoky long-grain rice, scotch bonnet, tomatoes, peppers", true, "Halal"),
                new MenuItem("PJC-SUY", "Suya Platter", "Mains", 1200L,
                        "grilled spiced beef skewers, yaji, red onion, tomato", true, "Halal, Spicy"),
                new MenuItem("PJC-TIL", "Grilled Tilapia", "Mains", 1350L,
                        "whole tilapia, pepper marinade, served with dodo", false, "Halal, Pescatarian"),
                new MenuItem("PJC-PUF", "Puff Puff", "Sides", 300L,
                        "sweet fried dough balls, sugar dusting", false, "Vegetarian"),
                new MenuItem("PJC-DOD", "Dodo", "Sides", 350L,
                        "fried sweet plantain", false, "Vegan, Gluten-Free"),
                new MenuItem("PJC-PAL", "Palm Wine", "Drinks", 600L,
                        "fresh tapped palm wine", false, null),
                new MenuItem("PJC-GIN", "Ginger Beer", "Drinks", 350L,
                        "fiery homemade ginger beer", false, "Vegan"));
    }

    private List<MenuItem> shopThreeMenu() {
        return List.of(
                new MenuItem("BVG-PER", "Peri Peri Chicken", "Mains", 900L,
                        "flame-grilled chicken, peri peri marinade", true, "Halal, Spicy"),
                new MenuItem("BVG-LAM", "Lamb Kebab", "Mains", 1000L,
                        "marinated lamb skewers, flatbread, salad", false, "Halal"),
                new MenuItem("BVG-BEE", "Beef Suya Wrap", "Mains", 850L,
                        "spiced beef, red onion, wrap, yaji", true, "Halal, Spicy"),
                new MenuItem("BVG-SWF", "Sweet Potato Fries", "Sides", 400L,
                        "sweet potato, sea salt, sunflower oil", false, "Vegan, Gluten-Free"),
                new MenuItem("BVG-COL", "Coleslaw", "Sides", 250L,
                        "cabbage, carrot, mayonnaise", false, "Vegetarian, Gluten-Free"),
                new MenuItem("BVG-MAN", "Mango Lassi", "Drinks", 400L,
                        "mango, yoghurt, cardamom", false, "Vegetarian"),
                new MenuItem("BVG-SOB", "Sobo Punch", "Drinks", 350L,
                        "hibiscus punch, pineapple, orange", false, "Vegan"));
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
            p.setAllergenMask(0);
            result.productsCreated++;
            return p;
        });
        product.setTitle(item.title());
        product.setCategory(item.category());
        product.setPricePennies(item.pricePennies());
        product.setIngredientsText(item.ingredients());
        product.setDescription(item.title() + " — " + item.ingredients());
        product.setAvailable(true);
        product.setShopId(shopId);
        product.setFeatured(item.featured());
        product.setDietaryTags(item.dietaryTags());
        // No image_url: product cards use the SafeImage branded fallback (#15).
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
    }
}
