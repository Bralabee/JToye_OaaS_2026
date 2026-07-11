---
phase: 18-vendor-onboarding-first-slice
plan: 07
subsystem: frontend-onboarding
tags: [vendor-onboarding, frontend, next-app-router, state-machine-ui, polling, go-live, docs-freshness]

# Dependency graph
requires:
  - phase: 18-vendor-onboarding-first-slice (plans 02, 05, 06)
    provides: live /api/v1/onboarding surface (create/submit/me/go-live), OnboardingDto/GateDto shapes, auto-approve + guard-veto (400) behaviour
provides:
  - /dashboard/onboarding — single stateful vendor UI (create form -> live status -> go-live)
  - OnboardingDto/GateDto/CreateOnboardingRequest + onboarding enums in frontend/types/api.ts
  - "Start your application" CTA on /for-operators
  - "Go live" sidebar nav item + incomplete-onboarding dashboard banner
  - reconciled docs/metrics.json (jest 129/22, total 907) keeping docs-freshness green
affects: [phase-19+, vendor-onboarding-admin-queue]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "State-machine UI keyed off OnboardingDto.status with a single state-driven primary CTA per state"
    - "4s setInterval polling of GET /me while VERIFYING/PENDING_APPROVAL; interval keyed on status so it self-clears when the status leaves the polling set and on unmount"
    - "Defensive enum->presentation maps with neutral-slate fallbacks so an unknown gateType/status renders instead of crashing"
    - "Non-critical fetch isolation: dashboard /me fetch swallows errors (404 -> not-started banner, other errors -> hidden) so it never breaks the existing dashboard render"

key-files:
  created:
    - frontend/app/dashboard/onboarding/page.tsx
    - frontend/app/dashboard/onboarding/__tests__/page.test.tsx
  modified:
    - frontend/types/api.ts
    - frontend/components/dashboard/sidebar.tsx
    - frontend/components/dashboard/__tests__/dashboard-shell.test.tsx
    - frontend/components/marketing/operator-pitch.tsx
    - frontend/components/marketing/__tests__/operator-pitch.test.tsx
    - frontend/app/dashboard/page.tsx
    - frontend/app/dashboard/__tests__/page.test.tsx
    - docs/metrics.json
    - CLAUDE.md
    - README.md

key-decisions:
  - "Poll effect is keyed on onboarding?.status (not the object) so a same-status re-poll does not churn the interval, and leaving the polling set cleans it up exactly once"
  - "Go-live 400 guard veto closes the dialog and fires the spec destructive toast but leaves the APPROVED status view (and gate breakdown) mounted — the client never assumes success (T-18-07-02)"
  - "Gate rendering reads ONLY the exposed GateDto fields (gateType/status/mandatory/reason/checkedAt); evidence/externalRef are never referenced (T-18-07-01)"
  - "/for-operators CTA is added as a new orange primary Link, existing hero buttons left byte-for-byte unchanged (A5 extend-not-restyle)"

patterns-established:
  - "Single stateful onboarding surface driven by GET /me, mirroring the marketing page's apiClient/toast/raw-select/spinner idioms verbatim"
  - "docs/metrics.json is the sole arbiter of counts (--write), CLAUDE.md + README synced to it, never hand-computed"

requirements-completed: [VOB-01, VOB-02, VOB-03]

# Metrics
duration: ~40min
completed: 2026-07-11
---

# Phase 18 Plan 07: Vendor Onboarding UI Slice Summary

**The already-merged onboarding backend is now VISIBLE and drivable: a signed-in vendor at `/dashboard/onboarding` creates an application (model + shop + optional company number), watches the overall-state badge and 3-gate breakdown auto-refresh every 4s while VERIFYING, and — once APPROVED — confirms and goes live, with a go-live 400 guard-veto surfaced as a destructive toast that keeps the blocking check on screen; a "Start your application" CTA on `/for-operators`, a "Go live" sidebar item, and an incomplete-onboarding dashboard banner route vendors in, all against the LOCKED 18-UI-SPEC, with the full Jest suite green and `docs-freshness` reconciled to 907.**

