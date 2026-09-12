---
phase: quick-260912-w0i
plan: 01
subsystem: testing
tags: [e2e, playwright, accessibility, aria-label, locator, cart-identity, rls-adjacent, ci-gate]

requires:
  - phase: "PR #726 (QA council 20260902-134741, A11Y-4)"
    provides: "the storefront Add button's aria-label — the correct change this plan follows rather than reverts"
provides:
  - "ADD_TO_BASKET: one named accessible-name locator constant in cart-identity-boundary.verify.mjs, used at all three former sites"
  - "the #459 / R-16 cart-identity gate is executable again (it had been VOID at 9 of 18 checks since 2026-09-07)"
  - "issue #741: the decay mechanism behind it, with the deny-list grep gate evaluated and rejected"
affects: [e2e-nightly, cart-identity, storefront-locators, future aria-label renames]

tech-stack:
  added: []
  patterns:
    - "Accessible-name locators live in ONE named constant per control, and the pattern is shared verbatim with every other instrument that locates the same control"

key-files:
  created:
    - .planning/quick/260912-w0i-fix-the-cart-identity-gate-s-dead-add-bu/260912-w0i-SUMMARY.md
  modified:
    - frontend/e2e/cart-identity-boundary.verify.mjs

key-decisions:
  - "The component is correct; the test locator was the defect. shop-detail-client.tsx, the aria-label and every *.spec.ts are byte-identical to main — proven by sha256, with the comparison shown non-vacuous."
  - "Pattern adopted VERBATIM from storefront-dish-modal-a11y.spec.ts:276 (/^Add .+ to basket/) rather than invented, so the three instruments that locate this control cannot drift to three patterns."
  - "Case-SENSITIVE and end-unanchored on purpose: /i would admit only names that do not exist; the Popular-rail copy carries a ' (featured)' suffix."
  - "EXPECTED_CHECKS stays at 18 and no check arm changed (23 check/results.push call sites before and after). The VOID is the instrument working correctly — silencing it would defeat the gate this plan repairs."
  - "The dead pattern's literal token was deliberately REMOVED from the explanatory comment: leaving it in prose tripped this plan's own verification #1 and would be a landmine for any future detector for that token."

patterns-established:
  - "Fail-direction first: the break arm was run on the SHIPPED tree before the edit, not reconstructed after it."
  - "A bracket that reads the pattern out of the edited file's bytes, so transcription error cannot explain a green result."

requirements-completed: [GATE-459-LIVENESS]

duration: ~35min
completed: 2026-09-12
---

# Quick 260912-w0i: cart-identity gate's dead Add locator Summary

**The #459 / R-16 cart-identity gate had been unexecutable since #726 gave the storefront Add button an `aria-label`: `aria-label` replaces the accessible name, so the anchored single-word locator matched nothing and the run exited VOID at 9 of 18 checks. One named constant carrying the pattern the already-green spec uses restores all three sites, with the component untouched.**

## Performance

- **Duration:** ~35 min
- **Tasks:** 3 (Task 2 intentionally stopped after `git push` — see Scope)
- **Source files modified:** 1

## Scope actually executed

| Task | Status |
|------|--------|
| Task 1 — named locator constant, proven both directions without a browser | **DONE** |
| Task 2 — commit + push | **DONE up to and including `git push`** |
| Task 2 — nightly dispatch + verdict read | **NOT DONE BY ME — owned by the orchestrator, which holds the monitor** |
| Task 3 — record the decay follow-up | **DONE — issue #741** |

**The runtime proof is therefore OUTSTANDING at the time of writing, and nothing below
should be read as supplying it.** The plan is explicit that the nightly is the only lane
with a real stack, and that a green local assertion is not evidence the gate runs.

## The change

One file, three locator sites, one new constant:

```js
const ADD_TO_BASKET = /^Add .+ to basket/
```

- `addFirstProduct` (was line 236) — `{ name: ADD_TO_BASKET }`
- `postOrderClear`, the `adds` collection (was line 579) — `{ name: ADD_TO_BASKET }`
- `postOrderClear`, the click inside the loop (was line 584) — `{ name: ADD_TO_BASKET }`

