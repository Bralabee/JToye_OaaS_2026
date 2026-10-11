-- V76: Phase 37 / plan 37-05 (RWO-003; decision D-09, RESEARCH Pitfall 13) — the Staff page can
-- tell a realm admin from a person with no access.
--
-- WHY THIS EXISTS. The Staff page now lists everyone who has signed in to the tenant with their
-- EFFECTIVE access, computed server-side by ShopAccessService.effectiveAccessFor through the same
-- decision path enforcement uses. A realm admin (Keycloak realm role "admin") is an implicit
-- GROUP_ADMIN through the D-03 bridge, but that role lives only in the token: the database holds
-- no shop_staff row for it and cannot see the role at all. Without this column every realm admin
-- would read "No access" on the page while being able to do everything — the exact "the page says
-- one thing, the system does another" defect D-09 exists to remove.
--
-- ============================================================================================
-- realm_admin_seen_at — when this user was FIRST seen signing in to this tenant with the realm
--                       admin role. Stamped by UserDirectoryToucher at the throttled directory
--                       upsert (reads included) the first time a token carries the role; never
--                       overwritten afterwards.
-- ============================================================================================
--
-- NULL MEANS "NEVER OBSERVED WITH THE ROLE". It is an observation, not a live lookup: a role
-- removed in Keycloak later is not un-stamped here (the token, not this column, is what the API
-- enforces with — a stamped user without the role in their token is NOT an admin to the API).
-- NOTHING IS BACKFILLED and there is NO DEFAULT: the database has never recorded who held the
-- role, so any backfilled value would be a statement nobody observed (the V63/V66 rule).
--
-- Because there is no backfill there is NO UPDATE and NO tenant loop, so the RLS-backfill trap
-- (V25 -> V44 -> V57: a bare UPDATE against a FORCE-RLS table matches ZERO rows under the
-- migration role and reports success) does not apply. ADD COLUMN with no default is
-- metadata-only in Postgres 11+.
--
-- RLS. user_directory keeps its V52 posture (ENABLE + FORCE, user_directory_tenant_policy through
-- the safe current_tenant_id()). No policy is created, altered or dropped. NO _aud: the table is
-- deliberately unaudited (V52, a derived "last seen" cache). NO INDEX: never a predicate.
-- NO POSTGRESQL EXTENSION is created.
--
-- VERSION NUMBERING. V76 is reserved for 37-05 (37-04 hand-off).

ALTER TABLE user_directory ADD COLUMN IF NOT EXISTS realm_admin_seen_at TIMESTAMPTZ;

COMMENT ON COLUMN user_directory.realm_admin_seen_at IS
    'First time this user was seen signing in to this tenant with the realm admin role (37-05, Pitfall 13). NULL means never observed with the role. An observation, not a live lookup; no backfill, no default.';

DO $$
BEGIN
    RAISE NOTICE 'V76 user_directory realm-admin observation applied: nullable realm_admin_seen_at '
        'added; no row was backfilled and no default was set.';
END $$;
