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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "rate-limiting.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    // issue #86 [P1-4]: reuse the existing per-profile Redis command timeout
    // (application.yml 2000ms / prod 3000ms / staging 2500ms) rather than a
    // hardcoded literal. Spring Boot binds the "2000ms"-style string to Duration.
    @Value("${spring.data.redis.timeout:2000ms}")
    private Duration redisCommandTimeout;

    @Value("${rate-limiting.enabled:true}")
    private boolean rateLimitingEnabled;

    /**
     * Creates a Lettuce-based proxy manager for distributed rate limiting.
     * Uses Redis as the shared state store for rate limit buckets.
     *
     * <p>issue #86 [P1-4]: an explicit Lettuce command timeout
     * ({@link #redisCommandTimeout}, sourced from {@code spring.data.redis.timeout})
     * replaces Lettuce's 60s default. Without it, a Redis outage made every
     * rate-limited request hang ~60s before the {@code RateLimitInterceptor} could
     * fail open. Applied both on the {@link RedisURI} (belt) and via
     * {@link RedisClient#setDefaultTimeout(Duration)} (braces) so the bounded
     * timeout holds regardless of which command path Lettuce takes.
     *
     * @return LettuceBasedProxyManager configured for tenant-aware rate limiting
     */
    @Bean
    public LettuceBasedProxyManager<String> lettuceBasedProxyManager() {
        if (!rateLimitingEnabled) {
            return null; // Skip bean creation if rate limiting is disabled
        }

        // Build Redis URI with an explicit, bounded command timeout (issue #86).
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .withTimeout(redisCommandTimeout);

        if (redisPassword != null && !redisPassword.isEmpty()) {
            uriBuilder.withPassword(redisPassword.toCharArray());
        }

        RedisURI redisUri = uriBuilder.build();

        // Create Redis client
        RedisClient redisClient = RedisClient.create(redisUri);
        redisClient.setDefaultTimeout(redisCommandTimeout);

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
