---
phase: quick-260708-rlp
plan: 01
subsystem: auth
tags: [rbac, spring-security, keycloak, jwt, method-security, preauthorize, multi-tenant]

# Dependency graph
requires:
  - phase: 12 (security headers / SecurityConfig)
    provides: SecurityConfig SecurityFilterChain + JwtTenantFilter tenant mapping that this plan wires the role converter alongside
  - phase: 17 (vendor-order-detail-stripe-refund-flow)
    provides: RefundController whose deferred-RBAC Javadoc this plan supersedes
provides:
  - Keycloak realm_access.roles -> Spring ROLE_* authority mapping (KeycloakRealmRoleConverter)
  - Application-wide @EnableMethodSecurity
  - Class-level hasRole('admin') gates on refunds, finance, GDPR, and dev-admin controllers
  - Converter unit test + Testcontainers role-based access integration test
affects: [any future controller adding privileged operations; future role-granularity work beyond admin/user]

# Tech tracking
tech-stack:
  added: []  # no new dependencies — spring-security-test already on classpath
  patterns:
    - "Realm-role -> ROLE_* authority conversion via JwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter"
    - "Class-level @PreAuthorize(\"hasRole('admin')\") gates on privileged controllers (method layer, not authorizeHttpRequests)"
    - "Integration tests exercise the REAL converter via jwt().authorities(new KeycloakRealmRoleConverter()) + realm_access claim"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/security/KeycloakRealmRoleConverter.java
    - core-java/src/test/java/uk/jtoye/core/security/KeycloakRealmRoleConverterTest.java
    - core-java/src/test/java/uk/jtoye/core/security/RoleBasedAccessIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java
    - core-java/src/main/java/uk/jtoye/core/payment/RefundController.java
    - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionController.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/GdprController.java
    - core-java/src/main/java/uk/jtoye/core/tenant/DevTenantController.java
    - core-java/src/test/java/uk/jtoye/core/integration/FinancialTransactionControllerIntegrationTest.java
    - docs/metrics.json
    - CLAUDE.md

key-decisions:
  - "Role checks live at the method layer (@PreAuthorize), not in authorizeHttpRequests — keeps the URL matcher list untouched and gates travel with the controller"
  - "Converter returns empty authorities (never null) when realm_access/roles absent — a token missing the claim is denied, never accidentally granted"
  - "hasRole('admin') maps to authority ROLE_admin with the literal lowercase role name preserved from Keycloak"

patterns-established:
  - "KeycloakRealmRoleConverter is a plain public class (no Spring stereotype) so tests can `new` it and SecurityConfig can instantiate it directly"
  - "Existing full-context controller tests that hit gated endpoints must carry @WithMockUser(roles = \"admin\")"

requirements-completed: [RBAC-P1-1]

# Metrics
duration: ~20min
completed: 2026-07-08
---

# Quick Task 260708-rlp: Issue #83 P1-1 RBAC Method Security Summary

**Keycloak realm_access.roles now map to Spring ROLE_* authorities and application-wide @EnableMethodSecurity gates refunds, the financial ledger, GDPR export/erase, and the dev-admin endpoint behind hasRole('admin') — closing the audit's highest-impact broken-access-control gap.**

## Performance

- **Duration:** ~20 min (includes the full ~8-min Testcontainers integrationTest run)
- **Started:** 2026-07-08T20:00Z
- **Completed:** 2026-07-08T20:18:51+01:00
- **Tasks:** 3
- **Files modified:** 13 (2 main created, 5 main modified, 2 test created, 1 test modified, docs/metrics.json, CLAUDE.md)

