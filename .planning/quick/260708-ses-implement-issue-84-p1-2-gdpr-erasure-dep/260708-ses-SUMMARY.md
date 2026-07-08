---
phase: quick-260708-ses
plan: 01
subsystem: database
tags: [gdpr, rls, envers, postgres, testcontainers, s3, multi-tenancy, spring-data-jpa]

# Dependency graph
requires:
  - phase: quick-260708-rlp
    provides: "Issue #83 RBAC — GdprController class-level @PreAuthorize(\"hasRole('admin')\") + RoleBasedAccessIntegrationTest admin/low-priv gate"
provides:
  - "V42 migration: erasure_records table (tenant-scoped, FORCE RLS, PII-free SHA-256 email hash)"
  - "V42 tenant-scoped UPDATE policies on orders_aud/customers_aud (Article-17 audit-scrub exception)"
  - "ErasureRecord entity (Persistable, app-assigned id) + ErasureRecordRepository"
  - "Extended GdprService: guest-order email sweep, S3 review-photo delete, native _aud PII scrub, durable erasure record"
  - "Native tenant-scoped scrub queries OrderRepository.scrubOrdersAudit / CustomerRepository.scrubCustomerAudit"
  - "GdprErasureIntegrationTest — Testcontainers proof of guest-PII reachability + _aud scrub + durable record"
affects: [gdpr, data-subject-rights, audit-history, storefront-guest-orders]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "GDPR Article-17 targeted PII scrub of append-only Envers _aud history via tenant-scoped native @Modifying UPDATE (Envers stays enabled)"
    - "PII-free durable audit record: store only a one-way SHA-256 hex of the erased email, never plaintext"
    - "Persistable<UUID> with app-assigned id + isNew()==true for append-only insert-only entities (direct INSERT, no merge SELECT)"
    - "Guest-order reachability by de-duplicated email sweep (findByCustomerId ∪ findByCustomerEmailOrderByCreatedAtDesc)"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V42__gdpr_erasure_completeness.sql
    - core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecord.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecordRepository.java
    - core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/GdprController.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderRepository.java
    - core-java/src/main/java/uk/jtoye/core/customer/CustomerRepository.java
    - core-java/src/test/java/uk/jtoye/core/gdpr/GdprServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/gdpr/GdprControllerTest.java
    - docs/metrics.json
    - CLAUDE.md

key-decisions:
  - "erasure_records stores only a SHA-256 hex of the email (never plaintext) so the durable audit record does not re-introduce the PII being erased"
  - "V42 adds the missing tenant-scoped UPDATE policies on orders_aud/customers_aud — the deliberate Article-17 exception to append-only audit history; Envers stays enabled"
  - "Native _aud scrub carries an explicit tenant_id WHERE (defense-in-depth) so it never relies on RLS alone; V42 UPDATE policies gate the same scope at the policy layer"
  - "ErasureRecord implements Persistable<UUID> (app-assigned id, isNew()==true) so recordId is available immediately and the insert is a direct persist"

patterns-established:
  - "Article-17 completeness: guest sweep + _aud scrub + S3 delete + durable PII-free record ship together, never deferred"
  - "Testcontainers reachability proof: create a guest order (customer_id NULL), erase, assert live row redacted + no plaintext in _aud + one erasure_records row"

requirements-completed: [ISSUE-84-P1-2]

# Metrics
duration: 18min
completed: 2026-07-08
---

# Phase quick-260708-ses: Issue #84 P1-2 GDPR Erasure Completeness Summary

**UK-GDPR Article-17 erasure now reaches guest storefront orders by email, physically deletes review photos from S3/MinIO, scrubs pre-erasure PII from the Envers orders_aud/customers_aud history via tenant-scoped native UPDATEs, and persists one PII-free erasure_records row (SHA-256 email hash) — the #83 admin role gate stays intact.**

## Performance

- **Duration:** 18 min
- **Started:** 2026-07-08T19:36:04Z
- **Completed:** 2026-07-08T19:54:32Z
- **Tasks:** 3 (Task 2 was TDD — RED + GREEN)
- **Files modified:** 12 (4 created, 8 modified)

