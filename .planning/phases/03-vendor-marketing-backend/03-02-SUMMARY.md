---
phase: 03-vendor-marketing-backend
plan: 02
subsystem: api
tags: [spring-boot, jpa, mapstruct, announcements, storefront, public-api, crud]

requires:
  - phase: 03-vendor-marketing-backend
    plan: 01
    provides: "V29 migration with shop_announcements table, DiscountType enum, ShopPromotion extension"
provides:
  - "ShopAnnouncement JPA entity mapping to shop_announcements table"
  - "ShopAnnouncementRepository with findActiveByShopId date-window query"
  - "AnnouncementController CRUD at /announcements (versioned to /api/v1/announcements)"
  - "AnnouncementService with tenant-scoped create via TenantContext"
  - "AnnouncementMapper (MapStruct) for entity-DTO conversion"
  - "GET /public/shops/{slug}/promotions returning active promotions with discount type info"
  - "GET /public/shops/{slug}/announcements returning active announcements within date window"
  - "ShopConfigDto.announcements changed from List<String> to List<AnnouncementSummary>"
  - "ShopConfigDto.PromotionDto updated with discountType and discountAmountPennies"
  - "AnnouncementServiceTest with 7 unit tests"
  - "PublicStorefrontServiceTest with 4 new tests (13 total)"
affects: [frontend-vendor-dashboard, frontend-storefront]

tech-stack:
  added: []
  patterns: ["Public storefront endpoints for active-only entity queries with date-window filtering"]

key-files:
  created:
    - "core-java/src/main/java/uk/jtoye/core/shop/ShopAnnouncement.java"
    - "core-java/src/main/java/uk/jtoye/core/shop/ShopAnnouncementRepository.java"
    - "core-java/src/main/java/uk/jtoye/core/shop/AnnouncementController.java"
    - "core-java/src/main/java/uk/jtoye/core/shop/AnnouncementService.java"
    - "core-java/src/main/java/uk/jtoye/core/shop/AnnouncementMapper.java"
    - "core-java/src/main/java/uk/jtoye/core/shop/dto/AnnouncementDto.java"
    - "core-java/src/main/java/uk/jtoye/core/shop/dto/CreateAnnouncementRequest.java"
    - "core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicPromotionDto.java"
    - "core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicAnnouncementDto.java"
  modified:
    - "core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java"
    - "core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java"
    - "core-java/src/main/java/uk/jtoye/core/storefront/dto/ShopConfigDto.java"
    - "core-java/src/test/java/uk/jtoye/core/shop/AnnouncementServiceTest.java"
    - "core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java"

key-decisions:
  - "ShopAnnouncementRepository.findActiveByShopId allows NULL validFrom/validUntil (announcements without scheduling are always active)"
  - "Public endpoints under /public/ (unversioned per D-09), authenticated endpoints under /announcements (auto-versioned to /api/v1/)"
  - "Resolved Plan 03-01 stub: PublicStorefrontService.getShopConfig() now queries ShopAnnouncementRepository instead of returning empty list"

patterns-established:
  - "Public storefront entity lookup: resolve shop by slug (published=true), query active entities by shopId, map to public DTO"

requirements-completed: [VMKT-03, VMKT-04]

duration: 5min
completed: 2026-04-08
---

# Phase 3 Plan 2: Announcement CRUD and Public Storefront Endpoints Summary

**ShopAnnouncement entity with full CRUD, public endpoints for promotions and announcements, ShopConfigDto upgraded to structured records with discount type info**

## Performance

- **Duration:** 5 min
- **Started:** 2026-04-08T08:58:47Z
- **Completed:** 2026-04-08T09:04:21Z
- **Tasks:** 2
- **Files modified:** 14

## Accomplishments
- ShopAnnouncement JPA entity mapping to shop_announcements table per D-04, with repository supporting active date-window queries
- Full AnnouncementController CRUD at /announcements with pagination, validation, Swagger docs, and JWT security
- Public GET /public/shops/{slug}/promotions returns active promotions including discountType and discountAmountPennies
- Public GET /public/shops/{slug}/announcements returns active announcements within scheduling window
- ShopConfigDto.announcements upgraded from List<String> to List<AnnouncementSummary> records queried from shop_announcements table
- ShopConfigDto.PromotionDto updated with discountType and discountAmountPennies fields
- 11 new unit tests (7 AnnouncementServiceTest + 4 PublicStorefrontServiceTest), full test suite green

## Task Commits

Each task was committed atomically:

1. **Task 1: ShopAnnouncement entity, repository, AnnouncementController CRUD, service, mapper, DTOs, and 7 unit tests** - `81b20e8` (feat)
2. **Task 2: Public storefront endpoints for promotions and announcements, ShopConfigDto update** - `8aa1a97` (feat)

## Files Created/Modified
- `core-java/src/main/java/uk/jtoye/core/shop/ShopAnnouncement.java` - JPA entity mapping to shop_announcements table
- `core-java/src/main/java/uk/jtoye/core/shop/ShopAnnouncementRepository.java` - Repository with findActiveByShopId date-window query
- `core-java/src/main/java/uk/jtoye/core/shop/AnnouncementController.java` - CRUD REST controller at /announcements
- `core-java/src/main/java/uk/jtoye/core/shop/AnnouncementService.java` - Business logic with TenantContext scoping
- `core-java/src/main/java/uk/jtoye/core/shop/AnnouncementMapper.java` - MapStruct mapper for entity-DTO conversion
- `core-java/src/main/java/uk/jtoye/core/shop/dto/AnnouncementDto.java` - Response DTO
- `core-java/src/main/java/uk/jtoye/core/shop/dto/CreateAnnouncementRequest.java` - Request DTO with validation
- `core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicPromotionDto.java` - Public promotion DTO with discount type info
- `core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicAnnouncementDto.java` - Public announcement DTO
- `core-java/src/main/java/uk/jtoye/core/storefront/dto/ShopConfigDto.java` - Upgraded announcements to AnnouncementSummary records, PromotionDto with discount type
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java` - Added promotions and announcements public endpoints
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java` - Added getActivePromotions, getActiveAnnouncements, wired ShopAnnouncementRepository
- `core-java/src/test/java/uk/jtoye/core/shop/AnnouncementServiceTest.java` - 7 unit tests for AnnouncementService
- `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java` - 4 new tests for public endpoints and config

## Decisions Made
- ShopAnnouncementRepository.findActiveByShopId allows NULL validFrom/validUntil so announcements without scheduling are always visible on the public endpoint
- Public endpoints at /public/shops/{slug}/promotions and /public/shops/{slug}/announcements follow existing unversioned public pattern (D-09)
- Resolved Plan 03-01 known stub: getShopConfig() now queries ShopAnnouncementRepository instead of returning empty list

## Deviations from Plan

None - plan executed exactly as written.

## Known Stubs

None - all stubs from Plan 03-01 have been resolved.

## Issues Encountered
- JDK 25 installed as default but project requires JDK 21 -- resolved by setting JAVA_HOME (same as Plan 03-01)

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Vendor marketing backend is complete: PromotionController + AnnouncementController for vendor CRUD, public endpoints for customer-facing visibility
- Frontend can integrate with /api/v1/promotions, /api/v1/announcements (authenticated), and /public/shops/{slug}/promotions, /public/shops/{slug}/announcements (public)
- ShopConfigDto provides structured announcement and promotion data for server-driven UI

## Self-Check: PASSED
