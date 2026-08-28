---
phase: 34-rendering-test-truthfulness
plan: 03
subsystem: frontend-customer-session
tags: [react-19, useSyncExternalStore, set-state-in-effect, customer-session, playwright, tdd]
requirements: [TRUTH-01]
requires:
  - "frontend/lib/customer-auth.ts getCustomerSession() — the server-truth probe (unchanged)"
  - "React 19 useSyncExternalStore"
provides:
  - "frontend/lib/customer-session-store.ts — the single customer-session source for all three public surfaces"
  - "a synchronous, reference-stable snapshot with a null server snapshot"
  - "browser coverage for the session pill (frontend/e2e/storefront-session-pill.spec.ts)"
  - "global jest isolation for module-level stores (jest.setup.js beforeEach)"
affects:
  - "components/public/public-header.tsx (unchanged — reads the hook)"
  - "components/public/public-footer.tsx (unchanged — reads the hook)"
  - "components/storefront/storefront-nav.tsx (unchanged — reads the hook)"
tech-stack:
  added: []
  patterns:
    - "useSyncExternalStore over a module-level store (the sanctioned fix for set-state-in-effect)"
    - "identity-keyed snapshot dedup over the interface's own declared fields"
    - "presence control + server-probe wait before any absence assertion in Playwright"
key-files:
  created:
    - frontend/lib/customer-session-store.ts
    - frontend/hooks/__tests__/use-customer-session.test.tsx
    - frontend/e2e/storefront-session-pill.spec.ts
  modified:
    - frontend/hooks/use-customer-session.ts
    - frontend/jest.setup.js
    - frontend/eslint.config.mjs
decisions:
  - "Dedup the snapshot on the profile's four declared fields, not on `sub` alone — keying on the subject silently discards a real display-name change"
  - "Reset the store globally in jest.setup.js rather than per suite — 14 further suites are one reordering away from the same cross-test leak"
  - "No @desktop-only / @mobile-only tag on the new spec — the helper adapts to the viewport so neither project is left unchecked"
metrics:
  duration: "~36 min (22:08 base → 22:44 last task commit, +18 min of break arms and rebuilds)"
  completed: 2026-08-28
  tasks: 3
  commits: 3
---

# Phase 34 Plan 03: Customer Session External Store Summary

The mount-time `setState` in `hooks/use-customer-session.ts` — the highest-risk of the four
`#99 follow-up` suppressions — is gone, replaced by `lib/customer-session-store.ts` read through
`useSyncExternalStore`, with the `{ profile, refresh }` contract and all three consumer files
untouched.

## Measured consumer count: THREE, not two

The plan was explicit that RESEARCH and the pattern map both inherited a count of two. Confirmed on
this tree with `rg -uu`:

| File | Line | Reads |
|---|---|---|
| `frontend/components/public/public-header.tsx` | 58 | `const { profile } = useCustomerSession()` |
| `frontend/components/public/public-footer.tsx` | 72 | `const { profile } = useCustomerSession()` |
| `frontend/components/storefront/storefront-nav.tsx` | 24 | `const { profile } = useCustomerSession()` |

All three destructure `{ profile }` only. The footer is the one the smaller number omits, and it
turned out to matter: two of the four tests that went red mid-plan were footer tests.

## RED / GREEN record

| Gate | Commit | Command | Result |
|---|---|---|---|
| RED | `c07ed69c` | `npx jest hooks/__tests__/use-customer-session.test.tsx --ci` | **rc=1**, `Cannot find module '../../lib/customer-session-store'`, **0 tests run** |
| GREEN | `13a1d65a` | same | **rc=0**, **19/19 passed** |
| E2E | `33beb7f9` | `npx playwright test e2e/storefront-session-pill.spec.ts --project=desktop --project=mobile` | **rc=0**, **4/4 passed** on both projects |

Gate order verified in `git log --oneline`: `test(34-03)` → `feat(34-03)` → `test(34-03)`.

Suite counts: **19 `it` blocks** — one per behaviour bullet (B1–B10), three sub-clause cases
(B8b poll bound, B8c visibility gate, B8d focus path), one security case (SEC / T-34-03-01) and
five hook-contract cases (H1–H5).

Whole-suite delta: **120 suites / 1230 tests → 121 suites / 1249 tests**, rc=0 both times.

## Break arms — all five executed, with their real output

Committed before every arm, so each restore is verifiable by content. **Clean → arms → clean
again**; the closing clean run is the only proof the restores took.

### Arm 1 — the ESLint gate must fire on a reintroduced mount effect

Reinstated the pre-34-03 hook with its suppression **deleted**.

