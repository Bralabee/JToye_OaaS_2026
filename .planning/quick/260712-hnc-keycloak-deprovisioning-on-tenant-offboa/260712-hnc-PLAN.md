---
phase: quick-260712-hnc
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - core-java/src/main/resources/db/migration/V49__tenant_keycloak_deprovisioned_at.sql
  - core-java/src/main/java/uk/jtoye/core/tenant/Tenant.java
  - core-java/src/main/java/uk/jtoye/core/tenant/dto/TenantDto.java
  - core-java/src/main/resources/application.yml
  - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminProperties.java
  - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClient.java
  - core-java/src/test/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClientTest.java
  - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionService.java
  - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionResult.java
  - core-java/src/main/java/uk/jtoye/core/tenant/TenantLifecycleService.java
  - core-java/src/main/java/uk/jtoye/core/tenant/TenantAdminController.java
  - core-java/src/test/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/tenant/TenantLifecycleAdminIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/tenant/TenantOffboardKeycloakHookIntegrationTest.java
  - .env.example
  - docker-compose.full-stack.yml
  - k8s/base/configmap.yaml
  - CLAUDE.md
  - docs/architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md
  - docs/metrics.json
  - docs/api/openapi-snapshot.json
autonomous: true
requirements:
  - "#102-keycloak-deprovision-on-offboard"

user_setup:
  - service: keycloak-admin
    why: "Live enablement of user-disable-on-offboard (feature ships inert; default disabled)"
    env_vars:
      - name: KC_ADMIN_ENABLED
        source: "Set true in the target env to activate the sweep (default false = inert)"
      - name: KC_ADMIN_BASE_URL
        source: "In-cluster Keycloak base URL reachable from core (e.g. http://keycloak:8080) — NOT the public localhost:8085 host"
    dashboard_config:
      - task: "Confirm KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD (already in .env) are reachable by the core service after wiring"
        location: "Deployment env / .env"

must_haves:
  truths:
    - "Offboarding a tenant with the feature enabled disables that tenant's Keycloak users (they can no longer mint tokens)"
    - "Offboarding never fails or rolls back when Keycloak is unreachable — the tenant reaches OFFBOARDED, the marker stays NULL, an ERROR is logged"
    - "tenants.keycloak_deprovisioned_at is stamped only when ALL of the tenant's users are disabled successfully"
    - "An admin can re-trigger deprovisioning via POST /api/v1/admin/tenants/{id}/keycloak/deprovision for an OFFBOARDED tenant; re-running is idempotent"
    - "With the feature disabled (default), offboarding stays clean (marker NULL, 200) and re-trigger returns a clear RFC 7807 400 'not configured'"
    - "The re-trigger endpoint is admin-gated (user role -> 403) and rejects non-OFFBOARDED tenants with 400"
  artifacts:
    - path: "core-java/src/main/resources/db/migration/V49__tenant_keycloak_deprovisioned_at.sql"
      provides: "keycloak_deprovisioned_at nullable TIMESTAMPTZ column on tenants"
      contains: "keycloak_deprovisioned_at"
    - path: "core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClient.java"
      provides: "RestClient-based Keycloak admin seam: token, paginated attribute search, disable, logout"
      min_lines: 60
    - path: "core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionService.java"
      provides: "Multi-realm disable+logout orchestration, marker stamping, idempotent, disabled no-op"
      min_lines: 50
    - path: "core-java/src/main/java/uk/jtoye/core/tenant/TenantAdminController.java"
      provides: "POST /{id}/keycloak/deprovision re-trigger endpoint"
      contains: "keycloak/deprovision"
  key_links:
    - from: "core-java/src/main/java/uk/jtoye/core/tenant/TenantLifecycleService.java"
      to: "KeycloakDeprovisionService.deprovision"
      via: "TransactionSynchronization afterCommit (best-effort, non-rolling-back)"
      pattern: "registerSynchronization"
    - from: "core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionService.java"
      to: "KeycloakAdminClient"
      via: "search by tenant_id attribute + setEnabled(false) + logout per realm"
      pattern: "keycloakAdminClient\\."
    - from: "core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionService.java"
      to: "tenantRepository"
      via: "stamp keycloak_deprovisioned_at only when all users disabled"
      pattern: "keycloakDeprovisionedAt|KeycloakDeprovisionedAt"
