# Local Kubernetes Rehearsal — `k8s/local`

**Operator runbook for the `jtoye-local` minikube overlay.**
Authored in Phase 26 (plan 26-06). Live evidence captured by plans 26-07 and 26-08.

You should be able to bring this cluster up, verify it, and tear it back down using nothing but
this file. If you find yourself needing to read a `.planning/` plan or a research document to get
through a step, that is a defect in this runbook — fix it here.

---

## 1. What this is and is not

**What it is.** The committed, reviewable, re-runnable replacement for the imperative sequence used
during the first live-deploy rehearsal on 2026-07-14. That rehearsal reached 11/11 pods READY through
hand-typed `kubectl patch configmap` / `kubectl create secret` steps that lived nowhere in the
repository — so it could not be reviewed, re-run or regression-tested. `k8s/local` (6 files, 23
rendered resources) plus `scripts/k8s-local-up.sh` are that knowledge, in git.

**What it is for.** Rehearsing the **production config path** on real Kubernetes. The overlay keeps
`SPRING_PROFILES_ACTIVE=prod` (decision D-10) deliberately: the internal 9091 management port, no SQL
logging, and production pool sizes are the point. It also exercises the **Ingress path**, real
rollout and probe behaviour, and the STOMP **relay** broker mode — three surfaces no other local
runtime touches (dev compose defaults to `STOMP_BROKER_MODE=in-memory`, so a normal compose run
never exercises the relay at all).

**What it is NOT.** It is not a development runtime. For day-to-day work — writing code, running the
Jest/Playwright suites, iterating on a page — use Docker Compose:

```bash
scripts/start-dev.sh        # the canonical local dev + E2E runtime
```

Compose remains canonical for development and E2E (`CLAUDE.md` § "Runtime & deploy topology");
Kubernetes remains the staging/production deploy target. **Neither layer is retired.** This cluster
is a deploy-layer rehearsal you run occasionally, not a place to live.

For the staging/production recipe see `k8s/QUICK_START.md` and `k8s/DEPLOYMENT.md`. Do not mistake
either for this one — the local path needs guards, an out-of-band secret bootstrap and locally built
images that the production path does not.

---

## 2. The compose XOR k8s rule

**compose XOR k8s** applies at *local runtime only*, and it is more precise than "run one or the
other":

| Compose layer | Required state while the cluster runs | Why |
|---|---|---|
| **App** services — `core-java`, `frontend`, `edge-go`, `mcp-server` | **DOWN** | Both they and the cluster pods write the **same shared dev Postgres**. Two writers on one database is the footgun this rule exists to stop. |
| **Backing** services — `postgres`, `redis`, `rabbitmq`, `keycloak`, `minio`, `mailhog` | **UP** | The cluster does not run its own backing services. Every endpoint in the overlay is shimmed to `host.minikube.internal`, so the pods **consume** these six. |

Bring the four app containers down (and leave the six backing services running):

```bash
docker compose -f docker-compose.full-stack.yml stop core-java frontend edge-go mcp-server
```

`scripts/k8s-local-up.sh` **refuses** rather than trusting you to have done it. It checks both halves
and names the offending services:

- `REFUSED [compose-apps-running]: compose APP service(s) still running: …`
- `REFUSED [compose-backing-down]: compose BACKING service(s) not running: …`

The guard is **read-only by construction** — it never stops, starts or removes a container. Stopping
the app containers is a human decision, because a second concurrent session may own that stack.

**The rule has a second, cluster-side half — a stopped compose stack is not the same as one writer.**
The compose guard above inspects compose and nothing else, so it cannot see a writer that is already
*inside* the cluster. Because a **Stopped** minikube profile preserves etcd, `minikube start` alone can
restore a whole namespace of pods that are writing the shared dev Postgres (measured — see §7, A1).
Step **3b** therefore inventories the cluster immediately after the profile starts and before anything
is applied, and refuses on any namespace outside the expected one holding live pods:

- `REFUSED [cluster-writers-present]: the cluster ALREADY contains workloads outside the expected local namespace 'jtoye-local': …`

It names each offending namespace, its live pod count, the pod phases and the **image tags** — because a
stale namespace is usually stale *code* too. Exempt: `jtoye-local` (read from
`k8s/local/kustomization.yaml`, so there is one source of truth) plus `kube-system`, `kube-public`,
`kube-node-lease` and `ingress-nginx`. **`default` is NOT exempt** — it is a writable namespace, not a
system one, and it is where an unqualified `kubectl run` with no `-n` lands, which is precisely the
accidental-writer case this guard is for. Like the compose arms it is **read-only**: deleting a
namespace is your decision, and it prints the two commands to inspect and then delete. It **fails
closed** — a missing `kubectl`, an unreachable API server, an unparseable response or an empty
inventory all exit **2** (VOID), never 0.

---

## 3. Prerequisites

**Tools** (the versions this path was built and verified against):

| Tool | Verified version | Notes |
|---|---|---|
| `kubectl` | v1.33.3 | Bundled Kustomize **v5.6.0** — `kubectl kustomize` is used throughout; no separate `kustomize` binary is needed. |
| `minikube` | v1.36.0 | Bundles ingress-nginx controller **v1.12.2** — see §6, this is load-bearing. |
| `docker` | 29.6.2 | Driver for the minikube profile and the image builds. |
| `jq` | any | Used to parse the minikube profile registry. |

**`.env` keys.** The scripts read the gitignored `.env`; `.env.example` documents every key with its
consumer. The local-k8s block is:

```
K8S_LOCAL_POD_HOST            # host.minikube.internal — the pod-side name for the host gateway
K8S_LOCAL_DB_PORT             # 5433  published compose Postgres port
K8S_LOCAL_KC_PORT             # 8085  published compose Keycloak port
K8S_LOCAL_REDIS_PORT          # 6379
K8S_LOCAL_AMQP_PORT           # 5672  RabbitMQ AMQP
K8S_LOCAL_STOMP_PORT          # 61613 RabbitMQ STOMP
K8S_LOCAL_MINIO_PORT          # 9000  MinIO S3 API
K8S_LOCAL_SMTP_PORT           # 1025  Mailhog SMTP
K8S_LOCAL_KUBE_CONTEXT        # the ONLY context this tooling will target
K8S_LOCAL_MINIKUBE_PROFILE    # profile name
K8S_LOCAL_MINIKUBE_CPUS       # 4
K8S_LOCAL_MINIKUBE_MEMORY     # 12g
K8S_LOCAL_BACKUP_BUCKET       # bucket for the pg-backup CronJob dumps
DB_BACKUP_PASSWORD            # BYPASSRLS dump-role password (refuses a CHANGE_ME value)
NOTIFICATION_UNSUBSCRIBE_SECRET  # optional; empty keeps one-click unsubscribe inert
```

Every port above is the **published compose value** — none is a script literal. If a compose port
moves, change it here and nowhere else.

Values live only in `.env`. This runbook names variables, never values; keep it that way.

**`/etc/hosts`.** The ingress hostnames must resolve to the minikube node IP. One line, exact shape:

```
<minikube-node-ip> api.jtoye.local app.jtoye.local
```

`scripts/k8s-local-up.sh` reads the hostnames **from the rendered overlay** and the IP from the
profile, prints the exact line with the real values substituted, and stops. It never escalates
privilege — you add the line yourself.

---

## 4. Bring-up

One command, idempotent, re-runnable from a stopped cluster:

```bash
scripts/k8s-local-up.sh                 # full bring-up
scripts/k8s-local-up.sh --dry-run-only  # stop after the server-side dry-run; no real apply
scripts/k8s-local-up.sh --skip-build    # reuse existing local tags (refuses if any is absent)
```

It runs the steps below in this order (the script itself is the authoritative list — it also carries a
`4b` admission-webhook gate not tabulated here, see §7). The order is load-bearing, and each step is
runnable by hand if one fails:

| # | Step | What it does / how to run it alone |
|---|---|---|
| 0 | flags | Parsed first, so a typo can never fall through into a mutating step (`--nope` → exit 2, zero tool calls). |
| 1 | preflight | Loads `.env`, asserts the `K8S_LOCAL_*` contract by name, checks `docker/kubectl/minikube/jq`, then runs `scripts/verify-env.sh`. |
| 2 | compose XOR | §2. **Precedes the profile start** — a mis-invocation cannot start a cluster against a live compose stack. Only the compose half; see 3b. |
| 3 | profile + context | `minikube start -p <profile> --cpus … --memory …` **only if the profile is not already up**, then asserts the kubectl context (it does not exist in kubeconfig until the profile starts). A healthy profile reports status `OK`, not `Running` — see §7. |
| 3b | cluster XOR | §2, second half. Inventories the cluster the instant it exists and refuses on live pods in any unexpected namespace. **This position is the point**: the hazard opens at `minikube start`, which step 2 ran too early to see. |
| 4 | ingress addon | `minikube addons enable ingress -p <profile>` (idempotent). metrics-server is deliberately **not** enabled — see §6. |
| 5 | reachability | `minikube ssh -p <profile> -- nc -vz -w 3 host.minikube.internal <port>` for all seven ports. |
| 6 | hosts file | Checks each rendered ingress hostname resolves to the node IP; prints the fix. |
| 7 | images | Builds and loads all four images, then prints their identities. See §7 (stale-image rule). |
| 8 | bootstrap | `scripts/k8s-local-secrets.sh` — namespace, BYPASSRLS dump role, backup bucket, all Secrets. |
| 9 | apply | `kubectl apply -f k8s/local/namespace.yaml` **first**, then `apply -k k8s/local --dry-run=server` printed verbatim, then the real apply. |
| 10 | rollout | `rollout status deploy/core-java` (5m), `deploy/frontend` (3m), `deploy/edge-go` (3m). |
| 11 | smoke | `curl` `<api>/health`, `<api>/public/shops`, `<app>/api/health` — **through the ingress hostnames**, never a loopback address. |
| 12 | evidence | Prints a copy-pasteable block for §11 of this file. |

Every host, port, hostname, tag and browser build-arg comes from `.env` or from the **committed
overlay render**, so the script cannot drift from what the cluster actually serves.

