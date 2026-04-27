# DevOps / SRE Audit
**Auditor persona**: SRE, on-call survivor, multi-tenant SaaS production
**Date**: 2026-04-27
**Production readiness score**: 6/10 — would I let this serve real customers tonight?

Verdict: there is a *lot* of right-shaped infra here (multi-stage builds, NetworkPolicies with default-deny, HPAs, PDBs, daily pg_dump CronJob, Sealed Secrets workflow, Trivy + Snyk + gitleaks in CI, rolling-update with rollback). It would not embarrass an SRE in a code review. But the moment you put it under live traffic, you discover several "ship-stoppers" hiding under tidy YAML — chiefly that **production has no metrics** despite a fully built Prometheus/Alertmanager stack, the **edge-go gateway has no `/metrics` endpoint at all**, **TenantContext is invisible in logs**, and **secrets are shaped for k8s but the dev-stack `.env` model leaks to operators**. None of these are unfixable in 1–2 weeks.

---

## Container & build

All three services have proper multi-stage Dockerfiles with non-root users and pinned base tags:

- `core-java/Dockerfile` — `eclipse-temurin:21-jre-alpine` runtime, dedicated `spring` UID, `MaxRAMPercentage=75` + G1GC, healthcheck via curl. Build stage skips tests (assumes CI gate). `EXPOSE 9090` is correct.
- `frontend/Dockerfile` — `node:20-alpine`, Next.js standalone output, `nextjs` UID 1001, healthcheck against `/api/health`. Builds with `NEXT_PUBLIC_API_URL` baked in at build time — that is correct for Next.js but means **per-environment images** unless you proxy via a single hostname.
- `edge-go/Dockerfile` — `golang:1.22-alpine` -> `FROM scratch`. Static binary, ~10–15MB. Cleanest of the three.

`.dockerignore` exists for all three and excludes IDE files, build artefacts, vendor dirs.

**Gaps**:
- All three images are tag-pinned (`21-jre-alpine`, `node:20-alpine`, `1.22-alpine`) but **not digest-pinned**. Supply-chain risk for an upstream tag rebuild.
- `core-java/Dockerfile:30` installs `curl` purely so the HEALTHCHECK can call itself — dead weight, since k8s probes hit the same endpoint anyway.
- No `USER` set in the `scratch` edge-go image — runs as root inside the container (no shell, but still UID 0). The k8s manifest fixes it (`runAsUser: 65534`), but if anyone runs the image without those securityContext settings they get root.
- No SBOM generated, no image signing (Sigstore/cosign).
- Dockerfiles don't `--chown` everything in the runner stage — frontend does, core-java does a separate `chown -R` (extra layer cost).

---

## Orchestration (Docker Compose / K8s)

### Docker Compose
`docker-compose.full-stack.yml` is honest about being for dev. Healthchecks are present on every service, `depends_on … condition: service_healthy` is wired up properly. Restart policy is `on-failure:5` for core-java, `on-failure:3` for keycloak — sensible. No CPU/memory limits anywhere except the Ollama GPU reservation. No secrets via `secrets:` block — everything is `.env`.

`infra/docker-compose.monitoring.yml` runs Prometheus/Grafana/Alertmanager/redis-exporter/postgres-exporter on the same network. This is dev-only and that's fine.

### Kubernetes (`k8s/`)
**Strong**:
- `k8s/base/core-java-deployment.yaml` is a textbook example: startupProbe (5min budget) **distinct from** liveness (`/actuator/health/liveness`) and readiness (`/actuator/health/readiness`), `preStop: sleep 10` for graceful shutdown, `podAntiAffinity` by hostname, RollingUpdate with `maxUnavailable: 0`, `securityContext` with `runAsNonRoot`, `allowPrivilegeEscalation: false`, all caps dropped.
- HPAs (`autoscaling/v2`) on all three services with sensible CPU/mem targets and a stabilisation window on scale-down.
- PodDisruptionBudgets on all three (`minAvailable: 2/3/2`).
- NetworkPolicies in `k8s/base/networkpolicies/` — explicit `00-default-deny.yaml` baseline plus per-tier allow lists; comment trail references threat model IDs (T-15-01..04). DNS rule scopes to `kube-dns`. Egress is correctly narrowed to 443 with private CIDRs excepted.
- Ingress (`k8s/base/ingress.yaml`) sets HSTS/X-Frame-Options/Referrer-Policy via configuration-snippet and uses cert-manager.

