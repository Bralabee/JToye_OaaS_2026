# Phase 14 Deferred Items

Out-of-scope issues surfaced by Phase 14 Task 14-01-05 regression sweep.

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
