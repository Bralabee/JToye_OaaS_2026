-- V64: give jtoye_runtime TRUNCATE on postcode_centroid, from the schema itself.
--
-- WHY THIS EXISTS (#647). The nightly full-stack E2E failed 14 consecutive nights,
-- 2026-08-11 to 2026-08-24, and never ran a single Playwright test. core-java
-- crash-looped roughly every 27 seconds on:
--
--     ERROR:  permission denied for table postcode_centroid
--     STATEMENT:  TRUNCATE postcode_centroid
--       at uk.jtoye.core.geo.PostcodeCentroidImporter.importIfNeeded
--
-- Each restart reset the container's health clock, so it never became healthy and
-- compose aborted every service declaring depends_on: core-java: service_healthy.
--
-- THE GAP. Since the SEC-04 / #552 runtime-migrator split (Phase 28, #630) the app
-- connects as jtoye_runtime, a DML-only role. TRUNCATE is a DISTINCT privilege and
-- is NOT implied by DELETE. infra/db/init/00-create-db.sql grants that role
-- SELECT/INSERT/UPDATE/DELETE and registers matching ALTER DEFAULT PRIVILEGES, but
-- it cannot name postcode_centroid: the table is created later, by V61, so at
-- cluster-init time there is nothing to grant on. Its own comment therefore says to
-- "run infra/db/create-runtime-role.sql once after the first migration" -- a MANUAL,
-- out-of-band step. e2e-nightly.yml tears down with `down -v` and gets a fresh
-- volume every night, so that step never ran and the grant never existed.
--
-- Measured 2026-08-24 on a throwaway Postgres driving the real init script, then
-- creating the table as jtoye_app exactly as V61 does:
--   grants jtoye_runtime actually receives  ->  DELETE,INSERT,SELECT,UPDATE
--   TRUNCATE postcode_centroid              ->  rc=1 permission denied
--   SELECT / DELETE on the same table       ->  rc=0, rc=0
-- The role is otherwise correctly provisioned; only TRUNCATE is missing.
--
-- WHY A MIGRATION, AND NOT THE TWO ALTERNATIVES.
--   * Widening the init script's ALTER DEFAULT PRIVILEGES to include TRUNCATE would
--     hand the DML-only application TRUNCATE on EVERY table Flyway ever creates,
--     including every tenant table. create-runtime-role.sql already rejects that in
--     writing, and it is the whole point of the split.
--   * Changing the importer to DELETE would work on privileges but rewrites
--     1,748,230 rows instead of truncating, and leaves the table bloated.
-- A table-scoped grant that travels WITH the schema removes the manual step without
-- widening the role by one privilege more than the importer needs. postcode_centroid
-- is public reference data -- no tenant_id, no RLS -- so a TRUNCATE on this table
-- alone carries no cross-tenant risk.
--
-- THE ROLE GUARD IS LOAD-BEARING, not defensive dressing. Testcontainers integration
-- tests migrate against a bare Postgres where jtoye_runtime does not exist; an
-- unguarded GRANT would fail with "role does not exist" and red every one of them.
-- Skipping is correct there: no runtime role means no split to grant for.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jtoye_runtime') THEN
        EXECUTE 'GRANT TRUNCATE ON postcode_centroid TO jtoye_runtime';
        RAISE NOTICE 'V64: granted TRUNCATE on postcode_centroid to jtoye_runtime';
    ELSE
        RAISE NOTICE 'V64: role jtoye_runtime absent (no runtime/migrator split here) - nothing to grant';
    END IF;
END
$$;
