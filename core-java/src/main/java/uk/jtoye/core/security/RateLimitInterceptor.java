package uk.jtoye.core.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Rate limiting interceptor that enforces tenant-aware rate limits using Bucket4j + Redis.
 *
 * Features:
 * - Tenant-aware rate limiting (per tenant, not global)
 * - Distributed rate limiting across multiple instances (via Redis)
 * - Configurable rate limits (100 req/min default)
 * - Proper HTTP 429 responses with retry headers
 * - Excludes health check and actuator endpoints
 *
 * Rate limit tiers:
 * - Standard tier: 100 requests/minute per tenant
 * - Premium tier: 1000 requests/minute per tenant (future enhancement)
 * - Internal tier: No rate limiting (for service-to-service calls)
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final String RATE_LIMIT_KEY_PREFIX = "rate_limit::";

    // HTTP Headers for rate limit information
    private static final String HEADER_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RESET = "X-RateLimit-Reset";
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    @Autowired(required = false)
    private ProxyManager<String> proxyManager;

    @Value("${rate-limiting.enabled:true}")
    private boolean rateLimitingEnabled;

    @Value("${rate-limiting.default-limit:100}")
    private int defaultLimit;

    @Value("${rate-limiting.burst-capacity:20}")
    private int burstCapacity;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Skip if rate limiting is disabled
        if (!rateLimitingEnabled || proxyManager == null) {
            return true;
        }

        // Skip rate limiting for health check and actuator endpoints
        String requestPath = request.getRequestURI();
        if (isExcludedPath(requestPath)) {
            return true;
        }

        // Get tenant ID from TenantContext
        Optional<UUID> tenantIdOpt = TenantContext.get();
        if (tenantIdOpt.isEmpty()) {
            logger.warn("Rate limiting skipped - no tenant context found for request: {}", requestPath);
            return true; // Allow request to proceed (tenant filter should have set context)
        }

        UUID tenantId = tenantIdOpt.get();
        String rateLimitKey = RATE_LIMIT_KEY_PREFIX + tenantId.toString();

        // Create bucket configuration supplier
        Supplier<BucketConfiguration> configSupplier = () -> createBucketConfiguration(tenantId);

        // Get or create bucket for this tenant
        var bucket = proxyManager.builder().build(rateLimitKey, configSupplier);

        // Try to consume 1 token
        var probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            // Request allowed - add rate limit headers
            response.setHeader(HEADER_LIMIT, String.valueOf(defaultLimit));
            response.setHeader(HEADER_REMAINING, String.valueOf(probe.getRemainingTokens()));
            response.setHeader(HEADER_RESET, String.valueOf(System.currentTimeMillis() / 1000 + 60)); // Reset in 60 seconds

            logger.debug("Rate limit check passed for tenant {} - {} tokens remaining", tenantId, probe.getRemainingTokens());
            return true;
        } else {
            // Rate limit exceeded - return 429
            long waitForRefill = probe.getNanosToWaitForRefill() / 1_000_000_000; // Convert to seconds
            response.setStatus(429); // HTTP 429 Too Many Requests
            response.setHeader(HEADER_LIMIT, String.valueOf(defaultLimit));
            response.setHeader(HEADER_REMAINING, "0");
            response.setHeader(HEADER_RESET, String.valueOf(System.currentTimeMillis() / 1000 + waitForRefill));
            response.setHeader(HEADER_RETRY_AFTER, String.valueOf(waitForRefill));
            response.setContentType("application/json");
            response.getWriter().write(String.format(
                "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Please try again in %d seconds.\",\"tenantId\":\"%s\"}",
                waitForRefill, tenantId
            ));

            logger.warn("Rate limit exceeded for tenant {} on path {} - retry after {} seconds",
                       tenantId, requestPath, waitForRefill);
            return false;
        }
    }

    /**
     * Creates bucket configuration for a tenant.
     * Currently uses standard tier (100 req/min) for all tenants.
     * Future enhancement: Lookup tenant tier from database/cache.
     *
     * @param tenantId the tenant UUID
     * @return BucketConfiguration with appropriate rate limits
     */
    private BucketConfiguration createBucketConfiguration(UUID tenantId) {
        // Standard tier: 100 requests/minute with burst capacity of 20
        // This allows brief bursts above the rate limit while maintaining average rate
        Bandwidth limit = Bandwidth.builder()
                .capacity(defaultLimit + burstCapacity)
                .refillIntervally(defaultLimit, Duration.ofMinutes(1))
                .build();

        return BucketConfiguration.builder()
                .addLimit(limit)
                .build();
    }

    /**
     * Determines if a request path should be excluded from rate limiting.
     * Excludes health checks, actuator endpoints, and Swagger UI.
     *
     * @param path the request path
     * @return true if path should be excluded, false otherwise
     */
    private boolean isExcludedPath(String path) {
        return path.startsWith("/actuator/") ||
               path.equals("/health") ||
               path.startsWith("/swagger-ui/") ||
               path.startsWith("/v3/api-docs");
    }

    /**
     * Future enhancement: Determine tenant tier from database/cache.
     * For now, all tenants are treated as standard tier.
     *
     * @param tenantId the tenant UUID
     * @return "STANDARD", "PREMIUM", or "INTERNAL"
     */
    @SuppressWarnings("unused")
    private String getTenantTier(UUID tenantId) {
        // TODO: Query tenant service or cache to determine tier
        // For now, return standard tier for all tenants
        return "STANDARD";
    }
}
