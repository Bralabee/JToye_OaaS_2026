---
quick_id: 260828-h0i
slug: e2e-storefront-nightly-six
date: 2026-08-28
status: complete
issue: 666
branch: fix/e2e-storefront-nightly-six
files_modified:
  - frontend/e2e/storefront-flows.spec.ts
---

# Summary — the six failing nightly E2E tests (#666)

## Outcome

Fixed. Three instrument defects in `frontend/e2e/storefront-flows.spec.ts`, three tests x
two projects. **The product was correct in all three cases.**

Measured on the live compose stack, `check-runtime-freshness` PASS 4/4 (0 unverified):

| | result |
|---|---|
| before | **6 failed** — same three tests, same three errors as nightly run `33142364550` |
| after | **6 passed**; full spec **44/44** |
| content proof | **4 real orders** reached the database, each line carrying its V63 `allergen_mask` snapshot |

## The finding that changed the diagnosis

The 2026-08-25 handoff recorded the `:770` email test as **"NOT YET ATTRIBUTED, and it is
the more serious"**, because no order row was ever created — which reads as a broken
checkout. It is not. The CI page snapshot shows the Phase 31 **LGL-03 allergen gate**
holding the submit:

```
- checkbox "I have read the allergen information for this order." [invalid]
- alert: Confirm you have read the allergen information before placing this order.
```

`app/shop/[slug]/checkout/page.tsx` refuses the submit **before any network call** and
leaves the button deliberately enabled, so the click is silently swallowed. The spec had
**zero** occurrences of "allergen". The gate is doing exactly its job.

**Consequence worth keeping:** `placeOrder()` has exactly one caller, so from Phase 31
merging (2026-08-17) until now **no E2E covered a successful order placement at all.**
That is the coverage this fix restores, and the four database rows are the proof.

## Falsification — bracket clean -> arms -> clean

Committed first (`6b1df8a1`), so every restore target was a committed state. Restores
verified **by content** (`git hash-object` == `3967caa9…`), never by `diff --stat`.

| arm | reverted | result |
|---|---|---|
| 1 | card-link fix | exactly 2 fail (`:142` x2), `strict mode violation … resolved to 2 elements` |
| 2 | heading locators | exactly 2 fail (`:566` x2), same violation on `text=Your basket` |
| 3 | allergen tick | exactly 4 fail (`:566`, `:805` x2), `"Order confirmed!"` not found — the original CI symptom |
| closing | nothing | **6/6 pass**, tree clean, 0 -> 4 orders created during the arm |

**Arm 2 is only half load-bearing, and that is recorded rather than glossed.** It proves
the fix at line 603. At line 88 (inside `placeOrder`) the ambiguous locator still *passed*
— `:805` was green under that arm. The cookie notice mounts post-hydration behind a 200ms
fade, so line 88 is a **latent race** that passes today on timing alone, not a currently
firing collision. Fixed as a hazard, not claimed as proven.

## Full-suite verification

Whole suite, both projects, against the live stack — not the three tests in isolation:

```
266 total · 258 passed · 8 skipped · 0 failed  (8.7m, rc=0)
```

`check-e2e-skip-budget` **PASS, rc=0**: "all 8 skip(s) are declared and within the budget
of 8". It had been VOID (no report describing this spec set), then FAIL, then PASS — each a
real answer at a different stage, and the VOID was never treated as one.

Gate sweep on this branch: `docs-freshness`, `check-doc-metrics`, `check-claims`,
`check-gate-enforcement`, `check-branch-behind-base`, `check-runtime-freshness` (4/4 FRESH,
0 unverified) and `check-e2e-skip-budget` — **all rc=0**.

## Two things measured that were NOT this task

1. **`docker-compose.full-stack.yml` maps core-java as the RANGE `"9090-9091:9090"`**
   (for replica scaling). Docker allocated **9091** on this machine, while the frontend
   bundle bakes `NEXT_PUBLIC_API_URL=http://localhost:9090` at build time. Every
   browser-side API call then fails with no listener behind it, and the storefront reports
   *"Failed to place order."* — which reads exactly like a broken checkout. This made **six
   further tests** fail locally (`:136` search and five postcode tests), all of which pass
   in CI. `docker compose up -d --force-recreate --no-deps core-java` moved it to 9090 and
   all six went green **with no code change**, which is what identifies the port as the
   cause rather than the product. Server-side rendering is unaffected (it uses the
   in-network `core-java:9090`), so pages LOAD correctly and only browser-side calls fail —
   which is why it presents so convincingly as product bugs. Worth an issue.

2. **The skip budget is NOT drifting — measured, not inferred.** The handoff left open
   whether the **65** skips seen locally on 2026-08-25 were real drift. They were not: a
   correctly-ported stack with freshly-seeded fixtures reports **8**, all declared, and the
   gate passes. Nightly `33142364550` independently reports **7**. Nothing to raise.

   **The two-skip delta is itself a finding.** The first full run reported **10** skipped and
   the gate FAILED naming an undeclared skip,
   `vendor-refund-flow.spec.ts › Issue refund button is hidden on a DRAFT order`. Cause:
   every suite run PLACES ORDERS, and that test reads only page 1 (top 20 by `created_at`).
   After repeated runs **22 orders were newer than `ORD-E2E-DRAFT-FIXTURE`**, pushing it to
   position 23 — off the page. Re-seeding restored it and the count went 10 -> 8.
   **The suite displaces its own fixture on a non-fresh volume.** CI never sees this because
   the nightly runs `down -v` and seeds once. Locally, re-seed before trusting a skip count.

## Instrument note

`rg -uu -c 'page.locator("text=Your basket")'` returned **empty** while the string was
present twice — `(` and `)` are regex metacharacters, so the pattern could not match. The
count was a pattern-shape false negative, not evidence. `rg -uu -F` gave 2. What actually
proved the arm had applied was its own failure output.
