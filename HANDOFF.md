# Handoff: J'Toye OaaS — Platform Audit Fixes + Stripe Integration Pending

**Generated**: 2026-04-03
**Branch**: `feat/image-upload` (PR #20 open, pushed to origin)
**Status**: Batch 1 complete, Stripe integration next

## Goal

Full platform audit identified implementation, architectural, business, and monetisation gaps. This session completed Batch 1 (schema + backend fixes) and housekeeping. Next session should tackle Stripe payment integration (Batch 2), which is the #1 revenue blocker.

## Completed (This Session)

### Batch 1 — Schema & Backend Fixes
- [x] **Per-shop product menus** — V20 migration adds `shop_id` FK to products (nullable, NULL = all shops). `ProductRepository.findAvailableByShopOrderedByCategory(shopId)` filters products per-shop. Frontend: shop assignment dropdown in product create/edit.
- [x] **Inventory tracking** — V20 adds `quantity_in_stock` (NULL = unlimited). `Product.hasStock(quantity)` helper. Stock validated on order creation, decremented on CONFIRMED, restored on CANCELLED. Frontend: "Track inventory" checkbox + stock input, "Out of Stock" badges on storefront.
- [x] **"0 items" bug fix** — V21 migration adds denormalized `item_count` on orders (backfilled). `Order.calculateTotal()` now sets itemCount. `PublicStorefrontService` uses `order.getItemCount()` instead of lazy-loading items through RLS.
- [x] **Guest API info disclosure** — `/public/orders` endpoint now accepts `verify` param (order number) to prove email ownership before returning order history.

### Housekeeping
- [x] **YAML logging fix** — `application-prod.yml:75` and `application-staging.yml:72` JSON logging patterns now use `>-` block scalar (was failing YAML parse)
- [x] **45 new tests** — ImageAnalysisServiceTest (10), BulkImportServiceTest (13), StorageServiceTest (15), OrderSseServiceTest (7). Total: 183 Java tests, 0 failures.
- [x] **Security hardening** — Removed hardcoded "minioadmin" defaults from `StorageProperties.java` (now empty strings, requires env vars)
- [x] **console.log removed** — from `image-uploader.tsx` (compression stats logging)
- [x] **Artifact cleanup** — deleted `.idea/.idea.bak`
- [x] **Docs freshness** — DOCUMENTATION_INDEX.md date updated

### Previously Completed (Prior Sessions)
- [x] MinIO/S3 image storage, upload endpoints, multi-image support
- [x] AI image recognition (Ollama/Anthropic), bulk CSV + photo scan import
- [x] Product detail modal, auth-gated order tracking, E2E tests
- [x] Full order state machine, RabbitMQ events, email notifications
- [x] PostgreSQL RLS multi-tenancy, Redis caching, rate limiting

## Not Yet Done (Priority Order)

### Batch 2 — Stripe Integration (NEXT SESSION, revenue blocker)
- [ ] **Stripe payment intent flow** — Add Stripe Java SDK dependency, create `PaymentService` with payment intent creation, webhook handler for `payment_intent.succeeded`/`payment_intent.failed`
- [ ] **Payment fields on Order** — New migration: `payment_status` (PENDING/AUTHORIZED/CAPTURED/FAILED/REFUNDED), `payment_reference` (Stripe PI ID), `payment_method` (card_last4)
- [ ] **Frontend Stripe Elements** — `@stripe/react-stripe-js` in checkout page, card input, payment confirmation flow
- [ ] **Order flow change** — Guest order should create payment intent first, then transition to PENDING only after payment succeeds

### Batch 3 — Business Logic Gaps
- [ ] **VAT at checkout** — Apply VAT rate to order total (currently orders have no tax breakdown)
- [ ] **Opening hours enforcement** — Validate orders only accepted when shop is open (hours stored as JSONB)
- [ ] **Customer allergen warnings** — Cross-check cart products' allergen masks against customer restrictions at checkout
- [ ] **Order idempotency** — Add idempotency key to prevent double-submit

### Batch 4 — Infra/Process
- [ ] **Automated K8s backup** — CronJob for pg_dump → S3
- [ ] **CORS from env vars** — SecurityConfig CORS origins currently hardcoded
- [ ] **Keycloak token lifespan** — 3600s too long for production, reduce to 300-900s
- [ ] **GDPR endpoints** — Data export + erasure ("right to be forgotten")

### Other Known Gaps
- [ ] Vendor dashboard multi-image gallery UI (backend done, frontend not wired)
- [ ] Next.js `<Image>` optimization on storefront
- [ ] E2E tests in CI pipeline
- [ ] Vendor onboarding flow (self-service tenant creation)
- [ ] Subscription billing (Stripe Billing for SaaS model)

## Failed Approaches (Don't Repeat These)

1. **SafeImage with loading skeleton + hidden img** — Container collapsed to 0px. Fixed by removing skeleton.
2. **llava:7b on RTX 2080 Ti** — CUDA segfault. Use `gemma3:12b` instead.
3. **Docker Ollama pulling models** — DNS failure. Use host Ollama.
4. **Anthropic Java SDK** — Not on Maven Central. Use WebFlux WebClient or Ollama.
5. **Playwright modal close by clicking backdrop** — Click intercepted. Fixed with `onClick={onClose}` on wrapper.

## Key Decisions

| Decision | Rationale |
|----------|-----------|
| `shop_id` nullable on products | NULL = available on all shops (backward compatible) |
| Denormalized `item_count` on orders | Avoids lazy-loading items through RLS without tenant context |
| Stock decrement on CONFIRMED (not DRAFT) | Vendor may reject order — don't lock stock prematurely |
| Stock restore on CANCELLED | Only if order was already CONFIRMED (stock had been decremented) |
| `verify` param on /public/orders (not mandatory) | Backward compatible. Can be made required later |
| Empty string defaults for S3 credentials | Forces explicit env var config. Prevents accidental use of hardcoded creds |

## Current State

**Working**: Everything from prior sessions + per-shop products, inventory, 0-items fix, 183 passing tests.

**Broken**: Nothing blocking. `StorageProperties` now has empty S3 credentials by default — ensure `.env` or env vars provide `S3_ACCESS_KEY` and `S3_SECRET_KEY` (or the `storage.s3.access-key` / `storage.s3.secret-key` Spring properties).

**Uncommitted**: Only `build-local/` compiled class files (in .gitignore).

## Files to Know

| File | Why It Matters |
|------|----------------|
| `core-java/src/main/resources/db/migration/V20__per_shop_products_and_inventory.sql` | shop_id + quantity_in_stock on products |
| `core-java/src/main/resources/db/migration/V21__order_item_count.sql` | Denormalized item_count on orders |
| `core-java/src/main/java/uk/jtoye/core/product/Product.java` | Now has shopId, quantityInStock, hasStock() |
| `core-java/src/main/java/uk/jtoye/core/order/Order.java` | Now has itemCount, updated calculateTotal() |
| `core-java/src/main/java/uk/jtoye/core/order/OrderService.java` | Stock validation + decrement/restore logic |
| `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java` | Per-shop product filtering, stock validation, item_count fix |
| `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java` | /public/orders verify param |
| `core-java/src/main/java/uk/jtoye/core/storage/StorageProperties.java` | S3 credentials now empty by default |
| `frontend/app/dashboard/products/page.tsx` | Shop assignment + inventory tracking UI |
| `frontend/app/shop/[slug]/page.tsx` | Out-of-stock badges on storefront |
| `frontend/components/storefront/product-detail-modal.tsx` | Out-of-stock in product modal |
| `core-java/build.gradle.kts` | Dependencies — Stripe SDK will need to be added here |

## Resume Instructions

1. `git checkout feat/image-upload && git pull`
2. `docker compose -f docker-compose.full-stack.yml up -d`
3. Ensure `.env` has `S3_ACCESS_KEY=minioadmin` and `S3_SECRET_KEY=minioadmin` for local dev
4. Ollama on host: `systemctl status ollama` (model: `gemma3:12b`)
5. Wait ~40s for startup, then verify:
   - `curl -s http://localhost:9090/actuator/health` → `{"status":"UP"}`
   - `curl -s http://localhost:3000/shop` → 200 OK
6. Run tests:
   - `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64 ./gradlew :core-java:test` → 183 pass
   - `cd frontend && npx next build` → builds clean
7. **Start Stripe integration** (Batch 2)

## Setup Required

- **Docker**: `docker-compose.full-stack.yml` (10 containers)
- **Java**: `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64` (Java 21 required, Java 25 installed as default but Gradle toolchain needs 21)
- **Node**: v20.19.3
- **GPU**: NVIDIA RTX 2080 Ti with NVIDIA Container Toolkit
- **Ollama**: Host at localhost:11434, `gemma3:12b` model
- **Test users**: `tenant-a-user` / `password123` (vendor)
- **MinIO console**: http://localhost:9001 (credentials from env vars)
- **Mailhog**: http://localhost:8025

## Warnings

- Java 25 is the system default but Gradle toolchain requires Java 21. Always set `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`
- `StorageProperties` S3 credentials are now empty by default — `.env` MUST provide them
- Docker Ollama container can't pull models — use host Ollama
- `llava:7b` crashes — use `gemma3:12b`
- AI photo scan with 20+ images takes 5+ minutes on GPU
