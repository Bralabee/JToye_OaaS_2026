# Phase 11: STOMP Broker Relay for Horizontal Scale - Research

**Researched:** 2026-04-16
**Domain:** Spring WebSocket STOMP Broker Relay, RabbitMQ STOMP Plugin, Multi-Replica Broadcasting, Prometheus/Grafana Monitoring
**Confidence:** HIGH

## Summary

This phase replaces the in-memory `SimpleBroker` in `WebSocketConfig.java` with Spring's `StompBrokerRelay` backed by RabbitMQ's STOMP plugin, gated behind a `stomp.broker.mode` config property. The change allows multiple `core-java` replicas to share a single message broker, ensuring kitchen WebSocket broadcasts reach all clients regardless of which replica they connected to.

The existing codebase is well-positioned: `spring-boot-starter-webflux` is already a dependency (pulls in `reactor-netty`, required by `StompBrokerRelay`), RabbitMQ 3.12 is already running with Prometheus metrics on port 15692 being scraped, and the `@stomp/stompjs` frontend client connects via native WebSocket (no SockJS). The primary work is (1) conditional broker config, (2) enabling the `rabbitmq_stomp` plugin and exposing port 61613, (3) removing the hardcoded `container_name` from `core-java` to allow `--scale`, (4) writing a Playwright e2e that verifies cross-replica broadcast, and (5) adding a Prometheus alert rule + Grafana dashboard tile for STOMP health.

**Primary recommendation:** Use `@ConditionalOnProperty` or a simple `if` branch in `WebSocketConfig.configureMessageBroker()` to switch between `enableSimpleBroker` and `enableStompBrokerRelay` based on `stomp.broker.mode`. Enable `rabbitmq_stomp` via a mounted `enabled_plugins` file rather than a runtime command, so it survives container restarts.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| STMP-01 | `WebSocketConfig.java` reads `stomp.broker.mode` property; `in-memory` preserves `enableSimpleBroker`, `relay` calls `enableStompBrokerRelay` with env-wired host/port/credentials | Architecture Pattern 1 (conditional broker config), Code Examples section |
| STMP-02 | RabbitMQ STOMP plugin enabled in docker-compose and k8s, port 61613 exposed, relay credentials as k8s Secrets | Architecture Pattern 2 (RabbitMQ STOMP plugin enablement), k8s Secret additions |
| STMP-03 | Two-replica broadcast: `--scale core-java=2`, order state change on replica B received by kitchen client on replica A within 2s | Pitfall 1 (container_name blocks --scale), smoke-test pattern |
| STMP-04 | Playwright e2e in relay mode: open `/dashboard/kitchen`, trigger order via REST on different replica, assert WebSocket message within 2s | Pitfall 3 (e2e against multi-replica stack), existing kitchen e2e pattern |
| STMP-05 | Prometheus alert rule on STOMP lag > 5s, Grafana dashboard tile for STOMP connection count, wired through Phase 9 Alertmanager | Architecture Pattern 3 (monitoring), existing Prometheus scrape config |
</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| STOMP broker mode switching | API / Backend (core-java) | -- | Spring WebSocket config is server-side Java |
| RabbitMQ STOMP plugin | Infrastructure (Docker/k8s) | -- | Plugin enablement and port exposure are infra concerns |
| Multi-replica broadcast | API / Backend + Infrastructure | -- | Backend publishes via SimpMessagingTemplate; infra routes via RabbitMQ |
| Playwright cross-replica e2e | Frontend (test runner) | API / Backend | Playwright drives browser; test harness needs to target specific replica |
| Prometheus alert + Grafana tile | Infrastructure (monitoring) | -- | Alert rules and dashboards are monitoring infra |
| Kitchen WebSocket client | Browser / Client | -- | `@stomp/stompjs` connects to whichever replica the load balancer assigns; no client changes needed |

## Standard Stack

