---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 06
subsystem: frontend-access-management
tags: [next-app-router, react-client, rfc7807, access-required-state, docs-freshness, phase-gate]

# Dependency graph
requires:
  - phase: 23-04-staff-backend
    provides: "GROUP_ADMIN-gated REST @ /api/v1/staff — GET (StaffListResponse{directory,grants}), POST /grant (201 new / 200 idempotent replay), DELETE /{id} (204; last-GA -> typed 409 /last-group-admin); non-GA -> typed 403 /shop-access-denied"
  - phase: 23-05-shop-switcher
    provides: "sidebar.tsx shared `navigation` array + the mounted ShopSwitcher; MobileTabBar's PRIMARY_ORDER/moreItems derivation"
  - phase: 23-07-shop-context-wiring
    provides: "the 3 shop-scope Jest specs whose blocks the phase-gate count reconcile had to include"
provides:
  - "/dashboard/staff — GROUP_ADMIN staff-management screen (directory picker + grant form + grants table w/ revoke), 403 -> access-required card, 409 -> inline last-GA message"
  - "lib/staff-api.ts — fetchStaff/grantStaff/revokeStaff typed client over apiClient"
  - "Staff nav item in the shared navigation array (auto-flows to the mobile More sheet)"
  - "Reconciled docs/metrics.json + CLAUDE.md/AGENTS.md prose counts (total 1511) — docs-freshness gate green"
affects: []   # last plan of the phase

# Tech tracking
tech-stack:
  added: []   # zero new dependencies — composition of existing apiClient / ui primitives / lucide icons
  patterns:
    - "Access-required-on-403 as an honest page state (finance/page.tsx idiom): the server's typed 403 is rendered as a ShieldCheck card, never a blank, a crash, or an empty table that implies 'no staff'"
    - "Typed-refusal-to-inline-notice: a 409 becomes a persistent in-context <div role='alert'> next to the action that caused it, NOT a transient destructive toast — the reason stays on screen and the row survives"
    - "Native <select> for form pickers (the 23-05 shop-switcher precedent) rather than Radix Select — jsdom-testable with fireEvent.change and keyboard/screen-reader native"
    - "Mount-effect independence from hook identity: load() is a plain function called from a `[]` effect, so an unstable useToast identity cannot loop GET /api/v1/staff"

key-files:
  created:
    - frontend/app/dashboard/staff/page.tsx
    - frontend/lib/staff-api.ts
    - frontend/app/dashboard/__tests__/staff-page.test.tsx
  modified:
    - frontend/components/dashboard/sidebar.tsx
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - .planning/phases/23-vendor-scoped-access-responsive-dashboard-nav/deferred-items.md

key-decisions:
  - "Page title is 'Staff & access', not 'Staff' — the nav label stays 'Staff' (D-10) while the h1 disambiguates from the STAFF role label rendered in the grants table."
  - "The directory is surfaced twice by design: as the grant-target picker (its functional role) and as a 'Team directory' panel with last-seen (its D-09 informational role, so an empty/short list reads as 'nobody has logged in yet', not a bug)."
  - "No confirm-gate on self-revoke. D-11's self-downgrade warning is a persistent 'This is you' badge + a standing caution line, not a modal — a blocking confirm would have made the server's 409 unreachable and traded a real guard for a UI ritual."
  - "AGENTS.md prose count reconciled too (it was stale at 681, a pre-MCP-era figure) because the plan's Task 3 explicitly names the AGENTS.md mirror; PROJECT.md's '1456' was deliberately NOT touched (see Deviations)."

patterns-established:
  - "Grant-form option labels are built as single template-string children (`{`${a} — ${b}`}`) rather than JSX interpolation sequences, so RTL exact-text queries on role labels elsewhere on the page do not collide with option text nodes"

requirements-completed: [VSA-04]

# Metrics
duration: 12min
completed: 2026-07-20
---

# Phase 23 Plan 06: Staff-Management Screen + Phase-Gate Metrics Reconcile Summary