**Concerns**:
- `k8s/base/core-java-deployment.yaml:171` has `readOnlyRootFilesystem: false` with comment "Spring Boot needs write access" — Spring Boot does not need write access to root FS; pointing `LOG_PATH` and tmp at `emptyDir` volumes would let you flip this to `true`.
- `frontend-deployment.yaml:106` same problem (`readOnlyRootFilesystem: false`). Next.js standalone needs `/.next/cache` — again, an `emptyDir` would do.
- `k8s/production/kustomization.yaml` pins images to `v0.8.0` but `k8s/base/*-deployment.yaml` defaults are `2.0.0`. CI overrides via `kubectl set image` in `.github/workflows/ci-cd.yaml:288` so the file value is mostly cosmetic, but the staging overlay also needs verification.
- No `topologySpreadConstraints` — relying on `preferredDuringScheduling` anti-affinity, which the scheduler can ignore under pressure. In a real failure you may end up with all three core-java pods on one node.
- No PodSecurity admission labels on `k8s/base/namespace.yaml` (no `pod-security.kubernetes.io/enforce: restricted`). The deployments themselves are restricted-compliant, so adding the label is a one-liner enforcement upgrade.
- Datastores (Postgres, Redis, RabbitMQ, MinIO) are referenced as DNS in `jtoye-infrastructure` namespace but no manifests are checked in — they are assumed to exist out-of-band. That is OK if you use Cloud SQL / ElastiCache, but the repo doesn't say what the assumption is.

---

## Observability (metrics, traces, logs, alerts)

This is where the audit finds real production blockers.

### Metrics — broken in prod
- `core-java/src/main/resources/application.yml:101-118` exposes only `health,info` by default. `application-prod.yml:83-91` *also* limits to `health,info` with a comment saying "Prometheus scrape must go via a cluster-internal sidecar or dedicated metrics port". **No such sidecar exists in any k8s manifest.**
- `k8s/base/core-java-deployment.yaml:25-28` annotates `prometheus.io/scrape: "true"`, `prometheus.io/path: "/actuator/prometheus"`. The scrape will return 404 in production.
- `k8s/base/networkpolicies/20-core-java.yaml:48-57` allow Prometheus ingress on port 9090 to scrape `/actuator/prometheus`. Same dead end.
- The `BusinessMetricsService` (`core-java/.../config/BusinessMetricsService.java`) registers nice business counters (`jtoye.orders.created`, `jtoye.revenue.pennies`, `jtoye.payments.failed`, fulfilment timer). All invisible in prod.
- `edge-go/cmd/edge/main.go:160-161` only registers `/health` and `/ready`. No `/metrics` despite the deployment annotation `prometheus.io/scrape: "true"` and prometheus.yml having a `job_name: edge-go` scraping `/metrics`. **The Go gateway is completely unobserved.**

### Tracing
- `application.yml:119-124` configures Micrometer Tracing → Zipkin with 10% sampling (env-overridable). Logback pattern injects `traceId,spanId` into MDC. Good.
- Edge-go has **zero** tracing — no propagation, no exporter. Distributed traces stop at the Gin gateway, which means you can never correlate "slow customer request" through Edge → Core.

### Logs
- `application-prod.yml:73-76` defines a JSON-shaped console pattern with traceId/spanId. Not the cleanest approach (no `logstash-logback-encoder`), but works.
- **No `tenantId` in the MDC.** `JwtTenantFilter` populates `TenantContext` (a ThreadLocal) but never `MDC.put("tenantId", …)`. In a multi-tenant SaaS this is an on-call dealbreaker — when "Tenant Bob is seeing 500s", you cannot grep the logs.
- No correlation ID middleware on edge-go (logs use `zap` but no traceparent propagation).