The context guard has four named refusal arms — `unresolvable-profile-ip`, `wrong-name`,
`context-absent`, `server-host-mismatch`. The last one compares the context's API-server host to the
profile's node IP, so a same-named context pointing elsewhere is still rejected. `kubectl config
use-context` is never called; every cluster call passes `--context` explicitly. **This matters on
this host: the other kubectl context is employer infrastructure and must never be targeted.**

---

## 5. What the overlay changes

| Change | Value(s) | Why |
|---|---|---|
| Endpoint shims (8) | `keycloak.issuer.uri`, `keycloak.admin.base-url`, `redis.host`, `rabbitmq.host`, `stomp.broker.relay-host`, `s3.endpoint`, `s3.backup.endpoint`, `smtp.host` → `host.minikube.internal` | Pods consume the compose backing services on the host. The underlying gateway IP varies by driver, so the **name** is used, never an IP. |
| Deliberately **not** shimmed | `s3.public-url` → `localhost:9000/…`, `keycloak.public.issuer.uri` → `localhost:8085/realms/jtoye-dev` | Browser-reachable, not pod-reachable. See the note below. |
| Scale triple | `replicas: 1` ×3, HPA `minReplicas: 1` ×3, PDB `minAvailable: 1` ×3 | `replicas:` reaches Deployments only, so HPAs and PDBs need the second mechanism (`scale-patch.yaml`, one file, six documents). A PDB of 2 over 1 replica makes the pod undrainable. |
| HPA `maxReplicas` | **UNCHANGED** from base | It is an input to `k8s/scripts/check-connection-math.sh`. Lowering it locally would make the local render stop proving the same connection arithmetic. An HPA with no metrics-server never scales up anyway. |
| Ingress hosts | `api.jtoye.local` → `core-java:9090`, `app.jtoye.local` → `frontend:3000`; SSE ingress `api.jtoye.local/api/v1/orders/stream` (`pathType: Exact`) | The one deploy surface no other local runtime exercises, and it gives NextAuth a stable callback origin. 9091 is the internal management port and is published through no ingress. |
| Image tags | all three service images pinned to `newTag: local` | A locally built tag that exists in no registry. Base sets `imagePullPolicy: IfNotPresent`, which is exactly right for `minikube image load`. The `pg-backup` image is loaded as-is at its immutable tag. |
| Split-horizon issuer pair | `keycloak.issuer.uri` = pod-reachable; `keycloak.public.issuer.uri` = browser-reachable | Two different values, by design. Set both to the pod URL and every real token is rejected on `iss` mismatch; set both to the browser URL and the pod cannot fetch JWKS. This is the class that once caused a total live-auth outage. |
| `log.path` | `/tmp` | See §7, PIT-5. |
| Secrets | **zero** `kind: Secret` in the render | Enforced by `k8s/scripts/check-no-plaintext-secrets.sh`, which auto-discovers every overlay. Secrets arrive out-of-band from `scripts/k8s-local-secrets.sh`. |

**Why `s3.public-url` stays browser-reachable while `s3.endpoint` is pod-reachable.** They are
consumed by two different clients. `s3.endpoint` is where the **pod** writes, via a server-side AWS
SDK call, so it must be the shimmed host. `s3.public-url` is the origin baked into the image URLs
that go out in API responses and are then fetched by the **browser on your own machine** — where
`host.minikube.internal` does not resolve at all. Shimming it would leave every product image broken
while every server-side upload still reported success: a silent, browser-only failure. The same
asymmetry is why the Keycloak issuer pair holds two values.

---

## 6. What local does NOT prove

Read this before treating a green local run as a production guarantee. **A local pass proves manifest
validity and the ingress path. It proves none of the following.**

**TLS and HSTS — not proven.** The local overlay sets `tls: null` and `ssl-redirect: "false"` on both
Ingresses, because there is no cert-manager here. `secretName: jtoye-tls` would never exist and nginx
would silently serve its own self-signed fallback certificate. So local says nothing about TLS
termination, certificate issuance or renewal, or HSTS. (ASVS V9 is deliberately degraded locally.)

**The nginx security-header snippet — not proven.** minikube v1.36.0 bundles ingress-nginx controller
**v1.12.2**, where `allow-snippet-annotations` defaults to `false` and `annotations-risk-level`
defaults to `High` (hardened defaults since ingress-nginx v1.9.0). The base ingress carries
`nginx.ingress.kubernetes.io/configuration-snippet` with 6 `more_set_headers` directives — including
HSTS, `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy` and `Permissions-Policy`. That
annotation is in the Critical-risk class, so the validating admission webhook **rejects both Ingress
objects**. The local overlay therefore nulls the annotation. It is **UNCHANGED in staging and
production**, and CI asserts both halves (the local render has no snippet; the production render
keeps it).

> **Do NOT "fix" this by enabling snippet annotations on the local cluster.** Setting
> `allow-snippet-annotations: "true"` / `annotations-risk-level: "Critical"` on the ingress addon
> would make the apply succeed by re-enabling a documented Critical-risk annotation class that
> ingress-nginx disables by default — weakening a cluster's admission posture for local
> convenience. `scripts/k8s-local-up.sh` never touches the controller's configuration.
>
> Flag for staging/production too: whichever ingress-nginx version runs there must have snippets
> enabled, or those six headers are silently absent (or the apply has been failing).

**NetworkPolicy enforcement — not proven (D-11).** All 6 policies render unchanged locally and are
validated as *manifests*. minikube's default CNI **does not enforce NetworkPolicies at all**, so
nothing about the traffic rules is exercised. This is not a small gap — under an enforcing CNI the
rendered policy set would deny the **entire** local traffic pattern:

- `k8s/base/networkpolicies/20-core-java.yaml` allows public egress to `0.0.0.0/0` with
  `10.0.0.0/8`, `172.16.0.0/12` and `192.168.0.0/16` in `except[]`. The minikube host gateway sits on
  the bridge inside `192.168.0.0/16`, so every call to `host.minikube.internal` is denied.
- That same policy's only in-cluster allow targets `namespaceSelector: jtoye-infrastructure`, a
  namespace that **does not exist locally**.
- The ports the local pattern actually needs — `5433` (Postgres), `8085` (Keycloak), `6379` (Redis),
  `5672` (AMQP), `61613` (STOMP), `9000` (MinIO), `1025` (SMTP) — appear in no allowed egress rule
  for the host gateway. The in-cluster rule lists `5432/6379/5672/61613/9000/9093`, but scoped to
  that non-existent namespace.

So "an enforcing CNI would need explicit egress rules" is concretely: a rule permitting TCP to the
host gateway CIDR on those seven ports. The policy flow matrix and rollback steps are in
`k8s/base/networkpolicies/README.md`.

**HPA scaling behaviour — not proven.** metrics-server is deliberately not enabled; D-09 sets
`minReplicas: 1` so nothing needs to compute a metric. An HPA here sits inert.

---

## 7. Known findings and caveats

**PIT-5 — the logback boot error, fixed locally.** Under the prod profile,
`application-prod.yml:91` logs to `${LOG_PATH:/var/log/jtoye}/application.log`. The container runs as
`runAsUser: 1000`, `/var/log` is root-owned, and the image never creates that directory — so
logback's FileAppender fails to start with a `FileNotFoundException … Permission denied` on every
boot. It is **non-fatal** (the app continues; the 2026-07-14 run reached 11/11 READY that way), but it
is noise that reads like a real fault, and file logging is silently absent. The local overlay sets
`log.path: /tmp`, which the pod user can write. The **base default is unchanged**; the durable fix is
an `emptyDir` mounted at `/var/log/jtoye` in the base (recorded as a deferred item).

**PIT-6 / D-17 — the kube-dns selector poisoning, fixed and CI-asserted.** The `labels:` transformer
used to carry `includeSelectors: true`, which injects the common labels into **every**
`podSelector.matchLabels` kustomize finds — including the DNS-egress rule of
`networkpolicies/20-core-java.yaml`. The rendered selector then read
`{k8s-app: kube-dns, app.kubernetes.io/managed-by: kustomize, app.kubernetes.io/part-of: jtoye-platform, environment: …}`.
Real kube-dns pods carry none of those extra labels, so the rule matched nothing: **total DNS
blackhole for core-java under an enforcing CNI.** This was live in `k8s/base` **and in
`k8s/production`**, not merely in the new local overlay.

Plan 26-01 fixed it by replacing `includeSelectors: true` with `includeSelectors: false` plus an
explicit three-kind `fields:` list, in all three kustomizations *and* repeated in `k8s/local` (each
overlay's own `labels` entry triggers its own transformer pass). Plan 26-03's **INV-3** in
`k8s/scripts/check-render-invariants.sh` asserts it **on the render**, per target, so the class
cannot return silently with the next transformer edit.

Note the reason a render-level assertion was necessary: `k8s/scripts/validate-networkpolicies.py`
cannot see this defect at all. It parses the **raw files** under `k8s/base/networkpolicies/`, never
the kustomize output — and the poisoning is introduced *by* the render. It is also not wired into CI.
That is exactly why INV-3 exists.

Because this fix was the stated prerequisite for the "Calico CNI locally to prove enforcement"
follow-up, **that prerequisite is now cleared** — a Calico local cluster would no longer inherit a
DNS blackhole.

**P-6 — the immutable-selector caveat.** The label transformer still writes into
`Deployment.spec.selector.matchLabels`, and that field is **immutable after creation**. This is fine
for a fresh `jtoye-local` namespace. But if you ever change the overlay's `labels:` pairs on a cluster
that already has these Deployments, `kubectl apply` fails with
`field is immutable` — and the only recovery is to **delete and recreate the Deployments** (Services
and PDBs likewise carry transformed selectors). Plan or expect a delete/recreate whenever a label
pair changes.

**A1 — stale etcd state on first start.** A **Stopped** minikube profile preserves etcd. Namespaces
and Secrets created imperatively during the 2026-07-14 rehearsal may therefore reappear the moment
the profile starts — and a leftover Secret can **mask a genuinely missing one**, turning a real gap
into a false green. On first start, take an inventory and clean up:

```bash
kubectl --context <ctx> get ns,secrets -A
# delete any stale rehearsal namespace you recognise, then let the bootstrap
# recreate everything in the fresh jtoye-local namespace
```

The fresh `jtoye-local` namespace is the recommended clean slate precisely because it cannot inherit
anything.

> **A1 was a GUARD GAP, not just housekeeping — measured live on 2026-07-25 (plan 26-07).** The stale
> namespace on this profile was not dormant. `jtoye-staging`, 11 days old, came back with **11 running
> pods** on the stale `:2.1.0` images and a `pg-backup` CronJob that fired on start, and those pods
> held **16 live connections to the shared dev Postgres as `jtoye_app`**.
>
> So `minikube start` alone silently re-created the two-writers-on-one-dev-Postgres hazard that §2
> exists to prevent — and the compose XOR guard could not see it, because
> `k8s_local_assert_compose_xor` inspects **compose only** and never the cluster it is about to start.
> The guard was asymmetric: it refused to start a cluster while compose was up, but it would happily
> start a cluster that already contained its own writers. Deleting the namespace dropped those
> connections **16 → 0**.
>
> **CLOSED — the gap is now covered by a guard, so this is no longer a manual duty.**
> `k8s_local_assert_cluster_xor` in `scripts/lib/k8s-local-guards.sh` runs as **step 3b** of
> `scripts/k8s-local-up.sh`: immediately after the profile start (the moment the hazard can open) and
> before the addon, the bootstrap and any apply. Behaviour, so you can predict it:
>
> | Situation | Result |
> |---|---|
> | Only `jtoye-local` + system namespaces hold live pods | **exit 0**, prints the live pod count it inventoried |
> | Any other namespace holds a live pod | **exit 1**, `REFUSED [cluster-writers-present]`, naming each namespace, its live pod count, the pod phases and the **image tags** |
> | `kubectl` missing, API server unreachable, response unparseable, or inventory **empty** | **exit 2** — the assertion is VOID, never treated as clean |
>
> Details that matter when you read its output:
>
> - It counts **pods**, not Deployment replicas — the 26-07 offender included a CronJob-spawned
>   `pg-backup` pod, and a Job, StatefulSet, DaemonSet or bare Pod holds a database connection just as
>   well as a Deployment's pod does.
> - "Live" means **not** `Succeeded`/`Failed`, so a `Pending` pod still pulling its image is caught too
>   — it is the same hazard a few seconds early. Completed pods (the `pg-backup-rehearsal` Job, the
>   ingress-admission Jobs) are correctly ignored.
> - Exempt namespaces are `jtoye-local` — read from `k8s/local/kustomization.yaml`, never duplicated
>   into a script literal — plus `kube-system`, `kube-public`, `kube-node-lease` and `ingress-nginx`. So
>   a **legitimate re-run passes**: your own running pods are not offenders (D-14).
> - **`default` is deliberately NOT exempt.** It is a writable namespace, not a system one: an
>   unqualified `kubectl run` or `kubectl apply` with no `-n` lands there, and that is exactly the
>   accidental-writer case this guard is for. Nothing in this tooling puts a workload there — step 4b's
>   webhook probe targets `default` under `--dry-run=server`, which creates nothing — so the strictness
>   costs you nothing on a clean cluster. Do not add it back to make a stray pod stop complaining;
>   delete the stray pod.
> - It **never deletes anything.** It prints the `get all` and `delete namespace` commands and stops;
>   deleting a namespace is your decision for exactly the reason stopping a compose container is.
>
> The manual `kubectl get ns,secrets -A` inventory above is still worth doing on a first start — a
> leftover **Secret** can mask a genuinely missing one, and the guard deliberately says nothing about
> Secrets. What it removes is the need to remember: a surviving `jtoye-*` namespace with live pods now
> **stops the run** instead of relying on you to notice it.

**A2 — `minikube profile list` says `OK`, not `Running`, and matching the wrong word restarted the whole
cluster on every re-run (found and fixed 2026-07-25, while proving the 3b guard above).** Step 3 decides
whether to call `minikube start` from `minikube profile list -o json`'s `.Status`. On minikube v1.36.0 a
fully-healthy profile reports **`OK`** there; `Running` is what `minikube status` says about
host/kubelet/apiserver, which is a different command. The check compared against `Running`, so its
"already Running (idempotent no-op)" branch was **dead** and every invocation called `minikube start` on
an already-running profile.

That is not inert on the docker driver. It reports `Updating the running docker "jtoye" container` and
bounces **every pod in the cluster**: measured, all three `jtoye-local` pods dropped to
`CreateContainerConfigError` on `failed to sync secret cache: timed out waiting for the condition` (a
warm-up condition, not a missing Secret — all 8 were present), took about two minutes to come back, and
restart counts went 3/2/2 → 4/3/3 with the ingress-nginx controller rolling 9 → 10. It self-heals, so it
looked like nothing — but it silently destroys the state of a rehearsal in progress, and it is a large
part of why step 4b's admission-webhook gate is load-bearing at all (the controller roll it absorbs was
being triggered by this, not only by `addons enable`).

The status match now accepts both spellings. It cannot mask a dead cluster: step 3b is the first step
that reads the live API server and it fails **closed**, so a profile that claims `OK` while actually
being down is caught there loudly rather than assumed healthy. **If you see the app pods bounce at the
start of a re-run, this is the first thing to check.**

**Expect 1–3 pod restarts on a first bring-up, and know which ones are benign (measured 2026-07-25,
plan 26-07).** After a successful rehearsal the pods sat at `1/1 Running` with restart counts 3
(core-java), 2 (edge-go), 2 (frontend), stable over a 60s observation. Two distinct causes, and only
one of them is interesting:

- **`java.net.UnknownHostException: host.minikube.internal` — fatal to core-java, self-healing.** The
  previous container exited **1** with that exception thrown from Flyway's
  `JdbcUtils.openConnection` via HikariCP. Pod DNS for the host gateway name is not always resolvable
  the instant a pod starts — CoreDNS and the host-gateway entry can still be settling, especially
  while the ingress addon is rolling or the node is busy loading images. Because it happens during
  Flyway migration, the Spring context fails and the JVM exits; the restart policy then brings it back
  and the second attempt connects. **Do not "fix" this by replacing the name with an IP** — §5 explains
  why the name is deliberate (the gateway IP varies by driver). Treat a *stable* restart count with a
  clean current boot log as success; treat a *climbing* count as a real fault.
- **Startup-probe `connection refused` on 9091, and HPA `FailedGetResourceMetric` — both benign.** The
  first is the startup probe polling the management port before Spring has bound it; that is what
  `startupProbe` `failureThreshold: 30` over `periodSeconds: 10` exists to absorb. The second is
  metrics-server being deliberately absent (§6), so every HPA logs
  `unable to fetch metrics from resource metrics API` on a loop. Neither indicates a problem, and
  neither should be "fixed" locally.

The check that actually matters after a restart is the current container's boot log, not the counter:
`is NOT a superuser` = 1, `DATABASE SECURITY VALIDATION PASSED` = 1, `... FAILED` = 0,
`Access refused for user` = 0.

**PIT-4 — the stale-image rule (anti-anecdote).** The `ghcr.io/bralabee/jtoye-*:2.1.0` images sitting
on this host were built on 2026-07-13/14 and therefore **predate Phases 23, 24 and 25**. Loading them
would produce READY pods rehearsing three-phase-old code against a current database. Step 7 rebuilds
all four with manifest-matching names and prints their identities, and §11 requires those identities
recorded next to every result. **A pass with no recorded image identity is not a pass** — it may be a
pass for code that is three phases stale.

**PIT-4b — the host image ID is NOT the in-cluster image ID; say which side you measured** (found by
plan 26-08, 2026-07-25). `minikube image load` re-imports the tarball, and the docker daemon inside the
node computes a **different** image ID for the same build. Measured on this cluster, same tag, same
build minute:

| tag | host docker ID | in-cluster ID | created |
|---|---|---|---|
| `jtoye-core-java:local` | `bba33e72…` | `f43a5e84…` | 20:09:50 UTC |
| `jtoye-edge-go:local`   | `e0e87717…` | `0644afc5…` | 2026-07-14 12:26:08 UTC |
| `jtoye-frontend:local`  | `3286c715…` | `def4382b…` | 20:10:19 UTC |
| `jtoye-pg-backup:15`    | `943a78f6…` | `1939105c…` | 20:10:44 UTC |

§11's run header records the **host** IDs (that is what step 7 prints after building). The IDs the
cluster actually runs are the right-hand column, which is what `kubectl get pod -o
jsonpath='{…containerStatuses[0].imageID}'` reports. They are reconciled by the **identical CreatedAt
minute**, which is the field that answers PIT-4. Nothing is wrong here — but a reader comparing a pod's
`imageID` against §11's header would find a mismatch and reasonably conclude the evidence was captured
against a different image, so record which side a digest came from.

**A3 — CONFIRMED PRODUCTION DEFECT: the STOMP relay rejects the KDS topic, because a RabbitMQ `/topic`
destination must be a SINGLE segment.**

*Found by plan 26-08 Task 3 (2026-07-25) on this local cluster; the finding and the "record now, fix in
its own scoped work" disposition were reviewed and **approved by the human at 26-08's verification
gate**, who independently confirmed the amber-dot symptom below in a real browser. This is **not** a
local caveat and **not** a "needs investigation" note: the mechanism is proven in both directions and
falsified two-arm against the live broker. It is unfixed here only because the fix is architectural —
see "What the fix must touch".*

**The constraint, stated plainly.** RabbitMQ's STOMP plugin maps `/topic/<name>` onto the `amq.topic`
exchange with `<name>` as the routing key. **`<name>` must be a single segment — it may not contain
`/`.** Any additional slash is rejected outright:

```
ERROR
message:Invalid destination
'/kitchen/00000000-0000-0000-0000-000000000001/97d95aa4-f6e8-4bb6-b9ad-525e49c61ef6' is not a valid topic destination
```

Spring's **in-memory** simple broker accepts arbitrary destination paths, so the same code is correct
there and invalid the moment it is relayed.

**Why every k8s environment is affected while development is not.**
`k8s/base/configmap.yaml:36` sets `stomp.broker.mode: "relay"`. Neither `k8s/staging/configmap-patch.yaml`
nor `k8s/production/configmap-patch.yaml` overrides it, so **staging and production both inherit the
broken path**. Meanwhile `docker-compose.full-stack.yml:215` passes
`STOMP_BROKER_MODE: ${STOMP_BROKER_MODE:-in-memory}` and `application.yml:224` reads
`mode: ${STOMP_BROKER_MODE:in-memory}` — so a normal compose run never enters the relay branch at all
(`WebSocketConfig.java:76`, `enableSimpleBroker`). That asymmetry is the entire reason this survived to
production undetected, and it is exactly what D-06 predicted when it insisted the relay be proven on the
cluster rather than in compose.

**Proven in BOTH directions, not just the subscriber.**

- *Subscribe side:* 14 browser SUBSCRIBEs, 14 `Invalid destination` ERRORs, **0 MESSAGE frames**.
- *Publish side:* the relay's own `_system_` session is rejected too, 43 ms after
  `OrderStateChangeListener` logged `CONFIRMED -> PREPARING`. The event never reaches the exchange.

**Raw-socket two-arm falsification** — same broker, same port, same credentials, read-only (a SUBSCRIBE
creates an auto-delete queue that vanishes on DISCONNECT; nothing was published):

| arm | destination | CONNECTED | SUBSCRIBE | ERROR |
|---|---|---|---|---|
| **A** (control) | `/topic/kitchen.<tenantId>.<shopId>` — dots, one segment | true | **ok (RECEIPT)** | none |
| **B** (the app's shape) | `/topic/kitchen/<tenantId>/<shopId>` — extra slashes | true | **rejected** | `Invalid destination` |

**Arm A is the load-bearing half.** Without it, `Invalid destination` could be misread as yet another
credential or connectivity problem. With it, the broker, the port, the STOMP login and the passcode are
all proven correct — **DEF-4 really is fixed** — and the fault is isolated to the destination *shape*
alone.

**THE OPERATOR-FACING TELL — read this before you "verify" the relay.**
**The kitchen board WILL appear to update live even though the relay is delivering nothing.** Every
rejected SUBSCRIBE tears the session down, `@stomp/stompjs` redials on `reconnectDelay: 5000`, and
`useStomp`'s `onReconnect` hook fires a full `fetchOrders()` on each redial. Measured over one 30-second
window: **14 WebSocket opens, 24 `/api/v1/orders…` requests, 0 MESSAGE frames.** The board moved
`Confirmed → Preparing` with zero page navigations. Accidental polling wearing realtime's clothes — and
by eye it is indistinguishable from a working relay. Anyone testing this later will otherwise "confirm"
a relay that is not relaying, which is precisely the false-green class this repository keeps catching.

*The honest signal is the connection dot beside the shop selector on `/dashboard/kitchen`*
(`frontend/app/dashboard/kitchen/page.tsx:376-386`):

| dot | `connectionLabel` | meaning |
|---|---|---|
| **green** (`bg-green-500`) | `Connected` | the subscription was accepted — the relay is working |
| **amber** (`bg-yellow-500`) | `Reconnecting...` | **the A3 symptom** — SUBSCRIBE is being rejected, in a redial loop |
| grey (`bg-gray-400`) | `Disconnected` | no session at all |

Observed here, and independently confirmed by the human at the 26-08 gate: **amber, never green.**
Judge the relay by the dot and the frame census, never by whether the board moves.

**What the fix must touch — all three, in one change.** This is why it is not a bolt-on:

1. `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java:109` — the publisher:
   `String topic = "/topic/kitchen/" + event.tenantId() + "/" + order.getShopId();`
2. `frontend/app/dashboard/kitchen/page.tsx:277` — the subscriber:
   `` ? `/topic/kitchen/${tenantId}/${selectedShopId}` `` (dialled by `frontend/hooks/use-stomp.ts`)
3. `core-java/src/main/java/uk/jtoye/core/websocket/TenantChannelInterceptor.java:123` — the
   **tenant-isolation** convention `/topic/{feature}/{tenantId}/{...}`, whose enforcement parses those
   very segments. Changing the delimiter changes what that parser sees, so a cross-tenant subscribe
   test must be **re-run, not assumed**.

A dot-delimited routing key (`/topic/kitchen.{tenantId}.{shopId}`) is the shape RabbitMQ accepts and it
preserves every segment; note `amq.topic` treats `.` as its wildcard separator, so a dotted UUID becomes
several routing-key words. The full suggested direction and a **must-fail-before-it-passes** four-part
acceptance test live in
`.planning/phases/26-local-k8s-overlay-verified-breakage-fixes/deferred-items.md`.

**DO NOT "fix" this by flipping `stomp.broker.mode` to `in-memory`.** It would make the symptom vanish
while making the system worse. `WebSocketConfig.java:76`'s simple broker is **per-JVM**, and
`k8s/base/core-java-deployment.yaml:10` sets `replicas: 3` — so an event published by one pod would
never reach a client connected to another. The relay exists for exactly that reason. Silencing the error
would trade a loud, diagnosable failure for a silent, replica-dependent one.

Full two-directional evidence, the frame census and the verbatim ERROR body are in §11, row **L6**.

---

## 8. Troubleshooting

**The kitchen display looks live but the connection dot is AMBER, and `core-java` logs
`Received ERROR {message=[Invalid destination]…}` every ~5 seconds**
This is **A3**, a confirmed production defect — not a local misconfiguration, and nothing here will fix
it. The relay is connected and authenticated; the *destination* `/topic/kitchen/{tenantId}/{shopId}` is
invalid because a RabbitMQ `/topic` destination may not contain `/`. **Do not conclude the relay works
because the board updates** — it updates from a reconnect-driven refetch, not from a relayed event.
Check the dot (green = working, amber = A3) and the frame census, and read §7 A3 before changing
anything. In particular, do **not** set `stomp.broker.mode: in-memory` to silence it.

**`admission webhook "validate.nginx.ingress.kubernetes.io" denied the request`**
The error text names the offending annotation. Cause: the ingress-nginx v1.12.2 snippet-annotation
policy described in §6. If it names `configuration-snippet`, the local ingress patch has lost its
`nginx.ingress.kubernetes.io/configuration-snippet: null` line — restore it. Do **not** relax
`allow-snippet-annotations` on the controller.

**`namespaces "jtoye-local" not found` on every object during a dry-run (PIT-8)**
A server-side dry-run does **not** create the Namespace it is validating, so every namespaced object
fails until the namespace exists. Correct order:

```bash
kubectl --context <ctx> apply -f k8s/local/namespace.yaml
kubectl --context <ctx> apply -k k8s/local --dry-run=server
```

**Pod stuck in `CreateContainerConfigError` (PIT-13)**
Two distinct cases, and telling them apart is the whole diagnosis:

1. **A non-optional `secretKeyRef` whose KEY is missing from an existing Secret.** This genuinely
   blocks container creation — the pod sits in `CreateContainerConfigError` until the key exists.
   `stomp-login` / `stomp-passcode` on `rabbitmq-credentials` are in this class: no `optional` flag,
   so an operator who skips them gets a pod that never becomes READY. `kubectl describe pod <name>`
   names the missing key.
2. **A `secretKeyRef` marked `optional: true`.** These do **not** block anything. Plan 26-02 marked
   seven refs optional (the media/SMTP/Stripe/notification credentials). With the Secret absent the
   env stays unset, `application.yml`'s own default applies, and the feature stays inert. If you are
   in `CreateContainerConfigError`, it is *not* one of these.

**A host backing service is unreachable from inside the cluster (PIT-11)**
Suspect a **host firewall on the minikube bridge before you touch a manifest.** minikube requires the
host service to listen on all interfaces (`0.0.0.0`); every compose port already publishes that way,
so the remaining risk is ufw/firewalld blocking the bridge subnet. Step 5 already probes this, and
you can repeat it by hand:

```bash
minikube ssh -p <profile> -- "nc -vz -w 3 host.minikube.internal 5433"
minikube ssh -p <profile> -- "nc -vz -w 3 host.minikube.internal 8085"
```

If the probe fails, do not start editing manifests.

**`apply` fails with `field is immutable`**
See §7, P-6. A selector label changed; delete and recreate the affected Deployments.

**`REFUSED [server-host-mismatch]`**
The configured context exists but its API server is not the minikube node. This is the guard doing
its job — on this host the alternative context is employer infrastructure. Do not "fix" it by
pointing the context elsewhere.

---

## 9. Backup rehearsal

Trigger the CronJob on demand rather than waiting for its schedule:

```bash
kubectl --context <ctx> -n jtoye-local create job pg-backup-manual --from=cronjob/pg-backup
kubectl --context <ctx> -n jtoye-local wait --for=condition=complete job/pg-backup-manual --timeout=10m
kubectl --context <ctx> -n jtoye-local get job pg-backup-manual -o jsonpath='{.status.succeeded}'
```

### Falsify the dump — do not confirm it

**A completed job and a verified dump are both compatible with a dump containing zero rows.**
`infra/backups/k8s-backup.sh` checks a `MIN_BACKUP_BYTES` floor (default **1000**) and runs
`pg_restore --list`. Sixty Flyway migrations of DDL comfortably exceed 1 KiB and list perfectly, so
**both checks pass on a schema-only, zero-row dump.** The tenant tables use FORCE ROW LEVEL SECURITY,
which applies RLS even to the table owner, so a `pg_dump` as the app role with no tenant GUC set
silently captures nothing at all.

Only a restore-and-count falsifies it, and it takes **two arms** — the counterexample is what makes
the positive result mean something:

| Arm | Dump taken as | Restored `SELECT count(*) FROM products` | Verdict |
|---|---|---|---|
| **A — the counterexample** | the APP role (`jtoye_app`, NOSUPERUSER, FORCE RLS, no tenant GUC) | must be `products = 0` | If this is non-zero, RLS is not actually enforcing and the whole isolation model is in question. |
| **B — the real backup** | the BYPASSRLS dump role (`jtoye_backup`) | must be `products > 0` | This is the only arm that proves the CronJob's dump is worth keeping. |

Run **both**. Arm B alone proves nothing about whether the floor and the listing were doing any work;
Arm A alone proves nothing about the backup. The restore commands are already written down and
proven — use them verbatim from `docs/runbooks/backups.md` § "Restore procedure (custom format)",
substituting the dump from each arm. Do not duplicate them here; they drift.

The BYPASSRLS role is created by `scripts/k8s-local-secrets.sh` (which invokes
`infra/backups/create-backup-role.sql` — the single definition of the role's privileges) and verified
from the database side via `rolbypassrls`. Nothing else provisions it: not compose, not Flyway,
because only a superuser can grant `BYPASSRLS`.

The backup bucket deliberately gets **no** public-read policy. The images bucket has one; database
dumps must not be world-readable.

---

## 10. Teardown

End where you started:

```bash
# 1. Stop the cluster. `stop` preserves etcd (see §7, A1); `delete` does not.
minikube stop -p <profile>

