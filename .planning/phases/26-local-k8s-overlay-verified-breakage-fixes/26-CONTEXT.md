# Phase 26: Local-K8s Overlay + Verified Breakage Fixes - Context

**Gathered:** 2026-07-25
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase delivers the **deploy layer only**: a committed, buildable `k8s/local` overlay that
replaces the imperative in-cluster secret/configmap patches used during the 2026-07-14 live-deploy
rehearsal, plus the fixes for the verified breakage list so core-java boots as the NOSUPERUSER
`jtoye_app` role on a single replica and the platform's config actually reaches the pods.

**In scope:** `k8s/local` overlay (namespace, endpoint shims to `host.minikube.internal`,
scale-to-1, backup→host MinIO, ingress hosts), the five confirmed config defects in `k8s/base` +
`application.yml`, a bootstrap script pair (secrets + bring-up), a CI env-contract gate, and the
`k8s/LOCAL.md` runbook.

**Out of scope:** no application behaviour change; no new feature surface; no Azure/AKS work; no
`mcp-server` k8s manifest (deferred, see `<deferred>`); no sealed-secrets adoption (PROJECT.md:141
locks plain k8s Secrets for this milestone).

**Runtime rule (carried, non-negotiable):** compose XOR k8s on local — the shared dev Postgres must
never have two writers (PROJECT.md:130, CLAUDE.md "Runtime & deploy topology"). Precisely: compose
**backing services** (postgres/redis/rabbitmq/keycloak/minio) stay UP because the cluster consumes
them via `host.minikube.internal`; compose **app containers** (core-java/frontend/edge-go/mcp-server)
must be DOWN while the cluster runs.

</domain>

<decisions>
## Implementation Decisions

### Confirmed defects this phase fixes (all verified against the code, 2026-07-25)

- **DEF-1 — `DB_PORT` hardcode.** `k8s/base/core-java-deployment.yaml:71-72` sets `value: "5432"`
  while `postgres-credentials` carries a `port` key that `k8s/base/pg-backup-cronjob.yaml:64-68`
  already consumes via `secretKeyRef`. Host Postgres publishes **5433**
  (`docker-compose.full-stack.yml:31`, to dodge the cohabiting OlaJay stack), so local cannot work
  and staging/prod are one port change from breaking. → route through `valueFrom.secretKeyRef`.
- **DEF-2 — superuser DB role in the secret recipe.** `k8s/QUICK_START.md:44-52` and
  `k8s/base/secrets-template.yaml.example` create `postgres-credentials` with `username: "jtoye"` —
  the DB **superuser**. `DatabaseConfigurationValidator` (`core-java/src/main/java/uk/jtoye/core/config/`)
  fails fast on a superuser because superusers bypass RLS. The deployment already reads the secret's
  `username`/`password` keys correctly, so the defect is in the recipe/template, not the manifest:
  they must specify the NOSUPERUSER `jtoye_app` (`.env` already holds `DB_USER`/`DB_PASSWORD`
  separately from `POSTGRES_USER`/`POSTGRES_PASSWORD` — use the former).
- **DEF-3 — production-sized scale minimums.** Base is `replicas: 3/5/3` (11 pods) with HPA
  `minReplicas` 3/5/3 and PDBs `minAvailable` 2/3/2; HPAs are inert locally with no metrics-server,
  and a PDB of 2 over 1 replica blocks any voluntary eviction.
- **DEF-4 — STOMP is a two-sided env-name mismatch, worse than "verify wiring".**
  `core-java/src/main/resources/application.yml:227-230` reads `${RABBITMQ_USER}` for
  `client-login`/`system-login`, but the deployment injects `RABBITMQ_USERNAME`
  (`core-java-deployment.yaml:140`); meanwhile `STOMP_CLIENT_LOGIN` / `STOMP_CLIENT_PASSCODE`
  (lines 170, 175) are read by **no `application*.yml` at all**. So STOMP silently falls back to
  `guest` → the observed boot-time `Access refused for user 'guest'`, and
  `spring.rabbitmq.username` silently falls back to its `jtoye` default too.
- **DEF-5 — split-horizon issuer env absent from k8s (found during this discussion).**
  `JWT_EXPECTED_ISSUER` (core + edge) and the frontend's `KEYCLOAK_ISSUER_INTERNAL` are wired in
  compose (`docker-compose.full-stack.yml:184, 277, 327`) but appear in **no k8s manifest**. Benign
  in prod (public issuer == pod-reachable URL); fatal locally, where the browser needs a
  browser-reachable Keycloak and pods need `host.minikube.internal:8085`. This is the #87
  split-horizon class that previously caused a total live-auth outage.
