---
phase: 31-consumer-safety-and-legal-floor
plan: 18
subsystem: frontend-accessibility-ci
tags: [a11y, wcag, axe, ci-gate, falsifiability, docs-metrics, LGL-02]
requires:
  - "31-13 (the published conformance statement this plan reconciles)"
  - "31-14 (A11Y-07/A11Y-08 closure, which decides what must NOT be listed)"
  - "31-02 (jsx-a11y lint layer + the contrast literal ledger)"
  - "31-01 (@axe-core/playwright, human-gated install)"
  - "31-12/31-17 (the legal routes and the public-layout gate this extends)"
provides:
  - "frontend/e2e/public-a11y.spec.ts — per-PR WCAG 2.1 AA scan of 13 declared surfaces on both viewports"
  - "frontend/e2e/helpers/public-surface.ts — the shared stub + storefront helpers, one definition"
  - "a permanent axe instrument test proving the scanner can fail, on every run"
  - "the reconciled accessibility statement (preparedOn = the final audit date)"
  - "docs/metrics.json regenerated + the three docs that quote it corrected"
affects:
  - ".github/workflows/ci-cd.yaml — frontend-e2e now runs both browser specs"
  - "CLAUDE.md / AGENTS.md — stale lint-config claim corrected (3 sites each)"
tech-stack:
  added: []
  patterns:
    - "control-then-scan welded into one function, so no code path can scan unguarded"
    - "shared e2e helpers as a non-spec module (importing a spec would register its tests twice)"
key-files:
  created:
    - frontend/e2e/public-a11y.spec.ts
    - frontend/e2e/helpers/public-surface.ts
  modified:
    - .github/workflows/ci-cd.yaml
    - frontend/components/legal/policy-toc.tsx
    - frontend/e2e/public-layout.spec.ts
    - frontend/lib/accessibility-statement.ts
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
decisions:
  - "The non-vacuity guarantee is STRUCTURAL, not a static grep — the planned 'controls >= AxeBuilder usages' count is vacuous once the scan is factored into a helper"
  - "The one violation found (policy TOC 4.41:1 on mobile) was FIXED, not published as an eighth exception"
  - "text-contrast-below-minimum KEPT despite a clean axe run — a dynamic scan's silence about a state it never entered is not evidence"
  - "The instrument test is RETAINED every run, a recorded departure from the RESEARCH note"
metrics:
  duration: ~85 min
  completed: 2026-08-16
  tasks: 3
  commits: 6
---

# Phase 31 Plan 18: Accessibility CI Gate + Phase Reconciliation Summary

**A WCAG 2.1 AA axe scan of thirteen declared surfaces now blocks a pull request, shown to fail against a deliberately broken control in both directions; the published conformance statement was re-verified entry-by-entry against the tree; and the metrics manifest plus all three documents that quote it were reconciled after eighteen plans.**

---

## Base verification — THE WORKTREE DEFECT HIT ME

The worktree was branched from `main`, not from the phase branch. Detected exactly as briefed:

```
git rev-parse HEAD                  -> bb2ae65d8dac891cded56a6aa2c4420f4b2006f3   (main, NOT fcac272d)
ls .../31-18-PLAN.md                -> No such file or directory
git rev-list --count fcac272d..HEAD -> 0        (nothing of mine would be lost)
git rev-list --count HEAD..fcac272d -> 124      (behind by 124 commits)
git status --porcelain | wc -l      -> 0        (clean)
git merge --ff-only fcac272d        -> Updating bb2ae65d..fcac272d, 163 files changed
git rev-parse HEAD                  -> fcac272da347ea70825b18f4202e48975b45948b   OK
```

All three required artifacts present after the fast-forward: `31-18-PLAN.md`,
`frontend/lib/accessibility-statement.ts`, `frontend/components/public/public-footer.tsx`.

**6 of 8 executors in this phase have now hit this.**

---

## THE BREAK ARM — LGL-02's actual deliverable, both directions

### Arm A — a known `image-alt` violation on `/`

Committed first (`8cf11de7`, `677df7e4`), so the restore target was a committed state.
An `<img src="/favicon.ico" width="16" height="16">` with no `alt` was added after the
landing `<h1>`, then **rebuilt and the server restarted** — a rebuild alone does not change
what `next start` serves — and the break proven live in the served HTML *before* measuring:

