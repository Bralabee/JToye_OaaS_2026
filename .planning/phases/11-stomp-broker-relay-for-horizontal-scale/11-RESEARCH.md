# Phase 11: STOMP Broker Relay for Horizontal Scale - Research

**Researched:** 2026-04-16 (re-research)
**Domain:** Spring WebSocket STOMP Broker Relay, RabbitMQ STOMP Plugin, Multi-Replica Broadcasting, Prometheus/Grafana Monitoring
**Confidence:** HIGH

## Summary

This phase replaces the in-memory `SimpleBroker` in `WebSocketConfig.java` with Spring's `StompBrokerRelay` backed by RabbitMQ's `rabbitmq_stomp` plugin, gated behind a `stomp.broker.mode` config property. The change allows multiple `core-java` replicas to share a single message broker so kitchen WebSocket broadcasts reach all connected clients regardless of which replica they connected to.

The existing codebase is well-positioned: `spring-boot-starter-webflux` is already a dependency (pulls in `reactor-netty`, required by `StompBrokerRelay` for TCP connections to RabbitMQ), RabbitMQ 3.12 is already running in docker-compose, and the `@stomp/stompjs` ^7.3.0 frontend client connects via native WebSocket without SockJS. The `OrderStateChangeListener` already publishes to `/topic/kitchen/{tenantId}/{shopId}` via `SimpMessagingTemplate` -- in relay mode, Spring transparently forwards these SEND frames to RabbitMQ STOMP on port 61613, and RabbitMQ fans them out to all subscribing replicas. No changes to the listener or frontend client are needed.

The primary work is: (1) conditional broker config with `@Value`-injected properties, (2) enabling `rabbitmq_stomp` via a mounted `enabled_plugins` file and exposing port 61613, (3) removing the hardcoded `container_name` from core-java to allow `--scale`, (4) writing a Playwright e2e that verifies cross-replica broadcast under relay mode, and (5) adding a Prometheus alert rule and Grafana dashboard tile for STOMP relay health, wired through the Phase 9 Alertmanager.

**Primary recommendation:** Use a simple `if ("relay".equals(brokerMode))` branch in `WebSocketConfig.configureMessageBroker()` to switch between `enableSimpleBroker` and `enableStompBrokerRelay`. Enable `rabbitmq_stomp` via a mounted `enabled_plugins` file (declarative, survives restarts) rather than a runtime `rabbitmq-plugins enable` command.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| STMP-01 | `WebSocketConfig.java` reads `stomp.broker.mode` property; `in-memory` preserves `enableSimpleBroker`, `relay` calls `enableStompBrokerRelay` with env-wired host/port/credentials | Architecture Pattern 1 (conditional broker config), Code Examples section |
| STMP-02 | RabbitMQ STOMP plugin enabled in docker-compose and k8s, port 61613 exposed, relay credentials as k8s Secrets | Architecture Pattern 2 (RabbitMQ STOMP plugin enablement), Code Examples (k8s additions) |
| STMP-03 | Two-replica broadcast: `--scale core-java=2`, order state change on replica B received by kitchen client on replica A within 2s | Pitfall 1 (container_name blocks --scale), Pitfall 2 (port conflict), smoke-test pattern |
| STMP-04 | Playwright e2e in relay mode: open `/dashboard/kitchen`, trigger order via REST on different replica, assert WebSocket message within 2s | Pitfall 3 (e2e against multi-replica stack), existing kitchen e2e pattern in `frontend/e2e/kitchen-flow.spec.ts` |
| STMP-05 | Prometheus alert rule on STOMP exchange lag > 5s, Grafana dashboard tile for STOMP connection count, wired through Phase 9 Alertmanager | Architecture Pattern 3 (monitoring), Open Question 1 (STOMP-specific metrics) |
</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| STOMP broker mode switching | API / Backend (core-java) | -- | Spring WebSocket config is server-side Java; `@Value` injection reads from `application.yml` |
| RabbitMQ STOMP plugin + port 61613 | Infrastructure (Docker/k8s) | -- | Plugin enablement and port exposure are infra concerns |
| Multi-replica broadcast | API / Backend + Infrastructure | -- | Backend publishes via `SimpMessagingTemplate`; RabbitMQ STOMP plugin routes between relay connections |
| Playwright cross-replica e2e | Frontend (test runner) | API / Backend | Playwright drives browser; REST calls go through edge-go which load-balances across replicas |
| Prometheus alert + Grafana tile | Infrastructure (monitoring) | -- | Alert rules in `alerts.yml`, dashboard JSON in Grafana provisioning |
| Kitchen WebSocket client | Browser / Client | -- | `@stomp/stompjs` connects to whichever replica the load balancer assigns; no client changes needed |

