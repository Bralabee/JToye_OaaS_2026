---
phase: 36-azure-blob-storage-throughout
plan: 13
subsystem: testing
tags: [playwright, azurite, azure-blob, e2e, naturalWidth, safeimage, nightly]

requires:
  - phase: 36-azure-blob-storage-throughout
    provides: "36-11 dev reseed (21 seed URLs on the new origin, 1 asset FAILED with the Phase 36 reason); 36-12 rebuilt stack on Azurite, freshness PASS, URL gate 23/23"
provides:
  - "frontend/e2e/storage-images.spec.ts: a browser proof that storefront images decode from the storage origin. It fails with the store down, on a wrong origin, and on a single missing image"
  - "Post-scroll captures of the storefront (390/1280) and the vendor image review queue (1280) for the end-of-phase human look"
affects: [36-17, 36-18, e2e-nightly]

actuals:
  tokens: 6617         # chars/4 over git diff 946756a4..HEAD before this SUMMARY (26466 chars)
  tasks: 2
  commits: 2           # MEASURED: git rev-list --count 946756a4..HEAD before this SUMMARY commit
plan_head_before: 946756a477f008ba03b183aba2290e8de68d6940

tech-stack:
  added: []
  patterns:
    - "Discover browser-test fixtures from SERVED HTML (request.get), so discovery does not depend on the dependency the test measures"
    - "Where a component removes a failed element (SafeImage onError swap), compare the server-rendered set to the loaded set; a DOM-only check cannot see what left the DOM"
    - "Settle on the images' own `complete` state against a deadline, never networkidle"

key-files:
  created:
    - frontend/e2e/storage-images.spec.ts
    - .planning/phases/36-azure-blob-storage-throughout/evidence/36-13-browser-proof.txt
  modified: []

key-decisions:
  - "Added a third assertion beyond the plan: every storage-origin <img> src the server rendered must be a loaded <img> in the browser. Measured: with one image aborted, the plan's 'every storage img has naturalWidth > 0' check PASSED (in-dom fell 9 -> 7, 7/7 loaded), because SafeImage swaps a failed <img> for a fallback <div>"
  - "The spec navigates relatively and declares no base URL, per playwright.config.ts as the base-URL authority (#505), instead of the plan's local BASE constant"
  - "BLOB-05 marked complete: all four clauses are now evidenced (36-11 reseed, 36-12 HEAD 200, 36-13 browser naturalWidth). The human look below is queued for the phase gate, as the plan specifies"

patterns-established:
  - "A browser image proof filters by FULL src prefix, VOIDs on zero, and cross-checks the server-rendered set"

requirements-completed: [BLOB-05]

coverage:
  - id: D1
    description: "storage-images.spec.ts is green on the live stack (2 passed, 9/9 storage-origin <img> loaded, 7 server-rendered) and green again after the Azurite restart and on the final bytes"
    requirement: BLOB-05
    verification:
      - kind: e2e
        ref: "frontend/e2e/storage-images.spec.ts#a seeded shop's images load from the storage origin with real pixels (naturalWidth > 0), never as fallbacks"
        status: pass
    human_judgment: false
  - id: D2
    description: "The spec can fail: Azurite stopped -> 2 failed (VOID, 0 storage <img> in DOM); wrong origin -> VOID rc=1; one image aborted -> check 5 red rc=1"
    requirement: BLOB-05
    verification:
      - kind: e2e
        ref: "evidence/36-13-browser-proof.txt RUN 2, ARM A, ARM B"
        status: pass
    human_judgment: false
  - id: D3
    description: "Placement: in the nightly (--list shows 2 entries, e2e-nightly runs the whole suite), absent from ci-cd.yaml (git grep rc=1, control rc=0), no skip (grep 0, control 1)"
    verification:
      - kind: other
        ref: "npx playwright test --list; git grep -n storage-images -- .github/workflows/ci-cd.yaml"
        status: pass
    human_judgment: false
  - id: D4
    description: "Storefront and vendor image review queue looked at by a human at 390px and 1280px after scrolling (the Incremental Betterment check that demo imagery was preserved)"
    requirement: BLOB-05
    verification:
      - kind: automated_ui
        ref: "frontend/e2e-artifacts/36-13/{shop-brixton-village-grill-390,shop-brixton-village-grill-1280,vendor-media-review-1280}.png (gitignored)"
        status: pass
    human_judgment: true
    rationale: "The plan queues a human look for the end-of-phase verify: whether the photos look right and the queue reads right is judgment no assertion makes"

