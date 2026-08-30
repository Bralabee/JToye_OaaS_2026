---
phase: 34-rendering-test-truthfulness
plan: 06
subsystem: e2e-test-truthfulness
tags: [playwright, skip-budget, gates, falsifiable-evidence, TRUTH-02]
requires:
  - "frontend/playwright.config.ts grepInvert mechanism (pre-existing, #420)"
  - "scripts/check-e2e-skip-budget.sh S-1..S-4 contract (pre-existing)"
provides:
  - "the single-tenant onboarding journey tagged @desktop-only, out of the mobile project's enumeration"
  - "e2e-skip-budget.conf with 2 ALLOW entries, MAX_SKIPS 6, and a measured composition"
affects:
  - "plan 34-10 — owns the full-suite run that re-earns this gate; MUST reseed the stack first (see Handoff)"
tech-stack:
  added: []
  patterns:
    - "@desktop-only enumeration tag replacing a runtime project pin (storefront-ssr-seo.spec.ts:72-77 analog)"
    - "docblock-above-describe justification with measured evidence (dashboard-interface-corrections.spec.ts:97-105 analog)"
key-files:
  created: []
  modified:
    - frontend/e2e/onboarding-blocked-flow.spec.ts
    - scripts/gates/e2e-skip-budget.conf
decisions:
  - "The conf's stated skip cause was measured FALSE and deleted rather than quoted; the refutation lives in the spec docblock so the conf cannot be grep-read as still exempting the journey"
  - "MAX_SKIPS lowered to the measured 6 because at 8 the gate could not distinguish 6, 7 or 8 — S-1 was incapable of failing"
  - "The gate's VOID (rc=2) is reported as VOID, not worked around and not reported as a pass"
metrics:
  duration: ~35 min
  completed: 2026-08-28
  tasks: 2
  commits: 2
---

# Phase 34 Plan 06: Home the Onboarding Skip Summary

Replaced the onboarding journey's runtime project pin with an `@desktop-only` enumeration tag
and corrected the skip-budget config's two false claims, dropping the ceiling from 8 to the
measured 6 — the change that makes TRUTH-02 falsifiable at all.

## What Shipped

| Task | Change | Commit |
|---|---|---|
| 1 | `@desktop-only` tag; runtime project pin and `testInfo` param deleted; rationale moved to a docblock and extended with the measurement | `383cc331` |
| 2 | Stale ALLOW + its false justification deleted; `MAX_SKIPS 8 -> 6`; arithmetic replaced with a measured composition and stated method | `8b833375` |

Files changed against the plan base `0b6a581c`, and nothing else:
`frontend/e2e/onboarding-blocked-flow.spec.ts`, `scripts/gates/e2e-skip-budget.conf`.
`frontend/playwright.config.ts`, `docs/metrics.json`, `frontend/package.json` and
`frontend/package-lock.json` are all provably untouched (`git diff --name-only` empty for each;
T-34-06-SC satisfied — nothing was installed beyond a plain `npm ci` from the committed lockfile).

## The Measurement That Refuted the Plan's Own Premise Source

Both #547 and `e2e-skip-budget.conf:46-48` blamed a missing seeded shop. The nightly's own
report says otherwise. Nightly run **33142364550** (`e2e-nightly.yml`, started
**2026-08-28T04:43:48Z**, 266 results, 7 skipped), read with `jq` from the downloaded artifact:

```
onboarding-blocked-flow.spec.ts [mobile]  status=skipped   73ms
    ANN: skip=single-tenant onboarding journey pinned to the desktop project
         (UNIQUE(tenant_id) — no cross-worker race)
onboarding-blocked-flow.spec.ts [desktop] status=passed   6746ms
```

The desktop arm **passes in 6.7 seconds**, which drives the create form — only possible when the
shop fixture is present. So the stated cause was false, and it was **one** skip (mobile only),
never the two the conf's `x 2 projects` arithmetic implied.

Full measured composition of the 7 (this is the "measured, not computed" figure now in the conf):

| Spec | Skips | Projects | Owner |
|---|---:|---|---|
| `stomp-relay.spec.ts` | 4 | mobile + desktop, 2 tests | #304 |
| `vendor-refund-flow.spec.ts` | 2 | mobile + desktop, 1 test | #61 |
| `onboarding-blocked-flow.spec.ts` | 1 | **mobile only** | none — homed by this plan |
| **Total** | **7** | | ceiling was 8 |

7 − 1 = **6**, which is the new `MAX_SKIPS`.

## Evidence, Both Directions

### The enumeration pair (the whole proof for Task 1)

| Command | Before | After |
|---|---|---|
| `--list --project=mobile` | `Total: 1 test in 1 file` | **`Total: 0 tests in 0 files`** |
| `--list --project=desktop` | `Total: 1 test in 1 file` | `Total: 1 test in 1 file` |

**Break arm, bracketed clean → arm → clean:**

