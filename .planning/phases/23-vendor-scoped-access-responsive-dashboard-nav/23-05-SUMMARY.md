---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 05
subsystem: ui
tags: [vendor-scoped-access, shop-switcher, localstorage, responsive-nav, mobile-375px, custom-event, radix, jest, playwright, next.js]

# Dependency graph
requires:
  - phase: 23-03-enforcement-sweep
    provides: "read-scoped GET /api/v1/shops (list already narrowed to the caller's grant set server-side) + typed shop-403 for a revoked shop"
  - phase: 23-02-enforcement-engine
    provides: "ShopAccessService grant model — GROUP_ADMIN / SHOP_MANAGER / STAFF; realm-admin ⇒ implicit GROUP_ADMIN"
  - phase: 19-frontend-overhaul
    provides: "Surface D responsive dashboard shell — `hidden md:flex` 256px sidebar + `md:hidden fixed` MobileTabBar (MOBL-01 already structurally satisfied)"
provides:
  - "VSA-03 (partial) — the persisted shop-context switcher chrome: a Radix dropdown mounted in the desktop sidebar logo header AND the mobile md:hidden top bar; GROUP_ADMIN lands on 'All shops' with the GA-only 'apply to all shops' action; non-GA sees only granted shops; single-grant pins a label (no dropdown)"
  - "lib/shop-context.ts — SSR-safe getShopContext()/setShopContext(id) over localStorage['shopContext'] (theme-toggle idiom, D-07) + the same-tab 'shopcontext:change' broadcast contract + subscribeShopContext(cb) that 23-07's useShopContext consumes to narrow the products/orders/marketing/kitchen screens live"
  - "lib/shops-api.ts — fetchMyShops() over the read-scoped GET /api/v1/shops (MyShops payload carries the granted set + GA status)"
  - "D-13 stale-selection safety — a revoked/absent persisted shopId degrades to the 'all' context (access-required, not a crash)"
  - "MOBL-01 CLOSED — 375px verify-first: switcher integrated width-capped (max-w-[55%] + truncate), no reintroduced overflow; asserted in Jest + Playwright + recorded in qa/surface-ledger.json with the live 375px DOM proof"
affects: [23-07-shop-context-wiring, 23-06-staff-screen]

# Tech tracking
tech-stack:
  added: []  # 100% composition of the existing Next.js/Radix/apiClient stack + the theme-toggle localStorage idiom
  patterns:
    - "SSR-safe localStorage helper (guard `typeof window`) mirroring the existing theme-toggle idiom — mount-time hydrate in a useEffect + setItem on change (react-hooks/set-state-in-effect eslint-disable copied verbatim)"
    - "Same-tab observability: setShopContext writes localStorage THEN dispatches a `window` CustomEvent('shopcontext:change') because the browser `storage` event only fires cross-tab; subscribeShopContext registers cb on BOTH the custom event AND `storage` and returns an unsubscribe"
    - "Read-scoped list as the switcher's ONLY source — the dropdown structurally cannot list ungranted shops (source is the 23-03-narrowed GET /api/v1/shops), so the client never invents a shop set"
    - "GA-gated group affordance rendered under a single compound guard (isGroupAdmin AND context=='all') so a non-GA never sees 'apply to all shops'"
    - "Width-capped chrome (max-w-[55%] + truncate) so a long shop name never reintroduces horizontal overflow at 375px"

key-files:
  created:
    - frontend/lib/shop-context.ts
    - frontend/lib/shops-api.ts
    - frontend/components/dashboard/shop-switcher.tsx
    - frontend/components/dashboard/__tests__/shop-switcher.test.tsx
  modified:
    - frontend/components/dashboard/sidebar.tsx
    - frontend/components/dashboard/dashboard-shell.tsx
    - frontend/components/dashboard/__tests__/dashboard-shell.test.tsx
    - frontend/e2e/dashboard-mobile.spec.ts
    - qa/surface-ledger.json

