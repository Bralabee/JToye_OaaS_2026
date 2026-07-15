---
phase: 19-full-frontend-experience-overhaul
plan: 08
subsystem: ui
tags: [tailwind, palette, accessibility, nextjs, customer-auth, jest, design-system]

# Dependency graph
requires:
  - phase: 19-04
    provides: mobile bottom tab bar + desktop-only sidebar (both carried avatar gradients swept here)
  - phase: 19-05
    provides: marketing surfaces re-skinned onto palette tokens + guest /track route
  - phase: 19-06
    provides: checkout fulfilment toggle + fee breakdown (text-scale residuals swept here)
  - phase: 19-07
    provides: KDS product-name/badge fixes (deliberately left purple for this sweep)
provides:
  - "Zero undocumented purple hue app-wide (PREPARING/VAT → amber, stat/gradient/avatar → blue)"
  - "Zero off-scale sub-12px arbitrary text tokens app-wide (text-[10px]/[9px]/[11px] → text-xs)"
  - "Quiet public console: /api/customer-auth/session returns 200 {authenticated:false} not 401 for anonymous visitors"
  - "Committed palette/type/IA discipline test locking the four grep gates against regression"
affects: [19-09, ui, design-system, storefront, kitchen, dashboard]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Static grep-gate jest test (execFileSync grep over app/+components) enforcing design-system discipline in CI"
    - "Anonymous session probe returns 200 with a boolean flag, not 401, so browsers don't log expected failed requests"

key-files:
  created:
    - frontend/__tests__/palette-discipline.test.ts
  modified:
    - frontend/app/api/customer-auth/session/route.ts
    - frontend/lib/customer-auth.ts
    - frontend/app/dashboard/page.tsx
    - frontend/app/dashboard/orders/page.tsx
    - frontend/app/dashboard/kitchen/page.tsx
    - frontend/app/dashboard/finance/page.tsx
    - frontend/app/dashboard/products/page.tsx
    - frontend/components/dashboard/orders/OrderDetailPanel.tsx
    - frontend/components/dashboard/sidebar.tsx
    - frontend/components/dashboard/mobile-tab-bar.tsx
    - frontend/app/shop/[slug]/page.tsx
    - frontend/app/shop/[slug]/cart/page.tsx
    - frontend/app/shop/[slug]/checkout/page.tsx
    - frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx
    - frontend/app/shop/orders/page.tsx
    - frontend/app/track/page.tsx
    - frontend/components/marketing/business-model-guide.tsx
    - frontend/components/marketing/operator-pitch.tsx
    - frontend/app/api/customer-auth/__tests__/route.test.ts

key-decisions:
  - "PREPARING + Finance VAT purple → amber (keeps PREPARING visually distinct from CONFIRMED blue / READY emerald); stat/gradient/avatar purple → blue"
  - "12px is the accessibility floor — all sub-12px arbitrary tokens (10/9/11px) collapse to text-xs; no sub-12px token introduced"
  - "Quiet the expected-401 probe by returning 200 {authenticated:false} (no profile); authoritative auth stays server-side (dashboard auth() + core RLS)"
  - "Discipline test greps the shipped trees so #10/#11/marketing-hex/IA can't silently regress"

patterns-established:
  - "Design-system grep gates as a committed jest suite (execFileSync grep, exit-1 = zero count)"
  - "Anonymous convenience probes signal state via 200 body flag, never a 4xx status"

requirements-completed: [UIX-06]

# Metrics
duration: ~22min
completed: 2026-07-11
---

# Phase 19 Plan 08: Palette / Type / Console Discipline Sweep Summary

**Removed the undocumented purple hue and every sub-12px text token app-wide, quieted the expected-401 customer-session probe (proven live 401→200), and locked all four gates behind a committed grep-gate jest test.**

## Performance

- **Duration:** ~22 min
- **Started:** 2026-07-11T11:16Z (approx)
- **Completed:** 2026-07-11T11:38Z
- **Tasks:** 3
- **Files modified:** 19 modified + 1 created (20 total)

