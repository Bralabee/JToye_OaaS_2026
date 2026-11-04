-- V90: #832 (UXT-063; ADE-04, P2-CHA-19) - a vendor can delete a product that past orders still name.
--
-- WHY THIS EXISTS. DELETE /products/{id} hard-deleted the row, so a product on ANY order (even one
-- CANCELLED weeks ago) hit fk_order_items_product and was refused with a 409. The only way to tidy
-- the catalogue was available=false, which leaves the dead item in the vendor's list for good. A
-- product with a photo hit product_media_product_id_fkey the same way and was reported with the
-- same false "still referenced by an existing order" message.
--
-- THE DESIGN. The photo case is fixed in the application (the delete removes the product_media
-- links and ref-count-releases their assets, V53 rules). The ordered case needs the row to stay,
-- because order_items keeps its product_id, so the product is ARCHIVED:
--   products.archived_at - when the vendor deleted it. NULL = a live catalogue product.
-- An archived product is made unavailable and its photos released by the service; Product carries
-- @SQLRestriction("archived_at IS NULL"), so every entity query (lists, by-id, storefront, the
-- onboarding gates, stock) stops seeing it, and the three native product searches add the same
-- predicate by hand. The order history is untouched: order_items.product_name was snapshotted at
-- write time (V30), so the order still shows what the customer bought.
--
-- SKU. idx_products_tenant_sku (V3) was UNIQUE (tenant_id, sku) over every row, so an archived
-- product would keep its SKU for ever and the vendor could never reuse it. It becomes a PARTIAL
-- unique index over live rows only (same name, so GlobalExceptionHandler's duplicate-SKU mapping
-- still recognises it). Live rows were unique before and stay unique, so the rebuild cannot fail.
--
-- ENVERS. Product is @Audited, so the column also lands on products_aud in THIS migration, or the
-- next audited product write throws at RUNTIME (the V38 repair after V30). Nullable, no DEFAULT,
-- no CHECK: an _aud row records a revision, not a live constraint.
--
-- RLS / BACKFILL. No UPDATE statement, so no tenant loop and no FORCE-RLS bare-UPDATE trap (V25 ->
-- V44 -> V57 -> V78). No table is created and no policy is created, altered or dropped, so
-- RlsContractTest's schema walk is unaffected. products_public_read (V89) already requires
-- available = true, and an archived product is unavailable, so it adds nothing to the anonymous
-- read. NO POSTGRESQL EXTENSION is created. NO DATA CHANGE: every existing product stays live.

ALTER TABLE products     ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

DROP INDEX IF EXISTS idx_products_tenant_sku;
CREATE UNIQUE INDEX idx_products_tenant_sku ON products (tenant_id, sku) WHERE archived_at IS NULL;

-- ============================================================================================
-- products.shop_id: ON DELETE SET NULL -> ON DELETE RESTRICT (#833, round-1 review finding 3)
-- ============================================================================================
-- V20 made the FK ON DELETE SET NULL, so deleting a shop silently orphaned its products (listed,
-- unreachable by id, moved to "All Shops" by the next edit). ShopService.deleteShop now counts the live
-- products and refuses with a typed 409, but count-then-delete is not atomic: a product created or moved
-- into the shop in between would still be orphaned. RESTRICT makes the database enforce the rule (the
-- DELETE fails 23503 on products_shop_id_fkey and the service maps that to the same 409), and it is a
-- plain referential check, so it holds for every writer.
-- ARCHIVED products (archived_at above) are hidden and kept only for order history; the service detaches
-- them (shop_id = NULL) in the same transaction before the delete, so they never block it.
-- No data change: every existing shop_id already references a shop (the old FK held), so re-adding the
-- constraint cannot fail, and there is NO UPDATE here (no tenant loop, no FORCE-RLS bare-UPDATE trap).
ALTER TABLE products DROP CONSTRAINT IF EXISTS products_shop_id_fkey;
ALTER TABLE products
    ADD CONSTRAINT products_shop_id_fkey FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT;
