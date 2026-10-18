# Phase 29: Deployable Staging, With Its Own Monitoring - Pattern Map

**Mapped:** 2026-10-18 (on `main`, branch `docs/29-plan-on-main`)
**Files analyzed:** 38 new/modified
**Analogs found:** 34 / 38 (4 have no in-repo analog; use RESEARCH.md Code Examples)

**Rulings that override RESEARCH.md here (CONTEXT D-29..D-32 + Corrections):**
- Ingress = **Traefik v3 with the Ingress-NGINX provider** (D-29). Keep `nginx.ingress.kubernetes.io/*` annotations as source of truth; NetworkPolicy `namespaceSelector`s must name Traefik's namespace.
- Cluster CA trust = **JVM-wide truststore = JDK `cacerts` + cert-manager CA**, built at pod start (D-30). Do NOT use `spring.mail.ssl.bundle` (RESEARCH Code Examples "D-21 bundle" block is superseded).
- Seed order = **Stripe TEST PaymentIntent** settled through the webhook (D-31). No cash order.
- AKS = **Entra ID + Azure RBAC, local accounts disabled**, CI via reused `jtoye-ci` + `kubelogin` (D-32).
- DNS = Namecheap; alert SMTP = Zoho `smtp.zoho.eu:587`; Redis = in-cluster; k8s runs `prod` profile.

