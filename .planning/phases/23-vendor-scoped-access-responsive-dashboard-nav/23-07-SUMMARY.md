---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 07
subsystem: ui
tags: [vendor-scoped-access, shop-context, useShopContext, products, orders, marketing, kitchen-kds, custom-event, d-08, jest, next.js]

# Dependency graph
requires:
  - phase: 23-05-shop-context-switcher
    provides: "lib/shop-context.ts — getShopContext()/setShopContext(id)/subscribeShopContext(cb) + the same-tab 'shopcontext:change' broadcast; the mounted switcher chrome (sidebar + mobile top bar)"
  - phase: 23-03-enforcement-sweep
    provides: "server-side read scoping — every list already narrowed to the caller's grant set; GET /api/v1/orders?shopId= gated by require(shopId, STAFF) via OrderService.getOrdersByShop; writes re-validated by require(shopId, SHOP_MANAGER)"
  - phase: 23-02-enforcement-engine
    provides: "ShopAccessService grant model — GROUP_ADMIN / SHOP_MANAGER / STAFF"
provides:
  - "VSA-03 CLOSED — the switcher selection now drives all four shop-scoped screens: Products, Orders, Marketing (promotions + announcements) and the Kitchen board narrow to the selected shop and react LIVE to a switcher change (no reload)"
  - "hooks/use-shop-context.ts — useShopContext() → { contextShopId, isAllShops }; SSR-safe mount hydration + subscribeShopContext re-read. The reusable seam any future shop-scoped screen consumes"
  - "D-08 concrete home — product / order / promotion / announcement create-forms default AND constrain their shop to the selected shop outside the All-shops context (single-shop context ⇒ single-shop writes)"
  - "Kitchen single source of truth — the KDS board's shop derives from the global switcher instead of a blind first-published default; the local Select stays for on-board convenience"
affects: [23-06-staff-screen, future shop-scoped dashboard screens]

# Tech tracking
tech-stack:
  added: []  # zero new packages — pure composition of the existing hooks/Next.js stack (RESEARCH Package Legitimacy Audit N/A held)
  patterns:
    - "Context-consuming hook over a localStorage seam: state starts at the 'all' sentinel and hydrates in a mount effect (never straight from getShopContext()) so SSR markup and the first client render agree; the same effect's subscribeShopContext return value IS the cleanup"
    - "Narrow at the layer that supports it: Orders narrows SERVER-side via the gated ?shopId= param; Products/Promotions/Announcements (no shop param) narrow client-side over the already grant-scoped page — the client only hides within the authorised set, never widens it"
    - "All-shops as a strict fall-through: contextShopId === null means 'no narrow', so every consuming screen's All-shops path is byte-for-byte today's behaviour (Incremental Betterment — additive, zero regression by omission)"
    - "Derived-selection reconcile effect instead of an async-callback default: the KDS board shop is derived from [shops, contextShopId, selectedShopId] rather than picked inside the mount-only fetch, avoiding a race between the fetch promise and the hook's hydration"
    - "Constrain-by-render, not by disable-alone: a pinned select renders ONLY the context shop's option (the 'All Shops' / 'Select a shop' escape hatch is not rendered at all), so the constraint holds even if the disabled attribute is bypassed"

key-files:
  created:
    - frontend/hooks/use-shop-context.ts
    - frontend/hooks/__tests__/use-shop-context.test.tsx
    - frontend/app/dashboard/__tests__/products-orders-shop-scope.test.tsx
    - frontend/app/dashboard/__tests__/marketing-kitchen-shop-scope.test.tsx
  modified:
    - frontend/app/dashboard/products/page.tsx
    - frontend/app/dashboard/orders/page.tsx
    - frontend/app/dashboard/marketing/page.tsx
    - frontend/app/dashboard/kitchen/page.tsx
    - .planning/phases/23-vendor-scoped-access-responsive-dashboard-nav/deferred-items.md

key-decisions:
  - "The hook hydrates in a mount effect rather than initialising state from getShopContext() (as the plan's action text literally said) — a useState initialiser runs on BOTH the server ('all') and the client (the real localStorage value), which is a hydration mismatch. Mirrors the proven shop-switcher.tsx / sidebar theme idiom (documented as a Rule 2 deviation below)"
  - "Orders narrows server-side (?shopId=) but the customer-scoped branch (/orders/customer/{id}, which takes no shop param) narrows its rendered rows client-side — so both entry points honour the same context instead of one silently ignoring it"
  - "Kitchen's fetchShops no longer selects a shop at all; a reconcile effect owns the choice. This is what makes 'single source of truth' true rather than 'two defaults that usually agree'"
  - "Pinned create-form selects render only the context shop's option (not merely disabled) so the D-08 single-shop-write constraint is structural in the markup"
  - "docs/metrics.json NOT reconciled here — 23-06 is still pending and would re-drift it; the phase-gate --write stays at the last plan per the 23-01 deferred-items entry"

