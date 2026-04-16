# Handoff: Deep Audit P1 Fixes Complete

**Generated**: 2026-04-16T18:30Z
**Branch**: feature/deep-audit-p1-fixes (PR pending)
**Status**: All P1 audit items implemented and tested.

## Completed (This Session)

- [x] Pagination max-page-size: 100 + default-page-size: 20 (CQ-03)
- [x] 4 new Prometheus alerts: DiskSpaceLow, DiskSpaceCritical, KeycloakDown, RedisDown (MON-01, MON-02)
- [x] redis-exporter service deployed in monitoring compose (INFRA-02)
- [x] Frontend depends_on service_healthy for core-java + keycloak (INFRA-03)
- [x] Alert threshold fixes: HighErrorRate 3%, FrequentGC 50/s, StompBrokerLag 30s, NoOrdersCreated URI regex (INFRA-06/07/08/09)
- [x] Error boundaries: root, dashboard, storefront (ERR-06)
- [x] STOMP tenant validation on ALL /topic/ subscriptions, not just /topic/kitchen/ (AUTH-01)
- [x] JWT moved from WebSocket query param to STOMP CONNECT frame headers with fallback (SEC-03)
- [x] Go edge gateway tests: 57 passing (was 21) — rate limiter, health/ready, extractBearerToken, SearchProducts, CreateOrder, ForwardWebhook, WhatsApp edge cases
- [x] CLAUDE.md test count updated to 474+

## Test Results

- Java: BUILD SUCCESSFUL (341 tests)
- Frontend Jest: 13 suites, 76/76 pass
- Go: 57/57 pass
- Go vet: Clean

## Remaining Work (P2 from Deep Audit)

- [ ] Fix stock race condition — validate at confirmation, not creation (CQ-01)
- [ ] Fix getSummary() to use DB aggregation instead of findAll() (CQ-02)
- [ ] Add K8s NetworkPolicies (INFRA-17)
- [ ] Add K8s Sealed Secrets (INFRA-11)
- [ ] Add application-layer tenant validation for guest tracking
- [ ] Add security headers to Spring responses
- [ ] Remove tenantId from response DTOs
- [ ] Fix blocking reactive calls in state machine
- [ ] Add CSP headers
- [ ] Log frontend API errors before swallowing
- [ ] Generate OpenAPI for Go gateway
- [ ] Add Grafana dashboards for JVM, database, business metrics (INFRA-10)
- [ ] Add Alertmanager inhibition rules (INFRA-11)
- [ ] Add alert runbook documentation

## Key Decisions

| Decision | Rationale |
|----------|-----------|
| ALL /topic/ subscriptions require tenant segment | Future-proofs against new broadcast channels bypassing isolation |
| JWT in STOMP CONNECT headers with session fallback | Backwards-compatible during rolling deploys |
| redis-exporter added to monitoring compose only | Full-stack compose uses redis directly, exporter is monitoring concern |

## Setup

- **JDK 21**: `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`
- **Frontend port**: 3100 (port 3000 held by MCP server)
- **Docker stack**: `docker compose -f docker-compose.full-stack.yml up -d`
- **Monitoring**: `docker compose -f infra/monitoring/docker-compose.monitoring.yml --env-file .env up -d`
