# Handoff: J'Toye OaaS — Batch 3 Complete + Housekeeping

**Generated**: 2026-04-06
**Branch**: `feat/batch3-business-logic` (PR #21 open, 8 commits ahead of main)
**Status**: Batch 3 complete, tested E2E, housekeeping done. Ready to merge.

## Goal

Implement Batch 3 business logic gaps (VAT, opening hours, allergens, idempotency) and close test coverage gaps.

## Completed (This Session)

### Batch 3 — Business Logic Gaps ✅
- [x] **VAT at checkout** — V23 migration adds `subtotal_pennies`, `vat_rate`, `vat_amount_pennies` to orders. `Order.calculateTotal()` computes VAT (20% STANDARD). Frontend shows subtotal + "VAT calculated at checkout" + total
- [x] **Opening hours enforcement** — `PublicStorefrontService.validateShopIsOpen()` parses JSONB opening hours, rejects orders when shop is closed. 2 tests
- [x] **Allergen cross-check** — Optional `customerAllergenMask` on `GuestOrderRequest`. Bitwise AND against product allergens. Soft warnings in `GuestOrderConfirmation`. Amber warning banner in checkout UI
- [x] **Order idempotency** — V24 migration adds `idempotency_key` with unique partial index. Frontend sends UUID per checkout session via `useRef(crypto.randomUUID())`
- [x] **COD fallback** — `PaymentService.isConfigured()` check. Orders go PENDING with "Cash on Delivery" when no Stripe key. Stock deducted. Event published after commit

### Test Coverage ✅
- [x] **DevTenantServiceTest** (7 tests) — SQL query, params, idempotency
- [x] **ProductLabelServiceTest** (10 tests) — PDF generation, allergens, edge cases
- [x] **EmailNotificationServiceTest** (11 tests) — all notification types, conditionals, error handling
- [x] Total: 289 tests (220 Java + 26 Go + 43 Jest), 100% pass rate

### Housekeeping ✅
- [x] Test counts updated in 5 living docs (261→289)
- [x] CHANGELOG entries for Batch 2 (Stripe) and Batch 3
- [x] `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` added to `.env.example`
- [x] `ANTHROPIC_API_KEY` uncommented in `.env.example`
- [x] Grafana default password removed (requires explicit env var)
- [x] Ollama healthcheck fixed (`ollama list` instead of missing `curl`)
- [x] V23 migration fixed (NOT NULL DEFAULT pattern for real data)
- [x] All Docker containers rebuilt with latest code

### Previously Completed (Prior Sessions)
- [x] Batch 1: per-shop products, inventory tracking, 0-items bug fix
- [x] Batch 2: Stripe payment integration, PaymentService, webhook handling, two-step checkout
- [x] MinIO/S3 image storage, AI image recognition, bulk import
- [x] Order state machine, RabbitMQ, email notifications, RLS multi-tenancy

## Not Yet Done

### Batch 4 — Infra/Process
- [ ] **Automated K8s backup** — CronJob for pg_dump → S3
- [ ] **CORS from env vars** — SecurityConfig CORS origins currently hardcoded
- [ ] **Keycloak token lifespan** — 3600s too long for production, reduce to 300-900s
- [ ] **GDPR endpoints** — Data export + erasure ("right to be forgotten")

### Remaining Test Coverage Gaps
- [ ] PaymentController — webhook endpoint test (signature verification, 400 on bad sig)
- [ ] PublicStorefrontController (service is tested, controller is not)
- [ ] Security filters (JwtTenantFilter, TenantFilter, TenantContextCleanupFilter)

### Infra Debt
- [ ] postgres-exporter has hardcoded `jtoye:secret` in `infra/monitoring/docker-compose.monitoring.yml:66`
- [ ] Next.js 16 `middleware` → `proxy` rename deprecation warning
- [ ] Most `@SpringBootTest` tests lack `@ActiveProfiles("test")` — risks failures without local Postgres/Redis
- [ ] CI tests use H2 despite Postgres service being available — RLS/Flyway behavior untested in CI
- [ ] 7 npm packages with major version drift (Tailwind v3→v4, lucide v0→v1, TypeScript v5→v6)

## Failed Approaches (Don't Repeat)

1. **V23 migration**: `ALTER TABLE ... ADD COLUMN` then `UPDATE` then `SET NOT NULL` fails because existing rows have NULL before UPDATE runs. Fix: use `ADD COLUMN ... NOT NULL DEFAULT 0` then backfill, then drop default.
2. **Stale Docker containers**: Rebuilt backend but forgot to rebuild frontend — frontend showed old checkout UI without VAT. Always rebuild ALL affected containers before E2E testing.
3. **Ollama healthcheck**: Container image has no `curl`. Use `ollama list` instead.
4. **JDK 25 + Gradle 8.10**: Incompatible. Always use `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`.

## Key Decisions

| Decision | Rationale |
|----------|-----------|
| VAT rate = STANDARD (20%) default | J'Toye sells hot/prepared food — standard UK VAT rate applies |
| Allergen check is soft warning, not blocker | Natasha's Law compliance: inform customer, let them decide |
| Idempotency via partial unique index | Only enforced when key is non-null, so old orders without keys aren't affected |
| COD as fallback, not primary | Stripe is the intended flow; COD prevents total failure when key is missing |
| Host Ollama instead of Docker | Host already has models + GPU access. Docker Ollama conflicts on port 11434 |

## Environment State

- **Branch**: `feat/batch3-business-logic` (8 commits, PR #21)
- **Java**: JDK 21 (`/usr/lib/jvm/jdk-21.0.6-oracle-x64`)
- **Docker**: core-java, frontend, edge-go rebuilt with latest code. Ollama running on host
- **Database**: Flyway V1-V24 all applied successfully
- **Tests**: 289 total, 100% pass rate
- **CI**: PR #21 checks pending

## Resume Instructions

1. `git checkout feat/batch3-business-logic` — branch has all work
2. Merge PR #21 into main: `gh pr merge 21 --squash`
3. After merge, clean up: `git checkout main && git pull && git branch -d feat/batch3-business-logic`
4. Next work: Batch 4 (CORS, Keycloak, GDPR, K8s backup) or remaining test gaps
5. To run stack: `docker compose -f docker-compose.full-stack.yml up -d` (Ollama runs on host separately)
6. To verify checkout: browse http://localhost:3000/shop → add items → checkout → expect COD success with VAT breakdown
