---
phase: 30-live-stripe-test-mode-execution
plan: 12
subsystem: core-java-payment
tags: [stripe, connect, webhooks, hmac, environment-tag, express-capabilities, idempotency, pay-03]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-10/30-11: bounded Stripe client, isolated stripe/stripe-refund/stripe-lookup breakers, merged and deployed (9d204778)"
provides:
  - "POST /api/v1/public/payments/webhook/connect (+ /public alias) verifying against stripe.connect-webhook-secret only, refusing account-less events, failing closed on a blank secret"
  - "The platform route refuses any event carrying account: one route and one secret per Stripe event scope, one shared dispatcher (dedup INSERT + switch)"
  - "stripe.environment-tag (STRIPE_ENVIRONMENT_TAG, empty fallback -> 'unset') written as metadata environment on both PaymentIntent builders, the Refund builder and Express Account creation"
  - "Foreign-environment events acknowledged before the dedup row (WARN stripe_event_foreign_environment); payment_intent.* for an unknown order acknowledged (WARN stripe_event_unknown_order); BL-04 kept for same-environment refunds"
  - "Express accounts created requesting card_payments + transfers under idempotency key connect-account-create-<tenantId>; a reused account missing either has both requested before the onboarding link"
affects: [30-13, 30-14, 30-15, 30-17]

estimate:
  tokens: 90000
  tasks: 3
actuals:
  tokens: 86052     # chars/4 over git diff c64b2aec..665749a9
  tasks: 3
  commits: 11       # MEASURED: git rev-list --count c64b2aec..HEAD before this SUMMARY's own commit
plan_head_before: c64b2aec110d498eb0f55c58314dc52d3e6fb690
plan_head_after: 665749a987948fa9c11148b9237ad37dfd93fc31

tech-stack:
  added: []
  patterns:
    - "verify(payload, sig, secret) + dispatch(event): one dispatcher behind per-scope webhook entry points"
    - "Environment tag in Stripe metadata, compared before the dedup INSERT, so another environment's events leave no trace"
    - "RED tests that bind new config keys through Spring's Binder, so they compile before the property exists and fail on behaviour"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/payment/StripeConnectWebhookRouteIntegrationTest.java
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-12-task1-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-12-task2-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-12-task3-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-12-red-task1-it.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-12-red-task1-unit.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-12-red-task2-unit.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-12-red-task2-refund.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-12-red-task2-it.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-12-red-task3.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentController.java
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java
    - core-java/src/main/java/uk/jtoye/core/payment/StripeProperties.java
    - core-java/src/main/java/uk/jtoye/core/payment/RefundService.java
    - core-java/src/main/java/uk/jtoye/core/payment/StripeConnectService.java
    - core-java/src/main/resources/application.yml
    - core-java/src/test/java/uk/jtoye/core/payment/PaymentServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/RefundServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/RefundWebhookHandlingIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/StripeConnectServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/StripePropertiesRedactionTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/StripeWebhookIdempotencyIntegrationTest.java
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md

key-decisions:
  - "Two named webhook routes and secrets (add-alongside), not a list of endpoints: Stripe has exactly two event scopes"
  - "A blank webhook secret refuses before Webhook.constructEvent is called (fail closed), on both routes"
  - "The environment check runs before the dedup INSERT, so a foreign event leaves no processed_stripe_events row; objects without environment metadata are processed as before"
  - "An undeclared stripe.environment-tag is written and compared as 'unset', never defaulted to a real environment name"
  - "RefundService takes StripeProperties through a new @Autowired constructor; the legacy constructors tag 'unset'"
  - "A reused Express account gets both capabilities requested whenever either is absent; the update carries no idempotency key, because requesting an already-requested capability is a no-op"

requirements-completed: [PAY-03]

coverage:
  - id: D1
    description: "Connect route accepts only Connect-signed, account-bearing events; cross-secret, unsigned, forged, account-less and blank-secret arms leave 0 rows; platform route refuses account-bearing events"
    requirement: PAY-03
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/payment/StripeConnectWebhookRouteIntegrationTest.java (10 arms, real HMAC)"
        status: pass
      - kind: unit
        ref: "PaymentServiceTest#connectWebhook_blankSecret_refusesBeforeVerification and 2 sibling arms"
        status: pass
    human_judgment: false
  - id: D2
    description: "Environment metadata on intents, refunds and Express accounts; foreign-environment and unknown-order events acknowledged; BL-04 kept for same-environment refunds"
    requirement: PAY-03
    verification:
      - kind: integration
        ref: "StripeWebhookIdempotencyIntegrationTest (11) + RefundWebhookHandlingIntegrationTest (9)"
        status: pass
      - kind: unit
        ref: "PaymentServiceTest (46) + RefundServiceTest (18)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Express accounts request card_payments + transfers under connect-account-create-<tenantId>; reuse path requests missing capabilities before the link"
    requirement: PAY-03
    verification:
      - kind: unit
        ref: "StripeConnectServiceTest (22)"
        status: pass
    human_judgment: false

