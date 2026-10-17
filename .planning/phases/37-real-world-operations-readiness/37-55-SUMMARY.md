---
phase: 37-real-world-operations-readiness
plan: 55
subsystem: testing
tags: [playwright, e2e, live-stack, runtime-parity, dark-theme, container-query, uat-gap-closure]

requires:
  - phase: 37-54
    provides: "the four frontend fixes for G-37-12..G-37-15 (webhooks refusal, Staff form dark tokens, pending-invitations container query, picker label)"
provides:
  - "frontend/e2e/staff-webhooks-uat.spec.ts: live instruments for G-37-12..G-37-15, each with a built-in instrument arm"
  - "frontend/e2e/helpers/staff-invitee.ts: inviteAndJoinAsStaff, a real non-admin session through the invite form, Mailhog and /invite"
  - "RED (3 failed, each on its gap assertion) on the pre-rebuild runtime and GREEN (3 passed) after a full rebuild with runtime parity"
  - "the PR branch phase-37-uat-gaps (one squash above origin/main, tag cut/phase-37-uat-gaps), not pushed"
affects: [phase-37-uat, gsd-verify-work-37, dashboard-dark-theme]

actuals:
  tokens: 16700
  tasks: 3
  commits: 2
plan_head_before: 1d2496407f3cf1d7d9188cf54993ae64a6db6952
plan_head_after: 1f383f47602cf5023231dccdc665b6d39f5e523d

tech-stack:
  added: []
  patterns:
    - "Live contrast instrument: computed colour against the first opaque ancestor background, read back through a 1x1 canvas so any colour syntax reaches the WCAG formula as sRGB"
    - "Every live gap assertion ships with an arm that forces the old defect back on every run and must be seen"
    - "Threshold boundary probe before applying a max-content rule: measure the layout at the threshold itself and below it with the layout forced"

key-files:
  created:
    - frontend/e2e/staff-webhooks-uat.spec.ts
    - frontend/e2e/helpers/staff-invitee.ts
    - .planning/phases/37-real-world-operations-readiness/evidence/37-55-uat-gaps-runtime.md
  modified:
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md

key-decisions:
  - "The 60rem container threshold was kept although the table max-content (1017 px) exceeds it: at card widths 960 to 1017 px the table layout has 0 failures and a constant 129 px row height, and with the table layout forced it first splits a token at 900 px (clean at 920 and 940). The continuation made a change conditional on the measurement showing the threshold wrong; it does not. Recorded for the owner"
  - "core-java, edge-go and mcp-server were fully cached in the full build, so they were rebuilt --no-cache (the 37-53 precedent) to keep 'rebuilt ALL images' from being vacuous"
  - "The PR branch's inherited upstream (origin/main, set by git switch -c … origin/main) was unset, so a bare push can never target main"
  - "The dark-theme residue and the shop switcher's light-scheme option list are measured and recorded, not restyled; they are logged in deferred-items.md as an open owner decision"

patterns-established:
  - "A gap-closure live gate records RED per test with the non-vacuity controls that passed in the same run, so a RED cannot be an instrument fault"

requirements-completed: [RWO-003, RWO-004]