duration: "~9 min (08:05:39Z-08:14Z)"
completed: 2026-09-29
status: complete
---

# Phase 36 Plan 13: Browser proof that images load from the object store Summary

**A Playwright spec that passes only when real pixels decode from the Azurite origin. It goes red with Azurite stopped, on a wrong origin, and when a single image fails. That last case is invisible to the plan's own filter, because SafeImage removes failed images from the DOM. Post-scroll captures of the storefront and the vendor review queue are queued for the end-of-phase human look.**

## Performance

- **Duration:** ~9 min
- **Started:** 2026-09-29T08:05:39Z
- **Completed:** 2026-09-29T08:14Z
- **Tasks:** 2 of 2
- **Files modified:** 2 committed (spec + evidence), plus this SUMMARY. 3 screenshots are gitignored and not committed.

## Accomplishments

- **`frontend/e2e/storage-images.spec.ts`** (1 test() block, 2 enumerated tests: mobile + desktop).
  - It discovers the shop from served HTML only: the `/shop` listing in order, then the first shop whose server-rendered page has a storage-origin `<img>`.
  - It scrolls through the page, then polls `complete` against a 20 s deadline, never networkidle.
  - It asserts: (3) VOID on zero storage-origin images; (4) every storage-origin image has `naturalWidth > 0`; (5) every server-rendered storage src is a loaded `<img>`.
  - `PLAYWRIGHT_STORAGE_PUBLIC_URL` defaults to the compose `STORAGE_PUBLIC_URL`.
- **Three browser runs as the plan asks.** Live: green, 9/9 loaded. Azurite stopped: red, both projects. Restarted: green. A fourth run on the final committed bytes was green.
- **Two more fail arms:** a wrong origin gives a VOID (rc=1), and a browser-side abort of one image makes check 5 red (rc=1).
- **Captures after scrolling:** shop at 390 and 1280 (9/9 storage images loaded in each) and the review queue at 1280, with the Phase 36 reason and a Re-upload button.

## Task Commits

1. **Task 1: storage-images spec, live/red/green runs**: `8b4279d1` (test)
2. **Task 2: post-scroll captures**: `b6071791` (docs, evidence only)

**Plan metadata:** recorded in the docs commit that follows this SUMMARY.

## Both-direction evidence (all in `evidence/36-13-browser-proof.txt`)

| Check | Fail direction (real output) | Pass direction |
|---|---|---|
| Spec vs store | `stop azurite` (anon GET rc=7), then **2 failed rc=1** `VOID: no <img> on /shop/brixton-village-grill has a src starting with http://localhost:10000/devstoreaccount1/jtoye-images/` (in-dom=0, server-rendered=7) | live and after `start azurite` (healthy in 10 s, GET 200): **2 passed rc=0**, 9/9 loaded |
| VOID on zero / fallbacks don't count | `PLAYWRIGHT_STORAGE_PUBLIC_URL=…/no-such-container` gives **rc=1** `VOID: none of 3 listed shop pages … server-renders an <img> whose src starts with …` | real origin gives rc=0 |
| Check 5 (added) | one image aborted via `page.route` in a deleted temp copy gives **rc=1** `server-rendered storage-origin images with no loaded <img>` naming beef-suya-wrap.jpg. Checks 3 and 4 PASSED here (in-dom 7, loaded 7/7) | rc=0 on the real spec |
| Not in the per-PR gate | control: the same grep on a copy with the spec appended gives `545: … storage-images.spec.ts` rc=0 | `git grep -n storage-images -- ci-cd.yaml` gives **rc=1** |
| In the nightly | none (not falsifiable in isolation) | `--list`: 2 entries, 325 tests in 28 files; e2e-nightly.yml:363 runs the whole suite |
| No skip added | control: `vendor-credentials.ts` gives 1 | spec gives 0 |
| Typecheck | temp copy with a string assigned to a number gives `tsc` **rc=2** TS2322 (copy deleted) | `tsc --noEmit` rc=0, 0 errors; eslint rc=0 |
| Screenshot count | empty dir gives rc=2, shots=0 | rc=0, shots=3 |
| Review queue = FAILED rows | none | DB (superuser, read-only): FAILED 3 = 3 "Rejected" cards; 1 carries the Phase 36 reason |

