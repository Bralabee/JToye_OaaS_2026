---
phase: quick-261010-ppb
verified: 2026-10-10T19:30:00Z
status: passed
score: 12/12 must-haves verified
covered_files:
  - .planning/quick/261010-ppb-owner-rulings-2026-10-10-package-option-/261010-ppb-PLAN.md
  - .planning/quick/261010-ppb-owner-rulings-2026-10-10-package-option-/261010-ppb-SUMMARY.md
  - core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java
  - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
  - docs/api/openapi-snapshot.json
  - frontend/app/dashboard/kitchen/page.tsx
  - frontend/components/dashboard/hours-notice-card.tsx
  - frontend/components/dashboard/kitchen/kds-sound.tsx
  - frontend/components/dashboard/kitchen/kitchen-route-calls.ts
  - frontend/components/dashboard/kitchen/pause-orders-control.tsx
  - frontend/components/dashboard/shops/opening-schedule-editor.tsx
  - infra/keycloak/realm-export.template.json
covered_digest: "v3:sha256:606c9c9aed558fd57e324dfa14e6c6d96f1c4874293ec408e128bd36866a636b"
behavior_unverified: 0
overrides_applied: 0
re_verification: false
---

# Quick 261010-ppb: Owner rulings 2026-10-10 package, Verification Report

**Goal:** Owner rulings package: option E customer write floors, hours editor all-day pre-fill and copy, pause-403 copy, alarm cap, vendor realm SSO max 14 h with the D-14 amendment, record option C and slug follow-ups.
**Verified:** 2026-10-10
**Status:** passed
**Re-verification:** No, initial verification
**Range inspected:** 16 executor commits, 10bf2c59..fa3552fa, branch phase-37-ops-readiness.

I started from "the goal was not achieved". Each truth below was checked against the code and records, not against SUMMARY.md. Where I re-ran something myself it says so. Where I relied on the executor's recorded output I say that too, and say how I judged the record.

## Observable Truths

