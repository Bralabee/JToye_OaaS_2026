package uk.jtoye.core.finance;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import uk.jtoye.core.security.TenantContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CQ-02 query-count pin — the reliable RED lever for the pre-rewrite state.
 *
 * <p>Before Task 14-02-02 rewrites {@link FinancialTransactionService#getSummary()},
 * the method calls {@code findAll()} which issues exactly <em>one</em>
 * prepared statement. After the rewrite, the method issues exactly <em>two</em>:
 * the scalar aggregate query and the {@code GROUP BY vatRate} breakdown query.
 *
 * <p>This test asserts the post-rewrite count (2) — so it FAILS on the
 * pre-rewrite tree and PASSES after Task 14-02-02. That RED→GREEN flip is
 * the CQ-02 TDD gate.
 *
 * <p>Requires {@code hibernate.generate_statistics=true} to be active for the
 * EntityManagerFactory during this test — enabled via {@code @DynamicPropertySource}.
 *
 * <p>Defensive note: the prepared-statement count is measured across the whole
 * method invocation, and {@link FinancialTransactionService#getSummary()} is
 * {@code @Transactional(readOnly = true)} — no implicit save/flush side-effects
 * should inflate the count. If a future change adds a pre-read hook the test
 * will catch it.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class FinancialSummaryQueryCountTest {

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
        // Enable Hibernate stats for prepared-statement counting.
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
        // PaymentEventOutboxFlusher polls every 5s by default — a tick landing
        // inside the measurement window below adds a prepared statement and
        // flakes the exact-count assertion (observed under full-suite load:
        // expected 2, was 3). fixedDelay's initial tick fires at context boot,
        // long before stats.clear(); a 1h interval guarantees no second tick
        // during the test. Bonus: the unique property value keeps this context
        // out of Spring's context cache, so no other class's schedulers share
        // this SessionFactory's statistics.
        registry.add("payment.outbox.flush-interval-ms", () -> "3600000");
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000014004");

    @Autowired FinancialTransactionService service;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManagerFactory emf;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        seedTenantIdempotent(TENANT_ID, "Phase 14-02 QueryCount Tenant");
        TenantContext.set(TENANT_ID);
        try {
            jdbcTemplate.update("DELETE FROM financial_transactions WHERE tenant_id = ?", TENANT_ID);
        } finally {
            TenantContext.clear();
        }
        seedSmallFixture(TENANT_ID, 20);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void getSummaryIssuesExactlyTwoPreparedStatements() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        TenantContext.set(TENANT_ID);
        try {
            service.getSummary();
        } finally {
            TenantContext.clear();
        }

        // Per-query statistics filtered to FinancialTransaction queries, NOT a
        // global prepared-statement delta: the shared JVM has background JPA
        // activity (e.g. the PaymentEventOutboxFlusher's 5s poll) whose
        // statements race into a global count and flaked this test (observed:
        // expected 2, was 3 under full-suite load). Filtering preserves the
        // CQ-02 contract exactly while ignoring unrelated statements.
        String[] queries = stats.getQueries();
        java.util.List<String> ftQueries = java.util.Arrays.stream(queries)
                .filter(q -> q.contains("FinancialTransaction"))
                .toList();

        long ftExecutions = ftQueries.stream()
                .mapToLong(q -> stats.getQueryStatistics(q).getExecutionCount())
                .sum();

        assertThat(ftExecutions)
                .as("getSummary must issue exactly 2 FinancialTransaction queries "
                        + "(aggregate + GROUP BY vatRate), not 1 (findAll) or >2. Ran: " + ftQueries)
                .isEqualTo(2L);

        // The CQ-02 regression shape is a full-entity fetch (findAll) followed by
        // in-memory aggregation — every FinancialTransaction query here must be a
        // DB-side aggregate, never an entity scan.
        assertThat(ftQueries)
                .as("all FinancialTransaction queries must aggregate DB-side")
                .allMatch(q -> {
                    String lower = q.toLowerCase();
                    return lower.contains("sum(") || lower.contains("count(");
                });
    }

    // ---- Seed helpers ----

    private void seedTenantIdempotent(UUID id, String name) {
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) "
                        + "ON CONFLICT (id) DO NOTHING",
                id, name);
    }

    private void seedSmallFixture(UUID tenantId, int count) {
        jdbcTemplate.execute(
                "SELECT set_config('app.current_tenant_id', '" + tenantId + "', false)");

        List<Object[]> batch = new ArrayList<>(count);
        VatRate[] rates = VatRate.values();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.nameUUIDFromBytes((tenantId + "-qcount-" + i).getBytes());
            long amount = ((i % 2 == 0) ? 1L : -1L) * ((i + 1) * 1000L);
            VatRate rate = rates[i % rates.length];
            batch.add(new Object[]{id, tenantId, amount, rate.name(), "QCOUNT-" + i});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO financial_transactions "
                        + "(id, tenant_id, created_at, amount_pennies, vat_rate, reference) "
                        + "VALUES (?, ?, now(), ?, ?, ?) "
                        + "ON CONFLICT (id) DO NOTHING",
                batch);
    }
}
