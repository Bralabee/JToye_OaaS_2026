---
phase: 19-full-frontend-experience-overhaul
plan: 07
subsystem: frontend
tags: [nextjs, react, kitchen-display, order-detail, jest, playwright, tailwind]

# Dependency graph
requires:
  - phase: 19-01 (order fulfilment/address backend)
    provides: OrderItem.productName snapshot (no more 'Unknown Product') + OrderDetailDto fulfilmentType/address
provides:
  - "KDS elapsed-time cap/format (just now / Xm / Xh / Xd) — no raw '2245m ago' (#12)"
  - "Kitchen card header badge-clip fix: order number min-w-0 truncate text-lg font-semibold, badge flex-shrink-0, header gap-2 (#8)"
  - "OrderDetail TS type + OrderDetailPanel expose fulfilmentType + read-only delivery-address block (DELIVERY only)"
  - "Real-product-name render verified on kitchen + order-detail (no 'Unknown Product' for existing products) (#2)"
  - "kitchen-flow e2e: no-'Unknown-Product' + clean-badge + capped-elapsed assertions using domcontentloaded"
affects: [19-08-palette-sweep, dashboard-order-detail, kitchen-display]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Capped/formatted relative-time helper (never raw uncapped minutes) for live dashboards"
    - "flex child min-w-0 + truncate + sibling flex-shrink-0 to stop a long header value clipping a badge"
    - "Playwright assertion of a NEGATIVE string (getByText().toHaveCount(0)) against seeded real data, with domcontentloaded (SSE/STOMP never idles)"

key-files:
  created:
    - .planning/phases/19-full-frontend-experience-overhaul/19-07-SUMMARY.md
  modified:
    - frontend/app/dashboard/kitchen/page.tsx
    - frontend/app/dashboard/kitchen/__tests__/page.test.tsx
    - frontend/components/dashboard/orders/OrderDetailPanel.tsx
    - frontend/components/dashboard/orders/__tests__/OrderDetailPanel.test.tsx
    - frontend/types/api.ts
    - frontend/e2e/kitchen-flow.spec.ts

key-decisions:
  - "Delivery-address block lives in OrderDetailPanel (which orders/[id]/page.tsx already renders) rather than duplicated on the page — the page needed no change"
  - "Left the PREPARING purple untouched (bg-purple-500/600) — the palette sweep 19-08 owns that same-file edit; purple- grep count stays at 2"
  - "elapsedText caps: <1m 'just now', <60m 'Xm ago', <24h 'Xh ago', >=24h 'Xd ago'"

patterns-established:
  - "Deterministic elapsed-time unit tests via createdAt = now - N*60000 fed through the render, asserting the capped label"

requirements-completed: [UIX-03, UIX-06]

# Metrics
duration: ~30min
completed: 2026-07-11
---

# Phase 19 Plan 07: Kitchen + Order-Detail Product Names & Fixes Summary

**KDS elapsed time is now capped/formatted (no '2245m ago'), the kitchen card status badge no longer clips a wrapped long order ID (truncate + flex-shrink-0), the order-detail panel renders a read-only delivery-address block for DELIVERY orders, and unit + e2e coverage assert that real snapshotted product names render on both surfaces while 'Unknown Product' never appears for an existing product — all building on 19-01's backend data fix.**

## Performance

- **Duration:** ~30 min
- **Completed:** 2026-07-11
- **Tasks:** 3
- **Files modified:** 6 (0 new source files; 1 SUMMARY created)

## Accomplishments

- **#12 elapsed-time cap** — `elapsedText()` (kitchen/page.tsx) now formats `<1m → "just now"`, `<60m → "Xm ago"`, `<24h → "Xh ago"`, `>=24h → "Xd ago"`. The audit's raw `2245m ago` becomes `1d ago`.
- **#8 badge-clip fix** — the card header is now `flex items-start justify-between gap-2`; the order number is `min-w-0 truncate text-lg font-semibold` (twMerge drops the old `text-2xl`); the status badge is `flex-shrink-0`. A long `ORD-…` number ellipsizes instead of wrapping under the badge.
- **#2 product-name render (UI side)** — confirmed both surfaces render `productName`: the kitchen card renders `{item.quantity}x {item.productName}`; `OrderDetailPanel` renders `item.productName` (falling back to a productId substring, never the literal `"Unknown Product"`). The data fix itself is 19-01's backend snapshot + backfill.
- **UIX-06 delivery-address block** — `OrderDetailPanel` gained a read-only Fulfilment block: a Delivery/Collection label always, plus a `data-testid="delivery-address"` sub-block (line1/line2/city/postcode) for `DELIVERY` orders, omitted for `COLLECTION`. Address is rendered only (never logged — T-19-07-01).
- **Type wiring** — `OrderDetail` TS interface + a new `FulfilmentType` union now mirror 19-01's `OrderDetailDto` fulfilment/address fields so the panel type-checks.
- **Coverage** — kitchen unit tests (10) assert the truncate/flex-shrink classes, all four elapsed thresholds, the preserved green age-border, and mute persistence; `OrderDetailPanel` tests (11) assert the no-'Unknown Product' render plus delivery-vs-collection address behaviour; `kitchen-flow` e2e asserts `getByText("Unknown Product").toHaveCount(0)` on both the kitchen display and a new order-detail navigation, a truncating order number, `1d ago` (not `2245m`), and the delivery-address block — all via `domcontentloaded`.

## Task Commits

Each task was committed atomically:

1. **Task 1: Kitchen card badge-clip fix + elapsed-time cap** — `30b6b77` (fix)
2. **Task 2: Order-detail product-name render + delivery-address block + type wiring** — `a74b257` (feat)
3. **Task 3: Kitchen-flow e2e (no 'Unknown Product' / clean badge / capped elapsed, domcontentloaded)** — `2a509ad` (test)

