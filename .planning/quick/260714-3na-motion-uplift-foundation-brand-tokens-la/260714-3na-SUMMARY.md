---
phase: quick-260714-3na
plan: 01
subsystem: ui
tags: [framer-motion, lazymotion, tailwind, shadcn, recharts, jest, brand-tokens]

# Dependency graph
requires:
  - phase: 19-full-frontend-experience-overhaul
    provides: storefront/dashboard surfaces, palette-discipline gates (12px floor), cart provider + FloatingCartBar
provides:
  - Flame-orange brand primary/ring in shadcn CSS vars (:root + .dark) + --trust/--ember-bright/--shadow-glow-ember/shimmer keyframe
  - MotionProvider (LazyMotion strict, async domMax) + MotionConfig reducedMotion=user wrapping the app; all 10 dashboard `motion` imports converted to `m`
  - lib/motion.ts shared variants (fadeInUp/staggerContainer/staggerItem, springPop, durations); ui/skeleton.tsx shimmer primitive
  - jtoye:cart-updated CustomEvent bus + useCartCount hook + nav basket badge on /shop/[slug]
  - AnimatePresence FloatingCartBar (spring in/out, safe-area), KDS popLayout FLIP + one-shot ember glow, useCountUp KPI count-ups, CHART_COLORS de-blued charts, animated tracking steppers
  - jest.setup.js global framer-motion mock covering m/AnimatePresence/LazyMotion/MotionConfig/animate/useReducedMotion + next/navigation useParams
affects: [storefront-theme-implementation, future motion phases, any frontend page using framer-motion]

# Tech tracking
tech-stack:
  added: []  # zero new runtime dependencies (D-01) — framer-motion@12 already present
  patterns:
    - "m. components only under LazyMotion strict — full `motion.` import banned app-wide"
    - "Shared motion vocabulary from @/lib/motion (springPop, staggerContainer/staggerItem/fadeInUp)"
    - "Brand chart hexes from @/lib/chart-colors, never raw #3b82f6 literals"
    - "jtoye:cart-updated CustomEvent as the same-document cart-count bus"

key-files:
  created:
    - frontend/lib/motion-features.ts
    - frontend/components/motion-provider.tsx
    - frontend/lib/motion.ts
    - frontend/components/ui/skeleton.tsx
    - frontend/hooks/use-cart-count.ts
    - frontend/hooks/use-count-up.ts
    - frontend/lib/chart-colors.ts
    - frontend/hooks/__tests__/use-cart-count.test.tsx
    - frontend/hooks/__tests__/use-count-up.test.tsx
    - frontend/components/ui/__tests__/skeleton.test.tsx
    - frontend/components/storefront/__tests__/storefront-nav-badge.test.tsx
    - frontend/components/__tests__/motion-provider.test.tsx
  modified:
    - frontend/app/globals.css
    - frontend/app/layout.tsx
    - frontend/app/dashboard/page.tsx
    - frontend/app/dashboard/kitchen/page.tsx
    - frontend/components/storefront/cart-provider.tsx
    - frontend/components/storefront/storefront-nav.tsx
    - frontend/components/storefront/product-detail-modal.tsx
    - frontend/app/shop/[slug]/page.tsx
    - frontend/app/track/page.tsx
    - frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx
    - frontend/jest.setup.js
    - docs/metrics.json
    - CLAUDE.md

key-decisions:
  - "Safe-area padding uses max(existing, env(safe-area-inset-bottom)) instead of the literal pb-[env(...)] so non-notch devices keep the bar's 12/16px bottom padding"
  - "KDS glow seen-set reseeds on shop switch so changing shops never glows an entire carried-over board"
  - "jest.setup.js framer-motion mock extended globally (m proxy + presence/config + immediate-jump animate) — real library re-mocked per-file where LazyMotion strict must be exercised"

patterns-established:
  - "LazyMotion strict + async domMax: new motion code imports { m } and shared presets, never { motion }"
  - "Reduced-motion handled centrally by MotionConfig reducedMotion=user; hooks using standalone animate() must call useReducedMotion themselves (useCountUp precedent)"

requirements-completed: [QUICK-260714-3NA]

# Metrics
duration: 20min
completed: 2026-07-14
---

# Quick Task 260714-3na: Motion Uplift Foundation Summary

**Flame-orange brand tokens in the shadcn vars, LazyMotion(strict domMax)/MotionConfig provider with full motion→m conversion, and mechanism-level motion across storefront (live cart badge + springing basket bar), KDS (FLIP re-sort + one-shot ember glow), dashboard (KPI count-ups + de-blued charts), and both tracking steppers — zero new dependencies, 12 new Jest blocks, metrics 1258→1270.**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-07-14T01:51:13Z
- **Completed:** 2026-07-14T02:11:13Z
- **Tasks:** 3
- **Files modified:** 35 (869 insertions, 243 deletions)

