# K8s NetworkPolicies for `jtoye-production` + `jtoye-staging`

These policies enforce pod-to-pod isolation so a compromised pod cannot pivot
laterally to unrelated services. Every file in this directory is applied via
`k8s/base/kustomization.yaml`.

The cluster enforces them: staging runs AKS with Cilium as the network-policy
engine, chosen at cluster creation (Phase 29 D-01, plan 29-01). The manifests
stay plain `networking.k8s.io/v1` NetworkPolicy, and INV-7 refuses any other
policy kind in a render.

See `.planning/phases/15-k8s-networkpolicies-sealed-secrets/15-RESEARCH.md` for
the original threat model, and
`.planning/phases/29-deployable-staging-with-its-own-monitoring/29-RESEARCH.md`
(Blockers D and F) for why the allow-lists were rewritten for the deployed topology.

## Files

| File | Purpose |
|------|---------|
| `00-default-deny.yaml` | Deny-all baseline. Every pod is subject to NetworkPolicy; only subsequent allow-rules open flows. |
| `10-frontend.yaml` | `app=frontend`: ingress from the controller namespace; egress to core-java, Keycloak, DNS and public 443. |
| `20-core-java.yaml` | `app=core-java`: ingress from frontend, edge-go, the controller namespace and Prometheus (9091); egress to managed Postgres (public endpoint, `db.port`), Redis, RabbitMQ, Mailpit, Alertmanager, Keycloak, DNS and public 443. |
| `30-edge-go.yaml` | `app=edge-go`: ingress from the controller namespace and Prometheus; egress to core-java, Keycloak, DNS and public 443. Deliberately no direct DB/cache/queue access. |
| `40-datastores.yaml` | `pg-backup` CronJob: no ingress; egress to managed Postgres (`db.port`), DNS and public 443 (the Azure Blob backup account and the Entra ID token endpoint). |
| `50-observability.yaml` | Inert placeholder for the Prometheus/Alertmanager/Grafana pod-level policies plan 29-23 ships. The scrape rules already live in 20-/30-. |
| `60-acme-http01-solver.yaml` | cert-manager HTTP-01 solver pods (`acme.cert-manager.io/http01-solver=true`): ingress from the controller namespace on 8089, no egress. |

## Flow matrix

Every row is one rendered (direction, peer, port) rule. This is the same set
INV-7 in `k8s/scripts/check-render-invariants.sh` asserts per policy per target
(`NETPOL_RULES_EXPECTED`). The table and that array change in the SAME commit.

`<controller ns>` is app-config `ingress.controller-namespace`: **`traefik`** in
base, staging and production (Traefik v3 with its Ingress-NGINX provider is the
controller of record, D-29), **`ingress-nginx`** in `k8s/local` (the minikube
addon runs there). `<db.port>` is app-config `db.port`. "pod `X`" means a pod
with that label in the SAME namespace. "public" means `ipBlock 0.0.0.0/0 except
10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16`.

| Policy | Direction | Peer | Port |
|--------|-----------|------|------|
| `default-deny` | both | (none: every pod selected, nothing admitted) | |
| `frontend-allow` | ingress | namespace `<controller ns>` | TCP 3000 |
| `frontend-allow` | egress | `kube-system` pods `k8s-app=kube-dns` | UDP 53, TCP 53 |
| `frontend-allow` | egress | pod `app=core-java` | TCP 9090 |
| `frontend-allow` | egress | pod `app=keycloak` | TCP 8080 |
| `frontend-allow` | egress | public | TCP 443 |
| `core-java-allow` | ingress | pods `app=frontend`, `app=edge-go` | TCP 9090 |
| `core-java-allow` | ingress | namespace `<controller ns>` | TCP 9090 |
| `core-java-allow` | ingress | pod `app=prometheus` | TCP 9091 |
| `core-java-allow` | egress | `kube-system` pods `k8s-app=kube-dns` | UDP 53, TCP 53 |
| `core-java-allow` | egress | public (managed Postgres) | TCP `<db.port>` |
| `core-java-allow` | egress | pod `app=redis` | TCP 6379 |
| `core-java-allow` | egress | pod `app.kubernetes.io/name=rabbitmq` | TCP 5672, TCP 61613 |
| `core-java-allow` | egress | pod `app=mailpit` | TCP 1025 |
| `core-java-allow` | egress | pod `app=alertmanager` | TCP 9093 |
| `core-java-allow` | egress | pod `app=keycloak` | TCP 8080 |
| `core-java-allow` | egress | public | TCP 443 |
| `edge-go-allow` | ingress | namespace `<controller ns>` | TCP 8080 |
| `edge-go-allow` | ingress | pod `app=prometheus` | TCP 8080 |
| `edge-go-allow` | egress | `kube-system` pods `k8s-app=kube-dns` | UDP 53, TCP 53 |
| `edge-go-allow` | egress | pod `app=core-java` | TCP 9090 |
| `edge-go-allow` | egress | pod `app=keycloak` | TCP 8080 |
| `edge-go-allow` | egress | public | TCP 443 |
| `pg-backup-allow` | egress | `kube-system` pods `k8s-app=kube-dns` | UDP 53, TCP 53 |
| `pg-backup-allow` | egress | public (managed Postgres) | TCP `<db.port>` |
| `pg-backup-allow` | egress | public | TCP 443 |
| `acme-http01-solver-allow` | ingress | namespace `<controller ns>` | TCP 8089 |
| `observability-placeholder` | both | (selects no pod) | |

