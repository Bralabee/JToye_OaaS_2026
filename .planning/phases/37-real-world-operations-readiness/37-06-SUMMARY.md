---
phase: 37-real-world-operations-readiness
plan: 06
subsystem: ui
tags: [d-08, d-09, rwo-003, rwo-004, uxt-003, staff-page, effective-access, no-access-page, confirm-dialog, contrast, nextjs, jest]

requires:
  - phase: 37-05
    provides: "GET /api/v1/staff people[] with server-computed EffectiveAccess (GROUP_ADMIN | REALM_ADMIN | SHOP_ROLES | NONE, bootstrapAdmin, tenantWideRole, perShopRole, realmAdminSeenAt); MyAccessDto.tenantWideRole"
  - phase: 37-04
    provides: "strict scoping ON by default (D-06), so automatic grants are inert unless the server says otherwise"
provides:
  - "EffectiveAccessCell: renders people[].effectiveAccess verbatim; 'Not recorded' when the server sent none (T-37-12)"
  - "Staff page People card (UI-SPEC B1): Person / Access / Actions, No access + 'Grant access' pre-fill, one Remove per grant, mobile stacking, LoadErrorPanel"
  - "NoAccessPage + DashboardAccessProvider (UI-SPEC B3): replaces page content when staff/me grants nothing; 'no longer' only after access was observed this session"
  - "api-client dispatches jtoye:shop-access-denied (lib/access-events.ts) on the typed 403"
  - "ConfirmActionDialog at components/dashboard/confirm-action-dialog.tsx: required cancelLabel, Button default variant for non-destructive confirms; old webhooks path re-exports"
  - "remainingAccessLines: the Remove access confirm names what remains"
affects: [37-07-invites, 37-15-runtime-gate, 37-18-kitchen-k2, B4-void, D1-reject, E1-credentials]

actuals:
  tokens: 33200
  tasks: 3
  commits: 6
plan_head_before: 31ece7742b1248dc830129296e9ddb1906175cfa
plan_head_after: 034328bd9c420133b1b422efa9733130213cfc4d

tech-stack:
  added: []
  patterns:
    - "Access shown to a person is the server's value formatted, never rebuilt from grant rows; a missing value reads 'Not recorded'"
    - "A refused dashboard read is a window event from the api-client; the shell decides only on a fresh staff/me answer, one re-read at a time"
    - "A claim that something was taken away needs an in-memory observation from this session (no storage key)"
    - "Confirm dialogs carry a required verb+noun cancel label; non-destructive confirms use the Button default variant"
    - "Jest RED evidence via a local TAP reporter (scratchpad, not committed) that serialises the real run for the GSD classifier"

key-files:
  created:
    - frontend/components/dashboard/staff/effective-access-cell.tsx
    - frontend/components/dashboard/no-access-page.tsx
    - frontend/components/dashboard/dashboard-access.tsx
    - frontend/components/dashboard/confirm-action-dialog.tsx
    - frontend/lib/access-events.ts
    - frontend/components/dashboard/__tests__/no-access-page.test.tsx
    - frontend/components/dashboard/__tests__/confirm-action-dialog.test.tsx
    - .planning/phases/37-real-world-operations-readiness/evidence/37-06-red-people-card.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-06-red-no-access-page.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-06-red-remove-confirm.json
  modified:
    - frontend/app/dashboard/staff/page.tsx
    - frontend/lib/staff-api.ts
    - frontend/lib/shops-api.ts
    - frontend/lib/api-client.ts
    - frontend/components/dashboard/shop-switcher-provider.tsx
    - frontend/components/dashboard/dashboard-shell.tsx
    - frontend/components/dashboard/sidebar.tsx
    - frontend/components/dashboard/webhooks/ConfirmActionDialog.tsx
    - frontend/app/dashboard/webhooks/page.tsx
    - frontend/app/dashboard/webhooks/[id]/page.tsx
    - frontend/app/dashboard/__tests__/staff-page.test.tsx
    - frontend/app/dashboard/webhooks/__tests__/webhooks-page.test.tsx
    - frontend/app/dashboard/webhooks/__tests__/delivery-log.test.tsx
    - frontend/lib/__tests__/api-client-interceptors.test.ts
    - frontend/e2e/dashboard-interface-corrections.spec.ts
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md
    - .planning/WINDOWS.md

