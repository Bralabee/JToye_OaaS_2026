---
phase: 37-real-world-operations-readiness
plan: 53
subsystem: testing
tags: [accessibility, gate, playwright, axe, runtime-parity, goods-to-preserve, wcag]

requires:
  - phase: 37-51
    provides: the tracking a11y work (status announcements, per-step titles, tracker wrap) and a11y-tracking.spec.ts
  - phase: 37-52
    provides: the storefront a11y work (card names, focus, cookie notice, large text) and a11y-storefront.spec.ts
provides:
  - 37-F proven on the gates and on a rebuilt runtime (both new live specs RED before, GREEN after)
  - all 40 persona goods re-verified on the delivered runtime (SC-7), 40 of 40 hold
  - the seventh sub-theme PR branch phase-37-f-accessibility (one squash ef5326dc on origin/main, tag cut/phase-37-f-accessibility), not pushed
affects: [phase-37 completion, the 37-F PR review, UXT-080 follow-up]

actuals:
  tokens: 23499
  tasks: 3
  commits: 7
plan_head_before: 602a1cfb88b25942ccb2e58e007330b94f8f3344
plan_head_after: 8a25330d06a045757c92aaf85917bc96b35852ec

tech-stack:
  added: []
  patterns:
    - "A/B a suspected regression on the DELIVERED page by restoring the old CSS behaviour through an injected style, with a control that reproduces the original persona defect"
    - "Measure a 'predates this phase' claim on a side build of the pre-phase commit (separate tag, side port, compose network), never by diff reasoning alone"
    - "A layout probe must read the settled layout: a transition-all element measured right after a style injection reads its pre-transition size"

key-files:
  created:
    - .planning/phases/37-real-world-operations-readiness/evidence/37-53-goods-40.md
    - .planning/phases/37-real-world-operations-readiness/evidence/37-53-screens/
  modified:
    - .planning/phases/37-real-world-operations-readiness/evidence/37-53-gate-37F.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md
    - frontend/e2e/a11y-tracking.spec.ts
    - frontend/e2e/a11y-storefront.spec.ts
    - frontend/e2e/dashboard-a11y-nightly.spec.ts
    - frontend/lib/accessibility-statement.ts

key-decisions:
  - "The /track mid-word label break at the default text size (a 37-51 regression, measured by A/B) is recorded as decide-before-merge on the 37-F PR, not fixed at the gate: it is a design trade on a customer surface (default-size readability against the 130% fit 37-51 bought) and needs the owner's call; the cut proceeds, as at 37-50"
  - "The signed-out 4 px header overflow at 360/130 is recorded, not fixed: measured identical on a pre-37-F build, so it predates 37-F; F6's document check passes only by transition timing"
  - "Three 37-51/37-52 spec defects found on the specs' first live run were fixed as test-only commits (below-minimum basket, the featured rail's duplicate Remove, a page-wide status count that predated 37-52's basket region); none changed product code"
  - "RWO-080 is reported as held, not complete: UXT-080's basket/header no-overflow at 360/130 is not met signed out, and its tracker fix degraded the default-size labels"

patterns-established:
  - "Goods battery: one row per source ID, a control beside each reading where one exists, and a table-parse cross-check (40 rows, 0 missing, 0 extra) beside the plan's mention-count check"

requirements-completed: [RWO-081, RWO-082, RWO-083, RWO-084, RWO-085]