### Alerts
- `infra/monitoring/prometheus/alerts.yml` has 14 rules across api/database/resource/business/infrastructure/messaging groups. Quality is solid — `HighErrorRate` (3% over 5m), `DatabaseConnectionPoolExhausted`, `TenantIsolationFailure` (security-flavoured, watching `tenant_context_missing_total` — a metric I cannot find anywhere in the Java code), `StompBrokerLag` for kitchen displays.
- The `TenantIsolationFailure` rule fires on `rate(tenant_context_missing_total[5m]) > 0.1` but no Java code increments any such counter. The rule will silently never fire. This is a paper alert.
- `NoOrdersCreated` over 30 minutes is an `info` severity alert that will spam the on-call channel after-hours. Either suppress between 22:00–08:00 or raise threshold.
- Alertmanager only routes to email (Mailhog by default). No PagerDuty/Opsgenie/Slack integration in `alertmanager.yml.tmpl` — fine for dev, not for prod.

### SLOs
None defined. No SLO YAML, no error-budget policy, no sloth/pyrra config.

---

## CI/CD

`.github/workflows/ci-cd.yaml` is comprehensive and largely well-shaped:

- Test job spins up Postgres 15 service, runs Java + Go + Frontend tests, validates edge-go OpenAPI spec.
- Security scan job runs Trivy filesystem scan + uploads SARIF, plus Snyk (with `continue-on-error: true` — that softens it to advisory).
- `gitleaks.yml` runs separately on PRs to main.
- Build/push job uses Docker Buildx, multi-arch (amd64+arm64), GHA cache, scans built images with Trivy, uploads SARIF.
- Deploys via `kubectl set image` and waits for `rollout status` with timeouts. Auto-rollback on failure. Slack notifications.

**Concerns**:
- **No SBOM generation** (`anchore/sbom-action` or syft). No image signing.
- Snyk is gated behind a token but failures are swallowed (`continue-on-error: true`). That means a HIGH CVE in a Gradle dep won't block a release.
- No staged `kubectl diff` step before `kubectl set image` — direct mutation against prod, and the kustomize tree isn't being applied (manifests in git and live cluster will drift).
- `kubectl set image` is imperative — Argo CD / Flux would give you reconciliation guarantees and an audit trail.
- Tests run in matrix-less single job; if a flaky frontend test fails, the whole pipeline fails. No retry, no test sharding.
- No load test in the pipeline. `infra/load-testing/` exists but is not invoked in CI.
- No DB migration verification step (Flyway `validate`).

---

## Secret management

- `.env` is gitignored (verified). `.env.example` and `.env.template` are checked in.
- `k8s/base/secrets-template.yaml` is explicit that it is a *legacy* plain-Secret template, with prominent banner pointing to Sealed Secrets in `docs/runbooks/sealed-secrets.md` and a `k8s/scripts/seal-secrets.sh` helper. That's the right shape.
- Production `core-java-deployment.yaml` reads from named Secrets (`postgres-credentials`, `rabbitmq-credentials`, `redis-credentials`, `nextauth-secret`, `keycloak-credentials`, STOMP creds split out separately). The split between ConfigMap (`app-config`) and Secret is disciplined.
- **Secret rotation story is undocumented.** No mention of rotation cadence, no External Secrets Operator config (only mentioned as an example comment in `secrets-template.yaml:90-105`), no rotation runbook. Rotating Postgres/Redis/RabbitMQ creds today means a manual `kubeseal` cycle and a rolling restart.
- Stripe API keys, ANTHROPIC_API_KEY, OLLAMA URL all flow through `.env` in dev; production K8s manifests don't appear to hold them at all (no `STRIPE_API_KEY` env in the prod deployment YAML). That suggests prod Stripe isn't actually wired.
- Backups directory `backups/` in the repo root contains 80+ `.sql.gz` files dating from Dec 2025 to Apr 2026 — these look real (time-series of dumps). **If those contain real tenant data they should not be in the repo.** Worth verifying.

