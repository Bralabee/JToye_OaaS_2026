---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 09
subsystem: auth
tags: [rls, postgres, envers, spring-data-jpa, pessimistic-lock, staff-management, idempotency, testcontainers]

requires:
  - phase: 23 (23-01)
    provides: V52 shop_staff + shop_staff_aud (@Audited) + functional unique index; ShopStaffRepository
  - phase: 23 (23-02)
    provides: ShopAccessService gate (requireGroupAdmin), LastGroupAdminException typed 409, evictMembership (D-05)
  - phase: 23 (23-04/23-06)
    provides: StaffManagementService grant/revoke + /api/v1/staff surface + StaffManagementIntegrationTest
  - phase: 23 (23-08)
    provides: fail-closed ShopAccessService (build on it — do not revert)
provides:
  - "grant() applies role changes (CR-05) instead of silently no-opping while reporting success"
  - "shop_staff_aud now records grants AND role changes, not revokes alone (WR-02)"
  - "race-safe idempotent grant via REQUIRES_NEW session insert + DataIntegrityViolationException catch (no 500)"
  - "last-GROUP_ADMIN check-then-act serialized by a PESSIMISTIC_WRITE lock (CR-06) — no zero-admin lockout"
  - "IN-03 two 409 messages extracted to named constants (revoke vs downgrade variants kept distinct)"
affects: [23-13 (IN-02 frontend copy + /me), 23-14 (JIT-row Envers half of WR-02), 23-15 (phase gate)]

tech-stack:
  added: []
  patterns:
    - "Envers-audited write: route create/update through the Hibernate session (save/saveAndFlush), never a native ON CONFLICT upsert, when an _aud trail is required"
    - "Concurrent-insert idempotency without a native upsert: isolate the session insert in @Transactional(REQUIRES_NEW) via ObjectProvider self-invocation, catch DataIntegrityViolationException, re-select and replay (outer tx stays un-poisoned since Postgres aborts a whole tx on any statement error)"
    - "Serialize a check-then-act invariant with @Lock(PESSIMISTIC_WRITE) + ORDER BY id over the exact invariant row set, before the count"

key-files:
  created: []
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopStaffRepository.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StaffManagementIntegrationTest.java

key-decisions:
  - "grant() reshaped to a session-based write (not a native DO UPDATE) — a native upsert would fix the role change but keep bypassing Envers, which is the worse half of WR-02"
  - "Concurrent-insert race handled with REQUIRES_NEW isolation (not native ON CONFLICT) so the create is auditable AND the outer tx survives a unique-index violation"
  - "lockTenantGroupAdmins scopes to shop_id IS NULL GROUP_ADMIN rows == the counted set (grant rejects shop-scoped GROUP_ADMIN), confirmed identical; ORDER BY id for deadlock-free lock order"
  - "VSA-04 left PENDING (anti-false-green) — 23-09 is one of six VSA-04 gap plans (23-08/09/12/13/14/15)"

patterns-established:
  - "Falsifiability barrier for a microsecond check-then-act race: a test-held control-connection SELECT ... FOR UPDATE over the contended rows parks both workers deterministically (pre-fix both pass the count then delete → RED; post-fix both block on the service's own lock → GREEN)"
  - "Distinct principal subs per concurrent worker so the onRequest() user_directory upsert row-lock does not mask the race under test"

requirements-completed: []  # VSA-04 intentionally NOT closed here — 23-12/13/14/15 still contribute

duration: 29min
completed: 2026-07-20
---

# Phase 23 Plan 09: Staff-Write Correctness (CR-05 / WR-02 / CR-06 / IN-03) Summary

**Staff grants now apply the role they report and are fully Envers-audited, and the last-GROUP_ADMIN guard is race-safe — closing a silent privilege-retention bug, an audit gap, and a permanent-lockout race.**

## Performance

- **Duration:** 29 min
- **Started:** 2026-07-20T21:01:52Z
- **Completed:** 2026-07-20T21:31:06Z
- **Tasks:** 3
- **Files modified:** 3

## Accomplishments

- **CR-05 (Elevation of Privilege):** re-granting a DIFFERENT role on an existing `(user, shop)` now UPDATES the role — a downgrade genuinely takes effect instead of silently no-opping via `ON CONFLICT DO NOTHING` while the API returns 200 "Access granted". A revoked privilege is really removed, not retained-while-reported-removed.
- **WR-02 (Repudiation):** the grant write moved from a native `ON CONFLICT` insert (invisible to Envers) to a Hibernate session write, so `shop_staff_aud` now gains an ADD revision for a create and a MOD revision for a role change. "Who granted whom which role on which shop, and when" is answerable.
- **CR-06 (Denial of Service):** the last-GROUP_ADMIN check-then-act is serialized by a `PESSIMISTIC_WRITE` lock over the tenant's tenant-wide GROUP_ADMIN rows, in BOTH `revoke()` and the `grant()` downgrade path, so two concurrent writes can no longer race the tenant to zero GROUP_ADMINs (a permanent lockout under strict-scoping ON).
- **T-23-09-04 (Tampering):** a concurrent duplicate grant surfacing a `DataIntegrityViolationException` is caught (the insert is isolated in a `REQUIRES_NEW` tx) and replayed as a typed idempotent 200 — never an untyped 500. The sequential idempotent-replay contract is preserved unchanged.
- **IN-03:** the two 409 messages were extracted to named constants (`MSG_REVOKE_LAST_GROUP_ADMIN` / `MSG_DOWNGRADE_LAST_GROUP_ADMIN`), kept deliberately distinct so 23-13 can fix the divergent frontend copy (IN-02).

