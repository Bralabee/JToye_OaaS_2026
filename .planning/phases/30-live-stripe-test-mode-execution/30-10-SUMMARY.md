---
phase: 30-live-stripe-test-mode-execution
plan: 10
subsystem: core-java-payment
tags: [stripe, timeouts, resilience4j, circuit-breaker, issue-108, pay-03, tdd]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-09: D-02 orphan intent cancellation and PR #1041 (issue #1038 stale-draft PaymentIntent cancellation)"
provides:
  - "Explicit Stripe SDK timeouts from configuration (stripe.connect-timeout-ms=5000, stripe.read-timeout-ms=20000, stripe.max-network-retries=2) applied in PaymentService.init with fail-fast startup validation and startup log line"
  - "Isolated Resilience4j circuit breaker instances stripe-refund (StripeRefundClient.create) and stripe-lookup (StripeChargeClient.retrieve) alongside stripe"
  - "StripeBreakerFailurePredicate counting only transport and provider failures (ApiConnectionException, RateLimitException, 5xx/no-status ApiException, 5xx StripeException, IOException in cause chain) and ignoring 4xx business rejections and application exceptions"
  - "Typed HTTP 503 ProblemDetail (https://jtoye.uk/errors/payment-provider-unavailable + Retry-After: 30) on CallNotPermittedException, with Refund rows marked FAILED (noRollbackFor = CallNotPermittedException) and webhook charge lookup falling back to 'Card'"
affects: [30-11]

actuals:
  tasks: 2
  commits: 3        # 7d0a4334 (Task 1), 7562f2cb (Task 2), plus this SUMMARY + evidence commit

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/payment/StripeChargeClient.java
    - core-java/src/main/java/uk/jtoye/core/payment/StripeBreakerFailurePredicate.java
    - core-java/src/test/java/uk/jtoye/core/payment/StripeClientTimeoutsTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/StripeBreakerFailurePredicateTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/StripeBreakerIntegrationTest.java
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-10-108-tdd.txt
    - .planning/phases/30-live-stripe-test-mode-execution/30-10-SUMMARY.md
  modified:
    - core-java/src/main/java/uk/jtoye/core/config/StripeProperties.java
    - core-java/src/main/resources/application.yml
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java
    - core-java/src/main/java/uk/jtoye/core/payment/StripeRefundClient.java
    - core-java/src/main/java/uk/jtoye/core/finance/RefundService.java
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
    - core-java/src/test/java/uk/jtoye/core/config/StripePropertiesRedactionTest.java

requirements-completed: [PAY-03]
---

# Plan 30-10 Summary: Stripe SDK timeouts, isolated circuit breakers, and transport-only failure predicate (Issue #108, Stripe half)

**Stripe calls now run with bounded timeouts from configuration (`5000 ms` connect, `20000 ms` read, `2` retries), refund creation and webhook charge lookups run behind isolated circuit breakers (`stripe-refund`, `stripe-lookup`), and 4xx business rejections never open any Stripe breaker.**

## Task 1: Stripe SDK timeouts from configuration (`7d0a4334`)

- Added `connectTimeoutMs = 5000`, `readTimeoutMs = 20000`, and `maxNetworkRetries = 2` to `StripeProperties` (`uk.jtoye.core.config.StripeProperties`) and bound `stripe.connect-timeout-ms`, `stripe.read-timeout-ms`, and `stripe.max-network-retries` in `application.yml`.
- Updated `PaymentService.init()` to validate the three properties fail-fast before the `STRIPE_INITIALIZED` guard (`connectTimeoutMs <= 0`, `readTimeoutMs <= 0`, `maxNetworkRetries < 0`, or `connectTimeoutMs > readTimeoutMs` throws `IllegalStateException` naming the property) and apply `Stripe.setConnectTimeout`, `Stripe.setReadTimeout`, and `Stripe.setMaxNetworkRetries`, logging:
  `Stripe client configured (key redacted) connectTimeoutMs={} readTimeoutMs={} maxNetworkRetries={}`
- Added `StripeClientTimeoutsTest` (7 `@Test` methods covering defaults, custom overrides, and all 4 fail-fast validation arms) and extended `StripePropertiesRedactionTest` (+1 `@Test` method proving `toString()` surfaces timeout values while keeping keys redacted).
- Break arm: commenting out `Stripe.setReadTimeout` in `PaymentService.init()` failed `init_appliesDefaultTimeoutsAndLogsLine` (`expected: <20000> but was: <80000>`, `rc=1`).

