# Phase 3: Vendor Marketing Backend - Research

**Researched:** 2026-04-07
**Domain:** Spring Boot CRUD APIs, Flyway migrations, PostgreSQL RLS, multi-tenant data modelling
**Confidence:** HIGH

## Summary

This phase extends the existing `ShopPromotion` entity with discount type support (PERCENTAGE/FLAT_AMOUNT), extracts the `Shop.announcements` TEXT[] column into a dedicated `ShopAnnouncement` entity with scheduling, and adds public storefront endpoints for customer-facing visibility. All work follows established codebase patterns with no new libraries or infrastructure required.

The codebase already has a working `ShopPromotion` entity, repository, and public storefront pattern. The primary engineering work is: (1) a Flyway migration adding columns to `shop_promotions` and creating `shop_announcements`, (2) data migration from `Shop.announcements` TEXT[] to the new table, (3) new CRUD controllers/services/mappers for both entities, and (4) two new public endpoints on the existing `PublicStorefrontController`.

**Primary recommendation:** Follow existing `ShopController`/`ShopService`/`ShopMapper` patterns exactly. The only novel aspect is the TEXT[] data migration and column drop in a single Flyway migration, which requires careful ordering (create table, migrate data, drop column).

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** Add Flyway migration with `discount_type` VARCHAR defaulting to `PERCENTAGE` and `discount_amount_pennies` INTEGER nullable on existing `shop_promotions` table. Keep existing `discount_percent` column.
- **D-02:** New promotions specify either `discountPercent` OR `discountAmountPennies` based on `discountType` (PERCENTAGE or FLAT_AMOUNT). Validation enforces mutual exclusivity.
- **D-03:** Pennies pattern follows existing convention (`delivery_fee_pennies`, `vat_amount_pennies` in orders table).
- **D-04:** New `ShopAnnouncement` entity with: `id` (UUID), `tenantId` (UUID), `shopId` (UUID), `title` (VARCHAR 200, not null), `body` (TEXT, nullable), `validFrom` (OffsetDateTime), `validUntil` (OffsetDateTime), `active` (Boolean, default true), `createdAt` (OffsetDateTime, immutable).
- **D-05:** Flyway migration creates `shop_announcements` table, migrates existing `Shop.announcements` TEXT[] data (each string becomes a row with title=string, body=null, active=true, no date bounds), then drops `announcements` column from `shops` table.
- **D-06:** Add RLS policy on `shop_announcements` matching existing `shop_promotions` RLS pattern.
- **D-07:** Add `GET /public/shops/{slug}/promotions` to PublicStorefrontController -- returns active promotions where `active=true AND validFrom <= now AND validUntil >= now`.
- **D-08:** Add `GET /public/shops/{slug}/announcements` to PublicStorefrontController -- same active/date filtering.
- **D-09:** Public endpoints are exempt from versioning (follows Phase 1 decision D-02). No auth required.

### Claude's Discretion
- New `PromotionController` at `/api/v1/promotions` (versioned, authenticated) with standard CRUD
- New `AnnouncementController` at `/api/v1/announcements` (versioned, authenticated) with standard CRUD
- `PromotionService` + `AnnouncementService` following existing service-repository pattern
- MapStruct mappers for DTOs (matching `ShopMapper`, `ProductMapper` patterns)
- RLS via existing `TenantContext` + `@TenantSetLocal` aspect
- Pagination following existing Spring Data Pageable pattern

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| VMKT-01 | Promotion CRUD -- vendor can create, edit, delete promotions. Flyway migration adds discountType enum and discountAmountPennies to existing ShopPromotion entity. New PromotionController + PromotionService. | Existing `ShopPromotion` entity at line 9 of ShopPromotion.java; `ShopController`/`ShopService` provide exact CRUD pattern; V28 migration shows table structure to extend |
| VMKT-02 | Promotion scheduling -- validFrom/validUntil with timezone-aware date handling | Already implemented: `ShopPromotion` has `validFrom`/`validUntil` as `OffsetDateTime`; `ShopPromotionRepository.findActiveByShopId()` uses `CURRENT_TIMESTAMP` comparison; public storefront already uses `UK_ZONE = ZoneId.of("Europe/London")` |
| VMKT-03 | Announcement entity extracted from Shop.announcements TEXT[] with Flyway migration | `Shop.java` line 78-79 has `announcements` TEXT[] column; V28 migration created it; data migration SQL pattern documented in Architecture Patterns section |
| VMKT-04 | Announcement CRUD -- vendor can create, edit, delete announcements with scheduling | New entity mirrors `ShopPromotion` pattern; CRUD follows `ShopController`/`ShopService` pattern; scheduling reuses same `validFrom`/`validUntil` + `OffsetDateTime` approach |
</phase_requirements>

