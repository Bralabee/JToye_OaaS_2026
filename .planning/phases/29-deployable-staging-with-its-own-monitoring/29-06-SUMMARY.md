---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 06
subsystem: monitoring-manifests
tags: [k8s, prometheus, exporters, kustomize, configmap, alert-corpus, gates, dply-03]

# Dependency graph
requires:
  - phase: 27-observability-hardening
    provides: "the compose scrape corpus, the 19-rule alert corpus, and deferred-items.md §5 — the 'everything here is compose-scoped' record this plan closes"
  - plan: 29-03
    provides: "check-alert-liveness.sh L-0 made runtime-agnostic (PROM_EXEC / PROM_RUNTIME_PROBE / PROM_ALERTS_PATH), which is what makes 'exit 0 against the staging target' reachable at all"
  - plan: 29-04
    provides: "per-datastore egress + INV-7's ipBlock arm; the app-config redis.port/redis.host keys the redis-exporter derives its address from"
provides:
  - "A scraping Prometheus in the k8s base render with the eight compose job names intact, so check-alert-liveness.sh's three data blocks run unchanged against either runtime"
  - "postgres-exporter and redis-exporter pointing at the managed endpoints, with pg_up / redis_up — the two gauges DatabaseDown and RedisDown read"
  - "scripts/check-alert-corpus-parity.sh — md5 byte-equality between the k8s alert corpus copy and infra/monitoring, wired into k8s-validate"
  - "LOC-2's expected Deployment/HPA/PDB counts derived from the base render instead of a literal 3"
affects:
  - "29-07 (Alertmanager + Grafana + the observability NetworkPolicies these workloads need; Grafana's datasource is the prometheus:9090 Service this plan creates)"
  - "29-08 (the keycloak scrape job is authored and DOWN until the in-cluster Keycloak lands)"
  - "29-09 (the rabbitmq / rabbitmq-queues jobs target jtoye-rabbitmq:15692, the operator's client Service)"
  - "29-12 / 29-14 (first live deploy: where the $(VAR) DSN assembly and pg_up become observable in-cluster)"

tech-stack:
  added: []   # all three image pins already existed in compose and in the horizon manifest
  patterns:
    - "A resource SUBDIRECTORY under k8s/base with no kustomization.yaml of its own, so the maxdepth-2 gate discovery set does not grow (networkpolicies/ precedent)"
    - "Split exporter credentials (URI + USER + PASS) instead of one composed DSN, so no credential sits in a `value:` and no password is URL-parsed"
    - "A byte-equality gate standing in for a cross-root file reference kustomize refuses to make"
    - "An expected-N count read out of a sibling render rather than written as a literal, so it cannot go stale on a correct tree"

key-files:
  created:
    - k8s/base/monitoring/prometheus-config.yaml
    - k8s/base/monitoring/alerts.yml
    - k8s/base/monitoring/prometheus-deployment.yaml
    - k8s/base/monitoring/postgres-exporter-deployment.yaml
    - k8s/base/monitoring/redis-exporter-deployment.yaml
    - scripts/check-alert-corpus-parity.sh
  modified:
    - k8s/base/kustomization.yaml
    - k8s/scripts/check-render-invariants.sh
    - .github/workflows/ci-cd.yaml
    - infra/dependency-horizons.yaml
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml

key-decisions:
  - "The prometheus self-scrape target is prometheus:9090, NOT the planned loopback literal — INV-4 forbids that literal in every non-local render and it was measured failing on base, staging and production before the change"
  - "The k8s alerts.yml copy carries NO header of its own: a banner would break the byte-equality the parity gate exists to assert, and adding it to both files would VOID L-0 against every running compose Prometheus"
  - "postgres-exporter connects as the NOSUPERUSER runtime role, not the table owner; the pg_monitor upgrade is named as an operator step with its measured symptom"
  - "redis-exporter gets no probe — the compose reason (no shell for an exec check) does not transfer to k8s, so a k8s-specific reason was measured and recorded instead"
  - "The observability NetworkPolicies these workloads need are NOT written here: 29-07 owns 50-observability.yaml, and duplicating it would collide inside the wave"

requirements-completed: [DPLY-03]

metrics:
  duration: ~95 min
  tasks: 3
  commits: 3
  files-created: 6
  files-modified: 6
  completed: 2026-08-10
