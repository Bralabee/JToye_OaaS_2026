---
phase: 01-api-versioning-backend
verified: 2026-04-07T23:55:00Z
status: gaps_found
score: 5/7 must-haves verified
re_verification: false
gaps:
  - truth: "All existing integration tests pass with updated paths"
    status: partial
    reason: >
      CustomerControllerIntegrationTest (line 143) calls GET /customers/{id} without /api/v1/ prefix
      and asserts status().isOk(). With addPathPrefix applied, /customers/{id} returns 404 — this
      test would fail if executed. FinancialTransactionControllerIntegrationTest (line 140) similarly
      calls GET /financial-transactions/{id} without prefix and asserts isOk(). These tests are
      excluded from CI via @Tag("testcontainers") + excludeTags("testcontainers") in build.gradle.kts,
      so the "310+ tests pass" claim is true but does not cover these cases.
    artifacts:
      - path: "core-java/src/test/java/uk/jtoye/core/integration/CustomerControllerIntegrationTest.java"
        issue: "Line 143: get(\"/customers/\" + customerId) expects isOk() — unversioned path returns 404 at runtime. Line 217: get(\"/customers/\" + customerId) expects isNotFound() — coincidentally passes but tests wrong path."
      - path: "core-java/src/test/java/uk/jtoye/core/integration/FinancialTransactionControllerIntegrationTest.java"
        issue: "Line 140: get(\"/financial-transactions/\" + transactionId) expects isOk() — unversioned path returns 404 at runtime."
    missing:
      - "Fix CustomerControllerIntegrationTest line 143: change get(\"/customers/\" + customerId) to get(\"/api/v1/customers/\" + customerId)"
      - "Fix CustomerControllerIntegrationTest line 217: change get(\"/customers/\" + customerId) to get(\"/api/v1/customers/\" + customerId)"
      - "Fix FinancialTransactionControllerIntegrationTest line 140: change get(\"/financial-transactions/\" + transactionId) to get(\"/api/v1/financial-transactions/\" + transactionId)"
  - truth: "Swagger UI at /swagger-ui.html shows /api/v1/ prefixed paths automatically"
    status: partial
    reason: >
      SpringDoc 2.8.6 is configured and addPathPrefix is in place — the automatic path detection
      behaviour is documented by SpringDoc and consistent with research. However no automated test
      verifies that /v3/api-docs actually returns /api/v1/ prefixed paths. This requires a runtime
      check or browser verification.
    artifacts: []
    missing:
      - "Human verification: start the service and confirm /v3/api-docs JSON shows /api/v1/shops, /api/v1/customers, etc."
human_verification:
  - test: "Swagger UI shows /api/v1/ prefixed paths"
    expected: "GET /v3/api-docs returns JSON with paths keyed as /api/v1/shops, /api/v1/customers, /api/v1/financial-transactions, /api/v1/sync/batch"
    why_human: "No automated test covers OpenAPI doc content. SpringDoc 2.8.6 auto-detection is expected to work but must be confirmed at runtime."
  - test: "Integration tests pass when run with -PincludeIntegration"
    expected: "All testcontainers-tagged tests pass including CustomerControllerIntegrationTest and FinancialTransactionControllerIntegrationTest"
    why_human: "Testcontainers tests require Docker. The three unversioned GETs identified above will cause test failures until fixed."
---

# Phase 1: API Versioning Backend — Verification Report

**Phase Goal:** All Spring Boot REST endpoints are accessible under /api/v1/ with webhook paths exempted and docs updated
**Verified:** 2026-04-07T23:55:00Z
**Status:** gaps_found
**Re-verification:** No — initial verification

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | GET /api/v1/shops returns 200 (versioned path works) | VERIFIED | ShopControllerIntegrationTest line 78: `get("/api/v1/shops")` asserts isUnauthorized (correct — 401 not 404, path resolves). createShopWithValidTenantShouldSucceed uses POST /api/v1/shops asserting isCreated. |
| 2 | GET /shops returns 404 (old unversioned path no longer routes) | VERIFIED | ShopControllerIntegrationTest has no bare /shops calls. addPathPrefix moves the mapping entirely. |
| 3 | GET /health returns 200 (exempt path still works) | VERIFIED | ShopControllerIntegrationTest line 72: `get("/health")` asserting isOk(). /health not in forBasePackage predicate. |
| 4 | GET /public/shops/{slug} returns 200 (exempt storefront still works) | VERIFIED | uk.jtoye.core.storefront not in forBasePackage predicate. SecurityConfig has `.requestMatchers("/public/**").permitAll()`. No regression risk. |
| 5 | POST /api/v1/sync/batch is accessible (sync controller versioned) | VERIFIED | SyncControllerIntegrationTest line 51: `post("/api/v1/sync/batch")` asserting isOk(). Test passes (non-testcontainers, @WebMvcTest). |
| 6 | Swagger UI at /swagger-ui.html shows /api/v1/ prefixed paths automatically | PARTIAL | SpringDoc 2.8.6 + addPathPrefix in place. No automated test verifies /v3/api-docs output. Needs human verification at runtime. |
| 7 | All existing integration tests pass with updated paths | PARTIAL | 310+ non-testcontainers tests pass. 3 unversioned GET paths remain in testcontainers-tagged tests (excluded from CI): CustomerControllerIntegrationTest lines 143 and 217, FinancialTransactionControllerIntegrationTest line 140. Two of these assert isOk() and would fail at runtime. |

