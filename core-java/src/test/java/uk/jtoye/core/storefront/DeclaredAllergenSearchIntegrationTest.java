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
 * #862 (owner ruling 2026-11-04, persona close-out): the public kitchen search and listing can be
 * narrowed by what a vendor has DECLARED. {@code ?declares=Peanuts} lists kitchens with an available
 * dish whose declared {@code allergen_mask} has the peanut bit; a search term that IS an allergen name
 * ({@code q=peanut}) also finds those kitchens although no dish text says "peanut".
 *
 * <p>The wording boundary is part of the contract: this is "declares", never "free from". So
 * {@code q=gluten free} must NOT be read as the allergen name (it would return exactly the kitchens a
 * gluten-avoider wants least), and an unavailable dish or an unpublished kitchen never counts.
 *
 * <p>Every search runs as a {@code NOSUPERUSER NOBYPASSRLS} role with no tenant pinned, the way
 * production runs it (see ShopSearchMatchesDishesIntegrationTest for why).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class DeclaredAllergenSearchIntegrationTest {

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
    private static final int PEANUTS = 1 << 4;
    private static final int GLUTEN = 1 << 0;
    private static final int MILK = 1 << 6;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PublicStorefrontService storefrontService;

    private UUID tenantT;
    private UUID tenantU;

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

        tenantT = seedTenant("decl-t");
        tenantU = seedTenant("decl-u");

        // Declares peanuts on an available dish whose TEXT never says peanut.
        UUID suyaHouse = seedShop(tenantT, "Suya House", true);
        seedProduct(tenantT, suyaHouse, "Beef suya skewers", "Beef, yaji spice", true, PEANUTS);

        // Declares gluten and milk, not peanuts.
        UUID bakery = seedShop(tenantU, "Corner Bakery", true);
        seedProduct(tenantU, bakery, "Meat pie", "Flour, beef", true, GLUTEN | MILK);

        // Declares peanuts but the dish is not orderable: must not count.
        UUID closedDish = seedShop(tenantU, "Quiet Pot", true);
        seedProduct(tenantU, closedDish, "Groundnut stew", "Groundnut", false, PEANUTS);

        // Declares peanuts but the kitchen is unpublished: must never appear.
        UUID hidden = seedShop(tenantU, "Hidden Pot", false);
        seedProduct(tenantU, hidden, "Peanut soup", "Peanut", true, PEANUTS);

        // Nothing declared.
        UUID plain = seedShop(tenantT, "Plain Plate", true);
        seedProduct(tenantT, plain, "Boiled rice", "Rice", true, 0);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("declares=Peanuts lists exactly the published kitchens with an AVAILABLE dish declaring peanuts")
    void declaresFilterListsOnlyDeclaringKitchens() {
        Page<PublicShopDto> page = asAnonymous(() ->
                storefrontService.listPublishedShopsDeclaring(PEANUTS, PageRequest.of(0, 20)));

        // Exact: an empty page would also "not contain" the unavailable and unpublished ones.
        assertThat(page.getContent()).extracting(PublicShopDto::getName).containsExactly("Suya House");
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("a mask with two allergens lists a kitchen declaring EITHER (any-of)")
    void declaresFilterIsAnyOf() {
        Page<PublicShopDto> page = asAnonymous(() ->
                storefrontService.listPublishedShopsDeclaring(PEANUTS | MILK, PageRequest.of(0, 20)));

        assertThat(page.getContent()).extracting(PublicShopDto::getName)
                .containsExactly("Corner Bakery", "Suya House");
    }

    @Test
    @DisplayName("q=peanut (an allergen NAME) finds the kitchen whose dish declares peanuts though no text says so")
    void allergenNameSearchMatchesDeclaringDishes() {
        Page<PublicShopDto> page = asAnonymous(() ->
                storefrontService.searchPublishedShops("peanut", PageRequest.of(0, 20)).page());

        assertThat(page.getContent()).extracting(PublicShopDto::getName).containsExactly("Suya House");
    }

    @Test
    @DisplayName("q=gluten finds the kitchen that declares gluten (singular, lower case)")
    void glutenSearchMatchesDeclaringDishes() {
        Page<PublicShopDto> page = asAnonymous(() ->
                storefrontService.searchPublishedShops("gluten", PageRequest.of(0, 20)).page());

        assertThat(page.getContent()).extracting(PublicShopDto::getName).containsExactly("Corner Bakery");
    }

    @Test
    @DisplayName("q=gluten free is NOT read as 'declares gluten': it must not return the gluten kitchen")
    void glutenFreeIsNeverReadAsDeclaresGluten() {
        Page<PublicShopDto> page = asAnonymous(() ->
                storefrontService.searchPublishedShops("gluten free", PageRequest.of(0, 20)).page());

        assertThat(page.getContent()).extracting(PublicShopDto::getName).doesNotContain("Corner Bakery");
    }

    @Test
    @DisplayName("declares + q intersect: the kitchen must match the text AND declare the allergen")
    void declaresAndTextIntersect() {
        Page<PublicShopDto> both = asAnonymous(() ->
                storefrontService.searchPublishedShops("suya", PEANUTS, PageRequest.of(0, 20)).page());
        assertThat(both.getContent()).extracting(PublicShopDto::getName).containsExactly("Suya House");

        // Same text, an allergen it does not declare: nothing. (Control for the one above.)
        Page<PublicShopDto> none = asAnonymous(() ->
                storefrontService.searchPublishedShops("suya", MILK, PageRequest.of(0, 20)).page());
        assertThat(none.getContent()).isEmpty();

        // Partial word reaches the LIKE tier and still honours the declaration.
        Page<PublicShopDto> partial = asAnonymous(() ->
                storefrontService.searchPublishedShops("suy", PEANUTS, PageRequest.of(0, 20)).page());
        assertThat(partial.getContent()).extracting(PublicShopDto::getName).containsExactly("Suya House");
        Page<PublicShopDto> partialOther = asAnonymous(() ->
                storefrontService.searchPublishedShops("suy", MILK, PageRequest.of(0, 20)).page());
        assertThat(partialOther.getContent()).isEmpty();
    }

    @Test
    @DisplayName("the paging count agrees with the result set (page size 1 forces the count query)")
    void countQueryMatchesResults() {
        Page<PublicShopDto> first = asAnonymous(() ->
                storefrontService.listPublishedShopsDeclaring(PEANUTS | MILK, PageRequest.of(0, 1)));

        assertThat(first.getContent()).hasSize(1);
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getTotalPages()).isEqualTo(2);
    }

    // ---- helpers ----

    private <T> T asAnonymous(java.util.function.Supplier<T> call) {
        TenantContext.clear();
        jdbc.execute("SELECT set_config('app.current_tenant_id', '', true)");
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
        try {
            return call.get();
        } finally {
            jdbc.execute("RESET ROLE");
        }
    }

    private UUID seedTenant(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, prefix + "-" + id);
        return id;
    }

    private UUID seedShop(UUID tenantId, String name, boolean published) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, tags, description, address, published, "
                        + "delivery_fee_pennies) VALUES (?, ?, ?, ?, '', '', '1 Test Road', ?, 0)",
                id, tenantId, name, "decl-" + id, published);
        return id;
    }

    private void seedProduct(UUID tenantId, UUID shopId, String title, String ingredients,
                             boolean available, int allergenMask) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, shop_id, sku, title, ingredients_text, available, allergen_mask) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, shopId, "SKU-" + id.toString().substring(0, 8), title, ingredients, available, allergenMask);
    }
}
