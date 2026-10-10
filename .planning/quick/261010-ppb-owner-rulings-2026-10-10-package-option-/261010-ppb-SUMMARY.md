---
phase: quick
plan: 261010-ppb
status: complete
subsystem: access-control, dashboard-ui, kitchen, identity
tags: [rbac, d-06, option-e, d-25, d-08, d-14, keycloak, jest, testcontainers]
requires:
  - phase: 37-real-world-operations-readiness
    provides: "37-21 editor/pause/notices, 37-22 runtime at cb8cf9d1, g8m customer any-grant gate"
provides:
  - "ShopAccessService.requireAnyRole(floor): honour-aware role floor with no JIT side effect"
  - "Customer writes floored: create/update SHOP_MANAGER (before the Idempotency-Key branch), delete Group admin"
  - "Hours editor all-day pre-fill of unreadable NEEDS_REVIEW days with the owner's wording; card in the owner's wording"
  - "Pause shop-access-denied 403 answered by K2 Access ended only; KITCHEN_ROUTE_CALLS registry + guard test"
  - "Board-stopped alarm stops by itself after 120 s"
  - "Vendor realm SSO max 14 h in template and in the running jtoye-dev realm (rotation stays off)"
affects: [37-23 UAT, 37-48 slug fix, Phase 39 planning, staging/production realm operators]
actuals:
  tokens: 45967
  tasks: 3
  commits: 16
plan_head_before: 10bf2c595f4d87e9583a423c9ba9141b6cf763dc
plan_head_after: fa3552fadce213d8403243c1e407cc5b67c53122
tech-stack:
  added: []
  patterns:
    - "Role floor on a tenant-level resource: Group admin by the ladder, else membership tenant-wide or any per-shop role >= floor"
    - "A source-scanning jest guard that keeps a route's API calls declared with their server floors"
key-files:
  created:
    - frontend/components/dashboard/kitchen/kitchen-route-calls.ts
    - frontend/components/dashboard/kitchen/__tests__/kitchen-route-calls.test.ts
    - .planning/quick/261010-ppb-owner-rulings-2026-10-10-package-option-/evidence/live-hours.cjs
    - .planning/quick/261010-ppb-owner-rulings-2026-10-10-package-option-/evidence/live-probes.md
    - .planning/quick/261010-ppb-owner-rulings-2026-10-10-package-option-/evidence/break-arms.txt
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
    - core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java
    - core-java/src/test/java/uk/jtoye/core/customer/CustomerAccessGateIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/ScopedWriteAccessIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/integration/TypedNotFoundBodyIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/integration/VanishedRowStatusIntegrationTest.java
    - docs/api/openapi-snapshot.json
    - frontend/components/dashboard/shops/opening-schedule-editor.tsx
    - frontend/components/dashboard/hours-notice-card.tsx
    - frontend/components/dashboard/kitchen/pause-orders-control.tsx
    - frontend/components/dashboard/kitchen/kds-sound.tsx
    - frontend/app/dashboard/kitchen/page.tsx
    - infra/keycloak/realm-export.template.json
    - infra/keycloak/README.md
key-decisions:
  - "Option E (owner 2026-10-10): customer reads any grant; create/update SHOP_MANAGER; delete Group admin"
  - "D-25 (owner 2026-10-10): an unreadable day pre-fills as one 00:00-00:00 window with the owner's sentence"
  - "D-08 (owner 2026-10-10): a pause shop-access-denied 403 is answered by K2 Access ended only"
  - "Alarm stops after 2 minutes (owner-accepted, alarm fatigue)"
  - "D-14 amended: vendor SSO max 14 h; idle 30 min unchanged; rotation NOT enabled (orchestrator revision)"
  - "Option C and old-slug aliases deferred to Phase 39; slug fix specified in 37-48 items (a)-(e)"
requirements-completed: [QUICK-261010-ppb, WINDOWS-36, WINDOWS-37]
completed: 2026-10-10
duration: ~55 min
---

# Quick 261010-ppb: Owner rulings 2026-10-10 package Summary

**Six owner rulings shipped as one package:**

