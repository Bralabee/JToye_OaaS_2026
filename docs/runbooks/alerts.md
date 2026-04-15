# Alert Runbook

This document is the first-response reference for every Prometheus alert rule defined in `infra/monitoring/prometheus/alerts.yml`. Alerts route via Alertmanager (`infra/monitoring/docker-compose.monitoring.yml`) to an email receiver — Mailhog in dev, SMTP relay in prod.

**When an alert fires:**
1. Open the alert notification (email body lists the alert name, severity, service, started-at)
2. Find the matching section below
3. Follow the first-response steps
4. If unresolved after first-response, follow the escalation path

**Mailhog UI (dev):** http://localhost:8025
**Alertmanager UI:** http://localhost:9093
**Prometheus UI:** http://localhost:9091

---

## ServiceDown

**Rule:** `alerts.yml` group `api_alerts`
**Expression:** `up == 0`
**Duration:** fires after 2 minutes of down-state
**Severity:** critical
**Service label:** `platform` (this alert is a platform-wide catch-all — any `up==0` triggers it regardless of which target)

### What it means

Prometheus cannot scrape one of its configured targets. The scraped target could be any of:
- `jtoye-core-java:9090` (Spring Boot actuator)
- `jtoye-edge-go:8080` (Go edge gateway)
- `jtoye-postgres-exporter:9187`
- `jtoye-keycloak:8080`
- `jtoye-rabbitmq:15692`
- `redis-exporter:9121`

Check the `job` label on the firing alert — it identifies which target is down.

### Expected impact

- **core-java down** — full API outage; customers cannot place orders, vendors cannot manage anything, kitchen WebSocket broadcasts stop
- **edge-go down** — rate limiting + JWT validation fallback path is dead; customer storefront may still partially work via direct core-java calls
- **postgres-exporter down** — no database metrics, but the database itself may still be serving traffic; check separately via `DatabaseDown` alert
- **keycloak down** — no new logins; existing JWTs work until expiry (default 15 min)
- **rabbitmq down** — payment outbox flush backs up, kitchen broadcasts stop fanning out, DLQ inaccessible
- **redis-exporter down** — cache metrics lost; cache itself may still work

### First-response steps

1. **Confirm scope** — how many targets are firing?
   ```bash
   curl -s http://localhost:9091/api/v1/query?query=up==0 | jq .
   ```
2. **Check container health** — is it restarting, crashed, or OOM-killed?
   ```bash
   docker ps --filter 'name=jtoye-' --format 'table {{.Names}}\t{{.Status}}'
   docker logs --tail 100 jtoye-<service-name>
   ```
3. **If the container is restart-looping** — likely a bad config or env var change. Inspect:
   ```bash
   docker compose config | less     # Validates + renders
   docker compose logs --tail 200 <service>
   ```
4. **If the container is healthy but Prometheus cannot scrape it** — network or DNS issue. Confirm both services are on the same docker network:
   ```bash
   docker network inspect jtoye_oaas_2026_jtoye-network | grep <service>
   ```
5. **If all targets are down simultaneously** — likely a docker-daemon / host / disk-full issue. Check:
   ```bash
   docker info
   df -h
   systemctl status docker
   ```

### Escalation

- **Production outage** — page the on-call engineer immediately. Expected RTO: 15 minutes
- **Unresolved after 30 minutes** — open an incident in the tracker with the full `docker logs` output and the Prometheus query response from step 1
- **Recurring** (same target, more than 3× per week) — treat as a systemic issue: open a post-incident review ticket, investigate root cause, add a rule-specific runbook entry below

---

## HighErrorRate

<!-- TODO: fill in when a real incident provides first-response lessons. -->
<!-- Rule: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) / rate(http_server_requests_seconds_count[5m]) > 5% for 5m, service=core-java -->
<!-- First response: check core-java logs for exception stack traces; check DatabaseConnectionPoolExhausted / TooManyDatabaseConnections for downstream cause -->

## HighResponseTime

<!-- TODO: fill in. Rule: P95 latency > 1s for 5m. service=core-java -->

## DatabaseConnectionPoolExhausted

<!-- TODO: fill in. Rule: hikaricp_connections_active/max > 90% for 5m. -->

## DatabaseDown

<!-- TODO: fill in. Rule: up{job="postgres"} == 0 for 1m. -->

## TooManyDatabaseConnections

<!-- TODO: fill in. Rule: pg_stat_database_numbackends > 100 for 5m. -->

## HighMemoryUsage

<!-- TODO: fill in. Rule: jvm heap > 85% for 5m. service=core-java -->

## FrequentGarbageCollection

<!-- TODO: fill in. Rule: rate(jvm_gc_pause_seconds_count[5m]) > 10 for 5m. service=core-java -->

## NoOrdersCreated

<!-- TODO: fill in. Rule: increase(http_server_requests_seconds_count{uri=/orders,POST,201}[30m]) < 1. This is an info-severity business signal, not an outage. -->

## TenantIsolationFailure

<!-- TODO: fill in. Rule: rate(tenant_context_missing_total[5m]) > 0.1. Security-critical — investigate the request path that bypassed JwtTenantFilter. -->

---

*Last updated: 2026-04-15 — phase 9 runbook skeleton. Fill in TODO sections as incidents expose first-response lessons.*
