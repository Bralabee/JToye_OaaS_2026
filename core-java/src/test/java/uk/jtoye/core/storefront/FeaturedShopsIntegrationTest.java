package uk.jtoye.core.storefront;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storefront.dto.PublicShopDto;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Featured kitchens (quick 261102-r6u, owner decision D-02, design D17): an ordered,
 * per-environment list of shop slugs, read anonymously from {@code GET /api/v1/public/featured-shops}
 * (and the legacy {@code /public/featured-shops}).
 *
 * <p>Every read here runs the way production runs it: as a {@code NOSUPERUSER NOBYPASSRLS} role with
 * NO tenant pinned. The Testcontainers default user is a SUPERUSER and bypasses FORCE RLS, so a read
 * proven only as that user would also see the unpublished shop, and this test would prove nothing
 * about what an anonymous visitor is shown (T-r6u-01).
 *
 * <p>The configured list is deliberately hostile: a published shop of tenant B FIRST, then an
 * unpublished shop, a slug that names no shop at all, and a published shop of tenant A. The answer
 * must be exactly [B, A] — published only, in CONFIGURED order (not name or insertion order, both of
 * which would put A first), across two tenants — and the two skipped slugs are named in exactly one
 * WARN however many times the landing page asks.
 *
 * <p>One test method on purpose: the once-per-distinct-set WARN state lives in a singleton bean of a
 * context cached for this class, so a second method that also read the endpoint would make the WARN
 * count depend on method order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class FeaturedShopsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    static final String SLUG_B = "feat-b-published";
    static final String SLUG_U = "feat-u-unpublished";
    static final String SLUG_GHOST = "feat-ghost";
    static final String SLUG_A = "feat-a-published";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("rate-limiting.enabled", () -> "false");
        registry.add("jtoye.storefront.featured-shop-slugs",
                () -> String.join(",", SLUG_B, SLUG_U, SLUG_GHOST, SLUG_A));
    }

    private static final String RLS_TEST_ROLE = "rls_featured_role";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private JsonMapper objectMapper;
    @Autowired private FeaturedShopsService featuredShopsService;

    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;

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

        UUID tenantA = seedTenant("featured-a");
        UUID tenantB = seedTenant("featured-b");
        // Names chosen so that NAME order (Alpha < Bravo) and the configured order (B before A)
        // disagree: a re-ordering step that is missing cannot pass by accident.
        seedShop(tenantA, "Alpha Kitchen", SLUG_A, true);
        seedShop(tenantB, "Bravo Kitchen", SLUG_B, true);
        seedShop(tenantB, "Unpublished Kitchen", SLUG_U, false);
        // A published shop that is NOT configured: must never appear.
        seedShop(tenantA, "Not Featured Kitchen", "feat-not-configured", true);

        serviceLogger = (Logger) LoggerFactory.getLogger(FeaturedShopsService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        TenantContext.clear();
    }

    @Test
    @DisplayName("anonymous NOSUPERUSER read: published only, configured order, two tenants, both prefixes, ONE warn")
    void featuredShopsArePublishedOnlyInConfiguredOrderWithOneWarn() throws Exception {
        downgradeToAnonymousRuntimeRole();

        // Positive control first: the role CAN see a published shop at all, so an empty answer
        // below could only mean the feature is wrong, never that the role sees nothing.
        Integer visible = jdbc.queryForObject(
                "SELECT count(*) FROM shops WHERE slug = ?", Integer.class, SLUG_A);
        assertThat(visible).as("positive control: the anonymous role sees a published shop").isEqualTo(1);
        Integer hidden = jdbc.queryForObject(
                "SELECT count(*) FROM shops WHERE slug = ?", Integer.class, SLUG_U);
        assertThat(hidden).as("control: RLS alone hides the unpublished shop from this role").isZero();

        // Service level.
        List<PublicShopDto> fromService = featuredShopsService.featuredShops();
        assertThat(fromService).extracting(PublicShopDto::getSlug).containsExactly(SLUG_B, SLUG_A);
        assertThat(fromService).extracting(PublicShopDto::getSeller)
                .as("the list endpoint attaches no seller (one query, like the other list endpoints)")
                .containsOnlyNulls();

        // HTTP level, both prefixes, anonymous (no Authorization header).
        for (String path : List.of("/api/v1/public/featured-shops", "/public/featured-shops")) {
            String body = mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getContentAsString();
            JsonNode array = objectMapper.readTree(body);
            assertThat(array.isArray()).as(path + " answers a JSON array").isTrue();
            List<String> slugs = new ArrayList<>();
            array.forEach(node -> slugs.add(node.get("slug").asString()));
            assertThat(slugs).as(path).containsExactly(SLUG_B, SLUG_A);
        }

        // Repeated reads: the landing page asks on every render.
        featuredShopsService.featuredShops();
        featuredShopsService.featuredShops();

        List<ILoggingEvent> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warns).as("one WARN per distinct skipped set, never one per request").hasSize(1);
        String message = warns.get(0).getFormattedMessage();
        assertThat(message).contains("jtoye.storefront.featured-shop-slugs");
        assertThat(message).contains(SLUG_U).contains(SLUG_GHOST);
        assertThat(message).doesNotContain(SLUG_A).doesNotContain(SLUG_B);
    }

    // ---- helpers ----

    private void downgradeToAnonymousRuntimeRole() {
        TenantContext.clear();
        jdbc.execute("SELECT set_config('app.current_tenant_id', '', true)");
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
    }

    private UUID seedTenant(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, prefix + "-" + id);
        return id;
    }

    private void seedShop(UUID tenantId, String name, String slug, boolean published) {
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0)",
                UUID.randomUUID(), tenantId, name, slug, "1 Test Street", published);
    }
}