- Customer create and edit now need SHOP_MANAGER, and delete needs Group admin, decided before the idempotency replay.
- An unreadable shop day pre-fills as open all day, in the owner's words.
- A refused pause is answered only by the K2 "access ended" screen.
- The stopped-board alarm stops itself after 2 minutes.
- The vendor realm SSO maximum is now 14 h, from the template through to the running server. Refresh-token rotation stays off.

Every ruling went RED first, saw its break arms go red, and was proven on the rebuilt runtime, which is at 4/4 freshness.

## Commits (TASK_BASE 10bf2c59, branch phase-37-ops-readiness, nothing pushed)

| sha | subject | paths |
|---|---|---|
| 29476bea | test: RED arms for the customer write floors (option E) | CustomerAccessGateIntegrationTest.java |
| ccbdfe0d | docs: customer-floor RED evidence and task base | evidence/task-base.txt, red-customer-floors.json |
| 8c79c178 | feat: customer write floors (option E) | ShopAccessService.java, CustomerController.java, ScopedWriteAccess/TypedNotFoundBody/VanishedRowStatus ITs, docs/api/openapi-snapshot.json |
| 07169522 | test: RED for the hours pre-fill, pause refusal and alarm cap | 5 jest files (incl. new kitchen-route-calls.test.ts) |
| b63bd043 | docs: frontend RED evidence | red-hours.json, red-pause.json, red-alarm.json |
| 2752ff50 | feat: unreadable day pre-fills as one all-day window (D-25) | opening-schedule-editor.tsx, hours-notice-card.tsx |
| d0be0886 | fix: pause shop-access-denied answered by K2 only (D-08) | pause-orders-control.tsx, kitchen-route-calls.ts, app/dashboard/kitchen/page.tsx |
| bf823ff1 | feat: alarm stops by itself after 2 minutes | kds-sound.tsx |
| 56808fd6 | docs: UI-SPEC rows and WINDOWS 36-38 | 37-UI-SPEC.md, WINDOWS.md |
| 587efcb2 | feat: vendor realm SSO max 14 h | infra/keycloak/realm-export.template.json, infra/keycloak/README.md |
| 19578dc1 | docs: WINDOWS 39-42 | WINDOWS.md |
| 27eec7c2 | docs: D-14 amended, option C and slug follow-ups recorded | 37-CONTEXT.md, deferred-items.md, 37-48-PLAN.md, 37-20-SUMMARY.md, ROADMAP.md |
| 1f6402f9 | docs: D-14 sub-bullet names the owner ruling in lower case | 37-CONTEXT.md |
| 82dcc863 | docs: customer-floor break arms | evidence/break-arms.txt |
| 88b81aec | docs: live hours, pause and frontend parity evidence | break-arms.txt, live-hours.cjs, live-probes.md, 3 screenshots |
| fa3552fa | docs: live core-java parity, customer floor and realm evidence | live-probes.md, break-arms.txt |

The commit count is measured, not estimated: `git rev-list --count 10bf2c59..HEAD` gives 16. None of the commit messages carries an attribution line. The check is `rg` over every message: rc 1, meaning nothing matched. Its control, a sample Co-Authored-By trailer, gives rc 0. STATE.md (8 inserted bullets, 0 deletions), this SUMMARY and the PLAN are left uncommitted for the orchestrator.

## RED evidence and break arms

### Core-java RED

- `red-customer-floors.json` classifies as RED_EVIDENCE_OK. Its target is the STAFF create arm, which returned 201 where 403 was expected.
- Observed split: **7 failed / 15 passed**, against a predicted 6 / 16.
- The six predicted arms all failed on status: 201, 200, 204, 201, 204 and 201.
- The seventh failure is `deHonouredJitGroupAdmin_createAndDelete_403`. It failed on its **requiredRole** assertion: the unfixed gate refused with STAFF, and SHOP_MANAGER was expected. So the JIT caller was already refused at RED, and only the reported floor was wrong.
- Every control arm passed.

### Frontend RED

- red-hours, red-pause and red-alarm all classify as RED_EVIDENCE_OK, from one TAP run.
- The prediction held, with three explained differences:
  1. The registry guard file failed to load, because its module ships with GREEN.
  2. The "403 of another type" case failed: at TASK_BASE every 403 showed the manager-referral copy.
  3. The 37-21 long-text layout test failed only on its locator wording.
