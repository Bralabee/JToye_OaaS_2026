---
phase: quick-260828-msx
plan: 01
subsystem: frontend-dependencies
tags: [dependabot, deps, typescript, doc-versions, supersede]
status: paused-at-checkpoint
requires: []
provides:
  - frontend minor-and-patch group bump (10 packages)
  - three latent type errors fixed with real types
  - 13 gated + 8 ungated doc version pins reconciled
  - corrected #651 disposition in HANDOFF.md
affects:
  - frontend/package.json
  - frontend/package-lock.json
  - CLAUDE.md
  - AGENTS.md
  - .planning/codebase/STACK.md
  - .planning/codebase/INTEGRATIONS.md
  - HANDOFF.md
tech-stack:
  added: []
  patterns:
    - "declared floor vs lockfile resolution are different numbers; check-doc-versions.sh reads the DECLARED one"
key-files:
  created: []
  modified:
    - frontend/package.json
    - frontend/package-lock.json
    - frontend/__tests__/shop/server-seeded-islands.test.tsx
    - frontend/components/dashboard/__tests__/dashboard-shell.test.tsx
    - frontend/lib/__tests__/structured-data.test.ts
    - CLAUDE.md
    - AGENTS.md
    - .planning/codebase/STACK.md
    - .planning/codebase/INTEGRATIONS.md
    - HANDOFF.md
decisions:
  - "Doc pins state the DECLARED floor (16.3.2), not the lockfile resolution (16.3.3), because check-doc-versions.sh resolves actuals from frontend/package.json with the caret stripped"
  - "The CVE-2025-13465 lodash 4.17.23 figure is recorded as upstream's and explicitly NOT verified by content"
  - "The HANDOFF #651 claim was rewritten, not deleted, to preserve the H-2 claim shape"
metrics:
  duration: ~35 min
  tasks_completed: 2 of 3
  completed: 2026-08-28
---

# Quick 260828-msx: Supersede dependabot PR #651 (frontend minor-and-patch) Summary

Landed #651's 10-package frontend bump on `feature/deps-frontend-651-supersede`, together with
the three type errors the bump exposes (all pre-existing on clean `main`) and the 21 doc version
pins it falsifies. **Execution is PAUSED at Task 3, a blocking human-verify checkpoint** — the
commits are made and the PR title and body are prepared, but `gh pr create` was deliberately NOT
run.

## Status: 2 of 3 tasks complete, stopped at a blocking checkpoint

| Task | Name | Status | Commit |
| --- | --- | --- | --- |
| 1 | Bump the 10 packages and fix the three latent type errors | complete | `db0edfe4` |
| 2 | Reconcile every falsified doc version pin and the #651 handoff claim | complete | `692daa90` |
| 3 | Commit and prepare the superseding PR | **CHECKPOINT — awaiting operator** | n/a |

Branch is 2 ahead / 0 behind `origin/main` (`efcc3ee9`). Working tree carries no tracked
modifications.

---

## THE CHECKPOINT — what the operator needs to approve

`gh pr create` was **not** run, per the blocking-checkpoint contract.

**Prepared PR title** (also the subject of `db0edfe4`):

```
chore(deps): bump the frontend minor-and-patch group, fixing 3 latent type errors
```

**Prepared PR body file:**

```
/tmp/claude-1000/-home-sanmi-IdeaProjects-JToye-OaaS-2026/21e7eb9a-b929-4ff1-a9a6-8f57917253c8/scratchpad/pr-body.md
```

169 lines / 10,595 bytes. It is a file rather than an inline `-m`/`-b` string because the body
names commands in backticks, and backticks inside double quotes execute and are silently dropped
from the stored text.

**The command to run on approval:**

```
gh pr create --title "$(cat <scratchpad>/pr-title.txt)" --body-file <scratchpad>/pr-body.md
```

`chore` is deliberate: `check-changelog-contract.sh` requires a `docs/CHANGELOG.md` citation only
for merged **feat/fix** PRs, so this needs none and adding one would be noise. Confirmed rc=0.

### Checkpoint `<how-to-verify>` — every step executed

**Step 1 — read back the STORED commit messages** (`git log -1 --format=%B`; write-time appearance
proves nothing):

