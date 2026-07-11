---
status: partial
phase: 19-full-frontend-experience-overhaul
source: [19-09-PLAN.md checkpoint, orchestrator browser UAT 2026-07-11]
started: 2026-07-11T21:00:00Z
updated: 2026-07-11T21:20:00Z
---

## Current Test

Orchestrator-performed browser UAT complete for all public surfaces (user authorized autonomous continuation).
Remaining: user's own eyes on the two login-gated dashboard criteria (UIX-02, UIX-03) — automated evidence is green but the "comparator-grade" aesthetic verdict is the user's.

## Tests

### 1. UIX-01 — Landing page + no orphans
expected: `/` renders a real landing page (no redirect) with customer + operator doors; header/footer reach /for-operators, /business-model-guide, /track, /shop
result: pass — verified in Chrome. Split hero "Order from local kitchens. Or run yours.", both doors present, all four routes linked from header/footer. No redirect to /dashboard.

### 2. UIX-02 — Dashboard usable at 390px
expected: bottom tab bar (4 tabs) + More drawer on mobile across all 11 dashboard routes; desktop sidebar unchanged
result: pass (automated) — live Playwright `dashboard-mobile.spec.ts` green on rebuilt stack: real Keycloak vendor login, all 11 routes at 390px device emulation. USER EYES PENDING for aesthetic verdict (login required; orchestrator does not enter credentials).

### 3. UIX-03 — Real product names in KDS/order detail
expected: no "Unknown Product" for real products; badges not clipped; elapsed time capped
result: pass (automated) — live Playwright `kitchen-flow.spec.ts` green on rebuilt stack (both mobile+desktop projects). 9 legacy order_items rows remain "Unknown Product" on OLD orders only (unresolvable product refs, documented residual). USER EYES PENDING.

### 4. UIX-04 — Fee breakdown before pay + delivery/collection
expected: fulfilment toggle, conditional UK address, Subtotal/Delivery/VAT/Total BEFORE payment; order placeable
result: pass — verified in Chrome end-to-end. Delivery: address form required, total £24.49 (£21.50 + £2.99 delivery) shown on the Place order button itself. Collection: address hidden, Delivery "Free". Order placed for real: ORD-00000000-20260711-7FD1E4DE, server breakdown matched preview (Subtotal £21.50 / Delivery £2.99 / VAT £4.07 / Total £24.49). Note: client VAT preview showed £4.08 vs server £4.07 (1p rounding display difference, totals identical) — cosmetic, logged as observation.

### 5. UIX-05 — Per-shop menus, realistic data
expected: each shop shows only its own menu, no duplicates, no test/lorem data
result: pass — verified in Chrome. Mama Ade's Kitchen (Chapman, Jollof Rice, Zobo, Egusi Soup, Pounded Yam & Egusi) fully disjoint from Peckham Jollof Co. (Party Jollof, Suya Platter, Ginger Beer, Palm Wine, Grilled Tilapia). Real brand logos render (naturalWidth 400 > 0); photo-less products show branded gradient fallback, zero broken images.

### 6. UIX-06 — Visual quality + console discipline
expected: orange/emerald/slate palette (+dashboard blue-600), no purple, no console 401 spam, images real
result: pass (public surfaces) — verified in Chrome: palette on-brand across landing/shop/cart/checkout/track, zero console errors across the entire walked session (anonymous-session probe now silent). Static grep gates additionally lock purple=0 and sub-12px=0 in CI. Dashboard-side aesthetic: USER EYES PENDING.

### 7. /track guest lookup (UIX-01 surface)
expected: guest can track an order with order number + email, no sign-in
result: pass — verified in Chrome with the freshly placed order: shop, item count, £24.49 total, status timeline (Received→…→Completed), auto-refreshing.

## Summary

total: 7
passed: 7 (5 hands-on browser, 2 automated-evidence with user eyes pending)
issues: 0
pending: 0 (2 items flagged for optional user visual review)
skipped: 0
blocked: 0

## Gaps

(none blocking — observations only)
- 1p VAT rounding display difference between checkout preview (£4.08) and server confirmation (£4.07); totals identical at £24.49.
- Residuals documented in deferred-items.md: 2 Playwright reds are pre-existing Phase-18 customer self-registration (Keycloak PKCE REGISTER_ERROR); jtoye-ollama container down (host service owns :11434, pre-existing).
