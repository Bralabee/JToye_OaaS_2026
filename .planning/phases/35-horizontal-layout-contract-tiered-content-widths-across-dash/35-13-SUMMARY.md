---
phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
plan: 13
subsystem: verification
tags: [owner-gate, human-verification, falsifiability, gate-sweep, layout, visual-review]

# Dependency graph
requires:
  - phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
    plan: "12"
    provides: "the delivered runtime proven to be this branch, the eight before/after captures, and the pre-change control arm this plan assesses rather than re-runs"
provides:
  - "the owner gate outcome, recorded verbatim with its date and provenance"
  - "the 1440 must-not-move claim MEASURED per tier, and the Detail narrowing stated with the magnitude the owner actually accepted"
  - "the full 41-gate sweep with every rc, and three failures triaged to cause"
  - "the step-4 visual check no assertion in this phase performs, run against the captures and then MEASURED"
  - "an honest UIX-07/08/09 assessment: two closable, one not, with the reason named"
affects: []

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "screenshot-spotted-then-measured: a symptom read off a scaled capture is a hypothesis; the disposition turns on the number, so measure before filing"
    - "grid-declares-vs-seed-fills: an empty-looking grid is a seed fact until you read gridTemplateColumns"

key-files:
  created:
    - .planning/phases/35-horizontal-layout-contract-tiered-content-widths-across-dash/35-13-SUMMARY.md
    - .planning/phases/35-horizontal-layout-contract-tiered-content-widths-across-dash/35-VERIFICATION.md
  modified:
    - HANDOFF.md

key-decisions:
  - "The two additive candidates the gate existed to decide were DECIDED, not deferred: the finance chart band is MOOT (that page renders zero charts and imports no chart library) and the approvals tier is UNDECIDABLE on an empty queue, filed as #690"
  - "The two left-hugging surfaces spotted by eye were measured and recorded as EXAMINED N/A, not filed as defects — the VAT grid declares 4 columns and fills 1 from the seed; the status-flow strip is a closed six-value stepper under the contract's own ceiling-not-target rule"
  - "HANDOFF.md's stale gate count was FIXED rather than logged: this phase added the 41st gate, and check-handoff-contract runs on pull_request, so the phase's own PR would have opened RED"
  - "The redis-exporter and go-coverage failures were repaired as ENVIRONMENT, not absorbed and not reported as phase defects — each was traced to cause first"

patterns-established:
  - "A gate document can be wrong about the product: 35-13 step 3 asked the owner to judge charts on a page that has none"

requirements-completed: [UIX-07, UIX-08]
requirements-progressed: [UIX-09]

# Metrics
duration: 100min
completed: 2026-08-30
---

# Phase 35 Plan 13: The Owner Gate and Phase Verification Summary

**The owner's three decisions are ratified against measured numbers rather than prose — Shell and Index are confirmed unmoved at 1440 and Detail confirmed moved by exactly the accepted amount — and the gate's own step 4 finally ran, finding that one of the two things it told the owner to look at does not exist.**

## Performance

- **Duration:** ~100 min
- **Tasks:** 2 of 2
- **Files modified:** 1 (`HANDOFF.md`), plus this SUMMARY and `35-VERIFICATION.md`
- **Branch:** `feature/35-horizontal-layout-contract`, 71 ahead / **0 behind** `origin/main`

---

## (i) THE OWNER GATE — outcome, and its provenance

The blocking checkpoint was put to the owner by the **orchestrator** on **2026-08-30**, with the
eight before/after captures at `frontend/e2e-artifacts/35-12/` and the measured arithmetic. It is
recorded in `CONTEXT.md` **§7**. Provenance is stated deliberately, because an earlier draft of
these plans attributed orchestrator choices to the owner: **these three ARE owner decisions**,
unlike ORCH-01..06 which were taken under delegated autonomy.

| Question | Owner decision | Recorded consequence |
|---|---|---|
| Detail tier narrows live surfaces | **Accepted 1100px** | The peer-matched reading column stands (Linear 1136, Square 1016, Lightspeed 1100). `35-05-SUMMARY.md`'s displaced-goods ledger is the **accepted cost, not an oversight** |
| ORCH-01: `/shop` stays Marketing 1280 rather than fluid | **Confirmed 1280px** | The one owner-visible judgement in the phase is ratified rather than assumed. No SEO/CLS re-measurement owed on a public indexed surface |
| Close-out | **Run 35-13, then open a PR** | Merge left to the owner after CI |

