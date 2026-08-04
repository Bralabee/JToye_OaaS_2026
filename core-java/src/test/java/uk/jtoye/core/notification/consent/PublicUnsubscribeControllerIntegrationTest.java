package uk.jtoye.core.notification.consent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * COMMS-03 — the no-auth one-click unsubscribe endpoint end-to-end against the
 * full security filter chain + Testcontainers Postgres (V54 applied). Proves:
 * a valid-token POST writes exactly one suppression row and a replay is
 * idempotent; a tampered token writes zero rows and returns {@code invalid}; the
 * endpoint resolves under {@code /api/v1/public/unsubscribe} with NO Bearer token
 * (permitAll inherited from SecurityConfig).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class PublicUnsubscribeControllerIntegrationTest {

    private static final String SIGNING_SECRET = "integration-test-unsubscribe-secret-abcdef0123456789";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // The controller's UnsubscribeTokenService and the autowired one below
        // must share this secret so a token minted here verifies in the handler.
        registry.add("notification.unsubscribe.signing-secret", () -> SIGNING_SECRET);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UnsubscribeTokenService tokenService;

    private UUID tenant;
    private final String email = "customer@example.com";
    private final NotificationCategory category = NotificationCategory.ORDERS;

    @BeforeEach
    void seedTenant() {
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "test-" + tenant);
    }

    private int suppressionCount() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM notification_suppression WHERE tenant_id = ? AND recipient = ? AND category = ?",
                Integer.class, tenant, email, category.name());
        return n == null ? 0 : n;
    }

    @Test
    void validToken_writesExactlyOneRow_andReplayIsIdempotent() throws Exception {
        String token = tokenService.tokenFor(tenant, email, category);

        mockMvc.perform(post("/api/v1/public/unsubscribe")
                        .param("tenant", tenant.toString())
                        .param("email", email)
                        .param("category", category.name())
                        .param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unsubscribed"));

        assertThat(suppressionCount()).as("one suppression row after the first unsubscribe").isEqualTo(1);

        // Replay the identical one-click link — must stay idempotent (still one row).
        mockMvc.perform(post("/api/v1/public/unsubscribe")
                        .param("tenant", tenant.toString())
                        .param("email", email)
                        .param("category", category.name())
                        .param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("already_unsubscribed"));

        assertThat(suppressionCount()).as("replay does not duplicate the opt-out").isEqualTo(1);
    }

    @Test
    void tamperedToken_returnsInvalid_andWritesZeroRows() throws Exception {
        mockMvc.perform(post("/api/v1/public/unsubscribe")
                        .param("tenant", tenant.toString())
                        .param("email", email)
                        .param("category", category.name())
                        .param("token", "tampered-not-a-valid-hmac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("invalid"));

        assertThat(suppressionCount()).as("a forged token must not write a suppression row").isZero();
    }

    @Test
    void jsonBodyPost_writesTheSuppressionRow_underRealRls() throws Exception {
        // Issue #278: the canonical browser POST now carries its fields in a JSON
        // body so they never enter the request line. Binding is pinned by
        // PublicUnsubscribeRequestShapeTest; this proves the body shape still
        // reaches the tenant-pinned @Transactional write against real Postgres
        // with the RLS policy live — a mock cannot show that.
        String token = tokenService.tokenFor(tenant, email, category);
        String body = """
                {"tenant":"%s","email":"%s","category":"%s","token":"%s"}
                """.formatted(tenant, email, category.name(), token);

        mockMvc.perform(post("/api/v1/public/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unsubscribed"));

        assertThat(suppressionCount()).as("the JSON-body POST writes exactly one suppression row").isEqualTo(1);
    }

    @Test
    void rfc8058OneClickPost_stillWritesTheRow_forLinksAlreadyInInboxes() throws Exception {
        // Every message already delivered carries List-Unsubscribe with the
        // fields in the URI, and RFC 8058 fixes the POST body to the literal
        // "List-Unsubscribe=One-Click" — so the query-param shape can never be
        // retired. Proven end-to-end, not just at the binding layer.
        String token = tokenService.tokenFor(tenant, email, category);

        mockMvc.perform(post("/api/v1/public/unsubscribe")
                        .param("tenant", tenant.toString())
                        .param("email", email)
                        .param("category", category.name())
                        .param("token", token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("List-Unsubscribe=One-Click"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unsubscribed"));

        assertThat(suppressionCount()).as("a mail-provider one-click POST still opts the recipient out").isEqualTo(1);
    }

    @Test
    void endpointReachableWithoutBearerToken() throws Exception {
        // A well-formed but wrong-secret request still reaches the handler (200
        // 'invalid'), NOT 401/403 — proving permitAll is inherited and no auth is
        // required on /api/v1/public/unsubscribe.
        mockMvc.perform(post("/api/v1/public/unsubscribe")
                        .param("tenant", tenant.toString())
                        .param("email", email)
                        .param("category", category.name())
                        .param("token", "anything"))
                .andExpect(status().isOk());
    }
}
