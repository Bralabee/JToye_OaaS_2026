package uk.jtoye.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import uk.jtoye.core.tenant.TenantLifecycleService;
import uk.jtoye.core.tenant.TenantStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant lifecycle enforcement (issue #102 [P2-11] AC1): rejects API traffic
 * for SUSPENDED/OFFBOARDED tenants with 403, tenant-wide.
 *
 * <p><b>Why an interceptor (not the JWT filter):</b> {@code preHandle} runs
 * after BOTH tenant-resolution filters ({@code JwtTenantFilter} for JWT claims,
 * dev/test {@code TenantFilter} for the X-Tenant-Id header), so a single check
 * on {@link TenantContext} covers every tenant-resolution path — mirroring the
 * {@code RateLimitInterceptor} precedent. The lookup is served from
 * {@link TenantLifecycleService}'s TTL status cache, so the steady-state cost
 * is a map read, not a DB query (eviction path documented on the service).
 *
 * <p><b>Exemptions:</b>
 * <ul>
 *   <li>Health/actuator/swagger — same exclusions as the rate limiter.</li>
 *   <li>{@code /public/**} + {@code /api/v1/public/**} — tenant-less at this
 *       point (the storefront resolves its tenant from the shop slug inside
 *       the service), and the Stripe webhook lives here and must never be
 *       blocked. Suspended-tenant storefront blocking is a documented
 *       follow-up (belongs with the onboarding state machine, the sole writer
 *       of {@code Shop.published}).</li>
 *   <li>{@code /api/v1/admin/tenants/**} — the lifecycle-management surface
 *       itself, so a platform admin can never be locked out of un-suspending
 *       a tenant (including their own).</li>
 *   <li>Requests with no {@link TenantContext} — nothing to enforce against.</li>
 * </ul>
 */
@Component
public class TenantStatusInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantStatusInterceptor.class);

    private final TenantLifecycleService tenantLifecycleService;

    /**
     * Resolves the lifecycle service lazily so this interceptor (included in
     * {@code @WebMvcTest} slices because it implements {@link HandlerInterceptor})
     * can exist in contexts where the service layer is absent — mirroring the
     * {@code TenantCacheEvictor}/{@code RateLimitInterceptor} ObjectProvider
     * pattern. With no service available enforcement no-ops (nothing to look up).
     */
    @Autowired
    public TenantStatusInterceptor(ObjectProvider<TenantLifecycleService> tenantLifecycleServiceProvider) {
        this.tenantLifecycleService = tenantLifecycleServiceProvider.getIfAvailable();
    }

    /** Test-only constructor that accepts the service directly. */
    TenantStatusInterceptor(TenantLifecycleService tenantLifecycleService) {
        this.tenantLifecycleService = tenantLifecycleService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (tenantLifecycleService == null) {
            return true; // no service layer in this context (web slice tests) — nothing to enforce
        }
        String path = request.getRequestURI();
        if (isExemptPath(path)) {
            return true;
        }

        Optional<UUID> tenantIdOpt = TenantContext.get();
        if (tenantIdOpt.isEmpty()) {
            return true; // tenant-less request — nothing to enforce against
        }

        UUID tenantId = tenantIdOpt.get();
        TenantStatus status = tenantLifecycleService.statusOf(tenantId);
        if (status != TenantStatus.SUSPENDED && status != TenantStatus.OFFBOARDED) {
            return true;
        }

        // Structured audit log (parseable, alertable) — tenant id is the caller's
        // own, so echoing it in the body leaks nothing they don't already hold.
        log.warn("event=tenant_traffic_rejected tenant={} status={} path={}", tenantId, status, path);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(String.format(
                "{\"error\":\"Tenant %s\",\"message\":\"This tenant is %s and cannot access the API.\"}",
                status == TenantStatus.SUSPENDED ? "Suspended" : "Offboarded",
                status.name().toLowerCase()));
        return false;
    }

    /**
     * Paths never subject to tenant-status enforcement — see class javadoc.
     * The admin-tenants exemption is prefix-exact ({@code /api/v1/admin/tenants}
     * or a sub-path), not a broad {@code /api/v1/admin/**} carve-out.
     */
    private boolean isExemptPath(String path) {
        return path.startsWith("/actuator/")
                || path.equals("/health")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.equals("/public") || path.startsWith("/public/")
                || path.equals("/api/v1/public") || path.startsWith("/api/v1/public/")
                || path.equals("/api/v1/admin/tenants") || path.startsWith("/api/v1/admin/tenants/");
    }
}