---

## Scaling story

- Core-java is **stateless** by JWT design (`TenantContext` is per-request ThreadLocal cleared per filter). No `spring.session` config — no sticky sessions needed for REST.
- **WebSocket / STOMP is the wrinkle**: `application.yml` shows `STOMP_BROKER_MODE` (`in-memory` vs `relay`). The k8s configmap defaults to `relay` against RabbitMQ port 61613 — that's the right answer for horizontal scaling, since otherwise each replica would hold its own subscription set. PR #39 ("STOMP relay") suggests this was deliberately addressed. Good.
- SSE order stream: the dashboard hit recently (commit a8f61c2 — "fix(dashboard): authenticate orders SSE stream") suggests SSE is in the hot path. Behind nginx-ingress, **SSE will hold a connection per client** — `proxy_read_timeout: 60` (`ingress.yaml:40`) will close those after 60s. SSE clients should reconnect, but that produces a thundering herd every minute. Bump the timeout for the SSE path.
- DB read replicas: not configured. HikariCP single-pool, no `@Transactional(readOnly=true)` routing.
- HPA on edge-go scales 5–20 replicas at 60% CPU — generous, fine for an entry point.

---

## Backup & DR

- `k8s/base/pg-backup-cronjob.yaml` runs daily at 02:00 UTC, format=custom + gzip, uploads to S3, prunes >30 days, 30-min `activeDeadlineSeconds`. Solid, with one bug: `apk add aws-cli` runs *every* job — pin a base image with awscli baked in.
- **No verification step**. No `pg_restore --list` to prove the dump is restorable. "Backups are working" should mean "we restored last night's dump in a sandbox and it succeeded" — there is no such job.
- **No PITR / WAL archiving**. RPO is 24h worst case. For a payments-handling SaaS that's borderline negligent.
- **No MinIO backup at all.** All vendor product images live in MinIO and there is no replication, snapshot, or sync-to-S3-glacier. If MinIO loses its volume, every shop's product catalogue is gone.
- **No documented RTO/RPO** anywhere in `docs/`. No DR runbook, no game-day script.

---

## Top 5 strengths
1. **NetworkPolicies are real** — default-deny baseline plus per-tier allow-lists with documented threat-model rationale (`k8s/base/networkpolicies/`). Most projects this size ship without any.
2. **K8s probe hygiene is exemplary** — startup vs liveness vs readiness are correctly distinguished against Spring Boot's `/actuator/health/liveness` and `/readiness` (`k8s/base/core-java-deployment.yaml:138-159`).
3. **CI/CD pipeline has the right pieces wired together** — tests, Trivy fs+image, gitleaks, SBOM-less but multi-arch builds, auto-rollback, Slack on failure.
4. **Sealed Secrets workflow is documented and the migration path from plain Secrets is explicit** (`k8s/base/secrets-template.yaml` banner + `seal-secrets.sh`).
5. **Custom business metrics exist** at the code level (`BusinessMetricsService`) and alerts reference real concerns (HikariCP pool exhaustion, STOMP relay lag, tenant context missing). The shape is right.

## Top 5 concerns (severity)
1. **CRITICAL — Prometheus is dark in production.** `application-prod.yml:91` exposes only `health,info`; no sidecar exists. Every alert, every Grafana panel, every business metric is paper-only.
2. **CRITICAL — Edge-go has no `/metrics` endpoint at all** despite the entire scrape config and netpols expecting one. The Go gateway is completely unobserved.
3. **HIGH — Tenant ID is not in MDC / log lines.** On-call cannot grep logs for "what is tenant Bob seeing". `JwtTenantFilter` already has the value in hand — adding `MDC.put("tenantId", …)` is a 5-line change with massive ops payoff.
4. **HIGH — No backup restore verification + no MinIO backup at all.** RPO is 24h, RTO is unmeasured, product images are unrecoverable.
5. **MEDIUM — Snyk failures are swallowed (`continue-on-error: true`)** and there is no SBOM/image signing. Supply-chain posture is checkbox-grade.