## The pod-label contract

The selectors above bind to these pod labels. The component plans (29-10,
29-12, 29-22, 29-23, 29-24) MUST label their pod templates exactly so; changing
one is a coordinated change across those plans, this directory and INV-7.

| Workload | Pod label | Service / ports |
|----------|-----------|-----------------|
| Keycloak | `app: keycloak` | `keycloak:8080`, management 9000 |
| Redis | `app: redis` | `redis:6379` |
| RabbitMQ (cluster operator, `RabbitmqCluster` named `rabbitmq`) | `app.kubernetes.io/name: rabbitmq` (set by the operator) | `rabbitmq`: 5672, 61613, 15692 |
| Mailpit | `app: mailpit` | `mailpit`: SMTP 1025, API 8025 |
| Prometheus | `app: prometheus` | |
| Alertmanager | `app: alertmanager` | 9093 |
| Grafana | `app: grafana` | |
| Exporters | `app: postgres-exporter`, `app: redis-exporter` | |
| Secret sync | `app: secret-sync` | |
| cert-manager HTTP-01 solver | `acme.cert-manager.io/http01-solver: "true"` (set by cert-manager) | 8089 |

## Assumptions this policy set depends on

1. **Namespace metadata label.** K8s >= 1.21 auto-applies
   `kubernetes.io/metadata.name=<namespace>` to every namespace. Our
   `namespaceSelector` rules rely on this (AKS is well past 1.21).

2. **kube-dns label.** CoreDNS in `kube-system` carries `k8s-app=kube-dns`.
   This is default in kubeadm / EKS / GKE / AKS. If your cluster uses
   `k8s-app=coredns` or similar, patch the `matchLabels` in every `egress`
   DNS rule.

