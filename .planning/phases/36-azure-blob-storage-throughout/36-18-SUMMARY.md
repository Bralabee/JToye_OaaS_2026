---
phase: 36-azure-blob-storage-throughout
plan: 18
subsystem: infra
tags: [ci, e2e-nightly, playwright, azurite, runtime-parity, blob]

requires:
  - phase: 36-azure-blob-storage-throughout
    provides: "36-02 Azurite in SERVICES; 36-08 restore drill; 36-12 URL + Content-Type gates; 36-13 storage-images spec; 36-16 residue gate; 36-17 metrics"
provides:
  - "A real CI run of e2e-nightly.yml on the phase branch (36552435811) that brought the stack up on Azurite and executed Playwright: 325 results, 319 passed, 0 failed, 6 skipped"
  - "The three Phase 36 runtime gates (URL resolution, stored Content-Type, restore drill) passing in CI"
  - "Final parity readings: runtime freshness PASS 4/4 with 0 unverified; 0 behind origin/main"
  - "Five codebase-map citations re-pointed after Phase 36 line shifts (Operational Contracts green again)"
affects: [phase-36-verification, ship, phase-29]

actuals:
  tokens: 2293
  tasks: 2
  commits: 2
plan_head_before: 536daf413c52acf2efab94c44962f99e9453f995

tech-stack:
  added: []
  patterns:
    - "Resolve a concrete run id (createdAt >= dispatch, headSha == pushed HEAD, exactly one) before watching; bounded event-native watch; every end state printed"
    - "Read a CI verdict from the downloaded report artifact and the step log, not from the run badge"

key-files:
  created:
    - .planning/phases/36-azure-blob-storage-throughout/evidence/36-18-nightly-run.txt
    - .planning/phases/36-azure-blob-storage-throughout/36-18-SUMMARY.md
  modified:
    - .planning/codebase/STACK.md
    - .planning/codebase/INTEGRATIONS.md
    - .planning/phases/36-azure-blob-storage-throughout/deferred-items.md

key-decisions:
  - "Owner chose push-and-dispatch (Task 1). This plan made three pushes of the same branch: the dispatch push, the citation fix, and the final close-out. There was no force, no --no-verify and no PR"
  - "The Operational Contracts doc-citation failure was a Phase 36 regression and reds the branch's CI, so it was fixed here (Rule 3). The core-java Trivy failure comes from the Boot BOM, was not caused by the phase, and was recorded rather than absorbed"
  - "No re-dispatch of the nightly after ae4ceb43: that commit changes only .planning/codebase docs, which no image or nightly step reads"

requirements-completed: [BLOB-04]

coverage:
  - id: D1
    description: "The nightly on the phase branch brings the stack up on Azurite and executes Playwright (#683's cause removed, not worked around)"
    requirement: BLOB-04
    verification:
      - kind: e2e
        ref: "gh run 36552435811 — report.json: executed 325, passed 319, failed 0, skipped 6; conclusion success"
        status: pass
      - kind: other
        ref: "gh run 36511482252 (historical fail direction): minio-init Error unauthorized at the stack build, Playwright never ran"
        status: pass
    human_judgment: false
  - id: D2
    description: "The restore-drill, URL-resolution and Content-Type gates ran and passed in the same CI run"
    requirement: BLOB-04
    verification:
      - kind: e2e
        ref: "gh run 36552435811 steps 9, 10, 20 — PASS 21/21; PASS 0 of 21 outside allowlist; PASS arm A 0, arm B 23 = live 23"
        status: pass
    human_judgment: false
  - id: D3
    description: "The delivered local runtime matches the branch and the branch is not behind its base"
    verification:
      - kind: other
        ref: "scripts/check-runtime-freshness.sh rc 0 (PASS 4/4, 0 unverified) at ae4ceb43; drift arm rc 1, VOID arm rc 2"
        status: pass
      - kind: other
        ref: "scripts/check-branch-behind-base.sh rc 0 (0 behind db725c94); fail arm rc 1, VOID arm rc 2"
        status: pass
    human_judgment: false
  - id: D4
    description: "Codebase-map citations resolve again after Phase 36 line shifts"
    verification:
      - kind: other
        ref: "scripts/check-doc-citations.sh rc 1 (5 FAIL) before ae4ceb43, rc 0 after; CI run 36555251078 Operational Contracts success"
        status: pass
    human_judgment: false

duration: 127min
completed: 2026-09-29
status: complete
---

# Phase 36 Plan 18: The nightly runs again on the phase branch Summary

