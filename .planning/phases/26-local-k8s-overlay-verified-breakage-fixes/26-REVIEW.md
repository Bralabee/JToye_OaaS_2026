---
phase: 26-local-k8s-overlay-verified-breakage-fixes
reviewed: 2026-07-26T00:00:00Z
depth: standard
files_reviewed: 29
files_reviewed_list:
  - scripts/lib/k8s-local-guards.sh
  - scripts/k8s-local-secrets.sh
  - scripts/k8s-local-up.sh
  - scripts/deploy.sh
  - k8s/scripts/check-env-contract.sh
  - k8s/scripts/check-render-invariants.sh
  - k8s/scripts/render-golden.sh
  - k8s/base/configmap.yaml
  - k8s/base/core-java-deployment.yaml
  - k8s/base/edge-go-deployment.yaml
  - k8s/base/frontend-deployment.yaml
  - k8s/base/ingress.yaml
  - k8s/base/kustomization.yaml
  - k8s/base/pg-backup-cronjob.yaml
  - k8s/base/secrets-template.yaml.example
  - k8s/local/kustomization.yaml
  - k8s/local/configmap-patch.yaml
  - k8s/local/ingress-patch.yaml
  - k8s/local/sse-ingress-patch.yaml
  - k8s/local/scale-patch.yaml
  - k8s/local/namespace.yaml
  - k8s/staging/kustomization.yaml
  - k8s/staging/configmap-patch.yaml
  - k8s/production/kustomization.yaml
  - k8s/production/configmap-patch.yaml
  - .github/workflows/ci-cd.yaml
  - core-java/src/main/resources/application.yml
  - core-java/src/test/java/uk/jtoye/core/config/StompCredentialResolutionTest.java
  - frontend/e2e/stomp-relay.spec.ts
  - infra/keycloak/realm-export.template.json
  - .env.example
findings:
  critical: 2
  warning: 9
  info: 8
  total: 19
status: issues_found
---

# Phase 26: Code Review Report

**Reviewed:** 2026-07-26
**Depth:** standard
**Files Reviewed:** 29 (+ cross-referenced: `k8s/base/networkpolicies/*`, `k8s/base/sse-ingress.yaml`, `frontend/Dockerfile`, `frontend/middleware.ts`, `frontend/lib/security-headers.ts`, `core-java/src/main/java/uk/jtoye/core/ai/ImageAnalysisService.java`, `docker-compose.full-stack.yml`, `infra/backups/create-backup-role.sql`)
**Status:** issues_found

## Summary

This is a careful, unusually well-reasoned infra phase. All five gate scripts run green locally
(`check-env-contract.sh` PASS, `check-render-invariants.sh` PASS across 4 targets, `render-golden.sh`
PASS on both goldens), and I falsified the headline invariant (INV-6) by restoring the dangling
Keycloak ingress rule — base/staging/production all correctly FAIL while `k8s/local` stays OK, exactly
as the header claims. The SIGPIPE-under-`pipefail` fixes, the `getent || true` fix, the
`minikube profile list` status literal fix, and the fail-closed-on-empty guards are all genuinely
present and correct. `.env.example` and `secrets-template.yaml.example` contain no real secret
material. The realm redirect URI addition is host-scoped and is not an open redirect. The STOMP
credential chain is additive and cannot resolve to `guest` in k8s (both `STOMP_CLIENT_*` refs are
non-`optional` `secretKeyRef`s, so a missing key is a loud `CreateContainerConfigError`, not a silent
fallback).

The defects below are the places where that rigour stopped one step short.

Two are blocking: (1) the five new gates are wired into `k8s-validate`, which is **not** in
`build-and-push`'s `needs:` list, so nothing in the deploy path is gated by them; and (2) the
`NEXT_PUBLIC_API_URL` deletion analysis is correct about inlining but the shipped consequence is a
CI-built frontend whose enforced CSP omits both the API and Keycloak origins — and `app-config:
api.url` is now a ConfigMap key with **zero consumers in any render**, which is precisely the DEF-6
shape this phase set out to eliminate.

I also **proved** INV-1 is evadable: with `DB_PORT` regressed to a single-quoted literal
`value: '5432'`, all of `check-render-invariants.sh` and `check-env-contract.sh` still exit 0.

## Critical Issues

### CR-01: The five new k8s gates do not gate the deploy path — `build-and-push` omits `k8s-validate`

**File:** `.github/workflows/ci-cd.yaml:213` (job `k8s-validate`), `.github/workflows/ci-cd.yaml:437` (`needs:`)

