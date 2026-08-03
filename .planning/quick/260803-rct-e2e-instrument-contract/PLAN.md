---
quick_id: 260803-rct
slug: e2e-instrument-contract
date: 2026-08-03
description: >-
  Fix the E2E instrument cluster (#505, #503, #305), convert each lesson into an
  executable gate, clear the check-e2e-skip-budget VOID, and measure the
  integrationTest maxParallelForks CI experiment.
issues: [505, 503, 305]
---

# Quick task: the E2E instruments, and making their lessons executable

## Why these three together

All three are defects in the **instrument**, not the product. The repo's standing
failure mode is that a green instrument certifies a surface it cannot observe:

- **#505** — one spec defaults to a port nothing publishes, so four blocks *fail*
  where the repo believes they *skip*. `check-e2e-skip-budget` is reasoning about
  a membership that does not hold.
- **#503** — the `mobile` Playwright project sets `isMobile` without `hasTouch`,
  so Chromium reports `pointer: fine`. Every touch-specific defect is invisible
  to it by construction — including the one the issue names.
- **#305** — filed as a strict-mode flake needing `.first()`.

The user-visible ask is not "fix three tests". It is: **make each lesson
reproducible**, so the same class of defect fails loudly next time rather than
being re-discovered by hand.

## Premise verification (run BEFORE any edit)

This repo has a recorded pattern of filed claims being false. Every premise below
was checked against the tree first; two were wrong.

| claim | filed as | measured |
|---|---|---|
| `vendor-refund-flow.spec.ts` defaults to `:3100` | #505 | **TRUE** — `e2e/vendor-refund-flow.spec.ts:39` |
| every sibling defaults to `:3000` | #505 | **TRUE** — 12 specs carry a local default; exactly 1 diverges |
| nothing publishes 3100 | #505 | **TRUE** — `docker ps` grep for `3100` → rc=1 |
| *"MCP server holds 3000"* (`playwright.config.ts:20`) | — | **FALSE.** mcp-server publishes **9100**; frontend holds 3000. This stale comment is the *source* of the 3100 folklore in 9 files |
| mobile project omits `hasTouch` | #503 | **TRUE** — `playwright.config.ts:35-39` |
| tailwind lacks `future.hoverOnlyWhenSupported` | #503 | **TRUE** — config keys are `darkMode`/`content`/`theme`/`plugins` only |
| `dashboard-mobile.spec.ts:268` asserts a bare test-id | #305 | **FALSE — already fixed.** Line 268 is inside `setupStubs`. The assertion now routes through `tabBarOf(page)` (byRole), landed in `d30e670e` |
| #305's root cause is "two DashboardShell trees during App Router transitions" | #305 | **FALSE.** It is React's streaming staging buffer (`<div hidden id="S:n">`) holding a second copy of the shell |
| #305's fix direction is `.first()` | #305 | **FALSE — and actively rejected.** `d30e670e` documents that `.first()` would silence the strict-mode error while still binding to the hidden staged copy |

Two of nine filed claims survived unchanged in #305. That issue closes as
already-fixed with evidence, not as work.

## Verification failures recorded during this task

Not filed claims — MY OWN measurements, wrong three times while checking whether
`hoverOnlyWhenSupported` took effect. All three said the fix was inert. It works.

| # | what was measured | why it was wrong |
|---|---|---|
| 1 | `@media\(hover:hover\)` matched nothing | pattern had no space; the un-minified form has one. Evidence about the PATTERN, not the CSS |
| 2 | `rg -c` read as an occurrence count | `-c` counts LINES, and minified CSS is nearly one line |
| 3 | "byte-identical before/after, so the flag is inert" | the "before" file was fetched AFTER the rebuild. The baseline was the treatment |

Settled by a structure-aware postcss walk **validated first against a
known-different pair** (Tailwind CLI, flag on vs off → 65/0 and 0/65), then
pointed at the real question → Next build 65/0, served artifact 65/0.

The transferable rule: **a text search cannot answer a question about nesting**,
and "is this rule inside a media query" is a nesting question. That is why the
shipped assertion lives in the CSSOM, in a browser, and not in a grep.

## Tasks

- **T1** — Make `playwright.config.ts` the single base-URL authority: delete the
  divergent default in `vendor-refund-flow.spec.ts`, move its `page.goto` calls
  to relative paths, and correct the stale `:3100` prose it and eight siblings
  inherited.
- **T2** — `scripts/check-e2e-baseurl-contract.sh`: fail when any spec declares a
  base-URL default that diverges from the config. Must be shown to FAIL.
- **T3** — `hasTouch: true` on the `mobile` project, plus a test that **asserts**
  `matchMedia("(pointer: coarse)").matches` rather than assuming it.
- **T4** — `future.hoverOnlyWhenSupported: true` in `tailwind.config.ts`, plus a
  coarse-pointer test proving a tapped element does not retain hover, and a
  fine-pointer test proving desktop hover still works.
- **T5** — `scripts/check-playwright-mobile-contract.sh`: any project declaring
  `isMobile` must also declare `hasTouch`. Must be shown to FAIL.
- **T6** — Close #305 with the evidence above; correct its recorded diagnosis.
- **T7** — Re-run the Playwright suite so `check-e2e-skip-budget` certifies a
  report newer than `frontend/e2e`. rc=2 → rc=0.
- **T8** — The CI experiment: measure whether `integrationTest` can use more than
  one fork on a GitHub runner, and what it saves against the 45-min baseline.
- **T9** — `scripts/ci-lane-cost.sh`: make the batching decision reproducible
  from a diff instead of remembered from a handoff.

## Falsification contract

Every gate added here is run against a deliberately broken tree first and its
failing output recorded. A gate observed only passing is not evidence.