# 2. Bring the compose app containers back up.
docker compose -f docker-compose.full-stack.yml up -d core-java frontend edge-go mcp-server
```

The backing services were up the whole time and need no action. If you want a genuinely clean slate
next time — no inherited namespaces or Secrets — use `minikube delete -p <profile>` instead of `stop`,
at the cost of a full re-start and re-load. The `/etc/hosts` line is harmless to leave in place; the
node IP is stable across `stop`/`start` for an existing profile, but re-check it after a `delete`.

---

## 11. Rehearsal Evidence

Rows **L1–L5 are FILLED** (plan 26-07, 2026-07-25, behind its human-action approval — which is what
authorised the shared-state mutations).

Rows **L6–L7 are FILLED** by plan 26-08 (2026-07-25). **L7 (the DEF-5 ingress login) PASSED. L6 (the
functional STOMP relay) was FALSIFIED** — the relay is reachable and authenticated, and it *rejects* the
KDS topic because a RabbitMQ `/topic` destination cannot contain `/`. That is a production-affecting
defect (base sets `stomp.broker.mode: relay`), recorded as §7 **A3** and surfaced for a decision rather
than patched inside this plan: the fix spans the backend publisher, the frontend subscriber and the
tenant-isolation channel convention. A falsified row is a *stronger* result than an unproven one — it is
the outcome D-06 was written to obtain, and it is recorded as a failure rather than smoothed into a pass.

**L6/L7's human-verify gate is CLOSED — APPROVED, 2026-07-25.** The human ran the journey in a real
browser and reported two things, both recorded here as given:

1. **Login: PASSED.** Signed in at `http://app.jtoye.local` as `admin-user` and landed on a dashboard.
   L7 stands as PROVEN.
