# Phase 5: KDS Security & WebSocket Foundation - Research

**Researched:** 2026-04-08
**Domain:** Spring Boot WebSocket/STOMP with multi-tenant JWT security
**Confidence:** HIGH

## Summary

This phase adds Spring WebSocket/STOMP infrastructure with tenant-isolated channel security to the existing Spring Boot 3.4.2 Core service. The critical challenge is that the existing tenant isolation model (ThreadLocal `TenantContext` populated by HTTP filter chain) does not apply to STOMP message handlers -- these run on a separate thread pool without any tenant context. A `ChannelInterceptor` must extract JWT claims at CONNECT time, store them in STOMP session attributes, and validate every SUBSCRIBE against those claims.

The existing `OrderSseService` demonstrates the anti-pattern to avoid: it broadcasts to ALL tenants indiscriminately via a flat `CopyOnWriteArrayList`. The WebSocket implementation must use tenant-scoped STOMP destinations (`/topic/kitchen/{tenantId}/{shopId}`) with interceptor-enforced subscription validation. The existing `JwtTenantFilter` provides reusable JWT parsing logic (claim preference order: `tenant_id`, `tenantId`, `tid`) that should be extracted into a shared utility.

**Primary recommendation:** Build a single `TenantChannelInterceptor` that handles CONNECT (JWT validation + session attribute storage), SUBSCRIBE (destination tenant matching), and SEND (TenantContext propagation) in one class. Test cross-tenant subscription rejection as the first integration test.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- D-01: Same Keycloak vendor login for kitchen displays. JWT token passed as query parameter on WebSocket handshake (`/ws?token=<jwt>`). TenantChannelInterceptor extracts and validates JWT at CONNECT time, stores tenantId + shopIds in STOMP session attributes.
- D-02: No new auth flow or PIN-based auth. Kitchen staff uses regular Keycloak credentials.
- D-03: WebSocket connections go directly to Spring Boot Core at `/ws` endpoint, bypassing Go edge gateway entirely.
- D-04: The `/ws` endpoint is NOT versioned. WebSocket is a transport, not a REST API.
- D-05: Channel naming: `/topic/kitchen/{tenantId}/{shopId}` -- per-shop granularity.
- D-06: TenantChannelInterceptor validates on SUBSCRIBE that the destination tenant/shop matches JWT claims.
- D-07: Use `spring-boot-starter-websocket` (version-managed by Boot 3.4.2). In-memory STOMP simple broker. No RabbitMQ STOMP relay needed yet.
- D-08: `WebSocketMessageBrokerConfigurer` with application prefix `/app`, broker prefix `/topic`, endpoint `/ws` with `setAllowedOrigins("*")` for dev.
- D-09: No SockJS needed -- modern browsers all support native WebSocket.
- D-10: TenantContext is ThreadLocal and does NOT propagate to WebSocket threads. TenantChannelInterceptor stores tenantId in STOMP session attributes at CONNECT time. Message handlers read from session attributes and explicitly set TenantContext.
- D-11: Create `WebSocketTenantInterceptor` (ChannelInterceptor) that handles CONNECT (JWT extract/validate/store), SUBSCRIBE (destination validation), and SEND (TenantContext set from session).
- D-12: Existing OrderSseService stays untouched. Dashboard uses SSE. WebSocket is for KDS only.

### Claude's Discretion
- Whether to use `@SendTo` annotation vs `SimpMessagingTemplate.convertAndSend()` for server-to-client messages
- SecurityConfig updates for `/ws` endpoint (likely `.requestMatchers("/ws/**").permitAll()` since auth handled at STOMP level)
- Test structure for WebSocket security (likely integration test with `StompSession`)

