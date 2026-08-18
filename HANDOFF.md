# Handoff: Phase 31 SHIPPED — nothing in flight, Phase 29 blocked on the owner

**Generated 2026-08-18. This is now the ONLY block in this file, and it describes current state.**
**Re-measure every figure here before quoting it forward** — that is the standing rule of this file,
and this session proved it again (see "The instrument lied four times").

> **History moved out on 2026-08-18.** This file had grown to 3035 lines across five stacked session
> blocks; only the top one described anything current, and the four below it each opened with a
> `git checkout` of a branch that had already merged and been deleted. They are archived **verbatim
> and unedited** at **`docs/archive/HANDOFF-history-through-2026-08-17.md`** — Phase 28 close-out,
> Phase 33 shipped, and two process-forensics sessions. Nothing was discarded: they carry measured
> break-arm results and trap mechanisms recorded nowhere else. Note that the archive is **not**
> covered by `scripts/check-handoff-contract.sh`, which reads this file only — so its stale claims
> can neither red the build nor be trusted.

## Resume here

**Branch `main`, clean tree, nothing in flight.** No phase is part-done and no branch is waiting.

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git checkout main && git pull --ff-only && git status --short   # expect clean
git log --oneline -3                                # expect 59dddb37, 662464c7, 42ac6dc3

# Gates. EXPECT 37 x rc=0 — and a VOID (2) is NOT a pass.
for g in scripts/check-*.sh scripts/docs-freshness.sh; do
  bash "$g" >/dev/null 2>&1 || echo "rc=$? $(basename "$g")"
