---
phase: quick-260808-sxi
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - .github/workflows/ci-cd.yaml
  - frontend/e2e/marketing-dish-scroller.spec.ts
  - .planning/ROADMAP.md
autonomous: true
requirements: [REVIEW-CI-FILTER, REVIEW-VACUOUS-LOCATOR, REVIEW-STALE-ROADMAP]
branch: fix/33-review-quick-items
base_commit: "6528e562"

must_haves:
  truths:
    - "The push trigger in ci-cd.yaml carries a pattern ('phase/**') that can match slash-style phase branches; 'phase-*' is retained for old-style names"
    - "The dish-scroller arrow locator interpolates the side parameter and resolves to exactly one element per side, so no hidden-assertion can ever again pass against zero matches"
    - "ROADMAP's Phase 33 row equals the measured SUMMARY count (5/8 at planning time) and the jtoye.co.uk line states registered-but-parked, not never-registered"
  artifacts:
    - path: ".github/workflows/ci-cd.yaml"
      provides: "push branches filter including 'phase/**'"
      contains: "phase/**"
    - path: "frontend/e2e/marketing-dish-scroller.spec.ts"
      provides: "interpolated arrow testid + toHaveCount(1) non-vacuity guards"
      contains: "dish-scroll-${side}"
    - path: ".planning/ROADMAP.md"
      provides: "corrected Phase 33 progress row and domain-registration claim"
  key_links:
    - from: "frontend/e2e/marketing-dish-scroller.spec.ts"
      to: "frontend/components/marketing/dish-scroller.tsx"
      via: "getByTestId template literal matching data-testid={`dish-scroll-${side}`} at dish-scroller.tsx:227"
      pattern: "dish-scroll-\\$\\{side\\}"
---

