---
phase: 19-full-frontend-experience-overhaul
plan: 04
subsystem: ui
tags: [nextjs, react, tailwind, responsive, mobile, playwright, dashboard, radix-sheet]

# Dependency graph
requires:
  - phase: 19-03
    provides: shadcn `sheet` primitive (frontend/components/ui/sheet.tsx) reused as the mobile "More" drawer
provides:
  - Exported single-source `navigation` array from sidebar.tsx driving BOTH the desktop sidebar and the mobile bar
  - Desktop-only sidebar (`hidden md:flex`) — zero visual change at >= md
  - MobileTabBar (< md): fixed 4-tab bottom bar (Dashboard/Orders/Products/Kitchen) + a "More" sheet holding the remaining routes, user block, theme toggle and sign-out
  - Evolved DashboardShell: mobile top bar (wordmark) + pb-20 scroll clearance for the fixed bar; desktop unchanged
  - Playwright mobile spec (390x844) across all 11 dashboard routes (sidebar-hidden + bottom-bar-visible + title-not-squeezed + onboarding-not-regressed)
affects: [19-08 palette sweep (owns sidebar avatar-gradient + any bar recolour), 19-09 whole-app UAT (definitive live Playwright + human sign-off)]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Single navigation source: both nav bars import the same exported array — no fork"
    - "Responsive chrome via md breakpoint: desktop `hidden md:flex` sidebar vs mobile `md:hidden` bottom bar"
    - "Theme state read from the DOM `dark` class (not matchMedia) so the drawer toggle stays jsdom-testable and in sync with the sidebar's on-mount owner"

key-files:
  created:
    - frontend/components/dashboard/mobile-tab-bar.tsx
    - frontend/e2e/dashboard-mobile.spec.ts
  modified:
    - frontend/components/dashboard/sidebar.tsx
    - frontend/components/dashboard/dashboard-shell.tsx
    - frontend/components/dashboard/__tests__/dashboard-shell.test.tsx

key-decisions:
  - "Drove both bars off the single exported `navigation` array (no fork) — hrefs/icons can never drift"
  - "Mobile 'More' drawer relocates (not duplicates) the sidebar footer's user block + theme toggle + sign-out"
  - "Playwright spec uses REAL Keycloak vendor login, not a fake session cookie — the server-side dashboard auth gate rejects fake cookies (empirically verified: redirects to /auth/signin)"

patterns-established:
  - "Pattern 1: Shared-array-driven dual navigation (desktop sidebar + mobile tab bar) with usePathname active-state"
  - "Pattern 2: mobile-first responsive dashboard shell — hidden md:flex / md:hidden + pb-20 fixed-bar clearance"

requirements-completed: [UIX-02]

# Metrics
duration: 16min
completed: 2026-07-11
---

# Phase 19 Plan 04: Dashboard Responsive Shell Summary

**Mobile dashboard rescue (UIX-02): the fixed 256px sidebar is now `hidden md:flex`; below md a 4-tab bottom bar + "More" sheet (driven off the same exported `navigation` array) makes all 11 dashboard routes usable at 390px — desktop untouched.**

## Performance

- **Duration:** ~16 min
- **Started:** 2026-07-11T12:06Z
- **Completed:** 2026-07-11T12:23Z
- **Tasks:** 3
- **Files modified:** 5 (2 created, 3 modified)