## Project Constraints (from CLAUDE.md)

- **Feature branches only** -- never commit to main directly. Use `feature/<name>` branch workflow.
- **No Co-Authored-By trailers** in commits.
- **All new code requires tests** -- project standard is 310+ tests passing.
- **Docker rebuild** required after code changes before E2E testing.
- **Tech stack locked:** Spring Boot 3.4.2, JDK 21, PostgreSQL 15, Flyway.

## Standard Stack

### Core (already in project -- no new dependencies)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.4.2 | Web framework, DI, transactions | Already in use |
| Spring Data JPA | 3.4.2 (via Boot) | Repository pattern, Pageable | Already in use |
| MapStruct | 1.5.5 | DTO mapping | Already in use for ShopMapper, ProductMapper |
| Flyway | (via Boot) | Database migrations | Already in use, V28 is latest |
| SpringDoc OpenAPI | 2.8.6 | Swagger annotations | Already in use |
| Jakarta Validation | (via Boot) | @Valid, @NotBlank, etc. | Already in use |

### Supporting
No new dependencies needed. All required libraries are already present.

### Alternatives Considered
None -- all decisions are locked and use existing project patterns.

**Installation:** No new packages to install.

## Architecture Patterns

### Recommended Project Structure
```
core-java/src/main/java/uk/jtoye/core/
├── shop/
│   ├── ShopPromotion.java          # EXTEND: add discountType + discountAmountPennies fields
│   ├── ShopPromotionRepository.java # EXTEND: add pagination queries
│   ├── PromotionController.java     # NEW: CRUD at /promotions (versioned)
│   ├── PromotionService.java        # NEW: follows ShopService pattern
│   ├── PromotionMapper.java         # NEW: follows ShopMapper pattern
│   ├── dto/
│   │   ├── PromotionDto.java        # NEW: response DTO
│   │   └── CreatePromotionRequest.java  # NEW: request DTO with validation
│   ├── ShopAnnouncement.java        # NEW: entity
│   ├── ShopAnnouncementRepository.java  # NEW: repository
│   ├── AnnouncementController.java  # NEW: CRUD at /announcements (versioned)
│   ├── AnnouncementService.java     # NEW: follows ShopService pattern
│   ├── AnnouncementMapper.java      # NEW: follows ShopMapper pattern
│   └── dto/
│       ├── AnnouncementDto.java     # NEW: response DTO
│       └── CreateAnnouncementRequest.java  # NEW: request DTO
├── storefront/
│   ├── PublicStorefrontController.java  # EXTEND: add 2 new GET endpoints
│   ├── PublicStorefrontService.java     # EXTEND: add promotion/announcement lookup methods
│   └── dto/
│       ├── PublicPromotionDto.java       # NEW: public-facing promotion DTO
│       ├── PublicAnnouncementDto.java    # NEW: public-facing announcement DTO
│       └── ShopConfigDto.java           # UPDATE: change announcements from List<String> to List<AnnouncementDto>
```