coverage:
  - id: D1
    description: "Both new live a11y specs RED on the pre-rebuild images and GREEN on the rebuilt runtime; the plan's five-spec a11y line 84 passed, 0 failed"
    requirement: RWO-081
    verification:
      - kind: e2e
        ref: "frontend/e2e/a11y-tracking.spec.ts, a11y-storefront.spec.ts, public-a11y.spec.ts, storefront-dish-modal-a11y.spec.ts, dashboard-a11y-nightly.spec.ts (gate file 2.2, 2.5)"
        status: pass
    human_judgment: false
  - id: D2
    description: "All 40 persona goods re-verified on the delivered runtime (SC-7)"
    verification:
      - kind: e2e
        ref: "evidence/37-53-goods-40.md (scratch API/browser probes + 10 live specs, 83 + 10 passed)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Runtime parity: every image rebuilt, freshness 4/4 with 0 unverified, content read from the running artifacts"
    verification:
      - kind: other
        ref: "bash scripts/check-runtime-freshness.sh (rc 1 before, rc 0 after; gate file 2.3)"
        status: pass
    human_judgment: false
  - id: D4
    description: "PR branch phase-37-f-accessibility: one squash on origin/main, tree equal to the tagged integration commit"
    verification:
      - kind: other
        ref: "the plan's fourth automated line (rc 0; gate file 2.13)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Screen reader (VoiceOver/NVDA) hears the status change on the order page and /track; a phone user tabbing the storefront never has focus hidden by the cookie notice; the 40-goods table read by a person; and the owner's call on the /track mid-word label regression (gate file 2.9 item 0)"
    requirement: RWO-080
    verification: []
    human_judgment: true
    rationale: "The plan's human-check: real assistive technology and a real phone are not emulated by the MutationObserver recorder or the tab-walk probe; the label regression is a design decision on a customer surface"

duration: "3h01m (Task 1 20:16Z-21:05Z; Task 3 21:29Z-23:18Z)"
completed: 2026-10-14
status: complete
---

# Phase 37 Plan 53: The 37-F gate (accessibility) and the 40-goods re-verification Summary

