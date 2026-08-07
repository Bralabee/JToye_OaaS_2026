---
quick_id: 260807-jj6
slug: triage-all-57-open-issues-into-an-explic
date: 2026-08-07
type: quick
scope: docs-only
---

# Triage all 57 open issues into an explicit disposition

## Why

The owner pushed back on the repo's own `STATE.md` line — *"Next: Phase 28"* — with a single
observation: **there are still over 50 issues open.**

The pushback was correct and the measurement is worse than it sounds. `ROADMAP.md` named **15 of 57**
open issues. Forty-two had no phase. Six of those — **#453, #460, #461, #544, #462, #507** — appeared
in **zero** files anywhere under `.planning/`, and four of the six are P1 filed from the owner using
the running application. The roadmap read as a complete go-to-market plan while the highest-signal
findings on the board had no home in it.

## Deviation from the standard quick workflow — recorded, not hidden

`/gsd-quick` normally spawns `gsd-planner` + `gsd-executor`. **Neither was spawned.** Two reasons:

1. The session's harness instruction forbids spawning subagents that were not requested.
2. The measured inputs were already in hand from the triage conversation that produced this task. A
   planner agent would have had to re-derive them, and the two search traps below are exactly the
   kind a re-derivation gets wrong.

The GSD guarantees the workflow exists to provide — artifacts in `.planning/quick/`, a `STATE.md`
row, atomic commits on a feature branch — are all satisfied. The subagent hop is what was skipped.

## Tasks

1. Measure how many open issues are named in `ROADMAP.md`, with a control arm proving the check can
   return "not found".
2. Classify all 57 into: a phase, a dated deferral with a revival condition, post-GTM hardening, or
   immediate work.
3. Write `.planning/ISSUE-DISPOSITION.md` — one row per issue, no miscellaneous bucket.
4. Update `ROADMAP.md`: name every issue under Phases 28–32; add Phases **33** (The Consumer
   Product) and **34** (Rendering + Test Truthfulness) for the 16 orphans that cluster cleanly; make
   Phase 32 depend on 33.
5. Re-run the coverage check. **57/57 or the task is not done.**

## Acceptance

- [ ] Every open issue number is named in `ROADMAP.md` or `ISSUE-DISPOSITION.md`.
- [ ] The coverage check is falsified in **both** directions before the result is trusted: a token
      that is absent must return rc=1, a real issue must return rc=0.
- [ ] No source file is touched. Docs only.
- [ ] Feature branch + PR. Never a direct commit to `main`.

## Out of scope

Estimating, re-prioritising, closing issues, and writing any phase plan. This task assigns homes.
Two items (#461, #453) cannot be planned at all until a product decision is made, and saying so is
part of the deliverable rather than a gap in it.
