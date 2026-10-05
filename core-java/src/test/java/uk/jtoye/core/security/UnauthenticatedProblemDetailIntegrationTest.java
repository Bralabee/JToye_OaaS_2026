package uk.jtoye.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Autowired
    private JsonMapper jsonMapper;

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
     * Phase 38 (BOOT4-04, D-04): the 401 body is written by Boot's Jackson-3 {@link JsonMapper},
     * byte for byte. The document is the API-10 one; its key order is Jackson 3's alphabetical
     * order (owner decision "jackson3-defaults", 38-05, locked by {@code Jackson3WireContractTest}).
     * A body still written by the Jackson-2 bridge mapper keeps declaration order and fails here.
     */
    @Test
    void unauthorizedBodyIsBootsJackson3Document() throws Exception {
        ProblemDetail expected = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication failed");
        expected.setTitle("Unauthorized");
        expected.setType(URI.create("https://jtoye.uk/errors/unauthorized"));
        String expectedBody = jsonMapper.writeValueAsString(expected);

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(expectedBody));
    }

    /**
     * API-10 preserved on Jackson 3: the mapper the 401 is written with flattens a
     * {@link ProblemDetail} extension member to the TOP level, exactly as
     * {@code GlobalExceptionHandler}'s 4xx bodies carry {@code errors}, {@code field} or
     * {@code code} (Boot registers {@code ProblemDetailJacksonMixin} on it). A mapper without
     * the mixin nests them under {@code properties} and fails here.
     */
    @Test
    void bootsJsonMapperFlattensProblemDetailExtensionMembers() throws Exception {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty("errors", java.util.Map.of("customerEmail", "Email is required"));
        JsonNode tree = jsonMapper.readTree(jsonMapper.writeValueAsString(problem));
        assertThat(tree.has("properties")).as("extension members nested under 'properties': %s", tree).isFalse();
        assertThat(tree.path("errors").path("customerEmail").asString()).isEqualTo("Email is required");
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
