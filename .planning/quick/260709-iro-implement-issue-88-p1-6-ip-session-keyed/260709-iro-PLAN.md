---
phase: quick-260709-iro
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - core-java/src/main/java/uk/jtoye/core/security/ClientIpResolver.java
  - core-java/src/test/java/uk/jtoye/core/security/ClientIpResolverTest.java
  - core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java
  - core-java/src/main/resources/application.yml
  - core-java/src/main/resources/application-prod.yml
  - core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorTest.java
  - core-java/src/test/java/uk/jtoye/core/security/PublicRateLimitIntegrationTest.java
  - docs/metrics.json
autonomous: true
requirements: [ISSUE-88-P1-6]
user_setup: []

must_haves:
  truths:
    - "A tenant-less request to /public/** is rate-limited by client IP at the Core layer (not silently allowed)."
    - "When a public IP exceeds its bucket, Core returns HTTP 429 with a Retry-After header."
    - "The public IP bucket is independent of tenant buckets — a public flood never consumes a tenant's tokens and vice-versa."
    - "A Redis outage still fails OPEN for public paths (no 500, bounded time) — issue #86 semantics preserved."
    - "Public limit/burst/window come from application*.yml keys with env override — no hardcoded literals."
  artifacts:
    - path: "core-java/src/main/java/uk/jtoye/core/security/ClientIpResolver.java"
      provides: "X-Forwarded-For-first client-IP extraction with getRemoteAddr fallback"
      contains: "X-Forwarded-For"
    - path: "core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java"
      provides: "IP-keyed public bucket branch (namespace rl:public:) for tenant-less /public paths"
      contains: "rl:public:"
    - path: "core-java/src/test/java/uk/jtoye/core/security/PublicRateLimitIntegrationTest.java"
      provides: "Testcontainers real-Redis proof: 429 on flood, tenant unaffected, fail-open on outage"
      contains: "testcontainers"
    - path: "core-java/src/main/resources/application.yml"
      provides: "rate-limiting.public.* config keys with env override"
      contains: "public:"
  key_links:
    - from: "RateLimitInterceptor.preHandle"
      to: "ClientIpResolver.resolveClientIp"
      via: "public-path branch keys bucket by client IP"
      pattern: "ClientIpResolver"
    - from: "RateLimitInterceptor public branch"
      to: "proxyManager Redis bucket"
      via: "rl:public:{ip} key inside the #86 fail-open try/catch"
      pattern: "rl:public:"
---

<objective>
Close issue #88 [P1-6]: public storefront endpoints are unthrottled at Core because
`RateLimitInterceptor` early-returns `true` whenever `TenantContext` is empty — which is
the state for every tenant-less `/public/**` guest request (orders, tracking, reviews,
browsing). Frontend calls Core directly, bypassing edge-go's coarse per-replica valve, so
guest-order/review spam and tracking-lookup abuse are inadequately bounded.

Fix: add an IP-keyed limiter bucket for tenant-less public paths, independent of the tenant
bucket, namespaced in Redis to avoid collision, and preserving the issue #86 fail-open-with-
alarm semantics (a Redis blip must NOT turn a public request into a 500).

Purpose: bound guest abuse at the Core layer regardless of edge routing.
Output: `ClientIpResolver` helper, a public-path branch in `RateLimitInterceptor`,
`rate-limiting.public.*` config keys, unit tests, and a Testcontainers integration test.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@./CLAUDE.md

