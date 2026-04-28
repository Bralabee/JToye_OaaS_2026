package uk.jtoye.core.review;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.sql.PreparedStatement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AUDIT-W0-04 regression: the rewritten {@code reviews_tenant_write} policy
 * in V35 must:
 *
 * <ul>
 *   <li>allow the legitimate write path — either matching
 *       {@code app.current_tenant_id} (the app branch, which fires for
 *       authenticated tenant-side writes via {@code TenantSetLocalAspect}),
 *       OR matching {@code app.customer_email} together with an EXISTS proof
 *       that the writer owns an order matching the claimed tuple
 *       (the customer branch, used by anonymous storefront writes);</li>
 *   <li>block the spam-review write hole that V27's OR-clause permitted —
 *       arbitrary {@code app.customer_email} no longer suffices without the
 *       EXISTS proof against {@code orders}.</li>
 * </ul>
 *
 * <p>Test mechanics: each method drives the policy by setting the GUCs
 * (PostgreSQL {@code current_setting} runtime parameters) that the V35
 * {@code WITH CHECK} clauses read. The tenant GUC is managed via
 * {@link TenantContext} so that the existing
 * {@link uk.jtoye.core.security.TenantSetLocalAspect} fires
 * {@code set_config('app.current_tenant_id', ?, true)} on the JDBC connection
 * — this is the same path the production storefront/review services use, so
 * the test exercises the actual runtime contract. The customer-email GUC is
 * issued via raw {@code SELECT set_config('app.customer_email', ?, true)} on
 * the same Hibernate-bound connection (the aspect does not manage it). The
 * {@code @Transactional} class-level annotation guarantees both writes share
 * one connection and the {@code true} (is_local) modifier scopes the GUCs to
 * that single transaction.
 *
 * <p>Storefront-wiring confirmation finding (per
 * {@code 16.1-CONTEXT.md <decisions>} Item 4 verification ask): the production
 * storefront review-submit path is {@code ReviewService.createReview}, which
 * calls {@code TenantContext.set(shop.getTenantId())}. The
 * {@code TenantSetLocalAspect} then issues
 * {@code set_config('app.current_tenant_id', ?, true)} on the connection
 * before the INSERT — so the production review-submit path fires the
 * <strong>app branch</strong> of the V35 policy. The customer-email branch
 * is currently exercised only by defense-in-depth here; no production code
 * path POSTs reviews under the customer-email GUC alone.
 * {@code PublicStorefrontService.getCustomerOrders} sets
 * {@code app.customer_email} on the connection but that is for read-only
 * order tracking and unrelated to reviews. No production wiring change is
 * required by this plan.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class ReviewsRlsPolicyIntegrationTest {

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
        // application-test.yml sets ddl-auto: create-drop which would clobber
        // the RLS state Flyway just installed. Force Hibernate to none so the
        // Flyway-managed schema is the sole source of truth.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private UUID tenantId;
    private UUID otherTenantId;
    private UUID shopId;
    private UUID otherShopId;
    private UUID orderId;
    private UUID otherOrderId;

    /**
     * Postgres testcontainer's `test` user is created as SUPERUSER (the
     * docker postgres:15 image's default behaviour). SUPERUSERs bypass ALL
     * RLS regardless of FORCE / NOBYPASSRLS — making it impossible to
     * exercise WITH CHECK denials. Per testcontainer-rls best practice we
     * provision a dedicated non-superuser role once at suite start and
     * {@code SET LOCAL ROLE} to it for every test method's RLS-sensitive
     * INSERT.
     *
     * <p>Idempotent: {@code DO} block skips the CREATE on subsequent test
     * methods sharing the same Spring context.
     */
    private static final String RLS_TEST_ROLE = "rls_test_role";

    @BeforeEach
    void seed() {
        // Provision a dedicated non-superuser role (idempotent).
        jdbc.execute("DO $$ BEGIN " +
                "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN " +
                "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; " +
                "    GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "    GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "    GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "  END IF; " +
                "END $$");

        tenantId = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        otherShopId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        otherOrderId = UUID.randomUUID();

        // Seed two tenants (no RLS — `tenants` is the registry itself).
        // Done as superuser so the FK target rows exist before we drop privileges.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "test-" + tenantId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                otherTenantId, "test-" + otherTenantId);

        // Seed shops + orders for tenant A. TenantContext drives the
        // TenantSetLocalAspect which issues set_config('app.current_tenant_id',
        // ?, true) on the connection before each JdbcTemplate call — same
        // path the production code uses. shops.slug is NOT NULL UNIQUE
        // (V16) and uniqueness is global, so derive a UUID-based slug.
        TenantContext.set(tenantId);
        try {
            seedShop(shopId, tenantId, "shop-a-" + shopId.toString().substring(0, 8));
            seedOrder(orderId, tenantId, shopId, "alice@example.com");
        } finally {
            TenantContext.clear();
        }

        // Seed shop + order for tenant B (the "other" pair).
        TenantContext.set(otherTenantId);
        try {
            seedShop(otherShopId, otherTenantId, "shop-b-" + otherShopId.toString().substring(0, 8));
            seedOrder(otherOrderId, otherTenantId, otherShopId, "bob@example.com");
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void clearTenantContext() {
        // Belt-and-braces: even though every test branch above clears, ensure
        // cross-test ThreadLocal pollution can never happen.
        TenantContext.clear();
    }

    /**
     * Drop superuser privileges for the rest of the current transaction so
     * RLS WITH CHECK denials actually fire. The {@code SET LOCAL ROLE} is
     * scoped to the current transaction and reverts at COMMIT/ROLLBACK.
     *
     * <p>Postgres behaviour: a SUPERUSER bypasses ALL RLS regardless of
     * NOBYPASSRLS / FORCE. The Testcontainers postgres image creates the
     * test user as SUPERUSER, so without this {@code SET ROLE} dance the
     * INSERT would succeed even when the WITH CHECK clause evaluates false,
     * defeating the regression test. Switching to a freshly-provisioned
     * non-superuser role is the canonical Testcontainers-RLS pattern.
     */
    private void dropSuperuserForTransaction() {
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
    }

    /**
     * Legitimate path 1 of 2: customer-branch — both
     * {@code app.current_tenant_id} (matching) and {@code app.customer_email}
     * (matching the order owner) set. The EXISTS subquery proof succeeds.
     * INSERT succeeds.
     */
    @Test
    void legitimateInsertWithMatchingOrderSucceeds() {
        TenantContext.set(tenantId);
        setCustomerEmailGuc("alice@example.com");
        dropSuperuserForTransaction();
        // Aspect-level call uses: SELECT set_config('app.current_tenant_id', ?, true)

        int rows = jdbc.update(
                "INSERT INTO reviews (id, tenant_id, shop_id, order_id, customer_email, " +
                        "  customer_name, food_rating, delivery_rating, comment, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                UUID.randomUUID(), tenantId, shopId, orderId, "alice@example.com",
                "Alice", 5, 5, "Lovely!");
        assertThat(rows).isEqualTo(1);
    }

    /**
     * Legitimate path 2 of 2: app-branch — only {@code app.current_tenant_id}
     * is set (no {@code app.customer_email}); the row's tenant_id matches.
     * The first OR-branch of the WITH CHECK fires; the customer branch is
     * never evaluated. INSERT succeeds.
     *
     * <p>This is the production code path: {@code ReviewService.createReview}
     * sets {@link TenantContext} → the aspect issues
     * {@code set_config('app.current_tenant_id', ?, true)} → INSERT.
     */
    @Test
    void appBranchInsertWithMatchingTenantSucceeds() {
        TenantContext.set(tenantId);
        // Deliberately do NOT set app.customer_email; only the app branch should fire.
        // Aspect-level call uses: SELECT set_config('app.current_tenant_id', ?, true)
        dropSuperuserForTransaction();

        int rows = jdbc.update(
                "INSERT INTO reviews (id, tenant_id, shop_id, order_id, customer_email, " +
                        "  customer_name, food_rating, delivery_rating, comment, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                UUID.randomUUID(), tenantId, shopId, orderId, "alice@example.com",
                "Alice (vendor-side moderation insert)", 4, 4, null);
        assertThat(rows).isEqualTo(1);
    }

    /**
     * Spam-review attack: the V27 OR-clause hole. Arbitrary
     * {@code app.current_tenant_id} (random — does not match the row's
     * {@code tenant_id}) and arbitrary {@code app.customer_email}
     * ({@code attacker@evil.com}, which does not match any order). Under V27
     * the customer branch's bare email-equality permitted this; under V35
     * the EXISTS subquery requires an actual order row owned by that email
     * + tenant_id + order_id tuple, so the policy denies the INSERT.
     */
    @Test
    void spamReviewWithArbitraryTenantIsBlocked() {
        UUID attackerTenant = UUID.randomUUID();
        TenantContext.set(attackerTenant);
        setCustomerEmailGuc("attacker@evil.com");
        dropSuperuserForTransaction();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO reviews (id, tenant_id, shop_id, order_id, customer_email, " +
                        "  customer_name, food_rating, delivery_rating, comment, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                UUID.randomUUID(), otherTenantId, otherShopId, otherOrderId, "attacker@evil.com",
                "Spam!", 1, 1, "FAKE REVIEW"))
                .isInstanceOf(DataAccessException.class)
                // Postgres reports "ERROR: new row violates row-level security
                // policy" on the root PSQLException; Spring wraps it in a
                // BadSqlGrammarException whose top-level message hides that
                // text. Use hasStackTraceContaining so the assertion matches
                // the wrapped chain instead of just the outer message.
                .hasStackTraceContaining("row-level security");
    }

    /**
     * Email spoofing: the order belongs to {@code bob@example.com}; the
     * attacker sets {@code app.customer_email = alice@example.com} and tries
     * to insert a review on Bob's order under Bob's email. The customer
     * branch's {@code current_setting('app.customer_email') = customer_email}
     * fails (alice ≠ bob). The app branch fails on tenant mismatch. INSERT
     * is denied.
     */
    @Test
    void customerEmailMismatchIsBlocked() {
        UUID attackerTenant = UUID.randomUUID();
        TenantContext.set(attackerTenant);
        setCustomerEmailGuc("alice@example.com");
        dropSuperuserForTransaction();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO reviews (id, tenant_id, shop_id, order_id, customer_email, " +
                        "  customer_name, food_rating, delivery_rating, comment, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                UUID.randomUUID(), otherTenantId, otherShopId, otherOrderId, "bob@example.com",
                "Spoofed", 5, 5, "Faking ownership"))
                .isInstanceOf(DataAccessException.class)
                // Postgres reports "ERROR: new row violates row-level security
                // policy" on the root PSQLException; Spring wraps it in a
                // BadSqlGrammarException whose top-level message hides that
                // text. Use hasStackTraceContaining so the assertion matches
                // the wrapped chain instead of just the outer message.
                .hasStackTraceContaining("row-level security");
    }

    /**
     * Defense-in-depth: when {@code app.customer_email} is empty, the
     * customer branch's {@code IS NOT NULL AND <> ''} guard short-circuits
     * the EXISTS clause to false. Combined with a tenant_id mismatch on the
     * app branch, the INSERT is denied.
     */
    @Test
    void nullCustomerEmailGucShortCircuitsCustomerBranch() {
        UUID attackerTenant = UUID.randomUUID();
        TenantContext.set(attackerTenant);
        // Blank customer_email so the IS NOT NULL / <> '' guards reject the
        // customer branch. Belt-and-braces: explicitly setting to '' is
        // safer than "do nothing" because a session-level value could leak
        // from outside the test.
        setCustomerEmailGuc("");
        dropSuperuserForTransaction();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO reviews (id, tenant_id, shop_id, order_id, customer_email, " +
                        "  customer_name, food_rating, delivery_rating, comment, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                UUID.randomUUID(), tenantId, shopId, orderId, "alice@example.com",
                "Alice", 5, 5, null))
                .isInstanceOf(DataAccessException.class)
                // Postgres reports "ERROR: new row violates row-level security
                // policy" on the root PSQLException; Spring wraps it in a
                // BadSqlGrammarException whose top-level message hides that
                // text. Use hasStackTraceContaining so the assertion matches
                // the wrapped chain instead of just the outer message.
                .hasStackTraceContaining("row-level security");
    }

    // --- helpers ---

    /**
     * Set the {@code app.customer_email} GUC on the Hibernate-bound JDBC
     * connection (the same connection that JdbcTemplate uses inside this
     * transaction). Bypasses {@link JdbcTemplate} so that
     * {@link uk.jtoye.core.security.TenantSetLocalAspect} doesn't fire and
     * reset the tenant GUC behind our back. The {@code true} (is_local)
     * modifier scopes the value to the active transaction.
     *
     * <p>Mirrors the canonical wiring used in
     * {@code PublicStorefrontService.getCustomerOrders} where the storefront
     * sets {@code app.customer_email} via
     * {@code session.doWork(c -> c.prepareStatement("SELECT set_config('app.customer_email', ?, true)"))}.
     */
    private void setCustomerEmailGuc(String value) {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "SELECT set_config('app.customer_email', ?, true)")) {
                stmt.setString(1, value);
                stmt.execute();
            }
        });
    }

    private void seedShop(UUID id, UUID tenant, String slug) {
        // V16 makes shops.slug NOT NULL with a global UNIQUE index. V26 dropped
        // the DEFAULT on shops.delivery_fee_pennies (NOT NULL), so it must be
        // supplied. V32 added a NOT NULL `version` column with DEFAULT 0.
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, " +
                        "  delivery_fee_pennies) " +
                        "VALUES (?, ?, ?, ?, ?, true, 0)",
                id, tenant, "shop-" + id, slug, "Test Address");
    }

    private void seedOrder(UUID id, UUID tenant, UUID shop, String email) {
        // V5/V21/V22/V23/V24/V26/V32 columns combined. V23 dropped DEFAULTs on
        // subtotal_pennies / vat_rate / vat_amount_pennies; V26 dropped DEFAULT
        // on delivery_fee_pennies. All are NOT NULL and must be supplied
        // explicitly. Status is the V5 enum type — supply COMPLETED so the
        // order looks legitimate, but the policy doesn't read status, only
        // id + tenant_id + customer_email.
        jdbc.update("INSERT INTO orders (id, tenant_id, shop_id, order_number, status, " +
                        "  customer_email, customer_name, total_amount_pennies, delivery_fee_pennies, " +
                        "  subtotal_pennies, vat_rate, vat_amount_pennies) " +
                        "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, 1000, 0, 1000, 'ZERO', 0)",
                id, tenant, shop,
                "ORD-" + id.toString().substring(0, 8),
                email, "Test " + email);
    }
}
