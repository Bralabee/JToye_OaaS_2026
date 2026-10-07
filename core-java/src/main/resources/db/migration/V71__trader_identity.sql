-- V71: Phase 31.1 / #789 (P0) — the legal entity customers buy FROM (D-10, D-11).
--
-- WHAT. One row per tenant holding the statutory trader details that the Consumer Contracts
-- Regulations 2013 Sch 2 (b)-(c) and the Electronic Commerce Regulations 2002 reg 6 require a
-- distance seller to give BEFORE purchase: the legal name (the company, or a sole trader's own
-- name), the kind of entity, a GEOGRAPHIC address, and the VAT number when VAT-registered.
-- Until now the platform held no legal-entity field at all; a shop published a trading name
-- and premises only.
--
-- WHY TENANT-LEVEL, not per shop (D-11). The legal entity is the business, not the premises:
-- a multi-site owner enters it ONCE and every shop of the tenant resolves to this one row.
-- Per-shop contact details stay where they already are (shops.phone / shops.email, edited on
-- the shop form), so this table carries no contact columns. #452 may later make onboarding
-- per-shop; the entity still belongs to the tenant, so that change would not move this row.
--
-- WHY NO COMPANY NUMBER COLUMN. vendor_onboarding.company_number (V43, UNIQUE(tenant_id) on
-- vendor_onboarding) already holds it, normalised and gate-checked against Companies House.
-- A second copy here could disagree with the one the gate verified, so the API reads it from
-- vendor_onboarding for display and never stores it twice.
--
-- WHY NO BACKFILL. There are no real tenants yet, and an invented legal name or address for a
-- demo tenant written by Flyway would land in EVERY environment's database as a statutory
-- statement nobody made: the V63 "a fabricated record is indistinguishable from a real one"
-- defect. Demo and e2e tenants are filled by the dev seeder and the e2e fixtures (31.1-12),
-- never here. The table ships EMPTY.
--
-- RLS. trader_identity and trader_identity_aud are ENABLE + FORCE row-level security through
-- the SAFE helper current_tenant_id() (V51), never the raw GUC cast; RlsContractTest sweeps
-- both with no exemption. The _aud mirror follows the V65 shape: tenant-scoped SELECT, and an
-- INSERT policy that admits (tenant_id IS NULL OR tenant_id = current_tenant_id()) — the NULL
-- arm keeps an Envers DELETE revision writable if store_data_at_delete is ever reverted, the
-- second arm refuses an audit row stamped with a FOREIGN tenant. There is no UPDATE or DELETE
-- policy on the _aud table: the audit trail of who published which legal identity is
-- append-only for the application role.
--
-- GATE CHECK. vendor_onboarding_gate.gate_type carries an inline CHECK of the 8 values V43
-- pre-listed for forward compatibility. V43's own note anticipated this: a value added later
-- needs a constraint rewrite. TRADER_IDENTITY is that value; 31.1-12 adds the gate that writes
-- it. The constraint is dropped and re-added with the 8 existing values plus TRADER_IDENTITY.
-- No row changes (every existing row holds one of the 8). vendor_onboarding_gate_aud.gate_type
-- has no CHECK and is not touched.
--
-- Sequential after V70; spring.flyway.out-of-order=true is set in every profile. No extension,
-- no role, no data change.

-- ============================================================
-- 1. trader_identity — one legal entity per tenant
-- ============================================================
CREATE TABLE IF NOT EXISTS trader_identity (
    id                UUID PRIMARY KEY,
    tenant_id         UUID         NOT NULL UNIQUE REFERENCES tenants(id),
    legal_name        VARCHAR(255) NOT NULL,
    entity_type       VARCHAR(16)  NOT NULL
                        CHECK (entity_type IN ('COMPANY','SOLE_TRADER','PARTNERSHIP')),
    address_line1     VARCHAR(255) NOT NULL,
    address_line2     VARCHAR(255),
    address_city      VARCHAR(120) NOT NULL,
    address_postcode  VARCHAR(12)  NOT NULL,
    vat_number        VARCHAR(16),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0
);

COMMENT ON TABLE trader_identity IS
    'The tenant''s legal entity shown to customers before purchase (CCR 2013 Sch 2, E-Commerce Regs 2002 reg 6). One per tenant; the company number is read from vendor_onboarding, never copied.';
COMMENT ON COLUMN trader_identity.vat_number IS
    'NULL means the trader declared no VAT registration. Stored upper-case with no spaces: GB followed by 9 or 12 digits.';

ALTER TABLE trader_identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE trader_identity FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='trader_identity' AND policyname='trader_identity_tenant_policy') THEN
    CREATE POLICY trader_identity_tenant_policy ON trader_identity
        FOR ALL
        USING      (tenant_id = current_tenant_id())
        WITH CHECK (tenant_id = current_tenant_id());
  END IF;
END $$;

-- ============================================================
-- 2. trader_identity_aud — Envers mirror (all columns nullable, PK (id, rev), FK rev -> revinfo)
-- ============================================================
CREATE TABLE IF NOT EXISTS trader_identity_aud (
    id                UUID     NOT NULL,
    rev               INT      NOT NULL REFERENCES revinfo(rev),
    revtype           SMALLINT,
    tenant_id         UUID,
    legal_name        VARCHAR(255),
    entity_type       VARCHAR(16),
    address_line1     VARCHAR(255),
    address_line2     VARCHAR(255),
    address_city      VARCHAR(120),
    address_postcode  VARCHAR(12),
    vat_number        VARCHAR(16),
    created_at        TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ,
    version           BIGINT,
    PRIMARY KEY (id, rev)
);

ALTER TABLE trader_identity_aud ENABLE ROW LEVEL SECURITY;
ALTER TABLE trader_identity_aud FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='trader_identity_aud' AND policyname='trader_identity_aud_select_policy') THEN
    CREATE POLICY trader_identity_aud_select_policy ON trader_identity_aud
        FOR SELECT
        USING (tenant_id = current_tenant_id());
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='trader_identity_aud' AND policyname='trader_identity_aud_insert_policy') THEN
    CREATE POLICY trader_identity_aud_insert_policy ON trader_identity_aud
        FOR INSERT
        WITH CHECK ((tenant_id IS NULL) OR (tenant_id = current_tenant_id()));
  END IF;
END $$;

-- ============================================================
-- 3. vendor_onboarding_gate.gate_type admits TRADER_IDENTITY (consumed by 31.1-12)
-- ============================================================
ALTER TABLE vendor_onboarding_gate DROP CONSTRAINT IF EXISTS vendor_onboarding_gate_gate_type_check;
ALTER TABLE vendor_onboarding_gate ADD CONSTRAINT vendor_onboarding_gate_gate_type_check
    CHECK (gate_type IN ('BUSINESS_VERIFIED','FOOD_HYGIENE_RATING',
                         'FOOD_BUSINESS_REGISTRATION','IDENTITY_KYC',
                         'PAYMENTS_CONNECTED','AGREEMENT_SIGNED',
                         'ALLERGEN_DATA_COMPLETE','MENU_MINIMUM',
                         'TRADER_IDENTITY'));

DO $$
BEGIN
    RAISE NOTICE 'V71 trader_identity applied: table and _aud mirror created EMPTY under FORCE RLS; gate_type CHECK now admits TRADER_IDENTITY; no row was written or changed.';
END $$;
