-- V43: Phase 18 — Vendor onboarding aggregate + data-driven gate chain.
--
-- Establishes the persistence foundation for vendor onboarding (design doc
-- docs/architecture/VENDOR_ONBOARDING_STATE_MODEL.md §4):
--   1. vendor_onboarding       — the tenant-scoped aggregate root (one per tenant).
--   2. vendor_onboarding_gate  — one data-driven row per compliance requirement.
--   3. Both Envers _aud mirrors (all columns nullable, PK (id, rev), FK rev->revinfo).
--
-- All four tables carry ENABLE + FORCE ROW LEVEL SECURITY with tenant policies keyed
-- on the canonical `app.current_tenant_id` GUC (set by TenantSetLocalAspect). This
-- mirrors the V36 table+RLS+FORCE+policy+_aud template and the idempotent DO-block
-- DDL house style of V42. Forward-only.
--
-- A DRAFT onboarding can be built under RLS while Shop.published=false holds the
-- storefront back — the state machine (18-02) becomes the sole writer of published.

-- ============================================================
-- 1. vendor_onboarding — aggregate root (one per tenant)
-- ============================================================
CREATE TABLE IF NOT EXISTS vendor_onboarding (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID         NOT NULL,
    shop_id            UUID         REFERENCES shops(id),
    model              VARCHAR(20)  NOT NULL
                         CHECK (model IN ('MARKETPLACE','WHITE_LABEL')),
    status             VARCHAR(24)  NOT NULL DEFAULT 'DRAFT'
                         CHECK (status IN ('DRAFT','VERIFYING','ACTION_REQUIRED',
                                           'PENDING_APPROVAL','APPROVED','LIVE',
                                           'SUSPENDED','REJECTED','WITHDRAWN')),
    -- company_number is NOT in the design §4 sketch, but the BUSINESS_VERIFIED
    -- (Companies House) gate in slice 18-04 needs it as input. Added here so slice 2
    -- does not require a CHECK/column-rewrite migration. Nullable: sole traders have
    -- no Companies House registration (BUSINESS_VERIFIED is WAIVED for them).
    company_number     VARCHAR(32),   -- company_number VARCHAR(32) (Companies House gate input, slice 18-04)
    stripe_account_id  VARCHAR(255),                 -- marketplace Connect account (reserved for slice 2)
    submitted_at       TIMESTAMPTZ,
    approved_at        TIMESTAMPTZ,
    went_live_at       TIMESTAMPTZ,
    suspended_at       TIMESTAMPTZ,
    rejection_reason   TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ,
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_onboarding_tenant UNIQUE (tenant_id)   -- MVP: one onboarding per tenant
);
CREATE INDEX IF NOT EXISTS idx_onboarding_tenant ON vendor_onboarding (tenant_id);
CREATE INDEX IF NOT EXISTS idx_onboarding_status ON vendor_onboarding (status);  -- compliance monitor scans LIVE

ALTER TABLE vendor_onboarding ENABLE ROW LEVEL SECURITY;
ALTER TABLE vendor_onboarding FORCE  ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='vendor_onboarding' AND policyname='vendor_onboarding_tenant_policy') THEN
    CREATE POLICY vendor_onboarding_tenant_policy ON vendor_onboarding
        FOR ALL
        USING      (tenant_id = current_setting('app.current_tenant_id', true)::UUID)
        WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true)::UUID);
  END IF;
END $$;

-- ============================================================
-- 2. vendor_onboarding_gate — one row per compliance requirement
-- ============================================================
-- The gate_type CHECK lists ALL 8 gate types even though only 3 are IMPLEMENTED
-- this slice (BUSINESS_VERIFIED, FOOD_HYGIENE_RATING, ALLERGEN_DATA_COMPLETE).
-- Pre-listing the future 5 is the deliberate forward-compat lesson from the V36
-- orders_status_check REFUNDED landmine (a late-added CHECK value forces a
-- constraint-rewrite migration). The extra CHECK values are inert until slice 2.
CREATE TABLE IF NOT EXISTS vendor_onboarding_gate (
    id             UUID PRIMARY KEY,
    tenant_id      UUID         NOT NULL,                 -- denormalised for RLS
    onboarding_id  UUID         NOT NULL REFERENCES vendor_onboarding(id),
    gate_type      VARCHAR(32)  NOT NULL
                     CHECK (gate_type IN ('BUSINESS_VERIFIED','FOOD_HYGIENE_RATING',
                                          'FOOD_BUSINESS_REGISTRATION','IDENTITY_KYC',
                                          'PAYMENTS_CONNECTED','AGREEMENT_SIGNED',
                                          'ALLERGEN_DATA_COMPLETE','MENU_MINIMUM')),
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING','PASSED','FAILED','MANUAL_REVIEW','WAIVED')),
    mandatory      BOOLEAN      NOT NULL DEFAULT true,
    evidence       JSONB,                                -- provider snapshot
    external_ref   VARCHAR(255),                         -- FHRS id / CH number / Stripe acct / envelope id
    reason         TEXT,
    checked_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_gate_onboarding_type UNIQUE (onboarding_id, gate_type)
);
CREATE INDEX IF NOT EXISTS idx_gate_tenant     ON vendor_onboarding_gate (tenant_id);
CREATE INDEX IF NOT EXISTS idx_gate_onboarding ON vendor_onboarding_gate (onboarding_id);

