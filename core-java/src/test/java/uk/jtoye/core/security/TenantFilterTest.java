package uk.jtoye.core.security;

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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantFilterTest {

    private TenantFilter filter;

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new TenantFilter();
        // Ensure OncePerRequestFilter internals work with our mock request
        lenient().when(request.getAttribute(any())).thenReturn(null);
        lenient().doNothing().when(request).setAttribute(any(), any());
        lenient().when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Valid X-Tenant-Id header sets TenantContext")
    void validTenantHeader_setsTenantContext() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(request.getHeader("X-Tenant-Id")).thenReturn(tenantId.toString());

        // Verify tenant is set during filter chain execution
        doAnswer(invocation -> {
            assertTrue(TenantContext.get().isPresent());
            assertEquals(tenantId, TenantContext.get().get());
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Invalid UUID in X-Tenant-Id header returns 400 error")
    void invalidUuidHeader_returns400() throws Exception {
        when(request.getHeader("X-Tenant-Id")).thenReturn("not-a-uuid");

        filter.doFilter(request, response, filterChain);

        verify(response).sendError(eq(400), contains("Invalid X-Tenant-Id"));
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("No X-Tenant-Id header proceeds without setting tenant")
    void noTenantHeader_filterChainProceeds() throws Exception {
        when(request.getHeader("X-Tenant-Id")).thenReturn(null);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("X-Tenant-Id header is ignored when TenantContext already set by JWT")
    void tenantAlreadySetByJwt_headerIgnored() throws Exception {
        UUID jwtTenantId = UUID.randomUUID();
        UUID headerTenantId = UUID.randomUUID();

        // Simulate JWT filter having already set the tenant
        TenantContext.set(jwtTenantId);
        when(request.getHeader("X-Tenant-Id")).thenReturn(headerTenantId.toString());

        // Verify JWT tenant is preserved during filter chain execution
        doAnswer(invocation -> {
            assertTrue(TenantContext.get().isPresent());
            assertEquals(jwtTenantId, TenantContext.get().get());
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("TenantContext is cleared in finally block after filter chain completes")
    void tenantContextClearedAfterFilterChain() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(request.getHeader("X-Tenant-Id")).thenReturn(tenantId.toString());

        filter.doFilter(request, response, filterChain);

        // After filter completes, TenantContext should be cleared
        assertTrue(TenantContext.get().isEmpty());
    }
}
