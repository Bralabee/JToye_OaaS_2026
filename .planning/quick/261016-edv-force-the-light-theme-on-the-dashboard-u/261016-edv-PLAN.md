---
phase: quick
plan: 261016-edv
type: tdd
wave: 1
depends_on: []
files_modified:
  - frontend/hooks/use-theme.ts
  - frontend/hooks/__tests__/use-theme.test.tsx
  - frontend/components/dashboard/sidebar.tsx
  - frontend/components/dashboard/mobile-tab-bar.tsx
  - frontend/components/dashboard/__tests__/theme-cross-surface.test.tsx
  - frontend/e2e/dashboard-forced-light.spec.ts
  - frontend/e2e/staff-webhooks-uat.spec.ts
  - docs/metrics.json
  - CLAUDE.md
  - AGENTS.md
  - README.md
  - .planning/phases/37-real-world-operations-readiness/deferred-items.md
  - .planning/quick/261016-edv-force-the-light-theme-on-the-dashboard-u/evidence/red-jest.txt
  - .planning/quick/261016-edv-force-the-light-theme-on-the-dashboard-u/evidence/break-arms.txt
  - .planning/quick/261016-edv-force-the-light-theme-on-the-dashboard-u/evidence/live-runtime.md
autonomous: true
requirements: [QUICK-261016-edv]
branch: "phase-37-ops-readiness (main checkout, sequential, no worktree; nothing pushed, no PR)"
base: "phase-37-ops-readiness @ b51241fe"
worktree: "none"

estimate:
  tokens: 130000
  raw_tokens: 260000
  tasks: 3
  confidence: high

must_haves:
  truths:
    - "With localStorage theme = \"dark\" AND the system preferring dark, the dashboard renders light: <html> carries no `dark` class, in jest (hook + both real surfaces) and in the live dashboard on port 3000 at desktop (1440) and mobile (390, isMobile + hasTouch) widths"
    - "The Dark Mode / Light Mode control is absent from the desktop sidebar footer and from the mobile tab bar's More sheet, while the Sign Out button beside it is present in both (the positive control proving the query reaches the region the control lived in)"
    - "A stored `theme` value is left byte-identical: setDark(true), setDark(false) and toggle() never call localStorage.setItem for the key (jest spy, which a read-back cannot prove when the stored value is already \"dark\"), and live localStorage.theme is still \"dark\" after load, reload and a second dashboard route"
    - "Re-enabling dark is a one-line change: flipping `DARK_THEME_AVAILABLE` to true in frontend/hooks/use-theme.ts restores the previous behaviour, and every pre-existing use-theme and theme-cross-surface test still passes through the `__setDarkThemeAvailableForTests(true)` seam (break arm A: the flip turns the forced-light blocks red and leaves the flag-on blocks green)"
    - "The delivered frontend image is built from the feat commit (scripts/check-runtime-freshness.sh rc 0, 0 unverified), the new live spec was RED on the pre-rebuild runtime and is GREEN on the rebuilt one, and the 37-55 UAT spec still passes with its dark-card measurements applied by hand"
    - "docs/metrics.json and the prose counts in CLAUDE.md, AGENTS.md and README.md equal the runner counts (docs-freshness, check-doc-metrics, and both count oracles rc 0)"
  artifacts:
    - path: frontend/hooks/use-theme.ts
      provides: "DARK_THEME_AVAILABLE (shipped false), the __setDarkThemeAvailableForTests seam, getSnapshot/applyTheme guards, and the `available` field on the hook's return"
      contains: "DARK_THEME_AVAILABLE"
    - path: frontend/hooks/__tests__/use-theme.test.tsx
      provides: "forced-light block on the shipped default + the pre-existing flag-on block behind the seam"
      contains: "__setDarkThemeAvailableForTests"
    - path: frontend/components/dashboard/__tests__/theme-cross-surface.test.tsx
      provides: "no theme control on either real surface on the shipped default, Sign Out control present; flag-on cases behind the seam"
      contains: "__setDarkThemeAvailableForTests"
    - path: frontend/e2e/dashboard-forced-light.spec.ts
      provides: "live proof at desktop and mobile with emulated dark colorScheme and stored theme=dark, each with an instrument arm"
      contains: "colorScheme"
  key_links:
    - from: frontend/components/dashboard/sidebar.tsx
      to: frontend/hooks/use-theme.ts
      via: "the `available` field gates rendering of the theme Button"
      pattern: "available"
    - from: frontend/components/dashboard/mobile-tab-bar.tsx
      to: frontend/hooks/use-theme.ts
      via: "the `available` field gates rendering of the theme Button inside the More sheet"
      pattern: "available"
    - from: frontend/hooks/use-theme.ts getSnapshot/applyTheme
      to: DARK_THEME_AVAILABLE (through the module-level darkThemeAvailable)
      via: "early return false / early return before any localStorage write or class change"
      pattern: "darkThemeAvailable"
