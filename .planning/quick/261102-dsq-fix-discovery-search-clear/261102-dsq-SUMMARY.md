---
phase: quick
plan: 261102-dsq
subsystem: storefront / kitchen directory (/shop)
tags: [frontend, storefront, search, debounce, r-02, r-09, external-tester]
status: complete
requires: []
provides:
  - "SEARCH_DEBOUNCE_MS (300) and a debounced resultsQuery behind the /shop fetch"
  - "applyQueryNow: X, chips, 'Browse all kitchens', URL changes and an emptied box apply at once"
  - "native search cancel button hidden; one clear affordance"
  - "4 Jest cases: the search box clears cleanly (2026-11-02 tester)"
affects: []
tech-stack:
  added: []
  patterns: ["debounce the fetch, not only the URL write; a cleared box is a decision, not a fragment"]
key-files:
  created:
    - .planning/quick/261102-dsq-fix-discovery-search-clear/evidence/red-green.txt
  modified:
    - frontend/app/shop/shop-discovery-client.tsx
    - frontend/app/shop/__tests__/shop-discovery-client.test.tsx
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md
    - docs/CHANGELOG.md
decisions:
  - "The backspace glitch is the un-debounced per-keystroke fetch, not an R-02 re-seed: measured at 120/450/700 ms per key and with 250 ms latency, box and URL always agreed and never re-seeded"
  - "Debounce 300 ms (the URL write keeps its own 400 ms); an empty box is applied immediately so the X and backspace-to-empty end the same way"
  - "Summary and empty-state copy read resultsQuery, so a count always names the query it counted"
metrics:
  completed: 2026-11-02
commits: 2
plan_head_before: 9b737e61
---

# Quick 261102-dsq: /shop search box — one clear button, no fragment searches on backspace

## What was wrong, measured

- **Two Xs.** `type="search"` makes Chrome paint its native cancel button beside the island's
  "Clear search" button (`evidence/before-typed-box.png`).
- **The backspace glitch.** Not a re-seed: in every browser run (120, 450, 700 ms per key; typed
  and deep-linked) the box ended `""`, the URL lost `q` and nothing came back. The glitch was
  the per-keystroke fetch: backspacing "pounded yam" searched every fragment, and the grid
  strobed between cards and the full "No kitchens found" empty state for "pounded ya",
  "pounded y", "poun", "pou", "po" (`evidence/before-summary.txt`); with 250 ms of latency it sat
  on the skeleton for the whole run (`evidence/before-latency250-summary.txt`). 23 requests for
  one type-and-backspace of an 11-character query.

## The fix

`shop-discovery-client.tsx`: the fetch keys on `resultsQuery`, which follows the box after
`SEARCH_DEBOUNCE_MS` (300 ms) of stillness. An emptied box (onChange), the X, a chip,
"Browse all kitchens" and a URL change go through `applyQueryNow` and apply at once. The
summary line and empty-state copy read `resultsQuery`. The input keeps `type="search"` and hides
`::-webkit-search-cancel-button` / `::-webkit-search-decoration`.

## Evidence

All under `.planning/quick/261102-dsq-fix-discovery-search-clear/evidence/`; `red-green.txt`
narrates every direction.

| Check | Before / fail direction | After |
|-------|-------------------------|-------|
| Box screenshot | two Xs (`before-typed-box.png`) | one X (`after-typed-box.png`) |
| Browser, 120 ms/key, typed | 23 requests, empty state at 5 fragments | 4 requests, result held until "", then all |
| Browser, 250 ms latency | skeleton for the whole backspace | one skeleton, for the final answer |
| Jest, new describe (4 cases) | 4 failed, 38 passed, rc 1 (`jest-red-unfixed.txt`) | 42 passed, rc 0 (`jest-green-fixed.txt`) |
| Full Jest | 3454 tests on main | 292 suites, 3458 passed (`jest-full.txt`) |
| `npm run lint` | — | rc 0, 0 errors; no warning in the changed files |
| `npm run build` | — | rc 0, TypeScript finished (`build.txt`) |
| `scripts/docs-freshness.sh` | rc 1 (3454/6997 vs tree) | rc 0 (7001) |
| `scripts/check-doc-metrics.sh` | 7 FAIL lines | PASS 37/37 |

## Deviations / recorded, not hidden

- **R-09 harness.** Its two arms read `expect(queue).toHaveLength(n)` synchronously after a
  keystroke; with the debounce they `await waitFor(...)`. The claim (two requests in flight, the
  older settling last never wins) is unchanged. The R-02 block is unmodified.
- **The computed-style probe for the native X cannot fail** (Chrome reports `appearance: auto`
  for the pseudo-element before and after). It is not used as evidence; the screenshots and the
  generated CSS rule are.
- **A customer who pauses longer than 300 ms on each key** still gets each fragment searched
  (`after-bs450-summary.txt`). That is the debounce working as intended, not a residue.
- **Unverified:** only Chromium was driven. Safari uses the same `-webkit-` pseudo-element but was
  not run; whether a current Firefox paints its own clear button for `type=search` was not checked. The tester's own
  device and network were not available, so their "glitch" is matched to the measured symptom,
  not observed on their screen. Staging was not driven; staging's public rate limit (30/min +
  10 burst per IP, `application.yml`) makes the old per-keystroke fetch a 429 risk there, which
  this fix reduces but which was not measured.
- Dev-runtime console noise: Auth.js `ClientFetchError` from the dev server's missing auth
  config, present before and after, unrelated to the island.

## Quality dimensions

- Web performance: fewer requests and no skeleton/empty-state churn while typing; the search
  input and the reserved summary-line element are unchanged (CLS-sensitive), so no layout change.
- SEO: N/A (no change to SSR output, titles or links).
- Agent-readiness: N/A (no API change).
- Security: N/A (no new input path; the query still goes through the same public client).
- Client-persisted identity: N/A (no client storage).
- Falsifiability: every new assertion shown RED first; the one probe that cannot fail is named.
