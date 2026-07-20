---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 08
subsystem: auth
tags: [rbac, vendor-scoped-access, spring-security, jwt, fail-closed, rls, testcontainers]

# Dependency graph
requires:
  - phase: 23-vendor-scoped-access-responsive-dashboard-nav (23-02)
    provides: ShopAccessService enforcement seam (require/requireGroupAdmin/isGroupAdmin/grantedShopIds), typed ShopAccessDeniedException 403
  - phase: 23-vendor-scoped-access-responsive-dashboard-nav (23-06)
    provides: StaffController + StaffManagementService (/api/v1/staff) under the requireGroupAdmin gate
provides:
  - Fail-closed isSystemPrincipal — an unparseable/anonymous/non-Jwt identity is denied, never escalated to GROUP_ADMIN (CR-03, D-04)
  - Explicit, empty-by-default machine-client allowlist (jtoye.access.machine-client-ids / ACCESS_MACHINE_CLIENT_IDS)
  - require(null, role) yields a typed 403 (GROUP_ADMIN-only null-shop write policy), never an NPE/500 (CR-04)
  - ShopAccessFailClosedIntegrationTest — CR-03 + CR-04 regression proof (7 cases, demonstrated RED pre-fix)
affects: [23-09, 24-image-architecture, 25-mutating-mcp-tools]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Fail-closed auth boundary: trust is granted only by an explicit declaration (auth==null internal bypass OR allowlisted machine client), never inferred from an unparseable identity"
    - "Typed-denial resolver (requireVendorUserId) converts an auth failure to a 403 before currentUserId() can 500"
    - "Falsifiability gate: new regression test demonstrated RED against the true pre-fix commit before the fix landed"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessFailClosedIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
    - core-java/src/main/resources/application.yml

key-decisions:
  - "isSystemPrincipal split into isInternalCaller() (auth==null ONLY) + isDeclaredMachineClient(Jwt) (non-UUID sub AND azp/client_id in the allowlist) so the two cases can never be conflated again"
  - "Machine trust is an explicit, empty-by-default allowlist (fail-closed default), resolved from azp then client_id — never inferred from a non-UUID subject"
  - "auth==null internal bypass RETAINED (measured blast radius: 62 no-principal test files); external reachability prevented by Spring Security 401 before any gated service"
  - "Null shopId = tenant-wide/unassigned resource; WRITE is GROUP_ADMIN-only (typed 403), READ side owned by plan 23-09 — pairing written into the require() javadoc so the halves cannot drift"

patterns-established:
  - "Fail-closed on every unparseable/unexpected identity shape at the ShopAccessService gate"
  - "Machine callers declared via config allowlist, not inferred"

requirements-completed: [VSA-02, VSA-04]

# Metrics
duration: 44min
completed: 2026-07-20
---

# Phase 23 Plan 08: Fail-Closed Shop-Access Gate (CR-03 + CR-04) Summary

**Closed the two ShopAccessService security defects from the phase-23 REVIEW gate: `isSystemPrincipal()` no longer maps an unparseable/anonymous identity to unrestricted GROUP_ADMIN (now denied by default, machine callers only via an explicit empty-by-default allowlist), and `require(null, role)` returns a typed 403 instead of an NPE/500.**

## Performance

- **Duration:** ~44 min
- **Started:** 2026-07-20T21:03:00Z (approx, first build)
- **Completed:** 2026-07-20T21:47:20Z
- **Tasks:** 3
- **Files modified:** 3 (2 modified, 1 created)

