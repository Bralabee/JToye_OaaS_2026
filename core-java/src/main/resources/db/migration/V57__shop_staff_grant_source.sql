-- V57: Phase 23 gap-closure (CR-07 / WR-09) — record grant PROVENANCE on shop_staff.
--
-- CR-07: enabling strict-scoping (D-12) tightened nothing, because D-04's JIT
-- lazy-provision writes a REAL, persistent tenant-wide GROUP_ADMIN row for every
-- user on their first write request, and resolveMembership honoured every such row
-- unconditionally. Flipping the switch only stopped NEW provisioning; every existing
-- day-one user kept GROUP_ADMIN. This column makes a grant's origin explicit so the
-- switch can de-honour JIT-sourced tenant-wide grants while keeping deliberate
-- operator grants (the actual code decision lives in ShopAccessService — this
-- migration only records the fact it needs).
--
--   grant_source = 'JIT'      — auto-created by ShopAccessService.onRequest (D-04),
--                               never by a human; de-honoured under strict-scoping ON
--                               (except the deterministic bootstrap admin — see the service).
--   grant_source = 'OPERATOR' — deliberately created by a GROUP_ADMIN via
--                               /api/v1/staff/grant; always honoured.
--
-- Idempotent DO-block house style (mirrors V52). NO RLS policy is added or altered:
-- the existing shop_staff_tenant_policy (via the safe current_tenant_id() helper, V51)
-- already covers the table, and this column introduces no new trust boundary — a new
-- policy would only risk re-introducing the raw `...::uuid` cast that
-- RlsContractTest.noPolicyUsesRawTenantGucCast forbids. Forward-only: HEAD is V56,
-- V53 stays RESERVED for Phase 24 media_asset, spring.flyway.out-of-order=true is set.

-- ============================================================
-- 1. Add grant_source to shop_staff (nullable first, for a deterministic backfill).
-- ============================================================
ALTER TABLE shop_staff ADD COLUMN IF NOT EXISTS grant_source VARCHAR(16);

-- ============================================================
-- 2. Backfill pre-V57 rows deterministically, UNDER RLS.
--
--    created_by IS NULL distinguishes a JIT row in practice: the JIT insert
--    (insertGroupAdminIfAbsent) never sets created_by, while the operator grant
--    (StaffManagementService.persistNewGrant) always sets it to the granting
--    GROUP_ADMIN's sub. That inference is SOUND for pre-V57 rows because those were
--    the only two write paths that existed.
--
--    Why the explicit column is still needed going forward (an implicit marker is not
--    a contract): created_by is legitimately nullable on the operator path too —
--    23-09's currentCallerSub() returns null for a machine principal — so a future
--    operator grant made by a non-UUID service principal would carry created_by NULL
--    and be mis-read as JIT. From V57 on, provenance is written explicitly at each
--    write site and never inferred from created_by again.
--
--    RLS SAFETY — why this is a tenant loop, not a bare `UPDATE shop_staff ...`:
--    shop_staff carries ENABLE + FORCE ROW LEVEL SECURITY (V52) with policy
--    shop_staff_tenant_policy USING (tenant_id = current_tenant_id()). Flyway runs as
--    the RLS-bound app role (jtoye_app — there is NO spring.flyway.user override), so
--    during migration current_tenant_id() returns NULL and the policy hides EVERY row.
--    A bare UPDATE with no GUC would therefore see zero rows and silently no-op — after
--    which step 3's `SET NOT NULL` FAILS on any database that already holds pre-V57
--    shop_staff rows (D-04's JIT lazy-provision writes a persistent tenant-wide
--    GROUP_ADMIN row on a user's first write request, so every non-fresh Compose/
--    staging/prod DB has them; the DEFAULT 'JIT' on step 3 only affects FUTURE inserts,
--    never existing rows). This is the exact defect class V44's header documents —
--    "V25's own backfill UPDATE ran as the RLS-bound migration role with NO tenant GUC
--    set, so under FORCE RLS it saw zero rows and silently no-opped ... must not be
--    repeated." The remedy is V44's: walk the tenants registry (no RLS on tenants) and
--    set the standard app GUC transaction-locally per tenant — exactly as
--    TenantSetLocalAspect does at runtime — so every row is reached WITH the policy
--    enforced, no policy is altered or bypassed, and the statement works for any role
--    with UPDATE privilege. Re-running is a no-op (WHERE grant_source IS NULL).
--    Testcontainers never caught the bare-UPDATE bug: fresh test DBs run V52 then V57
--    back-to-back on an empty table, so there is nothing to backfill and step 3's
--    SET NOT NULL passes trivially (regression covered by
--    V57GrantSourceBackfillIntegrationTest).
-- ============================================================
DO $$
DECLARE
    t          RECORD;
    n          BIGINT;
    backfilled BIGINT := 0;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        -- Same GUC the app sets (TenantSetLocalAspect); transaction-local, so it
        -- vanishes when Flyway commits this migration.
        PERFORM set_config('app.current_tenant_id', t.id::text, true);

        UPDATE shop_staff
           SET grant_source = CASE WHEN created_by IS NULL THEN 'JIT' ELSE 'OPERATOR' END
         WHERE tenant_id = t.id
           AND grant_source IS NULL;
        GET DIAGNOSTICS n = ROW_COUNT;
        backfilled := backfilled + n;
    END LOOP;

    -- Defensive reset so no later statement in this transaction inherits the last
    -- tenant's GUC (mirrors V44).
    PERFORM set_config('app.current_tenant_id', '', true);

    RAISE NOTICE 'V57: backfilled grant_source on % pre-V57 shop_staff row(s).', backfilled;
END $$;

-- ============================================================
-- 3. Constrain + default + NOT NULL — only AFTER the backfill, so no existing row is
--    left ambiguous. DEFAULT 'JIT' is the fail-safe posture: an unspecified insert is
--    treated as auto-provisioned (de-honoured under strict-scoping ON), never as a
--    deliberate operator grant that survives the flip. Both real write sites set the
--    value explicitly, so the default is only a backstop.
-- ============================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'shop_staff_grant_source_check') THEN
    ALTER TABLE shop_staff
      ADD CONSTRAINT shop_staff_grant_source_check CHECK (grant_source IN ('JIT','OPERATOR'));
  END IF;
END $$;
ALTER TABLE shop_staff ALTER COLUMN grant_source SET DEFAULT 'JIT';
ALTER TABLE shop_staff ALTER COLUMN grant_source SET NOT NULL;

-- ============================================================
-- 4. Mirror the column on the Envers aud table (nullable, unconstrained — the house
--    _aud shape, matching V52's shop_staff_aud). Envers writes grant_source into every
--    ADD/MOD revision, so "who created this grant and how (JIT vs operator)" is auditable.
-- ============================================================
ALTER TABLE shop_staff_aud ADD COLUMN IF NOT EXISTS grant_source VARCHAR(16);
