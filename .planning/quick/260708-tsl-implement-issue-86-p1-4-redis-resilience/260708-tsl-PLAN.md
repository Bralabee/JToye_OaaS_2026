---
phase: quick-260708-tsl
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - core-java/src/main/java/uk/jtoye/core/config/RedisCacheErrorHandler.java
  - core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java
  - core-java/src/main/java/uk/jtoye/core/config/RateLimitConfig.java
  - core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java
  - core-java/src/test/java/uk/jtoye/core/config/RedisCacheErrorHandlerTest.java
  - core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorFailOpenTest.java
  - core-java/src/test/java/uk/jtoye/core/resilience/RedisFaultInjectionIntegrationTest.java
  - docs/metrics.json
autonomous: true
requirements: [ISSUE-86-P1-4]

must_haves:
  truths:
    - "With Redis down, a @Cacheable read degrades to source-of-truth and returns its value (no exception, no HTTP 500)."
    - "Cache GET/PUT errors are logged (WARN) and swallowed; EVICT/CLEAR errors are logged at ERROR with a distinct metric (staleness alarm) but do NOT fail the write path."
    - "The rate-limit interceptor fails open on Redis errors within a bounded time (explicit Lettuce command timeout), emitting a WARN log + Micrometer counter so it alarms rather than failing silently."
    - "A Testcontainers fault-injection test stops Redis mid-test and proves both the cached-read degrade (returns value, no throw) and the bounded rate-limit path (preHandle returns true within a bounded time, no hang, no 500)."
    - "Full :core-java:test and :core-java:integrationTest pass; docs/metrics.json regenerated and the docs-freshness gate is green."
  artifacts:
    - path: "core-java/src/main/java/uk/jtoye/core/config/RedisCacheErrorHandler.java"
      provides: "CacheErrorHandler that degrades cache errors to log-and-continue with Micrometer counters"
      contains: "implements CacheErrorHandler"
    - path: "core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java"
      provides: "CachingConfigurer wiring of the custom error handler"
      contains: "implements CachingConfigurer"
    - path: "core-java/src/main/java/uk/jtoye/core/config/RateLimitConfig.java"
      provides: "Explicit Lettuce command timeout on the hand-rolled rate-limit Redis client"
      contains: "withTimeout"
    - path: "core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java"
      provides: "try/catch fail-open-with-alarm around the Redis bucket consume"
      contains: "catch"
    - path: "core-java/src/test/java/uk/jtoye/core/resilience/RedisFaultInjectionIntegrationTest.java"
      provides: "Fault-injection integration test (Redis unavailable) covering both ACs"
      contains: "@Tag(\"testcontainers\")"
  key_links:
    - from: "core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java"
      to: "RedisCacheErrorHandler"
      via: "CachingConfigurer.errorHandler() override returns the handler"
      pattern: "errorHandler"
    - from: "core-java/src/main/java/uk/jtoye/core/config/RateLimitConfig.java"
      to: "spring.data.redis.timeout"
      via: "@Value Duration injected and applied to RedisURI.withTimeout"
      pattern: "spring.data.redis.timeout"
    - from: "core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java"
      to: "MeterRegistry counter"
      via: "catch block increments jtoye.ratelimit.fail_open"
      pattern: "increment"
---

<objective>
Close GitHub issue #86 [P1-4]: a Redis blip currently becomes a full-platform outage. Make Redis a soft dependency across the two paths that touch it — Spring Cache (`@Cacheable`) and the Bucket4j rate limiter — so a Redis outage degrades gracefully instead of turning every request into a hung 60s call and an HTTP 500.

Purpose: Cached reads must fall back to source-of-truth when Redis is unavailable, and the rate-limit path must fail open within a bounded time with an alarm (metric + log), not hang or 500.

Output: A `CacheErrorHandler` wired via `CachingConfigurer`; an explicit Lettuce command timeout on the hand-rolled rate-limit client; a try/catch fail-open-with-alarm in `RateLimitInterceptor`; unit tests for both handlers; and a Testcontainers fault-injection integration test that stops Redis mid-test. No schema change.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@./CLAUDE.md

<interfaces>
<!-- Key contracts the executor needs. Extracted from the codebase — no exploration required. -->