**Score:** 5/7 truths verified

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `core-java/src/main/java/uk/jtoye/core/config/WebConfig.java` | configurePathMatch with addPathPrefix for /api/v1/ | VERIFIED | File exists, 50 lines, contains configurePathMatch override with addPathPrefix("/api/v1") and HandlerTypePredicate.forBasePackage targeting exactly 7 packages. |
| `core-java/src/test/java/uk/jtoye/core/integration/ShopControllerIntegrationTest.java` | Updated test paths with /api/v1/ prefix | VERIFIED | All 5 mockMvc calls use /api/v1/shops. /health preserved at line 72. |
| `core-java/src/test/java/uk/jtoye/core/integration/CustomerControllerIntegrationTest.java` | Updated test paths with /api/v1/ prefix | STUB/PARTIAL | POST/GET-list/PUT/DELETE paths updated to /api/v1/customers. Lines 143 and 217 still use bare /customers/{id}. |
| `core-java/src/test/java/uk/jtoye/core/integration/FinancialTransactionControllerIntegrationTest.java` | Updated test paths with /api/v1/ prefix | STUB/PARTIAL | POST and GET-list paths updated. Line 140 uses bare /financial-transactions/{id} expecting isOk(). |
| `core-java/src/test/java/uk/jtoye/core/sync/SyncControllerIntegrationTest.java` | Updated test paths with /api/v1/ prefix | VERIFIED | Single mockMvc call at line 51 uses /api/v1/sync/batch. |

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| WebConfig.java | uk.jtoye.core.shop, product, order, customer, finance, gdpr, sync | HandlerTypePredicate.forBasePackage() | WIRED | forBasePackage present at line 24, all 7 packages listed, no exempt packages included. |
| WebConfig.java | Exempt packages (payment, storefront, tenant, controller, root) | Absence from predicate | WIRED | grep confirms uk.jtoye.core.payment, .storefront, .tenant, .controller are absent from WebConfig.java. |
| ShopControllerIntegrationTest.java | /api/v1/shops | mockMvc.perform(get/post) | WIRED | 5 calls using /api/v1/shops found. |
| CustomerControllerIntegrationTest.java | /api/v1/customers | mockMvc.perform(post/get/put/delete) | PARTIAL | POST/GET-list/PUT/DELETE wired. GET /{id} at lines 143 and 217 not wired with prefix. |
| FinancialTransactionControllerIntegrationTest.java | /api/v1/financial-transactions | mockMvc.perform(post/get) | PARTIAL | POST and GET-list wired. GET /{id} at line 140 not wired with prefix. |

---

### Data-Flow Trace (Level 4)

Not applicable — phase produces routing configuration and tests, not dynamic data-rendering components.

---

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| WebConfig compileJava | grep configurePathMatch WebConfig.java | Present at line 22 | PASS |
| addPathPrefix targets 7 packages | grep forBasePackage + count packages in WebConfig.java | 7 packages confirmed | PASS |
| No exempt packages in predicate | grep uk.jtoye.core.payment WebConfig.java | Absent | PASS |
| SyncController test uses /api/v1/ | grep "/api/v1/sync/batch" SyncControllerIntegrationTest.java | Present at line 51 | PASS |
| Testcontainers excluded from standard build | grep excludeTags build.gradle.kts | excludeTags("testcontainers") at line 93 | PASS (explains test scope) |

---

### Requirements Coverage