Plus a block comment recording what #726 changed and why the aria-label is correct, and an
extension to the `postOrderClear` comment recording the featured-duplicate consequence
explicitly (the matched SET is the same 9 it was, so `adds.count()` is unchanged; clicking a
featured-rail copy retires TWO matches while a list-only copy retires one; acceptable
because C4 needs only a basket above the shop minimum and C4.0 asserts non-empty regardless).

## Evidence

### 1. Break arm, run on the SHIPPED tree BEFORE the edit

`$SCRATCH/locator-bracket.mjs` (scratchpad, never committed), `rc=0`, all 15 cases:

```
  PASS  [ 0] NEW /^Add .+ to basket/ MATCHES     "Add Party Jollof Rice to basket"  (actual=true)  -- category-list copy
  PASS  [ 1] NEW /^Add .+ to basket/ MATCHES     "Add Jollof Rice to basket (featured)"  (actual=true)  -- Popular-rail copy; end deliberately unanchored
  PASS  [ 2] NEW /^Add .+ to basket/ DOES NOT match "Add"  (actual=false)  -- the pre-#726 name: NEW is not a catch-all
  PASS  [ 3] NEW /^Add .+ to basket/ DOES NOT match "Increase quantity of Jollof Rice"  (actual=false)  -- stepper increment (line 186)
  PASS  [ 4] NEW /^Add .+ to basket/ DOES NOT match "Decrease quantity of Jollof Rice"  (actual=false)  -- stepper decrement
  PASS  [ 5] NEW /^Add .+ to basket/ DOES NOT match "Remove Jollof Rice from basket"  (actual=false)  -- stepper decrement at quantity 1
  PASS  [ 6] NEW /^Add .+ to basket/ DOES NOT match "Add one more Jollof Rice to cart"  (actual=false)  -- dish modal: 'to cart', not 'to basket'
  PASS  [ 7] NEW /^Add .+ to basket/ DOES NOT match "View details for Jollof Rice"  (actual=false)  -- card trigger
  PASS  [ 8] NEW /^Add .+ to basket/ DOES NOT match "Unavailable"  (actual=false)  -- out-of-stock span
  PASS  [ 9] NEW /^Add .+ to basket/ DOES NOT match "Back to basket"  (actual=false)  -- checkout nav
  PASS  [10] NEW /^Add .+ to basket/ DOES NOT match "Collection"  (actual=false)  -- checkout fulfilment toggle
  PASS  [11] NEW /^Add .+ to basket/ DOES NOT match "Place order · £38.00"  (actual=false)  -- checkout submit
  PASS  [12] OLD /^add$/i MATCHES     "Add"  (actual=true)  -- POSITIVE CONTROL: OLD is well-formed
  PASS  [13] OLD /^add$/i DOES NOT match "Add Party Jollof Rice to basket"  (actual=false)  -- BREAK ARM: OLD cannot match what the app renders
  PASS  [14] OLD /^add$/i DOES NOT match "Add Jollof Rice to basket (featured)"  (actual=false)  -- BREAK ARM: OLD cannot match what the app renders

ALL 15 locator cases behaved as declared (failed=0)
```

Cases 13 and 14 ARE the break arm: the shipped `/^add$/i` is incapable of matching either
name the component renders today. Case 12 is its positive control — the old pattern is
well-formed, so its failure is #726's rename and not a typo in my transcription of it.

**GREEN-BY-CONSTRUCTION, stated plainly:** this bracket compares regexes to string literals
I typed, not to a render. It is NOT a decay detector and NOT the runtime proof. Its only job
is the break arm.

### 2. The harness itself was shown capable of going red

A check never observed failing may be incapable of failing:

```
BRACKET_SELFTEST_FLIP=13 -> rc=1
  FAIL  [13] OLD /^add$/i MATCHES     "Add Party Jollof Rice to basket"  (actual=false)
  VIOLATION at case 13: expected test()===true, got false

BRACKET_SELFTEST_FLIP=0  -> rc=1
  FAIL  [ 0] NEW /^Add .+ to basket/ DOES NOT match "Add Party Jollof Rice to basket"  (actual=true)
  VIOLATION at case 0: expected test()===false, got true
```

### 3. A second bracket reading the pattern out of the EDITED FILE'S BYTES

This removes transcription error as an explanation for a green result (`rc=0`, 13 cases):

