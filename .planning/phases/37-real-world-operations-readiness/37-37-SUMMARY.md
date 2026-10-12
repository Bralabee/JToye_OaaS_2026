---
phase: 37-real-world-operations-readiness
plan: 37
subsystem: storefront-checkout
tags: [checkout, order-confirmation, cash-on-delivery, c3, uxt-029, rwo-029, a11y, focus-management, live-region, storage-policy, pgc-840, tdd, frontend, tracking-response]

requires:
  - phase: 37-34
    provides: "the checkout submit with expectedUnitPricePennies/expectedTotalPennies, the basket-changed 409 panel and the owner-preserving basket rewrite (all preserved; the 37-34 suites are green as regression)"
  - phase: 37-14
    provides: "the B7 VAT rule (a VAT row only for a seller with a declared vatNumber, otherwise the one-line note under Total)"
  - phase: 31.1-09
    provides: "the recorded allergen set on the tracking response, rendered by RecordedAllergenSet"
provides:
  - "A cash order routes to /shop/{slug}/orders/{orderNumber} after saveLocalOrder (unchanged), exactly like a card order; the in-place codConfirmation render is retired"
  - "lib/just-placed.ts: the one-shot sessionStorage marker jtoye-just-placed:{orderNumber} (order number only), written by the checkout and consumed once by the order page"
  - "Order page C3 states: first view h1 'Order confirmed' (tabIndex -1, focused), one role=status region 'Order confirmed, {n}', title 'Order {n} confirmed · {Shop} — J'Toye'; later views h1 'Order {n}' and 'Order {n} · {Shop} — J'Toye'; the C3 error state 'Order {n} placed' with Retry; the old status words kept as a line under the h1"
  - "The carried-over confirmation content on the order page: 'Pay on delivery/collection' for an unpaid cash order, the breakdown (subtotal, delivery or Free, VAT row per B7 or the B7 note, total), RecordedAllergenSet, SellerBlock, Back to shop"
  - "PublicOrderStatus.fulfilmentType and deliveryFeePennies (tracking and history readers; OpenAPI snapshot regenerated)"
  - "components/storefront/no-vat-registration-note.tsx: the B7 note, shared by checkout and the order page"
  - "Storage policy: jtoye-just-placed:<order number> listed in the session-storage table; ClientStorageKey.suffixLabel so a prefix key not keyed by shop names its real suffix"
  - "e2e/cash-confirmation.ts (awaitCashConfirmation, confirmedHeading): the one definition every cash-order walk waits on"
  - "public-a11y.spec.ts: the cash-confirmation scan on the order route, plus the C3 arm (focus, status text, title, F16 zero re-posts on reload)"
affects: [37-38, 37-51, 37-52, storefront-checkout, order-tracking, storage-policy, accessibility-statement, e2e-ordering-specs]

actuals:
  tokens: 38523
  tasks: 2
  commits: 7
plan_head_before: 70e528f023e075c7dc446549cdb87f32be4d6b9e
plan_head_after: f337abffafecf68bcd10cdd3408eaebb07406c6b

tech-stack:
  added: []
  patterns:
    - "A one-shot client hint (sessionStorage, consumed on first read, ref-guarded against StrictMode effect replay) distinguishes the first view of a route from every later one; the route still reads all data from the server"
    - "A live region is rendered from the first paint (also in the loading branch) and written once, so its text change is announced"
    - "A cross-route move of a confirmation carries every item it showed, each rendered only from a value the server returns; a missing value is left out, never guessed"

key-files:
  created:
    - frontend/lib/just-placed.ts
    - frontend/components/storefront/no-vat-registration-note.tsx
    - frontend/e2e/cash-confirmation.ts
    - frontend/app/shop/[slug]/orders/[orderNumber]/__tests__/order-confirmation.test.tsx
    - .planning/phases/37-real-world-operations-readiness/evidence/37-37-tdd/red-task1-record.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-37-tdd/red-task1-java-record.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-37-tdd/red-task2-record.json
  modified:
    - frontend/app/shop/[slug]/checkout/page.tsx
    - frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx
    - frontend/lib/client-storage-keys.ts
    - frontend/lib/accessibility-statement.ts
    - frontend/e2e/public-a11y.spec.ts
    - frontend/app/legal/__tests__/cookies-page.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/checkout.test.tsx
    - core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicOrderStatus.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md
    - (dependent tests and e2e listed under Deviations)