## Accomplishments
- **CR-03 fail-OPEN closed:** replaced `isSystemPrincipal()` with `isInternalCaller()` (only `Authentication == null`) + `isDeclaredMachineClient(Jwt)` (non-UUID `sub` AND `azp`/`client_id` in the configured allowlist). An anonymous principal, a non-`Jwt` principal, or a JWT with a non-UUID subject that is not allowlisted is now DENIED with the typed 403 — never escalated to GROUP_ADMIN. This makes locked decision D-04 ("fail-open is unacceptable for an auth boundary") true in code, not just prose.
- **Explicit machine allowlist:** new `jtoye.access.machine-client-ids` config key (`ACCESS_MACHINE_CLIENT_IDS`, empty default → fail-closed). Trust for a non-UUID-subject token is declared, never inferred.
- **Untyped 500 removed:** `currentUserId()` (which threw `IllegalStateException` → 500) replaced by `requireVendorUserId()`, which surfaces an auth failure as the typed `ShopAccessDeniedException` 403.
- **CR-04 NPE closed:** `require(null, role)` now guards the null shop BEFORE the `perShopRole().get(shopId)` lookup (whose `ImmutableCollections.MapN.get(null)` threw NPE); a scoped non-GROUP_ADMIN caller receives `ShopAccessDeniedException(null, GROUP_ADMIN)`.
- **Regression proof:** `ShopAccessFailClosedIntegrationTest` (7 Testcontainers cases) demonstrated RED against the true pre-fix commit (`b1b1bfe`) for cases 1-4 and 7, GREEN after. The 4 pre-existing Phase 23 suites stay green (VSA-01/02/04 not re-opened).

## Task Commits

Each task was committed atomically:

1. **Task 1: Fail-closed isSystemPrincipal + machine allowlist** - `cb51197` (fix)
2. **Task 2: require(null, role) typed 403 not NPE** - `381b3de` (fix)
3. **Task 3: CR-03 + CR-04 falsifiable regression test** - `9d6da86` (test)

**Plan metadata:** _(final docs commit — see git log)_

## Files Created/Modified
- `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java` - Fail-closed `isInternalCaller`/`isDeclaredMachineClient`/`resolveClientId`/`requireVendorUserId`; `machineClientIds` field; null-shop guard in `require`; rewritten javadoc stating the enforced rule (not an unenforced safety claim).
- `core-java/src/main/resources/application.yml` - `jtoye.access.machine-client-ids: ${ACCESS_MACHINE_CLIENT_IDS:}` (config key, not a secret; empty default = deny all non-UUID-subject tokens).
- `core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessFailClosedIntegrationTest.java` - 7-case CR-03 + CR-04 proof (created).

## Pre-fix RED Evidence (falsifiability gate)

