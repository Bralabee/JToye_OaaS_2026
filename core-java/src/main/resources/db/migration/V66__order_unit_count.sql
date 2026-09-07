-- V66: QA-council 20260902-134741 / COR-4 (adjudication A9) — the order records how many THINGS
-- the customer bought, not just how many lines the basket had.
--
-- WHY THIS EXISTS. `orders.item_count` means LINES: Order.calculateTotal() sets it to
-- items.size(). The browser's basket means UNITS: cart-provider.tsx reduces over quantity. Both
-- render the identical English string, "{n} item(s)". So a customer who buys 6 Zobos is shown
-- "6 items" on the basket, on the checkout header and in the cart drawer, then "1 item" on the
-- tracking page, on the per-shop order page and in My Orders — for the same order, minutes apart.
-- Measured on the dev runtime: 24 of 60 orders (40%) have SUM(quantity) <> COUNT(*), so this is
-- a live defect on two fifths of all orders, not a theoretical one.
--
-- Neither definition was ever CHOSEN over the other. V21's own header records that item_count was
-- added to fix a "'0 items' bug on tracking pages"; COUNT(*) was the expedient shape of a
-- non-zero number, four days after the client had already shipped the units reduce. The two have
-- never been reconciled in any commit message or plan.
--
-- ============================================================================================
-- WHY A NEW COLUMN RATHER THAN REDEFINING item_count.
--
-- Changing Order.calculateTotal() to sum quantities would rewrite the MEANING of a persisted
-- column for every existing row with NOTHING to distinguish migrated rows from unmigrated ones —
-- the exact "a fabricated value is indistinguishable from a real one" failure the V63 allergen
-- decision was written to avoid. It would also silently change three public contracts (OrderDto,
-- PublicOrderStatus, GuestOrderConfirmation) and the MCP read_orders payload with ZERO OpenAPI
-- diff, so `check-openapi-snapshot-fresh.sh` would stay green across a semantic break — the
-- "structural green over a changed meaning" shape this council exists to catch. And it would red
-- the shared money-conservation invariant I5 (item_count == COUNT(order_items)) on all 60 rows.
--
-- `item_count` is therefore left ENTIRELY untouched. I5 keeps its meaning, every existing
-- contract keeps its meaning, and `unit_count` is added beside it. Two columns, two facts.
-- ============================================================================================
--
-- NO BACKFILL, DELIBERATELY — the V63 shape, for the V63 reason. The column is nullable and
-- historic rows stay NULL, which reads as "not recorded". A backfill from
-- SUM(order_items.quantity) is arithmetically possible, but it would write a number into a
-- customer-visible historic figure that nobody showed that customer, and a fabricated record is
-- indistinguishable from a real one. NULL ("not recorded") and 0 ("an order with no units") are
-- DIFFERENT statements and nothing downstream may collapse them: the customer surfaces render
-- the count only when it is present, and render the price alone when it is not, rather than
-- inventing one. Do not add a DEFAULT 0 in a later migration — that would destroy the
-- distinction silently and retroactively.
--
-- Because there is no backfill there is deliberately NO UPDATE and NO tenant loop here, so the
-- recurring RLS-backfill trap (V25 -> V44 -> V57: a bare UPDATE against a FORCE-RLS table matches
-- ZERO rows under the migration role and reports success) genuinely does not apply, rather than
-- merely going unmentioned. ADD COLUMN with no default is metadata-only in Postgres 11+.
--
-- ENVERS. Order is @Audited (Order.java: @Entity @Table(name = "orders") @Audited), so the new
-- column MUST also land on orders_aud in THIS migration or the next audited order write throws at
-- RUNTIME, not at build time — the failure V38 had to repair after V30, and the reason V45 states
-- the same rule in its own header. The mirror column is nullable with NO DEFAULT and NO CHECK,
-- matching the V40/V41/V43/V45/V60/V63 mirror convention: an _aud row records a revision, not a
-- live constraint.
--
-- RLS. No new table, so the posture is inherited unchanged: orders and orders_aud are already
-- ENABLE + FORCE ROW LEVEL SECURITY through the safe current_tenant_id() helper. No policy is
-- created, altered or dropped here, so RlsContractTest's schema walk is unaffected.
--
-- NO INDEX. unit_count is read as part of an already-keyed order fetch and is never a predicate.
-- An index nothing selects on is cost without benefit.
--
-- NO POSTGRESQL EXTENSION IS CREATED. (The forbidden statement is not spelled out here on
-- purpose — scripts/check-no-create-extension.sh greps the migration directory for that exact
-- literal, and a comment naming it would fire the rule on its own definition.)
--
-- VERSION NUMBERING. Head at authoring time was V64. V65 is claimed by the same council run's
-- SEC-6 (_aud insert policies); this file is V66. spring.flyway.out-of-order=true is set
-- project-wide for the historical V44/V53 reserved slots — this file does not rely on it.

-- ============================================================
-- 1. orders — the unit count (metadata-only, nullable, no backfill)
-- ============================================================
ALTER TABLE orders ADD COLUMN IF NOT EXISTS unit_count INT;

COMMENT ON COLUMN orders.unit_count IS
    'Number of UNITS on the order: SUM(order_items.quantity). Distinct from item_count, which is the number of LINES (COUNT(order_items)) and is deliberately unchanged. NULL means not recorded (the row predates V66) and must NEVER be read as 0 or replaced by item_count.';

-- ============================================================
-- 2. orders_aud — Envers mirror, in the SAME migration. Nullable, no default, no check.
-- ============================================================
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS unit_count INT;

-- ============================================================
-- 3. AUDIT NOTE — runtime marker (no dedicated audit/log table exists in schema).
-- ============================================================
DO $$
BEGIN
    RAISE NOTICE 'V66 order unit_count applied: orders + orders_aud gained nullable unit_count '
        '(units = SUM(order_items.quantity)); item_count (lines) is UNCHANGED and no row was '
        'backfilled, so NULL means not recorded.';
END $$;
