# Phase 26 — Deferred Items

Out-of-scope discoveries logged during execution (per executor SCOPE BOUNDARY). These are NOT
fixed in the plan that discovered them. Plans 26-04, 26-06 and 26-08 append to this file.

## 26-02 — CI builds frontend images with NO `NEXT_PUBLIC_*` build args

**Discovered during:** 26-02 Task 2 (D-18 dead-config removal).
**Dated:** 2026-07-25.

**What.** `NEXT_PUBLIC_*` values are inlined into the Next.js bundle at Docker **BUILD** time
(`frontend/Dockerfile:22-23` declares `ARG NEXT_PUBLIC_API_URL`, and line 31 of that same file
already documents the rule). The CI image build passes **no `build-args` at all**:

- `.github/workflows/ci-cd.yaml:443-456` — the `docker/build-push-action` step's `with:` block
  carries `context` / `file` / `push` / `tags` / `labels` / `cache-from` / `cache-to` / `platforms`
  and no `build-args:` key.
- `k8s/base/frontend-deployment.yaml` previously injected `NEXT_PUBLIC_API_URL` from
  `app-config/api.url` as a **runtime** `env:` entry, which reached nothing the bundle reads.
  26-02 removed that dead injection and replaced it with an explanatory comment block.

So every CI-built frontend image ships with `NEXT_PUBLIC_API_URL` **unset and inlined as
undefined**, in staging and production alike. The removed runtime injection was masking this in
one specific place: `frontend/lib/env-validation.ts` reads its required list via the **dynamic**
form `process.env[envVar]`, which Next.js does not inline, so the injected runtime value
satisfied the boot-time required-var check while every inlined literal in the app was already
`undefined`. With the dead env gone, `validateEnvironment()` now reports the truth (a
`console.error` naming the missing var; it never throws, so nothing hard-fails).

**Why it is not fixed here.** A single image cannot carry two different baked API URLs for
staging and production. Making this value genuinely runtime requires a **server config source** —
a `/api/config` endpoint the client fetches, or a per-environment image build in the deploy
pipeline. Both are application-architecture changes, explicitly out of scope for Phase 26 per
`26-RESEARCH.md` PIT-3 and `26-CONTEXT.md` § "Out of scope" (no application behaviour change, no
new endpoint).

**What still works locally.** `app-config/api.url` remains the source of truth for the
`--build-arg`; plan 26-05's `scripts/k8s-local-up.sh` passes it so the locally-built image is
correctly baked, which is what keeps D-16's full browser proof reachable.

**Suggested owner.** A follow-up phase on frontend runtime configuration, alongside the other
`NEXT_PUBLIC_*` values that have the same shape (`NEXT_PUBLIC_KEYCLOAK_URL`,
`NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL`, `NEXT_PUBLIC_SUPPORT_*`,
`NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS`).

## 26-02 — Four new external-endpoint base values are UNVERIFIABLE-FROM-THIS-HOST

**Discovered during:** 26-02 Task 3 (provisioned-resources check).
**Dated:** 2026-07-25.

**What.** 26-02 replaced four loopback/dev application defaults with real external targets in
`k8s/base/configmap.yaml`, each derived from a value already committed in this repo:

| key | base value | provenance |
|---|---|---|
| `smtp.host` | `email-smtp.eu-west-2.amazonaws.com` | AWS SES SMTP endpoint for the committed `s3.backup.region` |
| `s3.endpoint` | `https://s3.eu-west-2.amazonaws.com` | AWS regional S3 endpoint for the same region |
| `s3.bucket` | `jtoye-images` | `application.yml` `${S3_BUCKET:jtoye-images}` |
| `s3.public-url` | `https://s3.eu-west-2.amazonaws.com/jtoye-images` | the two rows above, concatenated |

Provenance is not provisioning. Whether an SES sending domain is verified in `eu-west-2`, and
whether the `jtoye-images` bucket exists there, **cannot be checked from this host** — there are
no AWS credentials in this environment and the only kubeconfig context is the employer AKS
cluster `sipbihs2aks`, which is DO-NOT-TOUCH infrastructure (`k8s-kustomize-deploy` memory).
Recorded as `UNVERIFIABLE-FROM-THIS-HOST`, never as "looks right".