- **DEF-6 — base-manifest config drift vs the v2.2/v2.3 feature set (found during this discussion).**
  `k8s/base` injects only 23 env vars into core-java (DB, Keycloak, Redis, RabbitMQ, STOMP). Nothing
  is supplied for media storage (`S3_ENDPOINT` → `http://localhost:9000`, `S3_ACCESS_KEY` →
  `minioadmin`, `S3_PUBLIC_URL`), email (`SMTP_HOST` → `localhost`), Stripe (`STRIPE_API_KEY` →
  empty), `JWT_EXPECTED_AUDIENCE`, `CORS_ALLOWED_ORIGINS`, or the webhook-delivery knobs. Same bug
  class as DEF-4: a wrong-or-absent env resolving to a working-looking local default. Affects
  staging and production too, not only local.

### Local secret bootstrap

- **D-01:** Local secrets come from a committed **`scripts/k8s-local-secrets.sh`** that sources the
  gitignored `.env` and applies each secret idempotently
  (`kubectl create secret generic … --dry-run=client -o yaml | kubectl apply -f -`). Nothing becomes
  a kustomize resource, so `k8s/scripts/check-no-plaintext-secrets.sh` stays green.
  **Hard constraint:** that guard auto-discovers every kustomization at `maxdepth 2` and fails on any
  `kind: Secret` or `REPLACE_WITH` in the build output — so `secretGenerator` is **not** an option for
  `k8s/local`. Secrets needed: `postgres-credentials`, `s3-backup-credentials`, `redis-credentials`,
  `rabbitmq-credentials`, `keycloak-credentials`, `nextauth-secret`.
- **D-02:** The script also **bootstraps the BYPASSRLS `jtoye_backup` role** by running
  `infra/backups/create-backup-role.sql` against the host dev Postgres, using a **new
  `DB_BACKUP_PASSWORD` `.env` key**, then feeds `backup-username`/`backup-password` into
  `postgres-credentials`. Nothing (not compose, not Flyway) provisions this role today — it is a
  superuser bootstrap step — and without it a local `pg_dump` captures **zero rows** from every
  FORCE-RLS table, so the #101 rehearsal would "pass" on an empty dump.
- **D-03:** The local host + published ports live as **new `K8S_LOCAL_*` keys in `.env`**, read by the
  script — no port literal in any script (GLOBAL_RULE_6 / ARCHITECTURE_RULE_8 config-injection). A
  compose port shift is then a one-file fix. Values today: Postgres 5433, Keycloak 8085, Redis 6379,
  RabbitMQ 5672 + STOMP 61613, MinIO 9000 (`docker-compose.full-stack.yml:31, 102, 131, 151-153, 401`).
- **D-04:** The bootstrap **hard-fails the compose-XOR-k8s rule**: refuse to proceed while the compose
  **app** containers (core-java/frontend/edge-go/mcp-server) are running, and require the compose
  **backing services** to be up. The shared-dev-DB dual-writer footgun becomes a guard rail instead
  of a doc line.

### STOMP credential fix (DEF-4)

- **D-05:** Fix **both sides**, keeping the dedicated STOMP credential the secret and QUICK_START
  already provision: `application.yml` reads
  `${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}}` (and the passcode / `system-*` equivalents), **and**
  the k8s env `RABBITMQ_USERNAME` is renamed to `RABBITMQ_USER` so `spring.rabbitmq.username` stops
  silently defaulting. Compose keeps working unchanged (it sets `RABBITMQ_USER` already,
  `docker-compose.full-stack.yml:213`) — Incremental Betterment: the fallback chain is additive.
- **D-06:** STOMP is **proven on the local cluster**, not in compose: dev compose defaults to
  `STOMP_BROKER_MODE=in-memory` (`application.yml:222-224`), so a normal compose run never exercises
  the relay. Proof = core-java pod log free of `Access refused for user` **and** a working KDS
  WebSocket subscribe against host RabbitMQ `61613`.

### Recurrence prevention — CI env-contract gate

