package uk.jtoye.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-10 (QA council 20260902-134741) through the REAL filter chain.
 *
 * <p>{@link ProblemDetailAuthenticationEntryPointTest} proves the component; this proves it
 * is WIRED. An entry point that exists as a bean and is never registered on the chain is
 * indistinguishable from the defect — the live 401 came out of Spring Security's default,
 * not out of any class in this package.
 *
 * <p>Both doors into a 401 are exercised, because they are handled by different filters:
 * a request with NO Authorization header is refused by {@code ExceptionTranslationFilter},
 * and a request carrying a garbage bearer is refused by
 * {@code BearerTokenAuthenticationFilter}. Wiring only one leaves the other empty-bodied.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("testcontainers")
class UnauthenticatedProblemDetailIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    private static final UUID WELL_KNOWN_TENANT = UUID.fromString("00000000-0000-0000-0000-00000000b005");

    /**
     * Phase 38 D-04: the challenge is asserted BY EXACT VALUE. An existence check passed on the
     * Security-7 defect (a {@code resource_metadata} parameter advertising a URL this API does not
     * serve); an exact {@code Bearer} cannot.
     */
    @Test
    void missingBearerReturnsRfc7807ProblemDocument() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/unauthorized"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    /** Phase 38 D-04: RFC 6750 error form, and never a {@code resource_metadata} parameter. */
    @Test
    void garbageBearerReturnsRfc7807ProblemDocument() throws Exception {
        mockMvc.perform(get("/api/v1/products").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("WWW-Authenticate", allOf(
                        startsWith("Bearer error=\"invalid_token\""),
                        not(containsString("resource_metadata")))))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    /**
     * Phase 38 (D-05 baseline): pins the PRE-migration Boot 3.5.16 answer to an UNAUTHENTICATED
     * {@code GET /.well-known/oauth-protected-resource}, as measured (38-02 evidence,
     * {@code 38-02-baselines.txt}). The path is not mapped on 3.5, so the chain refuses it like
     * any other protected route. This method is EXPECTED to go red on Boot 4, where Spring
     * Security 7 serves the path, until 38-06 lands D-05 (404 for every caller); 38-06 changes it
     * deliberately and the baseline file records what changed.
     */
    @Test
    void wellKnownProtectedResourceUnauthenticated_boot35Baseline() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource"))
                .andDo(print())
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/unauthorized"));
    }

    /**
     * Phase 38 (D-05 baseline): pins the PRE-migration Boot 3.5.16 answer to an AUTHENTICATED
     * {@code GET /.well-known/oauth-protected-resource} (a tenant JWT for an ACTIVE tenant), as
     * measured: the unmapped path reaches {@code GlobalExceptionHandler}'s
     * {@code NoResourceFoundException} mapping. EXPECTED to go red on Boot 4 until 38-06 lands
     * D-05.
     */
    @Test
    void wellKnownProtectedResourceAuthenticated_boot35Baseline() throws Exception {
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                WELL_KNOWN_TENANT, "Well-known Baseline Tenant");
        mockMvc.perform(get("/.well-known/oauth-protected-resource")
                        .with(jwt().jwt(j -> j
                                .subject(UUID.randomUUID().toString())
                                .claim("tenant_id", WELL_KNOWN_TENANT.toString()))))
                .andDo(print())
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/not-found"));
    }

    /**
     * The control that makes the two assertions above mean something: a PERMITTED route
     * must still be reachable anonymously with its own, non-problem body. Without it,
     * "every anonymous request returns an unauthorized problem document" would satisfy
     * the pair — including a chain that had started refusing the public storefront.
     */
    @Test
    void permittedRouteStillAnswersAnonymously() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }
}
