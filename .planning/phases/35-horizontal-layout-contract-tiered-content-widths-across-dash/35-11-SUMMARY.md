---
phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
plan: 11
subsystem: docs
tags: [documentation, layout, design-tokens, metrics, gates, falsifiability, coverage-honesty]

# Dependency graph
requires:
  - phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
    plan: 10
    provides: "docs/architecture/layout-tiers.tsv and the static gate the document points at as its enforcement"
  - phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
    plan: 05
    provides: "the browser-measured displaced-goods ledger and the ceiling-not-target rule the document records"
  - phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
    plan: 08
    provides: "the per-PR Marketing-tier browser step, and the coverage split the document must state without overstating"
provides:
  - "docs/architecture/LAYOUT_WIDTH_CONTRACT.md — the declared content-width standard, its peer evidence, its exceptions and its coverage boundary"
  - "the phase's SINGLE docs/metrics.json regeneration, with the prose in three documents reconciled to it"
  - "a fix for a counter defect that made two required CI gates mutually unsatisfiable, with a fixture that reproduces it"
  - "three re-measured figures that came back different from the numbers this phase had been repeating"
affects: [35-12, 35-13]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "no-file:line-citations-in-a-standing-doc: line numbers drift, and a citation that drifts is worse than none"
    - "comment-masked-to-whitespace: a masked ELEMENT is content, a masked COMMENT is not — conflating them invented array rows"
    - "re-measure-before-repeating: every inherited figure this document would have quoted was measured first, and three were wrong"

key-files:
  created:
    - docs/architecture/LAYOUT_WIDTH_CONTRACT.md
    - scripts/fixtures/test-block-counter/commented-each-table.fixture.ts
  modified:
    - docs/DOCUMENTATION_INDEX.md
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - scripts/count-test-blocks.mjs
    - scripts/check-test-block-counter.sh
    - .planning/phases/35-horizontal-layout-contract-tiered-content-widths-across-dash/deferred-items.md
    - .planning/STATE.md

key-decisions:
  - "The document carries NO file:line citations by design, and says so — this phase's own planning line references had already drifted by the time it was written"
  - "No route count is quoted in the document at all: the tree's repeated '21 dashboard routes' measured as 18, and quoting an inherited number in the document that exists to stop that is self-defeating"
  - "The counter was FIXED rather than the test file edited around it — the bug is in a shared gate and would have bitten the next person to comment a table"
  - "The STATE.md plan-counter basis was reconciled to disk rather than advanced within a basis that would have emitted a false 100%"

patterns-established:
  - "An arm that does not apply must say so and stop: the first ARM A silently no-op'd on a wrong sed pattern and the content guard caught it before rc=0 could be recorded as a pass"
  - "Blast radius of a shared-gate fix is measured across every consumer BEFORE the change lands, not asserted after"

requirements-completed: [UIX-07]
requirements-progressed: [UIX-09]

# Metrics
duration: 80min
completed: 2026-08-29
---

# Phase 35 Plan 11: The Contract Document + the Phase's Single Metrics Regeneration Summary

**This repository now states its content-width standard, which it never has — and regenerating the
test-count manifest surfaced a counter defect that had made two required CI gates mutually
unsatisfiable, so no value of `docs/metrics.json` could have merged.**

## Performance

- **Duration:** ~80 min
- **Tasks:** 3 of 3
- **Files:** 10 (2 created, 8 modified)
- **Commits:** 4 implementation + 1 for this SUMMARY
- **Branch:** `feature/35-horizontal-layout-contract`, 65 ahead / **0 behind** `origin/main`

---

## (i) The contract document

`docs/architecture/LAYOUT_WIDTH_CONTRACT.md`, 404 lines, indexed in `docs/DOCUMENTATION_INDEX.md`
under Architecture & Decisions. It closes the finding the phase was opened on — CONTEXT.md §2's
*"no document in this repo states a content-width standard; the 1400px is inherited, not chosen"*.