This plan did **not** re-ask. It verified.

### The third question the resume-signal named, and what happened to it

The plan's resume signal named **three** things it wanted answered. Two were answered above. The
third — *"the approvals queue tiered Index rather than Detail — the phase's lowest-confidence
call"* — was **not** answered, and this record says so rather than folding it into "approved".
Section (iv) gives the measured reason it could not be, and its issue number.

---

## (ii) THE 1440 CLAIM — measured per tier, which is the thing the plan was corrected about

Threat **T-35-52** exists because an earlier draft told the owner *"if anything moved at 1440,
that is a defect"* — which would have reported a **correct** build as defective and invited the
repair of undoing the tier. The corrected claim is scoped, and here it is measured.

All figures below are from a probe I ran against the **delivered runtime** at
`--project=desktop`, scoped to `main` (see the scope proof below), then deleted:

| viewport | `main` content | Shell | Index | Detail | doc overflow |
|---|---|---|---|---|---|
| **1440** | 1184 | **1184** | **1120** | **1100** | 0 |
| 1920 | 1664 | 1664 | 1600 | 1100 | 0 |
| 2560 | 2304 | 1700 | 1636 | 1100 | 0 |

**Shell and Index at 1440 are UNCHANGED, and this is provable without a second build.** The
pre-change cap was **1400** (35-12 read `max-width:1400px` twice out of the merge-base build's
served stylesheet). `main` offers **1184** at 1440, measured. Band = `min(parent content, cap)`,
so `min(1184, 1400) = 1184` before and `min(1184, 1700) = 1184` after: **a cap that does not bind
cannot move the band.** Index is 1184 − 64 padding = 1120 on both. 35-12's ledger row 11
independently measured this on real builds of both trees and recorded *"1440 unchanged at 1184"*.

**Detail moved, by design, and by the magnitude the owner accepted.** Pre-change the Detail
surfaces had no cap of their own and simply sat inside the container band:

| viewport | pre-change Detail | now | narrowing |
|---|---|---|---|
| 1440 | 1120 | 1100 | **−20px** |
| 1920 | 1336 | 1100 | **−236px** |
| 2560 | 1336 | 1100 | **−236px** |

The pre-change **1336** is not inferred — it is **visible and measurable in 35-12's own
pre-change captures**. In `35-12-prechange-orders-2560.png` the content band runs ~1334px and is
**centred with ~485px of empty gutter on BOTH sides**; in `35-12-current-orders-2560.png` the same
band is ~1636px with ~334px gutters. That centring is the owner's original words made visible —
*"confined to the middle"* — and it is what 1400px `margin:auto` does at 2560.

### A documentation discrepancy, named rather than smoothed over

`CONTEXT.md` §7 states the Detail narrowing as *"1600→1100 at 1920/2560"*. The **plan** states it
as *"roughly 1336 to 1100"*. Both are true of different comparisons and only one answers the
owner's question:

- **1336 → 1100** is *actual before* vs *actual after* — what the live surface really did. **This
  is the number the owner was shown in the plan and the one they accepted.**
- **1600 → 1100** is *post-change-untiered* vs *post-change-tiered* — the counterfactual, i.e.
  what these surfaces would have become had the Detail tier not been applied.

§7's figure therefore **overstates the narrowing by 264px**. The decision is unaffected — the
owner accepted the tier on the plan's numbers — but the record should not carry a figure that
reads as a measurement and is not one.

### The scope was proven, not assumed

A document-wide `[data-width-tier]` query is satisfied by the header and footer rails, which
would make *"the marketing band is 1280"* true without ever looking at content. Every measurement
above prints both counts. On `/shop`: **docCount 5, main-scoped 1** — the trap is real on this
tree, and the scoped band is what was measured.

---

## (iii) `/shop` (ORCH-01) — ratified, and measured

| viewport | `main` content | `/shop` marketing band (main-scoped) |
|---|---|---|
| 1440 | 1440 | **1280** |
| 1920 | 1920 | **1280** |
| 2560 | 2560 | **1280** |

