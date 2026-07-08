---
quick_id: 260708-teb
issue: 85
title: "[P1-3] Guest-checkout stock — TOCTOU + apparent double-decrement (VERIFY FIRST)"
status: complete
date: 2026-07-08
branch: feature/85-guest-checkout-stock
verdict: double-decrement CONFIRMED
---

# Quick Task 260708-teb — Issue #85 [P1-3] guest-checkout stock

## Empirical verdict (VERIFY FIRST): double-decrement **CONFIRMED**

The audit flagged the double-decrement as *apparent*, not confirmed. Task 1 wrote a
characterization integration test **before** any remediation and empirically confirmed it:

- A guest order for **qty=3** against a product seeded at **stock=10**, then vendor-CONFIRMED,
  left stock at **4** pre-fix (delta = 2 × qty = 6) — stock was decremented **twice**:
  1. eagerly in `PublicStorefrontService.createGuestOrder` (naked read-modify-write, no `@Version` retry), and
  2. again at the `PENDING → CONFIRMED` transition via `OrderService.transitionOrder → StockService.decrementForOrder`.
- The webhook path (`PaymentService.handlePaymentIntentSucceeded`, DRAFT→PENDING) does **no** stock reconciliation.
- The admin `OrderService.createOrder` path does **not** decrement — confirming that single-decrement-at-CONFIRM is the intended CQ-01 design.

## Fix — converge to a single, retry-safe decrement point

`PublicStorefrontService.createGuestOrder`: removed the eager "Deduct stock" for-loop entirely.
Stock is now decremented **exactly once**, at the `CONFIRMED` transition, through the retry-safe
`StockService.decrementForOrder` (optimistic-lock retry — concurrent contention retries instead of
surfacing a customer-facing 500). This:
- matches the admin `createOrder` path (which never eager-decremented), and
- restores cancel-path restock symmetry — `restoreForOrder` fires only for `oldStatus >= CONFIRMED`,
  which is now exactly where the decrement also lives, so cancel/refund restock stays balanced.
- The read-only `product.hasStock(...)` availability check in `createGuestOrder` is kept as an early
  UX signal; it is **not** a reservation.

No schema change. `RefundService` touches no stock, so refund restock is unaffected.

## Acceptance criteria

- [x] A test reproduces the current behaviour (double-decrement **CONFIRMED** — documented above).
- [x] Stock is decremented exactly once per order across guest→confirm→webhook paths (single point at CONFIRM).
- [x] Concurrent checkout retries rather than returning 500 (StockService optimistic-lock retry; Test B asserts no 500 under concurrent last-unit checkout).

## Tests

- `GuestCheckoutStockConvergenceIntegrationTest` (Testcontainers, real Postgres + RLS), 2 tests:
  - **Test A** `guestCheckoutThenConfirm_decrementsStockOnce` — post-fix invariant: guest→confirm leaves stock at 7 (delta = 1 × qty).
  - **Test B** `concurrentGuestCheckout_lastUnit_surfacesContention` — concurrent last-unit checkout surfaces contention without a 500; authoritative decrement/oversell rejection deferred to CONFIRM.
- `StockDecrementLocationTest` — extended with `createGuestOrderDoesNotWriteStockInPlace`, a source guard that fails loudly if a future refactor reintroduces a guest-path `setQuantityInStock`/`productRepository.save(product)` write.
- Full `./gradlew :core-java:test :core-java:integrationTest` — **BUILD SUCCESSFUL (8m49s)**, 0 failures. RoleBasedAccessIntegrationTest (#83) and GdprErasureIntegrationTest (#84) remain green.
- `docs/metrics.json` + CLAUDE.md regenerated: 736 → **739** logical invocations (540 Java @Test across 84 files).

## Notes / recovery

The gsd-executor subagent hit the account session limit while running the final gate; Tasks 1–2 were
already committed in its worktree (`5e2f131`, `99a975f`). Recovered inline in the orchestrator context:
merged the worktree, re-ran the full gate to green, regenerated docs, and authored this SUMMARY —
so Task 3 (gate + docs) is complete despite the subagent's early termination.

## Commits

- `5e2f131` test(260708-teb): characterization test pins guest-checkout double-decrement (#85)
- `99a975f` fix(260708-teb): converge guest checkout to single stock decrement (#85)
- `8ffdf1f` chore(260708-teb): sync docs metrics for #85 stock tests (736→739)