### Core (already in project -- no new libraries needed)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| spring-boot-starter-websocket | 3.4.2 (Boot BOM) | STOMP over WebSocket, `enableStompBrokerRelay` API | Already in `build.gradle.kts` [VERIFIED: codebase] |
| spring-boot-starter-webflux | 3.4.2 (Boot BOM) | Pulls `reactor-netty` required by StompBrokerRelay TCP connections | Already in `build.gradle.kts` [VERIFIED: codebase] |
| reactor-netty | managed by Boot 3.4.2 BOM | TCP client for STOMP relay connections to RabbitMQ | Transitive dep from webflux, no explicit add needed [VERIFIED: codebase] |
| RabbitMQ 3.12-management-alpine | 3.12.14 | Message broker with STOMP plugin bundled (not enabled by default) | Already in `docker-compose.full-stack.yml` [VERIFIED: codebase + container] |
| @stomp/stompjs | ^7.3.0 | Frontend STOMP client over native WebSocket | Already in `frontend/package.json` [VERIFIED: codebase] |
| Prometheus + Grafana | v2.48.0 / 10.2.2 | Metrics scraping and visualization | Already in `docker-compose.monitoring.yml` [VERIFIED: codebase] |

### Supporting (no additions needed)

The existing stack covers all requirements. No new libraries or services are introduced.

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| RabbitMQ STOMP relay | Redis pub/sub relay | Redis already in stack, but Spring has no built-in `StompBrokerRelay` for Redis -- would require custom implementation. RabbitMQ STOMP is the blessed Spring path [CITED: docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html] |
| `rabbitmq_stomp` plugin | `rabbitmq_web_stomp` plugin | web_stomp is for browser-direct STOMP-over-WebSocket; we need server-to-broker TCP STOMP relay, which is `rabbitmq_stomp` [CITED: rabbitmq.com/docs/stomp] |

**Installation:** No new packages to install. All dependencies already present.

## Architecture Patterns

### System Architecture Diagram

```
Browser (Kitchen Display)
    |
    | WebSocket (ws://host/ws?token=JWT)
    v
[Load Balancer / Docker network round-robin]
    |
    +----------+----------+
    |                     |
core-java:A          core-java:B
    |                     |
    | STOMP relay TCP     | STOMP relay TCP
    | (port 61613)        | (port 61613)
    +----------+----------+
               |
        [RabbitMQ STOMP]
          /amq/topic/*
               |
    +----------+----------+
    |                     |
core-java:A          core-java:B
(subscribers)        (subscribers)
    |                     |
    v                     v
Kitchen client A    Kitchen client B
(both receive the message)
```

**Data flow for cross-replica broadcast:**
1. REST API call hits replica B: `POST /api/v1/orders/{id}/status`
2. OrderService publishes `OrderStateChangeEvent` to RabbitMQ AMQP queue (existing flow)
3. `OrderStateChangeListener` on whichever replica consumes the AMQP message calls `simpMessagingTemplate.convertAndSend("/topic/kitchen/{tenant}/{shop}", event)`
4. Spring's `StompBrokerRelay` forwards the SEND frame to RabbitMQ STOMP on port 61613
5. RabbitMQ routes the message to all STOMP subscribers (both replicas)
6. Each replica's relay receives the message and pushes it to connected WebSocket clients

### Recommended Project Structure (changes only)

```
core-java/src/main/java/uk/jtoye/core/websocket/
    WebSocketConfig.java          # Modified: conditional broker mode
core-java/src/main/resources/
    application.yml               # Added: stomp.broker.* properties
    application-local.yml         # stomp.broker.mode=in-memory (default)
docker-compose.full-stack.yml     # Modified: rabbitmq STOMP plugin + port, core-java container_name removed
infra/rabbitmq/
    enabled_plugins               # New: plugin enablement file
infra/monitoring/prometheus/
    alerts.yml                    # Added: StompBrokerLag alert rule
infra/monitoring/grafana/dashboards/
    stomp-dashboard.json          # New: STOMP connection count tile
k8s/base/
    configmap.yaml                # Added: stomp.broker.* entries
    secrets-template.yaml         # Added: stomp relay credentials
    core-java-deployment.yaml     # Added: STOMP env vars
frontend/e2e/
    stomp-relay.spec.ts           # New: cross-replica broadcast e2e
scripts/
    smoke-test-stomp-relay.sh     # New: manual verification script
```

