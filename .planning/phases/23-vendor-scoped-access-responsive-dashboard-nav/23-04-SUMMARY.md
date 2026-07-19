---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 04
subsystem: security-authorization
tags: [spring-web, rfc7807, idempotency, on-conflict, spring-cache, testcontainers, multi-tenancy, authorization]

# Dependency graph
requires:
  - phase: 23-01-data-layer
    provides: "ShopStaffRepository (countByTenantIdAndRole / findByTenantIdAndUserId / JpaRepository) + UserDirectoryRepository.findByTenantId + ShopRole enum + V52 shop_staff functional unique index uq_shop_staff_tenant_user_shop + shop_staff_shop_id_fkey"
  - phase: 23-02-enforcement-jit
    provides: "ShopAccessService.requireGroupAdmin()/isGroupAdmin()/evictMembership(userId) + LastGroupAdminException (409, /last-group-admin) + ShopAccessDeniedException (403, /shop-access-denied) already wired in GlobalExceptionHandler"
  - phase: "16.1-pre-prod-hardening"
    provides: "GlobalExceptionHandler RFC 7807 ProblemDetail convention (handleLastGroupAdmin 409, handleShopAccessDenied 403)"
provides:
  - "StaffController — GROUP_ADMIN-gated REST @ /api/v1/staff: GET (directory + grants), POST /grant (201 new / 200 idempotent replay), DELETE /{id} (204; last-GA -> 409)"
  - "StaffManagementService — list/grant(idempotent, ON CONFLICT DO NOTHING re-select)/revoke + last-GROUP_ADMIN 409 guard (D-11) + evict-after-commit membership eviction (D-05)"
  - "ShopStaffRepository.insertGrantIfAbsent (native race-safe idempotent grant) + findByTenantId (list view)"
  - "GrantStaffRequest / StaffMemberDto / DirectoryEntryDto record DTOs"
affects: [23-06-staff-screen]

# Tech tracking
tech-stack:
  added: []  # no new external dependencies — 100% composition of Spring Web / Spring Data JPA / Spring Cache / Testcontainers
  patterns:
    - "Idempotent grant = native INSERT ... ON CONFLICT DO NOTHING on the functional unique index + re-select the canonical row + created-flag (1=201, 0=200) — a retried/duplicate mutating call replays a typed DTO, never an untyped unique-constraint 500 (agent-readiness contract)"
    - "Service-level authorization gate (requireGroupAdmin() at the top of every method) instead of a class-level @PreAuthorize('hasRole(admin)'), so a non-realm-admin tenant GROUP_ADMIN is admitted while the realm-admin bridge still passes"
    - "Evict-after-commit via TransactionSynchronization.afterCommit so the target's membership cache is invalidated only once the grant/revoke has actually committed (RESEARCH §4 caveat, D-05)"
    - "Last-writer lockout guard: count the tenant-wide GROUP_ADMINs before a revoke/tenant-wide-downgrade and refuse the final one (409)"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffController.java
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/GrantStaffRequest.java
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/StaffMemberDto.java
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/DirectoryEntryDto.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StaffManagementIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopStaffRepository.java

key-decisions:
  - "Idempotent grant re-selects the canonical row after ON CONFLICT DO NOTHING and returns a created-flag; the controller maps created=true->201, created=false->200. A duplicate grant is a typed replay of the same StaffMemberDto id, never a DataIntegrityViolationException 500 (T-23-04-05 mitigation, CLAUDE.md agent-readiness)."
  - "The gate is the service's requireGroupAdmin() (D-10), NOT a class-level @PreAuthorize('hasRole(admin)') — the latter would exclude a non-realm-admin tenant GROUP_ADMIN. Path is authenticated (not in SecurityConfig permitAll)."
  - "Reject shop-scoped GROUP_ADMIN grants (role==GROUP_ADMIN requires shopId==null, 400): a shop-scoped GROUP_ADMIN row would not confer tenant-wide admin (resolveMembership only treats a NULL-shop GROUP_ADMIN as such) AND would corrupt the countByTenantIdAndRole(GROUP_ADMIN) last-admin guard. (Rule 2)"
  - "Membership eviction is registered afterCommit (D-05 / RESEARCH §4) so a re-resolve on the target's next request cannot race the just-written row; falls back to inline evict when no tx synchronization is active (no-op in the test profile — no cache manager)."
  - "VSA-04 left PENDING — the backend half (list/grant/revoke API) ships here, but VSA-04's acceptance also names the staff SCREEN + its Jest list/grant/revoke tests (23-06). Marking VSA-04 complete now would be a false-green (mirrors 23-01/23-02's VSA-01/VSA-02 discipline). VSA-04 closes in 23-06."