```
$ curl -s http://localhost:3210/ | grep -o '<img src="/favicon.ico"[^>]*>'
<img src="/favicon.ico" width="16" height="16"/>
```

**FAIL DIRECTION (verbatim):**

```
  2) [desktop] > e2e/public-a11y.spec.ts:176:9 > public surfaces — WCAG 2.1 AA > / has no WCAG 2.1 AA violations

    Error: /: WCAG 2.1 AA violations (1 rule(s))

    expect(received).toBe(expected) // Object.is equality

    Expected: ""
    Received: "image-alt [critical] x1 — Images must have alternative text
          at [\"img[src$=\\\"favicon.ico\\\"]\"]"

  2 failed
    [mobile] > ... > / has no WCAG 2.1 AA violations
    [desktop] > ... > / has no WCAG 2.1 AA violations
  24 passed (54.6s)
=== playwright rc=1 ===
```

Route named, rule id named, node selector named, both viewports, non-zero exit.

**RESTORE, VERIFIED BY CONTENT** (not `git diff --stat`, which is empty either way):

```
expected = 8596828ee453408716a0057c740cb87dbe7fb2e5   (git rev-parse HEAD:frontend/app/page.tsx)
actual   = 8596828ee453408716a0057c740cb87dbe7fb2e5   (git hash-object frontend/app/page.tsx)
```

**CLEAN DIRECTION (closing arm, all three browser specs):** `80 passed (2.3m)`, `rc=0`.

### Arm B — the checkout basket seed removed

```
    Error: /shop/brixton-village-grill/checkout: no <h1> — the page did not render its own content
    expect(received).toBeGreaterThanOrEqual(expected)
    Expected: >= 1
    Received:    0
  1 failed
=== playwright rc=1 ===
```

The control fired and **the scan never ran**. Restore hash `517e5edd...` == HEAD.

### Arm C — the storefront scanned without opening the modal

```
    Received: 0
      237 |         "the dish modal is not open — a scan of the shop page underneath it "
      238 |           "would report cleanly and prove nothing about the modal"
    > 239 |       ).toBe(1)
  1 failed
=== playwright rc=1 ===
```

Restore hash `517e5edd...` == HEAD.

### Arms B/C quantified — WHAT THE ARTEFACT ACTUALLY REPORTS

The controls firing proves they work. This proves they are *necessary*:

```
ARTEFACT 1 — checkout, UNSEEDED basket
  elements inside <main> : 8
  <h1> / <form> / <input>: 0 / 0 / 0
  main text              : "Nothing to checkoutAdd items from the menu first.Browse menu"
  axe violations         : 0  [none]
  axe passes             : 22

ARTEFACT 2 — storefront, modal NOT opened (scanned as "the modal")
  [role="dialog"] count  : 0
  axe violations         : 0  [none]
  axe passes             : 23
```

**Both report a PERFECT ZERO.** Without the controls this gate would have published
"checkout: 0 WCAG 2.1 AA violations" while touching none of the checkout form — including
none of the seven autofill tokens and none of the error-announcement wiring 31-14 shipped,
which is the very surface the statement leans on when it declines to list A11Y-08.

RESEARCH recorded "one moderate violation" on the unseeded checkout and "four elements";
re-measured here both are wrong (0 violations, 8 elements). The spec's comments were
corrected to the measured figures (`b7bd1308`) rather than left quoting inherited prose.

### Arm D — the CI wiring (read the STEP, not the job colour)

The `run:` line was extracted **from the YAML itself** via `yaml.safe_load`, never retyped:

| state | run line | layout tests | a11y tests | step rc |
|---|---|---|---|---|
| CLEAN | `npx playwright test e2e/public-layout.spec.ts e2e/public-a11y.spec.ts` | 39 | **27** | 0 |
| BROKEN (edit reverted) | `npx playwright test e2e/public-layout.spec.ts` | 39 | **0** | **0** |

**The broken state exits 0.** It is green. It simply tests nothing about accessibility —
which is precisely why the job's colour is not evidence and the step must be read.
Restore verified: `c8b51ab4...` == HEAD.

### Arm E — the statement's expiry gate, re-proven against the NEW dates

`nextReviewDue` set to a past date **after** reconciliation:

