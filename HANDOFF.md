# Handoff: eight faults in one CI job, seven of them hidden behind each other

**Generated:** 2026-08-04 ~00:30 BST. Supersedes the 2026-08-02 handoff. Its §0.1 (the QA council's
findings live only in a gitignored directory) and §4 (the four blocking decisions) are **still live**
and carried forward here in §4 — this document does not discard them.

> **The single transferable result of this session.** A 15-agent parallel run worked 20 issues, and
> **eight filed claims were falsified while being worked** — three of which would have shipped an
> outage or a no-op over a live defect. Not one was caught by a test passing. Every one came from
> running the fail direction first. That is now the eleventh through eighteenth instance of a pattern
> this repo has recorded since July; it is not advice any more, it is the base rate.
>
> **The second result is about masking.** `e2e-nightly.yml` had never once succeeded. Fixing it took
> **eight** separate faults, and each fix revealed the next — the stack died at Keycloak's JDBC
> connection, so nothing downstream had *ever* been evaluated. A fault you cannot reach is a fault you
> cannot see, and a job that fails early looks like one problem when it is eight.

| | |
|---|---|
| Tree | `batch/frontend-wave1-b`, clean, 0 behind `origin/main`. **Re-measure — do not trust this cell** |
| Open PRs | **2** — #518 (Lane B, this session) and #515 (the nightly fix). Both mine, neither merged |
| Open issues | **82** at generation. It moved during the session (80 → 82) as #516 and #517 were filed. `gh issue list --state open --limit 300 --json number --jq length` — the default `--limit` is **30**, which silently undercounts |
| Milestone | **v2.3 is OPEN, Phases 21–32.** Owner ruling stands (§4). Do **not** run `/gsd-complete-milestone` |
| Live stack | Compose UP, **16** jtoye containers. `check-runtime-freshness` is **rc=1** on this branch because Lane B changed frontend source — that is correct behaviour, remedy `bash scripts/sync-runtime.sh` |
| Gates | **22 scripts** (21 `check-*.sh` + `docs-freshness.sh`). On `batch/frontend-wave1-b`: 20 pass, and two are expected non-zero — `check-e2e-skip-budget` rc=2 (stored report older than `frontend/e2e`, which Lane B touches; **nightly-only, not in per-PR CI**) and `check-runtime-freshness` rc=1 (above). **Only seven gates run in per-PR CI**: changelog-contract, claims, doc-metrics, doc-versions, handoff-contract, project-version, docs-freshness — all seven pass |
| Test baseline | Read `docs/metrics.json`. Regenerated once at Lane B assembly: jest_blocks 593, files 79, total 2137, Playwright 49 |

---

## 0. ⚠ READ FIRST

### 0.1 The nightly E2E: eight faults, and why seven were invisible

`e2e-nightly.yml` is the half of #420 that runs all 126 specs against a real stack; the per-PR job runs
2 of 126. The previous handoff said it *"has still never run — dispatch it once manually."* **False.**
It had fired on schedule on 2026-08-02 and 2026-08-03 and **both failed in 4m13s**, against a workflow
that budgets ~20 min for the stack and ~20 min for the suite. Nobody looked, because the handoff said
there was nothing to look at.

**PR #515 fixes eight faults. Each was hidden by the one before it.**

