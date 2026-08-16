---
phase: 31-consumer-safety-and-legal-floor
plan: 12
subsystem: frontend-legal
tags: [legal, gdpr, retention, a11y, mobile, gates, falsifiability]
status: COMPLETE — both tasks; 8 break arms run in both directions; one owner question returned unanswered
requires:
  - "31-06 (docs/retention-manifest.json — the source of truth and the tree->manifest gate)"
  - "31-08 (PolicyPage / PolicySection / resolveControllerContact)"
provides:
  - "/legal/retention — the published 12-row schedule (LGL-01)"
  - "RetentionTable — the first table in this repo that FITS at 375px with no duplicated DOM"
  - "4 claims.manifest rule rows closing the manifest->prose half of D-08, with no new script"
  - "A field-by-field transcription gate covering the 8 rows no integer rule can hold"
affects:
  - "31-17 (footer Legal column + end-to-end reachability of /legal/retention)"
  - "31-18 (browser a11y gate — /legal/retention is now a scannable surface)"
  - "orchestrator (docs/metrics.json + prose-count reconciliation on the merged tree)"
tech-stack:
  added: []
  patterns:
    - "Table made to FIT rather than made to scroll: cells wrap, padding tightens below sm, overflow-wrap:anywhere breaks long tokens — neither repo anti-analog copied"
    - "Category-named period constants so a line-based PCRE can anchor per row"
    - "Non-vacuity control asserted BEFORE every axe scan and every fit measurement"
key-files:
  created:
    - frontend/components/legal/retention-table.tsx
    - frontend/components/legal/__tests__/retention-table.a11y.test.tsx
    - frontend/app/legal/retention/page.tsx
  modified:
    - scripts/gates/claims.manifest
    - frontend/e2e/public-layout.spec.ts
    - docs/metrics.json
decisions:
  - "Rows are TRANSCRIBED, not imported — forced by a measured Turbopack module-resolution failure, not chosen"
  - "4 rule rows, not 12: exactly 4 manifest rows publish an integer; the other 8 are held by the jest deep comparison"
  - "caption-top over shadcn's caption-bottom default — a caption met before the data orients the reader"
  - "The table stays inside PolicyPage's 68ch column; max-w-4xl is not exercised and the reason is recorded"
metrics:
  tasks: 2
  commits: 3
  tests-added: 13 (11 jest blocks + 2 playwright blocks)
  break-arms-run: 8
---

# Phase 31 Plan 12: Published Retention Schedule + the Manifest→Prose Gate — Summary

`/legal/retention` publishes all 12 rows of `docs/retention-manifest.json` as one real table that
measures 341px inside a 341px region on a 375px phone, and every integer it publishes is tied back to
the manifest by four rows in the existing claim engine — no new script, no `ci-cd.yaml` edit.

## The three situations named in Task 2 — which one applies

**Situation 1: the page carries the numbers as literal text.** This was not a preference; it was
forced, and the plan's preferred option was tried first and measured failing.

Importing the manifest would make drift structurally impossible — one copy, no gate needed. The
attempt is recorded because the failure is the whole justification for the gate:

```
./app/importprobe/page.tsx:1:1
Module not found: Can't resolve '../../../docs/retention-manifest.json'
> 1 | import manifest from "../../../docs/retention-manifest.json"
```

The path is correct — `ls ../../../docs/retention-manifest.json` from `frontend/app/importprobe/`
resolves to the real 14,247-byte file (`rc=0`). Turbopack refuses to resolve modules outside the
`frontend/` project root. **Jest's resolver has no such restriction** and read the same path happily
(`ROWS= 12`), and that asymmetry is what the design exploits: the page transcribes, and the test
reads the manifest directly and holds the transcription to it.

So the rows ARE a second copy of a legally operative document, and two independent things prevent
drift:

| Layer | Covers | Runs |
|---|---|---|
| 4 `claims.manifest` rule rows | the 4 published integers | `docs-freshness.yml`, every PR, even when the frontend suite does not run |
| `retention-table.a11y.test.tsx` field-by-field comparison | **all 12 rows, every field** — category, detail, period, lawful basis, enforcement, and the row count | the jest suite |

