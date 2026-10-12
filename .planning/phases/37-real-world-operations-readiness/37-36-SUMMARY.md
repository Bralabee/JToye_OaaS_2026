---
phase: 37-real-world-operations-readiness
plan: 36
subsystem: storefront-shop-detail, dashboard-marketing
tags: [promotions, reviews, d-17, uxt-008, uxt-044, rwo-008, rwo-044, ui-spec-c2, ui-spec-c4, frontend, tdd]

requires:
  - phase: 37-35
    provides: "public promotion reads return [] and public ReviewDto.customerName is 'First L.' or null"
provides:
  - "/shop/[slug] renders no promotion (no banner, no per-product chip) and neither ShopDetailClient's load nor loadShopDetail requests /promotions; ShopDetail and types/storefront carry no promotions field or PublicPromotion type"
  - "the announcement band (data-testid shop-announcements) renders only for an announcement, so no empty strip replaces the promotions"
  - "public review cards render the server's display name verbatim and 'Customer' for a null, empty or blank one; 'Anonymous' is gone"
  - "/dashboard/marketing: the C2 info panel (data-testid promotions-hidden-notice) opens the promotions tab; the announcement helper (id ann-discount-helper) sits under the body and describes the title and body fields"
  - "e2e STFR-06 inverted: with an active 20% promotion seeded through the vendor API, the shop page shows no discount and the browser makes no /promotions request"
affects: [37-37, 37-38, storefront-shop-detail, dashboard-marketing, e2e-storefront-flows]

actuals:
  tokens: 18400
  tasks: 2
  commits: 6
plan_head_before: 321cbfedc3373add6e09353e25dfa863bf6b7b98
plan_head_after: 5483dd413e3bf4a43e26662b0a9b943c48a79ad2

tech-stack:
  added: []
  patterns:
    - "A claim the platform cannot stand behind is removed from the TYPE, not just the render: ShopDetail has no promotions field, so tsc refuses a fixture or a loader that brings one back"
    - "A storefront test feeds the page a legacy payload that still carries the claim, so the test proves the page and not the server"

key-files:
  created:
    - frontend/app/shop/__tests__/shop-promotions-hidden.test.tsx
    - frontend/app/shop/__tests__/review-display.test.tsx
    - frontend/app/dashboard/__tests__/marketing-promotions-notice.test.tsx
    - .planning/phases/37-real-world-operations-readiness/evidence/37-36-tdd/red-task1-record.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-36-tdd/red-task2-record.json
  modified:
    - frontend/app/shop/[slug]/shop-detail-client.tsx
    - frontend/lib/storefront-server.ts
    - frontend/types/storefront.ts
    - frontend/app/dashboard/marketing/page.tsx
    - frontend/lib/__tests__/storefront-server.test.ts
    - frontend/__tests__/shop/server-seeded-islands.test.tsx
    - frontend/__tests__/shop/product-card-allergens.test.tsx
    - frontend/__tests__/shop/product-card-quantity-a11y.test.tsx
    - frontend/__tests__/shop/stepper-limit.test.tsx
    - frontend/__tests__/cluster-g-contrast-sites.test.ts
    - frontend/e2e/storefront-flows.spec.ts
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "37-36: the storefront does not FETCH promotions (client and SSR), so an older or misconfigured core cannot put a discount back into the HTML; the server's [] (37-35) is not relied on"
  - "37-36: ShopDetail.promotions and PublicPromotion are deleted from types/storefront.ts; the vendor-side Promotion types in types/api.ts are untouched"
  - "37-36: the announcement band renders only when there is an announcement; a shop with only promotions now has no band rather than an empty white strip"
  - "37-36: the reviewer fallback is review.customerName?.trim() || 'Customer' (UI-SPEC C4), so an empty or whitespace name cannot render a blank label"
  - "37-36: the review-form notice (UI-SPEC C4) is NOT shipped: the frontend has no review form (reviews are API-only); recorded as an open deferred item and a WINDOWS unmet-truth entry rather than invented"

patterns-established:
  - "Removing a customer-facing claim: delete the fetch, the type and the render together, prove each with a legacy-payload fail arm, and invert (not skip) the e2e that asserted the old claim"

requirements-completed: []
requirements-addressed: [RWO-008, RWO-044]

