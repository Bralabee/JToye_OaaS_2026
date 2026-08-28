# Phase 34 — deferred items

Out-of-scope discoveries made while executing. Each entry says what was measured,
why it was not fixed here, and what would close it.

---

## D-34-05-01 — the same unfalsifiable overflow shape survives in two other places

**Found during:** plan 34-05, Task 1 break arm A (2026-08-28).

**What was measured.** Under `isMobile: true` Chromium emulates a phone LAYOUT
viewport: content wider than the device width makes the page zoom out to fit, and
`window.innerWidth` GROWS to match the content. So

```
expect(docScrollWidth).toBeLessThanOrEqual(window.innerWidth + 1)
```

compares a number against itself and **cannot go red**. Proven on the live Compose
stack by appending a deliberate 1200px-wide div to `/dashboard/kitchen` at a 375px
pin, before the read:

```
{"docScrollWidth":1200,"bodyScrollWidth":1200,"innerWidth":1200,
 "htmlOverflowX":"visible","bodyOverflowX":"visible","injectedWidth":1200}
```

The sweep reported `1 passed`, rc=0, over an 825px overflow on a 375px phone.

**Fixed in 34-05:** only the new eleven-route block, which now compares against
`page.viewportSize()!.width` (the configured width, which page content cannot move)
and additionally asserts the layout viewport did not widen. Same injection now
reads `expected: 376 / received: 1200`, rc=1.

**Still carrying the vacuous shape — NOT fixed here:**

1. `frontend/e2e/dashboard-mobile.spec.ts`, the single-route MOBL-01 375px block:
   `expect(geom.docScrollWidth).toBeLessThanOrEqual(geom.viewportWidth + 1)` where
   `viewportWidth` is `window.innerWidth`. Not touched because plan 34-05 says in
   terms: do not change the 390px block or any existing assertion. Its OTHER
   assertions (the 56px top-bar geometry, the escaping-element list) are genuinely
   falsifiable and were measured red on main — only this one line is affected.
2. `frontend/e2e/public-layout.spec.ts:110-113`, `horizontalOverflow()` =
   `document.documentElement.scrollWidth - window.innerWidth`, asserted
   `<= 1` at three sites (`:225`, `:248`, `:380`). The same mechanism applies
   **only under the mobile project** (`isMobile: true`); under the desktop project
   `innerWidth` is a fixed 1440 and the assertion is live. NOT verified in the fail
   direction — that is the work, and it should not be assumed either way.

**What would close it:** run the 1200px-div break arm against each site above,
record both directions, and switch the yardstick to `page.viewportSize()` wherever
the arm shows green. Small, mechanical, and outside 34-05's `files_modified`.

**Why it was not done here:** `frontend/e2e/public-layout.spec.ts` is not in this
plan's files, and the MOBL-01 line is explicitly excluded by the plan's action text.
Both are recorded in `dashboard-mobile.spec.ts`'s own docblock so the next reader of
that file cannot miss it.