| # | Fault | Why it was invisible |
|---|---|---|
| 1 | `KC_DB_PASSWORD` ≠ `POSTGRES_PASSWORD` — `POSTGRES_USER` and `KC_DB_USERNAME` are both `jtoye`, one role, generated independently | killed every run at `FATAL: password authentication failed` |
| 2 | `infra/db/init/00-create-db.sql` created `jtoye_app` with hardcoded `PASSWORD 'secret'`; core-java connects with `DB_PASSWORD` | fault 1 killed the stack first |
| 3 | PR #510 added `GRAFANA_ADMIN_PASSWORD` + `POSTGRES_EXPORTER_PASSWORD` to `REQUIRED_VARS` and **not** to the generator | latent; both recorded runs predate #510's 18:14 merge |
| 4 | `${POSTGRES_EXPORTER_USER:-jtoye}` is the same role again when unset | latent; verified matching, `pg_up 1` |
| 5 | `KC_SEED_USER_PASSWORD` is `openssl rand -hex` = lower+digits only; the realm demands `upperCase(1)` and `specialChars(1)` | **Keycloak had never survived to realm import** |
| 6 | Flyway **V46** fails on a fresh DB (`invalid input syntax for type uuid: ""`) — filed as **#517** | never reached Flyway |
| 7 | `RABBITMQ_PASSWORD` ≠ `RABBITMQ_DEFAULT_PASS` — same broker account | fails **after** `Tomcat started`, so the container looks alive for ~3s |
| 8 | `.env.example` sets `SMTP_HOST=smtp.example.com`, overriding compose's correct `${SMTP_HOST:-mailhog}` | mail health DOWN → container unhealthy → `depends_on` takes the stack down |

**Faults 1, 5 and 7 are one bug three times: credentials naming the same account, generated
independently.** `verify-env.sh` now has a cross-variable check (d) covering all three pairs, each
shown failing. Checks (a)–(c) validate every variable *in isolation* and all three passed on the
`.env` that broke every nightly run — Proof Standard #5 inside the preflight itself.

**Fault 8 is the one to carry.** A working developer `.env` **omits** `SMTP_HOST` and lets compose's
default apply. **The working configuration is the absence of a value — which is exactly what copying
an example file destroys.** Unlike faults 1–7 this one is **not gate-covered**, and the commit says so:
"is this hostname reachable from inside the compose network" needs a running stack.

### 0.2 #517 is now the sole blocker, and it is intermittent

`V46__outbox_reliability.sql` ends with a bare `UPDATE payment_event_outbox`. That table has FORCE RLS,
and the policy live **at V46 time** is `V33__fix_rls_policies.sql:19-22`, which uses the raw
`current_setting('app.current_tenant_id', true)::uuid` cast. `V51__rls_uuid_cast_safety.sql:84-87`
replaces exactly that policy with the safe `current_tenant_id()` helper — **five migrations too late**.

**Measured across five dispatches, each against an empty volume:**

| run | reached Flyway? | V46 |
|---|---|---|
| 30859595028 | no (died at realm import) | n/a |
| 30860167178 | yes | **FAILED** |
| 30860772616 | yes | passed |
| 30861419445 | yes | **FAILED** |

**2 of 3.** Intermittent, and the failure is the common case. **Do not treat one green fresh-DB boot as
proof** — the run that passed ran the same code as the runs that failed.

**Why no environment has ever hit it:** `installed_rank` on the dev DB shows `rank 45 → version 46` and
`rank 46 → version 44` — V44 was applied **after** V46 there, because `out-of-order=true` is required
(V44 filled a reserved slot after V45/V46 shipped). A fresh chain applies them in version order, which
is the opposite. **The mechanism for why the GUC is `''` rather than NULL is NOT pinned** — all six
`set_config` calls in the tree pass `is_local = true`, which should not leak. #517 says so rather than
guessing; do not read the theory as established.

### 0.3 Instruments that lied, this session

| what | what it actually did |
|---|---|
| a Postgres auth probe over `127.0.0.1` | **rc=0 for EVERY password.** `pg_hba` is first-match-wins and matches `host all all 127.0.0.1/32 trust` before the image's appended scram line. Only the **control arm** caught it. Real services connect by hostname; the working probe used a separate container. **Found independently by two agents** — the #449 agent hit it via a break arm that refused to fail, and it invalidates the QA council's own DOC-04 evidence |
| `grep -c ACCESS_REFUSED` over a CI log | matched **my own workflow comment** describing the bug. A rule firing on its own definition |
| `sed -n '1,8p'` on a match list | used to prove a variable was **absent** from the realm templates. It was at line 412. Never bound a stream you are using to prove a negative |
| `git diff | grep -E '^[-+][^-+]'` | hid 3 of 5 changed lines: a markdown bullet makes the diff line start `-- `, and the pattern excluded a second dash |
| reading steps 15/16/17 as progress | they are `if: always()` cleanup steps. Step 7 had failed and 8–14 skipped. **A run that "reached step 17" reached nothing** |
| four agents' metrics deltas, summed | +18+15+2+9 = **+44**; measured **+45**. Agent deltas compose as deltas and never as absolutes, and `it.each` is invisible to the literal-token counter |