## Task Commits

Each task was committed atomically:

1. **Task 1: grant() applies role changes and audits every write (CR-05 + WR-02 + IN-03)** — `6602ff8` (fix)
2. **Task 2: serialize the last-GROUP_ADMIN guard with a row lock (CR-06)** — `b3af27b` (fix)
3. **Task 3: prove role-change + audit + revoke-race with tests that FAIL pre-fix** — `92bb2d1` (test)

**Plan metadata:** this commit (docs: complete plan)

_Note: Tasks 1 & 2 are `tdd="true"`; RED was demonstrated for all falsifiability cases before the fixes (Task 3 holds the tests, committed last so every commit stays green)._

## Files Created/Modified

- `core-java/.../access/StaffManagementService.java` — grant() re-selects the canonical row first, then: absent → `persistNewGrant` (REQUIRES_NEW session insert, Envers ADD) with a `DataIntegrityViolationException` catch → replay; same role → no-write replay; different role → `setRole` + `saveAndFlush` (Envers MOD), guarded by the lock+D-11 downgrade check. Message constants + `ObjectProvider<StaffManagementService>` self-reference added; unused `insertGrantIfAbsent` references removed.
- `core-java/.../access/ShopStaffRepository.java` — added `lockTenantGroupAdmins` (`@Lock(PESSIMISTIC_WRITE)`, `SELECT ... FOR UPDATE ... shop_id IS NULL AND role = GROUP_ADMIN ORDER BY id`); removed the now-unused `insertGrantIfAbsent`.
- `core-java/.../access/StaffManagementIntegrationTest.java` — 6 new Testcontainers cases (see below).

## Pre-fix RED evidence (falsifiability gate)

Captured by running the new cases against the pre-fix production code (`red-run3.log`). Each falsifiability case failed as predicted:

| Case | Assertion | Pre-fix RED output |
|------|-----------|--------------------|
| `grantWithDifferentRoleAppliesTheChange` (CR-05) | returned/persisted role is STAFF | `[the returned DTO carries the NEW (downgraded) role] expected: STAFF but was: SHOP_MANAGER` |
| `grantUpgradeAppliesTheChange` (CR-05) | persisted role is SHOP_MANAGER | `expected: SHOP_MANAGER but was: STAFF` |
| `grantWritesAnAuditRevision` (WR-02) | `shop_staff_aud` ADD revision ≥ 1 | `[the grant CREATE is audited (Envers ADD revision) — WR-02] Expecting actual: 0L to be greater than or equal to: 1L` |
| `concurrentRevokesCannotEmptyTheTenantOfGroupAdmins` (CR-06) | exactly one revoke succeeds | `[exactly one concurrent revoke succeeds] expected: 1L but was: 2L` — **both** revokes committed, i.e. the tenant was raced to **zero** GROUP_ADMINs |

`grantWithSameRoleIsIdempotentReplay` and `concurrentDuplicateGrantIsIdempotentNotA500` passed pre-fix (they guard existing/defensive behaviour, not a fixed bug). Post-fix: **12/12 green across 3 consecutive runs** (no flake); `:core-java:test` green (no unit regression).

### Making the CR-06 race deterministic (the one that first passed pre-fix)

The plan warned case 5 would be green-by-construction if the threads did not truly race — and the first pre-fix run confirmed it (both revokes serialized by luck). Root cause: the count→delete window in `revoke()` is microseconds wide, and `requireGroupAdmin()` → `onRequest()` upserts a `user_directory` row keyed `(tenant, sub)`; two workers sharing the seeding admin's sub serialized on that directory row lock, masking the race. Fixes: (1) a **test-held control-connection `SELECT ... FOR UPDATE`** over both GROUP_ADMIN rows parks both workers deterministically — pre-fix they pass the count then block at DELETE (release → both commit → zero admins → RED); post-fix they block at the service's own `lockTenantGroupAdmins` (release → exactly one wins, the other 409s → GREEN); (2) **distinct realm-admin subs per worker** so directory upserts do not contend. This yields a single test that is deterministically RED pre-fix and GREEN post-fix.

## Lock ↔ count row-set confirmation (plan-required)

