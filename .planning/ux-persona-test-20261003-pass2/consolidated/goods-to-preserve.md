# Goods to preserve — persona user-testing 2026-10-03 (passes 1 + 2)

Every positive finding (40: 4 pass-1 table rows + 36 pass-2 entries), deduplicated into themes. These are regression guards for the fixes in `catalogue.json`: a fix that breaks one of these is a regression even if its own test is green (Incremental Betterment Doctrine). Never file these as issues.

## Order placement cannot double-order (idempotency under taps, tabs, offline, lost responses)

- **P2-CHA-12** (Nkechi(P2)): Double-tapping Place order on a lossy Slow 3G connection creates exactly one order
- **P2-CHA-13** (Nkechi(P2)): A lost reply followed by a retry replays the same order (same Idempotency-Key), with no duplicate
- **P2-RAV-20** (Ravi(P2)): Retrying an order with the same key never duplicates it, and reusing the key for a different order is refused with a typed error
- _Also (pass-1 narrative):_ Sam (pass 1): triple-tap, two tabs, offline retry and lost-response retry each gave exactly one order; 'Add' becomes a stepper so frantic taps add one.

## Server-authoritative pricing and order validation

- **P2-KYL-P2** (Kyle(P2)): Pricing is server-authoritative: price and total tampered into the request body are ignored
- **P2-KYL-P3** (Kyle(P2)): Order items are isolated: another shop's product, an unavailable product, and a non-existent product are all rejected 404
- **P2-FUN-19** (Funmi(P2)): Positive: an order containing a sold-out item is refused by the server
- **P2-CHA-14** (Nkechi(P2)): The server refuses unsafe orders in clear, human words: unavailable item, below minimum, shop closed
- **P2-RAV-22** (Ravi(P2)): Bad or ambiguous agent input is refused clearly, with typed, field-level errors
- **P2-CHA-16** (Nkechi(P2)): A 20-line, 40-unit basket is comfortable: no limits hit, totals and VAT consistent, and the basket survives a forced sign-out
- _Also (pass-1 narrative):_ CAVEAT: the availability check holds on the STOREFRONT path only; vendor API and MCP orders accept unavailable products (cluster api-orders-accept-unavailable). Extend it, do not copy it.

## Allergen safety chain (order-time snapshot, kitchen banner, checkout gate, PPDS label)

- **P2-REG-15** (Bola(P2)): Per-line allergen snapshot is recorded at order time and shown to the vendor
- **P2-REG-16** (Bola(P2)): Allergen gate at checkout, plus allergens emphasised per dish and on the PPDS-format label
- **P2-CHA-15** (Nkechi(P2)): The kitchen ticket carries an allergen banner plus per-line allergen chips from the order-time snapshot
- **P2-TUN-15** (Tunde(P2)): Allergen banner and per-line chips are unmissable; money/GDPR endpoints refuse a non-admin
- **P2-MAR-P2** (Marcus(P2)): Checkout allergen gate is fully operable and announced
- _Also (pass-1 narrative):_ Priya (pass 1): the gate refuses without the tick, role=alert, focus moved, keyboard-operable; bold allergen words + named chips; 'We do not store your allergies'. Ade: 14 plain-English allergen boxes and a per-product label.

## Tenant and shop isolation

- **F1** (Dele(P1)): Cross-tenant product read → 404 for all 3 IDs
- **F4** (Dele(P1)): No cross-tenant path via edge or MCP
- **P2-KEM-20** (Kemi(P2)): Shop scoping on the API is tight: 30+ attempts on another site were refused
- **P2-KEM-21** (Kemi(P2)): Revoking one of several grants takes effect on the very next request
- **P2-KEM-22** (Kemi(P2)): Good guardrails: cross-shop product rejected in an order; last-admin lockout guard; kitchen explains one-shop-at-a-time; ledger VAT correct
- _Also (pass-1 narrative):_ Dele: cross-tenant reads return 404 (not 403); consistent RFC 7807. Claire: clear shop-scoped staff roles.

## Order-tracking privacy

- **F3** (Dele(P1)): Order tracking can't be enumerated (number + email)
- **P2-KYL-P5** (Kyle(P2)): Order tracking cannot be enumerated and idempotency keys cannot be abused
- **P2-GRA-22** (Grace(P2)): Signed in, tracking finds my order without the order number
- _Also (pass-1 narrative):_ Jordan: a real guest checkout, with tracking by order number + email.

## Security hygiene (SSRF, XSS, webhook secrets)

- **F2** (Dele(P1)): Webhook SSRF blocked (HTTPS + resolved private/loopback/link-local deny)
- **P2-KYL-P1** (Kyle(P2)): No stored XSS: HTML/script in name and notes, RTL-override chars and an emoji flood are stored raw but render as inert text everywhere, and emails are plain text
- **P2-RAV-21** (Ravi(P2)): Webhook signing secrets are handled carefully, and the delivery log, auto-pause banner and replay are clear
- _Also (pass-1 narrative):_ Dele: SSRF check runs on the RESOLVED IP.

## Kitchen live operations

- **P2-FUN-17** (Funmi(P2)): Positive: the phone Orders list updates live - new orders appeared in 1-5 seconds without refreshing
- **P2-FUN-18** (Funmi(P2)): Positive: bumping tickets on the kitchen screen is fast and big-buttoned, and every step reaches the customer
- **P2-FUN-20** (Funmi(P2)): Positive: a kitchen tablet left open for 38 minutes stays live and still receives and bumps new tickets
- **P2-TUN-13** (Tunde(P2)): Hard offline is handled honestly and catches up instantly
- **P2-TUN-14** (Tunde(P2)): Sign-out on the shared tablet really ends the session
- _Also (pass-1 narrative):_ Claire: per-shop kitchen banner and 'Print all'.