---

## 1. What landed

### 1.1 PR #518 — Lane B (OPEN, not merged)

Four issues, four agents, **fully disjoint file sets**, zero conflicts. Merged-tree verification that no
individual agent could do: **79 suites / 630 tests / 0 failures**, `npm run build` rc=0.

- **#504 — sign-out did not sign you out, and the label is wrong.** `post_logout_redirect_uri` built
  from the container **bind address** → `http://0.0.0.0:3000/shop`. But the real finding is downstream:
  all six IdP cookies survived and the next *Sign in* returned `credentialPrompt=false`,
  `authenticated=true`, **same `sub` as the previous customer**. Shared device → account access.
  **Filed as P2; it is not a P2.**
- **#295** — Stripe Connect return/refresh 404. The issue's origin was stale (#317 moved hostnames).
- **#495 + #490** — #490 is not an overflow, it **removes the control**: the notice's `h=64` re-centres
  an `items-center` bar and lifts the `<select>` to `top=-40`, off-screen.
- **#306** — PARTIALLY-FALSIFIED. The filed pagination defect cannot manifest (3 rows, page size 20),
  but the count is wrong at one page, and `?status=` — its proposed fix — is a **proven no-op**.

### 1.2 PR #515 — the nightly (OPEN, not merged)

§0.1. **Its merge criterion was corrected in a comment**: the description says "do not merge until the
run is green", which this branch can no longer achieve because the remaining blocker is #517. Revised:
merge when the run reaches Flyway and fails *only* on #517. Satisfied twice.

### 1.3 Two issues filed, both verified before filing

- **#516** — every unsubscribe link in every email is built as the **app** origin plus the **API's**
  path (`NotificationDispatchService.java:186`), while `k8s/base/ingress.yaml:100-108` routes that host
  wholly to the frontend, which serves no `/api/v1` route and declares no rewrite. **Unsubscribe 404s
  in staging, production and local.** The consent machinery behind it is built and correct. Compliance
  exposure (PECR, Gmail bulk-sender), not cosmetic.
- **#517** — §0.2.

---

## 2. Wave 1: the other four lanes are BUILT but NOT ASSEMBLED

**11 local branches hold finished, verified work that is not in any PR.** They are local-only — a
`git clone` does not have them. Losing this checkout loses them.

| lane | branches | issues |
|---|---|---|
| **A** core-java (**EXPENSIVE ~45 min CI — batch as one PR**) | `wave1/core-502`, `wave1/core-489-483`, `wave1/core-501-498`, `wave1/core-278` | #502, #489, #483, #501, #498, #278 |
| **C** frontend tokens | `wave1/fe-451-tokens` | #451 |
| **D** k8s | `wave1/k8s-293-506`, `wave1/k8s-271`, `wave1/k8s-298-299-303` | #293, #506, #271, #298, #299, #303 |
| **E** docs/CI | `wave1/docs-449`, `wave1/ci-276`, `wave1/ci-337` | #449, #276, #337 |

**Assembly rules that are not optional** (all four prevented conflicts rather than resolving them):

1. **No agent may touch `docs/metrics.json` or `docs/CHANGELOG.md`.** Regenerate/write **once per lane**
   at assembly with `scripts/docs-freshness.sh --write`, then sync the prose in README/CLAUDE/AGENTS or
   `check-doc-metrics` reds the PR. Never arithmetic (§0.3).
2. **Where two agents share a file, give each an explicit region.**
3. **The scratchpad is SHARED across agents.** Two independently wrote `commitmsg.txt`. Suffix per agent.
4. **PRs assemble narrow, one per lane** — not for CI (`ci-lane-cost.sh` says cheap lanes save minutes)
   but because `docs/CHANGELOG.md` has one insertion point, and "take main's copy" on a conflict there
   **silently deletes your own entry**.

