---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 03
subsystem: infra
tags: [kubernetes, traefik, ingress-nginx-provider, cert-manager, letsencrypt, rabbitmq-operator, rbac, bootstrap, supply-chain, bash, tdd]

requires:
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-01: jtoye-stg-ingress-ip in jtoye-rg with Network Contributor for the AKS control plane; jtoye-ci gets Cluster User + RBAC Writer on namespaces/jtoye-staging (D-32)"
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-02: app-config ingress.controller-namespace = traefik; acme-http01-solver-allow admits 8089 from the controller namespace; the pod-label contract (app=prometheus)"
provides:
  - "k8s/platform/traefik: Traefik v3.7.15 (tag + index digest) with the Ingress-NGINX provider, IngressClass nginx (k8s.io/ingress-nginx), watchNamespace jtoye-staging, allowSnippetAnnotations and proxyRequestBuffering on, LoadBalancer on jtoye-stg-ingress-ip with externalTrafficPolicy Local, least-privilege RBAC, NetworkPolicy, PDB"
  - "k8s/platform/traefik/supported-annotations.txt (86 rows, v3.7 support table) and README.md (D-29(a) record for all 20 in-use annotations plus cert-manager.io/cluster-issuer)"
  - "k8s/platform/cert-manager: ClusterIssuers letsencrypt-staging and letsencrypt-prod (HTTP-01 via ingressClassName nginx, account alerts@olajay.co.uk)"
  - "k8s/platform/rbac: jtoye-ci get/patch/update on Namespace jtoye-staging by resourceNames + CRUD on SecretProviderClass, Certificate, Issuer, RabbitmqCluster in jtoye-staging; subject token __CI_PRINCIPAL_OBJECT_ID__"
  - "k8s/platform/acme-probe: agnhost 2.66.1 netexec + Service + NetworkPolicy + Ingress on app-staging.olajay.co.uk/acme-probe with letsencrypt-staging"
  - "scripts/lib/staging-kube-guard.sh: staging_kube_guard CONTEXT IDENTITY_FILE (0 / 1 REFUSED [employer-context|identity-file|server-url] / 2 USAGE ERROR or VOID), sourced by 29-04 and 29-06"
  - "scripts/staging-bootstrap.sh: --context, --stage namespace|cert-manager|rabbitmq-operator|traefik|issuers|ci-rbac|acme-probe|acme-probe-delete|all, --dry-run, --identity-file, --selftest (7 arms)"
  - "scripts/check-dependency-horizons.sh K8S_GLOB_DIRS=(k8s/base k8s/platform); horizon rows traefik, agnhost, cert-manager, rabbitmq-cluster-operator"
  - "ci-cd.yaml k8s-validate: k8s/platform render + no-Secret step, bootstrap selftest step"
affects: [29-04, 29-06, 29-08, 29-09, 29-10, 29-11, 29-14, 29-15, 29-17, 29-23]

actuals:
  tokens: 23571
  tasks: 3
  commits: 4
plan_head_before: b3c51e6b09f447c51a98acfcca5ad0f350316f3b
plan_head_after: c78412c4be584fe420f96190098b9d472b1b0d12

tech-stack:
  added: ["traefik v3.7.15 (Ingress-NGINX provider)", "cert-manager v1.21.2 (release manifest, sha256-pinned)", "RabbitMQ cluster operator v2.23.1 (release manifest, sha256-pinned)", "agnhost 2.66.1 (netexec)"]
  patterns:
    - "Platform objects live under k8s/platform/<component> (depth 3): outside the render gates and goldens, applied only by the bootstrap with an explicit --context, covered by their own CI render step"
    - "One shared kube-context guard function (returns, never exits), sourced by every cluster-mutating staging script"
    - "Every upstream artefact a run needs is downloaded and sha256-verified before any stage runs; the pin in the script is also the horizon row's pin (H-5 drift on a one-sided bump)"
    - "Stubbed selftest: the script re-runs itself under env -i with an isolated PATH of stub kubectl/kubelogin/curl plus symlinked tools, a temp KUBECONFIG and identity file, and asserts rc, stderr tag and the stubs' argv logs"

