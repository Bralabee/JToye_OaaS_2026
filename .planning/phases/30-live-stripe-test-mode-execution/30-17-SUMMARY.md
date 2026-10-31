---
phase: 30-live-stripe-test-mode-execution
plan: 17
subsystem: payments
tags: [stripe, connect, direct-charge, stripe-js, stripeAccount, pay-04, pay-03, otp, startup-guard, allow-list]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-16: body-token preview and redeem, RedeemedPaymentLinkDto built once inside withLinkTenant; owner decision expose-on-staging"
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-12/30-13: stripe.environment-tag with an empty fallback, declared per overlay (local, staging, production), never in base"
provides:
  - "record PaymentIntentResult(String paymentIntentId, String clientSecret, String stripeAccountId): the Stripe-Account header account for DIRECT_CHARGE_VENDOR_MOR and SPLIT_DEPOSIT_VENDOR_MOR intents, null for destination and platform intents"
  - "Additive stripeAccountId on GuestOrderConfirmation (first response and idempotent replay; serialised as JSON null, never omitted), BalancePaymentSessionDto and OrderPaymentLinkService.RedeemedPaymentLinkDto"
  - "Idempotent replay of a payable DRAFT retrieves its client secret with retrieveClientSecret(order, pi), so a direct-charge intent is fetched on its connected account"
  - "PhoneVerificationService.OTP_SEAM_ENVIRONMENTS = {local, test, staging}; with expose-otp-for-test=true any other stripe.environment-tag (missing, blank, misspelt, wrongly cased, prod, production) is an IllegalStateException at construction naming both properties and [local, staging, test]; one startup WARN when the seam is on"
affects: [30-18, 30-20]

estimate:
  tokens: 70000
  tasks: 2
actuals:
  tokens: 66424     # chars/4 over git diff bd4a0159..d7dc58fa (code, tests, evidence incl. embedded JUnit XML, counts; excludes this SUMMARY)
  tasks: 2
  commits: 8        # MEASURED: git rev-list --count bd4a0159..HEAD before this SUMMARY's own commit
plan_head_before: bd4a0159671036ce447add25ab37baeed1415c2a
plan_head_after: d7dc58fa36a15104948112f09e177a3d77459f72

tech-stack:
  added: []
  patterns:
    - "The account a browser is told to use comes from the SAME routing decision that set the Stripe-Account header (PaymentService.owningAccount for creates, directChargeAccountFor for replay and redeem), never from a second lookup rule"
    - "A field whose null means something (here: the platform owns the intent) is serialised as JSON null and asserted present-as-null, so a NON_NULL regression is caught (break arm D)"
    - "A seam that leaks a credential is gated on an ALLOW-list of declared environments, not a deny-list of the production name, so a missing or mistyped declaration fails closed"
    - "RED arms reach a not-yet-existing record component or constructor reflectively and fail on an assertion; a REFACTOR commit replaces the reflection with direct calls once GREEN exists"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/storefront/GuestOrderStripeAccountTest.java
    - core-java/src/test/java/uk/jtoye/core/customer/PhoneVerificationProductionRefusalTest.java
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-17-red-task1-payment.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-17-red-task1-storefront.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-17-red-task1-it.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-17-red-task2.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-17-task1-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-17-task2-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-17-verify.txt
  modified:
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentIntentResult.java
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java
    - core-java/src/main/java/uk/jtoye/core/payment/OrderPaymentLinkService.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/main/java/uk/jtoye/core/storefront/dto/GuestOrderConfirmation.java
    - core-java/src/main/java/uk/jtoye/core/storefront/dto/BalancePaymentSessionDto.java
    - core-java/src/main/java/uk/jtoye/core/customer/PhoneVerificationService.java
    - core-java/src/test/java/uk/jtoye/core/payment/PaymentServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/PaymentLinkPreviewIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/payment/PhoneVerificationAndPaymentLinkTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "The idempotent replay now retrieves a DRAFT's secret with the order-aware retrieveClientSecret(order, pi) (Rule 1): the plan's interfaces assumed it already did, but it used the platform-only overload, so a direct-charge replay got a 404 and a null secret and would have shipped an account id with nothing to pay"
  - "Replay and redeem name the account only when the order has a PaymentIntent, so a cash order's replay says null exactly as its first response did, even where the shop now routes direct"
  - "GuestOrderConfirmation takes stripeAccountId as a trailing constructor argument (not a setter), so every construction site has to decide it; no two-argument PaymentIntentResult convenience constructor was added, for the same reason"
  - "The OTP guard does not trim or lower-case the tag: exact, case-sensitive membership, so 'Staging' and 'staging ' refuse"
  - "The OpenAPI snapshot is NOT regenerated here (plan 30-20 Task 1 owns it); the current diff is additive only"

