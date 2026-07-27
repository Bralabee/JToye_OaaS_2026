# Handoff: Phase 27 (Operational Maturity) — 7 plans written, audited twice, fixed; ready to register & execute

**Generated:** 2026-07-27 · Supersedes the 2026-07-26 handoff; its still-valid content is preserved
in §7–§9. **Nothing is committed.**

---

## 1. Git & environment

| | |
|---|---|
| Checkout | `/home/sanmi/IdeaProjects/JToye_OaaS_2026` |
| Branch | `feature/phase-26-local-k8s-overlay` @ `78eaa99` — **merged, dead, 6 behind** |
| `origin/main` | `213e06f` |
| Uncommitted | `M docs/DOCUMENTATION_INDEX.md`, `M docs/analysis/README.md`; untracked: `.planning/phases/27-operational-maturity/`, `HANDOFF.md`, `docs/analysis/MESSAGING-BROKER-EVALUATION-2026-07-26.md`, `docs/architecture/decisions/ADR-0003-messaging-broker-selection.md` |
| Stack | 16 `jtoye-*` containers up; broker `3.12.14`; Prometheus host port **9091** |
| minikube `jtoye` | **Stopped, not deleted.** compose XOR k8s — never both (shared dev DB) |
| Metrics | `origin/main` = **1759 / 1176 / V59**. This branch = 1736 / 1157 (stale) |

```bash
git fetch origin && git switch -c feature/phase-27-operational-maturity origin/main
```

**Verified: the 6 unmerged commits touch nothing Phase 27 depends on** (`git diff --stat
HEAD..origin/main` over media/, `RabbitMQConfig.java`, `OrderStateChangeListener.java`,
`core-java/src/main/resources/`, `infra/monitoring/`, compose, `k8s/` → **empty**).

---

## 2. What Phase 27 is

A question about migrating off RabbitMQ was answered **no** (ADR-0003). The investigation found the
real problem: **the system is well-architected for correctness and poorly architected for failure
visibility and lifecycle.** Reframed at the user's direction from "messaging hardening" to
**Operational Maturity, messaging as the first instance** — one policy applied once.

### The 7 plans — DAG verified, 0 violations

| Wave | Plan | Owns |
|---|---|---|
| 1 | **27-00** spine | terminal-states register, alert-liveness *mechanism*, dependency-horizon manifest+gate, load-baseline harness, the `9091→9090` scrape fix, `.env.example` |
| 1 | **27-01** media durability | the P0 upload-loss defect: `quarantine_reclaimed_at` sentinel, `PESSIMISTIC_WRITE` claim lock, vendor-visible DELAYED state |
| 1 | **27-05** webhook converter | **the only plan that fixes a live outage** — see §3 |
| 2 | **27-04** throughput + guards | the inert-listener-properties fix (`ObjectProvider`), media container factory, `StompDestinations` publish guard |
| 3 | **27-03** failure visibility | all `alerts.yml` rule content, 4 missing runbook sections, `check-alert-metrics.sh`, `dlq-inspect.sh`, DLQ **archive** |
| 4 | **27-02** broker upgrade | 3.12→4.3.4 fresh install, volume snapshot/rollback, DLQ **purge + disposition** |
| 4 | **27-06** CI wiring | the `ops-contracts` job — **three** gates incl. the rescued `check-alert-rules.sh` |