Confirmed the `lockTenantGroupAdmins` predicate (`shop_id IS NULL AND role = GROUP_ADMIN`) and `countByTenantIdAndRole(tenantId, GROUP_ADMIN)` cover the **identical** row set: `grant()` rejects any shop-scoped GROUP_ADMIN grant with a 400, so no `shop_id IS NOT NULL` GROUP_ADMIN row can exist; the two therefore agree, and the lock guards exactly the rows the count reads. Task 1's changes did not alter this (the shop-scoped-GROUP_ADMIN rejection is unchanged and still ahead of the write). The lock query stays tenant-scoped (explicit `tenantId` + the `shop_staff` FORCE-RLS wall via `TenantSetLocalAspect`), so a lock taken by tenant A cannot be observed or blocked by tenant B.

## Decisions Made

- Session-based write over a native `DO UPDATE SET role = EXCLUDED.role`: the native upsert would fix the role change but keep bypassing Envers — the worse half of WR-02. The deliberate trade (losing `ON CONFLICT` elegance for audit correctness) is the one named in the plan.
- `REQUIRES_NEW` self-invocation (`ObjectProvider`) to isolate the insert: Postgres aborts a whole transaction on any statement error, so re-selecting after a unique-violation must happen in the caller's (un-poisoned) tx while the failing insert lives in its own. `TenantSetLocalAspect` re-pins the tenant GUC before the inner repo call, so RLS holds in the nested tx.
- `ORDER BY s.id` on the lock query for a deterministic, deadlock-free lock-acquisition order across the two racing transactions.
- VSA-04 left PENDING (not marked complete) — anti-false-green, mirroring 23-01's VSA-01 handling; 23-12/23-13/23-14/23-15 still contribute to VSA-04.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Deterministic FOR UPDATE barrier + distinct worker subs for the CR-06 race test**
- **Found during:** Task 3 (falsifiability gate)
- **Issue:** The plan's two-thread `CountDownLatch` race did not reliably fire pre-fix (the count→delete window is microseconds; the shared realm-admin sub serialized both workers on the `user_directory` upsert row lock) — case 5 passed pre-fix, the exact green-by-construction failure the plan flagged.
- **Fix:** Added a test-held control-connection `SELECT ... FOR UPDATE` barrier over the GROUP_ADMIN rows and gave each worker a distinct realm-admin sub, making the test deterministically RED pre-fix (zero admins) and GREEN post-fix.
- **Files modified:** StaffManagementIntegrationTest.java
- **Verification:** Pre-fix run ends with `expected 1L but was 2L` (both revokes committed → zero admins); post-fix green across 3 runs.
- **Committed in:** `92bb2d1` (Task 3 commit)

**2. [Rule 1 - Bug] Removed the now-unused `insertGrantIfAbsent` native upsert**
- **Found during:** Task 1
- **Issue:** After grant() moved to a session write, the native `insertGrantIfAbsent` was dead code (and its javadoc still claimed to be the grant path).
- **Fix:** Deleted the method (the JIT `insertGroupAdminIfAbsent` path is untouched per the plan's scope note).
- **Files modified:** ShopStaffRepository.java, StaffManagementService.java
- **Verification:** `grep insertGrantIfAbsent` across `core-java/src` returns 0; main + unit + integration suites green.
- **Committed in:** `6602ff8` (Task 1 commit)

---

**Total deviations:** 2 auto-fixed (1 missing-critical test infrastructure, 1 dead-code bug)
**Impact on plan:** Both necessary — the barrier makes the required CR-06 falsifiability real, and the dead-code removal is implied by the "no more `insertGrantIfAbsent`" acceptance. No scope creep; the JIT path was left untouched as scoped.

## Issues Encountered

- The CR-06 concurrency test first passed pre-fix (see "Making the CR-06 race deterministic"). Resolved by diagnosing the `user_directory` upsert row-lock serialization via timing/lock diagnostics, then adding the FOR UPDATE barrier + distinct worker subs. All diagnostics were removed before committing.

## Known Stubs

None — no placeholder/empty-data code introduced.

## Threat Flags

None — no new network endpoints, auth paths, or trust-boundary schema introduced. All mitigations map to the plan's existing `<threat_model>` (T-23-09-01..04). T-23-09-05 (JIT rows bypass Envers) remains deliberately deferred.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- CR-05, WR-02, CR-06, IN-03 closed and proven. Ready for 23-12 (WR-05 grant tenancy/user validation + `/api/v1/staff/me`) which builds on this grant path.
- **Deferred to 23-14 (recorded):** the JIT-provision write path (`ShopStaffRepository.insertGroupAdminIfAbsent`) still bypasses Envers — the JIT half of WR-02 (T-23-09-05). 23-14 owns JIT-row semantics and will close it there.
- **VSA-04 stays PENDING:** 23-09 is one of six VSA-04 gap plans; the requirement closes at the 23-15 phase gate.
- **Standing phase blocker (unchanged):** `docs/api/openapi-snapshot.json` still lacks the 3 `/api/v1/staff` endpoints — regen at the 23-15 phase gate (`OpenApiSnapshotTest` runs inside `integrationTest`).

## Self-Check: PASSED

All 3 modified source files and the SUMMARY exist on disk; all 3 task commits (`6602ff8`, `b3af27b`, `92bb2d1`) present in git history.

---
*Phase: 23-vendor-scoped-access-responsive-dashboard-nav*
*Completed: 2026-07-20*