---

<objective>
Close the remaining slice of issue #102: when a tenant is offboarded, disable
their Keycloak users so revoked tenants can no longer mint valid tokens. Today
only the API-layer TenantStatusInterceptor 403s offboarded traffic — a stolen
or cached token still validates at the IdP. This plan adds a Keycloak admin
integration (greenfield — core is a pure OAuth2 resource server today) that
disables + logs out a tenant's users across configured realms, driven
best-effort after the offboard transaction commits, plus an admin re-trigger
endpoint for recovery.

Purpose: Real deprovisioning at the identity layer, not just request rejection.
Output: V49 column, KeycloakAdminClient + KeycloakDeprovisionService, after-commit
offboard hook, admin re-trigger endpoint, config namespace (inert by default),
env wiring, unit + integration tests, docs.

The feature is FULLY INERT when disabled (default): the service logs one WARN
and no-ops; the re-trigger endpoint returns an RFC 7807 400 "not configured".
Suspend/reactivate are untouched. No new Gradle dependencies (RestClient +
MockRestServiceServer are already on the classpath). No Keycloak testcontainer —
live E2E happens post-merge on the dev stack.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@CLAUDE.md

<interfaces>
<!-- Key contracts the executor needs. Extracted from the codebase — use directly, no exploration required. -->

TenantLifecycleService.offboard (core-java/.../tenant/TenantLifecycleService.java L130-141):
- @Transactional; require() -> assertTransition(OFFBOARDED from ACTIVE|SUSPENDED)
  -> setStatus(OFFBOARDED) + setOffboardedAt(now) -> tenantRepository.save
  -> evictStatus(tenantId) -> log.warn("event=tenant_offboarded tenant={}").
- Constructor today: TenantLifecycleService(TenantRepository). Add
  KeycloakDeprovisionService as a second constructor arg.
- The offboard javadoc (L123-129) currently documents Keycloak deprovisioning as
  a follow-up — UPDATE that javadoc to say it now runs after-commit.

TenantAdminController (core-java/.../tenant/TenantAdminController.java):
- class @RequestMapping("/api/v1/admin/tenants"), @PreAuthorize("hasRole('admin')"),
  @SecurityRequirement(name="bearer-jwt"), @Tag("Tenant Admin").
- Constructor today: (TenantLifecycleService, StripeConnectService). Add
  KeycloakDeprovisionService as a third arg for the re-trigger endpoint.
- Existing endpoints use ResponseEntity.ok(...) + @Operation/@ApiResponses.

Tenant entity (core-java/.../tenant/Tenant.java):
- @Entity @Table(name="tenants"); OffsetDateTime timestamp columns use
  @Column(name="...") with getter/setter pairs (e.g. offboardedAt at L66-67,105-106).
  No @ColumnDefault needed for a nullable column.

TenantDto (core-java/.../tenant/dto/TenantDto.java):
- record; static from(Tenant t) hand-maps every field. Add the new nullable
  OffsetDateTime as the last record component + last from() arg.

GlobalExceptionHandler (core-java/.../common/GlobalExceptionHandler.java):
- InvalidStateTransitionException -> 400 (L50-52); IllegalStateException -> 400
  (L84-86); ResourceNotFoundException -> 404 (L42-44). All emit RFC 7807
  ProblemDetail. The Stripe "not configured" precedent throws
  IllegalStateException -> 400 (StripeConnectService L75) with message containing
  "not configured" — MATCH this for the Keycloak not-configured case (400).

Keycloak admin REST shape (from infra/keycloak/configure-keycloak.sh — the only
existing admin caller, DO NOT modify that script):
- Token: POST {baseUrl}/realms/master/protocol/openid-connect/token, form-encoded
  grant_type=password & client_id=admin-cli & username & password -> {access_token}.
- Search by attribute (Keycloak 24): GET {baseUrl}/admin/realms/{realm}/users?q=tenant_id:{uuid}&first={n}&max={m}
  -> JSON array of full UserRepresentation objects (paginate; page size 100).
- Disable: PUT {baseUrl}/admin/realms/{realm}/users/{id} with the FULL user
  representation carrying "enabled": false (take the rep from search, flip enabled, PUT it back).
- Session revocation: POST {baseUrl}/admin/realms/{realm}/users/{id}/logout.
- All /admin calls carry Authorization: Bearer {token}.

