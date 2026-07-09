---
phase: quick-260709-iro
plan: 01
subsystem: security
tags: [rate-limiting, bucket4j, redis, x-forwarded-for, public-storefront, ddos, spring-boot]

# Dependency graph
requires:
  - phase: quick-260708-tsl
    provides: "issue #86 fail-open-with-alarm rate limiter (jtoye.ratelimit.fail_open counter + bounded Lettuce command timeout)"
  - phase: quick-260709-bl2
    provides: "issue #87 JWT audience validation (unchanged; public paths are tenant-less / permitAll)"
provides:
  - "IP-keyed rate limiting for tenant-less /public/** storefront paths at the Core layer"
  - "ClientIpResolver (X-Forwarded-For-first client IP extraction with getRemoteAddr fallback)"
  - "rate-limiting.public.* config keys (requests-per-minute / burst / window-seconds) with env override"
  - "Testcontainers real-Redis proof of 429+Retry-After, tenant independence, and public fail-open"
affects: [rate-limiting, public-storefront, edge-go, ddos-hardening, remediation-backlog]

# Tech tracking
tech-stack:
  added: []  # no new dependencies — Bucket4j core+redis 8.10.1 + Micrometer already present
  patterns:
    - "Public (tenant-less) requests keyed by client IP in a distinct Redis namespace (rl:public:{ip}) vs tenant rate_limit::{tenant}"
    - "Public branch reuses the #86 fail-open-with-alarm try/catch shape (availability over enforcement on Redis outage)"
    - "XFF-first IP resolution with documented spoofing caveat (trusted proxy must overwrite, not append)"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/security/ClientIpResolver.java
    - core-java/src/test/java/uk/jtoye/core/security/ClientIpResolverTest.java
    - core-java/src/test/java/uk/jtoye/core/security/PublicRateLimitIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java
    - core-java/src/main/resources/application.yml
    - core-java/src/main/resources/application-prod.yml
    - core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorTest.java
    - docs/metrics.json
    - CLAUDE.md

key-decisions:
  - "Key public buckets by client IP (XFF-first) — no stable guest session id exists; public storefront is stateless permitAll"
  - "Distinct Redis namespace rl:public: vs rate_limit:: so a public flood and a tenant bucket can never exhaust each other"
  - "Public 429 body is generic (no tenantId) — a tenant-less guest request has no tenant to leak"
  - "XFF spoofing is accepted-and-documented (T-88-02): operators must ensure the trusted edge/ingress overwrites XFF for hard guarantees"
  - "No WebConfig change — /public/** already reaches the interceptor and is not in isExcludedPath"

patterns-established:
  - "Public IP-keyed limiter branch runs BEFORE the tenant TenantContext logic and short-circuits it"
  - "Public limits injected from rate-limiting.public.* (@Value), never hardcoded literals"

requirements-completed: [ISSUE-88-P1-6]

# Metrics
duration: ~25min
completed: 2026-07-09
---

# Phase quick-260709-iro Plan 01: Issue #88 [P1-6] Public-Path IP-Keyed Rate Limiting Summary

**Tenant-less `/public/**` storefront requests are now throttled by client IP (Bucket4j `rl:public:{ip}`) at the Core layer, returning 429 + Retry-After on flood, independent of tenant buckets, and preserving the issue #86 fail-open-with-alarm on a Redis outage.**

## Performance

- **Duration:** ~25 min
- **Started:** 2026-07-09T13:37Z (approx)
- **Completed:** 2026-07-09T13:00Z (UTC clock; full gate 9m33s)
- **Tasks:** 3 (all TDD where applicable)
- **Files modified:** 9 (3 created, 6 modified)

## Accomplishments
- Closed the issue #88 gap: `RateLimitInterceptor.preHandle` used to early-return `true` for every tenant-less request, so all guest `/public/**` traffic bypassed throttling. Public paths now hit an IP-keyed bucket before the tenant logic.
- `ClientIpResolver` extracts the X-Forwarded-For first hop (trimmed) with a `getRemoteAddr()` fallback and a non-null `"unknown"` sentinel; the spoofing caveat is documented in Javadoc.
- Public and tenant limiter keyspaces are independent (`rl:public:{ip}` vs `rate_limit::{tenant}`) — proven by a Testcontainers assertion that a tenant request is unaffected by an exhausted public IP bucket.
- Redis outage still fails OPEN for `/public/**` (no 500, bounded, `jtoye.ratelimit.fail_open` alarmed) — issue #86 semantics preserved for the new branch.
- Public limits are config-driven (`rate-limiting.public.requests-per-minute|burst|window-seconds`) with env override in `application.yml` and prod literals in `application-prod.yml`; no hardcoded literals in the interceptor.

## Task Commits

Each task committed atomically (TDD RED → GREEN):

1. **Task 1: ClientIpResolver + unit test**
   - `17bf978` (test) — RED: failing ClientIpResolver XFF-first resolution test (7 cases)
   - `1e196d5` (feat) — GREEN: X-Forwarded-For-first ClientIpResolver