| commit | subject | Co-Authored-By | Claude-Session | backticks eaten | trailer lines |
| --- | --- | --- | --- | --- | --- |
| `db0edfe4` | `chore(deps): bump the frontend minor-and-patch group, fixing 3 latent type errors` | 0 | 0 | 0 | 0 |
| `692daa90` | `docs: reconcile the version pins and the #651 disposition the bump falsified` | 0 | 0 | 0 | 0 |

Both stored messages are **byte-identical** to their source files (`diff` rc=0), so nothing was
eaten at write time. The trailer grep was positive-controlled against contrived input
(`Co-Authored-By: someone <a@b.c>` → count 1), so a 0 here is a real absence rather than a broken
search.

**Step 2 — the exact PR body, and the closing-keyword grep:**

- `Supersedes #651` present: **count = 1**
- `grep -nEi '(clos|fix|resolv)[a-z]*[^.]{0,40}#651' <body-file>` → **rc=1, ZERO matches**
- **Positive control**: the identical regex against a deliberately broken body
  (`this closes #651 and should match`) → rc=0, 1 match. The grep is proven able to fire, so the
  zero above is evidence rather than a hope.

All four `#651` mentions in the body have the keyword *after* the number or no keyword at all:

```
1:Supersedes #651.
3:#651 was closed unmerged by dependabot itself on 2026-08-28T15:25:33Z, with the comment
83:**"#651 OPEN"**, which was the sole reason `check-handoff-contract.sh` was rc=1 on this branch
139:| 8 | `scripts/check-handoff-contract.sh` | 0 | from a recorded rc=1 on the #651 state claim |
```

The **PR title was checked too** — it contains no `#` reference at all, so no linkage is possible
from it regardless of the word "fixing".

**Step 3 — `bash scripts/check-branch-behind-base.sh`** → rc=0:
`PASS: HEAD contains every commit on origin/main (efcc3ee9); it is 2 commit(s) ahead and 0 behind.`

**Step 4 — Task 1 and Task 2 verifications re-run on the committed tree**: all rc=0 (table below).

**Step 5 — STOP.** Done; no PR was opened.

### After approval, verify from the forge and not from your own input

- `gh pr view <new> --json body -q .body | grep -c 'Supersedes #651'` → expect 1
- `gh pr view 651 --json state -q .state` → expect `CLOSED`. An `OPEN` here means the body
  reopened it via an unintended linkage — the failure this checkpoint exists to prevent.
- `gh pr checks <new>` — rc=1 means failed **or** unreachable, and an empty table is VOID, not a
  pass.

**One hazard the plan did not name:** GitHub parses **commit messages** for closing keywords too,
not only the PR body. Both commit messages were checked with the same regex (0 matches each). If
the PR is squash-merged, the operator composes the squash body — the same check should be applied
to whatever text ends up there.

---

## Verification — all 13 items, rc captured on the same statement as the command

Run from the repo root on the **committed** tree.

| # | check | rc | detail | fail direction |
| --- | --- | --- | --- | --- |
| 1 | `npx tsc --noEmit` | **0** | 0 errors | **EXECUTED** — rc=1 / 3 errors on the unmodified tree |
| 2 | `npm run build` | 0 | compiled, all routes emitted | not run |
| 3 | `npm test -- --ci --watchAll=false` | 0 | **120 suites / 1230 tests**, 0 failures | not run |
| 4 | `npm run lint` | 0 | **0 errors**, 34 warnings | not run |
| 5 | `scripts/check-e2e-typecheck.sh` | 0 | 25 files | not run |
| 6 | `scripts/check-doc-versions.sh` | **0** | 90 claims checked | **EXECUTED** — rc=1 / 13 stale claims post-bump, pre-edit |
| 7 | `scripts/check-doc-citations.sh` | 0 | | not run |
| 8 | `scripts/check-handoff-contract.sh` | **0** | 21 state claims, 21 matched | **EXECUTED** — rc=1 on the `#651 OPEN` claim at baseline |
| 9 | `scripts/docs-freshness.sh` | 0 | **without** `--write` | not run |
| 10 | `scripts/check-doc-metrics.sh` | 0 | | not run |
| 11 | `scripts/check-changelog-contract.sh` | 0 | | not run |
| 12 | `scripts/check-branch-behind-base.sh` | 0 | 2 ahead, 0 behind | not run |
| 13 | `git diff --stat docs/metrics.json` | — | **empty** | n/a |