### Pattern 1: Controller-Service-Repository CRUD
**What:** Authenticated CRUD controller delegating to service, which uses repository + mapper
**When to use:** Every new domain entity
**Example (from existing ShopController):**
```java
@RestController
@RequestMapping("/promotions")  // Gets /api/v1/ prefix via WebMvcConfigurer
@Tag(name = "Promotions", description = "Promotion management endpoints")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class PromotionController {
    private final PromotionService promotionService;

    public PromotionController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @GetMapping
    @Operation(summary = "List promotions")
    public Page<PromotionDto> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return promotionService.getAllPromotions(pageable);
    }
    // ... CRUD methods follow ShopController pattern
}
```

### Pattern 2: MapStruct Mapper
**What:** Interface-based compile-time mapper for entity-to-DTO conversion
**When to use:** Every entity needs a mapper
**Example (from existing ShopMapper):**
```java
@Mapper(componentModel = "spring")
public interface PromotionMapper {
    PromotionDto toDto(ShopPromotion entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    ShopPromotion toEntity(CreatePromotionRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    void updateEntity(CreatePromotionRequest request, @MappingTarget ShopPromotion entity);
}
```

### Pattern 3: Public Storefront Endpoint
**What:** Unauthenticated endpoint that resolves shop by slug, sets TenantContext, queries data
**When to use:** Customer-facing read endpoints
**Example (from existing PublicStorefrontController):**
```java
@GetMapping("/shops/{slug}/promotions")
@Operation(summary = "Get active promotions", description = "Returns currently active promotions for a published shop.")
public ResponseEntity<List<PublicPromotionDto>> getShopPromotions(@PathVariable String slug) {
    return ResponseEntity.ok(storefrontService.getActivePromotions(slug));
}
```

### Pattern 4: Discount Type Validation (mutual exclusivity)
**What:** Custom validation ensuring discountType matches the provided amount field
**When to use:** CreatePromotionRequest validation
**Example:**
```java
// In CreatePromotionRequest or a custom validator:
// If discountType == PERCENTAGE: discountPercent required, discountAmountPennies must be null
// If discountType == FLAT_AMOUNT: discountAmountPennies required, discountPercent must be null
// Default discountType to PERCENTAGE if not specified (backward compatibility)
```

### Anti-Patterns to Avoid
- **Creating new packages for promotions/announcements:** Keep in `shop/` package -- they are shop sub-entities, not independent domains.
- **Using `@Enumerated(EnumType.ORDINAL)` for discountType:** Use VARCHAR/STRING to avoid fragile ordinal coupling. The Flyway migration uses VARCHAR, so the JPA entity should use `@Enumerated(EnumType.STRING)`.
- **Hardcoding timezone:** Use `OffsetDateTime` throughout. The `UK_ZONE` constant in PublicStorefrontService is only for opening-hours display logic -- scheduling uses UTC offset.
- **Forgetting TenantContext.clear():** Always use try/finally when setting TenantContext manually in storefront methods.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| DTO mapping | Manual getters/setters in service | MapStruct `@Mapper` interface | Compile-time type safety, less boilerplate, matches existing pattern |
| Pagination | Custom offset/limit SQL | Spring Data `Pageable` + `Page<T>` | Already used everywhere, handles count queries automatically |
| Date filtering | Custom SQL WHERE clauses | `@Query` with JPQL `CURRENT_TIMESTAMP` | Matches existing `findActiveByShopId()` pattern |
| Tenant isolation | Manual tenant_id checks | RLS + `TenantSetLocalAspect` | Automatic, impossible to bypass in application code |
| Swagger docs | Manual OpenAPI YAML | `@Operation`, `@ApiResponse` annotations | Already in use, auto-generated from code |

## Common Pitfalls

