---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 09
subsystem: deploy-layer
tags: [rabbitmq, cert-manager, mailhog, networkpolicy, supply-chain, kustomize]
requires:
  - "29-08 (the four staging hosts, the cert-manager.cluster-issuer app-config key, the SAN discipline)"
  - "29-07 (alertmanager-allow's inert `app: mailhog` egress rule, and the alerting.secondary.smtp.* staging keys that named mailhog:1025 as a forward reference)"
  - "29-06 (the `rabbitmq` and `rabbitmq-queues` scrape jobs already targeting jtoye-rabbitmq:15692)"
provides:
  - "an in-cluster, STOMP-enabled, explicitly pinned RabbitmqCluster declared in this repository"
  - "staging application-email capture (D-13), absent from production by construction"
  - "both cert-manager ClusterIssuers, so 29-11's promotion is a one-key config change"
  - "dated horizon rows for cert-manager, the cluster operator, ingress-nginx and agnhost; rabbitmq-k8s RESOLVED"
affects:
  - "29-11 (bootstrap ordering: cert-manager -> operator -> apply; and the one-key issuer flip)"
  - "29-10 (scripts/staging-secrets.sh must add a `default_user.conf` key -- DEF-29-4)"
  - "29-12 (the `rabbitmq` / `rabbitmq-queues` scrape jobs can now be UP)"
tech-stack:
  added:
    - "rabbitmq.com/v1beta1 RabbitmqCluster (cluster operator v2.22.3)"
    - "cert-manager.io/v1 ClusterIssuer (cert-manager v1.21.1)"
    - "mailhog/mailhog:v1.0.1 as a k8s workload (staging only)"
  patterns:
    - "kind: platform -- a sixth horizon-row kind for cluster components installed from pinned release manifests"
    - "additive egress-only NetworkPolicy, so an index-addressed rule list is never disturbed"
key-files:
  created:
    - k8s/base/rabbitmq-cluster.yaml
    - k8s/staging/mailhog.yaml
    - k8s/staging/cluster-issuer.yaml
    - k8s/local/rabbitmq-cluster-delete-patch.yaml
  modified:
    - k8s/base/kustomization.yaml
    - k8s/base/configmap.yaml
    - k8s/base/networkpolicies/20-core-java.yaml
    - k8s/base/networkpolicies/40-datastores.yaml
    - k8s/base/networkpolicies/50-observability.yaml
    - k8s/local/kustomization.yaml
    - k8s/staging/kustomization.yaml
    - k8s/staging/configmap-patch.yaml
    - k8s/scripts/check-render-invariants.sh
    - infra/dependency-horizons.yaml
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
decisions:
  - "The broker's NetworkPolicy selector is `app.kubernetes.io/name: jtoye-rabbitmq` -- read out of the operator's source, because GetLabels() DISCARDS every app.kubernetes.io/* label the CR carries"
  - "app-config rabbitmq.host / stomp.broker.relay-host repointed at the operator's client Service; without it the broker move is structurally green and functionally dead"
  - "k8s/local removes the CR from its render, preserving a local bring-up that apply --dry-run=server would otherwise abort"
  - "kind: platform added to the horizons schema rather than mislabelling three release manifests as images"
metrics:
  duration: "~2h"
  completed: "2026-08-11"
---

# Phase 29 Plan 09: Broker, Mail Trap, Issuers and the Supply-Chain Paperwork Summary

The message broker, the staging mail trap and the certificate issuers all stop being
things this repository *refers to* and become things it *declares* — and every
third-party artefact the phase introduced gets a dated horizon row, resolving
`rabbitmq-k8s` ten weeks before its own review expired.

## What Shipped

### 1. The broker is in-cluster, STOMP-enabled and pinned (D-09 / ADR-0002)

`k8s/base/rabbitmq-cluster.yaml` declares a `RabbitmqCluster` named `jtoye-rabbitmq`
with `spec.image: rabbitmq:4.3.4-management-alpine` set **explicitly**, credentials
via `secretBackend.externalSecret` (so no `kind: Secret` enters any build), and
`additionalPlugins: [rabbitmq_stomp]`.

