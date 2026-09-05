package uk.jtoye.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API-10 (QA council 20260902-134741) unit contract for
 * {@link ProblemDetailAuthenticationEntryPoint}.
 *
 * <p>Live before the fix: {@code GET /api/v1/products} with no bearer returned
 * {@code HTTP 401} with {@code bytes=0} — the only status in the whole error-model sweep
 * that carried no {@code application/problem+json} document.
 *
 * <p>A REAL {@link ObjectMapper} built the way Spring Boot builds its auto-configured one,
 * not a mock: a mock returns null and every body assertion below would be asserting on the
 * string "null" while looking green (the issue #413 lesson, same class of surface).
 */
class ProblemDetailAuthenticationEntryPointTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final ProblemDetailAuthenticationEntryPoint entryPoint =
            new ProblemDetailAuthenticationEntryPoint(objectMapper);

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
        // the body must not cost it — asserted, not assumed.
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals("https://jtoye.uk/errors/unauthorized", body.path("type").asText());
        assertEquals("Unauthorized", body.path("title").asText());
        assertEquals(401, body.path("status").asInt());
        assertEquals("Authentication failed", body.path("detail").asText());
    }

    /**
     * An invalid/expired token takes the other path into this entry point: the resource
     * server raises an {@link OAuth2AuthenticationException} whose {@code BearerTokenError}
     * carries the RFC 6750 detail. The challenge must still carry {@code error=} — the
     * body is additive, never a replacement for it.
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
        String challenge = response.getHeader("WWW-Authenticate");
        assertNotNull(challenge, "WWW-Authenticate must survive");
        assertTrue(challenge.contains("error=\"invalid_token\""),
                "the RFC 6750 error code must survive the body being added, got: " + challenge);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals("https://jtoye.uk/errors/unauthorized", body.path("type").asText());
        assertEquals(401, body.path("status").asInt());

        // The body stays generic: WHY the token failed belongs in the challenge, which a
        // client is expected to read, not in a document an unauthenticated caller can mine.
        assertTrue(body.path("detail").asText().equals("Authentication failed"),
                "the body must not restate the token failure reason: " + body.path("detail").asText());
    }
}