No gate returned **rc=2 (VOID)**. Items 2-5, 7 and 9-12 are reported as passing **without a
fail-direction run on this branch** and are labelled as such rather than implied verified; items
1, 6 and 8 each carry a real recorded fail direction.

`docs/metrics.json` is byte-unchanged, so 1230 / 120 / 113 / 22 / 3188 hold. No test block was
added or removed — the three edits fix types inside existing blocks.

**`scripts/check-runtime-freshness.sh` was deliberately NOT run and is not required.** This change
never reaches a running container, no `docker compose` rebuild is in scope, and **runtime parity
is therefore not asserted by this plan.** Saying so explicitly rather than reporting a gate that
was never exercised.

---

## Task 1 — the bump and the three type fixes (`db0edfe4`)

### The 10 packages

`npm install` regenerated the lockfile (never hand-edited), so integrity hashes stay
registry-derived. `npm install` rc=0, "changed 14 packages".

| package | was | declared now | lockfile resolves |
| --- | --- | --- | --- |
| `@hookform/resolvers` | ^5.5.7 | ^5.9.1 | 5.9.1 |
| `@stripe/react-stripe-js` | ^6.8.0 | ^6.8.2 | 6.8.2 |
| `@stripe/stripe-js` | ^9.12.1 | ^9.14.0 | 9.14.0 |
| `lucide-react` | ^1.28.0 | ^1.33.0 | **1.35.0** |
| `next` | ^16.2.12 | ^16.3.2 | **16.3.3** |
| `react-hook-form` | ^7.84.0 | ^7.85.0 | **7.86.0** |
| `@testing-library/jest-dom` | ^7.0.0 | ^7.0.1 | 7.0.1 |
| `@testing-library/user-event` | ^14.5.1 | ^14.6.5 | **14.6.6** |
| `@types/node` | 26.1.2 | 26.2.0 | 26.2.0 |
| `eslint-config-next` | ^16.2.12 | ^16.3.2 | **16.3.3** |

Each of the ten was matched **exactly once** by an anchored line-start pattern before being
rewritten, and a match count other than 1 was a hard abort — which is what proves `"next"` did not
also hit `"next-auth"`.

### The three type errors were PRE-EXISTING, not bump-induced

Measured on the unmodified tree at `efcc3ee9` **before any edit**: `npm run build` rc=0 while
`npx tsc --noEmit` rc=1 with exactly these three:

```
__tests__/shop/server-seeded-islands.test.tsx(100,11): error TS2739 — missing first, last
components/dashboard/__tests__/dashboard-shell.test.tsx(154,22): error TS2503 — cannot find namespace 'JSX'
lib/__tests__/structured-data.test.ts(91,16): error TS2352 — insufficient overlap
```

`next build` type-checks the pages/app graph, not every file in the tsconfig program, so test
files sit outside its reach. **After: rc=0, 0 errors.** Because the fail direction was recorded on
an unmodified tree, this is a genuine two-direction result rather than a check observed only
passing.

The fixes, all real types — `as any`, `@ts-ignore`, `@ts-expect-error` and blanket
`as unknown as` are all absent from the three edited files (`rg -uu`, each rc=1/count 0, with a
positive control on `Record<string, unknown>` returning rc=0 and 3 hits, proving the search
direction matches):

- **TS2739** — added `first: true, last: true`. Semantically correct rather than filler: the
  literal already declares `totalPages: 1, number: 0`, one complete page, which is both first and
  last. tsc named no other literal in the file, and the other five `initial=` props are either
  `null` or a `ShopDetail` (not a `PageResponse`), so nothing else needed the same treatment.
- **TS2503** — React 19 removed the global `JSX` namespace. Added
  `import type { ReactElement } from "react"` and changed the cast to `Sidebar: () => ReactElement`.
- **TS2352** — `Record<string, never>` types every property as `never`, which is exactly why
  nothing overlapped. Changed to `Record<string, unknown>`. All five assertions in the
  `describe("productNode")` block are untouched (visible in the diff: only the cast line moved).

### Break arm: the changed cast did not weaken the assertion it guards

The `structured-data` cast was the one edit that could have silently disabled a real assertion, so
it was falsified rather than eyeballed. Bracketed **clean → arm → clean**, run against a committed
state:

