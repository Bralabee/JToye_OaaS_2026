package uk.jtoye.core.gdpr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-19 (owner ruling 2026-10-05) proven against a real Postgres: the DSAR worker can reach the
 * subject, the database never holds the address readable, and every terminal state destroys it.
 *
 * <p>What is asserted is the STORED BYTES, read straight out of {@code dsar_request} with plain JDBC,
 * not anything an entity or a service reports about itself. "The service says it encrypted" and
 * "the row holds no readable address" are different claims, and only the second is the one a
 * database dump would test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
// The fan-out and the expiry sweep are @Scheduled, and a fixedDelay task fires once at context
// refresh regardless of its interval (#418). This class drives both by hand and owns the timeline.
@Import(NoScheduledTriggersTestConfig.class)
class DsarSubjectAddressIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // One attempt, so a single failing sweep exhausts the request and parks it FAILED. No
        // test in this class relies on a retry.
        registry.add("jtoye.gdpr.dsar.max-process-attempts", () -> "1");
    }

    private static final String INTAKE_PATH = "/api/v1/public/gdpr/dsar";
    private static final String VERIFY_PATH = "/api/v1/public/gdpr/dsar/verify";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private DsarCipher cipher;
    @Autowired private DsarFanoutWorker worker;
    @Autowired private Environment environment;
    @Autowired private DsarRequestExpirySweep expirySweep;

    @MockitoSpyBean private DsarVerificationMailer mailer;
    @MockitoSpyBean private GdprService gdprService;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dsar_request");
        reset(mailer, gdprService);
    }

    // ---- The test key itself ------------------------------------------------------------------

    @Test
    void theTestProfileSuppliesAPerRunRandomKeyOfSixtyFourHexCharacters() {
        // Measured, not assumed: random.value is Spring Boot's RandomValuePropertySource. If it
        // ever stops yielding 32 hex characters, the concatenation stops being a valid key, and this
        // assertion names that rather than every DSAR test failing on a context load.
        String key = environment.getProperty("jtoye.gdpr.dsar.encryption-key");
        assertThat(key).as("the test profile must supply the key").isNotNull();
        assertThat(key).matches("[0-9a-fA-F]{64}");
        // Each resolution draws fresh randomness, so two reads differ: the key is not a literal
        // that happens to be 64 hex characters long.
        assertThat(environment.getProperty("jtoye.gdpr.dsar.encryption-key")).isNotEqualTo(key);
    }

    // ---- At rest -------------------------------------------------------------------------------

    @Test
    void aLodgedRequestStoresTheAddressOnlyAsCiphertextThatTheWorkerSideCanDecrypt() throws Exception {
        String typed = "  Subject.Address+" + UUID.randomUUID() + "@Example.TEST  ";
        String trimmed = typed.trim();

        lodge(typed, "ACCESS", "198.51.100.71");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, subject_email_ciphertext FROM dsar_request");
        UUID id = (UUID) row.get("id");
        byte[] stored = (byte[]) row.get("subject_email_ciphertext");

        assertThat(stored).as("the intake must store the encrypted address").isNotNull();

        for (String form : new String[]{trimmed, trimmed.toLowerCase(java.util.Locale.ROOT), typed}) {
            assertThat(indexOf(stored, form.getBytes(StandardCharsets.UTF_8)))
                    .as("the stored bytes must not contain the address in readable form: %s", form)
                    .isEqualTo(-1);
        }
        // CONTROL: the same search over a plain UTF-8 encoding finds it.
        byte[] plain = ("x" + trimmed).getBytes(StandardCharsets.UTF_8);
        assertThat(indexOf(plain, trimmed.getBytes(StandardCharsets.UTF_8))).isEqualTo(1);

        assertThat(cipher.decrypt(DsarCipher.Purpose.SUBJECT_ADDRESS, id, stored))
                .as("the worker side recovers the address as typed, trimmed and not lower-cased")
                .isEqualTo(trimmed);
    }

    // ---- Terminal states -----------------------------------------------------------------------

    @Test
    void completingTheRequestDestroysTheCiphertext() throws Exception {
        lodgeVerified("completes-" + UUID.randomUUID() + "@example.test", "ERASURE", "198.51.100.72");
        assertThat(ciphertextIsPresent()).as("PRECONDITION: a verified request still holds it").isTrue();

        worker.executeLodgedRequests();

        assertThat(requestStatus()).isEqualTo("COMPLETED");
        assertThat(ciphertextIsPresent()).as("COMPLETED must NULL the encrypted address").isFalse();
    }

    @Test
    void anExhaustedRequestIsParkedFailedAndDestroysTheCiphertext() throws Exception {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "T-" + tenant);
        doThrow(new IllegalStateException("deliberate per-tenant failure"))
                .when(gdprService).eraseSubjectByDigest(any(), anyString());
        lodgeVerified("exhausts-" + UUID.randomUUID() + "@example.test", "ERASURE", "198.51.100.73");
        assertThat(ciphertextIsPresent()).as("PRECONDITION: a verified request still holds it").isTrue();

        worker.executeLodgedRequests();

        assertThat(requestStatus()).as("max-process-attempts is 1 here").isEqualTo("FAILED");
        assertThat(ciphertextIsPresent()).as("FAILED must NULL the encrypted address").isFalse();
    }

    @Test
    void anUnverifiedRequestPastItsExpiryIsSweptToExpiredAndDestroysTheCiphertext() throws Exception {
        lodge("never-verified-" + UUID.randomUUID() + "@example.test", "ERASURE", "198.51.100.74");
        UUID lapsed = jdbc.queryForObject("SELECT id FROM dsar_request", UUID.class);
        jdbc.update("UPDATE dsar_request SET verification_expires_at = NOW() - INTERVAL '1 hour' WHERE id = ?",
                lapsed);
        lodge("still-pending-" + UUID.randomUUID() + "@example.test", "ACCESS", "198.51.100.75");
        UUID live = jdbc.queryForObject("SELECT id FROM dsar_request WHERE id <> ?", UUID.class, lapsed);

        expirySweep.expireLapsedVerifications();

        Map<String, Object> swept = jdbc.queryForMap(
                "SELECT status, completed_at IS NOT NULL AS done, subject_email_ciphertext IS NULL AS dropped "
                        + "FROM dsar_request WHERE id = ?", lapsed);
        assertThat(swept.get("status")).isEqualTo("EXPIRED");
        assertThat(swept.get("done")).as("completed_at is stamped").isEqualTo(true);
        assertThat(swept.get("dropped")).as("EXPIRED must NULL the encrypted address").isEqualTo(true);

        // CONTROL: a request still inside its window is untouched, so the sweep is selective and the
        // assertions above are about the expiry, not about a sweep that clears everything.
        Map<String, Object> untouched = jdbc.queryForMap(
                "SELECT status, completed_at IS NULL AS open, subject_email_ciphertext IS NOT NULL AS kept "
                        + "FROM dsar_request WHERE id = ?", live);
        assertThat(untouched.get("status")).isEqualTo("PENDING_VERIFICATION");
        assertThat(untouched.get("open")).isEqualTo(true);
        assertThat(untouched.get("kept")).isEqualTo(true);
    }

    @Test
    void theDatabaseRefusesATerminalRowThatStillHoldsTheAddress() throws Exception {
        lodge("check-" + UUID.randomUUID() + "@example.test", "ERASURE", "198.51.100.76");
        assertThat(ciphertextIsPresent()).as("PRECONDITION: the row holds ciphertext").isTrue();

        for (String terminal : new String[]{"COMPLETED", "FAILED", "EXPIRED"}) {
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE dsar_request SET status = ?, completed_at = NOW()", terminal))
                    .as("status %s with the ciphertext still present", terminal)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_dsar_request_ciphertext_terminal");
        }
        // CONTROL: the same UPDATE succeeds once the ciphertext is gone, so the refusals above are
        // the CHECK and not some other constraint on these columns.
        assertThat(jdbc.update("UPDATE dsar_request SET status = 'COMPLETED', completed_at = NOW(), "
                + "subject_email_ciphertext = NULL")).isEqualTo(1);
    }

    @Test
    void theApplicationRefusesToStartWithoutAKeyAndNamesThePropertyNotTheValue() {
        // The REAL application.yml, through Boot's own config-data loading and strict placeholder
        // resolver, with the process environment removed so a developer shell that exports
        // DSAR_ENCRYPTION_KEY cannot turn the fail arm into a false pass (the
        // StagingRedisPasswordFailClosedTest idiom).
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(DsarCipher.class);

        runner.run(ctx -> {
            assertThat(ctx).as("no DSAR_ENCRYPTION_KEY: application.yml's empty default must fail fast")
                    .hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jtoye.gdpr.dsar.encryption-key");
        });

        String wrongLength = randomHexKey().substring(0, 63);
        runner.withSystemProperties("DSAR_ENCRYPTION_KEY=" + wrongLength).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .hasMessageContaining("jtoye.gdpr.dsar.encryption-key")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(wrongLength.substring(0, 16)));
        });

        // CONTROL: the same runner starts with a valid key, so the failures above are the key.
        runner.withSystemProperties("DSAR_ENCRYPTION_KEY=" + randomHexKey())
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(DsarCipher.class));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static String randomHexKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return HexFormat.of().formatHex(key);
    }

    private void lodge(String email, String type, String clientIp) throws Exception {
        mockMvc.perform(post(INTAKE_PATH)
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("email", email, "requestType", type))))
                .andExpect(status().isAccepted());
    }

    private void lodgeVerified(String email, String type, String clientIp) throws Exception {
        lodge(email, type, clientIp);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendVerification(anyString(), token.capture(), any(), anyLong());
        mockMvc.perform(post(VERIFY_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("token", token.getValue()))))
                .andExpect(status().isOk());
        reset(mailer);
    }

    private String requestStatus() {
        return jdbc.queryForObject("SELECT status FROM dsar_request", String.class);
    }

    private boolean ciphertextIsPresent() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT subject_email_ciphertext IS NOT NULL FROM dsar_request", Boolean.class));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