| # | Truth (abridged) | Status | Evidence |
|---|---|---|---|
| 1 | STAFF: customer GET 200; POST/PUT 403 SHOP_MANAGER; DELETE 403 GROUP_ADMIN; table unchanged by SQL | VERIFIED | `CustomerController` at HEAD: `requireAnyAccess()` on list and getById (count 2), `requireAnyRole(ShopRole.SHOP_MANAGER)` as first statement of create and update (before the Idempotency-Key branch), `requireGroupAdmin()` on delete. **I re-ran** `CustomerAccessGateIntegrationTest` (Testcontainers, real HTTP stack): 22 tests, 0 failures, 0 errors, 0 skipped, XML newer than my start marker. B1 and B5 break-arm output is in break-arms.txt (201 and 204, uuid-bearing bodies, so not narrated). Live: STAFF probe user got POST 403 SHOP_MANAGER with 0 rows, PUT 403 with the row unchanged. |
| 2 | Per-shop and tenant-wide SHOP_MANAGER create/update; their DELETE 403; operator GROUP_ADMIN DELETE 204 | VERIFIED | Arms `shopManagerOfOneShop_createAndUpdate_succeed`, `tenantWideShopManagerWithNoShops_create_201`, `shopManagerOfOneShop_delete_403_rowSurvives`, `operatorGroupAdmin_delete_204` are in the 22 green. B3 (tenant-wide disjunct dropped) reds 403; B5 reds 204. Live: SHOP_MANAGER POST 201, PUT 200 (name re-read by SQL), DELETE 403 with the row kept; admin DELETE 204 and 0 rows. |
| 3 | STAFF replay of a SHOP_MANAGER's Idempotency-Key gets 403, never the stored 201; STAFF fresh key writes 0 rows | VERIFIED | `staffReplayOfShopManagerKey_403_notStored201` and the keyed half of the STAFF create arm are green in my run. B2 (gate only the unkeyed branch) reds both with `expected 403 but was 201`. Code: the floor is the first statement of `create()`, ahead of `idempotencyService.execute`. |
| 4 | De-honoured JIT tenant-wide GROUP_ADMIN refused POST and DELETE; strict OFF still creates 201 and writes no shop_staff row | VERIFIED | `deHonouredJitGroupAdmin_createAndDelete_403` green; B4 (reads `m.isGroupAdmin()`) reds with 201 on the POST. `requireAnyRole` reads only `isGroupAdmin()` (the ladder) plus `resolveMembership`; it never calls `onRequest()` or `grantedShopIds()`. `strictOff_ungrantedUnchanged_andNoJitRow` asserts create 201 and `shopStaffRows` for the caller (green). |
| 5 | Snapshot names the floors, regenerated, differs only under the two customer paths; full suites green on fresh XML with baseline + N | VERIFIED | I diffed `docs/api/openapi-snapshot.json` 10bf2c59..HEAD: only the three customer operation descriptions and the three 403 descriptions changed (SHOP_MANAGER on POST/PUT, GROUP_ADMIN on DELETE). `check-openapi-snapshot-fresh` rc 1 before the rebuild and rc 0 after (live-probes.md section 2). Suite totals unit 1683/0/0/1 and integration 1081/0/0/1 = 1071 + 10 are the executor's recorded fresh-XML figures; I did not re-run the full suites (excluded by brief). N=10 is consistent with CustomerAccessGate going 12 to 22 tests, which I confirmed. |
| 6 | Hours editor: unreadable NEEDS_REVIEW day opens unticked with ONE 00:00-00:00 window, ruled amber line, "Open 24 hours" hint; a day written closed stays Closed; partial-fix save sends the day, never omits it | VERIFIED | `initialDays` seeds `{closed:false, windows:[00:00-00:00]}` from the same `unreadableDays` predicate the server uses (`ShopAvailabilityPolicy.withUnreadableDaysOpen`). `HOURS_COPY.unreadable` reads exactly the ruled sentence. Server accepts 00:00-00:00 as a window (`TimeWindow.overnight()` is close-not-after-open). **I re-ran** the 5 jest files: 82/82. H1 (pre-fill removed) reds 3 tests, including the partial-fix test. Live (live-hours.cjs): Tuesday unticked, 00:00/00:00, amber line, Sunday Closed; the captured, unforwarded PUT carries `tue [{00:00,00:00}]` and `mon 10:00-17:00`. |
| 7 | Needs-review card sentence, singular and plural | VERIFIED | `needsReviewSentence` returns the ruled text with `that day`/`those days`. H2 (hard-coded plural) reds the singular test. Live: both sentences rendered on the dashboard, the 37-21 wording gone. |
| 8 | Pause shop-access-denied 403: no inline text, K2 overlay; other 403 types show generic error; jest invariant on kitchen-route API calls | VERIFIED | `pause-orders-control.tsx` returns early for `isShopAccessDenied(...)` and shows `describeLoadError` otherwise; the old `PAUSE_REFUSED_COPY` is deleted. `KITCHEN_ROUTE_CALLS` declares 11 calls; **I checked each cited server floor** (`OrderService.java:452/:324/:560/:648`, `ShopService.java:171/:459/:485`) is `ShopRole.STAFF`, and `:179` is a narrowing list read. P1 (inline copy restored) found one role=alert; R1 and R2 each red the registry test. The live check stubs the PUT to 403 in the browser, which is the same event path the real interceptor drives (see Notes). |
| 9 | Alarm still ignores mute, still stops on Silence; unsilenced alarm stops itself at 120 s, overlay stays | VERIFIED | `kds-sound.tsx`: `BOARD_STOPPED_ALARM_MAX_MS = 120_000`, cap measured from the episode start (ref reset only when `alarm` goes false). The jest test uses fake timers and the recording `AudioContext`: starts stop growing after 120 s while the dialog and pill remain; the mute test pins 3 then 6 starts. A1 (cap removed) reds 123 to 153. This is behaviour-tested, not presence-only. |
| 10 | Vendor realm template 50400 / idle 1800 / revokeRefreshToken false, differs from base in exactly ssoSessionMaxLifespan; customers template byte-identical; running realm reads back 50400/false/1800; double redeem still 200/200; e2e vendor still signs in | VERIFIED | I diffed the template: one line, 7200 to 50400. `realm-export-customers.template.json`, `frontend/auth.ts` and `docs/metrics.json` are byte-identical to 10bf2c59 (git diff --quiet rc 0). live-probes.md section 4 holds the before/after read-back from the Admin API (7200/false/1800 then 50400/false/1800), a full-realm key diff naming only `ssoSessionMaxLifespan` with a before-vs-before control that lists nothing, and a double redeem of 200/200 before and after (bogus-token control 400). The inventory found 3 runtime users outside the template, so the override import was correctly not run. Password grants for admin-user and tenant-a-user return 200 afterwards. I did not re-query the live realm (needs admin credentials); I judged the record because it carries the fail direction and controls. |
| 11 | Runtime matches branch: freshness 4/4, openapi gate rc 0, live STAFF 403 / SHOP_MANAGER 201, probe artefacts removed | VERIFIED | **I re-ran** `scripts/check-runtime-freshness.sh` now: `PASS: 4 running built service(s) match the source tree`, rc 0. Fail direction is recorded (core-java DRIFT on 8c79c178 and openapi rc 1 before the rebuild). Live floor results are in section 3 with SQL residue 0 and positive controls 1 and 9, Keycloak user 404, and the user-profile config sha unchanged. |
| 12 | Records match the rulings | VERIFIED | Checked by diff: 37-CONTEXT D-14 amended and the deferral marked RESOLVED with rotation still deferred; 37-20-SUMMARY phone-order line names SHOP_MANAGER; 37-48 Task 3 carries items (a)-(e) plus the typed 409 and the alias deferral; ROADMAP Phase 39 has a separate "also deferred here" note; deferred-items has "From quick 261010-ppb"; `infra/keycloak/README.md` records rotation OFF and deferred; UI-SPEC rows updated. WINDOWS parses as 42 entries (open 15, fixed 27, consistent): #36 fixed with its closing sha 2752ff50, #37 restated, #38 alarm, #39 option C, #40 aliases, #41 operator realm change, #42 rotation deferral. STATE has the eight bullets (7 rulings plus the orchestrator revision). |

