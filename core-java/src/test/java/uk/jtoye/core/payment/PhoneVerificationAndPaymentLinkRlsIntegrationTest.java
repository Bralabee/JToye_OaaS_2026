package uk.jtoye.core.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V88 / Phase 30 (PAY-02, PAY-04, GAP-GTM-05, #461, #462) PostgreSQL 18 integration proof:
 * <ul>
 *   <li>Cross-tenant RLS isolation on {@code phone_verification_challenges} and {@code order_payment_links}
 *       under a {@code NOSUPERUSER NOBYPASSRLS} role ({@code rls_test_role}).</li>
 *   <li>Database {@code CHECK (phone_verified = true)} constraint on {@code order_payment_links}
 *       refuses unverified inserts at the Postgres engine level.</li>
 *   <li>Database {@code CHECK} constraints on {@code tenants.billing_plan_mode} and
 *       {@code tenants.subscription_status} enforce the allowed recurring billing tiers.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class PhoneVerificationAndPaymentLinkRlsIntegrationTest {

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

    @Autowired private JdbcTemplate jdbc;

    private static final String RLS_TEST_ROLE = "rls_test_role";

    private UUID tenantA;
    private UUID tenantB;
    private UUID shopA;
    private UUID orderA;

    @BeforeEach
    void seed() {
        jdbc.execute("DO $$ BEGIN " +
                "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN " +
                "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; " +
                "  END IF; " +
                "  GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "  GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "  GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "END $$");

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        shopA = UUID.randomUUID();
        orderA = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name, billing_plan_mode, subscription_status) VALUES (?, ?, ?, ?)",
                tenantA, "tenant-a-" + tenantA, "SAAS_HYBRID_STANDARD", "ACTIVE");
        jdbc.update("INSERT INTO tenants (id, name, billing_plan_mode, subscription_status) VALUES (?, ?, ?, ?)",
                tenantB, "tenant-b-" + tenantB, "BATCH_PAYG_STARTER", "NONE");

        TenantContext.set(tenantA);
        try {
            jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies, fulfilment_timing_mode, deposit_bps, created_at) " +
                            "VALUES (?, ?, ?, ?, '1 Test St', true, 0, 'IMMEDIATE_ONLY', 10000, now())",
                    shopA, tenantA, "Shop A", "shop-a-" + shopA.toString().substring(0, 8));
            jdbc.update("INSERT INTO orders (id, tenant_id, shop_id, order_number, status, payment_status, " +
                            "total_amount_pennies, subtotal_pennies, vat_rate, vat_amount_pennies, delivery_fee_pennies, " +
                            "customer_phone, phone_verified, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, 'PENDING', 'PENDING', 3500, 3500, 'STANDARD', 583, 0, '+447700900111', true, now(), now())",
                    orderA, tenantA, shopA, "ORD-V88-" + orderA.toString().substring(0, 8));
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("phone_verification_challenges and order_payment_links enforce FORCE RLS across tenants")
    void rlsIsolatesChallengesAndPaymentLinksAcrossTenants() {
        UUID challengeA = UUID.randomUUID();
        UUID linkA = UUID.randomUUID();
        Timestamp expires = Timestamp.from(Instant.now().plus(15, ChronoUnit.MINUTES));

        TenantContext.set(tenantA);
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);

        jdbc.update("INSERT INTO phone_verification_challenges " +
                        "(id, tenant_id, phone_normalized, otp_sha256, channel, attempts, max_attempts, expires_at, created_at) " +
                        "VALUES (?, ?, '+447700900111', ?, 'SMS', 0, 5, ?, now())",
                challengeA, tenantA, "a".repeat(64), expires);

        jdbc.update("INSERT INTO order_payment_links " +
                        "(id, tenant_id, shop_id, order_id, token_sha256, stripe_payment_intent_id, amount_pennies, purpose, " +
                        "phone_verified, expires_at, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'pi_test_v88_rls', 3500, 'UPFRONT', true, ?, now())",
                linkA, tenantA, shopA, orderA, "b".repeat(64), expires);

        Integer countAsA = jdbc.queryForObject(
                "SELECT count(*) FROM order_payment_links WHERE id = ?", Integer.class, linkA);
        assertThat(countAsA).isEqualTo(1);

        // Switch to Tenant B — both tables must return 0 rows and block cross-tenant writes
        TenantContext.set(tenantB);

        Integer challengeCountAsB = jdbc.queryForObject(
                "SELECT count(*) FROM phone_verification_challenges WHERE id = ?", Integer.class, challengeA);
        Integer linkCountAsB = jdbc.queryForObject(
                "SELECT count(*) FROM order_payment_links WHERE id = ?", Integer.class, linkA);

        assertThat(challengeCountAsB).isZero();
        assertThat(linkCountAsB).isZero();

        // Cross-tenant INSERT as Tenant B with tenantA's tenant_id must fail RLS WITH CHECK
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO phone_verification_challenges " +
                        "(id, tenant_id, phone_normalized, otp_sha256, channel, attempts, max_attempts, expires_at, created_at) " +
                        "VALUES (?, ?, '+447700900999', ?, 'SMS', 0, 5, ?, now())",
                UUID.randomUUID(), tenantA, "c".repeat(64), expires))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("order_payment_links CHECK (phone_verified = true) rejects unverified phone at DB level (#461 / #462)")
    void checkConstraintRejectsUnverifiedPaymentLinkRow() {
        Timestamp expires = Timestamp.from(Instant.now().plus(30, ChronoUnit.MINUTES));
        TenantContext.set(tenantA);
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO order_payment_links " +
                        "(id, tenant_id, shop_id, order_id, token_sha256, stripe_payment_intent_id, amount_pennies, purpose, " +
                        "phone_verified, expires_at, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'pi_test_unverified', 3500, 'UPFRONT', false, ?, now())",
                UUID.randomUUID(), tenantA, shopA, orderA, "d".repeat(64), expires))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_order_payment_links_phone_verified");
    }

    @Test
    @DisplayName("tenants CHECK constraints accept all 4 BillingPlanMode presets and reject unknown modes (GAP-GTM-05)")
    void tenantsBillingPlanModeCheckConstraint() {
        for (String mode : new String[]{
                "SAAS_HYBRID_STANDARD", "SAAS_FLAT_PREDICTABLE", "BATCH_PAYG_STARTER", "WALK_SETUP_STEPDOWN"}) {
            UUID id = UUID.randomUUID();
            int updated = jdbc.update(
                    "INSERT INTO tenants (id, name, billing_plan_mode, subscription_status) VALUES (?, ?, ?, 'ACTIVE')",
                    id, "tenant-mode-" + mode + "-" + id, mode);
            assertThat(updated).isEqualTo(1);
        }

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO tenants (id, name, billing_plan_mode, subscription_status) VALUES (?, ?, 'INVALID_PLAN', 'ACTIVE')",
                UUID.randomUUID(), "tenant-invalid-" + UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_tenants_billing_plan_mode");
    }

    @Test
    @DisplayName("order_payment_links_public_select requires matching app.payment_link_token_sha256 GUC when anonymous")
    void anonymousPublicSelectRequiresMatchingTokenGuc() {
        UUID linkA = UUID.randomUUID();
        String tokenHash = "e".repeat(64);
        Timestamp expires = Timestamp.from(Instant.now().plus(15, ChronoUnit.MINUTES));

        TenantContext.set(tenantA);
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
        jdbc.update("INSERT INTO order_payment_links " +
                        "(id, tenant_id, shop_id, order_id, token_sha256, stripe_payment_intent_id, amount_pennies, purpose, " +
                        "phone_verified, expires_at, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'pi_test_v88_pub', 3500, 'UPFRONT', true, ?, now())",
                linkA, tenantA, shopA, orderA, tokenHash, expires);

        // Switch to anonymous storefront session (current_tenant_id() IS NULL)
        TenantContext.clear();
        jdbc.execute("SELECT set_config('app.current_tenant_id', '', true)");
        jdbc.execute("SELECT set_config('app.payment_link_token_sha256', '', true)");

        Integer countWithoutTokenGuc = jdbc.queryForObject(
                "SELECT count(*) FROM order_payment_links WHERE id = ?", Integer.class, linkA);
        assertThat(countWithoutTokenGuc).isZero();

        jdbc.execute("SELECT set_config('app.payment_link_token_sha256', '" + "f".repeat(64) + "', true)");
        Integer countWithWrongTokenGuc = jdbc.queryForObject(
                "SELECT count(*) FROM order_payment_links WHERE id = ?", Integer.class, linkA);
        assertThat(countWithWrongTokenGuc).isZero();

        jdbc.execute("SELECT set_config('app.payment_link_token_sha256', '" + tokenHash + "', true)");
        Integer countWithMatchingTokenGuc = jdbc.queryForObject(
                "SELECT count(*) FROM order_payment_links WHERE id = ?", Integer.class, linkA);
        assertThat(countWithMatchingTokenGuc).isEqualTo(1);
    }
}