key-files:
  created:
    - k8s/platform/traefik/traefik.yaml
    - k8s/platform/traefik/kustomization.yaml
    - k8s/platform/traefik/supported-annotations.txt
    - k8s/platform/traefik/README.md
    - k8s/platform/cert-manager/clusterissuers.yaml
    - k8s/platform/cert-manager/kustomization.yaml
    - k8s/platform/rbac/ci-deployer.yaml
    - k8s/platform/rbac/kustomization.yaml
    - k8s/platform/acme-probe/probe.yaml
    - k8s/platform/acme-probe/kustomization.yaml
    - scripts/staging-bootstrap.sh
    - scripts/lib/staging-kube-guard.sh
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-03-t3-red-employer-context.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-03-t3-red-server-url.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-03-t3-red-digest.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/deferred-items.md
  modified:
    - scripts/check-dependency-horizons.sh
    - infra/dependency-horizons.yaml
    - .github/workflows/ci-cd.yaml

key-decisions:
  - "Traefik sets allowSnippetAnnotations=true: measured in the v3.7.15 source, the flag defaults false and an Ingress carrying configuration-snippet is then SKIPPED WHOLE, so jtoye-ingress (api/app) would be unrouted, not merely header-less. Bounded by the provider's directive subset, CI-only Ingress writes in jtoye-staging, and a namespace-scoped Secret read"
  - "Traefik sets proxyRequestBuffering=true (NGINX's default): without request or response buffering the provider builds no Buffering middleware, so proxy-body-size 10m would be inert. Response buffering stays off; proxy-buffer-size and proxy-buffers-number are inert and that loss is accepted (they sized NGINX's upstream header buffers, a failure mode Traefik does not have)"
  - "publishService is not set and ingresses/status update is not granted: the provider resolves publishService through the watched-namespace informer, so traefik/traefik cannot resolve while only jtoye-staging is watched. Ingress status stays empty; the bootstrap reads the Service's own status"
  - "RBAC verbs are the documented list/watch only (no get); the CI namespace grant is get/patch/update by resourceNames with no unrestricted namespaces get"
  - "Upstream pins (cert-manager, operator) are recorded as horizon rows whose pin is the release URL at its script line, so a one-sided bump is H-5 drift (exit 2)"

patterns-established:
  - "k8s/platform/<component>: depth-3 platform kustomizations, bootstrap-applied, with a dedicated CI render/no-Secret step"
  - "staging_kube_guard is the single kube-context guard; a new cluster-mutating staging script sources it instead of re-implementing it"

requirements-completed: [DPLY-01, DPLY-02]

