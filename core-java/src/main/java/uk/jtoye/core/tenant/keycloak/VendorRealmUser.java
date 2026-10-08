package uk.jtoye.core.tenant.keycloak;

/**
 * The fields of a vendor-realm user representation the staff-invite accept flow reads (37-08, D-07):
 * who the person is in Keycloak, and which business their account belongs to.
 *
 * <p>One user, one tenant: {@code tenant_id} is a single-valued, admin-only user-profile attribute in
 * the vendor realm (RESEARCH 37-B.3, Pitfall 3). {@link #tenantId()} is that attribute's first value,
 * or {@code null} when the account carries none; a missing value is never read as "this business".
 *
 * @param id       Keycloak user id (the token's {@code sub})
 * @param email    the stored email, re-checked (ignoring case) by the caller before acting on it
 * @param tenantId the {@code tenant_id} attribute's value as stored, or {@code null}
 */
public record VendorRealmUser(String id, String email, String tenantId) {
}
