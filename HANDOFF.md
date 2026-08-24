# Handoff: Phase 31 shipped, the CI detectors got audited, Phase 29 still blocked on the owner

**Generated 2026-08-24, replacing the 2026-08-18 block.** This is the only live block in this file.
**Re-measure every figure here before quoting it forward** — that is this file's standing rule, and
the 2026-08-24 session broke it once itself (see "The truncating filter", below).

> **History moved out on 2026-08-18.** Five stacked session blocks are archived verbatim at
> **`docs/archive/HANDOFF-history-through-2026-08-17.md`** — Phase 28 close-out, Phase 33 shipped,
> and two process-forensics sessions. They carry measured break-arm results and trap mechanisms
> recorded nowhere else. The archive is **not** covered by `scripts/check-handoff-contract.sh`,
> which reads this file only — so its stale claims can neither red the build nor be trusted.

## Resume here

**Branch `main`, clean tree, nothing in flight.** No phase is part-done and no branch is waiting.

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git checkout main && git pull --ff-only && git status --short   # expect clean

# Gates. EXPECT 37 x rc=0 — and a VOID (2) is NOT a pass.
for g in scripts/check-*.sh scripts/docs-freshness.sh; do
  bash "$g" >/dev/null 2>&1 || echo "rc=$? $(basename "$g")"
