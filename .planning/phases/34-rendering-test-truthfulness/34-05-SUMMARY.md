---
phase: 34-rendering-test-truthfulness
plan: 05
subsystem: testing
tags: [playwright, e2e, mobile, viewport, falsifiability, dashboard]

requires:
  - phase: 19-frontend-overhaul
    provides: the eleven-route dashboard mobile sweep and its 390px shell contract
  - phase: 23-vendor-scoped-access
    provides: the shop-context switcher whose 375px geometry the MOBL-01 block guards
provides:
  - an additive eleven-route 375px no-horizontal-overflow sweep in dashboard-mobile.spec.ts
  - a falsifiable overflow yardstick (page.viewportSize) replacing an unfalsifiable one (window.innerWidth)
  - the narrowed #286 position, with re-measured numbers, recorded in the spec that answers it
  - deferred-items.md — two other sites carrying the same vacuous overflow shape
affects: [34-07 ssr-routes.conf, 34-10 docs/metrics.json, 34-09 e2e skip budget, "#542", "#286"]

tech-stack:
  added: []
  patterns:
    - "Per-describe viewport pinning for width-specific contracts, never moving the shared project viewport"
    - "Compare page geometry against page.viewportSize(), never against window.innerWidth under isMobile emulation"
    - "Presence control before geometry: assert the page rendered before measuring it"

key-files:
  created:
    - .planning/phases/34-rendering-test-truthfulness/deferred-items.md
  modified:
    - frontend/e2e/dashboard-mobile.spec.ts

key-decisions:
  - "playwright.config.ts stays at 390x844 — the 375px coverage is additive, pinned per-describe, so no mobile spec, instrument contract or perf baseline moves"
  - "The overflow yardstick is page.viewportSize().width, not window.innerWidth: under isMobile the layout viewport GROWS with the content, so the innerWidth form cannot fail (measured, 1200 vs 1200)"
  - "No @desktop-only / @mobile-only tag on the new block — its two siblings are enumerated by both projects by design"
  - "The nine context.route stubs stay: they are #542's SSR complaint, not a #286 defect"

patterns-established:
  - "Break arms run bracketed clean -> arm -> clean, with the restore verified by git hash-object against the committed blob"
  - "When a docblock's own prose corrupts the count it cites, write both numbers down rather than quietly switching pattern"

requirements-completed: [TRUTH-02]

duration: 35min
completed: 2026-08-28
---

# Phase 34 Plan 05: 375px Eleven-Route Sweep Summary

**All eleven dashboard routes now carry a 375px no-horizontal-overflow assertion that was proven capable of failing — the first version was not, and finding that is the substance of this plan.**

## Performance

- **Duration:** ~35 min
- **Started:** 2026-08-28T21:00Z (worktree reset + `npm ci`)
- **Completed:** 2026-08-28T21:36Z
- **Tasks:** 2 of 2
- **Files modified:** 1 modified, 1 created

## Accomplishments

- Eleven-route 375px sweep added additively to `dashboard-mobile.spec.ts`; enumeration 13 -> 24 per project, delta exactly +11 each, both projects green.
- **The break arm caught a vacuous assertion.** The overflow check lifted from the existing 375px block compares `docScrollWidth` against `window.innerWidth` — and under `isMobile` emulation Chromium widens the layout viewport to fit oversized content, so the two numbers move together. A deliberate 1200px overflow on a 375px phone measured `{"docScrollWidth":1200,"innerWidth":1200}` and the test **passed**. Replaced with `page.viewportSize()!.width`, which page content cannot move.
- The narrowed #286 — what is done, what belongs to #542, what nobody has to do — written into the spec's own docblock with every number re-measured on this tree.
- `playwright.config.ts` untouched; `check-playwright-mobile-contract.sh` rc=0 and `mobile-instrument-contract.spec.ts` still green.

## Task Commits

1. **Task 1: eleven-route 375px sweep** — `3d435ca1` (test)
2. **Task 1 (deviation, Rule 1): falsifiability fix** — `393c9cc1` (fix)
3. **Task 2: narrowed #286 docblock** — `3d1a055f` (docs)
4. **Out-of-scope discovery log** — `1139b566` (docs)