The explicit image pin is the deliverable, not a formality. Accepting the operator's
default would have left `pin: unknown` true *in substance* — the deployed version a
property of whichever operator release happened to be installed — while the horizon
row claimed otherwise.

**Every operator behaviour this depends on was read out of the source at the pinned
tag `v2.22.3`, not taken from documentation:**

| Claim | Source, fetched 2026-08-11 (HTTP 200) | What it says |
|---|---|---|
| STOMP is 61613 | `internal/resource/service.go:163-171` | `AdditionalPluginEnabled("rabbitmq_stomp")` -> ServicePort 61613 named `stomp`. **Unconditional on TLS**; the `stomps`/61614 block at `:217-225` sits inside the TLS branch |
| the client Service is `jtoye-rabbitmq` | `service.go:31` + `rabbitmqcluster_types.go:529` | `ServiceSuffix = ""`, `ChildResourceName` joins on `-` then TrimSuffix's it |
| 15692 is always present | `service.go:285-291` | the metrics plugin is always enabled |
| the pod labels | `internal/metadata/label.go` | `Label()` sets name/component/part-of; **`GetLabels()` SKIPS every key prefixed `app.kubernetes.io`** |

That last row is the one that would have cost a night. `50-observability.yaml` had
explicitly refused to guess the selector — *"guessing a selector here would produce a
rule that silently matches nothing"* — and it was right to: this kustomization stamps
`app.kubernetes.io/part-of: jtoye-platform` onto the CR, and the operator **drops it
before it reaches a pod**. A policy selecting that label would match every workload in
the namespace except the broker.

**The declaration moved with the policy, in the same commit.** 5672 and 61613 left
`20-core-java.yaml`'s `jtoye-infrastructure` rule (a namespaceSelector cannot select a
pod that now lives in the app's own namespace) and became `spec.egress.6`/`.7` by
podSelector, one port each, appended so no existing index shifted.
`NETPOL_INFRA_EXPECTED[core-java-allow]` lost both ports in the same change.

Two debts recorded in `50-observability.yaml`'s "STILL OWED" section were paid and the
entries rewritten as records rather than deleted: the Prometheus egress to
`jtoye-rabbitmq:15692` (its ingress twin is `rabbitmq-allow`, landing together), and
`core-java -> mailhog:1025`.

### 2. Staging captures application mail; production cannot (D-13)

`k8s/staging/mailhog.yaml` — Deployment + ClusterIP Service on 1025/8025, **no
Ingress**, one replica (in-memory storage: two replicas would scatter the archive
behind one Service and make L-3 report a paging failure that is really a
load-balancing artefact), `strategy: Recreate`, and an **empty egress rule list**
because an archive of captured customer mail behind a pod that can dial out is an
exfiltration path.

`mailhog` was added to `NEVER_PUBLISHED_BACKENDS`, so INV-8 now fails any Ingress
routing to it. That is the sharpest of the three entries: Prometheus leaks metrics and
Alertmanager leaks a mute button, but Mailhog's unauthenticated API serves captured
customer names, addresses and order contents — the compose stack learned this as #441.

`core-java`'s hop to the sink is an **additive, egress-only** policy rather than an
edit to `core-java-allow`, because that policy's rules are addressed by index from four
kustomization files. `policyTypes` names Egress only: naming Ingress with an empty list
is the one way an "additive" policy can subtract.

### 3. Both ClusterIssuers exist (T-29-08-04)

`k8s/base/ingress.yaml` has annotated `letsencrypt-prod` for phases while **nothing in
this repository defined that issuer** — it lived only in a `kubectl apply -f -` heredoc
in `k8s/DEPLOYMENT.md:73-88`. On a cluster where that was never run by hand,
cert-manager logs "ClusterIssuer not found", no Certificate is created, and the Ingress
serves a self-signed certificate while remaining perfectly valid.

