---
phase: 37-real-world-operations-readiness
plan: 59
subsystem: ui
status: complete
tags: [playwright, live-e2e, container-queries, finance, pagination, sentence-end, runtime-parity, pr-cut, g-37-6]

requires:
  - phase: 37-real-world-operations-readiness
    provides: "37-56 frontend/lib/sentence-end.ts; 37-57 uk.jtoye.core.common.Sentences; 37-58 finance container grids, tiered Pagination, evidence/37-58-tw-emits.js; 37-55 the live-spec shape and staff-invitee helpers; G376_BASE (evidence/37-56-g376-base.txt)"
provides:
  - "frontend/e2e/screens-copy-uat.spec.ts: five live tests for G-37-6 (finance, pagination, void dialog, checkout copy, invitation email), each with a built-in instrument arm, plus each finance grid measured with its container at exactly each @container threshold"
  - "finance grid thresholds raised to the measured need of the bound figure: stat 28rem/57rem, VAT 27rem/54rem"
  - "evidence/37-59-g376-runtime.md (RED, parity both directions, GREEN, arms, per-width tables, thresholds, goods, gates, cut record) and evidence/37-59-g376-paths.txt (the 25-path G-37-6 set)"
  - "local branch phase-37-uat-g376 (one commit above origin/main, only G-37-6's hunks plus regenerated metrics, NOT pushed) and tag cut/phase-37-uat-g376"
affects: [37-verify-work-test-6, phase-37-uat-g376-pr]

actuals:
  tokens: 30294
  tasks: 4
  commits: 11
plan_head_before: 8498425eb9640bb90a52bf8bf6aad0eb6620aff2
plan_head_after: 2f63a78eb8a0239e34619e8b367ff5d7faddd8c4

tech-stack:
  added: []
  patterns:
    - "Measure a container-query threshold AT the threshold: steer the viewport until the container's content box equals the condition's px (read from CSSContainerRule), then run the layout checker; fixed sample widths never land on the edge"
    - "An instrument arm must force the old layout onto something the measurement actually reads, before it measures: mark the target first, then inject"
    - "Cut a PR branch per path: git apply --index of each path's own BASE..tag patch onto origin/main's blob, then assert the added/removed lines byte-identical with custom output indicators"

key-files:
  created:
    - .planning/phases/37-real-world-operations-readiness/evidence/37-59-g376-runtime.md
    - .planning/phases/37-real-world-operations-readiness/evidence/37-59-g376-paths.txt
  modified:
    - frontend/e2e/screens-copy-uat.spec.ts
    - frontend/app/dashboard/finance/page.tsx
    - frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md

key-decisions:
  - "Step 4's raise was taken: with each finance container steered to exactly its 37-58 threshold, the bound figure wrapped to two lines at all four (27/55rem stat, 26/52rem VAT). Raised to the next whole rem above the measured need (444 px -> 28rem, 904 -> 57rem, 422 -> 27rem, 856 -> 54rem); pagination held (margins 20/4/4 px)"
  - "Branch 2b (void dialog fix) not taken: the dialog fits at 390/768/1280 on the pre-fix runtime once the instrument stopped measuring the leading-none title's font box vertically"
  - "The plan's combined desktop goods line drains the dev stack's per-tenant rate-limit bucket (100/min) on tenant 0001 once screens-copy-uat precedes staff-invite; the limiter was NOT reconfigured. Each spec passes alone on a rested bucket with 0 rate-limit events; logged in deferred-items"
  - "check-doc-citations' first rc 0 was VOID-masked for docs/ops/terminal-states.yaml (the machine's python shim refuses bare python3 -c; the pre-#926 script swallowed that). Re-run in the jtoye-ops conda env: rc 0, 53 citations"

patterns-established:
  - "Finance threshold edges are part of the live spec (finance-edge-* annotations): a threshold below the figure's need now reds the nightly"

requirements-completed: [RWO-003, RWO-004, RWO-018, RWO-026, RWO-027]

