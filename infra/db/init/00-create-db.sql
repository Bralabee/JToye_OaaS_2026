-- Initialize development database and application role
\connect postgres

-- Create role jtoye if it doesn't exist
-- Note: 'CREATE ROLE' can't be conditional easily without PL/pgSQL,
-- but we can use a helper or just ignore error if we use a script that continues.
DO $$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'jtoye') THEN
      CREATE ROLE jtoye LOGIN PASSWORD 'secret';
   END IF;
END$$;

-- CREATE DATABASE must be run outside of a transaction block
SELECT 'CREATE DATABASE jtoye OWNER jtoye'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'jtoye')\gexec

-- Create role jtoye_app if it doesn't exist (non-owner for RLS)
DO $$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'jtoye_app') THEN
      CREATE ROLE jtoye_app LOGIN PASSWORD 'secret';
   END IF;
END$$;

GRANT ALL PRIVILEGES ON DATABASE jtoye TO jtoye;
GRANT CONNECT ON DATABASE jtoye TO jtoye_app;

-- Optional: ensure uuid extension exists in target DB
\connect jtoye
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Grant usage on schema and all tables to jtoye_app
GRANT USAGE ON SCHEMA public TO jtoye_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO jtoye_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO jtoye_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO jtoye_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO jtoye_app;
