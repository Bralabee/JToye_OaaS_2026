---
phase: 28-security-triage-the-dev-prod-boundary
plan: 08
subsystem: database
tags: [postgres, rls, roles, ownership, grants, flyway, spring-boot, testcontainers, docker-compose, least-privilege]

# Dependency graph
requires:
  - phase: 28-07
    provides: "jtoye_runtime role + FOR ROLE-qualified grants (create-runtime-role.sql), Flyway credential decoupling (DB_MIGRATION_USER), config surfaces"
provides:
  - "Boot-time ownership fail-fast: the app refuses to start when its DB role owns the tables it reads, with a named, actionable reason (DatabaseConfigurationValidator.validateNotTableOwner)"
  - "RuntimeRoleGrantContractTest: the permanent future-table grant contract (FOR ROLE present -> readable; FOR ROLE omitted control -> not), non-DML (TRUNCATE/TEMPORARY), negative (owns nothing/no CREATE), and isolation-as-non-owner"
  - "IntegrationTestSupport.provisionRuntimeRoleFromShippedSql + locateRepoFile (additive; drives the shipped create-runtime-role.sql)"
  - "The LIVE dev stack now runs as the non-owner jtoye_runtime on freshly built images, with the ownership validator passing and isolation measured under a superuser control"
  - "e2e-nightly generator keeps the migrator role name jtoye_app and derives its password from DB_PASSWORD"
affects: [28-11]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Boot-time catalog fail-fast on pg_class.relowner = current_user::regrole, beside the existing superuser check (zero-tolerance)"
    - "Programmatic SpringApplicationBuilder boot under the DEFAULT profile to run a @Profile(\"!test\") validator while keeping @Profile(\"dev\") seeders inactive"
    - "Driving a SHIPPED operator SQL file through the container's psql (copyFileToContainer + execInContainer) so a Testcontainers contract asserts exactly what ships"
    - "FOR-ROLE-present vs FOR-ROLE-omitted control roles on ONE future table to record both directions of a default-privilege grant permanently"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/config/DatabaseConfigurationValidatorOwnershipTest.java
    - core-java/src/test/java/uk/jtoye/core/security/RuntimeRoleGrantContractTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/config/DatabaseConfigurationValidator.java
    - core-java/src/test/java/uk/jtoye/core/testsupport/IntegrationTestSupport.java
    - .github/workflows/e2e-nightly.yml

key-decisions:
  - "Zero-tolerance ownership check (> 0 owned public tables fails) — the honest reading of D-03; documented, no numeric slack"
  - "Ownership test boots under the DEFAULT (empty) profile, not test/dev — validator active, dev seeders inactive; the profile is the line that makes the class capable of failing"
  - "The nightly runs the app as jtoye_runtime (owner boot is now forbidden by the validator); a fresh-volume postcode_centroid TRUNCATE gap is left as a documented follow-up (needs a guarded migration — out of this plan's 3-task scope)"

patterns-established:
  - "A boot-time ownership guard makes a reverted-to-owner environment fail loudly instead of silently restoring the FORCE-dependence"
  - "The highest-value grant test is the future-table one; a grant test that only checks existing tables cannot fail for the reason that matters"

requirements-completed: [SEC-04]

# Metrics
duration: ~135min
completed: 2026-08-10
---

# Phase 28 Plan 08: Ownership fail-fast + the runtime-role split, proven and live Summary

**A boot-time ownership fail-fast beside the superuser check, the permanent future-table grant contract with a FOR-ROLE-omitted control, the isolation suite run as the non-owner role, and the LIVE dev stack repointed to jtoye_runtime on freshly built images — the app now refuses to boot as a table owner, and D-01's split is proven durable rather than just present.**

## Performance

- **Duration:** ~135 min
- **Started:** 2026-08-10T04:10:00Z (approx)
- **Completed:** 2026-08-10T06:25:00Z (approx)
- **Tasks:** 3 (+ the 28-07 e2e-nightly handoff)
- **Files modified:** 5 (2 created, 3 modified)

