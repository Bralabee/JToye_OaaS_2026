-- =============================================================================
-- V87: Multi-model commercial & scheduled fulfilment policy foundation (ADR-0007)
--      Closes GAP-GTM-01 (SettlementPolicyMode), GAP-GTM-02 (Scheduled Slots),
--      and GAP-GTM-03 (Consolidated Kitchen Cook Sheet indexing).
-- =============================================================================
--
-- WHY THIS MIGRATION EXISTS
-- -------------------------
-- 1. Commercial Settlement Policy (GAP-GTM-01 / ADR-0007 §1):
--    Previously StripeConnectService.resolveDestinationAccount routed ONLY
--    OnboardingModel.MARKETPLACE tenants via destination charges, leaving
--    WHITE_LABEL tenants on platform-level settlement unless hardcoded.
--    Adding nullable `shops.settlement_policy_mode` and `shops.deposit_bps`
--    (NOT NULL DEFAULT 10000 = 100% full payment) allows each shop to declare
--    or inherit its settlement posture:
--      - DIRECT_CHARGE_VENDOR_MOR (Cohort A/B default for WHITE_LABEL: direct
--        charge on the connected Stripe account with application_fee_amount;
--        Vendor is the legal Merchant of Record)
--      - DESTINATION_CHARGE_PLATFORM_MOR (Cohort D Marketplace default)
--      - SPLIT_DEPOSIT_VENDOR_MOR (Cohort C Catering deposit at checkout +
--        balance due recorded on the order)
--
-- 2. Scheduled Fulfilment Slots & Batch Capacities (GAP-GTM-02 / ADR-0007 §2):
--    Previously ShopAvailabilityPolicy.requireTakingOrders refused every
--    storefront order when a shop was outside its immediate opening windows,
--    blocking Cohort B batch/litre-bowl kitchens and Cohort C caterers from
--    taking pre-orders during the week for a scheduled weekend/future window.
--    `shops.fulfilment_timing_mode` (NOT NULL DEFAULT 'IMMEDIATE_ONLY',
--    preserving current behaviour for every existing shop with zero UPDATE)
--    and the new tenant-scoped `shop_fulfilment_slots` table allow shops to
--    accept capacity-bounded pre-orders for future service windows.
--
-- 3. Order Scheduled Snapshot & Deposit Columns (GAP-GTM-02 / GAP-GTM-03):
--    `orders` gains nullable `fulfilment_slot_id`, `scheduled_for_date`,
--    `scheduled_window_label`, `deposit_amount_pennies`, and
--    `balance_due_pennies` (plus nullable unconstrained mirrors on `orders_aud`
--    in the same migration, per the V30/V38 Envers rule). No backfill and no
--    default on `orders`: NULL means "not a scheduled-slot / deposit order".
--
-- RLS & TENANT ISOLATION
-- ----------------------
-- `shop_fulfilment_slots` has ENABLE + FORCE ROW LEVEL SECURITY:
--   1. `shop_fulfilment_slots_tenant_isolation` FOR ALL using the safe
--      `current_tenant_id()` helper (never a raw `::uuid` cast, V39/V51).
--   2. `shop_fulfilment_slots_public_read` FOR SELECT when
--      `current_tenant_id() IS NULL` (anonymous storefront read) AND the slot
--      is `active = true` on a `published = true` shop. Guarding the public
--      branch on `current_tenant_id() IS NULL` ensures an authenticated
--      tenant's unscoped SELECT never sees another tenant's published slots.
-- =============================================================================

-- ============================================================
-- 1. shops + shops_aud: fulfilment timing & settlement policy
-- ============================================================
ALTER TABLE shops
    ADD COLUMN IF NOT EXISTS fulfilment_timing_mode VARCHAR(32) NOT NULL DEFAULT 'IMMEDIATE_ONLY',
    ADD COLUMN IF NOT EXISTS settlement_policy_mode VARCHAR(40),
    ADD COLUMN IF NOT EXISTS deposit_bps INT NOT NULL DEFAULT 10000;

ALTER TABLE shops
    ADD CONSTRAINT ck_shops_fulfilment_timing_mode
        CHECK (fulfilment_timing_mode IN ('IMMEDIATE_ONLY', 'SCHEDULED_SLOTS_ONLY', 'HYBRID_IMMEDIATE_AND_SLOTS')),
    ADD CONSTRAINT ck_shops_settlement_policy_mode
        CHECK (settlement_policy_mode IS NULL OR settlement_policy_mode IN (
            'DIRECT_CHARGE_VENDOR_MOR',
            'DESTINATION_CHARGE_PLATFORM_MOR',
            'SPLIT_DEPOSIT_VENDOR_MOR'
        )),
    ADD CONSTRAINT ck_shops_deposit_bps
        CHECK (deposit_bps BETWEEN 1000 AND 10000);

COMMENT ON COLUMN shops.fulfilment_timing_mode IS
    'ADR-0007 / GAP-GTM-02: Whether the shop accepts immediate orders only (IMMEDIATE_ONLY, default), scheduled slot pre-orders only (SCHEDULED_SLOTS_ONLY), or both (HYBRID_IMMEDIATE_AND_SLOTS).';

COMMENT ON COLUMN shops.settlement_policy_mode IS
    'ADR-0007 / GAP-GTM-01: Optional shop-level settlement policy override (DIRECT_CHARGE_VENDOR_MOR | DESTINATION_CHARGE_PLATFORM_MOR | SPLIT_DEPOSIT_VENDOR_MOR). NULL inherits from the tenant OnboardingModel.';

COMMENT ON COLUMN shops.deposit_bps IS
    'ADR-0007 / GAP-GTM-01: Deposit basis points (1000..10000) charged at checkout when settlement_policy_mode is SPLIT_DEPOSIT_VENDOR_MOR. Default 10000 (100% upfront payment).';

