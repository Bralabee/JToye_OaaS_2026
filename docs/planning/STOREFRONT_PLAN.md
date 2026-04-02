# Plan: Public Customer Storefront (Option C — Hybrid Vendor-First with Discovery)

**Created:** 2026-04-01
**Status:** Approved for implementation
**Branch:** TBD (will be `feat/public-storefront`)

## Context

J'Toye OaaS is a multi-tenant UK retail SaaS platform. The vendor back-office dashboard is complete, but there is **zero customer-facing capability**. Customers cannot discover shops, browse products, or place orders. This plan adds a public storefront following the "Hybrid" model: customers discover nearby vendors, pick one, browse their catalog, and order — all without requiring authentication.

The key technical challenge is PostgreSQL RLS: all tables enforce `tenant_id = current_tenant_id()`, which returns NULL for unauthenticated requests, blocking all reads. The solution uses a **permissive SELECT policy** on shops for `published = true` rows, and **service-layer tenant injection** for product queries derived from the shop's tenant_id.

### Business Model: Hybrid Vendor-First with Discovery

Three models were considered:
- **A) Vendor-First** — customer must already know the brand (too limiting)
- **B) Product-First / Marketplace** — Deliveroo model, cross-vendor product search (breaks RLS, massive rework)
- **C) Hybrid** — platform helps customers discover vendors, then shop within one vendor's catalog

**Option C was chosen** because it keeps RLS intact, matches the existing schema (products are tenant-scoped), and provides a clear upgrade path to marketplace-style search via a read-only index later.

**Customer flow:**
```
Customer visits jtoye.uk/shop
  → Sees nearby published vendors (Deliveroo-style grid)
  → Picks "Jollof Express - Brixton"
  → Browses their menu (grouped by category, with images/descriptions)
  → Adds to cart → Checkout (name, email, phone)
  → Gets order confirmation + tracking number
```

---

## Phase 1: Shop Discovery + Product Browsing

### 1. Database Migration — `V16__public_storefront.sql`

**New columns on `shops` (vendor/shop presentation):**
| Column | Type | Notes |
|--------|------|-------|
| `slug` | VARCHAR(100) | Globally unique, URL-safe identifier |
| `description` | TEXT | Marketing blurb ("Authentic Nigerian cuisine, fresh daily") |
| `logo_url` | TEXT | Shop logo/brand image |
| `banner_url` | TEXT | Hero banner image (like Deliveroo shop header) |
| `phone` | VARCHAR(50) | Contact phone |
| `email` | VARCHAR(255) | Contact email |
| `latitude` | DOUBLE PRECISION | Geolocation |
| `longitude` | DOUBLE PRECISION | Geolocation |
| `opening_hours` | JSONB | `{"mon": "09:00-17:00", ...}` |
| `delivery_info` | TEXT | "Free delivery over £30, 3-mile radius" |
| `minimum_order_pennies` | BIGINT (default 0) | Minimum order threshold |
| `published` | BOOLEAN (default false) | Controls public visibility |
| `tags` | VARCHAR(500) | Comma-separated ("Nigerian, West African, Halal") |

**New columns on `products` (rich product presentation):**
| Column | Type | Notes |
|--------|------|-------|
| `description` | TEXT | Customer-facing description |
| `image_url` | TEXT | Product photo (like Tesco product image) |
| `category` | VARCHAR(100) | Grouping label ("Mains", "Sides", "Drinks") |
| `display_order` | INTEGER (default 0) | Sort position within category |
| `available` | BOOLEAN (default true) | In-stock toggle |
| `featured` | BOOLEAN (default false) | "Popular" items (like Deliveroo) |
| `preparation_time_minutes` | INTEGER | Estimated prep time |
| `dietary_tags` | VARCHAR(255) | Comma-separated ("Vegan, Gluten-Free, Spicy") |

**Backfill existing rows:**
- Shops: Generate slugs from `lower(replace(name, ' ', '-')) || '-' || left(id::text, 8)`
- Products: Set `available = true`, `featured = false`, `display_order = 0`

