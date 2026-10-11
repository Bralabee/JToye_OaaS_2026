package uk.jtoye.core.tenant.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Grouped configuration for Keycloak user deprovisioning (issue #102 remainder),
 * bound from the {@code jtoye.keycloak.admin.*} keys in {@code application.yml}.
 * No literals live in code paths — endpoints and credentials are injected here so
 * a value can be overridden per environment via {@code ${ENV:default}} without a
 * redeploy.
 *
 * <p>Mirrors {@code OnboardingProperties}: the {@code password} secret defaults to
 * empty (never null) and a redacted {@link #toString()} masks it so an accidental
 * {@code log.info("{}", props)} cannot leak the master-realm admin credential
 * (STRIDE T-kc-01).
 *
 * <p><b>Inert by default:</b> {@link #configured()} is false unless
 * {@code enabled=true} AND {@code base-url} and {@code password} are non-blank, so
 * the feature ships off and turns on only when an operator wires the env.
 */
@Component
@ConfigurationProperties(prefix = "jtoye.keycloak.admin")
public class KeycloakAdminProperties {

    /** Master switch. False (default) keeps the whole feature inert. */
    private boolean enabled = false;

    /**
     * In-cluster Keycloak base URL reachable from core (e.g.
     * {@code http://keycloak:8080}) — NOT the public {@code localhost:8085} host.
     * Empty default (feature inert until set).
     */
    private String baseUrl = "";

    /**
     * Realms to sweep. Default is the vendor realm only: {@code jtoye-customers}
     * has no {@code tenant_id} user attributes so it is deliberately excluded
     * (STRIDE T-kc-04 — wrong-scope disable). Mutable {@link ArrayList} so Spring
     * relaxed-binding of a comma-separated {@code KC_DEPROVISION_REALMS} value can
     * rebind it.
     */
    private List<String> realms = new ArrayList<>(List.of("jtoye-dev"));

    /**
     * 31.1-11 (D-03): the realm that holds CUSTOMER sign-in accounts (ADR-0005), and the ONLY realm
     * DSAR account deletion ever touches. Deliberately a separate key from {@link #realms}: that list
     * is the vendor sweep for tenant offboarding, and T-kc-04 keeps it vendor-only. A customer
     * deletion that walked {@code realms} could delete a vendor user who shares the address.
     */
    private String customerRealm = "jtoye-customers";

    /**
     * 37-08 (D-07, D-26): the VENDOR realm a staff-invite accept creates the invitee in, and the only
     * realm it looks an invited address up in. A separate key from {@link #realms} (the offboarding
     * sweep, which may list more than one realm) because an account is created in exactly one realm.
     * Default {@code jtoye-dev}, the vendor realm of every runtime in this repository.
     */
    private String vendorRealm = "jtoye-dev";

    /** master-realm admin username (admin-cli password grant). */
    private String username = "admin";

    /** Secret defaults empty (never null) — house convention (OnboardingProperties). */
    private String password = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public List<String> getRealms() { return realms; }
    public void setRealms(List<String> realms) { this.realms = realms; }

    public String getCustomerRealm() { return customerRealm; }
    public void setCustomerRealm(String customerRealm) { this.customerRealm = customerRealm; }

    public String getVendorRealm() { return vendorRealm; }
    public void setVendorRealm(String vendorRealm) { this.vendorRealm = vendorRealm; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    /**
     * True iff the feature is switched on AND both the base URL and admin
     * password are present. The client cannot reach Keycloak without a base URL,
     * and the password grant cannot authenticate without a password — so both
     * gate the "configured" state used by the service (no-op) and the admin
     * re-trigger endpoint (RFC 7807 400 "not configured").
     */
    public boolean configured() {
        return enabled
                && baseUrl != null && !baseUrl.isBlank()
                && password != null && !password.isBlank();
    }

    /** Redacted so an accidental logger call cannot leak the admin password. */
    @Override
    public String toString() {
        return "KeycloakAdminProperties(enabled=" + enabled
                + ", baseUrl=" + baseUrl
                + ", realms=" + realms
                + ", customerRealm=" + customerRealm
                + ", vendorRealm=" + vendorRealm
                + ", username=" + username
                + ", password=" + mask(password) + ")";
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "<unset>";
        }
        return "***";
    }
}
