---
phase: 37-real-world-operations-readiness
plan: 46
subsystem: ui
tags: [developers-page, integrator-credentials, secret-reveal, staff-page, sidebar, a11y, d-22, d-23, d-24, rwo-035, uxt-035]
status: complete

requires:
  - phase: 37-45
    provides: "POST/GET/rotate/DELETE /api/v1/credentials, the eight credential problem types, CredentialDto/CredentialSecretDto, StaffTargetIsCredentialException on grant"
  - phase: 37-06
    provides: "ConfirmActionDialog with a required cancelLabel at components/dashboard/confirm-action-dialog.tsx; the Staff page People card"
  - phase: 22 (COMMS-06)
    provides: "the webhook SecretRevealDialog (show-once, no implicit close)"
provides:
  - "/dashboard/developers (UI-SPEC E1): header, CredentialsTable (sm+ table, dl cards below sm), CredentialCreateDialog, show-once, rotate and delete behind ConfirmActionDialog, five page states"
  - "SecretRevealDialog generalised at components/dashboard/secret-reveal-dialog.tsx (title, description, warning, secretLabel, optional clientId and tokenEndpoint), re-exported at the webhooks path; DialogContent showCloseButton"
  - "lib/credentials-api.ts: list/create/rotate/delete, CREDENTIAL_SCOPES with plain-English labels, CREDENTIAL_ROLES (Staff, Shop manager), per-submit Idempotency-Key, credentialProblem/credentialActionError"
  - "Sidebar and mobile More sheet: 'Developers' (Code2) after Webhooks, groupAdminOnly on staff/me; NavItem, useIsKnownGroupAdmin, visibleNavigation"
  - "Staff page: credential service-account rows read 'Integration: {name}' with a Developers link instead of Remove/Grant; excluded from the grant picker; the staff-target-is-credential 409 has its own copy"
  - "core: CredentialDto.serviceAccountUserId; StaffManagementService.revoke refuses a credential's grant (409 staff-target-is-credential)"
  - "dashboard-a11y-nightly.spec.ts scans /dashboard/developers and the kitchen New lane behind non-vacuity controls"
affects: [37-47 the 37-E gate (live Developers page, nightly a11y, long-text backstop), staff page, webhooks pages]

actuals:
  tokens: 28302      # chars/4 over the added lines of source, tests and docs (113,210 chars); excludes .planning (RED evidence) and the regenerated OpenAPI snapshot. All added lines: ~77,700
  tasks: 3
  commits: 8         # MEASURED: git rev-list --count 42ffb5f5..da50cead
plan_head_before: 42ffb5f5f0cd4872e15fff63c32c542148ef659a
plan_head_after: da50cead5782a7fd3289445b9a183e983e4a3ef4

tech-stack:
  added: []
  patterns:
    - "A show-once secret lives in page component state only, set from the create/rotate response and dropped in the same onOpenChange(false) that closes the dialog; the dialog keeps no copy and renders no corner X (showCloseButton={false}), so the DOM and both storages are clean after 'I've saved it' (F13)"
    - "An admin-only nav item is listed only on the server's word: groupAdminOnly items need the shell's settled staff/me answer (useIsKnownGroupAdmin); a pending answer or no provider lists nothing admin-only"
    - "A loading skeleton that shares the loaded table's region name needs a 'loaded' wait in tests (skeleton rows detached), or a region query resolves on the skeleton"
    - "Page copy that suites assert lives in a sibling module (developers-copy.ts): a Next page file may export only its page fields"