Staging issues against the ACME **staging** endpoint first: all four SANs share one
order, none of the four names resolves yet, and each failed production order burns
weekly duplicate-certificate quota that then blocks the retry. `letsencrypt-prod` ships
alongside so 29-11's promotion is one key in a ConfigMap, not a two-file edit under
time pressure. The ACME contact is injected from a new `cert-manager.acme-email` key,
adopting `devops@olajay.co.uk` — the role alias `k8s/DEPLOYMENT.md:81` already used.

### 4. Horizon rows for everything, and `rabbitmq-k8s` resolved

| Row | Before | After |
|---|---|---|
| `rabbitmq-k8s` | `pin: unknown`, `sites: []`, `kind: out_of_repo`, `owner: UNASSIGNED`, review expiring **2026-10-26** | pinned, sited at `k8s/base/rabbitmq-cluster.yaml:215`, `kind: image`, `owner: maintainer`, `manual_review` **dropped** |
| `cert-manager` | — | slug measured 200/no-redirect, cycle 1.21, `eol: false` |
| `rabbitmq-cluster-operator` | — | slug 404 both ways -> UNKNOWN + dated review |
| `ingress-nginx` | — | slug 404 -> UNKNOWN + dated review **+ a dated Gateway-API deferral** |
| `agnhost` | — | slug 404 -> UNKNOWN + dated review |
| `mailhog` | one site, note claiming "never deployed to staging or production" | two sites, note corrected |

Every `eol_slug` was **measured**, never derived — the file header's one
non-negotiable rule. `cert-manager` resolving to `cert-manager` is a measured
coincidence, and it is recorded as one.

The `ingress-nginx` row states the consequence plainly rather than implying it:
deploying a **retired** controller on a public edge in August 2026 ships a component
that will receive no security fixes ever again, and the AKS application-routing add-on
is a horizon extension to November 2026, not a fix. Its exemption expires
**2026-12-31**, deliberately shorter than this file's usual six months.

`kind: platform` was added as a sixth value with a written justification, following the
header's own `pseudo` precedent: three of the new artefacts are release *manifests*
pinned by version+sha256, not image references — H-1 could never discover them, and
calling them `out_of_repo` would exempt them from H-5, the one rule that can see them.
Verified that nothing outside the gate reads the field.

## Falsifiable Evidence

Every acceptance criterion was run in the **fail direction first**, against a
**committed** tree, with the restore verified by `git hash-object`.

| # | Arm | Result |
|---|---|---|
| 1 | `NETPOL_INFRA_EXPECTED` left at its old six-port value while the policy moved to four | **rc=1** on all four targets: `allows egress ports [5432 6379 9000 9093] … expected [5432 5672 6379 9000 9093 61613]` |
| 2 | one remaining infra port changed (9093 -> 9094), declaration untouched | **rc=1**, naming 9094 |
| 3 | `mailhog.yaml` copied into `k8s/base` and production re-rendered | `mailhog/mailhog:v1.0.1` **0 -> 1**; objects named `mailhog` **NONE -> {Service, Deployment}** |
| 4 | same leak, `render-golden.sh` in check mode | committed production golden `cmp` rc=**1** — the leak has a permanent guard, not just this plan's assertion |
| 5 | an Ingress added routing to the mailhog Service | INV-8 **rc=1**: *"Ingress 'arm-proof-mailhog' publishes host 'mail-staging.olajay.co.uk' and routes it to Service 'mailhog', which must never be published"* |
| 6 | `cert-manager.acme-email` changed to `ARM-PROOF@example.invalid` | both rendered issuers changed with it — the value is injected, not authored |
| 7 | `cert-manager`'s `sites:` pointed at a path that does not exist | **rc=2 (VOID)**: *"H-5 cert-manager: declared site file does not exist"* |
| 8 | `cert-manager`'s pin bumped to a version the script does not carry | **rc=2 (VOID)**: *"declared pin … NOT FOUND on any non-comment line"* |

**Clean state asserted last.** After every restore, all eight gates re-run at rc=0 on a
`git status --short`-empty tree.

### One criterion FAILED as written, and is reported rather than massaged

The plan asked that `grep -c 'pin: unknown' infra/dependency-horizons.yaml` be
**strictly lower** after the change. **It is not: 2 before, 2 after.**

This is the "a doc rule that must name the string it forbids" shape, firing on this
plan's own change. The composition:

