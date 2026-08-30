-- Initialize development database and application role
--
-- CREDENTIALS COME FROM THE ENVIRONMENT, NEVER FROM A LITERAL HERE.
--
-- Both roles below used to be created with a hardcoded `PASSWORD 'secret'`.
-- For `jtoye` that was merely dead code — the postgres entrypoint creates
-- POSTGRES_USER with POSTGRES_PASSWORD *before* running this directory, so the
-- IF NOT EXISTS guard always skipped it. For `jtoye_app` it was live: nothing
-- creates that role first, so on a FRESH VOLUME it really was created with
-- 'secret', and the service authenticating as jtoye_app could only ever
-- succeed if its configured password happened to be that literal.
--
-- WHO CONNECTS AS WHAT, since the SEC-04/#552 runtime-migrator split:
--   jtoye_app     — the owner/MIGRATOR. Flyway authenticates with
--                   DB_MIGRATION_USER/DB_MIGRATION_PASSWORD (application.yml).
--   jtoye_runtime — the non-owner DML role the APPLICATION runs as
--                   (DB_USER/DB_PASSWORD).
-- Issue #684: this file created jtoye_app with DB_PASSWORD, so wherever the two
-- credentials differ, a fresh volume 28P01 crash-looped core-java with ZERO
-- migrations applied — invisible on long-lived volumes (the role predates the
-- split there) and in any environment that generates the two equal, which is
-- exactly what e2e-nightly.yml's DERIVED block did as a workaround.
--
-- That is why e2e-nightly.yml failed every night: it generates a random
-- DB_PASSWORD per run, so jtoye_app's actual password and the one core-java
-- presents could never match. It is also why a long-lived local volume drifts
-- from what this repo produces — the role there was altered out of band, so
-- `docker compose up` on a clean machine did not reproduce a working stack.
--
-- `\getenv` requires psql >= 14; this image is postgres:15-alpine. The value is
-- interpolated through format(%L), which quotes it as a literal, so a password
-- containing quotes cannot terminate the statement.
\connect postgres

\getenv superuser_password POSTGRES_PASSWORD
\getenv app_password DB_PASSWORD

-- Migrator credential, with a single-credential fallback (#684). \getenv leaves
-- the psql variable UNCHANGED when the env var is absent, so the pre-set empty
-- string is the sentinel for both "unset" and "set but empty"; the NULLIF/
-- COALESCE at the use site folds either case back to DB_PASSWORD, so an
-- environment that has not adopted the SEC-04 split behaves exactly as before.
\set migration_password ''
\getenv migration_password DB_MIGRATION_PASSWORD

-- Create role jtoye if it doesn't exist.
-- Unreachable in practice (see above) but kept correct rather than hardcoded:
-- if it ever DID fire, a literal here would create the role with a password no
-- service knows, and the failure would look exactly like the one this fixes.
SELECT format('CREATE ROLE jtoye LOGIN PASSWORD %L', :'superuser_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'jtoye')\gexec

-- CREATE DATABASE must be run outside of a transaction block
SELECT 'CREATE DATABASE jtoye OWNER jtoye'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'jtoye')\gexec

SELECT 'CREATE DATABASE keycloak OWNER jtoye'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak')\gexec

-- Create role jtoye_app if it doesn't exist — the owner/MIGRATOR since the
-- SEC-04/#552 split. Flyway authenticates as it with DB_MIGRATION_PASSWORD, so
-- that is the password the role must be created with (#684); the previous text
-- here ("core-java connects as jtoye_app with DB_PASSWORD") described the
-- pre-split world and was the defect. Falls back to DB_PASSWORD when
-- DB_MIGRATION_PASSWORD is unset or empty, so single-credential setups keep
-- working unchanged.
SELECT format('CREATE ROLE jtoye_app LOGIN PASSWORD %L',
              coalesce(nullif(:'migration_password', ''), :'app_password'))
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'jtoye_app')\gexec

-- Create role jtoye_runtime — the non-owner DML application role (SEC-04 / #552,
-- D-01). This is the FRESH-VOLUME provisioning path; it mirrors the operator
-- bootstrap infra/db/create-runtime-role.sql, which is the EXISTING-VOLUME path.
-- This init directory runs ONLY on an empty data directory, so a long-lived dev
-- volume never sees these lines and needs create-runtime-role.sql run by hand.
-- Only one of the two paths fires on any given machine.
--
-- Created with DB_PASSWORD: the split points the application's DB_USER at
-- jtoye_runtime, and the app authenticates with DB_PASSWORD. jtoye_app above stays
-- the owner/migrator; Flyway reaches it via DB_MIGRATION_USER/DB_MIGRATION_PASSWORD
-- (application.yml), which default to the datasource values so an environment that
-- has not adopted the split behaves exactly as before.
SELECT format('CREATE ROLE jtoye_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'jtoye_runtime')\gexec

GRANT ALL PRIVILEGES ON DATABASE jtoye TO jtoye;
GRANT CONNECT ON DATABASE jtoye TO jtoye_app;
GRANT CONNECT, TEMPORARY ON DATABASE jtoye TO jtoye_runtime;

-- Optional: ensure uuid extension exists in target DB
\connect jtoye
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Grant usage and create on schema to jtoye_app (CREATE needed for Flyway migrations in PostgreSQL 15+)
GRANT USAGE, CREATE ON SCHEMA public TO jtoye_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO jtoye_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO jtoye_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO jtoye_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO jtoye_app;

-- jtoye_runtime (SEC-04 / #552, D-01): non-owner DML role. USAGE only on the
-- schema — NO CREATE, which stays jtoye_app's (the migrator). The object-level
-- grants below cover tables that EXIST at init time (none yet on a fresh volume);
-- ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app covers everything Flyway creates
-- afterward. FOR ROLE is load-bearing: without it the defaults register against
-- the superuser running this init and cover nothing Flyway makes — the exact
-- defect repaired on jtoye_backup in infra/backups/create-backup-role.sql.
GRANT USAGE ON SCHEMA public TO jtoye_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO jtoye_runtime;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO jtoye_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO jtoye_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO jtoye_runtime;
-- NOTE: TRUNCATE on postcode_centroid (PostcodeCentroidImporter.java:162) is NOT
-- grantable here — that table is created later by Flyway (V61), so it does not yet
-- exist at cluster-init time, and a table-named grant cannot forward-reference it.
-- It is granted by MIGRATION V64 instead, which runs as jtoye_app (the table owner)
-- after V61 has created it. Do NOT add it to the ALTER DEFAULT PRIVILEGES above:
-- that would give the DML-only application TRUNCATE on every table Flyway ever
-- creates, tenant tables included, which is the whole thing the split prevents.
--
-- This used to read "run infra/db/create-runtime-role.sql once after the first
-- migration". That manual step is what #647 was: e2e-nightly.yml tears down with
-- `down -v`, so every night began on a fresh volume where nobody had run it, and
-- core-java crash-looped on `permission denied for table postcode_centroid` for 14
-- consecutive nights without executing a single Playwright test. A provisioning step
-- that only a human can perform is not provisioning. create-runtime-role.sql keeps
-- the grant for operators re-running it against an existing cluster; V64 is what
-- makes a fresh deployment work unattended.
