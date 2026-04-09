---
status: issues_found
phase: 01
depth: standard
files_reviewed: 5
findings:
  critical: 1
  warning: 5
  info: 3
  total: 9
---

# Phase 01 Review — API Versioning (Backend)

**Files reviewed:**
- `core-java/src/main/java/uk/jtoye/core/config/WebConfig.java`
- `core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java` *(pulled in for context)*
- `core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java` *(pulled in for context)*
- `core-java/src/main/java/uk/jtoye/core/sync/SyncController.java` *(pulled in for context)*
- `core-java/src/test/java/uk/jtoye/core/integration/ShopControllerIntegrationTest.java`
- `core-java/src/test/java/uk/jtoye/core/integration/CustomerControllerIntegrationTest.java`
- `core-java/src/test/java/uk/jtoye/core/integration/FinancialTransactionControllerIntegrationTest.java`
- `core-java/src/test/java/uk/jtoye/core/sync/SyncControllerIntegrationTest.java`

---

## Critical

### CRIT-1: Missing tenant isolation in test cleanup — cross-tenant data pollution

**Files:** `CustomerControllerIntegrationTest.java` (line 67), `FinancialTransactionControllerIntegrationTest.java` (line 67), `ShopControllerIntegrationTest.java` (line 67)

**Problem:** The `@BeforeEach` teardown deletes ALL rows across ALL tenants:

```java
jdbcTemplate.update("DELETE FROM customers");
jdbcTemplate.update("DELETE FROM financial_transactions");
jdbcTemplate.update("DELETE FROM shops");
```

These are unscoped deletes. If Testcontainers reuses the same container instance across test classes in a parallel run (a real risk with `@SpringBootTest` sharing the application context), one test class's cleanup can silently wipe rows that another concurrently-executing test class just inserted. This produces intermittent false negatives (e.g., `listCustomersShouldReturnPaginatedResults` expecting 3 records finding 0) that are extremely hard to diagnose.

**Fix:** Scope the delete to the test tenant:

```java
jdbcTemplate.update("DELETE FROM customers WHERE tenant_id = ?", testTenantId);
```

This also validates that RLS is correctly enforcing tenant scoping in production — an unscoped delete hiding that is a secondary concern.

---

## Warning

### WARN-1: `getByIdShouldReturnCustomer` and `deleteCustomerShouldReturn404` hit the wrong URL path

**File:** `CustomerControllerIntegrationTest.java` (lines 143, 217)

The GET and the verify-after-delete requests use the path `/customers/{id}` without the `/api/v1` prefix:

```java
// Line 143
mockMvc.perform(get("/customers/" + customerId)
// Line 217
mockMvc.perform(get("/customers/" + customerId)
```

`CustomerController` is in `uk.jtoye.core.customer`, so `WebConfig.configurePathMatch` will prepend `/api/v1`, making the real path `/api/v1/customers/{id}`. These two test calls hit a non-existent route and will either return 404 from Spring's `NoHandlerFoundException` or 401/403 from security — not the real controller. The tests may pass for the wrong reason (404 == not found for the post-delete assertion, which coincidentally matches the expectation).

**Fix:**

```java
mockMvc.perform(get("/api/v1/customers/" + customerId)
```

Same issue appears identically in `FinancialTransactionControllerIntegrationTest.java` line 140:

```java
mockMvc.perform(get("/financial-transactions/" + transactionId)
```

Should be `/api/v1/financial-transactions/{id}`.

---

### WARN-2: `SyncControllerIntegrationTest` disables security filters but omits tenant header validation

**File:** `SyncControllerIntegrationTest.java` (line 22)

```java
@WebMvcTest(SyncController.class)
@AutoConfigureMockMvc(addFilters = false)
```

The test completely disables security filters. `SyncController.batchSync` has no `@Valid` on its `@RequestBody`, no tenant header check, and no authentication guard in the test. The test exercises only the happy path with a mocked service. There are no tests for:

- Missing or malformed `tenantId` in the request body
- Unauthenticated access (the comment says "unit test" but the class is named `IntegrationTest`)
- Service-level exceptions propagating to a proper error response

This is a coverage gap for a sensitive sync endpoint that accepts arbitrary batch data.

---

### WARN-3: Missing tenant header validation test for `CustomerController` and `FinancialTransactionController`

**Files:** `CustomerControllerIntegrationTest.java`, `FinancialTransactionControllerIntegrationTest.java`

`ShopControllerIntegrationTest` has a dedicated `createShopWithoutTenantHeaderShouldReturn400` test. Neither `CustomerControllerIntegrationTest` nor `FinancialTransactionControllerIntegrationTest` has an equivalent. `FinancialTransactionControllerIntegrationTest.createTransactionWithoutTenantShouldFail` (line 170) expects `500` (because `TenantContext.get().orElseThrow()` throws `NoSuchElementException`) and the comment acknowledges this:

```java
// Expect 500 because TenantContext.get().orElseThrow() throws NoSuchElementException
```