## Accomplishments
- **Purple removed app-wide (#10):** 15 live occurrences (plan estimated 17; Wave 2 had shifted/reduced them) mapped per the UI-SPEC exhaustive list — `PREPARING` status + Finance VAT → amber (`bg-amber-500`, `text-amber-600/700`, chart `#f59e0b`); Products/Dashboard stat accents → blue; dashboard/orders gradient `to-purple-50` → `to-blue-100`; sidebar + mobile-tab-bar avatar gradients `to-purple-500` → `to-blue-600`. Both `#a855f7` chart fills swapped. `grep purple- == 0`.
- **Off-scale text killed (#11):** 35× `text-[10px]` + 2× `text-[9px]` + 3× `text-[11px]` → `text-xs`. No sub-12px arbitrary text token remains anywhere (`grep text-[<12px] == 0`).
- **Public console quieted (#13) — VERIFY-FIRST:** reproduced live — the probe returned **HTTP 401** for anonymous visitors, which the browser auto-logs on every public page view (storefront-nav mounts the probe). No `console.*` exists in the JS; the 401 status itself was the noise. Minimal fix: `/api/customer-auth/session` now returns **200 `{authenticated:false}`** (no profile) for the no-session/expired case. Verified live: before 401 → after 200.
- **Discipline test committed:** `palette-discipline.test.ts` asserts purple==0, sub-12px text==0, marketing-hex==0, `href="/track"`>=3.
- Full frontend suite green: **27 suites / 177 tests** (baseline 173 + 4 new gate tests); `npm run build` green.

## Task Commits

Each task was committed atomically:

1. **Task 1: Remove purple app-wide (exhaustive map → amber/blue)** - `cde877c` (style)
2. **Task 2: Replace text-[10px]/[9px]/[11px] with text-xs app-wide** - `48be762` (style)
3. **Task 3: Quiet the 401 probe (verify-first) + palette/type/IA discipline test** - `b56068b` (fix)

**Plan metadata:** committed with this SUMMARY (docs: complete plan)

## Files Created/Modified
- `frontend/__tests__/palette-discipline.test.ts` - **Created.** Grep-gate jest suite (purple, sub-12px text, marketing-hex, /track IA).
- `frontend/app/api/customer-auth/session/route.ts` - No-session/expired now returns 200 `{authenticated:false}` (was 401); documented the quiet-probe contract.
- `frontend/lib/customer-auth.ts` - `getCustomerSession()` keys off body `authenticated` flag; documented 200-not-401 contract; kept `!res.ok` as a defensive 5xx fallback.
- `frontend/app/api/customer-auth/__tests__/route.test.ts` - Two no-session cases assert 200 + `authenticated:false` + no profile leak.
- `frontend/app/dashboard/page.tsx` - PREPARING `bg-amber-500`/chart `#f59e0b`; Products stat → blue; VAT bar fill `#f59e0b`.
- `frontend/app/dashboard/orders/page.tsx` - PREPARING accent → amber; action button → amber; card gradient → blue.
- `frontend/app/dashboard/kitchen/page.tsx` - PREPARING badge + "Start Preparing" action → amber.
- `frontend/app/dashboard/finance/page.tsx` - Total VAT icon/value → amber.
- `frontend/app/dashboard/products/page.tsx` - Image fallback accent → blue; badge text → text-xs.
- `frontend/components/dashboard/orders/OrderDetailPanel.tsx` - PREPARING badge → amber.
- `frontend/components/dashboard/sidebar.tsx` - Avatar gradient → blue.
- `frontend/components/dashboard/mobile-tab-bar.tsx` - Avatar gradient → blue (Wave-2 residual, see Deviations).
- `frontend/app/shop/[slug]/page.tsx`, `.../cart/page.tsx`, `.../checkout/page.tsx`, `.../orders/[orderNumber]/page.tsx`, `frontend/app/shop/orders/page.tsx`, `frontend/app/track/page.tsx` - `text-[10px]`/`[9px]`/`[11px]` → `text-xs`.
- `frontend/components/marketing/business-model-guide.tsx`, `frontend/components/marketing/operator-pitch.tsx` - `text-[10px]`/`[11px]` → `text-xs`.

## Decisions Made
- Amber for `PREPARING`/VAT (not another hue) keeps the kitchen/orders status ramp readable against CONFIRMED (blue) and READY (emerald).
- The type gate in the discipline test was made a superset of the plan's `text-[10px]==0` — it catches the whole sub-12px arbitrary range (`text-[0..11px]`) — so the `text-[9px]`/`[11px]` residuals cleaned here cannot regress.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Swept purple avatar gradient in `mobile-tab-bar.tsx` (not in plan's file list)**
- **Found during:** Task 1
- **Issue:** The plan's `files_modified` list predated Wave 2. Wave 2 (19-04) added `components/dashboard/mobile-tab-bar.tsx` with an avatar gradient `from-blue-400 to-purple-500` — a 16th/17th purple occurrence outside the enumerated files. The app-wide `grep purple- == 0` gate could not be met without sweeping it.
- **Fix:** `to-purple-500` → `to-blue-600`, matching the identical sidebar avatar treatment.
- **Files modified:** frontend/components/dashboard/mobile-tab-bar.tsx
- **Verification:** `grep -rn 'purple-' app components | wc -l` == 0; build green; dashboard tests green.
- **Committed in:** cde877c (Task 1 commit)

**2. [Rule 2 - Missing Critical / a11y] Folded residual `text-[11px]` into `text-xs`**
- **Found during:** Task 2
- **Issue:** After the `text-[10px]`/`[9px]` sweep, 3× `text-[11px]` remained (`app/shop/orders/page.tsx` ×2, `components/marketing/operator-pitch.tsx` ×1). 11px is sub-12px and directly violates the objective's stated "12px accessibility floor. No sub-12px token", though it was not in the plan's explicit `text-[10px]` enumeration and passes the literal single-digit acceptance regex.
- **Fix:** `text-[11px]` → `text-xs`; both files are already in the plan's file list; strengthened the discipline test's type gate to catch the whole sub-12px range so it can't regress.
- **Files modified:** frontend/app/shop/orders/page.tsx, frontend/components/marketing/operator-pitch.tsx (+ test)
- **Verification:** `grep -rnoE 'text-\[([0-9]|1[01])px\]' app components | wc -l` == 0; build + full suite green.
- **Committed in:** 48be762 (Task 2 commit) + b56068b (test gate)

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 missing-critical/a11y)
**Impact on plan:** Both auto-fixes were required to satisfy the plan's own app-wide gates and stated objective (zero purple; no sub-12px token). No scope creep — no new features, no package installs.

## Issues Encountered
- **`text-violet-*` in `products/page.tsx` (out of scope):** the AI-suggestion badge uses `text-violet-500` / `bg-violet-600`. Violet is a distinct Tailwind hue and is NOT part of the plan's `purple-` gate or the UI-SPEC exhaustive purple map (the grep baseline of 17 was purple-only). Left untouched to stay within plan scope; noted here for a future palette pass if desired.
- **Live-evidence method:** the full docker stack was not needed. `next start` on the production build + `curl` reproduced the probe status directly (before: 401, after: 200) — deterministic runtime evidence of the exact status the browser would log.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- The four grep gates are now enforced by CI (`palette-discipline.test.ts`), giving 19-09 (closure/full-suite) a regression guard for palette/type/IA.
- One documented palette (orange/emerald/slate/blue/amber) and a single ≥12px type scale across the app.
- Concern (non-blocking): `text-violet-*` remains in the products AI-suggestion badge — out of this plan's scope but a candidate for a follow-up palette pass.

## Self-Check: PASSED
- FOUND: frontend/__tests__/palette-discipline.test.ts
- FOUND commits: cde877c, 48be762, b56068b

---
*Phase: 19-full-frontend-experience-overhaul*
*Completed: 2026-07-11*
