---
phase: 30-live-stripe-test-mode-execution
plan: 16
subsystem: payment-links
tags: [stripe, payment-link, pay-04, d-03, bearer-token, fragment, rls, preview, owner-checkpoint]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-15: branch feat/30-pay-link-page cut, 0 behind origin/main 480782d7; PR 4 merged and live"
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-16 Tasks 1-2 (owner, 2026-10-31T19:16:23Z, a6a7c202): token = fragment; staging OTP = expose-on-staging"
provides:
  - "OrderPaymentLinkService.previewPaymentLink(String rawToken): read-only, @Transactional(readOnly = true), no markUsedIfUnused, no PaymentService call"
  - "record OrderPaymentLinkService.PaymentLinkPreviewDto(shopName, orderNumber, amountPennies, currency, purpose, expiresAt, state); state precedence PAID > USED > REVOKED > EXPIRED > ACTIVE (package-private static previewState)"
  - "POST /api/v1/public/payment-links/preview and POST /api/v1/public/payment-links/redeem, body record PaymentLinkTokenRequest(@NotBlank String token), Cache-Control no-store; legacy /public/... aliases from the class mapping; the path redeem /payment-links/{token}/redeem unchanged"
  - "Issued payment URL is <base>/pay#token=<raw> (was <base>/pay/<raw>), in the API response and the WhatsApp/SMS text"
  - "Shared private withLinkTenant(rawToken, work): tenant-less token-GUC lookup, link tenant pinned, caller TenantContext restored in finally; redeem now runs through it unchanged"
affects: [30-17, 30-18, 30-19, 30-20]

estimate:
  tokens: 70000
  tasks: 3
actuals:
  tokens: 39951     # chars/4 over git diff a6a7c202..40df8c7f (code, tests, evidence, counts; excludes this SUMMARY)
  tasks: 3          # Tasks 1-2 were the owner's decisions (recorded in a6a7c202 before this run); Task 3 here
  commits: 3        # MEASURED: git rev-list --count a6a7c202..HEAD before this SUMMARY's own commit
plan_head_before: a6a7c20263372d9dbd1fe4b856b654c4eff00c6f
plan_head_after: 40df8c7ff52d055f7c47100dedbb332ee10608bf

tech-stack:
  added: []
  patterns:
    - "A bearer-token preview and its consuming action share ONE private resolution method, so the tenant-less lookup, the tenant pin and the context restore cannot drift between them"
    - "The non-consuming read runs in a read-only transaction: a regression that writes is refused by Postgres (500) before it can spend the link, a second wall behind the test"
    - "A 404 assertion on a new route also checks the service's own detail, because a missing route renders the same not-found type"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/payment/PaymentLinkPreviewIntegrationTest.java
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-16-red-task3-it.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-16-red-task3-unit.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-16-task3-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-16-task3-verify.txt
  modified:
    - core-java/src/main/java/uk/jtoye/core/payment/OrderPaymentLinkService.java
    - core-java/src/main/java/uk/jtoye/core/payment/PublicPhoneAndPaymentLinkController.java
    - core-java/src/test/java/uk/jtoye/core/payment/PhoneVerificationAndPaymentLinkTest.java
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "Fragment decision applied as the plan's fragment branch: body-token POST preview and redeem beside the unchanged path redeem, and the issued URL moved to /pay#token= in this plan (the plan's Task 3 action assigns it here, so no hand-off was needed)"
  - "currency comes from StripeProperties.getCurrency() (the currency the intent is created in, default gbp), not a literal"
  - "PAID for a BALANCE link needs balanceDuePennies == 0; a NULL balance is not recorded, not paid, and a captured deposit with a balance owed stays ACTIVE"
  - "Expiry uses the same isBefore(now) comparison as redeem, so preview and redeem never disagree at the boundary"
  - "The OpenAPI snapshot is NOT regenerated here: plan 30-20 Task 1 regenerates PR 5's snapshot and needs OpenApiSnapshotTest failing on these routes as its RED (30-17 and 30-20 plan text)"
  - "The body redeem takes no Idempotency-Key: it is idempotent by construction (a repeat is always refused 409), the V75 DSAR-export precedent; the preview is read-only"

requirements-completed: [PAY-04]

