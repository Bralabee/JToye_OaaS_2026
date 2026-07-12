package uk.jtoye.core.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import uk.jtoye.core.tenant.TenantLifecycleService;
import uk.jtoye.core.tenant.TenantStatus;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TenantStatusInterceptor} (issue #102 AC1): the cheap
 * request-path decision matrix. End-to-end enforcement against real endpoints
 * runs in {@code TenantLifecycleAdminIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class TenantStatusInterceptorTest {

    @Mock private TenantLifecycleService lifecycleService;

    private final UUID tenantId = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private TenantStatusInterceptor interceptor() {
        return new TenantStatusInterceptor(lifecycleService);
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    @DisplayName("allows tenant-less requests without a status lookup")
    void noTenantContext_allowed() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor().preHandle(request("/api/v1/shops"), response, new Object()));
        verifyNoInteractions(lifecycleService);
    }

    @Test
    @DisplayName("allows an ACTIVE tenant")
    void activeTenant_allowed() throws Exception {
        TenantContext.set(tenantId);
        when(lifecycleService.statusOf(tenantId)).thenReturn(TenantStatus.ACTIVE);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor().preHandle(request("/api/v1/shops"), response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("rejects a SUSPENDED tenant with 403 + JSON body")
    void suspendedTenant_rejected() throws Exception {
        TenantContext.set(tenantId);
        when(lifecycleService.statusOf(tenantId)).thenReturn(TenantStatus.SUSPENDED);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor().preHandle(request("/api/v1/orders"), response, new Object()));
        assertEquals(403, response.getStatus());
        assertEquals("application/json", response.getContentType());
        assertTrue(response.getContentAsString().contains("suspended"));
    }

    @Test
    @DisplayName("rejects an OFFBOARDED tenant with 403 + JSON body")
    void offboardedTenant_rejected() throws Exception {
        TenantContext.set(tenantId);
        when(lifecycleService.statusOf(tenantId)).thenReturn(TenantStatus.OFFBOARDED);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor().preHandle(request("/api/v1/products"), response, new Object()));
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("offboarded"));
    }

    @Test
    @DisplayName("exempts the admin tenant-lifecycle surface — no lockout for suspending your own tenant")
    void adminTenantsSurface_exempt() throws Exception {
        TenantContext.set(tenantId);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor().preHandle(
                request("/api/v1/admin/tenants/" + tenantId + "/reactivate"), response, new Object()));
        assertTrue(interceptor().preHandle(
                request("/api/v1/admin/tenants"), response, new Object()));
        verify(lifecycleService, never()).statusOf(any());
    }

    @Test
    @DisplayName("exempts public storefront paths and infra endpoints (webhook must never be blocked)")
    void publicAndInfraPaths_exempt() throws Exception {
        TenantContext.set(tenantId);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor().preHandle(request("/public/payments/webhook"), response, new Object()));
        assertTrue(interceptor().preHandle(request("/api/v1/public/shops"), response, new Object()));
        assertTrue(interceptor().preHandle(request("/health"), response, new Object()));
        assertTrue(interceptor().preHandle(request("/actuator/prometheus"), response, new Object()));
        verify(lifecycleService, never()).statusOf(any());
    }
}
