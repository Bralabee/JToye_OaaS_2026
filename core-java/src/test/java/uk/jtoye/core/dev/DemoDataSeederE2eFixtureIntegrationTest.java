package uk.jtoye.core.dev;

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

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #1126 review: {@code DemoDataSeeder.quarantineNonCurated} re-moved scripts/seed-e2e-fixtures.sh's
 * {@code E2E-*} products to the hidden archive shop, and made them unavailable, on EVERY core start, so
 * the two seeders fought and the zero-VAT specs failed after any restart with nothing pointing at the
 * seeder. An {@code E2E-} SKU is curated dev data; any other product the seeder does not know is still
 * quarantined (the control, so the test can fail in both directions).
 *
 * <p>Boots the real {@code dev} profile on a fresh database, as {@link DemoDataSeederTraderIdentityIntegrationTest}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("dev")
@TestPropertySource(properties = {"storage.blob.validate-on-startup=false",
        "jtoye.geo.postcode-import.enabled=false",
        "jtoye.gdpr.dsar.encryption-key=${random.value}${random.value}",
        "jtoye.storefront.client-ip-hmac-key=${random.value}${random.value}"})
@Tag("testcontainers")
class DemoDataSeederE2eFixtureIntegrationTest {

    private static final UUID DEMO_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

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

    @MockitoBean private DatabaseConfigurationValidator databaseConfigurationValidator;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DemoDataSeeder demoDataSeeder;

    @Test
    void anE2eFixtureProductStaysOnItsShopAndAvailableWhileAnUnknownProductIsQuarantined() throws Exception {
        UUID shopId = jdbc.queryForObject("SELECT id FROM shops WHERE slug = 'mama-ades-kitchen'", UUID.class);
        UUID archiveId = jdbc.queryForObject("SELECT id FROM shops WHERE slug = 'unsorted-legacy-items'", UUID.class);
        assertThat(shopId).isNotNull();
        assertThat(archiveId).isNotNull();
        insertProduct(shopId, "E2E-ZERO-VAT-001");
        insertProduct(shopId, "LEGACY-ORPHAN-001");

        demoDataSeeder.run(null);

        Map<String, Object> fixture = row("E2E-ZERO-VAT-001");
        assertThat(fixture.get("shop_id")).as("the e2e fixture stays on its shop").isEqualTo(shopId);
        assertThat(fixture.get("available")).as("and stays orderable").isEqualTo(true);
        Map<String, Object> orphan = row("LEGACY-ORPHAN-001");
        assertThat(orphan.get("shop_id")).as("control: a product the seeder does not know is quarantined")
                .isEqualTo(archiveId);
        assertThat(orphan.get("available")).isEqualTo(false);
    }

    @Test
    void theCuratedPredicateKeepsCuratedAndE2eSkusAndNothingElse() {
        Set<String> curated = Set.of("MAMA-001");
        assertThat(DemoDataSeeder.isCuratedSku("MAMA-001", curated)).isTrue();
        assertThat(DemoDataSeeder.isCuratedSku("E2E-ZERO-VAT-002", curated)).isTrue();
        assertThat(DemoDataSeeder.isCuratedSku("XE2E-1", curated)).isFalse();
        assertThat(DemoDataSeeder.isCuratedSku("e2e-lower", curated)).isFalse();
        assertThat(DemoDataSeeder.isCuratedSku(null, curated)).isFalse();
    }

    private void insertProduct(UUID shopId, String sku) {
        jdbc.update("DELETE FROM products WHERE tenant_id = ? AND sku = ?", DEMO_TENANT, sku);
        jdbc.update("INSERT INTO products (id, tenant_id, shop_id, sku, title, ingredients_text, price_pennies, vat_rate, category, "
                        + "available, quantity_in_stock, created_at) VALUES (gen_random_uuid(), ?, ?, ?, 'Fixture', 'Flour', 1200, "
                        + "'STANDARD', 'Mains', true, 99, now())", DEMO_TENANT, shopId, sku);
    }

    private Map<String, Object> row(String sku) {
        return jdbc.queryForMap("SELECT shop_id, available FROM products WHERE tenant_id = ? AND sku = ?",
                DEMO_TENANT, sku);
    }
}