## Pre-prod blocker list (would-not-ship items)
1. Flip `application-prod.yml` actuator exposure to `health,info,prometheus,metrics` **OR** wire a metrics sidecar — pick one and prove a Grafana dashboard renders end-to-end with real prod-like load.
2. Add a Prometheus `/metrics` endpoint to edge-go (`gin-gonic/gin-prometheus` or hand-rolled). Verify scrape works with the existing prometheus.yml job.
3. Add `MDC.put("tenantId", tenantId.toString())` in `JwtTenantFilter` (and `MDC.remove` in `finally`); update the JSON log pattern in `application-prod.yml` to include `"tenantId":"%X{tenantId:-}"`.
4. Verify `backups/jtoye_jtoye_*.sql.gz` files in the repo do not contain real tenant data; if they do, purge from history (BFG / git-filter-repo) and rotate any leaked credentials. Add `backups/` to `.gitignore`.
5. Verify the `tenant_context_missing_total` Counter is actually registered and incremented in code — currently the alert references a metric that does not exist.
6. Confirm Stripe production secret wiring — `STRIPE_API_KEY` is read by core-java but no prod K8s Secret/env exposes it.

## Day-2 ops gaps (ship-but-fix-soon)
1. Add Trivy/Snyk hard-fail on HIGH; generate SBOM (`anchore/sbom-action`); sign images with cosign.
2. Add an `argocd-application.yaml` (or Flux equivalent) — replace imperative `kubectl set image` with GitOps reconciliation.
3. Add edge-go OpenTelemetry tracing — propagate `traceparent`, export to the same Zipkin endpoint.
4. PodSecurity admission labels (`pod-security.kubernetes.io/enforce: restricted`) on the namespace.
5. Add a backup-restore CronJob that downloads yesterday's dump into a throwaway namespace and runs `pg_restore --list`. Alert on failure.
6. Add MinIO replication (mc mirror to S3 Glacier or second region).
7. Document RTO/RPO in `docs/runbooks/dr.md`. Run a tabletop DR exercise.
8. Bump `proxy_read_timeout` for the SSE path on the ingress (or split SSE traffic to a dedicated Ingress with longer timeout).
9. Add Postgres WAL archiving (pgBackRest or built-in `archive_command`) → PITR.
10. Add load test invocation in CI against staging post-deploy (`infra/load-testing/`).

## What I would do in the next 2 weeks before going live
1. **Day 1–2**: Fix the metrics blackout — flip prod actuator exposure (or sidecar), add edge-go `/metrics`, prove one Grafana dashboard end-to-end with real numbers from a load test against staging.
2. **Day 2–3**: Add `tenantId` to MDC + update JSON log pattern. Verify in staging by tailing logs while issuing requests as two tenants.
3. **Day 3–4**: Kill the dead `tenant_context_missing_total` alert OR implement the counter. Same audit pass against every alert in `alerts.yml` — every PromQL must reference a metric that actually exists.
4. **Day 4–5**: Audit `backups/*.sql.gz` in the repo. If real data, purge + rotate. Add `backups/` to `.gitignore`.
5. **Day 5–7**: Build backup-verify CronJob (restore yesterday's dump in a sandbox namespace, fail loudly). Add MinIO mirror to S3.
6. **Day 7–9**: Wire Snyk hard-fail + SBOM + cosign signing in CI. Add `pod-security.kubernetes.io/enforce: restricted` to the namespace.
7. **Day 9–11**: Move deployments to Argo CD (or at least kustomize-apply). Stop drift between git and cluster.
8. **Day 11–13**: Run a tabletop DR — kill the Postgres pod, restore from S3, measure RTO. Document it.
9. **Day 13–14**: Bump SSE/WebSocket ingress timeouts; load-test the kitchen-display STOMP path under realistic concurrency to confirm relay-mode actually scales.

If we do those nine items, score moves from 6/10 to a defensible 8.5/10 and I would happily take the pager.
