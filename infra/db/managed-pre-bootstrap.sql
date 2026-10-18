-- managed-pre-bootstrap.sql — the roles a FRESH managed PostgreSQL needs before
-- core-java first boots (Phase 29 / DPLY-01; Blocker E; decisions D-10, D-25).
--
-- Run by scripts/staging-db-bootstrap.sh --stage pre, connected to database
-- jtoye as the server ADMIN (on Azure Database for PostgreSQL Flexible Server a
-- member of azure_pg_admin: a pseudo-superuser, NOT a superuser). Never by hand
-- with -v: every password arrives through the container environment and
-- \getenv, so none is ever on a command line.
--
-- WHY BEFORE THE FIRST BOOT
--   core-java's Flyway migrates as jtoye_app (DB_MIGRATION_USER) and its pool
--   connects as jtoye_runtime (DB_USER). DatabaseConfigurationValidator refuses a
--   pool user that owns any public table, and the DML-only runtime role cannot
--   migrate. Both roles, and the default privileges that hand every table Flyway
--   creates to jtoye_runtime, must therefore exist before the first migration.
--   infra/db/create-runtime-role.sql cannot run yet (its GRANT TRUNCATE ON
--   postcode_centroid needs V61's table); the post stage runs it afterwards.
--
-- WHY THE ADMIN GRANTS ITSELF MEMBERSHIP
--   PostgreSQL 16+ gives a CREATEROLE creator ADMIN OPTION on a role it creates,
--   but not SET. Handing an object to a role (ALTER … OWNER TO) and setting
--   default privileges FOR a role both require being able to SET ROLE to it, so
--   each hand-over below is preceded by GRANT <role> TO CURRENT_USER. The
--   memberships are KEPT: a re-run's ALTER statements need them, and removing
--   them would make this file non-idempotent. (Measured on postgres:18 with a
--   CREATEROLE non-superuser stand-in: without the grant, ALTER SCHEMA public
--   OWNER TO jtoye_app fails "must be able to SET ROLE".)
--
-- WHAT IS ASSERTED, NOT SET
--   Attributes are given at CREATE ROLE (NOSUPERUSER NOBYPASSRLS are the
--   defaults, named as documentation) and asserted at the end. They are not
--   re-applied by ALTER ROLE: a non-superuser may not name the SUPERUSER
--   attribute in ALTER ROLE at all, NOSUPERUSER included.
--
-- Idempotent: every statement is IF NOT EXISTS, a DO block, or a re-applicable
-- GRANT/ALTER. scripts/staging-db-bootstrap.sh --selftest (arm pre-idempotent)
-- runs it twice and diffs every place it writes.

\set ON_ERROR_STOP on

-- RED skeleton: passwords arrive as psql -v variables

SELECT current_database() = 'jtoye' AS in_jtoye \gset
\if :in_jtoye
\else
DO $$ BEGIN RAISE EXCEPTION 'managed-pre-bootstrap.sql must start connected to database jtoye'; END $$;
\endif

-- ---- jtoye_app: owner of schema public in jtoye, Flyway's migrator ----------
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jtoye_app') THEN
    CREATE ROLE jtoye_app LOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
END
$$;
ALTER ROLE jtoye_app WITH LOGIN PASSWORD :'app_password';

GRANT CONNECT, CREATE, TEMPORARY ON DATABASE jtoye TO jtoye_app;
-- The new owner must hold CREATE on the database, granted just above.
ALTER SCHEMA public OWNER TO jtoye_app;

-- uuid-ossp as the admin: Flyway runs as jtoye_app, which may not create
-- extensions on a managed server; V1's CREATE EXTENSION IF NOT EXISTS is then a
-- no-op. The extension must be allowlisted first (azure.extensions, set by
-- scripts/azure-staging-datastores.sh).
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ---- jtoye_runtime: DML only, subject to RLS ---------------------------------
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jtoye_runtime') THEN
    CREATE ROLE jtoye_runtime LOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
END
$$;
ALTER ROLE jtoye_runtime WITH LOGIN PASSWORD :'runtime_password';
GRANT CONNECT, TEMPORARY ON DATABASE jtoye TO jtoye_runtime;
GRANT USAGE ON SCHEMA public TO jtoye_runtime;
-- FOR ROLE jtoye_app is the whole point: Flyway creates every table AS
-- jtoye_app, and a default registered against the admin would cover none of
-- them (the defect create-runtime-role.sql documents).
ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO jtoye_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO jtoye_runtime;

-- ---- keycloak: owns database keycloak AND its public schema ------------------
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak') THEN
    CREATE ROLE keycloak LOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
END
$$;
ALTER ROLE keycloak WITH LOGIN PASSWORD :'keycloak_password';
ALTER DATABASE keycloak OWNER TO keycloak;

-- ---- the attribute assertions (cluster-wide, so checked once) ----------------
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles
             WHERE rolname IN ('jtoye_app', 'jtoye_runtime', 'keycloak')
               AND (rolsuper OR rolbypassrls)) THEN
    RAISE EXCEPTION 'jtoye_app, jtoye_runtime or keycloak is SUPERUSER or BYPASSRLS; RLS would not bind it';
  END IF;
END
$$;

-- Owning the database does not give its public schema to keycloak on a managed
-- server (it belongs to azure_pg_admin), and Keycloak creates its tables there on
-- first boot.
\connect keycloak
ALTER SCHEMA public OWNER TO keycloak;
