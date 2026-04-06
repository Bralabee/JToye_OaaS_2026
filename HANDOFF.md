# Handoff: J'Toye OaaS — Batches 3+5 Complete

**Generated**: 2026-04-06
**Branch**: `main` (all work merged)
**Status**: Batches 3 and 5 complete and merged. Housekeeping done. Ready for Batch 4.

## Completed (This Session)

### Batch 3 — Business Logic (PR #21, merged)
- [x] VAT at checkout (V23) — 20% STANDARD, subtotal + VAT breakdown
- [x] Opening hours enforcement — server-side validation, 2 tests
- [x] Allergen cross-check — bitwise AND, soft warnings
- [x] Order idempotency (V24) — unique partial index
- [x] COD fallback — PaymentService.isConfigured() check
- [x] 28 new tests (DevTenant, ProductLabel, EmailNotification)

### Batch 5 — Customer Experience (PR #22, merged)
- [x] PostgreSQL full-text search (V25) — tsvector + GIN indexes, ts_rank ordering
- [x] Delivery fee calculation (V26) — per-shop fee, free threshold, shown in checkout
- [x] Customer reviews with photos (V27) — split food/delivery ratings, verified purchase
- [x] Server-driven shop config (V28) — announcements, promotions, featured products
- [x] Frontend: delivery badge, star ratings, review cards, promo banners

### Housekeeping
- [x] 383 tests (270 Java + 19 Go + 69 Jest + 25 Playwright), 100% pass
- [x] Ollama healthcheck fixed, Grafana pw secured, env parity fixed
- [x] CHANGELOG entries for Batches 2-5
- [x] 39/40 E2E Playwright checks passing with screenshots

## Not Yet Done

### Batch 4 — Infra/Process (highest priority)
- [ ] **CORS from env vars** — SecurityConfig CORS origins hardcoded, blocks real deployment
- [ ] **Keycloak token lifespan** — 3600s too long, reduce to 300-900s
- [ ] **GDPR endpoints** — Data export + erasure (UK legal requirement)
- [ ] **K8s backup CronJob** — pg_dump → S3

### Tier 2 — Reliability
- [ ] Resilience4j circuit breaker on Stripe/email/Ollama
- [ ] RabbitMQ dead letter queue + retry
- [ ] Custom business metrics (orders/hour, revenue/day)
- [ ] Scheduled cleanup jobs (stale DRAFT orders, orphaned images)

### Tier 3 — Enhancement
- [ ] Vendor dashboard UI for announcements/promotions (API exists, no UI)
- [ ] API versioning (/api/v1/ prefix)
- [ ] WebSocket for kitchen displays

### Remaining Test Gaps
- [ ] PaymentController (webhook endpoint)
- [ ] PublicStorefrontController (service tested, controller not)
- [ ] Security filters (JwtTenantFilter, TenantFilter)
- [ ] ReviewService (new, needs tests)

## Failed Approaches (Don't Repeat)
1. V23 migration: `SET NOT NULL` without `DEFAULT` fails on existing rows — use `NOT NULL DEFAULT 0`
2. Stale Docker: always rebuild ALL containers before E2E testing
3. Ollama healthcheck: no curl in image — use `ollama list`
4. JDK 25 + Gradle 8.10: incompatible — use `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`
5. Native SQL `ORDER BY` + Spring Pageable: conflicts — add `countQuery`, use `Sort.unsorted()`

## Environment
- **Branch**: `main`
- **Java**: JDK 21 (`/usr/lib/jvm/jdk-21.0.6-oracle-x64`)
- **Migrations**: V1-V28 all applied
- **Docker**: core-java + frontend rebuilt, Ollama on host
- **Tests**: 383 total, 100% pass

## Resume Instructions
1. `git checkout main && git pull`
2. Next: `git checkout -b feat/batch4-infra` — start with CORS from env vars
3. Stack: `docker compose -f docker-compose.full-stack.yml up -d` (rebuild first if code changed)
4. Verify: `curl http://localhost:9090/actuator/health` + `http://localhost:3000`