**The nightly ran as workflow_dispatch run 36552435811 on the pushed phase branch and passed. It pulled Azurite, brought the stack up and executed 325 Playwright tests: 319 passed, 0 failed, 6 skipped, counted from the downloaded report. In the same run the URL gate (21/21), the Content-Type gate (0 of 21 outside the allowlist) and the restore drill (arm A 0, arm B 23 = live 23) all passed. Every scheduled run since 2026-09-25 had died at image pull on `minio-init unauthorized`. The branch ends 0 behind main, and the running stack matches it (PASS 4/4, 0 unverified).**

## Performance

- **Duration:** 127 min, mostly waiting: the Java suite took 30 min, the nightly 22.7 min and CI/CD about 69 min
- **Started:** 2026-09-29T09:26:07Z
- **Completed:** 2026-09-29T11:33Z
- **Tasks:** 2 of 2 (Task 1 was resolved by the owner before this executor started)
- **Files modified:** 5 (2 created, 3 modified)

## Accomplishments

- **Full local suite before any push** (HEAD 536daf41):
  - Java unit: 1297 tests, 0 failures, 0 errors, 1 skipped.
  - Java integration: 738 tests, 0 failures, 0 errors, 1 skipped.
  - The Java counts come from 315 `TEST-*.xml` files under `core-java/build-local/test-results`, all newer than the run start. Neither task was UP-TO-DATE.
  - Jest: 172 suites, 1878 tests.
  - check-render-invariants, render-golden and check-env-contract: all rc 0.
- **Push:** 5b6e76bd..536daf41 was a fast-forward. The pre-push hook's P-2, P-3 and P-4 passed, and `ls-remote` matched HEAD.
- **The nightly** (run 36552435811):
  - Dispatched at 09:56:48Z. The run was created at 09:56:50Z; its headSha equals the pushed HEAD and it was the only match.
  - The watch ended on `completed-success` at 10:19:31Z, inside its 60-min deadline.
  - All 20 executed steps succeeded. "Dump logs on failure" and "Escalate" were skipped.
- **Read to the report, not the badge:**
  - The downloaded `report.json` gives 325 results: 319 passed, 0 failed, 6 skipped, from 28 spec files.
  - Playwright's own stats agree: expected 319, unexpected 0, flaky 0.
  - `storage-images.spec.ts` passed on both mobile and desktop.
  - The skip budget is PASS at 6 of 6.
- **Phase 36 gates in CI:**
  - URL gate: PASS, 21 references answer 200, 0 on the retired origin.
  - Content-Type gate: PASS, 0 of 21 outside the allowlist.
  - Restore drill: PASS, arm A restored 0 and arm B restored 23 = live 23.
- **Historical fail direction:** run 36511482252 (main, 2026-09-29 02:12Z) died at "Build and start the stack" with `minio-init Error unauthorized` and never reached Playwright. The same pull-error pattern counts 2 lines in that log and 0 in the new run's. Five straight scheduled failures ran from 09-25 to 09-29.
- **Parity at ae4ceb43:**
  - Runtime freshness: PASS, 4 of 4 fresh, 0 unverified.
  - Behind base: 0 behind, 119 ahead of db725c94.
  - core-java :9090 health: 200.

## Falsification record (both directions)

| Instrument | Fail arm | Pass on real input |
|---|---|---|
| Run-ID resolver | bogus branch rc 2; real branch + wrong sha rc 2 | known run 34722659896 rc 0; the real dispatch resolved 36552435811 |
| Watcher | failed run 36511482252 rc 1 (completed-failure); bogus id rc 4 (unreachable) | success run 35946039057 rc 0; real run rc 0 (completed-success) |
| Report assertion | `{"suites":[]}` counts 0, so `[ total -gt 0 ]` exits 1; a zero-byte file fails `[ -s ]` | 325 |
| Plan evidence awk | doctored copy scores run=0 executed=0 success=0 | run=1 executed=1 success=1 |
| check-runtime-freshness | throwaway clone with one newer core-java commit, same compose project: rc 1 DRIFT; nonexistent compose file: rc 2 | rc 0, PASS 4/4 |
| check-branch-behind-base | `--head origin/main~3` rc 1; nonexistent base rc 2 | rc 0 |
| check-doc-citations | pre-fix tree rc 1 (5 FAIL) | rc 0 after ae4ceb43 |
| Pull-error scan | 2 hits in 36511482252 | 0 hits in 36552435811 |

## Task Commits

