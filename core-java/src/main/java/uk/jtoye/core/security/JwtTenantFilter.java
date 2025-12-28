package uk.jtoye.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Maps tenant from JWT claims into TenantContext after authentication.
 * JWT tenant_id takes PRIORITY over X-Tenant-ID header for security.
 * Claim preference order: tenant_id, tenantId, tid.
 * Falls back to header-based tenant only if JWT has no tenant claim.
 */
@Component
@Order(200) // run after core Spring Security filters
public class JwtTenantFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtTenantFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // JWT tenant_id has priority over X-Tenant-ID header
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                Optional<UUID> jwtTenant = extractTenant(jwt);
                if (jwtTenant.isPresent()) {
                    // Override any header-based tenant with JWT tenant
                    TenantContext.set(jwtTenant.get());
                    log.debug("Set tenant context from JWT: {}", jwtTenant.get());
                }
            }
            // If no JWT tenant and TenantContext is still empty, header-based tenant (if any) remains
            filterChain.doFilter(request, response);
        } finally {
            // Do not clear here; TenantFilter will clear at end of request.
        }
    }

    private Optional<UUID> extractTenant(Jwt jwt) {
        for (String claim : new String[]{"tenant_id", "tenantId", "tid"}) {
            Object v = jwt.getClaim(claim);
            if (v instanceof String s) {
                try {
                    return Optional.of(UUID.fromString(s));
                } catch (IllegalArgumentException ignore) {
                }
            }
        }
        return Optional.empty();
    }
}
