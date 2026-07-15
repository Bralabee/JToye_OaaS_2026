package uk.jtoye.core.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Grouped configuration for the one-click unsubscribe machinery, bound from the
 * {@code notification.unsubscribe.*} keys in {@code application.yml}. No literals
 * live in code — the signing secret and public base URL are injected via
 * {@code ${ENV:default}} so a value can be overridden per environment without a
 * redeploy (GLOBAL_RULE_6).
 *
 * <p>Deliberately declares ONLY the {@code unsubscribe} sub-tree. The existing
 * {@code notification.email.*} keys stay owned by {@code EmailNotificationService}
 * (via {@code @Value}) and are ignored here (relaxed binding, unknown fields
 * tolerated) so this class coexists with — and never disturbs — the working
 * order-email path.
 *
 * <p>Mirrors {@code KeycloakAdminProperties}: the signing secret defaults to
 * empty (never null) and a redacted {@link #toString()} masks it so an accidental
 * {@code log.info("{}", props)} cannot leak the HMAC key.
 */
@Component
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    private final Unsubscribe unsubscribe = new Unsubscribe();

    public Unsubscribe getUnsubscribe() {
        return unsubscribe;
    }

    /**
     * True iff the unsubscribe HMAC signing secret is present. The stateless
     * unsubscribe token cannot be signed or verified without it, so a blank
     * secret means the feature is not yet configured.
     */
    public boolean configured() {
        return unsubscribe.getSigningSecret() != null && !unsubscribe.getSigningSecret().isBlank();
    }

    /** Redacted so an accidental logger call cannot leak the signing secret. */
    @Override
    public String toString() {
        return "NotificationProperties(unsubscribe.baseUrl=" + unsubscribe.getBaseUrl()
                + ", unsubscribe.signingSecret=" + mask(unsubscribe.getSigningSecret()) + ")";
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "<unset>";
        }
        return "***";
    }

    /**
     * The {@code notification.unsubscribe.*} sub-tree: the HMAC signing secret
     * (empty default → inert) and the public base URL that unsubscribe links
     * point at.
     */
    public static class Unsubscribe {

        /** HMAC signing secret for stateless unsubscribe tokens. Empty default (inert until set). */
        private String signingSecret = "";

        /** Public origin the one-click unsubscribe links are built against. */
        private String baseUrl = "http://localhost:3000";

        public String getSigningSecret() {
            return signingSecret;
        }

        public void setSigningSecret(String signingSecret) {
            this.signingSecret = signingSecret;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
