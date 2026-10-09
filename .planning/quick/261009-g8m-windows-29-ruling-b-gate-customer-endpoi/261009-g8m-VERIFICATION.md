---
phase: quick-261009-g8m
verified: 2026-10-09T13:05:00Z
status: passed
score: 10/10 must-haves verified
covered_files:
  - .planning/quick/261009-g8m-windows-29-ruling-b-gate-customer-endpoi/261009-g8m-PLAN.md
  - .planning/quick/261009-g8m-windows-29-ruling-b-gate-customer-endpoi/261009-g8m-SUMMARY.md
  - core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java
  - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
  - core-java/src/test/java/uk/jtoye/core/customer/CustomerAccessGateIntegrationTest.java
  - docs/api/openapi-snapshot.json
covered_digest: "v3:sha256:cca7be23f15e8a4420907d77ecf1c5419a14868a5dc9e334c8b6375e2d2b2dc0"
behavior_unverified: 0
overrides_applied: 0
---

# Quick 261009-g8m: WINDOWS #29 ruling B customer gate. Verification Report

**Goal:** Gate every customer endpoint on "holds any honoured grant" under D-06 strict scoping, with RED-first Testcontainers arms, and re-cut `phase-37-b-staff-access` from origin/main as one squash commit carrying the gate.
**Verified:** 2026-10-09
**Status:** passed (two executor claims could not be independently re-confirmed; see Warnings)
**Re-verification:** No, initial verification
**Mode:** read-only. No edits, commits, pushes or branch changes. Two gradle runs (below) overwrote `core-java/build-local/test-results/integrationTest` only.

## Goal Achievement