- The mute case and the untouched-save case passed, as predicted.

### Break arms (both directions are in `evidence/break-arms.txt`)

- **Core-java:**
  - B1 (create on the any-grant gate) went red with 201.
  - B2 (floor on the unkeyed branch only) went red with 201 on the replay and 201 on the keyed create.
  - B3 (tenant-wide disjunct dropped) went red with 403.
  - B4 (reads `m.isGroupAdmin()`) went red with 201.
  - B5 (delete on the any-grant gate) went red with 204.
  - Each was restored from HEAD and matched by sha256. The closing clean was the full suite.
- **Frontend:**
  - H1 (pre-fill removed) turned the partial-fix test red, plus the other two pre-fill tests.
  - H2 (plural hard-coded) turned the singular card test red.
  - P1 (inline copy restored) found one role=alert.
  - R1 (a call declared SHOP_MANAGER) turned the floor test red.
  - R2 (an undeclared `/api/v1/promotions` literal) turned the declared-paths test red.
  - A1 (cap removed) went from 123 to 153 alarm starts.
  - Every restore matched by sha256. Closing clean: 82/82.
- **The verify blocks' own checks** were each shown to fail on TASK_BASE or on a broken copy. That covers the anchored counts, snapshot floors and scope, ledger scripts, template diff, CONTEXT/37-20/deferred/STATE/37-48/ROADMAP checks, the auth.ts guard, and a plan-structure copy with the task name removed (rc 1).

## Test totals

- **Fixture raises:** each one gives a caller the role it now legitimately needs. No expected status was changed and no strict-OFF was added; the fixture-purity scan returns rc 1, and its positive control rc 0.
  - ScopedWriteAccess: the writer becomes SHOP_MANAGER on SHOP_A, and the delete arm gets its own fixed operator Group admin.
  - TypedNotFoundBody: PUT runs as tenant-wide SHOP_MANAGER and DELETE as operator Group admin.
  - VanishedRowStatus: PUT runs as tenant-wide SHOP_MANAGER and DELETE as operator Group admin. This one is a deviation, described below.
- **Core-java, full suites on fresh XML (one run):**
  - Unit: **1683/0/0/1**.
  - Integration: **1081/0/0/1** = 1071 + N.
  - N = (+@Test lines) − (−@Test lines) in the test diff = 10 − 0 = 10. CustomerAccessGate went from 12 to 22 tests.
  - The baseline applies: `git diff --quiet cb8cf9d1 10bf2c59 -- core-java/` returned rc 0.
- **Jest:** baseline re-measured at TASK_BASE was 2607 tests / 220 suites. Final is **2616 / 221**, 0 failed.
  - That is +9 tests (editor +3, card +1, pause +1, registry +2, alarm +2), −0 removed, and +1 suite.
  - Per-file counts from `count-test-blocks.mjs --family jest --per-file`: editor 26, card 8, pause 17, board-stopped 29, kitchen-route-calls 2. That is 82, against 73 at base.
- **Build and lint:** `npm run build` rc 0, with TypeScript run. `npm run lint` rc 0, and the touched files have 0 warnings.
- **docs/metrics.json** is untouched; the 37-17 VOID stays.

## Live evidence (`evidence/live-probes.md`)

- **Frontend rebuild:**
  - The running bundle carries both ruled sentences: grep rc 0, where the old bundle gave rc 1.
  - It has lost the 37-21 editor sentence and "Ask a manager": rc 1, where the old bundle gave rc 0.
  - The core-java image and container were unchanged by this rebuild.
  - Freshness at that point: frontend FRESH, and core-java the only drift, as expected.
- **live-hours.cjs, ALL PASS:**
  - notices: the singular and plural sentences.
  - editor: Tuesday unticked, 00:00/00:00, "Open 24 hours", the amber line, Sunday Closed. The captured, unforwarded PUT has `tue [{00:00,00:00}]`, `mon 10:00-17:00`, and no `sun`.
  - pause403: K2 headline "Your access has ended: new orders are NOT showing", 0 referral text, 0 control alerts.
  - The shops table is md5-identical before and after (5 rows, superuser).
  - The script's own fail direction (a nonexistent shop) gives rc 1.
