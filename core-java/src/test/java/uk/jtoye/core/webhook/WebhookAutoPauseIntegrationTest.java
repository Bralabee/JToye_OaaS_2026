package uk.jtoye.core.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UXT-038 (RWO-038): an endpoint auto-pauses only when it is really failing.
 *
 * <p>The persona run saw an endpoint auto-paused about 46 seconds after two flaky events: the
 * worker counted every failed ATTEMPT across every delivery against one subscription-wide threshold
 * (10), so two events failing five times each tripped it although both would have been delivered on
 * the sixth try. This class drives the real {@link WebhookDeliveryWorker} over Postgres 15 with the
 * production attempt budget ({@code max-attempts} 8, not overridden) and the production auto-pause
 * default (not overridden), against a scripted receiver that fails a given event N times and then
 * answers 200. Only the backoff is shortened, so a run takes seconds rather than minutes.
 *
 * <p>The rule under test: a failure counts when a DELIVERY exhausts its attempts; a delivered event
 * resets the count; {@code webhook.delivery.auto-pause-after-failed-deliveries} (default 3)
 * consecutive exhausted deliveries pause the endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import({WebhookAutoPauseIntegrationTest.ScriptedReceiverConfig.class,
        NoScheduledTriggersTestConfig.class})
class WebhookAutoPauseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // Hermetic: skip DNS resolution; HTTPS is still enforced at egress.
        registry.add("webhook.delivery.backoff-base-ms", () -> "1");
        registry.add("webhook.delivery.backoff-cap-ms", () -> "5");
        // max-attempts and the auto-pause rule are deliberately NOT overridden: the production
        // defaults are what UXT-038 measured.
        registry.add("webhook.target.block-private-ranges", () -> "false");
        registry.add("webhook.delivery.interval-ms", () -> "3600000");
        registry.add("webhook.delivery.retention-interval-ms", () -> "3600000");
        // The test profile turns mail off; this class asserts what the owner is sent.
        registry.add("notification.email.enabled", () -> "true");
        registry.add("notification.email.from", () -> "noreply@platform.jtoye.test");
        registry.add("management.health.mail.enabled", () -> "false");
    }

    @MockitoBean JavaMailSender mailSender;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebhookDeliveryWorker worker;
    @Autowired WebhookFanoutListener fanoutListener;
    @Autowired ScriptedReceiverConfig.ScriptedReceiver receiver;

    UUID tenantId;
    UUID subscriptionId;

    static final String TARGET = "https://hooks.example.com/jtoye";
    static final Set<String> TERMINAL = Set.of("DELIVERED", "FAILED", "HELD");

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM webhook_delivery");
        jdbc.update("DELETE FROM webhook_subscription");
        receiver.reset();
        Mockito.reset(mailSender);
        Mockito.when(mailSender.createMimeMessage()).thenAnswer(inv -> uk.jtoye.core.testsupport.SentMail.newMimeMessage());

        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "autopause-" + tenantId);
        subscriptionId = UUID.randomUUID();
        jdbc.update("INSERT INTO webhook_subscription "
                        + "(id, tenant_id, target_url, event_types, signing_secret, status) "
                        + "VALUES (?, ?, ?, '{ORDER_STATE_CHANGED}'::text[], ?, 'ACTIVE')",
                subscriptionId, tenantId, TARGET, "autopause-signing-value");
    }

    // ---------------------------------------------------------------- helpers

    /** Fans out one order event and scripts the receiver to fail it {@code failures} times. */
    UUID event(int failures) {
        Set<UUID> before = Set.copyOf(eventIds());
        fanoutListener.onOrderState(new OrderStateChangeEvent(
                UUID.randomUUID(), tenantId, "ORD-" + UUID.randomUUID().toString().substring(0, 6),
                OrderStatus.PREPARING, OrderStatus.READY, OffsetDateTime.now()));
        UUID eventId = eventIds().stream().filter(id -> !before.contains(id)).findFirst()
                .orElseThrow(() -> new AssertionError("the fan-out wrote no delivery row"));
        receiver.script(eventId, failures);
        return eventId;
    }

    List<UUID> eventIds() {
        return jdbc.queryForList("SELECT event_id FROM webhook_delivery WHERE subscription_id = ?",
                UUID.class, subscriptionId);
    }

    String deliveryStatus(UUID eventId) {
        return jdbc.queryForObject("SELECT status FROM webhook_delivery WHERE event_id = ? AND is_replay = false",
                String.class, eventId);
    }

    String subscriptionStatus() {
        return jdbc.queryForObject("SELECT status FROM webhook_subscription WHERE id = ?", String.class,
                subscriptionId);
    }

    int consecutiveFailures() {
        return jdbc.queryForObject("SELECT consecutive_failures FROM webhook_subscription WHERE id = ?",
                Integer.class, subscriptionId);
    }

    /** Drives the worker until every named event's delivery is terminal (or 400 ticks). */
    void driveUntilTerminal(UUID... eventIds) throws InterruptedException {
        for (int tick = 0; tick < 400; tick++) {
            boolean done = true;
            for (UUID e : eventIds) {
                done &= TERMINAL.contains(deliveryStatus(e));
            }
            if (done) {
                return;
            }
            worker.deliverDue();
            Thread.sleep(8);
        }
        throw new AssertionError("deliveries did not reach a terminal status within 400 ticks");
    }

    static final int ALWAYS = Integer.MAX_VALUE;

    // ------------------------------------------------------------------ arms

    @Test
    @DisplayName("UXT-038: two events that each fail five times and then succeed never pause the endpoint")
    void twoFlakyEventsDoNotPauseTheEndpoint() throws Exception {
        UUID first = event(5);
        UUID second = event(5);

        driveUntilTerminal(first, second);

        assertThat(subscriptionStatus())
                .as("transient failures across two events are not a failing endpoint (pre-fix: AUTO_PAUSED "
                        + "at the 10th failed attempt, about 46 s into a real run)")
                .isEqualTo("ACTIVE");
        assertThat(deliveryStatus(first)).as("first event delivered on its 6th attempt").isEqualTo("DELIVERED");
        assertThat(deliveryStatus(second)).as("second event delivered on its 6th attempt").isEqualTo("DELIVERED");
        assertThat(receiver.attempts(first)).isEqualTo(6);
        assertThat(receiver.attempts(second)).isEqualTo(6);
        assertThat(consecutiveFailures()).as("no delivery exhausted its attempts").isZero();
    }

    @Test
    @DisplayName("Three consecutive deliveries that exhaust their attempts pause the endpoint; two do not")
    void threeExhaustedDeliveriesInARowPauseTheEndpoint() throws Exception {
        UUID first = event(ALWAYS);
        driveUntilTerminal(first);
        assertThat(deliveryStatus(first)).isEqualTo("FAILED");
        assertThat(receiver.attempts(first)).as("the production budget of 8 attempts").isEqualTo(8);
        assertThat(subscriptionStatus()).as("one exhausted delivery does not pause").isEqualTo("ACTIVE");
        assertThat(consecutiveFailures()).isEqualTo(1);

        UUID second = event(ALWAYS);
        driveUntilTerminal(second);
        assertThat(subscriptionStatus()).as("two exhausted deliveries do not pause").isEqualTo("ACTIVE");
        assertThat(deliveryStatus(second)).isEqualTo("FAILED");
        assertThat(receiver.attempts(second)).isEqualTo(8);
        assertThat(consecutiveFailures()).isEqualTo(2);

        UUID third = event(ALWAYS);
        driveUntilTerminal(third);
        assertThat(deliveryStatus(third)).isEqualTo("FAILED");
        assertThat(receiver.attempts(third)).isEqualTo(8);
        assertThat(subscriptionStatus()).as("the third exhausted delivery in a row pauses")
                .isEqualTo("AUTO_PAUSED");
        assertThat(consecutiveFailures()).isEqualTo(3);
    }

    @Test
    @DisplayName("A delivered event between exhausted deliveries resets the consecutive count")
    void aDeliveredEventResetsTheCount() throws Exception {
        UUID e1 = event(ALWAYS);
        driveUntilTerminal(e1);
        UUID e2 = event(ALWAYS);
        driveUntilTerminal(e2);
        assertThat(consecutiveFailures()).isEqualTo(2);
        assertThat(subscriptionStatus()).isEqualTo("ACTIVE");

        UUID ok = event(0);
        driveUntilTerminal(ok);
        assertThat(deliveryStatus(ok)).isEqualTo("DELIVERED");
        assertThat(consecutiveFailures()).as("a delivered event resets the count").isZero();

        UUID e4 = event(ALWAYS);
        driveUntilTerminal(e4);
        UUID e5 = event(ALWAYS);
        driveUntilTerminal(e5);

        assertThat(subscriptionStatus())
                .as("four exhausted deliveries, but never three in a row")
                .isEqualTo("ACTIVE");
        assertThat(consecutiveFailures()).isEqualTo(2);
        assertThat(List.of(deliveryStatus(e1), deliveryStatus(e2), deliveryStatus(e4), deliveryStatus(e5)))
                .containsOnly("FAILED");
    }

    // ------------------------------------------------------ scripted receiver

    @TestConfiguration
    static class ScriptedReceiverConfig {

        /** Fails each scripted event N times, then answers 200; counts attempts per event id. */
        static final class ScriptedReceiver implements ExchangeFunction {
            private final Map<UUID, Integer> failuresBeforeSuccess = new ConcurrentHashMap<>();
            private final Map<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();

            void reset() {
                failuresBeforeSuccess.clear();
                attempts.clear();
            }

            void script(UUID eventId, int failures) {
                failuresBeforeSuccess.put(eventId, failures);
            }

            /** Every scripted event from now on succeeds on its next attempt. */
            void healAll() {
                failuresBeforeSuccess.replaceAll((id, n) -> 0);
            }

            int attempts(UUID eventId) {
                AtomicInteger n = attempts.get(eventId);
                return n == null ? 0 : n.get();
            }

            @Override
            public Mono<ClientResponse> exchange(ClientRequest request) {
                UUID eventId = UUID.fromString(request.headers().getFirst("X-JToye-Event-Id"));
                int n = attempts.computeIfAbsent(eventId, k -> new AtomicInteger()).incrementAndGet();
                int failures = failuresBeforeSuccess.getOrDefault(eventId, ALWAYS);
                boolean fail = failures == ALWAYS || n <= failures;
                return Mono.just(ClientResponse.create(
                        fail ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK).build());
            }
        }

        @Bean
        ScriptedReceiver scriptedReceiver() {
            return new ScriptedReceiver();
        }

        @Bean
        @Primary
        WebClient scriptedWebhookDeliveryWebClient(ScriptedReceiver receiver) {
            return WebClient.builder().exchangeFunction(receiver).build();
        }
    }
}
