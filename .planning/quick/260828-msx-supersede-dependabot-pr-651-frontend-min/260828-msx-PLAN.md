---
phase: quick-260828-msx
plan: 01
type: execute
wave: 1
depends_on: []
autonomous: false
requirements: [DEPS-651]
files_modified:
  - frontend/package.json
  - frontend/package-lock.json
  - frontend/__tests__/shop/server-seeded-islands.test.tsx
  - frontend/components/dashboard/__tests__/dashboard-shell.test.tsx
  - frontend/lib/__tests__/structured-data.test.ts
  - CLAUDE.md
  - AGENTS.md
  - README.md
  - .planning/codebase/STACK.md
  - .planning/codebase/INTEGRATIONS.md
  - HANDOFF.md

must_haves:
  truths:
    - "frontend/package.json declares all 10 bumped versions and package-lock.json agrees"
    - "npx tsc --noEmit exits 0 in frontend/ (measured baseline: exits 2 with exactly 3 errors)"
    - "npm run build, npm test, npm run lint and check-e2e-typecheck.sh all exit 0"
    - "Every live doc naming a bumped package's version states the NEW version"
    - "check-handoff-contract.sh exits 0 (measured baseline: exits 1 on the #651 OPEN claim)"
    - "A PR exists that supersedes #651 without any closing keyword, and #651 stays CLOSED-not-reopened"
  artifacts:
    - path: "frontend/package.json"
      provides: "the 10 bumped dependency declarations"
      contains: "\"next\": \"^16.3.2\""
    - path: "frontend/lib/__tests__/structured-data.test.ts"
      provides: "a type-correct productNode cast that still asserts price/currency/availability"
    - path: "HANDOFF.md"
      provides: "the corrected #651 disposition (closed, superseded)"
  key_links:
    - from: "frontend/package.json"
      to: "CLAUDE.md / AGENTS.md / .planning/codebase/STACK.md / README.md"
      via: "scripts/check-doc-versions.sh claim table"
      pattern: "check-doc-versions\\.sh"
    - from: "HANDOFF.md"
      to: "the GitHub forge state of #651"
      via: "scripts/check-handoff-contract.sh H-2"
      pattern: "check-handoff-contract\\.sh"
---

<objective>
Supersede dependabot PR #651 (closed unmerged 2026-08-28T15:25:33Z) by landing its 10-package
frontend `minor-and-patch` bump on a branch that also fixes the three type errors the bump exposes
and reconciles every doc version pin it falsifies.

Purpose: #651 could not merge — five red jobs. Two of those were stale-base and are already clear
on this branch; the rest are real work. The bump is also security-relevant: `next` 16.3.0+ updates
vendored lodash to 4.17.23, closing the CVE-2025-13465 item deferred in HANDOFF.md.

Output: a green branch and a PR that supersedes #651 without reopening or closing it.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@./CLAUDE.md
@.claude/skills/proof-standards/SKILL.md

Branch `feature/deps-frontend-651-supersede` already exists, is clean, and is 0 ahead / 0 behind
`origin/main` (efcc3ee9). Do NOT create a branch.
</context>

<measured_baseline>
Every figure below was executed on this branch at efcc3ee9 on 2026-08-28 BEFORE any edit.
These are the control arm: they are how you tell your own red from a pre-existing one.

