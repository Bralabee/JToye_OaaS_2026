-- create-backup-role.sql — least-privilege BYPASSRLS dump role for backups (#90 P1-8).
--
-- WHY: the tenant tables use FORCE ROW LEVEL SECURITY, which applies RLS even to
-- the table owner. A pg_dump run as the application role (jtoye_app) with no
-- `app.tenant_id` GUC set therefore silently captures ZERO rows from every
-- tenant-scoped table. Proven against the live DB (2026-07-10):
--     as jtoye_app   (FORCE RLS, no tenant): SELECT count(*) FROM products -> 0
--     as jtoye_backup (BYPASSRLS)          : SELECT count(*) FROM products -> 25
--
-- The BYPASSRLS attribute can only be granted by a superuser, so this is an
-- operator bootstrap step run as the postgres superuser — NOT a Flyway migration
-- (the app migration role lacks the privilege). See docs/runbooks/backups.md.
--
-- USAGE (password injected, never hardcoded):
--   psql -U <superuser> -d jtoye \
--     -v backup_password="$(pass show jtoye/backup-role)" \
--     -f infra/backups/create-backup-role.sql
--
-- Idempotent: safe to re-run (updates the password + re-grants).

\set ON_ERROR_STOP on

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jtoye_backup') THEN
    CREATE ROLE jtoye_backup LOGIN BYPASSRLS;
  END IF;
END
$$;

-- Ensure the attributes + password are correct even on an existing role.
ALTER ROLE jtoye_backup WITH LOGIN BYPASSRLS PASSWORD :'backup_password';

-- Read-only: a backup role only needs to SELECT. No write/DDL privileges.
GRANT CONNECT ON DATABASE jtoye TO jtoye_backup;
GRANT USAGE ON SCHEMA public TO jtoye_backup;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO jtoye_backup;
-- pg_dump reads each sequence's last_value (SELECT last_value FROM <seq>), so the
-- role also needs SELECT on sequences — without this pg_dump fails with
-- "permission denied for sequence <name>_seq" (e.g. revinfo_seq). Verified live.
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO jtoye_backup;

-- Cover objects created after this runs, so future migrations stay dumpable.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO jtoye_backup;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON SEQUENCES TO jtoye_backup;