**Issue:** The job itself is correct — each step is `run: |` under GitHub's default `bash -e {0}`,
there is no `|| true` and no `continue-on-error`, so a non-zero gate script fails the job. But:

```yaml
build-and-push:
  needs: [test, integration-tests, security-scan]     # line 437 — k8s-validate absent
deploy-staging:   needs: [build-and-push]
deploy-production: needs: [build-and-push]
```

`k8s-validate` gates nothing. On a push to `main` with `vars.DEPLOY_ENABLED == 'true'`,
`deploy-production` runs `kubectl apply -k k8s/production` on a commit whose
`check-render-invariants.sh` / `render-golden.sh` / `check-env-contract.sh` are simultaneously **red**.
The whole deliverable of plan 26-03 ("a one-time fix without a gate is a fix that returns") is defeated
on the one path where a returning defect actually reaches a cluster. The same hole applies to `lint`,
`openapi-compat` and `mcp-server-tests`.

**Failure scenario:** A PR reintroduces the kube-dns `includeSelectors: true` label poisoning (D-17).
`k8s-validate` goes red. If branch protection does not list it as a required check — and nothing in
this repo makes that verifiable — the PR merges, `build-and-push` runs (its three `needs` are green),
and `deploy-production` applies a NetworkPolicy that gives core-java zero DNS egress. Total outage,
with the gate that was built to prevent it sitting red in the same workflow run.

**Fix:**
```yaml
  build-and-push:
    name: Build and Push Images
    needs: [test, integration-tests, security-scan, k8s-validate, lint, openapi-compat, mcp-server-tests]
```
At minimum add `k8s-validate`. The gates are client-side and fast (no cluster access), so this costs
nothing in wall-clock on the critical path.

---

### CR-02: `app-config: api.url` has no consumer in any render; the CI-built frontend ships a CSP that blocks its own API

**File:** `k8s/base/frontend-deployment.yaml:49-81` (the D-18 deletion block), `k8s/base/configmap.yaml:20`, `.github/workflows/ci-cd.yaml:479-493`

**Issue:** The D-18 analysis ("a runtime `env:` entry here reached NOTHING") is **correct** — I verified
it. `frontend/Dockerfile:22-23` is `ARG NEXT_PUBLIC_API_URL` + `ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}`,
so the variable **always exists** at build time (empty string when no `--build-arg` is passed), which
means Next.js's `DefinePlugin` inlines it into *every* bundle. Proof from the built artifact:

```
$ grep -o "NEXT_PUBLIC_[A-Z_]*" '.next/server/edge/chunks/[root-of-the-server]__0-6e10c._.js'
   (no output — zero surviving references)
$ grep -o 'apiOrigin:"[^"]*"'  '.next/server/edge/chunks/[root-of-the-server]__0-6e10c._.js'
apiOrigin:"http://localhost:9090"
```

So the deletion is safe. What the phase did **not** follow through is the consequence. `build-and-push`
(lines 479-493) passes **no** `build-args`, so the staging/production image bakes `apiOrigin: ""` into
`frontend/middleware.ts`, and `frontend/lib/security-headers.ts:53` emits:

```
connect-src 'self' https://api.stripe.com https://*.stripe.com   
```

`'self'` is `app.jtoye.co.uk`; `api.jtoye.co.uk` is a different origin. `CSP_REPORT_ONLY` is unset in
k8s, so the policy is **enforcing**. Every dashboard XHR, every SSE stream open and the KDS
`wss://api.../ws` handshake are blocked by the browser. `form-action` loses the Keycloak origin the
same way.

Meanwhile `api.url` (`configmap.yaml:20`) is now referenced by **nothing** in any render — I
cross-checked every `configMapKeyRef.key` in all four renders against the rendered `app-config` data:

```
--- in app-config but never referenced ---
api.url
log.level
rabbitmq.port
redis.port
sql.log.level
```

Only `scripts/k8s-local-up.sh` consumes `api.url`, and only for the *local* build arg. The comment at
`frontend-deployment.yaml:69` — "app-config/api.url REMAINS the source of truth" — is true for local
and false for staging/production.

**Failure scenario:** `vars.DEPLOY_ENABLED=true`, push to main. Pods go READY, `kubectl rollout status`
passes, `smoke-test.sh` (server-side curl, no browser) passes. A vendor opens
`https://app.jtoye.co.uk/dashboard`: the page shell renders, every data fetch is refused by CSP, and the
console shows `Refused to connect to 'https://api.jtoye.co.uk/...'`. No CI signal fires.

**Fix:** Either bake per-environment values in CI (add `build-args` to the frontend matrix leg and
accept one image per environment), or make the CSP origins a genuine *runtime* input:

```ts
// frontend/middleware.ts — read a non-NEXT_PUBLIC name so Next cannot inline it
apiOrigin: process.env.API_ORIGIN || "",
keycloakOrigin: process.env.KEYCLOAK_PUBLIC_ORIGIN || "",
```
```yaml
# k8s/base/frontend-deployment.yaml — restores api.url to a real consumer
- name: API_ORIGIN
  valueFrom: { configMapKeyRef: { name: app-config, key: api.url } }
- name: KEYCLOAK_PUBLIC_ORIGIN
  valueFrom: { configMapKeyRef: { name: app-config, key: keycloak.public.issuer.uri } }
```
Whichever is chosen, add the "every `app-config` key has at least one `configMapKeyRef` consumer in the
render" assertion to `check-render-invariants.sh` — it is the same shape as INV-6 and catches WR-04 too.

## Warnings

### WR-01: INV-1 is evadable — the full gate suite passes with DEF-1 fully regressed (PROVEN)

**File:** `k8s/scripts/check-render-invariants.sh:264`

**Issue:** The invariant is a single anchored literal:

```bash
if grep -nE '^[[:space:]]+value: "5432"' "$CORE_DEPLOYMENT"; then
```

It matches only the double-quoted spelling. YAML has at least three equivalent forms, and kustomize
normalises them all to `value: "5432"` in the render — but INV-1 reads the **source**, not the render.

**Demonstrated:** I copied the tree, replaced the `DB_PORT` `secretKeyRef` block with
`value: '5432'` (single quotes), and re-ran the gates:

```
INV-1 ... OK   [k8s/base/core-java-deployment.yaml]: no 'value: "5432"' line
OK   [k8s/base]: INV-2 OK (72 EnvVars, DB_PORT present, 0 with both value+valueFrom) | ... | INV-6 OK
PASS: INV-1..INV-6 hold across 4 kustomize target(s)          exit 0
PASS: 49 injected env names all read ...                       exit 0   (check-env-contract.sh)
```

Both gates green with the defect they exist to pin fully restored. Only `render-golden.sh` flags it, and
only as a diff whose documented remedy is "run `--write` and commit" — i.e. it depends on a human
reading the golden diff, which is exactly the review step DEF-1 already survived once.

**Fix:** Assert on the render (where the form is normalised) and on the *shape*, not a literal:
```bash
# in the per-target loop, alongside INV-2 (envvars.tsv already has has_value/has_valueFrom):
if awk -F'\t' '$1=="DB_PORT" && $3==1' "$TMP/envvars.tsv" | grep -q .; then
    echo "  FAIL [$rel] INV-1: DB_PORT carries a literal 'value:' — it must come from postgres-credentials/port." >&2
    FAILED=1
fi
```
That cannot be evaded by quoting style and it covers every overlay, not just the base source file.

---

### WR-02: `DB_PORT` is now Secret-driven but the NetworkPolicy egress port is still a hardcoded 5432

**File:** `k8s/base/core-java-deployment.yaml:88-92`; cross-ref `k8s/base/networkpolicies/20-core-java.yaml:79`, `k8s/base/networkpolicies/40-datastores.yaml:57`

**Issue:** DEF-1's whole justification is "the port is CONFIG, not a constant … a hardcoded 5432 made
every environment's Postgres port a manifest edit." The Deployment and the CronJob now both read
`postgres-credentials/port`. But the egress allow-list that has to permit the connection still says:

```yaml
- protocol: TCP
  port: 5432   # Postgres
```

The two are coupled and nothing links them. On minikube this is invisible (default CNI does not enforce
NetworkPolicies — the phase says so itself), so the live rehearsal could not have caught it.

**Failure scenario:** An operator exercises exactly the capability this phase advertised — the managed
Postgres moves to 5433, so they update `postgres-credentials/port` (one Secret edit, no manifest edit,
as designed). Under the enforcing CNI that staging/production ship NetworkPolicies for, every core-java
pod's DB connection is now denied by `core-java-allow`; Hikari times out, `DatabaseConfigurationValidator`
never runs, all three replicas CrashLoopBackOff. `pg-backup` fails the same way. The diagnosis points at
Postgres, not at a NetworkPolicy port literal nobody changed.

**Fix:** Either add an invariant that the rendered `DB_PORT` source and the NetworkPolicy egress port
agree, or (simpler and self-maintaining) widen the datastore egress rule to the port range the
`jtoye-infrastructure` namespace actually serves and document it — the namespaceSelector is already the
real scoping control here, not the port number.

---

