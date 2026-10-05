package uk.jtoye.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;

/**
 * Phase 38 D-05: {@code /.well-known/oauth-protected-resource} is not served.
 *
 * <p><b>What it suppresses.</b> Spring Security 7.1's resource-server configurer always installs
 * {@code OAuth2ProtectedResourceMetadataFilter}; it cannot be switched off, and its request
 * matcher and class are final. It answers {@code GET /.well-known/oauth-protected-resource} and
 * every path under it with RFC 9728 metadata whose {@code resource} is derived from the request's
 * {@code Host} and which claims {@code tls_client_certificate_bound_access_tokens: true}. That claim
 * is false for this API: it validates plain bearer JWTs and binds no token to a client certificate.
 * The owner ruled the path suppressed and corrected metadata NOT served (D-05); RFC 9728 stays out
 * of scope, as in D-04, which is also why {@link ProblemDetailAuthenticationEntryPoint} drops the
 * {@code resource_metadata} challenge parameter that would point clients here.
 *
 * <p><b>What it answers instead</b> (owner decision 2026-10-05, "anon-401-parity"). On Boot 3.5.16
 * the path was simply unmapped (38-02 baseline): a caller with no credentials got the 401 every
 * protected route gives, and an authenticated caller got the application's 404. This filter keeps
 * both answers:
 * <ul>
 *   <li>no credentials: {@link AuthenticationEntryPoint#commence} on the same entry point the chain
 *       uses, so the status, the plain {@code Bearer} challenge and the problem document are those
 *       of any other 401. "Credentials" means what this resource server accepts: a bearer token as
 *       its {@link DefaultBearerTokenResolver} resolves it (the chain configures no other
 *       resolver), or a context already authenticated. A non-bearer {@code Authorization} header is
 *       no credential here, exactly as on every other route; a malformed bearer header is refused
 *       with the resolver's own RFC 6750 error (in Security 7.1.1, {@code invalid_token} "Bearer
 *       token is malformed"), as {@code BearerTokenAuthenticationFilter} refuses it;</li>
 *   <li>credentials present: the 404 not-found document {@code GlobalExceptionHandler} sends for an
 *       unmapped path ({@code NoResourceFoundException}), member for member, with
 *       {@code instance} set to the request path as Spring MVC sets it.</li>
 * </ul>
 * <b>Recorded residual:</b> this filter answers before authentication, so a well-formed but invalid
 * or expired bearer is treated as a credential and gets the 404, where Boot 3.5.16 gave
 * {@code 401 Bearer error="invalid_token"} (38-06 evidence; ADR-0006).
 *
 * <p><b>Scope and placement.</b> It matches exactly what the framework filter serves, with the same
 * matcher ({@code GET} + {@code /.well-known/oauth-protected-resource/**} through
 * {@link PathPatternRequestMatcher#withDefaults()}); every other request passes through untouched.
 * It is NOT a {@code @Component}: as a bean it would also be auto-registered as a plain servlet
 * filter outside the security chain. {@code SecurityConfig} registers it on the chain after
 * {@code CorsFilter}, so its responses carry the security headers and the CORS {@code Vary} of every
 * other response, and therefore ahead of the framework's metadata filter, so the false claim is
 * unreachable for every caller.
 *
 * <p><b>Proof.</b> {@code ProtectedResourceMetadataSuppressionFilterTest} (both caller kinds, every
 * path form, pass-through) and {@code UnauthenticatedProblemDetailIntegrationTest} (the real chain:
 * the same 401 as any protected route, the same 404 as an unmapped path, filter order, headers).
 * Fail-direction arm (38-06 evidence): with the registration removed, the path answers 200 with the
 * framework's body, {@code tls_client_certificate_bound_access_tokens} included, and those tests go
 * red.
 */
public final class ProtectedResourceMetadataSuppressionFilter extends OncePerRequestFilter {

    /** The RFC 9728 well-known path the framework serves (and its {@code /**} subtree). */
    static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    /** The stable type of the application's 404, matching {@code GlobalExceptionHandler.handleNoResourceFound}. */
    static final String NOT_FOUND_TYPE = "https://jtoye.uk/errors/not-found";

    /** The framework filter's own matcher, rebuilt: suppress exactly what it would serve. */
    private final RequestMatcher metadataRequests =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, METADATA_PATH + "/**");

    private final BearerTokenResolver bearerTokenResolver = new DefaultBearerTokenResolver();
    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    private final JsonMapper jsonMapper;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public ProtectedResourceMetadataSuppressionFilter(JsonMapper jsonMapper,
                                                      AuthenticationEntryPoint authenticationEntryPoint) {
        this.jsonMapper = jsonMapper;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !metadataRequests.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // The chain is deliberately NOT called: the framework's metadata filter is next in line.
        if (isAuthenticated()) {
            writeNotFound(request, response);
            return;
        }
        String token;
        try {
            token = bearerTokenResolver.resolve(request);
        } catch (OAuth2AuthenticationException malformed) {
            authenticationEntryPoint.commence(request, response, malformed);
            return;
        }
        if (token == null) {
            authenticationEntryPoint.commence(request, response,
                    new InsufficientAuthenticationException("Full authentication is required to access this resource"));
            return;
        }
        writeNotFound(request, response);
    }

    private boolean isAuthenticated() {
        Authentication current = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        return current != null && current.isAuthenticated() && !trustResolver.isAnonymous(current);
    }

    private void writeNotFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found");
        problem.setTitle("Not Found");
        problem.setType(URI.create(NOT_FOUND_TYPE));
        problem.setInstance(instanceOf(request));

        response.setStatus(HttpStatus.NOT_FOUND.value());
        // The bare media type, as Spring MVC sends GlobalExceptionHandler's 404; the bytes are UTF-8.
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(jsonMapper.writeValueAsBytes(problem));
    }

    /** The request path, as Spring MVC fills a ProblemDetail's empty {@code instance}; none if it is not a URI. */
    private static URI instanceOf(HttpServletRequest request) {
        try {
            return URI.create(request.getRequestURI());
        } catch (IllegalArgumentException notAUri) {
            return null;
        }
    }
}
