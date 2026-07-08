---
phase: quick-260708-rlp
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - core-java/src/main/java/uk/jtoye/core/security/KeycloakRealmRoleConverter.java
  - core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java
  - core-java/src/main/java/uk/jtoye/core/payment/RefundController.java
  - core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionController.java
  - core-java/src/main/java/uk/jtoye/core/gdpr/GdprController.java
  - core-java/src/main/java/uk/jtoye/core/tenant/DevTenantController.java
  - core-java/src/test/java/uk/jtoye/core/security/KeycloakRealmRoleConverterTest.java
  - core-java/src/test/java/uk/jtoye/core/security/RoleBasedAccessIntegrationTest.java
  - docs/metrics.json
  - CLAUDE.md
autonomous: true
requirements: [RBAC-P1-1]

must_haves:
  truths:
    - "A JWT carrying realm role `admin` is granted Spring authority `ROLE_admin`; a token without it is not."
    - "An `admin`-role token can list financial transactions (200) and reaches the service layer for refunds and GDPR export/erase (not 403)."
    - "A low-privilege (non-admin) token receives 403 from refunds, finance, and GDPR endpoints."
    - "Regular tenant CRUD (shops/products/orders) remains reachable by any authenticated user (no over-blocking)."
    - "Method security is enabled application-wide via @EnableMethodSecurity."
  artifacts:
    - path: "core-java/src/main/java/uk/jtoye/core/security/KeycloakRealmRoleConverter.java"
      provides: "realm_access.roles -> ROLE_<role> GrantedAuthority mapping"
      contains: "implements Converter"
    - path: "core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java"
      provides: "@EnableMethodSecurity + jwtAuthenticationConverter wiring"
      contains: "EnableMethodSecurity"
    - path: "core-java/src/main/java/uk/jtoye/core/payment/RefundController.java"
      provides: "hasRole('admin') gate on refund endpoints"
      contains: "PreAuthorize"
    - path: "core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionController.java"
      provides: "hasRole('admin') gate on finance endpoints"
      contains: "PreAuthorize"
    - path: "core-java/src/main/java/uk/jtoye/core/gdpr/GdprController.java"
      provides: "hasRole('admin') gate on GDPR endpoints"
      contains: "PreAuthorize"
    - path: "core-java/src/test/java/uk/jtoye/core/security/KeycloakRealmRoleConverterTest.java"
      provides: "unit assertion realm_access.roles -> ROLE_ authorities"
    - path: "core-java/src/test/java/uk/jtoye/core/security/RoleBasedAccessIntegrationTest.java"
      provides: "allowed-vs-forbidden per role against real controllers (Testcontainers)"
    - path: "docs/metrics.json"
      provides: "test-count baseline regenerated to match new tests"
  key_links:
    - from: "core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java"
      to: "KeycloakRealmRoleConverter"
      via: "jwtAuthenticationConverter(setJwtGrantedAuthoritiesConverter)"
      pattern: "jwtAuthenticationConverter"
    - from: "sensitive controllers"
      to: "Spring authority ROLE_admin"
      via: "@PreAuthorize(\"hasRole('admin')\")"
      pattern: "hasRole\\('admin'\\)"
    - from: "RoleBasedAccessIntegrationTest"
      to: "KeycloakRealmRoleConverter"
      via: "jwt().authorities(converter) + realm_access claim"
      pattern: "realm_access"
---

<objective>
Implement GitHub issue #83 [P1-1]: introduce role-based authorization across `core-java`.
Today the backend has zero role checks — `anyRequest().authenticated()` is the only gate, so
any authenticated tenant user can issue Stripe refunds, read the full financial ledger, and
erase customer PII. Keycloak already defines realm roles (`admin`, `user`) that are never
consulted.

This plan: (1) map Keycloak `realm_access.roles` claims into Spring `ROLE_*` authorities and
enable method security, (2) gate the sensitive surfaces (refunds, finance, GDPR, dev-admin) with
`@PreAuthorize("hasRole('admin')")`, and (3) prove allowed-vs-forbidden behaviour per role with a
converter unit test and a Testcontainers controller integration test.

Purpose: Close the enterprise-readiness audit finding P1-1 (broken access control) — the
highest-impact authorization gap in the backend.
Output: Realm-role authority mapping, method-security gates on 4 controllers, 2 new test classes,
and refreshed test-count docs.

Scope guardrails:
- Backend-only. No frontend changes (role claims already ride the existing JWT).
- Role checks are IN ADDITION to RLS/TenantContext isolation, never a replacement — do not touch
  tenant scoping logic.
- No new dependencies (`spring-security-test` is already on the test classpath).
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@./CLAUDE.md

<interfaces>
<!-- Contracts the executor needs. Extracted from the codebase — no exploration required. -->

