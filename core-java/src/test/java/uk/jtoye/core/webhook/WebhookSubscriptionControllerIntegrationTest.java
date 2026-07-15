package uk.jtoye.core.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP proof of the webhook-subscription API over real Postgres 15
 * (Testcontainers): the signing secret is shown exactly once (create + rotate)
 * and is never re-fetchable via GET; revoke is terminal; a non-HTTPS create and
 * an unknown id both return RFC 7807.
 *
 * <p>SSRF host-resolution is disabled ({@code webhook.target.block-private-ranges=false})
 * so the test stays hermetic (no DNS) while the HTTPS-scheme guard is still
 * enforced — the SSRF range blocking itself is proven by {@code WebhookUrlValidatorTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class WebhookSubscriptionControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // Hermetic: skip DNS resolution; HTTPS scheme is still enforced.
        registry.add("webhook.target.block-private-ranges", () -> "false");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void seedTenant() {
        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "test-" + tenantId);
    }

    private static final String CREATE_BODY =
            "{\"targetUrl\":\"https://example.com/hook\",\"eventTypes\":[\"ORDER_STATE_CHANGED\",\"ORDER_REFUNDED\"]}";

    private JsonNode createSubscription() throws Exception {
        String body = mockMvc.perform(post("/api/v1/webhooks")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.signingSecret").isNotEmpty())
                .andExpect(jsonPath("$.subscription.id").exists())
                .andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    @WithMockUser
    void create_returnsSecretOnce_andGetNeverReturnsIt() throws Exception {
        JsonNode created = createSubscription();
        String id = created.get("subscription").get("id").asText();
        assertThat(created.get("signingSecret").asText()).isNotBlank();

        // A subsequent GET must NOT expose the secret.
        mockMvc.perform(get("/api/v1/webhooks/" + id)
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.signingSecret").doesNotExist());
    }

    @Test
    @WithMockUser
    void rotateSecret_returnsADifferentSecret() throws Exception {
        JsonNode created = createSubscription();
        String id = created.get("subscription").get("id").asText();
        String firstSecret = created.get("signingSecret").asText();

        String rotated = mockMvc.perform(post("/api/v1/webhooks/" + id + "/rotate-secret")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signingSecret").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String newSecret = objectMapper.readTree(rotated).get("signingSecret").asText();

        assertThat(newSecret).isNotBlank().isNotEqualTo(firstSecret);
    }

    @Test
    @WithMockUser
    void revoke_marksSubscriptionRevoked() throws Exception {
        JsonNode created = createSubscription();
        String id = created.get("subscription").get("id").asText();

        mockMvc.perform(post("/api/v1/webhooks/" + id + "/revoke")
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        mockMvc.perform(get("/api/v1/webhooks/" + id)
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    @WithMockUser
    void create_withNonHttpsUrl_returns400ProblemDetail() throws Exception {
        String httpBody = "{\"targetUrl\":\"http://example.com/hook\",\"eventTypes\":[\"ORDER_STATE_CHANGED\"]}";
        mockMvc.perform(post("/api/v1/webhooks")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(httpBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @WithMockUser
    void get_unknownId_returns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/" + UUID.randomUUID())
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.detail").exists());
    }
}