## Standard Stack

### Core (already in project -- no new libraries needed)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| spring-boot-starter-websocket | 3.4.2 (Boot BOM) | STOMP over WebSocket, `enableStompBrokerRelay` API | Already in `build.gradle.kts` line 35 [VERIFIED: codebase] |
| spring-boot-starter-webflux | 3.4.2 (Boot BOM) | Pulls `reactor-netty` required by `StompBrokerRelay` TCP connections | Already in `build.gradle.kts` line 49 [VERIFIED: codebase] |
| reactor-netty | managed by Boot 3.4.2 BOM | TCP client for STOMP relay connections to RabbitMQ STOMP port 61613 | Transitive dep from webflux, no explicit add needed [VERIFIED: codebase] |
| RabbitMQ 3.12-management-alpine | 3.12.x | Message broker; STOMP plugin bundled but not enabled by default | Already in `docker-compose.full-stack.yml` line 88 [VERIFIED: codebase] |
| @stomp/stompjs | ^7.3.0 | Frontend STOMP client over native WebSocket | Already in `frontend/package.json` [VERIFIED: codebase] |
| Prometheus | v2.48.0 | Metrics scraping; already scrapes RabbitMQ at `jtoye-rabbitmq:15692` | Already in `docker-compose.monitoring.yml` [VERIFIED: codebase] |
| Grafana | 10.2.2 | Dashboard visualization; provisioning directory exists at `infra/monitoring/grafana/dashboards/` | Already in `docker-compose.monitoring.yml` [VERIFIED: codebase] |

### Supporting (no additions needed)

The existing stack covers all requirements. No new libraries or services are introduced.

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| RabbitMQ STOMP relay | Redis pub/sub relay | Redis already in stack, but Spring has no built-in `StompBrokerRelay` for Redis -- would require custom `AbstractBrokerMessageHandler` implementation. RabbitMQ STOMP is the blessed Spring path [CITED: docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html] |
| `rabbitmq_stomp` plugin | `rabbitmq_web_stomp` plugin | `web_stomp` is for direct browser-to-RabbitMQ WebSocket connections. Spring's `StompBrokerRelay` uses TCP STOMP (port 61613), which requires the plain `rabbitmq_stomp` plugin [CITED: rabbitmq.com/docs/stomp -- "When no configuration is specified the STOMP Adapter will listen on all interfaces on port 61613"] |

**Installation:** No new packages to install. All dependencies already present.

## Architecture Patterns

### System Architecture Diagram

```
Browser (Kitchen Display)
    |
    | WebSocket (ws://host/ws?token=JWT)
    v
[edge-go / Docker DNS round-robin]
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
          (port 61613)
               |
    +----------+----------+
    |                     |
core-java:A          core-java:B
(relay receives)    (relay receives)
    |                     |
    v                     v
Kitchen client A    Kitchen client B
(both receive the message)
```

**Data flow for cross-replica broadcast:**
1. REST API call hits replica B: `POST /api/v1/orders/{id}/status`
2. `OrderService` publishes `OrderStateChangeEvent` to RabbitMQ AMQP exchange `order.events` via `RabbitTemplate` (existing flow, unchanged)
3. `OrderStateChangeListener` on whichever replica consumes the AMQP message calls `simpMessagingTemplate.convertAndSend("/topic/kitchen/{tenant}/{shop}", event)` (existing code, line 55 of `OrderStateChangeListener.java`)
4. In relay mode, Spring's `StompBrokerRelay` forwards the SEND frame to RabbitMQ STOMP on port 61613
5. RabbitMQ routes the message to all STOMP subscribers on that topic (both replicas have subscribers if clients are connected)
6. Each replica's relay receives the message and pushes it to its connected WebSocket clients

**Critical distinction:** The AMQP path (port 5672) and STOMP path (port 61613) are separate. AMQP delivers the event to exactly one consumer (the `@RabbitListener`). That consumer then publishes to the STOMP topic, and the STOMP broker fans out to all subscribers across all replicas. This two-hop design is intentional -- it keeps the existing event pipeline intact.

### Recommended Project Structure (changes only)

