package uk.jtoye.core.common.idempotency;

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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.boot4.GoldenSamples;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BOOT4-08 (Phase 38, plan 38-10): a key reserved against a Boot-3.5 pod REPLAYS after the deploy.
 *
 * <p>The seeded {@code idempotency_keys} row is exactly what a Boot-3.5 pod left behind for an
 * {@code orders.create}: {@code request_hash} is the golden hash from
 * {@code jackson2-golden/idempotency/request-hashes.tsv} and {@code response_body} is the golden
 * {@code responses/OrderDto.json} (both captured on Boot 3.5.16, commit {@code e12177e1}). The client
 * then retries the same body against this (Boot-4) build. The answer must be the original 201 and
 * the original order; the create must not run again; and it must not be the 422
 * {@code IdempotencyPayloadMismatchException} a changed fingerprint format produces.
 *
 * <p><b>Runtime-like role.</b> The Testcontainers bootstrap role is a SUPERUSER and bypasses even
 * FORCE RLS, so the calls run under {@code SET LOCAL ROLE rls_test_role} (NOSUPERUSER NOBYPASSRLS),
 * the {@code IdempotencyKeysRlsPolicyIntegrationTest} recipe. {@code response_body} carries customer
 * PII, so whether the row is visible is part of what is under test.
 *
 * <p><b>Non-vacuity.</b> The tenant-B control shows the role is RLS-constrained: the same unscoped
 * count that sees tenant A's row as superuser sees nothing under the role with tenant B's GUC, and
 * tenant B's identical key is a fresh reservation (the work runs), never a replay of A's body.
 *
 * <p><b>Fail direction</b> (38-10 evidence): with {@code IdempotencyJson} on plain Jackson-3
 * defaults, the replay test fails with {@code IdempotencyPayloadMismatchException}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class IdempotencyLegacyHashReplayIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private IdempotencyService idempotencyService;
    @Autowired private JdbcTemplate jdbc;

    private static final String RLS_TEST_ROLE = "rls_test_role";
    private static final String ENDPOINT = "orders.create";
    private static final String KEY = "boot35-reserved-key-3810";

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void seedBoot35Reservation() throws IOException {
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
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "test-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "test-" + tenantB);

        String goldenHash = IdempotencyFingerprintGoldenTest.goldenHashes().get(ENDPOINT);
        assertThat(goldenHash).as("golden orders.create hash").hasSize(64);
        String goldenBody = Files.readString(
                IdempotencyFingerprintGoldenTest.GOLDEN.resolve("responses").resolve("OrderDto.json"),
                StandardCharsets.UTF_8);

        // The completed Boot-3.5 reservation, seeded as superuser (FORCE RLS bypassed here).
        TenantContext.set(tenantA);
        try {
            jdbc.update(
                    "INSERT INTO idempotency_keys (tenant_id, endpoint, idempotency_key, request_hash, response_status, response_body) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    tenantA, ENDPOINT, KEY, goldenHash, 201, goldenBody);
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private void dropSuperuserForTransaction() {
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
    }

    @Test
    @DisplayName("a Boot-3.5 orders.create reservation replays its original 201 under rls_test_role; the work never runs")
    void boot35ReservationReplaysUnderTheRuntimeRole() {
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();
        AtomicInteger workRuns = new AtomicInteger();

        IdempotencyOutcome<OrderDto> outcome = idempotencyService.execute(
                ENDPOINT, KEY, GoldenSamples.createOrderRequest(), OrderDto.class,
                () -> {
                    workRuns.incrementAndGet();
                    return new OrderDto();
                });

        assertThat(workRuns).as("the create must not run again for a replayed key").hasValue(0);
        assertThat(outcome.status()).isEqualTo(201);
        assertThat(outcome.value())
                .as("the order a Boot-3.5 pod stored, read back (OffsetDateTime compared by instant)")
                .usingRecursiveComparison()
                .withComparatorForType(Comparator.comparing(OffsetDateTime::toInstant), OffsetDateTime.class)
                .isEqualTo(GoldenSamples.orderDto());
    }

    @Test
    @DisplayName("control: tenant B under rls_test_role cannot see tenant A's row; the same key is a fresh reservation")
    void tenantBUnderTheRuntimeRoleCannotReplayTenantAsRow() {
        String unscopedCount = "SELECT count(*) FROM idempotency_keys WHERE endpoint = ? AND idempotency_key = ?";
        TenantContext.set(tenantB);
        assertThat(jdbc.queryForObject(unscopedCount, Integer.class, ENDPOINT, KEY))
                .as("positive control: as superuser the unscoped count sees tenant A's row")
                .isEqualTo(1);

        dropSuperuserForTransaction();
        assertThat(jdbc.queryForObject(unscopedCount, Integer.class, ENDPOINT, KEY))
                .as("under rls_test_role with tenant B's GUC, FORCE RLS hides tenant A's row")
                .isZero();

        AtomicInteger workRuns = new AtomicInteger();
        OrderDto fresh = new OrderDto();
        fresh.setOrderNumber("JT-TENANT-B-FRESH");
        IdempotencyOutcome<OrderDto> outcome = idempotencyService.execute(
                ENDPOINT, KEY, GoldenSamples.createOrderRequest(), OrderDto.class,
                () -> {
                    workRuns.incrementAndGet();
                    return fresh;
                });

        assertThat(workRuns).as("tenant B's key is a new reservation, so its work runs").hasValue(1);
        assertThat(outcome.status()).isEqualTo(201);
        assertThat(outcome.value().getOrderNumber())
                .as("tenant B gets its own result, never tenant A's stored order")
                .isEqualTo("JT-TENANT-B-FRESH");
    }
}
