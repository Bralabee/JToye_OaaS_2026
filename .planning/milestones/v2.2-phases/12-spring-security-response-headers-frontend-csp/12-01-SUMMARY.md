---
phase: 12-spring-security-response-headers-frontend-csp
plan: 01
subsystem: security
tags: [spring-security, response-headers, hsts, profile-gating, asvs-14.4, testcontainers, mockmvc]

# Dependency graph
requires:
  - phase: 11-stomp-relay
    provides: SecurityFilterChain bean at SecurityConfig.java:49 — the in-place extension target
provides:
  - X-Frame-Options: DENY on every Spring Boot response (ASVS 14.4.1)
  - X-Content-Type-Options: nosniff on every Spring Boot response (ASVS 14.4.4)
  - Referrer-Policy: strict-origin-when-cross-origin on every response (ASVS 14.4.6)
  - Strict-Transport-Security emitted ONLY under prod profile (ASVS 14.4.2) — max-age=31536000; includeSubDomains; explicit .disable() in non-prod to prevent dev-traffic leak
  - Golden snapshot file + regression test catching any future header-DSL drift
affects: [12-02-frontend-csp, 13-api-gateway-headers, any phase touching SecurityConfig]

# Tech tracking
tech-stack:
  added: []  # No new libraries; only Spring Security 6 DSL + existing testcontainers + AssertJ
  patterns:
    - "Spring Security 6 lambda DSL for headers (http.headers(headers -> {...}))"
    - "Profile-gated HSTS via runtime env.getActiveProfiles().contains('prod') — single-bean pattern (RESEARCH.md §4.2 Pattern A)"
    - "Composite @ActiveProfiles({'prod', 'test'}) idiom to bootstrap prod-config contexts without Redis"
    - "Golden-file snapshot regression test via java.nio.file.Files.readString + AssertJ isEqualTo"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java (4 @Test methods)
    - core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersProdProfileTest.java (2 @Test methods)
    - core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersDevProfileTest.java (2 @Test methods)
    - core-java/src/test/resources/security-headers-snapshot.txt (golden snapshot)
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java (added imports, Environment param, .headers(...) DSL block)
    - core-java/build.gradle.kts (Rule 3 fix — added systemProperty("api.version", "1.45") to unblock testcontainers vs Docker Engine 29)

key-decisions:
  - Profile gate uses runtime env.getActiveProfiles() check inside the existing SecurityFilterChain bean (not @Profile on the bean itself) — preserves single-bean architecture
  - Explicit httpStrictTransportSecurity(hsts -> hsts.disable()) in non-prod branch — prevents Spring default HSTS from leaking onto dev HTTPS tooling
  - Snapshot file curated to 3 SEC-03 headers only; Cache-Control/Date/Content-Type/X-XSS-Protection excluded as noise
  - Composite @ActiveProfiles({"prod","test"}) and {"dev","test"} to opt out of CacheConfig @Profile("!test") without weakening the prod/dev literal-profile check in SecurityConfig
  - RabbitMQ kept in context (not excluded) because OrderEventPublisher has compile-time RabbitTemplate dependency; redirect to port 0 with listener auto-startup disabled matches src/test/resources/application-test.yml pattern

patterns-established:
  - "Profile-gated security header DSL: use runtime env check inside the single SecurityFilterChain bean rather than split into @Profile-annotated beans"
  - "MockMvc HSTS-under-prod requires .secure(true) on the request builder — without it, HstsHeaderWriter's secureRequestMatcher sees isSecure()==false and emits nothing, giving a false pass"
  - "Golden-file regression tests via src/test/resources/*.txt + java.nio.file.Files + AssertJ — lightweight alternative to jest-style .toMatchSnapshot()"
  - "@DynamicPropertySource to override both the test classpath application-test.yml defaults (H2) AND the compile-time application-prod.yml requirements (Redis password, issuer-uri) when running under composite profiles"

requirements-completed: [SEC-03]

# Metrics
duration: ~90min
completed: 2026-04-18
---

