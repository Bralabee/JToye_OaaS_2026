-- V78: Phase 37-10 (D-10, RWO-018 / UXT-018, P0) — an order the books depend on is VOIDED, not deleted,
-- and the ledger says so with a reversing entry. Owner ruling 2026-10-08: ledger representation =
-- entry-kind (one ledger table; a reversal is an ordinary immutable row).
--
-- Why. The persona run deleted a COMPLETED order as a shop manager: 204, the order then read 404, and its
-- financial_transactions row stayed behind pointing at it (order_id has no foreign key, V40). 37-10 Task 1
-- refuses that delete (409 order-not-deletable). This migration gives the Group admin the alternative the
-- refusal names: a void that keeps the order, records who/when/why, and takes the sale back out of the
-- ledger by a REVERSAL row written in the same transaction.
--
-- 1. orders: voided_at / voided_by / void_reason. Columns, NOT a VOIDED status (CONTEXT discretion Q6):
--    orders_status_check is a CHECK whose last extension forced a rewrite (V36's "V6 landmine"), and a new
--    status would ripple through OrderStatus, the state machine, the kitchen statuses, the frontend status
--    maps, MCP and webhook payloads. A voided order keeps the status it had. ck_orders_void_triple makes a
--    half-written void unstorable (all three set, or none). Mirrored on orders_aud in this SAME migration:
--    Order is @Audited, and a missing _aud column throws at runtime on the next audited write (V30/V38).
--
-- 2. financial_transactions.entry_kind: SALE | REVERSAL | MANUAL (ck_fin_tx_entry_kind).
--      SALE     — the settlement of an order (card settlement or completion), one per order.
--      REVERSAL — takes a SALE back out (a void): negative amount, the sale's vat_rate, the same order_id,
--                 and reverses_transaction_id naming the sale. At most one per order.
--      MANUAL   — a row with no order (the admin ledger endpoint). Positive is revenue, negative an expense,
--                 exactly as before.
--    Revenue becomes SALE minus REVERSAL in both aggregates (FinancialTransactionRepository); without the kind
--    a negative reversal would be reported as an EXPENSE. No VAT computation changes (CONTEXT discretion Q2):
--    a reversal copies the sale's rate, so its VAT is the sale's VAT negated by the same fraction method.
--    ck_fin_tx_reversal_link: a REVERSAL always names its sale, and nothing else names one.
--    reverses_transaction_id references financial_transactions(id): a reversal cannot name a row that does
--    not exist. Mirrored on financial_transactions_aud (nullable, unconstrained: the house _aud shape).
--
-- 3. uq_fin_tx_tenant_order is REPLACED: (tenant_id, order_id) becomes (tenant_id, order_id, entry_kind),
--    still partial on order_id IS NOT NULL. Two rows per order (its SALE and its REVERSAL) are now normal,
--    and the database still allows at most ONE of each. It remains the race backstop behind
--    FinancialTransactionService's idempotency fast path, which is now keyed by SALE (an unkeyed
--    single-row lookup throws once a reversal exists beside the sale).
--
-- BACKFILL, AND THE FORCE-RLS TRAP. Existing rows become SALE where order_id is set and MANUAL where it is
-- not. financial_transactions is ENABLE + FORCE RLS (V2) and Flyway runs as the RLS-bound application role,
-- so during migration current_tenant_id() is NULL and the policy hides every row: a bare UPDATE here would
-- match ZERO rows and report success, and because the column is added with DEFAULT 'SALE' nothing would then
-- fail — every order-less (manual) row would silently become a SALE. That is the V25 -> V44 -> V57 defect
-- class. The backfill therefore walks the tenants registry (no RLS on tenants) and pins the same GUC the app
-- sets, transaction-locally, per tenant, so every row is reached WITH the policy enforced. Proven on a
-- non-fresh two-tenant database under a NOSUPERUSER NOBYPASSRLS role by
-- LedgerEntryKindMigrationIntegrationTest (a fresh Testcontainers database has nothing to backfill, and its
-- superuser bypasses FORCE RLS). Re-running is a no-op (WHERE entry_kind = 'SALE' AND order_id IS NULL).
-- The _aud rows are history and are NOT backfilled: NULL there means "not recorded".
--
-- No new table, no RLS policy change (financial_transactions_rls_policy is FOR ALL on tenant_id; the new
-- columns add no trust boundary). No placeholder syntax anywhere in this file: Flyway substitutes inside
-- comments too.

-- ============================================================
-- 1. orders: the void triple, and its Envers mirror.
-- ============================================================
ALTER TABLE orders ADD COLUMN IF NOT EXISTS voided_at   TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS voided_by   VARCHAR(255);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS void_reason VARCHAR(500);

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_orders_void_triple') THEN
    ALTER TABLE orders ADD CONSTRAINT ck_orders_void_triple
      CHECK ((voided_at IS NULL) = (voided_by IS NULL) AND (voided_at IS NULL) = (void_reason IS NULL));
  END IF;