```
committed blob : a4b543c2067b394437b844fc5d9112c1ba76953a
OPENING CLEAN  : mobile Total: 0 tests in 0 files
BREAK (tag removed, blob a03c72ec) : mobile Total: 1 test in 1 file   <- the check CAN fail
RESTORE        : blob a4b543c2… — RESTORE VERIFIED BY CONTENT
CLOSING CLEAN  : mobile Total: 0 tests   desktop Total: 1 test   git status clean
```

### The conf greps, every absence backed by a positive control

The control input is the pre-edit conf (`git show HEAD:…`, 57 lines). An absence is only
evidence if the same pattern matches a known-positive input.

| Assertion | Control (pre-edit) | Live (post-edit) |
|---|---|---|
| `rg -uu -c '^ALLOW'` | **3** | **2** |
| `rg -uu -c 'onboarding-blocked-flow'` | **1** | absent, rc=1 |
| `rg -uu -c 'demo tenant\|DemoDataSeeder'` | **2** | absent, rc=1 |
| `rg -uu -c '^ALLOW.*onboarding'` (stronger form) | **1** | absent, rc=1 |
| `rg -uu -n '^MAX_SKIPS'` | `MAX_SKIPS 8` | **`MAX_SKIPS 6`** |

### Gates

| Gate | rc | Note |
|---|---:|---|
| `scripts/check-e2e-typecheck.sh` | **0** | `PASS: 25 e2e file(s) type-check clean` |
| `npm run lint --prefix frontend` | **0** | 0 errors, 34 pre-existing warnings, **none** in the edited file |
| `scripts/check-e2e-skip-budget.sh` | **2 — VOID** | see below; recorded as VOID, not a pass |

## The Gate Is VOID, and That Is the Honest State

```
check-e2e-skip-budget  (2026-08-28T21:26:45Z)
VOID: no Playwright JSON report at …/frontend/e2e-artifacts/report.json
      — run the suite with --reporter=json first
rc=2
```

**Why:** RESEARCH Pitfall 4. The gate ties a report to a tree by `config.metadata.specDigest`.
No local report exists in this worktree, and the last nightly's digest (`eab59e77…`) already
disagreed with the tree before this plan; **this plan's spec edit moves it again** — the tree now
computes `bc0275de41ee3865…`. A VOID is not a pass, it was not worked around, and no claim in
this summary depends on the gate being green. **Plan 34-10 owns the full-suite run that re-earns
it**, after every spec edit in the phase has landed.

**Ceiling arm (recorded, not left in place):** with `MAX_SKIPS 8` the gate cannot distinguish 6
skips from 8 — S-1 is `[ "$SKIP_COUNT" -le "$MAX_SKIPS" ]`, so on the measured 7 it passed, and it
would equally have passed on 6 or on a newly-introduced 8th. That is stated from the conf's own
arithmetic rather than from a gate run no report can support. Lowering the ceiling to the measured
total is what makes S-1 capable of failing.

## Deviations from Plan

### 1. [Rule 1 — Bug] The plan's parser arm was VACUOUS as specified, and was replaced with a valid one

**Found during:** Task 2.
**Issue:** The plan states *"PARSER ARM (available without a report)"*. It is not. The gate voids
at `[ -s "$REPORT" ]` (`check-e2e-skip-budget.sh:132`) **before** it parses the config
(`:157-172`). Run as specified, with `NONSENSE foo` appended and no report, the gate emitted the
**identical** message and **identical rc=2** as the clean conf — proving nothing about whether the
config is read.
**Both directions recorded:**

```
clean conf, no report      -> rc=2  "VOID: no Playwright JSON report at …"
NONSENSE foo, no report    -> rc=2  "VOID: no Playwright JSON report at …"   <- indistinguishable
NONSENSE foo, fresh report -> rc=2  "VOID: unknown directive 'NONSENSE' … refusing to guess"
```

**Fix:** ran the arm with a fresh local report (digest `bc0275de…`, matching the tree) so the
parser is genuinely reached. It then named the unknown directive. The line was removed and the
file verified by blob hash.

### 2. [Rule 1 — Bug] The break-arm restore ate the uncommitted Task 2 edit; caught by the closing arm

