-- V74: Phase 31.1 / plan 31.1-14 (#861; decision D-16) — a product records its "may contain"
-- (cross-contact) risk as its OWN mask, separate from the allergens it declares.
--
-- WHY THIS EXISTS. The persona looked for a way to say "made in a kitchen that also handles
-- sesame" and there was none: the only allergen field on a product is allergen_mask, which is the
-- vendor's DECLARATION of what the food contains. Ticking sesame there would print it as an
-- ingredient on the PPDS label and in every storefront and checkout allergen panel, which is a
-- false statement about the recipe; leaving it unticked says nothing about the cross-contact risk
-- at all. Neither is acceptable on a food-safety label.
--
-- ============================================================================================
-- may_contain_mask — the allergens the vendor says the food MAY contain through cross-contact
--                    (shared equipment, shared kitchen), in the SAME 14-bit UK FSA layout as
--                    products.allergen_mask (uk.jtoye.core.product.AllergenCatalog, bits 0..13).
--                    It is printed on the PPDS label as its own "May contain: ..." line and shown
--                    on the storefront as its own line.
-- ============================================================================================
--
-- NEVER MERGED. This is the V63 rule for order_items.allergen_flag_mask, applied to the product:
-- may_contain_mask is NEVER OR-ed into allergen_mask, into the declared allergen names, into the
-- order-line snapshot (order_items.allergen_mask), or into the checkout acknowledgement
-- (orders.allergen_ack_mask). A precautionary statement is not a declaration, and folding one
-- into the other would make the platform the author of an ingredient claim the vendor never made.
-- A bit may legitimately be set in BOTH masks; it is stored as sent, and the label and storefront
-- then show that allergen only under the declaration, not repeated as "may contain".
--
-- NULL MEANS NOT RECORDED, AND IS DIFFERENT FROM 0. 0 means the vendor declared NO cross-contact
-- risk. NULL means the vendor has not said either way. NOTHING IS BACKFILLED and there is NO
-- DEFAULT, and no later migration may add one: inventing 0 for every existing product would
-- record, on every one of them, a statement no vendor made (the V63/V66 "a fabricated value is
-- indistinguishable from a real one" rule).
--
-- Because there is no backfill there is NO UPDATE and NO tenant loop, so the RLS-backfill trap
-- (V25 -> V44 -> V57: a bare UPDATE against a FORCE-RLS table matches ZERO rows under the
-- migration role and reports success) does not apply. ADD COLUMN with no default is
-- metadata-only in Postgres 11+.
--
-- CONSTRAINT (products only). ck_products_may_contain_mask_range: NULL, or a value within the 14
-- catalogue bits (0..16383), matching @Min(0) @Max(16383) on CreateProductRequest.mayContainMask,
-- so a value the API refuses cannot reach the table by another path either. It is validated
-- against existing rows, all NULL, which pass.
--
-- ENVERS. Product is @Audited, so the column also lands on products_aud in THIS migration, or the
-- next audited product write throws at RUNTIME (the V38 repair after V30). The mirror is nullable
-- with no DEFAULT and no CHECK, the V40/V41/V63/V66/V69/V73 convention: an _aud row records a
-- revision, not a live constraint.
--
-- RLS. No new table: products and products_aud keep their existing posture. No policy is
-- created, altered or dropped, so RlsContractTest's schema walk is unaffected.
--
-- NO INDEX: the column is never a predicate. NO POSTGRESQL EXTENSION is created.
--
-- VERSION NUMBERING. V68..V75 are reserved for Phase 31.1 (evidence/31.1-01-baseline.md section 5);
-- V74 is this plan's.

-- ============================================================
-- 1. products — the may-contain mask (metadata-only, nullable, no backfill, no default)
-- ============================================================
ALTER TABLE products ADD COLUMN IF NOT EXISTS may_contain_mask INT;

ALTER TABLE products ADD CONSTRAINT ck_products_may_contain_mask_range
    CHECK (may_contain_mask IS NULL OR may_contain_mask BETWEEN 0 AND 16383);

COMMENT ON COLUMN products.may_contain_mask IS
    'May contain (cross-contact) allergens, 14-bit UK FSA layout as allergen_mask (D-16). Separate from allergen_mask and NEVER merged into it, into the declared names, the order snapshot or the acknowledgement. NULL means not recorded; 0 means the vendor declared no cross-contact risk. No backfill, no default.';

-- ============================================================
-- 2. products_aud — Envers mirror, in the SAME migration. Nullable, no default, no check.
-- ============================================================
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS may_contain_mask INT;

-- ============================================================
-- 3. AUDIT NOTE — runtime marker (no dedicated audit/log table exists in schema).
-- ============================================================
DO $$
BEGIN
    RAISE NOTICE 'V74 product may-contain applied: products + products_aud gained nullable '
        'may_contain_mask (14-bit, CHECK 0..16383 on products); no row was backfilled and no '
        'default was set.';
END $$;
