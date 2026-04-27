# DevOps / SRE Remediation — J'Toye OaaS

**Date**: 2026-04-27
**Pair**: SRE / Production Operator (specialist) + Reliability Reviewer (assistant)
**Scope**: 13 findings escalated from `docs/audit/sources/04-devops-sre.md`
**Source synthesis**: `docs/audit/COUNCIL-AUDIT-2026-04-27.md`

---

## Operating principles

1. **No alert that cannot page a real human.** Every PromQL must reference a metric that exists today, or one whose creation is itself tracked in this remediation.
2. **No new failure mode introduced by a fix.** Read-only root FS without `/tmp` `emptyDir` crashes Spring on first temp-file write. The assistant must catch these.
3. **Cost the rollback before the rollout.** Every production change has a "how do we back this out in <10 minutes" line.
4. **Pager-load discipline.** Each new alert needs tuning + suppression + a runbook entry.
5. **Honest effort sizing.** Argo CD is days. CosCron-job + cosign keyless are hours.
6. **Observability before features.** No new product code lands until F1 + F2 + F3 ship.
7. **Single source of truth for K8s state.** Either CI mutates and git drifts, or git is canonical. Pick one.

---

## Finding 1 — Production observability blackout (CRITICAL)

### Specialist proposal
`application-prod.yml:91` exposes only `health,info` despite `k8s/base/core-java-deployment.yaml:25-28` annotating Prometheus scrape against `/actuator/prometheus`, and `k8s/base/networkpolicies/20-core-java.yaml:48-57` whitelisting port 9090 from the monitoring namespace. The chain is wired; the endpoint returns 404.

Two paths: **(a)** flip exposure on port 9090 with NetworkPolicy + ingress block; **(b)** sidecar exposing `/actuator/prometheus` on a separate port.

Pick **(a)** — the metric data is materialised by the JVM either way; a sidecar adds memory cost (~80 MiB) per replica without isolation gain. Existing NetworkPolicy already restricts port 9090 ingress to the monitoring namespaces.

```yaml
# application-prod.yml:83-91 diff
 management:
   endpoints:
     web:
       exposure:
-        include: health,info
+        # Scrape protected by NetworkPolicy 20-core-java and by ingress block (below)
+        include: health,info,prometheus,metrics
       base-path: /actuator
```

Add explicit deny at the public ingress (`k8s/base/ingress.yaml:29-44` configuration-snippet):

```yaml
    nginx.ingress.kubernetes.io/configuration-snippet: |
      location ^~ /actuator { return 404; }
      more_set_headers "X-Frame-Options: DENY";
      # ... existing headers ...
```

Grafana dashboards: only `infra/monitoring/grafana/dashboards/stomp-dashboard.json` exists today. Create:
- `core-java-overview.json` — error rate, p95 latency, JVM heap, HikariCP pool, GC pause.
- `business-metrics.json` — `jtoye_orders_created_total`, `jtoye_orders_completed_total`, `jtoye_revenue_pennies_total`, `jtoye_payments_failed_total` (verified registered at `core-java/src/main/java/uk/jtoye/core/config/BusinessMetricsService.java:23-37`).

Verify: port-forward Prometheus, `curl 'http://localhost:9090/api/v1/query?query=up{job="core-java"}'` → `value: [_, "1"]`. Run synthetic load from `infra/load-testing/`, confirm both dashboards render non-zero for ≥5 min. Confirm `https://api.jtoye.co.uk/actuator/prometheus` returns 404 from outside.

### Assistant deliberation
1. **Cardinality.** Spring's `http_server_requests_seconds_*` tags by `uri`, `method`, `status`. With dynamic paths like `/orders/{id}`, the `uri` tag explodes if Spring is tagging resolved URIs not URI templates. Must verify `WebMvcMetricsFilter` uses templates — otherwise enabling the endpoint creates a million-series timeseries within a week.
2. **The ingress block IS necessary.** `pathType: Prefix` with `path: /` (verified `k8s/base/ingress.yaml:54-63`) means `https://api.jtoye.co.uk/actuator/prometheus` would route through today and dump metrics publicly without the explicit `location` deny.
3. **Sidecar's only real benefit** would be mTLS scrape under a service mesh. No mesh on the roadmap → (a) is correct for current trajectory.

### Reconciled position
Adopt (a) plus: (i) the nginx `location ^~ /actuator { return 404; }` block, (ii) `WebMvcConfigurer` audit confirming URI templates are tagged not raw paths, (iii) the two Grafana dashboards. Verification gate: 10 min synthetic staging load with both dashboards rendering non-zero AND public `/actuator/prometheus` returning 404. **Rollback**: revert the include line. **Effort**: 1 day.

---

## Finding 2 — edge-go has no `/metrics` endpoint

### Specialist proposal
`edge-go/cmd/edge/main.go:160-161` registers only `/health` and `/ready`. The k8s deployment annotates `prometheus.io/scrape: "true"` on port 8080 (`k8s/base/edge-go-deployment.yaml:25-28`) and `infra/monitoring/prometheus/prometheus.yml:46-58` defines a scrape job — both silently fail.

Coordination with pair 07: even if absorb is approved, that is multi-week work. Patch metrics now to avoid running blind in the meantime.

