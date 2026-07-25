# Phase 26: Local-K8s Overlay + Verified Breakage Fixes - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-07-25
**Phase:** 26-local-k8s-overlay-verified-breakage-fixes
**Areas discussed:** Local secret bootstrap, STOMP credential fix direction, Local scale (HPA + PDB treatment), Local access + endpoint URLs, Base-manifest drift (raised mid-discussion)

---

## Area selection

All four proposed gray areas were selected for discussion. A fifth (base-manifest config drift) was
surfaced by Claude mid-discussion after checking whether the overlay could genuinely "ship all v2.3
services", and was discussed as a boundary question.

---

## Local secret bootstrap

### Q1 — How should k8s/local get its 6 secrets, given the CI guard bans any `kind: Secret` in a kustomize build?

| Option | Description | Selected |
|--------|-------------|----------|
| Committed script sourcing .env | `scripts/k8s-local-secrets.sh` reads gitignored `.env`, idempotent `kubectl create secret --dry-run=client -o yaml \| kubectl apply`. One source of truth, guard stays green. | ✓ |
| Doc-only recipe in k8s/LOCAL.md | QUICK_START-style copy-paste block. No new scripts, but it is the imperative pattern this phase replaces. | |
| Script + committed .env.k8s-local.example | Script plus its own example env file. Explicit contract, but duplicates creds and adds a second file to sync. | |

**User's choice:** Committed script sourcing `.env` (recommended)
**Notes:** `secretGenerator` was ruled out before asking — `check-no-plaintext-secrets.sh` auto-discovers
overlays at `maxdepth 2` and fails on any `kind: Secret` in the build output.

### Q2 — The local pg-backup needs the BYPASSRLS jtoye_backup role, which nothing provisions locally. How far should the local flow go?

| Option | Description | Selected |
|--------|-------------|----------|
| Bootstrap the role + new .env key | Script runs `infra/backups/create-backup-role.sql` with a new `DB_BACKUP_PASSWORD`, then feeds backup creds into `postgres-credentials`. Makes the #101 rehearsal prove a non-empty dump. | ✓ |
| Point local backup at the superuser | Use `POSTGRES_USER`/`POSTGRES_PASSWORD` for the backup keys. Zero provisioning, but rehearses a credential shape production must never use. | |
| Document the manual step | `k8s/LOCAL.md` tells the operator to run the SQL once by hand. Least code; fails confusingly if skipped. | |

**User's choice:** Bootstrap the role + new `.env` key (recommended)
**Notes:** Discovered during scouting that `jtoye_backup` is created neither by compose nor Flyway — it is
a superuser bootstrap step. Without it, a local `pg_dump` captures zero rows from every FORCE-RLS table.

### Q3 — Where should host.minikube.internal + host-published ports live?

| Option | Description | Selected |
|--------|-------------|----------|
| New .env keys, script reads them | `K8S_LOCAL_*` keys alongside `DB_BACKUP_PASSWORD`. No port literal in any script; one-file fix on a compose port shift. | ✓ |
| Committed local configmap patch + script defaults | Non-secret endpoints in `k8s/local/configmap-patch.yaml`; script carries overridable defaults. Fewer keys, but literals in a committed script. | |
| Parse docker-compose.full-stack.yml | Derive published ports from compose so they cannot drift. Self-consistent, but adds YAML parsing and couples to compose structure. | |

**User's choice:** New `.env` keys, script reads them (recommended)
**Notes:** Follows the project's config-injection doctrine (GLOBAL_RULE_6 / ARCHITECTURE_RULE_8) — the
same doctrine that makes the hardcoded `DB_PORT` a defect rather than a style point.

### Q4 — Should the local bootstrap script enforce the compose-XOR-k8s rule?

