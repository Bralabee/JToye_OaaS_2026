---
phase: 13-guest-tracking-tenant-validation
plan: 01
subsystem: security
tags: [spring-security, multi-tenant, rls, access-denied, testcontainers, mockmvc, asvs-4.1, audit-log, stride]

# Dependency graph
requires:
  - phase: 12-spring-security-response-headers-frontend-csp
    provides: MockMvc + Testcontainers PostgreSQL scaffolding pattern (Phase 12 Deviation #4 — explicit driver-class-name override to unblock H2 defaults); RabbitMQ port=0 stub pattern (Deviation #3); composite @ActiveProfiles idiom.
provides:
  - TenantAccessDeniedException extending org.springframework.security.access.AccessDeniedException — maps to 403 ProblemDetail via existing GlobalExceptionHandler, zero handler changes
  - PublicStorefrontService.resolvePublicShopForSlug(slug) package-private helper consolidating 3 vulnerable call sites (getShopConfig, getShopProducts, createGuestOrder) into a single tenant-match-gated entry point
  - ReviewService.resolvePublicShopForSlug(slug) private helper — intentional duplicate per D-07 — with source=reviews log discriminator, applied to getShopReviews + createReview
  - Structured SLF4J WARN audit log at cross-tenant rejection sites (event=tenant_spoof_attempt slug={} slugTenant={} upstreamTenant={} outcome=403 [source=reviews])
  - CrossTenantSpoofIntegrationTest — 6 MockMvc+Testcontainers methods covering SC-1..SC-3 + STRIDE T-13-01/02/03 + audit log format pin
  - 4 new PublicStorefrontServiceTest.resolvePublicShopForSlug_* unit tests covering SC-4 (tenant-match, tenant-mismatch, missing-tenant, slug-unknown)
affects: [14-code-quality, 15-order-operations, 17-vendor-order-ops, any phase touching /public/shops/{slug}/** endpoints]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Service-layer tenant-match gate: load slug→shop, compare TenantContext.get() to shop.tenantId, throw TenantAccessDeniedException on mismatch BEFORE setting TenantContext"
    - "Package-private helper visibility to permit direct unit test access within the same package without reflection"
    - "Deliberate helper duplication across service boundaries (D-07) — consolidate only at 3+ consumers"
    - "Structured SLF4J key=value audit log at service rejection sites, parseable by Loki/ELK; source=reviews discriminator on the reviews path"
    - "ASVS V4.1.5 — generic 403 response body; tenant UUIDs only in the log, never in the exception message"
    - "Testcontainers idempotent-seed helper pattern: check findBySlugAndPublishedTrue before INSERT to survive repeated @BeforeEach without @Transactional rollback"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/exception/TenantAccessDeniedException.java (17 lines)
    - core-java/src/test/java/uk/jtoye/core/security/CrossTenantSpoofIntegrationTest.java (187 lines, 6 @Test methods)
    - .planning/phases/13-guest-tracking-tenant-validation/deferred-items.md (pre-existing RabbitMQ failures logged)
  modified:
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java (+53 lines / -12; 1 import, 1 helper, 3 call-site refactors)
    - core-java/src/main/java/uk/jtoye/core/review/ReviewService.java (+40 lines / -10; 2 imports, 1 helper, 2 call-site refactors, getShopReviews gains finally-clear)
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java (+81 lines; @AfterEach + 4 new resolvePublicShopForSlug_* tests)
    - core-java/src/test/java/uk/jtoye/core/review/ReviewServiceTest.java (+14 lines; Rule-1 fix — @AfterEach TenantContext.clear)
    - .planning/phases/13-guest-tracking-tenant-validation/13-VALIDATION.md (flipped nyquist_compliant: false → true)

key-decisions:
  - "Service-layer gate over a new filter (RESEARCH.md D-01) — only the service has DB access to resolve slug→tenant; a filter would need circular DI to ShopRepository"
  - "TenantAccessDeniedException extends Spring's AccessDeniedException so GlobalExceptionHandler.handleAccessDenied maps it to 403 with zero handler changes (D-02)"
  - "Structured SLF4J WARN via existing loggers — not a new AuditService call, not a new Flyway migration, not a RabbitMQ event (D-03)"
  - "Generic 403 response body per ASVS V4.1.5 — tenant UUIDs NEVER leaked to client; they live only in the SLF4J log line (D-04)"
  - "Duplicate the helper in ReviewService rather than share a utility — 2 services × 2 call sites doesn't justify cross-service coupling (D-07); source=reviews log tag distinguishes review-layer spoof attempts in Loki"
  - "Helper MUST NOT call TenantContext.clear() — callers own cleanup in their finally blocks per D-09; the helper only SETS on success"
  - "Package-private (not private) helper visibility to permit direct unit test access within uk.jtoye.core.storefront — tested by 4 unit tests without reflection"

patterns-established:
  - "Guest-path tenant validation: resolve shop → read upstream TenantContext → compare → reject with 403+log or proceed with TenantContext.set → caller finally-clears"
  - "Idempotent integration-test shop seeding survives per-method @BeforeEach without @Transactional rollback — short-circuit when slug already exists"
  - "OutputCaptureExtension audit-log pins — Phase 12 + Phase 13 both rely on this for ASVS 14.4.x / V4.1.5 assertions"

requirements-completed: [SEC-01]

# Metrics
duration: ~45min
completed: 2026-04-18
---

# Phase 13 Plan 01: Guest Tracking Tenant Validation Summary

**Application-layer SEC-01 tenant-match gate on every `/public/shops/{slug}/**` entry point — rejects cross-tenant JWT with 403 ProblemDetail + structured audit log BEFORE any RLS-scoped read or write, backed by 6 MockMvc+Testcontainers integration tests and 4 unit tests pinning STRIDE T-13-01/02/03 mitigations.**

## Performance

- **Duration:** ~45 min (dominated by 4× full-context Spring Boot boots against Testcontainers Postgres, ~45s each, plus the 4-minute full regression sweep that surfaced the pre-existing RabbitMQ failures)
- **Started:** 2026-04-18T22:50Z (first gradle test execution, Task 13-01-01)
- **Completed:** 2026-04-18T23:13Z
- **Tasks:** 5 (all PLAN tasks executed atomically; TDD sequence RED→RED→GREEN→GREEN→pin)
- **Files created:** 3 (TenantAccessDeniedException, CrossTenantSpoofIntegrationTest, deferred-items.md)
- **Files modified:** 5 (PublicStorefrontService, ReviewService, PublicStorefrontServiceTest, ReviewServiceTest, 13-VALIDATION.md)
- **Tests added:** 6 integration @Test methods + 4 unit @Test methods + 1 @AfterEach auto-fix in ReviewServiceTest

## Accomplishments

- SEC-01 vulnerability closed on all 5 application-layer call sites that silently overwrote TenantContext from a path slug:
  - `PublicStorefrontService.getShopConfig` (was line 102)
  - `PublicStorefrontService.getShopProducts` (was line 214)
  - `PublicStorefrontService.createGuestOrder` (was line 326 — the highest-severity WRITE path)
  - `ReviewService.getShopReviews` (was line 38 — defense-in-depth on the read path, derived from slug)
  - `ReviewService.createReview` (was line 56 — WRITE path; reviews mint tenant-scoped rows)
- Audit log contract pinned: `event=tenant_spoof_attempt slug={} slugTenant={} upstreamTenant={} outcome=403 [source=reviews]` emitted at every rejection, parseable by Loki/ELK, alertable via Alertmanager (Phase 9 infrastructure).
- ASVS V4.1.5 compliance: the 403 response body is the generic "Access denied" string (from the existing `GlobalExceptionHandler.handleAccessDenied`). Tenant UUIDs appear ONLY in the SLF4J WARN log — never in the HTTP response or the exception message. Pinned by unit test assertions.
- 6 MockMvc+Testcontainers integration tests exercise real PostgreSQL RLS (Phase 12 Deviation #4 pattern — explicit `org.postgresql.Driver` override overriding the H2 default from `application-test.yml`). H2 would not enforce RLS and would pass tests vacuously.
- 4 unit tests exercise the `resolvePublicShopForSlug` helper directly (package-private visibility — same package) with the full SC-4 matrix: tenant-match, tenant-mismatch, missing-tenant (null upstream), slug-unknown.

## Task Commits

Each task was committed atomically on `feature/phase-13-guest-tracking-tenant-validation`:

1. **Task 13-01-01: RED — TenantAccessDeniedException + CrossTenantSpoofIntegrationTest scaffold** — `1f0b9aa` (test)
   — TenantAccessDeniedException.java created; CrossTenantSpoofIntegrationTest.java created with one failing-now @Test (`crossTenantJwtReturns403OnProducts`); RED confirmed via `Status expected:<403> but was:<200>`.
2. **Task 13-01-02: RED — 4 unit tests for resolvePublicShopForSlug helper** — `1e7f357` (test)
   — PublicStorefrontServiceTest.java extended with @AfterEach + 4 new @Test methods; RED confirmed via `error: cannot find symbol: method resolvePublicShopForSlug(String)` × 4.
3. **Task 13-01-03: GREEN — resolvePublicShopForSlug helper + 3 call-site refactors** — `e978939` (feat)
   — PublicStorefrontService.java gets the helper; 3 vulnerable sites collapse from `findBy+TenantContext.set` pairs to `Shop shop = resolvePublicShopForSlug(slug)`; 5 tests turn green.
4. **Task 13-01-04: GREEN — ReviewService tenant-match gate** — `9c5309b` (feat)
   — ReviewService.java gets its own helper (intentional duplicate, D-07); CrossTenantSpoofIntegrationTest gets `crossTenantJwtReturns403OnReviews`; idempotent-seed helper added to survive per-method @BeforeEach.
5. **Task 13-01-05: regression + audit-log pin + VALIDATION.md** — `300cae2` (test)
   — 4 new @Test methods on CrossTenantSpoofIntegrationTest (sameTenantJwtSucceeds / anonymousGuestSucceeds / crossTenantRequestLogsSpoofEvent / crossTenantJwtReturns403OnCreateOrder); Rule-1 fix to ReviewServiceTest (@AfterEach clear); VALIDATION.md flipped to `nyquist_compliant: true`; deferred-items.md logs pre-existing RabbitMQ failures.

_All five commits are TDD-sequence commits: RED → RED → GREEN → GREEN → pin._

## Files Created/Modified

- `core-java/src/main/java/uk/jtoye/core/exception/TenantAccessDeniedException.java` — new typed exception extending `org.springframework.security.access.AccessDeniedException` (17 lines)
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java` — added `resolvePublicShopForSlug` helper; refactored `getShopConfig`, `getShopProducts`, `createGuestOrder` to delegate
- `core-java/src/main/java/uk/jtoye/core/review/ReviewService.java` — added `resolvePublicShopForSlug` helper (duplicate of storefront's with `source=reviews` log tag); refactored `getShopReviews` + `createReview`; `getShopReviews` gains a finally-clear block
- `core-java/src/test/java/uk/jtoye/core/security/CrossTenantSpoofIntegrationTest.java` — new; 6 @Test methods; PostgreSQLContainer + @DynamicPropertySource overrides; idempotent seed helper
- `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java` — added @AfterEach + 4 new `resolvePublicShopForSlug_*` @Test methods
- `core-java/src/test/java/uk/jtoye/core/review/ReviewServiceTest.java` — added @AfterEach TenantContext.clear (Rule-1 fix)
- `.planning/phases/13-guest-tracking-tenant-validation/13-VALIDATION.md` — flipped to `nyquist_compliant: true`; all 5 Per-Task rows marked PASS
- `.planning/phases/13-guest-tracking-tenant-validation/deferred-items.md` — new; documents pre-existing RabbitMQ PLAIN auth failures across ~40 unrelated integration tests

## Decisions Made

All 9 locked decisions from PLAN.md `<locked_decisions>` were followed exactly:

- **D-01** Service-layer gate, not a new filter — no DB access in filters.
- **D-02** TenantAccessDeniedException extends AccessDeniedException — existing handler maps to 403.
- **D-03** Structured SLF4J WARN via existing loggers — no new AuditService / migration / RabbitMQ event.
- **D-04** 403 body is generic — no tenant UUIDs leaked to client.
- **D-05** Upstream tenant re-derived from TenantContext.get() per request — no Redis cache, no new abstraction.
- **D-06** Integration tests use PostgreSQLContainer(`postgres:15`), NOT H2 — Phase 12 Deviation #4.
- **D-07** ReviewService IS in scope (Assumption A2 confirmed by planner-side grep); helper duplicated across services with `source=reviews` log tag discriminator.
- **D-08** Log format: `event=tenant_spoof_attempt slug={} slugTenant={} upstreamTenant={} outcome=403 [source=reviews]`.
- **D-09** Helper only SETS TenantContext on success; never clears. Callers own cleanup in finally blocks.

One tactical decision not in the plan:

- **Package-private helper visibility** in `PublicStorefrontService` (the plan said "private OR package-private"; chose package-private so the 4 new unit tests can invoke it directly without reflection).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking] Testcontainers seeding failed: null created_at on tenants table**

- **Found during:** Task 13-01-01 (first gradle test run)
- **Issue:** The MultiTenantIsolationIntegrationTest pattern `INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING` failed with `null value in column "created_at" of relation "tenants" violates not-null constraint`. The schema has `created_at timestamptz NOT NULL DEFAULT now()`, but the DEFAULT did not apply in the Testcontainers image run here (possibly schema evolution or a Flyway migration idiosyncrasy).
- **Fix:** Made `created_at` explicit in the seed INSERTs: `INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING`.
- **Files modified:** `CrossTenantSpoofIntegrationTest.java`
- **Verification:** Seed succeeds; test proceeds to the actual 403/200 assertion.
- **Committed in:** `1f0b9aa` (Task 13-01-01 commit, as part of scaffolding the integration test).

**2. [Rule 3 — Blocking] Slug collision between TENANT_A and TENANT_B**

- **Found during:** Task 13-01-01 (second gradle test run after fix #1)
- **Issue:** `slug = "shop-" + tenantId.toString().substring(0, 8)` collided for TENANT_A (`00000000-0000-0000-0000-000000000001`) and TENANT_B (`-000000000002`) — both UUIDs share the same first 8 hex chars (`00000000`). `shops_slug_key` UNIQUE violation on Shop B save.
- **Fix:** Derive slug from the LAST 8 hex chars (strip dashes, take `substring(length-8, length)`) — yields `shop-00000001` vs `shop-00000002`, distinct.
- **Files modified:** `CrossTenantSpoofIntegrationTest.java`
- **Verification:** Both shops save; test proceeds.
- **Committed in:** `1f0b9aa` (Task 13-01-01 commit).

**3. [Rule 3 — Blocking] Idempotent shop seed needed to survive repeated @BeforeEach**

- **Found during:** Task 13-01-04 (second @Test method added → setUp re-runs → duplicate-slug UNIQUE violation on the already-persisted shop)
- **Issue:** `@BeforeEach` runs per test method and the test class is NOT `@Transactional`, so shops from the previous test persist. The second method's `@BeforeEach` then tried to INSERT a second row with the same deterministic slug → UNIQUE violation.
- **Fix:** `createPublishedShop` now short-circuits if `shopRepository.findBySlugAndPublishedTrue(slug)` already returns present.
- **Files modified:** `CrossTenantSpoofIntegrationTest.java`
- **Verification:** All 6 @Test methods in the class pass together on repeated `@BeforeEach` runs.
- **Committed in:** `9c5309b` (Task 13-01-04 commit).

**4. [Rule 1 — Bug] ReviewService change broke 4 of 8 existing ReviewServiceTest methods (ThreadLocal leak)**

- **Found during:** Task 13-01-05 (full regression gate)
- **Issue:** After Task 13-01-04, `ReviewService.getShopReviews` and `createReview` now set TenantContext via `resolvePublicShopForSlug`. The existing `ReviewServiceTest` has no `@AfterEach` cleanup. Each `@BeforeEach` creates a shop with a fresh random tenantId, so ThreadLocal state leaked from test N triggered the tenant-match gate in test N+1, throwing `TenantAccessDeniedException` instead of the expected `IllegalArgumentException`.
- **Fix:** Added `@AfterEach tearDown() → TenantContext.clear()` to `ReviewServiceTest`. Documented the rationale in the comment.
- **Files modified:** `core-java/src/test/java/uk/jtoye/core/review/ReviewServiceTest.java` (+14 lines)
- **Verification:** All 8 ReviewServiceTest @Test methods pass green.
- **Committed in:** `300cae2` (Task 13-01-05 commit — bundled with regression pin for atomicity).

**5. [Rule 1 — Bug] `crossTenantJwtReturns403OnCreateOrder` returned 400 not 403 due to @Valid**

- **Found during:** Task 13-01-05 (first run of the new `crossTenantJwtReturns403OnCreateOrder` method)
- **Issue:** The plan scaffold used `"items":[]` in the POST body. `GuestOrderRequest.items` has `@NotEmpty` (validated by `@Valid` on the controller BEFORE the service is invoked), so the request 400-ed on bean validation and never reached the tenant-match gate. The test failed with `Status expected:<403> but was:<400>`.
- **Fix:** Bumped the JSON body to include one valid item `{"productId":"<random-uuid>","quantity":1}`. Under a same-tenant call this would eventually 404 on product lookup, but the gate short-circuits to 403 first.
- **Files modified:** `CrossTenantSpoofIntegrationTest.java`
- **Verification:** `crossTenantJwtReturns403OnCreateOrder` passes green; 403 returned before any Order row could be minted.
- **Committed in:** `300cae2` (Task 13-01-05 commit).

---

**Total deviations:** 5 auto-fixed (3× Rule 3 blocking, 2× Rule 1 bug).
**Impact on plan:** All five deviations are test-scaffold corrections or consequences of the service-layer change; zero scope creep. The SEC-01 implementation itself is exactly what PLAN.md `<locked_decisions>` specified. The D-09 "caller owns cleanup" contract combined with an existing test that lacked cleanup (ReviewServiceTest) produced deviation #4 — a regression in Phase 13's own change footprint, handled in the same task that surfaced it.

## Issues Encountered

- Pre-existing RabbitMQ PLAIN authentication failures cascade through ~40 unrelated integration tests when `./gradlew :core-java:test -PincludeIntegration` runs. Verified reproduced on the pre-Phase-13 tree (`git stash` at HEAD=`9c5309b`) — same root cause as Phase 12 Deviation #3 (local RabbitMQ broker on `localhost:5672` with non-default credentials rejects PLAIN). Out of Phase 13 scope; tracked in `.planning/phases/13-guest-tracking-tenant-validation/deferred-items.md`. The Phase 13 delta is fully green when the scope is narrowed to the impacted test classes.

## Self-Check

FOUND: core-java/src/main/java/uk/jtoye/core/exception/TenantAccessDeniedException.java
FOUND: core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java (modified)
FOUND: core-java/src/main/java/uk/jtoye/core/review/ReviewService.java (modified)
FOUND: core-java/src/test/java/uk/jtoye/core/security/CrossTenantSpoofIntegrationTest.java
FOUND: core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java (modified)
FOUND: core-java/src/test/java/uk/jtoye/core/review/ReviewServiceTest.java (modified)
FOUND: .planning/phases/13-guest-tracking-tenant-validation/13-VALIDATION.md (updated)
FOUND: .planning/phases/13-guest-tracking-tenant-validation/deferred-items.md
FOUND: 1f0b9aa (Task 13-01-01 commit)
FOUND: 1e7f357 (Task 13-01-02 commit)
FOUND: e978939 (Task 13-01-03 commit)
FOUND: 9c5309b (Task 13-01-04 commit)
FOUND: 300cae2 (Task 13-01-05 commit)

## Self-Check: PASSED

## TDD Gate Compliance

RED gate commits (`test(13-01):`): `1f0b9aa` (Task 01 — failing integration test recorded in commit body), `1e7f357` (Task 02 — compile-fail recorded in commit body)
GREEN gate commits (`feat(13-01):`): `e978939` (Task 03 — helper + 3 refactors, 5 tests turn green), `9c5309b` (Task 04 — ReviewService helper, 2nd @Test green)
Regression pin (`test(13-01):`): `300cae2` (Task 05 — 4 new @Test methods + VALIDATION.md)

TDD sequence: RED → RED → GREEN → GREEN → pin — all five gates present on the feature branch.

## User Setup Required

None — no external service configuration required. The change is internal to the Spring Boot service layer. No new environment variables, no new Keycloak scopes, no new Redis keys, no new migrations.

## Must-Haves Verification

All 6 must-haves from the PLAN frontmatter verified:

1. ✓ "A JWT-authenticated request for tenant A hitting /public/shops/{B-slug}/products|config|orders|reviews is rejected with HTTP 403 BEFORE any tenant-scoped data is read or written"
   → `crossTenantJwtReturns403OnProducts`, `crossTenantJwtReturns403OnReviews`, `crossTenantJwtReturns403OnCreateOrder` all PASS green
2. ✓ "Legitimate same-tenant JWT request (JWT tenant_id == slug tenant) returns 200 — no regression"
   → `sameTenantJwtSucceeds` PASS green
3. ✓ "Anonymous guest request (no JWT) returns 200 — no regression on the guest happy path"
   → `anonymousGuestSucceeds` PASS green
4. ✓ "Cross-tenant spoof attempts emit a structured SLF4J WARN log containing event=tenant_spoof_attempt slugTenant={uuid} upstreamTenant={uuid}"
   → `crossTenantRequestLogsSpoofEvent` with OutputCaptureExtension PASS green
5. ✓ "PublicStorefrontService has exactly one slug→tenant resolution helper consumed by all 3 sites (getShopConfig, getShopProducts, createGuestOrder) — zero duplicated findBySlugAndPublishedTrue+TenantContext.set pairs remain"
   → Grep: 1 helper definition; 1 `TenantContext.set(shop.getTenantId())` call (inside the helper only); 0 `TenantContext.set(tenantId)` calls outside the helper
6. ✓ "ReviewService.getShopReviews and ReviewService.createReview use the same tenant-match gate"
   → Grep: 1 helper definition in ReviewService; 0 `TenantContext.set(tenantId)` calls; both public methods route through the helper

All required artifacts from plan frontmatter present and meet min_lines constraints (TenantAccessDeniedException ≥8 lines; CrossTenantSpoofIntegrationTest ≥140 lines; `event=tenant_spoof_attempt` present in helper + integration test).

## Threat Flags

None — Phase 13 strictly narrows existing surface; no new endpoints, no new auth paths, no new file access, no new schema.

## Next Plan Readiness

- Phase 13 is the sole plan in Phase 13. SEC-01 is shippable on the feature branch; ready for PR.
- Enables Phase 14 (CQ-01/CQ-02) and Phase 17 (VOPS) to proceed with the guest-path surface hardened.
- No blockers.
- Recommended follow-up (separate phase, tracked in deferred-items.md): audit `application-test.yml` to set `spring.rabbitmq.port: 0` + `listener.simple.auto-startup: false` as defaults, unblocking ~40 pre-existing integration tests that predate Phase 12 Deviation #3.

---
*Phase: 13-guest-tracking-tenant-validation*
*Completed: 2026-04-18*
