---
phase: 34-rendering-test-truthfulness
plan: 02
subsystem: ui
tags: [react, useSyncExternalStore, hooks, eslint, jest, theme, dashboard, ssr]

# Dependency graph
requires:
  - phase: 19-frontend-overhaul
    provides: the dashboard sidebar + mobile tab bar whose private theme state this replaces
  - phase: 23-vendor-scoped-access
    provides: MOBL-01's mobile tab bar and the ShopSwitcherProvider the cross-surface test mounts
provides:
  - "hooks/use-theme.ts — one useSyncExternalStore-backed theme store with a mandatory server snapshot"
  - "Two of the four #99 follow-up set-state-in-effect suppressions deleted (4 markers -> 2)"
  - "Executed both-direction evidence that the react-hooks/set-state-in-effect gate can fire, on --stdin AND on a real file"
  - "A cross-surface Jest suite over the REAL Sidebar + MobileTabBar proving the DOM-class coupling is gone"
affects: [34-03-use-customer-session, 34-04-oauth-callback, 34-10-metrics, dashboard-theme]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "useSyncExternalStore triple (subscribe / getSnapshot / getServerSnapshot) for any browser-state read a component used to hydrate in a mount effect"
    - "Untrusted localStorage narrowed to a closed union at the read, so only a boolean reaches a DOM sink"
    - "Cross-surface behaviour proven against the real components, not stand-ins"

key-files:
  created:
    - frontend/hooks/use-theme.ts
    - frontend/hooks/__tests__/use-theme.test.tsx
    - frontend/components/dashboard/__tests__/theme-cross-surface.test.tsx
  modified:
    - frontend/components/dashboard/sidebar.tsx
    - frontend/components/dashboard/mobile-tab-bar.tsx

key-decisions:
  - "useTheme keeps a classList-sync effect. It sets no state, so it is not the shape the lint rule forbids, and without it a stored dark preference stops surviving a reload — the job the sidebar's mount effect used to do. RESEARCH Pattern 4 asks for exactly this."
  - "The cross-surface proof lives in its own file mounting the REAL Sidebar and MobileTabBar, not in the theme suite, because both components could import the hook and still render from a private copy."
  - "The suite's MessageChannel is a local fake, not node:worker_threads: React re-refs a real MessagePort when it assigns onmessage, and jest then hangs to a hard rc=124 rather than going red."
  - "An unreadable localStorage is treated as ABSENT and falls through to the system preference, not pinned to false."

patterns-established:
  - "Pattern: any browser-state mount-effect hydration converts to useSyncExternalStore with a getServerSnapshot, which is mandatory under app-wide dynamic = force-dynamic"
  - "Pattern: when two surfaces share state, extract one store rather than copying the hook — copying removes the lint suppression and keeps the bug (#457 precedent)"
  - "Pattern: a Radix modal aria-hides the rest of the page, so a cross-surface count query must pass hidden:true or it reads 1 regardless of correctness"

requirements-completed: [TRUTH-01]

# Metrics
duration: 23min
completed: 2026-08-28
---

# Phase 34 Plan 02: Shared Theme Store Summary

**One `useTheme()` on `useSyncExternalStore` replaces the sidebar's and the mobile tab bar's private theme state, deleting two `#99 follow-up` lint suppressions and the DOM-class coupling that made the tab bar depend on the sidebar mounting first.**

## Performance

- **Duration:** 23 min
- **Started:** 2026-08-28T21:17:44Z
- **Completed:** 2026-08-28T21:40:52Z
- **Tasks:** 3
- **Files modified:** 5 (3 created, 2 modified)

## Accomplishments

- `hooks/use-theme.ts` is now the only theme source for both dashboard surfaces, built on the shipped `useSyncExternalStore` triple from `components/marketing/reveal.tsx:34-58`.
- The `#99 follow-up` `set-state-in-effect` marker count drops **4 → 2**; the remaining two belong to plans 34-03 (`hooks/use-customer-session.ts`) and 34-04 (`app/shop/auth/callback/page.tsx`).
- The implicit sidebar-then-tab-bar mount ordering is gone, and that is proven by a test that mounts the **tab bar first**, not by inspection.
- ROADMAP criterion 2's own requirement is satisfied: the ESLint gate was shown to fire in **both** arms — via `--stdin` and against a reintroduced defect in a real file — with the restore verified by content hash.

