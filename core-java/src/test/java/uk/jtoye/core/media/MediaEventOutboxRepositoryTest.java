package uk.jtoye.core.media;

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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1 proof (IMG-02): V58 {@code media_event_outbox} applies cleanly on a fresh
 * Testcontainers DB, the entity persists, and the {@code FOR UPDATE SKIP LOCKED}
 * claim query returns PENDING rows whose backoff window has elapsed. Mirrors the
 * PaymentEventOutbox repo behaviour.
 *
 * <p>Runs as the Testcontainers superuser (RLS bypassed) — this proves the
 * outbox MECHANICS; the tenant wall on media tables is proven separately under the
 * NOSUPERUSER downgrade in {@code MediaAssetRlsPolicyIntegrationTest}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional  // @Modifying resurrectFailed() requires an ambient tx (the flusher always calls it inside one)
class MediaEventOutboxRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MediaEventOutboxRepository repository;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenant;

    @BeforeEach
    void seedTenant() {
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "test-" + tenant);
        TenantContext.set(tenant);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void persistsAndClaimsPendingRow() {
        UUID assetId = UUID.randomUUID();
        MediaEventOutbox row = new MediaEventOutbox(tenant, assetId, "{\"tenantId\":\"" + tenant
                + "\",\"assetId\":\"" + assetId + "\"}");
        // Backdate next_attempt_at so the row is unambiguously eligible for the immediate
        // claim (production claims on a LATER flusher tick, well past the insert's now();
        // an immediate claim in-test is otherwise sensitive to JVM-vs-DB clock skew).
        row.setNextAttemptAt(OffsetDateTime.now().minusMinutes(1));
        MediaEventOutbox saved = repository.saveAndFlush(row);

        assertThat(saved.getId()).as("V58 media_event_outbox persists with a generated id").isNotNull();
        assertThat(saved.getStatus()).isEqualTo(MediaEventOutbox.Status.PENDING);

        List<MediaEventOutbox> claimed = repository.claimPendingBatch(10);
        assertThat(claimed)
                .as("the FOR UPDATE SKIP LOCKED claim query returns the eligible PENDING row")
                .extracting(MediaEventOutbox::getId)
                .contains(saved.getId());
        assertThat(claimed).allSatisfy(r ->
                assertThat(r.getStatus()).isEqualTo(MediaEventOutbox.Status.PENDING));
    }

    @Test
    void resurrectReturnsNonPoisonFailedRowsToPending() {
        UUID assetId = UUID.randomUUID();
        MediaEventOutbox row = new MediaEventOutbox(tenant, assetId, "{}");
        row.setStatus(MediaEventOutbox.Status.FAILED);
        row.setPoison(false);
        UUID id = repository.saveAndFlush(row).getId();

        int resurrected = repository.resurrectFailed();
        assertThat(resurrected).as("a non-poison FAILED row is re-leased").isGreaterThanOrEqualTo(1);
        assertThat(repository.findById(id).orElseThrow().getStatus())
                .as("resurrected row returns to PENDING")
                .isEqualTo(MediaEventOutbox.Status.PENDING);
    }
}