```
* accessibility statement — the dates > has not expired: nextReviewDue is still in the future
* accessibility statement — the dates > schedules the next review within 12 months of the last one
    Expected: > 1786838400000
    Received:   1771200000000
Tests: 2 failed, 8 passed, 10 total
```

Restore `87751e1a...` == HEAD. Clean: **28/28**.

### Arm F — the prose-side doc gate, one digit

```
FAIL: README.md [total_logical_invocations]: doc says 3186, docs/metrics.json says 3185
rc=1
```

Names the file, the key and both values. Restore `595ec62a...` == HEAD.

---

## CI wiring — verified against the real YAML, not the plan's claim

I was told to verify rather than trust. Both facts hold:

```yaml
# .github/workflows/ci-cd.yaml
on:
  push:
    branches: [main, 'phase-*', 'phase/**']
  pull_request:
    branches: [main]
```
```yaml
# .github/workflows/e2e-nightly.yml
on:
  schedule:
    - cron: '0 2 * * *'
  workflow_dispatch:
```

**The `phase/` CI-invisibility trap does NOT apply here.** `'phase/**'` is present alongside
`'phase-*'`, and `phase/31-consumer-safety-legal-floor` matches it — so CI runs on push to the
phase branch, and this gate's first contact with CI is at that push, not deferred to PR-open.
`e2e-nightly.yml` is confirmed `schedule` + `workflow_dispatch` only, so a gate placed there
could not block a PR.

`scripts/check-gate-enforcement.sh` -> **rc=0**, 36 gates, "every gate either runs in CI or has
a declared reason it cannot."

**What I could NOT exercise:** the GitHub Actions job itself. Arm D runs the step's *command*,
not the *runner*. The paths filter, the `if:` guards and the skip-notice branch are unexercised
from here and will first execute on push. Stated plainly rather than implied — verifying the
script is not verifying the job.

---

## The audit's one real finding — a mobile-only contrast failure, FIXED

The first run was **not** clean. 4 failures, all `color-contrast`, all on the four policy
documents, **mobile only**:

```
fg=#b45309 (amber-700)  bg=#f7efe7  ratio=4.41  needed=4.5:1  fontSize=10.5pt (14px)  weight=normal
```

| route | mobile nodes | desktop |
|---|---|---|
| `/legal/privacy` | 9 | 0 |
| `/legal/cookies` | 7 | 0 |
| `/legal/retention` | 5 | 0 |
| `/legal/accessibility` | 6 | 0 |
| `/legal` | 0 (no TOC) | 0 |

**Desktop was clean on every one of them.** The failing background exists only below `lg`:
`PolicyToc`'s `<details>` carries `bg-cream-100/60`, and `lg:bg-transparent` drops it again on
desktop. A single-viewport gate would have certified this surface clean. This is the concrete
vindication of running both projects.

