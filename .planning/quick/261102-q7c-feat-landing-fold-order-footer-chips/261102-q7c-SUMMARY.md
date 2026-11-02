---
phase: quick
plan: 261102-q7c
subsystem: frontend / public landing + shared public footer
tags: [landing, footer, design-run, D15, external-tester, web-perf, a11y]
status: complete
issue: none
requires: []
provides:
  - "Gold door inside a 1366x768 fold (759px, was 937)"
  - "How it works directly after the hero"
  - "Six cuisine chips in one non-wrapping rail; fit without scrolling from 1280"
  - "Footer + credits 397px on desktop (was 657); credits behind a native details disclosure"
  - "D15 in design/decisions.md"
affects: [landing, public-footer (every public route)]
tech-stack:
  added: []
  patterns: ["sizes='(min-width: 1024px) 1px, ...' so an lg:hidden eager image downloads its smallest candidate"]
key-files:
  created:
    - .planning/quick/261102-q7c-feat-landing-fold-order-footer-chips/evidence/before/measure-before.json
    - .planning/quick/261102-q7c-feat-landing-fold-order-footer-chips/evidence/after/measure-after.json
    - .planning/quick/261102-q7c-feat-landing-fold-order-footer-chips/evidence/after/keyboard-probe.json
  modified:
    - frontend/app/page.tsx
    - frontend/components/public/public-footer.tsx
    - frontend/app/__tests__/landing.test.tsx
    - frontend/components/public/__tests__/public-footer-legal.test.tsx
    - design/decisions.md
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md
    - docs/CHANGELOG.md
decisions:
  - "Owner-approved amendments (2026-11-02) to the D14 landing, from an external tester's feedback (2026-10-30): fold, order, footer, chips"
  - "h1 fallback (56 -> 48px at lg) NOT used: measured, 48px sets the headline on 2 lines from 1280 (D14 item 2 keeps 3) and leaves the 3-line CLS reservation empty; door padding and hero top padding one step tighter at lg instead"
  - "No right-edge fade mask on the chip rail: D14 item 4 chose a peek over a gradient edge fade; the cut-off chip is the peek"
  - "Ground order stone hero / white body / stone trust band / oxblood footer; identity.md's ground table not edited (outside this lane's file set) and now lists How it works on warm-100"
metrics:
  completed: 2026-11-02
commits: 4
---

# Quick 261102-q7c: landing fold, section order, footer height, one chip row — Summary

Four owner-approved amendments to `/` from an external tester, measured before and after with a
Playwright node script (`evidence/scripts/measure.cjs`) against a local `next dev` on :3437 reading
the Compose stack's API. The BEFORE numbers come from origin/main's two files and equal the
figures measured on staging (door 622/315, footer 497, credits 160/260, footer 955 at 390).

## Before / after

| Measure | Viewport | Before (origin/main) | After |
|---|---|---|---|
| Chip rows (distinct `top`) | 1024 / 1280 / 1366x768 / 1440x900 / 390 | 2 / 2 / 2 / 2 / 3 | 1 / 1 / 1 / 1 / 1 |
| Chip rail scrollWidth vs clientWidth | 1280, 1366, 1440 | wrapped (580/580) | 588/588, fits, no scroll (content 569 in the 580 column) |
| Chip rail | 1024 / 390 | wrapped | 577/460 and 577/366: swipes (as specified below 1280) |
| Gold door top / bottom, unscrolled | 1366x768 | 622 / 937 | 554 / **759** ("Browse shops" bottom 739) |
| Gold door bottom | 390x844 | 890 | 782 (strip kept) |
| h2 order in main | all | Kitchens on J'Toye, How it works | **How it works, Kitchens on J'Toye** |
| Footer height | 1280-1440 / 1024 / 390 | 497 / 517 / 955 | 377 / 397 / 732 |
| Photo credits block | desktop / 390 | 160 / 260 (`<p>`) | 20 / 20 (`<details>`, closed) |
| Footer + credits | desktop | 657 | **397** |
| h1 | all | 56px (3 lines), 34px at 390 | unchanged |

## Gates