patterns-established:
  - "Pattern: useShopContext() is the single consumption point for the shop switcher — any future shop-scoped screen imports the hook and treats contextShopId === null as 'no narrow', instead of re-reading localStorage"
  - "Pattern: prove the All-shops fall-through in the SAME spec as the narrow. Every narrowing case has a paired All-shops case that was green BEFORE the implementation landed — the RED run's green half is the zero-regression proof"

requirements-completed: [VSA-03]

# Metrics
duration: ~40min
completed: 2026-07-20
---

# Phase 23 Plan 07: Shop-Context Wiring — VSA-03 Closure Summary

**`useShopContext()` threads the persisted switcher selection (23-05) into Products, Orders, Marketing and the Kitchen board so they narrow to the selected shop and react live to a `shopcontext:change` broadcast — closing VSA-03's second clause with the All-shops context preserved as a strict, zero-regression fall-through.**

## Performance

- **Duration:** ~40 min
- **Tasks:** 2 (both TDD RED→GREEN pairs)
- **Files modified:** 9 (4 created, 5 modified)

## Accomplishments

- **`hooks/use-shop-context.ts` (the seam):** `useShopContext()` returns `{ contextShopId, isAllShops }`, hydrating from `getShopContext()` on mount and re-reading on every `subscribeShopContext` broadcast (same-tab `shopcontext:change` + cross-tab `storage`). `"all"` maps to `contextShopId: null`, which every consumer reads as "no narrow".
- **Products:** a derived `visibleProducts` narrows the already grant-scoped page to the context shop and drives the table, the count label and the empty state (which now says "No products in this shop" with a switch-context hint instead of the misleading "No products yet"). The create-form's Shop Assignment defaults to and is pinned to the context shop.
- **Orders:** the collection list narrows **server-side** via `&shopId=${contextShopId}` (`OrderService.getOrdersByShop`, gated by 23-03), with `contextShopId` in the fetch effect deps so a switch refetches live. The customer-scoped branch — whose endpoint takes no shop param — narrows its rendered rows instead, so both entry points honour the context. The create dialog's shop `Select` defaults to and is constrained to the context shop.
- **Marketing:** `filteredPromotions` / `filteredAnnouncements` gained an `inShopContext` predicate composed with the existing status filters; both count labels follow what is actually on screen. `openCreatePromo` / `openCreateAnnouncement` default `shopId` to the context shop and both Shop selects are pinned. **Edit flows deliberately untouched** — an edit keeps the entity's own shop.
- **Kitchen (single source of truth):** `fetchShops` no longer picks the board shop. A reconcile effect derives it from `[shops, contextShopId, selectedShopId]`: a specific context always wins (switching in the sidebar moves the board), All-shops keeps any manual on-board pick and falls back to the published-first default (QA-council FIX-4 behaviour preserved), and a context shop that isn't selectable degrades to that fallback rather than crashing (D-13).
- **Zero regression by omission:** every narrowing case has a paired All-shops case. Those paired cases were **already green in the RED run** — direct evidence that the All-shops path is unchanged.

## Task Commits

| Task | Gate | Commit | Description |
|------|------|--------|-------------|
| 1 | RED | `8b8e465` | failing hook + Products/Orders narrowing specs |
| 1 | GREEN | `e1a1a7e` | `useShopContext` + Products/Orders wiring |
| 2 | RED | `3d148aa` | failing Marketing narrowing + Kitchen board specs |
| 2 | GREEN | `1491a7d` | Marketing wiring + Kitchen reconcile |

**Plan metadata:** _this commit_ (SUMMARY + STATE + ROADMAP + REQUIREMENTS + deferred-items)

## Verification Evidence

**Ran (all green):**

- `npx jest hooks/__tests__/use-shop-context.test.tsx app/dashboard/__tests__/products-orders-shop-scope.test.tsx` — **10/10**
- `npx jest app/dashboard/__tests__/marketing-kitchen-shop-scope.test.tsx` — **6/6**
- `npx jest hooks/__tests__/use-shop-context.test.tsx app/dashboard/__tests__ components/dashboard` — **58/58 across 8 suites** (regression sweep incl. the 23-05 shop-switcher 9/9 and dashboard-shell 5/5 — the switcher this plan consumes still passes)
- `npm run build` — **exit 0**, tsc typecheck clean (`feedback_frontend_typecheck_gate`: jest does not type-check)

**RED-gate evidence (the proof the tests are real):** Task 1's RED run was `3 failed, 3 passed` and Task 2's was `3 failed, 3 passed` — the failures were exactly the narrowing/default cases, the passes exactly the All-shops fall-through cases. No test passed before its implementation landed.

