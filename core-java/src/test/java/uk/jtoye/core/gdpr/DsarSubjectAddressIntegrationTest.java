package uk.jtoye.core.gdpr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
    }

    private static final String INTAKE_PATH = "/api/v1/public/gdpr/dsar";
    private static final String VERIFY_PATH = "/api/v1/public/gdpr/dsar/verify";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private DsarCipher cipher;
    @Autowired private DsarFanoutWorker worker;
    @Autowired private Environment environment;

    @MockitoSpyBean private DsarVerificationMailer mailer;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dsar_request");
        reset(mailer);
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

    // ---- helpers -------------------------------------------------------------------------------

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
