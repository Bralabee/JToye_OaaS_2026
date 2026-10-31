package uk.jtoye.core.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.tenant.BillingPlanMode;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 Plan 30-25 (PAY-02 / D-04, T-30-96): proves that per-location billing
 * counts each tenant's own shops in isolation under the application role (NOSUPERUSER,
 * FORCE RLS active on {@code shops}).
 *
 * <p>Why the explicit {@code tenant_id} predicate in {@link ShopRepository#countByTenantId(UUID)}
 * is load-bearing even with FORCE RLS active: {@code shops_public_read} (V16) permits
 * {@code SELECT} on any row where {@code published = true} across all tenants. A bare
 * {@code count()} under {@code TENANT_A}'s RLS session would count {@code TENANT_A}'s
 * shops <em>plus</em> every other tenant's published shops.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class BillingLocationCountIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("payment.outbox.flush-interval-ms", () -> "86400000");
        registry.add("payment.outbox.resurrect-interval-ms", () -> "86400000");
    }

    private static boolean downgraded = false;

    @Autowired private ShopRepository shopRepository;
    @Autowired private StripeBillingService stripeBillingService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;
    private UUID tenantA;
    private UUID tenantB;
    private UUID tenantZero;

    @BeforeEach
    void seed() {
        txTemplate = new TransactionTemplate(transactionManager);
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        tenantZero = UUID.randomUUID();

        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())",
                tenantA, "Tenant A " + tenantA.toString().substring(0, 8));
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())",
                tenantB, "Tenant B " + tenantB.toString().substring(0, 8));
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())",
                tenantZero, "Tenant Zero " + tenantZero.toString().substring(0, 8));

        if (!downgraded) {
            jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
            downgraded = true;
        }

        // Tenant A: 2 shops (1 published, 1 unpublished)
        inTenantTx(tenantA, () -> {
            insertShop(tenantA, true);
            insertShop(tenantA, false);
            return null;
        });

        // Tenant B: 5 shops (2 published, 3 unpublished)
        inTenantTx(tenantB, () -> {
            insertShop(tenantB, true);
            insertShop(tenantB, true);
            insertShop(tenantB, false);
            insertShop(tenantB, false);
            insertShop(tenantB, false);
            return null;
        });
    }

    private void insertShop(UUID tenantId, boolean published) {
        UUID shopId = UUID.randomUUID();
        String suffix = shopId.toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, name, slug, published, delivery_fee_pennies, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0, now())",
                shopId, tenantId, "Shop " + suffix, "shop-" + suffix, published);
    }

    private <T> T inTenantTx(UUID tenantId, Supplier<T> work) {
        TenantContext.set(tenantId);
        try {
            return txTemplate.execute(status -> work.get());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("30-25 (T-30-96): two tenants with 2 and 5 shops (including published shops visible via shops_public_read) "
            + "each count only their own locations under the application role")
    void twoTenantsWithDifferentShopCounts_eachCountOnlyTheirOwnLocations() {
        long countA = inTenantTx(tenantA, () -> shopRepository.countByTenantId(tenantA));
        long countB = inTenantTx(tenantB, () -> shopRepository.countByTenantId(tenantB));
        long countZero = inTenantTx(tenantZero, () -> shopRepository.countByTenantId(tenantZero));

        assertThat(countA).as("Tenant A location count").isEqualTo(2L);
        assertThat(countB).as("Tenant B location count").isEqualTo(5L);
        assertThat(countZero).as("Tenant Zero location count").isZero();

        // Non-vacuity control: a bare count() under Tenant A sees Tenant A's 2 shops PLUS Tenant B's 2 published shops (4 total)
        long bareCountUnderA = inTenantTx(tenantA, () -> shopRepository.count());
        assertThat(bareCountUnderA)
                .as("shops_public_read exposes other tenants' published shops to a query without an explicit tenant_id predicate")
                .isGreaterThanOrEqualTo(4L);

        assertThat(inTenantTx(tenantA,
                () -> stripeBillingService.locationQuantity(tenantA, BillingPlanMode.SAAS_HYBRID_STANDARD)))
                .isEqualTo(2L);
        assertThat(inTenantTx(tenantB,
                () -> stripeBillingService.locationQuantity(tenantB, BillingPlanMode.SAAS_FLAT_PREDICTABLE)))
                .isEqualTo(5L);
        assertThat(inTenantTx(tenantB,
                () -> stripeBillingService.locationQuantity(tenantB, BillingPlanMode.WALK_SETUP_STEPDOWN)))
                .isEqualTo(1L);
    }
}
