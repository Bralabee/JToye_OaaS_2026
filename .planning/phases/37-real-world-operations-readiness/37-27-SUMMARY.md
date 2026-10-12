---
phase: 37-real-world-operations-readiness
plan: 27
subsystem: api
tags: [cash-order-caps, abuse-bounds, problem-json, rfc7807, 429, config-declared, flyway, v81, envers, not-audited, contact-verified, customer-jwt, openapi-snapshot, d-18, d-27, uxt-041, rwo-041]

requires:
  - phase: 37-26
    provides: "the third 37-D plan; the storefront refusal-inside-the-reserved-work shape (OrderLimits) this plan reuses, and the schema head V80"
  - phase: 37-24
    provides: "ClientIpResolver (D-19), the identity 37-28's IP axis will digest into the column V81 reserves here"
  - phase: 31.1-03
    provides: "GuestOrderAcknowledgements and the storefront refusals thrown inside the reserved idempotency work"
  - phase: issue-179
    provides: "CustomerJwtVerifier.verifiedEmail (signature, issuer, expiry, email_verified) that the contact-verified fact is read from"
provides:
  - "V81: orders.contact_verified BOOLEAN (NULL = not recorded, no DEFAULT, no backfill), orders.cancel_reason_code VARCHAR(32) + ck_orders_cancel_reason_code over the four CancelReason values (37-29 writes it), both mirrored on orders_aud in the same migration; orders.client_ip_digest CHAR(64) with NO audit mirror (@NotAudited, D-27; 37-28 writes it); three partial indexes idx_orders_open_cash_{email,phone,ip} over open (PENDING/CONFIRMED/PREPARING/READY), cash (payment_status = 'NONE'), not-voided orders per shop. Reserved for all of 37-D"
  - "CashOrderCaps (@Component): jtoye.storefront.cash-caps.{per-email 3, per-phone 3, per-ip 5}, a value below 1 refuses to start naming the key; checkEmailAndPhone(tenantId, shopId, email, phone) counts OPEN cash orders at the shop by trimmed lower-cased email (one indexed count) and by normalised phone (digits only, UK 0 -> 44, 00 dropped; the shop's open cash phones read through the partial index and folded in Java), email judged first"
  - "OrderCapExceededException -> 429 https://jtoye.uk/errors/order-cap-exceeded, code ORDER_CAP_EXCEEDED, typed axis EMAIL | PHONE | IP and cap, detail 'This customer has reached the limit of open cash orders ({axis}). Try again once one is completed.', NO Retry-After and a type distinct from rate-limited so no client auto-retries it (Pitfall 8)"
  - "PublicStorefrontService.placeGuestOrder calls the caps on the CASH branch only, before the save, inside the reserved idempotency work: a refusal leaves no order and no reservation and the same Idempotency-Key succeeds once an open order completes; card orders are outside the cap on both sides (never checked; never counted)"
  - "contact_verified recorded on EVERY storefront order: POST /api/v1/public/shops/{slug}/orders reads an optional X-Customer-Token, verifies it with CustomerJwtVerifier (an invalid token is ignored, never a 401), and stores true only when the proven email equals customerEmail with case and surrounding space folded; vendor / REST / MCP orders leave NULL. OrderDto and OrderDetailDto expose contactVerified (NON_NULL: absent = not recorded), in the OpenAPI snapshot"
  - "CancelReason enum (SUSPECTED_FAKE | CANNOT_FULFIL | CUSTOMER_REQUEST | OTHER); Order gains contactVerified, cancelReasonCode, clientIpDigest"
  - "Schema V81 in CLAUDE.md, AGENTS.md, README.md and docs/metrics.json"
affects: [37-28, 37-29, 37-30, 37-31, 37-D-gate, storefront-checkout, kitchen-board, k8s-staging-rollout, k8s-production-rollout]

actuals:
  tokens: 145446
  tasks: 3
  commits: 8
plan_head_before: 0449b35b272dc985f031d266e85ba9ece913bc9e
plan_head_after: 68be2323c622bc022846b8c18fdfc6c313e104ab

