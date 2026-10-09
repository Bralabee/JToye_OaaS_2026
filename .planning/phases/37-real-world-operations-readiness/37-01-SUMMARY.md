---
phase: 37-real-world-operations-readiness
plan: 01
subsystem: planning
tags: [baseline, flyway, requirements, git-topology, keycloak]

requires:
  - phase: 38-spring-boot-4-1
    provides: Boot 4.1.1 / Jackson 3 tree on origin/main (D-04)
  - phase: 31.1-persona-gap-closure
    provides: V69..V75 and the stale-acknowledgement 409 that 37-C composes with
provides:
  - merged-main baseline with six guarding suites green (66 tests, 0 failures)
  - migration reservations V76..V83, one per owning plan, proven unused on every ref
  - git topology every Phase 37 sub-theme follows (D-02..D-04)
  - 35 RWO-* requirement IDs with 35 traceability rows
  - owner decision D-26 = login-hint recorded before any 37-B code
affects: [37-02, 37-05, 37-07, 37-08, 37-09, 37-10, 37-11, 37-15, 37-19, 37-27, 37-45, 37-48]

actuals:
  tokens: 8100
  tasks: 3
  commits: 3
plan_head_before: d903df2ed529fe15364acc4ea3c8039228e952a2
plan_head_after: a8ef6d81bbaa378128e16499048f063f8c272607

tech-stack:
  added: []
  patterns:
    - "Migration numbers reserved up front, one per owning plan, proven unused per ref with a positive control and a planted-collision break arm"
    - "Requirement traceability rows derived by inverting the plans' frontmatter requirements fields, not hand-typed"

key-files:
  created:
    - .planning/phases/37-real-world-operations-readiness/evidence/37-01-baseline.md
  modified:
    - .planning/REQUIREMENTS.md

key-decisions:
  - "D-26 = login-hint (owner, 2026-10-08): after the invite accept page creates the account, the normal Keycloak sign-in starts with the invited email pre-filled; no second session-minting path; 37-08/37-09 need no re-plan"
  - "Migration reservations V76..V83 follow the plans' own filenames (37-05, 37-07, 37-10, 37-11, 37-19, 37-27, 37-45, 37-48), superseding RESEARCH's V76-V82 per-sub-theme proposal"
  - "Plan 37-01 is omitted from the RWO traceability rows: it declares all 35 IDs because it mints them, but implements none"

patterns-established:
  - "Baseline evidence states its own scope: six guarding suites, not the full suite, so 37-02 must take its own full-suite reading"

requirements-completed: [RWO-003, RWO-004, RWO-018, RWO-026, RWO-027, RWO-046, RWO-047, RWO-011, RWO-020, RWO-021, RWO-022, RWO-007, RWO-008, RWO-029, RWO-044, RWO-005, RWO-006, RWO-039, RWO-041, RWO-042, RWO-016, RWO-017, RWO-035, RWO-036, RWO-037, RWO-038, RWO-040, RWO-080, RWO-081, RWO-082, RWO-083, RWO-084, RWO-085, RWO-023, RWO-028]

coverage:
  - id: D1
    description: "origin/main merged into phase-37-ops-readiness (394edcda), Boot 4.1.1 and V69..V75 present"
    verification:
      - kind: other
        ref: "git merge-base --is-ancestor origin/main HEAD && grep Boot 4.1 line && test -f V75 (rc=0; reverse ancestor check rc=1)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Six guarding suites green on the merged tree before any Phase 37 change"
    verification:
      - kind: unit
        ref: "core-java/build-local/test-results/test/TEST-uk.jtoye.core.tenant.keycloak.KeycloakAdminClientTest.xml (11/0/0), IdempotencyFingerprintGoldenTest (15/0/0)"
        status: pass
      - kind: integration
        ref: "core-java/build-local/test-results/integrationTest: ShopAccessJitProvisionTest 4/0/0, StaffManagementIntegrationTest 19/0/0, GuestOrderAllergenAckIntegrationTest 10/0/0, RlsContractTest 7/0/0"
        status: pass
    human_judgment: false
  - id: D3
    description: "V76..V83 reserved and proven unused on origin/main and all remote branches"
    verification:
      - kind: other
        ref: "per-ref git ls-tree + grep for V76..V83 (0 hits on 8 refs) beside V75 control (hit on every ref that reached V75); planted V79 line -> 1 hit"
        status: pass
    human_judgment: false
  - id: D4
    description: "35 RWO requirement IDs and 35 traceability rows, same set, plan lists matching every plan's frontmatter"
    requirement: "RWO-003"
    verification:
      - kind: other
        ref: "verify-rwo.sh (bash form of the plan's python verify): pre-edit '0 0' rc=1, post-edit '35 35' rc=0, row-removed '35 34' rc=1, set-mismatch '35 35' rc=1; rows vs frontmatter inversion diff rc=0"
        status: pass
    human_judgment: false
  - id: D5
    description: "Git topology paragraph (D-02..D-04) and D-26 owner decision recorded in evidence"
    verification: []
    human_judgment: true
    rationale: "Prose records of a topology and an owner ruling; correctness of the wording against the owner's intent is a human read"

