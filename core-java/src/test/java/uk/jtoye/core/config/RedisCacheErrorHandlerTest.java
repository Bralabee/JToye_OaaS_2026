package uk.jtoye.core.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link RedisCacheErrorHandler} (issue #86 [P1-4]).
 *
 * <p>Plain JUnit 5 — NO Spring context, NO Testcontainers. Proves each of the
 * four cache error paths SWALLOWS (never rethrows) so a Redis outage degrades to
 * source-of-truth, and that each increments its distinctly-tagged
 * {@code jtoye.cache.errors} counter. Also proves the null-{@link MeterRegistry}
 * path logs and swallows without NPE (metrics simply absent).
 */
class RedisCacheErrorHandlerTest {

    private static final RuntimeException REDIS_DOWN =
            new RuntimeException("simulated Redis connection failure");

    @Test
    void handleCacheGetError_swallowsAndMetersAsGet() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisCacheErrorHandler handler = new RedisCacheErrorHandler(providerOf(registry));
        Cache cache = new ConcurrentMapCache("products");

        assertThatCode(() -> handler.handleCacheGetError(REDIS_DOWN, cache, "k1"))
                .doesNotThrowAnyException();

        assertThat(counterCount(registry, "get", "products")).isEqualTo(1.0);
    }

    @Test
    void handleCachePutError_swallowsAndMetersAsPut() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisCacheErrorHandler handler = new RedisCacheErrorHandler(providerOf(registry));
        Cache cache = new ConcurrentMapCache("products");

        assertThatCode(() -> handler.handleCachePutError(REDIS_DOWN, cache, "k1", "value"))
                .doesNotThrowAnyException();

        assertThat(counterCount(registry, "put", "products")).isEqualTo(1.0);
    }

    @Test
    void handleCacheEvictError_swallowsAndMetersAsEvict() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisCacheErrorHandler handler = new RedisCacheErrorHandler(providerOf(registry));
        Cache cache = new ConcurrentMapCache("shops");

        assertThatCode(() -> handler.handleCacheEvictError(REDIS_DOWN, cache, "k1"))
                .doesNotThrowAnyException();

        assertThat(counterCount(registry, "evict", "shops")).isEqualTo(1.0);
    }

    @Test
    void handleCacheClearError_swallowsAndMetersAsClear() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisCacheErrorHandler handler = new RedisCacheErrorHandler(providerOf(registry));
        Cache cache = new ConcurrentMapCache("shops");

        assertThatCode(() -> handler.handleCacheClearError(REDIS_DOWN, cache))
                .doesNotThrowAnyException();

        assertThat(counterCount(registry, "clear", "shops")).isEqualTo(1.0);
    }

    @Test
    void nullMeterRegistry_stillSwallowsWithoutNpe() {
        // No MeterRegistry available — handler must still log+swallow (no metric, no NPE).
        RedisCacheErrorHandler handler = new RedisCacheErrorHandler(nullProvider());
        Cache cache = new ConcurrentMapCache("products");

        assertThatCode(() -> {
            handler.handleCacheGetError(REDIS_DOWN, cache, "k1");
            handler.handleCachePutError(REDIS_DOWN, cache, "k1", "value");
            handler.handleCacheEvictError(REDIS_DOWN, cache, "k1");
            handler.handleCacheClearError(REDIS_DOWN, cache);
        }).doesNotThrowAnyException();
    }

    private static double counterCount(MeterRegistry registry, String operation, String cache) {
        return registry.get("jtoye.cache.errors")
                .tags("operation", operation, "cache", cache)
                .counter()
                .count();
    }

    private static ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        return new StubObjectProvider(registry);
    }

    private static ObjectProvider<MeterRegistry> nullProvider() {
        return new StubObjectProvider(null);
    }

    /**
     * Minimal {@link ObjectProvider} stub: {@link RedisCacheErrorHandler} only
     * calls {@link ObjectProvider#getIfAvailable()}, so the two abstract
     * {@code getObject} overloads are implemented for completeness only.
     */
    private static final class StubObjectProvider implements ObjectProvider<MeterRegistry> {
        private final MeterRegistry value;

        StubObjectProvider(MeterRegistry value) {
            this.value = value;
        }

        @Override
        public MeterRegistry getIfAvailable() {
            return value;
        }

        @Override
        public MeterRegistry getIfUnique() {
            return value;
        }

        @Override
        public MeterRegistry getObject() {
            return value;
        }

        @Override
        public MeterRegistry getObject(Object... args) {
            return value;
        }
    }
}