```
core-java/src/main/java/uk/jtoye/core/websocket/
    WebSocketConfig.java          # Modified: conditional broker mode
core-java/src/main/resources/
    application.yml               # Added: stomp.broker.* properties
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

**What:** Switch between `SimpleBroker` and `StompBrokerRelay` based on `stomp.broker.mode` property
**When to use:** Always -- this is the core of STMP-01

```java
// Source: docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html
// + docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay-configure.html
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
            log.info("STOMP broker relay configured: {}:{}", relayHost, relayPort);
        } else {
            config.enableSimpleBroker("/topic");
            log.info("In-memory simple broker configured");
        }
        config.setApplicationDestinationPrefixes("/app");
    }

    // registerStompEndpoints and configureClientInboundChannel unchanged
}
```

**Key details:**
- `in-memory` is the default, preserving backward compatibility for local dev [VERIFIED: requirements STMP-01]
- `relay` mode adds `/queue` destination prefix alongside `/topic` (standard for full STOMP brokers) [CITED: docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html]
- `systemLogin`/`systemPasscode`: credentials for the relay's own "system" TCP connection used for server-originated messages and heartbeats. Defaults to `guest`/`guest` [CITED: docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay-configure.html]
- `clientLogin`/`clientPasscode`: credentials set on CONNECT frames for per-client connections. Client-provided values are overridden by the relay [CITED: docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay-configure.html]
- The relay maintains a single system TCP connection plus one TCP connection per connected WebSocket client [CITED: docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay-configure.html]
- Automatic reconnection on broker disconnect with 5-second retry interval [CITED: docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay-configure.html]

### Pattern 2: RabbitMQ STOMP Plugin Enablement

**What:** Enable `rabbitmq_stomp` plugin via mounted `enabled_plugins` file
**When to use:** Docker Compose and k8s deployments (STMP-02)

```
# infra/rabbitmq/enabled_plugins
[rabbitmq_management,rabbitmq_management_agent,rabbitmq_prometheus,rabbitmq_stomp].
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

**Plugin details:**
- The `rabbitmq_stomp` plugin is bundled with the `rabbitmq:3.12-management-alpine` image but not enabled by default [CITED: rabbitmq.com/docs/stomp -- "Before clients can successfully connect, it must be enabled using rabbitmq-plugins"]
- Default STOMP TCP listener port: 61613 [CITED: rabbitmq.com/docs/stomp -- "When no configuration is specified the STOMP Adapter will listen on all interfaces on port 61613"]
- Default credentials: `guest`/`guest`, but this only works for loopback connections. Since Spring connects over Docker network, the relay must use the same credentials as the AMQP connection (`RABBITMQ_USER`/`RABBITMQ_PASSWORD` from `.env`) [CITED: rabbitmq.com/docs/stomp -- "STOMP adapter allows CONNECT frames to omit the login and passcode headers if a default is configured"]
- The `rabbitmq_prometheus` plugin (for `/metrics` on port 15692) is already enabled in the management image and already scraped by Prometheus [VERIFIED: codebase -- prometheus.yml line 86-92 targets `jtoye-rabbitmq:15692`]

### Pattern 3: BrokerAvailabilityEvent for Health Monitoring

**What:** Spring publishes `BrokerAvailabilityEvent` when the STOMP relay connects or disconnects from the broker
**When to use:** Useful for health endpoint integration and logging

```java
// Source: docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay-configure.html
@Component
public class StompBrokerHealthListener {
    private static final Logger log = LoggerFactory.getLogger(StompBrokerHealthListener.class);

    @EventListener
    public void onBrokerAvailability(BrokerAvailabilityEvent event) {
        if (event.isBrokerAvailable()) {
            log.info("STOMP broker relay connected");
        } else {
            log.warn("STOMP broker relay disconnected -- messages will be queued");
        }
    }
}
```

This is useful for operational visibility but not strictly required.

### Pattern 4: Prometheus Alert + Grafana Dashboard for STOMP Health

**What:** Alert on message delivery lag and display connection count
**When to use:** STMP-05

**Critical finding: RabbitMQ does NOT expose STOMP-protocol-specific Prometheus metrics.** [VERIFIED: github.com/rabbitmq/rabbitmq-server/blob/main/deps/rabbitmq_prometheus/metrics.md -- reviewed full metric list, no STOMP-specific metrics exist]. STOMP connections appear as regular `rabbitmq_connections`. STOMP subscriptions create auto-delete queues visible in `rabbitmq_queue_messages` family of metrics.

**Recommended approach for STMP-05:**
- **Alert rule:** Use `rabbitmq_queue_messages_unacked` filtered by queue names matching STOMP subscription pattern (discovered at runtime). Alternative: use `rabbitmq_queue_messages_ready` to detect queue depth buildup as a proxy for lag.
- **Grafana dashboard tile:** Use `rabbitmq_connections` for total connection count. For STOMP-specific counts, query the RabbitMQ Management API `/api/connections` which includes a `protocol` field distinguishing `{STOMP,0-9-1}` from `{AMQP,0-9-1}`.
- **STOMP subscription queue naming:** When a STOMP client subscribes to `/topic/kitchen/X/Y`, RabbitMQ creates a transient queue bound to the `amq.topic` exchange with routing key `kitchen.X.Y`. Queue names are auto-generated (e.g., `stomp-subscription-<random>`). [ASSUMED -- exact naming pattern needs runtime verification]