## Task Commits

1. **Task 1 (RED): failing behaviour suite for useTheme** — `2ca4e4b7` (test)
2. **Task 2 (GREEN): implement useTheme on useSyncExternalStore** — `d9ae041b` (feat)
3. **Task 3: wire both surfaces, delete both suppressions** — `041a1a49` (refactor)

## TDD Gate Compliance

`git log --oneline -4` shows the mandated order, all three within this plan:

```
041a1a49 refactor(34-02): wire both dashboard surfaces to the shared theme store
d9ae041b feat(34-02): implement shared theme store
2ca4e4b7 test(34-02): add failing theme-store suite
0b6a581c docs(phase-34): 10 plans / 27 tasks in 5 waves  <- plan base
```

- **RED gate:** `npx jest hooks/__tests__/use-theme.test.tsx --ci` → **rc=1**, `Cannot find module '../use-theme'`, **0 tests run**.
- **GREEN gate:** same command → **rc=0**, **14 passed / 14 total**, jest exits cleanly.
- **REFACTOR:** the rewiring commit; `eslint .` rc=0 and the dashboard/hooks suites rc=0 after it.

The first RED run failed for the **wrong reason** (`ReferenceError: MessageChannel is not defined`, before any test ran). That is recorded rather than quietly fixed: a RED gate that fails on a harness fault proves nothing about the missing module, so the polyfill was added and RED was re-run until the failure named `../use-theme`.

## Falsification Record

Every criterion below was run in **both** directions. Nothing here is reported from a pass alone.

### Test-level break arms (Task 2)

| Arm | Change | Result | Restore verified |
|---|---|---|---|
| 1 | `getServerSnapshot` returns `true` | **rc=1**, exactly one test red — `server-renders without throwing and never emits the dark class` — reporting `Expected substring: not "class=\"dark\"" / Received: "<div class=\"dark\">theme</div>"` | `git hash-object frontend/hooks/use-theme.ts` = `1390c80360ae8f6dc477c871f5e760474e901932`, identical to pre-arm |
| 2 | drop the explicit-`"light"` short-circuit from `getSnapshot` | **rc=1**, exactly one test red — `lets an explicit light preference win over a dark system preference` — `Expected: light / Received: dark` | same hash `1390c803…` |
| closing clean | none | **rc=0, 14/14** | tree clean |

Both arms were run **after** the GREEN commit, so the restore target was a committed blob (proof-standards §8). The closing clean arm is the only proof the restores actually took, and it was run.

### ESLint gate, both arms (Task 3, ROADMAP criterion 2)

**(a) `--stdin` probe — writes nothing to the tree, so there is no restore to verify:**

- Defect shape (synchronous `setState` in a `useEffect` body) → **rc=1**, `✖ 1 problem (1 error, 0 warnings)`, rule named: `react-hooks/set-state-in-effect`.
- Clean shape through the **same harness** → **rc=0**. This negative control matters: without it, rc=1 could have been a broken invocation rather than the rule firing.

**(b) Reintroduced defect in a real file:** the old mount effect was restored into `components/dashboard/sidebar.tsx` **without** its suppression.

- `npx eslint components/dashboard/sidebar.tsx` → **rc=1**, `sidebar.tsx:65:5 error … react-hooks/set-state-in-effect`.
- Restored via `git checkout --` against the already-committed blob; `git hash-object frontend/components/dashboard/sidebar.tsx` = `1c2d08a471f232fd58fe27f397d56a823cb68277`, identical to the committed object, and `git status --short` empty.
- Closing clean arm after the restore: `npx eslint .` **rc=0**, `npx jest components/dashboard hooks` **rc=0, 20/197**.

### Counts, with the pre-edit positive control

