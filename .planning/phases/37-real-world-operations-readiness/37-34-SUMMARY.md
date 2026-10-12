---
phase: 37-real-world-operations-readiness
plan: 34
subsystem: storefront-checkout
tags: [checkout, basket-revalidation, basket-changed, basket-confirmation-required, d-16, uxt-007, rwo-007, client-persisted-identity, r-16, idempotency, allergen-acknowledgement, tdd, frontend]

requires:
  - phase: 37-32
    provides: "expectedUnitPricePennies per line and expectedTotalPennies on the guest-order contract; the expected total is line totals plus FulfilmentPolicy's fee"
  - phase: 37-33
    provides: "409 https://jtoye.uk/errors/basket-changed with changes[] (PRICE_CHANGED, UNAVAILABLE, SOLD_OUT, REMOVED with no name, BELOW_MINIMUM, TOTAL_CHANGED), the optional 31.1 allergen properties, 422 basket-confirmation-required, and every refusal released inside the reserved idempotency work so the same key resubmits"
  - phase: 31.1-03
    provides: "readStaleAllergenProblem, the OrderAllergenPanel stale override and the refuse-not-disable gate pattern this plan mirrors"
provides:
  - "BasketChangedPanel (UI-SPEC C1): role=alert container, rounded-xl border border-orange-700 bg-orange-50 p-4, RefreshCcw, a focusable h2 'Your basket has changed since you opened it' that takes focus on mount, one row per change with its kind's exact sentence (PRICE_CHANGED 'Suya · £9.00 → £14.00' with the arrow aria-hidden and 'was £9.00, now £14.00' sr-only), a nameless REMOVED named from the basket's own copy, the allergens-also-changed line, an outline 'Accept changes', the refusal line, and the collapsed note 'Basket updated. Check the new total before you place your order.'"
  - "Checkout posts expectedUnitPricePennies per line (the price the basket holds) and expectedTotalPennies (line totals plus the delivery-fee preview, the figure on the Place order button) — D-16"
  - "readBasketChangedProblem (checked first among the 409s; a body it cannot read in full is not applied) and readBasketConfirmationRequiredProblem (422); malformed 409 and 422 both show 'Something went wrong preparing your order. Refresh the page and try again. Your basket is saved.' with the basket untouched"
  - "The accept gate: Place order before 'Accept changes' is refused with a role=alert line and focus moves to the button (refuse-not-disable); the resubmit reuses the SAME Idempotency-Key"
  - "applyBasketChanges (pure over the lines) and CartProvider.applyServerChanges (a functional setItems through the provider's one serialize path, so the ownership stamp is resolved exactly as on any shopper write — ADD or CONFIRM, never ERASE; R-16)"
  - "A mixed price+allergen 409 re-renders OrderAllergenPanel with the server's set unticked and the extra sentence; an allergen-only 409 keeps the 31.1 path"
  - "frontend/e2e/basket-revalidation.spec.ts: the vendor-reprice race plus an INSTRUMENT arm (no vendor edit, no panel), listed (4 tests: 2 x mobile/desktop); its RED (pre-rebuild) and GREEN runs belong to 37-38"
affects: [37-38, storefront-checkout, cart-provider, e2e-ordering-specs]

actuals:
  tokens: 32702
  tasks: 3
  commits: 6
plan_head_before: 92b4b972550008f0c544d70b31fae378ee1ac38e
plan_head_after: 8f57625467c8ae8b8d13bf217721ec87af693d61

tech-stack:
  added: []
  patterns:
    - "A server rewrite of client-persisted state goes through the same write path a user's own edit uses (functional setItems -> the one serialize that stamps the owner), never a direct storage write of a fresh object"
    - "A typed 409 that changes what the customer pays is shown as a diff and gated by an explicit accept; the resubmit reuses the same Idempotency-Key the server released"
    - "A problem body the client cannot read in full is not applied: generic copy, state untouched (the readStaleAllergenProblem null precedent)"