Every path below was checked with `git ls-files` on main. Paths marked **carry** exist only on `phase-29-research` (read with `git show phase-29-research:<path>`; never checkout that branch).

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `scripts/azure-staging-provision.sh` (new on main) | provisioning script | batch (imperative, guarded) | carry `phase-29-research:scripts/azure-staging-provision.sh`; guard shape from `scripts/k8s-local-secrets.sh` | exact (carry, adapt) |
| `scripts/staging-bootstrap.sh` | provisioning script | batch | carry `phase-29-research:scripts/staging-bootstrap.sh` | exact (carry; re-pin cert-manager v1.21.2, operator v2.23.1, Traefik) |
| `scripts/staging-secrets.sh` (D-23 write-once KV writer) | provisioning script | batch, write-once | carry `phase-29-research:scripts/staging-secrets.sh` + `scripts/k8s-local-secrets.sh` | role-match (rewrite target from k8s Secret to Key Vault) |
| `scripts/staging-secrets-preflight.sh` (derived-name preflight) | gate | transform (render → name list) | `k8s/scripts/check-no-plaintext-secrets.sh`, `k8s/scripts/check-env-contract.sh` | role-match |
| `scripts/staging-seed.sh` (D-26/D-27/D-31) | script | request-response (API client) | `scripts/smoke-test.sh`, `scripts/seed-order-metric.sh` | partial |
| `scripts/staging-pitr-drill.sh` | drill script | batch | carry `phase-29-research:scripts/staging-pitr-drill.sh`; arm-A shape from `scripts/check-backup-restore-drill.sh` | exact (carry, retarget PG 18) |
| `scripts/check-networkpolicy-enforcement.sh` | gate | request-response probe | carry `phase-29-research:scripts/check-networkpolicy-enforcement.sh` | exact |
| `scripts/check-alert-corpus-parity.sh` | gate | transform (md5) | carry `phase-29-research:scripts/check-alert-corpus-parity.sh` | exact |
| `scripts/check-deploy-digest-parity.sh` | gate | request-response (kubectl read) | carry `phase-29-research:scripts/check-deploy-digest-parity.sh`; `scripts/check-runtime-freshness.sh` | exact |
| `scripts/check-alert-liveness.sh` (modify: PROM_EXEC + SINK) | gate | request-response | itself (lines 80-85, 191-220, 358-359) + branch `PROM_EXEC` diff | exact |
| `k8s/scripts/check-connection-math.sh` (modify: per-target RESERVED) | gate | transform | itself (lines 41-60, 81-91) | exact |
| `k8s/scripts/check-env-contract.sh` (modify: drop DB_MIGRATION_* and #299 allowlist rows) | gate | transform | itself (lines 163-166, 232-234) | exact |
| `k8s/scripts/check-render-invariants.sh` (modify: INV-7 ingress rows, ipBlock arm, INV-10 widened to every WI workload) | gate | transform | itself (`NETPOL_INFRA_EXPECTED` line 470; INV-8..10 lines 211-222, 482-508) | exact |
| `scripts/gates/gate-enforcement.conf` (wire carried gates) | config | — | itself | exact |
| `k8s/base/networkpolicies/20-core-java.yaml` (Blocker F/D) | config (NetworkPolicy) | — | itself | exact |
| `k8s/base/networkpolicies/50-observability.yaml` (replace placeholder) + new `60-keycloak.yaml`, `70-messaging.yaml`, `80-mailpit.yaml` | config | — | `k8s/base/networkpolicies/20-core-java.yaml`, `40-datastores.yaml` | exact |
| `k8s/base/networkpolicies/README.md` (matrix) | doc | — | itself | exact |
| `k8s/base/serviceaccounts.yaml` (add SA per secret-reading workload) | config | — | itself | exact |
| `k8s/staging/workload-identity-patch.yaml` (widen to all workloads) | config (patch) | — | itself | exact |
| `k8s/staging/serviceaccount-clientid-patch.yaml` (new) | config (patch) | — | `k8s/staging/workload-identity-patch.yaml` | role-match |
| `k8s/staging/scale-patch.yaml` (new, Pitfall 1) | config (patch) | — | `k8s/local/scale-patch.yaml` | exact |
| `k8s/staging/configmap-patch.yaml` (client-id core-api, internal issuer, in-ns hosts, Mailpit SMTP, ONE_CLICK url) | config | — | itself + `k8s/local/configmap-patch.yaml` | exact |
| `k8s/staging/ingress-hosts-patch.yaml` (per-host TLS, LE staging issuer, noindex, ingressClassName traefik) | config | — | itself | exact |
| `k8s/staging/kustomization.yaml` | config | — | itself (lines 59-112) | exact |
| `k8s/components/azure-keyvault/` (SecretProviderClasses + CSI volume patches + `secret-sync` Deployment) | config (kustomize Component) | event-driven (CSI sync) | `k8s/staging/workload-identity-patch.yaml` (patch shape) | no direct analog (use RESEARCH Pattern 4) |
| `k8s/base/keycloak/` (Deployment, Service, Ingress auth host, realm-render init container) | config (workload) | request-response | `k8s/base/frontend-deployment.yaml`, `k8s/base/ingress.yaml` | role-match |
| `k8s/base/monitoring/` (prometheus/alertmanager/grafana/exporters + committed `alerts.yml` copy) | config (workload) | pub-sub (scrape/alert) | `infra/monitoring/*` (config) + `k8s/base/core-java-deployment.yaml` (Deployment shape) | role-match |
| `k8s/base/core-java-metrics-service.yaml` (headless, 9091) | config | — | Service in `k8s/base/core-java-deployment.yaml` | role-match |
| `k8s/base/rabbitmq-cluster.yaml` (RabbitmqCluster CR) | config (CR) | — | none in repo | no analog |
| `k8s/base/redis.yaml`, `k8s/base/mailpit.yaml` (+ cert-manager CA Issuer/Certificate) | config (workload) | request-response | `k8s/base/edge-go-deployment.yaml` | role-match |
| `k8s/base/core-java-deployment.yaml` (DB_MIGRATION_*, truststore init container, ONE_CLICK env) | config (workload) | — | itself | exact |
| `k8s/local/kustomization.yaml` (delete patches for operator CR, keycloak, CSI) | config | — | itself + `k8s/local/storage-env-patch.yaml` | exact |
| `k8s/goldens/staging.yaml`, `production.yaml` (regenerate) | generated | — | `k8s/scripts/render-golden.sh --write` | exact |
| `core-java/src/main/resources/application-prod.yml`, `application-staging.yml` (D-20 literals) | config | — | `core-java/src/main/resources/application.yml` mail block | exact |
| `core-java/src/test/java/uk/jtoye/core/notification/MailStartTlsPropertyTest.java` (extend) | test | — | itself | exact |
| `core-java/src/test/java/uk/jtoye/core/notification/dispatch/UnsubscribeLinkRoutingTest.java` (#592, read manifests) | test | — | itself | exact |
| `.github/workflows/ci-cd.yaml` deploy-staging (OIDC, kubelogin, kubectl bump, EXPECT_SWAGGER=false) | config (CI) | — | itself lines 1633-1877 | exact |
| `infra/dependency-horizons.yaml` (cert-manager, operator, traefik, mailpit, agnhost, B-series; `rabbitmq-k8s` pin) | config | — | itself (`rabbitmq-k8s` row ~552-561) | exact |
| `frontend/e2e/staging-login.spec.ts` (DPLY-01 L7 proof) | test (e2e) | request-response | `frontend/e2e/dashboard-mobile.spec.ts` | role-match |
| `docs/runbooks/staging-operations.md` (D-18 one page) + supersede notes in `docs/runbooks/sealed-secrets.md`, `k8s/DEPLOYMENT.md` | doc | — | `docs/runbooks/backups.md`, `docs/runbooks/azure-blob-provisioning.md` | role-match |
| `docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md` (dated D-24 amendment, append) | doc | — | itself | exact |
| DPLY-02 checklist (`.planning/phases/29-.../29-DPLY02-DISPOSITIONS.md`) + assertion script | doc + gate | — | `.planning/ISSUE-DISPOSITION.md` | partial |

## Pattern Assignments

### Bash gates and provisioning scripts (all `scripts/*.sh` above)

**Shared skeleton** — copy from `scripts/k8s-local-secrets.sh` lines 1-69 and branch `staging-secrets.sh` lines 82-116:
```bash
# EXIT CODES: 0 = done, 1 = a guard refused or a verification failed,
#             2 = parse / tooling failure.
set -euo pipefail          # gates use `set -uo pipefail` (check-*.sh on branch)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
refuse() { local arm="$1"; shift; echo "REFUSED [$arm]: $*" >&2; exit 1; }
void()   { echo "VOID: $*" >&2; exit 2; }
#   --context) shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --context needs a name" >&2; exit 2; }; KUBE_CONTEXT="$1" ;;
```
- **Guards first** (`k8s-local-secrets.sh` STEP 1 comment, lines 73-80): every refusal precedes every mutating call, so a refusal is provably a no-op.
- **Names only, never values** (lines 39-44): mirror `scripts/verify-env.sh`; staging-secrets must also use `az ... --output none`.
- **Subscription guard** (branch `azure-staging-provision.sh` lines 16-43): equality check against `c483d353-...`, refuse even when named for the refusal list; add `--enable-workload-identity`, `--enable-aad --enable-azure-rbac --disable-local-accounts` (D-32), `--enable-addons azure-keyvault-secrets-provider`, Cilium, ephemeral OS (RESEARCH Standard Stack).
- **Context refusal list** (branch `check-deploy-digest-parity.sh` line 108): refuse `sipbihs2aks`; assert the context's server URL equals the new cluster (stale `jtoye-staging` context exists).
- **Pinned upstream manifests** (branch `staging-bootstrap.sh` lines 15, 95, 138): URL + recorded sha256, digest mismatch = exit 1. Branch line 95 is about ingress-nginx `allow-snippet-annotations` — replace with Traefik install + `providers.kubernetesIngressNGINX` and the D-29 unsupported-annotation list (`proxy-buffer-size`, `proxy-buffers-number`, `proxy-connect-timeout`, `proxy-send-timeout`).
- **Cleanup trap before the billable act** (branch `staging-pitr-drill.sh` lines 52-87): `trap cleanup EXIT` installed BEFORE the restore request; run psql/pg_dump via `ghcr.io/bralabee/jtoye-pg-backup:18-blob` or `postgres:18`, never host 16.15 binaries.
- **Two-arm probe semantics** (branch `check-networkpolicy-enforcement.sh` lines 40-54): denied = literal `TIMEOUT`; RST is NOT a timeout; failed control arm = VOID exit 2. Add the Blocker-F arm (Traefik namespace -> core-java:9090; prometheus -> core-java:9091).
- **md5 parity** (branch `check-alert-corpus-parity.sh` lines 25, 64-84): `k8s/base/monitoring/alerts.yml` vs `infra/monitoring/prometheus/alerts.yml`; missing `md5sum` = VOID.

### `scripts/check-alert-liveness.sh` (modify, Blocker B)

Current docker binding to abstract (lines 80-85, 203-212):
```bash
MAILHOG_URL="${MAILHOG_URL:-http://localhost:8025}"
PROM_CONTAINER="${PROM_CONTAINER:-jtoye-prometheus}"
docker inspect "$PROM_CONTAINER" >/dev/null 2>&1 || void "L-0 container ..."
CTR_SUM=$(docker exec "$PROM_CONTAINER" md5sum "$PROM_ALERTS_PATH" 2>/dev/null | awk '{print $1}') || void ...
```
Introduce `PROM_EXEC` (default `docker exec "$PROM_CONTAINER"`, staging `kubectl --context X exec -n jtoye-staging deploy/prometheus --`) — carry the branch diff. Keep the comment at 194-201 (never `docker cp`; same logic for `kubectl cp`). Add `SINK=mailhog|mailpit`; line 358 `$MAILHOG_URL/api/v2/messages` becomes sink-dispatched (Mailpit `/api/v1/...`, read swagger first, A9). Keep the "unverifiable = VOID" wording.

### `k8s/scripts/check-connection-math.sh` (D-25)

Literal to replace (line 81) and parse helper to reuse (lines 41-50):
```bash
parse_fail() { echo "PARSE ERROR: $*" >&2; exit 2; }
extract_number() { ... [[ "$value" =~ ^[0-9]+$ ]] || parse_fail "could not extract $desc ..." }
RESERVED=3           # superuser_reserved_connections (PG default) — not usable by apps
```
Read `RESERVED` and `MAX_CONNECTIONS` per target from config (3 self-hosted / 15 Azure; staging `max_connections` 429 for B2s), via `extract_number`, so a missing value is exit 2. Fail arm: staging pool inside 429-3 budget but outside 429-15.

### `k8s/scripts/check-env-contract.sh` (Blocker E, #299)

Remove allowlist rows at lines 165-166 (`DB_MIGRATION_USER|Reviewed omission ...`, `DB_MIGRATION_PASSWORD|...`) in the same commit that wires the env vars, so the gate now requires them. If #299 is fixed, remove rows 163-164 and 232-234 together (row text itself says the whole set must land together).

### `k8s/base/networkpolicies/20-core-java.yaml` (+ new policies)

Analog is the file itself. Ingress rule shape to copy (lines 51-61), adding a Traefik namespace rule on 9090 and a Prometheus podSelector rule on 9091:
```yaml
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: monitoring
      ports:
        - protocol: TCP
          port: 9090
```
Egress: the Postgres rule must stay its own one-port rule (comment lines 76-105; `replacements` fieldPath `spec.egress.1.ports.0.port`). Change its peer from `jtoye-infrastructure` namespace to `ipBlock 0.0.0.0/0 except RFC1918` on `db.port` (Blocker D) and keep index 1. In-cluster peers (Redis/RabbitMQ/Mailpit/Keycloak) move to same-namespace `podSelector`s. Keep the header-comment convention (lines 1-23: Ingress/Egress bullet list + STRIDE ref). Update INV-7 `NETPOL_INFRA_EXPECTED` (`check-render-invariants.sh:470`) in lockstep; re-add branch `NETPOL_IPBLOCK_EXPECTED`.

### `k8s/staging/scale-patch.yaml`

Copy `k8s/local/scale-patch.yaml` verbatim in shape (lines 41-82: six documents, one field each; `maxReplicas` deliberately untouched — header lines 26-35 explain why, keep it). Register under `patches:` in `k8s/staging/kustomization.yaml`.

### `k8s/base/serviceaccounts.yaml`, `k8s/staging/workload-identity-patch.yaml`, client-id patch

ServiceAccount shape (serviceaccounts.yaml lines 29-36):
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: core-java
  labels:
    app.kubernetes.io/name: core-java
    app.kubernetes.io/component: backend
automountServiceAccountToken: false
```
Pod-template label patch (workload-identity-patch.yaml lines 21-41): `spec.template.metadata.labels.azure.workload.identity/use: "true"` for Deployments, `spec.jobTemplate.spec.template...` for CronJobs. Add documents for frontend, keycloak, rabbitmq (via `secret-sync`), monitoring, mailpit. Client-id annotation goes in a staging-only SA patch (header lines 22-28: "Phase 29's"). Do NOT add `AZURE_CLIENT_ID` as a manifest env var. Extend INV-10 to the wider SA set.

### `k8s/staging/kustomization.yaml`

Add `patches:` entries following lines 59-76 (bare `path`, or `target:` for Ingress), `components: [../components/azure-keyvault]`, and extend the `replacements:` block (lines 97-112) for Redis/RabbitMQ ports if made config-driven (#271 class).

### `k8s/staging/configmap-patch.yaml`, `ingress-hosts-patch.yaml`

Analog: themselves; `k8s/local/configmap-patch.yaml` already sets `keycloak.client-id: core-api` (Blocker A). Override the four `*.jtoye-infrastructure.svc.cluster.local` keys (`k8s/base/configmap.yaml:16,138,142,178`), set `keycloak.issuer.uri` internal / public issuer unchanged (`configmap.yaml:218-231`), replace the "SMTP deliberately NOT overridden ... SES" comment (lines 26-28) since it becomes false. `security.trusted-proxies` stays `""` until the Traefik pod CIDR is measured (lines 81-90). Ingress: per-host `tls` entries, `letsencrypt-staging` issuer first (base hardcodes `letsencrypt-prod` at `ingress.yaml:8`, `sse-ingress.yaml:17`), `X-Robots-Tag: noindex`.

### `k8s/base/monitoring/`

Config sources (D-16, one corpus): `infra/monitoring/prometheus/prometheus.yml.tmpl` (job names at lines 42, 74, 107, 120, 128, 138, 147, 200 — keep all eight names and `labels:` exactly; `check-alert-liveness.sh:118` VOIDs unknown jobs), `infra/monitoring/alertmanager/alertmanager.yml.tmpl` (receiver `email-default`, lines 28-40), `infra/monitoring/alertmanager/entrypoint.sh` (`ALERTMANAGER_MUTE_ALERTNAMES`, lines 75-140; mute `NoOrdersCreated`), Grafana provisioning under `infra/monitoring/grafana/provisioning/`. Image pins from `infra/monitoring/docker-compose.monitoring.yml` lines 35/82/112/160/180.
core-java job change (prometheus.yml.tmpl lines 74-84): replace `static_configs ['core-java:__CORE_JAVA_METRICS_PORT__']` and the constant `instance` relabel with `dns_sd_configs` on the headless `core-java-metrics` Service (per-pod instance; DEF-29-8). Alertmanager receiver gets two `email_configs` (Zoho + Mailpit with `tls_config.ca_file`). `alerts.yml` is a committed copy guarded by `check-alert-corpus-parity.sh`.

### `k8s/base/core-java-deployment.yaml`

- Add `DB_MIGRATION_USER/PASSWORD` from `postgres-credentials` keys `migration-username/migration-password`, mirroring the existing `DB_USER` secretKeyRef (lines ~103-112).
- D-30 truststore: init container that copies JDK `cacerts` and `keytool -importcert`s the cert-manager `ca.crt` into an emptyDir; JVM gets `-Djavax.net.ssl.trustStore=...` via `JAVA_TOOL_OPTIONS`. No in-repo analog for the init container; nearest volume pattern is the `/var/log/jtoye` emptyDir (~line 724). Break arm: CA-only truststore must red a Stripe/Blob call.
- #592: `NOTIFICATION_UNSUBSCRIBE_ONE_CLICK_BASE_URL` beside `NOTIFICATION_UNSUBSCRIBE_BASE_URL` (~line 463), from a new app-config key.

### `core-java/src/main/resources/application-prod.yml` + `application-staging.yml` (D-20)

Neither has a mail block today (only `spring:` at line 5). Add the literal block (RESEARCH Code Examples, first block only):
```yaml
spring:
  mail:
    properties:
      mail:
        smtp:
          starttls:
            enable: true
            required: true
```
Do NOT add the `SPRING_MAIL_SSL_BUNDLE` block (superseded by D-30).

### `MailStartTlsPropertyTest.java` (extend)

Copy the runner (lines 38-48) and loader (lines 72-79); add a loader that layers `application.yml` + `application-prod.yml` (and `-staging.yml`), and assert:
```java
assertThat(props.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
```
Include a case with `SMTP_STARTTLS=false` set under prod to prove the literal is not env-defeatable (shape of lines 64-70). RED first. Dev/default profile must stay opportunistic (existing test line 64 keeps passing).

### `.github/workflows/ci-cd.yaml` deploy-staging

Modify in place: lines 1650-1652 / 1875-1877 kubectl `v1.28.0` -> cluster minor; replace line 1765 `secrets.KUBE_CONFIG_STAGING` with `azure/login` (OIDC, `id-token: write`) + `az aks get-credentials --subscription ...` + `kubelogin convert-kubeconfig -l azurecli` (D-32); line 1847 `EXPECT_SWAGGER=true` -> `false` (preserves #549). Keep the CR-02 guard (~1693-1718) and its fail arm. Pin actions by sha as line 670 does.

### `frontend/e2e/staging-login.spec.ts`

Analog `frontend/e2e/dashboard-mobile.spec.ts` (real-KC login helper). Credential read from env injected from Key Vault at runtime (D-27), never a fixture. Record the verbatim authorize URL (`client_id=core-api`, encoded `redirect_uri`), post-login `/dashboard`, `<h1>`.

## Shared Patterns

### Exit-code contract (all gates)
**Source:** `scripts/k8s-local-secrets.sh` lines 60-61; branch gates. `0` clean, `1` violation/refused, `2` VOID (missing tool, empty discovery, unreachable). Empty output is never clean.

### Guards before mutation; names never values
**Source:** `scripts/k8s-local-secrets.sh` lines 39-44, 73-80; `scripts/lib/k8s-local-guards.sh`. Apply to provision, bootstrap, secrets, seed, PITR drill.

### No `kind: Secret` in any render
**Source:** `k8s/scripts/check-no-plaintext-secrets.sh` (+ INV-9). SecretProviderClass `secretObjects` reuse the existing Secret names/keys so no base `secretKeyRef` changes.

### Config-driven ports via `replacements:`
**Source:** `k8s/staging/kustomization.yaml` lines 87-112 and `20-core-java.yaml` lines 76-105. Any new port that varies by env follows this, and INV-7 asserts the rendered set.

### Third-party manifests outside `k8s/`
Applied by `staging-bootstrap.sh` by URL + sha256; only our CRs live in kustomizations (gates discover `-maxdepth 2` kustomizations; `render-golden.sh` TARGETS staging/production).

### Goldens and metrics regenerated in the same commit
`k8s/scripts/render-golden.sh --write`; `scripts/docs-freshness.sh --write` then `scripts/check-doc-metrics.sh`.

### Horizon row per new pin
`infra/dependency-horizons.yaml`, enforced by `scripts/check-dependency-horizons.sh`.

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `k8s/components/azure-keyvault/*` (SecretProviderClass, secret-sync Deployment) | config | event-driven | No CSI/SPC anywhere on main (RESEARCH: `rg -uu` rc=1); use RESEARCH Pattern 4 + Code Example |
| `k8s/base/rabbitmq-cluster.yaml` | CR | — | No operator CRs in repo; use upstream operator docs, pin `rabbitmq:4.3.6-management-alpine` |
| Traefik install/values in `staging-bootstrap.sh` | bootstrap | — | Branch installed ingress-nginx; Traefik Ingress-NGINX provider is new (D-29) |
| core-java truststore init container (D-30) | config | — | No init container in core-java today; design from D-30 text |

## Metadata

**Analog search scope:** `k8s/`, `scripts/`, `infra/monitoring/`, `infra/keycloak/`, `core-java/src/{main/resources,test/java/uk/jtoye/core/notification}`, `.github/workflows/ci-cd.yaml`, `docs/runbooks/`, `frontend/e2e/`, plus `phase-29-research:scripts/` (7 carried scripts, read-only)
**Files scanned:** ~45
**Pattern extraction date:** 2026-10-18
