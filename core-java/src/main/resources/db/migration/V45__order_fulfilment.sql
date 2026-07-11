-- V45: Order fulfilment + delivery address (Phase 19 — UIX-03 / UIX-04 backend).
--
-- Two audit blockers land together because both live on the guest order path
-- (PublicStorefrontService.createGuestOrder):
--
--   UIX-04 (schema): orders gains a fulfilment_type (DELIVERY | COLLECTION) plus
--          UK delivery-address columns so the storefront can persist how + where
--          an order is fulfilled and the dashboard order-detail view can render it.
--   UIX-03 (data): historical guest OrderItem rows persisted the entity default
--          product_name = 'Unknown Product' because the guest path never snapshotted
--          product.title. The one-line code fix lands in PublicStorefrontService;
--          this migration backfills the rows already written wrong.
--
-- Shape copied EXACTLY from V40__vat_ledger_correctness.sql (base VARCHAR+CHECK enum
-- + orders_aud mirror) and V6 (status CHECK convention). Forward-only; every ALTER
-- is additive and every UPDATE is a safe no-op on a fresh (empty) schema.
--
-- ============================================================================
-- ENVERS MIRROR — read before touching orders_aud (V38 was a dedicated fix for
-- exactly this drift). Order is @Audited, so EVERY new column on orders MUST have
-- a matching NULLABLE column on orders_aud with NO DEFAULT and NO CHECK. Omitting
-- the mirror makes the NEXT audited order write fail at the Envers INSERT with
-- "column ... does not exist" — a latent HTTP 500 that RlsContractTest (which walks
-- table-level RLS, not columns) cannot catch. OrderFulfilmentAuditIntegrationTest
-- performs a real audited write after V45 to prove there is no drift.
-- ============================================================================
--
-- RLS: none needed — orders + orders_aud already ENABLE + FORCE ROW LEVEL SECURITY.
-- V44 is deliberately NOT used here — it stays reserved for Issue #96.

-- ----------------------------------------------------------------------------
-- 1. Base table — fulfilment_type (VARCHAR+CHECK enum, mirrors V40:49-51 / V6:18)
--    + nullable UK delivery-address columns.
-- ----------------------------------------------------------------------------
-- NOT NULL DEFAULT 'DELIVERY' backfills existing order rows via the column default
-- (no separate UPDATE, and crucially no Envers revision minted, so existing orders
-- keep their current audit history). Address columns are nullable: a COLLECTION
-- order carries no delivery address.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS fulfilment_type VARCHAR(20) NOT NULL DEFAULT 'DELIVERY'
        CHECK (fulfilment_type IN ('DELIVERY','COLLECTION')),
    ADD COLUMN IF NOT EXISTS address_line1    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_line2    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_city     VARCHAR(120),
    ADD COLUMN IF NOT EXISTS address_postcode VARCHAR(12);

-- ----------------------------------------------------------------------------
-- 2. CRITICAL Envers mirror (V40:53-56 / V41:39-41 / V43:104-126 convention):
--    history columns are ALWAYS nullable, NEVER carry DEFAULT or CHECK.
-- ----------------------------------------------------------------------------
ALTER TABLE orders_aud
    ADD COLUMN IF NOT EXISTS fulfilment_type  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS address_line1    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_line2    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_city     VARCHAR(120),
    ADD COLUMN IF NOT EXISTS address_postcode VARCHAR(12);

-- ----------------------------------------------------------------------------
-- 3. UIX-03 backfill — snapshot real product titles onto guest order_items rows
--    that persisted the 'Unknown Product' entity default. Mirrors V40:79-84's
--    "UPDATE ... FROM ... safe on zero rows" style. A raw UPDATE does NOT mint an
--    Envers revision (acceptable per the V30 precedent) — the historical audit
--    rows keep the value that was actually written at order time.
-- ----------------------------------------------------------------------------
UPDATE order_items oi
   SET product_name = p.title
  FROM products p
 WHERE oi.product_id = p.id
   AND oi.product_name = 'Unknown Product';

-- ----------------------------------------------------------------------------
-- 4. AUDIT NOTE — runtime marker (no dedicated audit/log table exists in schema).
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    RAISE NOTICE 'V45 order fulfilment applied: orders + orders_aud gained '
        'fulfilment_type (DELIVERY|COLLECTION) + address_line1/2/city/postcode '
        '(aud columns nullable, no CHECK); order_items.product_name backfilled '
        'from products.title where it was the ''Unknown Product'' default.';
END $$;
