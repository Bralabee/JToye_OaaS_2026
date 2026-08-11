---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 07
subsystem: monitoring-alerting
tags: [k8s, alertmanager, grafana, networkpolicy, kustomize, gates, dply-03, d-17, d-13, d-19]

# Dependency graph
requires:
  - plan: 29-03
    provides: "the MEASURED answer to assumption A1 (Alertmanager fans out to every email_configs entry in one receiver, 2/2 against a 1/0 control) — which is what licenses the dual-sink receiver instead of the recorded IMAP/webhook fallback; and L-3b, which makes an undeclared multi-destination receiver VOID"
  - plan: 29-06
    provides: "prometheus:9090 (Grafana's datasource), the alerting: block that already targets alertmanager:9093, and the recorded gap this plan closes — five monitoring pods with no network at all under the enforcing CNI"
  - plan: 29-05
    provides: "the alertmanager-smtp (username/password/from/to) and grafana-admin (username/password) Secrets, and the recorded reason from/to live there: ONE source for the whole destination"
  - plan: 29-10
    provides: "the live estate facts these manifests are written against — enforcing Cilium dataplane, Azure Managed Redis on 10000"
provides:
  - "Alertmanager in the k8s render: the compose route byte-for-byte, a credential that is never a literal, and a second sink INSIDE the real receiver"
  - "Grafana in the k8s render with the existing datasource + dashboard corpus provisioned byte-identically, admin from a Secret, no public exposure yet"
  - "Seven concrete observability NetworkPolicies replacing the inert placeholder — the five monitoring workloads have a network again"
  - "INV-8: an Ingress backed by prometheus or alertmanager is a CI failure with an EMPTY allowlist behind it"
  - "INV-7 extended: the two exporters and Alertmanager join the ipBlock declaration, with __SMTP_PORT__ derived from app-config"
  - ".planning/phases/29-.../deferred-items.md — two out-of-scope discoveries recorded with their measurements"
affects:
  - "29-08 (adds the Grafana Ingress + TLS SAN + DNS; the Service and the ingress-nginx ingress rule are already here, and INV-8 now guards which monitoring surface may be published)"
  - "29-09 (ships k8s/staging/mailhog.yaml — the relay this plan's staging config and Alertmanager egress rule already name; also owes the rabbitmq scrape egress)"
  - "29-11 (verifies the Grafana admin credential against the RUNNING instance — the only place that question can be answered)"
  - "29-12 (the live L-3 drill: L3_SINK_TO is now an app-config value, L3_HUMAN_TO comes out of the Secret; the invocation is recorded in alertmanager-config.yaml's header)"

tech-stack:
  added: []   # both image pins already existed in compose and in the horizon manifest
  patterns:
    - "An initContainer that renders a config template from a mounted Secret, so a credential arrives as a FILE the config only names — the k8s form of the compose entrypoint idiom"
    - "A conditional CONFIG BLOCK that is omitted entirely rather than blanked, because a blanked destination is a delivery attempt to the empty address"
    - "A gate-derived expectation (__SMTP_PORT__) where a kustomize replacement is structurally impossible, so two files that encode one fact still cannot drift"
    - "An ADDITIVE NetworkPolicy that unions a new ingress grant onto an existing policy without editing a file whose egress indices are addressed from four kustomizations"

key-files:
  created:
    - k8s/base/monitoring/alertmanager-config.yaml
    - k8s/base/monitoring/alertmanager-deployment.yaml
    - k8s/base/monitoring/grafana-provisioning.yaml
    - k8s/base/monitoring/grafana-deployment.yaml
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/deferred-items.md
  modified:
    - k8s/base/networkpolicies/50-observability.yaml
    - k8s/base/configmap.yaml
    - k8s/base/kustomization.yaml
    - k8s/staging/configmap-patch.yaml
    - k8s/staging/kustomization.yaml
    - k8s/local/kustomization.yaml
    - k8s/production/kustomization.yaml
    - k8s/scripts/check-render-invariants.sh
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml

key-decisions:
  - "smtp_auth_password_file, NOT a rendered literal from a secretKeyRef — because check-alert-liveness.sh L-3b reads the running Alertmanager's own .config.original on every run, and a literal would put the Gmail app password into that response and into the gate's own messages. The field's existence in v0.27.0 was MEASURED with a control, not assumed"
  - "The from/to addresses stay in the alertmanager-smtp Secret and are NOT restated as app-config keys, despite the plan naming six keys: 29-05 recorded ONE source for the whole destination, and a second source would be the #271 shape plus the owner's inbox in a tracked file"
  - "The secondary-sink block is built with one printf argument per line after a multi-line string produced two DIFFERENT wrong indents, both caught by amtool"
  - "--web.external-url is omitted rather than copied: the compose value is a loopback literal INV-4 forbids, and this workload has no public URL by design"
  - "GF_INSTALL_PLUGINS is dropped, measured: the entire dashboard corpus uses `gauge` x1 and `timeseries` x1, both core panels"
  - "The two scrape-ingress grants are ADDITIVE policies in 50-observability.yaml rather than edits to 20-/30-, whose egress indices are addressed by four kustomization files"
  - "INV-8 keys on the BACKEND SERVICE, not the hostname: an Ingress is dangerous because of what it routes to"