### Deferred Ideas (OUT OF SCOPE)
- PIN-based kitchen authentication for shared screens (v2)
- RabbitMQ STOMP relay for horizontal scaling
- Fix SSE tenant-blind broadcast in OrderSseService
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| KDS-01 | Spring WebSocket/STOMP configuration with in-memory broker. Existing SSE kept for dashboard; WebSocket added for bidirectional KDS use only. | WebSocketConfig class using `WebSocketMessageBrokerConfigurer`, in-memory simple broker at `/topic`, endpoint at `/ws`. Dependency: `spring-boot-starter-websocket` (managed by Boot 3.4.2 BOM). |
| KDS-02 | TenantChannelInterceptor validates JWT and scopes subscriptions to tenant's shops | ChannelInterceptor registered in `configureClientInboundChannel()`. CONNECT: extract JWT from handshake query param, validate via existing `JwtDecoder` bean, store tenantId in session attributes. SUBSCRIBE: parse destination path, match tenantId against session. |
| KDS-03 | TenantContext propagation from WebSocket session attributes to message handlers | On SEND frames: interceptor reads tenantId from `StompHeaderAccessor.getSessionAttributes()`, calls `TenantContext.set()`. `TenantSetLocalAspect` then propagates to RLS via `set_config()`. Must clear TenantContext after handler completes. |
</phase_requirements>

## Project Constraints (from CLAUDE.md)

- **Tech stack**: Spring Boot 3.4.2, Java 21 -- no upgrades
- **Multi-tenancy**: All new features must respect RLS and TenantContext
- **Testing**: All new code requires tests -- project standard is 310+ tests passing
- **Git**: Feature branches only, never commit to main directly
- **Docker**: Rebuild ALL containers after code changes before E2E testing
- **E2E**: Must verify with browser-level testing, not just health checks

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| spring-boot-starter-websocket | 3.4.2 (managed) | WebSocket + STOMP support | Boot-managed, includes spring-messaging + spring-websocket |
| spring-messaging (transitive) | 6.2.x (managed) | STOMP protocol, ChannelInterceptor, SimpMessagingTemplate | Core of Spring's STOMP abstraction |

### Supporting (already in project)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| spring-boot-starter-security | 3.4.2 | JwtDecoder bean for token validation | Reuse existing JwtDecoder in interceptor |
| spring-boot-starter-test | 3.4.2 | Test infrastructure | WebSocket integration tests |

### No New Dependencies Required Beyond spring-boot-starter-websocket

The `spring-boot-starter-websocket` starter brings in everything needed. No STOMP client library is needed for server-side (the client library `spring-messaging` STOMP client is included transitively for testing).

**Installation (build.gradle.kts):**
```kotlin
implementation("org.springframework.boot:spring-boot-starter-websocket")
```

No version needed -- managed by Spring Boot 3.4.2 BOM.

## Architecture Patterns

### Recommended Project Structure
```
core-java/src/main/java/uk/jtoye/core/
├── websocket/
│   ├── WebSocketConfig.java              # @EnableWebSocketMessageBroker configuration
│   ├── TenantChannelInterceptor.java     # JWT auth + tenant validation on STOMP frames
│   └── WebSocketSecurityHelper.java      # Shared JWT extraction logic (from JwtTenantFilter)
├── security/
│   ├── SecurityConfig.java               # MODIFIED: add /ws/** permitAll
│   ├── JwtTenantFilter.java              # UNCHANGED (HTTP filter chain)
│   └── TenantContext.java                # UNCHANGED (ThreadLocal)
└── order/
    ├── OrderSseService.java              # UNCHANGED (kept for dashboard)
    └── OrderStateChangeListener.java     # UNCHANGED in Phase 5 (Phase 6 adds WS broadcast)
```

### Pattern 1: JWT Authentication on STOMP CONNECT via Query Parameter

**What:** JWT is passed as a query parameter during WebSocket handshake (`/ws?token=<jwt>`). A `HandshakeInterceptor` extracts the token from the HTTP upgrade request and places it into WebSocket session attributes. Then the `ChannelInterceptor` validates it on CONNECT.

**Why query param not STOMP header:** The native browser WebSocket API (`new WebSocket(url)`) does not support custom HTTP headers. The STOMP protocol does allow custom headers on the CONNECT frame, but D-01 specifies query parameter. This also simplifies the client -- no STOMP-level header management needed.

