# Architecture Patterns

**Domain:** Multi-tenant food vendor SaaS — Tier 3 enhancements
**Researched:** 2026-04-07

## Recommended Architecture

Three new capabilities layered onto the existing 3-tier architecture (Next.js frontend, Go edge, Spring Boot core). Each feature integrates differently but all must respect the TenantContext/RLS boundary.

```
                        Browser (Vendor Dashboard / Kitchen Display)
                              |                    |
                     REST (HTTPS)          WebSocket (WSS)
                              |                    |
                     +--------+--------+   +-------+-------+
                     | Next.js 16      |   | Next.js 16    |
                     | Dashboard Pages |   | Kitchen Page  |
                     | (SSR + Client)  |   | (Client-only) |
                     +--------+--------+   +-------+-------+
                              |                    |
                    REST via Edge Go       WebSocket direct*
                              |                    |
                     +--------+--------+   +-------+-------+
                     | Spring Boot Core|   | Spring Boot   |
                     | REST /api/v1/*  |   | STOMP Broker  |
                     | (versioned)     |   | /ws endpoint  |
                     +--------+--------+   +-------+-------+
                              |                    |
                     +--------+--------+   +-------+-------+
                     | PostgreSQL RLS  |   | RabbitMQ      |
                     | (tenant-scoped) |   | (order events)|
                     +--------+--------+   +-------+-------+

* WebSocket bypasses Edge Go — Go edge is an HTTP reverse proxy,
  not a WebSocket proxy. Direct connection to Core on internal network.
```

### Component Boundaries