coverage:
  - id: D1
    description: "A payment link can be previewed without being spent: 200 with shop, order number, amount, currency, purpose, expiry and state, repeatable, used_at still NULL, and redeem still succeeds afterwards"
    requirement: PAY-04
    verification:
      - kind: test
        ref: "PaymentLinkPreviewIntegrationTest (7 arms, NOSUPERUSER) + PhoneVerificationAndPaymentLinkTest (7 arms): evidence/30-16-task3-verify.txt V1, V2 rc 0"
        status: pass
      - kind: other
        ref: "evidence/30-16-red-task3-it.json, -unit.json (RED_EVIDENCE_OK); evidence/30-16-task3-arms.txt (A1/A2: consumption red, read-only wall; D: precedence red; E: URL red)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Tenant safety: tenant-less bearer lookup, the link's tenant pinned, the caller's TenantContext restored; a tenant-B caller previews as an anonymous visitor; one link's token cannot read another link's row"
    requirement: PAY-04
    verification:
      - kind: test
        ref: "PaymentLinkPreviewIntegrationTest bearer-semantics and RLS arms; PhoneVerificationAndPaymentLinkRlsIntegrationTest 4/4"
        status: pass
      - kind: other
        ref: "evidence/30-16-task3-arms.txt arms B (clear removed -> 404) and C (restore removed -> IT and unit red)"
        status: pass
    human_judgment: false

duration: 25min
completed: 2026-10-31
---

# Phase 30 Plan 16: Payment-link preview and the fragment token Summary

**A customer's payment link can now be checked without being used up. `POST /api/v1/public/payment-links/preview` takes the token in a JSON body and returns the shop name, order number, amount, currency, purpose, expiry and a state (PAID, USED, REVOKED, EXPIRED or ACTIVE, in that precedence). It never stamps `used_at` and never calls Stripe. It resolves the token the same way redeem does, through one shared private method: the lookup runs with no tenant, the link's own tenant is pinned for the reads, and the caller's context is restored afterwards. Following the owner's fragment decision, links are now issued as `/pay#token=…`, and a body-token `POST /payment-links/redeem` sits beside the unchanged path redeem.**

## Performance

- **Duration:** about 25 min for Task 3 (2026-10-31 19:18Z to 19:43Z). Tasks 1 and 2 were the owner's decisions, recorded at 19:16:23Z in `a6a7c202` before this run.
- **Tasks:** 3 of 3 (Task 3 executed here).
- **Commits:** 3 for Task 3 (1 test RED, 1 feat GREEN, 1 test evidence and counts), plus this SUMMARY's docs commit. All are on `feat/30-pay-link-page` and not pushed. The branch is 0 behind origin/main (`git rev-list --count HEAD..origin/main` = 0 after a fetch).

## Task results

**Tasks 1 and 2 (owner checkpoints, done before this run).** Task 1 chose **fragment** and Task 2 chose **expose-on-staging**. Both are in `evidence/30-16-owner-decisions.txt`. Task 2 binds plans 30-17 (production refusal) and 30-20 Task 1 (overlays). Nothing here touches k8s.

**Task 3 (tracer, TDD).**

- **RED (`19d5bb13`).** The IT is HTTP and JDBC only, so it compiles on the unfixed tree. Command: `./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --tests '…PaymentLinkPreviewIntegrationTest' --tests '…PhoneVerificationAndPaymentLinkRlsIntegrationTest' --no-daemon`, rc 1.
  - 7 of 7 preview arms fail on an assertion: 404 where 200 is expected (no route exists), or the service detail is missing (see the finding below).
  - The 4 existing RLS arms pass.
  - Unit command: `./gradlew :core-java:cleanTest :core-java:test --tests '…PhoneVerificationAndPaymentLinkTest' --no-daemon`, rc 1. The URL arm reads `https://shop.jtoye.co.uk/pay/…` where `/pay#token=…` is expected.
  - Both records are classified `RED_EVIDENCE_OK`. The semantic assessment is in each record: assertion-level failures, with no compile, fixture or context fault.
- **GREEN (`c7dc7594`).** The same two commands both return rc 0. The task lines show they executed (not UP-TO-DATE).
  - PaymentLinkPreviewIntegrationTest 7/7, PhoneVerificationAndPaymentLinkRlsIntegrationTest 4/4, PhoneVerificationAndPaymentLinkTest 7/7.
  - Three unit arms were added with GREEN: the precedence matrix, "preview never consumes or calls Stripe and restores the caller's tenant", and "unknown token is 404 with the tenant restored". The IT also gained a service-level arm: an ambient tenant B is restored.
