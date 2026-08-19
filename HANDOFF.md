# Handoff: Phase 31 SHIPPED — nothing in flight, Phase 29 blocked on the owner

**Generated 2026-08-18. This is now the ONLY block in this file, and it describes current state.**
**Re-measure every figure here before quoting it forward** — that is the standing rule of this file,
and this session proved it again (see "The instrument lied five times").

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
git log --oneline -3                    # newest = the PR #640 merge, or later work since

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
| `main` HEAD | tip of `main` at or after the **PR #640** merge — deliberately NOT a sha, see below |
| Phase 31 | `42ac6dc3` — `feat(31): consumer safety and the legal floor (#633)`, 18/18 plans |
| Working tree | clean, no worktrees in use |
| Schema head | **V63**, matching the live dev database |
| Test manifest | **3185** logical invocations (Java 1713/270 files, Jest 1230/120, Playwright 113/22, Go 81/11, MCP 48/8) — `docs/metrics.json` |
| Gate sweep 2026-08-18 | **36 PASS, 0 FAIL, 1 VOID** across all 37 gate scripts |

> **Why the HEAD row names a PR and not a sha — do not "helpfully" put one back.** A document that
> records its own repository's HEAD cannot be correct at rest: writing the sha IS a commit, so the
> act of correcting it falsifies it. Measured on 2026-08-18 — the row was updated to `44cacbaf`,
> and merging that update made `main` `b17bef59`, stale again in one step. Chasing it costs a PR
> and a CI run per commit of staleness and never converges. `check-handoff-contract` reaches the
> same conclusion from the other side: H-3 allows a budget of **3** commits rather than demanding
> exactness. The PR number is stable, tells a reader what work the file describes, and the resume
> block already says to `git pull`. The OTHER shas in this file are historical facts — "Phase 31
> merged as `42ac6dc3`" is true forever — and are fine to keep.

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
- **The httpcore5 CVE fix was re-verified in the running jar after the 2026-08-18 core-java
  rebuild** (awssdk 2.51.4, #638). Read from **inside** `/app/app.jar`: `httpcore5-5.4.3.jar` and
  `httpcore5-h2-5.4.3.jar` present, exactly one version each, and the VULNERABLE `5.3.6` absent
  (0 occurrences, with a positive control finding 5.4.3 twice so the zero is about the jar and not
  about the pattern). This override is LOAD-BEARING and worth re-checking after any Boot or SDK
  bump: `apache5-client:2.51.4` requests `httpclient5 5.6.2`, Boot 3.5.16 downgrades that to 5.5.2,
  which requests `httpcore5-h2 5.3.6` — only `extra["httpcore5.version"]` forces 5.4.3. A Gradle
  `dependencyInsight` report states the BUILD intent; only reading the archive proves what ships;
- the contrast fix present in the built `app_shop_shop-discovery-client_tsx_*.js` chunk, while the
  old pattern survives only in the unrelated `dashboard-shell` chunk;
- a **real order placed through the rebuilt stack** (`ORD-00000000-20260818-FE412C58`) came back
  `allergen_mask=1`. Measured 2026-08-18: **572 NULL / 5 populated** — the V63 write-time snapshot
  working on the live API path, and the deliberate no-backfill decision visible in production data.
  The NULL count does not move; only new orders add to the populated side.

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

**NO PULL REQUESTS ARE OPEN** as of 2026-08-18. #634 merged; #604, #606, #605 and #631 were closed
after triage. The dependabot backlog is empty, and dependabot will re-propose #605/#631 in some
form on its next run.

**ONE OUTSTANDING SECURITY ITEM WAS DEFERRED WITH #631, and it did not go away with the PR:**
`next` 16.3.0 updates vendored lodash to 4.17.23 to fix **CVE-2025-13465** (GHSA-xxjr-mmjv-4gpg,
prototype pollution in lodash `_.unset` / `_.omit`; lodash 4.0.0-4.17.22 affected, fixed 4.17.23).
That fix is NOT applied on `main` — the tree still runs `next` 16.2.12.

**ASSESSED 2026-08-19: MEDIUM, LOW REACHABILITY, NOT URGENT.** Fix it when the `next` 16.3.0
migration is done; do not interrupt roadmap work for it.

**Severity — the scorers genuinely disagree, and only on ONE field.** NVD (primary) **5.3 MEDIUM**
(`A:N`), GitHub **6.5 medium** (`A:L`), lodash vendor CVSS 4.0 **6.9 MEDIUM** (exploit maturity
PROOF_OF_CONCEPT), Red Hat **8.2 HIGH** (`A:H`). All four agree on `AV:N/AC:L/PR:N/UI:N` and
Integrity: Low; the whole 5.3-to-8.2 spread is the disputed availability impact. The advisory text
itself says the flaw permits DELETION of properties but not overwriting their behaviour, which
favours the lower reading. Do not quote 8.2 as "the" score, and do not quote 5.3 either — quote
the range and the reason for it.

**Reachability — three independent constraints, measured on the installed tree:** (a) our own code
never imports lodash nor calls `_.unset`/`_.omit` (positive-controlled: the same search saw 81
`use client` files); (b) the only lodash-family package in `frontend/package-lock.json` is
`lodash.merge`, which is NOT in the advisory scope (`lodash`, `lodash-amd`, `lodash-es`,
`lodash.unset`); (c) the vulnerable internals live ONLY in two vendored Next bundles —
`next/dist/compiled/babel-packages/packages-bundle.js` (build-time only) and
`next/dist/compiled/jsonwebtoken/index.js` (library-internal) — and the attack needs an
ATTACKER-CONTROLLED path argument, while both callers pass fixed internal keys.

**A SEARCH-SCOPE FALSE NEGATIVE HAPPENED HERE — do not repeat it.** The first reachability check
was scoped to `next/dist/compiled/lodash.curry/` and returned **0** for `baseUnset`, `basePickBy`
and `customOmitClone`, which read as "the vulnerable code is not present at all". It is present —
just in two OTHER bundles. Widening the search to `next/dist` found all three. `lodash.curry` is a
single-function micro-package and was never going to carry `_.unset`. An empty result is evidence
about the SCOPE you chose, not about the code. Also note `node_modules` is gitignored, so a bare
`rg` there silently returns nothing: use `rg -uu`.

**What was NOT established:** no obvious path, which is not the same as no path. A full proof would
trace `jsonwebtoken`'s internal `_.omit` callers. That was stopped deliberately — the answer does not
change the priority.

The dependabot backlog was triaged 2026-08-18 and every failure was REAL — none a flake, none a
stale-base artifact. All four were closed with the reasoning in their PR comments. If dependabot
re-proposes any of them, this is why they were declined; do not simply rebase-and-merge:

| PR | Bump | Behind | Failing step | Verdict |
|---|---|---|---|---|
| **#606 CLOSED** | node 24-alpine to 25-alpine | 85 | support-horizon gate | **Closed 2026-08-18.** endoflife.date says node 25 is `lts: false` with EOL **2026-06-01**, already passed; node 24 is LTS to 2028-04-30. The gate is correct |
| **#605 CLOSED** | springdoc 2.8.6 to 3.1.0 (MAJOR) | 85 | OpenAPI spec regeneration | Real break — the spec cannot be generated, so the app likely does not boot. Needs real work |
| **#631 CLOSED** | frontend npm, 10 updates | 4 | Build frontend + TypeScript validation | Real break across the frontend build. Needs real work |

**#604 CLOSED** (awssdk 2.50.2 to 2.51.4) was superseded by **#638**, merged as `9387b3bf`; the
bump shipped with its doc updates on our own branch because dependabot force-pushes its own branches and would discard
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

## The instrument lied five times — read this before trusting any check

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
4. **`mergeStateStatus=UNKNOWN` means ALREADY MERGED, not "still computing".** On PR #641 it was
   read as "GitHub has not finished calculating", polled three times, and only caught when an
   independent check errored with `fatal: Not a valid object name origin/<branch>` — the branch was
   gone because another session had merged it. Worse, the conflict count printed beside that error
   read `0`, which was VACUOUS: `git merge-tree` had FAILED, so the grep counted nothing. **An empty
   result and a failed command look identical if you only read the number.** Before merging, check
   the PR is still `OPEN`.

5. **`git log --first-parent` is the only reason the changelog gate survived the merge.** The
   branch carried **44 `feat`/`fix` commits, none citing a PR**. Had `check-changelog-contract.sh`
   scanned all commits, a merge commit would have redded `main` with 44 uncited subjects. It scans
   first-parent, so `main` sees one commit. **Squash with an explicit subject ending `(#NNN)` is the
   only safe merge method here** — rebase strips the citation and voids the gate.

### Where the durable learnings live — READ THEM BEFORE RE-DERIVING THEM

Cross-session learnings are NOT in this file. They are in the per-project memory at
`~/.claude/projects/-home-sanmi-IdeaProjects-JToye-OaaS-2026/memory/`, indexed by `MEMORY.md`
(124 entries). A selective snapshot is versioned in the `Bralabee/dotfiles` repo under
`claude/projects/.../memory/` — 9 of the 124 as of 2026-08-19, via dotfiles PR #106. The memory
directory itself is NOT a git repo; the files are plain files on disk.

This session added `project_phase_31`, `trap_doc_recording_own_head_sha` and
`trap_gh_checks_polling_semantics`, and extended `trap_grep_pattern_shape_false_negative` (a new
SCOPE axis), `trap_rebase_merge_voids_changelog_gate` and `env_gotchas_local_stack`.

**The lesson that cost the most was one already recorded.** `trap_handoff_residue_count_stale`
already said "#429's preamble refuses to quote HEAD SHAs for exactly this reason" — and the HEAD-row
sha problem was still re-derived across three PRs before anyone noticed. Search the memory index
before concluding something is new.

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

**THIS FILE WENT STALE FIVE TIMES ON 2026-08-18 ALONE**, four of them within an hour of being
written, and one caused by a DIFFERENT session merging #634. The cause is structural, not
carelessness: H-2 claims mirror forge state, and any PR merge by ANYONE falsifies them. H-2 caught
every one within minutes and named the PR — that is the design working. What it can NEVER see is
shas, prose and runtime facts, which drifted silently every single time. So: after editing forge
state, re-run the gate; and re-read the prose by eye, because nothing else will.