key-files:
  created:
    - frontend/app/dashboard/developers/page.tsx
    - frontend/app/dashboard/developers/layout.tsx
    - frontend/components/dashboard/developers/credentials-table.tsx
    - frontend/components/dashboard/developers/credential-create-dialog.tsx
    - frontend/components/dashboard/developers/developers-copy.ts
    - frontend/components/dashboard/secret-reveal-dialog.tsx
    - frontend/lib/credentials-api.ts
    - frontend/app/dashboard/developers/__tests__/developers-page.test.tsx
    - frontend/components/dashboard/__tests__/secret-reveal-dialog.test.tsx
    - frontend/components/dashboard/__tests__/sidebar-developers.test.tsx
    - .planning/phases/37-real-world-operations-readiness/evidence/37-46-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-46-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-46-red-task3.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-46-red-task3b.json
  modified:
    - frontend/components/dashboard/webhooks/SecretRevealDialog.tsx
    - frontend/components/ui/dialog.tsx
    - frontend/lib/webhooks-api.ts
    - frontend/app/dashboard/webhooks/page.tsx
    - frontend/app/dashboard/webhooks/[id]/page.tsx
    - frontend/components/dashboard/sidebar.tsx
    - frontend/components/dashboard/mobile-tab-bar.tsx
    - frontend/app/dashboard/staff/page.tsx
    - frontend/app/dashboard/__tests__/staff-page.test.tsx
    - frontend/e2e/dashboard-a11y-nightly.spec.ts
    - core-java/src/main/java/uk/jtoye/core/integration/credential/dto/CredentialDto.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffController.java
    - core-java/src/main/java/uk/jtoye/core/exception/StaffTargetIsCredentialException.java
    - core-java/src/test/java/uk/jtoye/core/integration/credential/IntegrationCredentialIntegrationTest.java
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "37-46: a credential's service account on the Staff page is labelled 'Integration: {name}' and offers no Remove access or Grant access, only a link to the Developers page; and the Staff API now refuses to revoke a credential's grant (409 staff-target-is-credential, the 37-45 grant refusal's type). Chosen because it is the only option that CANNOT leave a credential ACTIVE with no grant: hiding the row (or only removing the button) still leaves DELETE /api/v1/staff/{id} able to do it, after which the Developers page overstates what the credential may do. A credential's access is changed by deleting it and creating a new one, which the 409 copy says"
  - "37-46: CredentialDto gains serviceAccountUserId (additive OpenAPI). The plan's interface said GET carried it; it did not, and without it the Staff page cannot recognise a credential's row"
  - "37-46: the Developers page lists ACTIVE credentials only. A deleted credential stays on the server as REVOKED (the record of who revoked it and when) but can do nothing; listing it beside working ones would overstate what has access, and UI-SPEC says delete 'removes the row'"
  - "37-46: unlike Staff and Webhooks (always listed, page refuses), Developers is gated in the nav itself on the shell's settled staff/me answer, as UI-SPEC E1 requires; the plan's 'Staff/Webhooks precedent' for that gating does not exist in the tree"
  - "37-46: create sends an Idempotency-Key per submit, so the api-client's own 5xx retry replays (200, secretShown false) rather than issuing a second credential; the page says the credential already exists and that its secret can only be seen again by rotating"

patterns-established:
  - "Show-once secret: parent state only, dropped on close; F13 asserted on document.body and both storages after the exit button"
  - "Server-gated nav item: NavItem.groupAdminOnly + visibleNavigation(navigation, useIsKnownGroupAdmin()) in both navs"

requirements-completed: [RWO-035]

