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

The stack was up, so the plan's VOID clause did not apply. `spec: frontend/e2e/media-review-320.spec.ts`.

### Runtime first: the containers were rebuilt, and parity proven BY CONTENT

`check-runtime-freshness.sh` reported `core-java DRIFT` and `frontend DRIFT` before the run — the
frontend image predated Task 5 by 18 h and core-java by 4 h, so a browser run against them would have
tested code from yesterday. Both rebuilt with `up -d --build` (`start`/`restart` never rebuild), then:

```
core-java  FRESH  image tagged 2026-07-27 18:47:26 UTC  frontend FRESH 18:47:47 UTC
PASS: 4 running built service(s) match the source tree (0 unverified).
```

Timestamps alone are not proof, so the values were read **out of the running artifacts**:

```
docker exec … unzip -p /app/app.jar BOOT-INF/classes/uk/jtoye/core/media/MediaController.class | strings | grep -c reprocess   -> 4
docker exec … unzip -p /app/app.jar BOOT-INF/classes/application.yml | grep -c quarantine-retention-ms                          -> 2
docker exec … grep -rl 'Taking longer than usual' /app/.next | wc -l                                                            -> 3
curl -s localhost:9090/v3/api-docs | grep -c 'media/{assetId}/reprocess'                                                         -> 1
                                     grep -c 'redrivable' / '"delayed"'                                                          -> 1 / 1
```

The **live** app therefore serves the new path and both new fields. A filesystem `find` for the class
would have returned a misleading 0.

### Fixtures: real rows, not a stubbed route

Three `media_asset` rows were seeded for the demo tenant so the run exercises the whole path —
core-java deriving the bits, the D-10 widened query, and the rebuilt frontend rendering them.
Stubbing `/media/review-queue` would have proven only that the component lays out correctly given a
shape the backend might not send. Reproduce with:

```sql
INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type, status, flagged,
                         failure_reason, process_attempts, quarantine_expires_at, quarantine_reclaimed_at, created_at)
VALUES
 (gen_random_uuid(),'00000000-0000-0000-0000-000000000001','…/quarantine/ac55-fixture-redrivable.jpg',
  repeat('a',64),'image/jpeg','FAILED',false,'Processing stalled before it finished',0, now()+interval '72 hours', NULL,           now()),
 (gen_random_uuid(),'00000000-0000-0000-0000-000000000001','…/quarantine/ac55-fixture-vetoed.jpg',
  repeat('b',64),'image/jpeg','FAILED',false,'That file is not a supported image',   0, now()+interval '72 hours', now(),          now()),
 (gen_random_uuid(),'00000000-0000-0000-0000-000000000001','…/quarantine/ac55-fixture-delayed.jpg',
  repeat('c',64),'image/jpeg','PENDING',false,NULL,                                   0, now()+interval '72 hours', NULL, now()-interval '30 minutes');
```

### Arms

| dir | arm | rc | evidence |
|---|---|---|---|
| PASS | tree as committed, rebuilt image | 0 | `1 passed (2.7s)`; screenshots in `ac55-screenshots/` |
| BREAK | `flex-nowrap` + `min-w-[200px] shrink-0` on the FailedRow action row, **rebuilt** | 1 | `control is clipped past the 320px right edge — Expected: <= 320, Received: 449` |
| PASS | re-confirmed on the RESTORED rebuilt image | 0 | `1 passed (3.2s)`; `PASS: 4 running built service(s) match the source tree` |

---

### Finding A — **the plan's stated assertion cannot detect this defect.** It is vacuous here.

The plan specifies `document.documentElement.scrollWidth <= 320` and predicts the break yields
`scrollWidth ≈ 380 > 320`. Measured under the break:

```
PROBE docEl.scrollWidth  = 320      <-- the plan's assertion PASSES on a visibly broken layout
PROBE body.scrollWidth   = 320
PROBE row overflow       = { scrollWidth: 408, clientWidth: 238 }
PROBE re-process box     = { x: 249, width: 200 }        -> right edge 449, far past the 320 viewport
PROBE clipping ancestors = [ 'MAIN.flex-1 overflow-y-auto  overflowX=auto',
                             'DIV.flex h-screen overflow-hidden  overflowX=hidden' ]
```

The dashboard shell wraps content in a scroll container (`overflow-y-auto` computes `overflowX: auto`)
inside an `overflow-hidden` shell. Those **absorb the overflow before it reaches the document
element**, so `documentElement.scrollWidth` is pinned at the viewport width no matter how far a child
overflows. Had this criterion been implemented exactly as written, it would have passed on the
clipped layout in the screenshot — a criterion incapable of failing.

The assertion that actually fires is the **per-control** one: each action's `x + width` must be
`<= 320`. It reported `449`. Both are kept — the `scrollWidth` check still guards whole-page
overflow, which is a different (and real) defect — but the per-control check is the load-bearing one
and the plan did not specify it.

### Finding B — the first break arm was a **false green**, and the marker that "confirmed" it was vacuous

The first break run returned rc=0. Cause: `docker compose up -d --build frontend` leaves the OLD
container running and healthy while the new image builds, so a wait-loop polling `Health=healthy`
returns **immediately** and the test runs against the pre-break image. The marker used to confirm the
rebuild — `grep -rl 'flex-nowrap' /app/.next` — was **non-discriminating**: Tailwind emits that
utility class regardless of whether this component uses it, so the marker matched in both directions.

Corrected by polling on a marker that exists **only** in the broken build (`min-w-[200px]`), asserted
to be `0` files for the restored image. The re-run then produced the RED above. Two lessons, both
already-known trap classes arriving through a new door: *a rebuild is not complete when the old
container is still healthy*, and *a marker that matches in both directions proves nothing*.

---

## Regression surface

| run | result |
|---|---|
| `npx jest --ci` (FULL frontend suite) | `Test Suites: 62 passed, 62 total` / `Tests: 419 passed, 419 total` |
| `npm run build` | `✓ Compiled successfully` (rc 0) |
| `scripts/check-runtime-freshness.sh` | `PASS: 4 running built service(s) match the source tree (0 unverified)` |

Computed metrics after Task 5 (for Task 6 to reconcile — do NOT write `metrics.json` before then):

```
java_test_methods 1226   java_test_files 212   jest_blocks 424   jest_files 62
playwright_blocks 43     playwright_specs 13   total_logical_invocations 1818
```

Note `jest_blocks` 424 vs jest's runtime `419 tests`: the gate counts the **literal** `it(`/`test(`
tokens (`trap_docs_freshness_block_counter`), which is not the same number as tests executed. Both are
recorded so Task 6 reconciles against the right one — `docs-freshness.sh --write` is the arbiter.
