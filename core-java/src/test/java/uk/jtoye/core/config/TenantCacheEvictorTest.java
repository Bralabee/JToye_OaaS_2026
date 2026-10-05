package uk.jtoye.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.jtoye.core.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ------------------------------------------------------------------
    // evictEntityAfterCommit (issue #483) — the batch-safe form. A long
    // transaction (a sync/import batch) that evicts INLINE leaves a window,
    // as wide as the rest of the batch, in which a concurrent read repopulates
    // the entry from the uncommitted old row.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("evictEntityAfterCommit - Defers the eviction until the transaction commits")
    void testEvictEntityAfterCommit_DefersUntilCommit() {
        UUID tenant = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        String key = "tenant:" + tenant + ":getShopById:" + shopId;
        shopsCache.put(key, "stale-value");

        TenantContext.set(tenant);
        TransactionSynchronizationManager.initSynchronization();
        try {
            evictor.evictEntityAfterCommit("shops", "getShopById", shopId);

            assertNotNull(shopsCache.get(key),
                    "the entry must STILL be present before commit — an inline evict here is "
                            + "exactly the window a concurrent read repopulates from uncommitted state");

            List<TransactionSynchronization> registered =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, registered.size(), "exactly one afterCommit callback must be registered");
            registered.get(0).afterCommit();

            assertNull(shopsCache.get(key), "and it must be gone once the transaction commits");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("evictEntityAfterCommit - Evicts inline when no transaction synchronization is active")
    void testEvictEntityAfterCommit_InlineWithoutTransaction() {
        UUID tenant = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        String key = "tenant:" + tenant + ":getShopById:" + shopId;
        shopsCache.put(key, "stale-value");

        TenantContext.set(tenant);
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive(),
                "instrument: this arm is only meaningful with NO active synchronization");

        evictor.evictEntityAfterCommit("shops", "getShopById", shopId);

        assertNull(shopsCache.get(key),
                "with nothing to defer to, the eviction must happen immediately rather than be lost");
    }

    @Test
    @DisplayName("evictEntityAfterCommit - Does NOT touch other tenants' entries for the same entity id")
    void testEvictEntityAfterCommit_DoesNotEvictOtherTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID sharedShopId = UUID.randomUUID();
        String keyA = "tenant:" + tenantA + ":getShopById:" + sharedShopId;
        String keyB = "tenant:" + tenantB + ":getShopById:" + sharedShopId;
        shopsCache.put(keyA, "A-value");
        shopsCache.put(keyB, "B-value");

        TenantContext.set(tenantA);
        evictor.evictEntityAfterCommit("shops", "getShopById", sharedShopId);

        assertNull(shopsCache.get(keyA));
        assertNotNull(shopsCache.get(keyB),
                "the deferred form must carry the same tenant scoping as the inline one — this is "
                        + "the whole point of issue #483");
    }

    @Test
    @DisplayName("evictEntityAfterCommit - Targets the tenant that performed the write, not the one live at commit")
    void testEvictEntityAfterCommit_CapturesTenantAtRegistrationTime() {
        UUID writer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        String writerKey = "tenant:" + writer + ":getShopById:" + shopId;
        String otherKey = "tenant:" + other + ":getShopById:" + shopId;
        shopsCache.put(writerKey, "writer-value");
        shopsCache.put(otherKey, "other-value");

        TenantContext.set(writer);
        TransactionSynchronizationManager.initSynchronization();
        try {
            evictor.evictEntityAfterCommit("shops", "getShopById", shopId);

            // The callback runs later; simulate the TenantContext having moved on (or been
            // cleared) by then. Reading the tenant inside the callback would evict the WRONG
            // key — or, on a cleared context, silently evict nothing and ship a stale read.
            TenantContext.set(other);
            TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();

            assertNull(shopsCache.get(writerKey), "the writing tenant's entry must be the one evicted");
            assertNotNull(shopsCache.get(otherKey), "and the tenant live at commit time must be untouched");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ------------------------------------------------------------------
    // Transitional dual eviction (Phase 38 review WR-01): a Redis-backed cache
    // also loses the Boot-3.5 key ({region}::…) for the same entity, so an
    // eviction made on a Boot-4 pod reaches the other generation. The real-Redis
    // proof is CacheFormatIsolationIntegrationTest; these pin the edges.
    // ------------------------------------------------------------------

    /** A real RedisCache over a mocked writer, prefixed exactly as CacheConfig prefixes it. */
    private static TenantCacheEvictor redisBackedEvictor(RedisCacheWriter writer) {
        RedisCacheManager manager = RedisCacheManager.builder(writer)
                .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
                        .computePrefixWith(name -> CacheConfig.CACHE_KEY_FORMAT_VERSION + ":" + name + "::"))
                .build();
        return new TenantCacheEvictor(manager);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("evictEntity - Redis cache: deletes the v4 key AND the legacy Boot-3.5 key, same tenant only")
    void testEvictEntity_RedisCacheAlsoEvictsLegacyBoot35Key() {
        RedisCacheWriter writer = mock(RedisCacheWriter.class);
        TenantCacheEvictor redisEvictor = redisBackedEvictor(writer);
        UUID tenant = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        String suffix = "tenant:" + tenant + ":getShopById:" + shopId;

        TenantContext.set(tenant);
        redisEvictor.evictEntity("shops", "getShopById", shopId);

        // BOTH deletes are the SYNCHRONOUS evictIfPresent: Spring Data Redis 4's evict may be
        // fire-and-forget, which would let either key outlive the caller's write (PR #898 round 1
        // for the v4 key, WR-01 for the Boot-3.5 key). verifyNoMoreInteractions fails on any evict.
        verify(writer).evictIfPresent(eq("shops"), aryEq(utf8("v4:shops::" + suffix)));
        verify(writer).evictIfPresent(eq("shops"), aryEq(utf8("shops::" + suffix)));
        verifyNoMoreInteractions(writer);
    }

    @Test
    @DisplayName("evictEntity - A failing legacy delete is logged, not propagated, and the v4 eviction stands")
    void testEvictEntity_LegacyDeleteFailureDoesNotPropagate() {
        RedisCacheWriter writer = mock(RedisCacheWriter.class);
        UUID tenant = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        String suffix = "tenant:" + tenant + ":getShopById:" + shopId;
        doThrow(new RedisConnectionFailureException("simulated: legacy delete refused"))
                .when(writer).evictIfPresent(eq("shops"), aryEq(utf8("shops::" + suffix)));
        TenantCacheEvictor redisEvictor = redisBackedEvictor(writer);

        TenantContext.set(tenant);
        assertDoesNotThrow(() -> redisEvictor.evictEntity("shops", "getShopById", shopId),
                "the legacy delete is best-effort: it must never fail the caller's write");

        verify(writer).evictIfPresent(eq("shops"), aryEq(utf8("v4:shops::" + suffix)));
        verify(writer).evictIfPresent(eq("shops"), aryEq(utf8("shops::" + suffix)));
    }

    @Test
    @DisplayName("evictEntity - A non-Redis cache evicts normally and takes no legacy path")
    void testEvictEntity_NonRedisCacheUnaffectedByLegacyEviction() {
        UUID tenant = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        String key = "tenant:" + tenant + ":getShopById:" + shopId;
        shopsCache.put(key, "value");
        // An entry whose KEY is the legacy-form string: on a non-Redis cache there is no other
        // generation, so nothing may touch it.
        shopsCache.put("shops::" + key, "legacy-shaped-key-value");

        TenantContext.set(tenant);
        assertDoesNotThrow(() -> evictor.evictEntity("shops", "getShopById", shopId));

        assertNull(shopsCache.get(key), "the entry is evicted exactly as before");
        assertNotNull(shopsCache.get("shops::" + key),
                "a ConcurrentMapCache has no Boot-3.5 generation: the legacy path must not run");
    }
}
