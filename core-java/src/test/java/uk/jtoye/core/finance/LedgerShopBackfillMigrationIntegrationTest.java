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

/**
 * Phase 37-11 (RWO-027 / UXT-027): V79 gives every EXISTING order-linked ledger row its order's shop, on a
 * database that already holds them, under the RLS-bound migration role, in every tenant.
 *
 * <p><strong>The trap this pins (T-37-32).</strong> {@code financial_transactions} and {@code orders} are both
 * ENABLE + FORCE ROW LEVEL SECURITY. Flyway runs as the RLS-bound application role, so with no tenant GUC set
 * {@code current_tenant_id()} is NULL and the policies hide every row: a bare
 * {@code UPDATE financial_transactions ft SET shop_id = o.shop_id FROM orders o WHERE ft.order_id = o.id}
 * matches ZERO rows and reports success. Because {@code shop_id} is nullable nothing would then fail: every
 * existing sale would stay shop-less, and every shop's figures would read as empty. V79 therefore walks the
 * tenants registry and pins the GUC per tenant (the V44/V57/V78 pattern).
 *
 * <p><strong>Why a fresh Testcontainers database cannot catch it.</strong> A fresh database runs V78 and V79
 * back to back on an empty ledger, and the Testcontainers bootstrap role is a SUPERUSER, which bypasses FORCE
 * RLS. So this test migrates to V78 as the superuser, seeds TWO tenants (each with two shops, an order at each,
 * the orders' SALE rows, a voided order's REVERSAL, a MANUAL row and a legacy row whose order no longer
 * exists), then provisions {@code rls_migrator} (NOSUPERUSER NOBYPASSRLS, mirroring {@code jtoye_app}), hands
 * it ownership of the two tables V79 alters, and applies V79 as that role. Two tenants, because a loop that
 * stopped after the first tenant would pass a one-tenant test; two shops per tenant, because a backfill that
 * wrote one shop to every row would pass a one-shop test.
 *
 * <p>Mirrors {@code V57GrantSourceBackfillIntegrationTest} and {@code LedgerEntryKindMigrationIntegrationTest}.
 * Plain JUnit + the Flyway API: the scenario is Flyway's behaviour under an RLS-bound role, not the Spring
 * context.
 */
@Testcontainers
@Tag("testcontainers")
class LedgerShopBackfillMigrationIntegrationTest {

    private static final String RLS_MIGRATOR = "rls_migrator";
    private static final String RLS_MIGRATOR_PW = "rls_migrator_pw";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_v79")
            .withUsername("test")
            .withPassword("test");

    /** One tenant's seeded world: two shops, an order at each, and the ledger rows that hang off them. */
    record TenantSeed(UUID tenant, UUID shopX, UUID shopY, UUID saleAtX, UUID saleAtY, UUID reversalAtY,
                      UUID manual, UUID dangling) {}

