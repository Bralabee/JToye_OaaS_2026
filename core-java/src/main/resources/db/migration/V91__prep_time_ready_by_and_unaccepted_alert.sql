-- V91: persona close-out, owner rulings 2026-11-04 (#825 ready-by estimate, #821 unaccepted-order alert).
--
-- 1. #825 -- a shop's default PREPARATION TIME and the per-order ESTIMATED READY-BY it produces.
--    shops.prep_time_minutes INT, nullable: NULL means "the vendor has not said", and then no estimate is
--    shown anywhere (the V63/V66 rule: no DEFAULT, no backfill, because a default would have the platform
--    promise a time no vendor gave). CHECK 1..480 matches @Min(1) @Max(480) on CreateShopRequest.
--    orders.estimated_ready_at TIMESTAMPTZ, nullable: a SNAPSHOT of (placed_at or accepted_at) + the shop's
--    prep time at that moment, so a later edit of the shop setting does not move a time the customer was
--    already shown. It is an ESTIMATE, labelled as one wherever it is displayed. NULL = no estimate (the
--    shop gave no prep time, the order is a scheduled-slot order with its own window, or the order predates
--    V91). Never backfilled: inventing an estimate for a past order would fabricate a record.
--
-- 2. #821 -- an order left PENDING too long raises a vendor alert and emails the customer once.
--    orders.unaccepted_notified_at TIMESTAMPTZ, nullable: stamped by the scheduled sweep in the SAME statement
--    that claims the order, so each order is notified at most once even with two sweepers racing; the kitchen
--    board and dashboard read the stamp as "overdue". NULL = not (yet) alerted. No backfill: existing PENDING
--    orders are picked up by the first sweep like any other.
--    Partial index idx_orders_pending_unnotified backs the sweep's claim (open, not voided, not yet alerted).
--
-- 3. Shop and Order are @Audited: the shops_aud and orders_aud mirrors of all three columns are in THIS
--    migration (a missing _aud column throws at runtime on the next audited write: V30/V38). The mirrors are
--    nullable, unconstrained and NOT backfilled (NULL there = not recorded).
--
-- No UPDATE, so no per-tenant GUC loop and no FORCE-RLS backfill trap (V25 -> V44 -> V57 -> ... -> V80). No new
-- table and no RLS policy change: the existing orders / shops policies cover the new columns.

ALTER TABLE shops ADD COLUMN IF NOT EXISTS prep_time_minutes INT;
ALTER TABLE shops ADD CONSTRAINT ck_shops_prep_time_minutes
    CHECK (prep_time_minutes IS NULL OR (prep_time_minutes BETWEEN 1 AND 480));

ALTER TABLE orders ADD COLUMN IF NOT EXISTS estimated_ready_at     TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS unaccepted_notified_at TIMESTAMPTZ;

COMMENT ON COLUMN shops.prep_time_minutes IS
    'Default preparation time in minutes (#825, owner ruling 2026-11-04). NULL = not set: no ready-by estimate is shown for the shop.';
COMMENT ON COLUMN orders.estimated_ready_at IS
    'ESTIMATED ready-by instant (#825): placed-at or accepted-at plus the shop prep time at that moment. A snapshot, labelled as an estimate wherever shown. NULL = no estimate.';
COMMENT ON COLUMN orders.unaccepted_notified_at IS
    'When the unaccepted-order sweep alerted the vendor and emailed the customer (#821). NULL = not alerted. Written once.';

ALTER TABLE shops_aud  ADD COLUMN IF NOT EXISTS prep_time_minutes     INT;
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS estimated_ready_at     TIMESTAMPTZ;
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS unaccepted_notified_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_orders_pending_unnotified
    ON orders (tenant_id, updated_at)
    WHERE status = 'PENDING' AND unaccepted_notified_at IS NULL AND voided_at IS NULL;

DO $$
BEGIN
    RAISE NOTICE 'V91 applied: shops.prep_time_minutes, orders.estimated_ready_at and orders.unaccepted_notified_at '
        '(all nullable, no backfill) with nullable _aud mirrors, and idx_orders_pending_unnotified.';
END $$;