---

# Phase 29 Plan 06: Prometheus in Kubernetes, and One Alert Corpus Summary

**`k8s/` shipped zero monitoring manifests; it now renders a scraping Prometheus and two
exporters carrying the compose job names verbatim, and "one alert corpus" is an md5 gate
in CI rather than an intention.**

## What Shipped

**The scrape corpus.** `k8s/base/monitoring/prometheus-config.yaml` reproduces every
`job_name` and every `labels:` block of `infra/monitoring/prometheus/prometheus.yml.tmpl`,
header comments included, changing only the target address. That is not a style choice:
`check-alert-liveness.sh` maps rules to jobs through three hard-coded data blocks and
**VOIDs on any job it does not recognise**, so the names are the contract and the
addresses are the only variable.

| job_name | compose target | k8s target | why |
|---|---|---|---|
| prometheus | `localhost:9090` | **`prometheus:9090`** | INV-4 — see Deviations |
| core-java | `core-java:${CORE_JAVA_METRICS_PORT}` | `core-java:9091` | prod profile serves actuator on the management port |
| edge-go | `jtoye-edge-go:9101` | `edge-go:8080` | k8s leaves EDGE_MANAGEMENT_PORT unset |
| postgres | `jtoye-postgres-exporter:9187` | `postgres-exporter:9187` | this plan's Service |
| keycloak | `jtoye-keycloak:8080` | `keycloak:8080` | DOWN until 29-08 |
| redis | `redis-exporter:9121` | `redis-exporter:9121` | this plan's Service |
| rabbitmq | `jtoye-rabbitmq:15692` | `jtoye-rabbitmq:15692` | unchanged — the operator names the client Service after the cluster, which 29-09 calls `jtoye-rabbitmq` |
| rabbitmq-queues | same host, `/metrics/detailed` | same | `family=` params and the SSE drop rule preserved |

**Three cluster-internal workloads.** Prometheus (Deployment + ClusterIP Service,
`strategy: Recreate`, `readOnlyRootFilesystem` with the TSDB on a mounted volume) and the
two exporters. **No Ingress anywhere in the directory** (T-29-06-01) and no HPA/PDB, each
omission stated in its file header with the reason rather than left silent.

**The corpus gate.** `scripts/check-alert-corpus-parity.sh` md5-compares the k8s copy
against `infra/monitoring/prometheus/alerts.yml`. It exists because kustomize refuses to
read a `configMapGenerator` file from outside its own root and resolves a symlink the same
way — so "one corpus" is a copy plus an executable assertion, or it is nothing.

## Falsification — every arm run, with its control, clean state asserted last

### The parity gate, demonstrated at all three exit codes

| Arm | Broken input | Result |
|---|---|---|
| clean | — | **rc=0**, both md5 `7368261df6b44519ddd8d12267862856`, 565 lines each |
| A | one comment line appended to the k8s copy | **rc=1**, both md5s printed + a unified diff naming the line |
| B | the k8s copy renamed away | **rc=2** `alert corpus not found … a missing input is never clean (exit 2, not 0)` |
| B2 | the k8s copy truncated to zero bytes | **rc=2** `alert corpus is EMPTY … an empty file would md5-match another empty file` |
| clean (last) | restore | **rc=0**, restore verified by `git hash-object` = `a5d949b8921388ba9bf01cad8a52440c2d92a3b6` before and after |

B2 is not decoration. `[ -f X ]` alone would have let a zero-byte corpus md5-match another
zero-byte corpus and report PASS — the most complete drift possible, reported as clean.

### The eight job names, as an exact sorted set

Extracted from the **staging render**, not from the source file, and compared as a set:

```
core-java edge-go keycloak postgres prometheus rabbitmq rabbitmq-queues redis   (n=8)
set-diff rc=0
```

**Fail arm:** an unmapped ninth job (`node-exporter`) injected into the config → the same
comparison returned `3a4 > node-exporter`, **rc=1**. Restored, verified by
`git hash-object` = `1b30be18a3db6b07dd49c75733082c47e1223d65`, set-diff rc=0 again.
A spot check would have passed on that tree — which is the case that makes L-1b VOID.

The `rabbitmq-queues` job survives intact in the render: `family=` params present, and
`regex: 'order[.]state-changes[.]sse[.].*'` + `action: drop` at render lines 886-887.

