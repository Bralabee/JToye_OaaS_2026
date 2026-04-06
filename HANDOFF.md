# Handoff: J'Toye OaaS — Stripe Integration Complete + Housekeeping Done

**Generated**: 2026-04-05
**Branch**: `feat/image-upload` (PR #20 open, uncommitted changes present)
**Status**: Batch 2 (Stripe) complete + full housekeeping audit applied. Ready to commit.

## Goal

Implement Stripe payment integration (Batch 2, revenue blocker) and run full project housekeeping with all recommended fixes applied.

## Completed (This Session)

### Batch 2 — Stripe Payment Integration ✅
- [x] **Stripe Java SDK** — `com.stripe:stripe-java:28.2.0` added to `core-java/build.gradle.kts`
- [x] **V22 migration** — `core-java/src/main/resources/db/migration/V22__payment_fields.sql` adds `payment_status`, `payment_reference`, `payment_method` to `orders` + `orders_aud`
- [x] **PaymentStatus enum** — `uk.jtoye.core.order.PaymentStatus` (NONE, PENDING, AUTHORIZED, CAPTURED, FAILED, REFUNDED)
- [x] **Order entity** — Added 3 payment fields to `Order.java` + `OrderDto.java`
- [x] **StripeProperties** — `uk.jtoye.core.payment.StripeProperties` reads `stripe.api-key` and `stripe.webhook-secret`
- [x] **PaymentService** — Creates PaymentIntents with GBP currency, metadata (order_id, tenant_id), handles webhooks `payment_intent.succeeded` and `payment_intent.payment_failed`, signature verification, creates FinancialTransaction on success
- [x] **PaymentController** — Public `POST /public/payments/webhook` endpoint (no auth, signature verified)
- [x] **Checkout flow change** — Guest order now creates DRAFT + PaymentIntent, returns `clientSecret`. Order transitions DRAFT→PENDING **only** on `payment_intent.succeeded` webhook. Falls back to COD if `STRIPE_API_KEY` unset.
- [x] **Frontend** — `@stripe/react-stripe-js` + `@stripe/stripe-js` added. Checkout page refactored into two-step flow (details → payment) using PaymentElement with orange theme
- [x] **7 PaymentService tests** — `PaymentServiceTest.java` covers init, invalid signature, payment success/failure, missing metadata, unhandled event types

### Housekeeping — All 9 Recommended Actions ✅
- [x] **edge-go/edge binary** — untracked from git (`git rm --cached`), added to `.gitignore`
- [x] **Stripe env vars in compose** — `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`, `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` added to `docker-compose.full-stack.yml`
- [x] **Grafana password** — uses `${GRAFANA_ADMIN_PASSWORD:-admin123}` env var in `infra/monitoring/docker-compose.monitoring.yml`
- [x] **HANDOFF.md** — Batch 2 items marked complete
- [x] **Test counts updated** — 5 living docs now reflect 252→259 tests (AI_CONTEXT, GAP_ANALYSIS, PROJECT_STATUS, USER_GUIDE, ENTERPRISE_STRATEGIC_ANALYSIS)
- [x] **console.log gated** — `frontend/lib/env-validation.ts` guards logs with `NODE_ENV !== 'production'`
- [x] **CI artifact trimmed** — removed `frontend/.next/` (594M) from `.github/workflows/ci-cd.yaml` upload
- [x] **Deprecated `version: '3.9'`** — removed from monitoring compose

### Previously Completed (Prior Sessions)
- [x] Batch 1: per-shop products, inventory tracking, 0-items bug fix, guest API verify param
- [x] MinIO/S3 image storage, AI image recognition, bulk import
- [x] Order state machine, RabbitMQ, email notifications, RLS multi-tenancy

## Not Yet Done

### Immediate — Commit This Session's Work
1. Review uncommitted changes with `git diff --stat`
2. Commit on `feat/image-upload` branch (or create new `feat/stripe-payments`)
3. Push and update PR #20 (or open new PR)

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

### Remaining Test Coverage Gaps (from housekeeping audit)
- [ ] PaymentController — webhook endpoint test (signature verification, 400 on bad sig)
- [ ] EmailNotificationService, DevTenantService, ProductLabelService
- [ ] Security filters (JwtTenantFilter, TenantFilter, TenantContextCleanupFilter)
- [ ] PublicStorefrontController (service is tested, controller is not)

### Infra Debt (deferred from housekeeping)
- [ ] postgres-exporter has hardcoded `jtoye:secret` in `infra/monitoring/docker-compose.monitoring.yml:66`
- [ ] Next.js 16 `middleware` → `proxy` rename deprecation warning
- [ ] Most `@SpringBootTest` tests lack `@ActiveProfiles("test")` — risks failures without local Postgres/Redis
- [ ] CI tests use H2 despite Postgres service being available — RLS/Flyway behavior untested in CI
- [ ] 7 npm packages with major version drift (Tailwind v3→v4, lucide v0→v1, TypeScript v5→v6)

## Failed Approaches (Don't Repeat)

1. **Original intent**: initially planned `ShopRepository ShopRepository, ProductRepository...` constructor signature. Added `PaymentService` to `PublicStorefrontService` constructor — test file needed matching update (added `@Mock PaymentService paymentService`).
2. **Historical**: SafeImage with loading skeleton (container collapsed), llava:7b on RTX 2080 Ti (CUDA segfault → use gemma3:12b), Docker Ollama pulling models (DNS failure → host Ollama), Anthropic Java SDK not on Maven Central → WebClient.

## Key Decisions

| Decision | Rationale |
|----------|-----------|
| Stripe event types: `payment_intent.succeeded` + `payment_intent.payment_failed` | Minimum viable webhook set. Refunds/disputes deferred to later batch. |
| Currency hardcoded to `gbp` | UK-only MVP. Multi-currency deferred. |
| Order stays DRAFT until webhook confirms | Don't show customer success before Stripe confirms. No race conditions. |
| Payment method stored as human-readable ("Visa ending 4242") | Display-only, no PCI scope. Never store raw card data. |
| FinancialTransaction created on webhook success | Keeps ledger append-only and consistent with existing pattern. |
| Metadata-based lookup (not PI ID lookup) | Avoids needing DB index on payment_reference during webhook — uses order_id from metadata |
| `automatic_payment_methods: enabled` on Stripe | Supports cards, Apple Pay, Google Pay, Link automatically — no extra integration |
| COD fallback if no Stripe key | Backward compatible. `clientSecret == null` → frontend uses old direct-order flow |
| Grafana password env var with fallback | Strict fail would break existing local dev. Keeps dev UX, allows prod override. |

## Current State

**Working**:
- All 259 tests pass: 190 Java (added 7 PaymentServiceTest) + 26 Go + 43 Jest
- Java compiles clean, Next.js builds clean, both Docker Compose files validate
- Stripe integration fully implemented end-to-end (backend + frontend), gracefully degrades without API key

**Broken**: Nothing blocking.

**Uncommitted**: YES — significant changes need to be committed. See "Files Changed" below.

## Files Changed (Uncommitted)

### New files
- `core-java/src/main/java/uk/jtoye/core/order/PaymentStatus.java`
- `core-java/src/main/java/uk/jtoye/core/payment/StripeProperties.java`
- `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java`
- `core-java/src/main/java/uk/jtoye/core/payment/PaymentController.java`
- `core-java/src/main/resources/db/migration/V22__payment_fields.sql`
- `core-java/src/test/java/uk/jtoye/core/payment/PaymentServiceTest.java`

### Modified files
- `core-java/build.gradle.kts` — added `com.stripe:stripe-java:28.2.0`
- `core-java/src/main/java/uk/jtoye/core/order/Order.java` — 3 payment fields
- `core-java/src/main/java/uk/jtoye/core/order/dto/OrderDto.java` — 3 payment fields
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java` — new DRAFT+PaymentIntent flow
- `core-java/src/main/java/uk/jtoye/core/storefront/dto/GuestOrderConfirmation.java` — `clientSecret` field
- `core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicOrderStatus.java` — `paymentStatus` field
- `core-java/src/main/resources/application.yml` — `stripe:` config block
- `core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java` — PaymentService mock
- `frontend/app/shop/[slug]/checkout/page.tsx` — two-step Stripe Elements flow
- `frontend/lib/env-validation.ts` — NODE_ENV guards on logs
- `frontend/package.json` + `package-lock.json` — Stripe deps
- `.env.example` — Stripe keys
- `.gitignore` — `edge-go/edge` binary
- `.github/workflows/ci-cd.yaml` — removed .next/ from artifact upload
- `docker-compose.full-stack.yml` — Stripe env vars
- `infra/monitoring/docker-compose.monitoring.yml` — Grafana env var, removed `version:`
- `docs/AI_CONTEXT.md`, `docs/reports/GAP_ANALYSIS.md`, `docs/status/PROJECT_STATUS.md`, `docs/guides/USER_GUIDE.md`, `docs/analysis/ENTERPRISE_STRATEGIC_ANALYSIS.md` — test counts updated
- `HANDOFF.md` — this file
- `edge-go/edge` — deleted (untracked)

## Resume Instructions

### Option A: Commit and push now (recommended)
```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git checkout feat/image-upload
git add -A  # or selectively stage
git commit -m "feat: Stripe payment integration + housekeeping fixes"
git push
# PR #20 auto-updates
```

Expected: CI runs, 190 Java tests + 26 Go + frontend build all pass.

### Option B: Split into two commits
```bash
# Commit 1: Stripe integration
git add core-java/src/main/java/uk/jtoye/core/payment/ \
        core-java/src/main/java/uk/jtoye/core/order/PaymentStatus.java \
        core-java/src/main/java/uk/jtoye/core/order/Order.java \
        core-java/src/main/java/uk/jtoye/core/order/dto/OrderDto.java \
        core-java/src/main/java/uk/jtoye/core/storefront/ \
        core-java/src/main/resources/db/migration/V22__payment_fields.sql \
        core-java/src/main/resources/application.yml \
        core-java/build.gradle.kts \
        core-java/src/test/java/uk/jtoye/core/payment/ \
        core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java \
        frontend/app/shop/[slug]/checkout/page.tsx \
        frontend/package.json frontend/package-lock.json \
        .env.example
git commit -m "feat: Stripe payment integration for guest checkout"

# Commit 2: Housekeeping
git add -A
git commit -m "chore: housekeeping — env vars, docs, Grafana password, CI artifacts"
git push
```

### Verify before committing
```bash
# Backend
JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64 ./gradlew :core-java:test --rerun
# Expect: BUILD SUCCESSFUL, 190 tests

# Frontend
cd frontend && npx next build
# Expect: Compiled successfully

# Compose validation
docker compose -f docker-compose.full-stack.yml config --quiet
docker compose -f infra/monitoring/docker-compose.monitoring.yml config --quiet
# Expect: no output (valid)
```

### Then: Test Stripe end-to-end (optional but recommended)
1. Get Stripe test keys from https://dashboard.stripe.com/test/apikeys
2. Add to `.env`: `STRIPE_API_KEY=sk_test_...`, `STRIPE_WEBHOOK_SECRET=whsec_...`
3. Add to `frontend/.env.local`: `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY=pk_test_...`
4. Start services: `docker compose -f docker-compose.full-stack.yml up -d`
5. Install Stripe CLI, forward webhooks: `stripe listen --forward-to http://localhost:9090/public/payments/webhook`
6. Browse to `http://localhost:3000/shop`, pick a shop, add to cart, checkout
7. Use test card `4242 4242 4242 4242`, any future expiry, any CVC
8. Verify: order transitions DRAFT→PENDING, FinancialTransaction created, payment_reference = Stripe PI ID

## Setup Required

- **Docker**: `docker-compose.full-stack.yml` (10 containers)
- **Java**: `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64` (Java 21 toolchain)
- **Node**: v20.19.3
- **GPU**: NVIDIA RTX 2080 Ti with NVIDIA Container Toolkit (for Ollama)
- **Ollama**: Host at localhost:11434, `gemma3:12b` model
- **Stripe**: Test keys from Stripe Dashboard (optional — COD fallback works without)
- **Stripe CLI**: For local webhook forwarding (https://stripe.com/docs/stripe-cli)
- **Test users**: `tenant-a-user` / `password123` (vendor)

## Warnings

- Java 25 is system default but Gradle toolchain requires Java 21 — always set `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`
- `StorageProperties` S3 credentials empty by default — `.env` MUST provide them
- Docker Ollama container can't pull models — use host Ollama
- `llava:7b` crashes on RTX 2080 Ti — use `gemma3:12b`
- Stripe webhook signature uses raw body — controller takes `@RequestBody String payload` (don't change to JSON binding)
- `PaymentService.init()` gracefully degrades with empty API key — don't add fail-fast validation
- `automatic_payment_methods: enabled` requires HTTPS in production (localhost is fine for dev)
