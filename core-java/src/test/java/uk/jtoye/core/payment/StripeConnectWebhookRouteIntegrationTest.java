package uk.jtoye.core.payment;

import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * PAY-03 (plan 30-12): Stripe has exactly two event scopes, "Your account" and "Connected
 * accounts", and each endpoint registered for a scope has its own signing secret
 * (docs.stripe.com/connect/webhooks). Express {@code account.updated} and every direct-charge
 * {@code payment_intent.*} / {@code refund.*} event is delivered only to a Connected-accounts
 * endpoint, so the app needs a second route that verifies with that endpoint's secret.
 *
 * <p>The invariant this class encodes: every scope has exactly one route and one secret, and a
 * route verifying with another scope's secret, or receiving the other scope's event, refuses it
 * and leaves no trace. Every payload is signed for real with
 * {@link Webhook.Util#computeHmacSha256(String, String)} over {@code "<t>.<payload>"} — nothing is
 * mocked between the HTTP request and {@code Webhook.constructEvent}, so a route that verified
 * against the wrong secret could not pass the cross-secret arms.
 *
 * <p>Every refusal arm counts {@code processed_stripe_events} rows by its own event id and expects
 * 0; the accepted arms find their row with the same query, so the count is shown able to see a row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class StripeConnectWebhookRouteIntegrationTest {

    private static final String PLATFORM_SECRET = "whsec_test_platform_route";
    private static final String CONNECT_SECRET = "whsec_test_connect_route";
    private static final String CONNECT_ROUTE = "/api/v1/public/payments/webhook/connect";
    private static final String PLATFORM_ROUTE = "/api/v1/public/payments/webhook";
    /** The API version stripe-java 34.0.0 deserialises; an event in any other version yields no object. */
    private static final String API_VERSION = "2026-09-30.endive";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("stripe.webhook-secret", () -> PLATFORM_SECRET);
        registry.add("stripe.connect-webhook-secret", () -> CONNECT_SECRET);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StripeProperties stripeProperties;

    @MockitoBean private OrderEventPublisher orderEventPublisher;
    @MockitoBean private PaymentEventPublisher paymentEventPublisher;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM processed_stripe_events");
    }

    // ------------------------------------------------------------------
    // Connect route
    // ------------------------------------------------------------------

    @Test
    @DisplayName("connect route: an account.updated signed with the Connect secret and carrying account is accepted and recorded once")
    void connectRoute_connectSignedWithAccount_isAcceptedAndRecorded() throws Exception {
        String eventId = "evt_connect_ok_" + shortId();
        String payload = accountUpdatedEvent(eventId, "acct_connect_route_unknown", "acct_connect_route_unknown");

        int status = deliver(CONNECT_ROUTE, payload, sign(payload, CONNECT_SECRET));

        assertThat(status).as("Connect-signed Connect event on the Connect route").isEqualTo(200);
        assertThat(rows(eventId)).as("processed_stripe_events rows for " + eventId).isEqualTo(1L);
    }

    @Test
    @DisplayName("connect route: the same payload signed with the PLATFORM secret is refused 400 with no row")
    void connectRoute_platformSigned_isRefused() throws Exception {
        String eventId = "evt_connect_xsecret_" + shortId();
        String payload = accountUpdatedEvent(eventId, "acct_connect_route_unknown", "acct_connect_route_unknown");

        int status = deliver(CONNECT_ROUTE, payload, sign(payload, PLATFORM_SECRET));

        assertThat(status).as("platform-signed event on the Connect route").isEqualTo(400);
        assertThat(rows(eventId)).isZero();
    }

    @Test
    @DisplayName("connect route: an unsigned request is refused 400 with no row")
    void connectRoute_unsigned_isRefused() throws Exception {
        String eventId = "evt_connect_unsigned_" + shortId();
        String payload = accountUpdatedEvent(eventId, "acct_connect_route_unknown", "acct_connect_route_unknown");

        int status = deliver(CONNECT_ROUTE, payload, null);

        assertThat(status).as("unsigned request on the Connect route").isEqualTo(400);
        assertThat(rows(eventId)).isZero();
    }

    @Test
    @DisplayName("connect route: a forged signature (right shape, wrong key) is refused 400 with no row")
    void connectRoute_forged_isRefused() throws Exception {
        String eventId = "evt_connect_forged_" + shortId();
        String payload = accountUpdatedEvent(eventId, "acct_connect_route_unknown", "acct_connect_route_unknown");

        int status = deliver(CONNECT_ROUTE, payload, sign(payload, "whsec_attacker_guess"));

        assertThat(status).as("forged signature on the Connect route").isEqualTo(400);
        assertThat(rows(eventId)).isZero();
    }

    @Test
    @DisplayName("connect route: a Connect-signed event with no account is refused 400 with no row")
    void connectRoute_connectSignedWithoutAccount_isRefused() throws Exception {
        String eventId = "evt_connect_noacct_" + shortId();
        String payload = accountUpdatedEvent(eventId, null, "acct_connect_route_unknown");

        int status = deliver(CONNECT_ROUTE, payload, sign(payload, CONNECT_SECRET));

        assertThat(status).as("a platform-scope event (no account) on the Connect route").isEqualTo(400);
        assertThat(rows(eventId)).isZero();
    }

    @Test
    @DisplayName("connect route: with the Connect secret blank a correctly signed Connect event is refused 400 (fail closed)")
    void connectRoute_blankSecret_failsClosed() throws Exception {
        String eventId = "evt_connect_blank_" + shortId();
        String payload = accountUpdatedEvent(eventId, "acct_connect_route_unknown", "acct_connect_route_unknown");
        // Correctly shaped and signed with the configured secret; then the secret is withdrawn.
        // (A signature over an EMPTY key cannot be built at all: the JCE refuses an empty HMAC key.)
        String signature = sign(payload, CONNECT_SECRET);

        rebindConnectSecret("");
        try {
            int status = deliver(CONNECT_ROUTE, payload, signature);

            assertThat(status).as("Connect route with stripe.connect-webhook-secret blank").isEqualTo(400);
            assertThat(rows(eventId)).isZero();
        } finally {
            rebindConnectSecret(CONNECT_SECRET);
        }
    }

    @Test
    @DisplayName("connect route: a Connect account.updated reaches the existing handler (a known tenant's account becomes ENABLED)")
    void connectRoute_accountUpdated_reachesTheHandler() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String accountId = "acct_route_known_" + shortId();
        jdbc.update("INSERT INTO tenants (id, name, created_at, stripe_account_id, stripe_connect_status) "
                + "VALUES (?, ?, now(), ?, 'PENDING')", tenantId, "Connect route " + tenantId, accountId);
        String eventId = "evt_connect_enable_" + shortId();
        String payload = accountUpdatedEvent(eventId, accountId, accountId, true);

        int status = deliver(CONNECT_ROUTE, payload, sign(payload, CONNECT_SECRET));

        assertThat(status).isEqualTo(200);
        assertThat(rows(eventId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT stripe_connect_status FROM tenants WHERE id = ?", String.class, tenantId))
                .as("charges_enabled=true on the Connect route must reach StripeConnectService.handleAccountUpdated")
                .isEqualTo("ENABLED");
    }

    // ------------------------------------------------------------------
    // Platform route
    // ------------------------------------------------------------------

    @Test
    @DisplayName("platform route: a platform-signed event that carries account is refused 400 with no row")
    void platformRoute_platformSignedWithAccount_isRefused() throws Exception {
        String eventId = "evt_platform_acct_" + shortId();
        String payload = accountUpdatedEvent(eventId, "acct_connect_route_unknown", "acct_connect_route_unknown");

        int status = deliver(PLATFORM_ROUTE, payload, sign(payload, PLATFORM_SECRET));

        assertThat(status).as("a Connect-scope event (account set) on the platform route").isEqualTo(400);
        assertThat(rows(eventId)).isZero();
    }

    @Test
    @DisplayName("platform route: a platform-signed event without account is accepted and recorded once (unchanged path)")
    void platformRoute_platformSignedWithoutAccount_isAccepted() throws Exception {
        String eventId = "evt_platform_ok_" + shortId();
        String payload = accountUpdatedEvent(eventId, null, "acct_platform_route_unknown");

        int status = deliver(PLATFORM_ROUTE, payload, sign(payload, PLATFORM_SECRET));

        assertThat(status).isEqualTo(200);
        assertThat(rows(eventId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("platform route: an event signed with the CONNECT secret is refused 400 with no row")
    void platformRoute_connectSigned_isRefused() throws Exception {
        String eventId = "evt_platform_xsecret_" + shortId();
        String payload = accountUpdatedEvent(eventId, null, "acct_platform_route_unknown");

        int status = deliver(PLATFORM_ROUTE, payload, sign(payload, CONNECT_SECRET));

        assertThat(status).isEqualTo(400);
        assertThat(rows(eventId)).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private int deliver(String route, String payload, String signature) throws Exception {
        MockHttpServletRequestBuilder request = post(route)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload);
        if (signature != null) {
            request.header("Stripe-Signature", signature);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        return result.getResponse().getStatus();
    }

    private long rows(String eventId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_stripe_events WHERE event_id = ?", Long.class, eventId);
        return n == null ? -1L : n;
    }

    /** A real Stripe-Signature header: {@code t=<unix>,v1=<hex HMAC-SHA256 of "<t>.<payload>">}. */
    private static String sign(String payload, String secret) throws Exception {
        long t = Webhook.Util.getTimeNow();
        String v1 = Webhook.Util.computeHmacSha256(secret, t + "." + payload);
        return "t=" + t + ",v1=" + v1;
    }

    private static String accountUpdatedEvent(String eventId, String eventAccount, String objectAccountId) {
        return accountUpdatedEvent(eventId, eventAccount, objectAccountId, false);
    }

    private static String accountUpdatedEvent(String eventId, String eventAccount, String objectAccountId,
                                              boolean chargesEnabled) {
        String accountField = eventAccount == null ? "" : "\"account\":\"" + eventAccount + "\",";
        return "{"
                + "\"id\":\"" + eventId + "\","
                + "\"object\":\"event\","
                + accountField
                + "\"api_version\":\"" + API_VERSION + "\","
                + "\"created\":" + Webhook.Util.getTimeNow() + ","
                + "\"livemode\":false,"
                + "\"pending_webhooks\":1,"
                + "\"request\":{\"id\":null,\"idempotency_key\":null},"
                + "\"type\":\"account.updated\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"" + objectAccountId + "\","
                + "\"object\":\"account\","
                + "\"charges_enabled\":" + chargesEnabled + ","
                + "\"metadata\":{}"
                + "}}"
                + "}";
    }

    /**
     * Rebinds {@code stripe.connect-webhook-secret} on the live properties bean through Spring's own
     * Binder, the way the context bound it at startup, so the fail-closed arm exercises the real
     * configuration path rather than a test-only setter.
     */
    private void rebindConnectSecret(String value) {
        new Binder(new MapConfigurationPropertySource(Map.of("stripe.connect-webhook-secret", value)))
                .bind("stripe", Bindable.ofInstance(stripeProperties));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
