---
phase: 19-full-frontend-experience-overhaul
plan: 05
subsystem: ui
tags: [nextjs, react, tailwind, design-tokens, public-shell, order-tracking, guest-lookup]

# Dependency graph
requires:
  - phase: 19-03
    provides: PublicShell + PublicHeader/PublicFooter (shared public chrome) and the public link-graph
  - phase: 19-01
    provides: public order-status endpoint contract (/public/orders/{n}?email=)
provides:
  - "/for-operators + /business-model-guide re-skinned onto design tokens (zero hardcoded hex) and wrapped in PublicShell"
  - "/track converted to a guest lookup (order# + email, no sign-in wall) wrapped in PublicShell"
  - "order-confirmation page 'Track this order' affordance de-orphaning /track (>=3 inbound links)"
affects: [19-08 (text-[10px] sweep), 19-09 (CSP/Playwright closure), docs-freshness metrics]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Marketing surfaces use only Tailwind tokens within the locked orange/emerald/slate family; colored hard-shadows via theme(colors.*) not hex"
    - "Public marketing/track pages join the connected surface by wrapping their content in the shared PublicShell (root <main> demoted to <div> to avoid nested <main>)"
    - "Guest order tracking reuses the IDOR-hardened mandatory-verify endpoint (order# + email) — no second auth wall"

key-files:
  created:
    - frontend/app/__tests__/track.test.tsx
  modified:
    - frontend/components/marketing/operator-pitch.tsx
    - frontend/components/marketing/business-model-guide.tsx
    - frontend/app/for-operators/page.tsx
    - frontend/app/business-model-guide/page.tsx
    - frontend/app/track/page.tsx
    - frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx
    - frontend/components/marketing/__tests__/operator-pitch.test.tsx
    - frontend/components/marketing/__tests__/business-model-guide.test.tsx

key-decisions:
  - "Renamed the business-model-guide anchor slug 'decision' -> 'the-decision' so the hex-gate regex (#[0-9a-fA-F]{3,8}) no longer false-matches '#dec' in the skip-link href"
  - "Expressed the bespoke hard-offset shadows with theme(colors.orange.500)/theme(colors.slate.200) so the brutalist shadow aesthetic survives with zero hex"
  - "Added a real email input to /track (the old page only pre-filled email as text) so a guest with no session can actually look up an order"

patterns-established:
  - "Hex->token migration preserves layout + copy; only color values change (re-skin, not rebuild)"
  - "Public display type capped at text-6xl / weight 700 — font-black/text-7xl/text-8xl/serif banned on marketing"

requirements-completed: [UIX-01]

# Metrics
duration: ~18min
completed: 2026-07-11
---

# Phase 19 Plan 05: Marketing Re-skin + Guest Track Page Summary

**Migrated the two bespoke marketing palettes onto design tokens (zero hardcoded hex, converged on bg-slate-900) and turned /track into a no-auth guest lookup — both now wrapped in the shared PublicShell, with the order-confirmation page linking to /track.**

## Performance

- **Duration:** ~18 min
- **Started:** 2026-07-11T12:07:00Z (approx)
- **Completed:** 2026-07-11T12:22:28Z
- **Tasks:** 2 (Task 2 is TDD: RED + GREEN)
- **Files modified:** 8 modified + 1 created

## Accomplishments
- `operator-pitch.tsx` + `business-model-guide.tsx` fully de-hexed (17–20 bespoke hex each → Tailwind tokens within the locked orange/emerald/slate family); dark full-bleed layout and all copy preserved; both dark surfaces converged on `bg-slate-900`.
- Public display typography capped at `text-6xl`/700 — removed all `font-black`, `text-8xl`, `text-7xl`; no serif introduced.
- `/for-operators` and `/business-model-guide` wrapped in the shared `PublicShell` (join the connected surface).
- `/track` de-walled: removed `RequireCustomerAuth`, added a real order#+email guest form calling the IDOR-hardened `/public/orders/{n}?email=` endpoint, kept the stepper + 15s auto-refresh, session pre-fills (never requires) the email, and the bespoke mini-header is replaced by `PublicShell`.
- Order-confirmation page gained a "Track this order" affordance → `/track?order=…&email=…`; app-wide `href="/track"` links now number 4 (>=3).

## Task Commits

1. **Task 1: Marketing token re-skin + PublicShell wrap** - `a0aa91a` (feat)
2. **Task 2 (TDD RED): failing guest-track-lookup test** - `19e9465` (test)
3. **Task 2 (TDD GREEN): guest track lookup + PublicShell + confirmation cross-link** - `1d59259` (feat)

_No REFACTOR commit was needed — the GREEN implementation was already clean._