A 500 response for a missing tenant header is a bug, not a feature — it leaks a stack trace and is not a structured API error. The correct behaviour is 400. The test is asserting broken behaviour rather than desired behaviour.

---

### WARN-4: `@Autowired` field injection in `WebConfig`

**File:** `WebConfig.java` (lines 18–19)

```java
@Autowired
private RateLimitInterceptor rateLimitInterceptor;
```

Field injection is the least testable injection style and hides mandatory dependencies. `WebMvcConfigurer` classes are frequently tested in isolation. Constructor injection is the project's style everywhere else (e.g., `CustomerController` uses constructor injection).

**Fix:**

```java
public WebConfig(RateLimitInterceptor rateLimitInterceptor) {
    this.rateLimitInterceptor = rateLimitInterceptor;
}
```

---

### WARN-5: Rate limit error response writes tenant ID into the body without sanitisation

**File:** `RateLimitInterceptor.java` (lines 108–111)

```java
response.getWriter().write(String.format(
    "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Please try again in %d seconds.\",\"tenantId\":\"%s\"}",
    waitForRefill, tenantId
));
```

The `tenantId` is a UUID so injection risk is low in practice, but writing it via raw `String.format` into a JSON response body bypasses any content-type negotiation, character encoding, and structured serialisation. More importantly, returning the `tenantId` in a 429 error response to an unauthenticated/rate-limited caller leaks internal tenant identifiers. An attacker probing the API learns which tenant UUID is associated with which traffic.

**Fix:** Remove `tenantId` from the 429 error body. Use a proper `ObjectMapper` or `Jackson` to build the JSON string.

---

## Info

### INFO-1: `ShopControllerIntegrationTest` uses `PER_METHOD` lifecycle unnecessarily

**File:** `ShopControllerIntegrationTest.java` (line 29)

```java
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD)
```

`PER_METHOD` is the default JUnit 5 lifecycle — this annotation is a no-op and adds noise. The other two integration test classes omit it. Remove it for consistency.

---

### INFO-2: Inconsistent tenant header name — `X-Tenant-Id` vs `X-Tenant-ID`

**Files:** `ShopControllerIntegrationTest.java` (line 104), `CustomerControllerIntegrationTest.java` (lines 81, 105), `FinancialTransactionControllerIntegrationTest.java` (line 80)

`ShopControllerIntegrationTest` uses `X-Tenant-Id` (mixed case `d`). All other test files use `X-Tenant-ID` (upper-case `D`). HTTP headers are case-insensitive per RFC 7230, so this does not cause test failures, but it signals a missing canonical constant. Define a single `public static final String TENANT_HEADER = "X-Tenant-ID"` in a shared test utility and reference it everywhere to avoid future confusion.

---

### INFO-3: `SyncControllerIntegrationTest` is a slice test named `IntegrationTest`

**File:** `SyncControllerIntegrationTest.java`

The class uses `@WebMvcTest` (a slice test, no real context, mocked service) but is named `SyncControllerIntegrationTest`. This naming convention conflicts with the `@SpringBootTest`-based integration tests in the same codebase. Rename it to `SyncControllerTest` or `SyncControllerMvcTest` to avoid confusion in CI test reports and when running tests by tag.

---

## Summary

| ID | Severity | File | Issue |
|----|----------|------|-------|
| CRIT-1 | Critical | `*IntegrationTest.java` (all three) | Unscoped DELETE in `@BeforeEach` risks cross-tenant data pollution in parallel runs |
| WARN-1 | Warning | `CustomerControllerIntegrationTest.java` L143, L217 | GET by ID uses wrong URL (`/customers/` instead of `/api/v1/customers/`) |
| WARN-1b | Warning | `FinancialTransactionControllerIntegrationTest.java` L140 | Same wrong URL (`/financial-transactions/` instead of `/api/v1/financial-transactions/`) |
| WARN-2 | Warning | `SyncControllerIntegrationTest.java` | Security bypassed entirely; no negative-path or auth tests for sync endpoint |
| WARN-3 | Warning | `FinancialTransactionControllerIntegrationTest.java` L177 | Missing tenant header returns 500 (stack trace) rather than 400; test asserts broken behaviour |
| WARN-4 | Warning | `WebConfig.java` L18 | Field injection on `rateLimitInterceptor`; inconsistent with project style |
| WARN-5 | Warning | `RateLimitInterceptor.java` L108 | Tenant ID leaked in 429 error body; raw string formatting bypasses serialiser |
| INFO-1 | Info | `ShopControllerIntegrationTest.java` L29 | Redundant `@TestInstance(PER_METHOD)` annotation |
| INFO-2 | Info | All test files | Inconsistent `X-Tenant-Id` / `X-Tenant-ID` header casing; no shared constant |
| INFO-3 | Info | `SyncControllerIntegrationTest.java` | `@WebMvcTest` slice named `IntegrationTest` — misleading in CI reports |