3. **Ingress controller namespace is CONFIG, not a literal.** The four
   `namespaceSelector`s that admit the controller (frontend-allow ingress 0,
   core-java-allow ingress 1, edge-go-allow ingress 0, acme-http01-solver-allow
   ingress 0) are overwritten at render time from app-config
   `ingress.controller-namespace` by a `replacements:` entry in
   `k8s/base/kustomization.yaml`, the way #271 made the Postgres port
   config-driven. Base, staging and production name `traefik`. `k8s/local` sets
   `ingress-nginx`, its minikube addon's namespace, and repeats the replacement
   in its own kustomization (kustomize does not re-run a base replacement
   against an overlay's patched ConfigMap). To move a target to another
   controller, change that one key. The literal `traefik` in the policy files is
   only the base default.

4. **Every platform peer is in the application namespace.** Redis, RabbitMQ,
   Keycloak, Mailpit and Alertmanager run beside the application, so their rules
   are plain `podSelector`s. No policy names a separate infrastructure
   namespace.

5. **The Postgres port is DERIVED, not authored (issue #271).** The Postgres
   egress rule in `20-core-java.yaml` and `40-datastores.yaml` is its own
   single-port rule at egress index 1, whose port is overwritten at render time
   from app-config `db.port`, by the `replacements:` block each kustomization
   carries. Do not change the port by editing these files: set `db.port` for that
   environment, and make sure the `postgres-credentials` Secret `port` key (which
   is what the pods actually dial) says the same thing. `INV-7` in
   `k8s/scripts/check-render-invariants.sh` asserts the rendered rule set per
   policy per target, and `scripts/k8s-local-secrets.sh` refuses to create the
   local Secret when the two halves disagree.

6. **Postgres is a public peer (Blocker D, accepted as T-29-05).** The managed
   Flexible Server uses public access, so the Postgres rule's peer is the same
   RFC1918-excluding `0.0.0.0/0` as the 443 rule, scoped to `db.port`. Its
   public IP is not stable enough for a /32. The exposure is egress-only, from
   the two labelled workloads above, on one port, and the server's own firewall
   admits only the cluster's outbound IP. Phase 32 revisits this with private
   access. INV-7's `NETPOL_IPBLOCK_EXPECTED` refuses any other ipBlock shape, and
   any port on it other than 443 and `db.port`.

## Stripe + Keycloak egress tradeoff

Public 443/TCP egress uses `ipBlock: 0.0.0.0/0` with cluster-private CIDRs in
the `except:` list (SSRF-pivot defense). We accept the broader public surface
because Stripe does not publish a stable IP allowlist, a public Keycloak host is
typically behind a CDN with rotating IPs, and image CDNs similarly rotate.
In-cluster calls to Keycloak go to the pod directly (`app=keycloak` on 8080,
29-RESEARCH.md Pattern 5), not out through the LoadBalancer and back.

**Defense-in-depth option (NOT applied here, flagged as future work):**
- Deploy an egress-proxy pod (Squid/Envoy) with SNI-based L7 allowlist.
  Update core-java's public-443 egress rule to a `podSelector` on the proxy
  instead of `ipBlock: 0.0.0.0/0`. This is a v2.3+ effort gated on a proven
  threat or compliance requirement.

## Applying + verifying

### 1. Render via kustomize

```bash
kubectl kustomize k8s/staging/ | grep -A 2 "kind: NetworkPolicy"
# Expect: default-deny, frontend-allow, core-java-allow, edge-go-allow,
#         pg-backup-allow, observability-placeholder, acme-http01-solver-allow
#         (7 policies)
```

The authoritative static check is the gate, not a grep:

```bash
bash k8s/scripts/check-render-invariants.sh          # INV-7 per policy per target
bash k8s/scripts/check-render-invariants.sh --selftest-inv7   # five planted holes, each caught
```

### 2. Server-side dry-run (requires cluster + auth)

```bash
kubectl --dry-run=server apply -k k8s/staging/
# Must succeed. Any "NetworkPolicy spec is invalid" or "no matches for kind"
# is a real error.
```

### 3. Rollout to a staging namespace

```bash
kubectl apply -k k8s/staging/
kubectl get networkpolicy -n jtoye-staging
# Expect 7 policies listed.
```

### 4. Functional verification

**Read the port out of the environment; do not type it.** These are proofs of a
NEGATIVE ("this pod cannot reach Postgres"), and a negative proved against the
WRONG port proves nothing: if the database is on 5433, probing 5432 gets
"connection refused" because nothing is listening, and that is indistinguishable
from the policy doing its job. The port is environment config (issue #271), so
take it from the environment:

```bash
DBPORT=$(kubectl get cm app-config -n jtoye-staging -o jsonpath='{.data.db\.port}')
[ -n "$DBPORT" ] || { echo "VOID: no app-config db.port — do not proceed"; }
DBHOST=$(kubectl get secret postgres-credentials -n jtoye-staging -o jsonpath='{.data.host}' | base64 -d)
# Cross-check against what the pods actually dial (the Secret is the dialled value):
kubectl get secret postgres-credentials -n jtoye-staging -o jsonpath='{.data.port}' | base64 -d; echo
# The two MUST match. If they differ, stop: the policy permits one port and the
# application dials another, which is the #271 defect in a live namespace.
```

```bash
# Positive control FIRST: core-java SHOULD reach Postgres. Without this, a
# "refused" below is unattributable — it could be the policy, a wrong port, or a
# database that is simply down.
kubectl exec -n jtoye-staging deploy/core-java -- nc -z -w3 "$DBHOST" "$DBPORT"
# Expect: success. If this fails, every negative case below is meaningless.

# Negative case: frontend pod should NOT reach postgres.
kubectl exec -n jtoye-staging deploy/frontend -- nc -z -w3 "$DBHOST" "$DBPORT"
# Expect: connection timed out.

# Positive case: frontend pod SHOULD reach core-java.
kubectl exec -n jtoye-staging deploy/frontend -- \
  wget -qO- http://core-java:9090/actuator/health
# Expect: {"status":"UP"} or similar.

# Negative case: edge-go pod should NOT reach postgres.
kubectl exec -n jtoye-staging deploy/edge-go -- nc -z -w3 "$DBHOST" "$DBPORT"
# Expect: connection timed out.
```

The captured, two-arm denial (a control that connects, a denied probe that
prints `TIMEOUT`) is plan 29-17's `scripts/check-networkpolicy-enforcement.sh`,
including the Blocker-F pair: the controller namespace to core-java:9090 and
Prometheus to core-java:9091.

## Rolling back

If a policy blocks legitimate traffic, rollback is a single command per policy:

```bash
kubectl delete networkpolicy default-deny -n jtoye-staging
# Pods immediately revert to default-open for the removed policy.
```

The deny-all baseline is in `00-default-deny.yaml`; deleting that one alone
restores the namespace to pre-Phase-15 behaviour even if the other policies
remain in place (they become harmless allow-only rules with an open-by-default
backdrop). `acme-http01-solver-allow` only ever opens a flow, so removing it
can only break certificate issuance, never widen access.

## CI validation

CI runners have no cluster, so CI validates the RENDER (`ci-cd.yaml`, the k8s
manifest job): `k8s/scripts/check-render-invariants.sh` (INV-3 the kube-dns
selector, INV-7 the exact rule set per policy per target, the ipBlock shapes and
the policy kind, plus `--selftest-inv7`, which plants five holes in a copy of
`k8s/` and requires each to be caught) and `k8s/scripts/render-golden.sh`
(byte-equality with `k8s/goldens/`).

`k8s/scripts/validate-networkpolicies.py` (YAML syntax and pod-label references
against the shipped workloads and the contract above) is NOT wired into CI; run
it by hand from a Python environment with PyYAML.
