package uk.jtoye.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import uk.jtoye.core.security.TenantContext;

import java.util.UUID;

/**
 * Helper for evicting cache entries scoped to the current tenant.
 *
 * <p>The project's {@link TenantAwareCacheKeyGenerator} builds keys as
 * {@code tenant:{tenantId}:{methodName}:{params}}. Using
 * {@code @CacheEvict(allEntries=true)} blows away every tenant's data on any
 * tenant's write — this helper mirrors the key format so services can evict
 * exactly the key that corresponds to the single entity they just mutated.
 *
 * <p>For broader invalidation (e.g. a bulk import) fall back to
 * {@link #evictAllForMethod(String, String)} which clears every entry prefixed
 * with the current tenant's id for a given cache — still scoped to one tenant.
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
            // No cache manager in this profile (e.g. test profile) — nothing to evict.
            return;
        }
        UUID tenantId = TenantContext.get().orElse(null);
        if (tenantId == null) {
            log.warn("evictEntity skipped — TenantContext not set (cache={}, method={}, id={})",
                    cacheName, methodName, entityId);
            return;
        }
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            log.debug("evictEntity skipped — cache '{}' not configured", cacheName);
            return;
        }
        String key = String.format("tenant:%s:%s:%s", tenantId, methodName, entityId);
        cache.evict(key);
        log.debug("Evicted cache entry {}::{}", cacheName, key);
    }
}
