package uk.jtoye.core.tenant.keycloak;

/**
 * The fields of a Keycloak customer-realm user representation that DSAR account deletion reads
 * (31.1-11, D-03). Parsed from {@code GET /admin/realms/{realm}/users?email=...&exact=true}.
 *
 * <p>Any field Keycloak omits is {@code null}, never an empty string, so "no email on the account"
 * cannot be mistaken for an email that compares equal to something.
 *
 * @param id               Keycloak user id, the path segment of the DELETE
 * @param username         the realm username
 * @param email            the stored email, compared (ignoring case) against the verified subject
 *                         address before anything is deleted
 * @param firstName        given name, if any
 * @param lastName         family name, if any
 * @param createdTimestamp epoch milliseconds Keycloak recorded at creation, if any
 */
public record CustomerRealmUser(String id,
                                String username,
                                String email,
                                String firstName,
                                String lastName,
                                Long createdTimestamp) {
}
