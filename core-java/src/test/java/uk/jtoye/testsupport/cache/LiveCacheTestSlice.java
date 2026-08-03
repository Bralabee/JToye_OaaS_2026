package uk.jtoye.testsupport.cache;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.jtoye.core.config.TenantAwareCacheKeyGenerator;

/**
 * Issue #498 — <b>the required home for any assertion about caching behaviour.</b>
 *
 * <h2>The problem this exists to solve</h2>
 *
 * {@code CacheConfig} is annotated {@code @Profile("!test")} ("Disable caching in test profile")
 * and {@code application-test.yml} supplies no cache configuration of its own. So the default test
 * suite runs with <b>no cache manager at all</b>, and nothing in CI exercises the Spring caching
 * interceptor.
 *
 * <p>That is worse than absent coverage: it makes cache assertions <b>vacuous by construction</b>.
 * "The cache region holds zero entries after a 404 lookup" passes on a tree where the code is
 * correct, passes on a tree where the {@code unless=} clause was deleted, and passes on a tree
 * where caching was switched off outright — all three produce an empty region. An assertion that
 * cannot fail is not evidence. Issue #484 is the worked example: the fix it recommended would have
 * thrown {@code SpelEvaluationException EL1004E} on every successful lookup and disabled the
 * products cache, and that issue's own acceptance criterion would have gone GREEN on the broken
 * tree.
 *
 * <h2>What this slice supplies, and what it deliberately does not</h2>
 *
 * Only the two beans {@code CacheConfig} withholds under the {@code test} profile:
 * <ul>
 *   <li>a real {@link CacheManager} — {@link ConcurrentMapCacheManager}, so no Redis container is
 *       needed and the assertion stays a fast unit test rather than a 45-minute Testcontainers
 *       dependency; and</li>
 *   <li>a {@link KeyGenerator} registered under the bean name {@code tenantAwareCacheKeyGenerator},
 *       without which {@code @Cacheable(keyGenerator = "tenantAwareCacheKeyGenerator")} cannot
 *       resolve at all.</li>
 * </ul>
 *
 * It supplies <b>no application beans</b>. Each consuming test registers the loaders it is actually
 * asserting on, so this class never becomes a place where unrelated context grows.
 *
 * <h2>Why the package is {@code uk.jtoye.testsupport}, outside {@code uk.jtoye.core}</h2>
 *
 * A top-level {@code @Configuration} inside {@code uk.jtoye.core} sits in Spring's component-scan
 * root ({@code @SpringBootApplication} on {@code uk.jtoye.core.CoreApplication}). It would then be
 * a candidate configuration in {@code @SpringBootTest} contexts, silently switching caching ON for
 * integration tests that were written against a cache-free profile — quietly reversing the
 * deliberate {@code @Profile("!test")} decision across the whole suite. Outside the scanned root it
 * can only ever be used where a test names it explicitly.
 *
 * <h2>Sensitivity — deliberately higher than production</h2>
 *
 * {@code ConcurrentMapCacheManager} keeps its default {@code allowNullValues = true}, whereas
 * production Redis is built with {@code disableCachingNullValues()}. So if the interceptor attempts
 * to store a negative result at all, this cache accepts it: "no entry" here means no put was even
 * attempted, and cannot be an artifact of the cache refusing nulls.
 *
 * <h2>The rule for using it</h2>
 *
 * <b>A cache assertion must be BEHAVIOURAL, not just structural.</b> Reading
 * {@code cacheManager.getCache(region).get(key)} proves a put happened at a key; it does NOT prove
 * a subsequent call is served from the cache. The two come apart in practice — swapping
 * {@code @Cacheable} for {@code @CachePut} leaves every region-contents assertion in this codebase
 * green while every read goes to the database. Assert instead that a second identical call does not
 * reach the repository ({@code verify(repo, times(1))}), and pair every "not cached" assertion with
 * a positive control proving something IS cached through the same interceptor.
 *
 * @see uk.jtoye.core.config.CachingInterceptorLivenessTest the behavioural assertions
 * @see uk.jtoye.core.config.NegativeCachingOptionalEmptyTest the #484 region-contents assertions
 */
@Configuration
@EnableCaching
public class LiveCacheTestSlice {

    /** Every region the production {@code CacheConfig} declares that has a live annotation site. */
    public static final String[] REGIONS = {"shops", "products", "shopMembership"};

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(REGIONS);
    }

    /**
     * The bean NAME matters, not just the type: the production annotations select the generator by
     * the string {@code "tenantAwareCacheKeyGenerator"}. Registering the real production generator
     * (rather than a stub) is what makes the tenant dimension of the key real in this slice.
     */
    @Bean
    public KeyGenerator tenantAwareCacheKeyGenerator() {
        return new TenantAwareCacheKeyGenerator();
    }
}
