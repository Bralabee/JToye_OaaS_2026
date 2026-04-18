---
phase: 10-storefront-marketing-render-missing-customer-routes
plan: 01
subsystem: core-java/storefront
tags: [test, mockmvc, storefront, public-api, STFR-01, STFR-02]
requires: []
provides:
  - "Controller-level MockMvc coverage for GET /public/shops/{slug}/promotions"
  - "Controller-level MockMvc coverage for GET /public/shops/{slug}/announcements"
affects:
  - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerTest.java
tech-stack:
  added: []
  patterns:
    - "@WebMvcTest slice + @MockitoBean PublicStorefrontService"
    - "when(...).thenReturn(...) / thenThrow(ResourceNotFoundException) for 404 path"
    - "jsonPath assertions on top-level JSON array response"
key-files:
  created: []
  modified:
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerTest.java
decisions: []
metrics:
  duration_minutes: ~5
  tasks_completed: 3
  files_touched: 1
  tests_added: 4
  completed_date: 2026-04-15
---

# Phase 10 Plan 01: Storefront Marketing MockMvc Tests Summary

Added 4 controller-slice MockMvc tests that lock in the REST contract for the existing `GET /public/shops/{slug}/promotions` and `/announcements` endpoints (STFR-01, STFR-02), closing the Wave 0 gap from 10-RESEARCH §Validation Architecture with no production-code changes.

## Tasks Completed

| # | Task | Status |
|---|------|--------|
| 1 | Add promotions MockMvc tests (success + 404) | Done |
| 2 | Add announcements MockMvc tests (success + 404) | Done |
| 3 | Full backend regression + atomic commit | Done |

## Files Modified

- `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerTest.java` (+55 lines, +4 imports: `DiscountType`, `PublicAnnouncementDto`, `PublicPromotionDto`, plus 4 new `@Test` methods)

No production source files were touched.

## Tests Added

1. `getShopPromotions_returns200WithActivePromotions` — stubs `storefrontService.getActivePromotions("test-shop")` to return one `PublicPromotionDto(label="Lunch special", discountType=PERCENTAGE, discountPercent=10, category="Mains")`, asserts HTTP 200 and `$[0].label`, `$[0].discountPercent`, `$[0].category`.
2. `getShopPromotions_nonexistent_returns404` — stubs service to throw `ResourceNotFoundException("Shop not found: ghost")`, asserts HTTP 404 (handled by `GlobalExceptionHandler`).
3. `getShopAnnouncements_returns200WithActiveAnnouncements` — stubs service to return one `PublicAnnouncementDto(title="Closed Sunday", body="Back Monday")`, asserts HTTP 200 and `$[0].title`, `$[0].body`.
4. `getShopAnnouncements_nonexistent_returns404` — stubs service to throw `ResourceNotFoundException`, asserts HTTP 404.

## Verification Evidence

### Targeted run
`JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64 ./gradlew :core-java:test --tests 'uk.jtoye.core.storefront.PublicStorefrontControllerTest'`
```
BUILD SUCCESSFUL in 10s
5 actionable tasks: 2 executed, 3 up-to-date
```

### Full backend regression
`JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64 ./gradlew :core-java:test --rerun-tasks`
```
BUILD SUCCESSFUL in 24s
5 actionable tasks: 5 executed
```

Aggregated from `core-java/build-local/test-results/test/TEST-*.xml` (41 suites):
- **tests: 339**, failures: 0, errors: 0, skipped: 0
- Delta vs M2 baseline (335): **+4** — matches expected count.
- `PublicStorefrontControllerTest` suite: `tests="11" failures="0" errors="0"` (7 pre-existing + 4 new).

## Commits

| Hash | Message |
|------|---------|
| `168582a` | `test(stfr): add MockMvc tests for /public/shops/{slug}/promotions + /announcements (STFR-01, STFR-02)` |

Branch: `feat/phase-10-storefront-marketing` (no branch switch performed — commit is on the feature branch as required).

## Deviations from Plan

None — plan executed exactly as written. The plan's `files_modified` target (`core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerTest.java`) already existed with the correct `@WebMvcTest` / `@MockitoBean` skeleton, so the work was strictly additive (imports + 4 `@Test` methods). No architectural or Rule 1/2/3 auto-fixes were needed. Fixture strategy: reused the existing `@WebMvcTest(PublicStorefrontController.class)` slice with `@MockitoBean PublicStorefrontService` — stubbed the service directly (per Pitfall 7 in 10-RESEARCH) rather than seeding DB fixtures, since this is a controller-slice test and the service layer already has its own active-filter tests in `PublicStorefrontServiceTest.java:267-342`.

Note: the active-only filter is asserted at the service layer (existing `PublicStorefrontServiceTest`), not the controller layer — the controller simply delegates and this plan's scope is REST contract shape, not service logic.

## Known Stubs

None.

## Threat Flags

None — no new network surface; endpoints already shipped and tests are purely additive coverage.

## Self-Check: PASSED

- FOUND: `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerTest.java` (modified, 205 lines)
- FOUND: commit `168582a` on `feat/phase-10-storefront-marketing`
- FOUND: `PublicStorefrontControllerTest.xml` with `tests="11" failures="0" errors="0"`
- Full suite: 339/339 passing (335 baseline + 4 new)
