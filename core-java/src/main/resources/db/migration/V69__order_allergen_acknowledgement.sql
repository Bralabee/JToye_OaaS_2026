-- V69: Phase 31.1 / plan 31.1-03 (#784 P0, #785 P0; decisions D-05, D-06, D-07) — a storefront
-- order records WHICH allergen set the customer acknowledged, WHEN, and through WHICH channel the
-- order was placed.
--
-- WHY THIS EXISTS. The checkout's allergen checkbox gated the browser only. The guest-order POST
-- carried no acknowledgement, so a direct API call skipped it entirely, and nothing on the order
-- said what the customer had been shown. Worse, a vendor edit landing between render and submit
-- was recorded as if the customer had seen it: the persona run acknowledged Gluten/Fish/Peanuts,
-- and the order's V63 line snapshot recorded Gluten/Eggs/Fish/Peanuts/Milk. The acknowledgement
-- now travels with the request and the SERVER refuses a missing one (422) or a stale one (409,
-- carrying the current set). This migration holds what an ACCEPTED order recorded.
--
-- ============================================================================================
-- allergen_ack_mask — the 14-bit allergen set the customer ACKNOWLEDGED, in the same UK FSA bit
--                     layout as products.allergen_mask and V63 order_items.allergen_mask
--                     (uk.jtoye.core.product.AllergenCatalog, bits 0..13), so "acknowledged A"
--                     and "snapshot B" are directly comparable. The server only accepts an
--                     acknowledgement EQUAL to the union of the declared masks it reads for the
--                     basket in the same transaction that writes the V63 snapshot, so on every
--                     row this migration's code writes, allergen_ack_mask = OR(order_items
--                     .allergen_mask) by construction.
--
-- allergen_ack_at   — when the server accepted that acknowledgement.
--
-- placed_via        — STOREFRONT (the public guest-order endpoint, which requires the
--                     acknowledgement) or VENDOR (OrderService.createOrder: the dashboard, the
--                     REST API and the MCP create_order tool, which take none — D-07).
-- ============================================================================================
--
-- NULL MEANS NOT RECORDED. All three columns are nullable and NOTHING IS BACKFILLED. Every order
-- placed before V69 stays NULL, and so does the acknowledgement on every VENDOR order. 0 is a
-- DIFFERENT statement: the customer acknowledged a basket declaring none of the 14 regulated
-- allergens. Inventing a mask for a past order would fabricate a record of what a past customer
-- acknowledged, and a fabricated record is indistinguishable from a real one (the V63 and V66
-- rule). For the same reason there is NO DEFAULT, and no later migration may add one: a DEFAULT 0
-- would claim every pre-V69 customer acknowledged "no allergens", and a DEFAULT 'STOREFRONT' or
-- 'VENDOR' would guess a channel nobody recorded.
--
-- Because there is no backfill there is NO UPDATE and NO tenant loop here, so the recurring
-- RLS-backfill trap (V25 -> V44 -> V57: a bare UPDATE against a FORCE-RLS table matches ZERO rows
-- under the migration role and reports success) does not apply. ADD COLUMN with no default is
-- metadata-only in Postgres 11+. The two CHECK constraints are validated against existing rows,
-- which are all NULL in the new columns and therefore pass.
--
-- CONSTRAINTS (orders only). ck_orders_placed_via limits the channel to the two values the
-- application writes; ck_orders_allergen_ack_mask_range limits the mask to the 14 defined bits
-- (0..16383), matching the @Min(0) @Max(16383) on GuestOrderRequest so a value the API refuses
-- cannot reach the table by another path either. Both admit NULL.
--
-- ENVERS. Order is @Audited, so every new column also lands on orders_aud in THIS migration, or
-- the next audited order write throws at RUNTIME (the V38 repair after V30). The mirrors are
-- nullable with no DEFAULT and no CHECK, the V40/V41/V43/V45/V60/V63/V66 convention: an _aud row
-- records a revision, not a live constraint.
--
-- RLS. No new table: orders and orders_aud keep their ENABLE + FORCE ROW LEVEL SECURITY posture
-- through the safe current_tenant_id() helper. No policy is created, altered or dropped, so
-- RlsContractTest's schema walk is unaffected.
--
-- NO INDEX: none of the three columns is ever a predicate. NO POSTGRESQL EXTENSION is created
-- (the forbidden statement is not spelled out, so scripts/check-no-create-extension.sh cannot
-- fire on its own definition).
--
-- VERSION NUMBERING. V68..V75 are reserved for Phase 31.1 (evidence/31.1-01-baseline.md §5);
-- V68 is 31.1-02's, V69 is this plan's.

-- ============================================================
-- 1. orders — acknowledgement + channel (metadata-only, nullable, no backfill, no default)
-- ============================================================
ALTER TABLE orders ADD COLUMN IF NOT EXISTS allergen_ack_mask INT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS allergen_ack_at   TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS placed_via        VARCHAR(16);

ALTER TABLE orders ADD CONSTRAINT ck_orders_placed_via
    CHECK (placed_via IS NULL OR placed_via IN ('STOREFRONT', 'VENDOR'));

ALTER TABLE orders ADD CONSTRAINT ck_orders_allergen_ack_mask_range
    CHECK (allergen_ack_mask IS NULL OR allergen_ack_mask BETWEEN 0 AND 16383);

COMMENT ON COLUMN orders.allergen_ack_mask IS
    'The UK FSA 14-bit allergen set (AllergenCatalog bits 0..13) the customer acknowledged at checkout; equals OR(order_items.allergen_mask) by construction. NULL means not recorded (the order predates V69 or was placed by the vendor); 0 means the customer acknowledged a basket declaring none of the 14. Never defaulted, never backfilled.';

COMMENT ON COLUMN orders.allergen_ack_at IS
    'When the server accepted the allergen acknowledgement. NULL means not recorded (the order predates V69 or was placed by the vendor).';

COMMENT ON COLUMN orders.placed_via IS
    'Channel that placed the order: STOREFRONT (public guest checkout, acknowledgement required) or VENDOR (dashboard, REST API, MCP create_order; no acknowledgement). NULL means not recorded (the order predates V69).';

-- ============================================================
-- 2. orders_aud — Envers mirrors, in the SAME migration. Nullable, no default, no check.
-- ============================================================
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS allergen_ack_mask INT;
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS allergen_ack_at   TIMESTAMPTZ;
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS placed_via        VARCHAR(16);

-- ============================================================
-- 3. AUDIT NOTE — runtime marker (no dedicated audit/log table exists in schema).
-- ============================================================
DO $$
BEGIN
    RAISE NOTICE 'V69 order allergen acknowledgement applied: orders + orders_aud gained nullable '
        'allergen_ack_mask, allergen_ack_at and placed_via; no row was backfilled and no default '
        'was set, so NULL means not recorded.';
END $$;