key-decisions:
  - "The Team directory card is folded into the People card: every directory row is a People row and keeps its last-seen line, so the page is two cards (Grant access, People), as UI-SPEC B1's page order lists; the skeleton follows"
  - "The no-access decision reads the staff/me answer the shell already fetches for the shop switchers (fetchMyShops now returns it), so no second staff/me request per dashboard load (WR-06)"
  - "A refused read signals the shell through a window event from the api-client interceptor; the shell re-reads staff/me and shows the page only on that fresh answer, so a manager refused one shop keeps the dashboard"
  - "While the no-access page shows, every nav link, the shop switcher and the mobile tab bar are hidden: each route reads tenant data behind the shop/group gate, and the dashboard home shows the same page; Sign out stays in the sidebar and on the page"
  - "'Grant access' on a No access row pre-fills the existing grant form and focuses the Shop select; a person with no directory row is added to the picker for that pre-fill"
  - "Remove access shows only on rows with access; a No access row offers only Grant. An inert automatic grant is therefore not offered as removable access"
  - "The Remove confirm's 'what remains' starts from the server's effective access and only takes away what that grant confers; it adds nothing, and makes no claim when the server value is missing"
  - "Replay's cancel label is 'Skip replay' (UI-SPEC lists no label for it); Rotate 'Keep current secret', Revoke 'Keep endpoint', Remove 'Keep access'"

patterns-established:
  - "New dashboard confirms import ConfirmActionDialog from components/dashboard/confirm-action-dialog.tsx and must pass cancelLabel (the build fails without it)"
  - "Any dashboard surface that must react to access loss listens through DashboardAccessProvider, not its own staff/me fetch"

requirements-completed: [RWO-003, RWO-004]

coverage:
  - id: D1
    description: "People card renders people[].effectiveAccess only: Group admin · all shops (+ bootstrap line), Admin account (+ realm admin line), one line per shop, '{Role} · all shops', outline 'No access' badge, Integration account; no automatic-grant badge"
    requirement: "RWO-003"
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/__tests__/staff-page.test.tsx#People card: server-computed effective access (D-09): RED on 7955f99c (RED_EVIDENCE_OK, evidence/37-06-red-people-card.json), GREEN on 916d101a, 32/32 at 034328bd"
        status: pass
    human_judgment: false
  - id: D2
    description: "Fail arm: a person with no effectiveAccess reads 'Not recorded'; a page deriving access from grant rows is caught (T-37-12)"
    requirement: "RWO-003"
    verification:
      - kind: unit
        ref: "staff-page.test.tsx#renders nothing derived when the server sends no effectiveAccess: clean 1/0; deriving arm 1 failed ('Unable to find an element with the text: Not recorded'); closing clean 30/30, sha256 restore OK"
        status: pass
    human_judgment: false
  - id: D3
    description: "'Grant access' (h-11) on a No access row pre-fills the grant form; after removing a last grant the row reads 'No access', never Group admin (UXT-003)"
    requirement: "RWO-004"
    verification:
      - kind: unit
        ref: "staff-page.test.tsx#offers 'Grant access' on a No access row… and #after removing a person's last grant the row reads 'No access'…"
        status: pass
    human_judgment: false
  - id: D4
    description: "NoAccessPage in the shell: 'You don't have access yet' on first load with none; 'You no longer have access' only after access was observed; Check access again (Checking…, alert on failure, page returns on access); Sign out reachable; nav hidden; kitchen excluded; undecided before staff/me answers"
    requirement: "RWO-004"
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/__tests__/no-access-page.test.tsx: RED on 2561c67b (RED_EVIDENCE_OK, evidence/37-06-red-no-access-page.json), GREEN 10/10 on b2952aa9; hard-coded-heading arm 4 failed incl. the fail-arm test; closing clean 10/10"
        status: pass
      - kind: unit
        ref: "frontend/lib/__tests__/api-client-interceptors.test.ts#shop-access-denied signal (D-08): dispatch case red at RED, green after; control red under an every-403 predicate arm; closing clean 15/15"
        status: pass
    human_judgment: false
  - id: D5
    description: "ConfirmActionDialog generalised: required cancelLabel (build fails without it), Button default variant for non-destructive confirms (orange-700), destructive stays destructive; webhooks pass verb+noun labels; Remove access names what remains or 'no access to this business'"
    verification:
      - kind: unit
        ref: "confirm-action-dialog.test.tsx 4/4; webhooks-page.test.tsx + delivery-log.test.tsx green; staff-page.test.tsx#Remove access confirm names the consequence: RED on 2362407b (RED_EVIDENCE_OK, evidence/37-06-red-remove-confirm.json), GREEN on 034328bd"
        status: pass
      - kind: other
        ref: "npm --prefix frontend run build: rc 0 at 034328bd; with cancelLabel removed from one webhooks call site rc 1, TS2741 'Property cancelLabel is missing'; closing clean rc 0"
        status: pass
    human_judgment: false
  - id: D6
    description: "Whole frontend still green"
    verification:
      - kind: unit
        ref: "npm --prefix frontend test -- --ci: 197 suites / 2270 tests / 0 failed at 034328bd; npm run lint 0 errors (32 warnings, none on a line this plan added)"
        status: pass
    human_judgment: false
  - id: D7
    description: "The page in a real browser: People table stacking at 375px, the no-access page at 375px with a long business name (B3 backstop), the two edited e2e assertions, visual review"
    verification: []
    human_judgment: true
    rationale: "The People column reads people[], which the running core-java does not serve until the 37-15 rebuild (deferred-items §4/§5); jsdom cannot measure layout. Recorded in .planning/WINDOWS.md entries 16 and 17"