```yaml
# Alert rule for STMP-05 -- addition to infra/monitoring/prometheus/alerts.yml
- alert: StompBrokerLag
  expr: |
    sum(rabbitmq_queue_messages_ready{queue=~"stomp-subscription.*"}) > 0
  for: 5s
  labels:
    severity: warning
    component: messaging
    service: rabbitmq
  annotations:
    summary: "STOMP broker message lag detected"
    description: "Undelivered messages in STOMP subscription queues: {{ $value }}"
```

### Anti-Patterns to Avoid

- **Running `rabbitmq-plugins enable` at container start via `command:`** -- Fragile and runs on every restart. Use the `enabled_plugins` file mount instead, which is declarative and idempotent.
- **Using `rabbitmq_web_stomp` instead of `rabbitmq_stomp`:** `web_stomp` is for direct browser-to-RabbitMQ WebSocket connections. Spring's `StompBrokerRelay` uses TCP STOMP (port 61613), requiring the plain `rabbitmq_stomp` plugin.
- **Hardcoding relay credentials in Java source:** All credentials must come from environment variables / config properties, matching the project's existing pattern for `RABBITMQ_USER`/`RABBITMQ_PASSWORD`.
- **Configuring `@ConditionalOnProperty` at class level for two separate config beans:** This would require two `WebSocketConfig` classes. A simple `if` branch inside `configureMessageBroker()` is cleaner and easier to test.
- **Forgetting to add `/queue` prefix in relay mode:** `enableStompBrokerRelay("/topic", "/queue")` -- omitting `/queue` is fine for this project (nothing uses `/queue` destinations currently), but including it follows the standard relay configuration pattern.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Cross-replica WebSocket broadcast | Custom Redis pub/sub fan-out | Spring `StompBrokerRelay` + RabbitMQ STOMP | Spring has first-class support; RabbitMQ handles routing, durability, and clustering |
| STOMP heartbeat management | Manual ping/pong frames | `StompBrokerRelay` built-in heartbeat config (10s default) | Relay handles TCP heartbeats automatically |
| WebSocket session stickiness | Load balancer sticky sessions | Message broker relay | Sticky sessions are fragile and defeat horizontal scaling purpose |
| Grafana dashboard JSON | Manual JSON editing | Grafana UI export-as-JSON | Build visually in Grafana, then export and commit the JSON file |

**Key insight:** The entire point of this phase is to NOT hand-roll message routing between replicas. Spring's `StompBrokerRelay` is the standard solution for exactly this problem -- it turns each replica into a relay that forwards STOMP frames to/from an external broker, so the broker handles fan-out.

## Common Pitfalls

### Pitfall 1: `container_name` Blocks Docker Compose `--scale`

**What goes wrong:** `docker compose up --scale core-java=2` fails because `container_name: jtoye-core-java` is set, and Docker requires unique container names.
**Why it happens:** The compose file was written for single-instance dev and hardcodes the name.
**How to avoid:** Remove `container_name: jtoye-core-java` from the core-java service. Other services that reference it by container name (e.g., Prometheus scrape targets using `jtoye-core-java:9090`) must switch to the Docker Compose service name `core-java`.
**Warning signs:** `docker compose up --scale core-java=2` immediately errors with "Conflict. The container name /jtoye-core-java is already in use".
[VERIFIED: codebase -- `container_name: jtoye-core-java` at line 111 of docker-compose.full-stack.yml]

### Pitfall 2: Port Mapping Conflict with Multiple Replicas

**What goes wrong:** `--scale core-java=2` fails if the host port mapping `"9090:9090"` is still set, because both replicas try to bind to host port 9090.
**Why it happens:** Each replica needs its own host port (or no host port).
**How to avoid:** When scaling, remove the `ports:` mapping from core-java and access it only through the Docker network via edge-go (port 8089). Alternatively, use a port range like `"9090-9091:9090"`. For the smoke test and e2e, option 1 is cleaner -- all client traffic goes through edge-go which load-balances across core-java replicas via Docker DNS.
**Warning signs:** "Bind for 0.0.0.0:9090 failed: port is already allocated".
[VERIFIED: codebase -- `ports: - "9090:9090"` at line 149 of docker-compose.full-stack.yml]

### Pitfall 3: Playwright E2E Against Multi-Replica Stack