coverage:
  - id: D1
    description: "F17: a server-seeded payload carrying a 20% OFF and a £2 OFF promotion renders no banner, no chip, no 'off' and no percentage; the announcement still renders in its band; no fetch on mount"
    requirement: "RWO-008"
    verification:
      - kind: unit
        ref: "shop-promotions-hidden.test.tsx seeded arm: RED on 25b29b98 (7 ran / 7 failed across both new suites, RED_EVIDENCE_OK, evidence/37-36-tdd/red-task1-record.json); GREEN on 4bb68d05; break arm A (the pre-GREEN shop-detail-client restored, chip + banner + fetch + Anonymous) -> 6 of 15 failed; restored by sha256 5a207ef7..."
        status: pass
    human_judgment: false
  - id: D2
    description: "F17: the client load (initial null) and loadShopDetail make no /promotions request even when the server would answer with one, and the SSR payload has no promotions field"
    requirement: "RWO-008"
    verification:
      - kind: unit
        ref: "shop-promotions-hidden.test.tsx client and SSR arms; break arm B (pre-GREEN storefront-server.ts) -> 1 of 15 failed (the SSR arm); tsc arm: a fixture carrying promotions: [] -> TS2353, rc 2; built chunks hold no /public/shops/.../promotions path (only the dashboard's /api/v1/promotions CRUD)"
        status: pass
    human_judgment: false
  - id: D3
    description: "No empty band: with only (legacy) promotions and no announcement, no shop-announcements band renders; with an announcement, the band holds it and no discount"
    requirement: "RWO-008"
    verification:
      - kind: unit
        ref: "shop-promotions-hidden.test.tsx band arms (red on the old tree: the band rendered the promotions and had no test id)"
        status: pass
    human_judgment: false
  - id: D4
    description: "F19: 'Grace P.' renders, 'Grace' renders as given, null, '' and '   ' render 'Customer', and 'Anonymous' appears nowhere"
    requirement: "RWO-044"
    verification:
      - kind: unit
        ref: "review-display.test.tsx: RED in the same record (the Customer assertions failed after the Grace P. control passed); break arm C (the || \"Anonymous\" fallback restored alone) -> 2 of 15 failed"
        status: pass
    human_judgment: false
  - id: D5
    description: "The marketing info panel with the C2 copy above the promotions list, only on the promotions tab; the announcement helper under the body, describing title and body; promotion CRUD still offered"
    requirement: "RWO-008"
    verification:
      - kind: unit
        ref: "marketing-promotions-notice.test.tsx: RED on 935283b9 (4 ran / 2 failed, RED_EVIDENCE_OK, evidence/37-36-tdd/red-task2-record.json; the CRUD and Announcements-tab cases green by construction, named in the record); GREEN on eadaac14; arm D (panel on both tabs) 1 failed, arm E (helper unlinked from the body) 1 failed, arm F (panel below the list) 1 failed; restored by sha256"
        status: pass
    human_judgment: false
  - id: D6
    description: "The review-form notice ('Your review will show your first name and the first letter of your last name...')"
    requirement: "RWO-044"
    verification:
      - kind: other
        ref: "NOT SHIPPED: no review form exists in the frontend (no caller of POST /public/shops/{slug}/reviews); deferred-items.md (37-36, open) and WINDOWS unmet-truth entry"
        status: fail
    human_judgment: true

duration: 10 min (16:01Z-16:11Z)
completed: 2026-10-12
status: complete
---

# Phase 37 Plan 36: The storefront shows no promotion, the vendor is told why, and reviewers appear as "First L." Summary

**`/shop/[slug]` no longer advertises a discount that checkout never applies. The promotions banner and the per-product "% off" chip are gone, and neither the browser nor the server render asks for promotions at all, so an older core cannot bring them back. The announcement band stays where it was and now renders only for an announcement, so no empty strip is left. Public reviews show the server's "Grace P." and fall back to "Customer" instead of "Anonymous". On `/dashboard/marketing` the vendor keeps every promotion and the CRUD, and the promotions tab now opens with a panel that says customers can't see promotions until checkout can apply them. The announcement form warns against putting discounts or prices in an announcement. One truth is not met: the review-form notice, because the frontend has no review form.**

## Performance

- **Duration:** about 10 minutes, 16:01Z to 16:11Z.
- **Tasks:** 2 of 2. Task 1 was a tracer and Task 2 auto, both TDD.
- **Files:** 20 changed outside `.planning/` plus the two RED records (707 insertions, 121 deletions across the six commits).

## Accomplishments