## Accomplishments
- Introduced role-based authorization to `core-java` where previously `anyRequest().authenticated()` was the only gate — any authenticated tenant user could issue Stripe refunds, read the full financial ledger, or erase customer PII.
- `KeycloakRealmRoleConverter` maps the Keycloak `realm_access.roles` claim into Spring `ROLE_*` authorities (null-safe, empty-on-absence), wired into the resource server via `JwtAuthenticationConverter`.
- `@EnableMethodSecurity` enabled; class-level `@PreAuthorize("hasRole('admin')")` added to RefundController, FinancialTransactionController, GdprController, and DevTenantController.
- Proven end-to-end: a low-privilege (`user`-only) token receives 403 from refunds/finance/GDPR; an `admin` token passes the gate (200 on finance list, non-403 on GDPR export). Ordinary tenant CRUD (shops/products/orders/customers) remains ungated.
- Full suites green: `:core-java:test` and `:core-java:integrationTest` (96 integration tests, 0 failures, 1 ignored). `docs/metrics.json` regenerated (726 -> 735 logical invocations) and the docs-freshness gate passes.

## Task Commits

Each task was committed atomically:

1. **Task 1: Map realm roles to authorities and enable method security** - `06f7b1f` (feat)
2. **Task 2: Gate refunds, finance, GDPR, and dev-admin endpoints by role** - `3a1f0df` (feat)
3. **Task 3: Role-based access tests + docs metrics refresh** - `2dfce74` (test)

_Note: this plan's implementation (Tasks 1-2) preceded its tests (Task 3), so the TDD Task 3 verified already-built behaviour in the GREEN state rather than authoring failing-first tests. See TDD Gate Compliance below._

## Files Created/Modified
- `core-java/src/main/java/uk/jtoye/core/security/KeycloakRealmRoleConverter.java` - realm_access.roles -> ROLE_* authority mapping, null-safe empty fallback
- `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java` - @EnableMethodSecurity + jwtAuthenticationConverter wiring (JwtTenantFilter ordering and authorizeHttpRequests untouched)
- `core-java/src/main/java/uk/jtoye/core/payment/RefundController.java` - class-level hasRole('admin') gate; Javadoc rewritten (RBAC no longer deferred, supersedes Phase 17 UC-5)
- `core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionController.java` - class-level hasRole('admin') gate
- `core-java/src/main/java/uk/jtoye/core/gdpr/GdprController.java` - class-level hasRole('admin') gate
- `core-java/src/main/java/uk/jtoye/core/tenant/DevTenantController.java` - class-level hasRole('admin') gate (defense-in-depth atop existing @Profile dev/local)
- `core-java/src/test/java/uk/jtoye/core/security/KeycloakRealmRoleConverterTest.java` - 3 unit tests: mapping, absent claim, no-roles-list
- `core-java/src/test/java/uk/jtoye/core/security/RoleBasedAccessIntegrationTest.java` - 6 Testcontainers tests: admin passes finance/GDPR, low-priv 403 on refunds/finance/GDPR
- `core-java/src/test/java/uk/jtoye/core/integration/FinancialTransactionControllerIntegrationTest.java` - @WithMockUser -> @WithMockUser(roles = "admin") on 6 tests (regression fix from the new gate)
- `docs/metrics.json` - regenerated: java_test_methods 527->536, java_test_files 80->82, total_logical_invocations 726->735
- `CLAUDE.md` - Testing prose synced to the regenerated counts

## Decisions Made
- **Method-layer gates over URL matchers:** `@PreAuthorize` on controllers keeps `authorizeHttpRequests` and `JwtTenantFilter` ordering untouched, and the gate travels with the controller rather than a distant config list.
- **Fail-closed converter:** returns an empty authority collection (never null) when `realm_access` or its `roles` list is absent/malformed, so a token from another client is denied, never accidentally granted (threat T-rlp-04).
- **Role checks are additive to RLS/TenantContext:** the `admin` role grants the capability; RLS still bounds every read/write to the caller's tenant. No tenant-scoping logic was touched.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Gradle invocation path corrected**
- **Found during:** Task 1 (and all subsequent verify steps)
- **Issue:** The plan's verify commands use `cd core-java && ./gradlew ...`, but the Gradle wrapper exists only at the repo root — there is no `core-java/gradlew`.
- **Fix:** Ran all Gradle tasks from the repo root using project-scoped tasks (`./gradlew :core-java:compileJava`, `:core-java:test`, `:core-java:integrationTest`), per the environment notes. Set `JAVA_HOME` to the JDK 21 install explicitly for every invocation.
- **Verification:** All tasks resolved and executed; compile/test/integrationTest all green.
- **Committed in:** n/a (invocation only; no file change)