## Performance
- **Duration:** ~40 min
- **Completed:** 2026-07-11
- **Tasks:** 3 (Task 1 TDD)
- **Files:** 12 (2 created, 10 modified); +13 Jest `it/test` blocks across +1 new file

## Accomplishments
- **`/dashboard/onboarding` (new, single stateful page)** — inherits `DashboardShell` (no new auth/layout), `"use client"`, no `export const dynamic`, no inline scripts (CSP-safe). Loads `GET /api/v1/onboarding/me`: 404 → create form (model toggle + raw `<select>` shop dropdown from `GET /shops?page=0&size=100&sort=name,asc` + optional Companies House `Input`); 200 → status view keyed off `OnboardingDto.status`.
- **Full state-machine UX per spec:** empty→create→DRAFT("Submit for verification")→VERIFYING(4s poll + "Checking…" gate rows)→PENDING_APPROVAL(polled)→APPROVED("Go live")→confirm Dialog→LIVE; ACTION_REQUIRED surfaces each FAILED gate `reason` + a "Re-run checks" resubmit; SUSPENDED/REJECTED/WITHDRAWN render terminal badge + subtitle. Overall-state and per-gate badges use the exact spec label/class/icon maps with neutral-slate defensive fallbacks.
- **Polling (A4, not SSE):** `setInterval` every 4000ms while VERIFYING/PENDING_APPROVAL, keyed on `status` so it self-clears when the status leaves the polling set and on unmount (both proven by Jest fake-timer tests).
- **Go-live safety:** the button is present+enabled ONLY when `status === "APPROVED"`; a 400 guard veto fires the destructive "Not ready to go live yet" toast and keeps the gate breakdown visible (no crash) — the client never assumes success (T-18-07-02 made visible = VOB-03).
- **Entry surfaces:** `Rocket` "Go live" sidebar item → `/dashboard/onboarding`; a new orange "Start your application" `Link` in the `/for-operators` hero (existing buttons untouched); a dismissible dashboard-home banner (amber "not started" / blue "in progress", hidden when LIVE) whose `/me` fetch swallows errors so it can never break the dashboard.
- **Types:** `OnboardingDto`/`GateDto`/`CreateOnboardingRequest` + `OnboardingModel`/`OnboardingState`/`GateType`/`GateStatus` appended to `frontend/types/api.ts`, mirroring the merged Java DTOs.
- **Gates green:** `npm run build` (tsc) passes (route `/dashboard/onboarding` compiled); full Jest suite 128 → all green (22 suites); `docs/metrics.json` regenerated to jest 129/22, total 907; CLAUDE.md + README synced; `scripts/docs-freshness.sh` check mode exits 0.

## Task Commits
1. **Task 1 (RED):** `e0ff981` `test(18-07): add failing onboarding page test (empty/create, DRAFT-submit, VERIFYING poll, go-live guard veto)`
2. **Task 1 (GREEN):** `1c20e43` `feat(18-07): onboarding types + stateful /dashboard/onboarding page`
3. **Task 2:** `d26b62a` `feat(18-07): onboarding entry surfaces — CTA, sidebar item, dashboard banner`
4. **Task 3:** `69d8785` `docs(18-07): reconcile metrics.json + CLAUDE.md/README counts (894 -> 907)`

_(Final plan-metadata commit adds this SUMMARY.)_