### WR-03: the D-08 allowlist reason for `OLLAMA_URL` is factually wrong — `ai.enabled` defaults to **true**

**File:** `k8s/scripts/check-env-contract.sh:135`

**Issue:** The reasoned exemption reads:

> "the media vision stage is advisory-only behind `jtoye.media.vision.enabled`, which defaults false …
> leaving the unreachable default keeps the stage inert, which is the intended k8s behaviour."

That accounts for exactly one of three consumers. `application.yml:323-328` is a **separate** flag:

```yaml
ai:
  enabled: ${AI_ENABLED:true}          # <-- true, and no manifest supplies AI_ENABLED
  provider: ${AI_PROVIDER:ollama}
  ollama:
    url: ${OLLAMA_URL:http://localhost:11434}
```

`ImageAnalysisService` sets `this.enabled = enabled` unconditionally on the ollama branch
(`ImageAnalysisService.java:113-121`) — reachability is never checked — and it is injected into
`ProductController` and `BulkImportService`, not just `MediaProcessingWorker`.

**Failure scenario:** In staging/production a vendor calls
`POST /api/v1/products/{id}/image/analyze`. `isEnabled()` returns **true**, so the clean
`503 Service Unavailable` guard at `ProductController.java:186` is skipped. The pod dials
`http://localhost:11434` — its **own loopback** — burns the resilience4j `ai` retry budget, trips the
circuit breaker, and returns `422 Unprocessable Entity` with no diagnostic. The gate that is supposed to
make omissions "a reviewed inventory instead of a surprise" recorded a reason that does not hold.

**Fix:** Supply `AI_ENABLED` explicitly from `app-config` (`ai.enabled: "false"` in base; `"true"` only
where an Ollama actually exists) and correct the allowlist reason to name `ai.enabled` rather than
`jtoye.media.vision.enabled`. Adding `AI_ENABLED` as a manifest-supplied env also removes the need for
the `OLLAMA_URL` exemption to carry the load.

---

### WR-04: four `app-config` keys have no consumer in any render — staging's `log.level: DEBUG` is dead config

**File:** `k8s/base/configmap.yaml:25,29,32,33`; `k8s/staging/configmap-patch.yaml:7-8`

**Issue:** `log.level`, `sql.log.level`, `redis.port` and `rabbitmq.port` exist in `app-config` but no
Deployment or CronJob has a `configMapKeyRef` for them (see the cross-check in CR-02). `application.yml`
*does* read `${LOG_LEVEL:INFO}`, `${SQL_LOG_LEVEL:WARN}`, `${REDIS_PORT:6379}` and `${RABBITMQ_PORT:5672}`
— they simply never receive the ConfigMap value. `check-env-contract.sh` cannot see this: direction (a)
walks env **names** and direction (b) walks Spring placeholders; neither walks ConfigMap **keys**.

This is the same "config that looks configured and reaches nothing" class as DEF-4/DEF-6, and the local
overlay's own header warns about it (`k8s/local/configmap-patch.yaml:12-17`) — but only asserts the key
exists in base, never that it has a consumer.

**Failure scenario A:** An operator debugging a staging incident sees `log.level: DEBUG` in
`k8s/staging/configmap-patch.yaml`, rolls the pods, and gets INFO logs. Hours lost chasing a logging
config that was never wired.
**Failure scenario B (the sharper one):** `k8s/local/configmap-patch.yaml:104-106` states "Ports come
from the base keys (redis.port 6379, rabbitmq.port 5672 …), which already match the compose values, so
only the host changes." If a compose port ever shifts the way Postgres did (5432 → 5433), editing
`redis.port` in the ConfigMap changes nothing and the pod silently keeps dialling 6379.

**Fix:** Wire the four keys into `core-java-deployment.yaml` as `LOG_LEVEL` / `SQL_LOG_LEVEL` /
`REDIS_PORT` / `RABBITMQ_PORT` envs (they are all already read by `application.yml`, so direction (a) of
the env-contract gate will accept them immediately), and add the "every `app-config` key has a
`configMapKeyRef` consumer" invariant proposed in CR-02.

---

### WR-05: `deploy.sh <env> <service>` silently changed meaning — it now deploys everything but only rolls back one

**File:** `scripts/deploy.sh:2-6, 90-91, 108-116`

**Issue:** The usage banner still advertises `./scripts/deploy.sh staging core-java`, but the second
argument no longer scopes the deploy. Previously it selected which manifest was applied
(`kubectl apply -f k8s/base/${svc}-deployment.yaml`). Now the script unconditionally runs
`kubectl apply -k k8s/${ENVIRONMENT}` (line 91) — the whole overlay: three Deployments, the ConfigMap,
both Ingresses, six NetworkPolicies and the CronJob — and `$SERVICE` only picks which rollout to wait on
(line 115). The move to `apply -k` is the right fix for the kustomize-bypass bug; the argument semantics
were not updated with it.