**4 rule rows, and that number is a stated fact rather than a convenience.** Exactly 4 of the 12
manifest rows carry a `claim_key`, because exactly 4 publish an integer. The other 8 publish prose
(`Kept indefinitely - deliberately never deleted`, `For as long as the law requires`), which an
engine whose only shapes are `int` and `semver` cannot hold. That is precisely why the jest deep
comparison exists — without it, every non-numeric word on a legally operative page could drift with
nothing noticing.

`259200000` ms never reaches a rule: R-3 publishes `72 hours`, and the unit conversion stays owned by
31-06's script, as the plan requires.

## Break arms — all 8 run, both directions, clean arm re-run LAST

Bracketed clean → arms → clean. Both tasks were committed BEFORE any arm, so every restore came from
a committed state, and **every restore was verified by `git hash-object`**, never `git diff --stat`.

| # | Arm | Break | Real output | Clean |
|---|---|---|---|---|
| 1 | **M-1** (the headline) | delete the published `24 hours` sentence | `FAIL: … [R-1 abandoned checkouts]: rule matched NOTHING — the claim was removed or reworded` · `claims: 3 compared` · **rc=1** | rc=0, 4 compared |
| 2 | value drift | `24 hours` → `48 hours` | `FAIL: … claims '48', retention says '24'` · **rc=1** | rc=0 |
| 2b | value drift, 2nd layer | same break, jest | `Expected substring: "24 hours" / Received: "…48 hours…"` · 2 failed | 11 passed |
| 3 | the fit | add `min-w-[640px]` (the exact anti-analog) | `Error: retention table overflows its region at 375px (scrollWidth 393 > clientWidth 359)` | 341 ≤ 341 |
| 4 | **fit is not vacuous** | remove the table, keep the region | control fires: `the retention table did not render …`; and the fit alone reports **`scrollWidth 341, clientWidth 341 → PASSES trivially`** | table present |
| 5 | drop a rendered row | delete R-13 | `Expected: 12 / Received: 11` · 3 failed — **axe still passed** | 12 = 12 |
| 6 | colour-only enforcement | strip the word from the badge | jest `Expected: "Automated" / Received: ""`; browser `Received array: ["Automated","Operational"]` vs `""` — **axe still passed** | words present |
| 7 | duplicate DOM | add `hidden sm:block` | forbidden check `count=1 PRESENT`, `OVERALL rc=1` | count=0, rc=0 |
| 8 | empty row list | feed the main axe test `rows={[]}` | control fires at line 101 `Expected: > 1 / Received: 0` — **before** the axe call on line 104 | 11 passed |

**Arm 4 is the most important result in this plan.** With the table removed, the fit assertion
reports `341 / 341` — *byte-identical to the correct page*. The fit check alone cannot tell a
conforming table from no table at all. Only the non-vacuity control distinguishes them, which is why
it is asserted first in both browser tests.

Arms 5, 6 and 8 each show **axe passing over a defect**: a dropped row, a colour-only compliance
signal, and an empty table are all invisible to it. The permanent empty-table test in the suite keeps
that fact in the repo rather than in this document.

## Measured, in a real browser, against this worktree's own build

The 375px claim was measured, not asserted. Port 3000 was already serving **another tree** (it 404s
`/legal/retention`), so a dedicated server was run from this worktree's own production build on
:3210 and its identity confirmed by content before any measurement.

```
viewport            : 375 x 667      (set EXPLICITLY — the mobile project is 390x844)
region.scrollWidth  : 341
region.clientWidth  : 341
table.scrollWidth   : 341
fits (scroll<=client): true
document overflow px: 0
rows (incl header)  : 13   body rows: 12   captions: 1
```

Playwright: **4 passed** (2 blocks × mobile + desktop projects).

### A runtime-parity trap caught mid-run — worth recording