| instrument | rc | detail |
|---|---|---|
| `cd frontend && npm run build` | **0** | "Compiled successfully in 4.9s" |
| `cd frontend && npx tsc --noEmit` | **2** | **exactly the 3 errors below — PRE-EXISTING** |
| `bash scripts/check-e2e-typecheck.sh` | 0 | 25 files under frontend/e2e |
| `bash scripts/check-doc-versions.sh` | 0 | |
| `bash scripts/check-doc-citations.sh` | 0 | (#651's "1 citation violation" WAS stale-base — already clear) |
| `bash scripts/docs-freshness.sh` | 0 | |
| `bash scripts/check-doc-metrics.sh` | 0 | |
| `bash scripts/check-changelog-contract.sh` | 0 | 11 feat/fix in range, 11 cited |
| `bash scripts/check-branch-behind-base.sh` | 0 | 0 ahead, 0 behind |
| `bash scripts/check-handoff-contract.sh` | **1** | **H-2: "HANDOFF.md claims #651 is OPEN but the forge says closed"** |

`docs/metrics.json`: jest_blocks 1230 / jest_files 120 / playwright_blocks 113 / playwright_specs 22
/ total_logical_invocations 3188. **No test block is added or removed by this plan** — the three
edits fix types inside existing blocks — so these counts MUST NOT change and `docs-freshness.sh`
must stay rc=0 WITHOUT `--write`. If it wants to rewrite them, you changed something you should not have.

## Three findings that contradict the brief — read before starting

1. **The three type errors are PRE-EXISTING on clean main, not caused by the bump.** `next build`
   type-checks the pages/app graph, not every file in the tsconfig program — ci-cd.yaml:103-113 says
   so and this was re-measured: build rc=0 while `tsc --noEmit` rc=2 on the SAME unmodified tree.
   The bump exposes them; it does not create them. This is good news — **the fail direction is
   already recorded**, so `tsc --noEmit` going 2 → 0 is a falsifiable two-direction result, not a
   check observed only passing.

2. **The handoff failure is NOT stale-base and IS in scope.** #651 was closed today at 15:25:33Z
   (`mergedAt: null`). `HANDOFF.md:274` still reads "**#651 OPEN**". That is why
   `check-handoff-contract.sh` is red on this clean branch right now. Fixing it is this plan's job.

3. **The stale Stripe versions in `.planning/codebase/INTEGRATIONS.md` are invisible to BOTH gates.**
   `check-doc-versions.sh` does not read that file (its DOCS list is CLAUDE.md, AGENTS.md,
   STACK.md, README.md). `check-doc-citations.sh` C-3 needs only ONE strong token on the cited line
   and matches the package NAME, so it stays green over a wrong version number — the known
   "citation gate certifies the FILE not the line" trap. **No gate will catch this. Fix it by hand.**
</measured_baseline>

<tasks>

<task type="auto">
  <name>Task 1: Bump the 10 packages and fix the three latent type errors</name>
  <files>frontend/package.json, frontend/package-lock.json, frontend/__tests__/shop/server-seeded-islands.test.tsx, frontend/components/dashboard/__tests__/dashboard-shell.test.tsx, frontend/lib/__tests__/structured-data.test.ts</files>
  <action>
Set these exact ranges in `frontend/package.json`, then run `npm install` in `frontend/` so
`package-lock.json` is regenerated consistently (do NOT hand-edit the lockfile):

dependencies — `@hookform/resolvers` ^5.9.1, `@stripe/react-stripe-js` ^6.8.2,
`@stripe/stripe-js` ^9.14.0, `lucide-react` ^1.33.0, `next` ^16.3.2, `react-hook-form` ^7.85.0.
devDependencies — `@testing-library/jest-dom` ^7.0.1, `@testing-library/user-event` ^14.6.5,
`@types/node` 26.2.0 (exact, no caret — matches the existing pin style), `eslint-config-next` ^16.3.2.

Then fix the three type errors. These must be REAL fixes honouring each test's intent.
`as any`, `@ts-expect-error`, `@ts-ignore` and blanket `as unknown as` are FORBIDDEN — each would
delete the assertion the test exists to make.

(a) `frontend/__tests__/shop/server-seeded-islands.test.tsx:100` — TS2739, the
`initial={{ ... }}` literal is missing `first` and `last` from `PageResponse<PublicShop>`
(`frontend/types/api.ts:3`). Add `first: true, last: true`. Those are the semantically correct
values, not filler: the literal already declares `totalPages: 1, number: 0`, i.e. one complete page,
which is both the first and the last. Re-run tsc afterwards — if other literals in the file carry
the same omission, tsc names them; fix each the same way, deriving first/last from that literal's
own `number`/`totalPages`, never by copying `true, true`.

(b) `frontend/components/dashboard/__tests__/dashboard-shell.test.tsx:154` — TS2503, `JSX.Element`
in the `jest.requireActual` cast. React 19 types removed the global `JSX` namespace. Add
`import type { ReactElement } from "react"` at the top and change the cast to
`{ Sidebar: () => ReactElement }`. (`React.JSX.Element` also compiles but needs a React import the
file does not otherwise have; prefer `ReactElement`.)

(c) `frontend/lib/__tests__/structured-data.test.ts:91` — TS2352, the cast
`as Record<string, never> & { offers: Record<string, string> }`. `Record<string, never>` says every
property is `never`, which is why nothing overlaps; the intent was plainly an index-accessible bag.
Change the first half to `Record<string, unknown>`, giving
`as Record<string, unknown> & { offers: Record<string, string> }`. Verify by reading the WHOLE
`describe("productNode")` block that every existing assertion still compiles and still asserts what
it did — `node["@type"]`, `node.offers["@type"]`, `node.offers.price`, `node.offers.priceCurrency`
and `node.offers.availability` must all survive unchanged. The £8.50-not-850 pricing assertion is
the point of the block; if your cast weakens it, the cast is wrong.

Note on the toolchain: local node is v22.23.2 while CI pins node 24 (ci-cd.yaml:58/276/532/933).
The pre-bump build was green on v22, but local green is NOT proof of CI green. If `npm install` or
the build complains about the node floor under next 16.3.2, say so in the SUMMARY rather than
working around it silently.
  </action>
  <verify>
    <automated>
cd frontend
out=$(npx tsc --noEmit 2>&1); rc=$?; echo "tsc rc=$rc"; printf '%s\n' "$out" | grep -c "error TS"
# EXPECT rc=0, zero "error TS" lines.
# FAIL DIRECTION ALREADY RECORDED on the unmodified tree: rc=2 with exactly 3 errors —
# TS2739 at server-seeded-islands.test.tsx(100,11), TS2503 at dashboard-shell.test.tsx(154,22),
# TS2352 at structured-data.test.ts(91,16). This instrument is proven able to fail.
out=$(npm run build 2>&1); rc=$?; echo "build rc=$rc"
out=$(npm test -- --ci --watchAll=false 2>&1); rc=$?; echo "jest rc=$rc"; printf '%s\n' "$out" | grep -E "^Tests:|^Test Suites:"
out=$(npm run lint 2>&1); rc=$?; echo "lint rc=$rc"
cd .. && out=$(bash scripts/check-e2e-typecheck.sh 2>&1); rc=$?; echo "e2e-typecheck rc=$rc"
    </automated>
  </verify>
  <done>
`tsc --noEmit` rc=0 with zero TS errors (down from a recorded rc=2 / 3 errors); build, jest, lint
and check-e2e-typecheck all rc=0; jest reports 120 suites / 1230 tests to match docs/metrics.json.
Do NOT read eslint's last line as the verdict — it is the FIXABLE count; use the rc.
  </done>
</task>

<task type="auto">
  <name>Task 2: Reconcile every falsified doc version pin and the #651 handoff claim</name>
  <files>CLAUDE.md, AGENTS.md, README.md, .planning/codebase/STACK.md, .planning/codebase/INTEGRATIONS.md, HANDOFF.md</files>
  <action>
Run `bash scripts/check-doc-versions.sh` FIRST, post-bump and pre-edit. It should now be rc=1 and
name the stale claims. **Fix every claim it names** — that list is authoritative over the
enumeration below, which is a pre-measured aid, not a substitute.

`.planning/codebase/STACK.md` is the UPSTREAM SOURCE: AGENTS.md lines 19-152 are a generated block
(`<!-- GSD:stack-start source:codebase/STACK.md -->`). Fix source and derived together, or the next
GSD regeneration copies the stale values straight back in.

Sites measured stale by this bump (`rg -uu`, exact line numbers as of efcc3ee9):

GATED — the build fails until these are fixed:
- `CLAUDE.md:25, 52, 140` · `AGENTS.md:24, 51, 139` · `.planning/codebase/STACK.md:9, 58, 188` — "Next.js 16.2.12" → 16.3.2
- `CLAUDE.md:54` · `AGENTS.md:53` · `.planning/codebase/STACK.md:60` — "React Hook Form 7.84.0" → 7.85.0
- `README.md` — run the gate; fix whatever it names.

UNGATED but falsified — **no gate will catch these, fix them by hand** (see finding 3 above):
- `CLAUDE.md:78` · `AGENTS.md:77` — "Stripe React/JS 6.8.0, 9.12.0" → "6.8.2, 9.14.0"
- `.planning/codebase/INTEGRATIONS.md:10` — `@stripe/react-stripe-js` 6.8.0 → 6.8.2 and
  `@stripe/stripe-js` 9.12.0 → 9.14.0. Leave the `frontend/package.json:27-28` citation span alone:
  the bump changes values on those lines, not the line count, so the citation still resolves.
- `.planning/codebase/STACK.md:60` — `@hookform/resolvers` 5.2.2 → 5.9.1 (already stale pre-bump)
- `.planning/codebase/STACK.md:68` — `lucide-react` 1.28.0 → 1.33.0
- `.planning/codebase/STACK.md:83` — `@testing-library/jest-dom` 6.1.5 → 7.0.1 (already stale
  pre-bump) and `@testing-library/user-event` 14.5.1 → 14.6.5
- `AGENTS.md:494` — "Knows Next 16.2.12 App Router" (roster prose, OUTSIDE the generated block) → 16.3.2

**DO NOT TOUCH — these match the same tokens and editing them would be a defect:**
- `docs/troubleshooting/DOCKER_IPTABLES_ISSUE.md:61` — "Linux 6.8.0-90-generic" is a KERNEL version
- `docs/architecture/SYSTEM_DESIGN_V2.md:818` — `version: 'v1.28.0'` is a Kubernetes version
- `.qa-council/**` and `.planning/phases/**`, `.planning/quick/**` — dated historical records.
  check-doc-versions.sh excludes `.planning/PROJECT.md` for exactly this reason; the same logic applies.
- `.github/chatmodes/oaas-frontend.chatmode.md`, `.github/instructions/oaas-frontend.instructions.md`,
  `docs/PRD.md:234`, `docs/architecture/ESSENTIAL_ARCHITECTURE.md:78` all carry "Next.js 16.2.12"
  and are ungated. They are OUT OF SCOPE for this quick plan — but record them in the SUMMARY as
  known residual drift so the next reader does not have to re-measure it.

Never sed a bare version token across `*.md`. Edit the named line, or anchor the pattern on the
package name.

Finally, correct `HANDOFF.md:274-275`. Line 274 currently reads "**#651 OPEN** — five failures
including Run Tests and Frontend E2E. Real breakage, real work." #651 is CLOSED (unmerged,
2026-08-28T15:25:33Z) and superseded by this branch. Rewrite both lines to state that, and keep
line 275's "#654 and #651 are the two dependabot PRs left" honest — #654's disposition is unchanged,
#651's is not. Preserve the H-2 claim shape the gate parses (a capitalised state word next to the
number); do not simply delete the sentence, or you trade a false claim for no claim.
  </action>
  <verify>
    <automated>
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
for g in check-doc-versions check-doc-citations check-handoff-contract docs-freshness check-doc-metrics check-changelog-contract check-branch-behind-base; do
  out=$(bash scripts/$g.sh 2>&1); rc=$?
  echo "$g rc=$rc"
  [ "$rc" -ne 0 ] && printf '%s\n' "$out" | tail -6