key-decisions:
  - "37-37: the tracking response gains fulfilmentType and deliveryFeePennies (additive, order columns, no address): the moved confirmation must say 'Pay on delivery/collection' and show the delivery row, and neither could be derived honestly (a free delivery and a collection both have fee 0)"
  - "37-37: the C3 h1 is ONE element whose text moves between 'Order confirmed', 'Order {n} placed' and 'Order {n}', so focus survives a Retry; the old status words ('Order in Progress' etc.) stay as a line under it"
  - "37-37: the marker is consumed in a mount effect behind a ref, not in a useState initialiser, so StrictMode's double invocation cannot consume it twice or lose it"
  - "37-37: 'Pay on …' renders only for paymentStatus NONE, a known fulfilment type and a non-terminal status (no instruction on a paid, cancelled, refunded or completed order)"
  - "37-37: the cash allergenWarnings panel is not carried over: the list is computed at creation, never stored, and empty by construction since 2026-07-30, so the tracking response cannot carry it and nothing a customer saw is lost; the seam stays on GuestOrderConfirmation and the card step"

patterns-established:
  - "A spec that waits for a confirmation waits on the ROUTE and its heading (awaitCashConfirmation); a negative check asserts the page did not leave /checkout, never the absence of a heading the page can no longer render"

requirements-completed: []
requirements-addressed: [RWO-029]

coverage:
  - id: D1
    description: "A cash 201 calls saveLocalOrder, writes jtoye-just-placed:{n} and router.push()es to /shop/{slug}/orders/{n}; no in-place confirmation, no 'Nothing to checkout' flash"
    requirement: "RWO-029"
    verification:
      - kind: unit
        ref: "checkout.test.tsx routing arm (RED on 533ddbc5: push never called); break arm C (router.push removed) -> 1 of 40 failed; browser RED on a build of the pre-plan pages: toHaveURL /orders/ORD-… received /shop/test-kitchen/checkout"
        status: pass
    human_judgment: false
  - id: D2
    description: "First view: h1 'Order confirmed' focused, one role=status 'Order confirmed, {n}', confirmed title, marker removed; later view: 'Order {n}' and the plain title"
    requirement: "RWO-029"
    verification:
      - kind: unit
        ref: "order-confirmation.test.tsx (RED_EVIDENCE_OK, evidence/37-37-tdd/red-task1-record.json); break arm D (marker never removed) -> 3 failed"
        status: pass
      - kind: e2e
        ref: "public-a11y.spec.ts C3 arm, stack-free local build (CI env): passed mobile + desktop; RED on the pre-plan build (stays on /checkout)"
        status: pass
    human_judgment: false
  - id: D3
    description: "F16 / T-37-88: no POST on load or refresh of the confirmation route"
    requirement: "RWO-029"
    verification:
      - kind: unit
        ref: "order-confirmation.test.tsx F16 (publicApiClient.post mock + bare fetch spy); arm A2 (a mount effect that POSTs through the client) -> F16 red, 2 calls; arm B (bare fetch POST) -> F16 red, 2 calls"
        status: pass
      - kind: e2e
        ref: "public-a11y.spec.ts C3 arm: the recorder saw the placing POST (1), reload -> still 1"
        status: pass
    human_judgment: false
  - id: D4
    description: "Carried over: Pay on delivery/collection, subtotal, delivery or Free, VAT row per the vatNumber rule or the B7 note, total, recorded allergens, seller block, Back to shop"
    requirement: "RWO-029"
    verification:
      - kind: unit
        ref: "order-confirmation.test.tsx carried-over arms; break arm E (VAT row without the seller gate) -> 2 failed; PublicStorefrontServiceTest (RED_EVIDENCE_OK on the tracking fields, history-reader arm red with its setters removed)"
        status: pass
    human_judgment: false
  - id: D5
    description: "C3 loading (existing spinner) and C3 error on the first view ('Order {n} placed', focused, announced, the retry copy, Retry is a GET); no marker keeps the existing error branch"
    requirement: "RWO-029"
    verification:
      - kind: unit
        ref: "order-confirmation.test.tsx loading/error arms (red on 533ddbc5)"
        status: pass
    human_judgment: false
  - id: D6
    description: "The storage policy lists jtoye-just-placed:<order number> (session, order number only, removed on first view or tab close) and its test binds it"
    requirement: "RWO-029"
    verification:
      - kind: unit
        ref: "cookies-page.test.tsx (RED_EVIDENCE_OK: the row read <shop>, evidence/37-37-tdd/red-task2-record.json); key-removed arm -> 5 of 88 failed (4 policy + the source scan)"
        status: pass
    human_judgment: false
  - id: D7
    description: "Backstop: the carried-over breakdown rows fit at 360px with root 130%"
    requirement: "RWO-029"
    verification:
      - kind: e2e
        ref: "temporary measurement on the local build: Subtotal/Delivery/Total rows scrollWidth 275 = clientWidth 275 with and without the marker; the DOCUMENT overflows by 4px from the shared storefront header (pre-existing, 37-52's F6 scope, deferred-items.md)"
        status: pass
    human_judgment: false

