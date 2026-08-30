---
phase: quick-260830-ngf
plan: 01
subsystem: frontend-dashboard
tags: [error-states, feb-1, a11y-2, toasts]
status: complete
requires: []
provides:
  - "dashed count subtitles under loadFailed on products/orders/customers/shops"
  - "six raw-axios toasts routed through describeLoadError (products load + customers/shops mutations)"
  - "2 new jest blocks; docs/metrics.json 3492 -> 3494 with prose reconciled"
affects: [frontend/app/dashboard, docs/metrics.json]
key-files:
  created: []
  modified:
    - frontend/app/dashboard/products/page.tsx
    - frontend/app/dashboard/orders/page.tsx
    - frontend/app/dashboard/customers/page.tsx
    - frontend/app/dashboard/shops/page.tsx
    - frontend/app/dashboard/products/__tests__/error-state.test.tsx
    - docs/metrics.json
    - README.md
    - AGENTS.md
    - CLAUDE.md
decisions:
  - "Subtitle renders an em dash under loadFailed (the issue's suggested form) rather than omitting — the header keeps its shape and the dash is an explicit 'unknown', not an absence a reader might not notice"
  - "The sweep the issue asked for was done and acted on: orders was already clean on toasts (the A11Y-2 reference) but had the count edge; customers and shops each had the count edge plus two raw-axios mutation toasts; /shop has no raw-axios toasts (separate public pattern) and is untouched"
metrics:
  duration: "~35 min (15:55-16:30Z, 2026-08-30)"
  completed: "2026-08-30"
---

# Quick Task 260830-ngf: Close #688 — error-state residuals swept across the dashboard Summary

Both filed residuals fixed on `/dashboard/products` and the same two edges closed on the
other three dashboard list pages: the count subtitle dashes out when the load failed
(never "0 <things> in total" beside an error panel), and every toast that read
`error.message` off an axios error now routes through `describeLoadError` so RFC 7807
detail wins and transport strings never render.

## Commits

| Task | Commit | Files |
| ---- | ------ | ----- |
| fix + tests + metrics | `7f9a17c8` | 4 pages, error-state.test.tsx, metrics.json, 3 prose docs |

## Sweep result (the issue's scope note, answered)

| surface | count edge | raw-axios toast |
|---|---|---|
| products | FIXED (`:440`) | FIXED — load toast (`:201`) |
| orders | FIXED (`:603`) | already clean (A11Y-2 reference impl) |
| customers | FIXED (`:269`) | FIXED ×2 — update/create + delete mutations |
| shops | FIXED (`:307`) | FIXED ×2 — update/create + delete mutations |
| `/shop` | n/a | clean — public storefront, separate pattern; untouched |

## Verification

**jsdom, fail direction first.** The two new tests were run against the PRE-fix
`products/page.tsx` (stashed by path, restored by status): **2 failed / 3 passed** — only
the new tests failed, so each is precise to its fix. Post-fix: file 5/5; the four touched
pages' suites **9 suites / 58 tests** green; full jest **141 suites / 1505 tests** green.

**Real browser, both directions, against the REBUILT compose container** (the issue's own
method — image rebuilt + `--force-recreate` before any claim):

- STUB arm (`GET /api/v1/products**` → 500):
  `{"panel":true,"zeroInTotal":0,"dash":1,"toastRawAxios":false,"toastText":"… Error loading productsFailed to load products"}`
  — panel renders, zero "0 products in total" matches, the em dash present, toast text
  captured NON-EMPTY (so the raw-axios assertion is not vacuous) and humanised.
  Screenshot shows all three at once.
- CONTROL arm (no stub): `{"countVisible":true,"panelCount":0}` — a real count renders
  and no panel, so the stub arm's assertions are about the failure state, not the page.

**Docs loop closed:** `docs-freshness.sh --write` regenerated `docs/metrics.json`
(jest_blocks 1503 → **1505**, total 3492 → **3494**); README/AGENTS.md/CLAUDE.md
reconciled; `check-doc-metrics` rc=0 (37/37 claims) and `docs-freshness` rc=0 — both
halves of the #582-class loop green before the PR.

## Deviations from plan

None of substance. The browser probe initially failed `MODULE_NOT_FOUND` (node resolves
relative to the script's path, not cwd) — fixed with `NODE_PATH`, no result was read from
the failing form.

## Self-check: PASSED

Commit `7f9a17c8` on `feature/688-products-error-state-residuals`; all 9 files staged by
name; both proof screenshots in the session scratchpad; full jest green at the new count.