coverage:
  - id: D1
    description: "Create a credential: name, six plain-English scope checkboxes with their ids, Staff/Shop manager only, a shop or All shops; POST {name, scopes, role, shopId} with an Idempotency-Key; no scope ticked refuses inline and posts nothing"
    requirement: "RWO-035"
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/developers/__tests__/developers-page.test.tsx#sends the chosen name, scopes, role and shop, then shows the client ID and secret once; #refuses inline with no scope ticked and sends nothing; #offers Staff and Shop manager only, never Group admin; six scopes, each with its id (RED 4/4 at fd67cc9d)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Show-once: client ID, client secret and token endpoint; Esc, a click outside and a corner X cannot close it; 'I've saved it' (Button default) is the only exit; afterwards the secret is in neither the DOM nor localStorage nor sessionStorage (F13); webhooks keep their dialog through the re-export with the orange-700 exit"
    requirement: "RWO-035"
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/__tests__/secret-reveal-dialog.test.tsx (5/5, RED 5/5); developers-page.test.tsx#keeps the show-once dialog ... (F13); F13 arm: a dialog writing sessionStorage reds line 165, restored by hash"
        status: pass
      - kind: unit
        ref: "frontend/app/dashboard/webhooks/__tests__ (22/22 unchanged)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The credentials list: Name with client ID, Can chips, Shops, Role, Last used (relative + absolute UK time in title and sr-only, 'Never used'), Created '{date} by {name}', Actions; footnote from lastUsedIntervalMinutes; dl cards below sm; revoked not listed"
    requirement: "RWO-035"
    verification:
      - kind: unit
        ref: "developers-page.test.tsx Task 2 arms (RED 12/16 at add3bf59, GREEN 16/16); footnote arm: a literal 15 reds the 5-minute assertion"
        status: pass
    human_judgment: false
  - id: D4
    description: "Rotate (accent confirm, 'Keep current secret') reopens the show-once dialog; Delete (destructive, 'Keep credential') removes the row with a toast; refusals show the server's detail inline"
    requirement: "RWO-035"
    verification:
      - kind: unit
        ref: "developers-page.test.tsx#rotates after an accent confirm ...; #deletes after a destructive confirm ...; #shows a refused rotate in place ..."
        status: pass
    human_judgment: false
  - id: D5
    description: "Page states: skeleton under the real headers, LoadErrorPanel, empty with Create, feature off hides Create, not-Group-admin refusal (staff/me before any request; the server's 403 the same); headings h1/h2/h3 in order"
    requirement: "RWO-035"
    verification:
      - kind: unit
        ref: "developers-page.test.tsx state arms; heading levels red 2/16 against e232e384, green at da50cead; uncommitted jest-axe probe 0 violations on populated, empty, create and show-once"
        status: pass
    human_judgment: false
  - id: D6
    description: "Developers in the sidebar and the mobile More sheet after Webhooks for a Group admin only; Staff page names credential rows 'Integration: {name}', offers them no Remove/Grant, excludes them from the picker, and explains the 409; the Staff API refuses to revoke a credential's grant"
    requirement: "RWO-035"
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/__tests__/sidebar-developers.test.tsx (4/4; gating arm: ungated item reds both manager arms); staff-page.test.tsx 37-46 block (4/4, RED 3/4)"
        status: pass
      - kind: integration
        ref: "IntegrationCredentialIntegrationTest#list_namesEachCredentialsServiceAccountUser, #staffRevoke_ofACredentialsGrant_isRefused_andAPersonsStillRevokes (RED 2/20, GREEN 20/20); full integrationTest 1221/0"
        status: pass
    human_judgment: false
  - id: D7
    description: "The running stack serves the page: create, show once, rotate, delete against live Keycloak; the nightly axe scans /dashboard/developers and the New lane with their controls; a 60-character name and six chips wrap without horizontal scroll at 375px and 640px"
    verification: []
    human_judgment: true
    rationale: "Nothing here ran against the compose stack, which still runs images cut at d060d0af and is BEHIND the branch. The 37-47 gate rebuilds it; the long-text row is a Playwright backstop by UI-SPEC and the nightly spec is report-only."

duration: 54min
completed: 2026-10-13
---

# Phase 37 Plan 46: The Developers page Summary

**A Group admin can now issue, rotate and delete scoped API credentials from `/dashboard/developers`. Each credential's secret is shown once, in a dialog that cannot be dismissed by accident. After "I've saved it" the secret is in neither the DOM nor browser storage. The Staff page shows a credential's service account as "Integration: {name}" and can no longer strip its access. The server enforces that too: the Staff API now answers 409 when asked to revoke a credential's grant.**

## Performance

- **Duration:** 54 min (04:40:51Z to 05:35Z)
- **Tasks:** 3, each a TDD cycle (RED, then GREEN). Task 1 was a tracer.
- **Files:** 34 in the plan diff: 14 created, 20 modified.