2. **Status dot: AMBER — it never went green.** That **corroborates** the A3 measurement rather than
   contradicting it, so no re-run was required. L6 stands as FALSIFIED on the 14 SUBSCRIBE / 14 ERROR /
   0 MESSAGE frame census plus the raw-socket two-arm proof.

**A3 disposition, decided at that gate: RECORD NOW, FIX IN ITS OWN SCOPED WORK.** The Rule 4 stop was
upheld — changing a tenant-isolation prefix parser earns its own plan and its own threat model rather
than a bolt-on to a closing plan. See §7 A3.

### Run header

```
date (UTC)        : 2026-07-25T20:38:38Z
git commit        : db7e87c
minikube profile  : jtoye                 node IP: 192.168.49.2
namespace         : jtoye-local
kubectl context   : jtoye
ingress hosts     : api.jtoye.local app.jtoye.local
api base          : http://api.jtoye.local
app base          : http://app.jtoye.local

IMAGE IDENTITIES (all four — mandatory, see §7 PIT-4)
  ghcr.io/bralabee/jtoye-core-java:local   id/digest: sha256:bba33e72393dfa7eb19c1fc0347eabba7efe269e1f0cfdf976718a884690b8bb
  ghcr.io/bralabee/jtoye-edge-go:local     id/digest: sha256:e0e87717034df51931f9b97ac3654d01d85f5fde731bd66a96a77d367bf8f55e
  ghcr.io/bralabee/jtoye-frontend:local    id/digest: sha256:3286c715cddb42de57d8cf67f8a0844bacd26a431529768c6d83f4bab03c2b9b
  ghcr.io/bralabee/jtoye-pg-backup:15      id/digest: sha256:943a78f678b00a799e213885c0c7f2dad5265aa5571de5928959318d23ea5429
```

All four were **built during this run** by step 7 (no `--skip-build` on the build pass), so PIT-4 is
satisfied: these are NOT the on-host `:2.1.0` tags, which were built 2026-07-13/14 and predate Phases
23, 24 and 25. For the record, the stale tags this run deliberately did not use were
`jtoye-core-java:2.1.0` (`935983d5cad2`, 2026-07-13), `jtoye-edge-go:2.1.0` (`498edb758282`,
2026-07-13), `jtoye-frontend:2.1.0` (`74a3cf917e8e`, 2026-07-14) and `jtoye-pg-backup:15`
(`303d1511b2fc`, 2026-07-10 — rebuilt here to `943a78f6…`).

### Pre-apply cluster state (A1) — and why it mattered more than expected

A **Stopped** profile preserves etcd, and this one had a live namespace in it. The inventory below was
taken immediately after the profile start and **before any apply**:

```
$ kubectl --context jtoye get ns
NAME              STATUS   AGE
default           Active   11d
ingress-nginx     Active   2m53s
jtoye-staging     Active   11d          <-- the 2026-07-14 rehearsal
kube-node-lease   Active   11d
kube-public       Active   11d
kube-system       Active   11d
```

`jtoye-staging` was not merely holding maskable Secrets — it was **running**: 11 pods on the stale
`:2.1.0` images (core-java 3/3, edge-go 5/5, frontend 3/3) plus a `pg-backup` CronJob that fired the
moment the profile started and failed `BackoffLimitExceeded`. Those pods held **16 live connections to
the shared dev Postgres as `jtoye_app`**:

```
$ SELECT usename, client_addr, count(*) ... FROM pg_stat_activity WHERE datname='jtoye' ...
jtoye_app <- 172.18.0.1/32  (16 conns)
```

So starting the profile silently re-created the two-writers-on-one-dev-Postgres hazard that stopping
the compose apps exists to prevent. Per the standing decision the namespace was **deleted** (46
objects: 3 Deployments, 3 Services, 3 HPAs, 3 PDBs, 6 NetworkPolicies, 2 Ingresses, 1 CronJob, 1 Job,
7 ReplicaSets, 11 Pods, 6 Secrets), after which:

```
$ SELECT count(*) FROM pg_stat_activity WHERE datname='jtoye' AND client_addr IS NOT NULL
0
```

That zero is load-bearing: it is the baseline that makes L2's connection attribution exact rather than
inferential. **This is a guard gap, not just a housekeeping note** — see §7, A1.

`scripts/k8s-local-up.sh` step 12 prints this block with the real values already substituted — paste
it, do not retype it.

### The evidence-invalidating rule

**If `localhost:9090` (or any other loopback address) appears anywhere in the captured output, the run
does not count.** A loopback API base means the compose app containers were up and the compose XOR
k8s guard was bypassed — so whatever answered was the compose stack, not the cluster, and the ingress
path was never exercised. Discard the run, stop the app containers, start again. The same applies to
a run whose image identities are blank: it proves nothing about which code was deployed.

### Live rows

Seven rows, one per **live** entry in the phase validation contract.

### Bootstrap proofs (first whole-script `scripts/k8s-local-secrets.sh` execution in the phase)

Plan 26-05 authored the bootstrap but was forbidden to invoke it; this run is its first whole-script
execution, authorised by Task 1's approval items (c) and (d).

**The compose-XOR guard's PROCEED arm** — the half 26-05 could only take as far as refusal:

```
$ bash -c 'source scripts/lib/k8s-local-guards.sh; k8s_local_load_env; k8s_local_assert_compose_xor'
OK: env loaded from /home/sanmi/IdeaProjects/JToye_OaaS_2026/.env; all 13 K8S_LOCAL_* keys present
OK: compose XOR k8s satisfied — all app services down, all backing services up
xor exit=0
```

26-05's recorded refuse arms (`REFUSED [compose-apps-running]` naming core-java/frontend/edge-go/
mcp-server, and `REFUSED [compose-backing-down]` naming redis) are **not re-observed here on purpose**:
after the approval the apps are down, and restarting them mid-rehearsal would violate the XOR rule.
Refusals are 26-05's evidence; this proceed arm is 26-07's. D-04 is now proven in both directions
against real state.

**The BYPASSRLS dump role (D-02)** — bypass without superuser, exactly what `create-backup-role.sql`
intends:

```
$ docker exec jtoye-postgres psql -U jtoye -d jtoye -tAc \
    "SELECT rolbypassrls, rolsuper FROM pg_roles WHERE rolname='jtoye_backup'"
t|f
```

**The backup bucket — EXISTENCE asserted FIRST, because a 403 alone proves nothing.** MinIO returns 403
for a bucket that does not exist just as it does for a private one (tested live before creation), so a
bare 403 would be satisfied by a bucket that was never created:

```
$ docker exec jtoye-minio ls /data
jtoye-db-backups
jtoye-images
$ docker exec jtoye-minio ls /data | grep -c '^jtoye-db-backups$'
1
```

Only now is the 403 meaningful, against the deliberately-public images bucket as the control:

```
$ curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9000/jtoye-db-backups/
403
$ curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9000/jtoye-images/
200
```

**Secret inventory** (8 created, 2 skipped by design):

```
$ kubectl --context jtoye -n jtoye-local get secrets -o name | sort
secret/keycloak-credentials
secret/nextauth-secret
secret/notification-credentials
secret/postgres-credentials
secret/rabbitmq-credentials
secret/redis-credentials
secret/s3-backup-credentials
secret/s3-media-credentials
```

`stripe-credentials` skipped — `STRIPE_API_KEY` is empty and the manifest ref is `optional: true`, so
payments stay inert. `smtp-credentials` absent **by design** — Mailhog takes no auth and plan 26-02
made that ref optional.

**DEF-2 at the live secret** — the app role, never the superuser:

```
$ kubectl --context jtoye -n jtoye-local get secret postgres-credentials \
    -o jsonpath='{.data.username}' | base64 -d
jtoye_app
```