**What goes wrong:** The e2e test cannot guarantee which replica handles the REST call vs. which replica the WebSocket connects to.
**Why it happens:** Docker Compose round-robin DNS means each connection may hit a different replica.
**How to avoid:** This is actually the *desired behavior* for STMP-04. The test should:
1. Open `/dashboard/kitchen` (WebSocket connects to replica A or B via edge-go)
2. POST an order state change via the edge gateway (hits whichever replica)
3. Assert the WebSocket message arrives within 2 seconds
The test succeeds *precisely because* the broker relay ensures cross-replica delivery. No need to pin to specific replicas.
**Warning signs:** Test flaking due to timing -- use Playwright's `page.waitForEvent('websocket')` or poll the DOM for the new order card with a 2-second timeout.

### Pitfall 4: Missing `reactor-netty` on Classpath

**What goes wrong:** `StompBrokerRelay` throws `ClassNotFoundException` for `ReactorNettyTcpClient` at startup.
**Why it happens:** The relay uses Reactor Netty for TCP connections to the broker. It is NOT pulled in by `spring-boot-starter-websocket` alone.
**How to avoid:** This project already has `spring-boot-starter-webflux` in `build.gradle.kts` (line 49), which transitively includes `reactor-netty` and `netty-all`. No action needed.
[VERIFIED: codebase -- `spring-boot-starter-webflux` at line 49 of build.gradle.kts]
[CITED: docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html -- "Add io.projectreactor.netty:reactor-netty and io.netty:netty-all"]

### Pitfall 5: STOMP Relay Credentials vs. AMQP Credentials

**What goes wrong:** The STOMP relay uses the same RabbitMQ user/password as AMQP, but they're wired through different config paths. AMQP goes through `spring.rabbitmq.username`/`spring.rabbitmq.password`. STOMP relay credentials are set via `setClientLogin()`/`setSystemLogin()`.
**Why it happens:** Spring treats AMQP and STOMP as separate connection concerns with separate configuration.
**How to avoid:** Use the same `RABBITMQ_USER`/`RABBITMQ_PASSWORD` env vars for both. Wire them into `spring.rabbitmq.*` (existing) AND `stomp.broker.client-login`/`stomp.broker.system-login` (new). The RabbitMQ `guest` user only works for loopback connections -- when the relay connects over the Docker network, explicit credentials are required.
[CITED: rabbitmq.com/docs/stomp -- "default user login/passcode of guest/guest"]

### Pitfall 6: Prometheus Scrape Target for Scaled Replicas

**What goes wrong:** `prometheus.yml` has `targets: ['jtoye-core-java:9090']` which uses the container name. When `container_name` is removed for `--scale`, this target breaks.
**Why it happens:** Prometheus `static_configs` resolve DNS once and cache; Docker service DNS returns all replicas but Prometheus may only scrape one.
**How to avoid:** Change the scrape target to `core-java:9090` (the service name, not container name). For the smoke test, Prometheus scraping one replica is acceptable. For production (k8s), pod-level scraping via annotations is already configured (`prometheus.io/scrape: "true"` in `core-java-deployment.yaml`).
[VERIFIED: codebase -- Prometheus target `jtoye-core-java:9090` at line 36 of prometheus.yml]

### Pitfall 7: RabbitMQ Prometheus Plugin Port Not Exposed

**What goes wrong:** The `rabbitmq_prometheus` plugin metrics endpoint (port 15692) is scraped by Prometheus over Docker network but is not exposed to the host.
**Why it happens:** The docker-compose file only maps AMQP (5672) and Management UI (15672) ports for RabbitMQ.
**How to avoid:** No action needed for metrics scraping -- Prometheus is on the same Docker network. But for debugging, exposing 15692 to the host is helpful. This is a nice-to-have, not a blocker.
[VERIFIED: codebase -- rabbitmq ports only 5672/15672 in docker-compose.full-stack.yml]

## Code Examples

### Application YAML Properties (addition to application.yml)

```yaml
# Source: project convention + Spring StompBrokerRelay docs
# STOMP broker relay configuration
# Mode: "in-memory" (default, for local dev) or "relay" (for multi-replica)
stomp:
  broker:
    mode: ${STOMP_BROKER_MODE:in-memory}
    relay-host: ${STOMP_RELAY_HOST:localhost}
    relay-port: ${STOMP_RELAY_PORT:61613}
    client-login: ${RABBITMQ_USER:guest}
    client-passcode: ${RABBITMQ_PASSWORD:guest}
    system-login: ${RABBITMQ_USER:guest}
    system-passcode: ${RABBITMQ_PASSWORD:guest}
```

### Docker Compose Changes (core-java service for relay mode)

```yaml
# Source: project docker-compose.full-stack.yml with modifications
core-java:
  build:
    context: .
    dockerfile: core-java/Dockerfile
  # container_name: REMOVED to allow --scale
  environment:
    # ... existing env vars unchanged ...
    STOMP_BROKER_MODE: ${STOMP_BROKER_MODE:-in-memory}
    STOMP_RELAY_HOST: rabbitmq
    STOMP_RELAY_PORT: "61613"
  # ports: removed or kept as range for scaling
  # For single replica: "9090:9090"
  # For --scale: remove or use "9090-9091:9090"
```

