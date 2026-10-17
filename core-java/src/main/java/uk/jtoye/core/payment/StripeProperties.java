package uk.jtoye.core.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "stripe")
public class StripeProperties {

    private String apiKey = "";
    private String webhookSecret = "";

    /**
     * Presentment/settlement currency for PaymentIntents (issue #102 — the
     * former hardcoded {@code "gbp"} literal, now env-injected via
     * {@code STRIPE_CURRENCY}). Lowercase ISO 4217, per the Stripe API.
     */
    private String currency = "gbp";

    /**
     * Platform application fee in basis points (1 bps = 0.01%), applied to
     * MARKETPLACE destination charges only (ADR-0001 Decision 2). Fee in
     * pennies = amount * bps / 10_000, floored. {@code 0} (the default) means
     * no application fee is set — the fee is an explicit business decision
     * injected via {@code STRIPE_PLATFORM_FEE_BPS}, never a code literal.
     */
    private int platformFeeBps = 0;

    private final Connect connect = new Connect();
    private final Billing billing = new Billing();

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public int getPlatformFeeBps() { return platformFeeBps; }
    public void setPlatformFeeBps(int platformFeeBps) { this.platformFeeBps = platformFeeBps; }

    public Connect getConnect() { return connect; }

    public Billing getBilling() { return billing; }

    /** Stripe Connect (connected-account) settings — issue #102, ADR-0001 Decision 2. */
    public static class Connect {
        /** Country for new Express accounts (UK platform). */
        private String country = "GB";
        /** Where Stripe sends the vendor after completing Express onboarding. */
        private String returnUrl = "";
        /** Where Stripe sends the vendor when an onboarding link expires. */
        private String refreshUrl = "";

        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }

        public String getReturnUrl() { return returnUrl; }
        public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }

        public String getRefreshUrl() { return refreshUrl; }
        public void setRefreshUrl(String refreshUrl) { this.refreshUrl = refreshUrl; }
    }

    /**
     * Plan-configurable recurring billing & application-fee basis points
     * (ADR-0007 §1.4 / GAP-GTM-05, PAY-02, and BUSINESS-ALIGNMENT-2026-10.md D-B9).
     */
    public static class Billing {
        /** SAAS_HYBRID_STANDARD: £39/location/mo (3900 pennies). */
        private long hybridMonthlyPennies = 3900L;
        /** SAAS_HYBRID_STANDARD: 50 bps (0.5%) application fee. */
        private int hybridFeeBps = 50;

        /** SAAS_FLAT_PREDICTABLE: £79/location/mo default (7900 pennies; up to 14900 pennies). */
        private long flatMonthlyPennies = 7900L;
        private long flatMaxMonthlyPennies = 14900L;
        /** SAAS_FLAT_PREDICTABLE: 0 bps (0%) application fee. */
        private int flatFeeBps = 0;

        /** BATCH_PAYG_STARTER: £0/mo (0 pennies). */
        private long paygMonthlyPennies = 0L;
        /** BATCH_PAYG_STARTER: 600 bps (6.0%) application fee. */
        private int paygFeeBps = 600;

        /** WALK_SETUP_STEPDOWN (D-B9): £195/mo during setup (19500 pennies). */
        private long walkSetupMonthlyPennies = 19500L;
        /** WALK_SETUP_STEPDOWN (D-B9): 250 bps (2.5%) ongoing fee after setup completes. */
        private int walkOngoingFeeBps = 250;
        /** WALK_SETUP_STEPDOWN (D-B9): £39/mo per-account fee cap (3900 pennies). */
        private long walkMonthlyCapPennies = 3900L;

        public long getHybridMonthlyPennies() { return hybridMonthlyPennies; }
        public void setHybridMonthlyPennies(long hybridMonthlyPennies) { this.hybridMonthlyPennies = hybridMonthlyPennies; }

        public int getHybridFeeBps() { return hybridFeeBps; }
        public void setHybridFeeBps(int hybridFeeBps) { this.hybridFeeBps = hybridFeeBps; }

        public long getFlatMonthlyPennies() { return flatMonthlyPennies; }
        public void setFlatMonthlyPennies(long flatMonthlyPennies) { this.flatMonthlyPennies = flatMonthlyPennies; }

        public long getFlatMaxMonthlyPennies() { return flatMaxMonthlyPennies; }
        public void setFlatMaxMonthlyPennies(long flatMaxMonthlyPennies) { this.flatMaxMonthlyPennies = flatMaxMonthlyPennies; }

        public int getFlatFeeBps() { return flatFeeBps; }
        public void setFlatFeeBps(int flatFeeBps) { this.flatFeeBps = flatFeeBps; }

        public long getPaygMonthlyPennies() { return paygMonthlyPennies; }
        public void setPaygMonthlyPennies(long paygMonthlyPennies) { this.paygMonthlyPennies = paygMonthlyPennies; }

        public int getPaygFeeBps() { return paygFeeBps; }
        public void setPaygFeeBps(int paygFeeBps) { this.paygFeeBps = paygFeeBps; }

        public long getWalkSetupMonthlyPennies() { return walkSetupMonthlyPennies; }
        public void setWalkSetupMonthlyPennies(long walkSetupMonthlyPennies) { this.walkSetupMonthlyPennies = walkSetupMonthlyPennies; }

        public int getWalkOngoingFeeBps() { return walkOngoingFeeBps; }
        public void setWalkOngoingFeeBps(int walkOngoingFeeBps) { this.walkOngoingFeeBps = walkOngoingFeeBps; }

        public long getWalkMonthlyCapPennies() { return walkMonthlyCapPennies; }
        public void setWalkMonthlyCapPennies(long walkMonthlyCapPennies) { this.walkMonthlyCapPennies = walkMonthlyCapPennies; }

        public long monthlyAmountPenniesFor(uk.jtoye.core.tenant.BillingPlanMode mode, boolean walkSetupCompleted) {
            if (mode == null) {
                return hybridMonthlyPennies;
            }
            return switch (mode) {
                case SAAS_HYBRID_STANDARD -> hybridMonthlyPennies;
                case SAAS_FLAT_PREDICTABLE -> flatMonthlyPennies;
                case BATCH_PAYG_STARTER -> paygMonthlyPennies;
                case WALK_SETUP_STEPDOWN -> walkSetupCompleted ? 0L : walkSetupMonthlyPennies;
            };
        }

        public int feeBpsFor(uk.jtoye.core.tenant.BillingPlanMode mode, boolean walkSetupCompleted) {
            if (mode == null) {
                return hybridFeeBps;
            }
            return switch (mode) {
                case SAAS_HYBRID_STANDARD -> hybridFeeBps;
                case SAAS_FLAT_PREDICTABLE -> flatFeeBps;
                case BATCH_PAYG_STARTER -> paygFeeBps;
                case WALK_SETUP_STEPDOWN -> walkSetupCompleted ? walkOngoingFeeBps : 0;
            };
        }
    }

    /**
     * Redacted toString so accidental logger calls (log.info("config={}", stripeProps))
     * cannot leak the live Stripe API key or webhook secret. Both fields are
     * masked while still showing whether they are set — useful for diagnostics.
     */
    @Override
    public String toString() {
        return "StripeProperties(apiKey=" + mask(apiKey)
                + ", webhookSecret=" + mask(webhookSecret)
                + ", currency=" + currency
                + ", platformFeeBps=" + platformFeeBps
                + ", connect.country=" + connect.country
                + ", connect.returnUrl=" + connect.returnUrl
                + ", connect.refreshUrl=" + connect.refreshUrl + ")";
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "<unset>";
        }
        return "***";
    }
}
