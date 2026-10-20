package uk.jtoye.core.storefront;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.media.ProductMediaRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 29-29 (D-34(b), owner ruling 2026-10-19): the anonymous storefront serves the image a vendor uploaded
 * through the safe media pipeline.
 *
 * <p>The defect, measured live by plan 29-14: a vendor PNG became an ACTIVE WebP derivative and the
 * vendor product DTO returned it (the D-03a asset-first dual-read, {@code ProductService.resolveAssetFirst}),
 * but the anonymous menu returned {@code "imageUrl":null,"imageUrls":[]} for the same product, because the
 * public DTO read only the flat {@code products.image_url} / {@code additional_image_urls} columns and the
 * Phase 24 pipeline never writes them.
 *
 * <p>The fix applies the vendor's own rule ({@code findPrimaryActiveObjectKey} then
 * {@code StorageService.urlForKey}) batched over the menu, plus one public visibility predicate
 * ({@code flagged = false}). Everything here runs as the application's datasource role AFTER it is
 * downgraded to NOSUPERUSER NOBYPASSRLS (asserted before every case), so V53's FORCE RLS tenant policies
 * on {@code media_asset} and {@code product_media} genuinely decide what the storefront can read. No
 * anonymous read policy exists or is added: the storefront read runs inside
 * {@code resolvePublicShopForSlug}'s tenant pin, which case 12 measures.
 *
 * <p>Each negative case passes vacuously against the pre-fix code (nothing was public then); each is
 * shown able to fail by a break arm recorded in evidence/29-29-storefront-image.txt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class PublicStorefrontMediaImageIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    private static final String PUBLIC_URL = "https://media.29-29.test/jtoye-images";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("storage.blob.public-url", () -> PUBLIC_URL);
        // Case 13 (one query per menu) reads Hibernate's prepared-statement count.
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;
    @Autowired JsonMapper jsonMapper;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ProductMediaRepository productMediaRepository;

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000292901");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000292902");

    private static final String SHOP_A = "media-public-a";
    private static final String SHOP_HIDDEN = "media-hidden-a";
    private static final String SHOP_B = "media-public-b";

    private static final String ASSET_DISH = "Asset Dish";
    private static final String BOTH_DISH = "Both Dish";
    private static final String LEGACY_DISH = "Legacy Dish";
    private static final String SEED_DISH = "Seed Dish";
    private static final String PENDING_DISH = "Pending Dish";
    private static final String FAILED_DISH = "Failed Dish";
    private static final String FLAGGED_DISH = "Flagged Dish";
    private static final String FLAGGED_OVER_LEGACY_DISH = "Flagged-Over-Legacy Dish";
    private static final String QUARANTINE_KEY_DISH = "Quarantine-Key Dish";
    private static final String FOREIGN_JOIN_DISH = "Foreign-Join Dish";
    private static final String HIDDEN_DISH = "Hidden Dish";
    private static final String B_DISH = "B Dish";

    /** Every tenant-A product on shop A's public menu (the oracle of case 14 walks all of them). */
    private static final List<String> SHOP_A_TITLES = List.of(ASSET_DISH, BOTH_DISH, LEGACY_DISH, SEED_DISH,
            PENDING_DISH, FAILED_DISH, FLAGGED_DISH, FLAGGED_OVER_LEGACY_DISH, QUARANTINE_KEY_DISH,
            FOREIGN_JOIN_DISH);

    private static boolean seeded;
    private static int shaCounter;
    private static final Map<String, UUID> PRODUCT_IDS = new LinkedHashMap<>();
    /** title -> the seeded products.image_url (null when none). */
    private static final Map<String, String> LEGACY_URL = new LinkedHashMap<>();
    /** title -> the seeded products.additional_image_urls. */
    private static final Map<String, List<String>> GALLERY = new LinkedHashMap<>();
    /** title -> the object_key of the asset its primary join row names. */
    private static final Map<String, String> ASSET_KEY = new LinkedHashMap<>();
    /** title -> the id of that asset. */
    private static final Map<String, UUID> ASSET_ID = new LinkedHashMap<>();
    private static final List<String> FLAGGED_FIXTURES = List.of(FLAGGED_DISH, FLAGGED_OVER_LEGACY_DISH);
    private static int tenantAAssetCount;
    private static int tenantAJoinCount;

    @BeforeEach
    void seedOnceThenDowngrade() {
        if (!seeded) {
            insertTenant(TENANT_A, "29-29 Media Tenant A");
            insertTenant(TENANT_B, "29-29 Media Tenant B");
            UUID shopA = insertShop(TENANT_A, SHOP_A, true);
            UUID shopHidden = insertShop(TENANT_A, SHOP_HIDDEN, false);
            UUID shopB = insertShop(TENANT_B, SHOP_B, true);

            // Tenant B first: case 9's foreign join row in tenant A names B's asset.
            insertProduct(TENANT_B, shopB, B_DISH, null, List.of());
            primaryAsset(TENANT_B, B_DISH, "ACTIVE", false, false);

            insertProduct(TENANT_A, shopA, ASSET_DISH, null,
                    List.of(PUBLIC_URL + "/" + TENANT_A + "/media/gallery-asset-dish.webp"));
            primaryAsset(TENANT_A, ASSET_DISH, "ACTIVE", false, false);

            insertProduct(TENANT_A, shopA, BOTH_DISH, "https://legacy.29-29.test/both-dish.jpg", List.of());
            primaryAsset(TENANT_A, BOTH_DISH, "ACTIVE", false, false);

            insertProduct(TENANT_A, shopA, LEGACY_DISH, "https://legacy.29-29.test/legacy-dish.jpg",
                    List.of("https://legacy.29-29.test/legacy-dish-2.jpg", "https://legacy.29-29.test/legacy-dish-3.jpg"));

            // The shape V53 deliberately left flat (SPEC D1): seeded demo images are never wrapped as assets.
            insertProduct(TENANT_A, shopA, SEED_DISH, PUBLIC_URL + "/products/seed/seed-dish.webp", List.of());

            insertProduct(TENANT_A, shopA, PENDING_DISH, null, List.of());
            primaryAsset(TENANT_A, PENDING_DISH, "PENDING", false, true);

            insertProduct(TENANT_A, shopA, FAILED_DISH, null, List.of());
            primaryAsset(TENANT_A, FAILED_DISH, "FAILED", false, false);

            insertProduct(TENANT_A, shopA, FLAGGED_DISH, null, List.of());
            primaryAsset(TENANT_A, FLAGGED_DISH, "ACTIVE", true, false);

            insertProduct(TENANT_A, shopA, FLAGGED_OVER_LEGACY_DISH, "https://legacy.29-29.test/flagged-over-legacy.jpg",
                    List.of());
            primaryAsset(TENANT_A, FLAGGED_OVER_LEGACY_DISH, "ACTIVE", true, false);

            // A corrupt row the pipeline never writes (ACTIVE with a quarantine key), planted to prove the guard.
            insertProduct(TENANT_A, shopA, QUARANTINE_KEY_DISH, null, List.of());
            primaryAsset(TENANT_A, QUARANTINE_KEY_DISH, "ACTIVE", false, true);

            // A tenant-A join row naming tenant B's ACTIVE asset: product_media.asset_id's FK does not check tenant.
            insertProduct(TENANT_A, shopA, FOREIGN_JOIN_DISH, null, List.of());
            insertJoin(TENANT_A, PRODUCT_IDS.get(FOREIGN_JOIN_DISH), ASSET_ID.get(B_DISH));
            tenantAJoinCount++;

            insertProduct(TENANT_A, shopHidden, HIDDEN_DISH, null, List.of());
            primaryAsset(TENANT_A, HIDDEN_DISH, "ACTIVE", false, false);

            jdbcTemplate.update("UPDATE shops SET featured_product_ids = ARRAY[?::uuid] WHERE id = ?",
                    PRODUCT_IDS.get(ASSET_DISH).toString(), shopA);

            jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER NOBYPASSRLS");
            seeded = true;
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class))
                .as("PRECONDITION: every case below is meaningless if the datasource role still bypasses RLS")
                .isFalse();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Positive path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("29-29 — case 1: the menu carries Asset Dish's ACTIVE derivative as imageUrl, then its flat gallery")
    void case01_menuServesTheAssetDerivative() throws Exception {
        Map<String, JsonNode> menu = menu(SHOP_A);
        JsonNode dish = menu.get(ASSET_DISH);
        assertThat(text(dish, "imageUrl")).as(dish.toString()).isEqualTo(assetUrl(ASSET_DISH));
        assertThat(urls(dish)).as(dish.toString())
                .containsExactly(assetUrl(ASSET_DISH), GALLERY.get(ASSET_DISH).get(0));
    }

    @Test
    @DisplayName("29-29 — case 2: /config featuredProducts carries Asset Dish with the same derivative")
    void case02_featuredConfigServesTheAssetDerivative() throws Exception {
        JsonNode config = getJson("/api/v1/public/shops/" + SHOP_A + "/config", 200);
        JsonNode featured = config.get("featuredProducts");
        assertThat(featured.isArray()).as(config.toString()).isTrue();
        assertThat(featured.size()).as(config.toString()).isEqualTo(1);
        JsonNode dish = featured.get(0);
        assertThat(text(dish, "title")).isEqualTo(ASSET_DISH);
        assertThat(text(dish, "imageUrl")).as(dish.toString()).isEqualTo(assetUrl(ASSET_DISH));
        assertThat(urls(dish).get(0)).as(dish.toString()).isEqualTo(assetUrl(ASSET_DISH));
    }

    @Test
    @DisplayName("29-29 — case 3: Both Dish shows the asset, never the legacy column, as imageUrl and imageUrls[0]")
    void case03_assetWinsOverLegacy() throws Exception {
        JsonNode dish = menu(SHOP_A).get(BOTH_DISH);
        assertThat(text(dish, "imageUrl")).as(dish.toString()).isEqualTo(assetUrl(BOTH_DISH));
        assertThat(urls(dish)).as(dish.toString()).isNotEmpty();
        assertThat(urls(dish).get(0)).as(dish.toString()).isEqualTo(assetUrl(BOTH_DISH));
    }

    @Test
    @DisplayName("29-29 — case 4: Legacy Dish and Seed Dish (no media rows) are exactly what the flat columns say")
    void case04_legacyAndSeedUnchanged() throws Exception {
        Map<String, JsonNode> menu = menu(SHOP_A);
        for (String title : List.of(LEGACY_DISH, SEED_DISH)) {
            JsonNode dish = menu.get(title);
            assertThat(text(dish, "imageUrl")).as(title + " " + dish).isEqualTo(LEGACY_URL.get(title));
            List<String> expected = new ArrayList<>();
            expected.add(LEGACY_URL.get(title));
            expected.addAll(GALLERY.get(title));
            assertThat(urls(dish)).as(title + " " + dish).containsExactlyElementsOf(expected);
        }
    }

    // ------------------------------------------------------------------
    // Nothing non-public reaches the storefront
    // ------------------------------------------------------------------

    @Test
    @DisplayName("29-29 — case 5: a PENDING and a FAILED primary show no image")
    void case05_pendingAndFailedHidden() throws Exception {
        Map<String, JsonNode> menu = menu(SHOP_A);
        for (String title : List.of(PENDING_DISH, FAILED_DISH)) {
            JsonNode dish = menu.get(title);
            assertThat(text(dish, "imageUrl")).as(title + " " + dish).isNull();
            assertThat(urls(dish)).as(title + " " + dish).isEmpty();
        }
    }

    @Test
    @DisplayName("29-29 — case 6: a flagged ACTIVE primary is hidden publicly, while the vendor rule still sees its key")
    void case06_flaggedHiddenPubliclyButVendorVisible() throws Exception {
        JsonNode dish = menu(SHOP_A).get(FLAGGED_DISH);
        assertThat(text(dish, "imageUrl")).as(dish.toString()).isNull();
        assertThat(urls(dish)).as(dish.toString()).isEmpty();
        assertThat(vendorRuleKey(FLAGGED_DISH))
                .as("the vendor rule (findPrimaryActiveObjectKey, tenant A pinned) sees the flagged key, so the "
                        + "hiding is the public predicate and not missing data")
                .contains(ASSET_KEY.get(FLAGGED_DISH));
    }

    @Test
    @DisplayName("29-29 — case 7: a flagged primary over a legacy image falls back to the legacy image_url (D-03a)")
    void case07_flaggedOverLegacyFallsBack() throws Exception {
        JsonNode dish = menu(SHOP_A).get(FLAGGED_OVER_LEGACY_DISH);
        assertThat(text(dish, "imageUrl")).as(dish.toString()).isEqualTo(LEGACY_URL.get(FLAGGED_OVER_LEGACY_DISH));
        assertThat(urls(dish)).as(dish.toString()).containsExactly(LEGACY_URL.get(FLAGGED_OVER_LEGACY_DISH));
    }

    @Test
    @DisplayName("29-29 — case 8: an ACTIVE row with a quarantine key is skipped; the menu answers 200 and names no quarantine object")
    void case08_quarantineKeyNeverPublished() throws Exception {
        MvcResult menuResult = mockMvc.perform(get("/api/v1/public/shops/" + SHOP_A + "/products")).andReturn();
        String menuBody = menuResult.getResponse().getContentAsString();
        assertThat(menuResult.getResponse().getStatus()).as(menuBody).isEqualTo(200);
        JsonNode dish = index(jsonMapper.readTree(menuBody)).get(QUARANTINE_KEY_DISH);
        assertThat(text(dish, "imageUrl")).as(dish.toString()).isNull();
        String configBody = body("/api/v1/public/shops/" + SHOP_A + "/config", 200);
        for (String body : List.of(menuBody, configBody)) {
            assertThat(body).as("positive control: the scan sees media keys at all").contains("/" + TENANT_A + "/media/");
            assertThat(body).as("no quarantine-shaped key in a public body").doesNotContain("/quarantine/");
        }
    }

    @Test
    @DisplayName("29-29 — case 9: a tenant-A join row naming tenant B's asset shows no image, and B appears nowhere in A's bodies")
    void case09_foreignJoinRowHidden() throws Exception {
        JsonNode dish = menu(SHOP_A).get(FOREIGN_JOIN_DISH);
        assertThat(text(dish, "imageUrl")).as(dish.toString()).isNull();
        assertThat(urls(dish)).as(dish.toString()).isEmpty();
        for (String path : List.of("/products", "/config")) {
            String body = body("/api/v1/public/shops/" + SHOP_A + path, 200);
            assertThat(body).as(path).doesNotContain(TENANT_B.toString());
            assertThat(body).as(path).doesNotContain(ASSET_ID.get(B_DISH).toString());
        }
    }

    @Test
    @DisplayName("29-29 — case 10: shop B's own menu resolves B's asset (case 9's control)")
    void case10_tenantBResolvesItsOwnAsset() throws Exception {
        JsonNode dish = menu(SHOP_B).get(B_DISH);
        assertThat(text(dish, "imageUrl")).as(dish.toString()).isEqualTo(assetUrl(B_DISH));
        assertThat(urls(dish)).as(dish.toString()).containsExactly(assetUrl(B_DISH));
    }

    @Test
    @DisplayName("29-29 — case 11: an unpublished shop's menu and config are 404 and never name its asset")
    void case11_unpublishedShopHidden() throws Exception {
        for (String path : List.of("/products", "/config")) {
            String body = body("/api/v1/public/shops/" + SHOP_HIDDEN + path, 404);
            assertThat(body).as(path).doesNotContain(ASSET_ID.get(HIDDEN_DISH).toString());
        }
    }

    // ------------------------------------------------------------------
    // Measurements
    // ------------------------------------------------------------------

    @Test
    @DisplayName("29-29 — case 12: as the downgraded role, an unpinned read sees no media rows; a pin sees only its own tenant's")
    void case12_rlsMeasurement() throws Exception {
        int unpinnedAssets = count(null, "SELECT count(*) FROM media_asset");
        int unpinnedJoins = count(null, "SELECT count(*) FROM product_media");
        int aAssets = count(TENANT_A, "SELECT count(*) FROM media_asset");
        int aJoins = count(TENANT_A, "SELECT count(*) FROM product_media");
        int aSeesB = count(TENANT_A, "SELECT count(*) FROM media_asset WHERE id = '" + ASSET_ID.get(B_DISH) + "'");
        int bAssets = count(TENANT_B, "SELECT count(*) FROM media_asset");
        int bJoins = count(TENANT_B, "SELECT count(*) FROM product_media");
        int bSeesA = count(TENANT_B, "SELECT count(*) FROM media_asset WHERE tenant_id = '" + TENANT_A + "'");
        System.out.println("29-29 CASE12 unpinned media_asset=" + unpinnedAssets + " product_media=" + unpinnedJoins
                + " | pinned A media_asset=" + aAssets + " (seeded " + tenantAAssetCount + ") product_media=" + aJoins
                + " (seeded " + tenantAJoinCount + ") B-asset-visible=" + aSeesB
                + " | pinned B media_asset=" + bAssets + " product_media=" + bJoins + " A-assets-visible=" + bSeesA);

        assertThat(unpinnedAssets).as("unpinned media_asset").isZero();
        assertThat(unpinnedJoins).as("unpinned product_media").isZero();
        assertThat(aAssets).as("pinned A media_asset (a 0 here would mean the storefront needs another policy)")
                .isEqualTo(tenantAAssetCount);
        assertThat(aJoins).as("pinned A product_media").isEqualTo(tenantAJoinCount);
        assertThat(aSeesB).as("B's asset under A's pin").isZero();
        assertThat(bAssets).as("pinned B media_asset").isEqualTo(1);
        assertThat(bJoins).as("pinned B product_media").isEqualTo(1);
        assertThat(bSeesA).as("A's assets under B's pin").isZero();
    }

    @Test
    @DisplayName("29-29 — case 13: a menu read resolves every image in one query (prepared-statement deltas equal)")
    void case13_oneQueryPerMenu() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        // Warm both paths first so neither measurement pays a one-off cost the other does not.
        menu(SHOP_A);
        menu(SHOP_B);

        statistics.clear();
        Map<String, JsonNode> menuA = menu(SHOP_A);
        long deltaA = statistics.getPrepareStatementCount();
        statistics.clear();
        Map<String, JsonNode> menuB = menu(SHOP_B);
        long deltaB = statistics.getPrepareStatementCount();

        long mediaBearingA = SHOP_A_TITLES.stream().filter(t -> ASSET_ID.containsKey(t) || t.equals(FOREIGN_JOIN_DISH))
                .count();
        System.out.println("29-29 CASE13 prepared-statement delta shopA=" + deltaA + " (products=" + menuA.size()
                + ", media-bearing=" + mediaBearingA + ") shopB=" + deltaB + " (products=" + menuB.size() + ")");
        assertThat(mediaBearingA).as("the measurement needs at least five media-bearing products").isGreaterThanOrEqualTo(5);
        assertThat(menuA).hasSize(SHOP_A_TITLES.size());
        assertThat(menuB).hasSize(1);
        assertThat(deltaA).as("statements counted at all").isPositive();
        assertThat(deltaA).as("shop A's menu (many media products) vs shop B's (one)").isEqualTo(deltaB);
    }

    @Test
    @DisplayName("29-29 — case 14: every shop-A product's imageUrl is the vendor rule's key unless flagged or quarantine-shaped, else image_url")
    void case14_vendorRuleOracle() throws Exception {
        Map<String, JsonNode> menu = menu(SHOP_A);
        for (String title : SHOP_A_TITLES) {
            JsonNode dish = menu.get(title);
            assertThat(dish).as("listed: " + title).isNotNull();
            Optional<String> key = vendorRuleKey(title);
            String expected;
            if (key.isPresent() && !FLAGGED_FIXTURES.contains(title) && !isQuarantineShaped(key.get())) {
                expected = PUBLIC_URL + "/" + key.get();
            } else {
                expected = LEGACY_URL.get(title);
            }
            String actual = text(dish, "imageUrl");
            assertThat(actual).as(title + " (vendor key " + key.orElse("none") + ") " + dish).isEqualTo(expected);
            if (actual != null && !actual.isBlank()) {
                assertThat(urls(dish)).as(title + " " + dish).isNotEmpty();
                assertThat(urls(dish).get(0)).as(title + " imageUrls[0]").isEqualTo(actual);
            }
        }
    }

    // ---- helpers ----

    private static boolean isQuarantineShaped(String key) {
        int first = key.indexOf('/');
        return first > 0 && key.startsWith("quarantine/", first + 1);
    }

    private static String assetUrl(String title) {
        return PUBLIC_URL + "/" + ASSET_KEY.get(title);
    }

    private Optional<String> vendorRuleKey(String title) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx.execute(status -> {
            TenantContext.set(TENANT_A);
            try {
                return productMediaRepository.findPrimaryActiveObjectKey(PRODUCT_IDS.get(title));
            } finally {
                TenantContext.clear();
            }
        });
    }

    private Map<String, JsonNode> menu(String slug) throws Exception {
        return index(getJson("/api/v1/public/shops/" + slug + "/products", 200));
    }

    /** The menu JSON is category -> products; index every product by title across the groups. */
    private static Map<String, JsonNode> index(JsonNode grouped) {
        Map<String, JsonNode> byTitle = new LinkedHashMap<>();
        for (JsonNode group : grouped) {
            for (JsonNode product : group) {
                byTitle.put(product.get("title").asString(), product);
            }
        }
        return byTitle;
    }

    private JsonNode getJson(String path, int status) throws Exception {
        return jsonMapper.readTree(body(path, status));
    }

    private String body(String path, int status) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(path + " " + body).isEqualTo(status);
        return body;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.isMissingNode() ? null : value.asString();
    }

    private static List<String> urls(JsonNode node) {
        JsonNode array = node.get("imageUrls");
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(v -> values.add(v.asString()));
        }
        return values;
    }

    /** count(*) as the downgraded role inside one rolled-back transaction; tenant null = GUC unset. */
    private int count(UUID tenant, String sql) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (tenant != null) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                        ps.setString(1, tenant.toString());
                        ps.execute();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } finally {
                c.rollback();
            }
        }
    }

    // ---- seeding (bootstrap superuser, before the downgrade) ----

    private void insertTenant(UUID id, String name) {
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                id, name);
    }

    private UUID insertShop(UUID tenant, String slug, boolean published) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                id, tenant, "29-29 " + slug, slug, published);
        return id;
    }

    private void insertProduct(UUID tenant, UUID shop, String title, String imageUrl, List<String> gallery) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, shop_id, "
                        + "quantity_in_stock, vat_rate, version, image_url) "
                        + "VALUES (?, ?, now(), ?, ?, '', 0, 500, 0, true, false, ?, 100, 'STANDARD', 0, ?)",
                id, tenant, "SKU-2929-" + id, title, shop, imageUrl);
        if (!gallery.isEmpty()) {
            jdbcTemplate.update("UPDATE products SET additional_image_urls = string_to_array(?, '|') WHERE id = ?",
                    String.join("|", gallery), id);
        }
        PRODUCT_IDS.put(title, id);
        LEGACY_URL.put(title, imageUrl);
        GALLERY.put(title, gallery);
    }

    /** One media_asset row and the product's primary join row naming it. */
    private void primaryAsset(UUID tenant, String title, String status, boolean flagged, boolean quarantineKey) {
        UUID assetId = UUID.randomUUID();
        String key = quarantineKey
                ? tenant + "/quarantine/" + assetId + ".png"
                : tenant + "/media/" + assetId + ".webp";
        jdbcTemplate.update("INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type, status, flagged) "
                        + "VALUES (?, ?, ?, ?, 'image/webp', ?, ?)",
                assetId, tenant, key, String.format("%064x", ++shaCounter), status, flagged);
        insertJoin(tenant, PRODUCT_IDS.get(title), assetId);
        ASSET_ID.put(title, assetId);
        ASSET_KEY.put(title, key);
        if (TENANT_A.equals(tenant)) {
            tenantAAssetCount++;
            tenantAJoinCount++;
        }
    }

    private void insertJoin(UUID tenant, UUID productId, UUID assetId) {
        jdbcTemplate.update("INSERT INTO product_media (id, tenant_id, product_id, asset_id, is_primary, sort_order) "
                + "VALUES (?, ?, ?, ?, true, 0)", UUID.randomUUID(), tenant, productId, assetId);
    }
}
