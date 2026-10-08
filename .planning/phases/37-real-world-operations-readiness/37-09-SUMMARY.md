---
phase: 37-real-world-operations-readiness
plan: 09
subsystem: ui
tags: [d-07, d-26, rwo-004, rwo-003, staff-invite, accept-page, fragment-token, referrer-policy, noindex, nextauth-login-hint, staff-me, jest, playwright]

requires:
  - phase: 37-08
    provides: "POST /api/v1/public/staff-invites/preview {ref} and /accept {ref, firstName, lastName, password}; the link {base}#token={tenantId}.{token}; one byte-identical 404; 409 other business; 422 with Keycloak's message"
  - phase: 37-07
    provides: "POST/GET /api/v1/staff/invites, POST /{id}/resend, DELETE /{id}; StaffInviteDto with server-computed status"
  - phase: 37-06
    provides: "ConfirmActionDialog with a required cancelLabel; the People card; ROLE_LABELS"
  - phase: 37-01
    provides: "D-26 owner answer 'login-hint'"
provides:
  - "InviteStaffForm (email, role with hints, shop or All shops; Group admin locks the shop) and PendingInvitesTable (OPEN and EXPIRED only, UK-time expiry, resend, destructive cancel with 'Keep invitation') on the Staff page"
  - "/invite accept page: fragment read once and dropped, preview + accept POSTed in JSON bodies, checking / new / existing / unusable / other-business / retry states, uncontrolled password, Keycloak's 422 verbatim, then signIn('keycloak', {callbackUrl: '/dashboard?joined=1'}, {login_hint})"
  - "Token hygiene: noindex/nofollow with no canonical, robots DISALLOW /invite, Referrer-Policy no-referrer on /invite/:path* (served, measured), no third-party load, zero web-storage writes"
  - "GET /api/v1/staff/me carries businessName (additive, nullable): the caller's own tenant name"
  - "Dashboard ?joined=1 one-time toast from staff/me and the grant-scoped shop list; the parameter is dropped first"
  - "e2e/staff-invite.spec.ts: the live journey with an instrument arm, run at 37-15"
affects: [37-15-runtime-gate, 37-06-no-access-page-business-name, phase-pr-docs-metrics, gsd-secure-phase]

actuals:
  tokens: 35000
  tasks: 3
  commits: 10
plan_head_before: e9a7ec7afc2da2248b10bb17732ce3a06b8c39f0
plan_head_after: 7522879324ace66d09e6e82dd990666f6cfdee87

tech-stack:
  added: []
  patterns:
    - "A secret-bearing page reads its credential from the URL fragment once, strips it with replaceState, holds it in a ref and POSTs it in a JSON body; its route gets Referrer-Policy no-referrer from a next.config headers() entry placed AFTER the '/:path*' default (Next applies the last entry for a key)"
    - "A password field is UNCONTROLLED (read from a ref at submit), so the value is never React state, never a value attribute, never in a message"
    - "A refused-link state is a component that takes only constant copy, so no server detail, status or ref can reach the page; the test renders three different 404s and compares container HTML"
    - "A post-sign-in welcome is triggered by a URL parameter but worded only from server reads (staff/me + shops); the parameter is dropped before the reads"
    - "A brand-new route gets valid RED by committing an inert scaffold first (chore), so the test fails on assertions, not on a missing module"

key-files:
  created:
    - frontend/components/dashboard/staff/invite-staff-form.tsx
    - frontend/components/dashboard/staff/pending-invites-table.tsx
    - frontend/app/dashboard/__tests__/staff-invites.test.tsx
    - frontend/app/invite/page.tsx
    - frontend/app/invite/invite-client.tsx
    - frontend/app/invite/__tests__/invite-client.test.tsx
    - frontend/lib/joined-toast.ts
    - frontend/app/dashboard/__tests__/joined-toast.test.tsx
    - frontend/e2e/staff-invite.spec.ts
    - .planning/phases/37-real-world-operations-readiness/evidence/37-09-red-invite-form.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-09-red-invite-page.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-09-red-staff-me-business.json
  modified:
    - frontend/lib/staff-api.ts
    - frontend/lib/shops-api.ts
    - frontend/app/dashboard/staff/page.tsx
    - frontend/app/dashboard/page.tsx
    - frontend/app/dashboard/__tests__/staff-page.test.tsx
    - frontend/app/robots.ts
    - frontend/next.config.mjs
    - frontend/__tests__/header-snapshot.test.ts
    - frontend/__tests__/__snapshots__/header-snapshot.test.ts.snap
    - frontend/__tests__/csp-headers.test.ts
    - frontend/__tests__/link-graph.test.ts
    - frontend/e2e/dashboard-interface-corrections.spec.ts
    - scripts/gates/ssr-routes.conf
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/MyAccessDto.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StaffEffectiveAccessIntegrationTest.java
    - docs/api/openapi-snapshot.json
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md
    - .planning/WINDOWS.md

