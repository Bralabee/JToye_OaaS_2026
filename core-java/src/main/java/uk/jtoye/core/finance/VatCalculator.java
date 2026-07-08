package uk.jtoye.core.finance;

import java.util.EnumMap;
import java.util.List;

/**
 * Single source of truth for UK VAT arithmetic.
 *
 * <p>Prices in this platform are VAT-INCLUSIVE consumer (retail) prices, so VAT
 * is never <em>added on top</em>; it is the fraction already contained WITHIN a
 * gross amount. This class implements the HMRC "VAT fraction" method
 * (HMRC VAT Notice 700 §17.5.1):
 *
 * <ul>
 *   <li>STANDARD (20%): {@code vat = gross * 20 / 120} (= gross / 6)</li>
 *   <li>REDUCED (5%):   {@code vat = gross * 5 / 105}</li>
 *   <li>ZERO / EXEMPT:  {@code vat = 0}</li>
 * </ul>
 *
 * <h2>Rounding: round DOWN (truncate toward zero)</h2>
 * HMRC VAT Notice 700 §17.5.1 permits rounding the VAT amount down to the
 * nearest penny. We deliberately choose truncation over half-up because Java
 * {@code long} integer division and PostgreSQL integer division BOTH truncate
 * toward zero identically — giving trivial byte-for-byte parity between this
 * Java helper and the JPQL aggregate expressions ({@code (amount * 20) / 120}
 * etc.) that compute the same value DB-side. Multiplying before dividing keeps
 * full precision until the final truncation.
 *
 * <p>Truncation toward zero also gives the correct sign behaviour for negative
 * gross amounts (expenses / refunds): {@code -1200 STANDARD -> -200} and
 * {@code -100 STANDARD -> -16}, matching Postgres.
 *
 * <p>This is the ONLY place VAT-from-gross is computed in Java. The entity
 * ({@link FinancialTransaction#calculateVatAmount()}), the order math
 * ({@code Order.calculateTotal()}) and the DB aggregates all reconcile to it.
 */
public final class VatCalculator {

    private VatCalculator() {
        // static-only utility
    }

    /**
     * Derive the VAT contained within a VAT-inclusive gross amount using the UK
     * VAT fraction method, rounding DOWN (integer division truncating toward
     * zero) per HMRC VAT Notice 700 §17.5.1.
     *
     * @param grossPennies VAT-inclusive gross amount in pennies (may be negative
     *                     for expenses / refunds)
     * @param rate         the VAT rate category
     * @return the VAT portion in pennies (truncated toward zero)
     */
    public static long vatFromGross(long grossPennies, VatRate rate) {
        int pct = switch (rate) {
            case ZERO, EXEMPT -> 0;
            case REDUCED -> 5;
            case STANDARD -> 20;
        };
        if (pct == 0) {
            return 0L;
        }
        // Multiply BEFORE divide to preserve precision; integer division
        // truncates toward zero == HMRC round-down, and mirrors Postgres.
        return grossPennies * pct / (100 + pct);
    }

    /**
     * A single order line's VAT-inclusive gross and its resolved VAT rate, used
     * to compute an order's predominant liability.
     */
    public record LineRate(long grossPennies, VatRate rate) {
    }

    /**
     * Resolve the PREDOMINANT VAT rate for a basket (Issue #81 BUG 2).
     *
     * <p>The order carries a SINGLE {@code vatRate}. Per-line VAT reporting at
     * mixed rates in the ledger is deliberately out of scope for this issue (it
     * would require {@code order_items.vat_rate} + a stored VAT column, neither
     * of which is in the locked decision set) — this is a bounded model choice,
     * not a placeholder.
     *
     * <p>Algorithm: for each line compute its NET (ex-VAT) goods value
     * ({@code gross - vatFromGross(gross, rate)}), sum net per rate, and return
     * the rate carrying the greatest summed net value. The delivery fee then
     * follows this predominant liability via {@code Order.calculateTotal()}.
     *
     * <p>Tie-break: iterate the fixed priority order STANDARD &gt; REDUCED &gt;
     * ZERO &gt; EXEMPT and keep the first rate at the maximum, so equal net
     * values resolve to STANDARD (most conservative for HMRC). A null line rate
     * is treated as STANDARD (no silent zero-rating). An all-ZERO basket returns
     * ZERO — genuinely zero-rated baskets are NOT upgraded to STANDARD. An empty
     * line list returns STANDARD.
     */
    public static VatRate predominantRate(List<LineRate> lines) {
        if (lines == null || lines.isEmpty()) {
            return VatRate.STANDARD;
        }
        EnumMap<VatRate, Long> netByRate = new EnumMap<>(VatRate.class);
        for (LineRate line : lines) {
            VatRate rate = line.rate() == null ? VatRate.STANDARD : line.rate();
            long net = line.grossPennies() - vatFromGross(line.grossPennies(), rate);
            netByRate.merge(rate, net, Long::sum);
        }
        VatRate best = null;
        long bestNet = Long.MIN_VALUE;
        for (VatRate rate : new VatRate[]{VatRate.STANDARD, VatRate.REDUCED, VatRate.ZERO, VatRate.EXEMPT}) {
            Long net = netByRate.get(rate);
            // Strict '>' with priority-ordered iteration means STANDARD wins ties.
            if (net != null && net > bestNet) {
                bestNet = net;
                best = rate;
            }
        }
        return best != null ? best : VatRate.STANDARD;
    }
}
