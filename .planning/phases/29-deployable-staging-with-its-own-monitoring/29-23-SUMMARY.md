---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 23
subsystem: infra
tags: [prometheus, postgres-exporter, redis-exporter, kustomize, workload-identity, key-vault, alert-liveness, mailpit]
status: complete

requires:
  - phase: 29-12
    provides: azure-keyvault component, keyvault-chain gate, INV-10 widening
  - phase: 29-13
    provides: staging-secrets.sh write-once writer and preflight
  - phase: 29-22
    provides: Mailpit (the L-3 sink in staging) and mailpit-ui-kv
  - phase: 29-26
    provides: the current staging runtime at 6e95ee6c
provides:
  - k8s/components/monitoring (Prometheus v3.13.4, postgres-exporter v0.20.1, redis-exporter v1.94.0) in jtoye-staging
  - one rule corpus: byte-checked alerts.yml copy plus a `jobs` pair kind in check-k8s-copy-parity.sh
  - per-pod scraping of core-java and edge-go through headless Services and dns_sd_configs (DEF-29-8)
  - two exporter Workload Identities with their own SPCs; DB role jtoye_monitor
  - check-alert-liveness.sh PROM_EXEC, SINK=mailhog|mailpit, MAILPIT_URL, --selftest
affects: [29-24, 29-25, 29-27]

actuals:
  tokens: 53500
  tasks: 3
  commits: 7
plan_head_before: d3b434c30e7022686c704669fd00c7af5b386d8a
plan_head_after: 4526eabed7130396d645517d258af3c3ddadc1b2

tech-stack:
  added: [prom/prometheus:v3.13.4 (k8s), prometheuscommunity/postgres-exporter:v0.20.1 (k8s), oliver006/redis_exporter:v1.94.0 (k8s)]
  patterns:
    - "Config and rules side by side in /etc/prometheus through ONE projected volume of two content-hashed ConfigMaps"
    - "A non-byte-equal copy pair is held equal structurally (job names, labels, metrics_path, params, metric_relabel_configs), VOID on anything unreadable"
    - "A gate's runtime is an env-selected EXEC (docker or kubectl), never a copy command"

key-files:
  created:
    - k8s/components/monitoring/kustomization.yaml
    - k8s/components/monitoring/prometheus.yaml
    - k8s/components/monitoring/exporters.yaml
    - k8s/components/monitoring/prometheus/prometheus.yml
    - k8s/components/monitoring/prometheus/alerts.yml
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-23-monitoring-a.txt
  modified:
    - scripts/check-k8s-copy-parity.sh
    - scripts/check-alert-liveness.sh
    - scripts/lib/staging-secrets-common.sh
    - k8s/copy-parity.conf
    - k8s/staging/kustomization.yaml
    - k8s/components/azure-keyvault/secretproviderclasses.yaml
    - k8s/components/azure-keyvault/workload-patches.yaml
    - k8s/scripts/check-render-invariants.sh
    - k8s/goldens/staging.yaml
    - k8s/base/networkpolicies/README.md
    - .github/workflows/ci-cd.yaml

key-decisions:
  - "Prometheus self-scrape goes through its own Service (prometheus:9090): INV-4 forbids the loopback name in the render. prometheus-allow carries no self rule; Cilium admitted the self-scrape live (up=1)."
  - "dns_sd jobs stamp the compose labels with source-less relabel rules; the jobs pair counts those as labels and leaves source-derived relabels (compose's constant instance) out, so per-pod instance is the measured difference"
  - "Exporters probe with tcpSocket, never httpGet on /metrics, so an outage of the measured thing cannot CrashLoop its reporter"
  - "postgres-exporter uses sslmode=require (T-29-86, accepted): Go client, metrics-only role; verify-full stays on core-java, Flyway and the bootstrap"
  - "The Mailpit adapter counts messages_count (matches), never total (mailbox size)"
  - "One storefront guest order placed on staging under the owner's in-session ruling (option A) to give NoOrdersCreated a series"