- **Break arms (`evidence/30-16-task3-arms.txt`).** The bracket was clean, then arms A1 to E, then clean again. GREEN was committed first, and each restore was checked against HEAD by blob hash.
  - **A1:** `markUsedIfUnused` added to the preview, with the read-only transaction kept. Postgres refuses the UPDATE (6 IT arms get 500), and the unit `never()` verify fails.
  - **A2 (the plan's arm):** the same with a writable transaction. The second preview reads `USED` where `ACTIVE` is expected. The used_at arm is red, and redeem-after-preview gets 409.
  - **B:** `TenantContext.clear()` removed. The tenant-B caller gets 404, and both unit "lookup runs tenant-less" assertions fail.
  - **C:** the restore removed. The IT tenant-B restore assertion and 3 unit arms fail.
  - **D:** USED checked before PAID. "PAID beats USED" fails in the unit test and the IT.
  - **E:** the path URL back. The unit URL arm fails.
  - Clean after: 7/7 and 7/7.
- **Tracer gate:** interactive, `human_verify_mode` end-of-phase, automated-only `<verify>`. The verify was re-run in the closing clean bracket and passed. There is no expansion task after it.
- **Wide run:**
  - The whole core-java unit `test` task: 223 classes, 2010 tests, 0 failures. The 1 skip is the pre-existing `ProductLabelGoldenFileTest`.
  - The integrationTest run over `uk.jtoye.core.payment.*` plus `OpenApiSnapshotTest`: 56 tests, 1 failure. The failure is `OpenApiSnapshotTest`, and its diff is additive only: 4 paths (`/api/v1/public/payment-links/{preview,redeem}` and their `/public` aliases), 2 schemas (`PaymentLinkPreviewDto`, `PaymentLinkTokenRequest`), 0 removed lines.
- **Counts:** +10 Java `@Test` methods and +1 file, so 3091 across 427 and 6686 in total.
  - `docs-freshness.sh` returned rc 1 before `--write`. `check-doc-metrics.sh` returned rc 1 (10 FAIL lines) before the README, CLAUDE.md and AGENTS.md prose was edited.
  - Both now return rc 0.

## Finding: a 404 on a new route proves nothing by status and type alone

The first RED run showed that the unknown-token arm's `404` + `type https://jtoye.uk/errors/not-found` assertions **passed on the unfixed tree**. A request to a route that does not exist renders the same problem type, with detail "Resource not found". As first written, the arm could not tell "no endpoint" from "token looked up and not found". It now also asserts the service's detail, "Payment link not found.", and it went red on the re-run RED. The fix is inside this plan's test, so nothing was filed.

## Deviations from Plan

**1. [Decision-driven] Fragment branch applied.** Following the owner's Task 1 choice, the preview is `POST /payment-links/preview {token}`, not the path GET. A body redeem `POST /payment-links/redeem {token}` was added, and `issuePaymentLink` builds `baseUrl + "/pay#token=" + rawToken`. The path redeem is unchanged and still proven by an IT arm. The plan's Task 3 action itself assigns the URL change here, so nothing is handed off.
- Commit: c7dc7594

**2. [TDD ordering] Three unit arms and one IT arm were written with GREEN, not before it.** These are the precedence matrix, preview never consumes or calls Stripe, unknown token restores the tenant, and the service-level tenant-B restore in the IT. They call `previewPaymentLink` and `previewState`, which did not exist at RED. Writing them first would have made the RED commit fail to compile, which is INVALID_RED (`build_failure`). The HTTP IT carries the full state matrix through the real database and was RED first. Each GREEN-time arm was then shown failing by break arms A1, A2, B, C and D.

**3. [Rule 2 - correctness] The service's 404 detail is asserted** (see the finding above). Without it, the 404 arm could not fail.

**4. [Rule 2 - security] `Cache-Control: no-store` on both body routes.** They carry or answer for a bearer credential, and redeem returns a client secret. This is the `/invite` and DSAR-export rule, and the IT asserts it. The path redeem was left byte-identical.

**5. [Rule 3 - blocking] The constructor gained `ShopRepository` and `StripeProperties`.** These are for the shop name and the currency. The unit test's manual construction was updated. Spring wiring was proven by the IT context and the full unit suite (2010/0).

**6. [Plan-mandated deferral] The OpenAPI snapshot was not regenerated.** It is regenerated in plan 30-20 Task 1, whose acceptance requires `OpenApiSnapshotTest` to fail on these routes before regeneration. That RED now exists. Until 30-20, `OpenApiSnapshotTest` is red on this branch by design. The branch is not pushed and no PR is open.

**7. [Evidence hygiene] Random per-run 43-character test tokens are redacted in the arms evidence.** gitleaks `generic-api-key` flagged one (rc 1). A planted `sk_live_` control showed the scanner fires (rc 1). After redaction it returns rc 0, and the header of the arms file says what was redacted. The RED records scanned rc 0 unchanged.

**8. REQUIREMENTS.md: PAY-04 left unchecked.** As in 30-12 and 30-13, `requirements-completed: [PAY-04]` means "contributes to PAY-04". The box claims a link is payable end to end on staging, which needs 30-17 to 30-20.

**Total deviations:** 8 (1 decision-driven, 1 TDD ordering, 2 correctness and security additions, 1 wiring, 1 plan-mandated deferral, 1 evidence hygiene, 1 requirement tick withheld). **Impact:** no scope change.

## Notes for later plans

- **30-17:** `RedeemedPaymentLinkDto` gains `stripeAccountId`. It now has two callers (path and body routes), but there is one constructor site, inside `withLinkTenant`'s lambda in `redeemPaymentLink`. `PaymentLinkPreviewIntegrationTest` is in 30-17's files: its seeds use a NULL intent id, so redeem never reaches Stripe there. The OTP production refusal is 30-17's, and the staging overlay value is 30-20's (owner Task 2 decision).
- **30-18:** the contract is fragment. `app/pay/page.tsx` alone reads `location.hash` `#token=`. It calls `POST /api/v1/public/payment-links/preview {token}` and then `POST /api/v1/public/payment-links/redeem {token}`. The preview returns `{shopName, orderNumber, amountPennies, currency, purpose, expiresAt, state}`. Unknown is 404 with detail "Payment link not found.", and blank or missing is 400. On redeem: used is 409, revoked or expired is 410, and a Stripe failure is 502. Neither response carries the token. Both send `Cache-Control: no-store`. After redeem, replace the hash (`history.replaceState(null, "", "/pay")`) as the UI-SPEC already says for the path shape.
- **30-19:** with fragment, the Referer and robots work covers `/pay` itself. The fragment never travels, but keep the `/pay/:path*` header entry so the legacy path shape stays covered.
- **30-20 Task 1:** run `OpenApiSnapshotTest` first (RED on the 4 paths and 2 schemas above), then `./gradlew :core-java:updateOpenApiSnapshot --no-daemon`. The diff should name only these plus 30-17's `stripeAccountId` fields.
- **Observation (recorded, not filed):** the PATH redeem's problem body echoes the token in `instance` (seen in arm A2, `/api/v1/public/payment-links/tok-…/redeem`). This is pre-existing behaviour of a route kept for links already issued in the path shape. The body routes do not do it. It breaks no gate and has caused no observed failure, so it fails the admission test.
- **Observation:** the preview reports the link's state and does not look at the order's status. A link on a CANCELLED or voided order previews as ACTIVE, and redeem also accepts it today (issue refuses such orders, but redeem does not re-check). This is out of the plan's state contract and is recorded for the review series.

## Known Stubs

None. A scan of the 712 added lines under `core-java/` for TODO, FIXME, placeholder, "coming soon" and "not available" found 0 matches. The same pattern matched 1 on a planted control line.

## Threat Flags

None beyond the plan's register. All four new paths are inside `/public/**` and `/api/v1/public/**`, so the existing anonymous allowance and the public per-IP rate-limit buckets apply. They are the routes T-30-63 to T-30-66 describe.
- T-30-63: mitigated by the fragment decision. The token is never in a request line on the new routes.
- T-30-64: mitigated. Proven by arms A1 and A2.
- T-30-65: mitigated by NOSUPERUSER IT arms and break arms B and C. `oaas-tenancy-security` reviews the diff in the review series.
- T-30-66 and T-30-67: unchanged.
- T-30-SC: no package installed.

## Self-Check: PASSED

- PaymentLinkPreviewIntegrationTest.java and all 4 evidence files (two RED records, arms, verify) exist. `PaymentLinkPreviewIntegrationTest` contains `used_at`.
- Commits 19d5bb13, c7dc7594 and 40df8c7f are ancestors of HEAD (`git merge-base --is-ancestor`).
