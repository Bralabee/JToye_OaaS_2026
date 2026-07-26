---
phase: 26-local-k8s-overlay-verified-breakage-fixes
plan: 02
subsystem: infra
tags: [kubernetes, kustomize, configmap, secretkeyref, split-horizon-issuer, cors, stripe-connect, smtp, s3, webhooks, golden-render, nosuperuser]

# Dependency graph
requires:
  - phase: 26-local-k8s-overlay-verified-breakage-fixes
    plan: "01"
    provides: "render-golden.sh --snapshot/--diff-since (fail-closed anchoring), the committed k8s/goldens baselines, and the block-scoped-assertion + normal-diff-format conventions this plan's proofs use"
  - phase: 22-notifications-comms
    provides: "the notification/webhook config surface (unsubscribe HMAC, tracking base URL, delivery knobs) whose k8s halves were never supplied"
  - phase: 24-image-architecture
    provides: "the media-storage config surface (S3 endpoint/bucket/public-url/credentials) whose k8s halves were never supplied"
provides:
  - "19 new app-config keys, each with an in-file comment naming its in-repo SOURCE — media storage, SMTP, CORS, token audience, split-horizon public issuer, D-19 notification + Stripe Connect origins, log path, webhook delivery knobs"
  - "26 new core-java env entries (19 configMapKeyRef + 7 optional secretKeyRef across 4 new Secrets)"
  - "D-13 split-horizon wiring for all three deployments: keycloak.public.issuer.uri (STAMPED issuer) vs keycloak.issuer.uri (pod-reachable JWKS host)"
  - "CORS_ALLOWED_ORIGINS — the hard prerequisite for plan 26-07's KDS WebSocket proof (WebSocketConfig reads the same property as CorsConfig)"
  - "D-18: the dead frontend NEXT_PUBLIC_API_URL runtime injection removed and explained; 0 NEXT_PUBLIC_* envs in any tracked manifest"
  - "DEF-2: recipe + template both name the NOSUPERUSER jtoye_app role, with the two legitimate jtoye exceptions annotated"
  - "the per-phase .planning/phases/26-.../deferred-items.md file (Phase 23 format) that 26-04/26-06/26-08 append to"
affects: [26-03, 26-04, 26-05, 26-06, 26-07, 26-08, 26-09]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "every new config value carries an in-file comment naming its in-repo SOURCE, so a reviewer can verify nothing was invented"
    - "optional: true on every new secretKeyRef — inert-by-default rather than CreateContainerConfigError-by-default"
    - "provenance != provisioning: a value with a named repo source still needs a recorded PROVISIONED / NOT-PROVISIONED / UNVERIFIABLE outcome"
    - "block-scoped (per-YAML-document) assertions instead of whole-file greps when a token legitimately appears more than once"

key-files:
  created:
    - .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/deferred-items.md
  modified:
    - k8s/base/configmap.yaml
    - k8s/base/core-java-deployment.yaml
    - k8s/base/frontend-deployment.yaml
    - k8s/base/edge-go-deployment.yaml
    - k8s/staging/configmap-patch.yaml
    - k8s/production/configmap-patch.yaml
    - k8s/base/secrets-template.yaml.example
    - k8s/QUICK_START.md
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml

key-decisions:
  - "26-02: two Task-3 acceptance criteria were UNFALSIFIABLE as written because the token `jtoye` legitimately appears twice — rabbitmq-credentials.username IS `jtoye` (and 26-01's PRE-ROLLOUT OPERATOR CHECK expects exactly that), and the QUICK_START grep was anchored `$` against a line ending in ` \\`, so it already returned the pass value pre-change. Replaced with block-scoped per-document assertions that genuinely separate pre- from post-fix."
  - "26-02: `kubectl kustomize <overlay> | grep -c localhost` == 0 was ALREADY 0 pre-change (the loopback defaults live in application.yml, never in the render) — it is a valid non-regression guard but NOT a proof that D-19 is fixed. The falsifiable form is the env-name -> configMapKeyRef-key -> rendered-value chain, asserted pre (0) vs post (1) against the named snapshot."
  - "26-02: production's configmap-patch states the 7 env-varying keys EXPLICITLY even though every value is byte-identical to base — so a future base default change cannot silently move a production email link or a vendor payout redirect."
  - "26-02: smtp.auth stays \"false\" and all 7 new credential refs are optional:true, so the four values that changed from a loopback no-op into a real external target cannot become a loud failure until an operator deliberately supplies a Secret."
  - "26-02: STOMP note in QUICK_START reworded, never deleted — the operational warning still holds (no optional flag on those two refs), only the claim about what the envs then DO needed the D-05 correction."

patterns-established:
  - "Provisioned-resources check: every new value naming an EXTERNAL endpoint gets a recorded PROVISIONED / NOT-PROVISIONED / UNVERIFIABLE-FROM-THIS-HOST outcome with evidence — 'looks right' is not an outcome"
  - "Golden diff reviewed by attributing every added AND removed line to its enclosing YAML document (kind/name), with the line counts reconciled arithmetically against the edits made"

requirements-completed: []

# Metrics
duration: ~21min
completed: 2026-07-25
---

# Phase 26 Plan 02: DEF-6 / D-13 / D-19 Base-Manifest Config Drift Summary

**The 19 config values `k8s/base` never supplied — media storage, SMTP, CORS, token audience, the split-horizon public issuer, and the four production-affecting loopback origins that put a dev URL in every production unsubscribe link and every Stripe Connect vendor return — wired from `app-config` into all three deployments with every base value traced to a named in-repo source, the dead `NEXT_PUBLIC_API_URL` runtime injection removed and explained, and the superuser DB role purged from the secret recipe; proven additive by a snapshot-anchored golden diff whose only removal in either render is the one intentional deletion.**

## Performance

- **Duration:** ~21 min of task work (snapshot 18:06:xx → last task commit 18:21:04 +0100); wall time longer due to an API stream stall between Task 3's edits and its verification (no work lost, no edit redone — the working tree was re-inspected by `git diff` on resume before continuing)
- **Tasks:** 3 of 3
- **Files:** 11 (1 created, 10 modified) — exactly the plan's `files_modified` set, no file outside it touched