| arm | change | rc | evidence |
| --- | --- | --- | --- |
| clean | none | 0 | 14/14 pass |
| **break** | `toBe("8.50")` → `toBe("8.51")` | **1** | `● productNode › is a Product with a GBP Offer priced in pounds, not pennies` / `Expected: "8.51"` / `Received: "8.50"` |
| restore | `git checkout -- <file>` | — | blob `a5ee9b7c15169596e10027df1bc9288dc0678e3a`, **identical to the committed object** |
| clean (closing) | none | 0 | 14/14 pass |

The break arm reporting `Received: "8.50"` is the proof: the real value flows through the new
`Record<string, unknown>` cast and is really compared. The £8.50-not-850 pricing assertion still
asserts. The restore was verified **by content** (blob hash), never by `git diff --stat`, and the
closing clean arm is the only proof the restore actually happened.

---

## Task 2 — doc reconciliation (`692daa90`)

`check-doc-versions.sh` went **rc=1 naming 13 stale claims across 4 docs** post-bump/pre-edit, and
is rc=0 again. The gate's list was treated as authoritative over the plan's pre-measured
enumeration — which mattered, see deviation 2.

**Gated (13 claims):** `Next.js 16.2.12 → 16.3.2` at `CLAUDE.md:25,52,140`,
`AGENTS.md:24,51,139,502`, `STACK.md:9,58,188`; `React Hook Form 7.84.0 → 7.85.0` at
`CLAUDE.md:54`, `AGENTS.md:53`, `STACK.md:60`. `README.md` claims nothing in the table
(`checked=1 drift=0`) and is untouched.

**Ungated and falsified — no gate reads these, fixed by hand:** `Stripe React/JS 6.8.0, 9.12.0 →
6.8.2, 9.14.0` at `CLAUDE.md:78` / `AGENTS.md:77`; the same two at `INTEGRATIONS.md:10`;
`AGENTS.md:494` roster prose; `@hookform/resolvers 5.2.2 → 5.9.1`, `lucide-react 1.28.0 → 1.33.0`,
`@testing-library/jest-dom 6.1.5 → 7.0.1`, `user-event 14.5.1 → 14.6.5` in `STACK.md`. Two of
those were already stale before this bump.

`STACK.md` was edited alongside `AGENTS.md` because it is the upstream source for the latter's
generated stack block.

**`HANDOFF.md`, two claims:**

- `:274` `**#651 OPEN**` → `**#651 CLOSED**`, stated as dependabot closing it unmerged at
  `2026-08-28T15:25:33Z` with its own comment, not an operator decision. That single claim was the
  entire reason `check-handoff-contract.sh` was rc=1 on this clean branch. The H-2 claim **shape**
  was preserved rather than deleted: H-2's matcher is
  `#([0-9]+)[^#\n]{0,40}?\b(CLOSED|OPEN)\b`, and a direct probe confirms it extracts
  `#651 CLOSED` exactly once from the new text. **Claim count held at 21 (21 of 21 matching),
  identical to the baseline's 21** — so the pass comes from the claim flipping, not from the claim
  vanishing. A vacuous pass and a real one look the same in the rc alone; the count is what
  separates them.
- `:275` rewritten so "#654 and #651 are the two dependabot PRs left" stays honest, naming only
  #654 and adding **no capitalised state word** for it (avoiding a second self-falsifying claim,
  the trap HANDOFF.md documents against itself).

---

## Deviations from plan

### 1. [Rule 1 — measurement correction] The tsc baseline rc is 1, not 2

- **Found during:** baseline, before any edit
- **Plan said:** `npx tsc --noEmit` exits **2** with 3 errors
- **Measured:** exits **1** with 3 errors — the error **count, codes, files and line/column
  positions all match the plan exactly** (TS2739 at 100,11; TS2503 at 154,22; TS2352 at 91,16)
- **Impact:** none on the outcome; the plan's `must_haves` and success criteria are stated in
  terms of rc=0 and zero errors, both met. Recorded rather than silently accepted, because
  reporting "2 → 0" when the instrument actually did "1 → 0" would be quoting a figure that was
  never observed.

### 2. [Rule 1 — incomplete enumeration] The plan's gated-site list missed `AGENTS.md:502`

- **Found during:** Task 2, by running the gate first as the plan instructs
- **Issue:** the plan named 3 gated Next.js sites in `AGENTS.md` (24, 51, 139); the gate named
  **4**. The extra is `:502` — "Next.js 16.2.12 (App Router), React 19" — which the plan did not
  list anywhere. Conversely `:494` ("Knows Next **16.2.12** App Router") is genuinely *un*gated,
  because the gate's ERE requires the literal `Next.js` with the dot and 494 says only `Next`.
