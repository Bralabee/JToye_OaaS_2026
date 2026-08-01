---
quick_id: 260801-ths
slug: document-v23-gtm-phases-28-32
date: 2026-08-01
status: in-progress
---

# Document the go-to-market plan as Phases 28–32 inside the OPEN v2.3 milestone

## Decision that scopes this task

**v2.3 is NOT complete and nothing is being closed.** Asked to formalise the go-to-market plan as a
new milestone v2.4, the owner instead ruled (2026-08-01): *"2.3 is not complete. closing nothing.
just document. we will proceed with 2.3 until it's go to market ready."* Phase 27 is recorded as
**part of v2.3**.

So this task is **documentation only**:

- No `/gsd-new-milestone`, no `/gsd-complete-milestone`, no archive, no `MILESTONES.md` entry.
- v2.3's span widens from Phases 21–26 to **21–32**.
- No source code, no schema, no CI change.

## Why these phases exist — the 2026-08-01 state review

The build queue was empty: Phases 21–27 all complete, no v2.4 roadmap, 61 open issues and two
substantial threads that had **no phase, no requirement ID and no issue** and were therefore
invisible to every roadmap- or tracker-driven review. Those two are now filed as **#427** (ADR-0004
Ingredient node / allergen evidence chain) and **#428** (Cohort B catering, discovery-gated).

The review also established that nothing in this platform has ever run outside a laptop, the money
path has never touched Stripe even in test mode, and Phase 27's monitoring is **compose-scoped by
construction** — `k8s/` ships no Prometheus, Alertmanager or Grafana (Phase 27 `deferred-items.md`
§5). Phases 28–32 are the sequenced closure of that gap.

## Tasks

1. **`.planning/ROADMAP.md`** — widen the v2.3 overview to 21–32 with the dated reason; add Phase 27
   and Phases 28–32 to the phase checklist and the progress table; append Phase 28–32 detail
   sections in house style (Goal / Depends on / Requirements / falsifiable Success Criteria).
2. **`.planning/REQUIREMENTS.md`** — add SEC, DPLY, PAY, LGL, GTM requirement categories; extend the
   traceability table; update the coverage line.
3. **`.planning/STATE.md`** — hand-edit the stale current-position block only. It claims Phase 27 is
   mid-flight at 27-03 when it is 7/7 complete, and names `check-alert-liveness` as a red owned gate
   that #339 closed. **Do NOT call `state.record-session`** — this project's memory records that it
   bumps `completed_plans`, rewrites `percent` on a different denominator and destroys
   `last_activity`. The `progress:` frontmatter counters stay on their v2.3-build denominator.

## Acceptance

- Every criterion written into Phases 28–32 must be capable of FAILING on the current tree. Where a
  criterion is already satisfied, it is not a criterion.
- No file outside the three named above is modified.
- The two `.idea/` files staged in the working tree before this task began are **not** committed.

## Out of scope

Executing any of Phases 28–32. Closing v2.3. Tagging. Touching `MILESTONES.md`.