coverage:
  - id: D1
    description: "Traefik v3.7.15 with the Ingress-NGINX provider renders: one ClusterRole whose only resource is ingressclasses, Secrets read by a Role in jtoye-staging, IngressClass nginx/k8s.io/ingress-nginx, LoadBalancer on jtoye-stg-ingress-ip with externalTrafficPolicy Local"
    requirement: "DPLY-01"
    verification:
      - kind: other
        ref: "kubectl kustomize k8s/platform/traefik (rc 0; broken kustomization rc 1)"
        status: pass
      - kind: other
        ref: "anchored ClusterRole count: clusterroles=1 (planted second ClusterRole: 2, rc 1; unanchored count is 2 on the correct tree)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Every nginx.ingress.kubernetes.io annotation in the staging and production renders is in supported-annotations.txt (v3.7 support table); D-29(a) table records each"
    requirement: "DPLY-01"
    verification:
      - kind: other
        ref: "annotation coverage command: staging used=20 missing=0, production used=20 missing=0 (planted limit-rate: used=21 missing=1, rc 1)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The documented annotations actually behave at the live edge (security headers, CORS preflight, 429 on burst)"
    requirement: "DPLY-01"
    verification: []
    human_judgment: true
    rationale: "No cluster exists yet; supported-annotations.txt records what the provider DOCUMENTS. The live fidelity probes, each shown failing first, are plan 29-15."
  - id: D4
    description: "ClusterIssuers, the scoped CI deployer RBAC and the ACME probe render with no Secret; the CI RBAC has no wildcard and no cluster-admin binding"
    requirement: "DPLY-02"
    verification:
      - kind: other
        ref: "Task 2 verify line 1: cert-manager kinds=2, rbac kinds=4, acme-probe kinds=4, secrets=0 each, bad=0 (planted Secret in a temp copy of rbac: secrets=1, line rc 1)"
        status: pass
      - kind: other
        ref: "rbac render: star_items=0 cluster_admin_refs=0 (planted '*' verb: 1; planted cluster-admin roleRef: 1)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The CI principal's in-cluster permissions are sufficient and no wider than needed on the real cluster"
    requirement: "DPLY-02"
    verification: []
    human_judgment: true
    rationale: "Plan 29-11 records kubectl auth can-i --as for each verb before and after the ci-rbac stage on jtoye-stg-aks; that the Entra object id is the Kubernetes user name is also first observed there."
  - id: D6
    description: "staging-bootstrap.sh refuses no context, sipbihs2aks, a bad identity file, a stale/missing/port-less server URL, a tampered manifest and a missing kubelogin; dry-run mutates nothing"
    requirement: "DPLY-01"
    verification:
      - kind: unit
        ref: "bash scripts/staging-bootstrap.sh --selftest (7/7 PASS, rc 0; RED on the guard-free skeleton: employer-context, identity-file, server-url, digest, kubelogin FAIL, rc 1)"
        status: pass
      - kind: other
        ref: "Task 3 verify line 2: az_calls=0 in both scripts, control=1 prose=0 (planted az call: 1)"
        status: pass
      - kind: other
        ref: "no-flags run: rc=2 with USAGE ERROR"
        status: pass
    human_judgment: false
  - id: D7
    description: "Horizon gate discovers k8s/platform and every new pin has a row"
    requirement: "DPLY-01"
    verification:
      - kind: other
        ref: "bash scripts/check-dependency-horizons.sh (rc 0, missing-row=0, site-unresolvable=0; before the traefik row rc 1 naming traefik:v3.7.15; agnhost row removed rc 1, restored by sha rc 0; URL bump without the row rc 2 H-5; empty k8s/platform rc 2 VOID)"
        status: pass
    human_judgment: false

duration: 24min
completed: 2026-10-18
status: complete
---

# Phase 29 Plan 03: Platform Bootstrap Summary

**Traefik v3.7.15 with its Ingress-NGINX provider (snippets and request buffering deliberately on, after reading the pinned source), Let's Encrypt staging/prod ClusterIssuers, a resourceNames-scoped CI deployer RBAC, an ACME end-to-end probe, and a sha256-pinned bootstrap whose shared kube-context guard is shown refusing the employer context, a stale server URL and a tampered manifest before it was made to pass.**

## Performance

- **Duration:** 24 min
- **Started:** 2026-10-18T21:38:50Z
- **Completed:** 2026-10-18T22:03Z
- **Tasks:** 3
- **Files modified:** 19 (12 new manifests/scripts, 3 modified, 3 evidence records, deferred-items.md), plus this SUMMARY

## Accomplishments

- **Traefik measured, not inherited.** The plan asked for a re-measurement of the research's findings, and that was done against the pinned v3.7 pages and the v3.7.15 Go source. The research named four annotations as undocumented; all four are LISTED in the v3.7 table on 2026-10-18. The source then showed three more facts the plan did not have, each recorded in the README and the manifest header:
  - With `allowSnippetAnnotations` off, the whole Ingress is skipped, not just its headers.
  - `proxy-body-size` does nothing unless buffering is on.
  - `publishService` cannot resolve outside the watched namespace.
