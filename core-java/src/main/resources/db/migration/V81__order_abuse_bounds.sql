-- V81: Phase 37-27 (RWO-041 / UXT-041, decisions D-18 and D-27) — the columns and indexes behind the
-- cash-order abuse bounds: whether the contact was verified, why an order was cancelled, and a
-- pseudonymous digest of the client address; plus the partial indexes that count OPEN cash orders per
-- shop by email, by phone and by digest. Reserved here for the whole of sub-theme 37-D, so 37-28 (the
-- IP axis) and 37-29 (bulk reject) add no migration of their own.
--
-- WHY THIS EXISTS. The persona run placed many fake cash orders in seconds with throwaway contact
-- details (UXT-041). Nothing bounded how many open cash orders one identity could hold at a shop, and
-- the kitchen could not tell a verified customer from an invented one. D-18 answers with caps per
-- identity (config-declared, typed 429) and an "unverified contact" badge that reads a STORED fact.
--
-- ============================================================================================
-- contact_verified    — BOOLEAN NULL. true: the storefront request carried a verified customer token
--                       whose email equals the order's customer_email (case-insensitive). false: a
--                       storefront order with no such token. NULL: NOT RECORDED — every order placed
--                       before V81, and every vendor / REST API / MCP order (the vendor is the writer;
--                       there is no customer contact to verify). NULL and false are DIFFERENT facts:
--                       the kitchen badge renders for false and renders nothing for NULL (UI-SPEC D1).
--                       No DEFAULT and NO BACKFILL (the V63/V66/V69 rule): a default false would claim
--                       every historic customer was unverified, a statement nobody recorded.
--
-- cancel_reason_code  — VARCHAR(32) NULL, CHECK IN the four uk.jtoye.core.order.CancelReason values.
--                       Written by 37-29's bulk reject (and any later single cancel that names a reason).
--                       Every value the enum will hold is listed NOW, so no later migration rewrites the
--                       constraint (the V43 lesson, V36 REFUNDED). NULL: no reason recorded (every
--                       pre-V81 cancellation, and a cancel through a path that gives none).
--
-- client_ip_digest    — CHAR(64) NULL. An HMAC-SHA256 (hex) of the client address resolved by
--                       ClientIpResolver (D-19), under a key from config — NEVER the address itself
--                       (D-27). Written by 37-28; this migration only reserves the column and its index.
--                       It is a pseudonymous identifier that lives with the order, so it falls under
--                       retention and Article 17 erasure: the erasure path nulls it, and 37-28 declares
--                       its retention. NULL: not recorded.
-- ============================================================================================
--
-- ENVERS. Order is @Audited, so contact_verified and cancel_reason_code also land on the audit table in
-- THIS migration, or the next audited order write throws at RUNTIME (the V38 repair after V30). The
-- mirrors are nullable with no DEFAULT and no CHECK, the house convention: an audit row records a
-- revision, not a live constraint. _aud rows are history and are NOT backfilled (NULL = not recorded).
--
-- THE DIGEST HAS NO AUDIT MIRROR, DELIBERATELY (D-27, threat T-37-70). The entity field is @NotAudited,
-- so Envers never writes it and no mirror column exists. A keyed digest of a person's network address
-- copied into append-only history would be a second pseudonymous store that erasure then has to chase
-- through every revision; keeping it on the live row only means one NULL ends it. The integration test
-- asserts via information_schema that the audit table has no such column.
--
-- NO BACKFILL, so NO UPDATE and NO tenant loop: the RLS-backfill trap (V25 -> V44 -> V57 -> V78 -> V79
-- -> V80: a bare UPDATE against a FORCE-RLS table matches ZERO rows under the migration role and reports
-- success) genuinely does not apply. ADD COLUMN with no default is metadata-only in Postgres 11+. The one
-- CHECK is validated against existing rows, all NULL, which pass.
--
-- INDEXES. Three PARTIAL indexes on orders, one per cap axis, each restricted to the rows the cap counts:
-- an OPEN order (status PENDING, CONFIRMED, PREPARING or READY), paid in CASH (payment_status NONE —
-- the COD branch of PublicStorefrontService.placeGuestOrder writes NONE; a card order carries PENDING
-- until the webhook writes CAPTURED, so card orders are outside the index and outside the cap), and not
-- VOIDED (a void keeps the status, D-10, so without the predicate a voided order would hold a slot).
-- The count queries in OrderRepository repeat these predicates verbatim so the planner can use the index;
-- the email axis indexes lower(customer_email) because the cap folds case (CashOrderCaps normalises).
-- The phone index is on the raw column: phone normalisation (digits, UK 0 -> 44) lives in Java, and the
-- index serves the per-shop scan of open cash orders that the Java normaliser then matches.
--
-- RLS. No new table: orders keeps ENABLE + FORCE ROW LEVEL SECURITY through current_tenant_id(). No
-- policy is created, altered or dropped, so RlsContractTest's schema walk is unaffected.
--
-- NO POSTGRESQL EXTENSION is created (scripts/check-no-create-extension.sh).
--
-- VERSION NUMBERING. V80 was the head (37-19); V81 is 37-27's and covers 37-28 and 37-29 as well.