key-decisions:
  - "MOBL-01 was VERIFY-FIRST (satisfied-by-prior-work) — Phase 19 Surface D already shipped the responsive shell; this plan integrated the switcher without authoring a new drawer (drawer_authored:false), then recorded the 375px proof in the surface ledger rather than claiming a drawer build"
  - "The switcher's same-tab 'shopcontext:change' CustomEvent is the contract 23-07 consumes — the browser `storage` event alone would leave same-tab consumers deaf, so setShopContext broadcasts explicitly"
  - "VSA-03 left PENDING despite the switcher shipping — its second clause ('all shop-scoped screens operate on the selected shop') is satisfied only by 23-07; closing it here would be a false-green (mirrors the 23-01/22-01 anti-false-green discipline)"
  - "GA status is derived from the MyShops payload rather than a new session-role round-trip — keeps api-client.ts untouched (no new plumbing)"

patterns-established:
  - "Pattern: SSR-safe localStorage UI-preference helper + same-tab CustomEvent broadcast + subscribe/unsubscribe — reusable for any dashboard-wide client selection that consuming screens must react to live"
  - "Pattern: verify-first requirement closure — when prior work already satisfies a requirement, integrate + record the live proof in qa/surface-ledger.json (with drawer_authored:false), never re-build to manufacture a green"

requirements-completed: [MOBL-01]  # VSA-03 intentionally NOT included — closes in 23-07 (see Requirement Dispositions)

# Metrics
duration: ~35min
completed: 2026-07-19
---

# Phase 23 Plan 05: Vendor Shop-Context Switcher + MOBL-01 Verify-First Summary

**Persisted shop-context switcher (localStorage + same-tab `shopcontext:change` broadcast) mounted in the desktop sidebar and mobile top bar — GA-defaulted to "All shops" with a GA-only "apply to all shops" action — closing MOBL-01 verify-first at 375px without authoring a new drawer.**

## Performance

- **Duration:** ~35 min (build 8221f97→ca5653f 13:21–13:28 BST + live human-verify checkpoint + finalization)
- **Started:** 2026-07-19T12:21:25Z
- **Completed:** 2026-07-19T12:28:08Z (build); human-verify approved + finalized same day
- **Tasks:** 4 (3 auto + 1 blocking human-verify)
- **Files modified:** 9 (4 created, 5 modified)

## Accomplishments

- **Shop-context switcher (VSA-03 chrome):** `shop-switcher.tsx` — a Radix dropdown of the caller's granted shops with a first-class "All shops" entry for GROUP_ADMIN; hydrates the selection from `getShopContext()` on mount; a non-GA single-grant user gets a pinned label (no dropdown); the group-wide "apply to all shops" action renders only for GA in the "All shops" context.
- **Persisted, observable context helper:** `lib/shop-context.ts` — SSR-safe `getShopContext()`/`setShopContext(id)` over `localStorage['shopContext']` (theme-toggle idiom, D-07). `setShopContext` writes localStorage then dispatches a same-tab `window` CustomEvent `'shopcontext:change'`; `subscribeShopContext(cb)` registers on BOTH that event and the window `storage` event and returns an unsubscribe. **This is the exact contract 23-07's `useShopContext` consumes to narrow the products/orders/marketing/kitchen screens live.**
- **Read-scoped source:** `lib/shops-api.ts` `fetchMyShops()` over `GET /api/v1/shops` (already grant-narrowed server-side by 23-03) — the switcher structurally cannot list an ungranted shop.
- **D-13 stale-selection safety:** a revoked/absent persisted shopId degrades to the "all" context (access-required), not a crash.
- **Switcher mounted both surfaces:** desktop sidebar logo header (`sidebar.tsx`) + mobile `md:hidden` top bar (`dashboard-shell.tsx`), width-capped (`max-w-[55%]` + `truncate`).
- **MOBL-01 closed verify-first:** a 375px case added to `dashboard-mobile.spec.ts` (sidebar hidden, MobileTabBar + switcher visible, no horizontal overflow) + a 375px assertion in `dashboard-shell.test.tsx`; `qa/surface-ledger.json` records the requirement as satisfied-by-prior-work + switcher-integrated with the live 375px DOM proof and `drawer_authored:false`.