**Skip budget (acceptance criterion):** `check-e2e-skip-budget.sh` gives **rc=2 VOID both before and after**, because the local report's spec digest predates this phase. So "unchanged" is TRUE but VACUOUS: the gate could not measure either tree, which is the pre-existing #686 state and not this spec. The static evidence stands in for it: the spec has no skip path (0 skips; control 1).

**TDD RED record:** the Azurite-stopped run is the RED. The target test failed on its own behaviour assertion, as quoted above. `gsd_run check tdd-red-evidence` returned INVALID_RED `zero_tests_discovered` because it parses only node-test TAP and cannot read Playwright output. It also exits **rc=0 while reporting `passed: false`**. So the verdict is about the instrument, and the exit code cannot serve as a gate. This task has no production edit for a GREEN to authorize; the GREEN is the restart run.

## Files Created/Modified

- `frontend/e2e/storage-images.spec.ts`: the browser proof
- `.planning/phases/36-azure-blob-storage-throughout/evidence/36-13-browser-proof.txt`: every run, arm and capture, with real output
- `frontend/e2e-artifacts/36-13/*.png`: 3 captures, gitignored (`.gitignore:185`), NOT committed

## Decisions Made

See `key-decisions`. The main one is check 5. It was measured, not argued: Arm B shows the plan's filter passing on a page with a missing image.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical] Added the server-rendered-set check (check 5)**
- **Found during:** Task 1 (reading `components/ui/safe-image.tsx`)
- **Issue:** SafeImage's `onError` replaces a failed `<img>` with a fallback `<div>`. The plan's "every storage-origin img has naturalWidth > 0" therefore passes when some blobs 404, because those images are gone from the DOM. That is threat T-36-48 (passing on fallback tiles), which the plan's filter only covers when ALL images fail.
- **Fix:** Read the storage-origin `<img>` srcs from the served HTML and require each one to be a loaded `<img>`.
- **Verification:** Arm B is red on check 5 alone (checks 3 and 4 passed); the live run is green.
- **Committed in:** 8b4279d1

**2. [Rule 1 - Instrument] The Azurite-stopped red comes from the VOID, not from "naturalWidth 0"**
- **Found during:** Task 1, run 2
- **Issue:** The plan predicted red with naturalWidth 0. Measured: every image was swapped for a fallback, so 0 storage `<img>` remained and the VOID assertion fired.
- **Fix:** None needed; the criterion "red with Azurite stopped" is met. The actual mechanism is recorded rather than the predicted one. An `<img>` that errors before hydration would stay with naturalWidth 0, and check 4 covers it.
- **Committed in:** 8b4279d1 (evidence)

**3. [Rule 3 - Blocking] Captures made with Node Playwright, not the webapp-testing Python scripts**
- **Found during:** Task 2
- **Issue:** The `block-base-python` guard refused `/usr/bin/python3`, because no conda env is declared for this project. A blocked command is the answer, so no alternative Python route was tried.
- **Fix:** Wrote a Node capture script using `frontend/node_modules/playwright` 1.62.1, the same engine.
- **Committed in:** b6071791 (evidence notes it)

