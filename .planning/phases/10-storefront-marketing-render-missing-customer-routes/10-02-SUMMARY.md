---
phase: 10-storefront-marketing-render-missing-customer-routes
plan: 02
subsystem: storefront-frontend
tags: [frontend, storefront, marketing, promotions, announcements, cart, tests]
requires:
  - phase-3/promotions + announcements public endpoints (already shipped)
  - frontend/components/storefront/cart-provider.tsx
  - frontend/components/ui/badge.tsx
provides:
  - PublicPromotion + PublicAnnouncement TypeScript interfaces
  - Dedicated-endpoint-driven announcement banner on /shop/[slug]
  - Memoised O(1) per-category promotion lookup (useMemo Map)
  - Destructive-variant discount badges on ProductCard
  - Jest coverage for the standalone cart page (empty + populated)
affects:
  - frontend/types/storefront.ts
  - frontend/app/shop/[slug]/page.tsx
  - frontend/__tests__/shop/cart.test.tsx
tech-stack:
  added: []
  patterns:
    - useMemo-backed Map for constant-time per-product promotion lookup
    - Parallel Promise.all with per-endpoint .catch fallbacks
    - Pre-resolved thenable wrapper so React.use() unwraps synchronously in jest-jsdom
key-files:
  created:
    - frontend/__tests__/shop/cart.test.tsx
  modified:
    - frontend/types/storefront.ts
    - frontend/app/shop/[slug]/page.tsx
decisions:
  - Kept legacy /public/shops/{slug}/config fetch in place because it still powers ShopConfig.featuredProducts consumed elsewhere on the same page
  - Used a pre-resolved thenable (status=fulfilled, value=...) for the cart test params prop so React.use() unwraps synchronously without microtask-flush flakiness in jest-jsdom
  - Routed the discount badge through a dedicated promo prop on ProductCard (rather than reading context inside the card) so the useMemo Map remains the single source of truth and no .find() leaks into the hot loop
metrics:
  duration: ~10 minutes
  completed: 2026-04-14
---

# Phase 10 Plan 02: Render Storefront Marketing + Cart Test Coverage Summary

**One-liner:** Swap storefront promotion/announcement rendering to the dedicated `/public/shops/{slug}/promotions` and `/announcements` endpoints with a memoised O(1) category lookup and real discount badges on product cards, and add Jest coverage for the standalone cart page.

## Objective

Close the STFR-03 render-path gap (page was still pulling marketing data from the legacy `/config` bundle) and the STFR-04 test gap (no Jest coverage on the standalone `/shop/[slug]/cart` route) without introducing any new routes, components, or backend changes.

## What Changed

### Types (`frontend/types/storefront.ts`)

Added `PublicPromotion` and `PublicAnnouncement` interfaces mirroring the DTO shapes returned by `PublicStorefrontService` at the existing `/public/shops/{slug}/promotions` and `/announcements` endpoints.

### Shop detail page (`frontend/app/shop/[slug]/page.tsx`)

- Added two new calls to the existing `Promise.all` useEffect:
  - `publicApiClient.get<PublicPromotion[]>(/public/shops/${slug}/promotions).catch(...)`
  - `publicApiClient.get<PublicAnnouncement[]>(/public/shops/${slug}/announcements).catch(...)`
- Kept the existing `/config` call intact because `ShopConfig.featuredProducts` is still consumed on the same page.
- New `promotions` and `announcements` state variables.
- `promotionsByCategory` memoised via `useMemo` into a `Map<string, PublicPromotion>` keyed on `promotion.category`.
- Announcement banner swapped to render the first item from the new `announcements` state (title + optional body). `shopConfig.announcements` is no longer read.
- Promotions banner rewritten to handle both `PERCENTAGE` and `FLAT_AMOUNT` discount types.
- `ProductCard` now accepts an optional `promo?: PublicPromotion` prop and overlays a `<Badge variant="destructive">` inside the existing `relative w-24 sm:w-28 flex-shrink-0` image container. Label is `{n}% off` for PERCENTAGE or `£{X.XX} off` for FLAT_AMOUNT.
- Both featured-section and category-section render loops pass the matching promo via `promotionsByCategory.get(...)`. No `.find()` anywhere near ProductCard.