The first rebuild/restart helper used `pkill -f "next start -p 321[0]"`. It **never matched**: the
running process's cmdline is `next-server (v16.2.12)`, not the npx invocation. The old server
survived, the new one could not bind, and Arm 4's first measurement was taken against a **stale
runtime** — the served HTML still contained a table the source had already removed. Nothing in the
exit codes said so; `server READY (200)` was printed on every run. It was caught only by checking the
served HTML **by content**, and fixed by killing the PID that actually holds the port and asserting
the port is free before rebuilding. Arm 3 was unaffected (its break was content-verified in the
runtime before measuring), and every later arm re-verified content first.

Second instrument note: `grep -c` counts *lines*, and this HTML is minified onto few lines, so an
early "1 table tag" reading was a fact about line count, not occurrences. Re-measured with
`grep -o … | wc -l`.

Third: the self-check below first reported **`MISSING: commit 63c3e945`** for two commits that
demonstrably exist. The check was `git log --oneline --all | grep -q "$h"` under `set -o pipefail` —
`grep -q` exits on the first match, `git log` takes SIGPIPE → 141, and the pipeline reports failure
*because* it matched. Fixed with a here-string over captured output. Three instrument defects and
zero product defects in this run; each was a false negative that would have read as a real finding.

## The comment-satisfies-its-own-grep trap, eighth instance

The first version of `retention-table.tsx` named the anti-analog class strings in its header comment
to explain why they are forbidden. The acceptance check is a literal `grep -F` for exactly those
strings, so it reported:

```
min-w-[            count=2  PRESENT
whitespace-nowrap  count=1  PRESENT
```

on a file whose table contained none of them. A check that can never pass is the mirror image of one
that can never fail. The comment now describes those patterns in prose and says why; the file records
the reason so it is not "fixed" back. Arms 3 and 7 then proved the check still fires on real
occurrences (`count=0 → 1`).

Related, and stated rather than glossed: the *required*-pattern greps count comment mentions too
(`role="region"` count=2, `<table` count=2), so a file could satisfy them from a comment alone. Those
greps are a weak proxy; **the real check is the rendered-DOM assertions** in the jest test
(`getByRole("region", { name })`, `toHaveAttribute("tabindex","0")`, `scope` on every header), which
cannot be satisfied by prose.

## The table, and why neither existing treatment was copied

Both repo precedents violate the S2a contract, so this is genuinely new markup:

- `business-model-guide.tsx:227` pins a 640px minimum inside `overflow-x-auto` — that *guarantees*
  the 375px scrollbar rather than avoiding it. Arm 3 reproduced it: `scrollWidth 393 > 359`.