- **D-07:** Add a static **env-contract gate for core-java, both directions**, in the style of
  `k8s/scripts/check-connection-math.sh` / `RlsContractTest`:
  (a) every env name injected by `k8s/base/core-java-deployment.yaml` must be read by some
  `application*.yml`; (b) every `${PLACEHOLDER}` Spring expects must be supplied by a manifest or
  carry a deliberate, listed default. Would have caught **both** sides of DEF-4.
- **D-08:** The gate **also flags the DEF-6 class**: a placeholder whose default is a local-only value
  (`localhost`, `127.0.0.1`, `minioadmin`-style) that no manifest overrides. Every accepted omission
  must appear in an **explicit allowlist with a reason**, so whatever remains unsupplied is a
  reviewed inventory rather than a surprise. Wire it into the existing `k8s-validate` CI job
  (`.github/workflows/ci-cd.yaml:191-211`).

### Local cluster shape

- **D-09:** `k8s/local` patches **all three of** `replicas: 1`, HPA `minReplicas: 1`, and PDB
  `minAvailable: 1`. Every object still renders and gets validated by the build + dry-run, and a
  1-replica pod remains drainable. HPA `maxReplicas` is **untouched** — it is an input to
  `check-connection-math.sh`.
- **D-10:** Local **keeps `SPRING_PROFILES_ACTIVE=prod`** (base default). Rehearsing the prod config
  path — internal 9091 management port, no SQL logging, prod pool sizes — is the point, and it
  already booted 11/11 READY that way on 2026-07-14.
- **D-11:** The 6 NetworkPolicies **render unchanged** in the local build and `k8s/LOCAL.md` states
  plainly that local proves manifest validity, **not** enforcement (minikube's default CNI does not
  enforce them, and an enforcing CNI would need explicit egress for `host.minikube.internal`).

### Local access + endpoint URLs

- **D-12:** Access is via the **minikube ingress addon + `/etc/hosts` entries** (e.g. `jtoye.local`,
  `api.jtoye.local` → `minikube ip`), with the local overlay patching the Ingress host rules. This is
  the one deploy surface no other local path exercises, and it gives NextAuth stable callback URLs.
  `k8s/base/sse-ingress.yaml` gets the same treatment.
- **D-13:** The split-horizon values (DEF-5) are wired **into `k8s/base` via new `app-config` keys**
  that default to the same public issuer — so staging/prod render byte-identical behaviour — and the
  local overlay patches them to the split values (`JWT_EXPECTED_ISSUER` = browser-facing issuer;
  `KC_ISSUER_URI` / frontend `KEYCLOAK_ISSUER_INTERNAL` = `host.minikube.internal:8085`). Closes a
  latent k8s gap instead of hiding it behind a hosts-file trick.
- **D-14:** **`scripts/k8s-local-up.sh`** is the single bring-up entry point: XOR guard → secrets +
  backup role → `minikube image load` → `kubectl apply -k k8s/local` → wait for rollout → smoke.
  This is the durable replacement for the imperative rehearsal steps. `scripts/deploy.sh` stays
  focused on staging/prod, and its **phantom `dev` target** — accepted by the regex at
  `scripts/deploy.sh:27` with no `k8s/dev` overlay, applying base files directly via
  `kubectl apply -f` and bypassing kustomize entirely — is fixed or removed in the same change.

### Base-manifest drift (DEF-6)

- **D-15:** Close the drift **for core-java in `k8s/base` + overlays**: S3/MinIO (endpoint, bucket,
  public-url, credentials via a secret), SMTP, Stripe secrets, `CORS_ALLOWED_ORIGINS`,
  `JWT_EXPECTED_AUDIENCE`, and the webhook-delivery knobs. Rationale: a local overlay whose media
  uploads and emails fail silently is not rehearsing v2.3, and the same gap is live in staging/prod.
  Expect roughly one extra plan. Secret-shaped values follow D-01 locally and the existing
  SealedSecrets/`kubectl create secret` path for staging/prod;
  `k8s/base/secrets-template.yaml.example` + `k8s/QUICK_START.md` are updated to match.

### Proof

- **D-16:** Verification goes to **live apply + auth E2E through the ingress**: `kubectl kustomize`
  build, server dry-run (pre-create the namespace first — the documented chicken-and-egg), then a
  real apply with **3/3 pods READY**, core `/health` + `/public/shops`, and a **Playwright vendor
  login through the ingress** landing on a dashboard page. The login is the only step that actually
  proves DEF-5; credentials are available (`.env` `KC_SEED_USER_PASSWORD`, admin-user — see
  `project_phase_23_gap_closure` memory). Plus the backup rehearsal: the CronJob run puts a
  **non-empty** dump object into host MinIO.