done
# 2026-08-24 actual: 36 clean, plus check-e2e-skip-budget VOID — the documented
# once-per-merge staleness detector, re-earned by running Playwright on the live stack.
# If check-alert-metrics is the only rc=1 after a core-java rebuild, its standing
# remedy is: bash scripts/seed-order-metric.sh   (restart zeroes the counter)
```

| | |
|---|---|
| `main` HEAD | tip of `main` at or after the **PR #658** merge — deliberately NOT a sha, see below |
| Phase 31 | `42ac6dc3` — `feat(31): consumer safety and the legal floor (#633)`, 18/18 plans |
| Working tree | clean, no worktrees in use |
| Schema head | **V63**, matching the live dev database |
| Test manifest | **3185** logical invocations (Java 1713/270 files, Jest 1230/120, Playwright 113/22, Go 81/11, MCP 48/8) — `docs/metrics.json` |
| Gate sweep 2026-08-24 | **36 PASS, 0 FAIL, 1 VOID** across all 37 gate scripts |

> **Why the HEAD row names a PR and not a sha — do not "helpfully" put one back.** A document that
> records its own repository's HEAD cannot be correct at rest: writing the sha IS a commit, so the
> act of correcting it falsifies it. Measured 2026-08-18 — the row was set to `44cacbaf`, and
> merging that update made `main` `b17bef59`, stale again in one step. `check-handoff-contract`
> reaches the same conclusion from the other side: H-3 allows a budget of **3** commits rather than
> demanding exactness. The OTHER shas in this file are historical facts and are fine to keep.

### What shipped 2026-08-24

- **#657 CLOSED** — the amqp-client CVE pin was setting `rabbitmq-amqp-client.version`, a key
  nothing reads. The Boot BOM declares `rabbit-amqp-client.version`. A redundant direct
  `implementation()` pin was forcing the right version anyway, which is exactly why the typo was
  invisible. Property corrected, direct pin removed.
- **#658 CLOSED** — `base-image-freshness.yml` **had never scanned anything, in its entire life.**
  It assembled `ghcr.io/${OWNER}/...` from `github.repository_owner` = `Bralabee`; GHCR repository
  names must be lowercase, so every leg tripped the VOID arm before reaching Trivy. Present in the
  workflow's first commit (`e705d38f`, #520, 2026-08-04). **21 consecutive scheduled runs, all
  failure, zero successes ever.** Fixed on both the scheduled and the dispatch path, plus a
  `$GITHUB_OUTPUT` injection, the missing VOID report arm, a `gh` stderr fold, and `X-6` in
  `check-image-supply-chain.sh` so a revert fails loudly.
- **#659 OPEN** — filed, not fixed: `ci-cd.yaml` hardcodes the image owner as a lowercase literal
  in twelve places while deriving it from `github.repository_owner` in two. Same defect one layer
  down; breaks both deploy jobs on a fork, transfer or rename.
- **#661 CLOSED, and #647 CLOSED with it** — the nightly full-suite E2E had been dark for **14
  consecutive nights** (2026-08-11 to 2026-08-24) without executing one Playwright test. core-java
  crash-looped every ~27s on `permission denied for table postcode_centroid` / `TRUNCATE`, resetting
  its health clock each time, so compose aborted every service depending on it. Since the SEC-04
  runtime-migrator split the app connects as the DML-only `jtoye_runtime`, and TRUNCATE is a
  DISTINCT privilege not implied by DELETE; the grant lived only in `create-runtime-role.sql`, a
  MANUAL script, and the nightly's `down -v` meant nobody ever ran it. **V64** moves the grant into
  the schema. The nightly now escalates a scheduled failure into an issue instead of dumping logs
  into the run that already failed.
- **Two blind spots kept it alive, and both are worth remembering.** It cannot reproduce locally —
  this machine's role was granted TRUNCATE out of band and `postcode_centroid` already holds
  1,748,230 rows, so the importer short-circuits before the TRUNCATE. And
  `RuntimeRoleGrantContractTest` asserts *exactly this grant* and was green throughout, because its
  `@BeforeEach` runs `create-runtime-role.sql` itself: **it certifies the script, not the
  deployment.** `PostcodeTruncateGrantMigrationTest` is the falsifiable sibling.

## Environment state — measured 2026-08-24, not remembered

All compose services running. Runtime is **Docker Compose** (`docker-compose.full-stack.yml`), the
canonical local dev/E2E runtime; do not start a local minikube alongside it (they share the dev DB).

| Service | Host port | Probe |
|---|---|---|
| frontend | `3000` | `/legal` → 200 |
| core-java | `9090` | `/health` → 200 (**note: 9090, not 8081** — known port shift) |
| edge-go | **`8089`** → container 8080 | `/health` → 200 |
| mcp-server | `9100` | — |
| postgres | — | schema head V63 |

**`edge-go` is published on host `8089`, not `8080`.** A probe against `localhost:8080` returns
`000`, which reads like a dead service and is not one.

`check-runtime-freshness` is **PASS, 4 of 4, 0 unverified**. core-java was rebuilt twice on
2026-08-24 and its container recreated onto image `2218aa93…`; `amqp-client-5.33.1.jar` reads out
of the running `/app/app.jar`, with `5.25.0`, `5.22.0`, `5.3.6` and `5.4.2` at **0 occurrences**
under escaping that finds `5.33.1`/`5.4.3`/`5.5.2` — so the zeros are about the jar, not the
pattern. The broker connects and both outboxes hold zero `PENDING` and zero `FAILED` rows.

## What to do next

**The roadmap says Phase 29. Phase 29 still cannot start.** It reached 9/16 and is PAUSED at its
wave-7 boundary on two owner actions, **re-measured 2026-08-24 and both still unmet**:

| Blocker | Measured 2026-08-24 |
|---|---|
| staging DNS | `dig +short` returns **no answer** for `staging.olajay.co.uk` and `api.staging.olajay.co.uk` |
| operator secrets | **0 populated / 7 declared** in `~/.jtoye/staging-operator.env` |

Neither is an engineering task. Phase 29's authoritative body — including the correct counters
(`total_plans: 93`, `completed_plans: 78`) — lives on branch **`phase-29-research`**, not on `main`.
The `progress:` counters in `main`'s `.planning/STATE.md` remain knowingly corrupt
(`completed_plans` exceeds `total_plans`) and must be repaired **there**.

If the owner has not cleared those, the highest-value available work is:

1. **The dependabot batch opened 2026-08-21 — five of the six still open:** **#650 OPEN**,
   **#651 OPEN**, **#652 OPEN**, **#654 OPEN**, **#655 OPEN**. **#653 CLOSED** (jest-axe 10→11)
   merged 2026-08-24. The 2026-08-18 triage of the previous batch found every failure was REAL —
   none a flake, none a stale-base artifact — so do not rebase-and-merge without reading each one.
2. **#659 — fix in PR #664.** `ci-cd.yaml` hardcoded the image owner in twelve places while
   deriving it in two; latent until a fork, org transfer or rename, at which point both deploy
   jobs fail with a reference no rebuild can fix. Same class as #658, one layer down. **The two
   sides of `kustomize edit set image` are NOT the same string** — the LHS is a selector into the
   checked-in manifests, so deriving it from the owner too makes kustomize fall back silently to
   the immutable 2.1.0 default. The fix reads the selector out of the overlay it must match and
   derives only the published reference; `check-image-supply-chain.sh` X-7 keeps both halves
   honest, including that `ci-cd.yaml` and `base-image-freshness.yml` still describe the same
   image. Deliberately no capitalised state word here: this entry would otherwise falsify itself
   the moment #664 merges and red H-2 on main for whoever opens the next PR.
3. Then Phase 30 (The Money Path), Phase 32 or Phase 34.

### Dependabot: what the 2026-08-18 triage concluded, and why it still applies

**#606 CLOSED** (node 24→25-alpine): endoflife.date says node 25 is `lts: false`, EOL **2026-06-01**,
already passed; node 24 is LTS to 2028-04-30. The support-horizon gate is correct.
**#605 CLOSED** (springdoc 2.8.6→3.1.0, MAJOR): the OpenAPI spec cannot be generated, so the app
likely does not boot. Needs real work. **#631 CLOSED** (frontend npm, 10 updates): real break across
the frontend build. **#604 CLOSED** (awssdk) was superseded by **#638 CLOSED**, merged as `9387b3bf`;
its failure was `scripts/check-doc-versions.sh`, because dependabot cannot know to edit `CLAUDE.md`,
`AGENTS.md` and `.planning/codebase/STACK.md`, which each pin the version in prose. **Any future SDK
bump carries the same four-site requirement.**

**One security item is still deferred:** `next` 16.3.0 updates vendored lodash to 4.17.23 for
**CVE-2025-13465** (prototype pollution in `_.unset`/`_.omit`). Not applied — the tree runs `next`
16.2.12. **Assessed 2026-08-19: MEDIUM, LOW reachability, not urgent.** Scorers disagree only on
availability impact (NVD 5.3, GitHub 6.5, vendor 6.9, Red Hat 8.2) — quote the range, not one end.
Our code never imports lodash; the vulnerable internals live only in two vendored Next bundles whose
callers pass fixed internal keys. Fix it when the `next` 16.3.0 migration happens.
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

## Four more instrument failures, 2026-08-24 — one in the prescribed remedy, one already archived

These are additions to the 2026-08-18 list below, not replacements. All three produced a
*confident wrong answer*, not an error.

1. **`docker compose up -d --build <svc>` does NOT recreate the container when every layer is
   CACHED — and that command is what `check-runtime-freshness.sh`'s own failure message tells you
   to run.** Measured: after a rebuild whose inputs were byte-identical, buildx still exported a new
   manifest digest (image `89a31b0b` → `5a41e87d` → `2218aa93`), while compose printed
   `Container jtoye_oaas_2026-core-java-1 Running` and left the container on the OLD image for
   seven minutes. The health check said `healthy` throughout — of the *stale* container. The gate
   then correctly stayed red, pointing at a remedy that could not clear it.
   **`docker compose up -d --force-recreate --no-deps <svc>` is what actually works.** The repo
   already warns that `start` and `restart` do not rebuild; this extends it to the prescribed fix.
   Note also that a squash-merge re-dates the commit, so `check-runtime-freshness` goes red on
   merge even when the build inputs are byte-identical — verify with
   `git diff <built-from> <merged> -- <build paths>` before assuming real drift.

   **`scripts/sync-runtime.sh` carried the same defect, and proved it against itself.** Measured on
   the merge of #661: the script ran `up -d --build core-java`, the image moved
   `3f5b80ed -> fc187d19`, the container stayed on the old one, and the script's own re-check
   printed `FAIL: drift REMAINS after rebuilding: core-java` and exited 1 — telling the operator to
   run the command it had just run. It now passes `--force-recreate`. This is the strongest form of
   the lesson: not a claim that a command is insufficient, but a repair script reporting its own
   failure to repair.


   **CORRECTION, and the more useful finding: this was already diagnosed on 2026-08-07 and
   archived.** `docs/archive/HANDOFF-history-through-2026-08-17.md` line 673 reads, verbatim:
   *"`sync-runtime.sh` rebuilds but does not always recreate. It left both `frontend` and
   `core-java` on their previous image IDs; the gate correctly said `[container-not-recreated]` and
   both needed `docker compose up -d --force-recreate --no-deps <svc>`."* The squash-merge re-date
   finding sits in the same bullet block (*"Any PR touching a build input costs a rebuild AFTER
   merge"*). Both were correct, both were actionable, and the script stayed broken for 17 days —
   because archiving the handoff moved the diagnosis somewhere no gate reads and nobody re-reads.
   **The archive's own header warns that its CLAIMS cannot be trusted; its DIAGNOSES were fine.**
   Before concluding you have found something in this repo, grep the archive: #658's reviewer found
   the same shape there (`.planning/quick` had recorded "metadata-action lowercases owner" a month
   before that workflow was written).
2. **`awk … > new && mv new old` silently drops a file's executable bit**, and a defensive `chmod`
   in CI hid it. Two scripts went `100755 -> 100644` this way this session:
   `check-image-supply-chain.sh` (in #658) and `sync-runtime.sh` (in #662). Nothing failed, because
   `ci-cd.yaml` already runs `chmod +x ./scripts/check-image-supply-chain.sh` immediately before
   executing it directly, and every other call site uses `bash scripts/…`. So the regression was
   invisible in both directions: CI compensated, and the humans use `bash`. It surfaced only in a
   merge diff line — `mode change 100755 => 100644`. Prefer editing in place (`sed -i`), or restore
   the bit with `git update-index --chmod=+x` and CHECK THE MERGE DIFF for mode changes.

3. **The truncating filter, walked into while fixing a blind detector.**
   `gh run list --workflow X --limit 8` was used to establish *how long* a workflow had been
   failing, and answered "eight days". `--limit 100` returns **21** rows, all failure, back to the
   day the workflow landed. A bounded stream cannot answer a question about the EXTENT of
   something — that is the one thing it structurally cannot do. The wrong figure reached a commit
   message, a changelog entry and a PR body before code review caught it. This trap was already
   recorded in this repo, which is the point.

4. **`gh pr view --json statusCheckRollup` lags the job it reports.** The Testcontainers job
   completed `success` at 16:01:26Z while the PR rollup still showed it `PENDING` for minutes
   afterwards, across repeated polls. A poll loop that only reads the rollup will sit past a
   finished run. Read the JOB (`gh run view <id> --json jobs`) when the answer matters; the rollup
   is a summary, not the source.

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
