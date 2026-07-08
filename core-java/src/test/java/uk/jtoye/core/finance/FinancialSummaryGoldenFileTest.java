package uk.jtoye.core.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
import uk.jtoye.core.finance.dto.FinancialSummaryDto;
import uk.jtoye.core.security.TenantContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CQ-02 golden-file parity test — proves that the DB-side aggregation rewrite
 * of {@link FinancialTransactionService#getSummary()} produces the same
 * {@link FinancialSummaryDto} output as the legacy {@code findAll() + reduce}
 * implementation over a deterministic 1000-row fixture.
 *
 * <p>The fixture at {@code src/test/resources/fixtures/financial-summary-1k.golden.json}
 * is a committed snapshot of the pre-rewrite output. The active test
 * {@link #getSummaryOutputMatchesCommittedGolden()} seeds the same 1000-row
 * fixture, calls the current {@code getSummary()}, and uses AssertJ
 * {@code usingRecursiveComparison} to assert field-by-field equality with
 * the committed JSON.
 *
 * <p>Bootstrap: {@link #captureGoldenOnce()} is {@code @Disabled} during
 * normal runs. To regenerate the golden file, temporarily remove the
 * {@code @Disabled} annotation, run that single test method, commit the
 * regenerated JSON, then restore the {@code @Disabled} annotation.
 *
 * <p>Fixture determinism rules (RESEARCH §8):
 * <ul>
 *   <li>Fixed {@code new Random(42L)} seed — identical byte-for-byte across runs.</li>
 *   <li>Amounts constrained to multiples of 100. NOTE: with the HMRC VAT fraction
 *       method (Issue #81 — {@code gross*rate/(100+rate)}, round down), VAT is no
 *       longer exact on multiples of 100 (e.g. 100 STANDARD -&gt; 16, not 20). The
 *       golden values are therefore whatever the per-row fraction-method aggregate
 *       produces DB-side; determinism comes from the fixed seed + fixed rounding
 *       (integer division truncating toward zero), and Java/Postgres parity, NOT
 *       from the VAT being drift-free. Regenerate via captureGoldenOnce if the
 *       fixture or VAT math changes.</li>
 *   <li>VAT rate cycled deterministically per row index.</li>
 *   <li>VatBreakdown sorted by {@link VatRate#name()} before serialization for stable ordering.</li>
 * </ul>
 *
 * <p>Runs against Testcontainers Postgres 15 (Phase 12 Deviation #4 pattern) —
 * H2 defaults in {@code application-test.yml} would skip RLS + enum type handling.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class FinancialSummaryGoldenFileTest {

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
        // RabbitMQ stubs (Phase 12 Deviation #3) — broker absent in test env.
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    private static final Path GOLDEN_RELATIVE =
            Paths.get("src", "test", "resources", "fixtures", "financial-summary-1k.golden.json");

    // Phase 14 tenant — reuses the Phase-14 dedicated UUID scheme to avoid collisions with
    // Phase 12/13 fixtures when the full suite runs in a single JVM.
    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000014002");

    @Autowired FinancialTransactionService service;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        seedTenantIdempotent(TENANT_ID, "Phase 14-02 Golden Tenant");
        // Clear any residual fixture rows from a prior run — the same tenant UUID is reused.
        TenantContext.set(TENANT_ID);
        try {
            jdbcTemplate.update("DELETE FROM financial_transactions WHERE tenant_id = ?", TENANT_ID);
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void getSummaryOutputMatchesCommittedGolden() throws Exception {
        seed1kDeterministicFixture(TENANT_ID);
        TenantContext.set(TENANT_ID);

        FinancialSummaryDto actual;
        try {
            actual = service.getSummary();
        } finally {
            TenantContext.clear();
        }

        // Normalise VatBreakdown ordering — both legacy and new implementation must be
        // compared with a stable, tenant-agnostic sort for the assertion to be meaningful.
        FinancialSummaryDto actualStable = stabilise(actual);

        Path golden = locateGolden();
        assertThat(Files.exists(golden))
                .as("Golden file exists at %s — run captureGoldenOnce bootstrap to regenerate", golden)
                .isTrue();

        String goldenJson = Files.readString(golden);
        FinancialSummaryDto expected = objectMapper().readValue(goldenJson, FinancialSummaryDto.class);

        assertThat(actualStable)
                .usingRecursiveComparison()
                .ignoringCollectionOrder()
                .isEqualTo(expected);
    }

    /**
     * One-shot bootstrap. Re-enable by commenting out {@code @Disabled} temporarily,
     * run this single test, commit the regenerated
     * {@code core-java/src/test/resources/fixtures/financial-summary-1k.golden.json},
     * then restore {@code @Disabled}.
     */
    @Test
    @Disabled("One-shot bootstrap — re-enable manually to regenerate the golden file, then re-disable.")
    void captureGoldenOnce() throws Exception {
        seed1kDeterministicFixture(TENANT_ID);
        TenantContext.set(TENANT_ID);

        FinancialSummaryDto actual;
        try {
            actual = service.getSummary();
        } finally {
            TenantContext.clear();
        }

        FinancialSummaryDto stable = stabilise(actual);
        Path golden = locateGolden();
        Files.createDirectories(golden.getParent());
        objectMapper().writerWithDefaultPrettyPrinter().writeValue(golden.toFile(), stable);
    }

    // ---- Helpers ----

    private static ObjectMapper objectMapper() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    private static FinancialSummaryDto stabilise(FinancialSummaryDto dto) {
        List<FinancialSummaryDto.VatBreakdown> sorted = dto.vatBreakdown().stream()
                .sorted(Comparator.comparing(b -> b.vatRate().name()))
                .toList();
        return new FinancialSummaryDto(
                dto.totalRevenuePennies(),
                dto.totalExpensesPennies(),
                dto.netAmountPennies(),
                dto.totalVatPennies(),
                dto.transactionCount(),
                sorted);
    }

    private Path locateGolden() {
        // Gradle executes :core-java:test with the module sub-dir as the working dir,
        // so src/test/... resolves directly. But if invoked with a different CWD
        // (repo root, IDE run config), fall back to the module-scoped path. Use
        // parent-directory existence rather than file existence so capture-mode
        // (pre-file-write) still picks the correct path.
        Path relative = GOLDEN_RELATIVE;
        if (Files.isDirectory(relative.getParent())) {
            return relative;
        }
        Path moduleScoped = Paths.get("core-java").resolve(relative);
        return moduleScoped;
    }

    private void seedTenantIdempotent(UUID id, String name) {
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) "
                        + "ON CONFLICT (id) DO NOTHING",
                id, name);
    }

    /**
     * Deterministic 1000-row fixture for golden-file parity. Uses a fixed
     * {@code Random(42L)} seed and constrains amounts to multiples of 100 so
     * integer VAT math ({@code (amount * 20) / 100}) returns exact values
     * regardless of whether the math is done in the JVM or Postgres.
     *
     * <p>VAT rate distribution (cyclical per index): 40% STANDARD, 20% REDUCED,
     * 20% ZERO, 20% EXEMPT. Positive and negative amounts appear in roughly
     * equal proportion so both totalRevenue and totalExpenses accumulate
     * non-zero sums.
     *
     * <p>RLS is applied by the {@link uk.jtoye.core.security.TenantSetLocalAspect}
     * when {@code TenantContext} is set during a transactional boundary — but
     * {@code jdbcTemplate.batchUpdate} here runs outside a service transaction,
     * so we set the session-level GUC explicitly via {@code set_config} on the
     * pooled connection (connection-scoped, reset when returned to pool).
     */
    private void seed1kDeterministicFixture(UUID tenantId) {
        jdbcTemplate.execute(
                "SELECT set_config('app.current_tenant_id', '" + tenantId + "', false)");

        List<Object[]> batch = new ArrayList<>(1000);
        Random rng = new Random(42L);
        // 5-slot cyclical pattern: 40% STANDARD, 20% REDUCED, 20% ZERO, 20% EXEMPT.
        VatRate[] rates = {
                VatRate.STANDARD, VatRate.STANDARD,
                VatRate.REDUCED, VatRate.ZERO, VatRate.EXEMPT
        };
        for (int i = 0; i < 1000; i++) {
            UUID id = UUID.nameUUIDFromBytes(("fin-1k-" + i).getBytes());
            // Amounts in multiples of 100, in range [-100000, +100000] excluding zero.
            long magnitude = (rng.nextInt(1000) + 1) * 100L;
            long amount = rng.nextBoolean() ? magnitude : -magnitude;
            VatRate rate = rates[i % rates.length];
            String ref = "FIXTURE-1K-" + i;
            batch.add(new Object[]{id, tenantId, amount, rate.name(), ref});
        }

        // V12 converted vat_rate from the native enum type to VARCHAR — plain string bind, no cast.
        jdbcTemplate.batchUpdate(
                "INSERT INTO financial_transactions "
                        + "(id, tenant_id, created_at, amount_pennies, vat_rate, reference) "
                        + "VALUES (?, ?, now(), ?, ?, ?) "
                        + "ON CONFLICT (id) DO NOTHING",
                batch);
    }
}
