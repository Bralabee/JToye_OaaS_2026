package uk.jtoye.core.finance;

/**
 * What a ledger row IS (Phase 37-10, D-10; owner ruling 2026-10-08: entry-kind).
 *
 * <p>Until V78 every order-linked row was "the" ledger row of its order, and the sign of the amount
 * was the only classification: positive was revenue, negative an expense. A void needs a second row
 * for the same order that takes the sale back out of revenue, and a negative amount alone cannot say
 * that, because it would read as an expense. The kind says it.
 *
 * <ul>
 *   <li>{@link #SALE}: the settlement of an order, written once per order by
 *       {@code FinancialTransactionService.createTransaction} (card settlement or completion).</li>
 *   <li>{@link #REVERSAL}: takes a SALE back out (a void). Negative amount, the sale's VAT rate, and
 *       {@code reverses_transaction_id} naming the sale. At most one per order.</li>
 *   <li>{@link #MANUAL}: a row with no order, written through the admin ledger endpoint. The sign
 *       rule still applies to these: positive is revenue, negative is an expense.</li>
 * </ul>
 *
 * <p>Stored as its name in {@code financial_transactions.entry_kind}, constrained by
 * {@code ck_fin_tx_entry_kind}. Revenue is SALE minus REVERSAL; MANUAL rows keep the sign rule.
 */
public enum EntryKind {
    SALE,
    REVERSAL,
    MANUAL
}
