---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 17
subsystem: database
tags: [flyway, postgres, rls, migration, testcontainers, shop-staff, vendor-scoped-access]

# Dependency graph
requires:
  - phase: 23-14
    provides: "V57 shop_staff.grant_source column + backfill (the migration whose bare-UPDATE backfill this plan fixes)"
  - phase: 23-01
    provides: "V52 shop_staff (ENABLE+FORCE RLS via current_tenant_id()) — the FORCE-RLS table the backfill must reach"
provides:
  - "RLS-safe V57 grant_source backfill — a per-tenant set_config GUC loop (mirrors V44) that reaches every pre-V57 shop_staff row under FORCE RLS instead of zero rows"
  - "V57 applies cleanly on a NON-fresh DB (pre-existing shop_staff rows) — the canonical Compose dev DB / staging / prod scenario that would otherwise brick boot at SET NOT NULL"
  - "V57GrantSourceBackfillIntegrationTest — stepwise-Flyway regression proof under a NOSUPERUSER RLS-bound migrator role, across two tenants (RED against the bare UPDATE, GREEN after)"
affects: [phase-23-pr, phase-24-media-asset, deploy]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Flyway migration-behaviour test applying the migration-under-test as a dedicated NOSUPERUSER NOBYPASSRLS owner role (mirrors jtoye_app), so a FORCE-RLS backfill is genuinely enforced — the container superuser bypasses RLS and would mask the bug"
    - "RLS-safe migrate-time backfill on a FORCE-RLS table = walk the RLS-free tenants registry + set_config('app.current_tenant_id', t.id, true) per tenant (V44/V25 defect-class remedy)"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/security/access/V57GrantSourceBackfillIntegrationTest.java
  modified:
    - core-java/src/main/resources/db/migration/V57__shop_staff_grant_source.sql

key-decisions:
  - "Only V57 step 2 (the backfill) changed; steps 1/3/4 (ADD COLUMN, CHECK/DEFAULT/NOT NULL, _aud mirror) are byte-for-byte unchanged and NO RLS policy was added or altered (RlsContractTest stays green)"
  - "The regression test applies V57 as a NOSUPERUSER owner role, not the container superuser — otherwise the bare UPDATE would see every row and the test would be false-green"
  - "Editing V57 changes its Flyway checksum; safe here (V57 is new on this branch, only ever run in ephemeral Testcontainers) — a dev who already booted a local DB on this branch needs a one-time `flyway repair`"

patterns-established:
  - "Non-fresh-DB migration proof: stepwise Flyway (target N-1 -> seed via superuser bypassing RLS -> target N under an RLS-bound role) to exercise what fresh Testcontainers DBs never do"

requirements-completed: [VSA-02]

# Metrics
duration: ~65min
completed: 2026-07-21
---

# Phase 23 Plan 17: RLS-Safe V57 grant_source Backfill Summary

**Fixed a confirmed deployment blocker: V57's `grant_source` backfill was a bare no-GUC `UPDATE` that sees ZERO rows under the FORCE-RLS `shop_staff` table when Flyway runs as the RLS-bound `jtoye_app` role, so `SET NOT NULL` then bricks boot on any DB with pre-existing rows. Rewrote it as V44's per-tenant `set_config` loop and added a two-tenant stepwise-Flyway regression test that reproduces the exact failure (RED → GREEN) under a NOSUPERUSER migrator role.**

## Performance

- **Duration:** ~65 min (dominated by the full `:core-java:integrationTest` run — 34m 2s)
- **Started:** 2026-07-21T13:45:00Z (approx)
- **Completed:** 2026-07-21T14:50:00Z (approx)
- **Tasks:** 3 (2 code tasks committed + 1 verification gate)
- **Files modified:** 2 (1 migration rewritten, 1 test created)

## Accomplishments

