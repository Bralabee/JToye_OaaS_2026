# Phase 29: Deployable Staging, With Its Own Monitoring — Pattern Map

**Mapped:** 2026-08-10
**Files analyzed:** 31 (new or modified, extracted from 29-CONTEXT.md D-01..D-19 + 29-RESEARCH.md Blockers A–D, Pitfalls 5–10, Wave-0 Gaps)
**Analogs found:** 28 / 31 (3 have no in-repo analog: `RabbitmqCluster`, `ClusterIssuer`/`Certificate`, the Azure provisioning script)

> **Read this first.** This repo's house style is not "terse YAML". Every load-bearing
> file in `k8s/` and `scripts/` carries a header comment that states **the defect it
> exists to prevent, measured**. The analogs below are quoted *including* those headers
> because the header is the pattern. A new file in this phase that ships without one is
> off-pattern regardless of whether its YAML is correct.

---

## File Classification

| New/Modified File | New? | Role | Data Flow | Closest Analog | Match |
|---|---|---|---|---|---|
| `k8s/staging/scale-patch.yaml` | NEW | config (kustomize patch) | transform | `k8s/local/scale-patch.yaml` | **exact** |
| `k8s/staging/configmap-patch.yaml` | MOD | config | transform | `k8s/local/configmap-patch.yaml` | **exact** |
| `k8s/staging/kustomization.yaml` | MOD | config | transform | `k8s/local/kustomization.yaml` | **exact** |
| `k8s/staging/ingress-hosts-patch.yaml` | MOD | config (JSON6902) | transform | itself (lines 1–49) | **exact** |
| `k8s/base/ingress.yaml` | MOD | route | request-response | itself (lines 47–88, the removal note) | **exact** |
| `k8s/monitoring/prometheus-deployment.yaml` | NEW | service (deployment) | request-response | `k8s/base/edge-go-deployment.yaml` + compose `prometheus` | role-match |
| `k8s/monitoring/alertmanager-deployment.yaml` | NEW | service | pub-sub / event-driven | `k8s/base/edge-go-deployment.yaml` + compose `alertmanager` | role-match |
| `k8s/monitoring/grafana-deployment.yaml` | NEW | service | request-response | `k8s/base/frontend-deployment.yaml` (Svc+HPA+PDB set) | role-match |
| `k8s/monitoring/{postgres,redis}-exporter-deployment.yaml` | NEW | service | request-response | compose `postgres-exporter`/`redis-exporter` | role-match |
| `k8s/monitoring/prometheus-config.yaml` (ConfigMap) | NEW | config | transform | `infra/monitoring/prometheus/prometheus.yml.tmpl` | **exact (corpus)** |
| `k8s/monitoring/alertmanager-config.yaml` (ConfigMap) | NEW | config | transform | `infra/monitoring/alertmanager/alertmanager.yml.tmpl` | **exact (corpus)** |
| `k8s/monitoring/grafana-provisioning.yaml` (ConfigMap) | NEW | config | transform | `infra/monitoring/grafana/provisioning/**` | **exact (corpus)** |
| `k8s/monitoring/kustomization.yaml` | NEW | config | transform | `k8s/base/kustomization.yaml` | **exact** |
| `k8s/keycloak/keycloak-deployment.yaml` | NEW | service (IdP) | request-response | `k8s/base/edge-go-deployment.yaml` + compose `keycloak` (:138–183) | role-match |
| `k8s/keycloak/realm-import-configmap.yaml` | NEW | config | file-I/O | `infra/keycloak/realm-export.template.json` + compose realm-render | role-match |
| `k8s/base/mailhog-deployment.yaml` | NEW | service | pub-sub | `k8s/base/edge-go-deployment.yaml` | role-match |
| `k8s/base/rabbitmq-cluster.yaml` (`RabbitmqCluster`) | NEW | model (CR) | pub-sub | — | **none** |
| `k8s/staging/cluster-issuer.yaml` (cert-manager) | NEW | model (CR) | event-driven | — | **none** |
| `k8s/base/networkpolicies/20-core-java.yaml` | MOD | middleware (policy) | request-response | itself (:73–142) | **exact** |
| `k8s/base/networkpolicies/50-observability.yaml` | MOD (replace placeholder) | middleware | request-response | `20-core-java.yaml` | **exact** |
| `k8s/base/configmap.yaml` | MOD | config | transform | itself (:190–225) | **exact** |
| `k8s/base/core-java-deployment.yaml` | MOD | service | request-response | itself (:63–140) | **exact** |
| `k8s/goldens/{staging,production}.yaml` | REGEN | test fixture | batch | `k8s/scripts/render-golden.sh --write` | **exact** |
| `scripts/check-alert-liveness.sh` | MOD | test (gate) | live probe | itself (:78–86, :203–221, :355–359) | **exact** |
| `scripts/check-networkpolicy-enforcement.sh` | NEW | test (gate) | live probe | `k8s/scripts/check-no-plaintext-secrets.sh` (skeleton) + `check-alert-liveness.sh` (VOID doctrine) | role-match |
| `scripts/staging-pitr-drill.sh` | NEW | utility (drill) | batch / file-I/O | `scripts/restore-drill.sh` + `docs/runbooks/backups.md` §two-arm | role-match |
| `scripts/staging-bootstrap.sh` | NEW | utility (bring-up) | batch | `scripts/k8s-local-up.sh` | **exact** |
| `scripts/staging-secrets.sh` | NEW | utility (secrets) | batch | `scripts/k8s-local-secrets.sh` | **exact** |
| `k8s/scripts/check-render-invariants.sh` | MOD (INV-7 + new INV) | test (gate) | static | itself (:337–358, :833–886) | **exact** |
| `k8s/scripts/check-connection-math.sh` | MOD | test (gate) | static | itself (:52–110) | **exact** |
| `scripts/gates/gate-enforcement.conf` | MOD | config | static | itself (:1–25) | **exact** |
| `.github/workflows/ci-cd.yaml` | MOD (deploy-staging + k8s-validate) | config (CI) | batch | itself (:377–412, :1107–1248) | **exact** |
| `core-java/src/main/resources/application.yml` | MOD (`redis.ssl`) | config | transform | itself (:32–42) | **exact** |
| `…/notification/dispatch/UnsubscribeLinkRoutingTest.java` | MOD (#592) | test | unit | itself | **exact** |
| `docs/runbooks/staging-operations.md` | NEW | doc | prose | `docs/runbooks/credential-rotation.md` | **exact** |
| `docs/architecture/decisions/ADR-0002-…md` | MOD (Status → Accepted) | doc | prose | its own 2026-07-29 addendum (:50–78) | **exact** |
| `infra/dependency-horizons.yaml` | MOD (4 rows) | config | static | itself (:242–259 pinned, :497–514 unknown) | **exact** |

---

## Pattern Assignments

### `k8s/staging/scale-patch.yaml` (config, transform) — Wave-0 Gap, Pitfall 6

**Analog:** `k8s/local/scale-patch.yaml` — copy it almost verbatim, changing only the values and the header's environment name.

**Whole-file pattern** (`k8s/local/scale-patch.yaml:1-40` header, `:41-82` the six documents):

```yaml
# =============================================================================
# LOCAL scale minimums (Phase 26 / D-09, DEF-3)
#
# WHY THIS FILE EXISTS AT ALL
#   The overlay's `replicas:` list (kustomization.yaml) reaches ONLY
#   Deployment / ReplicationController / ReplicaSet / StatefulSet. It does NOT
#   touch HorizontalPodAutoscaler or PodDisruptionBudget
#   [kubectl.docs.kubernetes.io/references/kustomize/kustomization/replicas/,
#   verified by render in 26-RESEARCH.md P-1]. So scaling local to one replica
#   needs TWO mechanisms, and this is the second one.
#
# ONE FILE, SIX DOCUMENTS
#   kustomize matches each document to its target by GVK + name, so a single
#   `patches: - path: scale-patch.yaml` entry covers all six objects.
#
# maxReplicas IS DELIBERATELY UNTOUCHED — DO NOT "TIDY" IT
#   HPA maxReplicas is an INPUT to the Postgres connection budget:
#   k8s/scripts/check-connection-math.sh asserts
#   maxReplicas x DB_POOL_SIZE (+ Keycloak, exporter, healthcheck, pg-backup)
#   fits max_connections with >= 20% headroom.
# =============================================================================
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: core-java-hpa
spec:
  minReplicas: 1
---
# … frontend-hpa, edge-go-hpa, then three policy/v1 PodDisruptionBudget docs
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: core-java-pdb
spec:
  minAvailable: 1
```

**Two constraints the staging copy must respect (both already stated in the analog's header):**
1. Do **not** lower `maxReplicas` — `check-connection-math.sh` reads it from `k8s/base/core-java-deployment.yaml` and `check-render-invariants.sh` LOC-2 asserts the local `maxReplicas` multiset equals base's. Add a staging equivalent of that assertion or state why not.
2. Each patch document carries **exactly the one field it changes** — no selectors, no metrics, no labels.

**Wiring** — `k8s/local/kustomization.yaml:147-151` is the exact line to mirror in `k8s/staging/kustomization.yaml`:

```yaml
patches:
  - path: configmap-patch.yaml
  - path: scale-patch.yaml          # <- the one-line addition
  - path: ingress-patch.yaml
  - path: sse-ingress-patch.yaml
```

---

### `k8s/staging/configmap-patch.yaml` (config, transform) — Blocker A, Pitfall 7, #592

**Analog:** `k8s/local/configmap-patch.yaml` (it is the only overlay that patches `keycloak.client-id`; staging's current file records *deliberately not* patching it — that record is the defect, and it must be replaced with an equally explicit note, not silently deleted).

**The base's own statement of the bug** (`k8s/base/configmap.yaml:190-212`):

```yaml
  # --- Frontend OIDC client id (Phase 26 / plan 26-08) -----------------------
  # It was not merely a style point — it made the local ingress login
  # IMPOSSIBLE. The dev realm (infra/keycloak/realm-export.template.json) has
  # NO client named `frontend`; the client the frontend actually authenticates
  # as is `core-api` … Only the local overlay patches this key; see
  # k8s/local/configmap-patch.yaml.
  keycloak.client-id: "frontend"
```

**The local override to copy** (`k8s/local/configmap-patch.yaml:79-91`):

```yaml
  # jtoye-dev realm:
  #   grep -c '"clientId" : "frontend"' infra/keycloak/realm-export.template.json
  # returns 0. The realm's confidential browser-flow client is `core-api` …
  # Without this override the ingress login — the only step that actually
  # proves DEF-5 — fails at the authorize request.
  keycloak.client-id: "core-api"
```

**The comment block that must be REPLACED, not deleted** (`k8s/staging/configmap-patch.yaml:33-40`) — it currently asserts staging deliberately inherits `frontend`. Incremental Betterment: state what displaced it and why.

**Key-addition style for the new keys** (`redis.ssl`, `redis.port` routing, the #592 one-click base URL) — every key in `k8s/staging/configmap-patch.yaml:18-53` carries a `# D-nn:` provenance line and derives its value from *this overlay's own* `frontend.url`/`keycloak.issuer.uri`, never from the base's production values:

```yaml
  # D-19: unsubscribe + email open/click tracking links in staging notifications.
  notification.email.tracking-base-url: "https://app-staging.olajay.co.uk"
  notification.unsubscribe.base-url: "https://app-staging.olajay.co.uk"
```

---

### `k8s/staging/kustomization.yaml` (config, transform)

**Analog:** itself + `k8s/local/kustomization.yaml`.

**The `replacements:` block that must be extended for Redis** (`k8s/staging/kustomization.yaml:84-109`) — Blocker D says the Redis port must get the same treatment `db.port` already has. The repeat-in-every-overlay rule is measured, not assumed (`k8s/base/kustomization.yaml:113-119`):

```yaml
# THIS BLOCK MUST BE REPEATED IN EVERY OVERLAY THAT PATCHES db.port — verified,
# not assumed: with the block present here ONLY and k8s/local patching db.port to
# 5433, the local render still emitted `port: 5432`. kustomize builds the base
# (running this replacement against the BASE ConfigMap) before the overlay's
# patches touch that ConfigMap, so the overlay must run its own pass.
replacements:
  - source:
      kind: ConfigMap
      name: app-config
      fieldPath: data.db\.port
    targets:
      - select:
          kind: NetworkPolicy
          name: core-java-allow
        fieldPaths:
          - spec.egress.1.ports.0.port
```

**The index-coupling contract** (`k8s/base/kustomization.yaml:103-111`): `spec.egress.1` means "the Postgres rule, which deliberately carries exactly ONE port". Any new egress rule inserted **ahead of index 1** silently retargets the replacement. If a Redis replacement is added, give Redis its own single-port rule at a stable index and say so in the comment, exactly as Postgres does.

---

### `k8s/staging/ingress-hosts-patch.yaml` (config, JSON6902) — D-06, D-19, #296

**Analog:** itself. New hosts (`auth-staging`, `grafana-staging`) are **appended** ops, and the header already explains why JSON6902 and why index-coupling is a feature.

**Excerpt** (`k8s/staging/ingress-hosts-patch.yaml:19-49`):

```yaml
# WHY JSON6902 AND NOT A STRATEGIC MERGE PATCH. IngressSpec.rules carries no
# patchMergeKey, so a strategic merge REPLACES the whole list — which would silently
# drop every `http.paths` backend and render an Ingress that routes nothing.
#
# Index-coupled by necessity (rules[0]=api, rules[1]=app in k8s/base/ingress.yaml).
# If the base reorders or adds a rule, `kustomize build` FAILS LOUDLY on a missing
# path rather than silently mis-patching — which is the behaviour we want.
- op: replace
  path: /spec/tls/0/hosts/0
  value: api-staging.olajay.co.uk
- op: replace
  path: /spec/tls/0/secretName
  value: jtoye-staging-tls
- op: replace
  path: /spec/rules/1/host
  value: app-staging.olajay.co.uk
```

**Adding a host is `op: add` to both lists, in this order:**

```yaml
- op: add
  path: /spec/tls/0/hosts/-
  value: auth-staging.olajay.co.uk
- op: add
  path: /spec/rules/-
  value:
    host: auth-staging.olajay.co.uk
    http:
      paths:
        - path: /
          pathType: Prefix
          backend:
            service:
              name: keycloak
              port:
                number: 8080
```

**Ordering constraint (INV-6, and the SAN single-order failure mode):** `k8s/base/ingress.yaml:47-88` states that all SANs share ONE `jtoye-tls` Secret issued as a **single ACME order**, and *a failed challenge fails the whole order*. So the sequence is: **DNS A record → Service+Deployment exists → rule → SAN**. `check-render-invariants.sh` INV-6 fails CI if a rule's backend Service is not in the same render — which is the mechanism enforcing that order.

---

### `k8s/base/ingress.yaml` (route, request-response) — #296, D-02

**Analog:** its own removal note. This is an **Incremental Betterment displacement**: the file explicitly records what to do when the condition it assumed becomes false.

**The instruction being executed** (`k8s/base/ingress.yaml:84-88`):

```yaml
  # IF AN IN-CLUSTER KEYCLOAK IS EVER DEPLOYED: the host rule and the TLS SAN
  # come back TOGETHER WITH its Service and Deployment, in that order — never
  # before them. Recorded as a dated deferred item in
  # .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/deferred-items.md
  # so the displaced intent is not lost.
```

**Pitfall 5 lives in the annotations here** (`k8s/base/ingress.yaml:28-35`) — these headers are served only if the controller has `allow-snippet-annotations: "true"`:

```yaml
    nginx.ingress.kubernetes.io/configuration-snippet: |
      more_set_headers "X-Frame-Options: DENY";
      more_set_headers "Strict-Transport-Security: max-age=31536000; includeSubDomains; preload";
```

`k8s/local/ingress-patch.yaml:24-38` is the precedent for how to reason about that flag — **and it explicitly forbids the "just enable it" fix at cluster level without recording the acceptance.** Staging's `X-Robots-Tag: noindex` (RESEARCH §Project Constraints 7) belongs in this same annotation block, patched by the staging overlay.

---

### `k8s/monitoring/*` — Prometheus / Alertmanager / Grafana / exporters (service, request-response + pub-sub) — D-16, DPLY-03

**Analog A — the Deployment/Service/HPA/PDB skeleton:** `k8s/base/edge-go-deployment.yaml` (162 lines, the smallest complete set in the repo). Copy this shape per monitoring workload; drop the HPA/PDB for singleton stateful-ish workloads and say why.

```yaml
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: edge-go
  labels:
    app: edge-go
    component: gateway
spec:
  replicas: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 2
      maxUnavailable: 0
  selector:
    matchLabels:
      app: edge-go
  template:
    metadata:
      labels:
        app: edge-go
        component: gateway
        version: v1
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/metrics"
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              labelSelector:
                matchExpressions:
                - key: app
                  operator: In
                  values:
                  - edge-go
              topologyKey: kubernetes.io/hostname
      containers:
      - name: edge-go
        image: ghcr.io/bralabee/jtoye-edge-go:2.1.0
        imagePullPolicy: IfNotPresent
        ports:
        - containerPort: 8080
          name: http
          protocol: TCP
        resources:
          requests:
            memory: "64Mi"
            cpu: "100m"
          limits:
            memory: "256Mi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 10
          timeoutSeconds: 3
          failureThreshold: 3
        readinessProbe:
          httpGet:
            path: /ready
            port: 8080
          initialDelaySeconds: 5
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 3
        securityContext:
          runAsNonRoot: true
          runAsUser: 65534  # nobody user
          allowPrivilegeEscalation: false
          capabilities:
            drop:
            - ALL
          readOnlyRootFilesystem: true
---
apiVersion: v1
kind: Service
metadata:
  name: edge-go
  labels:
    app: edge-go
spec:
  type: ClusterIP
  selector:
    app: edge-go
  ports:
  - port: 8080
    targetPort: 8080
    protocol: TCP
    name: http
```

**Analog B — what each container is, verbatim from compose** (`infra/monitoring/docker-compose.monitoring.yml`). Reuse the pins (D-16) and the healthcheck endpoints as probe paths:

| Workload | Image pin (compose line) | Probe path | Notes carried from the analog |
|---|---|---|---|
| prometheus | `prom/prometheus:v2.48.0` (:35) | `/-/healthy` (:76) | flags at `:44-49`; `--storage.tsdb.retention.time=30d` |
| grafana | `grafana/grafana:10.2.2` (:84) | `/api/health` (:105) | `GF_USERS_ALLOW_SIGN_UP=false`; **admin password only applied at first user creation** (:20-27) |
| alertmanager | `prom/alertmanager:v0.27.0` (:115) | `/-/healthy` (:155) | config rendered from a template because *"Alertmanager has no native env-var substitution"* |
| postgres-exporter | `prometheuscommunity/postgres-exporter:v0.15.0` (:183) | — | `DATA_SOURCE_NAME` with `sslmode=${POSTGRES_EXPORTER_SSLMODE:-require}` (:186) |
| redis-exporter | `oliver006/redis_exporter:v1.58.0` (:163) | **none** | *"the redis_exporter image is scratch-based (no shell/wget), so an exec-style check can never pass"* (:175-178) — do not add a probe |

**The one comment in the analog that is a direct instruction to this phase** (`infra/monitoring/docker-compose.monitoring.yml:29-30`):

```
# When the k8s monitoring manifests land (DPLY-03) they should take the
# credential the same way, so this is fixed once rather than twice.
```

**Analog C — the rule/scrape corpus, mounted verbatim.** `infra/monitoring/prometheus/prometheus.yml.tmpl:40-219` is the source of the **job names and label blocks that must not change**. Only the target address changes (RESEARCH §Pattern 1). Copy each block including its header comment:

```yaml
  - job_name: 'core-java'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['core-java:__CORE_JAVA_METRICS_PORT__']
        labels:
          service: 'core-api'
          component: 'backend'
    relabel_configs:
      - source_labels: [__address__]
        target_label: instance
        replacement: 'core-java'
```

and the load-bearing per-queue drop (`:200-219`) must survive the port:

```yaml
  - job_name: 'rabbitmq-queues'
    metrics_path: '/metrics/detailed'
    params:
      family: ['queue_coarse_metrics', 'queue_consumer_count']
    metric_relabel_configs:
      # LOAD-BEARING, NOT DECORATIVE. … the random suffix changes on EVERY restart,
      # so keeping these series would leak one label value per restart, forever.
      - source_labels: [queue]
        regex: 'order[.]state-changes[.]sse[.].*'
        action: drop
```

**Analog D — Alertmanager config** (`infra/monitoring/alertmanager/alertmanager.yml.tmpl:14-47`). The receiver is a **list of `email_configs`**, which is exactly the shape Blocker B's L-3 fix relies on (add Mailhog beside Gmail in the *same* receiver — never a probe-only route):

```yaml
global:
  resolve_timeout: 5m
  smtp_smarthost: '__SMTP_SMARTHOST__'
  smtp_from: '__SMTP_FROM__'
  smtp_require_tls: __SMTP_REQUIRE_TLS__

route:
  receiver: email-default
  group_by: ['alertname', 'service']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 12h

receivers:
  - name: email-default
    email_configs:
      - to: '__SMTP_TO__'
        send_resolved: true
        headers:
          Subject: '[{{ .Status | toUpper }}…] {{ .CommonLabels.alertname }} …'
```

> In k8s the `__PLACEHOLDER__`/`entrypoint.sh` render idiom is **not** needed for the parts kustomize can inject, but the SMTP credential still cannot be a literal. Take it from a `secretKeyRef` env and keep a rendering step, or use Alertmanager's `smtp_auth_password_file` with a mounted Secret — and state which, and why, in the file header.

**Analog E — Grafana provisioning ConfigMaps.** `infra/monitoring/grafana/provisioning/datasources/prometheus.yml` (13 lines) and `.../dashboards/dashboard.yml` (13 lines) go in verbatim as ConfigMap keys; only `url: http://prometheus:9090` may change:

```yaml
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```

**Analog F — the monitoring kustomization.** `k8s/base/kustomization.yaml:18-35` for the `resources:` list style, and its `:5-8` namespace rule:

```yaml
# NOTE: the target Namespace is owned by each overlay (k8s/<env>/namespace.yaml),
# not the base. A base that shipped all env namespaces made every overlay's
# `namespace:` transformer collapse them to one name -> "namespace transformation
# produces ID conflict". Keep environment namespaces out of the shared base.
# SECURITY (#100 / P2-9): no Secret manifests are kustomize resources.
```

**Gate consequence to plan for:** `find "$K8S_DIR" -maxdepth 2 -name 'kustomization.yaml'` auto-discovers any new directory (`check-no-plaintext-secrets.sh:37`, `check-render-invariants.sh:367`). A new `k8s/monitoring/` **becomes a fourth render target immediately** — so it needs its own namespace object and its own golden decision. `render-golden.sh:22-25` explains why `k8s/goldens/` is deliberately not discovered.

---

### `k8s/keycloak/keycloak-deployment.yaml` (service, request-response) — D-02, #296

**Analog A — the container definition**, `docker-compose.full-stack.yml:138-183`:

```yaml
  keycloak:
    image: quay.io/keycloak/keycloak:24.0.5
    command: ["start-dev", "--import-realm"]
    environment:
      KEYCLOAK_ADMIN: ${KEYCLOAK_ADMIN}
      KEYCLOAK_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD:?KEYCLOAK_ADMIN_PASSWORD must be set}
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
      KC_DB_USERNAME: ${KC_DB_USERNAME}
      KC_DB_PASSWORD: ${KC_DB_PASSWORD:?KC_DB_PASSWORD must be set}
      # Issue #94 [P2-3]: Keycloak's Agroal pool defaults to 100 — it could
      # single-handedly exhaust the shared Postgres. 20 is the budget line
      # item assumed by the platform connection math (check-connection-math.sh).
      KC_DB_POOL_MAX_SIZE: "20"
      KC_HEALTH_ENABLED: "true"
      KC_METRICS_ENABLED: "true"
      KC_HOSTNAME: localhost
      KC_HOSTNAME_STRICT: "false"
      JAVA_OPTS: "-Xms512m -Xmx1024m"
    healthcheck:
      test: ["CMD-SHELL", "… GET /health/ready … | grep -q '200 OK'"]
      start_period: 90s
```

Three things carry across unchanged and are load-bearing:
- `KC_DB_POOL_MAX_SIZE: "20"` — `check-connection-math.sh:58-59` **parses this literal out of the compose file** as `KEYCLOAK_POOL`. If the k8s value differs, the gate is measuring the wrong number silently.
- `KC_METRICS_ENABLED: "true"` — RESEARCH §Pattern 1 says the `keycloak` scrape job becomes UP in k8s for the first time; without this it stays DOWN and `check-alert-liveness.sh` L-1 fails.
- `KC_HOSTNAME` in staging must be `auth-staging.olajay.co.uk` — and `KC_HOSTNAME_STRICT` must be reconsidered, not copied.

**Analog B — env-injection shape**, `k8s/base/core-java-deployment.yaml:63-107`. Every value is `valueFrom`, never a literal, and the reason is documented at the point of use:

```yaml
        env:
        - name: DB_HOST
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: host
        # DEF-1 (Phase 26 / INFRA-02a): the port is CONFIG, not a constant.
        # GLOBAL_RULE_6 / ARCHITECTURE_RULE_8 (config injection, no
        # environment-varying literals) …
        #
        # The literal `value:` had to be DELETED, not merely supplemented by an
        # overlay patch: a kustomize strategic-merge patch that adds `valueFrom`
        # to an env item that still has `value:` renders an EnvVar with BOTH
        # fields. `kubectl kustomize` accepts it, but the API server rejects the
        # apply with `env[i].valueFrom: Invalid value …`. There is no overlay shortcut.
        - name: DB_PORT
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: port
```

That last paragraph is enforced by **INV-2** (`check-render-invariants.sh:56`): no rendered EnvVar may carry both `value:` and `valueFrom:`.

**Analog C — realm import.** The compose `keycloak-realm-render` init service (`docker-compose.full-stack.yml:111-135`) renders the template with `envsubst` before Keycloak starts. Its comment is a trap warning worth reproducing:

```
# A placeholder that is NOT named here survives into the rendered file as a
# literal dollar-brace token — envsubst leaves unlisted names untouched — and
# Keycloak would then treat that literal as the client id. Extend this list;
# never remove it.
```

`reference_keycloak_realm_reimport` memory + `docs/runbooks/credential-rotation.md:52-54` add the second half: **KC is Postgres-backed, so a volume drop is a no-op and `--import-realm` SKIPS an existing realm** — re-import is `kc.sh import --override true` **plus a restart**.

---

### `k8s/base/rabbitmq-cluster.yaml` and `k8s/staging/cluster-issuer.yaml` (model / CR) — **NO IN-REPO ANALOG**

Use the RESEARCH §Code Examples shapes verbatim (29-RESEARCH.md:727-765). Three repo-specific constraints apply anyway:

1. **Third-party manifests live OUTSIDE `k8s/`** (RESEARCH §Pattern 3, corroborated by `render-golden.sh:22-25`): only *our* CRs go in a kustomization; the cert-manager and operator installs go in `scripts/staging-bootstrap.sh` by pinned URL + recorded sha256.
2. **INV-7 changes with any NetworkPolicy edit** — the RabbitMQ ports (5672 AMQP, 61613 STOMP, 15692 metrics) already appear in `NETPOL_INFRA_EXPECTED`; moving the broker to an operator-managed StatefulSet in a different namespace changes which `namespaceSelector` those ports sit under. See the INV-7 section below.
3. **The horizon row is the deliverable, not a chore** — `infra/dependency-horizons.yaml:497-514`'s `rabbitmq-k8s` row (`pin: unknown`, expires **2026-10-26**) is resolved by pinning `RabbitmqCluster.spec.image` explicitly.

---

### `k8s/base/networkpolicies/20-core-java.yaml` (middleware, request-response) — Blocker D, DPLY-05

**Analog:** itself. The Postgres rule (`:73-109`) is the exact shape a new out-of-cluster datastore rule must take — **its own rule, exactly ONE port**, so a `replacements:` fieldPath means "the Postgres port" and not "whichever port sorts first":

```yaml
    # -------------------------------------------------------------------------
    # POSTGRES — its own egress rule, holding exactly ONE port. Issue #271.
    #
    # THE PORT BELOW IS NOT AUTHORED — it is OVERWRITTEN at render time from
    # app-config `db.port` by the `replacements:` block …
    #
    # WHY A SEPARATE RULE RATHER THAN THE FIRST PORT OF THE COMBINED RULE.
    # NetworkPolicy egress rules are OR'ed and each rule is (peers x ports), so
    # splitting one rule with six ports into two rules over the SAME peer permits
    # exactly the same set of (peer, port) pairs — this is a shape change with no
    # security change.
    # -------------------------------------------------------------------------
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: jtoye-infrastructure
      ports:
        - protocol: TCP
          port: 5432   # Postgres — replaced from app-config db.port
```

**The rule Blocker D says denies every managed datastore** (`:131-142`):

```yaml
    # Public internet for Keycloak, Stripe, Ollama remote, S3 public, CDNs
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0
            except:
              - 10.0.0.0/8
              - 172.16.0.0/12
              - 192.168.0.0/16
      ports:
        - protocol: TCP
          port: 443
```

**The comment that names the follow-up this phase must do** (`:110-115`):

```
    # In-cluster infrastructure namespace: Redis, RabbitMQ, MinIO, Alertmanager.
    # These five are still literals. That is a KNOWN, deliberate limit of this
    # change: #271 is about the Postgres port, and redis.port / rabbitmq.port /
    # stomp.broker.relay-port already exist as app-config keys, so routing them
    # the same way is a clean follow-up … INV-7 asserts all five explicitly.
```

**Security constraint from `<security_domain>` V9:** do **not** widen the `0.0.0.0/0:443` rule to cover 5432/6380. Add per-datastore rules addressed by the resolved endpoint, on the specific port.

---

### `k8s/base/networkpolicies/50-observability.yaml` (middleware) — displaced good, D-16

**Analog:** `20-core-java.yaml`. This is a **replace**, and the file names its own replacement condition (`50-observability.yaml:14-17`):

```
# If a future phase ships Grafana / Prometheus / Alertmanager manifests in
# the jtoye-production namespace, replace the contents of this file with
# concrete policies.
```

The placeholder currently selects `app: nonexistent-placeholder` (`:39-45`). Per the Incremental Betterment Doctrine and RESEARCH §Project Constraints 6, the replacement must be *stated as a displacement* in the new file's header.

**Security constraint (V4 / D-19):** Prometheus and Alertmanager get a `ClusterIP` Service and **no Ingress**. Assert that in the render — the natural home is a new INV in `check-render-invariants.sh` beside INV-6.

---

### `scripts/check-alert-liveness.sh` (test gate, live probe) — **Blocker B**

**Analog:** itself. Two additive changes, both already anticipated by the file's own header.

**The overridables it already declares** (`:82-86`):

```bash
# L-0 needs to read the file out of the running Prometheus. Both are overridable
# because neither is a property of this script — a k8s Prometheus has a different
# container name and a different in-container path.
PROM_CONTAINER="${PROM_CONTAINER:-jtoye-prometheus}"
PROM_ALERTS_PATH="${PROM_ALERTS_PATH:-/etc/prometheus/alerts.yml}"
```

**The unconditional docker calls that must become an env-selected exec** (`:203-221`):

```bash
command -v docker >/dev/null 2>&1 \
  || void "L-0 docker not on PATH … set PROM_CONTAINER/PROM_ALERTS_PATH for a non-docker runtime."
docker inspect "$PROM_CONTAINER" >/dev/null 2>&1 \
  || void "L-0 container '$PROM_CONTAINER' not found …"

HOST_SUM=$(md5sum "$ALERTS" | awk '{print $1}')
CTR_SUM=$(docker exec "$PROM_CONTAINER" md5sum "$PROM_ALERTS_PATH" 2>/dev/null | awk '{print $1}') \
  || void "L-0 cannot read $PROM_ALERTS_PATH inside '$PROM_CONTAINER'"
```

**Keep, do not "improve", the byte-exact compare** (`:181-202`) — the header explains why a semantic compare is worse, and why `docker cp` **cannot see the drift** (measured: `cp` md5 `1cc20a85` vs `exec` md5 `d25f3d10`). The k8s analogue of that trap is a `kubectl cp` (which reads through the API from the container FS — verify) versus `kubectl exec … md5sum`; use **exec**, and state the k8s-specific failure mode (RESEARCH §Pattern 2: kubelet ConfigMap sync lag, not inode detach).

**The L-3 sink assertion that VOIDs against Gmail** (`:355-359`):

```bash
curl -sf --max-time 10 "$MAILHOG_URL/api/v2/messages?limit=1" >/dev/null 2>&1 \
  || void "destination (Mailhog at $MAILHOG_URL) is not inspectable — an unverifiable transport is VOID, never clean"
```

**Data blocks that must NOT be renamed** (`:99-139`) — reproduce these job names in the k8s Prometheus config or the gate VOIDs by design:

```bash
EXPORTER_GAUGES=( "postgres|pg_up" "redis|redis_up" )
DIRECT_JOBS=("prometheus" "core-java" "edge-go" "keycloak" "rabbitmq" "rabbitmq-queues")
SERVICE_JOB_MAP=( "core-java|core-java" "postgresql|postgres" "redis|redis"
                  "keycloak|keycloak" "rabbitmq|rabbitmq,rabbitmq-queues" "platform|*" )
```

The header's standing instruction (`:113-117`) forbids the tempting fix:

```
# Do NOT "fix" a future VOID by teaching L-1b to ignore unknown jobs — that would
# be strictly worse than the red, because the whole point is that a new exporter
# cannot slip in unmapped.
```

---

### `scripts/check-networkpolicy-enforcement.sh` (NEW test gate, live probe) — DPLY-05

**Analog A — script skeleton:** `k8s/scripts/check-no-plaintext-secrets.sh:1-58`.

```bash
#!/usr/bin/env bash
# check-no-plaintext-secrets.sh — regression gate for issue #100 (P2-9).
#
# Asserts that `kubectl kustomize` output for k8s/base and EVERY overlay: …
#
# Requires: kubectl (client-side only — no cluster access needed).
# Exit codes: 0 = clean, 1 = violation found, 2 = build/tooling failure.
#
# Usage: ./k8s/scripts/check-no-plaintext-secrets.sh
#   (run from anywhere; paths resolve relative to the repo root)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if ! command -v kubectl > /dev/null; then
    echo "ERROR: kubectl not on PATH …" >&2
    exit 2
fi

OUT="$(mktemp)"; ERR="$(mktemp)"
trap 'rm -f "$OUT" "$ERR"' EXIT
```

**Analog B — VOID doctrine and control-arm-first**, `scripts/check-alert-liveness.sh:61-68`:

```
# EXIT CODES — uniform across this plan's gates
#   0 = clean · 1 = a live detection defect · 2 = VOID (could not evaluate)
#
#   VOID on: missing curl/jq/python3/docker, unreachable Prometheus or
#   Alertmanager, ZERO targets or ZERO rules discovered … "Found nothing" is NEVER
#   "clean", and an unmapped new thing must stop the gate rather than skip.
```

**Analog C — the two-arm falsification structure**, `docs/runbooks/backups.md:295-302` (the table form is the house shape for "arm A is what makes arm B mean something"). The agnhost recipe (29-RESEARCH.md:705-723) supplies the arms; the **control arm must run first**, because a broken probe reads as a perfect security posture (`feedback_suspect_the_instrument_first`).

**Analog D — instrument-blindness precedent**, `scripts/k8s-local-secrets.sh:298-306`: a minimal image with no `grep`/`sed`/`awk` silently broke a verification *after* the mutation succeeded. `agnhost` is similarly minimal — assert with the tool's own exit code, not a piped filter.

**Wiring is mandatory:** `scripts/check-gate-enforcement.sh` is **default-deny** (`:29-32`):

```
#   Default-deny: a new gate that is neither wired nor declared FAILS. Forgetting to
#   think about a new gate is the case this exists to catch, so it must not pass
#   silently.
```

So each new `scripts/check-*.sh` either gets a step in `.github/workflows/ci-cd.yaml` **or** a reasoned line in `scripts/gates/gate-enforcement.conf` (format `<gate-basename.sh>  <reason>`, `:5`). The bar for a conf entry (`:11-14`): *"this gate inspects something a GitHub-hosted runner does not have, so it could only ever exit 2 (VOID) there"*.

---

### `scripts/staging-secrets.sh` + `scripts/staging-bootstrap.sh` (utility, batch) — D-04, D-17, Open Question 2

**Analog:** `scripts/k8s-local-secrets.sh` and `scripts/k8s-local-up.sh`.

**The idempotent apply idiom — copy verbatim** (`scripts/k8s-local-secrets.sh:214-227`):

```bash
apply_secret() {
  # apply_secret <name> <--from-literal=k=v>...
  # D-01's mandated idempotent pattern: render client-side, then apply. No delete
  # window, no error on re-run, and nothing becomes a kustomize resource.
  local name="$1"; shift
  kubectl create secret generic "$name" "$@" --dry-run=client -o yaml | k8s_local_kubectl apply -n "$NS" -f - >/dev/null
  CREATED+=("$name")
}

skip_secret() {
  # skip_secret <name> <reason>
  SKIPPED+=("$1 — $2")
  echo "SKIP: Secret ${1} not created — ${2}"
}
```

**The fail-loud-by-NAME preflight** (`:100-119`) — extend `REQUIRED_VALUES` with the phase's three new credentials (Gmail app password, Grafana admin, real AWS backup keys):

```bash
# STEP 1b — value preflight. Fail loud, by NAME, before anything is created:
# a half-bootstrapped cluster is worse than one that refused to start.
REQUIRED_VALUES=(
  POSTGRES_USER POSTGRES_DB DB_USER DB_PASSWORD DB_BACKUP_PASSWORD
  REDIS_PASSWORD RABBITMQ_USER RABBITMQ_PASSWORD
  KEYCLOAK_ADMIN KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_CLIENT_SECRET
  NEXTAUTH_SECRET MINIO_ROOT_USER MINIO_ROOT_PASSWORD
)
```

**The complete secret list to mirror** (`:325-379`): `postgres-credentials` (host/port/database/username/password/backup-username/backup-password), `redis-credentials`, `rabbitmq-credentials` (+ `stomp-login`/`stomp-passcode`), `keycloak-credentials`, `nextauth-secret`, `s3-backup-credentials`, `s3-media-credentials`, conditional `notification-credentials` and `stripe-credentials`.

**The DB-side verification pattern** (`:254-262`) — the model for every "did the bootstrap actually work" assertion in this phase, and directly reusable for the managed Flexible Server's `jtoye_backup` role (Blocker C):

```bash
# VERIFY from the DB side, so a silently-failed bootstrap cannot masquerade as
# success and hand the CronJob a zero-row dump.
ROLE_BYPASSRLS="$(… psql -tAc "SELECT rolbypassrls FROM pg_roles WHERE rolname = '${BACKUP_ROLE}'" | tr -d '[:space:]')"
if [ "$ROLE_BYPASSRLS" != "t" ]; then
  echo "FAIL: role ${BACKUP_ROLE} is missing or does not have rolbypassrls … A dump under this role would capture ZERO rows from every FORCE-RLS table." >&2
  exit 1
fi
```

**Bootstrap ordering + guard shape**, `scripts/k8s-local-up.sh:12-40` and `:63-80`. The house pattern is a numbered ORDER block that states *why the order is load-bearing*, flags parsed at STEP 0 *"so a typo can never fall through into a mutating step"*, and guards **before** any mutation:

```
# ORDER (and why the order is load-bearing)
#   0. flags        — parsed FIRST …
#   2. compose XOR  — refuse while any compose APP container runs …
#  3b. cluster XOR  — a Stopped profile preserves etcd …
#   9. apply        — namespace first, then a server dry-run printed VERBATIM,
#                     then the real apply
# EXIT CODES: 0 = up, 1 = a guard refused or a step failed, 2 = usage / tooling.
```

**Staging's XOR is different and must be stated:** the local XOR is compose-vs-minikube. The staging analogue is the `kubectl` **context** guard — `azure_deploy_target` memory + 29-RESEARCH.md:925: the only kube context on this host is `sipbihs2aks`, **employer infrastructure**. Every `kubectl` must pass `--context` explicitly or run after `az aks get-credentials`, never against the ambient default. `k8s_local_assert_context` (`scripts/lib/k8s-local-guards.sh`) is the function to mirror.

---

### `scripts/staging-pitr-drill.sh` (utility, batch) — DPLY-04, D-10, Pitfall 8

**Analog A — the two-arm falsification table**, `docs/runbooks/backups.md:295-302`:

| Arm | Take the dump as | Restore, then `SELECT count(*) FROM products` | What it establishes |
|---|---|---|---|
| **A — the counterexample** | the **app** role (NOSUPERUSER, FORCE RLS, no GUC) | must be **`products = 0`** | the trap is real in *this* database, so the size floor and TOC listing are demonstrably not doing the work |
| **B — the real backup** | the **BYPASSRLS** dump role | must be **`products > 0`** | the artifact the CronJob uploads carries tenant data |

and its rule (`:300-302`): *"Run both, in the same session, against the same database. Arm B on its own is exactly the result a broken pipeline also produces once, by luck."*

**Analog B — what the pipeline's own checks are** (`docs/runbooks/backups.md:286-288`): `MIN_BACKUP_BYTES` (default 1000) and `pg_restore --list`. Arm A must show **both passing on a zero-row dump**.

**Analog C — the CronJob env contract** (`k8s/base/pg-backup-cronjob.yaml:49-108`), which the drill must not restate:

```yaml
                # --- database (dumps as the BYPASSRLS backup role, see
                #     infra/backups/create-backup-role.sql — the app role would
                #     capture 0 rows from FORCE-RLS tenant tables) ---
                - name: DB_USER
                  valueFrom:
                    secretKeyRef:
                      name: postgres-credentials
                      key: backup-username
                - name: PGPASSWORD
                  valueFrom:
                    secretKeyRef:
                      name: postgres-credentials
                      key: backup-password
```

**Pitfall 8 addition with no analog:** PITR creates a **second billable server**; parameters and firewall rules are not copied. Put the delete in a `trap` — the same `trap 'rm -f …' EXIT` discipline `check-no-plaintext-secrets.sh:46` uses for temp files, scaled up to a cloud resource.

---

### `k8s/scripts/check-render-invariants.sh` (test gate, static) — Blocker D, D-19

**INV-7's declared multiset** (`:337-358`) — this is the file that **must change in the same PR** as any NetworkPolicy port change:

```bash
# ---------------------------------------------------------------------------
# INV-7 (issue #271): the COMPLETE expected TCP egress port multiset toward the
# `jtoye-infrastructure` namespace, per NetworkPolicy, sorted numerically.
#
# __DB_PORT__ is substituted with the rendered app-config `db.port` of the target
# under test — that substitution IS the invariant. Everything else is a literal,
# deliberately: an exact allow-list is strictly stronger than "db.port is in
# there somewhere" …
#
# Adding a datastore port to a policy MUST be accompanied by adding it here. That
# is the intended friction: an egress allow-list is a security boundary, and a
# gate that silently accepted new holes in it would not be one.
# ---------------------------------------------------------------------------
declare -A NETPOL_INFRA_EXPECTED=(
  [core-java-allow]="__DB_PORT__ 5672 6379 9000 9093 61613"
  [pg-backup-allow]="__DB_PORT__ 9000"
)
INFRA_NAMESPACE_LABEL="jtoye-infrastructure"
```

**Fail-closed parse guards to copy for any new invariant** (`:837-868`) — every presence check `parse_fail`s (exit 2) rather than passing vacuously:

```bash
(( pol_seen > 0 )) || parse_fail "[$rel] INV-7 found 0 NetworkPolicy documents in the render. This platform ships six; zero means the parser is blind and every port assertion below would pass vacuously. Fix the parser, do not delete the invariant."
…
if [[ ! "$db_port" =~ ^[0-9]+$ ]]; then
    parse_fail "[$rel] INV-7: app-config 'db.port' is '$db_port', which is not a bare port number. A NetworkPolicy port that is not an integer is a NAMED port and matches no traffic."
fi
```

**Allowlist hygiene shape** (`:307-323`) — if D-19's "Prometheus/Alertmanager have no Ingress" becomes a new invariant, the allowlist idiom is `'<name>|<reason>'`, with **blank reason FAILS, duplicate FAILS, STALE FAILS**:

```bash
# Hygiene (same rules as check-env-contract.sh's allowlists): a blank reason
# FAILS, a duplicate FAILS, and a STALE entry — one whose Service now resolves in
# every target — FAILS, so the allowlist cannot quietly become a standing excuse
# for something that is already fixed.
ALLOW_UNRESOLVED_INGRESS_BACKEND=()
```

---

### `k8s/scripts/check-connection-math.sh` (test gate, static) — Pitfall 6, Blocker C

**Analog:** itself. The gate's inputs are all **parsed from real files** (`:52-76`) — extending it to read a *declared staging* `max_connections` follows the same rule:

```bash
MAX_CONNECTIONS=$(extract_number "$COMPOSE" \
    'max_connections=([0-9]+)' '\1' 'Postgres max_connections')
KEYCLOAK_POOL=$(extract_number "$COMPOSE" \
    'KC_DB_POOL_MAX_SIZE: "([0-9]+)"' '\1' 'Keycloak KC_DB_POOL_MAX_SIZE')
MAX_REPLICAS=$(extract_number "$DEPLOYMENT" \
    'maxReplicas: ([0-9]+)' '\1' 'HPA maxReplicas')
```

and the per-environment check loop (`:105-110`):

```bash
K8S_EXTRAS=$(( KEYCLOAK_POOL + PG_BACKUP + EXPORTER ))
check_env "k8s prod (HPA max+surge)"    $(( MAX_REPLICAS + SURGE )) "$POOL_K8S"    "$K8S_EXTRAS" "$K8S_EXTRAS_DESC"
check_env "k8s staging (HPA max+surge)" $(( MAX_REPLICAS + SURGE )) "$POOL_STAGING" "$K8S_EXTRAS" "$K8S_EXTRAS_DESC"
```

**The known blindness (RESEARCH Pitfall 6):** `RESERVED=3` (`:81`) is the PG default; Azure reserves **15**. And `MAX_CONNECTIONS` comes from the compose file regardless of what the managed server offers. Extending this is cheap and closes the class — the pattern is a new parsed input, never a hardcoded number.

---

### `.github/workflows/ci-cd.yaml` (config, batch) — D-04, #99

**Analog A — the `k8s-validate` job** (`:377-412`) is where new static gates land, one step each:

```yaml
  k8s-validate:
    name: K8s Kustomize Secret Guard
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v4
      - name: Set up kubectl
        uses: azure/setup-kubectl@829323503d1be3d00ca8346e5391ca0b07a9ab0d # v5.1.0
        with:
          version: 'v1.33.3'
      - name: Assert no plaintext Secrets in any kustomize build
        run: |
          chmod +x ./k8s/scripts/check-no-plaintext-secrets.sh
          ./k8s/scripts/check-no-plaintext-secrets.sh
      - name: Assert rendered-manifest invariants (DB_PORT, kube-dns selector, no localhost, DB role)
        run: |
          chmod +x ./k8s/scripts/check-render-invariants.sh
          ./k8s/scripts/check-render-invariants.sh
      - name: Assert the staging/production render matches its reviewed golden
        run: |
          chmod +x ./k8s/scripts/render-golden.sh
          ./k8s/scripts/render-golden.sh
```

> **Job-level `if:` is forbidden on any job in `build-and-push`'s `needs:` list** (`:944-953`): a skipped `needs:` entry skips the dependent job, which would silently *block* deploys instead of gating them.

**Analog B — the two steps being replaced** (`:1194-1197`):

```yaml
      - name: Configure kubeconfig
        run: |
          mkdir -p $HOME/.kube
          echo "${{ secrets.KUBE_CONFIG_STAGING }}" | base64 -d > $HOME/.kube/config
```

Replaced by the OIDC pair from 29-RESEARCH.md:781-793 (`permissions: id-token: write` + `azure/login@v2` + `az aks get-credentials`). **Everything downstream is already correct and needs no change.**

**Analog C — the premortem guard shape to extend for runtime parity** (`:1205-1218`), which Open Question 3 says is the k8s analogue of `check-runtime-freshness.sh`:

```yaml
          # Premortem guard: assert the RENDERED overlay actually pins :<sha> on
          # all three jtoye images BEFORE apply. A silent images[].name key
          # mismatch would fall back to the static 2.1.0 default and deploy the
          # wrong (or non-existent) image — fail loudly here instead.
          RENDERED="$(kustomize build k8s/staging)"
          for svc in core-java edge-go frontend; do
            if ! echo "$RENDERED" | grep -q "ghcr.io/bralabee/jtoye-${svc}:${{ github.sha }}"; then
              echo "FATAL: rendered staging overlay does not pin ghcr.io/bralabee/jtoye-${svc}:${{ github.sha }}"
              exit 1
            fi
          done
          kubectl apply -k k8s/staging
```

**Analog D — a fail-closed CI assertion with an explicit blindness exit** (`:1165-1192`, the CR-02 baked-origin gate). Note `exit 2` when the guard *cannot read its own input*:

```bash
          RENDER_API_URL="$(awk '/^[[:space:]]*api\.url:[[:space:]]/ { v = $2; gsub(/^"|"$/, "", v); print v; exit }' /tmp/staging-render.yaml)"
          if [ -z "$RENDER_API_URL" ]; then
            echo "FATAL: could not read app-config api.url from the k8s/staging render — this guard is blind, so it fails closed." >&2
            exit 2
          fi
```

Its comment at `:1157-1164` also states the D-08 consequence plainly: **with one frontend image and one `FRONTEND_PUBLIC_API_URL`, only ONE of the two deploy jobs can pass this gate.** #292 (CLOSED 2026-08-04) is the surface that changed this; verify against the tree, do not assume the comment is current.

---

### `core-java/src/main/resources/application.yml` (config, transform) — Pitfall 7

**Analog:** itself (`:32-42`). The `ssl:` block goes in with the same `${ENV:default}` injection style and a default that keeps compose byte-identical:

```yaml
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 8
```

**Two-sided contract:** `k8s/scripts/check-env-contract.sh` asserts injected↔read in **both directions**, so the new `redis.ssl` app-config key, the `REDIS_SSL` env in `core-java-deployment.yaml`, and the `application.yml` property must land in one change. The gate is blind to a capability absent from both sides (RESEARCH Pitfall 7) — which is exactly why it did not catch this.

---

### `docs/runbooks/staging-operations.md` (doc, prose) — D-18, #112

**Analog:** `docs/runbooks/credential-rotation.md`. Structure to mirror:

- **Owner + provenance line** (`:3-7`): who wrote it, which plan, which date, which decision it discharges.
- **A GLOBAL_RULE_6 banner** (`:9-13`): *"no literal credential value appears in this file, in any commit message, or in any tracked artifact."*
- **§1 "Read the fact off the RUNNING service — never off the config file"** (`:17-36`) — a three-column table of *surface → the fact, read live*, plus the trap that makes the obvious check vacuous.
- **§2 the acceptance shape** (`:39-58`) — both directions recorded in one run.
- **§7 "What did NOT go as written"** (`:182-198`) — the honest-residue section. This is the house shape for recording a drill that partially failed, and it is why the runbook is trusted.

**Its §6 already names this phase's obligation** (`:163-179`):

```
## 6. Phase 29 — carrying these to a staging deployment

D-02's stated purpose is that Phase 29's staging secrets follow **this same path** …
The Keycloak import (§3) has no compose-only equivalent in staging: the realm still lives in
Postgres, so the same `kc.sh import --override true` + restart applies against the deployed realm.
```

**Second analog for the operational half:** `docs/runbooks/backups.md` — headings `## What the backup job does` / `## How to run` / `## How to verify` / `### Falsifying the dump — the two-arm recipe` / `### In-cluster result — <date>`. The dated-result subsection is how a drill's evidence is recorded without rewriting the procedure.

---

### `docs/architecture/decisions/ADR-0002-…md` (doc, prose) — D-09

**Analog:** its own dated addendum. **Append, never rewrite** (CONTEXT §Established Patterns). Two edits:

1. Line 3, the Status line:
   ```
   **Status:** Proposed (2026-07-12) — needs owner sign-off before #101 implementation starts
   ```
   → Accepted, dated 2026-08-10, naming the discussion as the sign-off venue.

2. A new dated section in the shape of `## 2026-07-29 — Open question (Phase 27, plan 27-02)` (`:50-78`), which closes the open question it raised and points at the resolved horizon row. That section's closing line is the one being answered:
   ```
   **Status deliberately unchanged.** Resolving the operator question needs owner sign-off, which is a
   human decision and not an agent's to record.
   ```

---

### `infra/dependency-horizons.yaml` (config, static) — RESEARCH §Package Legitimacy Audit

**Analog A — a fully-pinned row with an exemption** (`:242-259`):

```yaml
  - id: prometheus
    pin: "prom/prometheus:v2.48.0"
    sites: ["infra/monitoring/docker-compose.monitoring.yml:8"]
    kind: image
    owner: maintainer
    eol_slug: prometheus
    eol_cycle: "2.48"
    eol_date: "2023-12-28"
    exemption:
      reason: >-
        Prometheus 2.48 went EOL 2023-12-28 … Deferred deliberately for ONE phase …
      expires: "2026-12-31"
      tracked_by: "DEFERRED-27"
```

**Analog B — the row this phase resolves** (`:497-514`):

```yaml
  - id: rabbitmq-k8s
    pin: unknown
    sites: []
    kind: out_of_repo
    owner: UNASSIGNED
    manual_review:
      last_checked: "2026-07-26"
      expires: "2026-10-26"
```

**Non-negotiable from the file header (`:22-35`): `eol_slug` is MEASURED, never derived from the image name.** New rows needed: cert-manager, rabbitmq cluster-operator, ingress-nginx controller, and the `sites:` update for every monitoring image now referenced from `k8s/` as well as compose.

---

### DPLY-02 disposition checklist (doc, prose)

**Analog:** `.planning/ISSUE-DISPOSITION.md` — the per-phase table (`| # | P | Title | Note |`, e.g. `:98-114` for Phase 29) plus the **"Deferred, with a dated reason"** table (`:229-235`), whose columns are `| # | Reason | Revives when |`. That second shape is the one DPLY-02's "closed-or-deferred **with a written reason**" requires; silence is the defect.

Note `:234` is now false and must be corrected in the same change:
```
| #296 | Conditional by its own title — "if an in-cluster Keycloak is ever deployed". Phase 29 targets an external IdP | an in-cluster Keycloak is actually deployed |
```
D-02 makes the condition **live**.

---

## Shared Patterns

### 1. Every file's header states the measured defect it prevents
**Source:** `k8s/local/scale-patch.yaml:1-40`, `k8s/base/networkpolicies/20-core-java.yaml:73-102`, `scripts/check-alert-liveness.sh:1-70`, `k8s/scripts/check-render-invariants.sh:337-350`
**Apply to:** every new YAML and every new script in this phase.
The shape is: **WHAT GOES WRONG → measured evidence (date, numbers, file:line) → WHY the chosen mechanism → what NOT to "fix".** A file whose header only describes what the code does is off-pattern.

### 2. Config injection — no environment-varying literal, ever
**Source:** `k8s/base/core-java-deployment.yaml:71-92`, `scripts/k8s-local-secrets.sh:26-30`
**Apply to:** Keycloak, monitoring, exporters, RabbitmqCluster, ClusterIssuer, both bootstrap scripts.
```
# NO ENVIRONMENT-VARYING LITERALS
#   No host or port literal appears below (GLOBAL_RULE_6 / ARCHITECTURE_RULE_8).
#   Every one comes from the K8S_LOCAL_* keys in .env … The namespace comes from
#   the local kustomization, which is its single source of truth.
```
Enforced by **INV-1/INV-2/INV-4** (`check-render-invariants.sh`) and `check-env-contract.sh` (both directions).

### 3. No `kind: Secret` in any kustomize build; secrets arrive out-of-band
**Source:** `k8s/base/kustomization.yaml:9-17`, `k8s/scripts/check-no-plaintext-secrets.sh:64-74`
**Apply to:** monitoring (Gmail app password, Grafana admin), Keycloak (admin + client secret), RabbitmqCluster (`secretBackend.externalSecret`), backup (`s3-backup-credentials`).
```bash
    # Gate 2: no top-level Secret objects at all. `^kind:` anchors to column 0,
    # i.e. document top-level fields only. SealedSecret does not match.
    if grep -q '^kind: Secret[[:space:]]*$' "$OUT"; then
```
The gate auto-discovers `k8s/*/kustomization.yaml` at depth 2 — a new `k8s/monitoring/` is covered the moment it exists.

### 4. Overlay owns its namespace; base owns nothing environment-specific
**Source:** `k8s/base/kustomization.yaml:5-8`, `k8s/local/namespace.yaml:1-15`, `k8s/base/pg-backup-cronjob.yaml:5-17`
**Apply to:** any new `k8s/monitoring/` or `k8s/keycloak/` kustomization.
```yaml
# It must also exist BEFORE a server-side dry-run of this overlay: a dry-run does
# not create the Namespace it is validating, so every namespaced object fails
# with "namespaces \"jtoye-local\" not found" until it does.
```

### 5. Goldens are regenerated with `--write`, never hand-edited; snapshot before editing
**Source:** `k8s/scripts/render-golden.sh:17-50`
**Apply to:** every plan touching `k8s/base/**` or any overlay.
```
#     --snapshot <label>     copy the CURRENT goldens to k8s/goldens/.pre/<label>/
#                            BEFORE editing. Exit 1 if <label> already exists …
#     --diff-since <label>   after `--write`, print the snapshot (OLD) vs the
#                            current golden (NEW) … EXIT 2 when the snapshot
#                            directory or either file inside it is missing — a
#                            missing baseline FAILS the caller's assertion instead
#                            of handing it an empty diff.
```
The forbidden anti-pattern is named explicitly (`:28-33`): `diff <(git show HEAD~1:<f> 2>/dev/null || cat <f>) <f>` **compares the file to itself** when `git show` fails, and passes vacuously.

### 6. Gate exit-code contract: 0 clean · 1 violation · 2 VOID — and VOID is never clean
**Source:** `scripts/check-alert-liveness.sh:61-68`, `k8s/scripts/check-no-plaintext-secrets.sh:18`, `k8s/scripts/check-connection-math.sh:25`
**Apply to:** `check-networkpolicy-enforcement.sh`, `staging-pitr-drill.sh`, the extended `check-alert-liveness.sh`, and every CI assertion added to `ci-cd.yaml`.
Corollary from `check-render-invariants.sh`: a **presence check that finds zero** is `parse_fail`, not a pass — *"Fix the parser, do not delete the invariant."*

### 7. Every new gate is either wired into CI or declared in `gate-enforcement.conf`
**Source:** `scripts/check-gate-enforcement.sh:23-32`, `scripts/gates/gate-enforcement.conf:1-25`
**Apply to:** all three new scripts.
```
# gate-enforcement.conf — gates that deliberately do NOT run in CI.
# Format:  <gate-basename.sh>  <reason>
# The bar for an entry is NOT "this gate is inconvenient in CI". It is "this gate
# inspects something a GitHub-hosted runner does not have, so it could only ever
# exit 2 (VOID) there" …
```
The conf's own `:27-33` note records the preferred direction: **wiring beats exempting whenever a real runtime is available** (e.g. `e2e-nightly.yml`, which brings a full stack up).

### 8. Displaced goods are replaced and named, never silently deleted
**Source:** `k8s/base/ingress.yaml:47-88`, `k8s/base/networkpolicies/50-observability.yaml:14-17`, `k8s/staging/configmap-patch.yaml:33-40`
**Apply to:** the ingress Keycloak rule, the observability placeholder policy, the "staging deliberately inherits `frontend`" note, ADR-0002's proposed status, SYSTEM_DESIGN_V2's WAL-G claim.

### 9. Two-arm proof: run the control/fail arm FIRST, record both directions
**Source:** `docs/runbooks/backups.md:281-311`, `docs/runbooks/credential-rotation.md:39-58`, 29-RESEARCH.md:596-601
**Apply to:** DPLY-01 (point `keycloak.client-id` at `does-not-exist` and confirm the login proof goes red), DPLY-03 (both alert gates), DPLY-04 (arm A zero-row dump), DPLY-05 (unlabelled probe → `TIMEOUT`), #592 (`UnsubscribeLinkRoutingTest` must FAIL on the current tree first).
```
Run **both, in the same session, against the same database.** Arm B on its own is exactly the result a
broken pipeline also produces once, by luck; arm A is what makes arm B mean something. Record both
counts, not just "restored OK".
```

### 10. Verify against the RUNNING thing, not the manifest
**Source:** `docs/runbooks/credential-rotation.md:17-36`, `infra/monitoring/docker-compose.monitoring.yml:10-27`, `scripts/k8s-local-secrets.sh:254-262`
**Apply to:** Grafana admin (only applied at first user creation), Keycloak realm (Postgres-backed, volume drop is a no-op), the security headers (`curl -sI … | grep -i strict-transport-security`), the deployed image digest.
```
# a config-level reading of this file ("the credential is injected, therefore it is
# not the default") was true and useless at the same time.
```

---

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `k8s/base/rabbitmq-cluster.yaml` (`RabbitmqCluster`) | model (CR) | pub-sub | No CRD of any kind exists in `k8s/` today. Use 29-RESEARCH.md:727-746 verbatim; wrap it in the house header pattern (Shared Pattern 1) and pin `spec.image` explicitly to resolve the `rabbitmq-k8s` horizon row. |
| `k8s/staging/cluster-issuer.yaml` + `Certificate` | model (CR) | event-driven | Same — no cert-manager CR in the repo. `k8s/base/ingress.yaml:8` only *references* `cert-manager.io/cluster-issuer: "letsencrypt-prod"`; nothing defines it. Use 29-RESEARCH.md:750-777, and note the staging-issuer-first rate-limit guidance there. |
| Azure provisioning (`az aks create` etc.) | utility | batch | No cloud-provisioning script exists in this repo at all. `scripts/k8s-local-up.sh`'s **structure** (numbered ORDER block, guards before mutations, `--dry-run-only` flag, evidence block at the end) is the nearest shape; the `az` commands come from 29-RESEARCH.md:230-244. |

---

## Metadata

**Analog search scope:** `k8s/` (base, staging, local, production, scripts, goldens), `scripts/` + `scripts/gates/` + `scripts/lib/`, `infra/monitoring/`, `infra/keycloak/`, `infra/backups/`, `docs/runbooks/`, `docs/architecture/decisions/`, `.github/workflows/`, `core-java/src/main/resources/`, `docker-compose.full-stack.yml`
**Files scanned:** 41 read (24 in full, 17 targeted by grep-then-offset)
**Pattern extraction date:** 2026-08-10