patterns-established:
  - "Copy-parity `jobs <k8s-config> <compose-config>` lines for config files whose targets differ by runtime"

requirements-completed: [DPLY-03]

coverage:
  - id: D1
    description: "Prometheus runs in jtoye-staging with the compose rule corpus (served md5 = canonical md5) and the eight compose job names"
    requirement: DPLY-03
    verification:
      - kind: integration
        ref: "PROM_EXEC=kubectl... bash scripts/check-alert-liveness.sh (L-0 OK served md5 7368261d == canonical; drift arm VOID)"
        status: pass
      - kind: other
        ref: "bash scripts/check-k8s-copy-parity.sh (byte + jobs pair; arms: one-byte edit rc 1, keycloak->kc rc 1)"
        status: pass
    human_judgment: false
  - id: D2
    description: "core-java and edge-go scraped per pod through headless Services; no constant-instance relabel"
    requirement: DPLY-03
    verification:
      - kind: integration
        ref: "live targets: core-java 10.244.2.36:9091, edge-go 10.244.2.248:8080; rendered prometheus.yml const-instance=0 dns_sd=2 (control 1/0)"
        status: pass
    human_judgment: false
  - id: D3
    description: "check-alert-metrics.sh exits 0 against staging, and fails when an exporter is stopped"
    requirement: DPLY-03
    verification:
      - kind: integration
        ref: "PROM_URL=http://localhost:19090 bash scripts/check-alert-metrics.sh -> rc 0 PASS 19 rules / 25 selectors (rc 1 before the order; rc 1 with redis-exporter at 0)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Liveness gate is runtime-agnostic (PROM_EXEC, SINK) and against staging reports L-3 VOID only"
    requirement: DPLY-03
    verification:
      - kind: unit
        ref: "bash scripts/check-alert-liveness.sh --selftest -> 8/8 (RED 4/8, RED_EVIDENCE_OK x2)"
        status: pass
      - kind: integration
        ref: "staging run: rc 2, VOID: Alertmanager unreachable at http://localhost:19093, 0 FAIL lines"
        status: pass
    human_judgment: false
  - id: D5
    description: "Each exporter is its own Workload Identity with its own SPC; derived objects baseline+2, SPCs baseline+2; jtoye_monitor IN ROLE pg_monitor"
    requirement: DPLY-03
    verification:
      - kind: other
        ref: "Task 2 second verify: rc 1 on the unedited tree naming the 4 names; rc 0 objects=30 spcs=9; chain/INV-7/INV-10 break arms C1-C4"
        status: pass
      - kind: integration
        ref: "preflight 5 gaps -> 0; readback shows jtoye_monitor|f|f|t; pg_up=1, redis_up=1"
        status: pass
    human_judgment: false

duration: 36min
completed: 2026-10-19
---

# Phase 29 Plan 23: Prometheus and Exporters in k8s, Scraping Staging With the Compose Rule Corpus

**Prometheus now runs in jtoye-staging with two exporters, postgres-exporter and redis-exporter. It mounts a byte-checked copy of `infra/monitoring/prometheus/alerts.yml` and scrapes the eight compose jobs; core-java and edge-go are scraped per pod. Each exporter runs as its own Workload Identity with its own SecretProviderClass, and postgres-exporter connects as the new role `jtoye_monitor` (pg_monitor only). The liveness gate can now run against Kubernetes, with the Mailpit sink read through its `/api/v1` API. Against staging, `check-alert-metrics.sh` exits 0, and the liveness gate reports only the expected L-3 VOID: Alertmanager does not exist until 29-24.**

## Performance