**4. [Note] Relative navigation instead of a local BASE constant**
- The plan says to build BASE from PLAYWRIGHT_BASE_URL. The config names itself the only base-URL authority (#505), so the spec navigates relatively; `PLAYWRIGHT_BASE_URL` is still honoured through the config. `check-e2e-baseurl-contract.sh` PASS rc=0.

**5. [Note] Comment reworded after runs 1-3**
- The docblock carried the literal tag token that the per-PR step greps for. It was reworded so no text search reads it as tagged. Run 4 on the final bytes (sha256 37070e25…) was green.

---

**Total deviations:** 3 auto-fixed (1 missing-critical, 1 instrument, 1 blocking) plus 2 notes.
**Impact on plan:** Item 1 makes the proof strictly stronger. No scope creep. No production code was touched.

## Issues Encountered

- **New red caused by this plan, owned by 36-17:** `check-test-count-oracle.sh playwright` gives runner=128, manifest=127, rc=1. Without the spec (moved out and restored, sha256 equal) it gives 127=127 PASS rc=0. This is the +1 test() block (+2 enumerated tests). `docs/metrics.json` was NOT regenerated here, per the phase's ownership.
- **Inherited red:** `scripts/docs-freshness.sh` (36-17).
- **Capture note:** the fixed cookie notice (not dismissed) overlaps one card in each shop capture. That is a position:fixed artifact.

## End-of-phase human check

Carried verbatim from the plan (Task 2 `<human-check>`), for the phase's end-of-phase verification list:

> Open http://localhost:3000, go to a seeded shop at 390px and 1280px widths, scroll to the bottom first: product cards show real food photos whose image URL (right-click, copy image address) starts with http://localhost:10000/devstoreaccount1/jtoye-images. Then sign in as the dev vendor and open the media review queue: assets affected by the reseed show FAILED with the reason "Bytes not carried over in the Phase 36 dev reseed (D-04) -- re-upload" and a Re-upload control. Reply "approved" or describe what is wrong.

**Reading aid, measured:** the queue is at `/dashboard/media/review` ("Image review" in the sidebar). The vendor UI labels a FAILED asset **"Upload rejected" with a red "Rejected" chip**; the word "FAILED" does not appear on the page. The DB confirms 3 FAILED rows for 3 cards, and exactly one has the Phase 36 reason; the other two predate Phase 36. Captures of both surfaces are in `frontend/e2e-artifacts/36-13/`.

## Known Stubs

None.

## User Setup Required

None.

## Next Phase Readiness

- **The stack is LEFT RUNNING** for the end-of-phase human check (http://localhost:3000). At 08:13:48Z it was unchanged from 36-12's table: all 10 services running (healthy), and every image ID the same (azurite 830430c1da1a, core-java 356bca98c4b9 on 0.0.0.0:9090, frontend e9e3eb4af373 on :3000, edge-go cd432b1549a8, mcp-server 5faed32b5dac, …). `check-runtime-freshness` PASS 4/4 (0 unverified). Only azurite was stopped and started; no volume was touched.
- **BLOB-05** is complete on evidence; the human look is queued.
- **36-17** must regenerate `docs/metrics.json` for the +1 Playwright test() block.
- **36-18 / nightly:** the spec has not yet run on a hosted nightly runner.

## Self-Check: PASSED

- FOUND: frontend/e2e/storage-images.spec.ts (contains `naturalWidth` and `devstoreaccount1`), evidence/36-13-browser-proof.txt, 3 PNGs in frontend/e2e-artifacts/36-13/ (gitignored)
- FOUND commits: 8b4279d1, b6071791 (`git rev-list --count 946756a4..HEAD` = 2 before this SUMMARY)
- Acceptance re-run: spec rc=0, 2 passed (run 4, final bytes); ci-cd grep rc=1; skip budget rc=2 before and after (vacuous, stated); shots=3; freshness PASS

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-29*