patterns-established:
  - "When a criterion and a doctrine contradict each other, replace the criterion with a strictly stronger form measured somewhere the contradiction does not exist — here, asserting the placeholder's absence on the RENDER (which has no comments) rather than on the source file (where the doctrine requires the name to appear)"
  - "A new invariant gets its OWN fail-closed parse guard, because an assertion whose guard belongs to a different assertion cannot be shown to fail on its own"

requirements-completed: [DPLY-03]

metrics:
  duration: ~105 min
  tasks: 3
  commits: 3
  files-created: 5
  files-modified: 10
  completed: 2026-08-11
---

# Phase 29 Plan 07: Alertmanager, Grafana, and the Exposure Line as a Gate Summary

**The half of DPLY-03 that involves a human now renders: an Alertmanager whose Gmail
credential is never a literal and whose inspectable second sink is part of the real
delivery, a Grafana with the existing dashboards provisioned byte-identically, and
seven concrete NetworkPolicies where an inert placeholder used to sit — with
"Prometheus must never be published" turned from a convention into a CI failure.**

## What shipped

**Alertmanager, with the route untouched.** `k8s/base/monitoring/alertmanager-config.yaml`
carries the config as a TEMPLATE plus a render script, mirroring the compose idiom
because the constraint is the same and belongs to the binary: Alertmanager has no
env-var substitution, so the app password cannot arrive through a `${VAR}` and must
not be a ConfigMap literal. An initContainer renders it into an emptyDir and runs
`amtool check-config` before the notifier container ever starts.

The four route knobs are **byte-equal** to `infra/monitoring/alertmanager/alertmanager.yml.tmpl`,
compared explicitly rather than asserted:

```
  receiver: email-default
  group_by: ['alertname', 'service']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 12h      compose lines=5  k8s lines=5  ->  rc=0
```

**Grafana, provisioned from the corpus that already exists.** Three ConfigMap keys,
each md5-identical to its `infra/monitoring/` source, extracted **from the render**
rather than read from the file I wrote:

| key | md5 | lines |
|---|---|---|
| `datasource.yml` | `73d0c40933b746029e3fbf22d5aa6e2a` | 13 |
| `dashboard-provider.yml` | `5ba03c44219998deee190caa829d1747` | 13 |
| `stomp-dashboard.json` | `4136b0520b80e72a946b2b0cd150914f` | 125 |

Not one byte had to change, **including the datasource URL**: the compose corpus
already says `url: http://prometheus:9090`, which is exactly the Service 29-06
created. The plan allowed that url to differ; it does not.

**Seven policies where a placeholder was.** `50-observability.yaml` executed its own
written replacement condition. The header names the displaced good and accounts for
each thing the placeholder provided rather than deleting it silently.

## Falsification — every arm, with its control, clean state asserted last

### The credential mechanism was measured before it was chosen

| Arm | Config | Result |
|---|---|---|
| A | `smtp_auth_password_file` + a per-entry `smarthost:` and `require_tls: false` | `amtool check-config` **SUCCESS, rc=0** |
| **CONTROL** | identical file, field renamed `smtp_auth_password_fileZZ` | **rc=1** `field smtp_auth_password_fileZZ not found in type config.plain` |

The control is what makes the SUCCESS mean anything: it proves amtool rejects an
unknown field at all, so the accepted spelling is a real supported field in the
pinned v0.27.0 and not one silently ignored.

### ARM A — the conditional second sink, both render paths exercised

Both arms ran the render script **extracted from the staging render** inside the
pinned image, so what was tested is what ships:

| Arm | `alerting.secondary.smtp.smarthost` | destinations | amtool |
|---|---|---|---|
| A1 | *(empty)* | **1** | SUCCESS, rc=0 |
| A2 | `mailhog:1025` | **2** | SUCCESS, rc=0 |

The diff between the two outputs is **exactly the four added lines** and nothing
else:

```
32a33,36
>       - to: 'alerts-sink@jtoye-staging.local'
>         smarthost: 'mailhog:1025'
>         require_tls: false
>         send_resolved: true
```