**Found during:** Task 2 parser arm.
**Issue:** I ran the parser arm before committing Task 2, violating proof-standard 8 ("commit
before break arms"). `git checkout -- scripts/gates/e2e-skip-budget.conf` restores from the
**index**, which held the pre-edit conf — so the restore silently reverted `MAX_SKIPS` to 8 and
reinstated the deleted ALLOW.
**How it was caught:** the closing clean arm compared hashes and they disagreed —
`741516980cdd…` (HEAD's pre-edit blob) against the expected `8decb1e1b8fa…`. A
`git diff --stat` would have shown nothing wrong.
**Fix:** restored from a content backup taken before the arm; hash verified back to
`8decb1e1b8fa…`, `MAX_SKIPS 6` confirmed on disk, all four criteria re-run green, then committed
immediately. This is the documented `trap_break_arm_revert_eats_fixes` firing exactly as recorded.

### 3. [Deviation — measurement] The plan's `test.skip` baseline undercounted by one

**Plan said:** `rg -uu -c 'test\.skip\('` "prints 3 after the edit (was 4)".
**Measured:** **5 before, 4 after.** The plan's `<interfaces>` block enumerated four call sites
(`:116`, `:120`, `:152`, `:169`) and missed a fifth at **`:89`**, inside the `vendorLogin` helper
(`"No sign-in method found on /auth/signin — unknown auth flow"`), which is outside the test body.
The substance is unchanged and is asserted in a stronger form: exactly one call was deleted (the
project pin), and the four survivors are the auth-flow helper guard, the password guard, the
LIVE/terminal guard and the no-shop guard — each a real "nobody checked this". Recorded rather
than silently substituted.

### 4. [Deviation — criterion tension] Two conf criteria forbid tokens the plan's own action text requires

**Issue:** The plan requires the replacement comment to state "the correction it supersedes …
the onboarding skip was mobile-only", while its acceptance criteria require
`rg 'onboarding-blocked-flow'` and `rg 'demo tenant|DemoDataSeeder'` to print **nothing** in the
same file. Naming the correction and passing the greps are in direct tension — the known
"a doc rule that must name the token it forbids" shape.
**Resolution:** the literal criteria win, because the action text is explicit that the false
justification must not be "carried forward anywhere". The conf states the correction without
reproducing the refuted cause or the spec path; the full refutation, with both arms' annotations,
lives in the spec's docblock beside the tag it justifies. Both criteria now pass **with positive
controls** (table above).
**Recorded limitation:** as written, criterion C2 is token-based and cannot distinguish "the
exemption survives" from "the file merely mentions the spec". The strictly stronger form
`rg -uu -c '^ALLOW.*onboarding'` — which tests the actual intent and cannot be defeated by prose —
was run alongside it: **1 on the control, absent on the tree.**

### 5. [Observation — no action] The #61 ALLOW's justification and its runtime annotation differ, and are compatible

The conf explains the vendor-refund skip as an empty `STRIPE_API_KEY`; the nightly annotation
reads `No CONFIRMED+CAPTURED order seeded`. These are the root cause and the proximate guard
respectively — the conf explicitly says seeding that fixture would push the test past its skip and
then fail at the Stripe call. Not a false claim, and the entry is untouched as the plan directs.

## Known Stubs

None. No placeholder values, empty returns or TODO markers were introduced.

## Threat Flags

None. No network endpoint, auth path, file-access pattern or schema surface is touched — the
change is one test tag and one gate config.

Threat register dispositions, all mitigated: **T-34-06-01** the stale ALLOW is deleted rather than
left harmless; **T-34-06-02** the ceiling drops to the measured 6 so headroom cannot hide a new
skip; **T-34-06-03** the runtime "not applicable" skip is now an enumeration tag, proven by the
`--list` pair and its break arm; **T-34-06-04** the VOID is recorded as a VOID with the re-earn
assigned to 34-10; **T-34-06-SC** nothing installed, package files byte-identical.

## Handoff to Plan 34-10 — READ BEFORE THE FULL-SUITE RUN

**The stack must be freshly seeded, or this gate will fail for an environment reason.**
Measured on this long-lived local stack: the desktop arm of the onboarding journey **skipped**
after 1138 ms at the LIVE/terminal guard —

```
ANN: skip=Target tenant onboarding is already LIVE/terminal — this blocked-journey spec
     needs a fresh/disposable tenant. Skipping to avoid failing against or mutating the
     live demo.
```

That is a pre-existing, environment-dependent condition the guard's own comment anticipates, and
it is unrelated to this plan's edit (the guard is untouched; the diff is confined to the title,
the docblock and the deleted pin). But its consequence for 34-10 is concrete: on a stale stack the
**desktop** project contributes a skip that matches **no** ALLOW, so S-2 fails and the budget of 6
is exceeded. `e2e-nightly.yml` avoids this by tearing down with `down -v`; a local full-suite run
must do the equivalent. On the nightly's fresh volume this journey **passes**.

Do **not** respond to that failure by adding an ALLOW — it would re-create exactly the
false-exemption this plan removed.

Also for 34-10: the tree's spec digest is now `bc0275de41ee3865…` and will move again with every
further spec edit in this phase. Sequence the suite run last.

## Self-Check: PASSED

Files claimed, verified on disk:

- `frontend/e2e/onboarding-blocked-flow.spec.ts` — FOUND, contains `@desktop-only`
- `scripts/gates/e2e-skip-budget.conf` — FOUND, contains `MAX_SKIPS 6`
- `.planning/phases/34-rendering-test-truthfulness/34-06-SUMMARY.md` — this file

Commits claimed, verified in `git log`:

- `383cc331` — `test(34-06): home the onboarding skip as @desktop-only, correcting its diagnosis`
- `8b833375` — `chore(34-06): retire the stale ALLOW, drop the ceiling to 6, correct both false claims`

Working tree clean; no files deleted by either commit
(`git diff --diff-filter=D HEAD~1 HEAD` empty).