# Phase 12 Plan 01: Spring Security Response Headers Summary

**Profile-gated Spring Security 6 `.headers(...)` DSL emitting X-Frame-Options/X-Content-Type-Options/Referrer-Policy on every response and HSTS only under prod, backed by 8 MockMvc+Testcontainers integration tests and a committed golden-snapshot regression guard.**

## Performance

- **Duration:** ~90 min (build + test cycles dominated by Docker daemon API negotiation + two separate Spring Boot context bootstraps for prod/dev profile tests, each ~60s)
- **Started:** 2026-04-18T12:32Z (first gradle test execution)
- **Completed:** 2026-04-18T13:57Z
- **Tasks:** 4 (all PLAN tasks executed atomically)
- **Files created:** 4
- **Files modified:** 2 (SecurityConfig.java + build.gradle.kts)
- **Tests added:** 8 @Test methods across 3 new test classes (+~208 lines of test code)

## Accomplishments

- Spring Boot responses now carry ASVS 14.4.x browser security headers on every request, 200 and 401 alike
- HSTS is strictly profile-gated: only emitted under `prod` profile with `max-age=31536000; includeSubDomains`, explicitly disabled in all other profiles via the non-prod branch in `SecurityConfig.securityFilterChain`
- Eight integration tests (all testcontainer-gated) pin the header contract and catch regressions:
  - `SecurityHeadersIntegrationTest` (4 tests) — baseline headers on 200 + 401, HSTS absence default, and the golden-snapshot regression test
  - `SecurityHeadersProdProfileTest` (2 tests) — HSTS presence on `.secure(true)` + documented absence without `.secure(true)` (HstsHeaderWriter secureRequestMatcher pin)
  - `SecurityHeadersDevProfileTest` (2 tests) — HSTS absent under `dev` even on `.secure(true)`, proving the explicit `.disable()` branch is doing the work
- Golden snapshot file committed at `core-java/src/test/resources/security-headers-snapshot.txt`; any future `SecurityConfig.headers(...)` change that adds/removes/renames one of the three curated headers fails `headerSnapshotMatchesGolden`
- Rule 3 infrastructure fix: `core-java/build.gradle.kts` now sets `systemProperty("api.version", "1.45")` alongside the existing `environment("DOCKER_API_VERSION", "1.45")`, unblocking ALL testcontainer tests in the repo against Docker Engine 29's API-version floor of 1.40

## Task Commits

Each task was committed atomically on branch `feature/phase-12-security-headers-csp`:

1. **Task 12-01-01: RED integration test for Spring security headers** — `f428184` (test)
   — SecurityHeadersIntegrationTest.java with 3 @Test methods + build.gradle.kts Rule 3 Docker API fix
2. **Task 12-01-02: RED profile-gated HSTS tests** — `68e903b` (test)
   — SecurityHeadersProdProfileTest.java + SecurityHeadersDevProfileTest.java (4 @Test methods total)
3. **Task 12-01-03: .headers(...) DSL in SecurityConfig (GREEN)** — `953a25b` (feat)
   — SecurityConfig.java extended with 3 imports, Environment parameter, and the 13-line .headers(...) block
4. **Task 12-01-04: Golden-snapshot regression test** — `09149c6` (test)
   — security-headers-snapshot.txt (committed) + headerSnapshotMatchesGolden @Test method

_(All four tasks were TDD-cycle commits: tasks 1–2 written to fail against untouched SecurityConfig, task 3 turned them all green, task 4 pinned the behaviour for regression.)_

## Files Created/Modified

