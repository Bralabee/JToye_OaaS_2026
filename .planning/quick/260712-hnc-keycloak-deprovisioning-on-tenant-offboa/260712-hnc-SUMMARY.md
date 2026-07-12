---
phase: quick-260712-hnc
plan: 01
subsystem: auth
tags: [keycloak, oauth2, multi-tenancy, tenant-lifecycle, restclient, flyway, spring-boot]

# Dependency graph
requires:
  - phase: quick-260711-bej / #102 Stripe Connect slice (V48)
    provides: "tenant lifecycle (status/offboard) + TenantAdminController + TenantStatusInterceptor"
provides:
  - "V49 tenants.keycloak_deprovisioned_at marker (stamped only on a full clean sweep)"
  - "KeycloakAdminClient — first Java-side Keycloak admin seam (token/search/disable/logout)"
  - "KeycloakDeprovisionService — best-effort, non-throwing, idempotent, inert-by-default multi-realm sweep"
  - "offboard() after-commit deprovisioning hook (REQUIRES_NEW, non-rolling-back)"
  - "admin re-trigger endpoint POST /api/v1/admin/tenants/{id}/keycloak/deprovision"
  - "env + k8s wiring (inert by default) for all environments"
affects: [tenant-offboarding, keycloak-realm-ops, identity-deprovisioning]

# Tech tracking
tech-stack:
  added: []  # RestClient + MockRestServiceServer already on the Spring Boot 3.5 classpath
  patterns:
    - "RestClient seam + MockRestServiceServer unit test (no Spring context, no live IdP)"
    - "TransactionSynchronization.afterCommit + REQUIRES_NEW for non-rolling-back post-commit side effects"
    - "@ConfigurationProperties feature flag with a configured() guard (inert-by-default)"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V49__tenant_keycloak_deprovisioned_at.sql
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminProperties.java
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClient.java
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminException.java
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionService.java
    - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionResult.java
    - core-java/src/test/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClientTest.java
    - core-java/src/test/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/tenant/TenantOffboardKeycloakHookIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/tenant/TenantLifecycleService.java
    - core-java/src/main/java/uk/jtoye/core/tenant/TenantAdminController.java
    - core-java/src/main/java/uk/jtoye/core/tenant/Tenant.java
    - core-java/src/main/java/uk/jtoye/core/tenant/dto/TenantDto.java
    - core-java/src/main/resources/application.yml
    - .env.example
    - docker-compose.full-stack.yml
    - k8s/base/configmap.yaml
    - k8s/base/core-java-deployment.yaml
    - k8s/{staging,production}/configmap-patch.yaml
    - CLAUDE.md
    - docs/architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md
    - docs/metrics.json
    - docs/api/openapi-snapshot.json

key-decisions:
  - "Controller guard order is OFFBOARDED-first THEN not-configured (deviates from the plan's literal configured-first order) so both failure modes surface as distinct, meaningful 400s even with the feature off — matching the plan's own test intent"
  - "deprovision() uses REQUIRES_NEW: a plain @Transactional write inside afterCommit participates in the already-committed offboard tx and is silently lost; a fresh tx both persists the marker and keeps deprovisioning off the offboard tx"
  - "Customer realm (jtoye-customers) is excluded by default — it has no tenant_id user attributes; vendor realm only"

patterns-established:
  - "RestClient + MockRestServiceServer: unit-test an HTTP admin client with zero Spring context and zero live dependency"
  - "afterCommit + REQUIRES_NEW: correct shape for best-effort, non-rolling-back post-commit side effects"

requirements-completed: ["#102-keycloak-deprovision-on-offboard"]

# Metrics
duration: 42min
completed: 2026-07-12
---

# Quick Task 260712-hnc: Keycloak Deprovisioning on Tenant Offboard Summary

**Offboarding a tenant now disables + logs out its Keycloak users at the IdP (identity-layer deprovisioning), best-effort after the offboard commits and never rolling it back — with an admin re-trigger endpoint, fully inert until configured.**

## Performance

- **Duration:** ~42 min
- **Started:** 2026-07-12T11:52:04Z
- **Completed:** 2026-07-12T12:34:09Z
- **Tasks:** 3 completed
- **Files created/modified:** 9 created, 13 modified