Unchanged at Marketing 1280, exactly as ORCH-01 specified and as the owner confirmed. 35-12
additionally measured the header rail and the content band sharing a left edge at 1280/left-80 on
**both** builds, so the misalignment this phase fixed on `/` never existed here.

---

## (iv) STEP 4 — the visual check no assertion performs

RESEARCH names a defect class only a person catches: *a centred narrow layout that read as
deliberate becomes a stretched band with a lonely heading — green tests, worse product.* I read
the captures directly and then measured every candidate, because a symptom read off a capture
scaled 2560→2000 is a hypothesis, not a measurement.

### The headline surface reads better

`/dashboard/orders` at 2560, before vs after: the six-column table (Order ID, Customer, Status,
Total, Created, Actions) goes from a 1334px band centred in ~970px of gutter to a 1636px band.
The band **stays centred with symmetric gutters** — content does not hug the left edge — columns
gain breathing room, and Actions moves out to the band edge. Nothing empty, nothing broken.

### Candidate 1 — `/dashboard/finance` "VAT Breakdown" → **EXAMINED N/A**

`frontend/app/dashboard/finance/page.tsx:263`. By eye this is the textbook symptom: one small
tile at the left edge of a 1636px card with ~1200px of white beside it. Measured, it is not a
defect:

| viewport | card | content box | grid declares | filled | cell |
|---|---|---|---|---|---|
| 1440 | 1120 | 1070 | 4 (`258.5px ×4`) | 1 | 259 |
| 1920 | 1600 | 1550 | 4 (`378.5px ×4`) | 1 | 379 |
| 2560 | 1636 | 1586 | 4 (`387.5px ×4`) | 1 | 388 |

The element is `grid gap-3 sm:grid-cols-2 lg:grid-cols-4`. It declares **four** columns at every
desktop viewport and the cell is exactly a quarter of the content box —
`(1586 − 3×12) / 4 = 387.5`, matching the computed `grid-template-columns` to the half-pixel. The
grid **does** fill the band, and fills it *better* after the change (258.5 → 387.5). The
emptiness is the seed holding **one VAT rate category**.

### Candidate 2 — `/dashboard/orders` "Order Status Flow" → **EXAMINED N/A, with a named residual**

`frontend/app/dashboard/orders/page.tsx:571`.

| viewport | card | content box | pills | ink width | trailing |
|---|---|---|---|---|---|
| 1440 | 1120 | 1070 | 6 | **888** | 182 |
| 1920 | 1600 | 1550 | 6 | **888** | 662 |
| 2560 | 1636 | 1586 | 6 | **888** | 698 |

Ink width is **constant at 888px** — `flex flex-wrap` over six content-sized pills. It cannot
grow, so trailing space went from ~398px pre-change to ~698px: **amplified by ~300px, not
introduced.** Still N/A, deliberately: this is a **stepper over a closed six-value set** (Draft,
Pending, Confirmed, Preparing, Ready, Completed), not a data surface. Stretching a six-step
stepper across 1586px would read worse. This is precisely what the contract's own
**ceiling-not-target** rule is for — the same rule under which 35-05's exceptions ledger leaves
`/track` at 512px rather than dragging it to the band.

**Neither is the ORCH-04/ORCH-06 class** (*a content band inset from the chrome that wraps it*).
Both cards span the full band and share their left and right edges with the dashboard chrome.
That class was real, was fixed on `/` and the four `/legal/*` routes, and does not recur here.

Recorded as measurements on **#690** so a future reader has a number to argue with rather than a
silence.

### The two deliberately-unapplied additive candidates — DECIDED, per the plan's own instruction

The plan is explicit: *"a decision that is not written down did not happen."*

**Candidate A — the finance chart band: MOOT. The gate's premise is false.** Step 3 told the
owner *"the FINANCE LEDGER … has charts above its table. Charts stretched very wide and very
short can read worse."* Measured: `/dashboard/finance` renders **zero** charts at 1440, 1920 and
2560, and `frontend/app/dashboard/finance/page.tsx` imports **no chart library at all**. The
selector control found **2** charts on `/dashboard` in the same run, so the zero is about the
page and not about the probe. The planned additive fix — *"cap the chart band inside the page"* —
**has no subject.**

