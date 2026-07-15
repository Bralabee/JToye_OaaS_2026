# Phase 14 Deferred Items

Out-of-scope issues surfaced by Phase 14 Task 14-01-05 and 14-02-03 regression sweeps.

## 1. Pre-existing RabbitMQ PLAIN auth failures in OrderControllerIntegrationTest

**Symptom:** `./gradlew :core-java:test --tests "uk.jtoye.core.order.*" -PincludeIntegration` shows 6 failures in `OrderControllerIntegrationTest` (testCreateOrder, testDeleteOrder, testUpdateOrderStatus, testGetOrdersByStatus, testGetOrderById, testTenantIsolation). All fail during ApplicationContext startup with:

```
FatalListenerStartupException: Authentication failure
Caused by: ApplicationContextException: Failed to start bean
  'org.springframework.amqp.rabbit.config.internalRabbitListenerEndpointRegistry'
```

**Root cause:** Same as Phase 13 `deferred-items.md` #1 — the developer machine runs a RabbitMQ broker on `localhost:5672` with non-default credentials. `OrderControllerIntegrationTest` does NOT have the Phase 12 Deviation #3 stub-broker override (`spring.rabbitmq.port=0` + `listener.simple.auto-startup=false`) and so attempts to authenticate against the real broker at startup.

**Why out-of-scope for Phase 14:** Pre-existing; cascades from Phase 12/13 `deferred-items.md`. Phase 14 scope is CQ-01 only; adding the stub-broker override to `OrderControllerIntegrationTest` would fix these 6 tests but falls under a separate test-scaffolding phase (tracked in Phase 13 deferred-items.md §1 recommended follow-up — "audit application-test.yml to set spring.rabbitmq.port: 0 + listener.simple.auto-startup: false as defaults").

**Reproducer:** Check out `9c5309b` (Phase 13 HEAD, pre-Phase-14) and run the same command — same 6 failures observed.

**Impact on CQ-01 closure:** Zero. The CQ-01 success criteria all map to the NEW Phase 14 tests which all PASS:

| CQ-01 Test | Status |
|---|---|
| `ConcurrentStockDecrementIntegrationTest.concurrentConfirm_oneWins_oneThrowsInsufficientStock` | ✓ PASS |
| `StockServiceTest` (5 methods) | ✓ PASS |
| `StockDecrementLocationTest` (2 methods) | ✓ PASS |
| `InsufficientStockExceptionHandlerTest.throwInsufficientStockReturns409` | ✓ PASS |
| `OrderServiceTest` (39 methods including 2 new delegation tests) | ✓ PASS |

**Tracking:** Same issue as Phase 13 deferred-items.md #1. Fix is a cross-cutting test-infrastructure phase; not on any phase's roadmap yet.

## 2. Phase 14-02 regression-sweep carryover — same RabbitMQ PLAIN auth + Testcontainers superuser RLS bypass

**Phase 14-02 full-suite run (`./gradlew :core-java:test -PincludeIntegration`):** 415 tests / 37 failed / 1 skipped. Every one of the 37 failures is the same RabbitMQ PLAIN auth failure from item #1 above — cascaded across `OrderControllerIntegrationTest`, `CustomerControllerIntegrationTest`, `ShopControllerIntegrationTest`, `FinancialTransactionControllerIntegrationTest`, `AuditIntegrationTest`, `MultiTenantIsolationIntegrationTest`, `TenantSetLocalAspectTest`. None are caused by CQ-02 changes. CQ-02's own 5 new + 1 existing finance test class (28 test methods total) all PASS.

**Environmental caveat discovered during 14-02-03 cross-tenant test development:** Testcontainers Postgres 15 runs as a SUPERUSER ("test"), and Postgres superusers bypass RLS unconditionally — regardless of `FORCE ROW LEVEL SECURITY` and regardless of the `NOBYPASSRLS` attribute set by V2. The fix in V2 (`ALTER ROLE current_user NOBYPASSRLS`) removes the BYPASSRLS attribute but leaves SUPERUSER intact; in Postgres, SUPERUSER is what actually grants RLS bypass. This means no existing test (including `MultiTenantIsolationIntegrationTest`) actually verifies RLS enforcement directly — they pass by running INSIDE a `@Transactional` that rolls back, leaving no cross-tenant evidence. `FinancialSummaryCrossTenantIsolationTest` documents this via its class-level javadoc and pins what IS verifiable: per-tenant aggregate disjointness + JPQL-has-no-explicit-tenant-predicate reliance on RLS.

**Proper fix (deferred):** Add a non-superuser `jtoye_app_role` to V2 + extend test scaffold with an `@BeforeEach` that `SET SESSION AUTHORIZATION jtoye_app_role` on the Hikari connection. Falls under the same test-infrastructure phase as item #1.

**CQ-02 closure status:** Shippable. CQ-02 success criteria (output parity on 1k fixture + EXPLAIN ANALYZE index scan + no existing-test regression) are all green.