| Option | Description | Selected |
|--------|-------------|----------|
| Hard fail if compose app containers are up | Refuse while core-java/frontend/edge-go run; require backing services up. Footgun becomes a guard rail. | ✓ |
| Warn but continue | Loud warning, operator decides. Keeps deliberate overlap possible. | |
| No check — document it only | Rule stays in `k8s/LOCAL.md` and CLAUDE.md as today. | |

**User's choice:** Hard fail (recommended)
**Notes:** The shared dev Postgres having two writers is the specific hazard; the guard distinguishes
compose *app* containers (must be down) from compose *backing services* (must be up).

---

## STOMP credential fix direction

### Q1 — Which side of the STOMP name mismatch should change?

| Option | Description | Selected |
|--------|-------------|----------|
| Wire Spring to the dedicated STOMP vars | `${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}}` etc., plus rename k8s `RABBITMQ_USERNAME` → `RABBITMQ_USER`. Honours the separate stomp credential already provisioned; compose unchanged. | ✓ |
| Rename k8s only, delete the dead vars | One credential for AMQP + STOMP; simplest manifest, but discards a distinct broker user and changes the documented secret shape. | |
| Keep both names, add an alias in Spring | Accept either name everywhere. Nothing else changes, but preserves two names for one thing — how the drift happened. | |

**User's choice:** Wire Spring to the dedicated STOMP vars (recommended)
**Notes:** Confirmed two-sided: `STOMP_CLIENT_LOGIN`/`_PASSCODE` are injected at
`core-java-deployment.yaml:170,175` but read by no `application*.yml`, while `application.yml:227-230`
reads `${RABBITMQ_USER}` and k8s injects `RABBITMQ_USERNAME` (line 140). Hence the boot-time
`Access refused for user 'guest'` — and `spring.rabbitmq.username` silently defaulting too.

### Q2 — How should recurrence be prevented?

| Option | Description | Selected |
|--------|-------------|----------|
| CI test: every k8s env name must be read by Spring | Static gate in the style of `check-connection-math.sh` / `RlsContractTest`, both directions. Would have caught both sides. | ✓ |
| Drop the guest default so it fails at boot | Hard startup failure when unset. Loudest, but breaks a bare local run. | |
| Startup assertion in prod/staging only | Validator-style refusal when a credential resolves to `guest`. Keeps dev convenience, fires only on a deployed cluster. | |

**User's choice:** CI env-contract gate (recommended)

### Q3 — How wide should the env-contract gate be?

| Option | Description | Selected |
|--------|-------------|----------|
| core-java both directions | Injected-but-unread AND expected-but-unsupplied, with a documented allowlist. Covers the service where the drift happened. | ✓ |
| All three services, k8s → code only | Also edge-go and frontend, one direction. Broader, needs three parsers. | |
| core-java, k8s → yml only | Smallest; would have missed the `RABBITMQ_USERNAME` side. | |

**User's choice:** core-java, both directions (recommended)

### Q4 — How should the STOMP fix be proven, given dev compose defaults to in-memory?

| Option | Description | Selected |
|--------|-------------|----------|
| Prove on the local k8s cluster | Local overlay runs the relay against host RabbitMQ:61613; assert no `Access refused` in the pod log + a working KDS WebSocket subscribe. | ✓ |
| Add a relay-mode compose profile | Opt-in compose override flipping `STOMP_BROKER_MODE=relay`. Reusable, but new compose surface in an infra phase. | |
| Property-resolution unit test only | Spring test on the placeholder chain. Fast, never touches a broker handshake. | |

**User's choice:** Prove on the local k8s cluster (recommended)
**Notes:** Relay-mode compose override recorded as a deferred idea.

---

## Local scale: HPA + PDB treatment

### Q1 — How should the local overlay handle replicas, HPAs and PDBs?

| Option | Description | Selected |
|--------|-------------|----------|
| Patch all three to 1 | `replicas: 1`, HPA `minReplicas: 1`, PDB `minAvailable: 1`. Every object still renders and validates; a 1-replica pod stays drainable. | ✓ |
| replicas 1 + delete HPAs and PDBs | `$patch: delete` — no inert objects, but local stops exercising two manifest types. | |
| replicas 1 + HPA minReplicas 1 only | Roadmap wording exactly; PDB of 2 over 1 replica hangs `kubectl drain`. | |

