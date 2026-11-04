# Quick 261104-storefront: persona close-out, public storefront / checkout / tracking

Branch `fix/persona-storefront` from origin/main (d1a04035). Brief:
`~/IdeaProjects/JToye_OaaS_2026-evidence/persona-closeout-20261104/FIX-BRIEF.md`.

Each item is re-checked on this branch first (a triage note is a lead, not proof), then test first
(RED recorded in SUMMARY.md), then fix (GREEN).

## Issues and the fix for each

| Item | Fix | Test |
|---|---|---|
| #829 cash-only revealed at the bottom, card icon on Place order | one `PaymentMethodNote` (components/storefront) read from `shop.acceptsCardPayments`; shown on the shop card, the shop header details bar, the basket and the top of checkout; Place order icon follows the method | jest per surface + the button icon |
| #845 Order again, remembered address | "Order again" on a past order in My Orders re-adds the order's lines that are still available, shows what changed; the last delivery address is prefilled for a signed-in customer | jest; stored state carries an owner stamp, tested through sign-in A, lapse, sign-in B (asserts the STORED stamp) |
| #846 + #813 review entry point | new `POST /public/shops/{slug}/orders/{orderNumber}/review` (X-Customer-Token, no email in the URL) + a "Rate your order" form on a COMPLETED order, with the notice that the review is public and shows first name and initial. Old `?email=` endpoint kept (documented, tested by ReviewCrossShopIntegrationTest) and marked deprecated. Moderation is out of scope | Java integration test (Testcontainers, NOSUPERUSER path via the real endpoint) + jest |
| #850 remainder | `/track` "Order not found" gets role=alert; order page email prompt gets a label and an h1; "Copied!" announced through a polite live region | jest |
| #876 UXT-103/104/105 | offline message with "it may already be placed"; checkout draft kept for Back/Forward (sessionStorage, slug keyed, no payment data, cleared on success and sign-out); one validation pass with an error summary and focus on the first invalid field | jest |
| #877 UXT-107/109/110/111/112 | engineering sub-items only (see SUMMARY) | jest |
| Cookie banner #854, SW-CLR-12, SW-TND-03 | compact one-row card below `sm` (<= 10% of 390x664), flush, link inline; `/invite` carved out like `/pay` | Playwright e2e/cookie-notice-compact.spec.ts (stack-free, wired into the per-PR browser job) + jest |
| SW-CLR-08 | default og:image + twitter card from an existing brand asset in the root metadata | jest on the metadata |
| SW-PRY-07 | allergen panel prints the shop's phone/email beside "tell the kitchen", honest copy when none is stored | jest |

## Threat notes
- Reviews: identity comes from the verified customers-realm token only (`CustomerJwtVerifier`), never from a
  URL or body field; the order must belong to that email, be at that shop, be COMPLETED, one per order
  (UNIQUE(order_id), V27); tenant pinned from the published shop slug (SEC-01 gate), RLS unchanged; the
  review shows first name + initial only (ReviewerDisplayName). No new table, no migration.
- Idempotency: one review per order is enforced by UNIQUE(order_id), so a replay can never create a second
  row (it is refused, not duplicated): provably idempotent for the contract's purposes.
- MCP: reviewing is a customer act on the storefront, not a vendor/operator capability; no MCP tool (recorded).
- Order again / address prefill: client storage holds a basket-shaped list of product ids and a delivery address,
  both user-scoped; both carry an owner stamp, confirmed by a write and removed only by explicit sign-out.
  Nothing is stored server-side (a server-side address store is a retention/erasure decision for the owner).
- Cookie notice: /invite writes no storage and loads no third-party script; the notice only informs.

## Gates
`npx jest <paths>`, full `npm test`, `npm run lint`, `npm run build`; Playwright
`e2e/cookie-notice-compact.spec.ts`; `./gradlew test --tests` for the review endpoint;
`scripts/docs-freshness.sh --write`, `check-doc-metrics.sh`, `check-branch-behind-base.sh`,
`check-openapi-snapshot-fresh.sh` / OpenApiSnapshotTest after the endpoint.
