---
phase: 26-local-k8s-overlay-verified-breakage-fixes
plan: 05
subsystem: infra
tags: [kubernetes, minikube, kustomize, bash, docker-compose, secrets, postgres-rls, minio, playwright, ingress]

# Dependency graph
requires:
  - phase: 26-04
    provides: "the committed k8s/local overlay (23 rendered resources) whose render is the authoritative secret inventory, ingress hostnames, image tag and api.url source"
  - phase: 26-03
    provides: "the k8s/scripts/check-*.sh house style (SCRIPT_DIR/REPO_ROOT from $BASH_SOURCE, set -euo pipefail, 0/1/2 exit convention) mirrored by the new scripts"
  - phase: 26-02
    provides: "the app-config keys the local overlay patches, and the `optional: true` refs that make smtp-credentials a deliberate omission"
provides:
  - "scripts/lib/k8s-local-guards.sh — a source-only guard library: .env contract, kubectl-context assertion (4 named refuse arms), read-only compose-XOR guard, namespace resolver, --context-always kubectl wrapper"
  - "scripts/k8s-local-secrets.sh — idempotent out-of-band bootstrap of every Secret the local overlay consumes, the BYPASSRLS jtoye_backup role and the private backup bucket (AUTHORED, first EXECUTED in 26-07)"
  - "scripts/k8s-local-up.sh — the single idempotent bring-up entry point, 12 ordered steps (AUTHORED, first RUN in 26-07)"
  - "15 documented K8S_LOCAL_* / DB_BACKUP_PASSWORD / NOTIFICATION_UNSUBSCRIBE_SECRET keys in .env.example plus real values in the gitignored .env"
  - "scripts/deploy.sh: phantom `dev` target removed, raw base-file applies replaced with `apply -k`, rollback preserved"
  - "frontend/e2e/stomp-relay.spec.ts: cookie domain derived from the base URL, so the spec can target an ingress hostname"
affects: [26-06, 26-07, 26-08]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Source-only bash guard library: definitions + readonly constants, zero top-level tool invocation, so each guard is falsifiable in isolation without a cluster"
    - "Named refuse arms: every refusal prints REFUSED [arm-name], so a falsification probe records WHICH arm fired rather than only that something failed"
    - "Fail-closed expectation resolution: if the minikube profile node IP cannot be resolved by either mechanism, the context guard REFUSES rather than proceeding on an unresolvable expectation"
    - "Non-vacuity guard inside a guard: empty `docker compose ps` output exits 2 (VOID), never 0 (clean) — passing by finding nothing is not passing"
    - "Read-only PATH-shimmed fixture: prove a guard arm real state cannot show, without mutating anything, by shimming the tool in the scratch directory and exec'ing the real binary for every other invocation"
    - "Source-level order assertions: guard-call line numbers < first-mutating-call line numbers, so 'a refusal is a no-op' is provable without invoking the script"
    - "Literal-free scripts: every host/port from .env, namespace from the kustomization, ingress hostnames + api.url + image tag from the overlay render, browser build-arg fallbacks parsed out of the compose file"

key-files:
  created:
    - scripts/lib/k8s-local-guards.sh
    - scripts/k8s-local-secrets.sh
    - scripts/k8s-local-up.sh
  modified:
    - .env.example
    - scripts/deploy.sh
    - frontend/e2e/stomp-relay.spec.ts

key-decisions:
  - "The whole-script `bash scripts/k8s-local-secrets.sh` invocation was NOT run in this plan (26-REVIEWS.md Adjudication J) — it is relocated to 26-07 Task 2 behind the human checkpoint; the refusal is proven instead by the source-level guard order plus a function-level probe of the identical guard sequence, with the equivalence asserted"
  - "The context guard asserts the API-server HOST equals the minikube profile node IP, not merely that the context name matches — so a same-named context pointing elsewhere is still rejected; `kubectl config use-context` is never called anywhere in the tooling"
  - "The compose-XOR guard is READ-ONLY by construction: it never stops, starts or removes a container, because this checkout can be driven by a second concurrent session"
  - "The XOR guard's happy path was checked only SYNTHETICALLY (fixture) and is labelled as such — the authoritative D-04 proceed proof is 26-07's, against real state, after the human approves the compose app stop"
  - "scripts/verify-env.sh REQUIRED_VARS deliberately NOT extended: making a k8s-local-only key mandatory for every compose dev run would regress a working path (Incremental Betterment)"
  - "deploy.sh now rejects `dev` and `local` with DISTINCT messages — `dev` never had an overlay, `local` has one but needs the guarded entry point; a single shared message would have stated something false about k8s/local"
  - "The browser build-arg fallbacks in k8s-local-up.sh are PARSED OUT OF docker-compose.full-stack.yml rather than restated, so the script carries no default of its own and cannot drift from what compose bakes"
  - "The pg-backup image is loaded at whatever tag the CronJob pins and is never retagged; the other three carry the tag parsed from the overlay's `newTag`, cross-checked against the render"