### promtool — the rendered config actually loads (not merely "kustomize succeeded")

`kubectl kustomize` treats the scrape config as an opaque string, so a valid build says
nothing about whether Prometheus can read it. Extracted the 218-line config **out of the
staging render** and ran `promtool check config` inside the same `prom/prometheus:v2.48.0`
pin, at the same two mount paths the Deployment uses:

```
SUCCESS: /etc/prometheus/conf/prometheus.yml is valid prometheus config file syntax
Checking /etc/prometheus/rules/alerts.yml
  SUCCESS: 19 rules found
promtool rc=0
```

19 rules — the whole corpus — loading through the **absolute** `rule_files` path, so that
path is exercised rather than assumed.

**Control:** an invalid relabel action injected → `FAILED: unknown relabel action "dropp"`,
**rc=1**. Clean re-run last: rc=0.

Two instrument defects were caught by their own guards on the way, and both are worth
recording because each would have read as a pass:

1. The extractor first assumed 6-space indentation and produced **0 lines**. The
   `[ "$LINES" -gt 20 ]` guard VOIDed — without it, promtool would have been handed an
   empty file, which it accepts.
2. The first break arm's `sed` matched the source's 12-space indent, but the extracted
   file is dedented to 8, so it **changed nothing** and the arm produced output identical
   to the clean run — i.e. it read exactly like "promtool cannot fail". The arm now asserts
   that exactly one line was mutated before drawing any conclusion.

### The exporter env contracts, measured against the real images

Neither exporter's env-var contract was taken on trust. All four arms ran locally against
the pinned images (2026-08-10):

| Arm | Setup | Result |
|---|---|---|
| A | postgres-exporter with `DATA_SOURCE_URI`+`USER`+`PASS`, unreachable host, `--network none` | starts, **no** `empty dsn` warning — the split form is accepted |
| B **(control)** | identical, none of the three set | `level=warn msg="Failed to create PostgresCollector" err="empty dsn"` |
| C | the split form against the live compose Postgres as the **runtime** role | **`pg_up 1`**, `Established new database connection`, **5** `pg_stat_database_numbackends` series |
| D **(control)** | identical rig, deliberately wrong password | **`pg_up 0`**, `password authentication failed` |

B is what makes A mean anything (it proves the exporter *can* report the absence); D is
what makes C mean anything (it proves `pg_up` *can* be 0). Both rule-referenced series —
`pg_up` (alerts.yml:85) and `pg_stat_database_numbackends` (alerts.yml:97) — are present
under the least-privileged role available.

Arm C also measured the cost of that least-privilege choice rather than hand-waving it:
`collector failed name=wal err="pq: permission denied for function pg_ls_waldir"`. No rule
in the corpus reads WAL series, so nothing this platform alerts on is affected.

| Arm | redis-exporter setup | Result |
|---|---|---|
| plain | `REDIS_ADDR=redis://redis:6379` against the live plaintext Redis | **`redis_up 1`** |
| tls **(control)** | `rediss://redis:6379` against the **same** plaintext Redis | `Couldn't connect to redis instance (rediss://redis:6379)`, and `/metrics` **hung** (curl rc=28) |

The second arm is what makes the `rediss://` literal meaningful: if the scheme were
ignored, both arms would have reported 1 and the manifest literal would prove nothing.

### The remaining fail-direction arms

| Arm | Broken input | Result | Restore verified |
|---|---|---|---|
| INV-4 | the planned `localhost:9090` target | **rc=1** on base, staging AND production, naming the render line | by content (see Deviations) |
| scratch Secret | a `kind: Secret` document added to `k8s/base/monitoring/` | `check-no-plaintext-secrets.sh` **rc=1**, `- Secret: monitoring-scratch-arm` in all four targets | file deleted, `k8s/base/kustomization.yaml` hash `bb666a5893f680ba00960128b87dcc346b5e14d5` before and after, rc=0 |
| CI wiring | both workflow references renamed | `check-gate-enforcement.sh` **rc=1**, `check-alert-corpus-parity.sh` named as unwired | `ci-cd.yaml` hash `a7ab5a5953de02b5239134aeafd4be4c2b39b8d2`, rc=0 |
| LOC-2 | local overlay left prometheus at 2 replicas | **rc=1** `expected 6 Deployment object(s) … 5 of them at 1` | `k8s/local/kustomization.yaml` hash `5f0887286cf67b1bf830a5b8621dc09f0df89786`, rc=0 |
| horizon sites | one `sites:` path pointed at a non-existent file | **rc=2** `H-5 prometheus: declared site file does not exist` (VOID, not a plain FAIL) | `infra/dependency-horizons.yaml` hash `02b7fa9e02b1ffa421cfd53477534c12e88d608c`, rc=0 |