## Accomplishments

- **UI-SPEC E1, end to end in the browser half.**
  - **Header:** h1 "Developers" (36px/600), the subtitle, "Webhooks →" and Create credential.
  - **Create dialog:** a name, six scope checkboxes, a role and a shop.
    - Each scope is in plain English with its id, e.g. "Read your menu · `catalog:read`".
    - The role is Staff or Shop manager only, never Group admin.
    - The shop is one shop or "All shops".
  - **The credentials card:**
    - a table from `sm` up;
    - a `<dl>` card per credential below `sm`;
    - a "Last used" footnote whose interval comes from the API.
  - **Rotate and delete** go through `ConfirmActionDialog` with the UI-SPEC labels.
  - **Five page states:** loading, error, empty, feature off and not a Group admin.
- **The secret is shown exactly once, and only held while it is shown (T-37-112).**
  - The page keeps it in component state and drops it in the same handler that closes the dialog.
  - The dialog renders no corner X. It ignores Esc and outside clicks, and its only exit is the default `Button` (orange-700, 5.18:1).
  - The Webhooks "I've saved it" button moved from orange-500 (2.80:1) to that default, as the UI-SPEC contrast contract recorded.
- **All eight 37-45 problem types are handled.**
  - `credentials-not-configured` hides creation.
  - `credential-scope-invalid` and `credential-role-invalid` show in the dialog.
  - `human-only-action` shows its own detail.
  - `credential-revoked` and `credential-client-missing` appear inline after a rotate, and the list is re-read.
  - `credential-service-unavailable` shows the server's sentence. That sentence already says when to retry, so the Retry-After value is appended only when the server sends no detail.
  - `staff-target-is-credential` gets its own copy on the Staff page.
- **The 37-45 open choice, decided.** The Staff page now:
  - labels a credential's row;
  - replaces its Remove/Grant buttons with "Manage on the Developers page";
  - leaves it out of the grant picker.

  `StaffManagementService.revoke` also refuses a credential's grant (409). That makes "ACTIVE with no access" unreachable from the Staff side altogether, which neither UI-only option does. The credential's own delete removes its grant through the repository, so it is unaffected (see the integration arm).
- **Nightly a11y.** `/dashboard/developers` and the kitchen New lane are scanned in the report-only nightly spec. Each scan waits first for its own surface to be visible: the h1 plus a loaded card state, or the lane plus its "New orders" heading. If that control fails, the run records "control failed, NOT scanned", never "0 violations".

## Task Commits

1. **Task 1 (tracer): create a credential and see its secret once.**
   - `fd67cc9d` (test, RED): 9/9 failed. Evidence: `37-46-red-task1.json`, RED_EVIDENCE_OK.
   - `225efdce` (feat, GREEN): 31/31 across developers, secret-reveal and webhooks.
   - Tracer gate (end-of-phase mode, automated-only verify): I re-ran the plan's verify command on the committed tree and got rc 0, 31/31. Then I expanded.
2. **Task 2: the list, rotate, delete and every page state.**
   - `add3bf59` (test, RED): 12 of 16 failed. Evidence: `37-46-red-task2.json`, RED_EVIDENCE_OK.
   - `e232e384` (feat, GREEN): 16/16.
3. **Task 3: the nav item, the Staff page's integration names, and nightly a11y.**
   - `092b9f8f` (test, RED):
     - jest: 5 of 40 failed (`37-46-red-task3.json`);
     - integrationTest: 2 of 20 failed (`37-46-red-task3b.json`, JUnit).
     - Both records are RED_EVIDENCE_OK.
   - `c8d3e1fa` (feat, GREEN): jest 53/53; IntegrationCredential 20/0, StaffManagement 19/0, OpenApiSnapshot 1/0.
4. **Metrics:** `6b9d2ad4` (docs: 5876 → 5907).
5. **Fix:** `da50cead` (fix: heading order h1/h2/h3, found by an axe probe).