**Why this is bounded rather than open.** The failure mode cannot activate without a deliberate
operator act, because 26-02 kept the inert-by-default posture in three independent ways:
`smtp.auth` stays `"false"` (byte-identical to today's default, so no credential is offered to
any relay); all 7 new `secretKeyRef` entries are `optional: true`, so with no Secret created the
env stays unset and `application.yml`'s own default applies; and the S3/SMTP/Stripe credentials
live only in Secrets an operator must create by hand from `k8s/QUICK_START.md` Step 1.

**Operator action before these paths are activated in a real environment** (i.e. before creating
`smtp-credentials` or `s3-media-credentials`, or flipping `smtp.auth` to `"true"`): confirm the
SES sending domain is verified in `eu-west-2` and that the `jtoye-images` bucket exists in that
region. If either is false, the first newly-wired call becomes a **loud** runtime error (SMTP auth
failure, or S3 403/404) rather than the previous silent no-op.

**Suggested owner.** The operator performing the next staging/production rollout, alongside
26-01's `PRE-ROLLOUT OPERATOR CHECK` for `rabbitmq-credentials/username`. Plan 26-06 owns the
dated note on `k8s/PRODUCTION_READINESS_REPORT.md` that carries both.

## 26-02 — `NOT-PROVISIONED`: the Stripe Connect return/refresh paths have no frontend route

**Discovered during:** 26-02 Task 3 (provisioned-resources check).
**Dated:** 2026-07-25.

**What.** `stripe.connect.return-url` and `stripe.connect.refresh-url` now resolve to
`https://app.jtoye.co.uk/dashboard/payments/connect/{return,refresh}` (and the staging equivalents).
**Those routes do not exist.** Verified in-repo, not assumed:

- `find frontend/app -type d \( -name connect -o -name payments -o -name payouts -o -name stripe \)`
  returns **nothing**. `frontend/app/dashboard/` has `customers finance kitchen marketing media
  onboarding orders products shops staff webhooks` — and no `payments`.
- `grep -rn 'payments/connect' frontend --include=*.ts --include=*.tsx` returns **nothing**: the
  frontend has no reference to the path at all.
- The value IS consumed: `core-java/.../payment/StripeConnectService.java:107` passes it to
  Stripe's AccountLink API as `setReturnUrl(...)`, so Stripe really does redirect a vendor there
  at the end of Connect onboarding.

**This plan did not cause it, and did improve it.** The PATH is byte-identical to the
`application.yml` default (`${STRIPE_CONNECT_RETURN_URL:http://localhost:3000/dashboard/payments/connect/return}`);
26-02 changed only the ORIGIN. Before: a vendor was redirected to a loopback address on their own
machine — connection refused, and nothing about the platform on screen. After: the vendor lands on
the real platform origin and gets a **404**. Strictly better (the Stripe-side account link has
already been committed server-side either way, and the vendor is at least on the platform where
they can navigate to their dashboard), but still a broken landing destination — precisely the
lifecycle dead-end class the `feedback_audit_landing_destinations` memory flags.

**Failure mode on first use.** A vendor completing Stripe Connect onboarding sees a 404 page. No
server error, no data loss, no silent state corruption — the `stripe_connect_status` update path is
independent of this redirect. It is a UX dead-end, not a correctness defect.

**Why it is not fixed here.** Adding `frontend/app/dashboard/payments/connect/return/page.tsx` (and
`refresh`) is a new UI route — new application surface, which `26-CONTEXT.md` § "Out of scope" bars
for this phase ("no application behaviour change; no new feature surface"). Choosing what those
pages should say (poll Connect status? show a success/pending state? deep-link back to the payouts
setup?) is a product decision, not a deploy-layer one.

**Suggested owner.** The phase that builds the vendor payments/payouts dashboard surface. Until
then, the correct origin is still the right value to ship — a 404 on the platform beats a
connection-refused at a loopback address, and reverting the origin would restore a worse defect.

## 26-04 — In-cluster Keycloak manifests + the `auth.jtoye.co.uk` ingress rule and its TLS SAN

**Discovered during:** 26-04 Task 2 (the base ingress fix; `26-REVIEWS.md` Adjudication B).
**Dated:** 2026-07-25.