### Pattern 1: Conditional Broker Mode via Property

**What:** Switch between SimpleBroker and StompBrokerRelay based on `stomp.broker.mode` property
**When to use:** Always -- this is the core of STMP-01

```java
// Source: Spring Framework docs + project conventions
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${stomp.broker.mode:in-memory}")
    private String brokerMode;

    @Value("${stomp.broker.relay-host:localhost}")
    private String relayHost;

    @Value("${stomp.broker.relay-port:61613}")
    private int relayPort;

    @Value("${stomp.broker.client-login:guest}")
    private String clientLogin;

    @Value("${stomp.broker.client-passcode:guest}")
    private String clientPasscode;

    @Value("${stomp.broker.system-login:guest}")
    private String systemLogin;

    @Value("${stomp.broker.system-passcode:guest}")
    private String systemPasscode;

    private final TenantChannelInterceptor tenantChannelInterceptor;

    public WebSocketConfig(TenantChannelInterceptor tenantChannelInterceptor) {
        this.tenantChannelInterceptor = tenantChannelInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        if ("relay".equals(brokerMode)) {
            config.enableStompBrokerRelay("/topic", "/queue")
                  .setRelayHost(relayHost)
                  .setRelayPort(relayPort)
                  .setClientLogin(clientLogin)
                  .setClientPasscode(clientPasscode)
                  .setSystemLogin(systemLogin)
                  .setSystemPasscode(systemPasscode);
        } else {
            config.enableSimpleBroker("/topic");
        }
        config.setApplicationDestinationPrefixes("/app");
    }

    // registerStompEndpoints and configureClientInboundChannel unchanged
}
```

**Key details:**
- `in-memory` is the default (backward compatible for local dev) [VERIFIED: requirements STMP-01]
- `relay` mode adds `/queue` destination prefix (required by STOMP relay protocol) [CITED: docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html]
- System login/passcode is for the relay's own connection to the broker (heartbeats, internal subscriptions); client login/passcode is for per-user connections [CITED: docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html]

### Pattern 2: RabbitMQ STOMP Plugin Enablement

**What:** Enable `rabbitmq_stomp` plugin via mounted `enabled_plugins` file
**When to use:** Docker Compose and k8s deployments

```
# infra/rabbitmq/enabled_plugins
[rabbitmq_management,rabbitmq_prometheus,rabbitmq_stomp].
```

Mount in docker-compose:
```yaml
rabbitmq:
  image: rabbitmq:3.12-management-alpine
  volumes:
    - rabbitmq_data:/var/lib/rabbitmq
    - ./infra/rabbitmq/enabled_plugins:/etc/rabbitmq/enabled_plugins:ro
  ports:
    - "5672:5672"     # AMQP
    - "15672:15672"   # Management UI
    - "61613:61613"   # STOMP
```

[CITED: rabbitmq.com/docs/stomp] -- default STOMP port is 61613, default credentials are `guest`/`guest`.

### Pattern 3: Prometheus Alert Rule for STOMP Health

**What:** Alert on STOMP relay lag and dashboard for connection count
**When to use:** STMP-05

RabbitMQ exposes STOMP connection metrics via the Prometheus endpoint on port 15692. Key metrics:
- `rabbitmq_connections` (total connections, includes STOMP)
- `rabbitmq_channel_messages_published_total` (message throughput)
- `rabbitmq_queue_messages_unacked` (lag indicator)

