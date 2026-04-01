# Handoff: J'Toye OaaS — Real-time Updates, WhatsApp Ordering, Label Printing

**Generated**: 2026-04-01T16:10+01:00  
**Branch**: `main` at `9ddceaf`  
**Status**: In Progress — 3 features queued

## Goal

Implement three platform features to close the major functional gaps: real-time UI updates via RabbitMQ/SSE, WhatsApp message-to-order parsing in edge-go, and PDF allergen label generation.

## Completed (This Session — 10 PRs)

- [x] PR #2: Version alignment (OpenAPI 1.2.0, README badge)
- [x] PR #3: Order detail dialog with line items (click row → dialog with items table)
- [x] PR #4: RabbitMQ order state change consumer (`OrderStateChangeListener`)
- [x] PR #5: Financial reporting — `GET /financial-transactions/summary` + Finance dashboard page
- [x] PR #6: Product price field fix + order total NaN fix (`totalPricePennies` → `totalAmountPennies`)
- [x] PR #7: Product price column in table + docs freshness audit
- [x] PR #8: Dashboard charts (recharts donut + bar), customer orders endpoint, backend search
- [x] PR #9: Server-side search wiring (debounced frontend → `/search?q=`), customer order filtering via `?customer=` param
- [x] PR #10: Removed 18 unused Java imports/variables
- [x] Git tag `v1.2.0`
- [x] E2E verified: 46-point Playwright suite + 19-point wiring test — all passed

## Not Yet Done

- [ ] **Real-time updates**: Orders page auto-refreshes when order state changes via RabbitMQ
- [ ] **WhatsApp ordering**: Parse incoming WhatsApp webhook messages into `POST /orders` calls
- [ ] **Allergen label printing**: Generate PDF labels with product allergen data
- [ ] Tag `v1.3.0` after above features land
- [ ] Update HANDOFF.md + CHANGELOG for final state

## Failed Approaches (Don't Repeat These)

- **Product creation via UI during E2E**: Frontend form was missing `pricePennies` field, causing 400 from backend. Fixed in PR #6 by adding Price (£) input with pennies conversion.
- **Keycloak token via `core-api` client**: Returns `unauthorized_client`. The correct client for password grant is `test-client` (public client).
- **`order.totalPricePennies` in frontend**: Backend sends `totalAmountPennies`. The `Order` type had wrong field name causing `£NaN`. Fixed in PR #6.
- **Recharts `Tooltip formatter` type**: `(value: number) => [...]` fails TypeScript strict mode. Use `(value) => [...]` with `Number(value)` cast instead.
- **Jest + recharts**: SVG rendering crashes in jsdom. Must mock recharts components as simple divs.

## Key Decisions

| Decision | Rationale |
|----------|-----------|
| Debounced search (300ms, 2+ chars) | Avoids hammering backend on every keystroke; falls back to full list on empty |
| Customer orders via query param `?customer=` | Reuses existing orders page instead of building separate customer-orders page |
| `OrderStateChangeListener` as extension point | Logs all transitions; COMPLETED/CANCELLED handlers ready for email/webhook |
| Recharts for charts | Lightweight, React-native, no D3 dependency; donut + bar cover dashboard needs |
| Server-side search via JPQL LIKE | RLS automatically scopes results to tenant; pagination not needed for search results |

## Current State

**Working**:
- Full stack running: 7 Docker containers (postgres, keycloak, redis, rabbitmq, core-java, edge-go, frontend)
- All CRUD operations, order lifecycle (DRAFT→COMPLETED), financial reporting
- Dashboard with charts, server-side search, customer order filtering
- 120 Java unit tests + 43 Jest tests passing

**Not broken, but IDE noise**:
- 20 RabbitMQ "errors" in IDE — classpath issue, not real (Gradle compiles clean). Fix: reimport Gradle project in IntelliJ.
- 314 null-safety warnings — Spring/JPA framework noise

**Uncommitted Changes**: Only IDE files (.idea/) and build artifacts (build-local/, edge binary) — no source changes.

## Files to Know