patterns-established:
  - "Pattern: guard-order source assertion — record the line number of the last guard call and the first mutating call, so 'the refusal is a no-op' is a source fact rather than a runtime hope"
  - "Pattern: prove a check can fail before trusting it green — every grep-shaped criterion in this plan was run against a deliberately broken scratchpad COPY (never `git checkout --`) first"
  - "Pattern: nothing-mutated post-checks as a first-class deliverable — profile Stopped, one kubeconfig context, no elevated role, no bucket, all ten compose services in their prior state"

requirements-completed: []

# Metrics
duration: 42min
completed: 2026-07-25
---

# Phase 26 Plan 05: Local-k8s bring-up tooling (guards, bootstrap, entry point) Summary

**A source-only guard library plus two committed scripts that replace the 2026-07-14 rehearsal's hand-typed imperative sequence — with every guard's REFUSE path proven against real host state or a read-only fixture, and zero shared-state mutation, so the profile is still `Stopped`, the elevated DB role still does not exist and the developer's compose stack was never touched.**

## Performance

- **Duration:** ~42 min
- **Started:** 2026-07-25T19:28Z (approx)
- **Completed:** 2026-07-25T19:10:37Z (last task commit, local time 20:10:37+01:00)
- **Tasks:** 3 of 3
- **Files created:** 3 · **Files modified:** 3 · **Net:** +1196 / −35 lines

## Accomplishments

- **`scripts/lib/k8s-local-guards.sh`** — 5 guards, sourceable with **zero side effects** (proven: sourcing with `docker`/`kubectl`/`minikube` stubs that `exit 1` on PATH still exits **0**). Every refusal names its arm.
- **`scripts/k8s-local-secrets.sh`** — the sole creator of all 9 Secrets the overlay consumes, the BYPASSRLS `jtoye_backup` role (by **invoking** `infra/backups/create-backup-role.sql`, not restating it) and the **private** backup bucket. Authored here; **first executed in 26-07**.
- **`scripts/k8s-local-up.sh`** — 12 ordered steps from a stopped minikube to a smoked ingress, with the XOR guard structurally **before** the cluster start and the image identities in the evidence block so a stale-image pass cannot masquerade as green.
- **`scripts/deploy.sh`** — the phantom `dev` target is gone and the five raw `kubectl apply -f k8s/base/...` calls that **bypassed kustomize entirely** are replaced by one `apply -k`, matching what the CI deploy job already does.
- **`frontend/e2e/stomp-relay.spec.ts`** — cookie domain follows the base URL, so the relay spec can finally run against `app.jtoye.local`.
- **Every guard REFUSE path exercised for real**, with the actual output and exit code recorded below. **Nothing shared was mutated.**

## Task Commits

1. **Task 1: .env keys, shared guard library, secret/role/bucket bootstrap** — `6f94296` (feat)
2. **Task 2: k8s-local-up.sh entry point + deploy.sh phantom-dev fix** — `dd194ab` (feat)
3. **Task 3: parameterise the STOMP relay cookie domain (PIT-9)** — `537f45f` (test)

## Files Created/Modified

- `scripts/lib/k8s-local-guards.sh` (new, 330 lines) — `k8s_local_load_env`, `k8s_local_profile_ip`, `k8s_local_assert_context`, `k8s_local_compose_state`, `k8s_local_assert_compose_xor`, `k8s_local_namespace`, `k8s_local_kubectl` (+ 3 reporting helpers).
- `scripts/k8s-local-secrets.sh` (new, 325 lines, `+x`) — guards → value preflight → namespace → dump role (+ DB-side `rolbypassrls` verification) → private bucket → 9 Secrets → names-only summary.
- `scripts/k8s-local-up.sh` (new, 376 lines, `+x`) — flags → preflight → XOR → profile → ingress addon → in-cluster reachability → hosts check → 4 images → bootstrap → namespace/dry-run/apply → 3 rollouts → ingress smoke → evidence block.
- `.env.example` (+74) — documented `K8S_LOCAL_*` section (13 keys) + `DB_BACKUP_PASSWORD` + `NOTIFICATION_UNSUBSCRIBE_SECRET`, each naming its consumer and the compose line its default comes from.
- `scripts/deploy.sh` (+89/−35 net) — staging/production only; one `apply -k`; rollout-status + `rollout undo` rollback and the `SERVICE` selector preserved.
- `frontend/e2e/stomp-relay.spec.ts` (+37/−) — `COOKIE_DOMAIN = new URL(BASE).hostname` at both `addCookies` sites, `PLAYWRIGHT_BASE_URL` added **under** `FRONTEND_URL`, inert `DEBUG_E2E_TARGET` diagnostic, stub-cookie ≠ auth-proof comment.
- **`.env` (gitignored, the ONLY host-state write this plan performed)** — the same 15 keys with real values; the two secrets generated with `openssl rand -hex 32`. Values never printed. A timestamped backup was taken to the scratchpad first.

---

## ZERO SHARED-STATE MUTATION — stated explicitly

**Nothing this plan verified mutated shared state.** No `minikube start`. No compose container stopped, started or removed. No Postgres role created or altered. No MinIO bucket created. No Kubernetes Secret, ConfigMap or any other cluster object created. No `kubectl apply`. No `--dry-run=server`. The employer AKS context `sipbihs2aks` was **read** (to prove the guard rejects it) and never targeted. That is why `autonomous: true` is honest for this plan.

