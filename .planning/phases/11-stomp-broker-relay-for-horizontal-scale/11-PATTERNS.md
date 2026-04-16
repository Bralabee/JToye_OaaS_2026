# Phase 11: STOMP Broker Relay for Horizontal Scale - Pattern Map

**Mapped:** 2026-04-16
**Files analyzed:** 11 (3 new, 8 modified)
**Analogs found:** 10 / 11

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `core-java/src/main/java/uk/jtoye/core/websocket/WebSocketConfig.java` | config | event-driven | Self (current file) | exact |
| `core-java/src/main/resources/application.yml` | config | N/A | Self (current file) | exact |
| `core-java/src/test/java/uk/jtoye/core/websocket/WebSocketConfigTest.java` | test | N/A | Self (current file) | exact |
| `docker-compose.full-stack.yml` | config | N/A | Self (current file) | exact |
| `infra/rabbitmq/enabled_plugins` | config | N/A | -- | no-analog |
| `infra/monitoring/prometheus/alerts.yml` | config | event-driven | Self (current file) | exact |
| `infra/monitoring/grafana/dashboards/stomp-dashboard.json` | config | N/A | No existing dashboards | role-match |
| `k8s/base/configmap.yaml` | config | N/A | Self (current file) | exact |
| `k8s/base/secrets-template.yaml` | config | N/A | Self (current file) | exact |
| `k8s/base/core-java-deployment.yaml` | config | N/A | Self (current file) | exact |
| `frontend/e2e/stomp-relay.spec.ts` | test | event-driven | `frontend/e2e/kitchen-flow.spec.ts` | role-match |
| `scripts/smoke-test-stomp-relay.sh` | utility | request-response | `scripts/smoke-test.sh` | exact |

## Pattern Assignments

### `core-java/src/main/java/uk/jtoye/core/websocket/WebSocketConfig.java` (config, event-driven) -- MODIFY

**Analog:** Self -- this is the file being modified.

**Current imports pattern** (lines 1-8):
```java
package uk.jtoye.core.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
```

**New imports needed:**
```java
import org.springframework.beans.factory.annotation.Value;
```

**Current broker config** (lines 28-31) -- this is what gets the conditional branch:
```java
@Override
public void configureMessageBroker(MessageBrokerRegistry config) {
    config.enableSimpleBroker("/topic");
    config.setApplicationDestinationPrefixes("/app");
}
```

**Constructor injection pattern** (lines 21-24) -- follow this for adding @Value fields:
```java
private final TenantChannelInterceptor tenantChannelInterceptor;

public WebSocketConfig(TenantChannelInterceptor tenantChannelInterceptor) {
    this.tenantChannelInterceptor = tenantChannelInterceptor;
}
```

**Key constraint:** The `registerStompEndpoints` and `configureClientInboundChannel` methods (lines 34-44) must remain unchanged. Only `configureMessageBroker` and the field declarations change.

---

### `core-java/src/main/resources/application.yml` (config) -- MODIFY

**Analog:** Self -- adding a new top-level config block.

**Existing env-var-with-default pattern** (lines 55-59) -- follow this for STOMP properties:
```yaml
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:jtoye}
    password: ${RABBITMQ_PASSWORD:}
```

**Insertion point:** After the `spring.rabbitmq` block (after line 59) or as a new top-level `stomp:` block (after `cleanup:` at line 200). The RESEARCH.md recommends a top-level `stomp:` block, which is consistent with how `storage:`, `ai:`, `stripe:`, and `notification:` are structured as custom top-level blocks (lines 128-158).

**Pattern for custom top-level config blocks** (lines 128-136):
```yaml
storage:
  s3:
    endpoint: ${S3_ENDPOINT:http://localhost:9000}
    region: ${S3_REGION:eu-west-2}
    bucket: ${S3_BUCKET:jtoye-images}
    access-key: ${S3_ACCESS_KEY:minioadmin}
    secret-key: ${S3_SECRET_KEY:minioadmin}
    public-url: ${S3_PUBLIC_URL:http://localhost:9000/jtoye-images}
```

---

### `core-java/src/test/java/uk/jtoye/core/websocket/WebSocketConfigTest.java` (test) -- MODIFY

**Analog:** Self -- extending existing tests.

