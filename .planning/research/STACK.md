# Technology Stack — Tier 3 Enhancements

**Project:** J'Toye OaaS
**Researched:** 2026-04-07
**Scope:** Additions to existing stack for WebSocket kitchen displays, vendor dashboard UI, and API versioning

## New Backend Dependencies

### WebSocket / Real-Time

| Technology | Version | Purpose | Why | Confidence |
|------------|---------|---------|-----|------------|
| spring-boot-starter-websocket | 3.4.2 (managed) | WebSocket + STOMP protocol support | Native Spring integration, zero version conflicts, includes SockJS + STOMP message broker. Matches existing Spring Boot 3.4.2 version management. | HIGH |

**Gradle addition:**
```kotlin
implementation("org.springframework.boot:spring-boot-starter-websocket")
```

**What this provides:**
- `@EnableWebSocketMessageBroker` annotation
- STOMP over WebSocket with in-memory simple broker (`/topic`, `/queue`)
- `ChannelInterceptor` for JWT authentication on STOMP CONNECT frames
- `@MessageMapping` for server-side message handling
- `SimpMessagingTemplate` for pushing messages from services (e.g., order state changes)

**Why NOT an external broker (RabbitMQ STOMP plugin):** The project already has RabbitMQ for DLQ, but using it as a STOMP broker adds complexity for a kitchen display use case with low message volume. The in-memory simple broker handles hundreds of concurrent kitchen displays without issue. If the platform scales to thousands of tenants with concurrent kitchen sessions, swap to RabbitMQ STOMP relay later -- it is a config-only change, no code rewrite needed.

**Why NOT SSE (Server-Sent Events):** Kitchen displays need bidirectional communication -- orders flow to the display, but kitchen staff also acknowledge/update status back. SSE is unidirectional. STOMP over WebSocket handles both directions in a single connection.

### API Versioning

| Technology | Version | Purpose | Why | Confidence |
|------------|---------|---------|-----|------------|
| No new dependency | N/A | URL prefix versioning via `@RequestMapping` | Spring MVC natively supports path-based versioning. No library needed. Spring Framework 7 / Spring Boot 4 will add first-class API versioning, but that requires a major version upgrade not compatible with the current stack. | HIGH |

**Implementation approach:** Add `/api/v1/` prefix to all existing `@RequestMapping` annotations. Use a base controller class or `server.servlet.context-path` property for the common prefix.

**Why URL prefix over headers:** URL prefix (`/api/v1/`) is explicit, cache-friendly, easy to route at the Go edge gateway level, and requires no client-side header management. Header-based versioning (Accept header) is harder to test, debug, and cache.

**Why NOT upgrade to Spring Boot 4 for native versioning:** Spring Boot 4 requires Spring Framework 7 and likely JDK 25+. The project is constrained to JDK 21 and Gradle 8.10 which caps at Spring Boot 3.x. The URL prefix approach achieves the same result with zero dependency changes.

## New Frontend Dependencies

### WebSocket / STOMP Client

| Library | Version | Purpose | Why | Confidence |
|---------|---------|---------|-----|------------|
| @stomp/stompjs | ^7.3.0 | STOMP protocol client for browser | TypeScript-native, supports STOMP 1.0/1.1/1.2, active maintenance (last release Jan 2026), no framework dependency. Works directly with React hooks via useEffect. | HIGH |

**npm addition:**
```bash
npm install @stomp/stompjs
```

**Why NOT react-stomp-hooks:** Last published 2+ years ago (v3.0.1). The maintainer has not updated it for React 19. Writing a custom `useStompClient` hook with `@stomp/stompjs` directly is ~30 lines and gives full control over connection lifecycle, reconnection, and tenant-scoped subscriptions. Avoids a stale transitive dependency.

**Why NOT Socket.IO:** Socket.IO requires a Socket.IO server. The backend uses Spring WebSocket + STOMP, which speaks native WebSocket/STOMP protocol. Socket.IO's custom protocol would require a separate server process or a bridge. Direct STOMP client is the correct match.

**Why NOT native WebSocket API:** Raw WebSocket has no message routing, no subscription management, no reconnection logic. STOMP adds application-level framing (subscribe to `/topic/kitchen/{shopId}`, send to `/app/kitchen/ack`) which maps cleanly to the multi-tenant model. @stomp/stompjs also provides automatic reconnection with configurable backoff.

### Vendor Dashboard UI Components

| Library | Version | Purpose | Why | Confidence |
|---------|---------|---------|-----|------------|
| react-day-picker | ^9.6 | Date range picker for promotion scheduling | Already a dependency of the shadcn/ui date-picker pattern. Works with the existing date-fns ^4.1.0 in the project. Accessible (WCAG 2.1 AA). No additional date library needed. | HIGH |
| @radix-ui/react-switch | ^1.1 | Toggle switches for promotion active/inactive | Already using Radix UI throughout the project. Consistent with existing component library. | HIGH |
| @radix-ui/react-popover | ^1.1 | Popover for date picker, color picker, quick actions | Same rationale as above -- Radix UI consistency. | HIGH |
| recharts | ^3.8.1 (existing) | Dashboard charts for promotion performance | Already installed. No new dependency needed. | HIGH |
| lucide-react | ^0.562.0 (existing) | Icons for dashboard UI | Already installed. No new dependency needed. | HIGH |

