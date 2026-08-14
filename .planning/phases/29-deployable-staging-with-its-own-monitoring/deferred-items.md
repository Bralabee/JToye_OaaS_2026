# Phase 29 — deferred items

Out-of-scope discoveries made while executing this phase's plans. Each is
**recorded rather than fixed**, with the measurement that found it, so the next
reader neither re-diagnoses it nor trusts a green gate that does not cover it.

---

## DEF-29-1 — the `core-java` Service does not expose 9091, so its scrape cannot connect

**RESOLVED 2026-08-14 — commit 6ce15b42** (quick task 260814-u4t, Lane A). The
`core-java` Service now carries a second ClusterIP port `9091`/`management`
beside the existing 9090. The objection this entry pre-answered held on
measurement: the Ingress still names `number: 9090` (`k8s/base/ingress.yaml:159`)
so the new port publishes nothing, and the golden diff was asserted to contain
zero `number:` lines. `core-java-scrape-allow` needed no change and got none.

Two things were found that this entry did not predict, both recorded rather than
quietly fixed:

1. The scrape comment claiming 9091 was off the Service so the scrape "reaches
   the POD network directly" was **false the day it was written**, independent of
   this change — a static target of a Service DNS name resolves to the ClusterIP
   and can never address a pod IP. Its second clause was false too: it cited
   `networkpolicies/20-core-java.yaml` as carrying the scrape ingress rule, but
   that file contains no 9091 rule at all (measured: `rg -uu '9091'` over
   `k8s/base/networkpolicies/` returns hits in `50-observability.yaml` only).
   Both clauses are corrected in place.
2. Fixing the Service is not free — see **DEF-29-8** below.

**The fix is now guarded permanently, not just made.** INV-9 in
`k8s/scripts/check-render-invariants.sh` asserts, in every one of the four
renders, that a static scrape target naming a rendered Service names a port that
Service exposes. Shown to fail in three directions before being trusted: removing
the Service port (rc=1, naming `core-java:9091`), repointing the target at a port
the Service lacks (rc=1, naming `core-java:9099`), and both vacuity guards
(rc=2 — zero targets found, and targets found but none checked).

**DO NOT DELETE THE MEASUREMENT BELOW.** It is what lets the next reader tell a
real fix from a plausible one.