coverage:
  - id: D1
    description: "G-37-12: a freshly invited STAFF user's GET /api/v1/webhooks answers 403 …/shop-access-denied and the page shows 'Group admin access required' with no empty state and no Add endpoint; the Group admin still sees Add endpoint"
    requirement: "RWO-003"
    verification:
      - kind: e2e
        ref: "frontend/e2e/staff-webhooks-uat.spec.ts#G-37-12: a Staff user is refused on Webhooks; the Group admin still manages endpoints"
        status: pass
    human_judgment: false
  - id: D2
    description: "G-37-13: every visible text element and all five selects of the Invite someone and Grant access cards measure at least 4.5:1 in dark and light, from computed colour"
    requirement: "RWO-004"
    verification:
      - kind: e2e
        ref: "frontend/e2e/staff-webhooks-uat.spec.ts#G-37-13 and G-37-15: the Staff form cards read at 4.5:1 in both themes; the picker shows no null"
        status: pass
    human_judgment: true
    rationale: "The native option list a select opens is drawn by the browser outside the DOM; no instrument measures it. The probe shows the five selects request color-scheme dark, but the owner must open each select in dark (the plan's human check)"
  - id: D3
    description: "G-37-15: the grant picker has at least 2 options and none contains null, undefined or empty parentheses"
    requirement: "RWO-003"
    verification:
      - kind: e2e
        ref: "frontend/e2e/staff-webhooks-uat.spec.ts#G-37-13 and G-37-15: the Staff form cards read at 4.5:1 in both themes; the picker shows no null"
        status: pass
    human_judgment: false
  - id: D4
    description: "G-37-14: at 390, 768, 1024, 1280 and 1440 px no fixture-row token splits across lines, both actions of every row stay inside the card and its scroll region, and the region does not scroll sideways; 'Expires' at 768"
    requirement: "RWO-004"
    verification:
      - kind: e2e
        ref: "frontend/e2e/staff-webhooks-uat.spec.ts#G-37-14: pending invitations keep every token on one line and both actions in reach, 390 to 1440"
        status: pass
    human_judgment: false
  - id: D5
    description: "Runtime parity: every image rebuilt and recreated; check-runtime-freshness rc 1 -> 0 (0 unverified); the refusal copy in the running frontend bundle 0 -> 14 files"
    verification:
      - kind: other
        ref: "bash scripts/check-runtime-freshness.sh; docker compose -f docker-compose.full-stack.yml exec -T frontend grep -rlF 'Managing webhook endpoints' /app/.next"
        status: pass
    human_judgment: false
  - id: D6
    description: "Goods unchanged: staff-invite (byte-identical to origin/main), webhooks-flow and dashboard-mobile pass on the rebuilt runtime"
    verification:
      - kind: e2e
        ref: "npx playwright test e2e/staff-webhooks-uat.spec.ts e2e/staff-invite.spec.ts e2e/webhooks-flow.spec.ts e2e/dashboard-mobile.spec.ts --project=desktop (29 passed)"
        status: pass
    human_judgment: false
  - id: D7
    description: "Doc metrics match the tree (6135 -> 6138, Playwright 179 -> 182 blocks, 42 -> 43 specs)"
    verification:
      - kind: other
        ref: "bash scripts/docs-freshness.sh; bash scripts/check-doc-metrics.sh; bash scripts/check-e2e-typecheck.sh (rc 0 0 0)"
        status: pass
    human_judgment: false
  - id: D8
    description: "The dark-theme residue outside the two form cards, for the owner's decision"
    verification: []
    human_judgment: true
    rationale: "Measured and recorded by design (evidence section 7); whether to schedule a dashboard-wide dark pass or hide the toggle is an owner ruling"

duration: 46min
completed: 2026-10-15
status: complete
---

# Phase 37 Plan 55: UAT gaps G-37-12..G-37-15 live gate Summary

**A Playwright spec proves 37-54's four fixes in Chrome against the compose runtime. Each test has an instrument arm that forces the old defect back. RED was 3 of 3 on the stale frontend, GREEN 3 of 3 after a full rebuild with freshness rc 1 -> 0, the goods specs are green, and the PR branch `phase-37-uat-gaps` is cut and not pushed.**

## Performance

- **Duration:** about 46 min. The plan started at 19:16:52Z. Task 3 ran from 19:49:32Z to 20:03Z.
- **Tasks:** 3 of 3. Task 1 was done by the first executor (`df42be1f`). Task 2's checkpoint was answered "proceed" by the owner. Task 3 ran in this continuation.
- **Files:** 2 created in Task 1; the evidence file created and 5 files modified in Task 3.

## Accomplishments

- **RED on the pre-rebuild runtime (19:49:48Z):** rc 1, 3 failed. Each test failed on its own gap assertion, while its non-vacuity controls passed in the same run.
  - **Test 1:** the "Group admin access required" heading was not found. The Staff user's API read was a real 403 `…/shop-access-denied`, yet the page showed "No webhook endpoints yet" and two "Add endpoint" buttons.
  - **Test 2:** two separate soft failures.
    - Dark contrast: 13 failures. The four enabled selects were light text on white at 1.05:1, the labels 1.93:1, the error 3.09:1.
    - The picker: `integration-orders-rw (null)` and `integration-catalog-ro (null)`.
    - Light theme: 0 failures.
  - **Test 3:** 30 failures at 768 px (the email split over 7 lines, every action outside the card, a 630 px scroll width in a 398 px region), 2 at 1024 px and 1 at 1280 px. "Expires" was missing at 768 px.