## Files Created/Modified

- `frontend/e2e/dashboard-mobile.spec.ts` — new eleven-route 375px describe (viewport pinned per-block at 375x812, presence controls before geometry, falsifiable yardstick) plus a dated `#286, NARROWED` stanza in the file's top docblock.
- `.planning/phases/34-rendering-test-truthfulness/deferred-items.md` — the two other sites still carrying the vacuous overflow shape.

## Measured Evidence

### Enumeration and pass counts (live Compose stack, real Keycloak vendor login)

| Point | mobile | desktop |
|---|---|---|
| before the edit, `--list` | 13 | 13 |
| before the edit, run | **13 passed** (31.5s), rc=0 | **13 passed** (30.2s), rc=0 |
| after Task 1, `--list` | 24 | 24 |
| after the falsifiability fix, run | **24 passed** (50.9s), rc=0 | **24 passed** (50.8s), rc=0 |
| after Task 2 (docblock only), `--list` | 24 | 24 |
| final combined run, both specs, both projects | **52 passed** (1.7m), rc=0 | — |

Delta is exactly +11 per project, and the docblock commit moved the count by 0 — which is what the "a comment must not alter the test count" criterion exists to check.

### Break arms — bracketed clean -> arms -> clean, restores verified by content

Committed before every arm, so the restore target was a committed state.

| Arm | What was done | Result |
|---|---|---|
| **clean (opening)** | sweep as committed | mobile 24 passed / desktop 24 passed, rc=0 |
| **A (first attempt)** | 1200px div appended to `document.body` on `/dashboard/kitchen` before the read | **PASSED, rc=0 — the finding.** Probe: `{"docScrollWidth":1200,"bodyScrollWidth":1200,"innerWidth":1200,"htmlOverflowX":"visible","bodyOverflowX":"visible","injectedWidth":1200}` |
| **A (after the fix)** | same injection, yardstick now `page.viewportSize()` | **RED, rc=1**: `expect(received).toBeLessThanOrEqual(expected)  Expected: <= 376  Received: 1200` at `expect(geom.docScrollWidth)` |
| **B** | one iteration pointed at `/dashboard/kitchen/definitely-not-a-page-34-05` | **RED, rc=1 on the PRESENCE control**: `getByRole('navigation', { name: 'Primary' })` — `element(s) not found`, not a pass on the geometry |
| **B2** | same 404, presence controls disabled — to prove they are load-bearing | **The overflow assertion PASSED on the 404 page**; the run failed only later at `mainWidth >= 300  Received: 0`. So the overflow line alone genuinely is satisfied by a page that is not the dashboard, and the presence control is what names the real reason |
| **clean (closing)** | restore + full re-run | blob `23a6ba95e98efb80c4ebba11845ec209ac35b8fb` identical to the committed blob after **both** restores; mobile 24 passed, rc=0 |

Restores were verified by `git hash-object` against the committed blob, never by `git diff --stat`.

### Re-measured numbers (Task 2)