## Accomplishments
- Closed the cosmetic-erasure defect: guest storefront orders (customer_id NULL) carrying name/email/phone are now anonymised via a de-duplicated email sweep — previously invisible to the findByCustomerId-only walk.
- Pre-erasure PII in the append-only Envers audit history (orders_aud, customers_aud) is scrubbed for the subject through tenant-scoped native UPDATEs; V42 adds the missing UPDATE policies so the scrub runs under FORCE RLS for the NOSUPERUSER app role.
- Orphaned review photos are physically deleted from S3/MinIO via StorageService.delete before the URLs are nulled.
- Every erasure persists exactly one durable, PII-free erasure_records row (SHA-256 email hash, counts, erased_by principal) as GDPR proof-of-erasure.
- Testcontainers integration test on real Postgres 15 + Flyway V42 proves guest-PII reachability, _aud scrub, and the durable record end-to-end.

## Task Commits

Each task was committed atomically:

1. **Task 1: V42 migration + ErasureRecord entity/repo + native _aud scrub contracts** - `82f6134` (feat)
2. **Task 2 (TDD RED): failing GdprServiceTest for the four completeness behaviors** - `750bd60` (test)
3. **Task 2 (TDD GREEN): extend GdprService — guest sweep, S3 delete, _aud scrub, durable record** - `9ae1d03` (feat)
4. **Task 3: Testcontainers reachability proof + docs/metrics.json + CLAUDE.md prose sync** - `a11348c` (test)

_TDD gate satisfied: RED `test(...)` (750bd60) precedes GREEN `feat(...)` (9ae1d03)._

## Files Created/Modified
- `core-java/src/main/resources/db/migration/V42__gdpr_erasure_completeness.sql` - erasure_records table (tenant-scoped, FORCE RLS) + SELECT/INSERT policies + orders_aud/customers_aud UPDATE policies (Article-17 exception)
- `core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecord.java` - PII-free durable erasure entity (Persistable, app-assigned id, not @Audited)
- `core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecordRepository.java` - JpaRepository for erasure records
- `core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java` - extended eraseCustomerData: email sweep, S3 delete, _aud scrub, durable record, SHA-256 hash + erased_by resolution
- `core-java/src/main/java/uk/jtoye/core/gdpr/GdprController.java` - ErasureResponse extended to 7 args (auditRowsScrubbed, photosDeleted, recordId); admin gate untouched
- `core-java/src/main/java/uk/jtoye/core/order/OrderRepository.java` - scrubOrdersAudit native @Modifying query (explicit tenant_id WHERE)
- `core-java/src/main/java/uk/jtoye/core/customer/CustomerRepository.java` - scrubCustomerAudit native @Modifying query (explicit tenant_id WHERE)
- `core-java/src/test/java/uk/jtoye/core/gdpr/GdprServiceTest.java` - guest sweep + S3 delete + scrub + durable-record unit coverage (5 deps)
- `core-java/src/test/java/uk/jtoye/core/gdpr/GdprControllerTest.java` - 7-arg ErasureResponse + jsonPath assertions for the new fields
- `core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureIntegrationTest.java` - Testcontainers guest-PII reachability + _aud scrub + durable-record proof
- `docs/metrics.json` - regenerated: 735->736 logical invocations, schema_version 42
- `CLAUDE.md` - test-count prose (736/537/83) + schema-version prose (V42) synced

