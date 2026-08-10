-- create-runtime-role.sql — non-owner DML runtime role for the application
-- (SEC-04 / #552, D-01). The durable half of the dev/prod boundary.
--
-- WHY: every tenant table uses ENABLE + FORCE ROW LEVEL SECURITY, so the owner
-- (jtoye_app) is ALREADY subject to RLS today. Isolation therefore does not have
-- a live hole here — measured 2026-08-10, jtoye_app with no tenant GUC reads 0
-- rows from `products`. What it HAS is a fragile dependency: isolation holds only
-- as long as FORCE is remembered on EVERY future table, because a table a
-- migration forgets to FORCE would be fully readable by its owner. jtoye_runtime
-- cannot own anything, so it can never be the role that ownership exempts. That
-- is the durable fix D-01 asks for: isolation stops depending on FORCE being
-- remembered on every new table.
--
-- NOSUPERUSER and NOBYPASSRLS are PostgreSQL's CREATE ROLE defaults and are the
-- whole point of this role — it MUST be subject to RLS. They are asserted
-- explicitly below rather than granted; granting either would defeat the split.
--
-- Flyway keeps jtoye_app as owner/migrator (it holds CREATE on schema public and
-- runs the migration chain). The application's Hikari pool connects as
-- jtoye_runtime. spring.flyway.user is decoupled through DB_MIGRATION_USER
-- (application.yml) so pointing the app at jtoye_runtime does NOT move Flyway
-- with it — a shared credential would send Flyway to a role with no CREATE and
-- migrations would fail on a fresh database.
--
-- This is an operator bootstrap run as the postgres superuser, NOT a Flyway
-- migration: jtoye_app has no CREATEROLE, and the migration chain must stay
-- replayable on every existing database.
--
-- TWO PROVISIONING PATHS, ONLY ONE FIRES ON ANY GIVEN MACHINE:
--   * FRESH volume    -> infra/db/init/00-create-db.sql creates the role at
--                        cluster init (before Flyway), so a clean
--                        `docker compose up` already has it.
--   * EXISTING volume -> that init script does NOT re-run on a populated data
--                        directory, so run THIS file by hand once, AFTER the
--                        first migration (postcode_centroid must exist for the
--                        TRUNCATE grant below). This is the common case on any
--                        long-lived dev or deployed database.
-- A reader who knows only one path silently skips provisioning on the common one.
--
-- USAGE (password injected, never hardcoded):
--   psql -U <superuser> -d jtoye \
--     -v runtime_password="$(pass show jtoye/runtime-role)" \
--     -f infra/db/create-runtime-role.sql
--
-- Idempotent: safe to re-run (updates the password + re-grants).

\set ON_ERROR_STOP on

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jtoye_runtime') THEN
    -- NOSUPERUSER NOBYPASSRLS are the defaults; naming them here is documentation,
    -- not a change of behaviour. Not granting them is the entire point of the role.
    CREATE ROLE jtoye_runtime LOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
END
$$;

-- Correct the attributes + password even on an existing role. A role accidentally
-- created SUPERUSER or BYPASSRLS is brought back into line by a re-run.
ALTER ROLE jtoye_runtime WITH LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'runtime_password';

-- ---- database- and schema-level --------------------------------------------
GRANT CONNECT ON DATABASE jtoye TO jtoye_runtime;
-- TEMPORARY: PostcodeCentroidImporter.java:141 runs CREATE TEMP TABLE for its
-- staging load. PUBLIC holds TEMPORARY by default today, but relying on a default
-- a future hardening pass may REVOKE is fragile — grant it to the role explicitly.
GRANT TEMPORARY ON DATABASE jtoye TO jtoye_runtime;
GRANT USAGE ON SCHEMA public TO jtoye_runtime;

-- ---- existing objects ------------------------------------------------------
-- DML only. Deliberately NOT `GRANT ALL`: that would add TRUNCATE (over-broad),
-- REFERENCES and TRIGGER, and read as "the same privileges the owner has" —
-- defeating the split. [postgresql.org/docs/15/sql-grant.html]
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO jtoye_runtime;
-- TRUNCATE is a DISTINCT privilege, NOT implied by DELETE. PostcodeCentroidImporter
-- .java:162 runs `TRUNCATE postcode_centroid` on its (re)load path. The importer is
-- idempotent — it skips when the row count already matches the manifest
-- (PostcodeCentroidImporter.java:128-134) — so the TRUNCATE path fires precisely on
-- a fresh or short table: first boot, and after any dataset change, which is exactly
-- when a new deployment happens. postcode_centroid is public reference data (no
-- tenant_id, no RLS), so a table-scoped TRUNCATE here is safe; a blanket TRUNCATE
-- would let the DML-only app wipe any tenant table, which is why it is named.
GRANT TRUNCATE ON postcode_centroid TO jtoye_runtime;
-- Envers writes a revision row per transaction from revinfo_seq; Hibernate reads
-- and advances sequences. USAGE, SELECT, UPDATE mirrors jtoye_app's own sequence set.
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO jtoye_runtime;

-- ---- FUTURE objects — the FOR ROLE clause is the whole point ----------------
-- ALTER DEFAULT PRIVILEGES with NO `FOR ROLE` registers the defaults against the
-- CURRENT role (the superuser running this file), NOT against jtoye_app. Flyway
-- creates every table AS jtoye_app, so a superuser-registered default applies to
-- NOTHING Flyway makes, and the role silently loses access to the first table a
-- future migration adds — the app boots fine today and dies on that table.
--
-- THIS IS NOT HYPOTHETICAL. The identical omission is LIVE in this repo today on
-- jtoye_backup (infra/backups/create-backup-role.sql, repaired in the SAME commit
-- as this file): measured 2026-08-10, jtoye_backup could SELECT 40 of 41 tables —
-- the one it could not read was postcode_centroid, the newest table, created by
-- V61 as jtoye_app after the backup script last ran, so a pg_dump as that role
-- aborts with "permission denied for table postcode_centroid".
ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO jtoye_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO jtoye_runtime;

-- The live application is NOT repointed at jtoye_runtime by this file. Plan 28-08
-- owns switching DB_USER, the rebuild, the boot-time ownership validator, and the
-- permanent Testcontainers isolation proof.
