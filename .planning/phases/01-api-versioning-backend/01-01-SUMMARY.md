---
phase: 01-api-versioning-backend
plan: 01
subsystem: api
tags: [spring-boot, webmvc, path-prefix, api-versioning, integration-tests]

# Dependency graph
requires: []
provides:
  - "/api/v1/ prefix on all 7 versioned controller packages via WebMvcConfigurer"
  - "Updated integration tests for versioned paths"
affects: [02-api-versioning-edge-frontend, gateway, frontend]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "PathMatchConfigurer.addPathPrefix with HandlerTypePredicate.forBasePackage for selective URL prefixing"

key-files:
  created: []
  modified:
    - core-java/src/main/java/uk/jtoye/core/config/WebConfig.java
    - core-java/src/test/java/uk/jtoye/core/integration/ShopControllerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/integration/CustomerControllerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/integration/FinancialTransactionControllerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/sync/SyncControllerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/RateLimitIntegrationTest.java.disabled

key-decisions:
  - "Used org.springframework.web.method.HandlerTypePredicate (not org.springframework.web.servlet.handler) for Spring 6.2 compatibility"
  - "Left RateLimitIntegrationTest.disabled paths unchanged as they are test-only paths not mapped to real controllers -- added documentation note"

patterns-established:
  - "API versioning via addPathPrefix: new controllers in versioned packages get /api/v1/ automatically"
  - "Exempt packages (payment, storefront, tenant, controller, root) keep original paths"

requirements-completed: [APIV-01, APIV-04, APIV-05]

# Metrics
duration: 4min
completed: 2026-04-07
---

# Phase 1 Plan 01: API Versioning Backend Summary

**Spring WebMvcConfigurer addPathPrefix applying /api/v1/ to 7 controller packages with package-based HandlerTypePredicate, all 310+ tests passing**

## Performance

- **Duration:** 4 min
- **Started:** 2026-04-07T23:28:03Z
- **Completed:** 2026-04-07T23:31:59Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments
- Added configurePathMatch to WebConfig with addPathPrefix("/api/v1/") targeting 7 specific controller packages
- Updated 26 MockMvc paths across 4 active integration test files to use /api/v1/ prefix
- Verified exempt paths (/health, /public/**, /actuator/**) remain unprefixed
- Full test suite (310+ tests) passes green

## Task Commits

Each task was committed atomically:

1. **Task 1: Add configurePathMatch to WebConfig with /api/v1/ prefix** - `37d1314` (feat)
2. **Task 2: Update all MockMvc test paths to /api/v1/ prefix** - `78a63c1` (feat)

## Files Created/Modified
- `core-java/src/main/java/uk/jtoye/core/config/WebConfig.java` - Added configurePathMatch override with addPathPrefix for /api/v1/ targeting 7 packages
- `core-java/src/test/java/uk/jtoye/core/integration/ShopControllerIntegrationTest.java` - Updated 6 mockMvc paths, /health left exempt
- `core-java/src/test/java/uk/jtoye/core/integration/CustomerControllerIntegrationTest.java` - Updated 11 mockMvc paths (post/get/put/delete)
- `core-java/src/test/java/uk/jtoye/core/integration/FinancialTransactionControllerIntegrationTest.java` - Updated 8 mockMvc paths (post/get)
- `core-java/src/test/java/uk/jtoye/core/sync/SyncControllerIntegrationTest.java` - Updated 1 mockMvc path (post /sync/batch)
- `core-java/src/test/java/uk/jtoye/core/security/RateLimitIntegrationTest.java.disabled` - Added documentation note about path updates needed when re-enabled

## Decisions Made
- Used `org.springframework.web.method.HandlerTypePredicate` instead of `org.springframework.web.servlet.handler.HandlerTypePredicate` -- the class moved packages in Spring Framework 6.2 (shipped with Spring Boot 3.4.2)
- Left RateLimitIntegrationTest.disabled test-only paths (/api/health-test, /api/test-endpoint, etc.) unchanged -- these don't map to real controllers, added note for when test is re-enabled

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed HandlerTypePredicate import package**
- **Found during:** Task 1 (Add configurePathMatch to WebConfig)
- **Issue:** Plan specified `org.springframework.web.servlet.handler.HandlerTypePredicate` but Spring 6.2 moved this class to `org.springframework.web.method.HandlerTypePredicate`
- **Fix:** Changed import to `org.springframework.web.method.HandlerTypePredicate`
- **Files modified:** core-java/src/main/java/uk/jtoye/core/config/WebConfig.java
- **Verification:** compileJava succeeds, all tests pass
- **Committed in:** 37d1314 (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Import path correction required for Spring 6.2 compatibility. No scope creep.

## Issues Encountered
None beyond the import deviation above.

## User Setup Required
None - no external service configuration required.

## Known Stubs
None - all functionality is wired and verified.

## Next Phase Readiness
- Backend API versioning complete, all endpoints accessible under /api/v1/
- Edge gateway (Go) and frontend (Next.js) can be updated to use /api/v1/ paths
- SpringDoc 2.8.6 automatically picks up addPathPrefix -- Swagger UI will show /api/v1/ paths without config changes
- SecurityConfig unchanged -- anyRequest().authenticated() already covers /api/v1/** paths

---
*Phase: 01-api-versioning-backend*
*Completed: 2026-04-07*

## Self-Check: PASSED