(`.env` `DB_USER=jtoye_app`; the superuser `POSTGRES_USER=jtoye` was NOT injected.) The same Secret's
non-secret keys decode to `host=host.minikube.internal`, `port=5433`, `backup-username=jtoye_backup`.

**Idempotence (D-01)** — a second standalone run is a clean no-op:

```
$ bash scripts/k8s-local-secrets.sh ; echo exit=$?
...
PASS: local bootstrap complete and safe to re-run.
exit=0
```
Conflict / resourceVersion-churn errors in that output: **0**. `bash
k8s/scripts/check-no-plaintext-secrets.sh` still exits **0** afterwards — nothing the bootstrap created
became a kustomize resource.

**No secret value in the output.** Every high-entropy secret was checked as both its literal and its
base64 form, all **0**: `DB_BACKUP_PASSWORD` (64 chars) 0/0, `NEXTAUTH_SECRET` (44) 0/0,
`KEYCLOAK_CLIENT_SECRET` (64) 0/0, `MINIO_ROOT_PASSWORD` (64) 0/0. A naive
`grep -cF "$DB_PASSWORD"` returns **9**, but that is unsatisfiable-by-vocabulary on this host rather
than a leak: this dev `.env` sets `DB_PASSWORD` to the English word `secret` (6 chars; SHA-256 prefix
`2bb80d537b1d` == `printf 'secret' | sha256sum`), and all 9 hits are the script's own key-**name**
summary lines (`nextauth-secret`, `frontend-client-secret`, `secret-key`, `secrets created`). No value
was ever printed to diagnose this.

### Live rows

**L1 — INFRA-01: every reference resolves against a real API server** *(owner: 26-07)*

```
Command : kubectl --context <ctx> apply -f k8s/local/namespace.yaml
          kubectl --context <ctx> apply -k k8s/local --dry-run=server
Expected: exit 0, one "… (server dry run)" line per object, no "not found" and no
          admission-webhook denial. Capture the output VERBATIM — a dry-run that
          silently skipped a webhook and one that genuinely passed share an exit code.
Actual  : exit 0. VERBATIM, 23 objects, at git db7e87c:

--- server-side dry-run (VERBATIM) ---
namespace/jtoye-local unchanged (server dry run)
configmap/app-config unchanged (server dry run)
service/core-java unchanged (server dry run)
service/edge-go unchanged (server dry run)
service/frontend unchanged (server dry run)
deployment.apps/core-java configured (server dry run)
deployment.apps/edge-go unchanged (server dry run)
deployment.apps/frontend unchanged (server dry run)
cronjob.batch/pg-backup unchanged (server dry run)
poddisruptionbudget.policy/core-java-pdb configured (server dry run)
poddisruptionbudget.policy/edge-go-pdb configured (server dry run)
poddisruptionbudget.policy/frontend-pdb configured (server dry run)
horizontalpodautoscaler.autoscaling/core-java-hpa unchanged (server dry run)
horizontalpodautoscaler.autoscaling/edge-go-hpa unchanged (server dry run)
horizontalpodautoscaler.autoscaling/frontend-hpa unchanged (server dry run)
ingress.networking.k8s.io/jtoye-ingress unchanged (server dry run)
ingress.networking.k8s.io/jtoye-sse-ingress unchanged (server dry run)
networkpolicy.networking.k8s.io/core-java-allow unchanged (server dry run)
networkpolicy.networking.k8s.io/default-deny unchanged (server dry run)
networkpolicy.networking.k8s.io/edge-go-allow unchanged (server dry run)
networkpolicy.networking.k8s.io/frontend-allow unchanged (server dry run)
networkpolicy.networking.k8s.io/observability-placeholder unchanged (server dry run)
networkpolicy.networking.k8s.io/pg-backup-allow unchanged (server dry run)
--- end server-side dry-run ---

          The same 23 objects against the EMPTY namespace on the first apply read
          `created (server dry run)` for all 22 namespaced objects (namespace itself
          `unchanged`). BOTH Ingress objects are present in the output, and
          `grep -c 'denied the request'` is **0 across all eight captured run logs** —
          so PIT-1's annotation nulling has not regressed.

          NOT-SILENTLY-SKIPPED, and this is the row's real content: on two earlier
          attempts this dry-run FAILED both Ingresses with
          `failed calling webhook "validate.nginx.ingress.kubernetes.io": ... dial tcp
          10.108.175.67:443: connect: no route to host`. That is the webhook being
          UNREACHABLE, not an admission denial — a distinction exit codes alone cannot
          make, which is exactly why rule 6 demands verbatim capture. Cause: step 4's
          `minikube addons enable ingress` rolls the controller on every run
          (restartCount 5 -> 6, pod IP 10.244.0.51 -> .52) while the admission Service
          keeps its ClusterIP. Fixed by the new step 4b reachability gate; it answered
          on attempt 2 and attempt 5 on the two subsequent runs, i.e. it is genuinely
          load-bearing and the delay is variable.
```

**L1b — INFRA-01: the live apply, rollout and object set** *(owner: 26-07)*

```
Command : kubectl --context <ctx> apply -k k8s/local ; rollout status x3 ; get pods,svc,ingress,hpa,pdb,cronjob
Expected: 3 Deployments READY 1/1 on a real cluster consuming the compose-hosted backing services
Actual  : deployment "core-java" successfully rolled out
          deployment "frontend"  successfully rolled out
          deployment "edge-go"   successfully rolled out

NAME                            READY   STATUS    RESTARTS        AGE
pod/core-java-88b85df6f-x7vxn   1/1     Running   1               5m6s
pod/edge-go-6d67497b97-4mgvq    1/1     Running   1 (3m24s ago)   5m6s
pod/frontend-74d864f789-cvrq6   1/1     Running   1               5m6s

NAME                TYPE        CLUSTER-IP       EXTERNAL-IP   PORT(S)    AGE
service/core-java   ClusterIP   10.105.90.125    <none>        9090/TCP   5m7s
service/edge-go     ClusterIP   10.100.84.127    <none>        8080/TCP   5m7s
service/frontend    ClusterIP   10.103.140.207   <none>        3000/TCP   5m7s

NAME                                          CLASS   HOSTS                             ADDRESS        PORTS   AGE
ingress.networking.k8s.io/jtoye-ingress       nginx   api.jtoye.local,app.jtoye.local   192.168.49.2   80      5m5s
ingress.networking.k8s.io/jtoye-sse-ingress   nginx   api.jtoye.local                   192.168.49.2   80      5m4s

NAME                                                REFERENCE              TARGETS                                     MINPODS   MAXPODS   REPLICAS   AGE
horizontalpodautoscaler.autoscaling/core-java-hpa   Deployment/core-java   cpu: <unknown>/70%                          1         10        1          5m5s
horizontalpodautoscaler.autoscaling/edge-go-hpa     Deployment/edge-go     cpu: <unknown>/60%, memory: <unknown>/70%    1         20        1          5m5s
horizontalpodautoscaler.autoscaling/frontend-hpa    Deployment/frontend    cpu: <unknown>/70%                          1         10        1          5m5s

NAME                                       MIN AVAILABLE   MAX UNAVAILABLE   ALLOWED DISRUPTIONS   AGE
poddisruptionbudget.policy/core-java-pdb   1               N/A               0                     5m6s
poddisruptionbudget.policy/edge-go-pdb     1               N/A               0                     5m5s
poddisruptionbudget.policy/frontend-pdb    1               N/A               0                     5m5s

NAME                      SCHEDULE    TIMEZONE   SUSPEND   ACTIVE   LAST SCHEDULE   AGE
cronjob.batch/pg-backup   0 2 * * *   <none>     False     0        <none>          5m6s

          RESTARTS is 1 in the listing above and reached 3/2/2 by the end of the session,
          stable over a 60s observation with a clean current boot log. Cause recorded in
          §7: the previous core-java container exited 1 on
          `UnknownHostException: host.minikube.internal` thrown from Flyway at startup —
          pod DNS for the host-gateway name had not settled. It self-heals via the restart
          policy. Not papered over, and NOT worked around by replacing the name with an IP.

          HPA `TARGETS` reading `<unknown>` is EXPECTED, not a fault: metrics-server is
          deliberately not enabled (§6), so the HPAs sit inert. PDB
          `ALLOWED DISRUPTIONS 0` with minAvailable 1 over 1 replica is likewise the
          documented D-09 consequence (§5), not a regression. `maxReplicas` remains
          10/20/10, byte-identical to base.
```

**L1c — INFRA-01: ingress smoke through the hostnames** *(owner: 26-07)*

```
Command : curl http://api.jtoye.local/health , /public/shops , http://app.jtoye.local/api/health
Expected: 200 on all three, no loopback address anywhere
Actual  : api /health       -> 200  body: OK
          api /public/shops -> 200  body: {"content":[{"slug":"brixton-village-grill",
                                     "name":"Brixton Village Grill","description":"Flame-grilled
                                     peri peri chicken, kebabs and loaded sides.","address":
                                     "Unit 74, Brixton Village Market, London …
          app /api/health   -> 200  body: {"status":"ok"}
          Response line: HTTP/1.1 200 (via the ingress-nginx controller at 192.168.49.2).

          `/public/shops` returning REAL seeded rows — not an empty `content: []` — is
          the point: an empty catalogue would have been a green-looking regression.
```

**L1d — INFRA-01 / D-14: the `--dry-run-only` flag contract and re-runnability** *(owner: 26-07)*

```
Command : bash scripts/k8s-local-up.sh --dry-run-only --skip-build
Expected: exit 0, verbatim dry-run reached, NO rollout started
Actual  : exit 0
          reached the verbatim dry-run          : 1
          "stopping before the real apply"      : 1
          STEP 10 (rollout) present in output   : 0
          STEP 11 (smoke)  present in output    : 0
          deploy/core-java .metadata.generation : 1 -> 1  (unchanged)
          replicaset count                      : 3 -> 3  (unchanged)
          bash scripts/k8s-local-up.sh --nope   : exit 2 (flags parsed before any tool call)

          The whole entry point was additionally re-run from an already-Running profile
          (step 3 reporting `profile jtoye already Running (idempotent no-op)`), which is
          anti-anecdote rule 1's re-runnability requirement met against real state.
```

**L2 — INFRA-02b: core-java boots as a NOSUPERUSER role** *(owner: 26-07)*

```
Command : kubectl --context <ctx> -n jtoye-local logs deploy/core-java | grep -c "is NOT a superuser"
          plus the DB-side truth: SELECT current_user, usesuper  under the pod's connection identity
Expected: log count >= 1, AND the DB reports usesuper = false. Both arms — the log
          alone is the app's own claim about itself.
Actual  : ARM 1 — the app's own validator, asserted as COUNTS:
            grep -c "is NOT a superuser"                  = 1   (>= 1 OK)
            grep -c "DATABASE SECURITY VALIDATION PASSED" = 1   (>= 1 OK)
            grep -c "DATABASE SECURITY VALIDATION FAILED" = 0   (0 OK)

          Verbatim, from the running pod:
            "message":"DATABASE SECURITY CONFIGURATION CHECK"
            "message":"Database username: jtoye_app"
            "message":"Checking if database user is a superuser..."
            "message":"✅ User 'jtoye_app' is NOT a superuser (RLS will be enforced)"
            "message":"✅ DATABASE SECURITY VALIDATION PASSED"
          (timestamps 2026-07-25 20:38:12.128–.169, logger
           u.j.c.c.DatabaseConfigurationValidator, thread main)

          ARM 2 — the DB side, independent of anything the app says about itself:
            SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname='jtoye_app'
            -> f|f

          ARM 2b — that the live connections are THIS CLUSTER'S, proven three ways
          rather than by guessing at a subnet:
            SELECT usename, client_addr, count(*) FROM pg_stat_activity ...
            -> jtoye_app | 172.18.0.1/32 | 5 conns

            (i)   BASELINE. 16 conns (stale jtoye-staging) -> 0 (namespace deleted)
                  -> 5 (this rollout). The zero in the middle is the control.
            (ii)  ELIMINATION. All four compose app services report `exited`
                  (core-java, frontend, edge-go, mcp-server), so compose cannot be
                  the source.
            (iii) CORRELATION. All 5 backends share application_name
                  "PostgreSQL JDBC Driver" and backend_start 2026-07-25 20:37:48 — one
                  simultaneous HikariCP pool init — against pod
                  core-java-88b85df6f-x7vxn startedAt 2026-07-25T20:37:36Z.

          NOTE, a correction to the expected form: `client_addr` is 172.18.0.1, which
          is NOT on the minikube bridge subnet, and no correct run could make it so.
          Traffic is double-NAT'd — pod 10.244.0.x -> host.minikube.internal
          (minikube bridge gw 192.168.49.1) -> published host port 5433 ->
          docker-proxy -> the postgres container, which sees the COMPOSE bridge
          gateway 172.18.0.1 (network jtoye_oaas_2026_jtoye-network). A
          "client_addr is on the minikube subnet" assertion would fail on a perfectly
          healthy run, so the three proofs above replace it.
```