| Requirement | Phase Mapping | Description | Status | Evidence |
|-------------|--------------|-------------|--------|----------|
| APIV-01 | Phase 1 | All REST endpoints prefixed with /api/v1/ via WebMvcConfigurer path matching | SATISFIED | WebConfig.java configurePathMatch with addPathPrefix targeting 7 controller packages. Verified via ShopControllerIntegrationTest (excluded testcontainers GET-by-id paths are a test quality gap, not an APIV-01 violation). |
| APIV-04 | Phase 1 | Stripe webhook and WhatsApp webhook paths exempted from versioning | SATISFIED | uk.jtoye.core.payment (PaymentController/Stripe), uk.jtoye.core.storefront (PublicStorefrontController) absent from forBasePackage predicate. SecurityConfig `/public/**` permitAll unchanged. |
| APIV-05 | Phase 1 | OpenAPI/Swagger docs reflect /api/v1/ paths | PARTIAL | SpringDoc 2.8.6 configured. addPathPrefix in place (auto-pickup expected per research). OpenApiConfig.java unchanged — correct per design. No automated test verifies Swagger output. Needs human verification. |

**Orphaned requirements check:** REQUIREMENTS.md maps APIV-01, APIV-04, APIV-05 to Phase 1. All three are claimed in the PLAN frontmatter. No orphaned requirements.

---

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| CustomerControllerIntegrationTest.java | 143 | `get("/customers/" + customerId)` asserts `isOk()` — unversioned GET-by-id path | Warning | Would fail if testcontainers tests are run with -PincludeIntegration. False confidence in test coverage. |
| CustomerControllerIntegrationTest.java | 217 | `get("/customers/" + customerId)` asserts `isNotFound()` — unversioned path, coincidentally passes | Info | Tests wrong path. Actual versioned path (/api/v1/customers/{id}) is not verified for 404. |
| FinancialTransactionControllerIntegrationTest.java | 140 | `get("/financial-transactions/" + transactionId)` asserts `isOk()` — unversioned GET-by-id path | Warning | Would fail if testcontainers tests are run with -PincludeIntegration. |

---

### Human Verification Required

#### 1. Swagger UI Shows /api/v1/ Paths

**Test:** Start the core-java service (`./gradlew bootRun`) and fetch `http://localhost:8080/v3/api-docs`
**Expected:** Response JSON contains path keys prefixed with `/api/v1/` — e.g., `/api/v1/shops`, `/api/v1/customers`, `/api/v1/financial-transactions`
**Why human:** No automated test covers OpenAPI doc content. SpringDoc 2.8.6 automatic detection is expected but unverified.

#### 2. Integration Tests Pass With Docker

**Test:** Run `cd core-java && JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64 ./gradlew test -PincludeIntegration --no-daemon`
**Expected:** All testcontainers-tagged tests pass. Currently CustomerControllerIntegrationTest (`getCustomerByIdShouldReturnCustomer`) and FinancialTransactionControllerIntegrationTest (`getTransactionByIdShouldReturnTransaction`) will fail due to unversioned GET-by-id paths.
**Why human:** Testcontainers tests require Docker. The failing tests are verified by code inspection but must be confirmed against a live stack.

---

### Gaps Summary

Two gaps block full goal achievement:

**Gap 1 — Incomplete test path migration (3 paths):** The SUMMARY claims "11 paths updated" in CustomerControllerIntegrationTest and "8 paths updated" in FinancialTransactionControllerIntegrationTest, but 3 GET-by-id calls remain without the `/api/v1/` prefix. These tests are silently excluded from CI by the `excludeTags("testcontainers")` build configuration. The gap does not break runtime routing (the actual controller endpoint is correctly versioned at `/api/v1/customers/{id}`) but means the integration test suite does not verify GET-by-id through the versioned path.

Root cause: The commit message claims 11 and 8 paths updated, but inspection shows GET /{id} calls in `getCustomerByIdShouldReturnCustomer`, `deleteCustomerShouldSucceed` (line 217), and `getTransactionByIdShouldReturnTransaction` were missed.

**Gap 2 — APIV-05 unverified programmatically:** Swagger auto-detection from addPathPrefix is a SpringDoc 2.8.6 documented behaviour (confirmed in research) but no test asserts `/v3/api-docs` output. This is a human verification item, not a code gap.

---

*Verified: 2026-04-07T23:55:00Z*
*Verifier: Claude (gsd-verifier)*
