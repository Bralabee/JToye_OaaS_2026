---
phase: 28-security-triage-the-dev-prod-boundary
plan: 07
subsystem: database
tags: [postgres, rls, roles, grants, flyway, spring-boot, docker-compose, k8s, least-privilege]

# Dependency graph
requires:
  - phase: 28-05
    provides: prior security-triage groundwork this wave builds on
  - phase: 28-06
    provides: prior security-triage groundwork this wave builds on
provides:
  - "jtoye_runtime: a non-owner DML-only PostgreSQL role with an enumerated grant set (SQL bootstrap, two provisioning paths)"
  - "FOR ROLE-qualified default privileges so future migration tables are readable by the runtime (and backup) role without a further grant"
  - "Repair of the live jtoye_backup default-privileges defect (40/41 -> 41/41 readable), filed as #629"
  - "Flyway credential decoupled from the datasource via DB_MIGRATION_USER/DB_MIGRATION_PASSWORD, backward-compatible nested default proven both directions"
  - "The runtime/migrator credential pair declared on .env.example, compose, verify-env.sh, and the k8s secret template + QUICK_START recipe"
affects: [28-08, 28-11]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "ALTER DEFAULT PRIVILEGES FOR ROLE <owner> — future-object grants keyed to the role Flyway creates tables as, not the superuser running the bootstrap"
    - "Nested placeholder default ${A:${B}} for a backward-compatible credential indirection, proven from the RESOLVED config"
    - "verify-env cross-check: a half-applied role split (migrator unset, or pointed at the DML-only role) fails preflight loudly"

key-files:
  created:
    - infra/db/create-runtime-role.sql
    - core-java/src/test/java/uk/jtoye/core/integration/FlywayCredentialDecouplingTest.java
  modified:
    - infra/backups/create-backup-role.sql
    - infra/db/init/00-create-db.sql
    - core-java/src/main/resources/application.yml
    - .env.example
    - docker-compose.full-stack.yml
    - scripts/verify-env.sh
    - k8s/base/secrets-template.yaml.example
    - k8s/QUICK_START.md

key-decisions:
  - "Role name jtoye_runtime; new env keys DB_MIGRATION_USER / DB_MIGRATION_PASSWORD (LOCKED by this plan)"
  - "Nested-default fallback shape shipped (A3 verified), not per-profile explicit values"
  - "DatabaseConfigurationValidator ownership check and the live application switch deliberately left to 28-08"

patterns-established:
  - "FOR ROLE on ALTER DEFAULT PRIVILEGES is mandatory when the bootstrap runs as a different role than the table creator"
  - "A backward-compatible credential decoupling is proven, not assumed, by reading the resolved property both directions"

requirements-completed: [SEC-04]

# Metrics
duration: 75min
completed: 2026-08-10
---

# Phase 28 Plan 07: Non-owner runtime role + Flyway credential decoupling Summary

