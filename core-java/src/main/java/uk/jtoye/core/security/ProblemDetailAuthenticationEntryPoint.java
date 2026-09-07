package uk.jtoye.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

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
 * <p>The body is written with the application's own {@link ObjectMapper} so it serialises
 * exactly as {@code GlobalExceptionHandler}'s do: Spring Boot registers
 * {@code ProblemDetailJacksonMixin} on that bean, and a hand-rolled JSON string here would
 * be the very "resembles the contract" defect this fixes (the issue #413 argument, applied
 * to the same class of surface).
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** The stable type every 401 carries, matching {@code GlobalExceptionHandler.handleAuthentication}. */
    static final String UNAUTHORIZED_TYPE = "https://jtoye.uk/errors/unauthorized";

    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();
    private final ObjectMapper objectMapper;

    public ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // Status + WWW-Authenticate first: the challenge is the part a conforming client
        // acts on, and it must survive unchanged.
        delegate.commence(request, response, authException);

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
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
