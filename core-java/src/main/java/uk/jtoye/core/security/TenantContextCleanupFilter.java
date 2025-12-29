package uk.jtoye.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that ensures TenantContext ThreadLocal is always cleared after request processing.
 * This prevents memory leaks and tenant context bleeding between requests in thread pools.
 *
 * Executes with HIGHEST_PRECEDENCE to ensure cleanup happens after all other filters.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantContextCleanupFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextCleanupFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            // ALWAYS clear tenant context, even on exception
            TenantContext.clear();
            log.debug("Cleared tenant context after request: {} {}",
                      request.getMethod(), request.getRequestURI());
        }
    }
}
