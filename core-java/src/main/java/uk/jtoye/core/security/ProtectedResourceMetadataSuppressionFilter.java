package uk.jtoye.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Phase 38 D-05 (RED stub, 38-06 Task 2): passes every request through unchanged, so the tests
 * that pin the suppression fail on their assertions rather than on compilation.
 */
public final class ProtectedResourceMetadataSuppressionFilter extends OncePerRequestFilter {

    private final JsonMapper jsonMapper;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public ProtectedResourceMetadataSuppressionFilter(JsonMapper jsonMapper,
                                                      AuthenticationEntryPoint authenticationEntryPoint) {
        this.jsonMapper = jsonMapper;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, response);
    }
}