**Acceptance greps:**

| Check | Result |
|-------|--------|
| `grep -c useShopContext` products / orders / marketing / kitchen | 2 / 2 / 2 / 2 |
| `grep -c subscribeShopContext hooks/use-shop-context.ts` | 2 |
| `grep -c "shopId=" app/dashboard/orders/page.tsx` (was 0) | 3 |
| `grep -c contextShopId app/dashboard/kitchen/page.tsx` | 4 |

## Deferred verification (low-footprint mode)

The user's desktop crashed during earlier heavy work, so this plan ran under an explicit low-footprint constraint: Jest + `npm run build` only, run serially. **The following were NOT run and are NOT claimed as passing:**

- **Live browser verification of the switch (Playwright / Chromium).** No e2e spec was run. The live behaviour that remains unproven-in-browser: switching shop in the sidebar visibly narrowing each of the four screens without a reload, and the pinned create-form selects rendering correctly against real data. The Jest cases assert the same behaviour at component level (including the live re-read via the captured broadcast callback), but they mock `apiClient`, so **no real network round-trip to the gated `?shopId=` endpoint was exercised**.
- **Docker rebuild + full-stack E2E** (`scripts/start-dev.sh`, `docker compose`) — forbidden this session.
- **Java/Gradle + Testcontainers suites** — untouched by this plan (frontend-only change), and forbidden this session.

Suggested follow-up when a full-resource session is available: `npx playwright test e2e/dashboard-mobile.spec.ts` plus a manual GROUP_ADMIN switch across the four screens against a rebuilt frontend container.

## Requirement Dispositions

- **VSA-03 → COMPLETE.** Both clauses are now satisfied: (1) the persisted switcher with the GROUP_ADMIN-only "apply to all shops" action shipped in 23-05, and (2) "all shop-scoped screens operate on the selected shop" ships here — Products, Orders, Marketing and Kitchen all consume `useShopContext()` and narrow, with live reaction to the broadcast. The traceability row (`23-05, 23-07`) is fully covered.
  - **Honest scope note:** closure is proven at Jest + tsc level, not in a live browser (see Deferred verification). This is a deliberate, recorded limitation of the low-footprint session — not a claim of browser-verified behaviour. 23-05's clause-1 half WAS live-verified (human-verify APPROVED).

## Deviations from Plan

### Auto-fixed / adjusted

**1. [Rule 2 — correctness] Hook hydrates in a mount effect instead of a `useState(getShopContext())` initialiser**

- **Found during:** Task 1
- **Issue:** The plan's action text said the hook "holds the raw value in state initialised from `getShopContext()`". A `useState` initialiser runs on the server (returns `"all"`, no `window`) *and* again on the client during hydration (returns the real localStorage value) — a classic hydration mismatch on every dashboard screen for any user whose context isn't "all".
- **Fix:** State starts at `ALL_SHOPS_CONTEXT` and hydrates in the mount effect that also subscribes — exactly the idiom 23-05's `shop-switcher.tsx` and the sidebar theme toggle already use (`react-hooks/set-state-in-effect` eslint-disable copied verbatim).
- **Why it's still plan-compliant:** the plan itself flagged the SSR concern ("Guard SSR … initial state may be 'all' on the server"), and the observable contract (mapping + live reaction + unsubscribe) is unchanged and asserted.
- **Files:** `frontend/hooks/use-shop-context.ts` — **Commit:** `e1a1a7e`

**2. [Rule 2 — consistency] Orders' customer-scoped branch narrows client-side**

- **Found during:** Task 1
- **Issue:** The plan specified `?shopId=` on the orders fetch. `/orders/customer/{id}` takes no shop param, so with a customer deep-link active the page would have silently ignored the shop context — a screen visibly disobeying the switcher.
- **Fix:** Added `visibleOrders`, which narrows the rendered rows only on that branch (the collection branch stays server-narrowed). Sending an unsupported param was deliberately avoided.
- **Files:** `frontend/app/dashboard/orders/page.tsx` — **Commit:** `e1a1a7e`

**3. [Rule 1 — race] Kitchen selection moved out of the async fetch callback**

- **Found during:** Task 2
- **Issue:** The plan said to prefer `contextShopId` "in the `fetchShops` success path". That mount-only effect races the hook's own mount-effect hydration — the fetch callback could read a not-yet-hydrated `null` context and board the wrong shop (and fire a wasted orders request for it).
- **Fix:** `fetchShops` only sets `shops`; a separate reconcile effect derives the board shop from `[shops, contextShopId, selectedShopId]`. Deterministic, and it also delivers the plan's "a switcher change updates the board" requirement in the same effect.
- **Verification that it mattered:** the spec asserts the board **never** requests the non-context shop (`every(url => !url.includes(shopId=SHOP_A))`), which the racy version would have flunked intermittently.
- **Files:** `frontend/app/dashboard/kitchen/page.tsx` — **Commit:** `1491a7d`

