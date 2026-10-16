-- V85: Phase 39-08 (RWO-CUST-OPT-C, Option C per-shop customer narrowing, owner ruling 2026-10-10).
--
-- Why. Until V85, customers carried no shop attribution of their own, so WINDOWS #29 (Option B, 37-15)
-- and Option E (2026-10-10) could only gate whether a caller had a grant at all (reads: any grant;
-- create/update: SHOP_MANAGER; delete: GROUP_ADMIN), leaving every per-shop staff member or manager
-- seeing the entire tenant's customer book. Option C narrows non-allShops callers to customers that
-- either originated at one of their granted shops (origin_shop_id IN (:grantedShopIds)) OR have placed
-- at least one order at one of their granted shops.
--
-- 1. customers.origin_shop_id UUID NULL REFERENCES shops(id) ON DELETE SET NULL.
--    Nullable by design: legacy order-less customers and customers created by a tenant-wide caller
--    without naming a shop have NULL origin_shop_id and remain visible to allShops callers (or to a
--    per-shop caller once the customer places an order at that shop). ON DELETE SET NULL ensures that
--    deleting an empty shop never fails on a customer origin reference. Mirrored on customers_aud in
--    this SAME migration because Customer is @Audited (Envers).
--
-- 2. idx_customers_tenant_origin_shop ON customers (tenant_id, origin_shop_id) WHERE origin_shop_id IS NOT NULL:
--    supports the per-shop narrowing predicate (c.tenant_id = :tenantId AND c.origin_shop_id IN (:shopIds)).
--
-- 3. Backfill under FORCE RLS. Both customers and orders have ENABLE + FORCE ROW LEVEL SECURITY, and
--    Flyway runs as the RLS-bound application role where current_tenant_id() is NULL by default. A bare
--    UPDATE ... FROM orders would match ZERO rows and silently succeed. We therefore walk the tenants
--    table (unscoped) and pin app.current_tenant_id per tenant with set_config(..., true), attributing
--    each pre-V85 customer to the shop of their earliest order (ORDER BY o.created_at ASC, o.id ASC).
--    Proven under a NOSUPERUSER NOBYPASSRLS role in CustomerOriginShopScopeIntegrationTest.

-- ============================================================
-- 1. Column and Envers audit mirror
-- ============================================================
ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS origin_shop_id UUID REFERENCES shops(id) ON DELETE SET NULL;

COMMENT ON COLUMN customers.origin_shop_id IS
    'The shop this customer originated at (Phase 39-08, Option C per-shop customer narrowing, RWO-CUST-OPT-C). Backfilled from the customer''s earliest order when one exists; stamped on createCustomer or on the customer''s first order when null. NULL for legacy order-less customers or customers created by a tenant-wide caller without naming a shop.';

ALTER TABLE customers_aud
    ADD COLUMN IF NOT EXISTS origin_shop_id UUID;

-- ============================================================
-- 2. Partial index for tenant + origin_shop_id lookups
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_customers_tenant_origin_shop
    ON customers (tenant_id, origin_shop_id)
    WHERE origin_shop_id IS NOT NULL;

-- ============================================================
-- 3. Per-tenant GUC-pinned backfill from earliest order
-- ============================================================
DO $$
DECLARE
    t      RECORD;
    n      BIGINT;
    filled BIGINT := 0;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.current_tenant_id', t.id::text, true);

        UPDATE customers c
           SET origin_shop_id = earliest.shop_id
          FROM (
              SELECT DISTINCT ON (o.customer_id)
                     o.customer_id,
                     o.shop_id
                FROM orders o
               WHERE o.tenant_id = t.id
                 AND o.customer_id IS NOT NULL
                 AND o.shop_id IS NOT NULL
               ORDER BY o.customer_id, o.created_at ASC, o.id ASC
          ) earliest
         WHERE c.tenant_id = t.id
           AND c.id = earliest.customer_id
           AND c.origin_shop_id IS NULL;

        GET DIAGNOSTICS n = ROW_COUNT;
        filled := filled + n;
    END LOOP;

    PERFORM set_config('app.current_tenant_id', '', true);

    RAISE NOTICE 'V85: attributed % pre-V85 customer(s) to their earliest order''s shop; order-less customers stay shop-less.', filled;
END $$;
