---
phase: quick-260708-tsl
plan: 01
subsystem: infra
tags: [redis, lettuce, spring-cache, bucket4j, rate-limiting, resilience, micrometer, testcontainers]

# Dependency graph
requires:
  - phase: "#71 RLS integration-suite CI enablement"
    provides: "integrationTest Gradle task + IntegrationTestSupport Testcontainers harness"
  - phase: "#78 P0-2 prod-profile fix"
    provides: "ActiveProfileValidator (fail-fast on unknown Spring profiles)"
provides:
  - "RedisCacheErrorHandler — degrades @Cacheable GET/PUT/EVICT/CLEAR errors to log-and-continue (source-of-truth fallback), metered via jtoye.cache.errors"
  - "CacheConfig wires the handler via CachingConfigurer.errorHandler()"
  - "Explicit bounded Lettuce command timeout on the hand-rolled rate-limit client (from spring.data.redis.timeout, no literal)"
  - "RateLimitInterceptor fail-open-with-alarm on any Redis error, metered via jtoye.ratelimit.fail_open"
  - "RedisFaultInjectionIntegrationTest — Testcontainers proof that a Redis outage degrades gracefully (no 500, no 60s hang)"
affects: [rate-limiting, caching, observability, resilience]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "CacheErrorHandler via CachingConfigurer to make Redis a soft dependency for @Cacheable"
    - "Null-safe MeterRegistry via ObjectProvider constructor (mirrors PaymentEventOutboxFlusher)"
    - "Fault-injection integration test: warm cache → redis.stop() → assertTimeoutPreemptively bounded assertions"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/config/RedisCacheErrorHandler.java
    - core-java/src/test/java/uk/jtoye/core/config/RedisCacheErrorHandlerTest.java
    - core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorFailOpenTest.java
    - core-java/src/test/java/uk/jtoye/core/resilience/RedisFaultInjectionIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java
    - core-java/src/main/java/uk/jtoye/core/config/RateLimitConfig.java
    - core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java
    - core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorTest.java
    - docs/metrics.json
    - CLAUDE.md

key-decisions:
  - "Rate limiter fails OPEN on Redis errors (availability over enforcement) but alarms via jtoye.ratelimit.fail_open + WARN — a Redis blip must not become the outage this issue closes"
  - "EVICT/CLEAR cache errors swallowed (write path stays alive) but logged at ERROR + metered distinctly (staleness alarm); TTLs (products 10m, shops 15m) bound the staleness window"
  - "Fault-injection test runs under the 'dev' profile (not a bespoke 'redisfault' profile) because ActiveProfileValidator (#78) fail-fasts on unknown profiles; 'dev' keeps CacheConfig (@Profile(\"!test\")) active"

patterns-established:
  - "Soft-dependency Redis: CacheErrorHandler + bounded command timeout + fail-open interceptor"
  - "Observable degrade: every fallback increments a Micrometer counter so operators can alert"

requirements-completed: [ISSUE-86-P1-4]

# Metrics
duration: 30min
completed: 2026-07-08
---

# Phase quick-260708-tsl: Issue #86 [P1-4] Redis Resilience Summary

**Redis is now a soft dependency: a `@Cacheable` read degrades to source-of-truth (no HTTP 500) and the Bucket4j rate limiter fails open within a bounded Lettuce command timeout (no ~60s hang), both alarmed via Micrometer counters — proven by a Testcontainers fault-injection test that stops Redis mid-run.**

## Performance

- **Duration:** ~30 min
- **Started:** 2026-07-08T21:56:42Z
- **Completed:** 2026-07-08T22:26:35Z
- **Tasks:** 3
- **Files modified:** 10 (4 created, 6 modified)

## Accomplishments
- `RedisCacheErrorHandler` swallows all four cache error paths (GET/PUT at WARN, EVICT/CLEAR at ERROR with an explicit staleness note) and meters each as `jtoye.cache.errors{operation,cache}`; wired via `CacheConfig implements CachingConfigurer`.
- Explicit Lettuce command timeout on the hand-rolled rate-limit client, sourced from the existing per-profile `spring.data.redis.timeout` (2000ms base / 2500 staging / 3000 prod) — no hardcoded literal — replacing Lettuce's 60s default.
- `RateLimitInterceptor` wraps the Redis bucket build/consume in try/catch: on any error it logs WARN, increments `jtoye.ratelimit.fail_open`, and returns true (fail open). Happy-path 200/429 logic unchanged.
- `RedisFaultInjectionIntegrationTest` (real Postgres + real Redis) stops Redis mid-test and asserts, within `assertTimeoutPreemptively(10s)`, both (A) the cached read still returns the entity from the DB and (B) `preHandle` returns true — no throw, no 500, no hang.