The new suite was run against the true pre-fix source (`git show b1b1bfe`, with only a plain `machineClientIds` field added so the test's reflection plumbing resolves — no fail-closed logic reintroduced). Result: **7 tests, 5 failed** — exactly cases 1, 2, 3, 4, 7. Cases 5 (`absentAuthenticationStillPasses`) and 6 (`declaredMachineClientPasses`) passed pre-fix as expected (preservation guards).

Actual assertion output captured pre-fix:

- **Case 1 `nonUuidSubjectIsDeniedOnStaffList`** — `AssertionError: [a non-UUID-subject token must be denied on the staff list, not treated as GROUP_ADMIN] Expecting actual not to be null` (i.e. `list()` returned; the machine token WAS escalated to GROUP_ADMIN).
- **Case 2 `nonUuidSubjectIsDeniedOnGrantAndRevoke`** — `Expecting actual throwable to be an instance of ShopAccessDeniedException but was: org.springframework.dao.DataIntegrityViolationException ... violates foreign key constraint "shop_staff_shop_id_fkey"`. The self-grant path passed the gate and **actually reached the `shop_staff` INSERT**, failing only because the random shopId had no FK target — damning proof of the escalation.
- **Case 3 `nonUuidSubjectCannotReadDirectoryPii`** — `Expecting code to raise a throwable.` (`list()` returned the seeded `user_directory` PII row).
- **Case 4 `anonymousPrincipalIsDenied`** — `Expecting code to raise a throwable.` (anonymous principal escalated).
- **Case 7 `requireNullShopThrowsTypedDenialNotNpe`** — `Expecting ShopAccessDeniedException but was: java.lang.NullPointerException: Cannot invoke "Object.equals(Object)" because "o" is null at java.util.ImmutableCollections$Map1.get(...) at ShopAccessService.require(ShopAccessService.java:109)` — the exact CR-04 NPE.

Post-fix run: `ShopAccessFailClosedIntegrationTest` **7/7 green**. Re-running with the four pre-existing Phase 23 suites: Enforcement 4/4, StaffManagement 6/6, JitProvision 4/4, RlsPolicy 3/3 — **all green (24 integration tests, 0 failures)**. Full `:core-java:test` unit suite green after both Task 1 and Task 2.

## Decisions Made
- **isSystemPrincipal split into two named predicates** so anonymous/non-Jwt/unparseable-subject and genuine-internal can never be conflated again.
- **Machine trust via explicit empty-by-default allowlist**, resolved from `azp` then `client_id` (Keycloak standard claims). No project token currently reads either claim, so both are supported in preference order; an absent client identity is denied.
- **`auth == null` internal bypass retained deliberately** — measured blast radius is 62 no-principal test files, and the branch is not externally reachable (Spring Security rejects unauthenticated requests with 401 before any gated service). The javadoc now states the enforced rule instead of an unenforced safety claim.
- **Null-shop write policy written into `require()` javadoc**, paired with plan 23-09 as the read-side owner, so the write half (GROUP_ADMIN-only) and read half (tenant-wide-visible) cannot drift. Additive — preserves legacy null-shop catalogue visibility (Incremental Betterment).

## Deviations from Plan

None - plan executed exactly as written. No architectural changes (Rule 4) were needed; no bugs, missing functionality, or blocking issues (Rules 1-3) were discovered outside the planned work. No package installs (the plan adds no dependencies).

## Deferred with Reason

- **`asSystem()` ThreadLocal marker (23-REVIEW.md CR-03 suggestion)** — deferred. The measured blast radius of flipping the `auth == null` branch is 62 test files; that branch is not externally reachable (Spring Security 401 gate). Introducing a ThreadLocal system marker is a larger, separate change than a gap-closure fix warrants and is tracked as a follow-up. This plan instead fails closed on identity *shape* while retaining the narrow, non-reachable `auth == null` bypass.
- **`@PreAuthorize` scope backstop on `StaffController`** — deferred. Scope definitions belong to the issue #206 scoped-credentials work; adding an undefined scope here would break the live frontend, and D-10 already forbids the `hasRole('admin')` form. Not in scope for this fix.
- **Residual T-23-08-06 (async execution without SecurityContext propagation)** — an internal `@Async` call would inherit no `Authentication` and therefore take the retained `auth == null` bypass. No gated service is currently reached from a `@Scheduled`/`@RabbitListener`/`@Async` path (measured), so this is a tracked residual, not an open hole.

## Issues Encountered
- The `test` Gradle task excludes `@Tag("testcontainers")` (they run under `integrationTest`), so the plan's per-task `./gradlew :core-java:test --tests "*ShopAccess*"` matches no tests under `test`. Resolved by using the full `:core-java:test` unit suite as the per-task no-regression gate (green after Task 1 and Task 2) and `:core-java:integrationTest --tests "*ShopAccessFailClosedIntegrationTest"` (plus the 4 sibling suites) as the integration gate — both are the acceptance criteria's substantive checks.

## User Setup Required
None - no external service configuration required. The new `ACCESS_MACHINE_CLIENT_IDS` env var is optional and empty-by-default (fail-closed); set it only when a legitimate client-credentials service account with a non-UUID subject must bypass shop-scoping.

## Next Phase Readiness
- CR-03 and CR-04 are closed and proven; the `/api/v1/staff` PII-read + self-grant escalation path is shut at its single gate.
- **Unchanged pre-existing phase blocker (not this plan's scope):** `docs/api/openapi-snapshot.json` still lacks the 3 `/api/v1/staff` endpoints; `OpenApiSnapshotTest` runs inside `integrationTest`. Plan 23-15 closes it via `./gradlew :core-java:updateOpenApiSnapshot`.
- Plan **23-09** must implement the READ half of the null-shop policy (legacy `shop_id IS NULL` products visible to scoped users) — referenced in the `require()` javadoc.

---
*Phase: 23-vendor-scoped-access-responsive-dashboard-nav*
*Completed: 2026-07-20*

## Self-Check: PASSED

- Files verified present: ShopAccessService.java, application.yml, ShopAccessFailClosedIntegrationTest.java, 23-08-SUMMARY.md
- Commits verified in git: cb51197 (Task 1), 381b3de (Task 2), 9d6da86 (Task 3)