**What was removed, and why.** `k8s/base/ingress.yaml` published the host `auth.jtoye.co.uk` and
routed it to `service: keycloak` on port 8080 (pre-change lines `:74-83`), and listed the same
hostname in the single `jtoye-tls` SAN set alongside `api` and `app` (pre-change lines `:47-52`).
**No Service named `keycloak` exists anywhere in `k8s/`** — verified across every render, whose
complete Service set is `core-java`, `edge-go` and `frontend`; neither `k8s/staging` nor
`k8s/production` adds one (both list `../base` + `namespace.yaml`, one configmap patch, images and
replicas). So staging and production were each publishing a hostname with no backend, for which
nginx answers **503**. Both the rule and the SAN entry were removed in `k8s/base` — not merely
patched out of the local render — because hiding a production defect behind a local overlay
contradicts D-15's own doctrine of fixing the base.

**Why there is no Service to add instead.** Keycloak is an **external managed identity provider**
in staging and production: `app-config` `keycloak.issuer.uri` is
`https://auth.jtoye.co.uk/realms/jtoye-prod`, and public DNS for that hostname resolves to the
managed IdP, not to this ingress controller. Those two config keys legitimately keep the hostname
and must NOT be "cleaned up" — they are how the platform finds its IdP. What was wrong was this
controller *claiming* the name.

**The displaced intent.** If an in-cluster Keycloak is ever deployed into the platform namespace
(a real option — it would remove a managed-service dependency and make the realm import part of
the deploy), then the host rule and the TLS SAN come BACK, **together with its Service and
Deployment and in that order** — never before them. The pre-change shape is recoverable from git
history and from the in-file comment that replaced it.

**Secondary reason this was worth fixing rather than tolerating.** All the SAN names share ONE
`secretName: jtoye-tls`, and cert-manager issues that certificate as a single order covering every
SAN. An HTTP-01 challenge for a hostname this controller does not serve cannot be answered here,
and a failed challenge fails the whole order — so the dangling SAN was a live risk to issuance and
renewal for `api` and `app` too.

**Recurrence prevention shipped with the fix.** `INV-6` in
`k8s/scripts/check-render-invariants.sh` asserts, for **every** target's render, that each Ingress
backend Service name resolves to a `kind: Service` present in that same render. Its allowlist is
empty. So the class cannot return silently — including via a future overlay.

**Suggested owner.** A future in-cluster-identity phase, if one is ever wanted. Nothing is blocked
today: the removal deleted no working path, because there was no backend to reach.

## 26-06 — Calico CNI locally to actually enforce NetworkPolicies (prerequisite now CLEARED)

**Recorded during:** 26-06 Task 1 (the `k8s/LOCAL.md` "what local does NOT prove" section).
**Dated:** 2026-07-25.

**What.** The 6 NetworkPolicies render unchanged in `k8s/local` and are validated as manifests, but
minikube's default CNI does not enforce NetworkPolicies at all (D-11). Local therefore proves their
validity and nothing about their behaviour. Installing Calico on the local profile would make local
the **only** environment that proves enforcement.

**Why it is not done here.** It exceeds the roadmap criteria for this phase, and it is not a
drop-in: `k8s/base/networkpolicies/20-core-java.yaml` allows public egress to `0.0.0.0/0` with
`10.0.0.0/8`, `172.16.0.0/12` and `192.168.0.0/16` in `except[]`, and its only in-cluster allow
targets a `jtoye-infrastructure` namespace that does not exist locally. The minikube host gateway
sits inside `192.168.0.0/16`, so under an enforcing CNI the **entire** local traffic pattern — ports
5433/8085/6379/5672/61613/9000/1025 to `host.minikube.internal` — is denied. Enabling Calico without
first adding a host-gateway egress rule would simply break the local cluster.

**Status change worth recording: the stated prerequisite is now cleared.** This follow-up was
previously blocked behind the D-17 kube-dns selector defect — a Calico local cluster would have
inherited a total DNS blackhole for core-java, because the `labels:` transformer was injecting the
common labels into the DNS-egress `podSelector`. Plan 26-01 fixed that in all three kustomizations
(and it is repeated in `k8s/local/kustomization.yaml`), and plan 26-03's **INV-3** in
`k8s/scripts/check-render-invariants.sh` asserts it on the render per target. So the blocker is gone;
what remains is the host-gateway egress rule and the decision to spend the local resources.

