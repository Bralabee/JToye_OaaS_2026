# Phase 37 TDD gate override (owner ruling 2026-10-16)

**Ruling:** prove every plan's RED-before-GREEN ordering on the unbounded history, then override
`check tdd-review-checkpoint 37` with `--force-mvp-gate`.

## Why the gate reports 25/25

`gsd_run query check tdd-review-checkpoint 37` (2026-10-16, HEAD 9c9ec4de): rc 0, `block: true`,
`tddPlans: 25`, `violations: 25`, every row RED ✗ GREEN ✗. The gate reads each plan's commits
through `resolveEvaluationScope`, which is milestone-bound and stops at the newest reachable
tag. The `cut/phase-37-*` tags are reachable from this branch, so the scope contains no phase
commits at all. This is an instrument defect in GSD Core (machine stack; not changed from here).

## Unbounded measurement

`git log --reverse --format='%H %s' HEAD` (1549 commits). For each plan: the first
`test(<id>)` commit (RED) and the first `feat(<id>)`/`fix(<id>)` commit (GREEN); PASS when RED
comes first.

| Plan | RED | GREEN | Verdict |
|---|---|---|---|
| 37-04 | 0f1a893b | 44983a13 | PASS |
| 37-05 | 6d9dbe94 | 2a08876c | PASS |
| 37-07 | 89faca39 | ab632086 | PASS |
| 37-08 | ad9279a6 | 9b23c83f | PASS |
| 37-10 | 89717aee | cb339d0f | PASS |
| 37-11 | 8033716c | 27b236c0 | PASS |
| 37-13 | 6a27a7e7 | fe4725f1 | PASS |
| 37-19 | 5616a20c | 36c5e26f | PASS |
| 37-20 | d44576d5 | af1fb959 | PASS |
| 37-24 | 5c4bb8f9 | fb91684e | PASS |
| 37-25 | afe51230 | 86788abb | PASS |
| 37-26 | 43b41a5a | e5f9e3c0 | PASS |
| 37-27 | 2d750178 | c2feada7 | PASS |
| 37-28 | 7d0d3a5e | a54b679a | PASS |
| 37-29 | 1ab4a8ea | 7d17cab4 | PASS |
| 37-33 | 98005841 | 5c64802b | PASS |
| 37-35 | a0c3ded5 | 180501a6 | PASS |
| 37-39 | f3080ee7 | 9ce3da21 | PASS |
| 37-40 | 8409f68e | 667f197a | PASS |
| 37-41 | 6793441e | 72cf4bab | PASS |
| 37-42 | f7e4fdf9 | de7fcfd3 | PASS |
| 37-43 | ba5f9bf1 | d1e00183 | PASS |
| 37-44 | cbef64cb | 2a7c5c21 | PASS |
| 37-45 | 980ce74f | ea0561f7 | PASS |
| 37-48 | e6f7f455 | 1434b557 | PASS |

All 25 GREEN commits are `feat(...)`; none relies on the `fix(...)` widening.

## Fail direction

The same check over the log NOT reversed (newest first, so GREEN precedes RED by line number):
37-04 t=534 g=531 FAIL; 37-48 t=95 g=94 FAIL. A plan id with no commits (37-99): FAIL.
The check can fail; its 25/25 PASS is evidence.

## Override applied (2026-10-16, /gsd-execute-phase 37 re-verification)

At HEAD c5a3904d: `gsd_run check tdd.review-checkpoint 37 --raw` rc 0, {"block":true,"tddPlans":25,"violations":25}. Same instrument
defect as above (milestone-bound scope stops at the reachable `cut/phase-37-*` tags). The workflow's
`--force-mvp-gate` flag is documented but not implemented, so the orchestrator applied the owner's
override by hand under this ruling and continued to the verifier. The unbounded RED-before-GREEN table
above still holds: no TDD plan gained a commit since it was measured (the commits after b51241fe
belong to quick 261016-edv and the phase verification artifacts).
