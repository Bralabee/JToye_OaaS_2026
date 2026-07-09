package uk.jtoye.core.security;

import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RateLimitInterceptor.
 * Tests rate limiting logic with mocked Bucket4j dependencies.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class RateLimitInterceptorTest {

    @Mock
    private ProxyManager<String> proxyManager;

    // issue #86 [P1-4]: RateLimitInterceptor now takes ObjectProvider<MeterRegistry>
    // via constructor (fail-open alarm counter). Provide a mock so @InjectMocks can
    // construct it; getIfAvailable() returns null by default → counter absent, which
    // is the intended null-safe behaviour for these pre-existing happy-path tests.
    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private Bucket bucket;

    // issue #88 [P1-6]: promoted to a field so the public-path tests can assert the
    // Redis bucket key argument (rl:public:{ip} vs rate_limit::{tenant}).
    private RemoteBucketBuilder builder;

    @InjectMocks
    private RateLimitInterceptor interceptor;

    private UUID testTenantId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        testTenantId = UUID.randomUUID();

        // Standard mock
        bucket = mock(Bucket.class, withSettings().extraInterfaces(Class.forName("io.github.bucket4j.distributed.BucketProxy")));

        // Set interceptor properties via reflection
        ReflectionTestUtils.setField(interceptor, "rateLimitingEnabled", true);
        ReflectionTestUtils.setField(interceptor, "defaultLimit", 100);
        ReflectionTestUtils.setField(interceptor, "burstCapacity", 20);
        // issue #88 [P1-6]: public-tier limits (mirror the application*.yml defaults).
        ReflectionTestUtils.setField(interceptor, "publicRequestsPerMinute", 30);
        ReflectionTestUtils.setField(interceptor, "publicBurstCapacity", 10);
        ReflectionTestUtils.setField(interceptor, "publicWindowSeconds", 60);
        ReflectionTestUtils.setField(interceptor, "proxyManager", proxyManager);

        // Setup proxy manager mock
        builder = mock(RemoteBucketBuilder.class);
        doReturn(builder).when(proxyManager).builder();
        doAnswer(invocation -> bucket).when(builder).build(anyString(), any(Supplier.class));
    }

    @Test
    void testSuccessfulRequest_UnderLimit() throws Exception {
        // Arrange
        TenantContext.set(testTenantId);
        when(request.getRequestURI()).thenReturn("/api/customers");

        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(true);
        when(probe.getRemainingTokens()).thenReturn(45L);

        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(probe);

        // Act
        boolean result = interceptor.preHandle(request, response, new Object());

        // Assert
        assertTrue(result, "Request should be allowed");
        verify(response).setHeader("X-RateLimit-Limit", "100");
        verify(response).setHeader("X-RateLimit-Remaining", "45");
        verify(response).setHeader(eq("X-RateLimit-Reset"), anyString());
        verify(bucket).tryConsumeAndReturnRemaining(1);

        // Cleanup
        TenantContext.clear();
    }

    @Test
    void testRateLimitExceeded_Returns429() throws Exception {
        // Arrange
        TenantContext.set(testTenantId);
        when(request.getRequestURI()).thenReturn("/api/orders");

        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(false);
        when(probe.getNanosToWaitForRefill()).thenReturn(30_000_000_000L); // 30 seconds

        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(probe);

        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(writer);

        // Act
        boolean result = interceptor.preHandle(request, response, new Object());

        // Assert
        assertFalse(result, "Request should be blocked");
        verify(response).setStatus(429);
        verify(response).setHeader("X-RateLimit-Limit", "100");
        verify(response).setHeader("X-RateLimit-Remaining", "0");
        verify(response).setHeader(eq("Retry-After"), eq("30"));
        verify(response).setContentType("application/json");

        String responseBody = stringWriter.toString();
        assertTrue(responseBody.contains("Too Many Requests"));
        assertTrue(responseBody.contains("30 seconds"));

        // Cleanup
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testTenantIsolation_DifferentTenantsHaveSeparateLimits() throws Exception {
        // Arrange
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        when(request.getRequestURI()).thenReturn("/api/products");

        ConsumptionProbe probeA = mock(ConsumptionProbe.class);
        when(probeA.isConsumed()).thenReturn(false); // Tenant A exhausted
        when(probeA.getNanosToWaitForRefill()).thenReturn(10_000_000_000L);

        ConsumptionProbe probeB = mock(ConsumptionProbe.class);
        when(probeB.isConsumed()).thenReturn(true); // Tenant B still has tokens
        when(probeB.getRemainingTokens()).thenReturn(50L);

        // Mock different buckets for different tenants
        Bucket bucketA = mock(Bucket.class, withSettings().extraInterfaces(Class.forName("io.github.bucket4j.distributed.BucketProxy")));
        Bucket bucketB = mock(Bucket.class, withSettings().extraInterfaces(Class.forName("io.github.bucket4j.distributed.BucketProxy")));

        when(bucketA.tryConsumeAndReturnRemaining(1)).thenReturn(probeA);
        when(bucketB.tryConsumeAndReturnRemaining(1)).thenReturn(probeB);

        RemoteBucketBuilder builder = mock(RemoteBucketBuilder.class);
        doReturn(builder).when(proxyManager).builder();
        doAnswer(invocation -> bucketA).when(builder).build(argThat((String s) -> s != null && s.contains(tenantA.toString())), any(Supplier.class));
        doAnswer(invocation -> bucketB).when(builder).build(argThat((String s) -> s != null && s.contains(tenantB.toString())), any(Supplier.class));

        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(writer);

        // Act & Assert - Tenant A exhausted
        TenantContext.set(tenantA);
        boolean resultA = interceptor.preHandle(request, response, new Object());
        assertFalse(resultA, "Tenant A should be blocked");
        TenantContext.clear();

        // Act & Assert - Tenant B still has capacity
        TenantContext.set(tenantB);
        boolean resultB = interceptor.preHandle(request, response, new Object());
        assertTrue(resultB, "Tenant B should be allowed");
        TenantContext.clear();
    }

    @Test
    void testExcludedEndpoint_HealthCheck_NotRateLimited() throws Exception {
        // Arrange
        TenantContext.set(testTenantId);
        when(request.getRequestURI()).thenReturn("/health");

        // Act
        boolean result = interceptor.preHandle(request, response, new Object());

        // Assert
        assertTrue(result, "Health check should bypass rate limiting");
        verify(bucket, never()).tryConsumeAndReturnRemaining(anyLong());

        // Cleanup
        TenantContext.clear();
    }

    @Test
    void testExcludedEndpoint_Actuator_NotRateLimited() throws Exception {
        // Arrange
        TenantContext.set(testTenantId);
        when(request.getRequestURI()).thenReturn("/actuator/health");

        // Act
        boolean result = interceptor.preHandle(request, response, new Object());

        // Assert
        assertTrue(result, "Actuator endpoint should bypass rate limiting");
        verify(bucket, never()).tryConsumeAndReturnRemaining(anyLong());

        // Cleanup
        TenantContext.clear();
    }

    @Test
    void testExcludedEndpoint_Swagger_NotRateLimited() throws Exception {
        // Arrange
        TenantContext.set(testTenantId);
        when(request.getRequestURI()).thenReturn("/swagger-ui/index.html");

        // Act
        boolean result = interceptor.preHandle(request, response, new Object());

        // Assert
        assertTrue(result, "Swagger UI should bypass rate limiting");
        verify(bucket, never()).tryConsumeAndReturnRemaining(anyLong());

        // Cleanup
        TenantContext.clear();
    }

    @Test
    void testNoTenantContext_RequestAllowed() throws Exception {
        // Arrange
        TenantContext.clear(); // Ensure no tenant context
        when(request.getRequestURI()).thenReturn("/api/customers");

        // Act
        boolean result = interceptor.preHandle(request, response, new Object());

        // Assert
        assertTrue(result, "Request should be allowed when no tenant context (warning logged)");
        verify(bucket, never()).tryConsumeAndReturnRemaining(anyLong());
    }

    @Test
    void testRateLimitingDisabled_AllRequestsAllowed() throws Exception {
        // Arrange
        ReflectionTestUtils.setField(interceptor, "rateLimitingEnabled", false);
        TenantContext.set(testTenantId);
        when(request.getRequestURI()).thenReturn("/api/orders");

        // Act
        boolean result = interceptor.preHandle(request, response, new Object());

        // Assert
        assertTrue(result, "Request should be allowed when rate limiting is disabled");
        verify(bucket, never()).tryConsumeAndReturnRemaining(anyLong());

        // Cleanup
        TenantContext.clear();
    }

    @Test
    void testProxyManagerNull_RateLimitingBypassed() throws Exception {
        // Arrange
        ReflectionTestUtils.setField(interceptor, "proxyManager", null);
        TenantContext.set(testTenantId);
        when(request.getRequestURI()).thenReturn("/api/products");

        // Act
        boolean result = interceptor.preHandle(request, response, new Object());

        // Assert
        assertTrue(result, "Request should be allowed when proxyManager is null");

        // Cleanup
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // issue #88 [P1-6]: tenant-less /public/** IP-keyed rate limiting.
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void testPublicPath_UnderLimit_AllowedAndKeyedByClientIp() throws Exception {
        // Arrange: NO tenant context (guest request), a public path, and an XFF client IP.
        TenantContext.clear();
        when(request.getRequestURI()).thenReturn("/public/shops");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7");

        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(true);
        when(probe.getRemainingTokens()).thenReturn(29L);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(probe);

        // Act
        boolean result = interceptor.preHandle(request, response, new Object());

        // Assert: allowed, public-tier headers, and the bucket keyed by rl:public:{ip}
        // (NOT the tenant "rate_limit::" namespace).
        assertTrue(result, "Public request under limit should be allowed");
        verify(response).setHeader("X-RateLimit-Limit", "30");
        verify(response).setHeader("X-RateLimit-Remaining", "29");
        verify(bucket).tryConsumeAndReturnRemaining(1);
        verify(builder).build(
                argThat((String key) -> key.startsWith("rl:public:") && key.contains("203.0.113.7")),
                any(Supplier.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPublicPath_OverLimit_Returns429WithRetryAfter_NoTenantIdLeaked() throws Exception {
        // Arrange: tenant-less public flood.
        TenantContext.clear();
        when(request.getRequestURI()).thenReturn("/public/shops/acme/orders");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.99");

        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(false);
        when(probe.getNanosToWaitForRefill()).thenReturn(15_000_000_000L); // 15 seconds

        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(probe);

        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(writer);

        // Act
        boolean result = interceptor.preHandle(request, response, new Object());

        // Assert: 429 + Retry-After, keyed rl:public:, and NO tenantId in the guest body.
        assertFalse(result, "Public request over limit should be blocked");
        verify(response).setStatus(429);
        verify(response).setHeader("X-RateLimit-Limit", "30");
        verify(response).setHeader("X-RateLimit-Remaining", "0");
        verify(response).setHeader(eq("Retry-After"), eq("15"));
        verify(response).setContentType("application/json");
        verify(builder).build(argThat((String key) -> key.startsWith("rl:public:")), any(Supplier.class));

        String body = stringWriter.toString();
        assertTrue(body.contains("Too Many Requests"), "body should be the generic 429 message");
        assertFalse(body.contains("tenantId"), "public 429 body must not leak a tenantId field");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testTenantPath_UsesTenantKeyspace_NotPublic() throws Exception {
        // Arrange: a tenant-present API request must still use the tenant bucket keyspace.
        TenantContext.set(testTenantId);
        when(request.getRequestURI()).thenReturn("/api/v1/products");

        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(true);
        when(probe.getRemainingTokens()).thenReturn(50L);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(probe);

        // Act
        boolean result = interceptor.preHandle(request, response, new Object());

        // Assert: keyed by rate_limit::{tenant}, independent of the public namespace.
        assertTrue(result);
        verify(builder).build(
                argThat((String key) -> key.startsWith("rate_limit::") && key.contains(testTenantId.toString())),
                any(Supplier.class));

        // Cleanup
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPublicPath_RedisThrows_FailsOpen() throws Exception {
        // Arrange: tenant-less public request where the Redis bucket build blows up.
        TenantContext.clear();
        when(request.getRequestURI()).thenReturn("/public/shops");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7");

        RemoteBucketBuilder throwingBuilder = mock(RemoteBucketBuilder.class);
        doReturn(throwingBuilder).when(proxyManager).builder();
        when(throwingBuilder.build(anyString(), any(Supplier.class)))
                .thenThrow(new RuntimeException("simulated Redis outage — connection refused"));

        // Act
        boolean result = interceptor.preHandle(request, response, new Object());

        // Assert: fail OPEN (no 429, no 500) — issue #86 semantics preserved for public.
        assertTrue(result, "Public path must fail open when Redis is unavailable");
        verify(response, never()).setStatus(429);
    }
}
