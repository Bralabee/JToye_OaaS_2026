# Plan 39-05 Summary: PostgreSQL 15 -> 18.6 Upgrade (`OPS-HORIZON-05`)

**Status:** Complete  
**Date:** 2026-10-16  
**Requirement:** `OPS-HORIZON-05`

## What Changed

1. **Compose, CI, Backup Image & Kubernetes Manifests (`postgres:18-alpine` & `pg-backup:18-blob`)**
   - `docker-compose.full-stack.yml` and `infra/docker-compose.yml`: upgraded `postgres` image to `postgres:18-alpine` and updated volume mounts from `/var/lib/postgresql/data` to `/var/lib/postgresql` per PostgreSQL 18+ Docker entrypoint (`PGDATA=/var/lib/postgresql/18/docker`; mounting at `/var/lib/postgresql/data` exits `rc=1` with `OLD_DATABASES+=( '/var/lib/postgresql/data (unused mount/volume)' )`).
   - `.github/workflows/ci-cd.yaml`: upgraded `integration-tests` job service container from `postgres:15` to `postgres:18-alpine`.
   - `infra/backups/Dockerfile`: upgraded builder/runtime base from `postgres:15-bookworm` to `postgres:18-bookworm` (`pg_dump 18.6` / `pg_restore 18.6`).
   - `k8s/base/pg-backup-cronjob.yaml`, `k8s/local/kustomization.yaml`, `k8s/goldens/staging.yaml`, `k8s/goldens/production.yaml`, `k8s/LOCAL.md`, `k8s/DEPLOYMENT.md`, `.github/workflows/e2e-nightly.yml`, `scripts/check-backup-restore-drill.sh`, `docs/runbooks/backups.md`: updated backup image tag from `ghcr.io/bralabee/jtoye-pg-backup:15-blob` to `:18-blob`.

2. **Testcontainers Suite Sweep & Major-Parity Gate (`207` sites + `RlsContractTest` guard)**
   - Updated all 207 `PostgreSQLContainer` sites across `core-java/src/test/java/**` (`206` `"postgres:15"` + `1` `"postgres:16-alpine"` in `AuditIntegrationTest.java`) to `"postgres:18-alpine"`.
   - Added `SERVER|core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java|PostgreSQLContainer<>("postgres:|representative Testcontainers RLS suite` to `scripts/gates/postgres-major-parity.conf` so Testcontainers major drift fails `scripts/check-postgres-major-parity.sh`.
   - Added `IntegrationTestSupport.ensureDowngradableTestRole(PostgreSQLContainer<?> postgres)` (and updated `ScheduledCleanupServiceIntegrationTest.java`) so that when `PostgreSQLContainer` initializes with `oid = 10` (`BOOTSTRAP_SUPERUSERID`), the bootstrap role is renamed to `jtoye_bootstrap_su` and `<username>` (`"test"`) is recreated as a non-bootstrap `SUPERUSER` (`oid > 10`) before Flyway runs. This preserves `ALTER ROLE "<test>" NOSUPERUSER` across all 31 RLS test classes under PostgreSQL 16+ (where commit `a0ffa885e` forbids removing `SUPERUSER` from `oid = 10`).

3. **Dependency Horizons & Documentation**
   - Updated `postgres` (`pin: "postgres:18-alpine"`, `eol_cycle: "18"`, `eol_date: "2030-11-14"`) and `jtoye-pg-backup` (`pin: "ghcr.io/bralabee/jtoye-pg-backup:18-blob"`) in `infra/dependency-horizons.yaml`.
   - Updated `CLAUDE.md`, `AGENTS.md`, `README.md`, `.planning/codebase/STACK.md`, `.planning/codebase/INTEGRATIONS.md`, `docs/architecture/ESSENTIAL_ARCHITECTURE.md`, `docs/architecture/SYSTEM_DESIGN_V2.md`, `docs/guides/DEPLOYMENT_GUIDE.md`, `docs/CHANGELOG.md`, and `.planning/ROADMAP.md`.

## Verification Evidence

- Full command outputs recorded in `.planning/phases/39-platform-runtime-upgrades/evidence/39-05-postgres18-upgrade.txt`:
  - `scripts/check-postgres-major-parity.sh`: fail-before-pass (`rc=1`, 2 violations) and pass (`rc=0`, 9/9 sites on major `18`).
  - Hermetic PG 15.19 -> PG 18.6 dump/restore drill using `ghcr.io/bralabee/jtoye-pg-backup:18-blob`: `0` row-count diffs across all seeded tables (`flyway_schema_history=84`, `tenants=3`, `shops=5`, `products=38`, `customers=8`, `orders=485`) and `0` policy diffs across all `68` RLS policies (`rc=0`).
  - Testcontainers RLS verification (`RlsContractTest`, `CustomerOriginShopScopeIntegrationTest`, `ShopSlugAliasIntegrationTest`, `ScheduledCleanupServiceIntegrationTest`): `BUILD SUCCESSFUL`, `rc=0`.
