#!/bin/bash
# Run the Spring Boot Core API locally (outside Docker) against the compose backing services.
#
# WHY THIS FILE LOOKS LIKE THIS (issue #449 / QA-A F-M8):
#
#   1. It used to `export DB_USER=jtoye`. `jtoye` is a PostgreSQL **superuser**, and
#      DatabaseConfigurationValidator (@Profile("!test"), ApplicationReadyEvent) throws
#      SecurityConfigurationException on a superuser because superusers BYPASS row-level
#      security. So the one documented "start the backend" command could not start the
#      backend. Measured: with DB_USER=jtoye the boot dies with
#      "CRITICAL SECURITY ERROR: Application is using PostgreSQL superuser 'jtoye'";
#      with DB_USER=jtoye_app it reaches "DATABASE SECURITY VALIDATION PASSED".
#
#   2. It also used to `export DB_PASSWORD=secret`. That literal no longer authenticates:
#      `psql -h localhost -p 5433 -U jtoye` returns
#      "FATAL: password authentication failed". It read as working for a long time only
#      because it was tested with `docker exec ... psql -h 127.0.0.1`, and the container's
#      pg_hba.conf has `host all all 127.0.0.1/32 trust` — from INSIDE the container every
#      password is accepted, so that check could not fail. Credentials now come from the
#      environment (GLOBAL_RULE_6: sourced from config, never a literal).
#
#   3. `core-java/.env` was documented as the thing to create and then read by nothing —
#      there is no dotenv dependency in core-java/build.gradle.kts and this script sourced
#      nothing. It is now genuinely sourced, so the documented `cp core-java/.env.example
#      core-java/.env` step has an effect.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

# Load config, least specific first, so a per-service file wins over the repo-wide one.
# The repo-root .env is the same file docker-compose.full-stack.yml and scripts/verify-env.sh use.
for envfile in "$ROOT/.env" "$ROOT/core-java/.env"; do
    if [ -f "$envfile" ]; then
        echo "Loading $envfile"
        set -a
        # shellcheck disable=SC1090
        . "$envfile"
        set +a
    fi
done

# Local-run defaults. Every one of these may be overridden by the env files above or by the
# caller's environment. DB_PASSWORD deliberately has NO default.
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5433}"
export DB_NAME="${DB_NAME:-jtoye}"
export DB_USER="${DB_USER:-jtoye_app}"
export SERVER_PORT="${SERVER_PORT:-9090}"

if [ -z "${DB_PASSWORD:-}" ]; then
    cat >&2 <<EOF
ERROR: DB_PASSWORD is not set.

  Set it in $ROOT/.env (the file the Docker stack and scripts/verify-env.sh already use),
  or in $ROOT/core-java/.env, or export it for this shell:

      cp .env.example .env      # then fill in the CHANGE_ME values
      export DB_PASSWORD=...

  No password is hardcoded here on purpose.
EOF
    exit 1
fi

if [ "$DB_USER" = "jtoye" ]; then
    cat >&2 <<EOF
ERROR: DB_USER is 'jtoye', which is a PostgreSQL superuser.

  Superusers BYPASS row-level security, so multi-tenant isolation would not be enforced.
  The application refuses to start with it (DatabaseConfigurationValidator). Use the
  application role instead:

      DB_USER=jtoye_app

EOF
    exit 1
fi

echo "Starting JToye OaaS Core API..."
echo "Database: postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME} as ${DB_USER}"
echo "Server will start on: http://localhost:${SERVER_PORT}"
echo ""
cd "$ROOT/core-java"
../gradlew bootRun