It carries: the four tiers with the peer measurement behind **each** number; the convergent-cluster
finding (three shells within 40px at 1680–1720, which is ~88% of 1920 and two thirds of 2560 — the
owner's instinct reproducing the measured ceiling); the substitute-surface caveat so Square and
Toast are not later cited as closest-domain evidence; the Index tier's absence-as-a-rule with the
delivery-log exception stated so it is not "fixed" back; **the ceiling rule**; every exception and
N/A with its reason; the displaced-goods record; the two application shapes; the `lib/` vs
`components/` directory rule; the coverage boundary; and a pointer to the manifest and the gate.

It **quotes no test count**, as the plan requires, and it carries **no `file:line` citation** — a
deliberate choice recorded in the document itself, and vindicated in passing: ORCH-06's note in
`layout-tiers.tsv` cites the policy page's prose measures at lines 124/151/156, which are now
158/185/190.

### Three figures re-measured rather than copied — all three came back different

The document's own thesis is that a number without a measurement behind it is the defect. So every
inherited figure it would have quoted was measured first:

| Claim, as inherited | Measured | What was done |
|---|---|---|
| "all **21** authenticated routes" — repeated in 9 places incl. the manifest, `layout-widths.ts` and `dashboard-shell.tsx` | **18** `page.tsx` routes under `app/dashboard`, with no dashboard route group elsewhere | **No route count quoted at all**, with the reason written into the document. Logged to `deferred-items.md` |
| the `/legal` index is a "**five**-link" list (orchestrator) / "**six**-item" (`layout-tiers.tsv`) | **4** entries in `POLICY_DOCUMENTS` | Document says four, and notes that earlier notes said five and six |
| "all **five** index pages carry a modal `max-w-2xl`" (35-10) | **5 caps across 4 files** — `orders/page.tsx` carries two | Document says "five such caps across four index-tiered pages" |

Every measurement was run with a control. The route count used `git ls-files` with the same query
over `app/legal` returning **5**, the known number of legal routes, so the 18 is about the tree
rather than about the query.

### A correction to the orchestrator's coverage brief, measured

The brief describes the per-PR Marketing-tier assertions as covering `/`, `/for-operators`,
`/business-model-guide`, `/competitive`, `/shop` and the `/legal/*` routes across "both per-PR
specs". That conflates two different claims, and the document states them separately because the
difference is exactly the kind of blur this phase exists to remove:

```
rg -uu -n 'data-width-tier' e2e/public-layout.spec.ts e2e/public-a11y.spec.ts   matches=[]  rc=1
CONTROL  the same pattern over e2e/layout-width-contract.spec.ts                2 hits      rc=0
```

Neither per-PR spec references the tier attribute at all. So per-PR **band-width** assertions cover
exactly the four `@stack-free` routes (`/`, `/for-operators`, `/business-model-guide`,
`/legal/privacy`); `public-layout.spec.ts` and `public-a11y.spec.ts` do block a merge over the wider
route set but assert **no horizontal overflow and WCAG 2.1 AA**, not width. A wrong band width on
`/competitive` or `/shop` would not red per-PR, and the document says so.

### The accessibility boundary, measured rather than asserted

The plan requires the document to state that the per-PR accessibility gate cannot evaluate the
scroll-region rule. That claim was measured rather than repeated:

```
jsdom, a genuinely overflowing element:  MEASURED scrollWidth=0 clientWidth=0   rc=0
```

axe's `scrollable-region-focusable` compares those two dimensions, so it cannot fire in jsdom.
Additionally measured: **no dashboard surface uses `jest-axe` at all** (its four consumers are
public, legal and storefront components), and the browser-based per-PR a11y spec scans public routes
only. The document records that the structural affordance is asserted and blocking, while *whether
the region overflows at a given width* is checked by nothing that currently runs.

---

## (ii) The metrics regeneration — and the defect it exposed

### Before and after, every figure that moved

| Key | Committed before | **Shipped** | Δ |
|---|---|---|---|
| `jest_blocks` | 1394 | **1503** | +109 |
| `jest_files` | 140 | **141** | +1 |
| `playwright_blocks` | 122 | **127** | +5 |
| `playwright_specs` | 26 | **27** | +1 |
| `total_logical_invocations` | 3378 | **3492** | +114 |
| `java_test_methods` / `java_test_files` | 1730 / 275 | 1730 / 275 | 0 |
| `go_test_funcs` / `go_test_files` | 84 / 11 | 84 / 11 | 0 |
| `mcp_test_blocks` / `mcp_test_files` | 48 / 8 | 48 / 8 | 0 |
| `schema_version` | 64 | 64 | 0 |

**It went to 1504 first.** The script's first `--write` produced `jest_blocks: 1504` /
`total_logical_invocations: 3493`, which is what the orchestrator's reported deltas predicted. That
value was wrong, and finding out why is most of what this plan did — see (iii).

Prose reconciled in all three documents: `README.md` (the badge, the Jest line, the Playwright line
and the total), `CLAUDE.md` and `AGENTS.md` (the Testing constraint sentence). A word-level diff
confirms **only numbers moved** — no other token in any of the three changed.

### The deltas were reconciled, not trusted

Measured per file with the repo's own counter against the merge base, never by arithmetic:

```
whole-branch Jest delta                                        +170 blocks, +4 files
  already committed by 35-01 (layout-widths 16 + css 11)        -27, -2 files
  already committed by 35-02 (content-tier 19 + shell +14)      -33, -1 file
  => remaining, which is what this regeneration owed             +110, +1 file
```

`+110` is exactly the drift `docs-freshness.sh` reported. Attribution, per file:

| Plan | Blocks | Files it moved |
|---|---|---|
| 35-03 / 35-04 | +28 | dashboard home, orders, products, staff, kitchen, webhooks ×2, ReviewQueue, approvals, marketing-status-filter-honesty |
| 35-05 | +39 | onboarding +19, the new `detail-tier.test.tsx` +20 |
| 35-06 | +17 | landing +7, public-header +5, public-footer-legal +5 |
| 35-07 | +20 | operator-pitch +4, business-model-guide +5, competitive-teardown +5, shop-discovery-client +6 |
| ORCH-06 | +6 | `policy-page.a11y.test.tsx` |

Playwright: **35-08 +4** (the new `layout-width-contract.spec.ts`) and **35-09 +1**
(`landing-webperf.spec.ts` 4 → 5), giving 122 + 5 = 127 and 26 + 1 = 27. Both match the plans' own
reports.

**The counter was shown able to report NO change**, which is what makes the deltas evidence about
the files rather than about the instrument: `public-layout.spec.ts`, untouched by this phase,
reported **8 → 8**.

The final `+109` rather than `+110` is the counter fix in (iii) removing one phantom block from
35-01's file.

---

## (iii) THE BLOCKER THIS PLAN FOUND AND FIXED

**Two required CI gates were mutually unsatisfiable on this tree. No value of `docs/metrics.json`
could have merged.**

`docs-freshness.sh` asserts `jest_blocks` against the static counter; `check-test-count-oracle.sh`
asserts the same key against jest's own `numTotalTests`. After the regeneration:

```
check-test-count-oracle [jest]
  jest it/test blocks                runner=1503   manifest=1504
FAIL: the runner says 1503, docs/metrics.json says 1504.          rc=1
```

This is the deadlock issue **#582** describes, reached from the **over**-counting side rather than
the under-counting one it was written for.

### Located per file, not guessed

A jest JSON report was compared against the static counter for all 141 files:

```
runner=15  static=16   frontend/lib/__tests__/layout-widths.test.ts     <- the only disagreement
static file count: 141   runner file count: 141                          <- so it is not a file one side cannot see
```

### The cause, confirmed by measurement before anything was changed

That file's `it.each(PEERS)` table has **three** rows, a **trailing comma**, and a **line comment
after each row**. In a scratch copy, removing only the trailing comma:

```
AS COMMITTED (trailing comma present):  {"blocks":16,"files":1}
WITHOUT the trailing comma:             {"blocks":15,"files":1}
```

`countArrayElements` already handles trailing commas (`lastWasComma ? commas : commas + 1`). The
defect is upstream of it: **comments were masked to `FILL`**, and `FILL` is deliberately
non-whitespace so that a masked *element* still reads as content when counting rows. That is right
for a string-literal row and wrong for a comment, which is never an element — so a comment sitting
after the trailing comma made that comma read as a **separator** rather than a **terminator**, and
the table gained a phantom row.

### The fix, and its blast radius measured BEFORE it landed

Comments are now masked to whitespace (`blankComment`); string, template and regex literals still
use `FILL`. Patched and unpatched counters were run per file over every counted file in all three
families:

```
jest        exactly ONE file moves: layout-widths.test.ts 16 -> 15;  total 1504 -> 1503
playwright  NO per-file change;  total 127 -> 127
vitest      NO per-file change;  total  48 ->  48
```

### Guarded by a fixture, with its fail direction recorded

`scripts/fixtures/test-block-counter/commented-each-table.fixture.ts`. **`each-tables.fixture.ts`
already covered a trailing comma, and is full of comments — but never the two on the same row**,
which is why both of its arms passed while every commented table over-counted. The new fixture
carries four tables: line-commented with a trailing comma (3), line-commented **without** one (2 —
the control that isolates the defect to the combination), an inline table (2), and a
block-commented one (2), plus a plain block.

```
UNPATCHED counter over the new fixture:  {"blocks":13,"files":1}
PATCHED   counter over the new fixture:  {"blocks":10,"files":1}
```

Wired into the counter's own harness as two arms (jest + vitest):

```
scripts/check-test-block-counter.sh   FIXED counter    20 arms, all ok           rc=0
                                      PRE-FIX counter  ARM FAIL count[jest]   got 13, expected 10
                                                       ARM FAIL count[vitest] got 13, expected 10
                                                       the other 18 arms HOLD   rc=1
```

The 18 pre-existing arms passing under both counters is what shows the change is targeted rather
than a rewrite, and the two new arms failing only under the old counter is what makes them
load-bearing.

### All three runners now agree with the manifest

```
check-test-count-oracle [jest]        runner=1503  manifest=1503 · files 141/141   rc=0
check-test-count-oracle [playwright]  runner=127   manifest=127  · specs 27/27     rc=0
check-test-count-oracle [vitest]      runner=48    manifest=48   · files 8/8       rc=0
```

---

## Verification — every arm, both directions, real output

Tasks 1 and 2 were **committed before any arm ran**, so no restore could eat a fix. Every restore is
verified by **content and by blob identity**, never by `git diff --stat`.

### ARM A — a prose figure THIS PHASE MOVED, set wrong

Run twice: once against the interim figures, and again against the **shipped** ones, so the recorded
fail direction describes what actually ships.

```
BROKEN   CLAUDE.md `+ 1503 Jest` -> `+ 1502 Jest`
  FAIL: CLAUDE.md [jest_blocks]: doc says 1502, docs/metrics.json says 1503     rc=1
  — ONE FAIL only; the other 36 claims stayed green, so the arm is isolated
RESTORED by content (`+ 1503 Jest`) and by blob identity (git diff --quiet rc=0)
  PASS: all 37 prose metric claim(s) across 3 doc(s) match                      rc=0
```

The gate names **the document and the claim key**, which is what makes it actionable.

**The first attempt at this arm DID NOT APPLY, and that is recorded rather than smoothed over.**
The `sed` pattern omitted the word "Playwright" from the middle of the sentence, so nothing changed
— and `check-doc-metrics.sh` returned **rc=0**. Reporting that as "the arm passed" would have been a
textbook vacuous result. The content guard printed the unchanged line, the arm was re-run with a
verified pattern, and a `refusing to report a result` guard was used on every arm afterwards.

### ARM B — the manifest hand-edited, restored by the SCRIPT

```
BROKEN   docs/metrics.json "jest_blocks": 1503 -> 1502
  ERROR: documentation metrics are stale (docs/metrics.json != source reality).
    committed "jest_blocks": 1502   /   computed "jest_blocks": 1503            rc=1
  and the OTHER half fired too, in the OPPOSITE direction:
    FAIL: README.md  [playwright_specs]: doc says 27, docs/metrics.json says 26
    FAIL: CLAUDE.md  [playwright_specs]: doc says 27, docs/metrics.json says 26
    FAIL: AGENTS.md  [playwright_specs]: doc says 27, docs/metrics.json says 26  rc=1
RESTORED by re-running `scripts/docs-freshness.sh --write` — never by editing the number back
  blob identity vs HEAD rc=0 · both gates rc=0
```

The inverted direction between the two gates is the clearest available demonstration that they are
genuinely different comparisons rather than one check run twice.

### ARM C — recorded as NOT APPLICABLE, and then made stronger anyway

The plan allows "the document carries no citations by design" as a real result. It does, and that
was **measured with a control** rather than asserted:

```
C-1  citations in LAYOUT_WIDTH_CONTRACT.md      matches=[]   rc=1
     CONTROL: same pattern over k8s/LOCAL.md    21 hits      rc=0
C-2  times the default gate run names the doc   0            <- it is NOT in the default scan set
```

Reporting a not-run arm as a pass is the failure this discipline exists to prevent, so the gate was
additionally **shown able to red on this exact document**, by temporarily giving it one citation:

```
C-3a CORRECT citation, `SHELL_MAX_PX` at layout-widths.ts:65
     PASS: all 1 verified citation(s) ... (0 uncheckable)                        rc=0
C-3b the SAME claim pointed at line 1
     FAIL: C-3 docs/architecture/LAYOUT_WIDTH_CONTRACT.md:406 cites
           frontend/lib/layout-widths.ts:1, but that line says nothing the claim names
     violations 1                                                                rc=1
RESTORED residue of the arm: '' rc=1 · blob identity rc=0 · default run rc=0
```

C-3a is the control that matters: without it, C-3b's red would be consistent with the gate simply
rejecting anything in this file.

### Closing clean run

```
scripts/docs-freshness.sh              rc=0  metrics match source (total logical invocations: 3492)
scripts/check-doc-metrics.sh           rc=0  all 37 prose metric claim(s) across 3 doc(s) match
scripts/check-doc-citations.sh         rc=0  73 verified citation(s) across 8 doc(s) (7 uncheckable)
scripts/check-claims.sh                rc=0  all 47 claim(s) across 6 doc(s) match
scripts/check-test-block-counter.sh    rc=0  20 arms, refusals included
scripts/check-layout-width-contract.sh rc=0  104 checked claim(s)
scripts/check-gate-enforcement.sh      rc=0  every gate runs in CI or has a declared reason
scripts/check-branch-behind-base.sh    rc=0  65 ahead / 0 behind origin/main
check-test-count-oracle jest/pw/vitest rc=0  1503/141 · 127/27 · 48/8

git status --short  (empty)          git diff --quiet HEAD  rc=0
```

The closing clean arm is the only proof the restores landed.

---

## Deviations from Plan

### 1. [Rule 3 — Blocking] The counter defect: two required gates left mutually unsatisfiable

Full account in (iii). The plan's Task 2 ends at "both halves of the loop are green"; both halves
**were** green at `jest_blocks: 1504`, and a third required gate was red on the same key. Fixing the
counter is outside this plan's declared file set, and it was done anyway because:

- it **blocks this task** — the plan cannot deliver a mergeable manifest without it;
- the blast radius was **measured at exactly one block** before the change landed;
- the alternative — editing 35-01's test file to dodge the bug — would have left a live defect in a
  shared gate for the next person who comments a table, which prettier will happily produce.

Files outside the declared set: `scripts/count-test-blocks.mjs`,
`scripts/check-test-block-counter.sh`, and the new fixture. Commit `955bbfe8`.

### 2. [Rule 1 — Bug] The plan's own predicted spec count was right; its jest figure was not

The plan states *"one Playwright spec — taking the count from 26 to 27"* and that is exactly what
shipped. It does not predict a jest figure, but the orchestrator's brief and every plan's reported
delta summed to 1504. The runner says 1503. The arithmetic was not wrong — the instrument was.

### 3. [Falsifiability — self-caught] ARM A's first run did not apply

Recorded in full under Verification. A wrong `sed` pattern produced a silent no-op and a green gate.
Caught by the break-confirmed-by-content guard, not by the result looking odd.

### 4. [Rule 2 — an unmeasured figure the document would have propagated] The route count

Measured 18 against a repeated 21. Not fixed at the nine sites that assert it — four are dated
planning artefacts, one is a test file — and logged to `deferred-items.md` (commit `a5f82792`). The
document quotes no route count at all, which needs no maintenance in either direction.

### 5. [Recorded, NOT fixed — out of scope] `check-e2e-skip-budget.sh` is still rc=2 VOID

Unchanged from 35-08 and 35-10. Its stored report predates this branch's spec changes; it is failing
**closed**, and a fresh full-suite report clears it. That run is **35-12's**. Not touched, not
papered over.

### 6. [Deviation from the phase convention, deliberate] `STATE.md` updated

Every wave-3 plan left `STATE.md` alone because five agents shared one checkout. That reason has
expired — this plan ran alone. It was **hand-edited**, per the warning in `STATE.md` itself that
`state.record-session` and `state.begin-phase` both corrupt it. See "Planning state" below for the
counter-basis decision, which is not a routine increment.

---

## Displaced goods

**None.** No product source is touched — no component, page, style, endpoint or dependency. The
changes are one new document, one index row, a regenerated manifest, three prose figures, and a
counter fix whose measured effect on every consumer is one phantom block removed.

The one good that could have been displaced is a green build on lines this plan did not cause, and
that was the primary risk the counter fix was designed around: the blast radius was measured across
all three families **before** the change landed, and the 18 pre-existing harness arms were confirmed
to hold under both counters.

---

## Coverage boundaries, stated rather than implied

- **The document is not gated by `check-doc-citations.sh`.** It is not in that gate's default doc
  set (measured: 0 mentions in a default run). It carries no citations, so there is nothing to
  drift — but if a later editor adds one, **nothing will check it** unless the doc is added to
  `DEFAULT_DOCS`. Recorded because an unstated boundary reads as covered.
- **The document is not gated by `check-claims.sh` either** — that gate reads a declared manifest of
  6 documents and this is not one. Its factual claims are guarded by nothing automated; they are
  guarded by having been measured, and by the three corrections recorded above.
- **`docs/metrics.json` is now asserted from three sides** — source tree, prose, and all three test
  runners — and this plan ran all three.
- **No runtime parity is claimed.** No container was rebuilt and no running stack was exercised.
  `check-runtime-freshness.sh` was **not** run here; the frontend image is recorded as stale by
  35-08 and is owed to 35-12/35-13.
- **The full Jest suite WAS run**, as a by-product of the oracle: 141 suites, 1503 tests, rc=0. The
  consolidated build was **not** re-run, and did not need to be — no file this plan touched reaches
  the frontend build.

---

## Cross-Cutting Quality Contracts

- **Web performance: N/A.** No user-facing page, component, image, dependency or bundle is touched.
  Zero bytes reach the browser from this plan.
- **SEO: N/A.** No metadata, structured data, canonical, robots directive, sitemap entry or
  crawlable link changed, and no width changed on any indexed route.
- **AI agent-readiness: N/A.** No API surface, endpoint, credential, error contract or OpenAPI
  document changed. No MCP tool is warranted for a document and a manifest.
- **Security:** threat register below. ASVS L2 — no category applies; this plan changes
  documentation, a generated manifest and a build-time counter, touching no endpoint, input,
  credential or data flow.
- **Falsifiable evidence + runtime parity:** three arms with recorded output in both directions, one
  of them re-run against the shipped figures; one arm caught not applying and re-run rather than
  reported; a blast radius measured before a shared-gate change rather than after; and three
  inherited figures re-measured, all three wrong. Runtime parity is **not** claimed and is named as
  owed to 35-12/35-13.

---

## Threat model outcomes

- **T-35-41** (the source half green while the prose half is months stale) — **mitigated, and shown
  live.** Regenerating the manifest turned `check-doc-metrics.sh` from green to **16 named FAILs**
  across all three documents, which is the staleness existing rather than being hypothesised. ARM A
  then proved the prose half fires on a figure this phase actually moved.
- **T-35-42** (a hand-edited manifest) — **mitigated.** Every value written by
  `scripts/docs-freshness.sh --write`; ARM B restored by re-running the script, never by editing the
  number back. And the threat proved sharper than written: an arithmetic sum of the reported deltas
  would have given 1504, which is wrong.
- **T-35-43** (a drifting citation) — **mitigated by construction** (no citations) and the arm
  recorded as not-applicable **with the gate additionally shown able to red on this document**. The
  risk is not theoretical here: this phase's own line references had already drifted.
- **T-35-44** (a document that outlives its truth) — **mitigated.** It points at the manifest, the
  gate, the constants module and the vocabulary, so a reader can see what enforces it; and the one
  figure most likely to rot — a route count — was removed rather than recorded.
- **T-35-44b** (an OVERSTATED coverage claim) — **mitigated, and tightened.** The document uses
  CONTEXT §5's wording verbatim, cites #683 and #686, and additionally corrects the brief's own blur
  between the width spec and the layout/a11y specs, measured with a control.
- **T-35-SC** — nothing was installed, so the Package Legitimacy Gate correctly did not run rather
  than being skipped.

---

## Known Stubs

None. No placeholder, empty state, mock data source, TODO or FIXME ships in this plan.

## Threat Flags

None. No endpoint, input, credential, data flow or dependency is added.

---

## Planning state

`STATE.md` was hand-edited, per its own recorded warning that `state.record-session` and
`state.begin-phase` corrupt it.

**The plan counters were reconciled to disk rather than incremented, and that is a judgement call
worth reading.** The previous basis was `total_plans: 105 / completed_plans: 104`. Advancing it by
one would have printed **100%** while plans 35-12 and 35-13 remain unwritten — a false statement
produced by a basis the file's own note already flags as disagreeing with disk. Measured with
`git ls-files`: **119** `*-PLAN.md` and **117** `*-SUMMARY.md` (including this one). Those are the
numbers now in the file, with the provenance recorded beside them. `ROADMAP.md` and
`REQUIREMENTS.md` were left alone: `roadmap.update-plan-progress` has a recorded cell-corruption
defect, and UIX-09 is shared with three other plans, so a partial edit would be wrong.

**Requirement text, truthful as of this plan:**

- **UIX-07: COMPLETE.** Its four limbs were 35-01 (the declared contract), 35-02 (the vocabulary),
  35-10 (the gate) and 35-11 (the documented standard). The last one now exists. 35-10's summary
  named this plan as the outstanding limb; it is discharged.
- **UIX-09: in progress, not complete.** 35-05 (Detail), 35-06 (Marketing on `/`), 35-07 (the
  remaining marketing surfaces) and 35-09 (the CLS measurement) have landed. The requirement's
  remaining evidence is the owner-facing verification at **35-13** and the runtime parity close at
  **35-12**. Not this plan's to complete.

## Cited decisions

**ORCH-01, ORCH-03 and ORCH-06 (orchestrator decisions, 2026-08-29)** are cited by that name in the
contract document — never as user decisions. ORCH-01 is recorded as owner-visible-if-wrong at the
35-13 gate; ORCH-03 is why the Index tier declares itself despite applying no class; ORCH-06 is why
the four policy routes take the Marketing band while the prose inside stays at 68ch. ORCH-02, ORCH-04
and ORCH-05 are referenced through the surfaces they produced rather than by name.

## Commits

| Commit | Type | Subject |
|---|---|---|
| `23262a93` | docs | write down the width standard whose absence was the finding |
| `6c6aa4e5` | docs | regenerate the test-count manifest for the whole phase, and the prose that quotes it |
| `955bbfe8` | fix | stop the block counter inventing a row when a table's trailing comma is followed by a comment |
| `a5f82792` | docs | record the 21-vs-18 dashboard route count as a deferred finding |

Every message was written through a **quoted heredoc** and passed with `git commit -F`, never an
interpolating `-m` string, and read back with `git log -1 --format=%B`. Backticks inside a
double-quoted message execute and are silently dropped, and the corruption is invisible at write
time. Every commit staged **named paths only** — never `git add .` or `-A` — and each was checked
afterwards with `git diff --diff-filter=D HEAD~1 HEAD`: **zero deletions** on all four.

## TDD Gate Compliance

Not a `type: tdd` plan, and correctly so: the deliverables are a document and a generated manifest,
and there is no behaviour for a test to drive. The one code change (the counter fix) nevertheless
followed the RED/GREEN shape properly — the fixture was written and **observed failing against the
unpatched counter (13 vs 10)** before the fix was trusted, and it ships as a permanent arm in the
counter's own harness.

## Self-Check: PASSED

All 11 claimed files exist on disk and all 4 claimed commit shas resolve. Both halves were run with
a **control in the failing direction**, so a FOUND result is a statement about this repository
rather than about a check incapable of failing: `docs/does-not-exist-CONTROL.md` correctly reported
MISSING, and `deadbee` correctly reported MISSING.

The shipped figures were re-read out of the files themselves rather than from this summary's own
tables:

```
docs/metrics.json   jest_blocks 1503 · playwright_specs 27 · total_logical_invocations 3492
CLAUDE.md:15        standard is 3492 logical invocations
AGENTS.md:15        standard is 3492 logical invocations
README.md:267       Total: 3492 logical test invocations
```