**Fixed** (`8cf11de7`): `text-amber-800` (#92400e, **6.23:1** on the same panel), hover to
amber-900 so the hover state stays a visible change. **No design token moved** —
`globals.css` untouched, `contrast-tokens.test.ts` 8/8 and not in this plan's diff.
The prose links in the policy bodies keep amber-700 (they sit on the page background, were
never flagged, and `privacy-page`'s "uses the amber link colour" contract still holds).

Fixed rather than published as an eighth exception: a one-token component change is strictly
better than a dated commitment to do it later.

**Gap noted, not closed:** `contrast-literals.test.ts`'s `SCAN_ROOTS` does not include
`components/legal`, so nothing was watching these classes — which is why an axe scan found
what the literal ledger could not. See the owner question below.

---

## Final audit result — 0 violations, 13 surfaces, both viewports

`26 passed (52.7s)` on the a11y spec alone; `80 passed (2.3m)` for all three browser specs
in the closing clean arm.

| surface | mobile | desktop | non-vacuity control (RE-MEASURED) |
|---|---|---|---|
| `/` | 0 | 0 | main=1, h1>=1 |
| `/shop` | 0 | 0 | **3 shop cards** |
| `/shop/brixton-village-grill` | 0 | 0 | **9 dish cards** |
| dish modal (opened) | 0 | 0 | **`[role="dialog"]` = 1** |
| `/shop/[slug]/checkout` (seeded) | 0 | 0 | **1 line item + h1 "Checkout"** |
| `/shop/signin` | 0 | 0 | main=1, h1>=1 |
| `/auth/signin` | 0 | 0 | main=1, h1>=1 |
| `/legal`, `/privacy`, `/cookies`, `/retention`, `/accessibility` | 0 | 0 | main=1, h1>=1 |
| INSTRUMENT (broken fixture) | **4** | **4** | `button-name, color-contrast, image-alt, link-name` |

The storefront resolved at runtime to a **real seeded slug** (`brixton-village-grill`) rather
than the fixture, because a backend was reachable locally — exactly the case
`resolveStorefrontPath` exists for, and the reason no slug is hardcoded.

---

## The statement — reconciled, and what I deliberately did NOT change

**Changed: `preparedOn` 2026-08-15 -> 2026-08-16 only.** Nothing else moved, and that is a
finding rather than an omission — all seven exceptions were re-verified against the tree:

| exception | verified how | verdict |
|---|---|---|
| `vendor-dashboard-not-assessed` | dashboard is outside the gate's surface list | KEEP |
| `identity-provider-registration` | third-party IdP | KEEP |
| `stripe-hosted-payment-form` | third-party card fields | KEEP |
| `storefront-no-skip-link` | skip anchor exists in `public-shell.tsx` + `auth/signin/page.tsx`, **absent from `app/shop/layout.tsx`** | KEEP — route list exact |
| `required-fields-marked-visually-only` | `name`/`email`/`phone` carry `required` (lines 850/864/878); `address1`/`city`/`postcode` (770/802/821) carry `autoComplete`+`aria-invalid` but **no `required`, no `aria-required`** | KEEP — accurate to the line |
| `text-contrast-below-minimum` | see below | KEEP |
| `registered-office-not-published` | owner decision, verbatim, 16471464 | KEEP |

**The one that needed judgement.** axe reported **zero** contrast violations on all three routes
that exception names (`/`, `/shop/[slug]/checkout`, `/auth/signin`), both viewports. Removing it
on that basis would have been **overclaiming** — the exact failure the file's own header forbids.
The 31-02 literal ledger is still live and still names those routes (checkout's `text-red-600`
at 4.49 on cream, `text-slate-400` at 2.39; hero-search's `text-slate-500` at 4.43). Those
literals render in states a page-load scan never enters — error text appears only after a
refused submit. **A dynamic scan's silence about a state it did not reach is not a clean bill
of health for that state.**

**A11Y-08 / A11Y-07 remain ABSENT**, confirmed against 31-14's summary and re-verified in
source: seven valid autofill tokens on seven user-data inputs (seven, not eight — the `notes`
textarea correctly takes none), plus `role="alert"` / `aria-invalid` / `aria-describedby`.
Neither reappears as an exception.

**No remediation date was touched.** All seven published commitments (2026-11-16 x2,
2027-02-16 x5) are exactly as ratified. `nextReviewDue` stays 2027-02-16 — still six months
from the new `preparedOn`, still inside the 12-month bound.

**jsx-a11y downgrades: NONE.** Verified independently of 31-13's claim by reading
`eslint.config.mjs`: every `jsx-a11y/*` rule is at `"error"`; the only `off` entries are
`no-explicit-any`/`react-hooks/globals` (tests) and `no-require-imports` (jest.config.js), none
of them a11y. `control-has-associated-label` is deliberately *not enabled* with a measured
reason (30 false positives), which is a documented non-enablement, not a downgrade. So no
lint-downgrade exception was added.

---

## Metrics and documents

| key | before | after |
|---|---|---|
| `playwright_blocks` | 107 | **113** |
| `playwright_specs` | 21 | **22** |
| `total_logical_invocations` | 3179 | **3185** |
| everything else (java 1713/270, jest 1230/120, go 81/11, mcp 48/8, schema 63) | — | unchanged |

Regenerated with `scripts/docs-freshness.sh --write`, **never by arithmetic** — and this change
illustrates why: `public-a11y.spec.ts` *runs* 27 tests per project but contributes only **6
literal `test(` blocks**, because eight of its route tests come from one `test()` inside a loop.

**All three docs corrected, each site individually.** README carries the figures three times in
three different phrasings (a shields.io badge URL, a Playwright bullet, a Total bullet); CLAUDE.md
and AGENTS.md carry them once each inside one long Testing sentence. A single search-and-replace
would have fixed one file and left the others silently wrong.

**A9 RESOLVED, in the reassuring direction.** RESEARCH assumed the prose regexes might be matching
nothing. Measured: after `--write`, `check-doc-metrics` went **RED naming ten specific claims
across all three documents** by key and by file. The rules do read those sentences. The earlier
discrepancy was an already-reconciled figure, not a dead rule.

**Stale lint claim corrected in BOTH files** (3 sites each), not just the one the plan named —
AGENTS.md carried identical false text, and a stale instruction steers every future agent
whichever file it reads. The legacy RC-style config does not exist (`ls` rc=2; the only RC files
under `frontend/` belong to node_modules packages). Added the recorded FlatCompat caveat.

---

## Gate results (closing clean run)

| gate | rc |
|---|---|
| `docs-freshness.sh` | 0 |
| `check-doc-metrics.sh` | 0 (37 claims, 3 docs) |
| `check-claims.sh` | **0**, not 2 (47 claims, 6 docs) |
| `check-gate-enforcement.sh` | 0 (36 gates) |
| `check-retention-enforcement.sh` | 0 (12 rows) |
| `check-geo-attribution.sh` | 0 |
| `check-no-create-extension.sh` | 0 (63 migrations) |
| `check-e2e-baseurl-contract.sh` | 0 (22 specs, 0 divergent) |
| `check-branch-behind-base.sh` | 0 — **129 ahead, 0 behind** `origin/main` |

`npx jest` **120 suites / 1230 tests / 0 failures** (baseline exactly).
`npm run lint` **rc=0** (28 pre-existing warnings; rc read, not eslint's last line, which is
the *fixable* count). `npm run build` **rc=0**, all five legal routes emitted.

**`check-runtime-freshness.sh` NOT run — and that is deliberate, not an omission.** The compose
project name derives from the directory, so it VOIDs from a worktree. It must be run from the
main checkout after the merge.

---

## Deviations from Plan

### 1. [Rule 3 — blocking] The helpers the plan said to reuse were not exported

`resolveStorefrontPath()` and `openStorefront()` are module-level functions in
`public-layout.spec.ts` with no export. Importing one spec from another **executes its module
body**, registering the layout suite's tests a second time under the importing file — the
layout gate's accounting would silently double.

Extracted to `frontend/e2e/helpers/public-surface.ts` (a plain module; Playwright's `testMatch`
is `*.spec.ts`, so it is not collected) and imported by both specs. Navigation made **relative**
so `playwright.config.ts` stays the sole base-URL authority — declaring a
`PLAYWRIGHT_BASE_URL` fallback in a non-spec file would have placed it outside
`check-e2e-baseurl-contract.sh`'s scan, silently weakening an existing gate. Verified:
`public-layout.spec.ts` **38/38**, contract gate rc=0.

### 2. [Rule 1 — bug] The mobile contrast failure

Documented in full above. Commit `8cf11de7`.

### 3. [Deliberate departure] The instrument test is retained, not deleted

RESEARCH suggested recording it once and removing it. Kept: ~0.4s per run to re-prove the
scanner can fail, which is the only thing that makes a file full of absence-assertions
evidence. Recorded as instructed.

### 4. [Strictly stronger substitution] The planned static control-count criterion is VACUOUS

The plan asked to "assert that the count of `AxeBuilder` usages does not exceed the count of
control assertions in the file". Measured on the delivered file: `AxeBuilder` appears **4**
times and control assertions far exceed it, so `4 <= N` passes — **but it passes for the wrong
reason**. Once the scan is factored into a helper (as it is), the count is 1-2 regardless of
whether any control exists, and the criterion cannot fail. It is an already-satisfied
comparison, the archetype this phase has found nine times.

Replaced with a **structural guarantee**: `scanSurface()` asserts `main === 1`, `h1 >= 1` and a
**required** per-surface control callback, and only then constructs the `AxeBuilder`. There is no
path through the file that scans a real surface unguarded. This is enforced at runtime on every
CI run rather than by a grep, and it was falsified twice (arms B and C). Both the vacuous
original and the replacement are recorded here rather than silently substituted.

### 5. [Rule 2 — missing critical] AGENTS.md carried the same false lint claim

The plan named only CLAUDE.md. AGENTS.md is in `files_modified` and carried identical text in
three places. Fixed both.

### 6. [Instrument defect, caught by its own control] My acceptance script was initially vacuous

The first version of the acceptance script used `rg`, which **does not exist in a script
subprocess** on this machine (it is an interactive shell function). Every line died
`command not found`. The presence checks failed loudly — but every **absence** check printed
`OK` from `0 == 0`, a fail-open, and would have certified `.eslintrc.json` as removed without
reading the file. Caught only because the script carried an explicit instrument control
("a token that MUST be absent"), which failed. Rewritten against `/usr/bin/grep` by absolute
path (a real binary, not `.gitignore`-aware). A second bug then surfaced — `grep -c` prints `0`
*and* exits 1, so a `|| echo 0` fallback emitted `"0\n0"` and every absence check compared
`"0\n0"` against `"0"`. Both fixed; final run: **all checks pass, instrument control green in
both directions.**

---

## Owner question — ONE, and I have not acted on it

**`contrast-literals.test.ts` cannot see the legal pages it is supposed to protect.**

Its `SCAN_ROOTS` is `app/page.tsx`, `app/shop`, `app/auth/signin`, `components/public`,
`components/storefront`, `components/marketing`. It does **not** include `components/legal` or
`app/legal` — yet all five `/legal/*` routes are declared in-scope surfaces in the published
conformance statement, and `/legal/accessibility` **is that statement**.

That blind spot is why the 4.41:1 failure survived to this plan: the literal ledger structurally
could not see the class, and only a browser scan at 390px found it.

I did **not** widen `SCAN_ROOTS`. It is not in my `files_modified`, and adding two roots would
likely surface further literals requiring new ledger entries — which is a contrast-debt decision
with published-commitment consequences (the `text-contrast-below-minimum` exception is dated
2027-02-16), not a mechanical edit.

**Question: should `components/legal` and `app/legal` be added to the literal ledger's scan
roots, and any resulting entries folded into the existing dated exception?**

---

## Threat model dispositions

| Threat ID | Disposition | Evidence |
|---|---|---|
| T-31-18-01 | Discharged | Arm A run; `image-alt [critical] x1` in the fail direction, `rc=0`/80 passed clean; restore verified by `git hash-object` |
| T-31-18-02 | Discharged | Controls welded into `scanSurface`; arms B and C both fired; artefacts quantified at **0 violations each** |
| T-31-18-03 | Discharged | Spec + run line in one commit (`677df7e4`); arm D shows a11y tests 27 -> **0** at **step rc 0** |
| T-31-18-04 | Discharged | Seven exceptions re-verified line-by-line; A11Y-08/07 confirmed absent; contrast exception KEPT despite a clean scan |
| T-31-18-05 | Discharged | `contrast-tokens.test.ts` 8/8 and absent from `git diff fcac272d..HEAD`; fix was a utility class, no token moved |
| T-31-18-06 | Discharged | `storefront-dish-modal-a11y.spec.ts` **16/16** |
| T-31-18-07 | Discharged | Both docs-freshness halves + claim engine green; prose gate shown to fire on one digit |
| T-31-18-08 | Discharged | Stale lint claim removed from CLAUDE.md **and** AGENTS.md; FlatCompat caveat recorded |
| T-31-18-SC | Accepted | No package installed |

---

## Commits

| hash | message |
|---|---|
| `8cf11de7` | fix(31-18): the policy TOC links meet AA on mobile, where the panel is |
| `677df7e4` | feat(31-18): an accessibility violation now fails a pull request (LGL-02) |
| `b7bd1308` | docs(31-18): the spec quotes the artefact figures this plan MEASURED |
| `0a69bae5` | docs(31-18): reconcile the conformance statement against the final audit |
| `327534e6` | docs(31-18): regenerate the metrics manifest and correct the docs that quote it |
| (final) | docs(31-18): complete the accessibility CI gate plan |

---

## Known Stubs

None.

---

## Self-Check: PASSED

All 12 claimed files exist on disk; all 5 claimed commits resolve via `git cat-file -e`.
The commit check was proven capable of failing by running it against a bogus hash
(`deadbee1`), which correctly did not resolve — without that control, a check that resolves
everything and a check that resolves nothing print the same "FOUND" for real hashes.