**npm additions:**
```bash
npm install react-day-picker @radix-ui/react-switch @radix-ui/react-popover
```

**Why NOT a full dashboard template (TailAdmin, CoreUI, etc.):** The project already has a mature component system built on Radix UI + TailwindCSS + shadcn patterns. Introducing a dashboard framework would create two competing styling systems and component APIs. Build the dashboard pages using existing primitives.

**Why NOT FullCalendar for scheduling:** Promotions have simple start/end dates, not complex recurring events. react-day-picker with date-fns handles date range selection. FullCalendar (~180KB gzipped) is overkill for this use case.

## No New Infrastructure

| Concern | Decision | Rationale |
|---------|----------|-----------|
| Message broker for WebSocket | Use in-memory simple broker | Kitchen display traffic is low-volume, tenant-scoped. RabbitMQ relay is a config change if needed later. |
| Separate WebSocket server | No -- Spring Boot handles it | Single process reduces deployment complexity. WebSocket connections are long-lived but lightweight. |
| Redis for WebSocket sessions | Not needed initially | Single Spring Boot instance handles all WebSocket connections. If horizontally scaled, add Spring Session + Redis for sticky sessions or switch to RabbitMQ STOMP relay for broker-level fan-out. |
| New database tables | Flyway migration for kitchen display state | May need an `order_kitchen_status` or similar tracking table. Existing `orders` table tracks order state; kitchen display adds a view-layer concern. |

## Alternatives Considered

| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| WebSocket backend | Spring WebSocket + STOMP | Spring WebFlux + Reactive WebSocket | Project uses Spring MVC (servlet-based). Mixing reactive WebSocket with servlet stack adds complexity. STOMP over servlet WebSocket is the standard Spring MVC approach. |
| WebSocket backend | Spring WebSocket + STOMP | Dedicated Go WebSocket service | Fragments the backend, requires cross-service auth, adds deployment complexity. Spring Boot already supports WebSocket natively. |
| STOMP client | @stomp/stompjs 7.3.0 | react-stomp-hooks 3.0.1 | Stale (2+ years), no React 19 update, unnecessary abstraction |
| STOMP client | @stomp/stompjs 7.3.0 | Socket.IO client | Wrong protocol -- Socket.IO speaks its own protocol, not STOMP |
| Date picker | react-day-picker 9.x | react-datepicker | react-day-picker integrates with existing date-fns; react-datepicker bundles its own date library |
| API versioning | URL prefix /api/v1/ | Header-based (Accept header) | Harder to test, not cache-friendly, complicates Go edge routing |
| API versioning | Manual @RequestMapping | Spring Boot 4 native versioning | Requires JDK 25 + Spring Boot 4, incompatible with current constraints |
| Dashboard UI | Radix UI + TailwindCSS (existing) | TailAdmin / CoreUI template | Conflicts with existing component system, adds 2nd styling paradigm |

## Complete Installation Summary

### Backend (Gradle)
```kotlin
// Add to core-java/build.gradle.kts dependencies block
implementation("org.springframework.boot:spring-boot-starter-websocket")
```

### Frontend (npm)
```bash
cd frontend
npm install @stomp/stompjs react-day-picker @radix-ui/react-switch @radix-ui/react-popover
```

### No new infrastructure services, no new Docker containers, no new environment variables required for base functionality.

WebSocket endpoint configuration (e.g., allowed origins) should use the existing `CORS_ALLOWED_ORIGINS` environment variable pattern already established in the project.

## Sources

- [Spring Boot WebSocket STOMP Guide](https://websocket.org/guides/frameworks/spring-boot/) -- HIGH confidence
- [Spring Official: Token Authentication for WebSocket](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/authentication-token-based.html) -- HIGH confidence
- [Spring Official: Getting Started with STOMP WebSocket](https://spring.io/guides/gs/messaging-stomp-websocket/) -- HIGH confidence
- [@stomp/stompjs GitHub (v7.3.0)](https://github.com/stomp-js/stompjs/) -- HIGH confidence
- [react-stomp-hooks npm](https://www.npmjs.com/package/react-stomp-hooks) -- MEDIUM confidence (used for rejection rationale)
- [Spring Blog: API Versioning in Spring](https://spring.io/blog/2025/09/16/api-versioning-in-spring/) -- MEDIUM confidence (page failed to load fully, cross-referenced with Piotr Minkowski article)
- [Spring Boot Built-in API Versioning](https://piotrminkowski.com/2025/12/01/spring-boot-built-in-api-versioning/) -- MEDIUM confidence
- [Dan Vega: API Versioning in Spring Boot 4](https://www.danvega.dev/blog/spring-boot-4-api-versioning) -- MEDIUM confidence (used for future roadmap awareness)
- [React DayPicker](https://daypicker.dev/) -- HIGH confidence
- [Spring Security WebSockets (Baeldung)](https://www.baeldung.com/spring-security-websockets) -- MEDIUM confidence

---

*Stack research: 2026-04-07*
