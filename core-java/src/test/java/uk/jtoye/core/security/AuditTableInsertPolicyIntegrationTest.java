package uk.jtoye.core.security;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.envers.boot.internal.EnversService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA-council 20260902-134741 SEC-6 (adjudication A5) + N-3 — the six legacy Envers {@code _aud}
 * tables and the Envers settings that govern what gets written into them.
 *
 * <p><b>SEC-6.</b> V4/V5/V9/V11 created the INSERT policies on {@code shops_aud, products_aud,
 * financial_transactions_aud, orders_aud, order_items_aud, customers_aud} as
 * {@code WITH CHECK (true)}: a session pinned to tenant A could write an audit row stamped tenant B.
 * V65 replaces each with {@code WITH CHECK ((tenant_id IS NULL) OR (tenant_id = current_tenant_id()))},
 * {@code FOR INSERT} only. The {@code IS NULL} arm is load-bearing and adjudicated: with Envers'
 * default {@code store_data_at_delete=false} a DELETE revision carries only the identifier, so its
 * {@code tenant_id} is NULL by construction, and the naive
 * {@code WITH CHECK (tenant_id = current_tenant_id())} would have turned every product, order and
 * customer delete into an RLS violation. The reference tables' {@code FOR ALL ... USING (tenant_id IS
 * NULL OR ...)} form was rejected too (A5): a NULL-permissive USING opens a cross-tenant READ of every
 * delete revision (that is finding N-2 on the five newer tables, not fixed here).
 *
 * <p><b>N-3.</b> {@code application.yml} declared the Envers settings under
 * {@code spring.jpa.properties.hibernate.envers.*}. Spring Boot passes {@code spring.jpa.properties.*}
 * to Hibernate verbatim, and Envers reads {@code org.hibernate.envers.*}
 * ({@code org.hibernate.envers.configuration.EnversSettings}), so the whole block was inert. It never
 * showed: the defaults {@code _AUD}/{@code REV}/{@code REVTYPE} case-fold to the migrations' lower-case
 * names on Postgres. {@code store_data_at_delete=true} did NOT take effect, which is why every live
 * DELETE revision had a NULL tenant. {@link #enversSettingsDeclaredInApplicationYmlAreActuallyApplied}
 * reads the effective configuration back out of the running {@code SessionFactory}, and
 * {@link #deletingAnAuditedProductUnderRlsStillWritesItsDeleteRevisionAndItNowCarriesTheTenant}
 * observes the behavioural consequence.
 *
 * <p><b>Enforcement and verification are split on purpose.</b> The Testcontainers bootstrap role is
 * a SUPERUSER and bypasses even FORCE RLS, so every arm that must be policy-checked runs inside a
 * {@link TransactionTemplate} block that first executes {@code SET LOCAL ROLE rls_test_role}
 * (NOSUPERUSER NOBYPASSRLS — the {@code IdempotencyKeysRlsPolicyIntegrationTest} house pattern) with
 * the tenant GUC driven by {@link TenantContext} through {@code TenantSetLocalAspect}, the same path
 * production takes. The Envers write happens at that block's commit, on that connection, under that
 * role. VERIFICATION queries deliberately run afterwards as the superuser: the legacy
 * {@code *_aud_select_policy} is {@code tenant_id = current_tenant_id()}, so a NULL-tenant delete
 * revision is INVISIBLE to a tenant-pinned reader — a tenant-pinned count would report 0 for a row
 * that was written, and the liveness arm would go red for a visibility reason while looking like a
 * write failure ({@code trap_rls_blinds_the_verification_query}).
 *
 * <p>NOT {@code @Transactional} at class level: Envers writes at commit, so each arm needs a real
 * commit, and {@code SET LOCAL ROLE} reverts with it.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class AuditTableInsertPolicyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    /** The six tables whose INSERT policy was {@code WITH CHECK (true)} until V65 — the SEC-6 census. */
    static final List<String> LEGACY_AUD_TABLES = List.of(
            "shops_aud", "products_aud", "financial_transactions_aud",
            "orders_aud", "order_items_aud", "customers_aud");

    static Stream<String> legacyAudTables() {
        return LEGACY_AUD_TABLES.stream();
    }

    private static final String RLS_TEST_ROLE = "rls_test_role";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ProductRepository productRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void seed() {
        // Dedicated non-superuser role (idempotent) so the policies actually fire.
        jdbc.execute("DO $$ BEGIN " +
                "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN " +
                "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; " +
                "    GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "    GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "    GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "  END IF; " +
                "END $$");

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        // tenants is not RLS-scoped; names must be unique (V13 seeds 'Tenant A'/'Tenant B').
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "SEC-6 tenant A " + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "SEC-6 tenant B " + tenantB);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /**
     * Runs {@code body} in one committed transaction, pinned to {@code tenant} and downgraded to the
     * NOSUPERUSER role for its whole extent — including the Envers flush at commit.
     */
    private <T> T asTenantUnderRls(UUID tenant, TransactionCallback<T> body) {
        TenantContext.set(tenant);
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
                return body.doInTransaction(status);
            });
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * A committed {@code revinfo} row for the armed INSERTs to reference ({@code fk_*_aud_revinfo}).
     * {@code revinfo} is not RLS-scoped, so this is written as the superuser, outside the arm.
     */
    private int newRevision() {
        return jdbc.queryForObject(
                "INSERT INTO revinfo (rev, revtstmp, tenant_id) VALUES (nextval('revinfo_seq'), ?, ?) RETURNING rev",
                Integer.class, System.currentTimeMillis(), tenantA);
    }

    // ---- SEC-6 deny arm: RED before V65 (the INSERT succeeded) ------------------------------------

    @ParameterizedTest(name = "{0}: a tenant-A session cannot insert an audit row stamped tenant B")
    @MethodSource("legacyAudTables")
    void aTenantPinnedSessionCannotInsertAnAuditRowStampedWithAnotherTenant(String table) {
        int rev = newRevision();

        assertThatThrownBy(() -> asTenantUnderRls(tenantA, status -> jdbc.update(
                "INSERT INTO " + table + " (id, rev, revtype, tenant_id) VALUES (gen_random_uuid(), ?, 0, ?)",
                rev, tenantB)))
                .as("%s: INSERT policy must refuse a foreign tenant_id under a pinned GUC", table)
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("row-level security");
    }

    // ---- positive control + the A5 boundary: GREEN in both arms, and would go RED under the naive form

    @ParameterizedTest(name = "{0}: a tenant-A session can still insert its own audit row")
    @MethodSource("legacyAudTables")
    void aTenantPinnedSessionCanStillInsertItsOwnAuditRow(String table) {
        int rev = newRevision();

        Integer inserted = asTenantUnderRls(tenantA, status -> jdbc.update(
                "INSERT INTO " + table + " (id, rev, revtype, tenant_id) VALUES (gen_random_uuid(), ?, 0, ?)",
                rev, tenantA));

        assertThat(inserted).as("%s: own-tenant audit INSERT is the normal Envers write and must pass", table)
                .isEqualTo(1);
    }

    /**
     * The adjudicated {@code IS NULL} arm, exercised directly. A DELETE revision written with
     * {@code store_data_at_delete=false} has this exact shape (revtype 2, no entity columns), and so
     * would any audited entity whose tenant column is not part of the revision. Measured in the break
     * arm: replacing V65's predicate with the naive {@code tenant_id = current_tenant_id()} turns this
     * into "new row violates row-level security policy" on all six tables.
     */
    @ParameterizedTest(name = "{0}: a NULL-tenant audit row (the Envers delete-revision shape) is still accepted")
    @MethodSource("legacyAudTables")
    void aNullTenantAuditRowIsStillAcceptedBecauseThatIsTheEnversDeleteRevisionShape(String table) {
        int rev = newRevision();

        Integer inserted = asTenantUnderRls(tenantA, status -> jdbc.update(
                "INSERT INTO " + table + " (id, rev, revtype, tenant_id) VALUES (gen_random_uuid(), ?, 2, NULL)",
                rev));

        assertThat(inserted).as("%s: the NULL arm of the V65 predicate must remain open (A5)", table)
                .isEqualTo(1);
    }

    // ---- liveness arm through the real Envers path, plus the N-3 behavioural observable ------------

    /**
     * Create, then delete, an audited product under the NOSUPERUSER role with the tenant pinned. The
     * {@code revtype = 2} row must land (liveness: green in both arms), and — the N-3 half — it must
     * carry the tenant: with {@code org.hibernate.envers.store_data_at_delete=true} finally applied,
     * a DELETE revision snapshots the entity's audited columns, {@code tenant_id} among them. RED
     * before the prefix fix (NULL), GREEN after. Counted as the SUPERUSER so the NULL case is
     * observed rather than hidden by the SELECT policy (see the class Javadoc).
     */
    @Test
    void deletingAnAuditedProductUnderRlsStillWritesItsDeleteRevisionAndItNowCarriesTheTenant() {
        UUID productId = asTenantUnderRls(tenantA, status -> {
            Product product = new Product();
            product.setTenantId(tenantA);
            product.setSku("SKU-SEC6-" + UUID.randomUUID().toString().substring(0, 8));
            product.setTitle("Egusi Soup");
            product.setIngredientsText("Melon seeds, spinach, palm oil");
            product.setAllergenMask(0);
            product.setPricePennies(1299L);
            return productRepository.saveAndFlush(product).getId();
        });

        asTenantUnderRls(tenantA, status -> {
            productRepository.deleteById(productId);
            productRepository.flush();
            return null;
        });

        Integer addRevisions = jdbc.queryForObject(
                "SELECT count(*) FROM products_aud WHERE id = ? AND revtype = 0", Integer.class, productId);
        assertThat(addRevisions)
                .as("NON-VACUITY CONTROL: the ADD revision must be visible to this reader, or the counts below say nothing")
                .isEqualTo(1);

        Integer deleteRevisions = jdbc.queryForObject(
                "SELECT count(*) FROM products_aud WHERE id = ? AND revtype = 2", Integer.class, productId);
        assertThat(deleteRevisions)
                .as("LIVENESS: a product DELETE under the V65 policy must still write its Envers DELETE revision")
                .isEqualTo(1);

        UUID deleteRevisionTenant = jdbc.queryForObject(
                "SELECT tenant_id FROM products_aud WHERE id = ? AND revtype = 2", UUID.class, productId);
        assertThat(deleteRevisionTenant)
                .as("N-3: store_data_at_delete=true is applied, so the DELETE revision carries the tenant "
                        + "(NULL here means the org.hibernate.envers.* block is not reaching Envers)")
                .isEqualTo(tenantA);
    }

    /**
     * Reads the EFFECTIVE Envers configuration out of the running SessionFactory. Before the prefix
     * fix every value here is the Envers default ({@code _AUD}, {@code REV}, {@code REVTYPE},
     * {@code false}, {@code null}); the YAML said otherwise and Envers never heard it.
     */
    @Test
    void enversSettingsDeclaredInApplicationYmlAreActuallyApplied() {
        org.hibernate.envers.configuration.Configuration envers = entityManagerFactory
                .unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry()
                .getService(EnversService.class)
                .getConfig();

        assertThat(envers.isStoreDataAtDelete())
                .as("org.hibernate.envers.store_data_at_delete — the setting whose absence left every DELETE revision tenant-less")
                .isTrue();
        assertThat(envers.getRevisionFieldName())
                .as("org.hibernate.envers.revision_field_name").isEqualTo("rev");
        assertThat(envers.getRevisionTypePropertyName())
                .as("org.hibernate.envers.revision_type_field_name").isEqualTo("revtype");
        assertThat(envers.getDefaultSchemaName())
                .as("org.hibernate.envers.default_schema").isEqualTo("public");
        assertThat(envers.getAuditTableName("Product", "products"))
                .as("org.hibernate.envers.audit_table_suffix — the default is _AUD and only survived by Postgres case-folding")
                .isEqualTo("products_aud");
    }
}