**2. [Rule 1 - Bug] Integration test used pre-prefix endpoint paths**
- **Found during:** Task 3 (RoleBasedAccessIntegrationTest first run — 4/6 failed)
- **Issue:** The plan's `<behavior>` listed paths as `/financial-transactions` and `/gdpr/customers/...`, but `WebConfig.configurePathMatch` adds the `/api/v1` prefix to controllers in the `finance` and `gdpr` packages. The raw paths 404'd (authenticated-but-unmapped), so the low-priv assertions never reached the gate and the admin-not403 assertion passed only vacuously.
- **Fix:** Updated the test to hit the real runtime paths `/api/v1/financial-transactions` and `/api/v1/gdpr/customers/...` (RefundController already hard-codes `/api/v1/orders`). Added an explanatory comment so the prefix rule is not re-broken.
- **Verification:** Re-ran `:core-java:integrationTest --tests '*RoleBasedAccessIntegrationTest'` — 6/6 pass.
- **Committed in:** `2dfce74`

**3. [Rule 3 - Blocking] Existing finance integration tests broke under the new gate**
- **Found during:** Task 3 (regression check before running the full suite)
- **Issue:** `FinancialTransactionControllerIntegrationTest` (full `@SpringBootTest`, method security active) used bare `@WithMockUser` (authority `ROLE_USER`). The new class-level `hasRole('admin')` gate would 403 all 6 of its tests.
- **Fix:** Changed the 6 annotations to `@WithMockUser(roles = "admin")` (authority `ROLE_admin`) to preserve each test's intent while satisfying the gate. The `@WebMvcTest(addFilters=false)` slice tests for Refund/Gdpr do not load method security, so they needed no change (confirmed by the green fast `:core-java:test` run).
- **Verification:** Full `:core-java:integrationTest` — 96 tests, 0 failures, 100%.
- **Committed in:** `2dfce74`

---

**Total deviations:** 3 auto-fixed (1x Rule 1 bug in own test, 2x Rule 3 blocking). 
**Impact on plan:** All auto-fixes were necessary for correctness and for the no-regression success criterion. No scope creep — no production behaviour changed beyond the planned gates.

## TDD Gate Compliance

This plan's Task 3 was flagged `tdd="true"`, but the plan structured the implementation (Tasks 1-2, `feat` commits) ahead of the tests (Task 3, `test` commit). The tests therefore verified already-committed behaviour in the GREEN state rather than following a failing-first RED gate. The tests are nonetheless genuine negative/positive controls (low-priv 403 vs admin pass) and both fail if the gates or converter are removed. No separate RED `test(...)` commit precedes the GREEN `feat(...)` commits because the plan's task ordering placed implementation first.

## Issues Encountered
None beyond the deviations above. The container-teardown "Connection refused" stack trace at the tail of the integrationTest run is a benign post-test Hikari reconnect during Testcontainers shutdown — the build reported SUCCESSFUL (exit 0) with 0 failures.

## Known Stubs
None — all changes are backend authorization logic wired to real behaviour; no placeholder/empty-data patterns introduced.

## User Setup Required
None - no external service configuration required. Keycloak already mints the `realm_access.roles` claim (roles `admin`, `user`); no frontend or IdP change is needed.

## Next Phase Readiness
- P1-1 (broken access control) is closed for the four highest-risk surfaces. Follow-on P1 items (#83 siblings) can proceed.
- If finer-grained roles are needed later (e.g. `vendor-staff` vs `vendor-owner`), the converter already emits every realm role as `ROLE_*`; only new `@PreAuthorize` expressions would be required.

## Self-Check: PASSED

- All 3 created source/test files present on disk.
- All 3 task commits present in git history (`06f7b1f`, `3a1f0df`, `2dfce74`).
- `docs-freshness` gate exits 0 (metrics match source: 735 logical invocations).
- Working tree clean apart from this SUMMARY.md (committed by the orchestrator).

---
*Quick task: 260708-rlp-implement-issue-83-p1-1-rbac-method-secu*
*Completed: 2026-07-08*
