---
phase: 03-vendor-marketing-backend
plan: 01
subsystem: api
tags: [flyway, postgresql, rls, spring-boot, mapstruct, jpa, promotions, crud]

requires:
  - phase: 01-api-versioning
    provides: "/api/v1/ URL prefix via WebMvcConfigurer"
provides:
  - "V29 Flyway migration with shop_announcements table, discount_type column, RLS fix"
  - "DiscountType enum (PERCENTAGE, FLAT_AMOUNT)"
  - "Extended ShopPromotion entity with discountType and discountAmountPennies"
  - "PromotionController CRUD at /promotions (versioned to /api/v1/promotions)"
  - "PromotionService with tenant-scoped create via TenantContext"
  - "PromotionMapper (MapStruct) for entity-DTO conversion"
  - "CreatePromotionRequest with mutual exclusivity validation"
  - "PromotionServiceTest with 7 unit tests"
affects: [03-02-PLAN, frontend-vendor-dashboard]

tech-stack:
  added: []
  patterns: ["Discount type mutual exclusivity validation via @AssertTrue"]

key-files:
  created:
    - "core-java/src/main/resources/db/migration/V29__vendor_marketing.sql"
    - "core-java/src/main/java/uk/jtoye/core/shop/DiscountType.java"
    - "core-java/src/main/java/uk/jtoye/core/shop/PromotionController.java"
    - "core-java/src/main/java/uk/jtoye/core/shop/PromotionService.java"
    - "core-java/src/main/java/uk/jtoye/core/shop/PromotionMapper.java"
    - "core-java/src/main/java/uk/jtoye/core/shop/dto/PromotionDto.java"
    - "core-java/src/main/java/uk/jtoye/core/shop/dto/CreatePromotionRequest.java"
    - "core-java/src/test/java/uk/jtoye/core/shop/PromotionServiceTest.java"
  modified:
    - "core-java/src/main/java/uk/jtoye/core/shop/ShopPromotion.java"
    - "core-java/src/main/java/uk/jtoye/core/shop/Shop.java"
    - "core-java/src/main/java/uk/jtoye/core/shop/ShopPromotionRepository.java"
    - "core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java"

key-decisions:
  - "Fixed V28 shop_promotions_write RLS policy to use app.current_tenant_id (was app.tenant_id)"
  - "Set migrated announcements validFrom=NOW(), validUntil=9999-12-31 to ensure public endpoint visibility"
  - "PublicStorefrontService.getShopConfig() returns empty announcements list until Plan 03-02 wires announcement service"

patterns-established:
  - "Discount type mutual exclusivity: @AssertTrue isDiscountValid() enforces PERCENTAGE xor FLAT_AMOUNT"
  - "PromotionController follows ShopController CRUD pattern with @SecurityRequirement annotations"

requirements-completed: [VMKT-01, VMKT-02]

duration: 5min
completed: 2026-04-07
---

# Phase 3 Plan 1: Vendor Marketing Backend -- Promotion CRUD Summary

**V29 Flyway migration with discount types and announcement extraction, PromotionController CRUD with mutual exclusivity validation, 7 unit tests green**

## Performance

- **Duration:** 5 min
- **Started:** 2026-04-07T07:09:19Z
- **Completed:** 2026-04-07T07:14:30Z
- **Tasks:** 2
- **Files modified:** 12

## Accomplishments
- V29 migration extends shop_promotions with discount_type and discount_amount_pennies, creates shop_announcements table with RLS, migrates TEXT[] data, drops announcements column from shops, fixes V28 RLS bug
- Full PromotionController CRUD at /promotions with pagination, validation, and Swagger documentation
- PromotionServiceTest with 7 unit tests covering both discount types, CRUD operations, and not-found error cases

## Task Commits

Each task was committed atomically:

1. **Task 1: Flyway V29 migration + ShopPromotion entity extension + DiscountType enum** - `bda9dd4` (feat)
2. **Task 2: PromotionController, PromotionService, PromotionMapper, DTOs, and unit tests** - `4fea204` (feat)

## Files Created/Modified
- `core-java/src/main/resources/db/migration/V29__vendor_marketing.sql` - Flyway migration: discount types, announcement table, data migration, RLS fix
- `core-java/src/main/java/uk/jtoye/core/shop/DiscountType.java` - Enum: PERCENTAGE, FLAT_AMOUNT
- `core-java/src/main/java/uk/jtoye/core/shop/ShopPromotion.java` - Extended with discountType and discountAmountPennies fields
- `core-java/src/main/java/uk/jtoye/core/shop/Shop.java` - Removed announcements field and getter/setter
- `core-java/src/main/java/uk/jtoye/core/shop/ShopPromotionRepository.java` - Added pagination query method
- `core-java/src/main/java/uk/jtoye/core/shop/PromotionController.java` - CRUD REST controller at /promotions
- `core-java/src/main/java/uk/jtoye/core/shop/PromotionService.java` - Business logic with TenantContext scoping
- `core-java/src/main/java/uk/jtoye/core/shop/PromotionMapper.java` - MapStruct mapper for entity-DTO conversion
- `core-java/src/main/java/uk/jtoye/core/shop/dto/PromotionDto.java` - Response DTO with all promotion fields
- `core-java/src/main/java/uk/jtoye/core/shop/dto/CreatePromotionRequest.java` - Request DTO with @AssertTrue validation
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java` - Updated to not reference removed announcements field
- `core-java/src/test/java/uk/jtoye/core/shop/PromotionServiceTest.java` - 7 unit tests for PromotionService

## Decisions Made
- Fixed V28 shop_promotions_write RLS policy in V29 migration -- the original used `app.tenant_id` but TenantSetLocalAspect sets `app.current_tenant_id`
- Migrated announcements with validFrom=NOW() and validUntil=9999-12-31 to prevent them from being invisible on the public storefront endpoint
- PublicStorefrontService.getShopConfig() temporarily returns empty announcements list (was reading from Shop.announcements which was dropped) -- Plan 03-02 will wire the ShopAnnouncementRepository

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Updated PublicStorefrontService to handle removed announcements field**
- **Found during:** Task 1 (Shop.java announcements field removal)
- **Issue:** PublicStorefrontService.getShopConfig() called shop.getAnnouncements() which was being removed
- **Fix:** Replaced with List.of() and added comment noting Plan 03-02 will wire the announcement repository
- **Files modified:** core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
- **Verification:** Compilation passes clean
- **Committed in:** bda9dd4 (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Essential to prevent compilation failure after removing Shop.announcements. No scope creep.

## Known Stubs

- `PublicStorefrontService.java` line 91: `config.setAnnouncements(List.of())` -- returns empty list instead of querying shop_announcements table. Plan 03-02 will wire ShopAnnouncementRepository to resolve this.

## Issues Encountered
- JDK 25 installed as default but project requires JDK 21 -- resolved by setting JAVA_HOME to /usr/lib/jvm/jdk-21.0.6-oracle-x64 for Gradle commands

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Promotion CRUD API is complete and ready for frontend integration
- Plan 03-02 (Announcement CRUD + public storefront endpoints) can proceed -- it will create ShopAnnouncement entity, AnnouncementController, and wire announcements into PublicStorefrontService
- V29 migration provides the shop_announcements table that Plan 03-02 needs

## Self-Check: PASSED

---
*Phase: 03-vendor-marketing-backend*
*Completed: 2026-04-07*