## Task Commits

Each task was committed atomically (TDD: test + impl in one commit per task):

1. **Task 1: Cache resilience — CacheErrorHandler** — `5cafe1c` (feat)
2. **Task 2: Rate-limiter resilience — bounded timeout + fail-open-with-alarm** — `3ddfdf3` (feat)
3. **Task 3: Fault-injection integration test + docs regen** — `fd5c193` (test)

_SUMMARY.md / STATE.md are intentionally left uncommitted for the orchestrator._

## Files Created/Modified
- `core-java/.../config/RedisCacheErrorHandler.java` (created) — CacheErrorHandler degrading Redis errors to log-and-continue.
- `core-java/.../config/CacheConfig.java` (modified) — implements CachingConfigurer, overrides errorHandler(); existing beans untouched.
- `core-java/.../config/RateLimitConfig.java` (modified) — RedisURI.withTimeout + RedisClient.setDefaultTimeout from spring.data.redis.timeout.
- `core-java/.../security/RateLimitInterceptor.java` (modified) — fail-open try/catch + jtoye.ratelimit.fail_open counter (ObjectProvider constructor).
- `core-java/.../config/RedisCacheErrorHandlerTest.java` (created) — 5 unit tests (four ops + null-registry).
- `core-java/.../security/RateLimitInterceptorFailOpenTest.java` (created) — fail-open + counter unit test.
- `core-java/.../resilience/RedisFaultInjectionIntegrationTest.java` (created) — @Tag("testcontainers") AC1+AC2 proof.
- `core-java/.../security/RateLimitInterceptorTest.java` (modified) — ObjectProvider<MeterRegistry> mock for the new constructor.
- `docs/metrics.json` (modified) — regenerated 739 → 746 (+7 Java @Test, +3 files).
- `CLAUDE.md` (modified) — synced the test-count prose to 746/547/87.