**37-F proven on a fully rebuilt runtime: both new live a11y specs RED on the old images and GREEN after (84/0 on the plan's line), all 40 persona goods hold on the delivered runtime, and the seventh PR branch is cut; one 37-51 regression (/track labels break mid-word at default text) is left for the owner to decide before merge.**

## Performance

- **Duration:** about 3 h of execution (Task 1 20:16:42Z to about 21:05Z; owner checkpoint; Task 3 21:29:00Z to 23:18Z)
- **Started:** 2026-10-14T20:16:42Z
- **Completed:** 2026-10-14T23:18Z
- **Tasks:** 3 (Task 1 in the first executor; Task 2 the owner's "proceed"; Task 3 here)
- **Files modified:** 6 tracked files plus 16 screens and the new goods file

## Accomplishments

- **RED, live, on the pre-rebuild images:** 5 of 5 live tests failed, each on its own defect: no Chrome name on the shop cards, focus to `BODY` and "11 item in basket" after Add, clipped names at 360/130, "Got it" not reachable (`Infinity`) and 12 stops under the notice, and the root title on the basket. A soft-assert scratch copy reached the order page: no `aria-current="step"`, and the running build contains no `Order update:`.
- **Rebuild of every image** (core-java, edge-go and mcp-server `--no-cache`, because they were fully cached), recreated with `up -d`; freshness rc 1 → rc 0, 4/4, 0 unverified; parity by content (`Order update:` 0 → 4 files, `v5` in the running jar, V84's sha equal).
- **GREEN:** the plan's a11y line, verbatim, 84 passed, 0 failed, with every in-test instrument arm asserted. The live tracking journey hears "Order update: Confirmed. Shop accepted your order." exactly once, then "Order update: Preparing. Being prepared now." on `/track`.
- **40 of 40 goods hold** (`evidence/37-53-goods-40.md`): the API battery (41/41), a throwaway STAFF user (15/15 Brixton attempts refused, next-request revoke, money/GDPR 403), MCP and edge isolation, SSRF, inert XSS with a firing control, a 38-minute kitchen tablet (38/38 Live, card 812 ms, bump 164×44 in 67 ms), live offline/online, sign-out ending the IdP session, a double tap on a throttled link (1 POST, 1 order), forgot-password returning to the shop signed in, signed-in tracking, a basket surviving a forced sign-out, an order at 00:01 BST labelled 15 Oct.
- **The 37-47 open item measured closed:** vendor and MCP orders now refuse a switched-off product (409 `product-unavailable`), with an available-product control.
- **The PR branch** `phase-37-f-accessibility` = `ef5326dc` on origin/main `94af0d89`, tag `cut/phase-37-f-accessibility` = `8a25330d`, not pushed.

## Task Commits

1. **Task 1: every static gate and the full suites on the 37-F head:** `94f06286` (docs), from the first executor
2. **Task 2: owner checkpoint:** answered "proceed"; STATE parked by the orchestrator in `876bc2be`
3. **Task 3: a11y RED/GREEN, the 40 goods, the PR branch pointer:**
   - `812a3e74` test: the two live a11y specs reach their assertions on the seeded data
   - `8e0f7c84` docs: the accessibility statement comment names the cookie notice ahead of the skip link
   - `b05dbd11` test: count the order status region inside main, beside 37-52's basket region
   - `91f3a445` test: the nightly a11y sweep scans the kitchen New lane again
   - `8a25330d` docs: Part 2 evidence (tagged `cut/phase-37-f-accessibility`)
   - `ef5326dc` feat(37-F): accessibility, the squash, on `phase-37-f-accessibility` only

**Plan metadata:** this SUMMARY, with gate file 2.13, in the commit after `8a25330d`.

## Files Created/Modified

- `frontend/e2e/a11y-tracking.spec.ts`: raise the basket past the £10 minimum on the basket page; count the status region inside `<main>`
- `frontend/e2e/a11y-storefront.spec.ts`: F3 presses the Remove of the card whose Add it pressed
- `frontend/e2e/dashboard-a11y-nightly.spec.ts`: the kitchen lane control waits on the visible copy only
- `frontend/lib/accessibility-statement.ts`: comment only (the cookie notice comes before the skip link)
- `evidence/37-53-gate-37F.md`: Part 2 (2.1-2.13); `evidence/37-53-goods-40.md`: the 40 rows; `evidence/37-53-screens/`: 16 screens, each looked at
- `deferred-items.md`: the 37-53 comment item resolved; the 37-47 unavailable-product item resolved; two new open items

## RED/GREEN and fail directions (both directions recorded)

| Check | Fail direction (real output) | Pass direction |
|---|---|---|
| a11y-tracking live | old images: basket title `J'Toye — Discover Local Vendors`; first rebuilt run: Place order disabled ("Minimum order £10.00"); then status count `Received: 2`; arm with a planted second region in `<main>`: `Expected 1 Received 2`, rc 1 | 9/9 |
| a11y-storefront live | old images: 4/4 live tests red (above); first rebuilt run: F3 focus `Add Chapman to basket (featured)` (the rail copy) | 12/12 |
| check-runtime-freshness | rc 1 before the rebuild, and rc 1 again after `8e0f7c84` (`frontend DRIFT`) | rc 0, 4/4 |
| check-branch-behind-base | rc 1 in a worktree at `origin/main~1` | rc 0 |
| cut proof | tree vs origin/main: rc 1 | tree = tag tree, 1 commit above main, rc 0 |
| goods count (plan's line) | 39 with one ID removed, rc 1 | 40, rc 0; table parse 40 rows / 0 missing / 0 extra |
| nightly kitchen lane | "control failed, not scanned" (strict mode, the hidden staging copy) | scanned: desktop 0, mobile color-contrast ×12 (report-only, as 37-47) |
| comment-only filter | a planted `+  id: "x",` is caught | 0 non-comment lines changed |
| gitleaks | positive control rc 1 | rc 0 on every evidence path |

## Decisions Made

See `key-decisions`. In short: the cut goes ahead with two items for the PR review, as at 37-50, and RWO-080 is held.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] a11y-tracking could never place its order**
- **Found during:** Task 3 (first live run)
- **Issue:** one £4.50 dish against a £10.00 minimum; Place order stayed disabled for 240 s
- **Fix:** raise the line on the basket page until "Proceed to checkout" is a link (bounded)
- **Files modified:** `frontend/e2e/a11y-tracking.spec.ts`
- **Verification:** the live journey 9/9; the failing run is its fail direction
- **Committed in:** `812a3e74`

**2. [Rule 1 - Bug] a11y-storefront F3 pressed the rail copy's Remove**
- **Found during:** Task 3
- **Issue:** a featured dish renders twice and the steppers share labels; `.first()` was the rail copy
- **Fix:** `.last()`, the copy whose Add was pressed (the product's per-card focus return was verified correct)
- **Files modified:** `frontend/e2e/a11y-storefront.spec.ts`
- **Committed in:** `812a3e74`

**3. [Rule 1 - Bug] a page-wide status-region count met 37-52's basket region**
- **Found during:** Task 3 (second live run)
- **Fix:** count inside `<main>`; the page-wide recorder still requires exactly one "Order update:"
- **Verification:** planted-second-region arm red (rc 1); live 9/9
- **Committed in:** `b05dbd11`

**4. [Rule 1 - Bug] the nightly sweep never scanned the kitchen New lane**
- **Fix:** `.filter({ visible: true })` past React's hidden staging copy
- **Committed in:** `91f3a445`

**5. [Continuation instruction] the accessibility-statement comment** fixed (comment only), deferred item resolved: `8e0f7c84`.

**6. [Rule 3 - Blocking] the 38-minute probe's cancel used an expired token**; the order was cancelled by hand (200), recorded.

---

**Total deviations:** 4 auto-fixed spec/instrument bugs (Rule 1), 1 instructed comment fix, 1 cleanup fix. **Impact on plan:** test-only and comment-only; no product code changed at the gate.

## Issues Encountered

- **Found at the gate, open, decide before the 37-F PR merges:** `/track` step labels break mid-word at the DEFAULT text size on 360 and 390 px phones (37-51's `overflow-wrap:anywhere` + `min-w-0`). A/B on the delivered page: shipped "Confirmed, Completed" broken at 390/100%; the pre-37-51 behaviour none broken, but 423 px wide at 130% (the persona's P2-GRA-10, reproduced as the control). Gate file 2.9 item 0, deferred-items.
- **Found at the gate, open, pre-existing (measured on a pre-37-F build):** signed out, the storefront header row is 364/360 at 360/130; F6's document check reads it mid-transition (360 at t=0, 364 at 300 ms). Gate file 2.9 item 1.
- Instrument corrections during the goods battery (5 API, 3 browser, 1 kitchen regex, 1 `/track` date) are recorded in the goods file and were not counted as fail arms.
- `check-terminal-states.sh`, `check-dependency-horizons.sh` and `check-image-supply-chain.sh` stay VOID in a plain shell (python shim), PASS under env python (Part 1.3); the 37-51 deferred item stands.

## Known Stubs

None.

## Threat Flags

None: no endpoint, auth path or schema changed. Throwaway Keycloak users (one staff, three customers) were created and deleted through the admin API; one derived `user_directory` row remains (gate file 2.10).

## User Setup Required

None.

## Next Phase Readiness

- The owner opens the 37-F PR from `phase-37-f-accessibility` via `/gsd-ship` (push first, review second), decides 2.9 items 0 and 1 (review fixes land on the PR branch), and adds the CHANGELOG entry citing the PR number. Phase 37 completes when this seventh PR merges.
- Requirements: RWO-081..RWO-085 met; **RWO-080 held** pending the 2.9 decisions.
- Human check (the plan's `<human-check>`): screen reader on the tracking journey, phone tab-through with the cookie notice, the 40-goods table.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-14*

## Self-Check: PASSED