**jtoye_runtime non-owner DML role with FOR ROLE-qualified future-object grants, the live jtoye_backup default-privileges defect repaired (40/41 -> 41/41, filed #629), Flyway's credential decoupled via a proven-both-ways nested default, and the runtime/migrator pair declared on every config surface — the app not yet switched over (that is 28-08).**

## Performance

- **Duration:** ~75 min
- **Started:** 2026-08-10T03:03:00Z (approx)
- **Completed:** 2026-08-10T04:18:15Z
- **Tasks:** 3
- **Files modified:** 10 (9 plan `files_modified` + 1 new A3 test)

## Accomplishments
- Shipped `infra/db/create-runtime-role.sql`: an idempotent superuser bootstrap for `jtoye_runtime` (NOSUPERUSER NOBYPASSRLS, enumerated DML, TRUNCATE on `postcode_centroid` + TEMPORARY named with their `PostcodeCentroidImporter` reason, `ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app` for future objects).
- Repaired the already-live `jtoye_backup` `FOR ROLE` defect in the same commit and proved the fix against the live DB (readable tables 40 -> 41, denominator reached).
- Decoupled `spring.flyway.user/password` through `DB_MIGRATION_USER/DB_MIGRATION_PASSWORD`, proving RESEARCH assumption A3 (nested-default resolution) both directions and keeping the #517 dedicated Flyway DataSource intact under a break arm.
- Declared the runtime/migrator pair on `.env.example`, compose, `verify-env.sh` (with a half-applied-split cross-check), and the k8s secret template + `QUICK_START` recipe — without touching the running stack.

## Task Commits

1. **Task 1: Bootstrap the runtime role + repair jtoye_backup** - `978c46de` (feat)
2. **Task 2: Decouple Flyway credential + prove A3** - `65d3f830` (feat)
3. **Task 3: Declare the pair on every surface** - `7e34805e` (feat)

## Files Created/Modified
- `infra/db/create-runtime-role.sql` (new) - operator bootstrap for `jtoye_runtime`; enumerated grant set, `FOR ROLE jtoye_app` defaults.
- `infra/backups/create-backup-role.sql` - two `ALTER DEFAULT PRIVILEGES` lines gain `FOR ROLE jtoye_app`; comment records the measured defect.
- `infra/db/init/00-create-db.sql` - fresh-volume path also creates `jtoye_runtime` (DML + `FOR ROLE` defaults); documents the two-paths rule and the `postcode_centroid` TRUNCATE gap.
- `core-java/src/main/resources/application.yml` - `spring.flyway.user/password` indirected; `url` kept (#517); rationale comment extended.
- `core-java/src/test/java/uk/jtoye/core/integration/FlywayCredentialDecouplingTest.java` (new) - proves A3 both directions from the resolved config.
- `.env.example` - `DB_USER=jtoye_runtime`, `DB_MIGRATION_USER=jtoye_app`, `DB_MIGRATION_PASSWORD`, three-role comment block.
- `docker-compose.full-stack.yml` - `DB_MIGRATION_USER` (plain) + `DB_MIGRATION_PASSWORD` (`${VAR:?}`) on core-java.
- `scripts/verify-env.sh` - both keys in `REQUIRED_VARS`, a same-role coherence pair, a `(d2)` migrator-not-runtime-role check.
- `k8s/base/secrets-template.yaml.example` + `k8s/QUICK_START.md` - `runtime-username`/`runtime-password` added beside the existing pairs; recipes moved together.

## Recorded Evidence (per the plan's output spec)

### FOR ROLE grep counts (before/after)
- `create-runtime-role.sql`: `FOR ROLE jtoye_app` = **2** (tables + sequences).
- `create-backup-role.sql`: **before = 0** (git HEAD), **after = 3** (2 statements + 1 comment) — the after-count is evidence of the repair, not of a pattern that always matched.
- `TRUNCATE` = 7 (names `postcode_centroid`), `TEMPORARY` = 3, `ALL PRIVILEGES` = **0** (enumerated, not blanket).
- No literal password: `PASSWORD '<literal>'` = 0 in both new/repaired files; the single hit in `00-create-db.sql` is the **pre-existing historical comment on line 5** documenting the old bug, not a live literal. `:'..password'` grep matches (proving the grep can fire).

### jtoye_backup repair (live, with denominator + observed failure direction)
- **Before:** `has_table_privilege('jtoye_backup','postcode_centroid','SELECT')` = **f**; readable **40 of 41**; both `pg_default_acl` rows registered against the superuser `jtoye`.
- **A8 failure direction (verified, not assumed):** `SET ROLE jtoye_backup; COPY postcode_centroid TO STDOUT` — the exact per-table op pg_dump runs — aborted with `ERROR: permission denied for table postcode_centroid` (**rc=1**). pg_dump aborts the whole dump; it does NOT silently skip. Control (superuser COPY) returned a row (rc=0).
- **After:** `has_table_privilege` = **t**; readable **41 of 41** (= total); the same SET ROLE COPY now succeeds (rc=0). `jtoye_runtime` readable = 41.
- **FOR ROLE future-object proof:** a table created AS `jtoye_app` after the grants was SELECT/INSERT-able by `jtoye_runtime` with no further grant, TRUNCATE correctly **absent** (least-privilege control), and readable by `jtoye_backup` too. Probe table dropped; 0 remaining.
- **Idempotency:** `create-runtime-role.sql` run twice against live — both rc=0. **ON_ERROR_STOP** fail direction: an invalid statement aborted with rc=3.

### Issue
- **#629** OPEN — sanitized `jtoye_backup` defect, filed via `--body-file` (no interpolating string; stored body read back — "40 of 41", "FOR ROLE", "permission denied for table" all present). Records the ACTUAL observed pg_dump failure direction.

### A3 measurement + fallback shape
- **Shipped shape: the nested default** `${DB_MIGRATION_USER:${spring.datasource.username}}` (A3 held; per-profile explicit values NOT needed).
- `FlywayCredentialDecouplingTest` (3 tests, all executed/green in `build-local`): unset -> `spring.flyway.user` == `spring.datasource.username`; set (`DB_MIGRATION_USER=jtoye_app_migrator_probe`) -> `spring.flyway.user` == that value, datasource username not dragged along; `spring.flyway.url` still resolves to the datasource url.
- **Canary `FreshChainMigrationIntegrationTest`: 5/5 green, pre-existing assertions unchanged** (5 before, 5 after — I added a sibling class, not methods). Its boot-level `flywayGetsItsOwnDataSource...` and `stagingAndProdProfilesInheritTheFix` exercise the resolved `spring.flyway.user` end-to-end.

### #517 break arm
- Removing `spring.flyway.url` reddened exactly the three #517 guards (`stagingAndProdProfilesInheritTheFix`, `flywayGetsItsOwnDataSource...`, `sharingTheAppPoolWouldLeak...`), rc=1. Restored via `git checkout -- <named file>`; **content-hash verified**: restored `c3b0614f...` == committed blob. `application-staging.yml`/`application-prod.yml` untouched (not in the diff).

### verify-env break arm
- Clean `.env` (split keys) -> rc=0; unset `DB_MIGRATION_USER` while `DB_USER=jtoye_runtime` -> rc=1 naming `DB_MIGRATION_USER` ("Required variable DB_MIGRATION_USER is unset or empty"); restore -> rc=0. Wrong-role control (`DB_MIGRATION_USER=jtoye_runtime`) fires the `(d2)` check ("the DML-only runtime role ... needs CREATE"). `--list-required` lists both new keys (count 2). `docker compose config` rc=0. `.env.example` value-line trailing-comment count 0 (malformed copy = 1, grep proven falsifiable).

### No rebuild
- Running image IDs unchanged from plan start: core-java `sha256:c8e2b748...`, postgres `sha256:fceb6f86...`.

## Decisions Made
- Role name `jtoye_runtime`, keys `DB_MIGRATION_USER`/`DB_MIGRATION_PASSWORD` (locked by this plan; CONTEXT left them to discretion).
- Shipped the nested-default fallback (A3 verified) rather than per-profile explicit values.
- Left `username: jtoye_app` in the k8s secret template (framed as the owner/migrator) and ADDED `runtime-username`/`runtime-password` — matching the plan's framing and the literal acceptance keys; the Deployment env re-mapping is 28-08's.

## Deviations from Plan

None affecting scope. Two clarifications on the k8s surface, both within the plan's letter:
- The k8s secret template keeps `username: jtoye_app` (re-labelled owner/migrator) and adds `runtime-username`/`runtime-password`, per the plan's "jtoye_app is the OWNER ... jtoye_runtime cannot own anything" framing and the `grep -c runtime-username >= 1` acceptance. A `POSTGRES_RUNTIME_PASSWORD` export was added to the `QUICK_START` recipe so it does not reference an unset variable (the empty-secret trap).

**Total deviations:** 0 auto-fixes required. **Impact:** none; plan executed as written.

## Issues Encountered
- The live Postgres superuser is `jtoye`, not `postgres` (the plan's verify snippet used `-U postgres`). Ran all live psql arms as `-U jtoye`. No functional impact.

## Handoffs to Plan 28-08 (live application) and 28-11 (manifest)

These are in-scope for sibling plans, NOT defects introduced here. Recorded so they are not silently dropped:

1. **e2e-nightly generator (`.github/workflows/e2e-nightly.yml:121-133`) — 28-08 must update.** It `cp .env.example .env` then generates a random `ci<hex>` for every `--list-required` key not in its `DERIVED` list. With `DB_MIGRATION_USER` now required, the nightly would randomise it to a non-existent role name and Flyway would fail auth (a role NAME must stay `jtoye_app`, like `DB_USER` is kept out of the loop today). 28-08 (which owns the live application + rebuild, i.e. the nightly's fresh-stack build) must add `DB_MIGRATION_USER` to `DERIVED` and derive `DB_MIGRATION_PASSWORD=DB_PASSWORD` (mirroring the `KC_DB_PASSWORD` derivation), and ensure `jtoye_runtime` gets the `postcode_centroid` TRUNCATE grant on a fresh volume (create-runtime-role.sql run post-migration). NOT touched here: `e2e-nightly.yml` is not in this plan's `files_modified`, and it runs on `main`, so this branch does not reach it until the phase (incl. 28-08) merges.
2. **`DatabaseConfigurationValidator` boot-time ownership check + permanent Testcontainers isolation proof — 28-08's job.** Deliberately not pre-implemented here.
3. **`00-create-db.sql` fresh-volume `jtoye_runtime`** provisions role + DML + `FOR ROLE` defaults, but CANNOT grant `TRUNCATE ON postcode_centroid` (the table does not exist at cluster-init). Documented in-file; 28-08 runs `create-runtime-role.sql` after the first migration where the app is switched to `jtoye_runtime`.
4. **`docs/metrics.json` / prose test counts — 28-11 owns.** This plan adds one Java test class (3 `@Test`); per Task 2's instruction I did NOT touch `docs/metrics.json` or the prose counts. 28-11 reconciles the manifest before the phase merges.

## Next Phase Readiness
- The durable half (role, grants, config surfaces) is in place and proven. 28-08 can switch the live application to `jtoye_runtime`, add the boot ownership validator, and run the isolation arm.
- The live dev DB now carries the durable backup-role repair and a provisioned (unused) `jtoye_runtime` role with a throwaway password; 28-08 re-runs `create-runtime-role.sql` with the real password before the app connects as it.

## Self-Check: PASSED

- Created files verified present: `infra/db/create-runtime-role.sql`, `FlywayCredentialDecouplingTest.java`, `28-07-SUMMARY.md`.
- Task commits verified in git: `978c46de`, `65d3f830`, `7e34805e`.

---
*Phase: 28-security-triage-the-dev-prod-boundary*
*Completed: 2026-08-10*
