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

GRANT ALL PRIVILEGES ON DATABASE jtoye TO jtoye;

-- Optional: ensure uuid extension exists in target DB
\connect jtoye
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