- **Fix:** both fixed. `AGENTS.md` now has 5 occurrences of 16.3.2 (24, 51, 139, 494, 502).
- **Why it matters:** the plan's own instruction — "that list is authoritative over the
  enumeration below, which is a pre-measured aid, not a substitute" — is what caught this. Had the
  enumeration been trusted, the build would have stayed red for a reason the enumeration could not
  explain.

### 3. [informational] Five caret ranges resolve ABOVE the declared floor

`lucide-react` 1.35.0, `next` 16.3.3, `react-hook-form` 7.86.0, `user-event` 14.6.6,
`eslint-config-next` 16.3.3. This raised a real question: which number belongs in the docs?

**Resolved by reading the gate rather than guessing.** `check-doc-versions.sh`'s `n()` helper
greps `frontend/package.json` and strips a leading `^`/`~`, so it compares against the **declared
floor**. The docs therefore state 16.3.2 and 7.85.0, and the gate is rc=0. Both numbers are shown
side by side in the PR body so the distinction is not lost on a later reader.

### 4. [Rule 1 — doc claim falsified by this change] `HANDOFF.md` CVE deferral

- **Found during:** Task 2, while trying to verify the CVE claim for the PR body
- **Issue:** the deferral at `:309-311` read "Not applied — the tree runs `next` **16.2.12**",
  which this branch makes false. **It is line-wrapped across `:310-311`** ("runs \`next\`" /
  newline / "16.2.12."), which is precisely why a line-based search for `next 16.2.12` did not
  find it — it was only found by searching for the bare version token.
- **Fix:** rewritten to record that the 16.3.0 precondition is now met. No gate reads `HANDOFF.md`
  for versions, so nothing would have caught this.

### 5. [proof standards] The lodash 4.17.23 figure could NOT be verified by content

The plan's threat register (T-651-02) and its Task 3 instructions both state that `next` 16.3.0+
vendors lodash **4.17.23**, closing CVE-2025-13465. **This was attempted and could not be
confirmed from the installed artifact.** Next strips the version banner from its minified vendor
bundles: the only two files under `node_modules/next` carrying the lodash licence notice
(`compiled/jsonwebtoken/index.js`, `compiled/babel-packages/packages-bundle.js`) contain no
`4.17.x` string at all.

The tempting negative control — `rg 'lodash 4.17.21' node_modules/next` returning rc=1 — is
**not evidence of an upgrade**. It is absent because *no* version string is present. That is the
"an empty grep is evidence about the pattern or the scope, not the code" trap, and quoting it as
proof would have been exactly the failure mode this project keeps paying for.

What **is** verified: the tree now declares `^16.3.2` and resolves 16.3.3, so the precondition
`HANDOFF.md` itself set ("Fix it when the `next` 16.3.0 migration happens") is met. Both the PR
body and the rewritten `HANDOFF.md` paragraph state the 4.17.23 figure as **upstream's, carried
from the advisory and not verified by content here**.

### 6. [informational] The bump adds 6 new lint warnings from a rule that did not previously exist

`npm run lint` is rc=0 with **0 errors and 34 warnings**, of which **6** come from
`@next/next/no-location-assign-relative-destination`. Established rather than assumed: the
vendored `@next/eslint-plugin-next` goes from **21 to 22 rule files** between 16.2.12 and 16.3.3,
and the new file is exactly that rule. Measured by unpacking the 16.2.12 tarball
(`npm pack`), with a **positive control** confirming the same tar-listing search finds
`no-img-element`, a rule known to be old. Warnings do not fail `eslint .` — the rc is the verdict,
never eslint's trailing "fixable" line — so this is information, not a gate change.

### 7. [out of scope, recorded] Residual ungated `Next.js 16.2.12` drift

Measured with `rg -uu` rather than inherited from the plan, which is how the `.cursor` entry
turned up. None is read by any gate; all are outside `files_modified`:

| file | note |
| --- | --- |
| `.github/chatmodes/oaas-frontend.chatmode.md:2,21` | named by the plan |
| `.github/instructions/oaas-frontend.instructions.md:24` | named by the plan |
| **`.cursor/rules/oaas-frontend.mdc:2,21`** | **NOT named by the plan** — same generated roster family as the two above |
| `docs/PRD.md:234` | named by the plan |
| `docs/architecture/ESSENTIAL_ARCHITECTURE.md:78` | named by the plan |
| **`frontend/eslint.config.mjs:34`** | **NOT named by the plan** — a comment recording a measurement against `eslint-config-next@16.2.12` |

Correctly left alone (matching the token but not stale claims): `.github/dependabot.yml:67` (a
dated measurement inside a comment explaining a permanent ignore), `scripts/check-doc-citations.sh:348`
(the gate's own worked example — a doc rule naming the token it governs), and `.qa-council/**` /
`.planning/phases/**` (dated historical records).

### 8. [instrument defect, recorded] `rg` exits 2 on this repo regardless of matches

`rg -uu … .` from the repo root exits **2** even while printing matches, because
`./.gradle-docker/daemon/8.10.2` is `Permission denied (os error 13)`. **An `rg` rc therefore
cannot be used as an absence signal in this repo** — it is neither 0-means-found nor
1-means-absent. Every absence claim in this summary rests on a **match count plus a positive
control**, not on an rc. This bit once during execution (a residue search returned rc=2 and was
initially ambiguous) and was caught by inspecting stderr.

### 9. [pre-existing, left alone per plan] `INTEGRATIONS.md:10` citation is off by one

It cites `frontend/package.json:27-28`, but line 27 is `@stomp/stompjs` and line 28 is
`@stripe/react-stripe-js` — the two Stripe packages actually sit on **28-29**. The bump changed
values, not the line count (73 lines before and after), so the citation resolves exactly as well
as it did before, and `check-doc-citations.sh` stays rc=0 because
`@stripe/react-stripe-js` is inside the span. The plan explicitly says to leave the span alone, so
it was left alone and is recorded here instead.

### 10. [CLAUDE.md precedence] No `Co-Authored-By` trailer on either commit

The default commit convention would append `Co-Authored-By` and `Claude-Session` trailers. The
operator's global instruction ("Do not add Co-Authored-By trailers to anything") and this task's
explicit constraint both forbid them, and CLAUDE.md takes precedence. Both stored messages were
read back and confirmed to carry **zero** trailer lines.

### 11. [constraint] State artifacts deliberately NOT touched

`STATE.md`, `ROADMAP.md`, `REQUIREMENTS.md` and the planning docs were **not** modified or
committed, per the task constraints — the orchestrator owns the docs commit. The state SDK verbs
were **not** run: `STATE.md` itself records that `state.record-session` and `state.begin-phase`
are banned in this file (they rewrite `stopped_at`, destroy `last_activity`, and recompute the
already-corrupt `progress:` counters on the wrong denominator).

---

## Authentication gates

None. `gh` was already authenticated; `gh pr view 651` returned rc=0.

## Known stubs

None. No placeholder, empty-value or TODO stub was introduced.

## Threat flags

None. No new network endpoint, auth path, file-access pattern or schema change was introduced.
The three test edits touch assertion typing only — no fixture, credential or PII is added
(T-651-05, disposition *accept*, holds as written). T-651-01 holds: no new package **name** is
introduced, all ten move within the same major, and the lockfile was regenerated rather than
hand-edited, so no package-legitimacy checkpoint is required.

---

## Self-Check: PASSED

Files claimed created/modified — all present and all carrying the claimed change:

| file | check | result |
| --- | --- | --- |
| `frontend/package.json` | `"next": "^16.3.2"` present | FOUND |
| `frontend/package-lock.json` | `next` resolves 16.3.3 | FOUND |
| `frontend/lib/__tests__/structured-data.test.ts` | `Record<string, unknown>` cast, price assertion intact | FOUND |
| `HANDOFF.md` | `#651 CLOSED` extracted by the H-2 matcher, count 1 | FOUND |
| `.planning/quick/260828-msx-.../260828-msx-SUMMARY.md` | this file | FOUND |

Commits claimed — both present in `git log`:

- `db0edfe4` — `chore(deps): bump the frontend minor-and-patch group, fixing 3 latent type errors`
- `692daa90` — `docs: reconcile the version pins and the #651 disposition the bump falsified`

Prepared-but-unsent artifacts present in the scratchpad: `pr-title.txt`, `pr-body.md`.

No PR was opened. **Task 3 remains open and blocking.**
