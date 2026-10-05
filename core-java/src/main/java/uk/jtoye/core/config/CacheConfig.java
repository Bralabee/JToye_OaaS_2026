package uk.jtoye.core.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.CacheKeyPrefix;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis Cache Configuration for JToye OaaS.
 * 
 * Features:
 * - Tenant-aware caching with TenantAwareCacheKeyGenerator
 * - Per-cache TTL configuration (products: 10min, shops: 15min)
 * - JSON serialization for cache values (Jackson 3, allowlisted polymorphic typing)
 * - Versioned cache keys ({@code v4:{region}::...}, see CACHE_KEY_FORMAT_VERSION)
 * - Disabled for test profile to maintain test isolation
 * 
 * Cache Strategy:
 * - Products: Cached (rarely change) - 10 minute TTL
 * - Shops: Cached (rarely change) - 15 minute TTL
 * - Orders: NOT cached (change frequently)
 * - Customers: NOT cached (change frequently)
 */
@Configuration
@EnableCaching
@Profile("!test")  // Disable caching in test profile
public class CacheConfig implements CachingConfigurer {

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public CacheConfig(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * Redis cache resilience (issue #86 [P1-4]): replace Spring's default
     * {@code SimpleCacheErrorHandler} (which RE-THROWS every cache error → HTTP
     * 500 when Redis is down) with {@link RedisCacheErrorHandler}, which degrades
     * cache errors to log-and-continue so cached reads fall back to the
     * source-of-truth. See that class for the per-operation semantics.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new RedisCacheErrorHandler(meterRegistryProvider);
    }

    /**
     * The cache value FORMAT version, carried in every cache key as {@code v4:{region}::…}
     * (Phase 38, 38-09).
     *
     * <p><b>Why.</b> The Spring Boot 4 move put the cache value serializer on Jackson 3, whose typing
     * scheme writes different bytes: every value a Boot-3.5 pod cached under {@code {region}::…} is
     * unreadable by a Boot-4 pod (measured on the three 38-01 golden values). Read under the old key,
     * each would be a GET error that {@link RedisCacheErrorHandler} swallows and counts in
     * {@code jtoye.cache.errors} on every request; and during a rolling deploy a Boot-3.5 pod would
     * equally fail on what a Boot-4 pod wrote. With the version in the key the two formats never meet:
     * a Boot-4 pod neither reads nor overwrites an old entry.
     *
     * <p><b>No flush for FORMAT reasons.</b> Old entries are never read by a Boot-4 pod and expire by
     * their region TTL (shopMembership 5 min, products 10 min, shops 15 min; the 10-minute default for
     * any other region). {@code CacheFormatIsolationIntegrationTest} proves it on a real Redis.
     *
     * <p><b>But evictions do not cross generations by themselves (Phase 38 review WR-01).</b> Because
     * the two generations use different keys, an eviction made by one never reached the other's entry:
     * during a rolling deploy (or after a rollback) a revoke or edit handled by one generation left the
     * other generation serving its cached value until the TTL, including a {@code shopMembership}
     * grant. Two measures close it, and neither is complete on its own:
     * <ul>
     *   <li><b>Dual eviction (code, transitional).</b> {@link TenantCacheEvictor} also deletes the
     *       Boot-3.5 key ({@link #legacyBoot35CacheKey}) whenever it evicts. That covers a write handled
     *       by a Boot-4 pod: Boot-3.5 pods still running during the roll-forward, and Boot-3.5 pods after
     *       a rollback, no longer read an entry a Boot-4 write should have removed.</li>
     *   <li><b>Post-rollout cleanup (operator).</b> A Boot-3.5 pod cannot be taught to evict a
     *       {@code v4:} key, so a write it handles during the overlap leaves the Boot-4 entry for that
     *       entity stale until its TTL. Once every pod runs Boot 4, delete the {@code v4:*} keys (SCAN +
     *       UNLINK, never KEYS or FLUSHALL); the same step after a re-roll-forward removes {@code v4:}
     *       entries written before a rollback that the 3.5 pods never evicted. ADR-0006 "Deploy notes"
     *       and "Rollback notes" carry the procedure and the bound.</li>
     * </ul>
     *
     * <p><b>Standing procedure.</b> Any future change to the cache value FORMAT (serializer, typing
     * scheme, or a DTO change that old bytes cannot be read into) bumps this constant in the same
     * change. It sits on the default configuration, so every region, including one added later,
     * inherits it.
     */
    static final String CACHE_KEY_FORMAT_VERSION = "v4";

    /**
     * TRANSITIONAL (Phase 38 review WR-01): the Redis key a Boot-3.5 pod used for {@code key} in region
     * {@code cacheName}, so {@link TenantCacheEvictor} can delete it alongside the {@code v4:} key.
     *
     * <p>Boot 3.5's cache manager was built from {@code RedisCacheConfiguration.defaultCacheConfig()}
     * with no {@code computePrefixWith}, i.e. Spring Data Redis's {@link CacheKeyPrefix#simple()}
     * ({@code {cacheName}::}), and string keys. This calls that same library function rather than
     * hand-writing a second key format; {@code CacheFormatIsolationIntegrationTest} pins the result
     * against the key the 38-01 golden Boot-3.5 entry lives under.
     *
     * <p><b>Removal condition.</b> Delete this method and its one caller in the first release after the
     * Boot-4 rollout is complete: no Boot-3.5 pod can exist any more (no rollback to a 3.5 image is
     * still on the table) and the longest region TTL (15 minutes) has elapsed since the last 3.5 pod
     * stopped. Until then it is load-bearing.
     */
    static String legacyBoot35CacheKey(String cacheName, String key) {
        return CacheKeyPrefix.simple().compute(cacheName) + key;
    }

    /**
     * Configure Redis Cache Manager with per-cache TTL settings.
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Default cache configuration (fallback). Every region derives from it, so every region
        // inherits the versioned key prefix (see CACHE_KEY_FORMAT_VERSION).
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .computePrefixWith(cacheName -> CACHE_KEY_FORMAT_VERSION + ":" + cacheName + "::")
                .entryTtl(Duration.ofMinutes(10))  // Default TTL: 10 minutes
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(jsonRedisSerializer())
                )
                .disableCachingNullValues();  // Don't cache null values

        // Per-cache TTL configurations
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        
        // Products cache: 10 minutes (rarely change, frequently read)
        cacheConfigurations.put("products", defaultConfig.entryTtl(Duration.ofMinutes(10)));
        
        // Shops cache: 15 minutes (very stable data, infrequently updated)
        cacheConfigurations.put("shops", defaultConfig.entryTtl(Duration.ofMinutes(15)));

        // Phase 23 VSA-02 (D-05): per-user shop-membership cache. This cache genuinely
        // engages as of plan 23-14 (WR-01): ShopAccessService reaches the @Cacheable
        // resolveMembership through its own bean proxy, so the interceptor actually runs,
        // Membership round-trips through the JSON serializer below, and grant/revoke +
        // JIT-provision evict the exact entry AFTER commit (TenantCacheEvictor). This short
        // TTL is only a backstop should an eviction be missed — an auth boundary must not
        // carry a stale allow for long.
        cacheConfigurations.put("shopMembership", defaultConfig.entryTtl(Duration.ofMinutes(5)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    /**
     * QA-council 20260902-134741 SEC-4 (adjudication A6), re-derived for Jackson 3 in Phase 38
     * (38-09, 2026-10-05): the class-name prefixes the cache value serializer may INSTANTIATE from a
     * stored type id ({@code @class} on objects, the {@code ["<class>", value]} wrapper on
     * collections and non-final scalars). Every other type id is refused at deserialization with a
     * {@code tools.jackson.databind.exc.InvalidTypeIdException}, which Spring wraps in its
     * {@code SerializationException}.
     *
     * <p><b>Why an allowlist.</b> Jackson's default validator for polymorphic typing is the permissive
     * one, which would instantiate ANY class a stored entry named, with only Jackson's internal gadget
     * denylist in the way — measured on the running artifact before SEC-4: {@code java.net.URI} and
     * {@code java.util.TreeMap} both instantiated from a hand-written type id. Jackson's guidance is an
     * explicit {@code BasicPolymorphicTypeValidator}; the denylist still applies underneath it.
     *
     * <p><b>Derived from the LIVE Jackson-3 bytes, not from reading the DTOs.</b> Spring Data Redis 4's
     * {@link GenericJacksonJsonRedisSerializer} types values by its own NON_FINAL rule (Jackson 3
     * removed the "type everything" mode the Jackson-2 cache used): a non-final value carries an id,
     * while final JDK types ({@code Long}, {@code UUID}, {@code OffsetDateTime}, {@code String}),
     * primitives and enums are written bare. Serializing the 38-01 golden ProductDto, ShopDto and
     * Membership samples (production collection runtime types) plus a BigDecimal value and a
     * BigDecimal member wrote exactly these ids (evidence/38-09-cache.txt):
     * <ul>
     *   <li>{@code uk.jtoye.}: {@code core.product.dto.ProductDto}, {@code core.product.AllergenSpan},
     *       {@code core.media.MediaAssetDto}, {@code core.shop.dto.ShopDto},
     *       {@code core.security.access.Membership} — the cached values and their nested records.</li>
     *   <li>{@code java.util.}: {@code ArrayList} (MapStruct copies), {@code ImmutableCollections$ListN}
     *       ({@code Stream.toList()} media), {@code LinkedHashMap} (opening hours),
     *       {@code ImmutableCollections$Map1} / {@code $MapN} ({@code Map.copyOf} / {@code Map.of()}
     *       shop grants).</li>
     *   <li>{@code java.math.}: {@code BigDecimal}, top-level and as a member. No cached DTO carries
     *       one today; it stays because a {@code BigDecimal} is not final and is therefore stored WITH
     *       an id, so a money-precise field added to any cached DTO would otherwise fail every READ
     *       (PR #726 review).</li>
     * </ul>
     * {@code java.lang.} and {@code java.time.} carried the Jackson-2 {@code Long} and
     * {@code OffsetDateTime} wrappers; under Jackson 3 neither is written, so both were DROPPED and an
     * id under either is now refused. {@code CacheSerializerTypeAllowlistTest} pins the exact id set and
     * ties it to this list in both directions: a missing prefix fails the round-trips, and a prefix
     * with no observed id fails too.
     *
     * <p>Omit a needed prefix and every cache READ fails INVISIBLY:
     * {@link RedisCacheErrorHandler#handleCacheGetError} WARN-logs and swallows GET errors, so the
     * symptom is a permanent silent cache miss rather than a 500. After any change here: rebuild, then
     * confirm {@code jtoye.cache.errors} stays 0 under a read-after-write of the products / shops /
     * shopMembership regions.
     *
     * <p><b>Subtype matchers only — never a base-type matcher.</b> The nominal base of a top-level
     * cached value is {@code java.lang.Object}, and an ALLOWED base type makes Jackson hand every
     * subtype of it to the permissive validator, so a base-type rule naming {@code java.lang.} would
     * silently re-open exactly the hole this closes. This rule is unchanged by the Jackson-3 move.
     */
    static final List<String> CACHE_TYPE_ID_PREFIXES = List.of(
            "uk.jtoye.",   // ProductDto, ShopDto, Membership, AllergenSpan, MediaAssetDto
            "java.util.",  // ArrayList, LinkedHashMap, ImmutableCollections$ListN / $Map1 / $MapN
            "java.math."   // BigDecimal: not final, so stored WITH an id (PR #726 review)
    );

    static PolymorphicTypeValidator cacheTypeValidator() {
        BasicPolymorphicTypeValidator.Builder builder = BasicPolymorphicTypeValidator.builder();
        for (String prefix : CACHE_TYPE_ID_PREFIXES) {
            builder = builder.allowIfSubType(prefix);
        }
        return builder.build();
    }

    /**
     * The Redis value serializer: Spring Data Redis 4's Jackson-3
     * {@link GenericJacksonJsonRedisSerializer} with polymorphic typing gated by
     * {@link #cacheTypeValidator()} (SEC-4), so cached values deserialize back to their concrete type.
     *
     * <p>No {@code customize(...)}: Jackson 3 handles {@code java.time} natively and writes ISO-8601
     * text by default (measured: {@code "createdAt":"2026-10-04T13:34:56.123456789+01:00"}), which is
     * what QA-council BE-01 needed the Jackson-2 time module for.
     *
     * <p>Entries written by the Jackson-2 serializer before Phase 38 are format-incompatible (measured:
     * all three 38-01 golden cache values fail to read). They are made unreachable by the versioned key
     * prefix ({@link #CACHE_KEY_FORMAT_VERSION}), not by a deploy-time flush.
     *
     * <p>Static and public so the serializer tests exercise THIS serializer rather than a hand-kept
     * mirror of it: the previous mirror in {@code MembershipSerializerRoundTripTest} would have stayed
     * green over a validator change that killed the cache.
     */
    public static GenericJacksonJsonRedisSerializer jsonRedisSerializer() {
        return GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(cacheTypeValidator())
                .build();
    }

    /**
     * Tenant-aware cache key generator bean.
     * Ensures cache keys are scoped to tenant ID to prevent cross-tenant data leakage.
     */
    @Bean
    public TenantAwareCacheKeyGenerator tenantAwareCacheKeyGenerator() {
        return new TenantAwareCacheKeyGenerator();
    }
}
