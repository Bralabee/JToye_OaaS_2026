package uk.jtoye.core.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * COMMS-05 end-to-end proof over real Postgres 15 (Testcontainers): the
 * {@link WebhookDeliveryWorker} signs the stored payload, POSTs it via WebClient
 * (mocked here), and — with one failing (500) and one healthy (200) subscription —
 * delivers the healthy one while the failing one retries to FAILED and the
 * subscription AUTO_PAUSES, proving there is NO head-of-line block. A local mock
 * receiver recomputes the HMAC over {@code t + "." + rawBody} and matches the
 * {@code X-JToye-Signature} header (payload bytes signed == bytes POSTed). A
 * manual replay adds one {@code is_replay} row without mutating the original.
 *
 * <p>The WebClient egress is mocked via an {@link ExchangeFunction} injected
 * through a {@code @Primary} {@link WebClient.Builder} — no real network, no
 * cert. Deliveries target {@code https://ok.example.com} / {@code https://fail.example.com}
 * (HTTPS passes the delivery-time SSRF re-validation with {@code block-private-ranges=false},
 * and the mock intercepts before any real call).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(WebhookDeliveryWorkerIntegrationTest.MockWebClientConfig.class)
class WebhookDeliveryWorkerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // Hermetic: skip DNS resolution; HTTPS scheme is still enforced at egress.
        registry.add("webhook.target.block-private-ranges", () -> "false");
        // Small + aligned so a single failing delivery both FAILS (max-attempts)
        // and AUTO_PAUSES the subscription (auto-pause-threshold) on the same tick.
        registry.add("webhook.delivery.max-attempts", () -> "3");
        registry.add("webhook.delivery.auto-pause-threshold", () -> "3");
        registry.add("webhook.delivery.backoff-base-ms", () -> "1");
        registry.add("webhook.delivery.backoff-cap-ms", () -> "10");
        registry.add("webhook.delivery.batch-size", () -> "50");
        // Push the @Scheduled auto-tick far out so only our manual deliverDue()
        // calls drive delivery (the first tick at startup finds an empty DB).
        registry.add("webhook.delivery.interval-ms", () -> "3600000");
        registry.add("webhook.delivery.retention-interval-ms", () -> "3600000");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebhookDeliveryWorker worker;
    @Autowired private WebhookFanoutListener fanoutListener;
    @Autowired private WebhookSigner signer;
    @Autowired private MockWebClientConfig.CapturingExchange exchange;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        // Clean slate per test (no @Transactional here — the worker commits).
        jdbc.update("DELETE FROM webhook_delivery");
        jdbc.update("DELETE FROM webhook_subscription");
        exchange.recorded.clear();

        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "test-" + tenantId);
    }

    private UUID insertSubscription(String url, String secret) {
        UUID id = UUID.randomUUID();
        TenantContext.set(tenantId);
        try {
            jdbc.update("INSERT INTO webhook_subscription "
                            + "(id, tenant_id, target_url, event_types, signing_secret, status) "
                            + "VALUES (?, ?, ?, '{ORDER_STATE_CHANGED}'::text[], ?, 'ACTIVE')",
                    id, tenantId, url, secret);
        } finally {
            TenantContext.clear();
        }
        return id;
    }

    private void fanoutOrderReady() {
        fanoutListener.onOrderState(new OrderStateChangeEvent(
                UUID.randomUUID(), tenantId, "ORD-1",
                OrderStatus.PREPARING, OrderStatus.READY, OffsetDateTime.now()));
    }

    private String statusOf(String table, UUID id) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id = ?", String.class, id);
    }

    private UUID deliveryIdFor(UUID subscriptionId) {
        return jdbc.queryForObject(
                "SELECT id FROM webhook_delivery WHERE subscription_id = ? AND is_replay = false",
                UUID.class, subscriptionId);
    }

    @Test
    void healthyDelivered_whileFailingRetriesToFailedAndAutoPauses_noHeadOfLineBlock() throws Exception {
        String secretHealthy = "whsec-healthy-abc";
        UUID subHealthy = insertSubscription("https://ok.example.com/hook", secretHealthy);
        UUID subFailing = insertSubscription("https://fail.example.com/hook", "whsec-failing-xyz");

        fanoutOrderReady();
        UUID deliveryHealthy = deliveryIdFor(subHealthy);
        UUID deliveryFailing = deliveryIdFor(subFailing);

        // Drive the worker until the failing delivery exhausts its attempts.
        for (int i = 0; i < 12 && !"FAILED".equals(statusOf("webhook_delivery", deliveryFailing)); i++) {
            worker.deliverDue();
            Thread.sleep(25);
        }

        assertThat(statusOf("webhook_delivery", deliveryHealthy))
                .as("healthy subscription delivered despite the failing one (no HOL block)")
                .isEqualTo("DELIVERED");
        assertThat(statusOf("webhook_delivery", deliveryFailing))
                .as("failing subscription exhausts bounded backoff -> FAILED")
                .isEqualTo("FAILED");
        assertThat(statusOf("webhook_subscription", subFailing))
                .as("a permanently-failing subscription auto-pauses")
                .isEqualTo("AUTO_PAUSED");
        assertThat(statusOf("webhook_subscription", subHealthy))
                .as("the healthy subscription is untouched")
                .isEqualTo("ACTIVE");

        // HMAC over the EXACT bytes POSTed verifies with the subscription's secret.
        MockWebClientConfig.Recorded healthy = exchange.recorded.stream()
                .filter(r -> r.url().contains("ok.example.com"))
                .findFirst().orElseThrow();
        long ts = Long.parseLong(healthy.signature().substring(2, healthy.signature().indexOf(',')));
        String recomputed = signer.sign(healthy.body().getBytes(StandardCharsets.UTF_8), secretHealthy, ts);
        assertThat(healthy.signature())
                .as("receiver recomputes HMAC over t + '.' + rawBody and matches X-JToye-Signature")
                .isEqualTo(recomputed);
        assertThat(healthy.eventType()).isEqualTo("order.ready");
        assertThat(healthy.eventId()).isNotBlank();
    }

    @Test
    @WithMockUser
    void replay_createsTaggedNewRow_leavingOriginalIntact() throws Exception {
        UUID subscription = insertSubscription("https://ok.example.com/hook", "whsec-replay");
        fanoutOrderReady();
        UUID original = deliveryIdFor(subscription);

        mockMvc.perform(post("/api/v1/webhooks/" + subscription + "/deliveries/" + original + "/replay")
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("Idempotency-Key", "replay-key-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replay").value(true))
                .andExpect(jsonPath("$.replayOf").value(original.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        Integer replayRows = jdbc.queryForObject(
                "SELECT count(*) FROM webhook_delivery WHERE is_replay = true AND replay_of = ?",
                Integer.class, original);
        assertThat(replayRows).as("exactly one tagged replay row is created").isEqualTo(1);

        // The replay reuses the original envelope id (X-JToye-Event-Id) so a
        // retry can never double-deliver at the receiver (Idempotency-Key safe).
        String origEventId = jdbc.queryForObject(
                "SELECT event_id::text FROM webhook_delivery WHERE id = ?", String.class, original);
        String replayEventId = jdbc.queryForObject(
                "SELECT event_id::text FROM webhook_delivery WHERE is_replay = true AND replay_of = ?",
                String.class, original);
        assertThat(replayEventId).isEqualTo(origEventId);

        assertThat(statusOf("webhook_delivery", original))
                .as("the original row's status history is untouched by the replay")
                .isEqualTo("PENDING");
    }

    @Test
    @WithMockUser
    void replay_sameIdempotencyKey_createsExactlyOneRow_differentKeyCreatesAnother() throws Exception {
        UUID subscription = insertSubscription("https://ok.example.com/hook", "whsec-idem");
        fanoutOrderReady();
        UUID original = deliveryIdFor(subscription);

        String base = "/api/v1/webhooks/" + subscription + "/deliveries/" + original + "/replay";
        String sameKey = "idem-replay-" + UUID.randomUUID();

        // First replay with key K.
        String firstBody = mockMvc.perform(post(base)
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("Idempotency-Key", sameKey))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstId = com.jayway.jsonpath.JsonPath.read(firstBody, "$.id");

        // Second replay with the SAME key — must NOT create a second row (WR-01).
        String secondBody = mockMvc.perform(post(base)
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("Idempotency-Key", sameKey))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String secondId = com.jayway.jsonpath.JsonPath.read(secondBody, "$.id");

        assertThat(secondId)
                .as("a same-key replay returns the ORIGINAL replay, not a fresh one")
                .isEqualTo(firstId);

        Integer replayRows = jdbc.queryForObject(
                "SELECT count(*) FROM webhook_delivery WHERE is_replay = true AND replay_of = ?",
                Integer.class, original);
        assertThat(replayRows)
                .as("same Idempotency-Key must produce exactly ONE replay row")
                .isEqualTo(1);

        // A DIFFERENT key genuinely creates a new replay row.
        mockMvc.perform(post(base)
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("Idempotency-Key", "idem-replay-" + UUID.randomUUID()))
                .andExpect(status().isCreated());

        Integer afterDifferentKey = jdbc.queryForObject(
                "SELECT count(*) FROM webhook_delivery WHERE is_replay = true AND replay_of = ?",
                Integer.class, original);
        assertThat(afterDifferentKey)
                .as("a different Idempotency-Key creates a new replay row")
                .isEqualTo(2);
    }

    @Test
    @WithMockUser
    void deliveryLog_listsRowsForSubscription() throws Exception {
        UUID subscription = insertSubscription("https://ok.example.com/hook", "whsec-log");
        fanoutOrderReady();

        mockMvc.perform(get("/api/v1/webhooks/" + subscription + "/deliveries")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].eventType").value("order.ready"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));
    }

    // --- Mock WebClient egress: capture body + headers, 500 for *fail* hosts ---

    @TestConfiguration
    static class MockWebClientConfig {

        record Recorded(String url, String body, String signature, String eventId, String eventType) {}

        static final class CapturingExchange implements ExchangeFunction {
            final List<Recorded> recorded = new CopyOnWriteArrayList<>();

            @Override
            public Mono<ClientResponse> exchange(ClientRequest request) {
                MockClientHttpRequest mock = new MockClientHttpRequest(request.method(), request.url());
                request.writeTo(mock, ExchangeStrategies.withDefaults()).block();
                String body = mock.getBodyAsString().block();
                recorded.add(new Recorded(
                        request.url().toString(),
                        body,
                        request.headers().getFirst(WebhookSigner.SIGNATURE_HEADER),
                        request.headers().getFirst("X-JToye-Event-Id"),
                        request.headers().getFirst("X-JToye-Event-Type")));
                boolean fail = request.url().getHost() != null && request.url().getHost().contains("fail");
                return Mono.just(ClientResponse.create(fail ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.OK).build());
            }
        }

        @Bean
        CapturingExchange capturingExchange() {
            return new CapturingExchange();
        }

        @Bean
        @Primary
        WebClient.Builder webhookMockWebClientBuilder(CapturingExchange exchange) {
            return WebClient.builder().exchangeFunction(exchange);
        }

        // The worker now consumes a dedicated WebClient bean (the SSRF-hardened
        // webhookDeliveryWebClient in prod). A DIFFERENT bean name + @Primary wins
        // the by-type injection without colliding with the prod bean's name (Spring
        // Boot disables same-name override). Intercepts egress — no real network.
        @Bean
        @Primary
        WebClient mockWebhookDeliveryWebClient(CapturingExchange exchange) {
            return WebClient.builder().exchangeFunction(exchange).build();
        }
    }
}
