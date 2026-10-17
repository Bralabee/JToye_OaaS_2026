---
phase: quick
plan: 261016-edv
status: complete
subsystem: frontend/dashboard theme
tags: [theme, dark-mode, owner-ruling, use-theme, playwright, tdd]
requires:
  - "owner ruling 2026-10-16 (phase 37 deferred-items.md, Owner rulings 2026-10-16, second bullet)"
  - "37-55 UAT evidence sections 6-7 (dark-theme contrast residue)"
provides:
  - "DARK_THEME_AVAILABLE (shipped false) + __setDarkThemeAvailableForTests seam in frontend/hooks/use-theme.ts"
  - "theme control withheld on the sidebar and the mobile tab bar's More sheet"
  - "live spec frontend/e2e/dashboard-forced-light.spec.ts"
affects:
  - frontend/e2e/staff-webhooks-uat.spec.ts (dark runs apply the class by hand)
  - docs/metrics.json and the prose counts in CLAUDE.md, AGENTS.md, README.md
tech-stack:
  added: []
  patterns:
    - "module-level feature flag + __<name>ForTests seam; surfaces read availability from the hook, not the constant"
    - "live absence proof by seeding a stale class before hydration and waiting for the mount effect to remove it"
key-files:
  created:
    - frontend/e2e/dashboard-forced-light.spec.ts
    - .planning/quick/261016-edv-force-the-light-theme-on-the-dashboard-u/evidence/red-jest.txt
    - .planning/quick/261016-edv-force-the-light-theme-on-the-dashboard-u/evidence/break-arms.txt
    - .planning/quick/261016-edv-force-the-light-theme-on-the-dashboard-u/evidence/live-runtime.md
    - .planning/quick/261016-edv-force-the-light-theme-on-the-dashboard-u/evidence/doc-metrics-arm.txt
    - .planning/quick/261016-edv-force-the-light-theme-on-the-dashboard-u/evidence/base-sha.txt
  modified:
    - frontend/hooks/use-theme.ts
    - frontend/hooks/__tests__/use-theme.test.tsx
    - frontend/components/dashboard/sidebar.tsx
    - frontend/components/dashboard/mobile-tab-bar.tsx
    - frontend/components/dashboard/__tests__/theme-cross-surface.test.tsx
    - frontend/e2e/staff-webhooks-uat.spec.ts
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md
decisions:
  - "Live forced-light absence is proven by seeding a stale `dark` class before the page's scripts run and waiting for it to be removed: server markup never carries the class, so a bare absent-read could not fail on the old build"
  - "Forced-light Playwright assertions are expect.soft (controls hard) and the theme-button arm is relative to the pre-injection count, so one RED run shows every forced-light failure and still proves the arms"
  - "core-java re-tagged by `up --build frontend` was recreated with `up -d --no-deps --force-recreate core-java` (a second `--build core-java` did not recreate it)"
metrics:
  duration: "~17 min (2026-10-16T09:29:59Z to 09:47Z)"
  completed: 2026-10-16
actuals:
  tokens: 25159
  tasks: 3
  commits: 4
plan_head_before: b51241fed20951598214b37b69b02941ed3fe666
plan_head_after: e0c064fde824710fd43ec6834e6afbb219edb672
---

# Quick 261016-edv: Force the light theme on the dashboard Summary

The dashboard now renders light whatever is stored or preferred: `use-theme.ts` gets a `DARK_THEME_AVAILABLE` flag, shipped false, with a test seam. Both surfaces hide the Dark Mode / Light Mode control, and the user's stored `theme` is left byte-identical. Proven in jest (RED, then GREEN, then break arms) and live in Chrome (RED on the old image, GREEN after the rebuild).

## Commits (on phase-37-ops-readiness, not pushed)

| # | sha | subject |
|---|-----|---------|
| 1 | fa1591a4 | test(quick-261016-edv): forced-light theme contract for the hook and both dashboard surfaces |
| 2 | 6f7da333 | feat(quick-261016-edv): force the light theme on the dashboard and withhold the theme control |
| 3 | 9b8b0af8 | test(quick-261016-edv): live forced-light spec and the 37-55 UAT spec under forced light |
| 4 | e0c064fd | docs(quick-261016-edv): metrics, prose counts and the 37-55 dark-theme status |

