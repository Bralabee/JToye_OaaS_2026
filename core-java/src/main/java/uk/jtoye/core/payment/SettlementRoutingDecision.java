package uk.jtoye.core.payment;

/**
 * ADR-0007 / GAP-GTM-01: Resolved settlement routing instructions for a single order's
 * Stripe {@code PaymentIntent}.
 *
 * @param mode                    the resolved {@link SettlementPolicyMode}
 * @param connectedAccountId      the vendor's enabled Stripe account ID ({@code acct_...}), or {@code null}
 *                                when the tenant has no enabled connected account (platform fallback)
 * @param chargeAmountPennies     the amount to charge on the {@code PaymentIntent} now (equals
 *                                {@code depositAmountPennies} under {@link SettlementPolicyMode#SPLIT_DEPOSIT_VENDOR_MOR},
 *                                or the full order total otherwise)
 * @param applicationFeePennies   the platform fee in pennies ({@code 0} when no fee applies)
 * @param depositAmountPennies    non-null only for {@link SettlementPolicyMode#SPLIT_DEPOSIT_VENDOR_MOR}
 * @param balanceDuePennies       non-null only for {@link SettlementPolicyMode#SPLIT_DEPOSIT_VENDOR_MOR}
 */
public record SettlementRoutingDecision(
        SettlementPolicyMode mode,
        String connectedAccountId,
        long chargeAmountPennies,
        long applicationFeePennies,
        Long depositAmountPennies,
        Long balanceDuePennies
) {
    /**
     * True when the PaymentIntent must be created directly on the vendor's connected
     * Stripe account via {@code RequestOptions.setStripeAccount(connectedAccountId)}
     * (Vendor as Merchant of Record).
     */
    public boolean usesDirectChargeHeader() {
        return connectedAccountId != null && !connectedAccountId.isBlank()
                && (mode == SettlementPolicyMode.DIRECT_CHARGE_VENDOR_MOR
                || mode == SettlementPolicyMode.SPLIT_DEPOSIT_VENDOR_MOR);
    }

    /**
     * True when the PaymentIntent is created on the platform account with
     * {@code transfer_data[destination] = connectedAccountId} (Platform as Merchant of Record).
     */
    public boolean usesDestinationTransfer() {
        return connectedAccountId != null && !connectedAccountId.isBlank()
                && mode == SettlementPolicyMode.DESTINATION_CHARGE_PLATFORM_MOR;
    }
}
