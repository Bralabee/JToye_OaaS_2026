package uk.jtoye.core.webhook;

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
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COMMS-05 (#107, T-22-05-05) — the bounded-retention prune proof over real
 * Postgres 15 (Testcontainers). {@link WebhookRetentionCleanup} deletes
 * webhook_delivery rows older than the configured window and keeps recent ones,
 * and — per SPEC AC #13 — it is scoped to webhook_delivery ONLY: it must NEVER
 * delete a {@code notification_suppression} row (time-pruning a GDPR/PECR opt-out
 * would resurrect a suppressed recipient).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class WebhookRetentionCleanupTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("webhook.delivery.retention-days", () -> "30");
        // Keep the @Scheduled worker/retention auto-ticks out of the test window.
        registry.add("webhook.delivery.interval-ms", () -> "3600000");
        registry.add("webhook.delivery.retention-interval-ms", () -> "3600000");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebhookRetentionCleanup retentionCleanup;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM webhook_delivery");
        jdbc.update("DELETE FROM notification_suppression");

        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "test-" + tenantId);
    }

    private UUID insertDelivery(OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO webhook_delivery "
                        + "(id, tenant_id, subscription_id, event_id, event_type, payload, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'DELIVERED', ?)",
                id, tenantId, UUID.randomUUID(), UUID.randomUUID(), "order.ready", "{}", createdAt);
        return id;
    }

    private boolean deliveryExists(UUID id) {
        Integer c = jdbc.queryForObject(
                "SELECT count(*) FROM webhook_delivery WHERE id = ?", Integer.class, id);
        return c != null && c > 0;
    }

    @Test
    void prunesRowsOlderThanWindow_keepsRecent_andNeverPrunesSuppression() {
        UUID oldDelivery = insertDelivery(OffsetDateTime.now().minusDays(40));   // older than 30d window
        UUID recentDelivery = insertDelivery(OffsetDateTime.now().minusDays(1)); // inside the window

        // A GDPR/PECR opt-out that is far older than the webhook window — must survive.
        UUID suppressionId = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_suppression (id, tenant_id, recipient, category, created_at) "
                        + "VALUES (?, ?, ?, 'ORDERS', ?)",
                suppressionId, tenantId, "opted-out@example.com", OffsetDateTime.now().minusDays(100));

        retentionCleanup.pruneExpired();

        assertThat(deliveryExists(oldDelivery))
                .as("a webhook_delivery row older than retention-days is pruned").isFalse();
        assertThat(deliveryExists(recentDelivery))
                .as("a recent webhook_delivery row is retained").isTrue();

        Integer suppressionRows = jdbc.queryForObject(
                "SELECT count(*) FROM notification_suppression WHERE id = ?", Integer.class, suppressionId);
        assertThat(suppressionRows)
                .as("retention is scoped to webhook_delivery ONLY — suppression is never time-pruned (SPEC AC #13)")
                .isEqualTo(1);
    }
}