tech-stack:
  added: []
  patterns:
    - "An abuse bound is a COUNT of open work per identity, not a rate: the slot frees when the kitchen completes an order, so the refusal carries no Retry-After and a type the retrying client does not know"
    - "One normaliser per identity axis, in the bean that counts, so the cap cannot be dodged by respelling; where the stored spellings differ (phones) the shop's open rows are read through the partial index and folded in Java rather than keeping a second normaliser in SQL"
    - "A partial index and its count query repeat the SAME predicates verbatim (open statuses, the cash column, voided_at IS NULL), so the planner answers from the index and a voided order never holds a slot"
    - "A pseudonymous identifier of a person (the IP digest) is @NotAudited: it lives on the live row only, where one NULL ends it, and the test asserts via information_schema that no audit column exists"
    - "A stored boolean with NULL = not recorded, rendered NON_NULL on the DTOs, so the UI distinguishes 'unverified' (false, badge) from 'nobody recorded it' (absent, nothing) without deriving anything"
    - "Integration tests that place cash orders mint their own contact identity per arm, stable per address where an arm resubmits an identical body (RESEARCH Pitfall 9: unique details, never a raised default)"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V81__order_abuse_bounds.sql
    - core-java/src/main/java/uk/jtoye/core/order/CancelReason.java
    - core-java/src/main/java/uk/jtoye/core/exception/OrderCapExceededException.java
    - core-java/src/main/java/uk/jtoye/core/storefront/CashOrderCaps.java
    - core-java/src/test/java/uk/jtoye/core/storefront/CashOrderCapIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/CashOrderCapsTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-27-red-task1.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-27-red-task2.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-27-red-task2-unit.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-27-red-task3.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/order/Order.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderRepository.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderMapper.java
    - core-java/src/main/java/uk/jtoye/core/order/dto/OrderDto.java
    - core-java/src/main/java/uk/jtoye/core/order/dto/OrderDetailDto.java
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java
    - core-java/src/main/resources/application.yml
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/order/AllergyNoteAckIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderAllergenSnapshotIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/CustomerRecordedAllergenSetIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/GuestCheckoutIdempotencyIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/GuestOrderAllergenAckIntegrationTest.java
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - k8s/LOCAL.md
    - .planning/codebase/STACK.md
    - .planning/codebase/INTEGRATIONS.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md

key-decisions:
  - "37-27: 'open and cash' is status IN (PENDING, CONFIRMED, PREPARING, READY) AND payment_status = 'NONE' AND voided_at IS NULL. payment_status NONE is what the COD branch writes and a card order never carries (PENDING, then CAPTURED), so the column IS the cash predicate the plan asked for; voided_at IS NULL is added because a void keeps the order's status (D-10) and a voided CONFIRMED order must not hold a slot. The index predicates and the two count queries repeat the three verbatim"
  - "37-27: phones are normalised in Java over the shop's open cash phones (read through idx_orders_open_cash_phone), not by an expression index: the stored spellings differ per order, a SQL normaliser would be a second copy to hold equal to the Java one (the V80 lesson), and the scan is bounded by what is on one shop's kitchen board. Email is counted in SQL on lower(customer_email) because its folding IS expressible in one function"
  - "37-27: the email axis is judged before the phone axis and the order is pinned by a unit test, so a customer who is full on both is told EMAIL (the address the confirmation flow names) and the cheaper indexed count runs first"
  - "37-27: the 429 carries cap as a typed property beside axis (additive to the plan's shape), so a client can say 'the most open cash orders this shop allows for one customer' with the number, and an agent branches on axis without parsing the sentence; the detail is UI-SPEC D1's copy verbatim"
  - "37-27: contactVerified is NON_NULL on both DTOs (absent = not recorded) and compared through the caps' normaliseEmail (case and surrounding space folded), never equalsIgnoreCase alone; the verified email never enters the idempotency fingerprint, so a retry with or without the cookie replays the same order and the Jackson-2 golden is untouched"
  - "37-27: an invalid X-Customer-Token on the guest-order POST is ignored (null verified email), never a 401: checkout is a guest flow and a stale cookie must not refuse an order; /orders/mine keeps its 401 because there the token is the only proof"
  - "37-27: the three cash-caps keys are declared in application.yml without env placeholders and nowhere else, as the plan's file list says and the probe note implies (compose keeps the defaults); Spring relaxed binding still admits an env override, and 37-28 — which must wire the HMAC key through compose, .env.example and k8s anyway — is where all three go across if per-overlay tuning is wanted"
  - "37-27: the five pre-existing ITs that re-used one identity were given unique identities (phones derived from the arm's address so replay arms resend an identical body; GuestCheckoutIdempotency's identity derived from the key; the seven-order history arm completes each order as it is placed), never a raised cap — RESEARCH Pitfall 9 as written"

patterns-established:
  - "Open-work caps: COUNT per identity per shop over partial indexes, judged on the cash branch inside the reserved idempotency work; typed 429 with its own type, axis and cap, no Retry-After"
  - "Java RED evidence on Gradle, nested unit classes: the per-nested-class JUnit XML is the report; the testcase name is the method name with parentheses"