## Accomplishments

- **DEF-6 closed for core-java.** 23 injected env vars → **49**. Media storage no longer resolves to a dev MinIO endpoint with `minioadmin`/`minioadmin`; email no longer resolves to a loopback SMTP host; CORS, token audience, the log path and the webhook-delivery knobs are supplied rather than inherited invisibly from code defaults.
- **D-19 (four production-affecting defaults) closed with a falsifiable chain proof.** `NOTIFICATION_UNSUBSCRIBE_BASE_URL`, `NOTIFICATION_EMAIL_TRACKING_BASE_URL`, `STRIPE_CONNECT_RETURN_URL`, `STRIPE_CONNECT_REFRESH_URL` are each present in the rendered production Deployment (count **0 → 1** vs the named snapshot) and each resolves through `app-config` to an `https://app.jtoye.co.uk` value.
- **D-13 split-horizon wired across all three deployments** with base behaviour byte-identical: `keycloak.public.issuer.uri` holds the same value as `keycloak.issuer.uri`, so no environment's `iss` validation loosens or tightens. `KEYCLOAK_ISSUER` → public, new `KEYCLOAK_ISSUER_INTERNAL` → pod-reachable, edge-go gains `JWT_EXPECTED_ISSUER`. **No `auth.ts` change was required** and none was made.
- **D-18 dead config removed, and a masking effect discovered while removing it.** The runtime `NEXT_PUBLIC_API_URL` injection reached nothing any component reads (Next.js inlines `NEXT_PUBLIC_*` at Docker BUILD time), *but* it was satisfying `frontend/lib/env-validation.ts`, which reads its required list via the **dynamic** `process.env[envVar]` form that Next.js does **not** inline. So the boot check passed while every inlined literal in the app was already `undefined`. With the dead env gone, `validateEnvironment()` reports the truth (`console.error`; it never throws, so nothing hard-fails).
- **DEF-2 closed in both the recipe and the template**, with the two *legitimate* `jtoye` values (the `jtoye_backup` BYPASSRLS dump role, the RabbitMQ broker user) explicitly annotated so a future reader does not "fix" them.
- **Incremental Betterment proven, not asserted.** Across both renders: **+166 / −5 lines per target**, and the only 5 removed lines in either render are the single intentional D-18 block. The 9 Deployment/Service/PDB top-level selector blocks are **byte-identical** to the pre-change snapshot, so no immutable field moved.

## Task Commits

1. **Task 1: app-config keys + core-java env wiring** — `08b91b4` (fix)
2. **Task 2: frontend + edge-go split-horizon, D-18 removal, overlay overrides, deferred-items created** — `f15461f` (fix)
3. **Task 3: DEF-2 recipe/template + reviewed golden regeneration + gate sweep** — `9bcea26` (fix)

---

## The 19 new `app-config` keys, with their sourced base values

| key | base value | source (verified in-repo) |
|---|---|---|
| `keycloak.public.issuer.uri` | `https://auth.jtoye.co.uk/realms/jtoye-prod` | byte-equal to existing `keycloak.issuer.uri` (checked by string compare, below) |
| `cors.allowed-origins` | `https://app.jtoye.co.uk` | existing `frontend.url` |
| `jwt.expected-audience` | `core-api` | `application.yml:131` `${JWT_EXPECTED_AUDIENCE:core-api}` |
| `log.path` | `/var/log/jtoye` | `application-prod.yml:91` `${LOG_PATH:/var/log/jtoye}` |
| `s3.endpoint` | `https://s3.eu-west-2.amazonaws.com` | AWS regional endpoint for the committed `s3.backup.region` |
| `s3.region` | `eu-west-2` | existing `s3.backup.region` |
| `s3.bucket` | `jtoye-images` | `application.yml:316` `${S3_BUCKET:jtoye-images}` |
| `s3.public-url` | `https://s3.eu-west-2.amazonaws.com/jtoye-images` | the two rows above, concatenated |
| `smtp.host` | `email-smtp.eu-west-2.amazonaws.com` | AWS SES SMTP endpoint for the committed region (Phase 22 shipped SES-over-SMTP) |
| `smtp.port` | `587` | `application.yml:78` `${SMTP_PORT:587}` |
| `smtp.auth` | `false` | `application.yml:84` `${SMTP_AUTH:false}` — identical |
| `smtp.starttls` | `true` | `application.yml:86` `${SMTP_STARTTLS:true}` — identical |
| `notification.email.tracking-base-url` | `https://app.jtoye.co.uk` | existing `frontend.url` (D-19) |
| `notification.unsubscribe.base-url` | `https://app.jtoye.co.uk` | existing `frontend.url` (D-19) |
| `stripe.connect.return-url` | `https://app.jtoye.co.uk/dashboard/payments/connect/return` | `frontend.url` + the path in `application.yml:346` (D-19) |
| `stripe.connect.refresh-url` | `https://app.jtoye.co.uk/dashboard/payments/connect/refresh` | `frontend.url` + the path in `application.yml:347` (D-19) |
| `webhook.delivery.max-attempts` | `8` | `application.yml:398` — identical |
| `webhook.delivery.retention-days` | `30` | `application.yml:402` — identical |
| `webhook.target.block-private-ranges` | `true` | `application.yml:409` — identical |

Every placeholder name above was confirmed against `application.yml` / `application-prod.yml` by grep **before** the key was written — no env name was guessed.

```
$ grep -c 'localhost' k8s/base/configmap.yaml
0
$ public="https://auth.jtoye.co.uk/realms/jtoye-prod"  priv="https://auth.jtoye.co.uk/realms/jtoye-prod"
  BYTE-EQUAL: YES
```