**User's choice:** Patch all three to 1 (recommended)
**Notes:** HPA `maxReplicas` deliberately untouched — it is an input to `check-connection-math.sh`.

### Q2 — Base pins SPRING_PROFILES_ACTIVE=prod. What should local run?

| Option | Description | Selected |
|--------|-------------|----------|
| Keep prod | Exercises the prod config path (internal 9091 management port, no SQL logging, prod pools); already booted 11/11 READY that way. | ✓ |
| Switch to staging | Chattier logs, matches the rehearsal namespace, but leaves prod-only config unrehearsed. | |
| Add a local profile | New `application-local.yml`. Most control, a fourth profile to maintain. | |

**User's choice:** Keep prod (recommended)

### Q3 — minikube's default CNI does not enforce NetworkPolicies. What should the local overlay do?

| Option | Description | Selected |
|--------|-------------|----------|
| Keep them, document as inert | All 6 render unchanged; `k8s/LOCAL.md` states local proves manifest validity, not enforcement. | ✓ |
| Enable Calico + prove enforcement locally | Only environment that would enforce them; needs egress for `host.minikube.internal`, exceeds roadmap criteria. | |
| Exclude netpols from the local build | No false confidence, but those manifests stop being validated. | |

**User's choice:** Keep them, document as inert (recommended)
**Notes:** Calico enforcement recorded as a deferred idea.

---

## Local access + endpoint URLs

### Q1 — How should the local cluster be reached?

| Option | Description | Selected |
|--------|-------------|----------|
| minikube ingress addon + hosts entries | Addon + `/etc/hosts` → `minikube ip`, overlay patches Ingress hosts. Exercises the real Ingress path; stable NextAuth callbacks. | ✓ |
| port-forward on localhost | No addon or hosts edits, but Ingress + SSE annotations unexercised and forwards must stay running. | |
| NodePort at $(minikube ip) | No addon or forwards, but URLs shift with cluster IP and Ingress stays unexercised. | |

**User's choice:** minikube ingress addon + hosts entries (recommended)

### Q2 — Where should the split-horizon issuer values be wired?

| Option | Description | Selected |
|--------|-------------|----------|
| Add to base via new configmap keys | Base gains `JWT_EXPECTED_ISSUER` + frontend `KEYCLOAK_ISSUER_INTERNAL` from new `app-config` keys defaulting to the public issuer (staging/prod byte-identical); local patches the split values. | ✓ |
| Local-overlay-only patch | Smallest diff, but base keeps a silent gap. | |
| Single URL for both sides | `/etc/hosts host.minikube.internal → 127.0.0.1` so one issuer serves both. No new env vars, but depends on Keycloak stamping that hostname and hides the gap. | |

**User's choice:** Add to base via new configmap keys (recommended)
**Notes:** Found during the discussion — these vars exist in compose for all three services
(`docker-compose.full-stack.yml:184,277,327`) and in **no** k8s manifest. Benign in prod where the
public issuer is pod-reachable; fatal locally. Same class as issue #87's total live-auth outage.

### Q3 — How far should the proof go?

| Option | Description | Selected |
|--------|-------------|----------|
| Live apply + auth E2E through the ingress | Build + server dry-run, then real apply: 3/3 READY, core `/health` + `/public/shops`, Playwright vendor login through the ingress. The login is the only thing proving the issuer fix. | ✓ |
| Live apply + HTTP smoke, no browser | What the 2026-07-14 rehearsal proved; a 200 on the login page does not prove tokens validate. | |
| Build + server dry-run only | Roadmap wording exactly; catches none of the five runtime defects. | |

