package uk.jtoye.core.finance;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 37-10 (D-10, owner ruling 2026-10-08: entry-kind): V78 classifies every EXISTING ledger row on a
 * database that already holds them, under the RLS-bound migration role.
 *
 * <p><strong>The trap this pins.</strong> {@code financial_transactions} is ENABLE + FORCE ROW LEVEL
 * SECURITY (V2). Flyway runs as the RLS-bound application role, so with no tenant GUC set
 * {@code current_tenant_id()} is NULL and the policy hides every row: a bare
 * {@code UPDATE financial_transactions SET entry_kind = 'MANUAL' WHERE order_id IS NULL} matches ZERO rows
 * and reports success. Because V78 adds the column with {@code DEFAULT 'SALE'}, nothing would then fail:
 * every order-less (manual) row would silently become a SALE. V78 therefore walks the tenants registry and
 * pins the GUC per tenant (the V44/V57 pattern).
 *
 * <p><strong>Why a fresh Testcontainers database cannot catch it.</strong> A fresh database runs V77 and
 * V78 back to back on an empty ledger, and the Testcontainers bootstrap role is a SUPERUSER, which bypasses
 * FORCE RLS. So this test migrates to V77 as the superuser, seeds rows for TWO tenants (each with an
 * order-linked row and an order-less negative row), then provisions {@code rls_migrator}
 * (NOSUPERUSER NOBYPASSRLS, mirroring {@code jtoye_app}), hands it ownership of the four tables V78 alters,
 * and applies V78 as that role. Two tenants, because a loop that stopped after the first tenant would pass
 * a one-tenant test.
 *
 * <p>Mirrors {@code V57GrantSourceBackfillIntegrationTest}. Plain JUnit + the Flyway API: the scenario is
 * Flyway's behaviour under an RLS-bound role, not the Spring context.
 */
@Testcontainers
@Tag("testcontainers")
class LedgerEntryKindMigrationIntegrationTest {

    private static final String RLS_MIGRATOR = "rls_migrator";
    private static final String RLS_MIGRATOR_PW = "rls_migrator_pw";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_v78")
            .withUsername("test")
            .withPassword("test");