- **Rebuild and parity:**
  - All four images were rebuilt, three of them `--no-cache`, then recreated with `up -d`.
  - `check-runtime-freshness.sh` went from rc 1 (frontend DRIFT at `f199e97f`) to **rc 0, 4/4, 0 unverified**. Each running image ID equals its tag.
  - The refusal copy is in the running bundle: 0 files before, **14** after. The CSS chunk `2uolvmx0bd0tm.css` matches 37-54's GREEN build.
- **GREEN (19:55:13Z):** 3 passed, and every arm failed as designed.
  - The refusal assertion threw on the admin's page.
  - The forced white `#staff-role` read 1.05:1.
  - The injected "Arm Member (null)" option was caught.
  - The old table layout forced at 768 px gave 28 failures.
- **The plan's GREEN line verbatim:** rc 0, **29 passed**, 0 failed, 0 skipped. That is staff-webhooks-uat 3, staff-invite 1 (byte-identical to origin/main), webhooks-flow 1 and dashboard-mobile 24.
- **Metrics:** `docs/metrics.json` went from 6135 to **6138** (Playwright 179 -> 182 blocks, 42 -> 43 specs), and the prose in README.md, CLAUDE.md and AGENTS.md follows. docs-freshness, check-doc-metrics and the e2e typecheck give rc 0 0 0; the first two were rc 1 before the change.
- **PR branch** `phase-37-uat-gaps` = **`9f6c11dd`**, cut from origin/main `57d257f0` (#924).
  - Tag `cut/phase-37-uat-gaps` = `1f383f47`.
  - The PR branch's tree equals the tag's, `12742d43…`. Fail direction: against main's tree `073d2b73…` the comparison gives rc 1.
  - It is 1 commit above origin/main. The plan's fourth `<automated>` line gives rc 0.
  - It has no upstream and is not on origin. The D-04 merge of origin/main back into the integration branch gave `Already up-to-date`.

## Task Commits

1. **Task 1: the live instrument** - `df42be1f` (test), by the first executor
2. **Task 2: the owner's checkpoint** - answered "proceed", no commit
3. **Task 3: RED, rebuild, GREEN, goods, metrics, evidence** - `1f383f47` (docs)
4. **The cut** (on `phase-37-uat-gaps`, not the integration branch) - `9f6c11dd` fix(37): UAT gaps G-37-12..G-37-15 (webhooks refusal, Staff form dark theme, pending invitations layout, picker labels)

**Plan metadata:** the SUMMARY commit, below.

## Files Created/Modified

- `frontend/e2e/staff-webhooks-uat.spec.ts` - three live tests for G-37-12..15, with instrument arms and the dark-residue measurement
- `frontend/e2e/helpers/staff-invitee.ts` - `inviteAndJoinAsStaff`, plus vendorLogin, toasts and messagesFor
- `.planning/phases/37-real-world-operations-readiness/evidence/37-55-uat-gaps-runtime.md` - identities before and after, freshness and the bundle grep in both directions, RED and GREEN, arms, the per-width table, the threshold probes, the native option list, the dark residue, gates, and the branch by name
- `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md` - 6135 -> 6138
- `.planning/phases/37-real-world-operations-readiness/deferred-items.md` - the dark residue as an open owner decision (written after the cut, so not in the squash)

## Decisions Made

See `key-decisions`. The one that changes a plan instruction is the threshold, below.

## Deviations from Plan

**1. [Rule 4 boundary, resolved by the continuation's condition] The 60rem threshold was not raised although max-content exceeds it**
- **Found during:** Task 3, step 4.
- **Issue:**
  - The plan says: if the measured max-content exceeds the threshold, raise it to the next whole rem.
  - The GREEN measurement was 1017 px against 960 px, which would mean 64rem.
  - The continuation conditions a change on "the measurement shows the threshold is wrong".
- **What was measured instead:** two scratch probes, each deleted after use.
  - **Real styles at the boundary:** 0 failures at card widths 960, 961, 970, 980, 990, 1000, 1016 and 1017 px. Table width equals scroll width equals client width, and the row height is 129 px both at 960 px and at 1230 px.
  - **Table layout forced below 60rem:** clean at 940 and 920 px. A token first splits at 900 px.
  - Max-content counts the role/shop text on one line. That text wraps at word boundaries without growing the row.
- **Result:** the threshold stays at 60rem. Raising it is listed as an owner decision (evidence §9 item 2).
- **Files modified:** none.

**2. [Rule 2 - Safety] The PR branch's upstream was unset**
- `git switch -c phase-37-uat-gaps origin/main` set its upstream to `origin/main`. It was unset (`git branch --unset-upstream`), matching 37-53's "no upstream configured", so no bare push can target main.

**3. [Rule 3 - Honesty] core-java, edge-go and mcp-server were rebuilt `--no-cache`**
- The full build served them from cache. The no-cache rebuild follows 37-53, so "rebuilt ALL images" is not vacuous. Their inputs had not changed, so their content is unchanged.

**Total deviations:** 3 (1 plan rule not applied for a measured reason, 2 procedural). **Impact:** no product change. The threshold choice is recorded for the owner.

## Issues Encountered

- `check-doc-metrics` and `docs-freshness` were RED as the continuation predicted (6135 vs 6138). Both were fixed by `--write` and by editing the prose counts.
- No instrument fault. The first RED run was already 3 of 3 on the gap assertions, so the spec needed no edit.

## What 37-54 left open (recorded honestly)

- **Dark native option list:**
  - The five form selects compute `color-scheme: dark`, with options in `rgb(248,250,252)` on a transparent background, so the browser is asked for a dark list.
  - No instrument can see the drawn popup, so D2 stays `human_judgment: true`.
  - Found outside scope: the shop switcher `#shop-context-select` keeps `color-scheme: normal` in dark, with white option text.
- **Max-content against the threshold:** 1017 px against 960 px; kept, see Deviation 1.
- **Dark residue:** on `/dashboard/staff`, 73 of 125 text elements outside the two cards fail in dark (the h1 at 1.13:1, People and Pending text at 1.12 and 2.64, "No access" at 1.93).
  - The evidence's recommendation: a dashboard-wide dark pass as its own change, or hide the Dark Mode toggle until it ships.
  - Logged in `deferred-items.md` as open.

## Known Stubs

None. The created files are test instruments with no rendering stubs.

## Threat Flags

None. No new endpoint, auth path or schema. T-37-126, T-37-127 and T-37-128 were mitigated as planned:
- **T-37-126 (stale runtime):** freshness went rc 1 then 0, and the bundle grep 0 files then 14.
- **T-37-127 (per-run account and invitation):** the invitee password stayed in memory only, and the fixtures were cancelled.
- **T-37-128 (wrong commits on the PR branch):** the PR tree equals its tag's tree, and it is one commit above origin/main.

Gitleaks `protect --staged` reported "no leaks found" on `1f383f47` and on the squash `9f6c11dd`.

## Residue on the dev stack

- 3 STAFF users `staff-<ms>@example.test`, one per test-1 run, each with an accepted grant.
- 5 fixture invitations, all cancelled in the run that made them.
- The 6 older pending invitations from earlier specs were left alone.

## User Setup Required

None.

## Next Phase Readiness

The owner:
1. pushes `phase-37-uat-gaps` and opens the PR in the ship shape, then runs its D3 review series and adds the CHANGELOG entry with the PR number on that branch;
2. re-runs `/gsd-verify-work 37` tests 12 to 15 on this runtime, which matches the branch, opening each select in dark;
3. rules on the dark residue and the shop switcher's option list ((a) accept as a known limitation, (b) hide the toggle, or schedule the pass), and on whether to keep 60rem.

This SUMMARY and any STATE/UAT commits stay on the integration branch. Re-cut the same way if they must ride the PR.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-15*

## Self-Check: PASSED

- Files: `frontend/e2e/staff-webhooks-uat.spec.ts`, `frontend/e2e/helpers/staff-invitee.ts` and `evidence/37-55-uat-gaps-runtime.md` were each FOUND (`[ -f ]`).
- Commits: `df42be1f` and `1f383f47` are ancestors of HEAD. `9f6c11dd` is the tip of `phase-37-uat-gaps`, and the tag `cut/phase-37-uat-gaps` resolves to `1f383f47`.
- Gates on the final tree: freshness rc 0 (4/4, 0 unverified), docs rc 0 0 0, the plan's fourth line rc 0, and `git status` clean before the SUMMARY write.
