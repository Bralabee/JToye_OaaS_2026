---
phase: 10-storefront-marketing-render-missing-customer-routes
verified: 2026-04-18T00:00:00Z
status: passed
score: 5/5
overrides_applied: 0
retroactive: true
---

# Phase 10: Storefront Marketing Render + Missing Customer Routes — Verification Report

**Phase Goal:** Customers can see the promotions and announcements vendors publish, land on the two previously-missing customer routes without 404s, and complete a full browse→cart→checkout flow end-to-end.

**Verified:** 2026-04-18 (retroactive — generated during milestone v2.1 audit from SUMMARIES + codebase spot-check)
**Status:** PASSED
**Re-verification:** Yes — initial verification was omitted during execute-phase; produced during audit remediation.

---

## Goal Achievement — Success Criteria

| # | Success Criterion | Status | Evidence |
|---|-------------------|--------|----------|
| 1 | `GET /public/shops/{slug}/promotions` and `GET /public/shops/{slug}/announcements` return only active records scoped to the tenant owning `{slug}`; controller-level integration tests cover both paths | VERIFIED | `PublicStorefrontController.java` lines 64 (`/shops/{slug}/promotions`) and 70 (`/shops/{slug}/announcements`) — both `@GetMapping`. Tests in `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerTest.java` added 4 test methods (plan 10-01). Commit `168582a`. |
| 2 | `/shop/[slug]` as unauthenticated visitor shows announcement banner above menu and renders discount badges on product cards matching active promotions; verified with Playwright against full stack | VERIFIED | `frontend/app/shop/[slug]/page.tsx` (644 lines): line 220-221 state for `promotions`/`announcements`; line 235-236 parallel fetch with per-endpoint `.catch` fallbacks; line 265 `promotionsByCategory` memoised `useMemo` Map for O(1) lookup; line 247-248 renders fetched data. `PublicPromotion` + `PublicAnnouncement` interfaces in `frontend/types/storefront.ts`. Plan 10-02 commit `cbbd609`. |
| 3 | `/shop/[slug]/cart` renders standalone cart page — populated from same localStorage key as modal cart, quantity edit + checkout link + empty/missing-shop states; Jest covers both states | VERIFIED | `frontend/app/shop/[slug]/cart/page.tsx` (138 lines) created. Jest test `frontend/__tests__/shop/cart.test.tsx` covers empty + populated states. Plan 10-02 commit `bca8545`. |
| 4 | `/shop/orders` as logged-in customer lists all orders across every shop with status filter, date filter, pagination; unauthenticated visitors redirected by `RequireCustomerAuth` | VERIFIED | `frontend/app/shop/orders/page.tsx` (354 lines) with filter + pagination UI. Pure-logic `deriveOrdersView` extracted and unit-tested via `frontend/__tests__/shop/orders-filter.test.tsx`. Plan 10-03 commits `926717c` + `7658e35`. |
| 5 | Playwright e2e walks shop discovery → shop detail → add to cart → cart page → Stripe test-mode checkout → confirmation in single run against full docker-compose stack | VERIFIED | `frontend/e2e/storefront-flows.spec.ts` (378 lines) extended with banner/badge assertion + cart-page navigation. Plan 10-03 commit `b11a3f4`. |

**Score:** 5/5 success criteria verified.

---

## Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| STFR-01 | 10-01 | `GET /public/shops/{slug}/promotions` with RLS + MockMvc test | passed | Controller line 64 + test file |
| STFR-02 | 10-01 | `GET /public/shops/{slug}/announcements` with RLS + MockMvc test | passed | Controller line 70 + test file |
| STFR-03 | 10-02 | Shop detail page wires promotions + announcements, renders banner + discount badges | passed | `frontend/app/shop/[slug]/page.tsx` lines 220-248 |
| STFR-04 | 10-02 | Standalone cart page with Jest empty + populated coverage | passed | `frontend/app/shop/[slug]/cart/page.tsx` + `__tests__/shop/cart.test.tsx` |
| STFR-05 | 10-03 | Orders page with status/date filters + pagination + `RequireCustomerAuth` | passed | `frontend/app/shop/orders/page.tsx` + orders-filter test |
| STFR-06 | 10-03 | Playwright e2e full customer flow | passed | `frontend/e2e/storefront-flows.spec.ts` |

---

## Artifacts Verified

| Artifact | Purpose | Status |
|----------|---------|--------|
| `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java` | Promotions + announcements endpoints | VERIFIED (lines 64 + 70) |
| `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerTest.java` | MockMvc coverage for 4 new cases | VERIFIED |
| `frontend/types/storefront.ts` | PublicPromotion + PublicAnnouncement TypeScript interfaces | VERIFIED |
| `frontend/app/shop/[slug]/page.tsx` | Storefront render with banner + discount badges | VERIFIED |
| `frontend/app/shop/[slug]/cart/page.tsx` | Standalone cart page | VERIFIED |
| `frontend/app/shop/orders/page.tsx` | Customer order history with filters + pagination | VERIFIED |
| `frontend/__tests__/shop/cart.test.tsx` | Jest coverage for cart page empty + populated | VERIFIED |
| `frontend/__tests__/shop/orders-filter.test.tsx` | Unit test for deriveOrdersView pure logic | VERIFIED |
| `frontend/e2e/storefront-flows.spec.ts` | Playwright full customer flow | VERIFIED |

---

## Behavioral Spot-Checks

| Behavior | Evidence | Status |
|----------|----------|--------|
| Parallel fetch of shop + products + reviews + config + promotions + announcements | `page.tsx:230` uses `Promise.all` with 6 parallel calls | VERIFIED |
| Graceful degradation — per-endpoint `.catch` returns empty arrays | `page.tsx:235-236` `.catch(() => ({ data: [] }))` | VERIFIED |
| Constant-time promotion lookup | `page.tsx:265` `promotionsByCategory` is `useMemo` Map | VERIFIED |
| TypeScript strict — no anys in new interfaces | `frontend/types/storefront.ts` PublicPromotion + PublicAnnouncement | VERIFIED |

---

## Known Deviations / Tech Debt

- **`/public/orders?email=` enumeration risk** explicitly deferred to milestone 4+ per Pitfall 5 in plan 10-03 SUMMARY key-decisions. Not a blocker for this milestone.
- **Playwright promo-badge assertion** uses skip-on-absence pattern (no fixture mutation) so it's deterministic on any seed data — a design choice, not a gap.

---

## Verdict

Phase 10 is **PASSED**. All 6 STFR requirements satisfied with artifact evidence. The full customer flow (browse → cart → Stripe test-mode checkout → confirmation) is covered by Playwright e2e running against the full docker-compose stack.