**Current test structure** (full file, lines 1-35):
```java
package uk.jtoye.core.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebSocketConfigTest {

    @Test
    void shouldBeAnnotatedWithEnableWebSocketMessageBroker() {
        assertThat(WebSocketConfig.class.isAnnotationPresent(EnableWebSocketMessageBroker.class)).isTrue();
    }

    @Test
    void shouldImplementWebSocketMessageBrokerConfigurer() {
        assertThat(WebSocketMessageBrokerConfigurer.class.isAssignableFrom(WebSocketConfig.class)).isTrue();
    }

    @Test
    void shouldRegisterTenantChannelInterceptorOnInboundChannel() {
        TenantChannelInterceptor mockInterceptor = mock(TenantChannelInterceptor.class);
        WebSocketConfig config = new WebSocketConfig(mockInterceptor);
        ChannelRegistration registration = mock(ChannelRegistration.class);

        config.configureClientInboundChannel(registration);

        verify(registration).interceptors(mockInterceptor);
    }
}
```

**Test naming convention:** `should<BehaviorDescription>` with camelCase.
**Test libraries:** JUnit 5 + AssertJ + Mockito (no Spring context loaded).
**New tests needed:** Verify that `configureMessageBroker` calls `enableSimpleBroker` when mode is `in-memory` and `enableStompBrokerRelay` when mode is `relay`. Since `WebSocketConfig` will now use `@Value` fields, tests will need to use reflection or a constructor that accepts the mode, or use `ReflectionTestUtils.setField()`.

---

### `docker-compose.full-stack.yml` (config) -- MODIFY

**Analog:** Self -- two sections need changes.

**RabbitMQ service current** (lines 87-104):
```yaml
  rabbitmq:
    image: rabbitmq:3.12-management-alpine
    container_name: jtoye-rabbitmq
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_DEFAULT_USER}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_DEFAULT_PASS}
    ports:
      - "5672:5672"   # AMQP
      - "15672:15672" # Management UI
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - jtoye-network
```

**Changes needed:** Add port `61613:61613` (STOMP), add volume mount `./infra/rabbitmq/enabled_plugins:/etc/rabbitmq/enabled_plugins:ro`.

**Core-java service current** (lines 107-149):
```yaml
  core-java:
    build:
      context: .
      dockerfile: core-java/Dockerfile
    container_name: jtoye-core-java
    environment:
      # ... many env vars ...
```

**Changes needed:** Remove `container_name: jtoye-core-java` (line 111), add `STOMP_BROKER_MODE`, `STOMP_RELAY_HOST`, `STOMP_RELAY_PORT` env vars following the existing `${VAR:-default}` pattern (e.g., line 126: `RABBITMQ_HOST: rabbitmq`).

**Env var pattern for docker-compose** (lines 123-125):
```yaml
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USER: ${RABBITMQ_USER}
      RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD}
```

---

### `infra/rabbitmq/enabled_plugins` (config) -- NEW

**Analog:** None -- this is a RabbitMQ-specific Erlang config file with no codebase precedent.

**Content is a single line of Erlang term syntax:**
```erlang
[rabbitmq_management,rabbitmq_management_agent,rabbitmq_prometheus,rabbitmq_stomp].
```

No pattern extraction needed -- the RESEARCH.md provides the exact content.

---

### `infra/monitoring/prometheus/alerts.yml` (config, event-driven) -- MODIFY

**Analog:** Self -- adding a new alert group.

**Existing alert group structure** (lines 1-6, 50-53):
```yaml
groups:
  - name: api_alerts
    interval: 30s
    rules:
      - alert: HighErrorRate
        expr: |
```

**Single alert rule pattern** (lines 123-139) -- most recent/closest to STOMP use case (business alerts group):
```yaml
  - name: business_alerts
    interval: 30s
    rules:
      - alert: NoOrdersCreated
        expr: |
          (
            increase(http_server_requests_seconds_count{uri="/orders",method="POST",status="201"}[30m]) < 1
          )
        for: 30m
        labels:
          severity: info
          component: business
          service: core-java
        annotations:
          summary: "No orders created in last 30 minutes"
          description: "No successful order creation detected. This may indicate a business issue."
```

**New group to add:** `messaging_alerts` with the `StompBrokerLag` rule. Follow the same structure: `name`, `interval: 30s`, `rules` list with `alert`, `expr`, `for`, `labels` (severity, component, service), `annotations` (summary, description).

---