- **Found during:** plan 29-07, Task 3, while writing Prometheus's egress rule
- **Owner:** whoever owns the core-java manifest (29-06 authored the scrape target;
  `k8s/base/core-java-deployment.yaml` is not in 29-07's `files_modified`)

**The measurement**

| Fact | Where | Value |
|---|---|---|
| scrape target | `k8s/base/monitoring/prometheus-config.yaml:179` | `core-java:9091` |
| container port | `k8s/base/core-java-deployment.yaml:60-62` | `9091` (name `management`) |
| **Service ports** | `k8s/base/core-java-deployment.yaml:692-696` | **`9090` only** |

`core-java:9091` resolves to the Service ClusterIP, which has no port 9091, so the
connection is refused. The `core-java` scrape target will be **DOWN on first
deploy** — and the manifest says the omission is deliberate: *"Deliberately NOT
added to the Service/Ingress below, so metrics + probe surface stay off the public
network."*

**Why it is not fixed here.** Two different layers. NetworkPolicy governs pod
`IP:port`, so 29-07's `prometheus-allow` egress rule (`app: core-java` on 9091) is
correct as written and needs no change either way. The Service-port gap is a
service-discovery defect in another plan's file.

**The likely fix, with the objection already answered.** Add a second ClusterIP
port (`9091`, name `management`) to the `core-java` Service. That does **not**
publish it: the Ingress names its backend port explicitly (`number: 9090`), and a
ClusterIP port is cluster-internal by definition — so the manifest's stated intent
("off the public network") survives. The alternative is `kubernetes_sd_config`
pod-level discovery, which 29-06 deliberately avoided because
`check-alert-liveness.sh` VOIDs on any job name it does not recognise and static
targets are what keep the eight names fixed.

**How it will surface if nobody acts:** `up{job="core-java"} == 0`, and the
`ServiceDown`/app-tier rules keyed on it firing permanently against a perfectly
healthy application.

---

## DEF-29-2 — `check-env-contract.sh`'s EDGE_MANAGEMENT_PORT reason is now stale

- **Found during:** plan 29-07, Task 1, while checking the gate was unaffected
- **Owner:** whoever next edits that allowlist

`k8s/scripts/check-env-contract.sh:186` justifies the omission with *"NOTHING
SCRAPES EDGE-GO IN K8S AT ALL. k8s/ ships zero monitoring manifests (DPLY-03) — no
Prometheus, no ServiceMonitor"*. Plan 29-06 made that false: there is a Prometheus,
and it scrapes `edge-go:8080`.

**The CONCLUSION is still correct and the entry must stay** — k8s deliberately
leaves `EDGE_MANAGEMENT_PORT` unset so `/metrics` stays on 8080, which is exactly
what the scrape config targets and what the pod annotations advertise. Only the
stated reason has gone out of date, and the entry's own text asks for the revisit
to happen "as ONE change with DPLY-03". Recorded rather than edited because
rewriting another gate's allowlist reason is not this plan's change, and a wrong
reason attached to a right entry is a documentation defect, not a live one.

---

## DEF-29-3 — the local overlay renders a Keycloak that cannot start there

- **Found during:** plan 29-08, Task 1
- **Owner:** whoever next edits `scripts/k8s-local-secrets.sh`

D-02 adds a Keycloak Deployment/Service/NetworkPolicy to `k8s/base`, so
`k8s/local` renders one too. `scripts/k8s-local-secrets.sh` predates the workload
and does not create the `db-username` / `db-password` keys on the
`keycloak-credentials` Secret, so the pod stops at `CreateContainerConfigError`
naming the missing key.

**IT CANNOT SIMPLY BE REMOVED FROM THE LOCAL RENDER, and that is not an oversight.**
LOC-2 in `k8s/scripts/check-render-invariants.sh` compares COUNTS: every Deployment
the BASE renders must appear in the local render at `replicas: 1`. A `$patch: delete`
would drop the local count below base's and FAIL that invariant — which is LOC-2
doing its job, since the class it exists to catch is a local render silently
ceasing to cover what base ships. Verified after this plan's change:
`LOC-2 OK (replicas x9/9 ...)`.

**Nothing that worked locally broke.** The local login path has never gone through
an in-cluster Service: LOC-1 asserts, per key by name, that `keycloak.issuer.uri`,
`keycloak.public.issuer.uri` and `keycloak.admin.base-url` all point at the COMPOSE
Keycloak on `host.minikube.internal:8085`, and they still do. LOC-5 independently
asserts that no local Ingress routes to a Service named `keycloak`, so the pod can
take no traffic even if it did start.

**The failure is loud, not silent** — a named missing Secret key at container
creation, not a running server quietly serving a realm nobody meant to create.

**What closing it requires, as ONE change:** the two Secret keys in
`scripts/k8s-local-secrets.sh`, AND a decision about which database the local
Keycloak uses. The compose Keycloak already owns the `keycloak` database on the
compose Postgres (`docker-compose.full-stack.yml` healthcheck asserts it exists),
so pointing an in-cluster local Keycloak at the same database would give two
servers one schema — which is why the answer is a decision and not a default, and
why this plan recorded it instead of guessing.

---

## DEF-29-4 — `rabbitmq-credentials` needs a THIRD key (`default_user.conf`) or the broker refuses every connection

**RESOLVED 2026-08-14 — commit 2918cc31** (quick task 260814-u4t, Lane A), for
the file this entry names as its owner. `scripts/staging-secrets.sh` now creates
`rabbitmq-credentials` with five keys, the new `default_user.conf` among them,
generated ONCE from the same `$RABBITMQ_USER` / `$RABBITMQ_PASSWORD` the flat
keys use — so the agreement is structural, not clerical. The premise was
re-verified before editing, because the format claim is tag-bound: the operator
pin is still `v2.22.3` (`scripts/staging-bootstrap.sh:209`), the CR still uses
`secretBackend.externalSecret` naming this Secret, and core-java still reads
`username`/`password` from it.

**A defect was caught in the fix itself by its own falsification arm**, which is
the reason the arm exists. The obvious spelling —
`$(printf 'default_user = %s\ndefault_pass = %s\n' ...)` — yields a conf file
with **no final newline**, because `$(...)` strips trailing newlines. Measured
through `kubectl create secret --dry-run=client -o json`, decoding the value and
counting bytes: **55 where the operator's own format is 56**. Both lines are
still present and separated, so the loss is invisible to an eyeball and to any
line-by-line comparison — only a byte count could see it. Fixed with a sentinel
character stripped by `${var%.}`.

**WHAT IS STILL NOT PROVEN, and cannot be from here.** That the operator actually
projects the key, and that the broker accepts the credential, are cluster-side
facts. No static gate can cover them: secret VALUES never appear in a kustomize
render, and `check-no-plaintext-secrets.sh` exists to guarantee they never will.
This lane proved the SHAPE (a dotted key name is legal; a newline-bearing value
survives argv into a Secret intact) and the BINDING (the script builds the stanza
from the same variable names as the flat keys, shown able to fail by repointing
it at `$STOMP_CLIENT_LOGIN`). The acceptance proof belongs to 29-10/29-11 on a
live broker.

**NOT closed everywhere — see DEF-29-10.** The staging bootstrap path is fixed;
the sealed-secrets runbook's key table still omits the key on the production
path. `scripts/k8s-local-secrets.sh` is correct as-is and needs no change:
`k8s/local` deletes the RabbitMQ CR and shims `rabbitmq.host` to
`host.minikube.internal`, so local talks to the COMPOSE broker, which has no
operator to project the key.

- **Found during:** plan 29-09, Task 1, reading the cluster operator's source at the pinned tag
- **Owner:** `scripts/staging-secrets.sh` — plan 29-10's file, deliberately not edited from 29-09

**The measurement.** Read at `v2.22.3`, the tag `scripts/staging-bootstrap.sh`
pins. `internal/resource/statefulset.go:958-983`
(`appendDefaultUserSecretVolumeProjection`): when `secretBackend.externalSecret`
is set, the operator projects **exactly one key** out of the named Secret —

```go
Items: []corev1.KeyToPath{{ Key: "default_user.conf", Path: "default_user.conf" }}
```

— and mounts it at `/etc/rabbitmq/conf.d/11-default_user.conf`. The content format
is an ini stanza (`internal/resource/default_user_secret.go:204-218`):

```
default_user = <username>
default_pass = <password>
```

**The collision.** `k8s/base/core-java-deployment.yaml:319-328` reads `username`
and `password` from the *same* `rabbitmq-credentials` Secret. So that Secret needs
three keys, and the third must agree with the first two:

| Key | Read by | Consequence if absent/divergent |
|---|---|---|
| `username` | core-java `RABBITMQ_USER` | app authenticates as the Spring default `jtoye` |
| `password` | core-java `RABBITMQ_PASSWORD` | app has no credential |
| `default_user.conf` | **the operator, and nothing else** | broker's default user is not the one the app uses |

**Why it is not fixed here.** 29-09 owns the manifests; `scripts/staging-secrets.sh`
belongs to plan 29-10 and that worktree is parked. Editing another plan's file
across a worktree boundary is how two agents produce one broken merge.

**How it will surface if nobody acts:** every AMQP and STOMP connection refused
with `ACCESS_REFUSED`, on a cluster where the CR is `Ready`, the pod passes its
probes, the NetworkPolicy permits the traffic and every static gate is green. The
platform reports a *messaging* failure caused by a *secret-shape* omission.

---

## DEF-29-5 — `keycloak.admin.base-url` in the base ConfigMap still names the `jtoye-infrastructure` namespace

- **Found during:** plan 29-09, Task 1, while sweeping app-config for infra-namespace addresses
- **Owner:** whoever next edits `k8s/base/configmap.yaml`'s identity block (29-08's surface)

