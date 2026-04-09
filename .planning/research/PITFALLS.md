# Domain Pitfalls

**Domain:** Real-time WebSocket kitchen displays, vendor marketing dashboard, API versioning for multi-tenant food ordering SaaS
**Researched:** 2026-04-07

## Critical Pitfalls

Mistakes that cause rewrites, data leaks, or major production incidents.

### Pitfall 1: WebSocket Messages Broadcast Across Tenants

**What goes wrong:** The existing `OrderSseService` broadcasts order state changes to ALL connected SSE clients regardless of tenant. It stores emitters in a flat `CopyOnWriteArrayList` with no tenant association. If this pattern carries over to WebSocket, Tenant A's kitchen display sees Tenant B's orders.

**Why it happens:** The SSE service was likely built as a quick proof-of-concept. `SseEmitter` objects are anonymous -- no tenant ID is attached at subscription time. The `broadcast()` method iterates all emitters indiscriminately (see `OrderSseService.java:29-39`).

**Consequences:** Cross-tenant data leakage. In a food SaaS, this means vendors see each other's order volumes, customer names, and order contents. This is a GDPR violation and a business-ending trust breach.

**Prevention:**
- Map WebSocket sessions to tenant IDs at connection handshake (extract from JWT during STOMP CONNECT)
- Use tenant-scoped STOMP destinations: `/topic/orders/{tenantId}/{shopId}` not `/topic/orders`
- Add a `ChannelInterceptor` that validates the tenant claim matches the subscribed destination
- Unit test: connect as Tenant A, subscribe to Tenant B's channel, assert rejection
- Replace or refactor `OrderSseService` at the same time -- do not leave the old tenant-blind SSE endpoint active

**Detection:** Any SSE/WebSocket endpoint that does not filter by `TenantContext` is suspect. Grep for `broadcast` methods that iterate all subscribers without a tenant check.

**Phase:** Must be addressed in the WebSocket implementation phase, before any kitchen display work.

**Confidence:** HIGH -- verified directly from codebase (`OrderSseService.java` lines 19-41).

---

### Pitfall 2: TenantContext Lost in WebSocket Message Handlers

**What goes wrong:** `TenantContext` uses `ThreadLocal<UUID>`. WebSocket/STOMP message handlers run on a different thread pool than HTTP request threads. The `JwtTenantFilter` and `TenantFilter` in the security filter chain only run on HTTP requests. WebSocket message handlers execute without tenant context set, causing RLS to either return no data or (worse) return data across all tenants if `app.current_tenant_id` defaults to empty.

**Why it happens:** Spring's HTTP filter chain (`SecurityFilterChain`) does not apply to STOMP message handling. STOMP uses `ChannelInterceptor` for security, which is a completely separate pipeline. The existing `TenantSetLocalAspect` triggers on `@Transactional` methods, but the tenant ID in `TenantContext` ThreadLocal will be null unless explicitly set for the WebSocket thread.

**Consequences:** Database queries from WebSocket handlers either fail (no tenant context) or silently bypass RLS (empty tenant context treated as wildcard by some PostgreSQL configurations). Either way, the kitchen display either shows nothing or shows everything.

**Prevention:**
- Implement a STOMP `ChannelInterceptor` that extracts tenant ID from the JWT token in the STOMP CONNECT frame headers and stores it in session attributes
- Before any DB operation triggered by WebSocket messages, set `TenantContext` from the session attributes (not from ThreadLocal carried over from HTTP)
- Add an integration test: send a STOMP message, verify `TenantContext.get()` returns the correct tenant ID inside the handler
- Consider passing `tenantId` explicitly as a method parameter rather than relying on ThreadLocal (the CONCERNS.md already flags this for email -- same applies here)

**Detection:** Any `@MessageMapping` handler that calls a `@Transactional` service without explicitly setting `TenantContext` first.

**Phase:** Must be addressed in the WebSocket implementation phase, alongside Pitfall 1.

**Confidence:** HIGH -- verified from `TenantContext.java` (ThreadLocal-based), `SecurityConfig.java` (HTTP filter chain only), and `TenantSetLocalAspect.java` (aspect triggers on transactions but relies on ThreadLocal being pre-populated).

---

### Pitfall 3: API Versioning Breaks Edge Gateway Routing

**What goes wrong:** The Go edge service (`edge-go/cmd/edge/main.go`) proxies requests to the Spring Boot core at hardcoded paths like `/sync/batch` and `/webhooks/whatsapp`. Adding `/api/v1/` prefix to all Spring Boot endpoints breaks every edge route unless the Go service is updated simultaneously. The frontend also hits Spring Boot directly at paths like `/orders/stream` and `/public/shops`.

