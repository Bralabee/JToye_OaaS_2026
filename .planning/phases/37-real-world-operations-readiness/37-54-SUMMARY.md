---
phase: 37-real-world-operations-readiness
plan: 54
subsystem: ui
tags: [gap-closure, d-07, d-08, d-09, typed-403, load-error-panel, dark-theme, contrast, wcag-1.4.3, container-query, tailwind, jest, g-37-12, g-37-13, g-37-14, g-37-15]
status: complete

requires:
  - phase: 37-06
    provides: "isShopAccessDenied (lib/access-events.ts), the typed shop-access-denied 403 contract"
  - phase: 37-09
    provides: "InviteStaffForm and PendingInvitesTable this plan restyles"
provides:
  - "/dashboard/webhooks renders a 'Group admin access required' card for the typed 403, and LoadErrorPanel with Retry for any other first-read failure; 'No webhook endpoints yet' only for a list that was read and is empty"
  - "WEBHOOKS_ACCESS_REQUIRED_COPY in lib/webhooks-api.ts"
  - "Exported STAFF_SELECT_CLASS / STAFF_FIELD_LABEL_CLASS: the five Staff-page selects, their labels, both hints and the email error at >= 4.5:1 in dark AND light, light colours unchanged"
  - "Grant picker labels with no 'null': name (email) only when both exist, else name, else email, else 'Integration account'; DirectoryEntry.email typed string | null"
  - "Pending invitations switch layout on the card's own width ([container-type:inline-size] + one [@container_(min-width:60rem)] threshold), actions a column of full-width h-11 buttons, 'Expires' prefix on stacked open rows"
affects: [37-55, phase-37-uat]

actuals:
  tokens: 22234
  tasks: 3
  commits: 7
plan_head_before: f632b4e233a61d4ba792eebac9d73041d8643f25
plan_head_after: 07d1131b3af5525ce65f5224fc09cf32b8370aa9

tech-stack:
  added: []
  patterns:
    - "A class-token colour resolver in jest: palette hexes from tailwindcss/colors, theme names from globals.css :root/.dark, variant precedence dark:disabled > dark > disabled > base, no token = the card's own pair. jsdom applies no stylesheet, so this is the jest-level contrast instrument; the computed-style measure is the browser's"
    - "A refused read is a refusal and a never-read list is unknown: catch order isShopAccessDenied -> never read (LoadErrorPanel) -> toast and keep the list"
    - "A layout that depends on a card's width is decided by a container query on that card, not a viewport breakpoint"
    - "Built-CSS search as the only instrument for an interpolated Tailwind class: jest stays green on an interpolated variant, the stylesheet does not"

key-files:
  created:
    - .planning/phases/37-real-world-operations-readiness/evidence/37-54-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-54-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-54-red-task3.json
  modified:
    - frontend/app/dashboard/webhooks/page.tsx
    - frontend/lib/webhooks-api.ts
    - frontend/app/dashboard/webhooks/__tests__/webhooks-page.test.tsx
    - frontend/components/dashboard/staff/invite-staff-form.tsx
    - frontend/app/dashboard/staff/page.tsx
    - frontend/lib/staff-api.ts
    - frontend/app/dashboard/developers/page.tsx
    - frontend/app/dashboard/__tests__/staff-page.test.tsx
    - frontend/components/dashboard/staff/pending-invites-table.tsx
    - frontend/app/dashboard/__tests__/staff-invites.test.tsx
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "The webhooks refusal card uses an h2 under the page h1 (as the plan says), not the h3 Finance and Staff use, so the headings descend h1 then h2"
  - "The disabled invite-shop select keeps its own dark:disabled pair (slate-400 on slate-900), so removing the shared dark background turns 4 of the 5 selects red, not 5; the fifth needs its disabled pair removed too (arm A2)"
  - "The built-CSS instrument is rg over frontend/.next/static/**.css: Next 16 emits CSS under .next/static/chunks/, and the plan's .next/static/css does not exist (rc 2 on the positive control too)"
  - "The jest instrument for the container threshold is the specific rule (60rem)]:table-row; the general (min-width:60rem) search cannot see a single interpolated class while any other 60rem class stays literal"
  - "docs/metrics.json and the README/CLAUDE/AGENTS prose were regenerated here (6119 -> 6135, Jest 2943 -> 2959); 37-55 should re-check, not re-do"