**Failure scenario:** An operator running a targeted production hotfix types
`./scripts/deploy.sh production core-java`. All three services roll simultaneously. Only `core-java`
gets `rollout status` + auto-`rollout undo`; if `frontend` or `edge-go` then fails to become ready, the
script prints `✓ Deployment completed successfully!` and exits 0 with a broken frontend live and no
rollback.

**Fix:** Either wait on and roll back all three regardless of `$SERVICE` (the overlay applied all three
anyway), or drop the argument:

```bash
# after the apply, always:
deploy_service "core-java" || exit 1
deploy_service "edge-go"   || exit 1
deploy_service "frontend"  || exit 1
```
and remove `[service]` from the usage banner and examples.

---

### WR-06: staging runs the `prod` Spring profile, but the staging smoke test asserts Swagger is exposed

**File:** `.github/workflows/ci-cd.yaml:598-602`; `k8s/scripts/check-env-contract.sh:123` (the `SPRING_PROFILES_ACTIVE` allowlist reason)

**Issue:** The direction-(a) allowlist entry states the phase decision explicitly: *"26-CONTEXT.md D-10
keeps every k8s environment on the prod profile."* The render confirms it — `k8s/staging` line 205-206 is
`SPRING_PROFILES_ACTIVE: prod`, and no overlay patches it. So `application-staging.yml` (which enables
Swagger) is never active in the staging namespace; `application-prod.yml:139` `${SWAGGER_ENABLED:false}`
applies, and `SWAGGER_ENABLED` is supplied by no manifest.

The staging deploy job nevertheless asserts the opposite:

```yaml
- name: Run smoke tests
  # Staging exposes Swagger (application-staging.yml) — assert it is reachable.
  run: EXPECT_SWAGGER=true ./scripts/smoke-test.sh https://staging-api.jtoye.co.uk
```

`smoke-test.sh:121` requires `/swagger-ui.html` → 302.

**Failure scenario:** Someone sets `vars.DEPLOY_STAGING_ENABLED=true`. Pods roll out healthily, the
in-cluster `:9091/actuator/health` check passes, then the smoke test fails on Swagger and the
`Rollback on failure` step runs `kubectl rollout undo` on all three Deployments — auto-reverting a
correct release, permanently, every time.

**Fix:** Pick one and make it explicit. Either add `SPRING_PROFILES_ACTIVE: staging` to
`k8s/staging/configmap-patch.yaml` (and a patch on the Deployment env), or drop `EXPECT_SWAGGER=true`
from the staging job and delete the stale comment. Given D-10, the second is the smaller change.

---

### WR-07: INV-6 pins the Ingress backend but not the TLS SAN list — the half with the bigger blast radius

**File:** `k8s/base/ingress.yaml:47-66`; `k8s/scripts/check-render-invariants.sh:76-93`