duration: 33 min (16:17Z-16:50Z)
completed: 2026-10-12
status: complete
---

# Phase 37 Plan 37: The cash-order confirmation is its own announced, refresh-safe route Summary

**A cash order now leaves the checkout for `/shop/{slug}/orders/{orderNumber}`, the route a card order already used. The first view there says "Order confirmed", moves focus to that heading, announces "Order confirmed, ORD-…" through one status region and puts the order in the page title. A refresh or a later visit shows "Order ORD-…", and no view of that page sends a POST. Everything the old in-place confirmation showed is still there: "Pay on delivery" or "Pay on collection", the full breakdown under the 37-14 VAT rule, the recorded allergens, the seller and "Back to shop". To make that honest, the tracking response now also says how the order is fulfilled and what delivery cost. The storage policy lists the new one-shot marker under its real name, and the a11y gate scans the confirmation on its route and checks focus, the announcement and the refresh in a browser.**

## Performance

- **Duration:** 33 min (16:17Z to 16:50Z)
- **Tasks:** 2 of 2
- **Files changed:** 42 (1209 insertions, 329 deletions), 7 commits

## Accomplishments

- Checkout (`app/shop/[slug]/checkout/page.tsx`): on a cash 201 it stores the email, calls `saveLocalOrder` (unchanged), writes the marker, clears the basket and `router.push()`es to the order route. A short "Opening your order ORD-…" state covers the moment between the 201 and the navigation, so the emptied basket never shows as "Nothing to checkout". Nothing 37-34 added was touched: the expected prices, the basket-changed 409 panel, the reuse of the same key and the owner-preserving rewrite. All 37-34 suites are green.
- Order page (`app/shop/[slug]/orders/[orderNumber]/page.tsx`): consumes the marker once, focuses the h1, writes one `role="status"` region (present from the first paint, including the loading branch), sets the C3 titles and renders the carried-over content. It makes two GETs (tracking bound to number and email, and the public shop for the seller and the B7 rule) and never a POST.
- C3 error (planner assumption, implemented as written): if the first view's tracking read fails, the page shows "Order ORD-… placed" (focused), announces "Order placed, ORD-…" and shows "We couldn't load the latest details. Your order was placed." with Retry. Retry is a GET and puts focus back on the heading. Without the marker, the existing error branch is unchanged.
- Backend: `PublicOrderStatus.fulfilmentType` and `deliveryFeePennies` are set by both readers. The OpenAPI snapshot is regenerated, and `OpenApiSnapshotTest` is green on it and red on the old one.
- Storage policy: the registry entry `jtoye-just-placed:` (session storage, not personal, not cleared on sign-out, removed on first view). `suffixLabel` makes the policy print `<order number>` for this key instead of `<shop>`.
- Accessibility statement: the cash confirmation entry now names the order route, and `SCOPE_COVERAGE` in the gate follows it.
- Ran stack-free on a local production build with the CI env (`next start -p 3300`, not the shared stack): `public-a11y.spec.ts` passed 42 of 42 on mobile and desktop, including both INSTRUMENT arms.