| | comment/prose mentions | the row's own field | literal total |
|---|---|---|---|
| before | 1 | 1 | **2** |
| after | 2 (the history is quoted where the row was resolved) | **0** | **2** |

The strictly stronger form, anchored to the field:

```
rg -uu --count-matches --include-zero '^    pin: unknown\s*$'  ->  before 1, after 0
```

The before-tree's `1` is the **positive control** proving the anchored pattern can
match at all — without it, `0` would be a statement about the pattern. The gate's own
report agrees independently: `^UNKNOWN rabbitmq-k8s` goes **1 -> 0**, and
`^    owner: UNASSIGNED$` goes **1 -> 0**.

### A second criterion was unfalsifiable as written

The plan's Task 1 fail arm was *"change one RabbitMQ port in the policy WITHOUT
updating NETPOL_INFRA_EXPECTED"*. **After this change that arm cannot fail**: INV-7's
first arm keys strictly on the `jtoye-infrastructure` namespaceSelector, and both
RabbitMQ ports left its jurisdiction — a broken tree would pass it. Arm 1 above is the
strictly stronger substitute, and the substitution is written into
`check-render-invariants.sh` beside the map so the next editor does not re-derive it.

### And a third: the plan's expected-0 is 1 on a correct tree

The plan asked that `grep -c 'mailhog' /tmp/p.yaml` return **0** for production. The
real answer is **1**, on a completely correct tree: `alertmanager-allow`'s
`spec.egress.2` peer selector, authored in base by plan 29-07 and documented there as
*"INERT IN BASE, LOCAL AND PRODUCTION BY CONSTRUCTION: no pod carries `app: mailhog`
there"*. The substituted assertion is structural and stronger — objects whose
`metadata.name` is `mailhog`: `{Service, Deployment}` in staging, **NONE** in
production — plus the pinned-image count at 1 and 0, with arm 3 proving both zeros
falsifiable.

### Gate results (each rc recorded individually, final clean-tree run)

```
check-dependency-horizons.sh   rc=0    rows 28 -> 32, UNKNOWN 8 -> 10, exemptions 4 -> 5
render-golden.sh               rc=0    staging 4815 lines, production 4590
check-render-invariants.sh     rc=0    INV-1..8 x 4 targets, LOC-1..6
check-no-plaintext-secrets.sh  rc=0    staging 55 resources, production 49, 0 Secrets
check-env-contract.sh          rc=0
check-connection-math.sh       rc=0
check-alert-corpus-parity.sh   rc=0
check-gate-enforcement.sh      rc=0    37 gates, 6 workflows, 6 declared exempt
```

Golden diff (`--diff-since 29-09-pre`): resolve rc **0** (captured on the same
statement), stdout **504 lines** (non-empty), `grep '^>' | grep -c 'kind: Secret'` =
**0** — with a positive control on a deliberately Secret-bearing input returning 1,
since the pattern matches nothing anywhere in this diff and would otherwise be vacuous.

**Every removed line attributed** (15 total): 2x `rabbitmq.host` + 2x
`stomp.broker.relay-host` (both targets, the app-config repoint), 2x `- port: 5672` +
2x `- port: 61613` + 4x `protocol: TCP` (both targets, the ports moving rules), and 3
staging-only SMTP keys. Additions by object: production gains **only** `RabbitmqCluster`
and `rabbitmq-allow`; staging additionally gains the two Mailhog objects, both
ClusterIssuers and two Mailhog policies.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical functionality] `rabbitmq.host` and `stomp.broker.relay-host` repointed**
- **Found during:** Task 1
- **Issue:** `k8s/base/configmap.yaml` was not in `files_modified`, but both keys read
  `rabbitmq.jtoye-infrastructure.svc.cluster.local`. With the broker moved into the app
  namespace, that name resolves to nothing. The CR would render, the policy would permit
  the new pods, every gate would pass — and the application would still dial a dead name.
  A structural pass over a dead path. No later plan in this phase touches these keys
  (checked 29-11..29-16).