done
# 2026-08-18 actual: 36 clean, plus check-e2e-skip-budget VOID — the documented
# once-per-merge staleness detector, re-earned by running Playwright on the live stack.
# If check-alert-metrics is the only rc=1 after a core-java rebuild, its standing
# remedy is: bash scripts/seed-order-metric.sh   (restart zeroes the counter)
```

| | |
|---|---|
| `main` HEAD | `59dddb37` — `docs(handoff): Phase 31 shipped… (#636)`; STATE fix `662464c7` (#635) |
| Phase 31 | `42ac6dc3` — `feat(31): consumer safety and the legal floor (#633)`, 18/18 plans |
| Working tree | clean, no worktrees in use |
| Schema head | **V63**, matching the live dev database |
| Test manifest | **3185** logical invocations (Java 1713/270 files, Jest 1230/120, Playwright 113/22, Go 81/11, MCP 48/8) — `docs/metrics.json` |
| Gate sweep 2026-08-18 | **36 PASS, 0 FAIL, 1 VOID** across all 37 gate scripts |

The single VOID is **`check-e2e-skip-budget.sh`** — the documented once-per-merge staleness
detector. It is re-earned by running the Playwright suite against the live runtime, needs no code
change, and was in exactly this state at Phase 28's close-out too. A VOID is **not** a pass; it is
also not a defect here.

### What shipped this session

- **Phase 31 merged** (PR #633, squash). Closes **#116 CLOSED**, **#103 CLOSED**, **#272 CLOSED**.
  Epic **#427 OPEN** — only its Wave 1 slice shipped. Post-merge CI on `main` concluded
  **success**, zero failed jobs.
- **ROADMAP reconciled** (`c4e1f497`): the top-level Phase 31 checkbox was unticked and the phase
  table read `7/18 In Progress` although all 18 plan checkboxes were ticked and all 18 SUMMARYs
  exist. **No gate script reads `ROADMAP.md`** — verified with a positive control — which is
  precisely why it drifted. Hand-edited; `roadmap.update-plan-progress` is still banned here.
- **STATE.md Current Position corrected** (PR #635, `662464c7`). It read `Phase: 33 / Plan: Not
  started` under a note ending *"Current position on THIS branch: Phase 31, context gathered, ready
  to plan"* — pointing a fresh session at the start of a finished phase. The `stopped_at` preamble
  also still ordered three "STILL TO DO AT MERGE" items that were all discharged; each was verified
  before being marked so. The pre-merge forensic record is retained verbatim below it.
- **Local runtime rebuilt and proven by content**, not by a gate's verdict.

## Environment state — measured 2026-08-18, not remembered

All 11 compose services `running`. Runtime is **Docker Compose** (`docker-compose.full-stack.yml`),
the canonical local dev/E2E runtime; do not start a local minikube alongside it (they share the dev DB).

| Service | Host port | Probe |
|---|---|---|
| frontend | `3000` | `/legal` → 200 |
| core-java | `9090` | `/health` → 200 (**note: 9090, not 8081** — known port shift) |
| edge-go | **`8089`** → container 8080 | `/health` → 200 |
| mcp-server | `9100` | — |
| postgres | — | schema head V63 |

**`edge-go` is published on host `8089`, not `8080`.** A probe against `localhost:8080` returns
`000` (no connection), which reads like a dead service and is not one. This cost time this session.

Runtime parity is **FRESH 4/4** and was proven by identity and content after the rebuild:

- both rebuilt containers hold the **newly-tagged image IDs** (`match=YES`) — that is what
  distinguishes a rebuild from a restart;
- `httpcore5-5.4.3.jar` read from **inside** `/app/app.jar`, with `5.4.2` absent as a negative
  control (a filesystem `find` would have returned a misleading 0);
- the contrast fix present in the built `app_shop_shop-discovery-client_tsx_*.js` chunk, while the
  old pattern survives only in the unrelated `dashboard-shell` chunk;
- a **real order placed through the rebuilt stack** (`ORD-00000000-20260817-91108CE7`) came back
  `allergen_mask=1`, against **572 historic NULL** lines — the V63 write-time snapshot working on
  the live API path, and the deliberate no-backfill decision visible in production data.

If you rebuild `core-java`, **`check-alert-metrics.sh` will go red** — the counter dies on restart.
Run `scripts/seed-order-metric.sh`; do not investigate it. Observed rc=1 then rc=0 this session.

## What to do next

**The roadmap says Phase 29. Phase 29 cannot start.** It reached 9/16 and is PAUSED at its wave-7
boundary on two owner actions, **re-measured 2026-08-18 and both still unmet**:

| Blocker | Measured 2026-08-18 |
|---|---|
| staging DNS | `dig +short` returns **no answer** for `staging.olajay.co.uk` and `api.staging.olajay.co.uk` |
| operator secrets | **0 populated / 7 declared** in `~/.jtoye/staging-operator.env` |

Neither is an engineering task. Phase 29's authoritative body — including the correct counters
(`total_plans: 93`, `completed_plans: 78`) — lives on branch **`phase-29-research`**, not on `main`.
The `progress:` counters in `main`'s `.planning/STATE.md` remain knowingly corrupt
(`completed_plans` exceeds `total_plans`) and must be repaired **there**, or the fix is reverted by
the conflict.

If the owner has not cleared those, the available work is: Phase 30 (The Money Path), Phase 32,
Phase 34, or the open-PR backlog below.

### Open PRs — triaged 2026-08-18

**#634 OPEN** — QA validation ledger for PR #512. Not dependabot; unreviewed.

The dependabot backlog was triaged and every failure is REAL — none is a flake or a stale-base
artifact. Do not simply rebase-and-merge these:

| PR | Bump | Behind | Failing step | Verdict |
|---|---|---|---|---|
| **#606 OPEN** | node 24-alpine to 25-alpine | 85 | support-horizon gate | **Close it.** endoflife.date says node 25 is `lts: false` with EOL **2026-06-01**, already passed; node 24 is LTS to 2028-04-30. The gate is correct |
| **#605 OPEN** | springdoc 2.8.6 to 3.1.0 (MAJOR) | 85 | OpenAPI spec regeneration | Real break — the spec cannot be generated, so the app likely does not boot. Needs real work |
| **#631 OPEN** | frontend npm, 10 updates | 4 | Build frontend + TypeScript validation | Real break across the frontend build. Needs real work |

**#604** (awssdk 2.50.2 to 2.51.4) was superseded: the bump plus its doc updates ship on branch
`chore/awssdk-2.51.4` instead, because dependabot force-pushes its own branches and would discard
the doc commit. Its failure was `scripts/check-doc-versions.sh` — dependabot cannot know to edit
`CLAUDE.md`, `AGENTS.md` and `.planning/codebase/STACK.md`, which each pin the version in prose.
**Any future SDK bump carries the same four-site requirement.**

### Owner-facing, unresolved — carried from Phase 31

1. **`privacy@olajay.co.uk` must exist and be MONITORED** before the `/legal` pages naming it are
   publicly reachable. Unverifiable from this repository. A published DSAR route nobody reads is
   the same fail-open shape as no route at all, only worse — it looks discharged while a one-month
   statutory clock runs. **This is the load-bearing one.**
2. **Registered office is not published** — `NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` ships empty by
   owner decision, published as a dated exception. Recoverable by one build arg **plus a frontend
   image rebuild**, since `NEXT_PUBLIC_*` is inlined at build time.
3. **`PublicFooter:189` renders the platform's company identity on every tenant storefront**, while
   `frontend/lib/company.ts:9-12` states it must render *"never on tenant storefronts"*. Pre-existing since
   PR #232. Both readings are defensible, so it is a legal-content call, not an engineering one.
   A test pins the count at exactly one so neither answer is silently pre-empted. The number
   rendered is the ACTIVE `16471464`, not the dissolved namesake `13434105`.
4. **`contrast-literals.test.ts`'s `SCAN_ROOTS` excludes `components/legal` and `app/legal`** — the
   ledger is structurally blind to the five `/legal` routes that ARE declared in-scope surfaces.
   That blind spot is exactly why a 4.41:1 mobile contrast failure survived to the final plan.
   Widening it will likely surface further literals.
5. **31-07's Article 26 effectiveness-gate box stays UNTICKED.**

Phase 31's own deferred register is `.planning/phases/31-consumer-safety-and-legal-floor/deferred-items.md`
(DEF-31-11-01 plus five items from 31-17/31-18).

## The instrument lied four times — read this before trusting any check

Every one of these produced a *confident wrong answer*, not an error. This is the most transferable
thing this session produced.

1. **Four background watchers reported false completions**, all from transient
   `error connecting to api.github.com`. One exited **0** with jobs still `pending`; another printed
   `SETTLED` over an **empty result table**. Every one would have read as "CI passed" from the exit
   code alone. **An empty result table is VOID, never an answer** — and `gh pr checks` returns rc=1
   both for "a check failed" and for a network error, so rc alone cannot distinguish them. Poll on
   *content*: require rows > 0 AND pending == 0.
2. **`SELECT max(version) FROM flyway_schema_history` returns `9`, not `63`.** The column is TEXT,
   so it sorts lexically. Use
   `ORDER BY (regexp_replace(version,'\D','','g'))::int DESC LIMIT 1`.
3. **A served-page assertion over conditionally-rendered elements is vacuous.** Checking the
   contrast fix on `/shop`, both the fixed and the old class pattern returned **0** — the elements
   simply do not render with current seed data. "0 occurrences of the bad pattern" is byte-identical
   to "fixed" and to "never rendered". Reading the built chunk out of the container settled it.
   This is the same shape as 31-18's own finding that a scan over nothing looks like a flawless page.
4. **`git log --first-parent` is the only reason the changelog gate survived the merge.** The
   branch carried **44 `feat`/`fix` commits, none citing a PR**. Had `check-changelog-contract.sh`
   scanned all commits, a merge commit would have redded `main` with 44 uncited subjects. It scans
   first-parent, so `main` sees one commit. **Squash with an explicit subject ending `(#NNN)` is the
   only safe merge method here** — rebase strips the citation and voids the gate.

### Writing in this file is itself gated

`scripts/check-handoff-contract.sh` asserts this document against reality:

- **H-1** — a bold-marked `N of N rc=0` claim requires **BOTH** numbers to equal the live
  gate-script count (currently **37**). A truthful "36 of 37" in that bold form FAILS the gate, which
  is why the summary table above states `36 PASS, 0 FAIL, 1 VOID` instead. Any `EXPECT N x rc=0`
  line must also read 37. **Note the trap: this bullet cannot quote the token it describes** —
  writing the bold form here makes the gate fire on its own definition. It did, on the first draft.
- **H-2** — a claim opts in by CAPITALISING its state word: `#116 CLOSED` is checked against the
  forge, `#116 is closed` is narrative and is not. Do not capitalise a state you have not verified.
- **H-3** — this document must stay within **3** merged commits of the base branch.