## Task Commits

1. **Task 1 RED:** `533ddbc5` test(37-37): the cash confirmation is its own announced, refresh-safe route
2. **Task 1 backend (Rule 2, RED then GREEN):** `8b31e4ae` feat(37-37): the tracking response says how the order is fulfilled and what delivery cost
3. **Task 1 GREEN:** `44eaa92f` feat(37-37): a cash order is confirmed on its own route, announced and refresh-safe
4. **Task 2 RED:** `53451d8f` test(37-37): the storage policy lists the just-placed marker by its real name
5. **Task 2 GREEN:** `7b8a7d00` feat(37-37): the storage policy and the a11y gate know the confirmation route
6. **Fix:** `90b9c8d7` fix(37-37): the checkout's routing spinner passes the contrast contract
7. **Docs:** `f337abff` docs(37-37): the test counts follow the tree after 37-37

`commits: 7` is `git rev-list --count 70e528f0..f337abff`, measured from the persisted ledger `.git/gsd-plan-head-before-37-37-37`. `tokens` is chars/4 over the added lines of that diff: 38,523, of which 16,607 is code and docs and the rest is the three RED evidence records, which contain the full TAP and JUnit reports.

## TDD Gate Compliance

- **Task 1 RED** (`533ddbc5`): `npx jest "app/shop/\[slug\]/orders" "app/shop/\[slug\]/checkout"` with a TAP reporter, rc 1, 160 tests with 18 failed. Every failure is a planned assertion: no C3 heading, region, title, breakdown or seller block on the order page; the checkout not routing; the stripe-lazy-load cash arm waiting for the marker; the vat-registration order-page arm waiting for the B7 note. The classifier returned `RED_EVIDENCE_OK` (`target_test_failed`). Two arms were green on the old tree by construction, because they describe goods that exist today and must be kept: the recorded allergen set with Back to shop, and the error branch without the marker. The vat-preview arms, retargeted to the card summary, were also green, because that screen already used `vatRateLabel`.
- **Task 1 backend RED**: the DTO fields were added first as scaffolding, then `PublicStorefrontServiceTest` ran 47 tests with 1 failed, `expected: <COLLECTION> but was: <null>`, giving `RED_EVIDENCE_OK` (JUnit). The first record named the method and was rejected `no_target_test_failure`, because the report carries the `@DisplayName`. It was re-recorded under the reported name. Both verdicts are real classifier output.
- **Task 2 RED** (`53451d8f`): the legal suites ran 79 tests with 1 failed, because the row read `jtoye-just-placed:<shop>`. Verdict `RED_EVIDENCE_OK`. The wording arm and the list entry were green by construction: Task 1 had to register the key, because the storage source scan refuses an undeclared `setItem` the moment the checkout writes one. Their fail direction is the key-removed arm below.
- **GREEN:** `44eaa92f` (Task 1) and `7b8a7d00` (Task 2). There was no refactor commit.

## Fail-direction evidence (bracketed: clean, arms, clean; restores verified by sha256)

Code arms on the committed GREEN, against `order-confirmation.test.tsx`, `order-tracking-allergens.test.tsx` and `checkout.test.tsx` (40 tests). Clean before: 40 of 40.

| Arm | Break | Result |
|---|---|---|
| A2 | the order page POSTs through `publicApiClient` on mount | F16 red: `not.toHaveBeenCalled`, received 2 calls (plus the error arm) |
| A | the same, written with `.catch` on the mock's `undefined` | VOID: the arm itself crashed (TypeError). Rebuilt as A2 |
| B | the order page POSTs through bare `fetch` on mount | F16 red: 2 POSTs in the spy |
| C | `router.push` removed from the cash branch | 1 failed (the routing arm) |
| D | `consumeJustPlaced` never removes the marker | 3 failed (marker removed, F16, second render) |
| E | VAT row without the `sellerVatRegistered` gate | 2 failed (UXT-047 arm, failed-shop arm) |

