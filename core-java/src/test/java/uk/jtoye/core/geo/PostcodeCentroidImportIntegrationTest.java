package uk.jtoye.core.geo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The import path, end to end, against a real Postgres — because {@code COPY … FROM STDIN} is a
 * PostgreSQL protocol feature that H2 does not implement, so an H2 test here would be testing
 * nothing that ships.
 *
 * <p>The assertion that matters most is the Null Island arm. 879 rows in the 2026-08 Code-Point
 * Open release carry {@code positional_quality_indicator = 90} with zeroed coordinates, and a
 * surviving one is not a cosmetic defect: a shop at {@code (0,0)} is nearer the origin than any
 * real shop in Britain, so under a distance sort it becomes the nearest kitchen to
 * <em>every</em> customer on the platform.
 *
 * <p>Its fixture deliberately declares <strong>8</strong> rows in the manifest rather than 7. If
 * it declared 7 the import would abort on the row-count check and the test would pass for the
 * wrong reason — proving only that counting works, while the Null Island guard could be missing
 * entirely.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class PostcodeCentroidImportIntegrationTest {

    /** The fixture's real row count — kept in one place so a drifting fixture fails loudly. */
    private static final int FIXTURE_ROWS = 7;

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
        // application-test.yml routes to H2; override everything it sets so the real V61
        // migration runs on real Postgres. Mirrors RlsContractTest.
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

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    private PostcodeCentroidImporter importerFor(String csv, String manifest) {
        return new PostcodeCentroidImporter(
                dataSource, new DefaultResourceLoader(),
                "classpath:geo/" + csv, "classpath:geo/" + manifest);
    }

    @BeforeEach
    void clearTable() {
        jdbc.execute("TRUNCATE postcode_centroid");
    }

    // ---- The happy path --------------------------------------------------------------------

    @Test
    @DisplayName("the fixture loads to its exact manifest row count")
    void loadsFixtureToExactCount() throws Exception {
        long loaded = importerFor("postcode-centroids-fixture.csv", "fixture-SOURCE.md")
                .importIfNeeded();

        assertThat(loaded).isEqualTo(FIXTURE_ROWS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM postcode_centroid", Long.class))
                .isEqualTo(FIXTURE_ROWS);
    }

    @Test
    @DisplayName("the loaded rows are the real ones, keyed space-stripped and uppercased")
    void loadedRowsCarryTheCanonicalKeyAndRealCoordinates() throws Exception {
        importerFor("postcode-centroids-fixture.csv", "fixture-SOURCE.md").importIfNeeded();

        // Non-vacuity: proves the load actually placed parsed VALUES, not just row count.
        Double lat = jdbc.queryForObject(
                "SELECT latitude FROM postcode_centroid WHERE postcode = 'SE155BS'", Double.class);
        assertThat(lat).isEqualTo(51.472435);

        // The spaced form must NOT be a key — normalisation belongs to PostcodeGeocoder.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM postcode_centroid WHERE postcode = 'SE15 5BS'", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("zero loaded rows sit at Null Island")
    void noLoadedRowSitsAtNullIsland() throws Exception {
        importerFor("postcode-centroids-fixture.csv", "fixture-SOURCE.md").importIfNeeded();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM postcode_centroid WHERE latitude = 0 AND longitude = 0",
                Long.class))
                .isZero();
    }

    @Test
    @DisplayName("a re-run is a no-op and does not duplicate")
    void reRunIsIdempotent() throws Exception {
        PostcodeCentroidImporter importer =
                importerFor("postcode-centroids-fixture.csv", "fixture-SOURCE.md");

        importer.importIfNeeded();
        long second = importer.importIfNeeded();

        assertThat(second).isEqualTo(FIXTURE_ROWS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM postcode_centroid", Long.class))
                .isEqualTo(FIXTURE_ROWS);
        // The primary key would have rejected duplicates anyway; this asserts the stronger
        // property that distinct keys were not somehow re-inserted under new values.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT postcode) FROM postcode_centroid", Long.class))
                .isEqualTo(FIXTURE_ROWS);
    }

    // ---- The falsification arm -------------------------------------------------------------

    @Test
    @DisplayName("an injected Null Island row is REJECTED — the load aborts and stores nothing")
    void nullIslandRowIsRejectedRatherThanStored() {
        PostcodeCentroidImporter importer =
                importerFor("postcode-centroids-nullisland.csv", "nullisland-SOURCE.md");

        assertThatThrownBy(importer::importIfNeeded)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("(0,0)");

        // Not merely "the bad row is absent" — the whole load is rolled back, so the table is
        // untouched rather than partially populated with the seven good rows.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM postcode_centroid", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("a row-count mismatch aborts rather than serving a partial table")
    void rowCountMismatchAborts() {
        // The fixture holds 7 rows; this manifest claims 8.
        PostcodeCentroidImporter importer =
                importerFor("postcode-centroids-fixture.csv", "nullisland-SOURCE.md");

        assertThatThrownBy(importer::importIfNeeded)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partial postcode table");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM postcode_centroid", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("an unreadable manifest is a VOID, not a pass")
    void unreadableManifestAborts() {
        PostcodeCentroidImporter importer =
                importerFor("postcode-centroids-fixture.csv", "postcode-centroids-fixture.csv");

        // Pointing the manifest at the CSV: parseable file, no row-count line. "Could not
        // check" must never resolve to "clean".
        assertThatThrownBy(importer::importIfNeeded)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected row count");
    }

    // ---- The default that makes the whole phase non-vacuous ---------------------------------

    @Test
    @DisplayName("the DEV default for postcode-import.enabled resolves to true (not just claims to)")
    void devDefaultIsEnabled() throws Exception {
        // Resolved, not grepped. application.yml carries the placeholder
        // `${POSTCODE_IMPORT_ENABLED:true}`, and only a real resolution proves the default
        // inside it. A comment claiming a default is not a default.
        //
        // This is the arm that catches the silent-empty-substrate failure: were this false,
        // postcode_centroid would stay empty, 33-05 would write no coordinates, 33-06 would
        // correctly return nothing, 33-07 would render its empty state, and every automated
        // verify in all four plans would stay green.
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("dev");

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources =
                loader.load("application", new ClassPathResource("application.yml"));
        assertThat(sources).as("application.yml must be readable and non-empty").isNotEmpty();
        sources.forEach(environment.getPropertySources()::addLast);

        assertThat(environment.getProperty("jtoye.geo.postcode-import.enabled"))
                .as("jtoye.geo.postcode-import.enabled resolved under the dev profile")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("the keys 33-05 and 33-06 read are declared here, since this plan owns the block")
    void downstreamGeoKeysAreDeclared() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("dev");
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        try {
            loader.load("application", new ClassPathResource("application.yml"))
                    .forEach(environment.getPropertySources()::addLast);
        } catch (Exception e) {
            throw new IllegalStateException("could not load application.yml", e);
        }

        assertThat(environment.getProperty("jtoye.geo.coordinate-backfill.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("jtoye.geo.default-radius-km")).isEqualTo("5");
        assertThat(environment.getProperty("jtoye.geo.max-radius-km")).isEqualTo("50");
    }
}