patterns-established:
  - "shop-scoped grant tests must seed a real shop (shopService.createShop as realm-admin) to satisfy shop_staff_shop_id_fkey — the JIT test only used NULL shop_ids and never hit that FK"
  - "Race-safe grant reserve idiom copied verbatim from insertGroupAdminIfAbsent, parameterising shop_id (CAST(:shopId AS uuid) so a NULL bind never trips 'could not determine data type') + role (CAST(:role AS varchar)) + created_by"

requirements-completed: []  # VSA-04 pending — backend delivered; the staff screen + its Jest tests are 23-06

# Metrics
duration: 10min
completed: 2026-07-19
---

# Phase 23 Plan 04: Vendor Staff-Management Backend (VSA-04) Summary

**The GROUP_ADMIN-only staff-management REST backend is live: `GET/POST/DELETE /api/v1/staff` lists the login-populated directory + current grants, grants a `(user, shop|null, role)` idempotently (native ON CONFLICT DO NOTHING + re-select → typed 201/200, never a unique-constraint 500), and revokes with immediate effect (evict-after-commit, D-05) — all guarded by the last-GROUP_ADMIN 409 (D-11) and the typed shop-access 403 for a non-GROUP_ADMIN (D-10), proven 6/6 against real Postgres 15.**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-07-19T11:51:31Z
- **Completed:** 2026-07-19T12:01Z
- **Tasks:** 2
- **Files:** 6 created + 1 modified

## Accomplishments
- **Idempotent grant + last-GA guard + evict-on-write (Task 1)** — `ShopStaffRepository.insertGrantIfAbsent` (native `INSERT ... ON CONFLICT (tenant_id, user_id, COALESCE(shop_id, zero-uuid)) DO NOTHING`, `CAST(:shopId AS uuid)`/`CAST(:role AS varchar)` so a NULL shop bind never trips "could not determine data type") + `findByTenantId` for the list view. `StaffManagementService` (`@Service @Transactional`) — every method gated by `shopAccessService.requireGroupAdmin()`; `grant()` guards a last-GROUP_ADMIN tenant-wide downgrade (409), runs the race-safe insert, re-selects the canonical row, returns a `GrantResult(member, created)`, and evicts the target's cache after commit; `revoke()` blocks the final tenant-wide GROUP_ADMIN via `countByTenantIdAndRole(GROUP_ADMIN) <= 1` → 409, else deletes + evicts after commit (warn-logs a self-downgrade).
- **GROUP_ADMIN-gated REST + DTOs + proof (Task 2)** — `StaffController` @ `/api/v1/staff` (hard-mapped like `TenantAdminController`/`RefundController`): `GET` list, `POST /grant` (201 fresh / 200 idempotent replay via the created-flag), `DELETE /{id}` (204). Record DTOs: `GrantStaffRequest` (`@NotNull` userId + role, nullable shopId), `StaffMemberDto`, `DirectoryEntryDto`. `StaffManagementIntegrationTest` (Testcontainers, **6/6**) proves grant→access-gained, revoke→immediate typed 403, duplicate-grant→idempotent-200 (same id, exactly one row, no `DataIntegrityViolationException`), last-GROUP_ADMIN→409, non-GROUP_ADMIN→typed 403, and that a SECOND GROUP_ADMIN releases the guard (one of two is revocable).

## Task Commits

Each task was committed atomically:

1. **Task 1: StaffManagementService — idempotent grant + last-GROUP_ADMIN 409 + evict-on-write** — `9220c79` (feat)
2. **Task 2: StaffController (GROUP_ADMIN-gated REST) + DTO + idempotency/last-GA proof** — `6eb153c` (feat)

**Plan metadata:** committed with this SUMMARY + STATE.md + ROADMAP.md (docs: complete plan)

## Files Created/Modified
- `security/access/StaffManagementService.java` — list/grant(idempotent)/revoke + last-GA 409 guard + evict-after-commit; nested `StaffListResponse` + `GrantResult` records
- `security/access/StaffController.java` — GROUP_ADMIN-gated REST @ /api/v1/staff (201/200/204, @ApiResponses per TenantAdminController)
- `security/access/dto/GrantStaffRequest.java` — `@NotNull` userId + role, nullable shopId record
- `security/access/dto/StaffMemberDto.java` — grant view record (hand-mapped `from(ShopStaff)`)
- `security/access/dto/DirectoryEntryDto.java` — directory picker record (hand-mapped `from(UserDirectory)`)
- `security/access/ShopStaffRepository.java` (MOD) — added native `insertGrantIfAbsent` (ON CONFLICT DO NOTHING) + `findByTenantId`
- `test/.../StaffManagementIntegrationTest.java` — Testcontainers 6/6 (grant/revoke/idempotency/last-GA/non-GA)

