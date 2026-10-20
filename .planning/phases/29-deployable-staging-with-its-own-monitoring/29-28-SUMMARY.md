---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 28
subsystem: ci-cd
tags: [dply-02, dply-01, merge-gate, deploy-staging, digest-parity, rollback-rule, dispositions, d3-review]
status: complete   # all three tasks done; the first green CI deploy ran on the second attempt (see Deviations 2 and 3)

requires:
  - phase: 29-27
    provides: the approved 20-row dispositions table, its manifest hash 1d50dd2c89e8 and the check-dply02-dispositions.sh modes this plan applied
  - phase: 29-21
    provides: the deploy-staging job (OIDC, kubelogin, CR-02 guard, names-only preflight, full-sha apply, rollout, digest parity, smoke test) and the DEPLOY_STAGING_ENABLED gate
  - phase: 29-14
    provides: the DPLY-01 staging-login spec this plan re-ran against the CI-deployed runtime
provides:
  - The phase merged to main (PR #984, squash bffd2f74) behind a four-finding D3 round 1, a zero-admissible round 2 and a Review-Record
  - The first green CI deploy of the phase (run 39471893302, Deploy to Staging success), with digest parity from the operator host (rc 0, three services MATCH) and the staging-login spec (2 passed)
  - A fix to the digest-parity gate (PR #986, issue #985): it waits for terminating pods to drain, and the filter that does so is exercised by the selftest (33 arms)
  - The approved dispositions applied: 18 comments posted and read back equal, 10 issues closed, 4 already closed and recorded, 4 left open as approved; --post-close rc 0 on 20 rows
  - evidence/29-28-merge-deploy.txt, the closing evidence for #99
affects: [phase 29 verification, phase 29 security sign-off, phase 32]

actuals:
  tasks: 3
  # MEASURED per branch (the PRs were squash-merged, so no single linear range exists): #984 `git rev-list --count 652ac77d..40595f82` = 6
  # (the 8 commits in 652ac77d..6e42c7e7 less the two 29-19/29-27 commits 4dfb6ae2 and 6e42c7e7), #986 `git rev-list --count bffd2f74..bf85ae46` = 7.
  commits: 13
  tokens: 20300   # estimateTokens (chars/4) over the lines ADDED by those 13 commits (29495 + 16045 characters across the two PRs) plus the closing docs PR's added lines
plan_head_before: 652ac77ddedf5b3a59861ac2c8737a0f3178a58c   # last 29-27 commit; Task 1 ran on this head
plan_head_after: 33585b230a95e37100c3f4e0b19dcacf4e37123e    # the #986 squash merge on main, the last 29-28 code change; this SUMMARY's own commit is not counted

tech-stack:
  added: []
  patterns:
    - "A rollout reported complete is not a settled pod set: a gate that reads pods right after `rollout status` has to wait out the old replicas, and the wait has to be under test (a misspelt filter key still passed the first selftest)"
    - "The rollback rule is a recorded, read-back act: DEPLOY_STAGING_ENABLED set to false and read back as false on a non-green first run, with no issue touched"
    - "The owner's approval is verified (--verify-approval) and the state re-read (--pre-close) before the first comment, and the order is evidenced by timestamps"

key-files:
  created:
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/29-28-SUMMARY.md
  modified:
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-28-merge-deploy.txt
    - .planning/STATE.md
    - .planning/ROADMAP.md
    - scripts/check-deploy-digest-parity.sh   # in PR #986
    - scripts/check-deploy-rollback-guard.sh  # created in PR #984 round 1
    - scripts/deploy.sh                       # PR #984 round 1
    - .github/workflows/ci-cd.yaml            # PR #984 round 1 and PR #986

key-decisions:
  - "A non-green first deploy is rolled back by switching DEPLOY_STAGING_ENABLED off and reading it back, never by re-labelling or touching an issue; the fix goes through a PR (applied for real on attempt 1)"
  - "The Keycloak in-cluster forwarded-header residual is accepted, not fixed: Traefik overwrites client-supplied forwarded headers, so only a pod-network caller can spoof them (measured below); the trusted-proxies change is tracked separately"
  - "The parity gate reads pods once per poll as one JSON list and sorts them with one jq program, so the selftest feeds the same program the live gate runs"

requirements-completed: [DPLY-02]   # DPLY-02 is marked here, as 29-27's SUMMARY said it would be: the record was approved in 29-27 and applied here. DPLY-01 is carried by 29-14 and re-proven on the CI-deployed runtime here; DPLY-03 by 29-25 and 29-19.

coverage:
  - id: D1
    description: "deploy-staging runs on the merge commit and is green end to end; the first green CI deploy of the phase (#99, D-04)"
    requirement: DPLY-02
    verification:
      - kind: integration
        ref: "Run 39471893302, job Deploy to Staging success at 2026-10-20T19:35Z; steps 1-17 success, step 18 Rollback skipped (evidence/29-28-merge-deploy.txt, Task 3 attempt 2)"
        status: pass
      - kind: integration
        ref: "Fail direction: run 39440780472 on bffd2f74, step 15 Digest parity FAILURE, steps 16-17 skipped, step 18 Rollback ran (evidence, Task 3 attempt 1)"
        status: pass
    human_judgment: false
  - id: D2
    description: "The running staging pods' digests equal the merge commit's images, from the operator host as well as in the job"
    requirement: DPLY-02
    verification:
      - kind: integration
        ref: "bash scripts/check-deploy-digest-parity.sh --context jtoye-stg-aks --tag 33585b230a95e37100c3f4e0b19dcacf4e37123e -> rc 0; core-java sha256:53c33c05, edge-go sha256:4c46f907, frontend sha256:353af9e7 all MATCH"
        status: pass
      - kind: unit
        ref: "Fixed gate selftest 33/33; three break arms each turned a named arm red and were restored by sha256 (POD_JQ key misspelt, POD_JQ drops terminating pods, stuck-pod check -> if false)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The DPLY-01 login spec passes against the CI-deployed runtime"
    requirement: DPLY-01
    verification:
      - kind: e2e
        ref: "staging-login spec, STAGING_E2E=1, desktop, https://app-staging.olajay.co.uk: 2 passed (13.5s), rc 0 at 2026-10-20T19:36:38Z"
        status: pass
    human_judgment: false
  - id: D4
    description: "The approved dispositions are applied, after the approval and the state are re-checked, and each posted comment equals the approved copy"
    requirement: DPLY-02
    verification:
      - kind: integration
        ref: "--verify-approval rc 0 at 19:36:44Z (pinned=19), --pre-close rc 0 at 19:36:53Z, both before the first comment at 19:36:56Z; 18 comments read back equal; --post-close rc 0, 20 rows, findings=0 at 19:37:59Z"
        status: pass
    human_judgment: false
  - id: D5
    description: "The branch was current with its base at PR open and again immediately before the flip; the PR body and squash messages carry no attribution line and no closing keyword"
    requirement: DPLY-02
    verification:
      - kind: integration
        ref: "check-branch-behind-base.sh rc 0 at PR open, at 2026-10-20T14:36:06Z (#984) and at 18:06:30Z-18:06:32Z (#986); --scan-text rc 0 on the PR body and both squash messages; stored bodies read back; attribution lines in both stored merge messages: 0 (evidence)"
        status: pass
    human_judgment: false
  - id: D6
    description: "Aggregate health reads 200 from a Ready core-java pod before each flip (mail indicator included)"
    requirement: DPLY-02
    verification:
      - kind: integration
        ref: "kubectl exec health curl: 200 at 2026-10-20T14:36:06Z (before #984) and 18:06:30Z-18:06:32Z (before #986)"
        status: pass
    human_judgment: false

duration: about 12 h 45 min of wall clock between the first Task 1 record and the last disposition (2026-10-20, 06:53Z to 19:38Z), nearly all of it review rounds, CI runs and one failed-and-fixed deploy
completed: 2026-10-20
---

# Phase 29 Plan 28: The Merge Gate, the First Green CI Deploy, and the Applied Dispositions

**Phase 29 is merged, CI deployed it to staging and the pods are provably the merge commit's images. It took two attempts: the first deploy failed at digest parity on a gate fault and was rolled back by the rule, the fault was fixed through its own reviewed PR, and the second deploy was green end to end. The 20-row dispositions table the owner approved in 29-27 is applied: #99 is closed for real, on a deploy that ran.**

## What this plan delivered

- **The first green CI deploy of the phase (D-04).** Run 39471893302 on the #986 merge commit `33585b23`: Deploy to Staging success at 19:35Z, steps 1-17 success (OIDC, kubelogin, CR-02 guard, names-only preflight, full-sha apply, rollout, digest parity, health, smoke test), step 18 Rollback skipped.
- **Runtime parity from outside the job.** `check-deploy-digest-parity.sh --context jtoye-stg-aks --tag 33585b23...` rc 0 from the operator host, three services MATCH. The staging-login spec passed 2 of 2 against the CI-deployed runtime.
- **#99 closed on evidence.** Its comment carries the run URL; the closing evidence is `evidence/29-28-merge-deploy.txt`.
- **The dispositions applied** (rows 20): 10 issues closed by this plan (#99 #100 #101 #112 #296 #297 #299 #300 #592 #859), 4 already closed and recorded (#271 #292 #293 #302), 4 left open as approved (#98 #294 #301 #304), 2 merged PRs untouched (#953 #954). `--post-close` rc 0, `findings=0`.
- **A digest-parity gate that survives a real rollout** (issue #985, PR #986), found by the first deploy failing.

## Performance

- **Duration:** about 12 h 45 min of wall clock (06:53Z to 19:38Z). The work was waiting for review rounds and CI, not typing: the PR opened after the 06:53Z Task 1 record, the review series was done by 14:36Z, attempt 1 ran 16:17Z-16:21Z, the fix merged 18:06Z, attempt 2 finished 19:35Z.
- **Tasks:** 3 of 3.
- **Live estate touched:** staging only, on subscription `c483d353` through the pinned `jtoye-stg-aks` context. Changes made for proofs (rollout restarts of edge-go and core-java on the same image, `set image` of core-java to `bffd2f74` twice) were each followed by a rollout undo and a parity read against the prior image; the net change from this plan is the CI deploy itself.

## Task record

- **Task 1: pre-PR checks and the PR** (`40595f82`). The full static suite on `652ac77d` was rc 0 on every gate after the residue-allowlist renumber (29-27 deviation 1); `check-branch-behind-base.sh` rc 0 at open; PR #984 opened; the body scanned (`--scan-text` rc 0; controls: a Co-Authored-By line rc 1, "closes #99" rc 1), the stored body read back with an empty diff and 0 attribution lines (positive controls 1 and 1).
- **Task 2: review series, flip, merge** (checkpoint, run by Claude, see Deviation 1). Round 1 `/code-review 984 --comment`: 4 admissible, all fixed. Round 2 `/review-verify 984` on `6e42c7e79a44`: admissible 0, Review-Record posted. At 2026-10-20T14:36:06Z the local head equalled the PR head, `check-branch-behind-base.sh` rc 0 (0 behind `a5dd4d94`) and core-java health read 200. `DEPLOY_STAGING_ENABLED` set to true and read back, the squash message scanned (rc 0), `gh pr merge 984 --squash` at 14:36:23Z, merge `bffd2f74`, 0 attribution lines in the stored message.
- **Task 3: attempt 1 failed, attempt 2 green, dispositions applied.** See Deviations 2 and 3, and the evidence file, which holds every output.

## Both Directions, Recorded

| Check | Fail direction | Pass direction |
|-------|----------------|----------------|
| The deploy itself | Run 39440780472 on `bffd2f74`: Digest parity step FAILED (core-java `0a462ba9` NOT IN THE EXPECTED SET beside `e46092ee` MATCH); health and smoke skipped; Rollback ran | Run 39471893302 on `33585b23`: steps 1-17 success, step 18 skipped |
| Digest parity gate, live | The gate as on main, copied into `scripts/` for one run, against a real terminating pod with a different digest: rc 1, `0a462ba9... NOT IN THE EXPECTED SET` + `e46092ee... MATCH` (the CI output reproduced) | The fixed gate, same state: "1 old pod(s) still terminating; waiting until the shared 180s deadline", then MATCH, rc 0 |
| Digest parity selftest | With the first fix, `deletionTimestamp` misspelt as `deletionTimestampX` still gave 25/25 rc 0 (round 1 reproduced it); after the redesign, the same misspelling: selftest rc 1 (old-pod-drains rc 1, two content checks fail); `POD_JQ` dropping terminating pods (`then empty`): rc 1; stuck-pod check turned into `if false`: `stuck-only` rc 0 where rc 1 was wanted, selftest rc 1 | 33/33 rc 0, each break restored by sha256 and clean again rc 0 |
| Rollback rule (T-29-106) | Applied for real on attempt 1: `DEPLOY_STAGING_ENABLED` false at 16:22:01Z, read back `[false]`, no issue touched | n/a (the rule is exercised only by a non-green run) |
| Public text (PR body, squash messages, comment copies) | `--scan-text` controls: Co-Authored-By line rc 1, "closes #99" rc 1 | Real bodies and messages rc 0, findings 0; stored bodies read back, attribution lines 0 |
| Approval binding | One character of the approved hash changed: rc 1 (29-27, commit `2d32870d`) | `--verify-approval` rc 0, `pinned=19`, at 19:36:44Z |
| Order of the apply | Not re-broken here (29-27's selftest holds the `--pre-close` fail arms) | `--verify-approval` 19:36:44Z and `--pre-close` 19:36:53Z precede the first comment at 19:36:56Z |
| Dispositions end state | Not run against the pre-apply state in this plan (the `--post-close` fail arms are in the 29-27 selftest, 64 arms) | `--post-close` rc 0, 20 rows, `findings=0` at 19:37:59Z |
| Login spec | The fail arms are 29-14's and were not re-run here | 2 passed, rc 0 on the CI-deployed runtime |

The two cells that say "not run here" are stated, not filled: this plan did not re-break the dispositions end state or the login spec.

## TDD Gate Compliance

Not a `type: tdd` plan. The #986 fix followed the same shape informally: the failing direction was reproduced on the cluster before the gate was changed, and each review round added a break arm before the fix was accepted.

## Deviations from Plan

### Plan shape not followed

**1. [Actor] Task 2 was a `checkpoint:human-action` for the owner and was run by Claude.**
- **What the plan said:** the owner runs the D3 review series, flips `DEPLOY_STAGING_ENABLED` and squash-merges.
- **What happened:** the owner said "you can do most of these things on my behalf" on 2026-10-20 and Claude ran the review series, the pre-flip checks, the flip and the merge.
- **What did not change:** every precondition the checkpoint names was met and evidenced (head equals PR head, behind-base rc 0 and health 200 before the flip, squash message scanned, variable read back, nothing landed between step 2 and the merge). The merge itself is still the squash-merge the plan describes.

**2. [Rule 1 - Bug, gate] Task 3 attempt 1 failed at digest parity on a terminating pod, and the rollback rule was applied.**
- **Found during:** Task 3, run 39440780472 on `bffd2f74`, 16:17Z-16:21Z.
- **Issue:** `rollout status` had printed "1 old replicas are pending termination" and then success; the parity gate listed every `app=core-java` pod, including one still in its grace period (`0a462ba9`, the previous revision) that still reported `ready=true`. The built image itself was correct.
- **Rule applied:** `DEPLOY_STAGING_ENABLED` set back to false at 16:22:01Z and read back `[false]`; #99 stayed open; no issue touched. The estate was left on the pre-merge images and `check-deploy-digest-parity.sh --tag 6e95ee6c...` (fixed gate) rc 0.
- **Fix:** issue #985, PR #986: the gate waits up to `--drain-timeout` (default 180 s, one deadline shared by all services) for terminating pods, fails if one stays and names it, then compares only live pods.
- **Review of the fix, four rounds, admissible 1 → 1 → 0 → 0** (Review-Resolution comments on #986): round 1 found the filter itself could not fail (a misspelt key left the selftest at 25/25) and the gate was redesigned around one `POD_JQ` program the selftest feeds; round 2 found the stuck-pod check had no arm of its own (`if false` left the selftest green) and arm `stuck-only` was added; rounds 3 and 4 admitted zero and round 4 ended the series. The squash merge `33585b23` is at 2026-10-20T18:06:34Z; #985 closed with it.
- **Commits:** `e6542927`, `ac867be2`, `e6a6c539`, `bf85ae46` (changelog entry required by docs-freshness), and evidence commits `3b32b3ca`, `97282566`, `449deb57`.

**3. [Order] Task 3 completed on attempt 2.** The pre-flip checks were repeated on the #986 head (`bf85ae46`, behind-base rc 0, health 200, main unchanged) and `DEPLOY_STAGING_ENABLED` was set to true again, read back `[true]`, at the #986 merge. Everything in "What this plan delivered" is attempt 2.

### Auto-fixed issues (round 1 of #984)

**4. [Rule 1 - Bug] Four admissible round-1 findings on the phase PR, all fixed before round 2** (Review-Resolution 2026-10-20T07:13:28Z: admissible 4, fixed 4, recorded 4, filed 0).
- `06271fef`: the staging rollback ran even when the apply had not been reached; it now runs only once the apply was reached (new gate `scripts/check-deploy-rollback-guard.sh`).
- `b1d28243`: `deploy.sh` undid only the Deployment whose rollout failed; it now undoes every Deployment whose generation moved.
- `c6547bc4`: `k8s/local` could not send mail under the required-STARTTLS production profile (invariants LOC-8 and INV-19 added).
- `54d28e86`: the digest-parity gate hung on a value flag with no value; it now VOIDs.
- `da31c859`: the HANDOFF gate count moved 56 to 57 for the new rollback-guard gate.

**5. [Order] The PR opened before 29-19 and before the 29-27 SUMMARY**, so round 1 could run while the cost data posted (recorded at the time in the evidence file). The two cost commits (`4dfb6ae2`, `6e42c7e7`) landed on the branch before the merge and are 29-19 and 29-27 commits, not counted here.

**6. [CI flake, not a code fault] A push-run frontend build failed once on `next/font/google` fetching Work Sans** ("Can't resolve '@vercel/turbopack-next/internal/font/google/font'") on `6e42c7e7`; the PR run on the same sha passed and attempt 2 of the push run completed success. Recorded in the evidence file; no change was made for it.

**Total:** 1 actor change, 1 gate bug found by the deploy and fixed through its own reviewed PR, 4 round-1 fixes, 1 order deviation, 1 flake. **Impact:** the plan's goal is met, one deploy attempt later than planned, and the digest-parity gate is stronger than the plan assumed.

## Cross-Cutting Quality Contracts

Recorded for what 29-28 changed: deploy tooling (a CI deploy job, `deploy.sh`, two gate scripts), one `k8s/local` mail overlay fix, planning documents and issue-tracker text. No page, public route, API, or client storage was touched by this plan. None silently dropped.

| Contract | Applies? | Reason |
|----------|----------|--------|
| Web performance (mobile-first) | N/A | No user-facing page or bundle changed; the frontend image deployed is the one the phase already built. |
| SEO / discoverability | N/A | No public surface changed. Staging stays non-indexable by design (29-15). |
| AI agent-readiness / machine-consumability | N/A | No API surface added or changed. The gate scripts' exit codes (0 clean, 1 findings, 2 VOID) are CLI, not an API. |
| Security | Applies | Threat model: T-29-101 (unreviewed main deploying) was mitigated by flipping `DEPLOY_STAGING_ENABLED` only at the reviewed merge, on both attempts, with the GitHub environment restriction coming from 29-21 and not re-read here. T-29-102 (stale images behind a green job): digest parity in the job and from the operator host against the merge sha, three services MATCH. T-29-103: bodies and squash messages written from files, scanned, stored bodies read back, 0 attribution lines. T-29-105: `--verify-approval` and `--pre-close` rc 0 before the first comment. T-29-106: the rollback rule was exercised for real on attempt 1. `/gsd-secure-phase` is a phase-level step not yet run. |
| Client-persisted identity lifecycle | N/A | No client-side storage or user-scoped state changed. |
| Falsifiable evidence + runtime parity | Applies | Evidence half: every changed gate was shown failing first (live failing direction on a real terminating pod, three break arms restored by sha256, a round-1 reproduction that the first selftest could not fail). Parity half: the running pods' digests were compared with the merge commit's images by content (sha256), in the job and from the operator host. One limit: the first selftest's blind spot was found by review round 1, not by the author's own break arms. |

## Follow-ups (owner-approved 2026-10-20, in flight as PRs)

None of these is a 29-28 deliverable; they are the findings of the deploy week, filed and approved by the owner.

- **#987, staging Grafana memory** (issue #968): MERGED as `1db60c70` (request 320Mi, limit 512Mi); it deploys through this pipeline.
- **#988, Keycloak trusted proxies** (issue #976): OPEN. Keycloak believes `X-Forwarded-*` only from the pod network; invariant INV-20 keeps it so.
- **#989, CI hardening batch**: OPEN. Selftests wired into CI, an edge probe job, a digest-less VOID, action sha pins.
- **#990, Grafana edge basic auth** (issue #968): OPEN. A basic-auth gate at the Traefik edge in front of staging Grafana.
- **Accepted residual: Keycloak in-cluster forwarded headers.** A pod-network caller can still spoof them. Traefik overwrites client-supplied forwarded headers, measured: a public request carrying a spoofed `X-Forwarded-Host` left the realm's `token_endpoint` unchanged, while the same header sent direct to `keycloak:8080` moved it to `evil.example`. The exposure is therefore the pod network and the trusted-proxies change in #988 narrows who may be believed. This plan recorded the residual as accepted; it did not measure what else on the pod network can reach Keycloak.

## What is not done

- **Phase 29 is not yet verified and not security-signed.** All 29 plans have a SUMMARY; `/gsd-verify-work` and `/gsd-secure-phase` remain.
- **#98, #294, #301 and #304 stay open** as the approved table says, each with its written reason.
- **`.planning/REQUIREMENTS.md` was not edited by this closing PR**; its DPLY rows still read "not yet planned" from before the phase was planned. This plan marks DPLY-02 in this SUMMARY's `requirements-completed` as 29-27 said it would.

## Known Stubs

None.

## Threat Flags

None beyond the plan's threat model.

## Commits (29-28-scoped, per branch, from `git log`)

PR #984 (`652ac77d..40595f82`, 6 commits; the squash merge is `bffd2f74`):

| Commit | Subject |
|--------|---------|
| `54d28e86` | fix(29): digest-parity gate refuses a value flag with no value instead of hanging |
| `06271fef` | fix(29): staging rollback runs only once the apply was reached |
| `b1d28243` | fix(29): deploy.sh undoes every Deployment its apply changed when one rollout fails |
| `c6547bc4` | fix(29): k8s/local can send mail again under the required-STARTTLS prod profile |
| `da31c859` | docs(29): move the HANDOFF gate count from 56 to 57 for check-deploy-rollback-guard |
| `40595f82` | docs(29-28): record the Task 1 pre-PR checks and the PR open |

PR #986 (`bffd2f74..bf85ae46`, 7 commits; the squash merge is `33585b23`):

| Commit | Subject |
|--------|---------|
| `e6542927` | fix(29): digest parity waits for the old replicas to drain before it compares |
| `3b32b3ca` | docs(29-28): record Task 2, and Task 3 attempt 1 failing at digest parity with the rollback rule applied |
| `ac867be2` | fix(29): parity sorts pods with one jq filter that the selftest exercises on pod JSON |
| `97282566` | docs(29-28): record the round-1 fix's break arms and the live failing-direction proof; name the drain arms in CI |
| `e6a6c539` | fix(29): a selftest arm where a stuck pod is the only defect |
| `449deb57` | docs(29-28): record the round-2 fix and its break arm |
| `bf85ae46` | docs(changelog): cite #986, the digest-parity drain fix |

(`4dfb6ae2` and `6e42c7e7` sit on the #984 branch but are 29-19 and 29-27 commits. The closing docs PR that carries this SUMMARY is not counted.)

## Self-Check

- **Commits:** all 13 listed above exist and are ancestors of their PR heads (`git merge-base --is-ancestor` rc 0: the six #984 commits against `6e42c7e7`, the seven #986 commits against `bf85ae46`); the two squash merges `bffd2f74` and `33585b23` are ancestors of main at the base of this branch (`1db60c70`). The per-branch counts (6 and 7) were read from `git rev-list --count` at draft time.
- **Files:** FOUND this SUMMARY, `evidence/29-28-merge-deploy.txt` (the Task 3 attempt 2 and dispositions sections were appended by this closing PR), `scripts/check-deploy-digest-parity.sh`, `scripts/check-deploy-rollback-guard.sh`, `scripts/deploy.sh`.
- **Gates on the closing branch (jtoye-ops env, 2026-10-20):** `scripts/docs-freshness.sh` rc 0 (6381 logical invocations match); `scripts/check-doc-metrics.sh` rc 0 (37 claims across 3 docs); `scripts/check-dply02-dispositions.sh` static rc 0 (`derived=20 table=20 findings=0`), `--post-close` rc 0 (`rows=20 findings=0`), `--verify-approval` rc 0 (`pinned=19 findings=0`); `scripts/staging-secrets.sh --leak-scan` rc 0 with 29 objects checked and 0 found on this SUMMARY, the evidence file, `STATE.md` and `ROADMAP.md`. The dispositions gate reads every issue reference in the ROADMAP's Phase 29 section, so the ROADMAP edits made here added none (the diff is the progress count, one checkbox and one table cell). The leak scan's fail direction is the 29-27 selftest arm that names a planted value (rc 1); it was not re-run here.
- **Not re-run here:** the docs-freshness and doc-metrics gates were not shown failing in this plan; both are the repository's existing CI gates and their own selftests cover the fail direction.