**ARM A CAUGHT A REAL DEFECT BEFORE IT SHIPPED, TWICE, IN TWO DIFFERENT WRONG
DIRECTIONS** — see Deviations 1. Neither would have been visible in the render, in
`kubectl kustomize`, or in any gate; both were visible immediately to amtool.

### ARM B — the credential is required, and "loudly" means by name

| Arm | Setup | Result |
|---|---|---|
| B1 | Secret volume absent | **rc=1** `no SMTP credential mounted at … refusing to start an alert path that cannot authenticate` |
| B2 | password file present but **EMPTY** | **rc=1** `the SMTP credential … is EMPTY — an empty app password authenticates as nobody` |
| B3 | `To` empty | **rc=1** `an alert route with no destination is not a paging path` |
| B4 | secondary smarthost set, secondary To empty | **rc=1** names both variables |

B2 is not decoration. `[ -f ]` alone would let a Secret created from an empty
variable render a perfectly valid config that authenticates as nobody — the same
shape as the zero-byte-corpus case 29-06 recorded.

### The credential never reaches the rendered config

```
occurrences of the probe password in the rendered config : 0   (rc=1)
smtp_auth_password_file: /etc/alertmanager/smtp/password       (line 7 — the PATH)
CONTROL, same file plus one planted canary line             : 1   (rc=0)
```

The control is what licenses the 0: it proves the scan can match at all.
`check-no-plaintext-secrets.sh` is rc=0 across all four targets.

### Grafana — the render's own bytes were run, not just compared

An md5 match proves transcription. It does not prove the file works. So the three
keys were extracted from the staging render, mounted at the exact paths the
Deployment uses, and started in the pinned image under a **read-only root
filesystem** as uid 472:

```
/api/health   -> {"database": "ok", "version": "10.2.2"}
/api/datasources -> [{"name":"Prometheus","type":"prometheus","url":"http://prometheus:9090","isDefault":true,...}]
/api/search      -> [{"uid":"stomp-relay-dashboard","title":"STOMP Broker Relay",...}]
/api/admin/settings -> users.allow_sign_up = false   server.root_url = https://grafana-staging.olajay.co.uk
```

**CONTROL — a second Grafana, same image, NO provisioning mounted:**

```
datasources: []
dashboards : []
```

That empty pair is what makes the first result evidence: the datasource and the
dashboard come from these bytes, not from anything built into the image. The
read-only rootfs result is also a measurement, not a hope — Grafana creates its
SQLite database and runs its migrations with only `/var/lib/grafana` writable,
because the image's own entrypoint passes `default.log.mode=console`.

### The provisioning parity check, shown able to fail

| Arm | Input | Result |
|---|---|---|
| clean | — | rc=0, all three md5s match |
| break | ONE byte changed (`timeInterval: "15s"` -> `"16s"`) | **rc=1**, md5 `91df0bd6…` vs source `73d0c409…`, unified diff naming the line |
| clean (last) | restore | rc=0; restore verified by `git hash-object` = `0cc46124c669d75c1f04282f3622d464bdfc8561` **before and after** |

### INV-8 demonstrated at exit 0, 1 and 2

| Arm | Setup | Result |
|---|---|---|
| clean | — | **rc=0**, `INV-8 OK (3 backend ref(s) scanned, 0 publish an unauthenticated monitoring Service)` |
| **A** | a scratch Ingress rule backed by Service `prometheus` | **rc=1** on base, staging AND production, naming the Ingress, the host and the Service |
| **B** | INV-8's own document scan blinded (scratch copy of the gate) | **rc=2** `INV-8 found 0 Ingress backend references … 'Found nothing' is never 'nothing is published'` |
| clean (last) | restore | rc=0; `ingress.yaml` verified by `git hash-object` = `c5e61f1cfcb583a8d16081bbc37d020d2143cbbf` |

ARM A did **not** fire on `k8s/local`, and that is correct rather than a miss: the
local overlay replaces the whole `rules:` list, which is the same mechanism INV-6's
header records in the opposite direction. INV-8's guard was active there
throughout (`3 backend ref(s) scanned`).

**All four allowlist-hygiene arms, on a scratch copy so the committed gate was
never left mutated:**

| Arm | Entry | Result |
|---|---|---|
| H1 | `prometheus\|   ` (blank reason) | **rc=1** `has a blank reason … indistinguishable from an accident` |
| H2 | `grafana\|<reason>` (not on the never-publish list) | **rc=1** `exempts nothing. Either add it to that list or remove the entry` |
| H3 | `alertmanager\|<reason>`, nothing published | **rc=1** `STALE … a standing permission slip` |
| H4 | `prometheus\|<reason>` **plus** the scratch Ingress | **rc=0**, `INFO … is ALLOWLISTED: <reason>` |