| Assertion | Before | After |
|---|---|---|
| `rg -uu -c 'refactor tracked in issue #99 follow-up' frontend/` | **4 files** (use-customer-session.ts, sidebar.tsx, callback/page.tsx, mobile-tab-bar.tsx) | **2 files** (use-customer-session.ts, callback/page.tsx) |
| `rg -uu -n 'set-state-in-effect'` on the two dashboard files | 2 lines | **rc=1, no output** |
| `npx eslint .` | rc=0, 0 errors / 34 warnings | rc=0, **0 errors / 34 warnings** |
| `npx jest components/dashboard hooks --ci` | rc=0, **18 suites / 180 tests** | rc=0, **20 suites / 197 tests** |
| `npm run build` | — | **rc=0**, `Compiled successfully`, `Finished TypeScript` clean |
| `git diff --name-only -- frontend/package.json frontend/package-lock.json` | — | **empty** (T-34-02-SC: nothing installed) |

- The pre-edit **4** is the positive control for the marker count: a search that could not find them before cannot be trusted to report 2 now. `searchcheck 'refactor tracked in issue #99 follow-up' frontend/` also reported `PASS — all search paths agree, 4 file(s)`, so the count is not a `.gitignore` artefact.
- A `-c` grep with no match prints **nothing and exits 1**, not `0`. That is what the two "0" rows above actually measured. The search direction was validated on the same file with a token known to be present (`useSyncExternalStore` → 4, rc=0), so the empty result is a real absence rather than a broken pattern.
- The suite delta is exactly **+2 suites / +17 tests** = 14 (`use-theme.test.tsx`) + 3 (`theme-cross-surface.test.tsx`), so no pre-existing suite silently stopped running.
- **`eslint .` carries no `--max-warnings`** (RESEARCH Pitfall 6), so the 34 warnings are ungated and unchanged from baseline. The warning count is not a verdict in either direction and is recorded only to show it did not move.

## Files Created/Modified

- `frontend/hooks/use-theme.ts` — the shared store: `subscribe` / `getSnapshot` / `getServerSnapshot`, `readStored()` narrowing the untrusted storage value, `applyTheme()` writing storage + the document class + notifying, and a `classList`-sync effect.
- `frontend/hooks/__tests__/use-theme.test.tsx` — 14 behaviour cases plus the jsdom polyfills the server-render case needs.
- `frontend/components/dashboard/__tests__/theme-cross-surface.test.tsx` — 3 cases over the **real** `Sidebar` + `MobileTabBar`.
- `frontend/components/dashboard/sidebar.tsx` — `useState` + mount effect + local `toggleDark` replaced by `useTheme()`; suppression deleted. No markup, class name or button label changed.
- `frontend/components/dashboard/mobile-tab-bar.tsx` — the `classList.contains("dark")` read deleted along with its suppression and the comment describing the sidebar dependency.

## Decisions Made