Hand-rolled middleware in `edge-go/internal/metrics/metrics.go` exposing `edge_http_request_duration_seconds` (histogram by `method`, `path`=`c.FullPath()`, `status`), `edge_rate_limit_rejected_total` counter, `edge_circuit_breaker_state` gauge labelled by name. Register `/metrics` with `promhttp.Handler()` in `main.go` before the protected route group. Increment `edge_rate_limit_rejected_total` in the `default:` branch of `rateLimiter()` (`main.go:104-107`). Update `breakerState` from `core/client.go` whenever gobreaker state changes.

### Assistant deliberation
1. **Library choice.** `zsais/go-gin-prometheus` is unmaintained since 2022. For three custom series, hand-roll against `prometheus/client_golang` directly — saves a transitive dep on dead code and isolates the kill switch if pair 07 absorbs the service.
2. **Cardinality.** Gin returns `""` for unmatched routes; under path-fuzzing all 404s land in `""` — bounded.
3. **Coordination ordering.** If pair 07 absorbs in the same wave, this work is wasted. Hand-rolled keeps deletion to one `git rm`.

### Reconciled position
Hand-rolled metrics middleware (~50 LOC, no third-party dep). **Block on pair 07's absorb decision** for whether this is a 2-month bridge or permanent. **Rollback**: delete the package + import. **Effort**: 4 hours.

---

## Finding 3 — Tenant ID missing from MDC / log lines

### Specialist proposal
`core-java/src/main/java/uk/jtoye/core/security/JwtTenantFilter.java:40-43` calls `TenantContext.set(...)` but never `MDC.put`. The JSON log pattern at `application-prod.yml:75-76` already emits `traceId`/`spanId`; adding `tenantId` is a one-liner.

```java
// JwtTenantFilter.java — replace doFilterInternal body
try {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
        Optional<UUID> jwtTenant = extractTenant(jwt);
        if (jwtTenant.isPresent()) {
            TenantContext.set(jwtTenant.get());
            MDC.put("tenantId", jwtTenant.get().toString());
            log.debug("Set tenant context from JWT: {}", jwtTenant.get());
        }
    }
    filterChain.doFilter(request, response);
} finally {
    MDC.remove("tenantId");
    // TenantFilter still clears TenantContext at end of request.
}
```

```yaml
# application-prod.yml:75-76 diff — append ,"tenantId":"%X{tenantId:-}"
   pattern:
     console: >-
       {"timestamp":"...","level":"%level",...,"traceId":"%X{traceId:-}","spanId":"%X{spanId:-}","tenantId":"%X{tenantId:-}"}%n
```

For `@Async`: cross-reference backend pair 01's `TenantAwareTaskDecorator` — it must propagate both `TenantContext` and MDC. Spring's `MdcTaskDecorator` pattern is canonical. For SSE/STOMP: after pair 01 captures `tenantId` at `OrderSseService` subscribe(), the broadcast loop must `MDC.put` before each emitter and `MDC.clear()` after.

### Assistant deliberation
1. **MDC clearing semantics.** Tomcat reuses worker threads. The `finally` block above always runs even if `MDC.put` was skipped — safe.
2. **Indexing cardinality.** `tenantId` field cardinality = number of tenants. Fine at 10–100. At 10k it is a license cost — flag in runbook.
3. **Tracer interaction.** Micrometer Tracing manages `traceId`/`spanId` MDC; `tenantId` does not collide. If team later swaps to OTel auto-instrumentation, verify the propagator does not call `MDC.clear()` mid-request.

### Reconciled position
Ship the filter diff + log pattern diff + `MdcTaskDecorator` (with backend pair 01) + MDC scoping in `OrderSseService.broadcast` (with backend pair 01 SSE fix). Add a runbook line on cardinality cost at scale. **Rollback**: revert filter and pattern. **Effort**: 4 hours code + 4 hours regression test = 1 day.

---

## Finding 4 — Dead alert rules

### Specialist proposal
Verified `infra/monitoring/prometheus/alerts.yml:142` references `tenant_context_missing_total`. `grep -rn "tenant_context_missing" core-java/src/` returns zero hits. Rule is paper-only.

Audit of all 14 rules in `alerts.yml`: rules at lines 7, 24, 36, 54, 69, 81, 96, 111, 126, 156, 172, 188, 200, 218 reference real metrics from Spring Boot Actuator, postgres-exporter, node-exporter, RabbitMQ exporter, or Prometheus defaults. **Only line 141-150 (`TenantIsolationFailure` / `tenant_context_missing_total`) is dead.**

Two fixes:

**(a) Implement the counter.** Renamed to `jwt_tenant_claim_missing_total` to set honest expectations (this only catches the JWT-authn-but-no-tenant-claim path, NOT a true tenancy bypass).

```java
// JwtTenantFilter — register a counter via constructor injection of MeterRegistry
private final Counter jwtTenantClaimMissing;
public JwtTenantFilter(MeterRegistry registry) {
    this.jwtTenantClaimMissing = Counter.builder("jwt_tenant_claim_missing_total")
        .description("Authenticated requests with no extractable tenant claim")
        .register(registry);
}
// Increment site: when auth.getPrincipal() instanceof Jwt but extractTenant() returns Optional.empty()
```

```yaml
# alerts.yml:141-150 diff — rename + new metric
- alert: JwtTenantClaimMissing
  expr: rate(jwt_tenant_claim_missing_total[5m]) > 0.1
  for: 2m
  labels: { severity: critical, component: security, service: core-java }
  annotations:
    summary: "Authenticated requests with no extractable tenant claim"
    description: "{{ $value }} req/s authenticated but missing tenant claim — likely Keycloak mapper regression"
```