H4 is the one that matters most and is the easiest to skip: without it, ARM A's
FAIL could have been a hardcoded refusal rather than an empty allowlist, and the
exemption path would be untested dead code.

### INV-7's friction fired for real, unprompted, before ARM C was even designed

Writing the three ipBlock-carrying policies without touching the map produced, on
**all four targets**:

```
FAIL … NetworkPolicy 'alertmanager-allow' renders an ipBlock egress rule but has
       no entry in NETPOL_IPBLOCK_EXPECTED.
FAIL … 'postgres-exporter-allow' …
FAIL … 'redis-exporter-allow' …
```

That is a better demonstration than a synthetic one: nobody arranged it.

**ARM C (the port half), run deliberately:** adding `443` to the Alertmanager SMTP
rule without moving the declaration →

```
FAIL [k8s/base|local|production|staging] INV-7: NetworkPolicy 'alertmanager-allow'
  allows ipBlock egress [0.0.0.0/0:443 0.0.0.0/0:587]; expected [0.0.0.0/0:587]
rc=1
```

Restored by inverse edit; verified by content — `git hash-object` =
`b9b0af81b99ffc64c78bebe5f33af43cd56e27a5` before and after — and clean re-run rc=0.

### The goldens, every changed line attributed

Snapshot `29-07-pre` taken **before Task 1's first edit**. `--diff-since`
`resolve_exit=0` (a 2 would mean the baseline is missing and the assertion is VOID,
not passed); diff non-empty at 2196 lines.

```
removed(<) = 18     added(>) = 2140 (1070 per target)     2836 -> 3897 lines per target
```

**Every one of the 18 removed lines is a placeholder line** — 9 unique lines × 2
targets, all of them `observability-placeholder` / `nonexistent-placeholder` /
`jtoye.co.uk/placeholder` / the annotation prose. Nothing else was removed.

| Added per target | What |
|---|---|
| 2 ConfigMaps | `alertmanager-config`, `grafana-provisioning` |
| 2 Deployments | alertmanager, grafana |
| 2 Services | both ClusterIP; **0 Ingress** |
| 6 NetworkPolicies | net of the removed placeholder: 6 -> 12 policies |

`kind: Secret` among added lines = **0**, and that counter was shown able to fire:
the same awk over the same diff plus one synthetic `> kind: Secret` line returns **1**.

An independent structural cross-check, derived rather than asserted: `k8s-app: kube-dns`
selector blocks went **4 -> 9**, i.e. exactly +5 — one DNS egress rule per monitoring
policy, with the two ingress-only scrape policies correctly contributing none.

### Gate results — every rc recorded individually, on the committed tree

| Gate | rc |
|---|---|
| `k8s/scripts/render-golden.sh` | 0 (both goldens match at 3897 lines) |
| `k8s/scripts/check-render-invariants.sh` | 0 (`PASS: INV-1..INV-8 hold across 4 targets`) |
| `k8s/scripts/check-no-plaintext-secrets.sh` | 0 |
| `k8s/scripts/check-connection-math.sh` | 0 |
| `k8s/scripts/check-env-contract.sh` | 0 |
| `k8s/scripts/check-consumer-thread-budget.sh` | 0 |
| `scripts/check-alert-corpus-parity.sh` | 0 |
| `scripts/check-gate-enforcement.sh` | 0 (37 gates, 6 workflows, 6 exempt — unchanged: this plan adds an INVARIANT to an existing gate, not a new `scripts/check-*.sh`) |
| `scripts/check-dependency-horizons.sh` | 0 |
| `bash -n` + `shellcheck -S error` on the modified gate | 0 / 0 |

**Not run, with the reason, rather than silently skipped:**

- `k8s/scripts/validate-networkpolicies.py` — **BLOCKED** by this machine's
  base-conda guard, and no `.conda-env` is declared for this repository. A blocked
  command is the answer, not something to reroute around. Its absence costs little
  here: it parses the RAW files, while INV-3, INV-7 and INV-8 all assert on the
  RENDER, which `check-render-invariants.sh`'s own header names as the reason it
  exists. It is wired into no workflow either, so CI is in the same position.
- `check-alert-liveness.sh` / `check-alert-metrics.sh` against the k8s stack —
  there is no jtoye cluster deployed yet (29-11/29-12), and the only kube context on
  this host is the employer's `sipbihs2aks`, which is never to be touched. What this
  plan owed them — a two-destination receiver in ONE receiver, and the declarations
  L-3b needs — is delivered, and the exact invocation is recorded in
  `alertmanager-config.yaml`'s header.
- `check-runtime-freshness.sh` / `check-container-config-drift.sh` — a worktree's
  directory name changes the compose project name and would VOID them; they belong
  to the main checkout. This plan ships no runtime artefact.