### Post-research additions (appended 2026-07-25 after `26-RESEARCH.md`)

Research surfaced three further verified defects; the user folded all three in. These are tracked
decisions, not discretion.

- **D-17 — kube-dns NetworkPolicy selector bug (verified PRODUCTION defect).** The base
  `labels: [{pairs, includeSelectors: true}]` transformer injects the common labels into the DNS
  egress `podSelector` of `k8s/base/networkpolicies/20-core-java.yaml:60-66`. The rendered
  `kubectl kustomize k8s/production` output emits `app.kubernetes.io/managed-by: kustomize` +
  `app.kubernetes.io/part-of: jtoye-platform` + `environment: production` **alongside**
  `k8s-app: kube-dns` — real kube-dns pods carry none of those, so the selector matches nothing and
  core-java has NO DNS egress under an enforcing CNI (total outage). Inert on minikube (no
  enforcement) and invisible to `k8s/scripts/validate-networkpolicies.py`, which reads raw files, not
  the render, and is not wired into CI at all. **Fix the selector AND add a render-level assertion**
  so the class cannot silently return with the next transformer edit. Applies to every overlay, not
  just local.
- **D-18 — frontend `NEXT_PUBLIC_*` is build-time, so the k8s ConfigMap injection is dead config.**
  `frontend/Dockerfile:22-23` takes `NEXT_PUBLIC_API_URL` as a build ARG, and line 31 of that same
  file already documents that `NEXT_PUBLIC_*` is "inlined at BUILD time — a runtime `environment:`"
  does not work. `k8s/base/frontend-deployment.yaml:49-53` injects it from `app-config` at runtime →
  inert in every k8s environment (latent staging/prod defect). Therefore: `scripts/k8s-local-up.sh`
  **builds a local frontend image** with `--build-arg NEXT_PUBLIC_API_URL=http://api.jtoye.local`
  (and the other `NEXT_PUBLIC_*` args), **and** the misleading runtime injection in
  `frontend-deployment.yaml` is corrected or removed so no one trusts dead config again. This keeps
  D-16's full browser proof reachable.
- **D-19 — fold the 4 remaining prod-affecting localhost defaults into D-15.**
  `NOTIFICATION_UNSUBSCRIBE_BASE_URL`, `NOTIFICATION_EMAIL_TRACKING_BASE_URL`,
  `STRIPE_CONNECT_RETURN_URL`, `STRIPE_CONNECT_REFRESH_URL` all default to `http://localhost:3000`
  and are unsupplied by any manifest — so production emails carry unsubscribe/tracking links pointing
  at localhost and a Stripe Connect return sends vendors to localhost. ~4 more `app-config` keys in
  the same edit as D-15.

**Corrections to this document (found during research):**
- The `secretKeyRef` port precedent in `k8s/base/pg-backup-cronjob.yaml` is at **lines 46-50**, not
  64-68 (61-65 is `PGPASSWORD`). Grep `key: port`.
- The test baseline is **1690** logical invocations (`docs/metrics.json`), not the 1684 stated in
  `CLAUDE.md`/`AGENTS.md` prose — that prose is stale and should be reconciled by whichever plan
  touches docs (`scripts/docs-freshness.sh --write` is the arbiter).
- `.env`/docs already use `POSTGRES_BACKUP_PASSWORD` (`k8s/QUICK_START.md:32`) for what D-02 calls
  `DB_BACKUP_PASSWORD` — pick one name and use it consistently.

### Claude's Discretion

- Exact `.env` key names for D-02/D-03 (suggested: `DB_BACKUP_PASSWORD`, `K8S_LOCAL_HOST`,
  `K8S_LOCAL_DB_PORT`, `K8S_LOCAL_KC_PORT`, `K8S_LOCAL_REDIS_PORT`, `K8S_LOCAL_AMQP_PORT`,
  `K8S_LOCAL_STOMP_PORT`, `K8S_LOCAL_MINIO_PORT`) and the `.env.example` documentation shape.
- Local namespace name (suggested `jtoye-local`, per the overlay-owns-its-own-namespace rule) and the
  local image tag strategy (locally-built compose images already tag as
  `ghcr.io/bralabee/jtoye-*:2.1.0` + `minikube -p jtoye image load`; `imagePullPolicy: IfNotPresent`
  in base already suits loaded images).