1. **The `classList`-sync effect stays in the hook.** The plan's Task 2 action listed a minimal structure that would have left the document class written **only on toggle**. That is a real user-visible regression: a stored `theme=dark` would no longer be applied on load, because the sidebar's mount effect was what did it. RESEARCH Pattern 4 says so explicitly ("Keep the `classList.toggle` side effect in an effect — only the `setDark` must move"), and the Incremental Betterment Doctrine forbids trading a working good for a new one. The effect sets no state, so it is not the forbidden shape, and `eslint hooks/use-theme.ts` is rc=0 with no suppression.
2. **The cross-surface proof mounts the real components.** The plan allowed the case to live in the theme suite. It does not, because two arbitrary probes agreeing does not prove the *sidebar* and the *tab bar* agree — both could import the hook and still render a private copy. Proof-standards §5: a structural check can pass while the function is still broken.
3. **An unreadable `localStorage` is treated as ABSENT, not as `false`.** The plan's behaviour list reads "`getItem` throws → `dark === false`". The implementation returns `null` from the read and falls through to the system preference, which yields `false` under the light system stub the test installs — so the stated case holds exactly as written. But if storage were unreadable **and** the system preferred dark, this returns `true`. That is deliberate ("no preference readable" is the same state as "no preference stored") and is recorded here rather than left for a reader to discover.
4. **The fake `MessageChannel` is a correctness fix, not a shortcut.** See deviation 1.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] jsdom lacks the globals `react-dom/server` needs, and the obvious fix hangs jest**
- **Found during:** Task 1 (RED), then again in Task 2 (GREEN)
- **Issue:** Three failures in sequence. (a) `ReferenceError: MessageChannel is not defined` at `react-dom-server.browser.development.js:8818`, at module load, before any test ran — so the RED gate failed for the wrong reason. (b) After polyfilling from `node:worker_threads`, `ReferenceError: TextEncoder is not defined` at `:8835`. (c) With both present, the suite passed 14/14 but jest printed `Jest did not exit one second after the test run has completed` and then hit a hard **rc=124** timeout: React assigns `port1.onmessage`, which **re-refs** a real `MessagePort`, so `unref()`ing both ports in the constructor does not help. In CI that is a stuck job, not a red one.
- **Fix:** `TextEncoder`/`TextDecoder` come from `node:util`; `MessageChannel` is a local fake holding no OS handle. `renderToString` is fully synchronous, so no message ever needs delivering. The server renderer is imported **dynamically inside the test** so the assignments land first.
- **Verification:** rc=0, 14/14, jest exits without the open-handle warning. The plan's 34-04 executor independently measured the same `MessagePort` re-ref and reached the same fake-channel conclusion.
- **Committed in:** `2ca4e4b7` (partial) and `d9ae041b`

**2. [Rule 2 - Missing Critical] `classList` sync on mount, or dark mode stops surviving a reload**
- **Found during:** Task 2
- **Issue:** The plan's minimal structure writes the document class only inside `applyTheme`, i.e. only on an explicit toggle. Nothing would apply a stored `theme=dark` at load.
- **Fix:** A `useEffect` in `useTheme` syncing `classList` to the current snapshot, plus a test (`applies the stored preference to the document class on mount`) guarding it.
- **Verification:** The test is in the 14 and passes; `eslint hooks/use-theme.ts` rc=0 with 0 `eslint-disable` lines, so the effect did not reintroduce the suppression the plan exists to remove.
- **Committed in:** `d9ae041b`

**3. [Rule 2 - Missing Critical] A listener-teardown case for T-34-02-03**
- **Found during:** Task 1
- **Issue:** The threat register requires "a mount/unmount cycle in the suite asserts no listener survives", which was not in the plan's enumerated behaviour list.
- **Fix:** Added `removes exactly the listeners it added once the last subscriber unmounts`, asserting the **same handler reference** is removed — a bare call count would be satisfied by a teardown that removed a fresh closure and left the original attached. It reads the stub's own record of matchMedia listeners, never the hook's private `Set` (which the plan forbids asserting on).
- **Verification:** Passes; part of the 14.
- **Committed in:** `2ca4e4b7` / `d9ae041b`

**4. [Rule 3 - Blocking] A Radix modal aria-hides the sidebar, making the cross-surface count read 1 forever**
- **Found during:** Task 3
- **Issue:** Two of the three cross-surface cases failed with `Expected length: 2, Received length: 1`. The Sheet is a Radix modal dialog, so while it is open the whole sidebar carries `aria-hidden="true"` and is absent from the accessible tree. The single match was the tab bar's own button — the query would have read 1 whether or not the store was shared, which is a vacuous assertion in the making.
- **Fix:** A `themeButtons()` helper passing `hidden: true`, using `queryAllByRole` (the zero case is a real assertion and `getAll*` throws on zero). The reason is written into the file so the next reader does not "simplify" it back.
- **Verification:** 3/3 pass; the failing output is quoted in the commit message and in the test's own comment.
- **Committed in:** `041a1a49`

**5. [Rule 3 - Blocking] Worktree branched from the wrong base**
- **Found during:** Startup
- **Issue:** `git merge-base HEAD 0b6a581c` returned `896c8828`, i.e. HEAD was an **ancestor** of the intended base — the known intermittent worktree-base defect.
- **Fix:** `git reset --hard 0b6a581c…` after the HEAD-safety assertion passed (branch `worktree-agent-aadd688fefc64a37c`, in the required namespace, not a protected ref).
- **Verification:** `git rev-parse HEAD` = `0b6a581c…`, `git status --short` empty.
- **Committed in:** n/a (pre-work correction)