**Suggested owner.** A network-policy phase, ideally the same one that rolls enforcement out on AKS —
the concrete CIDR and port list it needs is written out in `k8s/LOCAL.md` § "What local does NOT
prove", so it starts from the truth rather than a guess.

## 26-06 — Env-contract gate covers core-java only; `edge-go` and the frontend are ungated

**Recorded during:** 26-06 Task 1.
**Dated:** 2026-07-25.

**What.** `k8s/scripts/check-env-contract.sh` asserts the env contract in both directions for
core-java by parsing `k8s/base/core-java-deployment.yaml` against `application*.yml`. It covers
**neither** `edge-go` nor the frontend — the gate's own summary line and the `k8s/DEPLOYMENT.md`
§ "K8s static gates" table both say "Covers **core-java only**" so the limit is not silently implied.

**Why it matters rather than being cosmetic.** This is the same DEF-4/DEF-6 bug class, just in two
other services. Phase 26 found a concrete instance: `edge-go/cmd/edge/main.go` has read
`JWT_EXPECTED_ISSUER` since the issuer/JWKS decoupling fix, and **no k8s manifest ever supplied it**
(plan 26-02 wired it). A core-java-only gate could not have caught that.

**Why it is not done here.** Extending it needs two new parsers with different shapes: Go
`os.Getenv("NAME")` call sites (plus `os.LookupEnv`, and any wrapper that reads a default), and
Next.js `process.env.NAME` — where the **dynamic** `process.env[expr]` form is not statically
resolvable at all and is exactly the form `frontend/lib/env-validation.ts` uses. The frontend also
needs the build-time/runtime distinction encoded (a `NEXT_PUBLIC_*` name supplied as a runtime `env:`
is *dead config*, which is defect 6 of this phase), so a naive "is it injected?" check would report
the opposite of the truth.

**Suggested owner.** A follow-up hardening phase, or whichever phase next touches `edge-go` or
frontend env wiring.

## 26-06 — The customer-storefront realm is unconfigured in EVERY k8s environment

**Recorded during:** 26-06 Task 1.
**Dated:** 2026-07-25.

**What.** `CUSTOMER_KC_ISSUER_URI`, `CUSTOMER_JWT_EXPECTED_ISSUER` and
`NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` exist in `docker-compose.full-stack.yml` and are read by the
application, but appear in **no** `k8s/` manifest — base, staging, production or local. So the
customer (storefront) realm is unconfigured in every Kubernetes environment, not just locally. This
is a real production gap, not a local-overlay omission.

**Why it is not fixed here.** Deliberately deferred in `26-CONTEXT.md` § `<deferred>` ("Customer-storefront
realm in k8s"), and recorded as a **reasoned allowlist entry** in
`k8s/scripts/check-env-contract.sh` rather than left silent: supplying only the core issuer would
half-wire the realm and make a broken configuration look configured, which is worse than an obviously
absent one. The full set also spans a `NEXT_PUBLIC_*` value, which is build-time (see the 26-02 entry
above), so wiring it correctly needs the frontend-runtime-config decision too.

**Suggested owner.** The storefront / customer-identity (CID) work, which owns the realm itself.

## 26-06 — Sealed-secrets / external-secrets for the local path

**Recorded during:** 26-06 Task 1.
**Dated:** 2026-07-25.

**What.** Local secrets arrive from `scripts/k8s-local-secrets.sh`, which sources the gitignored
`.env` and applies each Secret with `kubectl create secret … --dry-run=client -o yaml | kubectl apply
-f -` (D-01). Values are briefly visible in `argv` to a local `ps`, and nothing is encrypted at rest
beyond etcd's own defaults.

**Why it is not fixed here.** `.planning/PROJECT.md:141` locks the decision for this milestone: plain
GitHub + k8s Secrets now, "Work Order H (sealed-secrets or external-secrets-operator) is the
long-term answer". `26-CONTEXT.md` § "Out of scope" repeats it. Accepted for local specifically
because the host is a single-user development machine and `k8s/QUICK_START.md` already documents the
same imperative pattern for the bootstrap path.

**Suggested owner.** Work Order H / the secrets-management phase. `docs/runbooks/sealed-secrets.md`
already carries the staging/production workflow to extend.

## 26-06 — No `mcp-server` k8s manifest set

**Recorded during:** 26-06 Task 1.
**Dated:** 2026-07-25.

**What.** The MCP server shipped in Phases 20 and 25 and runs in compose (it is one of the four
compose **app** services the XOR guard requires to be down), but `k8s/` has no Deployment, Service,
HPA, PDB, NetworkPolicy or Ingress for it at all. So the local cluster — and staging and production —
run the platform without its MCP surface.

**Why it is not fixed here.** `26-CONTEXT.md` § "Out of scope" excludes it explicitly. It is not a
local-overlay patch: a new service needs its own manifests, its own scoped credential (the Phase 25
`integration-orders-rw`-style client), its own NetworkPolicy row in
`k8s/base/networkpolicies/README.md`'s flow matrix, and an ingress decision (is the MCP endpoint
public?). It also has connection-budget consequences — `k8s/scripts/check-connection-math.sh` would
need the new pool counted.