    @Test
    void v78ClassifiesExistingLedgerRowsAcrossTenantsUnderRls() throws Exception {
        // --- 1. Migrate to V77 as the container superuser: the ledger exists, V78 has not run. ------------
        Flyway toV77 = baseConfig(postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("77"))
                .load();
        toV77.migrate();
        assertThat(appliedVersions(toV77)).as("V77 applied, V78 not yet").contains("77").doesNotContain("78");

        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();
        UUID t1Sale = UUID.randomUUID();
        UUID t1Manual = UUID.randomUUID();
        UUID t2Sale = UUID.randomUUID();
        UUID t2Manual = UUID.randomUUID();
        UUID t1Order = UUID.randomUUID();
        UUID t2Order = UUID.randomUUID();

        try (Connection su = connect(postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement()) {
            s.execute("DROP ROLE IF EXISTS " + RLS_MIGRATOR);
            s.execute("CREATE ROLE " + RLS_MIGRATOR
                    + " NOSUPERUSER NOBYPASSRLS LOGIN PASSWORD '" + RLS_MIGRATOR_PW + "'");
            s.execute("GRANT ALL ON SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO " + RLS_MIGRATOR);
            // Ownership lets the role run V78's ALTERs and DROP INDEX while it stays SUBJECT to FORCE RLS.
            for (String table : new String[]{"financial_transactions", "financial_transactions_aud",
                    "orders", "orders_aud"}) {
                s.execute("ALTER TABLE " + table + " OWNER TO " + RLS_MIGRATOR);
            }

            // The superuser bypasses RLS, so the rows land for both tenants without a GUC. order_id has no
            // foreign key (V40), so an order-linked row needs no orders parent.
            s.execute("INSERT INTO tenants (id, name) VALUES "
                    + "('" + tenant1 + "', 'V78 Tenant 1'), ('" + tenant2 + "', 'V78 Tenant 2')");
            seedLedger(su, t1Sale, tenant1, 1500L, "STANDARD", "Order V78-1", t1Order);
            seedLedger(su, t1Manual, tenant1, -300L, "ZERO", "Supplier invoice 1", null);
            seedLedger(su, t2Sale, tenant2, 2500L, "REDUCED", "Order V78-2", t2Order);
            seedLedger(su, t2Manual, tenant2, -700L, "EXEMPT", "Supplier invoice 2", null);

            assertThat(columnCount(su, "financial_transactions", "entry_kind"))
                    .as("pre-V78: entry_kind must not exist yet").isZero();
        }

        // --- 2. Apply V78 as the RLS-bound migrator. ------------------------------------------------------
        // Resolved with no target, so a missing V78 reads as this assertion rather than a target error.
        assertThat(Arrays.stream(baseConfig(RLS_MIGRATOR, RLS_MIGRATOR_PW).load().info().all())
                .anyMatch(m -> m.getVersion() != null && "78".equals(m.getVersion().getVersion())))
                .as("V78__order_void_ledger_reversal.sql must be on the classpath")
                .isTrue();
        Flyway toV78 = baseConfig(RLS_MIGRATOR, RLS_MIGRATOR_PW)
                .target(MigrationVersion.fromVersion("78"))
                .load();
        var result = toV78.migrate();
        assertThat(result.migrationsExecuted).as("exactly V78 applied on top of V77").isEqualTo(1);
        assertThat(result.migrations.get(0).version).isEqualTo("78");

        // --- 3. Every seeded row classified, in BOTH tenants. Read as superuser to see all rows. ----------
        try (Connection su = connect(postgres.getUsername(), postgres.getPassword())) {
            assertThat(entryKind(su, t1Sale)).as("tenant1 order-linked row -> SALE").isEqualTo("SALE");
            assertThat(entryKind(su, t2Sale)).as("tenant2 order-linked row -> SALE").isEqualTo("SALE");
            assertThat(entryKind(su, t1Manual)).as("tenant1 order-less row -> MANUAL").isEqualTo("MANUAL");
            assertThat(entryKind(su, t2Manual)).as("tenant2 order-less row -> MANUAL").isEqualTo("MANUAL");
            assertThat(scalar(su, "SELECT count(*) FROM financial_transactions "
                    + "WHERE (order_id IS NULL AND entry_kind <> 'MANUAL') OR (order_id IS NOT NULL AND entry_kind <> 'SALE')"))
                    .as("no pre-V78 row left misclassified").isZero();

            // The unique index now includes entry_kind, still partial on order_id.
            String indexDef = string(su, "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_fin_tx_tenant_order'");
            assertThat(indexDef).as("uq_fin_tx_tenant_order").contains("(tenant_id, order_id, entry_kind)")
                    .contains("WHERE (order_id IS NOT NULL)");

            // Functionally: one REVERSAL per order is admitted beside its SALE, a second SALE or a second
            // REVERSAL is refused, and an unknown kind is refused.
            insertLedger(su, UUID.randomUUID(), tenant1, -1500L, "STANDARD", "Reversal of V78-1", t1Order,
                    "REVERSAL", t1Sale);
            assertThatThrownBy(() -> insertLedger(su, UUID.randomUUID(), tenant1, 1500L, "STANDARD", "dup sale",
                    t1Order, "SALE", null)).isInstanceOf(SQLException.class).hasMessageContaining("uq_fin_tx_tenant_order");
            assertThatThrownBy(() -> insertLedger(su, UUID.randomUUID(), tenant1, -1500L, "STANDARD", "dup reversal",
                    t1Order, "REVERSAL", t1Sale)).isInstanceOf(SQLException.class).hasMessageContaining("uq_fin_tx_tenant_order");
            assertThatThrownBy(() -> insertLedger(su, UUID.randomUUID(), tenant1, 1L, "STANDARD", "bogus",
                    null, "BOGUS", null)).isInstanceOf(SQLException.class).hasMessageContaining("ck_fin_tx_entry_kind");

            // Envers mirrors in the SAME migration (Order and FinancialTransaction are @Audited).
            for (String column : new String[]{"entry_kind", "reverses_transaction_id"}) {
                assertThat(columnCount(su, "financial_transactions", column)).as(column).isEqualTo(1);
                assertThat(columnCount(su, "financial_transactions_aud", column)).as(column + " _aud").isEqualTo(1);
            }
            for (String column : new String[]{"voided_at", "voided_by", "void_reason"}) {
                assertThat(columnCount(su, "orders", column)).as(column).isEqualTo(1);
                assertThat(columnCount(su, "orders_aud", column)).as(column + " _aud").isEqualTo(1);
            }
            assertThat(scalar(su, "SELECT count(*) FROM pg_constraint WHERE conname = 'ck_orders_void_triple'"))
                    .as("the void triple is all-or-nothing").isEqualTo(1);
        }
    }

    private static void seedLedger(Connection c, UUID id, UUID tenantId, long amount, String rate, String ref,
                                   UUID orderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO financial_transactions (id, tenant_id, created_at, amount_pennies, vat_rate, reference, order_id) "
                        + "VALUES (?, ?, now(), ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setLong(3, amount);
            ps.setString(4, rate);
            ps.setString(5, ref);
            ps.setObject(6, orderId);
            ps.executeUpdate();
        }
    }

    private static void insertLedger(Connection c, UUID id, UUID tenantId, long amount, String rate, String ref,
                                     UUID orderId, String kind, UUID reverses) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO financial_transactions (id, tenant_id, created_at, amount_pennies, vat_rate, reference, "
                        + "order_id, entry_kind, reverses_transaction_id) VALUES (?, ?, now(), ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setLong(3, amount);
            ps.setString(4, rate);
            ps.setString(5, ref);
            ps.setObject(6, orderId);
            ps.setString(7, kind);
            ps.setObject(8, reverses);
            ps.executeUpdate();
        }
    }

    private static String entryKind(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT entry_kind FROM financial_transactions WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("seeded row must still exist").isTrue();
                return rs.getString(1);
            }
        }
    }

    private static int columnCount(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String string(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static FluentConfiguration baseConfig(String user, String password) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), user, password)
                .locations("classpath:db/migration");
    }

    private static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), user, password);
    }

    private static java.util.List<String> appliedVersions(Flyway flyway) {
        return Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .toList();
    }
}
