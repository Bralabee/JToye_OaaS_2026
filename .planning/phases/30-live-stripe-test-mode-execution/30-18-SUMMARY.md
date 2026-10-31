---
phase: 30-live-stripe-test-mode-execution
plan: 18
subsystem: payment-links-frontend
tags: [stripe, payment-link, pay-04, pay-03, d-03, fragment, history-state, connect, direct-charge, ui-spec-s3]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-16: POST /api/v1/public/payment-links/preview {token} and /redeem {token}; links issued as /pay#token= (owner decision fragment)"
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-17: stripeAccountId on GuestOrderConfirmation, BalancePaymentSessionDto and RedeemedPaymentLinkDto"
provides:
  - "frontend/app/pay/page.tsx: metadata-only shell (title 'Pay for your order — J'Toye', noindex,nofollow, no canonical/OG/JSON-LD) wrapping PayClient in PublicShell; fetches nothing, never renders the token"
  - "frontend/app/pay/pay-client.tsx: PayClient, the /pay state machine (checking, checkFailed, active(+notUsed), opening, preparing, paying, linkOpened, dead used|gone|notFound, alreadyPaid, processing, received, redirectFailed, noLink); exports PAY_PAGE_COPY and PAY_RESUME_KEY = 'jtoyePayResume'"
  - "Mount order: #token= fragment -> preview; else Stripe's redirect query -> intent status on ?acct=; else history.state.jtoyePayResume -> resume; else 'Open your payment link'"
  - "Press order: publishableKey() -> awaited loadStripeOnce() -> redeem -> replaceState({...history.state, jtoyePayResume}, '', '/pay') -> loadStripeOnce(stripeAccountId) -> retrievePaymentIntent -> Elements + CardPaymentForm"
  - "types/storefront.ts: PaymentLinkPurpose, PaymentLinkState, PaymentLinkPreview, RedeemedPaymentLink; GuestOrderConfirmation.stripeAccountId?: string | null"
  - "CardPaymentForm omits receipt_email when customerEmail is blank"
  - "Checkout: startCardLoad(stripeAccount?) and paymentState.stripeAccountId; loadStripe(pk, { stripeAccount }) for a direct charge, loadStripe(pk) otherwise, Try again keeps the account"
affects: [30-19, 30-20]

estimate:
  tokens: 90000
  tasks: 3
actuals:
  tokens: 33422     # chars/4 over git diff 2f792191..4e545222 (code, tests, evidence incl. TAP records, counts); excludes this SUMMARY
  tasks: 3
  commits: 10       # MEASURED: git rev-list --count 2f792191..HEAD before this SUMMARY's own commit
plan_head_before: 2f792191e00273213b48165165e26269f3b6fae8
plan_head_after: 4e545222ae0229cd80fae80560eb09e72ece9abc

tech-stack:
  added: []
  patterns:
    - "A single-use bearer link is spent only after the thing it pays through has loaded: key check, awaited loader, then the consuming call; the test compares invocationCallOrder, not just counts"
    - "A reload resume lives in the tab's history.state entry, spread over the router's own keys, never in web storage; it carries the PaymentIntent client secret (the value Stripe itself puts in return_url) and display fields, never the link token"
    - "A resume arm that seeds history.state itself cannot see what the page writes; a round trip (press, unmount, mount on the same entry) is what proves the writer"

key-files:
  created:
    - frontend/app/pay/page.tsx
    - frontend/app/pay/pay-client.tsx
    - frontend/app/pay/__tests__/pay-client.test.tsx
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-18-red-task1.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-18-red-task2.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-18-red-task3.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-18-task2-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-18-verify.txt
  modified:
    - frontend/types/storefront.ts
    - frontend/components/storefront/card-payment-form.tsx
    - frontend/app/shop/[slug]/checkout/page.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/card-unavailable.test.tsx
    - frontend/__tests__/link-graph.test.ts
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "Fragment decision applied: one route, app/pay/page.tsx, serves the link (#token=), Stripe's return and the reload; no app/pay/[token]/page.tsx is created"
  - "The fragment stays in the address bar until redeem succeeds (UI-SPEC: a reload of an unused link still works); unlike /invite, it is not stripped on mount"
  - "The 'link hasn't been used' state keeps the amount block and replaces Continue with Try again, which re-runs the whole press (key check, load, then redeem)"
  - "Redeem failures with no HTTP answer, 5xx, or a 200 without a client secret read as 'link has now been opened': the server may have spent it"
  - "After a Stripe redirect the page knows only the intent, so 'Payment received' says 'You've paid £X. You can close this page.' (no shop or order it cannot name)"
  - "A failed Stripe.js load during resume keeps the entry (a later reload can still find the intent); an unreadable intent drops it"