## Task Commits

Each task was committed atomically (Tasks 1–2 are TDD test→feat pairs):

1. **Task 1 (RED): failing shop-switcher spec** — `8221f97` (test) — persistence / broadcast+subscribe / GA-default / apply-to-all-visibility / single-grant-pin / stale-selection
2. **Task 1 (GREEN): shop-context switcher + localStorage persistence + read-scoped shop list** — `e872717` (feat) — `shop-context.ts` + `shops-api.ts` + `shop-switcher.tsx`; jest 9/9 green
3. **Task 2 (RED): failing 375px shell assertion for the mounted switcher** — `4767543` (test)
4. **Task 2 (GREEN): mount switcher (sidebar + mobile top bar) + 375px MOBL-01 regression** — `5951de6` (feat) — `npm run build` tsc clean; jest 32/32 green (incl. dashboard-shell 5/5)
5. **Task 3: record MOBL-01 in surface-ledger as verify-first satisfied + switcher-integrated** — `ca5653f` (docs) — valid JSON

**Plan metadata:** _this commit_ (docs: complete plan — SUMMARY + STATE + ROADMAP + REQUIREMENTS)

## Files Created/Modified

- `frontend/lib/shop-context.ts` (created) — SSR-safe get/set over `localStorage['shopContext']` + `shopcontext:change` broadcast + `subscribeShopContext`
- `frontend/lib/shops-api.ts` (created) — `fetchMyShops()` over the read-scoped `GET /api/v1/shops` (`MyShops` payload: granted set + GA status)
- `frontend/components/dashboard/shop-switcher.tsx` (created) — Radix dropdown; All-shops (GA) + apply-to-all (GA+all context); single-grant pin; D-13 stale degrade
- `frontend/components/dashboard/__tests__/shop-switcher.test.tsx` (created) — 9 Jest cases (persistence, broadcast/subscribe, GA-default, apply-to-all visibility, single-grant pin, stale-selection)
- `frontend/components/dashboard/sidebar.tsx` (modified) — mounts `<ShopSwitcher variant=sidebar/>` in the logo header
- `frontend/components/dashboard/dashboard-shell.tsx` (modified) — mounts `<ShopSwitcher variant=topbar/>` in the `md:hidden` top bar
- `frontend/components/dashboard/__tests__/dashboard-shell.test.tsx` (modified) — +375px switcher-in-mobile-topbar assertion (5 cases)
- `frontend/e2e/dashboard-mobile.spec.ts` (modified) — +375px describe: sidebar hidden, MobileTabBar + switcher visible, no horizontal overflow
- `qa/surface-ledger.json` (modified) — MOBL-01 entry: satisfied-by-prior-work + switcher-integrated, live 375px DOM proof, `drawer_authored:false`

## Verification Evidence

**Automated (all green at build):**
- `cd frontend && npm run build` — tsc typecheck clean (feedback_frontend_typecheck_gate honoured)
- `npx jest components/dashboard` — 32/32 green (shop-switcher 9/9 + dashboard-shell 5/5 + prior)
- `python3 -c "import json; json.load(open('qa/surface-ledger.json'))"` — valid JSON

**Task 4 — human-verify checkpoint (gate=blocking): APPROVED**

The orchestrator ran a live browser verification against the **rebuilt** frontend container (`localhost:3000`) with a real Keycloak login as `admin-user` (implicit GROUP_ADMIN). User response: **"approved"**.

