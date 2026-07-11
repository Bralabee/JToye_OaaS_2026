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
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.security.TenantContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reproducible dev/demo data seeder (UIX-05, phase 19).
 *
 * <p>Replaces the ad-hoc, hand-created rows in the shared dev Postgres volume
 * with a committed, idempotent, realistic multi-shop dataset. It exists because
 * every shop under a tenant used to render the same menu — 24 of 25 seeded
 * products carried a NULL {@code shop_id} and the storefront query matched
 * {@code (shop_id = :shopId OR shop_id IS NULL)}, so unassigned products bled
 * into (and duplicated across) every shop. This seeder assigns every demo
 * product to exactly one shop and also aligns any pre-existing NULL-shop_id dev
 * rows, so the live dev volume matches the now strictly shop-scoped query.
 *
 * <p><strong>Profile gating (correctness + safety):</strong> restricted to the
 * {@code dev} Spring profile and only wired as an {@link ApplicationRunner} at
 * dev startup. Testcontainers integration tests boot under {@code test} profile
 * so this bean is never instantiated (no fixture/golden-file perturbation), and
 * prod boots the prod profile so it never runs against production data. It is
 * deliberately NOT a Flyway migration for the same reason — a migration would
 * ship to every environment.
 *
 * <p><strong>Multi-tenancy:</strong> all writes are scoped to the default demo
 * tenant via {@link TenantContext}; the {@code TenantSetLocalAspect} applies the
 * RLS GUC to every repository op inside the transaction (matching the
 * {@code ScheduledCleanupService} pattern — {@link TransactionTemplate} is used
 * rather than a {@code @Transactional} helper to avoid the self-invocation proxy
 * trap that would run the seed with a NULL tenant).
 *
 * <p><strong>Idempotency:</strong> shops upsert by slug, products by SKU,
 * customers by email — re-running never duplicates rows.
 */