Config precedent (application.yml): jtoye: namespace at L96; every value is
${ENV:default}; secrets default EMPTY. Follow OnboardingProperties for the
@ConfigurationProperties registration pattern (grep existing
@ConfigurationProperties/@EnableConfigurationProperties usage).

Test seams:
- RestClient.Builder is auto-configured by Spring Boot 3.5 (inject it).
  MockRestServiceServer.bindTo(RestClient.Builder) binds a mock HTTP server to it
  (Spring 6.2 on classpath — no new dependency).
- Integration test precedent: TenantLifecycleAdminIntegrationTest (@SpringBootTest
  + @AutoConfigureMockMvc + @Testcontainers + @Tag("testcontainers"), Flyway,
  real KeycloakRealmRoleConverter via adminJwt(tenant)/userJwt(tenant) post-processors).
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: V49 column + config + KeycloakAdminClient (Keycloak admin seam)</name>
  <files>core-java/src/main/resources/db/migration/V49__tenant_keycloak_deprovisioned_at.sql, core-java/src/main/java/uk/jtoye/core/tenant/Tenant.java, core-java/src/main/java/uk/jtoye/core/tenant/dto/TenantDto.java, core-java/src/main/resources/application.yml, core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminProperties.java, core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClient.java, core-java/src/test/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClientTest.java</files>
  <behavior>
    - obtainAdminToken(): POSTs the master-realm password grant (grant_type=password, client_id=admin-cli, username, password) and returns access_token.
    - searchUsersByTenant(realm, tenantId, token): pages GET .../users?q=tenant_id:{uuid}&first&max (page size 100) until a page shorter than the page size returns; concatenates all UserRepresentations. A single 100-item page followed by a 5-item page yields 105 users and two GET calls.
    - setUserEnabled(realm, userRep, false, token): PUTs the full representation back with enabled=false to .../users/{id}.
    - logoutUser(realm, userId, token): POSTs to .../users/{id}/logout.
    - A 5xx from any call propagates as an exception (no silent swallow at the client layer).
    - Authorization: Bearer {token} present on every /admin call.
  </behavior>
  <action>
V49 migration (follow V48 house style — forward-only, idempotent DDL): create
`V49__tenant_keycloak_deprovisioned_at.sql` with a header comment (issue #102
remainder: disable-users-on-offboard) and `ALTER TABLE tenants ADD COLUMN IF NOT
EXISTS keycloak_deprovisioned_at TIMESTAMPTZ;`. Nullable, NO default (NULL = not
yet deprovisioned). tenants stays RLS-free — no policy changes. V49 is the next
free slot (V48 is latest on disk); spring.flyway.out-of-order=true is already set.

Tenant entity: add `@Column(name="keycloak_deprovisioned_at") private
OffsetDateTime keycloakDeprovisionedAt;` with getter/setter, mirroring the
offboardedAt field (L66-67,105-106). No @ColumnDefault (nullable).

TenantDto: add `OffsetDateTime keycloakDeprovisionedAt` as the LAST record
component and the LAST arg passed in from(Tenant t) as t.getKeycloakDeprovisionedAt().

application.yml: add a `jtoye.keycloak.admin` block under the existing `jtoye:`
namespace (L96). Keys, all ${ENV:default}, secrets default empty:
enabled: ${KC_ADMIN_ENABLED:false}; base-url: ${KC_ADMIN_BASE_URL:};
realms: ${KC_DEPROVISION_REALMS:jtoye-dev} (comma-separated -> List<String>;
default vendor realm only — the customer realm jtoye-customers has no tenant_id
attributes so it is deliberately excluded); username: ${KEYCLOAK_ADMIN:admin};
password: ${KEYCLOAK_ADMIN_PASSWORD:}. Add a comment: feature is inert unless
enabled=true AND base-url set; disabling users is the identity-layer complement
to TenantStatusInterceptor's request rejection.

KeycloakAdminProperties: `@ConfigurationProperties(prefix="jtoye.keycloak.admin")`
POJO/record with boolean enabled, String baseUrl, List<String> realms, String
username, String password. Register it exactly how OnboardingProperties is
registered (grep for @ConfigurationProperties / @EnableConfigurationProperties /
@ConfigurationPropertiesScan and follow the same mechanism). Add a
`configured()` helper returning enabled && baseUrl is non-blank && password non-blank.

