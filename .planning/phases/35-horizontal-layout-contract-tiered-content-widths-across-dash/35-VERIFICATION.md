---
phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
verified: 2026-08-29T23:58:12Z
status: passed
score: 5/5 roadmap goal-and-constraint criteria verified; 3/3 requirements substantively complete (2 unconditional, 1 with a recorded standing coverage limitation attributable to #683)
overrides_applied: 0
re_verification: No — initial verification
---

# Phase 35: Horizontal Layout Contract — Verification Report

**Phase Goal:** Replace the inherited, undeclared shadcn `container` default (1400px, applied
uniformly to four different content types) with a **declared four-tier width contract**, so every
surface uses horizontal real estate appropriate to what it holds.
**Branch:** `feature/35-horizontal-layout-contract` @ `bcb1d011` (13/13 plans executed)
**Verified:** 2026-08-29T23:58:12Z
**Status:** passed
**Method:** Goal-backward, adversarial. Every gate, spec and probe cited below was **executed by
the verifier in this session against the live tree and the delivered runtime** unless marked
EVIDENCED, in which case the plan SUMMARY's quoted, dated, falsified-both-directions evidence is
assessed rather than re-run — per the phase's own instruction not to rebuild the merge base or
re-run the full E2E suite a second time within one verification pass.

> **A note on the criteria themselves.** Unlike Phases 33 and 34, **Phase 35's ROADMAP section
> carries no numbered "Success Criteria" block** — verified by scanning lines 722–771 (`rg` returns
> rc=1, no match) with a positive control on Phase 33's section (1 match), so the absence is about
> the document and not about the search. The phase is therefore scored against what the ROADMAP
> *does* state: its **Goal** and its four **"Constraints that are not negotiable"**.

## Goal Achievement — Roadmap Goal and Non-Negotiable Constraints

| # | Criterion (verbatim, abbreviated) | Requirement | Status | Evidence |
|---|---|---|---|---|
| 1 | **GOAL** — the inherited undeclared 1400px `container` is replaced by a declared four-tier contract, each surface using width appropriate to its content | UIX-07/08/09 | ✓ VERIFIED | Read **out of the running container's served stylesheet** by the verifier: `.max-w-shell{max-width:1700px}` ×1, `.max-w-detail{max-width:1100px}` ×1, `.max-w-marketing{max-width:1280px}` ×1, and `.container{` **×0** / `max-width:1400px` **×0**. Controls make those zeros evidence about the stylesheet: `max-width:` occurs 24×, and the absent-probe `max-width:9999px` returns 0. Chunk `3otvctlq2qoxs.css` (96,685 B) is the content-hashed name 35-12 recorded as the delivered artefact — identity, not resemblance. Rendered bands measured live at 1440/1920/2560: Shell 1184/1664/1700, Index 1120/1600/1636, Detail 1100/1100/1100, Marketing 1280/1280/1280 |
| 2 | **CONSTRAINT** — widths declared in **one config module**, never scattered literals | UIX-07 | ✓ VERIFIED | `frontend/lib/layout-widths.ts` holds `SHELL_MAX_PX = 1700`, `DETAIL_MAX_PX = 1100`, `MARKETING_MAX_PX = 1280`. `scripts/check-layout-width-contract.sh` re-run live: **PASS rc=0 — 104 claims across 7 assertions (G-1..G-7)**, over a 43-row manifest, scanning 338 source files for stray tier literals and 151 shipped files for declarations, finding 27 declaring files. This gate runs in `ops-contracts` in `ci-cd.yaml` and blocks **every** PR |
| 3 | **CONSTRAINT** — `/` carries known CLS debt 0.1793; any landing change measured against that baseline as a control, never assumed neutral | UIX-09 | ✓ VERIFIED | `e2e/landing-webperf.spec.ts` re-run live by the verifier, both projects: **9 passed, rc=0**, including the ORCH-02 arm *"holds desktop CLS no worse than its measured pre-change control"*. EVIDENCED for the figures: 35-12 read `LCP=800ms CLS=0.0362` out of the delivered container at 1440×900 with 4× CPU throttling, against control **0.1316** — a 72% fall. Mobile stays at the pre-existing **0.1793**, unchanged, which is the designed outcome. The two viewports are never conflated |
| 4 | **CONSTRAINT** — Incremental Betterment: each displaced good enumerated and preserved or bettered; **mobile-first behaviour must not regress** (this is a large-screen change only) | UIX-09 | ✓ VERIFIED / EVIDENCED | VERIFIED: the phase's 11-row displaced-goods ledger exists in `35-12-SUMMARY.md` §vii with a measurement per row. Prose measure re-counted live by the verifier — **8 occurrences of `max-w-[68ch]` across 2 files** (matching 35-12's 6→8, i.e. ORCH-06 *extended* the clamp rather than displacing it), with an absent-probe control (`max-w-[99ch]` → 0). Ceiling-not-target confirmed at source: `/track` keeps `mx-auto max-w-lg` (512px) at `frontend/app/track/page.tsx:249` and is declared **N/A** in the tier manifest — an examined exception the static gate knows about, not a silent omission. EVIDENCED: the mobile claim rests on 35-12's PostCSS-AST-partitioned pre/post stylesheet diff, which reduces below `lg` to exactly the 3 tier additions plus the retired `.container` and its `!important` variant, **with the filter armed in both directions** (ARM F1 a sub-`lg` rule must surface; ARM F2 an above-`lg` rule must be dropped and relocated), corroborated in a real browser at 390px where width/padding/margins/overflow are identical on both builds |
| 5 | **CONSTRAINT** — the widened index tier must not break the `overflow-x-auto` keyboard-focusable region (A11Y-3, #685), nor reintroduce body horizontal scroll at 390px | UIX-08 | ✓ VERIFIED / EVIDENCED | VERIFIED: document horizontal overflow measured **0** at 1440, 1920 and 2560 on every surface the verifier probed. EVIDENCED: 35-12 measured the products scroll region in a real browser on **both** builds at five viewports — `role="region"`, `aria-label="Products table, scroll horizontally for more columns"` and `tabindex="0"` identical throughout — and established the **direction of risk by measurement**: the region's client width grows 1286→1550 (@1920) and 1286→1586 (@2560) against unchanged content, so overflow becomes strictly *less* likely. Below `lg` the numbers are byte-identical. The honest limit is stated rather than papered over: neither the jsdom gate nor the report-only browser sweep can evaluate `scrollable-region-focusable` on this seed, which is **#689** |

**Score:** 5/5. Criteria 1, 2 and 3 were fully re-executed by the verifier; criteria 4 and 5 were
partly re-executed (prose count, source-level ceiling exception, live overflow) and partly assessed
from 35-12's dated, quoted, both-directions evidence, per the task's explicit instruction not to
rebuild the merge base or re-run the full suite in this pass.

## The Owner Gate — the criterion the phase was actually opened against

Phase 35 began from owner feedback, not from an audit: *"not enough of the real estate is being
utilised… it feels narrow and confined to the middle."* A green suite cannot answer that, which is
why 35-13 is a blocking human checkpoint (threat **T-35-50**).

**The gate was taken on 2026-08-30 and is recorded in `CONTEXT.md` §7.** These are **owner**
decisions, unlike ORCH-01..06 which were taken by the orchestrator under delegated autonomy — a
distinction this phase corrected mid-flight and which is preserved here.

| Question | Owner decision |
|---|---|
| The Detail tier narrows three live surfaces | **Accepted 1100px** — the displaced-goods ledger is the accepted cost, not an oversight |
| ORCH-01: public `/shop` stays Marketing 1280 rather than fluid | **Confirmed 1280px** |
| Close-out | **Run 35-13, then open a PR**; merge left to the owner after CI |

### The must-not-move claim, scoped and measured (threat T-35-52)

An earlier draft told the owner *"if anything moved at 1440, that is a defect"* — which would have
reported a **correct** build as defective and invited the repair of undoing the tier. The corrected,
scoped claim is measured here by the verifier:

| viewport | `main` content | Shell | Index | Detail | doc overflow |
|---|---|---|---|---|---|
| **1440** | 1184 | **1184 (unmoved)** | **1120 (unmoved)** | **1100** | 0 |
| 1920 | 1664 | 1664 | 1600 | 1100 | 0 |
| 2560 | 2304 | 1700 | 1636 | 1100 | 0 |

**Shell and Index are unchanged at 1440 without needing a second build.** The pre-change cap was
**1400** (35-12 read it twice out of the merge-base build's served stylesheet); `main` offers
**1184**, measured. Band = `min(parent content, cap)`, so `min(1184, 1400) = min(1184, 1700) = 1184`
— **a cap that does not bind cannot move the band.** 35-12 independently measured the same on real
builds of both trees.

**Detail moved, by design, by the magnitude the owner accepted:** −20px at 1440 (1120→1100) and
−236px at 1920 and 2560 (1336→1100).

**Scope was proven, not assumed.** A document-wide `[data-width-tier]` query is satisfied by the
header and footer rails; on `/shop` the verifier measured **docCount 5 vs main-scoped 1**. Every
figure above is `main`-scoped.

### A documentation discrepancy, named rather than smoothed over

`CONTEXT.md` §7 states the Detail narrowing as *"1600→1100 at 1920/2560"*; the **plan** states
*"roughly 1336 to 1100"*. Both describe different comparisons and only one answers the owner's
question. **1336→1100** is actual-before vs actual-after — and it is the figure the owner was shown
in the plan and accepted. **1600→1100** is the *counterfactual* (post-change untiered vs tiered).
§7 therefore **overstates the narrowing by 264px**. The decision is unaffected; the record should
not carry a figure that reads as a measurement and is not one. The 1336 is not inferred — it is
directly visible in `35-12-prechange-orders-2560.png`, where the band runs ~1334px **centred with
~485px of empty gutter on both sides**, which is the owner's *"confined to the middle"* made visual.

### Step 4 — the visual defect class no assertion in this phase looks for

RESEARCH names it: *a centred narrow layout that read as deliberate becomes a stretched band with a
lonely heading — green tests, worse product.* The verifier read the captures directly and then
**measured every candidate**, because a symptom read off a capture scaled 2560→2000 is a hypothesis.

- **`/dashboard/orders` reads better.** The six-column table goes from a 1334px band centred in
  ~970px of gutter to 1636px. The band **stays centred with symmetric gutters** — content does not
  hug the left edge — and no heading is stranded.
- **`/dashboard/finance` "VAT Breakdown" (`finance/page.tsx:263`) → EXAMINED N/A.** Looks like the
  textbook symptom; is not. The element is `grid gap-3 sm:grid-cols-2 lg:grid-cols-4` and declares
  **4 columns at every desktop viewport**; the cell is exactly a quarter of the content box
  (`(1586 − 3×12)/4 = 387.5`, matching computed `grid-template-columns` to the half-pixel). It fills
  the band, and fills it *better* after the change (258.5 → 387.5). **The emptiness is the seed
  holding one VAT rate category.**
- **`/dashboard/orders` "Order Status Flow" (`orders/page.tsx:571`) → EXAMINED N/A, residual named.**
  Ink width is **constant at 888px** at all three viewports (`flex flex-wrap` over six content-sized
  pills), so trailing space grew ~398px → ~698px: **amplified by ~300px, not introduced.** Still
  N/A deliberately — a stepper over a closed six-value set is not a data surface, and stretching it
  across 1586px would read worse. This is what **ceiling-not-target** is for, the same rule that
  leaves `/track` at 512px.

**Neither is the ORCH-04/ORCH-06 class** (*a content band inset from the chrome that wraps it*):
both cards span the full band and share their edges with the dashboard chrome. That class was real,
was fixed on `/` and the four `/legal/*` routes, and does not recur here. Both measurements are
recorded on **#690** so a future reader has a number to argue with rather than a silence.

### The two deliberately-unapplied additive candidates — DECIDED, not deferred

The plan is explicit that *"a decision that is not written down did not happen."*

- **The finance chart band: MOOT — the gate's own premise is false.** Step 3 told the owner
  `/dashboard/finance` *"has charts above its table"*. It renders **zero** charts at 1440/1920/2560
  and **imports no chart library at all**; the selector control found **2** charts on `/dashboard`
  in the same run, so the zero is about the page, not the probe. The planned fix has no subject.
  The real charts (Shell tier, `/dashboard`) were measured anyway: aspect ratio **1.99 → 2.95 →
  3.02**, bounded by the 1700px shell cap — an ordinary dashboard proportion.
- **The approvals queue tier: UNDECIDABLE, filed as #690.** `/dashboard/onboarding/approvals`
  renders *"No applications waiting"* — **0 rows, no table** at all three viewports — so nothing can
  hug an edge or be stranded and a green look would have been a **vacuous pass, not an approval**.
  Same root cause as **#686**: the demo tenant's onboarding is LIVE/terminal.

## Plan-Level Must-Haves (spot-verified against source and runtime, not SUMMARY prose)

| Plan | Must-have | Status | Evidence |
|---|---|---|---|
| 35-01 | Tier widths in one constants module; utilities generated from it; shadcn container plugin retired | ✓ VERIFIED | `lib/layout-widths.ts` exports the three constants; served stylesheet carries the three generated utilities and **zero** `.container{` rules |
| 35-02 | Tier vocabulary in the DOM; Shell applied at the single container call site | ✓ VERIFIED | Shell band measured 1184/1664/1700 on `/dashboard`; the width spec asserts exactly **one** shell band ("the tree's single width call site") and passes |
| 35-03 | Index tier on orders, products, overview, customers, shops | ✓ VERIFIED | Index band measured 1120/1600/1636 on `/dashboard/orders`; `max-width: none` asserted **by name** by the spec (ORCH-03's falsifiable form) |
| 35-04 | Index tier on the eight remaining surfaces; each ambiguity resolved at its site | ✓ VERIFIED | Manifest rows confirmed for `finance` and `onboarding/approvals`; the **A-8 low-confidence flag** was genuinely routed to this gate and is answered above (→ #690) |
| 35-05 | Detail tier on order detail, onboarding, import wizard; ceiling-not-target + exceptions ledger | ✓ VERIFIED | Detail measured **1100 at all three viewports** and asserted strictly narrower than the Shell band **in the same page evaluation**; `/track` exception confirmed at `track/page.tsx:249` and declared N/A in the manifest |
| 35-06 | Marketing tier on `/` and both public chrome rails | ✓ VERIFIED | 6 marketing tier attributes in the served `/` HTML (4 bands + 2 chrome rails), control *"Independent UK kitchens"* ×2, absent-probe 0; Marketing bands measured 1280 across 4 routes × 3 viewports |
| 35-07 | Marketing declared on already-1280 surfaces; shop detail skeleton aligned to its content | ✓ VERIFIED / EVIDENCED | `/shop` measured 1280 (main-scoped) at all three viewports. Skeleton parity EVIDENCED via 35-10's break arm, which reds the gate naming all three `/shop/[slug]` family members |
| 35-08 | Playwright width spec at 1440/1920/2560; stack-free half wired into the per-PR gate | ✓ VERIFIED | Spec re-run live by the verifier: **21/21 passed, rc=0** against the delivered runtime. Header documents the coverage split honestly and uses the authoritative wording |
| 35-09 | Desktop-viewport CLS arm for `/`, control measured by two-arm A/B | ✓ VERIFIED | `landing-webperf.spec.ts` re-run: 9 passed incl. the desktop CLS arm |
| 35-10 | Static contract gate + tier manifest + CI wiring in the same commit | ✓ VERIFIED | Gate re-run: **PASS, 104 claims / 7 assertions**, 43-row manifest; `check-gate-enforcement.sh` PASS (40 gates, 6 workflows, 6 declared exempt) — so it is wired, not orphaned |
| 35-11 | The contract document; metrics regenerated once for the phase | ✓ VERIFIED | `docs-freshness.sh` PASS rc=0 (total logical invocations **3492**); `check-doc-metrics.sh` PASS rc=0 (**37/37** claims across 3 docs); Jest re-run by the verifier at **141 suites / 1503 tests**, matching `docs/metrics.json` exactly |
| 35-12 | Runtime parity; mobile CSS diff; pre-change control arm; browser sweep | ✓ VERIFIED / EVIDENCED | VERIFIED: `check-runtime-freshness.sh` **4/4 FRESH, 0 unverified**; tier values read out of the served stylesheet; the delivered chunk name matches. The two comment-generated rules are gone (`sm\:max-w-lg` 0, `sm\:max-w-none` 0) with the applied control `sm\:max-w-md` still present — **26 − 2 = 24** reconciles arithmetically. EVIDENCED: the 21/21 RED merge-base arm and the AST-partitioned diff |
| 35-13 | Owner gate answered; every finding named by surface with a disposition | ✓ VERIFIED | `CONTEXT.md` §7; findings and dispositions above; **#690** filed and commented |

## Gate Sweep (executed live by the verifier, this session, from the MAIN checkout)

| Gate | Result |
|---|---|
| `scripts/check-layout-width-contract.sh` | PASS, rc=0 — 104 claims across 7 assertions, 27 declaring files |
| `scripts/check-runtime-freshness.sh` | PASS, rc=0 — **4/4 services FRESH, 0 unverified** |
| `scripts/check-branch-behind-base.sh` | PASS, rc=0 — **71 ahead, 0 behind** `origin/main` |
| `scripts/docs-freshness.sh` | PASS, rc=0 — total logical invocations **3492** |
| `scripts/check-doc-metrics.sh` | PASS, rc=0 — 37/37 claims across 3 docs |
| `scripts/check-gate-enforcement.sh` | PASS, rc=0 — 40 gates, 6 workflows, 6 declared exempt |
| `scripts/check-go-coverage.sh` | PASS, rc=0 — **67.0% ≥ 65.0%** (313 blocks) *after regenerating a stale profile* |
| `scripts/check-alert-liveness.sh` | PASS, rc=0 — 8 targets up, **blind=0**, 19 rules match live series *after an environment repair* |
| `scripts/check-handoff-contract.sh` | PASS, rc=0 — *after this phase's own stale gate-count was fixed* |
| `scripts/check-e2e-skip-budget.sh` | **FAIL, rc=1** — 323 total, **7 skipped (budget 6)**, 1 undeclared. **#686**, pre-existing |
| **Full sweep, `scripts/check-*.sh` + `docs-freshness.sh` (40+1 = 41)** | **40/41 PASS, 1 FAIL** (the #686 skip budget). **0 VOID** |
| `frontend` jest (full) | PASS, rc=0 — **141 suites / 1503 tests / 2 snapshots** |
| `e2e/layout-width-contract.spec.ts --project=desktop` | PASS, rc=0 — **21/21** against the delivered runtime |
| `e2e/landing-webperf.spec.ts` (both projects) | PASS, rc=0 — 9 passed incl. the ORCH-02 desktop CLS arm |
| `edge-go` `go test ./...` | PASS, rc=0 — all packages |

### Three first-sweep failures, each triaged to cause before being touched

| Gate | Cause | Class | Disposition |
|---|---|---|---|
| `check-handoff-contract` | 35-10 added the **41st** gate script (merge base 40 → HEAD 41); HANDOFF.md still said `EXPECT 40 x rc=0`. This gate runs in `docs-freshness.yml` on `pull_request` | **Caused by this phase** — the PR would have opened RED | **FIXED** `bcb1d011`, break-armed |
| `check-go-coverage` | Stale untracked `edge-go/coverage.out` (2026-08-29 02:20, 311 lines, 63.3%). **Zero Go files changed on this branch**, control: the same filter matches 58 frontend files. CI regenerates it in-job | Stale artefact — the classic "reading a stale artifact instead of the live one" | Regenerated → 67.0% / 313 |
| `check-alert-liveness` | `jtoye-redis-exporter` (up 35h) held a **12-char** `REDIS_PASSWORD` while `.env` carries a **48-char** one; redis restarted 2026-08-29T14:00Z with the new secret, the exporter never did. **Zero infra commits on this branch** | Local environment drift | Repaired by **compose recreate** |

The redis case is **Proof Standard #5** in the wild: the container reported `healthy` and its
Prometheus target reported `up=1` throughout, while the `redis_up` **gauge behind it** was 0 — a
structural check passing over a dead function, which is exactly the defect `L-1b` exists to catch.

## Fail-Direction Spot Checks (executed live by the verifier)

| Check | Break-arm result |
|---|---|
| `check-handoff-contract.sh` with the count claimed as 42 | `FAIL: H-1 … says 'EXPECT 42 x rc=0' but the repo has 41 gate script(s)`, **rc=1** — bracketed clean(rc=0) → arm(rc=1) → restore → clean(rc=0), restore verified by **blob identity** `137cbb1c…` on disk == at HEAD, never `git diff --stat`, with the file **committed before the arm ran** |
| `check-go-coverage.sh` on an absent profile | `VOID: coverage profile not found … An absent profile is not 0% coverage.`, **rc=2** |
| `check-go-coverage.sh` on a mode-line-only profile | `VOID: unparseable coverage profile … a broken or skipped test run, not a coverage regression.`, **rc=2** |
| Served-stylesheet absence probes | `.container{` 0 and `max-width:1400px` 0, against controls `max-width:` 24 and the absent-probe `max-width:9999px` 0 — so the zeros are about the stylesheet, not the search |
| Redis exporter, fresh container vs running one | A `docker restart` did **not** clear `redis_up 0`; a fresh `docker run` of the identical image with the identical `REDIS_ADDR` and the **current** `.env` password reported `redis_up 1`, empty scrape error — isolating stale state from a config defect. Redis answered `PONG` on that network with a negative control (`nosuchhost-35-13` → *"Could not connect"*) |
| Chart-selector control | The same selector that returns **0** on `/dashboard/finance` returns **2** on `/dashboard` — so the finance zero is a fact about the page |
| Prose-clamp control | `max-w-[68ch]` → 8 occurrences / 2 files; absent-probe `max-w-[99ch]` → 0 |
| Probe-cleanup control | Three temporary probes deleted; `tmp-3513` repo-wide → **0 files**, with a positive control (`THE HORIZONTAL LAYOUT CONTRACT` → 1 file) proving the search reaches `frontend/e2e/`; `git status --porcelain --untracked-files=all` → 0 paths |

One of the verifier's own arms was itself found vacuous and replaced rather than reported: the first
`check-go-coverage` fail-direction used `COVERAGE_PROFILE=`, which the script does not read, so it
"failed" by passing at rc=0. The real variable is `GO_COVERPROFILE`; both directions were re-run.

## Requirements Coverage

| Requirement | Description | Status | Evidence |
|---|---|---|---|
| UIX-07 | Declared four-tier layout contract; one constants module, no scattered literals | ✓ **SATISFIED** (unconditional) | Criteria 1 and 2. All four limbs landed: constants module (35-01), DOM vocabulary + single call site (35-02), the **enforcing static gate blocking every PR** (35-10, 104 claims / 7 assertions), the contract document closing the originating "no declared standard" finding (35-11). Values proven **in the delivered artefact** (35-12). Its per-PR enforcement is continuous — no coverage caveat applies |
| UIX-08 | Resource-index surfaces use available width; "uncapped" carries an explicit marker so the contract is falsifiable rather than an absence | ✓ **SATISFIED**, with a recorded standing limitation | Criteria 1 and 5. Measured 1120/1600/1636; computed `max-width` asserted to be the string `none` **by name** (ORCH-03's falsifiable form) and the band asserted equal to its parent's **content** box. Shown capable of failing: **21/21 RED** on a real merge-base build. The **marker** is gated per-PR statically (35-10 G-7). **Limitation:** the rendered-width browser assertion has no continuously executing lane (**#683**) |
| UIX-09 | Reading surfaces keep their measure (Detail ~1100, Marketing 1280, prose 68ch); a tier is a ceiling, not a target | ✓ **SATISFIED**, with the same recorded limitation | Criteria 1, 3 and 4, every limb re-measured by the verifier: Detail **1100 at all three viewports** and strictly narrower than the Shell band in the same page evaluation; Marketing **1280** across 4 routes × 3 viewports plus `/shop`; prose **8 × `max-w-[68ch]`** with an absent-probe control; ceiling-not-target confirmed at source (`track/page.tsx:249`, declared N/A in the manifest). **And the limb only a person could supply is now supplied:** the owner accepted 1100 as the right measure at the gate (`CONTEXT.md` §7), which is the judgement UIX-09 was waiting on. **Limitation:** the Detail arm has no continuously executing lane (**#683**) |

**On the recorded limitation.** #683 is a platform gap that predates this phase and outlives it: the
per-PR browser gate runs only `public-layout.spec.ts` + `public-a11y.spec.ts`, and the full-suite
nightly lane — the project's only full-suite E2E instrument — is dark. The correct wording, fixed as
authoritative by `CONTEXT.md` §5 and used consistently throughout this phase's artifacts, is
***"covered by a spec that no current tree executes"* — never *"covered nightly"***. Closing
UIX-08/09 on a gate that exists and passes locally rather than on continuous execution follows this
repository's own established precedent: Phase 34 closed **TRUTH-02** on exactly that basis.

**Documentation gap (informational, and it belongs to the orchestrator's close-out).**
`UIX-07/08/09` exist in `.planning/REQUIREMENTS.md` **only as traceability rows** (lines 261–263).
They have **no checkbox definition entries** — verified: `rg` for `- [ ] **UIX` returns rc=1 with a
positive control showing **53** such definitions for other requirements. So there is nothing for
`requirements mark-complete` to check off, and the three rows still read *"In progress"*. Per this
project's standard GSD sequencing — and per the identical note in `34-VERIFICATION.md` — closing
`REQUIREMENTS.md`, `STATE.md` and `ROADMAP.md` is the **orchestrator's** step at phase completion,
not a plan's. Not treated as a gap in the phase's delivery.

## Anti-Patterns Found

None in delivered code. The phase's own discipline caught two anti-patterns *during* execution and
both were fixed rather than explained away: two test-file **comments** that were generating live CSS
rules (`sm:max-w-lg`, `sm:max-w-none` — Tailwind's scanner is lexical), fixed in `59b80ce0`; and a
block-counter defect that had made two required CI gates mutually unsatisfiable, fixed in 35-11 with
a reproducing fixture. `check-no-measured-placeholders.sh` PASS rc=0 in this session's sweep.

Two **empty states** were measured and are correctly-behaving product rather than stubs — the
approvals queue (*"No applications waiting"*, 0 rows) and the finance VAT grid (1 of 4 declared
columns filled). Both are seed facts, both are stated with the numbers that distinguish them from a
broken surface, and the first is filed as **#690** because it blocks a judgement, not because it is
a stub.

## Human Verification Required

**None outstanding — this phase's human gate has been taken.** Phase 35 is unusual in that its
originating criterion was subjective, so a green suite could never close it; the blocking checkpoint
was answered by the owner on 2026-08-30 and is recorded in `CONTEXT.md` §7 with all three decisions.

One item is named for the record without escalating status: **#690**, the approvals queue's
Index-vs-Detail tier, is the phase's lowest-confidence call and remains **undecided** — not because
nobody looked, but because the surface renders empty on the seeded stack and a look at it would have
been a vacuous pass. It needs one seeded PENDING application, not a decision.

## Open Items Carried Into the PR (reported, not absorbed)

| # | Item | Relationship to this phase |
|---|---|---|
| **#683** | The nightly full-suite E2E lane is dark | **Pre-existing.** The reason UIX-08/09 close on local evidence rather than continuous execution. Every phase artifact says *"covered by a spec that no current tree executes"*, never *"covered nightly"* |
| **#686** | Skip budget 7/6 with one undeclared (`onboarding-blocked-flow`) | **Pre-existing and unchanged by this phase.** Reseeding does not clear it — the demo tenant's onboarding is LIVE/terminal. **This phase contributes zero skips.** No `ALLOW` was added, because declaring a skip the phase did not cause would be papering over #686 |
| **#687** | `marketing-motion` flake (~1 in 3) | **Pre-existing**, filed *before* this branch existed. `hero-scene.tsx` is **blob-identical** across the phase; the file is green in isolation; the failure moves between runs. This phase cannot be the cause |
| **#689** | jsdom cannot evaluate the geometric `scrollable-region-focusable` rule | **Pre-existing.** 35-12 added the finding that the report-only browser sweep cannot evaluate it either on this seed, as a comment rather than a duplicate issue |
| **#690** | Approvals queue tier undecidable while the queue renders empty | **Filed by this plan.** Also records the finance-chart candidate as moot and the two band-fill surfaces as examined N/A, with measurements |

## Gaps Summary

None blocking. The phase's goal and all four non-negotiable constraints were verified against the
live codebase and the **delivered runtime** this session — either by executing the exact
gate/spec/probe and observing the claimed output with a control in the failing direction, or (for
the merge-base rebuild and the full-suite E2E sweep, both explicitly out of scope for this pass) by
assessing 35-12's dated, quoted, both-directions evidence, which was internally consistent with
every figure re-measured here: the delivered CSS chunk name and its `max-width:` arithmetic
(26 − 2 = 24), the Jest counts (141/1503), the tier band widths at three viewports, and the prose
clamp count all matched on independent re-measurement.

Three things were found that the phase's own instruments had not: **HANDOFF.md's gate-count claim
went stale when this phase added the 41st gate** and would have opened the PR red (fixed,
break-armed); **35-13's own step 3 asks the owner to judge charts on a page that has none** (the same
class as T-35-52, one level up); and **`CONTEXT.md` §7 overstates the Detail narrowing by 264px** by
quoting a counterfactual where a measurement is read. None changes a decision; all three are
recorded rather than smoothed over.

No stub, no unwired artifact, no vacuous assertion and no debt marker was found in the phase's
delivered code.

---

*Verified: 2026-08-29T23:58:12Z*
*Verifier: Claude (gsd-executor, plan 35-13)*
