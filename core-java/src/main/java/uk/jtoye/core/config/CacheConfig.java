package uk.jtoye.core.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

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
     * QA-council 20260902-134741 SEC-4 (adjudication A6): the class-name prefixes the cache value
     * serializer may INSTANTIATE from a stored type id ({@code @class} on objects, the
     * {@code ["<class>", value]} wrapper on scalars and collections). Every other type id is refused
     * at deserialization with an {@code InvalidTypeIdException}.
     *
     * <p><b>Why an allowlist.</b> {@code new ObjectMapper().getPolymorphicTypeValidator()} is
     * {@code LaissezFaireSubTypeValidator}, so the previous
     * {@code activateDefaultTyping(<laissez-faire>, EVERYTHING, PROPERTY)} would instantiate ANY class
     * a stored entry named, with only Jackson's internal gadget denylist in the way — measured on the
     * running artifact: {@code java.net.URI} and {@code java.util.TreeMap} both instantiated from a
     * hand-written type id. Jackson's guidance since 2.10 is an explicit
     * {@code BasicPolymorphicTypeValidator}; the denylist still applies underneath it.
     *
     * <p><b>Why these four, and why {@code java.lang.} is not optional.</b> Derived from the LIVE
     * cache bytes, not from reading the DTOs. Under {@code DefaultTyping.EVERYTHING} a {@code Long}
     * field is written as {@code ["java.lang.Long", 899]} — {@code Integer}, {@code Boolean},
     * {@code Double} and {@code String} are Jackson "natural" types and carry no id, {@code Long} is
     * not — and both cached DTOs carry one ({@code ProductDto.pricePennies},
     * {@code ShopDto.minimumOrderPennies}). Omit that prefix and every cache READ fails, and fails
     * INVISIBLY: {@link RedisCacheErrorHandler#handleCacheGetError} WARN-logs and swallows GET errors,
     * so the symptom is a permanent silent cache miss rather than a 500. After any change here:
     * rebuild, then confirm {@code jtoye.cache.errors} stays 0 under a read-after-write of the
     * products / shops / shopMembership regions.
     *
     * <p><b>Subtype matchers only — never {@code allowIfBaseType}.</b> Under {@code EVERYTHING} the
     * nominal base of a top-level value is {@code java.lang.Object}, and an ALLOWED base type makes
     * Jackson swap in the laissez-faire validator for every subtype of it
     * ({@code StdTypeResolverBuilder.verifyBaseTypeValidity}), so {@code allowIfBaseType("java.lang.")}
     * would silently re-open exactly the hole this closes. {@code CacheSerializerTypeAllowlistTest}
     * holds the round-trip arm (first) and the refusal arm.
     */
    static final List<String> CACHE_TYPE_ID_PREFIXES = List.of(
            "uk.jtoye.",   // ProductDto, ShopDto, Membership, ShopRole, VatRate, AllergenSpan, MediaAssetDto
            "java.util.",  // UUID, ArrayList / List.of, LinkedHashMap / Map.copyOf (ImmutableCollections$*)
            "java.time.",  // OffsetDateTime
            "java.lang."   // Long — see above
    );

    static PolymorphicTypeValidator cacheTypeValidator() {
        BasicPolymorphicTypeValidator.Builder builder = BasicPolymorphicTypeValidator.builder();
        for (String prefix : CACHE_TYPE_ID_PREFIXES) {
            builder = builder.allowIfSubType(prefix);
        }
        return builder.build();
    }

    /**
     * QA-council BE-01: build the Redis value serializer with JSR-310 support.
     *
     * <p>The default {@code new GenericJackson2JsonRedisSerializer()} uses an
     * ObjectMapper with no {@link JavaTimeModule}, so caching any DTO that carries
     * a {@code java.time} type (e.g. {@code ShopDto}/{@code ProductDto.createdAt}
     * is an {@code OffsetDateTime}) threw on the cache write — turning the
     * {@code @Cacheable getShopById}/{@code getProductById} calls into HTTP 500.
     *
     * <p>We register the JavaTimeModule (ISO-8601, not epoch arrays) and keep the
     * serializer's polymorphic default typing so cached values still deserialize
     * back to their concrete type (stores {@code @class}), now gated by
     * {@link #cacheTypeValidator()} (SEC-4). NOTE: flush the Redis
     * "shops"/"products"/"shopMembership" caches on deploy — any entries written by
     * the old serializer are format-incompatible.
     *
     * <p>Static and public so the serializer tests exercise THIS mapper rather than a
     * hand-kept mirror of it: the previous mirror in {@code MembershipSerializerRoundTripTest}
     * would have stayed green over a validator change that killed the cache.
     */
    public static ObjectMapper cacheObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(cacheTypeValidator(),
                ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);
        return mapper;
    }

    public static GenericJackson2JsonRedisSerializer jsonRedisSerializer() {
        return new GenericJackson2JsonRedisSerializer(cacheObjectMapper());
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
