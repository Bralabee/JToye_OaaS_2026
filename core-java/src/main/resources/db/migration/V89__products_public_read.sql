-- =============================================================================
-- V89: products_public_read — an anonymous caller may read the products a
--      published shop already shows on its public menu, so the kitchen search
--      can find a kitchen by the DISHES it sells.
--      (owner-session ruling 2026-11-02; the query-time shop search that uses it
--      is ShopRepository.fullTextSearchPublished)
-- =============================================================================
--
-- WHY THIS MIGRATION EXISTS
-- -------------------------
-- A real external tester searched "pounded yam" and "plantain" on /shop and was
-- told "No kitchens found" while a published kitchen sold exactly those dishes.
-- The search read only shops.search_vector (V25: name, tags, description,
-- address). The approved fix matches a published shop when one of its available
-- products' search_vector (V25, GIN idx_products_search) matches the query.
--
-- That fix CANNOT work without this policy. `products` has carried exactly one
-- policy since V2, products_rls_policy FOR ALL USING (tenant_id =
-- current_tenant_id()), and no public-read policy in any migration. The public
-- menu (GET /shops/{slug}/products) works only because PublicStorefrontService
-- pins the SHOP's tenant before reading products; a cross-tenant search has no
-- single tenant to pin. Measured 2026-11-02 under a NOSUPERUSER NOBYPASSRLS role
-- with no tenant pinned: the products-aware search returned [] for a kitchen the
-- Testcontainers SUPERUSER (which bypasses FORCE RLS) found. A query-only fix
-- would have been green in every superuser test and dead in production, where
-- the application connects as jtoye_runtime (NOSUPERUSER NOBYPASSRLS).
--
-- WHAT AN ANONYMOUS CALLER CAN NOW SEE
-- ------------------------------------
-- Per ROW, exactly the rows GET /shops/{slug}/products already serves to anyone:
-- a product with available = true whose shop is published = true and belongs to
-- the product's own tenant. The shop predicate is itself evaluated under the
-- shops policies (shops_public_read admits published shops).
--
-- Per COLUMN it is WIDER than the menu, and that is stated, not hidden: RLS is
-- row-level, so a no-tenant SQL reader of such a row can read every column,
-- including ones the menu DTO never shows (sku, stock quantity, display order
-- and the rest). No such reader exists today: the public search reads only
-- whether a matching row exists (its id and tenant), never a product column, and
-- the audit (quick 261102-k7d evidence/audit.md) lists every no-tenant read of
-- products. A future no-tenant reader that SELECTs product columns must select
-- only what the menu already shows.
--
-- WHAT IT CANNOT SEE OR DO
-- ------------------------
--   * a product of an UNPUBLISHED shop (the EXISTS requires published = true);
--   * an UNAVAILABLE product (available = false), even on a published shop;
--   * a product with no shop (shop_id IS NULL never satisfies the EXISTS);
--   * a product whose shop_id names ANOTHER tenant's published shop:
--     products.shop_id is a plain foreign key (V20), so nothing in the schema
--     stops such a row (the FC-1 class, refused only by the application). The
--     menu pins the shop's tenant and never shows it, so the EXISTS requires
--     s.tenant_id = products.tenant_id, the predicate the search query uses;
--   * any WRITE: the policy is FOR SELECT only, so INSERT is refused (42501,
--     new row violates row-level security policy) and UPDATE/DELETE match zero
--     rows; products_rls_policy stays the only write path;
--   * anything at all once a tenant IS pinned: the branch is guarded by
--     current_tenant_id() IS NULL (the V87 shop_fulfilment_slots_public_read
--     shape), so an authenticated tenant's unpredicated SELECT never starts
--     seeing another tenant's published products.
--
-- The helper is the safe current_tenant_id() (V39/V51: a malformed GUC filters,
-- it never raises 22P02) and never the raw ::uuid cast.
--
-- THE AUDIT THAT CAME WITH IT
-- ---------------------------
-- Widening a SELECT policy changes what every NO-TENANT read of `products`
-- returns. Every ProductRepository method and every call site that can run
-- with no tenant was listed (quick task 261102 "shop search matches dishes",
-- evidence/audit.md). One class of finding: ProductService's GROUP_ADMIN list
-- and search branches ran an UNPREDICATED read and relied on RLS alone, so a
-- realm admin or declared system caller with no tenant claim would have listed
-- every tenant's published products. Those branches now return an empty page
-- when no tenant is pinned, which is what RLS returned before this policy.
--
-- No column, no table, no data change, no backfill, so no tenant loop and no
-- RLS-backfill trap. Sequential after V88, so spring.flyway.out-of-order=true
-- does not interact. Proven under NOSUPERUSER NOBYPASSRLS by
-- ProductsPublicReadRlsIntegrationTest and ShopSearchMatchesDishesIntegrationTest.
-- =============================================================================

DROP POLICY IF EXISTS products_public_read ON products;

CREATE POLICY products_public_read ON products
    FOR SELECT
    USING (
        current_tenant_id() IS NULL
        AND available = true
        AND EXISTS (
            SELECT 1 FROM shops s
            WHERE s.id = products.shop_id
              AND s.tenant_id = products.tenant_id
              AND s.published = true
        )
    );