| File | Why It Matters |
|------|----------------|
| `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java` | RabbitMQ consumer — extend for real-time SSE push |
| `core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java` | Exchange: `order.events`, Queue: `order.state-changes`, Routing: `order.state.*` |
| `edge-go/cmd/edge/main.go` | WhatsApp webhook handler — currently forwards raw payload to core API |
| `frontend/app/dashboard/orders/page.tsx` | Orders page — needs SSE/polling integration for auto-refresh |
| `core-java/src/main/java/uk/jtoye/core/product/Product.java` | Has `ingredientsText`, `allergenMask`, `pricePennies` — data source for labels |
| `frontend/types/api.ts` | All TypeScript interfaces — `Order`, `OrderDetail`, `Product`, `FinancialSummary`, etc. |

## Code Context

**RabbitMQ event shape** (published on every order state transition):
```java
public record OrderStateChangeEvent(
    UUID orderId,
    UUID tenantId,
    String orderNumber,
    OrderStatus previousStatus,
    OrderStatus newStatus,
    OffsetDateTime timestamp
) {}
```

**Current listener** (extension points marked):
```java
@RabbitListener(queues = RabbitMQConfig.ORDER_EVENTS_QUEUE)
public void handleOrderStateChange(OrderStateChangeEvent event) {
    switch (event.newStatus()) {
        case COMPLETED -> handleOrderCompleted(event);  // Extension point
        case CANCELLED -> handleOrderCancelled(event);  // Extension point
        default -> log.debug("...");
    }
}
```

**WhatsApp webhook** (edge-go forwards to core):
```go
// Currently in edge-go/cmd/edge/main.go
// POST /webhook/whatsapp → verifies signature → forwards body to Core API
// TODO: Parse message text → extract order intent → POST /orders
```

**Allergen bitmask** (14 allergens, UK Natasha's Law):
```typescript
// bit 0=Gluten, 1=Crustaceans, 2=Eggs, 3=Fish, 4=Peanuts, 5=Soybeans,
// 6=Milk, 7=Nuts, 8=Celery, 9=Mustard, 10=Sesame, 11=Sulphites, 12=Lupin, 13=Molluscs
```

## Resume Instructions

1. Verify stack is running:
   ```bash
   docker compose -f docker-compose.full-stack.yml up -d
   curl -sf http://localhost:9090/actuator/health   # {"status":"UP"}
   curl -sf http://localhost:3000 -o /dev/null -w "%{http_code}\n"  # 307
   ```

2. **Real-time updates** approach:
   - Add SSE endpoint `GET /orders/stream` in `OrderController` using Spring's `SseEmitter`
   - `OrderStateChangeListener` pushes to an `SseEmitterRegistry` on state change
   - Frontend: `EventSource` in orders page `useEffect` → update orders state on event
   - Alternative: simple polling every 5s (simpler but less elegant)

3. **WhatsApp ordering** approach:
   - Read `edge-go/cmd/edge/main.go` to understand current webhook handler
   - Parse WhatsApp Cloud API message format: `entry[].changes[].value.messages[].text.body`
   - Extract order intent (e.g., "2x Chocolate Cake") → match products by title → POST /orders
   - Needs: shop assignment strategy (default shop per tenant, or from message context)

4. **Label printing** approach:
   - Add `GET /products/{id}/label` endpoint returning PDF
   - Use iText or OpenPDF (Java) to generate allergen label with product name, ingredients, allergen icons
   - Frontend: "Print Label" button on products page → downloads PDF
   - Template: product name, SKU, ingredients list, allergen warnings with icons

5. Test with Playwright E2E after each feature. Auth: `tenant-a-user` / `password123` (Keycloak client: `test-client`).

## Setup Required

- `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64` (system default Java 25 is incompatible with Gradle 8.10.2)
- Docker containers: `docker compose -f docker-compose.full-stack.yml up -d`
- Keycloak test user: `tenant-a-user` / `password123` (tenant: `00000000-0000-0000-0000-000000000001`)
- JWT for API testing: POST to `http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token` with `client_id=test-client`, `grant_type=password`

## Warnings

- **Never use `core-api` as client_id** for password grant — it's confidential. Use `test-client`.
- **Frontend `Order.totalAmountPennies`** not `totalPricePennies` — field was renamed in PR #6.
- **Recharts in tests**: Must mock all components as divs + mock `ResizeObserver`.
- **IDE shows 20 RabbitMQ errors**: False positives. `./gradlew compileJava` succeeds. Reimport Gradle project to fix.
- **Integration tests (27)**: Require TestContainers with Docker daemon. They're expected to fail in local `./gradlew test` without Docker-in-Docker.
