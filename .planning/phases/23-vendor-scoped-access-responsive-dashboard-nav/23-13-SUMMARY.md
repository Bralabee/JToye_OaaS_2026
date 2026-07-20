---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 13
subsystem: frontend/dashboard-access
tags: [vendor-scoped-access, shop-switcher, staff-management, CR-08, WR-06, WR-12, IN-02, frontend]
gap_closure: true
requires:
  - "23-12 (GET /api/v1/staff/me → MyAccessDto: userId + groupAdmin + grantedShopIds)"
  - "23-05/23-07 (shop-context localStorage seam + useShopContext hook — reused, not duplicated)"
provides:
  - "server-authoritative GROUP_ADMIN in the frontend (no client-side JWT parse)"
  - "one shared switcher data source (single fetch + single hydration writer) across both mounts"
  - "sub-based self-identification on the staff screen (email-independent)"
  - "action-accurate 409 copy + truthful revocation-timing copy"
affects:
  - "23-14 (owns strict-scoping correctness + the richer JIT-row treatment this plan stubs as 'Unlisted member')"
  - "23-15 (phase-gate OpenAPI/docs reconcile; net Jest test-count delta below)"
tech-stack:
  added: []
  patterns:
    - "server-authoritative access answer consumed at the HTTP seam, not re-derived in the browser"
    - "shared React provider as the single fetch + single hydration writer for N mounted instances"
    - "selection state lifted into the existing localStorage+event seam so instances converge for free"
    - "falsifiability by mocking the apiClient/session seam (not the data function) so tests observe the defect"
key-files:
  created:
    - frontend/components/dashboard/shop-switcher-provider.tsx
  modified:
    - frontend/lib/shops-api.ts
    - frontend/components/dashboard/shop-switcher.tsx
    - frontend/components/dashboard/dashboard-shell.tsx
    - frontend/app/dashboard/staff/page.tsx
    - frontend/components/dashboard/__tests__/shop-switcher.test.tsx
    - frontend/components/dashboard/__tests__/dashboard-shell.test.tsx
    - frontend/app/dashboard/__tests__/staff-page.test.tsx
decisions:
  - "Single-fetch chosen via a shared PROVIDER (not a module-level cached promise): the provider is the SINGLE hydration writer as well, so two switcher instances can never both persist/dispatch on mount — a cached promise fixes the double fetch but leaves two hydration effects that both attempt the D-13 stale correction"
  - "GROUP_ADMIN + userId come from GET /api/v1/staff/me via fetchMyShops; the D-13 stale check keeps using the read-scoped shop list (IN-01 size=200 deferred with reason)"
  - "isSelf compares the Keycloak sub (userId), never a session email — the page no longer imports useSession"
  - "A non-GA 'all' context DISPLAYS as the first granted shop (no orphan <select value>) without persisting it"
  - "staff-api.ts needed no change — userId flows through fetchMyShops from Task 1"
  - "VSA-03/VSA-04 NOT marked complete (anti-false-green): 23-14 + 23-15 still contribute; phase VERIFICATION stays gaps_found until the gate"
metrics:
  duration: ~17m
  tasks: 3
  files: 8
  completed: 2026-07-21
---

# Phase 23 Plan 13: Frontend Consumes Server Authority (CR-08 + WR-06 + WR-12 + IN-02) Summary

The dashboard switcher's authority model now matches the server's, the two switcher
instances are one control with one fetch, and the staff screen identifies people by
their Keycloak `sub` while describing its own behaviour truthfully.

## What shipped