Clean after: 40 of 40. The three files were restored by content: the sha256 prefixes `f166cc71` / `fc14aabc` / `f58e45df` equal the committed ones.

Other arms:

- **Policy key-removed:** the registry entry was deleted, and 5 of 88 failed (4 policy tests and the storage source scan). After the restore the sha matched and 88 of 88 passed.
- **Java history reader:** with its two setters removed, the test failed with `expected: <DELIVERY> but was: <null>`. After the restore, 47 of 47 passed.
- **OpenAPI:** with HEAD's snapshot swapped in, `OpenApiSnapshotTest` failed ("no longer matches the reviewed snapshot"). After the restore it passed (sha `cb8d6f7e`).
- **Browser RED (UXT-029):** the two pre-plan pages (`70e528f0`) were built and served, and the confirmation scan and the C3 arm both failed with `toHaveURL /orders/ORD-…`, received `…/shop/test-kitchen/checkout`. The built chunks held "Order confirmed!" (1 file) and no "Opening your order", which shows the old code was the one served. A first attempt was VOID: an earlier `next start` child still held :3300 and served a half-replaced `.next`. It was killed by its captured PID and re-run. After the restore, rebuilding and re-running gave 42 of 42.
- **Metric gates:** `docs-freshness.sh` and `check-doc-metrics.sh` returned rc 1 (15 stale claims) before the update and rc 0 after it.
- **Contrast contract:** the full jest run found the new spinner's `text-amber-500` and the stale checkout `text-emerald-600` ledger entry (2 failed). Both were fixed in `90b9c8d7`.

## Verification (on the final tree)

| Command | rc | Result |
|---|---|---|
| `npm --prefix frontend test -- "app/shop/\[slug\]/orders" "app/shop/\[slug\]/checkout"` (plan Task 1 verify) | 0 | 16 suites, 160 tests |
| `npm --prefix frontend test -- app/legal` (plan Task 2 verify, part 1) | 0 | 79 tests |
| `npm --prefix frontend run build` (part 2) | 0 | "Finished TypeScript"; fresh `.next/BUILD_ID` (16:49:56Z); the chunks hold "Opening your order", `jtoye-just-placed:` and no "Order confirmed!" |
| `npx playwright test e2e/public-a11y.spec.ts --list` (part 3) | 0 | 42 tests (the C3 arm on mobile and desktop) |
| `PLAYWRIGHT_BASE_URL=http://localhost:3300 npx playwright test e2e/public-a11y.spec.ts` (local build, CI env) | 0 | 42 passed |
| `npx jest --ci` (full) | 0 | 235 suites, 2744 tests |
| `npx tsc --noEmit -p tsconfig.json` (full program: tests and e2e included) | 0 | 0 errors |
| `npx eslint .` | 0 | 0 errors, 30 warnings (31 before; the two on the order page, `searchParams` and `isPending`, predate 37-37) |
| `./gradlew :core-java:test` | 0 | 1769 tests, 0 failures, 1 skipped |
| `./gradlew :core-java:integrationTest --tests '*OpenApiSnapshotTest'` | 0 | 1 of 1 |
| `scripts/docs-freshness.sh`, `scripts/check-doc-metrics.sh` | 0, 0 | after the update (rc 1 before) |
| `scripts/check-test-count-oracle.sh` | 0 | jest 2744/235, playwright 162/40 and vitest 61/8 agree (VOID rc 2 on its first run, which is how the contrast failure was found) |
| `scripts/check-test-block-counter.sh`, `check-no-create-extension.sh` | 0, 0 | PASS |
| `scripts/check-branch-behind-base.sh` | 0 | 0 behind origin/main (078c0c11) |

Not run here: the live e2e on the shared stack, and the full core-java integration suite. The stack was not touched, and the live proof belongs to 37-38.

## Goods displaced, and how each is accounted for (Incremental Betterment)