- **Task 1 (tracer, TDD).**
  - RED `25b29b98`: `shop-promotions-hidden.test.tsx` (5 arms) and `review-display.test.tsx` (2 arms). 7 ran / 7 failed on the planned assertions: the discount text in the body, the `/promotions` requests from the client and the SSR loader, the missing announcements-only band, the missing "Customer". `RED_EVIDENCE_OK`.
  - GREEN `4bb68d05`: the fetch, chip, banner, `promotionsByCategory`, the `promo` prop, `ShopConfig.activePromotions`, `ShopDetail.promotions` and `PublicPromotion` are removed. The band is announcements-only with `data-testid="shop-announcements"`. The review name is `review.customerName?.trim() || "Customer"`. `loadShopDetail` makes 4 upstream calls, not 5. STFR-06 is inverted (see Deviations).
  - Tracer gate: the plan's `<verify>` (`npm --prefix frontend test -- app/shop __tests__/shop`) re-run on the GREEN tree: 36 suites, 359 tests, rc 0. `server-seeded-islands.test.tsx` green throughout. Tracer verified end to end.
- **Task 2 (TDD).**
  - RED `935283b9`: `marketing-promotions-notice.test.tsx`, 4 ran / 2 failed (panel and helper absent). `RED_EVIDENCE_OK`.
  - GREEN `eadaac14`: `PromotionsHiddenNotice` (`rounded-lg border border-slate-200 bg-white p-4`, `Info` icon `aria-hidden`, `text-sm`) at the top of the promotions tab, and the helper `<p id="ann-discount-helper" class="text-sm text-slate-600">` under the body, referenced by `aria-describedby` on the title and the body.
- **Follow-ups.** `77aa2a7d`: the A11Y-6 contrast case that counted the two amber badges now asserts they are gone. `5483dd41`: test counts.

## Task Commits

1. **Task 1 RED: the storefront shows promotions and "Anonymous" reviewers** — `25b29b98` (test)
2. **Task 1 GREEN: the storefront shows no promotion, and reviewers appear as "First L."** — `4bb68d05` (feat)
3. **Task 2 RED: the marketing page does not say why promotions are hidden** — `935283b9` (test)
4. **Task 2 GREEN: the vendor is told why promotions are hidden from customers** — `eadaac14` (feat)
5. **The A11Y-6 contrast case follows the removed discount badge** — `77aa2a7d` (test)
6. **The test counts follow the tree after 37-36** — `5483dd41` (docs)

`commits: 6` is `git rev-list --count 321cbfed..5483dd41`, measured from the persisted ledger `.git/gsd-plan-head-before-37-37-36`.

## TDD Gate Compliance

| Gate | Task 1 | Task 2 |
|------|--------|--------|
| RED (`test(37-36)`) | `25b29b98`: 7 ran / 7 failed, `RED_EVIDENCE_OK` (TAP via a scratchpad Jest reporter; `evidence/37-36-tdd/red-task1-record.json`) | `935283b9`: 4 ran / 2 failed, `RED_EVIDENCE_OK` (`red-task2-record.json`) |
| GREEN (`feat(37-36)`) | `4bb68d05`: 7/0 | `eadaac14`: 4/0 |
| REFACTOR | none | none |

**Semantic assessment.** Both suites loaded and ran against the real `ShopDetailClient`, `loadShopDetail` and `MarketingPage`; only the API clients, `fetch`, the cart context, the shop context and the toast were mocked. Every failing case failed on its planned assertion after its control passed. No syntax, import, fixture or zero-discovery fault. Two Task 2 cases were green on the old tree by construction (the CRUD preservation check and the "not on the Announcements tab" absence check); both are named in the record, and arm D gives the second its fail direction.

## Fail-direction evidence (bracketed: clean, arms, clean; restores verified by sha256)

| Arm | Break | Result |
|---|---|---|
| A | the pre-GREEN `shop-detail-client.tsx` (chip, banner, fetch, "Anonymous") | 6 of 15 failed (the 4 F17 component arms + the 2 F19 arms) |
| B | the pre-GREEN `storefront-server.ts` (SSR fetches promotions) | 1 of 15 failed (the SSR arm) |
| C | only `|| "Anonymous"` restored | 2 of 15 failed |
| D | the panel moved outside the promotions tab | 1 of 4 failed |
| E | `aria-describedby` dropped from the body textarea | 1 of 4 failed |
| F | the panel moved below the promotions list | 1 of 4 failed |
| G | an amber "% off" badge appended to the shop page | `cluster-g-contrast-sites` 1 of 11 failed |
| tsc | `promotions: []` added back to a `ShopDetail` fixture | TS2353, rc 2 |
| gates | `docs-freshness.sh` / `check-doc-metrics.sh` before the count update | rc 1 / rc 1 (10 claims), then rc 0 / rc 0 (37/37) |