### Pitfall 1: RLS Setting Name Inconsistency
**What goes wrong:** The V28 migration (shop_promotions) and V27 (reviews) use `current_setting('app.tenant_id', true)` for their write policies, but the `TenantSetLocalAspect` sets `app.current_tenant_id` (note: "current_" prefix). This means write operations via the aspect will NOT match the RLS policy.
**Why it happens:** V27/V28 used a different GUC name than V1-V15 established. The read policies use `USING (true)` so this bug is invisible for reads.
**How to avoid:** The new V29 migration for `shop_announcements` MUST use `app.current_tenant_id` (matching what TenantSetLocalAspect actually sets). Optionally, also fix V28's `shop_promotions_write` policy in the same migration.
**Warning signs:** Writes to shop_promotions or shop_announcements fail with RLS violation errors when using the standard service pattern.

### Pitfall 2: Data Migration Ordering in Flyway
**What goes wrong:** If the migration drops the `announcements` column from `shops` before migrating data, existing announcement strings are lost.
**Why it happens:** Flyway runs DDL and DML in the order written. A careless migration might create the new table and drop the column in the same statement block.
**How to avoid:** Strict three-step order: (1) CREATE TABLE shop_announcements, (2) INSERT INTO shop_announcements SELECT from shops.announcements, (3) ALTER TABLE shops DROP COLUMN announcements. All in one Flyway file (V29).
**Warning signs:** Empty `shop_announcements` table after migration with no error.

### Pitfall 3: Shop.java Entity Desync After Column Drop
**What goes wrong:** After dropping the `announcements` column from `shops`, the `Shop.java` entity still has the `announcements` field. Hibernate will fail on SELECT with "column not found".
**Why it happens:** Flyway runs before Hibernate schema validation. If the entity field is not removed, Hibernate tries to query a column that no longer exists.
**How to avoid:** Remove the `announcements` field and its getter/setter from `Shop.java` in the same commit as the migration. Also update `ShopDto` and `CreateShopRequest` if they reference announcements.
**Warning signs:** Application fails to start with `PSQLException: column shops.announcements does not exist`.

### Pitfall 4: ShopConfigDto Breaking Change
**What goes wrong:** `ShopConfigDto.announcements` is currently `List<String>`. Changing it to `List<AnnouncementDto>` breaks the existing `getShopConfig()` endpoint contract and any frontend code consuming it.
**Why it happens:** The public storefront `/shops/{slug}/config` endpoint already returns announcements.
**How to avoid:** Update `ShopConfigDto` to return announcement objects (with title, body, active, dates). This is a deliberate API evolution. The new dedicated `/shops/{slug}/announcements` endpoint provides the same data. Update `PublicStorefrontService.getShopConfig()` to query from `ShopAnnouncementRepository` instead of `shop.getAnnouncements()`.
**Warning signs:** Frontend tests or pages that parse announcement strings will break.

### Pitfall 5: Announcements Without Date Bounds
**What goes wrong:** Migrated announcements from TEXT[] have no `validFrom`/`validUntil`. The public endpoint filters on `validFrom <= now AND validUntil >= now`, so migrated announcements would be invisible.
**Why it happens:** D-05 specifies "no date bounds" for migrated data but D-08 specifies date filtering for the public endpoint.
**How to avoid:** Set `validFrom` to `NOW()` and `validUntil` to a far-future date (e.g., `'9999-12-31'::timestamptz`) for migrated announcements, OR adjust the public query to treat NULL dates as "always valid". Recommend: set dates in migration for consistency.
**Warning signs:** Migrated announcements don't appear on public storefront.

## Code Examples