### Nothing-mutated post-checks (run after every probe)

| # | Check | Result |
|---|-------|--------|
| 1 | `pg_roles` count for `jtoye_backup` | **`0`** (role still absent) |
| 2 | `docker exec jtoye-minio ls /data` | **`jtoye-images`** only — no `jtoye-db-backups` |
| 3 | minikube profile `jtoye` status | **`Stopped`** |
| 4 | `kubectl config get-contexts -o name \| wc -l` | **`1`** |

Plus a fifth, added beyond the plan: **all ten compose services are in exactly the state they were in when the plan started** — `edge-go=running frontend=running keycloak=running mailhog=running mcp-server=running minio=running postgres=running rabbitmq=running redis=running core-java=running`.

---

## Guard falsifiability probes — actual output and exit codes

### Context guard — ARM A (`context-absent`), default `.env`

```
REFUSED [context-absent]: kubectl context 'jtoye' does not exist in kubeconfig — the minikube
profile 'jtoye' creates it on start, so start the profile first (scripts/k8s-local-up.sh does
this in order); the other kubectl context(s) on this host are EMPLOYER infrastructure and must
NEVER be targeted
ARM-A exit=1
```
**Arm fired:** `context-absent`. The `jtoye` context genuinely does not exist in kubeconfig while the profile is Stopped — `minikube start` creates it — which is exactly why this arm is falsifiable with zero mutation.

### Context guard — ARM B (`server-host-mismatch`), `K8S_LOCAL_KUBE_CONTEXT=sipbihs2aks`

```
REFUSED [server-host-mismatch]: context 'sipbihs2aks' points at API server host
'sipbihs2aks-dns-ar3i9nzs.hcp.uksouth.azmk8s.io', but minikube profile 'jtoye' is at
'192.168.49.2'; the other kubectl context(s) on this host are EMPLOYER infrastructure and must
NEVER be targeted
ARM-B exit=1
```
**Arm fired:** `server-host-mismatch` — the arm the plan predicted. The profile IP `192.168.49.2` came from the JSON fallback (`minikube ip -p jtoye` exits **83** while Stopped, verified). The message references employer infrastructure.

### Context guard — the two remaining arms (proven beyond the plan's criteria)

```
REFUSED [wrong-name]: caller asked for context 'sipbihs2aks' but the configured local context
is 'jtoye'; the other kubectl context(s) on this host are EMPLOYER infrastructure and must
NEVER be targeted                                                            exit=1

REFUSED [unresolvable-profile-ip]: could not resolve a node IP for minikube profile
'no-such-profile-xyz' (tried 'minikube ip -p' then the profile registry JSON); refusing rather
than proceeding on an unresolvable expectation; ...                          exit=1
```
All **four** named arms fire. The `unresolvable-profile-ip` arm is the fail-closed guarantee.

### XOR guard — APP arm, REAL current state, zero mutation

```
REFUSED [compose-apps-running]: compose APP service(s) still running: core-java frontend
edge-go mcp-server. The local cluster and compose would be TWO WRITERS on the same shared dev
Postgres. Bring the app containers down first (a human decision — this tooling never stops a
container, because a second session may own this stack). The backing services must STAY UP.
XOR-APP exit=1
```
**Arm fired:** `compose-apps-running`, naming all four running app services. **No compose container was stopped.**

### XOR guard — BACKING-SERVICES arm, read-only PATH-shimmed `docker` fixture

```
REFUSED [compose-backing-down]: compose BACKING service(s) not running: redis. The local
overlay shims every endpoint to the pod host 'host.minikube.internal', so the cluster CONSUMES
these — a pod would come up and fail to connect. Start the backing services (apps stay down).
XOR-BACKING exit=1
```
**Arm fired:** `compose-backing-down`, naming `redis`. The shim lived in the scratch directory, printed the fixture only for a `compose … ps` invocation and `exec`'d the real binary otherwise; it was deleted afterwards and `git status --porcelain` is **empty** (no shim leaked into the repo).

### XOR guard — SYNTHETIC happy-path logic check

```
OK: compose XOR k8s satisfied — all app services down, all backing services up
XOR-SYNTHETIC exit=0
```
**Labelled `SYNTHETIC`.** This is a **logic check on the guard only** — all four app services absent and all six backing services running, via a fixture. It is explicitly **NOT the D-04 proceed proof**, which plan 26-07 owns against real state after the human approves the compose app stop. Claiming it otherwise would be exactly the green-by-construction failure this project keeps catching late.

### XOR guard — non-vacuity (added beyond the plan)

```
TOOLING ERROR: compose reported no services at all for docker-compose.full-stack.yml — the
assertion would pass by finding nothing, which is VOID, not clean
XOR-EMPTY exit=2
```
An empty `docker compose ps` exits **2 (VOID)**, never 0. Without this, an unrelated compose failure would have made the XOR guard silently *pass*.

### Entry-point refusal — `bash scripts/k8s-local-up.sh --dry-run-only`