- **V57 backfill is now RLS-safe.** Step 2's bare `UPDATE shop_staff SET grant_source = ... WHERE grant_source IS NULL` (invisible under FORCE RLS to the RLS-bound migration role → 0 rows → later `SET NOT NULL` fails) is replaced by a `FOR t IN SELECT id FROM tenants LOOP … set_config('app.current_tenant_id', t.id::text, true) … UPDATE … WHERE tenant_id = t.id AND grant_source IS NULL … END LOOP` block with a defensive `set_config('', true)` reset — mirroring V44:69–133 exactly in idiom. The `created_by IS NULL → 'JIT' ELSE 'OPERATOR'` inference and the idempotent `WHERE grant_source IS NULL` guard are preserved verbatim.
- **The blind spot Testcontainers missed is now a committed regression test.** `V57GrantSourceBackfillIntegrationTest` migrates to V56, seeds pre-V57 `shop_staff` rows across TWO tenants (superuser insert, bypassing RLS), then applies V57 as a `NOSUPERUSER NOBYPASSRLS` `rls_migrator` role that OWNS the two `shop_staff` tables (so it can run V57's ALTERs while staying subject to FORCE RLS on the backfill). It asserts every seeded row is backfilled with the correct JIT/OPERATOR value for BOTH tenants.
- **No collateral change.** Steps 1/3/4 unchanged; no RLS policy added/altered; the full `:core-java:integrationTest` task is green (332/0), including `RlsContractTest.noPolicyUsesRawTenantGucCast`.

## Task Commits

Each task was committed atomically:

1. **Task 1: Rewrite V57 step-2 backfill as an RLS-safe tenant loop** — `eb56871` (fix)
2. **Task 2: Regression test — V57 backfills pre-existing rows on a non-fresh DB** — `099f624` (test)
3. **Task 3: Prove the full suite stays green** — verification gate (no file changes → no commit)

**Plan metadata:** committed separately (docs: complete 23-17 plan).

## Files Created/Modified

- `core-java/src/main/resources/db/migration/V57__shop_staff_grant_source.sql` — step-2 backfill rewritten as a per-tenant `set_config` loop; step-2 comment expanded to document the RLS-safety (FORCE RLS + RLS-bound migration role + NULL GUC → 0 rows), citing the V25/V44 defect-class precedent. Steps 1/3/4 untouched.
- `core-java/src/test/java/uk/jtoye/core/security/access/V57GrantSourceBackfillIntegrationTest.java` — new `@Tag("testcontainers")` stepwise-Flyway test (plain JUnit + Flyway API, mirrors `FlywayV44OutOfOrderIntegrationTest`).

## Verification Evidence

### Task 3 — full `:core-java:integrationTest` (aggregated from JUnit result XMLs)

```
CLASSES=81 TESTS=332 FAILURES=0 ERRORS=0 SKIPPED=1
BUILD SUCCESSFUL in 34m 2s
```

- Was 331/0 (80 classes) at 23-16; now **332/0** (81 classes) with the new test — one added test, no regression.
- `V57GrantSourceBackfillIntegrationTest`: `tests="1" failures="0" errors="0"`.
- `RlsContractTest`: `tests="4" failures="0" errors="0"` — `noPolicyUsesRawTenantGucCast` still green (no policy touched).
- No result XML contains any `<failure>` or `<error>` element.

### Falsifiability — RED against the pre-fix bare-UPDATE V57 (anti-false-green)

Temporarily reverting V57 step 2 to the original bare `UPDATE` and rerunning the test reproduced the exact production deployment failure, then the committed fix was restored:

```
V57GrantSourceBackfillIntegrationTest > v57BackfillsPreExistingRowsAcrossTenantsUnderRls() FAILED
    org.flywaydb.core.internal.exception.FlywayMigrateException
        Caused by: org.flywaydb.core.internal.sqlscript.FlywaySqlScriptException
            Caused by: org.postgresql.util.PSQLException

SQL State  : 23502
Message    : ERROR: column "grant_source" of relation "shop_staff" contains null values
Location   : db/migration/V57__shop_staff_grant_source.sql
```

`SQLSTATE 23502` (not_null_violation) at `SET NOT NULL` — the bare UPDATE saw zero rows under FORCE RLS as the NOSUPERUSER `rls_migrator`, left the seeded rows NULL, and the `SET NOT NULL` verification scan (which bypasses RLS) rejected them. GREEN after the tenant-loop fix (rows reached, backfilled, `SET NOT NULL` passes).

## Decisions Made

- **Applied the migration-under-test as a NOSUPERUSER owner role, not the container superuser.** The Testcontainers bootstrap role bypasses even FORCE RLS, so running V57 as it would mask the bug (the bare UPDATE would see all rows). The test hands ownership of `shop_staff`/`shop_staff_aud` to a `NOSUPERUSER NOBYPASSRLS` `rls_migrator` role (mirroring production's `jtoye_app`) so V57's ALTERs run (owner) while the backfill UPDATE is genuinely RLS-enforced. V1–V56 still run as the robust superuser (V1 needs `CREATE EXTENSION`, V44 a superuser-only LEAKPROOF).
- **Data-level assertions, not just "migrate() didn't throw."** The test reads back `grant_source` per seeded row (as superuser, to see all rows), so it is RED whether the pre-fix path throws at `SET NOT NULL` (it does) or — hypothetically — passed with rows left NULL.

## Deviations from Plan

None — plan executed exactly as written (V57 step 2 rewritten per `<the_fix>`; two-tenant stepwise-Flyway regression test with RED demonstrated pre-fix; full suite green).

## Issues Encountered

- **Flyway checksum note (informational, for the deployer — not a code issue).** Editing V57 changes its checksum. V57 is new on this branch and has only ever run in ephemeral Testcontainers DBs, so no persistent DB has recorded the old checksum — the edit is safe here. If a developer already booted the app against a local dev DB on this branch (recording V57 v1), a one-time `flyway repair` is needed before the corrected V57 applies. No tooling added per plan.
- **Unrelated working-tree files left untouched.** `23-VERIFICATION.md` (modified) and `23-REVIEW-gapclosure.md` (untracked) appeared in the working tree during the session; they are outside this plan's scope, were not authored by this executor, and were deliberately NOT staged or reverted (scope boundary + destructive-git prohibition).

## User Setup Required

None — no external service configuration required. (Deployer note: see the Flyway checksum item above if a local dev DB was already booted against this branch.)

## Follow-up — docs/metrics.json count drift (deferred to phase-gate reconcile)

This plan's scope is hard-locked to the V57 migration + the new test (`git diff → only those two files`), so `docs/metrics.json` was deliberately NOT touched — mirroring the 23-14 → 23-15 defer pattern. The new test adds one Java `@Test` in one new file, so the counts drift by +1:

| key | current | after 23-17 |
|-----|---------|-------------|
| `java_test_methods` | 1064 | 1065 |
| `java_test_files` | 180 | 181 |
| `total_logical_invocations` | 1573 | 1574 |

**Action required before the phase PR:** run `scripts/docs-freshness.sh --write` (updates `docs/metrics.json` + the CLAUDE.md/AGENTS.md count prose) so the CI `docs-freshness` gate stays green. Until then that gate is expected-RED by exactly this +1. Recorded in STATE.md pending todos.

## Next Phase Readiness

- The V57 migration blocker is closed with proof; Phase 23 is deployable on non-fresh DBs (Compose dev, staging, prod). Ready for `/gsd:secure-phase 23` + `/gsd:verify-work` sign-off and the phase PR (after the docs/metrics reconcile above).
- Phase 24 (V53 `media_asset`) is unaffected — V53 remains reserved; `spring.flyway.out-of-order=true` still applies.

## Self-Check: PASSED

- FOUND: `core-java/src/main/resources/db/migration/V57__shop_staff_grant_source.sql`
- FOUND: `core-java/src/test/java/uk/jtoye/core/security/access/V57GrantSourceBackfillIntegrationTest.java`
- FOUND: `.planning/phases/23-vendor-scoped-access-responsive-dashboard-nav/23-17-SUMMARY.md`
- FOUND commit: `eb56871` (Task 1 — V57 fix)
- FOUND commit: `099f624` (Task 2 — regression test)

---
*Phase: 23-vendor-scoped-access-responsive-dashboard-nav*
*Completed: 2026-07-21*