@Component
@Profile("dev")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** Default demo tenant seeded by V13 — matches the dev Keycloak tenant claim. */
    private static final UUID DEMO_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final TransactionTemplate transactionTemplate;

    public DemoDataSeeder(ShopRepository shopRepository,
                          ProductRepository productRepository,
                          CustomerRepository customerRepository,
                          PlatformTransactionManager transactionManager) {
        this.shopRepository = shopRepository;
        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

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
                                + "{} pre-existing NULL-shop_id product(s) aligned.",
                        DEMO_TENANT, result.shopsCreated, result.productsCreated,
                        result.customersCreated, result.productsAligned);
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
                350L, 2500L));
        shops.add(upsertShop(result, "Peckham Jollof Co.", "peckham-jollof-co",
                "12 Bellenden Road, Peckham, London SE15 4QA",
                "Smoky party jollof, suya and grilled tilapia to eat in or take away.",
                299L, 3000L));
        shops.add(upsertShop(result, "Brixton Village Grill", "brixton-village-grill",
                "Unit 74, Brixton Village Market, London SW9 8PS",
                "Flame-grilled peri peri chicken, kebabs and loaded sides.",
                399L, 2000L));

        seedShopOneMenu(result, shops.get(0).getId());
        seedShopTwoMenu(result, shops.get(1).getId());
        seedShopThreeMenu(result, shops.get(2).getId());

        // Align any pre-existing dev rows created before shop-scoping (24/25 were
        // NULL) so the live dev volume matches the strictly scoped storefront query.
        alignNullShopProducts(result, shops);

        upsertCustomer(result, "Aisha Bello", "aisha.bello@example.com", "07700 900123");
        upsertCustomer(result, "Tom Whitfield", "tom.whitfield@example.com", "07700 900456");
        upsertCustomer(result, "Chidi Okonkwo", "chidi.okonkwo@example.com", "07700 900789");
        upsertCustomer(result, "Priya Sharma", "priya.sharma@example.com", "07700 900234");
        upsertCustomer(result, "James Okafor", "james.okafor@example.com", "07700 900567");

        return result;
    }

    private void seedShopOneMenu(SeedResult result, UUID shopId) {
        upsertProduct(result, shopId, "MAK-JOL", "Jollof Rice", "Mains", 899L,
                "long-grain rice, tomatoes, peppers, onions, chicken stock");
        upsertProduct(result, shopId, "MAK-EGU", "Egusi Soup", "Mains", 1050L,
                "melon seeds, spinach, palm oil, beef, dried fish, crayfish");
        upsertProduct(result, shopId, "MAK-PYE", "Pounded Yam & Egusi", "Mains", 1100L,
                "pounded yam, egusi soup, assorted meat");
        upsertProduct(result, shopId, "MAK-PLA", "Fried Plantain", "Sides", 350L,
                "ripe plantain, sunflower oil");
        upsertProduct(result, shopId, "MAK-MOI", "Moin Moin", "Sides", 400L,
                "steamed black-eyed bean pudding, peppers, onions");
        upsertProduct(result, shopId, "MAK-CHA", "Chapman", "Drinks", 450L,
                "Fanta, Sprite, blackcurrant, cucumber, bitters");
        upsertProduct(result, shopId, "MAK-ZOB", "Zobo", "Drinks", 300L,
                "hibiscus, ginger, pineapple");
    }

    private void seedShopTwoMenu(SeedResult result, UUID shopId) {
        upsertProduct(result, shopId, "PJC-PJO", "Party Jollof Rice", "Mains", 950L,
                "smoky long-grain rice, scotch bonnet, tomatoes, peppers");
        upsertProduct(result, shopId, "PJC-SUY", "Suya Platter", "Mains", 1200L,
                "grilled spiced beef skewers, yaji, red onion, tomato");
        upsertProduct(result, shopId, "PJC-TIL", "Grilled Tilapia", "Mains", 1350L,
                "whole tilapia, pepper marinade, served with dodo");
        upsertProduct(result, shopId, "PJC-PUF", "Puff Puff", "Sides", 300L,
                "sweet fried dough balls, sugar dusting");
        upsertProduct(result, shopId, "PJC-DOD", "Dodo", "Sides", 350L,
                "fried sweet plantain");
        upsertProduct(result, shopId, "PJC-PAL", "Palm Wine", "Drinks", 600L,
                "fresh tapped palm wine");
        upsertProduct(result, shopId, "PJC-GIN", "Ginger Beer", "Drinks", 350L,
                "fiery homemade ginger beer");
    }

    private void seedShopThreeMenu(SeedResult result, UUID shopId) {
        upsertProduct(result, shopId, "BVG-PER", "Peri Peri Chicken", "Mains", 900L,
                "flame-grilled chicken, peri peri marinade");
        upsertProduct(result, shopId, "BVG-LAM", "Lamb Kebab", "Mains", 1000L,
                "marinated lamb skewers, flatbread, salad");
        upsertProduct(result, shopId, "BVG-BEE", "Beef Suya Wrap", "Mains", 850L,
                "spiced beef, red onion, wrap, yaji");
        upsertProduct(result, shopId, "BVG-SWF", "Sweet Potato Fries", "Sides", 400L,
                "sweet potato, sea salt, sunflower oil");
        upsertProduct(result, shopId, "BVG-COL", "Coleslaw", "Sides", 250L,
                "cabbage, carrot, mayonnaise");
        upsertProduct(result, shopId, "BVG-MAN", "Mango Lassi", "Drinks", 400L,
                "mango, yoghurt, cardamom");
        upsertProduct(result, shopId, "BVG-SOB", "Sobo Punch", "Drinks", 350L,
                "hibiscus punch, pineapple, orange");
    }

    /** Upsert a shop by slug (idempotent). Returns the persisted (or existing) shop. */
    private Shop upsertShop(SeedResult result, String name, String slug, String address,
                            String description, long deliveryFeePennies, long freeDeliveryThresholdPennies) {
        return shopRepository.findBySlug(slug).orElseGet(() -> {
            Shop shop = new Shop();
            shop.setTenantId(DEMO_TENANT);
            shop.setName(name);
            shop.setSlug(slug);
            shop.setAddress(address);
            shop.setDescription(description);
            shop.setDeliveryFeePennies(deliveryFeePennies);
            shop.setFreeDeliveryThresholdPennies(freeDeliveryThresholdPennies);
            shop.setMinimumOrderPennies(1000L);
            shop.setPublished(true);
            Shop saved = shopRepository.save(shop);
            result.shopsCreated++;
            return saved;
        });
    }

    /** Upsert a product by SKU (idempotent). Every demo product gets a non-null shop_id. */
    private void upsertProduct(SeedResult result, UUID shopId, String sku, String title,
                               String category, long pricePennies, String ingredients) {
        if (productRepository.findBySku(sku).isPresent()) {
            return;
        }
        Product product = new Product();
        product.setTenantId(DEMO_TENANT);
        product.setSku(sku);
        product.setTitle(title);
        product.setCategory(category);
        product.setPricePennies(pricePennies);
        product.setIngredientsText(ingredients);
        product.setDescription(title + " — " + ingredients);
        product.setAllergenMask(0);
        product.setAvailable(true);
        product.setShopId(shopId);
        productRepository.save(product);
        result.productsCreated++;
    }

    /**
     * Assign a non-null shop_id to any product that still has one NULL (the
     * pre-scoping dev rows). Distributed deterministically round-robin across the
     * demo shops so no single shop absorbs every orphan. A no-op on re-run once
     * every product is assigned.
     */
    private void alignNullShopProducts(SeedResult result, List<Shop> shops) {
        List<Product> orphans = productRepository.findAll().stream()
                .filter(p -> p.getShopId() == null)
                .toList();
        int i = 0;
        for (Product orphan : orphans) {
            orphan.setShopId(shops.get(i % shops.size()).getId());
            productRepository.save(orphan);
            result.productsAligned++;
            i++;
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
        int productsAligned;
    }
}