### `k8s/base/configmap.yaml` (config) -- MODIFY

**Analog:** Self.

**Existing data entries pattern** (lines 17-24):
```yaml
data:
  # RabbitMQ configuration
  rabbitmq.host: "rabbitmq.jtoye-infrastructure.svc.cluster.local"
  rabbitmq.port: "5672"

  # Application settings
  log.level: "INFO"
  sql.log.level: "WARN"
```

**New entries to add** (follow same dot-notation key style):
```yaml
  # STOMP broker relay configuration
  stomp.broker.mode: "relay"
  stomp.broker.relay-host: "rabbitmq.jtoye-infrastructure.svc.cluster.local"
  stomp.broker.relay-port: "61613"
```

---

### `k8s/base/secrets-template.yaml` (config) -- MODIFY

**Analog:** Self.

**Existing rabbitmq-credentials secret** (lines 49-56):
```yaml
---
apiVersion: v1
kind: Secret
metadata:
  name: rabbitmq-credentials
type: Opaque
stringData:
  username: "jtoye"
  password: "REPLACE_WITH_SECURE_PASSWORD"
```

**New entries to add** under `stringData`:
```yaml
  stomp-login: "jtoye"
  stomp-passcode: "REPLACE_WITH_SECURE_PASSWORD"
```

---

### `k8s/base/core-java-deployment.yaml` (config) -- MODIFY

**Analog:** Self.

**ConfigMap env var pattern** (lines 81-90):
```yaml
        - name: REDIS_HOST
          valueFrom:
            configMapKeyRef:
              name: app-config
              key: redis.host
        - name: RABBITMQ_HOST
          valueFrom:
            configMapKeyRef:
              name: app-config
              key: rabbitmq.host
```

**Secret env var pattern** (lines 91-100):
```yaml
        - name: RABBITMQ_USERNAME
          valueFrom:
            secretKeyRef:
              name: rabbitmq-credentials
              key: username
        - name: RABBITMQ_PASSWORD
          valueFrom:
            secretKeyRef:
              name: rabbitmq-credentials
              key: password
```

**New env vars to add** (3 from configmap, 2 from secret):
- `STOMP_BROKER_MODE` from `app-config` key `stomp.broker.mode`
- `STOMP_RELAY_HOST` from `app-config` key `stomp.broker.relay-host`
- `STOMP_RELAY_PORT` from `app-config` key `stomp.broker.relay-port`
- `STOMP_CLIENT_LOGIN` from `rabbitmq-credentials` key `stomp-login`
- `STOMP_CLIENT_PASSCODE` from `rabbitmq-credentials` key `stomp-passcode`

---

### `frontend/e2e/stomp-relay.spec.ts` (test, event-driven) -- NEW

**Analog:** `frontend/e2e/kitchen-flow.spec.ts`

**Imports and constants pattern** (lines 14-19):
```typescript
import { test, expect } from "@playwright/test"

const BASE = "http://localhost:3000"
const API = "http://localhost:9090"
```

**Test describe/beforeEach structure** (lines 85-127):
```typescript
test.describe("Kitchen display", () => {
  test.beforeEach(async ({ page, context }) => {
    // Stub the STOMP websocket endpoint so a real broker is not needed
    await context.route("**/ws**", (route) => route.abort())

    // Stub REST calls
    await context.route(`${API}/api/v1/shops**`, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(shopsResponse),
      })
    )
    // ... more stubs ...

    // Fake a NextAuth session cookie
    await context.addCookies([
      {
        name: "authjs.session-token",
        value: "e2e-stub",
        domain: "localhost",
        path: "/",
        httpOnly: true,
        sameSite: "Lax",
      },
    ])
  })
```

**Key difference for STOMP relay e2e:** Unlike kitchen-flow.spec.ts which stubs the WebSocket (`route.abort()`), the relay e2e test must run against a live multi-replica stack and verify real WebSocket message delivery. The test should NOT stub routes -- it needs the full stack running with `STOMP_BROKER_MODE=relay` and `--scale core-java=2`.

**Playwright config** (`frontend/playwright.config.ts` lines 1-14):
```typescript
import { defineConfig, devices } from "@playwright/test"

export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: 0,
  reporter: [["html", { open: "never" }], ["list"]],
  use: {
    baseURL: "http://localhost:3000",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
```