### Docker Compose Changes (rabbitmq service)

```yaml
# Source: project docker-compose.full-stack.yml with modifications
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

Note: `rabbitmq_management_agent` is implicitly enabled by the management image but listing it explicitly is good practice. The trailing period is required (Erlang term syntax). [CITED: rabbitmq.com/docs/stomp -- enabling via rabbitmq-plugins]

### K8s ConfigMap Additions

```yaml
# k8s/base/configmap.yaml additions
data:
  # ... existing entries ...
  stomp.broker.mode: "relay"
  stomp.broker.relay-host: "rabbitmq.jtoye-infrastructure.svc.cluster.local"
  stomp.broker.relay-port: "61613"
```

### K8s Secret Template Additions

```yaml
# k8s/base/secrets-template.yaml -- add to existing rabbitmq-credentials
stringData:
  username: "jtoye"
  password: "REPLACE_WITH_SECURE_PASSWORD"
  stomp-login: "jtoye"           # Same user for STOMP relay
  stomp-passcode: "REPLACE_WITH_SECURE_PASSWORD"  # Same password
```

### K8s Deployment Env Var Additions

```yaml
# k8s/base/core-java-deployment.yaml -- add to env section
- name: STOMP_BROKER_MODE
  valueFrom:
    configMapKeyRef:
      name: app-config
      key: stomp.broker.mode
- name: STOMP_RELAY_HOST
  valueFrom:
    configMapKeyRef:
      name: app-config
      key: stomp.broker.relay-host
- name: STOMP_RELAY_PORT
  valueFrom:
    configMapKeyRef:
      name: app-config
      key: stomp.broker.relay-port
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
echo "Waiting for replicas to be healthy..."
# Poll edge-go health (it depends on core-java)
for i in $(seq 1 60); do
  curl -sf http://localhost:8089/health > /dev/null 2>&1 && break
  sleep 2
done

# 3. Verify STOMP connections via Management API
STOMP_CONNS=$(curl -s -u "${RABBITMQ_DEFAULT_USER}:${RABBITMQ_DEFAULT_PASS}" \
  http://localhost:15672/api/connections | \
  python3 -c "import sys,json; print(len([c for c in json.load(sys.stdin) if 'STOMP' in c.get('protocol','')]))")
echo "STOMP connections: $STOMP_CONNS"

# 4. Result
if [ "$STOMP_CONNS" -ge 2 ]; then
  echo "PASS: $STOMP_CONNS STOMP relay connections detected (expected >= 2 for 2 replicas)"
else
  echo "WARN: Only $STOMP_CONNS STOMP connections. System connections may not be established yet."
fi
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `AbstractWebSocketMessageBrokerConfigurer` | `WebSocketMessageBrokerConfigurer` (interface) | Spring 5.0 | Project already uses the interface [VERIFIED: codebase] |
| SockJS fallback | Native WebSocket | Mainstream ~2020+ | Project correctly uses native WS, no SockJS [VERIFIED: codebase -- line 38 of WebSocketConfig.java comments "D-09: No .withSockJS()"] |
| Manual `ReactorNettyTcpClient` wiring | Auto-configured by `StompBrokerRelay` | Spring 5.x+ | Just add reactor-netty to classpath (already present via webflux) |

**Deprecated/outdated:**
- `AbstractWebSocketMessageBrokerConfigurer`: replaced by `WebSocketMessageBrokerConfigurer` interface (project already correct)
- SockJS: unnecessary for modern browsers (project already correct)

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | STOMP subscription queues in RabbitMQ follow `stomp-subscription-*` naming pattern | Pattern 4 (alert rule) | Alert PromQL expression won't match queues; fixable at runtime by checking RabbitMQ management UI for actual queue names |
| A2 | `rabbitmq_queue_messages_ready` is a reliable proxy for STOMP delivery lag | Pattern 4 (alert rule) | May need different metric or approach; the 5-second "lag" requirement might need to be measured differently (e.g., application-level timestamp comparison) |
| A3 | Docker Compose DNS round-robin distributes requests across scaled replicas for the smoke test | Pitfall 3 | If DNS caching causes all requests to hit one replica, the test would still pass (broker relay handles it) but wouldn't definitively prove cross-replica -- very low risk |
| A4 | `rabbitmq_prometheus` plugin is enabled by default in `rabbitmq:3.12-management-alpine` | Standard Stack | If not, it needs to be added to the `enabled_plugins` file. Prometheus already scrapes port 15692 successfully per project config, so it's likely enabled. |

## Open Questions