duration: 19min
completed: 2026-10-08
status: complete
---

# Phase 37 Plan 06: Staff page shows server access; no-access page in the shell Summary

**The People card on the Staff page now shows each person's access exactly as the server computes it (37-05). "No access" rows offer "Grant access", which pre-fills the grant form. When staff/me grants nothing, the dashboard shell shows one honest no-access page, and it says "no longer" only after this session has seen access. ConfirmActionDialog is now the dashboard-wide confirm: its cancel label is required and its accent confirm meets contrast.**

## Performance

- **Duration:** 19 min
- **Started:** 2026-10-08T12:37:32Z
- **Completed:** 2026-10-08T12:56:47Z
- **Tasks:** 3 (all TDD, RED before GREEN)
- **Files modified:** 25 (10 created, 15 modified) plus 3 RED evidence records

## Accomplishments

- **Task 1 (tracer).** `EffectiveAccessCell` formats `people[].effectiveAccess` and nothing else.
  - Copy follows UI-SPEC § Copywriting B1-B3: "Group admin · all shops", the bootstrap line, "Admin account" with the realm-admin line, "{Role} · {Shop}" one per line, "{Role} · all shops", and an outline "No access" badge with the `Ban` icon.
  - A missing value reads "Not recorded".
  - The People card replaces "Current access" and absorbs the Team directory list (last seen kept).
  - No access rows get "Grant access" (h-11), which pre-fills the grant form. Rows with access get one Remove per grant.
  - Rows stack at ≤640px inside the focusable `People table` region. Names and lines wrap with `[overflow-wrap:anywhere]` and never truncate.
  - A load failure renders `LoadErrorPanel`.
  - The automatic-grant badge, the "cannot send an invite" sentence and the stale strict-OFF comments are gone.
  - Tracer gate (end-of-phase mode, automated-only verify): the plan's verify command was re-run, 30/30, before expanding.
- **Task 2.** The shell has one access decision.
  - `ShopSwitcherProvider` now keeps the whole staff/me answer, an in-memory `observedAccess` flag and `reloadAccess()`.
  - `DashboardAccessProvider` swaps the page content for `NoAccessPage` when the answer grants nothing. It re-reads staff/me when the api-client dispatches `jtoye:shop-access-denied`.
  - The sidebar hides nav and the switcher while the page shows; the shell hides the mobile tab bar and the top-bar switcher. Sign out stays.
  - The kitchen route is excluded (37-18 owns K2).
