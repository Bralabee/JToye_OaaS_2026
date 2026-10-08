-- V79: Phase 37-11 (RWO-027 / UXT-027, roadmap SC-2) — every ledger row knows its shop, so each shop can
-- have its own figures and an export, without opening the tenant ledger to non-admins.
--
-- Why. Per-shop finance showed the TENANT's totals, and a site manager's request was refused by the
-- class-level admin gate, which the dashboard rendered as "No financial data yet". financial_transactions
-- carried no shop, so there was nothing to filter on. From 37-11 the finance reads take an optional shop:
-- with one, the caller needs SHOP_MANAGER on it and sees only its rows; without one, the caller needs Group
-- admin and sees the whole tenant.
--
-- 1. financial_transactions.shop_id UUID NULL REFERENCES shops(id). NULLABLE, deliberately (the plan's
--    add-alongside decision): a MANUAL row (the admin ledger endpoint, no order) has no shop, and neither
--    does a legacy order-less row. Such rows appear ONLY in the tenant-wide (Group admin) view, so per-shop
--    totals need not sum to the tenant total. The writers set it from now on: order completion and card
--    settlement pass the order's shop, and a void's REVERSAL copies its SALE's shop. orders.shop_id already
--    references shops(id) with no cascade, so a shop that has ledger rows already cannot be deleted; this FK
--    adds no new way for a shop delete to fail. Mirrored on financial_transactions_aud in this SAME
--    migration: FinancialTransaction is @Audited, and a missing _aud column throws at runtime on the next
--    audited write (V30/V38).
--
-- 2. idx_fin_tx_tenant_shop_created ON (tenant_id, shop_id, created_at): the scoped summary, list and
--    export filter on shop and a date range inside a tenant.
--
-- BACKFILL, AND THE FORCE-RLS TRAP. Existing order-linked rows take their order's shop (orders.shop_id,
-- matched on order_id). financial_transactions and orders are both ENABLE + FORCE RLS, and Flyway runs as the
-- RLS-bound application role, so during migration current_tenant_id() is NULL and the policies hide every
-- row: a bare UPDATE ... FROM orders here would match ZERO rows and report success, and because the column is
-- nullable nothing would then fail — every existing sale would silently stay shop-less, and every shop's
-- figures would read as empty. That is the V25 -> V44 -> V57 -> V78 defect class. The backfill therefore walks
-- the tenants registry (no RLS on tenants) and pins the same GUC the app sets, transaction-locally, per
-- tenant, so every row is reached WITH the policies enforced, and both sides of the join are predicated on
-- the pinned tenant. Proven on a non-fresh two-tenant database under a NOSUPERUSER NOBYPASSRLS role by
-- LedgerShopBackfillMigrationIntegrationTest (a fresh Testcontainers database has nothing to backfill, and
-- its superuser bypasses FORCE RLS). Re-running is a no-op (WHERE ft.shop_id IS NULL). A row with no order,
-- or whose order no longer exists, stays NULL. The _aud rows are history and are NOT backfilled: NULL there
-- means "not recorded".
--
-- No new table, no RLS policy change (financial_transactions_rls_policy is FOR ALL on tenant_id; the new
-- column adds no trust boundary, and the shop gate is the application's ShopAccessService). No placeholder
-- syntax anywhere in this file: Flyway substitutes inside comments too.

-- ============================================================
-- 1. The column and its Envers mirror.
-- ============================================================
ALTER TABLE financial_transactions ADD COLUMN IF NOT EXISTS shop_id UUID REFERENCES shops(id);

COMMENT ON COLUMN financial_transactions.shop_id IS
    'The shop this row belongs to (Phase 37-11, RWO-027): the order''s shop for a SALE, the sale''s shop for a REVERSAL. NULL for a MANUAL row and for a legacy row with no order; such rows appear only in the tenant-wide (Group admin) view.';

ALTER TABLE financial_transactions_aud ADD COLUMN IF NOT EXISTS shop_id UUID;

-- ============================================================
-- 2. The scoped reads' index.
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_fin_tx_tenant_shop_created
    ON financial_transactions (tenant_id, shop_id, created_at);

-- ============================================================
-- 3. Backfill existing order-linked rows, one tenant at a time with the tenant pinned.
-- ============================================================
DO $$
DECLARE
    t      RECORD;
    n      BIGINT;
    filled BIGINT := 0;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        -- Same GUC the app sets (TenantSetLocalAspect); transaction-local, gone when Flyway commits.
        PERFORM set_config('app.current_tenant_id', t.id::text, true);

        UPDATE financial_transactions ft
           SET shop_id = o.shop_id
          FROM orders o
         WHERE ft.tenant_id = t.id
           AND o.tenant_id = t.id
           AND ft.order_id = o.id
           AND ft.shop_id IS NULL;
        GET DIAGNOSTICS n = ROW_COUNT;
        filled := filled + n;
    END LOOP;

    -- Defensive reset so no later statement in this transaction inherits the last tenant's GUC (V44/V57/V78).
    PERFORM set_config('app.current_tenant_id', '', true);

    RAISE NOTICE 'V79: attributed % pre-V79 order-linked ledger row(s) to their order''s shop; rows with no order stay shop-less.', filled;
END $$;
