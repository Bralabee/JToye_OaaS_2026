# Phase 26: Local-K8s Overlay + Verified Breakage Fixes — Research

**Researched:** 2026-07-25
**Domain:** Kubernetes/kustomize deploy configuration, minikube local cluster, Spring Boot env contract, CI static gates
**Confidence:** HIGH for mechanics (empirically proven this session), MEDIUM for the live-cluster auth path (one unresolved unknown, see Open Question 1)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Confirmed defects this phase fixes (all verified against the code, 2026-07-25)**

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

**Local secret bootstrap**

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

**STOMP credential fix (DEF-4)**

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

**Recurrence prevention — CI env-contract gate**

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

**Local cluster shape**

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

**Local access + endpoint URLs**

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

**Base-manifest drift (DEF-6)**

- **D-15:** Close the drift **for core-java in `k8s/base` + overlays**: S3/MinIO (endpoint, bucket,
  public-url, credentials via a secret), SMTP, Stripe secrets, `CORS_ALLOWED_ORIGINS`,
  `JWT_EXPECTED_AUDIENCE`, and the webhook-delivery knobs. Rationale: a local overlay whose media
  uploads and emails fail silently is not rehearsing v2.3, and the same gap is live in staging/prod.
  Expect roughly one extra plan. Secret-shaped values follow D-01 locally and the existing
  SealedSecrets/`kubectl create secret` path for staging/prod;
  `k8s/base/secrets-template.yaml.example` + `k8s/QUICK_START.md` are updated to match.

**Proof**

- **D-16:** Verification goes to **live apply + auth E2E through the ingress**: `kubectl kustomize`
  build, server dry-run (pre-create the namespace first — the documented chicken-and-egg), then a
  real apply with **3/3 pods READY**, core `/health` + `/public/shops`, and a **Playwright vendor
  login through the ingress** landing on a dashboard page. The login is the only step that actually
  proves DEF-5; credentials are available (`.env` `KC_SEED_USER_PASSWORD`, admin-user — see
  `project_phase_23_gap_closure` memory). Plus the backup rehearsal: the CronJob run puts a
  **non-empty** dump object into host MinIO.

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

