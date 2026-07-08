-- V41: PPDS / Natasha's Law label compliance (Issue #82 [P0-6]) — schema half.
--
-- Companion to a CODE change that rewrites the PPDS allergen label to the
-- FSA-compliant format: allergens emphasised INLINE within the ingredients list,
-- a computed durability date ('Use by' / 'Best before'), and the food business
-- name + address. See ProductLabelService + IngredientMarkupParser.
--
-- Three new nullable product columns support that render:
--   * allergen_spans   — a persisted cache of the parser's emphasis spans
--                        (offsets into the parsed plainText). The label renderer
--                        RE-PARSES ingredients_text at render time (authoritative);
--                        this column enables future consumers (e.g. a storefront
--                        allergen badge) without re-parsing.
--   * shelf_life_days  — per-product shelf life; the durability date is computed
--                        at generation time as generationDate + shelf_life_days.
--   * durability_type  — 'USE_BY' or 'BEST_BEFORE' (which durability wording to print).
--
-- All statements are additive, nullable, forward-only, and safe no-ops on a
-- fresh/zero-row schema. Existing products stay valid (all three are NULL until a
-- vendor supplies them); the label deliberately 422s for products missing this
-- required PPDS data rather than emitting a non-compliant label.

-- ----------------------------------------------------------------------------
-- 1. products — new PPDS columns (all nullable, no backfill)
-- ----------------------------------------------------------------------------
ALTER TABLE products ADD COLUMN IF NOT EXISTS allergen_spans jsonb;
ALTER TABLE products ADD COLUMN IF NOT EXISTS shelf_life_days integer;
-- A NULL value satisfies the CHECK in Postgres (matching V40's inline-CHECK style),
-- so existing rows with NULL durability_type remain valid.
ALTER TABLE products
    ADD COLUMN IF NOT EXISTS durability_type varchar(20)
    CHECK (durability_type IN ('USE_BY', 'BEST_BEFORE'));

-- ----------------------------------------------------------------------------
-- 2. products_aud — Envers audit mirror (ALWAYS nullable, no default/CHECK)
-- ----------------------------------------------------------------------------
-- Without these mirrors the next audited products write fails at the audit
-- INSERT with 'column does not exist' (audit-column convention per V4/V38/V40).
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS allergen_spans jsonb;
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS shelf_life_days integer;
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS durability_type varchar(20);

-- ----------------------------------------------------------------------------
-- 3. Runtime marker (no dedicated audit/log table exists in this schema)
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    RAISE NOTICE 'V41 PPDS label compliance applied: products.allergen_spans (jsonb), '
        'products.shelf_life_days (integer), products.durability_type (varchar CHECK '
        'USE_BY/BEST_BEFORE) added (all nullable), with products_aud audit mirrors. '
        'PPDS labels now render inline allergens + a durability date + business identity, '
        'and 422 when this required data is absent.';
END $$;