**Plan metadata:** this SUMMARY commit.

## TDD Gate Compliance

| Task | RED (commit, evidence) | GREEN | Fail-direction arms |
|------|------------------------|-------|---------------------|
| 1 | `fd67cc9d`: `37-46-red-task1.json` RED_EVIDENCE_OK (TAP: 9 tests, 9 failed) | `225efdce`: 31/31 | F13: dialog writes sessionStorage → line 165 red; restored by hash; clean 4/4 |
| 2 | `add3bf59`: `37-46-red-task2.json` RED_EVIDENCE_OK (TAP: 16 tests, 12 failed, the 4 Task 1 arms passing) | `e232e384`: 16/16 | Footnote: a literal "15" in the component reds the 5-minute assertion; restored; clean |
| 3 | `092b9f8f`: `37-46-red-task3.json` (TAP: 40 tests, 5 failed) + `37-46-red-task3b.json` (JUnit: 20 tests, 2 failed), both RED_EVIDENCE_OK | `c8d3e1fa`: jest 53/53, ITs 20/0 + 19/0 + 1/0 | Sidebar gating: `groupAdminOnly: false` reds both manager arms (2 of 4); restored; clean 4/4 |

**RED semantic assessments.**
- **Task 1.**
  - The target failed on its first planned step. No "Create credential" button existed; the route rendered an empty width-tier div.
  - The five dialog arms failed against the old webhook dialog, re-exported at the new path:
    - no title or client ID props;
    - the orange-500 exit;
    - a corner X still in the DOM, hidden only by a class that jsdom cannot apply.
- **Task 2.** The target failed on the missing "API credentials table" region. Each other arm failed on its own missing surface.
- **Task 3.**
  - The jest target failed on the missing "Integration: Till system" label: both service-account rows read "Integration account".
  - The sidebar arms failed on their assertions: Developers was absent after Webhooks, and the More sheet had no link.
  - The 3 new arms that passed are non-regression controls: a manager, a render with no provider, and a non-admin Staff page.
  - The JUnit target failed with expected 409, was 204: the Staff API removed an ACTIVE credential's only grant.
  - The second JUnit arm failed because the list had no `serviceAccountUserId`.
- **No failure in any RED was a setup, import or compile fault.**

**A recorded VOID arm.** The first sidebar gating arm used `sed` and broke the syntax: the trailing comma landed inside the injected comment. Jest reported "Test suite failed to run, 0 total". That is VOID, not RED. It was redone with `groupAdminOnly: false`, which went RED as recorded above.

## Verification on the final tree (`da50cead`)

| Check | Result |
|-------|--------|
| Full jest | **2790 passed / 0 failed** in 240 suites. The count equals `docs/metrics.json`. |
| `npm run build` | rc 0. "Running TypeScript … Finished TypeScript in 5.9s"; `/dashboard/developers` is in the route table. |
| Full-program `npx tsc --noEmit` (the CI step that reads jest files) | rc 0. Fail direction shown first: a planted `const probe: number = "…"` in a jest file gave rc 2, and with the probe removed it gave rc 0. |
| `npm run lint` | rc 0: 0 errors, 29 warnings. All 29 are pre-existing; I compared the files they name against the plan diff and none overlap. |
| `scripts/check-e2e-typecheck.sh` | PASS, 46 e2e files. |
| `npx playwright test e2e/dashboard-a11y-nightly.spec.ts --list` | 2 tests (mobile, desktop). |
| core-java unit (`:core-java:test`) | **1821 tests, 0 failures, 0 errors, 1 skipped**. All 210 report files are newer than the run's start. |
| core-java full `:core-java:integrationTest` | **1221 tests, 0 failures, 0 errors, 1 skipped** (1219 before plus the 2 new). All 211 report files are fresh. BUILD SUCCESSFUL in 32m 30s, run on `6b9d2ad4`. The only later commit, `da50cead`, touches no core-java file (`git diff --stat 6b9d2ad4..HEAD -- core-java` is empty). |
| `docs-freshness.sh`, `check-doc-metrics.sh` | rc 0 and rc 0 (37/37). Both were rc 1 before the metrics commit. |
| Plan verify commands (Tasks 1, 2 and 3, as written) | rc 0 each: 31/31; 16/16; 474/474 + build + `--list`. |
| `gitleaks dir` on each RED evidence file | No leaks. |

