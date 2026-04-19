# K8s NetworkPolicies for `jtoye-production` + `jtoye-staging`

These policies enforce pod-to-pod isolation so a compromised pod cannot pivot
laterally to unrelated services. All six files in this directory are applied
via `k8s/base/kustomization.yaml`.

See `.planning/phases/15-k8s-networkpolicies-sealed-secrets/15-RESEARCH.md` for
the full threat model, flow matrix, and design decisions that drove this.

## Files

| File | Purpose |
|------|---------|
| `00-default-deny.yaml` | Deny-all baseline. Every pod is subject to NetworkPolicy; only subsequent allow-rules open flows. |
| `10-frontend.yaml` | `app=frontend` — ingress from `ingress-nginx`, egress to core-java + DNS + public 443 (Keycloak, CDNs, S3). |
| `20-core-java.yaml` | `app=core-java` — ingress from frontend, edge-go, Prometheus; egress to infra namespace (Postgres/Redis/RabbitMQ/MinIO/Alertmanager) + public 443 (Keycloak/Stripe/Ollama/CDNs). |
| `30-edge-go.yaml` | `app=edge-go` — ingress from `ingress-nginx` + Prometheus; egress to core-java + DNS + public 443 (Keycloak JWKS). Deliberately no direct DB/cache/queue access. |
| `40-datastores.yaml` | `pg-backup` CronJob egress policy (Postgres + S3/MinIO only); documentation stub for `jtoye-infrastructure` to mirror. |
| `50-observability.yaml` | Inert placeholder for future Grafana/Alertmanager pod-level policies. Prometheus-scrape rules already live in 20-/30-. |

## Assumptions this policy set depends on

1. **Namespace metadata label.** K8s >= 1.21 auto-applies
   `kubernetes.io/metadata.name=<namespace>` to every namespace. Our
   `namespaceSelector` rules rely on this. For older clusters, label each
   referenced namespace manually:

   ```bash
   kubectl label ns ingress-nginx kubernetes.io/metadata.name=ingress-nginx
   kubectl label ns kube-system kubernetes.io/metadata.name=kube-system
   kubectl label ns jtoye-infrastructure kubernetes.io/metadata.name=jtoye-infrastructure
   kubectl label ns monitoring kubernetes.io/metadata.name=monitoring
   ```

2. **kube-dns label.** CoreDNS in `kube-system` carries `k8s-app=kube-dns`.
   This is default in kubeadm / EKS / GKE / AKS. If your cluster uses
   `k8s-app=coredns` or similar, patch the `matchLabels` in every `egress`
   DNS rule across the six files.

3. **Ingress controller.** Traffic enters the frontend + edge-go from pods in
   a namespace called `ingress-nginx`. If you run a different controller
   (Traefik, HAProxy, Istio gateway), rename the namespace label accordingly.

4. **Infrastructure namespace.** Datastores + Alertmanager live in
   `jtoye-infrastructure`. If your environment uses a different name (e.g.
   `data`, `platform`, `jtoye-infra`), update the `jtoye-infrastructure`
   references in `20-core-java.yaml` and `40-datastores.yaml`. All six
   references are in those two files — grep for `jtoye-infrastructure` to find
   them.

## Stripe + Keycloak egress tradeoff

Public 443/TCP egress uses `ipBlock: 0.0.0.0/0` with cluster-private CIDRs in
the `except:` list (SSRF-pivot defense). We accept the broader public surface
because Stripe does not publish a stable IP allowlist, Keycloak auth.jtoye.co.uk
is typically behind a CDN with rotating IPs, and image CDNs similarly rotate.

**Defense-in-depth option (NOT applied here, flagged as future work):**
- Deploy an egress-proxy pod (Squid/Envoy) with SNI-based L7 allowlist.
  Update core-java's public-443 egress rule to `namespaceSelector:
  egress-proxy` instead of `ipBlock: 0.0.0.0/0`. This is a v2.3+ effort
  gated on a proven threat or compliance requirement.

## Applying + verifying

### 1. Render via kustomize

```bash
kubectl kustomize k8s/staging/ | grep -A 2 "kind: NetworkPolicy"
# Expect: default-deny, frontend-allow, core-java-allow, edge-go-allow,
#         pg-backup-allow, observability-placeholder (6 policies)
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
# Expect 6 policies listed.
```

### 4. Functional verification

```bash
# Negative case: frontend pod should NOT reach postgres.
kubectl exec -n jtoye-staging deploy/frontend -- \
  nc -z -w3 postgresql-primary.jtoye-infrastructure.svc.cluster.local 5432
# Expect: connection timed out / refused.

# Positive case: frontend pod SHOULD reach core-java.
kubectl exec -n jtoye-staging deploy/frontend -- \
  wget -qO- http://core-java:9090/actuator/health
# Expect: {"status":"UP"} or similar.

# Negative case: edge-go pod should NOT reach postgres.
kubectl exec -n jtoye-staging deploy/edge-go -- \
  nc -z -w3 postgresql-primary.jtoye-infrastructure.svc.cluster.local 5432
# Expect: connection timed out / refused.
```

## Rolling back

If a policy blocks legitimate traffic in production, rollback is a single
command per file:

```bash
kubectl delete networkpolicy default-deny -n jtoye-production
# Pods immediately revert to default-open for the removed policy.
```

The deny-all baseline is in `00-default-deny.yaml`; deleting that one alone
restores the cluster to pre-Phase-15 behaviour even if the other five policies
remain in place (they become harmless allow-only rules with an open-by-default
backdrop).

## CI validation

Because CI runners do NOT have `kubectl` or cluster auth, this repo's CI
validates only YAML syntax + pod-label-reference consistency. Both checks are
embedded in the project's test suite — see Task 15-03 commit.
