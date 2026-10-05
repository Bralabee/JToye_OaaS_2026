package uk.jtoye.core.security;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.server.resource.web.OAuth2ProtectedResourceMetadataFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.filter.CorsFilter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    @Autowired
    private FilterChainProxy filterChainProxy;

    private static final UUID WELL_KNOWN_TENANT = UUID.fromString("00000000-0000-0000-0000-00000000b005");

    private static final String WELL_KNOWN = "/.well-known/oauth-protected-resource";

    /** The member only Spring Security 7.1's default RFC 9728 body carries (false for this API). */
    private static final String FALSE_CLAIM = "tls_client_certificate_bound_access_tokens";

    @BeforeEach
    void seedTenant() {
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                WELL_KNOWN_TENANT, "Well-known Baseline Tenant");
    }

    /** A tenant JWT for an ACTIVE tenant, as in the 38-02 Boot 3.5.16 baseline measurement. */
    private static RequestPostProcessor tenantJwt() {
        return jwt().jwt(j -> j
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", WELL_KNOWN_TENANT.toString()));
    }

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

    // ---------------------------------------------------------------------------------------
    // Phase 38 D-05: /.well-known/oauth-protected-resource is suppressed. Spring Security 7.1
    // always installs OAuth2ProtectedResourceMetadataFilter, whose default body claims
    // tls_client_certificate_bound_access_tokens: true, false for this API. Owner decision
    // 2026-10-05, "anon-401-parity (Recommended)": a caller with no credentials gets the 401 any
    // protected route gives (the Boot 3.5.16 answer, 38-02 baseline); a caller presenting
    // credentials gets the 404 not-found document (also the 3.5.16 answer). These two methods
    // were the 38-02 *_boot35Baseline pins, renamed; 38-06-security.txt lists every expectation
    // that changed against D-05 as written.
    // ---------------------------------------------------------------------------------------

    /**
     * No credentials: 401 with the exact plain challenge and the unauthorized document, the
     * Boot 3.5.16 answer (38-02). Never the framework's 200 metadata.
     */
    @Test
    void wellKnownProtectedResource_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(WELL_KNOWN))
                .andDo(print())
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/unauthorized"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(content().string(not(containsString(FALSE_CLAIM))))
                // the security headers SecurityHeadersIntegrationTest expects on every response
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    /**
     * Credentials present (an ACTIVE tenant's JWT, as in the 38-02 baseline): 404 with the
     * application's not-found document, the Boot 3.5.16 answer. Never the framework's metadata.
     */
    @Test
    void wellKnownProtectedResource_authenticated_returns404() throws Exception {
        mockMvc.perform(get(WELL_KNOWN).with(tenantJwt()))
                .andDo(print())
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/not-found"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.instance").value(WELL_KNOWN))
                .andExpect(content().string(not(containsString(FALSE_CLAIM))))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    /**
     * "The same 401 as any other protected route", measured rather than asserted field by field:
     * status, every header and the body bytes equal those of {@code GET /api/v1/products} with no
     * credentials. A 401 hand-built in the filter, or one answered before the CORS filter (no
     * {@code Vary}), fails here.
     */
    @Test
    void wellKnownProtectedResource_unauthenticated_isTheSame401AsAnyProtectedRoute() throws Exception {
        MockHttpServletResponse reference = mockMvc.perform(get("/api/v1/products")).andReturn().getResponse();
        MockHttpServletResponse wellKnown = mockMvc.perform(get(WELL_KNOWN)).andReturn().getResponse();

        assertThat(reference.getStatus()).as("control: the reference route refuses").isEqualTo(401);
        assertThat(wellKnown.getStatus()).isEqualTo(reference.getStatus());
        assertThat(headersOf(wellKnown)).isEqualTo(headersOf(reference));
        assertThat(wellKnown.getContentAsString()).isEqualTo(reference.getContentAsString());
    }

    /**
     * "The application's 404 document", measured: the suppressed path's 404 equals the one
     * {@code GlobalExceptionHandler} gives a genuinely unmapped path ({@code NoResourceFoundException}),
     * member for member and header for header, except {@code instance}, which is each request's
     * own path.
     */
    @Test
    void wellKnownProtectedResource_authenticated_isTheSame404AsAnUnmappedPath() throws Exception {
        String unmapped = "/.well-known/no-such-document";
        MockHttpServletResponse reference = mockMvc.perform(get(unmapped).with(tenantJwt())).andReturn().getResponse();
        MockHttpServletResponse wellKnown = mockMvc.perform(get(WELL_KNOWN).with(tenantJwt())).andReturn().getResponse();

        assertThat(reference.getStatus()).as("control: the unmapped path is a 404").isEqualTo(404);
        assertThat(wellKnown.getStatus()).isEqualTo(404);
        assertThat(headersOf(wellKnown)).isEqualTo(headersOf(reference));

        ObjectNode expected = (ObjectNode) jsonMapper.readTree(reference.getContentAsByteArray());
        ObjectNode actual = (ObjectNode) jsonMapper.readTree(wellKnown.getContentAsByteArray());
        assertThat(expected.path("instance").asString()).isEqualTo(unmapped);
        assertThat(actual.path("instance").asString()).isEqualTo(WELL_KNOWN);
        expected.remove("instance");
        actual.remove("instance");
        assertThat(actual).isEqualTo(expected);
    }

    /** Any path under the prefix is suppressed the same way (the framework serves {@code /**}). */
    @Test
    void wellKnownProtectedResource_suffixPath_isSuppressedForBothCallerKinds() throws Exception {
        String suffix = WELL_KNOWN + "/anything";
        mockMvc.perform(get(suffix))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/unauthorized"))
                .andExpect(content().string(not(containsString(FALSE_CLAIM))));
        mockMvc.perform(get(suffix).with(tenantJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/not-found"))
                .andExpect(jsonPath("$.instance").value(suffix))
                .andExpect(content().string(not(containsString(FALSE_CLAIM))));
    }

    /**
     * RECORDED RESIDUAL (owner decision "anon-401-parity", 38-06): the suppression answers BEFORE
     * authentication, so a well-formed but invalid or expired bearer is treated as a caller with
     * credentials and gets the 404, where Boot 3.5.16 gave {@code 401 Bearer error="invalid_token"}.
     * Pinned so that the residual is visible and any change to it is deliberate; see ADR-0006
     * (38-16) and the phase PR body (38-18).
     */
    @Test
    void wellKnownProtectedResource_invalidBearer_returns404_recordedResidual() throws Exception {
        mockMvc.perform(get(WELL_KNOWN).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/not-found"))
                .andExpect(content().string(not(containsString(FALSE_CLAIM))));
    }

    /**
     * The suppression filter runs ahead of the framework's metadata filter (so the false claim is
     * unreachable), and after the header writer and the CORS filter (so its 401/404 carry the
     * security headers and the CORS {@code Vary} every other response carries).
     */
    @Test
    void suppressionFilterRunsAfterHeadersAndCorsAndBeforeTheFrameworkMetadataFilter() {
        List<Class<?>> types = new java.util.ArrayList<>();
        for (Filter f : filterChainProxy.getFilters(WELL_KNOWN)) {
            types.add(f.getClass());
        }
        int suppression = types.indexOf(ProtectedResourceMetadataSuppressionFilter.class);
        int framework = types.indexOf(OAuth2ProtectedResourceMetadataFilter.class);
        int headers = types.indexOf(HeaderWriterFilter.class);
        int cors = types.indexOf(CorsFilter.class);

        assertThat(framework).as("control: Security 7.1 installs its metadata filter: %s", types).isNotNegative();
        assertThat(headers).as("HeaderWriterFilter in %s", types).isNotNegative();
        assertThat(cors).as("CorsFilter in %s", types).isNotNegative();
        assertThat(suppression).as("suppression filter registered on the chain: %s", types).isNotNegative();
        assertThat(suppression).as("suppression before the framework metadata filter: %s", types).isLessThan(framework);
        assertThat(suppression).as("suppression after HeaderWriterFilter: %s", types).isGreaterThan(headers);
        assertThat(suppression).as("suppression after CorsFilter: %s", types).isGreaterThan(cors);
    }

    /**
     * A 403 is untouched by D-04/D-05: an authenticated token lacking {@code catalog:write} is
     * refused by the method-security gate with the application's forbidden document, and no
     * challenge advertising {@code resource_metadata} rides on it.
     */
    @Test
    void forbiddenCarriesNoResourceMetadata() throws Exception {
        mockMvc.perform(delete("/api/v1/products/{id}", UUID.randomUUID())
                        .with(jwt().jwt(j -> j
                                        .subject(UUID.randomUUID().toString())
                                        .claim("tenant_id", WELL_KNOWN_TENANT.toString())
                                        .claim("scope", "catalog:read"))
                                .authorities(new JwtRolesAndScopesConverter())))
                .andDo(print())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/forbidden"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    private static Map<String, List<String>> headersOf(MockHttpServletResponse response) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : response.getHeaderNames()) {
            headers.put(name, response.getHeaders(name));
        }
        return headers;
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