**CLEAN STATE ASSERTED LAST:** after every arm and after the final commit —
`render-golden.sh` rc=0 (both goldens match at 2836 lines), `git status --short` empty,
no probe containers left running.

## The discovery set did not grow — measured both sides

```
PRE-CHANGE   find k8s -maxdepth 2 -name kustomization.yaml | wc -l  ->  4
POST-CHANGE  same command                                           ->  4
             (k8s/base, k8s/local, k8s/production, k8s/staging)
```

That is the whole reason `k8s/base/monitoring/` carries no `kustomization.yaml` of its own.
A standalone `k8s/monitoring/` overlay would have become a fifth render target containing
**zero** NetworkPolicies, and INV-7's `pol_seen > 0 || parse_fail` guard reads zero as a
blind parser — so the new directory would have silently degraded the invariant that
guards the egress allow-list.

## Goldens — additive only, every line attributed

Snapshot `29-06-pre` taken **before Task 1's first edit**. `--diff-since` `resolve_exit=0`
(a 2 would mean the baseline is missing and the assertion is VOID, not passed); diff
non-empty.

```
removed(<) = 0     added(>) = 2404 (1202 per target)     1634 -> 2836 lines per target
```

| Added per target | What |
|---|---|
| 2 ConfigMaps | `prometheus-config` (scrape corpus) + `prometheus-alerts-k24fddf4kk` (hashed rule corpus) |
| 3 Deployments | prometheus, postgres-exporter, redis-exporter |
| 3 Services | all ClusterIP; **0 Ingress** |

`kind: Secret` among added lines = **0**, and that counter was shown able to fire: the same
awk over the same diff plus one synthetic `> kind: Secret` line returns **1**.

## Gate results — every rc recorded individually

| Gate | rc |
|---|---|
| `k8s/scripts/render-golden.sh` | 0 |
| `k8s/scripts/check-render-invariants.sh` | 0 |
| `k8s/scripts/check-no-plaintext-secrets.sh` | 0 |
| `k8s/scripts/check-env-contract.sh` | 0 |
| `k8s/scripts/check-connection-math.sh` | 0 |
| `scripts/check-dependency-horizons.sh` | 0 |
| `scripts/check-alert-corpus-parity.sh` | 0 |
| `scripts/check-gate-enforcement.sh` | 0 (37 gates, 6 workflows, 6 exempt) |
| `scripts/check-no-create-extension.sh` | 0 |

`check-alert-liveness.sh` and `check-alert-metrics.sh` were **not** run against the k8s
Prometheus, and that is not an omission being glossed: there is no jtoye cluster yet
(29-10/29-12 provision and deploy), and the only kube context on this host is the
employer's `sipbihs2aks`, which is never to be touched. What this plan owed them — a
target whose job names their data blocks already recognise — is delivered and asserted
statically, and the exact k8s invocation is recorded in the config file's header.

## Deviations from Plan

### 1. [Rule 3 — blocking] The `prometheus` job cannot target the loopback literal

- **Found during:** Task 1
- **Issue:** the plan's interface table and `29-RESEARCH.md:365` both say the self-scrape
  target stays `localhost:9090`, "unchanged". INV-4 in `check-render-invariants.sh`
  forbids that literal anywhere in a non-local render (the DEF-6 recurrence guard) and it
  is a **whole-render grep**. Measured with the planned value in place: `rc=1`,
  `FAIL … INV-4: forbidden local-only literal 'localhost'` on **base, production and
  staging**.
- **Fix:** the self-scrape goes through Prometheus's own ClusterIP Service,
  `prometheus:9090`. The `job_name` is untouched, so every gate keyed on job names is
  unaffected, and it is the same address 29-07 provisions Grafana's datasource with. The
  rejected alternative — adding an INV-4 exclusion — would have blinded a **production**
  defect guard across a whole target to buy one line.
