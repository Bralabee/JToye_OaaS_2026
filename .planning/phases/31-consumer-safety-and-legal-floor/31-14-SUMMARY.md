---
phase: 31-consumer-safety-and-legal-floor
plan: 14
subsystem: ui
tags: [allergens, accessibility, wcag, checkout, storefront, radix, jest-axe, consumer-safety]

requires:
  - phase: 31-10
    provides: "The order allergen wire contract (allergenMask / allergenNames / allergenFlags) and the null-vs-empty semantics this surface renders"
  - phase: 31-01
    provides: "components/ui/checkbox.tsx — the 24px Radix primitive that carries aria-invalid and form semantics"
  - phase: 31-04
    provides: "OrderAllergenAggregator — the server-side reconciliation heuristic this surface deliberately does NOT re-implement"
provides:
  - "OrderAllergenPanel — a purely presentational allergen surface that keeps NOT RECORDED, DECLARED-NONE and DECLARED strictly apart"
  - "A pre-submit acknowledgement gate that refuses before any network call, announces the refusal, and moves focus to the control that refused"
  - "A11Y-08 CLOSED — 7 valid HTML autofill tokens on 7 user-data checkout inputs"
  - "A11Y-07 closed on the checkout — field errors carry id + aria-describedby + aria-invalid, generic error is a role=alert region"
  - "The first aria-invalid, aria-describedby-on-a-field and programmatic focus-move in this codebase"
affects: [31-13, 31-15, 31-18]

tech-stack:
  added: []
  patterns:
    - "Three-state allergen rendering: null (not recorded) / [] (declared none) / non-empty — branched explicitly, never by truthiness"
    - "Refusal-over-disable: a gate that refuses and announces rather than disabling a button"
    - "Count-comparison accessibility assertions with a floor, so a both-zero reading cannot pass"

key-files:
  created:
    - frontend/components/storefront/order-allergen-panel.tsx
    - frontend/components/storefront/__tests__/order-allergen-panel.a11y.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/allergen-acknowledgement.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/checkout-form-a11y.test.tsx
  modified:
    - frontend/app/shop/[slug]/checkout/page.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/checkout.test.tsx
    - frontend/jest.setup.js
    - docs/metrics.json

key-decisions:
  - "The pre-submit set is derived from the BASKET, not from an order DTO — no order exists at that point; 31-10's SUMMARY states this explicitly"
  - "allergenFlags is passed null, not [] — the advisory heuristic is a ~150-term server-side synonym list and a second ungated copy is not something this surface should author"
  - "Basket resolution is all-or-nothing: any unresolved line yields NOT RECORDED rather than a partial, under-stated union"
  - "The submit button stays ENABLED; the gate refuses in the handler"
  - "The NOT RECORDED copy is AUTHORED, not contracted — the UI-SPEC supplies no string for this third state"

patterns-established:
  - "Legally-operative copy exported as named constants so component, tests and legal docs quote one source"
  - "A pure panel that aggregates nothing, so checkout and the kitchen display cannot disagree"

metrics:
  duration: ~2h
  completed: 2026-08-16
  tasks: 2
  tests-added: 49
---

# Phase 31 Plan 14: Checkout Allergen Set + Acknowledgement Summary

**A customer can no longer place an order without confirming they have read that order's allergen
information, the refusal announces itself to assistive technology and moves focus to the control
that refused, and the two accessibility failures axe cannot see are closed rather than excepted.**

---

## A11Y-08 — VERDICT FIRST (31-13 depends on this)

**FIXED. Not excepted, not partial.**

