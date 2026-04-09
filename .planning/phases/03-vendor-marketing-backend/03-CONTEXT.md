# Phase 3: Vendor Marketing Backend - Context

**Gathered:** 2026-04-08
**Status:** Ready for planning

<domain>
## Phase Boundary

Promotion and announcement CRUD APIs with scheduling. Extends existing ShopPromotion entity with discount type support, extracts Shop.announcements TEXT[] to dedicated entity, and adds public storefront endpoints for customer-facing visibility.

</domain>

<decisions>
## Implementation Decisions

### Discount Model
- **D-01:** Add Flyway migration with `discount_type` VARCHAR defaulting to `PERCENTAGE` and `discount_amount_pennies` INTEGER nullable on existing `shop_promotions` table. Keep existing `discount_percent` column.
- **D-02:** New promotions specify either `discountPercent` OR `discountAmountPennies` based on `discountType` (PERCENTAGE or FLAT_AMOUNT). Validation enforces mutual exclusivity.
- **D-03:** Pennies pattern follows existing convention (`delivery_fee_pennies`, `vat_amount_pennies` in orders table).

### Announcement Migration
- **D-04:** New `ShopAnnouncement` entity with: `id` (UUID), `tenantId` (UUID), `shopId` (UUID), `title` (VARCHAR 200, not null), `body` (TEXT, nullable), `validFrom` (OffsetDateTime), `validUntil` (OffsetDateTime), `active` (Boolean, default true), `createdAt` (OffsetDateTime, immutable).
- **D-05:** Flyway migration creates `shop_announcements` table, migrates existing `Shop.announcements` TEXT[] data (each string becomes a row with title=string, body=null, active=true, no date bounds), then drops `announcements` column from `shops` table.
- **D-06:** Add RLS policy on `shop_announcements` matching existing `shop_promotions` RLS pattern.

### Storefront Visibility
- **D-07:** Add `GET /public/shops/{slug}/promotions` to PublicStorefrontController — returns active promotions where `active=true AND validFrom <= now AND validUntil >= now`.
- **D-08:** Add `GET /public/shops/{slug}/announcements` to PublicStorefrontController — same active/date filtering.
- **D-09:** Public endpoints are exempt from versioning (follows Phase 1 decision D-02). No auth required.

### Claude's Discretion
- New `PromotionController` at `/api/v1/promotions` (versioned, authenticated) with standard CRUD
- New `AnnouncementController` at `/api/v1/announcements` (versioned, authenticated) with standard CRUD
- `PromotionService` + `AnnouncementService` following existing service-repository pattern
- MapStruct mappers for DTOs (matching `ShopMapper`, `ProductMapper` patterns)
- RLS via existing `TenantContext` + `@TenantSetLocal` aspect
- Pagination following existing Spring Data Pageable pattern

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Existing Entity (extend, don't recreate)
- `core-java/src/main/java/uk/jtoye/core/shop/ShopPromotion.java` — Current entity with discountPercent only. Add discountType + discountAmountPennies via migration.
- `core-java/src/main/java/uk/jtoye/core/shop/ShopPromotionRepository.java` — Existing repository interface.

### Announcement Source (migrate from)
- `core-java/src/main/java/uk/jtoye/core/shop/Shop.java` — Lines 78-79: `announcements` TEXT[] column to be extracted and dropped.

### Pattern References (follow these)
- `core-java/src/main/java/uk/jtoye/core/shop/ShopController.java` — Controller pattern for new PromotionController
- `core-java/src/main/java/uk/jtoye/core/shop/ShopService.java` — Service pattern with tenant scoping
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java` — Public endpoint pattern for storefront visibility
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java` — Public service pattern (no auth, shop-slug based lookup)

### Database Migrations
- `core-java/src/main/resources/db/migration/` — Flyway migrations (currently V1-V28). New migration will be V29.

### Security
- `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java` — Permit patterns for /public/** already in place

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ShopPromotion` entity: already has tenantId, shopId, label, discountPercent, validFrom, validUntil, active — extend with discountType + discountAmountPennies
- `ShopPromotionRepository`: JpaRepository already exists — add custom query methods
- `PublicStorefrontController`: already has `/public/shops/{slug}/products` and `/public/shops/{slug}/reviews` patterns — replicate for promotions/announcements
- MapStruct mappers: `ShopMapper`, `ProductMapper` patterns exist for DTO conversion

### Established Patterns
- Service-Repository pattern with `@TenantSetLocal` aspect for RLS
- Controllers use `@Operation` annotations for Swagger docs
- DTOs use record classes or standard POJOs with MapStruct
- Pagination via Spring Data `Pageable` parameter
- Pennies convention for monetary amounts (integer, not decimal)

### Integration Points
- `SecurityConfig.securityFilterChain()` — `/public/**` already permitted, no changes needed
- `WebConfig.configurePathMatch()` — new controllers in versioned packages get `/api/v1/` prefix automatically (Phase 1)
- `PublicStorefrontService` — add methods for promotion/announcement lookup by shop slug

</code_context>

<specifics>
## Specific Ideas

No specific requirements — standard CRUD following existing codebase patterns.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 03-vendor-marketing-backend*
*Context gathered: 2026-04-08*