requirements-completed: [PAY-04, PAY-03]

coverage:
  - id: D1
    description: "Every response that hands a browser a client secret also names the Stripe account that owns the intent: checkout confirmation (first response and replay), balance session and payment-link redeem; JSON null, present, for platform and destination intents"
    requirement: PAY-04
    verification:
      - kind: unit
        ref: "PaymentServiceTest (7 new arms, 53/0) + GuestOrderStripeAccountTest (7/0): evidence/30-17-verify.txt V1"
        status: pass
      - kind: integration
        ref: "PaymentLinkPreviewIntegrationTest redeem arms (9/0, NOSUPERUSER, real routing): evidence/30-17-verify.txt V2"
        status: pass
      - kind: other
        ref: "evidence/30-17-red-task1-*.json (RED_EVIDENCE_OK x3); evidence/30-17-task1-arms.txt arms A-E red, clean before and after"
        status: pass
    human_judgment: false
  - id: D2
    description: "The OTP test seam starts only where stripe.environment-tag is declared as exactly local, test or staging; missing, blank, misspelt, wrongly cased, prod and production refuse startup; with the seam off nothing is checked"
    requirement: PAY-04
    verification:
      - kind: unit
        ref: "PhoneVerificationProductionRefusalTest 21/0 + PhoneVerificationAndPaymentLinkTest 7/0: evidence/30-17-verify.txt V3"
        status: pass
      - kind: other
        ref: "evidence/30-17-red-task2.json (RED_EVIDENCE_OK); evidence/30-17-task2-arms.txt arm P (production-only guard reds the missing and misspelt arms), Q-T"
        status: pass
    human_judgment: false

duration: 34min
completed: 2026-10-31
---

# Phase 30 Plan 17: The owning Stripe account reaches the browser, and the OTP seam fails closed Summary

**The browser can now pay a direct-charge payment. `PaymentIntentResult` carries `stripeAccountId`, the connected account named in the `Stripe-Account` header for direct-charge and split-deposit intents, and null for destination and platform intents. The checkout confirmation (first response and idempotent replay), the balance session and the payment-link redeem each pass it on, so Stripe.js can be initialised with `{ stripeAccount }`. Where the platform owns the intent, the field is sent as JSON null, never left out. Separately, `expose-otp-for-test=true` now refuses startup unless `stripe.environment-tag` is exactly `local`, `test` or `staging`.**

## Performance