**(b) Suppress `NoOrdersCreated` overnight (UK timezone)** via Alertmanager `time_intervals` (v0.22+):

```yaml
# alertmanager.yml additions
time_intervals:
  - name: overnight-uk
    time_intervals:
      - times: [{ start_time: '00:00', end_time: '08:00' }]
        location: Europe/London

route:
  routes:
    - matchers: [alertname="NoOrdersCreated"]
      mute_time_intervals: [overnight-uk]
      receiver: business-info
```

### Assistant deliberation
1. **The renamed counter is a misconfig signal, not a tenancy-violation signal.** The "code path bypassed tenancy" detector requires pair 02's method-level authorization work. Be honest in the alert name. Specialist concedes — hence the rename.
2. **Overnight mute is a one-way door for 8 hours.** True outages are still caught by `HighErrorRate`, `ServiceDown`. Approved for this low-signal info alert.
3. **Counter placement.** Wire from `JwtTenantFilter` directly, not `BusinessMetricsService` — the file already owns the increment site, no concern-mixing.

### Reconciled position
Three deliverables: (i) register `jwt_tenant_claim_missing_total` in `JwtTenantFilter` and increment when JWT auth succeeds but tenant claim extraction returns empty; (ii) update alert name + PromQL in `alerts.yml`; (iii) Alertmanager `time_intervals` mute on `NoOrdersCreated` 00:00–08:00 Europe/London. **Open dependency on pair 02** for true tenancy-bypass detection. **Rollback**: revert alert + counter (alert was dead anyway). **Effort**: 4 hours.

---

## Finding 5 — Backup verification CronJob

### Specialist proposal
The dump CronJob (`k8s/base/pg-backup-cronjob.yaml`) writes daily at 02:00 UTC with no proof of restorability. Daily verify CronJob at 04:00 UTC pulls yesterday's dump, runs `pg_restore --list`, schema-only restore, table-presence smoke. Weekly deep-verify (Sunday) with full data restore + transaction-count smoke.

```yaml
apiVersion: batch/v1
kind: CronJob
metadata: { name: pg-backup-verify, namespace: jtoye-production }
spec:
  schedule: "0 4 * * *"
  concurrencyPolicy: Forbid
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 7
  jobTemplate:
    spec:
      backoffLimit: 1
      activeDeadlineSeconds: 1200
      template:
        spec:
          restartPolicy: OnFailure
          securityContext: { runAsNonRoot: true, runAsUser: 1000, fsGroup: 1000 }
          containers:
            - name: verify
              image: ghcr.io/jtoye/pg-backup-tools:1.0  # baked aws-cli + postgres-client-15
              command: ["/bin/sh", "-c"]
              args:
                - |
                  set -euo pipefail
                  YDAY=$(date -u -d "yesterday" +%Y%m%d)
                  FILE=$(aws s3 ls "s3://${S3_BUCKET}/backups/" --endpoint-url "${S3_ENDPOINT}" \
                    | awk '{print $4}' | grep "${YDAY}" | head -1)
                  [ -n "${FILE}" ] || { echo "FAIL: no backup ${YDAY}"; exit 1; }
                  aws s3 cp "s3://${S3_BUCKET}/backups/${FILE}" "/tmp/${FILE}" --endpoint-url "${S3_ENDPOINT}"
                  gunzip "/tmp/${FILE}"; RAW="/tmp/${FILE%.gz}"
                  TOC_LINES=$(pg_restore --list "${RAW}" | wc -l)
                  [ "${TOC_LINES}" -ge 50 ] || { echo "FAIL: TOC=${TOC_LINES}"; exit 1; }
                  psql "${EPHEMERAL_DB_URL}" -c "DROP DATABASE IF EXISTS verify; CREATE DATABASE verify;"
                  pg_restore --schema-only -d "${EPHEMERAL_DB_URL%/*}/verify" "${RAW}"
                  for tbl in tenants shops orders products customers financial_transactions; do
                    psql "${EPHEMERAL_DB_URL%/*}/verify" -tAc "SELECT to_regclass('public.${tbl}')" \
                      | grep -q "${tbl}" || { echo "FAIL: missing ${tbl}"; exit 1; }
                  done
                  echo "OK: ${FILE} verified"
              env:
                - { name: S3_BUCKET,             valueFrom: { configMapKeyRef: { name: jtoye-config,  key: s3-bucket } } }
                - { name: S3_ENDPOINT,           valueFrom: { configMapKeyRef: { name: jtoye-config,  key: s3-endpoint } } }
                - { name: AWS_ACCESS_KEY_ID,     valueFrom: { secretKeyRef: { name: jtoye-secrets, key: s3-access-key } } }
                - { name: AWS_SECRET_ACCESS_KEY, valueFrom: { secretKeyRef: { name: jtoye-secrets, key: s3-secret-key } } }
                - { name: AWS_DEFAULT_REGION,    value: eu-west-2 }
                - { name: EPHEMERAL_DB_URL,      value: "postgresql://verify:verify@pg-verify.jtoye-backup-verify.svc.cluster.local:5432/postgres" }
              resources: { requests: { memory: 512Mi, cpu: 500m }, limits: { memory: 1Gi, cpu: 1000m } }
```

Plus an alert (requires kube-state-metrics in the scrape config):

```yaml
- alert: BackupVerifyFailed
  expr: kube_job_status_failed{job_name=~"pg-backup-verify-.*"} > 0
  for: 10m
  labels: { severity: critical, component: database }
  annotations: { summary: "pg_restore verify failed for {{ $labels.job_name }}" }
```

