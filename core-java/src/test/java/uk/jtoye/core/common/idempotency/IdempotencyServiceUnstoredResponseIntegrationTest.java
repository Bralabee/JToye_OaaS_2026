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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.exception.IdempotencyPayloadMismatchException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * QA council 20260902-134741, adjudication A3 — the {@code persistResponse=false} variant of the
 * V50 store, {@link IdempotencyService#executeWithoutStoringResponse}.
 *
 * <p>{@link IdempotencyService#execute} stamps {@code serialize(result)} into
 * {@code idempotency_keys.response_body} unconditionally. That is unusable for a response that
 * carries a credential (the guest checkout's {@code GuestOrderConfirmation.clientSecret} is a
 * Stripe PaymentIntent client secret): it would archive a browser-presentable payment credential
 * at rest, and a stale one — the storefront deliberately re-fetches a live secret on replay
 * (WR-02). The variant keeps everything else — reservation, request hash, in-flight 409,
 * mismatch 422 — and leaves {@code response_body} NULL, delegating the replay value to a caller
 * supplied re-derivation.
 *
 * <p>The IS-NULL assertion is shown to be FALSIFIABLE by the control arm, which runs the storing
 * {@code execute} on the same table and reads a NON-null body back — same query, same column,
 * opposite answer.
 *
 * <p>Real Postgres: the reservation is {@code INSERT ... ON CONFLICT DO NOTHING} on the V50 PK.
 * Superuser bootstrap role, so RLS is bypassed here (the RLS proof is
 * {@code IdempotencyKeysRlsPolicyIntegrationTest}); {@code TenantContext} is still set because
 * {@code execute} requires it and pins the GUC from it.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class IdempotencyServiceUnstoredResponseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired IdempotencyService idempotencyService;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000903");
    private static final String ENDPOINT = "test.unstored.create";
    /** Deliberately NOT Stripe-shaped; a sentinel we can prove is absent from the whole table. */
    private static final String SENTINEL_SECRET = "credential-that-must-never-reach-the-store-9f2c";

    /** A response shaped like the guest confirmation: an identifier plus a credential. */
    record CredentialBearing(String orderNumber, String clientSecret) {}

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "A3 Unstored Tenant");
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("first call: runs the work once, stamps response_status, and leaves response_body NULL — the credential never reaches the table")
    void firstCall_runsWork_stampsStatus_andStoresNoBody() {
        String key = "a3-first-" + UUID.randomUUID();
        AtomicInteger workRuns = new AtomicInteger();

        IdempotencyOutcome<CredentialBearing> outcome = idempotencyService.executeWithoutStoringResponse(
                ENDPOINT, key, Map.of("basket", "A"),
                () -> {
                    workRuns.incrementAndGet();
                    return new CredentialBearing("ORD-A3-1", SENTINEL_SECRET);
                },
                () -> fail("the replay supplier must not run on the FIRST call"));

        assertThat(outcome.status()).isEqualTo(201);
        assertThat(outcome.value().clientSecret()).as("the live response still carries the credential").isEqualTo(SENTINEL_SECRET);
        assertThat(workRuns).hasValue(1);

        Map<String, Object> row = reservationRow(key);
        assertThat(row.get("response_status")).as("completed, not in-flight").isEqualTo(201);
        assertThat(row.get("response_body")).as("persistResponse=false: nothing stored").isNull();
        assertThat(row.get("request_hash")).asString().hasSize(64);
        assertThat(countRowsContaining(SENTINEL_SECRET))
                .as("the credential must appear NOWHERE in the store, under any key")
                .isZero();
    }

    @Test
    @DisplayName("replay: the caller's replay supplier re-derives the value; the work does not run again")
    void replay_invokesTheReplaySupplier_notTheWork() {
        String key = "a3-replay-" + UUID.randomUUID();
        AtomicInteger workRuns = new AtomicInteger();
        AtomicInteger replayRuns = new AtomicInteger();

        idempotencyService.executeWithoutStoringResponse(ENDPOINT, key, Map.of("basket", "B"),
                () -> {
                    workRuns.incrementAndGet();
                    return new CredentialBearing("ORD-A3-2", "first-secret");
                },
                () -> fail("no replay on the first call"));

        IdempotencyOutcome<CredentialBearing> replayed = idempotencyService.executeWithoutStoringResponse(
                ENDPOINT, key, Map.of("basket", "B"),
                () -> fail("the work must not run twice for one key"),
                () -> {
                    replayRuns.incrementAndGet();
                    return new CredentialBearing("ORD-A3-2", "freshly-re-derived-secret");
                });

        assertThat(workRuns).hasValue(1);
        assertThat(replayRuns).hasValue(1);
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(replayed.value().clientSecret())
                .as("the replay value is whatever the caller re-derives NOW, not a stored copy")
                .isEqualTo("freshly-re-derived-secret");
    }

    @Test
    @DisplayName("same key + different body: refused 422 before any work runs")
    void differentBody_isRefused() {
        String key = "a3-mismatch-" + UUID.randomUUID();
        idempotencyService.executeWithoutStoringResponse(ENDPOINT, key, Map.of("basket", "C"),
                () -> new CredentialBearing("ORD-A3-3", "s"), () -> fail("no replay on first call"));

        assertThatThrownBy(() -> idempotencyService.executeWithoutStoringResponse(ENDPOINT, key, Map.of("basket", "C-changed"),
                () -> fail("mismatch must not run the work"), () -> fail("mismatch must not replay")))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
    }

    @Test
    @DisplayName("CONTROL (falsifier): the storing execute() on the same table DOES persist the body — so the IS NULL check above can fail")
    void control_storingVariant_persistsTheBody() {
        String key = "a3-control-" + UUID.randomUUID();

        idempotencyService.execute(ENDPOINT, key, Map.of("basket", "D"), CredentialBearing.class,
                () -> new CredentialBearing("ORD-A3-4", SENTINEL_SECRET + "-control"));

        Map<String, Object> row = reservationRow(key);
        assertThat(row.get("response_status")).isEqualTo(201);
        assertThat(row.get("response_body")).asString()
                .as("the storing variant writes the serialized DTO — the opposite answer from the same query")
                .contains("ORD-A3-4")
                .contains(SENTINEL_SECRET + "-control");
    }

    private Map<String, Object> reservationRow(String key) {
        return jdbcTemplate.queryForMap(
                "SELECT request_hash, response_status, response_body FROM idempotency_keys "
                        + "WHERE tenant_id = ? AND endpoint = ? AND idempotency_key = ?",
                TENANT_ID, ENDPOINT, key);
    }

    private long countRowsContaining(String needle) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE response_body LIKE ?", Long.class, "%" + needle + "%");
        return Objects.requireNonNull(n);
    }
}