**Why it happens:** The API versioning decision says "URL prefix /api/v1/" but the impact analysis underestimates the number of callers. There are at minimum three clients that need coordinated updates: (1) Go edge service routes, (2) Next.js frontend API calls (hardcoded `NEXT_PUBLIC_API_URL` + path), (3) any integration tests hitting the API directly.

**Consequences:** If Spring Boot endpoints move to `/api/v1/orders` but the frontend still calls `/orders`, every API call returns 404. If deployed as a rolling update, the edge and frontend are briefly incompatible with the core. During the split, real orders fail.

**Prevention:**
- Add `/api/v1/` prefix in Spring Boot but keep old paths working via `@RequestMapping` aliases or a servlet filter that redirects
- Update Go edge service and Next.js frontend in the same deployment/PR
- Note: Spring Boot 3.4.2 does NOT have the built-in `spring.mvc.api-version` property (that is Spring Boot 4 / Spring Framework 7). You must use manual `@RequestMapping` path configuration or a custom `WebMvcConfigurer` with path prefix
- Add an integration test that hits both old and new paths during the migration window
- Consider configuring `server.servlet.context-path=/api/v1` as the simplest approach, but be aware this moves ALL endpoints including `/health`, `/actuator/*`, and Swagger -- which may break Docker healthchecks and monitoring

**Detection:** Deploy to staging first. Run the full E2E suite (Playwright) against staging with versioned endpoints. Any 404 in the test run reveals a missed caller.

**Phase:** API versioning phase. Must be planned as a coordinated multi-service change, not a Spring Boot-only change.

**Confidence:** HIGH -- verified from edge service code (`main.go`), frontend code (`orders/page.tsx` line 248), and Spring Boot controller mappings.

---

### Pitfall 4: WebSocket Connections Exhaust Tomcat Thread Pool

