package uk.jtoye.core.gdpr;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.jtoye.core.exception.DsarRateLimitExceededException;
import uk.jtoye.core.security.ClientIpResolver;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The DSAR intake's own client-IP bucket (Phase 31, threat T-31-05-02).
 *
 * <h2>Why the platform limiter is not enough</h2>
 *
 * {@code RateLimitInterceptor} does bound this path — {@code /api/v1/public/**} falls into its
 * IP-keyed {@code rl:public:} tier at 30/min — but that tier is sized for storefront browsing.
 * <b>An unverified erasure request is a destructive action anybody on the internet can aim at
 * anybody else</b>, so 30 a minute is not a bound, it is a budget. The limit here is deliberately
 * far tighter than the 100/min platform default and tighter than the public tier: a genuine data
 * subject lodges one request; even a whole household behind one NAT address does not lodge five in
 * an hour. Both limits apply, and this one is the binding constraint.
 *
 * <h2>Why this bucket is in-process and NOT behind the shared Redis proxy manager</h2>
 *
 * This is the deliberate trade, and it is recorded rather than hidden. The shared limiter is
 * distributed and exact — and it is switched off wholesale by {@code rate-limiting.enabled=false},
 * and it fails OPEN with an alarm on any Redis error, both of which are correct for a throughput
 * control and wrong for a destructive-action guard. A protection that disappears when a config flag
 * flips or a cache blinks is the fail-open shape this project keeps paying for. An in-process
 * bucket has no such off switch and no dependency to lose, so it holds in every environment
 * including the integration test that proves it holds.
 *
 * <p>The cost is stated plainly: the bound is <b>per instance</b>, so with N replicas the global
 * ceiling is N times the configured limit. That is a weaker guarantee than a distributed counter
 * and a strictly stronger one than nothing; the distributed public tier still sits on top. A
 * distributed DSAR bucket is a worthwhile follow-up, not a precondition.
 *
 * <h2>The map is bounded, and it fails closed when it fills</h2>
 *
 * An unbounded IP-keyed map on an unauthenticated endpoint is itself a memory-amplification
 * surface. Above {@code maxTrackedClients} the limiter first evicts fully-refilled (idle) buckets,
 * which discards no live state because a full bucket is indistinguishable from a new one; if the
 * map is still full, further clients share ONE overflow bucket. That is deliberately fail-closed:
 * during an address-spraying flood legitimate callers are throttled together rather than the map
 * being cleared, because clearing it is exactly the reset an attacker would be trying to cause.
 */
@Component
public class DsarIntakeRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DsarIntakeRateLimiter.class);

    /** Requests per client per hour. Injected — never a hardcoded literal. */
    @Value("${jtoye.gdpr.dsar.rate-limit.requests-per-hour:5}")
    private int requestsPerHour;

    /** Upper bound on tracked clients before eviction and then the shared overflow bucket. */
    @Value("${jtoye.gdpr.dsar.rate-limit.max-tracked-clients:10000}")
    private int maxTrackedClients;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Lazily built; guards every client once {@link #maxTrackedClients} is reached. */
    private volatile Bucket overflowBucket;

    /**
     * Consume one token for the request's client, or refuse.
     *
     * @throws DsarRateLimitExceededException the client is over its limit (rendered as an RFC 7807
     *                                        429 by {@code GlobalExceptionHandler})
     */
    public void checkAllowed(HttpServletRequest request) {
        String clientIp = ClientIpResolver.resolveClientIp(request);
        ConsumptionProbe probe = bucketFor(clientIp).tryConsumeAndReturnRemaining(1);

        if (!probe.isConsumed()) {
            long retryAfter = Math.max(1L, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            log.warn("DSAR intake rate limit exceeded for client {} — retry after {}s",
                    clientIp, retryAfter);
            throw new DsarRateLimitExceededException(retryAfter);
        }
    }

    private Bucket bucketFor(String clientIp) {
        Bucket existing = buckets.get(clientIp);
        if (existing != null) {
            return existing;
        }
        if (buckets.size() >= maxTrackedClients) {
            evictIdleBuckets();
            if (buckets.size() >= maxTrackedClients) {
                return overflowBucket();
            }
        }
        return buckets.computeIfAbsent(clientIp, key -> newBucket());
    }

    /**
     * Drop buckets that are back at full capacity. A full bucket carries no state a new one would
     * not also have, so evicting it cannot hand anybody extra tokens — which is what makes this
     * safe and a blanket clear unsafe.
     */
    private void evictIdleBuckets() {
        buckets.entrySet().removeIf(entry -> entry.getValue().getAvailableTokens() >= requestsPerHour);
    }

    private Bucket overflowBucket() {
        Bucket local = overflowBucket;
        if (local == null) {
            synchronized (this) {
                local = overflowBucket;
                if (local == null) {
                    local = newBucket();
                    overflowBucket = local;
                    log.warn("DSAR intake limiter is tracking {} clients — further clients share "
                            + "one overflow bucket until idle entries are evicted", maxTrackedClients);
                }
            }
        }
        return local;
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(requestsPerHour)
                        .refillIntervally(requestsPerHour, Duration.ofHours(1))
                        .build())
                .build();
    }
}
