package uk.jtoye.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.security.TenantContext;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TenantAwareCacheKeyGenerator}.
 * Verifies that the generator throws when TenantContext is unset (preventing
 * cross-tenant cache collisions via a "no-tenant" fallback) and produces
 * distinct keys for distinct tenants.
 */
class TenantAwareCacheKeyGeneratorTest {

    private TenantAwareCacheKeyGenerator generator;
    private Method sampleMethod;

    // Stand-in target so Method.getName() has something to report
    @SuppressWarnings("unused")
    public Object sampleCacheable(UUID id) { return null; }

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        generator = new TenantAwareCacheKeyGenerator();
        sampleMethod = TenantAwareCacheKeyGeneratorTest.class.getMethod("sampleCacheable", UUID.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("generate - Throws IllegalStateException when TenantContext is not set")
    void testGenerate_ThrowsWhenTenantContextMissing() {
        TenantContext.clear();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> generator.generate(this, sampleMethod, UUID.randomUUID()));

        assertTrue(ex.getMessage().contains("TenantContext required"),
                "Message should mention TenantContext requirement: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("sampleCacheable"),
                "Message should name the method being called: " + ex.getMessage());
    }

    @Test
    @DisplayName("generate - Produces tenant-prefixed key when TenantContext is set")
    void testGenerate_ProducesTenantPrefixedKey() {
        UUID tenant = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID paramId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        TenantContext.set(tenant);

        Object key = generator.generate(this, sampleMethod, paramId);

        assertEquals(
                "tenant:11111111-1111-1111-1111-111111111111:sampleCacheable:22222222-2222-2222-2222-222222222222",
                key);
    }

    @Test
    @DisplayName("generate - Two different tenants produce different keys for same params")
    void testGenerate_DifferentTenantsDifferentKeys() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID paramId = UUID.randomUUID();

        TenantContext.set(tenantA);
        Object keyA = generator.generate(this, sampleMethod, paramId);

        TenantContext.set(tenantB);
        Object keyB = generator.generate(this, sampleMethod, paramId);

        assertTrue(keyA instanceof String && keyB instanceof String);
        assertTrue(!keyA.equals(keyB),
                "Same params under different tenants must produce different cache keys");
    }
}