ALTER TABLE vendor_onboarding_gate ENABLE ROW LEVEL SECURITY;
ALTER TABLE vendor_onboarding_gate FORCE  ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='vendor_onboarding_gate' AND policyname='vendor_onboarding_gate_tenant_policy') THEN
    CREATE POLICY vendor_onboarding_gate_tenant_policy ON vendor_onboarding_gate
        FOR ALL
        USING      (tenant_id = current_setting('app.current_tenant_id', true)::UUID)
        WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true)::UUID);
  END IF;
END $$;

-- ============================================================
-- 3. Envers _aud mirrors — every column nullable; PK (id, rev); FK rev->revinfo.
--     Audit rows may carry tenant_id NULL, so the _aud RLS predicate admits NULL
--     yet still tenant-filters on read (mirrors the V36 refunds_aud pattern).
-- ============================================================
CREATE TABLE IF NOT EXISTS vendor_onboarding_aud (
    id                 UUID     NOT NULL,
    rev                INT      NOT NULL REFERENCES revinfo(rev),
    revtype            SMALLINT,
    tenant_id          UUID,
    shop_id            UUID,
    model              VARCHAR(20),
    status             VARCHAR(24),
    company_number     VARCHAR(32),
    stripe_account_id  VARCHAR(255),
    submitted_at       TIMESTAMPTZ,
    approved_at        TIMESTAMPTZ,
    went_live_at       TIMESTAMPTZ,
    suspended_at       TIMESTAMPTZ,
    rejection_reason   TEXT,
    created_at         TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ,
    version            BIGINT,
    PRIMARY KEY (id, rev)
);

CREATE TABLE IF NOT EXISTS vendor_onboarding_gate_aud (
    id             UUID     NOT NULL,
    rev            INT      NOT NULL REFERENCES revinfo(rev),
    revtype        SMALLINT,
    tenant_id      UUID,
    onboarding_id  UUID,
    gate_type      VARCHAR(32),
    status         VARCHAR(16),
    mandatory      BOOLEAN,
    evidence       JSONB,
    external_ref   VARCHAR(255),
    reason         TEXT,
    checked_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ,
    version        BIGINT,
    PRIMARY KEY (id, rev)
);

ALTER TABLE vendor_onboarding_aud      ENABLE ROW LEVEL SECURITY;
ALTER TABLE vendor_onboarding_aud      FORCE  ROW LEVEL SECURITY;
ALTER TABLE vendor_onboarding_gate_aud ENABLE ROW LEVEL SECURITY;
ALTER TABLE vendor_onboarding_gate_aud FORCE  ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='vendor_onboarding_aud' AND policyname='vendor_onboarding_aud_tenant_policy') THEN
    CREATE POLICY vendor_onboarding_aud_tenant_policy ON vendor_onboarding_aud
        FOR ALL
        USING      (tenant_id IS NULL OR tenant_id = current_setting('app.current_tenant_id', true)::UUID)
        WITH CHECK (tenant_id IS NULL OR tenant_id = current_setting('app.current_tenant_id', true)::UUID);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='vendor_onboarding_gate_aud' AND policyname='vendor_onboarding_gate_aud_tenant_policy') THEN
    CREATE POLICY vendor_onboarding_gate_aud_tenant_policy ON vendor_onboarding_gate_aud
        FOR ALL
        USING      (tenant_id IS NULL OR tenant_id = current_setting('app.current_tenant_id', true)::UUID)
        WITH CHECK (tenant_id IS NULL OR tenant_id = current_setting('app.current_tenant_id', true)::UUID);
  END IF;
END $$;