## Decisions Made
- **Idempotent grant is a re-select + created-flag, not a naked insert:** after `insertGrantIfAbsent` (which returns rows-affected), the service re-selects the canonical row (the fresh one OR the pre-existing one on a DO-NOTHING replay) and returns `GrantResult(member, created)`. The controller maps `created` → 201/200. This makes a retried/double-submitted grant a stable typed replay of the same `StaffMemberDto` id — the agent-readiness idempotency contract (T-23-04-05) — instead of surfacing a raw `DataIntegrityViolationException` 500.
- **Service-gate, not controller @PreAuthorize:** the D-10 gate is `requireGroupAdmin()` inside every service method (the realm-admin implicit-GROUP_ADMIN bridge passes there), NOT a class-level `@PreAuthorize("hasRole('admin')")` which would 403 a legitimate non-realm-admin tenant GROUP_ADMIN. The path stays authenticated via `SecurityConfig`'s `anyRequest().authenticated()` (not in permitAll).
- **Shop-scoped GROUP_ADMIN grants are rejected (400):** GROUP_ADMIN is inherently tenant-wide (a NULL shop); a `(user, shopX, GROUP_ADMIN)` row would neither confer tenant-wide admin (`resolveMembership` only reads a NULL-shop GROUP_ADMIN as such) nor be counted correctly by the `countByTenantIdAndRole(GROUP_ADMIN)` last-admin guard. Rejecting it keeps the guard exact. (Rule 2 correctness.)
- **Evict after commit (D-05 / §4 caveat):** `evictMembership` is registered as a `TransactionSynchronization.afterCommit` callback so the target's next request re-resolves from `shop_staff` with no stale-allow window; an inline fallback covers the no-active-tx case (a no-op in the test profile anyway — no cache manager).
- **VSA-04 left pending (anti-false-green):** the API half ships here; VSA-04's acceptance also names the staff SCREEN + Jest list/grant/revoke tests (23-06). Closing VSA-04 now would be a false-green (mirrors 23-01/23-02's VSA-01/VSA-02 discipline).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Verify-command path corrected for the repo's build layout + test-tag routing**
- **Found during:** Task 1 + Task 2 (running the plan's `<verify>` commands)
- **Issue:** The plan's `<verify>` commands `cd core-java && ./gradlew compileJava` / `... ./gradlew test --tests "*StaffManagementIntegrationTest"` do not run as written: (a) there is no `core-java/gradlew` — the wrapper is at the repo ROOT (multi-project build); and (b) `StaffManagementIntegrationTest` is `@Tag("testcontainers")`, which the default `test` task EXCLUDES (`excludeTags("testcontainers")`) — it runs only under the dedicated `integrationTest` task. Identical routing deviation to 23-01/23-02.
- **Fix:** Ran from the repo root: `./gradlew :core-java:compileJava` and `./gradlew :core-java:integrationTest --tests "*StaffManagementIntegrationTest"` (real Postgres 15). No production/code change; verification path only.
- **Files modified:** none (execution-command adjustment)
- **Verification:** compileJava exits 0; StaffManagementIntegrationTest 6/6 green.
- **Committed in:** n/a (no file change)

**2. [Rule 2 - Missing critical] Shop-scoped GROUP_ADMIN grant rejected to keep the last-admin count invariant**
- **Found during:** Task 1 (implementing the grant path)
- **Issue:** Nothing in the plan prevented a `(user, shopX, GROUP_ADMIN)` grant, which would create a GROUP_ADMIN row that does NOT confer tenant-wide admin yet WOULD be counted by `countByTenantIdAndRole(GROUP_ADMIN)`, corrupting the D-11 last-admin guard (a phantom "GROUP_ADMIN" could mask the loss of the real one).
- **Fix:** `grant()` throws `IllegalArgumentException` (400) when `role==GROUP_ADMIN && shopId != null`. Keeps `countByTenantIdAndRole(GROUP_ADMIN)` an exact count of tenant-wide GROUP_ADMINs (all GROUP_ADMIN rows are now NULL-shop by construction).
- **Files modified:** `StaffManagementService.java`
- **Committed in:** `9220c79`

**3. [Rule 1 - Bug] Shop-scoped grant tests seed a real shop (FK), not a random UUID**
- **Found during:** Task 2 (first integration-test run — 3/6 failed)
- **Issue:** The three shop-scoped grant tests used a random `shopId` not present in `shops`, violating `shop_staff_shop_id_fkey` (a real FK on the base table). The 23-02 JIT test never hit this because JIT rows use a NULL shop_id.
- **Fix:** Added a `seedShop(tenant)` helper that inserts the tenant row + creates a real shop via `shopService.createShop` as a realm-admin (implicit GROUP_ADMIN), mirroring `ShopAccessEnforcementIntegrationTest`; the three tests now grant against a committed shop id.
- **Files modified:** `StaffManagementIntegrationTest.java` (test-only)
- **Committed in:** `6eb153c`

---

**Total deviations:** 3 (1 Rule 3 verify-routing no-code; 1 Rule 2 correctness; 1 Rule 1 test-fixture bug).
**Impact on plan:** None on deliverables — all intended behaviour (list/grant/revoke, idempotency, last-GA 409, non-GA 403) shipped and is proven green.

## Issues Encountered
- **`shop_staff_shop_id_fkey` on shop-scoped grants** — see Deviation 3. Resolved by seeding a real shop through the service layer (the established 23-03 pattern), not a raw JdbcTemplate shops insert (which would have to satisfy every NOT NULL column + the shops RLS).
- **Transactional-proxy strict-scoping toggle** — the test flips `ShopAccessService.strictScoping` via `ReflectionTestUtils` on the `AopTestUtils`-unwrapped target (the `@Transactional` CGLIB proxy would miss the field), the same idiom as `ShopAccessJitProvisionTest`.

## User Setup Required
None — no external service configuration, no new config keys, no secret rotation. The surface is a new authenticated REST endpoint under the existing JWT/tenant chain.

## Known Stubs
None. `StaffManagementService` is fully wired to real repository contracts (`insertGrantIfAbsent`, `findByTenantId`, `findByTenantIdAndUserId`, `countByTenantIdAndRole`) and `ShopAccessService` (`requireGroupAdmin`, `evictMembership`); no placeholder/empty-value returns. The `list()` directory + grants come from live tables.

## Threat Flags
None beyond the plan's `<threat_model>`. The one new network surface (`/api/v1/staff`) is exactly the modelled GROUP_ADMIN-gated mutating API: T-23-04-01 (non-GA elevation) mitigated by `requireGroupAdmin()` + proven non-GA→403; T-23-04-02 (last-GA lockout) by the 409 guard + proven; T-23-04-03 (stale-grant window) by evict-after-commit + proven revoke→immediate 403; T-23-04-04 (client role/shop tampering) by enum-constrained role + RLS-scoped writes + the shop-scoped-GROUP_ADMIN rejection; T-23-04-05 (duplicate-grant untyped 500) by ON CONFLICT DO NOTHING + re-select + proven idempotent-200/one-row.

## Deferred Items
- **docs-freshness count bump** deferred to the Phase 23 last-plan reconcile (per the 23-01/23-02 precedent). This plan added 1 Java test file / 6 `@Tag("testcontainers")` `@Test` methods; `docs/metrics.json` + CLAUDE.md prose counts are reconciled once at the phase gate.
- **OpenAPI snapshot regen** for the 3 new `/api/v1/staff` endpoints — deferred to the phase-gate `updateOpenApiSnapshot` reconcile (partial regen is not supported; SCOPE BOUNDARY).

## Next Phase Readiness
- **23-06 (staff screen):** the backend contract is ready and matches the planned shape — `GET /api/v1/staff → { directory: DirectoryEntryDto[], grants: StaffMemberDto[] }`, `POST /api/v1/staff/grant` (GrantStaffRequest → 201/200), `DELETE /api/v1/staff/{id}` (204; 409 on last-GA). The screen keys its access-required state on the `/shop-access-denied` 403 type and its conflict copy on `/last-group-admin` 409. VSA-04 closes when 23-06 lands its Jest list/grant/revoke + the screen.
- **No blockers.** Docker/Testcontainers verified; Java 21; the full Spring context boots with the new `StaffController` + `StaffManagementService` beans.

## Self-Check: PASSED
- All 6 created files + the 1 modified file verified present on disk (see verification below).
- Both task commits (`9220c79`, `6eb153c`) verified in git log.
- Verification green: `:core-java:compileJava` exits 0; `StaffManagementIntegrationTest` 6/6 (grant→access, revoke→403, duplicate-grant→idempotent-200/one-row, last-GA→409, non-GA→403, second-GA-revocable).

---
*Phase: 23-vendor-scoped-access-responsive-dashboard-nav*
*Completed: 2026-07-19*
