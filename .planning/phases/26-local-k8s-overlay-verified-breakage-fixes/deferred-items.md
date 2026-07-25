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

## 2026-07-25 — The compose⊕k8s XOR guard cannot see a second writer already inside the cluster — **CLOSED `6a2663b` + `a4ddc50`**

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

### CLOSED — 2026-07-25, commits `6a2663b` (the guard) + `a4ddc50` (`default` removed from the exempt set)

Closed as a scoped out-of-plan hardening attributed to this phase, on the user's explicit instruction,
against the live `jtoye` profile (so the proceed arm is real, not synthetic).

**What shipped.** `k8s_local_assert_cluster_xor` — a NEW sibling of `k8s_local_assert_compose_xor`
rather than an overload of it, because the two inspect different subsystems and have different tooling
failure modes — wired into `scripts/k8s-local-up.sh` as **step 3b**: after `minikube start`, before the
addon, the bootstrap and any apply. Running it pre-start is exactly what made the 16 connections
invisible.

**Three deliberate departures from the suggested closure above, all strictly stronger.**

1. It counts **pods, not non-zero-replica Deployments**. The 26-07 offender also carried a
   CronJob-spawned `pg-backup` pod, and a Job, StatefulSet, DaemonSet or bare Pod holds a dev-Postgres
   connection exactly as well as a Deployment's pod does. Counting pods catches every shape, including
   ones nobody has enumerated yet.
2. "Live" excludes only the terminal phases (`Succeeded`, `Failed`), so **`Pending` counts too** — a pod
   still pulling its image in a stale namespace is the same hazard a few seconds early. Completed pods
   (the `pg-backup-rehearsal` Job, the ingress-admission Jobs) are correctly ignored. Ruled correct on
   review and explicitly not narrowed: the refusal prints the phases it found, so a `Pending` refusal
   cannot be misread as a `Running` one.
3. **`default` is NOT exempt**, although the suggested closure above lists it and `6a2663b` initially
   honoured that list. It is an ordinary **writable** namespace, not a system one — an unqualified
   `kubectl run` or `kubectl apply` with no `-n` lands there, which is exactly the accidental-writer
   class this guard exists to catch. Exempting it bought nothing either: every object this tooling
   creates goes to the overlay namespace, and step 4b's admission-webhook probe targets `default` under
   `--dry-run=server`, which creates nothing. Removed in `a4ddc50` after verifying `default` was empty
   on this cluster (no pods; only the built-in `service/kubernetes`), so there was no false-refusal
   risk. The constant carries a DO-NOT-TIDY-THIS-BACK note, because the obvious "cleanup" is to re-add
   it the first time a stray pod trips the guard — the correct response is to delete the stray pod.

   Making `default` refusable also exposed wrong remediation advice in the refusal itself: it printed
   `kubectl delete namespace <namespace>`, and `default` cannot be deleted. Fixed in the same commit —
   the block now offers workload-level deletion alongside namespace-level and says which one applies to
   `default`. Wrong advice is worse than terse advice, given that naming offenders is the whole point.

**Kept from the suggested closure.** A named arm (`REFUSED [cluster-writers-present]`) so it is
falsifiable like the other four; no auto-deletion of anything; the offenders are NAMED — namespace, live
pod count, pod phases and **image tags** — because a bare exit code was the thing that taught the
operator nothing. Exempt: the expected namespace read from `k8s/local/kustomization.yaml` (one source of
truth, no new literal) plus `kube-system`, `kube-public`, `kube-node-lease` and `ingress-nginx`, which is
a named constant beside the existing service inventories.

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

**All three arms re-proven after `a4ddc50`**, because the exempt-set constant is load-bearing for each:
PROCEED still exit 0 at the same 11 live pods, with the rendered exempt list now correctly omitting
`default` (which proves the refusal message renders from the constant rather than from a copy of it);
REFUSE exit 1 on a scratch pause pod placed **in `default`** — the arm that was impossible before that
commit — then exit 0 again after deleting the pod (pod, not namespace, following the guard's own
corrected advice); VOID still exit 2 on a nonexistent context and on an empty-inventory fixture.

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
CLOSED with a behaviour table (0 / 1 / 2 arms) and the reasons behind the departures above. Both §2 and
§7 A1 state why `default` is excluded rather than merely omitting it from a list, so the next reader
cannot mistake the omission for an oversight. The suggested-closure paragraph earlier in THIS entry is
deliberately left as written, and so is the captured `kubectl get ns` output in `k8s/LOCAL.md` §11 —
those are records of what was proposed and observed, not documentation of current behaviour.

---

## 2026-07-25 — `frontend/e2e/stomp-relay.spec.ts` cannot run against the ingress; rework it to be ingress-capable

**Found by:** plan 26-08 Task 2, while looking for a mechanical proof of D-06's browser half.