## Transparent pricing and payment wording

- **P2-REG-17** (Bola(P2)): No drip pricing: delivery fee shown on list, menu and basket; total incl. delivery and VAT before ordering
- **P2-GRA-21** (Grace(P2)): Cash on delivery is stated plainly: no card details needed
- _Also (pass-1 narrative):_ Jordan: honest, consistent fees; unit counts and totals agree on every surface. Ade: the price stated plainly.

## Step-by-step notifications

- **P2-GRA-23** (Grace(P2)): An email at every step, and the Ready email knows it's a delivery
- _Also (pass-1 narrative):_ Jordan: orders placed instantly and the email arrives within seconds.

## Review gating (with a caveat)

- **P2-REG-18** (Bola(P2)): Reviews are gated to completed orders, a matching email and one per order
- **P2-KYL-P4** (Kyle(P2)): Review abuse is blocked: you cannot review a shop you never ordered from, an order you don't own, a non-completed order, or the same order twice
- _Also (pass-1 narrative):_ CAVEAT: P2-KYL-P4 says you cannot review a shop you never ordered from, but P2-REG-01 shows that holds only ACROSS tenants; within one tenant a buyer of shop A can review shop B. Preserve every gate listed; fix the shop check (cluster cross-shop-review).

## Accessibility foundations

- **P2-MAR-P1** (Marcus(P2)): Product-detail dialog is a textbook accessible modal
- **P2-MAR-P3** (Marcus(P2)): Strong page structure and control naming across the storefront
- **P2-GRA-24** (Grace(P2)): At large text the product sheet, checkout and menu fit, and forgot-password returns me signed in to the shop
- _Also (pass-1 narrative):_ Priya: visible focus rings, a skip link, no horizontal scroll at 200%.

## API contract fidelity

- **P2-RAV-19** (Ravi(P2)): The published API contract matches the live responses exactly
- _Also (pass-1 narrative):_ Claire: storefront JSON-LD and meta; ~0.94 s throttled-mobile LCP.

## UK-time correctness across midnight

- **P2-CHA-18** (Nkechi(P2)): An order placed at 00:01 BST is labelled 4 Oct everywhere I looked, consistent with UK time

## Pass-1 'Goods to preserve' narrative (not table rows; listed for completeness)

- **Ade(P1)**: Landing headline and food-first look; automatic geocoding of the shop postcode; photo pipeline (4000x3000 → light WebP); lovely mobile storefront; honest 'what we don't do' boundaries.
- **Jordan(P1)**: Postcode search with real distances; menu photos, dietary chips, allergen panel; sticky basket bar; basket survives sign-up and guest orders appear in My Orders; sign-out clears the basket on a shared phone.
- **Priya(P1)**: Privacy notice's allergen section; vendor sees the note verbatim with per-line chips snapshotted at order time.
- **Sam(P1)**: Plain-English validation next to each field; forgiving postcode/phone formats; basket survives a lapsed session with silent token refresh; honest legal pages (item-by-item cookie list, retention schedule); sign-out clears basket and customer cookies.
- **Dele(P1)**: Order tracking bound to number + email; consistent RFC 7807 errors.
- **Claire(P1)**: Candid boundary statements; privacy notice; CSV import with template + photo scan; VAT ledger.

## Fixes that put these goods at risk

| Cluster | Guard |
|---|---|
| UXT-007 Checkout never re-validates the stored basket: stale prices, removed and sold-out items surface only as a charge or a bare error | Order placement cannot double-order; Server-authoritative pricing — the re-validation step must reuse the same Idempotency-Key and must not move price authority to the client. |
| UXT-009 The customer's allergen acknowledgement is never sent to or stored by the server | Allergen safety chain — keep the refuse-not-disable gate, role=alert, focus move; add the server record without weakening the client gate. |
| UXT-010 The customer never sees the allergen set recorded on their order, and it can differ from what they acknowledged | Allergen safety chain — the order-time snapshot (V63) stays the source; the customer view reads it, never a live join. |
| UXT-004 Every staff login is a tenant-wide Group admin by default (JIT provisioning, strict-scoping off) | Tenant and shop isolation — keep the last-admin lockout guard (P2-KEM-22) and next-request revoke (P2-KEM-21) while changing the JIT default. |
| UXT-003 Revoking a manager's last shop grant silently makes him tenant-wide Group admin | Tenant and shop isolation — as above. |
| UXT-020 A new order makes no sound and never reaches the kitchen screen until someone confirms it on another page | Kitchen live operations — keep live list updates in 1–5 s, big bump buttons and the 38-minute stay-live behaviour. |
| UXT-021 When the all-day kitchen tablet's session lapses the board silently becomes a sign-in page | Kitchen live operations — sign-out on a shared tablet must still really end the session (P2-TUN-14). |
| UXT-006 The public rate limiter trusts any X-Forwarded-For value, so rotating it defeats the limit | Order-tracking privacy — tracking stays bound to number + email; idempotency keys remain non-abusable. |
| UXT-005 A buyer of one shop can publish a 5-star review on a different shop of the same tenant | Review gating — keep completed-order, matching-email and one-per-order gates. |
| UXT-029 The order confirmation is rendered in place at /checkout: off-screen, unannounced, and lost on refresh | Order placement cannot double-order — a confirmation route must not re-POST on refresh. |
| UXT-040 Asked for one shop's menu, an AI agent gets every product in the business with allergens only as an integer | API contract fidelity — any MCP/OpenAPI change must keep the published contract matching live responses. |
| UXT-015 Stripe JS and fraud cookies load on a cash-only checkout, contradicting the cookie policy | Transparent payment wording; Order placement idempotency — lazy-loading Stripe must not change the card path's single-submit behaviour. |
