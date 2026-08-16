package uk.jtoye.core.gdpr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Closes the gap 31-05 handed forward, and proves the gate it creates actually refuses.
 *
 * <h2>Why this class exists at all</h2>
 *
 * 31-05 shipped the intake in a deliberately INERT state: every row is created
 * {@code PENDING_VERIFICATION} and nothing in that plan could move one to {@code VERIFIED}. Its
 * SUMMARY records the alternative it rejected — defaulting to {@code VERIFIED} — because that would
 * arm an <em>unverified</em> erasure request, which is threat T-31-05-02 itself: a destructive
 * action anybody on the internet can aim at anybody else. An inert queue is safe; an armed one is a
 * weapon. So the queue could only be drained once something could prove control of the address.
 *
 * <p>This class asserts BOTH halves of that gate, and the first is the one that matters:
 * {@link #theFanoutRefusesARequestThatWasNeverVerified()} lodges a request, does NOT verify it, runs
 * the fan-out worker over a tenant that genuinely holds the subject, and asserts nothing was erased.
 * Its non-vacuity control is {@link #theFanoutActionsTheSameRequestOnceItIsVerified()} — the
 * identical fixture, verified, which DOES erase. Without that pair, "nothing was erased" would be
 * satisfied by a worker that never works at all.
 *
 * <h2>The token is a bearer credential, so it is never stored readable</h2>
 *
 * The intake stores only {@code verification_token_sha256}. The readable token exists exactly twice:
 * in memory during the lodge, and in the mailbox of the address that was named. That is the whole
 * mechanism — control of the mailbox IS the proof — and
 * {@link #theReadableTokenIsDeliveredToTheSubjectAndNeverPersisted()} asserts the readable value
 * appears nowhere in the row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
// The fan-out is @Scheduled; this class drives it by hand and must own the whole timeline.
// A fixedDelay task fires once at context refresh regardless of its interval (#418).
@Import(NoScheduledTriggersTestConfig.class)
// DELIBERATELY NOT @AsSystemHarness. This class asserts a REFUSAL (an unverified request is not
// actioned) and drives the worker, which declares system authority for itself. A class-wide
// declaration would put the whole test inside a system scope and make the worker's own declaration
// unfalsifiable — the exact "a test that cannot fail" shape the project refuses to count.
class DsarVerificationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String INTAKE_PATH = "/api/v1/public/gdpr/dsar";
    private static final String VERIFY_PATH = "/api/v1/public/gdpr/dsar/verify";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DsarFanoutWorker worker;
    @Autowired private PlatformTransactionManager txManager;

    /** Spied to capture the READABLE token, which is never persisted and so has no other source. */
    @SpyBean private DsarVerificationMailer mailer;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dsar_request");
    }

    // ---- The gate: an unverified request is not actionable -------------------------------------

    @Test
    void theFanoutRefusesARequestThatWasNeverVerified() throws Exception {
        UUID tenant = seedTenant();
        String email = "unverified-" + UUID.randomUUID() + "@example.com";
        seedCustomer(tenant, email);

        lodge(email, "ERASURE", "203.0.113.11");

        // Precondition, asserted rather than assumed: the row is really in the pre-armed state.
        assertThat(statusOf(email)).isEqualTo("PENDING_VERIFICATION");

        worker.executeLodgedRequests();

        assertThat(erasureRecordCount(tenant))
                .as("an UNVERIFIED erasure request must not be actioned — that is T-31-05-02")
                .isZero();
        assertThat(customerEmailStillPresent(tenant, email))
                .as("the subject's customer row must be untouched by an unverified request")
                .isTrue();
        assertThat(statusOf(email)).isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    void theFanoutActionsTheSameRequestOnceItIsVerified() throws Exception {
        // NON-VACUITY CONTROL for the test above: identical fixture, one difference (verified).
        // Without this, "nothing was erased" is also satisfied by a worker that never works.
        UUID tenant = seedTenant();
        String email = "verified-" + UUID.randomUUID() + "@example.com";
        seedCustomer(tenant, email);

        String token = lodgeAndCaptureToken(email, "ERASURE", "203.0.113.12");
        verifyToken(token).andExpect(status().isOk());

        assertThat(statusOf(email)).isEqualTo("VERIFIED");

        worker.executeLodgedRequests();

        assertThat(erasureRecordCount(tenant)).isEqualTo(1);
        assertThat(customerEmailStillPresent(tenant, email)).isFalse();
        assertThat(statusOf(email)).isEqualTo("COMPLETED");
    }

    // ---- Token handling ------------------------------------------------------------------------

    @Test
    void theReadableTokenIsDeliveredToTheSubjectAndNeverPersisted() throws Exception {
        String email = "token-secrecy-" + UUID.randomUUID() + "@example.com";
        String token = lodgeAndCaptureToken(email, "ACCESS", "203.0.113.13");

        assertThat(token).as("a token must actually have been minted and delivered").isNotBlank();

        // The whole row, every text column, compared against the readable token. A digest column
        // holding the readable value would be a bearer credential at rest.
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM dsar_request");
        assertThat(rows).hasSize(1);
        rows.get(0).forEach((column, value) -> {
            if (value instanceof String s) {
                assertThat(s)
                        .as("column dsar_request.%s holds the READABLE verification token", column)
                        .doesNotContain(token);
            }
        });

        // ...and the digest that IS stored must be the digest of that token, or the stored value is
        // unrelated to what was sent and verification could never succeed.
        String storedDigest = jdbc.queryForObject(
                "SELECT verification_token_sha256 FROM dsar_request", String.class);
        assertThat(storedDigest).isEqualTo(DsarSubjectDigest.sha256Hex(token));
    }

    @Test
    void theMailIsAddressedToTheSubjectSoOnlyTheMailboxHolderCanVerify() throws Exception {
        String email = "delivery-target-" + UUID.randomUUID() + "@example.com";
        lodge(email, "ERASURE", "203.0.113.14");

        ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendVerification(recipient.capture(), anyString(), any(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(recipient.getValue())
                .as("the token must go to the ADDRESS THAT WAS NAMED — that is the entire proof of "
                        + "control. Sending it anywhere else would make the gate decorative.")
                .isEqualTo(email);
    }

    @Test
    void anUnknownTokenIsRefusedAndArmsNothing() throws Exception {
        String email = "unknown-token-" + UUID.randomUUID() + "@example.com";
        lodge(email, "ERASURE", "203.0.113.15");

        verifyToken("not-a-real-token-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("invalid"));

        assertThat(statusOf(email))
                .as("a bad token must arm nothing")
                .isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        String email = "expired-token-" + UUID.randomUUID() + "@example.com";
        String token = lodgeAndCaptureToken(email, "ERASURE", "203.0.113.16");

        // Expire it in the past. The window is a published, config-injected period, not a literal.
        jdbc.update("UPDATE dsar_request SET verification_expires_at = NOW() - INTERVAL '1 second'");

        verifyToken(token)
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("invalid"));

        assertThat(statusOf(email)).isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    void aReplayedTokenDoesNotRearmAnAlreadyCompletedRequest() throws Exception {
        UUID tenant = seedTenant();
        String email = "replay-" + UUID.randomUUID() + "@example.com";
        seedCustomer(tenant, email);

        String token = lodgeAndCaptureToken(email, "ERASURE", "203.0.113.17");
        verifyToken(token).andExpect(status().isOk());
        worker.executeLodgedRequests();
        assertThat(statusOf(email)).isEqualTo("COMPLETED");

        verifyToken(token)
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("already_verified"));

        assertThat(statusOf(email))
                .as("a replayed token must not resurrect a completed request for a second erasure")
                .isEqualTo("COMPLETED");
    }

    @Test
    void theVerifyEndpointIsReachableWithoutACredential() throws Exception {
        // The complement that stops "it returned 200" being read as "auth is broken everywhere":
        // this endpoint IS anonymous by design, because the subject has no account with anybody.
        mockMvc.perform(get(VERIFY_PATH).param("token", "anything"))
                .andExpect(status().isOk());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void lodge(String email, String type, String clientIp) throws Exception {
        mockMvc.perform(post(INTAKE_PATH)
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "requestType", type))))
                .andExpect(status().isAccepted());
    }

    private String lodgeAndCaptureToken(String email, String type, String clientIp) throws Exception {
        lodge(email, type, clientIp);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendVerification(org.mockito.ArgumentMatchers.eq(email), token.capture(),
                any(), org.mockito.ArgumentMatchers.anyLong());
        return token.getValue();
    }

    private org.springframework.test.web.servlet.ResultActions verifyToken(String token) throws Exception {
        return mockMvc.perform(post(VERIFY_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", token))));
    }

    private String statusOf(String email) {
        return jdbc.queryForObject(
                "SELECT status FROM dsar_request WHERE subject_email_sha256 = ?",
                String.class, DsarSubjectDigest.of(email));
    }

    private UUID seedTenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "T-" + id);
        return id;
    }

    /** Seeds with the tenant GUC pinned inside the transaction so it works under FORCE RLS too. */
    private void seedCustomer(UUID tenantId, String email) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            pin(tenantId);
            jdbc.update("INSERT INTO customers (id, tenant_id, name, email) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), tenantId, "Seeded Subject", email);
        });
    }

    private long erasureRecordCount(UUID tenantId) {
        Long n = new TransactionTemplate(txManager).execute(s -> {
            pin(tenantId);
            return jdbc.queryForObject("SELECT COUNT(*) FROM erasure_records", Long.class);
        });
        return n == null ? 0 : n;
    }

    private boolean customerEmailStillPresent(UUID tenantId, String email) {
        Long n = new TransactionTemplate(txManager).execute(s -> {
            pin(tenantId);
            return jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE email = ?",
                    Long.class, email);
        });
        return n != null && n > 0;
    }

    private void pin(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.current_tenant_id', ?, true)",
                String.class, tenantId.toString());
    }
}