### Flyway Migration V29 (Critical Structure)
```sql
-- V29: Vendor marketing -- discount types and announcement entity

-- Step 1: Extend shop_promotions with discount type support
ALTER TABLE shop_promotions
    ADD COLUMN discount_type VARCHAR(20) NOT NULL DEFAULT 'PERCENTAGE',
    ADD COLUMN discount_amount_pennies INTEGER;

-- Step 2: Create shop_announcements table
CREATE TABLE shop_announcements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    shop_id UUID NOT NULL REFERENCES shops(id),
    title VARCHAR(200) NOT NULL,
    body TEXT,
    valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shop_announcements_shop ON shop_announcements(shop_id);
CREATE INDEX idx_shop_announcements_active ON shop_announcements(shop_id, active) WHERE active = true;

-- Step 3: RLS (using correct GUC name matching TenantSetLocalAspect)
ALTER TABLE shop_announcements ENABLE ROW LEVEL SECURITY;

CREATE POLICY shop_announcements_read ON shop_announcements
    FOR SELECT USING (true);

CREATE POLICY shop_announcements_write ON shop_announcements
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true));

-- Step 4: Migrate existing TEXT[] announcements to new table
INSERT INTO shop_announcements (tenant_id, shop_id, title, active, valid_from, valid_until)
SELECT s.tenant_id, s.id, unnest(s.announcements), true, NOW(), '9999-12-31T23:59:59Z'::timestamptz
FROM shops s
WHERE s.announcements IS NOT NULL AND array_length(s.announcements, 1) > 0;

-- Step 5: Drop announcements column from shops
ALTER TABLE shops DROP COLUMN announcements;

-- Step 6: Fix V28 shop_promotions RLS to use correct GUC name
DROP POLICY IF EXISTS shop_promotions_write ON shop_promotions;
CREATE POLICY shop_promotions_write ON shop_promotions
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true));
```

### ShopAnnouncement Entity
```java
@Entity
@Table(name = "shop_announcements")
public class ShopAnnouncement {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "valid_from")
    private OffsetDateTime validFrom;

    @Column(name = "valid_until")
    private OffsetDateTime validUntil;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // getters/setters following ShopPromotion pattern
}
```

### DiscountType Enum (new)
```java
public enum DiscountType {
    PERCENTAGE,
    FLAT_AMOUNT
}
```

### ShopPromotion Entity Extension
```java
// Add to existing ShopPromotion.java:
@Enumerated(EnumType.STRING)
@Column(name = "discount_type", nullable = false)
private DiscountType discountType = DiscountType.PERCENTAGE;

@Column(name = "discount_amount_pennies")
private Integer discountAmountPennies;
```