**Score:** 12/12 truths verified, 0 behaviour-unverified.

## Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `ShopAccessService.java` | `requireAnyRole(ShopRole floor)`, honour-aware, no JIT side effect | VERIFIED | Present at HEAD with the planned body: null check, GROUP_ADMIN floor is an IllegalArgumentException, `isGroupAdmin()` first, then tenant-wide or any per-shop role, else `ShopAccessDeniedException(null, floor)`. |
| `CustomerController.java` | Reads any-grant, create/update SHOP_MANAGER, delete Group admin | VERIFIED | Counts 2 / 2 / 1; operation and 403 descriptions name the floors. |
| `CustomerAccessGateIntegrationTest.java` | RED-first arms | VERIFIED | 22 arms, all green in my run; RED record was RED_EVIDENCE_OK. |
| `opening-schedule-editor.tsx` | Pre-fill and ruled copy | VERIFIED | Contains `so this day is open all day`. |
| `hours-notice-card.tsx` | Ruled sentence | VERIFIED | Contains `Check and save your hours`. |
| `kitchen-route-calls.ts` | `KITCHEN_ROUTE_CALLS` | VERIFIED | 11 calls, each with a cited server line (floors checked). |
| `kds-sound.tsx` | `BOARD_STOPPED_ALARM_MAX_MS` | VERIFIED | Present and wired into `useKitchenAlert`. |
| `realm-export.template.json` | `"ssoSessionMaxLifespan" : 50400` | VERIFIED | One-line diff. |
| `evidence/live-probes.md` | Parity, realm, floor evidence, both directions | VERIFIED | Contains the before and after read-backs and fail directions. |

## Key Link Verification

| From | To | Via | Status |
|---|---|---|---|
| `CustomerController.create/update` | `requireAnyRole(SHOP_MANAGER)` | first statement, before `idempotencyService.execute` | WIRED (B1/B2 prove it) |
| `requireAnyRole` | `isGroupAdmin()` / `resolveMembership(requireVendorUserId())` | ladder decides Group admin; floor reads `tenantWideRole` and `perShopRole`; no `onRequest()` | WIRED (B3/B4 prove it) |
| editor `initialDays` | `unreadableDays()` and the server's `withUnreadableDaysOpen` | the same predicate seeds the window the server enforces | WIRED |
| realm template | running `jtoye-dev` realm | render, then Admin API `PUT {"ssoSessionMaxLifespan":50400}` | WIRED (read-back recorded) |

## Behavioural Spot-Checks and Fail Direction