**Why this is deferred rather than fixed here.** The spec is a compose-era artifact and each of the four
problems below needs its own decision (which auth path, which order-creation path, which wait strategy),
so changing it is a design task rather than a config fix. 26-08 proved D-06 two other ways instead — at
the broker (identity) and through a real browser session (function) — and recorded the spec's limits in
`k8s/LOCAL.md` §11 so a future green run of it is never mistaken for the proof.

**The four structural mismatches, each verified against the committed file:**

1. **Stub-cookie auth.** `frontend/e2e/stomp-relay.spec.ts:61-63` and `:149-151` inject
   `authjs.session-token: "e2e-stub"`. `frontend/app/dashboard/layout.tsx:19` resolves the session
   server-side with `await auth()` and redirects when it is absent, so a fabricated token lands the run
   on `/auth/signin`. **Suggested closure:** reuse `frontend/e2e/dashboard-mobile.spec.ts`'s real
   Keycloak login helper (`E2E_VENDOR_USERNAME` / `E2E_VENDOR_PASSWORD`), which is already proven to
   work against `app.jtoye.local` — see 26-08's L7 evidence.
2. **Orders are posted to edge-go, which the local ingress does not route.**
   `frontend/e2e/stomp-relay.spec.ts:29` reads `EDGE_URL` with a loopback default on port 8089. The
   local overlay's Ingress has exactly two rules (`api.jtoye.local` → `core-java:9090`,
   `app.jtoye.local` → `frontend:3000`); `k8s/base` has no edge-go rule in any overlay.
   **Suggested closure:** either create the order through core-java on `api.jtoye.local` (the same path
   26-08's browser journey uses) or add an edge-go ingress rule — the first is smaller and does not
   change the deploy surface.
3. **`networkidle` never settles on the kitchen page.** `:76` and `:167`. `/dashboard/kitchen` holds an
   SSE stream and a STOMP connection open for its lifetime. **Suggested closure:** wait on a specific
   element/row appearing (`expect(locator).toBeVisible()`), never on a load state. This trap is already
   recorded in the project's fleet-supervision learnings.
4. **Two silent skip conditions.** `:46` skips without `RELAY_E2E`; `:80-85` skips without
   `TEST_SHOP_ID` / `TEST_PRODUCT_ID`. A skipped spec reported as green is a false pass, and this is the
   most likely way L6 would have been ticked without proving anything. **Suggested closure:** derive the
   shop/product ids from the API at run time (they are seeded and readable through
   `GET /public/shops`), so the spec has no reason to skip; keep `RELAY_E2E` as a deliberate opt-in but
   make the runner assert it ran (`--forbid-only` does not cover skips — assert a non-zero expected
   count).

**Acceptance for the closure:** the reworked spec passes against `PLAYWRIGHT_BASE_URL=http://app.jtoye.local`
with a real login, creates an order without edge-go, and FAILS (not skips) if the relay is unavailable —
proven by pointing `stomp.broker.relay-host` at an unreachable host and observing a red run.

---

## 2026-07-25 — **PRODUCTION DEFECT:** the STOMP relay rejects the KDS topic — a `/topic` destination cannot contain `/`

> **TRACKED AS GitHub issue [#266](https://github.com/Bralabee/JToye_OaaS_2026/issues/266)** (OPEN,
> `bug` / `P1`) — filed 2026-07-25 by plan 26-09 at phase close. This entry stays as the full technical
> record (mechanism, two-arm falsification, fix sites, acceptance test); **#266 is the authoritative
> status and the thing that gets scheduled.** A deferred item that lives only inside a completed phase
> directory is not tracked work.

**Found by:** plan 26-08 Task 3, the live browser journey D-06 exists to force. **Severity: production
affecting.** `k8s/base/configmap.yaml` sets `stomp.broker.mode: "relay"`, so staging and production both
run the broken path. Dev compose defaults to `STOMP_BROKER_MODE=in-memory`
(`core-java/src/main/resources/application.yml:222-224`), where Spring's simple broker accepts arbitrary
destination paths — which is exactly why this has never been seen before, and exactly what D-06
predicted ("dev compose never exercises the relay").

**The defect.** The application publishes and subscribes to `/topic/kitchen/{tenantId}/{shopId}`:
- publisher: `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java:109`
- subscriber: `frontend/app/dashboard/kitchen/page.tsx:277` (via `frontend/hooks/use-stomp.ts`)
- convention: `core-java/src/main/java/uk/jtoye/core/websocket/TenantChannelInterceptor.java:123`
  (`/topic/{feature}/{tenantId}/{...}`)

RabbitMQ's STOMP plugin maps `/topic/<name>` onto the `amq.topic` exchange with `<name>` as the routing
key, and `<name>` must be a **single segment**. It answers:

```
ERROR
message:Invalid destination
'/kitchen/00000000-0000-0000-0000-000000000001/97d95aa4-f6e8-4bb6-b9ad-525e49c61ef6' is not a valid topic destination
```

**Both directions fail.** 14/14 browser SUBSCRIBEs rejected, and the relay's own `_system_` session
rejected on publish 43 ms after `OrderStateChangeListener` logged `CONFIRMED -> PREPARING`. MESSAGE
frames delivered: **0**.

**Why it looks like it works, which is the dangerous part.** Each rejected SUBSCRIBE tears the session
down; `@stomp/stompjs` redials on `reconnectDelay: 5000`; `useStomp`'s `onReconnect` hook fires a full
`fetchOrders()`. So the board updates roughly every 5 s with no manual refresh, and by eye that is
indistinguishable from realtime. Measured: 24 `/api/v1/orders…` requests in a 30 s window, 14 WebSocket
opens, 0 MESSAGE frames. Anyone verifying this visually — including the human-verify step of this very
plan — would reasonably call it a pass. Only a frame census falsifies it.

**Diagnosis proven, not inferred** — two arms against the same broker, port and credentials over a raw
socket (read-only; a SUBSCRIBE makes an auto-delete queue that vanishes on DISCONNECT, nothing
published):

| arm | destination | CONNECTED | SUBSCRIBE | ERROR |
|---|---|---|---|---|
| A (control) | `/topic/kitchen.<tenant>.<shop>` (dots) | true | **ok (RECEIPT)** | none |
| B (app shape) | `/topic/kitchen/<tenant>/<shop>` (slashes) | true | **rejected** | `Invalid destination` |

Arm A is load-bearing: it proves the broker, port, STOMP login and passcode are all correct — i.e. DEF-4
really is fixed — and isolates the fault to the destination shape alone.

**Why it was NOT fixed in plan 26-08 (deviation Rule 4 — architectural).** The fix changes the topic
naming convention across three surfaces simultaneously (Java publisher, TypeScript subscriber, and the
`TenantChannelInterceptor` tenant-isolation prefix check that parses those segments to enforce
cross-tenant isolation), none of which is in that plan's `files_modified`, and Phase 26's stated boundary
is "no application behaviour change" beyond the additive edits its decisions authorise. Getting the
interceptor's parsing wrong is a **tenant-isolation** risk, so it needs its own plan, its own tests and
its own review.

**Do NOT close this by setting `stomp.broker.mode: in-memory`.** That hides the defect and breaks
multi-replica correctness: the simple broker is per-JVM, so with `replicas: 3` an event published by one
pod never reaches a client attached to another. The relay exists precisely for that.

**Suggested direction.** Move to a dot-delimited routing key that RabbitMQ accepts while keeping every
segment and the interceptor's prefix check — e.g. `/topic/kitchen.{tenantId}.{shopId}` — updating
publisher, subscriber and interceptor in one change. Note `amq.topic` treats `.` as its wildcard
separator, so a dotted UUID becomes five routing-key words; confirm the interceptor's tenant check and
any future wildcard subscriptions still behave, and check every other `/topic/...` destination in the
codebase for the same shape, not just the kitchen one.

**Acceptance for the closure (must FAIL before it passes):**
1. Against a relay-mode cluster, a browser KDS session receives a **MESSAGE** frame after an order
   status change — asserted on the frame census, not on the board's appearance, because the board
   updates either way.
2. `grep -c 'Invalid destination'` in the core-java log over the run = **0**.
3. WebSocket opens over a 60 s idle KDS session = **1** (proving the reconnect storm is gone).
4. A cross-tenant subscribe attempt is still refused by `TenantChannelInterceptor` — the isolation test
   must be re-run, not assumed, because the destination format it parses has changed.

---

## 2026-07-25 — `dashboard-mobile.spec.ts:268` is strict-mode fragile and flakes on a duplicate tab bar

**Found by:** plan 26-08 Task 3, running the spec against the ingress. **Pre-existing; unrelated to that
plan's change; deliberately not fixed** (outside its file list, and the SCOPE BOUNDARY rule).

`frontend/e2e/dashboard-mobile.spec.ts:268` asserts
`expect(page.getByTestId("mobile-tab-bar")).toBeVisible()` with no `.first()`. During an App Router
transition two `DashboardShell` trees are briefly mounted, so the locator resolves **2** elements and
Playwright raises a strict-mode violation (the first one measuring `hidden`).

Measured twice: **10 passed / 3 failed** (Dashboard, Shops, Orders), then **11 passed / 2 failed**
(Dashboard, Finance) on a re-run with the spec's own `NEXT_PUBLIC_API_URL` supplied. Different routes
fail each time, so it is flake, not a route defect. `/dashboard` failed in both runs — that is the case
where the test `goto`s the URL it is already on.

**It is not a product regression.** The unstubbed live journey in `k8s/LOCAL.md` §11 L7 measured exactly
**1** `mobile-tab-bar` element, visible, on the same build through the same ingress. And it is not a
DEF-5 failure: every failure is at line 268, which runs *after* `vendorLogin` has already succeeded in
`beforeEach` — so all 13 tests performed a real Keycloak login through the ingress on both runs.

**Suggested closure:** scope the locator (`.first()`, or assert on a count of >= 1 visible) and add an
explicit wait for the transition to settle. Do NOT weaken it to `toBeAttached()` — visibility is the
MOBL-01 assertion's whole point. Re-verify at both 390px and 375px.