<objective>
Fix three review quick items found on branch phase/33-the-consumer-product: (1) the CI push
filter cannot match `phase/` branches so the 27-commit phase branch has zero push-triggered runs;
(2) a vacuous Playwright locator (`dish-scroll-` with the `${side}` interpolation missing) makes
all dish-scroller arrow coverage dead — `toBeHidden` passes against zero matches; (3) two stale
facts in ROADMAP.md (Phase 33 progress 3/8 vs 5 measured SUMMARYs, and a "jtoye.co.uk was never
registered" claim that measured false on 2026-08-08).

All three defects were verified by the supervising session before planning. Cite them as given.

Purpose: restore CI coverage on the active phase branch, revive dead E2E arrow assertions with a
guard that makes vacuity impossible, and stop ROADMAP misinforming the next planner.
Output: three atomic commits on fix/33-review-quick-items (based on 6528e562), one per fix.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@./CLAUDE.md

Branch discipline: work on `fix/33-review-quick-items`, based on `phase/33-the-consumer-product`
HEAD `6528e562`. Plan against THAT tree, not main. `6528e562` is the pinned broken baseline for
every fail-direction check below — never use a `git show X 2>/dev/null || cat file` fallback shape
(it silently compares a file to itself).

Grep discipline (this repo's recorded traps):
- `grep` here is ugrep; `{`/`}` are metacharacters — a literal pattern containing `${side}` MUST
  use `grep -F` or it silently returns 0.
- Never `cmd | grep -q X` under pipefail (SIGPIPE inversion) — use `grep -c` (reads full input) and
  compare printed counts, or here-strings.
- Always pass explicit file paths to grep (`.planning/` is a dotdir; directory sweeps silently
  skip it).
- Capture exit codes on the same statement as the command, never after an echo.

Commit discipline: commit message bodies via `-F <file>` or a quoted heredoc (`<<'EOF'`), never an
interpolating string. No Co-Authored-By trailers. `git diff --staged` before every commit (a
second session may be driving this checkout).
</context>

<interfaces>
Existing contracts the fixes must match — no exploration needed:

From frontend/components/marketing/dish-scroller.tsx line 227 (NOT modified by this plan):

    data-testid={`dish-scroll-${side}`}    // side: "left" | "right"

From frontend/e2e/marketing-dish-scroller.spec.ts lines 90-91 (the defect):

    const arrow = (page: Page, side: "left" | "right") =>
      scroller(page).locator("xpath=..").getByTestId(`dish-scroll-`)

From .github/workflows/ci-cd.yaml lines 3-9 (the defect — `*` does not cross `/` in GitHub branch
filters, so `phase-*` can never match `phase/33-the-consumer-product`):

    on:
      push:
        branches: [main, 'phase-*']
      pull_request:
        branches: [main]

From .planning/ROADMAP.md (the two stale facts, verbatim at planning time):

    line 349: | 33. The Consumer Product | v2.3 | 3/8 | In Progress | — |
    line 367: starts until they land: (1) the production domain (`jtoye.co.uk` was never registered;
    line 368: `FRONTEND_PUBLIC_*` point at `olajay.co.uk`; no A records); (2) the hosting target; ...

Measured at planning time: 5 SUMMARY files exist under .planning/phases/33-the-consumer-product/
(33-00 through 33-04). SUMMARY.md presence is the completion marker.
</interfaces>

<tasks>

<task type="auto">
  <name>Task 1: Add 'phase/**' to the push branch filter in ci-cd.yaml</name>
  <files>.github/workflows/ci-cd.yaml</files>
  <action>
  Edit line 5 of .github/workflows/ci-cd.yaml: change the push branches list from
  `branches: [main, 'phase-*']` to `branches: [main, 'phase-*', 'phase/**']`. Keep `'phase-*'`
  (old-style hyphenated phase branches still exist in history and may recur). Do NOT touch the
  `pull_request` or `release` triggers, and do not add `fix/**` or any other pattern — the
  orchestrator scoped this to exactly the phase-branch gap. Single-quote the new pattern in the
  YAML flow sequence exactly as shown so `**` is a literal glob, not a YAML alias error.

  Commit atomically as `fix(ci): trigger push CI on phase/** branches — 'phase-*' cannot cross the slash`
  with a body noting the evidence: branch phase/33-the-consumer-product has 27 commits and zero
  push-triggered runs (gh run list --branch returned empty, measured 2026-08-08). Write the body
  via a quoted heredoc or -F file.
  </action>
  <verify>
    <automated>
    Pass direction: `grep -Fc "'phase/**'" .github/workflows/ci-cd.yaml` prints 1, and
    `python3 -c "import yaml,sys; d=yaml.safe_load(open('.github/workflows/ci-cd.yaml')); b=d[True]['push']['branches'] if True in d else d['on']['push']['branches']; print(b); sys.exit(0 if 'phase/**' in b and 'phase-*' in b and 'main' in b else 1)"`
    exits 0 (note: PyYAML parses bare `on` as boolean True — the command handles both keys).
    Fail direction (proves the grep can fail): `git show 6528e562:.github/workflows/ci-cd.yaml | grep -Fc "'phase/**'"`
    prints 0. If actionlint is installed (`command -v actionlint`), run it on the file; if absent,
    record that it was not run.
    </automated>
  </verify>
  <done>
  ci-cd.yaml push filter contains main, 'phase-*', and 'phase/**'; YAML parses; both grep
  directions recorded with their printed counts. Explicitly record in the SUMMARY what was NOT
  executed: the live trigger cannot be proven locally — it is proven the first time a push lands
  on phase/33-the-consumer-product after this merges, when
  `gh run list --branch phase/33-the-consumer-product` becomes non-empty. Note also that the fix
  branch itself (fix/33-review-quick-items) matches neither pattern, so an absent run on it is
  expected, not a failure of the fix.
  </done>
</task>

<task type="auto">
  <name>Task 2: Interpolate ${side} in the arrow locator and add a non-vacuity count guard</name>
  <files>frontend/e2e/marketing-dish-scroller.spec.ts</files>
  <action>
  Two edits to the spec; do NOT modify dish-scroller.tsx.

  (1) Line 91: change the template literal from `dish-scroll-` to `dish-scroll-${side}` so the
  arrow locator actually interpolates its `side` parameter and matches the component's
  `data-testid={`dish-scroll-${side}`}` at dish-scroller.tsx:227.

  (2) Non-vacuity guards, so a hidden-assertion can never again pass against zero matches
  (`toBeHidden` succeeds on an empty locator; `toHaveCount(1)` does not — and it counts ATTACHED
  elements regardless of visibility, so it holds whether the arrow is shown or display:none):
  - In the first test ("discloses more-to-the-right..."), inside the `!canScroll` branch,
    immediately BEFORE the two `toBeHidden` assertions, add
    `await expect(arrow(page, "right"), "arrow locator must resolve — a zero-match locator makes toBeHidden vacuous").toHaveCount(1)`
    and the same for `"left"`.
  - In the third test ("@desktop-only arrows..."), immediately after `const left`/`const right`
    are obtained and BEFORE the overflow branch, add the same pair of `toHaveCount(1)` assertions
    on `left` and `right` (this covers both the fit branch and the overflow branch of that test).
  Add one brief comment at the guard explaining why it exists (toBeHidden passes on zero matches;
  this locator was dead from 33-03 until now because the interpolation was missing). No emoji.
  The count is exactly 1 per side, not >=1: the locator is scoped through the live region's
  wrapper, which is what excludes the streaming staging-buffer copy — a count of 2 would mean that
  scoping regressed.

  Commit atomically FIRST (before running the fail-direction arms, so the restore target is a
  committed state — this repo's recorded break-arm trap): message
  `fix(e2e): interpolate side in dish-scroller arrow testid + non-vacuity count guard`.

  Then verify per the two-path protocol in <verify>. Prefer the live path. Detect the stack with
  `curl -sf -o /dev/null http://localhost:3000/` (capture rc on the same statement). No container
  rebuild is needed on either path: only test code changes, and the break arm mutates the SPEC,
  never the component, so the running frontend image is untouched.
  </action>
  <verify>
    <automated>
    Static checks, BOTH paths, run first:
    (a) `grep -Fc 'dish-scroll-${side}' frontend/e2e/marketing-dish-scroller.spec.ts` prints 1
        (grep -F is mandatory — braces are ugrep metacharacters and a non-F literal silently prints 0);
    (b) instrument fail direction: `git show 6528e562:frontend/e2e/marketing-dish-scroller.spec.ts | grep -Fc 'dish-scroll-${side}'`
        prints 0 — proves grep (a) distinguishes fixed from broken;
    (c) `grep -Fc 'toHaveCount(1)' frontend/e2e/marketing-dish-scroller.spec.ts` prints 4;
    (d) from frontend/: `npx playwright test marketing-dish-scroller --list` exits 0 and lists 3 tests.

    LIVE PATH (stack up — required if available):
    1. Green arm: `npx playwright test marketing-dish-scroller` passes; record pass/fail per test.
    2. Break arm (fail direction, REQUIRED): temporarily mutate the spec's locator only — change
       `dish-scroll-${side}` to `dish-scroll-X${side}` — re-run the spec; it MUST fail, and it must
       fail at a toHaveCount(1) guard (zero matches), not at a vacuous pass. Record the failure line.
    3. Restore from the committed state: `git checkout -- frontend/e2e/marketing-dish-scroller.spec.ts`,
       then verify the restore BY CONTENT: grep (a) prints 1 again and
       `git diff --numstat frontend/e2e/marketing-dish-scroller.spec.ts` is empty.
    4. Clean-state-last: re-run the spec once more green.

    STATIC PATH (stack down): checks (a)-(d) above, plus record verbatim in the SUMMARY what was
    and was not executed: "Playwright executed list-only; no browser run — stack was down; the
    break arm is therefore proven only at the grep level (a vs b), and the first live run of this
    spec after merge is the outstanding functional proof."
    </automated>
  </verify>
  <done>
  Locator interpolates ${side}; four toHaveCount(1) guards present (two per arrow-using test);
  spec compiles (--list exits 0). Live path: green run, then break-arm failure AT THE COUNT GUARD,
  then content-verified restore, then a final green run — all four recorded with real output.
  Static path: (a)=1, (b)=0, (c)=4, (d) rc=0 recorded, with the not-executed statement in the
  SUMMARY. Either way the SUMMARY states which path ran. dish-scroller.tsx has zero diff vs
  6528e562 at task end.
  </done>
</task>

<task type="auto">
  <name>Task 3: Correct the two stale ROADMAP facts (scoped edit — orchestrator-authorized)</name>
  <files>.planning/ROADMAP.md</files>
  <action>
  Orchestrator-authorized exception to the quick-mode "do not update ROADMAP.md" rule: this is a
  factual staleness correction of existing content, not new progress tracking. Scope the edit to
  EXACTLY the two facts below; touch nothing else in the file.

  (a) Phase 33 progress row (~line 349). Re-run the measurement before writing (recorded trap:
  handoff figures go stale): `ls .planning/phases/33-the-consumer-product/*-SUMMARY.md | wc -l` —
  expected 5 (33-00..33-04; SUMMARY.md presence is the completion marker). Set the row's progress
  cell to `<measured>/8` (expected `5/8`). If the measured count differs from 5 at execution time,
  use the measured count and say so in the SUMMARY. Change only the progress cell; the Status
  ("In Progress") and date ("—") cells stay as they are.

  (b) The domain claim (~line 367). The current text reads: "(1) the production domain
  (`jtoye.co.uk` was never registered; `FRONTEND_PUBLIC_*` point at `olajay.co.uk`; no A records);"
  Measured 2026-08-08: jtoye.co.uk IS registered but parked at Namecheap, and HTTPS times out.
  Before writing, re-measure cheaply if tooling allows: `dig +short jtoye.co.uk A` (or `host`);
  if DNS tooling is unavailable, cite the supervisor's 2026-08-08 measurement as given. Rewrite
  item (1) to state: the domain is registered but parked at Namecheap (HTTPS times out, nothing
  serves the application; measured 2026-08-08), and keep the `FRONTEND_PUBLIC_*` point at
  `olajay.co.uk` clause verbatim. If the dig shows parking A records, replace "no A records" with
  "parking A records only — nothing serving the app"; if dig was not run, drop the standalone
  "no A records" claim rather than restate an unverified negative. Do NOT remove or reword items
  (2), (3), (4) or any surrounding decision context, and do not touch the unrelated
  `auth.jtoye.co.uk` mention at line 307.

  Commit atomically: `docs(roadmap): Phase 33 progress 3/8 -> 5/8; jtoye.co.uk is registered (parked), not unregistered`.
  </action>
  <verify>
    <automated>
    Pass direction:
    (a) `grep -Fc '| 33. The Consumer Product | v2.3 | 5/8 |' .planning/ROADMAP.md` prints 1
        (substitute the measured count if it differed), and the old row is gone:
        `grep -Fc '| 33. The Consumer Product | v2.3 | 3/8 |' .planning/ROADMAP.md` prints 0;
    (b) `grep -F 'jtoye.co.uk' .planning/ROADMAP.md | grep -Fc 'never registered'` prints 0, and
        `grep -F 'jtoye.co.uk' .planning/ROADMAP.md | grep -Fc 'parked'` prints 1.
    Fail direction (instrument proof against the pinned baseline):
    `git show 6528e562:.planning/ROADMAP.md | grep -F 'jtoye.co.uk' | grep -Fc 'never registered'` prints 1, and
    `git show 6528e562:.planning/ROADMAP.md | grep -Fc '| 33. The Consumer Product | v2.3 | 3/8 |'` prints 1.
    Scope proof: `git diff -U0 6528e562 -- .planning/ROADMAP.md` shows exactly 2 hunks (the
    progress row and the item-(1) sentence) and `git diff --numstat 6528e562 -- .planning/ROADMAP.md`
    totals no more than ~6 lines each way.
    </automated>
  </verify>
  <done>
  Phase 33 row reads the measured count over 8; the never-registered claim is replaced with
  registered-but-parked (dated); items (2)-(4) and all surrounding context byte-identical; diff
  against 6528e562 confined to the two hunks; all six grep directions recorded with printed counts.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| GitHub push event -> CI workflow | Widening the push filter runs CI (with repo secrets) on more branch names |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-Q-01 | Elevation of Privilege | ci-cd.yaml push trigger | accept | 'phase/**' only matches same-repo branches; fork pushes never receive secrets, and the pull_request trigger (fork path) is unchanged. Writers to phase/ branches are already collaborators with main-push CI access. |
| T-Q-02 | Tampering | spec + ROADMAP edits | accept | Test code and planning docs only; no runtime code, no new dependencies, no package installs (legitimacy gate N/A). |
</threat_model>

<verification>
Source coverage audit (quick mode — sources are the three verified findings):

| Source item | Task | Status |
|---|---|---|
| Finding 1: CI filter cannot match phase/ branches | Task 1 | COVERED |
| Finding 2: vacuous arrow locator + required fail-direction proof | Task 2 | COVERED |
| Finding 3a: Phase 33 row 3/8 vs 5 measured SUMMARYs | Task 3 | COVERED |
| Finding 3b: jtoye.co.uk never-registered claim | Task 3 | COVERED |

Overall: three atomic commits on fix/33-review-quick-items; every grep asserted in both
directions against pinned baseline 6528e562; `git diff --staged` inspected before each commit
(concurrent-working-tree rule); working tree clean at end except intended commits.
</verification>

<success_criteria>
- ci-cd.yaml push filter = [main, 'phase-*', 'phase/**'], YAML-valid, both grep directions recorded
- Arrow locator interpolates ${side}; 4 toHaveCount(1) guards; fail direction proven (live break
  arm at the count guard, or grep a=1/b=0 with the not-executed statement recorded)
- ROADMAP Phase 33 row = measured/8 (expected 5/8); registered-but-parked claim dated 2026-08-08;
  diff vs 6528e562 confined to 2 hunks
- Three atomic commits, no Co-Authored-By, message bodies written via heredoc/-F
- No emoji anywhere in the edits
</success_criteria>

<output>
Create `.planning/quick/260808-sxi-fix-review-quick-items-phase-branch-ci-f/260808-sxi-SUMMARY.md`
when done. The SUMMARY must state, per task, which verification path ran and what was NOT executed
(Task 1: live trigger unprovable locally; Task 2: live vs static path).
</output>