**VSA-04 closes end-to-end: a GROUP_ADMIN can now open /dashboard/staff, see who has signed in, grant a `(user, shop|null, role)` and revoke it — while a non-group-admin gets the honest access-required card from the server's typed 403 and the last-group-admin 409 surfaces as a plain-English refusal that leaves the grant intact; the phase-gate reconcile takes docs/metrics.json + the CLAUDE.md prose from 1456 to 1511 with docs-freshness check-mode green.**

## Performance

- **Duration:** ~12 min (first commit 12:23 → last 12:35 BST)
- **Tasks:** 3
- **Files:** 3 created + 5 modified

## Accomplishments

- **Staff screen + typed client (Task 1, TDD)** — `lib/staff-api.ts` wraps `apiClient` with `fetchStaff()`/`grantStaff()`/`revokeStaff()` plus `ShopRole`/`ROLE_LABELS` and null-safe list defaults. `app/dashboard/staff/page.tsx` renders three cards: a **grant form** (directory picker → shop select defaulting to "All shops / tenant-wide" → role select with plain-English scope hints), a **Team directory** panel (display name + last-seen, with the D-09 "appear here after they sign in" copy), and a **Current access** table (email / shop / role / revoke). A 403 from the GROUP_ADMIN-gated GET short-circuits to the shared ShieldCheck access-required card (D-10/D-13); a 409 from grant *or* revoke becomes a persistent inline `role="alert"` notice (D-11); a 400 explains the shop-scoped-GROUP_ADMIN rejection that 23-04 enforces.
- **Staff nav item (Task 2)** — one entry added to the single-source-of-truth `navigation` array in `sidebar.tsx` with the `UserCog` icon, following the Approvals convention (always listed; the page gates). Because `MobileTabBar` derives `moreItems` by filtering the shared array against a fixed `PRIMARY_ORDER`, Staff flows into the mobile "More" sheet with **zero mobile-nav changes** — MOBL-01 stayed verify-first and untouched.
- **Phase-gate reconcile (Task 3)** — `scripts/docs-freshness.sh --write` recomputed every lane across 23-01..23-07; CLAUDE.md's prose breakdown and the AGENTS.md mirror were updated by hand (the script only rewrites the JSON). Check mode now exits **0**.

**Final reconciled count: 1511 total logical invocations** — `java_test_methods` 989→**1010** (files 170→175), `jest_blocks` 324→**357** (files 51→56), `playwright_blocks` 39→**40**, `java_controllers` 20→**21**, `go_test_funcs` 77 (unchanged), `mcp_test_blocks` 27 (unchanged). `schema_version` stays **56** as predicted (V52 < HEAD V56). Arithmetic checks: 1010+357+77+40+27 = 1511.

## Task Commits

1. **Task 1 RED — failing staff-screen spec (7 cases)** — `3d0b1cf` (test)
2. **Task 1 GREEN — staff screen + staff-api** — `c63c90d` (feat)
3. **Task 2 — Staff nav item in the shared array** — `a14490e` (feat)
4. **Task 3 — metrics + prose reconcile (1456 → 1511)** — `1301580` (docs)

## Verification Performed

| Check | Result |
|-------|--------|
| `npx jest app/dashboard/__tests__/staff-page.test.tsx` | **7/7 green** (list, shop-scoped grant, tenant-wide grant `shopId:null`, revoke, 403→access-required + no PII, 409→last-GA message + grant survives, self "This is you") |
| `npx jest` (full frontend suite) | **352/352 green, 56/56 suites** — no regression from the sidebar change |
| `npm run build` (tsc gate) | **exit 0**; `/dashboard/staff` route registered |
| `scripts/docs-freshness.sh` (check mode) | **exit 0** — "metrics match source (total logical invocations: 1511)" |
| RED-before-GREEN | confirmed — the spec failed on a missing `../staff/page` module before implementation |

## Deferred verification (low-footprint mode)