```yaml
# Addition to infra/monitoring/prometheus/alerts.yml
- alert: StompBrokerLag
  expr: |
    rabbitmq_queue_messages_unacked{queue=~"stomp-subscription.*"} > 0
  for: 5s
  labels:
    severity: warning
    component: messaging
    service: rabbitmq
  annotations:
    summary: "STOMP broker message lag detected"
    description: "Unacknowledged STOMP messages for {{ $labels.queue }}: {{ $value }} (threshold: any > 0 for 5s)"
```

**Note on STOMP-specific metrics:** RabbitMQ's Prometheus plugin does not expose STOMP-protocol-specific metrics separately from AMQP metrics. STOMP connections appear as regular connections, and STOMP queues (auto-generated for subscriptions) appear as regular queues. The alert should target queues matching the `stomp-subscription-*` naming pattern that RabbitMQ creates for STOMP subscriptions. [ASSUMED -- exact queue naming needs verification at runtime]

### Anti-Patterns to Avoid

- **Running `rabbitmq-plugins enable` at container start via `command`:** This is fragile and runs on every restart. Use the `enabled_plugins` file mount instead -- it's the declarative, idempotent approach.
- **Using `rabbitmq_web_stomp` instead of `rabbitmq_stomp`:** `web_stomp` is for direct browser-to-RabbitMQ WebSocket connections. Spring's `StompBrokerRelay` uses TCP STOMP (port 61613), which requires the plain `rabbitmq_stomp` plugin.
- **Hardcoding relay credentials in Java source:** All credentials must come from environment variables / config properties, matching the project's existing pattern for RABBITMQ_USER/RABBITMQ_PASSWORD.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Cross-replica WebSocket broadcast | Custom Redis pub/sub fan-out | Spring `StompBrokerRelay` + RabbitMQ STOMP | Spring has first-class support; RabbitMQ handles routing, durability, and clustering |
| STOMP heartbeat management | Manual ping/pong frames | `StompBrokerRelay` built-in heartbeat config | Relay handles TCP heartbeats to broker automatically |
| WebSocket session stickiness | Load balancer sticky sessions | Message broker relay | Sticky sessions are fragile and defeat horizontal scaling purpose |
| Grafana dashboard JSON | Manual JSON editing | Grafana UI export-as-JSON | Easier to build visually then commit the exported JSON |

**Key insight:** The entire point of this phase is to NOT hand-roll message routing between replicas. Spring's `StompBrokerRelay` is the standard solution for exactly this problem -- it turns each replica into a relay that forwards STOMP frames to/from an external broker, so the broker handles fan-out.

## Common Pitfalls

### Pitfall 1: `container_name` Blocks Docker Compose `--scale`

**What goes wrong:** `docker compose up --scale core-java=2` fails because `container_name: jtoye-core-java` is set, and Docker requires unique container names.
**Why it happens:** The compose file was written for single-instance dev and hardcodes the name.
**How to avoid:** Remove `container_name: jtoye-core-java` from the core-java service in docker-compose. Other services that reference core-java by container name (e.g., Prometheus scrape targets using `jtoye-core-java:9090`) must switch to the Docker Compose service name `core-java` which resolves to all replicas.
**Warning signs:** `docker compose up --scale core-java=2` immediately errors with "Conflict. The container name /jtoye-core-java is already in use".
[VERIFIED: codebase -- `container_name: jtoye-core-java` at line 111 of docker-compose.full-stack.yml]

### Pitfall 2: Port Mapping Conflict with Multiple Replicas

**What goes wrong:** `--scale core-java=2` fails if the host port mapping `"9090:9090"` is still set, because both replicas try to bind to host port 9090.
**Why it happens:** Each replica needs its own host port (or no host port, relying on Docker network).
**How to avoid:** When scaling, either (a) remove the `ports` mapping and access core-java only through the Docker network (via the edge gateway), or (b) use a port range like `"9090-9091:9090"`. For the smoke test, option (a) is cleaner -- route all traffic through edge-go which already load-balances across core-java replicas via Docker DNS.
**Warning signs:** "Bind for 0.0.0.0:9090 failed: port is already allocated".

### Pitfall 3: Playwright E2E Against Multi-Replica Stack