### Observable Truths (PLAN must_haves)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Gate in `ShopAccessService`, staff/me predicate, does NOT call `onRequest()` or read raw rows | VERIFIED | `ShopAccessService.java:342-353`: `isGroupAdmin()` -> return; `!grantedShopIds().isEmpty()` -> return; `self().resolveMembership(requireVendorUserId()).tenantWideRole() != null` -> return; else `throw new ShopAccessDeniedException(null, ShopRole.STAFF)`. `sed -n 342,353p ... \| rg -c 'onRequest\|shopStaffRepository\|findBy'` -> no match, rc 1. Predicate matches `StaffManagementService.myAccess` (lines 188-197): `isGroupAdmin()`, `grantedShopIds()`, `resolveMembership(userId).tenantWideRole()`. Javadoc (l.328-336) states the no-`onRequest`, no-raw-rows, no-options-C/E constraints. |
| 2 | Gate is the first statement of all five `CustomerController` handlers, before `idempotencyService.execute` | VERIFIED | `CustomerController.java`: l.67 (list), l.81 (getById), l.104 (create, precedes the Idempotency-Key branch at l.107 and `execute` at l.111), l.138 (update), l.155 (delete). `git grep -c 'shopAccessService.requireAnyAccess();'` on the branch -> 5. All `@PreAuthorize` lines unchanged. |
| 3 | 403 in the five `@ApiResponses` and in `docs/api/openapi-snapshot.json`; snapshot differs from predecessor only under the two customer paths | VERIFIED | `@ApiResponse(responseCode = "403"...)` at l.62, 76, 94, 131, 149. Snapshot read: customers get `[200,401,403]`, post `[201,400,403,409]`, `{id}` get `[200,403,404]`, put `[200,400,403,404]`, delete `[204,403,404,409]`. Diff `3af4bedc^..3af4bedc` -> `changed ['/api/v1/customers','/api/v1/customers/{id}'] other []`. Fail direction: same count on `2ab64fbb` snapshot -> 0. (Regeneration by `updateOpenApiSnapshot` vs hand-edit: not observable from the artifact; `OpenApiSnapshotTest` check mode is green against live source, which is the substantive proof.) |
| 4 | New IT exists with 12 arms, RED-first | VERIFIED | `CustomerAccessGateIntegrationTest.java`: 12 `@Test` (lines 108..365), 12 `@DisplayName("WINDOWS #29: ...")`, `StrictScopingGuard` capture/restore/set, `assertShopAccessDenied` asserts status 403, problem type `https://jtoye.uk/errors/shop-access-denied`, and no customer email in body (l.464-469). I re-ran it: gradle rc 0, fresh XML `tests=12 failures=0 errors=0 skipped=0`. Order: `merge-base --is-ancestor aa91eba9 3af4bedc` rc 0, reverse rc 1; `aa91eba9` touches only the IT file. |
| 5 | RED evidence exists and says `RED_EVIDENCE_OK` | VERIFIED | `gsd_run check tdd-red-evidence .../evidence/red-customer-gate.json --raw --pick verdict` -> `RED_EVIDENCE_OK`, rc 0. Record: exitCode 1, embedded XML `tests=12 failures=8 errors=0`, targetTest = the `ungranted_list_403` display name, actual `expected: 403 but was: 200`. `task-base.txt` = `2ab64fbb9a7e05c6da966d56d3a5084bb4169887`. |
| 6 | Fixed tests were repaired by granting access, not loosening | VERIFIED | Loosening scan over `git diff 3af4bedc^ 3af4bedc -- core-java/src/test` for changed lines carrying `status()`, `isOk/isCreated/isNotFound/isForbidden/isNoContent`, `StrictScopingGuard.set` or `strict` -> rc 1, no match. Control: the same regex on a sample `+ .andExpect(status().isOk());` -> 1 match, rc 0. I re-ran the fixed set (CustomerController 7, CustomerIdempotency 1, ScopedWriteAccess 12, TypedNotFoundBody 13, VanishedRowStatus 9, LocationHeader 7, OpenApiSnapshot 1, plus the gate IT 12): all fresh, 0 failures, 0 errors, gradle rc 0. |
| 7 | Full unit and integration suites green on the final code tree; doc gates rc 0 | VERIFIED (unit); integration full-suite total not re-confirmable | Unit: 189 files, 1624 tests, 0 failures, 0 errors, 1 skipped (read from `core-java/build-local/test-results/test`, mtime 12:25; `git diff --name-only f8a44ae1 HEAD -- core-java` is empty so the code tree is unchanged since). Integration 1018/0/0/1: arithmetic 1006 + 12 is consistent, but see Warning W1. Doc gates: extracted `git archive phase-37-b-staff-access` into scratchpad; `check-doc-metrics.sh` rc 0 (37 claims) and `docs-freshness.sh` rc 0 (5153) once the migrations were tracked in the scratch repo (the first run's `schema_version` came back blank because that script counts `git ls-files` and my scratch repo had nothing tracked; all other computed fields already matched). |
| 8 | WINDOWS #29 carries the ruling and is fixed; #30 records the owed live proof; STATE.md has the decision | VERIFIED | `.planning/WINDOWS.md` l.46: `\| 29 \| 37 \| unmet-truth ...` contains "RULED 2026-10-09 (owner, option B)", names `requireAnyAccess`, `CustomerAccessGateIntegrationTest`, options C and E as separate future rulings, status `fixed`. l.47: `\| 30 \| 37 \| unrun-verify ...` status `open`, text names the owed live proof. Frontmatter open 7 / fixed 23 / total 30. `git diff --numstat .planning/STATE.md` -> `1 0`; the added bullet is the `[Phase 37]: WINDOWS #29 RULED 2026-10-09 ...` decision line (uncommitted, as expected). |
| 9 | Re-cut: exactly one commit on origin/main; tree equals v2 tag; 2ab64fbb not an ancestor; no upstream; not pushed | VERIFIED | After `git fetch origin` (rc 0): origin/main `1773fe53...`; `git log origin/main..phase-37-b-staff-access` -> only `36d7f74b feat(37-B): staff access and finance` (count 1, behind 0). `phase-37-b-staff-access^{tree}` = `cut/phase-37-b-staff-access-v2^{tree}` = `47128157...`; `git diff --quiet v2 branch` rc 0. `2ab64fbb` is not an ancestor of the v2 tag (rc 1) nor of the branch (rc 1); old cut `96d5a94b` is an ancestor of v2 (rc 0). `KitchenStatusesParityTest` absent from the branch (rc 1), present on `phase-37-ops-readiness` (control, rc 0). `git rev-parse ...@{upstream}` -> "no upstream configured", `git config --get branch.phase-37-b-staff-access.remote` rc 1, `git ls-remote --heads origin phase-37-b-staff-access` rc 0, empty. Branch has the gate (5 calls) and the IT. Worktree list shows only the main checkout and an unrelated project; no `tmp/*` branches. |
| 10 | No attribution lines in any task commit or in the squash | VERIFIED | `git log --format=%B 2ab64fbb..HEAD \| rg -uu -i -c 'co-authored\|generated with\|claude-session\|claude.ai/code'` -> rc 1 (no match) over all six commits. Squash message read back: no match for those patterns or a backtick, rc 1. It has the exact first line, the WINDOWS #29 bullet, the cut line naming `cut/phase-37-b-staff-access-v2`, "5153 logical invocations", and the required closing line. |

**Score:** 10/10 truths verified (0 behavior-unverified; the gate's behavior is exercised by a passing IT through MockMvc on Postgres).

### Required Artifacts

| Artifact | Status | Details |
|----------|--------|---------|
| `ShopAccessService.java` `public void requireAnyAccess()` | VERIFIED | Present at l.342, substantive, called from 5 sites. |
| `CustomerController.java` gate x5 plus 403 docs | VERIFIED | See truths 2 and 3. |
| `CustomerAccessGateIntegrationTest.java` | VERIFIED | 12 arms, contains `StrictScopingGuard`. |
| `docs/api/openapi-snapshot.json` | VERIFIED | 403 on all 5 customer ops. |
| `evidence/red-customer-gate.json` | VERIFIED | Contains `semanticAssessment`; classifier `RED_EVIDENCE_OK`. |
| `.planning/WINDOWS.md` | VERIFIED | Contains "option B". |

### Key Links

| From | To | Status | Details |
|------|----|--------|---------|
| `CustomerController` handlers | `ShopAccessService.requireAnyAccess()` | WIRED | 5 anchored first-statement calls; in `create()` precedes the Idempotency-Key branch. |
| `requireAnyAccess()` | `StaffManagementService.myAccess()` predicate | WIRED | Same three disjuncts; parity asserted by the `staff/me` checks in the IT arms. |
| `ShopAccessDeniedException(null, STAFF)` | `type .../shop-access-denied` | WIRED | Asserted by `assertShopAccessDenied` in every refusal arm (a `@PreAuthorize` 403 would carry a different type and fail). |
| tag `cut/phase-37-b-staff-access-v2` | branch `phase-37-b-staff-access` | WIRED | Tree-equal, single squash commit. |

### Data-Flow Trace (Level 4)

Not applicable: the gate produces a refusal, not rendered data. The refusal path carries no customer data (asserted: refusal bodies do not contain the seeded email, including the stolen-replay body not containing the stored id).

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Gate IT (12 arms) | `./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --tests '...CustomerAccessGateIntegrationTest' --no-daemon` | rc 0; fresh XML 12/0/0/0 | PASS |
| Fixed fallout classes plus snapshot check | same, 8 classes | rc 0; all fresh, 0 failures, 0 errors | PASS |
| RED fail direction | embedded XML in `red-customer-gate.json` | `tests=12 failures=8 errors=0`, gradle exit 1 | PASS (as evidence of fail direction) |
| Attribution scan | `git log --format=%B 2ab64fbb..HEAD \| rg -uu -i -c ...` | rc 1, no match (no positive control run by me for this pattern) | PASS, weak |

### Probe Execution

No probes declared. Skipped.

### Requirements Coverage

| Requirement | Status | Evidence |
|-------------|--------|----------|
| QUICK-261009-g8m | SATISFIED | Truths 1-10. |
| WINDOWS-29 | SATISFIED | Gate in source and tested; ledger row fixed with ruling; live proof owed under #30 by design. |

### Anti-Patterns Found

Scanned the three changed main/test files for `TBD|FIXME|XXX|TODO|HACK|placeholder`: none introduced by this task (gate method and controller read in full above, no stubs).

### Human Verification Required

None for this task's must-haves. Recorded separately and by design: WINDOWS #30 (live compose-runtime proof of the gate: ungranted GET `/api/v1/customers` -> 403 `shop-access-denied`, STAFF -> 200, ungranted dashboard login lands on the no-access page) is owed at the 37-B ship, after a rebuild. It is an `open` ledger item, not a must-have of this task.

## Warnings (non-blocking, could not be confirmed independently)

- **W1. Full integration suite total (1018/0/0/1).** The executor's claim of 1018 tests ran on `f8a44ae1`. I could not re-read that XML: the targeted gradle runs this verification was told to perform use `cleanIntegrationTest`, which removed the earlier full-run reports. What I did confirm: unit 1624/0/0/1 is on disk and fresh; the eight fallout classes and the gate IT are green when re-run; 1006 + 12 = 1018 is consistent. I did not re-run the 27-minute full suite. CI on the PR will run it.
- **W2. Break arms B1/B2/B2b/B2c and sha256 restores** are documented in the SUMMARY only. I am read-only and did not re-run them. Supporting evidence I did check: the controller on HEAD has 5 gate calls and the IT's keyed-create and replay arms (l.146-175, l.252-287) can distinguish gate-after-`execute` (row count) from gate-in-unkeyed-branch-only (replay 201), which matches the SUMMARY's account of why the planned B2 could not red. The plan's own B2 deviation is disclosed honestly in the SUMMARY.
- **W3. Re-cut doc metrics, checked on an archive not a worktree.** The commit message states 5153 and `docs/metrics.json` on the branch reads 5153; I confirmed both doc gates rc 0 on an archive of the branch tree. Not re-run inside a real worktree.

## Gaps Summary

None. Every must-have resolves VERIFIED against the codebase and git state. The gate is first-statement in all five handlers, the predicate matches `staff/me`, it avoids `onRequest()` and raw row reads, the contract snapshot is correct and scoped, the 12-arm IT passes (and its RED record shows 8 genuine failures on the unfixed tree), the ledger and STATE are correct, and the re-cut branch is one tree-identical squash commit on origin/main without 37-16 and without an upstream.

---

_Verified: 2026-10-09_
_Verifier: Claude (gsd-verifier)_