requirements-completed: [RWO-041]

coverage:
  - id: D1
    description: "Three open cash orders for one email at a shop, a fourth spelled in another case is 429 order-cap-exceeded axis EMAIL with no Retry-After, no row and no reservation; completing one of the three frees the slot and the same Idempotency-Key is then 201 (UXT-041)"
    requirement: "RWO-041"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/CashOrderCapIntegrationTest.java#UXT-041: a fourth open cash order for the same email (case-folded) at a shop is refused 429 ... (RED 2d750178: the fourth order ACCEPTED HTTP 201, evidence/37-27-red-task1.json RED_EVIDENCE_OK 2 / 2; GREEN c2feada7 2/0 + RlsContractTest 7/0; tracer gate re-run on the committed tree rc 0, fresh reports, no UP-TO-DATE)"
        status: pass
    human_judgment: false
  - id: D2
    description: "V81 has the declared shape: contact_verified (boolean, no default), cancel_reason_code (varchar 32 + CHECK of the four values) and client_ip_digest (char 64) on orders; the first two mirrored on orders_aud and the digest NOT; the three partial cap indexes on the open statuses and the cash predicate"
    requirement: "RWO-041"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/CashOrderCapIntegrationTest.java#V81: orders gains contact_verified, cancel_reason_code (+CHECK) and client_ip_digest; orders_aud mirrors the first two and NOT the digest; the three partial cap indexes exist (RED: columns absent; GREEN pass)"
        status: pass
      - kind: other
        ref: "grep -nE 'ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS (contact_verified|cancel_reason_code)' V81 -> 2 lines (92, 93), rc 0; grep -nE 'orders_aud.*client_ip_digest' V81 -> nothing, rc 1; scripts/check-no-create-extension.sh PASS over 81 migrations"
        status: pass
    human_judgment: false
  - id: D3
    description: "The caps cannot be dodged by respelling a phone (07700 900NNN then +44 7700 900NNN is 429 axis PHONE), a neighbouring number is not counted, the same identity at another shop is 201, three open CARD orders do not count while three cash ones do, COMPLETED / CANCELLED / VOIDED orders hold no slot, a card checkout never consults the caps, and both sides of each cap and the refusal-to-start below 1 are pinned"
    requirement: "RWO-041"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/CashOrderCapIntegrationTest.java (RED 3de5b25b: 7 ran / 1 failed, the +44 fourth order ACCEPTED 201, evidence/37-27-red-task2.json RED_EVIDENCE_OK; GREEN 70804146 7/0; break arm: normalisation dropped -> 7 ran / 1 failed exactly the +44 arm 'expected: 429 but was: 201' on a fresh report, restore from a scratch copy verified by md5 7b152f31d129449606dee7d87e6675b3 before and after and a clean git status, closing clean 7/0)"
        status: pass
      - kind: unit
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/CashOrderCapsTest.java (RED: 20 ran / 10 failed, nine spellings unnormalised and the at-the-cap phone arm threw nothing, evidence/37-27-red-task2-unit.json RED_EVIDENCE_OK; GREEN 20/0) + PublicStorefrontServiceTest#createGuestOrder_cardCheckout_isOutsideTheCashCap and #createGuestOrder_cashCheckout_isRefusedAtTheCapBeforeSave (d2b6c0ea, green when written, 46/0)"
        status: pass
    human_judgment: false
  - id: D4
    description: "contact_verified is recorded on every storefront order: no token -> false; a verified token for the same email in another case -> true; a token for another email -> false (T-37-69); an invalid token is ignored (201, false, never 401); a vendor order -> NULL and both DTOs omit the field; OrderDto and OrderDetailDto carry contactVerified and the OpenAPI snapshot declares it"
    requirement: "RWO-041"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/storefront/CashOrderCapIntegrationTest.java (five contact_verified arms; RED 72f61c38: 12 ran / 4 failed each reading NULL where true/false was expected, evidence/37-27-red-task3.json RED_EVIDENCE_OK; GREEN 1dae5f54 12/0) + core-java/src/test/java/uk/jtoye/core/integration/OpenApiSnapshotTest.java (1/0 after updateOpenApiSnapshot, +26 lines: both contactVerified schemas and the X-Customer-Token header parameter)"
        status: pass
      - kind: unit
        ref: "IdempotencyFingerprintGoldenTest 15/0 with src/test/resources/jackson2-golden untouched (git status empty); Jackson3WireContractTest 12/0; GoldenFixturesIntegrityTest 5/0; ConfigKeyContractTest 9/0; PublicStorefrontController{,MyOrders,Idor}Test 24/0"
        status: pass
    human_judgment: false
  - id: D5
    description: "Nothing else moved: the unit suite, the storefront / order / review integration regression, the two guest-order ITs outside it, and every repository gate are green; the two runtime-parity gates are recorded as expected-red until the 37-D gate rebuilds"
    verification:
      - kind: unit
        ref: "./gradlew :core-java:cleanTest :core-java:test -> 1741 tests / 0 failures / 1 skipped (1719 after 37-26 + 22 new)"
        status: pass
      - kind: integration
        ref: "./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --tests storefront.* order.* review.* security.RlsContractTest integration.OpenApiSnapshotTest -> 28 classes / 202 tests / 0 failures (29 failures before 68be2323, all 429 order-cap-exceeded on fixed identities); ShopPauseIntegrationTest 14/0, PublicOrdersPaginationIntegrationTest 4/0"
        status: pass
      - kind: other
        ref: "check-doc-citations PASS 29 verified / 0 violations (after re-pointing four application.yml citations twice); check-doc-metrics PASS 37/37; check-no-create-extension PASS 81; check-env-example-contract PASS 19; check-strict-scoping-default PASS; check-no-object-store-residue PASS 51 entries; k8s check-env-contract PASS 76 / 174. Recorded, not fixed here: check-openapi-snapshot-fresh FAIL rc 1 on A-1 (contactVerified x2 and 37-26's maxLineQuantity ABSENT FROM THE RUNNING SERVICE, compose still 6a825865); docs-freshness rc 1 (counts drift, schema_version 81 matches)"
        status: pass
    human_judgment: false

