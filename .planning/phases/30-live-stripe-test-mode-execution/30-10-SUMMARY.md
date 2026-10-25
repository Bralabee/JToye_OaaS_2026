---
phase: 30-live-stripe-test-mode-execution
plan: 10
subsystem: core-java-payment
tags: [stripe, timeouts, resilience4j, circuit-breaker, issue-108, pay-03, tdd]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-09: D-02 done, and PR #1041 (the issue #1038 cleanup fix) merged and deployed"
provides:
  - "Stripe SDK timeouts from configuration: stripe.connect-timeout-ms 5000, stripe.read-timeout-ms 20000, stripe.max-network-retries 2, applied in PaymentService.init, refused at startup when out of range, announced in one log line"
  - "Refund creation behind its own breaker stripe-refund, and the webhook's charge lookup (new StripeChargeClient) behind stripe-lookup, so neither can open the checkout breaker stripe"
  - "StripeBreakerFailurePredicate on all three Stripe breakers: only transport, 5xx and rate-limit failures count; a 4xx business rejection never does"
  - "An open breaker answers a typed 503 (https://jtoye.uk/errors/payment-provider-unavailable, Retry-After 30); a refund refused that way is left FAILED, never CREATING"
affects: [30-11]

actuals:
  tasks: 2
  commits: 4        # 7d0a4334 (Task 1) and 7562f2cb (Task 2), from the Antigravity session; 80fe7d71 (its record); c0b8e253 (review fix)

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/payment/StripeChargeClient.java
    - core-java/src/main/java/uk/jtoye/core/payment/StripeBreakerFailurePredicate.java
    - core-java/src/test/java/uk/jtoye/core/payment/StripeClientTimeoutsTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/StripeBreakerFailurePredicateTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/StripeBreakerIntegrationTest.java
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-10-108-tdd.txt
  modified:
    - core-java/src/main/java/uk/jtoye/core/payment/StripeProperties.java
    - core-java/src/main/resources/application.yml
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java
    - core-java/src/main/java/uk/jtoye/core/payment/StripeRefundClient.java
    - core-java/src/main/java/uk/jtoye/core/payment/RefundService.java
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
    - core-java/src/test/java/uk/jtoye/core/payment/StripePropertiesRedactionTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/PaymentServiceTest.java

requirements-completed: [PAY-03]
---

# Plan 30-10 Summary: Stripe calls are bounded in time, and only a provider outage can open a Stripe breaker

**A Stripe call can no longer hold a request thread for the SDK's 30 s connect and 80 s read defaults: it now gives up after 5 s and 20 s. Refunds and the webhook's charge lookup each have their own breaker, so a run of rejected refunds can no longer open the checkout breaker, and a 4xx answer never counts toward opening any of them.**

The Antigravity session wrote the code (7d0a4334, 7562f2cb) in its worktree `-agy-p30-w10` and never pushed it. This session adopted it on `fix/108-stripe-timeouts`, merged main 201ac06e into it, fixed one review point, and re-measured everything. The original record could not be reproduced; `evidence/30-10-108-tdd.txt` replaces it and says why.

## Task 1: timeouts from configuration

- `StripeProperties` gained `connectTimeoutMs` (5000), `readTimeoutMs` (20000) and `maxNetworkRetries` (2), shown unmasked in `toString`. They are bound from `STRIPE_CONNECT_TIMEOUT_MS`, `STRIPE_READ_TIMEOUT_MS` and `STRIPE_MAX_NETWORK_RETRIES`.
- `PaymentService.init` refuses to start, naming the property, when either timeout is not positive, the read timeout is shorter than the connect timeout, or retries are outside 0..5. It then sets the three SDK statics beside `Stripe.apiKey` and logs `Stripe client configured (key redacted) connectTimeoutMs=… readTimeoutMs=… maxNetworkRetries=…`.
- RED: with the three setters removed, the SDK getters read `30000`, the stripe-java default (arm A).

## Task 2: breakers of their own, the failure predicate, and the 503

- `StripeRefundClient.create` is on `stripe-refund`. The new `StripeChargeClient.retrieve` (with an optional `Stripe-Account`) is on `stripe-lookup`, and the webhook uses it.
- All three instances have the same four numbers as `stripe` and share `record-failure-predicate: uk.jtoye.core.payment.StripeBreakerFailurePredicate`. The predicate walks the cause chain:
  - The first `StripeException` it finds decides. A connection or rate-limit failure counts. An `ApiException` counts when it has no status or a 5xx status. Any other `StripeException` counts only with a 5xx status.
  - An `IOException` counts when no `StripeException` is in the chain.
  - Anything else does not count.
- `RefundService.createRefund` treats `CallNotPermittedException` like a `StripeException`: it marks the row FAILED and rethrows. It also sets `noRollbackFor` on that exception, because unlike `StripeException` it is unchecked and would roll the FAILED row back.
- `GlobalExceptionHandler` maps `CallNotPermittedException` to 503 with `Retry-After: 30`.
  - For a Stripe breaker it answers type `payment-provider-unavailable`.
  - For any other breaker it answers `upstream-unavailable`.
  - It never shows the breaker's name.

## Verification (2026-10-25, at c0b8e253)

| Check | Result |
|---|---|
| StripeClientTimeoutsTest, StripePropertiesRedactionTest, PaymentServiceTest, StripeBreakerFailurePredicateTest | 6, 4, 38, 12 tests; 0 failures |
| `uk.jtoye.core.boot4.*` | 82 tests across 9 classes; 0 failures |
| StripeBreakerIntegrationTest, StripeWebhookIdempotencyIntegrationTest (Testcontainers) | 4, 6; 0 failures |
| RefundControllerIntegrationTest, RefundServiceTest | 9, 16; 0 failures |
| Arm A: the three setters removed | 2 of 6 red, reading `30000` |
| Arm B: `@CircuitBreaker` removed from refunds | 2 of 4 red (`503` expected, `500` returned) |
| Arm C: the naive design (refunds on `stripe`, which has no predicate) | 3 of 4 red; rejected refunds leave `stripe` OPEN |
| D-10: `mail.smtp` lines added to application.yml | 0 (control 1) |

## Deviations

1. **The mock fallback is removed (review fix c0b8e253).** The adopted `init` used built-in defaults whenever `StripeProperties` looked like an unstubbed Mockito mock. Real configuration never takes that path. The one `PaymentServiceTest` case that reaches `init` with a key now stubs the three values, so `init` has no test-only branch.
2. **The application.yml comments are restored, and the citation that depended on them moves.** The adopted change reflowed comments in the `stripe:` block so that `.planning/codebase/INTEGRATIONS.md:14` (`application.yml:974-978`) would still resolve. The comments are back on their own lines, so the diff only adds, and the citation now reads `978-984`. `check-doc-citations.sh` is rc 0, and rc 1 with the old citation.
3. **`RefundControllerIntegrationTest` is not a Testcontainers test.** It runs under `test`, not under the plan's `integrationTest` command, which selects nothing for it.
4. **The doc counts moved to plan 30-11 Task 1,** as the plan says.
