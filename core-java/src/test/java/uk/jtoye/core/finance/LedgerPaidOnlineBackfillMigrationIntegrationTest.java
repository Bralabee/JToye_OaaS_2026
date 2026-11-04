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
 * V93 (#827, PR #1124 review round 1): the migration records the payment channel on EXISTING ledger rows where
 * the row and its order prove it, and leaves it NULL ("not recorded") where they do not, in every tenant, on a
 * database that already holds them, under the RLS-bound migration role.
 *
 * <p><strong>The trap this pins.</strong> {@code financial_transactions} and {@code orders} are both ENABLE +
 * FORCE ROW LEVEL SECURITY and Flyway runs as the RLS-bound application role, so with no tenant GUC the
 * policies hide every row: a bare {@code UPDATE financial_transactions SET paid_online_pennies = ...} matches
 * ZERO rows and reports success. The column is nullable, so nothing would then fail; every legacy row would
 * silently read "not recorded". V93 walks the tenants registry and pins the GUC per tenant (V44/V57/V78/V79).
 * Step 3 below runs exactly that bare UPDATE as the migrator and shows ZERO rows (the break arm: the trap is
 * real in this very database), and the assertions after V93 show every provable row filled in BOTH tenants.
 *
 * <p>Why not a fresh database: it runs V92 and V93 back to back on an empty ledger, and the Testcontainers
 * bootstrap role is a SUPERUSER, which bypasses FORCE RLS. So this migrates to V92 as the superuser, seeds TWO
 * tenants, provisions {@code rls_migrator} (NOSUPERUSER NOBYPASSRLS, mirroring {@code jtoye_app}), hands it
 * ownership of the two tables V93 alters (the order table stays the superuser's, so BOTH sides of the backfill
 * are RLS-bound) and applies V93 as that role. Two tenants, because a loop that stopped after the first would
 * pass a one-tenant test.
 *
 * <p>Mirrors {@code LedgerShopBackfillMigrationIntegrationTest}. Plain JUnit + the Flyway API.
 */
@Testcontainers
@Tag("testcontainers")
class LedgerPaidOnlineBackfillMigrationIntegrationTest {

    private static final String RLS_MIGRATOR = "rls_migrator";
    // Generated per run: no credential literal in the tree. Both roles live only inside this throwaway container.
    private static final String RLS_MIGRATOR_PW = UUID.randomUUID().toString();
    private static final String DB_PW = UUID.randomUUID().toString();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_v93")
            .withUsername("test")
            .withPassword(DB_PW);

    /** One tenant's seeded world; every id is a ledger row. */
    record TenantSeed(UUID tenant, UUID settlementCard, UUID counterCash, UUID depositSplit, UUID ambiguous,
                      UUID balanceOnline, UUID reversalOfSplit, UUID reversalOfAmbiguous, UUID manual,
                      UUID danglingPayment, UUID danglingOrder) {}

    @Test
    void v93RecordsTheProvableChannelAndLeavesTheRestNullAcrossTenantsUnderRls() throws Exception {
        // --- 1. Migrate to V92 as the container superuser: the ledger exists, V93 has not run. ---------------
        Flyway toV92 = baseConfig(postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("92"))
                .load();
        toV92.migrate();
        assertThat(appliedVersions(toV92)).as("V92 applied, V93 not yet").contains("92").doesNotContain("93");

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
            for (String table : new String[]{"financial_transactions", "financial_transactions_aud"}) {
                s.execute("ALTER TABLE " + table + " OWNER TO " + RLS_MIGRATOR);
            }
            t1 = seedTenant(su, "1");
            t2 = seedTenant(su, "2");
            assertThat(columnCount(su, "financial_transactions", "paid_online_pennies"))
                    .as("pre-V93: paid_online_pennies must not exist yet").isZero();
        }

        // --- 2. The bare-UPDATE break arm, on the SAME database and role V93 will run as. -------------------
        // Add the column the way V93 does, then try the naive backfill with NO tenant pinned: it must match
        // nothing. (Done on a savepoint-free scratch statement inside a transaction that is rolled back, so V93
        // below still sees the pre-V93 schema.)
        try (Connection mig = connect(RLS_MIGRATOR, RLS_MIGRATOR_PW)) {
            mig.setAutoCommit(false);
            try (Statement s = mig.createStatement()) {
                s.execute("ALTER TABLE financial_transactions ADD COLUMN paid_online_pennies BIGINT");
                int bare = s.executeUpdate("UPDATE financial_transactions SET paid_online_pennies = 0 "
                        + "WHERE entry_kind = 'MANUAL'");
                assertThat(bare)
                        .as("BREAK ARM: a bare UPDATE under FORCE RLS with no tenant pinned reaches ZERO rows although "
                                + "MANUAL rows exist in both tenants; this is the trap V93 must not fall into")
                        .isZero();
            } finally {
                mig.rollback();
            }
        }

        // --- 3. Apply V93 as the RLS-bound migrator. -----------------------------------------------------
        assertThat(Arrays.stream(baseConfig(RLS_MIGRATOR, RLS_MIGRATOR_PW).load().info().all())
                .anyMatch(m -> m.getVersion() != null && "93".equals(m.getVersion().getVersion())))
                .as("V93__ledger_paid_online_pennies.sql must be on the classpath")
                .isTrue();
        Flyway toV93 = baseConfig(RLS_MIGRATOR, RLS_MIGRATOR_PW)
                .target(MigrationVersion.fromVersion("93"))
                .load();
        var result = toV93.migrate();
        assertThat(result.migrationsExecuted).as("exactly V93 applied on top of V92").isEqualTo(1);
        assertThat(result.migrations.get(0).version).isEqualTo("93");

        // --- 4. Every provable row carries its channel in BOTH tenants; the unprovable stay NULL. ----------
        try (Connection su = connect(postgres.getUsername(), postgres.getPassword())) {
            for (TenantSeed t : new TenantSeed[]{t1, t2}) {
                String tag = "tenant " + t.tenant() + ": ";
                assertThat(online(su, t.settlementCard())).as(tag + "settlement SALE ('Payment pi_...') was wholly online")
                        .isEqualTo(2000L);
                assertThat(online(su, t.counterCash())).as(tag + "SALE of an order that was never captured: 0")
                        .isEqualTo(0L);
                assertThat(online(su, t.depositSplit()))
                        .as(tag + "completed SALE of a deposit order with a balance still due: only the 2000p deposit")
                        .isEqualTo(2000L);
                assertThat(online(su, t.ambiguous()))
                        .as(tag + "completed SALE of an order that is CAPTURED now but with no deposit split and no "
                                + "settlement reference: not provable, NOT recorded")
                        .isNull();
                assertThat(online(su, t.balanceOnline())).as(tag + "balance-stage settlement SALE: wholly online")
                        .isEqualTo(10_000L);
                assertThat(online(su, t.reversalOfSplit())).as(tag + "REVERSAL = negative of its sale's figure")
                        .isEqualTo(-2000L);
                assertThat(online(su, t.reversalOfAmbiguous())).as(tag + "REVERSAL of a not-recorded sale is not recorded")
                        .isNull();
                assertThat(online(su, t.manual())).as(tag + "MANUAL row: 0").isEqualTo(0L);
                assertThat(online(su, t.danglingPayment()))
                        .as(tag + "settlement SALE whose order is gone: the reference proves it").isEqualTo(900L);
                assertThat(online(su, t.danglingOrder()))
                        .as(tag + "completed SALE whose order is gone: not recorded").isNull();
            }
            assertThat(scalar(su, "SELECT count(*) FROM financial_transactions WHERE paid_online_pennies IS NOT NULL "
                    + "AND NOT (CASE WHEN amount_pennies >= 0 THEN paid_online_pennies BETWEEN 0 AND amount_pennies "
                    + "ELSE paid_online_pennies BETWEEN amount_pennies AND 0 END)"))
                    .as("no backfilled figure lies outside 0..amount").isZero();

            // The Envers mirror in the SAME migration (FinancialTransaction is @Audited), and the range CHECK.
            assertThat(columnCount(su, "financial_transactions", "paid_online_pennies")).isEqualTo(1);
            assertThat(columnCount(su, "financial_transactions_aud", "paid_online_pennies")).as("_aud mirror").isEqualTo(1);
            assertThatThrownBy(() -> {
                try (Statement s = su.createStatement()) {
                    s.execute("UPDATE financial_transactions SET paid_online_pennies = amount_pennies + 1 "
                            + "WHERE id = '" + t1.settlementCard() + "'");
                }
            }).as("ck_fin_tx_paid_online_pennies refuses more online than the amount")
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_fin_tx_paid_online_pennies");
        }
    }

    // ---- seeding ----

    private static TenantSeed seedTenant(Connection su, String n) throws SQLException {
        UUID tenant = UUID.randomUUID();
        try (PreparedStatement ps = su.prepareStatement("INSERT INTO tenants (id, name) VALUES (?, ?)")) {
            ps.setObject(1, tenant);
            ps.setString(2, "V93 Tenant " + n);
            ps.executeUpdate();
        }
        UUID shop = seedShop(su, tenant, "v93-" + n + "-" + tenant.toString().substring(0, 8));
        UUID card = seedOrder(su, tenant, shop, "CAPTURED", 2000L, null, null);
        UUID cash = seedOrder(su, tenant, shop, "NONE", 1200L, null, null);
        UUID split = seedOrder(su, tenant, shop, "CAPTURED", 10_000L, 2000L, 8000L);
        UUID amb = seedOrder(su, tenant, shop, "CAPTURED", 1500L, null, null);
        UUID bal = seedOrder(su, tenant, shop, "CAPTURED", 10_000L, 2000L, 0L);

        UUID settlementCard = UUID.randomUUID();
        UUID counterCash = UUID.randomUUID();
        UUID depositSplit = UUID.randomUUID();
        UUID ambiguous = UUID.randomUUID();
        UUID balanceOnline = UUID.randomUUID();
        UUID reversalOfSplit = UUID.randomUUID();
        UUID reversalOfAmbiguous = UUID.randomUUID();
        UUID manual = UUID.randomUUID();
        UUID danglingPayment = UUID.randomUUID();
        UUID danglingOrder = UUID.randomUUID();
        seedLedger(su, settlementCard, tenant, 2000L, "Payment pi_card_" + n + " for Order X", card, "SALE", null);
        seedLedger(su, counterCash, tenant, 1200L, "Order Cash" + n, cash, "SALE", null);
        seedLedger(su, depositSplit, tenant, 10_000L, "Order Split" + n, split, "SALE", null);
        seedLedger(su, ambiguous, tenant, 1500L, "Order Amb" + n, amb, "SALE", null);
        seedLedger(su, balanceOnline, tenant, 10_000L, "Payment pi_bal_" + n + " for Order Y", bal, "SALE", null);
        seedLedger(su, reversalOfSplit, tenant, -10_000L, "Reversal of Split" + n, split, "REVERSAL", depositSplit);
        seedLedger(su, reversalOfAmbiguous, tenant, -1500L, "Reversal of Amb" + n, amb, "REVERSAL", ambiguous);
        seedLedger(su, manual, tenant, -300L, "Supplier invoice " + n, null, "MANUAL", null);
        // order_id has no foreign key (V40): a legacy row can name an order that is gone.
        seedLedger(su, danglingPayment, tenant, 900L, "Payment pi_gone_" + n + " for Order Z", UUID.randomUUID(), "SALE", null);
        seedLedger(su, danglingOrder, tenant, 800L, "Order Gone" + n, UUID.randomUUID(), "SALE", null);
        return new TenantSeed(tenant, settlementCard, counterCash, depositSplit, ambiguous, balanceOnline,
                reversalOfSplit, reversalOfAmbiguous, manual, danglingPayment, danglingOrder);
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

    private static UUID seedOrder(Connection c, UUID tenant, UUID shop, String paymentStatus, long total,
                                  Long deposit, Long balanceDue) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                        + "item_count, payment_status, deposit_amount_pennies, balance_due_pennies, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', 'V93 Customer', 'v93-customer@example.test', "
                        + "?, 'STANDARD', 0, ?, 0, 1, ?, ?, ?, now(), now())")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setObject(3, shop);
            ps.setString(4, "ORD-V93-" + id.toString().substring(0, 8));
            ps.setLong(5, total);
            ps.setLong(6, total);
            ps.setString(7, paymentStatus);
            ps.setObject(8, deposit);
            ps.setObject(9, balanceDue);
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

    private static Long online(Connection c, UUID ledgerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT paid_online_pennies FROM financial_transactions WHERE id = ?")) {
            ps.setObject(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("seeded row must still exist").isTrue();
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
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
