package uk.jtoye.core.finance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.finance.VatCalculator.LineRate;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderItem;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exact-penny unit tests for the single-source-of-truth VAT helper
 * ({@link VatCalculator}) and the reconciled VAT-inclusive order math
 * ({@link Order#calculateTotal()}). Pure JUnit — no Spring, no DB — so it runs
 * under {@code :core-java:test}.
 *
 * <p>Proves the three Issue #81 defects are closed at the arithmetic level:
 * the HMRC VAT fraction method with round-down, predominant-liability
 * resolution, and no VAT-on-top in the order total.
 */
class VatCalculatorTest {

    // ---- vatFromGross: STANDARD (20%) via fraction method gross*20/120 ----

    @Test
    @DisplayName("vatFromGross STANDARD — exact and round-down boundary")
    void vatFromGrossStandard() {
        assertEquals(200L, VatCalculator.vatFromGross(1200L, VatRate.STANDARD)); // 1200*20/120
        assertEquals(100L, VatCalculator.vatFromGross(600L, VatRate.STANDARD));  // 600*20/120
        // Round-down boundary: 100*20/120 = 16.67 -> 16 (HMRC VAT Notice 700 §17.5.1
        // permits rounding VAT DOWN; integer division truncates toward zero).
        assertEquals(16L, VatCalculator.vatFromGross(100L, VatRate.STANDARD));
    }

    @Test
    @DisplayName("vatFromGross REDUCED — exact and round-down boundary")
    void vatFromGrossReduced() {
        assertEquals(50L, VatCalculator.vatFromGross(1050L, VatRate.REDUCED)); // 1050*5/105
        assertEquals(4L, VatCalculator.vatFromGross(100L, VatRate.REDUCED));   // 100*5/105 = 4.76 -> 4
    }

    @Test
    @DisplayName("vatFromGross ZERO / EXEMPT — always zero")
    void vatFromGrossZeroExempt() {
        assertEquals(0L, VatCalculator.vatFromGross(123456L, VatRate.ZERO));
        assertEquals(0L, VatCalculator.vatFromGross(123456L, VatRate.EXEMPT));
    }

    @Test
    @DisplayName("vatFromGross negative amounts truncate toward zero (refunds/expenses)")
    void vatFromGrossNegative() {
        assertEquals(-200L, VatCalculator.vatFromGross(-1200L, VatRate.STANDARD));
        // -100*20/120 = -16.67 -> -16 (truncate toward zero, matches Postgres)
        assertEquals(-16L, VatCalculator.vatFromGross(-100L, VatRate.STANDARD));
    }

    // ---- predominantRate ----

    @Test
    @DisplayName("predominantRate — greater net goods value wins (STANDARD over ZERO)")
    void predominantStandardBeatsZero() {
        // STANDARD net 800 (two lines) vs ZERO net 200 -> STANDARD
        List<LineRate> lines = List.of(
                new LineRate(480L, VatRate.STANDARD), // net 480-80 = 400
                new LineRate(480L, VatRate.STANDARD), // net 400
                new LineRate(200L, VatRate.ZERO));    // net 200
        assertEquals(VatRate.STANDARD, VatCalculator.predominantRate(lines));
    }

    @Test
    @DisplayName("predominantRate — STANDARD wins a net-value tie against REDUCED")
    void predominantTieBreakStandard() {
        // STANDARD gross 600 -> net 500; REDUCED gross 525 -> net 500 (tie) -> STANDARD
        List<LineRate> lines = List.of(
                new LineRate(600L, VatRate.STANDARD),
                new LineRate(525L, VatRate.REDUCED));
        assertEquals(VatRate.STANDARD, VatCalculator.predominantRate(lines));
    }

    @Test
    @DisplayName("predominantRate — all-ZERO basket stays ZERO (no silent upgrade)")
    void predominantAllZeroStaysZero() {
        List<LineRate> lines = List.of(
                new LineRate(500L, VatRate.ZERO),
                new LineRate(300L, VatRate.ZERO));
        assertEquals(VatRate.ZERO, VatCalculator.predominantRate(lines));
    }

    @Test
    @DisplayName("predominantRate — empty basket and null rate default to STANDARD")
    void predominantEmptyAndNullDefaultStandard() {
        assertEquals(VatRate.STANDARD, VatCalculator.predominantRate(List.of()));
        assertEquals(VatRate.STANDARD, VatCalculator.predominantRate(null));
        // Unknown/null line rate is treated as STANDARD (no silent zero-rating).
        List<LineRate> nullRate = java.util.Collections.singletonList(new LineRate(1000L, null));
        assertEquals(VatRate.STANDARD, VatCalculator.predominantRate(nullRate));
    }

    // ---- Order.calculateTotal is VAT-inclusive (BUG 1) ----

    @Test
    @DisplayName("Order.calculateTotal — total = subtotal + delivery, VAT is the included fraction")
    void orderTotalIsVatInclusive() {
        Order order = new Order();
        order.addItem(new OrderItem(UUID.randomUUID(), 1, 1200L)); // subtotal 1200
        order.setDeliveryFeePennies(300L);
        order.setVatRate(VatRate.STANDARD);

        order.calculateTotal();

        // No VAT added on top: total is exactly subtotal + delivery.
        assertEquals(1200L, order.getSubtotalPennies());
        assertEquals(1500L, order.getTotalAmountPennies());
        // VAT is the fraction contained within the combined gross total:
        // vatFromGross(1500,STD)=250 (this fixture agrees under the old
        // per-component rule too — the divergent case is covered below).
        assertEquals(250L, order.getVatAmountPennies());
    }

    @Test
    @DisplayName("Order.calculateTotal — mixed delivery follows predominant rate; ZERO basket = zero VAT")
    void orderTotalPredominantAndZero() {
        Order zeroOrder = new Order();
        zeroOrder.addItem(new OrderItem(UUID.randomUUID(), 2, 500L)); // subtotal 1000
        zeroOrder.setDeliveryFeePennies(200L);
        zeroOrder.setVatRate(VatRate.ZERO);
        zeroOrder.calculateTotal();

        assertEquals(1200L, zeroOrder.getTotalAmountPennies()); // 1000 + 200
        assertEquals(0L, zeroOrder.getVatAmountPennies());      // zero-rated: no VAT extracted
    }

    // ---- QA-council M1 (run disc-20260712-010550, FIX-3): ONE VAT rule ----
    //
    // Truncation is subadditive: vatFromGross(a) + vatFromGross(b) can be 1p
    // BELOW vatFromGross(a+b). The order entity truncated per component while
    // the ledger (FinancialTransaction.calculateVatAmount over the order's
    // single gross total) and the checkout preview truncated the combined
    // total — three surfaces, two rules, 1p apart. Canonical rule adjudicated
    // as COMBINED-TOTAL (HMRC VAT Notice 700 §17.5-§17.6 permits invoice-total
    // round-down; the V40-hardened ledger stores one gross per order and
    // cannot compute per-component without a schema change).

    @Test
    @DisplayName("Order.calculateTotal — VAT truncates the COMBINED total, not per component (M1 Brixton basket)")
    void orderVatUsesCombinedTotalRule() {
        // The exact live repro: Beef Suya Wrap £8.50 + Peri Peri Chicken £9.00
        // + £3.99 delivery. Per-component: 291 + 66 = 357p. Combined:
        // vatFromGross(2149) = 358p — what the ledger and checkout show.
        Order order = new Order();
        order.addItem(new OrderItem(UUID.randomUUID(), 1, 850L));
        order.addItem(new OrderItem(UUID.randomUUID(), 1, 900L)); // subtotal 1750
        order.setDeliveryFeePennies(399L);
        order.setVatRate(VatRate.STANDARD);

        order.calculateTotal();

        assertEquals(2149L, order.getTotalAmountPennies());
        assertEquals(358L, order.getVatAmountPennies()); // pre-fix: 357
    }

    @Test
    @DisplayName("Order ↔ ledger VAT parity holds across a representative (subtotal, fee, rate) grid")
    void orderLedgerVatParityAcrossGrid() {
        long[] subtotals = {1L, 249L, 999L, 1000L, 1750L, 2149L, 8999L, 100_000L};
        long[] fees = {0L, 1L, 299L, 350L, 399L, 500L};
        for (VatRate rate : VatRate.values()) {
            for (long subtotal : subtotals) {
                for (long fee : fees) {
                    Order order = new Order();
                    order.addItem(new OrderItem(UUID.randomUUID(), 1, subtotal));
                    order.setDeliveryFeePennies(fee);
                    order.setVatRate(rate);
                    order.calculateTotal();

                    String scenario = "subtotal=" + subtotal + " fee=" + fee + " rate=" + rate;
                    // The single VatCalculator rule over the combined gross…
                    assertEquals(VatCalculator.vatFromGross(order.getTotalAmountPennies(), rate),
                            order.getVatAmountPennies(), scenario);
                    // …and byte-for-byte parity with the ledger row that
                    // OrderService/PaymentService build from the same gross.
                    FinancialTransaction ledgerRow = new FinancialTransaction();
                    ledgerRow.setAmountPennies(order.getTotalAmountPennies());
                    ledgerRow.setVatRate(rate);
                    assertEquals(ledgerRow.calculateVatAmount(), order.getVatAmountPennies(),
                            "order/ledger parity: " + scenario);
                }
            }
        }
    }
}