2. **Task 2: Public IP-keyed bucket branch + config + unit tests**
   - `71a7ecf` (test) — RED: failing public-path rate-limit unit tests (key namespace, 429, independence, fail-open)
   - `294dc88` (feat) — GREEN: IP-keyed public branch in RateLimitInterceptor + rate-limiting.public.* config keys
3. **Task 3: Testcontainers real-Redis integration test + docs regen + full gate**
   - `d8254dd` (test) — PublicRateLimitIntegrationTest (flood/tenant-independence/fail-open) + docs/metrics.json 755→767 + CLAUDE.md prose sync

_Plan metadata (SUMMARY.md, STATE.md) is committed by the orchestrator, not here._

## Files Created/Modified
- `core-java/.../security/ClientIpResolver.java` (new) — XFF-first client IP extraction, never null, spoofing caveat documented.
- `core-java/.../security/ClientIpResolverTest.java` (new) — 7 cases: single/multi-hop/whitespace XFF, blank/empty fallback, remoteAddr fallback, `"unknown"` sentinel.
- `core-java/.../security/RateLimitInterceptor.java` — added `isPublicPath`, `handlePublicRateLimit`, `createPublicBucketConfiguration`, three `@Value` public-tier fields, and the `rl:public:` key prefix; public branch runs before tenant logic, inside the #86 try/catch.
- `core-java/.../security/RateLimitInterceptorTest.java` — promoted the `RemoteBucketBuilder` mock to a field; added 4 public-branch tests (13 total in class).
- `core-java/.../security/PublicRateLimitIntegrationTest.java` (new) — real postgres:15 + redis:7-alpine, `@Tag("testcontainers")`, drives `preHandle` directly; asserts 429+Retry-After, tenant independence, and fail-open on `redis.stop()`.
- `core-java/src/main/resources/application.yml` — `rate-limiting.public.*` block with env override.
- `core-java/src/main/resources/application-prod.yml` — `rate-limiting.public.*` prod literals (30/10/60).
- `docs/metrics.json` — regenerated by `scripts/docs-freshness.sh --write`: 755 → 767 logical invocations (Java @Test 552→564 across 88→90 files).
- `CLAUDE.md` — line ~15 test-count prose synced to 767 / 564 across 90 files.

## Decisions Made
- Key by IP (XFF-first), not session: the public storefront is stateless permitAll with no guest session cookie, so IP is the only stable key available at the Core layer.
- Distinct `rl:public:` namespace guarantees keyspace independence from tenant buckets (T-88-03).
- Generic public 429 body (no tenantId) — nothing tenant-scoped exists to leak for a guest request.
- No WebConfig change required: `/public/**` already reaches the interceptor and is not excluded.

## Deviations from Plan

None - plan executed exactly as written. No Rule 1/2/3 auto-fixes were needed; no architectural (Rule 4) decisions arose; no authentication gates; no new packages (Package Legitimacy Gate not triggered).

## Issues Encountered
- None. The RED phases failed as expected, GREEN phases passed, and the isolated integration test passed on first run.
- Note: the full-gate console tail shows a Postgres "Connection refused" stack trace — this is an EXPECTED logged exception from a pre-existing dead-port datasource-fault test, not a failure (build succeeded, 0 failures / 0 errors).

## Test Results (final gate — full unfiltered suites)
- `:core-java:test` — **463 tests, 0 failures, 0 errors, 1 skipped** (pre-existing).
- `:core-java:integrationTest` — **101 tests, 0 failures, 0 errors, 1 skipped** (pre-existing).
- Combined Java @Test methods = 564, matching `docs/metrics.json`.
- `scripts/docs-freshness.sh` — **OK** (total logical invocations: 767, no drift).
- New classes: `ClientIpResolverTest` 7/7, `RateLimitInterceptorTest` 13/13, `RateLimitInterceptorFailOpenTest` 1/1 (unaffected), `PublicRateLimitIntegrationTest` 1/1.
- No frontend/edge-go changes → no `npm run build` / `go test` gates required.

## Threat Model Coverage
- T-88-01 (DoS on tenant-less /public/**) — mitigated: IP-keyed bucket + 429/Retry-After (integration ASSERT A).
- T-88-02 (IP spoofing via XFF) — accepted & documented in `ClientIpResolver` Javadoc.
- T-88-03 (public↔tenant keyspace collision) — mitigated: `rl:public:` vs `rate_limit::` (integration ASSERT B).
- T-88-04 (Redis outage → 500/hang) — mitigated: #86 fail-open reused (integration ASSERT C).

## Next Phase Readiness
- Issue #88 [P1-6] complete; the P1 remediation batch (#83–#88) is now fully closed at the Core layer.
- Optional future hardening (out of scope): tune `rate-limiting.public.*` defaults per production traffic; ensure the ingress/edge overwrites XFF to make the IP key non-spoofable.

## Self-Check: PASSED

- Created files verified present: `ClientIpResolver.java`, `ClientIpResolverTest.java`, `PublicRateLimitIntegrationTest.java`, `260709-iro-SUMMARY.md`.
- Task commits verified in git log: `17bf978`, `1e196d5`, `71a7ecf`, `294dc88`, `d8254dd`.
- Working tree clean except the (intentionally uncommitted) SUMMARY.md.

---
*Phase: quick-260709-iro*
*Completed: 2026-07-09*