**New RLS policy (additive — PostgreSQL OR's permissive policies):**
```sql
CREATE POLICY shops_public_read ON shops
    FOR SELECT USING (published = true OR tenant_id = current_tenant_id());
```
This lets anyone SELECT published shops. Existing `shops_rls_policy FOR ALL` still governs INSERT/UPDATE/DELETE.

**Audit tables:** Add matching nullable columns to `shops_aud` and `products_aud`.

**Indexes:** Unique on `shops(slug)`, partial on `shops(published)`, index on `products(category)` and `products(display_order)`.

**Files:**
- `core-java/src/main/resources/db/migration/V16__public_storefront.sql` (new)

---

### 2. Shop Entity + DTO Updates

Add all new shop fields to `Shop.java`: slug, description, logoUrl, bannerUrl, phone, email, latitude, longitude, openingHours (`@JdbcTypeCode(SqlTypes.JSON)` → `Map<String, String>`), deliveryInfo, minimumOrderPennies, published, tags.

Update `ShopDto`, `CreateShopRequest`, `ShopMapper`. Auto-generate slug from name if not provided.

Add to `ShopRepository`: `findBySlug()`, `findBySlugAndPublishedTrue()`, `findByPublishedTrue(Pageable)`.

**Files:**
- `core-java/src/main/java/uk/jtoye/core/shop/Shop.java`
- `core-java/src/main/java/uk/jtoye/core/shop/dto/ShopDto.java`
- `core-java/src/main/java/uk/jtoye/core/shop/dto/CreateShopRequest.java`
- `core-java/src/main/java/uk/jtoye/core/shop/ShopMapper.java`
- `core-java/src/main/java/uk/jtoye/core/shop/ShopService.java`
- `core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java`

### 2b. Product Entity + DTO Updates

Add new product fields to `Product.java`: description, imageUrl, category, displayOrder, available, featured, preparationTimeMinutes, dietaryTags.

Update `ProductDto`, `CreateProductRequest` (all new fields optional to avoid breaking existing API), `ProductMapper`. Update vendor dashboard products page to support the new fields.

Add to `ProductRepository`: `findByCategoryAndAvailableTrue()`, `findByAvailableTrue(Pageable)`, `findByFeaturedTrue()`.

**Files:**
- `core-java/src/main/java/uk/jtoye/core/product/Product.java`
- `core-java/src/main/java/uk/jtoye/core/product/dto/ProductDto.java`
- `core-java/src/main/java/uk/jtoye/core/product/dto/CreateProductRequest.java`
- `core-java/src/main/java/uk/jtoye/core/product/ProductMapper.java`
- `core-java/src/main/java/uk/jtoye/core/product/ProductRepository.java`

---

### 3. Public Storefront Backend

**New controller:** `PublicStorefrontController` at `/public/shops`
- `GET /public/shops` — list published shops (paginated, optional `?q=` search)
- `GET /public/shops/{slug}` — shop detail
- `GET /public/shops/{slug}/products` — product catalog (paginated, grouped by category)

No `@SecurityRequirement` annotations. No auth needed.

**New service:** `PublicStorefrontService`
- `listPublishedShops()` — queries shops directly (public RLS policy allows this)
- `getShopBySlug()` — finds by slug where published=true
- `getShopProducts(slug)` — resolves shop → sets `TenantContext` to shop's `tenantId` → queries products (available=true only) → clears context in `finally` block

The `TenantSetLocalAspect` fires `@Before` every repository call, applying the tenant context JIT. The `TenantContextCleanupFilter` provides request-level safety net.

**New DTOs:**
- `PublicShopDto` — slug, name, description, address, logoUrl, bannerUrl, geo, hours, phone, email, deliveryInfo, minimumOrderPennies, tags. NOT id/tenantId.
- `PublicProductDto` — id, title, description, imageUrl, ingredientsText, allergenMask, pricePennies, category, dietaryTags, preparationTimeMinutes, featured. NOT tenantId/sku. Only products where available=true.
- Products endpoint returns items **grouped by category** with featured items flagged, sorted by `displayOrder`.

**Files:**
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java` (new)
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java` (new)
- `core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicShopDto.java` (new)
- `core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicProductDto.java` (new)

---

### 4. SecurityConfig Update

Add `/public/**` to `permitAll()` alongside existing health/swagger rules.

**File:** `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java`

---

### 5. Edge-Go Public Routes

Add public route group (no JWT middleware) that proxies GET requests to core-java `/public/**`. Rate limit: 50 req/s burst 100 for browsing.

**File:** `edge-go/cmd/edge/main.go`

---

### 6. Frontend — Public API Client + Types

New `lib/public-api-client.ts` — axios instance without auth interceptor.

New `types/storefront.ts` — `PublicShop` (with banner, logo, tags, delivery info, hours), `PublicProduct` (with image, description, category, dietary tags, prep time, featured flag), `ProductsByCategory` (grouped menu structure).

**Files:**
- `frontend/lib/public-api-client.ts` (new)
- `frontend/types/storefront.ts` (new)

---

### 7. Frontend — Storefront Pages

**Layout:** `app/shop/layout.tsx` — clean, mobile-first layout with header + footer. No sidebar, no auth check. Middleware doesn't match `/shop/*` so no protection applied.

**Shop discovery:** `app/shop/page.tsx` — Deliveroo-style grid of shop cards. Each card shows:
- Shop logo + banner image (or gradient placeholder)
- Name + description snippet
- Tags (cuisine type badges)
- Open/closed status (computed from openingHours + current time)
- Delivery info + minimum order
- Click → `/shop/[slug]`

**Shop landing + menu:** `app/shop/[slug]/page.tsx` — immersive shop page modelled on Deliveroo/UberEats:
- **Hero section**: Banner image, logo overlay, shop name, description, delivery info, opening hours, contact
- **Category navigation**: Sticky horizontal tabs (Mains, Sides, Drinks, etc.) — scrolls to section
- **Featured items**: "Popular" section at top with featured products
- **Menu sections**: Products grouped by category, each showing:
  - Product image (or food-category placeholder icon)
  - Title + description
  - Price (formatted £X.XX)
  - Allergen badges (icons)
  - Dietary tags (Vegan/GF/Spicy badges)
  - Prep time estimate
  - "Add to cart" button (wired in Phase 2)
- **Mobile-first**: Single column on mobile, 2-col on tablet, 3-col on desktop

Uses existing shadcn/ui components (Card, Badge, Button, Pagination), framer-motion animations, lucide-react icons, and Tailwind styling.

**Files:**
- `frontend/app/shop/layout.tsx` (new)
- `frontend/app/shop/page.tsx` (new)
- `frontend/app/shop/[slug]/page.tsx` (new)

---

### 8. Vendor Dashboard Updates (for new fields)

The vendor needs to manage the new shop and product fields from the existing dashboard:

**Shops page** (`frontend/app/dashboard/shops/page.tsx`):
- Add fields to create/edit dialog: description, logo URL, banner URL, phone, email, delivery info, minimum order, tags, published toggle, opening hours editor (7-day grid)
- Show published status badge in shops table

**Products page** (`frontend/app/dashboard/products/page.tsx`):
- Add fields to create/edit dialog: description textarea, image URL, category (dropdown/autocomplete from existing categories), display order, available toggle, featured toggle, prep time, dietary tags
- Show image thumbnail, category badge, and availability status in products table
- Filter by category

**Files:**
- `frontend/app/dashboard/shops/page.tsx`
- `frontend/app/dashboard/products/page.tsx`

---

## Phase 2: Cart + Guest Checkout (next session)

- Cart context provider + localStorage persistence per shop slug
- Cart page: `app/shop/[slug]/cart/page.tsx`
- Checkout form: `app/shop/[slug]/checkout/page.tsx` (name, email, phone, notes)
- Backend: `POST /public/shops/{slug}/orders` — creates PENDING order, recalculates prices server-side
- Order confirmation page with order number
- Edge-go: POST route with lower rate limit (5/min per IP)

## Phase 3: Order Tracking + Notifications (future)

- `GET /public/orders/{orderNumber}` — public order lookup
- Order tracking page with real-time SSE
- Email notifications on all state transitions (expand existing `EmailNotificationService`)

---

## Verification Plan (Phase 1)

1. **Migration:** `./gradlew flywayMigrate` or let Spring Boot auto-migrate. Verify with `psql`: `\d shops` shows new columns, `SELECT * FROM pg_policies WHERE tablename='shops'` shows both policies.
2. **Backend tests:** Add unit tests for `PublicStorefrontService`. Run `./gradlew test`.
3. **Manual API test:**
   - Insert a shop with `published=true` via dashboard
   - `curl http://localhost:9090/public/shops` → should return the published shop
   - `curl http://localhost:9090/public/shops/{slug}/products` → should return products
4. **Frontend:** Navigate to `http://localhost:3000/shop` → should see shop cards. Click one → should see products.
5. **E2E (Playwright):** Browse shop listing, click into a shop, verify products render.

---

## Design Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| RLS bypass | Additive SELECT policy on shops | Zero changes to existing policies, PostgreSQL OR's permissive policies naturally |
| Tenant injection for products | Service-layer `TenantContext.set()` in `finally` | Works with existing `TenantSetLocalAspect` + cleanup filter — no new infrastructure |
| URL scheme | `/shop/{slug}` not `/shop/{uuid}` | SEO-friendly, user-readable, slug is globally unique |
| Public DTOs | Separate from vendor DTOs | Intentionally hide tenantId, sku, internal fields |
| Slug uniqueness | Global (not per-tenant) | Enables clean URLs without tenant prefix |
| Products scope | All tenant products on every shop | Matches current schema (no shop_id on products). Per-shop menus is a future enhancement |
| Product images | URL-based (not file upload) | Vendors paste image URLs (Cloudinary, S3, etc). File upload is a Phase 2+ enhancement |
| Categories | Free-text on product | Simple, no junction table. Vendor types "Mains", "Sides", etc. Frontend groups by these strings |
| Presentation model | Deliveroo/UberEats style | Hero banner, category tabs, product cards with images — familiar UK customer experience |
| Availability | Boolean toggle on product | Simple in/out-of-stock. No quantity tracking (future enhancement) |
| Dietary tags | Comma-separated string | Lightweight, no schema for tag taxonomy. Frontend splits and renders as badges |

---

## Appendix: Key Files Reference

### Backend (core-java)
- `src/main/java/uk/jtoye/core/security/SecurityConfig.java` — endpoint protection
- `src/main/java/uk/jtoye/core/security/TenantSetLocalAspect.java` — JIT tenant context on DB calls
- `src/main/java/uk/jtoye/core/security/TenantContextCleanupFilter.java` — request-level cleanup
- `src/main/java/uk/jtoye/core/security/TenantContext.java` — ThreadLocal tenant holder
- `src/main/java/uk/jtoye/core/shop/` — Shop entity, service, controller, mapper, repository
- `src/main/java/uk/jtoye/core/product/` — Product entity, service, controller, mapper, repository
- `src/main/resources/db/migration/V2__rls_policies.sql` — existing RLS policies (reference)

### Frontend
- `middleware.ts` — only protects `/dashboard/:path*` (storefront is unprotected by default)
- `auth.ts` — NextAuth + Keycloak config
- `lib/api-client.ts` — authenticated axios client (reference for public client)
- `types/api.ts` — existing TypeScript interfaces
- `app/dashboard/` — vendor dashboard (reference for patterns)

### Edge Service (Go)
- `edge-go/cmd/edge/main.go` — route definitions + proxy logic