- **Committed 375px MOBL-01 Playwright case PASSED** — desktop sidebar hidden, `MobileTabBar` visible, switcher visible, no horizontal overflow.
- **Desktop:** the switcher renders "All shops" under the J'Toye logo with the GA-only "Apply to all shops" action; `scrollW == clientW == 1366` (no overflow). Screenshot captured.
- **Mobile 375px:** the switcher is reachable in the top bar; `scrollW == innerW == 375` (no horizontal overflow); no content occlusion. Screenshot captured.
- **Persistence:** the live app writes `localStorage['shopContext']`; **D-13 stale-selection reset verified live** (an invalid persisted id reset to "all"); valid-id persistence proven by Jest 9/9.

## Requirement Dispositions

Following the 23-01..23-04 anti-false-green discipline:

- **MOBL-01 → COMPLETE.** 375px verified live (human-verify APPROVED) + Jest + Playwright regression guards + the surface-ledger proof (`drawer_authored:false`). Phase 19 Surface D already delivered the responsive shell; this plan integrated the switcher without reintroducing occlusion/overflow — the requirement's second listed plan (23-06) only adds the Staff nav item, not a nav change, so MOBL-01 closes here.
- **VSA-03 → PENDING (deliberately NOT closed).** VSA-03 has two clauses: (1) "the dashboard carries a persisted shop-context switcher … apply-to-all visible only to GROUP_ADMIN" — **shipped in this plan** — and (2) "all shop-scoped screens operate on the selected shop" — **satisfied only by 23-07** (the `useShopContext` wiring into products/orders/marketing/kitchen). Per the VALIDATION.md map, VSA-03's second traceability row is satisfied by 23-07, so it stays PENDING here and **closes in 23-07**. Marking it complete now would be a false-green (the consuming screens do not yet react to a switch).

## Decisions Made

- **MOBL-01 handled verify-first, not build-first** — integrated the switcher into the existing responsive shell and recorded the live proof rather than authoring a redundant drawer (`drawer_authored:false`).
- **Same-tab broadcast is explicit** — `setShopContext` dispatches `shopcontext:change` because the browser `storage` event fires only cross-tab; without it 23-07's same-tab consumers would never react.
- **GA status derived from the MyShops payload** — avoids a new session-role round-trip and keeps `api-client.ts` untouched.
- **VSA-03 left PENDING** — closes in 23-07 (see Requirement Dispositions).

## Deviations from Plan

None - plan executed exactly as written. (Tasks 1–2 followed the TDD RED→GREEN cycle; no auto-fix rules triggered; no packages installed — RESEARCH Package Legitimacy Audit N/A held.)

## Issues Encountered

None. The 375px overflow risk (T-23-05-04) was pre-empted by width-capping the switcher (`max-w-[55%]` + `truncate`) and proven clear by the Playwright case + the live human-verify.

## User Setup Required

None - no external service configuration required. (The switcher reads the caller's existing read-scoped shop list; localStorage is a per-device UI preference, not a trust boundary — the server re-validates every grant on every request, D-07.)

## Next Phase Readiness

- **23-07 (Wave 5) is unblocked:** `getShopContext()` / `subscribeShopContext()` / the `shopcontext:change` broadcast are the exact seam its `useShopContext` hook threads into products/orders/marketing/kitchen — this is where **VSA-03 closes**.
- **23-06 (Wave 6)** can add the GROUP_ADMIN-only Staff nav item to the same shell without touching the switcher.
- No blockers. MOBL-01 is closed; VSA-03 second clause is the only remaining switcher-adjacent work, owned by 23-07.

---
*Phase: 23-vendor-scoped-access-responsive-dashboard-nav*
*Completed: 2026-07-19*

## Self-Check: PASSED

All 4 created files present on disk (shop-context.ts, shops-api.ts, shop-switcher.tsx, shop-switcher.test.tsx) and all 5 task commits present in git history (8221f97, e872717, 4767543, 5951de6, ca5653f).