duration: 8min
completed: 2026-10-08
status: complete
---

# Phase 37 Plan 01: Baseline, migration reservations and RWO requirements Summary

**Phase 37 starts from current main (Boot 4.1.1, V75, merge 394edcda) with six guarding suites green (66 tests), V76..V83 reserved per owning plan and proven unused on all eight refs, 35 RWO-* IDs minted with 35 traceability rows, and the owner's D-26 answer `login-hint` recorded.**

## Performance

- **Duration:** 8 min
- **Started:** 2026-10-08T07:22:27Z
- **Completed:** 2026-10-08T07:30:31Z
- **Tasks:** 3
- **Files modified:** 2

## Accomplishments

- origin/main (cc860dc5) was already merged into the integration branch by 394edcda. `git fetch` returned rc=0, and `git merge origin/main` printed "Already up-to-date." with rc=0.
- On the merged tree, KeycloakAdminClientTest (11), IdempotencyFingerprintGoldenTest (15), ShopAccessJitProvisionTest (4), StaffManagementIntegrationTest (19), GuestOrderAllergenAckIntegrationTest (10) and RlsContractTest (7) are all green. Each ran fresh after `cleanTest` / `cleanIntegrationTest`, and results were read from `build-local`.
- The reservations V76..V83 are recorded against their owning plans, and no ref holds any of them:
  - searched origin/main, the six other remote branches and local HEAD;
  - positive control: V75 is found on every ref that has reached it;
  - break arm: a planted collision is detected.
- REQUIREMENTS.md has a new RWO section: 35 bullets grouped B, A, D, C, E, G, F, and 35 traceability rows. The nine P0 rows are marked "Planned (P0)". The section also records the D-01 deferrals and the #452 gap-2 note.
- The owner's D-26 answer (`login-hint`, 2026-10-08) is recorded in `evidence/37-01-baseline.md` §6.

## Task Commits

1. **Task 1: Merge origin/main and prove the baseline** - `afee7b04` (docs)
2. **Task 2: Mint the 35 RWO requirement IDs with traceability rows** - `46b9e4dc` (docs)
3. **Task 3: D-26 decision checkpoint (answered by the owner: login-hint)** - `a8ef6d81` (docs)

**Plan metadata:** this SUMMARY's commit (docs: complete plan)

## Files Created/Modified

- `.planning/phases/37-real-world-operations-readiness/evidence/37-01-baseline.md` holds:
  - the merge record;
  - suite results with fail-direction arms;
  - the reservation table and its per-ref proof;
  - the topology (D-02..D-04);
  - the shared-stack owner;
  - the D-26 decision;
  - the D-01 out-of-scope list.
- `.planning/REQUIREMENTS.md`: the "Real-world operations readiness (RWO) — Phase 37" section, 35 traceability rows, and the coverage addendum dated 2026-10-07.

## Decisions Made