requirements-completed: [PAY-04, PAY-03]

coverage:
  - id: D1
    description: "The customer sees shop, purpose heading, amount, order, London expiry and the single-use note before anything is spent; no Stripe request before the press"
    requirement: PAY-04
    verification:
      - kind: test
        ref: "pay-client.test.tsx mount, active-link and purpose arms; evidence/30-18-verify.txt V2 37/37 rc 0"
        status: pass
    human_judgment: false
  - id: D2
    description: "Redeem runs only after publishableKey() and an awaited loadStripeOnce(); every pre-redeem failure leaves the link unused"
    requirement: PAY-04
    verification:
      - kind: test
        ref: "ordering arm (invocationCallOrder) + no-key, null-load, rejected-load arms"
        status: pass
      - kind: other
        ref: "evidence/30-18-task2-arms.txt arm A: redeem moved first -> 4 arms red; clean 37/37 before and after"
        status: pass
    human_judgment: false
  - id: D3
    description: "Token hygiene and the reload resume: replaceState to /pay before Stripe mounts, history.state resume entry without the token and with Next's keys kept, no storage writes, a reload remounts the form"
    requirement: PAY-04
    verification:
      - kind: test
        ref: "direct-charge arm (replaceState args), storage arm (Storage.prototype.setItem spy with a control write), resume arms, reload round trip"
        status: pass
      - kind: other
        ref: "evidence/30-18-task2-arms.txt arm B: replaceState(null) -> 3 arms red, the round trip on 'Open your payment link'"
        status: pass
    human_judgment: false
  - id: D4
    description: "Dead, failed and returning links: preview USED/EXPIRED/REVOKED/PAID/404, 429/5xx/network, redeem 409/410/404/502, Element load error, intent succeeded/processing, redirect return, empty state"
    requirement: PAY-04
    verification:
      - kind: test
        ref: "pay-client.test.tsx Task 2 describes; evidence/30-18-red-task2.json (25 arms red at RED)"
        status: pass
    human_judgment: false
  - id: D5
    description: "A direct-charge checkout initialises Stripe.js on the vendor's account; without an account it is loadStripe(pk), one argument"
    requirement: PAY-03
    verification:
      - kind: test
        ref: "card-unavailable.test.tsx 30-18 describe + stripe-lazy-load property 3; evidence/30-18-verify.txt V3 18/18 rc 0; evidence/30-18-red-task3.json"
        status: pass
    human_judgment: false
  - id: D6
    description: "The page is payable through a real browser on staging, including after a reload (Next keeping a custom history.state key across a reload)"
    requirement: PAY-04
    verification:
      - kind: other
        ref: "plan 30-19's staging-pay-link.spec.ts, run live in plan 30-20"
        status: pending
    human_judgment: false

duration: 25min
completed: 2026-10-31
---

# Phase 30 Plan 18: The /pay payment-link page Summary

**A customer who opens a shop's payment link now sees who is asking and for how much before anything is spent. "Continue to payment" spends the single-use link only after Stripe.js has loaded. Payment runs through the checkout's own card form, on the vendor's connected account for a direct charge. Every dead, failed or returning link has its own plain state, and a reload after the link was opened brings the card form back. The checkout now initialises Stripe.js on the vendor's account for a direct-charge order.**

## Performance