**The measurement** (2026-08-11, from the repo root, `--include` scoped so the
`.planning` tree and the goldens cannot pad the count):

```
k8s/base/configmap.yaml:16   keycloak.admin.base-url: "http://keycloak.jtoye-infrastructure.svc.cluster.local:8080"
k8s/base/configmap.yaml:262  redis.host:              "redis-cluster.jtoye-infrastructure.svc.cluster.local"
k8s/base/configmap.yaml:295  rabbitmq.host:           "rabbitmq.jtoye-infrastructure.svc.cluster.local"   <- fixed by 29-09
k8s/base/configmap.yaml:331  stomp.broker.relay-host: "rabbitmq.jtoye-infrastructure.svc.cluster.local"   <- fixed by 29-09
```

D-02 (plan 29-08) moved Keycloak in-cluster, into the app's own namespace, exactly
as D-09 moved the broker — but the admin base URL still points at a Service in a
namespace this repository does not create. `redis.host` is a different case and is
**correct as it stands**: D-09 moved Redis to a managed endpoint addressed by
`ipBlock`, and staging patches the host in its own overlay.

**Why it is not fixed here.** `keycloak.admin.base-url` drives the Keycloak
deprovisioning admin client (issue #102), which is `jtoye.keycloak.admin.enabled=false`
by default — so the value is inert today and changing it is a behaviour decision
about a feature 29-09 does not own. It is recorded rather than swept in because a
same-shaped edit in the same file is exactly how an unrelated regression rides
along on a plausible-looking commit.

**How it will surface if nobody acts:** the admin re-trigger endpoint
`POST /api/v1/admin/tenants/{id}/keycloak/deprovision` fails to resolve its host
the first time an operator enables the feature in staging — long after the change
that made it wrong.

---

## DEF-29-6 — `SMTP_STARTTLS` has never reached JavaMail: the property name is misspelt

- **Found during:** plan 29-09, Task 2, while deciding the staging value for a sink that speaks no TLS
- **Owner:** whoever next edits `core-java/src/main/resources/application.yml`'s mail block

**The measurement.** `core-java/src/main/resources/application.yml:159-164`:

```yaml
    properties:
      mail:
        smtp:
          auth: ${SMTP_AUTH:false}
          starttls:
            enabled: ${SMTP_STARTTLS:true}
```

Spring Boot passes everything under `spring.mail.properties.*` into the JavaMail
`Properties` **verbatim**, so this sets a property literally named
`mail.smtp.starttls.enabled`. JavaMail reads `mail.smtp.starttls.enable` — no
trailing "d". The two adjacent keys make the contrast visible: `auth` is spelt
correctly and IS read; `starttls.enabled` is not a JavaMail property at all.

**The consequence.** STARTTLS has been effectively **off in every environment**
since this block was written, regardless of what the ConfigMap said — including
the SES path Phase 22 shipped, where `smtp.starttls: "true"` in
`k8s/base/configmap.yaml` reads as an assurance it does not provide.

**Why it is not fixed here.** It is an application-configuration defect on the
production email path, not a manifest one, and correcting the spelling *changes
behaviour* — the first connection to a relay that requires STARTTLS would start
negotiating it, and one that does not offer it would start failing. That belongs
in a change that can exercise the SES path, which this plan cannot.

**What 29-09 did instead.** Set `smtp.starttls: "false"` in the staging patch, so
the declaration matches reality now AND stays correct at the moment the spelling
is fixed: staging points at MailHog, which advertises no STARTTLS, so a stale
`"true"` would be the value that breaks first.

---

## DEF-29-7 — twelve horizon rows still cite a stale line number

- **Found during:** plan 29-09, Task 3
- **Owner:** whoever next runs `bash scripts/check-dependency-horizons.sh --refresh`

Twelve `NOTE <id>: pin not at <file>:<line>; found at line(s) N (run --refresh)`
advisories, unchanged in kind since before this phase. They are **NOTE class,
advisory, exit 0** — H-5's hard failure is "the pin is on no non-comment line at
all", which is `site-unresolvable=0`. The pin is found in every case; only the
recorded line has drifted as the files grew.

Plan 29-09 corrected **two** of the fourteen that existed at its start (`rabbitmq`
`:149 -> :240`, `mailhog` `:526 -> :702`), because it was editing those rows
anyway. It deliberately did **not** run `--refresh`, which would have rewritten
ten unrelated rows into this plan's commit — the change would look like a
horizons change and read as a line-number sweep, and a reviewer would have to
separate them by hand.

**How it will surface if nobody acts:** it will not, beyond twelve advisory lines
on every run. The risk is habituation — a reader who scrolls past twelve NOTEs
routinely is the reader who scrolls past the thirteenth that means something.

---

## DEF-29-8 — one ClusterIP in front of N replicas makes `rate()` over core-java's metrics unsound

- **Found during:** quick task 260814-u4t, Lane A, Task 1 — it is the second-order
  cost of fixing DEF-29-1, recorded at the moment the fix was made rather than
  discovered later from a confusing graph
- **Owner:** plan 29-12, which already owns the alert corpus and its parity gate

**The measurement**

| Fact | Where | Value |
|---|---|---|
| scrape target | `k8s/base/monitoring/prometheus-config.yaml` | `core-java:9091` — a Service DNS name |
| what it resolves to | the fix from DEF-29-1 | the `core-java` ClusterIP |
| staging replicas | `k8s/staging/kustomization.yaml` | **2** |
| production replicas | base HPA floor | **3** |
| `instance` label | `prometheus-config.yaml` `relabel_configs` | relabelled to the CONSTANT `core-java` |

A ClusterIP load-balances **per connection**. Each scrape therefore lands on a
possibly different pod, while `instance` is pinned to a constant — so Prometheus
sees ONE series whose counters jump between unrelated pods. Counters are
monotonic per process but not across processes, so every apparent decrease reads
as a counter reset and `rate()`/`increase()` over that series is unsound.

**Why it was accepted rather than fixed.** It is strictly better than the state it
replaced. Before DEF-29-1's fix the connection was REFUSED: no data at all, and
the `ServiceDown`/app-tier rules firing permanently against a healthy
application. A working scrape with a written-down aggregation caveat beats
`up{job="core-java"} == 0`. This disposition was put to the owner and taken
deliberately, not by default.

**What closing it requires, as ONE change:** a headless Service (`clusterIP:
None`) plus `dns_sd_configs`, which resolves to the individual pod IPs. That
keeps the eight job names fixed — `check-alert-liveness.sh` keys on JOB names and
DNS SD does not change them, which was 29-06's reason for avoiding
`kubernetes_sd_config` — but it necessarily drops the constant `instance` relabel,
because per-pod `instance` is the entire point. That touches every existing
alert's label set and the alert-corpus parity gate, so it is a change with its own
falsification arms, not a line in an offline lane.

**How it will surface if nobody acts:** rate-based panels and any alert with a
`rate()` or `increase()` over a core-java counter will under-report — silently,
and worst precisely when it matters, because a scrape landing on a freshly
restarted pod looks like a reset. `up` will be 1 throughout, so nothing looks
broken.

---

## DEF-29-9 — `core-api-client-secret` is named in four docs and read by nothing; three confidential clients need more than a key

- **Found during:** quick task 260814-u4t, Lane A, Task 3 — a search run to answer
  the question, not an assumption carried into it
- **Owner:** whoever next edits the Keycloak client set (docs) / plan 29-11 (realm)

**The measurement** (2026-08-14, `rg -uu` over `k8s/`, `scripts/`, `docs/`,
excluding `k8s/goldens/**` and `.claude/worktrees/**`).

The search was run WITH A MANDATORY POSITIVE CONTROL, and the control earned its
place: the first attempt ran the searches inside a `bash script.sh` subshell,
where `rg` — a Claude Code **shell function**, with no system ripgrep to fall
through to — does not exist. Every search returned `rc=127` and **zero hits**,
which is indistinguishable from a legitimate "not found". Had the control not run
first, "no consumers" would have been recorded as a finding when it was an
artefact of the instrument. Re-run directly, the control returns its 16 hits.

| Key | Manifest consumers (`key: <name>` in a secretKeyRef) | Doc mentions |
|---|---|---|
| `frontend-client-secret` | **2** — `frontend-deployment.yaml:186`, `keycloak-deployment.yaml:186` | several |
| `core-api-client-secret` | **0** | **4** |

The four doc sites: `k8s/DEPLOYMENT.md:147`, `k8s/QUICK_START.md:228`,
`k8s/base/secrets-template.yaml.example:127`, `docs/runbooks/sealed-secrets.md:38`.

The zero is real, not a pattern artefact: the identical pattern shape
(`key: frontend-client-secret`) returns two hits on the same tree.

**So no key was added, by a rule fixed before the answer was known:** a key is
created only if a manifest in a kustomize render reads it via `secretKeyRef`, or
an in-cluster process provably reads it by name. Neither holds. A key named only
in prose is a DOCUMENTATION defect, and manufacturing an unconsumed Secret key
does not fix it — it creates a standing invitation to rotate a value nothing
reads. The measured consumer map is now recorded in `scripts/staging-secrets.sh`
beside the `keycloak-credentials` block so this is not re-litigated from the
`STATE.md` paraphrase ("a second client-secret key"), which compresses
29-08-SUMMARY's THREE clients into a singular the source does not support.

**Why the four doc sites were RECORDED and not corrected here, and the choice is
stated rather than assumed:** `scripts/check-doc-citations.sh` was **already red
at this lane's baseline** (13 pre-existing C-3 violations, all line-number drift
in `STACK.md`, `INTEGRATIONS.md` and `LOCAL.md` — none in any file this lane
touches). The pre-committed rule was: doc gate green at baseline -> may correct in
place; already red -> record instead, and say which and why. Editing doc sites
while that gate is red would entangle this lane's change with an unrelated red and
make the next reader's bisect harder.

