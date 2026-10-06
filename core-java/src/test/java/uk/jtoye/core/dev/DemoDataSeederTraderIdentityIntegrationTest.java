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
import uk.jtoye.core.onboarding.GateResult;
import uk.jtoye.core.onboarding.GateStatus;
import uk.jtoye.core.onboarding.OnboardingModel;
import uk.jtoye.core.onboarding.VendorOnboarding;
import uk.jtoye.core.onboarding.gate.TraderIdentityGate;
import uk.jtoye.core.security.TenantContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #789 / D-12 / 31.1-12: demo storefronts are BACKFILLED with seller details, not
 * grandfathered past the TRADER_IDENTITY gate. The dev seeder publishes the curated demo shops
 * through its documented bypass, so the gate never runs for them — their seller block exists
 * only because the seeder fills it. This boots the real {@code dev} profile (so the seeder runs
 * as the {@code ApplicationRunner} it is at dev startup, on a fresh Flyway-migrated database)
 * and then asks the GATE ITSELF, for every published demo shop, whether it would pass.
 *
 * <p>Vacuity control: the number of published demo shops checked is printed and must be
 * greater than zero — an empty loop would otherwise "prove" every shop passes.
 *
 * <p>Context notes, as in {@code PublicRateLimitIntegrationTest}: the Testcontainers bootstrap
 * role is a SUPERUSER, so {@code DatabaseConfigurationValidator} is neutralised; there is no
 * Azurite here, so the storage probe is off and the seeder skips its images; the 1.7M-row
 * postcode import is off (the geocoder then misses, which leaves coordinates untouched); the
 * DSAR key is a per-run random value, never a literal.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("dev")
@TestPropertySource(properties = {"storage.blob.validate-on-startup=false",
        "jtoye.geo.postcode-import.enabled=false",
        "jtoye.gdpr.dsar.encryption-key=${random.value}${random.value}"})
@Tag("testcontainers")
class DemoDataSeederTraderIdentityIntegrationTest {

    /** V13's demo tenant, the tenant that owns the curated published demo shops. */
    private static final UUID DEMO_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        // Boot brokerless: keep the Rabbit beans but point them at a dead port, listeners off.
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @MockitoBean private DatabaseConfigurationValidator databaseConfigurationValidator;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TraderIdentityGate traderIdentityGate;
    @Autowired private DemoDataSeeder demoDataSeeder;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void everyPublishedDemoShopHasSellerDetailsAndPassesTheGate() {
        List<Map<String, Object>> published = jdbc.queryForList(
                "SELECT id, slug, email FROM shops WHERE tenant_id = ? AND published = true ORDER BY slug",
                DEMO_TENANT);
        System.out.println("[31.1-12] published demo shops checked: " + published.size());
        assertThat(published).as("vacuity control: the dev seeder publishes demo shops").isNotEmpty();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM trader_identity WHERE tenant_id = ?",
                Integer.class, DEMO_TENANT)).as("the demo tenant's trader identity").isEqualTo(1);
        Map<String, Object> identity = jdbc.queryForMap(
                "SELECT legal_name, entity_type, address_line1, address_city, address_postcode "
                        + "FROM trader_identity WHERE tenant_id = ?", DEMO_TENANT);
        // Plainly demonstration data, and a sole trader, so no company number is invented.
        assertThat((String) identity.get("legal_name")).endsWith("(demo)");
        assertThat(identity.get("entity_type")).isEqualTo("SOLE_TRADER");
        assertThat((String) identity.get("address_line1")).isNotBlank();
        assertThat((String) identity.get("address_city")).isNotBlank();
        assertThat((String) identity.get("address_postcode")).isNotBlank();

        TenantContext.set(DEMO_TENANT);
        for (Map<String, Object> shop : published) {
            String slug = (String) shop.get("slug");
            // example.com is reserved for documentation (RFC 2606): no real mailbox is named.
            assertThat((String) shop.get("email")).as("email on %s", slug).isEqualTo("kitchen@" + slug + ".example.com");

            VendorOnboarding probe = new VendorOnboarding();
            probe.setTenantId(DEMO_TENANT);
            probe.setShopId((UUID) shop.get("id"));
            probe.setModel(OnboardingModel.MARKETPLACE);
            GateResult result = traderIdentityGate.evaluate(probe);
            assertThat(result.status()).as("TRADER_IDENTITY gate for %s: %s", slug, result.reason())
                    .isEqualTo(GateStatus.PASSED);
        }
    }

    @Test
    void reRunningTheSeederChangesNoSellerDetail() throws Exception {
        String before = sellerDetailsSnapshot();

        demoDataSeeder.run(null);

        assertThat(sellerDetailsSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM trader_identity WHERE tenant_id = ?", Integer.class, DEMO_TENANT)).isEqualTo(1);
    }

    @Test
    void theSeederNeverOverwritesSellerDetailsAVendorEntered() throws Exception {
        String seededName = jdbc.queryForObject(
                "SELECT legal_name FROM trader_identity WHERE tenant_id = ?", String.class, DEMO_TENANT);
        String seededEmail = jdbc.queryForObject(
                "SELECT email FROM shops WHERE slug = 'peckham-jollof-co'", String.class);
        jdbc.update("UPDATE trader_identity SET legal_name = 'A Real Vendor Ltd' WHERE tenant_id = ?", DEMO_TENANT);
        jdbc.update("UPDATE shops SET email = 'orders@real-vendor.example.org' WHERE slug = 'peckham-jollof-co'");
        try {
            demoDataSeeder.run(null);

            assertThat(jdbc.queryForObject("SELECT legal_name FROM trader_identity WHERE tenant_id = ?",
                    String.class, DEMO_TENANT)).isEqualTo("A Real Vendor Ltd");
            assertThat(jdbc.queryForObject("SELECT email FROM shops WHERE slug = 'peckham-jollof-co'",
                    String.class)).isEqualTo("orders@real-vendor.example.org");
        } finally {
            // Restore, so the other arms see the seeded state whatever order JUnit picks.
            jdbc.update("UPDATE trader_identity SET legal_name = ? WHERE tenant_id = ?", seededName, DEMO_TENANT);
            jdbc.update("UPDATE shops SET email = ? WHERE slug = 'peckham-jollof-co'", seededEmail);
        }
    }

    /** Every seller detail the seeder owns, as one comparable string (version included). */
    private String sellerDetailsSnapshot() {
        String identity = jdbc.queryForObject(
                "SELECT legal_name || '|' || entity_type || '|' || address_line1 || '|' || coalesce(address_line2, '') "
                        + "|| '|' || address_city || '|' || address_postcode || '|' || version || '|' || updated_at "
                        + "FROM trader_identity WHERE tenant_id = ?", String.class, DEMO_TENANT);
        List<String> emails = jdbc.queryForList(
                "SELECT slug || '=' || coalesce(email, '<null>') FROM shops WHERE tenant_id = ? ORDER BY slug",
                String.class, DEMO_TENANT);
        return identity + "\n" + String.join("\n", emails);
    }
}