-- ============================================================
-- 1. orders — the three columns (metadata-only, nullable, no backfill, no default)
-- ============================================================
ALTER TABLE orders ADD COLUMN IF NOT EXISTS contact_verified   BOOLEAN;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancel_reason_code VARCHAR(32);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS client_ip_digest   CHAR(64);

ALTER TABLE orders ADD CONSTRAINT ck_orders_cancel_reason_code
    CHECK (cancel_reason_code IS NULL
           OR cancel_reason_code IN ('SUSPECTED_FAKE', 'CANNOT_FULFIL', 'CUSTOMER_REQUEST', 'OTHER'));

COMMENT ON COLUMN orders.contact_verified IS
    'Whether the storefront request that placed this order carried a verified customer token for its customer_email (D-18). true = verified; false = a storefront order with no such token (the kitchen shows "Unverified contact"); NULL = NOT RECORDED (pre-V81 orders and every vendor/API/MCP order). NULL is not false: no default, no backfill.';

COMMENT ON COLUMN orders.cancel_reason_code IS
    'Why the order was cancelled, as the vendor chose it (37-29 bulk reject): SUSPECTED_FAKE | CANNOT_FULFIL | CUSTOMER_REQUEST | OTHER (uk.jtoye.core.order.CancelReason). NULL = no reason recorded.';

COMMENT ON COLUMN orders.client_ip_digest IS
    'HMAC-SHA256 (hex) of the resolved client address under a config key (D-27), for the per-IP open-cash-order cap; NEVER the address itself. Written by 37-28. Pseudonymous: nulled by the retention purge and by Article 17 erasure. Not audited. NULL = not recorded.';

-- ============================================================
-- 2. Audit mirrors, in the SAME migration. Nullable, no default, no check. The digest has none (D-27).
-- ============================================================
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS contact_verified   BOOLEAN;
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS cancel_reason_code VARCHAR(32);

-- ============================================================
-- 3. The cap indexes: open + cash + not voided, one per identity axis.
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_orders_open_cash_email
    ON orders (shop_id, lower(customer_email))
    WHERE status IN ('PENDING', 'CONFIRMED', 'PREPARING', 'READY')
      AND payment_status = 'NONE'
      AND voided_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_orders_open_cash_phone
    ON orders (shop_id, customer_phone)
    WHERE status IN ('PENDING', 'CONFIRMED', 'PREPARING', 'READY')
      AND payment_status = 'NONE'
      AND voided_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_orders_open_cash_ip
    ON orders (shop_id, client_ip_digest)
    WHERE status IN ('PENDING', 'CONFIRMED', 'PREPARING', 'READY')
      AND payment_status = 'NONE'
      AND voided_at IS NULL;

-- ============================================================
-- 4. AUDIT NOTE — runtime marker (no dedicated audit/log table exists in schema).
-- ============================================================
DO $$
BEGIN
    RAISE NOTICE 'V81 order abuse bounds applied: orders gained nullable contact_verified, cancel_reason_code (CHECK) and client_ip_digest (no audit mirror); the audit table mirrored the first two; three partial open-cash indexes created; no row was backfilled and no default was set.';
END $$;