**What goes wrong:** The e2e test cannot guarantee which replica handles the REST call vs. which replica the WebSocket connects to.
**Why it happens:** Docker Compose round-robin DNS means each connection may hit a different replica.
**How to avoid:** This is actually the desired behavior for STMP-04. The test should:
1. Open the kitchen page (WebSocket connects to replica A or B -- doesn't matter)
2. POST an order state change via the edge gateway (hits whichever replica)
3. Assert the WebSocket message arrives within 2 seconds
The test succeeds precisely because the broker relay ensures cross-replica delivery. No need to pin to specific replicas.
**Warning signs:** Test flaking due to timing -- use `page.waitForEvent()` with a 2-second timeout rather than polling.

### Pitfall 4: Missing `reactor-netty` on Classpath

**What goes wrong:** `StompBrokerRelay` throws `ClassNotFoundException` for `ReactorNettyTcpClient` at startup.
**Why it happens:** The relay uses Reactor Netty for TCP connections to the broker, and it's not pulled in by `spring-boot-starter-websocket` alone.
**How to avoid:** This project already has `spring-boot-starter-webflux` in `build.gradle.kts`, which transitively includes `reactor-netty`. No action needed.
[VERIFIED: codebase -- `spring-boot-starter-webflux` at line 48 of build.gradle.kts]

### Pitfall 5: STOMP Relay Credentials vs. AMQP Credentials

**What goes wrong:** The STOMP relay uses the same RabbitMQ user/password as AMQP, but they're wired differently -- AMQP goes through `spring.rabbitmq.*` properties, while STOMP relay credentials are set via `setClientLogin()`/`setSystemLogin()`.
**Why it happens:** Spring treats AMQP and STOMP as separate connection concerns.
**How to avoid:** Use the same `RABBITMQ_USER` / `RABBITMQ_PASSWORD` env vars for both, but wire them into both `spring.rabbitmq.*` (existing) and `stomp.broker.client-login` / `stomp.broker.system-login` (new). The `guest`/`guest` default only works for localhost connections in RabbitMQ -- when running in Docker, the relay connects over the Docker network (not localhost), so explicit credentials are required.
[CITED: rabbitmq.com/docs/stomp -- default credentials are guest/guest, restricted to localhost by default]

### Pitfall 6: Prometheus Scrape Target for Scaled Replicas

**What goes wrong:** Prometheus config has `targets: ['jtoye-core-java:9090']` which resolves to a single container. With `--scale`, the container name disappears.
**Why it happens:** Prometheus static_configs don't do DNS round-robin discovery.
**How to avoid:** For the smoke test, this is acceptable -- Prometheus will scrape whichever replica responds. For production (k8s), pod-level scraping via annotations is already configured in `core-java-deployment.yaml`. No change needed for the docker-compose smoke test; note it as a known limitation.

## Code Examples

### Application YAML Properties (addition to application.yml)

```yaml
# STOMP broker relay configuration
# Mode: "in-memory" (default, for local dev) or "relay" (for multi-replica)
stomp:
  broker:
    mode: ${STOMP_BROKER_MODE:in-memory}
    relay-host: ${STOMP_RELAY_HOST:rabbitmq}
    relay-port: ${STOMP_RELAY_PORT:61613}
    client-login: ${RABBITMQ_USER:guest}
    client-passcode: ${RABBITMQ_PASSWORD:guest}
    system-login: ${RABBITMQ_USER:guest}
    system-passcode: ${RABBITMQ_PASSWORD:guest}
```

### Docker Compose Changes (core-java service)

```yaml
core-java:
  build:
    context: .
    dockerfile: core-java/Dockerfile
  # container_name removed to allow --scale
  environment:
    # ... existing env vars ...
    STOMP_BROKER_MODE: ${STOMP_BROKER_MODE:-in-memory}
    STOMP_RELAY_HOST: rabbitmq
    STOMP_RELAY_PORT: 61613
```

### Docker Compose Changes (rabbitmq service)

```yaml
rabbitmq:
  image: rabbitmq:3.12-management-alpine
  container_name: jtoye-rabbitmq
  environment:
    RABBITMQ_DEFAULT_USER: ${RABBITMQ_DEFAULT_USER}
    RABBITMQ_DEFAULT_PASS: ${RABBITMQ_DEFAULT_PASS}
  ports:
    - "5672:5672"     # AMQP
    - "15672:15672"   # Management UI
    - "61613:61613"   # STOMP
  volumes:
    - rabbitmq_data:/var/lib/rabbitmq
    - ./infra/rabbitmq/enabled_plugins:/etc/rabbitmq/enabled_plugins:ro
```

### enabled_plugins File

```erlang
[rabbitmq_management,rabbitmq_management_agent,rabbitmq_prometheus,rabbitmq_stomp].
```

Note: `rabbitmq_management_agent` is implicitly enabled by the management image, but listing it explicitly is good practice. `rabbitmq_prometheus` is already enabled (`[E*]` in plugin list). [VERIFIED: container -- `rabbitmq_prometheus` is enabled, `rabbitmq_stomp` is not]

### K8s ConfigMap Additions

```yaml
# k8s/base/configmap.yaml
data:
  # ... existing entries ...
  stomp.broker.mode: "relay"
  stomp.broker.relay-host: "rabbitmq.jtoye-infrastructure.svc.cluster.local"
  stomp.broker.relay-port: "61613"
```

### K8s Secret Template Additions

```yaml
# k8s/base/secrets-template.yaml -- add to rabbitmq-credentials
stringData:
  username: "jtoye"
  password: "REPLACE_WITH_SECURE_PASSWORD"
  stomp-login: "jtoye"           # Same user for STOMP relay
  stomp-passcode: "REPLACE_WITH_SECURE_PASSWORD"  # Same password
```

### Smoke Test Script Pattern

```bash
#!/usr/bin/env bash
# scripts/smoke-test-stomp-relay.sh
set -euo pipefail

echo "=== STOMP Relay Smoke Test ==="

# 1. Start stack with relay mode and 2 replicas
STOMP_BROKER_MODE=relay docker compose -f docker-compose.full-stack.yml up -d --scale core-java=2

# 2. Wait for health
echo "Waiting for replicas..."
sleep 30  # or poll health endpoints

# 3. Create an order via edge gateway (load-balanced)
ORDER_RESPONSE=$(curl -s -X POST http://localhost:8089/api/v1/orders ...)

# 4. Trigger state change
curl -s -X PUT http://localhost:8089/api/v1/orders/${ORDER_ID}/status ...

# 5. Check RabbitMQ STOMP connections
STOMP_CONNS=$(curl -s http://localhost:15672/api/connections | jq '[.[] | select(.protocol == "STOMP")] | length')
echo "STOMP connections: $STOMP_CONNS"

# Capture result
echo "Smoke test complete. STOMP connections: $STOMP_CONNS" >> smoke-test-stomp.log
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `AbstractWebSocketMessageBrokerConfigurer` | `WebSocketMessageBrokerConfigurer` (interface) | Spring 5.0 | Project already uses the interface [VERIFIED: codebase] |
| SockJS fallback | Native WebSocket | Mainstream ~2020+ | Project correctly uses native WS, no SockJS [VERIFIED: codebase] |
| `ReactorNettyTcpClient` manual config | Auto-configured by `StompBrokerRelay` | Spring 5.x+ | Just add reactor-netty to classpath (already present) |

**Deprecated/outdated:**
- `AbstractWebSocketMessageBrokerConfigurer`: replaced by `WebSocketMessageBrokerConfigurer` interface (project already correct)
- SockJS: unnecessary for modern browsers (project already correct)

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | STOMP subscription queues in RabbitMQ follow `stomp-subscription-*` naming pattern | Pattern 3 (alert rule) | Alert rule won't match; would need different queue pattern -- low risk, easily fixable at runtime |
| A2 | `rabbitmq_queue_messages_unacked` is the right metric for STOMP lag detection | Pattern 3 (alert rule) | May need a different metric; RabbitMQ Prometheus metrics may not expose per-protocol lag -- discoverable at runtime |
| A3 | Docker Compose DNS round-robin suffices for load balancing between 2 core-java replicas in smoke test | Pitfall 3 | If DNS caching causes all requests to hit one replica, the test would still pass (broker relay handles it) but wouldn't prove cross-replica -- very low risk |

## Open Questions

1. **Exact STOMP queue naming pattern in RabbitMQ**
   - What we know: RabbitMQ creates auto-delete queues for STOMP subscriptions
   - What's unclear: The exact naming pattern (may be `stomp-subscription-<id>` or similar)
   - Recommendation: Start the stack in relay mode, subscribe from kitchen display, inspect queues via RabbitMQ management UI, then finalize alert rule expression

2. **Whether `rabbitmq_connections` metric distinguishes STOMP from AMQP connections**
   - What we know: RabbitMQ Prometheus plugin reports total connections
   - What's unclear: Whether a `protocol` label exists to filter STOMP-only connections
   - Recommendation: Check `http://localhost:15692/metrics` output after STOMP plugin is enabled; may need to use management API (`/api/connections` with `protocol` field) for the Grafana tile instead

3. **Port mapping strategy for multi-replica smoke test**
   - What we know: Host port 9090 can't be shared between replicas
   - What's unclear: Whether to remove host port entirely (access via edge) or use port range
   - Recommendation: Remove `ports` mapping from core-java when scaling; route all traffic through edge-go at port 8089

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| RabbitMQ | STOMP relay broker | Yes | 3.12.14 | -- (already running) |
| rabbitmq_stomp plugin | STMP-01/02 | Bundled but disabled | 3.12.14 | Enable via `enabled_plugins` file |
| reactor-netty | StompBrokerRelay TCP | Yes (via webflux) | Boot BOM managed | -- (already present) |
| Prometheus | STMP-05 alerts | Yes | v2.48.0 | -- (already running) |
| Grafana | STMP-05 dashboard | Yes | 10.2.2 | -- (already running) |
| Alertmanager | STMP-05 alert routing | Yes (Phase 9) | v0.27.0 | -- (Phase 9 dependency) |
| Playwright | STMP-04 e2e | Yes | 1.59.1 | -- (already configured) |
| Docker Compose | STMP-03 scaling | Yes | Present | -- |

**Missing dependencies with no fallback:** None.
**Missing dependencies with fallback:** None.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework (Java) | JUnit 5 + Spring Boot Test (existing) |
| Framework (E2E) | Playwright 1.59.1 (existing) |
| Config file (Java) | `core-java/build.gradle.kts` (existing) |
| Config file (E2E) | `frontend/playwright.config.ts` (existing) |
| Quick run command | `cd core-java && ./gradlew test --tests '*WebSocketConfig*' -x bootJar` |
| Full suite command | `cd core-java && ./gradlew test -x bootJar && cd ../frontend && npx playwright test` |

### Phase Requirements -> Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| STMP-01 | WebSocketConfig switches between simple and relay broker based on property | unit | `./gradlew test --tests '*WebSocketConfig*'` | Partial (existing test covers annotation/interceptor, needs relay-mode branch) |
| STMP-02 | RabbitMQ STOMP plugin enabled, port 61613 exposed | smoke | `scripts/smoke-test-stomp-relay.sh` (checks port + connection) | No -- Wave 0 |
| STMP-03 | Cross-replica broadcast within 2 seconds | smoke | `scripts/smoke-test-stomp-relay.sh` (manual capture) | No -- Wave 0 |
| STMP-04 | Playwright e2e cross-replica WebSocket delivery | e2e | `npx playwright test e2e/stomp-relay.spec.ts` | No -- Wave 0 |
| STMP-05 | Prometheus alert fires, Grafana tile renders | smoke / manual | `promtool check rules alerts.yml` + manual Grafana verification | No -- Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests '*WebSocketConfig*' -x bootJar`
- **Per wave merge:** Full Java test suite + Playwright
- **Phase gate:** Full suite green + smoke-test-stomp-relay.sh pass + Grafana tile screenshot

### Wave 0 Gaps
- [ ] `core-java/src/test/java/uk/jtoye/core/websocket/WebSocketConfigTest.java` -- extend with relay-mode branch test (STMP-01)
- [ ] `frontend/e2e/stomp-relay.spec.ts` -- new Playwright spec (STMP-04)
- [ ] `scripts/smoke-test-stomp-relay.sh` -- new smoke test script (STMP-02, STMP-03)
- [ ] `promtool check rules` validation for new alert rule (STMP-05)

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | Yes | JWT validation on STOMP CONNECT (existing `TenantChannelInterceptor`) |
| V3 Session Management | No | WebSocket sessions managed by Spring, no change |
| V4 Access Control | Yes | Tenant isolation on SUBSCRIBE (existing `TenantChannelInterceptor`) |
| V5 Input Validation | No | No new user input surfaces |
| V6 Cryptography | No | No new crypto; relay uses plain TCP in Docker network (TLS optional for prod) |

### Known Threat Patterns

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| STOMP relay credentials in plaintext env vars | Information Disclosure | k8s Secrets + env var injection (existing pattern) |
| Cross-tenant message leakage via broker | Elevation of Privilege | `TenantChannelInterceptor` validates SUBSCRIBE destination against session tenant (existing, unchanged) |
| RabbitMQ STOMP port exposed to host | Information Disclosure | Port 61613 bound to localhost or Docker network only; not exposed in k8s ingress |

## Project Constraints (from CLAUDE.md)

- **Tech stack:** Spring Boot 3.4.2, Next.js 16, Go 1.22, PostgreSQL 15 -- no new frameworks
- **Java version:** JDK 21 (Gradle 8.10)
- **Multi-tenancy:** All WebSocket features must respect RLS and TenantContext -- existing `TenantChannelInterceptor` handles this, unchanged by relay switch
- **Testing:** All new code requires tests -- unit test for config branch, e2e for cross-replica
- **Docker:** Always rebuild ALL containers after code changes before E2E testing
- **Git:** Feature branches only, no direct main commits

## Sources

### Primary (HIGH confidence)
- [Spring Framework - External Broker](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html) -- `enableStompBrokerRelay` API, required dependencies, configuration
- [RabbitMQ STOMP Plugin](https://www.rabbitmq.com/docs/stomp) -- plugin enablement, port 61613, default credentials, TCP listener config
- [RabbitMQ Prometheus Monitoring](https://www.rabbitmq.com/docs/prometheus) -- metrics endpoint on 15692, available metrics
- Codebase verification -- `WebSocketConfig.java`, `build.gradle.kts`, `docker-compose.full-stack.yml`, `k8s/` manifests, running RabbitMQ container plugin list

### Secondary (MEDIUM confidence)
- [Spring WebSocket STOMP guide](https://spring.io/guides/gs/messaging-stomp-websocket) -- basic STOMP configuration patterns
- RabbitMQ container plugin list output (`rabbitmq-plugins list`) -- confirmed `rabbitmq_stomp 3.12.14` bundled but disabled

### Tertiary (LOW confidence)
- STOMP subscription queue naming pattern (`stomp-subscription-*`) -- needs runtime verification

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- all libraries already in project, versions verified against codebase and container
- Architecture: HIGH -- Spring `StompBrokerRelay` is the documented, standard approach for this exact problem
- Pitfalls: HIGH -- `container_name` conflict verified in codebase, port conflict is well-known Docker behavior, reactor-netty presence verified

**Research date:** 2026-04-16
**Valid until:** 2026-05-16 (stable -- Spring Boot 3.4.2 LTS, RabbitMQ 3.12 stable)