- **Duration:** about 34 min (2026-10-31 20:02Z to 20:36Z).
- **Tasks:** 2 of 2.
- **Commits:** 8, each with its own role: RED, GREEN, REFACTOR and evidence for each task. All are on `feat/30-pay-link-page` and none is pushed.
- **Base drift:** the branch is now 1 commit behind `origin/main`: `eff54aa0` (#1058, CI, lint and compose toolchain gaps). It shares no file with this plan's diff. Merging from the base before the push belongs to plan 30-20 (proof standard 3).

## Task results

**Task 1 (tracer, TDD): the owning account.**

- **RED (`fbd3b888`).** Three test sets failed for the right reason.
  - PaymentServiceTest: 7 new arms covering direct, split, destination and no routing, on both create methods. All 7 fail with "PaymentIntentResult must carry a stripeAccountId component". The record had two components, and the arm reads the third reflectively so the file still compiles. The 46 existing arms pass.
  - GuestOrderStripeAccountTest: 7 of 7 fail. The three replay arms fail on the missing JSON field. The first-response and balance arms fail on the missing three-component constructor.
  - IT: the 2 redeem arms fail on the missing field. The client-secret assertion before it passed, so the spy works. The 7 existing arms pass.
  - All three records are classified `RED_EVIDENCE_OK`.
- **GREEN (`c54abfe1`).** One helper, `PaymentService.owningAccount`, applies `usesDirectChargeHeader ? connectedAccountId : null` to the same `Optional<SettlementRoutingDecision>` that set the header. Both create methods use it.
  - The confirmation, the balance DTO and the redeem DTO gained the trailing field. Replay and redeem resolve it through `directChargeAccountFor`.
  - The replay switched to the order-aware secret retrieve (deviation 1).
  - The plan's two verify commands return rc 0 and both executed. Results: 53/0 and 7/0; the 9 boot4 classes 82/0; the IT 9/0.
- **REFACTOR (`b892c61c`).** The reflective accessor and constructor are gone, and the counts are unchanged.
- **Break arms (`d2c17248`, `evidence/30-17-task1-arms.txt`).** Run as clean, then arms A to E, then clean again. Every restore matched HEAD's blob.
  - **A:** the account is returned for any routing. Both destination arms go red, which is the T-30-71 check.
  - **B:** the replay goes back to the platform retrieve. The direct-charge replay goes red.
  - **C:** an account is named on an order with no intent. The cash replay goes red.
  - **D:** `NON_NULL` on the confirmation field. The three "present as null" arms go red. The balance record is unaffected, as it should be.
  - **E:** the redeem never names the account. The DIRECT redeem IT arm goes red.
- **Tracer gate.** The mode is interactive with `human_verify_mode` end-of-phase, and the `<verify>` is automated only. Both verify commands re-ran green in the closing clean bracket, so execution expanded to Task 2.

**Task 2 (TDD): the OTP seam refusal.**

- **RED (`0327d385`).** PhoneVerificationProductionRefusalTest has 21 arms, and each case has its own arm. The refusal arms are production, null, blank, whitespace, `stagng`, `Staging` and `prod`. The allowed cases are local, test and staging. There is a WARN arm and 10 seam-off cases. All 21 fail on assertions, because no constructor takes the tag. `RED_EVIDENCE_OK`.
- **GREEN (`8c187771`).** The constructor gains `@Value("${stripe.environment-tag:}") String environmentTag`, with an empty fallback. It also gains `OTP_SEAM_ENVIRONMENTS = Set.of("local", "test", "staging")`.
  - When the seam is on and the tag is not an exact member, it throws `IllegalStateException("jtoye.phone-verification.expose-otp-for-test=true requires stripe.environment-tag to be declared as one of [local, staging, test]; found '<tag>' | (not declared): the OTP would be returned to any caller")`.
  - Otherwise it logs one WARN, "phone verification OTPs are returned in API responses — test seam enabled in <tag>".
  - PhoneVerificationAndPaymentLinkTest now passes "test". Results: 21/0 and 7/0, and boot4 `ConfigKeyContractTest` 9/0.
- **REFACTOR (`55687939`).** Direct constructor call; the counts are unchanged.
- **Break arms (`d7dc58fa`, `evidence/30-17-task2-arms.txt`).**
  - **P (the plan's required arm):** the earlier production-only guard. `production` still refuses, but the missing (null), blank, whitespace, `stagng`, `Staging` and `prod` arms all go red. That is the fail-open the allow-list closes.
  - **Q:** case-insensitive matching. `Staging` goes red.
  - **R:** the message loses the allowed values. All 7 refusal arms go red.
  - **S:** the check runs with the seam off. The 7 non-allow-listed seam-off cases go red.
  - **T:** no WARN. The WARN arm goes red.
  - The closing clean run is 21/0 and 7/0.

**Wide runs.**

- Full core-java unit `test`: 225 classes, 2045 tests, 0 failures. The 1 skip is the pre-existing `ProductLabelGoldenFileTest`.
- The integrationTest run over `payment.*`, `storefront.GuestCheckout*` and `OpenApiSnapshotTest`: 76 tests, with 1 failure, `OpenApiSnapshotTest`. It is red by design until 30-20 (see 30-16's deviation 6). Its diff against the committed snapshot removes nothing and adds 379 lines: the three `stripeAccountId` properties, plus 30-16's 4 paths and their schemas.

**Counts:** +24 `@Test` methods and +2 files, so 3115 across 429 and 6710 in total.
- `docs-freshness.sh` returned rc 1 before `--write`.
- `check-doc-metrics.sh` returned rc 1 (10 FAIL lines) before the README, CLAUDE.md and AGENTS.md prose was edited.
- Both now return rc 0.

## Deviations from Plan

**1. [Rule 1 - Bug] The idempotent replay fetched a direct-charge intent's secret on the platform account.**
- **Found during:** Task 1, reading `replayConfirmation`. The plan's `<interfaces>` describe it as `retrieveClientSecret(order, pi)`, but the code called the platform-only overload `retrieveClientSecret(pi)`.
- **Issue:** Stripe answers a platform-account retrieve of a connected-account intent with 404 `resource_missing`. The replay logged a WARN and returned a null secret. With this plan's field added, a direct-charge retry would have told the browser which account to use and given it nothing to pay with.
- **Fix:** the replay now uses the order-aware overload, the A-1 call (PR #949) that redeem already used. The WR-02 unit stub in `PublicStorefrontServiceTest` moved to that overload.
- **Proof:** `GuestOrderStripeAccountTest` stubs the platform retrieve to throw and the order-aware one to answer. Break arm B goes red.
- **Files:** `PublicStorefrontService.java`, `PublicStorefrontServiceTest.java`
- **Commit:** c54abfe1

**2. [Plan detail] `paymentService.directChargeAccountFor(order)` stands in for `stripeConnectService.resolveSettlementRouting(order)`.** Neither `PublicStorefrontService` nor `OrderPaymentLinkService` holds `StripeConnectService`. `directChargeAccountFor` is that call with the plan's "direct header only" filter, and both callers already hold a tenant-pinned transaction, which its Javadoc requires.

**3. [Scope] One extra break arm.** Arm C is new: on replay, the account is named only when the order has an intent. Without that rule, a cash order at a shop that later moved to direct routing would replay an account id its first response never carried.

**4. [TDD shape] The RED arms reach the new record component and constructor by reflection.** The 30-16 alternative was to write those arms with GREEN, because they would not compile at RED (`build_failure`). Here they were written first, compiled against the old shapes, and failed on assertions. The REFACTOR commits (`b892c61c`, `55687939`) replace the reflection with direct calls.

**5. [Fixture] A first RED run had one arm fail with `UnfinishedStubbingException`.** An assertion fired inside a `when(...).thenReturn(...)` argument. The construction was moved out of the stubbing, and the recorded RED is the re-run, which has errors=0. The RED record says so.

**6. [Evidence] The Task 1 arms ran three times.** Runs 1 and 2 had identical rc values and failure counts, but printed no test names. In run 1 the summary grep did not match the XML. In run 2 a perl edit emptied the log path. Neither run was killed mid-arm, because killing one would have left a source file modified. Run 3 is the recorded one, and its header says so.

**7. [Plan-mandated deferral] The OpenAPI snapshot was not regenerated** (30-20 Task 1). Until then, `OpenApiSnapshotTest` stays red by design on this branch.

**8. REQUIREMENTS.md: PAY-03 and PAY-04 are left unchecked.** As in 30-12, 30-13 and 30-16, `requirements-completed` here means the plan contributes to them. The boxes claim a payment works end to end on staging, which needs 30-18 to 30-20.

**Total deviations:** 8. That is 1 bug fix, 1 call-site substitution, 1 extra arm, 1 TDD-shape choice, 1 fixture fault, 1 evidence re-run, 1 plan-mandated deferral and 1 requirement tick withheld. **Impact:** no scope change. Deviation 1 is needed for the replay half of the plan's truth to hold.

## TDD Gate Compliance

Both tasks have the RED `test(30-17)` commit before the GREEN `feat(30-17)` commit, and each GREEN commit descends from its RED: `fbd3b888` then `c54abfe1` for Task 1, and `0327d385` then `8c187771` for Task 2. Each task also has an optional `refactor(30-17)` commit, `b892c61c` and `55687939`. All four RED records are `RED_EVIDENCE_OK`, with the semantic assessment recorded in each one.

## Notes for later plans

- **30-18 (the page).** Read `stripeAccountId` from three responses:
  - the checkout confirmation (`GuestOrderConfirmation`);
  - the balance session (`BalancePaymentSessionDto`);
  - the payment-link redeem (`RedeemedPaymentLinkDto`, on both the body and path routes).
  - When it is non-null, call `loadStripe(publishableKey, { stripeAccount })`. When it is null, call it without the option. The field is always present, so a missing key means an older server.
- **30-20 Task 1.**
  - Run `OpenApiSnapshotTest` first. It should be red on 30-16's 4 paths and 2 schemas plus 3 `stripeAccountId` properties, and nothing else.
  - Then regenerate the snapshot.
  - The staging overlay can now set `jtoye.phone-verification.expose-otp-for-test: "true"`, and startup accepts it because staging declares `stripe.environment-tag: "staging"`. The production overlay's explicit false and the render check also belong to 30-20.
  - Before pushing, merge `origin/main`, which is 1 ahead at `eff54aa0`.
- **Observation (recorded, not filed):** the replay and redeem resolve the account from the shop's routing at the time they run, not at the time the intent was created. If a vendor changes the shop's settlement mode between checkout and retry, the account named could disagree with the intent's real account. `retrieveClientSecret(order, …)` has the same behaviour today (A-1). It breaks no gate and has caused no observed failure, so it fails the admission test. The durable fix would be to persist the account on the order when the intent is created.

## Known Stubs

None. A scan of the 665 added lines under `core-java/` for TODO, FIXME, placeholder, "coming soon" and "not available" found 0 matches. The same scan matched 1 on a planted control line.

## Threat Flags

None beyond the plan's register.
- **T-30-68 (accepted):** a public `acct_` id is sent only beside the client secret of the same intent.
- **T-30-69 (mitigated):** the allow-list, proven by arms P to S.
- **T-30-71 (mitigated):** the account comes from the decision that created the intent, proven by arms A and B.
- **T-30-SC:** no package was installed.
- No new endpoint, auth path or schema change was added.

## Self-Check: PASSED

- Both new test files and all 7 evidence files exist. `PaymentIntentResult.java` contains `stripeAccountId`, and `PhoneVerificationProductionRefusalTest.java` contains `production`.
- Commits fbd3b888, c54abfe1, b892c61c, d2c17248, 0327d385, 8c187771, 55687939 and d7dc58fa are ancestors of HEAD (`git merge-base --is-ancestor`).
