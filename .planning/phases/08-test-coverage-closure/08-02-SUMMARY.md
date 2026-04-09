---
phase: 08-test-coverage-closure
plan: 02
subsystem: core-java/security, core-java/gdpr
tags: [testing, security, gdpr, tenant-isolation]
dependency_graph:
  requires: []
  provides: [TEST-03-security-filter-tests, TEST-04-gdpr-controller-tests]
  affects: [core-java-test-coverage]
tech_stack:
  added: []
  patterns: [OncePerRequestFilter-unit-testing, WebMvcTest-with-mocked-service, ArgumentCaptor-tenant-isolation]
key_files:
  created:
    - core-java/src/test/java/uk/jtoye/core/security/JwtTenantFilterTest.java
    - core-java/src/test/java/uk/jtoye/core/security/TenantFilterTest.java
    - core-java/src/test/java/uk/jtoye/core/gdpr/GdprControllerTest.java
  modified: []
decisions:
  - Used OncePerRequestFilter.doFilter() public method with mocked request attributes instead of reflection for cleaner tests
  - Added @Import(GlobalExceptionHandler.class) to GdprControllerTest to ensure 404 handling works in @WebMvcTest slice
metrics:
  duration: 223s
  completed: 2026-04-09T11:47:15Z
---

# Phase 08 Plan 02: Security Filter and GDPR Controller Tests Summary

Unit tests for JwtTenantFilter/TenantFilter security filters and MockMvc tests for GdprController GDPR endpoints, closing TEST-03 and TEST-04 coverage gaps.

## Task Results

| Task | Name | Commit | Status |
|------|------|--------|--------|
| 1 | JwtTenantFilter and TenantFilter unit tests (TEST-03) | 72e5c04 | Done |
| 2 | GdprController endpoint tests with tenant isolation (TEST-04) | 7fa31bc | Done |

## What Was Built

### Task 1: JwtTenantFilter and TenantFilter Unit Tests

**JwtTenantFilterTest** (6 tests):
- JWT with `tenant_id` claim sets TenantContext
- JWT with `tenantId` (camelCase) claim sets TenantContext when `tenant_id` absent
- JWT with `tid` claim sets TenantContext when other claims absent
- JWT with no tenant claims leaves TenantContext empty
- No authentication in SecurityContext proceeds without setting tenant
- JWT with malformed UUID does not set TenantContext (no exception thrown)

**TenantFilterTest** (5 tests):
- Valid X-Tenant-Id header sets TenantContext
- Invalid UUID in X-Tenant-Id returns 400, filter chain not called
- No header proceeds without setting tenant
- Header ignored when TenantContext already set by JWT (priority enforcement)
- TenantContext cleared in finally block after filter chain completes

### Task 2: GdprController Endpoint Tests

**GdprControllerTest** (5 tests):
- GET /api/v1/gdpr/customers/{id}/export returns 200 with full DataExportResponse JSON
- DELETE /api/v1/gdpr/customers/{id}/erase returns 200 with ErasureResponse counts
- Export returns 404 when service throws ResourceNotFoundException
- Erasure returns 404 when service throws ResourceNotFoundException
- Tenant isolation verified via ArgumentCaptor (exact customerId passed to service)

## Deviations from Plan

None - plan executed exactly as written.

## Known Stubs

None - all tests are fully wired to production code paths.

## Verification

All 16 new tests pass: `./gradlew :core-java:test --tests "uk.jtoye.core.security.JwtTenantFilterTest" --tests "uk.jtoye.core.security.TenantFilterTest" --tests "uk.jtoye.core.gdpr.GdprControllerTest"` exits 0.
