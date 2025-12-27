-- Initialize development database and application role
\connect postgres

DO $$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'jtoye') THEN
      CREATE ROLE jtoye LOGIN PASSWORD 'secret';
   END IF;
END$$;

DO $$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_database WHERE datname = 'jtoye') THEN
      CREATE DATABASE jtoye OWNER jtoye;
   END IF;
END$$;

GRANT ALL PRIVILEGES ON DATABASE jtoye TO jtoye;

-- Optional: ensure uuid extension exists in target DB
\connect jtoye
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
