package uk.jtoye.core.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Grouped configuration for the WhatsApp/SMS channel (COMMS-07), bound from the
 * {@code jtoye.whatsapp.*} keys in {@code application.yml}. All provider
 * credentials are injected via {@code ${ENV:default}} with empty-string defaults
 * (GLOBAL_RULE_6) — never hardcoded.
 *
 * <p><b>Inert by default:</b> {@link #configured()} is false unless
 * {@code enabled=true} AND the account SID, auth token and from-number are all
 * present, so the channel ships off and a {@code WhatsAppSmsChannel.deliver(...)}
 * is a logged no-op until an operator wires real provider credentials. Enabling
 * the flag WITHOUT credentials still reports {@code configured()==false}, so the
 * channel stays a WARN no-op rather than crashing.
 *
 * <p>Mirrors {@code KeycloakAdminProperties}: secrets default empty (never null)
 * and a redacted {@link #toString()} masks the SID + auth token so an accidental
 * {@code log.info("{}", props)} cannot leak provider credentials
 * (STRIDE T-22-01-01).
 */
@Component
@ConfigurationProperties(prefix = "jtoye.whatsapp")
public class WhatsAppProperties {

    /** Master switch. False (default) keeps the whole channel inert. */
    private boolean enabled = false;

    /** Provider identifier (e.g. {@code twilio}). Empty default. */
    private String provider = "";

    /** Provider account SID. Empty default (channel inert until set). */
    private String accountSid = "";

    /** Provider auth token — secret, empty default, masked in {@link #toString()}. */
    private String authToken = "";

    /** Sender number the provider dispatches from. Empty default. */
    private String fromNumber = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getAccountSid() {
        return accountSid;
    }

    public void setAccountSid(String accountSid) {
        this.accountSid = accountSid;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getFromNumber() {
        return fromNumber;
    }

    public void setFromNumber(String fromNumber) {
        this.fromNumber = fromNumber;
    }

    /**
     * True iff the channel is switched on AND the account SID, auth token and
     * from-number are all present. The provider cannot authenticate or address a
     * message without all three, so every one gates the "configured" state used
     * by {@code WhatsAppSmsChannel} to decide send vs WARN no-op.
     */
    public boolean configured() {
        return enabled
                && accountSid != null && !accountSid.isBlank()
                && authToken != null && !authToken.isBlank()
                && fromNumber != null && !fromNumber.isBlank();
    }

    /** Redacted so an accidental logger call cannot leak the provider credentials. */
    @Override
    public String toString() {
        return "WhatsAppProperties(enabled=" + enabled
                + ", provider=" + provider
                + ", accountSid=" + mask(accountSid)
                + ", authToken=" + mask(authToken)
                + ", fromNumber=" + fromNumber + ")";
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "<unset>";
        }
        return "***";
    }
}
