-- V33: Fix RLS policy gaps found in deep audit (2026-04-16)
--
-- TENANT-02: payment_event_outbox had no RLS — scheduled flusher queried all tenants
-- TENANT-03: shop_promotions, shop_announcements had USING(true) SELECT — cross-tenant read
-- TENANT-04: reviews had USING(true) SELECT — cross-tenant read
--
-- Public storefront endpoints need to read promotions/announcements/reviews for published
-- shops, so the SELECT policy allows reads when the shop is published OR the row belongs
-- to the current tenant. This preserves public storefront functionality while preventing
-- cross-tenant enumeration of unpublished shop data.

-- ============================================================
-- 1. payment_event_outbox: Add RLS (was completely missing)
-- ============================================================

ALTER TABLE payment_event_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_event_outbox FORCE ROW LEVEL SECURITY;

CREATE POLICY payment_event_outbox_tenant ON payment_event_outbox
    FOR ALL
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- ============================================================
-- 2. shop_promotions: Replace unrestricted SELECT with tenant-scoped
-- ============================================================

DROP POLICY IF EXISTS shop_promotions_read ON shop_promotions;

CREATE POLICY shop_promotions_read ON shop_promotions
    FOR SELECT
    USING (
        tenant_id = current_setting('app.current_tenant_id', true)::uuid
        OR EXISTS (
            SELECT 1 FROM shops
            WHERE shops.id = shop_promotions.shop_id
              AND shops.published = true
        )
    );

-- ============================================================
-- 3. shop_announcements: Replace unrestricted SELECT with tenant-scoped
-- ============================================================

DROP POLICY IF EXISTS shop_announcements_read ON shop_announcements;

CREATE POLICY shop_announcements_read ON shop_announcements
    FOR SELECT
    USING (
        tenant_id = current_setting('app.current_tenant_id', true)::uuid
        OR EXISTS (
            SELECT 1 FROM shops
            WHERE shops.id = shop_announcements.shop_id
              AND shops.published = true
        )
    );

-- ============================================================
-- 4. reviews: Replace unrestricted SELECT with tenant-scoped
-- ============================================================

DROP POLICY IF EXISTS reviews_tenant_read ON reviews;

CREATE POLICY reviews_tenant_read ON reviews
    FOR SELECT
    USING (
        tenant_id = current_setting('app.current_tenant_id', true)::uuid
        OR EXISTS (
            SELECT 1 FROM shops
            WHERE shops.id = reviews.shop_id
              AND shops.published = true
        )
    );