coverage:
  - id: D1
    description: "G-37-6 defect 1: every finance figure on one line inside its own card at 390/768/1024/1280 (live and at the bound) and at each grid's own container threshold"
    requirement: RWO-027
    verification:
      - kind: e2e
        ref: "frontend/e2e/screens-copy-uat.spec.ts#G-37-6 defect 1: every finance figure stays whole inside its own card, live and at the bound"
        status: pass
      - kind: unit
        ref: "frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx#stat grid: columns from its own width (28rem two, 57rem four), no viewport-keyed column token"
        status: pass
    human_judgment: true
  - id: D2
    description: "G-37-6 defect 3: Pagination inside its card at 44 x 44 px from 375 to 1280; first/prev/next/last at 390; five-page window at 1280; next page reaches Showing 21-40"
    requirement: RWO-018
    verification:
      - kind: e2e
        ref: "frontend/e2e/screens-copy-uat.spec.ts#G-37-6 defect 3: the orders Pagination fits its card at 44 px, 375 to 1280"
        status: pass
    human_judgment: true
  - id: D3
    description: "G-37-6 item 4: the void dialog opened through the real path fits at 390/768/1280, closed with Keep order, zero void requests"
    requirement: RWO-018
    verification:
      - kind: e2e
        ref: "frontend/e2e/screens-copy-uat.spec.ts#G-37-6 item 4: the void dialog fits at 390, 768 and 1280 and is closed without voiding"
        status: pass
    human_judgment: true
  - id: D4
    description: "G-37-6 defect 2: one full stop after 'Peckham Jollof Co.' in the checkout allergen panel and in the staff invitation email"
    requirement: RWO-004
    verification:
      - kind: e2e
        ref: "frontend/e2e/screens-copy-uat.spec.ts#G-37-6 defect 2 (TypeScript): the checkout panel names Peckham Jollof Co. with one full stop"
        status: pass
      - kind: e2e
        ref: "frontend/e2e/screens-copy-uat.spec.ts#G-37-6 defect 2 (Java): the invitation email names Peckham Jollof Co. with one full stop"
        status: pass
    human_judgment: true
  - id: D5
    description: "Runtime parity: freshness 1 then 0 (0 unverified); the container rule and Sentences.class read out of the running artifacts, both directions with controls"
    requirement: RWO-026
    verification:
      - kind: other
        ref: "bash scripts/check-runtime-freshness.sh (rc 1 before, rc 0 after, and rc 0 after the post-merge rebuild)"
        status: pass
    human_judgment: false
  - id: D6
    description: "PR branch phase-37-uat-g376: one commit above origin/main carrying exactly each path's G376_BASE..tag hunks plus regenerated metrics; not pushed"
    requirement: RWO-003
    verification:
      - kind: other
        ref: "Task 4 third verify loop: paths=25 bad=0 void=0 (fail direction: planted line -> DIFFERS, rc 1)"
        status: pass
    human_judgment: false

duration: 55min
completed: 2026-10-16
---

# Phase 37 Plan 59: G-37-6 proven on the delivered runtime, and its own PR branch Summary

**Live Chrome proof of G-37-6 on a freshly rebuilt compose runtime, with RED first on the pre-fix one. Five instrumented Playwright tests cover finance, pagination, the void dialog, checkout copy and the invitation email. Measuring at the threshold raised the finance container thresholds to the bound figure's real need (28/57rem and 27/54rem). A one-commit local PR branch carries only this gap's hunks.**

## Performance

- **Duration:** about 55 min (plan head 14:38Z; this continuation 14:50Z to 15:34Z)
- **Started:** 2026-10-16T14:38:20Z
- **Completed:** 2026-10-16T15:34:00Z
- **Tasks:** 4 (Task 1 by the first executor, Task 2 the owner checkpoint, Tasks 3-4 here)
- **Files modified:** 10 on the integration branch (3 frontend, 4 docs, 3 planning)

## Accomplishments