**User's choice:** Live apply + auth E2E through the ingress (recommended)
**Notes:** Vendor credentials are available in `.env` (`KC_SEED_USER_PASSWORD`).

### Q4 — What drives the local bring-up?

| Option | Description | Selected |
|--------|-------------|----------|
| One scripts/k8s-local-up.sh | XOR guard → secrets + backup role → image load → `apply -k` → wait → smoke. Durable replacement for the imperative steps; deploy.sh stays staging/prod. | ✓ |
| Extend scripts/deploy.sh with a local target | One entry point, but local needs pre-steps staging/prod do not. | |
| Document the steps in k8s/LOCAL.md | Least code, but bring-up stays manual — the thing being replaced. | |

**User's choice:** One `scripts/k8s-local-up.sh` (recommended)
**Notes:** Surfaced that `scripts/deploy.sh:27` accepts a `dev` target with no `k8s/dev` overlay, applying
base files directly via `kubectl apply -f` and bypassing kustomize — fixed or removed in the same change.

---

## Base-manifest drift (raised mid-discussion)

Claude surfaced that `k8s/base` injects only 23 env vars into core-java, leaving media storage
(`S3_ENDPOINT` → `http://localhost:9000`), email (`SMTP_HOST` → `localhost`), Stripe, `CORS_ALLOWED_ORIGINS`,
`JWT_EXPECTED_AUDIENCE` and the webhook knobs on local-only defaults — in staging and production as well
as locally. Also: no `mcp-server` manifest exists in `k8s/` at all.

### Q1 — How much of that drift should Phase 26 close?

| Option | Description | Selected |
|--------|-------------|----------|
| Close it for core-java in base | Wire S3/MinIO, SMTP, Stripe, CORS, audience, webhook knobs into base + overlays. Local genuinely rehearses v2.3; fixes staging/prod too. ~1 extra plan. | ✓ |
| Only what the proof path exercises | Just S3/MinIO + issuer/audience; leave SMTP/Stripe/webhooks documented as unsupplied. | |
| Defer all of it to its own phase | Phase 26 stays the four verified items + overlay; drift becomes a backlog item. | |

**User's choice:** Close it for core-java in base (recommended)

### Q2 — Should the CI gate also catch a placeholder defaulting to a localhost value that no manifest overrides?

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, with an explicit allowlist | Flags `localhost`/`127.0.0.1`/`minioadmin`-style defaults never overridden; each accepted omission listed with a reason. | ✓ |
| No — keep the gate to name mismatches only | Simpler, less allowlist churn, but this defect class stays invisible. | |

**User's choice:** Yes, with an explicit allowlist (recommended)

---

## Claude's Discretion

- Exact new `.env` key names (`DB_BACKUP_PASSWORD`, `K8S_LOCAL_*`) and `.env.example` documentation shape.
- Local namespace name (`jtoye-local` suggested) and local image-tag strategy.
- Whether the env-contract gate is a bash script under `k8s/scripts/` or a JUnit test — must run in the
  `k8s-validate` CI job either way.
- Plan split across INFRA-01 / INFRA-02 / the drift work, and how the `pg-backup` image is loaded locally.
- Whether to remove the cosmetic hardcoded `namespace: jtoye-production` in `pg-backup-cronjob.yaml:5`.

## Deferred Ideas

- `mcp-server` k8s manifest (own phase — new deployment/service/ingress + scoped credentials).
- Calico CNI locally to actually enforce NetworkPolicies.
- Customer-storefront realm config in k8s (`CUSTOMER_KC_ISSUER_URI` and friends absent from every k8s env).
- Env-contract gate coverage for `edge-go` (Go `os.Getenv`) and `frontend` (`process.env`).
- Sealed-secrets / external-secrets for local (explicitly out of this milestone, PROJECT.md:141).
- Azure AKS deploy (personal-sub `jtoye-rg`, ~$60-175/mo).
- Relay-mode compose override for testing STOMP without a cluster.