The real charts are on the `/dashboard` overview (Shell tier), and since the concern is genuine
even if misfiled, it was measured there too: aspect ratio **1.99** (498×250 @1440) → **2.95**
(738×250 @1920) → **3.02** (756×250 @2560), bounded by the 1700px shell cap. 3:1 is an ordinary
dashboard chart proportion. Closed by measurement, not deferred.

**Candidate B — the approvals queue tier: UNDECIDABLE, filed as #690.** Not "measured and fine"
but *"unmeasurable while the queue renders empty"*. `/dashboard/onboarding/approvals` renders its
empty state on the seeded stack — *"No applications waiting"* — with **0 rows and no table** at
all three viewports, so there is no row, card or heading that could hug an edge or be stranded.
A green look would have been a **vacuous pass, not an approval**. Same root cause as **#686**:
the demo tenant's onboarding is in a LIVE/terminal state. #690 states exactly what would settle
it — one seeded PENDING application.

---

## (v) THE GATE SWEEP — all 41, every rc, three failures triaged to cause

Run from the **main checkout** (not a worktree — the compose project name comes from the
directory). Final state:

**40 of 41 rc=0.** The single rc=1 is `check-e2e-skip-budget.sh`, which is **#686** and
pre-existing.

Three gates failed on first sweep. **None was a phase-35 code defect, and one was a phase-35
documentation defect that would have opened the PR red.** Each was traced before being touched:

| Gate | First run | Cause | Disposition |
|---|---|---|---|
| `check-handoff-contract.sh` | rc=1 — *"says 'EXPECT 40 x rc=0' but the repo has 41 gate script(s)"* | **Caused by this phase.** 35-10 added `scripts/check-layout-width-contract.sh`: merge base has 40 gate scripts, HEAD has 41. The gate runs in `docs-freshness.yml` on `pull_request` | **FIXED** (`bcb1d011`), break-armed |
| `check-go-coverage.sh` | rc=1 — 63.3% < 65.0% floor | **Stale untracked artefact.** `edge-go/coverage.out` dated 2026-08-29 02:20 with 311 data lines. Zero Go files changed on this branch (control: the same filter matches 58 frontend files). CI regenerates it in the same job | **Regenerated**, → **67.0%** / 313 blocks, rc=0 |
| `check-alert-liveness.sh` | rc=1 — *"L-1b exporter job='redis' is UP but BLIND: redis_up = 0"* | **Local environment drift.** `jtoye-redis-exporter` (up 35h) held a **12-char** `REDIS_PASSWORD`; `.env` now carries a **48-char** one. Redis restarted 2026-08-29T14:00Z with the new secret; the exporter never was. Zero infra commits on this branch | **Repaired** by compose recreate, rc=0 |

The redis case is Proof Standard #5 exactly — *a structural check can pass while the function is
broken.* The container reported healthy and its Prometheus target reported `up=1` the whole time;
only the `redis_up` **gauge behind it** was 0. Two arms distinguish stale state from a real
config defect:

- **`docker restart` did NOT clear it** — so not a dropped connection.
- **A fresh `docker run` of the identical image with the identical `REDIS_ADDR` and the current
  `.env` password reported `redis_up 1` and an empty scrape error** — so the committed config is
  correct.
- Redis itself answered `PONG` at `redis:6379` on that network, with a negative control
  (`nosuchhost-35-13` → *"Could not connect"*), so DNS was never the issue.

Repair was the **compose-level recreate**, never `docker network connect` and never a hand-edit.
`blind=1 → blind=0`.

### Gate detail worth quoting

| Gate | Result |
|---|---|
| `check-layout-width-contract.sh` | PASS rc=0 — **104 claims across 7 assertions (G-1..G-7)**, 27 declaring files over a 43-row manifest |
| `check-runtime-freshness.sh` | PASS rc=0 — **4/4 FRESH, 0 unverified** |
| `check-branch-behind-base.sh` | PASS rc=0 — **71 ahead, 0 behind** `origin/main` |
| `docs-freshness.sh` | PASS rc=0 — total logical invocations **3492** |
| `check-doc-metrics.sh` | PASS rc=0 — **37/37** prose claims across 3 docs |
| `check-gate-enforcement.sh` | PASS rc=0 — 40 gates, 6 workflows, 6 declared exempt |
| `check-e2e-skip-budget.sh` | **rc=1** — 323 total, **7 skipped (budget 6)**, undeclared: `onboarding-blocked-flow.spec.ts`. specDigest matches the tree. **#686**, unchanged by this phase |

