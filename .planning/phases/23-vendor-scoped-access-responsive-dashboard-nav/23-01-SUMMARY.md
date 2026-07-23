---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 01
subsystem: database
tags: [postgres, rls, flyway, jpa, envers, testcontainers, multi-tenancy, authorization]

# Dependency graph
requires:
  - phase: 22-notifications-comms
    provides: "V55/V56 webhook migrations + WebhookSubscriptionRlsPolicyIntegrationTest — the NOSUPERUSER RLS-proof harness copied here"
  - phase: "16.1-pre-prod-hardening"
    provides: "RlsContractTest sweeps (everyPublicTableHasRlsAndForce / noPolicyUsesRawTenantGucCast) that gate every new table"
provides:
  - "V52 shop_staff (user<->shop<->role) + shop_staff_aud Envers mirror, ENABLE+FORCE RLS via current_tenant_id()"
  - "V52 user_directory (D-09) login-populated grant-target picker, ENABLE+FORCE RLS, email PII protected, NO _aud"
  - "Functional unique index uq_shop_staff_tenant_user_shop over (tenant_id, user_id, COALESCE(shop_id, zero-uuid)) — the ON CONFLICT target for race-safe JIT"
  - "ShopRole enum {GROUP_ADMIN>SHOP_MANAGER>STAFF} with rank()/satisfies() for require(minRole)"
  - "ShopStaff/UserDirectory JPA entities + repositories incl. native race-safe insertGroupAdminIfAbsent + throttled upsertSeen (contracts for 23-02)"
  - "ShopStaffRlsPolicyIntegrationTest — cross-tenant read/forge + user_directory PII proof under NOSUPERUSER"
affects: [23-02-enforcement-jit, 23-03-staff-ui, 23-04-staff-backend, 24-image-architecture]

# Tech tracking
tech-stack:
  added: []  # no new external dependencies — 100% composition of existing Spring Boot / Postgres RLS / Testcontainers stack
  patterns:
    - "V51 safe helper current_tenant_id() for ALL new tenant RLS policies (never the raw ::uuid cast)"
    - "House reserve idiom INSERT ... ON CONFLICT DO NOTHING/DO UPDATE against a functional unique index for race-safe JIT + throttled upsert"
    - "@IdClass composite key (tenant_id, user_id) for a login-populated, non-audited directory table"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V52__shop_staff.sql
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopRole.java
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopStaff.java
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopStaffRepository.java
    - core-java/src/main/java/uk/jtoye/core/security/access/UserDirectory.java
    - core-java/src/main/java/uk/jtoye/core/security/access/UserDirectoryId.java
    - core-java/src/main/java/uk/jtoye/core/security/access/UserDirectoryRepository.java
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopStaffRlsPolicyIntegrationTest.java
  modified: []

key-decisions:
  - "user_directory ships in the SAME V52 migration as shop_staff (one atomic vendor-scoped-access schema unit; keeps V53 free for Phase 24 media_asset)"
  - "All three V52 policies gate through the V51 safe helper current_tenant_id(), NOT the raw current_setting(...)::uuid cast (RlsContractTest#noPolicyUsesRawTenantGucCast gate + 22P02 bug class)"
  - "NO migrate-time GROUP_ADMIN backfill (RESEARCH §1-FLAG): tables ship empty; JIT lazy-provision is 23-02's job"
  - "user_id typed UUID (Keycloak sub is a UUID in this realm); user_directory is NOT @Audited (D-09)"
  - "VSA-01 left PENDING — its JIT-provision backfill, realm-admin bridge, and idempotency test are 23-02 (anti-false-green, mirrors 22-01)"

patterns-established:
  - "Vendor-scoped-access tables (shop_staff/shop_staff_aud/user_directory) are tenant-scoped ENABLE+FORCE RLS — no EXEMPT_TABLES entry"
  - "_aud RLS predicate admits NULL tenant_id: USING (tenant_id IS NULL OR tenant_id = current_tenant_id())"

requirements-completed: []  # VSA-01 intentionally NOT closed here — spans 23-01 (data layer) + 23-02 (JIT/enforcement); see Decisions

# Metrics
duration: 12min
completed: 2026-07-19
---

# Phase 23 Plan 01: Vendor-Scoped Access Data Layer Summary

**V52 ships shop_staff + shop_staff_aud + user_directory (all ENABLE+FORCE RLS via the safe current_tenant_id() helper), the ShopStaff/UserDirectory JPA layer with race-safe native JIT-insert + throttled directory upsert, and a NOSUPERUSER Testcontainers proof that cross-tenant reads/forges and user_directory email PII are blocked.**