patterns-established:
  - "Every Staff-page form control takes its classes from the two exported constants, so a new control inherits the dark pair"

requirements-completed: [RWO-003, RWO-004]

coverage:
  - id: D1
    description: "A non-Group-admin's refused webhooks read shows 'Group admin access required' (no empty state, no Add endpoint, no toast); any other first-read failure shows LoadErrorPanel with Retry; a genuinely empty list and a failed refresh behave as before (G-37-12)"
    requirement: RWO-003
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/webhooks/__tests__/webhooks-page.test.tsx#Webhooks page: refused and unknown reads (G-37-12) (5); RED_EVIDENCE_OK (evidence/37-54-red-task1.json); fail arm (isShopAccessDenied skipped) red on the refusal test"
        status: pass
    human_judgment: false
  - id: D2
    description: "The five Staff-page selects, their labels, the Group-admin hint, the grant hint and the email error resolve to >= 4.5:1 in the dark and light themes, with light colours equal to today's (G-37-13)"
    requirement: RWO-004
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/__tests__/staff-page.test.tsx#Staff-page form controls are readable in both themes (G-37-13) (4, incl. the permanent instrument arm); RED_EVIDENCE_OK (evidence/37-54-red-task2.json); fail arms A, A2, D, E red"
        status: pass
      - kind: other
        ref: "git diff --quiet origin/main -- frontend/app/globals.css frontend/__tests__/contrast-tokens.test.ts (rc 0; arm C rc 1)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The grant picker never prints null: a no-email entry is labelled by its name, a nameless one 'Integration account'; the #290 rules hold (G-37-15)"
    requirement: RWO-003
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/__tests__/staff-page.test.tsx#Grant picker labels say only what the directory holds (G-37-15) + the two #290 tests unchanged; fail arm B red"
        status: pass
    human_judgment: false
  - id: D4
    description: "Pending invitations stack until the card itself is 60rem wide (container query), one threshold, 'Expires' on stacked open rows, full-width h-11 actions named by the address; the rule reaches the built stylesheet (G-37-14)"
    requirement: RWO-004
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/__tests__/staff-invites.test.tsx#Pending invitations follow the card's width, not the viewport (G-37-14) (6); RED_EVIDENCE_OK (evidence/37-54-red-task3.json)"
        status: pass
      - kind: other
        ref: "npm --prefix frontend run build && rg -uu -l -F --glob '*.css' '(min-width:60rem)' frontend/.next/static: RED build rc 1, GREEN rc 0, control (min-width:23rem) rc 0 on both; arms F1/F2 rc 1"
        status: pass
    human_judgment: false
  - id: D5
    description: "The four gaps in live Chrome on the rebuilt stack: the refusal as a Staff user, the dark-theme computed contrast and the native option list, the pending card at 390/768/1024/1280/1440 and the 60rem threshold measured against the real table width"
    verification: []
    human_judgment: true
    rationale: "By plan the live RED/GREEN, rebuild and runtime parity are 37-55's. jsdom applies no stylesheet and cannot measure container widths or the browser-rendered native option list ([color-scheme:dark])"

duration: 12min
completed: 2026-10-15
---

# Phase 37 Plan 54: Staff and Webhooks gap closure (G-37-12..15) Summary

**A Staff user who opens Webhooks now sees "Group admin access required" instead of an empty list with Add endpoint. A failed first read shows an error with Retry. The five Staff-page selects, their labels, hints and error text clear 4.5:1 in the dark theme; light-theme colours are unchanged. The grant picker no longer prints "(null)". Pending invitations follow the card's own width, so at 768px the rows stack instead of squeezing a four-column table into 398px.**

## Performance

- **Duration:** about 12 min
- **Started:** 2026-10-15T19:02:08Z
- **Completed:** 2026-10-15T19:14:03Z
- **Tasks:** 3 of 3, each RED then GREEN
- **Files:** 14 source, test and doc files, plus 3 evidence records

## Accomplishments

