package uk.jtoye.core.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import uk.jtoye.core.tenant.TenantPlan;

/**
 * Admin request to create a tenant (issue #102 AC1 — "created without an
 * engineer running SQL"). Plan defaults to STANDARD when omitted; contact
 * fields are optional but the email, when present, must be well-formed (it
 * seeds the Stripe Express account email later).
 *
 * @param name         unique tenant name
 * @param plan         commercial plan/tier (null → STANDARD)
 * @param contactName  primary contact person (optional)
 * @param contactEmail primary contact email (optional)
 * @param contactPhone primary contact phone (optional)
 */
public record CreateTenantRequest(
        @NotBlank @Size(max = 255) String name,
        TenantPlan plan,
        @Size(max = 255) String contactName,
        @Email @Size(max = 320) String contactEmail,
        @Size(max = 32) String contactPhone) {
}