**CLEAN STATE ASSERTED LAST:** after every arm and after the final commit — all
gates above rc=0, `git status --short` empty, every probe container verified
removed, and the shared compose stack never mutated (every probe ran in a throwaway
container on `--network none` or a published loopback port).

## Deviations from Plan

### 1. [Rule 1 — bug in new code] The secondary sink was indented wrong, twice, in opposite directions

- **Found during:** Task 1, ARM A2 — never by reading, only by running
- **Issue (a):** the block was first written at the indent of the CONFIGMAP rather
  than of the rendered file. Six spaces too deep, it landed **inside** the preceding
  entry's `html: |-` block scalar, and the keys after it were parsed as a second set
  of keys on the FIRST email_config: `field send_resolved already set in type
  config.plain`. A missing sink reported as a duplicate field.
- **Issue (b):** the obvious fix — a multi-line `"..."` string at the right indent —
  was *also* wrong, because only the first line's leading spaces survive: every
  continuation line carries the ConfigMap block scalar's own indent, which the mount
  strips. Result: `field smarthost not found in type config.plain`.
- **Fix:** the block is built with **one `printf` argument per line**, so each line's
  indentation sits inside its own quoted string and what you read is what is written.
  Both wrong shapes and their exact amtool messages are recorded in the file.
- **Why it matters beyond this file:** neither shape is visible in `kubectl kustomize`,
  in the goldens, or in any render-level gate. Only running the render script through
  `amtool` found them. A plan that had only diffed the render would have shipped an
  Alertmanager that refuses to start.
- **Commit:** `980a5ab9`

### 2. [Rule 2 — missing critical functionality] The staging capture sink, and the L-3b obligation

- **Found during:** Task 1
- **Issue:** the plan says base values are production-correct and "the staging
  overrides land in the staging overlay ConfigMap patch", but
  `k8s/staging/configmap-patch.yaml` is not in this plan's `files_modified`, and
  **29-09's Task 2 does not take up `alerting.secondary.smtp.*` either** — it points
  the *application* `smtp.*` keys at Mailhog and stops there. Left as scoped, the
  phase would reach 29-12's live drill with a ONE-destination receiver: L-3 would
  search Mailhog, find nothing, and report a broken paging path that is actually a
  missing config key.
- **Fix:** staging sets `alerting.secondary.smtp.smarthost: "mailhog:1025"` and
  `alerting.secondary.smtp.to: "alerts-sink@jtoye-staging.local"` (the latter is also
  the `L3_SINK_TO` value, which is why it is a config key rather than a workflow
  literal — the render and the gate read ONE declaration). `grafana.root-url` gets
  its staging value in the same edit, for the D-19 reason the neighbouring keys carry:
  an inherited base value would make a staging dashboard hand out production links.
- **Forward reference, stated:** `mailhog:1025` names a Service plan 29-09 creates.
  That is this file's existing house style (`redis.host`, `rabbitmq.host`,
  `keycloak.admin.base-url` all do it) and is checked at deploy time, not render time.
- **Merge note:** 29-08 and 29-09 both list `k8s/staging/configmap-patch.yaml`. Both
  are later waves, so this lands in their base.
- **Commit:** `980a5ab9`

### 3. [Criterion corrected, not silently substituted] The plan's six app-config keys are four

- **Issue:** the plan names six keys including `alerting.smtp.from` and
  `alerting.smtp.to`, both annotated "injected". Authoring them in app-config would
  be a SECOND source for a fact `scripts/staging-secrets.sh:507-517` explicitly
  records as having exactly one — *"so the Alertmanager config plan 29-07 writes has
  exactly ONE source for the whole destination, and so a missing To refuses at
  bootstrap instead of sending nowhere in silence"* — and would put the owner's
  personal inbox in a tracked file.
- **Resolution:** four authored keys; `from`/`to`/`username` arrive from the
  `alertmanager-smtp` Secret via `secretKeyRef`, which is what "injected" means here.
  Flagged rather than quietly reinterpreted.

### 4. [Criterion corrected, not silently substituted] `grep -c 'nonexistent-placeholder'` cannot be 0

- **Issue:** one acceptance criterion asks for `grep -c 'nonexistent-placeholder'
  k8s/base/networkpolicies/50-observability.yaml` to return **0** *and*, in the same
  sentence, for "the new header [to] name the displaced placeholder and why it is
  gone". The Incremental Betterment doctrine requires the second. The two cannot both
  hold — the recorded "a doc rule must name the token it forbids" trap.
- **Measured on the correct tree:** the count is **1**, at line 9, inside the header
  paragraph that names the displaced good. Satisfying the criterion as written would
  mean deleting the displacement record.