| Measurement | Value |
|---|---|
| Checkout inputs collecting the user's own data | **7** |
| Valid `autocomplete` tokens applied | **7** |
| `<input>` elements in the page SOURCE | **7** |
| `autoComplete` occurrences in the page SOURCE | **7** |
| `<input>` elements in the RENDERED DOM | **8** |
| Of which excluded (Radix's hidden acknowledgement checkbox) | **1** |
| `<textarea>` (`notes`) — correctly carries no token | **1** |

**31-13 must NOT list A11Y-08 as a named exception in the conformance statement.**

**The count is seven.** My plan said seven; 31-13's plan said eight. Seven is correct — the eighth
element is the `notes` `<textarea>`, which collects no information about the user and takes no
autofill token. Measured directly, twice, with two instruments.

The tokens, by field:

| Field | Token |
|---|---|
| `address1` | `address-line1` |
| `address2` | `address-line2` |
| `city` | `address-level2` |
| `postcode` | `postal-code` |
| `name` | `name` |
| `email` | `email` |
| `phone` | `tel` |

**A measurement the plan did not anticipate: the rendered DOM has EIGHT inputs, not seven.** Radix
Checkbox mounts a hidden native `<input type="checkbox">` (its `BubbleInput`) so the acknowledgement
participates in form semantics. A naive `querySelectorAll("input")` comparison therefore reads
7 !== 8 and fails on a CORRECT tree — the "expected-0 that is actually 1 on a correct tree" shape.
The fix was to filter by control type so the assertion stays an **equality**, not to loosen it to
`>=`; loosening is exactly the edit that would stop catching a missing token. A second test pins
`all=8, userData=7, excluded=1, excluded[0].type="checkbox"` so the filter itself cannot silently
start swallowing a real field.

**A11Y-07 is also closed on this surface**: `address1`, `city` and `postcode` carry
`aria-invalid` + `aria-describedby` pointing at an `id`-bearing message, focus moves to the first
invalid field, and the generic submit error is now a `role="alert"` region (it was a plain `<div>`).

---

## What shipped

**`OrderAllergenPanel`** (`frontend/components/storefront/order-allergen-panel.tsx`) — purely
presentational, aggregates nothing, and takes no customer/profile/restriction-mask prop at all, so
D-01 is structural rather than a convention someone must remember.

**The three states, and how each reads on screen** (the plan's central requirement):

| State | Prop | Heading | Body |
|---|---|---|---|
| Declared set | `["Milk", …]` | "Allergens in this order" | intro naming the vendor + one chip per allergen, **name in words** |
| Declared none | `[]` | "No allergens declared for this order" | "The kitchen has not declared any of the 14 regulated allergens for these items. That is not the same as allergen-free — if you have a serious allergy, tell the kitchen before you order." |
| **Not recorded** | `null` | "Allergen information not recorded for this order" | "We do not have the allergen information for these items. That is not the same as allergen-free — ask the kitchen before you order." |

The branch is written as three explicit predicates (`allergenNames === null`,
`!== null && length === 0`, `!== null && length > 0`) rather than a truthiness test, because `[]`
is truthy and `null` is not, so any `allergenNames?.length` shortcut silently merges the two states
the panel exists to keep apart. A dedicated test asserts the empty-state copy is **absent** in the
not-recorded state, which is the assertion that would catch that merge.

**Copy shipped verbatim** (recorded here so 31-07's `article-9-allergen-basis.md` assertion and this
component cannot drift):

- Heading — `Allergens in this order`
- Intro — `These items are prepared by {vendor}. Based on what the kitchen has declared, this order contains:`
- Flag — `Check — {Product}: the ingredients list mentions {allergen}, which the kitchen has not declared for this item. Ask the kitchen before you order.`
- Acknowledgement — `I have read the allergen information for this order.`
- Sub-line — `We do not store your allergies and we cannot check this order against them.`
- Empty heading — `No allergens declared for this order`
- Empty body — `The kitchen has not declared any of the 14 regulated allergens for these items. That is not the same as allergen-free — if you have a serious allergy, tell the kitchen before you order.`
- Error — `Confirm you have read the allergen information before placing this order.`

⚠ **The NOT RECORDED copy is AUTHORED, not contracted** — see Owner Questions.

**The gate**: the submit handler refuses before any network call; `role="alert"` announces the exact
error; `aria-invalid="true"` and `aria-describedby` are set on the checkbox; focus moves to it,
guarded by `isConnected` following `product-detail-modal.tsx:117`. **The button stays enabled** — a
disabled button on a touch device gives no feedback at all when pressed, and the refusal is itself
evidence the gate fired.

**Preserved** (Incremental Betterment): both post-order panels survive, still guarded on
`allergenWarnings.length > 0` (asserted as a count of exactly 2), with their boundary raised from
`border-amber-200` (1.25:1) to `border-amber-600` (3.19:1). Their silence today is expected and was
not treated as a regression signal. The CTA's copy, `bg-oxblood` fill and `py-3.5` padding are
untouched.

---

## Deviations from Plan

### 1. [Plan correction] The pre-submit panel's data comes from the BASKET, not the order DTO

- **Found during:** Task 2 planning, before any code.
- **Issue:** The plan says to mount the panel "reading the allergen fields from the order DTO 31-10
  added". **Those fields live on `OrderDetailDto`, which by construction describes an order that
  already exists.** At the pre-submit checkout there is no order — it is created by the very submit
  this gate guards. The instruction is not satisfiable as written.
- **Resolution:** 31-10's own SUMMARY resolves it explicitly (line 543): *"Note the panel is
  PRE-submit, so its data comes from the basket, not from an order that does not exist yet; these
  types are the shape to match."* The panel matches the DTO shapes and is fed from the basket.
- **How:** the checkout fetches `GET /public/shops/{slug}/products` (an endpoint it did not
  previously call), indexes by id, ORs the declared masks of the basket's lines, and decodes to
  names via `getAllergenNames` — whose table is held identical to the Java `AllergenCatalog` by
  `__tests__/allergen-table-parity.test.ts`. So the decode cannot drift from what the kitchen shows
  for the same integer. **The panel itself still aggregates nothing**; the plan's "do not aggregate
  in this component" constraint is honoured.
- **Resolution is all-or-nothing:** any line that cannot be resolved to a product with a declared
  mask yields `null` (NOT RECORDED) rather than a partial union. A partial union UNDER-states the
  set, and under-stating is the direction that injures someone.

### 2. [Rule 4 boundary — declared, not silently resolved] `allergenFlags` is passed `null`

- **Issue:** the advisory reconciliation flags are computed by `OrderAllergenAggregator` against
  `AllergenCatalog.SYNONYMS` — a **~150-term** map (measured) plus emphasis-span parsing.
  Re-implementing that in TypeScript would create a second, **ungated** copy of a safety heuristic.
  The existing parity test covers only the 14-name bit table, not the synonym list.
- **Decision:** the panel renders flags when given them (fully tested), and the checkout passes
  `null` — "not computed" — rather than `[]`, which would assert "nothing flagged", a claim this
  surface cannot substantiate. `frontend/components/ui/ingredient-text.tsx` parses `**markup**` but
  carries no allergen resolution, and there is no synonym list anywhere in `frontend/` (measured,
  with a positive control).
- **Consequence, stated plainly:** the checkout shows the DECLARED set (the legally operative
  statement, complete and correct) but **not** the advisory "Check —" lines that 31-15's kitchen
  ticket will show. Closing this needs a server-side pre-submit aggregate endpoint — a new public
  API surface, which is Rule 4 architectural work outside this plan's frontend-only
  `files_modified` and would collide with siblings on the OpenAPI snapshot. **Raised as a
  merge-gate item, not silently absorbed.**

### 3. [Rule 3 — blocking] `jest.setup.js` gains a ResizeObserver stub

- **Found during:** Task 2, first GREEN run.
- **Issue:** Radix Checkbox mounts its hidden `BubbleInput` when inside a `<form>`; that input sizes
  itself via `@radix-ui/react-use-size`, which constructs a `ResizeObserver`. jsdom implements none.
  The whole checkout page then failed to render, which surfaced as **32 failing tests** including
  the pre-existing suite — one missing browser API wearing the costume of a broad regression.
- **Fix:** a guarded, additive stub in `jest.setup.js`, beside the file's existing precedent block
  for pointer-capture/`scrollIntoView` ("stubbed here rather than per file"). `jest.setup.js` is not
  in `files_modified`; the alternative was a per-file polyfill that would leave the next consumer to
  rediscover it, and it would not have fixed the pre-existing suite.
- **Result:** 32 failures → 8.

### 4. [Expected consequence] One pre-existing test gains an acknowledgement step

- `checkout.test.tsx` "COD confirmation says 'Pay on delivery'" submitted without acknowledging, so
  the new gate correctly refused it. The submit contract genuinely changed; the test now ticks the
  box first. **What it asserts (the COD wording) is unchanged** — 15 tests, still 15, still passing.

### 5. [Self-correction found by a break arm] Assertion ORDER in the refusal test

- The first version asserted the alert before the absent network call. In the break direction it
  failed on "Unable to find role=alert" — a true failure about the **wrong thing**: the run never
  reached "was an order created?", which is the question that matters. Hoisting
  `expect(mockedPost).not.toHaveBeenCalled()` above it makes the break arm report
  **"Received number of calls: 1"** with the full order payload. Committed separately (`696b71c8`).

---

## Falsification — both directions, all arms

**Protocol:** committed before every arm; each restore verified **by content**
(`git hash-object` vs `git rev-parse HEAD:<path>`), never `git diff --stat`; clean arm re-run LAST.
Baseline blobs: panel `cfeb876e`, checkout page `c47174a4`.

| # | Arm | Break direction — real output | Clean direction |
|---|---|---|---|
| 1 | Remove `role="alert"` (panel) | `2 failed, 23 passed` — `TestingLibraryElementError: Unable to find an accessible element with the role "alert"` | 25/25 |
| 2 | Suppress the empty-state body | `1 failed, 24 passed` — `Unable to find an element with the text: The kitchen has not declared any of the 14 regulated allergens…` | 25/25 |
| 3 | **D-01**: render a profile-derived value | `1 failed, 24 passed` — `Expected substring: not "Peanuts"` / `Received string: "…I have read the allergen information for this order.Your allergen profile lists: PeanutsWe do not store your allergies…"` | 25/25 |
| 4 | Pre-check the acknowledgement | `2 failed, 23 passed` — `Received element is checked` | 25/25 |
| 5 | **Unwire the gate (F5)** | `expect(jest.fn()).not.toHaveBeenCalled()` / `Expected number of calls: 0` / `Received number of calls: 1` / `1: "/public/shops/jollof-express/orders", {…"items": [{"productId": "p1", "quantity": 1}]}` — **an order was created**; DOM showed the COD confirmation screen | 14/14 |
| 6 | Remove `role="alert"` (checkout path) | `6 failed, 8 passed` | 14/14 |
| 7 | Remove ONE autocomplete token | DOM: `Expected: 7 / Received: 6`; source gate: `inputs=7 tokens=6 → A11Y-08 NOT closed`; also `Expected "address-line2" / Received null` | `7 == 7` |
| 8 | Invent a token (`postcode`) | `LINT_RC=1` — `820:15 error the autocomplete attribute is incorrectly formatted jsx-a11y/autocomplete-valid` | `LINT_RC=0` |
| 9 | Disable the button on the ack state | `8 failed, 6 passed` — both the behavioural "stays ENABLED" test and the structural all-`disabled`-expressions test fired | 14/14 |
| 10 | Delete one preserved post-order panel | test `Expected: 2 / Received: 1`; source gate `BAD (1) a preserved post-order panel was dropped` | `(2) both intact` |

**Closing clean arm (run last, after every restore):**
`Test Suites: 109 passed, 109 total / Tests: 1057 passed, 1057 total`; `TASK1 VERIFY: PASS`;
`TASK2 VERIFY: PASS`; `LINT_RC=0` (28 warnings, 0 errors — exactly the pre-change baseline);
`BUILD_RC=0`. Working-tree hashes equal the committed blobs for both source files.

### Two instrument defects found while doing this — both would have produced a false PASS

**(a) `rg` does not exist inside a script subprocess, and the plan's verify limb reported
A11Y-08 CLOSED on the strength of it.** `rg` is a shell function with no system binary behind it.
Run inside `bash script.sh` it died `rg: command not found`, so `inputs=0` and `tokens=0`, and the
equality limb printed:

```
inputs=0 tokens=0
OK   tokens == inputs (0 == 0) — A11Y-08 CLOSED
```

**The plan's `>= 7` floor is the only limb that caught it** — which is precisely why the plan added
it, and it earned its place. Re-measured with `grep -c` (which does resolve) and independently with
`rg` in the interactive shell: both give **7 and 7**.

**(b) A comment satisfies the `role="alert"` grep.** With `role="alert"` deleted from the JSX, the
plan's fixed-string grep still returned **1** — matching the doc comment on
`ALLERGEN_ACK_ERROR_COPY` — and `TASK1 VERIFY` reported **PASS** while the behavioural tests were
RED. This is this phase's **eighth** comment-satisfies-grep instance. The greps are a spelling
check on the copy; **the behavioural tests are the gate.** Recorded so 31-18 does not read a green
grep as proof the attribute is live.

---

## Tests

| Suite | Tests |
|---|---|
| `components/storefront/__tests__/order-allergen-panel.a11y.test.tsx` (new) | **25** |
| `app/shop/[slug]/checkout/__tests__/allergen-acknowledgement.test.tsx` (new) | **14** |
| `app/shop/[slug]/checkout/__tests__/checkout-form-a11y.test.tsx` (new) | **10** |
| `app/shop/[slug]/checkout/__tests__/checkout.test.tsx` (modified) | 15 (unchanged count) |
| **Added** | **49** |

Full frontend suite **109 suites / 1057 tests / 0 failures** (baseline 106 / 1008 → +3 / +49).
`npm run build` rc=0. `npm run lint` rc=0, 0 errors, 28 warnings (baseline exactly).

Three jest-axe scans (populated, empty, errored), each preceded **in the same test** by its own
non-vacuity control. The autocomplete assertions are deliberately DOM/source counts, not axe scans,
with the reason written into the file — axe is blind to SC 1.3.5, so folding them into an axe run
would silently un-fix A11Y-08 while staying green.

`docs/metrics.json` regenerated via `scripts/docs-freshness.sh --write`
(`jest_blocks` 1008→1057, `jest_files` 106→109, `total_logical_invocations` 3000). **Prose counts in
`README.md` / `CLAUDE.md` / `AGENTS.md` were NOT touched** — a worktree cannot see its siblings'
tests, so the orchestrator reconciles once on the merged tree.

---

## Owner Questions (returned unanswered)

**1. The NOT RECORDED copy is authored, not contracted — please ratify.**

The UI-SPEC gives verbatim copy for the declared and declared-none states but **none for the third
state**, which 31-10's wire contract makes reachable. Shipped, in the register of the contracted
strings, flagged in the source and here:

> **Allergen information not recorded for this order**
> We do not have the allergen information for these items. That is not the same as allergen-free —
> ask the kitchen before you order.

It carries no allergen-free claim and no implication the platform knows the customer's allergies. It
is not an acknowledgement the customer is bound by, so I judged it safe to author rather than block
on — but the wording is the owner's to confirm.

**2. May an order whose allergen picture is NOT RECORDED be sold at all?**

Today it can: the panel says the information is missing and the customer may still acknowledge and
proceed. The alternative — refusing the sale — is a commercial and legal decision, not an
engineering one, so it is **not** implemented. Pre-submit this state arises when the products
endpoint fails or a basket line cannot be resolved.

---

## Merge-gate items

1. **31-13 must not list A11Y-08 as an exception.** Fixed, 7/7. Stated at the top of this file.
2. **The checkout does not show the advisory reconciliation flags** (deviation 2). The kitchen ticket
   (31-15) will. If the owner wants parity, that needs a server-side pre-submit aggregate endpoint —
   a scoped follow-up, deliberately not smuggled into this plan.
3. **`frontend/jest.setup.js` was modified** (ResizeObserver stub) — additive and guarded, but a
   shared file; check for a sibling conflict at merge.
4. **`docs/metrics.json`** will conflict with siblings by construction; regenerate once on the
   merged tree.
5. **31-18**: the `role="alert"` grep is satisfiable by a comment. Do not read it as proof.
6. **My worktree was branched from the WRONG base** — `bb2ae65d` (Phase 28), not `0d1834c2`, with no
   Phase 31 planning directory at all. Corrected at startup with `git merge --ff-only 0d1834c2`
   after verifying `0d1834c2..HEAD` was empty, HEAD was a strict ancestor, and the tree was clean —
   a pure fast-forward that destroyed nothing. **Worth checking whether any sibling was spawned the
   same way**, since a sibling that did not notice would silently produce work against Phase 28.

---

## Cross-cutting quality dimensions

- **Web performance:** one added `GET /public/shops/{slug}/products` on checkout mount, parallel to
  the existing shop fetch, failing soft. No image, font or bundle growth beyond the panel; no new
  dependency. Panel markup is static with no animation.
- **SEO:** **N/A** — `/shop/[slug]/checkout` is a transactional surface, not a discoverable one.
- **AI agent-readiness:** **N/A** — no API surface added or changed. The acknowledgement is a client
  gate; it is deliberately NOT sent to the server (see Known Stubs).
- **Security:** T-31-14-01 (profile disclosure) is structural — no prop exists to pass one — and the
  break arm proving the assertion can fail was run. T-31-14-02 asserted by the absence of the
  network call. T-31-14-03/04/06/07/08 all covered above. No new dependency (T-31-14-SC).
- **Falsifiable evidence:** ten arms, both directions recorded verbatim, plus two instrument defects
  that each produced a false PASS and were caught by a control.
- **Runtime parity:** **not claimed, and deliberately so.** This is a worktree with no rebuilt stack;
  the delivered-runtime half belongs to the phase owner after merge.

---

## Known Stubs

**The acknowledgement is not persisted.** It gates the client submit and is not sent to the server,
so there is no server-side record that the customer acknowledged, and nothing stops a direct API
call to `POST /public/shops/{slug}/orders` from bypassing it. The plan scopes this surface to the
frontend and specifies no request-field change; V63 records what the customer *was shown*, which is
the immutability guarantee. **If the owner wants the acknowledgement itself to be evidence, that is
a server change and a follow-up.** Recorded rather than quietly shipped, because "the gate exists"
and "the gate is enforceable" are different claims.

No other stubs: every panel state is reachable and tested, and no placeholder data feeds any UI.

## Threat Flags

None. No network endpoint, auth path, file access or schema change was introduced. The one new
client call is a GET to an existing public endpoint already used by `/shop/[slug]`.

## User Setup Required

None.

---

## Self-Check: PASSED

Run after writing this summary. Real output:

```
=== files claimed created/modified ===
FOUND:   frontend/components/storefront/order-allergen-panel.tsx
FOUND:   frontend/components/storefront/__tests__/order-allergen-panel.a11y.test.tsx
FOUND:   frontend/app/shop/[slug]/checkout/__tests__/allergen-acknowledgement.test.tsx
FOUND:   frontend/app/shop/[slug]/checkout/__tests__/checkout-form-a11y.test.tsx
FOUND:   frontend/app/shop/[slug]/checkout/page.tsx
FOUND:   frontend/app/shop/[slug]/checkout/__tests__/checkout.test.tsx
FOUND:   frontend/jest.setup.js
FOUND:   docs/metrics.json
FOUND:   .planning/phases/31-consumer-safety-and-legal-floor/31-14-SUMMARY.md
=== commits claimed ===
FOUND:   7e2654d9
FOUND:   87ff4ace
FOUND:   696b71c8
=== files NOT to have been touched (must be unchanged vs base 0d1834c2) ===
UNTOUCHED: frontend/types/api.ts
UNTOUCHED: frontend/app/globals.css
UNTOUCHED: README.md
UNTOUCHED: CLAUDE.md
UNTOUCHED: AGENTS.md
=== kitchen display (sibling 31-15's file) untouched ===
UNTOUCHED: frontend/app/dashboard/kitchen
=== verdict ===
SELF-CHECK: PASSED
```

**Scope discipline confirmed.** The complete changed-file set vs base `0d1834c2` is exactly eight
files: the five in `files_modified`, plus `checkout.test.tsx` (deviation 4), `jest.setup.js`
(deviation 3) and the generated `docs/metrics.json`. `frontend/types/api.ts` was NOT edited;
`frontend/app/globals.css` and the kitchen display (sibling 31-15) were NOT touched.

**STATE.md / ROADMAP.md deliberately NOT updated** — six wave-3 executors are running concurrently
in separate worktrees, so six edits to those two files would conflict by construction. The
orchestrator reconciles them once on the merged tree.