## Files Created/Modified
- `frontend/components/marketing/operator-pitch.tsx` - navy/orange/yellow hex → `slate-900`/`orange-500`/`amber-300` tokens; hero capped; root `<main>`→`<div>`.
- `frontend/components/marketing/business-model-guide.tsx` - teal/rust/olive hex → `slate-900`/`orange-600`/`emerald-50` tokens; `<main>`→`<div>`; anchor slug `decision`→`the-decision`.
- `frontend/app/for-operators/page.tsx` - wraps `OperatorPitch` in `PublicShell`.
- `frontend/app/business-model-guide/page.tsx` - wraps `BusinessModelGuide` in `PublicShell`.
- `frontend/app/track/page.tsx` - guest lookup (order# + email), no auth wall, `PublicShell`, contract not-found copy.
- `frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx` - "Track this order" affordance → `/track`.
- `frontend/app/__tests__/track.test.tsx` - new: guest lookup / no-wall / endpoint / pre-fill / not-found / PublicShell / cross-link.
- `frontend/components/marketing/__tests__/{operator-pitch,business-model-guide}.test.tsx` - added token + PublicShell assertions.

## Decisions Made
- **Anchor slug rename (`decision`→`the-decision`)**: the plan's hex success gate uses `#[0-9a-fA-F]{3,8}`, which false-matches the `#dec…` prefix of the skip-link `href="#decision"`. Renaming the slug keeps the gate at 0 while preserving the "Decision" label and nav behavior.
- **`theme(colors.*)` for hard shadows**: the bespoke `shadow-[Npx_Npx_0_#hex]` offset shadows are part of the confident brutalist look; expressed them as `theme(colors.orange.500)` / `theme(colors.slate.200)` to keep the aesthetic with no hex.
- **Added a genuine email `<input>` to /track**: the previous page only surfaced the email as pre-filled *text*, which left a session-less guest unable to enter an email. The guest contract needs two real inputs.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] hex success-gate false-positive on the `#decision` anchor**
- **Found during:** Task 1 (marketing re-skin)
- **Issue:** After full token migration, `grep -rlE "#[0-9a-fA-F]{3,8}" components/marketing/*.tsx` still returned `business-model-guide.tsx` because the skip-link `href="#decision"` matches `#dec` (3 hex chars). This would fail the plan's own success gate.
- **Fix:** Renamed the anchor slug `decision`→`the-decision` in the nav item, the skip link, and the `<section id>` (label/behavior unchanged).
- **Files modified:** frontend/components/marketing/business-model-guide.tsx
- **Verification:** `grep -rlE "#[0-9a-fA-F]{3,8}" components/marketing/*.tsx | wc -l` == 0; nav + skip link still resolve to the section.
- **Committed in:** a0aa91a (Task 1 commit)

**2. [Rule 2 - Missing Critical] /track guest form was missing an email input**
- **Found during:** Task 2 (track guest lookup)
- **Issue:** The old page pre-filled the email only as display text; a guest without a session had no way to supply the mandatory proof-of-ownership email, making guest lookup impossible.
- **Fix:** Added a labelled `type="email"` input (pre-filled from session/URL when available, always editable).
- **Files modified:** frontend/app/track/page.tsx
- **Verification:** Jest asserts two inputs render and the endpoint is called with `{ params: { email } }`; build passes.
- **Committed in:** 1d59259 (Task 2 GREEN commit)

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 missing-critical)
**Impact on plan:** Both were necessary to satisfy the plan's own gates / guest contract. No scope creep; layout and copy preserved.

## Issues Encountered
- **Worktree had no `node_modules`** (Turbopack rejects a symlink escaping the worktree root). Resolved by copying the main repo's `node_modules` (lockfile verified identical) into the worktree so `npm run build` + Jest run natively. `node_modules` is gitignored and NOT committed.

## Known Stubs
None — the marketing pages are token re-skins of existing content, and `/track` is wired to the real IDOR-hardened endpoint. Input placeholders (`ORD-XXXX…`, `you@email.com`) are not data stubs.

## Verification
- Marketing hex gate: `grep -rlE "#[0-9a-fA-F]{3,8}" components/marketing/*.tsx` == 0.
- Banned typography: `grep -rcE "text-7xl|text-8xl|font-black|font-serif" components/marketing/*.tsx` == 0.
- Track acceptance greps: `RequireCustomerAuth`==0, `PublicShell`>=1, `/public/orders/`>=1, confirmation `/track` link present, app-wide `href="/track"`==4.
- Jest: full frontend suite **25 suites / 153 tests green** (marketing 16, track 6 included).
- `npm run build` succeeds (all 30 routes compile).

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Plan 19-08 still owns the app-wide `text-[10px]`→`text-xs` sweep (intentionally untouched here).
- Plan 19-09 closure owns the CSP/Playwright storefront specs and the `docs/metrics.json` / docs-freshness reconciliation — this plan adds 14 new Jest `it` blocks (8 marketing + 6 track), so the Jest count in `docs/metrics.json` will need updating at phase closure (deliberately not touched here to avoid cross-worktree merge conflicts).

## Self-Check: PASSED
- Created file exists: `frontend/app/__tests__/track.test.tsx` — FOUND.
- Modified files present (8) — FOUND.
- Commits exist: `a0aa91a`, `19e9465`, `1d59259` — FOUND.

---
*Phase: 19-full-frontend-experience-overhaul*
*Completed: 2026-07-11*
