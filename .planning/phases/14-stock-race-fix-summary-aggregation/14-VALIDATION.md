---
phase: 14-stock-race-fix-summary-aggregation
nyquist_compliant: partial
validated_against_research: true
research_version: 14-RESEARCH.md (2026-04-18, HIGH confidence)
generated: 2026-04-18
updated: 2026-04-19 — Plan 14-01 complete (CQ-01); Plan 14-02 pending (CQ-02)
---

# Phase 14 Validation Architecture

Per-task automated verification map for the two parallel plans in Wave 1 (14-01 + 14-02). Each task in each plan has an `<automated>` Gradle command plus grep-verifiable acceptance criteria. This document establishes the Nyquist sampling rate: no task can be marked "done" without the listed test passing.

- **nyquist_compliant** flips to `true` when all Per-Task rows below are PASS after plan execution completes.
- Every success criterion from ROADMAP.md Phase 14 has a test listed below.
- Pre-existing RabbitMQ PLAIN auth failures (Phase 13 `deferred-items.md`) are explicitly excluded from the regression gate — noted in the relevant rows.

## Phase Requirements → Test Map

| Req ID | Behavior to Verify | Plan | Test (File::Method) | Automated Command | File Exists Pre-Wave-0? |
|--------|---------------------|------|---------------------|-------------------|-------------------------|
| CQ-01  | InsufficientStockException → HTTP 409 ProblemDetail via GlobalExceptionHandler | 14-01 | `core-java/src/test/java/uk/jtoye/core/common/InsufficientStockExceptionHandlerTest.java::throwInsufficientStockReturns409` | `./gradlew :core-java:test --tests "uk.jtoye.core.common.InsufficientStockExceptionHandlerTest"` | ❌ Wave 0 (created in Task 14-01-01) |
| CQ-01  | Two concurrent CONFIRM on last-in-stock → one success, one InsufficientStockException; final stock = 0 (no clamp) | 14-01 | `core-java/src/test/java/uk/jtoye/core/order/ConcurrentStockDecrementIntegrationTest.java::concurrentConfirm_oneWins_oneThrowsInsufficientStock` | `./gradlew :core-java:test --tests "uk.jtoye.core.order.ConcurrentStockDecrementIntegrationTest" -PincludeIntegration` | ❌ Wave 0 (created RED in Task 14-01-02, flips GREEN after Task 14-01-04) |
| CQ-01  | StockService.decrementForOrder bypasses null (unlimited) quantityInStock without version check | 14-01 | `StockServiceTest.nullStockBypassesVersionAndDecrement` | `./gradlew :core-java:test --tests "uk.jtoye.core.order.StockServiceTest"` | ❌ Wave 0 (created in Task 14-01-03) |
| CQ-01  | StockService.decrementForOrder throws InsufficientStockException on insufficient stock | 14-01 | `StockServiceTest.insufficientStockThrows` | same as above | ❌ Wave 0 |
| CQ-01  | Retry exhaustion via @Recover throws InsufficientStockException with "3 retries" message | 14-01 | `StockServiceTest.recoverFromOptimisticLockThrowsInsufficientStock` | same as above | ❌ Wave 0 |
| CQ-01  | Sufficient stock path decrements correctly and saves | 14-01 | `StockServiceTest.sufficientStockDecrements` | same as above | ❌ Wave 0 |
| CQ-01  | Empty items list is a no-op (no DB calls) | 14-01 | `StockServiceTest.emptyItemsListIsNoOp` | same as above | ❌ Wave 0 |
| CQ-01  | Stock decrement path lives in OrderService.transitionOrder CONFIRMED branch AND is ordered AFTER sendEvent, BEFORE final save | 14-01 | `core-java/src/test/java/uk/jtoye/core/order/StockDecrementLocationTest.java::decrementLivesInTransitionOrderCONFIRMEDBranch` | `./gradlew :core-java:test --tests "uk.jtoye.core.order.StockDecrementLocationTest"` | ❌ Wave 0 (created in Task 14-01-05) |
| CQ-01  | createOrder does NOT decrement stock (adjustStockInBatch gone from createOrder body) | 14-01 | `StockDecrementLocationTest.createOrderDoesNotCallDecrementForOrder` | same as above | ❌ Wave 0 |
| CQ-01  | V34 migration applies cleanly on fresh schema (`products.version BIGINT NOT NULL DEFAULT 0`) | 14-01 | Any Testcontainers integration test booting Flyway (implicit) — e.g., `ConcurrentStockDecrementIntegrationTest` | included in concurrent test command above | ❌ Wave 0 |
| CQ-01  | Existing OrderServiceTest assertions remain green after constructor expansion + adjustStockInBatch removal | 14-01 | `core-java/src/test/java/uk/jtoye/core/order/OrderServiceTest.java` (all existing @Test methods) | `./gradlew :core-java:test --tests "uk.jtoye.core.order.OrderServiceTest"` | ✅ pre-existing file — mock arrangements updated in Task 14-01-04 |
| CQ-02  | getSummary output parity vs old findAll()+reduce on 1k-row fixture | 14-02 | `core-java/src/test/java/uk/jtoye/core/finance/FinancialSummaryGoldenFileTest.java::getSummaryOutputMatchesCommittedGolden` | `./gradlew :core-java:test --tests "uk.jtoye.core.finance.FinancialSummaryGoldenFileTest" -PincludeIntegration` | ❌ Wave 0 (created in Task 14-02-01; golden JSON captured once against pre-rewrite impl and committed) |
| CQ-02  | getSummary aggregate query uses Index Scan at 10k rows (not Seq Scan on financial_transactions) | 14-02 | `FinancialSummaryQueryPlanTest.aggregateQueryUsesIndexScanAt10k` | `./gradlew :core-java:test --tests "uk.jtoye.core.finance.FinancialSummaryQueryPlanTest" -PincludeIntegration` | ❌ Wave 0 (created in Task 14-02-01 as GREEN pin) |
| CQ-02  | getSummary emits exactly 2 prepared statements (aggregate + GROUP BY), not 1 (findAll) and not >2 | 14-02 | `FinancialSummaryQueryCountTest.getSummaryIssuesExactlyTwoPreparedStatements` | `./gradlew :core-java:test --tests "uk.jtoye.core.finance.FinancialSummaryQueryCountTest" -PincludeIntegration` | ❌ Wave 0 (created RED in Task 14-02-01, flips GREEN after Task 14-02-02) |
| CQ-02  | Cross-tenant summary isolation preserved — tenant A summary excludes tenant B rows | 14-02 | `FinancialSummaryCrossTenantIsolationTest.tenantAGetSummaryExcludesTenantBRows` | `./gradlew :core-java:test --tests "uk.jtoye.core.finance.FinancialSummaryCrossTenantIsolationTest" -PincludeIntegration` | ❌ Wave 0 (created in Task 14-02-03) |
| CQ-02  | VatBreakdown ordering is deterministic (sorted by VatRate enum name) | 14-02 | Verified indirectly by `FinancialSummaryGoldenFileTest` (golden JSON has stable order) + code-search in Task 14-02-02 acceptance (`Comparator.comparing` present in service) | golden file test command above | — |
| CQ-02  | Existing FinancialTransactionServiceTest assertions remain green after mock arrangement updates | 14-02 | `core-java/src/test/java/uk/jtoye/core/finance/FinancialTransactionServiceTest.java` (all existing @Test methods) | `./gradlew :core-java:test --tests "uk.jtoye.core.finance.FinancialTransactionServiceTest"` | ✅ pre-existing file — mock arrangements updated in Task 14-02-02 |

