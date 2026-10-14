-- V83: Phase 37-48 (RWO-023 / UXT-023) — a shop says which fulfilment types it offers, and every order writer
-- refuses one it does not.
--
-- Why. A vendor that only takes collection had no way to say so: every shop took DELIVERY and COLLECTION on
-- every writer (the storefront checkout, and the vendor dashboard / REST API / MCP create_order path), so a
-- collection-only kitchen was sent delivery orders it could not fulfil. The persona run asked for exactly
-- this ("collection only") and found nothing in the shop form or the API that modelled it.
--
-- 1. shops.offers_delivery and shops.offers_collection BOOLEAN NOT NULL DEFAULT true. The default records NO
--    new statement: today every shop takes both, on both writers, so true/true is what is already true of
--    every existing row (the V63/V66 rule forbids a default that would fabricate a vendor statement; this one
--    restates the behaviour each shop already has). Adding a NOT NULL column with a constant DEFAULT fills
--    existing rows in the catalog with no UPDATE, so the FORCE-RLS backfill trap (V25 -> V44 -> V57 -> V78 ->
--    V79 -> V80) does not apply: there is no UPDATE and no tenant loop.
-- 2. ck_shops_offers_some CHECK (offers_delivery OR offers_collection): a shop that offers neither could take
--    no order at all and would read as open while refusing every checkout. The API refuses it first with a
--    typed 400 (CreateShopRequest + ShopService); this constraint is the last line if that guard regresses.
-- 3. The shops_aud mirrors of both, in this SAME migration: Shop is @Audited, and a missing _aud column throws
--    at runtime on the next audited write (V30/V38). The _aud copies are nullable and unconstrained (house
--    shape) and are NOT backfilled: NULL there means "not recorded".
--
-- Enforcement is FulfilmentPolicy.requireOffered(shop, type), called by both writers right after the type is
-- resolved: 422 https://jtoye.uk/errors/fulfilment-not-offered naming the offered type. PublicShopDto exposes
-- both flags so the storefront can render only what the shop offers.
--
-- No new table, no RLS policy change. V83 was reserved for 37-48 and applies out of order after V84
-- (spring.flyway.out-of-order=true).

ALTER TABLE shops ADD COLUMN IF NOT EXISTS offers_delivery   BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE shops ADD COLUMN IF NOT EXISTS offers_collection BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE shops ADD CONSTRAINT ck_shops_offers_some
    CHECK (offers_delivery OR offers_collection);

COMMENT ON COLUMN shops.offers_delivery IS
    'The shop takes DELIVERY orders (37-48, UXT-023). Default true: every shop took delivery before V83. Enforced on every order writer by FulfilmentPolicy.requireOffered (422 fulfilment-not-offered).';

COMMENT ON COLUMN shops.offers_collection IS
    'The shop takes COLLECTION orders (37-48, UXT-023). Default true: every shop took collection before V83. At least one of offers_delivery / offers_collection is true (ck_shops_offers_some).';

ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS offers_delivery   BOOLEAN;
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS offers_collection BOOLEAN;

DO $$
BEGIN
    RAISE NOTICE 'V83 shop fulfilment offered applied: shops gained offers_delivery and offers_collection '
        '(NOT NULL DEFAULT true, every existing shop keeps taking both) with ck_shops_offers_some; shops_aud '
        'gained nullable mirrors, not backfilled.';
END $$;