- **core-java rebuild:**
  - `check-openapi-snapshot-fresh` gave rc 1 before and rc 0 after.
  - `check-runtime-freshness` gives **PASS 4/4** after the rebuild and again at Step 7.
  - The live `/v3/api-docs` names SHOP_MANAGER and GROUP_ADMIN.
- **Live customer floor (probe user via the Admin API):**
  - As STAFF: GET 200; POST 403 SHOP_MANAGER with 0 rows written; PUT 403 SHOP_MANAGER with the row unchanged.
  - As SHOP_MANAGER: POST 201 (1 row); PUT 200 (name confirmed by SQL); DELETE 403 GROUP_ADMIN with the row kept.
  - Admin DELETE: 204.
  - Residue: 0 customers and 0 shop_staff rows, with positive controls of 1 and 9. The Keycloak user returns 404. The user-profile config is identical by sha.
- **Realm:**
  - **Inventory:** 7 live users. Three are outside the template: `d06-fresh-1791536915` and two `invitee-*@example.test` accounts.
  - An `--override` import would delete those three users and every live jtoye-dev SSO session. It was **not run**, and Keycloak was not restarted.
  - **Apply:** Admin API `PUT /admin/realms/jtoye-dev` with exactly `{"ssoSessionMaxLifespan": 50400}`, taken from the rendered file. Result: 204.
  - **Before → after:** 7200 / false / 1800 → **50400 / false / 1800**.
  - The full-realm key diff lists exactly `ssoSessionMaxLifespan`. Its before-vs-before fail direction lists nothing and returns rc 1.
  - jtoye-customers stays 7200 / true before and after. Users: 7 before, 7 after.
  - **Double redeem:** 200/200 before **and** 200/200 after, so rotation is off. The bogus-token control returns 400.
- **Sign-in after the change:** password grants for admin-user and tenant-a-user both return 200 with an access token. The `notices` re-run reached /dashboard and printed ALL PASS.
- **Data left behind:** the probe's `user_directory` row, the `customers_aud` revisions of the created-then-deleted probe customer, and the three runtime Keycloak users that were already there. No test order was created.

## Deviations from Plan

### Auto-fixed issues

1. **[Rule 3] The VanishedRowStatus DELETE arms could not use the existing `groupAdmin(tenant)` helper.**
   - That helper is a realm-admin token without the `customers:write` scope, and DELETE's @PreAuthorize still requires the scope.
   - Fix: a new `customerGroupAdmin(tenant)` that seeds an operator tenant-wide GROUP_ADMIN plus the scope, the same shape as the other raises. Commit 8c79c178.
2. **[Rule 3] Hand-editing the WINDOWS #36 reason and #37 description made the next `windows append` refuse.**
   - The cause was "table disagrees with the fenced JSON".
   - Fix: re-render the table region with the ledger's own exported `renderTable` (`broken-windows.cjs`), then append. Commit 56808fd6.
3. **[Rule 1] The plan's prescribed D-14 sentence failed the plan's own check.**
   - The sentence starts "Owner ruling 2026-10-10 amends D-14", but the plan's verify greps the lower-case phrase case-sensitively.
   - Fix: reworded to "D-14 amended by owner ruling 2026-10-10". Commit 1f6402f9.
4. **[Interpretation] requiredRole is asserted on every new option-E arm. The ten KEEP arms keep their unchanged type-only refusal.**
   - This keeps them "unchanged in intent".
   - It made the de-honoured-JIT create/delete arm fail at RED on requiredRole, which is the explained 7th RED failure.
5. **[Interpretation] The registry module was created in the GREEN commit**, so its guard test could not "pass at RED by design". It failed to load instead. Its falsifiers are R1/R2, as planned.
6. **The plan-structure fail direction:** removing `<done>` only produced a warning (rc 0). Removing the task's `<name>` gave rc 1, and that is the recorded fail direction.

## Owner-visible consequences and decisions to take

