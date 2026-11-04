-- V94: #460 / UXT-030 - a shop says how far it delivers, and both order writers refuse a delivery beyond it.
--
-- Why. Checkout accepted a DELIVERY order to any UK postcode: a London kitchen was sent an order for Edinburgh
-- and the customer was charged a delivery fee for something nobody would drive 530 km to bring. Phase 33 gave
-- shops coordinates (V61 postcode_centroid, ShopCoordinateBackfill) and the discovery radius, but the order
-- writers never measured anything.
--
-- 1. shops.delivery_radius_km DOUBLE PRECISION, NULLABLE. NULL means "no limit", which is what every existing
--    shop does today, so NULL records no new statement and nothing is backfilled: there is no UPDATE, so the
--    FORCE-RLS migration-backfill trap (V25 -> V44 -> V57 -> V78 -> V79 -> V80) does not apply and no tenant
--    loop is needed. Deliberately NOT given a default: a default radius would turn on a refusal nobody asked for
--    in every existing kitchen on the day it deploys (the V63/V66 rule, applied to a behaviour).
-- 2. ck_shops_delivery_radius_km: NULL, or greater than 0 and at most 100. The API refuses a bad value first
--    with a typed 400 (CreateShopRequest); this is the last line if that guard regresses. 100 km is the platform
--    ceiling a single-kitchen delivery can plausibly mean; a larger area is not a radius, it is "no limit".
-- 3. The shops_aud mirror in this SAME migration: Shop is @Audited, and a missing _aud column throws at runtime
--    on the next audited write (V30/V38). The _aud copy is nullable and unconstrained (house shape) and is not
--    backfilled: NULL there means "not recorded".
--
-- Enforcement is FulfilmentPolicy.requireWithinDeliveryRadius, called by both writers (the storefront checkout
-- and OrderService.createOrder, which the dashboard, the REST API and MCP share) right after the delivery
-- address is required: 422 delivery-out-of-range, or 422 delivery-postcode-unrecognised when a radius is set and
-- the postcode is not in postcode_centroid. A shop with no radius, or with a radius but no stored coordinate,
-- behaves exactly as before.
--
-- No new table, no RLS policy change (the shops policies are column-agnostic).

ALTER TABLE shops ADD COLUMN IF NOT EXISTS delivery_radius_km DOUBLE PRECISION;

ALTER TABLE shops ADD CONSTRAINT ck_shops_delivery_radius_km
    CHECK (delivery_radius_km IS NULL OR (delivery_radius_km > 0 AND delivery_radius_km <= 100));

ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS delivery_radius_km DOUBLE PRECISION;

COMMENT ON COLUMN shops.delivery_radius_km IS
    'How far the shop delivers, in km from its stored coordinate (#460, UXT-030). NULL = no limit (every shop before V94). Enforced on every order writer by FulfilmentPolicy.requireWithinDeliveryRadius.';