KeycloakAdminClient (@Component): constructor injects RestClient.Builder (Boot
auto-configures it) + KeycloakAdminProperties + ObjectMapper; build a RestClient
with baseUrl = properties.baseUrl() in the constructor. Implement the four
behaviors above. Use Jackson ObjectNode (mutable) or JsonNode for the user
representation so the full rep is preserved on the disable PUT (flip only
enabled). Never log the token or password. Pagination: loop first=0,100,200...
until a returned page size < 100. Wrap non-2xx into a clear
KeycloakAdminException (new RuntimeException subclass in the same package) with
realm/operation context — do NOT map to any HTTP status here (the client is a
low-level seam; the service decides best-effort behavior).

KeycloakAdminClientTest (JUnit, NO Spring context): build a RestClient.Builder,
bind MockRestServiceServer.bindTo(builder), pass the builder into a
KeycloakAdminClient constructed with test KeycloakAdminProperties. Assert:
(a) token request hits /realms/master/.../token with a form body containing
grant_type=password and client_id=admin-cli, respond {"access_token":"tok"};
(b) paginated search — expect GET .../users?q=tenant_id:{uuid}&first=0&max=100
returning a 100-element array then first=100 returning a 5-element array; assert
105 users and both expectations consumed; (c) setUserEnabled PUTs to
.../users/{id} with a body where enabled=false, respond 204; (d) logoutUser POSTs
.../users/{id}/logout, respond 204; (e) a 500 response causes the client method
to throw. Verify Authorization: Bearer on the /admin calls.
  </action>
  <verify>
    <automated>./gradlew :core-java:compileJava :core-java:compileTestJava -q && ./gradlew :core-java:test --tests "uk.jtoye.core.tenant.keycloak.KeycloakAdminClientTest" -q</automated>
  </verify>
  <done>V49 migration + Tenant/TenantDto column exist; jtoye.keycloak.admin config block added (inert by default); KeycloakAdminProperties bound; KeycloakAdminClient implements token/search/disable/logout against the RestClient seam; KeycloakAdminClientTest green via MockRestServiceServer (pagination, PUT enabled=false, logout POST, error propagation).</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: KeycloakDeprovisionService + after-commit offboard hook + re-trigger endpoint</name>
  <files>core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionService.java, core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionResult.java, core-java/src/main/java/uk/jtoye/core/tenant/TenantLifecycleService.java, core-java/src/main/java/uk/jtoye/core/tenant/TenantAdminController.java, core-java/src/test/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionServiceTest.java</files>
  <behavior>
    - deprovision(tenantId), feature ENABLED, two realms configured, users found in each: obtains one token, searches each realm, disables + logs out every user, stamps tenants.keycloak_deprovisioned_at (only if currently NULL), returns KeycloakDeprovisionResult with usersDisabled=total, complete=true, deprovisionedAt set.
    - Partial failure (one disable throws): remaining work aborts for that run, marker STAYS NULL, ERROR logged, returns complete=false with the count disabled so far — never rethrows to the caller.
    - Feature DISABLED (default): logs a single WARN (guarded so it warns once), makes NO Keycloak calls, marker stays NULL, returns complete=false / usersDisabled=0 (a no-op result).
    - Idempotent: if keycloak_deprovisioned_at is already set, does not re-stamp (returns the existing timestamp); re-running the disable sweep is harmless (disabling an already-disabled user is a no-op PUT).
    - offboard(): after the tx commits, deprovision(tenantId) is invoked exactly once; a thrown Keycloak error does NOT roll back or fail the offboard (tenant reaches OFFBOARDED regardless).
  </behavior>
  <action>
KeycloakDeprovisionResult (record, tenant.keycloak package): fields UUID tenantId,
int usersDisabled, boolean complete, OffsetDateTime deprovisionedAt. Used by both
the service and the controller.

