package uk.jtoye.core.product;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the live product search path (Issue #96, P2-5).
 *
 * The search endpoint moved from an unpaginated leading-wildcard LIKE to the
 * V25 GIN/tsvector full-text machinery with a prefix tsquery plus an anchored
 * SKU-prefix branch. These tests prove, on real Postgres with FORCE RLS and a
 * NOSUPERUSER role:
 *
 * <ul>
 *   <li>FTS relevance: multi-word AND semantics, weighted ranking (title above
 *       description), case-insensitivity, per-word prefix matching ("chick"
 *       finds "Chicken"), and coverage of category/ingredients/dietary tags;</li>
 *   <li>SKU prefix lookup preserved (SKU is not in the tsvector) with LIKE
 *       wildcards in user input treated literally;</li>
 *   <li>tsquery-operator injection safety and empty-page behaviour for blank
 *       or punctuation-only queries;</li>
 *   <li>pagination on the search path and the global 100 page-size cap at the
 *       web layer (frozen wire contract: bare JSON array);</li>
 *   <li>RLS tenant isolation on both the FTS and SKU branches;</li>
 *   <li>the GIN-index story (EXPLAIN plans) and the V25 trigger keeping
 *       search_vector fresh on UPDATE;</li>
 *   <li>the V44 backfill making trigger-bypassed NULL-vector rows searchable
 *       with vectors identical to trigger output (products AND shops).</li>
 * </ul>
 *
 * <p><strong>GIN index under RLS (#96 finding, CLOSED by V44):</strong>
 * Postgres refuses non-LEAKPROOF operators as index quals beneath a
 * row-security barrier, and {@code ts_match_vq} (the {@code @@} function)
 * ships {@code proleakproof=f}, so the FTS branch used to planner-degrade to a
 * tenant-filtered seq scan for the RLS-bound app role. V44 applies
 * {@code ALTER FUNCTION pg_catalog.ts_match_vq(tsvector, tsquery) LEAKPROOF},
 * after which the identical SQL is served by a Bitmap Index Scan on
 * {@code idx_products_search} / {@code idx_shops_search} even under FORCE RLS.
 * The former tripwire test now REQUIRES the index in the RLS-bound plan (both
 * FTS paths), pinning the fix the same way it pinned the defect.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Transactional
@org.junit.jupiter.api.Tag("testcontainers")
// #283: the search path runs through productService.search (gated read-scope). The subject is
// full-text search correctness, not authorization.
@uk.jtoye.core.testsupport.AsSystemHarness
class ProductSearchFtsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ShopRepository shopRepository;

    // Per-tenant demo shop cache (phase 19 UIX-05 tripwire): every seeded product
    // now carries a non-null shop_id so this suite no longer relies on the removed
    // NULL-shop_id bleed. shop_id is not part of the FTS query, so the pinned
    // query-plan tests are unaffected. Reset per test method (JUnit new instance).
    private final Map<UUID, UUID> tenantShopIds = new HashMap<>();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager entityManager;

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT_A, "FTS Tenant A");
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT_B, "FTS Tenant B");
    }

    /**
     * FORCE ROW LEVEL SECURITY does NOT bind a SUPERUSER — the Testcontainers
     * bootstrap role bypasses RLS entirely, so without this downgrade the
     * isolation assertions would see cross-tenant rows (QA-council #71).
     * Safe here because: (1) Flyway already ran at context boot as superuser;
     * (2) ALTER ROLE is transactional and this class is @Transactional, so
     * the per-test rollback restores superuser afterwards; (3) inserts still
     * pass WITH CHECK because TenantSetLocalAspect sets the tenant GUC
     * before each repository op inside the test transaction.
     *
     * <p>Called at the top of every functional test (production parity: the
     * app role is never a superuser). The one test that must observe the
     * barrier-free plan stays superuser and skips this.
     */
    private void enforceRls() {
        jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- FTS relevance ----

    @Test
    void multiWordQueryRequiresAllWordsAndRanksTitleMatchesFirst() {
        enforceRls();
        seedStandardCatalog(TENANT_A);
        TenantContext.set(TENANT_A);

        // AND semantics: both words must match, so only "Chicken Curry" qualifies
        List<ProductDto> chickenCurry = search("chicken curry");
        assertThat(chickenCurry).extracting(ProductDto::getTitle)
                .containsExactly("Chicken Curry");

        // Weighted ranking: title matches (weight A) rank above a
        // description-only match (weight C); equal ranks tie-break by title.
        List<ProductDto> curry = search("curry");
        assertThat(curry).extracting(ProductDto::getTitle)
                .containsExactly("Beef Curry", "Chicken Curry", "House Special");
    }

    @Test
    void searchIsCaseInsensitive() {
        enforceRls();
        seedStandardCatalog(TENANT_A);
        TenantContext.set(TENANT_A);

        assertThat(search("CHICKEN")).extracting(ProductDto::getTitle)
                .containsExactly("Chicken Curry");
        assertThat(search("cHiCkEn CuRrY")).extracting(ProductDto::getTitle)
                .containsExactly("Chicken Curry");
    }

    @Test
    void prefixMatchesPartialWords() {
        enforceRls();
        seedStandardCatalog(TENANT_A);
        TenantContext.set(TENANT_A);

        assertThat(search("chick")).extracting(ProductDto::getTitle)
                .containsExactly("Chicken Curry");
        assertThat(search("choc")).extracting(ProductDto::getTitle)
                .containsExactly("Chocolate Cake");
    }

    @Test
    void searchCoversCategoryIngredientsAndDietaryTags() {
        enforceRls();
        seedStandardCatalog(TENANT_A);
        TenantContext.set(TENANT_A);

        // category (weight B)
        assertThat(search("dessert")).extracting(ProductDto::getTitle)
                .containsExactly("Chocolate Cake");
        // ingredients_text (weight C) — stemming: "almonds" indexed as "almond"
        assertThat(search("almond")).extracting(ProductDto::getTitle)
                .containsExactly("Chocolate Cake");
        // dietary_tags (weight D)
        assertThat(search("vegetarian")).extracting(ProductDto::getTitle)
                .containsExactly("Chocolate Cake");
    }

    // ---- SKU branch ----

    @Test
    void skuPrefixLookupIsPreserved() {
        enforceRls();
        seedStandardCatalog(TENANT_A);
        TenantContext.set(TENANT_A);

        // SKU is not in the V25 tsvector — the anchored SKU branch must serve it
        assertThat(search("CHK-0")).extracting(ProductDto::getSku)
                .containsExactly("CHK-001");
        assertThat(search("chk-001")).extracting(ProductDto::getSku)
                .containsExactly("CHK-001");
    }

    @Test
    void likeWildcardsInQueryMatchLiterallyNotAsWildcards() {
        enforceRls();
        seedStandardCatalog(TENANT_A);
        TenantContext.set(TENANT_A);

        // Old LIKE path: '%' matched every product. Now it sanitises to nothing.
        assertThat(search("%")).isEmpty();
        // '_' must not act as a single-character wildcard against "CHK-001" etc.
        assertThat(search("CHK-00_")).isEmpty();
    }

    // ---- Robustness ----

    @Test
    void tsqueryOperatorsInUserInputAreHarmless() {
        enforceRls();
        seedStandardCatalog(TENANT_A);
        TenantContext.set(TENANT_A);

        // Operators are stripped before to_tsquery, so no syntax error escapes
        assertThat(search("chicken & (curry")).extracting(ProductDto::getTitle)
                .containsExactly("Chicken Curry");
        assertThatCode(() -> search("!batter | (fish:*) <-> chips'); DROP TABLE products; --"))
                .doesNotThrowAnyException();
        // products table survived
        assertThat(search("chicken")).isNotEmpty();
    }

    @Test
    void blankAndPunctuationOnlyQueriesReturnEmptyPage() {
        enforceRls();
        seedStandardCatalog(TENANT_A);
        TenantContext.set(TENANT_A);

        assertThat(search("")).isEmpty();
        assertThat(search("   ")).isEmpty();
        assertThat(search("!!!")).isEmpty();
        assertThat(search("&|:*()")).isEmpty();
    }

    // ---- Pagination + cap ----

    @Test
    void searchPathPaginates() {
        enforceRls();
        for (int i = 0; i < 7; i++) {
            createProduct(TENANT_A, "BUN-00" + i, "Bun " + i, null, null, null);
        }
        TenantContext.set(TENANT_A);

        Page<ProductDto> page0 = productService.search("bun", PageRequest.of(0, 3));
        assertThat(page0.getContent()).hasSize(3);
        assertThat(page0.getTotalElements()).isEqualTo(7);
        assertThat(page0.getTotalPages()).isEqualTo(3);

        Page<ProductDto> page2 = productService.search("bun", PageRequest.of(2, 3));
        assertThat(page2.getContent()).hasSize(1);

        // No overlap between pages: rank ties are broken by title, then id
        Page<ProductDto> page1 = productService.search("bun", PageRequest.of(1, 3));
        assertThat(page1.getContent()).extracting(ProductDto::getId)
                .doesNotContainAnyElementsOf(page0.getContent().stream().map(ProductDto::getId).toList());
    }

    /**
     * Production-shaped auth for the HTTP search assertion: a UUID-subject Keycloak JWT
     * with the realm-admin authority (implicit GROUP_ADMIN), mirroring
     * {@code ShopAccessEnforcementIntegrationTest}. Replaces the pre-Phase-23
     * {@code WithMockUser}, whose non-JWT principal the fail-closed {@code ShopAccessService}
     * (23-08) now denies on the product read-scope gate — the reason this method regressed
     * to 403. A realm admin reads the tenant-wide set unrestricted, so the 100-item cap and
     * bare-array contract are asserted exactly as before.
     */
    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j
                        .subject(UUID.randomUUID().toString())
                        .claim("email", "operator@example.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"));
    }

    @Test
    void searchEndpointCapsPageSizeAtGlobalMaximumAndKeepsArrayContract() throws Exception {
        enforceRls();
        for (int i = 0; i < 101; i++) {
            createProduct(TENANT_A, String.format("MUF-%03d", i), "Muffin " + i, null, null, null);
        }

        // Default page size on the search path is the global cap (100)
        mockMvc.perform(get("/api/v1/products/search")
                        .with(adminJwt())
                        .param("q", "muffin")
                        .header("X-Tenant-Id", TENANT_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(100)))
                // frozen wire contract: bare array of objects with id/sku/title
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].sku").exists())
                .andExpect(jsonPath("$[0].title").exists());

        // An oversized size request is clamped by spring.data.web.pageable.max-page-size
        mockMvc.perform(get("/api/v1/products/search")
                        .with(adminJwt())
                        .param("q", "muffin").param("size", "500")
                        .header("X-Tenant-Id", TENANT_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(100)));

        // Explicit paging works and stays a bare array
        mockMvc.perform(get("/api/v1/products/search")
                        .with(adminJwt())
                        .param("q", "muffin").param("page", "1").param("size", "100")
                        .header("X-Tenant-Id", TENANT_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get("/api/v1/products/search")
                        .with(adminJwt())
                        .param("q", "muffin").param("size", "5")
                        .header("X-Tenant-Id", TENANT_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
    }

    // ---- RLS isolation ----

    @Test
    void searchNeverReturnsAnotherTenantsProducts() {
        enforceRls();
        Product curryA = createProduct(TENANT_A, "CHK-A", "Chicken Curry", null, null, null);
        Product kormaB = createProduct(TENANT_B, "CHK-B", "Chicken Korma", null, null, null);

        TenantContext.set(TENANT_A);
        List<ProductDto> tenantAResults = search("chicken");
        assertThat(tenantAResults).extracting(ProductDto::getId)
                .contains(curryA.getId())
                .doesNotContain(kormaB.getId());

        // The SKU branch must be tenant-isolated too
        assertThat(search("CHK-")).extracting(ProductDto::getId)
                .containsExactly(curryA.getId());

        TenantContext.set(TENANT_B);
        List<ProductDto> tenantBResults = search("chicken");
        assertThat(tenantBResults).extracting(ProductDto::getId)
                .contains(kormaB.getId())
                .doesNotContain(curryA.getId());
    }

    // ---- Index usage + trigger freshness ----

    /**
     * Without the RLS security barrier (superuser bypasses it), the repository
     * SQL's FTS branch is served by a Bitmap Index Scan on idx_products_search —
     * i.e. the query SHAPE is index-compatible, and the UNION-by-id structure
     * (rather than a single OR-ed predicate) is what keeps it so.
     */
    @Test
    void queryPlanUsesGinIndexOnceRlsBarrierIsAbsent() {
        seedStandardCatalog(TENANT_A);
        TenantContext.set(TENANT_A);

        // Tiny tables make Postgres prefer a seq scan on cost alone; disabling
        // it (transaction-local) reveals whether the GIN index CAN serve the
        // FTS branch.
        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
        String plan = explainRepositorySql();

        assertThat(plan)
                .as("FTS branch must be served by the V25 GIN index when no RLS barrier applies:\n" + plan)
                .contains("idx_products_search");
    }

    /**
     * FLIPPED TRIPWIRE — V44 landed, so the GIN index is now REQUIRED under
     * RLS (#96 closure).
     *
     * <p>Until V44, ts_match_vq (the {@code @@} function) was not LEAKPROOF and
     * Postgres only allows leakproof operators as index quals beneath a
     * row-security barrier, so this test pinned the seq-scan degradation. V44's
     * {@code ALTER FUNCTION pg_catalog.ts_match_vq(tsvector, tsquery)
     * LEAKPROOF} flips the identical SQL to a Bitmap Index Scan on
     * idx_products_search for the RLS-bound app role — asserted here so any
     * regression (e.g. the ALTER silently skipped, a Postgres upgrade resetting
     * proleakproof) fails loudly.
     */
    @Test
    void v44LeakproofMakesGinIndexServeFtsUnderRls() {
        enforceRls();
        seedStandardCatalog(TENANT_A);
        TenantContext.set(TENANT_A);

        Boolean leakproof = jdbcTemplate.queryForObject(
                "SELECT proleakproof FROM pg_proc WHERE proname = 'ts_match_vq'", Boolean.class);
        assertThat(leakproof)
                .as("V44 must mark ts_match_vq LEAKPROOF (superuser ran Flyway here); "
                        + "if false, the GIN index is unreachable under RLS again")
                .isTrue();

        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
        String plan = explainRepositorySql();

        assertThat(plan)
                .as("With ts_match_vq LEAKPROOF (V44) the FTS branch must be served by "
                        + "idx_products_search even under FORCE RLS:\n" + plan)
                .contains("idx_products_search");
    }

    /**
     * Same #96 defect affected the live shops FTS
     * ({@code ShopRepository.fullTextSearchPublished}); V44 unlocks
     * idx_shops_search under RLS too. EXPLAIN of the exact repository SQL
     * shape, as the RLS-bound (NOSUPERUSER) role.
     */
    @Test
    void v44LeakproofMakesGinIndexServeShopsFtsUnderRls() {
        enforceRls();
        shopIdFor(TENANT_A); // shops table populated via the trigger path
        TenantContext.set(TENANT_A);

        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN SELECT * FROM shops WHERE published = true "
                        + "AND search_vector @@ plainto_tsquery('english', 'coffee') "
                        + "ORDER BY ts_rank(search_vector, plainto_tsquery('english', 'coffee')) DESC",
                String.class));

        assertThat(plan)
                .as("With ts_match_vq LEAKPROOF (V44) the shops FTS path must be served by "
                        + "idx_shops_search even under FORCE RLS:\n" + plan)
                .contains("idx_shops_search");
    }

    @Test
    void v25TriggerKeepsSearchVectorFreshOnUpdate() {
        enforceRls();
        Product product = createProduct(TENANT_A, "CHK-001", "Chicken Curry", null, null, null);
        TenantContext.set(TENANT_A);

        assertThat(search("chicken")).extracting(ProductDto::getId).contains(product.getId());

        Product managed = productRepository.findById(product.getId()).orElseThrow();
        managed.setTitle("Lamb Kebab");
        productRepository.saveAndFlush(managed);
        entityManager.clear();

        assertThat(search("lamb")).extracting(ProductDto::getId).contains(product.getId());
        assertThat(search("chicken")).extracting(ProductDto::getId).doesNotContain(product.getId());
    }

    // ---- V44 backfill regression (#96: NULL search_vector rows invisible) ----

    /**
     * The live dev DB had 24/25 products with NULL search_vector — rows loaded
     * via trigger-bypassing paths (pg_restore --disable-triggers, ETL) never
     * get a vector and are invisible to search. Re-runs the V44 backfill (the
     * literal migration file, proving its idempotency) against such a row, as
     * the RLS-bound NOSUPERUSER role — the exact reality it must fix in
     * dev/prod where the migration role is jtoye_app. Also exercises V44's
     * graceful-degrade path: the non-superuser ALTER FUNCTION attempt lands in
     * the WARNING branch instead of failing.
     */
    @Test
    void v44BackfillMakesTriggerBypassedProductSearchableWithTriggerIdenticalVector() {
        enforceRls();
        Product triggerTwin = createProduct(TENANT_A, "BAN-001", "Banoffee Pie",
                "Sticky toffee banana dessert", "Dessert", "vegetarian");
        TenantContext.set(TENANT_A);

        // Trigger-bypassing load: identical content, search_vector left NULL.
        // (TenantSetLocalAspect applies TENANT_A's GUC before each JdbcTemplate
        // op, so the raw INSERT passes the RLS WITH CHECK.)
        UUID bypassId = UUID.randomUUID();
        jdbcTemplate.execute("ALTER TABLE products DISABLE TRIGGER trg_products_search_vector");
        jdbcTemplate.update(
                "INSERT INTO products (id, tenant_id, shop_id, sku, title, description, category, "
                        + "ingredients_text, dietary_tags, allergen_mask, price_pennies) "
                        + "VALUES (?, ?, ?, 'BAN-002', 'Banoffee Pie', 'Sticky toffee banana dessert', "
                        + "'Dessert', 'Test ingredients', 'vegetarian', 0, 1000)",
                bypassId, TENANT_A, shopIdFor(TENANT_A));
        jdbcTemplate.execute("ALTER TABLE products ENABLE TRIGGER trg_products_search_vector");

        // Pre-V44 reality: the row exists but no query can ever match it.
        assertThat(vectorOf("products", bypassId)).isNull();
        assertThat(search("banoffee")).extracting(ProductDto::getId)
                .containsExactly(triggerTwin.getId());

        runV44Migration();

        assertThat(vectorOf("products", bypassId))
                .as("backfilled vector must be byte-identical to the V25 trigger's output")
                .isNotNull()
                .isEqualTo(vectorOf("products", triggerTwin.getId()));
        assertThat(search("banoffee")).extracting(ProductDto::getId)
                .containsExactlyInAnyOrder(triggerTwin.getId(), bypassId);
    }

    /**
     * Same defect and backfill for the shops FTS vector (V25 shops trigger).
     * The trigger-bypassed twin lives in TENANT_B — identical vector-relevant
     * content is impossible within one tenant (unique (tenant_id, name)) and
     * this also proves the V44 tenant loop reaches every tenant. Both shops
     * are published, so the public storefront search must return both.
     */
    @Test
    void v44BackfillRestoresShopsSearchVectorIdenticalToTriggerOutput() {
        enforceRls();
        TenantContext.set(TENANT_A);

        // Twin through the trigger path (raw INSERT still fires the trigger).
        UUID twinId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, name, slug, published, tags, description, address, delivery_fee_pennies) "
                        + "VALUES (?, ?, 'Corner Coffee House', ?, true, 'coffee, brunch', "
                        + "'Espresso bar and bakery', '1 High Street', 0)",
                twinId, TENANT_A, "corner-coffee-twin-" + twinId);

        // Trigger-bypassed load with identical vector-relevant content.
        // TenantContext drives the GUC (TenantSetLocalAspect re-applies it
        // before every JdbcTemplate op), so switch to TENANT_B for the insert.
        UUID bypassId = UUID.randomUUID();
        TenantContext.set(TENANT_B);
        jdbcTemplate.execute("ALTER TABLE shops DISABLE TRIGGER trg_shops_search_vector");
        jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, name, slug, published, tags, description, address, delivery_fee_pennies) "
                        + "VALUES (?, ?, 'Corner Coffee House', ?, true, 'coffee, brunch', "
                        + "'Espresso bar and bakery', '1 High Street', 0)",
                bypassId, TENANT_B, "corner-coffee-bypass-" + bypassId);
        jdbcTemplate.execute("ALTER TABLE shops ENABLE TRIGGER trg_shops_search_vector");
        TenantContext.set(TENANT_A);

        assertThat(vectorOf("shops", bypassId)).isNull();
        assertThat(shopRepository.fullTextSearchPublished("coffee", PageRequest.of(0, 10)))
                .extracting(Shop::getId).containsExactly(twinId);

        runV44Migration();

        assertThat(vectorOf("shops", bypassId))
                .as("backfilled vector must be byte-identical to the V25 trigger's output")
                .isNotNull()
                .isEqualTo(vectorOf("shops", twinId));
        assertThat(shopRepository.fullTextSearchPublished("coffee", PageRequest.of(0, 10)))
                .extracting(Shop::getId).containsExactlyInAnyOrder(twinId, bypassId);
    }

    // ---- Helpers ----

    /** search_vector::text of one row (null when the vector is NULL). */
    private String vectorOf(String table, UUID id) {
        List<String> vectors = jdbcTemplate.queryForList(
                "SELECT search_vector::text FROM " + table + " WHERE id = ?", String.class, id);
        assertThat(vectors).hasSize(1);
        return vectors.get(0);
    }

    /**
     * Executes the literal V44 migration file against the current (already
     * fully migrated) schema — every statement is idempotent, so this both
     * exercises the backfill and proves safe re-execution. Run after
     * {@link #enforceRls()} it also covers the non-superuser path: the
     * LEAKPROOF ALTER falls into its insufficient_privilege WARNING branch.
     */
    private void runV44Migration() {
        String sql;
        try (var in = getClass().getResourceAsStream(
                "/db/migration/V44__fts_leakproof_and_vector_backfill.sql")) {
            assertThat(in).as("V44 migration must be on the classpath").isNotNull();
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read V44 migration", e);
        }
        jdbcTemplate.execute(sql);
    }

    private List<ProductDto> search(String query) {
        return productService.search(query, PageRequest.of(0, 100)).getContent();
    }

    /** EXPLAIN of the exact SQL shape ProductRepository.searchFullText executes. */
    private String explainRepositorySql() {
        List<String> planLines = jdbcTemplate.queryForList(
                "EXPLAIN SELECT p.* FROM products p WHERE p.id IN ("
                        + "SELECT id FROM products WHERE search_vector @@ to_tsquery('english', 'chicken:*') "
                        + "UNION "
                        + "SELECT id FROM products WHERE LOWER(sku) LIKE 'chicken%' ESCAPE '!') "
                        + "ORDER BY ts_rank(p.search_vector, to_tsquery('english', 'chicken:*')) DESC, p.title ASC, p.id ASC",
                String.class);
        return String.join("\n", planLines);
    }

    /**
     * Four products exercising every weighted field of the V25 vector:
     * two title matches for "curry", one description-only match, and one
     * product carrying category/ingredients/dietary tokens.
     */
    private void seedStandardCatalog(UUID tenantId) {
        createProduct(tenantId, "CHK-001", "Chicken Curry",
                "Tender chicken in a spiced sauce", "Mains", null);
        createProduct(tenantId, "BEF-001", "Beef Curry",
                "Slow-cooked beef", "Mains", null);
        createProduct(tenantId, "HSE-001", "House Special",
                "A mild curry sauce with rice", "Mains", null);
        createProduct(tenantId, "CAK-001", "Chocolate Cake",
                "Rich chocolate sponge", "Dessert", "vegetarian");
    }

    private Product createProduct(UUID tenantId, String sku, String title,
                                  String description, String category, String dietaryTags) {
        UUID shopId = shopIdFor(tenantId);
        TenantContext.set(tenantId);
        Product product = new Product();
        product.setTenantId(tenantId);
        product.setSku(sku);
        product.setTitle(title);
        product.setIngredientsText("Chocolate Cake".equals(title)
                ? "flour, cocoa, almonds, butter"
                : "Test ingredients");
        product.setDescription(description);
        product.setCategory(category);
        product.setDietaryTags(dietaryTags);
        product.setAllergenMask(0);
        product.setPricePennies(1000L);
        // Phase 19 UIX-05: every product belongs to exactly one shop.
        product.setShopId(shopId);
        // saveAndFlush: Hibernate batching would otherwise defer this INSERT to a
        // later flush under a DIFFERENT tenant GUC -> RLS WITH CHECK violation.
        Product saved = productRepository.saveAndFlush(product);
        TenantContext.clear();
        // Detach: subsequent queries must hit SQL (where RLS filters), not the
        // persistence context.
        entityManager.clear();
        return saved;
    }

    /**
     * Lazily creates one published shop per tenant and caches its id. The FTS
     * search path does not filter by shop_id, so assigning products a shop leaves
     * every search assertion and the pinned GIN-index query plans unchanged; it
     * only removes this suite's incidental reliance on NULL shop_id rows.
     */
    private UUID shopIdFor(UUID tenantId) {
        UUID cached = tenantShopIds.get(tenantId);
        if (cached != null) {
            return cached;
        }
        TenantContext.set(tenantId);
        Shop shop = new Shop();
        shop.setTenantId(tenantId);
        shop.setName("FTS Catalog Shop");
        shop.setSlug("fts-catalog-shop-" + tenantId);
        shop.setPublished(true);
        UUID shopId = shopRepository.saveAndFlush(shop).getId();
        TenantContext.clear();
        entityManager.clear();
        tenantShopIds.put(tenantId, shopId);
        return shopId;
    }
}
