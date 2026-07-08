package uk.jtoye.core.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Redis cache resilience handler (issue #86 [P1-4]).
 *
 * <p>Spring's default {@code SimpleCacheErrorHandler} RE-THROWS every cache
 * error, so a Redis blip turns a {@code @Cacheable} read into an HTTP 500 (and,
 * with Lettuce's 60s default command timeout, a 60s hang first). That makes
 * Redis a hard dependency for every cached read/write. This handler makes Redis
 * a SOFT dependency: it logs and swallows all four cache error paths so the
 * caller degrades to the source-of-truth (a Redis outage becomes a cache miss,
 * not an outage).
 *
 * <p><strong>Per-operation semantics (deliberate, per the #86 fix direction):</strong>
 * <ul>
 *   <li><b>GET</b> — WARN + swallow. The {@code @Cacheable} method body runs and
 *       returns the source-of-truth value (a plain cache miss). This is the AC1
 *       "Redis down ⇒ serve from DB, no 500" behaviour.</li>
 *   <li><b>PUT</b> — WARN + swallow. A failed write-through must never fail the
 *       read that produced the value; the value simply isn't cached this time.</li>
 *   <li><b>EVICT</b> — ERROR + swallow. <em>Staleness risk:</em> a stale entry
 *       may survive the write because the eviction never reached Redis. We
 *       deliberately swallow (so the write path completes) but log at ERROR and
 *       meter it distinctly so operators can alarm on it. Rethrowing would
 *       re-introduce the 500 on the write path this issue exists to remove; the
 *       per-cache TTLs (products 10m, shops 15m) bound the staleness window.</li>
 *   <li><b>CLEAR</b> — ERROR + swallow. Same staleness trade-off as EVICT.</li>
 * </ul>
 *
 * <p>Every error is metered as {@code jtoye.cache.errors} with an {@code operation}
 * tag (get/put/evict/clear) and a {@code cache} tag, so the degrade is observable
 * rather than silent. The {@link MeterRegistry} is resolved null-safely (mirroring
 * {@code PaymentEventOutboxFlusher}): with no registry the handler still logs and
 * swallows, it just records no metric.
 */
public class RedisCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheErrorHandler.class);
    private static final String METRIC = "jtoye.cache.errors";

    private final MeterRegistry meterRegistry;

    public RedisCacheErrorHandler(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        // Resolve once — null when no MeterRegistry is on the classpath/context.
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache GET failed for cache '{}' key '{}' — degrading to source-of-truth (treated as a cache miss): {}",
                cacheName(cache), key, exception.getMessage());
        meter("get", cache);
        // swallow: the @Cacheable method body now executes against the source-of-truth
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("Cache PUT failed for cache '{}' key '{}' — value not cached, read path unaffected: {}",
                cacheName(cache), key, exception.getMessage());
        meter("put", cache);
        // swallow: a failed write-through must not fail the read that produced the value
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        // ERROR (not WARN): the eviction never reached Redis, so a STALE entry may
        // survive this write. Swallowed on purpose so the write path completes;
        // TTLs bound the staleness window and the distinct metric alarms operators.
        log.error("Cache EVICT failed for cache '{}' key '{}' — CACHE MAY BE STALE "
                        + "(stale entry may survive this write); swallowed to keep the write path alive: {}",
                cacheName(cache), key, exception.getMessage());
        meter("evict", cache);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.error("Cache CLEAR failed for cache '{}' — CACHE MAY BE STALE "
                        + "(entries may survive this clear); swallowed to keep the write path alive: {}",
                cacheName(cache), exception.getMessage());
        meter("clear", cache);
    }

    /**
     * Register-on-demand (MeterRegistry dedupes by name+tags, so repeated calls
     * return the same Counter) and increment. No-op when no registry is present.
     */
    private void meter(String operation, Cache cache) {
        if (meterRegistry != null) {
            Counter.builder(METRIC)
                    .description("Redis cache operation errors degraded to log-and-continue (issue #86)")
                    .tags("operation", operation, "cache", cacheName(cache))
                    .register(meterRegistry)
                    .increment();
        }
    }

    private static String cacheName(Cache cache) {
        return cache != null ? cache.getName() : "unknown";
    }
}