**Test pattern from storefront-flows.spec.ts** (lines 15-17) -- helper function style for reusable setup:
```typescript
async function loginCustomer(page: Page): Promise<{ email: string }> {
```

---

### `scripts/smoke-test-stomp-relay.sh` (utility, request-response) -- NEW

**Analog:** `scripts/smoke-test.sh`

**Shebang and setup pattern** (lines 1-6):
```bash
#!/bin/bash
# Smoke tests for J'Toye OaaS deployment
# Validates that the application is functional after deployment

set -e
```

**Color constants pattern** (lines 8-12):
```bash
# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'
```

**Test function pattern** (lines 23-41):
```bash
test_endpoint() {
    local name=$1
    local url=$2
    local expected_code=${3:-200}
    local method=${4:-GET}

    echo -n "Testing $name... "

    response=$(curl -s -o /dev/null -w "%{http_code}" -X "$method" \
        --max-time $TIMEOUT \
        "$url" 2>/dev/null || echo "000")

    if [ "$response" = "$expected_code" ]; then
        echo -e "${GREEN}PASS${NC} (HTTP $response)"
        return 0
    else
        echo -e "${RED}FAIL${NC} (Expected HTTP $expected_code, got $response)"
        return 1
    fi
}
```

**Test counter and summary pattern** (lines 44-47, 120-134):
```bash
TESTS_PASSED=0
TESTS_FAILED=0
# ... tests ...
echo -e "${YELLOW}=== Test Summary ===${NC}"
echo -e "Passed: ${GREEN}$TESTS_PASSED${NC}"
echo -e "Failed: ${RED}$TESTS_FAILED${NC}"
```

---

## Shared Patterns

### Environment Variable Wiring (Spring Boot)
**Source:** `core-java/src/main/resources/application.yml` lines 55-59
**Apply to:** All new STOMP config properties
```yaml
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:jtoye}
    password: ${RABBITMQ_PASSWORD:}
```
Convention: `${ENV_VAR:default}` with sensible local dev defaults.

### Docker Compose Env Var Injection
**Source:** `docker-compose.full-stack.yml` lines 123-125
**Apply to:** core-java service STOMP env vars
```yaml
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USER: ${RABBITMQ_USER}
      RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD}
```
Convention: Infrastructure hostnames are hardcoded to Docker service names; credentials reference `.env` vars.

### K8s ConfigMap + Secret Wiring
**Source:** `k8s/base/core-java-deployment.yaml` lines 81-100
**Apply to:** New STOMP env vars in the deployment
```yaml
        - name: RABBITMQ_HOST
          valueFrom:
            configMapKeyRef:
              name: app-config
              key: rabbitmq.host
        - name: RABBITMQ_PASSWORD
          valueFrom:
            secretKeyRef:
              name: rabbitmq-credentials
              key: password
```
Convention: Non-sensitive config from `app-config` ConfigMap; credentials from named Secrets.

### Prometheus Alert Rule Structure
**Source:** `infra/monitoring/prometheus/alerts.yml` lines 6-21
**Apply to:** New StompBrokerLag alert
```yaml
      - alert: HighErrorRate
        expr: |
          (
            sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) by (service)
            /
            sum(rate(http_server_requests_seconds_count[5m])) by (service)
          ) * 100 > 5
        for: 5m
        labels:
          severity: critical
          component: api
          service: core-java
        annotations:
          summary: "High error rate detected on {{ $labels.service }}"
          description: "Service {{ $labels.service }} has {{ $value | humanizePercentage }} error rate (threshold: 5%)"
```
Convention: `labels` always include `severity`, `component`, `service`. `annotations` always include `summary` and `description` with template variables.

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `infra/rabbitmq/enabled_plugins` | config | N/A | RabbitMQ-specific Erlang term file; no precedent in codebase. Content is a single line -- use RESEARCH.md example directly. |
| `infra/monitoring/grafana/dashboards/stomp-dashboard.json` | config | N/A | No existing Grafana dashboard JSON files in the repo. Build visually in Grafana UI then export as JSON (per RESEARCH.md recommendation). |

## Metadata

**Analog search scope:** `core-java/src/`, `frontend/e2e/`, `infra/monitoring/`, `k8s/base/`, `scripts/`, `docker-compose.full-stack.yml`
**Files scanned:** 15 analog candidates read
**Pattern extraction date:** 2026-04-16