- **Duration:** about 36 min (15:30:27Z to 16:06Z), including the checkpoint for the owner's order ruling
- **Tasks:** 3 of 3
- **Commits:** 7, measured from the plan ledger (`d3b434c3..4526eabe`), all this plan's
- **Files:** 20 changed (3970 insertions, 76 deletions)
- **Live estate touched:** kube-context `jtoye-stg-aks`, Key Vault `jtoye-stg-kv` and the managed Postgres, all in subscription c483d353:
  - **Key Vault:** two objects written by `--generate` and three grants made by `--assign-access`, each command carrying `[az-ok] 29-23`.
  - **Postgres:** `--stage monitor` created `jtoye_monitor`.
  - **Deploy:** one `deploy.sh` run at 6e95ee6c.
  - **Fail arm:** redis-exporter was scaled to 0 and back to 1.
  - **Order:** one storefront guest order, under the owner's in-session ruling (option A).
  - **Not done:** no other tenant-data write, no Stripe call, no push.

## Accomplishments

- **Task 1** (43a8fdaf, with arms and baseline in b6d22fb6):
  - **`k8s/components/monitoring`:**
    - Recreate strategy, a 10Gi PVC, retention 7d / 8GB, a read-only root filesystem and no service-account token.
    - Config and rules side by side in `/etc/prometheus`, so the liveness gate's default L-0 path works in both runtimes.
    - Headless Services `core-java-metrics` (9091) and `edge-go-metrics` (8080), read by `dns_sd_configs`.
    - The `prometheus-allow` NetworkPolicy, with its INV-7 row and README rows.
  - **Copy-parity gate:** a new `jobs` pair kind, with six new selftest arms (19/19).
    - Break arm: a comparator that always passes reds 6 of them.
  - **promtool:** `check config` on the rendered config exits 0. Control: an invalid DNS-SD type exits 1.
- **Task 2** (41eb069d):
  - **`exporters.yaml`:** the two exporters with their Services, plus the `postgres-exporter-allow` and `redis-exporter-allow` policies. The postgres policy's `egress.1` follows `db.port` through the staging replacement.
  - **Key Vault:** SPCs `postgres-exporter-kv` and `redis-exporter-kv` with their workload patches, and identity replacements from `azure-identity.yaml`.
  - **Classification:** `postgres-exporter-credentials--username` is estate (`jtoye_monitor`); `--password` is random.
  - **Break arms:**
    - C1, two workloads on one identity: chain gate rc 1.
    - C2, the csi mount removed: chain gate rc 1.
    - C3, an extra egress port: INV-7 rc 1.
    - C4, the identity label dropped but the mount kept: INV-10 rc 1.
    - Every file was restored, checked by blob hash.
- **Task 3** (TDD: fee1a3dc RED, 04ef0994 GREEN, 17df79e5 fix and live evidence, 4526eabe post-order evidence):
  - **PROM_EXEC:** the default is the compose `docker exec`. In Kubernetes it is `kubectl exec`. A copy command is never used.
  - **`SINK=mailhog|mailpit`:** an unknown value is a USAGE ERROR before any call.
  - **L-0 line:** printed as soon as L-0 passes, so a run that VOIDs later still records the parity.
  - **Live result:** all 8 targets are up, and `pg_up` and `redis_up` are both 1.

## Both Directions, Recorded (`evidence/29-23-monitoring-a.txt`)