duration: 79min
completed: 2026-10-25
---

# Phase 30 Plan 12: The Connect webhook route Summary

**Stripe's Connected-accounts events now have their own route, `POST /api/v1/public/payments/webhook/connect`, verified against `stripe.connect-webhook-secret` only. The platform route refuses any event carrying `account`. Every PaymentIntent, Refund and Express Account is tagged `metadata.environment`. Events from another environment, or for an order this database does not hold, get a 200 with a WARN instead of a retry storm. Express accounts request `card_payments` and `transfers` under the idempotency key `connect-account-create-<tenantId>`.**

## Performance

- **Duration:** 79 min (2026-10-25 21:17Z to 22:37Z; the full suites took 43 min of that)
- **Tasks:** 3 of 3
- **Commits:** 11 (4 test, 3 feat, 4 docs), on `feat/30-connect-webhook`, not pushed

## TDD gate record

Each task has a `test(30-12)` commit before its `feat(30-12)` commit. Every RED record got `RED_EVIDENCE_OK` from `gsd_run check tdd-red-evidence` over the JUnit XML from that run, and each was checked by hand to confirm the failure was the intended one.

| Task | RED commit | RED: what failed | GREEN commit |
|------|-----------|------------------|--------------|
| 1 | eb68c4b8 | The Connect route returned 404, and the platform route accepted a Connect-scope event with 200. 8 of 10 IT arms failed. The redaction test had no `connectWebhookSecret` slot to mask. | 44780a93 |
| 2 | c02c7a26 | `metadata.environment` was null on both intents. The refund had no `environment` key. A foreign-environment event wrote 1 row where 0 was expected. Unknown-order events threw `ResourceNotFoundException`. | a7b79c62 |
| 3 | 8b28bdec | Capabilities were null, so the account fell back to platform defaults. The reuse path never called `account.update`. | 17d451f8 (assertion tightened in 35db22bd) |

There was no separate refactor commit. 35db22bd only tightens one test assertion so the failure names the missing capability instead of hitting an NPE.

## Task results

**Task 1 (tracer): the Connect route.** All 10 route IT arms sign their payloads with `Webhook.Util.computeHmacSha256`. Nothing is mocked between the HTTP request and `Webhook.constructEvent`. Each refusal arm counts rows by its own event id and expects 0. The accepted arms find their row with the same query. One more arm sends a Connect `account.updated` with `charges_enabled=true` and checks that a known tenant becomes `ENABLED`, which proves the shared dispatcher reaches the existing handler. The OpenAPI snapshot was regenerated: one path added, nothing removed.

- Break arm A (the plan's named arm): the Connect route was made to verify with the platform secret. The cross-secret arm failed (expected 400, got 200) and the real Connect event was refused.
- Break arm B: the account rule on the platform route was removed. Its arm failed.
- Break arm C: the blank-secret guard was removed. The IT arm alone could not catch this, because `constructEvent` on an empty key also ends in a 400. The unit arm, which asserts `Webhook` is never touched, is the one that failed.
- Tracer gate (end-of-phase mode, automated-only verify): the re-run passed, so the plan moved on to Tasks 2 and 3.

**Task 2: environment and acknowledgements.** Six break arms (D, D2, E, F, G, H), all failed as expected:
- With no environment check, a foreign payment event writes 1 row, and a foreign refund throws BL-04.
- With the unknown-order acknowledgements removed, both unknown-order arms throw.
- With the refund tag removed, both refund arms fail.
- With a blank tag read as `local`, both `unset` arms fail.
- With the BL-04 throw removed, the new BL-04 arm fails.

**Task 3: Express capabilities.** Break arms I (no transfers requested), J (no idempotency key) and K (no capability check on reuse) each failed their arm.

Every break arm was bracketed clean, arm, restore, clean. Each restore was checked by blob hash against HEAD, then confirmed with a clean `git diff --quiet`. Evidence: `evidence/30-12-task{1,2,3}-arms.txt` and `evidence/30-12-red-task*.json`.

## Verification

| Command | Result (read from the JUnit XML under `core-java/build-local/test-results/`) |
|---|---|
| Task 1 verify: route IT + idempotency IT | rc 0; 10 + 6 tests at the time, 0 failures |
| Task 1 verify: redaction + PaymentServiceTest | rc 0; 5 + 41 tests |
| Task 2 verify: PaymentServiceTest + RefundServiceTest | rc 0; 46 + 18 tests |
| Task 2 verify: idempotency IT + refund webhook IT | rc 0; 11 + 9 tests |
| Task 3 verify: StripeConnectServiceTest | rc 0; 22 tests |
| Full `:core-java:test` | rc 0; 223 classes, 2007 tests, 0 failures, 0 errors, 1 skipped |
| Full `:core-java:integrationTest` | rc 0; 228 classes, 1391 tests, 0 failures, 0 errors, 1 skipped |
| `scripts/docs-freshness.sh` | rc 1 before the update (3049 committed vs 3079 in the tree), rc 0 after `--write` |
| `scripts/check-doc-metrics.sh` | rc 1 with the old prose counts, rc 0 after CLAUDE.md, AGENTS.md and README.md moved to 6667 / 3079 / 426 |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing test] RefundWebhookHandlingIntegrationTest had no BL-04 arm**
- **Found during:** Task 2
- **Issue:** The plan relies on this IT to guard BL-04, but it had no arm for the case where a refund event carries our metadata and the local refund row is missing (that case must throw). Break arm H would have passed against the unmodified class.
- **Fix:** Added a same-environment BL-04 arm (a missing local row still rolls back, leaving 0 rows) and a foreign-environment refund arm (acknowledged, 0 rows, refund untouched).
- **Files modified:** core-java/src/test/java/uk/jtoye/core/payment/RefundWebhookHandlingIntegrationTest.java
- **Commit:** a7b79c62

