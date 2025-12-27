package uk.jtoye.core.common;

import uk.jtoye.core.security.TenantContext;

import java.util.UUID;

public final class CurrentTenant {
    private CurrentTenant() {}

    public static UUID require() {
        return TenantContext.get().orElseThrow(() -> new IllegalStateException("Tenant is not set. Provide JWT with tenant claim or X-Tenant-Id header in dev."));
    }
}
