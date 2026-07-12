package uk.jtoye.core.security;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtTenantFilterTest {

    private JwtTenantFilter filter;

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;
    // issue #98 [P2-7]: null-safe MeterRegistry provider; getIfAvailable()
    // returns null by default → counter absent, the intended no-op path here.
    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    @BeforeEach
    void setUp() {
        // issue #98 [P2-7]: JwtTenantFilter now takes a null-safe
        // ObjectProvider<MeterRegistry>. These behaviours don't assert on the
        // counter, so a mock provider (getIfAvailable() -> null by default) is
        // enough; the isolation-failure counter is exercised in the dedicated
        // JwtTenantFilterMetricsTest.
        filter = new JwtTenantFilter(meterRegistryProvider);
        // Ensure OncePerRequestFilter internals work with our mock request
        lenient().when(request.getAttribute(any())).thenReturn(null);
        lenient().doNothing().when(request).setAttribute(any(), any());
        lenient().when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("JWT with tenant_id claim sets TenantContext")
    void jwtWithTenantIdClaim_setsTenantContext() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Jwt jwt = buildJwt(Map.of("tenant_id", tenantId.toString()));
        setAuthentication(jwt);

        filter.doFilter(request, response, filterChain);

        assertTrue(TenantContext.get().isPresent());
        assertEquals(tenantId, TenantContext.get().get());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("JWT with tenantId (camelCase) claim sets TenantContext when tenant_id absent")
    void jwtWithCamelCaseTenantId_setsTenantContext() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Jwt jwt = buildJwt(Map.of("tenantId", tenantId.toString()));
        setAuthentication(jwt);

        filter.doFilter(request, response, filterChain);

        assertTrue(TenantContext.get().isPresent());
        assertEquals(tenantId, TenantContext.get().get());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("JWT with tid claim sets TenantContext when other claims absent")
    void jwtWithTidClaim_setsTenantContext() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Jwt jwt = buildJwt(Map.of("tid", tenantId.toString()));
        setAuthentication(jwt);

        filter.doFilter(request, response, filterChain);

        assertTrue(TenantContext.get().isPresent());
        assertEquals(tenantId, TenantContext.get().get());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("JWT with no tenant claims leaves TenantContext empty")
    void jwtWithNoTenantClaims_tenantContextRemainsEmpty() throws Exception {
        Jwt jwt = buildJwt(Map.of("sub", "user123"));
        setAuthentication(jwt);

        filter.doFilter(request, response, filterChain);

        assertTrue(TenantContext.get().isEmpty());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("No authentication in SecurityContext proceeds without setting tenant")
    void noAuthentication_filterChainProceeds() throws Exception {
        // SecurityContextHolder has no authentication set

        filter.doFilter(request, response, filterChain);

        assertTrue(TenantContext.get().isEmpty());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("JWT with malformed UUID in tenant_id claim does not set TenantContext")
    void jwtWithMalformedUuid_tenantContextRemainsEmpty() throws Exception {
        Jwt jwt = buildJwt(Map.of("tenant_id", "not-a-valid-uuid"));
        setAuthentication(jwt);

        filter.doFilter(request, response, filterChain);

        assertTrue(TenantContext.get().isEmpty());
        verify(filterChain).doFilter(request, response);
    }

    private Jwt buildJwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private void setAuthentication(Jwt jwt) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(jwt);
        SecurityContextImpl context = new SecurityContextImpl();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }
}
