# Docker Full-Stack Status

**Date:** January 3, 2026
**Status:** ⚠️ PARTIALLY WORKING

## Current State

### ✅ What Works

1. **Local Development (./start-dev.sh)** - FULLY WORKING
   - Infrastructure (PostgreSQL, Keycloak) runs in Docker
   - Backend (Spring Boot) runs locally via Gradle
   - Frontend (Next.js) runs locally via npm
   - All services communicate successfully
   - **This is the recommended approach**

2. **Infrastructure Only (infra/docker-compose.yml)** - FULLY WORKING
   - PostgreSQL starts and accepts connections
   - Keycloak starts with embedded H2 database
   - Both services are healthy and accessible

### ❌ What Doesn't Work

**Full-Stack Docker Compose (docker-compose.full-stack.yml)** - FAILING

**Issue:** core-java container cannot connect to PostgreSQL container

**Error:**
```
java.net.SocketTimeoutException: Connect timed out
org.postgresql.util.PSQLException: The connection attempt failed.
org.flywaydb.core.internal.exception.FlywaySqlException: Unable to obtain connection from database
```

**Root Cause:** Docker networking issue - appears to be related to the iptables/nf_tables problem documented in DOCKER_IPTABLES_ISSUE.md

**Evidence:**
- PostgreSQL is healthy and accepts connections (verified with `pg_isready`)
- PostgreSQL works fine when accessed from host machine
- PostgreSQL works fine when infrastructure is started separately
- Only fails when core-java tries to connect from within Docker network

## Attempted Fixes

1. ✅ Removed Keycloak PostgreSQL configuration (use embedded H2)
2. ✅ Added restart policy for core-java (`restart: on-failure:3`)
3. ✅ Increased healthcheck timings
4. ✅ Added `start_period` to PostgreSQL healthcheck
5. ❌ Still fails - networking issue persists

## Diagnosis

The issue is **NOT**:
- Keycloak configuration (fixed by using embedded H2)
- Service startup timing (healthchecks work correctly)
- PostgreSQL readiness (verified healthy)

The issue **IS**:
- Docker networking between containers (core-java → postgres)
- Likely related to iptables/nf_tables incompatibility
- System-level Docker networking configuration

## Recommendation

### For Development
**Use `./start-dev.sh`**
```bash
./start-dev.sh
```

This approach:
- ✅ Starts all services in correct order
- ✅ Includes automatic healthchecks
- ✅ Provides clear console output
- ✅ Works reliably every time
- ✅ Logs to `logs/backend.log` and `logs/frontend.log`

### For Production
Use Kubernetes (k8s/) deployment which:
- Has proper networking configuration
- Works in production environment
- Is tested and verified

## Technical Details

### Working Configuration (infra/docker-compose.yml)
```yaml
keycloak:
  image: quay.io/keycloak/keycloak:24.0.5
  command: ["start-dev", "--import-realm"]
  environment:
    KEYCLOAK_ADMIN: admin
    KEYCLOAK_ADMIN_PASSWORD: admin123
  # No PostgreSQL configuration - uses embedded H2
```

### Failing Configuration (docker-compose.full-stack.yml)
```yaml
core-java:
  environment:
    DB_HOST: postgres      # Cannot connect even though on same network
    DB_PORT: 5432
    # ... other config
  depends_on:
    postgres:
      condition: service_healthy  # Health check passes
  networks:
    - jtoye-network        # Same network as postgres
```

## Related Issues

- [DOCKER_IPTABLES_ISSUE.md](DOCKER_IPTABLES_ISSUE.md) - Docker networking fix applied
- [IPTABLES_FIX_RESULTS.md](IPTABLES_FIX_RESULTS.md) - Verification of iptables fix

## Next Steps

1. Continue using `./start-dev.sh` for local development
2. Investigate iptables rules for container-to-container communication
3. Consider Docker daemon configuration (`/etc/docker/daemon.json`)
4. May need additional iptables rules for inter-container networking

## Conclusion

The `./start-dev.sh` approach works perfectly and is the recommended method for local development. The full Docker Compose stack has a networking issue that requires additional system-level debugging.

**Status:** Local development workflow is fully functional ✅
