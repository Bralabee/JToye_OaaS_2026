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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.hasSize;
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
 *       search_vector fresh on UPDATE.</li>
 * </ul>
 *
 * <p><strong>GIN index under RLS (#96 finding):</strong> Postgres refuses
 * non-LEAKPROOF operators as index quals beneath a row-security barrier, and
 * {@code ts_match_vq} (the {@code @@} function) ships {@code proleakproof=f}.
 * So for the RLS-bound app role the FTS branch currently planner-degrades to a
 * tenant-filtered seq scan, while the identical SQL is served by a Bitmap
 * Index Scan on {@code idx_products_search} the moment the barrier is absent
 * (superuser) or {@code ALTER FUNCTION pg_catalog.ts_match_vq(tsvector,
 * tsquery) LEAKPROOF} is applied (verified manually on postgres:15; needs a
 * future migration — V43 is reserved). Two tests below pin BOTH realities;
 * the tripwire test fails loudly when that migration lands so the assertion
 * gets flipped rather than silently drifting.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Transactional
@org.junit.jupiter.api.Tag("testcontainers")
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

    @Test
    @WithMockUser
    void searchEndpointCapsPageSizeAtGlobalMaximumAndKeepsArrayContract() throws Exception {
        enforceRls();
        for (int i = 0; i < 101; i++) {
            createProduct(TENANT_A, String.format("MUF-%03d", i), "Muffin " + i, null, null, null);
        }

        // Default page size on the search path is the global cap (100)
        mockMvc.perform(get("/api/v1/products/search")
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
                        .param("q", "muffin").param("size", "500")
                        .header("X-Tenant-Id", TENANT_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(100)));

        // Explicit paging works and stays a bare array
        mockMvc.perform(get("/api/v1/products/search")
                        .param("q", "muffin").param("page", "1").param("size", "100")
                        .header("X-Tenant-Id", TENANT_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get("/api/v1/products/search")
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
     * TRIPWIRE — pins the current planner degradation under RLS (#96 report).
     *
     * <p>Postgres only allows LEAKPROOF operators as index quals beneath a
     * row-security barrier, and ts_match_vq (the {@code @@} function) is not
     * leakproof, so for the RLS-bound app role the FTS branch falls back to a
     * tenant-filtered seq scan today. {@code ALTER FUNCTION
     * pg_catalog.ts_match_vq(tsvector, tsquery) LEAKPROOF} flips the identical
     * SQL to a Bitmap Index Scan on idx_products_search (verified manually on
     * postgres:15) but needs a migration, and V43 is reserved. When that
     * migration lands, THIS TEST MUST FAIL — flip both assertions so the plan
     * verification becomes contains("idx_products_search") under RLS.
     */
    @Test
    void rlsLeakproofRestrictionCurrentlyForcesSeqScanTripwire() {
        enforceRls();
        seedStandardCatalog(TENANT_A);
        TenantContext.set(TENANT_A);

        Boolean leakproof = jdbcTemplate.queryForObject(
                "SELECT proleakproof FROM pg_proc WHERE proname = 'ts_match_vq'", Boolean.class);
        assertThat(leakproof)
                .as("ts_match_vq became LEAKPROOF — the GIN index now works under RLS; "
                        + "flip this test's assertions to require idx_products_search")
                .isFalse();

        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
        String plan = explainRepositorySql();

        assertThat(plan)
                .as("Expected the documented RLS/leakproof seq-scan degradation; "
                        + "if the index now appears, flip this tripwire:\n" + plan)
                .doesNotContain("idx_products_search");
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

    // ---- Helpers ----

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
        // saveAndFlush: Hibernate batching would otherwise defer this INSERT to a
        // later flush under a DIFFERENT tenant GUC -> RLS WITH CHECK violation.
        Product saved = productRepository.saveAndFlush(product);
        TenantContext.clear();
        // Detach: subsequent queries must hit SQL (where RLS filters), not the
        // persistence context.
        entityManager.clear();
        return saved;
    }
}
