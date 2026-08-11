# Phase 29 — deferred items

Out-of-scope discoveries made while executing this phase's plans. Each is
**recorded rather than fixed**, with the measurement that found it, so the next
reader neither re-diagnoses it nor trusts a green gate that does not cover it.

---

## DEF-29-1 — the `core-java` Service does not expose 9091, so its scrape cannot connect

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
