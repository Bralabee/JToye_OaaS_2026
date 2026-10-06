package uk.jtoye.core.gdpr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Issue #778 (P0, decision D-01), the second half: the subject OPENS the emailed link and is handed
 * the export exactly once. 31.1-16 stores the encrypted document behind a single-use token; this
 * class proves the anonymous consume endpoint that spends it.
 *
 * <p>Every export here is produced by the real {@link DsarAccessExportService#storeAndIssueToken} —
 * the same call the fan-out worker makes — so the token, its SHA-256 and the AES-256-GCM payload are
 * the production shapes, not fixtures typed by hand. Every state assertion reads
 * {@code dsar_access_export} with plain JDBC, never what the endpoint reports.
 *
 * <p>{@code dsar_access_export} carries no row-level security (V75, exempted by addition in
 * {@code RlsContractTest}), so there is no role downgrade here: there is no policy to bypass.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
// No scheduled trigger fires on its own: this class drives every sweep by hand (#418).
@Import(NoScheduledTriggersTestConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class DsarExportDownloadIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    static final String EXPORT_PATH = "/api/v1/public/gdpr/dsar/export";
    static final String UNAVAILABLE_TYPE = "https://jtoye.uk/errors/dsar-export-unavailable";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper jsonMapper;
    @Autowired DsarAccessExportService exportService;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM dsar_request");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dsar_access_export", Long.class))
                .as("PRECONDITION: the export rows cascade from dsar_request, so each arm starts empty")
                .isZero();
    }

    // ---- Task 1: the tracer — open the link, press the button, see the data, once ----------------

    @Test
    void aValidTokenReturnsTheStoredDocumentOnceAndConsumesIt() throws Exception {
        String document = sampleDocument("grace.download+" + shortId() + "@example.test");
        Issued issued = issue(document);

        MvcResult first = postToken(issued.token());

        assertThat(first.getResponse().getStatus()).as("the first press is answered with the data").isEqualTo(200);
        assertThat(first.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(first.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("the body is the stored document, byte for byte")
                .isEqualTo(document);
        assertThat(first.getResponse().getHeader("Cache-Control"))
                .as("a personal-data document must never sit in a browser or proxy cache")
                .isEqualTo("no-store");

        Map<String, Object> row = exportRow(issued.requestId());
        assertThat(row.get("consumed_at")).as("the row is stamped consumed").isNotNull();
        assertThat(row.get("payload_ciphertext")).as("the payload is destroyed in the same statement").isNull();

        MvcResult second = postToken(issued.token());
        assertThat(second.getResponse().getStatus()).as("the link works once").isEqualTo(404);
        assertThat(problemType(second)).isEqualTo(UNAVAILABLE_TYPE);
    }

    @Test
    void usedUnknownAndExpiredTokensAllReceiveTheIdenticalTypedRefusal() throws Exception {
        Issued used = issue(sampleDocument("used+" + shortId() + "@example.test"));
        assertThat(postToken(used.token()).getResponse().getStatus()).isEqualTo(200);

        Issued expired = issue(sampleDocument("expired+" + shortId() + "@example.test"));
        assertThat(jdbc.update("UPDATE dsar_access_export SET expires_at = now() - interval '1 minute' "
                + "WHERE dsar_request_id = ?", expired.requestId()))
                .as("PRECONDITION: the expired fixture row was moved into the past").isEqualTo(1);

        MvcResult usedAgain = postToken(used.token());
        MvcResult unknown = postToken("never-issued-" + UUID.randomUUID());
        MvcResult lapsed = postToken(expired.token());

        for (MvcResult r : new MvcResult[] {usedAgain, unknown, lapsed}) {
            assertThat(r.getResponse().getStatus()).isEqualTo(404);
            assertThat(problemType(r)).isEqualTo(UNAVAILABLE_TYPE);
            JsonNode body = jsonMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(body.path("code").asString()).isEqualTo("DSAR_EXPORT_UNAVAILABLE");
            assertThat(body.path("title").asString()).isEqualTo("Data export unavailable");
        }
        byte[] usedBytes = usedAgain.getResponse().getContentAsByteArray();
        assertThat(unknown.getResponse().getContentAsByteArray())
                .as("unknown and used must be indistinguishable, byte for byte (T-31.1-62)")
                .isEqualTo(usedBytes);
        assertThat(lapsed.getResponse().getContentAsByteArray())
                .as("expired and used must be indistinguishable, byte for byte (T-31.1-62)")
                .isEqualTo(usedBytes);

        assertThat(exportRow(expired.requestId()).get("payload_ciphertext"))
                .as("refusing an expired token spends nothing and changes nothing")
                .isNotNull();
    }

    @Test
    void aGetCannotSpendTheToken() throws Exception {
        Issued issued = issue(sampleDocument("prefetch+" + shortId() + "@example.test"));

        MvcResult viaGet = mockMvc.perform(get(EXPORT_PATH).param("token", issued.token())).andReturn();

        assertThat(viaGet.getResponse().getStatus())
                .as("there is no GET variant: a link scanner or prefetcher must not be able to spend it")
                .isNotEqualTo(200);
        Map<String, Object> row = exportRow(issued.requestId());
        assertThat(row.get("consumed_at")).isNull();
        assertThat(row.get("payload_ciphertext")).isNotNull();
        assertThat(postToken(issued.token()).getResponse().getStatus())
                .as("the token still works for the subject who presses the button")
                .isEqualTo(200);
    }

    @Test
    void neitherTheTokenNorTheDocumentReachesTheLog(CapturedOutput output) throws Exception {
        String email = "logcheck+" + shortId() + "@example.test";
        Issued issued = issue(sampleDocument(email));

        assertThat(postToken(issued.token()).getResponse().getStatus()).isEqualTo(200);
        assertThat(postToken(issued.token()).getResponse().getStatus()).isEqualTo(404);

        assertThat(output.getAll())
                .as("the download is observable in the log")
                .contains("event=dsar_export_downloaded");
        assertThat(output.getAll()).doesNotContain(issued.token());
        assertThat(output.getAll()).doesNotContain(email);
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    record Issued(UUID requestId, String token) {
    }

    /** A completed ACCESS request plus its export, stored by the production service. */
    Issued issue(String document) {
        UUID requestId = UUID.randomUUID();
        jdbc.update("INSERT INTO dsar_request (id, subject_email_sha256, request_type, status, verified_at, "
                + "completed_at) VALUES (?, ?, 'ACCESS', 'COMPLETED', NOW(), NOW())",
                requestId, DsarSubjectDigest.sha256Hex(requestId.toString()));
        DsarAccessExportService.IssuedToken token =
                exportService.storeAndIssueToken(requestId, document.getBytes(StandardCharsets.UTF_8));
        return new Issued(requestId, token.token());
    }

    MvcResult postToken(String token) throws Exception {
        return mockMvc.perform(post(EXPORT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("token", token))))
                .andReturn();
    }

    Map<String, Object> exportRow(UUID requestId) {
        return jdbc.queryForMap("SELECT consumed_at, purged_at, payload_ciphertext, expires_at "
                + "FROM dsar_access_export WHERE dsar_request_id = ?", requestId);
    }

    String problemType(MvcResult r) throws Exception {
        return jsonMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("type").asString();
    }

    static String sampleDocument(String email) {
        return "{\"format\":\"jtoye-dsar-export/1\",\"generatedAt\":\"2026-10-06T12:00:00Z\","
                + "\"requestedFor\":\"" + email + "\",\"summary\":{\"vendorDataHeld\":true,\"vendorCount\":1},"
                + "\"vendors\":[{\"reference\":\"" + UUID.randomUUID() + "\",\"recipient\":{\"legalName\":"
                + "\"Mama Ade's Kitchen Ltd\",\"shops\":[\"Mama Ade's Peckham\"]},\"orders\":[{\"orderNumber\":"
                + "\"ORD-1\",\"customerEmail\":\"" + email + "\"}]}]}";
    }

    static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