KeycloakDeprovisionService (@Service): constructor injects KeycloakAdminClient,
KeycloakAdminProperties, TenantRepository. Method `@Transactional
KeycloakDeprovisionResult deprovision(UUID tenantId)`:
- If !properties.configured(): log ONE WARN (guard with an AtomicBoolean warnedOnce
  so repeated offboards don't spam), return no-op result (complete=false, count 0).
- Load the tenant; if keycloak_deprovisioned_at already set, return the existing
  timestamp result WITHOUT calling Keycloak (idempotent short-circuit) — complete=true.
- Otherwise obtain one admin token; for each configured realm: searchUsersByTenant,
  then for each user setUserEnabled(false) + logoutUser. Count disabled users.
  Wrap the whole sweep so any KeycloakAdminException aborts the run, logs
  ERROR (event=tenant_keycloak_deprovision_failed tenant={} realm={}), and
  returns complete=false WITHOUT stamping the marker and WITHOUT rethrowing.
- Only when every configured realm's sweep completed with no error: stamp
  tenant.setKeycloakDeprovisionedAt(now), save, log INFO
  (event=tenant_keycloak_deprovisioned tenant={} usersDisabled={}), return
  complete=true. This method must NEVER throw (best-effort contract) — the only
  exception it may propagate is the not-OFFBOARDED / not-configured guard used by
  the re-trigger endpoint (see below), so put the state/config guards in the
  controller path, keeping deprovision() itself non-throwing for the hook.
  (Concretely: deprovision() is the non-throwing worker; the controller checks
  OFFBOARDED + configured() and throws the 400s before delegating.)

TenantLifecycleService: add KeycloakDeprovisionService to the constructor. In
offboard(), after tenantRepository.save + evictStatus, register a
TransactionSynchronization via TransactionSynchronizationManager whose
afterCommit() calls keycloakDeprovisionService.deprovision(tenantId) inside a
try/catch that logs ERROR on any Throwable (belt-and-braces — deprovision() is
already non-throwing). This runs OUTSIDE the offboard tx, so a Keycloak failure
cannot roll back the offboard. Update the offboard javadoc (L123-129): Keycloak
deprovisioning now runs best-effort after-commit; enforcement via
isRequestBlocked remains the synchronous guarantee. Do NOT touch suspend/reactivate.

TenantAdminController: add KeycloakDeprovisionService to the constructor and a
new endpoint `POST /{tenantId}/keycloak/deprovision` (admin-gated by the existing
class-level @PreAuthorize):
- Load tenant (404 via lifecycleService.get if missing — reuse existing not-found path).
- If not configured(): throw new IllegalStateException("Keycloak admin is not
  configured — cannot deprovision users") -> 400 (matches the Stripe not-configured
  precedent; message contains "not configured").
- If tenant.status != OFFBOARDED: throw new InvalidStateTransitionException(
  "Tenant must be OFFBOARDED to deprovision Keycloak users (was <status>)") -> 400.
- Else return ResponseEntity.ok(keycloakDeprovisionService.deprovision(tenantId))
  (KeycloakDeprovisionResult JSON: tenantId, usersDisabled, complete,
  deprovisionedAt). Add @Operation/@ApiResponses (200 result; 400 not-configured
  or not-OFFBOARDED; 403 non-admin; 404 not found). Keep the loaded-tenant status
  read consistent with the lifecycle service (read via a get() DTO or repository —
  do not bypass the not-found path).

KeycloakDeprovisionServiceTest (Mockito, NO Spring context): mock
KeycloakAdminClient + TenantRepository, supply real KeycloakAdminProperties
instances. Cases: (a) two realms, users in each -> disable+logout invoked per user,
marker stamped once, complete=true, usersDisabled = sum; (b) a disable throws ->
marker NOT stamped (verify repository.save not called with a timestamp / marker
stays null), complete=false, method returns (does not throw); (c) properties
disabled -> client never touched, WARN-once, complete=false, count 0; (d) marker
already set -> no Keycloak calls, returns existing timestamp (idempotent).
  </action>
  <verify>
    <automated>./gradlew :core-java:test --tests "uk.jtoye.core.tenant.keycloak.KeycloakDeprovisionServiceTest" -q</automated>
  </verify>
  <done>KeycloakDeprovisionService orchestrates multi-realm disable+logout, stamps the marker only on full success, is idempotent, no-ops when disabled, and never throws from deprovision(); offboard registers a non-rolling-back after-commit hook; re-trigger endpoint is admin-gated, OFFBOARDED-only (else 400), not-configured -> 400; unit tests green.</done>
</task>

<task type="auto">
  <name>Task 3: Integration tests + env wiring + docs + freshness/snapshot gates</name>
  <files>core-java/src/test/java/uk/jtoye/core/tenant/TenantLifecycleAdminIntegrationTest.java, core-java/src/test/java/uk/jtoye/core/tenant/TenantOffboardKeycloakHookIntegrationTest.java, .env.example, docker-compose.full-stack.yml, k8s/base/configmap.yaml, CLAUDE.md, docs/architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md, docs/metrics.json, docs/api/openapi-snapshot.json</files>
  <action>
Extend TenantLifecycleAdminIntegrationTest (feature stays DISABLED = default, so
the REAL KeycloakDeprovisionService exercises the inert path):
- featureDisabled_offboard_staysClean: offboard a tenant via the admin API ->
  200, status OFFBOARDED, and assert `SELECT keycloak_deprovisioned_at FROM
  tenants WHERE id=?` is NULL (the after-commit hook ran the no-op service).
- retrigger_requiresAdmin: POST /{id}/keycloak/deprovision with userJwt -> 403.
- retrigger_activeTenant_is400: create (ACTIVE) tenant, POST re-trigger with
  adminJwt -> 400 (not OFFBOARDED).
- retrigger_offboardedButDisabled_notConfigured400: offboard, then POST re-trigger
  with adminJwt -> 400 with body containing "not configured".
Reuse the existing adminJwt/userJwt post-processors and ADMIN_TENANT seed.

Add TenantOffboardKeycloakHookIntegrationTest (new @SpringBootTest +
@AutoConfigureMockMvc + @Testcontainers + @Tag("testcontainers"), feature ENABLED
via @TestPropertySource: jtoye.keycloak.admin.enabled=true,
jtoye.keycloak.admin.base-url=http://localhost:1 [unreachable-on-purpose is fine
for the failure case], realms=jtoye-dev). @MockBean the KeycloakAdminClient (NOT
the service — the real KeycloakDeprovisionService must run):
- hookInvokedAfterCommit_success: stub the client (token, empty-or-small user
  list, disable/logout no-op) -> offboard via API -> 200 OFFBOARDED -> assert
  keycloak_deprovisioned_at is NON-NULL (marker stamped after commit) and verify
  the client was invoked.
