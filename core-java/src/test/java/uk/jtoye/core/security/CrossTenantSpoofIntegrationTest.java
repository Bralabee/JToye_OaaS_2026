package uk.jtoye.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-01 integration tests: application-layer tenant validation for guest-path
 * endpoints. Verifies that a JWT-authenticated caller for tenant A cannot
 * spoof tenant B's slug on {@code /public/shops/{slug}/...} and extract or
 * mutate tenant-scoped data.
 *
 * <p>Runs against Testcontainers PostgreSQL (NOT H2) so real RLS policies
 * and Postgres-specific types are exercised (Phase 12 Deviation #4 pattern).
 * JWT claim key is {@code tenant_id} — matches the canonical Keycloak
 * claim that {@code JwtTenantFilter} reads first (RESEARCH.md Pitfall 3).
 *
 * <p>Threat coverage: STRIDE T-13-01 Tampering, T-13-02 Information
 * Disclosure, T-13-03 Elevation of Privilege (cross-tenant write via
 * guest order endpoint).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class CrossTenantSpoofIntegrationTest {

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
        // Phase 12 Deviation #4 — override H2 defaults from application-test.yml so
        // the Testcontainers PostgreSQL image is used with real Flyway migrations.
        // RLS policies only exist on Postgres; H2 would pass tests vacuously.
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        // RabbitMQ stubs (Phase 12 Deviation #3) — OrderEventPublisher has a
        // compile-time RabbitTemplate dependency; redirect broker to a dead port
        // with listener auto-startup disabled so context boots without a live broker.
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ShopRepository shopRepository;

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private String slugA;
    private String slugB;

    @BeforeEach
    void setUp() {
        // Mirror MultiTenantIsolationIntegrationTest.java:74-86 seeding pattern.
        // Explicit created_at = now() — observed schema drift where the V1 DEFAULT
        // did not apply in the Testcontainers image used here; belt-and-braces
        // ensures the NOT NULL constraint is satisfied without relying on DB defaults.
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_A, "Tenant A");
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_B, "Tenant B");
        slugA = createPublishedShop(TENANT_A, "Shop A");
        slugB = createPublishedShop(TENANT_B, "Shop B");
    }

    private String createPublishedShop(UUID tenantId, String name) {
        // Slug must be globally unique (shops.slug UNIQUE constraint) AND stable
        // across @BeforeEach runs in the same Spring context — use the LAST 8 hex
        // chars of the tenant UUID so TENANT_A (ends 0001) and TENANT_B (ends 0002)
        // get distinct slugs (substring(0, 8) would collide on both leading zeros).
        String tenantSuffix = tenantId.toString().replace("-", "");
        tenantSuffix = tenantSuffix.substring(tenantSuffix.length() - 8);
        String slug = "shop-" + tenantSuffix;

        // Idempotent-insert: @BeforeEach runs per test method and there's no
        // @Transactional rollback — skip if a shop with this slug already exists.
        TenantContext.set(tenantId);
        try {
            if (shopRepository.findBySlugAndPublishedTrue(slug).isPresent()) {
                return slug;
            }
            Shop shop = new Shop();
            shop.setTenantId(tenantId);
            shop.setName(name);
            shop.setSlug(slug);
            shop.setPublished(true);
            // Non-null columns with defaults (see Shop.java:64-68) — values accepted as-is.
            shopRepository.save(shop);
            return slug;
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void crossTenantJwtReturns403OnProducts() throws Exception {
        // Caller holds JWT for TENANT_A, tries to list TENANT_B's products via public endpoint.
        // Must be rejected with 403 BEFORE the service overwrites TenantContext with tenant B.
        mockMvc.perform(get("/public/shops/{slug}/products", slugB)
                .with(jwt().jwt(j -> j.claim("tenant_id", TENANT_A.toString()))))
            .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantJwtReturns403OnReviews() throws Exception {
        // Symmetric check — ReviewService.getShopReviews must reject cross-tenant
        // JWT the same way as PublicStorefrontService. Confirms SEC-01 coverage
        // extends to the reviews path (RESEARCH.md Assumption A2 resolved).
        mockMvc.perform(get("/public/shops/{slug}/reviews", slugB)
                .with(jwt().jwt(j -> j.claim("tenant_id", TENANT_A.toString()))))
            .andExpect(status().isForbidden());
    }
}