- **Task 3.** `ConfirmActionDialog` moved to `components/dashboard/confirm-action-dialog.tsx`.
  - `cancelLabel` is required, and the non-destructive confirm is the `Button` default variant (orange-700, 5.18:1), replacing orange-500 (2.80:1).
  - The old path re-exports it.
  - Webhooks pass "Keep current secret", "Keep endpoint" and "Skip replay".
  - Staff "Remove access" opens a destructive confirm, "Remove {Role} at {Shop} for {name}?". It states what remains, computed from the server's access minus that grant, or "After this they'll have no access to this business."

## Task Commits

1. **Task 1 RED: People-card tests** - `7955f99c` (test)
2. **Task 1 GREEN: People card renders server effective access** - `916d101a` (feat)
3. **Task 2 RED: no-access page + interceptor signal tests** - `2561c67b` (test)
4. **Task 2 GREEN: no-access page inside the dashboard shell** - `b2952aa9` (feat)
5. **Task 3 RED: confirm dialog + Remove access copy tests** - `2362407b` (test)
6. **Task 3 GREEN: one confirm dialog; Remove access names its consequence** - `034328bd` (feat)

**Plan metadata:** the commit that adds this SUMMARY, the deferred-items update and the WINDOWS.md entries (docs). STATE.md and ROADMAP.md are not touched; the orchestrator owns them.

## Evidence (both directions)

| Check | Pass direction | Fail direction |
|-------|----------------|----------------|
| Task 1 RED | n/a | 21 of 30 red on `7955f99c`. The target `offers 'Grant access' on a No access row…` failed at `rowOf("Nora Nobody")`: `expect(received).not.toBeNull()`, received null. The name existed only in the old directory `<li>`, with no People row. RED_EVIDENCE_OK. |
| Task 1 GREEN | 30/30 on `916d101a`; plan verify command 30/30 (tracer gate) | — |
| T-37-12 fail arm | clean 1/0 | **Deriving arm** (the page falls back to grant rows when `effectiveAccess` is missing): 1 failed, `Unable to find an element with the text: Not recorded`, because the row printed "Staff · Peckham Kitchen". Restored by `cp`, sha256 `cc8c237c…` OK; closing clean 30/30. |
| Copy removal (`rg -uu -n 'auto-granted\|cannot send (them )?an invite' frontend/app frontend/lib frontend/components`) | rc 1 | Plan base `31ece774` (`git grep`): 10 hits in page.tsx, staff-api.ts and the test. The first post-change run still found `staff-api.ts:47` (rc 0), which was fixed. Positive control `signed in once with their own` in page.tsx: count 1. |
| Task 2 RED | n/a | 7 of 10 red on `2561c67b`. Target: `Unable to find role="heading" and name "You don't have access yet"`. RED_EVIDENCE_OK. Interceptor: dispatch case red (1 failed / 14 passed). |
| T-37-13 fail arm | clean 10/10 | **Hard-coded "You no longer have access"**: 4 failed, including `never says 'You no longer have access' without having seen access in this session`. Restored, sha256 `fecf7d2e…` OK; closing clean 10/10. |
| Interceptor control | 15/15 | **Every-403 predicate** (`… \|\| true`): the control `does not dispatch it for any other refusal` failed. Restored, sha256 OK; closing clean 15/15. |
| Storage (`rg -uu -n 'sessionStorage\|localStorage'` on no-access-page, dashboard-shell, dashboard-access, access-events) | rc 1, and the diff of every modified shell file adds no storage line | Positive control: `frontend/lib/shop-context.ts` count 5 |
| Task 3 RED | n/a | 5 of 32 red on `2362407b`. Target: `Unable to find role="dialog"`. RED_EVIDENCE_OK. Dialog and webhooks suites: 6 red (cancel label, accent class, pending-state cancel, Rotate/Revoke/Replay labels). |
| Build gate (`npm --prefix frontend run build`) | rc 0 at `034328bd`, "Finished TypeScript"; closing clean rc 0 | `cancelLabel` removed from the Revoke call site: rc 1, `error TS2741: Property 'cancelLabel' is missing in type … ConfirmActionDialogProps`. Restored, sha256 OK. |
| Plan criterion `rg -uu -n '>Cancel<' …confirm-action-dialog.tsx` | rc 1 | **Vacuous as written:** it is rc 1 on the OLD component too, which wrote `Cancel` on its own JSX line. Stronger form `^\s*Cancel\s*$`: old file rc 0 (line 70), new file rc 1, call sites rc 1. The behavioural form is the jest assertion that no dialog button is named "Cancel" (red at RED, green after). |
| Full frontend | 197 suites / 2270 tests / 0 failed; lint 0 errors | The arms above are the falsifying runs. |