- keycloakFailure_doesNotRollBackOffboard: stub the client to throw on token/search
  -> offboard via API -> still 200 + status OFFBOARDED in DB, and
  keycloak_deprovisioned_at NULL (best-effort, non-rolling-back). Use a distinct
  tenant per test; never leave the shared seed tenant offboarded.

Env wiring — mirror the existing KEYCLOAK_ADMIN/KEYCLOAK_ADMIN_PASSWORD pattern.
First grep each target for existing KEYCLOAK_ADMIN references, then add the new
core-service keys KC_ADMIN_ENABLED (default false), KC_ADMIN_BASE_URL,
KC_DEPROVISION_REALMS and ensure KEYCLOAK_ADMIN/KEYCLOAK_ADMIN_PASSWORD reach the
core service:
- .env.example: add the three new keys with inert defaults + a one-line comment
  (feature off by default; base-url must be the in-cluster host, not localhost:8085).
- docker-compose.full-stack.yml: pass KC_ADMIN_ENABLED / KC_ADMIN_BASE_URL /
  KC_DEPROVISION_REALMS / KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD into the CORE
  service environment (they are currently only on the keycloak container).
- k8s/base/configmap.yaml: add the non-secret keys (KC_ADMIN_ENABLED,
  KC_ADMIN_BASE_URL, KC_DEPROVISION_REALMS). Grep k8s/staging + k8s/production for
  existing Keycloak configmap keys; if those overlays carry Keycloak config, add
  matching patch entries there too (leave the admin PASSWORD to the existing
  secret mechanism — never add a password literal to a configmap).