### Assistant deliberation
1. **Throwaway namespace overhead** — keep an always-on `pg-verify` StatefulSet (1 replica, 1Gi PVC); CronJob does `DROP DATABASE; CREATE DATABASE` per run. Lower flakiness than fresh pods.
2. **`kube_job_status_failed` requires kube-state-metrics**, NOT in `prometheus.yml` today. Hard prerequisite.
3. **Schema-only is necessary but not sufficient.** Add weekly `pg-backup-verify-deep` doing full restore + `SELECT count(*) FROM financial_transactions WHERE created_at::date = yesterday` smoke.

### Reconciled position
Two CronJobs (daily schema-only + weekly deep), always-on `pg-verify` STS, kube-state-metrics install, custom `pg-backup-tools` image (also fixes the dump CronJob's `apk add` overhead). **Rollback**: delete CronJobs + STS. **Effort**: 2 days.

---

## Finding 6 — MinIO replication

### Specialist proposal
Vendor product images live in MinIO; zero backup. Three options: **(a)** `mc mirror` to S3 Glacier, **(b)** MinIO bucket-replication to a second cluster, **(c)** move off MinIO to native S3.

Pick **(a)** for now, **(c)** before scale. (b) does not protect against logical corruption replicated to both clusters.

```yaml
apiVersion: batch/v1
kind: CronJob
metadata: { name: minio-mirror-glacier, namespace: jtoye-production }
spec:
  schedule: "30 2 * * *"  # post pg-backup
  concurrencyPolicy: Forbid
  jobTemplate:
    spec:
      activeDeadlineSeconds: 7200
      template:
        spec:
          restartPolicy: OnFailure
          containers:
            - name: mc
              image: minio/mc:RELEASE.2025-01-17T01-26-43Z
              command: ["/bin/sh", "-c"]
              args:
                - |
                  set -euo pipefail
                  mc alias set src "${MINIO_ENDPOINT}" "${MINIO_ACCESS_KEY}" "${MINIO_SECRET_KEY}"
                  mc alias set dst "https://s3.${AWS_REGION}.amazonaws.com" "${AWS_ACCESS_KEY_ID}" "${AWS_SECRET_ACCESS_KEY}"
                  # NOTE: --remove deliberately OMITTED. Glacier is append-only trust copy.
                  mc mirror --overwrite src/jtoye-products dst/jtoye-products-glacier --storage-class GLACIER_IR
              env:
                - { name: MINIO_ENDPOINT,   valueFrom: { configMapKeyRef: { name: jtoye-config,  key: minio-endpoint } } }
                - { name: MINIO_ACCESS_KEY, valueFrom: { secretKeyRef: { name: jtoye-secrets, key: minio-access-key } } }
                - { name: MINIO_SECRET_KEY, valueFrom: { secretKeyRef: { name: jtoye-secrets, key: minio-secret-key } } }
                - { name: AWS_ACCESS_KEY_ID,     valueFrom: { secretKeyRef: { name: jtoye-secrets, key: backup-aws-access } } }
                - { name: AWS_SECRET_ACCESS_KEY, valueFrom: { secretKeyRef: { name: jtoye-secrets, key: backup-aws-secret } } }
                - { name: AWS_REGION, value: eu-west-2 }
```

Cost: Glacier Instant Retrieval ~$4/TB-mo + $0.01/GB egress. Deep Archive $0.99/TB-mo + $0.02/GB egress (12h retrieval). At MVP scale (<10 GB) cost is <£0.05/mo. At 1000 vendors × 10 GB (10 TB), Deep Archive is ~£10/mo.

### Assistant deliberation
1. **`--remove` is destructive.** Drop it — Glacier should be append-only trust copy. If MinIO bug empties a bucket, mirror with `--remove` destroys our backup. Already dropped above.
2. **Bucket-replication option (b) is wrong here** — replicates between clusters, no protection against logical corruption. Concur with (a).
3. **Untested backup is not a backup.** Quarterly manual `mc cp` of one object out of Glacier to verify byte-match. Runbook entry, not CronJob (Glacier retrieval cost makes daily probing wasteful).

### Reconciled position
Adopt (a) without `--remove`. Enable S3 bucket versioning on the Glacier target for accidental-overwrite recovery. Quarterly manual restore-test runbook. **Tag option (c) as a Day-2 strategic decision** tied to pair 07 — if we drop edge-go we should also evaluate dropping self-hosted MinIO. **Rollback**: delete CronJob; Glacier objects remain. **Effort**: 1 day.

---

## Finding 7 — WAL archiving / PITR

### Specialist proposal
RPO is 24h with daily `pg_dump`. For payment processing this is borderline negligent.

**(a) Self-managed PITR via `archive_command` + `pgbackrest`** — RPO ~60s, RTO 10–30 min, ongoing operational tax.
**(b) Move to managed Postgres (RDS / Cloud SQL / Azure DB)** — RPO ≤5 min and PITR for free, ~£300–800/mo for Multi-AZ.

Specialist picks **(b)**: self-hosting Postgres on K8s burns multi-person-days/quarter (failover, version upgrades, storage resizing, backup tooling); RDS Multi-AZ failover is automatic in <60s; the audit/regulatory story is cleaner.

### Assistant deliberation
1. **TCO honesty.** £300/mo RDS vs ~£0 marginal self-hosted PG, but at £400/day blended cost the 4 person-days/quarter of self-hosted ops is £533/mo-equivalent. RDS wins on TCO once human time is honestly costed.
2. **Lock-in.** No AWS-only extensions in use; migration off RDS is a `pg_dump`/`pg_restore`. Low risk.
3. **The honest blocker.** This depends on where production K8s actually lives. Not stated in the repo. If non-AWS, RDS is unreachable without VPN + cross-cloud egress.

### Reconciled position
**Open question for the founder**: where does prod K8s live?
- **Path A (EKS / GKE / AKS)**: migrate to managed PG. RPO ≤5 min, RTO ≤15 min. Effort 3–5 days (logical replication migration + secrets cutover + smoke).
- **Path B (non-cloud K8s)**: implement `pgbackrest` to S3, WAL streaming every 60s. RPO ≤60s, RTO ≤30 min. Effort 2 days.

Either way, write `docs/runbooks/dr.md` with RTO/RPO targets and run a tabletop DR exercise before going live.

---

## Finding 8 — Argo CD vs imperative `kubectl set image`

### Specialist proposal
`.github/workflows/ci-cd.yaml:288-311` mutates production via `kubectl set image`. The image tag in `k8s/production/kustomization.yaml:21-26` (`v0.8.0`) is decoration; CI overwrites it. Cluster and git silently diverge.

Argo CD app-of-apps shape (`k8s/argocd/root-app.yaml` + per-env Applications under `k8s/argocd/apps/`):

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata: { name: jtoye-production, namespace: argocd }
spec:
  project: default
  source:
    repoURL: https://github.com/Bralabee/JToye_OaaS_2026
    targetRevision: main
    path: k8s/production
  destination: { server: https://kubernetes.default.svc, namespace: jtoye-production }
  syncPolicy:
    automated: { prune: true, selfHeal: true }
    syncOptions: [CreateNamespace=false, PrunePropagationPolicy=foreground]
```

CI changes from `kubectl set image` to: build image, write new tag into `k8s/production/kustomization.yaml`, commit, push. Argo reconciles.

### Assistant deliberation
1. **Effort honesty.** Argo install + RBAC + ingress + OIDC + secret cycling for git access + app-of-apps tree + CI rewrite + branch protection = realistically **3–5 days**.
2. **Drift-detection-only as pragmatic interim** — daily `kubectl diff -k k8s/production/` cron, alert Slack on non-empty diff. ~2h, detection without cure.
3. **Rollback under Argo with `selfHeal: true`** auto-reverts manual `kubectl rollout undo` within seconds. Incident response becomes "git revert + push" — slower; runbook update mandatory.
4. **At 0 customers, Argo migration is yak-shaving** — defer until customer #10 per council strategy.

### Reconciled position
**Two-stage adoption**:
- **Stage 1 (now, ~2h)**: `.github/workflows/k8s-drift-check.yaml` running `kubectl diff -k k8s/production/` daily, alerting Slack on non-empty diff.
- **Stage 2 (post first 10 customers, 3–5 days)**: Argo CD migration as above. Keep the imperative CI job commented in the workflow file for 30 days post-cutover for emergency rollback.

---

## Finding 9 — CI hardening

### Specialist proposal
`.github/workflows/ci-cd.yaml:138-145`: Snyk runs with `continue-on-error: true`. No SBOM, no image signing.

```yaml
# Diff to .github/workflows/ci-cd.yaml security-scan job
      - name: Run Snyk security scan
        uses: snyk/actions/gradle@master
-        continue-on-error: true
        env:
          SNYK_TOKEN: ${{ secrets.SNYK_TOKEN }}
        with:
          command: test
          args: --all-projects --severity-threshold=high

# Diff to build-and-push job — add after build-push step
      - name: Generate SBOM (Syft)
        uses: anchore/sbom-action@v0
        with:
          image: ${{ fromJSON(steps.meta.outputs.json).tags[0] }}
          format: spdx-json
          output-file: sbom-${{ matrix.service }}.spdx.json
          upload-artifact: true

      - name: Install Cosign
        uses: sigstore/cosign-installer@v3

      - name: Sign image (keyless OIDC)
        env: { COSIGN_EXPERIMENTAL: "1" }
        run: cosign sign --yes ${{ fromJSON(steps.meta.outputs.json).tags[0] }}

      - name: Attach SBOM as attestation
        env: { COSIGN_EXPERIMENTAL: "1" }
        run: |
          cosign attest --yes \
            --predicate sbom-${{ matrix.service }}.spdx.json \
            --type spdxjson \
            ${{ fromJSON(steps.meta.outputs.json).tags[0] }}
```

Stage 2: cluster-side admission (Kyverno or Sigstore policy-controller) requiring `cosign verify` on all `jtoye-production` images.

### Assistant deliberation
1. **Snyk hard-fail will block tomorrow's deploy** — there WILL be HIGH CVEs in transitive Gradle deps. Add a `.snyk` ignore-file with explicit, time-boxed exceptions (each entry expires in 30 days). Day-1 backlog of 5–15 entries.
2. **Cosign keyless OIDC token** is only available on `push` events from the repo, not `pull_request` from forks. Already gated by the build-push job's `if:` clause.
3. **Signing without verification is theatre.** Stage 2 (Kyverno admission policy) is the actual security gain — must be in scope, even if deferred.

### Reconciled position
- **Stage 1 (~1 day)**: drop `continue-on-error`, add `.snyk` exception backlog, add SBOM step, add cosign sign step.
- **Stage 2 (~1 day, gated on Stage 1 stable)**: Kyverno install + `verify-signature` policy targeting `ghcr.io/jtoye/*` with the GitHub OIDC issuer. Run in audit mode for 1 week before flipping to enforce.

**Rollback Stage 1**: revert workflow YAML. Stage 2: delete the Kyverno policy.

---

## Finding 10 — PodSecurity admission + readOnlyRootFilesystem

### Specialist proposal
`k8s/base/namespace.yaml` (verified) has no PodSecurity labels.

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: jtoye-production
  labels:
    name: jtoye-production
    environment: production
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/enforce-version: latest
    pod-security.kubernetes.io/audit: restricted
    pod-security.kubernetes.io/warn: restricted
```

Flip `readOnlyRootFilesystem` with `emptyDir` mounts:

```yaml
# k8s/base/core-java-deployment.yaml:164-171 + add volumeMounts/volumes
        securityContext:
          runAsNonRoot: true
          runAsUser: 1000
          allowPrivilegeEscalation: false
          capabilities: { drop: [ALL] }
+          readOnlyRootFilesystem: true
+          seccompProfile: { type: RuntimeDefault }
+        volumeMounts:
+          - { name: tmp,          mountPath: /tmp }
+          - { name: logs,         mountPath: /var/log/jtoye }
+          - { name: spring-work,  mountPath: /workspace/tmp }
+      volumes:
+        - { name: tmp,         emptyDir: { sizeLimit: 256Mi } }
+        - { name: logs,        emptyDir: { sizeLimit: 1Gi } }
+        - { name: spring-work, emptyDir: { sizeLimit: 256Mi } }
```

Same shape for frontend (`/.next/cache` + `/tmp`):

```yaml
# k8s/base/frontend-deployment.yaml:99-106
        securityContext:
          ...
+          readOnlyRootFilesystem: true
+          seccompProfile: { type: RuntimeDefault }
+        volumeMounts:
+          - { name: tmp,        mountPath: /tmp }
+          - { name: next-cache, mountPath: /app/.next/cache }
+      volumes:
+        - { name: tmp,        emptyDir: { sizeLimit: 64Mi } }
+        - { name: next-cache, emptyDir: { sizeLimit: 512Mi } }
```

edge-go already has `readOnlyRootFilesystem: true` (verified `k8s/base/edge-go-deployment.yaml:90`). Add `seccompProfile: { type: RuntimeDefault }` for PSA compliance.

### Assistant deliberation
1. **The crash fix.** Spring writes Tomcat session temp files, JVM `hsperfdata`, log appender. `application-prod.yml:78` resolves `${LOG_PATH:/var/log/jtoye}`. Match against the proposed `logs` mount: confirmed.
2. **`-Djava.io.tmpdir`.** Hibernate/JPA spawns temp files for large query results. Verify `core-java/Dockerfile` JVM args point at `/workspace/tmp` — add if missing.
3. **PSA `restricted` requires `seccompProfile: RuntimeDefault`** — without it the label flip rejects pods at admission. Specialist's diff missed this on first pass; corrected above.

### Reconciled position
Ship with `seccompProfile`, `LOG_PATH` audit, and JVM `-Djava.io.tmpdir` audit. **Staged rollout** — apply PSA label as `audit`/`warn` for 1 week first, then flip to `enforce`. Same for `readOnlyRootFilesystem` — staging for 48h, then production. **Rollback**: flip back to `false`, drop PSA `enforce`. **Effort**: 1 day code + 1 week soak.

---

## Finding 11 — SSE ingress timeout

### Specialist proposal
`k8s/base/ingress.yaml:40` sets `proxy_read_timeout: 60`. The dashboard SSE order stream (recent commit `0d6f863`) holds connections per client; nginx kills every 60s → thundering-herd reconnect. STOMP relay is already live (`k8s/base/configmap.yaml:27-29` — `stomp.broker.mode: relay`).

**(a) Long-timeout dedicated SSE Ingress.**
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: jtoye-ingress-sse
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-buffering: "off"  # critical for SSE
spec:
  ingressClassName: nginx
  tls: [{ hosts: [api.jtoye.co.uk], secretName: jtoye-tls }]
  rules:
    - host: api.jtoye.co.uk
      http:
        paths:
          - path: /api/v1/orders/sse
            pathType: Prefix
            backend: { service: { name: core-java, port: { number: 9090 } } }
```

**(b) Deprecate SSE entirely** — STOMP relay covers it. Council blocker #1 (SSE cross-tenant leak) is being fixed by pair 01 anyway; if pair 01 elects removal, this finding closes.

Specialist prefers **(b)**.

### Assistant deliberation
1. **STOMP via SockJS adds ~30 KB to dashboard bundle** — negligible for an internal vendor dashboard.
2. **Authn for STOMP** — pair 01 already solved authenticated streaming in commit `0d6f863`; that work mostly transfers.
3. **`proxy_buffering: off` is non-negotiable for path (a)** — without it nginx buffers SSE bytes for 60s and the client receives nothing.

### Reconciled position
**Defer to backend pair 01's SSE decision.** If they deprecate SSE → this finding closes for free. If they retain SSE → ship `jtoye-ingress-sse` with 1h timeout + `proxy_buffering: off`. **Rollback (a)**: `kubectl delete ingress jtoye-ingress-sse`. **Effort**: 2h or 0h.

---

## Finding 12 — Stripe production secret wiring

### Specialist proposal
Verified `core-java/src/main/resources/application.yml:164` reads `STRIPE_API_KEY`. `k8s/base/core-java-deployment.yaml` env block (verified lines 51-130) does NOT include any Stripe vars. **Production Stripe is not wired** — payments are either not happening, or silently failing.

```yaml
# k8s/base/core-java-deployment.yaml — append to env: list after line 130
        - name: STRIPE_API_KEY
          valueFrom: { secretKeyRef: { name: stripe-credentials, key: api-key } }
        - name: STRIPE_WEBHOOK_SECRET
          valueFrom: { secretKeyRef: { name: stripe-credentials, key: webhook-secret } }
        - name: STRIPE_PUBLISHABLE_KEY
          valueFrom: { secretKeyRef: { name: stripe-credentials, key: publishable-key } }
```

```yaml
# k8s/base/sealed-secrets/stripe-credentials.yaml — TEMPLATE; run kubeseal
apiVersion: bitnami.com/v1alpha1
kind: SealedSecret
metadata: { name: stripe-credentials, namespace: jtoye-production }
spec:
  encryptedData:
    api-key:         <REPLACE_WITH_KUBESEAL_OUTPUT>
    webhook-secret:  <REPLACE_WITH_KUBESEAL_OUTPUT>
    publishable-key: <REPLACE_WITH_KUBESEAL_OUTPUT>
  template:
    metadata: { name: stripe-credentials, namespace: jtoye-production }
    type: Opaque
```

`docs/runbooks/sealed-secrets.md` already documents the `kubeseal` workflow.

### Assistant deliberation
1. **Webhook secret rotation** — Stripe supports multiple active secrets during rotation; our config reads exactly one. Recommend `STRIPE_WEBHOOK_SECRETS` comma-separated with the verifier trying each. Code change — coordinate with backend pair 01 / security pair 02.
2. **Publishable key** is not secret but routing through a Secret is consistent.
3. **Audit trail** — production key MUST be a Stripe Restricted Key, not a Standard secret key. Add to runbook.

### Reconciled position
Ship the diff + sealed-secret scaffold. Runbook entries: (i) production key must be a Stripe Restricted Key with explicit resource scopes, (ii) escalate multi-webhook-secret support to backend pair 01, (iii) post-deploy verification by hitting `/api/v1/payments/config` and confirming a non-empty publishable key. **Rollback**: revert env additions. **Effort**: 4 hours.

---

## Finding 13 — Backups directory in repo

### Specialist proposal
Verified facts:
1. `git ls-files backups/` returns one file: `backups/jtoye_jtoye_20251231_121414.sql.gz` (~11 KB).
2. `.gitignore:105` has `backups/*.sql.gz` (added after the tracked file).
3. **Dev backup script bug**: `infra/backups/backup.sh:131` runs `pg_dump … --verbose 2>&1 | gzip` — `2>&1` redirects stderr into the pipe; when `pg_dump` fails (no password — verified Apr 24+ files at 0 bytes; "Password:" prompt — verified Apr 19 file containing `Password:\npg_dump: error: ... no password supplied`), the resulting `.gz` contains stderr noise, not a dump.
4. **The tracked file when decompressed contains pure `pg_dump: …` stderr lines — same bug, no real data.** Verified via `zcat backups/jtoye_jtoye_20251231_121414.sql.gz | head -30`.

Three deliverables:

(a) **Verify content** — done. Tracked file is stderr noise. No tenant data leaked.

(b) **Skip the history rewrite.** Since the tracked file contains zero schema/rows, the privacy cost of leaving it in history is zero. `git rm` in a normal commit is the cheaper, non-destructive answer.

(c) **Fix the dev backup script:**

```bash
# infra/backups/backup.sh:129-137 diff
    if [ "$USE_DOCKER" = true ]; then
        log_info "Using Docker exec for backup..."
-        docker exec "$DOCKER_CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" --clean --if-exists --verbose 2>&1 | \
-            gzip > "$backup_file_gz"
+        docker exec "$DOCKER_CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" --clean --if-exists --verbose \
+            2> "${backup_file_gz}.log" \
+            | gzip > "$backup_file_gz"
+        # Validate the gzip is actually a pg_dump archive, not stderr leakage
+        if ! gunzip -t "$backup_file_gz" 2>/dev/null \
+           || ! gunzip -c "$backup_file_gz" | head -c 10 | grep -qE '^(PGDMP|--)'; then
+            log_error "Backup looks invalid (probably stderr noise) — see ${backup_file_gz}.log"
+            rm -f "$backup_file_gz"
+            exit 1
+        fi
    else
+        if [ -z "${DB_PASSWORD:-}" ]; then
+            log_error "DB_PASSWORD env var is required for non-Docker backups"
+            exit 1
+        fi
        log_info "Using direct pg_dump connection..."
        PGPASSWORD="$DB_PASSWORD" pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
-            --clean --if-exists --verbose 2>&1 | gzip > "$backup_file_gz"
+            --clean --if-exists --verbose \
+            2> "${backup_file_gz}.log" \
+            | gzip > "$backup_file_gz"
    fi
```

### Assistant deliberation
1. **History rewrite cost-benefit.** Verified content is stderr noise → leak risk = 0. Skip the rewrite, save the team a force-push.
2. **Untracked files in working tree.** Sweep locally: `for f in backups/*.sql.gz; do gunzip -c "$f" 2>/dev/null | head -1; done`. Files starting with `PGDMP` or `--` are real dumps; anything starting with `pg_dump:`/`Password:` is stderr noise. Delete the noise; verify any real dumps don't carry production tenant rows before deleting.
3. **Empty `DB_PASSWORD`** is the actual root cause of the Apr 24+ silent failures. Hard-fail in the script.

### Reconciled position
Three actions: (i) `git rm backups/jtoye_jtoye_20251231_121414.sql.gz` in a normal commit; (ii) sweep `backups/` locally and delete stderr-noise files; (iii) ship the script diff with stderr separation, gzip-content validation, and `DB_PASSWORD` precondition. **Rollback**: revert the commit + script changes. **Effort**: 1 hour.

---

## Dependency graph

```
F1  (prod observability) ─┬─→ F4  (alerts; need scrape live to verify)
                          └─→ F5  (kube-state-metrics scrape relies on F1)

F2  (edge-go /metrics)   ─→ blocked-by → pair 07 absorb decision

F3  (tenantId MDC)       ─→ needs pair 01 TaskDecorator (@Async) + SSE fix

F4  (alert rules)        ─→ needs F1 to verify;
                            ─→ deeper "tenancy bypass" alert needs pair 02 @PreAuthorize

F5  (backup verify)      ─→ needs F7 path decision (ephemeral PG matters for self-hosted)

F6  (MinIO mirror)       ─→ independent; strategic tie to pair 07 (drop self-hosted MinIO?)

F7  (WAL / PITR)         ─→ blocked-by founder decision: where does prod K8s live?

F8  (Argo CD)            ─→ Stage 1 independent; Stage 2 deferred to post-customer-#10

F9  (CI hardening)       ─→ needs ~30-day .snyk exception backlog first

F10 (PSA + roFS)         ─→ needs Dockerfile audit for LOG_PATH + java.io.tmpdir

F11 (SSE ingress)        ─→ blocked-by pair 01's SSE-vs-STOMP decision

F12 (Stripe secrets)     ─→ multi-webhook-secret rotation needs pair 01/02 input

F13 (backups in repo)    ─→ independent; ship Wave 1
```

---

## Wave breakdown

### Wave 1 — Prerequisite hygiene (Day 1–2, ~2 days)
Independent items.
- **F13** — `git rm` stale backup, fix dev script, sweep `backups/`. (1h)
- **F1**  — flip actuator exposure + nginx `/actuator` deny + 2 Grafana dashboards. (1 day)
- **F3**  — `MDC.put("tenantId", ...)` + log pattern. @Async/SSE coverage lands with pair 01. (4h)
- **F8 Stage 1** — daily `kubectl diff` drift-check workflow. (2h)

### Wave 2 — Observability and trust (Day 3–6)
Depends on Wave 1 metrics live.
- **F2**  — edge-go `/metrics` (hand-rolled, gated on pair 07). (4h)
- **F4**  — register `jwt_tenant_claim_missing_total`, update alert, Alertmanager mute window. (4h)
- **F5**  — `pg-backup-tools` image + verify CronJobs (daily + weekly) + kube-state-metrics + alert. (2 days)
- **F12** — Stripe sealed-secret + env wiring + smoke. (4h)

### Wave 3 — Hardening (Day 7–10)
- **F6**  — MinIO `mc mirror` to S3 Glacier. (1 day)
- **F9**  — Snyk hard-fail + `.snyk` backlog + cosign + SBOM. Stage 2 deferred. (1 day)
- **F10** — PSA `restricted` + `readOnlyRootFilesystem` flip + seccomp + tmpdir audit. Audit→enforce over 1 week. (1 day code + 1 week soak)
- **F11** — SSE ingress split (only if pair 01 retains SSE). (2h or 0)

### Wave 4 — Strategic, founder-decision-gated
- **F7**  — managed PG vs `pgbackrest` (2–5 days).
- **F8 Stage 2** — Argo CD migration, defer to post-customer-#10 (3–5 days).
- **F2/F6 follow-up** — if pair 07 absorbs edge-go, delete the metrics package; if MinIO is dropped, delete the mirror CronJob.

---

## Open questions

1. **Where does production K8s actually live?** EKS / GKE / AKS / DO / on-prem / Hetzner / not-yet-deployed? Determines F7.
2. **Single operator or imminent collaborators?** Determines whether F13's history rewrite matters at all (vs the simpler `git rm`).
3. **Real production traffic today?** If 0 customers, F7/F8 defer materially. If 1–2 vendors, Wave 1+2 are urgent.
4. **Stripe production wiring status** — active account? Determines urgency of F12 plus pair 01 idempotency work.
5. **Pager destination** — Mailhog is dev-only. PagerDuty / Opsgenie / Slack-only for prod? Required before Wave 2 alert tuning is meaningful.
6. **Edge-go absorb decision (pair 07)** — confirm before Wave 2 so F2 effort is not wasted.

If items 1, 4, and 6 are answered before Wave 1 ships, the pair can sequence Wave 2 cleanly.

---

## Closing read

The infra story for J'Toye matches the rest of the audit: the *shape* is right (CronJobs, NetworkPolicies, Sealed Secrets workflow, multi-stage builds, signed-commit gitleaks), and the *behaviour* under prod load was never proven. Closing F1, F3, F4, F5, F12, F13 is the difference between "looks production-ready in a code review" and "actually wakes someone up when prod breaks". Estimated ~6 working days for those six items end-to-end. The remainder (F6–F11) is hardening that should follow the first 10 paying customers, not precede them, per the council's strategic guidance.