I read every message back with `git log -1 --format=%B`. None carries an attribution line. The only "claude" match is the file name CLAUDE.md in commit 4's body. No commit deletes a file.

## Task 1: jest tracer (evidence/red-jest.txt, evidence/break-arms.txt)

- **RED** at the unchanged hook: 25 tests, all 25 failed, rc=1.
  - The 8 forced-light cases failed on their own assertions:
    - expected "light", received "dark"
    - `Received length: 3` setItem calls `[["theme","dark"],["theme","light"],["theme","dark"]]`
    - stale class `Expected: false / Received: true`
    - theme buttons `Expected length: 0 / Received length: 2`
    - `DARK_THEME_AVAILABLE` received `undefined`
  - The 17 flag-on cases failed only on `__setDarkThemeAvailableForTests is not a function`.
- **GREEN**:
  - The two files: 25/25 passed.
  - Full jest: 250 suites, 2967 tests passed, rc=0.
  - `next build`: rc=0, "Finished TypeScript".
  - `eslint .`: rc=0, "0 errors, 29 warnings". None of the warnings is in a changed file; this was checked with an rg that returned rc=1 and empty, plus a control that listed other files.
- **Break arms** (run after the feat commit, in the order clean, then arms, then clean):
  - **A:** flipping `DARK_THEME_AVAILABLE` to true gave 8 failed and 17 passed. Exactly the forced-light cases went red; every flag-on case stayed green.
  - **B:** deleting only applyTheme's early return turned the setItem-spy case red (`Received length: 3`), plus the "nothing stored" case.
  - **C** (added): returning a string for `available` made the build fail with `TS2322: Type 'string' is not assignable to type 'boolean'`.
  - **Restore:** sha256 `a2236794…` was the same before and after. `rg -uu -c "DARK_THEME_AVAILABLE: boolean = false"` printed 1. The closing jest run passed 25/25 and the closing build exited rc=0.
- **Search for code that applies the dark class** (`rg -uu -i` over every classList add/toggle/remove, next-themes, ThemeProvider and setAttribute("class") in frontend source, tests and e2e excluded): rc=0. It found only `frontend/hooks/use-theme.ts`: line 182 (applyTheme), line 209 (the effect) and line 71 (a comment). That file is the positive control. A first attempt with the wrong globs pulled in `.next` bundles; I discarded that output and redid the search with `**/` globs.

## Task 2: live proof (evidence/live-runtime.md)

- **Precondition met.** The frontend image c1e233e7 was tagged 08:46:35Z, before the feat commit at 09:32:49Z. Freshness before the rebuild: rc=1, frontend DRIFT `[image-not-rebuilt]`. This is the gate's fail direction.
- **RED on the old runtime:** rc=1, 2 failed.
  - Desktop: html.dark stayed (two routes); there was 1 theme control in the footer and 1 on the page; /dashboard/staff also had 1.
  - Mobile: 1 theme control in the More sheet; html.dark stayed.
  - The controls held: prefers-dark was true and the stored value was "dark".
  - Both arms behaved as designed. The html-class arm gave `{armed:true, after:false}` and the theme-button arm gave `{before:1, armed:2, after:1}`.
- **Rebuild:**
  - `up -d --build frontend` re-tagged core-java without recreating it, so freshness was rc=1 `[container-not-recreated]`.
  - `up -d --build core-java` did not recreate it either.
  - `up -d --no-deps --force-recreate core-java` worked; core-java was healthy after about 31 s.
  - Freshness after: rc=0, "PASS: 4 running built service(s) match the source tree (0 unverified)". Frontend a69cbd04 was tagged 09:39:33Z.
- **GREEN:**
  - New spec: 2 passed, 0 skipped. On desktop and mobile, html-dark was false, theme buttons were 0, the stored value was still "dark" (including after /dashboard/staff), and the arms gave `{armed:true, after:false}` and `{before:0, armed:1, after:0}`.
  - 37-55 UAT spec (desktop): 3 passed, 0 skipped. contrast-dark and contrast-light each measured 15 texts and 5 selects with 0 failures. The G-37-12 refusal card in dark had 0 failures. Every arm fired.
  - dashboard-mobile.spec.ts: 48 passed.
- `check-e2e-baseurl-contract.sh`: rc=0 over 44 specs. Its fail arm gave rc=1 and the restore was verified by sha256.
- `check-e2e-typecheck.sh`: rc=0 over 51 files.

