package uk.jtoye.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import uk.jtoye.core.security.TenantContext;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link TenantCacheEvictor}.
 *
 * <p>Critical invariant: a write performed under tenant A must NEVER evict
 * tenant B's entry for the same entity id. This directly replaces the
 * previous {@code @CacheEvict(allEntries=true)} cross-tenant-blast behaviour.
 */
class TenantCacheEvictorTest {

    private SimpleCacheManager cacheManager;
    private Cache shopsCache;
    private TenantCacheEvictor evictor;

    @BeforeEach
    void setUp() {
        shopsCache = new ConcurrentMapCache("shops");
        cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(shopsCache, new ConcurrentMapCache("products")));
        cacheManager.afterPropertiesSet();
        evictor = new TenantCacheEvictor(cacheManager);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("evictEntity - Removes exactly the current tenant's entry")
    void testEvictEntity_RemovesCurrentTenantEntry() {
        UUID tenantA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID shopId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        String keyA = "tenant:" + tenantA + ":getShopById:" + shopId;
        shopsCache.put(keyA, "shop-A-cached-value");

        TenantContext.set(tenantA);
        evictor.evictEntity("shops", "getShopById", shopId);

        assertNull(shopsCache.get(keyA), "Current tenant entry should be evicted");
    }

    @Test
    @DisplayName("evictEntity - Does NOT touch other tenants' entries for the same entity id")
    void testEvictEntity_DoesNotEvictOtherTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID sharedShopId = UUID.randomUUID();

        String keyA = "tenant:" + tenantA + ":getShopById:" + sharedShopId;
        String keyB = "tenant:" + tenantB + ":getShopById:" + sharedShopId;
        shopsCache.put(keyA, "A-value");
        shopsCache.put(keyB, "B-value");

        // Tenant A writes and evicts its own entry
        TenantContext.set(tenantA);
        evictor.evictEntity("shops", "getShopById", sharedShopId);

        // Tenant B's entry MUST survive
        assertNull(shopsCache.get(keyA));
        Cache.ValueWrapper bValue = shopsCache.get(keyB);
        assertNotNull(bValue, "Tenant B's cache entry must not be evicted by tenant A's write");
        assertEquals("B-value", bValue.get());
    }

    @Test
    @DisplayName("evictEntity - No-op when TenantContext unset (does not throw)")
    void testEvictEntity_NoOpWhenTenantUnset() {
        TenantContext.clear();
        // Should not throw — logs a warn and returns
        evictor.evictEntity("shops", "getShopById", UUID.randomUUID());
    }

    @Test
    @DisplayName("evictEntity - No-op when cache name unknown")
    void testEvictEntity_NoOpWhenCacheMissing() {
        TenantContext.set(UUID.randomUUID());
        evictor.evictEntity("nonexistent", "getShopById", UUID.randomUUID());
    }
}