**Example:**
```java
// Source: Spring Framework docs + D-01 locked decision
public class JwtHandshakeInterceptor implements HandshakeInterceptor {
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // Extract token from query parameter
        URI uri = request.getURI();
        String query = uri.getQuery();
        if (query != null && query.contains("token=")) {
            String token = extractTokenFromQuery(query);
            attributes.put("jwt_token", token);
        }
        return true; // allow handshake even without token (reject at STOMP CONNECT)
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {}

    private String extractTokenFromQuery(String query) {
        for (String param : query.split("&")) {
            if (param.startsWith("token=")) {
                return param.substring(6);
            }
        }
        return null;
    }
}
```

### Pattern 2: TenantChannelInterceptor -- Three Responsibilities

**What:** A single ChannelInterceptor handles all three STOMP security concerns.

**CONNECT phase:**
```java
// Source: Spring Framework token-based auth docs
@Component
public class TenantChannelInterceptor implements ChannelInterceptor {
    private final JwtDecoder jwtDecoder;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            handleConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            handleSubscribe(accessor);
        } else if (StompCommand.SEND.equals(accessor.getCommand())) {
            handleSend(accessor);
        }
        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttrs = accessor.getSessionAttributes();
        String token = (String) sessionAttrs.get("jwt_token");
        if (token == null) {
            throw new MessageDeliveryException("No JWT token provided");
        }
        // Reuse existing JwtDecoder bean (same Keycloak JWKS validation)
        Jwt jwt = jwtDecoder.decode(token);
        UUID tenantId = extractTenantId(jwt); // reuse JwtTenantFilter logic
        sessionAttrs.put("tenantId", tenantId);
        // Set STOMP user for Spring's user destination support
        accessor.setUser(new JwtAuthenticationToken(jwt));
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        UUID sessionTenant = (UUID) accessor.getSessionAttributes().get("tenantId");
        // Parse /topic/kitchen/{tenantId}/{shopId}
        if (destination != null && destination.startsWith("/topic/kitchen/")) {
            String[] parts = destination.split("/");
            // parts: ["", "topic", "kitchen", "{tenantId}", "{shopId}"]
            if (parts.length >= 4) {
                UUID destTenant = UUID.fromString(parts[3]);
                if (!destTenant.equals(sessionTenant)) {
                    throw new MessageDeliveryException("Cross-tenant subscription blocked");
                }
            }
        }
    }

    private void handleSend(StompHeaderAccessor accessor) {
        UUID tenantId = (UUID) accessor.getSessionAttributes().get("tenantId");
        if (tenantId != null) {
            TenantContext.set(tenantId);
        }
    }
}
```

### Pattern 3: TenantContext Propagation for @MessageMapping Handlers

**What:** WebSocket message handlers must explicitly set TenantContext before any DB operations and clear it after.