| Behaviour | Command | Result | Status |
|---|---|---|---|
| Customer floors through MockMvc + Testcontainers | `./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --tests '...CustomerAccessGateIntegrationTest' --no-daemon` | rc 0; 22/0/0/0, XML fresh vs marker | PASS |
| Editor, card, pause, registry, alarm | `npx jest` on the five files | 5 suites, 82 passed | PASS |
| Runtime parity | `scripts/check-runtime-freshness.sh` | PASS 4/4, rc 0 | PASS |
| RED records | `gsd_run check tdd-red-evidence` on the four records | all `RED_EVIDENCE_OK` | PASS |
| Fail direction of that checker | same, on a copy of the customer record with `exitCode` set to 0 | `INVALID_RED` (copy removed afterwards) | PASS (checker can fail) |
| Untouched scope | `git diff --quiet 10bf2c59 HEAD` on `docs/metrics.json`, `frontend/auth.ts`, customers realm template | rc 0 | PASS |
| No attribution lines | `rg -i 'co-authored|generated with|claude.ai/code'` over the 16 messages | rc 1 (no match) | PASS |

Break arms B1-B5, H1, H2, P1, R1, R2 and A1 were not re-run by me, because that means editing source and the brief forbids it. I judged the recorded output instead: each arm shows the mutation diff, a non-zero test rc, the named test failing with its real assertion message (uuid-bearing response bodies, `123 vs 153`, and so on), a sha256 restore check, and a closing clean run. That is the shape of real output.

## Requirements Coverage

| Requirement | Status | Evidence |
|---|---|---|
| QUICK-261010-ppb | SATISFIED | Truths 1-12. |
| WINDOWS-36 | SATISFIED | Ledger #36 fixed at 2752ff50; the editor and card carry the ruled copy. |
| WINDOWS-37 | SATISFIED | Restated as an open unrun-verify entry (#37); the code change is in d0be0886. |

## Anti-Patterns

Debt-marker scan (TBD/FIXME/XXX) over the changed implementation files found none the phase introduced. No stubs: every touched artifact is substantive and wired, and the only new module (`kitchen-route-calls.ts`) is consumed by its guard test and cited from `page.tsx`.

## Notes for the owner (none blocks the goal)

These are real, and the executor recorded them. They are not defects against the must-haves.

1. **Live evidence for the pause and the alarm is partly simulated.** The pause 403 live check stubs the PUT in the browser. It exercises the real listener, overlay and control, but not a server refusal for a revoked STAFF user. The alarm cap is proven in jest with fake timers only. Both are open ledger entries (#37, #38) routed to 37-23 UAT, where the alarm is checked by ear.
2. **STAFF still sees the customer Add/Edit/Delete controls.** A refused write shows the generic destructive toast. The ruling did not ask for role-aware hiding. This was read from source, not run live.
3. **Strict-OFF customer DELETE.** `requireGroupAdmin()` calls `onRequest()`, so with strict scoping OFF an ungranted caller is JIT-provisioned and the delete proceeds. This is the same posture as shop delete today and is unchanged. Create and update write no grant.
4. **Sunday-overnight spill.** A NEEDS_REVIEW shop with a Sunday 18:00-02:00 window and an unreadable Monday cannot save its week until Monday's real times are set. Editor and server agree, and it is pinned by jest.
5. **Refresh-token rotation was removed from the package by orchestrator revision.** `revokeRefreshToken` stays false, `frontend/auth.ts` is untouched, and the deferral is recorded (WINDOWS #42, CONTEXT, README, STATE). `auth.ts:100` still says "max lifespan 2h" and is left for that work. Accepted, not a gap.
6. **Staging and production vendor realms** need the same 50400 change from an operator (WINDOWS #41). Not in this repo.
7. **Ledger gate.** `open_count` is now 15. Entries #37-#42 are open by design. If `workflow.windows_enforce` is on, `/gsd-ship` stays blocked until they are closed or waived by the owner.
8. **docs-metrics gate** is VOID on the pre-existing 37-17 table, as the brief says. `docs/metrics.json` is untouched.

## Gaps Summary

None. All 12 must-have truths hold in the code and on the rebuilt runtime. The core floor behaviour was re-proven by me through the real HTTP stack (22/22), the frontend behaviour by 82/82 jest, and runtime parity by a fresh 4/4 freshness pass. The fail direction is recorded for every criterion, and I confirmed the RED-evidence checker itself can return INVALID_RED.

---

_Verified: 2026-10-10_
_Verifier: Claude (gsd-verifier)_