key-decisions:
  - "The accept page is /invite, not /invite/[token]: 37-08 put the token in the URL fragment and the API takes the ref in a JSON body (binding instruction, WINDOWS.md entry 21, now closed). The page reads location.hash, replaceState('/invite'), and POSTs {ref}"
  - "GET /api/v1/staff/me gained a nullable businessName (the caller's own tenant name, read by id): the joined toast must be built from server data and no endpoint a staff member can read named the business; additive, OpenAPI +7/-0 (WINDOWS.md entry 24). The URL parameter stays a trigger only, because a parameter that carried words would let any link make the dashboard claim a business"
  - "The password input is uncontrolled so React never syncs a value attribute into the DOM; the 422 shows Keycloak's detail verbatim and no client-side rule is shown (the realm's generic 'Password policy not met' is the only policy message)"
  - "A 200 from POST /api/v1/staff/invites (the idempotent replay) toasts 'An invitation to {email} is already open. It expires on {date, time}.' rather than the 'sent' copy: the server sent no email"
  - "Native <select> controls, the Staff page's existing idiom, rather than the Radix Select the UI-SPEC inventory lists: the grant form beside it already uses native selects, and a disabled native select is the plain mirror of the Group-admin rule"
  - "The existing Grant access card stays between Invite someone and People: UI-SPEC's page order names the three new/renamed cards; the grant form is preserved (Incremental Betterment) and People's 'Grant access' button still scrolls to it"

patterns-established:
  - "Token page: fragment -> ref -> replaceState -> JSON-body POST -> constant-copy refusal -> Referrer-Policy no-referrer after the default -> robots DISALLOW -> ssr-routes STATIC -> link-graph allowlist"

requirements-completed: [RWO-004, RWO-003]