- **Sunday-overnight spill.** A NEEDS_REVIEW shop with Sunday 18:00-02:00 and an unreadable Monday cannot save its week until Monday's real times are set. Editor and server agree. This is pinned by jest and recorded, not special-cased.
- **Strict-OFF customer DELETE.** `requireGroupAdmin()` calls `onRequest()`, so under strict-OFF an ungranted caller is JIT-provisioned as the day-one Group admin and the delete proceeds. This is the same posture as shop delete, and it is unchanged here. Create and update (`requireAnyRole`) write no grant.
- **STAFF still sees the customer Add/Edit/Delete controls.** A refused write shows the page's destructive toast ("Shop access denied"). This is read from source, not run live. Role-aware hiding was not in the ruling.
- **Refresh-token rotation deferred (orchestrator revision).**
  - A per-process single-flight cannot protect the 3 k8s frontend replicas or `middleware.ts`.
  - Turning rotation on needs a shared guard (a Redis single-flight or proven sticky sessions) or a `refreshTokenMaxReuse` policy, and then its own ruling.
  - `frontend/auth.ts:100` still says "max lifespan 2h". It is left for that work; auth.ts is byte-identical to TASK_BASE.
- **V81 is reserved by 37-27**, so option C takes the next free Flyway version at Phase 39 plan time.
- **Phase 39 is "Platform runtime upgrades".** The option C and slug-alias deferrals sit there as a separate note, and `/gsd-plan-phase 39` decides whether they become 39-F or a phase of their own.
- **Staging and production vendor realms** need the same `ssoSessionMaxLifespan` 50400 change, made by an operator. This is WINDOWS #41.
- **37-23 UAT expectations:**
  - The red screen now appears after 14 h.
  - A revoked STAFF user's pause shows K2 "Your access has ended" and no inline copy (WINDOWS #37).
  - A stopped board falls silent at about 2 minutes, with the red screen still up (WINDOWS #38, by ear).

## Records

- **37-CONTEXT:** D-14 is amended, and the :209 deferral is marked RESOLVED with rotation still deferred.
- **deferred-items:** a new "From quick 261010-ppb" section.
- **37-48 Task 3:** carries items (a)-(e) and the typed 409 `shop-slug-taken`, and plan-structure validates it.
- **37-20-SUMMARY:** line 72 now names SHOP_MANAGER. The :105 `ref:` is untouched.
- **ROADMAP:** a Phase 39 note.
- **UI-SPEC:** rows 407, 458, 780, 791, 794, plus a new "Hours: all-day hint" row.
- **WINDOWS:**
  - #36 is fixed, closed at 2752ff50.
  - #37 is restated.
  - #38 is the alarm-cap unrun-verify entry.
  - #39-#42 are todos: option C, slug aliases, the operator realm change, and the rotation deferral (line 9).
- **STATE:** 7 owner-ruling bullets and 1 orchestrator-revision bullet, uncommitted.

## Cross-cutting dimensions

- **Web performance:** no new dependency. Text and one default changed, and the build is green, so this is N/A beyond that.
- **SEO:** N/A. These are the authenticated dashboard and the API.
- **AI agent-readiness:**
  - The 403 stays typed RFC 7807 with `requiredRole`.
  - The OpenAPI snapshot documents the floors, and the live contract matches it (snapshot gate rc 0).
  - MCP `create_customer` runs as integration-orders-rw, which holds SHOP_MANAGER, so the MCP is unchanged.
- **Client-persisted identity lifecycle:** N/A. Nothing new is stored client-side.
- **Security:** threat register T-ppb-01..04, 09 and 11 are mitigated as planned. T-ppb-05, 07, 10 and 12 are accepted by ruling. No new threat surface.
- **Falsifiable evidence:** both directions are recorded for every criterion.
- **Runtime parity:** PASS 4/4 at the end.

## Known Stubs

None.

## Self-Check: PASSED

- Files exist: ShopAccessService.java, CustomerController.java, kitchen-route-calls.ts, kitchen-route-calls.test.ts, kds-sound.tsx, opening-schedule-editor.tsx, hours-notice-card.tsx, realm-export.template.json, and the evidence files listed above.
- All 16 commits are ancestors of HEAD; this was checked with `git merge-base --is-ancestor`.
- Task 1, 2 and 3 verify blocks each returned rc 0 on the final tree.