## Performance

- **Duration:** ~12 min
- **Started:** 2026-07-19T10:13:29Z
- **Completed:** 2026-07-19T10:26Z
- **Tasks:** 3
- **Files modified:** 8 created (+ 1 planning artifact: deferred-items.md)

## Accomplishments
- **V52 migration** — three tenant-scoped tables, all ENABLE+FORCE RLS gated through the V51 `current_tenant_id()` helper (never the raw `::uuid` cast), idempotent V43-style DO-block policies, functional unique index `uq_shop_staff_tenant_user_shop` over `(tenant_id, user_id, COALESCE(shop_id, zero-uuid))`, and `shop_staff_aud` Envers mirror with a NULL-admitting `_aud` policy. Ships empty (no migrate-time backfill).
- **JPA layer** — `ShopRole` enum (rank-ordered for `require(minRole)`), `@Audited ShopStaff` entity (hand-written accessors, no Lombok), composite-key non-audited `UserDirectory`, and repositories exposing the exact contracts 23-02 needs: `insertGroupAdminIfAbsent` (native `ON CONFLICT DO NOTHING`, race-safe) and `upsertSeen` (native `ON CONFLICT DO UPDATE ... WHERE last_seen < :cutoff`, D-09 throttle).
- **RLS proof** — `ShopStaffRlsPolicyIntegrationTest` (3/3 green) proves under the NOSUPERUSER role-downgrade that tenant B cannot read tenant A's shop_staff grant, cannot forge a tenant-A row (WITH CHECK → "row-level security"), and cannot read tenant A's `user_directory` email PII (FORCE load-bearing).
- **RlsContractTest stays green (4/4)** — the three new tables pass `everyPublicTableHasRlsAndForce`, `noPolicyUsesRawTenantGucCast`, and `noPolicyReadsBuggyAppTenantIdGuc` with no exemption.

## Task Commits

Each task was committed atomically:

1. **Task 1: V52 migration (shop_staff + shop_staff_aud + user_directory)** - `da1df9e` (feat)
2. **Task 2: Entities, repositories, ShopRole enum** - `cdbedbd` (feat)
3. **Task 3: ShopStaffRlsPolicyIntegrationTest (NOSUPERUSER RLS + PII)** - `316f95b` (test)

**Plan metadata:** committed with this SUMMARY + STATE.md + ROADMAP.md (docs: complete plan)

## Files Created/Modified
- `core-java/src/main/resources/db/migration/V52__shop_staff.sql` - shop_staff + shop_staff_aud + user_directory schema, FORCE RLS via `current_tenant_id()`, functional unique index
- `core-java/src/main/java/uk/jtoye/core/security/access/ShopRole.java` - GROUP_ADMIN>SHOP_MANAGER>STAFF with `rank()`/`satisfies()`
- `core-java/src/main/java/uk/jtoye/core/security/access/ShopStaff.java` - `@Audited` entity → shop_staff_aud
- `core-java/src/main/java/uk/jtoye/core/security/access/ShopStaffRepository.java` - membership finders + native `insertGroupAdminIfAbsent`
- `core-java/src/main/java/uk/jtoye/core/security/access/UserDirectory.java` - login-populated directory entity (composite key, NOT audited)
- `core-java/src/main/java/uk/jtoye/core/security/access/UserDirectoryId.java` - `@IdClass` composite key (tenant_id, user_id)
- `core-java/src/main/java/uk/jtoye/core/security/access/UserDirectoryRepository.java` - `findByTenantId` + native throttled `upsertSeen`
- `core-java/src/test/java/uk/jtoye/core/security/access/ShopStaffRlsPolicyIntegrationTest.java` - cross-tenant RLS + PII proof under NOSUPERUSER