Closing clean runs: 15/15 and 4/4, `sha256sum -c` OK on every restored file, `git status` clean apart from `.planning/milestone.lock`.

## Verification (on the final tree)

| Command | rc | Result |
|---|---|---|
| `npm --prefix frontend test -- app/shop __tests__/shop --ci` | 0 | 36 suites, 359 tests |
| `npm --prefix frontend test -- app/dashboard/__tests__ --ci` | 0 | 10 suites, 116 tests (all three marketing suites) |
| `npx jest --ci` (full) | 0 | 234 suites, 2728 tests (it was 1 failed before `77aa2a7d`) |
| `npx tsc --noEmit -p tsconfig.json` (full program: tests and e2e included) | 0 | 0 errors |
| `npx eslint .` | 0 | 0 errors, 31 warnings (none new: the two on touched files, `KEYCLOAK` unused in storefront-flows and `watch()` in marketing, predate 37-36) |
| `npm run build` | 0 | "Running TypeScript ... Finished"; fresh `.next/BUILD_ID` (16:10:02Z); the panel copy is in the marketing chunks; no built chunk holds a `/public/shops/.../promotions` path |
| `npx playwright test e2e/storefront-flows.spec.ts --list` | 0 | 44 tests; the inverted STFR-06 listed for mobile and desktop |
| `scripts/docs-freshness.sh`, `scripts/check-doc-metrics.sh` | 0, 0 | after the update (rc 1 before) |
| `scripts/check-test-count-oracle.sh`, `check-test-block-counter.sh` | 0, 0 | PASS |
| `scripts/check-branch-behind-base.sh` | 0 | 0 behind origin/main (078c0c11) |

Not run here: the e2e itself and any live check. The shared stack runs the 37-D images and must not be rebuilt by this plan; the live proof belongs to 37-38.

## Goods displaced, and how each is accounted for (Incremental Betterment)

- **The promotions banner, the per-product chip and the promotions fetch on `/shop/[slug]`.** Removed on purpose: they were the defect (D-17). The vendor's promotions, the CRUD and the dashboard list are kept and asserted (Task 2 CRUD case; the inverted e2e also creates one through the API and expects 201).
- **The announcements.** Kept in the same position, same markup, same copy; asserted in the seeded, client and SSR arms.
- **The band's layout.** A shop with an announcement renders the same band as before. A shop with neither renders nothing, as before. Only a shop with promotions and no announcement changes: from a band of promotions to no band, which is the "no empty band" contract.
- **The A11Y-6 contrast guarantee.** The badge it guarded is gone; the case still forbids white text on `bg-amber-500` and now also forbids the badge class and "% off".
- **The reviewer name.** Unchanged for every review with a name; only the null/blank fallback copy changes ("Customer").
- **`ShopConfig` fetch.** Kept (it was already discarded on read); only the `activePromotions` declaration went.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixtures and the loader test outside `files_modified`**
- **Found during:** Task 1 GREEN.
- **Issue:** removing `ShopDetail.promotions` made four storefront fixtures excess-property errors under tsc, and `storefront-server.test.ts` asserted 6 calls and `r.data.promotions`.
- **Fix:** dropped `promotions: []` from `server-seeded-islands`, `product-card-allergens`, `product-card-quantity-a11y` and `stepper-limit`; the loader test now counts 5 calls and no longer reads the field. No other assertion changed.
- **Committed in:** `4bb68d05`.

**2. [Handover note 2] e2e STFR-06 inverted, not skipped**
- **File:** `frontend/e2e/storefront-flows.spec.ts` (outside `files_modified`).
- **Was:** "promotion banner and discount badge render on shop detail", skipping when nothing was seeded.
- **Now:** "no promotion banner or discount badge on shop detail, even with an active promotion (STFR-06, D-17)". It seeds an active 20% promotion through `POST /api/v1/promotions` with the vendor token from `e2e/order-cleanup.ts` (expects 201), loads the page, asserts the h1 and a card (control), no seeded label, no `\d+% off`, no `£x off`, and no browser request to `/promotions`, then deletes the promotion in `finally`. Without vendor credentials the seed is skipped and recorded as a test annotation; the assertions still run. Listed by Playwright; not run here (no stack rebuild).
- **Committed in:** `4bb68d05`.