1. **Exact STOMP subscription queue naming pattern in RabbitMQ**
   - What we know: RabbitMQ creates auto-delete queues for STOMP subscriptions bound to `amq.topic`
   - What's unclear: The exact queue name format (likely `stomp-subscription-<random-id>` but needs verification)
   - Recommendation: Start stack in relay mode, subscribe from kitchen display, inspect queues at `http://localhost:15672/#/queues`, then finalize alert rule PromQL expression

2. **Whether `rabbitmq_connections` metric distinguishes STOMP from AMQP connections**
   - What we know: The `rabbitmq_prometheus` plugin reports `rabbitmq_connections` (total). The Management API `/api/connections` has a `protocol` field. The Prometheus metrics list does NOT include a `protocol` label. [VERIFIED: github.com/rabbitmq/rabbitmq-server metrics.md]
   - What's unclear: Whether the Grafana tile can filter STOMP-only connections via Prometheus, or must use the Management API
   - Recommendation: For the Grafana tile, use `rabbitmq_connections` as total and note it includes both AMQP and STOMP. If STOMP-only count is needed, the Management API is the source.

3. **Port mapping strategy for multi-replica smoke test**
   - What we know: Host port 9090 can't be shared between replicas
   - What's unclear: Whether removing `ports:` from core-java breaks other smoke tests or manual debugging
   - Recommendation: Remove `ports:` mapping when `--scale` is used; route all traffic through edge-go at port 8089. Keep a comment in docker-compose showing how to restore for single-replica dev.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| RabbitMQ | STOMP relay broker | Yes | 3.12.x | -- (already running in docker-compose) |
| rabbitmq_stomp plugin | STMP-01/02 | Bundled (disabled) | 3.12.x | Enable via `enabled_plugins` file |
| reactor-netty | StompBrokerRelay TCP | Yes (via webflux) | Boot BOM managed | -- (already present) |
| Prometheus | STMP-05 alerts | Yes | v2.48.0 | -- (already in monitoring compose) |
| Grafana | STMP-05 dashboard | Yes | 10.2.2 | -- (already in monitoring compose) |
| Alertmanager | STMP-05 alert routing | Yes (Phase 9) | v0.27.0 | -- (Phase 9 dependency) |
| Playwright | STMP-04 e2e | Yes | 1.59.1 | -- (already configured in `frontend/playwright.config.ts`) |
| Docker Compose | STMP-03 scaling | Yes | Present | -- |
| edge-go | Load balancing replicas | Yes | In docker-compose | -- (already present) |

**Missing dependencies with no fallback:** None.
**Missing dependencies with fallback:** None.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework (Java) | JUnit 5 + Spring Boot Test (existing) |
| Framework (E2E) | Playwright 1.59.1 (existing) |
| Config file (Java) | `core-java/build.gradle.kts` |
| Config file (E2E) | `frontend/playwright.config.ts` |
| Quick run command | `cd core-java && ./gradlew test --tests '*WebSocketConfig*' -x bootJar` |
| Full suite command | `cd core-java && ./gradlew test -x bootJar && cd ../frontend && npx playwright test` |

