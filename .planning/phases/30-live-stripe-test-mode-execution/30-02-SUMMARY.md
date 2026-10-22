---
phase: 30-live-stripe-test-mode-execution
plan: 02
subsystem: payments
tags: [stripe, nextjs, react, checkout, fail-loud, a11y, issue-957]

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-01: frontend/lib/stripe-loader.ts (publishableKey, loadStripeOnce, STRIPE_APPEARANCE, resetStripeLoaderForTests)"
provides:
  - "frontend/components/storefront/card-payment-form.tsx: CardPaymentForm({ slug, orderNumber, customerEmail, totalPennies, returnUrl, onSucceeded, onLoadError })"
  - "Checkout step 2 driven by a load state loading | ready | failed; render guard is `if (paymentState)`"
  - "Pre-POST refusal: card shop + no publishable key -> role=alert #card-payments-unavailable, Place order disabled, no POST"
  - "ORDER_STATUS_COPY.DRAFT { label 'Awaiting payment', desc 'Waiting for your payment' }; order page status line reads 'Awaiting payment' for a DRAFT"
affects: [30-03, 30-04, 30-18-pay-link-page, phase-30-verify]

actuals:
  tokens: 17200
  tasks: 3
  commits: 7
plan_head_before: 1748670b15018481cf3014cc7f42b25cd8327278
plan_head_after: 0b9a0aedb5c0d8c611a791ea01e8356ab2a9f314

tech-stack:
  added: []
  patterns:
    - "A Stripe-backed step renders from an explicit load state (loading/ready/failed), never from whether a Stripe promise exists"
    - "Only the latest load attempt may set the load state (attempt counter ref), so a slow first load cannot overwrite a retry's answer"
    - "A disabled submit lists every on-screen explanation in aria-describedby, in reading order, and the submit handler refuses too"

key-files:
  created:
    - frontend/components/storefront/card-payment-form.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/card-unavailable.test.tsx
    - frontend/app/shop/[slug]/orders/[orderNumber]/__tests__/order-draft-awaiting-payment.test.tsx
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-02-red-task1.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-02-red-task2.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-02-red-task3.json
  modified:
    - frontend/app/shop/[slug]/checkout/page.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/stripe-lazy-load.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/checkout-seller-block.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/vat-preview.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/vat-registration.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/abuse-refusals.test.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/checkout.test.tsx
    - frontend/__tests__/contrast-literals.test.ts
    - frontend/lib/order-status-announcement.ts
    - frontend/app/shop/[slug]/orders/[orderNumber]/page.tsx
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md

key-decisions:
  - "Step 2 renders whenever a confirmation carries a clientSecret; the payment-details card shows loading, the Payment Element, or the 'Card payment couldn't load' alert"
  - "Try again is offered only when publishableKey() is non-null; it re-runs loadStripeOnce, which forgets a null or rejected load"
  - "The pre-POST refusal keys on acceptsCardPayments === true, so a shop that sends no field is not refused and the post-POST failure state still catches a keyless confirmation"
  - "CardPaymentForm keeps slug and orderNumber in its props (the plan's contract) without deriving anything from them; the host builds returnUrl and owns onSucceeded"
  - "The moved 'Secured by Stripe' text-slate-400 line keeps its colour (UI-SPEC: moved, not restyled) and its contrast-ledger entry moves with it, as floating-cart-bar's did (#718)"

patterns-established:
  - "Card step load state: loadStripeOnce() null -> failed; promise -> loading, then non-null Stripe -> ready, null or rejection -> failed; PaymentElement onLoadError -> failed"

requirements-completed: [PAY-04, PAY-03]