## Decisions Made

See `key-decisions`. The Staff page choice is the one 37-45 left open. A UI-only fix (hiding the row, or hiding only its button) still leaves `DELETE /api/v1/staff/{id}` able to strand a credential. The 409 on revoke closes that, and it reuses 37-45's own problem type for the grant direction.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The list API did not carry `serviceAccountUserId`**
- **Found during:** Task 3, while reading the 37-45 DTOs.
- **Issue:** The plan's interface lists `serviceAccountUserId` in GET `/api/v1/credentials`. `CredentialDto` does not have it, so the Staff page could not name a credential's row.
- **Fix:** I added the field to `CredentialDto`. The OpenAPI snapshot change is additive. It was RED first (IT `list_namesEachCredentialsServiceAccountUser`).
- **Files modified:** `CredentialDto.java`, `docs/api/openapi-snapshot.json`.
- **Commits:** `092b9f8f` (RED), `c8d3e1fa` (GREEN).

**2. [Rule 2 - Missing critical] The Staff API could leave a credential ACTIVE with no access**
- **Found during:** Task 3. This is the decision the dispatch asked for.
- **Fix:** `StaffManagementService.revoke` refuses a credential's grant with 409 `staff-target-is-credential`. I updated the `StaffController` OpenAPI text and the exception's javadoc. A positive control in the same IT shows that a person's grant still revokes with 204.
- **Files modified:** `StaffManagementService.java`, `StaffController.java`, `StaffTargetIsCredentialException.java`.
- **Commits:** `092b9f8f` (RED), `c8d3e1fa` (GREEN).

**3. [Rule 1 - Bug] Heading order on the Developers page**
- **Found during:** an uncommitted jest-axe probe after Task 3.
- **Issue:** The page h1 was followed by the CardTitle h3 (axe `heading-order`).
- **Fix:** I made the card title an h2 and the empty-state heading an h3. Assertions inside existing arms check the levels; they were red 2 of 16 against `e232e384`. The probe then reported 0 violations for the populated page, the empty page, the create dialog and the show-once dialog.
- **Commit:** `da50cead`.

**4. [Rule 3 - Blocking] Things the plan did not list**
- **Primitive change:** `components/ui/dialog.tsx` gained an optional `showCloseButton` prop (default true, so every existing dialog is unchanged). The show-once dialog removes its X from the DOM instead of relying on the old `[&>button]:hidden` class, which jsdom cannot see.
- **Files touched beyond the plan's list:** `app/dashboard/webhooks/[id]/page.tsx` (it is also a SecretRevealDialog call site), `components/dashboard/mobile-tab-bar.tsx` (UI-SPEC: both navs share one array), `lib/webhooks-api.ts` (`SIGNING_SECRET_COPY`, so the two webhook call sites cannot drift) and `components/dashboard/developers/developers-copy.ts`. That last one exists because Next.js rejects non-page named exports from `page.tsx`.

**5. [Test harness] Two adjustments inside the Task 2 GREEN commit**
- The tests now wait for the list to load before acting. The skeleton shares the table's region name, and the header Create button is disabled until the list has loaded.
- The delete mock now leaves the credential REVOKED, as the 37-45 server does. With the old mock, the post-delete re-read brought the row back.
- These are fixture corrections. No assertion was weakened.

---

**Total deviations:** 5 (1 Rule 1, 1 Rule 2, 2 Rule 3, 1 test harness). **Impact:** Two of them are necessary server changes (one additive field, and one refusal on a path that predates any deployment of 37-45). The rest are scoped to the files the contract implies. No scope creep beyond the 37-45 handoff item.