done
# EXPECT every one rc=0.
# FAIL DIRECTIONS RECORDED PRE-EDIT: check-handoff-contract was rc=1 naming the #651 OPEN claim,
# and check-doc-versions goes rc=1 post-bump/pre-edit. Both are proven able to fail. rc=2 is VOID
# (missing input / zero claims extracted) and is NOT a pass — investigate, never accept.
git diff --stat docs/metrics.json
# EXPECT EMPTY. No test block was added or removed, so the counts must not move.
    </automated>
  </verify>
  <done>
All seven gates rc=0. `docs/metrics.json` is unchanged (empty diff — 1230/120/113/22/3188 hold).
The stale INTEGRATIONS.md Stripe versions are corrected even though no gate demanded it.
  </done>
</task>

<task type="checkpoint:human-verify" gate="blocking">
  <name>Task 3: Commit, then open the superseding PR</name>
  <files>git commit on feature/deps-frontend-651-supersede; PR body written to the session scratchpad</files>
  <action>
Commit the work and prepare — but do NOT yet open — the superseding PR. Opening the PR happens only
after the operator approves the exact wording at the resume signal below.

Stage and commit on the existing `feature/deps-frontend-651-supersede` branch with subject:
`chore(deps): bump the frontend minor-and-patch group, fixing 3 latent type errors`

