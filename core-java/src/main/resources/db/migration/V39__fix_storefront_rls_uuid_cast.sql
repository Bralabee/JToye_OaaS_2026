-- V39: Fix storefront public-read RLS crash on empty tenant context (2026-07-06)
--
-- BUG (HTTP 409 on public storefront endpoints):
--   /public/shops/{slug}/promotions, /announcements and /config returned
--   409 "Duplicate Entry / Data integrity constraint violated". The real
--   Postgres error was 22P02: "invalid input syntax for type uuid: \"\"".
--
--   V33 defined the three SELECT policies below using the RAW expression
--   `current_setting('app.current_tenant_id', true)::uuid`. That cast is a
--   query-level constant, so Postgres evaluates it at query init -- even when
--   the table has zero rows. Anonymous storefront requests carry no tenant
--   context, so TenantSetLocalAspect resets the GUC to its default (empty
--   string) and ''::uuid raises 22P02. Spring maps the resulting
--   SQLException to DataIntegrityViolationException, which the global handler
--   renders as HTTP 409.
--
--   reviews_tenant_read only escaped because ReviewService.getShopReviews
--   sets TenantContext before querying; the policy itself is equally fragile
--   and is corrected here for consistency.
--
-- FIX:
--   Use the existing safe helper current_tenant_id(), which returns NULL for
--   an empty / 'default' / unset GUC instead of crashing -- the same pattern
--   already used by the products and shops RLS policies. Behaviour is
--   otherwise unchanged: rows still match for the authenticated tenant, and
--   published shops remain publicly readable via the EXISTS(published shop)
--   branch. With current_tenant_id() returning NULL, the tenant equality is
--   simply NULL (no match) and the public branch is reached without error.
--
-- No data change. Policy semantics preserved; only the unsafe cast is removed.

-- ============================================================
-- 1. shop_promotions: public storefront read
-- ============================================================

DROP POLICY IF EXISTS shop_promotions_read ON shop_promotions;

CREATE POLICY shop_promotions_read ON shop_promotions
    FOR SELECT
    USING (
        tenant_id = current_tenant_id()
        OR EXISTS (
            SELECT 1 FROM shops
            WHERE shops.id = shop_promotions.shop_id
              AND shops.published = true
        )
    );

-- ============================================================
-- 2. shop_announcements: public storefront read
-- ============================================================

DROP POLICY IF EXISTS shop_announcements_read ON shop_announcements;

CREATE POLICY shop_announcements_read ON shop_announcements
    FOR SELECT
    USING (
        tenant_id = current_tenant_id()
        OR EXISTS (
            SELECT 1 FROM shops
            WHERE shops.id = shop_announcements.shop_id
              AND shops.published = true
        )
    );

-- ============================================================
-- 3. reviews: public storefront read (latent same-bug hardening)
-- ============================================================

DROP POLICY IF EXISTS reviews_tenant_read ON reviews;

CREATE POLICY reviews_tenant_read ON reviews
    FOR SELECT
    USING (
        tenant_id = current_tenant_id()
        OR EXISTS (
            SELECT 1 FROM shops
            WHERE shops.id = reviews.shop_id
              AND shops.published = true
        )
    );