coverage:
  - id: D1
    description: "Staff page: 'Invite someone' (POST {email, role, shopId}; Group admin locks the shop to All shops with the hint; empty email and 409/400/404 inline as role=alert; UK-time toast; 200 replay says 'already open') and 'Pending invitations' (OPEN + EXPIRED only, expired greyed with 'Expired', resend, cancel confirm with 'Keep invitation', hidden when none), in the order h1, Invite someone, People, Pending invitations"
    requirement: "RWO-004"
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/__tests__/staff-invites.test.tsx: RED 12/13 on 6fef2937 (RED_EVIDENCE_OK, evidence/37-09-red-invite-form.json), GREEN on cd0dd9a0; with staff-page.test.tsx 45/45. Break arms on cd0dd9a0: shop select left enabled -> 1 red ('locks the shop to All shops for Group admin…', 'Received element is not disabled'); pending filter removed -> 3 red (incl. the absence test that was vacuously green at RED); both restored by sha256 (3018d443…, 1d0e60ec…); closing clean 45/45"
        status: pass
    human_judgment: false
  - id: D2
    description: "/invite accept page: fragment read once and dropped, preview POSTed once (Strict Mode), checking role=status, new-account form (read-only email, given/family name, new-password with a 44px aria-pressed toggle), field-level empty refusals with focus and no request, single accept POST with 'Creating your account…', Keycloak's 422 verbatim under the password with aria-invalid and focus, one unusable state for every 404, other-business from accountState or 409, EXISTS_HERE 'Sign in to accept', retry on a non-answer; then signIn('keycloak', {callbackUrl: '/dashboard?joined=1'}, {login_hint: email}); no storage writes; the ref and password never in the DOM"
    requirement: "RWO-004"
    verification:
      - kind: unit
        ref: "frontend/app/invite/__tests__/invite-client.test.tsx: RED 23/23 on d0c248a1 over the inert scaffold 023526f7 (RED_EVIDENCE_OK, evidence/37-09-red-invite-page.json), GREEN 23/23 on 3d1565c5. Break arms on 31598121, each restored by sha256 (37c02c4c…): unusable body varies with detail -> 'renders identical HTML…' red; a localStorage.setItem -> the storage-spy test red and the source-only rg rc=0; password appended to the 422 message -> 'never the password' red; login_hint dropped -> 2 red; closing clean 26/26 (with header-snapshot)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Token hygiene at the route: Referrer-Policy no-referrer on /invite/:path* placed after the '/:path*' default, noindex/nofollow with no canonical or Open Graph, robots DISALLOW /invite, STATIC in ssr-routes.conf, /invite in the link-graph allowlist, no third-party origin in the served HTML, and the route's shipped source names no web-storage API"
    requirement: "RWO-004"
    verification:
      - kind: unit
        ref: "header-snapshot.test.ts + csp-headers.test.ts: both red first on the new config (snapshot mismatch; 'returns a single route'), then updated, 23/23; order arm (invite entry moved before the default) -> the order test and the snapshot red, restored by sha256 (8f5cce08…)"
        status: pass
      - kind: other
        ref: "next start of the 3d1565c5 build on :3999: GET /invite -> 200, Referrer-Policy: no-referrer; control /for-operators -> strict-origin-when-cross-origin; /robots.txt has 'Disallow: /invite'; served HTML: <meta name=\"robots\" content=\"noindex, nofollow\"/>, title 'Your invitation — J'Toye', 0 rel=canonical, 0 absolute http(s) src/href; .next/routes-manifest.json: the /invite/:path* regex matches '/invite'"
        status: pass
      - kind: other
        ref: "scripts/check-ssr-coverage-contract.sh PASS 42 routes; arm (STATIC invite line removed) -> rc=1 'R-1 undeclared page: invite/page.tsx', restored by sha256 (9d5c8009…). link-graph.test.ts 4/4; arm (allowlist entry removed) -> 1 red naming '/invite <- app/invite/page.tsx', restored by sha256. Plan criterion rg -uu 'localStorage|sessionStorage' frontend/app/invite: rc=0 on the correct tree (the test must name both APIs to spy on them) -> replaced by the source-only form with __tests__ excluded: rc=1 [] after rewording two comments (31598121); fail direction rc=0 with an injected setItem"
        status: pass
    human_judgment: false
  - id: D4
    description: "GET /api/v1/staff/me names the caller's own business (businessName, nullable, additive) for a group admin, a scoped user and an ungranted user, with another tenant present"
    requirement: "RWO-003"
    verification:
      - kind: integration
        ref: "StaffEffectiveAccessIntegrationTest#37-09: staff/me carries the caller's own business name…: RED 1/10 on 5ef4c1fc (RED_EVIDENCE_OK, junit, evidence/37-09-red-staff-me-business.json; body had no businessName), GREEN 10/0 on e78ec377; OpenApiSnapshotTest red before regeneration, then +7/-0 (one MyAccessDto property) and green"
        status: pass
    human_judgment: false
  - id: D5
    description: "Dashboard ?joined=1: drops the parameter first (keeping others), then toasts 'You've joined {business}. You can work on {shop names | all shops}.' from staff/me + the grant-scoped shops only; no access -> no toast; no parameter -> no staff/me call"
    requirement: "RWO-004"
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/__tests__/joined-toast.test.tsx 6/6 (+ page.test.tsx 17/17). Arms on 75228793, restored by sha256: scope always 'all shops' -> 3 red; replaceState removed -> 3 red; closing clean 6/6"
        status: pass
    human_judgment: false
  - id: D6
    description: "The live journey: invite through the Staff form, link from Mailhog, only the app/API/Keycloak contacted and no /invite Referer, account created, Keycloak sign-in with the address pre-filled, the toast on /dashboard, 'Shop manager · {shop}' on the Staff page (instrument arm must fail), second visit unusable"
    requirement: "RWO-004"
    verification:
      - kind: e2e
        ref: "npx playwright test e2e/staff-invite.spec.ts --list -> 1 test in 1 file (rc=0); arm (syntax broken) -> rc=1 'No tests found'"
        status: pass
    human_judgment: true
    rationale: "Listed only. The live RED (pre-rebuild runtime: no 'Invite someone' card) and GREEN are owed to the 37-15 rebuild together with the realm re-import (WINDOWS.md entries 20 and 22); the inverted dashboard-interface-corrections test 2 likewise (entry 23)."
  - id: D7
    description: "'Join {business} on J'Toye' wraps inside the max-w-md card for a 60-character business name with no horizontal overflow at 375px"
    verification:
      - kind: unit
        ref: "invite-client.test.tsx 'lets the heading wrap anywhere' — the h1 carries [overflow-wrap:anywhere] for a 60-character name (jsdom cannot measure layout)"
        status: pass
    human_judgment: true
    rationale: "The backstop is a layout measurement; it is written into e2e/staff-invite.spec.ts (scrollWidth - clientWidth <= 0 at 375px) and runs at 37-15 (WINDOWS.md entry 22)."
  - id: D8
    description: "No regression"
    verification:
      - kind: unit
        ref: "75228793: full jest 200 suites / 2313 tests / 0 failed; npx tsc --noEmit rc=0; npm run lint 0 errors (warnings pre-existing); npm run build rc=0 with ƒ /invite"
        status: pass
      - kind: integration
        ref: "75228793: core-java :test 188 files / 1610 / 0 failed / 1 skipped; :integrationTest 182 files / 947 / 0 failed / 1 skipped (37-08 closed at 946; +1 is D4's method); scripts/check-doc-citations.sh PASS 29 verified"
        status: pass
    human_judgment: false