`chore` is deliberate: `check-changelog-contract.sh` requires a `docs/CHANGELOG.md` citation only
for merged **feat/fix** PRs (measured: "11 feat/fix in range, 11 cited"), so a chore needs none and
adding one would be noise. **Add NO `Co-Authored-By` trailer** — the operator's standing policy
forbids it on anything.

Write the PR body to a file (e.g. the session scratchpad), never to an inline `-m`/`-b` string.
Backticks inside double quotes EXECUTE and are silently dropped from the stored text, and this body
must name commands. Use `gh pr create --title ... --body-file <path>`.

The body MUST contain the literal `Supersedes #651` and MUST NOT contain any GitHub closing keyword
(close/closes/closed/fix/fixes/fixed/resolve/resolves/resolved) ANYWHERE before `#651`. The parser
is lexical — "does not close #651" still closes #651. If you must refer to its state, write it as
`#651 was closed unmerged` (number BEFORE the keyword), never `closed #651`.

Body should state: which 10 packages moved and to what; that the three type errors were
pre-existing and invisible to `next build`, with the tsc 2 → 0 before/after; which doc pins were
reconciled; the residual ungated drift from Task 2; and that next 16.3.0+ closes CVE-2025-13465
via vendored lodash 4.17.23.
  </action>
  <verify>
  <human-check>