## Decisions Made
- **Poll effect keyed on `onboarding?.status`, not the object identity** — a same-status re-poll does not churn/reset the interval, and the cleanup runs exactly once when the status leaves the polling set. This is the cleanest way to satisfy both "poll every 4s" and "stop on terminal/actionable state".
- **CTA is a pure addition** — `/for-operators`'s existing "Check your pilot fit" and "Download vendor pack" buttons were restored byte-for-byte after an initial restyle; only the new orange "Start your application" Link was inserted (A5).
- **Banner error semantics** — 404 → "not started" (amber prompt), any non-404 error → hidden; the fetch lives in its own try/catch outside the dashboard's `Promise.all` so it cannot break the existing render.
- **Dashboard-shell test uses `jest.requireActual`** to render the REAL Sidebar for the "Go live" nav assertion (the existing smoke test stubs the Sidebar), plus a `matchMedia` stub the sidebar theme effect needs under jsdom.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Installed the worktree's `node_modules` to run the gates**
- **Found during:** Task 3 (`npm run build`).
- **Issue:** The parallel-execution git worktree had no `node_modules`. An initial symlink to the main checkout's `node_modules` let Jest run but Turbopack `next build` rejected it ("Symlink … points out of the filesystem root").
- **Fix:** Removed the symlink and ran `npm ci` (worktree lockfile is byte-identical to main — verified — so this installs the EXISTING dependency set; **no new dependency added**, honouring T-18-07-SC). `node_modules` is gitignored and not committed.
- **Files:** none committed. **Verification:** `next build` then green.

**2. [Rule 2 - Test infra] `window.matchMedia` stub in the dashboard-shell test**
- **Found during:** Task 2 (rendering the real Sidebar).
- **Issue:** The real `Sidebar` reads `window.matchMedia` in its theme effect; jsdom does not implement it, so the new nav assertion threw.
- **Fix:** Added a `beforeAll` `matchMedia` stub scoped to the new "Sidebar navigation" describe. No source change.
- **Files:** `frontend/components/dashboard/__tests__/dashboard-shell.test.tsx`. **Committed in:** `d26b62a`.

---
**Total deviations:** 2 (1 environment/build-tooling install, 1 test-infra stub). **Impact:** No scope change; no new runtime dependency; all plan tasks delivered exactly as specified.

## Issues Encountered
- Turbopack cannot resolve an out-of-tree `node_modules` symlink (see deviation 1) — a real `npm ci` in the worktree is required for `next build`.
- A pre-existing `act(...)` warning from an unrelated `app/dashboard/kitchen/page.tsx` test prints during `npm test`; it is a warning only and does not fail any suite (out of scope — logged, not fixed).

## Threat Flags
None beyond the plan's `<threat_model>`. This slice adds **client callers only — no new server surface**. All four register dispositions are honoured:
- **T-18-07-01** (gate info disclosure): only the exposed `GateDto` fields are read; `evidence`/`externalRef` are never referenced.
- **T-18-07-02** (forced go-live): button enabled only when `status === "APPROVED"`; the 400 guard veto is surfaced and success is never assumed (proven by the go-live-400 Jest test).
- **T-18-07-03** (cross-tenant): `CreateOnboardingRequest` carries no `tenantId`; the server resolves tenant and `api-client` injects `X-Tenant-Id`.
- **T-18-07-SC** (supply chain): zero new npm dependencies; the whole slice is built from existing shadcn/lucide/date-fns/framer-motion primitives.

## Known Stubs
None. Every surface is wired to the live `/api/v1/onboarding` endpoints; there are no hardcoded/empty data sources or placeholder copy. Live-browser Playwright validation on the running stack is the orchestrator's post-deploy step (deliberately out of executor scope).

## Self-Check: PASSED
- Both created files verified present: `frontend/app/dashboard/onboarding/page.tsx` (544 lines ≥ min 180) and its test (`259` lines ≥ min 80).
- All 4 task commits verified in git history: `e0ff981`, `1c20e43`, `d26b62a`, `69d8785`.
- `npm run build` (tsc) green with `/dashboard/onboarding` compiled; full Jest suite green (22 suites / 128 tests); `scripts/docs-freshness.sh` exits 0 at total 907.
- STATE.md / ROADMAP.md deliberately NOT modified (orchestrator owns them post-wave).

---
*Phase: 18-vendor-onboarding-first-slice*
*Completed: 2026-07-11*
