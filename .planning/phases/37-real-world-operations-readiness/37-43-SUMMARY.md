---
phase: 37-real-world-operations-readiness
plan: 43
subsystem: api
tags: [webhooks, auto-pause, held-deliveries, email, dashboard, openapi, uxt-038, rwo-038]
status: complete

requires:
  - phase: 22 (COMMS-04/05/06)
    provides: "webhook_subscription / webhook_delivery, the per-tenant delivery worker, the dashboard delivery log and auto-pause banner"
  - phase: 37-07
    provides: "EmailNotificationService's platform-sent plain-text pattern (sendStaffInvite) and the SentMail wire-level assertions"
provides:
  - "webhook.delivery.auto-pause-after-failed-deliveries (default 3): consecutive DELIVERIES that exhausted max-attempts, replacing the 10-attempt threshold"
  - "WebhookDelivery.Status.HELD: kept, not attempted, while the subscription is PAUSED or AUTO_PAUSED; resume re-queues every HELD row; revoke fails them"
  - "EmailNotificationService.sendWebhookPaused(contact, url, pausedAt, heldCount): one email per ACTIVE -> AUTO_PAUSED transition, after commit"
  - "WebhookSubscriptionDto.heldCount on read, list, pause and resume"
  - "Dashboard: outline 'Held' badge (PauseCircle) in the log and the status filter; the UI-SPEC E2 auto-pause banner with 'Resume and deliver held events'"
affects: [37-E gate, webhooks, dashboard-webhooks, notification, phase-37-uat]

actuals:
  tokens: 25840
  tasks: 3
  commits: 7
plan_head_before: c80bfb6820b5decd49432585d0e78061e12037cc
plan_head_after: 2bf59cf4c3218024ff6dcfba6996a7f735e39cc3

tech-stack:
  added: []
  patterns:
    - "Status-transition side effects run once: the email is scheduled only on the ACTIVE -> AUTO_PAUSED write, after commit (TransactionSynchronization.afterCommit), so a rolled-back tick sends nothing and a paused endpoint cannot pause again until resumed"
    - "Bulk @Modifying(flushAutomatically = true) without clearAutomatically inside a worker that still holds managed rows from its claimed batch: the caller's FAILED write is flushed first and excluded, the batch entities stay attached"
    - "A @ConfigurationProperties rename is guarded by binding the shipped yml with NoUnboundElementsBindHandler, because Boot ignores an unknown key and ConfigKeyContractTest judges only spring/management/server/logging"
    - "A badge map lookup has a fallback for an unknown enum value, so a new backend status cannot crash the page"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/webhook/WebhookAutoPauseIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/webhook/WebhookPropertiesBindingTest.java
    - core-java/src/test/java/uk/jtoye/core/notification/EmailNotificationServiceWebhookPausedTest.java
    - frontend/app/dashboard/webhooks/__tests__/webhooks-held.test.tsx
    - .planning/phases/37-real-world-operations-readiness/evidence/37-43-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-43-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-43-red-task3.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookProperties.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookDeliveryWorker.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookDelivery.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookDeliveryRepository.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookFanoutListener.java
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookSubscriptionService.java
    - core-java/src/main/java/uk/jtoye/core/webhook/dto/WebhookSubscriptionDto.java
    - core-java/src/main/java/uk/jtoye/core/notification/EmailNotificationService.java
    - core-java/src/main/resources/application.yml
    - core-java/src/test/java/uk/jtoye/core/webhook/WebhookDeliveryWorkerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/webhook/WebhookSubscriptionServiceTest.java
    - frontend/lib/webhooks-api.ts
    - frontend/components/dashboard/webhooks/status-badge.tsx
    - frontend/app/dashboard/webhooks/[id]/page.tsx
    - docs/api/openapi-snapshot.json
    - docs/webhooks.md
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "37-43: auto-pause counts consecutive DELIVERIES that exhausted max-attempts (default 3), not attempts; a delivered event resets it. The old key auto-pause-threshold is removed, not aliased: WebhookPropertiesBindingTest fails if it comes back"
  - "37-43: HELD applies to PAUSED and AUTO_PAUSED only. A revoked or missing subscription still fails its rows (it can never be resumed), and revoke fails anything already held"
  - "37-43: an event raised while AUTO_PAUSED is written HELD (the platform paused the endpoint, the vendor did not ask to miss events); an event raised during a MANUAL pause is still not recorded, as before"
  - "37-43: resume re-queues HELD rows as PENDING with attempt_count 0 (a fresh budget of 8), keeping the last real error on the row"
  - "37-43: heldCount 0 omits the held sentence in both the banner and the email; 1 is singular. The banner's button reads 'Resume and deliver held events' at every count, as UI-SPEC E2 states it"

