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
