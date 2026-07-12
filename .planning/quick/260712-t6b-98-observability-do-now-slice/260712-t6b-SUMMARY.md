---
phase: quick-260712-t6b
plan: 01
subsystem: infra
tags: [prometheus, micrometer, observability, spring-boot, gin, go, kubernetes, alertmanager, actuator]

# Dependency graph
requires:
  - phase: quick-260712-pzi
    provides: CI/CD deploy honesty + smoke-test EXPECT_SWAGGER structure (PR #201) that this slice extends with EXPECT_PUBLIC_ACTUATOR
provides:
  - edge-go Prometheus /metrics endpoint (http_requests_total + http_request_duration_seconds, low-cardinality route-template labels) + re-enabled scrape job
  - Two real Micrometer counters (tenant_context_missing_total, jtoye_payment_failed_total) backing the TenantIsolationFailure + new PaymentFailureSpike alerts
  - Reconciled alerts.yml with no phantom-metric rules (DiskSpace disabled pending node-exporter)
  - Prod /actuator/prometheus served only on an internal management port (9091), app port exposes no metrics, deploy pipeline aligned (ci-cd + smoke)
  - Fixed single-emit prod JSON log pattern
  - Fixed deprecated Boot 2.x metrics-export property that left /actuator/prometheus dead in Boot 3.5
affects: [observability, deployment, ci-cd, monitoring, edge-go, core-java-security]

# Tech tracking
tech-stack:
  added: ["github.com/prometheus/client_golang v1.23.2 (edge-go)"]
  patterns:
    - "Low-cardinality metric labels: matched route TEMPLATE (c.FullPath) never raw path; label-free counters (no tenant/order id)"
    - "Separate Spring Boot management.server.port for prod actuator/metrics (off the public app port), with unconditional /actuator/prometheus permitAll safe because prod serves it only internally"
    - "Deploy-smoke negative assertions gated by env var (EXPECT_PUBLIC_ACTUATOR, mirroring EXPECT_SWAGGER) so a hardened prod release never auto-rolls-back"

key-files:
  created:
    - edge-go/cmd/edge/metrics.go
    - edge-go/cmd/edge/metrics_test.go
    - core-java/src/test/java/uk/jtoye/core/security/JwtTenantFilterMetricsTest.java
    - core-java/src/test/java/uk/jtoye/core/security/ManagementPortMetricsIntegrationTest.java
  modified:
    - edge-go/cmd/edge/main.go
    - infra/monitoring/prometheus/prometheus.yml
    - infra/monitoring/prometheus/alerts.yml
    - core-java/src/main/java/uk/jtoye/core/security/JwtTenantFilter.java
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java
    - core-java/src/main/resources/application-prod.yml
    - core-java/src/main/resources/application.yml
    - core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java
    - k8s/base/core-java-deployment.yaml
    - .github/workflows/ci-cd.yaml
    - scripts/smoke-test.sh
    - docs/metrics.json
    - docs/CHANGELOG.md
    - CLAUDE.md

key-decisions:
  - "Fixed the deprecated Spring Boot 2.x metrics-export property (management.metrics.export.prometheus.enabled) at the root cause in base application.yml — it is a NO-OP in Boot 3.5 and had left /actuator/prometheus unregistered (404) across all profiles"
  - "ManagementPortMetricsIntegrationTest runs untagged (H2, no Testcontainers) so it executes under the fast `test` task the plan's verify command uses; uses management.server.port=0 + random app port to avoid colliding with the cohabiting live stack's 9090/9091"
  - "SecurityConfig /actuator/prometheus permitAll made unconditional (dropped the !isProd gate) — the arbiter test proved the main SecurityFilterChain DOES apply to the separate management port, so this is required for the scrape to reach it; app-port anyRequest().authenticated() untouched"

patterns-established:
  - "Micrometer counter emission at natural detection points with null-safe ObjectProvider<MeterRegistry>, mirroring RateLimitInterceptor"
  - "Every LIVE Prometheus alert must reference an emitted metric; cluster-blocked rules disabled-with-note, not deleted"

requirements-completed: [ISSUE-98-P2-7-OBSERVABILITY-DO-NOW]

# Metrics
duration: 35min
completed: 2026-07-12
---

# Phase quick-260712-t6b Plan 01: #98 Observability do-now slice Summary

**Turned "demo-grade" observability into provable coverage: live edge /metrics endpoint, two real Micrometer counters behind reconciled alerts, prod Prometheus on an internal management port with aligned CI/CD+smoke, single-emit JSON logs, and a fix for a deprecated property that had left /actuator/prometheus dead in Boot 3.5.**

## Performance

- **Duration:** ~35 min
- **Started:** 2026-07-12T21:35Z
- **Completed:** 2026-07-12T22:06Z
- **Tasks:** 4 (Task 2 + Task 3 are TDD)
- **Files modified:** 17 (4 created, 13 modified)

## Accomplishments
- edge-go Gin gateway now serves GET /metrics (Prometheus exposition text) with `http_requests_total` + `http_request_duration_seconds`, route label = matched TEMPLATE only (cardinality/tenant-leak bounded); scrape job re-enabled.
- Two real Micrometer counters emitted at their natural detection points and proven by tests: `tenant.context.missing` (JwtTenantFilter) behind `TenantIsolationFailure`, `jtoye.payment.failed` (PaymentService) behind a new `PaymentFailureSpike` alert.
- `alerts.yml` reconciled — no live rule references a never-emitted metric (node-exporter DiskSpace rules disabled with a PENDING note).
- Prod `/actuator/prometheus` served only on an internal management port (9091, env-overridable); public app port exposes no actuator/metrics; k8s probes/annotation/containerPort aligned to 9091 (Service stays 9090-only); ci-cd prod+staging in-cluster health checks curl 9091; `scripts/smoke-test.sh` gains `EXPECT_PUBLIC_ACTUATOR` (default false = prod-safe).
- Fixed the prod JSON log pattern to emit each message once (nested `%replace`).
- Discovered + fixed a real bug: the deprecated Boot 2.x property `management.metrics.export.prometheus.enabled` is a NO-OP in Boot 3.5 — `/actuator/prometheus` was silently unregistered (404). Corrected to `management.prometheus.metrics.export.enabled` at the root (base application.yml) + prod.

## Task Commits

1. **Task 1: edge-go Prometheus /metrics endpoint + scrape job** - `c85b500` (feat)
2. **Task 2 (TDD RED): failing tenant-missing + payment-failed counter tests** - `67a2115` (test)
3. **Task 2 (TDD GREEN): emit counters + reconcile alerts + single-emit log** - `c7b4cc7` (feat)
4. **Task 3 (TDD): prod prometheus on management port + deploy-pipeline alignment** - `63b5b02` (feat)
5. **Task 4: docs resync + CHANGELOG + item-5 verification** - `6f4411c` (docs)

_Task 2 followed the RED → GREEN TDD gate (test commit then feat commit). Task 3's arbiter integration test drove RED (404) → GREEN and surfaced the deprecated-property bug; committed as one feat since its changes are config/infra-heavy._

## Files Created/Modified
- `edge-go/cmd/edge/metrics.go` - CounterVec + HistogramVec + prometheusMiddleware (FullPath route label) + promhttp handler
- `edge-go/cmd/edge/metrics_test.go` - /metrics returns 200 with http_requests_total; unmatched route collapses to "unmatched" (no raw-path leak)
- `edge-go/cmd/edge/main.go` - wire middleware early + public /metrics route
- `infra/monitoring/prometheus/prometheus.yml` - edge-go scrape job re-enabled; core-java target → :9091
- `infra/monitoring/prometheus/alerts.yml` - PaymentFailureSpike added, TenantIsolationFailure emission note, DiskSpace rules disabled (PENDING node-exporter)
- `core-java/.../security/JwtTenantFilter.java` - null-safe tenant.context.missing counter + increment on empty-tenant authenticated principal
- `core-java/.../payment/PaymentService.java` - null-safe jtoye.payment.failed counter + increment in handlePaymentIntentFailed
- `core-java/.../security/SecurityConfig.java` - /actuator/prometheus permitAll unconditional
- `core-java/src/main/resources/application-prod.yml` - management.server.port 9091 + prometheus exposure + nested single-emit log pattern + Boot 3.x prometheus property
- `core-java/src/main/resources/application.yml` - Boot 3.x prometheus metrics-export property (root-cause fix)
- `core-java/.../security/JwtTenantFilterMetricsTest.java` - counter increments on missing tenant, not on valid tenant
- `core-java/.../security/ManagementPortMetricsIntegrationTest.java` - prometheus 200 on mgmt port, not 200 on app port
- `k8s/base/core-java-deployment.yaml` - probes/annotation/containerPort → 9091, Service unchanged (9090)
- `.github/workflows/ci-cd.yaml` - prod health-check :9091, staging mirrored in-cluster check
- `scripts/smoke-test.sh` - EXPECT_PUBLIC_ACTUATOR gate (default false), test count preserved
- `docs/metrics.json`, `CLAUDE.md`, `docs/CHANGELOG.md` - resync + entry

## Decisions Made
- Root-caused the prometheus 404 to the deprecated property name and fixed it in base config (benefits all profiles), rather than a prod-only patch — issue #98 is literally about observability not actually working.
- Kept the arbiter test untagged/H2 so it runs under the required `test` task (the plan's verify command), booting the full context on the existing H2 test datasource.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Deprecated Spring Boot 2.x metrics-export property left /actuator/prometheus unregistered (404) in Boot 3.5**
- **Found during:** Task 3 (ManagementPortMetricsIntegrationTest arbiter run)
- **Issue:** The arbiter test returned 404 (not 401) for /actuator/prometheus on the management port. Probing showed the endpoint was never registered: base + prod + staging all used `management.metrics.export.prometheus.enabled` (Boot 2.x), a NO-OP in Boot 3.5, so the PrometheusMeterRegistry was never created. This meant prod's prometheus endpoint would 404 even after item 4 — defeating the whole slice.
- **Fix:** Corrected to the Boot 3.x property `management.prometheus.metrics.export.enabled` in `application.yml` (root cause, all profiles) and `application-prod.yml`. The test then passes without any test-only property override, so it also regression-guards the base config.
- **Files modified:** core-java/src/main/resources/application.yml, core-java/src/main/resources/application-prod.yml
- **Verification:** ManagementPortMetricsIntegrationTest (both assertions) green on the base-config fix alone; full :core-java:test suite green.
- **Committed in:** `63b5b02` (Task 3 commit)

**Note:** `application.yml` was not in the plan's Task 3 files_modified list; it was added under Rule 1 because item 4's goal (prod /actuator/prometheus reachable) is unachievable without it.

---

**Total deviations:** 1 auto-fixed (1 bug). **Impact on plan:** Necessary for correctness of item 4 — without it the entire prod metrics endpoint would 404. No scope creep beyond the observability surface.

## Issues Encountered
- The plan's verify command runs under the `test` Gradle task, which excludes `@Tag("testcontainers")`. Resolved by writing the arbiter test untagged (H2, RANDOM_PORT + random management port), booting on the existing `src/test/resources/application-test.yml` H2 datasource — confirmed the full context boots without Docker/localhost-Postgres (localhost:5432 was closed in this worktree).
- Management port is secured by the main SecurityFilterChain (arbiter probe showed /actuator index → 401), so the unconditional /actuator/prometheus permitAll is genuinely required for the scrape to reach it — validated by the test rather than assumed.

## Item 5 (verification-only)
Alertmanager SMTP receiver env-injection confirmed already satisfied — `infra/monitoring/docker-compose.monitoring.yml` injects the four `ALERTMANAGER_SMTP_*` vars with Mailhog defaults and `infra/monitoring/alertmanager/entrypoint.sh` applies the same defaults. No file change; no regression.

## User Setup Required
None - no external service configuration required. (Live prod-shaped scrape proof, Loki, trace collector, and node-exporter/DiskSpace wiring remain OUT OF SCOPE — issue #98 stays open for these cluster-blocked items.)

## Verification Summary
- edge-go: `go vet ./...` clean, `go test ./...` green (metrics test + full suite); /metrics → 200 with http_requests_total.
- core-java: `compileTestJava` clean (JwtTenantFilterTest + PaymentServiceTest updated for new constructors); full `:core-java:test` unit suite green; JwtTenantFilterMetricsTest, PaymentServiceTest (+counter), ManagementPortMetricsIntegrationTest all pass.
- alerts.yml + prometheus.yml valid YAML; no live rule references node_filesystem_*; both counters referenced.
- application-prod.yml: exactly one `%msg`; management.server.port set; exposure includes prometheus.
- k8s: `kubectl kustomize k8s/base|staging|production` build clean; probes/annotation/containerPort on 9091; Service 9090-only.
- ci-cd: no `localhost:9090/actuator` check remains (prod on 9091, staging mirrored).
- smoke-test.sh: `bash -n` clean; functionally validated against a prod-shaped stub — default posture 10/10 PASS (actuator not publicly exposed), EXPECT_PUBLIC_ACTUATOR=true flips the 4 actuator tests to FAIL, count preserved at 10.
- docs-freshness check-mode exits 0 (1192 total logical invocations).

## Self-Check: PASSED
- Created files verified present: edge-go/cmd/edge/metrics.go, metrics_test.go, JwtTenantFilterMetricsTest.java, ManagementPortMetricsIntegrationTest.java.
- Commits verified present: c85b500, 67a2115, c7b4cc7, 63b5b02, 6f4411c.

## Next Phase Readiness
- All five issue-#98 do-now items resolved; edge + core metrics now honestly scrapeable and prod-safe.
- Follow-ups (issue #98 remains open): node-exporter deploy → re-enable DiskSpace rules; Loki/log aggregation; trace collector + cross-service propagation; live prod-shaped scrape proof.

---
*Phase: quick-260712-t6b-98-observability-do-now-slice*
*Completed: 2026-07-12*