### Task 1 — CR-08: server-sourced GROUP_ADMIN, no silent pinning (`3c7c5c9`)
`decodeJwtPayload` and `isGroupAdminFromSession` are **deleted** from `shops-api.ts` — a
browser-side JWT parse is the wrong shape even for a UI hint (T-23-13-01). `fetchMyShops`
now sources `isGroupAdmin` (and the caller's `userId`, needed by Task 3) from
`GET /api/v1/staff/me` (23-12's `MyAccessDto`), keeping the read-scoped
`GET /api/v1/shops?page=0&size=200` call for the list. The switcher's hydration effect
persists ONLY a genuinely-stale correction (D-13); a clean first load no longer writes a
pin, so the day-one implicit GROUP_ADMIN (who is *not* a realm admin, and whom the old
JWT parse mis-detected as non-GA) lands on **All shops** and can re-select it, instead of
being silently narrowed to their first shop with no way back (T-23-13-02).

### Task 2 — WR-06: one state, one fetch across both switcher mounts (`1975142`)
New `ShopSwitcherProvider` (mounted once in `dashboard-shell.tsx` above both switchers)
is the single `fetchMyShops` caller and the single hydration writer. `ShopSwitcher` is now
a pure presenter: it reads `{shops, isGroupAdmin, loading, stale}` from the provider and
its SELECTED value from `useShopContext()` (the existing localStorage + `shopcontext:change`
seam built in 23-05), so the sidebar and mobile instances converge live with no remount.
The provider renders no DOM of its own, so the MOBL-01-verified 375px shell markup is
byte-for-byte unchanged.

### Task 3 — WR-12 + IN-02 + timing copy + UUID fallback (`0a2eca7`)
- **WR-12:** `isSelf(userId)` compares the caller's Keycloak `sub` (`userId` from
  `MyAccessDto`) instead of a session-email round-trip. The old compare failed whenever the
  session email was absent/differently-cased and — after 23-12 masked directory emails —
  could never match, silently hiding the self-revoke warning. `useSession` is removed from
  the page entirely.
- **IN-02:** a 409 on the **grant** path now shows downgrade-specific copy ("You cannot
  change the last group admin's role…"), distinct from the **revoke** path's removal copy.
- **Timing copy:** the "Current access" description no longer claims unqualified immediacy;
  it states the real bound — changes apply on the next request, but an already-open live
  stream can persist up to the 5-minute SSE timeout (per 23-11's SUMMARY). The self-revoke
  warning was corrected to match.
- **Raw-UUID fallback:** a grant with no directory entry now renders a labelled identity
  ("Unlisted member") instead of a bare UUID (23-14 owns the richer JIT-row treatment).

## Single-fetch approach — chosen: shared provider (not a cached promise)

The plan offered a shared provider OR a module-level cached/in-flight promise. **The
provider was chosen** because the requirement is two-fold: single fetch AND single
hydration writer. A cached promise fixes the duplicate `GET` but leaves *two* hydration
effects (one per mount), both of which would attempt the D-13 stale correction and each
dispatch `shopcontext:change` on mount. The provider centralises both concerns in one
instance, is directly testable (the two new WR-06 cases assert it), and — rendering no DOM
— does not touch the responsive-shell boundary. Cost: one new ~110-line file plus a
two-line wrap in `dashboard-shell.tsx`; the switcher shrank (its fetch + local selection
state + hydration effect moved to the provider).

## Falsifiability (MANDATORY gate)

The switcher tests were deliberately re-pointed to mock the **apiClient + session seam**,
NOT `fetchMyShops` — the previous spec mocked `fetchMyShops` directly and therefore *could
not* observe that the old `fetchMyShops` derived GROUP_ADMIN from a browser JWT parse. Run
against the pre-fix `shops-api.ts` + `shop-switcher.tsx` (session resolves to a
non-realm-admin), **5 of 11** cases were RED:

```
✕ defaults a GROUP_ADMIN to the 'All shops' context (D-06)
✕ lands a server-side GROUP_ADMIN who is NOT a realm admin on 'All shops' and never pins them to the first shop (CR-08)
✕ does not silently persist a shop pin on a clean first load (no setShopContext write)   [pre-fix: setItem('shopContext', 'shop-a')]
✕ shows the 'apply to all shops' action ONLY for a GROUP_ADMIN in the 'All shops' context (D-08)
✕ degrades a stale/revoked saved selection … (D-13)   [pre-fix select.value: "shop-a", expected "all"]
Tests: 5 failed, 6 passed, 11 total
```

The Task 3 staff cases were RED against the committed pre-fix page (restored via a targeted
`git checkout HEAD -- …/staff/page.tsx`, then restored from a scratch copy):

```
✕ warns that a grant belongs to the signed-in user (self-downgrade, D-11)   [email-based isSelf, no session email → never renders]
✕ renders the self-revoke warning by userId even with no session email (WR-12)
✕ shows downgrade-specific 409 copy on the grant path, distinct from the revoke path (IN-02)
✕ states the real revocation-timing bound, not unqualified immediacy (23-11)
Tests: 4 failed, 6 passed, 10 total
```

All go GREEN post-fix.

## Verification

- `cd frontend && npx jest components/dashboard hooks app/dashboard` — **156 passed / 20 suites** (23-07's consumer-page narrowing + the 375px dashboard-shell chrome specs still green).
- `cd frontend && npm run build` — **Compiled successfully** (the tsc gate that jest does not run — verified after every task, not just at the end).
- `grep -c "decodeJwtPayload\|isGroupAdminFromSession" frontend/lib/shops-api.ts` → **0**.
- `grep -rc "isGroupAdminFromSession" frontend/` → **0** everywhere.
- `grep -c "session.user.email\|sessionEmail" frontend/app/dashboard/staff/page.tsx` → **0** (and `useSession` → 0).
- `grep -c "useState<string>(ALL_SHOPS_CONTEXT)\|useState(ALL_SHOPS_CONTEXT)" shop-switcher.tsx` → **0** (selection is no longer local state).

## Deferred with reason

- **375px Playwright (`dashboard-mobile.spec`) — deferred to the phase PR / a credentialed
  session.** The full compose stack IS running (frontend :3000, core :9090, Keycloak up),
  but the spec performs a REAL Keycloak vendor login and `E2E_VENDOR_PASSWORD` is not
  available in this session (the login reaches the `jtoye-dev` realm login page but the
  submit does not authenticate; the password is never committed — same blocker recorded for
  23-07 + the webhook specs). Additionally, port 3000 serves the pre-change Docker image, so
  a meaningful run would need a frontend rebuild first. This plan changed **only** the
  authority source and selection state — the 375px markup (classes, `data-testid`,
  `variant` prop, the mobile top-bar div) is byte-for-byte unchanged, MOBL-01 was live
  browser-verified in 23-05, and the unit-level MOBL-01 assertion
  (`dashboard-shell.test.tsx` "mounts the shop-context switcher in the md:hidden mobile top
  bar (375px chrome)") passes.
- **IN-01 — `fetchMyShops` hard-codes `size=200`.** A tenant with >200 shops gets a
  truncated list, and a valid saved selection is then misclassified as stale. The real fix
  is a dedicated unpaginated `/api/v1/shops/mine` endpoint — new API surface out of
  proportion to this gap-closure run, and >200 shops per tenant is not a current scenario.
  Left as-is; documented in the `fetchMyShops` javadoc.

## Scope note — `decodeJwtPayload` name collision (out of scope)

Two unrelated `decodeJwtPayload` functions remain in `frontend/app/api/customer-auth/session/route.ts`
and `frontend/lib/customer-auth.ts`. These are module-private OIDC `id_token`-claim parsers
in the **customer** login flow — a different function, a different purpose (not an
authorization display decision), and outside this plan's scope. The load-bearing signal,
`isGroupAdminFromSession`, is 0 everywhere.

## Threat register outcome

| Threat ID | Disposition | Status |
|-----------|-------------|--------|
| T-23-13-01 (client JWT parse for an authZ display decision) | mitigate | closed — helper deleted; server answers via `/api/v1/staff/me` |
| T-23-13-02 (silent shop pinning removes the cross-shop view) | mitigate | closed — fallback no longer persists an unchosen narrowing |
| T-23-13-03 (email-based self-identification) | mitigate | closed — compared on the Keycloak `sub` |
| T-23-13-04 (directory PII in the picker) | transfer | consumed the masked form (23-12); no full-email dependency reintroduced |
| T-23-13-05 (copy claiming immediate revocation) | mitigate | closed — copy states the true 5-minute bound |
| T-23-13-SC (npm installs) | mitigate | N/A — no new dependencies |

## Test-count delta (for the 23-15 phase-gate reconcile)

Net new Jest cases from this plan: **+7** (shop-switcher: +2 CR-08/no-pin +2 WR-06
sync/single-fetch; staff-page: +3 WR-12/IN-02/timing). `docs/metrics.json` +
`docs-freshness --write` are reconciled at the phase gate (23-15), consistent with
23-08..23-12.

## Requirement status

**VSA-03 and VSA-04 remain NOT marked complete** (anti-false-green, consistent with every
prior gap plan in this phase). This plan closes the CR-08/WR-06/WR-12/IN-02 frontend
findings, but 23-14 (strict-scoping correctness, the richer JIT-row treatment) and 23-15
(OpenAPI/docs phase gate) still contribute, and the phase `23-VERIFICATION` status stays
`gaps_found` until the gate.

## Known Stubs

- **`"Unlisted member"`** label for a grant row with no directory entry
  (`staff/page.tsx`). Intentional and minimal — plan **23-14** owns the richer
  "auto-granted on first sign-in" JIT-row treatment. Not a data stub: grants still key on
  `userId` and render/ revoke correctly; only the display label is a placeholder for the
  no-directory-entry case.

## Self-Check: PASSED
