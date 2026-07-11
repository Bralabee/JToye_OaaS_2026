---
phase: 19-full-frontend-experience-overhaul
plan: 02
subsystem: api
tags: [postgres, rls, jpa, testcontainers, spring-boot, seed-data, multi-tenancy]

# Dependency graph
requires:
  - phase: 13-public-storefront
    provides: "PublicStorefrontService.getShopProducts + resolvePublicShopForSlug (sole caller of the menu query)"
  - phase: 18-vendor-onboarding
    provides: "Shop.published state machine + shops RLS (tenant-scoped WITH CHECK)"
provides:
  - "Strictly shop-scoped storefront menu query (no NULL-shop_id bleed)"
  - "Committed dev-profile DemoDataSeeder (idempotent, realistic UK multi-shop data)"
  - "Testcontainers proof of per-shop menu isolation under FORCE RLS"
affects: [checkout, storefront, order-detail, kitchen, seed-data]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Dev-only ApplicationRunner seeder gated @Profile(\"dev\") — never runs in test/prod, not a Flyway migration"
    - "TransactionTemplate + TenantContext seed writes (ScheduledCleanupService pattern) to satisfy RLS GUC without the self-invocation proxy trap"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductRepositoryScopingIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/product/ProductRepository.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductSearchFtsIntegrationTest.java

key-decisions:
  - "Removed the OR p.shopId IS NULL fallback outright — no tenant-wide items feature (LOCKED CONTEXT resolved-question 2)"
  - "Demo data delivered as a dev-profile runtime seeder, NOT a Flyway migration, so it never ships to test/prod (resolved-question 1)"
  - "Seeder also aligns pre-existing NULL-shop_id dev rows round-robin so the live dev volume matches the scoped query"

patterns-established:
  - "Per-shop scoping proof: two shops under one tenant, assert menu disjointness + NULL-shop product absent under NOSUPERUSER RLS"
  - "FTS/#96 tests seed an explicit shop_id (per-tenant published shop) without touching the pinned GIN query plans"

requirements-completed: [UIX-05]

# Metrics
duration: 14min
completed: 2026-07-11
---

# Phase 19 Plan 02: Per-Shop Menu Scoping + Demo Seeder Summary

**Storefront menu query narrowed to `p.shopId = :shopId` (dropping the NULL bleed that duplicated one shop's menu onto every other), backed by a committed dev-profile `DemoDataSeeder` and a Testcontainers per-shop isolation proof.**

## Performance

- **Duration:** ~14 min
- **Started:** 2026-07-11T10:44:00Z
- **Completed:** 2026-07-11T10:56:00Z
- **Tasks:** 2 (Task 2 is TDD — RED/GREEN + tripwire fixup)
- **Files modified:** 5 (2 created, 3 modified)

## Accomplishments
- **UIX-05 root cause closed:** `findAvailableByShopOrderedByCategory` now matches `p.shopId = :shopId` only. The 24/25 unassigned products no longer bleed into (and duplicate across) every shop's menu; a vendor's second shop shows its own menu.
- **Committed reproducible demo data:** `DemoDataSeeder` (dev-profile `ApplicationRunner`) idempotently seeds 3 realistic UK shops (Mama Ade's Kitchen, Peckham Jollof Co., Brixton Village Grill), each with delivery fees + free-delivery thresholds, ~21 products each assigned exactly one shop, and 5 realistic customers. It also aligns any pre-existing NULL-`shop_id` dev rows so the live dev volume matches the scoped query.
- **Isolation proven on real Postgres:** new `ProductRepositoryScopingIntegrationTest` (3 tests) asserts shop A menu ∩ shop B menu == ∅, that a NULL-`shop_id` product is absent from every shop menu, and that an assigned + unassigned duplicate title renders the line item only once — all under production-parity FORCE RLS (NOSUPERUSER).
- **#96 FTS tripwire honoured:** `ProductSearchFtsIntegrationTest` now seeds an explicit `shop_id` per product (per-tenant published shop) while all 14 search assertions and both GIN query-plan pins stay green.

## Task Commits

Each task was committed atomically:

1. **Task 1: Dev-profile DemoDataSeeder** - `3be78e0` (feat)
2. **Task 2 (RED): failing per-shop scoping test** - `3e9ae77` (test)
3. **Task 2 (GREEN): scope menu query to shop_id** - `6f0d6f2` (feat)
4. **Task 2 (tripwire): seed shop_id in FTS tests** - `3020747` (test)

_TDD Task 2 produced test → feat → test commits (RED → GREEN → regression-tripwire fixup)._

## Files Created/Modified
- `core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java` (created) - Dev-only idempotent seeder; realistic UK shops/products/customers; every product gets a `shop_id`; aligns NULL rows.
- `core-java/src/test/java/uk/jtoye/core/product/ProductRepositoryScopingIntegrationTest.java` (created) - 3 Testcontainers tests proving per-shop menu isolation under FORCE RLS.
- `core-java/src/main/java/uk/jtoye/core/product/ProductRepository.java` (modified) - Dropped `OR p.shopId IS NULL` from the storefront menu query; signature unchanged.
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java` (modified) - Refreshed two now-stale "tenant-wide fallback" comments on the sole caller.
- `core-java/src/test/java/uk/jtoye/core/product/ProductSearchFtsIntegrationTest.java` (modified) - Seed a per-tenant shop and assign `shop_id`; FTS assertions + query plans unchanged.

## Decisions Made
- **No "tenant-wide items" feature.** Per the LOCKED CONTEXT decision, the NULL fallback was removed rather than reified into a deliberate feature. Every product belongs to exactly one shop.
- **Seeder over migration.** Demo data ships as a `@Profile("dev")` runtime seeder, not Flyway, so it is excluded from Testcontainers (`test` profile) and prod, and does not perturb integration fixtures/golden files.
- **Round-robin NULL alignment.** Pre-scoping orphan rows are distributed deterministically across the demo shops so no single shop absorbs every orphan; idempotent on re-run.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Refreshed stale "tenant-wide" comments on the query's sole caller**
- **Found during:** Task 2 (GREEN)
- **Issue:** `PublicStorefrontService.getShopProducts` carried a method Javadoc and an inline comment stating products were filtered "or unassigned = tenant-wide" — false the moment the NULL fallback was removed, and actively misleading for the next reader.
- **Fix:** Updated both comments to state the query is strictly shop-scoped (UIX-05, no tenant-wide fallback). No logic change; the plan noted "no caller edit needed" for logic, and this is a doc-accuracy correction.
- **Files modified:** core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
- **Verification:** Mock-based `PublicStorefrontServiceTest` still green; compile clean.
- **Committed in:** `6f0d6f2` (GREEN task commit)

---

**Total deviations:** 1 auto-fixed (1 bug/doc-accuracy)
**Impact on plan:** Comment-only; no scope creep. All plan tasks executed as written.

## Issues Encountered
- The `@Profile("dev")` string appeared twice initially (annotation + a Javadoc `{@code}` reference), tripping the `== 1` acceptance grep; reworded the Javadoc to describe the `dev` profile without the literal token.
- The GREEN ProductRepository comment initially contained the literal `p.shopId IS NULL`, tripping the `shopId IS NULL == 0` acceptance grep; reworded to "NULL-shop_id fallback". Both resolved before their respective commits.

## User Setup Required
None - no external service configuration required. (The dev seeder runs automatically at `dev`-profile startup; `docker compose` sets `SPRING_PROFILES_ACTIVE=dev`.)

## Verification
- `./gradlew :core-java:compileJava` — clean.
- `./gradlew :core-java:integrationTest --tests '*ProductRepositoryScoping*' --tests '*Product*'` — BUILD SUCCESSFUL: `ProductRepositoryScopingIntegrationTest` 3/3, `ProductSearchFtsIntegrationTest` 14/14, 0 failures.
- RED proof captured: before the query change, the NULL-bleed and duplicate-line-item assertions failed (2/3); after, all pass.
- Acceptance greps: `@Profile("dev")` == 1, placeholder pollution == 0, `shopId IS NULL` == 0, `p.shopId = :shopId ORDER BY` == 1.
- Seeder bean confirmed absent under the test profile (`@Profile("dev")`, no default; Testcontainers boot green with it excluded).

## Threat Flags
None - the change only narrows the existing tenant-scoped query by `shop_id` (no widening, no new endpoint/input surface). RLS still tenant-scopes `products`; the isolation test asserts no cross-shop bleed. The dev seeder writes only under the `dev` profile.

## Next Phase Readiness
- Per-shop menus are now correct for the storefront (plan 19-06 checkout / 19-07 order-detail render can rely on shop-scoped product lists).
- No blockers. Full-suite regression (metrics/`docs-freshness`, schema-version narrative) is deferred to plan 19-09 per the phase plan.

## Self-Check: PASSED
- Created files present: `DemoDataSeeder.java`, `ProductRepositoryScopingIntegrationTest.java` — FOUND.
- Modified files present: `ProductRepository.java`, `PublicStorefrontService.java`, `ProductSearchFtsIntegrationTest.java` — FOUND.
- Commits present: `3be78e0`, `3e9ae77`, `6f0d6f2`, `3020747` — FOUND.

---
*Phase: 19-full-frontend-experience-overhaul*
*Completed: 2026-07-11*
