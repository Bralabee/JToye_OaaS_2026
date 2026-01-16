package uk.jtoye.core.security;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
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

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private Bucket bucket;

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
        ReflectionTestUtils.setField(interceptor, "proxyManager", proxyManager);

        // Setup proxy manager mock
        RemoteBucketBuilder builder = mock(RemoteBucketBuilder.class);
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
}
