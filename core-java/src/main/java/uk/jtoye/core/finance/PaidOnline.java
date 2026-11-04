package uk.jtoye.core.finance;

import uk.jtoye.core.order.PaymentStatus;

/**
 * The "Paid online / Not paid online" rule (#827, owner ruling 2026-11-04), in one place.
 *
 * <p>The ledger carries no payment method, so the label is derived from the order behind a row. An order whose
 * payment went through the platform's card processing is <b>Paid online</b>: {@code CAPTURED}, and
 * {@code REFUNDED} (it was captured before it was refunded, so its REVERSAL must net the online figure rather
 * than leave a positive sale on the cash side). Everything else is <b>Not paid online</b>: a cash order (its
 * payment status stays {@code NONE}), an order whose card payment is still {@code PENDING}, {@code AUTHORIZED}
 * or {@code FAILED}, and a MANUAL row, which has no order.
 *
 * <p>The summary computes the same split DB-side: the two aggregate queries in
 * {@link FinancialTransactionRepository} spell this set out as JPQL ({@code CAPTURED}, {@code REFUNDED}), and
 * {@code FinancePaidOnlineSplitIntegrationTest} holds the two spellings equal on one fixture.
 */
public final class PaidOnline {

    /** The label the dashboard and the CSV print for a row paid online. */
    public static final String PAID_ONLINE_LABEL = "Paid online";
    /** The label for everything else. */
    public static final String NOT_PAID_ONLINE_LABEL = "Not paid online";

    private PaidOnline() {
    }

    /** True when an order with this payment status was paid online. {@code null} (no order) is not. */
    public static boolean of(PaymentStatus orderPaymentStatus) {
        return orderPaymentStatus == PaymentStatus.CAPTURED || orderPaymentStatus == PaymentStatus.REFUNDED;
    }

    public static String label(boolean paidOnline) {
        return paidOnline ? PAID_ONLINE_LABEL : NOT_PAID_ONLINE_LABEL;
    }
}