---

## (vi) SUITES AND SPECS RE-RUN BY THIS PLAN, not inherited

| Instrument | Result |
|---|---|
| `e2e/layout-width-contract.spec.ts --project=desktop` | **21 passed** rc=0, against the delivered runtime |
| `frontend` jest (full) | **141 suites / 1503 tests / 2 snapshots — all passed** rc=0 |
| `e2e/landing-webperf.spec.ts` (both projects) | **9 passed** rc=0, incl. the ORCH-02 desktop CLS arm *"holds desktop CLS no worse than its measured pre-change control"* |
| `edge-go` `go test ./...` | rc=0, all packages |

Jest **141 / 1503** matches `docs/metrics.json` (`jest_files` 141, `jest_blocks` 1503) exactly.

**Runtime parity read by content, by me, out of the running container** — not from a SUMMARY:

```
served /_next/static/chunks/3otvctlq2qoxs.css     96,685 bytes
  .max-w-shell{max-width:1700px}       1
  .max-w-detail{max-width:1100px}      1
  .max-w-marketing{max-width:1280px}   1
  .container{                          0
  max-width:1400px                     0
  CONTROL  max-width: occurrences     24
  CONTROL  max-width:9999px (absent)   0     <- the probe can report an absence
```

The chunk name **`3otvctlq2qoxs.css`** is the one 35-12 recorded as the final delivered artefact
(a Next.js CSS chunk name is a content hash, so this is identity, not resemblance). The
`max-width:` count reconciles arithmetically with 35-12's pre-fix control of 26: the two
comment-generated rules it removed are absent (`sm\:max-w-lg` 0, `sm\:max-w-none` 0) while the
genuinely-applied control `sm\:max-w-md` is still present — **26 − 2 = 24**.

---

## Deviations from Plan

### 1. [Rule 1 — Bug] HANDOFF.md's gate count went stale when this phase added the 41st gate

Full account in (v). `check-handoff-contract.sh` runs in `docs-freshness.yml` on
`pull_request: branches: [main]`, so this phase's own PR would have opened RED. Fixed in
`bcb1d011`. **Fail direction recorded before the fix**, and the gate then break-armed
clean → arm → clean:

| Arm | Result |
|---|---|
| opening clean | `EXPECT 41` ×1, `EXPECT 42` ×0 · gate rc=0 |
| **ARM** — claim 42 gates | `FAIL: H-1 … says 'EXPECT 42 x rc=0' but the repo has 41 gate script(s)` · rc=**1** |
| restore from the **commit** | `EXPECT 41` ×1, `EXPECT 42` ×0 · blob `137cbb1c…` on disk **==** blob at HEAD · `git diff --quiet` rc=0 |
| closing clean | gate rc=0 |

The restore is verified by **blob identity**, never `git diff --stat`, and the file was
**committed before the arm ran** so the restore target was a committed state.

### 2. [Rule 3 — Blocking, environment] Two gate failures were environment, not code

`edge-go/coverage.out` staleness and the redis-exporter's rotated secret. Both traced to cause
before being touched, both repaired, both recorded in HANDOFF.md so the next reader does not
re-diagnose them. Neither is a phase-35 defect and neither is reported as one.

### 3. [Deviation from the plan's instruction] The plan's own step 3 describes a surface that does not exist

The plan tells the owner that `/dashboard/finance` *"has charts above its table"*. It has none,
and imports no chart library. Recorded rather than quietly skipped, because it is the same class
as **T-35-52** one level up: a verification step that cannot be satisfied as written teaches its
reader to distrust the gate. The concern was measured where the charts actually live.

### 4. [Deviation — decided, not deferred] Only one of the three resume-signal questions needed an issue

The plan required each finding to be *"scoped as an additive plan or filed as an issue, with the
number recorded"*. The finance chart band needed neither (moot). The two left-hugging surfaces
needed neither (measured N/A). The approvals tier needed one: **#690**.

### 5. [Recorded, NOT fixed] Four open items carried into the PR