1. `git log -1 --format=%B` — read back the STORED commit message. Confirm no backtick phrase was
   eaten and no `Co-Authored-By` trailer is present. (Write-time appearance proves nothing.)
2. `cat <body-file>` — read the exact PR body you are about to send. Confirm `Supersedes #651` is
   present and grep it for closing keywords:
   `grep -nEi '(clos|fix|resolv)[a-z]*[^.]{0,40}#651' <body-file>` — expect ZERO matches.
3. `bash scripts/check-branch-behind-base.sh` — expect rc=0, "0 behind". A branch behind its base
   ships a runtime missing already-merged work and no rebuild can fix it.
4. Confirm Task 1 and Task 2 verifications are all still rc=0 on the committed tree.
5. Show the operator the exact title and body, then STOP.
  </human-check>
  </verify>
  <done>
The commit is stored uncorrupted with no `Co-Authored-By` trailer; the PR body file contains
`Supersedes #651` and zero closing keywords before that number; the operator has seen the exact
title and body. After approval: the opened PR reads back `Supersedes #651` from the forge and
#651 is still CLOSED.
  </done>
  <resume-signal>
Type "approved" to open the PR, or describe the wording changes you want first.

AFTER approval and `gh pr create`, verify by reading back from the forge, not from your own input:
- `gh pr view <new> --json body -q .body | grep -c 'Supersedes #651'` → expect 1
- `gh pr view 651 --json state -q .state` → expect `CLOSED`. If this says `OPEN`, the body reopened
  #651 via a linkage you did not intend — that is the failure this checkpoint exists to prevent.
- `gh pr checks <new>` — remember rc=1 means failed OR unreachable, and an empty table is VOID,
  not a pass.
  </resume-signal>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| npm registry → developer machine + CI | third-party package code executes at install and at build |