- **Fix:** both set to `jtoye-rabbitmq`, the operator's client Service. `k8s/local`
  already overrides both to `host.minikube.internal`, so local is unaffected (LOC-1 green).
- **Commit:** `53bfe80c`

**2. [Rule 3 - Blocking issue] `k8s/local` would have lost its bring-up**
- **Found during:** Task 1
- **Issue:** `k8s/local` renders `../base`, so it renders the `RabbitmqCluster`.
  `scripts/k8s-local-up.sh:453` runs `apply -k --dry-run=server`, and minikube has no
  operator, so the API server answers `no matches for kind "RabbitmqCluster"` and aborts
  the whole apply — not just that object. Regression by omission.
- **Fix:** `k8s/local/rabbitmq-cluster-delete-patch.yaml` (`$patch: delete`). Consistent
  with that overlay's stated design (it consumes the COMPOSE backing services), and the
  **opposite** call from 29-08's for Keycloak — deliberately, because LOC-2 compares
  Deployment counts and a `RabbitmqCluster` is not a Deployment, and because the failure
  modes differ in kind (one pod at `CreateContainerConfigError` vs an aborted apply).
- **Commit:** `53bfe80c`

**3. [Plan file-list correction] the RabbitMQ ports were in `20-core-java.yaml`, not `40-datastores.yaml`**
- **Found during:** Task 1
- **Issue:** the plan listed `40-datastores.yaml` as Task 1's policy file, but 5672/61613
  live in `20-core-java.yaml` (as the plan's own `read_first` implies). `50-observability.yaml`
  also needed the Prometheus egress half.
- **Fix:** all three edited; `40-datastores.yaml` received the broker's own policy and the
  header premise correction its old text had made false.
- **Commit:** `53bfe80c`

**4. [Rule 2 - Security] `mailhog` added to `NEVER_PUBLISHED_BACKENDS`**
- **Found during:** Task 2
- **Issue:** a new unauthenticated HTTP API holding captured customer mail, with nothing
  but convention preventing an Ingress being pointed at it.
- **Fix:** INV-8 entry + the fail arm proving it fires (arm 5).
- **Commit:** `9cd2e2b6`

**5. [Rule 1 - Bug] two stale citations, both self-inflicted**
- **Found during:** post-Task-3 sweep of every line reference written in this plan
- **Issue:** `cluster-issuer.yaml` cited `k8s/base/ingress.yaml:8` (the annotation is at
  `:32`; `:8` is 29-08's comment about it), and `mailhog.yaml` cited
  `configmap-patch.yaml:215` for the smarthost — a line **this plan's own additions**
  moved to `:281`.
- **Fix:** both corrected; the ingress note also now records that 29-08 turned the literal
  into a replacement default.
- **Commit:** `49b7becc`

### Process failure worth recording

While running the H-5 fail arm during Task 3, `git checkout -- infra/dependency-horizons.yaml`
was used to restore an **uncommitted** file — which reverted the entire set of Task 3
horizon-row edits, not just the break. This is the recorded
`trap_break_arm_revert_eats_fixes` failure, reproduced exactly. Caught immediately by
`git status --short` showing only the goldens modified; the edits were redone, **committed**,
and the arms re-run against the committed tree, where the restore is safe. The rule holds:
**commit before running arms.**

## Cross-Cutting Quality Contracts

| Dimension | Disposition |
|---|---|
| Web performance | **N/A** — no user-facing page touched |
| SEO / discoverability | **N/A** — no public surface; the one new public-adjacent object (ClusterIssuer) renders no HTML |
| AI agent-readiness | **N/A** — no API surface added or changed |
| Security | Addressed — see the threat register below; no `kind: Secret`, egress denied by default on the sink, INV-8 extended, retired-controller risk accepted **with a dated review** |
| Falsifiable evidence + runtime parity | **(a)** eight fail arms, three criteria reported as failed/unfalsifiable rather than massaged. **(b)** N/A for the runtime half — nothing is deployed by this plan, and the ONLY kube context on this host is the employer's, which is never a target. Verification is render-level throughout |

## Threat Register Disposition

| Threat ID | Disposition | Evidence |
|---|---|---|
| T-29-09-01 broker credentials in the build | mitigated | `secretBackend.externalSecret`; `check-no-plaintext-secrets.sh` rc=0, 0 Secrets in all four builds |
| T-29-09-02 real vendor mail from a rehearsal env | mitigated | staging-only; objects named `mailhog` = NONE in production, proven falsifiable by arms 3 and 4 |
| T-29-09-03 unpatched retired ingress controller | **accepted, dated** | `ingress-nginx` row with the security consequence stated and a Gateway-API deferral expiring 2026-12-31 |
| T-29-09-04 burning ACME quota on a four-SAN order | mitigated | staging issuer first; promotion is a one-key config change |
| T-29-09-05 egress hole when the broker moved | mitigated | INV-7 declaration moved in the same commit; arms 1 and 2 prove the friction fires |
| T-29-09-06 a row that disagrees with reality | mitigated | pin and row changed together; counts recorded before and after, including the one that did not move |
| T-29-09-SC supply chain | mitigated | provenance in 29-RESEARCH.md; sha256 pinned in `staging-bootstrap.sh`; dated rows land here. No npm/PyPI in scope — recorded, not skipped |

## Threat Flags

| Flag | File | Description |
|---|---|---|
| threat_flag: new-listener | `k8s/base/rabbitmq-cluster.yaml` | 5672/61613/15692/4369/25672 are new in-namespace listeners. All are governed by `rabbitmq-allow`; none is reachable from outside the namespace and none is published |
| threat_flag: unauthenticated-api | `k8s/staging/mailhog.yaml` | 8025 serves captured mail content with no authentication. ClusterIP only, no Ingress, INV-8-enforced, reachable in practice only by `kubectl port-forward` |
| threat_flag: cluster-scoped-object | `k8s/staging/cluster-issuer.yaml` | Two cluster-scoped `ClusterIssuer`s shipped from a namespaced overlay. Shared state across any overlay applied to the same cluster — which is why they live in one overlay and not in base |

## Known Stubs

None. Every object added renders complete and is wired to a real consumer: the broker to
`core-java` and Prometheus, the sink to Alertmanager and `core-java`, the issuers to the
Ingress annotation 29-08 already routed through app-config.

Nothing is deployed by this plan — the staging DNS is parked and the only kube context on
this host is the employer's, which is never a target. That is the plan's scope, not a stub:
`kubectl apply` belongs to 29-11.

## What 29-11 Must Not Rediscover

1. **`scripts/staging-bootstrap.sh` runs BEFORE any `kubectl apply -k`.** `kustomize build`
   renders the CR and the issuers without their CRDs; a **server-side** dry-run does not.
   cert-manager must be first of all — the RabbitMQ operator's own release manifest contains
   `cert-manager.io/v1` objects.
2. **`rabbitmq-credentials` needs a third key, `default_user.conf`** (DEF-29-4). Under
   `externalSecret` the operator projects that key and nothing else. Without it the broker's
   default user is not the one `core-java` authenticates as: `ACCESS_REFUSED` on a cluster
   where every static gate is green.
3. **The issuer flip is one key**, `cert-manager.cluster-issuer` in
   `k8s/staging/configmap-patch.yaml`, and only after a certificate has actually been produced.

## Self-Check: PASSED

Files claimed created — all present:
```
FOUND: k8s/base/rabbitmq-cluster.yaml
FOUND: k8s/staging/mailhog.yaml
FOUND: k8s/staging/cluster-issuer.yaml
FOUND: k8s/local/rabbitmq-cluster-delete-patch.yaml
```

Commits claimed — all present in `git log`:
```
FOUND: 53bfe80c  feat(29-09): in-cluster RabbitmqCluster with STOMP …
FOUND: 9cd2e2b6  feat(29-09): staging mail trap and both cert-manager ClusterIssuers
FOUND: 420dc8fd  chore(29-09): dated horizon rows …
FOUND: 49b7becc  docs(29-09): correct two self-inflicted stale citations …
```

No file deleted by any of the four commits (`git diff --diff-filter=D HEAD~1 HEAD`
empty at each).
