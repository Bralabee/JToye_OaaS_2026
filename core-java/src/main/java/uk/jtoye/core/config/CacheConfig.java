package uk.jtoye.core.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
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
public class CacheConfig {

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
                        RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer())
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
     * Tenant-aware cache key generator bean.
     * Ensures cache keys are scoped to tenant ID to prevent cross-tenant data leakage.
     */
    @Bean
    public TenantAwareCacheKeyGenerator tenantAwareCacheKeyGenerator() {
        return new TenantAwareCacheKeyGenerator();
    }
}
