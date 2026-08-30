---
phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
plan: 08
subsystem: testing
tags: [playwright, e2e, layout, contract-test, ci, falsifiability, coverage-honesty]

# Dependency graph
requires:
  - phase: 35-horizontal-layout-contract
    plan: "01"
    provides: "lib/layout-widths.ts — the three constants this spec imports rather than restates"
  - phase: 35-horizontal-layout-contract
    plan: "02"
    provides: "components/layout/content-tier.tsx — the tier class map and the data-width-tier vocabulary"
  - phase: 35-horizontal-layout-contract
    plan: "03"
    provides: "the index-tier markers on the dashboard resource indexes"
  - phase: 35-horizontal-layout-contract
    plan: "04"
    provides: "the detail-tier markers, including the deliberately tiered onboarding spinner branch"
  - phase: 35-horizontal-layout-contract
    plan: "05"
    provides: "the shell-tier marker on dashboard-shell.tsx"
  - phase: 35-horizontal-layout-contract
    plan: "06"
    provides: "the landing route's four marketing bands"
  - phase: 35-horizontal-layout-contract
    plan: "07"
    provides: "the marketing rails on public-header / public-footer, and the ORCH-06 policy band"
provides:
  - "frontend/e2e/layout-width-contract.spec.ts — THE browser instrument for the four-tier contract, 21 desktop tests across three viewports"
  - "a per-PR blocking CI step for the Marketing tier, filtered to the @stack-free tag"
  - "the measured refutation of the plan's own ARM A/B design, and the constant-independent geometry claim that replaces it"
  - "the measured CI-vs-live divergence on the landing route (5 bands vs 6) that a naive exact count would have red-ed on every PR"
  - "the consolidated wave-3 close: full Jest + a real production build over the merged wave"
affects: [35-10, 35-11, 35-12, 35-13]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "plural-measurement-helper: the band helper returns an ARRAY plus a raw pre-filter count, so a partial migration and a streaming-buffer duplicate are both visible rather than absorbed"
    - "landmark-by-ancestry scoping: closest('main') decides the region, never document.querySelector order"
    - "constant-independent geometry claim beside a constant-derived one, so an edit to the shared module cannot move both sides of the assertion"

key-files:
  created:
    - frontend/e2e/layout-width-contract.spec.ts
  modified:
    - .github/workflows/ci-cd.yaml

key-decisions:
  - "The fluid/capped claim per viewport is declared in SHELL_CAP_BINDS and NOT derived from SHELL_MAX_PX, because every other assertion imports the constant and therefore follows it wherever it goes — measured, the plan's ARM A and ARM B both PASS without this"
  - "Landing-route band counts are a RANGE (3..4 in main), not an exact number, because the kitchen row is server-data-conditional and the per-PR gate has no backend — measured 5 bands stack-free vs 6 live"
  - "Detail tier measured on /dashboard/onboarding rather than /dashboard/orders/[id]: no seeded order id needed, and all three of its branches carry the tier so the measurement cannot depend on which rendered"
  - "/legal/privacy added to the marketing route set beyond the plan's three, because it carries exactly ONE content band and is therefore the sharpest available control for the scope trap ORCH-06 arm A measured"
  - "Skip-budget option (a) — satisfy the precondition — not (b) declare the skips; the only lane that runs the whole suite already exports the credential"

metrics:
  duration_minutes: 74
  completed: 2026-08-29
---

# Phase 35 Plan 08: Width-Contract Playwright Spec + CI Wiring Summary

A 711-line Playwright instrument that measures all four tiers at 1440/1920/2560 against the
declared constants, wired per-PR for the tier that can run stack-free — plus the measured
refutation of two of its own plan's break arms and the consolidated wave-3 build.

## What shipped

`frontend/e2e/layout-width-contract.spec.ts` — 21 desktop tests, 0 mobile (the `@desktop-only`
tag stops the mobile project ENUMERATING them, so they are never runtime skips):

| Describe | Viewport | Tests | Per-PR? |
|---|---|---|---|
| `@stack-free` Marketing | 1440 / 1920 / 2560 | 4 routes × 3 = 12 | **yes, blocking** |
| Dashboard (Shell, Index, Detail) | 1440 / 1920 / 2560 | 3 × 3 = 9 | no executing lane |