## Decisions Made
- **user_directory in V52 (not a separate slot):** one atomic vendor-scoped-access schema unit; keeps V53 free for Phase 24 `media_asset`.
- **Safe helper only:** every USING/WITH CHECK uses `current_tenant_id()` — copying the V43/V47/V50 raw `::uuid` cast would fail `RlsContractTest#noPolicyUsesRawTenantGucCast` and reintroduce the 22P02 empty-GUC crash class.
- **No migrate-time backfill (RESEARCH §1-FLAG):** identities live only in Keycloak; there is no `sub` set at migrate time. The GROUP_ADMIN "backfill" is JIT lazy-provision on first request (23-02). Tables ship empty.
- **VSA-01 left pending (anti-false-green):** the data-layer slice is delivered, but VSA-01's acceptance also requires the JIT-provision backfill, the realm-admin ⇒ implicit GROUP_ADMIN bridge, and the JIT-provision idempotency test — all explicitly scoped to 23-02 by this plan's objective. Marking VSA-01 complete now would be a false-green (mirrors the 22-01 "COMMS-02 left pending" decision). VSA-01 closes in 23-02.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Verification task path corrected for the repo's test-tag routing**
- **Found during:** Task 1 (running the plan's `<verify>` command)
- **Issue:** The plan's `<verify>` command `cd core-java && ./gradlew test --tests "*RlsContractTest" -x integrationTest` does not run the target test in this repo: (a) there is no `core-java/gradlew` (root multi-project build; wrapper is at repo root), and (b) `RlsContractTest`/`ShopStaffRlsPolicyIntegrationTest` are `@Tag("testcontainers")`, which the default `test` task **excludes** (`excludeTags("testcontainers")`, build.gradle.kts:99) — they run only under the dedicated `integrationTest` task (QA-council #71). The literal command exits with "No tests found for given includes".
- **Fix:** Ran the tests via `./gradlew :core-java:integrationTest --tests "*RlsContractTest" --tests "*ShopStaffRlsPolicyIntegrationTest"` from the repo root — the task that actually exercises testcontainers-tagged tests against real Postgres 15. No production/code change; verification only.
- **Files modified:** none (execution-command adjustment)
- **Verification:** RlsContractTest 4/4 green; ShopStaffRlsPolicyIntegrationTest 3/3 green; `compileJava` clean. Results XML under `core-java/build-local/test-results/integrationTest/` (buildDir is `build-local`, build.gradle.kts:15).
- **Committed in:** n/a (no file change)

---

**Total deviations:** 1 (Rule 3 blocking — verify-command routing, no code change)
**Impact on plan:** None on deliverables. The intended verification (real-Postgres RLS proof) ran and is green; only the task-invocation path differed from the plan's literal string due to the repo's testcontainers-tag routing.

## Issues Encountered
- **Acceptance-grep false positives from comments/spacing:** the plan's literal file greps (`FORCE ROW LEVEL SECURITY` == 3, raw-cast == 0, `@Audited` == 0 on UserDirectory) initially tripped on (a) double-spaced `FORCE  ROW` in ALTER statements, (b) a header comment quoting the raw-cast string, and (c) `@Audited` appearing in UserDirectory's "NOT audited" javadoc. Reworded comments and normalised spacing so the literal greps pass exactly, without weakening the actual DDL/annotations. Resolved before commit.

## User Setup Required
None - no external service configuration required. New config keys (`jtoye.access.strict-scoping` D-12, `directory-upsert-interval` D-09) are introduced in 23-02 alongside the JIT/enforcement service, not this data-layer plan.

## Known Stubs
None. The native `insertGroupAdminIfAbsent` and `upsertSeen` queries are complete, functional contracts (wired by 23-02's service); no placeholder/empty-value stubs were introduced.

## Deferred Items
- **docs-freshness count bump** deferred to the Phase 23 last-plan reconcile (per the 22-07 precedent). This plan added 1 Java test file / 3 `@Test` methods; `docs/metrics.json` + CLAUDE.md prose counts are reconciled once at the phase gate. Logged in `deferred-items.md`.

## Next Phase Readiness
- **23-02 (enforcement + JIT):** the data contracts are ready — `ShopStaffRepository.insertGroupAdminIfAbsent` (race-safe GROUP_ADMIN reserve), `existsByTenantIdAndUserId` (JIT short-circuit), `countByTenantIdAndRole` (last-GROUP_ADMIN guard), `UserDirectoryRepository.upsertSeen` (throttled login upsert), and `ShopRole.satisfies(minRole)`. 23-02 owns `ShopAccessService` (JIT provision + realm-admin bridge + membership cache), the enforcement sweep, and closing VSA-01 (incl. the JIT idempotency test).
- **No blockers.** Docker/Testcontainers verified working; Java 21; V52 applies out-of-order behind V54–V56.

## Self-Check: PASSED
- All 8 created files verified present on disk.
- All 3 task commits (`da1df9e`, `cdbedbd`, `316f95b`) verified in git log.
- Combined verification green: RlsContractTest 4/4, ShopStaffRlsPolicyIntegrationTest 3/3, compileJava clean.

---
*Phase: 23-vendor-scoped-access-responsive-dashboard-nav*
*Completed: 2026-07-19*