## Accomplishments

- App chrome now carries the locked sketch-001 flame-orange primary (`20.5 90.2% 48.2%`) and ring in both light and dark modes, plus --trust/--ember-bright/--shadow-glow-ember custom props and the shimmer keyframe — token indirection only, no component restyle
- Every framer-motion component renders through `LazyMotion strict` (async `domMax`) + `MotionConfig reducedMotion="user"`; all 10 dashboard pages converted from `motion.` to `m.` and the dashboard overview consumes the shared `staggerContainer`/`staggerItem` variants
- Storefront: `CartProvider` broadcasts `jtoye:cart-updated` on every persist; `useCartCount` (SSR-safe, cross-tab) drives a spring-pop basket badge in the nav on `/shop/[slug]` routes with `aria-live="polite"`; `FloatingCartBar` springs in/out via `AnimatePresence` with safe-area padding; modal footer got tap feedback
- KDS: single sorted grid FLIP-animates via `AnimatePresence mode="popLayout"` + `m.div layout`; orders unseen after the first batch glow ember exactly once (seen-set reseeds on shop switch); STOMP wiring, bump handlers, beep, and sorting untouched
- Dashboard: KPI values count up via `useCountUp` (instant jump under prefers-reduced-motion); zero `#3b82f6` literals remain — charts fill from `CHART_COLORS` (pie Cell plumbing preserved)
- Both order-tracking steppers animate (scaleX/scaleY connectors, spring dots keyed on completion, finite 2-repeat active pulse); both 15s polling transports untouched
- Gates: `npm run build` green, full `npx jest` 256/256 green (40 suites), `docs-freshness` check green at 1270, zero package.json/package-lock.json changes (D-01), full `eslint .` 0 errors (one fewer warning than baseline)

## Task Commits

Each task was committed atomically:

1. **Task 1: Foundation — brand tokens, MotionProvider, shared variants, Skeleton, m-conversion, viewport** - `2aa2785` (feat)
2. **Task 2: Feature motion — cart badge/basket bar, tap feedback, KDS FLIP + glow, KPI count-ups + chart colors, tracking steppers** - `48f7d89` (feat)
3. **Task 3: Tests, metrics refresh, full gates** - `e38872e` (test)

**Plan metadata:** docs commit handled by orchestrator per dispatch constraints.

## Files Created/Modified

- `frontend/lib/motion-features.ts` - domMax feature bundle default export for LazyMotion async loading
- `frontend/components/motion-provider.tsx` - MotionProvider: LazyMotion strict + MotionConfig reducedMotion=user
- `frontend/lib/motion.ts` - shared durations/springPop/fadeInUp/staggerContainer/staggerItem
- `frontend/components/ui/skeleton.tsx` - shimmer Skeleton primitive (incremental adoption; no existing animate-pulse replaced, per D-06)
- `frontend/hooks/use-cart-count.ts` - SSR-safe per-slug cart count (localStorage + jtoye:cart-updated + storage events)
- `frontend/hooks/use-count-up.ts` - standalone animate() count-up with useReducedMotion instant jump
- `frontend/lib/chart-colors.ts` - CHART_COLORS brand hexes for Recharts
- `frontend/app/globals.css` - orange-600 primary/ring HSL (:root + .dark), extended brand tokens, shimmer keyframe
- `frontend/app/layout.tsx` - MotionProvider wrap + viewport export (themeColor media array); force-dynamic + CSP comment intact
- `frontend/app/dashboard/*.tsx` (10 pages) - motion→m conversion; overview also gets StatValue count-ups + CHART_COLORS + orange spinner
- `frontend/app/dashboard/kitchen/page.tsx` - popLayout FLIP + one-shot ember glow with seen-id bookkeeping
- `frontend/components/storefront/{cart-provider,storefront-nav,product-detail-modal}.tsx` - event dispatch, basket badge, tap feedback
- `frontend/app/shop/[slug]/page.tsx` - FloatingCartBar AnimatePresence spring + safe-area
- `frontend/app/track/page.tsx` + `frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx` - animated steppers, polling untouched
- `frontend/jest.setup.js` - global framer-motion mock extension + next/navigation useParams
- 5 new test files (12 blocks) + `frontend/app/dashboard/__tests__/page.test.tsx` spinner assertion update
- `docs/metrics.json` (1258→1270, jest 249/35→261/40 via `docs-freshness.sh --write`) + `CLAUDE.md` testing line synced

## Decisions Made