```
=== J'Toye local Kubernetes bring-up (dry-run-only=1, skip-build=0) ===

=== STEP 1: preflight ===
OK: env loaded from /home/sanmi/IdeaProjects/JToye_OaaS_2026/.env; all 13 K8S_LOCAL_* keys present
OK: docker, kubectl, minikube, jq present
OK: scripts/verify-env.sh passed

=== STEP 2: compose XOR k8s guard ===
REFUSED [compose-apps-running]: compose APP service(s) still running: core-java frontend
edge-go mcp-server. ...
ENTRYPOINT exit=1
```
Post-checks immediately after: profile **`Stopped`**, contexts **`1`**. **This refusal IS this task's live-ish proof** — the exit-0 dry-run, the rollouts, the smoke and the captured image identities are 26-07's.

### Unknown-flag handling

`bash scripts/k8s-local-up.sh --nope` → **exit 2** with a usage message. Proven to make **zero** external tool calls two ways: a logging `docker`/`kubectl`/`minikube` shim recorded **0 invocations**, and structurally the flag loop is at **L72** while the first tool invocation is at **L100**. Profile still `Stopped`.

### Function-level probe of the bootstrap's identical guard sequence

```
# with set -e (short-circuits at the FIRST refusing guard, exactly as the script does)
REFUSED [context-absent]: ...                                        SEQ-set-e exit=1

# the plan's literal command (no set -e; every guard runs, last exit wins)
OK: env loaded ...
REFUSED [context-absent]: ...
REFUSED [compose-apps-running]: ...                                  SEQ-literal exit=1
```
Both forms exit non-zero. Under `set -e` — which is what `scripts/k8s-local-secrets.sh` actually runs with — it aborts at the **first** refusing guard, `context-absent`.

---

## The bootstrap script was NOT invoked in this plan

**`bash scripts/k8s-local-secrets.sh` was never run — not as a whole script, and not even to watch a guard refuse.** Reason: `26-REVIEWS.md` **Adjudication J**. This plan is `autonomous: true`, and a whole-script run relies on the guard being *correct*; a bug would have created an RLS-bypassing role on the shared dev Postgres unattended. The four nothing-mutated post-checks would only **detect** that afterwards, and detection-after-the-fact is not prevention.

**What replaced it, both mutating nothing by construction:**

1. **Source-level guard order.** Guard calls at **L70** (`k8s_local_load_env`), **L71** (`k8s_local_assert_context`), **L72** (`k8s_local_assert_compose_xor`). First mutating call — `kubectl create secret … | k8s_local_kubectl apply` — at **L146**. `docker exec` psql at **L175**, `docker run … mc` at **L217**. **72 < 146**, so a refusal is provably a no-op. Helper functions are deliberately defined *after* the guard calls precisely so a helper body containing a mutating call cannot sit at a lower line number.
2. **Function-level probe of the identical sequence** (output above).
3. **Sequence equivalence asserted.** `grep -n 'k8s_local_load_env\|k8s_local_assert_context\|k8s_local_assert_compose_xor' scripts/k8s-local-secrets.sh` returns **exactly three matches, in order**:
   ```
   70:k8s_local_load_env
   71:k8s_local_assert_context
   72:k8s_local_assert_compose_xor
   ```
   — the same sequence, in the same order, the probe ran.

**Plan 26-07 Task 2 owns the first execution, behind its `checkpoint:human-action`.**

---

## Secret inventory — proven complete against the RENDERED overlay

Derived from `kubectl kustomize k8s/local` (client-side render, **no cluster contacted**), every `secretKeyRef`:

| Secret | Keys the render consumes | In the script? | Bound to |
|--------|--------------------------|----------------|----------|
| `postgres-credentials` | host, port, database, username, password, backup-username, backup-password | ✅ all 7 | `$K8S_LOCAL_POD_HOST`, `$K8S_LOCAL_DB_PORT`, `$POSTGRES_DB`, **`$DB_USER`**, `$DB_PASSWORD`, `jtoye_backup` (parsed from the SQL), `$DB_BACKUP_PASSWORD` |
| `redis-credentials` | password | ✅ | `$REDIS_PASSWORD` |
| `rabbitmq-credentials` | username, password, stomp-login, stomp-passcode | ✅ all 4 | `$RABBITMQ_USER`, `$RABBITMQ_PASSWORD`, `${STOMP_CLIENT_LOGIN:-$RABBITMQ_USER}`, `${STOMP_CLIENT_PASSCODE:-$RABBITMQ_PASSWORD}` |
| `keycloak-credentials` | admin-username, admin-password, frontend-client-secret | ✅ all 3 | `$KEYCLOAK_ADMIN`, `$KEYCLOAK_ADMIN_PASSWORD`, `$KEYCLOAK_CLIENT_SECRET` |
| `nextauth-secret` | secret | ✅ | `$NEXTAUTH_SECRET` |
| `s3-backup-credentials` | access-key, secret-key | ✅ | MinIO root pair |
| `s3-media-credentials` | access-key, secret-key (`optional: true`) | ✅ | MinIO root pair |
| `notification-credentials` | unsubscribe-signing-secret (`optional: true`) | ✅ conditional | skipped by name when `NOTIFICATION_UNSUBSCRIBE_SECRET` is empty |
| `stripe-credentials` | api-key, webhook-secret (`optional: true`) | ✅ conditional | skipped by name when `STRIPE_API_KEY` is empty (it is absent on this host) |
| `smtp-credentials` | username, password (`optional: true`) | **deliberately NOT created** | — |

