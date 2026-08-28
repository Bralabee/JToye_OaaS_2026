---
quick_id: 260828-h0i
slug: e2e-storefront-nightly-six
date: 2026-08-28
issue: 666
branch: fix/e2e-storefront-nightly-six
---

# Fix the six failing nightly E2E tests (#666)

## What is broken

`e2e-nightly.yml` has failed every night since 2026-08-18. Until V64 (#661, merged
2026-08-25) the stack never came up, so Playwright never ran. It runs now: the last
**three** nights (08-26, 08-27, 08-28) all reach step 13 and fail only at step 14, the
verdict step. Tonight's run `33142364550`: `total=266 passed=253 failed=6 skipped=7`.

The 6 are **3 tests x 2 projects** (desktop + mobile), all in
`frontend/e2e/storefront-flows.spec.ts`. **All three are instrument defects. The product
is correct in every case.**

## Root causes, measured from the run-33142364550 artifact

| # | Site | Cause |
|---|---|---|
| 1 | `:133` | `getByRole("link", {name:"Browse"})` resolved to 2: `"Browse shops"` (footer) and `"Cookie and **brows**er-storage policy"` (footer, Phase 31). Also the nav link is now **"Shops"** — the code comment describes a page that no longer exists, and on **mobile the nav has no shops link at all** (hamburger), so a nav-scoped fix would fail on one project. |
| 2 | `:88` (helper), `:578` | `text=Your basket` resolved to 2: the `h1` and the Phase 31 cookie notice's *"remembering what is in **your basket**, and keeping your order secure"*. `text=` is substring + case-insensitive. |
| 3 | `placeOrder():105`, `:615` | The Phase 31 **LGL-03 allergen gate** (`app/shop/[slug]/checkout/page.tsx:417`, `if (!acknowledged) { setAckError(true); return }`) refuses the submit **before any network call**. The spec has **zero** occurrences of "allergen". Submit is swallowed, no order row is created, failure surfaces 15s later as a missing `"Order confirmed!"` heading. |

Cause 3 is why the 08-25 local run found no `email-<ts>@test.com` order in the database.
It is not a broken checkout — LGL-03 is doing exactly its job.

**`:541` carries TWO defects stacked** (cause 2 at `:578`, then cause 3 at `:615`). Fixing
only the locator leaves it red for a second reason. That is the trap in this task.

## Fixes

1. **`:133`** — replace the chrome-link assertion with one about the card the test is named
   for: the card links to its own storefront (`a[href="/shop/mama-ades-kitchen"]`).
   Viewport-stable, unambiguous, and faithful to "shop card renders with real data".
2. **`:88`, `:578`** — `getByRole("heading", {name:"Your basket"})`. This is the
   disambiguation Playwright itself printed for element 1. Leave `:629`
   (`text=Your basket is empty`) alone — it does not collide and it passed.
3. **`placeOrder()` and `:541`** — tick the acknowledgement before Place order:
   `getByRole("checkbox", {name: /I have read the allergen information for this order\./i})`,
   click, assert `toBeChecked()`. The gate is present in **all three** panel states
   (per `order-allergen-panel.a11y.test.tsx:179`), so this is unconditional.
   Radix renders `button[role=checkbox]`; its `BubbleInput` sibling is `aria-hidden`, so the
   role locator matches exactly one node. Use `.click()`, not `.check()`.
   Copy pinned as a **literal**, not imported — no spec under `e2e/` imports app source, and
   a literal fails loudly if user-visible copy changes.

## Proof obligations (Proof Standard 1)

Each fix must be shown to FAIL before it is trusted. Bracket: **clean -> arms -> clean**.
- Commit before running arms, so the restore target is a committed state.
- Verify restores **by content** (`git hash-object`), never `git diff --stat`.
- The closing clean arm is the only proof the restores happened.

## Out of scope

Skip budget: CI reports `skipped=7` against a budget of 8. The 65 seen locally on 08-25 was
the both-projects-unfiltered artefact. Nothing to do.