CacheConfig.java (config, @Profile("!test"), @EnableCaching) — currently exposes:
  @Bean CacheManager cacheManager(RedisConnectionFactory connectionFactory)   // auto-configured Lettuce factory; honors spring.data.redis.timeout
  private GenericJackson2JsonRedisSerializer jsonRedisSerializer()
  @Bean TenantAwareCacheKeyGenerator tenantAwareCacheKeyGenerator()
  NOTE: class is @Profile("!test") — it does NOT load under the "test" profile, so caching + any CacheErrorHandler are inert in the unit `test` task. The fault-injection integration test must therefore run under a NON-"test" profile (see Task 3).

RateLimitConfig.java (config, @ConditionalOnProperty rate-limiting.enabled=true default) — currently:
  @Value spring.data.redis.host/port/password ; rate-limiting.enabled
  @Bean LettuceBasedProxyManager<String> lettuceBasedProxyManager()
    builds RedisURI via RedisURI.builder().withHost().withPort()[.withPassword()].build()   // NO .withTimeout() -> Lettuce default 60s
    RedisClient.create(redisUri) ; redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE))

RateLimitInterceptor.java (security, @Component implements HandlerInterceptor):
  @Autowired(required = false) ProxyManager<String> proxyManager
  @Value rate-limiting.enabled / default-limit(100) / burst-capacity(20)
  boolean preHandle(req,res,handler):
    if (!rateLimitingEnabled || proxyManager == null) return true;      // fail-open-when-unconfigured (leave as-is)
    if (isExcludedPath) return true;
    Optional<UUID> tenant = TenantContext.get(); if empty -> WARN + return true;
    var bucket = proxyManager.builder().build(key, configSupplier);      // <-- Redis call, NO try/catch today
    var probe  = bucket.tryConsumeAndReturnRemaining(1);                 // <-- Redis call, NO try/catch today
    if (probe.isConsumed()) { set X-RateLimit-* headers; return true; }
    else { set 429 + Retry-After + JSON body; return false; }

Micrometer counter pattern already used in the codebase (BusinessMetricsService, PaymentEventOutboxFlusher):
  constructor takes ObjectProvider<MeterRegistry>; MeterRegistry reg = provider.getIfAvailable();
  Counter c = reg != null ? Counter.builder("name").tag(...).register(reg) : null;
  ... if (c != null) c.increment();

