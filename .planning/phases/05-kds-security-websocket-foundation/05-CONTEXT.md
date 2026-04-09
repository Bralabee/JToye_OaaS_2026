# Phase 5: KDS Security & WebSocket Foundation - Context

**Gathered:** 2026-04-08
**Status:** Ready for planning

<domain>
## Phase Boundary

Add Spring WebSocket/STOMP infrastructure with in-memory broker, tenant-aware channel security via TenantChannelInterceptor, and TenantContext propagation to WebSocket message handlers. Existing SSE stays for dashboard — WebSocket is additive for KDS bidirectional communication only.

</domain>

<decisions>
## Implementation Decisions

### Kitchen Auth Model
- **D-01:** Use same Keycloak vendor login for kitchen displays. JWT token passed as query parameter on WebSocket handshake (`/ws?token=<jwt>`). TenantChannelInterceptor extracts and validates JWT at CONNECT time, stores tenantId + shopIds in STOMP session attributes.
- **D-02:** No new auth flow or PIN-based auth. Kitchen staff uses their regular Keycloak credentials. PIN-based shared-screen auth deferred to v2.

### WebSocket Path Routing
- **D-03:** WebSocket connections go **directly to Spring Boot Core** at `/ws` endpoint, bypassing Go edge gateway entirely. Rationale: Go edge has no WebSocket upgrade support, and K8s Ingress handles WSS termination natively. No changes to edge-go needed.
- **D-04:** The `/ws` endpoint is NOT versioned (no `/api/v1/ws`). WebSocket is a transport, not a REST API. Exempt from versioning like `/health`.

### Topic Structure
- **D-05:** Channel naming: `/topic/kitchen/{tenantId}/{shopId}` — per-shop granularity. Each shop has its own topic so kitchen staff only see their shop's orders.
- **D-06:** TenantChannelInterceptor validates on SUBSCRIBE that the destination tenant/shop matches the JWT claims. Rejects subscriptions to other tenants' topics.

### WebSocket Configuration
- **D-07:** Use `spring-boot-starter-websocket` (version-managed by Boot 3.4.2). In-memory STOMP simple broker (no RabbitMQ STOMP relay needed yet — single Core replica). RabbitMQ relay is a config-only switch for future scaling.
- **D-08:** `WebSocketMessageBrokerConfigurer` with:
  - Application destination prefix: `/app`
  - Broker destinations: `/topic`
  - STOMP endpoint: `/ws` with `setAllowedOrigins("*")` for dev (tighten in prod)
- **D-09:** CORS for WebSocket handled by Spring's SockJS fallback or native WebSocket allowed origins. No SockJS needed — modern browsers all support native WebSocket.

### TenantContext Propagation
- **D-10:** TenantContext is ThreadLocal and does NOT propagate to WebSocket message handler threads. The TenantChannelInterceptor stores `tenantId` in STOMP session attributes at CONNECT time. Message handlers (`@MessageMapping`) read from session attributes and explicitly set TenantContext before any DB calls.
- **D-11:** Create a `TenantChannelInterceptor` (ExecutorChannelInterceptor for thread-safe cleanup) that:
  1. On CONNECT: Extract JWT from handshake query param, validate via Keycloak JWKS, extract tenantId + roles, store in session attributes
  2. On SUBSCRIBE: Validate destination topic matches session tenantId
  3. On SEND (from client): Set TenantContext from session attributes before handler invocation

### SSE Coexistence
- **D-12:** Existing `OrderSseService` stays untouched. Dashboard orders page continues using SSE. WebSocket is for KDS only. The tenant-blind SSE broadcast is a known issue but acceptable for the authenticated dashboard (vendor only sees their own orders via RLS on the API calls that populate the page).

### Claude's Discretion
- Whether to use `@SendTo` annotation vs `SimpMessagingTemplate.convertAndSend()` for server-to-client messages
- SecurityConfig updates for `/ws` endpoint (likely `.requestMatchers("/ws/**").permitAll()` since auth is handled at STOMP level, not HTTP level)
- Test structure for WebSocket security (likely integration test with `StompSession`)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Existing SSE (do NOT modify)
- `core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java` — Broadcasts to all tenants (known issue, kept for dashboard)
- `core-java/src/main/java/uk/jtoye/core/order/OrderController.java` — SSE endpoint at `/orders/stream`

### Tenant Context (critical for WebSocket propagation)
- `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java` — Sets TenantContext before processing events (pattern to replicate for WebSocket handlers)

### Security
- `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java` — SecurityFilterChain, needs `/ws/**` permitAll entry
- `core-java/src/main/java/uk/jtoye/core/security/JwtTenantFilter.java` — JWT extraction pattern (reuse logic for WebSocket handshake)

### Event Pipeline (Phase 6 will wire WebSocket broadcast here)
- `core-java/src/main/java/uk/jtoye/core/order/OrderEventPublisher.java` — Publishes to RabbitMQ
- `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeEvent.java` — Event payload with tenantId, orderId, statuses

### Research
- `.planning/research/ARCHITECTURE.md` — WebSocket bypasses Go Edge, direct to Core
- `.planning/research/PITFALLS.md` — Pitfalls #1 (cross-tenant broadcast), #2 (TenantContext null in STOMP), #4 (event pipeline divergence)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `JwtTenantFilter.java` — JWT parsing and tenant extraction logic. Reuse the JWT validation code for WebSocket handshake auth.
- `OrderStateChangeEvent.java` — Event payload already has `tenantId` field, usable for topic routing in Phase 6.
- `TenantContext` — ThreadLocal pattern. WebSocket handlers must explicitly set/clear this.

### Established Patterns
- Spring Security `SecurityFilterChain` — HTTP-level security. WebSocket security is separate (ChannelInterceptor).
- `@TenantSetLocal` aspect — not usable for WebSocket threads. Manual TenantContext.set() needed.

### Integration Points
- `SecurityConfig.java` — add `/ws/**` to permitAll (WebSocket auth handled at STOMP level)
- `build.gradle.kts` — add `spring-boot-starter-websocket` dependency
- Phase 6 will wire `OrderStateChangeListener` to also broadcast via WebSocket

</code_context>

<specifics>
## Specific Ideas

No specific requirements — standard Spring WebSocket/STOMP implementation with tenant security overlay.

</specifics>

<deferred>
## Deferred Ideas

- PIN-based kitchen authentication for shared screens (v2)
- RabbitMQ STOMP relay for horizontal scaling (config-only switch when needed)
- Fix SSE tenant-blind broadcast in OrderSseService (not blocking, dashboard uses RLS)

</deferred>

---

*Phase: 05-kds-security-websocket-foundation*
*Context gathered: 2026-04-08*
