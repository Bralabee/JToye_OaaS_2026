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
 *
 * <h2>QA-council cluster S1 (SEC-1) — the IP bucket alone is not enough</h2>
 *
 * The bucket above keys on {@link ClientIpResolver#resolveClientIp}, which trusts the FIRST,
 * client-controllable {@code X-Forwarded-For} hop (an intentional, documented trade for the public
 * tier's rate limiter — see that class; it is NOT changed here). Rotating that header on every
 * request therefore defeats the IP bucket completely: each "new" IP gets its own fresh allowance, so
 * an attacker can drive an unbounded number of REAL verification emails at any address it names — an
 * email-bombing amplifier hiding behind a rate limiter, and a flood of {@code dsar_request} rows
 * alongside it.
 *
 * <p>Two further, independent gates close this, applied in sequence with an early exit (a request
 * refused by an earlier gate never debits a later one):
 *
 * <ul>
 *   <li><b>Per-subject-digest.</b> Keyed on {@code subject_email_sha256} (the SAME digest
 *       {@link DsarIntakeService} persists — computed via the shared {@link DsarSubjectDigest}, so
 *       the two can never drift apart), bounding how often ANY axis can be used to target one
 *       address. This is XFF-independent by construction and contract-safe: it is a pure function of
 *       submission frequency for an opaque digest, never a tenant lookup, so it cannot become a
 *       cross-tenant enumeration oracle the way varying the 202 body would.</li>
 *   <li><b>Global.</b> A single, un-keyed, process-wide ceiling. An attacker who rotates BOTH the
 *       IP and the target email defeats the first two gates trivially (a fresh key on both axes,
 *       every time) and would otherwise be free to queue tens of thousands of rows and emails an
 *       hour; this is the final backstop that bounds the AGGREGATE flood regardless of how many
 *       distinct keys are used.</li>
 * </ul>
 *
 * <p>The per-subject-digest map gets the exact same bounded/evict/overflow treatment as the IP map,
 * for the identical reason: the KEY SPACE here is a 64-hex-char digest, not the raw address, but an
 * attacker can still mint an unbounded number of distinct target addresses, so the memory-
 * amplification risk is the same shape.
 */
@Component
public class DsarIntakeRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DsarIntakeRateLimiter.class);

    /** Requests per client IP per hour. Injected — never a hardcoded literal. */
    @Value("${jtoye.gdpr.dsar.rate-limit.requests-per-hour:5}")
    private int requestsPerHour;

    /** Upper bound on tracked client IPs before eviction and then the shared overflow bucket. */
    @Value("${jtoye.gdpr.dsar.rate-limit.max-tracked-clients:10000}")
    private int maxTrackedClients;

    /**
     * Requests per DIGESTED subject email per day (SEC-1) — XFF-independent, closes the
     * rotating-client-IP amplification gap the IP bucket alone cannot. A day, not an hour: a
     * genuine subject lodges once; even a shared household address does not need five within an
     * hour, but might plausibly retry a couple of times over a day if a first email never arrived.
     */
    @Value("${jtoye.gdpr.dsar.rate-limit.requests-per-email-per-day:3}")
    private int requestsPerEmailPerDay;

    /** Upper bound on tracked subject digests before eviction and the shared overflow bucket. */
    @Value("${jtoye.gdpr.dsar.rate-limit.max-tracked-emails:10000}")
    private int maxTrackedEmails;

    /**
     * Aggregate ceiling across EVERY caller regardless of IP or target email (SEC-1) — the final
     * backstop when an attacker rotates both axes at once. Sized well above genuine platform-wide
     * DSAR volume (a legal-floor feature whose queue is usually empty) and well below a number that
     * would let a flood do real damage in an hour.
     */
    @Value("${jtoye.gdpr.dsar.rate-limit.global-requests-per-hour:200}")
    private int globalRequestsPerHour;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> emailBuckets = new ConcurrentHashMap<>();

    /** Lazily built; guards every client once {@link #maxTrackedClients} is reached. */
    private volatile Bucket overflowBucket;

    /** Lazily built; guards every subject digest once {@link #maxTrackedEmails} is reached. */
    private volatile Bucket emailOverflowBucket;

    /** Lazily built on first use; ONE bucket shared by every caller (SEC-1's aggregate ceiling). */
    private volatile Bucket globalBucket;

    /**
     * Consume one token on EACH of three independent axes for the request — client IP, target
     * subject digest, then the global ceiling — or refuse. Sequential with an early exit: a request
     * refused by an earlier gate never debits a later one, so the per-email and global buckets are
     * spent only by requests that would otherwise have been accepted.
     *
     * @param email the subject email the request names (digested here, never stored by this class —
     *              only {@link DsarIntakeService} persists the digest, and only that)
     * @throws DsarRateLimitExceededException any one of the three axes is over its limit (rendered
     *                                        as an RFC 7807 429 by {@code GlobalExceptionHandler})
     */
    public void checkAllowed(HttpServletRequest request, String email) {
        String clientIp = ClientIpResolver.resolveClientIp(request);
        consumeOrThrow(bucketFor(clientIp), "ip", clientIp);

        String subjectDigest = DsarSubjectDigest.of(email);
        consumeOrThrow(emailBucketFor(subjectDigest), "email-digest", subjectDigest);

        consumeOrThrow(globalBucket(), "global", "*");
    }

    private void consumeOrThrow(Bucket bucket, String axis, String key) {
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfter = Math.max(1L, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            log.warn("DSAR intake rate limit exceeded (axis={} key={}) — retry after {}s",
                    axis, key, retryAfter);
            throw new DsarRateLimitExceededException(retryAfter);
        }
    }

    private Bucket bucketFor(String clientIp) {
        Bucket existing = buckets.get(clientIp);
        if (existing != null) {
            return existing;
        }
        if (buckets.size() >= maxTrackedClients) {
            evictIdleBuckets(buckets, requestsPerHour);
            if (buckets.size() >= maxTrackedClients) {
                return overflowBucket();
            }
        }
        return buckets.computeIfAbsent(clientIp, key -> newBucket(requestsPerHour, Duration.ofHours(1)));
    }

    private Bucket emailBucketFor(String subjectDigest) {
        Bucket existing = emailBuckets.get(subjectDigest);
        if (existing != null) {
            return existing;
        }
        if (emailBuckets.size() >= maxTrackedEmails) {
            evictIdleBuckets(emailBuckets, requestsPerEmailPerDay);
            if (emailBuckets.size() >= maxTrackedEmails) {
                return emailOverflowBucket();
            }
        }
        return emailBuckets.computeIfAbsent(subjectDigest,
                key -> newBucket(requestsPerEmailPerDay, Duration.ofDays(1)));
    }

    /**
     * Drop buckets that are back at full capacity. A full bucket carries no state a new one would
     * not also have, so evicting it cannot hand anybody extra tokens — which is what makes this
     * safe and a blanket clear unsafe. Shared by the IP map and the subject-digest map, parameterised
     * on each map's own capacity so eviction never mistakes "idle at the OTHER axis's capacity" for
     * "idle at mine".
     */
    private static void evictIdleBuckets(Map<String, Bucket> pool, int capacity) {
        pool.entrySet().removeIf(entry -> entry.getValue().getAvailableTokens() >= capacity);
    }

    private Bucket overflowBucket() {
        Bucket local = overflowBucket;
        if (local == null) {
            synchronized (this) {
                local = overflowBucket;
                if (local == null) {
                    local = newBucket(requestsPerHour, Duration.ofHours(1));
                    overflowBucket = local;
                    log.warn("DSAR intake limiter is tracking {} client IPs — further clients share "
                            + "one overflow bucket until idle entries are evicted", maxTrackedClients);
                }
            }
        }
        return local;
    }

    private Bucket emailOverflowBucket() {
        Bucket local = emailOverflowBucket;
        if (local == null) {
            synchronized (this) {
                local = emailOverflowBucket;
                if (local == null) {
                    local = newBucket(requestsPerEmailPerDay, Duration.ofDays(1));
                    emailOverflowBucket = local;
                    log.warn("DSAR intake limiter is tracking {} subject digests — further targets "
                            + "share one overflow bucket until idle entries are evicted",
                            maxTrackedEmails);
                }
            }
        }
        return local;
    }

    private Bucket globalBucket() {
        Bucket local = globalBucket;
        if (local == null) {
            synchronized (this) {
                local = globalBucket;
                if (local == null) {
                    local = newBucket(globalRequestsPerHour, Duration.ofHours(1));
                    globalBucket = local;
                }
            }
        }
        return local;
    }

    private static Bucket newBucket(int capacity, Duration window) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillIntervally(capacity, window)
                        .build())
                .build();
    }
}
