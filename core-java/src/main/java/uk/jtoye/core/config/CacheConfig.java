package uk.jtoye.core.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
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
     * back to their concrete type (stores {@code @class}). NOTE: flush the Redis
     * "shops"/"products" caches on deploy — any entries written by the old
     * serializer are format-incompatible.
     */
    private GenericJackson2JsonRedisSerializer jsonRedisSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(mapper);
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
