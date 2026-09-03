package uk.jtoye.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
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
    // issue #88 [P1-6]: distinct namespace for tenant-less public (guest) IP buckets so
    // a public flood can never consume a tenant's tokens and vice-versa (T-88-03).
    private static final String PUBLIC_RATE_LIMIT_KEY_PREFIX = "rl:public:";

    // HTTP Headers for rate limit information
    private static final String HEADER_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RESET = "X-RateLimit-Reset";
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    @Autowired(required = false)
    private ProxyManager<String> proxyManager;

    // issue #86 [P1-4]: alarm when the rate limiter fails open on a Redis error,
    // so a temporary throttling-disabled window is observable, not silent.
    // Null-safe MeterRegistry, mirroring PaymentEventOutboxFlusher.
    private final Counter failOpenCounter;

    /**
     * issue #413: the application's own mapper, so the 429 body is serialised exactly as
     * {@code GlobalExceptionHandler}'s ProblemDetail responses are. Spring Boot registers
     * {@code ProblemDetailJacksonMixin} on this bean, which is what flattens the extra
     * {@code retryAfterSeconds}/{@code tenantId} properties to top level instead of nesting
     * them under a {@code "properties"} object.
     */
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(ObjectProvider<MeterRegistry> meterRegistryProvider,
                                ObjectMapper objectMapper) {
        MeterRegistry reg = meterRegistryProvider.getIfAvailable();
        this.failOpenCounter = reg != null
                ? Counter.builder("jtoye.ratelimit.fail_open")
                    .description("Rate limiter degraded to fail-open because the Redis-backed bucket was unavailable (issue #86)")
                    .register(reg)
                : null;
        this.objectMapper = objectMapper;
    }

    @Value("${rate-limiting.enabled:true}")
    private boolean rateLimitingEnabled;

    @Value("${rate-limiting.default-limit:100}")
    private int defaultLimit;

    @Value("${rate-limiting.burst-capacity:20}")
    private int burstCapacity;

    // issue #88 [P1-6]: public (tenant-less) IP-keyed limiter. Injected from
    // rate-limiting.public.* with env override — never hardcoded literals.
    @Value("${rate-limiting.public.requests-per-minute:30}")
    private int publicRequestsPerMinute;

    @Value("${rate-limiting.public.burst:10}")
    private int publicBurstCapacity;

    @Value("${rate-limiting.public.window-seconds:60}")
    private int publicWindowSeconds;

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

        // issue #88 [P1-6]: tenant-less public storefront paths (/public/**) never carry a
        // TenantContext, so they would otherwise hit the tenant-less allow-through below and
        // bypass throttling entirely. Bound guest abuse with an IP-keyed bucket in its own
        // Redis namespace, independent of any tenant bucket, before the tenant logic runs.
        if (isPublicPath(requestPath)) {
            return handlePublicRateLimit(request, response, requestPath);
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

        // issue #86 [P1-4]: fail OPEN with an alarm on ANY Redis error. The
        // Redis-touching section (bucket build + token consume + probe handling)
        // is bounded by the explicit Lettuce command timeout configured on the
        // rate-limit client (RateLimitConfig). If Redis is unavailable the call
        // throws within that bound; we log at WARN, increment
        // jtoye.ratelimit.fail_open, and let the request proceed rather than
        // turning a Redis blip into a 500/60s hang. This is a deliberate
        // availability-over-enforcement trade-off for the outage window (alarmed
        // via the counter so operators can alert on it).
        try {
            // Get or create bucket for this tenant
            var bucket = proxyManager.builder().build(rateLimitKey, configSupplier);

            // Try to consume 1 token
            var probe = bucket.tryConsumeAndReturnRemaining(1);

            if (probe.isConsumed()) {
                // Request allowed - add rate limit headers
                response.setHeader(HEADER_LIMIT, String.valueOf(tenantBucketCapacity()));
                response.setHeader(HEADER_REMAINING, String.valueOf(probe.getRemainingTokens()));
                response.setHeader(HEADER_RESET, String.valueOf(System.currentTimeMillis() / 1000 + 60)); // Reset in 60 seconds

                logger.debug("Rate limit check passed for tenant {} - {} tokens remaining", tenantId, probe.getRemainingTokens());
                return true;
            } else {
                // Rate limit exceeded - return 429
                long waitForRefill = probe.getNanosToWaitForRefill() / 1_000_000_000; // Convert to seconds
                response.setStatus(429); // HTTP 429 Too Many Requests
                response.setHeader(HEADER_LIMIT, String.valueOf(tenantBucketCapacity()));
                response.setHeader(HEADER_REMAINING, "0");
                response.setHeader(HEADER_RESET, String.valueOf(System.currentTimeMillis() / 1000 + waitForRefill));
                response.setHeader(HEADER_RETRY_AFTER, String.valueOf(waitForRefill));
                // issue #413: tenantId stays on the TENANT path. It is a diagnostic an
                // authenticated caller already knows, and dropping it while reshaping the
                // body would be a regression by omission. The public path omits it — see
                // handlePublicRateLimit.
                writeProblem(response, waitForRefill, tenantId);

                logger.warn("Rate limit exceeded for tenant {} on path {} - retry after {} seconds",
                           tenantId, requestPath, waitForRefill);
                return false;
            }
        } catch (Exception e) {
            // Redis-backed rate limiter degraded — fail OPEN (availability over
            // enforcement) within the bounded command timeout, and alarm on it.
            if (failOpenCounter != null) {
                failOpenCounter.increment();
            }
            logger.warn("Rate limiter degraded — failing open for tenant {} on path {}: {}",
                    tenantId, requestPath, e.getMessage());
            return true;
        }
    }

    /**
     * issue #413: write the 429 body as RFC 7807, the shape every other error surface uses.
     *
     * <p><b>Why a real {@link ProblemDetail} and a real {@link ObjectMapper}, not hand-rolled
     * JSON.</b> The defect being fixed is precisely that this class hand-wrote a body which
     * only resembled the contract. Constructing the same type {@code GlobalExceptionHandler}
     * returns, and serialising it with the application's own mapper, means the shape cannot
     * drift from the documented one — {@code type}, {@code title}, {@code status},
     * {@code detail}, and a flattened {@code retryAfterSeconds}. Approximating it by hand
     * would reintroduce the same class of bug in a nicer-looking form.
     *
     * <p><b>{@code retryAfterSeconds} is a typed number, not prose.</b> The wait was previously
     * available only inside an English sentence, so the frontend had to mine it out with a
     * regex (#409/#410). An agent or client reading the documented contract now gets an
     * integer. The sentence stays too — it is what a human sees.
     *
     * <p><b>The charset is set explicitly.</b> {@code getWriter()} defaults to ISO-8859-1, and
     * the pre-fix responses really did go out as
     * {@code application/json;charset=ISO-8859-1} — measured in a browser 2026-08-01.
     *
     * @param tenantId included only on the tenant path; {@code null} on the public path, where
     *                 leaking a tenant id to an unauthenticated guest would be a disclosure.
     */
    private void writeProblem(HttpServletResponse response, long waitForRefill, UUID tenantId)
            throws java.io.IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                String.format("Rate limit exceeded. Please try again in %d seconds.", waitForRefill));
        problem.setTitle("Too Many Requests");
        problem.setType(URI.create("https://jtoye.uk/errors/rate-limited"));
        problem.setProperty("retryAfterSeconds", waitForRefill);
        if (tenantId != null) {
            problem.setProperty("tenantId", tenantId.toString());
        }

        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    /**
     * API-8 (QA council 20260902-134741): the capacity of a tenant bucket - the number of
     * tokens it actually holds - which is what {@code X-RateLimit-Limit} advertises.
     *
     * <p>The header used to advertise {@code defaultLimit}, the per-minute REFILL RATE,
     * while {@code X-RateLimit-Remaining} counted tokens out of this capacity. Live headers
     * therefore read {@code Limit: 100, Remaining: 119} on every response, and a client
     * computing {@code remaining / limit} for backoff got a ratio above 1.
     *
     * <p>This method is deliberately the ONLY place the sum is formed: it feeds both
     * {@link #createBucketConfiguration(UUID)} and the header, so the advertised limit and
     * the real bucket cannot drift apart again whatever the configured numbers are.
     */
    private long tenantBucketCapacity() {
        return (long) defaultLimit + burstCapacity;
    }

    /**
     * API-8: the capacity of the public IP-keyed bucket (issue #88), the second and
     * separately-configured limiter - {@code rate-limiting.public.requests-per-minute} +
     * {@code rate-limiting.public.burst}, overridden to 600/120 for the local compose
     * runtime (#409). Same defect, same single-source-of-truth remedy.
     */
    private long publicBucketCapacity() {
        return (long) publicRequestsPerMinute + publicBurstCapacity;
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
                .capacity(tenantBucketCapacity())
                .refillIntervally(defaultLimit, Duration.ofMinutes(1))
                .build();

        return BucketConfiguration.builder()
                .addLimit(limit)
                .build();
    }

    /**
     * issue #88 [P1-6]: identifies tenant-less public storefront paths ({@code /public/**})
     * that must be throttled by client IP rather than by tenant.
     *
     * <p>issue #97 [P2-6]: {@code /api/v1/public/**} is the canonical versioned alias of
     * the same surface (see {@code PublicStorefrontController}/{@code PaymentController})
     * and must share the SAME IP-keyed public tier — otherwise a guest flood could pick
     * the alias and fall through to the tenant-less allow-through.
     *
     * @param path the request path
     * @return true for {@code /public}, {@code /api/v1/public}, and any of their sub-paths
     */
    private boolean isPublicPath(String path) {
        return path.equals("/public") || path.startsWith("/public/")
                || path.equals("/api/v1/public") || path.startsWith("/api/v1/public/");
    }

    /**
     * issue #88 [P1-6]: enforces an IP-keyed rate limit for tenant-less public paths.
     *
     * <p>Keys the Redis bucket by {@code rl:public:{clientIp}} — a namespace distinct from
     * the tenant {@code rate_limit::} keyspace (T-88-03) — so a public flood cannot exhaust
     * a tenant's bucket and vice-versa. On limit exceeded it returns HTTP 429 with a
     * {@code Retry-After} header and a generic body (no tenantId is leaked to guests).
     *
     * <p>The Redis-touching section runs inside the SAME issue #86 fail-open-with-alarm
     * try/catch as the tenant path: on any Redis error it increments
     * {@code jtoye.ratelimit.fail_open} and returns true within the bounded Lettuce command
     * timeout, so a Redis blip degrades a public request to allowed rather than to a 500/hang.
     *
     * @return true if the request may proceed (allowed or failed-open); false if throttled (429)
     */
    private boolean handlePublicRateLimit(HttpServletRequest request, HttpServletResponse response, String requestPath) throws Exception {
        String clientIp = ClientIpResolver.resolveClientIp(request);
        String rateLimitKey = PUBLIC_RATE_LIMIT_KEY_PREFIX + clientIp;

        Supplier<BucketConfiguration> configSupplier = this::createPublicBucketConfiguration;

        try {
            var bucket = proxyManager.builder().build(rateLimitKey, configSupplier);
            var probe = bucket.tryConsumeAndReturnRemaining(1);

            if (probe.isConsumed()) {
                response.setHeader(HEADER_LIMIT, String.valueOf(publicBucketCapacity()));
                response.setHeader(HEADER_REMAINING, String.valueOf(probe.getRemainingTokens()));
                response.setHeader(HEADER_RESET, String.valueOf(System.currentTimeMillis() / 1000 + publicWindowSeconds));

                logger.debug("Public rate limit check passed for client {} - {} tokens remaining", clientIp, probe.getRemainingTokens());
                return true;
            } else {
                long waitForRefill = probe.getNanosToWaitForRefill() / 1_000_000_000; // Convert to seconds
                response.setStatus(429); // HTTP 429 Too Many Requests
                response.setHeader(HEADER_LIMIT, String.valueOf(publicBucketCapacity()));
                response.setHeader(HEADER_REMAINING, "0");
                response.setHeader(HEADER_RESET, String.valueOf(System.currentTimeMillis() / 1000 + waitForRefill));
                response.setHeader(HEADER_RETRY_AFTER, String.valueOf(waitForRefill));
                // Generic body — no tenantId to leak for a tenant-less guest request.
                writeProblem(response, waitForRefill, null);

                logger.warn("Public rate limit exceeded for client {} on path {} - retry after {} seconds",
                           clientIp, requestPath, waitForRefill);
                return false;
            }
        } catch (Exception e) {
            // issue #86 [P1-4]: fail OPEN with an alarm on ANY Redis error (availability over
            // enforcement) within the bounded command timeout — a Redis blip must not turn a
            // public request into a 500/hang.
            if (failOpenCounter != null) {
                failOpenCounter.increment();
            }
            logger.warn("Public rate limiter degraded — failing open for client {} on path {}: {}",
                    clientIp, requestPath, e.getMessage());
            return true;
        }
    }

    /**
     * Creates the bucket configuration for tenant-less public paths (issue #88 [P1-6]).
     * Capacity = publicRequestsPerMinute + publicBurstCapacity, refilling
     * publicRequestsPerMinute tokens per publicWindowSeconds. All values are injected
     * from {@code rate-limiting.public.*} (no hardcoded literals).
     *
     * @return BucketConfiguration for the public IP-keyed limiter
     */
    private BucketConfiguration createPublicBucketConfiguration() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(publicBucketCapacity())
                .refillIntervally(publicRequestsPerMinute, Duration.ofSeconds(publicWindowSeconds))
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
        // For now, return standard tier for all tenants
        return "STANDARD";
    }
}
