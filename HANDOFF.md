# Session Handoff — J'Toye OaaS v1.2.1

**Date**: 2026-04-01  
**Branch**: `main`  
**Last commit**: `da9c5b3 fix: add price field to product form and fix order total field name (#6)`  
**Tag**: `v1.2.0` (on `76366a4`)

---

## What Was Completed This Session

### Quick Wins (PRs #2)
- OpenAPI version: `0.1.0-SNAPSHOT` → `1.2.0`
- README badge: `1.1.0` → `1.2.0`
- Git tag `v1.2.0` created and pushed

### Features (PRs #3–#5)
- **Order Detail Dialog** (#3): Click any order row → dialog shows order number, status, customer info, shop name, line items table with product names/quantities/prices, state transition buttons
- **RabbitMQ Consumer** (#4): `OrderStateChangeListener` consumes `order.state-changes` queue, logs all transitions, dedicated COMPLETED/CANCELLED handlers with extension points
- **Financial Reporting** (#5): `GET /financial-transactions/summary` endpoint, Finance dashboard page with Revenue/Expenses/Net/VAT cards, VAT breakdown panel, paginated transaction list, Finance sidebar nav link

### Bug Fixes (PR #6)
- Product form: added missing Price (£) input field (was returning 400)
- Order total: fixed `totalPricePennies` → `totalAmountPennies` (was showing £NaN)
- Removed stale `CreateOrderRequest.totalPricePennies`

### E2E Verified
- Full Playwright E2E suite: login → create shop/product/order → order detail dialog → order lifecycle (DRAFT→COMPLETED) → finance page shows revenue + VAT breakdown

---

## Remaining Work (Next Session)

### Polish
1. **Product price in table** — PR pending merge (adds Price column to products table)
2. **Docs freshness** — CHANGELOG updated to v1.2.1, README test badge updated to 166

### Features
3. **Dashboard charts** — revenue over time, order status distribution (data available via summary endpoint)
4. **Email notifications** — extend RabbitMQ consumer to send emails on order completion/cancellation
5. **Customer order history** — link customers page to their orders
6. **Pagination on search** — backend search endpoints for shops/products (currently client-side only)

### Technical Debt
7. **Product form allergens** — checkbox clicks don't use standard shadcn `Checkbox` component
8. **Integration tests** — 27 integration tests need TestContainers; consider CI Docker setup
9. **Tag v1.2.1** — after the pending PR merges

---

## Environment State

- **Branch**: `main` at `da9c5b3`
- **Docker**: 7 containers (postgres, keycloak, redis, rabbitmq, core-java, edge-go, frontend)
- **Java**: Requires `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`
- **Tests**: 120 Java unit + 43 Jest + 3 Go = 166 passing. 27 integration tests need Docker/TestContainers.
- **Keycloak test user**: `tenant-a-user` / `password123` (client: `test-client`)

---

## Resume Instructions

```bash
# 1. Verify state
git log --oneline -3
# expect: da9c5b3, 76366a4, 724b39b

# 2. Start services
docker compose -f docker-compose.full-stack.yml up -d

# 3. Verify health
curl -sf http://localhost:9090/actuator/health
curl -sf http://localhost:3000 -o /dev/null -w "Frontend: %{http_code}\n"
```
