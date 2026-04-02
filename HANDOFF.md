# Handoff: J'Toye OaaS — Image Upload, AI Recognition, Bulk Import, Storefront UX

**Generated**: 2026-04-02
**Branch**: `feat/image-upload` (PR #20 open, 12 commits ahead of main)
**Status**: In Progress — core features built, housekeeping + final review needed

## Goal

Build a production-grade image management system for a multi-tenant UK food retail SaaS. Vendors need to upload product photos, get AI-powered dish identification, bulk-import menus, and customers need to see rich product detail modals on the storefront.

## Completed

- [x] **MinIO/S3 image storage** — Docker service, tenant-isolated paths, public-read bucket, auto-init
- [x] **Image upload endpoints** — `POST /products/{id}/image`, `/shops/{id}/logo`, `/shops/{id}/banner`, plus DELETE variants
- [x] **Multi-image support** — V19 migration adds `additional_image_urls TEXT[]`, `POST /products/{id}/images` for gallery
- [x] **Image quality control** — magic byte verification, min dimension validation (400x400 products, 100x100 logos, 600x200 banners), client-side compression (canvas resize to 1600px max, JPEG 0.85)
- [x] **SafeImage component** — error fallback for broken image URLs, lazy loading
- [x] **ImageUploader component** — drag-and-drop, mobile camera, progress bar, dimension validation
- [x] **AI image recognition (Ollama)** — local GPU-accelerated vision model identifies dishes, suggests name/ingredients/category/dietary tags
- [x] **AI suggestions UI** — vendor dashboard shows AI results with one-click "Apply" buttons
- [x] **Bulk CSV import** — `POST /products/bulk/csv` with template download, per-row validation
- [x] **Bulk photo scan** — `POST /products/bulk/images` uploads multiple photos, AI identifies each, creates draft products
- [x] **Import dashboard page** — `/dashboard/products/import` with CSV and Photo Scan tabs
- [x] **Product detail modal** — clickable cards open full-screen modal with image carousel, ingredients, allergens, dietary tags, add-to-cart
- [x] **Auth-gated order tracking** — all tracking pages require customer login, "My Orders" hidden when not signed in
- [x] **E2E test rewrite** — assertions verify `img.naturalWidth > 0` (not just DOM existence)

## Not Yet Done

- [ ] **Housekeeping** — docs freshness audit (README, AI_CONTEXT, CHANGELOG), .gitignore check for new file types
- [ ] **Vendor dashboard multi-image management** — the gallery upload UI for additional images (backend exists, frontend not wired)
- [ ] **Backend auth on order tracking API** — `/public/orders` endpoints still accept any email without JWT validation (frontend blocks access but API is still open)
- [ ] **Next.js `<Image>` optimization** — storefront uses `<img>` tags; `next.config.mjs` has `remotePatterns` but `<Image>` not adopted (external URLs from vendors wouldn't match patterns)
- [ ] **Per-shop product menus** — all tenant products show on every shop (no `shop_id` on products table)
- [ ] **"0 items" bug** — order cards show "0 items" on tracking pages (JPA lazy loading)
- [ ] **E2E tests in CI** — require docker-compose, not wired into GitHub Actions

## Failed Approaches (Don't Repeat These)

1. **SafeImage with loading skeleton + hidden img** — skeleton `<div>` and `<img>` both used `absolute inset-0` inside a parent with no intrinsic height. Container collapsed to 0px, images invisible. `naturalWidth=0` in browser. Fixed by removing skeleton, rendering `<img>` directly with `onError` fallback.

2. **llava:7b on RTX 2080 Ti** — Ollama's llava runner crashes with segfault during inference (CUDA driver incompatibility with Ollama 0.19.0). Fixed by switching to `gemma3:12b` which has vision support and runs fine on the same GPU.

3. **Docker Ollama container pulling models** — container couldn't reach `registry.ollama.ai` (DNS resolution failed inside Docker network). Fixed by using the host's Ollama server (already installed with systemd) and configuring `OLLAMA_URL` as env var.

4. **Anthropic Java SDK (`com.anthropic:anthropic-java:1.3.0`)** — artifact not found on Maven Central. Fixed by using Spring WebFlux `WebClient` to call Claude's REST API directly, then refactored to use Ollama instead (free, local).

5. **Playwright modal close by clicking backdrop** — backdrop `<div>` had the modal `<div>` on top, so Playwright's click was intercepted by the modal content. Fixed by adding `onClick={onClose}` on the modal wrapper div (not just the backdrop).

## Key Decisions

| Decision | Rationale |
|----------|-----------|
| Ollama (local) over Anthropic (cloud) | Zero per-use cost for vendors, data stays on-prem, GPU inference is fast enough |
| gemma3:12b over llava:7b | llava crashes on this CUDA setup, gemma3 has vision and is more stable |
| Separate upload endpoints (not multipart in create) | Keeps existing JSON APIs clean, allows image upload after creation |
| `TEXT[]` column for additional images (not junction table) | Simpler for 1-5 images per product, no joins needed |
| Auth-gated tracking (not guest) | User explicitly requested no guest access to order tracking |
| Draft products from AI scan (price=0, available=false) | Prevents unreviewed AI-generated content from going live |
| Client-side compression before upload | Reduces upload time and storage cost, 1600px max is plenty for food photos |

## Current State

**Working**: Shop discovery, product browsing with images, clickable detail modals with carousel, cart + checkout, order tracking (auth-gated), CSV bulk import, image upload with quality validation, AI image recognition (Ollama on GPU), email notifications.

**Broken**: Nothing blocking. Some products have placeholder images from Pexels (vendors should upload their own). Ollama container in docker-compose can't pull models (use host Ollama instead).

**Uncommitted Changes**: Only `core-java/build-local/` compiled class files (build artifacts, in .gitignore).

## Files to Know

| File | Why It Matters |
|------|----------------|
| `core-java/src/main/java/uk/jtoye/core/storage/StorageService.java` | Upload/delete with magic byte verification, dimension validation, tenant isolation |
| `core-java/src/main/java/uk/jtoye/core/ai/ImageAnalysisService.java` | Dual-provider AI (Ollama/Anthropic), food-specific prompt, JSON parsing |
| `core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java` | CSV parsing + AI photo scan, creates products with auto-generated SKUs |
| `core-java/src/main/java/uk/jtoye/core/product/ProductController.java` | All product endpoints including bulk import, image upload, AI analysis |
| `core-java/src/main/resources/db/migration/V19__product_multiple_images.sql` | additional_image_urls TEXT[] column |
| `frontend/components/ui/image-uploader.tsx` | Drag-drop upload with compression, dimension validation, AI suggestions callback |
| `frontend/components/ui/safe-image.tsx` | Error-fallback image component used across all pages |
| `frontend/components/storefront/product-detail-modal.tsx` | Full product detail with image carousel, ingredients, allergens |
| `frontend/components/storefront/require-customer-auth.tsx` | Auth guard wrapping order tracking pages |
| `frontend/app/dashboard/products/import/page.tsx` | Bulk import UI with CSV and Photo Scan tabs |
| `frontend/e2e/storefront-flows.spec.ts` | E2E tests with real image rendering assertions |
| `docker-compose.full-stack.yml` | MinIO + Ollama services added |

## Code Context

**AI Analysis Response** (from Ollama or Anthropic):
```json
{
  "identifiedName": "Jollof Rice",
  "description": "Smoky tomato-based rice dish, a West African staple",
  "ingredients": "Rice, tomatoes, peppers, onions, vegetable oil, seasoning",
  "category": "Mains",
  "dietaryTags": ["Halal", "Gluten-Free"],
  "allergenWarnings": [],
  "cuisineOrigin": "Nigerian",
  "confidence": 0.92
}
```

**Image Upload Response** (wraps product + AI suggestions):
```json
{
  "product": { "id": "...", "title": "...", "imageUrl": "http://localhost:9000/jtoye-images/..." },
  "aiSuggestions": { "identifiedName": "...", "confidence": 0.92 }
}
```

**Bulk Import Response**:
```json
{
  "totalRows": 10, "successCount": 9, "errorCount": 1,
  "created": [{ "id": "...", "title": "...", "sku": "...", "pricePennies": 899 }],
  "errors": [{ "row": 5, "field": "price_pounds", "message": "Invalid price: abc" }]
}
```

**AI Config** (`application.yml`):
```yaml
ai:
  enabled: true
  provider: ollama  # or "anthropic"
  ollama:
    url: http://localhost:11434
    model: gemma3:12b
  anthropic:
    api-key: ${ANTHROPIC_API_KEY:}
```

## Resume Instructions

1. `git checkout feat/image-upload && git pull`
2. `docker compose -f docker-compose.full-stack.yml up -d` — starts all services including MinIO
3. Ollama must be running on host: `systemctl status ollama` (or `ollama serve`)
   - Model needed: `ollama pull gemma3:12b` (if not already pulled)
4. Wait ~40s for core-java startup, then verify:
   - `curl -s http://localhost:9090/actuator/health` → `{"status":"UP"}`
   - `curl -s http://localhost:9000/minio/health/live` → 200 OK
   - `curl -s http://localhost:11434/api/tags` → lists gemma3:12b
   - `curl -s http://localhost:3000/shop` → 200 OK
5. Run tests:
   - `./gradlew :core-java:test` → 138 pass
   - `cd frontend && npx next build` → builds clean
   - `npx jest --watchAll=false` → 43 pass
6. View storefront: `http://localhost:3000/shop`
7. Vendor dashboard: `http://localhost:3000/dashboard` (login: tenant-a-user / password123)

## Setup Required

- **Docker**: All services via `docker-compose.full-stack.yml` (10 containers including MinIO, Ollama)
- **Java**: `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`
- **Node**: v20.19.3
- **GPU**: NVIDIA RTX 2080 Ti (11GB VRAM) with NVIDIA Container Toolkit
- **Ollama**: Running on host at localhost:11434 with `gemma3:12b` model
- **Test users**: `tenant-a-user` / `password123` (vendor), self-register for customer
- **MinIO console**: http://localhost:9001 (minioadmin/minioadmin)
- **Mailhog**: http://localhost:8025 for email testing

## Warnings

- `build-local/` directory has uncommitted compiled classes — build artifacts, in .gitignore
- Docker Ollama container can't reach external internet to pull models — use host Ollama
- `llava:7b` crashes on this system's CUDA setup — use `gemma3:12b` instead
- The `application.yml` DB/RabbitMQ password defaults are empty — `.env` file MUST be present
- Frontend container must be rebuilt (`docker compose up -d --build frontend`) to see code changes
- `additional_image_urls TEXT[]` needs PostgreSQL (won't work with H2 in tests unless array handling is mocked)
- AI photo scan with 20+ images can take 5+ minutes even on GPU