The user placed this execution in reduced-resource mode (a prior desktop crash), which forbids Docker, Gradle/Testcontainers, and Playwright. These plan-listed checks were **NOT run** and are **not** claimed:

- `cd core-java && ./gradlew test integrationTest` (plan `<verification>` full-phase gate) — **not run** (Testcontainers/Docker forbidden). No Java source changed in this plan, so the risk is limited to the pre-existing snapshot item below.
- `npx playwright test` (plan `<verification>` full-phase gate) — **not run** (browser automation forbidden). No E2E spec was added or modified here.
- Live browser verification of /dashboard/staff (real Keycloak GROUP_ADMIN vs non-GA session) — **not run**. The 403/409 paths are proven at the Jest level against axios-shaped errors, not against the live API.

## Known blocker for the phase PR (inherited, could not be cleared here)

**`docs/api/openapi-snapshot.json` does not contain the three `/api/v1/staff` endpoints** (`grep -c "api/v1/staff"` → 0). `OpenApiSnapshotTest` runs in *check* mode inside `integrationTest`, so **CI will fail** until someone runs `./gradlew :core-java:updateOpenApiSnapshot` and commits the diff. 23-04 deferred this to "the phase-gate reconcile"; this plan owns that gate but the task boots the full Spring context against a throwaway Testcontainers Postgres — forbidden under the low-footprint constraint. **This is the one known red gate left in Phase 23.** It is a regeneration step, not a code defect.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Mount effect re-fetched in a loop on unstable hook identity**
- **Found during:** Task 1 (GREEN — 3 of 7 cases flipped the page back to its loading spinner)
- **Issue:** `load` was a `useCallback` with `[toast]` in its deps, consumed by `useEffect(..., [load])`. Any `useToast()` implementation that returns a fresh object per render makes `load` a new identity every render → the effect re-runs → `setState` → re-render → **an unbounded GET /api/v1/staff loop**. Under test the page never left `loading`; in production it would hammer the API.
- **Fix:** `load` is now a plain function invoked from a `[]` mount effect (with the codebase's standard `eslint-disable-next-line react-hooks/exhaustive-deps`), matching the `finance/page.tsx` idiom — fetch on mount, explicit `await load()` after each write. The page no longer couples its fetch lifecycle to a hook's identity.
- **Files modified:** `frontend/app/dashboard/staff/page.tsx`
- **Commit:** `c63c90d`

**2. [Rule 1 - Bug] Role `<option>` labels split into separate text nodes**
- **Found during:** Task 1 (GREEN — `queryByText("Staff")` matched an option, not just the grants row)
- **Issue:** `{ROLE_LABELS[r.value]} — {r.hint}` compiles to three sibling text children, so the option exposed a standalone exact-text `"Staff"` / `"Shop manager"` node. That collides with any exact-text assertion on the grants table and, more importantly, makes the option's accessible name fragmented for assistive tech.
- **Fix:** single template-string child — `` {`${ROLE_LABELS[r.value]} — ${r.hint}`} ``. Recorded as a pattern above.
- **Files modified:** `frontend/app/dashboard/staff/page.tsx`
- **Commit:** `c63c90d`

### Judgement calls (not auto-fixes)

**3. AGENTS.md updated; PROJECT.md deliberately left alone**
- Task 3 says to update the AGENTS.md mirror "if the project mirrors counts" — it does, and it was stale at **681** (a pre-MCP-era figure, drifted long before Phase 23). Updated to 1511 in line with CLAUDE.md.
- `.planning/PROJECT.md:31` also contains "1456", but that sentence reads *"Current **main** … is schema V56 / 1456 test invocations"* — a historical statement about the state of `main`, which is still accurate: Phase 23 is unmerged. Rewriting it to 1511 would have falsified the record rather than reconciled it. The docs-freshness gate does not read PROJECT.md. Left unchanged on purpose.

### Out-of-scope discoveries (logged, not fixed)

**4. docs-freshness counts ~5 more Jest blocks than Jest executes** — the manifest records `jest_blocks` 357 while `npx jest` runs 352. The script counts textual `\b(it|test)\(` occurrences, which also matches `RegExp.prototype.test(` calls (`frontend/__tests__/link-graph.test.ts:47,117,119`, `app/dashboard/kitchen/__tests__/page.test.tsx:210`). **Pre-existing** — this plan's own spec counts 7 and runs 7. The gate stays self-consistent (manifest == computed), so per SCOPE BOUNDARY it is logged in `deferred-items.md`, not fixed: changing the regex would shift the committed baseline and is its own change.

---

**Total deviations:** 2 auto-fixed bugs (both real defects the tests caught), 1 documented judgement call, 1 out-of-scope discovery logged.
**Impact on plan:** None on deliverables — every intended behaviour shipped and is proven green.

## Issues Encountered

- **Radix `Select` is hostile to jsdom** (pointer-event APIs). Rather than shim it, the form uses native `<select>` — which is also exactly what the 23-05 shop-switcher does, so this is convention-following, not a workaround. Native selects are keyboard- and screen-reader-native and give the mobile OS picker for free.
- **Exact-text collisions between role labels and page chrome** drove two naming decisions (h1 "Staff & access"; option labels carrying a scope hint). Both improved the UI copy independently of the tests.

## User Setup Required
None. No new dependencies, config keys, env vars, or secrets. The screen consumes an endpoint that already exists on the current JWT/tenant chain.

## Known Stubs
None. Every rendered value comes from a live source: `GET /api/v1/staff` (directory + grants) and `fetchMyShops()` (shop names for the picker and the grants table). No hardcoded arrays, placeholder copy, or TODO-gated branches. Empty states ("Nobody has signed in yet", "No access granted yet") are genuine zero-data renders, not stubs.

## Threat Flags
None beyond the plan's `<threat_model>`. No new network surface was introduced — the screen consumes 23-04's endpoints. T-23-06-01 (non-GA elevation via direct URL) is mitigated by the server-side gate rendered as the access-required state and proven by the 403 case; T-23-06-02 (directory PII to a non-GA) is proven by asserting no directory name/email is in the DOM on a 403 (no data is even fetched); T-23-06-03 (last-GA self-lockout) by the inline 409 message + the "This is you" / standing self-downgrade caution; T-23-06-04 (stale counts hiding a broken gate) by the reconcile + check-mode-green. T-23-06-SC: no packages installed.

## Next Phase Readiness

- **VSA-04 is complete** — backend (23-04) + screen (23-06) + Jest list/grant/revoke, matching the requirement's stated acceptance. MOBL-01 was already closed by 23-05 and is untouched here.
- **Phase 23 is code-complete across all 7 plans.** The docs-freshness CI gate is green at 1511.
- **One action before the phase PR can go green:** run `./gradlew :core-java:updateOpenApiSnapshot` and commit the snapshot diff (see the blocker section). This needs Docker, which was unavailable in this session.
- **Recommended before merge:** the full-phase gate that low-footprint mode deferred — `./gradlew test integrationTest`, `npx playwright test`, and a live browser pass over /dashboard/staff with a real GROUP_ADMIN and a real non-GA session.

## Self-Check: PASSED
- All 3 created files + 5 modified files verified present on disk (`staff/page.tsx` 468 lines, `staff-api.ts` 79, `staff-page.test.tsx` 256, `sidebar.tsx` 145, `metrics.json` 15, `CLAUDE.md` 403).
- All 4 task commits verified in git log: `3d0b1cf`, `c63c90d`, `a14490e`, `1301580`.
- Verification green: 7/7 targeted Jest, 352/352 full frontend Jest, `npm run build` exit 0, `scripts/docs-freshness.sh` exit 0 at 1511.
- Deferred/forbidden verifications explicitly recorded above rather than claimed.

---
*Phase: 23-vendor-scoped-access-responsive-dashboard-nav*
*Completed: 2026-07-20*
