-- =============================================================================
-- V86: Old-slug 301 aliases (Phase 39 / Plan 39-09, RWO-028, owner ruling 2026-10-10)
-- =============================================================================
--
-- WHY THIS TABLE EXISTS
-- ---------------------
-- Phase 37-48 made shop edits keep the stored `shops.slug` unless a vendor explicitly
-- requests a different web address. When a vendor DOES deliberately change a shop's
-- web address, printed QR codes, menus, and shared links still carry the previous
-- slug. Recording the previous slug in `shop_slug_alias` allows the public storefront
-- to answer requests for the old address with a permanent redirect (HTTP 301) to the
-- shop's current canonical slug, and stops another shop from claiming a retired slug
-- and hijacking those links.
--
-- WHY THIS TABLE IS DELIBERATELY NOT TENANT-SCOPED (RLS-EXEMPT BY ADDITION)
-- -------------------------------------------------------------------------
-- 1. Anonymous storefront resolution happens BEFORE any tenant is known (no JWT,
--    no TenantContext, no `app.current_tenant_id` GUC on the connection), exactly
--    like `dsar_request` (V62) and `dsar_access_export` (V75). Enabling + forcing
--    a tenant-pinned RLS policy here would return zero rows to the anonymous caller
--    and silently break every redirect while tests stayed green.
-- 2. Shop slugs (`shops.slug`, `idx_shops_slug`) are globally unique across all
--    tenants; `shop_slug_alias.old_slug` is likewise globally unique across all
--    tenants (`PRIMARY KEY (old_slug)`), and collision checks on shop create/update
--    must see aliases held by any tenant so Vendor B cannot claim Vendor A's old slug.
-- 3. No personal data and no unpublished shop disclosure: the row holds only a URL
--    slug string, the target `shop_id` FK (`ON DELETE CASCADE`), and `created_at`.
--    Public resolution joins `shops` with `published = true`, so an unpublished
--    shop's alias returns HTTP 404 and never discloses the shop's existence or
--    current slug.
-- =============================================================================

CREATE TABLE IF NOT EXISTS shop_slug_alias (
    old_slug VARCHAR(100) PRIMARY KEY,
    shop_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_shop_slug_alias_shop
        FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_shop_slug_alias_shop_id
    ON shop_slug_alias(shop_id);

COMMENT ON TABLE shop_slug_alias IS
    'Historical shop slugs (39-09 / RWO-028, owner ruling 2026-10-10): maps a previous shops.slug to its shop so printed QR codes and shared links permanently redirect (HTTP 301) after a deliberate slug change, and prevents other shops from claiming a retired slug. RLS-exempt by addition in RlsContractTest.EXEMPT_TABLES.';

COMMENT ON COLUMN shop_slug_alias.old_slug IS
    'Previous URL slug that was replaced on a deliberate shop web-address change; globally unique across all shops.';

COMMENT ON COLUMN shop_slug_alias.shop_id IS
    'Target shop that previously held old_slug (ON DELETE CASCADE removes aliases when the shop is deleted).';

DO $$
BEGIN
    RAISE NOTICE 'V86 shop_slug_alias table and idx_shop_slug_alias_shop_id created';
END $$;