| Component | Responsibility | Communicates With | New/Modified |
|-----------|---------------|-------------------|--------------|
| **WebSocketConfig** (Core) | STOMP endpoint registration, SockJS fallback, broker relay config | Spring Security, RabbitMQ | NEW |
| **TenantChannelInterceptor** (Core) | Validates tenant_id on WS SUBSCRIBE, prevents cross-tenant leaks | TenantContext, JWT claims | NEW |
| **KitchenWebSocketController** (Core) | @MessageMapping handlers for kitchen actions (acknowledge, mark ready) | OrderService, OrderStateMachineService | NEW |
| **Kitchen Display Page** (Frontend) | Real-time order feed with status cards, sound alerts | Core WS endpoint via STOMP.js | NEW |
| **Vendor Dashboard Pages** (Frontend) | Announcements CRUD, promotions CRUD, scheduling calendar | Core REST API via api-client | NEW |
| **AnnouncementController** (Core) | REST CRUD for shop announcements | ShopService (announcements field exists on Shop entity) | NEW |
| **PromotionController** (Core) | REST CRUD for ShopPromotion entity | ShopPromotionRepository (entity exists) | NEW (controller/service layer) |
| **API Version Prefix** (Core) | /api/v1/ prefix on all controllers | All existing controllers, Edge Go routing | MODIFIED |
| **Edge Go Routing** (Edge) | Forward /api/v1/* to Core, strip or pass prefix | Core API | MODIFIED |

### Data Flow

**1. Kitchen Display — Real-time Order Feed**

```
Order state changes (existing flow):
  OrderService.updateStatus()
    -> publishes OrderStateChangeEvent to RabbitMQ (ORDER_EVENTS_EXCHANGE)
    -> OrderStateChangeListener receives from ORDER_EVENTS_QUEUE
    -> currently broadcasts to SSE emitters (OrderSseService)

Enhanced flow (add WebSocket broadcast):
  OrderStateChangeListener.handleOrderStateChange()
    -> sseService.broadcast(event)              [existing — keep for backward compat]
    -> kitchenWebSocketService.broadcast(event)  [NEW — tenant-scoped STOMP topic]
    -> SimpMessagingTemplate.convertAndSend(
         "/topic/kitchen/{tenantId}/{shopId}", event)

Kitchen Display (browser):
  1. Vendor opens /dashboard/kitchen (Next.js page)
  2. Page connects via SockJS to ws://core:9090/ws with JWT token
  3. STOMP CONNECT frame includes Authorization header
  4. TenantChannelInterceptor validates JWT, extracts tenant_id
  5. Client SUBSCRIBE to /topic/kitchen/{tenantId}/{shopId}
  6. Interceptor validates tenant_id matches JWT claim (CRITICAL — prevents cross-tenant)
  7. Order events stream in real-time as STOMP MESSAGE frames
  8. Kitchen staff clicks "Acknowledge" -> STOMP SEND to /app/kitchen/acknowledge
  9. KitchenWebSocketController triggers state transition CONFIRMED -> PREPARING
  10. State change publishes new event, loop continues
```

**2. Vendor Marketing Dashboard — Announcements and Promotions**

```
Vendor opens /dashboard/marketing (Next.js page):
  1. Page loads with tabs: Announcements | Promotions | Schedule
  2. GET /api/v1/shops/{shopId}/announcements -> ShopService returns Shop.announcements[]
  3. Vendor creates announcement -> POST /api/v1/shops/{shopId}/announcements
  4. ShopService updates Shop.announcements TEXT[] column
  5. Cache evict on "shops" cache (existing pattern)

Promotions tab:
  1. GET /api/v1/promotions?shopId={id} -> PromotionService returns ShopPromotion list
  2. Vendor creates promotion -> POST /api/v1/promotions (label, discountPercent, category, validFrom, validUntil)
  3. PromotionService persists to shop_promotions table (exists, has RLS)
  4. Active promotions auto-surface on public storefront via existing ShopConfigDto

Schedule tab:
  1. Calendar view of promotions by date range
  2. Drag-to-reschedule -> PUT /api/v1/promotions/{id} (update validFrom/validUntil)
  3. No new entities needed — scheduling is just date ranges on existing ShopPromotion
```

**3. API Versioning — URL Prefix Migration**

```
Current:  /orders, /shops, /products, /customers, ...
Target:   /api/v1/orders, /api/v1/shops, /api/v1/products, ...

Backend approach (Spring Boot 3.4 — manual prefix, not Spring 7 native):
  Option A: server.servlet.context-path=/api/v1 in application.yml
    - Simplest. Changes ALL endpoints including /health, /actuator.
    - Risk: breaks health checks, Swagger, Keycloak callback URLs.

  Option B: @RequestMapping base path per controller (tedious, error-prone)

  Option C (RECOMMENDED): Custom WebMvcConfigurer with path prefix
    - Add configurePathMatch() to prefix all @RestController mappings
    - Exclude /health, /actuator, /public from prefix
    - Edge Go updates route forwarding to include /api/v1/
    - Frontend api-client.ts updates NEXT_PUBLIC_API_URL base

Migration sequence:
  1. Add WebMvcConfigurer with /api/v1 prefix for all controllers
  2. Keep /public/* endpoints unprefixed (storefront, payments)
  3. Update Edge Go protected.POST routes to /api/v1/*
  4. Update frontend api-client.ts baseURL
  5. Update all test fixtures and MockMvc paths
  6. Add redirect from old paths -> new paths (temporary, remove in v2)
```

## Patterns to Follow

### Pattern 1: Tenant-Scoped WebSocket Topics
**What:** Every STOMP topic includes tenant_id in the path, validated by a channel interceptor.
**When:** Any real-time feature in a multi-tenant system.
**Why:** RLS protects database queries but does nothing for WebSocket subscriptions. Without topic-level tenant validation, Kitchen A could subscribe to Kitchen B's order feed.

```java
@Component
public class TenantChannelInterceptor implements ChannelInterceptor {
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            // Extract tenant from JWT (set during CONNECT)
            UUID sessionTenant = (UUID) accessor.getSessionAttributes().get("tenantId");
            // Validate destination contains correct tenant
            if (!destination.contains(sessionTenant.toString())) {
                throw new AccessDeniedException("Cross-tenant subscription blocked");
            }
        }
        return message;
    }
}
```

### Pattern 2: RabbitMQ as WebSocket Broker Relay
**What:** Configure Spring STOMP to use RabbitMQ as the message broker relay instead of the in-memory simple broker.
**When:** Multiple Core instances behind HPA (the project already has HPA configured for 3-10 replicas).
**Why:** With in-memory broker, WebSocket connections pinned to one instance miss events from other instances. RabbitMQ relay ensures all instances see all messages.

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Use RabbitMQ STOMP plugin as relay (port 61613)
        config.enableStompBrokerRelay("/topic", "/queue")
              .setRelayHost("rabbitmq")
              .setRelayPort(61613);
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
```

**Decision point:** For initial implementation, the simple in-memory broker is fine. Switch to RabbitMQ relay when scaling beyond 1 Core replica. The project's RabbitMQ is already deployed and available.

### Pattern 3: Additive API Versioning via WebMvcConfigurer
**What:** Apply /api/v1 prefix programmatically to all @RestController classes, without modifying individual @RequestMapping annotations.
**When:** Retrofitting versioning onto an existing API with many controllers.

```java
@Configuration
public class ApiVersionConfig implements WebMvcConfigurer {
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api/v1",
            HandlerTypePredicate.forAnnotation(RestController.class)
                .and(HandlerTypePredicate.forBasePackage("uk.jtoye.core"))
                // Exclude public endpoints that should remain unversioned
                .and(c -> !c.getPackageName().contains("storefront"))
        );
    }
}
```

### Pattern 4: Existing SSE + New WebSocket Coexistence
**What:** Keep the existing OrderSseService for the dashboard order list (lightweight, already works), add WebSocket only for kitchen display (needs bidirectional communication — acknowledge, mark ready).
**When:** Adding real-time features alongside existing SSE infrastructure.
**Why:** SSE is simpler and sufficient for one-way order status updates on the admin dashboard. Kitchen display needs two-way (staff actions flow back), so WebSocket is justified there. Ripping out SSE for WebSocket everywhere adds risk with no benefit.

## Anti-Patterns to Avoid

### Anti-Pattern 1: WebSocket Through the Go Edge Proxy
**What:** Routing WebSocket connections through the Go edge gateway.
**Why bad:** The Go edge is a simple HTTP reverse proxy using standard net/http. Adding WebSocket proxying requires connection hijacking, upgrade handling, and long-lived connection management. This adds significant complexity to a component designed for stateless request forwarding.
**Instead:** Kitchen display connects directly to Spring Boot Core's /ws endpoint. In production (K8s), the Ingress controller handles WebSocket upgrade natively. The Edge Go gateway continues handling REST traffic only.

### Anti-Pattern 2: Changing context-path for API Versioning
**What:** Setting `server.servlet.context-path=/api/v1` in application.yml.
**Why bad:** Moves ALL endpoints including /health, /actuator/prometheus, /public/*, Swagger UI. Breaks health checks in Docker, K8s liveness/readiness probes, Keycloak token endpoints, and the public storefront API. Requires updating every infrastructure configuration simultaneously.
**Instead:** Use WebMvcConfigurer.configurePathMatch() with a predicate that targets only the controllers that should be versioned.

### Anti-Pattern 3: Per-User WebSocket Topics for Kitchen
**What:** Creating /topic/kitchen/{userId} for each kitchen staff member.
**Why bad:** Kitchen displays are shared screens. Multiple staff watch the same display. Per-user topics mean each staff member gets a separate connection and separate event stream, wasting resources and creating inconsistent views.
**Instead:** Use per-shop topics: /topic/kitchen/{tenantId}/{shopId}. All staff watching the same shop see the same feed. Individual actions (acknowledge) are sent via /app/kitchen/acknowledge and attributed by the server using the session principal.

### Anti-Pattern 4: Building a Full Scheduling Engine for Promotions
**What:** Creating a separate scheduled_tasks table, cron-like scheduler, and activation/deactivation jobs for promotions.
**Why bad:** Over-engineering. ShopPromotion already has validFrom/validUntil fields. The query `WHERE active = true AND valid_from <= NOW() AND valid_until >= NOW()` handles scheduling without any background jobs.
**Instead:** Query-time filtering. The existing PublicStorefrontService already returns active promotions. Just ensure the query filters by date range.

## Scalability Considerations

| Concern | At 1 vendor (dev) | At 100 vendors | At 1000+ vendors |
|---------|-------------------|----------------|------------------|
| WebSocket connections | In-memory STOMP broker, single Core instance | In-memory still fine if sticky sessions | RabbitMQ STOMP relay mandatory, multiple Core instances |
| Kitchen display latency | Negligible | Negligible (topic isolation means no fan-out overhead) | Monitor RabbitMQ relay throughput, consider dedicated WS nodes |
| API versioning overhead | Zero (prefix rewrite is compile-time) | Zero | Zero |
| Marketing dashboard load | Standard REST, existing Redis cache handles it | Cache hit rate important for promotion queries | Consider separate read replica for analytics queries |
| Order event throughput | RabbitMQ easily handles tens/sec | RabbitMQ handles thousands/sec | Partition order events by tenant for horizontal scaling |

## Component Dependencies and Build Order

The three features have clear dependency relationships that dictate build order:

```
API Versioning (foundation — no dependencies on other features)
    |
    v
Vendor Marketing Dashboard (depends on versioned API paths)
    |
    v
WebSocket Kitchen Display (depends on versioned API, uses order event infrastructure)
```

### Suggested Build Order

**Phase 1: API Versioning (build first)**
- Rationale: Every other feature builds on top of versioned endpoints. Doing this first means all new controllers, tests, and frontend calls use the /api/v1/ prefix from day one. Retrofitting versioning after building new features doubles the migration work.
- Components: ApiVersionConfig, Edge Go route updates, frontend api-client.ts base URL, test fixture updates.
- Risk: High surface area (all controllers, all tests, Edge Go, frontend). But mechanical, not complex.

**Phase 2: Vendor Marketing Dashboard (build second)**
- Rationale: Uses standard REST patterns the codebase already follows (controller/service/repository). Backend entities (Shop.announcements, ShopPromotion) already exist. This is primarily a frontend build with thin backend controllers.
- Components: AnnouncementController (or extend ShopController), PromotionController + PromotionService, frontend /dashboard/marketing page with tabs.
- Risk: Low. Standard CRUD on existing entities.

**Phase 3: WebSocket Kitchen Display (build last)**
- Rationale: Most complex new infrastructure (WebSocket, STOMP, channel security, bidirectional messaging). Depends on order event pipeline being stable. Benefits from having API versioning and marketing dashboard already done (reduces concurrent changes).
- Components: spring-boot-starter-websocket dependency, WebSocketConfig, TenantChannelInterceptor, KitchenWebSocketController, KitchenWebSocketService, OrderStateChangeListener enhancement, frontend /dashboard/kitchen page with STOMP.js.
- Risk: Moderate. Multi-tenant WebSocket security is the novel piece. Existing SSE and RabbitMQ patterns provide a solid foundation.

**Phase 4: Test gap closure (can parallel with Phase 2 or 3)**
- PaymentController, PublicStorefrontController, security filters, ReviewService tests.
- Independent of feature work — can be done alongside any phase.

## Sources

- [Spring Boot WebSocket + STOMP Guide](https://websocket.org/guides/frameworks/spring-boot/) — STOMP broker patterns, scaling with RabbitMQ relay
- [Spring Security WebSocket Reference](https://docs.spring.io/spring-security/reference/servlet/integrations/websocket.html) — Channel security, subscription authorization
- [Baeldung: Spring Security + WebSockets](https://www.baeldung.com/spring-security-websockets) — Authentication on CONNECT, interceptor patterns
- [Spring.io: API Versioning in Spring (2025)](https://spring.io/blog/2025/09/16/api-versioning-in-spring/) — Native versioning in Spring 7, path prefix patterns
- [Piotr Minkowski: Spring Boot Built-in API Versioning](https://piotrminkowski.com/2025/12/01/spring-boot-built-in-api-versioning/) — configurePathMatch approach
- [Baeldung: Spring API Versioning](https://www.baeldung.com/spring-api-versioning) — URL prefix vs header vs param strategies
- Existing codebase: OrderSseService, OrderStateChangeListener, ShopPromotion entity, RabbitMQConfig, Edge Go main.go

---

*Architecture analysis: 2026-04-07*