duration: 45 min
completed: 2026-10-11
status: complete
---

# Phase 37 Plan 27: Open cash-order caps per identity and the contact-verified fact Summary

**One customer identity can no longer flood a shop with open cash orders. `CashOrderCaps` counts the OPEN cash orders (PENDING, CONFIRMED, PREPARING or READY, `payment_status = 'NONE'`, not voided) one trimmed lower-cased email or one normalised phone holds at a shop over V81's three partial indexes, and the storefront refuses the next one 429 `order-cap-exceeded` — its own type, never the auto-retried `rate-limited`, typed `axis` and `cap`, no `Retry-After`, because the slot frees when the kitchen completes an order, not when time passes — inside the reserved idempotency work, so a refusal leaves no order and no reservation and the same key succeeds once one completes. "07700 900123" and "+44 7700 900123" are one phone; the cap is per shop; card orders are outside it on both sides. And every storefront order now records a STORED fact about who placed it: `contact_verified` is true only when the request carried a verified customer token (the `X-Customer-Token` `/orders/mine` already reads) for the same address, false otherwise, NULL ("not recorded") for a vendor's order — exposed NON_NULL on both order DTOs so the kitchen badge (37-30) derives nothing. V81 also reserves `cancel_reason_code` (37-29) and the `@NotAudited` `client_ip_digest` (37-28, D-27) so the rest of 37-D adds no migration.**

## Performance

- **Duration:** 45 min of code commits (plan start 2026-10-11T22:18:32Z; last code commit 68be2323 at 23:03:02Z); SUMMARY after
- **Started:** 2026-10-11T22:18:32Z
- **Completed:** 2026-10-11
- **Tasks:** 3 of 3; each RED (`test(37-27)`, RED_EVIDENCE_OK) then GREEN (`feat(37-27)`); two test-only follow-up commits; no REFACTOR commit was needed
- **Files modified:** 33 across the eight code commits (1618 insertions, 30 deletions), plus this SUMMARY and deferred-items.md

## Accomplishments

- **Task 1 (tracer).** `CashOrderCapIntegrationTest` drives `POST /api/v1/public/shops/{slug}/orders` on Testcontainers Postgres with the real indexes and the real idempotency store.
  - **RED** (`2d750178`; `evidence/37-27-red-task1.json`, 2 ran / 2 failed, RED_EVIDENCE_OK): the fourth cash order for the same address was ACCEPTED — `HTTP 201 orderNumber ORD-…-247A9E48 status PENDING` — the UXT-041 shape; the V81 shape arm failed on the absent columns. No compile scaffold was needed: the IT is HTTP and JSON.
  - **GREEN** (`c2feada7`): V81 (three columns, two mirrors, the CHECK, three partial indexes, COMMENTs; no backfill, no tenant loop); `CancelReason`; `Order.contactVerified` / `cancelReasonCode` / `@NotAudited clientIpDigest`; `OrderRepository.countOpenCashOrdersByEmail` over the index; `CashOrderCaps` (email axis); `OrderCapExceededException` and `handleOrderCapExceeded` (429, typed `axis` and `cap`, no `Retry-After`); the call on the COD branch of `placeGuestOrder` before the save; `jtoye.storefront.cash-caps.per-email: 3`; schema V81 in CLAUDE.md, AGENTS.md, README.md and `docs/metrics.json`. IT 2/0, RlsContractTest 7/0, PublicStorefrontServiceTest 44/0, ConfigKeyContractTest 9/0, IdempotencyFingerprintGoldenTest 15/0.
  - **Tracer gate** (interactive, `end-of-phase`, automated-only `<verify>`): the verify command re-run on the committed tree, rc 0, fresh reports, 0 `UP-TO-DATE`; expansion continued.
