package uk.jtoye.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * API-10 (QA council 20260902-134741): make the 401 look like every other error.
 *
 * <p>The error-model sweep found 400/403/404/405/415/422/429/500 all returning
 * {@code application/problem+json} with a stable {@code https://jtoye.uk/errors/*} type —
 * and 401 alone returning <b>nothing</b> ({@code Content-Length: 0}). Spring Security's
 * default {@link BearerTokenAuthenticationEntryPoint} writes the status and the
 * {@code WWW-Authenticate} header but no body, and an entry point runs in the filter
 * chain, so {@code GlobalExceptionHandler}'s {@code @ExceptionHandler(AuthenticationException)}
 * — which produces exactly this document — is never reached.
 *
 * <p><b>What is preserved.</b> The default entry point is not replaced but wrapped: it
 * still sets the status and the RFC 6750 {@code WWW-Authenticate: Bearer} challenge
 * (including {@code error}/{@code error_description} for an invalid or expired token),
 * which RFC 7235 §4.1 requires and which is why a conforming client was never blind here.
 * Only the empty body is filled in.
 *
 * <p><b>What is deliberately NOT done.</b> {@code sessionCreationPolicy(STATELESS)} — the
 * other half of the finding's observation (a {@code JSESSIONID} on a stateless API) — is
 * tier HIGH: it changes {@code SecurityContextRepository} behaviour process-wide and needs
 * a census over the STOMP/WebSocket handshake and the springdoc paths before it ships.
 * Plan §4.1b: ship the problem document alone first.
 *
 * <p>The body is written with the application's own Jackson-3 {@link JsonMapper} (Boot 4's
 * auto-configured bean) so it serialises exactly as {@code GlobalExceptionHandler}'s do:
 * Spring Boot registers {@code ProblemDetailJacksonMixin} on that bean, so extension members
 * are flattened to the top level, and a hand-rolled JSON string here would be the very
 * "resembles the contract" defect this fixes (the issue #413 argument, applied to the same
 * class of surface). Its key order is Jackson 3's alphabetical default (owner decision
 * "jackson3-defaults", plan 38-05).
 *
 * <p><b>Phase 38 D-04 / D-05: no {@code resource_metadata} in the challenge.</b> Spring
 * Security 7's {@link BearerTokenAuthenticationEntryPoint} always appends
 * {@code resource_metadata="<scheme>://<request host>/.well-known/oauth-protected-resource"}
 * (RFC 9728). Its resolver can be replaced but not switched off: whatever it returns is still
 * written as a parameter. This API does not serve protected-resource metadata — RFC 9728 is
 * out of scope (D-04), and the framework's own metadata endpoint is not to be served either
 * (D-05) — so advertising that URL would send every client that follows it to a document that
 * does not exist, at an address derived from the request's {@code Host}. The delegate still
 * decides the status and the {@code error}/{@code error_description}/{@code error_uri}
 * parameters; it writes the challenge into a {@link ChallengeRewritingResponse}, which keeps
 * every parameter except {@code resource_metadata}. The value is parsed as an RFC 7235
 * auth-param list (a quoted value may contain commas, {@code =} and even the parameter's own
 * name), never cut with one regex over the whole header; a value that cannot be parsed is left
 * exactly as the framework wrote it. The result is the Boot 3.5 challenge: {@code Bearer} for a
 * missing token, {@code Bearer error="invalid_token", error_description="…", error_uri="…"} for a
 * bad one. Proven by {@code ProblemDetailAuthenticationEntryPointTest} (exact values plus a table
 * of parameter positions) and {@code UnauthenticatedProblemDetailIntegrationTest} (the real
 * chain); the fail-direction arm, a wrapper that passes the header through unchanged, turns both
 * red with {@code resource_metadata} in the asserted header (38-06 evidence).
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** The stable type every 401 carries, matching {@code GlobalExceptionHandler.handleAuthentication}. */
    static final String UNAUTHORIZED_TYPE = "https://jtoye.uk/errors/unauthorized";

    /** The RFC 9728 challenge parameter this API never sends (D-04). */
    static final String RESOURCE_METADATA = "resource_metadata";

    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();
    private final JsonMapper jsonMapper;

    public ProblemDetailAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // Status + WWW-Authenticate first: the challenge is the part a conforming client
        // acts on, and it must survive unchanged except for resource_metadata (D-04).
        delegate.commence(request, new ChallengeRewritingResponse(response), authException);

        if (response.isCommitted()) {
            return;
        }

        // The delegate owns the status (401 for a missing/invalid token, 400 for a
        // malformed Authorization header per RFC 6750 invalid_request). Read it back
        // rather than assuming, so the body can never contradict the status line.
        HttpStatus status = HttpStatus.resolve(response.getStatus());
        if (status == null) {
            status = HttpStatus.UNAUTHORIZED;
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Authentication failed");
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create(UNAUTHORIZED_TYPE));

        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(jsonMapper.writeValueAsString(problem));
    }

    /**
     * Drop the {@code resource_metadata} auth-param from a {@code WWW-Authenticate} value and keep
     * everything else, in order, joined with the framework's {@code ", "}. The value is read as
     * {@code scheme 1*SP auth-param *( OWS "," OWS auth-param )} (RFC 7235 §2.1), where an
     * auth-param is {@code token BWS "=" BWS ( token / quoted-string )} and the name is
     * case-insensitive. Returns the input unchanged when it carries no such parameter, or when it
     * cannot be parsed that way (token68, an unterminated quote, a stray character): a value this
     * method does not understand is not rewritten.
     */
    static String stripResourceMetadata(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        int len = headerValue.length();
        int i = headerValue.indexOf(' ');
        if (i < 0) {
            return headerValue;
        }
        String scheme = headerValue.substring(0, i);
        List<String> kept = new ArrayList<>();
        boolean dropped = false;
        while (i < len) {
            char c = headerValue.charAt(i);
            if (c == ' ' || c == '\t' || c == ',') {
                i++;
                continue;
            }
            int start = i;
            while (i < len && isTokenChar(headerValue.charAt(i))) {
                i++;
            }
            if (i == start) {
                return headerValue;
            }
            String name = headerValue.substring(start, i);
            i = skipWhitespace(headerValue, i);
            if (i >= len || headerValue.charAt(i) != '=') {
                return headerValue;
            }
            i = skipWhitespace(headerValue, i + 1);
            if (i >= len) {
                return headerValue;
            }
            if (headerValue.charAt(i) == '"') {
                i = endOfQuotedString(headerValue, i);
                if (i < 0) {
                    return headerValue;
                }
            } else {
                int valueStart = i;
                while (i < len && isTokenChar(headerValue.charAt(i))) {
                    i++;
                }
                if (i == valueStart) {
                    return headerValue;
                }
            }
            String param = headerValue.substring(start, i);
            i = skipWhitespace(headerValue, i);
            if (i < len && headerValue.charAt(i) != ',') {
                return headerValue;
            }
            if (RESOURCE_METADATA.equalsIgnoreCase(name)) {
                dropped = true;
            } else {
                kept.add(param);
            }
        }
        if (!dropped) {
            return headerValue;
        }
        return kept.isEmpty() ? scheme : scheme + " " + String.join(", ", kept);
    }

    /** Index just past the closing quote of the quoted-string opening at {@code open}, or -1. */
    private static int endOfQuotedString(String s, int open) {
        int i = open + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i + 1;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** RFC 7230 §3.2.6 tchar. */
    private static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    /**
     * The response the delegate writes its challenge into: {@code WWW-Authenticate} (name compared
     * case-insensitively, through either setter) is stored as {@link #stripResourceMetadata};
     * every other header, the status and the body go to the real response untouched.
     */
    static final class ChallengeRewritingResponse extends HttpServletResponseWrapper {

        ChallengeRewritingResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, rewrite(name, value));
        }

        @Override
        public void addHeader(String name, String value) {
            super.addHeader(name, rewrite(name, value));
        }

        private static String rewrite(String name, String value) {
            return HttpHeaders.WWW_AUTHENTICATE.equalsIgnoreCase(name) ? stripResourceMetadata(value) : value;
        }
    }
}
