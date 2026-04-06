# Handoff: J'Toye OaaS — Batch 5 Customer Experience

**Generated**: 2026-04-06
**Branch**: `feat/batch5-customer-experience` (3 commits ahead of main)
**Status**: Batch 5 backend complete. Frontend delivery fee + review UI still needed.

## Completed (This Session)

### Batch 5 — Customer Experience (Backend) ✅
- [x] **PostgreSQL full-text search** — V25 migration: tsvector + GIN indexes on products (title/category/description/ingredients/dietary) and shops (name/tags/description/address). Weighted ranking (A-D), auto-update triggers, `fullTextSearch()` with ts_rank, LIKE fallback
- [x] **Delivery fee calculation** — V26 migration: `delivery_fee_pennies` + `free_delivery_threshold_pennies` on shops. Orders track delivery fee. `calculateTotal()` = subtotal + VAT + delivery. Fee waived above threshold
- [x] **Customer reviews with photos** — V27 migration: reviews table, food/delivery split ratings, photo URLs, one-per-order constraint. ReviewService with order ownership validation. `GET/POST /public/shops/{slug}/reviews`. `shop_ratings` SQL view

### Previously Completed
- [x] Batch 3: VAT, opening hours, allergens, idempotency, COD fallback (PR #21 merged)
- [x] Batch 2: Stripe payments (PR #20 merged)
- [x] Batch 1: per-shop products, inventory, image upload, AI recognition, bulk import

## Not Yet Done

### Batch 5 — Frontend (this branch)
- [ ] **Delivery fee in checkout UI** — Show delivery fee line in order summary, "Free delivery over £X" badge on shop cards
- [ ] **Reviews UI** — Star rating display on shop page, review submission form on order detail page, photo upload in reviews
- [ ] **Search UI upgrade** — Frontend may already work (backend returns ranked results) but could add "no results" handling

### Batch 4 — Infra/Process
- [ ] CORS from env vars, Keycloak token lifespan, GDPR endpoints, K8s backup

### Tier 2 — Operational Reliability
- [ ] Resilience4j circuit breaker on Stripe/email/Ollama
- [ ] RabbitMQ dead letter queue
- [ ] Custom business metrics (orders/hour, revenue/day)
- [ ] Scheduled cleanup jobs (stale DRAFT orders, orphaned images)

## Key Decisions

| Decision | Rationale |
|----------|-----------|
| PostgreSQL full-text search over Elasticsearch | Simpler ops, no new dependency, handles 100K+ products. Add ES when needed |
| Delivery fee on Shop, not per-product | UK food delivery charges per-order, not per-item |
| Free delivery threshold | Industry standard (Glovo, Deliveroo) — drives higher basket value |
| Split food/delivery ratings | Glovo pattern — restaurant shouldn't be penalized for courier delays |
| One review per order | Prevents spam, ensures verified purchase |

## Environment State

- **Branch**: `feat/batch5-customer-experience`
- **Tests**: 289+ passing (all existing + new features compile clean)
- **Migrations**: V25 (search), V26 (delivery fee), V27 (reviews)
- **Docker**: core-java/frontend/edge-go images need rebuild for this branch

## Resume Instructions

1. `git checkout feat/batch5-customer-experience`
2. Rebuild containers: `docker compose -f docker-compose.full-stack.yml build --no-cache core-java frontend`
3. Restart: `docker compose -f docker-compose.full-stack.yml up -d`
4. Next: Add frontend UI for delivery fees and reviews
5. Then: Push, open PR, E2E test, merge
