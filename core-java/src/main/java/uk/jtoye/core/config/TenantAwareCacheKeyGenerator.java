package uk.jtoye.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.interceptor.KeyGenerator;
import uk.jtoye.core.security.TenantContext;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tenant-aware cache key generator.
 *
 * Ensures cache keys are scoped by tenant ID to prevent cross-tenant data leakage.
 * Key format: "tenant:{tenantId}:{method}:{params}"
 *
 * Example keys:
 * - tenant:123e4567-e89b-12d3-a456-426614174000:getProductById:9876dcba-e89b-12d3-a456-426614174999
 * - tenant:123e4567-e89b-12d3-a456-426614174000:getShopById:5432fedc-e89b-12d3-a456-426614174888
 *
 * Security:
 * - All cache keys are prefixed with tenant ID from TenantContext.
 * - If TenantContext is unset, the generator throws IllegalStateException. This is
 *   deliberate: a "no-tenant" fallback would collapse all unset-context callers to
 *   a single shared cache slot, producing cross-tenant cache hits on the next request
 *   that does have a tenant. Fail fast instead.
 */
public class TenantAwareCacheKeyGenerator implements KeyGenerator {
    private static final Logger log = LoggerFactory.getLogger(TenantAwareCacheKeyGenerator.class);

    @Override
    public Object generate(Object target, Method method, Object... params) {
        // Get tenant ID from context — must be set for any @Cacheable call.
        UUID tenantId = TenantContext.get().orElseThrow(() -> new IllegalStateException(
                "TenantContext required for cacheable call: "
                        + target.getClass().getSimpleName() + "#" + method.getName()));

        // Build cache key with tenant isolation
        String paramString = Arrays.stream(params)
                .map(obj -> obj == null ? "null" : obj.toString())
                .collect(Collectors.joining(":"));

        String cacheKey = String.format("tenant:%s:%s:%s",
                tenantId.toString(),
                method.getName(),
                paramString.isEmpty() ? "no-params" : paramString);

        log.debug("Generated tenant-aware cache key: {}", cacheKey);

        return cacheKey;
    }
}