- Safe-area inset implemented as `pb-[max(0.75rem,env(safe-area-inset-bottom))]` (+ sm variant) rather than the plan's literal `pb-[env(...)]` — see Deviations
- KDS glow seen-set is reseeded (`null`) on shop switch so switching shops never glows the whole incoming board
- Tests needing real LazyMotion strict re-mock framer-motion with `jest.requireActual` per-file; everything else uses the extended global mock

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Literal safe-area class would have zeroed the basket bar's bottom padding**
- **Found during:** Task 2 (FloatingCartBar)
- **Issue:** Tailwind orders `pb-*` after `p-*`, so the plan's literal `pb-[env(safe-area-inset-bottom)]` resolves to `padding-bottom: 0` on every non-notch device (env()=0), visually regressing the bar flush against the screen edge
- **Fix:** `pb-[max(0.75rem,env(safe-area-inset-bottom))] sm:pb-[max(1rem,env(safe-area-inset-bottom))]` — keeps the existing 12/16px padding and still pads for the inset
- **Files modified:** frontend/app/shop/[slug]/page.tsx
- **Verification:** build green; class preserves p-3/sm:p-4 visuals when inset is 0
- **Committed in:** 48f7d89

**2. [Rule 1 - Bug] Nav badge violated the Phase 19 12px accessibility floor**
- **Found during:** Task 3 (full jest run — pre-existing `palette-discipline.test.ts` gate failed)
- **Issue:** New badge used `text-[10px]`, banned app-wide since 19-08 (UIX-06 sub-12px floor)
- **Fix:** `text-xs`, matching the FloatingCartBar badge precedent
- **Files modified:** frontend/components/storefront/storefront-nav.tsx
- **Verification:** palette-discipline suite green
- **Committed in:** e38872e

**3. [Rule 3 - Blocking] Global jest framer-motion mock only knew `motion.div`/`motion.tr`**
- **Found during:** Task 3 (planned "fix any fallout" step — 9 suites / 49 tests failing after the m-conversion)
- **Issue:** jest.setup.js's framer-motion mock predates `m`, `AnimatePresence`, `LazyMotion`, `MotionConfig`, `animate()`, `useReducedMotion`, and next/navigation's `useParams` — every page test rendering converted components crashed
- **Fix:** Extended the global mock (m proxy stripping motion-only props, passthrough presence/config wrappers, immediate-jump `animate()` fake, `useReducedMotion`→false, `domMax`) and added `useParams` to the next/navigation mock; updated the dashboard spinner assertion blue→orange (legitimate consequence of the planned de-blue-ing)
- **Files modified:** frontend/jest.setup.js, frontend/app/dashboard/__tests__/page.test.tsx
- **Verification:** full suite 256/256 green
- **Committed in:** e38872e

**4. [Rule 1 - Bug, pre-declared in plan] D-08 basket bar enhanced in place**
- **Found during:** Task 2 (as the plan itself documented)
- **Issue:** D-08 originally specified a NEW mobile-only bar; the mandated check confirmed `FloatingCartBar` already ships the affordance on all viewports as the page's only cart affordance
- **Fix:** Enhanced in place (AnimatePresence spring + safe-area), all-viewport visibility preserved per the Incremental Betterment Doctrine
- **Files modified:** frontend/app/shop/[slug]/page.tsx
- **Committed in:** 48f7d89

---

**Total deviations:** 4 auto-fixed (3× Rule 1, 1× Rule 3)
**Impact on plan:** All fixes required for visual correctness, the Phase 19 accessibility gate, and green pre-existing suites. No scope creep; zero dependency changes.

## Issues Encountered

- `react-hooks/set-state-in-effect` (eslint 9 flat config) reports once per effect — the suppress directive must sit on the FIRST sync setState in the effect body; documented inline in use-cart-count.ts
- `git diff --diff-filter=D origin/main..HEAD` shows phantom "deletions" (V51 migration etc.) — branch-base drift only: origin/main advanced with the #113 merge after this worktree branch was cut from 7e356a3. Per-commit deletion checks on all 3 commits are clean

## Known Stubs

None — all new components are wired to live data sources (localStorage/context/API state); no placeholder values flow to UI.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Motion architecture (provider + shared lib + m-only rule) ready for the storefront theme implementation phase (sketch 001 winner D) and any future motion work
- Live browser verification (badge pop, basket bar spring, KDS glow, count-ups, steppers) is the orchestrator's post-execution step per dispatch — jsdom cannot prove visual motion
- Skeleton primitive shipped component+tests only; hand-rolled animate-pulse blocks remain for incremental adoption (D-06, intentional)

## Self-Check: PASSED

- All 12 created files exist on disk (verified)
- Commits 2aa2785, 48f7d89, e38872e exist on feature/motion-uplift-foundation (verified)
- Gates re-verified: build green, jest 256/256, docs-freshness OK (1270), package.json/package-lock.json untouched

---
*Phase: quick-260714-3na*
*Completed: 2026-07-14*