## Accomplishments
- Closed the remaining slice of issue #102: a revoked tenant's users can no longer mint or keep valid tokens — the identity-layer complement to `TenantStatusInterceptor`'s API-layer 403.
- Built the first Java-side Keycloak admin integration (core was a pure OAuth2 resource server): `KeycloakAdminClient` (master-realm token, paginated `tenant_id` search, disable-via-full-rep PUT, session logout) + `KeycloakDeprovisionService` orchestrating a multi-realm sweep that stamps `keycloak_deprovisioned_at` only on full success.
- Wired the sweep into `offboard()` as a best-effort `afterCommit` hook that can never roll back the offboard, plus an admin-gated, OFFBOARDED-only, idempotent re-trigger endpoint for recovery.
- Kept the feature fully inert by default (one WARN no-op + RFC 7807 400 "not configured") and wired the env for docker-compose + all k8s overlays without shipping any password literal.

## Task Commits

Each task was committed atomically:

1. **Task 1: V49 column + config + KeycloakAdminClient** - `cd316f0` (feat)
2. **Task 2: KeycloakDeprovisionService + after-commit hook + re-trigger endpoint** - `3e578d6` (feat)
3. **Task 3: Integration tests + env/k8s wiring + docs + gates** - `97753d9` (test)

_(REQUIRES_NEW correctness fix on KeycloakDeprovisionService was made during Task 3 integration testing and committed in `97753d9`.)_

## Files Created/Modified

**Migration + entity:**
- `V49__tenant_keycloak_deprovisioned_at.sql` — nullable marker column (RLS-free registry, no default: NULL = not yet done)
- `Tenant.java` / `TenantDto.java` — `keycloakDeprovisionedAt` field + hand-mapping

**Keycloak integration (new package `tenant/keycloak`):**
- `KeycloakAdminProperties.java` — `@ConfigurationProperties(jtoye.keycloak.admin)`, redacted toString, `configured()` guard
- `KeycloakAdminClient.java` — RestClient seam; wraps non-2xx into `KeycloakAdminException`, never logs token/password
- `KeycloakDeprovisionService.java` — non-throwing, idempotent, inert-by-default, `REQUIRES_NEW`
- `KeycloakDeprovisionResult.java` — result record (tenantId, usersDisabled, complete, deprovisionedAt)

**Lifecycle wiring:**
- `TenantLifecycleService.java` — after-commit hook in `offboard()`, updated javadoc
- `TenantAdminController.java` — re-trigger endpoint, updated offboard description

**Config/env/k8s:** `application.yml`, `.env.example`, `docker-compose.full-stack.yml`, `k8s/base/configmap.yaml`, `k8s/base/core-java-deployment.yaml`, `k8s/{staging,production}/configmap-patch.yaml`

**Docs/gates:** `CLAUDE.md` (schema → V49), `ADR-0001` (impl note), `docs/metrics.json`, `docs/api/openapi-snapshot.json`

## Decisions Made
- **Controller guard order (OFFBOARDED-first, then not-configured):** makes the ACTIVE re-trigger case genuinely exercise the not-OFFBOARDED branch and the OFFBOARDED+disabled case exercise the not-configured branch — both distinct 400s even with the feature off. See Deviations.
- **`REQUIRES_NEW` on `deprovision()`:** required so the marker write survives when invoked from `afterCommit`.
- **Realm allow-list, customer realm excluded:** users are selected strictly by `tenant_id` attribute across a configured realm allow-list (STRIDE T-kc-04).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Correctness] `deprovision()` needed `REQUIRES_NEW`, not plain `@Transactional`**
- **Found during:** Task 3 (writing `TenantOffboardKeycloakHookIntegrationTest`)
- **Issue:** The plan specified `@Transactional deprovision(...)`. Invoked from the offboard `afterCommit` hook, a plain (REQUIRED) transactional method PARTICIPATES in the just-committed offboard transaction (its synchronization is still active during `afterCommit`), so the marker `save()` is never committed — the success test's "marker NON-NULL" assertion would fail.
- **Fix:** Changed to `@Transactional(propagation = REQUIRES_NEW)` so deprovisioning runs in a fresh, independent transaction that persists the marker and stays fully off the offboard tx.
- **Files modified:** `KeycloakDeprovisionService.java`
- **Verification:** `TenantOffboardKeycloakHookIntegrationTest.hookInvokedAfterCommit_stampsMarker_onCleanSweep` green (marker non-null after commit).
- **Committed in:** `97753d9`

