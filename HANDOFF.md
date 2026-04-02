# Handoff: J'Toye OaaS — Public Customer Storefront Complete

**Generated**: 2026-04-02
**Branch**: `feat/public-storefront` (PR #19 open, 10 commits)
**Status**: Ready for Review / Merge

## Goal

Build a customer-facing public storefront for J'Toye OaaS — a multi-tenant UK retail SaaS platform. Customers should be able to discover shops, browse products, place orders, track them, and receive email notifications — all without vendor-level auth.

## Completed

- [x] **Public storefront UI** — Deliveroo-style shop discovery grid, product catalog with categories, dietary badges, allergen info, prep times
- [x] **V16 migration** — 13 shop columns (slug, description, logo, banner, hours, geo, tags, published) + 8 product columns (description, image, category, dietary, availability, featured) + public RLS policy
- [x] **Cart system** — React context + localStorage per shop slug, add-to-cart with quantity controls, floating cart bar, cart page
- [x] **Guest checkout** — `POST /public/shops/{slug}/orders` with server-side price recalculation, order confirmation page
- [x] **Order tracking** — V17 RLS for secure lookup (order_number + email verification via session variables), live 5-step progress tracker with 15s auto-refresh
- [x] **Customer order history** — V18 RLS for email-based history, `/shop/orders` page, automatic tracking without manual order number input
- [x] **Email notifications** — All 6 state transitions (PENDING→CONFIRMED→PREPARING→READY→COMPLETED/CANCELLED) with tracking links, Mailhog for dev
- [x] **Customer auth** — Keycloak `storefront-client` (public, PKCE), self-service registration, `customer` role, Sign in/out in storefront header with immediate nav update
- [x] **Vendor dashboard** — Updated shops + products pages with all new storefront fields
- [x] **Playwright E2E suite** — 10 tests × 2 viewports (mobile + desktop) = 20 tests, all passing
- [x] **Housekeeping** — Security fixes (removed insecure defaults), env parity, docs freshness, CI deploy fix, stale branch cleanup
- [x] **PR #18 merged** — email notifications, WhatsApp order creation, tech debt

## Not Yet Done

- [ ] **Payments (Stripe)** — FinancialTransaction is record-keeping only, no card payments
- [ ] **Delivery management** — entirely new domain, not started
- [ ] **Edge-go public routes** — storefront currently calls core-java:9090 directly, should proxy through edge-go with rate limiting
- [ ] **Keycloak theme** — login/register page uses default Keycloak theme, not branded
- [ ] **Customer token refresh** — `customer-auth.ts` doesn't refresh expired tokens (MVP — re-login required)
- [ ] **"0 items" bug** — Order items show "0 items" on tracking/history pages (JPA lazy loading — `order.getItems()` returns empty outside transaction)
- [ ] **Per-shop menus** — all tenant products show on every shop (no `shop_id` on products table)
- [ ] **Product image upload** — currently URL-only, no file upload widget
- [ ] **E2E in CI** — Playwright tests require docker-compose, not yet wired into GitHub Actions

## Failed Approaches (Don't Repeat These)

1. **RLS bypass for public shops via `current_tenant_id() IS NULL` check** — didn't work because `tenant_id = NULL` evaluates to UNKNOWN in PostgreSQL, not TRUE. Fixed by adding a separate permissive SELECT policy: `USING (published = true OR tenant_id = current_tenant_id())`.

2. **V16 migration with `NOT NULL DEFAULT 'pending-slug'` for slug backfill** — failed because 8 existing shops all got the same default slug, and the UNIQUE index creation failed on duplicates. Also, RLS with `FORCE ROW LEVEL SECURITY` blocked the UPDATE backfill since Flyway runs without tenant context. Fixed by temporarily disabling RLS, backfilling with `name + id prefix`, then re-enabling.

3. **Publishing RabbitMQ event inside `createGuestOrder()` transaction** — the listener picked up the event before the transaction committed, so `orderRepository.findById()` returned empty (order not yet visible to other transactions). Fixed with `TransactionSynchronizationManager.registerSynchronization(afterCommit)`.

4. **`OrderStateChangeListener` email sending without tenant context** — the listener runs on a RabbitMQ consumer thread with no tenant context. Even setting `TenantContext` wasn't enough because `TenantSetLocalAspect` had already fired at transaction start (before the tenant was set). Fixed by manually calling `SET LOCAL app.current_tenant_id` via `EntityManager.unwrap(Session.class).doWork()` within the listener, plus adding `@Transactional` to the listener method.

5. **Tracking page email resolution from `localStorage.getItem(\`jtoye-checkout-email-\${slug}\`)` only** — failed for orders placed before the code was deployed (no localStorage entry existed). Also failed when navigating from My Orders because the slug-specific key wasn't always present. Fixed with multi-source email resolution: URL param → customer session → checkout localStorage → order history localStorage → inline prompt fallback.

6. **Nav state after OAuth login not updating** — `useEffect([], [])` only ran once on mount. After OAuth redirect back to the storefront, localStorage had the tokens but the component didn't re-render. Fixed with focus/visibility event listeners + brief 1s polling for 5s after mount.

## Key Decisions

| Decision | Rationale |
|----------|-----------|
| Hybrid vendor-first storefront (Option C) | Keeps RLS intact, customers discover vendors then browse their catalog. Upgrade path to marketplace via read-only search index later. |
| Permissive RLS policies (additive SELECT) | PostgreSQL OR's permissive policies — new policy works alongside existing tenant RLS without modifying it |
| Service-layer `TenantContext.set()` for public product queries | Resolves shop → tenant_id, sets context, queries products. `TenantSetLocalAspect` applies it JIT before repository calls. |
| Slug-based URLs (not UUID) | SEO-friendly, globally unique (not per-tenant) |
| Separate customer auth (not NextAuth) | Vendor dashboard uses NextAuth with confidential `core-api` client. Customer storefront uses lightweight PKCE OAuth with public `storefront-client` + localStorage tokens to avoid config conflicts. |
| Email as order verification | `GET /public/orders/{orderNumber}?email=` requires both to match — lightweight security without requiring customer accounts |
| `afterCommit` event publishing | Guest orders publish RabbitMQ events after transaction commits so listener can find the order |
| Mailhog for dev email | Zero-config SMTP testing, viewable at http://localhost:8025 |

## Current State

**Working**: All storefront flows — browse, search, add to cart, checkout, order confirmation, My Orders, track order, email notifications, customer registration/login, vendor dashboard with new fields. 20/20 Playwright E2E tests pass.

**Broken**: Nothing blocking. "0 items" displays on order cards (cosmetic — JPA lazy loading). CI deploy step skipped (no K8s credentials).

**Uncommitted Changes**: Only `core-java/build-local/` compiled class files (build artifacts, in .gitignore).

## Files to Know

| File | Why It Matters |
|------|----------------|
| `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java` | Core storefront logic — tenant context injection, order creation, tracking, email history |
| `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java` | 5 public endpoints: list shops, shop detail, products, create order, track order, customer orders |
| `core-java/src/main/java/uk/jtoye/core/security/TenantSetLocalAspect.java` | JIT tenant context on DB calls — fires `@Before` every repository method |
| `core-java/src/main/resources/db/migration/V16__public_storefront.sql` | Shop + product enrichment, public RLS, audit tables |
| `core-java/src/main/resources/db/migration/V17__order_tracking.sql` | Guest order tracking RLS via session variables |
| `core-java/src/main/resources/db/migration/V18__order_history_by_email.sql` | Email-based order history RLS |
| `frontend/components/storefront/cart-provider.tsx` | Cart context + localStorage persistence |
| `frontend/lib/customer-auth.ts` | PKCE OAuth flow for customer login (separate from vendor NextAuth) |
| `frontend/lib/order-history.ts` | Guest order localStorage management |
| `frontend/app/shop/[slug]/page.tsx` | Shop detail + menu page with add-to-cart |
| `frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx` | Order tracking with progress stepper |
| `frontend/app/shop/orders/page.tsx` | Customer order history (My Orders) |
| `frontend/e2e/storefront-flows.spec.ts` | 10 Playwright E2E tests |
| `infra/keycloak/configure-keycloak.sh` | Keycloak setup including storefront-client |

## Code Context

**Public API endpoints** (no auth required):
```
GET  /public/shops                        → Page<PublicShopDto>
GET  /public/shops/{slug}                 → PublicShopDto
GET  /public/shops/{slug}/products        → Map<String, List<PublicProductDto>>  (grouped by category)
POST /public/shops/{slug}/orders          → GuestOrderConfirmation
GET  /public/orders?email={email}         → List<PublicOrderStatus>
GET  /public/orders/{orderNumber}?email=  → PublicOrderStatus
```

**RLS pattern for public queries** (3 permissive policies):
```sql
-- V16: shops — anyone can SELECT published shops
CREATE POLICY shops_public_read ON shops FOR SELECT
  USING (published = true OR tenant_id = current_tenant_id());

-- V17: orders — tracking by order_number + email via session vars
CREATE POLICY orders_guest_tracking ON orders FOR SELECT
  USING (tenant_id = current_tenant_id()
    OR (order_number = current_setting('app.tracking_order_number', true)
        AND customer_email = current_setting('app.tracking_email', true)));

-- V18: orders — history by email via session var
CREATE POLICY orders_customer_history ON orders FOR SELECT
  USING (tenant_id = current_tenant_id()
    OR (customer_email = current_setting('app.customer_email', true)));
```

**Tenant context injection for product queries** (in `PublicStorefrontService`):
```java
Shop shop = shopRepository.findBySlugAndPublishedTrue(slug).orElseThrow(...);
TenantContext.set(shop.getTenantId());
try {
    List<Product> products = productRepository.findAvailableOrderedByCategory();
    // ... map to DTOs
} finally {
    TenantContext.clear();
}
```

## Resume Instructions

1. `git checkout feat/public-storefront && git pull`
2. `docker compose -f docker-compose.full-stack.yml up -d` — starts all 8 services
3. Wait ~40s for core-java startup, then verify:
   - `curl -s http://localhost:9090/public/shops | python3 -c "import sys,json; print(json.load(sys.stdin)['totalElements'], 'shops')"` → `1 shops`
   - `curl -s http://localhost:3000/shop` → 200 OK
   - `curl -s http://localhost:8025/` → 200 OK (Mailhog)
4. Run E2E tests: `cd frontend && npx playwright test` → 20/20 pass
5. Run unit tests: `./gradlew :core-java:test` → 138 pass, `npx jest --watchAll=false` → 43 pass

## Setup Required

- **Docker**: All 8 containers via `docker-compose.full-stack.yml`
- **Java**: `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`
- **Node**: v20.19.3
- **Test users**: `tenant-a-user` / `password123` (vendor), self-register for customer
- **Keycloak clients**: `core-api` (vendor, confidential), `storefront-client` (customer, public)
- **Mailhog**: http://localhost:8025 for email testing

## Warnings

- `build-local/` directory has uncommitted compiled classes — these are build artifacts, not source changes
- The `application.yml` DB/RabbitMQ password defaults are now empty (`${DB_PASSWORD:}`) — `.env` file MUST be present or Spring Boot won't start
- `core-java` container takes ~35s to start (Flyway migrations + Keycloak JWKS fetch)
- Keycloak `configure-keycloak.sh` must be run once after first `docker compose up` to set up storefront-client and customer role
- The `DEPLOY_ENABLED` GitHub repository variable must be set to `true` to enable production deploys in CI
