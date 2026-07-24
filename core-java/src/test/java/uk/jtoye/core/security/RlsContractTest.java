package uk.jtoye.core.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUDIT-W0-04 / AUDIT-W0-05 contract test: every regular table in the public
 * schema must have BOTH {@code ENABLE ROW LEVEL SECURITY} and
 * {@code FORCE ROW LEVEL SECURITY} unless explicitly exempted with a written
 * justification in {@link #EXEMPT_TABLES}.
 *
 * <p>Catches the recurring "we added a tenant table and forgot RLS" failure
 * mode that drove the V11→V4, V14→V9, V15→V5, V33→V27/28/29 retro-patches
 * (see {@code docs/audit/remediation/03-database-remediation.md} Finding 11).
 *
 * <p>Implements the schema-walk approach LOCKED in
 * {@code .planning/phases/16.1-pre-prod-hardening/16.1-CONTEXT.md} cross-cutting
 * decision: walk every relation in {@code pg_class} where
 * {@code relkind = 'r' AND relnamespace = 'public'::regnamespace} rather than
 * a hardcoded positive list. This way a future Flyway migration that adds a
 * tenant-scoped table without RLS+FORCE breaks the build instead of silently
 * leaking across tenants.
 *
 * <p>Runs against Testcontainers Postgres 15 so the real V35 migration is
 * exercised end-to-end. The same JDBC properties are overridden as in
 * {@code StripeWebhookIdempotencyIntegrationTest} because
 * {@code src/test/resources/application-test.yml} otherwise routes the tests
 * to H2 (which has no row-level security implementation).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class RlsContractTest {

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
        // src/test/resources/application-test.yml defaults to H2; override every
        // property that yml sets so Testcontainers Postgres is actually used.
        // Mirrors StripeWebhookIdempotencyIntegrationTest's pattern.
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // application-test.yml sets ddl-auto: create-drop which would clobber the
        // RLS state Flyway just installed. Force Hibernate to none so the
        // Flyway-managed schema is the sole source of truth and
        // pg_class.relrowsecurity / relforcerowsecurity reflect the migrations.
        // (validate would also work in principle but our entities and Flyway
        // schemas have minor mismatches that are out of scope for this test.)
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        // RabbitMQ is referenced by OrderEventPublisher; point at a dead port
        // and disable listener auto-startup so the context boots without a
        // live broker.
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired private JdbcTemplate jdbc;

    /**
     * Tables in the public schema that are intentionally NOT tenant-scoped and
     * therefore not RLS-controlled. Every entry needs a written justification.
     *
     * <p>If a future migration adds a public table that legitimately doesn't
     * need RLS (e.g. another infrastructure-level idempotency log), add it
     * here with a comment — DO NOT weaken the assertion below.
     */
    private static final Set<String> EXEMPT_TABLES = Set.of(
            // Flyway migration metadata; managed by Flyway itself.
            "flyway_schema_history",

            // AUDIT-W0-03 idempotency log. Stripe event_ids are globally unique
            // across all tenants, the webhook handler runs BEFORE TenantContext
            // is set, and this is infrastructure idempotency rather than tenant
            // data. Per V35 comment + 16.1-CONTEXT.md <decisions> Item 3 LOCKED:
            // "NOT RLS-enabled".
            "processed_stripe_events",

            // The tenant registry itself — `tenants` IS the source of identity
            // for every tenant_id, it has no tenant_id column of its own.
            // Managed via privileged admin connection per V2__rls_policies.sql
            // closing comment: "you may manage tenants via privileged admin
            // connection".
            "tenants",

            // Hibernate Envers revision metadata. Audit-row writes are global
            // (one revision row per transaction, regardless of tenant); per-
            // tenant filtering of audit reads happens in the *_aud child
            // tables (which DO have RLS+FORCE). See V8__add_tenant_context_to_revinfo.sql.
            "revinfo"
    );

    /**
     * Drift-prevention sweep: every regular table in the public schema MUST
     * have BOTH RLS enabled AND forced unless it is in {@link #EXEMPT_TABLES}.
     */
    @Test
    void everyPublicTableHasRlsAndForce() {
        List<Map<String, Object>> tables = jdbc.queryForList(
                "SELECT relname, relrowsecurity, relforcerowsecurity " +
                        "FROM pg_class " +
                        "WHERE relkind = 'r' " +
                        "  AND relnamespace = 'public'::regnamespace " +
                        "ORDER BY relname");

        for (Map<String, Object> row : tables) {
            String name = (String) row.get("relname");
            if (EXEMPT_TABLES.contains(name)) continue;

            assertThat(row.get("relrowsecurity"))
                    .as("ENABLE ROW LEVEL SECURITY missing on public.%s — every tenant-scoped " +
                            "table must enable RLS in its Flyway migration. If %s is " +
                            "intentionally not tenant-scoped, add it to RlsContractTest.EXEMPT_TABLES " +
                            "with a written justification.", name, name)
                    .isEqualTo(true);

            assertThat(row.get("relforcerowsecurity"))
                    .as("FORCE ROW LEVEL SECURITY missing on public.%s — table-owner / " +
                            "superuser would bypass tenant isolation. Add `ALTER TABLE %s " +
                            "FORCE ROW LEVEL SECURITY` to the migration that creates the table.",
                            name, name)
                    .isEqualTo(true);
        }
    }

    /**
     * AUDIT-W0-05 sentinel: redundant with {@link #everyPublicTableHasRlsAndForce}
     * but produces a sharper failure message naming the specific table when V35
     * is missed or its FORCE clauses are partially reverted. The 9 tables
     * listed are LOCKED in 16.1-CONTEXT.md Item 5.
     */
    @Test
    void auditW0_05_targetTablesAreForced() {
        List<String> w0_05_targets = List.of(
                "reviews", "shop_promotions", "shop_announcements",
                "customers_aud", "shops_aud", "products_aud",
                "financial_transactions_aud", "orders_aud", "order_items_aud"
        );

        for (String t : w0_05_targets) {
            Boolean forced = jdbc.queryForObject(
                    "SELECT relforcerowsecurity FROM pg_class " +
                            "WHERE relkind = 'r' AND relnamespace = 'public'::regnamespace " +
                            "  AND relname = ?",
                    Boolean.class, t);
            assertThat(forced)
                    .as("AUDIT-W0-05 target %s missing FORCE ROW LEVEL SECURITY — V35 must add it", t)
                    .isNotNull()
                    .isEqualTo(true);
        }
    }

    /**
     * IMG-01 (Phase 24, V53) sentinel: the copy-on-write media layer tables must all
     * carry FORCE ROW LEVEL SECURITY. Redundant with
     * {@link #everyPublicTableHasRlsAndForce} (the dynamic pg_class sweep already
     * covers them) but names the specific table when a future edit drops FORCE on the
     * asset/link/audit tables — the tenant wall behind safe vendor image sharing.
     */
    @Test
    void img01_mediaTablesAreForced() {
        List<String> mediaTargets = List.of("media_asset", "product_media", "media_asset_aud");

        for (String t : mediaTargets) {
            Boolean forced = jdbc.queryForObject(
                    "SELECT relforcerowsecurity FROM pg_class " +
                            "WHERE relkind = 'r' AND relnamespace = 'public'::regnamespace " +
                            "  AND relname = ?",
                    Boolean.class, t);
            assertThat(forced)
                    .as("IMG-01 media table %s missing FORCE ROW LEVEL SECURITY — V53 must add it", t)
                    .isNotNull()
                    .isEqualTo(true);
        }
    }

    /**
     * AUDIT-W0-04 sentinel: ensure no policy in the database still references
     * the buggy GUC name {@code app.tenant_id} (replaced by V35 with the
     * canonical {@code app.current_tenant_id}). This guards against a future
     * migration accidentally re-introducing the typo.
     */
    @Test
    void noPolicyReadsBuggyAppTenantIdGuc() {
        List<String> bad = jdbc.queryForList(
                "SELECT polname || ' on ' || polrelid::regclass::text " +
                        "FROM pg_policy " +
                        "WHERE pg_get_expr(polqual, polrelid)      LIKE '%app.tenant_id%' " +
                        "   OR pg_get_expr(polwithcheck, polrelid) LIKE '%app.tenant_id%'",
                String.class);
        assertThat(bad)
                .as("policies referencing forbidden GUC `app.tenant_id` " +
                        "(canonical is `app.current_tenant_id`)")
                .isEmpty();
    }

    /**
     * Issue #113 [P3-11] permanent drift guard: no policy may read the tenant
     * GUC via the raw {@code current_setting('app.current_tenant_id', true)::uuid}
     * cast. That cast is a query-level constant Postgres evaluates at query init,
     * so an empty / malformed GUC raises 22P02 ("invalid input syntax for type
     * uuid") even on an empty table — the latent crash V39 removed from the
     * storefront SELECT policies and V51 removed from the remaining ten.
     *
     * <p>The canonical accessor is the safe helper {@code current_tenant_id()}
     * (renders as {@code current_tenant_id()} in {@code pg_get_expr}), which
     * returns NULL for a bad GUC instead of crashing. The predicate is scoped to
     * the {@code ::uuid} cast on purpose: the safe
     * {@code (tenant_id)::text = current_setting('app.current_tenant_id', true)}
     * TEXT comparisons (orders / customers / shop_*_write) read the same GUC but
     * never cast it to uuid, carry no 22P02 risk, and are legitimately out of
     * this bug class — matching them here would be a false positive.
     */
    @Test
    void noPolicyUsesRawTenantGucCast() {
        List<String> bad = jdbc.queryForList(
                "SELECT polname || ' on ' || polrelid::regclass::text " +
                        "FROM pg_policy " +
                        "WHERE (pg_get_expr(polqual, polrelid)      LIKE '%current_setting(''app.current_tenant_id''%' " +
                        "       AND pg_get_expr(polqual, polrelid)      LIKE '%::uuid%') " +
                        "   OR (pg_get_expr(polwithcheck, polrelid)  LIKE '%current_setting(''app.current_tenant_id''%' " +
                        "       AND pg_get_expr(polwithcheck, polrelid)  LIKE '%::uuid%')",
                String.class);
        assertThat(bad)
                .as("policies still using the raw `current_setting('app.current_tenant_id', true)::uuid` " +
                        "cast (22P02 bug class — use the safe helper current_tenant_id() instead; see V51)")
                .isEmpty();
    }
}