**Why:** The `TenantSetLocalAspect` triggers `@Before` on `@Transactional` methods and repository calls. It reads `TenantContext.get()`. If TenantContext is null (because WebSocket threads don't go through the HTTP filter chain), the aspect calls `resetTenant()` which sets `app.current_tenant_id TO DEFAULT` -- meaning RLS sees no tenant and returns nothing (or everything, depending on policy).

**The fix is two-layered:**
1. `TenantChannelInterceptor.handleSend()` sets TenantContext from session attributes BEFORE the handler runs
2. A custom `ExecutorChannelInterceptor.afterMessageHandled()` clears TenantContext AFTER the handler completes

```java
// afterMessageHandled ensures cleanup even if handler throws
@Override
public void afterMessageHandled(Message<?> message, MessageChannel channel,
                                 MessageHandler handler, Exception ex) {
    TenantContext.clear();
}
```

**Critical note:** The interceptor's `preSend()` runs on the SAME thread as the `@MessageMapping` handler for inbound client messages (they use `clientInboundChannel`). This means `TenantContext.set()` in `preSend()` IS visible to the handler. Verified: Spring's `ExecutorSubscribableChannel` dispatches to a task executor, but the ChannelInterceptor's `preSend` and the handler run sequentially on the executor thread.

### Pattern 4: WebSocketConfig with HandshakeInterceptor

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");           // In-memory broker
        config.setApplicationDestinationPrefixes("/app"); // Client SEND prefix
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .addInterceptors(new JwtHandshakeInterceptor())
                .setAllowedOriginPatterns("*"); // D-09: no SockJS
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(tenantChannelInterceptor);
    }
}
```

### Anti-Patterns to Avoid

- **Flat emitter list (OrderSseService pattern):** Never store WebSocket sessions without tenant association. The existing SSE broadcasts to ALL clients. WebSocket MUST use topic-scoped delivery.
- **Relying on TenantSetLocalAspect alone:** The aspect reads TenantContext ThreadLocal. In WebSocket threads, this is null unless explicitly set by the interceptor. Do not assume the aspect "just works."
- **Validating JWT in @MessageMapping handlers:** JWT validation must happen once at CONNECT time, not on every message. Session attributes carry the validated tenant for the session lifetime.
- **Using `accessor.getNativeHeader("Authorization")` for STOMP CONNECT auth:** D-01 specifies query parameter, not STOMP headers. The `HandshakeInterceptor` extracts from the HTTP upgrade request URL.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JWT validation | Custom token parsing | Existing `JwtDecoder` bean (NimbusJwtDecoder with Keycloak JWKS) | Handles key rotation, signature verification, claim parsing, expiry checks |
| Tenant ID extraction from JWT | New claim parsing code | Extract `JwtTenantFilter.extractTenant()` into a shared static utility | Same claim preference order (tenant_id, tenantId, tid) must be consistent |
| WebSocket session management | Custom session registry | Spring's built-in `SimpUserRegistry` | Tracks connected users and sessions automatically |
| STOMP message routing | Custom pub/sub | `SimpMessagingTemplate.convertAndSend()` | Handles topic matching, serialization, error handling |

**Key insight:** The JwtDecoder bean is already configured with Keycloak JWKS endpoint and 5-second timeouts. The TenantChannelInterceptor should inject this bean directly -- no new auth infrastructure needed.

## Common Pitfalls

### Pitfall 1: Cross-Tenant Subscription (CRITICAL)
**What goes wrong:** Without SUBSCRIBE validation, any authenticated user can subscribe to `/topic/kitchen/{otherTenantId}/{anyShopId}` and see another tenant's orders.
**Why it happens:** STOMP simple broker does not enforce any authorization on topic subscriptions by default.
**How to avoid:** The `handleSubscribe()` method in TenantChannelInterceptor MUST parse the destination path and compare the tenantId segment against the session's validated tenantId. Reject with `MessageDeliveryException`.
**Warning signs:** Integration test where Tenant A subscribes to Tenant B's topic and receives messages.

### Pitfall 2: TenantContext Null in WebSocket Handler Threads
**What goes wrong:** `@MessageMapping` handlers call `@Transactional` services. `TenantSetLocalAspect` reads `TenantContext.get()` which returns `Optional.empty()` because no HTTP filter set it. The aspect calls `resetTenant()`, setting `app.current_tenant_id TO DEFAULT`. RLS returns no rows.
**Why it happens:** HTTP filter chain (`JwtTenantFilter`) does not apply to STOMP message handling pipeline.
**How to avoid:** `TenantChannelInterceptor.handleSend()` sets `TenantContext.set(tenantId)` from session attributes BEFORE the handler executes. `afterMessageHandled()` clears it.
**Warning signs:** Database queries from WebSocket handlers returning empty results despite data existing.

### Pitfall 3: HandshakeInterceptor Not Registered
**What goes wrong:** The `JwtHandshakeInterceptor` is created but not added to the endpoint registration. The `TenantChannelInterceptor` then finds no `jwt_token` in session attributes at CONNECT time.
**Why it happens:** Spring's `registerStompEndpoints` requires explicit `.addInterceptors()` call.
**How to avoid:** Verify in WebSocketConfig that `registry.addEndpoint("/ws").addInterceptors(new JwtHandshakeInterceptor())` is present.

### Pitfall 4: SecurityConfig Blocks WebSocket Upgrade
**What goes wrong:** The current `SecurityConfig` has `.anyRequest().authenticated()`. The `/ws` endpoint handshake is an HTTP GET upgrade request. Without an explicit permitAll for `/ws/**`, Spring Security rejects the upgrade with 401 before STOMP auth can run.
**Why it happens:** WebSocket auth happens at the STOMP level (ChannelInterceptor), not the HTTP level. The HTTP upgrade must be allowed through.
**How to avoid:** Add `.requestMatchers("/ws/**").permitAll()` to SecurityConfig before `.anyRequest().authenticated()`.
**Warning signs:** 401 response on WebSocket connection attempt, no STOMP CONNECT frame ever reaches the interceptor.

### Pitfall 5: ShopId Validation Gap
**What goes wrong:** The interceptor validates tenantId in the subscription destination but not shopId. A user from Tenant A with 2 shops could subscribe to Tenant A's third shop (belonging to a different user in the same tenant) if shopId ownership is not validated.
**Why it happens:** D-06 says "validates tenant/shop matches JWT claims" but the JWT may only contain tenantId, not specific shopIds.
**How to avoid:** Two options: (a) trust RLS -- if the kitchen staff can see the shop via RLS, they can subscribe (simpler). (b) Query ShopRepository to verify the shopId belongs to the session's tenant before allowing subscription. Option (a) is sufficient for Phase 5 since topic subscription itself does not query the database -- it only routes messages. The actual order data visible is filtered by RLS when fetched.
**Recommendation:** Validate tenantId in the destination path. Do NOT validate shopId against the database on every SUBSCRIBE (adds latency). RLS on the order queries handles the data isolation.

## Code Examples

### WebSocketConfig (Complete)
```java
// Source: Spring Boot 3.4.2 WebSocket docs + locked decisions D-07/D-08/D-09
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final TenantChannelInterceptor tenantChannelInterceptor;

    public WebSocketConfig(TenantChannelInterceptor tenantChannelInterceptor) {
        this.tenantChannelInterceptor = tenantChannelInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .addInterceptors(new JwtHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
        // D-09: No .withSockJS()
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(tenantChannelInterceptor);
    }
}
```

### SecurityConfig Update
```java
// Add before .anyRequest().authenticated()
.requestMatchers("/ws/**").permitAll()
```

### TenantChannelInterceptor Skeleton
```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class TenantChannelInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;

    public TenantChannelInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        switch (accessor.getCommand()) {
            case CONNECT -> authenticateConnection(accessor);
            case SUBSCRIBE -> validateSubscription(accessor);
            case SEND -> propagateTenantContext(accessor);
            default -> {} // DISCONNECT, ACK, etc. -- no action needed
        }
        return message;
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel,
                                     boolean sent, Exception ex) {
        // Clean up TenantContext after message handling completes
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.SEND.equals(accessor.getCommand())) {
            TenantContext.clear();
        }
    }

    private void authenticateConnection(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttrs = accessor.getSessionAttributes();
        if (sessionAttrs == null) {
            throw new MessageDeliveryException("No session attributes");
        }
        String token = (String) sessionAttrs.get("jwt_token");
        if (token == null || token.isBlank()) {
            throw new MessageDeliveryException("Missing JWT token");
        }

        Jwt jwt = jwtDecoder.decode(token); // throws on invalid/expired
        UUID tenantId = extractTenantId(jwt);
        sessionAttrs.put("tenantId", tenantId);

        // Set authenticated user principal
        List<GrantedAuthority> authorities = extractAuthorities(jwt);
        accessor.setUser(new JwtAuthenticationToken(jwt, authorities));
    }

    private void validateSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) return;

        if (destination.startsWith("/topic/kitchen/")) {
            UUID sessionTenant = getSessionTenant(accessor);
            String[] parts = destination.split("/");
            if (parts.length >= 4) {
                try {
                    UUID destTenant = UUID.fromString(parts[3]);
                    if (!destTenant.equals(sessionTenant)) {
                        throw new MessageDeliveryException(
                            "Cross-tenant subscription denied");
                    }
                } catch (IllegalArgumentException e) {
                    throw new MessageDeliveryException("Invalid tenant ID in destination");
                }
            }
        }
    }

    private void propagateTenantContext(StompHeaderAccessor accessor) {
        UUID tenantId = getSessionTenant(accessor);
        if (tenantId != null) {
            TenantContext.set(tenantId);
        }
    }

    private UUID getSessionTenant(StompHeaderAccessor accessor) {
        Map<String, Object> attrs = accessor.getSessionAttributes();
        return attrs != null ? (UUID) attrs.get("tenantId") : null;
    }

    // Reuse same claim preference as JwtTenantFilter
    private UUID extractTenantId(Jwt jwt) {
        for (String claim : new String[]{"tenant_id", "tenantId", "tid"}) {
            Object v = jwt.getClaim(claim);
            if (v instanceof String s) {
                try { return UUID.fromString(s); }
                catch (IllegalArgumentException ignore) {}
            }
        }
        throw new MessageDeliveryException("JWT missing tenant claim");
    }
}
```

### Integration Test Pattern
```java
// Test cross-tenant subscription rejection
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantChannelInterceptorIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void shouldRejectCrossTenantSubscription() {
        // Connect as Tenant A
        StompSession session = connectWithToken(tenantAToken);

        // Try to subscribe to Tenant B's topic
        assertThrows(MessageDeliveryException.class, () ->
            session.subscribe("/topic/kitchen/" + tenantBId + "/" + shopId,
                new StompFrameHandler() { /* ... */ })
        );
    }
}
```

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (via spring-boot-starter-test) |
| Config file | `core-java/build.gradle.kts` (JUnitPlatform config at line 88) |
| Quick run command | `cd core-java && ./gradlew test --tests "*WebSocket*" --tests "*ChannelInterceptor*"` |
| Full suite command | `cd core-java && ./gradlew test` |

### Phase Requirements to Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| KDS-01 | WebSocket STOMP config with in-memory broker, endpoint at /ws | unit | `./gradlew test --tests "WebSocketConfigTest"` | Wave 0 |
| KDS-02a | JWT validation on CONNECT -- valid token accepted | unit | `./gradlew test --tests "TenantChannelInterceptorTest.shouldAuthenticateValidJwt"` | Wave 0 |
| KDS-02b | JWT validation on CONNECT -- missing/invalid token rejected | unit | `./gradlew test --tests "TenantChannelInterceptorTest.shouldRejectMissingToken"` | Wave 0 |
| KDS-02c | SUBSCRIBE to own tenant's topic -- allowed | unit | `./gradlew test --tests "TenantChannelInterceptorTest.shouldAllowOwnTenantSubscription"` | Wave 0 |
| KDS-02d | SUBSCRIBE to other tenant's topic -- BLOCKED | unit | `./gradlew test --tests "TenantChannelInterceptorTest.shouldBlockCrossTenantSubscription"` | Wave 0 |
| KDS-03a | TenantContext set from session attributes on SEND | unit | `./gradlew test --tests "TenantChannelInterceptorTest.shouldSetTenantContextOnSend"` | Wave 0 |
| KDS-03b | TenantContext cleared after message handling | unit | `./gradlew test --tests "TenantChannelInterceptorTest.shouldClearTenantContextAfterSend"` | Wave 0 |

### Sampling Rate
- **Per task commit:** `cd core-java && ./gradlew test --tests "*WebSocket*" --tests "*ChannelInterceptor*" --tests "*Handshake*"`
- **Per wave merge:** `cd core-java && ./gradlew test`
- **Phase gate:** Full suite green before `/gsd:verify-work`

### Wave 0 Gaps
- [ ] `core-java/src/test/java/uk/jtoye/core/websocket/WebSocketConfigTest.java` -- covers KDS-01
- [ ] `core-java/src/test/java/uk/jtoye/core/websocket/TenantChannelInterceptorTest.java` -- covers KDS-02, KDS-03
- [ ] `core-java/src/test/java/uk/jtoye/core/websocket/JwtHandshakeInterceptorTest.java` -- covers JWT extraction from query param

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| SockJS fallback for old browsers | Native WebSocket everywhere | ~2020 | No SockJS needed (D-09 aligns) |
| Custom JWT parsing per endpoint | Reuse `JwtDecoder` bean | Spring Boot 3.x | One validation path for HTTP and WebSocket |
| `@MessageMapping` with `@SendTo` | `SimpMessagingTemplate.convertAndSend()` | N/A (both valid) | Template gives more control over destination construction |

**Recommendation on `@SendTo` vs `SimpMessagingTemplate`:** Use `SimpMessagingTemplate.convertAndSend()` for Phase 6 (when OrderStateChangeListener broadcasts to WebSocket topics). `@SendTo` is fine for request-reply patterns in `@MessageMapping` handlers (e.g., kitchen acknowledge action). For this phase (infrastructure only), no handlers are needed yet -- Phase 6/7 adds them.

## Open Questions

1. **Shop ownership validation on SUBSCRIBE**
   - What we know: JWT contains tenantId. Subscription path contains tenantId + shopId. TenantId is validated by the interceptor.
   - What's unclear: Whether the JWT also contains shopIds or whether shopId validation requires a DB query.
   - Recommendation: For Phase 5, validate tenantId only. ShopId ownership is enforced by RLS when actual order data is queried (Phase 6/7). This avoids adding DB calls to the subscription path.

2. **TenantContext cleanup timing**
   - What we know: `afterSendCompletion` on `ChannelInterceptor` fires after message delivery.
   - What's unclear: Whether `afterSendCompletion` runs on the same thread as the handler (needed for TenantContext cleanup to work).
   - Recommendation: Use `ExecutorChannelInterceptor.afterMessageHandled()` instead if `afterSendCompletion` doesn't guarantee same-thread execution. Test this explicitly.

3. **JWT expiry during long-lived WebSocket sessions**
   - What we know: Kitchen displays stay open for hours. JWT validated once at CONNECT.
   - What's unclear: Whether expired JWT sessions should be forcibly disconnected.
   - Recommendation: For Phase 5, do not implement session expiry enforcement. The JWT is validated at CONNECT; if it expires during the session, the connection stays live. Phase 7 (UI) can add periodic re-auth if needed. This matches D-01 which only specifies CONNECT-time validation.

## Sources

### Primary (HIGH confidence)
- [Spring Framework Token-Based Auth for STOMP](https://docs.enterprise.spring.io/spring-framework/reference/web/websocket/stomp/authentication-token-based.html) -- ChannelInterceptor CONNECT pattern, @Order precedence
- [Spring Security WebSocket Reference (Baeldung)](https://www.baeldung.com/spring-security-websockets) -- Channel security separate from HTTP filter chain
- Codebase: `JwtTenantFilter.java` -- JWT claim extraction logic (tenant_id, tenantId, tid)
- Codebase: `TenantContext.java` -- ThreadLocal<UUID> pattern
- Codebase: `TenantSetLocalAspect.java` -- RLS propagation via set_config(), triggers on @Transactional
- Codebase: `OrderStateChangeListener.java` -- TenantContext manual set pattern (lines 47-54)
- Codebase: `SecurityConfig.java` -- Current filter chain, needs /ws/** permitAll
- Codebase: `OrderSseService.java` -- Anti-pattern: broadcasts to all tenants

### Secondary (MEDIUM confidence)
- [Spring Boot WebSocket STOMP Guide (websocket.org)](https://websocket.org/guides/frameworks/spring-boot/) -- Configuration patterns, scaling considerations
- [Spring Boot 3 JWT WebSocket Auth (Medium)](https://medium.com/@poojithairosha/spring-boot-3-authenticate-websocket-connections-with-jwt-tokens-2b4ff60532b6) -- HandshakeInterceptor + ChannelInterceptor combo pattern
- [Overcome WebSocket Auth Issues (Softbinator)](https://blog.softbinator.com/overcome-websocket-authentication-issues-stomp/) -- Query param token approach

### Tertiary (LOW confidence)
- JWT expiry behavior during long-lived WebSocket sessions -- no authoritative Spring docs found on automatic session termination

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- spring-boot-starter-websocket is the canonical dependency, version managed by BOM
- Architecture: HIGH -- ChannelInterceptor pattern is the documented Spring approach, verified against official docs and codebase patterns
- Pitfalls: HIGH -- all pitfalls verified against actual codebase (OrderSseService, TenantContext, SecurityConfig)

**Research date:** 2026-04-08
**Valid until:** 2026-05-08 (stable -- Spring Boot 3.4.x WebSocket API is mature)