### 2.1 Decisions the lanes are waiting on

- **Lane C blocks on a brand decision.** #451 moved `--primary` from orange-600 to **orange-700**
  design-system-wide. Unavoidable for AA — white-on-orange-600 is 3.56:1 and there is no lighter
  foreground — but it is a palette change. Alternative offered: a separate `--primary-strong` used only
  behind text. **Results: desktop 257→58, mobile 270→31, 0 critical / 0 serious, 0 routes regressed.**
  It also caught a trap: the vendor account renders "No shop access" on every dashboard route, so tables
  never mount and `button-name: 0` from a naive sweep is an **artefact**. Measured populated separately:
  64 → 0.
- **Lane E needs two stale-doc calls**: `AGENTS.md`/`CLAUDE.md` claim "Docker Compose 1.40+" and
  "Go: 1.25-alpine" (both stale, both in gate-enforced generated files), and `ci-cd.yaml`'s step name
  still reads *"Assert the **core-java** env contract"* after D3 widened it to three services.
- **Lane E changes the gate count.** `wave1/ci-276` adds `scripts/check-image-supply-chain.sh`, so
  `check-handoff-contract` H-1 will fail against this document's `EXPECT 22`. **Re-measure and update
  §6 when Lane E merges — do not hardcode a guess.**
- **Lane A needs one OpenAPI regeneration** (`./gradlew :core-java:updateOpenApiSnapshot`), once for the
  lane. `wave1/core-278` widens the spec additively; a whole-file rewrite by four agents would collide.

---

## 3. Findings with no home — read before planning

- **#502 is understated.** `orders.fulfilment_type` is `NOT NULL DEFAULT 'DELIVERY'`, so this was not an
  edge case: **every** order created through the vendor/API path was told to come and collect.
- **#337's new gate found a live bug on its first run.** The edge decoded `processed_count` against
  core's `processedCount`; Go's `encoding/json` drops an unplaceable field silently, so **every batch
  sync reported 0 items processed**. The pre-existing test could not see it — it encoded the stub's
  response using the edge's *own* struct, so both sides agreed by construction.
- **#449 counted 16, not 17** — the council's own summary and table disagree by one — and found **8
  broken items the sweep missed**, including a README build badge pointing at a 404 repo.
- **#298 is twice as wide as filed**: three more unsupplied customer-realm variables the issue never
  named, and one of its three (`NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL`) is **already supplied** through the
  enforced build-arg channel — a k8s `env:` entry for it would be dead config.
- **Five findings carried only as allowlist entries in `check-env-contract.sh`**, in no issue:
  `NEXT_PUBLIC_KEYCLOAK_URL`, `NEXT_PUBLIC_SITE_URL` (sitemap advertises `localhost:3100` in every k8s
  env — overlaps #447), `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`, `CSP_UPGRADE_INSECURE_REQUESTS`,
  `CORE_API_INTERNAL_URL`.
- **Two omissions that must STAY omissions.** Supplying `EDGE_MANAGEMENT_PORT` moves `/metrics` off the
  application port and blinds the Phase 27 scrape config (no credentials declared). Setting
  `CSP_REPORT_ONLY` downgrades the enforcing CSP cluster-wide. A naive "is it supplied?" reading of
  either would cause an outage.
- **#483 says do NOT apply #287's fix to `SyncService`** — that path genuinely upserts, so removing the
  eviction ships stale reads. Only its *radius* was wrong; a break arm proves deletion is caught.

---

## 4. Carried forward — the blocking decisions

Phases 29–32 do not start until these land. **None are engineering tasks.**

| decision | state |
|---|---|
| **Production domain** | `FRONTEND_PUBLIC_*` point at `olajay.co.uk`; `jtoye.co.uk` IS registered and is the lower-friction target. Do not flip `DEPLOY_*_ENABLED` until DNS resolves |
| **Hosting target** | Your Azure sub is `c483d353`; the employer HS2 sub is off-limits |
| **Stripe test-mode keys** | Empty on every stack. Gates Phase 30 entirely, and #461 |
| **ADR-0002 sign-off** | Still `Proposed` — gates PITR / DPLY-04 |

Also unscheduled: **#427** (ADR-0004 ingredient graph, 0% built) and **#428** (the catering cohort —
Wave 1 costs no engineering time and can start today). **`k8s/` still ships zero monitoring manifests.**