- Whether the gate (D-07/D-08) is a bash script under `k8s/scripts/` or a JUnit test — match whichever
  existing precedent reads more naturally; it must run in the `k8s-validate` CI job either way.
- Plan split across INFRA-01 / INFRA-02 / the DEF-6 work, and how the `pg-backup` image
  (`ghcr.io/bralabee/jtoye-pg-backup:15`, built from `infra/backups/Dockerfile`) gets loaded locally.
- The hardcoded `namespace: jtoye-production` in `k8s/base/pg-backup-cronjob.yaml:5` — currently
  overridden by each overlay's `namespace:` transformer, so cosmetic; remove it if it stays free.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope + requirements
- `.planning/ROADMAP.md` § "Phase 26: Local-K8s Overlay + Verified Breakage Fixes" — goal, the 4
  success criteria, and the 2-plan suggestion.
- `.planning/REQUIREMENTS.md:78-80` — INFRA-01 / INFRA-02 verbatim, incl. the sub-items (a)-(d) and
  their "Source: HANDOFF k8s breakage list #N" provenance.
- `.planning/PROJECT.md:28, 130, 141` — the local-k8s overlay commitment, the compose-XOR-k8s runtime
  rule, and the "plain k8s Secrets this milestone, sealed-secrets later" decision.
- `CLAUDE.md` § Constraints "Runtime & deploy topology" — compose = canonical local dev/E2E runtime;
  k8s = staging/prod deploy target; XOR applies only at local runtime.

### The manifests being changed
- `k8s/base/core-java-deployment.yaml` — DEF-1 (`DB_PORT`, lines 71-72), DEF-4
  (`RABBITMQ_USERNAME` line 140, dead `STOMP_CLIENT_*` lines 170-179), DEF-3 (HPA `minReplicas: 3`,
  PDB `minAvailable: 2`), DEF-6 (the 23-var env block that needs extending). Note the `9091`
  management-port probes/annotations — do not disturb.
- `k8s/base/configmap.yaml` — the `app-config` keys overlays patch; already carries
  `s3.backup.endpoint` (empty = real AWS) which is the local backup repoint seam.
- `k8s/base/pg-backup-cronjob.yaml` — the correct `secretKeyRef` `port` precedent (lines 64-68), the
  BYPASSRLS-role rationale, and the hardcoded `namespace:` at line 5.
- `k8s/base/kustomization.yaml` — resource list + the "environment namespaces never live in the base"
  rule and the #100 no-Secret-resources rule, both stated in-file.
- `k8s/staging/kustomization.yaml`, `k8s/staging/configmap-patch.yaml` — the exact overlay shape to
  mirror for `k8s/local` (namespace, labels, `images:` newTag, `patches:`, `replicas:`).
- `k8s/base/frontend-deployment.yaml`, `k8s/base/edge-go-deployment.yaml` — the other two scale
  targets (frontend HPA min 3 / PDB 2; edge HPA min 5 / PDB 3) and the frontend's
  `NEXTAUTH_URL` / `NEXT_PUBLIC_API_URL` / `KEYCLOAK_ISSUER` env from `app-config` (DEF-5 surface).
- `k8s/base/ingress.yaml`, `k8s/base/sse-ingress.yaml` — host rules the local overlay patches (D-12).
- `k8s/base/networkpolicies/README.md` — flow matrix + rollback, for the D-11 "inert locally" note.

### CI gates that must stay green (and one to extend)
- `k8s/scripts/check-no-plaintext-secrets.sh` — auto-discovers every kustomization at `maxdepth 2`;
  bans `kind: Secret` and `REPLACE_WITH` in build output. **`k8s/local` is gated automatically.**
- `k8s/scripts/check-connection-math.sh` — parses `k8s/base/core-java-deployment.yaml` for
  `DB_POOL_SIZE` + HPA `maxReplicas` and asserts CPU-only HPA metrics. Editing the env block risks
  breaking its parser; re-run it after any manifest change.
- `.github/workflows/ci-cd.yaml:183-211` (`k8s-validate` job) — where both scripts run and where the
  new env-contract gate (D-07/D-08) belongs.