- **The in-place cash confirmation at /checkout.** Retired on purpose: it was the defect (UXT-029). Everything it showed moved to the order route.
  - "Pay on delivery/collection": WR-08 holds there.
  - The breakdown: subtotal, delivery or Free, VAT by `vatRateLabel` under the B7 rule, the B7 note, and the total (P2-REG-17).
  - The recorded allergen set: on the order page it gains `placedVia` and the allergy-note status, which the checkout did not pass.
  - The seller block (#789).
  - "Back to shop".
  - "Track your order": the page is now the tracking page, and "Track this order" (to /track) remains.
- **The cash `allergenWarnings` panel.** It was always empty: the list is computed at creation and never stored, and it has been empty by construction since 2026-07-30. The seam stays on `GuestOrderConfirmation` and on the card step. The preserved-goods test now counts one guard and says why. A future consented warning path must persist its warnings and expose them on the tracking response for cash orders.
- **The order page's status h1** ("Order in Progress" and the others). These are now a line under the C3 h1, so nothing a reader saw is lost. `order-tracking-allergens` and the a11y scan assert the line.
- **The order page as the target of the email-prompt and card-return flows.** Unchanged: no marker means the plain view.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical functionality] The tracking response could not carry the confirmation it was to show.**
- **Found during:** Task 1, before any code.
- **Issue:** the plan says `PublicOrderStatus` "has vatRate and the recorded allergen set". It has no fulfilment type and no delivery fee, so "Pay on delivery/collection" and the delivery row would have been guesses. A fee of 0 is either a collection or a free delivery.
- **Fix:** two additive fields set by both readers, a JUnit RED then GREEN, and a regenerated OpenAPI snapshot. No address is added and the endpoint is unchanged.
- **Files:** `PublicOrderStatus.java`, `PublicStorefrontService.java`, `PublicStorefrontServiceTest.java`, `docs/api/openapi-snapshot.json`.
- **Commit:** `8b31e4ae`.

**2. [Rule 3 - Blocking] Tests and specs written against the in-place confirmation.**
- **Issue:** a case-insensitive search found more of these than the plan's file list names. On their own they would have gone red, or worse, gone vacuous.
- **Moved to the order route:**
  - The cash-confirmation arms in `vat-registration` (2 arms) and `checkout-seller-block` (1 arm), now in `order-confirmation.test.tsx`.
  - The vat-preview `vatRateLabel` pair, retargeted to the card summary.
  - The allergy-note D-08 pair, retargeted to the card step.
  - `stripe-lazy-load` and allergy-note storage now wait on the marker.
- **Negative checks fixed:** three negatives that would have passed for ever (`basket-changed`, `allergen-ack-stale`, `shop-closed`: "no /order confirmed/i text") now assert that no marker was left. `basket-changed` keeps its positive waits on the marker. sessionStorage is now cleared between tests in those files.
- **Other test updates:**
  - The seam count in `allergen-acknowledgement` is now 1, with the reason given.
  - The storage scan's exact session-key count is now 6.
  - The accessibility statement and its two tests point the confirmation at the order route.
  - One stale entry is gone from the contrast ledger.
- **e2e:** 7 specs (`seller-identity`, `allergen-ack-race`, `abuse-bounds`, `basket-revalidation`, `cash-checkout-no-stripe`, `storage-keys-signout`, `storefront-flows`) wait through `e2e/cash-confirmation.ts`. Their negatives also assert the page stayed on `/checkout`. `storefront-flows` now asserts "Pay on delivery" on the route.
- **Commits:** `44eaa92f`, `90b9c8d7`.

**3. [Rule 3 - Blocking] The storage registry entry landed with Task 1, not Task 2.**
- **Issue:** the storage source scan fails the build the moment a `setItem` writes an undeclared key, so the checkout could not write the marker without the entry.
- **Fix:** Task 2 kept its own RED, on the policy's display name, which was a real defect (`<shop>`). Task 2 also delivered `suffixLabel`.

**4. [Rule 1 - Bug] The new spinner used an unregistered `text-amber-500`.**
- **Issue:** the contrast contract caught it in the full jest run, not in the touched-suite run.
- **Fix:** changed to `text-amber-700`.
- **Commit:** `90b9c8d7`.

