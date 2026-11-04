package uk.jtoye.core.finance;

import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.PaymentStatus;

/**
 * The "Paid online / Not paid online" rule (#827, owner ruling 2026-11-04), in one place, applied ONCE: when the
 * ledger row is written.
 *
 * <p>The first cut derived the label at read time from the order's current payment status, in four places. That
 * was wrong two ways (PR #1124 review round 1): a deposit-split order (deposit by card, balance in cash) was
 * labelled wholly online, and an order completed at the counter then paid by a payment link flipped to "Paid
 * online" in a closed period. The channel is a fact about the moment the row was written, so it is now stored on
 * the row ({@code financial_transactions.paid_online_pennies}, V93) and every read (summary, list, CSV) reads
 * the stored figure. This class owns the one write-time rule and the labels.
 *
 * <p><b>Why pennies.</b> A SPLIT_DEPOSIT sale is part online and part cash and the ledger keeps one SALE per
 * order ({@code uq_fin_tx_tenant_order}); two rows by channel would break that index and double the count. The
 * row keeps its single gross amount and VAT; the figure says how much of it came in by card.
 */
public final class PaidOnline {

    /** The label for a row taken wholly online. */
    public static final String PAID_ONLINE_LABEL = "Paid online";
    /** The label for a row with nothing taken online: cash, a card payment not yet captured, a MANUAL row. */
    public static final String NOT_PAID_ONLINE_LABEL = "Not paid online";
    /** The label for a SPLIT_DEPOSIT sale: the deposit by card, the balance not. */
    public static final String PART_PAID_ONLINE_LABEL = "Part paid online";
    /** The label for a legacy row whose channel could not be proven (V93 left it NULL). */
    public static final String NOT_RECORDED_LABEL = "Not recorded";

    private PaidOnline() {
    }

    /**
     * How many of an order's pennies have been CAPTURED online at this moment, for the ledger row about to be
     * written (the SALE). Called by every SALE writer: card settlement, the balance-stage settlement and the
     * COMPLETED cash path; a replayed writer finds the SALE already there and never reaches the ledger insert.
     *
     * <ul>
     *   <li>Not {@code CAPTURED}: 0. An order completed at the counter has {@code NONE} here, and a payment
     *       link captured afterwards does not reach back into the row.</li>
     *   <li>{@code CAPTURED} with a deposit split and a balance still due: the deposit only (capped at the
     *       total). The balance is collected outside the platform.</li>
     *   <li>{@code CAPTURED} otherwise: the order total (a full card payment, or a deposit order whose balance
     *       intent has also been captured, which zeroes the balance due).</li>
     * </ul>
     */
    public static long settledOnlinePennies(Order order) {
        long total = order.getTotalAmountPennies() == null ? 0L : order.getTotalAmountPennies();
        if (order.getPaymentStatus() != PaymentStatus.CAPTURED) {
            return 0L;
        }
        Long deposit = order.getDepositAmountPennies();
        Long balanceDue = order.getBalanceDuePennies();
        if (deposit != null && balanceDue != null && balanceDue > 0) {
            return Math.max(0L, Math.min(deposit, total));
        }
        return total;
    }

    /**
     * The label for a row: {@code paidOnlinePennies} against the row's own {@code amountPennies} (same sign, so
     * the comparison holds for a REVERSAL too). {@code null} is "Not recorded", never "Not paid online".
     */
    public static String label(Long paidOnlinePennies, long amountPennies) {
        if (paidOnlinePennies == null) {
            return NOT_RECORDED_LABEL;
        }
        if (paidOnlinePennies == 0L) {
            return NOT_PAID_ONLINE_LABEL;
        }
        return paidOnlinePennies == amountPennies ? PAID_ONLINE_LABEL : PART_PAID_ONLINE_LABEL;
    }
}