### Application config being changed
- `core-java/src/main/resources/application.yml:14-16` (datasource `DB_*`), `:71-75`
  (`spring.rabbitmq`, incl. the `RABBITMQ_USER` name), `:141-142` + `:149-154`
  (`jtoye.security.jwt.expected-issuer` / customer equivalent — the #87 fix), `:222-231`
  (`stomp.broker.*`), `:295-300` (`S3_*`), `:77-86` (SMTP), `:315-328` (Stripe).
- `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java:52-103` — how
  `expectedIssuer` is validated separately from the JWKS host (DEF-5 mechanism).
- `core-java/src/test/java/uk/jtoye/core/security/JwtIssuerDecouplingTest.java` — the existing
  regression test for that decoupling; the pattern to extend if a k8s-side assertion is wanted.
- `docker-compose.full-stack.yml:31, 102, 131, 151-153, 180-189, 213, 276-277, 326-327, 401` — the
  authoritative host ports and the compose env contract the k8s manifests must match.

### Backup / PITR
- `infra/backups/create-backup-role.sql` — the BYPASSRLS `jtoye_backup` bootstrap (superuser-only,
  not a Flyway migration) with the proven `0 rows vs 25 rows` rationale.
- `infra/backups/k8s-backup.sh`, `infra/backups/Dockerfile` — the CronJob entrypoint + image the
  local run needs loaded.
- `docs/runbooks/backups.md` — the PITR/restore runbook the #101 rehearsal exercises.

### Deploy docs + scripts
- `k8s/QUICK_START.md:22-100` — Step 1 secret recipe (carries DEF-2's `username=jtoye`, and the
  now-known-false claim at line 68 that omitting `stomp-login` fails pods).
- `k8s/DEPLOYMENT.md` — living how-to; § "Database Connection Budget" is referenced by the
  connection-math gate. `k8s/PRODUCTION_READINESS_REPORT.md` is a **dated signed audit — do not
  rewrite**; append a dated post-audit note if it must change.
- `k8s/base/secrets-template.yaml.example` — reference key shapes (DEF-2 + D-15 update target).
- `scripts/deploy.sh:27` — the phantom `dev` target (D-14).
- `docs/runbooks/sealed-secrets.md` — the staging/prod secret path (context only; out of scope here).

### Memory (session-external, load-bearing)
- `k8s-kustomize-deploy` memory — the 2026-07-14 first-live-deploy record: minikube profile `jtoye`,
  `--cpus=4 --memory=12g`, images loaded via `minikube -p jtoye image load`, the numbered breakage
  list this phase closes, the server-dry-run namespace chicken-and-egg, and the caution that
  `sipbihs2aks` is **employer infrastructure — never touch**.
- `env_gotchas_local_stack` memory — root-owned `core-java/build`, the `:9090/:9091` port-range shift,
  the cohabiting OlaJay stack (why Postgres is on 5433), seed password in `.env`.
- `jwt_issuer_jwks_split_horizon` memory — why DEF-5 is a total-auth-outage class, not a nit.
- `feedback_port3100` memory — server-side in-container Keycloak calls must use the internal host, or
  undici hangs ~10.5s then 401 (the `KEYCLOAK_ISSUER_INTERNAL` half of DEF-5).

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **`k8s/staging/` overlay** — the exact template for `k8s/local`: `resources: [../base, namespace.yaml]`,
  own namespace, `labels` with `includeSelectors: true`, `images:` newTag block, `patches: [configmap-patch.yaml]`,
  `replicas:` list. Copy this shape rather than inventing one.
- **`s3.backup.endpoint` configmap key** — already exists and is already consumed by the CronJob, so
  the "backup → host MinIO" repoint is a configmap patch, not new plumbing.
- **`secretKeyRef` `port` pattern in `pg-backup-cronjob.yaml`** — the in-repo precedent DEF-1 should
  copy verbatim.
- **`check-no-plaintext-secrets.sh` / `check-connection-math.sh`** — the house style for a
  parse-the-real-files CI gate; the D-07/D-08 gate should read like a sibling.
- **`.env`** — already holds `DB_USER`/`DB_PASSWORD` (jtoye_app), `POSTGRES_USER`/`POSTGRES_PASSWORD`
  (superuser, must NOT be used for the app), `REDIS_PASSWORD`, `RABBITMQ_USER`/`RABBITMQ_PASSWORD`,
  `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD`, `NEXTAUTH_SECRET`, `KEYCLOAK_ADMIN*`,
  `KEYCLOAK_CLIENT_SECRET`, `EDGE_API_CLIENT_SECRET`, `KC_SEED_USER_PASSWORD`. Only
  `DB_BACKUP_PASSWORD` + the `K8S_LOCAL_*` keys are new.
- **`infra/backups/create-backup-role.sql`** — idempotent and password-injected; safe to call from
  the bootstrap script.

### Established Patterns
- **Overlay owns its namespace** — never add an env namespace to the base, or every overlay's
  `namespace:` transformer collapses them ("namespace transformation produces ID conflict").
- **No `kind: Secret` in any kustomize build** — enforced by CI for every overlay, including new ones.
- **Config injection, no environment-varying literals** — the reason DEF-1 and D-03 are defects rather
  than style points (GLOBAL_RULE_6 / ARCHITECTURE_RULE_8).
- **Dated audits are records, not docs** — `PRODUCTION_READINESS_REPORT.md` gets an appended note, never
  a rewrite.
- **Anti-false-green** — a requirement is marked Complete only with a named, live proof; hence D-16.
- **Incremental Betterment** — the `${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}}` chain is additive so
  compose keeps working; the DEF-5 configmap keys default to today's public issuer so staging/prod
  render identically.

### Integration Points
- `k8s/local/` (new) → `k8s/base` via `resources`; picked up automatically by the CI secret guard.
- `scripts/k8s-local-secrets.sh` + `scripts/k8s-local-up.sh` (new) → `.env`, docker/compose state,
  minikube profile `jtoye`, `infra/backups/create-backup-role.sql`.
- `application.yml` STOMP + issuer keys → `k8s/base/core-java-deployment.yaml` env + `app-config`.
- New env-contract gate → `.github/workflows/ci-cd.yaml` `k8s-validate` job.
- `k8s/LOCAL.md` (new) → cross-referenced from `k8s/QUICK_START.md` and `k8s/DEPLOYMENT.md`.

</code_context>

<specifics>
## Specific Ideas

- The phase's own doctrine test: a local cluster where media upload and email silently fail is not
  rehearsing v2.3 — hence DEF-6 is fixed in `k8s/base`, not papered over locally (D-15).
- The `guest` default is what turned DEF-4 into a survivable WARN. The fix is not just the value but
  the **gate** that makes the class visible (D-07/D-08), including an explicit, reasoned allowlist for
  anything left unsupplied.
- Local must exercise the **Ingress** path (D-12) precisely because no other local runtime does.
- The backup rehearsal only counts if the dump is **non-empty** — that is why the BYPASSRLS role is
  bootstrapped rather than documented (D-02).
- One command should bring the local cluster up (D-14); the imperative multi-step sequence is the
  thing this phase exists to delete.

</specifics>

<deferred>
## Deferred Ideas

- **`mcp-server` k8s manifest** — the MCP server shipped in Phases 20 and 25 and runs in compose, but
  `k8s/` has no deployment/service/ingress for it at all. Its own phase (new manifests + scoped
  credentials + ingress), not a local-overlay patch.
- **Calico CNI locally to actually enforce NetworkPolicies** — would make local the only environment
  that proves enforcement, but needs egress rules for `host.minikube.internal` and exceeds the
  roadmap criteria. Attractive follow-up.
- **Customer-storefront realm in k8s** — `CUSTOMER_KC_ISSUER_URI` / `CUSTOMER_JWT_EXPECTED_ISSUER` /
  `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` exist in compose but nowhere in `k8s/`, so the customer realm is
  unconfigured in every k8s environment. Real prod gap; belongs with the storefront/CID work.
- **Env-contract gate coverage for `edge-go` and `frontend`** — this phase gates core-java only.
  Extending it needs Go `os.Getenv` and Next.js `process.env` parsers.
- **Sealed-secrets / external-secrets for local** — explicitly the long-term answer, out of this
  milestone (PROJECT.md:141).
- **Azure AKS deploy** — the personal-sub `jtoye-rg` target and its ~$60-175/mo cost sit outside this
  phase (local first, per the 2026-07-14 decision).
- **Relay-mode compose override** — a committed opt-in profile flipping `STOMP_BROKER_MODE=relay` so
  the relay path is testable without a cluster. Rejected here in favour of proving it on the cluster.

</deferred>

---

*Phase: 26-Local-K8s Overlay + Verified Breakage Fixes*
*Context gathered: 2026-07-25*