**Suggested owner.** Its own phase (manifests + scoped credentials + ingress).

## 26-06 — `emptyDir` at `/var/log/jtoye` in the base is the durable PIT-5 fix

**Recorded during:** 26-06 Task 1.
**Dated:** 2026-07-25.

**What.** Under the prod profile that every k8s environment uses (D-10),
`core-java/src/main/resources/application-prod.yml:91` sets
`logging.file.name: ${LOG_PATH:/var/log/jtoye}/application.log`. The container runs as
`runAsUser: 1000`, `/var/log` is root-owned, and the image never creates that directory — so
logback's FileAppender fails to start on **every** boot with a `FileNotFoundException … Permission
denied`, and file logging is silently absent. Non-fatal (the app continues; the 2026-07-14 rehearsal
reached 11/11 READY that way), but it is boot noise that reads like a real fault **in staging and
production too**, not only locally.

**What was done instead, and why it is not the fix.** `k8s/local/configmap-patch.yaml` sets
`log.path: /tmp`, which the pod user can write. That resolves it for local only. The base default is
deliberately **unchanged**, because changing where production writes its application log is a
production behaviour change and this phase's boundary is "no change to behaviour for existing,
already-configured environments".

**The durable fix.** Mount an `emptyDir` at `/var/log/jtoye` in `k8s/base/core-java-deployment.yaml`
(or make the path a first-class config key with a writable default). That keeps the prod log path
intact *and* makes it writable, in every environment, without a per-overlay patch. It touches the
Deployment's volumes and the golden renders, so it wants its own reviewed change.

**Suggested owner.** An observability or logging phase, or the next base-manifest hardening pass.

## 26-06 — `OLLAMA_URL` and `ZIPKIN_ENDPOINT` remain allowlisted omissions

**Recorded during:** 26-06 Task 1.
**Dated:** 2026-07-25.

**What.** Both are `${PLACEHOLDER}` values `application*.yml` expects, supplied by no manifest, and
both are carried as **reasoned entries in the allowlist** of
`k8s/scripts/check-env-contract.sh` (the gate fails a blank reason, a duplicate, or an entry that has
become unnecessary — so these cannot rot silently):

- `OLLAMA_URL` — there is no in-cluster Ollama, and the media vision stage is advisory-only behind
  `jtoye.media.vision.enabled`, which defaults `false` (Phase 24 IMG-03: a vision failure never
  rejects an upload, it only flags for review). Supplying this would point core-java at a host that
  does not exist; the unreachable default keeps the stage inert, which is the intended k8s behaviour.
- `ZIPKIN_ENDPOINT` — no in-cluster Zipkin/OTLP collector is deployed, and Micrometer tracing export
  is best-effort: spans are dropped silently and no request path degrades. A supplied-but-wrong
  endpoint would be worse than an unreachable default.

**Why they are recorded rather than closed.** Each needs a real backing service before a value would
mean anything, and inventing one is the DEF-6 defect class in reverse. They are listed here so the
inventory of "what is deliberately unsupplied" lives in one reviewable place as well as in the gate.

**Suggested owner.** `OLLAMA_URL` → whichever phase deploys or points at a real inference endpoint.
`ZIPKIN_ENDPOINT` → the observability phase that adds a collector (which the readiness report's own
"Long-Term" recommendations already propose).

## 26-06 — `.planning/PROJECT.md` quotes a stale test baseline (found by the staleness sweep)

**Discovered during:** 26-06 Task 2 (the systematic stale-reference sweep, QUALITY_RULE_6).
**Dated:** 2026-07-25.

**What.** `.planning/PROJECT.md:128` states "baseline is **1257** logical invocations" as a live
milestone constraint ("Total must grow, not regress"). The real figure is **1698** after this plan's
reconcile — the line is stale by 441 and has been through several phases.
`.planning/PROJECT.md:165` (the dated "Last updated" narrative for Phase 25) separately quotes
**1684**, which was correct when written but is a dated record.

**Why it is only partly a problem.** The same sentence at line 128 already names
`docs/metrics.json` as the single source of truth and `scripts/docs-freshness.sh --write` as the
reconciler, so a reader following the pointer gets the right number. The `docs-freshness` CI gate does
**not** read `PROJECT.md`, so this cannot turn the build red — which is precisely why it drifted.

**Why it is not fixed here.** Plan 26-06's declared file list is
`docs/metrics.json` + `CLAUDE.md` + `AGENTS.md`; `.planning/PROJECT.md` is the milestone owner's
document, and line 165 is a dated narrative record of Phase 25 that this repo's convention says to
append to rather than rewrite. Editing a milestone constraint line mid-phase, from a docs plan, would
also make the "1257" figure's provenance unrecoverable without git archaeology.

**Suggested owner.** The milestone-close pass (`/gsd:review-backlog` or the v2.3 wrap-up), which
already updates `PROJECT.md`. The durable fix is to stop quoting a number there at all and point only
at `docs/metrics.json` — a count in prose next to a gate that does not check it will always drift.

---

## 2026-07-25 — The compose⊕k8s XOR guard cannot see a second writer already inside the cluster — **CLOSED `6a2663b`**

**Discovered by.** Plan 26-07, live, during the A1 pre-apply inventory. This is the most transferable
finding the phase produced, so it is recorded here rather than only in the plan summary.

**What.** `k8s_local_assert_compose_xor` enforces D-04 by inspecting **compose only** — the four app
services must be down, the six backing services up. It never looks at the cluster it is about to
start. The guard is therefore asymmetric: it will refuse to start a cluster while compose is up, but
it will happily start a cluster that **already contains its own writers**.

**Measured, not hypothesised.** The `jtoye` profile had been left `Stopped` on 2026-07-14, and a
Stopped profile preserves etcd. On start it restored the `jtoye-staging` namespace with **11 running
pods** (core-java 3/3, edge-go 5/5, frontend 3/3) on the stale `:2.1.0` images — code predating Phases
23, 24 and 25 — plus a `pg-backup` CronJob that fired immediately and failed `BackoffLimitExceeded`.
Those pods held **16 live connections to the shared dev Postgres as `jtoye_app`**:

```
jtoye_app <- 172.18.0.1/32  (16 conns)
```

So `minikube start` alone silently re-created the two-writers-on-one-shared-dev-Postgres hazard that
the human is asked to stop their compose apps in order to avoid — and every guard in the phase
reported green while it was happening. Deleting the namespace (46 objects) dropped remote connections
to **0**, which is how the attribution was established.

**Why it is not fixed here.** Plan 26-07's declared file list is `k8s/LOCAL.md` +
`docs/runbooks/backups.md`. Adding a cluster-side arm to `k8s_local_assert_compose_xor` changes a
load-bearing safety guard and needs its own falsification probes on both arms (refuse and proceed),
which is a plan's worth of work, not a drive-by edit inside the phase's single live-mutation window.
Three script defects were already fixed in this plan under Rule 1/3 because they *blocked* the
rehearsal; this one does not block it, and the standing instruction was explicitly to record rather
than implement.

**Suggested closure.** After step 3's profile start and before any apply, refuse if a namespace outside
the expected set (`jtoye-local`, `kube-*`, `default`, `ingress-nginx`) has a Deployment with non-zero
replicas — naming the offending namespaces the way the compose arms already name offending services.
Two details matter: the refusal must be a REFUSE arm with its own name (e.g.
`REFUSED [cluster-writers-present]`) so it can be falsified like the other four, and it must not
auto-delete anything — deleting a namespace is a human decision for exactly the reason stopping a
compose container is.

**Interim mitigation, already shipped.** `k8s/LOCAL.md` §7 A1 now carries this as a named guard gap
with the measured numbers and an instruction to run the inventory by hand on every first start and
treat a surviving `jtoye-*` namespace as a blocker rather than clutter.

**Suggested owner.** A follow-up plan touching `scripts/lib/k8s-local-guards.sh`, or the next phase
that revisits the local-k8s tooling.

### CLOSED — 2026-07-25, commit `6a2663b`

Closed as a scoped out-of-plan hardening attributed to this phase, on the user's explicit instruction,
against the live `jtoye` profile (so the proceed arm is real, not synthetic).

**What shipped.** `k8s_local_assert_cluster_xor` — a NEW sibling of `k8s_local_assert_compose_xor`
rather than an overload of it, because the two inspect different subsystems and have different tooling
failure modes — wired into `scripts/k8s-local-up.sh` as **step 3b**: after `minikube start`, before the
addon, the bootstrap and any apply. Running it pre-start is exactly what made the 16 connections
invisible.

**Two deliberate departures from the suggested closure above, both strictly stronger.**

1. It counts **pods, not non-zero-replica Deployments**. The 26-07 offender also carried a
   CronJob-spawned `pg-backup` pod, and a Job, StatefulSet, DaemonSet or bare Pod holds a dev-Postgres
   connection exactly as well as a Deployment's pod does. Counting pods catches every shape, including
   ones nobody has enumerated yet.
2. "Live" excludes only the terminal phases (`Succeeded`, `Failed`), so **`Pending` counts too** — a pod
   still pulling its image in a stale namespace is the same hazard a few seconds early. Completed pods
   (the `pg-backup-rehearsal` Job, the ingress-admission Jobs) are correctly ignored.

**Kept from the suggested closure.** A named arm (`REFUSED [cluster-writers-present]`) so it is
falsifiable like the other four; no auto-deletion of anything; the offenders are NAMED — namespace, live
pod count, pod phases and **image tags** — because a bare exit code was the thing that taught the
operator nothing. Exempt: the expected namespace read from `k8s/local/kustomization.yaml` (one source of
truth, no new literal) plus `kube-system`, `kube-public`, `kube-node-lease`, `ingress-nginx`, `default`,
which is a named constant beside the existing service inventories.

**Fails closed on every tooling path** — missing `kubectl`, unreachable API server, unparseable
response, or an **empty** inventory all exit 2 (VOID). The empty case is explicit because wave 5 had to
fix that exact fail-open class in the compose guard. No `grep -q` under a pipe anywhere (the `43fb5e1`
inversion class).

**Falsified on three arms, verbatim output recorded in the session:** PROCEED exit 0 with `jtoye-local`'s
3 running pods correctly not flagged; REFUSE exit 1 naming a scratch namespace, its pod count, phase and
image tag, then exit 0 again once deleted; VOID exit 2 on a nonexistent context, an empty fixture, an
unparseable fixture and with `kubectl` off `PATH`. Plus an end-to-end wiring proof: the real
`scripts/k8s-local-up.sh` aborted at step 3b before step 4 mutated anything. D-14 re-runnability is
intact — `jtoye-local` running pods are the normal second-invocation state and pass.

**One unanticipated defect found while proving the wiring, fixed in the same commit (Rule 1).** Step 3's
idempotence check compared `minikube profile list -o json`'s `.Status` against `Running`, but a healthy
profile reports **`OK`** there (`Running` is `minikube status`'s vocabulary). The "already Running
(idempotent no-op)" branch was therefore **dead**, and every invocation called `minikube start` on an
already-running profile — which on the docker driver bounces every pod in the cluster (measured: all
three `jtoye-local` pods to `CreateContainerConfigError` on `failed to sync secret cache`, ~2 minutes
unavailable, restarts 3/2/2 → 4/3/3, ingress controller rolled 9 → 10). It self-heals, so it read as
nothing while silently destroying a running rehearsal's state — and it is a large part of why step 4b's
webhook gate is load-bearing. Recorded as `k8s/LOCAL.md` §7 **A2**.

**Operator-facing record.** `k8s/LOCAL.md` §2 gains the cluster half of the XOR rule and its refusal
arm; §4's step table gains the 3b row; §7 A1 is flipped from "guard gap, run the inventory by hand" to
CLOSED with a behaviour table (0 / 1 / 2 arms) and the reasons behind the two departures above.