- **Least-privilege controller RBAC.** One ClusterRole, list/watch on `ingressclasses`. Everything else is a Role in `jtoye-staging`, matching the provider's measured informer scopes.
- **D-29(a) record.** `supported-annotations.txt` holds the 86-row v3.7 table. The README table covers all 21 annotations on the Ingresses. The four research-flagged rows state the 2026-10-18 measurement.
- **Issuers, CI RBAC, probe.** Issuers, the CI RBAC and the probe are reviewable manifests. The CI grant has no wildcard verb or resource and no cluster-admin binding. It names one Namespace by `resourceNames`, and four CRD kinds in `jtoye-staging`.
- **One kube-context guard.** `staging_kube_guard` returns and never exits. `staging-bootstrap.sh` verifies every upstream artefact before any stage runs, and the selftest shows the guard refusing in each named arm.
- **Horizon gate widened to `k8s/platform`.** Each directory VOIDs on its own when empty. Four new rows were added.
- **CI wiring.** The platform renders and the bootstrap selftest now run in `k8s-validate`.

## Task Commits

1. **Task 1: Traefik, RBAC, the D-29(a) table, horizon discovery**: `ab7f2550` (feat)
2. **Task 2: ClusterIssuers, CI deployer RBAC, ACME probe**: `8e5c593c` (feat)
3. **Task 3: staging-bootstrap.sh and staging_kube_guard (TDD)**
   - RED: `3f4de8f3` (test). Selftest plus guard-free skeleton, and three RED records.
   - GREEN: `c78412c4` (feat). Guard, digest refusal, horizon rows, CI steps.

**Plan metadata:** the docs commit carrying this SUMMARY.

## Evidence (both directions recorded)

### Task 1

**Verify 1** (`kubectl kustomize k8s/platform/traefik`): rc=0, empty stderr.
- Fail direction: a temp copy whose kustomization lists a missing file gives rc=1 ("accumulating resources … no such file or directory").

**Horizon discovery widened:**

| Step | Result |
|---|---|
| `k8s/platform` absent | `VOID: declared k8s directory not readable: k8s/platform`, rc 2 |
| `k8s/platform/traefik` present, no row yet (break arm) | `FAIL: H-1 traefik:v3.7.15@sha256:0f6150…3368 is pinned in the declared source surface but has NO horizon row`, `missing-row=1`, rc 1 |
| With the row (verify 2) | `rc=0 missing_row_zero=1 site_unresolvable_zero=1 traefik_rows=1` |
| Temp copy of the gate's inputs, `k8s/platform` holding only a kustomization | `VOID: H-1 discovered ZERO images in k8s/platform`, rc 2 (the per-directory rule) |