`.github/workflows/ci-cd.yaml` — a new step in the "Frontend E2E (public surfaces)" job,
immediately after the existing layout+a11y step, running `--grep "@stack-free"`.

## Coverage split, stated as the phase requires

Written verbatim into the spec header AND the CI step comment, in CONTEXT.md §5's
authoritative wording:

> **"covered by a spec that no current tree executes"** — citing the open **#683**. Never
> "covered nightly".

- **Marketing tier** — `/`, `/for-operators`, `/business-model-guide`, `/legal/privacy`.
  Genuinely per-PR blocking. Verified: the exact CI command selects **12 tests, all desktop,
  0 mobile**; without the tag filter the same file enumerates **21** on desktop, so the filter
  is doing work rather than decorating the line.
- **Shell / Index / Detail** — `/dashboard`, `/dashboard/orders`, `/dashboard/products`,
  `/dashboard/onboarding`. They need a Keycloak login, so their only instrument is the
  full-suite nightly lane, and #683 records that lane as DARK. The assertions exist, are
  correct, and **run nowhere**. Their per-PR substitutes (35-10's static gate, 35-03..05's
  jsdom assertions) prove the declaration, never the rendered width.

## The five arms, both directions, real output

Bracketed **clean → arms → clean**, everything committed first, every source arm preceded by
a rebuild whose propagation was verified in the emitted CSS (never by a build exit code alone).

**Opening clean:** 21/21 passed (38.5s).

### ARM A — shell constant set to the pre-change 1400, rebuilt

Emitted CSS confirmed the rebuild propagated: `max-w-shell{max-width:1400px}`.

```
✘ Dashboard tiers @ 1920px › Shell
  Error: /dashboard shell @ 1920px: at this viewport NO cap binds, so the dashboard must use
  every pixel <main> offers it — this is CONTEXT.md's "must not move" case, and a shell cap
  set below the available width lands here. band=1400 parentContentWidth=1664
  parentClientWidth=1664 parentPaddingX=0 [<div class="mx-auto max-w-shell p-4 pb-20 sm:p-8
  sm:pb-20 md:pb-8 dark:text-slate-100"> inside <main>, computed max-width=1400px]
  Expected: 1664   Received: 1400
1 failed, 8 passed      ARM_A_RC=1
```

1440 stays green (1184 < 1400, still fluid) — the targeted control showing the arm reds only
where it should.

### ARM B — shell constant set to 900, rebuilt

`max-w-shell{max-width:900px}`.

```
✘ @ 1440px › Shell   band=900 parentContentWidth=1184 ... Expected: 1184  Received: 900
✘ @ 1920px › Shell   band=900 parentContentWidth=1664 ... Expected: 1664  Received: 900
2 failed, 7 passed      ARM_B_RC=1
```

The 1440 red is precisely CONTEXT.md's "must not move" case shown capable of failing.

### ARM C — tier class removed from the shell band, attribute kept, rebuilt

```
✘ @ 1440px  the Shell tier must declare a computed max-width of "1700px", found "none".
            band=1184 ... computed max-width=none        Expected: "1700px"  Received: "none"
✘ @ 1920px  (same shape)                                  Expected: "1700px"  Received: "none"
✘ @ 2560px  expected min(parentContentWidth, SHELL_MAX_PX=1700) = 1700, measured 2304
            (off by 604)                                  Expected: <= 1      Received: 604
3 failed, 6 passed      ARM_C_RC=1
```

Proves the spec measures the ELEMENT, not the declaration.

### ARM D — the vacuity arm (spec-only; no rebuild is meaningful, the artefact is unchanged)

Index selector pointed at a tier value nothing declares:

```
Error: /dashboard/orders index @ 2560px: no element matched [data-width-tier="index"] within
"[data-width-tier="shell"]". A missing band measures 0 and 0 <= every tier, so this MUST fail
rather than pass. (raw matches before the hidden-ancestor filter: 0)
Expected: > 0   Received: 0            ARM_D_RC=1
```

It VOIDed on the non-null guard naming both the selector and its scope — it did **not** pass
with a zero width.