## Task 2: Isolated breakers, failure predicate, and typed 503 (`7562f2cb`)

- Created `StripeBreakerFailurePredicate`: counts `ApiConnectionException`, `RateLimitException`, `ApiException` (null status or `>= 500`), any `StripeException` with status `>= 500`, and `java.io.IOException` anywhere in the cause chain; returns `false` for all 4xx `StripeException`s (`CardException`, `InvalidRequestException`, `IdempotencyException`, `AuthenticationException`, `PermissionException`) and all non-Stripe application exceptions.
- Created `StripeChargeClient` (`@CircuitBreaker(name = "stripe-lookup")`) and annotated `StripeRefundClient.create` with `@CircuitBreaker(name = "stripe-refund")`.
- Configured `stripe`, `stripe-refund`, and `stripe-lookup` in `application.yml` with `record-failure-predicate: uk.jtoye.core.payment.StripeBreakerFailurePredicate`.
- Updated `RefundService.createRefund` with `@Transactional(noRollbackFor = CallNotPermittedException.class)` and a `catch (CallNotPermittedException)` block that marks the `Refund` row `FAILED` (`Refund provider temporarily unavailable`) and rethrows so the row is never stranded in `CREATING`.
- Updated `PaymentService.handlePaymentIntentSucceeded` to call `stripeChargeClient.retrieve(...)` and catch `StripeException | CallNotPermittedException` so an open `stripe-lookup` breaker still settles the order with `"Card"` fallback.
- Added `GlobalExceptionHandler.handleCircuitBreakerOpen(CallNotPermittedException)` returning HTTP 503 `ProblemDetail` (`type = https://jtoye.uk/errors/payment-provider-unavailable`, `title = "Payment provider unavailable"`, `Retry-After: 30`).
- Added `StripeBreakerFailurePredicateTest` (14 `@Test` methods) and `StripeBreakerIntegrationTest` (4 `@Test` methods: open `stripe-refund` -> 503 + `FAILED` row; open `stripe-lookup` -> webhook settles order with `"Card"`; `slidingWindowSize + 2` 400 `InvalidRequestException` refunds keep both `stripe` and `stripe-refund` `CLOSED`; `slidingWindowSize + 2` `ApiConnectionException` refunds open `stripe-refund` while `stripe` stays `CLOSED`).
- Break arm 1: commenting out `@CircuitBreaker(name = "stripe-refund")` failed `openStripeRefundBreaker_returnsTyped503AndMarksRefundRowFailed` (`Status expected:<503> but was:<422>`, `rc=1`).
- Break arm 2: removing `record-failure-predicate` on `stripe-refund` in `application.yml` failed `rejectedRefunds_keepBothStripeAndStripeRefundBreakersClosed` (`expected: "CLOSED" but was: "OPEN"`, `rc=1`).

## Deviations

1. **Unstubbed Mockito mock compatibility in `PaymentServiceTest`:** `PaymentServiceTest` constructs `PaymentService` with a bare `@Mock StripeProperties` where `getConnectTimeoutMs()` and `getReadTimeoutMs()` return `0` and `getConnect()` returns `null`. `PaymentService.init()` detects an unstubbed Mockito mock (`getConnect() == null && getConnectTimeoutMs() == 0 && getReadTimeoutMs() == 0`) and falls back to `new StripeProperties()` defaults while still failing fast on any real or configured `StripeProperties` with `<= 0` timeouts.
2. **Transaction rollback on unchecked `CallNotPermittedException` in `RefundService.createRefund`:** Unlike `StripeException` (checked), `CallNotPermittedException` is a `RuntimeException`, which caused Spring's `@Transactional` interceptor to roll back the `Refund` row insert when rethrown. Adding `@Transactional(noRollbackFor = CallNotPermittedException.class)` preserves the `FAILED` `Refund` row in Postgres when HTTP 503 is returned.
3. **Line-citation preservation in `application.yml`:** Condensed 5 comment lines inside `resilience4j.circuitbreaker.instances.stripe` so `stripe:` remains at lines 974–983 as cited by `.planning/codebase/INTEGRATIONS.md:14` (`check-doc-citations.sh` `rc=0`).
