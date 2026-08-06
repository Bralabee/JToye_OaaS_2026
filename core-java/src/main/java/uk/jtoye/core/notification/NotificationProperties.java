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
                + ", unsubscribe.pagePath=" + unsubscribe.getPagePath()
                + ", unsubscribe.oneClickBaseUrl=" + unsubscribe.getOneClickBaseUrl()
                + ", unsubscribe.oneClickPath=" + unsubscribe.getOneClickPath()
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
     * (empty default → inert) plus the TWO origins an unsubscribe link needs.
     *
     * <h2>Why two origins, and not one (issue #516)</h2>
     *
     * <p>The app and the API are different Services behind the ingress
     * ({@code app.*} → {@code frontend}, {@code api.*} → {@code core-java}), so
     * an origin only routes the paths its own Service serves. Composing the
     * app origin with the API's path — which is what this class used to invite,
     * carrying one {@code baseUrl} for both purposes — produced a link that
     * 404'd in staging, production and local.
     *
     * <p>The two consumers genuinely need different targets:
     * <ul>
     *   <li>the <b>clickable footer link</b> is a browser GET and must land on
     *       the branded confirmation page the frontend serves
     *       ({@code frontend/app/unsubscribe/page.tsx}) — {@link #baseUrl} +
     *       {@link #pagePath};</li>
     *   <li>the <b>RFC 8058 {@code List-Unsubscribe} target</b> is POSTed by the
     *       mail provider and must reach {@code PublicUnsubscribeController} —
     *       {@link #oneClickBaseUrl} + {@link #oneClickPath}. A Next.js page
     *       answers 405 to a POST, so this one cannot ride the app origin.</li>
     * </ul>
     *
     * <p>Both paths are configuration, not literals (GLOBAL_RULE_6): a routing
     * change must be answerable with a ConfigMap value, which is exactly what
     * #516 was not.
     */
    public static class Unsubscribe {

        /** HMAC signing secret for stateless unsubscribe tokens. Empty default (inert until set). */
        private String signingSecret = "";

        /**
         * The APP (frontend) origin — the host that serves {@link #pagePath}.
         * Sourced from {@code frontend.url} in every k8s overlay.
         */
        private String baseUrl = "http://localhost:3000";

        /** Path of the frontend's unsubscribe confirmation page, appended to {@link #baseUrl}. */
        private String pagePath = "/unsubscribe";

        /**
         * The API origin serving {@link #oneClickPath}. EMPTY BY DEFAULT and
         * fail-safe: with no value the {@code List-Unsubscribe} header advertises
         * the page URL and {@code List-Unsubscribe-Post} is NOT stamped, so a
         * one-click POST is never promised at a target that cannot honour it.
         * An environment wires it to its own API origin ({@code api.url}) to turn
         * true RFC 8058 one-click back on.
         *
         * <p>Empty, and not a localhost convenience default: a local-only default
         * that no manifest supplies is silently wrong everywhere else, which is
         * both the D-19 defect class and a direction-(b) failure of
         * {@code k8s/scripts/check-env-contract.sh}.
         */
        private String oneClickBaseUrl = "";

        /** Path of the no-auth API endpoint, appended to {@link #oneClickBaseUrl}. */
        private String oneClickPath = "/api/v1/public/unsubscribe";

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

        public String getPagePath() {
            return pagePath;
        }

        public void setPagePath(String pagePath) {
            this.pagePath = pagePath;
        }

        public String getOneClickBaseUrl() {
            return oneClickBaseUrl;
        }

        public void setOneClickBaseUrl(String oneClickBaseUrl) {
            this.oneClickBaseUrl = oneClickBaseUrl;
        }

        public String getOneClickPath() {
            return oneClickPath;
        }

        public void setOneClickPath(String oneClickPath) {
            this.oneClickPath = oneClickPath;
        }

        /** True iff a POST-capable one-click target is configured (RFC 8058 advertisable). */
        public boolean oneClickConfigured() {
            return oneClickBaseUrl != null && !oneClickBaseUrl.isBlank();
        }
    }
}