**What goes wrong:** Each WebSocket connection holds a thread (on Tomcat's default blocking I/O). The kitchen display stays open all shift (8-12 hours). With 50 vendors each having 2-3 kitchen displays plus the existing SSE connections for the dashboard orders page, you quickly approach Tomcat's default 200-thread limit. New HTTP requests (API calls, payment webhooks) start queuing or timing out.

**Why it happens:** Spring Boot's default embedded Tomcat uses blocking I/O for WebSocket. The `application.yml` already shows `hikari.maximum-pool-size: 20` for DB connections, but there is no WebSocket-specific thread pool configuration. The existing SSE endpoint (`/orders/stream`) already holds threads open for 5 minutes per connection.

**Consequences:** Under load, the application becomes unresponsive. Payment webhooks from Stripe time out and get retried. Order state transitions fail. The system appears "down" even though the process is running.

**Prevention:**
- Configure a dedicated async thread pool for WebSocket message handling separate from the Tomcat request thread pool
- Set explicit WebSocket session limits: `server.tomcat.threads.max` should account for WebSocket connections
- Implement connection limits per tenant (e.g., max 5 WebSocket connections per tenant)
- Add heartbeat/ping-pong to detect and close dead connections (kitchen tablet goes to sleep but TCP connection lingers)
- Monitor active WebSocket session count via Micrometer metrics and alert when approaching 70% of thread capacity
- Consider whether SSE (unidirectional, for dashboard order list) should coexist with WebSocket (bidirectional, for kitchen display) or whether SSE should be replaced entirely

**Detection:** Monitor `tomcat.threads.busy` and `tomcat.threads.config.max` metrics. If busy threads approach max during peak hours, WebSocket connections are the likely cause.

**Phase:** WebSocket implementation phase. Thread pool sizing must be decided before go-live.

**Confidence:** MEDIUM -- based on Tomcat defaults and the existing SSE pattern. Actual impact depends on concurrent vendor count.

---

### Pitfall 5: Stripe Webhook Breaks Under API Versioning

**What goes wrong:** Stripe sends webhooks to a configured URL (e.g., `https://api.jtoye.uk/public/payments/webhook`). If API versioning moves this to `/api/v1/public/payments/webhook`, the Stripe webhook configuration must be updated simultaneously. Between the deployment and the Stripe dashboard update, webhook deliveries fail. Stripe retries for up to 72 hours, but during that window, payment confirmations are delayed.

**Why it happens:** External webhook senders (Stripe, WhatsApp) cannot be updated atomically with your deployment. They are configured in third-party dashboards with their own update cadence.

**Consequences:** Payments marked as "pending" indefinitely. Customers charged but orders not confirmed. Manual intervention required to reconcile.

**Prevention:**
- Exempt webhook endpoints from API versioning entirely -- keep `/public/payments/webhook` as a permanent stable path
- Or: implement a proxy/redirect at the old path that forwards to the versioned path
- Never version external callback URLs; only version endpoints consumed by your own clients
- Document which endpoints are "externally registered" and therefore immune to path changes

**Detection:** After deploying versioned endpoints, check Stripe dashboard webhook delivery logs for failures. Set up Stripe webhook event monitoring alerts.

**Phase:** API versioning phase. Webhook paths must be explicitly excluded from the versioning strategy before implementation begins.

**Confidence:** HIGH -- Stripe webhook is verified at `PaymentController.java:14` (`@RequestMapping("/public/payments/webhook")`).

## Moderate Pitfalls

### Pitfall 6: Kitchen Display Reconnection Loses Order State

**What goes wrong:** When a WebSocket connection drops (Wi-Fi hiccup, tablet sleep, server restart), the kitchen display reconnects but has no way to know which orders arrived during the disconnection gap. The display shows a stale view until the next order event arrives. Kitchen staff miss orders.

**Why it happens:** WebSocket is fire-and-forget -- there is no built-in message replay. The existing SSE implementation (`EventSource` in the frontend) has the same problem: on reconnect, it just starts listening again with no gap recovery.

**Prevention:**
- On WebSocket reconnect, the client must fetch the full current order list via REST (`GET /orders/status/CONFIRMED` + `GET /orders/status/PREPARING`)
- Include a sequence number or timestamp in each WebSocket message so the client can detect gaps
- Implement a "last seen" timestamp on the client; on reconnect, request only events after that timestamp
- Add a visual indicator on the kitchen display when the connection is interrupted ("Connection lost -- reconnecting...")
- Test with network throttling in Playwright to simulate drops

**Detection:** Kitchen staff reporting "missing orders" that appear in the database but were never shown on the display.

**Phase:** Kitchen display UI phase. The reconnection strategy must be designed alongside the initial WebSocket integration.

**Confidence:** HIGH -- this is a universal WebSocket pitfall confirmed by multiple sources.

---

### Pitfall 7: Vendor Dashboard Promotion Scheduling Without Timezone Handling

**What goes wrong:** The `ShopPromotion` entity uses `OffsetDateTime` for `validFrom` and `validUntil`, which is correct. But the vendor dashboard UI must let vendors pick dates/times in their local timezone (UK = BST/GMT depending on season). If the frontend sends UTC and the vendor thinks they are scheduling for "12:00 BST", the promotion goes live an hour early in summer.

**Why it happens:** JavaScript `Date` objects and most date pickers default to the browser's local timezone. The backend stores `OffsetDateTime` but may not validate the offset. If the frontend strips the offset and sends a bare ISO string, the backend interprets it as UTC.

**Consequences:** Promotions go live at wrong times. Discount applied when vendor's shop is closed. Financial impact from unintended discounts.

**Prevention:**
- Always send ISO 8601 with explicit offset from the frontend (e.g., `2026-04-07T12:00:00+01:00`)
- Validate on the backend that `validFrom < validUntil`
- Display scheduled times back to the vendor in their timezone, not UTC
- Add a "preview" step in the dashboard: "This promotion will go live at 12:00 PM BST on 7 April"
- Store the vendor's timezone preference in their shop settings

**Detection:** Promotions appearing in the storefront at unexpected times relative to the vendor's expectation.

**Phase:** Vendor dashboard phase. Must be considered during the promotion scheduling UI design.

**Confidence:** MEDIUM -- `ShopPromotion.java` uses `OffsetDateTime` which is correct, but frontend timezone handling is commonly botched.

---

### Pitfall 8: RabbitMQ Event and WebSocket Event Diverge

**What goes wrong:** The system currently publishes order state changes to RabbitMQ via `OrderEventPublisher`. The new WebSocket kitchen display also needs real-time order events. If WebSocket events are sent from a different code path than RabbitMQ events, they can diverge: RabbitMQ gets the event but WebSocket does not (or vice versa). Two "sources of truth" for real-time events.

**Why it happens:** The temptation is to add `webSocketService.broadcast(event)` alongside `rabbitTemplate.convertAndSend(event)` in the order state change flow. But the RabbitMQ publish is already wrapped in a try-catch that swallows errors (`OrderEventPublisher.java:38`). If one succeeds and the other fails, events are inconsistent.

**Consequences:** Kitchen display shows different state than what email notifications (driven by RabbitMQ) report. Customer gets "order confirmed" email but kitchen display never shows the order.

**Prevention:**
- Use RabbitMQ as the single source of truth for events. Have the WebSocket service consume from RabbitMQ and forward to WebSocket clients
- Pattern: `OrderService -> RabbitMQ -> OrderEventConsumer -> WebSocketBroadcaster` (single event pipeline)
- Do NOT broadcast directly from the controller or service to WebSocket -- always go through the message queue
- This also fixes the existing silent-failure problem: if RabbitMQ publish fails, neither email nor WebSocket fires, making the failure visible
- Add dead letter queue monitoring (already flagged in CONCERNS.md)

**Detection:** Kitchen display and order history page showing different statuses for the same order.

**Phase:** WebSocket implementation phase. The event pipeline architecture must be decided before building the kitchen display.

**Confidence:** HIGH -- verified from `OrderEventPublisher.java` (broad exception catch, no WebSocket integration).

---

### Pitfall 9: Frontend EventSource SSE Connection Lacks Auth Token

**What goes wrong:** The existing SSE connection in the orders dashboard (`frontend/app/dashboard/orders/page.tsx:248`) uses `new EventSource(url)` which does NOT support sending Authorization headers. The SSE endpoint `/orders/stream` requires JWT authentication per `SecurityConfig`. This means the SSE stream either (a) already fails silently and nobody noticed, or (b) is somehow working without auth. Either way, adding WebSocket with proper auth may expose that the SSE path was never properly secured.

**Why it happens:** The browser `EventSource` API does not support custom headers. Authentication for SSE typically requires either cookies or query parameter tokens. The code uses `new EventSource(url)` with no token -- suggesting the endpoint may be accidentally accessible or the connection silently fails (the `onerror` handler just gives up).

**Consequences:** If SSE works without auth, it is a security hole -- any client can subscribe to order events. If SSE silently fails, the "real-time" feature in the dashboard is non-functional and nobody knows.

**Prevention:**
- For WebSocket: use STOMP CONNECT frame to send the JWT token, then validate in a `ChannelInterceptor`
- For SSE (if kept): pass token as query parameter `?token=xxx` and validate server-side, or switch to cookie-based auth for SSE
- Test both paths: (1) connect without token, assert rejection; (2) connect with valid token, assert events received
- Audit the existing SSE endpoint to determine if it is actually functional in production

**Detection:** Open browser DevTools on the dashboard orders page, check the EventSource connection status. If it shows an error or 401, the "real-time" feature is already broken.

**Phase:** Should be investigated immediately as part of the WebSocket phase. May reveal that "real-time" has never worked in production.

**Confidence:** HIGH -- verified from frontend code (`page.tsx:248`) and `SecurityConfig.java:54-58` (all non-public endpoints require auth).

## Minor Pitfalls

### Pitfall 10: API Version in URL Pollutes OpenAPI/Swagger Docs

**What goes wrong:** Adding `/api/v1/` prefix via `server.servlet.context-path` or `@RequestMapping` class-level annotation changes all Swagger/OpenAPI paths. Existing API documentation links, Postman collections, and developer onboarding docs break. The Swagger UI path itself may move (from `/swagger-ui.html` to `/api/v1/swagger-ui.html`).

**Prevention:**
- If using `server.servlet.context-path`, Swagger paths automatically include the prefix -- update all documentation references
- Use `springdoc.api-docs.path` and `springdoc.swagger-ui.path` to control Swagger paths independently
- Update Postman/Insomnia collections as part of the API versioning PR
- Test Swagger accessibility after the change

**Phase:** API versioning phase.

**Confidence:** MEDIUM.

---

### Pitfall 11: Spring Boot 3.4.2 Lacks Built-in API Versioning

**What goes wrong:** Teams research Spring Boot API versioning and find articles about `spring.mvc.api-version.*` properties and `@RequestMapping(version = "1")`. They implement this, and it does not compile. These features are Spring Boot 4 / Spring Framework 7 only (released November 2025). Spring Boot 3.4.2 does not have them.

**Why it happens:** Recent blog posts and documentation focus on the new built-in versioning. Search results mix Spring Boot 3.x and 4.x advice without clear version boundaries.

**Prevention:**
- Stick to manual approaches for Spring Boot 3.4.2: class-level `@RequestMapping("/api/v1/orders")` or `server.servlet.context-path=/api/v1`
- Do NOT attempt to use `spring.mvc.api-version` properties -- they do not exist in 3.4.x
- If upgrading to Spring Boot 4 is planned, defer API versioning to that milestone and use the built-in support

**Detection:** Compilation errors when referencing `version` attribute in `@RequestMapping`.

**Phase:** API versioning phase. Stack version must be confirmed before choosing implementation approach.

**Confidence:** HIGH -- Spring Boot 4/Spring Framework 7 release timeline confirmed via multiple sources; project is on Spring Boot 3.4.2 per `PROJECT.md`.

---

### Pitfall 12: Kitchen Display UI Blocks on Large Order Backlogs

**What goes wrong:** On a busy day, a kitchen display subscribing via WebSocket receives hundreds of orders. If the UI renders all orders in a single list without virtualization, React re-renders become expensive. The tablet browser (often a cheap Android device) becomes unresponsive.

**Prevention:**
- Implement virtual scrolling or limit displayed orders to active statuses only (CONFIRMED + PREPARING)
- Auto-archive COMPLETED orders from the display after 30 seconds
- Use `React.memo` or similar to prevent re-rendering unchanged order cards
- Test on a low-end device (or Chrome DevTools CPU throttling) with 100+ concurrent orders

**Phase:** Kitchen display UI phase.

**Confidence:** MEDIUM -- depends on vendor order volume, but a common real-time dashboard issue.

## Phase-Specific Warnings

| Phase Topic | Likely Pitfall | Mitigation |
|-------------|---------------|------------|
| WebSocket implementation | Tenant isolation (Pitfalls 1, 2) | Build tenant-scoped channels from day one. Test cross-tenant rejection. |
| WebSocket implementation | Thread exhaustion (Pitfall 4) | Size thread pool for expected WebSocket connections. Monitor. |
| WebSocket implementation | Event pipeline divergence (Pitfall 8) | Route WebSocket through RabbitMQ, not direct broadcast. |
| WebSocket implementation | SSE auth gap (Pitfall 9) | Audit existing SSE before building WebSocket. Fix or remove. |
| Kitchen display UI | Reconnection gap (Pitfall 6) | Fetch full state on reconnect. Show connection status indicator. |
| Kitchen display UI | Tablet performance (Pitfall 12) | Virtual scrolling, limit visible orders, test on low-end hardware. |
| API versioning | Multi-service coordination (Pitfall 3) | Update Go edge, Next.js frontend, and Spring Boot in single coordinated change. |
| API versioning | External webhooks (Pitfall 5) | Exempt Stripe/WhatsApp webhook URLs from versioning. |
| API versioning | Wrong Spring Boot version (Pitfall 11) | Use manual @RequestMapping, not spring.mvc.api-version (Boot 4 only). |
| Vendor dashboard | Timezone bugs (Pitfall 7) | Send ISO 8601 with offset. Display in vendor's local time. |

## Sources

- Codebase analysis: `OrderSseService.java`, `TenantContext.java`, `SecurityConfig.java`, `TenantSetLocalAspect.java`, `OrderEventPublisher.java`, `ShopPromotion.java`, `edge-go/cmd/edge/main.go`, `frontend/app/dashboard/orders/page.tsx`
- [Spring Security and WebSockets - Baeldung](https://www.baeldung.com/spring-security-websockets) -- STOMP security runs separate from HTTP filter chain
- [Spring Boot WebSocket STOMP guide - WebSocket.org](https://websocket.org/guides/frameworks/spring-boot/) -- Thread blocking in WebSocket handlers
- [WebSockets with Next.js Part 3 - Pedro Alonso](https://www.pedroalonso.net/blog/websockets-nextjs-part-3/) -- Connection singleton pattern, memory leak prevention
- [WebSocket Best Practices - WebSocket.org](https://websocket.org/guides/best-practices/) -- Reconnection, heartbeat, state reconciliation
- [SSE vs WebSockets vs Long Polling 2025 - DEV Community](https://dev.to/haraf/server-sent-events-sse-vs-websockets-vs-long-polling-whats-best-in-2025-5ep8) -- SSE buffering in production
- [SSE vs WebSockets - Ably](https://ably.com/blog/websockets-vs-sse) -- HTTP/1.1 6-connection limit for SSE
- [Spring Boot Built-in API Versioning - Piotr Minkowski](https://piotrminkowski.com/2025/12/01/spring-boot-built-in-api-versioning/) -- Spring Boot 4 only feature
- [API Versioning in Spring - Spring.io](https://spring.io/blog/2025/09/16/api-versioning-in-spring/) -- Official Spring versioning guidance
- [First-Class API Versioning in Spring Boot 4 - Dan Vega](https://www.danvega.dev/blog/spring-boot-4-api-versioning) -- Confirms Boot 4/Framework 7 requirement

---

*Pitfalls audit: 2026-04-07*