- **Task 2.** **RED** (`3de5b25b`; `37-27-red-task2.json` 7 ran / 1 failed — the `+44` fourth order ACCEPTED 201; `37-27-red-task2-unit.json` `CashOrderCapsTest$PhoneAxis` 3 / 1 and nine `PhoneNormalisation` spellings unnormalised in the same run; both RED_EVIDENCE_OK). The prefix-neighbour, other-shop, card-exemption, closed-orders and V81 arms were already green on Task 1's email axis and cash predicate and are recorded as such. The RED commit carries compile scaffolding only: the three-cap constructor, a behaviour-less `normalisePhone`, `findOpenCashOrderPhones`, the `per-phone` / `per-ip` keys. **GREEN** (`70804146`): `normalisePhone` (digits only; `0` -> `44`; `00` dropped; null when no digit; exact) and the phone axis over the shop's open cash phones, email judged first. IT 7/0, unit 20/0.
  - **Break arm (recorded):** `String digits = phone;` in place of the digit strip → 7 ran / **1 failed**, exactly the `+44` arm (`expected: 429 but was: 201`) on a fresh report; restore from a scratch copy verified by md5 `7b152f31d129449606dee7d87e6675b3` before and after and an empty `git status` for the file; closing clean run 7/0.
  - **Write-side card exemption** (`d2b6c0ea`, test-only): the IT cannot PLACE a card order (no Stripe key in the test profile), so `PublicStorefrontServiceTest` gained two guards — a card checkout never consults the caps even with the identity at the cap (`verify(never())` on both repository queries), and a cash checkout at the cap is refused before any save or event. Both were green when written (the cash-branch-only call is Task 1's) and are recorded as guards, not RED. 46/0.
- **Task 3.** **RED** (`72f61c38`; `37-27-red-task3.json` 12 ran / 4 failed, RED_EVIDENCE_OK): every contact_verified arm read NULL where `true` or `false` was expected — nothing wrote the column. The vendor-NULL arm and the eight cap arms passed. A FIRST run was VOID (see Issues) and is not the record. **GREEN** (`1dae5f54`): the controller reads an optional `X-Customer-Token` and resolves it to a verified email or null (a failed verification is ignored, never a 401); `createGuestOrder(slug, request, key, verifiedEmail)` with the three-argument form delegating; `placeGuestOrder` writes `contactVerified = normalise(verified) equals normalise(customerEmail)`; `contactVerified` NON_NULL on `OrderDto` and `OrderDetailDto` with explicit `OrderMapper` lines; the OpenAPI snapshot regenerated (+26 lines). IT 12/0, OpenApiSnapshotTest 1/0, fingerprint golden 15/0 with its fixtures untouched, Jackson3WireContractTest 12/0, GoldenFixturesIntegrityTest 5/0, ConfigKeyContractTest 9/0, the three controller slices 24/0.
- **Regression** (`68be2323` records the fix): full `:core-java:test` **1741 / 0 failures / 1 skipped**; `:core-java:integrationTest` over `storefront.*`, `order.*`, `review.*`, `RlsContractTest` and `OpenApiSnapshotTest`: **28 classes / 202 tests / 0 failures** — after 29 failures in the first run, all `429 order-cap-exceeded` from five pre-existing ITs that placed every arm's order with one fixed email or phone at one shop (and one collision between arms of the new IT). Fixed as RESEARCH Pitfall 9 prescribes: unique identities, never a raised default. `ShopPauseIntegrationTest` 14/0 and `PublicOrdersPaginationIntegrationTest` 4/0 (the two guest-order ITs outside the set). Gates: doc citations PASS 29 / 0, doc metrics PASS 37 / 37, no-create-extension PASS 81, env-example 19, strict-scoping PASS, residue PASS, k8s env contract PASS 76 / 174.

## Task Commits

1. **Task 1: a fourth open cash order for the same email is refused with a typed 429** — `2d750178` (test, RED), `c2feada7` (feat, GREEN)
2. **Task 2: phone normalisation, per-shop scope, card exemption** — `3de5b25b` (test, RED), `70804146` (feat, GREEN), `d2b6c0ea` (test: the write-side card exemption guards)
3. **Task 3: the contact-verified fact on every storefront order** — `72f61c38` (test, RED), `1dae5f54` (feat, GREEN)
4. **Follow-up (Rule 1):** `68be2323` (test: six integration tests give every arm its own contact identity)

**Plan metadata:** this SUMMARY and `deferred-items.md` in the docs commit that follows. STATE.md, ROADMAP.md and REQUIREMENTS.md are the orchestrator's.

`commits: 8` is `git rev-list --count 0449b35b..68be2323`; `gsd_run check evaluation-scope --plan 37-27 --commits-only` → `status: resolved`, 8 commits.

## TDD Gate Compliance

| Task | RED | GREEN | REFACTOR |
|------|-----|-------|----------|
| 1 | `2d750178` test(37-27), RED_EVIDENCE_OK (`37-27-red-task1.json` 2 / 2) | `c2feada7` feat(37-27) | none needed |
| 2 | `3de5b25b` test(37-27), RED_EVIDENCE_OK x2 (`37-27-red-task2.json` 7 / 1; `37-27-red-task2-unit.json` 3 / 1 on `CashOrderCapsTest$PhoneAxis`, with 9 / 10 `PhoneNormalisation` failures in the same run) | `70804146` feat(37-27) | none needed |
| 3 | `72f61c38` test(37-27), RED_EVIDENCE_OK (`37-27-red-task3.json` 12 / 4) | `1dae5f54` feat(37-27) | none needed |

Semantic assessment for all three: the target executed and failed on its planned assertion for the intended reason (no cap; no phone axis and no normaliser; nothing writes `contact_verified`), with the context and Testcontainers up (sibling arms placed and read back real orders in the same run) and no compile, load, fixture or syntax fault in the recorded run. **Format notes:** Gradle writes one JUnit XML per `@Nested` class, so the Task 2 unit record's report is `TEST-…CashOrderCapsTest$PhoneAxis.xml` and its `targetTest` is the method name with parentheses (no `@DisplayName` on the method); the IT records use the `@DisplayName`, as 37-26 found. Task 3's first IT run was VOID on a fixture fault and is not recorded as RED (Issues).

## Files Created/Modified

- `db/migration/V81__order_abuse_bounds.sql`: the three columns, two mirrors, `ck_orders_cancel_reason_code`, three partial indexes, COMMENTs
- `order/CancelReason.java`: the enum; `exception/OrderCapExceededException.java`: axis + cap; `storefront/CashOrderCaps.java`: the bean, both normalisers, the two axes
- `common/GlobalExceptionHandler.java`: `handleOrderCapExceeded` (429)
- `order/Order.java`: `contactVerified`, `cancelReasonCode`, `@NotAudited clientIpDigest`; `order/OrderRepository.java`: `countOpenCashOrdersByEmail`, `findOpenCashOrderPhones`
- `storefront/PublicStorefrontService.java`: the bean; the cap check on the COD branch; the four-argument `createGuestOrder`; `contactVerified` written; `storefront/PublicStorefrontController.java`: `X-Customer-Token` read, `verifiedEmailOrNull`
- `order/dto/OrderDto.java`, `order/dto/OrderDetailDto.java`: `contactVerified` (NON_NULL, `@Schema`); `order/OrderMapper.java`: explicit mappings
- `application.yml`: `jtoye.storefront.cash-caps.{per-email, per-phone, per-ip}` (17 lines incl. comments)
- `CashOrderCapIntegrationTest` (12 arms), `CashOrderCapsTest` (20): new; `PublicStorefrontServiceTest` (bean + two guards); `AllergyNoteAck`, `OrderAllergenSnapshot`, `CustomerRecordedAllergenSet`, `GuestCheckoutIdempotency`, `GuestOrderAllergenAck` ITs (unique identities)
- `docs/api/openapi-snapshot.json`: regenerated (+26); `docs/metrics.json`: `schema_version` 81; CLAUDE.md, AGENTS.md, README.md: V81 schema lines
- `k8s/LOCAL.md`, `.planning/codebase/{STACK,INTEGRATIONS}.md`: four `application.yml` citations re-pointed (+11 then +6), and STACK.md's `resilience4j:` anchor corrected from 806 to the measured 884
- `evidence/37-27-red-task{1,2,2-unit,3}.json`; `deferred-items.md` (four entries)

## Decisions Made

See `key-decisions`. The two that depart from the plan's letter: `voided_at IS NULL` joins the index predicate and the queries (a void keeps the status, so without it a voided order would hold a slot); and the phone axis normalises in Java over the shop's open phones rather than an expression index, so there is one normaliser, not two to hold equal.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Four doc citations into `application.yml` moved, twice**
- **Found during:** Task 1 GREEN (`check-doc-citations.sh` 4 violations after the 11-line `storefront:` block) and Task 2 GREEN (again, +6 for the two further keys)
- **Fix:** `k8s/LOCAL.md:609` and `:1634` `:572` → `:583` → `:589`; `STACK.md:84` `822-873` → `833-884` → `839-890`; `INTEGRATIONS.md:14` `870-874` → `881-885` → `887-891`; gate PASS 29 / 0. STACK.md's prose anchor `resilience4j:` "at 806" was found already wrong (the key is at 884, measured; the gate does not check prose anchors) and corrected in the same edit.
- **Committed in:** `c2feada7`, `70804146`

**2. [Rule 3 - Blocking] `PublicStorefrontServiceTest` built the service without the new constructor argument**
- **Found during:** Task 1 GREEN (two `new PublicStorefrontService(` sites)
- **Fix:** a real `CashOrderCaps` over the mocked repository (counts 0 unless an arm stubs them); re-done for the three-cap constructor in Task 2's RED.
- **Committed in:** `c2feada7`, `3de5b25b`

**3. [Rule 1 - Bug] Five pre-existing integration tests assumed an unbounded identity**
- **Found during:** the integration regression (29 failures across `AllergyNoteAck`, `OrderAllergenSnapshot`, `CustomerRecordedAllergenSet`, `GuestCheckoutIdempotency`, `GuestOrderAllergenAck`, all `429 order-cap-exceeded` on PHONE or EMAIL: every arm placed its order with one fixed `+4477009003xx` or `cluster-e@example.com` at one shop and the orders stay PENDING across arms)
- **Fix:** the phone derived from the arm's address (stable, so the D-08 and idempotency replay arms resend an identical body); `OrderAllergenSnapshot` mints an email per request; `GuestCheckoutIdempotency` derives email and phone from the key, and its header-only arm builds one body and sends it twice as a retry does (two keyless builds were two customers and a 422 mismatch); the seven-order history arm completes each order as it is placed (a seven-order history is a customer whose earlier orders completed; the history and the N+1 probe are status-agnostic). The new IT's own three-digit phone suffix had collided between arms in the group run and became six digits. Never a raised default (Pitfall 9).
- **Verification:** the six classes 62/0, then the full set 28 classes / 202 tests / 0 failures.
- **Committed in:** `68be2323`

### Plan wording that did not hold (recorded, not relabelled)
- **"a fourth for ' a@x.com'"** (leading space). `GuestOrderRequest.customerEmail` is `@Email`, which refuses a leading space with 400 before any cap runs, so the arm folds CASE (`Cap.Buyer…@Example.com` then the lower-cased spelling) and the normaliser's trim is pinned in the unit test instead.
- **"a card order beyond the cap -> not refused by the cap"** as an IT arm. The test profile has no Stripe key, so no IT can place a card order; the exemption is proven in two halves — open CARD orders do not COUNT (IT, with a three-open-cash-orders control that does) and a card checkout is never CHECKED (service unit test with the payment gate stubbed).
- **"per-ip:5" in `CashOrderCaps`.** Declared, bound and refused below 1, as the plan says; it counts nothing until 37-28 writes the digest (deferred-items records the erasure and retention it then owes).
- **The index predicate** gained `voided_at IS NULL` beyond the plan's statuses and cash column; the handler carries `cap` beside `axis`. Both additive; both recorded in `key-decisions`.
- **"RED (fourth order 201)"**: measured exactly so (`HTTP 201 … 247A9E48`).

**Total deviations:** 3 auto-fixed (2 blocking, 1 bug), 5 plan-wording records. **Impact:** no scope creep; every fix was required for a gate to read the tree correctly or for an existing test to stop assuming the behaviour this plan removes.

## Issues Encountered

- **Task 3's first RED run was VOID**: `when(verifier.verifiedEmail(VERIFIED_TOKEN))` invoked the mock while the `anyString()` throw-stub was already live, so the stubbing itself threw and all 12 arms failed in `@BeforeEach` (`401 Customer authentication required`). Not RED — a fixture fault; the fixture moved to `doThrow` / `doReturn` and the re-run (12 / 4, the planned NULLs) is the record.
- **The group regression exposed a collision inside the new IT**: a three-digit random phone suffix shared by two arms made the other-email-token arm 429 on PHONE in the group run but not alone. Six random digits now; recorded because "passed alone" is not "passes in the suite".
- **The RED-evidence classifier matches the report's testcase names**: for a `@Nested` unit class that is the method name with parentheses, per nested-class report; the IT records use the `@DisplayName`.
- `scripts/check-doc-citations.sh` prints the pre-existing VOID for `docs/ops/terminal-states.yaml` (37-24's deferred item); rc 0.
- `scripts/docs-freshness.sh` rc 1 (5560 invocations at the measurement): this plan's two new Java test files and two methods, on top of the 37-24..37-26 drift; owed to the 37-D PR docs step (deferred-items). `schema_version` 81 is already in step.

## Known Stubs
None introduced. `CashOrderCaps.perIp()` is a bound configuration value with no counter yet; it is 37-28's axis, recorded in deferred-items, not a stub.

## Threat Flags

None new. T-37-68 (fake cash-order flood) is mitigated by D1 and D3 — per-shop open-order caps by email and phone, typed 429, break arm red; T-37-69 (a forged token marking a contact verified) by D4 — the token is verified by `CustomerJwtVerifier` (signature, issuer, expiry, `email_verified`), a token for another address records false, an invalid token records false and never changes the outcome; T-37-70 (the IP digest in audit history) by D2 — `@NotAudited`, no audit column (information_schema assertion; `grep 'orders_aud.*client_ip_digest'` rc 1); T-37-SC held — nothing installed (`build.gradle.kts`, `package-lock.json` untouched). New surface, recorded: the guest-order POST now reads `X-Customer-Token`; it reaches only the existing verifier, a failure yields `null`, and the only thing stored is a boolean about THIS order's address. Residual, by design: the email and phone axes bound the identity the customer TYPED, so a flood that invents a fresh address and number per order is bounded only by the IP axis (37-28) — D-18's third axis exists for exactly that.

## User Setup Required

None — the three caps have code defaults, no overlay needs an operator input, and `X-Customer-Token` is sent by the frontend's existing server-side proxy when a customer is signed in (no client change is required for the fact to be false, which it is for every guest).

## Next Phase Readiness

- **37-28 (IP axis, D-27):** V81 has reserved `client_ip_digest` and `idx_orders_open_cash_ip`; `CashOrderCaps.perIp()` is bound; `ClientIpResolver.resolve(request)` (37-24) is the identity to digest. 37-28 owes: the HMAC key config wired through compose / `.env.example` / k8s (and, if wanted, the three caps with it), the write on the storefront path, the count, the erasure null in `GdprService` and a declared retention purge (deferred-items).
- **37-29 (bulk reject):** `orders.cancel_reason_code` and `CancelReason` are in place; no migration needed.
- **37-30 (kitchen badge):** `OrderDetailDto.contactVerified` is the stored fact — `false` renders "Unverified contact", absent renders nothing (UI-SPEC D1 / F20).
- **37-31 (E2E):** any spec that trips a cap uses unique contact details per order (the probe note); the defaults stay.
- **37-D gate:** the compose stack (all 11 services Up) still runs `6a825865`'s images. `check-openapi-snapshot-fresh.sh` is FAIL on A-1 (`contactVerified` x2 and `maxLineQuantity` absent from the running service); live probes are in deferred-items.
- **Requirements:** RWO-041 is declared by this plan; the orchestrator's `requirements.ready-ids` gate decides whether a sibling plan (37-28 / 37-29 / 37-30 may) also declares it.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-11*

## Self-Check: PASSED

- FOUND: `V81__order_abuse_bounds.sql` (contains `client_ip_digest` x5), `CancelReason.java`, `OrderCapExceededException.java`, `CashOrderCaps.java`, `CashOrderCapIntegrationTest.java` (contains `order-cap-exceeded` x3), `CashOrderCapsTest.java`, the four `evidence/37-27-red-*.json`; `PublicStorefrontService.java` contains `cashOrderCaps` x4
- FOUND commits (ancestors of HEAD): `2d750178`, `c2feada7`, `3de5b25b`, `70804146`, `d2b6c0ea`, `72f61c38`, `1dae5f54`, `68be2323`; `git rev-list --count 0449b35b..68be2323` = 8; `gsd_run check evaluation-scope --plan 37-27 --commits-only` → `status: resolved`, 8 commits
- TDD gate: 3 `test(37-27)` RED commits each preceding its `feat(37-27)` GREEN; 2 further `test(37-27)` follow-ups; 0 `refactor(37-27)`