coverage:
  - id: D1
    description: "After a 201 with a clientSecret, step 2 always renders: no key -> Failed (no Try again, 0 loads); Stripe.js null or rejected -> Failed with Try again; pending -> skeleton + role=status; onLoadError -> Failed; never the step-1 form"
    requirement: PAY-03
    verification:
      - kind: unit
        ref: "frontend/app/shop/[slug]/checkout/__tests__/card-unavailable.test.tsx#post-POST arms (5)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Try again re-runs the load: after a null load the retry calls loadStripe a second time and mounts the form"
    requirement: PAY-03
    verification:
      - kind: unit
        ref: "card-unavailable.test.tsx#when Stripe.js resolves null, the Failed alert offers Try again, and Try again loads again"
        status: pass
    human_judgment: false
  - id: D3
    description: "CardPaymentForm extracted; Ready state mounts Elements with STRIPE_APPEARANCE (#c2410c) and the order's clientSecret; a successful payment still saves locally, clears the basket and routes to the order page with return_url /shop/{slug}/orders/{orderNumber}"
    requirement: PAY-03
    verification:
      - kind: unit
        ref: "card-unavailable.test.tsx#Ready state arms (2)"
        status: pass
      - kind: other
        ref: "rg -uu -n f97316 page.tsx card-payment-form.tsx -> rc=1; rg -uu -c '<h1' card-payment-form.tsx -> rc=1"
        status: pass
    human_judgment: false
  - id: D4
    description: "A card shop with no publishable key sends no order: the card-payments-unavailable alert at the top of step 1, Place order disabled with aria-describedby (both ids when the shop is also not taking orders), the How you'll pay line 'Pay by card — unavailable right now.'; a forced submit sends nothing"
    requirement: PAY-04
    verification:
      - kind: unit
        ref: "card-unavailable.test.tsx#pre-POST arms (4)"
        status: pass
      - kind: unit
        ref: "stripe-lazy-load.test.tsx#property 4 (rewritten) + properties 1-3 unchanged"
        status: pass
    human_judgment: false
  - id: D5
    description: "The customer order page reads 'Awaiting payment' for a DRAFT; ORDER_STATUS_COPY.DRAFT added; PENDING unchanged"
    requirement: PAY-04
    verification:
      - kind: unit
        ref: "frontend/app/shop/[slug]/orders/[orderNumber]/__tests__/order-draft-awaiting-payment.test.tsx (4)"
        status: pass
    human_judgment: false
  - id: D6
    description: "docs/metrics.json and the prose counts in README.md, CLAUDE.md, AGENTS.md match the tree"
    verification:
      - kind: other
        ref: "bash scripts/docs-freshness.sh && bash scripts/check-doc-metrics.sh -> rc 0 (37/37 claims)"
        status: pass
    human_judgment: false
  - id: D7
    description: "The three step-2 states and the step-1 notice look right in a real browser at 360px and desktop (amber block, skeleton height, no layout shift when the iframe mounts, the #c2410c tab label inside the Stripe iframe)"
    verification: []
    human_judgment: true
    rationale: "Only Jest/jsdom was run; no browser render or screenshot was taken in this plan, and axe cannot see into the Stripe iframe (UI-SPEC backstop). The staging browser E2E in later Phase 30 plans is where this is observed."

duration: 16min
completed: 2026-10-22
status: complete
---

# Phase 30 Plan 02: The Card Checkout Fails Loud Summary

**A card customer can no longer be sent back to the order form after an unpaid DRAFT is created. Step 2 now always renders and shows one of three things: a loading skeleton, the Stripe form, or an amber "Card payment couldn't load" alert that names the unpaid order (with "Try again" when a key exists). A card shop in a build with no publishable key refuses to send the order and says why at the top of step 1. The card form is now a shared `CardPaymentForm`, and an unpaid DRAFT reads "Awaiting payment" on the order page.**

## Performance

- **Duration:** 16 min
- **Started:** 2026-10-22T03:51:48Z
- **Completed:** 2026-10-22T04:08:04Z
- **Tasks:** 3/3
- **Files modified:** 20 (incl. 3 evidence records), 908 insertions, 156 deletions

## Accomplishments