### Cart tests (`frontend/__tests__/shop/cart.test.tsx`, NEW)

- Test 1: empty-cart state — asserts "Your basket is empty" copy and the back-to-menu link pointing at `/shop/jollof-express`.
- Test 2: populated-cart state — seeds `localStorage` BEFORE render (CartProvider hydrates in `useEffect`), asserts the item title ("Jollof Rice"), the £17.98 line total (2 × £8.99), and the proceed-to-checkout link pointing at `/shop/jollof-express/checkout`.
- Wraps `CartPage` in a `Suspense` boundary (the page uses `React.use(params)`) and passes a pre-resolved thenable so `use()` unwraps synchronously under jest-jsdom.
- `afterEach` clears `localStorage`.

## Commits

| # | Hash      | Subject                                                                                       |
|---|-----------|-----------------------------------------------------------------------------------------------|
| 1 | `cbbd609` | `feat(stfr): render promotions + announcements via dedicated public endpoints (STFR-03)`      |
| 2 | `bca8545` | `test(stfr): add Jest coverage for standalone cart page (STFR-04)`                            |

## Verification

- `npx tsc --noEmit` — clean on the two source files touched (`frontend/types/storefront.ts`, `frontend/app/shop/[slug]/page.tsx`). Pre-existing type errors in other dashboard tests are unrelated and out of scope.
- `npx jest --testPathPattern=__tests__/shop/cart` — 2/2 passing.
- `npm test -- --watchAll=false` — **71 passed / 71 total, 12 suites** (baseline was 69; +2 new from this plan, matches the +2 target).
- Grep: `promotionsByCategory` occurs exactly once as the `useMemo` definition, plus two consumer call sites in JSX. No `promotions.find(` anywhere. No `dangerouslySetInnerHTML` added.

```
Test Suites: 12 passed, 12 total
Tests:       71 passed, 71 total
Snapshots:   0 total
Time:        2.228 s
```

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking] React.use() suspended indefinitely in jest-jsdom**
- **Found during:** Task 3
- **Issue:** First test run hit `Unable to find an element with the text: Your basket is empty` — the rendered DOM showed only the Suspense `loading` fallback. `React.use()` on a freshly-created `Promise.resolve(...)` does not unwrap synchronously under jest-jsdom because microtasks aren't flushed between render and the first query poll.
- **Fix:** Wrote a `resolvedThenable()` helper that tags the promise with `status: "fulfilled"` and `value`, which React recognises as a pre-resolved thenable and unwraps synchronously on first render.
- **Files modified:** `frontend/__tests__/shop/cart.test.tsx`
- **Commit:** `bca8545`

**2. [Rule 1 — Minor] Unused import cleanup**
- **Found during:** Task 3
- **Issue:** Temporarily imported `waitFor` while debugging the suspend issue; became dead after the thenable fix.
- **Fix:** Removed from imports.
- **Commit:** `bca8545`

### Auth gates

None.

## Known Stubs / Follow-ups

- The legacy `/public/shops/{slug}/config` call stays in place and populates `shopConfig` for the `featuredProducts` list. This is intentional per plan Anti-Patterns note — a future plan can retire the `/config` endpoint once all consumers are migrated off it.
- `next lint` script is broken on the Next 16 upgrade (`Invalid project directory provided, no such directory: /frontend/lint`). Not introduced by this plan; logged as a pre-existing frontend tooling issue to investigate separately.

## Self-Check

- [x] `frontend/types/storefront.ts` — modified (contains `PublicPromotion` + `PublicAnnouncement`)
- [x] `frontend/app/shop/[slug]/page.tsx` — modified (contains `promotionsByCategory`, `/promotions`, `/announcements`, destructive Badge)
- [x] `frontend/__tests__/shop/cart.test.tsx` — created (2 passing tests)
- [x] Commit `cbbd609` exists on `feat/phase-10-storefront-marketing`
- [x] Commit `bca8545` exists on `feat/phase-10-storefront-marketing`

## Self-Check: PASSED