- `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java` — added 3 imports (java.util.Arrays, org.springframework.core.env.Environment, org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter), extended `securityFilterChain(...)` signature with `Environment env` param, inserted 13-line `http.headers(...)` DSL block between the existing fluent chain and the `addFilterBefore(...)` call
- `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java` — 4 @Test methods (shopsEndpointHasSecurityHeaders, headersPresentOn401, hstsAbsentByDefaultProfile, headerSnapshotMatchesGolden); uses Testcontainers PostgreSQL image with @ActiveProfiles("test")
- `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersProdProfileTest.java` — 2 @Test methods (hstsPresentInProdProfile, hstsPresentOnSecureRequestOnly); @ActiveProfiles({"prod","test"})
- `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersDevProfileTest.java` — 2 @Test methods (hstsAbsentInDevProfile, hstsAbsentEvenOverSecureInDevProfile); @ActiveProfiles({"dev","test"})
- `core-java/src/test/resources/security-headers-snapshot.txt` — 3 lines, sorted alphabetically, LF endings; the golden snapshot against which `headerSnapshotMatchesGolden` diffs
- `core-java/build.gradle.kts` — Rule 3 fix adding `systemProperty("api.version", "1.45")` under `tasks.test`

## Decisions Made

- **Runtime profile check over @Profile beans** — `env.getActiveProfiles().contains("prod")` inside the SecurityFilterChain bean rather than duplicating the bean into prod/non-prod variants (RESEARCH.md §4.2 Pattern A). Preserves one-bean invariant, avoids profile-split proliferation.
- **Explicit `hsts.disable()` in non-prod branch** — without this, Spring's default `HstsHeaderWriter` still emits HSTS on `.secure(true)` requests in dev, which would poison dev HTTPS tooling (verified by `SecurityHeadersDevProfileTest.hstsAbsentEvenOverSecureInDevProfile`).
- **Composite @ActiveProfiles({"prod", "test"})** — chose this over a dedicated prod-only profile scaffold because `CacheConfig` is annotated `@Profile("!test")` and requires a live `RedisConnectionFactory`. Adding `"test"` opts out of CacheConfig without weakening the prod-literal match in SecurityConfig's HSTS gate.
- **Snapshot curated to 3 headers** — excluded Cache-Control/Date/Content-Type/X-XSS-Protection as noise; those are default Spring Security headers outside the SEC-03 remit.
- **RabbitMQ kept in context, redirected to port 0** — excluding `RabbitAutoConfiguration` would unsatisfy `OrderEventPublisher`'s compile-time `RabbitTemplate` dependency. Redirecting the broker to `localhost:0` with `listener.simple.auto-startup=false` mirrors the pattern already used in `src/test/resources/application-test.yml` and is the minimal-surprise option.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking] Docker client API version mismatch blocked all testcontainer tests**
- **Found during:** Task 12-01-01 (first gradle test run)
- **Issue:** `core-java/build.gradle.kts:100` already set `environment("DOCKER_API_VERSION", "1.45")`, but this env var does not reach docker-java's `UnixSocketClientProviderStrategy` in the Gradle test worker JVM. Testcontainers 1.21.3's bundled docker-java client hardcodes API version 1.32, which Docker Engine 29.4.0 rejects with `Status 400: client version 1.32 is too old. Minimum supported API version is 1.40`. All testcontainer-tagged tests (including this plan's) were failing at `initializationError`.
- **Fix:** Added `systemProperty("api.version", "1.45")` in `tasks.test`. `DefaultDockerClientConfig.createDefaultConfigBuilder()` reads EITHER `DOCKER_API_VERSION` env var OR the `api.version` system property; setting both is belt-and-braces.
- **Files modified:** `core-java/build.gradle.kts`
- **Verification:** `./gradlew :core-java:test -PincludeIntegration --tests "*SecurityHeaders*"` now BUILD SUCCESSFUL in 2m 41s with all 8 tests passing; the pre-existing `ShopControllerIntegrationTest` also unblocked as a side-benefit.
- **Committed in:** `f428184` (Task 12-01-01 commit)

**2. [Rule 3 — Blocking] @ActiveProfiles("prod") and @ActiveProfiles("dev") failed to boot context**
- **Found during:** Task 12-01-02 (profile-gated test design)
- **Issue:** The plan scaffold called for `@ActiveProfiles("prod")` / `@ActiveProfiles("dev")` singletons. Under those profiles `CacheConfig` (annotated `@Profile("!test")`) activates and demands a `RedisConnectionFactory` bean, which neither profile supplies in a testcontainer-only context. Context startup failed with `NoSuchBeanDefinitionException: RedisConnectionFactory`.
- **Fix:** Composite `@ActiveProfiles({"prod", "test"})` and `@ActiveProfiles({"dev", "test"})`. The `"test"` component opts out of CacheConfig via the `!test` guard; the `"prod"` / `"dev"` component still makes `env.getActiveProfiles().contains("prod")` (or not) inside `SecurityConfig` correctly — the HSTS gate is literal-match on `"prod"`, unaffected by adding `"test"`.
- **Files modified:** `SecurityHeadersProdProfileTest.java`, `SecurityHeadersDevProfileTest.java`
- **Verification:** All 4 profile tests pass with composite profile; the SecurityConfig HSTS branch is provably exercised because `hstsAbsentEvenOverSecureInDevProfile` REQUIRES the explicit `hsts.disable()` branch to pass (Spring defaults would otherwise emit HSTS on `.secure(true)`).
- **Committed in:** `68e903b` (Task 12-01-02 commit)

**3. [Rule 3 — Blocking] RabbitMQ PLAIN auth failure crashed Spring context in dev/prod profiles**
- **Found during:** Task 12-01-02 (first profile test run)
- **Issue:** A running RabbitMQ instance at `localhost:5672` with non-default credentials was refusing `PLAIN` login from Spring's default `RabbitAutoConfiguration`. Context startup failed with `AmqpAuthenticationException: ACCESS_REFUSED`.
- **Fix:** Added @DynamicPropertySource overrides setting `spring.rabbitmq.host=localhost`, `spring.rabbitmq.port=0`, `spring.rabbitmq.listener.simple.auto-startup=false`. Pattern copied verbatim from `src/test/resources/application-test.yml`. RabbitAutoConfiguration is intentionally NOT excluded because `OrderEventPublisher` has a compile-time constructor dependency on `RabbitTemplate`.
- **Files modified:** `SecurityHeadersProdProfileTest.java`, `SecurityHeadersDevProfileTest.java`
- **Verification:** Context now boots cleanly under both composite profiles.
- **Committed in:** `68e903b` (Task 12-01-02 commit)

**4. [Rule 3 — Blocking] H2 driver selection conflicted with Testcontainers PostgreSQL URL**
- **Found during:** Task 12-01-01 (second gradle test run)
- **Issue:** `src/test/resources/application-test.yml` forces `spring.datasource.driver-class-name=org.h2.Driver` and disables Flyway, but our `@DynamicPropertySource` overrides the URL to `jdbc:postgresql://...`. Hikari's driver pre-selection then threw `Driver org.h2.Driver claims to not accept jdbcUrl`.
- **Fix:** @DynamicPropertySource also overrides `spring.datasource.driver-class-name=org.postgresql.Driver`, `spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect`, and `spring.flyway.enabled=true`. This unlocks Testcontainers PostgreSQL with real Flyway migrations — required because RLS policies and Postgres-specific types only exist on real Postgres.
- **Files modified:** `SecurityHeadersIntegrationTest.java` + copy-paste to both profile tests
- **Verification:** All 8 tests pass against Testcontainers PostgreSQL.
- **Committed in:** `f428184` (Task 12-01-01 commit) and `68e903b` (Task 12-01-02 commit)

---

**Total deviations:** 4 auto-fixed (all Rule 3 — blocking infrastructure issues that prevented plan verification)
**Impact on plan:** All four deviations are pre-existing infrastructure issues surfaced by this plan's fresh integration tests, not scope creep. Fix #1 (Docker API version) unblocks all testcontainer tests in the repo as a side-benefit; fixes #2-4 are test-scoped and scoped only to this plan's three new files. The SEC-03 mitigation itself is implemented exactly as RESEARCH.md §4.2 Pattern A specifies.

## Issues Encountered

- The plan assumed "dev tests may pass trivially (no HSTS exists anywhere today)"; in fact Spring Security's defaults DO emit HSTS on `.secure(true)` requests. The dev-profile test `hstsAbsentEvenOverSecureInDevProfile` was the true RED signal in Task 12-01-02, not the prod tests (which passed trivially against defaults). The plan's intent — pinning explicit `hsts.disable()` on the non-prod branch — is fully achieved, but the test-ordering commentary in the plan was slightly off. Noted for future TDD plans on Spring Security.
- No RabbitMQ or Redis instance was needed; the profile tests were able to boot the full Spring Boot context with just the Testcontainers PostgreSQL image by stubbing message-broker dependencies via @DynamicPropertySource.

## Self-Check

FOUND: core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java
FOUND: core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersProdProfileTest.java
FOUND: core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersDevProfileTest.java
FOUND: core-java/src/test/resources/security-headers-snapshot.txt
FOUND: core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java (modified)
FOUND: core-java/build.gradle.kts (modified)
FOUND: f428184 (Task 12-01-01)
FOUND: 68e903b (Task 12-01-02)
FOUND: 953a25b (Task 12-01-03)
FOUND: 09149c6 (Task 12-01-04)

## TDD Gate Compliance

RED gate commits (`test(...)`): `f428184` (Task 12-01-01) and `68e903b` (Task 12-01-02) — both recorded as failing tests in commit body
GREEN gate commit (`feat(...)`): `953a25b` (Task 12-01-03) — SecurityConfig.java implementation that turned all tests green
REFACTOR: N/A (no refactoring needed)
Regression guard (`test(...)`): `09149c6` (Task 12-01-04) — golden-snapshot + new @Test method pinning the contract

TDD cycle sequence: RED → RED → GREEN → pin — all gates present.

## User Setup Required

None — no external service configuration required. The change is internal to Spring Security's filter chain.

Optional (not gated): a dev verifier running the full stack locally via `docker compose up` can curl the API and visually confirm:
```
curl -I http://localhost:9090/api/v1/shops
# Expect: X-Frame-Options: DENY
# Expect: X-Content-Type-Options: nosniff
# Expect: Referrer-Policy: strict-origin-when-cross-origin
# Expect: NO Strict-Transport-Security header (dev profile)
```

## Must-Haves Verification

All 7 must-haves from the PLAN frontmatter verified:

1. ✓ GET /api/v1/shops 200 response includes X-Frame-Options: DENY
   → `shopsEndpointHasSecurityHeaders` PASSED
2. ✓ GET /api/v1/shops 200 response includes X-Content-Type-Options: nosniff
   → `shopsEndpointHasSecurityHeaders` PASSED
3. ✓ GET /api/v1/shops 200 response includes Referrer-Policy: strict-origin-when-cross-origin
   → `shopsEndpointHasSecurityHeaders` PASSED
4. ✓ Strict-Transport-Security present in prod profile (max-age=31536000; includeSubDomains)
   → `hstsPresentInProdProfile` PASSED
5. ✓ Strict-Transport-Security absent in dev profile even over .secure(true)
   → `hstsAbsentEvenOverSecureInDevProfile` PASSED
6. ✓ Headers present on 401 unauthenticated responses
   → `headersPresentOn401` PASSED
7. ✓ Java-side header snapshot committed; regression fails the test
   → `security-headers-snapshot.txt` committed; `headerSnapshotMatchesGolden` PASSED

All required artifacts from plan frontmatter present and meet min_lines constraints (SecurityConfig.java +25 lines, 3 test files ≥40 lines each, snapshot contains the three required lines).

## Next Plan Readiness

Phase 12 Plan 02 (Frontend CSP) can proceed — this plan is fully independent; no shared state carryover beyond the "Spring headers are already in place" context.

Note: Plan 12-02 should cross-reference this plan's Referrer-Policy choice (`strict-origin-when-cross-origin`) for consistency with the frontend Next.js header configuration.

---
*Phase: 12-spring-security-response-headers-frontend-csp*
*Completed: 2026-04-18*
