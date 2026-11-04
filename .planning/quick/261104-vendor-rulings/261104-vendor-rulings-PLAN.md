---
quick_id: 261104-vendor-rulings
slug: vendor-rulings
date: 2026-11-04
type: execute
requirements: ["#821", "#825", "#962", "#452 (gap 1, design only)"]
---

# Persona close-out, lane `vendor-rulings`: owner rulings 2026-11-04

All four are OWNER RULINGS made 2026-11-04 (persona close-out). Each is recorded as such in the code comment
where a future reader needs the why.

## #962 - a seller with no declared VAT number records zero VAT

- **Ruling:** from now on a sale by a seller with NO declared VAT number (trader identity absent, or its
  `vat_number` NULL; the field V71 introduced, #816) is recorded with 0 VAT in the order AND the ledger (and so
  the Article 15 export). VAT-registered sellers unchanged. NO backfill of past orders: rewriting historic
  ledger rows would fabricate a record.
- **Fix:** `TraderIdentityService.isVatRegistered(tenantId)` (refuses an unpinned tenant: under FORCE RLS a
  silent "no row" would zero-rate a registered seller); `VatCalculator.forSeller(rate, registered)`; applied at
  order PLACEMENT in both writers (`PublicStorefrontService.placeGuestOrder`, `OrderService.createOrder`). The
  ledger rate is read from the order, so the ledger, summary, CSV and DSAR export follow with no further change.
- **Tests:** `SellerVatRegistrationIntegrationTest` (Testcontainers): both writers x registered/unregistered, a
  tenant with no identity row, the ledger row and the shop-scoped ledger aggregate, and "placed while registered
  keeps its VAT after deregistering".

## #825 - default preparation time and an ESTIMATED ready-by time

- **Ruling:** shop-level default prep time (minutes, nullable = not set) in shop settings; confirmation,
  tracking page and kitchen ticket show an estimated ready-by (placed/accepted + prep), labelled as an estimate;
  NULL shows nothing.
- **Fix:** V91 `shops.prep_time_minutes` (+ `shops_aud`), `orders.estimated_ready_at` (+ `orders_aud`) - a
  snapshot, so a later settings edit does not move a time the customer was shown. One rule, `ReadyByEstimate`,
  used at placement (both writers) and re-stamped from acceptance on CONFIRM. A scheduled-slot order gets none
  (it carries its own window). Shop form field (explicit-null clears, like the free-delivery threshold),
  `EstimatedReadyBy` on the order page and `/track`, `KdsReadyByBadge` on both kitchen card kinds, a row on
  the printed ticket.
- **Not done (recorded):** the order-received EMAIL does not quote the estimate (would widen the
  `OrderEmailSummary` record every email test constructs; the order page it links to shows it).
  No MCP tool: there is no shop-write MCP tool to extend; `read_orders` passes the DTO through, so
  `estimatedReadyAt` is visible to agents with no change.

## #821 - unaccepted PENDING orders: vendor alert + one customer email, no auto-cancel

- **Ruling:** an order left PENDING too long (config `jtoye.orders.unaccepted-alert.threshold`, default 10m)
  raises a vendor alert (kitchen board + dashboard) and emails the customer once. NO auto-cancel.
- **Fix:** V91 `orders.unaccepted_notified_at` (+ `orders_aud`, no backfill) and a partial index.
  `UnacceptedOrderSweep` (`@Scheduled`, every 60 s): per-tenant loop over the `tenants` registry, each tenant in
  its own transaction with `TenantContext` + GUC pinned (FORCE RLS); a conditional UPDATE claims each order, so
  it is notified once even with two sweepers; the stamp commits before the email, so a send failure is
  at-most-once, never a repeat. Skips voided orders, orders awaiting payment (the shop cannot accept them yet;
  same predicate as the accept guard), and anything not PENDING. The stamp is the vendor alert: kitchen New-lane
  badge and dashboard banner (the board re-reads every minute; no new STOMP publish site, which StompPublishCallSiteTest forbids). The customer email
  honours the ORDERS opt-out. `OrderEmailContextFactory` extracted from `OrderStateChangeListener` so both
  senders write from the same context.
- **Why not the V46 outbox:** the outbox carries state-change events whose consumers are the status-email
  listeners; this is not a state change. The `unaccepted_notified_at` claim is the exactly-once ledger and the
  email service is already `@Async`.
- **Clock:** `updated_at` (V5 trigger refreshes it on every UPDATE) - can only delay an alert, never raise one early.
- **Tests:** `UnacceptedOrderSweepIntegrationTest` (two tenants, NOSUPERUSER): stamping, negatives (young,
  confirmed, voided, unpaid), idempotence + exactly one email, no-email, DTO carries the stamp; break arm
  (pin removed) turns all 4 red.

## #452 gap 1 - second-shop onboarding: DESIGN ONLY (see SUMMARY)

Judged L and not one coherent PR; per the brief, 1, 2 and 4 first and the design reported.

## Gates

`./gradlew :core-java:test`, targeted `integrationTest`, `RlsContractTest`, `updateOpenApiSnapshot`,
frontend `npm test` / `lint` / `build`, `docs-freshness.sh --write`, `check-doc-metrics.sh`,
`check-branch-behind-base.sh`.
