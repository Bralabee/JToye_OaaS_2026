package uk.jtoye.core.finance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.PaymentStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V93 (#827, PR #1124 review round 1): the one write-time rule for how much of an order has come in online, and
 * the labels. The rule is applied once, when the ledger row is written; nothing re-derives it at read time.
 */
class PaidOnlineTest {

    private static Order order(PaymentStatus status, long total, Long deposit, Long balanceDue) {
        Order o = new Order();
        o.setTotalAmountPennies(total);
        o.setPaymentStatus(status);
        o.setDepositAmountPennies(deposit);
        o.setBalanceDuePennies(balanceDue);
        return o;
    }

    @Test
    @DisplayName("a captured card order is wholly online")
    void capturedFullPaymentIsWhollyOnline() {
        assertThat(PaidOnline.settledOnlinePennies(order(PaymentStatus.CAPTURED, 2000, null, null))).isEqualTo(2000);
    }

    @Test
    @DisplayName("deposit by card, balance still to collect: ONLY the deposit is online (10000 total, 2000 deposit -> 2000)")
    void depositOnlyWhileBalanceIsDue() {
        assertThat(PaidOnline.settledOnlinePennies(order(PaymentStatus.CAPTURED, 10_000, 2_000L, 8_000L))).isEqualTo(2_000);
    }

    @Test
    @DisplayName("deposit and balance both captured (balance due cleared): the whole total is online")
    void depositAndBalanceBothOnline() {
        assertThat(PaidOnline.settledOnlinePennies(order(PaymentStatus.CAPTURED, 10_000, 2_000L, 0L))).isEqualTo(10_000);
    }

    @Test
    @DisplayName("a deposit larger than the total cannot make more than the total online")
    void depositIsCappedAtTheTotal() {
        assertThat(PaidOnline.settledOnlinePennies(order(PaymentStatus.CAPTURED, 1_000, 5_000L, 1L))).isEqualTo(1_000);
    }

    @Test
    @DisplayName("not captured (a counter completion, a pending or failed card payment, a refund): nothing is online")
    void notCapturedIsNotOnline() {
        for (PaymentStatus status : new PaymentStatus[]{PaymentStatus.NONE, PaymentStatus.PENDING,
                PaymentStatus.AUTHORIZED, PaymentStatus.FAILED, PaymentStatus.REFUNDED}) {
            assertThat(PaidOnline.settledOnlinePennies(order(status, 1_200, null, null))).as(status.name()).isZero();
        }
        assertThat(PaidOnline.settledOnlinePennies(order(null, 1_200, null, null))).isZero();
    }

    @Test
    @DisplayName("labels compare the stored figure with the row's own amount, either sign; null is 'Not recorded', never 'Not paid online'")
    void labels() {
        assertThat(PaidOnline.label(2_000L, 2_000)).isEqualTo("Paid online");
        assertThat(PaidOnline.label(0L, 2_000)).isEqualTo("Not paid online");
        assertThat(PaidOnline.label(2_000L, 10_000)).isEqualTo("Part paid online");
        assertThat(PaidOnline.label(-2_000L, -10_000)).isEqualTo("Part paid online");
        assertThat(PaidOnline.label(-10_000L, -10_000)).isEqualTo("Paid online");
        assertThat(PaidOnline.label(null, 2_000)).isEqualTo("Not recorded");
    }
}
