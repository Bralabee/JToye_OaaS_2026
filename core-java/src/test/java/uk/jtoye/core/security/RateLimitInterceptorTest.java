package uk.jtoye.core.security;

import io.github.bucket4j.BucketConfiguration;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // issue #413: a REAL mapper, not a mock. The interceptor now serialises a
    // ProblemDetail through it, and a mock would return null — every body assertion
    // below would then be asserting on the string "null" while looking green.
    // Jackson2ObjectMapperBuilder is what Spring Boot's auto-configured mapper is built
    // with, and it is what registers ProblemDetailJacksonMixin — the mixin that flattens
    // the extra properties to top level instead of nesting them under "properties".
    @Spy
    private ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

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
        verify(response).setHeader("X-RateLimit-Limit", "120");  // API-8: capacity (100 + burst 20), not the refill rate
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
        verify(response).setHeader("X-RateLimit-Limit", "120");  // API-8: capacity (100 + burst 20), not the refill rate
        verify(response).setHeader("X-RateLimit-Remaining", "0");
        verify(response).setHeader(eq("Retry-After"), eq("30"));
        // issue #413: RFC 7807 media type, and an explicit charset — getWriter() otherwise
        // defaults to ISO-8859-1, which is what the pre-fix responses really sent.
        verify(response).setContentType("application/problem+json");
        verify(response).setCharacterEncoding("UTF-8");

        // Assert the CONTRACT, not a substring. `contains("Too Many Requests")` passed
        // against the old hand-rolled {"error":...} body too, so it could not tell the two
        // shapes apart — parse it and assert the RFC 7807 fields by name.
        JsonNode body = objectMapper.readTree(stringWriter.toString());
        assertEquals("https://jtoye.uk/errors/rate-limited", body.path("type").asText());
        assertEquals("Too Many Requests", body.path("title").asText());
        assertEquals(429, body.path("status").asInt());
        assertEquals("Rate limit exceeded. Please try again in 30 seconds.", body.path("detail").asText());

        // The wait as a TYPED number, not mined out of prose (#409/#410).
        assertTrue(body.path("retryAfterSeconds").isNumber(), "retryAfterSeconds must be a number, not a string");
        assertEquals(30, body.path("retryAfterSeconds").asLong());

        // The old shape is GONE, not merely supplemented — a client reading `detail` per the
        // documented contract must not be handed `message` instead.
        assertTrue(body.path("error").isMissingNode(), "the hand-rolled `error` field must be gone");
        assertTrue(body.path("message").isMissingNode(), "the hand-rolled `message` field must be gone");

        // Flattened to top level, NOT nested under "properties" — this is the mixin doing
        // its job, and it is the one thing a wrong ObjectMapper would silently change.
        assertTrue(body.path("properties").isMissingNode(),
                "extra members must be flattened by ProblemDetailJacksonMixin, not nested");

        // Cleanup
        TenantContext.clear();
    }

    @Test
    void tenantPath429_carriesTenantIdAsATypedMember() throws Exception {
        // Incremental betterment: tenantId existed in the old body and is a diagnostic an
        // authenticated caller already knows. Reshaping the body must not drop it.
        TenantContext.set(testTenantId);
        when(request.getRequestURI()).thenReturn("/api/orders");

        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(false);
        when(probe.getNanosToWaitForRefill()).thenReturn(7_000_000_000L);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(probe);

        StringWriter stringWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

        interceptor.preHandle(request, response, new Object());

        JsonNode body = objectMapper.readTree(stringWriter.toString());
        assertEquals(testTenantId.toString(), body.path("tenantId").asText());

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
        verify(response).setHeader("X-RateLimit-Limit", "40");   // API-8: capacity (30 + burst 10), not the refill rate
        verify(response).setHeader("X-RateLimit-Remaining", "29");
        verify(bucket).tryConsumeAndReturnRemaining(1);
        verify(builder).build(
                argThat((String key) -> key.startsWith("rl:public:") && key.contains("203.0.113.7")),
                any(Supplier.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testVersionedPublicAlias_TreatedAsPublicPath_KeyedByClientIp() throws Exception {
        // issue #97 [P2-6]: /api/v1/public/** is the canonical versioned alias of the
        // /public/** surface and must hit the SAME IP-keyed public tier — otherwise a
        // guest flood could pick the alias and bypass the public bucket entirely.
        TenantContext.clear();
        when(request.getRequestURI()).thenReturn("/api/v1/public/shops");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.42");

        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(true);
        when(probe.getRemainingTokens()).thenReturn(29L);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(probe);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result, "Versioned public alias request under limit should be allowed");
        verify(response).setHeader("X-RateLimit-Limit", "40");   // API-8: capacity (30 + burst 10), not the refill rate
        verify(builder).build(
                argThat((String key) -> key.startsWith("rl:public:") && key.contains("203.0.113.42")),
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
        verify(response).setHeader("X-RateLimit-Limit", "40");   // API-8: capacity (30 + burst 10), not the refill rate
        verify(response).setHeader("X-RateLimit-Remaining", "0");
        verify(response).setHeader(eq("Retry-After"), eq("15"));
        verify(response).setContentType("application/problem+json");
        verify(response).setCharacterEncoding("UTF-8");
        verify(builder).build(argThat((String key) -> key.startsWith("rl:public:")), any(Supplier.class));

        // issue #413: same RFC 7807 contract as the tenant path, asserted by field name.
        JsonNode body = objectMapper.readTree(stringWriter.toString());
        assertEquals("https://jtoye.uk/errors/rate-limited", body.path("type").asText());
        assertEquals("Too Many Requests", body.path("title").asText());
        assertEquals(429, body.path("status").asInt());
        assertEquals(15, body.path("retryAfterSeconds").asLong());

        // The load-bearing omission, asserted TWO ways. `isMissingNode()` is the precise
        // check; the raw-substring check additionally catches a tenant id leaking anywhere
        // else in the payload — inside `detail`, say — which a field lookup cannot see.
        assertTrue(body.path("tenantId").isMissingNode(), "public 429 must not carry a tenantId member");
        assertFalse(stringWriter.toString().contains("tenantId"),
                "public 429 body must not mention tenantId anywhere");
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

    // ------------------------------------------------------------------
    // API-8 (QA council 20260902-134741): X-RateLimit-Remaining exceeded
    // X-RateLimit-Limit on EVERY response, on BOTH buckets.
    //
    // Adjudication A4: there are two buckets and two separate defects. The
    // tenant bucket is 100/min + burst 20 and the public IP-keyed bucket
    // (#88) is 30/min + burst 10 by default, widened to 600/120 for the local
    // compose runtime. Both advertised the REFILL RATE as Limit while counting
    // Remaining out of the bucket's CAPACITY (rate + burst), so live headers
    // read "Limit: 100, Remaining: 119" and "Limit: 600, Remaining: 719" - a
    // client computing remaining/limit for backoff gets a ratio above 1.
    //
    // These two tests take the capacity from the SAME BucketConfiguration the
    // interceptor hands Bucket4j, not from a literal typed here: if the header
    // and the bucket ever disagree again, the assertion fails whatever the
    // configured numbers are.
    // ------------------------------------------------------------------

    /** Collects every setHeader(name, value) call into a map, in call order. */
    private Map<String, String> capturedHeaders() {
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(response, atLeastOnce()).setHeader(names.capture(), values.capture());
        List<String> n = names.getAllValues();
        List<String> v = values.getAllValues();
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < n.size(); i++) {
            out.put(n.get(i), v.get(i));
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenantHeaders_advertiseBucketCapacity_andRemainingNeverExceedsLimit() throws Exception {
        TenantContext.set(testTenantId);
        when(request.getRequestURI()).thenReturn("/api/v1/shops");

        // 119 is the live observation: a 120-token bucket with one token just consumed.
        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(true);
        when(probe.getRemainingTokens()).thenReturn(119L);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(probe);

        assertTrue(interceptor.preHandle(request, response, new Object()));

        ArgumentCaptor<Supplier<BucketConfiguration>> configCaptor =
                ArgumentCaptor.forClass(Supplier.class);
        verify(builder).build(anyString(), configCaptor.capture());
        long realCapacity = configCaptor.getValue().get().getBandwidths()[0].getCapacity();

        Map<String, String> headers = capturedHeaders();
        long limit = Long.parseLong(headers.get("X-RateLimit-Limit"));
        long remaining = Long.parseLong(headers.get("X-RateLimit-Remaining"));

        assertEquals(realCapacity, limit,
                "X-RateLimit-Limit must advertise the capacity the bucket is actually built with "
                + "(default-limit + burst-capacity), not the refill rate");
        assertTrue(remaining <= limit,
                "X-RateLimit-Remaining (" + remaining + ") must never exceed X-RateLimit-Limit ("
                + limit + ") - a client computing remaining/limit for backoff got a ratio above 1");

        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicHeaders_advertiseBucketCapacity_andRemainingNeverExceedsLimit() throws Exception {
        // The second bucket, the one CLAUDE.md never documented. Same defect, own code path.
        TenantContext.clear();
        when(request.getRequestURI()).thenReturn("/api/v1/public/shops");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.11");

        // 39 = the public bucket full (30 + burst 10) with one token consumed; the same
        // shape as the live 719-out-of-600 reading under the compose 600/120 override.
        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(true);
        when(probe.getRemainingTokens()).thenReturn(39L);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(probe);

        assertTrue(interceptor.preHandle(request, response, new Object()));

        ArgumentCaptor<Supplier<BucketConfiguration>> configCaptor =
                ArgumentCaptor.forClass(Supplier.class);
        verify(builder).build(anyString(), configCaptor.capture());
        long realCapacity = configCaptor.getValue().get().getBandwidths()[0].getCapacity();

        Map<String, String> headers = capturedHeaders();
        long limit = Long.parseLong(headers.get("X-RateLimit-Limit"));
        long remaining = Long.parseLong(headers.get("X-RateLimit-Remaining"));

        assertEquals(realCapacity, limit,
                "the public IP-keyed bucket must advertise its own capacity "
                + "(public.requests-per-minute + public.burst)");
        assertTrue(remaining <= limit,
                "X-RateLimit-Remaining (" + remaining + ") must never exceed X-RateLimit-Limit ("
                + limit + ") on the public bucket either");
    }
}
