---
quick_id: 261104-vendor-rulings
slug: vendor-rulings
date: 2026-11-04
status: complete for #962, #825, #821; #452 gap 1 design only
---

# Summary: owner rulings 2026-11-04 (persona close-out), lane `vendor-rulings`

Each of the first three is an owner ruling made 2026-11-04 and is recorded as such in the code.

## What changed

- **#962 zero VAT for an unregistered seller.** `TraderIdentityService.isVatRegistered`, `VatCalculator.forSeller`,
  applied at placement in `PublicStorefrontService` and `OrderService.createOrder`. Ledger, summary, CSV and the
  Article 15 export follow because the ledger rate is read from the order. No backfill (rewriting historic
  ledger rows would fabricate a record). A tenant with no trader identity at all counts as "no declared VAT
  number", so existing demo/dev tenants without one now record zero VAT on NEW orders (the dev seeder's trader
  identity from 31.1-12 is unchanged).
- **#825 ready-by estimate.** V91 `shops.prep_time_minutes`, `orders.estimated_ready_at`, `_aud` mirrors;
  `ReadyByEstimate`; shop form field; `EstimatedReadyBy` (order page, `/track`), kitchen card badge, printed
  ticket row. Always labelled an estimate; absent = nothing shown.
- **#821 unaccepted-order alert.** V91 `orders.unaccepted_notified_at` (+ `_aud`), `UnacceptedOrderSweep`,
  `OrderEmailContextFactory` (extracted from `OrderStateChangeListener`), `EmailNotificationService
  .sendOrderAwaitingAcceptance`, kitchen `KdsUnacceptedBadge`, dashboard `UnacceptedOrdersBanner`, config
  `jtoye.orders.unaccepted-alert.{enabled,threshold,sweep-interval-ms}`.
- OpenAPI snapshot regenerated (`updateOpenApiSnapshot`): `prepTimeMinutes`, `estimatedReadyAt`,
  `unacceptedNotifiedAt`, all NON_NULL so the golden DTO bytes are unchanged.

## RED to GREEN