- **G-37-12, Webhooks.** `fetchSubscriptions` now handles failures in a fixed order:
  1. The typed `shop-access-denied` 403 sets `forbidden`. There is no toast.
  2. If no list has ever been read, it sets `loadError` from `describeLoadError`. There is no toast.
  3. Otherwise it shows the existing toast and keeps the list on screen.

  The h1 and subtitle are now one shared element, used by every branch. Add endpoint sits only beside a list that loaded. The refusal branch shows the ShieldCheck card with an h2 and the copy from `WEBHOOKS_ACCESS_REQUIRED_COPY`. The load-error branch shows `LoadErrorPanel` with the subject "webhook endpoints" and Retry. Neither branch renders the create, secret-reveal or confirm dialogs. Both declare `data-width-tier="index"`.

  I read the detail page `/dashboard/webhooks/[id]` and left it unchanged. Its failure branch already says "Couldn't load this endpoint" (lines 114 and 296).
- **G-37-13, dark theme.** `STAFF_SELECT_CLASS` is exported and used on all five selects. The invite selects keep h-11 and the grant selects keep h-10. Its light classes are today's, plus `text-foreground`, which is the colour the select already inherited. For dark it adds the shop switcher's `dark:border-slate-700 dark:bg-slate-800`, `dark:[color-scheme:dark]` and a dark disabled pair.

  `STAFF_FIELD_LABEL_CLASS` (`dark:text-slate-300`) is used on the five labels and on the skeleton labels. Only colour tokens differ, so the #454 geometry parity holds. The hints get `dark:text-slate-400` and the email error gets `dark:text-red-400`. No CSS variable changed (UI-SPEC L8).

  The native option list is drawn by the browser. `[color-scheme:dark]` asks the browser for a dark list, but no instrument in this plan measures it; that is 37-55's to check.
- **G-37-15, picker.** The label is `Name (email)` only when both exist; otherwise the name, else the email, else "Integration account". `DirectoryEntry.email` is now typed `string | null`.
- **G-37-14, pending invitations.** The CardContent is a size container. Rows, cells, the header and the action wrapper switch at one threshold, `[@container_(min-width:60rem)]`, instead of `sm:`. The actions stay a column of full-width h-11 buttons. The email keeps `[overflow-wrap:anywhere]` and gains `[@container_(min-width:60rem)]:break-words`. A stacked OPEN row now reads "Expires 10 Oct, 14:32".

## Task Commits

1. **Task 1 (tracer): Webhooks refusal and load error (G-37-12)**
   - RED `1f61d6dd` (test)
   - GREEN `fc6b2bd3` (feat)
   - Tracer gate: the mode is end-of-phase and the verify is automated only, so I re-ran `npm --prefix frontend test -- app/dashboard/webhooks/__tests__` before expanding. Result: 3 suites, 27/27, rc 0.
2. **Task 2: dark-theme form controls and a null-free picker (G-37-13, G-37-15)**
   - RED `b2e008da` (test)
   - GREEN `b3378d67` (feat)
3. **Task 3: container-query pending invitations (G-37-14)**
   - RED `902804a3` (test)
   - GREEN `f199e97f` (feat)
4. **Metrics:** `07d1131b` (docs), 6119 → 6135

No REFACTOR commits; none were needed.

## RED / GREEN evidence, both directions

All three RED records were built with a jest → TAP 13 reporter (`--reporters=<scratchpad>/tap-reporter.js`, `TAP_OUT=…`). `gsd_run check tdd-red-evidence` returned `RED_EVIDENCE_OK / target_test_failed` for each.

### Task 1 (`evidence/37-54-red-task1.json`)

**RED:** 27 tests ran, 3 failed and 24 passed. The refusal, unknown-list (500) and other-403 tests all failed on their first assertion. Each received `<h3 …>No webhook endpoints yet</h3>`: the empty state rendered for the refused read, which is G-37-12 as seen in the UAT. Both goods tests passed in RED: a genuine empty list, and a failed refresh that keeps the list and toasts "Error loading webhooks".

**GREEN:** 3 suites, 27/27, rc 0.

**Fail arm.** In the working copy I changed the catch to `if (false && isShopAccessDenied(…))`.