### ARM E — the denominator CONTROL (spec-only)

Index comparison switched to the parent's raw `clientWidth`:

```
✘ @ 1440px  expected min(parentClientWidth, ...) = 1184, measured 1120 (off by 64)
✘ @ 1920px  expected ... = 1664, measured 1600 (off by 64)
✘ @ 2560px  expected ... = 1700, measured 1636 (off by 64)
            band=1636 parentContentWidth=1636 parentClientWidth=1700 parentPaddingX=64
            — NOTE: the miss equals the parent's horizontal padding exactly, which is the
            signature of comparing against clientWidth instead of the content box, NOT a
            layout defect.
3 failed, 6 passed      ARM_E_RC=1
```

Exactly the predicted 64px at 2560, all four numbers printed, and the padding signature named
in the message so the next reader cannot mistake it for a layout bug. This is the only arm
that can distinguish the two denominators — A, B and C all move a declared value and watch the
measurement follow, so they behave identically under either.

### Restores proven by CONTENT and blob identity, never `git diff --stat`

```
MATCH   frontend/lib/layout-widths.ts                 4e0a2638f80d3cb23f108a8f940c655b4dd379a4
MATCH   frontend/components/dashboard/dashboard-shell.tsx  2c7702cdaaacc2dc257e74571cb5426a030c7937
MATCH   frontend/e2e/layout-width-contract.spec.ts    107999592c52217d1ba29761ce9f13b4f8a73c37
git status --short: (empty)
```

**Closing clean arm: 21/21 passed (39.1s), rc=0.**

## Deviations from Plan

### 1. [Rule 1 — Bug in the plan's own arm design] ARM A and ARM B, as written, could not fail

- **Found during:** designing Task 3, before running anything.
- **Issue:** every shell assertion imported `SHELL_MAX_PX`. Editing the module and rebuilding
  moves BOTH sides — the page renders what the module now claims and the spec expects what the
  module now claims. The plan predicted "the 2560 shell assertion must RED" for ARM A and "the
  1440 assertion must RED" for ARM B; under a genuine rebuild neither can.
- **Measured, not reasoned about:** with `SHELL_MAX_PX = 1400` the **2560 shell test PASSES**
  (see ARM A output above — 8 passed, and 2560 is among them). With `= 900` it passes too.
  That drift-proofing is correct and is the point of a declared contract; it just makes a
  module edit useless as a break arm at the capped viewport.
- **Fix:** added `SHELL_CAP_BINDS`, a per-viewport claim about the PAGE'S GEOMETRY that the
  constant cannot move — at 1440 and 1920 the band must FILL `main` (no cap binds); at 2560 it
  must be strictly narrower (the cap binds). ARM A then reds at 1920 and ARM B at 1440, which
  is the falsifiability the plan wanted and could not previously get.
- **Residual, stated rather than hidden:** at **2560** a shell-constant change still passes,
  because both sides move. No assertion in this file can catch "the contract value is wrong" —
  only "the page disagrees with the contract". The instrument for the former is the peer
  evidence in `layout-widths.ts` plus 35-10's static gate.
- **Commit:** `e256431a`

### 2. [Rule 1 — Bug] An exact landing-route band count would have red-ed the per-PR gate on every PR

