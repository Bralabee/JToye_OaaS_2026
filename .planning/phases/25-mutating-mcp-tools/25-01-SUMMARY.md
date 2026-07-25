---
phase: 25-mutating-mcp-tools
plan: "01"
subsystem: core-security
tags: [scopes, preauthorize, least-privilege, mcp-auth, openapi]
requires:
  - "@EnableMethodSecurity active (SecurityConfig #83)"
  - "JwtRolesAndScopesConverter scope→SCOPE_* mapping (#206)"
provides:
  - "orders:write scope gate on POST /api/v1/orders (D-01)"
  - "customers:write scope gate on POST /api/v1/customers (D-02, new scope)"
  - "OpenApi scope taxonomy documents orders:write/customers:write as enforced"
  - "converter-through-MockMvc CI proof (ScopedWriteAccessIntegrationTest)"
affects:
  - "25-02 realm wiring (integration-orders-rw carries orders:write + customers:write)"
  - "25-02+ MCP create_order / create_customer tools ride these gates"
tech-stack:
  added: []
  patterns:
    - "Positive least-privilege @PreAuthorize gate (no deny SpEL), operators default-grant (#206 model)"
    - "Fully-valid body in the CI 403 test so @Valid does not mask @PreAuthorize (D-04 ordering trap)"
key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/security/ScopedWriteAccessIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/order/OrderController.java
    - core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java
    - core-java/src/main/java/uk/jtoye/core/config/OpenApiConfig.java
decisions:
  - "Enforce orders:write + customers:write in core now via @PreAuthorize (D-01/D-02); positive gate only, #206 model"
  - "AI-02 stays PENDING (anti-false-green) — the MCP write tools + idempotent-replay + RLS proof land in 25-02..25-04"
metrics:
  duration: "~6min"
  tasks: 2
  files: 4
  completed: "2026-07-24"
---

# Phase 25 Plan 01: Write-Scope Authorization Gates Summary

Activated the reserved `orders:write` scope and introduced a new `customers:write` scope as positive `@PreAuthorize` least-privilege gates on the two create surfaces (`POST /api/v1/orders`, `POST /api/v1/customers`), proven in CI via the converter-through-MockMvc pattern with fully-valid bodies so `@Valid` cannot mask the 403.

## What Was Built

- **`OrderController.createOrder`** — added `@PreAuthorize("hasAuthority('SCOPE_orders:write')")` directly above `@PostMapping` (D-01). The `@Idempotent`/`idempotencyService.execute` wiring is byte-for-byte untouched (D-06); only the annotation + import changed.
- **`CustomerController.create`** — added `@PreAuthorize("hasAuthority('SCOPE_customers:write')")` above `@PostMapping` (D-02, a genuinely new scope so an order-only agent cannot mint customers). Idempotency wiring untouched.
- **`OpenApiConfig`** — the scope taxonomy now documents `orders:write` and `customers:write` as **enforced** (gates `POST /orders` / `POST /customers`), adds `customers:read` as defined-but-unenforced, and keeps `orders:read` reserved. Doc-string only (markdown description block + `Scopes()` list + the inline comment); no bean/security config change.
- **`ScopedWriteAccessIntegrationTest`** (new) — an exact structural mirror of `ScopedCatalogAccessIntegrationTest` (`@SpringBootTest @AutoConfigureMockMvc @Testcontainers @ActiveProfiles("test") @Tag("testcontainers")`). Tokens are built with `jwt().jwt(...).authorities(new JwtRolesAndScopesConverter())` (the real production mapping) carrying a random-UUID subject + `tenant_id` claim + a per-case `scope` claim. Four cases: a no-write-scope token (`scope=catalog:read`) → 403 on both creates; an `orders:write` token → not-403 on `/orders`; a `customers:write` token → not-403 on `/customers`. All bodies are fully valid (`VALID_ORDER_JSON`, `VALID_CUSTOMER_JSON`) so `@Valid` binding passes and `@PreAuthorize` decides the outcome (the D-04 ordering trap). `not403()` custom `ResultMatcher` copied verbatim.

## TDD Cycle

- **RED (Task 1, commit `5e34d30`):** `ScopedWriteAccessIntegrationTest` authored before the gates exist. The two `noScopeToken...Forbidden...` cases asserted `status().isForbidden()` but observed non-403, proving the gates were genuinely absent:
  - `noScopeTokenForbiddenOnOrderCreate`: expected 403, **observed 404** (the no-scope token reached `OrderService`; the random `shopId` → `ResourceNotFoundException` → 404).
  - `noScopeTokenForbiddenOnCustomerCreate`: expected 403, **observed 201** (the no-scope token reached `CustomerService` and created the customer row).
  - The two `writeScoped...Not403` cases passed trivially at RED (any non-403 satisfies `not403()` when no gate exists).
- **GREEN (Task 2, commit `40f7595`):** added the two `@PreAuthorize` gates + `OpenApiConfig` taxonomy update. `ScopedWriteAccessIntegrationTest` now GREEN **4/4** (tests=4, failures=0, errors=0, skipped=0); `./gradlew :core-java:compileJava` clean.

## Verification

- `./gradlew :core-java:integrationTest --tests '*ScopedWriteAccess*'` — BUILD SUCCESSFUL, 4/4 green (Testcontainers Postgres 15, real RLS + Flyway schema).
- `./gradlew :core-java:compileJava` — BUILD SUCCESSFUL.
- `git diff` on both controllers shows only the `@PreAuthorize` annotation + `import org.springframework.security.access.prepost.PreAuthorize;` — the idempotency wiring is untouched (D-06 satisfied).

## Threat Model Discharge

| Threat ID | Disposition | How mitigated this plan |
|-----------|-------------|--------------------------|
| T-25-01 (EoP: creates left authenticated-only) | mitigate | Positive `SCOPE_orders:write` / `SCOPE_customers:write` gates added (Task 2); no deny SpEL |
| T-25-02 (masked authz: `@Valid` before `@PreAuthorize`) | mitigate | CI proof sends fully-valid bodies so `@PreAuthorize` decides the outcome (RED observed 404/201, not a 400 mask) |
| T-25-03 (stale OpenAPI contract) | mitigate | `OpenApiConfig` taxonomy updated to read "enforced" + `customers:read/write` added |

## Deviations from Plan

None — plan executed exactly as written. (Note: RESEARCH/PATTERNS referred to the test as `ScopedOrdersCustomersAccessIntegrationTest`; the PLAN — the source of truth — names it `ScopedWriteAccessIntegrationTest`, which is what was created.)

## Requirement Status

**AI-02 kept PENDING (anti-false-green).** This plan delivers only the core write-scope gates + their CI proof — the Java-only, CI-provable half. AI-02's acceptance (MCP `create_order`/`create_customer` write tools riding the uniform Idempotency-Key contract, an idempotent-replay integration test, and a cross-tenant RLS proof under the MCP credential) is met by 25-02..25-04. Marking AI-02 complete now would be a false-green, consistent with how every prior multi-plan requirement in this milestone was held PENDING until its last contributing plan.

## Known Stubs

None. Both gates are fully wired and enforced; no placeholder/empty-value surfaces introduced.

## Self-Check: PASSED

- Files: all 4 present (1 created, 3 modified).
- Commits: `5e34d30` (test/RED) and `40f7595` (feat/GREEN) both present in git history.
