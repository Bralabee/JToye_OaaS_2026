# Phase 34 — deferred items

Out-of-scope discoveries made while executing. Each entry says what was measured,
why it was not fixed here, and what would close it.

---

## D-34-05-01 — the same unfalsifiable overflow shape survives in two other places

**Found during:** plan 34-05, Task 1 break arm A (2026-08-28).

**What was measured.** Under `isMobile: true` Chromium emulates a phone LAYOUT
viewport: content wider than the device width makes the page zoom out to fit, and
`window.innerWidth` GROWS to match the content. So

```
expect(docScrollWidth).toBeLessThanOrEqual(window.innerWidth + 1)
```

compares a number against itself and **cannot go red**. Proven on the live Compose
stack by appending a deliberate 1200px-wide div to `/dashboard/kitchen` at a 375px
pin, before the read:

```
{"docScrollWidth":1200,"bodyScrollWidth":1200,"innerWidth":1200,
 "htmlOverflowX":"visible","bodyOverflowX":"visible","injectedWidth":1200}
```

The sweep reported `1 passed`, rc=0, over an 825px overflow on a 375px phone.

**Fixed in 34-05:** only the new eleven-route block, which now compares against
`page.viewportSize()!.width` (the configured width, which page content cannot move)
and additionally asserts the layout viewport did not widen. Same injection now
reads `expected: 376 / received: 1200`, rc=1.

**Still carrying the vacuous shape — NOT fixed here:**

1. `frontend/e2e/dashboard-mobile.spec.ts`, the single-route MOBL-01 375px block:
   `expect(geom.docScrollWidth).toBeLessThanOrEqual(geom.viewportWidth + 1)` where
   `viewportWidth` is `window.innerWidth`. Not touched because plan 34-05 says in
   terms: do not change the 390px block or any existing assertion. Its OTHER
   assertions (the 56px top-bar geometry, the escaping-element list) are genuinely
   falsifiable and were measured red on main — only this one line is affected.
2. `frontend/e2e/public-layout.spec.ts:110-113`, `horizontalOverflow()` =
   `document.documentElement.scrollWidth - window.innerWidth`, asserted
   `<= 1` at three sites (`:225`, `:248`, `:380`). The same mechanism applies
   **only under the mobile project** (`isMobile: true`); under the desktop project
   `innerWidth` is a fixed 1440 and the assertion is live. NOT verified in the fail
   direction — that is the work, and it should not be assumed either way.

**What would close it:** run the 1200px-div break arm against each site above,
record both directions, and switch the yardstick to `page.viewportSize()` wherever
the arm shows green. Small, mechanical, and outside 34-05's `files_modified`.

**Why it was not done here:** `frontend/e2e/public-layout.spec.ts` is not in this
plan's files, and the MOBL-01 line is explicitly excluded by the plan's action text.
Both are recorded in `dashboard-mobile.spec.ts`'s own docblock so the next reader of
that file cannot miss it.

---

## From plan 34-07: check-gate-enforcement.sh cannot tell a `run:` line from prose

**Found:** 2026-08-28, while running the wiring arm for the new SSR-coverage gate.

**Measured, both directions.** `scripts/check-gate-enforcement.sh` decides "is this gate
wired?" by searching `.github/workflows/*` for the script's filename, and it does **not**
strip comments. With the `ops-contracts` step for `check-ssr-coverage-contract.sh` deleted
and only a COMMENT in the `frontend-e2e` job naming the file, the meta-gate reported:

```
  gates     : 37
  exempt    : 6 declared
PASS: every gate either runs in CI or has a declared reason it cannot.
rc=0
```

With the same step deleted and no comment naming the file, it correctly reported
`FAIL: 1 gate(s) are referenced by no workflow`, rc=1.

**Why this matters beyond one comment.** The workflow files are heavily commented and
several comments name gates — `check-runtime-freshness.sh` is discussed at length in the
`ops-contracts` header, and the retention and pentest steps both explain why they must
never gain a `gate-enforcement.conf` entry. Any gate whose name appears in such a comment
is exempt from the meta-gate's detection without anyone deciding that. It is the recorded
"a gate satisfied by its own prose" class, in the one place that is supposed to catch it.

**What 34-07 did instead:** removed the script's filename from its own comment, so the only
occurrence in the workflow is the `run:` line. That fixes the instance, not the class.

**What would close it:** strip `#` comments from each workflow file before the by-name
search in `check-gate-enforcement.sh`, then re-measure every gate's wired/exempt verdict —
a gate currently counted as wired only by a comment would flip to FAIL and needs a real
step or a reasoned exemption. Run the corpus both directions before and after.

**Why it was not done here:** `scripts/check-gate-enforcement.sh` is not in plan 34-07's
`files_modified`, and the change re-classifies all 37 gates — an out-of-scope edit to a
shared meta-gate that could red the build for reasons unrelated to this plan.

---

## From plan 34-07: docs/metrics.json is behind wave 1 (PRE-EXISTING, ALREADY OWNED by 34-10)

**Found:** 2026-08-28, running `scripts/docs-freshness.sh` as a side-check during 34-07.

**Not a new finding — already decided.** `34-01-SUMMARY.md` records it as a key decision in
terms: *"docs/metrics.json deliberately NOT regenerated — plan 34-10 is its single writer.
This leaves the docs-freshness gate RED on the branch until 34-10 lands."* This entry adds
only the measured numbers and confirms the red is not 34-07's, so a reader of this file does
not re-diagnose it. **Do not fix it here or in 34-08/34-09: single-writer is the point.**

`scripts/docs-freshness.sh` exits **1** on the wave-1-complete base (8285d6f5) and on every
34-07 commit. It is NOT caused by 34-07: the only files this plan changed are
`scripts/check-ssr-coverage-contract.sh`, `scripts/gates/ssr-routes.conf` and
`.github/workflows/ci-cd.yaml`, none of which contains a test block.

| metric | docs/metrics.json (committed) | tree measures now |
|---|---:|---:|
| jest_blocks | 1230 | 1272 |
| jest_files | 120 | 124 |
| playwright_blocks | 113 | 120 |
| playwright_specs | 22 | 25 |
| total_logical_invocations | 3188 | 3237 |

The new specs and tests from plans 34-01 through 34-06 landed without the manifest being
regenerated, so BOTH docs gates are currently red: `docs-freshness.sh` (tree → manifest)
and, consequently, `check-doc-metrics.sh` (prose in CLAUDE.md / AGENTS.md / README.md →
manifest), since the prose still quotes the 3188 line verbatim.

**What would close it:** `scripts/docs-freshness.sh --write`, then update the prose
sentence in CLAUDE.md, AGENTS.md and README.md to the regenerated figures. Never compute
the new numbers arithmetically — the counter greps literal `it(` / `test(`.

**Why it was not done here:** it is not 34-07's defect, it is not in its `files_modified`,
and 34-10 is the declared single writer of that manifest. Regenerating from any other plan
would be re-broken by the next plan's specs and would take the single-writer property with
it.