## Accomplishments
- Exported the sidebar `navigation` array as the single source of truth and hid the 256px sidebar below `md` (`hidden md:flex`) with zero desktop visual change.
- Built `MobileTabBar`: a fixed `h-14` bottom bar (`md:hidden`, `pb-[env(safe-area-inset-bottom)]`) with 4 thumb-zone tabs (Dashboard, Orders, Products, Kitchen) + a 5th "More" tab opening the 19-03 shadcn `sheet`, which lists the remaining routes (Shops, Customers, Finance, Marketing, Go live) plus the relocated user block, theme toggle and sign-out. Active = `text-blue-600` (no dark pill); every icon-only control has an `aria-label` + `focus-visible` ring.
- Evolved `DashboardShell` with a slim `md:hidden` mobile top bar (wordmark) and `pb-20` container clearance so content never hides behind the fixed bar; desktop chrome unchanged.
- Extended the shell Jest test (2 → 4 tests) to assert the mobile bars render, are `md:hidden`, and expose the 4 primary tabs + More trigger.
- Authored `e2e/dashboard-mobile.spec.ts`: a 12-case Playwright mobile-viewport spec covering all 11 dashboard routes.

## Task Commits

Each task was committed atomically:

1. **Task 1: Export navigation + make sidebar desktop-only** — `bc2d555` (feat)
2. **Task 2: Mobile tab bar (4 tabs + More sheet) + evolved dashboard shell** — `32bef09` (feat)
3. **Task 3: Playwright mobile spec — 11 dashboard routes at 390px** — `a937c1b` (test)

## Files Created/Modified
- `frontend/components/dashboard/sidebar.tsx` — `export const navigation`; root wrapper `hidden md:flex` (desktop-only). Avatar gradient left untouched per plan (19-08 owns the palette sweep).
- `frontend/components/dashboard/mobile-tab-bar.tsx` (created) — mobile bottom bar + More drawer, imports the shared `navigation`.
- `frontend/components/dashboard/dashboard-shell.tsx` — mobile top bar + `<MobileTabBar className="md:hidden" />` + `pb-20`/`p-4` mobile container padding.
- `frontend/components/dashboard/__tests__/dashboard-shell.test.tsx` — partial-mock the sidebar (keep real `navigation` export) + 2 new mobile-bar assertions.
- `frontend/e2e/dashboard-mobile.spec.ts` (created) — 390px Playwright proof across 11 routes.