**#686** skip budget 7/6 with one undeclared · **#687** `marketing-motion` flake · **#689** jsdom
cannot evaluate the geometric axe rule · **#683** the dark nightly lane. None caused by this
phase; each is reported in (v) and in `35-VERIFICATION.md` rather than absorbed.

### 6. [Recorded] Synthetic orders remain on the dev stack

35-12's `T-35-13 Live Reflow Probe` and `Metric Seed Probe` orders are visible in the captures
and still present. Each is labelled synthetic in its own `notes` field. Local dev stack only.

---

## Known Stubs

None introduced. Two **empty states** were measured and are correctly-behaving product, not
stubs: `/dashboard/onboarding/approvals` renders *"No applications waiting"* because no
application is pending, and the finance VAT grid fills 1 of its 4 declared columns because the
seed holds one VAT rate category. Both are seed facts, both are stated with the numbers that
distinguish them from a broken surface, and the approvals one is filed as **#690** precisely
because it blocks a judgement rather than because it is a stub.

## Threat Flags

None. This plan changed no endpoint, input, credential, data flow or dependency. The one code
change is a documentation line in `HANDOFF.md`.

| Threat | Outcome |
|---|---|
| **T-35-50** a green suite standing in for acceptance | **mitigated.** The gate was taken by a person (CONTEXT §7, 2026-08-30) against real captures, not against a description of them |
| **T-35-51** verifying from a description | **mitigated.** The captures were read directly — the pre-change centring and its ~485px symmetric gutters were seen, not quoted — and every symptom spotted by eye was then measured in a live browser |
| **T-35-52** a gate that instructs the owner to reject a correct build | **mitigated, and the scoping held.** Shell and Index confirmed unmoved at 1440; Detail confirmed moved at the accepted magnitude. A §7-vs-plan figure discrepancy was found and named rather than smoothed |
| **T-35-53** an orchestrator decision passing as ratified | **mitigated.** ORCH-01 got an explicit owner answer and is measured at 1280 on three viewports; ORCH-01..06 are cited by that name throughout and never as user decisions |
| **T-35-SC** package installs | **accepted.** None performed |

## Cited decisions

**ORCH-01..ORCH-06 (orchestrator decisions, 2026-08-29, `CONTEXT.md` §4b)** — cited by that name.
**The three owner decisions of 2026-08-30 (`CONTEXT.md` §7)** are cited as owner decisions. The
distinction is the one this phase corrected mid-flight and is preserved here.

## Commits

| Commit | Type | Subject |
|---|---|---|
| `bcb1d011` | fix | HANDOFF's gate count went stale when this phase added the 41st gate |

Written through a **quoted heredoc** and passed with `git commit -F`, never an interpolating
`-m` string, then read back with `git log -1 --format=%B` — backticks inside a double-quoted
message execute and are silently dropped, and the corruption is invisible at write time. Named
paths only; `git diff --diff-filter=D HEAD~1 HEAD` reports **zero deletions**.

## TDD Gate Compliance

Not a `type: tdd` plan, and correctly so: the deliverable is a recorded decision and evidence,
not behaviour. The one code change is a documentation line whose fail direction was recorded
before the fix and re-armed after it.

## Self-Check: PASSED

Run with a control in the failing direction on both halves, so a FOUND result is a statement
about this repository rather than about a check incapable of failing.

```
FILES     35-13-SUMMARY.md                                  FOUND
          35-VERIFICATION.md                                FOUND
          HANDOFF.md                                        FOUND
          frontend/app/dashboard/finance/page.tsx           FOUND
          frontend/app/dashboard/orders/page.tsx            FOUND
          8 captures under frontend/e2e-artifacts/35-12/    FOUND (8 png, as claimed)
          CONTROL 35-13-DOES-NOT-EXIST.md                   MISSING (control OK)

COMMITS   bcb1d011 (this plan)  59b80ce0 (35-12)  96c8d794 (the merge base)   FOUND
          CONTROL deadbee                                                     MISSING

CLEANUP   three temporary probes deleted; 'tmp-3513' repo-wide       0 file(s)
          CONTROL 'THE HORIZONTAL LAYOUT CONTRACT' in frontend/e2e   1 file(s)
          git status --porcelain --untracked-files=all               0 paths
```
