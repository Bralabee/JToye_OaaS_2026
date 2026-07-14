---
phase: 15
plan: "01"
subsystem: infrastructure
tags: [k8s, networkpolicy, sealed-secrets, security, infra-only]
status: implementation-complete
requirements: [INF-01, INF-02]
dependency_graph:
  requires: []
  provides:
    - k8s/base/networkpolicies/*
    - docs/runbooks/sealed-secrets.md
    - k8s/scripts/seal-secrets.sh
    - k8s/scripts/validate-networkpolicies.py
  affects:
    - k8s/base/kustomization.yaml
    - k8s/base/secrets-template.yaml
    - k8s/staging/ (inherits networkpolicies via base)
    - k8s/production/ (inherits networkpolicies via base)
tech-stack:
  added: [bitnami-labs/sealed-secrets, kubeseal, kubectl-NetworkPolicy]
  patterns: [default-deny + allow-list NetworkPolicies, SealedSecret ciphertext-in-git, offline-CI label-reference validation]
key-files:
  created:
    - k8s/base/networkpolicies/00-default-deny.yaml
    - k8s/base/networkpolicies/10-frontend.yaml
    - k8s/base/networkpolicies/20-core-java.yaml
    - k8s/base/networkpolicies/30-edge-go.yaml
    - k8s/base/networkpolicies/40-datastores.yaml
    - k8s/base/networkpolicies/50-observability.yaml
    - k8s/base/networkpolicies/README.md
    - k8s/scripts/validate-networkpolicies.py
    - k8s/scripts/seal-secrets.sh
    - docs/runbooks/sealed-secrets.md
    - .planning/phases/15-k8s-networkpolicies-sealed-secrets/15-RESEARCH.md
  modified:
    - k8s/base/kustomization.yaml
    - k8s/base/secrets-template.yaml
    - docs/CHANGELOG.md
decisions:
  - "Phase 15 is DRAFT-ONLY: operator install + live kubeseal need cluster-admin access not available here. Ship the manifests, runbook, and script; operational rollout is a checklist for cluster admin."
  - "Default-deny baseline via single 00-default-deny.yaml podSelector:{} + additive allow-lists. K8s OR-combines policies, order-independent, but alphabetical file prefix aids review."
  - "Public 443 egress uses ipBlock: 0.0.0.0/0 with RFC1918 in except[] rather than Stripe-CIDR allowlist — Stripe does not publish stable CIDRs (docs explicitly recommend DNS/SNI). Defense-in-depth egress-proxy option flagged as v2.3+ work."
  - "secrets-template.yaml retained in base/ for dev-bootstrap purposes; flagged legacy via header; overlay removal deferred to post-rollout cleanup."
  - "ROADMAP originally referenced k8s/overlays/staging — actual layout is k8s/staging + k8s/production (flat). Updated in this phase's CHANGELOG + SUMMARY."
metrics:
  duration: ~60min
  completed: 2026-04-18
---

# Phase 15 Plan 01: K8s NetworkPolicies + Sealed Secrets Summary

NetworkPolicies + Sealed Secrets drafted and wired; operator install + first
`kubeseal` conversion remain as a 4-step cluster-admin checklist.

## One-liner

Phase 15 ships the complete set of NetworkPolicy manifests (default-deny
baseline + per-tier allow-lists across all three app tiers + pg-backup) plus
the Sealed Secrets runbook and batch conversion script, leaving only the
cluster-admin-gated operator install + public-key export as manual rollout
work.

## What shipped

### INF-01 — K8s NetworkPolicies

- **`k8s/base/networkpolicies/` (6 manifests + README)** — wired into
  `k8s/base/kustomization.yaml` so both the staging and production overlays
  inherit pod-to-pod isolation automatically.
- **`00-default-deny.yaml`** — `podSelector: {}` baseline; every pod becomes
  NetworkPolicy-subject, denying all flows not explicitly allowed below.
- **`10-frontend.yaml`** — `app=frontend` ingress from `ingress-nginx:3000`;
  egress to DNS + `core-java:9090` + public 443 (Keycloak / CDNs / S3 public).
- **`20-core-java.yaml`** — `app=core-java` ingress from frontend + edge-go +
  Prometheus on 9090; egress to DNS + `jtoye-infrastructure` on
  5432 (Postgres), 6379 (Redis), 5672 (RabbitMQ AMQP), 61613 (RabbitMQ STOMP),
  9000 (MinIO), 9093 (Alertmanager) + public 443 (Keycloak / Stripe /
  Ollama-remote / CDNs).
- **`30-edge-go.yaml`** — `app=edge-go` ingress from `ingress-nginx:8080` +
  Prometheus; egress to DNS + `core-java:9090` + public 443 (Keycloak JWKS).
  No direct DB/cache/queue egress — intentional design.
- **`40-datastores.yaml`** — `pg-backup` CronJob egress (Postgres + S3/MinIO,
  no ingress) + inline documentation stub for the sister `jtoye-infrastructure`
  namespace to mirror.
- **`50-observability.yaml`** — inert placeholder until Prometheus/Grafana/
  Alertmanager pods migrate into our namespace.

Public 443 egress uses `ipBlock: 0.0.0.0/0` with RFC1918 ranges in `except[]`
to defend against SSRF pivots while accepting Stripe/CDN IP volatility.
Rationale + defense-in-depth egress-proxy option documented in the README
and `15-RESEARCH.md §4`.

### INF-02 — Sealed Secrets (draft-only)

- **`docs/runbooks/sealed-secrets.md`** — full operational runbook covering
  controller install via helm, public-key fetch, interactive + batch
  conversion, overlay wiring, dev/local `.env` fallback unchanged, 30-day
  automatic key rotation, emergency compromise rotation with full re-seal,
  rollback on decryption failure, mandatory off-cluster controller-key
  backup, and a cheatsheet.
- **`k8s/scripts/seal-secrets.sh`** — batch converter: multi-doc plaintext
  Secret YAML → one `<name>.sealed.yaml` per Secret in the output dir.
  Dep-checks `kubeseal` + `yq`; overrides target namespace per-doc;
  validates `kind: Secret` input + `kind: SealedSecret` output; prints
  `shred -u` reminder for plaintext input.
- **`k8s/base/secrets-template.yaml`** — retained on disk (dev bootstrap +
  living example for the runbook), flagged LEGACY via new header comment.

### Validation

- **`k8s/scripts/validate-networkpolicies.py`** — offline validator (no
  `kubectl` / cluster needed). PyYAML parse + `podSelector.matchLabels`
  cross-reference against every `Deployment`/`CronJob`/`Service` label in
  `k8s/base/`. Current run: 6 files parsed, 13 label refs, all resolve.
- **Live validation** is a manual cluster-admin step documented in the
  networkpolicies README.

## What's STILL PENDING (cluster-admin only)

The Phase 15 work is implementation-complete on the branch. The remaining
work requires cluster-admin access to the staging and production clusters
and CANNOT be done from this agent environment:

1. Install the `bitnami-labs/sealed-secrets` controller via helm.
2. Export the cluster's public key and commit it per-env.
3. Run `k8s/scripts/seal-secrets.sh` to convert plaintext Secrets to
   SealedSecrets; remove `secrets-template.yaml` from overlay resources.
4. `kubectl apply -k k8s/staging/` and `kubectl apply -k k8s/production/`
   to roll out both NetworkPolicies and SealedSecrets, then functionally
   verify connectivity per the README's test commands.

## Cluster admin rollout checklist (4 steps)

Full details in `docs/runbooks/sealed-secrets.md` + the
`k8s/base/networkpolicies/README.md`. The condensed sequence:

### Step 1 — Install the sealed-secrets controller

```bash
helm repo add sealed-secrets https://bitnami-labs.github.io/sealed-secrets
helm repo update

helm install sealed-secrets-controller sealed-secrets/sealed-secrets \
  --namespace kube-system \
  --set-string fullnameOverride=sealed-secrets-controller \
  --wait

# Verify
kubectl get pods -n kube-system -l name=sealed-secrets-controller
```

### Step 2 — Export the cluster public key

```bash
kubeseal --fetch-cert > k8s/certs/<env>/sealed-secrets-pub.pem
git add k8s/certs/<env>/sealed-secrets-pub.pem
# Commit on a PR; the file is safe to commit (it's a public cert)
```

Run once per cluster — staging and production have DIFFERENT keys.

### Step 3 — Convert plaintext Secrets → SealedSecrets

Build a plaintext file with the REAL values (locally, never committed),
then:

```bash
./k8s/scripts/seal-secrets.sh \
  --cert k8s/certs/production/sealed-secrets-pub.pem \
  --namespace jtoye-production \
  --input  /tmp/plaintext-secrets.yaml \
  --output k8s/production/sealed-secrets/

shred -u /tmp/plaintext-secrets.yaml
```

Then wire into the overlay by adding each `*.sealed.yaml` to
`k8s/production/kustomization.yaml` resources:, and remove
`secrets-template.yaml` from the overlay-inherited resources (either by
excluding it in the overlay or by adding a strategic-merge delete patch).

### Step 4 — Apply + verify NetworkPolicies and SealedSecrets

```bash
# Staging first
kubectl apply -k k8s/staging/

# Verify NetworkPolicies landed
kubectl get networkpolicy -n jtoye-staging
# Expect 6 policies: default-deny, frontend-allow, core-java-allow,
# edge-go-allow, pg-backup-allow, observability-placeholder

# Verify SealedSecrets decrypted
kubectl get sealedsecret -n jtoye-staging
kubectl get secret       -n jtoye-staging
# Counts should match: one Secret per SealedSecret

# Functional smoke: frontend cannot reach postgres, can reach core-java
kubectl exec -n jtoye-staging deploy/frontend -- \
  nc -z -w3 postgresql-primary.jtoye-infrastructure.svc.cluster.local 5432
# EXPECT: timeout / refused (policy working)

kubectl exec -n jtoye-staging deploy/frontend -- \
  wget -qO- http://core-java:9090/actuator/health
# EXPECT: {"status":"UP"}

# If staging is green, repeat for production:
kubectl apply -k k8s/production/
```

## Deviations from Plan

None — plan executed exactly as written. All 6 tasks completed atomically
with one commit each (plus this summary). No Rule 1-4 auto-fixes needed:

- No bugs found during execution (Rule 1).
- No missing critical functionality (Rule 2) — all planned threat
  mitigations present.
- No blocking issues (Rule 3) — Python 3.12 + bash in-env sufficient; no
  cluster access required for drafting phase.
- No architectural decisions (Rule 4) — the "draft-only" scope was
  explicit in the prompt.

## Known Stubs

`k8s/base/networkpolicies/50-observability.yaml` contains a deliberately
inert placeholder (`app: nonexistent-placeholder`) because no observability
pods ship from this repo yet. Documented in the file's own comment block
and in the README. This is intentional, not accidental — the file reserves
the slot for future Grafana/Prometheus/Alertmanager migration.

## Status

**Implementation-complete; cluster-admin rollout pending.**

The branch carries 6 atomic commits + this SUMMARY. INF-01 and INF-02
move from "Pending" to "Operationally Complete (cluster rollout pending)"
in `REQUIREMENTS.md`.

## Commit log

| # | Task  | Commit   | Message |
|---|-------|----------|---------|
| 1 | 15-01 | 69710e7  | docs(phase-15): NetworkPolicy research + pod label inventory |
| 2 | 15-02 | 1ec1187  | feat(phase-15): NetworkPolicies for all tiers + default-deny baseline |
| 3 | 15-03 | 5ac74b2  | test(phase-15): NetworkPolicy YAML + label-reference validation |
| 4 | 15-04 | a3755b5  | docs(phase-15): sealed-secrets runbook + conversion script |
| 5 | 15-05 | f59a0fb  | docs(phase-15): flag secrets-template.yaml as legacy + point to runbook |
| 6 | 15-06 | (this commit) | docs(phase-15): complete K8s NetworkPolicies + Sealed Secrets drafting (infra rollout pending) |

## Self-Check: PASSED

- All 12 planned files present on disk (6 NetworkPolicy manifests + README +
  2 scripts + runbook + research + SUMMARY).
- All 5 task-level commits resolve in `git log --oneline --all`: 69710e7,
  1ec1187, 5ac74b2, a3755b5, f59a0fb.
- Offline validator `k8s/scripts/validate-networkpolicies.py` passes:
  6 files parsed, 13 `podSelector.matchLabels` refs, all resolve to real
  workload labels.
- `k8s/scripts/seal-secrets.sh --help` exits 0 under `bash -n` syntax check.
- YAML parse verified via `python3 -c "import yaml; list(yaml.safe_load_all(...))"`
  across all 6 NetworkPolicies + the legacy-flagged secrets-template.yaml.
- `docs/runbooks/sealed-secrets.md` + `k8s/base/networkpolicies/README.md`
  + `.planning/phases/15-.../15-RESEARCH.md` all committed and cross-
  referenced from SUMMARY.md, CHANGELOG, ROADMAP, REQUIREMENTS, STATE.