### Mutual Exclusivity Validation (in CreatePromotionRequest)
```java
@AssertTrue(message = "PERCENTAGE type requires discountPercent; FLAT_AMOUNT requires discountAmountPennies")
private boolean isDiscountValid() {
    if (discountType == null || discountType == DiscountType.PERCENTAGE) {
        return discountPercent != null && discountAmountPennies == null;
    } else {
        return discountAmountPennies != null && discountPercent == null;
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Shop.announcements TEXT[] | Dedicated ShopAnnouncement entity | This phase (V29) | Enables scheduling, CRUD, RLS |
| discountPercent only | discountType enum + discountAmountPennies | This phase (V29) | Supports flat-amount discounts |
| Announcements in ShopConfigDto | Dedicated public endpoint + updated config | This phase | Richer announcement data |

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito (via Spring Boot Test) |
| Config file | `core-java/build.gradle.kts` (Spring Boot Test dependency) |
| Quick run command | `cd core-java && ./gradlew test --tests "uk.jtoye.core.shop.*" -x bootJar` |
| Full suite command | `cd core-java && ./gradlew test -x bootJar` |

### Phase Requirements to Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| VMKT-01 | Promotion CRUD (create, update, delete with discount types) | unit | `./gradlew test --tests "uk.jtoye.core.shop.PromotionServiceTest" -x bootJar` | No -- Wave 0 |
| VMKT-01 | Discount type mutual exclusivity validation | unit | `./gradlew test --tests "uk.jtoye.core.shop.PromotionServiceTest.createPromotion_flatAmountWithPercent_throws" -x bootJar` | No -- Wave 0 |
| VMKT-02 | Promotion scheduling (validFrom/validUntil filtering) | unit | `./gradlew test --tests "uk.jtoye.core.shop.PromotionServiceTest.getActivePromotions_filtersExpired" -x bootJar` | No -- Wave 0 |
| VMKT-03 | Announcement entity creation and data integrity | unit | `./gradlew test --tests "uk.jtoye.core.shop.AnnouncementServiceTest" -x bootJar` | No -- Wave 0 |
| VMKT-04 | Announcement CRUD with scheduling | unit | `./gradlew test --tests "uk.jtoye.core.shop.AnnouncementServiceTest.createAnnouncement_withScheduling" -x bootJar` | No -- Wave 0 |
| VMKT-01/03 | Public storefront endpoints return active promotions/announcements | unit | `./gradlew test --tests "uk.jtoye.core.storefront.PublicStorefrontServiceTest.getActivePromotions*" -x bootJar` | Partial (file exists, methods do not) |

### Sampling Rate
- **Per task commit:** `cd core-java && ./gradlew test --tests "uk.jtoye.core.shop.*" -x bootJar`
- **Per wave merge:** `cd core-java && ./gradlew test -x bootJar`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `core-java/src/test/java/uk/jtoye/core/shop/PromotionServiceTest.java` -- covers VMKT-01, VMKT-02
- [ ] `core-java/src/test/java/uk/jtoye/core/shop/AnnouncementServiceTest.java` -- covers VMKT-03, VMKT-04
- [ ] New test methods in `PublicStorefrontServiceTest.java` -- covers VMKT-01 (public endpoint), VMKT-03 (public endpoint)

## Open Questions

1. **RLS GUC name inconsistency (app.tenant_id vs app.current_tenant_id)**
   - What we know: V27/V28 write policies use `app.tenant_id` but the aspect sets `app.current_tenant_id`. This means write operations through the standard service pattern will fail RLS checks for shop_promotions (existing bug).
   - What's unclear: Whether this has been caught in production or if writes happen through a different code path.
   - Recommendation: Fix the V28 `shop_promotions_write` policy in V29 migration. Use `app.current_tenant_id` for the new `shop_announcements` table. This is documented in the migration example above.

2. **ShopConfigDto API contract change**
   - What we know: The `/public/shops/{slug}/config` endpoint currently returns `announcements` as `List<String>`. After migration, this changes to structured objects.
   - What's unclear: Whether any frontend code depends on the string format.
   - Recommendation: Accept the breaking change since this is an internal API and the frontend (Phase 4) will be updated. Document in release notes.

## Sources

### Primary (HIGH confidence)
- `ShopPromotion.java` -- current entity structure, fields, annotations
- `Shop.java` lines 78-79 -- announcements TEXT[] column definition
- `ShopPromotionRepository.java` -- existing active query pattern
- `V28__shop_config.sql` -- table creation and RLS pattern
- `ShopController.java` / `ShopService.java` -- CRUD pattern to replicate
- `PublicStorefrontController.java` / `PublicStorefrontService.java` -- public endpoint pattern
- `ShopMapper.java` / `ProductMapper.java` -- MapStruct mapper pattern
- `TenantSetLocalAspect.java` -- RLS GUC name (`app.current_tenant_id`)
- `V1__base_schema.sql` -- `current_tenant_id()` function definition

### Secondary (MEDIUM confidence)
- `ShopConfigDto.java` -- current announcement format (List<String>)
- `CreateShopRequest.java` -- DTO validation pattern

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- no new dependencies, all patterns exist in codebase
- Architecture: HIGH -- direct replication of existing ShopController/ShopService/ShopMapper patterns
- Pitfalls: HIGH -- RLS inconsistency verified by reading both migration SQL and Java aspect source code
- Migration: HIGH -- TEXT[] unnest pattern is standard PostgreSQL

**Research date:** 2026-04-07
**Valid until:** 2026-05-07 (stable -- no external dependency changes)