- **Duration:** about 25 min, measured from the base commit (`2f792191`, 20:37Z) to the last task commit (`4e545222`, 21:00Z). Reading and `npm ci` came before the first commit.
- **Tasks:** 3 of 3.
- **Commits:** 10 before this SUMMARY: 3 RED, 3 GREEN, and 4 more for tests, evidence and counts. All are on `feat/30-pay-link-page` and not pushed (the push is 30-20's).

## Task results

**Task 1 (tracer, TDD): preview, Continue, pay.**
- **RED (`25126b7f`).** 9 arms. An inert `PayClient` and an empty-metadata page were committed with them, so the suite fails on assertions and not on a missing module.
  - Command: `npx jest app/pay/__tests__/pay-client.test.tsx` with a TAP reporter. rc 1, 9/9 red.
  - The failures are a metadata `toBe`, no `main` landmark, no `role=status`, and no purpose heading (x6).
  - `evidence/30-18-red-task1.json` returned `RED_EVIDENCE_OK`.
- **GREEN (`aecf8193`).**
  - Plan verify (pay-client + stripe-lazy-load): 13/13, rc 0.
  - card-unavailable 11/11, so the CardPaymentForm change left the checkout's `receipt_email` assertion intact.
  - `tsc --noEmit` rc 0. eslint over the touched files: 0 errors.
- **Ordering arm:** `mockedLoadStripe.mock.invocationCallOrder[0] < mockedRedeem.mock.invocationCallOrder[0]`. The `/pay` replaceState also comes before the connected-account `loadStripe` call.
- **Tracer gate:** interactive mode, `human_verify_mode` end-of-phase, automated-only verify. It was re-run on the committed tree: 13/13, rc 0. Then expansion went ahead.
- **Storage search:** over the route source it returns rc 1 with nothing printed. The `app/shop` control returns rc 0 and lists `orders-client.tsx` and `checkout/page.tsx`. See deviation 3 for why the test directory is excluded.

**Task 2 (TDD): every other state.**
- **RED (`12f01182`).** 27 new arms; 25 red, 2 green by design:
  - the declined-card arm is CardPaymentForm's existing behaviour;
  - the long-text arm checks classes Task 1 already set.
  - `RED_EVIDENCE_OK`.
- **GREEN (`9eddb8b7`).** 36/36, `tsc` rc 0, `npm run lint` rc 0 with 0 errors and 27 warnings, none in `app/pay`.
- **Round trip (`8f679a57`).** The arm presses Continue, unmounts, resets the loader memo and mounts again on the same history entry. The form comes back with 1 preview and 1 redeem, on the connected account. 37/37.
- **Break arms (`46f26f86`, `evidence/30-18-task2-arms.txt`).** The bracket ran clean (37/37), then the two arms, then clean again (37/37). Each restore was checked against HEAD by sha256 (`38842cd2…` both times).
  - **A, redeem before the key check and the awaited load:** 4 red. These are the no-key, null-load, rejected-load and ordering arms.
  - **B, `replaceState(null, "", "/pay")`:** 3 red. These are the resume-entry assertion, the already-paid entry drop and the round trip, which lands on "Open your payment link".

**Task 3 (TDD): checkout on the connected account.**
- **RED (`1cf2bc53`).** The target arm fails on deep equality: `loadStripe` received `[pk]` where `[pk, { stripeAccount: "acct_1" }]` was expected. The two key-alone arms pass on the unfixed tree by design: they are the property-3 guard the fix must keep. `RED_EVIDENCE_OK`.
- **GREEN (`a714132a`).**
  - Plan verify: card-unavailable + stripe-lazy-load 18/18, rc 0.
  - The whole checkout directory: 16 suites, 161/161.
  - `tsc` rc 0.

**Wide run, on the final tree:**
- Full Jest: 275 suites, 3259/3259, rc 0.
- `npm run build` rc 0. The TypeScript step ran (not cached), and `/pay` is listed as a route.
- `docs-freshness.sh` was rc 1, then rc 0 after `--write`.
- `check-doc-metrics.sh` was rc 1 with 10 FAIL lines, then rc 0 after the prose edit.
- Counts are now Jest 3259 across 275 files, total 6750.

## Deviations from Plan

**1. [Decision-driven] No `app/pay/[token]/page.tsx`.**
- The owner chose fragment in 30-16, so `app/pay/page.tsx` is the single route.
- The island reads `#token=` from `location.hash` and calls the body routes `POST …/preview {token}` and `POST …/redeem {token}`.
- The plan's frontmatter lists the path route as conditional on the path decision.

**2. [Rule 3 - Blocking] `npm --prefix frontend ci` in this worktree.**
- `frontend/node_modules` did not exist, so the precondition was unmet.
- The 30-06 precedent restores it from the lockfile. `package.json` and `package-lock.json` sha256 were checked before and after: both OK. No package was added or bumped (T-30-SC).
- Two install scripts were blocked by npm's allowScripts policy (`@parcel/watcher`, `unrs-resolver`). Jest, lint, `tsc` and `next build` all ran without them.

**3. [Criterion form] The storage-search criterion is scoped to the route source.**
- As written, `rg -uu -n 'localStorage|sessionStorage' frontend/app/pay` cannot return rc 1. The plan's own storage arm needs the test to clear both storages and make a control write, so the test file names both APIs (3 lines).
- The criterion was run as `-g '!**/__tests__/**'`, which gives rc 1. The whole directory gives rc 0 from the test file only, and the `app/shop` control gives rc 0.
- Instrument failure, recorded: my first exclusion glob, `!__tests__/**`, excluded nothing.

**4. [Rule 2 - Test strength] A reload round-trip arm was added.**
- The plan's arm B was "pass null to replaceState → reload-resume goes red". The resume arms seed `history.state` themselves, so they cannot see what the island writes, and arm B would not have reddened them.
- The new arm goes through the press and remounts on the same entry. Arm B reddens it.

**5. [Rule 3 - Blocking] `/pay` joins the link-graph orphan allowlist (`__tests__/link-graph.test.ts`).**
- The full Jest run named `/pay` as the one orphan. Without the allowlist entry the suite fails.
- `/pay` has the same shape as `/invite`: it is reached only by an emailed or texted fragment-token link, or by Stripe's return_url.
- Fail direction: red before the entry (full run, 1 failed), green after (4/4).

**6. [Copy, not in the contract] Redirect-return body text.**
- The contracted "Payment received" body names the shop and the order. After a Stripe redirect the page has only the PaymentIntent, so it shows "You've paid £{amount}. You can close this page."
- The processing state likewise drops the amount if Stripe returns none.
- "Already paid" without an order number (a redirect never produces it) reads "There's nothing more to pay. You can close this page."
- All three are prefixes or suffixes of contracted rows. Recorded for the UI review.

**7. [Counts] `docs/metrics.json`, README.md, CLAUDE.md and AGENTS.md were updated.** They are not in `files_modified`, but the CLAUDE.md gates require it, as in 30-16 and 30-17.

**8. [Handed on] The SSR-coverage gate is red.** `scripts/check-ssr-coverage-contract.sh` returns rc 1: "R-1 undeclared page: pay/page.tsx". Plan 30-19 owns `scripts/gates/ssr-routes.conf`, by its `files_modified` and its must-have, along with robots.txt and the Referrer-Policy header. Nothing is pushed until 30-20, so no CI run sees it.

**9. [TDD scaffold] Task 1's RED commit included inert stubs (`PayClient` returning `<div/>`, a page with empty metadata).** Jest cannot run a suite whose module does not resolve, and that would be a load failure, not a RED. The GREEN commit replaced both.

**10. REQUIREMENTS.md: PAY-04 and PAY-03 are left unchecked.** As in 30-12, 30-13, 30-16 and 30-17, `requirements-completed` here means the plan contributes to them. Both boxes claim end-to-end payment on staging, which needs 30-19 and 30-20.

**Total deviations:** 10 (1 decision-driven, 2 blocking, 1 test strength, 1 criterion form, 1 copy, 1 counts, 1 handed on, 1 scaffold, 1 requirement tick withheld). **Impact:** no scope change.

## TDD Gate Compliance

There are RED (`test(30-18)`) and GREEN (`feat(30-18)`) commits for each task, in order:
- Task 1: `25126b7f` then `aecf8193`.
- Task 2: `12f01182` then `9eddb8b7`.
- Task 3: `1cf2bc53` then `a714132a`.

Each RED record returned `RED_EVIDENCE_OK`, and its semantic assessment is in the JSON. There is no REFACTOR commit.

## Notes for later plans

- **30-19:**
  - Add the SSR-gate line: `STATIC pay/page.tsx`, metadata only, fetches nothing; the island reads the fragment, Stripe's return query and history.state after mount. There is no `[token]` page.
  - `robots.ts` should DISALLOW `"/pay"`.
  - `next.config.mjs` should send `Referrer-Policy: no-referrer` on `/pay/:path*` after the default.
  - The staging spec's reload-after-Continue arm is the only proof that Next keeps `history.state.jtoyePayResume` across a real reload (flagged assumption 1). In Jest, the round-trip arm proves only the island's half.
  - The page's states, for spec selectors: the h1 reads `Pay {shopName}` before and during payment, and `Payment received` after. "Continue to payment" is the only oxblood button before the press.
- **30-20:**
  - The OpenAPI snapshot work is unchanged by this plan (frontend only).
  - Merge `origin/main` before pushing.
- **Observation (recorded, not filed):** the preview's `currency` is read but not displayed. The page formats every amount as £, because the server creates intents in gbp (`StripeProperties.getCurrency()`, default gbp) and no other currency exists today.

## Known Stubs

None. The scan of the 1382 added frontend lines for TODO, FIXME, placeholder, "coming soon" and "not available" found 0 matches. A planted control line matched 1.

## Threat Flags

None beyond the plan's register.
- T-30-72: mitigated, by the ordering arm, the zero-redeem arms and break arm A.
- T-30-73 and T-30-129: mitigated, by the replaceState argument assertions, the storage spy with its control write, the token-not-rendered checks and break arm B.
- T-30-74: mitigated, by the active-link arm.
- T-30-75: mitigated. States branch on status and the state enum only, and each refused arm asserts the server's prose is absent.
- T-30-76: mitigated, by the connected-account arms on both `/pay` and the checkout.
- T-30-SC: no package added; `npm ci` used the existing lockfile.

## Self-Check: PASSED

- All 3 created source files and all 5 evidence files exist.
- `pay-client.tsx` contains `replaceState`, and the test contains `Continue to payment`.
- All 10 commits listed above are ancestors of HEAD (`git merge-base --is-ancestor`).