**`smtp-credentials` omission rationale:** Mailhog accepts any sender with **no auth**, and plan 26-02 made that manifest ref `optional: true`. An empty username/password Secret would add nothing and would misrepresent the local topology. The script names it in an **explicit `skip_secret` line with that reason**, so the omission is visible in the script's own output rather than being a silent gap.

**Rendered `secretName` refs (TLS): 0** — the local overlay sets `tls: null`, so there is no cert Secret to create. Checked, not assumed.

**DEF-2 at source level:** `--from-literal=username=$DB_USER` appears **1** time for `postgres-credentials`; `--from-literal=username=$POSTGRES_USER` appears **0** times. The superuser is never injected as the app identity. (The live base64 decode is 26-07's.)

---

## Source-level assertions recorded

| Assertion | Result |
|---|---|
| `bash -n` on all four scripts | **0** (all parse) |
| `test -x scripts/k8s-local-secrets.sh`, `scripts/k8s-local-up.sh` | both **executable**; the guard library is deliberately **not** `+x` (source-only) |
| host/port literals, comment-filtered, in `k8s-local-guards.sh` / `k8s-local-secrets.sh` | **0 / 0** |
| host/port/`192.168.49`/`jtoye.local` literals, comment-filtered, in `k8s-local-up.sh` | **0** |
| `kubectl config use-context`, comment-filtered, both scripts | **0** |
| kubectl-call routing in `k8s-local-secrets.sh` | `grep -cE 'kubectl '` = **2** == `grep -cE 'k8s_local_kubectl\|kubectl [^\|]*--context'` = **2** |
| side-effect-free sourcing | `declare -F \| grep -c k8s_local_` = **10**; sourcing under exit-1 stubs exits **0** |
| guard order, secrets script | last guard **L72** < first mutation **L146** |
| guard-before-start, entry point | flags **L72** < XOR **L118** < `minikube start` **L130** |
| step order (D-14), entry point | secrets **L304** < `apply -k` **L318** < `rollout status` **L333** < smoke `curl` **L344** |
| `build-images.sh` referenced in `k8s-local-up.sh` | **0** |
| `allow-snippet-annotations`, comment-filtered | **0** (PIT-1: the cluster's posture is not weakened) |
| `NEXT_PUBLIC_API_URL` in `k8s-local-up.sh` | **2**; read from the render at **L248**: `API_URL="$(printf '%s' "$RENDER" \| awk '/^[[:space:]]*api\.url:[[:space:]]/{print $2; exit}')"` where `RENDER="$(kubectl kustomize "$OVERLAY")"`; all four other `NEXT_PUBLIC_*` build args passed at L279-282 |
| `sudo` in `k8s-local-up.sh` | **1** occurrence, inside a printed instruction; `grep -nE '^\s*sudo '` returns **nothing** |
| image-identity tokens in the evidence path | **4** lines matching `jtoye-core-java\|jtoye-edge-go\|jtoye-frontend\|jtoye-pg-backup` |
| role SQL invoked, not reimplemented | `create-backup-role.sql` refs **3**; `CREATE ROLE\|ALTER ROLE`, comment-filtered **0** |
| bucket privacy at source | `anonymous set download` **0**; `mb --ignore-existing` **1** |
| idempotent creation at source | `dry-run=client -o yaml` **1**; `kubectl delete secret` **0** (no delete window) |
| no secret value printable | the `(echo\|printf).*$SECRET_VAR` grep returns **nothing** across both scripts |
| namespace single source of truth | `k8s_local_namespace` → `jtoye-local`; that literal appears **0** times in either script |
| `.env.example` 15-key loop | prints nothing |
| `.env` 15-key loop | prints nothing; `DB_BACKUP_PASSWORD` is 64 chars and not `CHANGE_ME` (value never printed) |
| `git diff --quiet scripts/verify-env.sh` | **true** — the compose preflight was deliberately not made stricter, and it still exits **0** with the new `.env` |
| `deploy.sh` raw base applies | `kubectl apply -f "$PROJECT_ROOT/k8s/base/` = **0**; `apply -k` = **2** (1 code + 1 comment); `rollout undo` = **1**; old `^(dev|staging|production)$` regex = **0** |
| `deploy.sh` rejection is pre-cluster | validation `case` at **L40** < first `kubectl` at **L65** |
| `./scripts/deploy.sh dev all` | **exit 1**, names `scripts/k8s-local-up.sh` |
| `./scripts/deploy.sh local all` | **exit 1**, names `scripts/k8s-local-up.sh` (with an accurate, distinct message) |
| `./scripts/deploy.sh nonsense all` | **exit 1**, "Use: staging or production" |
| all five CI gates | `check-env-contract` **0**, `check-render-invariants` **0**, `render-golden` **0**, `check-connection-math` **0**, `check-no-plaintext-secrets` **0** |

## Task 3 (Playwright) results

- `playwright test --list e2e/stomp-relay.spec.ts` → **`Total: 4 tests in 1 file`** — identical before and after the change.
- `PLAYWRIGHT_BASE_URL=http://app.jtoye.local … --list` → **4 tests**, and the spec's own `DEBUG_E2E_TARGET` print, emitted at list time by the real file, yields:
  `[stomp-relay] BASE=http://app.jtoye.local API=http://localhost:8089 cookieDomain=app.jtoye.local`
  **Observed derived domain: `app.jtoye.local`.**
- Compose-targeted invocation **byte-identical behaviour**: with `FRONTEND_URL=http://localhost:3100` set *and* `PLAYWRIGHT_BASE_URL` pointing at the ingress, the spec still resolves `cookieDomain=localhost`. With no env at all: `cookieDomain=localhost`.
- `grep -c 'domain: "localhost"'` = **0**; `grep -c 'domain:'` = **2**, both `domain: COOKIE_DOMAIN`; `grep -c 'new URL('` = **1**; `PLAYWRIGHT_BASE_URL` = **2**, `FRONTEND_URL` = **2**, `RELAY_E2E` = **2** (nothing removed).
- `docs-freshness` `playwright_blocks` computed with the script's own regex on the same tracked file set: **40 → 40** (this spec **2 → 2**). `docs/metrics.json` untouched.
- `npm run build` → **exit 0**.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Non-vacuity guard added to the XOR guard**
- **Found during:** Task 1
- **Issue:** As specified, the XOR guard would have returned **clean** if `docker compose ps` produced no output at all (an unrelated compose/daemon failure, a renamed compose file). Passing by finding nothing is the second silent-pass mode 26-03 already had to close for the render invariants.
- **Fix:** Empty compose output now exits **2 (VOID)** with an explicit message. Proven RED with a shim that prints nothing.
- **Files modified:** `scripts/lib/k8s-local-guards.sh`
- **Committed in:** `6f94296`

**2. [Rule 2 - Missing Critical] `deploy.sh local` message was factually false as specified**
- **Found during:** Task 2
- **Issue:** The plan asked for `dev` and `local` to be rejected "with the same guidance". A shared message reading "there is no k8s/local overlay" is **now untrue** — 26-04 created it. Shipping it would have put a false statement in an operator-facing error.
- **Fix:** Two distinct branches. `dev`: "no k8s/dev overlay exists anywhere in the repo, so the old code applied k8s/base with no overlay at all." `local`: "the k8s/local overlay does exist, but bringing it up needs the compose-XOR guard, the out-of-band secret bootstrap and locally built images." Both point at `scripts/k8s-local-up.sh`; both still exit non-zero above every cluster call.
- **Verification:** both probes exit 1 with the correct, accurate text.
- **Committed in:** `dd194ab`

**3. [Rule 2 - Missing Critical] Image-name drift guard added**
- **Found during:** Task 2
- **Issue:** Nothing in the plan stopped `k8s-local-up.sh` from building four correctly-named-*today* images that a later manifest rename would silently orphan — a guaranteed `ImagePullBackOff` presenting as a mystery.
- **Fix:** All four refs are cross-checked against the overlay render (`grep -Fq "image: <ref>"`) and the run dies naming the drift. The `:local` tag is parsed from the overlay's `newTag` and the pg-backup ref from the CronJob, so neither is restated.
- **Committed in:** `dd194ab`

**4. [Rule 2 - Missing Critical] Browser build-arg fallbacks parsed from compose instead of hardcoded**
- **Found during:** Task 2
- **Issue:** `NEXT_PUBLIC_SUPPORT_EMAIL`, `NEXT_PUBLIC_SUPPORT_URL` and `NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS` are **absent** from this host's `.env` (compose supplies `:-` defaults). Reading them straight from `.env` would have baked empty strings into the browser bundle; hardcoding the defaults would have introduced exactly the environment-varying literal class this plan exists to remove (CLAUDE.md ARCHITECTURE_RULE_8).
- **Fix:** A `compose_default()` helper parses the `${VAR:-default}` fallback out of `docker-compose.full-stack.yml`, so the script carries no default of its own and provably bakes what compose bakes. `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` is `:?`-required in compose, so the script **dies** if it is unset rather than baking an empty realm URL.
- **Committed in:** `dd194ab`

### Unfalsifiable / wrong acceptance criteria, replaced with strictly stronger forms

Per the upstream warning from waves 1-4, **every** grep-shaped criterion was run against a deliberately broken input **first**. Backups were taken with `cp` to the scratchpad and restored with `cp` — **`git checkout --` was never used** on an uncommitted file (the 26-04 process incident). Four criteria could not fail as written:

**5. [Rule 1 - Bug] `npx tsc --noEmit -p tsconfig.json` exits 0 — ALREADY RED before this plan**
- **Issue:** The criterion is unsatisfiable and always was: `tsc` reports **366** pre-existing errors, every one a missing `jest-dom` matcher typing (`toBeInTheDocument`, `toBeDisabled`) in `*.test.tsx` files that `next build` does not type-check. Reporting "criterion failed" would have been noise; reporting it passed would have been a lie.
- **Replacement (strictly stronger, and falsifiable):** (a) error count **unchanged 366 → 366** with the change applied, measured by setting the file aside with `cp`, re-running at `HEAD`, restoring and re-verifying the sha256; (b) `stomp-relay` appears in **0** error lines; (c) **the tsc scope was proven to cover this spec** — a deliberate `const COOKIE_DOMAIN: number = …` break took the count to **369** and named `e2e/stomp-relay.spec.ts` three times, so (b) is a real detector, not a blind one; (d) `npm run build` — the gate CI actually runs — exits **0**.

**6. [Rule 1 - Bug] `grep -q "<name>"` for secret-inventory completeness is substring-weak**
- **Issue:** The plan's plain substring grep **passes** against a script where `redis-credentials` has been renamed to `redis-credentialsX`. Proven: the broken copy still satisfied the name grep.
- **Replacement:** a per-secret check that (i) locates the `apply_secret <name>` block and (ii) requires every key the render consumes for **that** secret to appear as `--from-literal=<key>=` **inside that block**. Global key matching was also rejected as too weak — `--from-literal=password=` matches somewhere for every secret. Proven RED two ways: the renamed-secret copy → `MISSING BLOCK: no apply_secret block for redis-credentials`, and a copy with `stomp-passcode` deleted → `MISSING KEY: rabbitmq-credentials/stomp-passcode`. Both exit **1**; the real script exits **0**.

**7. [Rule 1 - Bug] Comment prose self-invalidated three greps**
- **Issue:** Three explanatory comments contained the exact tokens their own criteria ban, because `grep -v '^\s*#'` only filters **full-line** comments: `# scripts/build-images.sh is NOT reusable` made `grep -c 'build-images.sh'` return **1** (must be 0); `# consumes (\`kubectl kustomize k8s/local\`…)` broke the kubectl-call routing equality (**3 ≠ 2**); and `// domain: "localhost"` in the spec's own explanation made `grep -c 'domain: "localhost"'` return **1** (must be 0) and `grep -c 'domain:'` return **3** (must be 2).
- **Fix:** all three reworded to say the same thing without the banned token. Same class as 26-01's / 26-02's / 26-03's prose-vs-grep catches — it recurs every wave.

**8. [Rule 1 - Bug] "The whole-script invocation proves the refusal" — the mechanism itself, superseded**
- Already adjudicated upstream (Adjudication J) and honoured: see *The bootstrap script was NOT invoked in this plan* above. Recorded here so the count is complete.

---

**Total deviations:** 8 auto-fixed (4 missing-critical strengthenings, 4 bug/unfalsifiable-criterion corrections). **Impact:** no scope creep. Four of the eight are anti-false-green catches; two of those (#5, #6) would otherwise have let this plan report a green it had not earned, and one (#2) would have shipped a factually false operator-facing message.

## Issues Encountered

- **`minikube ip -p jtoye` exits 83 while Stopped** (confirmed on this host). Handled exactly as research prescribed: the guard falls back to `minikube profile list -o json`'s `.valid[] | select(.Name==$p) | .Config.Nodes[0].IP` → `192.168.49.2`, and **refuses** if neither mechanism yields an IPv4.
- **MinIO publishes both an IPv4 and an IPv6 binding for the same host port**, so the naive `docker inspect` port template concatenated to `9000/tcp9000/tcp`. Fixed by emitting a newline per binding and taking the first.
- **`shellcheck` is absent on this host** — recorded, not silently skipped. `yamllint` and `actionlint` are present but had nothing to lint (this plan authored no YAML and touched no workflow). `mc` is absent on the host, which is why the bucket step uses the `minio/mc` container on the compose network, mirroring the existing `minio-init` service.
- **`gitleaks` is absent locally.** Local substitute: every tracked file this plan touched was checked for a ≥40-char hex token — **all clean**. `.env.example` carries only `CHANGE_ME` / empty placeholders, matching the existing file's shape; the two real generated secrets live only in the gitignored `.env`.

## Deferred to plan 26-07 (and why) — precise list

Every item below depends on mutating shared state, which requires 26-07's `checkpoint:human-action`. **None is a gap in this plan; each is the deliberate static/live split from `26-VALIDATION.md`.**

| Deferred criterion | Requires | Why not here |
|---|---|---|
| First whole-script `bash scripts/k8s-local-secrets.sh` run | the run itself | Adjudication J: an unattended run relies on the guard being correct; a bug creates a BYPASSRLS role on the shared dev Postgres. **26-07 Task 2.** |
| XOR guard **PROCEED** arm against real state | stopping the compose app containers | A human decision; a second concurrent session may own this stack. The fixture check here is labelled `SYNTHETIC`. |
| Context guard **PROCEED** arm | `minikube start` | The `jtoye` context does not exist in kubeconfig until the profile starts. |
| `jtoye_backup` created + `rolbypassrls` asserted **from the DB side** | creating an RLS-bypassing role | Elevation of privilege on the shared dev DB (T-26-25). |
| Backup bucket exists + unauthenticated **403-vs-200** probe | creating the bucket | The bucket does not exist until 26-07 creates it (T-26-26). |
| Secret creation, the **live base64 decode** proving `username` = `jtoye_app`, and the twice-in-a-row idempotence run | creating cluster Secrets | Cluster objects. |
| Exit-0 `--dry-run=server`, the real `apply -k`, three rollouts, ingress smoke | a running cluster | Cluster start + apply. |
| Captured image identities in the evidence block | building + loading images into a running profile | The block is asserted at source level here; the values are 26-07's evidence. |
| Full-run output grep for a known secret value | a full run | Source-level assertion only here (T-26-27). |
| In-cluster `nc -vz` reachability results, `/etc/hosts` resolution | a started profile | Both steps run after `minikube start`. |
| `RELAY_E2E=true PLAYWRIGHT_BASE_URL=http://app.jtoye.local` relay spec execution | cluster + ingress + hosts entries | **26-08's** live proof; only `--list` was run here, as the plan required. |

## Known Stubs

None. Nothing was left hardcoded-empty or placeholder-shaped. The two *conditional* Secrets (`notification-credentials`, `stripe-credentials`) are inert-by-design and log an explicit named SKIP; `smtp-credentials` is a documented deliberate omission. `NOTIFICATION_UNSUBSCRIBE_SECRET` is empty in `.env.example` by design (documented inert) but carries a real generated value in the developer's `.env`, so 26-07 will create that Secret rather than skip it.

## Threat Flags

None. No new network endpoint, auth path, file-access pattern or schema change at a trust boundary. Every surface this plan touches is already in the plan's `<threat_model>`: T-26-24 (mis-targeted apply), T-26-25 (BYPASSRLS role), T-26-26 (dump exposure), T-26-27 (secret in output), T-26-28 (argv, accepted), T-26-29 (dual writers), T-26-30 (`sudo`), T-26-31 (stale image), T-26-32 (`deploy.sh` raw applies), T-26-60 (this plan mutating shared state). Each is either mitigated at source level here or explicitly routed to 26-07 in the table above.

## Other Quality Contracts

- **Web performance (mobile-first): N/A** — no bundle, route or asset change. The frontend image build changes only which values get baked; `npm run build` output is unchanged in shape.
- **SEO / discoverability: N/A** — `.local` hosts are not internet-reachable; no public surface changed.
- **AI agent-readiness: N/A** — no endpoint, contract or OpenAPI change.
- **Security:** contracted via the `<threat_model>` above; static halves discharged here, live halves in 26-07.

## User Setup Required

None new for the repo. **For plan 26-07's rehearsal the human must:** (a) bring the four compose **app** containers down while leaving the six **backing** services up, and (b) add `192.168.49.2 api.jtoye.local app.jtoye.local` to `/etc/hosts` — `k8s-local-up.sh` prints the exact line and the exact command and never escalates privilege itself. The `.env` on this host already carries every key the bootstrap needs.

## Next Phase Readiness

- **26-06** (docs / `docs/metrics.json` / `PRODUCTION_READINESS_REPORT.md`): both files are untouched by this plan — verified `docs/metrics.json` clean and absent from `git diff HEAD~3..HEAD`. This plan contributes **0** to every counted invocation total (three bash scripts, which `docs-freshness.sh` does not count, and a Playwright spec whose `test()` count is unchanged at 2 / 40). `docs-freshness` check mode stays **known-RED at 26-01's delta** until 26-06 writes it. `k8s/LOCAL.md` should document the `K8S_LOCAL_*` keys, reconcile the `POSTGRES_BACKUP_PASSWORD` naming in `k8s/QUICK_START.md`, and use `k8s-local-up.sh`'s step-12 block as its rehearsal-evidence template.
- **26-07** (live rehearsal): everything it needs is committed and statically proven. Re-probe the host first — a second session can drive this checkout. Order: human stops the compose app containers → `bash scripts/k8s-local-up.sh` → capture the step-12 evidence block. Note that `minikube ip -p jtoye` starts working once the profile is Running, and the `jtoye` context appears in kubeconfig only then.
- **26-08** (auth/relay E2E through the ingress): `stomp-relay.spec.ts` is now ingress-capable; drive it with `PLAYWRIGHT_BASE_URL` and **leave `FRONTEND_URL` unset**, because `FRONTEND_URL` deliberately still wins. Remember the stub cookie is a transport check only — the DEF-5 split-horizon proof needs the real Keycloak login in `dashboard-mobile.spec.ts` (creds: `admin-user` / `.env` `KC_SEED_USER_PASSWORD`).
- **INFRA-01 is NOT marked complete** (anti-false-green, consistent with 26-01..26-04): this plan closes the static half of the tooling only. Its live rows — server dry-run, rollout, boot assertions, backup rehearsal, ingress login — are 26-07 and 26-08.

## Self-Check: PASSED

- Created files verified present: `scripts/lib/k8s-local-guards.sh`, `scripts/k8s-local-secrets.sh`, `scripts/k8s-local-up.sh`, `26-05-SUMMARY.md`.
- Commits verified present in git history: `6f94296`, `dd194ab`, `537f45f`.
- Working tree clean before the metadata commit; no scratchpad shim leaked into the repo.

---
*Phase: 26-local-k8s-overlay-verified-breakage-fixes*
*Completed: 2026-07-25*
