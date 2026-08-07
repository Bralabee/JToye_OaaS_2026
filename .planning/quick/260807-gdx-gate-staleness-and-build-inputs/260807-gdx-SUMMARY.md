---
quick_id: 260807-gdx
slug: gate-staleness-and-build-inputs
date: 2026-08-07
branch: fix/gate-staleness-and-build-inputs
status: complete
---

# Summary: two gates were measuring the wrong thing, and both taxed every merge

Neither gate was wrong about its *purpose*. Each was answering a cheaper question
than the one it advertised, and the difference only showed up as recurring cost:

| gate | advertised question | question it actually asked | cost |
|---|---|---|---|
| `check-e2e-skip-budget` | does this report describe the specs on disk? | were the specs *written* after the report? (mtime) | VOID after every merge touching a spec; ~6.5 min suite re-run to clear |
| `check-runtime-freshness` | can the running image differ from the tree? | did any file in the build context change? | DRIFT + a prescribed rebuild for a commit that cannot change the artifact |

## 1. `check-e2e-skip-budget` — mtime → content digest

`find frontend/e2e frontend/playwright.config.ts -newer "$REPORT"` reports a
difference whenever git **rewrites** a file. `checkout`, `pull`, `merge` and
`stash pop` all do that with identical bytes, so the gate VOIDed after every merge
touching a spec — including the merge of the change the report was produced from.
HANDOFF.md carried a standing note telling readers to budget ~6.5 minutes for it.

Replaced with content:

- `scripts/e2e-spec-digest.sh` — sha256 over `<relpath>\t<git hash-object>` for
  every file under `frontend/e2e` plus `playwright.config.ts`. **Working-tree**
  bytes, not the index: a report produced from a dirty tree must not be certified
  against content that was never run.
- `frontend/playwright.config.ts` stamps it into `config.metadata.specDigest` at
  run time, so every producer records it with no extra step — CI and human alike.
- The gate recomputes and compares. Absent, sentinel, or mismatched ⇒ **VOID**.
  The gate cannot be satisfied by omitting the field it checks.

`--from-nightly` fetches the last successful nightly's report artifact rather than
re-running 20 minutes of suite. It is **not** a bypass — the downloaded report
faces the same digest check, so a nightly that ran on a different tree VOIDs.

### Arms — digest helper

| arm | expected | result |
|---|---|---|
| baseline, run twice | identical | `22992bb0…` both |
| `touch` a spec (mtime only) | digest **unchanged** | unchanged ✅ |
| append one comment line | digest **moves** | `22992bb0…` → `5fb619d5…` ✅ |
| rename a spec, bytes identical | digest **moves** | → `a6b3b5c7…` ✅ |
| `E2E_SPEC_DIR` missing | rc=2 VOID | rc=2 ✅ |
| `E2E_SPEC_DIR` empty | rc=2 VOID (never a constant digest) | rc=2 ✅ |
| closing arm | back to baseline | `22992bb0…` ✅ |

### Arms — gate

| report | expected | result |
|---|---|---|
| no `specDigest` | VOID | rc=2 ✅ |
| `specDigest: UNAVAILABLE` | VOID | rc=2 ✅ |
| wrong digest | VOID | rc=2 ✅ |
| matching digest | PASS | rc=0, `8 skip(s) … budget 8` ✅ |

### The decisive arm — old vs new on one tree

Every spec `touch`ed, bytes provably unchanged:

```
OLD (mtime)   would VOID: 'report is OLDER than frontend/playwright.config.ts'
NEW (content) PASS: all 8 skip(s) are declared and within the budget of 8.   rc=0
```

**End-to-end, not just constructed**: a real `npx playwright test` run (20 results)
stamped `692fab4f…`, matching an independent computation, and the gate cleared
freshness on it before failing S-3 — correct, since a single-spec subset has no
skips for the ALLOWs to match.

## 2. `check-runtime-freshness` — apply `.dockerignore`, conservatively

A commit touching only `frontend/e2e/kitchen-flow.spec.ts` reported the frontend
runtime as DRIFT. The runner stage copies only `.next/standalone`, `.next/static`
and `public`, so no rebuild could change a byte of the served bundle.

The header previously refused this outright: translating ignore patterns "risks
excluding MORE than intended, and an over-broad exclusion is a FALSE NEGATIVE".
That reasoning is correct and is now the **design constraint** rather than the
conclusion — the translator refuses anything ambiguous instead of refusing everything:

- any `!` re-include **voids the whole file**;
- any glob, `..`, or the `Dockerfile` itself is skipped **and printed**;
- every refusal falls back to the previous over-reporting behaviour.

`frontend/.dockerignore` now excludes `e2e/` and `playwright.config.ts`.

**A claim I made and then measured false.** The commit message and the first draft of
this file said the specs' type-check coverage moves to CI's `npm run build`. It does
not — `next build` never checked them:

```
planted `const broken: number = "..."` in frontend/e2e/zz-typeerror.spec.ts
  npm run build (next build)  -> rc=0, spec not mentioned      does NOT check e2e
  npx tsc --noEmit            -> rc=2, TS2322 on that line      does check it
```

So the Docker build was not type-checking these specs either, and nothing is lost —
but the *reason* is "the coverage never existed by that path", not "it moved". The
gap is pre-existing, is neither widened nor fixed here, and is recorded in
`frontend/.dockerignore` so nobody "restores" coverage that was never there.

**Exclusion proven, not assumed**: with a brand-new file added under `frontend/e2e/`,
`COPY . .` and `RUN npm run build` both came back **CACHED** and the image built
rc=0. A file that entered the context would have busted that layer.

### Arms — asserted on the commit the gate NAMES, not on FRESH/DRIFT

The first pass of these arms asserted FRESH/DRIFT and was **vacuous**: the branch's
own commit touches `.dockerignore`, a genuine build input, so the frontend drifts
regardless of the arm. The discriminating signal is *which commit* the gate dates
the image against.

| arm | expected | result |
|---|---|---|
| clean baseline | names `872bf9b3` | `872bf9b3` ✅ |
| commit touching **only** `frontend/e2e` | must **not** advance | still `872bf9b3` ✅ |
| commit touching `frontend/app` | must advance | `a09a9036` ✅ (not blind) |
| `!` re-include added, e2e commit | exclusions void, must advance | `3ac60b06` ✅ |
| closing arm | back to `872bf9b3`, clean tree | ✅ |

## A defect found in this change, by its own output

The first `dockerignore_excludes()` set a global and the caller read it after
`mapfile -t x < <(fn …)`. Process substitution runs the function in a **subshell**,
so no refusal ever reached the parent — the gate printed a clean run precisely
because the part that reports doubt could not speak. Caught by noticing that
`frontend/.dockerignore` has globs and yet no refusal printed. Notes now travel in
the return stream (`X<TAB>path` / `N<TAB>note`), which makes the failure
unrepresentable rather than merely fixed.

## Not done, deliberately

- **The nightly workflow is unchanged.** It runs the suite then the gate, so the
  digest is stamped in the same job; no wiring is needed.
- **No new gate.** `check-gate-enforcement` still reports **28**, because
  `e2e-spec-digest.sh` is a helper, not an assertion.
- The gate half of the pair is still deliberately out of CI (a runner has no
  containers, so it could only ever VOID there).