```
npx eslint hooks/use-customer-session.ts   ->  rc=1
17:5  error  Calling setState synchronously within an effect can trigger cascading renders
             ...  react-hooks/set-state-in-effect
> 17 |     checkSession()
     |     ^^^^^^^^^^^^ Avoid calling setState() directly within an effect
```

Note what this also confirms: the rule fired on `checkSession()` — a **call**, not a literal
`setState`. That is RESEARCH's row C reproduced, and it rules out any "move it into a helper"
non-fix. Restored; `git hash-object` → `e11c772c74db2e828f0637e957b84e630a5a9d15` (matches the
committed blob).

### Arm 2 — the security clause (T-34-03-02): a null answer must clear the cache

Changed `refresh()` to keep the previous profile when `getCustomerSession()` resolves null.

```
rc=1, 2 failed / 17 passed
● B6: refresh() resolving to null clears the cached profile and notifies (sign-out / expiry)
    expect(received).toBeNull()
    Received: {"email": "alice@example.com", "emailVerified": true,
               "name": "Alice Adeyemi", "sub": "kc-alice"}
● H4: a signed-out answer collapses every mounted reader, even with the marker set
    (same, at the hook level)
```

The failure **names the surviving profile**, which is exactly the disclosure the clause prevents.
Restored; hash → `52754043ed2fe96477f3984a3611ad974ded2351`.

### Arm 3 — snapshot stability

Returned a fresh object from `getSnapshot()` on every call.

Two distinct reds, both recorded for what they are:

1. **The whole-file run did not terminate.** React reported
   `The result of getSnapshot should be cached to avoid an infinite loop` at
   `use-customer-session.ts:39` and the run had to be killed at 300s. The plan anticipated a
   "maximum update depth" error as an acceptable red; what actually happens is the runaway itself.
2. **B2 in isolation** (`-t "B2:"`, no React, so it terminates) — rc=1:

```
● B2: getSnapshot() returns the SAME reference on repeated calls while nothing changed
    expect(received).toBe(expected) // Object.is equality
    If it should pass with deep equality, replace "toBe" with "toStrictEqual"
    Expected: {"email": "alice@example.com", ... }
    Received: serializes to the same string
```

`Received: serializes to the same string` is the point: `toEqual` would have **passed** here. Only
`toBe` catches it. Restored; hash → `52754043ed2fe96477f3984a3611ad974ded2351`.

### Arm 4 — the Playwright block must fail on a marker-only reader

Reintroduced the reader the docblock forbids (`getSnapshot()` fabricates a profile when
`jtoye-customer-logged-in` is set, latched so the snapshot stays reference-stable and *lies* rather
than loops). **Rebuilt** (`npm run build`, rc=0) and restarted `next start` before running — a
`start` alone would have proved nothing about the changed source.

```
rc=1 — 4 failed (both projects, both blocks)
Block 1: expect(locator).toBeVisible() failed
  Locator: getByRole('navigation', { name: 'Storefront' })
             .getByRole('link', { name: /^sign in$/i })
  Error: element(s) not found
```

The red lands on the signed-**out** control vanishing, because the two branches are mutually
exclusive. Playwright's error context carries the accessibility snapshot at that moment, and it
names the signed-in controls directly:

```
- link "My Orders":
- text: Arm Customer
- button "Sign out"
```

So the planted marker did produce a signed-in pill with no session anywhere, and the block caught
it. Restored; hash → `52754043ed2fe96477f3984a3611ad974ded2351`.

### Arm 5 — the presence control is load-bearing, not decoration

A throwaway spec carrying **only** the absence assertions, pointed at `/shop/zzz-not-a-shop`:

```
✓ VACUITY: absence assertions alone pass over a 404 page   —  rc=0, 1 passed
```

It passes. A page with no session pill at all satisfies "no My Orders" and "no Sign out"
perfectly. That is why both committed blocks assert a landmark first and wait for a real
`/api/customer-auth/session` response before asserting anything absent. Probe deleted; the tree is
clean (`git status --short` empty).

### Closing clean arm

After all five restores, rebuilt and restarted, then:

- `npx jest hooks/__tests__/use-customer-session.test.tsx` → **rc=0, 19/19**
- `npx playwright test e2e/storefront-session-pill.spec.ts --project=desktop --project=mobile` →
  **rc=0, 4/4**
- working-tree hashes match the committed blobs for all three touched source files

## Deviations from Plan

### 1. [Rule 1 — Bug] Dedup on `sub` alone silently drops a real profile change