| this branch → GitHub forge | commit/PR prose is interpolated by a shell and parsed by GitHub's linker |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-651-01 | Tampering | npm install of 10 bumped packages | accept | **No new package NAME is introduced** — all 10 already exist in `frontend/package.json` and move within the same major. The Package Legitimacy Gate targets slopsquatted new names; a version bump of an already-trusted name is not that shape. `package-lock.json` is regenerated by `npm install`, never hand-edited, so integrity hashes are registry-derived. |
| T-651-02 | Elevation of Privilege | `next` 16.2.12 → 16.3.2 | mitigate | This bump is a security IMPROVEMENT: 16.3.0+ updates vendored lodash to 4.17.23, closing the CVE-2025-13465 item deferred in HANDOFF.md. Record it in the PR body so the deferral can be discharged. |
| T-651-03 | Tampering | commit message / PR body prose | mitigate | Backticks inside double quotes execute and are silently dropped. Task 3 mandates `--body-file` and a quoted heredoc, plus a read-back of the STORED text (`git log -1 --format=%B`, `gh pr view --json body`) because the corruption is invisible at write time. |
| T-651-04 | Repudiation | GitHub issue-linking on #651 | mitigate | A closing keyword before `#651` would silently alter the disposition of a PR the operator closed by hand. Blocking human checkpoint + an explicit grep for closing keywords + a post-open re-read of `#651`'s state. |
| T-651-05 | Information Disclosure | test-file type fixes | accept | The three edits touch assertion typing only; no fixture, credential or PII is added. `as any` is forbidden precisely so no assertion is silently disabled. |
| T-651-SC | Tampering | npm install (supply chain) | mitigate | No `[ASSUMED]`/`[SUS]`/`[SLOP]` package is introduced (see T-651-01), so no legitimacy checkpoint is required. CI's existing trivy/dependabot scanners cover the resulting lockfile. |
</threat_model>

<verification>
Run from the repo root on the committed tree. Every one of these has a recorded fail direction in
`<measured_baseline>` or was proven able to fail during execution.

1. `cd frontend && npx tsc --noEmit` → rc=0 (was rc=2 / 3 errors — the primary two-direction proof)
2. `cd frontend && npm run build` → rc=0
3. `cd frontend && npm test -- --ci --watchAll=false` → rc=0, 120 suites / 1230 tests
4. `cd frontend && npm run lint` → rc=0 (read the rc, NOT eslint's trailing fixable count)
5. `bash scripts/check-e2e-typecheck.sh` → rc=0, 25 files
6. `bash scripts/check-doc-versions.sh` → rc=0 (rc=2 is VOID, not a pass)
7. `bash scripts/check-doc-citations.sh` → rc=0
8. `bash scripts/check-handoff-contract.sh` → rc=0 (was rc=1 on #651 OPEN)
9. `bash scripts/docs-freshness.sh` → rc=0 **without** `--write`
10. `bash scripts/check-doc-metrics.sh` → rc=0
11. `bash scripts/check-changelog-contract.sh` → rc=0
12. `bash scripts/check-branch-behind-base.sh` → rc=0, 0 behind
13. `git diff --stat docs/metrics.json` → empty

NOT run and NOT required by this plan: `check-runtime-freshness.sh`. This change never reaches a
running container (no `docker compose` rebuild is in scope), so runtime parity is not asserted here.
Say so in the SUMMARY rather than reporting a gate that was not exercised.
</verification>

<success_criteria>
- All 13 verification items pass, each with its rc captured on the same statement as its command.
- The three type errors are fixed with real types — zero occurrences of `as any`, `@ts-ignore`,
  `@ts-expect-error` or a blanket `as unknown as` in the three edited files.
- `docs/metrics.json` is byte-unchanged.
- A PR exists containing the literal `Supersedes #651`, with zero closing keywords before `#651`,
  and `#651` is still `CLOSED` after the PR is opened.
- The SUMMARY records: the tsc 2→0 before/after with both directions; that the three errors were
  pre-existing rather than bump-induced; the residual ungated Next.js version drift left in
  `.github/chatmodes/`, `.github/instructions/`, `docs/PRD.md` and `docs/architecture/`; and that
  runtime parity was deliberately not asserted.
</success_criteria>

<output>
Create `.planning/quick/260828-msx-supersede-dependabot-pr-651-frontend-min/260828-msx-SUMMARY.md` when done.
</output>
</content>