- `dashboard/webhooks/page.tsx:303` hides the table below `sm` and renders a parallel card list —
  duplicated DOM, filed twice as a product bug (#556, #593).

What makes it fit: cells wrap, horizontal padding tightens below `sm`, and `overflow-wrap: anywhere`
lets the long unbreakable tokens in the detail column (`jtoye-customer-refresh`, `jtoye-track-email`)
break instead of forcing the table's min-content width past the viewport. Those tokens were the
actual overflow mechanism, not a hypothetical one.

`caption-top` chosen deliberately over shadcn's `caption-bottom` default: a caption met before the
data orients a reader looking for one row.

**`max-w-4xl` is not exercised, and that is a decision rather than an oversight.** The UI-SPEC permits
the table region to widen ("may"), on the stated grounds that a 4-column table inside 68ch "forces a
scrollbar at desktop widths for no reason". That premise does not hold here — the table fits at
375px, so it fits at every width above it (measured 0px document overflow). Widening would also
collide with the sticky ToC rail `PolicyPage` places beside the prose at `lg`, and breaking out of
the 68ch column would require editing `policy-page.tsx`, which belongs to 31-08 and is outside this
plan's `files_modified`. Adding a `max-w-4xl` class that the parent already caps would be a no-op
worn as compliance.

## Verification (clean arm, run last)

| Check | Result |
|---|---|
| `npx jest` (full) | **107 suites / 1019 tests, 0 failures** (baseline 106 / 1008 → +1 suite, +11 tests, no regressions) |
| `npm run build` | rc=0 |
| `npm run lint` | rc=0, **0 errors**, 28 warnings — all pre-existing, none in files this plan touched |
| Playwright `-g "retention"` | 4 passed (mobile + desktop) |
| retention claims rows (isolated) | `PASS: all 4 claim(s) across 1 doc(s)` · rc=0 |
| `scripts/docs-freshness.sh` | rc=0 (2964) |
| forbidden/required pattern sweep | `OVERALL rc=0` |
| `.github/workflows/ci-cd.yaml` modified | **no** |
| new `check-*.sh` created | **no** |

## FOR THE ORCHESTRATOR — merge-gate items

1. **`bash scripts/check-claims.sh` exits 1 in this worktree, and the cause is entirely outside this
   plan.** All 13 failures are metrics-prose rows in `README.md`, `CLAUDE.md` and `AGENTS.md`
   (`claims '2951', metrics says '2964'` and siblings). I regenerated `docs/metrics.json` as
   instructed and deliberately did **not** touch those three files. **Zero retention rows fail** —
   proven by re-running the gate against a manifest containing only the retention block, extracted
   from the shipped file rather than retyped: `PASS: all 4 claim(s) … rc=0`.

   On the merged tree, run `scripts/docs-freshness.sh --write` and then update the prose counts in
   those three files. **Do not reconcile by arithmetic** — regenerate, then re-run `check-claims.sh`.

2. **`docs/metrics.json` will conflict with every sibling that added tests.** My worktree computes
   `jest_blocks 1019 / jest_files 107 / playwright_blocks 103 / total 2964`, which is correct for
   this worktree and wrong for the merged tree, because a worktree cannot see its siblings' tests.
   Take any resolution and regenerate; the file is generated, so the conflict content is disposable.
   My contribution is **+11 jest blocks, +1 jest file, +2 playwright blocks**.

3. **`/legal/retention` links sideways to `/legal/privacy`, `/legal/cookies` and
   `/legal/accessibility`** (UI-SPEC S2 cross-links). Those routes are owned by 31-11 and 31-13 and
   do not exist in this worktree, so they 404 here. Expected — same situation `app/legal/page.tsx`
   already records for its own index links; resolves on merge.

4. `frontend/app/legal/page.tsx` was **not** edited, as instructed; its index already links this route.

## OWNER QUESTION — returned unanswered, not guessed

**R-9 "Order and payment records" publishes no number, and I did not invent one.**

The manifest is explicit that this is deliberate: *"NO NUMBER IS PUBLISHED HERE ON PURPOSE. A
statutory figure would be a legal position, not a measurement."* The page therefore says "For as long
as the law requires" and explains in "Periods we do not publish as a number" why no figure appears.

That is the correct engineering default, but it leaves a real gap in a legally operative document: a
consumer asking "how long do you keep my order?" gets no answer. **Publishing the actual UK statutory
retention period (commonly cited as 6 years for tax and accounting records) is a legal choice that
needs adviser confirmation, and it is not recorded anywhere in the manifest — so it was not guessed.**

If the owner obtains adviser confirmation, the change is small and the gate is already in place: add
a flat integer claim key to `docs/retention-manifest.json`, give R-9 a `claim_key`, publish it as a
named constant beside the other four, and add a fifth rule row. Note that R-9 is `Operational` with
**no enforcement path in this repository at all** — nothing deletes these records — so a published
number would be a promise about process, not a measurement, and 31-06's tree→manifest gate could not
hold it.

A second, smaller one: R-5's period is set by the identity provider's realm configuration, which this
repository does not own. It is described rather than numbered, for the same reason, and is correctly
marked `Operational`.

## Self-Check: PASSED

- `frontend/components/legal/retention-table.tsx` — FOUND
- `frontend/components/legal/__tests__/retention-table.a11y.test.tsx` — FOUND
- `frontend/app/legal/retention/page.tsx` — FOUND
- `scripts/gates/claims.manifest` — FOUND (4 retention rule rows + 1 source row, tab-separated, verified with `cat -A`)
- `frontend/e2e/public-layout.spec.ts` — FOUND (2 new blocks)
- commits `63c3e945`, `195c459d` — FOUND