Current SecurityConfig JWT wiring (core-java/.../security/SecurityConfig.java) — REPLACE the
default converter here:
```java
.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
```
The class is annotated `@Configuration @EnableWebSecurity` and exposes a `SecurityFilterChain`
bean. `JwtTenantFilter` runs AFTER `BearerTokenAuthenticationFilter` and maps `tenant_id` ->
`TenantContext`; DO NOT disturb it.

Keycloak realm-role claim shape in the JWT (standard Keycloak):
```json
{ "realm_access": { "roles": ["admin", "user"] }, "tenant_id": "..." }
```
Spring's `hasRole('admin')` checks for authority `ROLE_admin` — the converter MUST emit the
`ROLE_` prefix + the literal (lowercase) role name so `admin` -> `ROLE_admin`.

Sensitive endpoints to gate (verified current locations):
- RefundController  `@RequestMapping("/api/v1/orders")`  POST `/{orderId}/refund`, GET `/{orderId}/refunds`
- FinancialTransactionController `@RequestMapping("/financial-transactions")` GET `/`, GET `/{id}`, POST `/`, GET `/summary`
- GdprController `@RequestMapping("/gdpr/customers")` GET `/{customerId}/export`, DELETE `/{customerId}/erase`
- DevTenantController `@RequestMapping("/dev/tenants")` (already `@Profile({"dev","local"})`)

RefundController Javadoc CURRENTLY states RBAC is deferred (Phase 17 UC-5 LOCKED). Issue #83
supersedes that deferral — update the Javadoc so it no longer claims "any JWT-authenticated
tenant user can refund".

403 mapping is already handled: `common/GlobalExceptionHandler` has
`@ExceptionHandler(AccessDeniedException.class) -> HttpStatus.FORBIDDEN`. Spring Security 6
method-security throws `AuthorizationDeniedException extends AccessDeniedException`, so
`@PreAuthorize` denials return 403. DO NOT add a duplicate handler.

Existing test auth pattern (core-java/.../security/CrossTenantSpoofIntegrationTest.java):
```java
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
mockMvc.perform(get("/public/...").with(jwt().jwt(j -> j.claim("tenant_id", TENANT_A.toString()))))
```
The `jwt()` post-processor bypasses the app's resource-server converter, so authorities must be
supplied explicitly. Use the `.authorities(Converter<Jwt, Collection<GrantedAuthority>>)` overload
with the REAL `KeycloakRealmRoleConverter` so the test exercises the actual mapping:
```java
jwt().jwt(j -> j.claim("tenant_id", TENANT_A.toString())
                .claim("realm_access", Map.of("roles", List.of("admin"))))
     .authorities(new KeycloakRealmRoleConverter())
```

Testcontainers harness (reuse verbatim): `IntegrationTestSupport.registerPostgresTestProperties(
registry, postgres)` + class annotations `@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@ActiveProfiles("test") @Tag("testcontainers")`. The `integrationTest` Gradle task runs only
`@Tag("testcontainers")` classes; the fast `test` task excludes that tag.

docs-freshness gate: `scripts/docs-freshness.sh --write` regenerates `docs/metrics.json`
deterministically (it counts `@Test\b` in `core-java/src/test/**`). No-arg run = check mode
(fails on drift). CLAUDE.md "Testing" line hardcodes "527 Java @Test methods across 80 files"
and "726 logical invocations" — update those prose numbers to the regenerated values.
</interfaces>
</context>

<tasks>

<task type="auto">
  <name>Task 1: Map realm roles to authorities and enable method security</name>
  <files>core-java/src/main/java/uk/jtoye/core/security/KeycloakRealmRoleConverter.java, core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java</files>
  <action>
    Create `KeycloakRealmRoleConverter` implementing
    `org.springframework.core.convert.converter.Converter<Jwt, Collection<GrantedAuthority>>`.
    Read the `realm_access` claim as a `Map<String,Object>`; extract its `roles` entry as a
    `List<String>`; map each role to `new SimpleGrantedAuthority("ROLE_" + role)` preserving the
    literal (lowercase) role name. Return an empty list — never null — when `realm_access` is
    absent, not a Map, or has no `roles` list (defensive: tokens from other clients may omit it).
    Keep it a plain public class (no Spring stereotype needed) so tests can `new` it directly and
    SecurityConfig can instantiate it.

    In `SecurityConfig`: add `@EnableMethodSecurity` (from
    `org.springframework.security.config.annotation.method.configuration`) to the class alongside
    the existing `@EnableWebSecurity`. Build a `JwtAuthenticationConverter` whose
    `setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter())` is set, and wire it into
    the resource server by replacing `oauth2.jwt(Customizer.withDefaults())` with
    `oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))`. Leave every other line of
    `securityFilterChain` (authorizeHttpRequests matchers, headers, JwtTenantFilter/TenantFilter
    ordering, `anyRequest().authenticated()`) unchanged — role gates are additive and live at the
    method layer, not here.
  </action>
  <verify>
    <automated>cd core-java && ./gradlew compileJava -q && grep -q "EnableMethodSecurity" src/main/java/uk/jtoye/core/security/SecurityConfig.java && grep -q "jwtAuthenticationConverter" src/main/java/uk/jtoye/core/security/SecurityConfig.java && test -f src/main/java/uk/jtoye/core/security/KeycloakRealmRoleConverter.java</automated>
  </verify>
  <done>KeycloakRealmRoleConverter exists and maps realm_access.roles -> ROLE_*; SecurityConfig enables method security and wires the converter into the resource server; core-java compiles.</done>