## Per-Task Verification Map

### Plan 14-01 (CQ-01)

| Task | Status | Verification Command | Acceptance Grep / State |
|------|--------|----------------------|-------------------------|
| 14-01-01 Wave 0 — V34 migration + @Version + InsufficientStockException + GEH 409 + RetryConfig + spring-retry dep + handler test | ✓ PASS (commit ec89443) | `./gradlew :core-java:compileJava :core-java:compileTestJava && ./gradlew :core-java:test --tests "uk.jtoye.core.common.InsufficientStockExceptionHandlerTest"` | All grep criteria verified; handler test PASS |
| 14-01-02 RED — Concurrent-CONFIRM Testcontainers integration test | ✓ RED recorded (commit c062f3a) | `./gradlew :core-java:test --tests "uk.jtoye.core.order.ConcurrentStockDecrementIntegrationTest" -PincludeIntegration` | RED on pre-fix tree with `ObjectOptimisticLockingFailureException` (not the expected `InsufficientStockException` — because @Version fired from Task 01 but StockService wrapping was missing) — recorded in RED commit body |
| 14-01-03 GREEN — StockService with @Retryable + @Recover + unit tests | ✓ PASS (commit ad02c98) | `./gradlew :core-java:test --tests "uk.jtoye.core.order.StockServiceTest"` | StockServiceTest: 5/5 @Test PASS; all grep criteria verified; integration test STILL RED (expected) |
| 14-01-04 GREEN — Wire StockService into OrderService + fix save-before-decrement + delete adjustStockInBatch | ✓ PASS (commits fe27915 + 20ebf24 fix) | `./gradlew :core-java:test --tests "uk.jtoye.core.order.*" -PincludeIntegration` | ConcurrentStockDecrementIntegrationTest RED→GREEN flip confirmed; all grep criteria verified; 20ebf24 adds Propagation.REQUIRES_NEW (without it the retry never fires because saveAll's commit happens at outer-TX boundary) and broadens @Recover first param to `Throwable` (exact subtype had "Cannot locate recovery method" under generic erasure) |
| 14-01-05 Regression pin — code-search location test + CHANGELOG + full suite | ✓ PASS (commit c77fbdd) | `./gradlew :core-java:test -PincludeIntegration --tests "uk.jtoye.core.order.*" --tests "uk.jtoye.core.common.InsufficientStockExceptionHandlerTest"` | StockDecrementLocationTest: 2/2 PASS; CHANGELOG.md [Unreleased] entry added; full order-package sweep: 61/67 PASS — 6 failures all in `OrderControllerIntegrationTest` with the pre-existing RabbitMQ PLAIN auth cascade documented in `.planning/phases/14-stock-race-fix-summary-aggregation/deferred-items.md` (same root cause as Phase 13 deferred §1). Zero NEW failures |

### Plan 14-02 (CQ-02)

| Task | Status | Verification Command | Acceptance Grep / State |
|------|--------|----------------------|-------------------------|
| 14-02-01 RED — Golden-file + QueryCount + QueryPlan tests + committed golden JSON | ⏳ pending mixed | `./gradlew :core-java:test --tests "uk.jtoye.core.finance.FinancialSummary*" -PincludeIntegration` | Three new test files exist (≥80 lines each); `fixtures/financial-summary-1k.golden.json` is valid JSON parseable as FinancialSummaryDto; **QueryCountTest FAILS** with `expected:<2> but was:<1>` (reliable RED); **QueryPlanTest PASSES** (GREEN pin — independent of production wiring); **GoldenFileTest PASSES** (parity trivially true against its own capture); RED commit body records three expected outcomes |
| 14-02-02 GREEN — JPQL DTOs + 2 repository queries + rewritten getSummary() | ⏳ pending | `./gradlew :core-java:test --tests "uk.jtoye.core.finance.*" -PincludeIntegration` | QueryCountTest RED→GREEN flip (`expected:<2> == actual:<2>`); GoldenFileTest STILL PASS (SQL math == Java math, parity maintained); QueryPlanTest STILL PASS; FinancialTransactionServiceTest PASS (mock arrangement updated); `grep -c "findAll()" FinancialTransactionService.java → 0`; `grep "aggregateForCurrentTenant\|aggregateByVatRate\|GROUP BY ft.vatRate\|ORDER BY ft.vatRate\|COALESCE(SUM(CASE WHEN" FinancialTransactionRepository.java → all present` |
| 14-02-03 Regression pin — cross-tenant isolation + CHANGELOG + full suite | ⏳ pending | `./gradlew :core-java:test -PincludeIntegration --tests "uk.jtoye.core.finance.*"` | FinancialSummaryCrossTenantIsolationTest PASS; `grep "CQ-02 getSummary DB aggregation\|idx_fin_tx_tenant" CHANGELOG.md → ≥1`; full :core-java:test sweep: ZERO new failures (RabbitMQ PLAIN auth failures per Phase 13 deferred-items.md are acceptable, must be called out in commit body) |

## Source Coverage Audit

### GOAL — ROADMAP Phase 14 Goal Statement
"The platform cannot oversell stock under concurrent order confirmations, and summary endpoints scale to 10k+ rows without loading the full table into memory."

| Goal Aspect | Covered By | Status |
|-------------|------------|--------|
| Cannot oversell under concurrent CONFIRM | Plan 14-01 Tasks 01-04 (V34 + @Version + StockService @Retryable + OrderService wiring) | COVERED |
| Summary endpoints scale to 10k+ rows | Plan 14-02 Tasks 01-02 (JPQL aggregation + Index Scan assertion at 10k) | COVERED |

### REQ — REQUIREMENTS.md Phase 14 IDs
| Requirement | Plan | Status |
|-------------|------|--------|
| CQ-01 (stock race fix) | 14-01 | COVERED |
| CQ-02 (getSummary DB aggregation) | 14-02 | COVERED |

### RESEARCH — RESEARCH.md Key Decisions & Constraints
| Decision Point | Plan | Disposition | Status |
|----------------|------|-------------|--------|
| Placement Option C (service method, not state-machine action) | 14-01 Task 04 | LOCKED in plan D-01 | COVERED |
| HTTP 409 for InsufficientStockException | 14-01 Task 01 | LOCKED D-02 | COVERED |
| 3 retries × 50ms backoff | 14-01 Task 03 | LOCKED D-03 | COVERED |
| JPQL over native SQL | 14-02 Task 02 | LOCKED D-04 (14-01 n/a) | COVERED |
| No new index | 14-02 Task 02 | LOCKED D-05 (14-01 n/a) | COVERED |
| Version default 0 | 14-01 Task 01 | LOCKED D-06 | COVERED |
| Fix save-before-decrement ordering (secondary bug) | 14-01 Task 04 | LOCKED D-07 | COVERED |
| State machine action at L53 unchanged | 14-01 Task 04 | LOCKED D-08 | NO-OP (preserved; code-search in Task 05 confirms) |
| Cancel-path restore preserved | 14-01 Task 04 | LOCKED D-09 | COVERED (stockService.restoreForOrder) |
| Unlimited (null quantityInStock) bypass | 14-01 Tasks 03, 04 | LOCKED D-10 | COVERED |
| COALESCE wrap on every aggregate SUM | 14-02 Task 02 | LOCKED D-05 (14-02) | COVERED |
| ORDER BY ft.vatRate + Java Comparator (defense-in-depth) | 14-02 Task 02 | LOCKED D-04 (14-02) | COVERED |
| No caching on getSummary | — | LOCKED D-08 (14-02) | NO-OP (preserved; compliance comment at service L29 stays) |

### CONTEXT — D-XX Decisions
No CONTEXT.md exists for Phase 14 (orchestrator-supplied decisions transcribed as `<locked_decisions>` in each plan). Every orchestrator-locked decision maps to a task above.

### STRIDE Threats
| Threat ID | Plan | Disposition | Mitigation Task |
|-----------|------|-------------|-----------------|
| T-14-01 Business Logic Flaw (race condition) | 14-01 | mitigate | Tasks 01-04 (primary mitigation) + Task 02 integration test pin |
| T-14-02 Business-critical DoS (oversell fulfilment) | 14-01 | mitigate (transitive) | Transitively covered by T-14-01 |
| T-14-03 Information Disclosure (stock enumeration) | 14-01 | accept | Documented in 14-01 threat_model; no code change |
| T-14-04 Aggregate exposure via summary | 14-02 | mitigate | Task 03 `FinancialSummaryCrossTenantIsolationTest` |
| T-14-05 NULL-aggregate tampering via empty-result SUMs | 14-02 | mitigate | Task 02 COALESCE wraps on every aggregate |
| T-14-06 Side-channel timing via EXPLAIN | 14-02 | accept | Test-only path; no production controller |

## Coverage Summary

- v1 requirements this phase: 2 (CQ-01, CQ-02)
- Mapped to tasks: 8 task-level mappings
- Unmapped: 0
- STRIDE threats: 6 enumerated; 4 mitigate + 2 accept; zero transfer
- Exclusions (not gaps):
  - Pre-existing RabbitMQ PLAIN auth failures in ~40 unrelated integration tests (Phase 13 deferred-items.md; scope tracker: not in Phase 14)
  - Caching on getSummary (explicit OUT OF SCOPE per RESEARCH §User Constraints)
  - Pessimistic locking (explicit OUT OF SCOPE — optimistic is the requirement)
  - Reservation-based inventory (v3.x signal per RESEARCH)
  - Adding @Version to Order/Shop (already present via V32)

## Nyquist Sampling Rate

- **Per task commit:** `./gradlew :core-java:test --tests "<most-specific-class>"` — < 60 seconds per task
- **Per plan end:** `./gradlew :core-java:test --tests "<plan-package>.*" -PincludeIntegration` — ~2-5 min
- **Phase gate (end of Wave 1):** Full `./gradlew :core-java:test -PincludeIntegration` sweep — expect ~5 min against Docker; accept only RabbitMQ PLAIN auth failures per Phase 13 deferred-items.md
- **`nyquist_compliant: true` flip condition:** All 22 ⏳ rows above turn into PASS rows after plan execution

*Generated: 2026-04-18. Flip `nyquist_compliant: true` only after all per-task commands pass post-execution.*