## Decisions Made
- **Fail open, not closed, on the rate-limit path** — a Redis outage temporarily disables throttling (accepted STRIDE T-tsl-03) rather than 503-ing every request; alarmed via `jtoye.ratelimit.fail_open`. Failing closed would turn a Redis blip into the very outage #86 closes.
- **EVICT/CLEAR swallowed at ERROR** (accepted STRIDE T-tsl-04) — a stale entry may survive a write, but rethrowing re-introduces the 500 on the write path; TTLs bound the window and the distinct metric alarms it.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fault-injection profile: `dev` instead of `redisfault`**
- **Found during:** Task 3 (integration test context boot)
- **Issue:** The plan specified `@ActiveProfiles("redisfault")`, but `ActiveProfileValidator` (added in issue #78, after this plan was written) runs as an `EnvironmentPostProcessor` and fail-fasts on any profile outside `{local,dev,test,staging,prod}` — so `redisfault` aborts startup with `IllegalStateException`.
- **Fix:** Used `@ActiveProfiles("dev")` — a behaviour-neutral known profile that is NOT `test`, so `CacheConfig` (`@Profile("!test")`) still loads and caching + the CacheErrorHandler are active. All other settings supplied via `@DynamicPropertySource` exactly as planned.
- **Files modified:** RedisFaultInjectionIntegrationTest.java
- **Verification:** Context boots; test green.
- **Committed in:** `fd5c193`

**2. [Rule 3 - Blocking] Did NOT exclude RabbitAutoConfiguration**
- **Found during:** Task 3 (first boot attempt → `NoSuchBeanDefinitionException: RabbitTemplate`)
- **Issue:** The plan directed setting `spring.autoconfigure.exclude=...RabbitAutoConfiguration`. Under a non-`test` profile that removes the `RabbitTemplate`/`ConnectionFactory` beans, which are hard constructor dependencies of `OrderEventPublisher` → `OrderService` → `OrderController` and `RabbitMQConfig.rabbitListenerContainerFactory` → context startup fails.
- **Fix:** Kept the autoconfig beans but pointed them at a dead port (`spring.rabbitmq.port=0`) with `listener.simple.auto-startup=false`; Lettuce/Rabbit connections are lazy so the context boots brokerless (the proven `CrossTenantSpoofIntegrationTest` pattern).
- **Files modified:** RedisFaultInjectionIntegrationTest.java
- **Verification:** Context boots; test green.
- **Committed in:** `fd5c193`

**3. [Rule 3 - Blocking] Neutralised DatabaseConfigurationValidator in the test**
- **Found during:** Task 3 (second boot attempt → `DatabaseConfigurationValidator$SecurityConfigurationException`)
- **Issue:** `DatabaseConfigurationValidator` (`@Profile("!test")`, `@EventListener(ApplicationReadyEvent)`) fail-fasts when the DB user is a PostgreSQL SUPERUSER. The Testcontainers bootstrap role IS a superuser, and this test deliberately keeps it (per the plan: it proves resilience, not RLS isolation). Every other integration test dodges the validator via the `test` profile — unavailable here because `CacheConfig` needs a non-`test` profile.
- **Fix:** `@MockBean DatabaseConfigurationValidator` — a no-op mock whose `@EventListener` does nothing. Irrelevant to what this test verifies.
- **Files modified:** RedisFaultInjectionIntegrationTest.java
- **Verification:** Context boots; test green.
- **Committed in:** `fd5c193`

**4. [Rule 3 - Blocking] Updated existing RateLimitInterceptorTest for the new constructor**
- **Found during:** Task 2 (adding the ObjectProvider constructor to RateLimitInterceptor)
- **Issue:** The new `RateLimitInterceptor(ObjectProvider<MeterRegistry>)` constructor made the existing `@InjectMocks` construction pass `null` for the provider → NPE in the constructor, breaking all 9 pre-existing interceptor tests.
- **Fix:** Added a `@Mock ObjectProvider<MeterRegistry>` field; `getIfAvailable()` returns null by default → counter absent (the intended null-safe path). No behavioural change to the existing assertions.
- **Files modified:** RateLimitInterceptorTest.java
- **Verification:** RateLimitInterceptorTest 9/9 green.
- **Committed in:** `3ddfdf3`

---

**Total deviations:** 4 auto-fixed (all Rule 3 - blocking). Plus one requested hygiene edit: synced the `CLAUDE.md` test-count prose (per environment note) alongside the `docs/metrics.json` regen.
**Impact on plan:** All four were context-boot / compile blockers, not scope changes. The production behaviour delivered is exactly what the plan and `must_haves` specified; only the integration-test scaffolding differed from the plan's suggested profile/exclude approach (which pre-dated `ActiveProfileValidator`).

## Issues Encountered
- Booting the full application context under a non-`test` profile surfaced three startup guards the `test` profile normally suppresses (RabbitMQ hard deps, `DatabaseConfigurationValidator`, and the profile-name allow-list). Each resolved as documented above; no production code was weakened.

## Test Results (exact)
- `./gradlew :core-java:test` (unfiltered, full): **447 tests, 0 failures, 0 errors, 1 skipped** (59 classes)
- `./gradlew :core-java:integrationTest` (unfiltered, full Testcontainers): **100 tests, 0 failures, 0 errors, 1 skipped** (28 classes)
- Targeted: `RedisCacheErrorHandlerTest` 5/5, `RateLimitInterceptorFailOpenTest` 1/1, `RateLimitInterceptorTest` 9/9, `RedisFaultInjectionIntegrationTest` 1/1
- `scripts/docs-freshness.sh` gate: **OK** (total logical invocations 746)

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Issue #86 [P1-4] closed. Remaining P1 backlog: #87, #88.
- Two new Micrometer counters (`jtoye.cache.errors`, `jtoye.ratelimit.fail_open`) are available for Prometheus/Grafana alerting — an alert rule on either would flag a Redis degradation window.

---
*Phase: quick-260708-tsl*
*Completed: 2026-07-08*

## Self-Check: PASSED
- All 4 created source/test files present on disk.
- SUMMARY.md present (intentionally uncommitted for the orchestrator).
- All 3 task commits present in git history (5cafe1c, 3ddfdf3, fd5c193).
- Working tree clean except the uncommitted SUMMARY.md (expected).
