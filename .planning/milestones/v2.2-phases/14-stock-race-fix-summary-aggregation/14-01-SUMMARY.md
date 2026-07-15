---
phase: 14-stock-race-fix-summary-aggregation
plan: 01
subsystem: order-management
tags: [stock-race, optimistic-locking, hibernate-version, spring-retry, requires-new, testcontainers, cq-01, rfc-7807]

# Dependency graph
requires:
  - phase: 13-guest-tracking-tenant-validation
    provides: "@BeforeEach TenantContext.clear() pattern (cross-class leakage mitigation); composite @ActiveProfiles('test') + @DynamicPropertySource Postgres driver override (Phase 12 Deviation #4); RabbitMQ stub pattern (port=0 + listener auto-startup=false)"
provides:
  - "V34 Flyway migration — products.version BIGINT NOT NULL DEFAULT 0"
  - "@Version primitive long field on Product entity (Hibernate optimistic lock)"
  - "InsufficientStockException → HTTP 409 ProblemDetail (RFC 7807) via GlobalExceptionHandler"
  - "StockService — dedicated @Service bean with @Retryable(ObjectOptimisticLockingFailureException.class, maxAttempts=3, backoff=50ms) + @Recover + Propagation.REQUIRES_NEW — the canonical CQ-01 race mitigation"
  - "RetryConfig @EnableRetry bean — activates Spring Retry AOP"
  - "OrderService.transitionOrder refactor — delegates stock decrement/restore to StockService, deletes adjustStockInBatch + Math.max silent clamp, moves orderRepository.save(order) to AFTER stock bookkeeping (fixes latent save-before-decrement ordering bug — RESEARCH §11 Q7)"
  - "ConcurrentStockDecrementIntegrationTest — Testcontainers Postgres 15 + CountDownLatch two-thread race pin; asserts exactly 1 success + 1 InsufficientStockException + final stock = 0"
  - "StockServiceTest — 5 Mockito unit tests (null-bypass, sufficient, insufficient, @Recover exhaustion, empty-list)"
  - "StockDecrementLocationTest — 2 source-level regression tests (code-search guards for stockService.decrementForOrder placement + createOrder non-decrement)"
  - "InsufficientStockExceptionHandlerTest — MockMvc standalone 409 ProblemDetail pin"
  - "OrderServiceTest.testConfirmOrder_DelegatesToStockService + testCancelOrder_DelegatesRestoreToStockService — replaces two old batching tests; pins the delegation contract"
affects: [14-02-summary-aggregation, 15-order-operations, 17-vendor-order-ops, any phase touching order.transitionOrder or product.quantityInStock mutation]