**4. [scope-boundary log, not a fix] `docs/metrics.json` drift**

- Measured read-only at close: recorded 1456 vs computed **1504** (`jest_blocks` 324→350, `jest_files` 51→55, `java_test_methods` 989→1010, `java_test_files` 170→175, `java_controllers` 20→21, `playwright_blocks` 39→40). 16 Jest blocks / 3 files of that are this plan's; the rest is 23-01..23-05.
- **Not written here** — 23-06 is still pending and will move the numbers again. Deferred to the phase's last plan per the existing 23-01 `deferred-items.md` entry (Phase 22 precedent). Logged in `deferred-items.md` under `## 23-07` with the exact figures so the reconcile is a lookup, not a re-derivation.
- **Consequence if forgotten:** the `docs-freshness` CI gate fails the phase PR. It is recorded in two places (deferred-items + this SUMMARY) precisely so it isn't.

## Incremental Betterment — displaced goods accounted for

Per the CLAUDE.md doctrine, the goods this plan's rework could have displaced, and their disposition:

| Existing good | Disposition |
|---------------|-------------|
| Cross-shop Products/Orders/Marketing lists (GROUP_ADMIN whole-group view) | **Preserved** — `contextShopId === null` is a strict fall-through; asserted by paired All-shops cases that were green pre-implementation |
| Products create-form "All Shops" (unassigned) option | **Preserved in the All-shops context**; hidden only in a single-shop context, where assigning to "All Shops" would contradict D-08 |
| Kitchen QA-council FIX-4 published-first default (never boards a draft/junk shop) | **Preserved** — still the fallback whenever the context doesn't name a selectable shop; `shops` still holds the published-filtered `selectable` list |
| Kitchen local on-board Select (fast switching without leaving the KDS) | **Preserved** — it still switches the board; it just no longer owns an independent default |
| Marketing edit flows keeping the entity's own shop | **Preserved** — only the *create* opens were touched |
| Server pagination on Products | **Preserved** — the Pagination component and server paging are untouched; the narrow applies to the current page's rows (see Known Limitation) |

## Known Limitation (not a stub — a recorded design boundary)

Products / Promotions / Announcements narrow **client-side over the current server page**, because those list endpoints take no shop param. In a single-shop context a page of 20 rows spanning several shops shows fewer than 20 rows, and the server pager still counts unnarrowed pages. This is the minimal slice the plan specified ("acceptable for this minimal slice") and is **cosmetic only** — no row outside the caller's grant set can ever appear (23-03 scopes the query server-side; RLS scopes the tenant). The clean fix is a `shopId` param on those three list endpoints, which is a backend change outside this plan's no-new-endpoints boundary.

## Security Note

The client-side narrow is **not** a security boundary and is not presented as one — the code comments say so explicitly at each filter site. Reads are already grant-scoped by 23-03 and tenant-scoped by RLS; the filter only hides rows *within* the already-authorised set and can never widen it. The pinned create-form shop is a UX default: the server re-validates every write via `require(shopId, SHOP_MANAGER)`, so a tampered `shopId` yields the typed shop-access 403. This matches the plan's threat register (T-23-07-01 / T-23-07-02); T-23-07-03 (switch fails to propagate) is mitigated and asserted by the hook's live-reaction case.

**Threat flags:** none — this plan adds no endpoint, no auth path, no file access and no schema change. `T-23-07-SC` (package tampering) holds: **zero packages installed**.

## Issues Encountered

None blocking. The three deviations above were caught during implementation (two hydration/race hazards and one silently-ignored-context branch) and fixed inline before their commits.

## User Setup Required

None — no external service configuration. The context is a per-device localStorage UI preference; the server re-validates every grant on every request.

## Next Phase Readiness

- **VSA-03 is closed**; the only remaining Phase 23 plan is **23-06** (GROUP_ADMIN-only Staff nav item + screen), which is independent of this wiring.
- **23-06 owns the phase-gate reconcile:** `scripts/docs-freshness.sh --write` + the CLAUDE.md prose counts, per `deferred-items.md`.
- **For future shop-scoped screens:** import `useShopContext()` and treat `contextShopId === null` as "no narrow" — do not re-read localStorage directly.
- Recommended before the phase PR: the live browser pass listed under **Deferred verification**.

---
*Phase: 23-vendor-scoped-access-responsive-dashboard-nav*
*Completed: 2026-07-20*

## Self-Check: PASSED

All 4 created files present on disk (`hooks/use-shop-context.ts` + the 3 spec files) and all 4 task commits present in git history (`8b8e465`, `e1a1a7e`, `3d148aa`, `1491a7d`).