</task>

<task type="auto">
  <name>Task 2: Gate refunds, finance, GDPR, and dev-admin endpoints by role</name>
  <files>core-java/src/main/java/uk/jtoye/core/payment/RefundController.java, core-java/src/main/java/uk/jtoye/core/finance/FinancialTransactionController.java, core-java/src/main/java/uk/jtoye/core/gdpr/GdprController.java, core-java/src/main/java/uk/jtoye/core/tenant/DevTenantController.java</files>
  <action>
    Add `@org.springframework.security.access.prepost.PreAuthorize("hasRole('admin')")` at the
    CLASS level of each of the four controllers, so every mapped method inherits the gate:
    RefundController, FinancialTransactionController, GdprController, and DevTenantController
    (defense-in-depth for the admin surface — it is already `@Profile` dev/local, keep that).

    In RefundController, also rewrite the class Javadoc block that currently says RBAC is deferred
    ("Per UC-5 LOCKED in Phase 17 CONTEXT: deferred RBAC. Any JWT-authenticated tenant user can
    refund...") to state that refunds now require the `admin` realm role per issue #83, superseding
    the Phase 17 deferral. RLS still enforces tenant scoping in addition to the role check.

    Do NOT gate ShopController/ProductController/OrderController/CustomerController — ordinary
    tenant CRUD must remain reachable by any authenticated user (`user` or `admin`). Do NOT add
    gates in SecurityConfig's authorizeHttpRequests; keep authorization at the method layer only.
  </action>
  <verify>
    <automated>cd core-java && for f in payment/RefundController finance/FinancialTransactionController gdpr/GdprController tenant/DevTenantController; do grep -v '^\s*\*' src/main/java/uk/jtoye/core/$f.java | grep -v '^\s*//' | grep -q "hasRole('admin')" || { echo "MISSING gate in $f"; exit 1; }; done && ./gradlew compileJava -q</automated>
  </verify>
  <done>All four sensitive controllers carry a class-level hasRole('admin') gate; RefundController Javadoc no longer claims deferred RBAC; core-java compiles.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 3: Role-based access tests + docs metrics refresh</name>
  <files>core-java/src/test/java/uk/jtoye/core/security/KeycloakRealmRoleConverterTest.java, core-java/src/test/java/uk/jtoye/core/security/RoleBasedAccessIntegrationTest.java, docs/metrics.json, CLAUDE.md</files>
  <behavior>
    KeycloakRealmRoleConverterTest (plain unit, no Spring context):
    - Jwt with realm_access.roles = ["admin","user"] -> authorities contain ROLE_admin AND ROLE_user.
    - Jwt with no realm_access claim -> empty authorities (no NPE).
    - Jwt with realm_access present but no roles list -> empty authorities.

    RoleBasedAccessIntegrationTest (@Tag("testcontainers"), Testcontainers Postgres via
    IntegrationTestSupport, MockMvc, seed one tenant row like CrossTenantSpoofIntegrationTest):
    - admin token (realm_access.roles=["admin"], tenant_id set, .authorities(new KeycloakRealmRoleConverter()))
      -> GET /financial-transactions returns 200 (positive control: admin passes the gate).
    - low-priv token (realm_access.roles=["user"], tenant_id set) -> 403 on:
        GET  /financial-transactions
        POST /api/v1/orders/{randomUUID}/refund   (minimal valid CreateRefundRequest body)
        GET  /gdpr/customers/{randomUUID}/export
        DELETE /gdpr/customers/{randomUUID}/erase
      (gate fires before the service, so the nonexistent IDs never matter).
    - admin token -> GET /gdpr/customers/{randomUUID}/export returns a non-403 status
      (asserts the gate is passed; 404 when the customer is absent is acceptable — assert != 403).
  </behavior>
  <action>
    Write both test classes. For the integration test, copy the class-level annotations and the
    `@DynamicPropertySource` -> `IntegrationTestSupport.registerPostgresTestProperties(registry,
    postgres)` wiring from an existing testcontainers class, and reuse the `jwt().jwt(...).
    authorities(new KeycloakRealmRoleConverter())` pattern shown in <interfaces>. Build the refund
    POST body to satisfy CreateRefundRequest's bean validation (inspect the DTO for required
    fields) so the request reaches the authorization layer rather than failing @Valid first —
    though for a 403 the gate short-circuits before body binding, keep the body well-formed JSON.

    After the tests pass, regenerate the test-count manifest: run `scripts/docs-freshness.sh
    --write` from repo root to update `docs/metrics.json` (java_test_methods, java_test_files,
    total_logical_invocations rise by the number of new @Test methods / files added). Then update
    the CLAUDE.md "Testing" line prose ("527 Java @Test methods across 80 files ... 726 logical
    invocations") to match the regenerated numbers in docs/metrics.json.
  </action>
  <verify>
    <automated>cd core-java && ./gradlew test --tests '*KeycloakRealmRoleConverterTest' -q && ./gradlew integrationTest --tests '*RoleBasedAccessIntegrationTest' -q && cd .. && bash scripts/docs-freshness.sh</automated>
  </verify>
  <done>Converter unit test and role-based integration test pass; low-priv token gets 403 on refunds/finance/GDPR and admin reaches those services; docs/metrics.json and CLAUDE.md counts are in sync (docs-freshness exits 0).</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| client -> core-java API | Authenticated tenant user submits a Bearer JWT; the realm-role claim within it is the sole source of privilege level. |
| Keycloak -> core-java | Realm roles minted by the IdP arrive as `realm_access.roles`; core-java must trust the signed token but map roles correctly. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-rlp-01 | Elevation of Privilege | RefundController.createRefund | mitigate | `@PreAuthorize("hasRole('admin')")`; asserted by RoleBasedAccessIntegrationTest (low-priv -> 403). |
| T-rlp-02 | Information Disclosure | FinancialTransactionController (ledger + summary) | mitigate | Class-level `hasRole('admin')`; low-priv 403 asserted; RLS still scopes admin reads to their tenant. |
| T-rlp-03 | Tampering / Repudiation | GdprController.eraseData (irreversible PII anonymisation) | mitigate | `hasRole('admin')` gate; low-priv DELETE -> 403 asserted. |
| T-rlp-04 | Elevation of Privilege | KeycloakRealmRoleConverter (ROLE_ prefix / claim shape) | mitigate | Unit test proves `admin`->`ROLE_admin` mapping and null-safe empty-authority fallback (no accidental grant). |
| T-rlp-05 | Elevation of Privilege | DevTenantController (admin surface) | mitigate | `hasRole('admin')` added on top of existing `@Profile({dev,local})` (absent in prod). |
| T-rlp-06 | Spoofing (tenant vs role) | JwtTenantFilter + method gates | accept | Role checks are additive to existing tenant isolation; tenant scoping (RLS/TenantContext) is unchanged and independently tested — no regression introduced here. |

No package-manager installs in this plan (no npm/pip/cargo) — package-legitimacy gate not applicable.
</threat_model>

<verification>
- `cd core-java && ./gradlew compileJava` — production code compiles with method security + converter wired.
- `cd core-java && ./gradlew test` — fast unit suite green (includes KeycloakRealmRoleConverterTest).
- `cd core-java && ./gradlew integrationTest` — FULL Testcontainers suite green (includes
  RoleBasedAccessIntegrationTest and all pre-existing RLS/controller integration tests — no
  regression from the new gates).
- `bash scripts/docs-freshness.sh` — exits 0 (docs/metrics.json matches source reality).
</verification>

<success_criteria>
- Method security is enabled (`@EnableMethodSecurity`) and Keycloak `realm_access.roles` map to
  `ROLE_*` Spring authorities via `KeycloakRealmRoleConverter` wired into the resource server.
- Refund, finance, GDPR, and dev-admin endpoints require the `admin` realm role.
- A low-privilege (`user`-only) token receives 403 from refunds, finance, and GDPR; an `admin`
  token passes the gate (200 on finance list; non-403 on refund/GDPR).
- Realm-role -> authority mapping is asserted directly (unit test) and end-to-end (integration test).
- Ordinary tenant CRUD remains reachable by any authenticated user (no over-blocking).
- Full `:core-java:integrationTest` and `:core-java:test` pass; `docs/metrics.json` + CLAUDE.md
  counts regenerated and the docs-freshness CI gate is green.
</success_criteria>

<output>
Create `.planning/quick/260708-rlp-implement-issue-83-p1-1-rbac-method-secu/260708-rlp-SUMMARY.md` when done.
</output>
</content>
</invoke>
