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
 * Key format: "tenant:{tenantId}:{cacheName}:{method}:{params}"
 * 
 * Example keys:
 * - tenant:123e4567-e89b-12d3-a456-426614174000:products:getProductById:9876dcba-e89b-12d3-a456-426614174999
 * - tenant:123e4567-e89b-12d3-a456-426614174000:shops:getShopById:5432fedc-e89b-12d3-a456-426614174888
 * 
 * Security:
 * - All cache keys are prefixed with tenant ID from TenantContext
 * - If tenant context is not set, falls back to "no-tenant" to avoid null pointer exceptions
 * - Prevents accidental cross-tenant cache hits
 */
public class TenantAwareCacheKeyGenerator implements KeyGenerator {
    private static final Logger log = LoggerFactory.getLogger(TenantAwareCacheKeyGenerator.class);

    @Override
    public Object generate(Object target, Method method, Object... params) {
        // Get tenant ID from context (or default to "no-tenant" if not set)
        UUID tenantId = TenantContext.get().orElse(null);
        
        if (tenantId == null) {
            log.warn("Tenant context not set for cache key generation in {}#{}. Using 'no-tenant' fallback.",
                    target.getClass().getSimpleName(), method.getName());
        }

        // Build cache key with tenant isolation
        String paramString = Arrays.stream(params)
                .map(obj -> obj == null ? "null" : obj.toString())
                .collect(Collectors.joining(":"));

        String cacheKey = String.format("tenant:%s:%s:%s",
                tenantId != null ? tenantId.toString() : "no-tenant",
                method.getName(),
                paramString.isEmpty() ? "no-params" : paramString);

        log.debug("Generated tenant-aware cache key: {}", cacheKey);

        return cacheKey;
    }
}