| Claim | Command | Measured | vs RESEARCH |
|---|---|---|---|
| sibling spec uses real login | `rg -uu -c 'vendorLogin' frontend/e2e/dashboard-interface-corrections.spec.ts` | **3** (`:49` def, `:108`, `:201`), rc=0 | matches (3) |
| sibling spec stubs nothing | `rg -uu -c 'context\.route\(' <same file>` | **0**, rc=1; `searchcheck` PASS, all paths agree, 0 files | matches (0) |
| positive control for that zero | `rg -uu -c 'test\(' <same file>` | **5**, rc=0 | n/a — added here |
| this file's stubs | `rg -uu -c '^\s*await context\.route\(' frontend/e2e/dashboard-mobile.spec.ts` | **9**, rc=0 (`:338 :346 :351 :355 :359 :365 :371 :372` + `:504`) | matches (9) |
| the eleven routes are client components | first non-empty line of each `page.tsx` | **11/11 `"use client"`**, 0 server, 0 missing | n/a — added here |
| `scripts/gates/ssr-routes.conf` | `ls` | **absent**, rc=2 (34-07 owns it) | consistent |

**One figure changed because of this plan, and it is written into the docblock rather than hidden:** the naive `rg -uu -c 'context\.route\('` on `dashboard-mobile.spec.ts` printed **9** before Task 2 and prints **11** after, because the new stanza names the token twice. The count that survives its own documentation is the anchored `'^\s*await context\.route\('` -> **9**. Both numbers are recorded in the file.

### Gates

| Gate | Result |
|---|---|
| `scripts/check-e2e-typecheck.sh` | rc=0, 25 e2e files, PASS (run after both tasks) |
| `scripts/check-playwright-mobile-contract.sh` | rc=0, 2 projects parsed, 0 blind |
| `npx eslint e2e/dashboard-mobile.spec.ts` | clean, no output |
| `git diff --name-only -- frontend/playwright.config.ts frontend/package.json frontend/package-lock.json` | empty |
| `e2e/mobile-instrument-contract.spec.ts --project=mobile` | 2 passed, rc=0 |

## Decisions Made

- **The mobile project stays at 390x844.** Moving it to 375 would close #286 in one line and silently relocate every mobile spec, the instrument contract and every mobile perf baseline. The viewport is pinned per-describe instead, which also makes the block correct under the desktop project.
- **No `@desktop-only` / `@mobile-only` tag.** Both sibling blocks are enumerated by both projects by design; consistency beats halving the run, and the plan says so.
- **`mainWidth >= 300` kept alongside the overflow assertion.** A collapsed content column satisfies "no overflow" while being unusable — and arm B2 showed it also catches the blank-page case as a second, weaker control.
- **The MOBL-01 block's identical vacuous line was NOT fixed.** The plan forbids changing existing assertions; it is recorded in the docblock and in `deferred-items.md` instead.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 — Bug] The 375px overflow assertion could not fail**

- **Found during:** Task 1, break arm A.
- **Issue:** The plan's `<interfaces>` block specifies lifting `expect(geom.docScrollWidth).toBeLessThanOrEqual(geom.viewportWidth + 1)` from the existing 375px block, where `viewportWidth` is `window.innerWidth`. Under `isMobile: true`, Chromium emulates a phone layout viewport and widens it to fit oversized content, so `window.innerWidth` grows with `docScrollWidth` and the comparison is between a number and itself. Measured: an 825px overflow on a 375px viewport reported `1 passed`, rc=0.
- **Fix:** Compare against `page.viewportSize()!.width` (the configured 375, which content cannot move), and additionally assert the layout viewport did not widen — that widening is the user-visible symptom (the page zooms out). The measured probe output and the mechanism are written into the block's docblock so the next person does not re-lift the broken form.
- **Files modified:** `frontend/e2e/dashboard-mobile.spec.ts`
- **Verification:** same injection now `Expected: <= 376 / Received: 1200`, rc=1; clean arm 24 passed on both projects.
- **Committed in:** `393c9cc1`

**2. [Rule 2 — Missing critical record] Out-of-scope instances of the same shape logged**

- **Found during:** Task 1, after the arm-A finding.
- **Issue:** The same `scrollWidth` vs `innerWidth` shape exists in the single-route MOBL-01 block and at three sites in `frontend/e2e/public-layout.spec.ts` (`:225`, `:248`, `:380`). Both are outside this plan's `files_modified`, and the plan explicitly forbids touching the former.
- **Fix:** Recorded in `.planning/phases/34-rendering-test-truthfulness/deferred-items.md` with the measurement, the boundary of the claim (the mechanism only bites under `isMobile`; the desktop arm is probably live and **has not been run**), and what would close it.
- **Committed in:** `1139b566`

---

**Total deviations:** 2 auto-fixed (1x Rule 1, 1x Rule 2).
**Impact on plan:** The Rule 1 fix is the difference between a test and a decoration — without it this plan would have shipped eleven assertions incapable of failing and reported them as satisfied. No scope creep: one yardstick changed inside the block this plan owns.

## Issues Encountered

- **The worktree was branched from `main` (896c8828), not from the phase base.** Detected by `git merge-base` at startup; reset to `0b6a581c` per the startup protocol, verified by `git rev-parse`. Known intermittent behaviour.
- **The worktree had no `frontend/node_modules`.** `npm ci` run in-place (rc=0); nothing copied from the main checkout.
- **The scratchpad is shared between sibling agents.** A message file written for this plan was overwritten by another agent's plan-34-06 text mid-run. Detected before use (the commit had already been made and was read back from `git log -1 --format=%B` intact); subsequent files moved to a private subdirectory. No commit here carries another plan's text.

## Threat Model Verification

| Threat ID | Disposition | Evidence |
|---|---|---|
| T-34-05-01 credentials in a new block | mitigated | `rg -uu -n -i 'password'` on the spec -> 6 hits, all at `:29-:139`, all pre-existing and above the new block. No literal added; the new block calls the existing `vendorLogin`. |
| T-34-05-02 an overflow assertion that cannot fail | mitigated — **and it had already happened** | Arm A caught it; the fixed assertion goes red at `Expected: <= 376 / Received: 1200`. Presence controls proven load-bearing by arm B2. |
| T-34-05-03 moving the shared mobile viewport | mitigated | `git diff --name-only -- frontend/playwright.config.ts` empty; contract gate rc=0; instrument contract 2 passed. |
| T-34-05-04 stubbed API masking an authorisation failure | accepted (pre-existing) | The presence controls make a zero-grant fallback visible rather than silent. |
| T-34-05-SC npm installs | mitigated | `git diff --name-only -- frontend/package.json frontend/package-lock.json` empty. `npm ci` installed the committed lockfile only; nothing added. |

## Known Stubs

None introduced. The nine pre-existing `context.route` stubs are unchanged and are now documented as deliberate (#542's SSR-coverage question, not a #286 defect).

## Threat Flags

None — this plan adds test code only; no network endpoint, auth path, file access pattern or schema changed.

## Next Phase Readiness

- **34-10 must regenerate `docs/metrics.json`.** This plan adds **11** Playwright `test()` blocks to `frontend/e2e/dashboard-mobile.spec.ts` and touches no prose count. `docs/metrics.json` was deliberately NOT regenerated here — 34-10 is its single writer, and the wave-1 lesson (four worktrees, four different wrong totals) is why.
- **34-07 owns `scripts/gates/ssr-routes.conf`.** The docblock references it and records that it does not exist yet, so if 34-07 changes the filename the reference must follow.
- **Open, deferred:** two other sites carry the vacuous overflow shape — see `deferred-items.md`. `public-layout.spec.ts` has NOT been run in the fail direction; that is unfinished work, not a clean bill.

## Self-Check: PASSED

Every file and commit this SUMMARY claims was verified to exist, with negative
controls proving the check can report both verdicts.

```
FOUND:   frontend/e2e/dashboard-mobile.spec.ts
FOUND:   .planning/phases/34-rendering-test-truthfulness/deferred-items.md
FOUND:   .planning/phases/34-rendering-test-truthfulness/34-05-SUMMARY.md
FOUND:   3d435ca1   FOUND: 393c9cc1   FOUND: 3d1a055f   FOUND: 1139b566
MISSING: deadbeef1234  (control ok)      MISSING: bogus file (control ok)
code-only stub count=9  rc=0   naive stub count=11 rc=0   bogus pattern=0 rc=1
failures=0
```

**The first self-check was itself broken, in two documented ways, and is recorded
because a green run from it would have been worthless:**

1. `rg` is a shell function — invisible inside a `bash script.sh` subshell. It
   died `rc=127` with zero output, which is indistinguishable from "not found".
   Only printing the rc revealed it. Replaced with `grep` on named files.
2. `git log --oneline --all | grep -q "$hash"` under `set -o pipefail` **inverts
   on match**: grep exits first, `git log` takes SIGPIPE -> 141, and the `if`
   reads false. It printed `MISSING` for all four real commits — and, critically,
   also printed `MISSING` for the deliberately-bogus control, so the control could
   not detect it. A check that returns the same verdict in both directions is not
   a check. Replaced with a here-string on data already in hand.

---
*Phase: 34-rendering-test-truthfulness*
*Completed: 2026-08-28*
