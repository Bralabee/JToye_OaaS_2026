package uk.jtoye.core.finance;

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
}