## Issues Encountered

- My first core RED run used `./gradlew` from `core-java/` and got rc 127. The wrapper is at the repo root. I re-ran it as `./gradlew :core-java:integrationTest …` from there.
- The JUnit RED record first came back INVALID_RED (`no_target_test_failure`) because I named the target by method. Gradle's testcase name is the `@DisplayName`. I re-targeted the record to that name, and it then returned RED_EVIDENCE_OK.
- Plan Task 3 calls the Developers nav gating the "Staff/Webhooks precedent". That precedent does not exist: those two items are always listed, and their pages refuse non-admins. Developers is the first nav item gated on staff/me.

## Known Stubs

None. The RED-phase placeholders (`secret-reveal-dialog.tsx` as a re-export, and an empty `page.tsx`) were replaced in the GREEN commits. A scan of the added lines finds no TODO, FIXME, "coming soon" or placeholder copy. The one "not available" hit is a test-fixture detail string.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: response-field | core-java/.../dto/CredentialDto.java | `serviceAccountUserId` is now in `GET /api/v1/credentials`. The endpoint is Group admin and human-only. The same id is already visible to that caller in `GET /api/v1/staff` (`people[].userId`, grant rows), so nothing new is disclosed. |
| threat_flag: authz-narrowing | core-java/.../StaffManagementService.java | `DELETE /api/v1/staff/{id}` now refuses a credential's grant (409). This narrows what callers can do, and the IT covers it with a person-grant positive control. |

## For the orchestrator and 37-47 (record, decide)

- **Runtime.** The compose stack is still behind the branch. None of this is live until the 37-47 gate rebuilds core-java and frontend. That rebuild is also when V82 and the 37-45 endpoints reach the dev database and Keycloak.
- **37-47 live proof for E1.** On the rebuilt stack, as the vendor Group admin:
  1. Create a credential with two scopes and confirm the show-once dialog ignores Esc and outside clicks.
  2. After "I've saved it", search the page DOM and both storages for the secret.
  3. Rotate the credential and confirm the old secret then fails a token mint.
  4. Delete it and confirm the row is gone and the next call is `credential-revoked`.
  5. On the Staff page, after the credential's first call, confirm its row reads "Integration: {name}" with no Remove access, and that `DELETE /api/v1/staff/{grantId}` returns 409.
- **Owed to the Playwright backstop (UI-SPEC long-text row).** A 60-character name plus all six chips should wrap at 375px (cards) and 640px (table cell). At 640px the table region may still scroll sideways: seven columns at about 608px of content width. UI-SPEC accepts that ("the table keeps its focusable region at sm and up"), but the backstop should check that the name and chips wrap and that the page itself does not scroll.
- **Nightly a11y.** The two new controlled scans first run in the next nightly, or in 37-47. They are report-only by the spec's design.
- **Ledger.** Recording the unrun backstop and the nightly scan in `WINDOWS.md` (kind `unrun-verify`) is the orchestrator's call. I left shared artifacts alone, as instructed.

## Owner questions

None new. 37-45's two owner questions still stand (the staging and production `keycloak.client-id`, and the `core-api` service account).

## User Setup Required

None.

## Next Phase Readiness

Ready for 37-47, the 37-E gate. Plans 37-39..37-46 are done; the live E1 proof is listed above.

## Self-Check: PASSED

- All 14 created files exist on disk. I checked each one with `[ -f ]` over `git diff --diff-filter=A 42ffb5f5..HEAD`.
- All 8 plan commits are ancestors of HEAD on `phase-37-ops-readiness`, from `fd67cc9d` to `da50cead`. `gsd_run check evaluation-scope --plan 37-46 --commits-only` resolves them from the plan subjects with rc 0.
- Acceptance criteria re-run on the final tree:
  - Task 1: the F13 arm is red, then green again after the restore.
  - Task 2: the footnote arm is red, then green.
  - Task 3: the sidebar gating arm is red, then green.
- Every plan verify command returned rc 0.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-13*
