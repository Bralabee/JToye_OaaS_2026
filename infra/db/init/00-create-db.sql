-- Initialize development database and application role
--
-- CREDENTIALS COME FROM THE ENVIRONMENT, NEVER FROM A LITERAL HERE.
--
-- Both roles below used to be created with a hardcoded `PASSWORD 'secret'`.
-- For `jtoye` that was merely dead code — the postgres entrypoint creates
-- POSTGRES_USER with POSTGRES_PASSWORD *before* running this directory, so the
-- IF NOT EXISTS guard always skipped it. For `jtoye_app` it was live: nothing
-- creates that role first, so on a FRESH VOLUME it really was created with
-- 'secret', and core-java — which connects as jtoye_app using DB_PASSWORD —
-- could only ever authenticate if DB_PASSWORD happened to be that literal.
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

-- Create role jtoye_app if it doesn't exist (non-owner for RLS).
-- This is the one that was actually broken: core-java connects as jtoye_app
-- with DB_PASSWORD, so the role must be created with DB_PASSWORD.
SELECT format('CREATE ROLE jtoye_app LOGIN PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'jtoye_app')\gexec

GRANT ALL PRIVILEGES ON DATABASE jtoye TO jtoye;
GRANT CONNECT ON DATABASE jtoye TO jtoye_app;

-- Optional: ensure uuid extension exists in target DB
\connect jtoye
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Grant usage and create on schema to jtoye_app (CREATE needed for Flyway migrations in PostgreSQL 15+)
GRANT USAGE, CREATE ON SCHEMA public TO jtoye_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO jtoye_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO jtoye_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO jtoye_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO jtoye_app;