- **Commit:** `4994cce4`

### 2. [Rule 1 — bug in new code] The header comment named the token it forbids

- **Found during:** Task 1, in the INV-4 arm above
- **Issue:** the first draft explained the change in a comment **inside** the
  `prometheus.yml: |` block. Everything in that block is ConfigMap **data** and lands in
  the render, so INV-4 flagged the comment line alongside the target line — the assertion
  would have failed on a correct tree. Same shape as a doc rule that must name the string
  it forbids.
- **Fix:** the in-blob comment describes the change without spelling the literal, and says
  why; the full explanation lives in the YAML header, which is not part of the data.
- **Commit:** `4994cce4`

### 3. [Rule 3 — blocking] LOC-2's expected count was wrong on a correct tree

- **Found during:** Task 2
- **Issue:** LOC-2 hard-codes "exactly 3 Deployments at `replicas: 1`". The three
  monitoring singletons make the local render six — **all six correctly at 1** — so the
  gate reported `expected 3 … found 6 object(s), 6 of them at 1`. An expected-N that is
  wrong on the correct tree, exactly the vacuous-criterion shape this repo has a rule for.
- **Fix:** the three expected counts (Deployment / HPA / PDB) are now read out of the
  **base** render, which is the discipline the same invariant's maxReplicas arm already
  uses and which its header calls for by name. This is **stronger** than the literal, not
  a relaxation: the weak form `ones == total` would pass on a local render that had LOST a
  Deployment; comparing against base catches that too. Shown to still fire (arm above).
  The OK message now prints the measured counts rather than a hardcoded "x3", so the
  summary line cannot disagree with what was measured.
- **Commit:** `db4dd637`

### 4. [Rule 2 — missing critical functionality] Exporter credentials, sourced and scoped

- **Found during:** Task 2
- **Issue (a):** the plan says the redis-exporter address comes from `redis-credentials`.
  That Secret carries **only** a `password` key — no host — in both the template
  (`secrets-template.yaml.example:143`) and `scripts/staging-secrets.sh`.
- **Fix (a):** host and port come from the app-config keys core-java itself dials
  (`redis.host`, `redis.port`), which is also the correct coupling: a literal here would
  keep reporting `redis_up` on the old endpoint the day the cache moves — a green light
  for a cache nothing uses.
- **Issue (b):** the plan says `DATA_SOURCE_NAME`. A single composed DSN puts a credential
  reference inside a `value:` field and subjects the password to URL parsing.
- **Fix (b):** the measured split form (`DATA_SOURCE_URI` + `DATA_SOURCE_USER` +
  `DATA_SOURCE_PASS`). Every credential-bearing env is `valueFrom`; the only two `value:`
  envs in the whole monitoring slice are `DATA_SOURCE_URI` and `REDIS_ADDR`, neither of
  which carries a credential, and `sslmode=require` stays reviewable in this repo instead
  of hiding inside an operator-supplied Secret.
- **Issue (c):** no exporter-specific DB role exists. The compose analog defaults to the
  **superuser**.
- **Fix (c):** the NOSUPERUSER, non-BYPASSRLS `runtime-username`/`runtime-password` pair —
  the least-privileged role that exists in `postgres-credentials` today, so no new operator
  obligation and no CrashLoop on first deploy. The `pg_monitor` upgrade is recorded with
  its measured symptom.
- **Commit:** `db4dd637`

### 5. [Criterion corrected, not silently substituted] The copy cannot carry a header

The plan asks for `k8s/base/monitoring/alerts.yml` to be **byte-identical** to the
canonical corpus *and* for its header to state that it is a copy. Those are mutually
exclusive: a banner inside the copy makes the two files differ by construction, so the
parity gate could never pass; adding it to **both** changes the canonical file's md5 and
VOIDs `check-alert-liveness.sh`'s L-0 against every already-running compose Prometheus,
because a single-file bind mount detaches on inode change.

Byte-equality was kept, since it is the half that is executable. The "this is a COPY"
statement lives in `k8s/base/kustomization.yaml` beside the generator, in the parity gate's
header (including which direction to copy and why the compose container must then be
recreated), and in `prometheus-config.yaml`. Flagged here rather than quietly reinterpreted.

### 6. [Scope, recorded] Stale horizon site lines corrected in the same edit