Verify the DAG at any time (every dependency's wave must be **strictly less**):
```bash
cd .planning/phases/27-operational-maturity
declare -A W; for f in 27-0*-PLAN.md; do k=${f:0:5}; W[$k]=$(grep -m1 '^wave:' "$f"|tr -dc '0-9'); done
for f in 27-0*-PLAN.md; do k=${f:0:5}; for x in $(grep -m1 '^depends_on:' "$f"|grep -o '27-0[0-9]'); do
  [ "${W[$x]}" -ge "${W[$k]}" ] && echo "VIOLATION $k(w${W[$k]}) <- $x(w${W[$x]})"; done; done
```

---

## 3. Live defects, independently verified (all reproducible now)

| # | Defect | Proof |
|---|---|---|
| 1 | **Outbound webhooks have NEVER worked.** `RabbitMQConfig.java:385-387` builds `new Jackson2JsonMessageConverter()` with no trusted packages, so `DefaultJackson2JavaTypeMapper` rejects `uk.jtoye.core.*`. It bites only `WebhookFanoutListener` — the sole class-level `@RabbitListener` + `@RabbitHandler` listener, where multi-method dispatch **must** resolve `__TypeId__`. **100% of outbound webhooks dead-lettered since Phase 22.** Owned by **27-05** | `docker logs`: `IllegalArgumentException: … not in the trusted packages: [java.util, java.lang]` |
| 2 | **The producer is LIVE.** `webhook.deliveries.dlq` depth 9; oldest x-death `2026-07-15T11:46:18Z`, newest `2026-07-26T15:33:51Z`; ~5 arrivals/day. **Never assert depth == 9** | mgmt API peek, `ackmode=reject_requeue_true`, depth re-asserted after |
| 3 | **Database monitoring is blind.** `DatabaseDown` uses `up{job="postgres"}` = **1**; `pg_up` = **0** and is referenced by no rule. **Two faults**: `show ssl` → `off` while the DSN at `docker-compose.monitoring.yml:118` is `sslmode=…:-require` (deterministic, confirmed); DNS is **unproven** (the 2026-05-05 log lines predate the current run — `StartedAt=2026-07-25`, `logs --since 30m` empty). Fix sslmode first, then re-read `pg_up` | live query |
| 4 | **11 of 14 alerts defective**: 6 dataless (scrape targets `core-java:9091`, dev serves `${SERVER_PORT:9090}`), 2 evaluating **Keycloak's** JVM while labelled `service: core-java`, 1 dataless from `pg_up`, 1 reporting healthy on a blind signal. Includes `TenantIsolationFailure` | targets API; `count by (job)(jvm_memory_used_bytes)` → keycloak only |
| 5 | **`ServiceDown` fired 32+ h and nobody knew** — `activeAt 2026-07-25T13:01:41.425388701Z`, delivered to `ops@jtoye.local` via **Mailhog**, a dev sink. `.env` Slack keys are **PLACEHOLDER**, and never reach the container (`environment:` block has no SLACK entry) | `/api/v1/alerts` |
| 6 | **`StompBrokerLag` cannot fire** — aggregated mode emits `rabbitmq_queue_messages_ready 9` with **no `queue` label**; the rule selects `{queue=~…}` | selector → `[]`, control → 9 |
| 7 | **Broker unsupported.** 3.12 community EOL 2024-02-29, commercial 2025-06-30. **No direct 3.12→4.x path**; chain is 3.12→3.13→4.x with all stable feature flags first | rabbitmq.com upgrade table |
| 8 | **`spring.rabbitmq.listener.simple.*` is inert** — `RabbitMQConfig.java:402` names a bean `rabbitListenerContainerFactory`; Boot has `@ConditionalOnMissingBean(name=[…])`. Setting those properties does nothing; `auto-startup=false` in 22 test files is equally inert | `javap` on the autoconfigure jar |
| 9 | **Broker outage > 15 min destroys uploads** — `reaperGraceMs = 900_000`; `MediaPendingReaper.java:78-80` deletes bytes then flips FAILED. Cannot distinguish "worker crashed" from "never dispatched" | source |
| 10 | **The load test measures nothing** — no status assertion, `GET /shops` unauthenticated is 401. No load tool installed | — |
| 11 | **Six pins past EOL**: rabbitmq 3.12, prometheus 2.48, grafana 10.2, **keycloak 24.0**, node 20, alpine 3.20; plus `minio`/`mc`/`ollama` on `:latest`. **No rabbitmq image pin exists in `k8s/` at all** | endoflife.date, re-fetched |

**Keycloak:** we are **not** moving away from it. "Keycloak deprovisioning" (V49/#102) disables a
tenant's *users* at offboard. But 24.0.5 is past EOL (latest 26.7.0).

---

## 4. §D-S — ENVIRONMENT FACTS THAT VOID CHECKS SILENTLY

Full list in `.planning/phases/27-operational-maturity/drafts/REVISION-BRIEF.md` §D-S. The critical ones:

1. **`grep` here is a bash function → ugrep 7.5.0, not GNU grep.** It omits the `./` prefix GNU emits.
   **Any path-exclusion regex written against `./docs/…` matches nothing.** One plan's recorded
   control of 5 measures **34** in this shell. Use `git ls-files` + explicit paths + `grep -F`.
2. **`grep -c` returning 0 exits 1** — under `set -e` an expected-0 criterion kills its own harness.
3. **Greps are case-sensitive; this repo mixes case.** `grep -c sslmode .env.example` → 0;
   `grep -ci` → 1 (`POSTGRES_EXPORTER_SSLMODE=require` at `:14`). This produced a wrong correction.
4. **`PATH=/nonexistent bash script` exits 127** — bash isn't found, the script never runs, the VOID
   arm tests nothing. Use a PATH with bash+coreutils minus the tool under test.
5. **PromQL unescapes `\\` before RE2** — `"\\."` means a literal dot, identical to `[.]`; a single
   `\.` is a parse error. An earlier claim that `\\.` "matches nothing" was **wrong**.
6. **`awk '/^  job:/,/^  [a-z-]+:$/'` collapses to one line** when the start also matches the end.
   Use the `f=1;next` flag form, always with a control on a job of known size.
7. **`docs-freshness.sh --write` recomputes from the working tree** — `git diff --quiet` is clean on
   *any* base. Assert `git show origin/main:docs/metrics.json | jq -e …`.
8. **`core-java/build/` is stale (2025-12-27); live is `build-local`.**
9. **`python3` IS blocked by the `block-base-python` hook** — on the heredoc form (`python3 - <<'PY'`);
   inline `python3 -c` has passed. *(The previous handoff said it "did not reproduce" — that was wrong.)*

---

## 5. Process record — what went wrong and how it was caught

Two audit rounds (5 audits total: correctness ×2, falsifiability ×2, regression-by-omission ×1).
Draft round: 162 criteria, 22 vacuous, 1 fail-open, 2 outage-causing. Revised round: 222 criteria,
19 vacuous, **0 fail-open**, 2 outage-causing (one *newly introduced by the fix*), then fixed.

**Five blockers, three of them mine:**
- `D-B` reassigned six deliverables; I verified none landed on the other side → `check-alert-rules.sh`
  was dropped by **both** owners. Caught only by adding a regression-by-omission audit lens.
- `D-C` split archive/purge without checking the wave order → 27-03 would have STOPped at its own
  first task.
- `D-I` put 27-03 and 27-04 both in wave 2 while requiring 27-03 to depend on 27-04.

**Agent claims that were wrong and were caught by re-running:** `render-golden.sh --check` "exits 0"
(twice asserted as PROVEN BY EXECUTION — it exits **2**, the gate fails closed, do **not** "fix" it);
the classpath-shadowing consequence; the `pg_up` DNS diagnosis.

**Five of my own probes were broken**, each nearly filed as a finding: metrics probed from inside the
wrong container (`Connection refused` read as "metric absent"); a case-sensitive grep; a stale log
buffer read as current; and two wave-consistency checkers (one too loose, one defeated by a trailing
backtick). **Every one was caught by running a control.** A check you have not seen fail is not
evidence — including one you wrote thirty seconds ago.

---

## 6. THE CI BLOCKER (unchanged, not caused by this work)

`main`'s pipeline fails at **Build and Push Images (frontend)**:
```
FATAL: refusing to build the frontend image — required NEXT_PUBLIC_* build-arg(s) are empty
```
Phase 26's CR-02 gate working as designed. Two repo variables still unset (`gh variable list` empty):
```bash
gh variable set FRONTEND_PUBLIC_API_URL --body '<origin>'
gh variable set FRONTEND_PUBLIC_CUSTOMER_KEYCLOAK_URL --body '<origin>'
```
**Values need a human decision**: `jtoye.co.uk` is **NOT REGISTERED** (NXDOMAIN) while
`jtoyedigital.co.uk` is, yet every staging/prod hostname in `k8s/base` targets the unregistered name.
**Blast radius:** the matrix is fail-fast, so this cancels `core-java` and `edge-go` — nothing publishes.

---

## 7. v2.3 close-out and `/qa-council`

`/gsd-review-backlog` ✅ DONE (#307, 28 issues filed #278–#305) → **`/qa-council`** →
`/gsd-complete-milestone`. **Phase 27 was not in that sequence.** Decide whether it runs before
`/qa-council` — it fixes the monitoring the council would otherwise audit blind — or after.

**`/qa-council` audits the running app and the stack's images predate recent merges. Rebuild first:**
```bash
docker compose -f docker-compose.full-stack.yml up -d --build core-java frontend edge-go mcp-server
```
`docker compose start` does **not** rebuild. Prove by content, with a control:
```bash
docker exec jtoye_oaas_2026-core-java-1 sh -c \
  'unzip -p /app/app.jar BOOT-INF/classes/uk/jtoye/core/product/ProductService.class | strings | grep -c getProductsByShop'
# → 0 (the #280 fix is ABSENT); control with getAllProducts → 2. After rebuild expect ≥ 1.
```
**Open issues: 57.** Six overlap Phase 27 and are referenced in the plans: **#115** (load baseline —
27-00's GAP 3), **#284** (`@RabbitListener` propagates no SecurityContext — 27-04), **#289** (STOMP
shop-gate — 27-04), **#304**, **#205** (webhooks — **27-05 owns the comment; do not post a diagnosis
from any other plan**), **#209**.

**Suites at last full local run** (on the #308 branch, now `main`): `:core-java:test` 111/**792** ·
`:core-java:integrationTest` 98/**392**/1 skip (39m56s) · jest 62/**411** · `npm run build` exit 0 ·
`docs-freshness` exit 0 at **1759**. Not re-run this session.

---

## 8. Standing traps

- **`PageImpl` silently recomputes `totalElements`** — fixture total must exceed page size.
- **A gate piped into `tail`/`head` reports the pipe's exit code.** Capture first: `out=$(cmd 2>&1); rc=$?`.
- **`check-branch-behind-base.sh` measures the CWD's repo**, not the script's.
- **Fresh runtime + behind tree = missing merged work with a green gate.** Both gates are needed.
- **`gh pr merge --delete-branch` aborts if a worktree holds the branch.** `gh pr checks --json` unsupported.
- **`cleanTest` is load-bearing** or Gradle reports success while executing nothing.
- Plus everything in §4.

---

## 9. Resume instructions

1. `git fetch origin && git switch -c feature/phase-27-operational-maturity origin/main`
2. Re-apply the four doc changes (§1 uncommitted list). Expect `docs-freshness.sh` exit 0 at **1759**.
3. Read `.planning/phases/27-operational-maturity/drafts/REVISION-BRIEF.md` — binding, and it records
   six places where the brief itself was wrong.
4. **Capture the RED baselines BEFORE any edit** (§3 rows 1–6, plus the 4 missing runbook headings and
   the 6 EOL pins). Unreproducible afterwards without deliberately re-breaking the tree.
5. Register the phase: **`/gsd-phase`** — `.planning/ROADMAP.md` has no Phase 27 entry, and the
   requirement IDs must come from registration (every plan ships `requirements: []` deliberately).
6. Execute by wave (§2). 27-05 first within wave 1 if you want the webhook outage closed soonest.
7. Decide Phase 27 vs `/qa-council` ordering (§7).

**Do not** hand-run `state.record-session` (recorded trap: corrupts `STATE.md` mid-plan). `STATE.md`
still reads `status: phase-complete`, v2.3 6/6 — untouched by this session.

---

## 10. CARRIED FORWARD from `origin/main`'s handoff (Phase 26 close-out)

*Preserved verbatim in substance. This session's handoff superseded that document; these items are
NOT superseded and would have been lost.*

### Not yet done (independent of Phase 27)

- [ ] **Decide the production domain**, then set the two CI variables (§6). Everything else is downstream.
- [ ] **#274** — gitleaks allowlists are **inert**. Fix is one env line: `GITLEAKS_VERSION: "8.27.2"` in `.github/workflows/gitleaks.yml`.
- [ ] **#276** — no base-image refresh path; add `fail-fast: false` to the build matrix.
- [ ] **Evidence row L6** — a KDS client receiving a relayed order event through a real broker has **still never been captured**. #266 is *fixed* (`d964a85`) but **unproven**; `26-VALIDATION.md`'s INFRA-02d row stays RED deliberately. **Do not read the fix as a proof.** Cheapest closure: capture it on the next `scripts/k8s-local-up.sh` rehearsal.
- [ ] 6 open security + 7 code-review warnings from Phase 26, deliberately scoped out — see `deferred-items.md`; **#270/#271/#272** cover the sharpest.
- [ ] Remote branch `feature/phase-26-local-k8s-overlay` still exists; safe to delete.

### Failed approaches from Phase 26 — do not repeat

- **A fresh `git clone -b main <local-repo>` checks out your STALE local `main`, not `origin/main`.** A gitleaks scan reported "no leaks found" and would have concluded there was nothing to fix. Always `git checkout <explicit-sha>` and echo it before scanning.
- **AWS's documentation example key (`wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY`) is allowlisted by gitleaks' own default config.** Using it as a "does the scanner still catch real secrets?" control gives a **false pass**. Use a freshly random key.
- **`gitleaks detect` without `-v` prints only counts, no findings** — grepping for `Finding|Secret|File:` returns nothing and reads as clean.
- **`[[allowlists]]` (plural) is silently inert in gitleaks 8.24.3**, the version `gitleaks-action@v2` downloads. Singular `[allowlist]` with an identical regex works. Fails *safe*. → #274
- **Trivy findings in a Node image are usually NOT app dependencies.** `tar`/`sigstore` had **zero** entries in `frontend/package-lock.json` — they are npm's own bundled deps at `/usr/local/lib/node_modules/npm`. Check the lockfile before assuming an `overrides` bump can work.
- **A PR's green `gitleaks`/`Build and Push` proves nothing about a fix to them.** `build-and-push` *skips* on `pull_request`, and gitleaks scans a narrow `--first-parent A^..B` range on PRs vs `--log-opts=-1` on push. Prove locally.
- **`Integration Tests` completing in 9s is a path-gated short-circuit, not a run.** Check elapsed time before claiming a suite passed.
- **`gh pr checks --json` is unsupported** by the installed `gh`. Parse the plain tab-separated output.

### Warnings

- **`jtoye.co.uk` IS NOT REGISTERED.** Decide before setting `FRONTEND_PUBLIC_API_URL` (§6).
- **A second Claude session shares this checkout.** Stage by explicit path — `git add -A` / `git add .` / `git commit -a` are unsafe. Prefer a worktree for branch work.
- **`docs/metrics.json` is a cross-branch conflict hotspot and neither side is ever right.** Recipe: `git merge origin/main` → `scripts/docs-freshness.sh --write` → `scripts/docs-freshness.sh` (exit 0). The gate validates the JSON but **not the prose quoting it** — `CLAUDE.md:15` and `AGENTS.md:15` cite the counts and must change in the same commit.
- **Local minikube does NOT enforce NetworkPolicies** (D-11), has no TLS, and drops the PIT-1 nginx header snippet. Do not enable snippet annotations to make something pass — see `k8s/LOCAL.md` §6.
- **`k8s/goldens/.pre/` is gitignored scratch.** Committed goldens are `k8s/goldens/{staging,production}.yaml`; regenerate with `render-golden.sh --write` and commit the reviewed diff in the same change — CI enforces this.
- **Do not add `Co-Authored-By` trailers** (global instruction; the repo's dominant convention is no trailers).
