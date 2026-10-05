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
 * - JSON serialization for cache values
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
     * Configure Redis Cache Manager with per-cache TTL settings.
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Default cache configuration (fallback)
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
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
     * prefix, not by a deploy-time flush.
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
