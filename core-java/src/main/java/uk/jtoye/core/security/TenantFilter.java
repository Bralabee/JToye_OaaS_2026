package uk.jtoye.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class TenantFilter extends OncePerRequestFilter {
    public static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(TENANT_HEADER);
        try {
            // Only set from header if no tenant has been established (e.g., by JWT)
            if (TenantContext.get().isEmpty() && header != null && !header.isBlank()) {
                try {
                    TenantContext.set(UUID.fromString(header.trim()));
                } catch (IllegalArgumentException e) {
                    response.sendError(400, "Invalid X-Tenant-Id header (must be UUID)");
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