**L2b — INFRA-02a / DEF-1: the pod used the SECRET-supplied port, not a hardcoded 5432** *(owner: 26-07)*

```
Command : kubectl --context <ctx> -n jtoye-local get deploy/core-java \
            -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="DB_PORT")]}'
Expected: a secretKeyRef and NO `value` field; the decoded secret port quoted
Actual  : {"name":"DB_PORT","valueFrom":{"secretKeyRef":{"key":"port","name":"postgres-credentials"}}}
            secretKeyRef present   : 1
            "value" field present  : 0
            decoded secret `port`  : 5433

          The pod is genuinely CONNECTED (L2 arm 2b, 5 live backends), and the host
          Postgres is reachable only on the published port 5433 — so a live connection
          is itself the proof that the secret's port was the one used. Had the deleted
          `value: "5432"` still been in play, nothing would have connected at all.
```

**L2c — PIT-5: the logback file-appender error is absent** *(owner: 26-07)*

```
Command : kubectl --context <ctx> -n jtoye-local logs deploy/core-java | grep -ci 'FileNotFoundException.*jtoye'
Expected: 0, because the local overlay sets log.path: /tmp
Actual  : 0
          LOG_PATH resolves via configMapKeyRef app-config/log.path
          rendered app-config log.path = /tmp
          (The base default /var/log/jtoye is UNCHANGED; the durable fix is still the
           deferred emptyDir. This row only says the local overlay works.)
```

**L3 — INFRA-02c: the pg-backup CronJob completes in-cluster and uploads** *(owner: 26-07)*

```
Command : kubectl --context <ctx> -n jtoye-local create job pg-backup-manual --from=cronjob/pg-backup
          kubectl --context <ctx> -n jtoye-local get job pg-backup-manual -o jsonpath='{.status.succeeded}'
Expected: .status.succeeded == 1, and the dump object present in the local backup bucket.
Actual  : job name used: pg-backup-rehearsal
          kubectl wait --for=condition=complete -> "condition met", exit 0
          .status.succeeded = 1

          FULL JOB LOG (verbatim):
[2026-07-25T20:48:29Z] Starting backup of jtoye on host.minikube.internal:5433 as jtoye_backup (BYPASSRLS expected)
[2026-07-25T20:48:30Z] Dump verified: 214370 bytes, archive readable
[2026-07-25T20:48:31Z] Uploading to s3://jtoye-db-backups/backups/jtoye-backup-20260725-204829.dump
Completed 209.3 KiB/209.3 KiB (1.7 MiB/s) with 1 file(s) remainingupload: tmp/jtoye-backup-20260725-204829.dump to s3://jtoye-db-backups/backups/jtoye-backup-20260725-204829.dump
[2026-07-25T20:48:31Z] Pruning objects older than 20260625 (30d retention)
[2026-07-25T20:48:31Z] Pruned 0 old backup(s)
[2026-07-25T20:48:31Z] Backup complete: jtoye-backup-20260725-204829.dump

          It dumped as jtoye_backup (the BYPASSRLS role) against
          host.minikube.internal:5433 — i.e. the in-cluster job reached the compose
          Postgres over the pod host on the secret-supplied port, and the S3 target was
          the overlay's s3.backup.endpoint http://host.minikube.internal:9000.

          OBJECT — existence established FIRST, then the privacy probe:
            $ mc ls --recursive b/jtoye-db-backups
            [2026-07-25 20:48:31 UTC] 209KiB STANDARD backups/jtoye-backup-20260725-204829.dump
            key  : backups/jtoye-backup-20260725-204829.dump
            size : 214370 bytes  (MIN_BACKUP_BYTES floor is 1000 — cleared by 214x)

            unauthenticated GET that key                      -> 403
            unauthenticated GET a known jtoye-images object    -> 200   (control)
              control key: 00000000-…-000000000001/media/71c253f8-…-e81c7e2e4246.webp
              (that object's existence was confirmed by mc first, so the 200 is real)

          Ordering is load-bearing and was followed: the key came from the job log and
          was confirmed in the bucket listing BEFORE the 403 was interpreted. MinIO
          returns 403 for a nonexistent bucket or key too, so an unordered probe would
          be satisfiable by absence. Because this key is read from the job log, this
          object-level probe is the STRONGER of the two privacy proofs.

          NOTE: the size floor does not distinguish anything about CONTENT — see L4,
          where a zero-row dump clears it by 149x.
```

**L4 — INFRA-02c: the dump is NON-EMPTY, both arms** *(owner: 26-07)*

```
Command : §9 arm A (app-role dump) and arm B (jtoye_backup dump), each restored per
          docs/runbooks/backups.md § "Restore procedure (custom format)"
Expected: arm A -> products = 0 ; arm B -> products > 0. Record BOTH numbers.
          A missing arm A makes arm B unfalsifiable.
Actual  : arm A = products 0        arm B = products 47

          ARM A — the counterexample. pg_dump as jtoye_app (NOSUPERUSER, subject to
          FORCE RLS, no app.current_tenant_id GUC), restored into jtoye_restore_armA:
            products=0  orders=0  customers=0  shops=0

          AND THIS IS THE POINT — both of the pipeline's own verifications PASS on that
          zero-row artifact:
            1. MIN_BACKUP_BYTES floor (default 1000): artifact is 149268 bytes
               -> PASSES, by 149x
            2. pg_restore --list: PASSES, archive structurally readable,
               393 TOC entries
          So a dump containing no rows at all satisfies every automated content check
          in infra/backups/k8s-backup.sh. Only the restore-and-count separates the arms.

          ARM B — the real backup. The object the CronJob actually uploaded, downloaded
          with the runbook's aws-cli --endpoint-url procedure and restored into
          jtoye_restore_armB:
            downloaded 214370 bytes (byte-identical to the size the job log reported)
            pg_restore rc=0, 0 errors, RTO 9s
            products=47  orders=23  customers=12  shops=5

          CROSS-CHECK against the live database read through the BYPASSRLS role:
            products=47  customers=12  orders=23  shops=5   — an exact match, so the
            dump captured the full tenant data rather than a subset.

          MECHANISM, recorded precisely because it differs from the runbook's wording.
          The runbook says an app-role dump "silently captures ZERO rows". Measured
          here on PostgreSQL 15 with 36 tables ENABLE RLS, all 36 FORCE:
            - a plain SELECT as jtoye_app with no GUC returns 0 rows, SILENTLY
              (products=0, customers=0) — the trap is real and is what the restore shows;
            - pg_dump additionally requests row_security=off, which Postgres REFUSES for
              a non-BYPASSRLS role on a FORCE-RLS table, so pg_dump itself EXITS 1 with
              `ERROR: query would be affected by row-level security policy for table
              "customers"`.
          So pg_dump has a second safety net the runbook does not claim: it fails loudly
          rather than silently. That does NOT retire the BYPASSRLS role, and it does not
          make arm A redundant — the partial artifact left behind still clears the size
          floor and the TOC check while restoring to zero rows, which is exactly the
          false-green this evidence exists to exclude. k8s-backup.sh's explicit rc check
          plus its `rm -f "$TMP"` on failure are what stop that artifact being uploaded;
          both are load-bearing and neither is implied by the two content checks.

          Both scratch databases were DROPPED afterwards; `SELECT count(*) FROM
          pg_database WHERE datname LIKE 'jtoye_restore%'` returns 0, and the database
          list is back to the preflight set (jtoye, keycloak, postgres).

          The 2026-07-10 runbook reference figures were products=25, orders=57,
          customers=4, shops=10; the dev DB has since moved to 47/23/12/5. Same order of
          magnitude, which is all that figure was ever for.
```

**L5 — INFRA-02d: no boot-time broker rejection** *(owner: 26-07)*

```
Command : kubectl --context <ctx> -n jtoye-local logs deploy/core-java | grep -c "Access refused for user"
Expected: exactly 0. Assert the COUNT, not the absence of a line you looked for —
          a missing log line and an absent grep hit look identical otherwise.
Actual  : grep -c "Access refused for user"  = 0
          grep -ciE "AuthenticationFailureException|ACCESS_REFUSED|
                     com.rabbitmq.client.AuthenticationFailure" = 0
          grep -c "DATABASE SECURITY VALIDATION FAILED" = 0

          Both recorded as COUNTS. Note the boundary honestly: this row proves only
          that the broker did NOT reject the credentials at boot. It does NOT prove a
          message traversed the relay — the functional STOMP relay proof is L6, and it
          is plan 26-08's.
```

### Broker-side STOMP identity (plan 26-08, 2026-07-25) — and why `list_connections` cannot show it

L5 above is a *negative*: no credential rejection. The positive that DEF-4 actually needs is **which
principal the relay authenticated as**, asserted at the broker rather than inferred from a quiet log.
That distinction is not academic: a relay that never connected at all also produces
`Access refused for user` = 0.

**`rabbitmqctl list_connections` structurally cannot answer this on RabbitMQ 3.12, so the obvious form
of the assertion is unsatisfiable.** Measured, not assumed — `list_connections` lists **AMQP** readers
only, and the sole row it returns is the Spring `CachingConnectionFactory` pool:

```
$ docker exec jtoye-rabbitmq rabbitmqctl list_connections user peer_host peer_port protocol
Listing connections ...
user	peer_host	peer_port	protocol
jtoye	172.18.0.1	48468	{0,9,1}
```

`{0,9,1}` is AMQP 0-9-1. Piping that through `grep -ci stomp` returns **0** no matter how healthy the
relay is, so an assertion of the form "`list_connections … | grep -ci stomp` >= 1" can never pass and
would have been recorded as a DEF-4 failure against a working relay. `rabbitmqctl
list_stomp_connections` also **rejects** the `user` info key outright
(`Error (argument validation): Info key(s) user are not supported`). The identity column it does expose
is **`auth_login`**.

**Two independent broker-side views, both naming the dedicated login.** The plugin's own CLI:

```
$ docker exec jtoye-rabbitmq rabbitmqctl list_stomp_connections conn_name auth_login peer_host peer_port protocol
Listing STOMP connections ...
conn_name	auth_login	peer_host	peer_port	protocol
172.18.0.1:54520 -> 172.18.0.14:61613	jtoye	172.18.0.1	54520	{'STOMP', 0}
```

and the management API, which unlike `list_connections` does carry non-AMQP protocols:

```
$ curl -s -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" http://localhost:15672/api/connections \
    | jq '[.[]|select(.protocol|startswith("STOMP"))]|.[]'
{
  "name": "172.18.0.1:54520 -> 172.18.0.14:61613",
  "user": "jtoye",
  "protocol": "STOMP 1.2",
  "peer_host": "172.18.0.1",
  "peer_port": 54520,
  "connected_at": 1785013837263
}
```

Counts, so every claim is a number rather than an impression:

```
total broker connections                       2   (1 AMQP + 1 STOMP)
STOMP connections                              1
STOMP rows with user == guest                  0
STOMP rows with user == jtoye                  1
auth_login == guest   (CLI view)               0
auth_login == jtoye   (CLI view)               1
NON-VACUITY: rows matching a protocol nothing  0   (startswith("MQTT") — proves the
             on this broker uses                   protocol filter selects, not passes-all)
PREDICATE CAN FIRE: the identical guest        1   (applied to a synthetic fixture holding a
             predicate on a fixture                guest STOMP row — so 0 on live data is a
                                                   real negative, not a broken jq path)
```

`jtoye` is the value of `rabbitmq-credentials/stomp-login`, i.e. the dedicated STOMP login the
deployment injects as `STOMP_CLIENT_LOGIN` (`k8s/base/core-java-deployment.yaml:226-235`). **No
passcode value appears anywhere in this document** — the login NAME is the only credential material
recorded, and that is asserted below in the Sign-off.

**`peer_host` is `172.18.0.1`, and that is the healthy answer — not a compose leftover.** The same
double-NAT that 26-07 measured for Postgres applies here: pod `10.244.0.x` →
`host.minikube.internal` (minikube gateway `192.168.49.1`) → the published host port 61613 →
docker-proxy → the rabbitmq container, which sees the **compose** bridge gateway. So "peer_host is on
the minikube bridge subnet" cannot hold on a healthy run, and attribution is established the way
26-07 established it for L2 — by elimination and correlation:

- **Elimination.** All four compose app services are `exited` at the time of capture
  (`core-java exited`, `frontend exited`, `edge-go exited`, `mcp-server exited`; the six backing
  services `running`), so no compose process could be holding a STOMP connection. Nothing else on this
  host speaks STOMP.
- **Correlation, to the millisecond.** The broker's `connected_at` is `1785013837263` =
  `2026-07-25 21:10:37.263`. The pod's own relay lifecycle, verbatim:

```
2026-07-25 21:10:32.291  u.j.core.websocket.WebSocketConfig          STOMP broker relay configured: host.minikube.internal:61613
2026-07-25 21:10:36.865  o.s.m.s.s.StompBrokerRelayMessageHandler    Starting...
2026-07-25 21:10:36.875  o.s.m.s.s.StompBrokerRelayMessageHandler    Starting "system" session, StompBrokerRelay[ReactorNettyTcpClient[...]]
2026-07-25 21:10:36.977  o.s.m.s.s.StompBrokerRelayMessageHandler    Started.
2026-07-25 21:10:37.267  o.s.m.s.s.StompBrokerRelayMessageHandler    "System" session connected.
2026-07-25 21:10:37.269  o.s.m.s.s.StompBrokerRelayMessageHandler    BrokerAvailabilityEvent[available=true, ...]
```

  Broker `21:10:37.263` vs pod `21:10:37.267` — a **4 ms** delta, inside the current container's
  lifetime (`startedAt 2026-07-25T21:10:05Z`). `BrokerAvailabilityEvent[available=true]` is Spring's
  own confirmation that the relay is usable, not merely dialled.

**Pod-side resolved values** (non-secret names only, passcode never read):

```
$ kubectl --context jtoye -n jtoye-local exec deploy/core-java -- printenv \
    STOMP_BROKER_MODE STOMP_RELAY_HOST STOMP_RELAY_PORT STOMP_CLIENT_LOGIN RABBITMQ_USER
relay
host.minikube.internal
61613
jtoye
jtoye
```

`STOMP_BROKER_MODE=relay` matters on its own: dev compose defaults to `in-memory`
(`application.yml:222-224`), so this code path is exercised **only** on this cluster (D-06).
Re-asserted on the CURRENT pod after Task 1's frontend re-apply:
`grep -c "Access refused for user"` = **0**, `grep -c 'In-memory simple broker'` = **0**,
`grep -c 'STOMP broker relay configured'` = **1**, restart count **4** and stable (§7 A2 explains why
it is 4 rather than 26-07's 3 — an idempotence defect in `scripts/k8s-local-up.sh` step 3 bounced the
pods once between the two plans; a stable count with a clean current boot log is success).

**Why `frontend/e2e/stomp-relay.spec.ts` is NOT the ingress-path proof.** Four structural reasons, each
verified against the committed file, recorded so the next reader does not re-derive them — and so a
green-looking run of that spec is never mistaken for D-06:

1. **It authenticates with a stub cookie.** `authjs.session-token: "e2e-stub"` at
   `frontend/e2e/stomp-relay.spec.ts:61-63` and again at `:149-151`. `/dashboard/kitchen` gates
   server-side: `frontend/app/dashboard/layout.tsx:19` calls `await auth()` and redirects on no
   session, and a fabricated token is not a session. The spec would land on `/auth/signin`.
2. **It posts orders to edge-go, which the local ingress does not route.** `:29` reads
   `EDGE_URL` with a loopback default on port 8089. The overlay's Ingress has exactly two rules —
   `api.jtoye.local` → `core-java:9090` and `app.jtoye.local` → `frontend:3000` (verified in
   `kubectl kustomize k8s/local`). There is no edge-go backend to reach.
3. **It waits on `networkidle`** (`:76`, `:167`). The kitchen page holds SSE and STOMP connections
   open, so that state never settles — a trap this repository has hit repeatedly.
4. **It skips silently on two separate conditions.** `:46` skips without `RELAY_E2E`; `:80-85` skips
   without `TEST_SHOP_ID`/`TEST_PRODUCT_ID`. A skipped spec reported as green is a false pass, which is
   the single most likely way this row would have been ticked without proving anything.

So D-06 is proven two other ways: at the **broker**, above (identity), and through a **real browser
session** in L6/L7 below (function). Reworking the spec to be ingress-capable is recorded as a
deferred item, not silently left as a trap.

**L6 — INFRA-02d: a KDS client receives a relayed order event** *(owner: 26-08)*

```
Command : (as originally written) RELAY_E2E=true PLAYWRIGHT_BASE_URL=http://app.jtoye.local \
            npx playwright test e2e/stomp-relay.spec.ts
Expected: pass, and the host RabbitMQ shows a live STOMP connection authenticated as the
          dedicated STOMP login (NOT guest). Record the broker-side connection line too.
Actual  : SPLIT INTO TWO HALVES by plan 26-08, because the command above cannot serve as
          the proof — see "Broker-side STOMP identity" immediately above for the four
          structural reasons (stub cookie vs a server-side auth() gate, an edge-go target
          the local ingress does not route, a networkidle wait on an SSE/STOMP page, and
          two silent skip conditions).

          BROKER HALF — PROVEN (26-08 Task 2, 2026-07-25). One live STOMP 1.2 connection
          from the cluster, auth_login/user = jtoye (the dedicated stomp-login), guest
          count 0, corroborated by two independent broker views and correlated to the
          pod's "System" session at a 4 ms delta. Full capture and counts in the
          subsection above.

          BROWSER HALF — RUN, AND IT FALSIFIED THE PATH. Full evidence below.
```

**Browser half of L6 — the relay is reached, and it REJECTS the KDS topic.** Captured in one live
browser session against `http://app.jtoye.local` (no stubs, no mocks, real login). Reported as a
finding, not smoothed over — see §7 A3.

**Image identity for L6 AND L7** (mandatory per PIT-4; both rows were captured in the same session
against the same workloads, so the identity is stated once here and applies to both):

```
core-java  ghcr.io/bralabee/jtoye-core-java:local  in-cluster sha256:f43a5e84…  (host sha256:bba33e72…)
                                                   pod core-java-88b85df6f-x7vxn, startedAt 21:10:05Z
edge-go    ghcr.io/bralabee/jtoye-edge-go:local    in-cluster sha256:0644afc5…  (host sha256:e0e87717…)
frontend   ghcr.io/bralabee/jtoye-frontend:local   in-cluster sha256:def4382b…  (host sha256:3286c715…)
                                                   pod frontend-849d595866-2xdxb (rolled by 26-08 Task 1,
                                                   restartCount 0) — SAME image as the run header, only
                                                   the pod is new: the client-id change is a runtime env,
                                                   not a rebuild
pg-backup  ghcr.io/bralabee/jtoye-pg-backup:15     in-cluster sha256:1939105c…  (host sha256:943a78f6…)
```

These are the **same four builds** the run header records — the header carries the HOST-side ids and
this block carries the ids the cluster actually runs; they differ by mechanism, not by build, and are
reconciled by an identical `CreatedAt` (see §7 **PIT-4b**). None is a `:2.1.0` tag, so PIT-4 is
satisfied: L6 and L7 were captured against code built during the 26-07 run, not against the
three-phase-stale images sitting on this host.

The frontend image additionally proves the D-18 build-arg wiring: `api.jtoye.local` appears **10×** in
its static chunks and `localhost:9090` **0×**, so the browser bundle really is pointed at the ingress.

What WORKED, and it matters, because it isolates the defect to the destination and nothing else:

```
websocket opened            : ws://api.jtoye.local/ws        (14 opens over the session)
STOMP CONNECT  sent         : yes
STOMP CONNECTED received    : yes
  CONNECTED
  server:RabbitMQ/3.12.14
  session:session-ZnxI-6gaL4s4Qmg47iMARQ
  heart-beat:10000,10000
  version:1.2
  user-name:99d11593-ea98-4891-a136-220884094283
```

`server:RabbitMQ/3.12.14` in a frame delivered to the BROWSER is the strongest single line in this
document for D-06: the browser's STOMP session is being served by the **host RabbitMQ through the
relay**, not by Spring's in-memory simple broker (which identifies itself with no `server` header). The
`user-name` is the authenticated vendor's subject, so the CONNECT Authorization header was honoured.

What FAILED:

```
SUBSCRIBE sent (14x)
  destination:/topic/kitchen/00000000-0000-0000-0000-000000000001/97d95aa4-f6e8-4bb6-b9ad-525e49c61ef6

ERROR received (14x)
  message:Invalid destination
  content-type:text/plain
  version:1.0,1.1,1.2
  content-length:118

  '/kitchen/00000000-0000-0000-0000-000000000001/97d95aa4-f6e8-4bb6-b9ad-525e49c61ef6' is not a valid topic destination

frame census: open 14 | CONNECT 14 | CONNECTED 14 | SUBSCRIBE 14 | ERROR 14 | close 14 | MESSAGE 0
```

**Both directions are rejected, not just the subscriber.** The publish side fails on the relay's own
`_system_` session, 43 ms after the state change was accepted:

```
21:44:24.505 INFO  PaymentEventOutboxFlusher        Flushed outbox event ORDER_STATE_CHANGED (exchange=order.events)
21:44:24.511 INFO  OrderStateChangeListener         Order state change received: order=ORD-00000000-20260712-23C4097F
                                                    tenant=00000000-…-000000000001 CONFIRMED -> PREPARING
21:44:24.548 ERROR StompBrokerRelayMessageHandler   Received ERROR {message=[Invalid destination] …}
                                                    session=_system_ payload='/kitchen/00000000-…/97d95aa4-…'
```

The trigger itself succeeded — `POST http://api.jtoye.local/api/v1/orders/afe90b6d-…/start-preparation`
→ **HTTP 200**, `status: PREPARING` — so the API, the ingress, the outbox and the AMQP listener are all
healthy. Only the STOMP destination is invalid.

**The board DID visibly change without a manual refresh — and that is the trap this row exists to
catch.** `ORD-…-23C4097F Confirmed` → `ORD-…-23C4097F Preparing`, with **0** navigations to
`/dashboard/kitchen` during the wait. A human watching the screen would call that a pass. It is not: 0
MESSAGE frames arrived, and the same 30-second window contains **24** `/api/v1/orders…` requests — three
per redial, because every rejected SUBSCRIBE closes the session, `@stomp/stompjs` redials after
`reconnectDelay: 5000`, and `useStomp`'s `onReconnect` fires a full `fetchOrders()`. The visible update
is a **refetch caused by the failure**, not a relayed event. Reconnect-driven polling is
indistinguishable from realtime by eye and distinguishable only by frame census.

**Diagnosis falsified in two arms** against the same broker, port and credentials, over a raw socket
(read-only — a SUBSCRIBE creates an auto-delete queue that vanishes on DISCONNECT; nothing published):

```
ARM A control  destination:/topic/kitchen.00000000-…-000000000001.97d95aa4-…   (dots, one segment)
               CONNECTED true   SUBSCRIBE ok true (RECEIPT)   ERROR none
ARM B app shape destination:/topic/kitchen/00000000-…-000000000001/97d95aa4-…  (extra slashes)
               CONNECTED true   SUBSCRIBE ok false            ERROR 'Invalid destination'
DIAGNOSIS CONFIRMED: a RabbitMQ /topic destination must be a SINGLE segment.
```

ARM A is the load-bearing half: it proves the broker, the port, the STOMP login and the passcode are all
correct (so DEF-4 really is fixed) and that **only the destination shape** is at fault. Without it,
"Invalid destination" could have been read as another credential or connectivity problem.

**L6 verdict: the relay is PROVEN reachable and PROVEN authenticated, and the KDS event path is PROVEN
BROKEN.** D-06's browser half is therefore recorded as FALSIFIED rather than unproven — a stronger and
more useful outcome than a pass, and precisely why D-06 insisted this be exercised on the cluster where
`stomp.broker.mode` is `relay` instead of in compose where it is `in-memory`. The defect is
production-affecting (base sets `relay`); see §7 A3 and `deferred-items.md`.

**L7 — DEF-5: a real vendor login through the ingress reaches a dashboard** *(owner: 26-08)*

