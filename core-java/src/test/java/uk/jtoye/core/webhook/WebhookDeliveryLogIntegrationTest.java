package uk.jtoye.core.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #444 (QA council F-H4-WHDELIV) — {@code GET
 * /api/v1/webhooks/{id}/deliveries} returned {@code totalElements: 0} under every
 * filter while the rows provably existed, and replay 404'd on rows the vendor
 * could see in the database. The visible half of Phase 22 had never worked.
 *
 * <p><b>Why this test has to be shaped the way it is.</b> The endpoint's failure
 * mode was RLS returning zero rows because no tenant GUC was pinned — and "RLS
 * returns zero rows" is indistinguishable from "the fixture never seeded
 * anything". A test that asserted only on the endpoint would therefore have
 * passed on the broken tree the moment its seed silently failed. So
 * {@link #instrumentSeesTheSeededRows_beforeAnyEndpointAssertion} runs FIRST and
 * proves, through a connection that is subject to the same policy the endpoint
 * is, that the rows are physically present and visible when — and only when — a
 * tenant GUC is pinned. Only then does anything assert on the HTTP surface.
 *
 * <p><b>RLS must actually be enforced.</b> The Testcontainers bootstrap role is a
 * Postgres SUPERUSER, which bypasses even FORCE ROW LEVEL SECURITY; against a
 * superuser this whole class would pass on the unfixed code and prove nothing.
 * Seeding therefore ends with {@code ALTER ROLE ... NOSUPERUSER} (the
 * {@code PublicOrdersPaginationIntegrationTest} house pattern), so every HTTP
 * request below runs under genuinely enforced RLS — the production posture.
 *
 * <p><b>The cross-tenant row is deliberately mis-filed.</b> One tenant-B delivery
 * carries tenant A's {@code subscription_id} (the table has no FK on that
 * column). It is therefore inside the endpoint's {@code subscription_id}
 * predicate and can only be excluded by the tenant boundary itself — which makes
 * the isolation assertion a genuine RLS proof rather than a restatement of the
 * WHERE clause.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class WebhookDeliveryLogIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // Hermetic: no DNS resolution for the subscription target URL.
        registry.add("webhook.target.block-private-ranges", () -> "false");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-00000000d4a1");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-00000000d4b1");

    /** Tenant A's read-only log subscription — 3 deliveries, never mutated. */
    private static final UUID SUB_A = UUID.fromString("00000000-0000-0000-0000-0000000005a1");
    /** Tenant B's own subscription — 1 delivery. */
    private static final UUID SUB_B = UUID.fromString("00000000-0000-0000-0000-0000000005b1");
    /** Tenant A, replay without an Idempotency-Key. */
    private static final UUID SUB_C = UUID.fromString("00000000-0000-0000-0000-0000000005c1");
    /** Tenant A, replay with an Idempotency-Key. */
    private static final UUID SUB_D = UUID.fromString("00000000-0000-0000-0000-0000000005d1");
    /** Tenant A, un-keyed replay in isolation (no preceding log read). */
    private static final UUID SUB_E = UUID.fromString("00000000-0000-0000-0000-0000000005e1");

    private static final UUID DEL_DELIVERED = UUID.fromString("00000000-0000-0000-0000-00000000de11");
    private static final UUID DEL_RETRYING = UUID.fromString("00000000-0000-0000-0000-00000000de22");
    private static final UUID DEL_FAILED = UUID.fromString("00000000-0000-0000-0000-00000000de33");
    /** Tenant B's row, mis-filed under tenant A's subscription id. */
    private static final UUID DEL_FOREIGN = UUID.fromString("00000000-0000-0000-0000-00000000de44");
    private static final UUID DEL_B_OWN = UUID.fromString("00000000-0000-0000-0000-00000000de55");
    private static final UUID DEL_REPLAY_SRC = UUID.fromString("00000000-0000-0000-0000-00000000de66");
    private static final UUID DEL_REPLAY_KEYED_SRC = UUID.fromString("00000000-0000-0000-0000-00000000de77");
    private static final UUID DEL_REPLAY_UNKEYED_SRC = UUID.fromString("00000000-0000-0000-0000-00000000de88");

    private static boolean seeded = false;

    @BeforeEach
    void seedOnce() {
        if (seeded) {
            return;
        }
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT_A, "wh-log-tenant-a");
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT_B, "wh-log-tenant-b");

        insertSubscription(SUB_A, TENANT_A);
        insertSubscription(SUB_B, TENANT_B);
        insertSubscription(SUB_C, TENANT_A);
        insertSubscription(SUB_D, TENANT_A);
        insertSubscription(SUB_E, TENANT_A);

        // Tenant A's log: exactly the shape the council observed in production —
        // a delivered row, a retrying row at attempt 6 with a real remote 503,
        // and an exhausted row.
        insertDelivery(DEL_DELIVERED, TENANT_A, SUB_A, "order.state.changed", "DELIVERED", 1, 200, null);
        insertDelivery(DEL_RETRYING, TENANT_A, SUB_A, "order.state.changed", "RETRYING", 6, 503, "503 Service Unavailable");
        insertDelivery(DEL_FAILED, TENANT_A, SUB_A, "order.refunded", "FAILED", 8, 405, "405 Method Not Allowed");

        // Tenant B's row filed under tenant A's subscription: inside the endpoint's
        // subscription_id predicate, excluded only by the tenant boundary.
        insertDelivery(DEL_FOREIGN, TENANT_B, SUB_A, "order.state.changed", "DELIVERED", 1, 200, null);
        insertDelivery(DEL_B_OWN, TENANT_B, SUB_B, "order.state.changed", "DELIVERED", 1, 200, null);

        insertDelivery(DEL_REPLAY_SRC, TENANT_A, SUB_C, "order.state.changed", "FAILED", 8, 500, "500 Internal Server Error");
        insertDelivery(DEL_REPLAY_KEYED_SRC, TENANT_A, SUB_D, "order.state.changed", "FAILED", 8, 500, "500 Internal Server Error");
        insertDelivery(DEL_REPLAY_UNKEYED_SRC, TENANT_A, SUB_E, "order.state.changed", "FAILED", 8, 500, "500 Internal Server Error");

        // Seeding ran as the Testcontainers SUPERUSER (bypasses FORCE RLS).
        // Downgrade so every request below faces genuinely enforced RLS.
        jdbc.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
        seeded = true;
    }

    private void insertSubscription(UUID id, UUID tenantId) {
        jdbc.update("INSERT INTO webhook_subscription "
                        + "(id, tenant_id, target_url, event_types, signing_secret, status) "
                        + "VALUES (?, ?, 'https://example.com/hook', ARRAY['ORDER_STATE_CHANGED','ORDER_REFUNDED'], ?, 'ACTIVE')",
                id, tenantId, "secret-" + id);
    }

    private void insertDelivery(UUID id, UUID tenantId, UUID subscriptionId, String eventType,
                                String status, int attemptCount, Integer lastHttpStatus, String lastError) {
        jdbc.update("INSERT INTO webhook_delivery "
                        + "(id, tenant_id, subscription_id, event_id, event_type, payload, status, "
                        + " attempt_count, last_http_status, last_error) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, subscriptionId, UUID.randomUUID(), eventType,
                "{\"id\":\"" + id + "\"}", status, attemptCount, lastHttpStatus, lastError);
    }

    /**
     * Count {@code webhook_delivery} rows for one subscription on a fresh
     * connection under the (now NOSUPERUSER) application role, with
     * {@code app.current_tenant_id} pinned to {@code gucTenant} — or left unset
     * when {@code gucTenant} is {@code null}, which is exactly the state the
     * broken endpoint's connection was in.
     */
    private int countVisibleDeliveries(UUID gucTenant, UUID subscriptionId) throws Exception {
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            if (gucTenant != null) {
                try (PreparedStatement ps =
                             c.prepareStatement("SELECT set_config('app.current_tenant_id', ?, false)")) {
                    ps.setString(1, gucTenant.toString());
                    ps.execute();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM webhook_delivery WHERE subscription_id = ?")) {
                ps.setObject(1, subscriptionId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }
    }

    /**
     * AC #3 — the instrument is proven able to SEE the rows before anything
     * asserts that the endpoint can. Three arms on one table through one role:
     * tenant A's GUC sees A's three rows, tenant B's GUC sees only its own
     * mis-filed one, and an UNPINNED connection sees nothing at all. The third
     * arm is the bug reproduced at the SQL layer, and it is what makes the
     * endpoint assertions below falsifiable rather than vacuous.
     */
    @Test
    @DisplayName("instrument check: the seeded rows are visible under a pinned GUC and invisible without one")
    void instrumentSeesTheSeededRows_beforeAnyEndpointAssertion() throws Exception {
        assertThat(countVisibleDeliveries(TENANT_A, SUB_A))
                .as("tenant A's three delivery rows are physically present and readable under A's GUC")
                .isEqualTo(3);
        assertThat(countVisibleDeliveries(TENANT_B, SUB_A))
                .as("tenant B sees only its own mis-filed row on the same subscription id")
                .isEqualTo(1);
        assertThat(countVisibleDeliveries(null, SUB_A))
                .as("with NO tenant GUC pinned the very same query returns nothing — the defect, at SQL level")
                .isZero();
    }

    /** AC #1 — the log returns the rows that provably exist for the caller's tenant. */
    @Test
    @WithMockUser
    @DisplayName("delivery log returns the rows that exist for the tenant")
    void deliveryLog_returnsTheRowsThatExist() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/webhooks/" + SUB_A + "/deliveries")
                        .header("X-Tenant-Id", TENANT_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains(DEL_DELIVERED.toString())
                .contains(DEL_RETRYING.toString())
                .contains(DEL_FAILED.toString());
        // The council's exact evidence: a retrying row at attempt 6 with a real
        // remote status. If the endpoint returned rows but dropped these fields,
        // the vendor still could not debug.
        assertThat(body).contains("\"attemptCount\":6").contains("\"lastHttpStatus\":503");
    }

    /** AC #1 — the status filter narrows the same non-empty set (it was 0 under every filter). */
    @Test
    @WithMockUser
    @DisplayName("delivery log status filter returns the matching subset, not an empty page")
    void deliveryLog_statusFilter_returnsMatchingSubset() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/" + SUB_A + "/deliveries")
                        .header("X-Tenant-Id", TENANT_A.toString())
                        .param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(DEL_FAILED.toString()));
    }

    /**
     * AC #4 — cross-tenant isolation, proven with a real second tenant. Tenant B's
     * row sits under tenant A's subscription id, so the subscription predicate
     * alone cannot exclude it; only the tenant boundary can.
     */
    @Test
    @WithMockUser
    @DisplayName("another tenant's delivery under the same subscription id is never returned")
    void deliveryLog_doesNotLeakAnotherTenantsRow() throws Exception {
        MvcResult asA = mockMvc.perform(get("/api/v1/webhooks/" + SUB_A + "/deliveries")
                        .header("X-Tenant-Id", TENANT_A.toString()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(asA.getResponse().getContentAsString())
                .as("tenant B's mis-filed row must not appear in tenant A's log")
                .doesNotContain(DEL_FOREIGN.toString());

        MvcResult asB = mockMvc.perform(get("/api/v1/webhooks/" + SUB_B + "/deliveries")
                        .header("X-Tenant-Id", TENANT_B.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertThat(asB.getResponse().getContentAsString())
                .as("tenant B's own log must not carry tenant A's rows")
                .contains(DEL_B_OWN.toString())
                .doesNotContain(DEL_DELIVERED.toString())
                .doesNotContain(DEL_RETRYING.toString())
                .doesNotContain(DEL_FAILED.toString());
    }

    /** AC #4 — a foreign subscription id is 404, not a readable log. */
    @Test
    @WithMockUser
    @DisplayName("a tenant cannot open another tenant's subscription log")
    void deliveryLog_foreignSubscription_is404() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/" + SUB_A + "/deliveries")
                        .header("X-Tenant-Id", TENANT_B.toString()))
                .andExpect(status().isNotFound());
    }

    /**
     * The defect's real teacher: an unpinned read returning zero rows is
     * indistinguishable from a genuine empty log, which is why this survived a
     * milestone. With no tenant established the endpoint must now FAIL LOUDLY
     * (RFC 7807, {@code missing-tenant-context}) rather than answer with an
     * empty page.
     */
    @Test
    @WithMockUser
    @DisplayName("with no tenant established the log errors loudly instead of returning an empty page")
    void deliveryLog_withNoTenant_failsLoudly() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/webhooks/" + SUB_A + "/deliveries"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/missing-tenant-context"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .as("the caller must not be handed a page of zero deliveries")
                .doesNotContain("totalElements");
    }

    /** AC #2 — replay works on a delivery the log returned (no Idempotency-Key). */
    @Test
    @WithMockUser
    @DisplayName("replay succeeds on a delivery returned by the log")
    void replay_worksOnADeliveryReturnedByTheLog() throws Exception {
        // The log returns it...
        mockMvc.perform(get("/api/v1/webhooks/" + SUB_C + "/deliveries")
                        .header("X-Tenant-Id", TENANT_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(DEL_REPLAY_SRC.toString()));

        // ...and replaying that same id creates a new PENDING attempt.
        mockMvc.perform(post("/api/v1/webhooks/" + SUB_C + "/deliveries/" + DEL_REPLAY_SRC + "/replay")
                        .header("X-Tenant-Id", TENANT_A.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.replay").value(true))
                .andExpect(jsonPath("$.replayOf").value(DEL_REPLAY_SRC.toString()));

        assertThat(countVisibleDeliveries(TENANT_A, SUB_C))
                .as("the replay row is durably persisted for tenant A")
                .isEqualTo(2);
    }

    /**
     * AC #2, isolated — the un-keyed replay POST on its own, with no preceding
     * log read. This is the arm that separates the two halves of the filed root
     * cause: the KEYED replay path was already correct before the fix (
     * {@code IdempotencyService.execute} is {@code @Transactional} and pins the
     * tenant GUC explicitly), while the un-keyed path ran with no transaction at
     * all and 404'd on a row that provably existed. Keep both arms — a single
     * "replay works" test would have hidden which half was broken.
     */
    @Test
    @WithMockUser
    @DisplayName("un-keyed replay does not 404 on a delivery that exists")
    void replay_withoutIdempotencyKey_doesNot404() throws Exception {
        assertThat(countVisibleDeliveries(TENANT_A, SUB_E))
                .as("the source delivery provably exists before the replay is attempted")
                .isEqualTo(1);

        mockMvc.perform(post("/api/v1/webhooks/" + SUB_E + "/deliveries/" + DEL_REPLAY_UNKEYED_SRC + "/replay")
                        .header("X-Tenant-Id", TENANT_A.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayOf").value(DEL_REPLAY_UNKEYED_SRC.toString()));
    }

    /** AC #2 — the Idempotency-Key path replays once and is safe to retry. */
    @Test
    @WithMockUser
    @DisplayName("keyed replay is idempotent and creates exactly one new attempt")
    void replay_withIdempotencyKey_createsExactlyOneAttempt() throws Exception {
        String key = "replay-444-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/webhooks/" + SUB_D + "/deliveries/" + DEL_REPLAY_KEYED_SRC + "/replay")
                        .header("X-Tenant-Id", TENANT_A.toString())
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayOf").value(DEL_REPLAY_KEYED_SRC.toString()));

        mockMvc.perform(post("/api/v1/webhooks/" + SUB_D + "/deliveries/" + DEL_REPLAY_KEYED_SRC + "/replay")
                        .header("X-Tenant-Id", TENANT_A.toString())
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayOf").value(DEL_REPLAY_KEYED_SRC.toString()));

        assertThat(countVisibleDeliveries(TENANT_A, SUB_D))
                .as("two identical keyed replays create exactly ONE new attempt")
                .isEqualTo(2);
    }
}
