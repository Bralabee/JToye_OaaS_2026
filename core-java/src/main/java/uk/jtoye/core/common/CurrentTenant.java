package uk.jtoye.core.common;

import uk.jtoye.core.exception.MissingTenantContextException;
import uk.jtoye.core.security.TenantContext;

import java.util.UUID;

public final class CurrentTenant {
    private CurrentTenant() {}

    /**
     * The current request's tenant, or {@link MissingTenantContextException}
     * (→ HTTP 500, IN-08) when no tenant was established — an unmapped tenant on
     * an authenticated request is a server-side filter-chain fault, not a client
     * request-shape error.
     */
    public static UUID require() {
        return TenantContext.get().orElseThrow(() -> new MissingTenantContextException(
                "Tenant is not set. Provide JWT with tenant claim or X-Tenant-Id header in dev."));
    }
}