CacheErrorHandler contract (org.springframework.cache.interceptor.CacheErrorHandler):
  void handleCacheGetError(RuntimeException ex, Cache cache, Object key)
  void handleCachePutError(RuntimeException ex, Cache cache, Object key, Object value)
  void handleCacheEvictError(RuntimeException ex, Cache cache, Object key)
  void handleCacheClearError(RuntimeException ex, Cache cache)
  (Spring's default SimpleCacheErrorHandler RE-THROWS all four — that is exactly the current 500 behavior.)

CachingConfigurer contract (org.springframework.cache.annotation.CachingConfigurer, Spring 6 — all default methods; CachingConfigurerSupport is removed):
  default CacheErrorHandler errorHandler() { return null; }   // override to return our handler
</interfaces>

<config_facts>
spring.data.redis.timeout (Lettuce command timeout for the AUTO-configured cache factory): base application.yml 2000ms, application-prod.yml 3000ms, application-staging.yml 2500ms. dev/local inherit the base default. application-test.yml disables rate limiting and the cache profile.
Reuse this existing per-profile key for the hand-rolled rate-limit client's command timeout — do NOT introduce a hardcoded literal (project config rule).
Gradle: `test` task excludes @Tag("testcontainers"); `integrationTest` task (already registered) includes it. Testcontainers postgresql + junit-jupiter are on the test classpath; Redis is via testcontainers core GenericContainer (see the disabled RateLimitIntegrationTest.java.disabled for the redis:7-alpine pattern). No build.gradle change is needed.
docs/metrics.json baseline: total_logical_invocations 736 (issue #85 lands in parallel and will shift this — regenerate and VERIFY at execution time, do not pin a number).
</config_facts>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Cache resilience — CacheErrorHandler degrades Redis cache errors to source-of-truth</name>
  <files>core-java/src/main/java/uk/jtoye/core/config/RedisCacheErrorHandler.java, core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java, core-java/src/test/java/uk/jtoye/core/config/RedisCacheErrorHandlerTest.java</files>
  <behavior>
    - handleCacheGetError: logs at WARN, increments counter jtoye.cache.errors{operation=get}, and RETURNS (swallows) so the @Cacheable call proceeds to source-of-truth as a cache miss — does NOT rethrow.
    - handleCachePutError: logs at WARN, increments jtoye.cache.errors{operation=put}, swallows (a failed write-through must not fail the read).
    - handleCacheEvictError: logs at ERROR (staleness risk — a stale entry may survive), increments jtoye.cache.errors{operation=evict}, but still swallows so the write path completes. Documented as the deliberate chosen behaviour.
    - handleCacheClearError: logs at ERROR, increments jtoye.cache.errors{operation=clear}, swallows.
    - Null-safe MeterRegistry: with a null registry the handler still logs and swallows without NPE (metrics simply absent).
  </behavior>
  <action>Create `RedisCacheErrorHandler` in the `config` package implementing `org.springframework.cache.interceptor.CacheErrorHandler`. Constructor takes an `ObjectProvider<MeterRegistry>` (null-safe, mirroring PaymentEventOutboxFlusher): resolve `getIfAvailable()` once. Meter each error as `jtoye.cache.errors` with an `operation` tag per method (get/put/evict/clear) plus a `cache` tag sourced from `cache.getName()` — register on demand via `Counter.builder("jtoye.cache.errors").tags("operation", op, "cache", cache.getName()).register(reg)` (MeterRegistry dedupes by name+tags) guarded on reg != null. All four methods log the exception message + cache name and swallow it. GET/PUT log at WARN; EVICT/CLEAR log at ERROR with an explicit "cache may be stale" note in the message. Add a class Javadoc stating the degrade-to-source-of-truth intent and the deliberate EVICT/CLEAR swallow-with-ERROR-alarm decision (per issue #86 fix direction). Then modify `CacheConfig` to `implements CachingConfigurer` and override `errorHandler()` to return a `RedisCacheErrorHandler` instance; construct it from an injected `ObjectProvider<MeterRegistry>` (add a constructor or field). Keep the existing `@Profile("!test")`, `@EnableCaching`, `cacheManager`, serializer, and key-generator beans untouched. Write `RedisCacheErrorHandlerTest` as a plain JUnit 5 unit test (NO Spring context, NO testcontainers tag) that instantiates the handler with a SimpleMeterRegistry (and once with a null-provider) and asserts each of the four methods does not throw when handed a RuntimeException + a `ConcurrentMapCache` Cache, and that the corresponding counter increments.</action>
  <verify>
    <automated>cd core-java && ./gradlew test --tests "uk.jtoye.core.config.RedisCacheErrorHandlerTest" -q</automated>
  </verify>
  <done>RedisCacheErrorHandler exists implementing CacheErrorHandler with all four methods swallowing + metering (GET/PUT WARN, EVICT/CLEAR ERROR); CacheConfig implements CachingConfigurer and returns it from errorHandler(); unit test green including the null-registry case.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Rate-limiter resilience — bounded command timeout + fail-open-with-alarm</name>
  <files>core-java/src/main/java/uk/jtoye/core/config/RateLimitConfig.java, core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java, core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorFailOpenTest.java</files>
  <behavior>
    - RateLimitInterceptor.preHandle, when the Redis-backed bucket build/consume throws ANY exception: logs at WARN ("rate limiter degraded — failing open"), increments counter jtoye.ratelimit.fail_open, and RETURNS true (request proceeds) — no 500, no rethrow.
    - The happy path is unchanged: consumed -> true + X-RateLimit-* headers; not consumed -> 429 + Retry-After + JSON body -> false.
    - The existing unconfigured fail-open (proxyManager == null) path is preserved and returns true.
  </behavior>
  <action>In `RateLimitConfig`, inject the existing per-profile command timeout with `@Value("${spring.data.redis.timeout:2000ms}") private Duration redisCommandTimeout;` (Spring Boot binds the `2000ms` style string to `java.time.Duration`). Apply it to the hand-rolled client by calling `.withTimeout(redisCommandTimeout)` on the `RedisURI.builder()` chain (belt-and-braces: may also call `redisClient.setDefaultTimeout(redisCommandTimeout)` after `RedisClient.create`). Do NOT hardcode a literal duration. Update the method Javadoc to note the explicit command timeout replaces Lettuce's 60s default (issue #86). In `RateLimitInterceptor`, add a null-safe `ObjectProvider<MeterRegistry>`-based `Counter` field `jtoye.ratelimit.fail_open` (constructor-inject the provider, mirroring the codebase pattern; keep the `@Autowired(required=false) ProxyManager` field). Wrap ONLY the Redis-touching section — `proxyManager.builder().build(rateLimitKey, configSupplier)` through `bucket.tryConsumeAndReturnRemaining(1)` and the subsequent probe handling — in a try/catch(Exception). In catch: log WARN with the tenant + path + exception, increment the fail_open counter, and return true (fail open). Leave the unconfigured short-circuit (`!rateLimitingEnabled || proxyManager == null -> true`), excluded-path skip, and no-tenant WARN skip exactly as they are. Write `RateLimitInterceptorFailOpenTest` as a plain JUnit 5 unit test (NO context/testcontainers): construct the interceptor with a SimpleMeterRegistry, inject a mock `ProxyManager` whose `builder().build(...).tryConsumeAndReturnRemaining(anyLong())` throws a RuntimeException (or whose `build` throws), set `rateLimitingEnabled=true` and a TenantContext tenant, call preHandle against a MockHttpServletRequest for `/api/v1/products` + MockHttpServletResponse, and assert it returns true, does not throw, and the jtoye.ratelimit.fail_open counter incremented by 1. Reset TenantContext in a finally/@AfterEach.</action>
  <verify>
    <automated>cd core-java && ./gradlew test --tests "uk.jtoye.core.security.RateLimitInterceptorFailOpenTest" -q</automated>
  </verify>
  <done>RateLimitConfig sets an explicit Lettuce command timeout from spring.data.redis.timeout (no literal); RateLimitInterceptor fails open on any Redis error with WARN + jtoye.ratelimit.fail_open counter and returns true; happy-path 429/200 logic unchanged; unit test green.</done>
</task>

<task type="auto">
  <name>Task 3: Fault-injection integration test (Redis unavailable) + docs regen + full-suite verification</name>
  <files>core-java/src/test/java/uk/jtoye/core/resilience/RedisFaultInjectionIntegrationTest.java, docs/metrics.json</files>
  <action>Create `RedisFaultInjectionIntegrationTest` in a new `resilience` test package, annotated `@SpringBootTest @Testcontainers @Tag("testcontainers")`. It must run cache + rate-limiting ACTIVE, so do NOT use `@ActiveProfiles("test")` (that profile disables CacheConfig via `@Profile("!test")` and disables rate limiting). Use a dedicated non-"test" profile via `@ActiveProfiles("redisfault")` (no yml file required — supply everything through @DynamicPropertySource) so CacheConfig loads. Declare two `@Container`s: a `PostgreSQLContainer<>("postgres:15")` and a `GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)` (mirror the redis pattern in RateLimitIntegrationTest.java.disabled). In `@DynamicPropertySource`: wire the Postgres datasource url/username/password + driver `org.postgresql.Driver` + PostgreSQLDialect + `spring.jpa.hibernate.ddl-auto=none` + `spring.flyway.enabled=true` (mirror IntegrationTestSupport.registerPostgresTestProperties, but do NOT set rate-limiting.enabled=false); wire `spring.data.redis.host`/`spring.data.redis.port` to the Redis container and set a short `spring.data.redis.timeout=1500ms` for fast failure; set `rate-limiting.enabled=true`; and because the "test" profile is not active, exclude RabbitMQ so the context boots brokerless: `spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration`, `spring.rabbitmq.host=localhost`, `spring.rabbitmq.port=0`, `spring.rabbitmq.listener.simple.auto-startup=false`. Autowire the `@Cacheable` service (ShopService.getShopById or ProductService.getProductById — pick whichever has the simplest seed path) and the `RateLimitInterceptor`. This test proves resilience, not tenant isolation, so keep the Testcontainers SUPERUSER role (no NOSUPERUSER downgrade needed) and set TenantContext directly for seeding/reads. Test flow: (1) set TenantContext to a fixed tenant; seed the minimal row(s) the cached read needs via the repositories/service; (2) call the @Cacheable read once to WARM the Redis cache and assert it returns the entity; (3) `redis.stop()`; (4) ASSERT A — within `assertTimeoutPreemptively(Duration.ofSeconds(10), ...)`, the same @Cacheable read still returns the entity and does NOT throw (CacheErrorHandler degraded the Redis GET to source-of-truth); (5) ASSERT B — build a MockHttpServletRequest for `/api/v1/products` (non-excluded) + MockHttpServletResponse, and within `assertTimeoutPreemptively(Duration.ofSeconds(10), ...)` assert `rateLimitInterceptor.preHandle(req,res,handler)` returns true and does not throw (bounded fail-open, no 60s hang, no 500). Clear TenantContext in @AfterEach. After the test file compiles and passes, regenerate the metrics manifest with `scripts/docs-freshness.sh --write` (adds the new @Test methods) and confirm the gate passes — do not hand-pin numbers. Finally run the full unit + integration suites.</action>
  <verify>
    <automated>cd core-java && ./gradlew integrationTest --tests "uk.jtoye.core.resilience.RedisFaultInjectionIntegrationTest" -q && cd /home/sanmi/IdeaProjects/JToye_OaaS_2026 && scripts/docs-freshness.sh && cd core-java && ./gradlew test integrationTest -q</automated>
  </verify>
  <done>RedisFaultInjectionIntegrationTest (@Tag("testcontainers")) stops Redis mid-test and proves (a) the @Cacheable read still returns from source-of-truth without throwing and (b) preHandle returns true within a bounded time (no hang, no 500). docs/metrics.json regenerated and docs-freshness gate passes. Full :core-java:test and :core-java:integrationTest are green.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| app -> Redis | Cache reads/writes and rate-limit bucket state cross to a network dependency that can blip or go down |
| client -> API (rate limiter) | Untrusted request volume is throttled per tenant using Redis-backed buckets |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-tsl-01 | Denial of Service | @Cacheable reads when Redis is down (current 500s / 60s hangs) | mitigate | CacheErrorHandler degrades GET/PUT/EVICT/CLEAR errors to log-and-continue -> reads fall back to source-of-truth; no 500, no hang (Task 1) |
| T-tsl-02 | Denial of Service | RateLimitInterceptor Redis call with no timeout/try-catch | mitigate | Explicit Lettuce command timeout (from spring.data.redis.timeout) + try/catch fail-open with WARN + jtoye.ratelimit.fail_open counter (Task 2) |
| T-tsl-03 | Denial of Service (rate-limit bypass window) | Rate limiter fails OPEN during a Redis outage — throttling is temporarily disabled | accept | Deliberate availability-over-enforcement trade-off for the outage window; ALARMED via jtoye.ratelimit.fail_open metric so operators can alert. Failing closed (blanket 503) would turn a Redis blip into the very outage this issue closes. Documented in code + SUMMARY. |
| T-tsl-04 | Tampering (stale cache) | EVICT/CLEAR error swallowed -> a stale cache entry may survive a write | accept | Swallowed but logged at ERROR + metered (jtoye.cache.errors{operation=evict/clear}); TTLs (products 10m, shops 15m) bound the staleness window. Rethrowing would re-introduce the 500 on the write path. |
| T-tsl-SC | Tampering | npm/pip/cargo installs | n/a | No new packages installed — all deps (Micrometer, Lettuce, Bucket4j, Testcontainers) already present; redis:7-alpine is a pinned Docker image pulled by Testcontainers, not a package-manager install. No legitimacy gate required. |
</threat_model>

<verification>
- Redis-down cached read: `@Cacheable` path returns the entity from source-of-truth, no exception, no HTTP 500 (RedisFaultInjectionIntegrationTest ASSERT A).
- Rate-limit path under Redis-down: `preHandle` returns true within a bounded time (assertTimeoutPreemptively 10s), no hang, no 500, and jtoye.ratelimit.fail_open increments (Task 2 unit + integration ASSERT B).
- CacheErrorHandler swallow semantics verified per-method (GET/PUT WARN, EVICT/CLEAR ERROR) with metrics (RedisCacheErrorHandlerTest).
- Config: command timeout sourced from spring.data.redis.timeout per profile — no hardcoded literal.
- docs-freshness gate green after `scripts/docs-freshness.sh --write` (numbers regenerated, not pinned — #85 is landing in parallel).
- Full `:core-java:test` and `:core-java:integrationTest` pass.
</verification>

<success_criteria>
- With Redis down, cached endpoints serve from source-of-truth (no 500) — AC1.
- The rate-limit path has a bounded timeout and does not hang requests — AC2.
- Behaviour is covered by a fault-injection test with Redis unavailable — AC3.
- No schema change (no Flyway migration); multi-tenancy (RLS + TenantContext) untouched.
- All new code has tests; both Gradle suites green; docs/metrics.json in sync.
</success_criteria>

<output>
Create `.planning/quick/260708-tsl-implement-issue-86-p1-4-redis-resilience/260708-tsl-SUMMARY.md` when done.
</output>
