package uk.jtoye.core.payment;

import com.stripe.param.RefundCreateParams;

/**
 * Refund reason — mirrors {@link com.stripe.param.RefundCreateParams.Reason}
 * but kept as a separate Java enum so the API surface is independent of the
 * Stripe SDK enum (lets us version, document, and validate independently).
 *
 * <p>Use {@link #toStripeReason(RefundReason)} to obtain the Stripe SDK
 * value when calling {@code Refund.create}.
 */
public enum RefundReason {
    /** Charged the customer twice for the same order (vendor admin tool). */
    DUPLICATE,

    /** Charge was fraudulent — Stripe forwards this to the issuer. */
    FRAUDULENT,

    /** Customer requested the refund (most common path). */
    REQUESTED_BY_CUSTOMER;

    /**
     * Map our enum to the Stripe SDK enum used in {@link com.stripe.param.RefundCreateParams}.
     */
    public static RefundCreateParams.Reason toStripeReason(RefundReason reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case DUPLICATE -> RefundCreateParams.Reason.DUPLICATE;
            case FRAUDULENT -> RefundCreateParams.Reason.FRAUDULENT;
            case REQUESTED_BY_CUSTOMER -> RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER;
        };
    }
}