- **Replaced with a strictly stronger form, asserted where the contradiction does not
  exist:** `kubectl kustomize` emits no comments, so the RENDER is the honest place to
  assert absence.

  ```
  CURRENT staging render                        -> 0 occurrences  (rc=1)
  CONTROL, the pre-change render snapshot       -> 1 occurrence   (rc=0)
                       'observability-placeholder' -> 2 occurrences
  ```

  The control is what makes the 0 evidence rather than an already-empty grep.

### 5. [Rule 3 — blocking] Both app-tier scrapes were denied, and the rule lives on the target

- **Found during:** Task 3
- **Issue:** `20-core-java.yaml` and `30-edge-go.yaml` each carry a Prometheus-scrape
  ingress rule, and both admit only `namespaceSelector: monitoring` and
  `namespaceSelector: jtoye-infrastructure` — two namespaces this repository never
  creates. 29-06 put Prometheus in the SAME namespace as its targets, which matches
  neither, so under the enforcing CNI both scrapes are denied while every other layer
  looks correct. 29-06's handoff named only the EGRESS half.
- **Fix:** two ADDITIVE ingress-only policies (`core-java-scrape-allow`,
  `edge-go-scrape-allow`) in this plan's own file. Kubernetes unions the ingress rules
  of every policy selecting a pod, so the existing cross-namespace grant is preserved
  untouched — displacing a working good would be exactly what the doctrine forbids.
  They live here rather than in 20-/30- because those files' egress rules are addressed
  **by index** from four kustomization files, and every edit to them is a chance to
  shift an index.
- **`policyTypes: [Ingress]` only, and that is load-bearing:** naming Egress too would
  make each an egress policy with an EMPTY rule list, which brings the pod under egress
  policy while granting nothing — the one way an "additive" policy can subtract.
- **Commit:** `0d73f424`

### 6. [Rule 2 — missing critical functionality] Four kustomizations, not one

- **Issue:** the exporters' out-of-cluster egress must follow app-config, and a base
  `replacements:` block does not re-run against an overlay's patched ConfigMap
  (measured by 29-04 and again by 29-10). With base-only passes, staging's
  redis-exporter would be permitted the BASE `6379` while dialling `10000` —
  `redis_up` stuck at 0 and RedisDown firing permanently about a healthy cache.
- **Fix:** the same four targets added to `k8s/base`, `k8s/staging`, `k8s/local` and
  `k8s/production` kustomizations. The last two are outside this plan's
  `files_modified`; without them INV-7 fails, so this is blocking rather than
  optional. Confirmed in the render: staging's exporter rule renders `10000`, local's
  Postgres rule renders `5433`, base/production render their own defaults.
- **Commit:** `0d73f424`

### 7. [Scope, recorded] Two stale prose counts in the gate's own failure messages

INV-7's two parse-guard messages said "This platform ships six" NetworkPolicies and
"four" ipBlock policies. Both were about to become wrong. Corrected to twelve and
seven — **counted off the render rather than remembered**, and the first draft of the
correction said "thirteen", which the render count caught.

## Issues encountered

**I destroyed my own uncommitted file with `git checkout --`, and the by-content
check is the only reason I know.** Restoring the ARM C break with
`git checkout -- k8s/base/networkpolicies/50-observability.yaml` reverted the file to
its **committed** state — the old placeholder — discarding the entire rewrite. This is
the repo's recorded `trap_break_arm_revert_eats_fixes` exactly: `git checkout` restores
from the index, not from what you had.

What caught it was the discipline, not the alarm: the pre-arm hash was
`6cdce89e…` and the post-restore hash came back `dfe815be…`. A `git diff --stat`
check would have shown a clean file and read as success. The file was rewritten, the
arm re-run with an **inverse edit** as the restore, and the second restore verified
identical (`b9b0af81…` before and after).

**The `>` marker in the goldens diff is not a proof on its own.** The 2140 added lines
were only meaningful once the 18 removed lines were enumerated and attributed
individually — the interesting half of a diff for this change is the removals, because
the whole risk of replacing a placeholder is removing something else with it.

## Known Gaps (recorded, not dropped)

- **The `core-java` Service does not expose 9091**, so the `core-java` scrape cannot
  connect regardless of NetworkPolicy. Measured and written up as **DEF-29-1** in this
  phase's `deferred-items.md`, with the likely fix and the objection to it already
  answered. The policy here is correct as written — NetworkPolicy governs pod
  `IP:port`, not Service ports — which is precisely why this could otherwise be
  mis-diagnosed as a network denial.
- **`check-env-contract.sh`'s EDGE_MANAGEMENT_PORT reason is stale** (it says k8s ships
  zero monitoring manifests). The conclusion is still right and the entry must stay;
  only the stated reason has gone out of date. **DEF-29-2**.
