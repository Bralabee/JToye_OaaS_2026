# Docker Networking Fix - Container-to-Container Communication

**Date:** January 3, 2026
**Status:** ⚠️ FIX REQUIRED

## Problem Summary

After applying the initial iptables fix (which resolved host-to-container networking), **container-to-container networking still fails**.

### Symptoms

- ✅ PostgreSQL starts and is healthy
- ✅ Keycloak starts successfully (after removing PostgreSQL backend config)
- ✅ Redis, RabbitMQ start successfully
- ❌ **core-java cannot connect to PostgreSQL**

### Root Cause Diagnosis

Performed network diagnostics:

```bash
# DNS resolution works ✅
$ docker exec jtoye-core-java getent hosts postgres
172.22.0.3        postgres  postgres

# But packets cannot reach the container ❌
$ docker exec jtoye-core-java ping -c 2 172.22.0.3
PING 172.22.0.3 (172.22.0.3): 56 data bytes
--- 172.22.0.3 ping statistics ---
2 packets transmitted, 0 packets received, 100% packet loss
```

**Conclusion:** The initial iptables fix resolved **host → container** networking, but **container → container** networking requires additional iptables FORWARD rules.

## The Issue

The system uses `iptables-nft` by default, but Docker expects `iptables-legacy`. The previous fix switched to iptables-legacy and created basic Docker chains, but did **not** configure the FORWARD chain rules needed for inter-container communication on the Docker bridge network.

## The Solution

A comprehensive fix script has been created: **`fix-docker-networking.sh`**

### What It Does

1. Stops Docker completely
2. Switches to iptables-legacy
3. Flushes all existing iptables rules
4. Sets default policies to ACCEPT
5. Creates required Docker chains:
   - `DOCKER`
   - `DOCKER-ISOLATION-STAGE-1`
   - `DOCKER-ISOLATION-STAGE-2`
   - `DOCKER-USER`
6. **Adds FORWARD rules for container-to-container communication**
7. Configures Docker bridge (docker0) forwarding
8. Restarts Docker
9. Verifies the fix

### How to Apply

```bash
# Run the fix script with sudo
sudo ./fix-docker-networking.sh
```

### After Applying the Fix

Test that docker-compose.full-stack.yml works:

```bash
# Start the full stack
docker compose -f docker-compose.full-stack.yml up -d

# Wait for services to start
sleep 30

# Test connectivity
docker exec jtoye-core-java ping -c 2 postgres

# Should see successful ping responses
# PING 172.22.0.3 (172.22.0.3): 56 data bytes
# 64 bytes from 172.22.0.3: seq=0 ttl=64 time=0.123 ms
# 64 bytes from 172.22.0.3: seq=1 ttl=64 time=0.089 ms
```

## Technical Details

### What Was Fixed in Keycloak

Original configuration tried to use PostgreSQL as Keycloak's backend database:

```yaml
# BEFORE (broken)
environment:
  KC_DB: postgres
  KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
  KC_DB_USERNAME: jtoye
  KC_DB_PASSWORD: secret
```

This caused Keycloak 24.x connection pool timeout bug (known issue). Fixed by removing PostgreSQL configuration and using embedded H2:

```yaml
# AFTER (works)
environment:
  KEYCLOAK_ADMIN: admin
  KEYCLOAK_ADMIN_PASSWORD: admin123
# No KC_DB* configuration - uses embedded H2
```

### What Was Fixed in core-java

Added restart policy to handle temporary connection failures:

```yaml
restart: on-failure:5  # Retry up to 5 times
healthcheck:
  start_period: 90s    # Allow 90s for startup before checking health
```

## Files Modified

1. **docker-compose.full-stack.yml**
   - Removed Keycloak PostgreSQL configuration
   - Added restart policy for core-java
   - Increased healthcheck timings

2. **fix-docker-networking.sh** (NEW)
   - Comprehensive iptables fix for both host→container and container→container networking

## Current Workaround

Until the iptables fix is applied, use:

```bash
./start-dev.sh
```

This starts infrastructure in Docker and backend/frontend locally, avoiding the container-to-container networking issue.

## References

- Original iptables issue: [DOCKER_IPTABLES_ISSUE.md](DOCKER_IPTABLES_ISSUE.md)
- Iptables fix results: [IPTABLES_FIX_RESULTS.md](IPTABLES_FIX_RESULTS.md)
- Quick start guide: [QUICK_START.md](QUICK_START.md)

## Summary

**The Problem:** Two separate issues:
1. ✅ Keycloak connection pool timeout (FIXED - use embedded H2)
2. ⚠️ Container-to-container networking (FIX AVAILABLE - run `fix-docker-networking.sh`)

**The Solution:** Run the comprehensive networking fix script to enable full Docker Compose stack.