- **#962:** `SellerVatRegistrationIntegrationTest` run on the unfixed tree (evidence/red962.txt): 8 tests, 5 failed
  (unregistered storefront, unregistered vendor, no-identity tenant, unregistered ledger all `expected ZERO but
  was STANDARD`; the registered ledger aggregate arm failed on a test-harness flaw, an unscoped aggregate on a
  superuser connection summing every tenant, fixed by scoping to the seller's shop). After the fix
  (evidence/green962.txt): 8 of 8 pass. The registered/"keeps VAT after deregistering" arms were green both
  before and after, as intended (unchanged behaviour).
- **#821:** `UnacceptedOrderSweepIntegrationTest` green 4 of 4 (evidence/green821.txt). Break arm: with the
  per-tenant GUC pin and `TenantContext.set` removed, 4 of 4 RED under NOSUPERUSER (evidence/break821.txt); the
  pin was restored and the file compared against the saved copy. Fixture trap found on the way: the V5
  `update_orders_updated_at` trigger resets `updated_at` on any UPDATE, which first made the voided and
  no-email arms pass or fail for the wrong reason; every fixture is now written in its INSERT.
- **#825:** `OrderReadyByEstimateIntegrationTest` 5 of 5, `ReadyByEstimateTest` 4 of 4; frontend tests for the
  helper, component, badges, banner and shop form (49 new Jest tests). These are new-feature tests that cannot
  compile or render against the unfixed tree, so their RED is "absent"; the behavioural break arm is the
  explicit-null test (an emptied field must send `null`).
- A review catch of my own: a STOMP frame from the sweep was a second STOMP publish site, which
  `StompPublishCallSiteTest` forbids (1 failure); removed, the board's once-a-minute read shows the badge.

## Gates (rc)

- `:core-java:test` (all unit tests): first run 2109 tests, 32 failed: 1 was mine (`StompPublishCallSiteTest`,
  fixed by removing the sweep's STOMP publish) and 31 were `TenantChannelInterceptor*` /
  `WebSocketConfigTest` `NoClassDefFoundError`/Mockito failures caused by compiling in the same build dir while
  the test task ran (I ran `updateOpenApiSnapshot` concurrently). Re-run of the websocket, order, storefront,
  finance and shop unit packages after the fix: rc=0. NOT re-run: the whole unit suite once more end to end.
- `:core-java:integrationTest` over finance, order, storefront, gdpr, onboarding, shop, integration, boot4,
  notification, payment, dev, config and `RlsContractTest`: 818 tests, 1 failed (`MoneyPathExecutionIntegrationTest`
  mixed-VAT arm: its tenant had no trader identity, so it correctly recorded ZERO now; fixture given a VAT
  number). Re-run of that class, the three new ITs and `OpenApiSnapshotTest`: rc=0. Not run: the other ~600
  integration classes (security, media, webhook, ...), which do not touch VAT, orders' new columns or shops.
- Frontend: `npm test` 308 suites / 3652 tests rc=0; `npm run lint` 0 errors (26 pre-existing warnings);
  `npm run build` rc=0. Not run: Playwright, and no browser look at the new UI (no rebuilt compose stack).
- `scripts/docs-freshness.sh --write` then `scripts/check-doc-metrics.sh` PASS (39 claims);
  `scripts/check-branch-behind-base.sh` PASS.
- V91 (renumbered from V90, taken by PR 1115): applied by every Testcontainers run above, including
  `RlsContractTest` (no new table, no policy change).

## #452 gap 1: second-shop onboarding. NOT implemented; design for the owner

Judged L, beyond one coherent PR, and containing decisions that are product/legal rather than mechanical.

Facts found: `uq_onboarding_tenant UNIQUE(tenant_id)` (V43); about 20 call sites resolve the onboarding by
tenant (`VendorOnboardingService` x3 plus every lifecycle method through `requireOnboarding(tenantId)`,
`GateChainRunner` x2, `OnboardingSubmitterResolver` x3, `TraderIdentityService` x3, `StripeConnectService` x4,
`DemoDataSeeder` x4); every `OnboardingController` route (`/me`, `/submit`, `/resubmit`, `/withdraw`,
`/go-live`, `/company-number`) is tenant-addressed; the onboarding page is 1173 lines and its "live" copy is
driven by the one onboarding's status.

Proposed design (needs the owner's ruling on the three questions below):
1. Migration: drop `uq_onboarding_tenant`, add `UNIQUE (tenant_id, shop_id)`; existing rows already carry
   `shop_id` (NOT NULL since V43), so no data change and no per-tenant loop is needed (confirm no row has a NULL).
2. Addressing: every route takes `?shopId=` (default: the tenant's only onboarding, so a single-shop tenant
   is unaffected); `requireOnboarding(tenantId, shopId)`; `shopAccessService.require(shopId, SHOP_MANAGER)` on
   each, which already exists on create.
3. The state machine stays the sole writer of `Shop.published`: GO_LIVE publishes only the onboarding's own shop.
4. Gates split by what they are about: BUSINESS_VERIFIED (Companies House), TRADER_IDENTITY and Stripe Connect
   are tenant facts and would be inherited (copied PASSED, same evidence) by a second onboarding;
   FOOD_HYGIENE_RATING and ALLERGEN_DATA_COMPLETE are per shop and re-run.
5. Dashboard: a "Add another shop" entry that creates a DRAFT onboarding for a chosen shop with no onboarding,
   a shop picker on the onboarding page, and the "storefront is live" copy keyed to the selected shop.
6. Proof: NOSUPERUSER NOBYPASSRLS ITs like the existing onboarding ITs: two shops one tenant, independent
   states, cross-shop `published` isolation, STAFF/other-shop refusal.

Questions for the owner: (a) is the `model` (MARKETPLACE or WHITE_LABEL, which drives Stripe routing and lives
on the tenant's Stripe account) per tenant or per shop; (b) may a second shop inherit the Companies House and
trader-identity gates, or must it re-verify; (c) does a second shop need approval (PENDING_APPROVAL) like the first.

## Not done / residuals

- The order-received email does not quote the estimate (see PLAN).
- No MCP tool added: there is no shop-write tool; `read_orders` passes the DTO through.
- Dev/prose docs that state "Current schema version: V89" were not edited (another lane holds V90; the claims
  collide at rebase).

## Review round 1 of PR 1119: three admissible findings in `UnacceptedOrderSweep`

All fixed test-first. RED = `evidence/review1-red.txt` (7 tests, 3 failed, on the code as reviewed); GREEN =
`evidence/review1-green.txt` (7 of 7). `OrderReadyByEstimateIntegrationTest`,
`OrderStateChangeListenerIdempotencyIntegrationTest`, `OpenApiSnapshotTest` and `RlsContractTest` also pass (rc=0);
the API and schema shape did not change (only a comment in V91).

1. **Starvation.** RED: 250 unpaid PENDING orders older than a real overdue cash order: the real one was never
   stamped (`Expecting actual not to be null`). Fix: every skip predicate (voided, PENDING, not yet alerted, not
   VENDOR-placed, not awaiting payment) is in `ALERTABLE_PREDICATE`, used by both `CANDIDATES_SQL` and
   `CLAIM_SQL`; the Java skip is gone. The awaiting-payment rule is the SQL form of
   `OrderService.isAwaitingUpfrontOrDepositPayment`. GREEN: the real order is alerted and none of the 250 is stamped.
2. **False email for shop-created orders.** Decision: a `placed_via = 'VENDOR'` order is excluded from the sweep
   ENTIRELY (no stamp, no email), not just from the email. Reason: the badge and banner copy say "the customer has
   been told", which would be false for it, and the shop created the order so it is not waiting on an unseen
   customer order. A NULL `placed_via` (pre-V69) is a customer order and stays in. RED: the VENDOR order was stamped.
   GREEN: VENDOR unstamped and unemailed, STOREFRONT and NULL controls stamped, exactly 2 emails.
3. **Optimistic lock and audit.** The claim no longer touches `version`. RED: an accept holding the pre-sweep
   version failed with `ObjectOptimisticLockingFailureException`. GREEN: the same accept commits and the order is
   CONFIRMED. Audit: left UNAUDITED and documented (migration header, `CLAIM_SQL` javadoc). Precedent:
   `ClientIpDigestRetentionSweep`'s native `UPDATE orders SET client_ip_digest = NULL` writes no `orders_aud` row
   either. The stamp is operational, not a business decision; who accepted, cancelled or voided the order is
   audited by that transition.

The six recorded findings were left alone, as instructed.
