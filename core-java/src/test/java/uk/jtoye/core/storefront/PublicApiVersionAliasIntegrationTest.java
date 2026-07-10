package uk.jtoye.core.storefront;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Versioned public-surface alias contract (issue #97 [P2-6]).
 *
 * <p>{@code /api/v1/public/**} is the canonical versioned alias of the legacy
 * {@code /public/**} surface. The alias must be ADDITIVE: the legacy paths keep
 * working (deployed frontend/edge callers + the configured Stripe webhook), the
 * alias serves the identical handlers, and — crucially — the alias's
 * {@code permitAll} matcher must not widen access to the authenticated
 * {@code /api/v1/**} surface. Runs with the full security filter chain against
 * Testcontainers Postgres, so it exercises SecurityConfig matchers, WebConfig
 * mappings, and controllers together.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("testcontainers")
class PublicApiVersionAliasIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void legacyPublicPathStillServesUnauthenticated() throws Exception {
        mockMvc.perform(get("/public/shops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void versionedAliasServesTheSameSurfaceUnauthenticated() throws Exception {
        MvcResult legacy = mockMvc.perform(get("/public/shops"))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult versioned = mockMvc.perform(get("/api/v1/public/shops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        assertThat(objectMapper.readTree(versioned.getResponse().getContentAsString()))
                .as("/api/v1/public/shops must serve the identical handler as legacy /public/shops")
                .isEqualTo(objectMapper.readTree(legacy.getResponse().getContentAsString()));
    }

    @Test
    void versionedAliasDoesNotWidenAccessToAuthenticatedSurface() throws Exception {
        // The /api/v1/public/** permitAll matcher must not leak onto /api/v1/**.
        mockMvc.perform(get("/api/v1/shops"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void stripeWebhookReachableOnBothLegacyAndVersionedPaths() throws Exception {
        // A bogus signature reaches the controller and fails verification with 400
        // — NOT 401/403 (blocked by security) and NOT 404 (unmapped). That proves
        // both paths are mapped and public without needing a real Stripe secret.
        String payload = "{\"type\":\"payment_intent.succeeded\"}";

        mockMvc.perform(post("/public/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", "t=1,v1=bogus"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/public/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", "t=1,v1=bogus"))
                .andExpect(status().isBadRequest());
    }
}