## Decisions Made
- **PII-free durable record:** erasure_records stores a one-way SHA-256 hex of the email, never plaintext — the audit-of-the-erasure must not re-store the very PII removed (threat T-ses-04).
- **Deliberate Article-17 audit exception:** V42 adds tenant-scoped UPDATE policies to the otherwise append-only orders_aud/customers_aud so a targeted PII-column scrub can run; Envers stays fully enabled and no audit rows are deleted (threats T-ses-02, T-ses-07).
- **Defense-in-depth tenancy:** every native scrub UPDATE carries an explicit `tenant_id = :tenantId` WHERE (from customer.getTenantId()), so cross-tenant scrub is impossible even independent of RLS (threat T-ses-03).
- **ErasureRecord as Persistable:** app-assigned id + isNew()==true makes recordId available immediately in the response and forces a direct INSERT for the insert-only record.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Updated GdprControllerTest in the Task 2 GREEN commit instead of Task 3**
- **Found during:** Task 2 GREEN (extending ErasureResponse to the 7-arg signature)
- **Issue:** The `ErasureResponse` DTO signature change made the existing 4-arg construction in `GdprControllerTest` (a Task 3 file) a compile error, which blocked the entire test module — so `GdprServiceTest` (Task 2's verify) could not run.
- **Fix:** Applied the full Task-3 GdprControllerTest update (7-arg constructor + jsonPath assertions for auditRowsScrubbed/photosDeleted/recordId) inside the Task 2 GREEN commit so every commit leaves a compiling, green tree. Task 3 therefore only added the integration test + docs sync.
- **Files modified:** core-java/src/test/java/uk/jtoye/core/gdpr/GdprControllerTest.java
- **Verification:** `:core-java:test --tests "uk.jtoye.core.gdpr.*"` green
- **Committed in:** `9ae1d03` (Task 2 GREEN commit)

**2. [Rule 1 - Bug] Pass a List (not a Collection) to orderRepository.saveAll**
- **Found during:** Task 2 GREEN (first GdprServiceTest run)
- **Issue:** The merged guest+linked orders were passed as a `LinkedHashMap.values()` Collection; the Mockito mock casts its `thenAnswer` return to `saveAll`'s declared `List` return type, throwing ClassCastException.
- **Fix:** Wrap the de-duplicated values in `new ArrayList<>(...)` before saveAll — also the more idiomatic call.
- **Files modified:** core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
- **Verification:** GdprServiceTest passes
- **Committed in:** `9ae1d03` (Task 2 GREEN commit)

**3. [Rule 1 - Correctness] ErasureRecord uses app-assigned id via Persistable instead of @GeneratedValue**
- **Found during:** Task 2 GREEN (recordId asserted non-null)
- **Issue:** With `@GeneratedValue`, the Mockito-mocked repository never triggers Hibernate id assignment, so `recordId` was null in the response; the plan itself specifies "new UUID id assigned in the constructor/service".
- **Fix:** ErasureRecord now assigns `UUID.randomUUID()` in its constructor and implements `Persistable<UUID>` (isNew()==true) so the id is available immediately and the insert is a direct persist.
- **Files modified:** core-java/src/main/java/uk/jtoye/core/gdpr/ErasureRecord.java
- **Verification:** GdprServiceTest + GdprErasureIntegrationTest green
- **Committed in:** `9ae1d03` (Task 2 GREEN commit)

**4. [Rule 3 - Blocking] Ran RoleBasedAccessIntegrationTest under :core-java:integrationTest, not :core-java:test**
- **Found during:** Task 3 verification
- **Issue:** The plan's Task 3 verify runs RoleBasedAccessIntegrationTest under `:core-java:test`, but that class is `@Tag("testcontainers")` and the `test` task `excludeTags("testcontainers")` — it would match 0 tests there.
- **Fix:** Ran it under `:core-java:integrationTest` (which `includeTags("testcontainers")`) alongside GdprErasureIntegrationTest; both green (6/6 and 1/1).
- **Files modified:** none (test-invocation correction only)
- **Verification:** `:core-java:integrationTest` BUILD SUCCESSFUL, 0 failures
- **Committed in:** n/a (execution-time command correction)

---

**Total deviations:** 4 auto-fixed (2 blocking, 2 bug/correctness)
**Impact on plan:** All auto-fixes were necessary to keep every commit compiling+green and to make the durable-record behavior work as the plan specified. No scope creep — the four Article-17 behaviors shipped exactly as planned.

## Issues Encountered
- Transient Hikari "Connection refused localhost:3xxxx" stack trace during Testcontainers startup — pool warm-up retry before the Postgres container port was ready; the build reached BUILD SUCCESSFUL with 0 failures, so it was benign noise, not a test failure.

## Known Stubs
None — all four acceptance behaviors are fully wired (no placeholder/empty-data stubs introduced).

## Threat Flags
None — all new surface (erasure_records table, orders_aud/customers_aud UPDATE policies, S3 deletes) is already enumerated in the plan's threat_model; no security-relevant surface outside it was introduced.

## User Setup Required
None - no external service configuration required. (Flyway applies V42 automatically on the next core-java boot; a Docker rebuild of the core-java container is needed before live/E2E testing per project policy.)

## Next Phase Readiness
- Issue #84 [P1-2] complete; erasure is now GDPR-compliant end-to-end (live rows, audit history, S3, durable record) and remains admin role-gated.
- Remaining P1 backlog: #85–#88.
- Verification status: `:core-java:compileJava` clean; `:core-java:test --tests "uk.jtoye.core.gdpr.*"` green; `:core-java:integrationTest` GdprErasureIntegrationTest (1/1) + RoleBasedAccessIntegrationTest (6/6) green; `scripts/docs-freshness.sh` passes at 736.

## Self-Check: PASSED

- All created files verified present on disk (V42 migration, ErasureRecord, ErasureRecordRepository, GdprErasureIntegrationTest, SUMMARY).
- All four commits verified in git history: 82f6134, 750bd60, 9ae1d03, a11348c.
- SUMMARY.md intentionally left uncommitted (orchestrator rescues it).

---
*Phase: quick-260708-ses*
*Completed: 2026-07-08*