key-files:
  created:
    - frontend/components/storefront/basket-changed-panel.tsx
    - frontend/components/storefront/__tests__/basket-changed-panel.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/basket-changed.test.tsx
    - frontend/e2e/basket-revalidation.spec.ts
    - .planning/phases/37-real-world-operations-readiness/evidence/37-34-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-34-red-task2.json
  modified:
    - frontend/app/shop/[slug]/checkout/page.tsx
    - frontend/components/storefront/cart-provider.tsx
    - frontend/components/storefront/__tests__/cart-provider-identity.test.tsx
    - frontend/types/storefront.ts
    - frontend/lib/checkout-idempotency.ts
    - frontend/lib/__tests__/checkout-idempotency.test.ts
    - frontend/app/shop/[slug]/checkout/__tests__/allergen-attribution.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/checkout-idempotency.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/checkout-seller-block.test.tsx
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "37-34: applyServerChanges is a functional setItems over the CURRENT lines, persisted by the provider's one serialize path; it never writes storage itself, so the owner stamp is out of its hands (F15; a whole-object rewrite reds the lapse arm)"
  - "37-34: the two D-16 fields are part of the signed idempotency intent (lib/checkout-idempotency.ts) and absent for a caller without them, so the pre-37-32 fingerprint is unchanged and a reprice moves the signature; after a basket-changed 409 the page reuses the key explicitly (reuseKeyAfterBasketChangeRef) because the server released it"
  - "37-34: the panel's REMOVED names come from the basket as seen at the moment of the 409 (names map captured before applyServerChanges drops the line)"
  - "37-34: on a mixed 409 the server's allergen set is held as the stale override and released only when a fresh catalogue read agrees with it (the 31.1 convergence rule)"

patterns-established:
  - "Phase 37-C client rule: branch on the problem type, show every change, require an explicit accept, resubmit once under the same key, and rewrite client state only through its owner-preserving write path"

requirements-completed: []
requirements-addressed: [RWO-007]

coverage:
  - id: D1
    description: "F14: a 409 PRICE_CHANGED Suya 900->1400 shows the panel row 'Suya · £9.00 → £14.00' directly above OrderAllergenPanel with focus on the heading; Place order before Accept is refused with a role=alert line, focus on Accept changes and still one POST; after Accept the resubmit reuses the SAME Idempotency-Key; the submit body carries expectedUnitPricePennies per line and expectedTotalPennies (collection and delivery)"
    requirement: "RWO-007"
    verification:
      - kind: unit
        ref: "basket-changed.test.tsx + basket-changed-panel.test.tsx (RED on 6f5d9ada against a scaffold rendering null: TAP 153 / 123 pass / 30 fail, RED_EVIDENCE_OK, evidence/37-34-red-task1.json); close-out break arm 2026-10-12: the accept gate disabled (`if (false && basketChanged && !basketChangesAccepted)`) -> basket-changed.test.tsx 16 ran / 2 failed, exactly the F14 refusal arm and the tap-landing-as-the-409-returns arm, rc 1; restored, sha256 3f483c64… equal to HEAD"
        status: pass
    human_judgment: false
  - id: D2
    description: "F15: applyServerChanges rewrites prices, drops UNAVAILABLE/REMOVED, sets SOLD_OUT quantities (dropped at 0), and the stored owner stamp is byte-identical before and after through a lapse and a sign-in performed between the 409 and the update, asserted on the parsed localStorage payload"
    requirement: "RWO-007"
    verification:
      - kind: unit
        ref: "cart-provider-identity.test.tsx + basket-changed.test.tsx (RED on 4b761d8f against no-op scaffolds: TAP 321 / 313 pass / 8 fail, RED_EVIDENCE_OK, evidence/37-34-red-task2.json); close-out break arm 2026-10-12, the plan's named arm: applyServerChanges replaced by a whole-cart-object write (`localStorage.setItem(key, JSON.stringify({shopSlug, owner: getCurrentCustomerId(), items: applyBasketChanges(readCart(shopSlug), changes)}))`) -> cart-provider-identity.test.tsx 22 ran / 1 failed, the F15 lapse arm (stamp A became null), rc 1; restored, sha256 31a76b8f… equal to HEAD"
        status: pass
    human_judgment: false
  - id: D3
    description: "Malformed 409 (changes empty, missing, unreadable kind) and the 422 show the generic copy in the error slot, no panel, one POST, the stored basket untouched; a mixed price+allergen 409 re-renders the allergen panel unticked and submits once after re-tick + Accept; an allergen-only 409 keeps the 31.1 path"
    requirement: "RWO-007"
    verification:
      - kind: unit
        ref: "basket-changed.test.tsx (the it.each malformed block, the 422 arm, the mixed arm, the allergen-only guard) inside the 28-suite targeted run 321/0"
        status: pass
    human_judgment: false
  - id: D4
    description: "The vendor-reprice race as an executable browser spec, with an INSTRUMENT arm proving the panel assertion can fail"
    requirement: "RWO-007"
    verification:
      - kind: e2e
        ref: "npx playwright test e2e/basket-revalidation.spec.ts --list -> 4 tests in 1 file (race + INSTRUMENT, mobile and desktop), rc 0. NOT RUN: the RED (pre-rebuild) and GREEN runs belong to the 37-38 gate against rebuilt images, as the plan's acceptance criterion says"
        status: deferred
    human_judgment: false
  - id: D5
    description: "Whole frontend green on the final tree; the test-count loop green"
    verification:
      - kind: other
        ref: "jest full 231 suites / 2717 tests / 0 failed rc 0; tsc --noEmit (full program incl. tests and e2e) rc 0 with 0 errors, fail arm: a scratch file with a string-to-number assignment -> TS2322 rc 2, file removed; next build rc 0 (tsconfig.build.json, shipped code); eslint . rc 0, 0 errors / 31 warnings, the plan's files carry 1 warning (page.tsx:482 unused router, f1876ded4 2026-04-19, pre-existing); docs-freshness.sh rc 0 (fail arm: HEAD's 5639 manifest -> rc 1); check-doc-metrics.sh rc 1 on 15 claims before the prose edit, rc 0 37/37 after; check-branch-behind-base.sh rc 0, 0 behind origin/main 078c0c11"
        status: pass
    human_judgment: false