## Accomplishments
- Added `DatabaseConfigurationValidator.validateNotTableOwner()` — one `pg_class.relowner = current_user::regrole` catalog query, registered immediately after `validateNotSuperuser()`, zero-tolerance, with a message naming the role, the reason, the remedy and the three files to edit and no credential.
- `DatabaseConfigurationValidatorOwnershipTest` (2 tests): boots the whole app twice under the default profile — owner role → startup refused with the named reason; non-owner runtime role → starts. Falsified by neutralising the check AND by flipping the profile to `test`.
- `RuntimeRoleGrantContractTest` (5 tests): provisions the split in-container, drives the SHIPPED `create-runtime-role.sql`, and asserts the future-table grant (with a no-FOR-ROLE control), the non-DML grants, the negative contract, and isolation through a real `jtoye_runtime` connection.
- Extended `IntegrationTestSupport` additively (non-owner provisioning + repo-file locator + RLS-caveat javadoc) without touching the entry point 45 files depend on.
- Repointed the LIVE dev stack to `jtoye_runtime` on freshly rebuilt+recreated images; the validator logged its new ownership success line, the importer skipped cleanly, Flyway's migrator is `jtoye_app`, and `check-runtime-freshness.sh` is rc=0 (4/4 FRESH).
- Fixed the `e2e-nightly` generator so the migrator role NAME stays `jtoye_app` and `DB_MIGRATION_PASSWORD` derives from `DB_PASSWORD`.

## Task Commits

1. **Task 1: Boot-time ownership fail-fast + test** - `4a99c20a` (feat)
2. **Task 2: Future-table grant contract + isolation harness** - `f7f14c64` (test)
3. **Task 3: Apply the split to the live stack** - no commit (no tracked file — live DB role catalog, gitignored `.env`, rebuilt images; evidence below)
4. **e2e-nightly generator fix (28-07 handoff)** - `d3639d0c` (ci)

**Plan metadata:** committed with this SUMMARY.

## Files Created/Modified
- `core-java/src/main/java/uk/jtoye/core/config/DatabaseConfigurationValidator.java` - `validateNotTableOwner()` + registration (`relowner` count 0 → 2).
- `core-java/src/test/java/uk/jtoye/core/config/DatabaseConfigurationValidatorOwnershipTest.java` (new) - two-direction boot test, default profile.
- `core-java/src/test/java/uk/jtoye/core/security/RuntimeRoleGrantContractTest.java` (new) - the four contracts + isolation.
- `core-java/src/test/java/uk/jtoye/core/testsupport/IntegrationTestSupport.java` - `provisionRuntimeRoleFromShippedSql` + `locateRepoFile`; javadoc RLS caveat extended (NOSUPERUSER vs non-OWNER).
- `.github/workflows/e2e-nightly.yml` - `DB_MIGRATION_USER`/`DB_MIGRATION_PASSWORD` moved to DERIVED and pinned/derived.

## Recorded Evidence (per the plan's output spec)

### The six break arms (all run FAIL-direction, restores verified by content hash, clean asserted last)

1. **[Task 1 — the check] Neutralised `validateNotTableOwner()`** with `if (true) return;` → `ownerRoleRefusesStartupWithNamedReason` RED, `nonOwnerRoleStartsCleanly` still GREEN. Restored; `git hash-object` == clean `5171486e…`; clean re-run GREEN.
2. **[Task 1 — the profile] Flipped `ACTIVE_PROFILES` to `{"test"}`** → the `@Profile("!test")` validator is not created; owner arm RED (boot no longer refused) and the non-owner bean-presence assertion RED. Restored; `git hash-object` == clean `ebe8c117…`; clean re-run GREEN. This is the arm that proves the pass is produced by the validator running, not by a context that never checked.
3. **[Task 2 — THE ONE THAT MATTERS] Removed `FOR ROLE jtoye_app` from the SHIPPED `create-runtime-role.sql`** (`--rerun-tasks` to defeat the UP-TO-DATE trap) → `futureTableIsReadableByRuntime…` RED: `has_table_privilege('jtoye_runtime','future_probe','SELECT')` went **true → false**. Restored via `git checkout --`; `git hash-object` == HEAD `0ed4ce83…`; clean re-run GREEN.
4. **[Task 2 — permanent control] no-FOR-ROLE control role** on the SAME future table: `has_table_privilege('jtoye_runtime_noforrole','future_probe','SELECT') = false` while the real role is `true` — both directions recorded in one green run.
5. **[Task 2 — permanent controls] TRUNCATE + ownership** non-vacuity: control lacks `TRUNCATE` on `postcode_centroid` (`false` vs runtime `true`); a table reassigned to `jtoye_runtime` makes the owned-table count read exactly `1` before it is dropped back to `0` — the ownership query is not blind.
6. **[Task 3 — the runtime gate] Stopped `edge-go`** → `check-runtime-freshness.sh` rc=2 (VOID) naming it ("container 'jtoye-edge-go' is 'exited', not 'running'"); restarted → rc=0, PASS 4/4.

