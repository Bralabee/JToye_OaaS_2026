# Plan 39-06 Summary: Redis 7.4 -> 8.10-alpine Upgrade (`OPS-HORIZON-06`)

**Status:** Complete  
**Date:** 2026-10-16  
**Requirement:** `OPS-HORIZON-06`

## What Changed

1. **Compose & Testcontainers Upgrade (`redis:8.10-alpine`)**
   - `docker-compose.full-stack.yml`: upgraded `redis` service image from `redis:7-alpine` to `redis:8.10-alpine` (preserving `--appendonly yes --requirepass` and healthcheck).
   - `core-java/src/test/java/**`: upgraded all 8 `GenericContainer<>(DockerImageName.parse("redis:7-alpine"))` sites (`CacheFormatIsolationIntegrationTest`, `TenantCacheEvictorIntegrationTest`, `RedisFaultInjectionIntegrationTest`, `IdempotencyDualLayerIntegrationTest`, `PublicRateLimitIntegrationTest`, `RateLimitDegradedModeIntegrationTest`, `TieredRateLimitIntegrationTest`, `TenantLifecycleNegativeTest`) to `"redis:8.10-alpine"`.

2. **Dependency Horizons & Documentation**
   - Updated `redis` row in `infra/dependency-horizons.yaml` to `pin: "redis:8.10-alpine"`, `eol_cycle: "8.10"`, `resolved_cycle: "8.10"`, `eol_date: "false"`.
   - Updated `CLAUDE.md`, `AGENTS.md`, `.planning/codebase/STACK.md`, `.planning/codebase/INTEGRATIONS.md`, `docs/architecture/ESSENTIAL_ARCHITECTURE.md`, `docs/architecture/SYSTEM_DESIGN_V2.md`, `docs/guides/DEPLOYMENT_GUIDE.md`, `docs/CHANGELOG.md`, and `.planning/ROADMAP.md`.

## Verification Evidence

- Full command outputs recorded in `.planning/phases/39-platform-runtime-upgrades/evidence/39-06-redis8-upgrade.txt`:
  - `rg -uu 'redis:7' docker-compose.full-stack.yml infra/ k8s/ core-java/src/test/java/` returns `0` matches.
  - `conda run -n jtoye-ops bash scripts/check-dependency-horizons.sh`: `OK redis pin=redis:8.10-alpine cycle=8.10 horizon=open-ended (eol=false)`, `violations=0`, `active-exemptions=0`, `rc=0`.
  - Testcontainers verification (`CacheFormatIsolationIntegrationTest`, `PublicRateLimitIntegrationTest`, `RedisFaultInjectionIntegrationTest`): `BUILD SUCCESSFUL`, `rc=0`.