```
extracted from frontend/e2e/cart-identity-boundary.verify.mjs: /^Add .+ to basket/  -> body="^Add .+ to basket" flags=""
ALL 13 cases behaved as declared, against the pattern read out of the file
```

Its three fail arms, each with a distinct disposition (VOID is never a pass):

```
ARM A  constant absent (the before-image)      -> rc=2  VOID: could not extract ADD_TO_BASKET
ARM B  constant set to the old /^add$/i        -> rc=2  VOID: case-insensitive flag present
ARM C  constant set to a loose /^Add/          -> rc=1  2 VIOLATION(S): matched "Add" and "Add one more … to cart"
CLOSING CLEAN ARM  real file untouched         -> const ADD_TO_BASKET = /^Add .+ to basket/ at line 265; 13/13 again
```

### 4. Pre-existing, CI-enforced render pin — NOT my evidence to claim

`frontend/__tests__/shop/server-seeded-islands.test.tsx` renders the real `ShopDetailClient`
and asserts the literal names `"Add Jollof Rice to basket"` and
`"Add Jollof Rice to basket (featured)"`, and itself uses `/^Add .+ to basket/`:

```
rc=0   Test Suites: 1 passed, 1 total   Tests: 8 passed, 8 total
```

This pins component → rendered names from an actual render. It is **PRE-EXISTING and
CI-enforced; its fail direction was established by #726's authors.** I did not break the
component to re-prove it and do not report it as if I had.

### 5. Searches — every one with a control

```
rg -uu -n '\^add\$' frontend/e2e                      -> rc=1, empty      (no dead locator survives)
  SAME pattern+flags on the before-image              -> rc=0, 3 hits (236, 579, 584)
  ^ proves the rc=1 is real absence, not a broken search direction
rg -uu -c -F 'const ADD_TO_BASKET = /^Add .+ to basket/'  -> 1   (one definition)
rg -uu -c -F '{ name: ADD_TO_BASKET }'                    -> 3   (three live uses)
rg -uu -c 'ADD_TO_BASKET'                                 -> 5   (1 def + 3 uses + 1 prose mention)
rg -uu -c -F 'const EXPECTED_CHECKS = 18'                 -> 1   (untouched)
check()/results.push call sites, before vs after          -> 23 vs 23  IDENTICAL
```

### 6. Exactly one source file changed — by content hash

```
IDENTICAL  5bbc143f7d2af5a0  frontend/app/shop/[slug]/shop-detail-client.tsx
IDENTICAL  6e7d0f3544ffd82e  frontend/e2e/storefront-dish-modal-a11y.spec.ts
IDENTICAL  7bb4769115c91855  docs/metrics.json
IDENTICAL  70717ac2dc91e236  frontend/__tests__/shop/server-seeded-islands.test.tsx
DIFFERS    main=69a1834733af44e4 now=1fb87324a4eb9e07  frontend/e2e/cart-identity-boundary.verify.mjs
```

The last line is the fail direction for that same comparison — it shows the hash check is
not vacuous. `git diff --name-only main...HEAD` plus the working tree lists only the plan
doc and this one `.mjs`; `-- '*.spec.ts'` is empty.

`docs/metrics.json` is untouched and must stay so: `docs-freshness.sh:114` counts
`^frontend/e2e/.*\.spec\.ts$` only, and that regex cannot match a `.mjs`.

### 7. Lint and parse, each with its own fail direction

```
npx eslint e2e/cart-identity-boundary.verify.mjs   -> rc=0, no output, NOT "File ignored"
node --check frontend/e2e/cart-identity-boundary.verify.mjs -> rc=0
  fail arm: append 'const broken = ('  -> rc=1  (node --check CAN fail here)
  closing clean arm: probe token absent (rc=1), parses again (rc=0), 3 live uses intact
```

**Recorded honestly — the eslint gate is weaker than the plan assumed.** A deliberately
injected unused/undefined identifier produced a WARNING and **rc=0**, not a failure:

```
737:7  warning  'deliberatelyUnused' is assigned a value but never used  @typescript-eslint/no-unused-vars
✖ 1 problem (0 errors, 1 warning)     rc=0
```

So `npx eslint … ; rc=0` is NOT by itself proof of cleanliness for warning-level rules. What
the probe does establish is that eslint really analysed this file (it reported a problem in
it, and never said "File ignored") — i.e. the gate is non-vacuous as a *reachability* check,
not as a pass/fail gate. The restore was then confirmed by content, not by `diff --stat`.