patterns-established:
  - "One email per state transition, scheduled after commit from the code that performs the transition"

requirements-completed: [RWO-038]

coverage:
  - id: D1
    description: "Two events that each fail five times and then succeed never pause the endpoint (production attempt budget and default, scripted receiver); three exhausted deliveries in a row pause it and two do not; a delivered event resets the count"
    requirement: "RWO-038"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/webhook/WebhookAutoPauseIntegrationTest.java#twoFlakyEventsDoNotPauseTheEndpoint, #threeExhaustedDeliveriesInARowPauseTheEndpoint, #aDeliveredEventResetsTheCount (RED 3/3 on the pre-fix tree, GREEN after)"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/webhook/WebhookPropertiesBindingTest.java (2/2; arm A: the retired key in the yml reds it)"
        status: pass
    human_judgment: false
  - id: D2
    description: "A paused endpoint holds its queued events (HELD, not FAILED), events raised while auto-paused are queued HELD, the worker leaves them alone, resume re-queues and delivers them, revoke fails what is held"
    requirement: "RWO-038"
    verification:
      - kind: integration
        ref: "WebhookAutoPauseIntegrationTest#pauseHoldsQueuedEventsEmailsTheOwnerOnceAndResumeDeliversThem, #manualPauseHoldsAndResumeDelivers (arms C2, C4, C6, C9)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The tenant contact receives one plain-text email per auto-pause, after commit: subject, the pause time in UK time, '1 event is held' / '3 events are held', the sentence omitted at 0, the fix line; no second email on resume; no contact is a WARN and the worker carries on"
    requirement: "RWO-038"
    verification:
      - kind: integration
        ref: "WebhookAutoPauseIntegrationTest#pauseEmailCountsSeveralHeldEvents, #pauseEmailOmitsTheHeldSentenceWhenNothingIsHeld, #noContactEmailIsAWarningNotAFailure"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/notification/EmailNotificationServiceWebhookPausedTest.java (6/6: BST and GMT, plural/singular/zero, URL control characters, disabled and incomplete; arm E reds 3)"
        status: pass
    human_judgment: false
  - id: D4
    description: "heldCount is on the subscription read, list, pause and resume responses and in the committed OpenAPI contract (additive: heldCount, the HELD filter value, the consecutiveFailures description)"
    verification:
      - kind: integration
        ref: "WebhookAutoPauseIntegrationTest (jsonPath $.heldCount on GET, list, pause, resume, revoke); core-java/src/test/java/uk/jtoye/core/integration/OpenApiSnapshotTest.java (1/0)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Dashboard: a HELD delivery renders an outline 'Held' badge with PauseCircle and is filterable; held, failed and delivered rows coexist; the banner says how many events are held (1 singular, 0 omitted) and its 'Resume and deliver held events' button resumes; the loading branch, delivery log, replay and last-error line are unchanged"
    requirement: "RWO-038"
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/webhooks/__tests__/webhooks-held.test.tsx (7/7; RED 7/7; arm F1 '0 events are held' reds the zero and singular arms); delivery-log.test.tsx and webhooks-page.test.tsx unchanged and green (22/22 across the three)"
        status: pass
    human_judgment: false
  - id: D6
    description: "The running stack serves this: a live endpoint that fails three events pauses, holds the next ones, emails the contact (Mailhog), and Resume delivers them; the dashboard shows the Held badge and the banner"
    verification: []
    human_judgment: true
    rationale: "Nothing here was run against the compose stack. It still runs the 37-C images cut at d060d0af and is BEHIND the branch; the 37-E gate must rebuild core-java and frontend and replay this live (with Mailhog for the email)."