## Decisions Made
- **Single navigation source, no fork:** `MobileTabBar` imports `navigation` from `sidebar.tsx`; primary tabs are resolved by href against that array so icons/labels stay in lock-step (also satisfies threat T-19-04-01 — no new/unauthenticated hrefs).
- **Relocate, don't duplicate:** the sidebar footer's user/theme/sign-out controls move into the More sheet; sign-out reuses the existing `signOut({ callbackUrl: "/auth/signin" })` handler (threat T-19-04-02).
- **Theme state from the DOM class, not `matchMedia`:** the drawer toggle reads `document.documentElement.classList.contains("dark")` (the sidebar's on-mount effect remains the initializer). This keeps the two toggles in sync and avoids the jsdom `matchMedia` gap so the shell test renders the real `MobileTabBar` cleanly.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1/3 - Bug/Blocking] Playwright spec uses real Keycloak login instead of the fake-cookie stub idiom**
- **Found during:** Task 3 (Playwright mobile spec)
- **Issue:** The plan told me to copy `kitchen-flow.spec.ts`'s fake-session-cookie idiom (`authjs.session-token=e2e-stub`). Empirically, the dashboard's server-side `auth()` gate (frontend/app/dashboard/layout.tsx) rejects that fake cookie and redirects to `/auth/signin` — I confirmed this with a live probe (page snapshot showed the Keycloak signin page, not the dashboard). Copying it verbatim would produce a spec that can never go green.
- **Fix:** Switched to the repo's proven live-dashboard idiom from `vendor-refund-flow.spec.ts` — a real Keycloak vendor login (SSO button → hosted form, with a credentials-form fallback and a graceful `test.skip` for unknown auth flows) — while keeping the `route()` API stubs for deterministic per-route rendering.
- **Files modified:** frontend/e2e/dashboard-mobile.spec.ts
- **Verification:** `playwright --list` enumerates all 12 cases; `grep -c networkidle == 0`. Live probe against the running `:3000` stack confirmed the login mechanics reach the Keycloak hosted form and submit; it only failed on (a) the seeded creds `tenant-a-user`/`password123` not matching this realm instance and (b) the `:3000` container still running pre-change code (no bottom bar). Both are resolved by the 19-09 rebuilt+seeded UAT.
- **Committed in:** `a937c1b` (Task 3 commit)

**2. [Rule 2 - Missing Critical] Kept the fixed-bar clearance across the full `< md` range**
- **Found during:** Task 2 (dashboard shell padding)
- **Issue:** The plan's `p-4 sm:p-8 pb-20 md:pb-8` string lets `sm:p-8` override the base `pb-20` bottom padding at 640–767px — a range where the 56px fixed bar is still shown — clipping the last ~24px of content.
- **Fix:** Used `p-4 pb-20 sm:p-8 sm:pb-20 md:pb-8` so the 5rem bottom clearance holds until `md`, where the bar is hidden and padding returns to 2rem.
- **Files modified:** frontend/components/dashboard/dashboard-shell.tsx
- **Verification:** `npm run build` succeeds; `grep -c pb-20` on the shell == 2.
- **Committed in:** `32bef09` (Task 2 commit)

---

**Total deviations:** 2 auto-fixed (1 bug/blocking, 1 missing-critical)
**Impact on plan:** Both necessary for correctness. The login change makes the spec genuinely green-able (the plan's suggested idiom could not authenticate); the padding change prevents content clipping in the tablet-ish mobile range. No scope creep — all 11-route coverage and the shared-navigation contract are intact.

## Issues Encountered
- **Worktree has no `node_modules`:** Turbopack (`next build`) rejects a symlinked `node_modules` pointing outside the worktree root. Resolved by a hardlink copy (`cp -al` from the main repo's `frontend/node_modules`, ~0.5s, gitignored) so `next build` + Jest + Playwright run natively. No lockfile churn.
- **Local `npx jest`/`npx playwright` resolve a stale global cache:** used `./node_modules/.bin/jest` and `./node_modules/.bin/playwright` directly.

## Verification Evidence
- **Task 1:** `grep -c "export const navigation" == 1`, `grep -c "hidden md:flex" == 1`, 9 nav entries intact, `npm run build` green.
- **Task 2:** shell Jest 4/4 green; grep gates all pass (no re-declared array; `pb-[env(safe-area-inset-bottom)]`; `text-blue-600` present, `bg-blue-600` absent; `fixed inset-x-0 bottom-0`; shell `pb-20` + `MobileTabBar className="md:hidden"`); `npm run build` green.
- **Task 3:** `grep -c networkidle == 0`; `playwright --list` enumerates 12 cases across all 11 routes. Definitive live green run deferred to 19-09 per plan.

## Known Stubs
None. All wiring is real; the Playwright spec's API `route()` stubs are test doubles, not product stubs.

## User Setup Required
None - no external service configuration required. (For the 19-09 live Playwright run, set `E2E_VENDOR_USERNAME`/`E2E_VENDOR_PASSWORD` to the rebuilt stack's seeded Keycloak vendor if they differ from the `tenant-a-user` default.)

## Next Phase Readiness
- Mobile dashboard nav is functional and desktop is untouched — ready for the 19-08 palette sweep (which owns the sidebar avatar-gradient purple and any bar recolour) and the 19-09 whole-app UAT (definitive live Playwright pass + human visual sign-off against the rebuilt+seeded stack).
- No blockers introduced. Both nav bars share the exported `navigation` array; future route additions land in the More drawer automatically unless promoted to a primary tab.

## Self-Check: PASSED
- Files: all 5 (2 created, 3 modified) present on disk.
- Commits: `bc2d555`, `32bef09`, `a937c1b` all present in git log.
- Scoped Jest: 4/4 passing. `next build`: green (run after Tasks 1 and 2).

---
*Phase: 19-full-frontend-experience-overhaul*
*Completed: 2026-07-11*
