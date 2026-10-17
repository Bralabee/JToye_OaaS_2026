---
phase: 39-platform-runtime-upgrades
plan: 05
subsystem: database
tags: [postgresql-18, pg-backup, testcontainers, rls, flyway]
requires:
  - phase: 39-01
    provides: dependency-horizons baseline
provides:
  - PostgreSQL 18.6 (postgres:18-alpine) across compose, CI service container, and all 206 Testcontainers suites
  - Backup tooling image ghcr.io/bralabee/jtoye-pg-backup:18-blob (postgres:18-bookworm)
  - 10-site check-postgres-major-parity.sh enforcement including RlsContractTest.java and check-backup-restore-drill.sh
  - IntegrationTestSupport.ensureDowngradableTestRole non-bootstrap superuser swap (oid != 10) for PostgreSQL 18 NOSUPERUSER RLS tests
affects: [core-java, infra, k8s, ci-cd]
tech-stack:
  added: []
  patterns:
    - "PostgreSQL 18 volume mount at /var/lib/postgresql (honoring PGDATA=/var/lib/postgresql/18/docker)"
    - "Non-bootstrap superuser swap in IntegrationTestSupport.ensureDowngradableTestRole so ALTER ROLE ... NOSUPERUSER works on PostgreSQL 16+/18"
key-files:
  created:
    - .planning/phases/39-platform-runtime-upgrades/evidence/39-05-postgres18-upgrade.txt
  modified:
    - docker-compose.full-stack.yml
    - infra/docker-compose.yml
    - .github/workflows/ci-cd.yaml
    - infra/backups/Dockerfile
    - k8s/base/pg-backup-cronjob.yaml
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - scripts/check-backup-restore-drill.sh
    - scripts/gates/postgres-major-parity.conf
    - infra/dependency-horizons.yaml
    - core-java/src/test/java/uk/jtoye/core/testsupport/IntegrationTestSupport.java
decisions:
  - "Mount Postgres volume at /var/lib/postgresql in compose files so postgres:18-alpine's entrypoint uses /var/lib/postgresql/18/docker without tripping docker_error_old_databases."
  - "Swap the Testcontainers initdb bootstrap superuser (oid=10) with an identically named non-bootstrap superuser (oid>10) in IntegrationTestSupport.ensureDowngradableTestRole so all 30+ RLS integration tests can execute ALTER ROLE ... NOSUPERUSER on PostgreSQL 18 without weakening a single assertion."
metrics:
  completed_date: "2026-10-16"
---

# Phase 39 Plan 05: PostgreSQL 18.6 Runtime & Tooling Upgrade Summary

Upgraded PostgreSQL from `15` to `18.6` (`postgres:18-alpine` / `postgres:18-bookworm`) in lockstep across compose stacks, CI service containers, `jtoye-pg-backup:18-blob` backup tooling, K8s manifests/goldens, and all 206 Testcontainers integration test classes.

## Key Changes

1. **Runtime, CI & Backup Tooling Lockstep (`major = 18`)**:
   - Updated `docker-compose.full-stack.yml`, `infra/docker-compose.yml`, and `.github/workflows/ci-cd.yaml` to `postgres:18-alpine` (with `/var/lib/postgresql` volume target for PG 18's `/var/lib/postgresql/18/docker` layout).
   - Updated `infra/backups/Dockerfile` (`FROM postgres:18-bookworm`, build tag `ghcr.io/bralabee/jtoye-pg-backup:18-blob`), `k8s/base/pg-backup-cronjob.yaml`, `k8s/goldens/{staging,production}.yaml`, `scripts/check-backup-restore-drill.sh`, `.github/workflows/e2e-nightly.yml`, `k8s/local/kustomization.yaml`, and `k8s/LOCAL.md`.
   - Expanded `scripts/gates/postgres-major-parity.conf` from 8 to 10 sites by adding `RlsContractTest.java` and `scripts/check-backup-restore-drill.sh`.
2. **Testcontainers Suite & PostgreSQL 18 `NOSUPERUSER` Compatibility**:
   - Updated all 206 `PostgreSQLContainer` declarations in `core-java/src/test/java/` from `"postgres:15"` to `"postgres:18-alpine"`.
   - Added `IntegrationTestSupport.ensureDowngradableTestRole(PostgreSQLContainer<?>)` to replace the `initdb` bootstrap superuser (`oid = 10`, which PostgreSQL 16+ / 18 forbids downgrading via `ALTER ROLE ... NOSUPERUSER`) with an identically named non-bootstrap superuser (`oid > 10`) before the first JDBC connection opens.
3. **Hermetic Dump/Restore Drill**:
   - Built `ghcr.io/bralabee/jtoye-pg-backup:18-blob` (`pg_dump`/`pg_restore` `18.6`) and verified a custom-format logical dump from `postgres:15-alpine` (`15.19`) restores cleanly into `postgres:18-alpine` (`18.6`) with identical row counts and `ENABLE + FORCE ROW LEVEL SECURITY` policies (`evidence/39-05-postgres18-upgrade.txt`).