duration: 55min
completed: 2026-10-08
status: complete
---

# Phase 37 Plan 09: Staff invite UI and the invitation accept page Summary

**A Group admin now invites from the Staff page ("Invite someone", with the Group-admin shop lock) and sees every open or expired link in "Pending invitations" with resend and a guarded cancel; the invitee opens `/invite#token=…`, which drops the token from the address bar, POSTs it in a JSON body, serves `Referrer-Policy: no-referrer`, writes nothing to web storage, shows one unusable state for every dead link, renders Keycloak's password refusal verbatim, and after the account is created starts the ordinary Keycloak sign-in with the invited address as `login_hint`; the dashboard then welcomes them once, from `staff/me` (which now names the business) and their shops.**

## Performance

- **Duration:** ~55 min
- **Started:** 2026-10-08T15:55:42Z
- **Completed:** 2026-10-08T16:51:05Z
- **Tasks:** 3
- **Files modified:** 31 (12 created, 19 modified), plus this summary

## Accomplishments

- **Task 1 (tracer).** `staff-api` invite calls (create reports 201 vs the idempotent 200), `InviteStaffForm`, `PendingInvitesTable`, the Staff page in the order h1, Invite someone, Grant access (kept), People, Pending invitations, a skeleton that holds the new first card, and a failed invitation read shown as unknown rather than "none". Tracer gate: interactive, `end-of-phase`, automated-only verify, re-run 45/45, expanded.
- **Task 2.** `/invite` (page + island), robots, the no-referrer header (ordered after the default and asserted against Next's own matcher), the SSR manifest entry and the link-graph allowlist; proven on a `next start` of the built tree.
- **Task 3.** `staff/me` `businessName` (server, test-first), the dashboard's one-time joined toast, and the live `e2e/staff-invite.spec.ts` with an instrument arm.

## Task Commits

1. **Task 1: invite form and pending invitations**
   - `6fef2937` test(37-09): failing test (RED 12/13, RED_EVIDENCE_OK)
   - `cd0dd9a0` feat(37-09): the form, the table, the page
   - `9c008479` test(37-09): the staff e2e now requires the invite control it used to forbid
2. **Task 2: the accept page, with token hygiene**
   - `023526f7` chore(37-09): inert scaffold (so RED fails on assertions)
   - `d0c248a1` test(37-09): failing tests (RED 23/23, RED_EVIDENCE_OK)
   - `3d1565c5` feat(37-09): the page, header, robots, gates
   - `31598121` refactor(37-09): the route's source names no web-storage API
3. **Task 3: the joined toast and the live spec**
   - `5ef4c1fc` test(37-09): staff/me must name the caller's own business (RED 1/10, RED_EVIDENCE_OK, junit)
   - `e78ec377` feat(37-09): staff/me names the caller's own business (+ OpenAPI)
   - `75228793` feat(37-09): the joined welcome and `e2e/staff-invite.spec.ts`

**Plan metadata:** the docs commit that adds this summary, the evidence files, deferred-items §8 and WINDOWS.md entries 22-24 (21 closed).

## Files Created/Modified

- `frontend/components/dashboard/staff/invite-staff-form.tsx` - the B1 invite card
- `frontend/components/dashboard/staff/pending-invites-table.tsx` - the B1 pending card, UK-time expiry, cancel confirm
- `frontend/app/invite/page.tsx`, `invite-client.tsx` - the B2 accept page
- `frontend/lib/joined-toast.ts` - the joined toast wording from server data
- `frontend/lib/staff-api.ts`, `frontend/lib/shops-api.ts` - invite calls, `ROLE_HINTS`, `MyAccess.businessName`
- `frontend/app/dashboard/staff/page.tsx`, `frontend/app/dashboard/page.tsx` - mounting both
- `frontend/next.config.mjs`, `frontend/app/robots.ts` - no-referrer on /invite, DISALLOW /invite
- `core-java/.../MyAccessDto.java`, `StaffManagementService.java`, `docs/api/openapi-snapshot.json` - `businessName` on staff/me
- `frontend/e2e/staff-invite.spec.ts` - the live journey for 37-15
- tests: `staff-invites.test.tsx`, `invite-client.test.tsx`, `joined-toast.test.tsx`, `staff-page.test.tsx`, `header-snapshot.test.ts` (+ snap), `csp-headers.test.ts`, `link-graph.test.ts`, `StaffEffectiveAccessIntegrationTest.java`, `dashboard-interface-corrections.spec.ts`
- `scripts/gates/ssr-routes.conf` - `STATIC invite/page.tsx`

## Decisions Made

See `key-decisions` in the frontmatter. The two with consequences outside this plan: `/invite` instead of `/invite/[token]` (it follows 37-08's contract), and the additive `businessName` on `staff/me` (37-15 must rebuild core-java for the toast to name the business; until then it reads "your new business").

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The route and API wording were superseded by 37-08**
- **Found during:** before Task 2 (binding instruction: deferred-items §7, WINDOWS.md entry 21)
- **Issue:** the plan's `/invite/[token]` route, `GET /{ref}` and `POST /{ref}/accept` no longer exist; the link is `{base}#token={tenantId}.{token}` and the API is `POST .../preview {ref}` and `POST .../accept {ref, …}`.
- **Fix:** the page is `frontend/app/invite/page.tsx` + `invite-client.tsx` (files_modified paths `app/invite/[token]/…` became `app/invite/…`); it reads `location.hash`, `replaceState`s to `/invite`, and POSTs the ref. The header source stayed `/invite/:path*`, which Next's matcher applies to `/invite` (asserted). WINDOWS.md entry 21 is closed.
- **Committed in:** `3d1565c5`

**2. [Rule 2 - Missing critical] `staff/me` could not name the business**
- **Found during:** Task 3
- **Issue:** the must-have toast "You've joined {business}" is to be built from `GET /api/v1/staff/me` and the shops list; neither, nor the session, carries the business name (the same gap 37-06 recorded as WINDOWS.md entry 18).
- **Fix:** an additive, nullable `MyAccessDto.businessName` read by tenant id (`TenantRepository`), test-first; OpenAPI +7/-0. Recorded as WINDOWS.md entry 24.
- **Files modified:** `MyAccessDto.java`, `StaffManagementService.java`, `StaffEffectiveAccessIntegrationTest.java`, `openapi-snapshot.json`, `frontend/lib/shops-api.ts`
- **Committed in:** `5ef4c1fc`, `e78ec377`

**3. [Rule 3 - Blocking] Existing gates and tests that a new route and new controls break**
- **Found during:** Tasks 1 and 2
- **Issue:** `staff-page.test.tsx` queried `getByLabelText(/^shop$/i)` / `/^role$/i` page-wide (the invite form adds a second Role and Shop) and counted two skeleton cards; `csp-headers.test.ts` asserted exactly one headers entry; `ssr-routes.conf` and `link-graph.test.ts` fail on an undeclared or orphan route; `dashboard-interface-corrections.spec.ts` test 2 asserted zero invite buttons.
- **Fix:** label queries scoped to the grant card, skeleton count 3; csp test asserts the default first plus only the /invite override; STATIC entry and allowlist entry (each shown failing without them); e2e test 2 inverted to require exactly one "Send invitation" (recorded as WINDOWS.md entry 23, not run).
- **Committed in:** `cd0dd9a0`, `3d1565c5`, `9c008479`

**4. [Rule 2 - Missing critical] States the UI-SPEC copy does not cover**
- **Found during:** Tasks 1 and 2
- **Issue:** the invite POST's 200 replay sends no email, so "Invitation sent" would be false; a preview that gets no answer (network, 503 with the admin seam off) and accept refusals 429/503/400 had no copy.
- **Fix:** "An invitation to {email} is already open. It expires on {date, time}."; a retry state "We couldn't check your invitation just now. Your link still works…"; inline messages for 429/503/400 that say the link still works. None is a client-side password rule.
- **Committed in:** `cd0dd9a0`, `3d1565c5`

**5. [Criterion replaced - unfalsifiable as written] The storage grep**
- **Issue:** `rg -uu -n 'localStorage|sessionStorage' frontend/app/invite` (expected rc 1) is rc 0 on the correct tree, because the test must name both APIs to spy on them (an expected-0 that is 1 on the correct tree).
- **Fix:** kept the original's output on record and replaced it with the source-only form `--glob '!**/__tests__/**'` (rc 1, empty) after rewording two doc comments, plus the runtime spy test (zero `setItem` calls, both storages empty). Fail direction: an injected `localStorage.setItem` turns rg to rc 0 and the spy test red.
- **Committed in:** `31598121`

**6. [Rule 3 - Blocking] An inert scaffold commit before Task 2's RED**
- **Issue:** a test importing a module that does not exist fails as a load error, which is INVALID_RED.
- **Fix:** `023526f7` (chore) added `page.tsx`/`invite-client.tsx` rendering nothing; the RED then failed 23/23 on assertions.

---

**Total deviations:** 6 (2 blocking per Rule 3, 3 per Rule 2 incl. the server field, 1 criterion replacement; plus the scaffold)
**Impact on plan:** All necessary to follow 37-08's contract, keep the copy true, and keep existing gates green. One scope addition outside the frontend (the `staff/me` field), additive and test-first.

## Issues Encountered

- `jest.requireActual` instead of `require` for Next's compiled `path-to-regexp` in the header test (the `no-require-imports` rule).
- The plan's MVP+TDD gate passed at every GREEN: a `test(37-09)` commit with RED_EVIDENCE_OK preceded each `feat(37-09)`.

## TDD Gate Compliance

| Task | RED | GREEN | REFACTOR |
|------|-----|-------|----------|
| 1 | `6fef2937` (12/13, RED_EVIDENCE_OK, TAP) | `cd0dd9a0` | none |
| 2 | `d0c248a1` (23/23, RED_EVIDENCE_OK, TAP, over scaffold `023526f7`) | `3d1565c5` | `31598121` (comments only; 23/23 after) |
| 3 (server field) | `5ef4c1fc` (1/10, RED_EVIDENCE_OK, JUnit) | `e78ec377` | none |

Task 3's frontend half (`type="auto"`, not TDD) shipped with its test in `75228793` and two break arms.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: data-exposure | core-java/src/main/java/uk/jtoye/core/security/access/dto/MyAccessDto.java | `staff/me` now returns the caller's own business name to any authenticated caller of that tenant, including one with no shop access. Read by the token's tenant id only, so no other tenant's name can be returned; not in the plan's threat model, recorded for `/gsd-secure-phase` |

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- **37-15:** rebuild core-java and the frontend, re-import the realm (entry 20), then run `e2e/staff-invite.spec.ts` (RED on the pre-rebuild runtime, GREEN after) and the inverted `dashboard-interface-corrections.spec.ts` test 2 (WINDOWS.md entries 22, 23), with `scripts/check-runtime-freshness.sh`.
- **B3:** `MyAccess.businessName` is now available for the no-access page's "{business}" (WINDOWS.md entry 18).
- **Before the phase PR:** docs metrics (deferred-items §1, §8).

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-08*

## Self-Check: PASSED

- Created files exist (all 12 FOUND by `git ls-files --error-unmatch`)
- Task commits on this branch: 6fef2937, cd0dd9a0, 9c008479, 023526f7, d0c248a1, 3d1565c5, 31598121, 5ef4c1fc, e78ec377, 75228793 (all FOUND by `git merge-base --is-ancestor`; `git rev-list --count e9a7ec7a..75228793` = 10; `gsd_run check evaluation-scope --plan 37-09 --commits-only` resolved)
- Acceptance re-run: staff-invites + staff-page 45/45; invite + header/csp + link-graph + sitemap-robots 53/53; joined-toast + page 23/23; build rc=0; spec listed; every break arm red and restored by sha256 with a closing clean run
