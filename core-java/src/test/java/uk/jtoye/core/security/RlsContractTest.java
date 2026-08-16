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
            "revinfo",

            // V61 (Phase 33 / plan 33-02) public reference data: GB postcode unit
            // centroids from OS Code-Point Open, under the Open Government Licence.
            // It has NO tenant_id column and no tenant dimension to scope by — the
            // postcode of a public address is not tenant information, and there are
            // no customer rows here at all. Every tenant reads the same 1,748,230
            // rows, and that is the intended behaviour: a shop in Peckham and a shop
            // in Cardiff must resolve against one shared table or distance ranking
            // means nothing across tenants.
            //
            // Adding RLS here would not be "safer" — with no tenant_id there is no
            // predicate to write, so a FORCE'd policy would return zero rows to
            // every caller and silently disable locality platform-wide while every
            // test stayed green. Note this is exempted BY ADDITION, per the standing
            // instruction above; the schema-walk assertion itself is untouched.
            "postcode_centroid",

            // V62 (Phase 31 / plan 31-05, D-16/D-17): the platform-level UK-GDPR
            // data-subject-request intake queue. It holds a request type, a few
            // timestamps, an opaque acknowledgement body and a one-way SHA-256 digest
            // of the subject's address — no readable personal data and, critically, no
            // tenant_id, because it CANNOT have one: an anonymous data subject lodges
            // the request from the public internet before any tenant is known (no JWT,
            // no TenantContext, no app.current_tenant_id on the connection), and the
            // whole purpose of the row is to be actioned across EVERY tenant. Articles
            // 15 and 17 give the subject one right against the controller, not one per
            // vendor they happened to buy from; splitting the row per tenant at intake
            // would require the intake to already know the answer the background sweep
            // exists to discover.
            //
            // Adding RLS here would not be "safer" — it would silently DISABLE the DSAR
            // path. With no tenant_id there is no predicate to write, so a FORCE'd policy
            // would return zero rows to the very worker (plan 31-09) that must read them:
            // the intake would keep returning 202, the queue would keep filling, nothing
            // would ever be actioned, and every test would stay green because a dead table
            // is indistinguishable from an empty one — the exact liveness failure mode
            // everyRlsEnabledTableHasAtLeastOnePolicy below was added to catch. The tenant
            // wall is not weakened by this: the reach that touches tenant data belongs to
            // the background worker, which gets it by iterating tenants and pinning the
            // GUC one at a time, under FORCE RLS exactly like every other caller.
            //
            // Exempted BY ADDITION, per the standing instruction above; the schema-walk
            // assertion itself is untouched.
            "dsar_request"
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
     * D-13 (Phase 28 / plan 28-01) companion sweep: every public table that has RLS
     * ENABLED must also carry AT LEAST ONE policy.
     *
     * <p><strong>Why this is a LIVENESS defect and not a leak.</strong> PostgreSQL's
     * row-security default is deny: with {@code ENABLE ROW LEVEL SECURITY} on and no
     * policy present, the table returns zero rows and accepts zero writes for every
     * non-bypassing role. Nothing escapes. So this sweep does not protect tenant data —
     * {@link #everyPublicTableHasRlsAndForce} does that. It protects the FEATURE built
     * on the table, which is worth catching precisely because a silently-dead table is
     * indistinguishable from an empty one: every query succeeds, every count is 0, every
     * test that seeds nothing stays green, and the surface reads as "no data yet" rather
     * than "unreachable". The 33-02 {@code postcode_centroid} note records the same
     * reasoning from the other direction — a FORCE'd policy-less table would "silently
     * disable locality platform-wide while every test stayed green".
     *
     * <p>Generalises {@code DatabaseConfigurationValidator.validateRlsPolicies}, which
     * makes the same check but only over five hardcoded tables
     * ({@code shops, products, orders, customers, financial_transactions}). This walks
     * the catalog, so a future migration that enables RLS and forgets its policy breaks
     * the build instead of shipping a dead table.
     *
     * <p><strong>Exemptions are BY ADDITION.</strong> A table that legitimately has RLS
     * enabled with no policy goes in {@link #EXEMPT_TABLES} with a written justification,
     * exactly as the sweep above requires — the assertion itself is never weakened. Note
     * that today no such table exists: every entry in {@code EXEMPT_TABLES} is exempt
     * because it has RLS OFF, so this method skips them for a different reason than
     * {@link #everyPublicTableHasRlsAndForce} does, and reaching them here at all would
     * itself be new information.
     *
     * <p><strong>The denominator is load-bearing, not decoration.</strong> "Zero tables
     * without a policy" is satisfied vacuously by a query that returns nothing at all —
     * a mistyped catalog name, a schema filter that matches nothing, a {@code relkind}
     * that excludes every row. The {@code >= 30} floor is the same "found nothing is
     * never clean" contract {@code scripts/check-no-create-extension.sh:67} states as
     * "refusing to report clean over an empty scan". Measured live 2026-08-10: 36 public
     * tables have RLS enabled and all 36 carry at least one policy; the floor is set
     * below that so ordinary schema churn does not red the build, while an empty or
     * near-empty scan still does.
     */
    @Test
    void everyRlsEnabledTableHasAtLeastOnePolicy() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.relname, count(p.oid) AS policy_count " +
                        "FROM pg_class c " +
                        "LEFT JOIN pg_policy p ON p.polrelid = c.oid " +
                        "WHERE c.relkind = 'r' " +
                        "  AND c.relnamespace = 'public'::regnamespace " +
                        "  AND c.relrowsecurity = true " +
                        "GROUP BY c.relname " +
                        "ORDER BY c.relname");

        int tablesWithAtLeastOnePolicy = 0;

        for (Map<String, Object> row : rows) {
            String name = (String) row.get("relname");
            if (EXEMPT_TABLES.contains(name)) continue;

            long policyCount = ((Number) row.get("policy_count")).longValue();

            assertThat(policyCount)
                    .as("public.%s has ENABLE ROW LEVEL SECURITY but ZERO policies — PostgreSQL's " +
                            "row-security default is deny, so this table now returns no rows and " +
                            "accepts no writes for the application role, and every feature built on " +
                            "it is silently dead while all its queries still succeed. Add the tenant " +
                            "policy to the migration that enables RLS on %s (see V2__rls_policies.sql " +
                            "for the shape, and use the safe current_tenant_id() helper, never a raw " +
                            "current_setting(...)::uuid cast). If %s is genuinely meant to have RLS on " +
                            "with no policy, add it to RlsContractTest.EXEMPT_TABLES BY ADDITION with a " +
                            "written justification — DO NOT weaken this assertion.", name, name, name)
                    .isGreaterThanOrEqualTo(1L);

            tablesWithAtLeastOnePolicy++;
        }

        assertThat(tablesWithAtLeastOnePolicy)
                .as("NON-VACUITY DENOMINATOR: this sweep observed only %d non-exempt table(s) with " +
                        "RLS enabled and at least one policy, but the schema is known to carry ~36. " +
                        "The 'zero policy-less tables' result above is therefore evidence about the " +
                        "QUERY, not about the schema — a mistyped catalog name, a namespace filter " +
                        "that matches nothing, or a relkind that excludes every row all satisfy it " +
                        "while proving nothing. Fix the walk rather than lowering this floor.",
                        tablesWithAtLeastOnePolicy)
                .isGreaterThanOrEqualTo(30);
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
     * LGL-01 (Phase 31 / plan 31-05, V62) sentinel: {@code dsar_request}'s EXEMPT_TABLES
     * justification says the table "CANNOT have" a {@code tenant_id}. This makes that claim
     * EXECUTABLE, and it exists because the obvious break arm cannot fail.
     *
     * <p><strong>The measurement that forced this method.</strong> The exemption is keyed by
     * TABLE NAME, so adding a {@code tenant_id} column to V62 leaves
     * {@link #everyPublicTableHasRlsAndForce} green — the table is skipped before any column is
     * looked at. Run as a deliberate break arm, that is exactly what happened: the sweep passed
     * with a tenant-dimensioned table sitting inside an exemption whose written reason had just
     * become false. A justification no test can contradict is decoration, so the criterion is
     * replaced here with a stronger one rather than reported as satisfied.
     *
     * <p><strong>Why the premise matters and is not pedantry.</strong> If a future edit gives this
     * table a tenant dimension, the exemption stops being "there is no predicate to write" and
     * starts being "there is a tenant-scoped table with RLS switched off" — the precise failure
     * this whole class exists to catch, wearing an exemption it inherited from a different design.
     * The right response to that edit is to REMOVE the exemption and add the policy, not to update
     * the comment.
     *
     * <p><strong>Non-vacuity.</strong> "No column named tenant_id" is also satisfied by a table
     * that does not exist, by a mistyped catalog name, and by a namespace filter that matches
     * nothing. So the column count is asserted {@code > 0} FIRST: the walk must be shown able to
     * see this table's columns before its failure to see one of them means anything.
     */
    @Test
    void dsarRequestHasNoTenantDimension() {
        List<String> columns = jdbc.queryForList(
                "SELECT a.attname " +
                        "FROM pg_attribute a " +
                        "JOIN pg_class c ON c.oid = a.attrelid " +
                        "WHERE c.relname = 'dsar_request' " +
                        "  AND c.relkind = 'r' " +
                        "  AND c.relnamespace = 'public'::regnamespace " +
                        "  AND a.attnum > 0 " +
                        "  AND NOT a.attisdropped " +
                        "ORDER BY a.attnum",
                String.class);

        assertThat(columns)
                .as("NON-VACUITY CONTROL: the catalog walk found NO columns on public.dsar_request, " +
                        "so the 'no tenant_id' result below would be evidence about this QUERY, not " +
                        "about the table — a missing table, a mistyped catalog name or a namespace " +
                        "filter that matches nothing all satisfy it. V62 must have applied.")
                .isNotEmpty();

        assertThat(columns)
                .as("public.dsar_request has grown a tenant dimension, which makes its " +
                        "RlsContractTest.EXEMPT_TABLES justification FALSE. That justification is " +
                        "'with no tenant_id there is no predicate to write, so a FORCE'd policy would " +
                        "return zero rows to the worker that must read them'. Once the column exists " +
                        "the predicate exists, and an exemption is no longer defensible: REMOVE the " +
                        "dsar_request entry from EXEMPT_TABLES and add ENABLE + FORCE ROW LEVEL " +
                        "SECURITY plus a tenant policy through the safe current_tenant_id() helper. " +
                        "Do NOT edit the comment to match. Columns seen: %s", columns)
                .doesNotContain("tenant_id");
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
