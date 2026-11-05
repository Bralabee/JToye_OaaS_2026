-- V93 [PR #1124 review round 1, findings 1+2+8, #827 — the ledger row records HOW it was paid, when it is
-- written, instead of the label being derived from the order's CURRENT payment status at read time].
--
-- Why. The first cut of the "Paid online / Not paid online" split (#827) derived the label at read time from
-- orders.payment_status (CAPTURED or REFUNDED = online), in four places. Two defects follow from reading a fact
-- off a mutable column:
--   (a) a deposit-split order (V87) goes CAPTURED when only the DEPOSIT is taken online, and its full-gross SALE
--       is written later at COMPLETED when the balance was paid in cash; status-only derivation labelled the
--       whole total "Paid online" (10,000p with a 2,000p card deposit and an 8,000p cash balance reported
--       10,000p online, 0p not online);
--   (b) an order completed at the counter (SALE written while payment_status was NONE) and paid later by a
--       payment link flipped from "Not paid online" to "Paid online" in a period already closed, and a CSV
--       already handed to an accountant no longer matched the page.
-- The ledger is append-only; the channel is a fact about the moment the row was written, so it is stored on the
-- row.
--
-- 1. financial_transactions.paid_online_pennies BIGINT NULL: of this row's amount, how many pennies were taken
--    ONLINE (card, through the platform). A PENNIES column, not a boolean, because one SALE can be part online
--    and part cash (deposit by card, balance in cash) and the ledger keeps ONE sale per order
--    (uq_fin_tx_tenant_order on (tenant_id, order_id, entry_kind)). Two rows by channel would break that
--    index, double transaction_count, and need two REVERSALs on a void. The row keeps its single gross amount
--    and its single VAT figure; only the channel split is added. Same sign as amount_pennies: a REVERSAL
--    stores the NEGATIVE of its sale's figure, so online revenue is SALE minus REVERSAL exactly like revenue.
--    0 = none online (cash, and every MANUAL row); amount = wholly online; between = part online.
--    NULL = NOT RECORDED. ck_fin_tx_paid_online_pennies keeps the figure between 0 and the amount (either sign).
--    Mirrored on financial_transactions_aud in this SAME migration (FinancialTransaction is @Audited; a
--    missing _aud column throws at runtime on the next audited write: V30/V38).
--
-- BACKFILL, AND WHY SOME ROWS STAY NULL. NULL is different from 0 (the V63/V66 rule): a fabricated value is
-- indistinguishable from a real one, and the read-time rule being replaced is exactly a guess. So a legacy row
-- gets a value only where the row and its order prove it, and stays NULL ("not recorded", shown as such and
-- kept out of both the online and the not-online figures) where they do not:
--   * MANUAL rows: 0 (no order, no card).
--   * a SALE whose reference starts with 'Payment ' was written by the card-settlement webhook
--     (PaymentService writes "Payment <intent> for Order <n>" and the cash path writes "Order <n>"), so the
--     whole amount was taken online: amount_pennies.
--   * any other SALE whose order exists and is not CAPTURED or REFUNDED: it was completed without a card
--     capture: 0.
--   * any other SALE whose order is CAPTURED or REFUNDED and carries a deposit split with a balance still due:
--     the deposit was online and the balance was not: LEAST(deposit, amount).
--   * any other SALE whose order is CAPTURED or REFUNDED with no balance due: completed first and captured
--     later (counter, then a payment link) or captured and then completed: not provable from the row: NULL.
--   * a SALE whose order no longer exists, with no 'Payment ' reference: NULL.
--   * a REVERSAL takes the negative of its sale's figure (NULL when the sale's is NULL).
-- The ordering above is SALE rows first, then REVERSALs from them.
--
-- THE FORCE-RLS TRAP. financial_transactions and orders are ENABLE + FORCE ROW LEVEL SECURITY and Flyway runs as
-- the RLS-bound application role, so with no tenant GUC the policies hide every row: a bare UPDATE here would
-- match ZERO rows and report success, and because the column is nullable nothing would fail: every legacy row
-- would silently read "not recorded" (the V25 -> V44 -> V57 -> V78 -> V79 class). The backfill walks the tenants
-- registry (no RLS on tenants) and pins the same GUC the app sets, transaction-locally, per tenant, with both
-- sides of the join predicated on the pinned tenant. Proven on a non-fresh two-tenant database under a
-- NOSUPERUSER NOBYPASSRLS role, with a bare-UPDATE break arm, by LedgerPaidOnlineBackfillMigrationIntegrationTest.
-- Re-running is a no-op (every UPDATE is guarded by paid_online_pennies IS NULL). The _aud rows are history and
-- are NOT backfilled: NULL there means "not recorded".
--
-- No new table, no RLS policy change (financial_transactions_rls_policy is FOR ALL on tenant_id). No
-- placeholder syntax anywhere in this file: Flyway substitutes inside comments too.

-- ============================================================
-- 1. The column, its Envers mirror and its range check.
-- ============================================================
ALTER TABLE financial_transactions ADD COLUMN IF NOT EXISTS paid_online_pennies BIGINT;

COMMENT ON COLUMN financial_transactions.paid_online_pennies IS
    'Of amount_pennies, how many pennies were taken online (card, through the platform), recorded when the row is written (V93, #827). Same sign as amount_pennies (a REVERSAL stores the negative of its sale''s figure). 0 = none online, amount = wholly online, between = part online. NULL = not recorded.';

ALTER TABLE financial_transactions_aud ADD COLUMN IF NOT EXISTS paid_online_pennies BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_fin_tx_paid_online_pennies') THEN
        ALTER TABLE financial_transactions ADD CONSTRAINT ck_fin_tx_paid_online_pennies
            CHECK (paid_online_pennies IS NULL
                OR (amount_pennies >= 0 AND paid_online_pennies BETWEEN 0 AND amount_pennies)
                OR (amount_pennies < 0 AND paid_online_pennies BETWEEN amount_pennies AND 0));
    END IF;
END $$;

-- ============================================================
-- 2. Backfill, one tenant at a time with the tenant pinned.
-- ============================================================
DO $$
DECLARE
    t        RECORD;
    n        BIGINT;
    manual   BIGINT := 0;
    sales    BIGINT := 0;
    reversed BIGINT := 0;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        -- Same GUC the app sets (TenantSetLocalAspect); transaction-local, gone when Flyway commits.
        PERFORM set_config('app.current_tenant_id', t.id::text, true);

        -- MANUAL rows: no order, nothing taken by card.
        UPDATE financial_transactions ft
           SET paid_online_pennies = 0
         WHERE ft.tenant_id = t.id
           AND ft.entry_kind = 'MANUAL'
           AND ft.paid_online_pennies IS NULL;
        GET DIAGNOSTICS n = ROW_COUNT;
        manual := manual + n;

        -- SALE rows: the value is decided in a subselect so the order is LEFT-joined (a sale whose order is gone
        -- must still be considered) and both tables are read under the pinned tenant.
        UPDATE financial_transactions ft
           SET paid_online_pennies = x.online
          FROM (
                SELECT f.id,
                       CASE
                         WHEN f.reference LIKE 'Payment %' THEN f.amount_pennies
                         WHEN o.id IS NULL THEN NULL
                         WHEN o.payment_status IS NULL OR o.payment_status NOT IN ('CAPTURED', 'REFUNDED') THEN 0
                         WHEN o.deposit_amount_pennies IS NOT NULL AND COALESCE(o.balance_due_pennies, 0) > 0
                              THEN GREATEST(0, LEAST(o.deposit_amount_pennies, f.amount_pennies))
                         ELSE NULL
                       END AS online
                  FROM financial_transactions f
                  LEFT JOIN orders o ON o.id = f.order_id AND o.tenant_id = t.id
                 WHERE f.tenant_id = t.id
                   AND f.entry_kind = 'SALE'
                   AND f.paid_online_pennies IS NULL
               ) x
         WHERE ft.id = x.id
           AND ft.tenant_id = t.id
           AND x.online IS NOT NULL;
        GET DIAGNOSTICS n = ROW_COUNT;
        sales := sales + n;

        -- REVERSAL rows: the negative of the sale's figure, once the sale has one.
        UPDATE financial_transactions r
           SET paid_online_pennies = -s.paid_online_pennies
          FROM financial_transactions s
         WHERE r.tenant_id = t.id
           AND s.tenant_id = t.id
           AND r.entry_kind = 'REVERSAL'
           AND r.reverses_transaction_id = s.id
           AND r.paid_online_pennies IS NULL
           AND s.paid_online_pennies IS NOT NULL;
        GET DIAGNOSTICS n = ROW_COUNT;
        reversed := reversed + n;
    END LOOP;

    -- Defensive reset so no later statement in this transaction inherits the last tenant's GUC (V44/V57/V78/V79).
    PERFORM set_config('app.current_tenant_id', '', true);

    RAISE NOTICE 'V93: recorded the payment channel on % MANUAL, % SALE and % REVERSAL ledger row(s); rows the order cannot prove stay NULL (not recorded).',
        manual, sales, reversed;
END $$;