<verified_findings>
Verified against the CURRENT (post-#86, post-#87) tree — do NOT re-derive, use these:

1. PUBLIC PATH PREFIX = `/public/**`.
   - `PublicStorefrontController` (uk.jtoye.core.storefront) is annotated `@RequestMapping("/public")`.
   - `WebConfig.configurePathMatch` prefixes `/api/v1` ONLY to packages shop/product/order/
     customer/finance/gdpr/sync. `storefront` is NOT in that list → public routes stay `/public/**`.
   - Endpoints under it that need bounding: GET /public/shops, GET/POST /public/shops/{slug}/orders,
     GET /public/orders, GET /public/orders/{orderNumber}, GET/POST /public/shops/{slug}/reviews.

2. THE GAP: `RateLimitInterceptor.preHandle` (lines ~90-94) — when `TenantContext.get()` is
   empty it logs a WARN and `return true` (allows). Public guest requests are always tenant-less,
   so they bypass throttling. This is the specific early-return to replace for public paths.

3. `/public/**` ALREADY reaches the interceptor: `WebConfig.addInterceptors` registers it on
   `/**`, excluding only `/actuator/**`, `/health`, `/swagger-ui/**`, `/v3/api-docs/**`.
   `isExcludedPath` in the interceptor mirrors that list. `/public` is NOT excluded.
   => NO WebConfig change is required; the fix is entirely inside the interceptor + config.

4. #86 fail-open (MUST preserve): the Redis section (bucket build + tryConsume) is wrapped in
   try/catch. On ANY Redis error it increments `jtoye.ratelimit.fail_open` (a null-safe
   Micrometer Counter built in the constructor from `ObjectProvider<MeterRegistry>`) and returns
   `true`. The bounded timeout comes from `RateLimitConfig` (Lettuce command timeout from
   `spring.data.redis.timeout`). The new public branch MUST run inside the same try/catch shape.

5. EXISTING tenant config keys (mirror these): `rate-limiting.enabled` /
   `rate-limiting.default-limit` / `rate-limiting.burst-capacity`. Base `application.yml` uses
   env-override form (`${RATE_LIMIT_PER_MINUTE:100}`); `application-prod.yml` uses literals;
   `application-test.yml` sets `rate-limiting.enabled: false` (leave as-is).

6. Tenant bucket key prefix is `RATE_LIMIT_KEY_PREFIX = "rate_limit::"`. The public bucket MUST
   use a DISTINCT namespace: `rl:public:` — so public and tenant keyspaces never collide.

7. NO XFF handling exists anywhere in core-java today (grep confirmed) — `ClientIpResolver` is new.

8. NO stable guest session id exists — public storefront is stateless (unauthenticated permitAll,
   no session cookie). => key on IP (XFF-first). Document the spoofing caveat.

9. Test suite wiring (core-java/build.gradle.kts): `test` task `excludeTags("testcontainers")`;
   `integrationTest` task `includeTags("testcontainers")`. The new integration test MUST carry
   `@Tag("testcontainers")` to land in the integrationTest suite (and out of the unit suite).
   Bucket4j core+redis (8.10.1) and Micrometer are already dependencies — NO new packages.

10. docs baseline = 755 logical invocations (docs/metrics.json total_logical_invocations).
    Regenerate via `scripts/docs-freshness.sh --write`; do NOT pin an exact new number.
</verified_findings>

<interfaces>
From RateLimitInterceptor.java (current):
```java
private static final String RATE_LIMIT_KEY_PREFIX = "rate_limit::";
private final Counter failOpenCounter; // null-safe, "jtoye.ratelimit.fail_open"
public RateLimitInterceptor(ObjectProvider<MeterRegistry> meterRegistryProvider);
@Value("${rate-limiting.enabled:true}")   boolean rateLimitingEnabled;
@Value("${rate-limiting.default-limit:100}") int defaultLimit;
@Value("${rate-limiting.burst-capacity:20}") int burstCapacity;
public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler);
private BucketConfiguration createBucketConfiguration(UUID tenantId); // capacity=limit+burst, refill limit/1min
private boolean isExcludedPath(String path);
// Response headers already used: X-RateLimit-Limit/Remaining/Reset, Retry-After (HEADER_RETRY_AFTER)
```
The existing tenant flow (build key → tryConsumeAndReturnRemaining → 429 with Retry-After OR
allow with headers), wrapped in the #86 try/catch, is the template to reuse for the public branch.
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: ClientIpResolver (XFF-first) + unit test</name>
  <files>core-java/src/main/java/uk/jtoye/core/security/ClientIpResolver.java, core-java/src/test/java/uk/jtoye/core/security/ClientIpResolverTest.java</files>
  <behavior>
    - Single stable hop, no XFF: header absent → returns request.getRemoteAddr() (e.g. "198.51.100.9").
    - XFF present single value: "203.0.113.7" → returns "203.0.113.7".
    - XFF multiple hops: "203.0.113.7, 70.41.3.18, 150.172.238.178" → returns FIRST hop "203.0.113.7".
    - Whitespace tolerance: " 203.0.113.7 , ..." → trimmed "203.0.113.7".
    - Blank/empty XFF ("" or "   ") → falls back to getRemoteAddr().
    - getRemoteAddr also null (defensive) → returns non-null sentinel "unknown" (never returns null).
  </behavior>
  <action>Create a package-scoped static utility `ClientIpResolver` in uk.jtoye.core.security with
  `public static String resolveClientIp(HttpServletRequest request)`. Read the `X-Forwarded-For`
  header; if non-null and non-blank, split on comma, take element [0], trim, return it; otherwise
  fall back to `request.getRemoteAddr()`; if that is null, return the literal `"unknown"`. Never
  return null or empty. Javadoc MUST document the spoofing caveat: X-Forwarded-For is
  client-controllable unless a trusted proxy (edge-go / ingress) overwrites it — keying on the
  first hop is the pragmatic Core-layer choice for issue #88, and operators should ensure the
  ingress overwrites (not appends) XFF for true anti-abuse guarantees. Write `ClientIpResolverTest`
  (plain JUnit 5, MockHttpServletRequest, no Spring context) covering every case in <behavior>.
  No fenced code in the resolver beyond the method itself; keep it under ~30 lines.</action>
  <verify>
    <automated>./gradlew :core-java:test --tests 'uk.jtoye.core.security.ClientIpResolverTest'</automated>
  </verify>
  <done>ClientIpResolver exists with a static resolveClientIp; all ClientIpResolverTest cases pass; method never returns null.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Public IP-keyed bucket branch in RateLimitInterceptor + config keys + unit tests</name>
  <files>core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java, core-java/src/main/resources/application.yml, core-java/src/main/resources/application-prod.yml, core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorTest.java</files>
  <behavior>
    - Tenant-less GET /public/shops under the public limit → allowed (true), rate-limit headers set,
      bucket keyed by "rl:public:{ip}" (NOT "rate_limit::...").
    - Tenant-less /public flood past (publicRequests + publicBurst) → 429, Retry-After header set,
      preHandle returns false. Body is JSON "Too Many Requests" (no tenantId leaked for public).
    - Tenant-present /api/v1/products request → still uses the tenant bucket ("rate_limit::{tenant}"),
      UNAFFECTED by public-bucket state (independent keyspaces).
    - Tenant-less NON-public path (e.g. /api/customers) → preserves existing behavior: allowed (true),
      no bucket consumed (the pre-existing testNoTenantContext_RequestAllowed test MUST still pass).
    - Fail-open preserved: if the Redis section throws on a public request, increment
      jtoye.ratelimit.fail_open and return true (no 429, no 500).
  </behavior>
  <action>In `RateLimitInterceptor.preHandle`, after the excluded-path check and BEFORE the
  `TenantContext.get()` tenant logic, add: `if (isPublicPath(requestPath)) { return
  handlePublicRateLimit(request, response, requestPath); }`. Add `private boolean isPublicPath(String
  path)` returning `path.equals("/public") || path.startsWith("/public/")`. Add
  `handlePublicRateLimit(...)` that keys the bucket by `"rl:public:" + ClientIpResolver.resolveClientIp(request)`,
  builds a public BucketConfiguration (capacity = publicRequests + publicBurst; refill publicRequests
  per `Duration.ofSeconds(publicWindowSeconds)`), calls `tryConsumeAndReturnRemaining(1)`, and mirrors
  the existing tenant flow for headers / 429 + Retry-After — but WITH the SAME #86 try/catch wrapper:
  on any Exception increment `failOpenCounter` (null-safe) and return true. Do NOT set/require
  TenantContext for the public branch. Do NOT include tenantId in the public 429 body. Keep the tenant
  branch (and its tenant-less allow-with-warn for non-public paths) exactly as-is. Add
  `@Value("${rate-limiting.public.requests-per-minute:30}") int publicRequestsPerMinute;`,
  `@Value("${rate-limiting.public.burst:10}") int publicBurstCapacity;`,
  `@Value("${rate-limiting.public.window-seconds:60}") int publicWindowSeconds;` — reusing the existing
  `rate-limiting.enabled` gate (already checked at the top). In `application.yml` add under
  `rate-limiting:` a `public:` block with env-override form:
  `requests-per-minute: ${RATE_LIMIT_PUBLIC_PER_MINUTE:30}`,
  `burst: ${RATE_LIMIT_PUBLIC_BURST:10}`,
  `window-seconds: ${RATE_LIMIT_PUBLIC_WINDOW_SECONDS:60}`. In `application-prod.yml` add the same
  `public:` block with literals matching the prod style (30 / 10 / 60). Leave `application-test.yml`
  untouched (rate-limiting disabled there). NO hardcoded numeric literals in the interceptor — all
  from @Value. Add unit tests to `RateLimitInterceptorTest` for every <behavior> case using the same
  Mockito ProxyManager/RemoteBucketBuilder/ConsumptionProbe pattern already in that file; assert the
  public branch builds a key containing "rl:public:" (argThat on build(key,...)) and that a
  tenant-context request builds a "rate_limit::" key.</action>
  <verify>
    <automated>./gradlew :core-java:test --tests 'uk.jtoye.core.security.RateLimitInterceptorTest' --tests 'uk.jtoye.core.security.RateLimitInterceptorFailOpenTest'</automated>
  </verify>
  <done>preHandle routes tenant-less /public/** to an rl:public:{ip} bucket returning 429+Retry-After over limit; tenant and non-public paths unchanged; fail-open preserved; config keys present in application.yml + application-prod.yml with env override; all RateLimitInterceptorTest cases (old + new) green.</done>
</task>

<task type="auto">
  <name>Task 3: Testcontainers real-Redis integration test + docs regen + full gate</name>
  <files>core-java/src/test/java/uk/jtoye/core/security/PublicRateLimitIntegrationTest.java, docs/metrics.json</files>
  <action>Create `PublicRateLimitIntegrationTest` mirroring the harness of
  `RedisFaultInjectionIntegrationTest` (uk.jtoye.core.resilience): `@SpringBootTest @Testcontainers
  @ActiveProfiles("dev") @Tag("testcontainers")`; real `postgres:15` + `redis:7-alpine` containers;
  `@DynamicPropertySource` wiring datasource + `spring.data.redis.*` (short `timeout` e.g. 1500ms) +
  `rate-limiting.enabled=true` + `rate-limiting.public.requests-per-minute` (set a SMALL value e.g. 5)
  + `rate-limiting.public.burst` (e.g. 2) + brokerless RabbitMQ (dead port, listener auto-startup
  false); `@MockBean DatabaseConfigurationValidator` (dev profile fail-fasts on superuser
  Testcontainers role). Autowire `RateLimitInterceptor`. Drive it directly with
  MockHttpServletRequest/Response (the established pattern), NOT MockMvc. Three assertions:
  (A) FLOOD: no TenantContext, request URI "/public/shops", set header X-Forwarded-For "203.0.113.7";
  call preHandle in a loop; the first (publicRequests+publicBurst) calls return true, and a subsequent
  call returns false with response status 429 and a non-null "Retry-After" header. (B) TENANT
  UNAFFECTED: with the public bucket for that IP now exhausted, set TenantContext to a seeded tenant,
  request URI "/api/v1/products", assert preHandle returns true (tenant bucket is a separate keyspace).
  (C) FAIL-OPEN: `redis.stop()`, then a fresh tenant-less "/public/shops" preHandle inside
  `assertTimeoutPreemptively(Duration.ofSeconds(10), ...)` returns true with status 200 (no 429, no
  500) — proving #86 fail-open holds for the public path. Use a distinct XFF per assertion where needed
  so buckets don't cross-contaminate. Seed a tenant row + published shop the way
  RedisFaultInjectionIntegrationTest does if a tenant/shop is needed for (B). After the test is green,
  regenerate docs metrics: run `scripts/docs-freshness.sh --write` and commit the updated
  docs/metrics.json (baseline was 755; do not hardcode the new number — let the script compute it).</action>
  <verify>
    <automated>./gradlew :core-java:test :core-java:integrationTest && ./scripts/docs-freshness.sh</automated>
  </verify>
  <done>PublicRateLimitIntegrationTest (tagged testcontainers) proves 429+Retry-After on public flood, tenant request unaffected, and fail-open (no 500) when Redis is down; full :core-java:test + :core-java:integrationTest pass; docs-freshness gate clean (metrics regenerated, no drift).</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| guest client → Core `/public/**` | Unauthenticated, tenant-less input crosses here; only per-replica edge valve upstream (frontend calls Core directly) |
| Core → Redis (Bucket4j) | Distributed bucket state; subject to transient outage |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-88-01 | Denial of Service | tenant-less `/public/**` (guest orders/reviews/tracking) | mitigate | IP-keyed Bucket4j bucket (`rl:public:{ip}`, publicRequests+burst per window) replaces the tenant-less allow-through; 429 + Retry-After when exceeded (Task 2) |
| T-88-02 | Spoofing | client IP via `X-Forwarded-For` | accept (documented) | XFF is client-controllable; key on first hop as the pragmatic Core-layer choice; Javadoc documents that trusted edge/ingress MUST overwrite (not append) XFF for hard guarantees; fallback to `getRemoteAddr()` when XFF absent (Task 1) |
| T-88-03 | Tampering | public bucket vs tenant bucket keyspace collision | mitigate | Distinct Redis namespace `rl:public:` vs `rate_limit::`; public flood cannot consume a tenant's tokens and vice-versa; proven by integration assertion B (Task 2/3) |
| T-88-04 | Denial of Service | Redis outage turning public request into 500/hang | mitigate | Reuse #86 fail-open-with-alarm: public branch runs inside the same try/catch, increments `jtoye.ratelimit.fail_open`, returns true within the bounded Lettuce timeout; proven by integration assertion C (Task 3) |
| T-88-SC | Tampering | npm/pip/cargo installs | accept | No new dependencies — Bucket4j core+redis (8.10.1) and Micrometer already present; nothing to install |
</threat_model>

<verification>
- `./gradlew :core-java:test :core-java:integrationTest` — full unit + Testcontainers suites green
  (the new PublicRateLimitIntegrationTest lands in integrationTest via `@Tag("testcontainers")`).
- `./scripts/docs-freshness.sh` — docs-freshness gate clean after `--write` regen (no drift; baseline 755).
- Core-java-only change: no edge-go, no frontend, no schema/migration. If any frontend TypeScript were
  touched the gate would also require `npm run build` (tsc type-check) — NOT the case here.
- No new packages (no Package Legitimacy Gate needed).
</verification>

<success_criteria>
- Tenant-less `/public/**` requests are rate-limited by client IP at the Core layer; exceeding the
  limit returns HTTP 429 + Retry-After (issue #88 acceptance criteria, covered by a test).
- Public and tenant buckets are independent (namespaced keys); neither can exhaust the other.
- Redis outage still fails open for public paths (no 500), preserving #86 semantics.
- Public limit / burst / window are injected from `rate-limiting.public.*` application config with env
  override — no hardcoded literals.
- Full `:core-java:test` + `:core-java:integrationTest` pass; docs-freshness clean.
</success_criteria>

<output>
Create `.planning/quick/260709-iro-implement-issue-88-p1-6-ip-session-keyed/260709-iro-SUMMARY.md` when done.
</output>