Every break arm ran on a committed tree, and every restore was verified by sha256.

**RED evidence for Jest.** Jest has no TAP or JUnit reporter installed, and none was installed. A 40-line local reporter in the session scratchpad (not committed) serialises the run's own `testResults` as strict flat TAP 13 for `gsd_run check tdd-red-evidence`. Its first two runs VOIDed correctly ("no TAP report written"): once on a reporter argument-order error and once on a field-name bug. Both were fixed before any record was written.

## Decisions Made

See `key-decisions` in the frontmatter.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Two e2e assertions named copy this plan removes**
- **Found during:** Task 1
- **Issue:** `e2e/dashboard-interface-corrections.spec.ts` asserted the "cannot send them an invite" sentence (l.185) and the "Team directory" heading in the CLS test (l.242).
- **Fix:** It now asserts the denial sentence is absent and waits for the "People" heading.
- **Not run.** The People column needs the rebuilt core-java (WINDOWS #16).
- **Commit:** `916d101a`

**2. [Rule 2 - Missing critical] Load failure rendered as a toast over an empty list**
- **Issue:** UI-SPEC B1 error requires `LoadErrorPanel`. The page toasted and then showed an empty list, which on this page would read as "nobody has access".
- **Fix:** A `loadError` branch renders `LoadErrorPanel` and declares the index tier like the other branches. Tested both ways.
- **Commit:** `916d101a`

**3. [Rule 3 - Blocking] Files outside the plan list needed for Task 2**
- `shop-switcher-provider.tsx` holds the only staff/me read in the shell. It now exposes `access`, `observedAccess` and `reloadAccess`, so no second fetch is needed.
- `lib/api-client.ts` and the new `lib/access-events.ts` carry the refused-read signal.
- `components/dashboard/dashboard-access.tsx` holds the decision, so `sidebar.tsx` can read it without a circular import through the shell.
- `lib/__tests__/api-client-interceptors.test.ts` covers the signal.
- **Commits:** `2561c67b`, `b2952aa9`

**4. [Rule 3 - Blocking] `app/dashboard/webhooks/[id]/page.tsx` also uses the dialog**
- **Issue:** It is not in the plan's file list, but the required `cancelLabel` fails the build without it (proven by the TS2741 arm).
- **Fix:** It now passes "Keep current secret", "Keep endpoint" and "Skip replay". Both webhooks pages import the new path.
- **Commit:** `034328bd`

**5. [Rule 1 - Bug] `MyAccess` comment claimed an empty `grantedShopIds` "only ever means no access"**
- **Issue:** 37-05 made that false: a tenant-wide role in a tenant with no shops is access with an empty set.
- **Fix:** `MyAccess.tenantWideRole` was added, and `hasAnyAccess` counts it.
- **Commit:** `b2952aa9`

**6. [Plan criterion] `>Cancel<` grep is vacuous.** Replaced by the stronger forms recorded in the evidence table. The original result is kept alongside.

### Spec points not implemented as written (recorded, not silently dropped)

- **UI-SPEC B1 "existing per-grant role select":** no such control existed on the page (it had only a ghost "Revoke" button). None was invented. A role change is still made through the grant form.
- **UI-SPEC B3 "{business}":** a person with no access can read no tenant record, and the session carries no business name, so the body says "your business" (WINDOWS #18). `NoAccessPage` accepts a `businessName` prop for when a source exists.
- **UI-SPEC B3 backstop** (375px, 60-character business name, no horizontal overflow): the body wraps with `[overflow-wrap:anywhere]`. The Playwright check was not run (WINDOWS #17).

---

**Total deviations:** 5 auto-fixed (1 bug, 1 missing critical, 3 blocking), plus 1 vacuous criterion replaced.
**Impact on plan:** each change is required for the plan's own truths to hold or for the build to pass. No refusal or gate was weakened.

## Preserved goods (Incremental Betterment)

| Good | Disposition |
|------|-------------|
| Last-admin refusals and their copy (D-11, IN-02) | Preserved; both tests kept |
| Self-revoke warning by userId (WR-12) | Preserved |
| #290 picker label (masked email once) | Preserved |
| 5-minute revocation bound copy (23-11) | Preserved, in the People description |
| Loading skeleton with real headings (#454) | Preserved, now two cards to match the page |
| Width tier declared on every branch (UIX-08) | Preserved, plus the new load-error branch |
| Team directory list (who signed in, last seen) | Replaced by People rows, which carry the same name, masked email and last-seen line, plus access |
| Directory count ("N people have signed in") | Dropped; the list itself shows it |
| JIT "auto-granted" badge | Replaced by the computed access, which is strictly more accurate (UI-SPEC ledger) |
| Webhooks Rotate/Replay orange-500 confirm (2.80:1) | Replaced by orange-700 (5.18:1), per UI-SPEC § Color |

## Issues Encountered

- The session refuses compound `git` commands and runtime-computed `rg` arguments, so those were split into plain commands. No python was used.
- The first `red-record.sh` runs VOIDed: `--reporters` swallowed the test path, and the reporter read `assertionResults` instead of `testResults`. The VOID guard ("no TAP report written") caught both before any record was written.

## Known Stubs

- `frontend/components/dashboard/no-access-page.tsx` body: "your business" in place of the business name. No data source is reachable for a person with no access. Intentional, and recorded as WINDOWS #18. It does not block the plan goal: the page's purpose (a clear statement and a way to recheck or sign out) holds.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: information-disclosure | frontend/lib/api-client.ts | A window event fires on every shop-access-denied 403. It carries no payload (no URL, no body), so any script on the page learns only that a refusal happened. The shell's response is a staff/me re-read, at most one at a time. |

## TDD Gate Compliance

- **Task 1:** RED `7955f99c` `test(37-06)` (RED_EVIDENCE_OK, target failed on the planned row lookup) → GREEN `916d101a` `feat(37-06)`.
- **Task 2:** RED `2561c67b` (RED_EVIDENCE_OK, target failed on the planned heading) → GREEN `b2952aa9`.
- **Task 3:** RED `2362407b` (RED_EVIDENCE_OK, target failed: no dialog) → GREEN `034328bd`.
- No REFACTOR commits.

## User Setup Required

None.

## Next Phase Readiness

- **37-07 (invites):**
  - The Grant card's description no longer denies invitations, but `staff-page.test.tsx` "does not promise an invite…" and e2e test 2 still assert there is no invite control. 37-07 must update both when it adds "Invite someone".
  - Its "Cancel invitation" confirm must use `ConfirmActionDialog` with `cancelLabel="Keep invitation"`.
- **37-15 runtime gate:**
  - Rebuild, then run the two edited e2e assertions and a 375px check of the People table and the no-access page (WINDOWS #16, #17).
  - Prove parity with `scripts/check-runtime-freshness.sh`.
- **B4 / D1 / E1:** import the dialog from `components/dashboard/confirm-action-dialog.tsx`.
- **Before the phase PR:** the docs metrics regeneration (deferred-items §1) now includes this plan's Jest additions.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-08*

## Self-Check: PASSED

- Created files exist: effective-access-cell.tsx, no-access-page.tsx, dashboard-access.tsx, confirm-action-dialog.tsx, access-events.ts, both new test files, the three RED evidence records
- Commits on this branch (`git log 31ece774..HEAD`): 7955f99c, 916d101a, 2561c67b, b2952aa9, 2362407b, 034328bd (6 at SUMMARY write)
- Acceptance re-run: copy-removal grep rc 1 (base 10 hits); storage grep rc 1 (control 5); build rc 0 (arm rc 1 TS2741); full jest 197/2270/0; the plan verify commands for Tasks 1 and 3 rc 0 (30/30; 47/47)
