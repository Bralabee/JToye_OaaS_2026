-- V52: Phase 23 — Vendor-Scoped Access data layer (VSA-01 + D-09).
--
-- Establishes the persistence foundation for in-tenant shop-role authorization:
--   1. shop_staff       — user <-> shop <-> role mapping within a tenant.
--   2. shop_staff_aud   — Envers mirror (all columns nullable, PK (id, rev),
--                         FK rev->revinfo; RLS predicate admits NULL tenant_id).
--   3. user_directory   — login-populated grant-target picker (D-09). Carries
--                         email PII; ENABLE+FORCE RLS is load-bearing. NO _aud.
--
-- All three tables carry ENABLE + FORCE RLS with tenant policies gated through
-- the SAFE helper current_tenant_id() (V51) — NEVER the raw GUC `...::uuid` cast.
-- Copying the V43/V47/V50 raw-cast form would (a) fail
-- RlsContractTest#noPolicyUsesRawTenantGucCast
-- and (b) reintroduce the 22P02 empty-GUC crash class (Issue #113). Uses the V43
-- idempotent DO-block DDL house style. Forward-only (out-of-order applies: HEAD is
-- V56, spring.flyway.out-of-order=true is set in all profiles; V53 stays free for
-- Phase 24 media_asset).
--
-- IMPORTANT — no migrate-time backfill (RESEARCH §1-FLAG): identities live only in
-- Keycloak; there is no local users table and no `sub` set at migrate time. The
-- three tables ship EMPTY. The GROUP_ADMIN "backfill" is JIT lazy-provision on the
-- first authenticated request (D-04), implemented in 23-02 — NOT a SQL UPDATE here.

-- ============================================================
-- 1. shop_staff — user <-> shop <-> role within a tenant
-- ============================================================
CREATE TABLE IF NOT EXISTS shop_staff (
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    user_id     UUID        NOT NULL,                    -- Keycloak `sub`
    shop_id     UUID        REFERENCES shops(id),        -- NULL = tenant-wide (GROUP_ADMIN shape)
    role        VARCHAR(16) NOT NULL
                  CHECK (role IN ('GROUP_ADMIN','SHOP_MANAGER','STAFF')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID
);

-- Uniqueness per (tenant, user, shop) treating tenant-wide (NULL shop) as the
-- zero-uuid. MUST be a functional UNIQUE INDEX — a table UNIQUE constraint cannot
-- use COALESCE. This is the ON CONFLICT target for the race-safe JIT insert (23-02).
CREATE UNIQUE INDEX IF NOT EXISTS uq_shop_staff_tenant_user_shop
    ON shop_staff (tenant_id, user_id, COALESCE(shop_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX IF NOT EXISTS idx_shop_staff_tenant_user ON shop_staff (tenant_id, user_id); -- membership resolution
CREATE INDEX IF NOT EXISTS idx_shop_staff_shop        ON shop_staff (shop_id);

ALTER TABLE shop_staff ENABLE ROW LEVEL SECURITY;
ALTER TABLE shop_staff FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='shop_staff' AND policyname='shop_staff_tenant_policy') THEN
    CREATE POLICY shop_staff_tenant_policy ON shop_staff
        FOR ALL
        USING      (tenant_id = current_tenant_id())     -- V51 safe helper, NOT ::uuid cast
        WITH CHECK (tenant_id = current_tenant_id());
  END IF;
END $$;

-- ============================================================
-- 2. shop_staff_aud — Envers mirror (all cols nullable, PK (id, rev),
--     FK rev->revinfo). The _aud RLS predicate admits NULL tenant_id yet still
--     tenant-filters on read (V43 refunds_aud / V51 pattern).
-- ============================================================
CREATE TABLE IF NOT EXISTS shop_staff_aud (
    id          UUID     NOT NULL,
    rev         INT      NOT NULL REFERENCES revinfo(rev),
    revtype     SMALLINT,
    tenant_id   UUID,
    user_id     UUID,
    shop_id     UUID,
    role        VARCHAR(16),
    created_at  TIMESTAMPTZ,
    created_by  UUID,
    PRIMARY KEY (id, rev)
);

ALTER TABLE shop_staff_aud ENABLE ROW LEVEL SECURITY;
ALTER TABLE shop_staff_aud FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='shop_staff_aud' AND policyname='shop_staff_aud_tenant_policy') THEN
    CREATE POLICY shop_staff_aud_tenant_policy ON shop_staff_aud
        FOR ALL
        USING      (tenant_id IS NULL OR tenant_id = current_tenant_id())
        WITH CHECK (tenant_id IS NULL OR tenant_id = current_tenant_id());
  END IF;
END $$;

-- ============================================================
-- 3. user_directory (D-09) — login-populated grant-target picker. RLS+FORCE, NO _aud.
--     Composite PK (tenant_id, user_id). email is PII → FORCE RLS is load-bearing.
--     The throttled login upsert (23-02) targets ON CONFLICT (tenant_id, user_id).
-- ============================================================
CREATE TABLE IF NOT EXISTS user_directory (
    tenant_id    UUID        NOT NULL,
    user_id      UUID        NOT NULL,                    -- Keycloak `sub`
    email        VARCHAR(320),
    display_name VARCHAR(255),
    last_seen    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id)
);

ALTER TABLE user_directory ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_directory FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='user_directory' AND policyname='user_directory_tenant_policy') THEN
    CREATE POLICY user_directory_tenant_policy ON user_directory
        FOR ALL
        USING      (tenant_id = current_tenant_id())     -- V51 safe helper, NOT ::uuid cast
        WITH CHECK (tenant_id = current_tenant_id());
  END IF;
END $$;