- **Keycloak and RabbitMQ scrape egress are not written here.** The `keycloak` and
  `rabbitmq`/`rabbitmq-queues` jobs stay DOWN until 29-08 and 29-09. Guessing the
  operator's pod labels for RabbitMQ would produce a rule that silently matches
  nothing — worse than an absent one. Recorded in `50-observability.yaml`'s footer.
- **`core-java -> mailhog:1025`** (application email in staging) belongs to
  `core-java-allow`, i.e. 29-09.
- **Prometheus, Alertmanager and Grafana all store on `emptyDir`.** Metric history,
  silences, and any UI-created Grafana object are lost on a pod restart. Alerting is
  unaffected in all three cases, and for Grafana the emptyDir has one helpful
  consequence worth naming: a rotated admin Secret DOES take effect on the next
  restart, which stops being true the day a PVC lands.
- **The Grafana admin password is not proven by anything here, by construction.**
  Grafana applies `GF_SECURITY_ADMIN_PASSWORD` only when it FIRST CREATES the user, so
  a manifest-level reading is true and useless simultaneously. 29-11 owns the
  verification, against the running instance.
- **`$(VAR)`-free, but still unobserved in-cluster.** No jtoye cluster exists yet, so
  the initContainer ordering, the Secret mount and the DNS-dependent smarthost are
  asserted statically. All three fail LOUDLY rather than silently: a missing Secret
  blocks the pod, a malformed config fails the init container, and an unreachable
  relay logs a send failure.

## Known Stubs

None. Every file added is a live kustomize resource in all four render targets, the
new invariant runs inside a gate that CI already invokes, and both new ConfigMaps are
mounted by a Deployment rather than being inert declarations. The `mailhog:1025`
reference is a forward reference to a Service plan 29-09 creates, not a stub: the
first sink is unaffected by it and the entries are independent.

## Threat Flags

None beyond the plan's own register. Six of seven dispositions were `mitigate` and all
six were applied; the seventh was `accept` and is unchanged.

| ID | Applied as |
|---|---|
| T-29-07-01 | ClusterIP only; **0** Ingress documents naming either, with the whole-render count of 2 Ingresses as the control proving the counter can see them — plus INV-8, demonstrated at 0, 1 and 2 with all four allowlist-hygiene arms |
| T-29-07-02 | `smtp_auth_password_file` from a mounted Secret; 0 occurrences of the credential in the rendered config against a planted-canary control; `check-no-plaintext-secrets.sh` rc=0 on all four targets |
| T-29-07-03 | `grafana-admin` via `secretKeyRef` (never a `value:`, INV-2 rc=0) and `GF_USERS_ALLOW_SIGN_UP` read back as `false` **from the running instance**, not from the manifest. The manifest reading is explicitly NOT offered as the proof |
| T-29-07-04 | ONE receiver, TWO `email_configs`, same route/grouping/template; the four route knobs byte-equal to the compose corpus; no probe route, no `group_wait: 0s`, no second receiver |
| T-29-07-05 | INV-7's map moved in the same commit; the undeclared-policy arm fired for real on four targets and ARM C fired on the port half |
| T-29-07-06 | `accept`, unchanged. Staging keeps `log.level: DEBUG` (Phase 26 D-15); no PII is in staging's seeded data by design |
| T-29-07-SC | Both pins reused verbatim from compose with their dated exemptions already in `infra/dependency-horizons.yaml`; `check-dependency-horizons.sh` rc=0. No npm/PyPI packages in scope |

**One threat-surface addition worth naming beyond the register:** Grafana gets
**no public egress at all**. The compose analog's `GF_INSTALL_PLUGINS` is dropped
(measured: the dashboard corpus uses only core panel types), so nothing needs to fetch
from grafana.com — which means a dashboard cannot be used to reach arbitrary external
URLs from inside the cluster once the login is public.

## Cross-Cutting Quality Contracts

- **Web performance** — N/A (no user-facing page touched).
- **SEO / discoverability** — N/A (no public surface; the deliberate ABSENCE of one for
  Prometheus and Alertmanager is the security control this plan makes executable).
- **AI agent-readiness** — N/A (no API surface added or changed). The extended gate
  keeps the repo's uniform 0/1/2 exit contract, and INV-8 fails closed at 2 on a blind
  parser.
- **Security** — covered under Threat Flags. The load-bearing additions are a
  credential that never enters a config file, a default-deny namespace that now grants
  each monitoring workload exactly what it needs, and an empty-allowlist invariant on
  the two surfaces that have no authentication.