---

<objective>
Force the light theme on the dashboard until a dashboard-wide dark pass ships, exactly as the owner ruled on
2026-10-16 (".planning/phases/37-real-world-operations-readiness/deferred-items.md", "Owner rulings 2026-10-16",
second bullet; the measured problem is the "From 37-55" entry above it and
".planning/phases/37-real-world-operations-readiness/evidence/37-55-uat-gaps-runtime.md" sections 6-7: in dark,
73/125 text elements on /dashboard/staff outside the two Staff form cards are below 4.5:1, and
#shop-context-select draws a light option list).

The design is the owner's and is NOT revisited here: frontend/hooks/use-theme.ts stays the ONE theme source and
gains one module-level flag; while it is false the hook resolves light whatever the stored value or system
preference, leaves no `dark` class on <html>, and setDark/toggle write nothing to localStorage (a stored `theme`
is left byte-identical so the choice returns with the pass); the Dark Mode / Light Mode control is hidden in the
sidebar and the mobile tab bar; a test seam keeps the flag-on behaviour covered by the existing tests.

Purpose: the dashboard is unreadable in dark today; the user must not be able to land in it, and the user's stored
choice must survive untouched for the Phase 39 candidate dark pass.
Output: the flag + seam + guards, both controls hidden, jest RED-then-GREEN with break arms, a live Playwright
spec with RED on the old runtime and GREEN on the rebuilt one, the 37-55 UAT spec kept green under forced light,
regenerated metrics, and the deferred-items status.

Planner decisions (Claude's discretion, recorded):
- Flag name `DARK_THEME_AVAILABLE`, typed `boolean` (not the literal `false`), kept IN frontend/hooks/use-theme.ts
  as the owner wrote; the seam follows the repo's existing `__<name>ForTests` convention
  (frontend/lib/vendor-logout.ts `__resetVendorLogoutForTests`, frontend/lib/gsap-gate.ts
  `__resetEntranceMountGateForTests`): `__setDarkThemeAvailableForTests(next: boolean)`.
- The surfaces read availability from the hook (`available` on the return) rather than importing the constant,
  so the seam reaches them too.
- The forced-light jest blocks run on the SHIPPED default (no seam call), so they prove what ships; when the dark
  pass flips the flag they go red by design and are deleted with the flag. The pre-existing blocks call the seam.
- frontend/e2e/staff-webhooks-uat.spec.ts (37-55, run at the gate and in the nightly full-stack lane) asserts
  `html.dark` after storing theme=dark; under this ruling that would go red in the nightly. It is adapted, not
  deleted: the stored dark choice is first asserted NOT to reach <html> (the new contract), then the class is
  applied by hand so its dark-card contrast measurements and the "dark-residue" annotation stay alive as the
  forward guard and the input for the Phase 39 dark pass (Incremental Betterment: the instrument is preserved).
- app/layout.tsx `viewport.themeColor` carries a `(prefers-color-scheme: dark)` entry. It sets the browser chrome
  colour, not the `dark` class, and it predates this change on the always-light storefront too; it is left
  untouched and recorded in the SUMMARY as an observation, not changed.
</objective>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@CLAUDE.md
@.claude/skills/proof-standards/SKILL.md
@frontend/hooks/use-theme.ts
@frontend/hooks/__tests__/use-theme.test.tsx
@frontend/components/dashboard/__tests__/theme-cross-surface.test.tsx
@frontend/e2e/helpers/staff-invitee.ts

Live observations at planning time (b51241fe, clean tree):
- `rg -uu` for classList / next-themes / ThemeProvider / darkMode / color-scheme across frontend found exactly two
  writers of the `dark` class, both in frontend/hooks/use-theme.ts (applyTheme line 133, the effect line 155).
  frontend/app/layout.tsx renders `<html lang="en" suppressHydrationWarning>` with no className and no inline
  script; frontend/package.json has no next-themes; frontend/tailwind.config.ts is `darkMode: ["class"]`;
  frontend/app/globals.css has a `.dark` token block (line 76) and no prefers-color-scheme media query.
- The theme control renders at frontend/components/dashboard/sidebar.tsx:192-201 (hook at :123) and
  frontend/components/dashboard/mobile-tab-bar.tsx:182-191 (hook at :75), each beside a Sign Out Button.
- Only theme-cross-surface.test.tsx references the Dark Mode / Light Mode labels among jest tests; sidebar renders
  are also in sidebar-developers, vendor-signout-affordance, kitchen-deliberate-signout, no-access-page and
  shop-switcher tests (the full jest run is their regression check).
- e2e consumers of the theme: frontend/e2e/staff-webhooks-uat.spec.ts lines 347-358 (`openStaffIn`) and 392-397
  (G-37-12), plus the G-37-13 control at line 432. frontend/e2e/ is .dockerignore'd and excluded by
  scripts/check-runtime-freshness.sh, so committing e2e changes after a rebuild does not make the image stale;
  jest files under frontend/hooks and frontend/components ARE in the frontend build context, so Task 1's commits
  must precede the rebuild.
- docs/metrics.json today: jest_blocks 2959 / jest_files 250, playwright_blocks 182 / playwright_specs 43,
  total_logical_invocations 6138. scripts/docs-freshness.sh counts literal `it(` / `test(` declarations (do not use
  `it.each` or a looped declaration in new tests).
- workflow.tdd_mode = true, security_enforcement = true (config-get).
</context>

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: Tracer — forced light from the hook through both real surfaces (jest RED, then GREEN, then break arms)</name>
  <files>frontend/hooks/__tests__/use-theme.test.tsx, frontend/components/dashboard/__tests__/theme-cross-surface.test.tsx, frontend/hooks/use-theme.ts, frontend/components/dashboard/sidebar.tsx, frontend/components/dashboard/mobile-tab-bar.tsx</files>
  <behavior>
    Forced-light block in use-theme.test.tsx (shipped default, NO seam call; each case is a plain `it(`):
    - The shipped flag: DARK_THEME_AVAILABLE is false, and the hook returns `available: false`.
    - Stored "dark" + system light: resolves light, no `dark` class on documentElement, localStorage.getItem("theme") still "dark".
    - Nothing stored + system prefers dark: resolves light, no `dark` class.
    - A stale `dark` class already on documentElement before mount is removed on mount (proves the effect actively leaves none, not merely that nobody added one).
    - Stored "dark": clicking setDark(true), setDark(false) and toggle() leaves the probe light, leaves no `dark` class, and a `jest.spyOn(Storage.prototype, "setItem")` installed AFTER seeding records ZERO calls whose key is "theme"; getItem("theme") is still "dark". (The spy, not a read-back, is the proof: writing "dark" over "dark" is invisible to a read-back.)
    - Nothing stored: setDark(true) leaves getItem("theme") null and the probe light.
    - A cross-tab StorageEvent for "theme" after another tab stored "dark" leaves the probe light.
    Forced-light block in theme-cross-surface.test.tsx (shipped default, NO seam call):
    - Sidebar + MobileTabBar inside ShopSwitcherProvider with stored "dark", More sheet opened: `queryAllByRole("button", { name: /dark mode|light mode/i, hidden: true })` has length 0, the same query for /sign out/i has length 2 (one per surface: the positive control), documentElement has no `dark` class, localStorage "theme" still "dark".
    Flag-on blocks (every pre-existing case in both files, unchanged assertions): wrapped with beforeEach `__setDarkThemeAvailableForTests(true)` and afterEach `__setDarkThemeAvailableForTests(DARK_THEME_AVAILABLE)`, and still pass.
  </behavior>
  <action>
RED first (tdd_mode). Record the base sha into the evidence directory before any write. Write ONLY the two test
files per the behavior block: move the shared reset (localStorage.clear, documentElement.className = "",
matchMedia stub) into a setup both blocks use, add the forced-light describe blocks, and put the seam calls on the
pre-existing describe blocks. Import `DARK_THEME_AVAILABLE` and `__setDarkThemeAvailableForTests` from
"@/hooks/use-theme" and read `available` from the hook in the Probe. Run the two files and save the full output to
.planning/quick/261016-edv-force-the-light-theme-on-the-dashboard-u/evidence/red-jest.txt. Expected RED, and the
evidence must say which is which: the forced-light cases fail ON THEIR ASSERTIONS (e.g. expected "light", received
"dark"; theme buttons length 2, expected 0); the pre-existing cases fail because the seam is not yet exported
("is not a function"). A forced-light case that passes on the unchanged hook is vacuous: fix the test before
continuing. Commit only the two test files: `test(quick-261016-edv): forced-light theme contract for the hook and both dashboard surfaces`.

GREEN, implementing the owner ruling 2026-10-16 in frontend/hooks/use-theme.ts:
- Add `export const DARK_THEME_AVAILABLE: boolean = false` with a header comment naming the ruling (deferred-items
  "Owner rulings 2026-10-16", quick 261016-edv), the measured reason (37-55 evidence sections 6-7), that flipping
  this one line to true re-enables dark when the dashboard-wide dark pass (Phase 39 candidate) ships, and that the
  forced-light jest blocks go red on that flip by design and are deleted with the flag.
- Add a module-level `let darkThemeAvailable: boolean = DARK_THEME_AVAILABLE` and
  `export function __setDarkThemeAvailableForTests(next: boolean): void` that assigns it and calls notify().
- getSnapshot: return false first when darkThemeAvailable is false (before readStored, so the stored value is
  not even read). getServerSnapshot unchanged (false).
- applyTheme: return immediately when darkThemeAvailable is false — no localStorage.setItem, no classList change,
  no notify. Keep the T-34-02-01 narrowing comment and readStored unchanged for when the flag returns.
- The effect stays `classList.toggle(DARK_CLASS, dark)`: with dark always false it removes any `dark` class.
- ThemeControls gains `available: boolean` (doc: false while the dark theme is withheld; surfaces hide the
  control); useTheme returns `available: darkThemeAvailable`. subscribe stays as it is.
In frontend/components/dashboard/sidebar.tsx and frontend/components/dashboard/mobile-tab-bar.tsx destructure
`available` from useTheme() and render the theme Button only when it is true; leave the Sign Out Button, its
classes and its order untouched. Update the "Theme Toggle + Logout" comment and the MobileTabBar JSDoc ("user/
theme/sign-out controls") to say the theme control is withheld while DARK_THEME_AVAILABLE is false (owner
ruling 2026-10-16). No other file applies the class (planning-time `rg -uu`); re-run that search once with a
positive control (it must list use-theme.ts) and record the result in the SUMMARY.

Run the two files GREEN, then the full gates: `npm --prefix frontend test` (all suites; the other sidebar/tab-bar
suites are the regression check), `npm --prefix frontend run build` (the type-check; jest does not type-check),
`npm --prefix frontend run lint` (read the error/warning verdict lines, not the trailing fixable-count line).
Commit the hook and the two components: `feat(quick-261016-edv): force the light theme on the dashboard and withhold the theme control`.
No Co-Authored-By trailer or any AI attribution in either commit (owner ruling); read each back with
`git log -1 --format=%B`.

Break arms, AFTER the feat commit so the restore target is committed (clean -> arms -> clean), appended to
.planning/quick/261016-edv-force-the-light-theme-on-the-dashboard-u/evidence/break-arms.txt with the sha256 of
use-theme.ts before and after:
- Arm A (the owner's example): flip the `DARK_THEME_AVAILABLE` line to true in the working tree, run the two test
  files: every forced-light case must go red and every flag-on case must stay green. Record the counts.
- Arm B: delete only the early return in applyTheme, run use-theme.test.tsx: the setItem-spy case must go red
  (proves the "writes nothing" assertion can fail even when the stored value is already "dark").
- Restore with `git restore --source=HEAD --staged --worktree frontend/hooks/use-theme.ts`, verify BY CONTENT
  (sha256 equals the pre-arm hash; `rg -uu -c "DARK_THEME_AVAILABLE: boolean = false" frontend/hooks/use-theme.ts`
  prints 1), and re-run the two files green as the closing clean arm.
  </action>
  <verify>
    <automated>npm --prefix frontend test -- hooks/__tests__/use-theme.test.tsx components/dashboard/__tests__/theme-cross-surface.test.tsx; echo "rc=$?"</automated>
    <automated>npm --prefix frontend test; echo "rc=$?"</automated>
    <automated>npm --prefix frontend run build; echo "rc=$?"</automated>
    <automated>npm --prefix frontend run lint; echo "rc=$?"</automated>
  </verify>
  <done>
    evidence/red-jest.txt holds the RED run with forced-light failures on their own assertions; the test commit
    precedes the feat commit; both targeted files pass; full jest, build and lint exit 0 (lint verdict read as
    0 errors); break-arms.txt shows arm A (forced-light red, flag-on green) and arm B (spy case red), the
    content-verified restore, and the closing green run; `git status --short` is clean apart from evidence files.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Live proof — new forced-light spec RED on the old runtime, rebuild, GREEN; keep the 37-55 UAT spec green</name>
  <files>frontend/e2e/dashboard-forced-light.spec.ts, frontend/e2e/staff-webhooks-uat.spec.ts</files>
  <precondition>The compose stack (docker-compose.full-stack.yml) is running or startable, the repo-root .env provides the vendor password vendorLogin reads, and the running frontend image predates the Task 1 feat commit (check `docker image inspect` LastTagTime against `git log -1 --format=%cI` of that commit) — if it does not, record that the old-runtime RED cannot be shown and continue.</precondition>
  <behavior>
    - Desktop (@desktop-only, 1440 project viewport): after vendorLogin, store theme "dark" via page.evaluate and reload /dashboard; with file-level `test.use({ colorScheme: "dark" })`: matchMedia("(prefers-color-scheme: dark)").matches is true (control: the emulation reached the page), localStorage theme is "dark" (control: the stored choice is present), <html> has no `dark` class, the sidebar shows Sign Out (control) and zero buttons named /dark mode|light mode/i; after navigating to /dashboard/staff the same holds and localStorage theme is still "dark".
    - Mobile (@mobile-only, the mobile project: 390x844, isMobile, hasTouch): same storage + emulation; open the "More navigation" sheet; inside the dialog Sign Out is visible (control) and zero theme buttons; <html> has no `dark` class; localStorage theme still "dark".
    - Instrument arms run on EVERY execution and must fail as designed: (1) add `dark` to documentElement by hand -> the html-class checker reports dark -> remove it -> reports light again; (2) inject a button whose text is "Dark Mode" into the region under test (sidebar footer / open sheet) -> the theme-button locator counts 1 -> remove it -> counts 0.
    - 37-55 spec: `openStaffIn(page, "dark")` and G-37-12 first assert the stored dark choice does NOT reach <html>, then apply the class by hand; the existing "html.dark must be set" controls stay, and a second html.dark control sits immediately before the G-37-13 dark `measureContrast(page, "cards")` call.
  </behavior>
  <action>
Create frontend/e2e/dashboard-forced-light.spec.ts in the house style of frontend/e2e/staff-webhooks-uat.spec.ts:
a header naming the ruling (owner 2026-10-16, quick 261016-edv), which test proves what, the instrument arms, the
coverage boundary (needs the live compose stack and a Keycloak login, so it runs at gates and in the nightly lane,
never per PR), that it goes red by design when DARK_THEME_AVAILABLE is flipped, and a Run line. Use
`vendorLogin` from ./helpers/staff-invitee, relative URLs only (playwright.config.ts is the only base-URL
authority; scripts/check-e2e-baseurl-contract.sh enforces it), two plain `test(` declarations — one tagged
@desktop-only, one tagged @mobile-only — and testInfo annotations for each measured value. A vendorLogin skip is
VOID, not a pass.

Adapt frontend/e2e/staff-webhooks-uat.spec.ts per the behavior block: in `openStaffIn`, for "dark", after both
cards are visible assert <html> lacks the class with a message naming quick 261016-edv, then add it by hand with a
comment: the dashboard no longer applies dark (owner ruling 2026-10-16); the two cards' dark: classes stay
measured as the forward guard for the Phase 39 candidate dark pass, and the class is added after mount because
use-theme's mount effect removes it. Same pattern in G-37-12 after the reload. Update header item 2 to say so.
Change no other assertion, arm or threshold.

Old-runtime RED: with the pre-change frontend image still running, run the new spec (both projects) and save the
output: the desktop and mobile cases must fail on the forced-light assertions (html.dark set / a theme button
found), while their arms behave as designed. Then rebuild: `docker compose -f docker-compose.full-stack.yml up -d --build frontend`
(this also retags core-java), then `scripts/check-runtime-freshness.sh`; if any service reports DRIFT or
unverified, rebuild that service too and re-run until rc 0 with 0 unverified (the project rule is that every
container under test is current). GREEN: the new spec (2 passed, 0 skipped), the 37-55 spec on the desktop
project (3 passed, 0 skipped; G-37-12 creates one STAFF account, record its address as dev residue), and
e2e/dashboard-mobile.spec.ts as the goods check. Write
.planning/quick/261016-edv-force-the-light-theme-on-the-dashboard-u/evidence/live-runtime.md with timestamps,
image LastTagTime and image IDs before/after, the RED and GREEN outputs, every annotation, the arm results, and
the freshness output. Commit the two specs: `test(quick-261016-edv): live forced-light spec and the 37-55 UAT spec under forced light`.
No AI attribution; read the message back.
  </action>
  <verify>
    <automated>(set -a; . ./.env; set +a; cd frontend && npx playwright test e2e/dashboard-forced-light.spec.ts --reporter=list); echo "rc=$?"</automated>
    <automated>(set -a; . ./.env; set +a; cd frontend && npx playwright test e2e/staff-webhooks-uat.spec.ts --project=desktop --reporter=list); echo "rc=$?"</automated>
    <automated>scripts/check-runtime-freshness.sh; echo "rc=$?"</automated>
    <automated>scripts/check-e2e-baseurl-contract.sh; echo "rc=$?"</automated>
  </verify>
  <done>
    live-runtime.md shows the new spec RED on the pre-rebuild runtime on its forced-light assertions, the
    rebuild, freshness rc 0 with 0 unverified, and GREEN: the new spec 2 passed 0 skipped, the 37-55 spec 3 passed
    0 skipped, dashboard-mobile passing; every instrument arm failed as designed in every run; the base-URL
    contract exits 0; the spec commit is in the log with no attribution lines.
  </done>
</task>

<task type="auto">
  <name>Task 3: Metrics, prose counts, count oracles, and the deferred-items status</name>
  <files>docs/metrics.json, CLAUDE.md, AGENTS.md, README.md, .planning/phases/37-real-world-operations-readiness/deferred-items.md</files>
  <action>
Run `scripts/docs-freshness.sh --write` to regenerate docs/metrics.json, then update every prose count
scripts/check-doc-metrics.sh checks in CLAUDE.md, AGENTS.md and README.md (the jest, Playwright and total
figures; the rules are listed in that script, lines 64 onward) to the regenerated values. Ask the runners, not
only the static reader: produce a jest JSON report into the session scratchpad with
`npm --prefix frontend test -- --ci --watchAll=false --json --outputFile=<scratchpad>/jest-report.json`, then run
`bash scripts/check-test-count-oracle.sh jest --report <scratchpad>/jest-report.json` and
`bash scripts/check-test-count-oracle.sh playwright`. Show check-doc-metrics can fail: apply the arm documented in
its own header (change one README count, expect rc 1), restore, verify by content (`git hash-object README.md`
equals the pre-arm value), re-run expecting 0.

In .planning/phases/37-real-world-operations-readiness/deferred-items.md, in the "From 37-55" entry whose title
begins "In the dark theme the dashboard page content", change its `status: open` line to
`status: ruled: forced light (quick 261016-edv); dark pass = Phase 39 candidate`. Touch nothing else there.

Closing clean: `scripts/check-runtime-freshness.sh` rc 0 again (nothing in this task is in a build context, so it
must still be clean; if it is not, find out why before committing). Commit docs/metrics.json, the three prose
files, deferred-items.md and the evidence directory:
`docs(quick-261016-edv): metrics, prose counts and the 37-55 dark-theme status`. No AI attribution; read back.
Do not push.
  </action>
  <verify>
    <automated>scripts/docs-freshness.sh; echo "rc=$?"</automated>
    <automated>scripts/check-doc-metrics.sh; echo "rc=$?"</automated>
    <automated>bash scripts/check-test-count-oracle.sh playwright; echo "rc=$?"</automated>
    <automated>rg -uu -c "status: ruled: forced light \(quick 261016-edv\); dark pass = Phase 39 candidate" .planning/phases/37-real-world-operations-readiness/deferred-items.md; echo "rc=$?"</automated>
  </verify>
  <done>
    docs-freshness and check-doc-metrics exit 0 with the doc-metrics fail arm recorded (rc 1 then 0, restore
    verified by hash); both count oracles exit 0; the deferred-items status line matches exactly once; freshness
    rc 0 at close; `git status --short` clean; nothing pushed.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| browser storage -> theme store | localStorage `theme` is writable by any script on the origin and is read by the hook |
| theme store -> DOM | the only DOM sink is `classList.toggle("dark", boolean)` on documentElement |
| module exports -> production bundle | the test seam ships in the client bundle |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-261016edv-01 | Tampering | use-theme.ts getSnapshot / readStored | low | mitigate | While the flag is false getSnapshot returns false before readStored runs, so no stored value reaches render; the T-34-02-01 two-value narrowing in readStored is kept unchanged for when the flag returns |
| T-261016edv-02 | Tampering (data integrity) | use-theme.ts applyTheme | low | mitigate | Early return before localStorage.setItem keeps the user's stored choice byte-identical; proven by the setItem spy case (break arm B turns it red) and by the live localStorage read after load, reload and a second route |
| T-261016edv-03 | Elevation of Privilege | `__setDarkThemeAvailableForTests` export | low | accept | Reachable only by code that imports the module (not on window); the worst outcome is the cosmetic dark theme returning; it grants no data access and changes no request |
| T-261016edv-SC | Tampering | npm/pip/cargo installs | low | accept | This plan installs no package; no lockfile or manifest changes |
</threat_model>

<verification>
- Jest RED recorded before any production change, GREEN after; full jest, build (type-check) and lint exit 0.
- Break arms A and B each failed as designed; restore verified by sha256 and the closing green run.
- Live: new spec RED on the pre-rebuild runtime, GREEN on the rebuilt one at desktop and mobile with emulated dark
  and stored theme=dark; the 37-55 UAT spec and dashboard-mobile still pass; every instrument arm failed as
  designed; freshness rc 0 with 0 unverified.
- docs-freshness, check-doc-metrics (with its fail arm), both count oracles and the base-URL contract exit 0.
- Commits in order: test (jest) -> feat -> test (e2e) -> docs, none carrying AI attribution, nothing pushed.
</verification>

<success_criteria>
- A vendor whose browser stores theme=dark and whose system prefers dark sees the light dashboard, with no theme
  control on desktop or mobile, and their stored choice is still "dark" afterwards.
- Re-enabling dark is the one-line flip of DARK_THEME_AVAILABLE; every pre-existing theme test still covers the
  flag-on behaviour through the seam.
- The running frontend matches the branch, and every count gate agrees with the runners.
- The 37-55 deferred item reads "ruled: forced light (quick 261016-edv); dark pass = Phase 39 candidate".
</success_criteria>

<output>
Create `.planning/quick/261016-edv-force-the-light-theme-on-the-dashboard-u/261016-edv-SUMMARY.md` when done,
including: the RED/GREEN/arm evidence pointers, the dark-class appliers search with its positive control, the
themeColor observation, the metrics before/after, the dev-stack residue (the STAFF account G-37-12 created), and
the four commit shas.
</output>
