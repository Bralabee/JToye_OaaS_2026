---
quick_id: 260803-rct
slug: e2e-instrument-contract
date: 2026-08-03
status: complete
issues_closed: [505, 503, 305]
prs: [512, 513]
---

# Summary: the E2E instruments, and making their lessons executable

## What shipped

Two PRs, deliberately split by CI lane — which is the recommendation this task
was also asked to act on, applied to its own work.

| PR | lane | contents | CI cost |
|---|---|---|---|
| **#513** | cheap | #505, #503, #305, three new scripts, docs | ~3 min (suite path-skips) |
| **#512** | expensive | `integrationTest` fork concurrency | the 45-min suite — it *is* the measurement |

## The three issues

- **#505** — `vendor-refund-flow.spec.ts` defaulted to `:3100`, which nothing
  publishes. `page.goto` failed before the spec's own skip condition was ever
  evaluated, so four blocks the repo recorded as *deliberate skips* were
  **failures**, and `check-e2e-skip-budget` was reasoning about a membership that
  did not hold. Root cause was a **stale comment** in `playwright.config.ts`
  ("Dev env uses port 3100 (MCP server holds 3000)" — both halves false) that had
  propagated into nine files' prose before one turned it into code.
- **#503** — the `mobile` project set `isMobile` with no `hasTouch`, so Chromium
  reported `pointer: fine`; the mobile suite was blind by construction to the
  ungated `hover:` it existed to catch. Both halves fixed.
- **#305** — **already fixed**, and both its diagnosis and its proposed fix were
  wrong. Closed with evidence.

## What makes the lessons reusable

Prose rules have failed here before where executable checks have not, so each
lesson became a script that fails loudly, and each was run against a deliberately
broken tree before being trusted.

| artifact | asserts | falsified by |
|---|---|---|
| `scripts/check-e2e-baseurl-contract.sh` | every `PLAYWRIGHT_BASE_URL` fallback equals the config's | single-line divergence → 1 · **multi-line** divergence → 1 (a line-oriented grep sees nothing there) · config authority removed → **2 VOID** |
| `scripts/check-playwright-mobile-contract.sh` | `isMobile` ⇒ `hasTouch` or a device descriptor | `hasTouch` removed → 1 · descriptor instead → 0 · **`hasTouch` present only as a comment → 1** · array unparseable → 2 VOID |
| `frontend/e2e/mobile-instrument-contract.spec.ts` | the *emulated* pointer state and the *served* stylesheet | carries a vacuity guard: an unreadable stylesheet yields 0 findings, which would otherwise read as a pass |
| `scripts/ci-lane-cost.sh` | which CI lane a diff falls in | both lanes classified · filter block renamed → 2 VOID · caught its own over-greedy parse (9 patterns where the workflow declares 8) |

Every arm was bracketed clean → arms → **clean again**, with restores verified by
`git hash-object` rather than by `git diff --stat`.

## What went wrong, and is worth carrying forward

**My own verification was wrong three times in a row**, all three saying the
Tailwind fix was inert when it works:

1. `@media\(hover:hover\)` matched nothing — the pattern had no space. Evidence
   about the *pattern*, not the CSS.
2. `rg -c` read as an occurrence count. It counts **lines**, and minified CSS is
   nearly one line.
3. A file labelled "before the change" had been fetched *after* the rebuild — the
   baseline was the treatment, so a real difference read as byte-identical.

Settled by a postcss walk **validated first against a known-different pair**
(Tailwind CLI flag on/off → 65/0 and 0/65), then pointed at the question.
**A text search cannot answer a question about nesting**, and "is this rule inside
a media query" is a nesting question.

**A build was corrupted by switching branches under it.** `sync-runtime.sh` was
running when the tree moved to another branch; the image was tagged anyway and
`check-runtime-freshness` reported **FRESH**, because it compares timestamps. A
structural gate green over an untrustworthy artifact — caught only by reading the
CSS out of the running container.

**`pkill -f` killed the shell that issued it**, matching its own command line —
the same self-match trap already recorded for `pgrep -f` wait-loops. Bracket
self-exclusion (`[p]laywright`) is needed for `pkill` too, not just `pgrep`.

**The skip-budget gate caught a principle violation, not just a number.** The
first version of the new spec used `test.skip(project !== "mobile")`, which
enumerates under the other project and skips — a permanent "not applicable here"
entry, which `playwright.config.ts` states a skip must never mean. Adding ALLOW
entries would have declared the forbidden meaning legitimate; the fix extended
the existing `grepInvert` mechanism symmetrically instead.

## A fourth defect, found only because the VOID gate got a fresh report

Once `check-e2e-skip-budget` could run, it named a test skipping with "No DRAFT
order seeded" **while six DRAFT orders existed**. Two more instrument defects:

1. The seeder's upsert refreshed `updated_at` but **not** `created_at`. The orders
   list sorts `createdAt desc` at `PAGE_SIZE = 20`; the spec reads page 1 only. The
   fixture sat at **rank 21 of 156** — one row past the page — and every suite run
   creates orders that push it further down. It passed in one run and skipped in
   the next with no code change between them.
2. The seeder verified `count(*) DRAFT >= 1` **anywhere in the table**, then printed
   "can now assert non-vacuously". Its own comment said "verify by the SPECS' OWN
   predicates, not by row counts", and then did exactly that.

Falsified: fixture off page 1 → old check **6 (passes)**, new check **0 (fails)**.

## Verification (final)

- Playwright: **127 passed / 8 skipped / 0 failed** — skips now exactly the
  declared budget — against a pre-fix baseline of 119 / 6 / **6 failed**.
- **Full gate sweep: 22 gates, 22 × rc=0, 0 fail, 0 VOID.**
- Jest: 74 suites, 565 tests, 0 failures.
- Runtime rebuilt from this branch: 4/4 FRESH; the running container serves
  **65 gated / 0 ungated** hover utilities.

## The CI experiment (#512), measured — TWO samples per side

| | baseline #509 (1 fork) | #512 (2 forks) |
|---|---|---|
| sample 1 | 45m52s | 31m27s |
| sample 2 | **45m44s** | **27m13s** |
| mean | **45m48s** | **29m20s** |
| spread | **8s** | 4m14s |
| tests / failures / ignored | 479 / 0 / 6 (both) | 479 / 0 / 6 (both) |

**Mean delta −16m28s (−36.0%); the ranges do not overlap**, so the floor is
worst-experiment vs best-baseline = **−14m17s (−31.2%)**.

Mechanism confirmed, and the CONTROL confirmed from the baseline's own source
rather than assumed: `git show 7fad1c9:core-java/build.gradle.kts` has
`setForkEvery(4)` and **no `maxParallelForks`** (Gradle default 1). The baseline
log carries no `doFirst` line because that logging ships in #512, so reading the
control from the tree is the only honest route. Experiment log:
`nproc: 4` · `availableProcessors=4, maxParallelForks=2, forkEvery=4` — which also
confirms the premise, since the old `/4` divisor gives `4/4 = 1`.

The baseline being stable to **8 seconds** across runs 3.5h apart is what makes
the delta trustworthy. The experiment's larger spread is expected: two forks
contending for four cores introduces scheduling variance a serial run lacks.

Still not established: both samples are re-executions of the SAME commit, so this
measures runner-to-runner variance, not commit-to-commit. And it is not the local
2.6x — that was 4 forks across 16 cores, this is 2 sharing 4.