### 8. Fail direction in the real medium — pre-existing, not manufactured

Per the plan, NOT reproduced: the same step, same lane, on the unfixed tree, red at this one
step with the identical timeout — scheduled nightlies **2026-09-07**, **2026-09-08**,
**2026-09-09**, and dispatch **34401146291**, the last of which was otherwise green (stack
healthy, OpenAPI 0 differences across 109 paths, Keycloak logout-URI gate PASS, Playwright
total=323 passed=317 failed=0 skipped=6, skip budget PASS). Nothing was broken to re-prove it.

## Deviations from plan

**1. [Rule 1 — Bug in the plan's own verification] The explanatory comment tripped verification #1**

- **Found during:** Task 1, first run of `rg -uu -n '\^add\$' frontend/e2e`
- **Issue:** my new block comment *named* the dead pattern to explain it, so the search
  returned `rc=0` with one hit in prose. This is the documented "a doc rule fires on its own
  definition" shape, and it would also be a landmine for exactly the kind of detector Task 3
  discusses.
- **Fix:** reworded to describe the old locator precisely ("an anchored, case-insensitive
  exact match on the single word \"Add\", deliberately not spelled out here so a future
  detector for it cannot trip on the comment explaining it"). All meaning kept; the token gone.
- **Why not the other way:** redefining the criterion mid-execution to accept my own prose
  would be substituting a weaker check for the declared one.

**2. [Documented, not fixed] The positive control counts 5, not the 4 the plan predicted**

`rg -uu -c 'ADD_TO_BASKET'` gives **5**, because the `postOrderClear` comment the plan itself
instructed me to write names the constant. The plan's two instructions are in mild conflict;
the load-bearing claim (1 definition + 3 live uses) is verified structurally instead, by
line listing and by two `-F` counts. No weakening: the looser count is reported alongside.

**3. [Tooling] Python was blocked; used node instead**

`block-base-python` refused a Python edit script (no conda env declared for this repo). I did
not reroute around the guard or declare an env to satisfy it — I used node, the project's own
runtime, for the same text edit.

**4. [Scope] The nightly dispatch was deliberately not run**

Per the orchestrator's scope boundary. See "Outstanding" below.

## Follow-up filed

**#741** — `e2e: the *.verify.mjs nightly gates decay silently — no PR runs them, and nothing
ties their locators to the names the component renders` (OPEN). Records the mechanism
(`*.verify.mjs` runs only in the nightly, invoked by `node` directly, so `playwright test`
never sees it and no PR exercises it; #726 updated the specs and the jsdom tests and missed
this file; 3 nightlies + 1 dispatch burned), why the obvious grep gate is rejected (deny-list
fails OPEN; plus the unwired-gate rule), and the two allow-list candidates worth costing
(shared exported constant; jsdom-render assertion over the e2e locators, the only shape that
fails closed).

Referenced issues were unchanged by the body: **459 CLOSED** and **726 MERGED** both before
and after. A lexical guard for `(close|fix|resolve)[sd]? #N` ran clean on the body and was
shown capable of firing on a control string (`this does not close #459 at all` / `fixes #726`).
**Caveat:** #459 was already CLOSED, so the state check could not have detected an accidental
close of it — the lexical guard is the real protection there.

## Outstanding / unverified

- **The runtime proof.** The nightly step `Gate — cart identity boundary (#459 / R-16)` has
  NOT been observed green on this branch. Accepting it requires all four: step conclusion
  `success`; `ALL PASS`; `18/18 checks passed`; and NO `VOID: expected at least` line. A
  `skipped` step (fixtures never came up), `gh` rc=1, or an empty status table is **VOID**,
  not a pass.
- **The rest of the C4 / C2+C1 chain is verified only by static inspection.** The plan records
  that `Collection`, `Place order`, `allergen-ack-row` and `cart-item-count` are all still
  live in product code, so no second locator is expected to be dead — but only the dispatch
  settles it. If the gate fails further down on another locator, the repair belongs in THIS
  file only, under the same both-directions criteria.
- **Whether the matched set is 9 at runtime** is inferred from the seeded fixture (7 list + 2
  featured-rail copies), not measured in a browser by me.