**Staging/production overrides** (7 env-varying keys each, derived from that overlay's own `frontend.url` / `keycloak.issuer.uri`):

```
$ kubectl kustomize k8s/staging | grep -c 'cors.allowed-origins: https://app-staging.jtoye.co.uk'                                        1
$ kubectl kustomize k8s/staging | grep -c 'notification.unsubscribe.base-url: https://app-staging.jtoye.co.uk'                            1
$ kubectl kustomize k8s/staging | grep -c 'stripe.connect.return-url: https://app-staging.jtoye.co.uk/dashboard/payments/connect/return'   1
$ kubectl kustomize k8s/staging | grep -c 'keycloak.public.issuer.uri: https://auth-staging.jtoye.co.uk/realms/jtoye-staging'              1
$ kubectl kustomize k8s/production | grep -c 'keycloak.public.issuer.uri: https://auth.jtoye.co.uk/realms/jtoye-prod'                     1
```

Media storage and SMTP are deliberately **not** overridden per environment — the base values are the correct AWS `eu-west-2` regional endpoints for both, and `smtp.auth` stays `"false"` in both.

---

## Injected-env count for core-java, with its arithmetic

```
$ grep -cE '^\s+- name: [A-Z0-9_]+\s*$' k8s/base/core-java-deployment.yaml
49
```

**23 pre-existing + 19 ConfigMap-sourced + 7 Secret-sourced = 49.** This matches the plan's expected 49 exactly (the earlier draft's 42 was the arithmetic error `26-REVIEWS.md` Adjudication A corrected).

Falsification of the "never delete to hit a target" rule:

```
$ git diff k8s/base/core-java-deployment.yaml | grep -cE '^-\s+- name: '
0          # zero pre-existing env entries removed
```

All 26 expected new names present (loop over the 26 printed nothing). Reviewed omissions confirmed absent:

```
$ grep -cE '^\s+- name: (OLLAMA_URL|ZIPKIN_ENDPOINT|CUSTOMER_KC_ISSUER_URI|CUSTOMER_JWT_EXPECTED_ISSUER)\s*$' k8s/base/core-java-deployment.yaml
0
```

Parser contract for the connection-math gate held (no comment inserted between `- name:` and `value:`):

```
$ kubectl kustomize k8s/base | awk '/- name: DB_POOL_SIZE/{getline; print}'
          value: "10"
```