duration: 58min
completed: 2026-10-13
---

# Phase 37 Plan 43: Honest webhook auto-pause Summary

**A webhook endpoint now auto-pauses only after three consecutive events have each used all 8 attempts. Two flaky events no longer pause it in about 46 seconds. While an endpoint is paused, its events are HELD instead of thrown away as FAILED. Resuming delivers them. The tenant's contact address gets one plain-text email per pause, which says how many events are held. The dashboard shows an outline "Held" badge and a banner whose button reads "Resume and deliver held events".**

## Performance

- **Duration:** 58 min
- **Started:** 2026-10-13T01:52:16Z
- **Completed:** 2026-10-13T02:50Z
- **Tasks:** 3, each a TDD cycle (RED, then GREEN). Task 1 was a tracer.
- **Files modified:** 27: 9 main, 6 test, 3 frontend source, 3 RED records, the OpenAPI snapshot, `docs/webhooks.md`, metrics, README, CLAUDE.md and AGENTS.md.

## Accomplishments

- **UXT-038 fails before the change and passes after.**
  - Before: production defaults (8 attempts, threshold 10). Two events that each fail 5 times and then answer 200 left the subscription **AUTO_PAUSED**. The worker counted every failed attempt across both deliveries and tripped on the 10th.
  - After: the subscription stays **ACTIVE**, and both events are DELIVERED on their 6th attempt.
- **What pauses an endpoint now.** `webhook.delivery.auto-pause-after-failed-deliveries` (default 3; env `WEBHOOK_DELIVERY_AUTO_PAUSE_AFTER_FAILED_DELIVERIES`) counts consecutive deliveries that each exhausted `max-attempts`. A delivered event resets the count. The old `auto-pause-threshold` key is gone from `WebhookProperties` and `application.yml`. No compose file, `.env.example` or k8s manifest ever set it; I searched to confirm.
- **No event is lost because an endpoint paused.**
  - When the endpoint pauses, every other queued (PENDING or RETRYING) row of that subscription becomes HELD in the same transaction.
  - A row claimed for a PAUSED or AUTO_PAUSED subscription becomes HELD. Before, it became FAILED.
  - An event raised while the endpoint is AUTO_PAUSED is written HELD.
  - A manual pause holds what is already queued.
  - Resume re-queues every HELD row as PENDING with a fresh attempt budget.
  - Revoke fails what is held.
- **The owner is told once.** On the ACTIVE -> AUTO_PAUSED transition, the worker reads `tenants.contact_email` and, **after commit**, calls `EmailNotificationService.sendWebhookPaused(contact, url, pausedAt, heldCount)`. The email is plain text, uses the UI-SPEC E2 copy and states the time in UK time. The vendor URL is sanitised. Neither the URL nor the address is logged. With no contact address on file, the worker logs a WARN (`event=webhook_pause_email_skipped reason=no_contact_email`) and carries on. Resuming and delivering sends nothing more.
- **`heldCount`.** `WebhookSubscriptionDto.heldCount` is counted by the database: one query per single read, and one grouped query for the list. Each query also filters on the tenant explicitly, on top of RLS. The OpenAPI diff is additive.
- **Dashboard (UI-SPEC E2).**
  - HELD renders as a `Badge variant="outline"` with `PauseCircle` and "Held", and is in the status filter.
  - The banner reads "**Deliveries to this endpoint are paused** · We paused it after deliveries kept failing. {n} events are held and will be delivered when you resume. Fix the endpoint first, then: [Resume and deliver held events]". It uses "1 event is held" for one and drops the held sentence at 0.
  - These goods are preserved: the last-error line, the summary card's Pause/Resume, Rotate and Revoke, the delivery log, replay, and the loading, error and width-tier branches.
