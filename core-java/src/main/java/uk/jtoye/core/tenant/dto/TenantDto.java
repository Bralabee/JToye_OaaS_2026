package uk.jtoye.core.tenant.dto;

import uk.jtoye.core.tenant.StripeConnectStatus;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantPlan;
import uk.jtoye.core.tenant.TenantStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Admin view of a tenant registry row (issue #102). Returned only by the
 * role-gated {@code /api/v1/admin/tenants} surface, so exposing the lifecycle
 * timestamps and the Stripe Connect linkage state is intentional. Never
 * exposes secrets (there are none on the row — the Stripe account id is a
 * public-ish identifier, not a credential).
 */
public record TenantDto(
        UUID id,
        String name,
        TenantStatus status,
        TenantPlan plan,
        String contactName,
        String contactEmail,
        String contactPhone,
        OffsetDateTime createdAt,
        OffsetDateTime suspendedAt,
        OffsetDateTime offboardedAt,
        String stripeAccountId,
        StripeConnectStatus stripeConnectStatus,
        OffsetDateTime keycloakDeprovisionedAt) {

    /** Hand-mapped (single small DTO — MapStruct would be ceremony here). */
    public static TenantDto from(Tenant t) {
        return new TenantDto(
                t.getId(), t.getName(), t.getStatus(), t.getPlan(),
                t.getContactName(), t.getContactEmail(), t.getContactPhone(),
                t.getCreatedAt(), t.getSuspendedAt(), t.getOffboardedAt(),
                t.getStripeAccountId(), t.getStripeConnectStatus(),
                t.getKeycloakDeprovisionedAt());
    }
}
