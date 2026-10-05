package uk.jtoye.core.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 38 D-05 unit contract for {@link ProtectedResourceMetadataSuppressionFilter}.
 *
 * <p>Spring Security 7.1 always installs {@code OAuth2ProtectedResourceMetadataFilter}, which
 * answers {@code GET /.well-known/oauth-protected-resource[/**]} with RFC 9728 metadata claiming
 * {@code tls_client_certificate_bound_access_tokens: true} — false for this API. The owner ruled
 * the path suppressed (D-05) and, on 2026-10-05, chose "anon-401-parity" for how: a caller with
 * NO credentials gets the 401 every other protected route gives (through
 * {@link ProblemDetailAuthenticationEntryPoint}, so the same challenge and document), and a
 * caller presenting credentials gets the application's 404 not-found document. Neither answer
 * ever reaches the framework filter, so the false claim is unreachable.
 *
 * <p>A REAL entry point and a REAL Jackson-3 mapper carrying Boot's {@code ProblemDetailJacksonMixin},
 * not mocks: a mock would return nulls and every body assertion would pass on "null" (the
 * issue #413 lesson).
 */
class ProtectedResourceMetadataSuppressionFilterTest {

    private static final String PATH = "/.well-known/oauth-protected-resource";

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();
    private final ProtectedResourceMetadataSuppressionFilter filter =
            new ProtectedResourceMetadataSuppressionFilter(jsonMapper,
                    new ProblemDetailAuthenticationEntryPoint(jsonMapper));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    // --- no credentials: the 401 any protected route gives (anon-401-parity) ---

    static Stream<String> suppressedPaths() {
        return Stream.of(PATH, PATH + "/", PATH + "/anything", PATH + "/a/b");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("suppressedPaths")
    void anonymousCallerGetsThePlainBearer401AndTheChainIsNeverCalled(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(get(path), response, chain);

        assertNull(chain.getRequest(), "the framework metadata filter must never be reached");
        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertTrue(response.getContentType().startsWith("application/problem+json"), response.getContentType());
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertEquals("https://jtoye.uk/errors/unauthorized", body.path("type").asString());
        assertEquals("Unauthorized", body.path("title").asString());
        assertEquals(401, body.path("status").asInt());
        assertEquals("Authentication failed", body.path("detail").asString());
        assertFalse(response.getContentAsString().contains("tls_client_certificate_bound_access_tokens"));
    }

    /**
     * An Authorization header that is not a bearer credential is no credential to this resource
     * server (its {@code BearerTokenResolver} resolves nothing), exactly as on every other route.
     */
    @Test
    void nonBearerAuthorizationIsAnonymousToThisApi() throws Exception {
        MockHttpServletRequest request = get(PATH);
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
    }

    /**
     * A malformed bearer header is refused the way {@code BearerTokenAuthenticationFilter} refuses
     * it on every other route: the resolver's RFC 6750 {@code invalid_request} goes to the same
     * entry point, so the status (400) and the challenge are the framework's.
     */
    @Test
    void malformedBearerIsRefusedWithTheFrameworksInvalidRequest() throws Exception {
        MockHttpServletRequest request = get(PATH);
        request.addHeader("Authorization", "Bearer not a token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(400, response.getStatus());
        assertTrue(response.getHeader("WWW-Authenticate").startsWith("Bearer error=\"invalid_request\""),
                response.getHeader("WWW-Authenticate"));
        assertFalse(response.getHeader("WWW-Authenticate").contains("resource_metadata"));
        assertEquals("https://jtoye.uk/errors/unauthorized",
                jsonMapper.readTree(response.getContentAsString()).path("type").asString());
    }

    // --- credentials present: the application's 404 not-found document ---

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("suppressedPaths")
    void bearerCallerGetsTheNotFoundDocumentAndTheChainIsNeverCalled(String path) throws Exception {
        MockHttpServletRequest request = get(path);
        request.addHeader("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.e30.c2ln");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest(), "the framework metadata filter must never be reached");
        assertNotFoundDocument(response, path);
    }

    /** A caller the chain has already authenticated is a caller with credentials. */
    @Test
    void alreadyAuthenticatedCallerGetsTheNotFoundDocument() throws Exception {
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new TestingAuthenticationToken("user", null, "ROLE_user")));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(get(PATH), response, chain);

        assertNull(chain.getRequest());
        assertNotFoundDocument(response, PATH);
    }

    private void assertNotFoundDocument(MockHttpServletResponse response, String path) throws Exception {
        assertEquals(404, response.getStatus());
        // Exactly what GlobalExceptionHandler's NoResourceFoundException mapping sends: the bare
        // media type, no charset parameter.
        assertEquals("application/problem+json", response.getContentType());
        assertNull(response.getHeader("WWW-Authenticate"), "a 404 carries no challenge");
        JsonNode body = jsonMapper.readTree(response.getContentAsByteArray());
        assertEquals("https://jtoye.uk/errors/not-found", body.path("type").asString());
        assertEquals("Not Found", body.path("title").asString());
        assertEquals(404, body.path("status").asInt());
        assertEquals("Resource not found", body.path("detail").asString());
        assertEquals(path, body.path("instance").asString());
        assertEquals(5, body.size(), "no member beyond the five of the not-found document: " + body);
    }

    // --- everything the framework filter does not serve passes through untouched ---

    /** Method + path the framework's own matcher ({@code GET /.well-known/oauth-protected-resource/**}) does not match. */
    static Stream<Arguments> passThrough() {
        return Stream.of(
                Arguments.of("GET", "/.well-known/openid-configuration"),
                Arguments.of("GET", "/.well-known/oauth-authorization-server"),
                Arguments.of("GET", "/.well-known/oauth-protected-resource-x"),
                Arguments.of("GET", "/actuator/health"),
                Arguments.of("GET", "/api/v1/products"),
                Arguments.of("POST", PATH),
                Arguments.of("HEAD", PATH));
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("passThrough")
    void everyOtherRequestPassesThroughUntouched(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertSame(request, chain.getRequest(), "the chain must be called with the request itself");
        assertSame(response, chain.getResponse(), "the chain must be called with the response itself");
        assertEquals(200, response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
        assertTrue(response.getHeaderNames().isEmpty(), "headers written: " + response.getHeaderNames());
    }
}