- `docs/webhooks.md` §5 now describes the delivery-level rule, HELD events, `heldCount` and the email.

## Task Commits

1. **Task 1 (tracer): transient failures no longer pause an endpoint.**
   - `ba5f9bf1` (test, RED)
   - `d1e00183` (feat, GREEN)
   - Tracer gate (end-of-phase mode, automated-only verify): I re-ran the verify on the committed tree. Results: AutoPause 3/0 and Worker 4/0. Then I expanded.
2. **Task 2: paused endpoints hold their events, resume delivers them, and the owner is told once.**
   - `bd9ce37f` (test, RED)
   - `62235139` (feat, GREEN, including the OpenAPI snapshot)
3. **Task 3: the dashboard shows held deliveries and an honest pause banner.**
   - `4f22b6d4` (test, RED)
   - `55a0b90b` (feat, GREEN)
4. **Metrics:** `2bf59cf4` (docs: 5814 → 5837).

**Plan metadata:** this SUMMARY commit.

## TDD Gate Compliance

| Task | RED (commit, evidence) | GREEN | Fail-direction arms |
|------|------------------------|-------|---------------------|
| 1 | `ba5f9bf1`: `37-43-red-task1.json` RED_EVIDENCE_OK (JUnit: 3 tests, 3 failed) | `d1e00183`: AutoPause 3/0, Worker 4/0, binding 2/0 | A, B |
| 2 | `bd9ce37f`: `37-43-red-task2.json` RED_EVIDENCE_OK (JUnit: 8 tests, 5 failed; the 3 Task 1 arms still passing) | `62235139`: webhook ITs 45/0 + OpenApiSnapshotTest 1/0; webhook and notification unit tests green | C2, C4, C6, C9, E |
| 3 | `4f22b6d4`: `37-43-red-task3.json` RED_EVIDENCE_OK (TAP: 7 tests, 7 failed) | `55a0b90b`: webhooks jest 22/22, build rc 0 | F1 (the plan's zero-count arm), F2 |

**RED semantic assessments.**
- **Task 1.** The target failed at its first planned assertion: expected ACTIVE, was AUTO_PAUSED. That is the UXT-038 defect. The two other arms failed on the attempt-counting rule they replace. After one exhausted delivery the count read 8 where 1 was expected. The reset arm read 10 where 2 was expected, because the endpoint had already paused partway through the second dead delivery.
- **Task 2** (run on the Task 1 GREEN tree). The target failed at its first new assertion: HELD expected 1, was 0. The queued event was thrown away as FAILED, which is the lost-event half of UXT-038. The other arms failed as follows:
  - Plural arm: 0 HELD where 3 were expected.
  - Zero-held arm: no email arrived within 10 s.
  - No-contact arm: the WARN line was missing.
  - Manual-pause arm: there was no `heldCount` field on the response.
- **Task 3.** The target failed on the missing banner heading, with the page rendered and the old banner present. The 1-held, 0-held and resume arms failed the same way. I served those arms deliveries that contain no HELD row, so each one is judged on the banner alone.
  - The three badge and filter arms failed differently: the shipped page **throws** `TypeError: Cannot read properties of undefined (reading 'icon')` at `status-badge.tsx:109` on any HELD row. That is a render crash, not an assertion failure, so I do not offer it as the RED evidence. It is a real finding, covered under Deviations below.

## Verification (both directions)

Each arm ran on a committed tree. I restored each one with `cp` from a scratch copy and confirmed `git diff --quiet HEAD` (rc 0) afterwards.

| Arm | Break | Fail direction (real output) | Pass direction |
|-----|-------|------------------------------|----------------|
| RED T1 | pre-fix worker | 3 tests, 3 failed (AUTO_PAUSED; count 8; count 10) | `d1e00183`: 3/0 |
| A | `auto-pause-threshold: ${…:10}` put back into `application.yml` | `WebhookPropertiesBindingTest` 2 tests, 1 failed: "The elements [webhook.delivery.auto-pause-threshold] were left unbound" | restored; 2/0 |
| B | `WebhookDeliveryWorkerIntegrationTest` keeps the old key (`auto-pause-threshold=3`) instead of `auto-pause-after-failed-deliveries=1` | 4 tests, 1 failed: "[a permanently-failing subscription auto-pauses] expected AUTO_PAUSED but was ACTIVE" | restored; 4/0 |
| C2 | no bulk hold at the transition (`held = 0`) | 8 tests, 2 failed: the email body lacks "1 event is held" / "3 events are held" | restored; 8/0 |
| C4 | fan-out to ACTIVE only | 8 tests, 1 failed: "the fan-out wrote no delivery row" (an event raised during the pause would be lost) | restored; 8/0 |
| C6 | resume without re-queue | 8 tests, 2 failed: `$.heldCount` expected 0 but was 2 / 1 | restored; 8/0 |
| C9 | worker FAILs (instead of HOLDs) a row claimed for a paused subscription | 8 tests, 1 failed: plural arm, HELD expected 3 but was 0 | restored; 8/0 |
| E | held sentence always plural, never omitted | `EmailNotificationServiceWebhookPausedTest` 6 tests, 3 failed (singular, zero, forms) | restored; 6/0 |
| F1 (plan's arm) | banner prints `${n ?? 0} events are held` | `webhooks-held.test.tsx` 7 tests, 2 failed: the 0-held arm ("never '0 events are held'") and the singular arm | restored; 7/7 |
| F2 | `subscription.heldCounts` typo in the page | `npm run build` rc 1: `TS2551 Property 'heldCounts' does not exist on type 'WebhookSubscription'` | restored; build rc 0 |
| ConfigKeyContractTest (plan's criterion) | n/a | **Cannot fail on this change.** `check-boot-config-keys.sh` rc 0, 218 keys, but its report holds **0** `webhook` rows: it judges only `spring.*`/`management.*`/`server.*`/`logging.*`. I replaced it with `WebhookPropertiesBindingTest` (arm A), which binds the shipped `webhook.delivery`/`webhook.envelope` keys with `NoUnboundElementsBindHandler` | gate rc 0 kept; binding test 2/0 |
| docs-freshness / check-doc-metrics | test counts moved | rc 1 (5814 vs 5837) / rc 1 (15 stale claims) | rc 0 / rc 0 (37/37) |
| Root pin guard | run from /tmp | FATAL actual-capture, rc 1 | PIN OK before every commit |

**Suites on the final code tree `2bf59cf4`:**
- **core-java unit, FULL:** 1804 tests, 0 failures, 0 errors, 1 skipped, 210 classes. Before (37-42): 1796 / 208.
- **core-java integrationTest, FULL:** 1197 tests, 0 failures, 0 errors, 1 skipped, 209 classes. BUILD SUCCESSFUL in 32 min 7 s (02:16Z–02:49Z). Before (37-42): 1189 / 208.
- **jest, FULL:** 2761 passed in 237 suites, 0 failed. Before: 2754 / 236.
- **frontend `npm run build`:** rc 0, and "Running TypeScript … Finished" is in the log, so the type check ran. Full-program `tsc --noEmit -p tsconfig.json` rc 0. `eslint .` 0 errors (30 warnings, none of them in a file this plan touched; the one webhooks warning is the existing `WebhookCreateDialog.tsx` line).
- **Untouched and green:** the SSRF suites `WebhookSsrfResolverTest` (6/0) and `WebhookUrlValidatorTest` (11/0). `git diff` is empty for those two tests, `WebhookUrlValidator`, `SsrfGuardAddressResolverGroup` and `WebhookDeliveryClientConfig`.
- `scripts/check-edge-core-contract.sh` rc 0 against the regenerated snapshot. gitleaks over `c80bfb68..HEAD` found no leaks, and each RED record was scanned clean before its commit.
- **Not run:**
  - `scripts/check-terminal-states.sh` returned VOID (rc 2). It shells out to bare `python3`, which the machine's PATH shim refuses in this project (no `.conda-env`). That is an environment limit, not a result. `HELD` is not in its D-3 discovery set (enum constants `FAILED`/`AUTO_PAUSED`) in any case.
  - Playwright and the live stack were not run (coverage D6).

## Files Created/Modified

- `webhook/WebhookProperties.java`: `autoPauseAfterFailedDeliveries` (default 3) replaces `autoPauseThreshold`.
- `webhook/WebhookDeliveryWorker.java`:
  - counts exhausted deliveries;
  - HELD for paused subscriptions;
  - `autoPaused()`: bulk hold, the WARN when there is no contact address, and the after-commit email;
  - new constructor arguments `TenantRepository` and `EmailNotificationService`.
- `webhook/WebhookDelivery.java`: `Status.HELD`. V56 has no CHECK on the column, so no migration is needed.
- `webhook/WebhookDeliveryRepository.java`: `holdQueued`, `requeueHeld`, `failHeld`, `countByTenantIdAndSubscriptionIdAndStatus` and `countByStatusPerSubscription`. Each filters on the tenant explicitly.
- `webhook/WebhookFanoutListener.java`: an AUTO_PAUSED subscription gets a HELD row.
- `webhook/WebhookSubscriptionService.java`: pause holds, resume re-queues, revoke fails what is held, and the DTOs carry `heldCount`.
- `webhook/dto/WebhookSubscriptionDto.java`: `heldCount`, plus `@Schema` on it and on `consecutiveFailures`.
- `notification/EmailNotificationService.java`: `sendWebhookPaused`, `webhookHeldSentence` and the subject and fix-line constants.
- `application.yml`: the renamed key.
- `frontend/lib/webhooks-api.ts`, `components/dashboard/webhooks/status-badge.tsx`, `app/dashboard/webhooks/[id]/page.tsx`.
- Tests:
  - New: `WebhookAutoPauseIntegrationTest` (8), `WebhookPropertiesBindingTest` (2), `EmailNotificationServiceWebhookPausedTest` (6), `webhooks-held.test.tsx` (7).
  - Adapted: `WebhookDeliveryWorkerIntegrationTest` (one property, listed below) and `WebhookSubscriptionServiceTest` (the constructor).
- `docs/api/openapi-snapshot.json`, `docs/webhooks.md`, `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md`.

**`WebhookDeliveryWorkerIntegrationTest` changes, listed as the plan asks.** One assertion encoded the attempt rule: `healthyDelivered_whileFailingRetriesToFailedAndAutoPauses_noHeadOfLineBlock` expects "a permanently-failing subscription auto-pauses" after ONE delivery exhausts 3 attempts. It used `auto-pause-threshold=3 == max-attempts=3`. The property is now `auto-pause-after-failed-deliveries=1`, and the assertion itself is unchanged. Arm B shows that keeping the old key reds it. No other assertion changed.

## Decisions Made

- **Deliveries, not attempts; no alias for the old key.** Silently mapping `auto-pause-threshold` onto a count with a different meaning would turn an operator's "10 attempts" into "10 dead events". The binding test refuses the old key instead.
- **HELD only for a pause.** A revoked endpoint can never be resumed, so its rows still fail, and revoke fails anything already held. Otherwise held rows would sit until retention for an endpoint that no longer exists.
- **Auto-pause records new events; manual pause does not.**
  - When the platform paused the endpoint, the vendor never chose to miss events, so events raised during the pause are written HELD (Rule 2: "no event is lost because an endpoint paused").
  - A manual pause is the vendor's own act. It keeps what was already queued but, as before, records no new events. Holding them too would change what a deliberate pause means. `docs/webhooks.md` says so.
- **A fresh budget on resume.** A held row may have used 7 of its 8 attempts against a broken endpoint. Resume sets `attempt_count` back to 0, because the banner promises those events "will be delivered when you resume". The row's last real error is kept.
- **The email is scheduled from the transition itself, after commit.** One transition means one email (T-37-103), and a rolled-back tick sends nothing.
- **Banner button at 0 held.** It still reads "Resume and deliver held events", which is how UI-SPEC E2 states it. Only the held sentence is omitted (the plan's assumption).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical] Events raised while AUTO_PAUSED were never written**
- **Found during:** Task 2.
- **Issue:** `WebhookFanoutListener` fanned out to ACTIVE subscriptions only. Holding the existing backlog would still have lost every event raised during the pause, which contradicts the must-have "no event is lost because an endpoint paused".
- **Fix:** An AUTO_PAUSED subscription gets a HELD row. The file is not in `files_modified`.
- **Verification:** Arm C4 reds the target arm.
- **Committed in:** `62235139`

**2. [Rule 2 - Missing critical] Revoke would have left HELD rows behind**
- **Found during:** Task 2.
- **Issue:** Revoke left HELD rows for an endpoint that can never be resumed, which would have made `heldCount` permanently wrong.
- **Fix:** `failHeld` on revoke.
- **Verification:** The manual-pause arm asserts that the held row becomes FAILED and that `heldCount` is 0.
- **Committed in:** `62235139`

**3. [Rule 1 - Bug] The shipped delivery log crashed on any status it did not know**
- **Found during:** Task 3 RED.
- **Issue:** `deliveryStatusConfig[status].icon` threw on HELD, taking down the whole `/dashboard/webhooks/[id]` page. The Task 2 backend already returns HELD rows.
- **Fix:** HELD was added to the map. An unknown status now renders its raw name in an outline badge instead of throwing.
- **Committed in:** `55a0b90b`
- **For the orchestrator:** the backend (Task 2) and the frontend (Task 3) must ship together in the 37-E PR. Core at `62235139` with an older frontend would crash that page whenever an endpoint pauses.

**4. [Falsifiability] The plan's "ConfigKeyContractTest green (no orphaned key)" could not fail**
- **Found during:** Task 1.
- **Issue:** The gate judges only `spring.*`, `management.*`, `server.*` and `logging.*` keys. Its report has 0 webhook rows.
- **Fix:** I kept the gate (rc 0) and added `WebhookPropertiesBindingTest`, measured both ways (arm A). Its first draft also flagged `webhook.target.block-private-ranges`, which `@Value` reads in `WebhookUrlValidator`, so the test binds only the two sections `WebhookProperties` owns.
- **Committed in:** `d1e00183`

**5. [Plan path] The banner and the delivery log live in `[id]/page.tsx`**
- **Issue:** The plan listed `frontend/app/dashboard/webhooks/page.tsx`. That is the subscription list page, and it has no banner. The loading branch the UI-SPEC cites (`webhooks/page.tsx:266`) is also in `[id]/page.tsx`.
- **Fix:** The work went into `[id]/page.tsx`. The list page is unchanged. `frontend/lib/webhooks-api.ts` (types) was modified too, although it was not listed.

**6. [Scenario] UXT-038 reproduced with 5 failures per event, not 4**
- **Issue:** The plan's `<behavior>` says "each failing 4 attempts then succeeding". With the production threshold of 10 attempts, 4 + 4 = 8 never pauses, even before the fix, so that arm could not have gone RED.
- **Fix:** I used 5 + 5 = 10, which matches the measured "5 of 8 attempts" in RESEARCH §37-E.4.

**7. [Rule 3 - Blocking] Test-count drift**
- **Fix:** `docs-freshness.sh --write` plus the prose counts, as in 37-39..37-42.
- **Committed in:** `2bf59cf4`

**8. [File list] Files added beyond `files_modified`**
- `WebhookDeliveryRepository.java`, `WebhookFanoutListener.java`, `application.yml`, `docs/webhooks.md`, `WebhookPropertiesBindingTest.java`, `EmailNotificationServiceWebhookPausedTest.java`, `WebhookSubscriptionServiceTest.java` (constructor), `frontend/lib/webhooks-api.ts`, and the metrics and prose files.

---

**Total deviations:** 3 auto-fixed (2 Rule 2, 1 Rule 1), 1 falsifiability replacement, 1 plan path correction, 1 scenario correction, 1 Rule 3 and 1 file-list note.
**Impact on plan:** Each change was needed for the plan's own must-haves (no lost event, no crash on the new status) or to make a criterion able to fail. There is no migration, no new endpoint, no new table and no change to the SSRF code.

## Issues Encountered

- `jest-junit` is not installed. I used a scratchpad TAP reporter for the Task 3 RED record, the same recipe 37-36 and 37-37 used. The RED records were built with node, because the project's python hook refuses a script run.

## Known Stubs

None.

## Threat Flags

None beyond the register:
- **T-37-101 (mitigated):** HELD, plus the resume re-queue. The IT covers it, and arms C2, C4, C6 and C9 each red it.
- **T-37-102 (mitigated):** one email per AUTO_PAUSED transition, and a WARN when there is no contact address.
- **T-37-103 (mitigated):** the email is sent only on the transition, after commit. A paused endpoint cannot pause again until resumed. The IT asserts one send across pause, resume and delivery.
- **T-37-SC:** nothing was installed.
- **New surface:** the pause email goes to `tenants.contact_email`, an address the tenant set (V48). It carries the vendor's own endpoint URL, which is sanitised. Neither the URL nor the address is logged.

## User Setup Required

None. The new env var `WEBHOOK_DELIVERY_AUTO_PAUSE_AFTER_FAILED_DELIVERIES` defaults to 3 and was never set anywhere for its predecessor.

## Next Phase Readiness

- **For the orchestrator:**
  - RWO-038 can be ticked.
  - The compose runtime is still the 37-C build at d060d0af and is BEHIND the branch. A live endpoint will still pause after about 10 failed attempts and drop its events until the 37-E gate rebuilds core-java and the frontend (coverage D6).
  - Ship Tasks 2 and 3 together (Deviation 3).
- **For the 37-E gate's live run:**
  1. Point a subscription at a receiver that answers 503.
  2. Raise three events. The endpoint should pause after the third one exhausts, and a fourth queued event should show "Held".
  3. Mailhog should receive "Your J'Toye webhook endpoint was paused" with the held count in UK time. Set the dev tenant's `contact_email` first if it is empty.
  4. Fix the receiver. The banner's "Resume and deliver held events" should deliver the held events.
  5. Two flaky events should not pause the endpoint.
- **Behaviour change integrators will see:**
  - `GET /api/v1/webhooks*` responses gain `heldCount`.
  - Delivery status can be `HELD`.
  - `consecutiveFailures` now counts dead deliveries, not attempts.
  - A paused endpoint's backlog is kept instead of failed.
- Ready for 37-44.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-13*

## Self-Check: PASSED

- 7/7 key created files FOUND on disk: the 4 new test files and the 3 RED records.
- 7/7 commits FOUND as ancestors of HEAD: ba5f9bf1 d1e00183 bd9ce37f 62235139 4f22b6d4 55a0b90b 2bf59cf4.
- `gsd_run check evaluation-scope --plan 37-43 --commits-only`: status resolved, source plan-subjects, commits on this branch.
- All three RED records were re-checked and returned RED_EVIDENCE_OK.
- Must-haves:
  - `WebhookAutoPauseIntegrationTest` contains HELD (12 occurrences).
  - The key-link pattern `sendWebhookPaused` is in `WebhookDeliveryWorker.java` (2).
  - The prohibition "MUST NOT discard queued webhook events because an endpoint was paused" is covered by test (arms C2, C4, C9).
- TDD gate: three `test(37-43)` RED commits, each before its `feat(37-43)`. No refactor commit was needed.
- Acceptance criteria re-run:
  - Task 1: RED then GREEN; ConfigKeyContractTest gate rc 0, plus the stronger binding test.
  - Task 2: each arm RED then GREEN; the SSRF suites are untouched and green.
  - Task 3: the existing webhooks suites are green; the zero-count arm reds against a banner printing "0 events are held" (F1).