Three rows' compose line numbers were stale (`prometheus:8` vs 35, `redis-exporter:129`
vs 163, `postgres-exporter:149` vs 183) and had been emitting advisory NOTEs. They were
corrected while adding the second site to the same field. Measured rather than asserted:
running the gate with `MANIFEST=` pointed at the **pre-change** manifest from `8cc9717d`
reports `pin-not-at-site=16`; the current tree reports **13**. The remaining 13 belong to
rows this plan does not touch.

## Known Gaps (recorded, not dropped)

- **Under an enforcing CNI these three pods have NO network at all, and 29-07 owns the
  fix.** `00-default-deny.yaml` selects every pod in the namespace, and
  `networkpolicies/50-observability.yaml` is still the inert `app=nonexistent-placeholder`
  whose own header says to replace it when this exact set of workloads arrives. It is
  **29-07's** `files_modified`, and writing it here would collide inside the wave. Until
  it lands, expect every target DOWN and both exporters unable to reach their datastores —
  a network denial, not a monitoring defect. Named here so it is diagnosed in one step.
- **`external_labels` are not per-environment.** `cluster: jtoye-k8s` /
  `environment: kubernetes` are true of staging and production alike because this
  ConfigMap lives in the base. Nothing consumes them today (no rule reads them;
  Alertmanager groups by alertname/service). Per-environment values need an app-config key
  or an overlay patch, which belongs with 29-07's Alertmanager routing.
- **`rediss://` is right for staging and wrong for production.** Production still renders
  the base's in-cluster `redis.host` on plaintext 6379. Production is not deployed in this
  phase (D-08), and the cutover must move the scheme with the port — the same shape as
  29-04's recorded "production RESERVED = 3 will be wrong the moment production moves to a
  managed server". Making the scheme derivable needs a new app-config key, and
  `configmap.yaml` belongs to 29-07 in this wave.
- **Prometheus storage is an `emptyDir`, while the retention flag says 30d.** Both are
  true: metric HISTORY is lost on any pod restart. Alerting is unaffected (rules evaluate
  against live scrapes). A PVC is the upgrade and needs a StorageClass + cost decision
  inside the £150 ceiling (D-03).
- **`$(VAR)` env expansion is not measured in-cluster.** Both exporters assemble their
  address from earlier env vars, which the kubelet resolves. There is no jtoye cluster to
  observe it on, and the employer's is off limits — so this is an assumption, stated. It
  fails **loudly** rather than silently: an unexpanded reference makes the connection fail,
  which drives `pg_up`/`redis_up` to 0 and fires the very alerts this plan wires up. The
  ordering requirement and the `envFrom` exclusion are documented in both files.
- **A dedicated `pg_monitor` exporter role is still owed** on the managed server, with the
  measured symptom (`permission denied for function pg_ls_waldir`) recorded so it is not
  re-diagnosed. It belongs with the role bootstrap in `scripts/staging-secrets.sh`.
- **The 13 remaining `pin-not-at-site` NOTEs** on rows this plan does not own are advisory
  and were deliberately left; `--refresh` would fix them but would also rewrite `eol_date`
  fields across rows outside this plan's scope.

## Known Stubs

None. Every file added is a live kustomize resource in all four render targets, the new
gate runs in CI, and the two new ConfigMaps are mounted by a Deployment rather than being
inert declarations.

## Threat Flags

None beyond the plan's own register. All six dispositions were `mitigate` and all six were
applied:

| ID | Applied as |
|---|---|
| T-29-06-01 | ClusterIP only; **0** `kind: Ingress` among the 8 monitoring documents, with the whole-render count of 2 as the control proving the counter can fire |
| T-29-06-02 | every credential-bearing env is `valueFrom`; the scratch-Secret arm proves `check-no-plaintext-secrets.sh` fires on this directory; 0 credential-shaped literals in the slice |
| T-29-06-03 | `sslmode=require` as a reviewable literal in `DATA_SOURCE_URI`; `rediss://` proven load-bearing by its own control arm |
| T-29-06-04 | `check-alert-corpus-parity.sh`, wired into `k8s-validate`, demonstrated at 0, 1 and 2 |
| T-29-06-05 | the job-name set asserted exactly, with the ninth-job arm proving the assertion can fail; kube-state-metrics and node_exporter excluded, with the exclusion recorded in the config header |
| T-29-06-SC | three existing pins reused verbatim, each with its `sites:` extended so H-5 asserts the pin at every reference; no npm/PyPI packages in scope |