**5. [Rule 3] The a11y scan counts `<h1>` without waiting.**
- **Issue:** the first local run failed because the universal control counted during the "Opening your order" moment.
- **Fix:** the test waits for the route and the heading before the scan, as the old one effectively did.
- **Commit:** `7b8a7d00`.

### CLAUDE.md-driven

- No AI attribution lines in any commit (the user ruling overrides the harness reminder). Every commit was staged by explicit path, and `.planning/milestone.lock` was left alone.

## Issues Encountered

- The first browser RED was VOID. Killing the `npx` PID did not stop its `next-server` child, which kept serving :3300 from a `.next` that was being replaced. Every later server was stopped by the PID read from `ss -ltnp`, and that first run is not counted.
- `searchParams` (unused) and the moved tracker's `isPending` still raise warnings on the order page. Both predate 37-37 and were left as they were.

## Notes for the 37-38 gate

- **Runtime:** this plan changes core-java (`PublicOrderStatus`, OpenAPI) as well as the frontend. Rebuild BOTH images before the live arms. `check-openapi-snapshot-fresh.sh` against the current (37-D) runtime will report the two new fields as missing until core is rebuilt; that is expected.
- **Live arms to run, red before the rebuild and green after:** `public-a11y.spec.ts` (the cash-confirmation scan and "C3: the cash confirmation takes focus, announces itself, and a refresh re-posts nothing"), and the regression specs, which now wait on the route: `cash-checkout-no-stripe`, `storefront-flows`, `allergen-ack-race`, `basket-revalidation`, `seller-identity`, `storage-keys-signout` and `abuse-bounds`.
- **Live title check:** against a real core the title is set after the tracking read. The local run with stubs saw `^Order ORD-… confirmed · ` both times. If Next's metadata ever writes the title after the effect, the C3 arm's `toHaveTitle` will catch it. 37-51 adds `layout.tsx generateMetadata` for this route and must keep the confirmed variant on the first view.
- **37-51** must keep ONE `role="status"` region on the order page: 37-37's is the region its status-change announcements should write into.
- **UXT-029 goods to re-verify on the rebuilt runtime:** P2-REG-17 (the breakdown on the route), P2-GRA-21 (the plain COD wording), F3 and P2-KYL-P5 (tracking bound to number and email).

## Known Stubs

None. Every rendered value comes from the tracking response or the public shop. An absent value is left out rather than defaulted.

## Ledger

No stub, skipped test or unrun `<verify>` to record: both plan verify commands ran, and the a11y spec also ran in a browser. The deferred item (the storefront header overflowing by 4px at 360px with root 130%, which is 37-52's F6 scope) is in `deferred-items.md`.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: info-disclosure (mitigated) | core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicOrderStatus.java | Two fields added to the unauthenticated tracking response: fulfilment type and delivery fee. Neither is personal data, and the address stays off. The response is still bound to order number + email (T-37-89). |

T-37-88 (duplicate order from a refresh) is mitigated: the route only reads, and F16 holds in jest, under arms A2 and B, and in the browser. T-37-89 is mitigated: the marker holds the order number only and the page trusts nothing from it. T-37-SC: no package was installed.

## User Setup Required

None.

## Next Phase Readiness

The 37-C code is complete with 37-37. Next is 37-38, the 37-C gate (`autonomous: false`): reconcile and merge, rebuild all images, run the live RED and GREEN arms, re-verify the goods, and run the human checks.

## Self-Check: PASSED

- Files: `frontend/lib/just-placed.ts`, `frontend/components/storefront/no-vat-registration-note.tsx`, `frontend/e2e/cash-confirmation.ts`, `frontend/app/shop/[slug]/orders/[orderNumber]/__tests__/order-confirmation.test.tsx`, this SUMMARY — all FOUND.
- Commits: 533ddbc5, 8b31e4ae, 44eaa92f, 53451d8f, 7b8a7d00, 90b9c8d7, f337abff, 77ba0eb0 — all ancestors of HEAD.
- REQUIREMENTS.md unchanged: RWO-029 is declared through the 37-38 gate (requirements-addressed, not completed).
