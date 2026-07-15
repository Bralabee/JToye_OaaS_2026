package uk.jtoye.core.notification.template;

/**
 * The audience an email is rendered for. Transactional lifecycle events fan out
 * to both the customer (the order's email, D-04) and the vendor
 * ({@code tenants.contact_email}, D-04); the copy differs per audience, so
 * {@link EmailTemplateRenderer} selects the variant with this role.
 *
 * <p>Distinct from the CONSENT {@code NotificationCategory} owned by plan 22-02 —
 * this is the "who is reading" axis, not the "what may I send" axis.
 */
public enum RecipientRole {
    CUSTOMER,
    VENDOR
}