## Task 3: metrics and status (evidence/doc-metrics-arm.txt)

- **Metrics:** jest blocks went from 2959 to 2967; Playwright from 182 blocks / 43 specs to 184 / 44; the total from 6138 to 6148. Java, Go and MCP are unchanged.
  - `docs-freshness.sh` exited rc=1 before `--write` and rc=0 after.
  - `check-doc-metrics.sh` exited rc=1 with 13 claim FAILs before the prose edit, and rc=0 (37/37) after.
  - The explicit README arm (2967 changed to 2966) gave rc=1. The restore was checked with `git hash-object` (683f0531…, the same before and after); the closing run was rc=0.
- **Count oracles:** jest runner 2967/250 and Playwright runner 184/44 both match, rc=0. With the old metrics.json swapped back in, both exited rc=1; the restore was checked by hash-object (6be12ca3…).
- **deferred-items.md:** the 37-55 entry now reads `status: ruled: forced light (quick 261016-edv); dark pass = Phase 39 candidate`. The match count is 1 on the edited file and 0 on the HEAD copy (rc=1).
- **Closing freshness:** rc=0, 4/4 FRESH, 0 unverified.

## Deviations from Plan

1. **[Rule 2 - correctness of the instrument] The live absence check seeds a stale class.**
   - **Found during:** Task 2.
   - **Issue:** a plain "no `dark` class" read can pass before hydration on any build.
   - **Fix:** both the new spec and the 37-55 `openStaffIn` dark path put `dark` on <html> before the page's scripts run, and wait for the mount effect to remove it. The RED run shows this fails on the old build.
2. **[Rule 1] Forced-light Playwright assertions made soft; arm 2 made relative.**
   - **Found during:** the first RED run, which stopped at the html.dark failure and never reached the theme-button assertion or the arms.
   - **Fix:** as above. The plan's RED requirement ("fail on the forced-light assertions while their arms behave as designed") then held in one run.
3. **[Rule 3] core-java recreation needed `--force-recreate --no-deps`.** The plan's "rebuild that service too" did not recreate the container (the known compose re-tag trap).
4. **Arm C was added** (a build type-check break) beyond arms A and B, so the build gate was also shown to fail.

## Observations (not changed)

- `frontend/app/layout.tsx:28-31` `viewport.themeColor` still has a `(prefers-color-scheme: dark)` entry, `#0f172a`. It sets the browser chrome colour, not the `dark` class, and it predates this change on the always-light storefront. I left it untouched, as the plan says.
- The 37-55 `dark-residue` annotation now measures 97 of 155 texts below 4.5:1 outside the two cards (it was 73/125 at 37-55, with more Staff rows on the page now). It is still measured and still not asserted; it is the input for the Phase 39 candidate dark pass.

## Dev-stack residue

- G-37-12 created the STAFF account `staff-1792143672255@example.test`, accepted through the helper. Its invitation was e-mailed at 09:41:12Z.
- G-37-14 created the fixture invitation `p1792143682396@example.test`, which the test cancelled.
- **core-java was recreated, so the order metric was not re-seeded.** `scripts/seed-order-metric.sh` returned VOID rc=2: HTTP 429 `order-cap-exceeded` (axis EMAIL, cap 3 open cash orders for the seed address). That data state was left untouched. The alert-metrics check may stay red until open cash orders for that address complete.

## Not run / VOID

- `scripts/check-e2e-skip-budget.sh`: rc=2 VOID, "report describes a DIFFERENT spec set than the tree". It needs a full e2e suite report from this tree. It is not in this plan's verify list, and it is not a pass.

## TDD Gate Compliance

The RED commit fa1591a4 (test) comes before the GREEN commit 6f7da333 (feat). The RED evidence is in evidence/red-jest.txt: forced-light cases fail on their own assertions, and flag-on cases fail on the missing seam. No REFACTOR commit was needed.

## Self-Check: PASSED

- FOUND: frontend/e2e/dashboard-forced-light.spec.ts, frontend/hooks/use-theme.ts, evidence/red-jest.txt, evidence/break-arms.txt, evidence/live-runtime.md, evidence/doc-metrics-arm.txt
- FOUND in HEAD ancestry: fa1591a4, 6f7da333, 9b8b0af8, e0c064fd
- `git rev-list --count b51241fe..HEAD` = 4
