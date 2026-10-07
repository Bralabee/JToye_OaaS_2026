-- V73: Phase 31.1 / plan 31.1-13 (#812; decision D-15) — a customer's allergy or dietary request is
-- its own field on the order, and the shop's acknowledgement of it is recorded: WHO read it and WHEN.
--
-- WHY THIS EXISTS. The persona run sent "My child is allergic to peanuts and sesame - please
-- confirm" in the delivery NOTES. The order went all the way to COMPLETED, and nothing anywhere
-- recorded that anybody in the shop had read it. A request that can be the difference between a
-- safe meal and an ambulance was indistinguishable from "ring the bell".
--
-- ============================================================================================
-- allergy_note         — the customer's own free text, sent for the NAMED SHOP to prepare THIS
--                        order. Optional. Trimmed by the server; a blank note is stored as NULL.
--                        Capped at 500 characters, matching @Size(max = 500) on GuestOrderRequest,
--                        so a value the API refuses cannot reach the table by another path either.
--                        It may be special-category (health) data: the vendor is the controller of
--                        its content (docs/legal/article-9-allergen-basis.md, Finding 1 and the
--                        2026-10 extension), the platform derives nothing from it, and it is nulled
--                        by an Article 17 erasure on this table AND on orders_aud.
--
-- allergy_note_ack_at  — when someone in the shop confirmed they had read the note.
-- allergy_note_ack_by  — who: the authenticated principal name of the shop user who confirmed it.
--                        Staff identity, not subject data: an erasure keeps both ack columns, so
--                        the record that the request was read survives the customer's erasure.
-- ============================================================================================
--
-- NULL MEANS NONE. All three columns are nullable and NOTHING IS BACKFILLED: every order placed
-- before V73 has no allergy note (any such request is still inside its delivery notes, and it is
-- not moved: moving free text between columns would rewrite what the customer submitted), and no
-- acknowledgement was ever recorded for it. There is no DEFAULT, and no later migration may add
-- one: a default acknowledgement would claim that somebody read something nobody read.
--
-- Because there is no backfill there is NO UPDATE and NO tenant loop, so the RLS-backfill trap
-- (V25 -> V44 -> V57: a bare UPDATE against a FORCE-RLS table matches ZERO rows under the migration
-- role and reports success) does not apply. ADD COLUMN with no default is metadata-only in
-- Postgres 11+. The one CHECK constraint is validated against existing rows, all NULL, which pass.
--
-- CONSTRAINT (orders only). ck_orders_allergy_note_ack_pair: an acknowledgement is WHO and WHEN
-- together, never one without the other. The application writes both in one statement; the
-- constraint makes a half-written acknowledgement impossible by any other path. It deliberately
-- does NOT say "an acknowledgement implies a note": an Article 17 erasure nulls the note and keeps
-- who/when, so that state is legitimate. "Only an order with a note can be acknowledged" is
-- therefore enforced where it is decided, in OrderService.acknowledgeAllergyNote (a typed 400).
--
-- ENVERS. Order is @Audited, so every new column also lands on orders_aud in THIS migration, or
-- the next audited order write throws at RUNTIME (the V38 repair after V30). The mirrors are
-- nullable with no DEFAULT and no CHECK, the V40/V41/V43/V45/V60/V63/V66/V69 convention: an _aud
-- row records a revision, not a live constraint. OrderRepository.scrubOrdersAudit and
-- scrubOrdersAuditByEmail null orders_aud.allergy_note on erasure.
--
-- RLS. No new table: orders and orders_aud keep their ENABLE + FORCE ROW LEVEL SECURITY posture
-- through the safe current_tenant_id() helper. No policy is created, altered or dropped, so
-- RlsContractTest's schema walk is unaffected. The V42 orders_aud UPDATE policy already admits the
-- erasure scrub.
--
-- NO INDEX: none of the three columns is ever a predicate. NO POSTGRESQL EXTENSION is created.
--
-- VERSION NUMBERING. V68..V75 are reserved for Phase 31.1 (evidence/31.1-01-baseline.md section 5);
-- V73 is this plan's.

-- ============================================================
-- 1. orders — the note and its acknowledgement (metadata-only, nullable, no backfill, no default)
-- ============================================================
ALTER TABLE orders ADD COLUMN IF NOT EXISTS allergy_note        VARCHAR(500);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS allergy_note_ack_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS allergy_note_ack_by VARCHAR(255);

ALTER TABLE orders ADD CONSTRAINT ck_orders_allergy_note_ack_pair
    CHECK ((allergy_note_ack_at IS NULL) = (allergy_note_ack_by IS NULL));

COMMENT ON COLUMN orders.allergy_note IS
    'The customer''s own allergy or dietary note, sent for the named shop to prepare this order (D-15). Trimmed; NULL means none was given (or it was erased). Separate from notes (delivery). Possibly special-category data: the vendor is the controller of its content; never derived from, matched on or analysed; nulled by Article 17 erasure here and on orders_aud.';

COMMENT ON COLUMN orders.allergy_note_ack_at IS
    'When someone in the shop confirmed they had read allergy_note. NULL means not acknowledged. First write wins: never overwritten. Kept by erasure (staff record, not subject data).';

COMMENT ON COLUMN orders.allergy_note_ack_by IS
    'Who in the shop confirmed reading allergy_note: the authenticated principal name. NULL means not acknowledged. Written together with allergy_note_ack_at. Kept by erasure.';

-- ============================================================
-- 2. orders_aud — Envers mirrors, in the SAME migration. Nullable, no default, no check.
-- ============================================================
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS allergy_note        VARCHAR(500);
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS allergy_note_ack_at TIMESTAMPTZ;
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS allergy_note_ack_by VARCHAR(255);

-- ============================================================
-- 3. AUDIT NOTE — runtime marker (no dedicated audit/log table exists in schema).
-- ============================================================
DO $$
BEGIN
    RAISE NOTICE 'V73 order allergy note applied: orders + orders_aud gained nullable allergy_note, '
        'allergy_note_ack_at and allergy_note_ack_by; no row was backfilled and no default was set.';
END $$;