**Issue:** The `rules:` comment and the INV-6 header both identify the *certificate* consequence as the
serious one: "a failed challenge fails the WHOLE order — so a dangling SAN could block or stall renewal
for api and app too, taking the platform's real certificate down with it." INV-6 asserts only that every
`backend.service.name` resolves to a Service in the render. It does **not** assert that
`spec.tls[].hosts` ⊆ `spec.rules[].host`. `ingress.yaml:61` concedes this ("CI asserts the backend half
of that") — but the unasserted half is the one the comment says is worse.

**Failure scenario:** A future change adds `auth.jtoye.co.uk` back to the `tls.hosts` list without a
matching rule (a plausible half-revert, or a copy-paste when adding a fourth host). INV-6 stays green
because there is no new backend reference. cert-manager includes the SAN in the `jtoye-tls` order, the
HTTP-01 challenge for a hostname whose public DNS points at the managed IdP cannot be answered by this
controller, the order fails, and renewal for `api.jtoye.co.uk` and `app.jtoye.co.uk` stalls behind it.

**Fix:** Add the set assertion to the same per-target loop (the `INGRESS_BACKEND_AWK` walk already
buffers each document, so `tls.hosts` is a few lines away):

```bash
# every host in spec.tls[].hosts must appear as a spec.rules[].host in the SAME Ingress
comm -23 <(sort -u "$TMP/tls_hosts.txt") <(sort -u "$TMP/rule_hosts.txt") > "$TMP/dangling_sans"
[[ -s "$TMP/dangling_sans" ]] && { echo "  FAIL [$rel] INV-7: TLS SAN with no rule: $(tr '\n' ' ' < "$TMP/dangling_sans")" >&2; FAILED=1; }
```

---

### WR-08: the bootstrap runs an unpinned `minio/mc:latest` with MinIO **root** credentials

**File:** `scripts/k8s-local-secrets.sh:217-234` (tag at line 221)

**Issue:**
```bash
docker run --rm --network "$MINIO_NETWORK" \
  -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD \
  ... --entrypoint /bin/sh "minio/mc:${MINIO_MC_IMAGE_TAG:-latest}" -c '...'
```

The default resolves to the mutable `minio/mc:latest`, pulled from Docker Hub at run time, joined to the
compose network, and handed the MinIO **root** username and password via environment. This repo already
treats mutable tags as an audit finding — `.github/workflows/ci-cd.yaml:472-476` documents pinning
deploy images to an immutable full-sha tag precisely because "two deploys of different code could
resolve to the same floating tag" — and `k8s/local/kustomization.yaml:130-132` makes the same argument
for `jtoye-pg-backup:15`. `MINIO_MC_IMAGE_TAG` is not in `.env.example`, so in practice every run is
`latest`.

**Failure scenario:** Docker Hub serves a newer, compromised or merely behaviour-changed `minio/mc:latest`
between two runs of the same committed script. The container receives root credentials for the MinIO
holding this project's media bucket and its database dumps, on a network that also reaches Postgres,
Redis, RabbitMQ and Keycloak. Nothing in the repo records which image actually ran.

**Fix:** Pin a digest and document it beside the `jtoye-pg-backup:15` note:
```bash
readonly MINIO_MC_IMAGE="${MINIO_MC_IMAGE:-minio/mc@sha256:<digest>}"
```
Longer term, scope a bucket-limited MinIO service account for the bootstrap instead of passing root —
`k8s/base/secrets-template.yaml.example:94-96` already prescribes exactly that for the real deployment.

---

### WR-09: remaining SIGPIPE/`pipefail` and unguarded-pipeline instances of the phase's own defect class

**Files:** `scripts/k8s-local-secrets.sh:127, 205-206, 209-211`; `scripts/k8s-local-up.sh:383, 385`; `k8s/scripts/check-render-invariants.sh:928`

**Issue:** The phase correctly converted two `grep -q` pipes to here-strings and added `|| true` to the
`getent` call and to `k8s_local_profile_ip`'s `minikube … | jq`. Six sibling sites were not converted.
All six are `VAR="$(pipeline)"` assignments under `set -euo pipefail`, so a non-zero pipeline aborts the
script **with no message at all** — the exact "destroyed the branch that was supposed to print
instructions" shape recorded as class 3.

Measured on this host (`bash 5`, 64 KiB pipe capacity):

```
printf 154KB | head -1        -> 141 in 7/20 runs   (SIGPIPE, reproducible)
printf 154KB | awk '…;exit'   -> 0 in 30/30 runs    (gawk drains; not reproducing today)
```

So the two `printf '%s' "$RENDER" | awk '…exit'` sites (`k8s-local-up.sh:383,385`) do **not** currently
fire — but they are the identical construct the file's own comment at lines 363-371 says "inverts as a
RACE", left unconverted ten lines below that comment. The three `| head -1` sites are the live class:
`docker inspect --format '…{{"\n"}}…' | head -1` (line 206) emits one line per attached network and
(line 210) one line per published-port binding — Docker routinely publishes both an IPv4 and an IPv6
binding for the same host port, so that template legitimately emits two lines.

Separately, `check-render-invariants.sh:928`:
```bash
loc_hosts=$(grep -E '^[[:space:]]*- host: ' "$TMP/loc_ingress.yaml" | sed … | sort -u | tr '\n' ' ')
```
has no `|| true` — unlike `shim_total` (line 803) and `loc_ing_docs` (line 885), which do. If a local
Ingress ever renders with zero hosts, `grep` exits 1, `pipefail` propagates it, and `set -e` kills the
script with a bare exit 1 *before* LOC-5 can report the violation — so a real LOC-5 failure is
indistinguishable from a tooling crash.

**Failure scenario (line 206):** MinIO is attached to a second Docker network (a second compose project,
a manually attached `docker network connect`). `docker inspect` writes two lines, `head -1` exits after
the first, `docker` takes SIGPIPE → 141 → `MINIO_NETWORK="$(…)"` fails → `set -e` exits 141 **after**
STEP 3 has already created the BYPASSRLS `jtoye_backup` role, with no output. The operator sees a
half-applied bootstrap and no reason — the precise failure mode the "half-bootstrapped cluster is worse
than one that refused to start" comment at lines 74-77 exists to prevent.

**Fix:** Replace every `| head -1` with a reader that cannot signal the writer, and here-string the awk
pipes:
```bash
MINIO_NETWORK="$(docker inspect "$MINIO_CONTAINER" \
  --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{"\n"}}{{end}}' \
  | awk 'NR==1{print; f=1} END{exit !f}')"          # awk drains; no SIGPIPE

API_URL="$(awk '/^[[:space:]]*api\.url:[[:space:]]/{print $2; exit}' <<<"$RENDER")"

loc_hosts=$( { grep -E '^[[:space:]]*- host: ' "$TMP/loc_ingress.yaml" || true; } | sed … | sort -u | tr '\n' ' ')
```

## Info

### IN-01: `keycloak.admin.base-url` contradicts INV-6's "Keycloak is an external managed IdP" premise

**File:** `k8s/base/configmap.yaml:16`
INV-6's rationale and `k8s/base/ingress.yaml:76-82` both assert Keycloak is external in staging and
production ("there is no Service to add"). Yet `keycloak.admin.base-url` points at
`http://keycloak.jtoye-infrastructure.svc.cluster.local:8080` — an in-cluster Service that, by the same
argument, does not exist. It is inert only because `keycloak.admin.enabled: "false"`. An operator
flipping that flag to deprovision an offboarded tenant's users gets a silent best-effort failure
(`keycloak_deprovisioned_at` stays NULL, one ERROR log). Either point it at the managed IdP's admin
endpoint or state in the comment why it is deliberately unreachable today.

### IN-02: two Ingresses claim the same `secretName: jtoye-tls` with divergent SAN lists

**File:** `k8s/base/ingress.yaml:62-66`; cross-ref `k8s/base/sse-ingress.yaml:47-51`
`jtoye-ingress` requests `[api, app]`; `jtoye-sse-ingress` requests `[api]` — both into `jtoye-tls`, both
annotated `cert-manager.io/cluster-issuer: letsencrypt-prod`. cert-manager's ingress-shim names the
`Certificate` after the secret and owns it from one Ingress, so the second produces recurring
ownership-conflict events. The phase's TLS reasoning treats `jtoye-ingress`'s list as the single SAN
source of truth without accounting for the second claimant. Drop the `cert-manager.io/cluster-issuer`
annotation and the `tls:` block from `sse-ingress.yaml` (the cert `jtoye-ingress` orders already covers
`api.jtoye.co.uk`) or give the SSE Ingress its own `secretName`.

### IN-03: `k8s-local-secrets.sh` run standalone skips the cluster-XOR guard

**File:** `scripts/k8s-local-secrets.sh:70-72`
The header documents `scripts/k8s-local-secrets.sh` as directly runnable, but it calls only
`k8s_local_load_env` / `assert_context` / `assert_compose_xor` — not `k8s_local_assert_cluster_xor`. A
standalone run therefore creates the BYPASSRLS role, the bucket and every Secret against a cluster that
may already carry a stale writer namespace. Add the fourth guard, or state in the header that standalone
use is only valid after `k8s-local-up.sh` has passed step 3b.

### IN-04: the local overlay ships `pg-backup` unsuspended, and the local namespace is XOR-exempt

**File:** `k8s/local/kustomization.yaml:147-151`
`scale-patch.yaml` covers HPAs and PDBs but nothing suspends the CronJob, so a `jtoye-local` namespace
runs a nightly 02:00 UTC full dump of the **shared** dev Postgres into host MinIO for as long as the
profile exists. `k8s_local_assert_cluster_xor` deliberately exempts the expected local namespace, so a
forgotten rehearsal namespace is invisible to the very guard added because a restored namespace's
`pg-backup` CronJob "fired on start". Consider `spec.suspend: true` in the local patch, enabled
explicitly for the #101 restore rehearsal.

### IN-05: `deploy.sh` pod-status listing uses a label that never matches

**File:** `scripts/deploy.sh:120`
`kubectl get pods -n "$NAMESPACE" -l app="$SERVICE"` with the default `SERVICE=all` selects
`app=all` — no resources found. The final status block of every full deploy prints nothing useful. Drop
the `-l` when `$SERVICE = all`.

### IN-06: gate discovery and INV-5's superuser name are both silently narrow

**File:** `k8s/scripts/check-render-invariants.sh:235, 252`
`find "$K8S_DIR" -maxdepth 2 -name kustomization.yaml` misses any future nested overlay
(`k8s/overlays/<env>/`), which would then be ungated by INV-2/3/4/6 with no signal. `DB_SUPERUSER_ROLE="jtoye"`
is a hardcoded live fact; renaming the superuser makes INV-5 an assertion that can no longer fail, and
nothing detects that. Both are worth a one-line staleness note or a `-maxdepth 3` widen.

### IN-07: `FORBIDDEN_RENDER_LITERALS` is narrower than the env-contract gate's `LOCAL_ONLY_WORDS`

**File:** `k8s/scripts/check-render-invariants.sh:238-242` vs `k8s/scripts/check-env-contract.sh:141-149`
INV-4 forbids `localhost`, `127.0.0.1`, `minioadmin`. The env-contract gate's list also carries
`0.0.0.0`, `guest`, `mailhog` and `host.docker.internal` — and neither list carries
`host.minikube.internal`, so a local shim leaking into `k8s/staging/configmap-patch.yaml` would pass
INV-4 entirely. The two lists guard the same defect class from opposite ends; keep them in one place.

### IN-08: `stomp-relay.spec.ts` contains an assertion that cannot fail

**File:** `frontend/e2e/stomp-relay.spec.ts:176`
`expect(wsConnections.length).toBeGreaterThanOrEqual(0)` is true for every possible array. The test
named "kitchen display page loads and connects to WebSocket" therefore proves only that the heading
rendered — it makes no statement about the WebSocket at all. Pre-existing, but the file was edited in
this phase (PIT-9 cookie-domain fix) and the phase's own doctrine is that an assertion which cannot
detect a violation is worse than no assertion. `expect(wsConnections.some(u => u.includes("/ws"))).toBe(true)`
is the assertion the test name claims.

---

## Verified clean

The following were checked specifically and found sound — recorded so the absence of a finding is a
statement, not an omission:

- **`.env.example`** — no real secret values; `DB_BACKUP_PASSWORD=CHANGE_ME` (and
  `k8s-local-secrets.sh:105-110` refuses that placeholder), `NOTIFICATION_UNSUBSCRIBE_SECRET=` empty.
- **`k8s/base/secrets-template.yaml.example`** — every credential is a `REPLACE_WITH_*` placeholder; the
  only literal values are role/user *names* (`jtoye_app`, `jtoye_backup`, `admin`, `jtoye`), and INV-5
  correctly block-scopes the superuser assertion to `postgres-credentials` so the legitimate RabbitMQ
  `jtoye` does not false-positive.
- **`infra/keycloak/realm-export.template.json`** — the added `http://app.jtoye.local/*` redirect URI is
  host-scoped, matches the existing `/*` convention on the same client, is additive (all four localhost
  URIs retained), and lives in a dev-realm template that no k8s environment imports. Not an open redirect.
- **STOMP credential chain** (`application.yml:246-249` + `StompCredentialResolutionTest`) — the nested
  fallback is correct and cannot silently reach `guest` in k8s: both `STOMP_CLIENT_LOGIN` and
  `STOMP_CLIENT_PASSCODE` are non-`optional` `secretKeyRef`s
  (`core-java-deployment.yaml:226-235`), so a missing Secret key is `CreateContainerConfigError`, not a
  fallback. The test's eight cases cover all three precedence levels and the four level-1 cases are
  genuinely RED against the pre-change single-level form.
- **`k8s/base/kustomization.yaml` label transformer** — the explicit `fields:` list is correct and
  mirrored in all three overlays; INV-3 finds 4 kube-dns selector blocks per target, each with exactly
  one key, and the block-walk-by-indentation shape is genuinely falsifiable (a forward `grep -A` would
  not be, for the alphabetical-sort reason the header states).
- **INV-6 falsifiability** — restoring the dangling Keycloak rule makes base/staging/production FAIL and
  `k8s/local` stay OK, exactly as documented. Confirmed by direct experiment.
- **`k8s/goldens/`** — both goldens match their renders byte-for-byte, contain zero `kind: Secret`, and
  the directory has no `kustomization.yaml` so it is not mistaken for a fourth overlay.
- **Compose service-name inventory** — all ten names in `K8S_LOCAL_APP_SERVICES` /
  `K8S_LOCAL_BACKING_SERVICES` exist verbatim in `docker-compose.full-stack.yml`. A typo here would make
  the APP arm fail *open*; it does not.
- **`create-backup-role.sql` injection surface** — the password reaches psql as `:'backup_password'`
  (properly quoted psql variable), not as string interpolation.
- **`k8s/local/scale-patch.yaml`, `namespace.yaml`, `sse-ingress-patch.yaml`** — no defects found.

---

_Reviewed: 2026-07-26_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
