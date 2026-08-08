---
quick_id: 260808-dke
description: Criteria-decay audit of Phase 28 and Phase 33 roadmap success criteria
date: 2026-08-08
docs_only: true
---

# Quick Task 260808-dke — Plan

## Why

`ROADMAP.md` was written 2026-08-01 and `ISSUE-DISPOSITION.md` swept the board 2026-08-07. Neither
re-measured the phases' success criteria against the tree, and work landed in between. Phase 33 was
about to be planned from those criteria.

The roadmap states the rule about itself: *"Every success criterion below must be capable of FAILING
on the tree as it stands on 2026-08-01. Where a criterion is already satisfied it is not a
criterion."* Nothing enforced it.

## Tasks

1. Measure Phase 28 SC-3 and SC-4 against the tree, with non-vacuity controls.
2. Measure Phase 33 SC-1, SC-2, SC-4, SC-5 against the tree, with non-vacuity controls. Record SC-6
   as not measured rather than assuming it live.
3. Write `.planning/CRITERIA-DECAY-2026-08-08.md` recording both directions — which criteria remain
   failable, which cannot fail any more, and the measurement that settled each.
4. Annotate `ROADMAP.md` in place at both phases, so a planner reading only the roadmap cannot plan
   a vacuous criterion.
5. Update STATE.md's Quick Tasks table.

## Acceptance

- Every "zero" claim in the audit carries a control returning non-zero on the same corpus with the
  same pattern machinery. A zero without a control is a statement about the pattern, not the code.
- Every decayed criterion names the commit or file that satisfied it.
- Unmeasured criteria are recorded as unmeasured, never as live.
- `ROADMAP.md` annotations are inline at the phase, not only in a sibling document — the failure
  being corrected is precisely that a reader of one document could not see the other.

## Out of scope

Fixing any of the criteria. This task records what is true; Phase 33 planning consumes it.
