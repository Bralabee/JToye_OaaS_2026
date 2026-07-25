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