    @Test
    void v79AttributesExistingLedgerRowsToTheirOrdersShopAcrossTenantsUnderRls() throws Exception {
        // --- 1. Migrate to V78 as the container superuser: the ledger has entry kinds, V79 has not run. -------
        Flyway toV78 = baseConfig(postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("78"))
                .load();
        toV78.migrate();
        assertThat(appliedVersions(toV78)).as("V78 applied, V79 not yet").contains("78").doesNotContain("79");

        TenantSeed t1;
        TenantSeed t2;
        try (Connection su = connect(postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement()) {
            s.execute("DROP ROLE IF EXISTS " + RLS_MIGRATOR);
            s.execute("CREATE ROLE " + RLS_MIGRATOR
                    + " NOSUPERUSER NOBYPASSRLS LOGIN PASSWORD '" + RLS_MIGRATOR_PW + "'");
            s.execute("GRANT ALL ON SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO " + RLS_MIGRATOR);
            // Ownership lets the role run V79's ALTERs and CREATE INDEX while it stays SUBJECT to FORCE RLS on
            // financial_transactions. orders stays owned by the superuser: as a non-owner the migrator is
            // subject to its RLS policy as well, so BOTH sides of the backfill's join are RLS-bound.
            for (String table : new String[]{"financial_transactions", "financial_transactions_aud"}) {
                s.execute("ALTER TABLE " + table + " OWNER TO " + RLS_MIGRATOR);
            }

            // The superuser bypasses RLS, so the rows land for both tenants without a GUC.
            t1 = seedTenant(su, "1");
            t2 = seedTenant(su, "2");

            assertThat(columnCount(su, "financial_transactions", "shop_id"))
                    .as("pre-V79: shop_id must not exist yet").isZero();
        }

        // --- 2. Apply V79 as the RLS-bound migrator. -------------------------------------------------------
        // Resolved with no target, so a missing V79 reads as this assertion rather than a target error.
        assertThat(Arrays.stream(baseConfig(RLS_MIGRATOR, RLS_MIGRATOR_PW).load().info().all())
                .anyMatch(m -> m.getVersion() != null && "79".equals(m.getVersion().getVersion())))
                .as("V79__financial_transactions_shop_id.sql must be on the classpath")
                .isTrue();
        Flyway toV79 = baseConfig(RLS_MIGRATOR, RLS_MIGRATOR_PW)
                .target(MigrationVersion.fromVersion("79"))
                .load();
        var result = toV79.migrate();
        assertThat(result.migrationsExecuted).as("exactly V79 applied on top of V78").isEqualTo(1);
        assertThat(result.migrations.get(0).version).isEqualTo("79");

        // --- 3. Every order-linked row carries its order's shop, in BOTH tenants. Read as superuser. -------
        try (Connection su = connect(postgres.getUsername(), postgres.getPassword())) {
            for (TenantSeed t : new TenantSeed[]{t1, t2}) {
                String tag = "tenant " + t.tenant() + ": ";
                assertThat(shopOf(su, t.saleAtX())).as(tag + "SALE of the order at shop X -> X").isEqualTo(t.shopX());
                assertThat(shopOf(su, t.saleAtY())).as(tag + "SALE of the order at shop Y -> Y").isEqualTo(t.shopY());
                assertThat(shopOf(su, t.reversalAtY())).as(tag + "REVERSAL of the voided order at Y -> Y")
                        .isEqualTo(t.shopY());
                assertThat(shopOf(su, t.manual())).as(tag + "MANUAL row (no order) stays shop-less").isNull();
                assertThat(shopOf(su, t.dangling())).as(tag + "row whose order no longer exists stays shop-less")
                        .isNull();
            }
            assertThat(scalar(su, "SELECT count(*) FROM financial_transactions ft JOIN orders o ON o.id = ft.order_id "
                    + "WHERE ft.shop_id IS DISTINCT FROM o.shop_id"))
                    .as("no order-linked row left with a shop other than its order's").isZero();

            // Envers mirror in the SAME migration (FinancialTransaction is @Audited), the FK and the index.
            assertThat(columnCount(su, "financial_transactions", "shop_id")).isEqualTo(1);
            assertThat(columnCount(su, "financial_transactions_aud", "shop_id")).as("shop_id _aud").isEqualTo(1);
            assertThat(scalar(su, "SELECT count(*) FROM pg_constraint c JOIN pg_class r ON r.oid = c.conrelid "
                    + "JOIN pg_class f ON f.oid = c.confrelid WHERE c.contype = 'f' "
                    + "AND r.relname = 'financial_transactions' AND f.relname = 'shops'"))
                    .as("shop_id references shops(id)").isEqualTo(1);
            String indexDef = string(su,
                    "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_fin_tx_tenant_shop_created'");
            assertThat(indexDef).as("idx_fin_tx_tenant_shop_created").contains("(tenant_id, shop_id, created_at)");
        }
    }

    // ---- seeding ----

    private static TenantSeed seedTenant(Connection su, String n) throws SQLException {
        UUID tenant = UUID.randomUUID();
        try (PreparedStatement ps = su.prepareStatement("INSERT INTO tenants (id, name) VALUES (?, ?)")) {
            ps.setObject(1, tenant);
            ps.setString(2, "V79 Tenant " + n);
            ps.executeUpdate();
        }
        UUID shopX = seedShop(su, tenant, "v79-x-" + n + "-" + tenant.toString().substring(0, 8));
        UUID shopY = seedShop(su, tenant, "v79-y-" + n + "-" + tenant.toString().substring(0, 8));
        UUID orderX = seedOrder(su, tenant, shopX, "COMPLETED");
        UUID orderY = seedOrder(su, tenant, shopY, "COMPLETED");

        UUID saleAtX = UUID.randomUUID();
        UUID saleAtY = UUID.randomUUID();
        UUID reversalAtY = UUID.randomUUID();
        UUID manual = UUID.randomUUID();
        UUID dangling = UUID.randomUUID();
        seedLedger(su, saleAtX, tenant, 1500L, "Order X" + n, orderX, "SALE", null);
        seedLedger(su, saleAtY, tenant, 2500L, "Order Y" + n, orderY, "SALE", null);
        seedLedger(su, reversalAtY, tenant, -2500L, "Reversal of Y" + n, orderY, "REVERSAL", saleAtY);
        seedLedger(su, manual, tenant, -300L, "Supplier invoice " + n, null, "MANUAL", null);
        // order_id has no foreign key (V40): a legacy row can name an order that is gone.
        seedLedger(su, dangling, tenant, 900L, "Deleted order " + n, UUID.randomUUID(), "SALE", null);
        return new TenantSeed(tenant, shopX, shopY, saleAtX, saleAtY, reversalAtY, manual, dangling);
    }

    private static UUID seedShop(Connection c, UUID tenant, String slug) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setString(3, "Shop " + slug);
            ps.setString(4, slug);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedOrder(Connection c, UUID tenant, UUID shop, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                        + "item_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'V79 Customer', 'v79-customer@example.test', "
                        + "1000, 'STANDARD', 200, 1200, 0, 1, now(), now())")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setObject(3, shop);
            ps.setString(4, "ORD-V79-" + id.toString().substring(0, 8));
            ps.setString(5, status);
            ps.executeUpdate();
        }
        return id;
    }

    private static void seedLedger(Connection c, UUID id, UUID tenantId, long amount, String ref, UUID orderId,
                                   String kind, UUID reverses) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO financial_transactions (id, tenant_id, created_at, amount_pennies, vat_rate, reference, "
                        + "order_id, entry_kind, reverses_transaction_id) VALUES (?, ?, now(), ?, 'STANDARD', ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setLong(3, amount);
            ps.setString(4, ref);
            ps.setObject(5, orderId);
            ps.setString(6, kind);
            ps.setObject(7, reverses);
            ps.executeUpdate();
        }
    }

    // ---- reads ----

    private static UUID shopOf(Connection c, UUID ledgerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT shop_id FROM financial_transactions WHERE id = ?")) {
            ps.setObject(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("seeded row must still exist").isTrue();
                return rs.getObject(1, UUID.class);
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
