---
phase: quick-260830-ngf
plan: 01
subsystem: frontend-dashboard
tags: [error-states, feb-1, a11y-2, toasts]
requires: []
provides:
  - "products load toast routed through describeLoadError (no raw axios string)"
  - "count subtitles on all four dashboard list pages dash out under loadFailed"
  - "customers/shops mutation toasts routed through describeLoadError (A11Y-2 parity with orders)"
created: 2026-08-30
branch: feature/688-products-error-state-residuals
---

# Quick Task 260830-ngf: Close #688 — products error-state residuals + the sweep it asked for

## Problem

#685's FEB-1 fix ships the honest error panel, but on `/dashboard/products` two edges of
the same class survive: the card subtitle still asserts "0 products in total" while the
panel says the load failed, and the load toast shows raw axios text ("Request failed with
status code 500") because the catch at `page.tsx:201` reads `error.message` directly
instead of `describeLoadError` (the A11Y-2 class fixed on orders).

## Sweep findings (the issue asked for orders/customers/shops//shop)

- **orders** — clean on both toast edges (A11Y-2 reference implementation), but its count
  subtitle renders unconditionally: same false-zero edge.
- **customers** — count edge at `:269`; TWO raw-axios mutation toasts (`:188` update/create,
  `:214` delete).
- **shops** — count edge at `:307`; TWO raw-axios mutation toasts (`:227`, `:253`).
- **`/shop`** — no raw-axios toasts (public storefront, separate pattern). Untouched.

## Fix

1. `products/page.tsx` — load catch routed through `describeLoadError(error,
   "Failed to load products")`, message shared by toast and panel (mirrors orders:330).
2. Count subtitles on products/orders/customers/shops render **"—"** when `loadFailed`
   (the issue's suggested form) instead of a number nothing loaded.
3. `customers/page.tsx` + `shops/page.tsx` — the four mutation toasts routed through
   `describeLoadError(error, <existing fallback>)`, exactly the orders A11Y-2 shape.
   RFC 7807 `detail` still wins when the server sends one — that is the helper's contract.

## Verification

- Extend `products/__tests__/error-state.test.tsx` with two tests, both run in the fail
  direction first (on the pre-fix tree they must FAIL):
  a) under a load failure the subtitle does NOT contain "0 products" and DOES contain "—";
  b) the toast receives the humanised fallback, never "Request failed with status code".
- Full jest suite green; `docs/metrics.json` regenerated with `docs-freshness.sh --write`
  (+2 jest blocks) and the three gated prose sites reconciled; `check-doc-metrics` +
  `docs-freshness` + `check-doc-citations` all rc=0 locally.
- Browser proof on the live stack: stub products 500 via Playwright route interception,
  capture the panel + dashed subtitle + humanised toast (the issue's own method, both
  directions: same assertions fail without the stub).
