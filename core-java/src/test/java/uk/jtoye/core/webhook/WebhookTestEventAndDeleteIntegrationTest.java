package uk.jtoye.core.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.jtoye.core.testsupport.TenantJwts.adminJwt;

/**
 * #879 / UXT-122: an integrator can prove an endpoint works without waiting for a real order, and can delete
 * the dead ones.
 *
 * <p>The ping rides the SAME delivery path as a real event (the stored row, the signed bytes, the SSRF-hardened
 * client, the delivery log), so a green ping means the real thing will arrive. Driven over real Postgres with
 * the worker's scripted receiver standing in for the vendor's server; the worker is ticked by hand.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import({WebhookAutoPauseIntegrationTest.ScriptedReceiverConfig.class, NoScheduledTriggersTestConfig.class})
class WebhookTestEventAndDeleteIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("webhook.target.block-private-ranges", () -> "false");
        registry.add("webhook.delivery.interval-ms", () -> "3600000");
        registry.add("webhook.delivery.retention-interval-ms", () -> "3600000");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired WebhookDeliveryWorker worker;
    @Autowired WebhookAutoPauseIntegrationTest.ScriptedReceiverConfig.ScriptedReceiver receiver;

    UUID tenantId;
    UUID otherTenantId;

    @BeforeEach
    void setUp() {
        receiver.reset();
        tenantId = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "wt-" + tenantId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", otherTenantId, "wt-" + otherTenantId);
    }

    private UUID subscription(UUID tenant, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO webhook_subscription (id, tenant_id, target_url, event_types, signing_secret, status) "
                        + "VALUES (?, ?, 'https://hooks.example.com/jtoye', '{ORDER_STATE_CHANGED}'::text[], ?, ?)",
                id, tenant, "sig-" + id, status);
        return id;
    }

    private JsonNode ping(UUID subscriptionId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/webhooks/" + subscriptionId + "/test").with(adminJwt(tenantId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveryId").isNotEmpty())
                .andExpect(jsonPath("$.eventId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String deliveryStatus(String deliveryId) {
        return jdbc.queryForObject("SELECT status FROM webhook_delivery WHERE id = ?::uuid", String.class, deliveryId);
    }

    @Test
    @DisplayName("#879: a test event is queued, signed and delivered to the endpoint like a real one, and shows in the log")
    void testEventIsDeliveredAndLogged() throws Exception {
        UUID sub = subscription(tenantId, "ACTIVE");

        JsonNode queued = ping(sub);
        UUID eventId = UUID.fromString(queued.path("eventId").asString());
        receiver.script(eventId, 0);
        worker.deliverDue();

        assertThat(deliveryStatus(queued.path("deliveryId").asString())).isEqualTo("DELIVERED");
        assertThat(receiver.attempts(eventId)).as("the endpoint received exactly one POST").isEqualTo(1);

        JsonNode envelope = json.readTree(jdbc.queryForObject(
                "SELECT payload FROM webhook_delivery WHERE id = ?::uuid", String.class,
                queued.path("deliveryId").asString()));
        assertThat(envelope.path("type").asString()).isEqualTo("webhook.test");
        assertThat(envelope.path("id").asString()).isEqualTo(eventId.toString());
        assertThat(envelope.path("tenantId").asString()).isEqualTo(tenantId.toString());
        assertThat(envelope.path("data").path("subscriptionId").asString()).isEqualTo(sub.toString());
        assertThat(envelope.path("data").path("message").asString()).isNotBlank();

        mockMvc.perform(get("/api/v1/webhooks/" + sub + "/deliveries").with(adminJwt(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventType").value("webhook.test"))
                .andExpect(jsonPath("$.content[0].status").value("DELIVERED"));
    }

    @Test
    @DisplayName("#879: a failing endpoint fails its ping once and is not retried for a day, and pings never auto-pause it")
    void failedPingIsTerminalAndNeverPausesTheEndpoint() throws Exception {
        UUID sub = subscription(tenantId, "ACTIVE");

        for (int i = 0; i < 4; i++) {
            JsonNode queued = ping(sub);
            // Unscripted: the receiver answers 503 to it.
            worker.deliverDue();
            assertThat(deliveryStatus(queued.path("deliveryId").asString()))
                    .as("ping %d: one attempt, then FAILED (a retry schedule is for real events)", i + 1)
                    .isEqualTo("FAILED");
            assertThat(receiver.attempts(UUID.fromString(queued.path("eventId").asString()))).isEqualTo(1);
        }

        assertThat(jdbc.queryForObject("SELECT status FROM webhook_subscription WHERE id = ?", String.class, sub))
                .as("four failed pings are the integrator testing a broken endpoint, not the endpoint failing in production")
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM webhook_subscription WHERE id = ?",
                Integer.class, sub)).isZero();
    }

    @Test
    @DisplayName("#879: a ping needs an ACTIVE endpoint: paused and revoked ones refuse with a typed 409, and a stranger's is 404")
    void pingNeedsAnActiveEndpoint() throws Exception {
        for (String status : new String[]{"PAUSED", "AUTO_PAUSED", "REVOKED"}) {
            UUID sub = subscription(tenantId, status);
            mockMvc.perform(post("/api/v1/webhooks/" + sub + "/test").with(adminJwt(tenantId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/webhook-subscription-state"))
                    .andExpect(jsonPath("$.subscriptionStatus").value(status));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE subscription_id = ?",
                    Integer.class, sub)).as("a refused ping queues nothing").isZero();
        }

        UUID strangers = subscription(otherTenantId, "ACTIVE");
        mockMvc.perform(post("/api/v1/webhooks/" + strangers + "/test").with(adminJwt(tenantId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("#879: DELETE removes a REVOKED endpoint and its log, refuses a live one, and cannot reach another tenant's")
    void deleteRemovesOnlyRevokedEndpoints() throws Exception {
        UUID live = subscription(tenantId, "ACTIVE");
        mockMvc.perform(delete("/api/v1/webhooks/" + live).with(adminJwt(tenantId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/webhook-subscription-state"))
                .andExpect(jsonPath("$.subscriptionStatus").value("ACTIVE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_subscription WHERE id = ?", Integer.class, live))
                .as("a live endpoint survives a refused delete").isEqualTo(1);

        UUID dead = subscription(tenantId, "ACTIVE");
        ping(dead);
        mockMvc.perform(post("/api/v1/webhooks/" + dead + "/revoke").with(adminJwt(tenantId))).andExpect(status().isOk());
        UUID bystander = subscription(tenantId, "ACTIVE");
        ping(bystander);

        mockMvc.perform(delete("/api/v1/webhooks/" + dead).with(adminJwt(tenantId)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_subscription WHERE id = ?", Integer.class, dead))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE subscription_id = ?",
                Integer.class, dead)).as("its delivery log goes with it").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE subscription_id = ?",
                Integer.class, bystander)).as("another endpoint's log is untouched").isEqualTo(1);
        mockMvc.perform(get("/api/v1/webhooks/" + dead).with(adminJwt(tenantId))).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/webhooks/" + dead).with(adminJwt(tenantId))).andExpect(status().isNotFound());

        UUID strangersRevoked = subscription(otherTenantId, "REVOKED");
        mockMvc.perform(delete("/api/v1/webhooks/" + strangersRevoked).with(adminJwt(tenantId)))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_subscription WHERE id = ?", Integer.class,
                strangersRevoked)).as("another tenant's endpoint is out of reach").isEqualTo(1);
    }
}