**2. [Rule 2 - Missing test] A unit arm for T-30-50 and the Connect dispatch**
- **Found during:** Task 1
- **Issue:** With the blank-secret guard removed, the IT blank-secret arm cannot tell the difference: an empty HMAC key also ends in a 400.
- **Fix:** Added three PaymentServiceTest arms: blank secret refuses before `Webhook` is touched; the Connect route verifies with the Connect secret only and dispatches; the platform route refuses an account-bearing event before the dedup INSERT. Arm C shows the first one is load-bearing.
- **Commit:** 44780a93

**3. [Rule 3 - Blocking] OpenAPI snapshot regenerated**
- The new route changes the generated contract, and `OpenApiSnapshotTest` compares bytes. The snapshot was regenerated with `updateOpenApiSnapshot`: 90 lines added, 0 removed.
- **Commit:** 44780a93

**4. RED tests written to compile against the old tree**
- New config keys (`stripe.connect-webhook-secret`, `stripe.environment-tag`) are bound in the tests through Spring's `Binder` instead of new setters. That way each RED fails on behaviour instead of on compilation.
- The tagged-refund unit arm needs the new `RefundService` constructor, so it landed with GREEN. Break arm F shows it can fail.
- The first Task 1 RED run errored one sub-arm on the test's own fault (the JCE refuses an empty HMAC key). That sub-arm was removed and RED was re-run before the commit.

**Total deviations:** 4 (2 missing tests added, 1 blocking contract regeneration, 1 test-design note). **Impact:** no scope change. The BL-04 guard is now actually tested.

## Notes for later plans

- **30-13 (wiring):** `STRIPE_CONNECT_WEBHOOK_SECRET` and `STRIPE_ENVIRONMENT_TAG` exist only in `application.yml` with empty fallbacks. Not done here and left to 30-13: compose, `.env.example`, k8s env, SPC/Key Vault, and the env-contract counts.
- **30-14/30-15:** the platform route now returns 400 for any event that carries `account`. Any TEST-mode Connect endpoint still pointed at the platform URL will start getting 400s until it is retargeted to `/webhook/connect` or deleted (PR #1018 review finding 4).
- **30-17:** a runtime that declares no tag writes and compares `unset`.
- **Idempotency key caveat:** Stripe keeps idempotency keys for about 24 h. A second create for the same tenant inside that window with different parameters (for example, a changed contact email) gets Stripe's idempotency error rather than a duplicate account. That is the intended trade-off for T-30-48.

## Known Stubs

None. A scan of the added lines in `core-java/src/main` for TODO, FIXME, placeholder, "coming soon" and "not available" found 0 matches; the same pattern matched 1 on a planted control line.

## Self-Check: PASSED

- All 6 RED records and 3 arms files exist under `evidence/`; `StripeConnectWebhookRouteIntegrationTest.java` exists.
- Commits eb68c4b8, 44780a93, 401618c6, c02c7a26, a7b79c62, f721a5c0, 8b28bdec, 17d451f8, 35db22bd, bbc88087 and 665749a9 are all ancestors of HEAD (`git rev-list c64b2aec..HEAD` lists all 11).