**3. [Rule 1 - Bug in my own test] SSR arm's slug matched its own filter**
- **Found during:** Task 1 GREEN. The SSR arm used the slug `promotions-hidden-ssr` and filtered URLs with `includes("promotion")`, so every URL matched. The slug became `brixton-village-grill-ssr` and both filters became `/\/promotions(\?|$)/`. On the RED tree this arm failed for the right reason as well (the loader did request `/promotions`); arm B re-proves its fail direction on the final test.
- **Committed in:** `4bb68d05` (the RED commit carries the original).

**4. [Rule 3 - Blocking] `cluster-g-contrast-sites.test.ts` counted the removed badges**
- **Found during:** the full Jest run (1 failed: expected 2 badge classes, got none).
- **Fix:** the case now asserts the badge class and "% off" are absent and that white-on-amber-500 has not returned (arm G proves it can fail). The shop page's removal comment was reworded so it no longer quotes a "% off" string.
- **Committed in:** `77aa2a7d`.

**5. [Rule 3 - Blocking] Test counts.** `docs-freshness.sh --write`, then the six figures in README, CLAUDE.md and AGENTS.md. `5483dd41`.

### Truth not met (recorded, not relabelled)

- **"The review form shows 'Your review will show your first name and the first letter of your last name, like "{First} {L}.".'"** The frontend has no review form. `rg -uu` over `frontend/app`, `components`, `lib` and `hooks` finds no caller of `POST /public/shops/{slug}/reviews`; reviews are created only through the API (the UXT-044 repro does exactly that). Building a review-submission surface (order binding, ratings, photos, the UXT-005 refusal copy, error states) is a new customer flow with no UI-SPEC contract, so I did not invent one. The data-protection half of UXT-044 holds without it: the server minimises the name (37-35) and the page renders it with the "Customer" fallback. Recorded as an open item in `deferred-items.md` and as a WINDOWS `unmet-truth` entry. 37-38's human check "read the review form notice" cannot be performed as written.

**Total deviations:** 5 auto-fixed (3 blocking, 1 own-test bug, 1 handover inversion) and 1 truth not met. **Impact:** the storefront and dashboard halves of RWO-008 are complete; RWO-044's display half is complete, its form notice is open.

## Issues Encountered

- No TAP reporter ships with the project's Jest. I wrote a minimal one in the session scratchpad (`file > suite > test` names, ANSI stripped) so the classifier's TAP adapter could read the real run.
- `.planning/milestone.lock` (untracked) left alone.

## Notes for 37-37 and the 37-38 gate

- **37-37:** nothing in this plan changes its inputs. The storefront `ShopDetail` type has no `promotions` field now; a new fixture must not add one (tsc refuses it).
- **37-38 (gate):**
  - Rebuild the frontend (and core-java for 37-35). Then run `e2e/storefront-flows.spec.ts` with the stack's `.env` sourced, so STFR-06 seeds a real active promotion; read its `promotion-seed` annotation to confirm it seeded, and that it cleaned up.
  - On the rebuilt `/shop/[slug]`: view source for "% off" and for any `/promotions` request in the network panel; check the announcement band at 390/768/1280 and the CLS of `/shop/[slug]` on the throttled profile (the page makes one call fewer on SSR and on the client).
  - `/dashboard/marketing`: the panel above the list, the helper under the announcement body.
  - Record the human check "read the review form notice" as N/A with the deferred-items reference, or schedule the form.

## Known Stubs

None. The removed promotions are the feature (D-17), not a placeholder.

## Ledger

One WINDOWS entry: `unmet-truth`, phase 37, the review-form notice (see above). Every `<verify>` was run.

## Threat Flags

None new.
- **T-37-87** (discounts shown but not applied) is mitigated on the client: no fetch, no type, no render, client and SSR, each with a fail arm; the announcement helper covers the remaining customer-facing text.
- **T-37-SC** held: no package was installed.

## User Setup Required

None.

## Next Phase Readiness

- **37-37** is next.
- **Requirements.** RWO-008 and RWO-044 stay unticked until the 37-C gate (37-38), as 37-35 recorded.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-12*

## Self-Check: PASSED

- FOUND: `shop-promotions-hidden.test.tsx`, `review-display.test.tsx`, `marketing-promotions-notice.test.tsx`, both RED evidence records; `marketing/page.tsx` contains the panel heading, `shop-detail-client.tsx` the "Customer" fallback
- FOUND commits (ancestors of HEAD): `25b29b98`, `4bb68d05`, `935283b9`, `eadaac14`, `77aa2a7d`, `5483dd41`, and the SUMMARY commit `b62f1a0a`
