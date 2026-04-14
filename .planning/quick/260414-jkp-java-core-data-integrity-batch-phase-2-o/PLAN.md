# Quick Task 260414-jkp: Java Core Data Integrity (Audit Phase 2)

**Branch:** `fix/java-core-data-integrity`
**Goal:** Close 10 verified data-integrity, security, and correctness gaps in `core-java/`. One atomic commit per fix; Gradle test gate after each.

## Verified findings

| # | File:Line | Issue | Fix |
|---|-----------|-------|-----|
| 1 | `core-java/src/main/java/uk/jtoye/core/order/OrderService.java:351-374` | N+1: stock decrement/restore loops `findById()` + `save()` per item | Replace with `findAllById(productIds)` then bulk update; persist via single `saveAll`. Add a unit test that asserts a 5-item order issues exactly 1 SELECT and 1 batch UPDATE (or count repository calls via Mockito.verify). |
| 2 | `core-java/src/main/java/uk/jtoye/core/config/TenantAwareCacheKeyGenerator.java:32-54` | `TenantContext.get().orElse(null)` collapses keys when tenant unset → cross-tenant cache risk | Replace `.orElse(null)` with `.orElseThrow(() -> new IllegalStateException("TenantContext required for cacheable call: " + method))`. Add a unit test that asserts the throw. |
| 3 | `core-java/src/main/java/uk/jtoye/core/shop/ShopService.java:37,91,112,143,161,174` + `ProductService.java:48,113,136,158,174,197` | 13× `@CacheEvict(allEntries=true)` blasts every tenant's cache on any tenant's write | Remove `allEntries=true`; switch to keyed eviction using the tenant-aware key. For each annotated method, declare `@CacheEvict(value="...", key="…tenant-scoped…")`. Where the eviction needs to clear all entries for THIS tenant only, introduce a small `TenantCacheEvictor` helper backed by `CacheManager.getCache(name).evictIfPresent(tenantPrefix)` — or, if Spring's keyed eviction can't easily target a prefix, scope the eviction to the specific cache key of the affected entity. Document choice in commit body. |
| 4 | `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventPublisher.java:48-59` | try/catch swallows RabbitMQ failures with only a log | Implement a minimal **transactional outbox**: persist a `PaymentEventOutbox` row (event_type, payload JSON, tenant_id, created_at, status=PENDING) inside the same `@Transactional` method; a scheduled `@Scheduled` flusher publishes PENDING rows and marks them SENT, with retries. Add Flyway migration `V31__payment_event_outbox.sql`. Tests: persist + flush happy path, persist when broker down, flush retries. |
| 5 | `OrderController.java:144-151` + `OrderService.java:261-274` | Deprecated `updateOrderStatus` direct-set bypasses state machine | Delete the deprecated method AND the controller endpoint that routes to it. Update tests. If any frontend or integration test calls the route, route it through the proper transition endpoint instead. |
| 6 | `core-java/src/main/java/uk/jtoye/core/order/OrderController.java:102` | `updateOrder(@RequestBody UpdateOrderRequest request)` missing `@Valid` | Add `@Valid`. Audit all `@RequestBody` params across `**/*Controller.java`; report and fix any others (target: every `@RequestBody` annotated with `@Valid`). |
| 7 | `core-java/src/main/java/uk/jtoye/core/order/Order.java`, `core-java/src/main/java/uk/jtoye/core/shop/Shop.java` | No `@Version` optimistic locking | Add `@Version private Long version;` to `Order` and `Shop` entities. Add a Flyway migration `V32__optimistic_locking.sql` adding `version BIGINT NOT NULL DEFAULT 0` columns to `orders` and `shops`. Update mappers/DTOs only if version needs to be exposed (otherwise leave hidden). Add a concurrency test. |
| 8 | `core-java/src/main/resources/application.yml:87-91` | Actuator `prometheus` endpoint exposed via base profile | Remove `prometheus` from the base `management.endpoints.web.exposure.include`. Move it to `application-dev.yml` (and `application-staging.yml` if it exists). In `application-prod.yml`, expose only `health,info`. Verify by grep: prod profile must NOT include `prometheus` or `metrics`. |
| 9 | `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java:52` | `csrf.disable()` without justifying comment | Add a 2-line comment block above the disable: explain that the API is stateless JWT bearer auth, no cookies, so CSRF doesn't apply; and that browser-form posts must use the auth flow not direct POSTs. |
| 10 | `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java:50-58` | Static `Stripe.apiKey =` set in constructor; risk of accidental log leak | Wrap in a small static-initializer guard so it's set once per JVM (idempotent). Replace any `log.debug("Stripe key …")` references (grep) with redacted versions. Add a unit test that asserts the API key never appears in `toString()` of the configured properties bean. |

## Commit sequence

1. `perf(order): batch product loads in stock decrement loop`
2. `fix(cache): require tenant context in cache key generator`
3. `fix(cache): scope shop/product cache eviction to tenant`
4. `feat(payment): transactional outbox for payment events`
5. `refactor(order): remove deprecated updateOrderStatus bypass`
6. `fix(api): add @Valid to all @RequestBody params`
7. `feat(orm): optimistic locking on Order and Shop`
8. `fix(actuator): restrict prometheus endpoint to dev profile`
9. `docs(security): justify CSRF disable for stateless JWT`
10. `fix(payment): redact stripe api key from logs and toString`

## Test gate after each commit

```bash
cd core-java && ./gradlew test --console=plain
```

Stop on red. Investigate. Fix or revert. No proceed-on-red.

## Exit criteria

- 10 atomic commits + final `docs(planning)` commit
- Gradle test green on every commit
- SUMMARY.md written with SHAs, file lists, deviations
- Branch NOT pushed; orchestrator handles PRs after all phases done
