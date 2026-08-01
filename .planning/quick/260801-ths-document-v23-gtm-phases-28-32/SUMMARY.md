---
quick_id: 260801-ths
slug: document-v23-gtm-phases-28-32
date: 2026-08-01
status: complete
files_changed: 4
---

# Document the go-to-market plan as Phases 28–32 inside the OPEN v2.3 milestone

**Docs only.** No source, no schema, no CI, no workflow change.

## What was asked, and what changed about it mid-task

The request was to formalise the go-to-market plan as a new milestone **v2.4** via
`/gsd-new-milestone`. Before running it I raised an ordering problem: v2.3 had never been archived,
`MILESTONES.md` recorded only v2.1 and v2.0, Phase 27 sat outside any milestone, and `STATE.md`'s
counters ran on a denominator its own body contradicted. The owner's ruling changed the task:

> *"2.3 is not complete. closing nothing. just document. we will proceed with 2.3 until it's go to
> market ready."* — with Phase 27 recorded as **part of v2.3**.

So **no milestone was created and nothing was closed or archived.** `/gsd-new-milestone` was not
run. v2.3 widened from Phases 21–26 to **21–32**.

## Origin — the 2026-08-01 state review

The build queue was empty: Phases 21–27 all complete, no successor milestone, 61 open issues. Against
that, three things were true and none were visible from `ROADMAP.md` + the tracker:

1. **Nothing has ever run outside a laptop.** `DEPLOY_ENABLED`/`DEPLOY_STAGING_ENABLED` are off, the
   production domain is unregistered, and #99 records the deploy half as theatre.
2. **The money path has never touched Stripe**, even in test mode — which is why the refund E2E is
   correctly left skipped rather than seeded past its guard.
3. **11 pentest findings (3 CRITICAL) sit in a git-excluded file with no issue.** A tracker-driven
   review sees a clean security picture.

The review also found two substantial threads with **no phase, no requirement ID and no issue** —
the ADR-0004 ingredient graph and the **catering cohort**, the latter being half of the stated
go-to-market. Both were filed as **#427** and **#428** before this task ran.

## Changes

| File | Change |
|---|---|
| `.planning/ROADMAP.md` | 355 → 544 lines. Overview widened to 21–32 with the dated ruling; Phase 27 + Phases 28–32 added to the phase checklist and the progress table; five new Phase Detail sections in house style; the four blocking decisions recorded against the table |
| `.planning/REQUIREMENTS.md` | 24 → **46** requirements. New: `SEC×4`, `DPLY×5`, `PAY×3`, `LGL×3`, `GTM×2` — **plus `OPS×5`, which Phase 27 never had here** and which is why a requirements-driven review could not see Phase 27 at all. Traceability table and coverage line extended |
| `.planning/STATE.md` | Current-position block corrected; Phase Map extended to 27–32 with execution order and blocking decisions; quick-task row added |
| `.planning/quick/260801-ths-…/` | This task's PLAN.md + SUMMARY.md |

## The STATE.md correction

It read *"Phase 27 — IN PROGRESS … 27-03 PAUSED at Task 7's checkpoint"* and named
`check-alert-liveness` as a red owned gate. Both were stale: Phase 27 closed **7/7 on 2026-07-29**,
and that gate was fixed under **#339 (CLOSED)**. The GSD workflows read this file first, so a fresh
session would have resumed a phase that finished three days earlier.

The 27-03 record is **retained verbatim** with its resolution noted in place, not deleted — the three
defects it records (found by *running* the checks rather than reading them) are the durable lesson.

**`state.record-session` was deliberately NOT called.** This project's memory records that it bumps
`completed_plans`, rewrites `percent` on a different denominator and destroys `last_activity`. The
`progress:` frontmatter counters are untouched and now carry an explicit label: they run on the v2.3
**build** denominator (Phases 21–26, 48/48) and are **not** the milestone's completion measure.

## Falsifiability of the new criteria

Every success criterion in Phases 28–32 was written to be capable of **failing on the tree as it
stands**. The load-bearing example is **DPLY-03**, which fails by construction: `k8s/` ships zero
monitoring manifests — Prometheus, Alertmanager and Grafana live only in
`infra/monitoring/docker-compose.monitoring.yml` (Phase 27 `deferred-items.md` §5). Everything Phase
27 built stops at the compose boundary, so a staging deploy today would be **wholly unmonitored**.
That was the most under-weighted item in the backlog and it is now a phase criterion.

**SEC-01** is written the same way: it does not ask "fix A1", it asks that A1 be **re-verified**,
because its stated root cause does not hold on the tree — both tables carry `tenant_id` + FORCE RLS
and both services gate writes on `require(shopId, SHOP_MANAGER)`.

## Gates — and what green does NOT mean here

10 gates run, **all rc=0**: `check-doc-citations`, `check-doc-metrics`, `check-doc-versions`,
`check-claims`, `check-project-version`, `check-changelog-contract`, `check-handoff-contract`,
`check-no-measured-placeholders`, `check-terminal-states`, `docs-freshness` (1917, unchanged — this
task added no counted test invocation).

**That green is close to vacuous for this change, and saying so is the point.** No gate covers
`ROADMAP.md`, `REQUIREMENTS.md` or `STATE.md`: `check-doc-citations`'s `DEFAULT_DOCS` reaches
`.planning/codebase/*` only, and `check-doc-versions` gates `.planning/codebase/STACK.md`. So the
result means *"I broke nothing the gates watch"*, not *"this change is validated"*.

**This is a finding, not an excuse.** The three most load-bearing planning files in the repo are
ungated — which is exactly how `STATE.md` came to claim a finished phase was mid-flight, and how the
Phase 27 deferrals register rotted (its own header says "nothing gates this file"). A
`check-state-freshness`-style gate — asserting that `STATE.md`'s current phase matches `ROADMAP.md`'s
progress table — would have caught this drift on the day it appeared. Recommended, not built here.

## Deliberately not done

Closing or archiving v2.3. Touching `MILESTONES.md`. Tagging. Planning or executing any of Phases
28–32 (`/gsd-plan-phase` has not run for them — the traceability table says `not yet planned` rather
than inventing plan numbers). The two `.idea/` files staged in the working tree before this task
began were **not** committed.