# Tech tracking
tech-stack:
  added:
    - "org.springframework.retry:spring-retry (Boot 3.4.2 BOM-managed version)"
  patterns:
    - "@Retryable + @Recover + Propagation.REQUIRES_NEW on stock-sensitive service methods — REQUIRES_NEW is MANDATORY when caller is @Transactional (otherwise saveAll only queues UPDATEs and the optimistic-lock exception fires at outer-commit time, outside the retry proxy)"
    - "@Recover first parameter typed as Throwable to avoid 'Cannot locate recovery method' under generic erasure when the annotated method has a parameterized-collection parameter"
    - "MockMvc standalone setup with explicit MappingJackson2HttpMessageConverter — Boot's default auto-config omits this from standaloneSetup, so ProblemDetail serializes as a String instead of an object"
    - "Testcontainers @DynamicPropertySource must include spring.jpa.hibernate.ddl-auto=none when application-test.yml defaults to create-drop (Hibernate recreates schema from entity metadata, losing enum types / known Envers drift)"
    - "Idempotent test-seed helpers — short-circuit on existing SKU / UUID / slug so @BeforeEach survives repeated runs without @Transactional rollback (Phase 13 pattern)"
    - "Code-search regression tests (StockDecrementLocationTest) — no Spring context, no DB; read source file with Files.readString and assert grep-like invariants on class structure"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V34__product_optimistic_locking.sql (16 lines)
    - core-java/src/main/java/uk/jtoye/core/exception/InsufficientStockException.java (17 lines)
    - core-java/src/main/java/uk/jtoye/core/config/RetryConfig.java (14 lines)
    - core-java/src/main/java/uk/jtoye/core/order/StockService.java (151 lines including Propagation.REQUIRES_NEW fix)
    - core-java/src/test/java/uk/jtoye/core/order/StockServiceTest.java (108 lines, 5 @Test methods)
    - core-java/src/test/java/uk/jtoye/core/order/ConcurrentStockDecrementIntegrationTest.java (263 lines, 1 @Test method)
    - core-java/src/test/java/uk/jtoye/core/order/StockDecrementLocationTest.java (97 lines, 2 @Test methods)
    - core-java/src/test/java/uk/jtoye/core/common/InsufficientStockExceptionHandlerTest.java (56 lines, 1 @Test method)
    - .planning/phases/14-stock-race-fix-summary-aggregation/deferred-items.md (pre-existing RabbitMQ PLAIN auth tracker)
  modified:
    - core-java/src/main/java/uk/jtoye/core/product/Product.java (+8 lines: @Version long version field + getter/setter)
    - core-java/src/main/java/uk/jtoye/core/product/ProductMapper.java (+2 lines: @Mapping(target="version", ignore=true) × 2)
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java (+14 lines: InsufficientStockException import + handler)
    - core-java/src/main/java/uk/jtoye/core/order/OrderService.java (-101 +53: delete adjustStockInBatch + Math.max clamp; inject StockService; reorder save AFTER stock; delegate to stockService.decrementForOrder / restoreForOrder)
    - core-java/src/test/java/uk/jtoye/core/order/OrderServiceTest.java (~net-neutral: +StockService @Mock; replace 2 batching tests with 2 delegation tests)
    - core-java/build.gradle.kts (+1: spring-retry dep)
    - docs/CHANGELOG.md (+3 lines: [Unreleased] ## Fixed CQ-01 entry)
    - .planning/phases/14-stock-race-fix-summary-aggregation/14-VALIDATION.md (flipped 5 task rows to ✓ PASS with commit refs; nyquist_compliant: false → partial until Plan 14-02)

key-decisions:
  - "D-01 LOCKED (RESEARCH §11): Stock decrement lives in a dedicated StockService called from OrderService.transitionOrder CONFIRMED branch — NOT a state-machine action bean (reactive pipeline swallows exceptions), NOT a state-machine guard (guards are read-only predicates)"
  - "D-02 LOCKED: InsufficientStockException → HTTP 409 Conflict (RFC 9110 §15.5.10)"
  - "D-03 LOCKED: 3 retry attempts × 50ms fixed backoff (no exponential); retryFor=ObjectOptimisticLockingFailureException only"
  - "D-05 LOCKED: No new Product index — RLS + existing tenant index covers the retry re-read path"
  - "D-06 LOCKED: @Version field DEFAULT 0 for existing rows via migration; primitive long (not Long) because NOT NULL + DEFAULT 0 guarantees no NULLs"
  - "D-07 LOCKED (RESEARCH §11 Q7): Fix save-before-decrement ordering — orderRepository.save(order) now runs AFTER stock bookkeeping so an @Transactional rollback reverts setStatus(CONFIRMED) cleanly"
  - "D-10 LOCKED: Unlimited stock (quantityInStock == null) bypasses @Version check and decrement — matches existing Product.hasStock(null) contract"
  - "DEVIATION fix 20ebf24: Propagation.REQUIRES_NEW on decrementForOrder and restoreForOrder — plan did not anticipate this. Without it the outer @Transactional (OrderService.transitionOrder) joins its TX scope and saveAll only queues UPDATEs, so the optimistic-lock flush happens at outer-commit time, OUTSIDE the @Retryable proxy. Retry never fires."
  - "DEVIATION fix 20ebf24: @Recover first parameter typed as Throwable (not ObjectOptimisticLockingFailureException). Plan-exact signature hit 'Cannot locate recovery method' at runtime because Spring's recovery method resolver matches by assignability + parameter count AND generic erasure strips List<OrderItem> to raw List — the broader Throwable type is unambiguous"

patterns-established:
  - "CQ-01 race pattern: @Version entity → @Retryable service method with @Recover → Propagation.REQUIRES_NEW so flush happens inside retry boundary → broad @Recover exception param (Throwable) so resolver works under generic erasure → dedicated @Service bean (no self-invocation; outer @Transactional → inner REQUIRES_NEW decouples TX scope)"
  - "Phase 14 test scaffold recipe: @SpringBootTest + @Testcontainers + @ActiveProfiles('test') + @Tag('testcontainers') + @DynamicPropertySource with explicit org.postgresql.Driver + PostgreSQLDialect + spring.flyway.enabled=true + spring.jpa.hibernate.ddl-auto=none + rate-limiting.enabled=false + spring.rabbitmq.host=localhost + port=0 + listener.simple.auto-startup=false"
  - "V6 dropped the order_status enum in favour of VARCHAR(20) + CHECK — JDBC seed templates must INSERT plain strings, not CAST('...' AS order_status)"
  - "MapStruct must explicitly @Mapping(target='version', ignore=true) on toEntity/updateEntity when adding a @Version field — otherwise MapStruct emits 'Unmapped target property' warnings (cosmetic but noisy)"

requirements-completed: [CQ-01]

# Metrics
duration: ~20min (net, excluding external-process branch-reset recovery overhead)
completed: 2026-04-19
---

# Phase 14 Plan 01: Stock Race Fix (CQ-01) Summary

**Optimistic-lock-gated stock decrement on order CONFIRM — V34 adds `products.version`, `@Version` on the Product entity, `StockService.decrementForOrder` wraps the decrement in `@Retryable(ObjectOptimisticLockingFailureException.class, maxAttempts=3, backoff=50ms)` + `@Recover` + `Propagation.REQUIRES_NEW`, converting retry exhaustion to `InsufficientStockException` → HTTP 409 `ProblemDetail`; `OrderService.transitionOrder` delegates to `StockService` and saves AFTER the stock bookkeeping so a decrement failure rolls back cleanly; `adjustStockInBatch` + its silent `Math.max(0, …)` clamp are deleted. Two concurrent `confirmOrder` calls on a single-unit product now produce exactly one success and one HTTP 409 — previously both succeeded (oversold).**

## Accomplishments

- CQ-01 deep-audit P1 bug closed. Two concurrent CONFIRM events on a last-in-stock product produce exactly one success and one `InsufficientStockException` — verified by `ConcurrentStockDecrementIntegrationTest` against real Postgres (Testcontainers 15 image + `CountDownLatch` two-thread race).
- Secondary save-before-decrement bug closed (RESEARCH §11 Q7). `orderRepository.save(order)` now runs AFTER the stock decrement branch; on decrement failure the surrounding `@Transactional` rolls back the in-memory `setStatus(CONFIRMED)` mutation so the order stays PENDING — no ghost CONFIRMED rows.
- Silent `Math.max(0, …)` clamp in `adjustStockInBatch` deleted. The helper hid overskill by clamping negative stock values to 0 — an oversell that vendor-facing code could never detect. Gone.
- Exception contract for HTTP clients is RFC 7807 `ProblemDetail` at status 409 with `title="Insufficient Stock"`, `type="https://jtoye.uk/errors/insufficient-stock"`, and a detail message. Pinned by `InsufficientStockExceptionHandlerTest`.
- Five pinning tests cover the full CQ-01 contract: the concurrent Testcontainers integration test (race), 5 StockService unit tests (null-bypass + sufficient + insufficient + @Recover + empty-list), 2 StockDecrementLocationTest source-level regression tests (placement + createOrder non-decrement), 1 handler test (409 contract), and the existing OrderServiceTest grew 2 delegation tests replacing the two old batching tests.

## Task Commits

Each task was committed atomically on `feature/phase-14-stock-race-summary-aggregation`:

1. **Task 14-01-01: Wave 0 infra bundle** — `ec89443` (feat)
   — V34 migration + @Version on Product + InsufficientStockException + GEH 409 handler + RetryConfig @EnableRetry + spring-retry dep + InsufficientStockExceptionHandlerTest. Handler test PASS. Product.java + ProductMapper.java updated for the new @Version field.
2. **Task 14-01-02: RED — concurrent integration test** — `c062f3a` (test)
   — ConcurrentStockDecrementIntegrationTest created; RED confirmed via `Expecting actual throwable to be an instance of: InsufficientStockException but was: ObjectOptimisticLockingFailureException` (the @Version from Task 01 IS firing on race, but the StockService wrapping is not yet active — expected RED).
3. **Task 14-01-03: GREEN — StockService standalone** — `ad02c98` (feat)
   — StockService with @Retryable(3 × 50ms) + @Recover + null-bypass + InsufficientStockException on insufficient stock. 5/5 unit tests PASS. Integration test still RED (not wired).
4. **Task 14-01-04: GREEN — wire StockService + ordering fix** — `fe27915` (feat)
   — Inject StockService into OrderService (9th ctor param). Rewrite transitionOrder: save AFTER stock bookkeeping; CONFIRMED delegates to decrementForOrder; CANCELLED delegates to restoreForOrder; delete adjustStockInBatch + Math.max clamp; remove now-unused imports (Map, Function, Collectors). OrderServiceTest gets @Mock StockService and two delegation tests replacing the two old batching tests (39/39 PASS).
5. **Task 14-01-04 fix: REQUIRES_NEW + Throwable @Recover** — `20ebf24` (fix)
   — Plan did not anticipate two Spring-Retry/JPA interaction issues that surfaced only in the concurrent integration test: (a) outer @Transactional swallowed the flush out of the @Retryable boundary — fixed with Propagation.REQUIRES_NEW; (b) "Cannot locate recovery method" because the specific ObjectOptimisticLockingFailureException + List<OrderItem> signature didn't resolve under generic erasure — fixed by broadening the first @Recover param to Throwable. Integration test RED→GREEN flip confirmed.
6. **Task 14-01-05: regression pin + CHANGELOG** — `c77fbdd` (test)
   — StockDecrementLocationTest (2 source-level regression @Test methods, both PASS) + `## [Unreleased] ### Fixed` CHANGELOG entry documenting the fix.

_TDD sequence: infra → RED → GREEN unit → GREEN wire → bug-fix — pin. All 6 commits atomic on the feature branch._

## Files Created/Modified

- Created 9 files (5 test files + 4 main files) — see key-files frontmatter.
- Modified 8 files (Product.java, ProductMapper.java, GlobalExceptionHandler.java, OrderService.java, OrderServiceTest.java, build.gradle.kts, CHANGELOG.md, 14-VALIDATION.md).

## Decisions Made

All 7 locked decisions from the PLAN `<locked_decisions>` block were followed exactly:

- **D-01** Dedicated `StockService` called from OrderService.transitionOrder CONFIRMED branch. Not a state-machine action bean. Not a guard.
- **D-02** HTTP 409 Conflict for InsufficientStockException.
- **D-03** 3 retries × 50ms fixed backoff, retryFor=ObjectOptimisticLockingFailureException.
- **D-05** No new Product index.
- **D-06** @Version DEFAULT 0 in migration + primitive `long`.
- **D-07** save AFTER stock decrement — the secondary bug fix.
- **D-10** Unlimited stock (null quantityInStock) bypasses version check + decrement.

Two tactical decisions not in the plan, both Rule-1 bug fixes during integration testing:

- **Propagation.REQUIRES_NEW on StockService** — without it the outer OrderService.transitionOrder @Transactional makes inner @Transactional REQUIRED, which means saveAll only queues UPDATEs and the flush happens at outer-commit time OUTSIDE the @Retryable proxy scope. The retry never fires and the original `ObjectOptimisticLockingFailureException` propagates. REQUIRES_NEW forces inner commit-on-return.
- **@Recover first parameter typed as `Throwable`** — Spring Retry's recovery-method resolver matches by assignability + parameter count. With `ObjectOptimisticLockingFailureException` + `List<OrderItem>` and the @Retryable method signature using the same `List<OrderItem>` under generic erasure, the resolver failed with "Cannot locate recovery method". Broadening the exception param to Throwable is unambiguous and matches the same semantic intent.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking] @DynamicPropertySource needed `spring.jpa.hibernate.ddl-auto=none` override**

- **Found during:** Task 14-01-02 (first Testcontainers test run)
- **Issue:** `application-test.yml` defaults to `spring.jpa.hibernate.ddl-auto: create-drop`. The @DynamicPropertySource re-enabled Flyway but Hibernate still ran create-drop AFTER Flyway, recreating the schema from entity metadata. That dropped the `order_status` enum type (V6 converts to VARCHAR, but Hibernate's create-drop does not know). INSERT INTO orders ... CAST(? AS order_status) then failed with `type "order_status" does not exist`.
- **Fix:** Added `registry.add("spring.jpa.hibernate.ddl-auto", () -> "none")` to the @DynamicPropertySource. `validate` was too strict (known Envers drift on order_items_aud.product_name), `none` lets Flyway be the single source of truth.
- **Files modified:** `ConcurrentStockDecrementIntegrationTest.java`
- **Committed in:** `c062f3a` (Task 02 — folded into the RED test since it blocked even reaching the assertion).

**2. [Rule 3 — Blocking] JDBC seed must use plain string for `orders.status`, not enum cast**

- **Found during:** Task 14-01-02 (after fix #1)
- **Issue:** Initial seed used `CAST(? AS order_status)` based on the assumption orders.status is a custom enum. V6 dropped the enum and made it VARCHAR(20) with a CHECK constraint — the cast fails.
- **Fix:** INSERT the plain string value (no CAST).
- **Committed in:** `c062f3a` (Task 02).

**3. [Rule 3 — Blocking] `List.of(...)` forbids null — both-threads-succeed RED state threw NPE before the assertion**

- **Found during:** Task 14-01-02 (RED run)
- **Issue:** The scaffold collected worker results with `List.of(fA.get(), fB.get())`, but in the RED state both threads succeed (both return null), and `List.of` rejects nulls with NPE — so the test failed before the assertion could fire.
- **Fix:** Switched to `ArrayList` accumulation. The assertion `assertThat(successes).isEqualTo(1)` now runs and fails cleanly on RED.
- **Committed in:** `c062f3a` (Task 02).

**4. [Rule 1 — Bug] MapStruct "Unmapped target property: version" warning on Product mappers**

- **Found during:** Task 14-01-01 compile
- **Issue:** Added `@Version long version` field to Product. MapStruct's `ProductMapper.toEntity` and `updateEntity` warned "Unmapped target property: version" — MapStruct doesn't know to skip @Version.
- **Fix:** Added `@Mapping(target="version", ignore=true)` to both mapper methods.
- **Committed in:** `ec89443` (Task 01).

**5. [Rule 1 — Bug] MockMvc standalone ProblemDetail serialized as String not object**

- **Found during:** Task 14-01-01 handler test
- **Issue:** `standaloneSetup(controller).setControllerAdvice(handler).build()` does not register Boot's default Jackson MessageConverter. `ProblemDetail` returned from @ExceptionHandler was serialized as a String via the default converter — `jsonPath("$.status")` failed with `PathNotFoundException: found 'java.lang.String'`.
- **Fix:** Register `MappingJackson2HttpMessageConverter(new ObjectMapper())` explicitly on the MockMvc builder.
- **Committed in:** `ec89443` (Task 01).

**6. [Rule 1 — Bug] Spring Retry never fires because outer @Transactional swallows the flush**

- **Found during:** Task 14-01-04 integration test (after wiring)
- **Issue:** After wiring StockService into OrderService.transitionOrder (itself @Transactional), the inner StockService.decrementForOrder @Transactional joined the outer TX (REQUIRED propagation default). `saveAll` only queued UPDATEs — no flush. The outer TX's commit at transitionOrder's return then hit the optimistic-lock failure, OUTSIDE the @Retryable proxy. Integration test failed with the raw `ObjectOptimisticLockingFailureException` (retry never ran).
- **Fix:** `@Transactional(propagation = Propagation.REQUIRES_NEW)` on both `decrementForOrder` and `restoreForOrder`. The inner TX now commits on method return, inside the @Retryable proxy scope — retry fires correctly.
- **Committed in:** `20ebf24` (Task 04 fix).

**7. [Rule 1 — Bug] Spring Retry "Cannot locate recovery method" after exhaustion**

- **Found during:** Task 14-01-04 integration test (after fix #6)
- **Issue:** After the REQUIRES_NEW fix, retry exhausted to @Recover — but Spring Retry threw `ExhaustedRetryException: Cannot locate recovery method`. The recovery method's exception type was `ObjectOptimisticLockingFailureException` but the @Retryable method had `List<OrderItem>` under generic erasure, and Spring's resolver couldn't unambiguously match.
- **Fix:** Broadened @Recover's first parameter to `Throwable`. Assignability matching is unambiguous.
- **Committed in:** `20ebf24` (Task 04 fix).

---

**Total deviations:** 7 auto-fixed (3× Rule 3 blocking test-scaffold, 4× Rule 1 bug in plan-specified code). All deviations fall within Rules 1–3; no Rule 4 architectural escalation. The CQ-01 implementation IS what PLAN `<locked_decisions>` specified — the two runtime fixes in commit 20ebf24 are Spring-Retry + JPA interaction specifics that the plan under-specified but are mandatory for correctness.

## Issues Encountered

### Pre-existing RabbitMQ PLAIN auth cascade (NOT a Phase 14 regression)

- **Symptom:** 6 failures in `OrderControllerIntegrationTest` when running `--tests "uk.jtoye.core.order.*" -PincludeIntegration`. All fail at ApplicationContext startup with `FatalListenerStartupException: Authentication failure`.
- **Root cause:** Identical to Phase 13 `deferred-items.md` §1 — developer machine has live RabbitMQ on `localhost:5672` with non-default credentials; `OrderControllerIntegrationTest` does not have the Phase 12 Deviation #3 stub-broker override.
- **Verified pre-existing:** Checked out `9c5309b` (Phase 13 HEAD, pre-Phase-14), ran the same command — same 6 failures.
- **Documented:** New `.planning/phases/14-stock-race-fix-summary-aggregation/deferred-items.md` cross-references Phase 13's entry.
- **Impact on CQ-01 closure:** Zero — all 61 other order-package tests PASS, including the 5 new Phase 14 tests that exercise the full CQ-01 contract (Concurrent, StockService, StockDecrementLocation, InsufficientStockExceptionHandler, OrderService delegation).

### External branch-reset pressure during execution

- During task execution, an external process repeatedly switched working branch from `feature/phase-14-stock-race-summary-aggregation` to `feature/design-system-overhaul` and back, cherry-picking design commits and resetting my task commits. Multiple recovery rounds via `git cherry-pick` from reflog were required.
- All Phase 14 commits now sit on `feature/phase-14-stock-race-summary-aggregation`: `ec89443 → c062f3a → ad02c98 → fe27915 → 20ebf24 → c77fbdd`, in task order.
- No work was lost; deviations logged above ARE the full set of in-task deviations (not recovery artifacts).

## Self-Check

FOUND: core-java/src/main/resources/db/migration/V34__product_optimistic_locking.sql
FOUND: core-java/src/main/java/uk/jtoye/core/exception/InsufficientStockException.java
FOUND: core-java/src/main/java/uk/jtoye/core/config/RetryConfig.java
FOUND: core-java/src/main/java/uk/jtoye/core/order/StockService.java
FOUND: core-java/src/main/java/uk/jtoye/core/product/Product.java (with @Version)
FOUND: core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java (with InsufficientStockException handler)
FOUND: core-java/src/main/java/uk/jtoye/core/order/OrderService.java (with stockService.decrementForOrder, no adjustStockInBatch, no Math.max)
FOUND: core-java/src/test/java/uk/jtoye/core/common/InsufficientStockExceptionHandlerTest.java
FOUND: core-java/src/test/java/uk/jtoye/core/order/ConcurrentStockDecrementIntegrationTest.java
FOUND: core-java/src/test/java/uk/jtoye/core/order/StockServiceTest.java
FOUND: core-java/src/test/java/uk/jtoye/core/order/StockDecrementLocationTest.java
FOUND: core-java/src/test/java/uk/jtoye/core/order/OrderServiceTest.java (with @Mock StockService + 2 delegation tests)
FOUND: docs/CHANGELOG.md (with CQ-01 [Unreleased] entry)
FOUND: .planning/phases/14-stock-race-fix-summary-aggregation/deferred-items.md
FOUND: ec89443 (Task 14-01-01 commit)
FOUND: c062f3a (Task 14-01-02 commit)
FOUND: ad02c98 (Task 14-01-03 commit)
FOUND: fe27915 (Task 14-01-04 commit)
FOUND: 20ebf24 (Task 14-01-04 fix commit)
FOUND: c77fbdd (Task 14-01-05 commit)

## Self-Check: PASSED

## TDD Gate Compliance

RED gate commit (`test(14-01):`): `c062f3a` (Task 02 — concurrent integration test recorded RED output in commit body).
GREEN gate commits (`feat(14-01):`): `ec89443` (Task 01), `ad02c98` (Task 03), `fe27915` (Task 04).
Bug-fix gate commit (`fix(14-01):`): `20ebf24` (Task 04 fix — REQUIRES_NEW + Throwable @Recover).
Regression pin (`test(14-01):`): `c77fbdd` (Task 05 — code-search test + CHANGELOG).

TDD sequence: GREEN-infra → RED → GREEN-unit → GREEN-wire → GREEN-fix → regression-pin. All gates present on the feature branch.

## User Setup Required

None — no external service configuration required. The V34 Flyway migration runs automatically on Spring Boot startup. No new environment variables, no new Keycloak scopes, no new Redis keys. Spring-retry dependency pulled transitively via Boot BOM.

## Must-Haves Verification

All 6 must-haves from the PLAN frontmatter verified:

1. ✓ "Two concurrent CONFIRM events on a last-in-stock product produce exactly one success and one InsufficientStockException (no oversell)"
   → `ConcurrentStockDecrementIntegrationTest.concurrentConfirm_oneWins_oneThrowsInsufficientStock` PASS green (Testcontainers Postgres)
2. ✓ "Stock decrement happens inside StockService.decrementForOrder called from the CONFIRMED branch of OrderService.transitionOrder — not in createOrder"
   → `StockDecrementLocationTest.decrementLivesInTransitionOrderCONFIRMEDBranch` PASS + `createOrderDoesNotCallDecrementForOrder` PASS
3. ✓ "A throw from decrementForOrder rolls back the order status save (order stays PENDING, not CONFIRMED)"
   → Save re-ordered AFTER stock bookkeeping in transitionOrder; `@Transactional` rolls back on InsufficientStockException since it extends RuntimeException. Verified implicitly by ConcurrentStockDecrementIntegrationTest (loser order stays PENDING; winner CONFIRMED).
4. ✓ "An InsufficientStockException thrown from any controller maps to HTTP 409 with RFC 7807 ProblemDetail body"
   → `InsufficientStockExceptionHandlerTest.throwInsufficientStockReturns409` PASS — title "Insufficient Stock", type `https://jtoye.uk/errors/insufficient-stock`, status 409, detail echoed from exception message
5. ✓ "Products with null quantityInStock (unlimited) bypass the @Version check and never throw InsufficientStockException"
   → `StockServiceTest.nullStockBypassesVersionAndDecrement` PASS — spied Product with null stock; decrement not applied; no exception
6. ✓ "OrderStateMachineTest + existing OrderServiceTest remain green after the refactor"
   → OrderServiceTest 39/39 PASS (2 batching tests replaced with 2 delegation tests, all pre-existing tests still green); OrderStateMachineTest not touched by this phase (unrelated to stock).

All required artifacts from plan frontmatter present and meet min_lines constraints (V34 migration SQL present; @Version on Product.java; InsufficientStockException ≥ 10 lines; StockService ≥ 40 lines; ConcurrentStockDecrementIntegrationTest ≥ 120 lines; StockServiceTest ≥ 80 lines).

## Threat Flags

None — Phase 14 strictly closes an existing oversell bug. No new endpoints, no new auth paths, no new file access. The @Version column is schema-local; RLS policies on `products` already scope access by tenant_id. InsufficientStockException's message includes product title + quantities — RESEARCH §Security T-14-03 disposition accepts this (product titles are already public via /public/storefront; numeric stock is attacker-submitted vs observed — no PII, no tenant UUIDs).

## Next Plan Readiness

- Plan 14-01 shippable on the feature branch. CQ-01 ROADMAP success criteria #1, #2, #5 all verifiable.
- Enables Plan 14-02 (CQ-02 getSummary DB aggregation) — independent wave, no code dependency. Plan 14-02 can begin immediately.
- Enables Phase 17 (Vendor Order Operations) — the CQ-01 contract (409 on stock exhaustion) is the behaviour Phase 17 controllers will rely on.
- Recommended follow-up (separate phase): apply the Phase 12 Deviation #3 stub-broker override to the remaining ~40 pre-Phase-12 integration tests that still trip the RabbitMQ PLAIN auth cascade. Tracked in Phase 13 deferred-items.md §1.

---
*Phase: 14-stock-race-fix-summary-aggregation*
*Completed: 2026-04-19*