- **Found during:** Task 2, on the first full-suite run.
- **Issue:** The plan specified equivalence by "a stable key on the profile (prefer an id/sub,
  falling back to email)". Implemented literally, the same subject arriving with an updated display
  name is discarded as "equivalent", so a stale name survives for the life of the tab. It is not
  hypothetical: it reds `components/public/__tests__/public-header-session.test.tsx:70` ("falls
  back to the email when the profile carries no name"), whose nameless profile shares `sub: "u1"`
  with the profile cached by the tests before it.
- **Fix:** The key enumerates the interface's own four declared fields. This keeps the property the
  plan actually argued for — immunity to fields the server later **adds**, unlike a
  `JSON.stringify` of the whole object — without discarding what is on screen. `sub` and `email`
  still decide identity; a profile with neither is treated as equivalent to nothing, because
  refusing to dedupe what cannot be identified is the safe direction.
- **Files:** `frontend/lib/customer-session-store.ts`
- **Commit:** `13a1d65a`

### 2. [Rule 3 — Blocking] Module state outlives a jsdom test

- **Found during:** Task 2, same run. 3 suites / 4 tests red.
- **Issue:** Jest resets the module registry per **file**, not per test. Moving the session into
  module state means every consumer test inherits the previous test's session. The sharpest case is
  `public-footer-persona.test.tsx:188`, "emits the operator links before the session resolves",
  which asserts the **synchronous first paint** — its entire premise is that nothing has resolved
  yet.
- **Fix:** `__resetForTests()` on the store, wired as a global `beforeEach` in `jest.setup.js`. It
  is global on purpose: 14 further suites render one of the three consumers and are one reordering
  away from the same failure, and a rule every author must remember is not a fix. The `require` is
  **inside** the hook — `setupFilesAfterEnv` runs before a test file registers its `jest.mock`
  calls, so a top-level import would bind the store to the real auth module and defeat every
  consumer suite's mock.
- **Files:** `frontend/jest.setup.js`, `frontend/lib/customer-session-store.ts`
- **Commit:** `13a1d65a`

### 3. [Rule 3 — Blocking] `no-require-imports` on `jest.setup.js`

- **Issue:** `npx eslint .` → rc=1, one error, mine: `jest.setup.js:150 A require() style import is
  forbidden`. The config already exempts `jest.config.js` as a CommonJS module.
- **Fix:** Extended that existing exemption to `jest.setup.js`, with the reason recorded in the
  config — the `require` there is load-bearing (see deviation 2), not stylistic.
- **Files:** `frontend/eslint.config.mjs`
- **Commit:** `13a1d65a`

### 4. [Plan-shape] The E2E spec runs on BOTH projects rather than one

The plan did not say which projects. `PublicHeader`'s sign-in control sits in a `hidden sm:flex`
row and moves into the hamburger sheet at 390px, so a naive block would only work on desktop. Rather
than tagging `@desktop-only` — which leaves a viewport unchecked — a helper asks the page which
shape it is in. `getByRole` excludes `display:none`, so the hamburger branch is taken because the
control genuinely is not in the accessibility tree, not because a guess was made. No runtime
`test.skip`: `playwright.config.ts` is explicit that a skip must mean "nobody checked this".

## Incremental Betterment — goods displaced, and what happened to each

| Existing good | Status |
|---|---|
| `{ profile, refresh }` contract | **Preserved.** `git diff --name-only HEAD~2 --` over the three consumers prints nothing, and each still imports the hook (`rg -uu -c useCustomerSession` → 2 / 3 / 2) — the control that proves the emptiness is not because the files vanished |
| Mount-time session check | **Preserved.** `subscribe()` probes immediately on the first subscriber. Without it the first reader would show "Sign in" to a signed-in customer until the poll's first tick — a visible regression, and B8b asserts the immediate call is exactly 1 |
| focus / visibilitychange / storage listeners, 5×1s post-OAuth poll | **Preserved**, same numbers, now asserted (B8, B8b, B8c, B8d, B10) rather than merely present |
| #465 single-flight refresh + token rotation | **Untouched.** `git diff --name-only HEAD~2 -- lib/api-client.ts lib/customer-auth.ts` prints nothing (T-34-03-05) |
| No "Sign in" flash when navigating `/shop` → `/` | **Improved.** The cache now outlives the unmount, so the newly mounted header paints the known session immediately instead of starting from null. This is why `__resetForTests` exists and why the cache is deliberately NOT cleared on last-unsubscribe |
| Whole-tree jest / eslint / build | **No regression.** 1249 pass (was 1230, +19 new); eslint 0 errors and the *same* 34 warnings as before the change; `npm run build` rc=0 |

## Threat model outcome

| Threat | Disposition | Evidence |
|---|---|---|
| T-34-03-01 spoofing via the marker | mitigated | `isLoggedIn()` never imported; SEC unit case asserts it is never called; Playwright Block 1 plants the marker and requires a signed-out pill; **arm 4 proved that block can fail** |
| T-34-03-02 stale profile after sign-out | mitigated | B6 + H4; **arm 2 proved they fail when the clearing is removed**, naming the surviving profile |
| T-34-03-03 server render leaking a profile | mitigated | `getServerSnapshot()` returns a literal null; B3 asserts it **while a profile is cached** (so the null is a decision, not an empty cache) and spies prove no `Storage.getItem` / `addEventListener` read |
| T-34-03-04 unbounded poll / listener leak | mitigated | B8b bounds the poll (≥4 and ≤5 calls, then unchanged after +20s); B9 compares add/remove by **handler identity** and requires `jest.getTimerCount() === 0`; H5 does the same through a real mount/unmount |
| T-34-03-05 token rotation (#465) | accept | `git diff --name-only HEAD~2 -- frontend/lib/api-client.ts frontend/lib/customer-auth.ts` → empty |
| T-34-03-SC npm installs | mitigated | `git diff --name-only HEAD~2 -- frontend/package.json frontend/package-lock.json` → empty; nothing installed |

## Verification

| Check | Result |
|---|---|
| `npx jest --ci --watchAll=false` | rc=0 — **121 suites / 1249 tests** (baseline 120 / 1230) |
| `npx eslint .` | rc=0 — **0 errors**, 34 warnings (identical set to pre-change) |
| `npm run build` | rc=0 — the only step that type-checks the three consumers |
| `npx playwright test e2e/storefront-session-pill.spec.ts --project=desktop --project=mobile` | rc=0 — 4/4, against the **branch build** on `:3123` **and** the live Compose stack on `:3000` |
| `npx playwright test …session-pill …public-layout --project=desktop` | rc=0 — **21/21**, the 19 public-layout regressions covering the headers this store now feeds |
| `bash scripts/check-e2e-baseurl-contract.sh` | rc=0 — 23 specs, 14 declared fallbacks, **0 divergent**; this spec declares none |
| `bash scripts/check-e2e-typecheck.sh` | rc=0 — 26 e2e files, their only type-check cover |
| `rg -uu -c 'refactor tracked in issue #99 follow-up' …use-customer-session.ts` | **0** (pre-edit control: **1**); positive control `rg -uu -c useSyncExternalStore` on the same file → **3**, so the zero is a real absence and not a broken search |
| `git log --oneline -3` | `test(34-03)` before `feat(34-03)` — TDD gate order intact |

### Runtime parity

The Compose frontend on `:3000` is built from the phase base, so a green run there proves the spec
works but says nothing about this branch. Every arm and the closing clean run therefore used a
`next start` served from a **rebuilt** branch artifact. `.next/BUILD_ID` mtime was checked against
the sources and initially read **older** — not because the build was stale but because
`git checkout --` during the arms rewrites a file and bumps its mtime even when the bytes are
identical (the trap `playwright.config.ts` documents). Rather than argue from mtime, the tree was
rebuilt so the artifact unambiguously post-dates every source, and arm 4 supplies the functional
proof: changing the store and rebuilding flipped the spec red, which is only possible if the served
artifact is built from this working tree.

## Not done here, deliberately

- `docs/metrics.json` is **not** regenerated. Plan 34-10 is its single writer. This plan adds 19
  Jest `it` blocks (1230 → 1249), 1 Jest suite (120 → 121) and 2 Playwright `test()` blocks
  (22 → 23 spec files), and 34-10 owns reconciling those numbers with `CLAUDE.md`, `AGENTS.md` and
  `README.md`.
- `STATE.md` / `ROADMAP.md` are not touched — the orchestrator owns those writes.

## Known Stubs

None. No hardcoded empty values, placeholder text or unwired components were introduced.

## Self-Check: PASSED

```
FOUND: frontend/lib/customer-session-store.ts
FOUND: frontend/hooks/use-customer-session.ts
FOUND: frontend/hooks/__tests__/use-customer-session.test.tsx
FOUND: frontend/e2e/storefront-session-pill.spec.ts
FOUND: commit c07ed69c   test(34-03): add failing customer-session store suite
FOUND: commit 13a1d65a   feat(34-03): implement customer-session external store
FOUND: commit 33beb7f9   test(34-03): cover the session pill in a real browser
CLEAN:  git status --short  (empty)
```