## Cross-Cutting Quality Contracts

- **Web performance** — N/A (no user-facing page touched).
- **SEO / discoverability** — N/A (no public surface; the deliberate absence of one is the
  security control here).
- **AI agent-readiness** — N/A (no API surface). The new gate emits the repo's uniform
  0/1/2 exit contract.
- **Security** — covered under Threat Flags. The load-bearing additions are least-privilege
  exporter credentials and zero public exposure.
- **Falsifiable evidence + runtime parity** — (a) every criterion was run in its fail
  direction; two instrument defects were caught by their own guards and are recorded; one
  criterion was corrected rather than substituted (the copy's header) and one was
  contradicted by a standing invariant and changed with the measurement recorded (INV-4).
  (b) Runtime parity: this plan ships no runtime artefact. `check-runtime-freshness.sh` and
  `check-container-config-drift.sh` were deliberately **not** run — a worktree's directory
  name changes the compose project name and would VOID them; they belong to the main
  checkout. The compose stack was read from and never mutated: every probe ran in a
  throwaway container and all were verified removed.

## User Setup Required

None for this plan. Downstream obligations, so they are not lost:

- **29-07** must write `networkpolicies/50-observability.yaml` for `app: prometheus`,
  `app: postgres-exporter` and `app: redis-exporter` (DNS, scrape egress to core-java:9091
  and edge-go:8080, and out-of-cluster egress to the managed Postgres and Redis — the
  latter two need entries in INV-7's `NETPOL_IPBLOCK_EXPECTED` in the same change).
- **29-10 / 29-12** should add a `pg_monitor` exporter role rather than leaving metrics on
  the runtime role, and must keep `postgres-credentials` carrying
  `runtime-username`/`runtime-password`, which these manifests now consume.

## Commits

| Hash | Type | What |
|---|---|---|
| `4994cce4` | feat | scrape corpus ConfigMap + byte-identical alerts copy + parity gate + CI wiring |
| `db4dd637` | feat | Prometheus and both exporters as Deployments/Services; LOC-2 count derived from base |
| `a0821e26` | chore | goldens regenerated against the pre-change snapshot; horizon `sites:` for every k8s reference |

## Self-Check: PASSED

Verified rather than trusted:

- Files exist and carry the declared content: `k8s/base/monitoring/prometheus-config.yaml`
  (contains `rabbitmq-queues`), `prometheus-deployment.yaml` (contains
  `prom/prometheus:v2.48.0`), `postgres-exporter-deployment.yaml`,
  `redis-exporter-deployment.yaml`, `alerts.yml` (md5 identical to canonical),
  `scripts/check-alert-corpus-parity.sh` (130 lines, mode 755, `bash -n` rc=0).
- Commits exist on `worktree-agent-a7f75c8429f927352`: `4994cce4`, `db4dd637`, `a0821e26`.
  None is on a protected ref.
- No commit deleted a tracked file: `git diff --diff-filter=D` empty for all three.
- No untracked files left behind; `git status --short` empty after the final commit.
- `render-golden.sh` re-run after the last commit: **rc=0**, both goldens at 2836 lines.

**Not updated by design (worktree mode):** `STATE.md` and `ROADMAP.md` are the
orchestrator's.

**Merge note for the orchestrator:** this plan touches `k8s/base/kustomization.yaml`,
`k8s/scripts/check-render-invariants.sh`, `k8s/goldens/*` and `.github/workflows/ci-cd.yaml`
— all four are also in **29-07**'s and/or **29-09**'s `files_modified` for this wave.
Resolve goldens by re-running `k8s/scripts/render-golden.sh --write` after the merge, never
by hand-editing one. In `check-render-invariants.sh` my change is confined to the LOC-2
block (base-derived counts) and its header paragraph; 29-07 adds a new Ingress invariant and
29-09 extends `NETPOL_INFRA_EXPECTED`, so the three should be line-disjoint. Re-run
`check-render-invariants.sh`, `check-alert-corpus-parity.sh` and `check-gate-enforcement.sh`
after the merge — all three read the merged tree rather than any committed artifact.

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Completed: 2026-08-10*