duration: 1h 07m of execution (12:25Z-12:54Z commits, then the close-out session 15:20Z-15:45Z)
completed: 2026-10-12
status: complete
---

# Phase 37 Plan 34: The checkout shows what changed in the basket and makes the customer accept it Summary

**The customer now sees the new price and decides before paying it. The checkout sends the price each line showed (`expectedUnitPricePennies`) and the total on the Place order button (`expectedTotalPennies`), as D-16 requires. If 37-33's 409 `basket-changed` comes back, the page shows `BasketChangedPanel` directly above the allergen panel. The panel lists every change in UI-SPEC C1's words, for example "Suya · £9.00 → £14.00" with "was £9.00, now £14.00" for screen readers. A removed line, which the server sends without a name, is named from the basket's own copy. Place order is refused until the customer presses "Accept changes", and the resubmit reuses the same Idempotency-Key, so any tap sequence yields one order. The stored basket is rewritten to the server's values through the cart provider's own write path. That path leaves the ownership stamp alone, which a test asserts on the stored payload through a session lapse and a sign-in (R-16). Task 2's break arm, a whole-cart-object write, turns that test red. A 409 that also carries the 31.1 allergen properties re-renders the allergen panel unticked. An allergen-only 409 stays on the 31.1 path.**

## Performance

- **Duration:** the original execution ran 12:25Z (the 37-33 gitleaks commit) to 12:54Z (Task 3). That session ended with a /clear before it wrote the SUMMARY or the tracking. The close-out session ran 15:20Z-15:45Z: it re-verified everything, ran the plan's break arms and reconciled the metrics.
- **Tasks:** 3 of 3. Task 1 was a tracer and Task 2 auto, both TDD. Task 3 was auto.
- **Files:** 17 outside `.planning/` across the six commits (code diff about 130 KB, 2126 insertions and 39 deletions including the RED evidence), plus the two RED evidence records and this SUMMARY.

## Accomplishments

- **Task 1 (tracer, TDD).** RED commit `6f5d9ada`: the panel suite and the checkout suite ran against a scaffold that rendered null. 153 ran, 123 passed and 30 failed, classified `RED_EVIDENCE_OK`. GREEN commit `52f030d9`:
  - the panel itself;
  - the D-16 fields on every line and on the body;
  - `readBasketChangedProblem`, checked before the other 409 readers, and `readBasketConfirmationRequiredProblem`;
  - the accept gate, placed ahead of the allergen gate in the submit handler;
  - an explicit same-key reuse after a basket-changed 409.
- **Task 2 (TDD).** RED commit `4b761d8f` ran against no-op scaffolds: 321 ran, 313 passed and 8 failed, `RED_EVIDENCE_OK`. GREEN commit `116a5306`:
  - `applyBasketChanges` (pure, and it returns the same array when nothing changes);
  - `applyServerChanges` (a functional `setItems`);
  - the page applies the diff and keys the panel's names on the basket as seen;
  - on a mixed 409 it holds the server's allergen set as the override, unticks the box, and tells the basket-signature effect that the server made the change.
