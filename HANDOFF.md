# Handoff: two threads had no ticket, and a roadmap review cannot see what has no ticket

**Generated:** 2026-08-01 ~22:15 BST. Supersedes #424/#425, which were accurate for the E2E/outbox
session that produced them. Their §0.2 (four wrong diagnoses) and §2.4 (decisions with rationale) are
**still live** and are carried forward here in §5 — do not treat this document as discarding them.

> **§0.1 is the point of this document.** The build queue was empty and every process said the
> project was in good shape. Two substantial threads — one of them **half the stated go-to-market** —
> had no phase, no requirement ID and no issue, so no roadmap- or tracker-driven review could see
> them. A third body of work, **11 pentest findings including 3 CRITICAL**, sits in a git-excluded
> file that one `rm` would destroy. If you read one section, read that one.

| | |
|---|---|
| `JToye_OaaS_2026` | **1 PR merged: #429.** 2 epics filed: **#427**, **#428**. HEAD deliberately **not** quoted |
| Open PRs | **none** |
| Open issues | **63** (was 61; +#427 +#428) |
| Milestone | **v2.3 is OPEN and now spans Phases 21–32.** Owner ruling — see §1.1. Do **not** run `/gsd-complete-milestone` |
| Live stack | Compose UP, **17** jtoye containers, **15 healthy** — the other 2 define no healthcheck |
| Gates | **19 of 19 rc=0**, measured this session |
| Test baseline | `docs/metrics.json` **1917**, unchanged — this session added no counted invocation |
| Runtime | 4/4 built services FRESH. **No rebuild needed** — nothing this session touched source, schema, CI or workflows |

> **Why no HEAD SHAs.** A document quoting its own repo's HEAD is stale the moment it merges.
> §6 pairs every fact with the command that produces it: **run them, don't read them.**

---

## 0. ⚠ READ FIRST

### 0.1 The review was blind, and the blindness was structural

A full state review ran against `ROADMAP.md` + `REQUIREMENTS.md` + `STATE.md` + `gh issue list`. It
reported the project as feature-complete with an empty build queue. It was wrong, in three ways that
share one cause:

| what was invisible | why | now |
|---|---|---|
| **ADR-0004** — the ingredient/knowledge graph. Accepted 2026-07-30, 468 lines, a 6-step sequencing plan | Authored as a `/gsd-quick` task. No phase, no requirement ID, no issue | **#427** |
| **The catering cohort** — *half the stated go-to-market*. `BUSINESS_MODEL_DECISION_GUIDE.md` names takeaway **and** catering as separate cohorts and calls catering the wedge | Lives only in an analysis doc. No phase, no requirement ID, no issue | **#428** |
| **11 pentest findings, 3 CRITICAL** (Strix run `d8c0`, 2026-07-31 02:16) | `SECURITY-FINDINGS.md` is **untracked and git-excluded** (public repo). No issue for any finding | **still untriaged — SEC-02** |

**The filter was wrong, not the search.** Every file was readable; "planned" was defined as *has a
phase or an issue*. An Accepted ADR ending in a Sequencing section is planned work by any reading.

**The durable fix is not "look harder".** Give every Accepted ADR a tracking issue, or add a
`check-adr-has-tracking-issue.sh` that fails closed — same shape as `check-terminal-states.sh`.
**Not built.**

### 0.2 Instruments that lied, this session

| what I measured with | what it actually did |
|---|---|
| `PLAN.md`-without-`SUMMARY.md` as the pending-work scan | **Correct method, incomplete scope.** It found `23-18` — but I ran it over `phases/` and `milestones/` and **not `quick/`**, which is exactly where ADR-0004 lived. Scan all three |
| the same scan, on `motion-D-PLAN.md` | **False positive.** Its summary is `motion-D-01-SUMMARY.md`; a stem-match said "pending" over work completed 2026-07-14 |
| 10 green doc gates over `.planning/` edits | **Near-vacuous.** No gate covers `ROADMAP.md`, `REQUIREMENTS.md` or `STATE.md` — `check-doc-citations`'s `DEFAULT_DOCS` reaches `.planning/codebase/*` only. Green meant "broke nothing they watch" |
| `Integration Tests` passing in **6s**, `Frontend E2E` in **11s** on a docs-only PR | **Path-filtered, not run.** A 6-second required check is a skip wearing a tick |
| `git grep -c` to prove main carried the merge | Sound **only because a control ran**: `Phase 33` → 0 on the same file. Without it, a matcher that matches everything is indistinguishable from a real hit |

---

## 1. What landed

### 1.1 The milestone ruling — v2.3 stays open (#429)

Asked to formalise the go-to-market plan as a new milestone **v2.4**, the owner ruled:

> *"2.3 is not complete. closing nothing. just document. we will proceed with 2.3 until it's go to
> market ready."*

So `/gsd-new-milestone` was **not** run. Nothing archived, `MILESTONES.md` untouched (it still lists
only v2.1/v2.0 — now deliberate). **v2.3 widened from Phases 21–26 to 21–32**, Phase 27 recorded as
part of v2.3. Requirements went **24 → 46** (added `OPS×5` — Phase 27 never had requirement IDs, which
is exactly why a requirements-driven review could not see it — plus `SEC×4 DPLY×5 PAY×3 LGL×3 GTM×2`).

**Order: 28 → 29 → 32, with 30 and 31 in parallel alongside 29.**

`STATE.md` was corrected: it claimed Phase 27 was mid-flight at 27-03 when it closed **7/7 on
2026-07-29**, and named `check-alert-liveness` as a red owned gate that **#339** had closed. The GSD
workflows read that file first, so a fresh session would have resumed a phase finished three days
earlier. The 27-03 record is **retained verbatim** with its resolution noted in place.
`state.record-session` was deliberately **not** called.

### 1.2 #427 — the ingredient graph epic

ADR-0004's decision: **adopt the graph data model, reject the graph datastore.** Apache AGE is
disqualified for a repo-specific reason worth carrying: AGE creates label tables dynamically in a
**per-graph schema**, and `RlsContractTest` sweeps `relnamespace = 'public'::regnamespace`
(verified at `core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java:130`) — **the RLS
drift guard would stay green over an unprotected graph.**

**Built: 0%.** The edges exist as shipped columns; the Ingredient node does not — `ingredients` is
free text (V1/V25/V41), no ingredient or edge table in any migration.

**Its finding is a live product risk:** nothing ever reconciles `allergen_mask` — an integer a vendor
hand-types into a CSV column — against the ingredients text in the adjacent column, and that mask is
what the storefront renders (`frontend/components/storefront/product-detail-modal.tsx:65`). Wave 1 is
scheduled as **LGL-03 in Phase 31**.

### 1.3 #428 — the catering cohort epic, deliberately gated

Cohort B has **zero implementation**: no Quote/Enquiry/Booking entity, and `fulfilment_type` is
`DELIVERY | COLLECTION` only (V45). What *is* shipped is the honesty — `/for-operators`
(`operator-pitch.tsx:114`, `:130`) already tells operators catering and WhatsApp are *"validation
tracks, not current, guaranteed production workflows"*. **Nothing may weaken that disclaimer ahead of
the capability.**

The epic is **gated, not scheduled**: Wave 2 stays closed until ≥3 caterers are interviewed. The
business-model guide calls these *discovery* cohorts, so building quotation workflow first would
answer with code a question interviews answer free. **Wave 1 costs no engineering time and can start
today.**

---

## 2. Open items — this session's

### 2.1 The pentest backlog is still untriaged, and A1's root cause is FALSIFIED

`SECURITY-FINDINGS.md` (untracked, git-excluded; full evidence at
`~/strix_runs/host-docker-internal-9090_d8c0/`, chmod 600). **Do not repeat its claims unchecked** —
verified against the tree 2026-08-01:

- **A1** (cross-tenant BOLA on promotions/announcements, CVSS 9.1) — **its stated root cause is
  false.** It says the tables *"lack a `tenant_id` column / RLS policy"*. Both carry `tenant_id` +
  ENABLE + FORCE RLS (V28/V29/V33/V35/V39/V51), and `PromotionService`/`AnnouncementService` both call
  `shopAccessService.require(shopId, SHOP_MANAGER)` on create/update/delete since Phase 23. Either the
  tested stack was stale or the mechanism is the A2 chain. **Re-verify on a freshly built stack before
  filing or fixing** — that is SEC-01, and it is written as *re-verify*, not *fix*.
- **A2** (`X-Tenant-Id` fallback, CVSS 8.2) — **real in code, dev-scoped.** `TenantFilter` is
  `@Profile({"dev","local","test"})` and every k8s env sets `SPRING_PROFILES_ACTIVE=prod`. Compose runs
  `dev`, so it is live locally. `OpenApiConfig.java:50` still **advertises** it, in a spec finding C3
  reports as unauthenticated.
- **B1/B2/C1** — compose publishes with no bind address, so `0.0.0.0`: postgres `5433`, mailhog `8025`,
  minio `9000`/`9001`, rabbitmq mgmt `15672`.

**Confirmed-good, protect from regression:** JWT signature validation rejects `alg=none` (all case
variants), RS256→HS256 confusion, JKU injection, `kid` traversal; `core-api` enforces PKCE.

### 2.2 Two bookkeeping repairs — ✅ BOTH CLOSED 2026-08-02

*Recorded as open when this document was written; fixed the same day. Kept rather than deleted,
because the mechanism is the reusable part.*

1. **`23-18-PLAN.md` had no `SUMMARY.md`** though its work shipped (#280 CLOSED, PR #308 merged
   2026-07-26). `gsd-sdk` marks a plan done **only** by SUMMARY presence, so an unscoped
   `/gsd:execute-phase 23` would have **re-executed already-merged work**. The executor had written
   its evidence back into `23-18-PLAN.md` instead of a SUMMARY — the evidence was never lost, only
   the completion marker. **Retroactive `23-18-SUMMARY.md` written**, sourced from that evidence and
   re-verified against the live tree.
2. **Phase 23 carried three disagreeing plan counts** — prose said **15**, the progress table
   **17/17**, and **18** `*-PLAN.md` files existed, with `23-18` named nowhere in the roadmap. **Now
   18 plans / 18 summaries, and the roadmap reads 18 in both places**, with a `23-18` entry added
   under a new *Post-phase* heading.

> **Trap caught while doing this, worth carrying:** a `find` for the two test classes the plan's T6
> named (`PromotionControllerShopFilterTest`, `AnnouncementControllerShopFilterTest`) returned
> **MISSING** and was nearly written up as a coverage gap. Reading the shipping commit showed both had
> landed **consolidated into one `MarketingControllerShopFilterTest`**. *An empty search is evidence
> about the pattern, not about the code* — the third instance of that shape in this repo's records.

### 2.3 The three most load-bearing planning files are ungated

`ROADMAP.md`, `REQUIREMENTS.md` and `STATE.md` are covered by **no gate**. That is how `STATE.md` came
to claim a finished phase was mid-flight and nothing noticed for three days. A `check-state-freshness`
— assert `STATE.md`'s current phase matches `ROADMAP.md`'s progress table — would have caught it the
day it appeared. **Recommended in `260801-ths`'s SUMMARY, not built.**

### 2.4 The four blocking decisions — none are engineering tasks

Phases 29–32 do not start until these land:

| decision | state |
|---|---|
| **Production domain** | `jtoye.co.uk` never registered; `FRONTEND_PUBLIC_*` point at `olajay.co.uk`; no A records |
| **Hosting target** | Your Azure sub is `c483d353`; the employer HS2 sub is off-limits. A live `snackpass-*` Container Apps stack already runs this product |
| **Stripe test-mode keys** | Empty on every stack. Gates Phase 30 **entirely** |
| **ADR-0002 sign-off** | Still `Proposed` — *"needs owner sign-off before #101 implementation starts"*. Gates PITR / DPLY-04 |

### 2.5 The most under-weighted item in the backlog

**`k8s/` ships zero monitoring manifests.** Prometheus, Alertmanager and Grafana exist only in
`infra/monitoring/docker-compose.monitoring.yml` (Phase 27 `deferred-items.md` §5). Everything Phase
27 built — 19 alert rules, the liveness gate, `dlq-inspect` — is **compose-scoped by construction**,
so a staging deploy today would be **wholly unmonitored**. Now criterion **DPLY-03**, written to fail
on the current tree.

---

## 3. Carried forward — still true, from #424/#425

Not re-measured this session unless noted. **Read #425's §2.4 in git history before undoing any of
these** — each was chosen deliberately and at least three break something if "fixed" naively.

- **#418 — the flake's mechanism is STILL UNKNOWN, and the issue body's diagnosis is retracted.** It
  does *not* race its own `@Scheduled` flusher (`@DynamicPropertySource` parks both intervals at 24h).
  The assertion is in `PaymentEventOutboxReliabilityIntegrationTest.failedRows_resurrectAndDrain_poisonStaysDead()`
  — **now at line 294, not 273**, which both CI logs and the issue body still quote. #422 inverted the
  assertion order so the **next occurrence is diagnostic**: row `SENT` + wrong count → the mock is the
  problem; row still `PENDING` → the flush did not run. **Capture that line when it next fails.**
- **#420's CI half** — #426 added `e2e-nightly.yml` (all 126 specs, real stack). **It has never run**
  (`schedule` fires only on the default branch); dispatch it once manually. Per-PR CI still runs 2 of 126.
- **The refund E2E stays skipped deliberately** — `Stripe.Refund.create` with an empty key. Seeding
  `paymentStatus=CAPTURED` would push it past its guard and fail at Stripe: a green-looking fixture
  over a broken path. **Needs keys, not a fixture.**
- **`NoOrdersCreated` goes blind after any rebuild that recreates core-java.** Remedy:
  `bash scripts/seed-order-metric.sh`. Expect it every time.
- **Fixtures decay by design** — seeds write every instant relative to now. Re-run the seed before
  suspecting the product.
- **Cross-tenant residue** in the dev DB (one `shop_announcements`, one `shop_promotions` row).
  `CLEAN_RESIDUE=1 bash scripts/seed-e2e-fixtures.sh` removes them — **not run**, deliberately.
- **No `v2.3` git tag** — latest is `v2.2` while `build.gradle.kts` reads `2.3.0`. Now **GTM-01**.
- **`financial_transactions.order_id` has no FK to `orders`**; 3 rows point at deleted orders.
- **Toolchain: 4 DRIFT + 1 UNKNOWN**, none applied. `docker-ce` restarts the daemon — stack down first.

---

## 4. Environment state

- **JToye:** `main`, 0 behind, clean **except** `.idea/dataSources.xml` + `.idea/db-forest-config.xml`
  (staged) and `.idea/dataSources.local.xml` + `.idea/dataSources/` (untracked). **These are not mine
  and were deliberately never committed** — #429 was committed by pathspec to leave them staged.
  Decide whether they belong in `.gitignore`.
- **Live stack:** 17 jtoye containers, 15 healthy; `jtoye-redis-exporter` and `jtoye-postgres-exporter`
  report no health status because their images define no healthcheck. That is **not** unhealthy.
- **No rebuild pending** — this session changed no source, schema, CI or workflow.
- **Conda env:** none needed — no Python application code.

---

## 5. Resume instructions

```bash
# 0. Tree state, asserted rather than quoted. Resolve the default branch, never hardcode it.
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git fetch -q origin
b=$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD) || echo "VOID: no origin/HEAD"
echo "on $(git branch --show-current) vs $b: dirty=$(git status --porcelain|wc -l) ahead=$(git rev-list --count $b..HEAD) behind=$(git rev-list --count HEAD..$b)"
# expect behind=0. dirty=4 is the .idea residue above, not your change. A VOID line is NOT a pass.

# 1. Every gate. Capture rc on its OWN statement — an rc read after a pipe is the pipe's.
#    RUN FROM THE MAIN CHECKOUT, NOT A WORKTREE (compose project name comes from the directory).
for g in scripts/check-*.sh scripts/docs-freshness.sh; do
  bash "$g" >/dev/null 2>&1; rc=$?; printf '%-34s rc=%s\n' "$(basename "$g" .sh)" "$rc"
done
# EXPECT 19 x rc=0 — measured 2026-08-01. A VOID (2) is not a pass.

# 2. The milestone shape, read out of the file rather than remembered
git grep -c 'Phase 32: Production Cutover' HEAD -- .planning/ROADMAP.md   # expect 2
git grep -c 'Phase 33'                     HEAD -- .planning/ROADMAP.md   # expect 0 — the control

# 3. Before starting Phase 28, settle SEC-01 FIRST — it is the question the phase hangs on.
#    Rebuild ALL, then re-run A1 cross-tenant. Its filed root cause does NOT hold on the tree.
bash scripts/sync-runtime.sh && bash scripts/seed-order-metric.sh

# 4. Before touching Phase 23 at all — §2.2. An unscoped execute-phase re-runs merged work.
ls .planning/phases/23-*/23-18-SUMMARY.md 2>/dev/null || echo "MISSING — write it first"

# 5. Before merging ANY PR — never an inline gh-api-pipe-wc idiom
~/dotfiles/gates/pr-merge-guard.sh --repo Bralabee/JToye_OaaS_2026 --pr <n> --expect-head <sha>
#    0 = safe · 1 = not safe · 2 = VOID (could not evaluate — NEVER treat as 0)
```

**Recommended next move: Phase 28.** It is the only phase gated on none of the four decisions, it is
the cheapest, and it determines what is safe to deploy in Phase 29. Start at **SEC-01** — re-verify
A1 — because every other item in that phase is downstream of the answer.

**#428 Wave 1 (catering discovery) costs no engineering time and can run in parallel with anything.**

**Merged code is not running code.** After any merge touching source: `bash scripts/sync-runtime.sh`,
then reseed the order metric. `docker compose start` does not rebuild.

**Squash-merge note:** the repo squash-merges, so `git branch --merged` and `git branch -d` call
merged branches unmerged. Use PR state as the authority.