## Files Created/Modified

- `frontend/app/dashboard/kitchen/page.tsx` — capped `elapsedText()`; header `gap-2`, title `min-w-0 truncate text-lg font-semibold`, badge `flex-shrink-0`. STOMP feed, `ageBorderClass`, mute toggle, and PREPARING purple untouched.
- `frontend/app/dashboard/kitchen/__tests__/page.test.tsx` — badge/title class assertions, four elapsed-threshold tests, green age-border preservation; `stubApi`/`orderDetailPayload` take an optional `createdAt`.
- `frontend/components/dashboard/orders/OrderDetailPanel.tsx` — read-only Fulfilment + delivery-address block (DELIVERY only).
- `frontend/components/dashboard/orders/__tests__/OrderDetailPanel.test.tsx` — no-'Unknown Product' assertion + delivery/collection address-block tests.
- `frontend/types/api.ts` — added `FulfilmentType` union and `fulfilmentType` + `addressLine1/2/city/postcode` to `OrderDetail` (see Deviations, Rule 3).
- `frontend/e2e/kitchen-flow.spec.ts` — real-name + badge + capped-elapsed assertions on kitchen; new order-detail test; `domcontentloaded` throughout; zero `networkidle`.

## Decisions Made

- **Address block placement:** put the delivery-address block in `OrderDetailPanel` (which `orders/[id]/page.tsx` already renders) instead of adding a second render on the page. The acceptance ("orders/[id] renders a delivery-address block") is met transitively, and `orders/[id]/page.tsx` needed no edit.
- **Purple deferred:** did not touch `bg-purple-500/600` — the 19-08 palette sweep owns that same-file edit to avoid a cross-wave double edit. `grep -c "purple-" kitchen/page.tsx` stays at 2 (unchanged).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added fulfilment/address fields to the `OrderDetail` TS type**
- **Found during:** Task 2
- **Issue:** The plan's Task 2 action requires rendering `fulfilmentType` + address from the DTO 19-01 exposed, but the frontend `OrderDetail` interface (`frontend/types/api.ts`, not in the plan's `files_modified`) did not carry those fields — the panel would not type-check (`npm run build` would fail).
- **Fix:** Added a `FulfilmentType = "DELIVERY" | "COLLECTION"` union and `fulfilmentType` + `addressLine1/2/city/postcode` (nullable) to `OrderDetail`, mirroring `OrderDetailDto.java` from 19-01.
- **Files modified:** frontend/types/api.ts
- **Verification:** `npm run build` succeeds; `OrderDetailPanel.test.tsx` delivery/collection tests green.
- **Committed in:** `a74b257` (Task 2 commit)

**2. [Plan-listed file not modified] `frontend/app/dashboard/orders/[id]/page.tsx`**
- **Reason:** the page already renders `<OrderDetailPanel order={order} …>` with the full detail response (which now includes fulfilment/address), so the delivery-address block renders transitively via the panel. Editing the page would duplicate the block. No functional gap; left unchanged.

**Total deviations:** 1 auto-fixed (Rule 3 blocking type addition) + 1 documented no-op on a listed file.
**Impact on plan:** none negative — the type addition was mandated by the plan's own Task 2 action; the frontmatter file list was simply missing `types/api.ts`.

## Issues Encountered

- **Worktree had no `node_modules`** (gitignored, not copied into git worktrees). Jest ran fine against a symlink to the main checkout's install, but Turbopack rejected the escaping symlink (`Symlink … points out of the filesystem root`). Resolved by replacing the symlink with a real local copy of the (identical package.json/lock) install — no package installs, no dependency changes. This is an environment fix only; the copy is gitignored and discarded with the worktree.

## Test Results

- `jest app/dashboard/kitchen/__tests__/page.test.tsx` — PASS (10/10)
- `jest components/dashboard/orders/__tests__/OrderDetailPanel.test.tsx` — PASS (11/11)
- Combined scoped run — PASS (21/21)
- `npm run build` — SUCCESS (all routes compiled, incl. `/dashboard/kitchen`, `/dashboard/orders/[id]`)
- `grep -c "networkidle" e2e/kitchen-flow.spec.ts` — 0
- `kitchen-flow.spec.ts` Playwright run deferred to 19-09 UAT against the rebuilt stack (syntax/type-checked now; the touched non-test files are tsc-clean).

## Known Stubs

None — the delivery-address block is wired to the real `OrderDetail` fields (schema → DTO → type → render). The pre-existing `toBeInTheDocument`/`toHaveClass` `tsc` errors across `__tests__` files are pre-existing jest-dom matcher noise outside the jest runtime (the plan's verify pipes them to `/dev/null`); the real gate is `npm run build`, which passes.

## Threat Flags

None — no security surface beyond the plan's `<threat_model>` was introduced. Registered mitigations are honoured: T-19-07-01 (delivery address is a read-only render; no console/analytics logging added), T-19-07-02 (e2e uses `domcontentloaded` + explicit waits and exact `toHaveCount(0)` string assertions on seeded orders), T-19-07-SC (no package installs).

## Self-Check: PASSED

- Modified files verified present: kitchen/page.tsx, kitchen/__tests__/page.test.tsx, OrderDetailPanel.tsx, OrderDetailPanel.test.tsx, types/api.ts, e2e/kitchen-flow.spec.ts
- Task commits verified in git log: 30b6b77, a74b257, 2a509ad
- Product-name render intact: `item.productName` present on kitchen card (2 refs) and `productName` in OrderDetailPanel (1 ref)

---
*Phase: 19-full-frontend-experience-overhaul*
*Completed: 2026-07-11*