- **Found during:** writing the count assertion.
- **Issue:** the landing "kitchen row" band is `{shops.length > 0 && …}` from a **server**
  fetch (`app/page.tsx`, #544). The per-PR job has no backend, so the fetch fails and the band
  is absent. An exact count of 4 in `main` — the number a live-stack probe reports — reds in CI
  on a correct page.
- **Measured in both configurations rather than reasoned about:** a second `next start` was
  brought up from the SAME build artefact with `CORE_API_INTERNAL_URL` pointed at a dead port,
  reproducing the CI shape exactly. Live-backed `/` serves **6** marketing bands; dead-backend
  `/` serves **5**. The stack-free half then passed 12/12 against BOTH.
- **Fix:** `MARKETING_ROUTES` declares `mainMin`/`mainMax`, a range on `/` alone with the
  reason recorded at the entry; every other route stays exact.

### 3. [Rule 2 — Missing critical coverage] The rail scope would have been satisfied by a content band

- **Found during:** first draft, scoping the rails with `document.querySelector("header"/"footer")`.
- **Issue:** on `/business-model-guide` and `/competitive` the FIRST `<footer>` in the document
  is the page's own footer, which lives **inside `<main>`**. A rail assertion written that way
  is satisfied by a content band, and the counts add up only by coincidence — the same defect
  class as 35-07's header assertion being satisfied by the footer, and as ORCH-06 arm A.
- **Fix:** region resolved by ANCESTRY (`closest('main')` wins first), with a `stray` bucket
  asserted empty so a band cannot drift out of every scope the file checks.

### 4. [Scope boundary — recorded, not fixed] The Compose frontend runtime is stale

- `scripts/check-runtime-freshness.sh` — **rc=1, `FAIL: 1 of 4 running built service(s) do not
  match the source tree (0 unverified)`**: `frontend` is `[image-not-rebuilt]`, image tagged
  `2026-08-29 15:42:00 UTC` against build-input commit `34256f5c` at `2026-08-29 19:40:26 UTC`.
  core-java, edge-go and mcp-server are FRESH.
- Independently corroborated before the gate was run: `curl localhost:3000/` returns **0**
  `data-width-tier="marketing"` attributes, so the running image predates 35-06 entirely.
- **This is why every measurement in this plan was taken against a locally built
  `next start`, not against `:3000`** — measuring the contract on a runtime that predates it
  would have produced a confident, worthless red.
- Not fixed here: it is outside this plan's file set and belongs to the phase's runtime-parity
  close. **Named as owed to 35-12 / 35-13.**

### 5. [Rule 1 — Bug, caught by this plan's own self-check] The CI step's coverage wording was present but not greppable

- **Found during:** the post-SUMMARY self-check, which is exactly what it is for.
- **Issue:** the authoritative sentence was in the CI comment, but SPLIT across two comment
  lines with a `#` and indentation between the halves. A search for the phrase as a contiguous
  string matched the spec header and **not** the workflow:
  `spec: 1, ci-cd.yaml: 0`. A coverage boundary a reader can only find by reading every line of
  a 1000-line workflow is stated for the author's benefit, not the next reader's — which is the
  same defect the boundary exists to prevent.
- **Fix:** put on one line, with a note recording why so a later reflow does not silently undo
  it. Re-verified in **both** directions: `1` match in each file, and a phrase that is absent
  returns `rc=1`, so the check can report absence rather than always matching.
- **Commit:** `9f18eeb5`. `actionlint` rc=0; the workflow re-parses and both Playwright steps
  read back as separate steps with their own commands (and the parser was itself shown able to
  fail on a deliberately broken copy, `rc=1`).
- The spec file is byte-identical across this fix, so the closing 21/21 clean arm still
  describes the committed spec.

### 6. [Deviation from the plan's route list — additive] `/legal/privacy` added to the marketing set

The plan named three marketing routes. A fourth was added because it carries exactly ONE
content band, which makes it the sharpest control in the file for the scope trap: revert
`components/legal/policy-page.tsx` and the `main`-scoped count drops to 0 and this reds, while
the document-wide query would still find the two rails and pass — which is exactly what ORCH-06
arm A measured.

## Skip budget — option (a), satisfied, and measured in both directions

Recorded explicitly as the plan requires. **Option (a): state and satisfy the precondition.**
Not (b).

| Arm | Result |
|---|---|
| Credential supplied (`set -a; . ./.env; set +a`) | **21 passed, 0 skipped**, all desktop |
| `E2E_VENDOR_PASSWORD` and `KC_SEED_USER_PASSWORD` both unset | **12 passed, 9 skipped**, all desktop, **0 mobile results** |

The 0-mobile figure in both arms confirms `@desktop-only` prevents ENUMERATION, so nothing here
can ever be a "not applicable here" skip.

The precondition is satisfied structurally, not by hope: **`e2e-nightly.yml:311-316`** — the
only lane that runs the whole suite — does `set -a; . ../.env; set +a` then
`export E2E_VENDOR_PASSWORD="${KC_SEED_USER_PASSWORD}"` before invoking Playwright. So these
describes EXECUTE there and contribute **zero** skips. Run credential-less they would add 9,
which is over `MAX_SKIPS 6` on its own — stated here so nobody discovers it by surprise.

`scripts/check-e2e-skip-budget.sh` is **rc=2 VOID** — *"report describes a DIFFERENT spec set
than the tree"*. **Attributed, not assumed:** `git diff --name-only origin/main 139fe252^ --
frontend/e2e/ frontend/playwright.config.ts` lists `landing-webperf.spec.ts` and
`perf-budgets.ts`, both edited by earlier plans on this branch, so the digest had already
diverged **before** 35-08 touched anything. Clearing it needs a fresh full-suite report, which
this plan does not own.

## Consolidated wave-3 close

Handed forward by 35-03, 35-04, 35-05 and 35-07, each of which narrowed its own closing arm.

| Signal | Result |
|---|---|
| `npx jest` | **141 suites, 1503 tests, 2 snapshots — all passed**, rc=0 |
| `npm run build` (after `rm -rf .next`) | **rc=0 in 17s**, compiled 7.4s, 7 static pages generated |

**The build was shown able to fail before being trusted.** Appending
`export const ARM_TYPE_ERROR: number = "not a number"` to `lib/layout-widths.ts`:

```
  Running TypeScript ...
lib/layout-widths.ts(141,14): error TS2322: Type 'string' is not assignable to type 'number'.
Failed to type check.
BUILD_BREAK_ARM_RC=1
```

Restored → `RESTORED_BUILD_RC=0`, blob back to `4e0a2638…`. So "the build is green" is a claim
about a live type-check over shipped code (`tsconfig.build.json`), not about a no-op.

Nothing in the merged wave reds. No finding to attribute to another plan.

## Block delta — `docs/metrics.json` NOT regenerated (35-11 owns that)

`scripts/docs-freshness.sh` is **rc=1 DRIFT** (the state the orchestrator described; not the
rc=2 VOID that 35-06/07 saw).

| Metric | Committed | Computed now | Delta |
|---|---|---|---|
| `jest_blocks` / `jest_files` | 1394 / 140 | 1504 / 141 | +110 / +1 — **not mine**, no Jest test added here |
| `playwright_blocks` / `playwright_specs` | 122 / 26 | 127 / 27 | +5 / +1 |

**My contribution, measured with the repo's own counter rather than by grep:**

```
$ printf 'frontend/e2e/layout-width-contract.spec.ts\n' | node scripts/count-test-blocks.mjs --family playwright --stdin
{"blocks":4,"files":1}
```

**4 playwright blocks, 1 spec file.** The remaining **+1** block is attributed by measurement,
not inference: `landing-webperf.spec.ts` counts **4** at `3ef2cd51^` and **5** now — 35-09's
desktop CLS arm. 122 + 4 + 1 = 127; 26 + 1 = 27. Accounted for exactly.

## Verification — every criterion, both directions

| # | Criterion | Pass direction | Fail direction |
|---|---|---|---|
| 1 | `check-e2e-typecheck.sh` | rc=0, *"PASS: 31 e2e file(s) type-check clean"* | the constants module IS in the program — `tsc --listFiles` resolves `frontend/lib/layout-widths.ts` (grep count 1), so a malformed module reds the gate |
| 2 | Stack-free half, live-backed `:3100` | **12 passed** rc=0 | ARM D / ARM E |
| 3 | Stack-free half, CI-shaped `:3011` (dead backend) | **12 passed** rc=0 | an exact count of 4 in `main` would have red-ed here — see deviation 2 |
| 4 | Full spec, `--project=desktop` | **21 passed** rc=0 | ARMs A–E |
| 5 | `check-gate-enforcement.sh` | rc=0 | **rc=0 is VACUOUS for this change** — it audits `scripts/check-*.sh` and this plan adds none. Shown able to fire: dropped an unwired static gate in → `FAIL: 1 gate(s) are referenced by no workflow`, rc=1, naming the file; removed it → rc=0 |
| 6 | `check-e2e-baseurl-contract.sh` | rc=0, 27 specs scanned, 0 divergent | this spec declares no fallback at all, so it cannot diverge |
| 7 | `check-playwright-mobile-contract.sh` | rc=0 | — |
| 8 | `check-e2e-skip-budget.sh` | **rc=2 VOID**, pre-existing on this branch — attributed above | — |
| 9 | CI grep selects what it claims | 12 tests, 12 desktop, 0 mobile | control: no `--grep` → 21 desktop, so the tag is load-bearing |
| 10 | `npx jest` | 141 suites / 1503 tests | — |
| 11 | `npm run build` | rc=0 from a cleared `.next` | TS2322 → *"Failed to type check"* rc=1, then restored to rc=0 |

## Owed forward

- **The pre-change-tree arm** — CONTEXT.md §6's *"must be shown failing against the pre-change
  tree"*. Explicitly **deferred to plan 35-12**, as the plan directs. The five arms here are the
  sharper, cheaper ones; none of them is that arm.
- **Compose frontend rebuild + runtime parity** — `check-runtime-freshness.sh` rc=1, frontend
  `[image-not-rebuilt]`. Owed to 35-12 / 35-13.
- **`docs/metrics.json` regeneration** — 35-11 owns the single regeneration for the phase. Block
  delta recorded above; nothing hand-edited.
- **A fresh full-suite report** to clear the skip-budget VOID (also needed by #686).

## TDD Gate Compliance

The plan is `type: tdd` and Task 1 is `tdd="true"`. The deliverable **is a test**, so there is
no separate implementation to make it pass — the RED/GREEN split does not decompose the usual
way, and pretending otherwise would have produced a ceremonial `feat` commit over no behaviour.

What was done instead, and it is strictly stronger than a `test`→`feat` pair: the spec was shown
to FAIL from **five independent directions** against real builds, each with recorded output, and
**two of the plan's own arms were measured incapable of failing and replaced** (deviation 1). The
commit sequence is `test` → `ci` → `test`, with no `feat` gate. Recorded here rather than left
for a later reader to notice the missing commit type.

## Threat register outcomes

| Threat | Outcome |
|---|---|
| **T-35-28** a vacuous gate | **mitigated, and better than planned.** Five arms, all recorded in both directions; plus the discovery that two of them were themselves vacuous as designed |
| **T-35-29** vendor credential | **mitigated.** `e2e/vendor-credentials.ts` reused; no literal anywhere in the spec; both credential arms measured |
| **T-35-30** CI job runtime | **accepted as planned.** One extra spec on a browser stack already started; 12 desktop tests, ~19s measured |
| **T-35-31** CLI tag filtering | **mitigated.** Positive `--grep` only, with the reason at the step; verified 0 mobile tests enumerated under the CI command |
| **T-35-31b** padding-inclusive denominator | **mitigated and CONTROLLED.** Content box computed once in the shared helper; all four numbers on every failure; ARM E reds by exactly 64px at all three viewports and the message names the padding signature |
| **T-35-SC** package installs | **accepted.** None performed |

## Threat Flags

None. This plan adds no endpoint, input, data flow or credential handling; the one CI change
runs an existing runner against an already-started local server.

## Commits

| SHA | Type | Subject |
|---|---|---|
| `139fe252` | test | measure the four-tier width contract in a real browser |
| `1d3b00ec` | ci | run the width contract's marketing tier on every pull request |
| `e256431a` | test | give the shell tier a claim the constant cannot move |
| `9f18eeb5` | docs | make the CI step's coverage wording greppable, not just present |

## Self-Check: PASSED (after one real failure, fixed)

The self-check is recorded as it actually ran, not as a rubber stamp — it **found a defect on
its first pass** and that is the only reason it is worth running.

| Claim | Result |
|---|---|
| 3 files created/modified exist on disk | FOUND × 3 |
| 4 commits exist in git | FOUND × 4 |
| CI step present and tag-filtered | `ci-cd.yaml:573` |
| Spec imports the lib module relatively | `layout-width-contract.spec.ts:159` — `"../lib/layout-widths"` |
| #683 cited in spec AND CI step | 1 match each |
| Authoritative coverage phrase, contiguous, in both | **FAILED first** (`spec: 1, ci: 0`) → fixed in `9f18eeb5` → now `1` each, with the fail direction confirmed at `rc=1` |