| Check | Fail direction | Pass direction |
|-------|----------------|----------------|
| alerts.yml byte pair | one-byte edit: rc 1, both md5s named | rc 0, md5 7368261d on both sides |
| jobs pair | keycloak renamed kc: rc 1, "only in the k8s config ['kc']" | rc 0, jobs=8 |
| DEF-29-8 render read-back | control (the compose template): const-instance=1, dns_sd=0 | render: 0 and 2 |
| INV-7 | prometheus-allow before its row: "undeclared policy"; arm C3 | rc 0, 15 policies, 106 tuples |
| Task 2 second verify | unedited tree: rc 1 naming the 2 objects and 2 SPCs | rc 0, objects=30 spcs=9 (baseline 28 / 7 at 43a8fdaf) |
| `--list` classes | before the row: rc 1, username UNCLASSIFIED | rc 0, estate and random |
| Live preflight | 5 gaps, rc 1 | 0 gaps, rc 0 |
| Role `jtoye_monitor` | readback before: absent | `jtoye_monitor|f|f|t` |
| Liveness selftest | RED 4/8; a planted `.total` read gives 7/8 | 8/8 |
| `check-alert-metrics` (verify 2) | before the order: rc 1 (NoOrdersCreated); redis-exporter at 0: rc 1, adding RedisDown | rc 0, PASS 19 live rules / 25 selectors, ISO-8601 header ×1 |
| Liveness against staging (verify 3) | before the order: rc 2 with 2 FAIL lines; drifted ALERTS: rc 2, L-0 SOURCE DRIFT | rc 2, exactly the Alertmanager VOID line, 0 FAIL lines |
| Mailpit search (live) | absent id: messages_count=0, while total=10 | known term: messages_count=3 |

**Capacity (4 × B2s, D-34):**
- **Before:** free memory per node was 605, 1423, 99 and 1725 Mi.
- **After:** node 3 has 1149 Mi free. Prometheus and both exporters landed there (+576 Mi, +240m).
- **Rolling still works:** no pod is Pending, and a 1Gi surge still fits on node 1 or node 3.

**Pitfall 6:** the unqualified `jvm_*` rules now bind to both JVMs (core-java/core-api and keycloak/keycloak). Each alert names its own series' service. L-2b does not job-scope them, because they carry no service label (D-11).

## TDD Gate Compliance

| Cycle | RED | GREEN | Classifier |
|-------|-----|-------|------------|
| liveness PROM_EXEC and SINK | fee1a3dc: 4/8 (arms 2, 4, 5, 6 and 7 not ok) | 04ef0994: 8/8 | RED_EVIDENCE_OK: `29-23-t3-red-prom-exec.json`, `29-23-t3-red-sink.json`. Control on a passing arm: INVALID_RED no_target_test_failure |

**Semantic assessment:** each target arm ran against the stubs and failed on its planned assertion. The RED gate ignored PROM_EXEC, so docker ran. It also had no SINK, so with Mailpit down it searched the Mailhog stub and passed. Arms 1, 3 and 8 passed in the same run, so the harness itself was sound.

## Deviations from Plan

### Criteria replaced or adapted (recorded, not silent)

**1. [Unfalsifiable as written] The `--list` "unclassified" count**
- **As written:** "count of lines containing 'unclassified' is exactly 0".
- **Why it cannot pass:** the summary line `list: N objects, 0 unclassified` itself contains the word. The count is therefore 1 when every object is classified, and also 1 when one is not (that row says UNCLASSIFIED, upper case).
- **Replacement:** rows whose class column is UNCLASSIFIED = 0 (1 before), plus the summary reading "0 unclassified" (1 before), plus rc 0 (1 before). Both forms are recorded.

**2. [Rule 2 - Missing] README rows for the three new policies**
- `k8s/base/networkpolicies/README.md` is outside `files_modified`. INV-7's own contract requires its flow matrix to change with any policy row, so the rows and the stale "11 in staging" count were updated.

**3. [Plan location] The classification lives in `scripts/lib/staging-secrets-common.sh`**
- The plan says `scripts/staging-secrets.sh`, but 29-13 moved the class table into the shared library.

**4. [Rule 2] L-0 printed before a later VOID** (17df79e5)
- The acceptance criterion needs L-0's md5s shown. The gate printed them only in the summary, and a run that VOIDs at L-3 never reaches the summary.

**5. [Plan adaptation, owner-ruled] One staging order** (`ORD-1AAC0CDE-20261019-750F976B`)
- **Why:** NoOrdersCreated's request counter had no series, so verify 2 could not reach rc 0.
- **Ruling:** returned as a checkpoint. The owner chose option A in session: exactly one guest collection order through `scripts/seed-order-metric.sh`.

