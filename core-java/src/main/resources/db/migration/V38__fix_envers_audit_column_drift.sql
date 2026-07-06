-- V38: QA-council BE-02 — backfill missing Envers audit columns (schema drift).
--
-- Root cause: columns were added to the audited tables without the matching
-- columns on their Envers `*_aud` history tables, so every audited write fails
-- at the audit INSERT with 'column ... does not exist':
--   * shops        -> delivery_fee_pennies, featured_product_ids,
--                     free_delivery_threshold_pennies  (surfaces as HTTP 500 on
--                     shop create/update/delete)
--   * order_items  -> product_name (added V30) — a LATENT 500 on the next
--                     audited order_items write (all existing aud rows predate V30).
-- (search_vector and the @Version `version` column are legitimately NOT audited.)
--
-- Envers audit columns are historical snapshots, so they are always NULLABLE
-- regardless of the base table's constraints. Forward-only, idempotent.

ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS delivery_fee_pennies            BIGINT;
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS featured_product_ids            UUID[];
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS free_delivery_threshold_pennies BIGINT;

-- Base order_items.product_name is unbounded `character varying`; match it
-- (a length cap here could truncate and re-break the audit insert).
ALTER TABLE order_items_aud ADD COLUMN IF NOT EXISTS product_name VARCHAR;
