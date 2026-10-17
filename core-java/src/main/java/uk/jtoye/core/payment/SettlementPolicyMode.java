package uk.jtoye.core.payment;

/**
 * ADR-0007 / GAP-GTM-01: Governs how Stripe card payments are routed and which
 * legal entity acts as Merchant of Record (MoR).
 */
public enum SettlementPolicyMode {
    /**
     * Direct charge on the vendor's connected Stripe account ({@code Stripe-Account}
     * header on {@code RequestOptions}) with {@code application_fee_amount}. The Vendor
     * is the legal Merchant of Record; J'Toye is a pure technology processor.
     * Default for {@code OnboardingModel.WHITE_LABEL} (Cohort A &amp; B).
     */
    DIRECT_CHARGE_VENDOR_MOR,

    /**
     * Platform-level charge with {@code transfer_data[destination]} and
     * {@code application_fee_amount}. J'Toye Platform acts as Merchant of Record.
     * Default for {@code OnboardingModel.MARKETPLACE} (Cohort D).
     */
    DESTINATION_CHARGE_PLATFORM_MOR,

    /**
     * Split-deposit direct charge on the vendor's connected Stripe account for
     * catering/event orders (Cohort C): charges {@code depositBps} of the order total
     * upfront on the vendor's Stripe account and records {@code depositAmountPennies}
     * and {@code balanceDuePennies} on the order.
     */
    SPLIT_DEPOSIT_VENDOR_MOR
}
