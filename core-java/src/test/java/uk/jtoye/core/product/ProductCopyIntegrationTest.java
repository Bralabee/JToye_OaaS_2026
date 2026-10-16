package uk.jtoye.core.product;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.storage.StorageService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 37-13 (RWO-026 / UXT-026): a vendor copies a menu from one shop to another, and every allergen fact
 * arrives intact.
 *
 * <p>The persona run: a multi-site owner opening a second shop had no way to reuse the first shop's menu, so
 * the only route was retyping every product — allergens included, by hand, which is exactly where an
 * allergen gets dropped. {@code POST /api/v1/products/copy} copies through the one domain create path
 * ({@code ProductService.createProduct}, theme 6).
 *
 * <p>The rule this pins:
 * <ul>
 *   <li>SHOP_MANAGER on BOTH shops; a shop the caller does not manage is the typed 403 and nothing is
 *       written. Source and target must differ (400).</li>
 *   <li>Every allergen fact is carried VERBATIM, never derived: the declared mask, the stored emphasis
 *       spans (even when they do not match what the ingredients text would parse to, and even when NULL),
 *       the may-contain mask, the ingredients text; and the price, the VAT rate, the label fields and the
 *       photos too. Each is read back from BOTH rows by SQL.</li>
 *   <li>Photos are shared copy-on-write: the copy gets NEW {@code product_media} links to the SAME
 *       {@code media_asset} rows, so no object is duplicated, and removing the copy's photo neither drops
 *       the source's link nor deletes an object the source still shows.</li>
 *   <li>Idempotent: a product the target already has (same copy SKU, or the same title ignoring case) is
 *       skipped, so repeating a copy creates nothing; the target shop row is locked for the copy, so two
 *       concurrent copies cannot both create.</li>
 *   <li>A requested product that is not in the source shop is a per-product FAILED outcome; the rest copy.</li>
 * </ul>
 *
 * <p>Real Postgres 15 (Testcontainers). The bootstrap role is a SUPERUSER, so these arms prove the
 * application contract and the shop gate, not RLS. {@link StorageService} is a spy with the physical
 * deletes stubbed, so "no object deleted" is asserted without a live object store.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class ProductCopyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired ShopAccessService shopAccessService;
    @MockitoSpyBean StorageService storageService;

    private static final String COPY = "/api/v1/products/copy";
    private static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";

    /** Columns that must be identical between a source product and its copy (everything but id, shop and SKU). */
    private static final String FIDELITY_COLUMNS = "title, ingredients_text, allergen_mask, allergen_spans::text AS allergen_spans, "
            + "may_contain_mask, price_pennies, vat_rate, description, image_url, "
            + "additional_image_urls::text AS additional_image_urls, category, display_order, available, featured, "
            + "preparation_time_minutes, dietary_tags, quantity_in_stock, shelf_life_days, durability_type";

    private Object bootedStrictScoping;
    private UUID tenantId;
    private String suffix;
    private UUID shopA;
    private UUID shopB;
    private UUID shopC;
    private UUID managerOfAandB;
    private UUID managerOfAOnly;

    /** Suya: mask 64 (milk), spans stored as [0,5] (NOT what its text parses to), may-contain 1024, ZERO-rated, photos. */
    private UUID suya;
    /** Puff Puff: emphasised text but allergen_spans NULL (an imported row) — a derived copy would gain spans. */
    private UUID puffPuff;
    /** In shop A but not requested: must not be copied by an explicit productIds request. */
    private UUID zobo;
    private UUID primaryAsset;
    private UUID galleryAsset;
    private String sharedImageUrl;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
        StrictScopingGuard.set(shopAccessService, true);
        // No live object store: the physical deletes are no-ops, observed by the spy.
        Mockito.doNothing().when(storageService).deleteByKey(Mockito.anyString());
        Mockito.doReturn(true).when(storageService).delete(Mockito.anyString());

        tenantId = UUID.randomUUID();
        suffix = tenantId.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())",
                tenantId, "37-13 Copy Tenant " + suffix);
        shopA = seedShop("copy-a-3713-" + suffix, "37-13 Copy Shop A " + suffix);
        shopB = seedShop("copy-b-3713-" + suffix, "37-13 Copy Shop B " + suffix);
        shopC = seedShop("copy-c-3713-" + suffix, "37-13 Copy Shop C " + suffix);

        managerOfAandB = UUID.randomUUID();
        grant(managerOfAandB, shopA, "SHOP_MANAGER");
        grant(managerOfAandB, shopB, "SHOP_MANAGER");
        managerOfAOnly = UUID.randomUUID();
        grant(managerOfAOnly, shopA, "SHOP_MANAGER");

        sharedImageUrl = "https://blob.example/" + tenantId + "/products/suya.webp";
        suya = seedProduct(shopA, "SUYA-" + suffix, "Suya", "Beef, **milk**, yaji spice", 64,
                "'[{\"start\": 0, \"end\": 5}]'::jsonb", 1024, 900L, "ZERO", sharedImageUrl,
                new String[] { "https://blob.example/" + tenantId + "/products/suya-2.webp" }, 2, "USE_BY", 12);
        puffPuff = seedProduct(shopA, "PUFF-" + suffix, "Puff Puff", "Flour, **egg**, sugar", 4,
                "NULL", null, 250L, "STANDARD", null, new String[0], null, null, null);
        zobo = seedProduct(shopA, "ZOBO-" + suffix, "Zobo", "Hibiscus, ginger", 0,
                "'[]'::jsonb", 0, 300L, "REDUCED", null, new String[0], null, null, null);

        primaryAsset = seedAsset("suya-primary");
        galleryAsset = seedAsset("suya-gallery");
        link(suya, primaryAsset, true, 0);
        link(suya, galleryAsset, false, 1);
    }

    @AfterEach
    void tearDown() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Copy, with allergen fidelity read from both rows by SQL.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UXT-026: copying two products A->B creates two copies in B whose allergen mask, spans, may-contain mask, ingredients, price and VAT rate equal the source by SQL")
    void copyAtoB_carriesEveryAllergenFactVerbatim() throws Exception {
        MvcResult result = copy(userJwt(managerOfAandB), shopA, shopB, suya, puffPuff);
        JsonNode body = json(result, 200);
        assertThat(body.path("created").asInt()).as(text(result)).isEqualTo(2);
        assertThat(body.path("skipped").asInt()).as(text(result)).isZero();
        assertThat(body.path("failed").asInt()).as(text(result)).isZero();
        assertThat(body.path("items").size()).as(text(result)).isEqualTo(2);

        for (UUID source : List.of(suya, puffPuff)) {
            UUID copyId = newProductIdFor(body, source);
            assertThat(copyId).as("item for source %s carries the new product id: %s", source, text(result)).isNotNull();
            assertThat(fidelityRow(copyId)).as("every carried column of the copy equals the source, by SQL")
                    .isEqualTo(fidelityRow(source));
            assertThat(shopOf(copyId)).as("the copy is in shop B").isEqualTo(shopB.toString());
        }
        // The headline facts, named, so a regression reads as what it is.
        UUID suyaCopy = newProductIdFor(body, suya);
        assertThat(fidelityRow(suyaCopy).get("may_contain_mask")).isEqualTo(1024);
        assertThat(fidelityRow(suyaCopy).get("allergen_spans").toString()).isEqualTo("[{\"end\": 5, \"start\": 0}]");
        assertThat(fidelityRow(newProductIdFor(body, puffPuff)).get("allergen_spans"))
                .as("NULL spans stay NULL: the copy is never re-derived from the ingredients text").isNull();
        assertThat(productTitlesIn(shopB)).as("only the requested products were copied")
                .containsExactlyInAnyOrder("Suya", "Puff Puff");
        assertThat(fidelityRow(suya)).as("the source is untouched").containsEntry("allergen_mask", 64);
    }

    @Test
    @DisplayName("Copied photos are new product_media links to the SAME media_asset rows (no object duplicated)")
    void copiedMedia_linksTheSameAssets() throws Exception {
        long assetsBefore = assetCount();
        JsonNode body = json(copy(userJwt(managerOfAandB), shopA, shopB, suya), 200);
        UUID suyaCopy = newProductIdFor(body, suya);

        assertThat(mediaLinks(suyaCopy)).as("the copy links the same assets, same primary and order")
                .isEqualTo(mediaLinks(suya))
                .containsExactly(primaryAsset + "|true|0", galleryAsset + "|false|1");
        assertThat(assetCount()).as("no media_asset row was duplicated").isEqualTo(assetsBefore);
    }

    @Test
    @DisplayName("A later edit of the copy never changes the source: removing the copy's photo keeps the source's links, assets and objects")
    void editingTheCopy_neverChangesTheSource() throws Exception {
        JsonNode body = json(copy(userJwt(managerOfAandB), shopA, shopB, suya), 200);
        UUID suyaCopy = newProductIdFor(body, suya);
        Map<String, Object> sourceBefore = fidelityRow(suya);

        MvcResult removed = mockMvc.perform(delete("/api/v1/products/" + suyaCopy + "/image")
                .with(userJwt(managerOfAandB))).andReturn();
        assertThat(removed.getResponse().getStatus()).as(text(removed)).isEqualTo(200);

        assertThat(mediaLinks(suya)).as("the source keeps both of its links")
                .containsExactly(primaryAsset + "|true|0", galleryAsset + "|false|1");
        assertThat(assetStatus(primaryAsset)).as("the shared primary asset still exists").isEqualTo("ACTIVE");
        Mockito.verify(storageService, Mockito.never()).deleteByKey(Mockito.anyString());
        Mockito.verify(storageService, Mockito.never()).delete(sharedImageUrl);
        assertThat(fidelityRow(suya)).as("the source row is unchanged").isEqualTo(sourceBefore);
    }

    // ------------------------------------------------------------------
    // Idempotency.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Repeating the copy creates nothing: created 0, skipped 2")
    void repeatingTheCopy_createsNothing() throws Exception {
        json(copy(userJwt(managerOfAandB), shopA, shopB, suya, puffPuff), 200);
        MvcResult again = copy(userJwt(managerOfAandB), shopA, shopB, suya, puffPuff);
        JsonNode body = json(again, 200);
        assertThat(body.path("created").asInt()).as(text(again)).isZero();
        assertThat(body.path("skipped").asInt()).as(text(again)).isEqualTo(2);
        for (JsonNode item : body.path("items")) {
            assertThat(item.path("outcome").asString()).as(text(again)).isEqualTo("SKIPPED");
        }
        assertThat(productTitlesIn(shopB)).as("still exactly one copy of each").hasSize(2);
    }

    @Test
    @DisplayName("A product the target already has by title (ignoring case) is skipped, not duplicated")
    void targetAlreadyHasTheTitle_isSkipped() throws Exception {
        seedProduct(shopB, "OWN-SUYA-" + suffix, "suya", "Beef", 0, "NULL", null, 800L, "STANDARD",
                null, new String[0], null, null, null);
        MvcResult result = copy(userJwt(managerOfAandB), shopA, shopB, suya, puffPuff);
        JsonNode body = json(result, 200);
        assertThat(body.path("created").asInt()).as(text(result)).isEqualTo(1);
        assertThat(body.path("skipped").asInt()).as(text(result)).isEqualTo(1);
        assertThat(outcomeFor(body, suya)).as(text(result)).isEqualTo("SKIPPED");
        assertThat(productTitlesIn(shopB)).containsExactlyInAnyOrder("suya", "Puff Puff");
    }

    @Test
    @DisplayName("T-37-37: two concurrent copies of the same products create exactly one copy of each, and both succeed")
    void concurrentCopies_createExactlyOneCopyOfEach() throws Exception {
        for (int round = 0; round < 3; round++) {
            UUID target = seedShop("copy-t" + round + "-3713-" + suffix, "37-13 Target " + round + " " + suffix);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<MvcResult>> calls = new ArrayList<>();
                for (int t = 0; t < 2; t++) {
                    calls.add(pool.submit(() -> {
                        start.await();
                        return copy(adminJwt(), shopA, target, suya, puffPuff);
                    }));
                }
                start.countDown();
                int created = 0;
                int skipped = 0;
                for (Future<MvcResult> call : calls) {
                    MvcResult result = call.get(60, TimeUnit.SECONDS);
                    JsonNode body = json(result, 200);
                    created += body.path("created").asInt();
                    skipped += body.path("skipped").asInt();
                }
                assertThat(created).as("round %d: exactly one copy of each product", round).isEqualTo(2);
                assertThat(skipped).as("round %d: the other copy skipped both", round).isEqualTo(2);
                assertThat(productTitlesIn(target)).as("round %d", round).containsExactlyInAnyOrder("Suya", "Puff Puff");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ------------------------------------------------------------------
    // Gates and per-product failures.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T-37-35: copying into a shop the caller does not manage is a typed 403 and writes nothing")
    void copyIntoAnUnmanagedShop_isRefused() throws Exception {
        MvcResult result = copy(userJwt(managerOfAOnly), shopA, shopB, suya, puffPuff);
        assertThat(json(result, 403).path("type").asString()).as(text(result)).isEqualTo(SHOP_ACCESS_DENIED);
        assertThat(productTitlesIn(shopB)).as("nothing was written to B").isEmpty();
    }

    @Test
    @DisplayName("Copying from a shop the caller does not manage is a typed 403")
    void copyFromAnUnmanagedShop_isRefused() throws Exception {
        MvcResult result = copy(userJwt(managerOfAOnly), shopB, shopA);
        assertThat(json(result, 403).path("type").asString()).as(text(result)).isEqualTo(SHOP_ACCESS_DENIED);
    }

    @Test
    @DisplayName("Source and target must differ (400)")
    void sameSourceAndTarget_isABadRequest() throws Exception {
        MvcResult result = copy(userJwt(managerOfAandB), shopA, shopA, suya);
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(400);
        assertThat(productTitlesIn(shopA)).hasSize(3);
    }

    @Test
    @DisplayName("A requested product that is not in the source shop is a FAILED item; the others copy")
    void productFromAnotherShop_isAFailedItem() throws Exception {
        UUID inC = seedProduct(shopC, "C-ONLY-" + suffix, "Moin Moin", "Beans", 0, "NULL", null, 400L, "STANDARD",
                null, new String[0], null, null, null);
        MvcResult result = copy(adminJwt(), shopA, shopB, suya, inC);
        JsonNode body = json(result, 200);
        assertThat(body.path("created").asInt()).as(text(result)).isEqualTo(1);
        assertThat(body.path("failed").asInt()).as(text(result)).isEqualTo(1);
        assertThat(outcomeFor(body, inC)).as(text(result)).isEqualTo("FAILED");
        assertThat(productTitlesIn(shopB)).containsExactly("Suya");
    }

    @Test
    @DisplayName("Without productIds the whole source menu is copied")
    void noProductIds_copiesTheWholeMenu() throws Exception {
        MvcResult result = copy(userJwt(managerOfAandB), shopA, shopB);
        assertThat(json(result, 200).path("created").asInt()).as(text(result)).isEqualTo(3);
        assertThat(productTitlesIn(shopB)).containsExactlyInAnyOrder("Suya", "Puff Puff", "Zobo");
        assertThat(zobo).isNotNull();
    }

    @Test
    @DisplayName("The copies show on shop B's public storefront")
    void copiesShowOnTheTargetStorefront() throws Exception {
        json(copy(userJwt(managerOfAandB), shopA, shopB, suya, puffPuff), 200);
        MvcResult menu = mockMvc.perform(get("/api/v1/public/shops/copy-b-3713-" + suffix + "/products")).andReturn();
        List<String> titles = new ArrayList<>();
        json(menu, 200).forEach(category -> category.forEach(p -> titles.add(p.path("title").asString())));
        assertThat(titles).containsExactlyInAnyOrder("Suya", "Puff Puff");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private MvcResult copy(RequestPostProcessor auth, UUID source, UUID target, UUID... productIds) throws Exception {
        StringBuilder json = new StringBuilder("{\"sourceShopId\":\"").append(source)
                .append("\",\"targetShopId\":\"").append(target).append('"');
        if (productIds.length > 0) {
            json.append(",\"productIds\":[");
            for (int i = 0; i < productIds.length; i++) {
                json.append(i == 0 ? "" : ",").append('"').append(productIds[i]).append('"');
            }
            json.append(']');
        }
        json.append('}');
        return mockMvc.perform(post(COPY).contentType(MediaType.APPLICATION_JSON).content(json.toString()).with(auth))
                .andReturn();
    }

    private static UUID newProductIdFor(JsonNode body, UUID source) {
        for (JsonNode item : body.path("items")) {
            if (source.toString().equals(item.path("sourceProductId").asString())) {
                String id = item.path("newProductId").asString();
                return id == null || id.isEmpty() ? null : UUID.fromString(id);
            }
        }
        return null;
    }

    private static String outcomeFor(JsonNode body, UUID source) {
        for (JsonNode item : body.path("items")) {
            if (source.toString().equals(item.path("sourceProductId").asString())) {
                return item.path("outcome").asString();
            }
        }
        return null;
    }

    private Map<String, Object> fidelityRow(UUID productId) {
        return new LinkedHashMap<>(jdbcTemplate.queryForMap(
                "SELECT " + FIDELITY_COLUMNS + " FROM products WHERE id = ?", productId));
    }

    private String shopOf(UUID productId) {
        return jdbcTemplate.queryForObject("SELECT shop_id::text FROM products WHERE id = ?", String.class, productId);
    }

    private List<String> productTitlesIn(UUID shop) {
        return jdbcTemplate.queryForList("SELECT title FROM products WHERE tenant_id = ? AND shop_id = ?",
                String.class, tenantId, shop);
    }

    private List<String> mediaLinks(UUID productId) {
        return jdbcTemplate.queryForList(
                "SELECT asset_id::text || '|' || is_primary || '|' || sort_order FROM product_media "
                        + "WHERE product_id = ? ORDER BY is_primary DESC, sort_order", String.class, productId);
    }

    private long assetCount() {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM media_asset WHERE tenant_id = ?", Long.class, tenantId);
        return n == null ? -1 : n;
    }

    private String assetStatus(UUID assetId) {
        List<String> s = jdbcTemplate.queryForList("SELECT status FROM media_asset WHERE id = ?", String.class, assetId);
        return s.isEmpty() ? null : s.get(0);
    }

    private UUID seedShop(String slug, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                id, tenantId, name, slug);
        return id;
    }

    private UUID seedProduct(UUID shop, String sku, String title, String ingredients, int allergenMask,
                             String spansSql, Integer mayContain, long price, String vatRate, String imageUrl,
                             String[] gallery, Integer shelfLife, String durability, Integer stock) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement("INSERT INTO products (id, tenant_id, shop_id, sku, title, ingredients_text, "
                    + "allergen_mask, allergen_spans, may_contain_mask, price_pennies, vat_rate, image_url, "
                    + "additional_image_urls, shelf_life_days, durability_type, quantity_in_stock, category, "
                    + "description, dietary_tags, preparation_time_minutes, display_order, available, featured) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, " + spansSql + ", ?, ?, ?, ?, ?, ?, ?, ?, 'Grill', "
                    + "'Smoky and hot', 'Halal', 15, 3, true, true)");
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, shop);
            ps.setString(4, sku);
            ps.setString(5, title);
            ps.setString(6, ingredients);
            ps.setInt(7, allergenMask);
            ps.setObject(8, mayContain);
            ps.setLong(9, price);
            ps.setString(10, vatRate);
            ps.setString(11, imageUrl);
            ps.setArray(12, con.createArrayOf("text", gallery));
            ps.setObject(13, shelfLife);
            ps.setString(14, durability);
            ps.setObject(15, stock);
            return ps;
        });
        return id;
    }

    private UUID seedAsset(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type, status) "
                        + "VALUES (?, ?, ?, ?, 'image/webp', 'ACTIVE')",
                id, tenantId, tenantId + "/media/" + name + ".webp",
                String.format("%064x", Math.abs((long) (name + suffix).hashCode())));
        return id;
    }

    private void link(UUID productId, UUID assetId, boolean primary, int sortOrder) {
        jdbcTemplate.update("INSERT INTO product_media (id, tenant_id, product_id, asset_id, is_primary, sort_order) "
                + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), tenantId, productId, assetId, primary, sortOrder);
    }

    private void grant(UUID userId, UUID shop, String role) {
        jdbcTemplate.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at, grant_source) "
                + "VALUES (?, ?, ?, ?, ?, now(), 'OPERATOR')", UUID.randomUUID(), tenantId, userId, shop, role);
    }

    /** A realm admin of the tenant (implicit Group admin) holding the catalogue write scope. */
    private RequestPostProcessor adminJwt() {
        UUID tenant = tenantId;
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_catalog:write"));
    }

    /** A vendor user with no realm role: what they may write comes from shop_staff alone. */
    private RequestPostProcessor userJwt(UUID subject) {
        UUID tenant = tenantId;
        return jwt().jwt(j -> j.subject(subject.toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_catalog:write"));
    }

    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(expectedStatus);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String text(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }
}