- **RED on the pre-fix runtime** (images tagged 10:39 BST, before G376_BASE): finance (768 live, 768/1024 bound: figures leave their 100 px cards), pagination (32 px controls at every width; at 375/390 they also leave the card), checkout intro ("Co.. Based") and the invitation ("for Peckham Jollof Co.."). Every failure was on a gap assertion. The void dialog was found clean.
- **Parity, both directions:** freshness rc 1 (frontend and core-java DRIFT) then rc 0 with 0 unverified. edge-go and mcp-server needed `--force-recreate`. The finance container rule went from absent to present in the served stylesheet and `Sentences.class` from 0 to 1 in the running jar, with the controls unchanged.
- **GREEN 5/5**, each arm failing as designed. dashboard-mobile passed 48/48 on both projects and the goods 11/11 on desktop, with nothing skipped.
- **Thresholds measured at the edge, then raised:** at exactly 27rem, 55rem, 26rem and 52rem the bound figure "−£999,999.99" wrapped to two lines. Raised to 28/57rem (stat) and 27/54rem (VAT): RED in jest first, then GREEN. The edge measurement is now part of the spec.
- **origin/main moved (#926) mid-task:** merged, rebuilt (the gate over-reports a merge by design), and freshness back to 0. The goods are green per spec on a rested tenant bucket.
- **PR branch `phase-37-uat-g376`** = `116ef333`, one commit above origin/main `b43d1ae9`. 25 of 25 path patches applied cleanly, all "same base". tsc, full jest (253 suites / 3003 tests) and the Gradle common and notification tests (146 tests) pass. The third verify is clean, and its fail direction was proven. Not pushed. Tag `cut/phase-37-uat-g376` = `eaaf8dc2`.

## Task Commits

1. **Task 1: the live instrument** - `6423fb86` (test), by the first executor
2. **Task 2: owner checkpoint** - no commit. The owner answered "proceed", covering Tasks 3 and 4.
3. **Task 3: RED, rebuild, GREEN, goods**
   - `6ebd7616` test(37-59): the void-dialog title check reads ink, not the font box, and carries its own arm (instrument fault found in RED)
   - `1debebf8` test(37-59): the finance arm marks the grid before forcing it; each grid measured at its own threshold
   - `b54daa1e` fix(37-59): finance grid thresholds raised to the measured need of the bound figure (step 4 taken)
   - `78df11ca` docs(37-59): runtime evidence
4. **Task 4: docs, gates, tag, cut**
   - `eaaf8dc2` docs(37-59): doc metrics regenerated, gate rcs recorded (tagged `cut/phase-37-uat-g376`)
   - `174f6fb1` docs(37-59): the path set
   - `db2887ca` merge of origin/main (`b43d1ae9`, #926)
   - `35b88f13` docs(37-59): tag, range checks, post-merge rebuild and goods
   - `2f63a78e` docs(37-59): cut record
   - On `phase-37-uat-g376` only: `116ef333` fix(37): UAT gap G-37-6 (finance figures, pagination, one sentence-end rule per language, void dialog proven)

The `commits: 11` above is measured (`git rev-list --count 8498425e..2f63a78e`). It is these nine 37-59 commits, plus the merge `db2887ca`, plus origin/main's `b43d1ae9`, which the merge brought into the range.

## Files Created/Modified

- `frontend/e2e/screens-copy-uat.spec.ts`: title check axes, a second void arm, the finance arm fix, and the threshold-edge block (`financeGridThresholds`, `steerFinanceContainer`)
- `frontend/app/dashboard/finance/page.tsx`: stat grid 28/57rem, VAT grid 27/54rem, derivation comments with the measured numbers
- `frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx`: the same literals (RED first)
- `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md`: 6148 to 6208 on the integration tree (6138 to 6198 on the PR tree)
- `evidence/37-59-g376-runtime.md`, `evidence/37-59-g376-paths.txt`, `deferred-items.md`

## Decisions Made

See key-decisions in the frontmatter. In short: the raise was taken on the plan's own trigger (a failure only at a width next to a threshold, measured directly). The void dialog needed no change. The rate limiter was left at its developer default. A VOID-masked gate pass was corrected, not kept.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Void-dialog title check measured the font box, not the ink**
- **Found during:** Task 3 RED
- **Issue:** The check failed at all three widths with the title horizontally inside the box. Vertically, the `leading-none` heading's text rect (21 px) overhangs its 18 px line box by about 2 px into the padding.
- **Fix:** Horizontal against the content box and vertical against the border box, with the message printing both axes. A second arm (an unwrapped title) proves the title check can fail, since the 600 px arm is caught by the viewport check instead.
- **Files modified:** frontend/e2e/screens-copy-uat.spec.ts
- **Commit:** 6ebd7616

**2. [Rule 1 - Bug] Finance instrument arm forced nothing**
- **Found during:** Task 3 first GREEN (the arm reported 0 failures)
- **Issue:** The arm loaded a fresh page, and `measureFinance` marks the grid only at its end, so the injected rule matched nothing while the boxes were measured. On the pre-fix runtime it was vacuous too: the old grid was natively four columns.
- **Fix:** Mark the grid with a throwaway measurement first, and assert exactly one grid was marked.
- **Files modified:** frontend/e2e/screens-copy-uat.spec.ts
- **Commit:** 1debebf8

**3. [Rule 2 - Missing check] Thresholds were never measured at the threshold**
- **Found during:** Task 3 step 4 (the needs measured at 214/205 px implied container needs above 27/55/26/52rem, yet every sample width passed)
- **Fix:** Test 1 now steers each grid's container to exactly each of its @container conditions with the bound figures. It was RED at all four on the 37-58 build, and drove the step-4 raise.
- **Files modified:** frontend/e2e/screens-copy-uat.spec.ts, frontend/app/dashboard/finance/page.tsx, frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx
- **Commits:** 1debebf8, b54daa1e

**4. [Rule 3 - Blocking] origin/main moved before the cut**
- Merged (`db2887ca`). Freshness reported the merge itself (by design), so every image was rebuilt and the containers force-recreated until rc 0. Goods re-run.

**5. [Observation, not fixed] The combined goods line drains the dev tenant's rate-limit bucket**
- After the merge the plan's single combined desktop run failed three times, each failure a 429 at `RateLimitInterceptor` for tenant 0001 (logged at those times). Each spec passes alone on a rested bucket with 0 events. The fail direction is shown too: started on a drained bucket, staff-webhooks-uat failed with 16 events. The limiter's developer default was not changed. Logged in deferred-items.md.

**6. [Gate correction] check-doc-citations' first rc 0 was VOID-masked**
- Its YAML half had been skipped (citations=0) because the machine's python shim refuses a bare `python3 -c`. After #926 the plain shell gives rc 2. In the jtoye-ops env it gives rc 0, with 17 YAML citations and 53 in total. The evidence row was corrected.

## Issues Encountered

- `git switch -c … origin/main` set the PR branch to track origin/main. That was unset immediately, so the branch cannot be pushed onto main by accident.
- The 37-58 per-file emission probe needs absolute paths (relative paths give VOID rc 2).

## Known Stubs

None.

## Threat Flags

None. No new endpoint, auth path or schema. The spec reads existing endpoints. The Authorization header stays in one local variable (l.833) passed only to `request.get`.

## User Setup Required

None.

## Next Phase Readiness

- **Owner:** push `phase-37-uat-g376`, open the PR in the ship shape (push first, review second), add the CHANGELOG entry citing its number on that branch, and run the D3 review series. Whichever of this PR and the light-theme PR merges second regenerates `docs/metrics.json`.
- **UAT:** `/gsd-verify-work 37`, test 6, on this runtime (finance at about 768 px, orders pagination at 390, the void dialog at 390/768/1280, the Peckham Jollof Co. checkout panel and invitation email), and **NoAccessPage at 390/768/1280**, which no instrument here covers.

## Self-Check: PASSED

- FOUND: frontend/e2e/screens-copy-uat.spec.ts, evidence/37-59-g376-runtime.md, evidence/37-59-g376-paths.txt
- FOUND on HEAD's history: 6423fb86, 6ebd7616, 1debebf8, b54daa1e, 78df11ca, eaaf8dc2, 174f6fb1, 35b88f13, 2f63a78e
- FOUND: branch phase-37-uat-g376 = 116ef333 (1 above origin/main), tag cut/phase-37-uat-g376 = eaaf8dc2; not on origin