### Phase Requirements -> Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| STMP-01 | WebSocketConfig switches between simple and relay broker based on property | unit | `./gradlew test --tests '*WebSocketConfig*'` | Partial -- existing test covers annotation/interceptor (3 tests), needs relay-mode branch test |
| STMP-02 | RabbitMQ STOMP plugin enabled, port 61613 exposed, credentials wired | smoke | `scripts/smoke-test-stomp-relay.sh` | No -- Wave 0 |
| STMP-03 | Cross-replica broadcast within 2 seconds | smoke | `scripts/smoke-test-stomp-relay.sh` (manual capture) | No -- Wave 0 |
| STMP-04 | Playwright e2e cross-replica WebSocket delivery within 2s | e2e | `npx playwright test e2e/stomp-relay.spec.ts` | No -- Wave 0 |
| STMP-05 | Prometheus alert fires, Grafana tile renders | smoke / manual | `promtool check rules alerts.yml` + manual Grafana verification | No -- Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests '*WebSocketConfig*' -x bootJar`
- **Per wave merge:** Full Java test suite + Playwright
- **Phase gate:** Full suite green + `smoke-test-stomp-relay.sh` pass + Grafana tile screenshot

### Wave 0 Gaps
- [ ] `core-java/src/test/java/uk/jtoye/core/websocket/WebSocketConfigTest.java` -- extend with relay-mode branch test for STMP-01
- [ ] `frontend/e2e/stomp-relay.spec.ts` -- new Playwright spec for STMP-04
- [ ] `scripts/smoke-test-stomp-relay.sh` -- new smoke test script for STMP-02, STMP-03
- [ ] `promtool check rules` validation for new alert rule for STMP-05

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | Yes | JWT validation on STOMP CONNECT via `JwtHandshakeInterceptor` (existing, unchanged) |
| V3 Session Management | No | WebSocket sessions managed by Spring, no change |
| V4 Access Control | Yes | Tenant isolation on SUBSCRIBE via `TenantChannelInterceptor` (existing, unchanged) |
| V5 Input Validation | No | No new user input surfaces |
| V6 Cryptography | No | Relay uses plain TCP within Docker network; TLS for prod is an optional enhancement but not required for this phase |

### Known Threat Patterns

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| STOMP relay credentials in plaintext env vars | Information Disclosure | k8s Secrets + env var injection (existing project pattern) |
| Cross-tenant message leakage via broker | Elevation of Privilege | `TenantChannelInterceptor` validates SUBSCRIBE destination includes session tenant_id (existing, unchanged) |
| RabbitMQ STOMP port 61613 exposed to host | Information Disclosure | Only expose to Docker network in prod; host mapping for dev convenience only |
| Broker unavailability causing message loss | Denial of Service | `StompBrokerRelay` auto-reconnects with 5-second interval; `BrokerAvailabilityEvent` for monitoring; Alertmanager alert for sustained outage |

## Project Constraints (from CLAUDE.md)

- **Tech stack:** Spring Boot 3.4.2, Next.js 16, Go 1.22, PostgreSQL 15 -- no new frameworks allowed
- **Java version:** JDK 21 (JDK 25 incompatible with Gradle 8.10)
- **Multi-tenancy:** All WebSocket features must respect RLS and TenantContext -- existing `TenantChannelInterceptor` handles this, unchanged by relay switch
- **Testing:** All new code requires tests -- 310+ tests must remain passing; unit test for config branch, e2e for cross-replica
- **Docker:** Always rebuild ALL containers after code changes before E2E testing
- **Git:** Feature branches only, no direct main commits

## Sources

### Primary (HIGH confidence)
- [Spring Framework - External Broker / StompBrokerRelay](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html) -- `enableStompBrokerRelay` API, required dependencies (`reactor-netty`, `netty-all`), supported brokers (RabbitMQ, ActiveMQ)
- [Spring Framework - Broker Relay Configuration](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay-configure.html) -- relayHost, relayPort, systemLogin/Passcode, clientLogin/Passcode defaults (`guest`/`guest`), heartbeat intervals (10s), reconnect interval (5s), `BrokerAvailabilityEvent`
- [RabbitMQ STOMP Plugin](https://www.rabbitmq.com/docs/stomp) -- plugin enablement, default port 61613, TCP listener config, default user config, virtual host config
- [RabbitMQ Prometheus Metrics](https://github.com/rabbitmq/rabbitmq-server/blob/main/deps/rabbitmq_prometheus/metrics.md) -- full metric list confirming NO STOMP-specific metrics exist
- Codebase verification -- `WebSocketConfig.java`, `OrderStateChangeListener.java`, `build.gradle.kts`, `docker-compose.full-stack.yml`, `k8s/` manifests, `prometheus.yml`, `alerts.yml`, `application.yml`, `application-test.yml`, `application-prod.yml`, `use-stomp.ts`, `kitchen-flow.spec.ts`

### Secondary (MEDIUM confidence)
- [Spring WebSocket STOMP Guide](https://spring.io/guides/gs/messaging-stomp-websocket) -- basic STOMP configuration patterns via Context7
- [RabbitMQ Prometheus Monitoring](https://www.rabbitmq.com/docs/prometheus) -- `rabbitmq_prometheus` plugin enablement, port 15692

### Tertiary (LOW confidence)
- STOMP subscription queue naming pattern (`stomp-subscription-*`) -- needs runtime verification [ASSUMED]
- `rabbitmq_queue_messages_ready` as a proxy for STOMP lag -- needs runtime verification [ASSUMED]

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- all libraries already in project, versions verified against codebase
- Architecture: HIGH -- Spring `StompBrokerRelay` is the documented standard approach; data flow traced through existing codebase (`OrderEventPublisher` -> AMQP -> `OrderStateChangeListener` -> `SimpMessagingTemplate` -> relay -> RabbitMQ STOMP -> fan-out)
- Pitfalls: HIGH -- `container_name` and port conflicts verified in codebase; reactor-netty presence confirmed; credential separation documented in official Spring docs
- Monitoring (STMP-05): MEDIUM -- RabbitMQ has no STOMP-specific Prometheus metrics; alert rule expression needs runtime tuning

**Research date:** 2026-04-16
**Valid until:** 2026-05-16 (stable -- Spring Boot 3.4.2 LTS, RabbitMQ 3.12 stable)
