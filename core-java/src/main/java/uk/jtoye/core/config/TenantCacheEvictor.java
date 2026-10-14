package uk.jtoye.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.jtoye.core.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Helper for evicting cache entries scoped to the current tenant.
 *
 * <p>The project's {@link TenantAwareCacheKeyGenerator} builds keys as
 * {@code tenant:{tenantId}:{methodName}:{params}}. Using
 * {@code @CacheEvict(allEntries=true)} blows away every tenant's data on any
 * tenant's write — a cache REGION is shared by every tenant, isolation lives in
 * the KEY — so this helper mirrors the key format and lets services evict
 * exactly the key of the entity they just mutated.
 *
 * <p><strong>Per-id eviction is the ONLY supported pattern, including for bulk
 * work (issue #483).</strong> This javadoc used to point at a method
 * {@code evictAllForMethod(String, String)} for "broader invalidation, e.g. a
 * bulk import". That method never existed, and its absence is plausibly how the
 * {@code allEntries = true} blast kept being reintroduced: a developer following
 * the documented route found nothing and fell back to the annotation. The
 * promise is deleted rather than implemented — implementing it honestly needs a
 * Redis key-prefix {@code SCAN}, which is not reachable through Spring's
 * {@link Cache} interface, plus an O(region) scan on every call. So:
 *
 * <ul>
 *   <li><b>a single mutation</b> — {@link #evictEntity(String, String, UUID)};</li>
 *   <li><b>a batch/bulk mutation</b> — accumulate the ids actually written and
 *       evict each one. {@code SyncService.processBatch} is the worked example;</li>
 *   <li><b>a create-only path</b> — evict NOTHING. A row that did not exist has
 *       no key in the region, so nothing can have been staled
 *       ({@code BulkImportService}, issue #287);</li>
 *   <li><b>inside a transaction</b> — prefer
 *       {@link #evictEntityAfterCommit(String, String, UUID)}, which closes the
 *       window in which a concurrent read repopulates the entry from
 *       not-yet-committed state.</li>
 * </ul>
 *
 * <p>Do NOT reach back for {@code @CacheEvict(allEntries = true)}. If a genuinely
 * region-wide invalidation is ever needed, that is a design decision to escalate,
 * not a convenience to reintroduce.
 */
@Component
public class TenantCacheEvictor {
    private static final Logger log = LoggerFactory.getLogger(TenantCacheEvictor.class);

    private final CacheManager cacheManager;

    /**
     * Resolves CacheManager lazily so this component can be created in profiles
     * where {@link CacheConfig} is disabled (e.g. the {@code test} profile).
     * When no CacheManager is present every eviction becomes a no-op, which is
     * correct: there is no cache to invalidate.
     */
    @Autowired
    public TenantCacheEvictor(ObjectProvider<CacheManager> cacheManagerProvider) {
        this.cacheManager = cacheManagerProvider.getIfAvailable();
    }

    /** Test-only constructor that accepts a specific CacheManager directly. */
    TenantCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * Evict a single entity's cache entry under the current tenant.
     *
     * @param cacheName  the cache name (e.g. "products", "shops")
     * @param methodName the method name used by TenantAwareCacheKeyGenerator
     *                   (e.g. "getShopById", "getProductById")
     * @param entityId   the entity id used as the cached method's parameter
     */
    public void evictEntity(String cacheName, String methodName, UUID entityId) {
        if (cacheManager == null) {
            // No cache manager in this profile (e.g. test profile) — nothing to evict, and
            // deliberately checked BEFORE the TenantContext lookup so this stays silent rather
            // than WARN-logging on every write in a cache-less profile (pre-existing behaviour).
            return;
        }
        UUID tenantId = TenantContext.get().orElse(null);
        if (tenantId == null) {
            log.warn("evictEntity skipped — TenantContext not set (cache={}, method={}, id={})",
                    cacheName, methodName, entityId);
            return;
        }
        evictEntity(tenantId, cacheName, methodName, entityId);
    }

    /**
     * Evict a single entity's cache entry AFTER the current transaction commits, falling back
     * to an inline evict when no transaction synchronization is active.
     *
     * <p>Post-commit matters most where the mutation and the return are far apart — a sync or
     * import batch can hold its transaction open across hundreds of rows. An inline evict at
     * row 3 of 500 leaves a window of the remaining batch's duration in which a concurrent read
     * calls the {@code @Cacheable} loader, sees the not-yet-committed (old) row, and repopulates
     * the entry with it — stale for the full cache TTL, which is exactly the bug the eviction
     * exists to prevent. Registering an {@code afterCommit} synchronization closes it.
     *
     * <p>The tenant is captured NOW, not read inside the callback, so the eviction targets the
     * tenant that performed the write even if the callback were ever to run with a different (or
     * cleared) {@link TenantContext}.
     *
     * <p>This is the same idiom {@code ShopAccessService.evictMembershipAfterCommit} uses for the
     * membership cache; it lives here so batch callers do not have to re-derive it.
     */
    public void evictEntityAfterCommit(String cacheName, String methodName, UUID entityId) {
        if (cacheManager == null) {
            return;   // see evictEntity — silent no-op in a cache-less profile
        }
        UUID tenantId = TenantContext.get().orElse(null);
        if (tenantId == null) {
            log.warn("evictEntityAfterCommit skipped — TenantContext not set (cache={}, method={}, id={})",
                    cacheName, methodName, entityId);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictEntity(tenantId, cacheName, methodName, entityId);
                }
            });
        } else {
            evictEntity(tenantId, cacheName, methodName, entityId);
        }
    }

    /** Shared body of both public evictions, with the tenant already resolved. */
    private void evictEntity(UUID tenantId, String cacheName, String methodName, UUID entityId) {
        if (cacheManager == null) {
            // No cache manager in this profile (e.g. test profile) — nothing to evict.
            return;
        }
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            log.debug("evictEntity skipped — cache '{}' not configured", cacheName);
            return;
        }
        String key = String.format("tenant:%s:%s:%s", tenantId, methodName, entityId);
        // evictIfPresent, NOT evict (PR #898 review round 1): Spring Data Redis 4's RedisCache.evict
        // is fire-and-forget when asynchronous writes are on (its default), so the DEL could land
        // after this method returned — a revoked shopMembership grant still served in that window —
        // and a failed DEL would be lost without a trace. evictIfPresent issues a synchronous DEL,
        // as Boot 3.5's evict did, so a failure surfaces to the caller exactly as it did on 3.5.
        cache.evictIfPresent(key);
        log.debug("Evicted cache entry {}::{}", cacheName, key);
        evictOtherGenerationKey(cache, CacheConfig.legacyBoot35CacheKey(cache.getName(), key), "Boot-3.5");
        evictOtherGenerationKey(cache, CacheConfig.previousFormatCacheKey(cache.getName(), key),
                "previous-format (" + CacheConfig.PREVIOUS_CACHE_KEY_FORMAT_VERSION + ")");
    }

    /**
     * TRANSITIONAL: also delete the key an OLDER pod generation caches this entity under, so a write
     * handled by a pod on this version reaches that generation too, during the rolling deploy and after
     * a rollback. Without it the generations' evictions never meet and a revoked {@code shopMembership}
     * grant stays cached on the other generation for its TTL. Two generations are reached:
     * <ul>
     *   <li>Boot 3.5 ({@link CacheConfig#legacyBoot35CacheKey}, Phase 38 review WR-01);</li>
     *   <li>the previous key format version ({@link CacheConfig#previousFormatCacheKey}, 37-G review:
     *       the pre-37-G image reads and writes {@code v4:} keys).</li>
     * </ul>
     * See {@link CacheConfig#CACHE_KEY_FORMAT_VERSION} for the direction this cannot cover (a write on an
     * older pod) and the operator step that does; each key is removed under its own condition.
     *
     * <p>Only a Redis-backed cache has another generation: the check is on the native cache, so a
     * {@code ConcurrentMapCache} (or any other provider) is skipped and a decorator around a Redis cache
     * is still reached. The delete is BEST-EFFORT: the primary eviction above has already been issued, so a
     * failure here is WARN-logged with the region and key and never fails the caller's write.
     */
    private void evictOtherGenerationKey(Cache cache, String otherKey, String generation) {
        try {
            if (!(cache.getNativeCache() instanceof RedisCacheWriter writer)) {
                return;
            }
            // Both older generations serialized keys with StringRedisSerializer, i.e. UTF-8. evictIfPresent,
            // NOT evict: Spring Data Redis 4's DefaultRedisCacheWriter.evict is fire-and-forget when
            // asynchronous writes are on (its default), so the DEL could land after this method
            // returned and its failure would never reach the catch below. evictIfPresent issues a
            // synchronous DEL, as Boot 3.5's evict did. Measured: with evict, the real-Redis arm in
            // CacheFormatIsolationIntegrationTest still found the legacy key in 3 runs of 8.
            writer.evictIfPresent(cache.getName(), otherKey.getBytes(StandardCharsets.UTF_8));
            log.debug("Evicted {} cache entry {}", generation, otherKey);
        } catch (RuntimeException e) {
            log.warn("{} cache eviction failed (region={}, key={}); the entry expires by its "
                    + "TTL instead: {}", generation, cache.getName(), otherKey, e.toString());
        }
    }
}
