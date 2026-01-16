package uk.jtoye.core.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration for Bucket4j rate limiting with Redis backend.
 * Provides distributed, tenant-aware rate limiting across multiple core-java instances.
 *
 * Rate limit strategy:
 * - Standard tier: 100 requests/minute per tenant
 * - Premium tier: 1000 requests/minute per tenant
 * - Internal tier: No rate limiting (for service-to-service calls)
 *
 * Redis key pattern: rate_limit::{tenantId}
 */
@Configuration
public class RateLimitConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${rate-limiting.enabled:true}")
    private boolean rateLimitingEnabled;

    /**
     * Creates a Lettuce-based proxy manager for distributed rate limiting.
     * Uses Redis as the shared state store for rate limit buckets.
     *
     * @return LettuceBasedProxyManager configured for tenant-aware rate limiting
     */
    @Bean
    public LettuceBasedProxyManager<String> lettuceBasedProxyManager() {
        if (!rateLimitingEnabled) {
            return null; // Skip bean creation if rate limiting is disabled
        }

        // Build Redis URI
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort);

        if (redisPassword != null && !redisPassword.isEmpty()) {
            uriBuilder.withPassword(redisPassword.toCharArray());
        }

        RedisURI redisUri = uriBuilder.build();

        // Create Redis client
        RedisClient redisClient = RedisClient.create(redisUri);

        // Create connection with String keys and byte array values
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        StatefulRedisConnection<String, byte[]> connection = redisClient.connect(codec);

        // Create proxy manager with expiration strategy
        // Buckets expire after 2 minutes of inactivity to prevent memory bloat
        return LettuceBasedProxyManager.builderFor(connection)
                .withExpirationStrategy(
                    ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(2))
                )
                .build();
    }

    /**
     * Check if rate limiting is enabled.
     *
     * @return true if rate limiting is enabled, false otherwise
     */
    public boolean isRateLimitingEnabled() {
        return rateLimitingEnabled;
    }
}
