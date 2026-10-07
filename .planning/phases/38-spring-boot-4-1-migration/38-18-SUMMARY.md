---
phase: 38-spring-boot-4-1-migration
plan: 18
subsystem: infra
tags: [ci, temurin, trivy, e2e-nightly, playwright, gitleaks, spring-boot-4, ship-checklist]
status: complete

requires:
  - phase: 38-17
    provides: branch merged with origin/main (0 behind) and the runtime-parity proofs
  - phase: 38-15
    provides: CVE floors and the local Trivy gate on the 2026-10-05 DB
  - phase: 38-11
    provides: check-boot-config-keys.sh CI gate
provides:
  - origin/phase-37-spring-boot-4-1 at acee00c5 (fast-forward, no PR)
  - CI/CD run 37348823924 read job by job — Temurin 25.0.4+1 unit 1490/0/1 and integration 771/0/6, OpenAPI gate OK, ops contracts 25/25, core-java Trivy image gate 0 fixable CRITICAL/HIGH
  - Nightly run 37348829067 on the Boot-4 stack — report.json executed 325, passed 319, failed 0, skipped 6; skip budget PASS
  - gitleaks allowlist for the golden-fixture idempotency key and one prose false positive, proven in both directions
  - The ship checklist (#706, #739, changelog via squash, D3 review series, contract notes)
affects: [ship, phase-38-verification]

actuals:
  tokens: 5681       # chars/4 over git diff 67a1b1ea..4e39487f (22727 chars, 4 files)
  tasks: 2           # Task 1 resolved by the owner before dispatch; Task 2 executed here
  commits: 2         # git rev-list --count 67a1b1ea..HEAD at SUMMARY time
plan_head_before: 67a1b1eac9fa14b889188add73256f4ecb4ebed9
plan_head_after: 4e39487f434dcbc6d6d3e68c89c14144837c7004

tech-stack:
  added: []
  patterns:
    - "Resolve a concrete run id (headSha == pushed HEAD, createdAt >= push/dispatch, exactly one), then a bounded watch that prints every end state"
    - "Read CI test totals from the uploaded Gradle HTML report, not from BUILD SUCCESSFUL"
    - "A secret-scanner false positive in a byte-guarded fixture gets a both-ends-anchored content allowlist, so the squash commit main scans is covered too"

key-files:
  created:
    - .planning/phases/38-spring-boot-4-1-migration/evidence/38-18-ci-and-nightly.txt
  modified:
    - .gitleaks.toml
    - .gitleaksignore
    - .planning/phases/38-spring-boot-4-1-migration/38-05-SUMMARY.md

key-decisions:
  - "38-18: the owner chose push-and-dispatch (exact words \"push-and-dispatch (Recommended)\"); one push landed, a fast-forward with no force, no --no-verify and no PR"
  - "38-18: the pre-push gitleaks refusal was fixed at the cause (Rule 3): a content allowlist anchored to exactly golden-guest-key-0001, because the fixtures are byte-guarded and a commit fingerprint would not cover the squash commit; a fingerprint plus an inline allow for the prose hit"
  - "38-18: the six CI integration skips are explained, not absorbed: one @Disabled bootstrap method and five MailHog-reachability assumptions, which are unchanged from origin/main"
  - "38-18: the close-out docs commits are not pushed here; the ship step pushes them, so no second CI run is left unread"

requirements-completed: [BOOT4-14, BOOT4-12, BOOT4-01]  # the plan's list, verbatim; the shared-ID gate decides which are marked now

coverage:
  - id: D1
    description: "The branch is on origin only with the owner's go-ahead and 0 behind origin/main; ls-remote equals HEAD; no PR opened"
    requirement: BOOT4-14
    verification:
      - kind: other
        ref: "scripts/check-branch-behind-base.sh rc 0 (fail arm rc 1, VOID arm rc 2); git ls-remote == acee00c5; gh pr list --head -> 0 — evidence §1, §2, §6"
        status: pass
    human_judgment: false
  - id: D2
    description: "The push-triggered CI/CD run for HEAD succeeded on Temurin, and every job was read: unit 1490/0/1, integration 771/0/6 (skips explained), OpenAPI gate OK, ops contracts 25/25 with check-boot-config-keys PASS, core-java Trivy image gate 0 vulnerabilities"
    requirement: BOOT4-01
    verification:
      - kind: other
        ref: "gh run view 37348823924 --json conclusion -> success; jobs and logs via the API; HTML report totals with empty and doctored arms — evidence §4"
        status: pass
    human_judgment: false
  - id: D3
    description: "The Trivy image gate on the Boot-4 core-java image finds 0 fixable CRITICAL/HIGH (OS and app.jar) on CI's DB"
    requirement: BOOT4-12
    verification:
      - kind: other
        ref: "Build and Push Images (core-java) step 10 success; Report Summary alpine 0 / app/app.jar 0 — evidence §4.6"
        status: pass
    human_judgment: false
  - id: D4
    description: "The nightly ran on the branch's Boot-4 runtime and executed Playwright: 325 executed, 319 passed, 0 failed, 6 skipped; skip budget, OpenAPI-vs-running, URL, Content-Type, cart-identity and restore-drill gates all PASS"
    requirement: BOOT4-14
    verification:
      - kind: e2e
        ref: "gh run 37348829067 — downloaded report.json counts (plan verify 325; empty-report and doctored arms) — evidence §5"
        status: pass
    human_judgment: false
  - id: D5
    description: "Ship checklist written: Closes #706, supersedes #739, changelog heading cites the PR under squash, D3 review series, contract notes, no attribution"
    verification:
      - kind: other
        ref: "evidence §7; the 16-pattern acceptance grep gives 16/16, and a doctored copy gives 14/16"
        status: pass
    human_judgment: true
    rationale: "Whether the checklist is complete for the ship step is a reviewer judgment"

duration: 58min
completed: 2026-10-05
---

# Phase 38 Plan 18: CI and the nightly on the pushed Boot-4 branch Summary

**The branch is pushed as acee00c5 (fast-forward, 0 behind origin/main, no PR). CI/CD run 37348823924 succeeded on Temurin 25.0.4+1. Unit: 1490 tests, 0 failed, 1 skipped. Integration: 771 tests, 0 failed, 6 skipped. The core-java Trivy image gate found 0 fixable CRITICAL/HIGH. Nightly run 37348829067 built the Boot-4 stack and ran Playwright: 325 executed, 319 passed, 0 failed, 6 skipped, read from the downloaded report.json.**

## Performance

- **Duration:** 58 min, mostly waiting (CI/CD about 52.5 min, the nightly about 22.75 min, running in parallel).
- **Started:** 2026-10-05T17:26:43Z
- **Completed:** 2026-10-05T18:25Z
- **Tasks:** 2 (Task 1 was resolved by the owner before dispatch; Task 2 ran here)
- **Files modified:** 4 (1 evidence file created; 3 changed by the gitleaks fix)

## Accomplishments

- **Parity before the push.** The behind-base gate passed: 0 behind origin/main 03022f21. Its fail arm gave rc 1 and its VOID arm rc 2. The tree was clean apart from the untracked `milestone.lock`.
- **First push refused.** The pre-push P-3 gitleaks check found 3 generic-api-key hits, all false positives:
  - `golden-guest-key-0001` in the two byte-guarded golden fixtures;
  - the class name `Jackson3WireContractTest` in 38-05-SUMMARY prose.
- **The cause was fixed, and the second push landed.** acee00c5 adds the allowlist and passes P-2, P-3 and P-4. The push was a fast-forward from 767f5658, and `ls-remote` equals HEAD.
- **CI/CD 37348823924, read job by job.** Every job is success, except three that are skipped by their `if:`:
  - Branch Not Behind Base runs on pull requests only;
  - the two deploy jobs run on main only.
  - Temurin: `Resolved Java 25.0.4+1 … Distribution: temurin`. This closes RESEARCH Open Question 3.
  - Unit: 1490 / 0 / 1 skipped, read from the uploaded HTML report. That equals the local wave-6 count.
  - Integration: 771 / 0 / 6 skipped. One is the `@Disabled` bootstrap method. Five are MailHog assumptions in files identical to origin/main.
  - The aggregate coverage floor passed. The OpenAPI gate printed "OK: OpenAPI spec matches the reviewed snapshot". Operational Contracts passed 25/25 steps. check-boot-config-keys passed (218 keys, 36 excludes).
  - The core-java Trivy image gate (v0.70.0, CRITICAL,HIGH, ignore-unfixed, exit-code 1) reported 0 vulnerabilities in alpine 3.24.2 and 0 in `app/app.jar`.
- **Nightly 37348829067, dispatched on the branch:**
  - It was created 2 s after the dispatch, with headSha equal to HEAD.
  - The stack built and became healthy; core-java resolved every `spring-boot*` artifact to 4.1.1 (0 unresolved 3.x lines).
  - The OpenAPI-vs-running gate passed: 0 differences across 109 paths.
  - report.json: 325 executed, 319 passed, 0 failed, 6 skipped. Playwright's stats agree: expected 319, unexpected 0, flaky 0. The skip budget passed at 6/6.
  - The URL, Content-Type, cart-identity and restore-drill gates all passed.
- **Ship checklist written** (evidence §7). It covers #706, #739, the changelog entry under squash merge, the D3 review series, every contract note the PR body must carry, the gitleaks 8.27.2 watch item, and no attribution lines.

## Falsification record (both directions)

| Instrument | Fail arm | Pass on real input |
|---|---|---|
| check-branch-behind-base | `--head origin/main~3` rc 1; bogus base rc 2 | rc 0, 0 behind |
| gitleaks push range | before the fix: rc 1, 3 findings | rc 0 |
| gitleaks simulated squash | key `-0002`: rc 1; inline allow removed: rc 1; my first ignore-file comment: rc 1 | rc 0 |
| Run-id resolver | bogus branch rc 2; wrong sha rc 2 | 37348823924 and 37348829067, one match each |
| Watcher | failed run rc 1; bogus id rc 4; 1 s deadline rc 5 | success run rc 0; both real runs rc 0 |
| HTML report reader | empty page rc 2; doctored failures 0→3 reads 3 | 1490/0/1 and 771/0/6 |
| report.json counts | `{"suites":[]}` gives 0; zero-byte file fails `[ -s ]`; doctored copy gives failed=319 | 325 executed, 0 failed |
| Unresolved-3.x pattern | a synthetic `3.5.15 (*)` line matches | 0 matches in the nightly log |
| Evidence acceptance grep | doctored copy 14/16 | 16/16 |
| Root-pin guard | from the wrong cwd: FATAL root-mismatch, rc 1 | PIN OK |

## Task Commits

1. **Task 1: owner push decision.** Resolved before dispatch ("push-and-dispatch (Recommended)"). No commit.
2. **Task 2: push, CI/CD, nightly, ship checklist.**
   - `acee00c5` (fix): gitleaks allowlist for the golden-fixture key and the prose false positive. This is the deviation below.
   - `4e39487f` (docs): the evidence file.

**Plan metadata:** this SUMMARY's commit, then the STATE/ROADMAP/REQUIREMENTS commit.

## Files Created/Modified

- `.planning/phases/38-spring-boot-4-1-migration/evidence/38-18-ci-and-nightly.txt`: holds the decision, the push and its refusal, the instrument arms, the per-job CI conclusions with Temurin, totals and Trivy, the nightly steps and report counts, and the ship checklist.
- `.gitleaks.toml`: a content allowlist `^golden-guest-key-0001$`, with its reasoning.
- `.gitleaksignore`: a fingerprint for 370cc0fc's prose line.
- `.planning/phases/38-spring-boot-4-1-migration/38-05-SUMMARY.md`: an inline `gitleaks:allow` on line 146. The text is unchanged.

## Decisions Made

See `key-decisions` in the frontmatter.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The pre-push gitleaks gate refused the push on three false positives**
- **Found during:** Task 2, first push.
- **Issue:** P-3 found generic-api-key hits in 38-01's capture commit e12177e1 and in 38-05's SUMMARY commit 370cc0fc. Earlier plans never pushed, so nothing had scanned them.
  - The fixtures are byte-guarded by `GoldenFixturesIntegrityTest` and cannot carry an inline allow.
  - CI's gitleaks.yml scans main's single squash commit after merge, so a commit fingerprint alone would leave main red.
- **Fix:**
  - A content allowlist anchored at both ends to exactly `golden-guest-key-0001`.
  - A `.gitleaksignore` fingerprint for the historical prose commit.
  - An inline `gitleaks:allow` on the live prose line.
- **Files modified:** .gitleaks.toml, .gitleaksignore, 38-05-SUMMARY.md
- **Verification:**
  - Push range: rc 1 before, rc 0 after.
  - Simulated squash commit: real rc 0; a changed key rc 1; the inline allow removed rc 1.
  - The real arm caught my own first ignore-file comment, which quoted the prose and tripped the rule. I reworded it.
  - Limit: measured on gitleaks 8.30.1 only. CI pins 8.27.2, so the PR's gitleaks job is a ship-checklist watch item.
- **Commit:** acee00c5, pushed under the owner's push-and-dispatch decision.

### Instrument corrections (own checks)

**2. A doctoring arm that changed nothing was discarded and redone.** Line-based `sed` could not reach the multi-line HTML counter, so the "doctored" file equalled the original. A `cmp` showed this. The arm was redone with `perl -0`, and the reader then reports failures=3.

**3. A wrong printed rc was caught and fixed.** In one loop, `$(basename …)` inside the `echo` reset `$?`, so the printed rc was basename's. Every rc in the evidence was re-captured on the same line as its command.

**4. The plan's report verify fails open on a zero-byte file.** `jq … | length` prints nothing with rc 0 on an empty file, so the "jq cannot read the file" direction does not fail by itself. The `[ -s ]` test catches it, as step 17 of the workflow does. This is recorded in evidence §5.1.

---

**Total deviations:** 1 auto-fixed (blocking) and 3 corrections to my own instruments.
**Impact on plan:** No production code changed. The scanner allowlist is exact to one value and one historical line, and every must-have is met.

## Issues Encountered

Recorded here and not filed. None breaks a gate, loses data or is an observed failure:
- 20 build-log lines in the nightly request `spring-boot*:3.5.15`. All of them resolve up to 4.1.1, so some third-party starter still declares a 3.5 dependency.
- The CI integration suite skips 5 MailHog-dependent methods, because no MailHog runs in CI. This predates the phase.
- The close-out docs commits (`4e39487f` and the SUMMARY/STATE commits) are local. The ship step's push will run CI/CD again on a HEAD that differs from acee00c5 only in `.planning/phases/**`.

## Known Stubs

None.

## Threat Flags

None beyond the plan's threat model:
- **T-38-43:** mitigated by the owner decision, a plain fast-forward push with hooks (no force, no --no-verify), the behind-base check before the push, and the image gate read from its job.
- **T-38-44:** mitigated by concrete run ids (headSha and createdAt, one match each), every watch end state printed, and counts read from report.json and the HTML reports.
- **T-38-SC:** no package was installed.
- The gitleaks allowlist narrows a security scanner. It is scoped to one exact value and one historical file:line. It is listed in the ship checklist so the PR scan is watched.

## User Setup Required

None.

## Next Phase Readiness

- Ready for 38-19's successors or the ship step. Every CI-only proof of the phase has run on the pushed Boot-4 branch and been read to its outcome.
- For /gsd-ship: follow evidence §7 in order. Open the PR (feat/fix title, "Closes #706", supersedes #739, the contract notes), add the changelog heading with the PR number, run the D3 review series, merge by squash, then close #739.

## Self-Check: PASSED

- FOUND: .planning/phases/38-spring-boot-4-1-migration/evidence/38-18-ci-and-nightly.txt
- FOUND: acee00c5, 4e39487f (git log)
- Remote: origin/phase-37-spring-boot-4-1 == acee00c5; CI 37348823924 conclusion success; nightly 37348829067 conclusion success
- commits: 2, measured as `git rev-list --count 67a1b1ea..HEAD`

---
*Phase: 38-spring-boot-4-1-migration*
*Completed: 2026-10-05*
