package uk.jtoye.core.storefront;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storefront.dto.PublicShopDto;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public kitchen search finds a kitchen by the DISHES it sells, not only by its own name, tags,
 * description and address.
 *
 * <p>The symptom (a real external tester, 2026-11-02): {@code /shop?q=pounded yam} and {@code ?q=plantain}
 * answered "No kitchens found" while a published kitchen sold exactly those dishes, because
 * {@code ShopRepository.fullTextSearchPublished} consulted only {@code shops.search_vector} (V25), and
 * the products of a published shop had no anonymous read policy at all (fixed by V89,
 * {@code products_public_read}).
 *
 * <p>Every search here runs the way production runs it: as a {@code NOSUPERUSER NOBYPASSRLS} role
 * ({@code rls_test_role}) with NO tenant pinned, through {@link PublicStorefrontService#searchPublishedShops}
 * (the service behind {@code GET /api/v1/public/shops?q=}). The Testcontainers default user is a
 * SUPERUSER and bypasses FORCE RLS, so a search proven only as that user would be green here and dead
 * in production; the 2026-11-02 probe measured exactly that (superuser: Mama's Kitchen; NOSUPERUSER: []).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class ShopSearchMatchesDishesIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    private static final String RLS_TEST_ROLE = "rls_test_role";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PublicStorefrontService storefrontService;

    private UUID tenantT;
    private UUID tenantU;
    private UUID mamasKitchen;
    private UUID unpublishedShop;

    @BeforeEach
    void seed() {
        jdbc.execute("DO $$ BEGIN "
                + "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN "
                + "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; "
                + "  END IF; "
                + "  GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE + "; "
                + "  GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE + "; "
                + "  GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE + "; "
                + "END $$");

        tenantT = seedTenant("dishes-t");
        tenantU = seedTenant("dishes-u");

        // Nothing in Mama's Kitchen's own name, tags, description or address names a dish.
        mamasKitchen = seedShop(tenantT, "Mama's Kitchen", "family, homely", "Cooked the way mum does it",
                "12 Church Road", true);
        seedProduct(tenantT, mamasKitchen, "Pounded yam with egusi", true);
        seedProduct(tenantT, mamasKitchen, "Plantain surprise", false);   // not orderable

        // An unpublished kitchen selling the dish: it must never appear.
        unpublishedShop = seedShop(tenantU, "Hidden Pot", "", "Not live yet", "3 Back Lane", false);
        seedProduct(tenantU, unpublishedShop, "Pounded yam", true);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("q=pounded yam finds the published kitchen that SELLS it, as an anonymous NOSUPERUSER caller")
    void dishWordFindsTheKitchenThatSellsIt() {
        Page<PublicShopDto> page = searchAnonymously("pounded yam", 20);

        assertThat(page.getContent()).extracting(PublicShopDto::getName).containsExactly("Mama's Kitchen");
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("q=plantain finds the kitchen with an AVAILABLE plantain dish, never the one whose plantain is unavailable")
    void unavailableProductDoesNotMatch() {
        // Positive control: without it, "nothing found" could equally mean "the dish search is dead".
        UUID dodoSpot = seedShop(tenantU, "Dodo Spot", "", "", "5 Station Road", true);
        seedProduct(tenantU, dodoSpot, "Fried plantain", true);

        Page<PublicShopDto> page = searchAnonymously("plantain", 20);

        assertThat(page.getContent()).extracting(PublicShopDto::getName).containsExactly("Dodo Spot");
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("a kitchen whose NAME matches ranks above a kitchen matched only through a dish")
    void ownVectorMatchRanksAboveProductMatch() {
        // Name-ordered, "Mama's Kitchen" would come first; the ranking must put the name match first.
        seedShop(tenantU, "Pounded Yam Palace", "", "", "8 Market Street", true);

        Page<PublicShopDto> page = searchAnonymously("pounded yam", 20);

        assertThat(page.getContent()).extracting(PublicShopDto::getName)
                .containsExactly("Pounded Yam Palace", "Mama's Kitchen");
    }

    @Test
    @DisplayName("the unpublished kitchen never appears, whatever it sells")
    void unpublishedShopNeverAppears() {
        Page<PublicShopDto> page = searchAnonymously("pounded yam", 20);

        // Exact, not doesNotContain: an empty page would also "not contain" the unpublished shop.
        assertThat(page.getContent()).extracting(PublicShopDto::getName).containsExactly("Mama's Kitchen");
        assertThat(page.getContent()).extracting(PublicShopDto::getSlug).doesNotContain("dishes-" + unpublishedShop);
    }

    @Test
    @DisplayName("a partial word (q=plantai) reaches the LIKE fallback and still finds a kitchen by its available dish")
    void partialWordFindsKitchenThroughLikeFallback() {
        // "plantai" stems to no lexeme of "plantain", so the full-text tier returns nothing and the
        // LIKE tier answers. Mama's only plantain dish is unavailable: first nothing...
        assertThat(searchAnonymously("plantai", 20).getContent()).isEmpty();

        // ...then an available one makes the kitchen findable by the fragment the tester typed.
        jdbc.execute("RESET ROLE");   // seed as the superuser again; the search re-drops the role
        seedProduct(tenantT, mamasKitchen, "Fried plantain (dodo)", true);
        Page<PublicShopDto> page = searchAnonymously("plantai", 20);

        assertThat(page.getContent()).extracting(PublicShopDto::getName).containsExactly("Mama's Kitchen");
    }

    @Test
    @DisplayName("the paging count query agrees with the result set (page size 1 forces the count query)")
    void countQueryMatchesResults() {
        UUID adesBuka = seedShop(tenantU, "Ade's Buka", "", "", "40 High Street", true);
        seedProduct(tenantU, adesBuka, "Pounded yam and efo riro", true);

        Page<PublicShopDto> first = searchAnonymously("pounded yam", 1);
        Page<PublicShopDto> all = searchAnonymously("pounded yam", 20);

        assertThat(all.getContent()).extracting(PublicShopDto::getName)
                .containsExactlyInAnyOrder("Mama's Kitchen", "Ade's Buka");
        assertThat(first.getContent()).hasSize(1);
        assertThat(first.getTotalElements())
                .as("countQuery must count the same rows the content query returns")
                .isEqualTo(all.getContent().size())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a caller carrying a tenant gets dish matches from its OWN tenant only")
    void tenantCarryingCallerGetsOwnTenantDishMatchesOnly() {
        // Ruling 2026-11-02 (review of #1098): a tenant-bearing caller on /api/v1/public/** stays
        // restricted to its own tenant, as resolvePublicShopForSlug enforces (403 +
        // tenant_spoof_attempt for another tenant's shop); the search must not offer it kitchens
        // it cannot open. With a tenant pinned, products_public_read is off and the EXISTS runs
        // under that tenant's own products_rls_policy.
        UUID ownKitchen = seedShop(tenantU, "Ade's Buka", "", "", "40 High Street", true);
        seedProduct(tenantU, ownKitchen, "Pounded yam and efo riro", true);

        TenantContext.set(tenantU);   // a different tenant from Mama's Kitchen's
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);

        Page<PublicShopDto> page = storefrontService
                .searchPublishedShops("pounded yam", PageRequest.of(0, 20)).page();

        assertThat(page.getContent()).extracting(PublicShopDto::getName).containsExactly("Ade's Buka");
        assertThat(TenantContext.get()).as("the caller's tenant is untouched").contains(tenantU);
    }

    // ---- helpers ----

    private Page<PublicShopDto> searchAnonymously(String q, int size) {
        TenantContext.clear();
        jdbc.execute("SELECT set_config('app.current_tenant_id', '', true)");
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
        return storefrontService.searchPublishedShops(q, PageRequest.of(0, size)).page();
    }

    private UUID seedTenant(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, prefix + "-" + id);
        return id;
    }

    private UUID seedShop(UUID tenantId, String name, String tags, String description, String address,
                          boolean published) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, tags, description, address, published, "
                        + "delivery_fee_pennies) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                id, tenantId, name, "dishes-" + id, tags, description, address, published);
        return id;
    }

    private void seedProduct(UUID tenantId, UUID shopId, String title, boolean available) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, shop_id, sku, title, ingredients_text, available) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, shopId, "SKU-" + id.toString().substring(0, 8), title, title, available);
    }
}