Docs + gates (run in this order):
- CLAUDE.md: add a V49 line to the schema-version narrative (the "Current schema
  version: V48..." paragraph) describing keycloak_deprovisioned_at + the
  disable-on-offboard feature; bump the "Current schema version: V49".
- ADR-0001 (docs/architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md): add an implementation
  note that Keycloak user deprovisioning on offboard is now implemented
  (after-commit best-effort, admin re-trigger endpoint), closing the #102 follow-up.
- OpenAPI snapshot: a new endpoint was added — run `./gradlew
  :core-java:updateOpenApiSnapshot` (if the task name differs, discover it via
  `./gradlew :core-java:tasks --all | grep -i openapi`) and commit the regenerated
  snapshot.
- Metrics freshness: new @Test methods were added — run `scripts/docs-freshness.sh
  --write` to update docs/metrics.json, then re-run without --write to confirm the
  gate passes.
  </action>
  <verify>
    <automated>bash scripts/docs-freshness.sh && ./gradlew :core-java:test -q && ./gradlew :core-java:integrationTest -q</automated>
  </verify>
  <done>Integration tests cover: feature-disabled offboard stays clean (marker NULL, 200); re-trigger admin gate (user 403), ACTIVE -> 400, OFFBOARDED+disabled -> not-configured 400; after-commit hook stamps the marker on success and does NOT roll back offboard on Keycloak failure. Env keys wired into .env.example + docker-compose core service + k8s configmap (+ overlays if applicable). CLAUDE.md V49 line + ADR-0001 note added. OpenAPI snapshot regenerated. docs-freshness green. :core-java:test and :core-java:integrationTest both pass.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| core-java -> Keycloak admin REST | Privileged master-realm admin credentials + bearer token cross here to disable/logout users |
| admin client -> re-trigger endpoint | Admin JWT crosses; the endpoint can disable a tenant's users |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-kc-01 | Information Disclosure | KeycloakAdminProperties / KeycloakAdminClient | mitigate | Password + token default empty, env-injected, never logged; client logs realm/operation context only, never credentials or the bearer token |
| T-kc-02 | Elevation of Privilege | re-trigger endpoint | mitigate | Inherits class-level @PreAuthorize("hasRole('admin')"); integration test asserts user role -> 403 |
| T-kc-03 | Denial of Service (availability) | offboard hook | mitigate | Deprovision runs after-commit, best-effort, non-throwing; Keycloak outage never rolls back or fails offboard (marker stays NULL, ERROR logged) — integration test proves no rollback |
| T-kc-04 | Tampering / wrong-scope disable | KeycloakDeprovisionService | mitigate | Users selected strictly by tenant_id attribute AND a configured realm allow-list; customer realm (no tenant_id attrs) excluded by default; only OFFBOARDED tenants deprovisionable via the endpoint |
| T-kc-05 | Repudiation | KeycloakDeprovisionService | mitigate | keycloak_deprovisioned_at timestamp + structured events (event=tenant_keycloak_deprovisioned / _failed) provide an audit trail |

No package-manager installs in this plan (no new Gradle/npm/pip deps: RestClient +
MockRestServiceServer are already on the classpath) — no supply-chain (T-*-SC)
threat or legitimacy checkpoint applies.
</threat_model>

<verification>
- ./gradlew :core-java:test passes (new unit tests: KeycloakAdminClientTest, KeycloakDeprovisionServiceTest + existing suite green)
- ./gradlew :core-java:integrationTest passes (extended TenantLifecycleAdminIntegrationTest + new TenantOffboardKeycloakHookIntegrationTest)
- bash scripts/docs-freshness.sh exits 0 (docs/metrics.json regenerated for the new @Test methods)
- OpenAPI snapshot regenerated and committed (new /keycloak/deprovision endpoint)
- Feature is inert by default: no behavior change unless KC_ADMIN_ENABLED=true + KC_ADMIN_BASE_URL set
- No new Gradle dependencies added (verify build.gradle.kts diff is empty)
</verification>

<success_criteria>
- Offboarding a tenant with the feature enabled disables + logs out that tenant's Keycloak users across configured realms
- Offboard never rolls back on Keycloak failure; the marker is stamped only on full success
- Admin re-trigger endpoint: OFFBOARDED-only (400 otherwise), admin-gated (403 otherwise), not-configured -> 400, idempotent, returns count + timestamp
- Feature fully inert when disabled (default): service no-ops with one WARN, endpoint returns RFC 7807 400 "not configured"
- Env keys wired into .env.example, docker-compose core service, and k8s configmap; docs (CLAUDE.md V49, ADR-0001) updated; freshness + OpenAPI snapshot gates green
- Java tests pass: :core-java:test then :core-java:integrationTest (run once, at the end)
</success_criteria>

<output>
Create `.planning/quick/260712-hnc-keycloak-deprovisioning-on-tenant-offboa/260712-hnc-SUMMARY.md` when done
</output>