- **Task 3.** Commit `ee19c814`: `basket-revalidation.spec.ts`. It works from the live seeded price (Suya Platter £12.00, not the integration-test fixture's £9.00) and asserts the 409 on the wire. It checks the stored owner stamp and, with a response counter, that the refused second press sends no second POST. It counts the vendor's orders to show exactly one exists at the new total. It restores the product price in a `finally` and cancels every order it places. An INSTRUMENT arm runs the same flow without the vendor edit and the panel must not appear.

## Task Commits

1. **Task 1 RED: the basket-changed panel and the D-16 submit are RED** — `6f5d9ada` (test)
2. **Task 1 GREEN: the checkout sends what the customer saw and shows what the server found changed** — `52f030d9` (feat)
3. **Task 2 RED: the basket rewrite that keeps its owner is RED** — `4b761d8f` (test)
4. **Task 2 GREEN: the basket follows the server's facts and keeps its owner** — `116a5306` (feat)
5. **Task 3: the vendor-reprice race as a browser spec, run by the 37-C gate** — `ee19c814` (test)
6. **Close-out: the test counts follow the tree after 37-34** — `8f576254` (docs)

`commits: 6` is `git rev-list --count 92b4b972..8f576254`, measured from the original run's persisted ledger `.git/gsd-plan-head-before-37-34`.

## TDD Gate Compliance

| Gate | Task 1 | Task 2 |
|------|--------|--------|
| RED (`test(37-34)`) | `6f5d9ada`: 153 ran / 30 failed, `RED_EVIDENCE_OK` (`evidence/37-34-red-task1.json`). The failures were missing panel elements and an absent D-16 body, against a scaffold that exported the names and rendered null (the 37-30 precedent), so there was no load or compile fault | `4b761d8f`: 321 ran / 8 failed, `RED_EVIDENCE_OK` (`evidence/37-34-red-task2.json`), against no-op `applyBasketChanges` / `applyServerChanges` |
| GREEN (`feat(37-34)`) | `52f030d9` | `116a5306`. Re-run in the close-out: targeted 28 suites 321/0 and full jest 2717/0 |
| REFACTOR | none | none |
| Plan's named break arm (close-out) | Accept gate disabled: 2 of 16 failed, exactly the refusal arm and the tap-landing arm | Whole-object write: 1 of 22 failed, the F15 lapse arm |

The plan names a specific fail arm for each task: "a page that submits without the accept gate" for F14 and "an implementation that rewrites the whole cart object" for F15. The RED commits ran against scaffolds, not those implementations, so the close-out ran both arms. Before the arms, a hash check confirmed the working-tree files matched HEAD. After each arm, `git checkout -- <file>` restored the file and sha256 matched again (`page.tsx` 3f483c64…, `cart-provider.tsx` 31a76b8f…). The closing clean run was 321/0 with `sha256sum -c` OK for both files.

## Verification (close-out, on the final tree)

| Command | rc | Result |
|---|---|---|
| `npx jest components/storefront "app/shop/\[slug\]/checkout"` (the plan's Task 1/2 verify) | 0 | 28 suites, 321 tests, 0 failed |
| `npx jest` (full) | 0 | 231 suites, 2717 tests, 0 failed, 2 snapshots |
| `npx tsc --noEmit` (full program incl. tests and e2e, the CI step) | 0 | 0 errors. Fail arm: rc 2 with TS2322 on a scratch file |
| `npm run build` (`next build`, tsconfig.build.json) | 0 | all routes built |
| `npm run lint` | 0 | 0 errors / 31 warnings (one in this plan's files, pre-existing) |
| `npx playwright test e2e/basket-revalidation.spec.ts --list` | 0 | 4 tests in 1 file |
| `scripts/docs-freshness.sh` | 0 | 5686. Fail arm with HEAD's manifest: rc 1 |
| `scripts/check-doc-metrics.sh` | 1, then 0 | 15 FAIL claims before the prose edit, then 37/37 |
| `scripts/check-branch-behind-base.sh` | 0 | 0 behind origin/main (078c0c11) |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The docs-freshness loop was left uncommitted and the prose counts were stale**
- **Found during:** close-out.
- **Issue:** The original run left `docs/metrics.json` regenerated but uncommitted (2672 → 2717 jest blocks, 229 → 231 files, 159 → 161 Playwright blocks, 39 → 40 specs, 5639 → 5686 total). README, CLAUDE.md and AGENTS.md still quoted the old figures, so `check-doc-metrics.sh` failed rc 1 on 15 claims.
- **Fix:** I re-ran `scripts/docs-freshness.sh --write`, and the output was byte-identical to the uncommitted copy (`diff` rc 0). I then updated the six prose spellings.
- **Verification:** `docs-freshness.sh` rc 0 and `check-doc-metrics.sh` rc 0 (37/37).
- **Committed in:** `8f576254`

### Plan wording that did not hold (recorded, not relabelled)
- **files_modified.** The plan names seven files. The implementation also touched three more:
  - `types/storefront.ts`: the problem types and `BASKET_CHANGED_PROBLEM_TYPE`.
  - `lib/checkout-idempotency.ts` and its test: the two D-16 fields signed into the intent, absent for a caller without them, so the pre-37-32 fingerprint is unchanged.
  - Three existing checkout suites that pin the exact submit body (`allergen-attribution`, `checkout-idempotency`, `checkout-seller-block`). Each pinned shape gains only the two D-16 fields. I read the diff in the close-out: no assertion was removed or loosened, and each test's own claim is unchanged.
- **"red against an implementation that rewrites the whole cart object (recorded)".** This arm was not in the original run's evidence, because its RED was against a no-op scaffold. The close-out ran it (see TDD Gate Compliance). Only the lapse arm fails. The sign-in arm is a same-person sign-in, so a whole-object write stamping the current identity writes A either way. That is consistent with the arm's purpose, not a gap: the lapse is the R-16 transition.

**Total deviations:** 1 auto-fixed (blocking gate) and 2 plan-wording records. **Impact:** none on behaviour.

## Issues Encountered

- The original executor's transcript is gone, so every result above comes from the close-out run. Commit bodies are cited only for what they contain (the RED evidence records are committed files).
- `.planning/milestone.lock` (untracked, belonging to dead pid 80992) was left alone and not committed.

## Notes for 37-38 (the 37-C gate)

- Run `frontend/e2e/basket-revalidation.spec.ts` against the 37-D images first (RED expected: no panel, an order placed at the repriced total or a bare error), then rebuild and run it GREEN. Run the INSTRUMENT arm in both.
- The spec reads the live seeded price of the Suya Platter at Peckham Jollof Co (£12.00) and raises it through the vendor's real PUT. It restores the price in a `finally` and reads it back, and cancels every order it places by number (`order-cleanup.ts`). It skips with a named reason when `KEYCLOAK_CLIENT_SECRET` or the vendor credentials are absent (source the stack's `.env`).
- `check-openapi-snapshot-fresh.sh` stays red until that rebuild, as 37-32 and 37-33 recorded.
- Rebuild the frontend image as well. The panel, the D-16 body and `applyServerChanges` exist only in source until then.

## Known Stubs
None. The stub scan over the added lines found only the spec's credential-conditional `test.skip(...)` calls. Each names its missing credential, which is the project's e2e precedent for a stack-dependent spec, not a stub.

## Ledger
No WINDOWS entry added. The one unrun verify, the Playwright race, is not a defect that outlives the plan: the plan assigns its runs to 37-38 by design.

## Threat Flags

None new.
- **T-37-83** (owner stamp lost or swapped on the update) is mitigated by D2: the write goes through the owner-preserving serialize, the stored-content assertion runs through a lapse and a sign-in, and the whole-object break arm turns it red.
- **T-37-84** (duplicate order from rapid taps around the 409) is mitigated by D1: same-key reuse, the refusal before Accept, a tap landing as the 409 returns meets the refusal (that arm reds when the gate is disabled), and in the e2e an order count by API of exactly one, deferred to 37-38.
- **T-37-SC** held: nothing installed, `package-lock.json` untouched.

## User Setup Required

None.

## Next Phase Readiness

- **37-35** (wave 28) is next.
- **37-38** runs the browser spec RED, then GREEN, after the rebuild.
- **Requirements:** RWO-007 is declared by 37-32, 37-33, this plan and 37-38, and stays unticked until the 37-C gate.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-12*

## Self-Check: PASSED

- FOUND: `frontend/components/storefront/basket-changed-panel.tsx` (contains "Your basket has changed since you opened it"), `frontend/e2e/basket-revalidation.spec.ts` (contains "Accept changes"), `applyServerChanges` in `cart-provider.tsx` and `page.tsx`, both RED evidence records
- FOUND commits (ancestors of HEAD): `6f5d9ada`, `52f030d9`, `4b761d8f`, `116a5306`, `ee19c814`, `8f576254`
