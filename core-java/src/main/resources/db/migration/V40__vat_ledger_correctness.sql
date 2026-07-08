-- V40: VAT ledger correctness (Issue #81 [P0-5]) — schema half.
--
-- This migration is the schema companion to a CODE change that fixes three
-- independent VAT ledger defects:
--   BUG 1 (VAT-on-VAT / VAT-on-top): VAT is now derived from a VAT-inclusive
--          gross via the UK VAT fraction method (gross * rate / (100+rate)).
--   BUG 2 (hardcoded STANDARD): the order's VAT rate is now resolved per product
--          (predominant liability), so products need a persisted vat_rate.
--   BUG 3 (duplicate ledger): a settled order must produce EXACTLY ONE
--          financial_transactions row; enforced by a partial unique index on
--          (tenant_id, order_id) plus a service-layer idempotency guard.
--
-- ============================================================================
-- AUDIT NOTE — read before reasoning about historical financial data (Decision B)
-- ============================================================================
-- (a) FRACTION-METHOD ADOPTION IS A READ-TIME CODE CHANGE, NOT A DATA REWRITE.
--     VAT is a DERIVED value on financial_transactions (there is no stored
--     vat_amount column — VAT is recomputed from amount_pennies + vat_rate at
--     read time by calculateVatAmount() and the JPQL summary aggregates). The
--     code switch to the fraction method therefore re-derives correct VAT for
--     ALL rows, historical included, with nothing to UPDATE here.
-- (b) DUPLICATE COLLAPSE: legacy card orders double-counted revenue because both
--     PaymentService (on settlement) and OrderService (on COMPLETED) inserted a
--     row. Section 4 collapses those duplicates, keeping the earliest row per
--     (tenant_id, order_id).
-- (c) DELIBERATE PRESERVATION (flagged for developer review in the SUMMARY):
--     * historical amount_pennies is NOT rewritten — it records actual money
--       settled via Stripe and MUST stay reconcilable with bank / Stripe records.
--     * historical per-row vat_rate is NOT rewritten — no per-order rate existed
--       before products.vat_rate, so STANDARD remains the best-available
--       historical assumption. Rewriting it would fabricate history.
--     Only genuinely duplicated rows are removed; surviving amounts are untouched.
--
-- No dedicated audit/log table exists in this schema (verified), so the audit
-- trail of this correction is: this comment block, the Envers _aud mirrors below
-- (revisions keep writing), and the runtime RAISE NOTICE in section 6.
--
-- All statements are forward-only and safe no-ops on a fresh (empty) schema:
-- the historical UPDATE/DELETE touch zero rows, and every ALTER is additive.

-- ----------------------------------------------------------------------------
-- 1. DECISION A — products.vat_rate (mirror V12's VARCHAR(20)+CHECK convention)
-- ----------------------------------------------------------------------------
-- NOT NULL DEFAULT 'STANDARD' backfills existing product rows via the column
-- default — no separate UPDATE, and (crucially) no Envers revision is minted,
-- so existing products keep their current audit history. No product is silently
-- zero-rated: absence of an explicit rate means STANDARD, the conservative UK
-- default for prepared food retail.
ALTER TABLE products
    ADD COLUMN vat_rate VARCHAR(20) NOT NULL DEFAULT 'STANDARD'
    CHECK (vat_rate IN ('ZERO', 'REDUCED', 'STANDARD', 'EXEMPT'));

-- Envers audit mirror: history columns are ALWAYS nullable, no default/CHECK
-- (audit-column convention per V7/V16/V19/V20). Without this, the next audited
-- products write would fail at the audit INSERT with 'column does not exist'.
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS vat_rate VARCHAR(20);

-- ----------------------------------------------------------------------------
-- 2. DEDUPE SCHEMA — financial_transactions.order_id
-- ----------------------------------------------------------------------------
-- Nullable: the admin / manual ledger path (FinancialTransactionController)
-- creates transactions with no owning order. Only order-linked rows participate
-- in dedup and the partial unique index below.
ALTER TABLE financial_transactions ADD COLUMN order_id UUID;

-- Envers audit mirror (nullable history column, per convention).
ALTER TABLE financial_transactions_aud ADD COLUMN IF NOT EXISTS order_id UUID;

-- ----------------------------------------------------------------------------
-- 3. DECISION B — historical order_id backfill (best-effort; safe on zero rows)
-- ----------------------------------------------------------------------------
-- Both legacy reference formats end with 'Order <order_number>':
--   OrderService:   "Order ORD-...."
--   PaymentService: "Payment pi_... for Order ORD-...."
-- so a suffix match on '%Order <order_number>' links both to the same order.
-- This is best-effort: it only enables the duplicate collapse + unique index.
-- Rows whose reference does not match stay order_id = NULL and are harmless
-- (they are excluded from the partial unique index).
UPDATE financial_transactions ft
   SET order_id = o.id
  FROM orders o
 WHERE o.tenant_id = ft.tenant_id
   AND ft.order_id IS NULL
   AND ft.reference LIKE '%Order ' || o.order_number;

-- ----------------------------------------------------------------------------
-- 4. DECISION B — collapse duplicate ledger rows (safe on zero rows)
-- ----------------------------------------------------------------------------
-- Delete all but the EARLIEST row per (tenant_id, order_id) where order_id is
-- set. Keeping MIN(created_at) retains the PaymentService settlement row for
-- card orders (which fires first, on payment) and the sole row for cash orders.
-- amount_pennies / vat_rate on retained rows are intentionally NOT rewritten
-- (see AUDIT NOTE (c) above). Runs BEFORE the unique index so it cannot fail on
-- pre-existing duplicates.
DELETE FROM financial_transactions ft
 USING (
     SELECT id,
            ROW_NUMBER() OVER (
                PARTITION BY tenant_id, order_id
                ORDER BY created_at, id
            ) AS rn
       FROM financial_transactions
      WHERE order_id IS NOT NULL
 ) dup
 WHERE ft.id = dup.id
   AND dup.rn > 1;

-- ----------------------------------------------------------------------------
-- 5. DEDUPE guarantee — partial unique index (created AFTER the collapse)
-- ----------------------------------------------------------------------------
-- The race-safe backstop behind the service-layer findByOrderId() guard: a
-- concurrent second createTransaction() for the same order raises a unique
-- violation, which the service catches and resolves to the existing row.
-- Partial (WHERE order_id IS NOT NULL) so the admin/manual path (order_id NULL)
-- is unconstrained.
CREATE UNIQUE INDEX IF NOT EXISTS uq_fin_tx_tenant_order
    ON financial_transactions (tenant_id, order_id)
    WHERE order_id IS NOT NULL;

-- ----------------------------------------------------------------------------
-- 6. AUDIT NOTE — runtime marker for the correction (no audit/log table exists)
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    RAISE NOTICE 'V40 VAT ledger correctness applied: products.vat_rate added '
        '(default STANDARD), financial_transactions.order_id added + backfilled, '
        'duplicate order-linked ledger rows collapsed (earliest retained), partial '
        'unique index uq_fin_tx_tenant_order created. Historical amount_pennies and '
        'vat_rate deliberately preserved (reconcilable with Stripe/bank); VAT is '
        're-derived at read time via the HMRC fraction method.';
END $$;
