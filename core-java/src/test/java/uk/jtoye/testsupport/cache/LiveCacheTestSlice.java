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
 * <h2>Coverage inventory (#498 acceptance criterion) — COMPLETE, not truncated</h2>
 *
 * Every {@code @Cacheable}/{@code @CachePut}/{@code @CacheEvict} site in {@code core-java/src/main},
 * measured with a line-anchored search that excludes the many Javadoc mentions of these annotations
 * (a naive substring search returns ~30 hits, of which 26 are prose):
 *
 * <table border="1">
 *   <caption>All four annotated methods, and where each is covered</caption>
 *   <tr><th>Site</th><th>Region</th><th>Covered by</th></tr>
 *   <tr>
 *     <td>{@code ProductService.ProductCacheLoader#getProductById}</td><td>products</td>
 *     <td>{@code CachingInterceptorLivenessTest} (hit, miss, cross-tenant — behavioural) and
 *         {@code NegativeCachingOptionalEmptyTest} (region contents)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ShopService.ShopCacheLoader#getShopById}</td><td>shops</td>
 *     <td>{@code CachingInterceptorLivenessTest} (hit, miss — behavioural) and
 *         {@code NegativeCachingOptionalEmptyTest} (region contents)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ShopAccessService#resolveMembership}</td><td>shopMembership</td>
 *     <td><b>NOT covered by this fast slice.</b> Covered structurally by
 *         {@code ShopAccessCacheBypassIntegrationTest}, which is {@code @Tag("testcontainers")} and
 *         therefore runs only in {@code integrationTest}, not in the per-PR unit job. Left where it
 *         is deliberately: the method needs four collaborators plus an {@code ObjectProvider} self
 *         reference and {@code @Value}-injected properties, so reproducing it here would be a
 *         second, differently-wired copy of a context that already exists. The region is declared
 *         in {@link #REGIONS} so the slice is ready for it.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code SyncService#syncBatch} — {@code @Caching(evict = {shops, products}, allEntries)}</td>
 *     <td>shops + products</td>
 *     <td><b>NOT covered.</b> An eviction assertion needs a populated region and a real
 *         {@code SyncService}; it is a genuine gap, recorded rather than quietly omitted. Note that
 *         {@code allEntries = true} is itself cross-tenant by construction — it clears every
 *         tenant's entries, which is the blast radius {@code TenantCacheEvictor} exists to avoid
 *         elsewhere. Worth its own issue.</td>
 *   </tr>
 * </table>
 *
 * <h2>Tests that depend on reads NOT being cached (#498 acceptance criterion)</h2>
 *
 * <b>None were changed, and none needed identifying, because nothing moved.</b> {@code CacheConfig}
 * keeps {@code @Profile("!test")}; this slice is only ever active in a context that names it
 * explicitly ({@code @SpringJUnitConfig} / {@code @Import}); and it lives outside the component-scan
 * root so it cannot be picked up implicitly. Every existing test therefore runs with exactly the
 * cache configuration it ran with before — which is none. This criterion is satisfied by the change
 * being additive, NOT by an enumeration having been performed; stated that way so nobody later
 * reads it as a completed audit of which tests tolerate caching.
 *
 * <h2>The rule for using it</h2>
 *
 * <b>A cache assertion must be BEHAVIOURAL, not just structural.</b> Reading
 * {@code cacheManager.getCache(region).get(key)} proves a put happened at a key; it does NOT prove
 * a subsequent call is served from the cache. The two come apart in practice, and this was measured
 * rather than reasoned: with {@code @Cacheable} swapped for {@code @CachePut} on
 * {@code ProductCacheLoader}, so the products cache stores everything and serves nothing,
 * {@code NegativeCachingOptionalEmptyTest} ran <b>4 tests, 0 failures — fully green</b>, while
 * {@code CachingInterceptorLivenessTest} caught it with 3 failures. Assert instead that a second
 * identical call does not reach the repository ({@code verify(repo, times(1))}), and pair every
 * "not cached" assertion with a positive control proving something IS cached through the same
 * interceptor.
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