- **#957 cannot recur silently after the POST.** The render guard is `if (paymentState)`, and step 2's card is driven by `cardLoadState`. Removing that one change (restoring `paymentState && stripePromise`) reds the key-absent arm.
- **No unpayable DRAFT is created before the POST.** `cardUnavailable = shop?.acceptsCardPayments === true && publishableKey() === null` disables Place order and puts the reason on screen (K3 rule). The submit handler also returns early: removing that guard alone makes the forced-submit arm see 1 POST.
- **CardPaymentForm** (`components/storefront/card-payment-form.tsx`): the old `PaymentForm` markup, classes and copy, plus `returnUrl`, `onSucceeded` and `onLoadError`. Plan 30-18's `/pay` can reuse it.
- **STRIPE_APPEARANCE** (`#c2410c`) replaces the inline `#f97316` appearance.
- **DRAFT copy:** "Awaiting payment" on the order page's status line, and `ORDER_STATUS_COPY.DRAFT` for the announcement. Tracker steps unchanged.
- **Every other step-2 good is kept and asserted:** the allergen record, SellerBlock, totals, the Pay CTA, "Secured by Stripe", `redirect: "if_required"`, the return URL, and key rotation on "Back to details".

## Task Commits

1. **Task 1 RED:** `66846786` test(30-02): add failing tests for the card step's loading, ready and failed states (#957)
2. **Task 1 GREEN:** `2dc080ac` feat(30-02): a card order always reaches the payment step, which shows the form or a stated failure (#957)
3. **Task 2 RED:** `12a33e18` test(30-02): add failing tests for refusing a keyless card order before it is sent (#957)
4. **Task 2 GREEN:** `be9c0cd9` feat(30-02): a card shop with no publishable key refuses to place the order, and says why (#957)
5. **Task 3 RED:** `cccb10ec` test(30-02): add failing tests for "Awaiting payment" on an unpaid DRAFT order (#957)
6. **Task 3 GREEN:** `c945db1c` feat(30-02): an unpaid DRAFT reads "Awaiting payment" on the customer order page (#957)
7. **Fix:** `0b9a0aed` fix(30-02): carry the Secured-by-Stripe contrast ledger entry with the card form (#957)

No REFACTOR commit was needed.

## TDD Gate Compliance

Each task's RED commit comes before its GREEN commit (66846786 → 2dc080ac, 12a33e18 → be9c0cd9, cccb10ec → c945db1c). Every RED record went through `gsd_run check tdd-red-evidence` and came back `RED_EVIDENCE_OK / target_test_failed` (TAP from Jest's real results, using the 30-01 scratch reporter).

- **Task 1** (`evidence/30-02-red-task1.json`): 6 of 7 arms fail on their planned assertions.
  - Four fail with "Unable to find … Card payment couldn't load".
  - One fails with "Unable to find role=status".
  - One fails on colour: expected `#c2410c`, received `#f97316`.
  - Target: the key-absent arm.
  - Semantic check: on the pre-fix tree the guard fell through to the step-1 form, which is the defect itself. The one passing arm is the successful-payment regression guard, green by design.
- **Task 2** (`evidence/30-02-red-task2.json`): 3 of 15 arms fail, each on the notice's presence: the keyless notice and no-POST arm, the both-explanations arm, and rewritten property 4. The regression arms and Task 1's arms pass.
- **Task 3** (`evidence/30-02-red-task3.json`): 2 of 4 arms fail.
  - The DRAFT status line: "Unable to find … Awaiting payment".
  - `ORDER_STATUS_COPY.DRAFT`: received `undefined`.
  - Both PENDING arms pass, as they should.
  - The page had rendered (shop name found) before the status line was asserted, so this is not a harness fault.

## Verification (both directions)

| Check | Fail direction (broken input) | Pass direction (tree) |
|---|---|---|
| T1 verify (4 suites) | Pre-fix tree: card-unavailable 6/7 red. Break arms on the committed tree: old guard restored → key-absent arm red (1 failed); `onLoadError` unwired in the form → onLoadError arm red | `Tests: 32 passed`, 4 suites, rc 0 |
| T1 `f97316` grep | Control file containing the string → rc 0, line printed; pre-fix page (`git show 1748670b:…`) → rc 0, l.1349 | page + card-payment-form → rc 1 |
| T1 `<h1` grep | `checkout/page.tsx` (has h1s) → rc 0, count 2 | card-payment-form → rc 1 |
| T2 verify (3 suites) | Pre-fix: 3 red. Break arms: submit-handler guard removed → "Received number of calls: 1"; aria-describedby order swapped → exact-string arm red | `Tests: 32 passed`, rc 0 |
| T2 lint | Planted `onClick` on the notice `<p>` → `npm run lint` rc 1 with jsx-a11y errors | rc 0 (0 errors; 28 pre-existing warnings, none in touched files) |
| T3 verify (2 suites) | Pre-fix: 2 red. Break arm: DRAFT branch disabled → DRAFT arm red | `Tests: 12 passed`, rc 0 |
| T3 counts gate | Before `--write`: docs-freshness rc 1. On the committed tree, `jest_blocks` set back to its HEAD value 3157 → rc 1. check-doc-metrics before the prose edit: rc 1, 10 FAIL lines | Both rc 0. `docs-freshness OK … 6536`; `PASS: all 37 prose metric claim(s)` |
| Tsc (full program) | `returnUrl: number` planted in card-payment-form → errors reported in that file and in page.tsx | No errors outside the 5 known `layout-widths-css.test.ts` errors |
| Contrast ledger | Before 0b9a0aed: "every unregistered text colour" red, naming `card-payment-form.tsx:119 text-slate-400` | Green; the suite is back to its 2 baseline failures |
| Full Jest | n/a | 3157/3172 pass. The 15 failures match the 30-01 baseline list exactly (`diff` of failing names is empty): 3 Tailwind suites, stale node_modules |
| Other docs gates | n/a | check-test-block-counter, check-doc-versions, check-project-version, check-claims and check-changelog-contract: rc 0 |
| env contract / single reader | n/a | check-env-contract rc 0; `process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` read only in `lib/stripe-loader.ts` |

Every break arm ran as clean → arms → clean, against committed files. Restores were verified by sha256: identical before and after, and `git diff` is empty.

## Decisions Made

- **Load state, not promise state.** `startCardLoad()` (a `useCallback`, declared above the page's early returns) uses an attempt counter. A slow load that settles after "Try again" cannot overwrite the newer answer.
- **The Failed state renders when the key is absent, even with no promise.** `cardLoadState === "failed" || !stripePromise`, so the no-key path cannot reach the skeleton by mistake.
- **The pre-POST guard keys on `=== true`.** An older backend that sends no field is not refused before the POST. The post-POST alert is the backstop, and card-unavailable's post-POST arms use exactly that shop.
- **The contrast ledger line moves with the component.** The site is unchanged, and restyling the moved fine print is out of scope under UI-SPEC "moved, not restyled".

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Property 4 rewrite split across Tasks 1 and 2**
- **Found during:** Task 1 RED design.
- **Issue:** The plan puts the final property 4 (keyless card shop → POST count 0, Place order disabled) in Task 1's RED. Its GREEN, though, is Task 2's pre-POST guard. The old property 4 also cannot pass after Task 1: it waits for Place order to re-enable after the POST, and that is exactly the fall-through Task 1 removes.
- **Fix:** In Task 1 (`2dc080ac`), property 4 settles on the stated failure instead of a re-enabled Place order, and still asserts 0 loads. Task 2's RED (`12a33e18`) rewrites it to the final pre-POST form. Every commit's suites are green on that commit.
- **Files:** `stripe-lazy-load.test.tsx`

**2. [Rule 3 - Blocking] Existing card-step harnesses mocked a NULL Stripe load**
- **Found during:** Task 1 GREEN.
- **Issue:** `checkout-seller-block`, `vat-preview` and `vat-registration` mock `loadStripe` as `Promise.resolve(null)`. Null is Stripe.js's failure value, so those suites now (correctly) got the Failed state and lost `stripe-elements` / "Payment details". This is the same trap 30-01 hit in the lazy-load harness.
- **Fix:** Those three mocks now resolve `{ __fakeStripe: true }`. No assertion changed.
- **Committed in:** `2dc080ac`

**3. [Rule 3 - Blocking] Keyless card-shop arms in other suites**
- **Found during:** Task 2 GREEN.
- **Issue:** `abuse-refusals` (2 arms) and `checkout.test` ("shows the card notice") run a card shop with no key. Those arms are now refused before the POST, or show the unavailable line.
- **Fix:** Those suites set a `pk_test_` key and restore the env in `afterEach`, as a deployed card shop has. No assertion changed. The keyless behaviour is asserted in card-unavailable.
- **Committed in:** `be9c0cd9`

**4. [Rule 3 - Blocking] contrast-literals ledger after the extraction**
- **Found during:** Full Jest sweep.
- **Fix:** Registered `components/storefront/card-payment-form.tsx::text-slate-400` with a comment, following the extraction precedent. Details are under Decisions.
- **Committed in:** `0b9a0aed`

**5. [Rule 2 - Missing critical] Extra test arms beyond the plan's list**
- A Stripe.js REJECTION arm (the plan named only null and pending).
- A Ready-state arm asserting STRIPE_APPEARANCE and the clientSecret.
- A successful-payment arm: confirmPayment params, saveLocalOrder, router.push, cart cleared. It guards the `onSucceeded` move.

---

**Total deviations:** 5 auto-fixed (4 blocking, 1 missing critical). **Impact:** all are harness or gate consequences of the planned behaviour change. No assertion that protects an existing good was weakened, and nothing is out of scope.

## Issues Encountered

- The known environment baseline still applies: stale `frontend/node_modules` (tailwindcss 3.4.19 against locked 4.3.3) causes 15 Jest failures in 3 Tailwind suites and 5 tsc errors in `layout-widths-css.test.ts`. I did not run `npm ci` (shared checkout). The failing-test names are identical to the 30-01 baseline run.
- Adding `ORDER_STATUS_COPY.DRAFT` also means `/track`'s `statusUpdateAnnouncement` would announce a move into DRAFT. No order moves into DRAFT (it is the first state), so nothing changes in practice.

## Known Stubs

None.

## Threat Flags

None. No new network surface. T-30-06 (no POST, asserted 0), T-30-07 (the copy states the order is unpaid) and T-30-08 (lazy-load properties 1 to 3 unchanged and green) are mitigated as planned.

## User Setup Required

None.

## Next Phase Readiness

- **For 30-03:** once the repository variable holds a staging `pk_test_` key and the frontend is redeployed, a staging card order should reach the Payment Element. If the key is absent, the checkout now shows "Card payments are unavailable" (card shop) instead of bouncing. That makes the live read in 30-03/30-04 easy to judge by eye.
- **Not in this plan; the orchestrator should place it:** UI-SPEC § S1 "Connected-account initialisation". `GuestOrderConfirmation.stripeAccountId` → `loadStripeOnce(stripeAccountId)` is needed before a direct-charge (`DIRECT_CHARGE_VENDOR_MOR`) browser order can confirm. `startCardLoad()` currently calls `loadStripeOnce()` with no account, and that is the one line to extend.
- **At PR time:** `docs/CHANGELOG.md` needs the entry citing the PR number (inherited from 30-01).
- **Branch:** `git log HEAD..origin/main` is empty (local ref, not re-fetched).

---
*Phase: 30-live-stripe-test-mode-execution*
*Completed: 2026-10-22*

## Self-Check: PASSED

- All 6 created files present; all 8 commits (66846786, 2dc080ac, 12a33e18, be9c0cd9, cccb10ec, c945db1c, 0b9a0aed, 7db49310) are ancestors of HEAD.
- STATE.md, ROADMAP.md and REQUIREMENTS.md untouched (0 files in 1748670b..HEAD).
