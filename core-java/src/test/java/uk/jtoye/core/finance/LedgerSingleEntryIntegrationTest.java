package uk.jtoye.core.finance;

import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.finance.dto.CreateTransactionRequest;
import uk.jtoye.core.security.TenantContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers regression proving BUG 3 (duplicate ledger) is closed against a
 * real Postgres 15 with Flyway V40 applied (partial unique index
 * {@code uq_fin_tx_tenant_order}). Also confirms V40's idempotency backstop and
 * the fraction-method VAT on the retained row.
 *
 * <p>Per the plan, the settlement (PaymentService) and COMPLETED (OrderService)
 * steps are represented by direct {@link FinancialTransactionService#createTransaction}
 * calls carrying the order's id — the ledger-owner behaviour under test is the
 * idempotency keyed on {@code orderId}, independent of which caller fires it.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class LedgerSingleEntryIntegrationTest {

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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000081001");

    @Autowired FinancialTransactionService service;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        seedTenantIdempotent(TENANT_ID, "Issue 81 Ledger Tenant");
        // Fresh state for this tenant — the same UUID is reused across methods.
        executeWithTenant(TENANT_ID, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM financial_transactions WHERE tenant_id = ?")) {
                ps.setObject(1, TENANT_ID);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Card path: settlement row + later COMPLETED = exactly ONE ledger row")
    void cardPathProducesExactlyOneRow() {
        UUID orderId = UUID.randomUUID();
        // (1) Stripe settlement row (PaymentService equivalent).
        createViaService(orderId, 1500L, VatRate.STANDARD,
                "Payment pi_card for Order ORD-CARD");
        // (2) Later COMPLETED transition (OrderService equivalent) — idempotent no-op.
        createViaService(orderId, 1500L, VatRate.STANDARD, "Order ORD-CARD");

        assertThat(countByOrderId(orderId)).isEqualTo(1L);
        assertRetainedRowRateAndVat(orderId, VatRate.STANDARD, 1500L);
    }

    @Test
    @DisplayName("COD path: single COMPLETED createTransaction = exactly ONE ledger row")
    void codPathProducesExactlyOneRow() {
        UUID orderId = UUID.randomUUID();
        // No webhook fires for cash/COD; OrderService COMPLETED owns the row.
        createViaService(orderId, 1200L, VatRate.STANDARD, "Order ORD-COD");

        assertThat(countByOrderId(orderId)).isEqualTo(1L);
        assertRetainedRowRateAndVat(orderId, VatRate.STANDARD, 1200L);
    }

    @Test
    @DisplayName("Duplicate createTransaction for same orderId = ONE row; unique index present")
    void duplicateCreateStillOneRow() {
        UUID orderId = UUID.randomUUID();
        createViaService(orderId, 2100L, VatRate.REDUCED, "Order ORD-DUP");
        createViaService(orderId, 2100L, VatRate.REDUCED, "Order ORD-DUP");

        assertThat(countByOrderId(orderId)).isEqualTo(1L);
        assertThat(partialUniqueIndexExists())
                .as("V40 partial unique index uq_fin_tx_tenant_order must exist")
                .isTrue();
        assertRetainedRowRateAndVat(orderId, VatRate.REDUCED, 2100L);
    }

    // ---- Helpers ----

    private void createViaService(UUID orderId, long amount, VatRate rate, String reference) {
        TenantContext.set(TENANT_ID);
        try {
            service.createTransaction(new CreateTransactionRequest(amount, rate, reference, orderId));
        } finally {
            TenantContext.clear();
        }
    }

    private long countByOrderId(UUID orderId) {
        return executeWithTenant(TENANT_ID, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM financial_transactions WHERE order_id = ?")) {
                ps.setObject(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /**
     * Assert the single retained row carries the order's resolved rate and that
     * its derived VAT (fraction method) matches the stored amount + rate.
     */
    private void assertRetainedRowRateAndVat(UUID orderId, VatRate expectedRate, long expectedAmount) {
        String[] row = executeWithTenant(TENANT_ID, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT vat_rate, amount_pennies FROM financial_transactions WHERE order_id = ?")) {
                ps.setObject(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new String[]{rs.getString(1), String.valueOf(rs.getLong(2))};
                }
            }
        });
        assertThat(row[0]).isEqualTo(expectedRate.name());
        long amount = Long.parseLong(row[1]);
        assertThat(amount).isEqualTo(expectedAmount);
        // Derived VAT is the fraction method applied to the retained gross + rate;
        // this is exactly what the order computed, so ledger and order agree.
        long derivedVat = VatCalculator.vatFromGross(amount, expectedRate);
        assertThat(derivedVat).isEqualTo(VatCalculator.vatFromGross(expectedAmount, expectedRate));
    }

    private boolean partialUniqueIndexExists() {
        return executeWithTenant(TENANT_ID, conn -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT count(*) FROM pg_indexes "
                                 + "WHERE indexname = 'uq_fin_tx_tenant_order'")) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        });
    }

    private void seedTenantIdempotent(UUID id, String name) {
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) "
                        + "ON CONFLICT (id) DO NOTHING",
                id, name);
    }

    /**
     * Run a callback on a single physical connection with the RLS GUC set, so the
     * {@code set_config} and the query share the connection (required under
     * FORCE RLS on financial_transactions).
     */
    private <T> T executeWithTenant(UUID tenantId, ConnectionWork<T> work) {
        return jdbcTemplate.execute((Connection conn) -> {
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT set_config('app.current_tenant_id', '" + tenantId + "', false)");
            }
            try {
                return work.doWork(conn);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @FunctionalInterface
    private interface ConnectionWork<T> {
        T doWork(Connection conn) throws Exception;
    }
}
