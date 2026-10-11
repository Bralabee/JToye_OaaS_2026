package uk.jtoye.core.dev;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.config.DatabaseConfigurationValidator;
import uk.jtoye.core.security.TenantContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 37-22 (D-15, D-25): every shop the dev seeder writes DECLARES its hours, an all-day window every day
 * ({@code 00:00-00:00}, which {@code ShopAvailabilityPolicy} reads as open 24 hours), stored as
 * {@code hours_source = STRUCTURED}. So the demo storefronts and the e2e journeys that run against them stay
 * time-independent without relying on {@code LEGACY_ALWAYS_OPEN}, which only V80 writes, and their JSON-LD
 * publishes hours the (demo) vendor declared rather than nothing.
 *
 * <p>Three cases, each with its own fail direction:
 * <ul>
 *   <li>a fresh volume: every seeded shop (the curated storefronts, the archive and the tenant-B probe) is
 *       STRUCTURED with the all-day week;</li>
 *   <li>an EXISTING volume, where V80 marked the hours-less demo shops LEGACY_ALWAYS_OPEN (the dev database
 *       had {@code opening_hours} NULL or {@code {}} on all five at V79): the next seeder run declares them;</li>
 *   <li>hours a developer saved through the shop editor (STRUCTURED, not all-day) survive a restart.</li>
 * </ul>
 *
 * <p>Context as in {@link DemoDataSeederTraderIdentityIntegrationTest}: the {@code dev} profile, so the seeder
 * runs as the {@code ApplicationRunner} it is at startup on a fresh Flyway-migrated database; the Testcontainers
 * role is a SUPERUSER, so these reads see every tenant and {@code DatabaseConfigurationValidator} is
 * neutralised.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("dev")
@TestPropertySource(properties = {"storage.blob.validate-on-startup=false",
        "jtoye.geo.postcode-import.enabled=false",
        "jtoye.gdpr.dsar.encryption-key=${random.value}${random.value}"})
@Tag("testcontainers")
class DemoDataSeederHoursIntegrationTest {

    /** The all-day week the seeder declares, as the jsonb the application writes (compared semantically). */
    private static final String ALL_DAY_WEEK = "{"
            + "\"mon\":[{\"open\":\"00:00\",\"close\":\"00:00\"}],"
            + "\"tue\":[{\"open\":\"00:00\",\"close\":\"00:00\"}],"
            + "\"wed\":[{\"open\":\"00:00\",\"close\":\"00:00\"}],"
            + "\"thu\":[{\"open\":\"00:00\",\"close\":\"00:00\"}],"
            + "\"fri\":[{\"open\":\"00:00\",\"close\":\"00:00\"}],"
            + "\"sat\":[{\"open\":\"00:00\",\"close\":\"00:00\"}],"
            + "\"sun\":[{\"open\":\"00:00\",\"close\":\"00:00\"}]}";

    /** Hours a developer saved: Monday 09:00-17:00 only. Not the seeder's shape, so an overwrite is visible. */
    private static final String DEVELOPER_HOURS = "{\"mon\":[{\"open\":\"09:00\",\"close\":\"17:00\"}],"
            + "\"tue\":[],\"wed\":[],\"thu\":[],\"fri\":[],\"sat\":[],\"sun\":[]}";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
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

    @MockitoBean private DatabaseConfigurationValidator databaseConfigurationValidator;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DemoDataSeeder demoDataSeeder;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** Shops whose hours are NOT the declared all-day STRUCTURED week, as "slug=source". */
    private List<String> shopsWithoutTheAllDayWeek() {
        return jdbc.queryForList(
                "SELECT slug || '=' || coalesce(hours_source, '<null>') FROM shops "
                        + "WHERE hours_source IS DISTINCT FROM 'STRUCTURED' "
                        + "OR opening_schedule IS DISTINCT FROM CAST(? AS jsonb) ORDER BY slug",
                String.class, ALL_DAY_WEEK);
    }

    @Test
    void everySeededShopDeclaresAnAllDayStructuredWeek() {
        List<Map<String, Object>> shops = jdbc.queryForList("SELECT slug, published FROM shops ORDER BY slug");
        System.out.println("[37-22] seeded shops checked: " + shops);
        // Vacuity control: the three curated storefronts, the archive and the tenant-B probe.
        assertThat(shops).as("vacuity control: the dev seeder writes shops").hasSizeGreaterThanOrEqualTo(5);
        assertThat(shopsWithoutTheAllDayWeek()).as("seeded shops without the declared all-day week").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shops WHERE hours_source = 'LEGACY_ALWAYS_OPEN'",
                Integer.class)).isZero();
    }

    @Test
    void aDemoShopV80MarkedLegacyIsDeclaredOnTheNextRun() throws Exception {
        // What V80 makes of an hours-less pre-existing shop (the shared dev database's curated three at V79).
        jdbc.update("UPDATE shops SET hours_source = 'LEGACY_ALWAYS_OPEN', opening_schedule = NULL "
                + "WHERE slug IN ('mama-ades-kitchen', 'unsorted-legacy-items', 'tenant-b-probe')");
        assertThat(shopsWithoutTheAllDayWeek()).as("arm precondition").hasSize(3);

        demoDataSeeder.run(null);

        assertThat(shopsWithoutTheAllDayWeek()).as("after the next seeder run").isEmpty();
    }

    @Test
    void hoursADeveloperSavedSurviveARestart() throws Exception {
        jdbc.update("UPDATE shops SET hours_source = 'STRUCTURED', opening_schedule = CAST(? AS jsonb) "
                + "WHERE slug = 'brixton-village-grill'", DEVELOPER_HOURS);
        try {
            demoDataSeeder.run(null);

            assertThat(jdbc.queryForObject(
                    "SELECT opening_schedule = CAST(? AS jsonb) AND hours_source = 'STRUCTURED' FROM shops "
                            + "WHERE slug = 'brixton-village-grill'", Boolean.class, DEVELOPER_HOURS))
                    .as("the developer's saved hours after a seeder run").isTrue();
        } finally {
            jdbc.update("UPDATE shops SET opening_schedule = CAST(? AS jsonb) WHERE slug = 'brixton-village-grill'",
                    ALL_DAY_WEEK);
        }
    }
}