**The bigger half is not a doc fix at all.** `edge-api`,
`integration-catalog-ro` and `integration-orders-rw` each need a secret key AND a
realm entry AND a decision about whether staging needs them at all — a
confidential client with a secret nobody holds is worse than an absent one, which
is exactly why `k8s/base/keycloak/realm-import-configmap.yaml:62-69` omits them
on purpose and says so.

---

## DEF-29-10 — the sealed-secrets runbook's key table omits `default_user.conf` on the production path

- **Found during:** quick task 260814-u4t, Lane A, Task 2, while checking whether
  any other site creates `rabbitmq-credentials`
- **Owner:** whoever next edits `docs/runbooks/sealed-secrets.md`

**The measurement.** `docs/runbooks/sealed-secrets.md:41` lists the
`rabbitmq-credentials` keys as `username`, `password`, `stomp-login`,
`stomp-passcode` — the pre-DEF-29-4 set. An operator sealing that Secret from
this table produces one the cluster-operator cannot use, and the result is
DEF-29-4 verbatim: `ACCESS_REFUSED` on every AMQP and STOMP connection, on a
cluster where the CR is `Ready` and every static gate is green.

Two adjacent rows in the same table are wrong in the same direction and are worth
correcting in the same pass: it lists `core-api-client-secret` (read by nothing —
DEF-29-9) and attributes `keycloak-credentials` to `frontend-deployment.yaml`
alone, when `keycloak-deployment.yaml` reads four of its keys.

**Why it is not fixed here.** Same reason as DEF-29-9: `check-doc-citations.sh`
was already red at this lane's baseline, and the pre-committed rule was to record
rather than edit doc sites in that state.

**Checked and found CORRECT, so nobody re-opens it:**
`scripts/k8s-local-secrets.sh` also creates `rabbitmq-credentials` and needs no
`default_user.conf`. `k8s/local` deletes the RabbitMQ CR
(`rabbitmq-cluster-delete-patch.yaml`) and shims `rabbitmq.host` to
`host.minikube.internal`, so the local overlay talks to the COMPOSE broker —
there is no operator in that path to project the key. Its own comment already
says "what the compose broker actually accepts".

**How it will surface if nobody acts:** the first production broker rollout that
follows the runbook rather than the bootstrap script fails closed, and the
symptom points at messaging rather than at a secret shape.