**AC: annotation coverage** (the plan's exact command):
- `staging rc=0 used=20 missing=0`, empty missing list, rc 0.
- `production rc=0 used=20 missing=0`, rc 0. Both renders use the same 20 annotations.
- Fail direction: `limit-rate` (in the page's Unsupported table) planted in a temp copy of `k8s/base/ingress.yaml` gives `used=21 missing=1`, prints `limit-rate`, rc 1.
- Extraction controls on the transcribed list:
  - Supported 86 rows and unsupported 45 rows overlap 0.
  - `proxy-buffer-size`, `proxy-buffers-number`, `proxy-connect-timeout`, `proxy-send-timeout` and `configuration-snippet` are each supported=1.
  - `limit-rate`, `mirror-target` and `stream-snippet` are each supported=0 and unsupported=1.
  - The versioned v3.7 page and the latest page give the same list (diff rc 0).

**AC: ClusterRole count:**
- `rc=0 clusterroles=1`, rc 0.
- Read-back: `traefik-ingressclasses`, apiGroups `networking.k8s.io`, resources `ingressclasses`, verbs `list, watch`.
- The unanchored count is 2 on this correct tree, as the plan warned.
- Fail direction: a planted second ClusterRole gives `clusterroles=2`, rc 1.
- Render kinds: ClusterRole 1, ClusterRoleBinding 1, Deployment 1, IngressClass 1, Namespace 1, NetworkPolicy 1, PodDisruptionBudget 1, Role 1, RoleBinding 1, Service 1, ServiceAccount 1.

### Task 2

**Verify 1:** `cert-manager rc=0 kinds=2 secrets=0`, `rbac rc=0 kinds=4 secrets=0`, `acme-probe rc=0 kinds=4 secrets=0`, `bad=0`, rc 0.
- Control: a `kind: Secret` planted in a temp copy of `k8s/platform/rbac` gives `rbac rc=0 kinds=5 secrets=1`, `bad=1`, line rc 1.

**Verify 2:**
- Before the agnhost row: `FAIL: H-1 registry.k8s.io/e2e-test-images/agnhost:2.66.1@sha256:6e2eef87…66a4 … has NO horizon row`, rc 1.
- With the row: `rc=0 missing_row_zero=1 site_unresolvable_zero=1 agnhost_rows=1`.

**AC: agnhost break arm** (on the committed state `8e5c593c`; clean, then arm, then clean):

| Step | sha (prefix) | agnhost rows | gate |
|---|---|---|---|
| CLEAN-BEFORE | `7b975bf9ac39dbfd` (= committed) | 1 | — |
| ARM: row removed | `c6915d3ca79750d2` | 0 | rc 1 naming the agnhost image |
| CLEAN-AFTER | `7b975bf9ac39dbfd` | 1 (pin line present: 1) | rc 0 |

The restore was verified by content, against the committed sha.

**AC: ci-deployer read-back:**
- Role `jtoye-ci-custom-resources`:
  - `secrets-store.csi.x-k8s.io/secretproviderclasses`
  - `cert-manager.io/{certificates,issuers}`
  - `rabbitmq.com/rabbitmqclusters`
  - Each with get, list, watch, create, update, patch, delete.
- ClusterRole `jtoye-ci-namespace-jtoye-staging`: `namespaces`, `resourceNames: [jtoye-staging]`, get, patch, update.
- Both bindings name `__CI_PRINCIPAL_OBJECT_ID__`.
- Counts: `star_items=0 cluster_admin_refs=0`.
- Controls: a planted `"*"` verb gives `star_items=1`. A planted `roleRef` to `cluster-admin` gives `cluster_admin_refs=1`.

### Task 3 (TDD)

**RED** (`3f4de8f3`, guard-free skeleton: the guard checks only for an empty context, and `fetch_and_verify` does not compare the sha256). Selftest rc 1:

```
PASS no-context rc=2 kubectl_calls=0 curl_calls=0
FAIL employer-context stderr lacks 'REFUSED [employer-context]': cert-manager v1.21.2: fetching https://github.com/cert-manager/... rabbitmq cluster-operator v2.23.1: fetch
FAIL identity-file (empty) stderr lacks 'REFUSED [identity-file]': cert-manager v1.21.2: fetching ...
FAIL server-url (jtoye-staging) stderr lacks 'REFUSED [server-url]': cert-manager v1.21.2: fetching ...
FAIL digest stderr lacks 'REFUSED [digest]': cert-manager v1.21.2: fetching ...
PASS dry-run rc=0 would_apply=6 cluster_calls=0 curl_calls=0
FAIL kubelogin rc=0 (want 2):
```

- **Classifier:** `gsd_run check tdd-red-evidence` returned `RED_EVIDENCE_OK` (`target_test_failed`, TAP, 7 tests) for the three required targets: `employer-context`, `server-url` and `digest`. Records: `evidence/29-03-t3-red-{employer-context,server-url,digest}.json`.
- **Semantic assessment:** each target arm executed. The child run got past the point where it should have refused. It reached the (stubbed) artefact download and the stages: sipbihs2aks and the stale `jtoye-staging` context were accepted, and the tampered body was never compared. The failure is the planned assertion (the refusal tag is absent from stderr), not a harness fault. The same harness PASSes `no-context` and `dry-run` in the same run.

**GREEN** (`c78412c4`). Verify line 1:

```
PASS no-context rc=2 kubectl_calls=0 curl_calls=0
PASS employer-context rc=1 kubectl_calls=0 curl_calls=0
PASS identity-file absent+empty+two rc=1 kubectl_calls=0 curl_calls=0
PASS server-url stale+missing+noport rc=1 kubectl_calls_beyond_config_view=0 curl_calls=0
PASS digest rc=1 curl_calls=1 kubectl_calls_beyond_config_view=0 (nothing applied)
PASS dry-run rc=0 would_apply=6 cluster_calls=0 curl_calls=0
PASS kubelogin rc=2 curl_calls=0 kubectl_calls_beyond_config_view=0
rc=0
missing=0 fail_lines=0
```

The verify line's own fail direction: empty output reports `missing=6`.

**Pass direction of the digest check on the real artefacts.** A stub kubectl was used here: it delegates only `config view`/`kustomize`, and every other call is logged and no-op'd. With it, a fake kubeconfig and real curl:
- `--stage cert-manager` gives `OK: cert-manager v1.21.2 verified: 1034400 bytes, sha256 e03b668e…879f`, rc 0.
- `--stage rabbitmq-operator` gives `OK: rabbitmq cluster-operator v2.23.1 verified: 360452 bytes, sha256 ae35a547…4687`, rc 0.
- The stub log shows the applies came only after verification.

**ci-rbac stage** (same stub):
- A UUID object id gives 2 substituted subjects and `token_left=0`, rc 0.
- `not-a-uuid` gives `REFUSED [identity-file] … is not an Entra object id (UUID)`, rc 1.

**Verify line 2:** `staging-bootstrap.sh az_calls=0`, `staging-kube-guard.sh az_calls=0`, `control=1 prose=0 bad=0`, rc 0. An `az` call planted in a temp copy is counted 1.

**AC: sha256 literals:** `e03b668e…879f` count=2 and `ae35a547…4687` count=2 (the header comment plus the variable).

**AC: no-flags run:** `USAGE ERROR: --context is required …`, `rc=2`, AC rc 0.

**H-5 drift arm on the new rows:** the cert-manager URL bumped to v1.21.3 in the script, with the row untouched, gives `H-5 cert-manager: declared pin … NOT FOUND`, rc 2. The file was restored from a scratch copy, and its sha equals the pre-arm sha.

### Whole-plan gates on the final tree

| Gate | rc |
|---|---|
| `check-render-invariants` | 0 (4 targets; `k8s/platform` not a target) |
| `render-golden` | 0 |
| `check-no-plaintext-secrets` | 0 |
| `check-env-contract` | 0 |
| `check-connection-math` | 0 |
| `check-gate-enforcement` | 0 |
| `check-az-subscription-pin` (and `--selftest`) | 0 / 0 |
| `check-no-measured-placeholders` | 0 |
| `check-dependency-horizons` | 0 (rows=32, missing-row=0, site-unresolvable=0) |

The new CI step body was run locally under `bash -e`: `platform_kustomizations=4 bad=0`, rc 0. A planted Secret in `acme-probe` gives `bad=1`, rc 1. `ci-cd.yaml` parses (PyYAML), and `k8s-validate` has 10 steps.

## Files Created/Modified

- `k8s/platform/traefik/traefik.yaml`: Namespace (PSA restricted), SA, ClusterRole/Binding, Role/Binding, Deployment, Service, IngressClass, NetworkPolicy, PDB.
- `k8s/platform/traefik/{kustomization.yaml,supported-annotations.txt,README.md}`.
- `k8s/platform/cert-manager/{clusterissuers.yaml,kustomization.yaml}`.
- `k8s/platform/rbac/{ci-deployer.yaml,kustomization.yaml}`.
- `k8s/platform/acme-probe/{probe.yaml,kustomization.yaml}`.
- `scripts/lib/staging-kube-guard.sh`: the shared guard.
- `scripts/staging-bootstrap.sh`: stages, artefact verification, `--dry-run`, `--selftest`.
- `scripts/check-dependency-horizons.sh`: `K8S_GLOB_DIRS`, with a per-directory VOID.
- `infra/dependency-horizons.yaml`: a new "k8s platform (Phase 29)" block with rows `traefik`, `agnhost`, `cert-manager` and `rabbitmq-cluster-operator`.
- `.github/workflows/ci-cd.yaml`: two `k8s-validate` steps.
- `evidence/29-03-t3-red-*.json` (3 files) and `deferred-items.md`.

## Decisions Made

See `key-decisions`. In short:
- Snippets on, because off would drop the whole Ingress.
- Request buffering on, because the body limit is otherwise inert.
- Response-buffer tuning: loss accepted.
- No `publishService`, and no `ingresses/status` grant.
- Documented verbs only.
- The upstream pins double as horizon pins.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `publishService=traefik/traefik` dropped, along with `ingresses/status update`**
- **Found during:** Task 1, reading `kubernetes.go`/`client.go` at v3.7.15.
- **Issue:** `GetService` refuses any namespace that is not watched. With `watchNamespace=jtoye-staging`, every status update would fail with "namespace is not within watched namespaces".
- **Fix:** Not set. The status verb is not granted, because it is never used. The bootstrap reads the Service's own status.
- **Files:** `k8s/platform/traefik/traefik.yaml`, `README.md`.
- **Commit:** `ab7f2550`.

**2. [Rule 2 - Missing critical] `--providers.kubernetesingressnginx.proxyrequestbuffering=true` added**
- **Found during:** Task 1, reading `middleware.go` `buildBuffering`.
- **Issue:** Under the provider defaults, `jtoye-ingress`'s 10m body limit is never applied: no Buffering middleware is built at all.
- **Fix:** Request buffering on, which matches NGINX. `sse-ingress` still opts out with its own `off`.
- **Commit:** `ab7f2550`.

**3. [Rule 2 - least privilege] RBAC verbs narrowed to the documented set**
- **Traefik:** list/watch, with no `get`, on the ClusterRole and the Role. The plan's text named get/list/watch, but also said "do not grant anything it [the documented RBAC] does not list". That RBAC lists no `get` for these resources, and the source issues none.
- **Traefik `configmaps` kept:** the documented RBAC lists it, and the informer waits for its sync.
- **CI namespace ClusterRole:** dropped the extra unrestricted `get` on namespaces. The `resourceNames`-scoped `get` already covers the read `kubectl apply` performs on the one Namespace in the render.
- **Commits:** `ab7f2550`, `8e5c593c`.

**4. [Rule 2 - supply chain] Images pinned by tag AND index digest**
- `traefik:v3.7.15@sha256:0f6150…`.
- `agnhost:2.66.1@sha256:6e2eef87…`.
- agnhost 2.66.1 is the newest tag the registry served; RESEARCH named 2.33.
- The horizon rows carry the full pinned strings.

**5. [Rule 2 - Missing critical] CI wiring**
- `k8s/platform` sits outside every render gate by design, so a Secret planted there would have passed CI. The `k8s-validate` job now renders the four platform kustomizations, and asserts each renders something and holds no Secret.
- The bootstrap selftest also runs there, following 29-02's precedent: a falsifiability arm that runs nowhere proves nothing after its commit.
- **Commit:** `c78412c4`.

**6. [Scope extension, recorded] The selftest has a seventh arm, `kubelogin`**
- The arm checks that a missing kubelogin gives a VOID with rc 2.
- The `server-url` arm also covers a missing context and a port-less URL.
- The `identity-file` arm covers absent, empty-value and two-value files.

**7. [Interpretation, recorded] The digest arm makes exactly one curl call**
- The behaviour list says every refusing arm makes no curl call. The digest arm cannot refuse a download without making it.
- So it asserts exactly 1 curl call and 0 kubectl calls beyond `config view`. The guard arms assert 0 curl calls.

**8. [Interpretation, recorded] `kubectl kustomize` in the ci-rbac stage carries no `--context`**
- It is a local render that never reaches a cluster.
- The selftest stub hands only `config view` and `kustomize` to the real kubectl. The dry-run arm asserts that 0 calls reach a cluster.

---

**Total deviations:** 8.
- 1 bug fix.
- 4 critical or least-privilege additions.
- 3 recorded extensions or interpretations.

**Impact on plan:** All of them make the edge correct or the checks falsifiable. No new scope beyond the plan's artefacts, apart from the two CI steps.

## TDD Gate Compliance

Task 3 (`tdd="true"`):
- RED `3f4de8f3` (`test(29-03)`) precedes GREEN `c78412c4` (`feat(29-03)`).
- RED was classified `RED_EVIDENCE_OK` for employer-context, server-url and digest. The semantic assessment is above.
- No REFACTOR commit was needed.

Tasks 1 and 2 are configuration tasks without a `tdd` flag.

## Issues Encountered

- **`check-doc-citations.sh` fails on this branch (C-3, `k8s/LOCAL.md:606`).** This is pre-existing, caused by 29-02's `k8s/base/configmap.yaml` line shift: the gate gives rc 0 at `7c687487`, and the cited `stomp.broker.mode` key is now at line 199. The gate is CI-wired (`ops-contracts`). It is out of scope for 29-03 and logged in `deferred-items.md` with the one-line fix.
- `/usr/bin/python3` is blocked by the base-python guard, so the docs HTML was converted to text in the `jtoye-ops` env.

## Known Stubs

None. `__CI_PRINCIPAL_OBJECT_ID__` is a substitution token, not a stub: the bootstrap refuses to apply while it survives.

## Threat Flags

None beyond the register. These threats are mitigated as planned:
- T-29-07 (snippet annotations): the subset, CI-only Ingress writes, and the namespace-scoped Secret read.
- T-29-08 (digest refusal, shown refusing).
- T-29-09 (the shared guard, shown refusing).
- T-29-10 (no wildcard, `resourceNames`).
- T-29-11 (the staging-issuer probe first).

Traefik's egress is deliberately left open: it must reach the API server and every backend. The backends' own policies bound what the `traefik` namespace may reach.

## User Setup Required

None. Nothing was applied to any cluster, and no Azure call was made.

## Next Phase Readiness

| Plan | What it gets from 29-03 |
|---|---|
| 29-04 and 29-06 | Source `scripts/lib/staging-kube-guard.sh` and call `staging_kube_guard "$CTX" "$IDF" \|\| exit $?` |
| 29-08 | Turns the annotation-coverage command into a render invariant against `supported-annotations.txt` |
| 29-09 | Must write `aks_api_server_fqdn`, `ingress_ip_address` and `ci_principal_object_id` into `k8s/staging/azure-identity.yaml` (bare host, IPv4, UUID; the bootstrap refuses other shapes) |
| 29-11 | Runs the stages live, records `kubectl auth can-i --as <ci oid>` before and after ci-rbac, then runs `--stage acme-probe-delete` |
| 29-15 | Proves the live fidelity of headers, CORS and 429 |

Caveat for 29-08 and 29-14: the HTTP-to-HTTPS redirect on `jtoye-ingress`, combined with the solver Ingress for the same host, is first exercised when the real hosts are issued (see README "Known caveat").

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Completed: 2026-10-18*

## Self-Check: PASSED

- FOUND: all 12 created manifests/scripts, the 3 RED evidence records, and deferred-items.md.
- FOUND (ancestors of HEAD): ab7f2550, 8e5c593c, 3f4de8f3, c78412c4.
- Commits were measured with `git rev-list --count b3c51e6b..HEAD` = 4 at SUMMARY write. `plan_head_after` was read from git.