---

**Total deviations:** 5 auto-fixed (3 blocking, 2 missing-critical)
**Impact on plan:** No scope creep. Deviation 2 preserves a shipped user-visible behaviour the plan's minimal structure would have dropped; deviations 1 and 4 are harness faults that would otherwise have produced a hung CI job and a vacuous assertion respectively. All three tasks landed as specified.

## Issues Encountered

- **The shared scratchpad is genuinely shared.** A parallel phase-34 agent overwrote two of this plan's log files mid-run (`red2.log` came back containing 34-04's OAuth-callback output). Every later artefact was renamed with a `34-02-` prefix. No measurement in this summary comes from a clobbered file; the RED evidence was re-derived after the rename.
- **`git rev-parse --show-toplevel` was re-checked before each write**, and heredoc-based file writes were rejected by the worktree isolation guard as too complex to verify — the Write tool was used instead, with containment checked against the worktree root.

## Known Stubs

None. Every code path in `hooks/use-theme.ts` is reached by a test; the two `catch` blocks are documented no-ops for blocked storage, both exercised (the read by the unreadable-storage case).

## Threat Flags

None. This plan adds no network endpoint, no auth path, no file access and no schema change. The one trust boundary it touches — attacker-writable `localStorage` reaching a DOM sink — is T-34-02-01 in the plan's own register and is mitigated at the read: the value is narrowed to `"dark" | "light" | null` and only the derived boolean reaches `classList.toggle`.

## Threat Register Disposition

| Threat ID | Disposition | Evidence |
|---|---|---|
| T-34-02-01 | mitigated | `readStored()` returns a closed union; anything else is `null`. No string reaches `classList` or any DOM sink. Reason written into the file. |
| T-34-02-02 | mitigated | `getServerSnapshot` present; the server-render case asserts markup is produced, nothing throws, and no `dark` class is emitted **even when both client inputs say dark**. Break arm 1 proved the case can fail. |
| T-34-02-03 | mitigated | Teardown removes the same handler references it added; asserted by the unmount case, including reference identity. |
| T-34-02-04 | accepted | Unchanged — a light/dark preference is not personal data and never leaves the browser. |
| T-34-02-SC | mitigated | `git diff --name-only -- frontend/package.json frontend/package-lock.json` is empty. Nothing was installed; `useSyncExternalStore` is React 19 core. |

## Next Phase Readiness

- **34-03** (`hooks/use-customer-session.ts`) and **34-04** (`app/shop/auth/callback/page.tsx`) own the remaining two `#99 follow-up` markers. `hooks/use-theme.ts` is the worked example for 34-03's harder case (two consumers, plus #465's single-flight refresh underneath).
- **34-10 must pick up +17 Jest blocks** (14 + 3) across **2 new files**. `docs/metrics.json` was deliberately **not** regenerated here — 34-10 is its single writer — so `docs-freshness` will read as drifted until it runs. That is the plan's design, not an oversight.
- No blockers. `npm run build` is rc=0 with TypeScript clean, so the dashboard shell still server-renders.
- Not covered here, and not claimed: no browser was driven. Every assertion above is jsdom, ESLint, or a production build. A live check that the dashboard theme toggle still behaves at 375px belongs to the phase's e2e/UAT step.

## Self-Check: PASSED

Created files exist:
- `frontend/hooks/use-theme.ts` — FOUND
- `frontend/hooks/__tests__/use-theme.test.tsx` — FOUND
- `frontend/components/dashboard/__tests__/theme-cross-surface.test.tsx` — FOUND

Commits exist (`git log --oneline`): `2ca4e4b7` FOUND, `d9ae041b` FOUND, `041a1a49` FOUND.

Working tree clean after all break arms; both restore hashes match their committed blobs.

---
*Phase: 34-rendering-test-truthfulness*
*Completed: 2026-08-28*
