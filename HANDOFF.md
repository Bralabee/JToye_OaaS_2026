# Handoff: 27-01 MERGED to main · domain moved to olajay.co.uk (PR #317 open) · AKS deploy planned, not started

**Generated:** 2026-07-27 ~22:30 BST. Supersedes the previous handoff (27-01 Task 6), whose content
is now closed — 27-01 is merged.

---

## 0. WHERE TO RESUME — three things, in this order

1. **Review + merge PR #317** (`feature/domain-olajay`) — hostnames moved to `olajay.co.uk` AND a
   real pre-existing defect fixed (staging was publishing production hostnames). All gates green.
2. **Fix the two Trivy findings blocking `Build and Push Images (core-java)`** on `main` — see §4.
   Frontend and edge-go images already publish fine.
3. **The AKS deployment** — decided, scoped, deliberately NOT started. See §5. It is a multi-day
   phase with a real gap, not a "create a cluster and apply" task.

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git switch feature/domain-olajay      # or: git switch main
git fetch origin && bash scripts/check-branch-behind-base.sh   # expect rc=0
```

---

## 1. Git & environment

| | |
|---|---|
| Checkout | `/home/sanmi/IdeaProjects/JToye_OaaS_2026` |
| Branch | **`feature/domain-olajay`** @ `0188d36` — clean, pushed, 0 behind `origin/main` |
| `main` | `4da6e0f` — **27-01 MERGED** (PR #316) |
| Local branches | `feature/domain-olajay`, `main` (27-00/27-01 branches deleted; merged by content) |
| Open PR | **#317** domain move · plus **23 dependabot PRs** (#234–#259) untouched |
| Stack | Compose up. `core-java` + `frontend` show **DRIFT** — explained in §3, not a defect to chase |
| minikube `jtoye` | Stopped. Compose XOR k8s locally. |
| Azure | default sub = **`Azure subscription 1`** `c483d353-…` (`admin@jtoyedigital.co.uk`) — **yours**. `Prod - HS2 Ltd` `8d1c4578-…` is the **employer's — DO NOT TOUCH** |

### Gate state at handoff (real output, on `feature/domain-olajay`)

```
docs-freshness              rc=0   (1818)
check-branch-behind-base    rc=0
check-render-invariants     rc=0
check-env-contract          rc=0
check-terminal-states       rc=1   <- X-3 only, 27-03 owns it. NOT a regression.
check-alert-liveness        rc=1   <- correct until 27-03. NOT a regression.
check-runtime-freshness     rc=1   <- see §3; both DRIFTs explained, neither is stale CODE
```

---

## 2. What shipped this session

| PR | State | What |
|---|---|---|
| **#316** JToye | **MERGED** `4da6e0f` | Phase 27-01 media durability (V60) — a broker outage no longer destroys vendor uploads |
| **#317** JToye | **OPEN** | hostnames → `olajay.co.uk` + staging-publishes-prod-hosts fix |
| **#42** dotfiles | **MERGED** `9a30b0a` | wait-loop guard hooks |

**27-01 Task 6** completed the phase: metrics `1765 → 1818` (hand-enumerated to match), full suite
green (**unit 114 classes/820 tests, integration 102/414, jest 62/419**, all 0 failures), runtime
parity proven against two real pre-27-01 images, terminal-states TS-07 corrected + TS-17 added.
Full record: `.planning/phases/27-operational-maturity/27-01-SUMMARY.md` and
`baselines/AC-6-TASK6-ARMS.md`.

### Two defects found by process, not by tests

1. **`npm run lint` was failing (rc=1)** with a `react-hooks/rules-of-hooks` ERROR introduced in
   Task 5 — it would have failed CI's Lint job. Fixed in `38cfb3e` (29 problems/1 error → 28/0
   errors; warnings unchanged at 28, nothing suppressed). **`npm run build` and jest both stay green
   through this class — add `npm run lint` to per-task frontend verification.**
2. **The staging overlay published production hostnames** — see §6.

---

## 3. The two runtime DRIFTs are explained — do not chase them

```
core-java  DRIFT  image 20:22:54Z / newest build-input commit 4da6e0f (20:29:58Z)
frontend   DRIFT  image 20:23:18Z / newest build-input commit 0188d36 (22:19:39Z)
```

- **core-java** — `4da6e0f` is the **squash-merge commit** of 27-01. The repo squash-merges, so the
  merge stamps a NEW commit time after the image was built. The image's *content* is the 27-01 code
  (verified earlier by reading `quarantine-retention-ms` = 2 and 4 `reprocess` symbols from inside
  the running jar). No code changed; only a timestamp moved.
- **frontend** — `0188d36` is the domain commit, which touched `docker-compose.full-stack.yml` (a
  frontend build path) to change a `NEXT_PUBLIC_SUPPORT_EMAIL` default.

Both clear with `docker compose -f docker-compose.full-stack.yml up -d --build core-java frontend`.
The gate is right by its own contract — record it, don't "fix" the gate.

---

## 4. `main` CI is RED on ONE job — and it is not from this session's code

`CI/CD Pipeline` on `4da6e0f`: everything green except **`Build and Push Images (core-java)`**.
Frontend and edge-go images **build and publish fine** (verified in the registry: newest
`jtoye-frontend` version tagged `4da6e0f50f…`, `main`, `latest`).

The core-java image **builds and pushes successfully**; the post-push **Trivy gate** fails on 2
fixable findings — the `trap_trivy_daily_db_timebomb` pattern, unrelated to any code change here:

| package | CVE | installed | fixed in | fix |
|---|---|---|---|---|
| `libexpat` | CVE-2026-56131 | 2.8.1-r0 | 2.8.2-r0 | Alpine pkg from `eclipse-temurin:21-jre-alpine` → refresh base image, or targeted `apk upgrade libexpat` in `core-java/Dockerfile` |
| `commons-beanutils` | CVE-2025-48734 | 1.9.4 | 1.11.0 | **transitive** (not in `build.gradle.kts`) → add an explicit resolution constraint. Bump the EXACT flagged dep, not a broad upgrade |

`Deploy to Staging/Production` stayed **skipped** (`DEPLOY_*_ENABLED` unset), so nothing shipped.

**Also proven this session (issue #276):** the frontend leg failing **cancelled** the core-java and
edge-go legs. `fail-fast: false` on that matrix would let the healthy images publish.

---

## 5. AKS deployment — DECIDED, SCOPED, NOT STARTED

**Decision: provision AKS and use the repo's kustomize overlays as designed** (chosen over reusing
the existing Container Apps, and over a hybrid).

### What already exists in YOUR Azure sub (`c483d353-…`, rg `jtoye-rg`, uksouth)

A **live, running** earlier incarnation of this same product on **Azure Container Apps**:

```
snackpass-env            Microsoft.App/managedEnvironments
snackpass-webapp         Running  public    ghcr.io/bralabee/snackpass-webapp:deploy
snackpass-go-edge        Running  public    ghcr.io/bralabee/snackpass-go-edge:deploy
snackpass-java-core      Running  internal  ghcr.io/bralabee/snackpass-java-core:deploy
snackpass-redis          Running  internal  redis:7-alpine
snackpass-minio          Running  public    minio/minio
snackpass-python-vision  Running  internal
snackpass-pg             Microsoft.DBforPostgreSQL/flexibleServers   <- MANAGED POSTGRES, reuse this
jtoye-bootcamp           Microsoft.Web/staticSites
```

`java-core`/`go-edge`/`webapp` map exactly onto `core-java`/`edge-go`/`frontend`.

### THE GAP — why this is a phase, not an afternoon

**The repo deploys only 3 Deployments** (`core-java`, `edge-go`, `frontend`) and **expects** four
dependencies it provides **no manifests for**:

```
keycloak.jtoye-infrastructure.svc.cluster.local
rabbitmq.jtoye-infrastructure.svc.cluster.local
redis-cluster.jtoye-infrastructure.svc.cluster.local
+ Postgres (host supplied via secret)
```

There is **no `jtoye-infrastructure` namespace in this repo.** `kubectl apply -k k8s/production` on a
fresh AKS would start three pods that immediately fail to reach anything. **Keycloak hosting is an
unmade decision** — it is an external IdP, not deployed here, and Phase 26 deliberately removed a
dangling `auth.*` ingress rule (production renders only `api.` and `app.`).

### Prerequisites measured

- `Microsoft.ContainerService` = **NotRegistered** on the subscription (free to register).
- **8 Secrets / 25 keys** required before pods can start:
  `postgres-credentials` (host, port, database, username, password, backup-username, backup-password) ·
  `keycloak-credentials` (admin-username, admin-password, frontend-client-secret) ·
  `rabbitmq-credentials` (username, password, stomp-login, stomp-passcode) ·
  `stripe-credentials` (api-key, webhook-secret) · `s3-media-credentials` (access-key, secret-key) ·
  `s3-backup-credentials` (access-key, secret-key) · `smtp-credentials` (username, password) ·
  `redis-credentials` (password) · `nextauth-secret` (secret) ·
  `notification-credentials` (unsubscribe-signing-secret).
  Repo has only `k8s/base/secrets-template.yaml.example` + `k8s/scripts/seal-secrets.sh`.
- `az vm list-usage -l uksouth` returned **empty** — vCPU quota UNVERIFIED. Check before sizing.

### DNS — registered, delegated, NOT pointing anywhere

`olajay.co.uk` NS = `dns1-4.p05.nsone.net` (**NS1**, not Azure DNS — records are made in NS1, not
via `az network dns`). **No A records** exist for apex, `api.`, `app.`, `auth.` (verified by `dig`).

Order matters: cluster → nginx ingress LB IP → **then** A records → **then** cert-manager HTTP-01
can solve. Requesting a cert for a non-resolving host is the documented failure mode.

### Suggested task order

1. Register `Microsoft.ContainerService`; verify vCPU quota in uksouth; price the SKU.
2. Decide Keycloak hosting (in-cluster vs Azure Container Apps vs managed) — **blocking**.
3. Author the missing `jtoye-infrastructure` layer (Keycloak, RabbitMQ, Redis).
4. Reuse `snackpass-pg` as the database; wire the 25 secret values via `seal-secrets.sh`.
5. AKS → nginx ingress → cert-manager + `letsencrypt-prod` ClusterIssuer.
6. NS1 A records → `api.`/`app.` (+ `-staging`) → LB IP.
7. `kubectl apply -k k8s/production`, then verify in a real browser.

---

## 6. PR #317 — the domain move, and the defect it uncovered

Only **hostnames** moved (58 functional refs). **Identity untouched on purpose**: `uk.jtoye` Java
packages (1801), Keycloak realms (713), DB/container `jtoye_` (682), image names (210).
**Zero source files hardcode the domain** (core-java 0, edge-go 0, frontend 0) — the config-injection
design meant no application change at all.

**The defect:** `k8s/staging/kustomization.yaml` patched the ConfigMap and images but **not the
Ingresses**, so staging rendered the **production** hostnames inside `jtoye-staging` — the two
goldens were *identical* — while its ConfigMap claimed `app-staging.`/`api-staging.`, which nothing
served. Consequences: CORS/CSP/OAuth computed from an unpublished host; staging and prod contending
for the same names and one TLS SAN list; CI smoke-testing a third name.

Fixed with two **JSON6902** patches (`k8s/staging/ingress-hosts-patch.yaml`,
`sse-ingress-hosts-patch.yaml`) — **not** strategic-merge, because `IngressSpec.rules` has no
`patchMergeKey` and a merge would silently drop every `http.paths` backend. Verified 3 `pathType`
entries survive in both renders. Staging now has its own `jtoye-staging-tls`.

**Deliberately unchanged:** `jtoye.co.uk/placeholder` and `/notes` in `50-observability.yaml` are
Kubernetes **annotation key namespaces**, not hostnames (`check-render-invariants.sh` has an explicit
exclusion for them). `.planning/**` and `docs/{archive,audit,reports}` are the historical record.

**Follow-up not in the PR:** no invariant asserts "a non-production overlay must not publish
production hostnames". Adding **INV-7** to the 1063-line `check-render-invariants.sh` deserves its
own change with its own falsification against the pre-fix tree.

---

## 7. CI variables — set, with a live obligation

```
FRONTEND_PUBLIC_API_URL                = https://api.olajay.co.uk
FRONTEND_PUBLIC_CUSTOMER_KEYCLOAK_URL  = https://auth.olajay.co.uk/realms/jtoye-customers
DEPLOY_ENABLED / DEPLOY_STAGING_ENABLED = UNSET  (verified 0 — nothing auto-deploys)
```

**⚠ The realm is a trap.** k8s only defines `jtoye-prod`, but that is the **staff/API** realm. The
customer variable must use **`jtoye-customers`** (own export at
`infra/keycloak/realm-export-customers.json`); `frontend/lib/customer-auth.ts` warns *"never fall
back to jtoye-dev (staff realm)"*. Copying the k8s value would be a security-relevant error.

**⚠ Do not set `DEPLOY_*_ENABLED` until DNS resolves.** These values are inlined by `next build` and
cannot be corrected at runtime; deploying before DNS gives a pod that passes every readiness probe
while the browser cannot reach the API.

---

## 8. New this session: the wait-loop guard (global, already merged)

Three abandoned `until ! pgrep -f "X"` shells were found alive after **~20 h** — `pgrep -f` matches
full command lines, so each matched **itself** and could never exit. All three waited on the same
gradle run; each was a retry of the last, because the failure is silent and looks exactly like
"still running".

Now blocked at source by `~/.claude/hooks/block-unbounded-waitloop.sh` (PreToolUse/Bash), with
`reap-stale-shells.sh` for strays. Merged in dotfiles **PR #42**.

**Practical note:** the guard will block a command that merely *quotes* the bad pattern inline
(it blocked its own commit once). Heredoc bodies are exempt unless piped to a shell — to test it,
put the payload in a file.

---

## 9. Standing traps (carried forward, all still live)

- **`grep` here is a bash function → ugrep.** Use `command grep` in scripts.
- **`grep -c` returning 0 exits 1** — under `set -e` an expected-0 kills its own harness.
- **`cmd | grep -q X` under `pipefail` INVERTS on match** (SIGPIPE→141). Use here-strings.
- **Capture exit codes on the same line**: `out=$(cmd 2>&1); rc=$?`.
- **`cleanTest`/`cleanIntegrationTest` are load-bearing** — proven again: `:core-java:test` twice
  without them prints `UP-TO-DATE` + `BUILD SUCCESSFUL in 1s` while executing **zero** tests.
- **Read counts from `core-java/build-local/test-results/`**, never `core-java/build/` (stale).
- **`docs/metrics.json` is a cross-branch conflict hotspot** — `docs-freshness.sh --write` is the
  arbiter; `CLAUDE.md:15` and `AGENTS.md:15` quote the totals and change in the SAME commit.
- **The repo squash-merges**, so ancestry lies — verify merged-ness by content or PR state.
- **eslint's last line is the FIXABLE count**, not the verdict — compare BOTH numbers.
- **A second Claude session may share this checkout.** Stage by explicit path; `git add -A` unsafe.
- **Do not add `Co-Authored-By` trailers.** Do not hand-run `state.record-session`.
- **`git stash -u` is unsafe here** (root-owned untracked paths under `infra/monitoring/`) — use
  `git worktree add --detach` for baselines.
- **`frontend/e2e/media-review-320.spec.ts:23` documents port 3100; this stack serves 3000.**
  Following the comment produces a false RED on a passing spec. Playwright specs also need
  `E2E_VENDOR_PASSWORD` (from `.env` `KC_SEED_USER_PASSWORD`).

---

## 10. Open, independent of the above

- [ ] **23 dependabot PRs** (#234–#259) — triage, do not bulk-merge. Several majors violate the
      pinned stack (Spring Boot 4.1.0, tailwind 4, eslint 10, testcontainers 2.0.5).
- [ ] **#274** gitleaks allowlists inert · **#276** matrix `fail-fast: false` (now *proven*).
- [ ] **Evidence row L6** — a KDS client receiving a relayed order event through a real broker has
      still never been captured. #266 fixed (`d964a85`) but unproven.
- [ ] 6 open security + 7 code-review warnings from Phase 26 — `deferred-items.md`.
- [ ] **Phase 27 remaining:** 27-02, 27-03 (owns the 4 X-3 runbook sections + the alert rules behind
      `MediaStallFailures` / `MediaReaperSuspended`), 27-04, 27-06.
- [ ] Wire jest-dom into `tsconfig.json` so AC-5.4's type-error count becomes a real gate.