1. **Task 1: owner approval to push** (checkpoint:decision). RESOLVED by the owner as `push-and-dispatch`; no commit.
2. **Task 2: dispatch, read the run to its report, final parity.**
   - `ae4ceb43` (docs): re-point five codebase-map citations that Phase 36 line shifts broke. This is the deviation below.
   - `f6efb5ff` (docs): the evidence file and the deferred Trivy item.

**Plan metadata:** this SUMMARY's commit (docs: complete plan).

## Files Created/Modified

- `.planning/phases/36-azure-blob-storage-throughout/evidence/36-18-nightly-run.txt`: holds the run id, the times, the conclusion, the Playwright counts, each gate's line, the historical fail run, the instrument fail arms, both CI/CD runs and the parity readings.
- `.planning/codebase/STACK.md`: four citation line numbers updated, plus `netty-nio-client` replaced by `azure-core-http-netty`.
- `.planning/codebase/INTEGRATIONS.md`: the stripe breaker citation changed from 727-732 to 740-745.
- `.planning/phases/36-azure-blob-storage-throughout/deferred-items.md`: the core-java Trivy / jackson-databind entry.

## Decisions Made

- Push-triggered CI/CD is recorded separately and is never counted as the nightly's verdict.
- The nightly was not re-dispatched after ae4ceb43, because that commit touches only `.planning/codebase/*.md`, which neither an image nor a nightly step reads.
- Two ops-contracts gates could not run locally, because the machine's Python policy refuses a bare `python3` with no project env: `check-image-supply-chain.sh` (VOID locally) and `check-logout-preflight.py`. I did not choose an env. CI ran both in run 36555251078, and Operational Contracts passed.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Five codebase-map citations broken by Phase 36 line shifts**
- **Found during:** Task 2, reading the push-triggered CI/CD run 36552432346.
- **Issue:** Operational Contracts failed at `check-doc-citations.sh` (C-3), and 14 later ops steps were skipped:
  - STACK.md cited `infra/docker-compose.yml:13-14`, `core-java/build.gradle.kts:21-52`, `:394` and `:195`.
  - INTEGRATIONS.md cited `application.yml:727-732`.
  - Commits in 36-01, 36-02, 36-06, 36-14 and 36-15 had moved all five targets, and no earlier plan ran this gate. CI had been green on 5b6e76bd.
  - STACK.md also still named the AWS SDK's `netty-nio-client` as a transitive netty source.
- **Fix:** re-pointed the citations to 19-20, 21-54, 415, 197 and 740-745, and named `azure-core-http-netty`.
- **Files modified:** .planning/codebase/STACK.md, .planning/codebase/INTEGRATIONS.md
- **Verification:**
  - check-doc-citations: rc 1 before, rc 0 after. check-doc-versions and check-no-object-store-residue: rc 0.
  - 13 of the 14 skipped ops gates ran locally with rc 0. The Python-dependent one was left to CI.
  - In CI run 36555251078, Operational Contracts is success.
- **Committed in:** ae4ceb43. It was pushed 536daf41..ae4ceb43 under the same sanction.

---

**Total deviations:** 1 auto-fixed (1 blocking).
**Impact on plan:** documentation only; runtime, images and tests are unchanged. It removes a Phase 36 regression that would have reddened the PR.

## Issues Encountered

- **The core-java image Trivy gate is red on both CI/CD runs.** This is recorded, not fixed; see deferred-items.md.
  - The only finding is `jackson-databind 2.21.4`, CVE-2026-68497 (HIGH), fixed in 2.21.6.
  - `dependencyInsight` shows the version is "Selected by rule", i.e. the Spring Boot 3.5.16 BOM. Neither HEAD nor main pins jackson.
  - The same job passed on 5b6e76bd at 2026-09-28T19:49Z (run 36469503402).
  - So it is the Trivy daily-DB time-bomb, not a Phase 36 change. main's next core-java image build will fail the same way.
  - The fix is a version bump of that exact artifact, in its own change. It blocks a green CI/CD on the phase PR until that bump lands on main or on the branch.
- **CI/CD run conclusions:**
  - 36552432346 (536daf41): failure (the doc citations plus Trivy).
  - 36555251078 (ae4ceb43): failure (Trivy only). Every test and gate job is success.

## User Setup Required

None.

## Next Phase Readiness

- BLOB-04 closes: the nightly on the phase branch executed Playwright and passed.
- Phase 36's plans are all summarised. The compose stack stays RUNNING for the end-of-phase human check (core-java on :9090, frontend on :3000, freshness PASS 4/4).
- Open before ship:
  - the jackson-databind bump (deferred-items.md 36-18);
  - the review series on the PR (push first, then review, then merge).

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-29*
