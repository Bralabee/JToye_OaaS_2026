# 27-01 Task 5 — acceptance-criterion arms, both directions

> Not to be confused with `AC-5-ARMS.md`, which belongs to plan **27-00** Task 5. This file is
> **27-01 Task 5** (the frontend DELAYED affordance).

Every arm run through `baselines/runcheck.sh <expected_rc> "<label>" -- <cmd>`. The implementation
was committed first (`3c23bb7`) so `git checkout --` restores are safe (handoff trap 1).

---

## AC-5.1 — a stalled upload is explained, not left spinning (the M4 receipt)

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | tree as committed | 0 | `19 passed` across both suites; delayed card + Check-again present, `onCheckAgain` fires |
| BREAK | render the delayed card unconditionally for PENDING (`status === "PENDING"`) | 1 | **2 tests red**: `shows the plain spinner for a fresh PENDING asset, not the delayed card` → `Unable to find an element with the text: /processing/i`, **and the PRE-EXISTING Phase-24 test** `PENDING renders a processing indicator` |

The second failure is the more valuable one. Making the delayed card unconditional **destroys the
fresh-upload spinner**, and the test that has guarded that spinner since Phase 24 catches it. That is
the Incremental Betterment receipt for this branch: the new state is proven ADDITIVE, not a
replacement.

---

## AC-5.2 — Re-process appears only when the bytes are retained

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | tree as committed | 0 | `redrivable` → button present and `onReprocess` fires; `!redrivable` → `queryByRole` is null |
| BREAK | render the button unconditionally | 1 | **only** `hides Re-process on a FAILED asset when the bytes are gone` → `expect(received).toBeNull()` / `Received: <button …>Re-process</button>` |

The `shows Re-process…` half stayed GREEN under the break — which is exactly why both directions are
required. A lone "it renders" test passes on an unconditional button and is incapable of detecting
the defect it claims to guard: a Re-process control on an asset whose bytes are gone is a button that
can only ever 409.

---

## AC-5.3 — the existing Re-upload control is not displaced

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | tree as committed | 0 | both buttons present; `onReupload` still fires on the Re-upload click |
| BREAK | replace the Re-upload button with Re-process | 1 | **5 tests red**, all with `Unable to find an accessible element with the role "button" and name /re-upload/i` — including the two PRE-EXISTING Phase-24 FAILED-state tests |

---

## AC-5.4 — typecheck

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | `npm run build` | 0 | `✓ Compiled successfully in 4.9s` |
| BREAK | pass `delayed="yes"` at the `ReviewQueue.tsx` `<AssetImage>` call site | 1 | `Type error: Type 'string' is not assignable to type 'boolean \| undefined'.` |

**The gate was checked for vacuity before being trusted:** `frontend/next.config.mjs` carries no
`typescript.ignoreBuildErrors` / `eslint.ignoreDuringBuilds`, so `next build` really does typecheck.
Had it carried either, this criterion could not fail and would have been decorative.

The break was deliberately placed in a **component**, not a test fixture. `next build` does not
typecheck `__tests__/`, so a break there exits 0 in both directions — a vacuous arm.

### The plan's "`tsc --noEmit` count unchanged" clause is NOT SATISFIABLE, and is recorded as such

The plan says: *"Record `npx tsc --noEmit`'s count as **unchanged** vs. the pre-change baseline
(currently 366, all jest-dom matcher typings in test files that `next build` never checks)."*

Measured — baseline taken from `HEAD~1` in a **separate git worktree** (see the note below):

```
tsc --noEmit errors at HEAD~1 (pre-Task-5) : 368
tsc --noEmit errors now                    : 378      (+10)
```

Every one of the +10 is the **identical pre-existing class**, in the two test files this task
extends:

```
asset-image.test.tsx      : 6 -> 12   Property 'toBeInTheDocument' does not exist on type 'JestMatchers<HTMLElement>'
ReviewQueue.test.tsx      : 8 -> 12   Property 'toBeInTheDocument' does not exist on type 'JestMatchers<HTMLElement>'
```

No new error *class* appears. The root cause is that jest-dom's type augmentation is not wired into
`tsconfig.json`, so **every** `expect(...).toBeInTheDocument()` counts as one error. The count is
therefore a monotonic function of how many jest-dom assertions exist: it can only stay "unchanged" if
a task adds **zero** test assertions, which is incompatible with adding tests. The clause is
unsatisfiable as written for any task that adds frontend tests.

Recorded, not silently substituted. The load-bearing half of AC-5.4 — `npm run build` exits 0, and
is proven capable of failing — is GREEN. Fixing the underlying `tsconfig` gap would make this a real
gate again and is flagged as a follow-up; it is out of Task 5's scope because it is a global config
change affecting the whole frontend build.

> **Baseline method, and a hazard worth recording.** The first attempt measured the baseline with
> `git stash -u`. It **failed halfway**: `warning: failed to remove infra/monitoring/alertmanager/…:
> Permission denied` (root-owned untracked files), so the stash entry was created but the checkout to
> HEAD did not complete, and `git stash pop` then refused with *"Your local changes would be
> overwritten by merge"*. The working tree was left holding the edits with a duplicate stash beside
> it — one step from the `trap_break_arm_revert_eats_fixes` failure mode. It was resolved by
> diffing `git diff` against `git stash show -p` (byte-identical → the stash was redundant), then
> committing and dropping it. **Use `git worktree add --detach <path> <ref>` for baseline
> measurements in this repo, never `git stash -u`** — root-owned paths under `infra/monitoring/`
> make `stash -u` unsafe. (`frontend/node_modules` symlinks into the worktree so `tsc` can run.)

---

## AC-5.5 — 320 px layout on the running stack

See the section appended below once the rebuild + browser run completes. Per the plan the
"if the stack is unavailable, mark DEFERRED" escape is REMOVED: an unavailable stack makes this
criterion **VOID (exit 2) and the plan NOT done**, with the recorded evidence of unavailability.