| Gate | Fail direction | After |
|---|---|---|
| 5 new D15 Jest cases | origin/main's page.tsx + footer swapped in: 5 failed, rc 1 (`evidence/jest-D15-red-on-origin-main.txt`) | 5 passed |
| landing + components/public Jest | — | 9 suites, 125 tests, rc 0 |
| Full Jest | one run under load had 1 unrelated failure (#485 products picker); passes alone and on the re-run | 292 suites, 3459 tests, rc 0 |
| `npm run lint` | — | rc 0, 0 errors (26 pre-existing warnings, none in the changed files) |
| `npm run build` | — | rc 0 |
| `public-a11y.spec.ts` "/ has no WCAG 2.2 AA violations" (mobile + desktop), on `next start` | not re-run against a broken page here | 2 passed |
| `marketing-motion.spec.ts` + `marketing-dish-scroller.spec.ts` | — | all passed on `next dev` and `next start` |
| `landing-webperf.spec.ts` | — | 3 of 4 per project passed on `next start` (LCP/CLS, card-box reservation, JS baseline); POST-GRANT failed on :3437 because core's CORS refuses that origin (`evidence/cors-control.txt`: 403 vs 200 for :3000); the same test passes against the Compose frontend on :3000 (control) |
| taste-skill `lint.mjs` (2 files) | origin/main: 2 FAIL (footer hairlines), rc 1 | 0 FAIL, 1 WARN (em-dash count), rc 0 |
| taste-skill `inspect.mjs` (`--allow still-page`) | origin/main: 6 FAIL, 6 WARN | 5 FAIL, 8 WARN; see below |
| `scripts/check-geo-attribution.sh` | — | PASS, rc 0 |
| `scripts/docs-freshness.sh` / `check-doc-metrics.sh` | rc 1 / 7 FAIL before the update | rc 0 / 37 claims PASS (this branch adds 5 Jest blocks: 3454 -> 3459 on 9b737e61; after merging #1094, 3463 and 7006) |
| Keyboard: Tab from the search box to the sixth chip | — | reached at 390, 1024, 1366; chip fully inside the rail, ring painted, page overflow 0 (`evidence/after/keyboard-probe.json`) |

**inspect, after.** FAILs: `headline-lines` at 1366 and 390 and `focal-point` (accepted by the owner
in D14 item 2), `micro-label` (the footer's column headings) and `body-size` 14px: all four
pre-existing on origin/main, not introduced here. Removed: the `hairline` FAIL (footer rules).
New WARN: `edge-text` at 390, the peeking "South Asian" chip at the rail's edge, which is the D14
item 4 peek. `side-scroll` at 390 was already reported on origin/main (kitchen row) and now also
covers the chip rail, as specified.

## Deviations

1. **No h1 fallback.** The door missed the fold after the specified changes (779 > 768): the gold door
   is 217px, not 195, because it shares a row with the operator door, whose longer copy sets the
   height. Measured (`evidence/scripts/h1probe.cjs`): h1 at 48px sets the headline on 2 lines from
   1280 (D14 item 2 keeps 3) and, with the 3-line CLS reservation kept, leaves an empty line. Instead
   the doors' padding (pt 20->16, pb 24->20, icon gap 12->8) and the hero's top padding (72->64) are
   one step tighter at `lg` only; phones are unchanged.
2. **No fade mask** on the chip rail (D14 item 4 precedent: peek, not a gradient edge fade).
3. **Chip spacing tighter than "slightly":** the 13px labels need 297px, so the six fit 580px only
   with 4px between chips, 4px between picture and label and 10px right padding (569px).
4. **Footer phone layout:** two tracks at base, brand spanning both, instead of four stacked columns;
   the conditional "For operators" column stays last.
5. **Footer rail test:** `public-footer-legal.test.tsx` pinned `py-12` as a displaced good; it now
   pins `py-8`, with the D15 reason in the comment.
6. **identity.md not edited** (outside the lane's file set); D15 records that its ground table now
   lists How it works on warm-100 while the page sets it on white.

## Not verified

- Staging: nothing was deployed. The AFTER numbers are from the local build only.
- axe on the OPEN credits disclosure: the scan runs with it closed. Its text is warm-700 on
  warm-100 with underlined links, the same pairing as before.
- The webperf POST-GRANT test on this branch's build (CORS refuses :3437; it passes on :3000 against
  the Compose frontend, which is not this branch).
- Blind A/B and a separate scorer (design-run step 8) were not run.

## Self-Check: PASSED