**6. [Additions beyond the plan's literal shape]**
- Prometheus: `--storage.tsdb.retention.size=8GB`, so a full 10Gi disk cannot stop ingestion.
- Every new pod: `enableServiceLinks: false` (the 29-14 lesson).
- The headless Services are labelled `app.kubernetes.io/name`, not `app`, so no Service lookup by `-l app=core-java` changes meaning.
- The jobs pair also compares `metrics_path`, `params` and `metric_relabel_configs`, which is stronger than names and labels alone.

**Total:** 1 criterion replaced, 2 missing items added, 1 location adaptation, 1 owner-ruled live step, and 4 additions. **Impact:** none on scope. Each change makes a plan claim truthful or checkable.

## Findings

- **Mailpit's `total` is the mailbox size, not the match count.** A live search for an absent id returned total=10 and messages_count=0. An adapter reading `total` would pass L-3 whenever the mailbox is non-empty. The selftest arm `mailpit-counts-matches-only` pins the correct field.
- **Under Cilium, a scrape of a Service with no endpoints times out.** It reports "context deadline exceeded", not a refusal. This matches 29-17's finding that a policy denial is a drop.
- **`kubectl apply` printed "configured" for core-java with an empty diff.** core-java stayed at revision 8 with the same pod. This matches the keycloak-theme and PDB lines that 29-29 recorded.

## Open Items

- **For 29-25 / 29-27: the order counter does not survive a core-java restart.**
  - **Effect:** every deploy that rolls core-java reds `check-alert-metrics` M-1 and liveness L-2/L-2b on a quiet staging, and NoOrdersCreated cannot fire until the next order.
  - **Recommended:**
    - Make the seed a named, owner-approved step of the staging deploy runbook. Run `seed-order-metric.sh` right after any deploy that rolled core-java; with its default `FORCE=0` it does not order when the series already exists.
    - Have 29-25 run the gates only after that step.
    - Mute NoOrdersCreated's notification on staging, as RESEARCH Pitfall 5 recommends.
  - **Not recommended:** a scheduled re-seed, which would write synthetic orders forever.
- **Mailpit certificate (from 29-22):** Mailpit reads its certificate only at start, and the leaf expires 2027-01-17.
  - Nothing in this corpus alerts on certificate expiry or mail health. `alerts.yml` has no Mailpit job, and Mailpit is not among the eight scrape targets.
  - Catching it needs a new job or rule, which is outside this plan's one-corpus scope. Recorded, not widened.
- **L-3 is VOID until 29-24 deploys Alertmanager.** 29-25 owns the rc 0 liveness run, with `ALERTMANAGER_URL=http://localhost:19093` set explicitly.
- **The `observability-placeholder` policy stays in base,** because base, production and local ship no monitoring.

## Threat Flags

None beyond the plan's threat model.
- **T-29-83:** no Ingress for Prometheus, and only app=grafana is admitted on 9090.
- **T-29-84:** byte and jobs parity in CI.
- **T-29-85:** `jtoye_monitor` has pg_monitor only, its own identity and its own SPC.
- **T-29-86:** `sslmode=require` is accepted and recorded.

## Self-Check: PASSED

- **Files:** FOUND `k8s/components/monitoring/{kustomization.yaml,prometheus.yaml,exporters.yaml,prometheus/prometheus.yml,prometheus/alerts.yml}`, the evidence file and both RED JSON records.
- **Commits:** 43a8fdaf, b6d22fb6, 41eb069d, fee1a3dc, 04ef0994, 17df79e5 and 4526eabe are ancestors of HEAD. `git rev-list --count d3b434c3..4526eabe` = 7.
- **Leaks:** `gitleaks dir` found none in the evidence or the RED records. `staging-secrets.sh --leak-scan` on the evidence found 0 of 24 objects.