| Run | Result |
|-----|--------|
| Arm | rc 1, 1 failed: the refusal test (`Unable to find … heading … "Group admin access required"`, because the read fell through to the load-error panel) |
| Restore | sha256 `1b3fb8c8…` matched, and `git diff` against HEAD was rc 0 |
| Closing clean run | 27/27, rc 0 |

### Task 2 (`evidence/37-54-red-task2.json`)

**RED:** 65 tests ran, 2 failed and 63 passed.

The dark-theme target failed with 12 elements below AA:

| Element | Dark ratio |
|---------|-----------|
| `select#invite-role` | 1.05:1 |
| `select#staff-user` | 1.05:1 |
| `select#staff-shop` | 1.05:1 |
| `select#staff-role` | 1.05:1 |
| All five labels | 1.93:1 each |
| Group-admin hint | 2.64:1 |
| Grant hint | 4.2:1 |
| Email error | 3.09:1 |

These match the UAT diagnosis: about 1.0:1 for the selects and about 1.9:1 for the labels. `select#invite-shop` was disabled in this setup, so it resolved to its light disabled pair (slate-700 on slate-100) and passed.

The picker test failed with Expected `"integration-orders-rw"`, Received `"integration-orders-rw (null)"`.

These passed in RED, as they should: the light-theme AA test, the light-colours-unchanged test, the instrument arm, `contrast-tokens.test.ts` (unedited) and the 60 pre-existing tests.

**GREEN:**
- The plan verify (staff-page, staff-invites, contrast-tokens) passed 65/65 with rc 0.
- Adding `app/dashboard/developers` for the type follow-through gave 4 suites, 83/83.
- `git diff --quiet origin/main -- frontend/app/globals.css frontend/__tests__/contrast-tokens.test.ts` returned rc 0.

**Fail arms.** Each was run against the committed GREEN tree, and each restore matched by sha256:

| Arm | Break | Result |
|-----|-------|--------|
| A (plan's arm) | Remove `dark:border-slate-700 dark:bg-slate-800` from `STAFF_SELECT_CLASS` | Dark test red on **4** selects at 1.05:1: invite-role, staff-user, staff-shop, staff-role. The disabled invite-shop keeps its own `dark:disabled:` pair, so it stays readable. The plan expected "all five"; see deviation 3 |
| A2 | Arm A plus remove `dark:disabled:bg-slate-900` | Red on all **5** selects; invite-shop at 2.34:1 |
| D | Label class without `dark:text-slate-300` | Red on all five labels at 1.93:1 |
| B | The pre-fix picker expression | Only the G-37-15 test red |
| E | `text-foreground` → `text-slate-800` | The light-colours-unchanged test red, plus the dark test |
| C (L8 gate) | Append `/* arm */` to `globals.css` | `git diff --quiet origin/main …` rc 1. After the restore (sha256 matched), rc 0 |

Closing clean run: 3 suites, 65/65, rc 0.

### Task 3 (`evidence/37-54-red-task3.json`)

**RED:** 63 tests ran, 5 failed and 58 passed.

- **Target.** The row carried `… flex flex-col gap-3 py-4 sm:table-row sm:py-0` and no container variant.
- **The four sibling tests** failed on their own assertions:
  - The CardContent's classes were `p-6 pt-0`.
  - The threshold set was `[]` instead of `["60rem"]`.
  - The email had no container `break-words`.
  - No "Expires" appeared inside the row; the only one was the column header.
- **Goods test.** Both actions h-11 w-full and named by the address: it passed in RED.

**Built CSS.** The plan's path was wrong; see deviation 2. The corrected instrument is `rg -uu -l -F --glob '*.css' PATTERN frontend/.next/static`.

| Build | `(min-width:60rem)` | Control `(min-width:23rem)` (app/track) | CSS chunk |
|-------|---------------------|----------------------------------------|-----------|
| RED build, unchanged component, mtime 20:10:19 | rc 1 | rc 0 | `2c2q701rp0_da.css` |
| GREEN build, mtime 20:11:09 | rc 0 | rc 0 | `2uolvmx0bd0tm.css` |

The GREEN build contains all seven container utilities: `table-row`, `py-`, `table-cell`, `p-`, `table-header-group`, `break-words` and `hidden`.

**Fail arms.** Each was rebuilt, and each restore matched by sha256:

| Arm | Break | jest | Built CSS |
|-----|-------|------|-----------|
| F1 | Only `ROW_CLASS` built by interpolation (`${CQ_ROW}:table-row`) | 19/19 **green** | Row rule `60rem\)\]\:table-row` rc 1. The general `(min-width:60rem)` search stays rc 0, because the cell classes are still literal |
| F2 (the plan's arm, every variant) | Every container variant interpolated | 19/19 green | `(min-width:60rem)` rc 1; control rc 0; the CSS chunk is `2c2q701rp0_da.css`, the same file name as the RED build |

**Closing clean run.** Tests passed 63/63, the build rc was 0, `60rem` rc 0, the row rule rc 0 and the control rc 0. The CSS chunk is `2uolvmx0bd0tm.css`, the same as GREEN.

## Plan-level verification

1. Full jest: 250 suites, 2959/2959, 2 snapshots, rc 0.
2. `(cd frontend && npx tsc --noEmit)` rc 0.
3. `npm --prefix frontend run lint` rc 0, with 0 errors and 29 warnings. None of the warnings are in a file this plan touched: I listed the warned files, and none of the 10 touched files appear.
4. `npm --prefix frontend run build` rc 0 (the Task 3 closing build, on the final code tree).
5. The L8 diff gate rc 0, with fail arm C at rc 1.
6. Three RED records with `RED_EVIDENCE_OK`. Every fail arm's restore was checked by sha256, and each task has a closing clean run.

## Doc metrics

- `docs/metrics.json` was regenerated with `scripts/docs-freshness.sh --write`.
  - jest_blocks: 2943 → 2959
  - total_logical_invocations: 6119 → 6135
- That accounts for 16 new literal `it(` blocks: 5 in Task 1, 5 in Task 2 and 6 in Task 3.
- `docs-freshness.sh` went from rc 1 before the write to rc 0 after.
- `check-doc-metrics.sh` returned 7 FAIL claims with rc 1 after the write. After the README/CLAUDE/AGENTS prose update it returned 37/37 PASS with rc 0.
- **For 37-55:** these files are already regenerated here, so re-check them rather than re-doing them.

## Decisions Made

See `key-decisions` in the frontmatter. The two that matter for 37-55:
- The 60rem threshold is the plan's estimate. jsdom cannot measure the real table width, so the Chrome measurement may raise it.
- The rest of the dashboard has no dark variants (page headings, People and Pending cards), and it is untouched here. That is 37-55's measurement for the owner.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Widening `DirectoryEntry.email` to `string | null` broke the developers page's type check**
- **Found during:** Task 2 GREEN.
- **Issue:** `tsc` reported TS2345 at `app/dashboard/developers/page.tsx:88`. Its name map did `map.set(d.userId, d.displayName || d.email)`.
- **Fix:** It now sets the entry only when a name exists. Behaviour is unchanged: `createdLabel` already treated a null as "no name" and showed the date alone.
- **Files modified:** `frontend/app/dashboard/developers/page.tsx`. This file is not in the plan's `files_modified`.
- **Verification:** `tsc` rc 0; the developers suites passed (83/83 with the Task 2 suites).
- **Committed in:** `b3378d67`.

**2. [Rule 1 - Instrument] The plan's built-CSS path does not exist on a Next 16 build**
- **Found during:** Task 3, the RED build.
- **Issue:** `rg -uu -l -F '(min-width:60rem)' frontend/.next/static/css` returned rc 2 ("No such file or directory"). The positive control returned rc 2 as well. As written, the criterion could never return rc 0, and its rc 1 could not be told apart from a missing directory.
- **Fix:** Next 16 emits CSS under `.next/static/chunks/*.css`, so I used `rg -uu -l -F --glob '*.css' PATTERN frontend/.next/static`. Its fail direction (rc 1 on the RED build), its pass (rc 0 on GREEN) and its control (rc 0 on both) are all recorded above. The original form and its rc 2 are recorded here.
- **Files modified:** none (instrument only).

**3. [Rule 1 - Criterion as stated vs measured] The Task 2 fail arm turns 4 selects red, not "all five"**
- **Found during:** Task 2 fail arms.
- **Issue:** The plan said deleting the dark border and background pair turns the contrast test red "on all five selects". The disabled `#invite-shop` resolves through its own `dark:disabled:bg-slate-900` / `dark:disabled:text-slate-400`, so it stays readable.
- **Fix:** I recorded arm A as measured (4 selects). I added arm A2, which also removes `dark:disabled:bg-slate-900` and turns all five red. I did not change the code to make the original statement true, because it is correct as it stands.

**4. [Rule 1 - Instrument] The plan's Task 3 fail arm, as literally described, would be blind**
- **Issue:** "Build the row class by string interpolation" leaves the cell, header, email and prefix classes literal. Each of them still emits a `(min-width:60rem)` rule, so the general search stays rc 0 (measured in arm F1).
- **Fix:**
  - Arm F1 interpolates the row class only and is checked with the rule-specific search, `60rem\)\]\:table-row`, which went rc 1.
  - Arm F2 interpolates every variant and is checked with the plan's general search, which went rc 1.
  - jest stayed green in both arms. That is the point of the built-CSS check: jest cannot see an interpolated Tailwind variant.

**5. [Rule 3 - Lint] The Task 1 test helper called a `use*` function outside a component**
- **Issue:** `react-hooks/rules-of-hooks` reported an error on `useToast()` inside the `toastMock` helper.
- **Fix:** The toast mock factory also exposes `__toast`, and the helper reads it through `jest.requireMock`. This was a test-only change, committed with GREEN `fc6b2bd3`. Behaviour was unchanged: 27/27 before and after.

---

**Total deviations:** 5 (1 blocking type follow-through, 1 lint, 3 instrument or criterion corrections, each recorded with both directions).
**Impact on plan:** None on scope. Each correction made a check able to fail where the plan's form could not.

## Issues Encountered

- The Python guard on this machine refuses a base-env interpreter, so the RED record builder is a node script in the scratchpad (`mkred.cjs`) and not Python.

## Threat Flags

None. No new endpoint, auth path, storage or schema change. The refusal renders static copy only. The picker reads no new field. The server's GROUP_ADMIN gate is unchanged.

| Threat | Mitigation | Proof |
|--------|------------|-------|
| T-37-123 | Every control in the two form cards is at 4.5:1 or better in both themes | The jest resolver plus arms A, A2, D and E |
| T-37-124 | The refusal branch renders no list and no create, secret or confirm dialog | Code review of the branch; no dialog components in it |
| T-37-125 | The picker shows only the masked email or the name, never `null` | Arm B |
| T-37-SC | No package installed | No install was run |

## Known Stubs

None. The stub scan of the plan's diff (`TODO|FIXME|placeholder|coming soon|not available` on added lines) returned rc 1.

## User Setup Required

None.

## Next Phase Readiness

37-55 should cover, in live Chrome after a rebuild:
- the Staff-user refusal on `/dashboard/webhooks`;
- the dark-theme computed contrast of the five selects, and the native option list;
- the pending card at 390, 768, 1024, 1280 and 1440, raising 60rem if the real table needs more;
- runtime parity, and the PR branch.

The doc metrics are already regenerated here.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-15*

## Self-Check: PASSED

- The 3 evidence records and the key source files exist (`[ -f ]` FOUND for each).
- All 7 commits are ancestors of HEAD: `1f61d6dd`, `fc6b2bd3`, `b2e008da`, `b3378d67`, `902804a3`, `f199e97f`, `07d1131b`.
- The artifact `contains` checks each matched (`rg -uu -c` ≥ 1):
  - `WEBHOOKS_ACCESS_REQUIRED_COPY` in `lib/webhooks-api.ts`
  - `isShopAccessDenied(` in the webhooks page
  - `dark:bg-slate-800` in the invite form
  - `container-type:inline-size` in the pending table
  - `STAFF_SELECT_CLASS` in the staff page
- STATE.md and ROADMAP.md were not touched. The orchestrator owns them.