- **D-26 = `login-hint`.** The owner gave this answer in the orchestrator session on 2026-10-08, and it is recorded verbatim. 37-08/37-09 execute as planned.
- **Reservation numbering.** The plans' own filenames (V76..V83, one per plan) are the authority. RESEARCH's earlier V76-V82 per-sub-theme proposal is superseded; the evidence notes this.
- **37-01 left out of the traceability rows.** It declares all 35 IDs only because it mints them. Leaving it out keeps each row's plan list to the plans that implement that ID. Every listed plan's `requirements` contains the ID (diff rc=0).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Task 2's python verify replaced by an equivalent bash script**
- **Found during:** Task 1 (first attempt to run python for the result reader).
- **Issue:** The session's `block-base-python` PreToolUse guard refuses `/usr/bin/python3` in this project because no conda env is declared. So the plan's `<automated>` python one-liner cannot run here.
- **Fix:** `verify-rwo.sh` performs the same check in bash/grep. It takes the `**RWO-NNN**` ID set and the `| RWO-NNN | Phase 37` row set, and passes only when both have exactly 35 members and the sets are equal. The JUnit result reader was likewise written in bash.
- **Verification (both directions run):**
  - pre-edit file: `0 0`, rc=1 (the fail direction the acceptance criterion asks for);
  - post-edit file: `35 35`, rc=0;
  - copy with one row removed: `35 34`, rc=1;
  - copy with RWO-085's row renamed to RWO-086: `35 35`, rc=1, which proves the set comparison and not only the counts.
- **Files modified:** none in the repo (the scripts live in the session scratchpad).
- **Committed in:** n/a

**2. [Rule 1 - Bug] Reservation table sub-theme for V81 corrected before commit**
- **Found during:** Task 1, while cross-checking the evidence against the plans.
- **Issue:** The first draft labelled V81 / 37-27 as "37-C/37-D". 37-27's `requirements` is [RWO-041], which is 37-D.
- **Fix:** Changed the label to 37-D. Each owning plan's migration filename is now quoted in the evidence.
- **Committed in:** afee7b04

**3. [Plan premise] No new merge commit**
- **Issue:** The plan expects this plan to create the merge. origin/main had not moved since 394edcda (2026-10-07 21:43 UTC), so `git merge` was a no-op.
- **Effect:** The acceptance criterion still holds: `git log -1 --merges --format=%s` names origin/main (394edcda), and that SHA is in the evidence.

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 bug) + 1 plan-premise note.
**Impact on plan:** The verify was substituted, not weakened, and was run in both directions. There is no scope change.

## Issues Encountered

- The worktree-isolation guard refuses compound `git` commands and loops. Each per-ref `ls-tree` ran as its own plain command, with output written to scratch files.

## Fail-direction record

| Criterion | Pass (real tree) | Fail (broken input) |
|-----------|------------------|---------------------|
| origin/main ancestor of HEAD | rc=0 | reverse check (HEAD ancestor of origin/main), rc=1 |
| Boot 4.1 line | line 2 `4.1.1`, rc=0 | same grep for `3\.`, rc=1 |
| V75 present | rc=0 | `test -f V76__nonexistent.sql`, rc=1 |
| Suite results reader | six OK, rc=0 | `failures="1"` copy rc=1; `tests="0"` copy rc=1; missing file rc=2 (VOID) |
| V76..V83 unused | 0 hits on 8 refs; V75 control hit on 4 refs | planted `V79__planted_collision.sql` gives 1 hit |
| 35 IDs = 35 rows | `35 35` rc=0 | pre-edit `0 0` rc=1; row removed `35 34` rc=1; set mismatch rc=1 |
| Rows vs plan frontmatter | diff rc=0 (35 lines) | fail direction not run separately: diff exits non-zero on any byte difference |

## User Setup Required

None. No external service configuration required.

## Next Phase Readiness

- 37-02 can start. Its comparison point is "the full suite with ACCESS_STRICT_SCOPING unset", which this baseline does NOT contain (six suites only), so 37-02 must take that reading itself. The evidence file says so.
- The shared compose stack belongs to this worktree: core-java, frontend, edge-go, mcp-server, keycloak, postgres and rabbitmq were recreated here at 2026-10-07 21:45 UTC, after the 394edcda merge. Each sub-theme gate must rebuild every image and run `check-runtime-freshness.sh`.
- STATE.md and ROADMAP.md are not touched. The orchestrator owns them.

## Self-Check: PASSED

- FOUND: `.planning/phases/37-real-world-operations-readiness/evidence/37-01-baseline.md` (contains `V83`)
- FOUND: `.planning/REQUIREMENTS.md` (contains `RWO-085`)
- FOUND in HEAD: afee7b04, 46b9e4dc, a8ef6d81
- Measured commits: `git rev-list --count d903df2e..HEAD` = 3 before this SUMMARY's commit

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-08*