`optional: true` = **7** in both the file and the render (the four new Secrets' seven keys); `kind: Secret` in the base render = **0**.

---

## Golden diff, grouped by kind, with the snapshot-anchored three-part assertion

Snapshot `26-02` was taken **before the first edit of this plan** (working tree clean, `snapshot_exit=0`, both files created).

| Assertion part | Command | Result |
|---|---|---|
| 1. baseline resolved | `render-golden.sh --diff-since 26-02 > "$D"; echo "resolve_exit=$?"` | **`resolve_exit=0`** (a `2` would mean VOID, not passed) |
| 2. no `localhost` / `5432` line removed | `grep '^<' "$D" \| grep -cE 'localhost\|value: "5432"'` | **`0`** — 26-01 owns the `5432` removal and it predates this snapshot |
| 3. snapshot predates the edit | `test -s "$D"` | **TRUE** — 12,135 bytes; 10 `<` lines / 332 `>` lines across both targets |

Every added and removed line attributed to its enclosing YAML document (per-document walk, not a line heuristic):

| Target | added | ConfigMap/app-config | Deployment/core-java | Deployment/edge-go | Deployment/frontend | anywhere else | removed |
|---|---|---|---|---|---|---|---|
| staging | 166 | **19** | **137** | **5** | **5** | **0** | 5 (all `Deployment/frontend`) |
| production | 166 | **19** | **137** | **5** | **5** | **0** | 5 (all `Deployment/frontend`) |

**The counts reconcile arithmetically against the edits made:** 19 ConfigMap keys; core-java 19 configMapKeyRef entries × 5 lines (95) + 7 secretKeyRef entries × 6 lines (42) = **137**; edge-go `JWT_EXPECTED_ISSUER` = 5; frontend `KEYCLOAK_ISSUER_INTERNAL` = +5 and `NEXT_PUBLIC_API_URL` = −5.

**All 10 removed lines in the entire diff, verbatim** — the single intentional D-18 deletion, twice (once per target), and nothing else:

```
<         - name: NEXT_PUBLIC_API_URL
<           valueFrom:
<             configMapKeyRef:
<               key: api.url
<               name: app-config
```

Nothing was modified in any `Service`, `HorizontalPodAutoscaler`, `PodDisruptionBudget`, `NetworkPolicy`, `Ingress`, `CronJob` or `Namespace` document.

**Immutable-field proof** (the assertion that rules out an apply failure on a live cluster), using 26-01's per-document extraction:

```
staging:    selector blocks OLD=9 NEW=9 -> BYTE-IDENTICAL
production: selector blocks OLD=9 NEW=9 -> BYTE-IDENTICAL
```

The frontend `KEYCLOAK_ISSUER` repoint is visible in the hunk and was independently confirmed on the render (not inferred from diff line alignment):

```
$ kubectl kustomize k8s/base | grep -A 4 'name: KEYCLOAK_ISSUER$'          | grep -c 'key: keycloak.public.issuer.uri'   1
$ kubectl kustomize k8s/base | grep -A 4 'name: KEYCLOAK_ISSUER_INTERNAL'  | grep -c 'key: keycloak.issuer.uri'          1
```

---

## Provisioned-resources check (NAMED, per value)

The four SMTP/S3 values convert a **silent no-op** (loopback default, feature inert) into a **real outbound call** in environments that previously lacked them, so the plan requires a recorded outcome per value rather than "looks right".

**Evidence gathered from this host (actual output, not assumed):**

```
$ command -v aws && aws --version
aws-cli/1.45.46 Python/3.12.2 Linux/6.8.0-136-generic botocore/1.43.46
$ test -f ~/.aws/credentials && echo PRESENT || echo ABSENT
ABSENT
$ test -f ~/.aws/config && echo PRESENT || echo ABSENT
ABSENT
$ env | grep -c '^AWS_'
0
$ kubectl config get-contexts
CURRENT   NAME          CLUSTER       AUTHINFO
          sipbihs2aks   sipbihs2aks   clusterUser_sipbihs2aks_group_sipbihs2aks
$ kubectl config current-context
error: current-context is not set
```

The AWS CLI exists but has **no credentials of any kind** (no file, no env), and the only kubeconfig context is the employer AKS cluster `sipbihs2aks` — DO-NOT-TOUCH infrastructure, with no current-context even set. So SES domain verification and bucket existence are genuinely unreachable from here.

| value | outcome | evidence / reason |
|---|---|---|
| `smtp.host` = `email-smtp.eu-west-2.amazonaws.com` | **UNVERIFIABLE-FROM-THIS-HOST** | No AWS credentials on this host (output above); cannot query SES verified sending domains. Mitigated: `smtp.auth` stays `"false"` (byte-identical to today's default, so no credential is offered to any relay) and `smtp-credentials` is `optional: true`, so with no Secret created the email path stays inert. |
| `s3.endpoint` = `https://s3.eu-west-2.amazonaws.com` | **UNVERIFIABLE-FROM-THIS-HOST** | Same: no credentials to run a bucket/region existence check. The endpoint form itself is the standard AWS regional S3 endpoint for the committed `s3.backup.region`. |
| `s3.bucket` = `jtoye-images` | **UNVERIFIABLE-FROM-THIS-HOST** | Cannot confirm the bucket exists in `eu-west-2`. Note the value is byte-identical to `application.yml`'s own `${S3_BUCKET:jtoye-images}` default, so this key changes *nothing* about which bucket name the app uses — only `s3.endpoint`/`s3.public-url` change where it looks for it. |
| `s3.public-url` = `https://s3.eu-west-2.amazonaws.com/jtoye-images` | **UNVERIFIABLE-FROM-THIS-HOST** | Derived from the two rows above; inherits their status. Mitigated: `s3-media-credentials` is `optional: true`, so with no Secret created no S3 write is attempted. |
| `stripe.connect.return-url` | **NOT-PROVISIONED** | Repo-verified, not assumed: `find frontend/app -type d \( -name connect -o -name payments -o -name payouts -o -name stripe \)` returns **nothing**; `frontend/app/dashboard/` has no `payments`; `grep -rn 'payments/connect' frontend --include=*.ts --include=*.tsx` returns **nothing**. The value *is* consumed — `payment/StripeConnectService.java:107` passes it to Stripe's AccountLink API. Dated deferred item added. |
| `stripe.connect.refresh-url` | **NOT-PROVISIONED** | Same absent route; same deferred item. |

**On the two NOT-PROVISIONED values — this plan did not cause the gap and did improve the outcome.** The *path* is byte-identical to the `application.yml` default; only the *origin* changed. Before: a vendor finishing Connect onboarding was redirected to a loopback address on their own machine (connection refused, nothing of the platform on screen). After: they land on the real platform origin and get a **404** — strictly better, still a broken landing destination, and exactly the lifecycle dead-end class the `feedback_audit_landing_destinations` memory flags. Reverting the origin would restore the worse defect, so shipping the correct origin is right; building the route is a new-UI-surface decision barred by `26-CONTEXT.md` § "Out of scope".

**Inert-by-default mitigation still holds in the render** (so no unprovisioned target can become a loud failure without a deliberate operator act):

```
$ kubectl kustomize k8s/base | grep -c 'smtp.auth: "false"'                      1
$ grep -c 'optional: true' k8s/base/core-java-deployment.yaml                    7
$ kubectl kustomize k8s/base | grep -c 'optional: true'                          7
```

---

## Per-phase deferred-items file created here

**Path:** `.planning/phases/26-local-k8s-overlay-verified-breakage-fixes/deferred-items.md` (first line `# Phase 26 — Deferred Items`, Phase 23 format: heading, purpose line, `## <plan-id>` section per entry). No top-level `.planning/deferred-items.md` was invented — verified absent before and after (`test ! -f` passes). Plans 26-04, 26-06 and 26-08 append to this file.

**Three entries recorded, all dated 2026-07-25:**

1. **CI builds frontend images with no `NEXT_PUBLIC_*` build args** — the entry the plan required. `.github/workflows/ci-cd.yaml:443-456`'s `docker/build-push-action` `with:` block carries `context`/`file`/`push`/`tags`/`labels`/`cache-from`/`cache-to`/`platforms` and **no `build-args:`** (verified by reading lines 440-456). So every CI-built frontend image ships `NEXT_PUBLIC_API_URL` unset and inlined as undefined, while the now-removed ConfigMap injection was masking it in `env-validation.ts` via the dynamic-lookup path. A single image cannot bake two per-environment API URLs, so the durable fix is a runtime config source (a server `/api/config` endpoint or a per-environment build) — out of scope per `26-RESEARCH.md` PIT-3. Carries both file:line citations. `grep -c 'ci-cd.yaml:443'` = **1**.
2. **Four new external-endpoint base values are UNVERIFIABLE-FROM-THIS-HOST** — with the actual `~/.aws` / `kubectl config` evidence, the three independent inert-by-default mitigations, and the named operator action required before those paths are activated.
3. **NOT-PROVISIONED: the Stripe Connect return/refresh paths have no frontend route** — with the three repo greps that establish it, the note that the failure mode is a 404 UX dead-end (not a correctness defect, since `stripe_connect_status` is updated independently of the redirect), and why building the route is out of scope.

---

## Static gate results

| Gate | Result |
|---|---|
| `kubectl kustomize k8s/base` | **exit 0** |
| `kubectl kustomize k8s/staging` / `k8s/production` | **exit 0** / **exit 0** |
| `k8s/scripts/check-no-plaintext-secrets.sh` | **exit 0** — base 22 resources / production 23 / staging 23, **0 plaintext Secrets**. The only `REPLACE_WITH` in any render is the pre-existing `deployment.timestamp` annotation the gate explicitly excludes (`grep -o 'REPLACE_WITH[A-Z_]*' \| sort -u` → `REPLACE_WITH_DEPLOYMENT_TIMESTAMP` only); the four new placeholder names appear in **0** renders (T-26-11 proven, not assumed). |
| `k8s/scripts/check-connection-math.sh` | **exit 0**, `PASS`, `133 <= 157`; drift guard `DB_POOL_SIZE (10) == application-prod.yml (10)` OK; HPA-memory guard OK. Its `DB_POOL_SIZE` awk parser survived a 26-entry env-block extension. |
| `k8s/scripts/render-golden.sh` | **exit 0** — both renders match their committed goldens (1476 lines each, was 1315). |

**Known-deferred, unchanged from 26-01:** `scripts/docs-freshness.sh` check mode stays RED until plan **26-06** runs the single `--write` reconcile (`docs/metrics.json` 1690 → 1698). `docs/metrics.json` was **not touched** — verified clean in `git status` and absent from `git diff HEAD~3..HEAD`.

## Decisions Made

1. **Production's `configmap-patch.yaml` states the 7 env-varying keys explicitly** even though every value is byte-identical to base. The overlay becomes self-describing, and a future base default change cannot silently move a production email link or a vendor payout redirect. Cost: 7 redundant lines. Worth it for a value class whose whole defect was invisibility.
2. **`smtp.auth` stays `"false"`.** The plan's provenance-vs-provisioning note is load-bearing: this is the single knob that keeps a possibly-unverified SES endpoint from becoming a loud auth failure, and it is byte-identical to today's default.
3. **Media storage and SMTP get no per-overlay override.** Both environments deploy to the same AWS region, so an override would be duplication that can drift.
4. **The `.example` file's comment-block `kubectl create secret` recipe was corrected too**, not just its `stringData`. DEF-2 is a *recipe* defect and that comment is a recipe — leaving it would have preserved a copy-pasteable superuser.
5. **The two legitimate `jtoye` values were annotated rather than left bare.** `rabbitmq-credentials.username` and `backup-username: jtoye_backup` now each carry a comment saying why the DEF-2 correction does not apply, so a later reader does not "complete" the rename and break the broker login (or the BYPASSRLS dump).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Task 3 criterion `grep -cE '^\s+username: "jtoye"\s*$' k8s/base/secrets-template.yaml.example` returns 0 is WRONG as written — satisfying it would have broken the RabbitMQ broker login**

- **Found during:** Task 3 (DEF-2 template correction)
- **Issue:** That token appears **twice** in the file, and only one is the defect. Verified against the pre-change file:
  ```
  $ git show HEAD~2:k8s/base/secrets-template.yaml.example | grep -nE '^\s+username: "jtoye"\s*$'
  41:  username: "jtoye"     # postgres-credentials — THE DEFECT (DB superuser)
  101:  username: "jtoye"    # rabbitmq-credentials — CORRECT (broker user)
  ```
  Driving the count to 0 would have required renaming the RabbitMQ broker user to `jtoye_app` — which directly contradicts 26-01's `PRE-ROLLOUT OPERATOR CHECK`, whose expected live value is `jtoye`, and would have broken AMQP auth in staging and production.
- **Fix:** replaced with a **block-scoped per-document assertion** that separates the two and proves both halves of the intent — the defect changed, the legitimate value did not:
  ```
  BEFORE (HEAD~2)                        AFTER (working tree)
    postgres-credentials  username="jtoye"      postgres-credentials  username="jtoye_app"
    rabbitmq-credentials  username="jtoye"      rabbitmq-credentials  username="jtoye"
                                                smtp-credentials      username="REPLACE_WITH_SES_SMTP_USERNAME"
  ```
  This is strictly stronger than the original: it asserts *which* username changed and *which* did not, where a whole-file count cannot. The other two criteria for this file (`username: "jtoye_app"` = 1, `backup-username: "jtoye_backup"` = 1) pass as written and were run.
- **Files modified:** none beyond the intended edit (verification-only correction)
- **Committed in:** `9bcea26`

**2. [Rule 1 - Bug] Task 3 criterion `grep -c 'from-literal=username=jtoye$' k8s/QUICK_START.md` returns 0 was already 0 pre-change — unfalsifiable**

- **Found during:** Task 3 (DEF-2 recipe correction)
- **Issue:** every `--from-literal=` line in that recipe ends with a shell line-continuation ` \`, so the `$`-anchored pattern never matched, in either direction. Measured on the pre-change file:
  ```
  $ grep -c 'from-literal=username=jtoye$'  k8s/QUICK_START.md   # pre-change
  0                                                              # the "pass" value, before any fix
  $ grep -c 'from-literal=username=jtoye'   k8s/QUICK_START.md   # unanchored, pre-change
  2                                                              # postgres (defect) + rabbitmq (correct)
  ```
- **Fix:** same block-scoped treatment — attribute each `--from-literal=username=` to the `kubectl create secret generic` command it belongs to:
  ```
  BEFORE: postgres-credentials username=jtoye      | rabbitmq-credentials username=jtoye
  AFTER:  postgres-credentials username=jtoye_app  | rabbitmq-credentials username=jtoye
  ```
  The companion criterion (`from-literal=username=jtoye_app` = 1) is genuinely falsifiable and passes.
- **Files modified:** none beyond the intended edit
- **Committed in:** `9bcea26`

**3. [Rule 1 - Bug] `kubectl kustomize <overlay> | grep -c 'localhost'` == 0 is a valid non-regression guard but NOT a proof that D-19 is fixed**

- **Found during:** Task 2 (staging/production overrides)
- **Issue:** it was **already 0** on the pre-change render, because the loopback defaults never lived in the manifests at all — they live in `application.yml` and were reached precisely *because* the render supplied nothing:
  ```
  $ grep -c 'localhost' k8s/goldens/.pre/26-02/production.yaml   0
  $ grep -c 'localhost' k8s/goldens/.pre/26-02/staging.yaml      0
  ```
  So the criterion cannot distinguish "defect present" from "defect fixed". It is still worth running (it catches a loopback literal introduced *by my own additions*), and it passes — but on its own it proves nothing about D-19.
- **Fix:** added a strictly stronger falsifiable assertion — the **env-name → configMapKeyRef-key → rendered-value chain**, measured pre (snapshot) vs post:
  ```
  env name                              pre  post   -> key                              rendered value
  NOTIFICATION_UNSUBSCRIBE_BASE_URL      0    1     notification.unsubscribe.base-url    https://app.jtoye.co.uk
  NOTIFICATION_EMAIL_TRACKING_BASE_URL   0    1     notification.email.tracking-base-url https://app.jtoye.co.uk
  STRIPE_CONNECT_RETURN_URL              0    1     stripe.connect.return-url            https://app.jtoye.co.uk/dashboard/payments/connect/return
  STRIPE_CONNECT_REFRESH_URL             0    1     stripe.connect.refresh-url           https://app.jtoye.co.uk/dashboard/payments/connect/refresh
  CORS_ALLOWED_ORIGINS                   0    1     cors.allowed-origins                 https://app.jtoye.co.uk
  JWT_EXPECTED_ISSUER                    0    2     keycloak.public.issuer.uri           https://auth.jtoye.co.uk/realms/jtoye-prod
  KEYCLOAK_ISSUER_INTERNAL               0    1     keycloak.issuer.uri                  (frontend, pod-reachable half)
  ```
  (`JWT_EXPECTED_ISSUER` is 2 because both core-java and edge-go carry it — correct.) This is falsifiable in both directions and anchored to the named snapshot.
- **Files modified:** none (verification-only)
- **Committed in:** `f15461f` (evidence recorded here)

**4. [Rule 3 - Blocking] Reworded two comments so `grep -c 'optional: true'` returns the specified 7**

- **Found during:** Task 1
- **Issue:** my first draft explained the flag in prose using the literal token, so the count was **9** (7 real + 2 in comments) against a criterion of 7. Identical class to 26-01's deviation 3: a literal grep tripping on prose, not on code.
- **Fix:** the comments now say "the secretKeyRef `optional` flag" / "the `optional` flag". No meaning lost — arguably clearer, since it names the field rather than a YAML fragment. Count is now 7 in the file **and** 7 in the render.
- **Files modified:** `k8s/base/core-java-deployment.yaml`
- **Committed in:** `08b91b4`

**5. [Rule 2 - Missing critical] Corrected the `.example` file's comment-block `kubectl create secret` recipe as well**

- **Found during:** Task 3
- **Issue:** the plan's action text names only `postgres-credentials`'s `stringData` username, but the same file's "How to create secrets in production" comment (pre-change line ~110) carried `--from-literal=username=jtoye` — a second copy-pasteable superuser recipe. DEF-2 is defined in `26-CONTEXT.md` as a *recipe/template* defect, so leaving it would have left the defect half-fixed in the very file being fixed.
- **Fix:** changed to `username=jtoye_app` with a two-line rationale above it.
- **Files modified:** `k8s/base/secrets-template.yaml.example`
- **Committed in:** `9bcea26`

**Total deviations:** 5 auto-fixed — 3 broken/unfalsifiable acceptance criteria replaced with strictly stronger falsifiable forms, 1 blocking comment-wording conflict, 1 missing-half completion. **No scope creep:** no file outside the plan's `files_modified` was touched, and no behaviour differs from what the plan specified. Three of the five are the anti-false-green class, caught by *running* the assertions rather than assuming them — the same failure mode 26-01 hit twice.

## Issues Encountered

- **API stream stall mid-Task-3.** The run was cut off between Task 3's file edits and its verification sweep. On resume the working tree was re-inspected with `git status` + full `git diff` on both modified files **before** continuing, confirming all four template edits and all four QUICK_START edits were already on disk; nothing was redone and nothing was assumed. All Task 3 acceptance checks reported here were run *after* the resume, against the on-disk state, except the four noted below.
- **Verified before the stall and NOT re-confirmed after** (all four are pure-read assertions over files that the stall could not have changed, and each was re-covered by an equivalent post-resume check): the pre-change `git show HEAD~2` block-scoped username extracts (re-derivable at any time, and the AFTER half was re-run post-resume); the `.gitleaks.toml` path-allowlist read (`k8s/base/secrets-template.yaml.example` is allowlisted at line 15); the pre-change `grep -c 'from-literal=username=jtoye$'` = 0 measurement; and the pre-change snapshot `localhost` counts (both 0). The load-bearing gate results — `check-no-plaintext-secrets.sh`, `check-connection-math.sh`, `render-golden.sh`, all three `kubectl kustomize` builds, and the full golden diff — were **all run after the resume**.
- **`python3` / `yq` unavailable** for authoritative YAML parsing (as in 26-01), so the golden diff attribution and the selector-identity proof were built in awk. My first attribution pass mislabelled the 19 ConfigMap `data:` additions as `Namespace`, because `kubectl kustomize` emits top-level document keys alphabetically (`apiVersion`, `data`, `kind`, `metadata`) so `data:` precedes its own `kind:` line and a "last-seen kind" scan attributes it to the previous document. Caught it because the label was implausible, rewrote the attribution to walk documents (`---` boundaries) instead of tracking the last `kind:` line, and re-ran — giving the correct `ConfigMap/app-config +19`. Recorded because it is the same forward-scan trap 26-01 documented, in a new guise.

## Constraint compliance

- **Static side of the static/live split respected.** No `minikube start`, no compose container stopped, no DB role / bucket / Secret created, no `kubectl apply`. Only `kubectl kustomize` (pure local render), a local kubeconfig read (`config get-contexts`), and local file reads. Every mutation remains plan 26-07's, behind its human-action checkpoint.
- **No value invented.** All 19 base values trace to a named in-repo source (table above), every placeholder env name was grep-confirmed against `application*.yml` before use, and the four values whose *provisioning* could not be confirmed are recorded as `UNVERIFIABLE-FROM-THIS-HOST` / `NOT-PROVISIONED` with a deferred item — never as "looks right".
- **`docs/metrics.json` untouched** — 26-06 is its single writer this phase (documented cross-branch merge-conflict hotspot). Verified clean in `git status` and absent from `git diff HEAD~3..HEAD`.
- **Goldens regenerated with `--write` (the arbiter, never hand-edited) and committed in the same change** as the base edits that changed them.
- **`k8s/PRODUCTION_READINESS_REPORT.md` untouched** (`git diff --quiet` true) — it is a dated signed audit; 26-06 owns its appended note.
- **Golden-snapshot convention honoured.** Every golden assertion anchors to the named `26-02` snapshot, taken before the first edit; the forbidden `diff <(git show HEAD~1:<f> … || cat <f>) <f>` form appears nowhere.
- **Sequential-executor rules honoured:** main working tree, branch `feature/phase-26-local-k8s-overlay` throughout, normal commits with hooks (no `--no-verify`), no `git stash`, no branch switch, no worktree.

## Threat model disposition

| Threat | Disposition | Evidence |
|---|---|---|
| T-26-06 (EoP / Info disclosure — superuser in docs+template) | **mitigated** | Both the recipe and the template (including its comment-block recipe) name `jtoye_app`; block-scoped per-document proof shows `postgres-credentials` changed and `rabbitmq-credentials` did not. `DatabaseConfigurationValidator` remains the fail-fast enforcement point; 26-07 proves it live. |
| T-26-07 (Spoofing — expected issuer) | **mitigated** | `keycloak.public.issuer.uri` is byte-equal to `keycloak.issuer.uri` (string compare, above), so **no environment's `iss` validation is widened**; each overlay pins its own realm URL (asserted per overlay). `JWT_EXPECTED_AUDIENCE` = `core-api`, identical to the default, and `AudienceValidator` throws at construction on a blank value so it cannot silently no-op. |
| T-26-08 (Info disclosure — CORS) | **mitigated** | Base and both overlays pin a single origin derived from that overlay's own `frontend.url`; no wildcard anywhere. Asserted per overlay by exact-string grep; `grep -c localhost` on both renders = 0. |
| T-26-09 (Cryptography — unsubscribe HMAC) | **mitigated** | `NOTIFICATION_UNSUBSCRIBE_SECRET` now wired from `notification-credentials`; documented in the recipe and template, both flagging that the `application.yml` default is the EMPTY STRING (an HMAC over an empty key). `optional: true` keeps it inert-by-default rather than silently weak-by-default. **Note for 26-03: do NOT allowlist this away as "accepted".** |
| T-26-10 (DoS — new secretKeyRefs) | **mitigated** | All 7 new refs are `optional: true` (7 in the file, 7 in the render), so a namespace without the Secret keeps today's behaviour instead of `CreateContainerConfigError`. The 2 pre-existing STOMP refs deliberately keep no flag, and QUICK_START's note about that was reworded rather than deleted. |
| T-26-11 (Info disclosure — new placeholder material) | **mitigated, proven** | The four new Secret shapes live only in `secrets-template.yaml.example` (gitleaks path-allowlisted, `.gitleaks.toml:15`) and kustomize never renders it: the four new placeholder names appear in **0** of the three renders, and the only `REPLACE_WITH` in any render is the pre-existing non-secret `deployment.timestamp` annotation the gate excludes. `check-no-plaintext-secrets.sh` exit 0. |
| T-26-62 (DoS / Info disclosure — possibly-unprovisioned external targets) | **mitigated, with recorded outcomes** | Three-way mitigation verified in the render: `smtp.auth: "false"` (1), all 7 credential refs `optional: true` (7). Per-value outcomes recorded (4 × `UNVERIFIABLE-FROM-THIS-HOST` with the actual no-credentials evidence, 2 × `NOT-PROVISIONED` with three repo greps), and both NOT-PROVISIONED values carry a dated deferred item. No value recorded as "looks right". |
| T-26-SC (supply chain) | **n/a** | Zero packages installed — YAML + Markdown only. |

**Other quality contracts:** web performance **N/A** (no user-facing page, route or bundle changed); SEO **N/A** (no public/unauthenticated surface changed); AI agent-readiness **N/A** (no endpoint, contract or OpenAPI change).

**Threat flags:** none. No new network endpoint, auth path, file-access pattern or schema change was introduced at a trust boundary. The `s3`/`smtp` values *retarget* an existing outbound path rather than creating one, and that is captured as T-26-62 in the register above rather than as a new surface.

## Known Stubs

None. This plan ships YAML manifest and Markdown documentation changes only — no placeholder value reaches a UI, no empty-collection default, no unwired data source. The `REPLACE_WITH_*` strings in `secrets-template.yaml.example` are the file's established, gitleaks-allowlisted convention for a reference template that kustomize never renders (proven: 0 occurrences in all three renders).

## User Setup Required

None for this plan's execution. **Operator actions carried forward** (recorded in `deferred-items.md`, and to be surfaced by 26-06's dated `PRODUCTION_READINESS_REPORT.md` note alongside 26-01's RabbitMQ check):

1. Before creating `smtp-credentials` or flipping `smtp.auth` to `"true"` in any environment: confirm the SES sending domain is verified in `eu-west-2`. Until then the email channel stays inert.
2. Before creating `s3-media-credentials`: confirm the `jtoye-images` bucket exists in `eu-west-2`. Until then no S3 write is attempted.
3. Use `username=jtoye_app` (the `.env` `DB_USER` role) when creating `postgres-credentials` — the recipe now says so, but any *already-created* Secret in a live namespace still holds the old value and must be corrected, or core-java will refuse to boot.

## Next Phase Readiness

**Ready.** `CORS_ALLOWED_ORIGINS` — plan 26-07's hard prerequisite for the KDS WebSocket proof — is supplied and rendered in all three targets, reaching the same property `WebSocketConfig` and `CorsConfig` both read.

Notes for the plans that build on this:

- **26-03** (env-contract gate, single writer for `ci-cd.yaml`): direction (a) will now find 26 more injected names, all read by `application*.yml` (each was grep-confirmed before wiring). The allowlist needs entries **with reasons** for `OLLAMA_URL`, `ZIPKIN_ENDPOINT` and the `CUSTOMER_*` realm envs — all three are deliberately omitted here and named as such in an in-manifest comment. `NOTIFICATION_UNSUBSCRIBE_SECRET` must **not** be allowlisted as accepted (T-26-09). 26-03 also owns wiring `render-golden.sh` into `k8s-validate`.
- **26-04** (ingress host cleanup + its own base edits): must take its **own** `--snapshot` label; `26-02` is now consumed and a stale label is refused with exit 1. The goldens are at 1476 lines each.
- **26-05** (`.env` keys + `k8s-local-up.sh`): the `DB_BACKUP_PASSWORD` / `POSTGRES_BACKUP_PASSWORD` naming reconcile is documented in `k8s/QUICK_START.md`; both feed the same `backup-password` Secret key. `k8s-local-up.sh` must pass `--build-arg NEXT_PUBLIC_API_URL=...` (from `app-config/api.url`) — with the runtime injection now gone, that build arg is the *only* path for that value.
- **26-06** (docs): owns `docs/metrics.json` (1690 → 1698, unchanged by this plan) and the dated `PRODUCTION_READINESS_REPORT.md` note. Three operator actions above should land in it.
- **26-07** (live): the local overlay must patch `keycloak.public.issuer.uri` (browser-facing) and `keycloak.issuer.uri` (`host.minikube.internal:8085`) to the split values — both keys now exist to patch, which is the point of D-13.

**Concerns:**

- `docs-freshness` check mode stays RED until 26-06. Expected; do not hand-fix.
- The two `NOT-PROVISIONED` Stripe Connect URLs point at routes that do not exist. Correct to ship (a platform 404 beats a loopback connection-refused) but it is a live UX dead-end until a payments dashboard surface is built.
- Any new overlay (e.g. `k8s/local` in 26-05) inherits the 19 base keys automatically, including the AWS S3/SES endpoints — a local overlay will want to patch `s3.endpoint` / `s3.public-url` to host MinIO, or media upload will target real S3.

## Self-Check: PASSED

All 11 files (1 created, 10 modified) exist on disk; all 3 commits (`08b91b4`, `f15461f`, `9bcea26`) resolve in `git log`. `git diff --stat HEAD~3..HEAD` shows exactly those 11 files and nothing else.

| must_haves truth | Proof |
|---|---|
| Every core-java config value whose Spring default is a local-only literal is supplied by a manifest or recorded as a reviewed omission (D-15) | 19 keys present (loop printed nothing) + 26 env entries present (loop printed nothing); env count 23 → **49** with 0 removed; the 4 omissions (`OLLAMA_URL`, `ZIPKIN_ENDPOINT`, 2 × `CUSTOMER_*`) are grep-confirmed absent (**0**) and named as reviewed omissions in an in-manifest comment for 26-03 to allowlist |
| A production email carries an unsubscribe link and a tracking URL pointing at the platform origin, not a loopback dev origin (D-19) | env-chain proof: `NOTIFICATION_UNSUBSCRIBE_BASE_URL` and `NOTIFICATION_EMAIL_TRACKING_BASE_URL` each **0 → 1** in the production render, each linked to its key, each rendering `https://app.jtoye.co.uk`; staging renders `https://app-staging.jtoye.co.uk` |
| A vendor finishing Stripe Connect onboarding is returned to the platform origin (D-19) | env-chain proof: both `STRIPE_CONNECT_{RETURN,REFRESH}_URL` **0 → 1**, rendering `https://app.jtoye.co.uk/dashboard/payments/connect/{return,refresh}`. **Caveat reported, not hidden:** that route does not exist (`NOT-PROVISIONED`, three repo greps + deferred item) — the vendor now lands on the platform and gets a 404 instead of a loopback connection-refused |
| core-java validates `iss` against a configurable public issuer independent of the JWKS host, in every k8s environment (D-13) | `JWT_EXPECTED_ISSUER` → `keycloak.public.issuer.uri` (**0 → 2** in the production render: core-java + edge-go), `KC_ISSUER_URI` still → `keycloak.issuer.uri`; base values byte-equal (string compare **BYTE-EQUAL: YES**) so no environment's validation loosens; each overlay renders its own realm URL (grep = 1) |
| The frontend pod gets a pod-reachable Keycloak base for server-side token exchange and a browser-reachable issuer for the authorize redirect | rendered base: `KEYCLOAK_ISSUER` → `key: keycloak.public.issuer.uri` (**1**), `KEYCLOAK_ISSUER_INTERNAL` → `key: keycloak.issuer.uri` (**1**), `KEYCLOAK_ISSUER_INTERNAL` **0 → 1** vs snapshot; `NEXTAUTH_URL` preserved (**1**). No `auth.ts` change needed or made (both Auth.js discovery call sites are gated and `auth.ts:55-59` sets all three endpoints) |
| No manifest injects an env Next.js cannot read at runtime, and the reason is documented where the dead config used to be (D-18) | `- name: NEXT_PUBLIC_API_URL` in `frontend-deployment.yaml` = **0**; `grep -c 'BUILD time'` = **1**; `^\s+- name: NEXT_PUBLIC_` = **0** in all three renders and in every tracked `k8s/**` manifest (the 4 remaining file matches are all in the gitignored, untracked `k8s/goldens/.pre/` transient snapshots — confirmed by `git ls-files`) |
| The secret-creation recipe specifies the NOSUPERUSER `jtoye_app` role | `from-literal=username=jtoye_app` in `QUICK_START.md` = **1**; template `username: "jtoye_app"` = **1**; block-scoped per-document proof shows `postgres-credentials` `jtoye` → `jtoye_app` while `rabbitmq-credentials` stays `jtoye`; `backup-username: "jtoye_backup"` preserved (**1**); the `.example` comment-block recipe corrected too |
| The staging and production renders change only by the intended additions — no existing value's behaviour changes | `--diff-since 26-02`: `resolve_exit=0`, `test -s` TRUE, **+166 / −5 per target**; every line attributed per document (ConfigMap +19, core-java +137, edge-go +5, frontend +5/−5, **0 elsewhere**) with the counts reconciled arithmetically; **all 10 removed lines in the whole diff are the one intentional D-18 block**; the 9 Deployment/Service/PDB selector blocks **BYTE-IDENTICAL** to the snapshot, so no immutable field moved |

**Nothing is claimed as proven that was not run.** Three acceptance criteria were unfalsifiable or wrong as written and are reported as such above, each with the measurement that shows it and a strictly stronger replacement. Six values carry an explicit `UNVERIFIABLE-FROM-THIS-HOST` / `NOT-PROVISIONED` outcome rather than a claimed verification.

---
*Phase: 26-local-k8s-overlay-verified-breakage-fixes*
*Plan: 02*
*Completed: 2026-07-25*