END $$;

COMMENT ON COLUMN orders.voided_at IS
    'When a Group admin voided this order (Phase 37-10, D-10). NULL means not voided. A voided order keeps its status; it leaves the kitchen board and its SALE is reversed in financial_transactions. Written once, with voided_by and void_reason (ck_orders_void_triple).';
COMMENT ON COLUMN orders.voided_by IS
    'Who voided the order: the authenticated principal name. NULL means not voided.';
COMMENT ON COLUMN orders.void_reason IS
    'Why the order was voided, as the Group admin wrote it (trimmed, at most 500 characters). NULL means not voided.';

ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS voided_at   TIMESTAMPTZ;
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS voided_by   VARCHAR(255);
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS void_reason VARCHAR(500);

-- ============================================================
-- 2. financial_transactions: entry_kind and reverses_transaction_id.
--    Added with DEFAULT 'SALE', so every existing row reads SALE until the loop below reclassifies the
--    order-less ones.
-- ============================================================
ALTER TABLE financial_transactions ADD COLUMN IF NOT EXISTS entry_kind VARCHAR(16) DEFAULT 'SALE';
ALTER TABLE financial_transactions ADD COLUMN IF NOT EXISTS reverses_transaction_id UUID
    REFERENCES financial_transactions(id);

DO $$
DECLARE
    t      RECORD;
    n      BIGINT;
    manual BIGINT := 0;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        -- Same GUC the app sets (TenantSetLocalAspect); transaction-local, gone when Flyway commits.
        PERFORM set_config('app.current_tenant_id', t.id::text, true);

        UPDATE financial_transactions
           SET entry_kind = 'MANUAL'
         WHERE tenant_id = t.id
           AND order_id IS NULL
           AND entry_kind = 'SALE';
        GET DIAGNOSTICS n = ROW_COUNT;
        manual := manual + n;
    END LOOP;

    -- Defensive reset so no later statement in this transaction inherits the last tenant's GUC (V44/V57).
    PERFORM set_config('app.current_tenant_id', '', true);

    RAISE NOTICE 'V78: classified % pre-V78 order-less ledger row(s) as MANUAL; order-linked rows are SALE.', manual;
END $$;

ALTER TABLE financial_transactions ALTER COLUMN entry_kind SET NOT NULL;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_fin_tx_entry_kind') THEN
    ALTER TABLE financial_transactions ADD CONSTRAINT ck_fin_tx_entry_kind
      CHECK (entry_kind IN ('SALE', 'REVERSAL', 'MANUAL'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_fin_tx_reversal_link') THEN
    ALTER TABLE financial_transactions ADD CONSTRAINT ck_fin_tx_reversal_link
      CHECK ((entry_kind = 'REVERSAL') = (reverses_transaction_id IS NOT NULL));
  END IF;
END $$;

COMMENT ON COLUMN financial_transactions.entry_kind IS
    'What the row is (Phase 37-10, D-10): SALE (an order''s settlement, one per order), REVERSAL (takes a SALE back out when the order is voided; negative, same vat_rate, names the sale), MANUAL (no order; positive revenue, negative expense). Revenue is SALE minus REVERSAL.';
COMMENT ON COLUMN financial_transactions.reverses_transaction_id IS
    'For a REVERSAL, the SALE it takes back out. NULL for every other kind (ck_fin_tx_reversal_link).';

ALTER TABLE financial_transactions_aud ADD COLUMN IF NOT EXISTS entry_kind VARCHAR(16);
ALTER TABLE financial_transactions_aud ADD COLUMN IF NOT EXISTS reverses_transaction_id UUID;

-- ============================================================
-- 3. One SALE and at most one REVERSAL per order: the unique index gains entry_kind.
-- ============================================================
DROP INDEX IF EXISTS uq_fin_tx_tenant_order;
CREATE UNIQUE INDEX uq_fin_tx_tenant_order
    ON financial_transactions (tenant_id, order_id, entry_kind)
    WHERE order_id IS NOT NULL;

DO $$
BEGIN
    RAISE NOTICE 'V78 order void + ledger reversal applied: orders and orders_aud gained voided_at, voided_by, '
        'void_reason (ck_orders_void_triple); financial_transactions and its _aud gained entry_kind '
        '(SALE/REVERSAL/MANUAL) and reverses_transaction_id; uq_fin_tx_tenant_order is now '
        '(tenant_id, order_id, entry_kind) WHERE order_id IS NOT NULL.';
END $$;
