package uk.jtoye.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API-10 (QA council 20260902-134741) unit contract for
 * {@link ProblemDetailAuthenticationEntryPoint}.
 *
 * <p>Live before the fix: {@code GET /api/v1/products} with no bearer returned
 * {@code HTTP 401} with {@code bytes=0} — the only status in the whole error-model sweep
 * that carried no {@code application/problem+json} document.
 *
 * <p>A REAL Jackson-3 {@link JsonMapper} with the {@code ProblemDetailJacksonMixin} Boot 4
 * registers on its auto-configured one, not a mock: a mock returns null and every body
 * assertion below would be asserting on the string "null" while looking green (the issue #413
 * lesson, same class of surface). That the entry point writes with Boot's REAL bean, byte for
 * byte, is proven through the chain by {@code UnauthenticatedProblemDetailIntegrationTest}.
 *
 * <p>Phase 38 D-04: Spring Security 7's {@code BearerTokenAuthenticationEntryPoint} always
 * appends {@code resource_metadata="…/.well-known/oauth-protected-resource"}, advertising
 * metadata this API does not serve. Every challenge below is asserted BY EXACT VALUE, and
 * {@link ProblemDetailAuthenticationEntryPoint#stripResourceMetadata(String)} is pinned by a
 * table that includes quoted commas and the parameter in every position.
 */
class ProblemDetailAuthenticationEntryPointTest {

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();
    private final ProblemDetailAuthenticationEntryPoint entryPoint =
            new ProblemDetailAuthenticationEntryPoint(jsonMapper);

    @Test
    void missingBearerReturnsProblemDocumentAndKeepsTheChallenge() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response,
                new InsufficientAuthenticationException("Full authentication is required"));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/problem+json"),
                "401 must carry the same media type as every other error, got: " + response.getContentType());

        // The RFC 7235 §4.1 challenge is the part a conforming client acts on. Filling in
        // the body must not cost it — asserted, not assumed. D-04: exactly "Bearer", so a
        // resource_metadata parameter (Security 7) cannot pass.
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertEquals(1, response.getHeaders("WWW-Authenticate").size(),
                "exactly one challenge, not a stripped one beside the original");

        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertEquals("https://jtoye.uk/errors/unauthorized", body.path("type").asString());
        assertEquals("Unauthorized", body.path("title").asString());
        assertEquals(401, body.path("status").asInt());
        assertEquals("Authentication failed", body.path("detail").asString());
    }

    /**
     * An invalid/expired token takes the other path into this entry point: the resource
     * server raises an {@link OAuth2AuthenticationException} whose {@code BearerTokenError}
     * carries the RFC 6750 detail. The challenge must still carry {@code error=} — the
     * body is additive, never a replacement for it. D-04: the whole challenge is asserted
     * exactly, so the framework's error semantics are kept and nothing else is added.
     */
    @Test
    void invalidTokenKeepsTheRfc6750ChallengeParameters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new OAuth2AuthenticationException(
                new BearerTokenError(BearerTokenErrorCodes.INVALID_TOKEN,
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "The token expired", null)));

        assertEquals(401, response.getStatus());
        assertEquals("Bearer error=\"invalid_token\", error_description=\"The token expired\"",
                response.getHeader("WWW-Authenticate"));

        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertEquals("https://jtoye.uk/errors/unauthorized", body.path("type").asString());
        assertEquals(401, body.path("status").asInt());

        // The body stays generic: WHY the token failed belongs in the challenge, which a
        // client is expected to read, not in a document an unauthenticated caller can mine.
        assertTrue(body.path("detail").asString().equals("Authentication failed"),
                "the body must not restate the token failure reason: " + body.path("detail").asString());
    }

    /** Input header value -> expected value after {@code stripResourceMetadata}. */
    static Stream<Arguments> challenges() {
        return Stream.of(
                // the Security 7 missing-token challenge
                Arguments.of("Bearer resource_metadata=\"http://h/.well-known/oauth-protected-resource\"",
                        "Bearer"),
                // the Security 7 invalid-token challenge: parameter last
                Arguments.of("Bearer error=\"invalid_token\", error_description=\"x\", resource_metadata=\"u\"",
                        "Bearer error=\"invalid_token\", error_description=\"x\""),
                // parameter first
                Arguments.of("Bearer resource_metadata=\"u\", error=\"invalid_token\"",
                        "Bearer error=\"invalid_token\""),
                // parameter in the middle
                Arguments.of("Bearer error=\"invalid_token\", resource_metadata=\"u\", error_description=\"x\"",
                        "Bearer error=\"invalid_token\", error_description=\"x\""),
                // a quoted value containing commas, '=' and the parameter's own name is ONE value
                Arguments.of("Bearer error=\"invalid_token\", error_description=\"expired, renew; resource_metadata=\\\"d\\\"\", resource_metadata=\"u\"",
                        "Bearer error=\"invalid_token\", error_description=\"expired, renew; resource_metadata=\\\"d\\\"\""),
                // realm (setRealmName) is kept, and stays first
                Arguments.of("Bearer realm=\"api\", resource_metadata=\"u\"",
                        "Bearer realm=\"api\""),
                // auth-param names are case-insensitive (RFC 7235 §2.1)
                Arguments.of("Bearer Resource_Metadata=\"u\"", "Bearer"),
                // token (unquoted) values
                Arguments.of("Bearer error=invalid_token, resource_metadata=u",
                        "Bearer error=invalid_token"),
                // separators written without spaces are normalised to the framework's ", "
                Arguments.of("Bearer error=\"invalid_token\",resource_metadata=\"u\",scope=\"read\"",
                        "Bearer error=\"invalid_token\", scope=\"read\""));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("challenges")
    void stripResourceMetadataDropsOnlyThatParameter(String input, String expected) {
        assertEquals(expected, ProblemDetailAuthenticationEntryPoint.stripResourceMetadata(input));
    }

    /** Values that carry no resource_metadata, or that cannot be parsed safely, are returned unchanged. */
    static Stream<String> unchanged() {
        return Stream.of(
                "Bearer",
                "Bearer error=\"invalid_token\", error_description=\"x\"",
                "Bearer error=\"insufficient_scope\", error_description=\"The request requires higher privileges than provided by the access token.\", error_uri=\"https://tools.ietf.org/html/rfc6750#section-3.1\"",
                "Basic realm=\"x\"",
                // malformed (a stray character after a quoted value, then an unterminated
                // quote): not rewritten rather than guessed at
                "Bearer error=\"invalid_token, resource_metadata=\"u");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unchanged")
    void stripResourceMetadataLeavesOtherValuesUnchanged(String input) {
        assertEquals(input, ProblemDetailAuthenticationEntryPoint.stripResourceMetadata(input));
    }

    @Test
    void stripResourceMetadataOfNullIsNull() {
        assertNull(ProblemDetailAuthenticationEntryPoint.stripResourceMetadata(null));
    }

    /**
     * The wrapper rewrites the challenge whichever setter writes it and however the name is
     * cased; any other header passes through untouched.
     */
    @Test
    void wrapperRewritesTheChallengeOnBothSettersAndOnlyTheChallenge() {
        MockHttpServletResponse viaAdd = new MockHttpServletResponse();
        new ProblemDetailAuthenticationEntryPoint.ChallengeRewritingResponse(viaAdd)
                .addHeader("WWW-Authenticate", "Bearer resource_metadata=\"u\"");
        assertEquals("Bearer", viaAdd.getHeader("WWW-Authenticate"));

        MockHttpServletResponse viaSet = new MockHttpServletResponse();
        new ProblemDetailAuthenticationEntryPoint.ChallengeRewritingResponse(viaSet)
                .setHeader("www-authenticate", "Bearer error=\"invalid_token\", resource_metadata=\"u\"");
        assertEquals("Bearer error=\"invalid_token\"", viaSet.getHeader("WWW-Authenticate"));

        MockHttpServletResponse other = new MockHttpServletResponse();
        new ProblemDetailAuthenticationEntryPoint.ChallengeRewritingResponse(other)
                .setHeader("X-Note", "resource_metadata=\"u\"");
        assertEquals("resource_metadata=\"u\"", other.getHeader("X-Note"));
    }
}