**2. [Rule 1 - Correctness] Controller guard order swapped to OFFBOARDED-first**
- **Found during:** Task 2 (re-trigger endpoint) / reconciled against Task 3 test intent
- **Issue:** The plan's action text listed the `not configured()` check before the `not OFFBOARDED` check. But the plan's own test `retrigger_activeTenant_is400` (comment: "not OFFBOARDED") runs with the feature DISABLED — configured-first would return "not configured" and never exercise the not-OFFBOARDED branch, making the test name inaccurate.
- **Fix:** Check OFFBOARDED-only first, then `configured()`. Both failure modes now surface as distinct, meaningful 400s regardless of feature state; both tests exercise their named branch.
- **Files modified:** `TenantAdminController.java`
- **Verification:** `retrigger_activeTenant_is400_notOffboarded` (body contains "OFFBOARDED") and `retrigger_offboardedButFeatureDisabled_notConfigured400` (body contains "not configured") both green.
- **Committed in:** `3e578d6`

**3. [Rule 3 - Blocking] `TenantLifecycleServiceTest` constructor call**
- **Found during:** Task 2
- **Issue:** Adding `KeycloakDeprovisionService` to the `TenantLifecycleService` constructor broke the existing unit test's `new TenantLifecycleService(tenantRepository)` call (compile error).
- **Fix:** Added a `@Mock KeycloakDeprovisionService` and passed it to the constructor (the after-commit hook does not fire in a plain unit test — no active tx synchronization — so no stubbing needed).
- **Files modified:** `TenantLifecycleServiceTest.java`
- **Verification:** `:core-java:test` green (12/12 in that class).
- **Committed in:** `3e578d6`

**4. [Rule 2 - Completeness] `KeycloakAdminException` supporting class**
- **Found during:** Task 1
- **Issue:** The plan's action called for wrapping non-2xx into a "clear `KeycloakAdminException` (new RuntimeException subclass in the same package)" but did not enumerate the file.
- **Fix:** Added `KeycloakAdminException.java` (realm/operation context, never token/password).
- **Committed in:** `cd316f0`

---

**Total deviations:** 4 auto-fixed (2× Rule 1, 1× Rule 2, 1× Rule 3)
**Impact on plan:** All necessary for correctness/consistency. No scope creep — the plan's stated behaviours and success criteria are all met; the guard-order and REQUIRES_NEW fixes make the plan's own tests pass as intended.

## Issues Encountered
- **Full `:core-java:integrationTest` suite (38 Testcontainers classes) exceeds the local time budget** (~25 min; would be cut by the 20-min guard). Verified instead with a targeted integration run of the three directly-affected classes plus the full fast unit suite. My changes are additive and backward-compatible (a nullable column + new `@Service`/`@Component` beans + a regenerated OpenAPI snapshot), so no other integration class is affected. See Testing.

## Testing

- `:core-java:test` (fast unit suite): **green, 0 failures** — includes new `KeycloakAdminClientTest` (5) and `KeycloakDeprovisionServiceTest` (4).
- Targeted `:core-java:integrationTest` (Testcontainers Postgres): **all green, 0 failures**
  - `OpenApiSnapshotTest` (1) — regenerated snapshot matches (new `/keycloak/deprovision` endpoint present)
  - `TenantLifecycleAdminIntegrationTest` (10 = 6 original + 4 new)
  - `TenantOffboardKeycloakHookIntegrationTest` (2 new) — after-commit marker stamp + no-rollback-on-failure
- `bash scripts/docs-freshness.sh`: **exit 0** (java tests 834→849, schema 48→49, total 1166→1181).
- No new Gradle dependencies (`build.gradle.kts` unchanged).

## User Setup Required

**To ENABLE the feature in a target environment** (it ships OFF/inert; live E2E is the orchestrator's follow-up):
- Set `KC_ADMIN_ENABLED=true`.
- Set `KC_ADMIN_BASE_URL` to the IN-CLUSTER Keycloak admin host reachable from core (e.g. `http://keycloak:8080`), NOT the public `localhost:8085`.
- Confirm `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` (already in `.env` / the `keycloak-credentials` Secret) reach the core service.
- Optionally set `KC_DEPROVISION_REALMS` (default `jtoye-dev`; overlays set `jtoye-staging` / `jtoye-prod`).

## Next Phase Readiness
- Ready for the orchestrator's live E2E enablement on the dev stack (enable the flag, offboard a test tenant, confirm the tenant's Keycloak users are disabled + `keycloak_deprovisioned_at` stamped).
- No blockers. The full 38-class integration suite should be run in CI (the "Integration Tests" job) where the time budget is not constrained.

## Self-Check: PASSED

All 9 created source files + the SUMMARY exist on disk; all 3 task commits (`cd316f0`, `3e578d6`, `97753d9`) are present in git history.

---
*Quick task: 260712-hnc-keycloak-deprovisioning-on-tenant-offboa*
*Completed: 2026-07-12*