- **Falsifiable evidence + runtime parity** — **(a)** every criterion was run in its
  fail direction with its control; TWO criteria were corrected rather than silently
  substituted (the six-keys-vs-four source of truth, and the self-contradicting
  placeholder grep) and both are recorded with their measurements; one arm caught two
  real defects in new code; one instrument failure of my own (`git checkout` eating the
  file) was caught by verifying the restore BY CONTENT and is recorded rather than
  smoothed over. **(b)** Runtime parity: this plan ships no runtime artefact —
  `check-runtime-freshness.sh` and `check-container-config-drift.sh` are deliberately
  not run from a worktree, whose directory name changes the compose project name and
  would VOID them. The shared compose stack was read from and never mutated; every
  probe ran in a throwaway container and all were verified removed.

## User Setup Required

None for this plan. Downstream obligations, so they are not lost:

- **29-08** — the Grafana Ingress, its TLS SAN and its DNS record, in that order. The
  Service and the ingress-nginx ingress rule already exist, so INV-6 is satisfiable the
  moment the rule lands. INV-8 will fail the build if the same change publishes
  Prometheus or Alertmanager.
- **29-09** — `k8s/staging/mailhog.yaml` with label `app: mailhog` and a ClusterIP on
  1025/8025. Alertmanager's egress rule to it and staging's
  `alerting.secondary.smtp.*` keys are already in place and name exactly that.
- **29-12** — the live L-3 drill. The invocation is recorded verbatim in
  `alertmanager-config.yaml`'s header: `L3_SINK_TO` is the app-config value,
  `L3_HUMAN_TO` is read out of the `alertmanager-smtp` Secret at run time. Remember the
  group-collision trap: a repeated probe with a CONSTANT alertname is never dispatched
  within `group_interval` (5m).
- **Operator** — the `alertmanager-smtp` and `grafana-admin` Secret values are still
  parked. Nothing here fakes them, and the render step refuses to produce a config
  without them rather than starting with an empty password.

## Commits

| Hash | Type | What |
|---|---|---|
| `980a5ab9` | feat | Alertmanager config template + render script + Deployment/Service; four app-config keys; staging's second sink |
| `dc3175ef` | feat | Grafana provisioning ConfigMap (three byte-identical keys) + Deployment/Service |
| `0d73f424` | feat | seven observability NetworkPolicies replacing the placeholder; INV-7 map extended with `__SMTP_PORT__`; new INV-8; replacements in all four kustomizations; goldens; deferred-items.md |

## Self-Check: PASSED

Verified rather than trusted:

- **Files exist and carry the declared content:** `alertmanager-config.yaml` (contains
  `email_configs`), `alertmanager-deployment.yaml` (contains `prom/alertmanager:v0.27.0`,
  twice — initContainer and container), `grafana-provisioning.yaml` (contains
  `url: http://prometheus:9090`), `grafana-deployment.yaml` (contains
  `grafana/grafana:10.2.2`), `50-observability.yaml` (contains `prometheus`),
  `deferred-items.md`.
- **`must_haves` contains-assertions**, measured on the staging render:
  `prom/alertmanager:v0.27.0` = 2, `grafana/grafana:10.2.2` = 1,
  `url: http://prometheus:9090` = 1, `alertmanager` present in
  `check-render-invariants.sh`.
- **Commits exist** on `worktree-agent-a1aa9f02cbba5aebf`: `980a5ab9`, `dc3175ef`,
  `0d73f424`. None is on a protected ref.
- **No commit deleted a tracked file:** `git diff --diff-filter=D` empty for all three.
- **No untracked files left behind:** `git status --short` empty after the final commit;
  both scratch gate copies (`k8s/scripts/.armb-scratch.sh`, `.armh-scratch.sh`) removed
  and the directory listing verified.
- **Gates green on the final tree**, each rc recorded individually in the table above.

**Not updated by design (worktree mode):** `STATE.md` and `ROADMAP.md` are the
orchestrator's.

**Merge note for the orchestrator:** this plan touches `k8s/base/kustomization.yaml`,
`k8s/base/configmap.yaml`, `k8s/scripts/check-render-invariants.sh`, `k8s/goldens/*`
and all four kustomizations — several of which appear in **29-08**'s and **29-09**'s
`files_modified`. Both are LATER waves, so this lands in their base rather than
colliding. Resolve goldens by re-running `k8s/scripts/render-golden.sh --write` after
any merge, never by hand-editing one. In `check-render-invariants.sh` my changes are
the `NETPOL_IPBLOCK_EXPECTED` map, the `__SMTP_PORT__` derivation, the whole INV-8
block and the summary lines; 29-09 extends `NETPOL_INFRA_EXPECTED`, which is a
different map. Re-run `check-render-invariants.sh` and `render-golden.sh` after the
merge — both read the merged tree rather than any committed artifact.

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Completed: 2026-08-11*