### Future-table `has_table_privilege` values (both directions)
- FOR ROLE **present** (shipped file): `jtoye_runtime` on a table created after the grants = **true**; no-FOR-ROLE control = **false**.
- FOR ROLE **omitted** (break arm on the shipped file): `jtoye_runtime` = **false** — the inert form that was live on `jtoye_backup` before #629.

### New test counts
- `DatabaseConfigurationValidatorOwnershipTest`: **2** tests (owner refused / non-owner starts) — green.
- `RuntimeRoleGrantContractTest`: **5** tests (future-table, non-DML, negative, isolation, shipped-path) — green.
- `relowner` in `DatabaseConfigurationValidator.java`: **0 → 2** (was 0 at HEAD).
- Full `integrationTest` count (additive-harness check): **after = 562** (measured on the merged branch — 127 classes, 0 failures, 0 errors, 1 skipped, `BUILD SUCCESSFUL in 18m`, executed-not-cached). The two new classes contribute **7** tests (2 + 5), giving a derived **before = 555** (after − 7); the before figure is arithmetic, not independently re-measured on this branch. The load-bearing regression signal is the **0 failures / 0 errors** — 28-07's role split plus this plan's boot validator broke none of the 120 pre-existing integration classes. `registerPostgresTestProperties` unchanged. (This is the `integrationTest`-only count; 28-06's combined `test`+`integrationTest` figure of 1650 is a different task set.)
- `docs/metrics.json` / prose counts: **NOT touched** — 28-11 reconciles the manifest (per Task 1/2 instruction).

### Live application switched to jtoye_runtime (Task 3)
- `create-runtime-role.sql` re-run against the LIVE dev DB with a fresh high-entropy password (openssl rand -hex 24, never a committed literal) — rc=0; `jtoye_runtime` authenticates (current_user=jtoye_runtime, owns 0 tables).
- `.env` (gitignored, machine-local): `DB_USER=jtoye_runtime` + new `DB_PASSWORD`; `DB_MIGRATION_USER=jtoye_app` and `DB_MIGRATION_PASSWORD` (= jtoye_app's password) left unchanged, so Flyway still migrates as the owner. Values redacted.
- Rebuilt + recreated: `docker compose … up -d --build --force-recreate core-java` → healthy. Running app role read out of the RUNNING process: `pg_stat_activity` shows **9 `jtoye_runtime`** connections (the Hikari pool), **0 `jtoye_app`** app connections.
- **Startup lines quoted** (from `docker logs`):
  - Validator: `Database username: jtoye_runtime` → `✅ User 'jtoye_runtime' is NOT a superuser (RLS will be enforced)` → `✅ Role 'jtoye_runtime' OWNS no public tables (isolation does not depend on FORCE being remembered)` → `✅ DATABASE SECURITY VALIDATION PASSED`.
  - Importer: `postcode_centroid already holds 1748230 rows, matching classpath:geo/SOURCE.md — skipping import` (no privilege error — the assertion a CRUD test cannot make).
  - Flyway migrator: `flyway_schema_history` `installed_by = jtoye_app` for V61/V60/V59; no startup ERROR/permission-denied.
- `scripts/check-runtime-freshness.sh` rc=0 after the rebuild — core-java tagged `2026-08-10 05:09:14 UTC` ≥ newest build-input commit `f7f14c64`; edge-go/frontend/mcp-server FRESH against their own inputs. `bash scripts/seed-order-metric.sh` PASS (the core-java rebuild resets the NoOrdersCreated counter; the HTTP 201 order placement also proves the app FUNCTIONS as jtoye_runtime under RLS, not merely boots).

### Live isolation arm on `products` (NOT `shops`), with the summing superuser control
Measured as `jtoye_runtime` (SET ROLE from the superuser subjects the session to RLS):

| arm | products |
|-----|----------|
| runtime, no tenant GUC | **0** |
| runtime, tenant A (`…0001`) | **47** |
| runtime, tenant B (`…0002`) | **4** |
| superuser control (RESET ROLE) | **51** |

`47 + 4 = 51` — the control proves the leading 0 is RLS, not a blind instrument. These numbers are **identical to plan 28-01's pre-split baseline (products 0 / 47 / 4 / 51)**, and that is the point: D-01 is a **durability fix**, not the closure of a live cross-tenant hole. The FILTERING criterion was already satisfiable on the owner role (all 36 tenant tables are ENABLE + FORCE); what this plan newly proves is the OWNERSHIP property and the future-table property. **The `shops` exception:** `shops` unpinned returns **3**, not 0 — it carries the permissive `shops_public_read` SELECT policy `(published = true) OR (tenant_id = current_tenant_id())` for the public storefront. That expected-3 is correct; an arm run on `shops` and "fixed" to 0 would break the storefront. Do NOT fix it.

## Decisions Made
- **Zero-tolerance ownership check** (any owned public table fails). D-03's exact wording; a numeric slack nobody can justify becomes a slack nobody dares tighten.
- **Default (empty) profile for the ownership test.** The validator is `@Profile("!test")`, so any non-`test` profile activates it; the empty default also keeps `DemoDataSeeder` (`@Profile("dev")`) inactive, so the boot exercises the validator without seeding. Command-line args (not `SpringApplicationBuilder.properties`, which are lowest-precedence and were overridden by `application.yml` — the first cause of a Flyway ConnectException) carry the container URL/roles.
- **Drive the shipped SQL through psql** rather than an inline copy, so the contract certifies what ships; a `@BeforeEach` once-guard (not `@BeforeAll`, which SpringExtension runs before the lazy context load) provisions after Flyway created `postcode_centroid`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] uuid-ossp must be pre-created for a NOSUPERUSER owner to run Flyway in Testcontainers**
- **Found during:** Task 1 (and reused in Task 2)
- **Issue:** V1 runs `CREATE EXTENSION IF NOT EXISTS "uuid-ossp"`, which a NOSUPERUSER owner cannot execute; in real environments the superuser pre-creates it (00-create-db.sql), so `IF NOT EXISTS` is a no-op.
- **Fix:** The tests pre-create the extension as the bootstrap superuser before provisioning the owner role — mirroring the live bootstrap.
- **Files modified:** the two new test classes (test-only).
- **Verification:** Flyway then migrates cleanly as the NOSUPERUSER owner; both suites green.
- **Committed in:** `4a99c20a`, `f7f14c64`.

## Issues Encountered
- **`SpringApplicationBuilder.properties()` is lowest-precedence**, so `application.yml` overrode the container datasource/flyway URLs and Flyway hit `localhost:5432` (ConnectException). Switched to command-line args (high precedence). Then the default-profile boot needed a real Redis (CacheConfig is `@Profile("!test")`), fixed with a Redis Testcontainer as `PublicRateLimitIntegrationTest` does.
- **`@BeforeAll` runs before the lazy Spring context** in `@SpringBootTest`, so `postcode_centroid` did not yet exist when the grant contract provisioned `jtoye_runtime`; moved to a `@BeforeEach` once-guard.
- **UP-TO-DATE trap:** editing the shipped `.sql` (not a Gradle task input) let `integrationTest` report BUILD SUCCESSFUL in 750ms without running; forced with `--rerun-tasks` for the FD1 arm.
- **Postgres was recreated** by `--force-recreate core-java` (it is a dependency in the graph); the named data volume persisted, so `jtoye_runtime` and all data survived (verified: role present, isolation numbers intact).

## Follow-ups (recorded, NOT silently dropped)

- **Fresh-volume `postcode_centroid` TRUNCATE for `jtoye_runtime` (28-07 handoff #1 part b).** On a FRESH compose/nightly volume, `00-create-db.sql` cannot grant `TRUNCATE ON postcode_centroid` (the table does not exist at cluster init), the FOR-ROLE default privileges grant only DML (not TRUNCATE), and `PostcodeCentroidImporter` TRUNCATEs on its first (empty-table) load — so core-java as `jtoye_runtime` would abort at boot, and the Task 1 validator now (correctly) forbids the `jtoye_app` fallback. The clean fix is a **guarded Flyway migration** `GRANT TRUNCATE ON postcode_centroid TO jtoye_runtime` wrapped in `DO $$ IF EXISTS (role) … $$` (idempotent; skips in Testcontainers where the role is absent), **or** having the importer TRUNCATE via the migration datasource. Both are out of this plan's 3-task scope (a new migration is a Rule 4 architectural change) and are untestable from here without a fresh volume — flagged for a sibling plan / a new issue. The generator half of the handoff (migrator name stays `jtoye_app`) IS shipped in `d3639d0c`. The live EXISTING-volume path is unaffected (`jtoye_runtime` already holds the TRUNCATE grant).

## Next Phase Readiness
- D-01's split is now durable (boot fail-fast) and proven both permanently (Testcontainers) and live (the dev stack runs as the non-owner on fresh images). 28-11 owns reconciling `docs/metrics.json` for the +7 tests.
- One follow-up flagged above (fresh-volume TRUNCATE via a guarded migration).

---
*Phase: 28-security-triage-the-dev-prod-boundary*
*Completed: 2026-08-10*
