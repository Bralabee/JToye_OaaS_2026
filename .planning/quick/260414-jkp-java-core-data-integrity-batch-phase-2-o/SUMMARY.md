# Quick Task 260414-jkp — SUMMARY

**Status:** ✅ Complete (PR held until all audit phases done)
**Branch:** `fix/java-core-data-integrity`
**Commits:** 10 fix commits + 1 nullable follow-up + 1 planning artifact
**Tests:** `./gradlew :core-java:test` BUILD SUCCESSFUL, 335 tests, 0 failures (baseline 324 + 11 new)

## Commits

| # | SHA | Subject |
|---|-----|---------|
| 1 | d318fd2 | perf(order): batch product loads in stock decrement loop |
| 2 | aeb0913 | fix(cache): require tenant context in cache key generator |
| 3 | f4f3b2c | fix(cache): scope shop/product cache eviction to tenant |
| 4 | 775ccc6 | feat(payment): transactional outbox for payment events |
| 5 | 91f564c | refactor(order): remove deprecated updateOrderStatus bypass |
| 6 | 867447a | fix(api): add @Valid to all @RequestBody params |
| 7 | 22f071b | feat(orm): optimistic locking on Order and Shop |
| 8 | 7525bfe | fix(actuator): restrict prometheus endpoint to dev profile |
| 9 | e5dca65 | docs(security): justify CSRF disable for stateless JWT |
| 10 | eca0eaa | fix(payment): redact stripe api key from logs and toString |
| f/u | 4c022ee | docs(orm): annotate getVersion() as @Nullable |

## Highlights

- **N+1 eliminated**: `OrderService.transitionOrder` stock loops now bulk-load via `findAllById` + `saveAll`. 5-item order: 10 → 2 DB round-trips.
- **Tenant cache safety**: `TenantAwareCacheKeyGenerator` now throws if `TenantContext` is unset. `TenantCacheEvictor` replaces all 13 `@CacheEvict(allEntries=true)` sites with tenant-scoped key eviction.
- **Transactional outbox**: `PaymentEventPublisher` now persists PENDING rows in `payment_event_outbox`; `PaymentEventOutboxFlusher` drains to RabbitMQ with retry+DLQ semantics. Broker outage no longer drops events. V31 migration.
- **Optimistic locking**: `@Version` on Order and Shop + V32 migration. Getter annotated `@Nullable` (null pre-flush).
- **@Valid audit**: 14/17 → 16/17 `@RequestBody` params. Remaining one is Stripe webhook raw-String (N/A; signature verification is the integrity check).
- **Deprecated bypass removed**: `OrderController PATCH /orders/{id}/status` + service method deleted; tests migrated to proper state-machine path.
- **Actuator hardening**: prometheus endpoint removed from prod and base profiles; exposed only in `application-local.yml` and staging.
- **CSRF justification**: 8-line comment above `csrf.disable()` explaining stateless JWT bearer model.
- **Stripe key**: static init guarded via `AtomicBoolean` CAS; `StripeProperties.toString()` masks apiKey + webhookSecret.

## Deviations
1. **Fix #4** payload stored as TEXT not JSONB (cleaner Hibernate round-trip).
2. **Fix #7** concurrency test shipped as reflection-level regression fence (`OptimisticLockingConfigurationTest`) rather than Postgres race test (testcontainers disabled by default tag).
3. **Fix #3** removed all 13 `allEntries=true` sites (target was ≥10).
4. **Fix #8** dev profile is `application-local.yml`, not `application-dev.yml`.
5. **Follow-up** added `@Nullable` on `getVersion()` in both entities after JDT null-analysis warnings surfaced; JDT's own annotation namespace means the IDE warning may persist, but semantic intent is now explicit.

## Known non-blockers
- Eclipse JDT null-analysis warnings on `ProductServiceTest.java` (Mockito generic-inference pattern, pre-existing) — surfaced because Phase 2 touched the file to extend the mock constructor list. Not regressions. No test failures.
