-- V63: Phase 31 / plan 31-10 (LGL-03) — the order line records the allergen picture that was
-- true WHEN THE ORDER WAS PLACED.
--
-- WHY THIS EXISTS. order_items carried no allergen data at all, so the checkout panel and the
-- kitchen display could only be fed by joining back to products.allergen_mask at read time. Under
-- a live join, a vendor who edits a product's allergen mask AFTER an order is placed silently
-- changes what the customer is recorded as having acknowledged and what the kitchen ticket shows:
-- the customer acknowledged set A, the kitchen sees set B, and no record of A exists anywhere. On
-- the one surface in this product that can physically injure someone, that is the wrong trade.
-- order_items already snapshots product_name (V30) for exactly this class of reason — the
-- PublicStorefrontService call site records it as a UIX-03 root-cause fix so the kitchen display
-- could never show a stale default. The allergen mask is snapshotted beside it, for the same
-- reason and at the same moment.
--
-- ============================================================================================
-- allergen_mask       — the vendor's DECLARED mask for this line's product, copied at write time.
--                       The same 14-bit UK FSA layout as products.allergen_mask
--                       (uk.jtoye.core.product.AllergenCatalog, bits 0..13). This is the legally
--                       operative statement.
--
-- allergen_flag_mask  — the ADVISORY reconciliation result for this line: the bits that this
--                       product's EMPHASISED ingredients text names but its declared mask omits
--                       (OrderAllergenAggregator, plan 31-04). Structurally separate from
--                       allergen_mask and never OR-ed into it: a text heuristic must not rewrite
--                       a vendor's declaration, because that would make the platform the author
--                       of an allergen statement it cannot stand behind AND would mask the
--                       vendor's underlying data error instead of surfacing it.
-- ============================================================================================
--
-- WHY TWO INTEGERS AND NOT JSONB / TEXT[]. The plan left the flag shape open (JSONB or a text
-- array) on the ground that the flags are read as a list and never queried by predicate, so the
-- simpler shape wins. Per LINE, a reconciliation flag is exactly "an allergen bit" — the product
-- is the row itself (order_items.product_name / product_id), so a per-row bitmask preserves both
-- halves of "which product, which allergen" with no encoding at all. The human-readable allergen
-- NAME is deliberately NOT stored: it is resolved from AllergenCatalog, which is held to the
-- TypeScript table in frontend/types/api.ts by a cross-language parity test. Persisting the name
-- as prose would create a second, ungated copy of that table, one row per order line, frozen at
-- write time — the exact drift the parity gate exists to prevent. The BIT is the fact; the name
-- is a label.
--
-- NO BACKFILL, DELIBERATELY. Both columns are nullable and historic rows stay NULL, which reads
-- as "not recorded". Inventing a mask for a past order from today's product rows would fabricate
-- a record of what a past customer was shown — this plan's own defect pointed backwards, and a
-- worse one, because a fabricated record is indistinguishable from a real one. NULL ("not
-- recorded") and 0 ("the vendor declared none of the 14 regulated allergens") are DIFFERENT
-- statements and the checkout copy for the second is legally specific, so nothing downstream may
-- collapse them. Do not add a DEFAULT 0 to these columns in a later migration: that would destroy
-- the distinction silently and retroactively claim every historic order was allergen-free.
--
-- Because there is no backfill there is deliberately NO UPDATE and NO tenant loop in this file,
-- so the recurring RLS-backfill trap (V25 -> V44 -> V57: a bare UPDATE against a FORCE-RLS table
-- matches ZERO rows under the migration role and reports success) genuinely does not apply here
-- rather than merely going unmentioned. ADD COLUMN with no default is metadata-only in Postgres
-- 11+ — no table rewrite, no per-row write.
--
-- ENVERS. OrderItem is @Audited (OrderItem.java: @Entity @Table(name = "order_items") @Audited),
-- so both columns MUST also land on order_items_aud in THIS migration or the next audited write
-- throws at RUNTIME, not at build time — the same failure V38 had to repair after V30 added
-- product_name to the base table only. Mirror columns are nullable, matching the V38/V60 mirror
-- convention: an _aud row records a revision, not a live constraint.
--
-- RLS. No new table, so the posture is inherited unchanged: order_items and order_items_aud are
-- already ENABLE + FORCE ROW LEVEL SECURITY through the safe current_tenant_id() helper (V15).
-- No policy is created, altered or dropped here, so RlsContractTest's schema walk is unaffected.
--
-- NO INDEX. These columns are read only as part of an already-keyed order-line fetch
-- (WHERE order_id = ...), never as a predicate. An index nothing selects on is cost without
-- benefit.
--
-- NO POSTGRESQL EXTENSION IS CREATED. (The forbidden statement is not spelled out here on
-- purpose: this plan's own verification greps the file for that exact literal and expects zero,
-- so a comment naming it would fire the rule on its own definition. The invariant across the
-- whole migration directory is enforced by scripts/check-no-create-extension.sh, wired into
-- ci-cd.yaml.)
--
-- HEAD is V62 (dsar_request, plan 31-05), so V63 is the next strict version and
-- spring.flyway.out-of-order — required project-wide for the V44/V53 reserved slots — does not
-- interact with this file.

-- ============================================================
-- 1. order_items — the snapshot columns (metadata-only, nullable, no backfill)
-- ============================================================
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS allergen_mask      INT;
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS allergen_flag_mask INT;

COMMENT ON COLUMN order_items.allergen_mask IS 'Write-time snapshot of the product DECLARED UK FSA 14-bit allergen mask. NULL means not recorded (row predates V63); 0 means the vendor declared none of the 14. Never widened by the reconciliation heuristic.';

COMMENT ON COLUMN order_items.allergen_flag_mask IS 'Write-time snapshot of the ADVISORY reconciliation result: bits the emphasised ingredients text names but the declared mask omits. Never merged into allergen_mask. NULL means not recorded; 0 means nothing was flagged.';

-- ============================================================
-- 2. order_items_aud — Envers mirror, in the SAME migration. Nullable (V38/V60 convention).
-- ============================================================
ALTER TABLE order_items_aud ADD COLUMN IF NOT EXISTS allergen_mask      INT;
ALTER TABLE order_items_aud ADD COLUMN IF NOT EXISTS allergen_flag_mask INT;