```
Command : (cd frontend && PLAYWRIGHT_BASE_URL=http://app.jtoye.local \
            E2E_VENDOR_USERNAME=admin-user E2E_VENDOR_PASSWORD="$KC_SEED_USER_PASSWORD" \
            npx playwright test --project=mobile e2e/dashboard-mobile.spec.ts)
          (credentials: user admin-user, password from the .env key KC_SEED_USER_PASSWORD —
           referenced by NAME, never echoed. Run from frontend/: there is no
           playwright.config.ts at the repository root, so `--prefix frontend` from the
           root resolves neither the config nor the `mobile` project.)
Expected: pass. This is the ONLY step that actually proves the split-horizon issuer fix —
          a real Keycloak flow, browser-issuer token, pod-side JWKS fetch.
Actual  : DEF-5 PROVEN. The authorize redirect, captured verbatim from the browser:

--- the redirect the SSO button produced (VERBATIM) ---
http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/auth
  ?scope=openid+profile+email
  &response_type=code
  &client_id=core-api
  &redirect_uri=http%3A%2F%2Fapp.jtoye.local%2Fapi%2Fauth%2Fcallback%2Fkeycloak
  &code_challenge=O8Oo6g6f5_QXCIHO6IPNNVvDeAA-suWrGaMmP4DFzTA
  &code_challenge_method=S256
--- end ---

          Three things are proven by that one URL: the browser was sent to the PUBLIC
          issuer host (not the pod-reachable one); `client_id=core-api` is the realm's
          real client, resolved from app-config/keycloak.client-id (it was the literal
          `frontend`, a client that does not exist in jtoye-dev); and the callback is the
          ingress origin, which the realm now accepts.

          Keycloak hosted form host : localhost:8085
          POST-LOGIN URL            : http://app.jtoye.local/dashboard
          rendered <h1>             : "Dashboard"   (not the sign-in page)
          login wall time           : 591 ms
                                      A ~10s hang followed by a 401 is the feedback_port3100
                                      symptom of a server-side call to the PUBLIC host. 591 ms
                                      is a decisive negative for it: the code-for-token
                                      exchange used KEYCLOAK_ISSUER_INTERNAL.
          `redirect_uri` errors in the frontend pod log for the login window : 0
          `Invalid parameter: redirect_uri` anywhere                          : 0

          THE TWO ISSUER VALUES, AND THEY DIFFER — that difference IS DEF-5:
            frontend KEYCLOAK_ISSUER          = http://localhost:8085/realms/jtoye-dev
            frontend KEYCLOAK_ISSUER_INTERNAL = http://host.minikube.internal:8085/realms/jtoye-dev
            core-java JWT_EXPECTED_ISSUER     = http://localhost:8085/realms/jtoye-dev
            core-java KC_ISSUER_URI           = http://host.minikube.internal:8085/realms/jtoye-dev
          The stamped token confirms which side is which: a direct-grant token minted by
          this realm carries iss=http://localhost:8085/realms/jtoye-dev, aud=core-api,
          tenant_id=00000000-0000-0000-0000-000000000001 — and core-java ACCEPTED it
          through the ingress (HTTP 200 on /api/v1/orders). So `iss` is validated against
          the public value while JWKS is fetched from the pod-reachable one. Collapsing
          them to a single value would have made the login pass while proving nothing.

          API ORIGIN — the XOR rule, measured rather than asserted:
            /api/v1 requests by host : {"api.jtoye.local": 10}
            loopback APP requests    : 0   (Keycloak on :8085 is the browser-reachable
                                            IdP by design — a compose backing service,
                                            not an application origin)
            /api/v1 responses >= 400 : 0
          Real seeded data rendered, not an empty shell: the shop switcher listed
          "Unsorted legacy items", "Brixton Village Grill", "Peckham Jollof Co.",
          "Mama Ade's Kitchen", and the kitchen board rendered real orders with line
          items. An empty catalogue would have been a green-looking regression.

          VISUAL / MOBILE:
            375px horizontal overflow : scrollWidth 375 == clientWidth 375 -> none
            <img> elements with naturalWidth === 0 : 0
              (the dashboard and kitchen routes render 0 <img> elements at all — a
               legitimate zero, stated rather than dressed up as an image proof. The
               s3.public-url image path is NOT exercised by this journey.)
            mobile-tab-bar elements in the live DOM : 1, visible
            console errors : 15, ALL accounted for — 14 are the A3 `STOMP error: Invalid
              destination` cascade and 1 is an authjs `Failed to fetch` from a getSession
              race during the redirect. None is unexplained.

          SPEC RESULT, reported exactly as measured: 10 passed / 3 failed on the first
          run and 11 passed / 2 failed on a second run with the spec's own
          NEXT_PUBLIC_API_URL supplied so its route() stubs intercept. NOT a DEF-5
          failure and NOT an environment fault — every failure is at line 268
          (`expect(getByTestId('mobile-tab-bar')).toBeVisible()`), which runs AFTER
          `vendorLogin` has already succeeded in `beforeEach`; a login failure would have
          thrown there instead. So all 13 tests performed a real Keycloak login through
          the ingress, twice. The failure is a pre-existing strict-mode fragility: the
          locator is not `.first()`, and during an App Router transition two shells are
          briefly mounted, so it resolves 2 elements. It is FLAKY (different routes failed
          on each run) and it does not reproduce in the unstubbed journey above, which
          measured exactly 1 visible tab bar. Recorded as a deferred item; deliberately
          NOT fixed here (pre-existing, unrelated to this plan's change, outside its file
          list).
```

**L7's precondition, read back from the RUNNING Keycloak** (de-fenced deliberately — see the addendum
below; this array legitimately contains a pre-existing loopback entry on the core-java port, and fencing
it would make this document fail its own loopback check on a string that predates the phase):

> `GET /admin/realms/jtoye-dev/clients?clientId=core-api` → `.redirectUris` =
> `[ "http://localhost:8080/*", "http://localhost:3100/*", "http://localhost:3000/*", "http://localhost:9090/*", "http://app.jtoye.local/*" ]`
> — 4 pre-existing localhost entries retained, 1 added. `webOrigins` = `[ "*" ]` (unchanged),
> `post.logout.redirect.uris` = `"+"` (unchanged), `publicClient` = `false`,
> `standardFlowEnabled` = `true`. A `frontend` client still does **not** exist in this realm
> (`?clientId=frontend` returns 0 results), which is what made the hardcoded literal unusable.
>
> Falsified in both directions so the acceptance is not blanket-acceptance: an authorize request
> carrying `redirect_uri=http://app.jtoye.local/api/auth/callback/keycloak` returns **HTTP 200** (the
> login page renders), while an unlisted host still returns `Invalid parameter: redirect_uri`. The
> client secret survived the additive update — a `client_credentials` grant returned HTTP 200 both
> before and after.

### Sign-off

```
Rows L1–L5 filled with real captured output        : [x]  (26-07, 2026-07-25)
Rows L6–L7 filled with real captured output        : [x]  (26-08, 2026-07-25)
                                                     L7 PASSED · L6 FALSIFIED (see §7 A3)
L6/L7 human-verify browser gate approved           : [x]  APPROVED 2026-07-25. Human report:
                                                     login PASSED (app.jtoye.local -> dashboard);
                                                     status dot AMBER, never green — which
                                                     CORROBORATES A3, so no re-run was needed.
                                                     A3 disposition: record now, fix in its own
                                                     scoped work (Rule 4 stop upheld).
Four image identities recorded in the header       : [x]  host-side IDs; see §7 PIT-4b for
                                                     the host-vs-in-cluster reconciliation
No loopback address anywhere in the evidence       : [x]  see the check below + the 26-08 addendum
Both backup arms recorded (L4)                     : [x]  arm A = 0, arm B = 4067
Recorded by                                        : plan 26-07 executor, 2026-07-25
                                                     plan 26-08 executor, 2026-07-25 (L6/L7)
```

**The loopback check, measured rather than asserted — and stated in the only form that can actually
fail.** The obvious form, `grep -c 'localhost:9090' k8s/LOCAL.md` == 0, is **unsatisfiable**: the rule
above has to name the string it forbids, so that grep returns **2** on a perfectly clean document (the
rule sentence in this section, and this paragraph). Both hits are classified and neither is captured
output.

The assertion that means something is scoped to §11's **fenced blocks** — i.e. to captured output only.
Walk this section with awk, toggling on each triple-backtick fence, keep only the lines inside a fence,
and count the pattern in those: the result is **0**, over 278 captured-output lines.

That command is deliberately described here rather than shown in a fenced block, and the reason is worth
recording: when it *was* pasted into a fence in this section, its own worked example became a line
"inside §11's fences" and the count went from 0 to **1**. The check began failing on itself. A
verification example and the material it verifies must not share a namespace — the same prose-vs-grep
trap this phase hit in 26-01, 26-02, 26-03, 26-04 and 26-05, arriving here one level more recursive.

Two `127.0.0.1` strings DO appear inside those fences, and they are legitimate rather than an
exception: they are the host-side MinIO bucket-privacy probes
(`http://127.0.0.1:9000/jtoye-db-backups/` → 403 and `/jtoye-images/` → 200). Those deliberately
address **host MinIO**, a compose backing service the cluster consumes; they are not an application URL
and cannot be served through an ingress. Every URL that reaches an application in the evidence above is
`http://api.jtoye.local` or `http://app.jtoye.local`. The §5 `localhost` values are configuration prose,
outside §11 entirely.

**Addendum, plan 26-08 (2026-07-25).** This section grew by two evidence blocks, so the measurement was
re-taken rather than assumed: the pattern count inside §11's fences is still **0**, now over **549**
captured-output lines (up from 278; 412 at the Task 2 commit, 547 before the approved gate result was
recorded in the sign-off block). The figure quoted here is the FINAL measurement of the finished
section, deliberately re-taken after the last edit rather than left at whichever intermediate value was
written down first — every edit that adds a fenced line moves it, so a stale figure here would be its
own small false-green. The check was re-falsified before being trusted — a fence carrying
the forbidden string, injected INSIDE §11, takes the count 0 → **1**; the same fence appended at
end-of-file leaves it at 0, which is the awk scoping working correctly rather than the check being
blind. Restoration was by `cp` from a scratchpad copy and verified byte-identical with `cmp`; no
`git checkout --` was used on an uncommitted file (26-04's recorded process incident).

One further loopback string now appears inside these fences and is legitimate for the same reason the
two MinIO probes are: `http://localhost:15672/api/connections`, the host RabbitMQ **management API**.
That is a compose backing service the cluster consumes over the host gateway — and the only broker-side
view that reports non-AMQP protocols at all — not an application endpoint, and it cannot be served
through an ingress. The forbidden pattern remains specifically an application API base on the core-java
port, and that count is 0.

The verbatim `redirectUris` read-back from the live realm (L7's precondition) is recorded **de-fenced**
in L7 below, for exactly the recursive reason 26-07 recorded for its worked example: that array
legitimately contains a pre-existing loopback entry on the core-java port, and pasting it into a fence
would make this document fail its own check on a string that predates this phase and is not captured
application output. The content is verbatim and complete; only the fence is omitted, and this sentence
is why.

**What these five rows do NOT establish.** Read this next to §6. L1–L5 prove manifest validity against a
real API server, a real rollout, the boot identity and the backup content. They prove **nothing** about
TLS or HSTS (`tls: null`, no cert-manager), **nothing** about the six nginx security headers (the
PIT-1 snippet annotation is nulled locally and the controller's posture was never weakened to make
anything pass), and **nothing** about NetworkPolicy enforcement (minikube's default CNI does not
enforce; all 6 policies were applied and are inert). INFRA-01 and INFRA-02 are therefore **not** marked
complete by this plan.

**What L6 and L7 add, and what they still do not establish** (plan 26-08). L7 closes DEF-5: a real
Keycloak flow through the ingress, with the two issuer values recorded as demonstrably different values
and the token's stamped `iss` accepted by core-java. L6 closes D-06's *identity* question and **opens** a
new one — the relay is reachable and correctly authenticated, and the KDS destination is invalid, so the
KDS realtime path is unproven-because-broken rather than unproven-because-untested (§7 A3). Neither row
touches TLS, the nginx header snippet, NetworkPolicy enforcement or HPA scaling — §6 stands unchanged and
uncontradicted. The `s3.public-url` browser image path is likewise **not** exercised: the dashboard and
kitchen routes render zero `<img>` elements. **26-08 does not mark INFRA-01 or INFRA-02 complete
either** — plan 26-09 owns that, and A3 is now an input to it.

---

## Related documents

- `k8s/QUICK_START.md` — the staging/production 5-minute recipe (Secrets → apply → verify → DNS → TLS).
- `k8s/DEPLOYMENT.md` — the living deployment how-to, including the five static CI gates and the
  golden-render workflow.
- `k8s/PRODUCTION_READINESS_REPORT.md` — a dated signed audit. Read it as a record, not as current
  state; later corrections are appended as dated notes.
- `k8s/base/networkpolicies/README.md` — policy flow matrix, verification and rollback.
- `docs/runbooks/backups.md` — backup mechanics, verification and the restore procedures §9 references.
- `.env.example` — every `K8S_LOCAL_*` key with its consumer and provenance.