**The QA council's findings still live only in `.qa-council/disc-20260802-121732/`, which is
GITIGNORED.** `.qa-council/LATEST` still points at the July run. Read the directory, not the pointer.

---

## 5. Environment state

- **Branch `batch/frontend-wave1-b`**, clean, 0 behind. 11 unassembled `wave1/*` branches are **local
  only**.
- **Live stack:** 16 jtoye containers. `check-runtime-freshness` rc=1 — Lane B changed frontend source.
  Remedy: `bash scripts/sync-runtime.sh`, then `bash scripts/seed-order-metric.sh` (the order metric
  goes blind after any rebuild that recreates core-java).
- **Conda:** none needed — no Python application code. The `block-base-python` hook refuses bare
  `python3` here and no `.conda-env` exists; use `conda run -n jtoye-ops` for scratch analysis.
- **Stripe:** UNCONFIGURED. Email → Mailhog. S3 → MinIO. Broker → RabbitMQ 4.3.4.
- **Agent worktrees** under `.claude/worktrees/` (gitignored since #482) still hold the 15 agents' trees.

---

## 6. Resume instructions

```bash
# 0. Tree state, asserted rather than quoted. Resolve the default branch, never hardcode it.
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git fetch -q origin
b=$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD) || echo "VOID: no origin/HEAD"
echo "on $(git branch --show-current) vs $b: dirty=$(git status --porcelain|wc -l) behind=$(git rev-list --count HEAD..$b)"

# 1. Every gate. Capture rc on its OWN statement — an rc read after a pipe is the pipe's.
#    RUN FROM THE MAIN CHECKOUT, NOT A WORKTREE (compose project name comes from the directory).
for g in scripts/check-*.sh scripts/docs-freshness.sh; do
  bash "$g" >/dev/null 2>&1; rc=$?; printf '%-34s rc=%s\n' "$(basename "$g" .sh)" "$rc"
done
# EXPECT 22 x rc=0 on a clean main. On batch/frontend-wave1-b expect TWO non-zero:
#   check-runtime-freshness rc=1 -> you changed source: bash scripts/sync-runtime.sh
#   check-e2e-skip-budget   rc=2 -> stored report older than frontend/e2e; nightly-only
# Neither is a regression. Both print their own remedy.
# ⚠ Lane E adds a gate script — re-measure this number when it merges.

# 2. The eleven unassembled branches. These are LOCAL ONLY.
git branch --list 'wave1/*'

# 3. Is the nightly still blocked only by #517? Dispatch and read the FAILING STEP, not the last step —
#    steps 15/16/17 are `if: always()` cleanup and run even on failure.
gh workflow run e2e-nightly.yml --ref fix/e2e-nightly-credential-pairing
gh run list --workflow=e2e-nightly.yml --limit 1
# Expect: step 7 fails, and the core-java log shows V46 (#517) — 2 of 3 historically.

# 4. Before merging ANY PR — never an inline gh-api-pipe-wc idiom
~/dotfiles/gates/pr-merge-guard.sh --repo Bralabee/JToye_OaaS_2026 --pr <n> --expect-head <sha>
#    0 = safe · 1 = not safe · 2 = VOID (could not evaluate — NEVER treat as 0)
```

**Next move, in order.** Assemble **Lane A** (core-java, the expensive lane — batching it is worth ~45
min per PR avoided; four branches, one OpenAPI regeneration). Then **Lane E**, then **Lane D**. **Lane C
waits on the `--primary` brand decision** (§2.1). Merge #515 on its corrected criterion, and treat
**#517** as the real blocker for #420 — it is a product defect, not a CI one, and no environment can be
provisioned cleanly on the first attempt until it is fixed.