### Deferred Ideas (OUT OF SCOPE)

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
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description (from `.planning/REQUIREMENTS.md:78-80`) | Research Support |
|----|------------------------------------------------------|------------------|
| **INFRA-01** | Committed `k8s/local` overlay. Endpoint shims to `host.minikube.internal`, `minReplicas=1` (no metrics-server locally), backup CronJob repointed to host MinIO. Replaces imperative secret/configmap patches. Tests: `kubectl kustomize k8s/local` builds; dry-run apply resolves all refs. | §Architecture Patterns P-1..P-6 give the exact, **empirically built** overlay shape (one multi-doc scale patch for 3 HPAs + 3 PDBs, `replicas:` list for Deployments, configmap merge patch, Ingress rules-replace + annotation-null). §Pitfalls PIT-1 (snippet-annotation admission rejection) and PIT-8 (namespace pre-create) are the two things that make a dry-run fail. |
| **INFRA-02** | (a) `DB_PORT` via `valueFrom.secretKeyRef` (no hardcoded 5432). (b) Secrets use `DB_USER`/`DB_PASSWORD` (NOSUPERUSER `jtoye_app`). (c) pg-backup CronJob → host MinIO (#101 PITR rehearsal). (d) verify STOMP relay stomp-login/passcode wiring reaches spring config. Tests: config-injection assertion (no hardcoded port), boot-as-app-role smoke. | §Pitfalls PIT-2 proves (a) **must** be a base edit, not an overlay patch (strategic merge yields an invalid `value`+`valueFrom` EnvVar). §Live Facts confirms (b): `jtoye`=superuser, `jtoye_app`=NOSUPERUSER, both live. §Backup Rehearsal covers (c) incl. the **missing bucket** blocker. §Env-Contract Gate covers (d) with the complete two-direction inventory. |

</phase_requirements>

---

## Project Constraints (from CLAUDE.md)

Actionable directives the planner must honour. These carry the same authority as CONTEXT.md locked decisions.

| Directive | Consequence for this phase |
|-----------|----------------------------|
| **Tech stack fixed** — Spring Boot 3.5.16, Next.js 16, Go 1.25, PostgreSQL 15; JDK 21 | No dependency changes. Phase is YAML + bash + one `application.yml` edit. |
| **Multi-tenancy** — all new features respect RLS + TenantContext | The NOSUPERUSER `jtoye_app` role fix (DEF-2) *is* the RLS guarantee; the BYPASSRLS `jtoye_backup` role is the deliberate, scoped exception (dump only). |
| **Testing** — all new code requires tests; `docs/metrics.json` is the single source of truth, enforced by `docs-freshness` CI | **A bash gate under `k8s/scripts/` contributes 0 to `metrics.json`** (`scripts/docs-freshness.sh:46-65` counts only Java `@Test`, Go `Test*`, Jest/vitest `it(`/`test(`, Playwright `test(`). A JUnit gate would count +N and force a `--write` reconcile — a documented merge-conflict hotspot. See §Env-Contract Gate for the recommendation. Current baseline: **1690** (`docs/metrics.json`), *not* the 1684 CLAUDE.md text states. |
| **Docker** — always rebuild ALL containers after code changes before E2E | Applies literally here: the `ghcr.io/bralabee/jtoye-*:2.1.0` images on this host are from **2026-07-13/14** (pre-Phase 23/24/25). See PIT-4. |
| **Runtime & deploy topology** — compose = canonical local dev/E2E; k8s = staging/prod target; **XOR only at local runtime** | D-04's guard. Compose **app** containers are running right now (verified) — the guard is immediately load-bearing. |
| **Incremental Betterment Doctrine** — never trade away a working good; regression by omission is a defect | Every base-manifest change (D-05 rename, D-13 configmap keys, D-15 env additions) must render staging/prod **behaviour-identical**. Concretely: additive `${A:${B:default}}` chains and configmap keys defaulting to today's values. |
| **Cross-Cutting Quality Contracts** — plans carry a `<threat_model>` block (ASVS L1) | See §Security Domain. Web-perf/SEO are **N/A** (no user-facing page change); agent-readiness is **N/A** (no API surface change) — record them N/A, never silently drop. |
| **Config injection, no environment-varying literals** | Drives D-01/D-03 (`.env` → script) and DEF-1. Any port/host literal in an authored script is a defect. |
| **GSD workflow enforcement** — no direct repo edits outside a GSD command | Execution happens under `/gsd:execute-phase`. |

---

## Summary

This phase is **configuration correctness work with a live proof**, not feature work. The mechanics
turned out to be almost entirely verifiable ahead of time, and I built and rendered a candidate
`k8s/local` overlay in a scratchpad this session to prove the patch shapes. Three findings change the
plan materially:

1. **DEF-1 cannot be fixed by an overlay patch.** A kustomize strategic-merge patch that adds
   `valueFrom` to an env that already has `value:` produces an EnvVar with **both** fields — which
   `kubectl kustomize` emits happily but the API server rejects. The `value: "5432"` line at
   `k8s/base/core-java-deployment.yaml:71-72` must be *deleted* in the base. Proven by build, below.
2. **Two blockers stand between `kubectl apply -k k8s/local` and a green rollout that CONTEXT.md does
   not name.** (a) The `nginx.ingress.kubernetes.io/configuration-snippet` annotation on
   `k8s/base/ingress.yaml:29-35` is rejected by the ingress-nginx validating admission webhook —
   minikube v1.36.0 ships **ingress-nginx v1.12.2**, where `allow-snippet-annotations` defaults to
   `false` and `annotations-risk-level` to `High`. (b) The frontend image has
   `NEXT_PUBLIC_API_URL=http://localhost:9090` **baked in at Docker build time**; Next.js freezes
   `NEXT_PUBLIC_*` at build, so the `k8s/base/frontend-deployment.yaml:49-53` configmap injection of
   it is **dead config**, and D-16's "Playwright vendor login landing on a dashboard page" cannot pass
   without a purpose-built local frontend image.
3. **The DEF-6 inventory is larger than D-15 enumerates.** The full two-direction env audit (below,
   116 placeholders vs 23 injected) surfaces 13 localhost-defaulted unsupplied vars — including
   `NOTIFICATION_UNSUBSCRIBE_BASE_URL`, `NOTIFICATION_EMAIL_TRACKING_BASE_URL` and
   `STRIPE_CONNECT_RETURN_URL`/`REFRESH_URL`, which are **live production defects** (every prod
   unsubscribe link, tracking pixel and Stripe Connect return URL points at `http://localhost:3000`).

Two things are *better* than CONTEXT.md assumed: `check-connection-math.sh` is **not** at risk from
the DB_PORT change (it parses `DB_POOL_SIZE`/`maxReplicas`, both untouched — re-run confirmed green),
and the overlay's `k8s/local` addition passes `check-no-plaintext-secrets.sh` unchanged. One thing is
*worse*: the `labels: includeSelectors: true` transformer injects the common labels into
NetworkPolicy `podSelector.matchLabels` — including the `k8s-app: kube-dns` egress rule — which under
an enforcing CNI is a total DNS blackhole. That bug is **already live in `k8s/base` and
`k8s/production`** and is invisible to `validate-networkpolicies.py` (which reads raw files, not the
kustomize build).

**Primary recommendation:** split into three plans — (1) base-manifest breakage fixes + `application.yml`
STOMP chain + env-contract gate (pure static, CI-provable, zero cluster); (2) the `k8s/local` overlay +
bootstrap scripts + `k8s/LOCAL.md` (buildable + server-dry-run provable); (3) the live rehearsal
(apply, boot proof, STOMP proof, ingress login, backup CronJob). Put the DEF-6/D-15 env additions in
plan 1 (they are base edits and the CORS value is a **prerequisite** for plan 3's WebSocket proof).

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| `k8s/local` overlay (namespace, scale, endpoint shims, ingress hosts) | **Deploy config (kustomize)** | — | Declarative, environment-varying values belong in the overlay, never in code or the base. |
| `DB_PORT` via `secretKeyRef` (DEF-1) | **Deploy config — `k8s/base`** | — | Proven: an overlay patch cannot legally replace `value:` with `valueFrom:` (PIT-2). Must be the base. |
| NOSUPERUSER role in the secret recipe (DEF-2) | **Docs + bootstrap script** | Database (role grants) | The manifest is already correct; the defect is in `QUICK_START.md` + `secrets-template.yaml.example`. |
| STOMP credential resolution (DEF-4) | **Application config (`application.yml`)** | Deploy config (env rename) | Both sides needed. The fallback chain is app-layer; the `RABBITMQ_USERNAME`→`RABBITMQ_USER` rename is deploy-layer. |
| Split-horizon issuer (DEF-5) | **Deploy config (`app-config` keys)** | Frontend server (NextAuth discovery — see OQ-1) | D-13 puts the keys in base with prod-identical defaults; the frontend half may need a 1-line `auth.ts` change. |
| Env-contract enforcement (D-07/D-08) | **CI static gate (`k8s/scripts/`)** | — | Sibling of `check-connection-math.sh`, which already parses the same two file families. |
| Local secret + role + bucket bootstrap (D-01/D-02) | **Host script (`scripts/`)** | Database, MinIO | Imperative by necessity (secrets must never be kustomize resources, #100). |
| Compose-XOR-k8s guard (D-04) | **Host script (`scripts/`)** | — | Only the host can observe both runtimes. |
| Backup CronJob → host MinIO (INFRA-02c) | **Deploy config (configmap patch)** | Host script (bucket pre-create) | `s3.backup.endpoint` seam already exists; the **bucket does not** (see §Backup Rehearsal). |
| Live rollout + auth/STOMP/backup proof (D-16) | **Live cluster (minikube `jtoye`)** | Playwright (frontend tier) | Nothing static can prove these. |

---

## Environment Availability

All probed read-only this session on this host.

| Dependency | Required By | Available | Version / detail | Fallback |
|------------|------------|-----------|------------------|----------|
| `kubectl` | build, dry-run, apply, all gates | ✓ | **v1.33.3**, embedded **Kustomize v5.6.0** | — |
| standalone `kustomize` | not needed for this phase | ✗ | not on PATH | `kubectl kustomize` (identical v5.6.0 — CI pins kustomize **5.6.0** at `ci-cd.yaml:511-512`, so local and CI agree byte-for-byte) |
| `minikube` | local cluster | ✓ | **v1.36.0**, docker driver | — |
| minikube profile `jtoye` | the local cluster | ✓ (**Stopped**) | IP `192.168.49.2`, k8s **v1.33.1**, 1 node | `minikube start -p jtoye` (the 2026-07-14 rehearsal used `--cpus=4 --memory=12g`) |
| kubectl context | dry-run + apply | ⚠ | **`current-context` is NOT set.** Only context present is **`sipbihs2aks` = EMPLOYER AKS — never target** | Every `kubectl` call in scripts/plans MUST pass `--context jtoye` explicitly. `minikube start -p jtoye` creates/selects that context. |
| Docker | images, compose, minikube driver | ✓ | Server **29.6.2** | — |
| compose stack | backing services for the cluster | ✓ **all 10 services healthy** | postgres `0.0.0.0:5433`, keycloak `0.0.0.0:8085`, redis `0.0.0.0:6379`, rabbitmq `0.0.0.0:5672`+`0.0.0.0:61613`, minio `0.0.0.0:9000-9001`, mailhog `1025/8025` | — |
| compose **app** containers | must be DOWN for XOR | ⚠ **currently UP** | `core-java` (9090), `frontend` (3000), `edge-go` (8089), `mcp-server` (9100) | D-04's guard must stop the run, or the operator brings them down first. |
| ingress-nginx (minikube addon) | D-12 ingress path | ? not enabled yet | minikube v1.36.0 bundles **ingress-nginx/controller:v1.12.2** + `kube-webhook-certgen:v1.5.3` | `minikube addons enable ingress -p jtoye`. See PIT-1 — v1.12.2 **rejects** the base ingress as-is. |
| metrics-server | HPAs (inert without it) | ? not enabled | — | Not needed; D-09 sets `minReplicas: 1` so HPAs never need to compute. |
| `ghcr.io/bralabee/jtoye-core-java:2.1.0` | `minikube image load` | ✓ **but STALE** | built **2026-07-13 15:14** — predates Phases 23/24/25 (no V52-V59 code) | Rebuild before load. PIT-4. |
| `ghcr.io/bralabee/jtoye-frontend:2.1.0` | `minikube image load` | ✓ **but STALE + wrong baked API URL** | built **2026-07-14 00:01** | Must be rebuilt with `--build-arg NEXT_PUBLIC_API_URL=http://api.jtoye.local`. PIT-3/PIT-4. |
| `ghcr.io/bralabee/jtoye-edge-go:2.1.0` | `minikube image load` | ✓ **but STALE** | built **2026-07-13 12:31** | Rebuild. |
| `ghcr.io/bralabee/jtoye-pg-backup:15` | backup CronJob | ✓ | built 2026-07-10; `infra/backups/Dockerfile` unchanged since | Load as-is, or rebuild for determinism. |
| host DB role `jtoye_backup` | non-empty dump | ✗ **does not exist** | live query: only `jtoye` (superuser, BYPASSRLS) and `jtoye_app` (NOSUPERUSER, no BYPASSRLS) | D-02's bootstrap is **required**, confirmed. |
| MinIO bucket `jtoye-db-backups` | backup upload | ✗ **does not exist** | `/data` contains only `jtoye-images` (compose's `minio-init` creates only that, `docker-compose.full-stack.yml:426`) | **New finding — must be created by the bootstrap.** `aws s3 cp` to a missing MinIO bucket fails `NoSuchBucket`. |
| host schema version | overlay ships all v2.3 | ✓ | Flyway **V59** applied | — |
| `python3` + PyYAML | `validate-networkpolicies.py` (not in CI) | ✓ via conda envs | base python is hook-blocked on this host | Not on the critical path. |

**Missing dependencies with no fallback:** none.
**Missing dependencies requiring a bootstrap step (all inside this phase's scope):** `jtoye_backup`
role, `jtoye-db-backups` bucket, `/etc/hosts` entries, minikube ingress addon, fresh images,
`--context jtoye` selection.

---

## Package Legitimacy Audit

**Not applicable — this phase installs no external packages.** Every deliverable is YAML
(`k8s/local/*`), bash (`scripts/k8s-local-*.sh`, `k8s/scripts/check-env-contract.sh`), one
`application.yml` edit, doc updates, and existing already-installed tooling (`kubectl`, `minikube`,
`docker`). No `npm install`, `pip install`, or `go get` is required. The Standard Stack table below
lists **already-installed binaries with verified versions**, not registry packages.

---

## Standard Stack

### Core (already installed — versions verified this session)

| Tool | Version | Purpose | Why standard |
|------|---------|---------|--------------|
| `kubectl` | v1.33.3 (Kustomize v5.6.0) | `kubectl kustomize`, `apply -k`, server dry-run | Already what CI's `k8s-validate` job uses (`ci-cd.yaml:199` pins `v1.33.3`) — local matches CI exactly |
| kustomize | v5.6.0 (embedded + CI-pinned) | overlay rendering | `ci-cd.yaml:511-512` pins `KUSTOMIZE_VERSION=5.6.0` with a SHA256 check; the kubectl-embedded version is the same 5.6.0, so no version skew |
| minikube | v1.36.0 (docker driver) | the local cluster | Profile `jtoye` already exists from the 2026-07-14 rehearsal |
| ingress-nginx | v1.12.2 (minikube addon) | D-12 ingress path | Bundled; not a choice |
| `aws` CLI v1 | inside `ghcr.io/bralabee/jtoye-pg-backup:15` | MinIO upload via `--endpoint-url` | Already baked into `infra/backups/Dockerfile`; `docs/runbooks/backups.md:245` documents the `--endpoint-url for MinIO` pattern as already-proven |

### Supporting patterns already in-repo (reuse, do not reinvent)

| Asset | Purpose | Reuse for |
|-------|---------|-----------|
| `k8s/staging/kustomization.yaml` | overlay shape reference | Copy verbatim structure for `k8s/local` (namespace / resources / labels / images / patches / replicas) |
| `k8s/scripts/check-connection-math.sh` | parse-the-real-files CI gate | House style + exit-code convention (0 ok / 1 violation / 2 parse failure) for the new env-contract gate; it already parses **both** `k8s/base/core-java-deployment.yaml` and `application*.yml` |
| `scripts/verify-env.sh` | `.env` preflight, fail-loud, **never prints values** | Direct precedent for `k8s-local-secrets.sh`'s `.env` validation (D-01/D-03); reuse its deny-list + name-only reporting |
| `infra/backups/create-backup-role.sql` | idempotent BYPASSRLS role | Call from the bootstrap (D-02); password injected via `-v backup_password=` |
| `docs/runbooks/backups.md:228-249` | already-proven local backup + restore drill | The exact non-empty-dump proof recipe (§Validation Architecture) |
| `frontend/e2e/stomp-relay.spec.ts` | existing relay E2E, env-configurable (`FRONTEND_URL`, `EDGE_URL`, `RELAY_E2E` gate) | D-06's KDS WebSocket proof — but see PIT-9 (hardcoded cookie `domain: "localhost"`) |
| `frontend/e2e/dashboard-mobile.spec.ts` | Phase 23 vendor-login journey, 13/13 green | D-16's ingress login proof; `PLAYWRIGHT_BASE_URL` is already config-injected (`playwright.config.ts:13`) |

**No installation step.** Verification commands run against what is already present:
```bash
kubectl version --client                  # expect v1.33.3 / Kustomize v5.6.0
minikube version                          # expect v1.36.0
minikube profile list                     # expect profile 'jtoye'
kubectl config get-contexts               # expect ONLY sipbihs2aks until minikube starts
```

---

## Architecture Patterns

### System Architecture — local cluster data flow

```
   Browser (host)                                    Host services (compose, 0.0.0.0-published)
        |                                            ┌──────────────────────────────────────┐
        | /etc/hosts: app.jtoye.local ──┐            │ postgres  :5433   (jtoye_app / RLS)  │
        |             api.jtoye.local ──┼─> minikube │ keycloak  :8085   (KC_HOSTNAME=      │
        |                               │    ip      │                    localhost:8085)   │
        v                        192.168.49.2        │ redis     :6379                      │
  ┌──────────────────────────────────────────┐       │ rabbitmq  :5672 AMQP / :61613 STOMP  │
  │ ingress-nginx v1.12.2 (addon, hostPort   │       │ minio     :9000  (buckets:           │
  │  80/443 on the node)                     │       │            jtoye-images,             │
  │  · jtoye-ingress      host rules patched │       │            jtoye-db-backups ← NEW)   │
  │  · jtoye-sse-ingress  /api/v1/orders/    │       └──────────────────────────────────────┘
  └───────────┬──────────────────┬───────────┘                       ^   ^   ^   ^
              |                  |                                   |   |   |   |
     ┌────────v────────┐  ┌──────v──────────┐         host.minikube.internal (192.168.49.1)
     │ Service         │  │ Service         │                 (resolved via CoreDNS
     │  frontend :3000 │  │ core-java :9090 │                  NodeHosts, minikube-managed)
     └────────┬────────┘  └──────┬──────────┘                        |
              |                  |                                   |
   ┌──────────v──────────┐  ┌────v──────────────────────────┐        |
   │ Deployment frontend │  │ Deployment core-java          │────────┘
   │  replicas: 1        │  │  replicas: 1                  │  DB_PORT ← secretKeyRef(port=5433)
   │  NEXTAUTH_URL       │  │  SPRING_PROFILES_ACTIVE=prod  │  KC_ISSUER_URI  = h.m.i:8085 (JWKS)
   │  KEYCLOAK_ISSUER    │  │  probes → :9091 (management)   │  JWT_EXPECTED_ISSUER = localhost:8085
   │  KEYCLOAK_ISSUER_   │  │  STOMP_BROKER_MODE=relay      │  S3_ENDPOINT = h.m.i:9000
   │    INTERNAL  ← DEF-5│  │  CORS_ALLOWED_ORIGINS ← DEF-6 │  SMTP_HOST = h.m.i (mailhog 1025)
   │  NEXT_PUBLIC_API_URL│  └───────────────────────────────┘
   │   is BAKED, not env │           ^
   └─────────────────────┘           │  (in-cluster only, no ingress)
                                     │
     ┌───────────────────┐    ┌──────┴──────────────┐      ┌──────────────────────────────┐
     │ Deployment edge-go│    │ HPA min=1 max=10/20 │      │ CronJob pg-backup            │
     │  replicas: 1      │    │ PDB minAvailable: 1 │      │  DB_USER=jtoye_backup        │
     └───────────────────┘    └─────────────────────┘      │  (BYPASSRLS ← NEW role)      │
                                                            │  S3_ENDPOINT → h.m.i:9000    │
     ┌────────────────────────────────────────────┐         └──────────────────────────────┘
     │ 6 NetworkPolicies — RENDER but DO NOT      │
     │ ENFORCE (minikube default CNI). D-11.      │
     └────────────────────────────────────────────┘
```

Trace of the primary use case (vendor login → dashboard): browser resolves `app.jtoye.local` via
`/etc/hosts` → `minikube ip` → ingress-nginx → `frontend` Service → frontend pod issues the OIDC
authorization redirect to the browser-reachable Keycloak (`localhost:8085`) → browser authenticates →
callback to `app.jtoye.local/api/auth/callback/keycloak` → frontend pod exchanges the code
**server-side** against `KEYCLOAK_ISSUER_INTERNAL` (`host.minikube.internal:8085`) → dashboard page
calls the API at the baked `NEXT_PUBLIC_API_URL` → ingress → `core-java` Service → core validates the
token's `iss` against `JWT_EXPECTED_ISSUER` while fetching JWKS from `KC_ISSUER_URI`.

**Every arrow above with a `← DEF-n` marker is currently broken or absent.**

### Recommended structure

```
k8s/
├── base/                       # edited (DEF-1, DEF-4, DEF-5, DEF-6)
├── staging/                    # untouched
├── production/                 # untouched
├── local/                      # NEW
│   ├── kustomization.yaml      # namespace jtoye-local, labels, images, patches, replicas
│   ├── namespace.yaml
│   ├── configmap-patch.yaml    # host.minikube.internal shims + local issuer/CORS/S3 values
│   ├── scale-patch.yaml        # ONE multi-doc file: 3 HPAs + 3 PDBs → 1
│   ├── ingress-patch.yaml      # jtoye-ingress: local hosts, tls:null, snippet:null, ssl-redirect off
│   └── sse-ingress-patch.yaml  # jtoye-sse-ingress: same treatment
├── scripts/
│   └── check-env-contract.sh   # NEW (D-07/D-08) — sibling of check-connection-math.sh
└── LOCAL.md                    # NEW runbook, cross-linked from QUICK_START.md + DEPLOYMENT.md
scripts/
├── k8s-local-secrets.sh        # NEW (D-01/D-02/D-03)
└── k8s-local-up.sh             # NEW (D-04/D-14)
```

---

### P-1: `replicas:` list for Deployments — NOT for HPA/PDB

`replicas:` in a kustomization applies **only** to `Deployment, ReplicationController, ReplicaSet,
StatefulSet` [CITED: kubectl.docs.kubernetes.io/references/kustomize/kustomization/replicas/]. It does
**not** touch `HorizontalPodAutoscaler` or `PodDisruptionBudget`. So D-09's three targets need two
mechanisms.

```yaml
# k8s/local/kustomization.yaml (excerpt) — mirrors k8s/staging/kustomization.yaml:44-50
replicas:
  - name: core-java
    count: 1
  - name: edge-go
    count: 1
  - name: frontend
    count: 1
```

### P-2: ONE multi-doc strategic-merge patch for all 3 HPAs + all 3 PDBs

**[VERIFIED: built with `kubectl kustomize` v5.6.0 this session]** A single multi-document patch file
referenced once as `patches: - path: scale-patch.yaml` correctly targets six objects by GVK+name.
`maxReplicas` stays untouched (10 / 20 / 10 confirmed in the rendered output), which is what D-09
requires because `check-connection-math.sh` reads it. No JSON6902 patch is needed.

```yaml
# k8s/local/scale-patch.yaml — 6 documents in one file
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: core-java-hpa
spec:
  minReplicas: 1
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: frontend-hpa
spec:
  minReplicas: 1
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: edge-go-hpa
spec:
  minReplicas: 1
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: core-java-pdb
spec:
  minAvailable: 1
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: frontend-pdb
spec:
  minAvailable: 1
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: edge-go-pdb
spec:
  minAvailable: 1
```

Rendered result (verified): `replicas: 1` ×3, `minAvailable: 1` ×3, `minReplicas: 1` ×3,
`maxReplicas: 10 / 20 / 10` unchanged.

### P-3: ConfigMap patch merges keys (does not replace `data`)

**[VERIFIED: rendered staging build]** A strategic-merge patch on `app-config` merges the listed keys
into the base's 20 keys — `k8s/staging/configmap-patch.yaml` adds 6 and the rendered ConfigMap still
carries all base keys. So the local patch only lists what changes.

```yaml
# k8s/local/configmap-patch.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
data:
  redis.host: "host.minikube.internal"
  rabbitmq.host: "host.minikube.internal"
  stomp.broker.relay-host: "host.minikube.internal"
  s3.backup.endpoint: "http://host.minikube.internal:9000"   # base:45 is "" (real AWS)
  # ... plus the new D-13 / D-15 keys the base gains this phase
```

### P-4: Ingress — `rules:` REPLACES, and `null` DELETES

**[VERIFIED: built this session]** In a strategic-merge patch:
- `rules:` has no patch merge key on `Ingress`, so supplying `rules:` **replaces the whole list**.
  Rendered output showed exactly the two local hosts and the `auth.jtoye.co.uk → keycloak` rule
  **gone** — which is desirable, because `k8s/base/ingress.yaml:74-83` points at a `keycloak` Service
  that **does not exist in `k8s/base`** (a genuine dangling backend today).
- `key: null` on an annotation **removes it** — proven for `cert-manager.io/cluster-issuer`.
- `tls: null` **removes the whole TLS block** — proven. Necessary locally: there is no cert-manager,
  so `secretName: jtoye-tls` would never exist and nginx would fall back to its self-signed default.

```yaml
# k8s/local/ingress-patch.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: jtoye-ingress
  annotations:
    cert-manager.io/cluster-issuer: null                          # no cert-manager locally
    nginx.ingress.kubernetes.io/configuration-snippet: null       # MANDATORY — see PIT-1
    nginx.ingress.kubernetes.io/ssl-redirect: "false"
    nginx.ingress.kubernetes.io/force-ssl-redirect: "false"
    nginx.ingress.kubernetes.io/cors-allow-origin: "http://app.jtoye.local"
spec:
  tls: null
  rules:                                                          # REPLACES the base's 3 rules
    - host: api.jtoye.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend: { service: { name: core-java, port: { number: 9090 } } }
    - host: app.jtoye.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend: { service: { name: frontend, port: { number: 3000 } } }
```

`k8s/base/sse-ingress.yaml` gets the identical treatment (single rule, host `api.jtoye.local`, path
`/api/v1/orders/stream` `pathType: Exact`). nginx merges rules for the same host across Ingresses and
the exact path wins over the `/` prefix — that behaviour is already documented in the base file's own
header comment (`sse-ingress.yaml:9-10`).

### P-5: overlay namespace transformer beats the CronJob's hardcoded namespace

**[VERIFIED]** With `namespace: jtoye-local` in the overlay, the rendered CronJob shows
`namespace: jtoye-local` — confirming CONTEXT.md's read that `pg-backup-cronjob.yaml:5`'s
`namespace: jtoye-production` is cosmetic. Removing it is safe and is the correct config-hygiene fix
(it is also what makes `scripts/deploy.sh`-style raw `kubectl apply -f` less dangerous).

### P-6: `labels: includeSelectors: true` — what it actually touches

**[VERIFIED]** In the rendered local build the overlay pair `environment: local` (plus the base pairs)
lands in:
- `Deployment.spec.selector.matchLabels` — **immutable after creation**. Fine for a fresh
  `jtoye-local` namespace; a *later* label change requires delete+recreate. Record this in
  `k8s/LOCAL.md`.
- `Service.spec.selector`, `PodDisruptionBudget.spec.selector.matchLabels` — consistent within one
  build, so they match.
- `NetworkPolicy.spec.podSelector.matchLabels` **and every `podSelector` inside ingress/egress
  rules** — including `k8s-app: kube-dns`. See PIT-6; this is a pre-existing latent bug.
- `default-deny`'s `podSelector: {}` is left empty (verified) — the empty selector still selects all.

### Anti-patterns to avoid

- **Overlay-patching a `value:` env into `valueFrom:`** — produces an invalid EnvVar. See PIT-2.
- **`secretGenerator` in `k8s/local`** — emits `kind: Secret`; `check-no-plaintext-secrets.sh`
  auto-discovers `k8s/local` at `maxdepth 2` and fails the build (D-01's hard constraint, verified by
  reading `check-no-plaintext-secrets.sh:37`).
- **Adding a namespace to `k8s/base`** — collapses every overlay's namespace transformer
  ("namespace transformation produces ID conflict"), stated in `k8s/base/kustomization.yaml:5-8`.
- **Any port/host literal in `k8s-local-*.sh`** — violates config injection; use `.env` `K8S_LOCAL_*`.
- **Running `kubectl` without `--context jtoye`** — with no current-context set, a careless
  `kubectl config use-context` could select `sipbihs2aks` (employer AKS).

---

## Don't Hand-Roll

| Problem | Don't build | Use instead | Why |
|---------|-------------|-------------|-----|
| Setting HPA `minReplicas` per environment | JSON6902 patch, or a duplicated HPA manifest | One multi-doc strategic-merge patch (P-2) | Proven to work; JSON6902 needs per-object `target:` blocks and is far more verbose |
| Scaling Deployments in an overlay | strategic-merge patch on each Deployment | `replicas:` list | Idiomatic, matches `k8s/staging/kustomization.yaml:44-50`, one place |
| Removing base annotations locally | a forked copy of `ingress.yaml` in the overlay | `annotation: null` in a merge patch (P-4) | Keeps a single source of truth for the ingress; fork = permanent drift |
| Idempotent secret creation | `kubectl delete secret \|\| true; kubectl create` | `kubectl create secret … --dry-run=client -o yaml \| kubectl apply -f -` | D-01's stated pattern; no delete window, no error on re-run |
| BYPASSRLS backup role | new SQL, or a Flyway migration | `infra/backups/create-backup-role.sql` | Already idempotent + password-injected; **cannot** be Flyway (the migration role lacks BYPASSRLS grant — the file's own header says so) |
| Verifying a dump is non-empty | file-size threshold | `pg_restore --list` + **restore into a scratch DB and count rows** | The backup script's own `MIN_BACKUP_BYTES=1000` floor (`k8s-backup.sh:36`) passes on a schema-only, zero-row dump of 60 migrations. Only a row count falsifies it. `docs/runbooks/backups.md:234-237` records the proven recipe and the expected counts |
| `.env` preflight in the bootstrap | new validation logic | extend/reuse `scripts/verify-env.sh` | Already fail-loud, has a weak-secret deny-list, and **never prints values** (its own SECURITY note) |
| Reaching host services from pods | hardcoded `192.168.49.1` | `host.minikube.internal` | minikube maintains the mapping; the IP "varies by driver and potentially by cluster" [CITED: minikube.sigs.k8s.io/docs/handbook/host-access/] |
| An ingress-nginx security-headers replacement | a custom sidecar / server-snippet workaround | drop the snippet locally + document; enable `allow-snippet-annotations` only if staging/prod need it | Snippet annotations are a documented Critical-risk class disabled by default since ingress-nginx 1.9 |

**Key insight:** almost every "how do I patch X" question in this phase already has a proven in-repo
precedent (`k8s/staging/`, `check-connection-math.sh`, `verify-env.sh`, `create-backup-role.sql`,
`backups.md`). The genuinely new artefacts are exactly three: the local overlay directory, the two
bootstrap scripts, and the env-contract gate.

---

## The Env-Contract Gate (D-07 / D-08) — concrete design

### Recommendation: **bash script at `k8s/scripts/check-env-contract.sh`**, not a JUnit test

| Criterion | bash under `k8s/scripts/` | JUnit test |
|-----------|---------------------------|-----------|
| Precedent | `check-connection-math.sh` **already parses both** `k8s/base/core-java-deployment.yaml` and `application{,-prod,-staging}.yml` — literally the same two file families | `RlsContractTest` parses a *database*, not repo YAML |
| CI wiring | drop-in 4th step in the existing `k8s-validate` job (`ci-cd.yaml:191-211`); no JVM, no Testcontainers, ~1s | needs the `test` task, and the k8s YAML lives outside the classpath → brittle `Paths.get("../k8s/...")` relative to Gradle's working dir |
| `docs/metrics.json` | **0 delta** — `docs-freshness.sh` counts no bash | +N `@Test` → forces `scripts/docs-freshness.sh --write` in the same PR; metrics.json is a documented cross-branch merge-conflict hotspot |
| Local runnability | `./k8s/scripts/check-env-contract.sh` | `./gradlew :core-java:test --tests …` |
| Exit-code convention | reuse 0/1/2 from siblings | JUnit assertions |

Trade-off accepted: the phase adds no test-count growth from the gate itself. Growth should come from
the Playwright/behavioural proofs in §Validation Architecture instead.

### Parsing rules (each one is a real trap found in the actual files)

1. **Injected-env extraction** — `k8s/base/core-java-deployment.yaml`, regex anchored to the env-list
   item indent: `^\s+- name: ([A-Z0-9_]+)\s*$`. Verified to yield exactly 23 names.
2. **Placeholder extraction** — `application.yml`, `application-prod.yml`, `application-staging.yml`.
   The regex **must tolerate one level of nesting**:
   `\$\{([A-Z0-9_]+)(?::((?:[^{}]|\$\{[^}]*\})*))?\}`.
   A naive `\$\{([A-Z_]+):([^}]*)\}` mis-terminates on the two real nested defaults:
   `application.yml:142` `${JWT_EXPECTED_ISSUER:${spring.security.oauth2.resourceserver.jwt.issuer-uri}}`
   and `application.yml:154` `${CUSTOMER_JWT_EXPECTED_ISSUER:${jtoye.security.customer-jwt.issuer-uri}}`.
3. **Uppercase filter is the env/property discriminator** — `${spring.application.name}` and
   `${jtoye.security…}` are Spring *property* references, not env vars. `[A-Z0-9_]+` excludes them
   correctly (verified: no false positives in 116 results).
4. **Profile scope: all six `application*.yml` are equivalent for this gate.** Verified: the set of
   placeholders appearing *only* in `application-dev.yml` / `-test.yml` / `-local.yml` is **empty**
   (0 names). So scanning `application*.yml` and scanning just base+prod+staging give the same
   116-name set. Scan all — simpler, and no allowlist entries are needed for dev-only vars.
5. **One name can have several different defaults across profiles.** Real cases: `DB_POOL_SIZE`
   (`10`|`20`), `RABBITMQ_USER` (`jtoye`|`guest`), `CUSTOMER_JWT_REQUIRE_VERIFIED_EMAIL`
   (`false`|`true`), `REDIS_PASSWORD` (no-default in prod/staging, empty in base). The gate must
   collect a **set** of defaults per name and trip the localhost rule if **any** member is local-only.
6. **Spring-native env names are not `${}` placeholders** and must be allowlisted on the
   injected-but-unread side: `SPRING_PROFILES_ACTIVE` (relaxed binding), and if ever added,
   `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`, `JAVA_OPTS`, `TZ`.
7. **Bare-word local defaults matter too, not just URLs.** `${RABBITMQ_USER:guest}` and
   `${S3_ACCESS_KEY:minioadmin}` are the DEF-4 / DEF-6 signature. A regex of only
   `localhost|127\.0\.0\.1` misses both. Include a word-list: `localhost`, `127.0.0.1`, `0.0.0.0`,
   `minioadmin`, `guest`, `mailhog`, `host.docker.internal`. (Caution: with defaults `jtoye|guest`,
   a `^guest$`-anchored test misses it — match per-default, not on a joined string.)

### Direction (a): injected by k8s but read by no `application*.yml`

**Current true state — 4 names.** Two of them ARE DEF-4:

| Env name | Verdict | Disposition |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | Spring-native, no `${}` | **Allowlist** — "Spring relaxed-binding env, not a placeholder" |
| `RABBITMQ_USERNAME` | **DEF-4** — no `application*.yml` reads it | **Fixed** by D-05's rename to `RABBITMQ_USER` |
| `STOMP_CLIENT_LOGIN` | **DEF-4** — dead env | **Fixed** by D-05's `${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}}` chain |
| `STOMP_CLIENT_PASSCODE` | **DEF-4** — dead env | **Fixed** by D-05's passcode equivalent |

After the D-05 fix, **the allowlist for direction (a) is a single entry**. That is the cleanest
possible outcome and is strong evidence the gate is correctly scoped.

### Direction (b): expected by Spring, classified

116 distinct placeholders. **19 supplied by k8s** (23 injected − 4 dead). The remaining 97:

| Bucket | Count | Meaning | Gate verdict |
|--------|-------|---------|--------------|
| **B — no default at all** | **0** | would hard-fail boot if unsupplied | none exist. (`REDIS_PASSWORD` is the only no-default and it *is* supplied.) |
| **C — localhost-ish default, unsupplied** | **13** | the DEF-6 class | **FAIL unless allowlisted with a reason** |
| **D — empty default, unsupplied** | **12** | feature-off-by-default | allowlist with reason (mostly correct) |
| **E — safe non-local default, unsupplied** | **72** | tuning knobs | pass by rule (no local-only default) |

**Bucket C in full — this is the D-15 work-list and the allowlist seed:**

| Env name | Default (`application.yml` line) | D-15 covers it? | Impact if left |
|----------|----------------------------------|-----------------|----------------|
| `S3_ENDPOINT` | `http://localhost:9000` (:295) | ✓ | media upload fails / writes nowhere |
| `S3_ACCESS_KEY` | `minioadmin` (:298) | ✓ | ditto |
| `S3_SECRET_KEY` | `minioadmin` (:299) | ✓ | ditto |
| `S3_PUBLIC_URL` | `http://localhost:9000/jtoye-images` (:300) | ✓ | every image URL in every DTO points at localhost |
| `SMTP_HOST` | `localhost` (:77) | ✓ | all notification email silently fails |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` (:393) | ✓ | **blocks BOTH the dashboard API calls and the KDS WebSocket handshake** — `CorsConfig.java:16` *and* `WebSocketConfig.java:57-58,85` read it. **Prerequisite for D-06's proof.** |
| `NOTIFICATION_EMAIL_TRACKING_BASE_URL` | `http://localhost:3000` (:359) | ✗ **NOT in D-15** | every email tracking pixel in prod points at localhost |
| `NOTIFICATION_UNSUBSCRIBE_BASE_URL` | `http://localhost:3000` (:367) | ✗ **NOT in D-15** | **every prod unsubscribe link is dead** — a GDPR/CAN-SPAM-adjacent defect |
| `STRIPE_CONNECT_RETURN_URL` | `http://localhost:3000/dashboard/payments/connect/return` (:327) | ✗ **NOT in D-15** | **Stripe Connect onboarding returns the vendor to localhost** in prod |
| `STRIPE_CONNECT_REFRESH_URL` | `http://localhost:3000/dashboard/payments/connect/refresh` (:328) | ✗ **NOT in D-15** | ditto |
| `OLLAMA_URL` | `http://localhost:11434` (:308) | ✗ | vision stage advisory-only; low impact (`MEDIA_VISION_ENABLED` defaults false) |
| `ZIPKIN_ENDPOINT` | `http://localhost:9411/api/v2/spans` (:275) | ✗ | tracing export silently drops |
| `CUSTOMER_KC_ISSUER_URI` | `http://localhost:8085/realms/jtoye-customers` (:153) | explicitly **deferred** in CONTEXT.md | customer realm unconfigured in every k8s env |

Bucket D notables for the reasoned allowlist: `NOTIFICATION_UNSUBSCRIBE_SECRET` (empty ⇒ HMAC over an
empty key — security-relevant, flag it), `STRIPE_API_KEY` / `STRIPE_WEBHOOK_SECRET` (D-15 covers),
`SMTP_USERNAME` / `SMTP_PASSWORD` (correct for mailhog; must be settable for SES),
`ACCESS_MACHINE_CLIENT_IDS` (Phase 25 VSA-02; empty is the correct k8s default),
`COMPANIES_HOUSE_API_KEY`, `ANTHROPIC_API_KEY`, the four `WHATSAPP_*`.

Bucket E notables: `JWT_EXPECTED_AUDIENCE` (default `core-api`, D-15 wants it explicit anyway),
`LOG_PATH` (default `/var/log/jtoye` — see PIT-5), `RABBITMQ_USER` (becomes k8s-supplied after D-05).

**Planner decision needed:** the four unflagged prod defects (`NOTIFICATION_*_BASE_URL`,
`STRIPE_CONNECT_*_URL`) are the same class D-15 exists to close and cost ~4 configmap keys + 4 env
entries. Recommend folding them in and noting the scope addition, rather than allowlisting a known
prod defect as "accepted".

---

## Backup Rehearsal Mechanics (INFRA-02c / #101)

`infra/backups/k8s-backup.sh` contract, read from source:

| Variable | Required? | Source in the CronJob | Local value |
|----------|-----------|------------------------|-------------|
| `DB_HOST` | **yes** (`:26`) | `postgres-credentials/host` | `host.minikube.internal` |
| `DB_PORT` | no, default `5432` (`:33`) | `postgres-credentials/port` (`cronjob:46-50`) | **`5433`** — the same secret key DEF-1 routes core through |
| `DB_NAME` | **yes** (`:27`) | `postgres-credentials/database` | `jtoye` |
| `DB_USER` | **yes, must be BYPASSRLS** (`:28`) | `postgres-credentials/backup-username` | `jtoye_backup` |
| `PGPASSWORD` | **yes** (`:29`) | `postgres-credentials/backup-password` | new `.env` key |
| `S3_BUCKET` | **yes** (`:30`) | `app-config/s3.backup.bucket` | `jtoye-db-backups` |
| `S3_PREFIX` | no, default `backups` | `app-config/s3.backup.prefix` | `backups` |
| `S3_ENDPOINT` | no; **when set → `--endpoint-url`** (`:37-40`) | `app-config/s3.backup.endpoint` (base:45 is `""`) | `http://host.minikube.internal:9000` |
| `AWS_DEFAULT_REGION` | — | `app-config/s3.backup.region` | `eu-west-2` (MinIO ignores it) |
| `AWS_ACCESS_KEY_ID` / `_SECRET_ACCESS_KEY` | — | `s3-backup-credentials/access-key`,`/secret-key` | `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` from `.env` |
| `RETENTION_DAYS` | no, default 30 | literal `"30"` (`cronjob:98-99`) | 30 |
| `MIN_BACKUP_BYTES` | no, default 1000 (`:36`) | not set | see the size-floor caveat below |

**Correction to a canonical ref:** CONTEXT.md cites `pg-backup-cronjob.yaml:64-68` as the
`secretKeyRef` `port` precedent. The actual `DB_PORT` → `secretKeyRef(port)` block is at
**lines 46-50**; 61-65 is `PGPASSWORD` → `backup-password`. Grep for `key: port`, not the line number.

**Blockers found:**

- **The `jtoye-db-backups` bucket does not exist.** Live MinIO `/data` contains only `jtoye-images`;
  compose's `minio-init` creates only that one (`docker-compose.full-stack.yml:426`). `aws s3 cp` to a
  missing bucket fails with `NoSuchBucket` — the CronJob would fail at the upload step *after* a
  successful dump. The bootstrap must create it. **Not mentioned in CONTEXT.md.**
- **The `jtoye_backup` role does not exist** (live `pg_roles` query). D-02 confirmed necessary.
- **The size floor is not a non-empty proof.** `MIN_BACKUP_BYTES=1000` + `pg_restore --list` both pass
  on a schema-only dump; 60 Flyway migrations of DDL comfortably exceed 1 KiB with zero data rows.
  This is precisely the failure mode D-02 exists to prevent, and the *size check cannot detect it*.

**On-demand trigger:**
```bash
kubectl --context jtoye -n jtoye-local create job pg-backup-rehearsal --from=cronjob/pg-backup
kubectl --context jtoye -n jtoye-local logs job/pg-backup-rehearsal
```

**Proven MinIO precedent:** `docs/runbooks/backups.md:228-237` records a complete local
backup+restore drill on 2026-07-10 (133 KiB dump, uploaded to `s3://jtoye-db-backups/backups/`,
restored with `products=25, orders=57, customers=4, shops=10`) — i.e. aws-cli v1 + `--endpoint-url` +
MinIO is a *verified* working combination in this repo. The runbook's own open checkbox is literally
"CronJob completes **in-cluster** (exit 0)" — exactly what this phase closes. Append the new result;
do not rewrite the dated section.

---

## Common Pitfalls

### PIT-1 — ingress-nginx v1.12.2 REJECTS the base Ingress (`configuration-snippet`) — HIGH
**What goes wrong:** `kubectl apply -k k8s/local` (and likely the server dry-run) fails with
`admission webhook "validate.nginx.ingress.kubernetes.io" denied the request` for both Ingress
objects. Nothing else deploys cleanly around it.
**Why:** `k8s/base/ingress.yaml:29-35` carries `nginx.ingress.kubernetes.io/configuration-snippet`
(6 `more_set_headers` security headers). ingress-nginx defaults are
`allow-snippet-annotations: "false"` and `annotations-risk-level: High`
[CITED: kubernetes.github.io/ingress-nginx/user-guide/nginx-configuration/configmap/]; snippet
annotations are the Critical-risk group, disabled by default since v1.9 as a security hardening.
minikube v1.36.0 bundles **ingress-nginx/controller:v1.12.2** [VERIFIED: `strings /usr/local/bin/minikube`].
**How to avoid:** null the annotation in `k8s/local/ingress-patch.yaml` (P-4 — proven that the
annotation survives a patch unless explicitly nulled) and state in `k8s/LOCAL.md` that local does not
prove the security-header snippet. Alternative (heavier, not recommended): patch the addon's
`ingress-nginx-controller` ConfigMap with `allow-snippet-annotations: "true"` +
`annotations-risk-level: "Critical"` in `k8s-local-up.sh` — that mutates a cluster addon and weakens
the local cluster's own posture.
**Warning signs:** the error text names the annotation. Also flag for staging/prod: whichever
ingress-nginx version runs there must have snippets enabled, or the headers are silently absent (or
the apply has been failing).

### PIT-2 — strategic merge cannot turn `value:` into `valueFrom:` — HIGH
**What goes wrong:** the rendered container env contains
```yaml
- name: DB_PORT
  value: "5432"
  valueFrom:
    secretKeyRef: {key: port, name: postgres-credentials}
```
`kubectl kustomize` emits this without complaint, but the API server rejects it
(`env[i].valueFrom: Invalid value: "": may not be specified when 'value' is not empty`) — `value` and
`valueFrom` are mutually exclusive in `EnvVar`.
**Why:** `containers[].env` has patch merge key `name`, so the patch item *merges into* the base item;
merging cannot remove a scalar field.
**How to avoid:** **DEF-1 must delete `value: "5432"` in `k8s/base/core-java-deployment.yaml:71-72`**
and add `valueFrom` there. This is what INFRA-02(a) says — the finding is that there is no overlay
shortcut. If a future overlay genuinely needs to flip an env's source, it needs a JSON6902 `remove` on
`/spec/template/spec/containers/0/env/N/value` (index-fragile) or `$patch: replace` on the list item.
**Warning signs:** `kubectl kustomize k8s/local | grep -A3 'name: DB_PORT'` showing both keys.
**[VERIFIED: reproduced this session with kubectl 1.33.3 / kustomize 5.6.0]**

### PIT-3 — `NEXT_PUBLIC_API_URL` is frozen at Docker build time; the k8s env is dead config — HIGH
**What goes wrong:** the vendor logs in through the ingress, lands on the dashboard, and **every API
call goes to `http://localhost:9090`** — which is the compose core-java the XOR rule just told you to
shut down. D-16's Playwright proof cannot pass.
**Why:** `frontend/Dockerfile:22-23` takes `NEXT_PUBLIC_API_URL` as a build ARG→ENV, and compose bakes
`http://localhost:9090` (`docker-compose.full-stack.yml:304`). Next.js docs are explicit: *"Next.js can
'inline' a value, at build time, into the js bundle … replacing all references to
`process.env.[variable]` with a hard-coded value"* and *"After being built, your app will no longer
respond to changes to these environment variables … if you build and deploy a single Docker image to
multiple environments, all `NEXT_PUBLIC_` variables will be frozen with the value evaluated at build
time"* [CITED: nextjs.org/docs/app/guides/environment-variables, v16.2.11].
`frontend/lib/api-client.ts:21` and `frontend/lib/public-api-client.ts:4` both read it directly.
**Corollary — a 7th defect:** `k8s/base/frontend-deployment.yaml:49-53` injects `NEXT_PUBLIC_API_URL`
from `app-config/api.url`. **That injection is inert in every k8s environment** — exactly the DEF-6
class ("an env that looks like it configures something but does not"), and it means staging/prod
frontends have been serving whatever API URL CI baked, not what the ConfigMap says. Verify what
`ci-cd.yaml` passes as the frontend build arg; the plan should either remove the dead env with a
comment or make it genuinely runtime (a `/api/config` endpoint — out of scope).
**How to avoid:** `k8s-local-up.sh` builds a **local-specific** frontend image:
```bash
docker build -t ghcr.io/bralabee/jtoye-frontend:local \
  --build-arg NEXT_PUBLIC_API_URL="http://api.jtoye.local" \
  --build-arg NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL="$..." \
  --build-arg NEXT_PUBLIC_SUPPORT_EMAIL="$..." --build-arg NEXT_PUBLIC_SUPPORT_URL="$..." \
  --build-arg NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS="$..." \
  -f frontend/Dockerfile ./frontend
```
and the overlay's `images:` block pins `newTag: local` for the frontend. Note
`NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` is `:?`-required in compose, and the three
`NEXT_PUBLIC_SUPPORT_*`/SLA args degrade the onboarding support exit if omitted.
**Also:** `scripts/build-images.sh` is *not* usable here — it tags `ghcr.io/jtoye/<svc>:<tag>`
(a third naming scheme, matching neither the manifests' `ghcr.io/bralabee/jtoye-<svc>` nor compose's
`jtoye_oaas_2026-<svc>`) and passes **no** `NEXT_PUBLIC_*` build args at all. Either fix it as part of
D-14 or bypass it.

### PIT-4 — the `ghcr.io/bralabee/jtoye-*:2.1.0` images on this host are STALE — HIGH
**What goes wrong:** `minikube image load` succeeds, pods go READY, and you are rehearsing **Phase 22
code**. Flyway would attempt V52-V59 against… no: an old core-java image would carry only up to V51's
migrations, so the app boots against a V59 database and Flyway `validate-on-migrate: true` fails, or
newer tables are simply unused.
**Why:** verified creation dates — core-java **2026-07-13 15:14**, edge-go **2026-07-13 12:31**,
frontend **2026-07-14 00:01**. Phases 23, 24, 25 all shipped after that. CONTEXT.md's discretion note
("locally-built compose images already tag as `ghcr.io/bralabee/jtoye-*:2.1.0`") is **inaccurate**:
`docker compose build` produces `jtoye_oaas_2026-<svc>:latest` (verified in `docker compose ps` and
`docker images`); the ghcr-tagged images exist only because they were hand-built for the 2026-07-14
rehearsal.
**How to avoid:** `k8s-local-up.sh` builds (or retags from a fresh compose build) all four images
with the manifest-matching names before `minikube -p jtoye image load`. Record the image digests in
the rehearsal evidence so "which code did we prove?" is answerable.

### PIT-5 — prod profile writes logs to a directory the pod cannot create — MEDIUM
**What goes wrong:** noisy logback `FileNotFoundException … Permission denied` on every core-java pod
start under `SPRING_PROFILES_ACTIVE=prod` (D-10), and file logging silently absent.
**Why:** `application-prod.yml:91` sets `logging.file.name: ${LOG_PATH:/var/log/jtoye}/application.log`.
`core-java/Dockerfile` never creates `/var/log/jtoye`; the container runs as `runAsUser: 1000` with
`readOnlyRootFilesystem: false`, and `/var/log` is root-owned. Logback's FileAppender fails to start,
records an error status, and the application continues — so this is noise + a missing capability, not
a crash (consistent with the 2026-07-14 11/11 READY result).
**How to avoid:** cleanest is `LOG_PATH=/tmp` via the local configmap patch (`LOG_PATH` is already an
env-overridable knob, bucket E). Better long-term: an `emptyDir` mounted at `/var/log/jtoye` in the
base. Either way, mention it in `k8s/LOCAL.md` so the log line is not mistaken for a real fault.
**Warning signs:** `kubectl logs` showing a logback status error near the top of the boot sequence.

### PIT-6 — `includeSelectors: true` poisons the NetworkPolicy kube-dns selector (pre-existing, prod) — MEDIUM
**What goes wrong:** under a policy-enforcing CNI, `core-java-allow`'s DNS egress rule selects pods
matching `{k8s-app: kube-dns, app.kubernetes.io/managed-by: kustomize, app.kubernetes.io/part-of:
jtoye-platform}` — labels real kube-dns pods do not carry. The rule matches nothing ⇒ **total DNS
blackhole** for core-java.
**Why:** `k8s/base/kustomization.yaml:41-45` sets `labels: [{pairs: …, includeSelectors: true}]`, and
kustomize applies selector labels to **every** `podSelector.matchLabels` it finds, including those
nested inside NetworkPolicy `egress[].to[].podSelector`.
**[VERIFIED]** present in the rendered `k8s/base` **and** `k8s/production` builds today, not just the
new local overlay. It is invisible to `k8s/scripts/validate-networkpolicies.py` because that script
parses the **raw** files in `k8s/base/networkpolicies/`, never the kustomize output — and that script
is **not wired into CI** at all (only the two bash gates run in `k8s-validate`).
**How to avoid (this phase):** nothing — D-11 already declares local does not prove enforcement, and
minikube's default CNI does not enforce. **Record it as a finding** in `k8s/LOCAL.md` / the deferred
list so the "Calico locally" follow-up and any AKS network-policy rollout starts from the truth. The
real fix (set `includeSelectors: false` and add pod-template labels explicitly, or exclude
NetworkPolicy from the transformer) is a separate change with immutable-selector consequences.

### PIT-7 — the netpol egress `except: 192.168.0.0/16` excludes `host.minikube.internal` — LOW (inert)
`k8s/base/networkpolicies/20-core-java.yaml:92-101` allows public egress to `0.0.0.0/0` **except**
RFC1918, and the in-cluster allow targets `namespaceSelector: jtoye-infrastructure` — which does not
exist locally. So the *entire* local traffic pattern (host gateway `192.168.49.1`, ports
5433/8085/6379/5672/61613/9000/1025) is denied by the rendered policy set. Inert on minikube's default
CNI; this is the concrete evidence behind D-11's "an enforcing CNI would need explicit egress for
`host.minikube.internal`". Cite the CIDR + ports in `k8s/LOCAL.md` rather than the vague statement.

### PIT-8 — server dry-run needs the namespace to exist first — MEDIUM
`kubectl apply -k k8s/local --dry-run=server` fails on every namespaced object if `jtoye-local` does
not yet exist, because the dry-run does not create the Namespace it is validating. Documented in the
`k8s-kustomize-deploy` memory as the 2026-07-14 chicken-and-egg. Sequence:
```bash
kubectl --context jtoye apply -f k8s/local/namespace.yaml
kubectl --context jtoye apply -k k8s/local --dry-run=server
```

### PIT-9 — the existing STOMP/kitchen E2E specs hardcode `domain: "localhost"` — MEDIUM
`frontend/e2e/stomp-relay.spec.ts:37` adds the stub session cookie with `domain: "localhost"`; a
browser at `app.jtoye.local` will not send it. `playwright.config.ts:13` already reads
`PLAYWRIGHT_BASE_URL`, so the base URL is config-injected — but the **cookie domain is not**. Either
parameterise the cookie domain, or (better for D-16) drive a **real** Keycloak login rather than a
stub cookie, since a real login is the only thing that proves DEF-5.

### PIT-10 — ingress rate-limit annotations can throttle the E2E run — LOW
`k8s/base/ingress.yaml:13-15` sets `limit-rps: "100"`, `limit-burst-multiplier: "5"`,
`limit-connections: "50"`. A Playwright run from one source IP can plausibly trip
`limit-connections`. If the run shows sporadic 503s, null those three annotations in the local patch.
The SSE ingress deliberately omits them (`sse-ingress.yaml:42-45`).

### PIT-11 — `host.minikube.internal` requires host services on 0.0.0.0 AND an unblocked host firewall — LOW
The minikube docs state services *"must listen on all interfaces (0.0.0.0) … binding exclusively to
localhost (127.0.0.1) will not work"* [CITED: minikube.sigs.k8s.io/docs/handbook/host-access/].
**Verified good**: every compose port publishes as `0.0.0.0:` (from `docker compose ps`). Remaining
risk is a host firewall (ufw/firewalld) blocking the `192.168.49.0/24` bridge. Probe from inside the
cluster before blaming the manifests:
```bash
minikube -p jtoye ssh -- "nc -vz host.minikube.internal 5433; nc -vz host.minikube.internal 8085"
```

### PIT-12 — `check-connection-math.sh` is NOT at risk from DEF-1, but IS fragile to env reordering — LOW
Re-run **green** after inspection. It parses `maxReplicas` (first match, only one HPA in the file) and
`DB_POOL_SIZE` via `awk '/name: DB_POOL_SIZE/{getline; if ($1=="value:")…}'` — i.e. it requires
`value:` to be **the immediately next line** after `- name: DB_POOL_SIZE`. Neither is touched by
converting `DB_PORT` or by appending new env entries. **But:** inserting a comment between
`- name: DB_POOL_SIZE` and its `value:` would break it with `PARSE ERROR` (exit 2). Also note strategic
merge **reorders** env (patched items move to the front of the list, verified) — harmless today
because no env uses `$(VAR)` interpolation, but it would matter if one ever did. Re-run both gates
after every manifest edit.

### PIT-13 — the QUICK_START "omitting stomp-login fails pods" claim is *true*, just not for the reason implied
CONTEXT.md calls the note at `k8s/QUICK_START.md:68` "now-known-false". Precisely: a `secretKeyRef` to
a **missing key** in an existing Secret *does* put the pod in `CreateContainerConfigError` (no
`optional: true` is set) — so the operational claim holds. What is false is the *implication* that
`STOMP_CLIENT_LOGIN`/`_PASSCODE` reach Spring configuration; today they reach nothing (DEF-4). After
D-05 both statements become true. Reword rather than delete, so the operator does not stop creating a
key that the manifest still requires.

---

## Runtime State Inventory

This phase renames env vars and repoints endpoints, and the proof runs against live systems — so
runtime state matters as much as files.

| Category | Items found | Action required |
|----------|-------------|------------------|
| **Stored data** | Host Postgres `jtoye` @ **V59**. Role `jtoye_backup` (BYPASSRLS) **absent** — verified via `pg_roles`. Roles present: `jtoye` (superuser + BYPASSRLS — the DEF-2 wrong role), `jtoye_app` (NOSUPERUSER, no BYPASSRLS — correct). No `rls_test_role` in this DB. | **Bootstrap the role** (D-02) via `create-backup-role.sql` as superuser. No data migration. |
| | Host MinIO buckets: **only `jtoye-images`**. `jtoye-db-backups` **absent**. | **Create the bucket** in the bootstrap. Not in CONTEXT.md. |
| **Live service config** | minikube profile `jtoye` exists but **Stopped** (IP 192.168.49.2, k8s v1.33.1). Any namespace/secret/configmap from the 2026-07-14 imperative rehearsal is gone with the stopped cluster's state? **UNVERIFIED** — minikube `Stopped` preserves etcd, so a `jtoye-production`/other namespace with the old imperative Secrets may still exist on restart. | On first start, inspect (`kubectl --context jtoye get ns,secrets -A`) and decide: reuse a clean new `jtoye-local` namespace (recommended) and delete stale namespaces from the rehearsal. Documented in `k8s/LOCAL.md`. |
| | minikube **addons**: ingress not confirmed enabled; metrics-server not needed. | `minikube addons enable ingress -p jtoye` in `k8s-local-up.sh` (idempotent). |
| | Compose **app** containers running right now (core-java, frontend, edge-go, mcp-server). | D-04's XOR guard must refuse; operator brings them down. |
| **OS-registered state** | Host `/etc/hosts` needs `<minikube ip> app.jtoye.local api.jtoye.local` (D-12). Requires **sudo** — cannot be done by an unprivileged script. | `k8s-local-up.sh` must **detect and instruct** (print the exact line + `sudo` command), not silently attempt. Assert resolution before the Playwright step. |
| | kubectl **current-context is unset**; only context is `sipbihs2aks` (employer AKS). | Every command pins `--context jtoye`. Add an explicit guard in the scripts: refuse if the resolved context is not `jtoye`. |
| **Secrets / env vars** | `.env` present with `DB_USER`, `DB_PASSWORD`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `REDIS_PASSWORD`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `NEXTAUTH_SECRET`, `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET`, `KC_SEED_USER_PASSWORD`, `EDGE_API_CLIENT_SECRET`, `INTEGRATION_*_SECRET`, `CUSTOMER_*`, `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL`. **`DB_BACKUP_PASSWORD` and all `K8S_LOCAL_*` are absent** — new keys, as CONTEXT.md states. | Add the new keys to `.env` (gitignored) **and** document them in `.env.example` (tracked). Naming note: `k8s/QUICK_START.md` already uses the shell name `POSTGRES_BACKUP_PASSWORD` for the same value — pick one and make both docs agree. |
| | `RABBITMQ_USERNAME` → `RABBITMQ_USER` rename is a **k8s-manifest-only** rename. Compose already sets `RABBITMQ_USER` (`:213`). No secret **key** changes (`rabbitmq-credentials/username` stays). | Code/manifest edit only; no secret rotation. |
| **Build artifacts / installed packages** | `ghcr.io/bralabee/jtoye-{core-java,frontend,edge-go}:2.1.0` present but **stale (2026-07-13/14)**; `ghcr.io/bralabee/jtoye-pg-backup:15` (2026-07-10, Dockerfile unchanged → still valid); compose images `jtoye_oaas_2026-*:latest` are current but **wrongly named** for the manifests and (frontend) wrongly baked. | Rebuild + retag all four before `minikube image load`. PIT-3/PIT-4. |
| | `docs/metrics.json` total is **1690**; CLAUDE.md prose says 1684. | If any counted test file changes, run `scripts/docs-freshness.sh --write`; do not hand-edit. |

---

## Code Examples

### Overlay kustomization (mirrors `k8s/staging/kustomization.yaml`, built green this session)

```yaml
# k8s/local/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: jtoye-local

resources:
  - ../base
  - namespace.yaml

labels:
  - pairs:
      environment: local
    includeSelectors: true

# NOTE: no commonAnnotations deployment.timestamp here — staging/production stamp
# REPLACE_WITH_DEPLOYMENT_TIMESTAMP at deploy time; local has no CI stamp step and
# check-no-plaintext-secrets.sh only exempts that exact annotation.

images:
  - name: ghcr.io/bralabee/jtoye-core-java
    newTag: "local"
  - name: ghcr.io/bralabee/jtoye-edge-go
    newTag: "local"
  - name: ghcr.io/bralabee/jtoye-frontend
    newTag: "local"     # MUST be built with NEXT_PUBLIC_API_URL=http://api.jtoye.local (PIT-3)

patches:
  - path: configmap-patch.yaml
  - path: scale-patch.yaml          # 6 docs: 3 HPA + 3 PDB (P-2)
  - path: ingress-patch.yaml
  - path: sse-ingress-patch.yaml

replicas:
  - name: core-java
    count: 1
  - name: edge-go
    count: 1
  - name: frontend
    count: 1
```

### DEF-1 fix — must be in the base (PIT-2)

```yaml
# k8s/base/core-java-deployment.yaml — replace lines 71-72 entirely.
# The `value: "5432"` line MUST GO; leaving it and adding valueFrom in an overlay
# produces an EnvVar with both fields, which the API server rejects.
        - name: DB_PORT
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: port          # same key pg-backup-cronjob.yaml:46-50 already uses
```

### DEF-4 fix — additive fallback chain (Incremental Betterment)

```yaml
# core-java/src/main/resources/application.yml:222-231
stomp:
  broker:
    mode: ${STOMP_BROKER_MODE:in-memory}
    relay-host: ${STOMP_RELAY_HOST:localhost}
    relay-port: ${STOMP_RELAY_PORT:61613}
    client-login:     ${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}}
    client-passcode:  ${STOMP_CLIENT_PASSCODE:${RABBITMQ_PASSWORD:guest}}
    system-login:     ${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}}
    system-passcode:  ${STOMP_CLIENT_PASSCODE:${RABBITMQ_PASSWORD:guest}}
```
Compose (which sets `RABBITMQ_USER`/`RABBITMQ_PASSWORD` and no `STOMP_CLIENT_*`) resolves to exactly
today's values — behaviour-identical. k8s (which sets both after the D-05 rename) prefers the
dedicated STOMP credential. Nested-default parsing is a real Spring feature and is the same shape
already used at `application.yml:142`.

### On-demand rollout + boot proof

```bash
CTX=jtoye; NS=jtoye-local
kubectl --context $CTX -n $NS rollout status deploy/core-java --timeout=5m
kubectl --context $CTX -n $NS rollout status deploy/frontend  --timeout=3m
kubectl --context $CTX -n $NS rollout status deploy/edge-go   --timeout=3m

# DEF-2 proof: booted as the NOSUPERUSER app role (DatabaseConfigurationValidator, ApplicationReadyEvent)
kubectl --context $CTX -n $NS logs deploy/core-java | grep -E "Database username:|is NOT a superuser|DATABASE SECURITY VALIDATION PASSED"

# DEF-4 proof: no guest rejection anywhere in the boot log
kubectl --context $CTX -n $NS logs deploy/core-java | grep -c "Access refused for user"   # must be 0

# DEF-1 proof: the running pod actually connected on 5433
kubectl --context $CTX -n $NS get deploy/core-java -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="DB_PORT")]}'
```

---

## State of the Art

| Old approach | Current approach | When changed | Impact here |
|--------------|------------------|--------------|-------------|
| `bases:` in a kustomization | `resources:` | kustomize v2.1+ / deprecated v4 | `k8s/staging` already uses `resources:` — copy that |
| `commonLabels:` | `labels: [{pairs, includeSelectors}]` | kustomize v4.5+ | Already migrated in this repo; note it now also hits NetworkPolicy selectors (PIT-6) |
| `patchesStrategicMerge:` | `patches: [{path}]` (auto-detects merge vs JSON6902) | kustomize v4.5+ | Already used by staging/production; multi-doc merge patch files are supported (P-2, verified) |
| ingress-nginx snippet annotations enabled by default | `allow-snippet-annotations: false` + `annotations-risk-level: High` | ingress-nginx **v1.9.0** (Oct 2023 security hardening) | PIT-1 — the base ingress predates this and is now rejected by default |
| `management.metrics.export.prometheus.enabled` | `management.prometheus.metrics.export.enabled` | Spring Boot 3 | already fixed in `application-prod.yml:123-126` (recorded as the BOOT-3 trap) |
| Runtime `NEXT_PUBLIC_*` | frozen at build; runtime needs a server API | Next.js since 9.4, restated in v16 docs | PIT-3 |

---

## Validation Architecture

### Test framework

| Property | Value |
|----------|-------|
| Static k8s gates | bash under `k8s/scripts/`, run by CI job `k8s-validate` (`.github/workflows/ci-cd.yaml:191-211`) |
| Config file | none — scripts resolve paths from `$BASH_SOURCE`; exit codes 0/1/2 |
| Java unit/integration | JUnit 5 + Testcontainers, `./gradlew :core-java:test` / `:integrationTest` |
| Frontend E2E | Playwright 1.59.1, `frontend/playwright.config.ts` (baseURL from `PLAYWRIGHT_BASE_URL`) |
| Quick run command | `bash k8s/scripts/check-no-plaintext-secrets.sh && bash k8s/scripts/check-connection-math.sh && bash k8s/scripts/check-env-contract.sh` (~2s, no cluster) |
| Full suite command | the three gates + `kubectl --context jtoye apply -k k8s/local --dry-run=server` + the live rehearsal checklist below |

**Definition of "validated" for an infra phase:** every claim is either (a) a deterministic assertion
over committed text that CI re-runs on every PR, or (b) a live observation captured as **named,
falsifiable evidence** (a command, its expected output, and the actual output pasted into the phase
evidence). Nothing may be marked complete on "it worked once" without the recorded command+output.

### Phase requirements → test map

| Req | Behaviour | Type | Automated command | Exists? |
|-----|-----------|------|-------------------|---------|
| INFRA-01 | `k8s/local` builds | static, deterministic | `kubectl kustomize k8s/local >/dev/null` | ✅ (auto-covered by `check-no-plaintext-secrets.sh`, which discovers `k8s/local` at `maxdepth 2`) |
| INFRA-01 | no `kind: Secret`, no `REPLACE_WITH` in the local build | static | `bash k8s/scripts/check-no-plaintext-secrets.sh` | ✅ exists, green now (base/staging/production 22/23/23 resources) |
| INFRA-01 | overlay shims all four endpoints to `host.minikube.internal` | static | `kubectl kustomize k8s/local \| grep -c 'host.minikube.internal'` ≥ 4 | ❌ Wave 0 (one-line assertion in `check-env-contract.sh` or `k8s/LOCAL.md` smoke) |
| INFRA-01 | `minReplicas: 1` ×3, `minAvailable: 1` ×3, `replicas: 1` ×3, `maxReplicas` unchanged | static | `kubectl kustomize k8s/local \| grep -E '^\s*(replicas\|minReplicas\|minAvailable\|maxReplicas):'` compared against an expected fixture | ❌ Wave 0 |
| INFRA-01 | backup CronJob targets host MinIO | static | `kubectl kustomize k8s/local \| grep 's3.backup.endpoint: http://host.minikube.internal:9000'` | ❌ Wave 0 |
| INFRA-01 | every ref resolves; no dangling secret/configmap/label ref | **live** | `kubectl --context jtoye apply -f k8s/local/namespace.yaml && kubectl --context jtoye apply -k k8s/local --dry-run=server` | ❌ live step (PIT-8) |
| INFRA-02a | no hardcoded `5432` anywhere in `k8s/base` core-java env | static | `! grep -nE '^\s+value: "5432"' k8s/base/core-java-deployment.yaml` | ❌ Wave 0 (put it in `check-env-contract.sh`) |
| INFRA-02a | `DB_PORT` env has `valueFrom` and **no** `value` | static | assert the rendered EnvVar has exactly one of the two (guards PIT-2 forever) | ❌ Wave 0 |
| INFRA-02b | docs/template specify the NOSUPERUSER role | static | `! grep -n 'from-literal=username=jtoye$' k8s/QUICK_START.md` and same for `secrets-template.yaml.example` | ❌ Wave 0 |
| INFRA-02b | core boots as a non-superuser | **live** | `kubectl logs deploy/core-java \| grep "is NOT a superuser"` **and** the DB-side truth: `SELECT current_user, usesuper FROM pg_user WHERE usename=current_user` from inside the pod's connection identity | ❌ live step |
| INFRA-02c | CronJob run exits 0 and uploads | **live** | `kubectl create job --from=cronjob/pg-backup`; job `.status.succeeded == 1` | ❌ live step |
| INFRA-02c | the dump is **NON-EMPTY** (not just >1000 bytes) | **live, falsifiable** | download the object, `pg_restore` into a scratch DB, `SELECT count(*) FROM products` **> 0** (recipe: `docs/runbooks/backups.md:245-249`) | ❌ live step |
| INFRA-02d | STOMP creds reach Spring config | static | `check-env-contract.sh` direction (a) reports **0** injected-but-unread beyond the `SPRING_PROFILES_ACTIVE` allowlist | ❌ Wave 0 |
| INFRA-02d | no boot-time guest rejection | **live** | `kubectl logs deploy/core-java \| grep -c "Access refused for user"` == 0 | ❌ live step |
| INFRA-02d | a KDS client actually receives a relayed event | **live** | `RELAY_E2E=true PLAYWRIGHT_BASE_URL=http://app.jtoye.local npx playwright test e2e/stomp-relay.spec.ts` (after fixing PIT-9's cookie domain) | ⚠ spec exists, needs domain parameterisation |
| DEF-5 | a real vendor login through the ingress lands on a dashboard | **live** | `PLAYWRIGHT_BASE_URL=http://app.jtoye.local npx playwright test e2e/dashboard-mobile.spec.ts` (creds: `.env` `KC_SEED_USER_PASSWORD`, user `admin-user`) | ⚠ spec exists (13/13 in Phase 23), needs the local-built frontend image (PIT-3) |
| DEF-6 | the DEF-6 class cannot recur silently | static | `check-env-contract.sh` direction (b): every bucket-C name is either supplied by a manifest or in the allowlist **with a reason string** | ❌ Wave 0 |
| Regression | connection math still holds | static | `bash k8s/scripts/check-connection-math.sh` | ✅ exists, **re-run green this session** (133 ≤ 157) |
| Regression | staging + production builds unchanged | static | golden-file diff: `kubectl kustomize k8s/staging` / `k8s/production` before vs after the base edits | ❌ Wave 0 — **the Incremental Betterment proof.** Base edits (D-05 rename, D-13/D-15 additions) MUST show only intended additions, never behavioural changes |

### Sampling rate

- **Per task commit:** the three bash gates (~2s, no cluster). Any base-manifest edit additionally
  re-runs the staging/production golden diff.
- **Per wave merge:** all static gates + `kubectl kustomize k8s/local` + `--dry-run=server` if a
  cluster is up + `./gradlew :core-java:test` if `application.yml` changed (it will, for D-05).
- **Phase gate:** full static suite green, plus the live rehearsal evidence block (every command in
  the "live" rows above with its actual output) recorded before `/gsd:verify-work`.

### Making the live proofs reproducible, not anecdotal

1. **One idempotent entry point** — `scripts/k8s-local-up.sh` (D-14). If the rehearsal cannot be
   re-run from a stopped cluster by one command, it is not reproducible.
2. **Pin the evidence to code identity** — record the four image digests
   (`docker image inspect --format '{{index .RepoDigests 0}}{{.Id}}'`) alongside the results, so PIT-4
   can never make a stale-image pass look like a green one.
3. **Assert negatives with counts, not eyeballs** — `grep -c "Access refused for user"` must be `0`;
   `grep -c "is NOT a superuser"` must be `≥1`. A missing log line and an absent grep hit look
   identical otherwise.
4. **Falsify the backup, don't confirm it** — deliberately run one dump as `jtoye_app` and show it
   restores to `products=0`, then the `jtoye_backup` dump restoring to `products>0`. That is the only
   assertion that distinguishes "the backup works" from "the size floor passed".
5. **Prove the ingress path, not localhost** — every live URL in the evidence must be
   `http://api.jtoye.local` / `http://app.jtoye.local`. A `localhost:9090` in the evidence means the
   compose app containers were up and the XOR guard was bypassed.
6. **Capture the dry-run output verbatim** — a server dry-run that silently skipped the ingress
   admission webhook (see OQ-2) and a dry-run that passed look the same in an exit code.

### Wave 0 gaps

- [ ] `k8s/scripts/check-env-contract.sh` — covers INFRA-02a/d + DEF-6 recurrence (D-07/D-08)
- [ ] Rendered-manifest assertions for the local overlay (endpoint shim count, scale triple, backup
      endpoint, `DB_PORT` exactly-one-of `value`/`valueFrom`) — fold into the same script or a small
      sibling
- [ ] Staging/production golden-file diff harness (can be as simple as committing
      `k8s/**/.golden.yaml`, or a CI step diffing against `git stash`-ed HEAD renders)
- [ ] `frontend/e2e/*` cookie-domain parameterisation (PIT-9) so the existing specs run against
      `app.jtoye.local`
- [ ] `k8s/LOCAL.md` "rehearsal evidence" template so the live rows have a fixed home

---

## Security Domain

`security_enforcement` is not disabled in `.planning/config.json`, so this section is required.

### Applicable ASVS categories

| ASVS category | Applies | Standard control in this phase |
|---------------|---------|-------------------------------|
| V2 Authentication | **yes** | DEF-5: the split-horizon issuer must be *correct*, not permissive. `SecurityConfig.java:52-108` validates `iss` against `jtoye.security.jwt.expected-issuer` and `aud` via `AudienceValidator` (which throws at construction on a blank value, so enforcement cannot silently no-op). Local must supply real values, never widen the validator. |
| V3 Session Management | yes | NextAuth cookies over the ingress; `trustHost: true` is already set (`auth.ts:110`) and `NEXTAUTH_URL` must equal the ingress origin or callbacks break. Do **not** relax `sameSite`/`secure` to make the local HTTP run work — prefer HTTP-only local hosts. |
| V4 Access Control | yes (indirect) | **DEF-2 is an access-control control, not a config nit**: a superuser DB connection bypasses every RLS policy. `DatabaseConfigurationValidator.validateNotSuperuser()` is the enforcement point and it fails fast. The BYPASSRLS `jtoye_backup` role is the deliberate scoped exception — read-only, `GRANT SELECT` only, no DDL (`create-backup-role.sql`). |
| V5 Input Validation | n/a | no new request-handling code |
| V6 Cryptography | yes | `NOTIFICATION_UNSUBSCRIBE_SECRET` defaults to **empty** (bucket D) — an HMAC over an empty key. Flag it in the allowlist with a reason, or supply it. Never hand-roll. |
| V7 Error/Logging | yes | prod profile already suppresses error details (`application-prod.yml:60-64`). The new bootstrap scripts must follow `verify-env.sh`'s rule: **variable NAMES only, never values**. |
| V9 Communications | **partially degraded locally, by design** | The local overlay removes TLS (`tls: null`, `ssl-redirect: false`) and the security-header snippet (PIT-1). `k8s/LOCAL.md` must state plainly that local does **not** prove HSTS/X-Frame-Options/TLS. |
| V10 Malicious Code | yes | no new packages (see §Package Legitimacy Audit) |
| V12/V13 Files & API | n/a | no new endpoints |
| V14 Configuration | **primary** | This whole phase is V14. Secrets never in kustomize (#100, CI-enforced); `.env` gitignored; `.env.example` documents names only; config injection everywhere. |

### Known threat patterns for this stack

| Pattern | STRIDE | Standard mitigation | Status this phase |
|---------|--------|---------------------|-------------------|
| App connects as DB superuser ⇒ RLS bypass ⇒ cross-tenant read | Elevation of Privilege / Information Disclosure | NOSUPERUSER app role + fail-fast validator | **Fixed** (DEF-2); prove with the live boot log **and** the DB-side `usesuper` check |
| Plaintext Secret committed as a kustomize resource | Information Disclosure | `check-no-plaintext-secrets.sh` on every overlay | Held — the new overlay is auto-discovered; `secretGenerator` is forbidden |
| Secret values leaking into a committed script or doc | Information Disclosure | `.env` sourced at runtime; names-only reporting; `.gitleaks.toml` allowlists `.planning/**-RESEARCH.md` but **not** arbitrary scripts | Ensure `k8s-local-secrets.sh` never echoes a value; watch GitGuardian (not a required check, false-positives on password-shaped prose) |
| Wrong/absent `iss` validation ⇒ token accepted from the wrong issuer, or total auth outage | Spoofing | `expected-issuer` decoupled from the JWKS host (#87); `JwtIssuerDecouplingTest` regression test | DEF-5 must set both correctly; extend `JwtIssuerDecouplingTest` if a k8s-side assertion is wanted |
| Snippet annotations enabling arbitrary nginx config | Tampering / Elevation | ingress-nginx disables them by default since 1.9 | **Do not** enable `allow-snippet-annotations` on the local cluster just to make the apply pass; null the annotation instead (PIT-1) |
| Backup dump silently empty ⇒ false sense of recoverability | Denial of Service (recovery) | BYPASSRLS dump role + row-count restore drill | The size floor alone is insufficient — see §Validation Architecture item 4 |
| Egress to RFC1918 from app pods (SSRF pivot) | Information Disclosure | `ipBlock 0.0.0.0/0` with RFC1918 in `except[]` | Unchanged; note that this is exactly what would block `host.minikube.internal` under an enforcing CNI (PIT-7) |
| Applying manifests to the wrong cluster (employer AKS) | Tampering | explicit `--context jtoye` + a refuse-if-not-jtoye guard in both scripts | **Must be built** — current-context is unset and `sipbihs2aks` is the only context |

---

## Assumptions Log

| # | Claim | Section | Risk if wrong |
|---|-------|---------|---------------|
| A1 | The stopped minikube `jtoye` profile retains etcd state, so namespaces/secrets from the 2026-07-14 rehearsal may reappear on start | Runtime State Inventory | Stale imperative Secrets could mask a genuinely missing secret and make the rehearsal falsely green. Mitigation: inventory `get ns,secrets -A` on first start, use a fresh `jtoye-local` namespace. |
| A2 | `kubectl apply --dry-run=server` invokes the ingress-nginx validating webhook (`sideEffects: None`), so PIT-1 surfaces at dry-run rather than only at apply | PIT-1, Validation | If webhooks are skipped, the dry-run passes and the real apply fails. Cost is a surprise, not a wrong design — the overlay should null the snippet regardless. See OQ-2. |
| A3 | ingress-nginx v1.12.2's `annotations-risk-level: High` default rejects `configuration-snippet` even when it is the only snippet used | PIT-1 | If the annotation is actually accepted, nulling it is merely a cosmetic loss of local security headers. Low cost. |
| A4 | Auth.js v5 (`next-auth@5.0.0-beta.32`) still performs OIDC **discovery** against `KEYCLOAK_ISSUER` even though `authorization`/`token`/`userinfo` are explicitly overridden in `frontend/auth.ts:55-60` | OQ-1 | If it does, the frontend pod cannot reach `localhost:8085` and login breaks — compose only works because of `extra_hosts: localhost:host-gateway` (`docker-compose.full-stack.yml:340`), which k8s has no equivalent for. This is the single biggest live-path risk. |
| A5 | Keycloak with `KC_HOSTNAME=localhost` + `KC_HOSTNAME_PORT=8085` + `KC_HOSTNAME_STRICT=false` stamps `iss: http://localhost:8085/realms/jtoye-dev` regardless of the request Host header | OQ-1 | Determines whether `JWT_EXPECTED_ISSUER` should be `localhost:8085` or a `.local` host. Verify with one unauthenticated `curl` of the discovery document via two different Host headers. |
| A6 | aws-cli v1 inside `jtoye-pg-backup:15` uses path-style addressing against a custom `--endpoint-url`, so MinIO works without extra config | Backup Rehearsal | Strongly supported by `docs/runbooks/backups.md:228-237`'s recorded successful local run, but that run was outside the cluster. Failure mode is a clear DNS/404 error, easy to spot. |
| A7 | Logback's file-appender failure under prod profile is non-fatal (app boots, appender disabled) | PIT-5 | If it were fatal, core-java would crash-loop locally. The 2026-07-14 11/11-READY prod-profile result argues strongly against fatality. |
| A8 | `NEXT_PUBLIC_*` inlining also applies to the **server** bundle in a Next.js `standalone` build, making the k8s env injection inert server-side too | PIT-3 | Next.js docs say the replacement happens "in the Node.js environment", which supports this. If server-side reads *were* runtime, the impact narrows to the browser bundle only — the fix (build-time arg) is the same either way. |
| A9 | On Linux + docker driver, `/etc/hosts` → `minikube ip` reaches the ingress directly without `minikube tunnel` | Environment Availability | The minikube docs' tunnel note is explicitly scoped to Docker **Desktop**. If wrong, add `minikube tunnel` (needs sudo) to the runbook. |

---

## Open Questions

1. **How does the frontend pod perform OIDC discovery when the public issuer is not pod-reachable?** *(highest risk)*
   - **What we know:** `frontend/auth.ts` deliberately splits the browser-facing authorization URL
     (`kcPublicBase`) from the server-side `token`/`userinfo` endpoints (`kcServerBase =
     KEYCLOAK_ISSUER_INTERNAL || KEYCLOAK_ISSUER`), and `refreshAccessToken` uses the internal base
     with an explicit comment that the public URL "is not reachable from here and hangs ~10s"
     (`auth.ts:9-14`). But the provider's `issuer` option is still the **public** value
     (`auth.ts:55`), and Auth.js's Keycloak provider derives `wellKnown` from `issuer`. Compose masks
     this with `extra_hosts: - "localhost:host-gateway"` on the frontend service — a hack with no
     Kubernetes analogue (`hostAliases` cannot override `localhost`).
   - **What's unclear:** whether discovery is actually fetched when all three endpoints are
     overridden, and if it is, whether oauth4webapi strictly compares the discovered `issuer` with the
     configured one.
   - **Recommendation:** make this a **Wave 0 spike with a cheap decisive probe** — start the cluster,
     apply only the frontend Deployment with `KEYCLOAK_ISSUER=http://localhost:8085/...` +
     `KEYCLOAK_ISSUER_INTERNAL=http://host.minikube.internal:8085/...`, hit `/api/auth/signin`, and
     read the pod log. If discovery is attempted, the fix is a one-line additive change in `auth.ts`
     (`wellKnown: \`${kcServerBase}/.well-known/openid-configuration\``) — the exact same
     split-horizon pattern already applied to `token`/`userinfo`, and additive so compose/prod are
     unchanged. **Plan for this contingency explicitly; do not discover it during the rehearsal.**

2. **Does `--dry-run=server` run the ingress-nginx admission webhook?**
   - **What we know:** dry-run requests traverse admission; webhooks declaring `sideEffects: None`
     are the ones considered dry-run-safe. The authoritative Kubernetes "Side effects" section was
     truncated on fetch, so I could not quote it.
   - **Recommendation:** treat the dry-run as necessary-but-not-sufficient. Null the snippet
     annotation unconditionally, and keep a real apply in the acceptance criteria (D-16 already does).

3. **`.env` key naming for the backup password.**
   - `k8s/QUICK_START.md:32` already uses the shell name `POSTGRES_BACKUP_PASSWORD`; CONTEXT.md
     suggests `DB_BACKUP_PASSWORD`. Pick one and make `.env.example`, `QUICK_START.md`,
     `k8s/LOCAL.md` and the script agree. Recommend `DB_BACKUP_PASSWORD` (consistent with the
     existing `DB_USER`/`DB_PASSWORD` app-role pair, and distinct from the `POSTGRES_*` superuser
     pair — the very distinction DEF-2 is about).

4. **Should the four unflagged bucket-C prod defects be folded into D-15?**
   - `NOTIFICATION_EMAIL_TRACKING_BASE_URL`, `NOTIFICATION_UNSUBSCRIBE_BASE_URL`,
     `STRIPE_CONNECT_RETURN_URL`, `STRIPE_CONNECT_REFRESH_URL` are the same class D-15 exists to
     close, are live prod defects, and cost ~4 configmap keys + 4 env entries. Recommend **yes**, with
     the scope addition recorded. The alternative is allowlisting known prod defects as "accepted",
     which contradicts D-08's "reviewed inventory" intent.

5. **What does `ci-cd.yaml` pass as the frontend `NEXT_PUBLIC_API_URL` build arg?**
   - PIT-3's corollary means staging/prod frontends are serving a **baked** API URL while the
     ConfigMap injection is inert. I did not trace the CI build step. Worth a 5-minute check during
     planning: if CI bakes nothing, the staging/prod frontend has an empty API base and the dead env
     has been hiding it.

6. **Are the 6 NetworkPolicies worth rendering at all locally, given PIT-6?**
   - D-11 says render-not-enforce, which is right. But rendering a DNS rule that is *provably broken*
     under enforcement is a "validated" manifest that would fail in production. Recommend `k8s/LOCAL.md`
     states the PIT-6 finding explicitly and the deferred list gains "fix `includeSelectors` vs
     NetworkPolicy podSelectors" as a prerequisite for the Calico follow-up.

---

## Sources

### Primary (HIGH confidence — verified by tool this session)
- `kubectl kustomize` v5.6.0 builds of `k8s/base`, `k8s/staging`, `k8s/production`, and a scratchpad
  candidate `k8s/local` — proved P-1..P-6, PIT-2, PIT-6
- `bash k8s/scripts/check-no-plaintext-secrets.sh` → OK, 22/23/23 resources, exit 0
- `bash k8s/scripts/check-connection-math.sh` → PASS, 133 ≤ 157, exit 0
- `kubectl version --client`, `minikube version`, `minikube profile list`, `kubectl config get-contexts`,
  `docker version`, `docker compose -f docker-compose.full-stack.yml ps`, `docker images`
- Live read-only Postgres query (`pg_roles`, `flyway_schema_history`) and MinIO `/data` listing
- `strings /usr/local/bin/minikube` → `ingress-nginx/controller:v1.12.2`, `kube-webhook-certgen:v1.5.3`
- Repo files read in full: `k8s/base/{core-java,frontend,edge-go}-deployment.yaml`,
  `k8s/base/{configmap,kustomization,ingress,sse-ingress,pg-backup-cronjob}.yaml`,
  `k8s/base/networkpolicies/{00-default-deny,20-core-java}.yaml`,
  `k8s/staging/{kustomization,configmap-patch,namespace}.yaml`,
  `k8s/scripts/{check-no-plaintext-secrets,check-connection-math}.sh`,
  `k8s/scripts/validate-networkpolicies.py` (header),
  `core-java/src/main/resources/{application,application-prod}.yml`,
  `core-java/src/main/java/uk/jtoye/core/config/DatabaseConfigurationValidator.java`,
  `core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java` (45-108),
  `infra/backups/{k8s-backup.sh,create-backup-role.sql,Dockerfile}`,
  `frontend/{auth.ts,Dockerfile,playwright.config.ts,lib/env-validation.ts}`,
  `scripts/{deploy.sh,build-images.sh,verify-env.sh}`, `docker-compose.full-stack.yml` (service blocks),
  `.github/workflows/ci-cd.yaml` (170-230, 500-620), `docs/runbooks/backups.md`, `docs/metrics.json`,
  `scripts/docs-freshness.sh`, `.gitleaks.toml`

### Primary (HIGH confidence — official documentation)
- kustomize `replicas` supported kinds — https://kubectl.docs.kubernetes.io/references/kustomize/kustomization/replicas/
- ingress-nginx ConfigMap defaults (`allow-snippet-annotations: "false"`, `annotations-risk-level: High`) — https://kubernetes.github.io/ingress-nginx/user-guide/nginx-configuration/configmap/
- Next.js `NEXT_PUBLIC_*` build-time inlining + "frozen with the value evaluated at build time" — https://nextjs.org/docs/app/guides/environment-variables (docs version 16.2.11)
- minikube host access / `host.minikube.internal` + the 0.0.0.0-binding prerequisite — https://minikube.sigs.k8s.io/docs/handbook/host-access/

### Secondary (MEDIUM confidence)
- ingress-nginx 1.9 snippet-annotation breaking change + admission-denial error text — corroborated by
  multiple community write-ups and kubernetes/ingress-nginx issues #10543, #12648, #13186; consistent
  with the official ConfigMap defaults above
- minikube Ingress access on Linux/docker driver without `minikube tunnel` — the official quick-start
  scopes its tunnel note to Docker **Desktop**; not an explicit positive statement for Linux
- Kubernetes dry-run + admission webhook `sideEffects: None` behaviour — the authoritative section was
  truncated on fetch; see A2/OQ-2

### Tertiary (LOW confidence — flagged for validation)
- Auth.js v5 discovery behaviour when all endpoints are overridden (A4/OQ-1) — inferred from
  `frontend/auth.ts` + provider re-export only; the installed `@auth/core` provider source was not
  read. **Must be resolved by the Wave 0 spike.**

---

## Metadata

**Confidence breakdown:**
- Kustomize patch mechanics: **HIGH** — every pattern was built and rendered this session with the
  exact kubectl/kustomize versions CI uses
- Existing CI gate interaction: **HIGH** — both gates re-run green; parsers read
- Env-contract inventory: **HIGH** — computed mechanically from the real files, 116/23/19/13/12/72
  fully enumerated
- Backup mechanics: **HIGH** for the env contract and the missing bucket/role; **MEDIUM** for
  in-cluster aws-cli↔MinIO (proven outside a cluster only)
- minikube / ingress specifics: **MEDIUM-HIGH** — versions verified from the binary; behaviour from
  official docs plus one Linux-vs-Desktop inference
- The live auth path (DEF-5, frontend half): **MEDIUM** — one genuinely unresolved unknown (OQ-1) with
  a cheap, named probe and a known one-line contingency fix
- Pitfall completeness: **MEDIUM-HIGH** — 13 pitfalls, 8 of which CONTEXT.md does not name; the
  unknown-unknown risk is concentrated in the live rehearsal, which is why plan 3 should be its own
  wave with the OQ-1 spike ahead of it

**Research date:** 2026-07-25
**Valid until:** ~2026-08-24 for the repo-internal findings (they are file-derived and this branch is
the only writer). Shorter — **7 days** — for the host-environment facts (image freshness, compose
state, minikube profile state, DB roles, MinIO buckets): re-probe before the live rehearsal, because
the memory record `env_concurrent_working_tree` documents that a second session can drive this
checkout and its stack.