ALTER TABLE shops_aud
    ADD COLUMN IF NOT EXISTS fulfilment_timing_mode VARCHAR(32),
    ADD COLUMN IF NOT EXISTS settlement_policy_mode VARCHAR(40),
    ADD COLUMN IF NOT EXISTS deposit_bps INT;

-- ============================================================
-- 2. shop_fulfilment_slots: capacity-bounded pre-order windows
-- ============================================================
CREATE TABLE IF NOT EXISTS shop_fulfilment_slots (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
    service_date DATE NOT NULL,
    window_start TIME NOT NULL,
    window_end TIME NOT NULL,
    cutoff_at TIMESTAMPTZ NOT NULL,
    max_orders INT,
    max_units INT,
    reserved_orders INT NOT NULL DEFAULT 0,
    reserved_units INT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_fulfilment_slot_window
        CHECK (window_end > window_start),
    CONSTRAINT ck_fulfilment_slot_capacity
        CHECK (reserved_orders >= 0
           AND reserved_units >= 0
           AND (max_orders IS NULL OR (max_orders > 0 AND reserved_orders <= max_orders))
           AND (max_units IS NULL OR (max_units > 0 AND reserved_units <= max_units)))
);

CREATE INDEX IF NOT EXISTS idx_shop_fulfilment_slots_shop_date
    ON shop_fulfilment_slots (shop_id, service_date, window_start)
    WHERE active = true;

CREATE INDEX IF NOT EXISTS idx_shop_fulfilment_slots_tenant
    ON shop_fulfilment_slots (tenant_id);

COMMENT ON TABLE shop_fulfilment_slots IS
    'ADR-0007 / GAP-GTM-02: Capacity-bounded scheduled fulfilment slots for pre-orders, batch drops, and catering windows. Tenant-scoped under ENABLE + FORCE RLS.';

ALTER TABLE shop_fulfilment_slots ENABLE ROW LEVEL SECURITY;
ALTER TABLE shop_fulfilment_slots FORCE ROW LEVEL SECURITY;

DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'shop_fulfilment_slots'
          AND policyname = 'shop_fulfilment_slots_tenant_isolation'
    ) THEN
        CREATE POLICY shop_fulfilment_slots_tenant_isolation ON shop_fulfilment_slots
            FOR ALL
            USING (tenant_id = current_tenant_id())
            WITH CHECK (tenant_id = current_tenant_id());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'shop_fulfilment_slots'
          AND policyname = 'shop_fulfilment_slots_public_read'
    ) THEN
        CREATE POLICY shop_fulfilment_slots_public_read ON shop_fulfilment_slots
            FOR SELECT
            USING (
                current_tenant_id() IS NULL
                AND active = true
                AND EXISTS (
                    SELECT 1 FROM shops s
                    WHERE s.id = shop_fulfilment_slots.shop_id
                      AND s.published = true
                )
            );
    END IF;
END $$;

-- ============================================================
-- 3. orders + orders_aud: scheduled slot snapshot & split deposit
-- ============================================================
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS fulfilment_slot_id UUID REFERENCES shop_fulfilment_slots(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS scheduled_for_date DATE,
    ADD COLUMN IF NOT EXISTS scheduled_window_label VARCHAR(64),
    ADD COLUMN IF NOT EXISTS deposit_amount_pennies BIGINT,
    ADD COLUMN IF NOT EXISTS balance_due_pennies BIGINT;

COMMENT ON COLUMN orders.fulfilment_slot_id IS
    'ADR-0007 / GAP-GTM-02: Scheduled fulfilment slot reserved by this order (NULL for immediate orders).';
COMMENT ON COLUMN orders.scheduled_for_date IS
    'ADR-0007 / GAP-GTM-02: Service date snapshotted from the fulfilment slot when the order was placed.';
COMMENT ON COLUMN orders.scheduled_window_label IS
    'ADR-0007 / GAP-GTM-02: Human-readable window label (e.g. "2026-10-24 14:00-18:00") snapshotted from the fulfilment slot.';
COMMENT ON COLUMN orders.deposit_amount_pennies IS
    'ADR-0007 / GAP-GTM-01: Upfront deposit charged at checkout in pennies when settled under SPLIT_DEPOSIT_VENDOR_MOR (NULL for standard full-payment orders).';
COMMENT ON COLUMN orders.balance_due_pennies IS
    'ADR-0007 / GAP-GTM-01: Remaining balance due in pennies when settled under SPLIT_DEPOSIT_VENDOR_MOR (NULL for standard full-payment orders).';

ALTER TABLE orders_aud
    ADD COLUMN IF NOT EXISTS fulfilment_slot_id UUID,
    ADD COLUMN IF NOT EXISTS scheduled_for_date DATE,
    ADD COLUMN IF NOT EXISTS scheduled_window_label VARCHAR(64),
    ADD COLUMN IF NOT EXISTS deposit_amount_pennies BIGINT,
    ADD COLUMN IF NOT EXISTS balance_due_pennies BIGINT;

CREATE INDEX IF NOT EXISTS idx_orders_shop_scheduled_date
    ON orders (shop_id, scheduled_for_date)
    WHERE scheduled_for_date IS NOT NULL AND voided_at IS NULL;

DO $$
BEGIN
    RAISE NOTICE 'V87 applied: shops gained fulfilment_timing_mode, settlement_policy_mode, deposit_bps (+ shops_aud mirrors); shop_fulfilment_slots created under ENABLE + FORCE RLS; orders gained scheduled slot + deposit columns (+ orders_aud mirrors).';
END $$;
