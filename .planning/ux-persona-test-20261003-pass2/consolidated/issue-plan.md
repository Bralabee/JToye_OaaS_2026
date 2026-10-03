# Issue plan — persona user-testing 2026-10-03 (pass 1 + pass 2)

Nothing here has been filed. This is the exact set to file once approved. Source of truth: `catalogue.json`.
No AI-attribution lines in any body (owner ruling 2026-08-30).

## Totals

- Clusters: **123**
- Covered by an existing OPEN issue ("same"): **8 clusters → 6 comments** on #102, #208, #452, #460, #587, #727
- To file: **115 clusters → 103 new issues** (98 single-cluster issues + 5 P3 polish bundles covering 17 clusters)
- Plus **1 epic** tracking issue (below), so **104 new issues + 6 comments** in total.

New label: **`ux-persona-test`** (colour suggestion `#5319e7`, description: "Found by persona user-testing (2026-10-03 passes 1–2)"). Existing labels reused: `P0`–`P3`, `bug`, `enhancement`, `compliance`, `security`, `accessibility`, `tech-debt`.

Filing order: P0 first (they gate a first real tenant), then P1, then the epic body's links are filled in.

## A. Comments on existing open issues

### #102 — [P2-11] No production tenant lifecycle: cannot onboard/bill/pay-out/offboard; single pooled Stripe account

Comment body:

> **Reproduced by persona user-testing (2026-10-03).**
> - **UXT-033 (P1)** The published £39/location subscription has no billing built and no billing UI. #102 'cannot bill'; Phase 30 PAY-02 closes its remainder.
>   Repro: Read /for-operators pricing → Look for billing in the vendor dashboard. Actual: None; /competitive admits 'no SaaS subscription billing built'
>   Found by C-02. Evidence: `.planning/ux-persona-test-20261003/08-sceptical-evaluator-claire/ (refs: same + dtxt_dashboard.txt)`.
> - **UXT-034 (P1)** Vendors have no payments or payouts surface (/dashboard/payments returns 404). #102 'cannot pay-out'; Phase 30 PAY-03.
>   Repro: As a vendor open /dashboard/payments and /dashboard/payments/connect. Actual: 404; Connect reachable only via TenantAdminController POST
>   Found by C-03. Evidence: `.planning/ux-persona-test-20261003/08-sceptical-evaluator-claire/ (refs: dtxt_dashboard_payments*.txt)`.
> Label suggestion: add `ux-persona-test`.

### #208 — [AI-6] Complete the WhatsApp conversational channel (edge parser → real ordering flow)

Comment body:

> **Reproduced by persona user-testing (2026-10-03).**
> - **UXT-094 (P2)** A vendor cannot connect their own WhatsApp; WhatsApp ordering is one platform-wide setting. #208 slice 1 (tenant routing of a WhatsApp business number). New: the webhook returns 503 'signing not configured' and is env-configured for one tenant.
>   Repro: POST to the WhatsApp webhook → Read edge-go cmd/edge/main.go WHATSAPP_DEFAULT_TENANT_ID / WHATSAPP_DEFAULT_SHOP_ID. Actual: 503 'webhook signing not configured' with Retry-After 300. One tenant and shop for the whole platform, set by environment variable.
>   Found by P2-RAV-18. Evidence: `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/edge-openapi.json`.
> Label suggestion: add `ux-persona-test`.

### #452 — QA-A/F-H5+F-H7: two lifecycle dead-ends — no 2nd-shop onboarding path, no staff invite (needs a product decision)

Comment body:

> **Reproduced by persona user-testing (2026-10-03).**
> - **UXT-024 (P1)** A second or third shop can never go live, while onboarding says 'Your storefront is live'. #452 gap 1 (no 2nd-shop onboarding path). New: the onboarding page tells owner and manager 'Your storefront is live' while all shops are Draft, and the vendor has no draft preview (ADE-10).
>   Repro: Create kemi-p2-peckham and kemi-p2-lewisham on /dashboard/shops (both Draft) → Open /dashboard/onboarding: no shop picker, no way to start onboarding for a shop → POST /api/v1/onboarding {model:MARKETPLACE, shopId:<peckham>} → POST /api/v1/onboarding/submit and /go-live → GET /api/v1/public/shops/tenant-b-probe (the onboarded shop). Actual: 409 'An onboarding already exists for this tenant'; submit/go-live 400 'cannot apply event … in state LIVE'. The onboarding page (for Kemi and for her manager) says 'Live — Your storefront is live and visible to customers' but all 4 shops are Draft, and the onboarded shop's public URL returns 404.
>   Found by P2-KEM-03, ADE-10. Evidence: `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/s07-onboarding.txt`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/16-onboarding-kemi.png`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/19-onboarding-api-2nd-shop.txt`.
> - **UXT-025 (P1)** There is no way to invite a staff member: they must self-register, then auto-become Group admin. #452 gap 2 (no staff invite). New: combined with the JIT default, the only onboarding route for staff grants full admin.
>   Repro: Open /dashboard/staff as admin-user-b. Actual: Copy: 'Team members appear here only after they have signed in once… this page cannot send them an invite.' Vendor signup does not exist (pass-1 ADE-01), and on first sign-in the person becomes an implicit tenant-wide Group admin (P2-KEM-01/02).
>   Found by P2-KEM-14. Evidence: `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/26-grant-bayo-peckham.txt`.
> Label suggestion: add `ux-persona-test`.

### #460 — UX-4: the product has no concept of locality — device location unused, shop coordinates inert, no delivery radius (item 7)

Comment body:

> **Reproduced by persona user-testing (2026-10-03).**
> - **UXT-030 (P1)** Delivery is accepted to any UK postcode with no radius check at checkout. #460 'no delivery radius'. Phase 33 added radius DISCOVERY; checkout still accepts Belfast/Manchester/300+ miles for a London shop.
>   Repro: POST a DELIVERY order to Mama Ade's (Peckham) with addressPostcode 'EH2 2AN' (Edinburgh), 2x jollof to clear the £10 minimum → -> 201, deliveryFee £3.50, total £21.48, PENDING. Actual: Any valid-format UK postcode is accepted with the flat delivery fee; no distance gate at order time (discovery computes distance but checkout ignores it).
>   Found by J-01, P2-KYL-05. Evidence: `.planning/ux-persona-test-20261003/04-hungry-customer-jordan/ (refs: 21, 23; orders …B090D3F3, …6CC30687)`, `.planning/ux-persona-test-20261003-pass2/16-prankster-kyle/k3-absurd.json (far-delivery via followup curl)`, `.planning/ux-persona-test-20261003-pass2/16-prankster-kyle/xff-probe.txt`.
> Label suggestion: add `ux-persona-test`.

### #587 — Outbound webhooks give a receiver 127 seconds before the event is permanently lost — the 1-hour backoff cap is unreachable

Comment body:

> **Reproduced by persona user-testing (2026-10-03).**
> - **UXT-038 (P1)** Webhook endpoints auto-pause after ~46 s of failures and retries stop after 5 attempts, with nobody told. #587 (127 s window before events are lost). New: auto-pause trips at ~46 s, only 5 of 8 attempts run, and no email/notification is sent.
>   Repro: Subscribe https://example.com/ravi-gsheet-hook to Orders (it returns 405) → Let two order events occur → Read the delivery log and the subscription status; search Mailhog. Actual: Two events × 5 attempts = 10 consecutive failures → AUTO_PAUSED at 22:14:24 (first event 22:13:38); the remaining attempts were terminally FAILED with 'subscription not active: AUTO_PAUSED'. Mailhog: 0 messages.
>   Found by P2-RAV-10. Evidence: `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/deliveries1.json`, `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/w08-list-auto-paused.png`, `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/w09-delivery-log-failed.png`.
> Label suggestion: add `ux-persona-test`.

### #727 — Sync-created products are orphaned: upsertProduct never sets shop_id and SyncItem cannot supply one

Comment body:

> **Reproduced by persona user-testing (2026-10-03).**
> - **UXT-017 (P0)** Products created by /sync/batch belong to no shop, are orderable at any shop, and record the wrong allergens. #727 calls this latent (no producer). Pass 2 reproduced it live and found the safety consequence: an order for a duplicate-titled orphan records allergenMask 0 where the real dish declares 256. Ask to re-prioritise.
>   Repro: Sync a product sku RAVI-EPOS-2 titled 'Jollof Rice', allergenMask 0 → GET /api/v1/products → shopId null; not on any storefront → MCP list_products shows two 'Jollof Rice' items (masks 0 and 256) → MCP create_order at Mama Ade's Kitchen with the orphan id → MCP read_orders orderId → line allergenMask. Actual: The orphan is created (confirms issue #727 live), is hidden from storefronts even though the spec says null = 'available on all tenant shops', is listed to agents and is accepted on a Mama Ade's order. The line records allergenMask 0, but Mama Ade's real Jollof Rice declares 256.
>   Found by P2-RAV-05. Evidence: `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/products-after-sync.json`, `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/mcp-jollof-candidates.tsv`, `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/mcp-orphan-order-detail.json`.
> Label suggestion: add `ux-persona-test`.

## B. New issues (one per cluster)

| # | Cluster | P | Title | Labels | Links |
|---:|---|---|---|---|---|
| 1 | UXT-001 | P0 | DSAR erasure is marked completed while nothing is erased for storefront customers | ux-persona-test, P0, bug, compliance | regression-of-closed #84 |
| 2 | UXT-002 | P0 | A verified DSAR access request is never fulfilled (ACCESS delivery not implemented) | ux-persona-test, P0, bug, compliance | none |
| 3 | UXT-003 | P0 | Revoking a manager's last shop grant silently makes him tenant-wide Group admin | ux-persona-test, P0, bug, security | related #285, #499 |
| 4 | UXT-004 | P0 | Every staff login is a tenant-wide Group admin by default (JIT provisioning, strict-scoping off) | ux-persona-test, P0, bug, security | related #285 |
| 5 | UXT-005 | P0 | A buyer of one shop can publish a 5-star review on a different shop of the same tenant | ux-persona-test, P0, bug, compliance | none |
| 6 | UXT-006 | P0 | The public rate limiter trusts any X-Forwarded-For value, so rotating it defeats the limit | ux-persona-test, P0, bug, security | regression-of-closed #88 |
| 7 | UXT-007 | P0 | Checkout never re-validates the stored basket: stale prices, removed and sold-out items surface only as a charge or a bare error | ux-persona-test, P0, bug | none |
| 8 | UXT-008 | P0 | An advertised '20% OFF' promotion is displayed on the storefront but never applied to the order | ux-persona-test, P0, bug, compliance | none |
| 9 | UXT-009 | P0 | The customer's allergen acknowledgement is never sent to or stored by the server | ux-persona-test, P0, bug, compliance | related #427 |
| 10 | UXT-010 | P0 | The customer never sees the allergen set recorded on their order, and it can differ from what they acknowledged | ux-persona-test, P0, bug, compliance | related #427 |
| 11 | UXT-011 | P0 | The kitchen screen ticket hides the customer's note (e.g. 'severe peanut allergy') and the fulfilment type | ux-persona-test, P0, bug | none |
| 12 | UXT-012 | P0 | A product whose ingredients name an allergen (e.g. 'butter (MILK)') with no box ticked saves and shows as 'No allergens' | ux-persona-test, P0, bug, compliance | related #427 |
| 13 | UXT-013 | P0 | Shops go live with a failed FSA match, self-approved by the vendor, while the site claims 'UK food-hygiene verified' | ux-persona-test, P0, compliance | related #453 |
| 14 | UXT-014 | P0 | Customers are never given the seller's legal identity or any way to contact the shop | ux-persona-test, P0, compliance | none |
| 15 | UXT-015 | P0 | Stripe JS and fraud cookies load on a cash-only checkout, contradicting the cookie policy | ux-persona-test, P0, bug, compliance | none |
| 16 | UXT-016 | P0 | Updating a product without quantityInStock silently turns stock tracking off | ux-persona-test, P0, bug | none |
| 17 | UXT-018 | P0 | A completed, paid order can be deleted, leaving ledger rows that point at nothing | ux-persona-test, P0, bug | none |
| 18 | UXT-019 | P0 | The platform's own registered office is not published anywhere on the site | ux-persona-test, P0, compliance | none |
| 19 | UXT-020 | P1 | A new order makes no sound and never reaches the kitchen screen until someone confirms it on another page | ux-persona-test, P1, bug | none |
| 20 | UXT-021 | P1 | When the all-day kitchen tablet's session lapses the board silently becomes a sign-in page | ux-persona-test, P1, bug | none |
| 21 | UXT-022 | P1 | A vendor cannot pause or stop taking orders: no pause switch, no holiday closure, and free-text hours fail open and cannot be cleared | ux-persona-test, P1, enhancement | none |
| 22 | UXT-023 | P1 | Vendors cannot set a delivery fee, free-delivery threshold or collection-only: orders are charged £0 delivery and collection-only shops take deliveries | ux-persona-test, P1, enhancement | none |
| 23 | UXT-026 | P1 | CSV import ignores the selected shop and hides imported items from every storefront; a menu cannot be copied to another site | ux-persona-test, P1, bug | none |
| 24 | UXT-027 | P1 | Per-shop dashboard and finance show the whole business's takings, and site managers get 'No financial data yet' instead of their shop's numbers | ux-persona-test, P1, bug | none |
| 25 | UXT-028 | P1 | Every shop update regenerates the public URL slug, so shared links and QR codes break | ux-persona-test, P1, bug | none |
| 26 | UXT-029 | P1 | The order confirmation is rendered in place at /checkout: off-screen, unannounced, and lost on refresh | ux-persona-test, P1, bug | related #409 |
| 27 | UXT-031 | P1 | A prospective vendor has no way in: 'Start your application' dead-ends at a login, and no sales or support contact exists | ux-persona-test, P1, enhancement | related #102 |
| 28 | UXT-032 | P1 | No merchant terms, pricing page, VAT basis or card-fee figure: /legal/terms, /pricing, /contact and /about all 404 | ux-persona-test, P1, compliance | none |
| 29 | UXT-035 | P1 | A vendor has no way to give a developer API credentials; the only path is the owner's password plus the confidential core-api client secret | ux-persona-test, P1, enhancement | related #206 |
| 30 | UXT-036 | P1 | An order taken by an AI agent through MCP stays DRAFT and the kitchen never sees it | ux-persona-test, P1, bug | none |
| 31 | UXT-037 | P1 | /sync/batch has no stock or availability field and silently skips unknown items while reporting SUCCESS | ux-persona-test, P1, enhancement | related #727 |
| 32 | UXT-039 | P1 | Anonymous traffic can exhaust the edge gateway's single process-wide rate limit and block every vendor's sync; its 429 is untyped | ux-persona-test, P1, bug | related #413 |
| 33 | UXT-040 | P1 | Asked for one shop's menu, an AI agent gets every product in the business with allergens only as an integer | ux-persona-test, P1, bug | none |
| 34 | UXT-041 | P1 | Anyone can place many fake cash orders in seconds with throwaway contact details | ux-persona-test, P1, enhancement | related #461 |
| 35 | UXT-042 | P1 | Item quantity has no upper bound: a £19 billion order is accepted and shown on the dashboard | ux-persona-test, P1, bug | none |
| 36 | UXT-043 | P1 | An allergy request is a generic free-text note: no alert to the vendor, no acknowledgement, never echoed to the customer | ux-persona-test, P1, enhancement, compliance | none |
| 37 | UXT-044 | P1 | Reviews publish the reviewer's full checkout name with no notice, policy or moderation | ux-persona-test, P1, bug, compliance | none |
| 38 | UXT-045 | P1 | The local compose stack can never exercise the card-payment path (Stripe env-var names and build-time key mismatch) | ux-persona-test, P1, tech-debt | related #461, #538, #61 |
| 39 | UXT-046 | P1 | Products have no VAT-rate choice; everything is booked as Standard 20% | ux-persona-test, P1, bug | related #81 |
| 40 | UXT-047 | P1 | 'VAT (incl. 20%)' is shown for every vendor, with no VAT-registration status or number captured | ux-persona-test, P1, compliance | none |
| 41 | UXT-048 | P1 | Menu cards show allergens as an unnamed count, 'Add' works without seeing them, and 'none declared' is never stated | ux-persona-test, P1, bug, compliance | none |
| 42 | UXT-049 | P2 | A double-tap on 'Start Preparing' jumps the order to READY and emails the customer 'Ready!' with no undo | ux-persona-test, P2, bug | none |
| 43 | UXT-050 | P2 | Kitchen mute survives sign-out and the next person's icon shows sound on while new orders are silent | ux-persona-test, P2, bug | none |
| 44 | UXT-051 | P2 | Cancelling an order is one unconfirmed tap with no reason, next to Confirm on small phone buttons | ux-persona-test, P2, bug | none |
| 45 | UXT-052 | P2 | Unanswered orders stay Pending forever and the customer is never told | ux-persona-test, P2, enhancement | none |
| 46 | UXT-053 | P2 | Orders and Customers have no search, so an order cannot be found by customer name at the counter | ux-persona-test, P2, enhancement | none |
| 47 | UXT-054 | P2 | Every kitchen ticket cuts the order number to 'ORD-…', so tickets look identical | ux-persona-test, P2, bug | none |
| 48 | UXT-055 | P2 | The kitchen board is not built for a wall tablet: wasted space, small text, newest-first ordering and very tall tickets | ux-persona-test, P2, bug | related #699 |
| 49 | UXT-056 | P2 | No ready-by or delivery time exists anywhere: tickets, confirmation, tracking or emails | ux-persona-test, P2, enhancement | related #458 |
| 50 | UXT-057 | P2 | Order confirmation and every status email omit the shop, items, total and address, and come from inconsistent senders | ux-persona-test, P2, bug | related #649 |
| 51 | UXT-058 | P2 | Finance has no 'today', no date range, no export and no cash/card split | ux-persona-test, P2, enhancement | none |
| 52 | UXT-059 | P2 | A completed cash order stays 'Payment status NONE / Unpaid' while counted as revenue | ux-persona-test, P2, bug | related #461 |
| 53 | UXT-060 | P2 | Cash-only is revealed only at the bottom of checkout, and the Place order button shows a card icon | ux-persona-test, P2, bug | related #461 |
| 54 | UXT-061 | P2 | Menu 'Add' buttons are visible but ignore taps until hydration (4.2 s on 4G, 14 s on Slow 3G) | ux-persona-test, P2, bug | related #507 |
| 55 | UXT-062 | P2 | Saving a shop overwrites its banner with the logo URL | ux-persona-test, P2, bug | none |
| 56 | UXT-063 | P2 | Products cannot be deleted once they have a photo or any order (even cancelled): misleading 409 | ux-persona-test, P2, bug | none |
| 57 | UXT-064 | P2 | Deleting a shop orphans its products, and editing an orphan silently moves it to 'All Shops' | ux-persona-test, P2, bug | related #727 |
| 58 | UXT-065 | P2 | The image dialog stays on 'Processing…' although the server has the image ACTIVE within ~6 s | ux-persona-test, P2, bug | none |
| 59 | UXT-066 | P2 | The 'Publish to storefront' checkbox is ignored, or throws away the whole shop edit with a raw developer error | ux-persona-test, P2, bug | none |
| 60 | UXT-067 | P2 | Internal strategy pages are public, expose a repo path, and contradict the landing page (incl. a 'payouts: Full' claim) | ux-persona-test, P2, compliance | none |
| 61 | UXT-068 | P2 | Test and demo data is visible to customers and vendors (E2E 20% OFF promo, weeks-old test orders, a real person's name and email) | ux-persona-test, P2, tech-debt | none |
| 62 | UXT-069 | P2 | There is no account page: data access and erasure are mailto-only although a backend intake exists | ux-persona-test, P2, enhancement, compliance | none |
| 63 | UXT-070 | P2 | The DSAR confirmation link does not open on compose and shows raw JSON when it does | ux-persona-test, P2, bug, compliance | none |
| 64 | UXT-071 | P2 | The cookie policy omits keys that hold the customer's email and id and survive sign-out | ux-persona-test, P2, compliance | none |
| 65 | UXT-072 | P2 | Order contact details are barely validated, and API/MCP orders need no customer contact at all | ux-persona-test, P2, bug | none |
| 66 | UXT-073 | P2 | Vendors have no bulk-reject and no fraud signal for junk orders | ux-persona-test, P2, enhancement | none |
| 67 | UXT-074 | P2 | On a slow network, returning after 5 minutes signs the customer out (parallel refreshes burn the single-use token) | ux-persona-test, P2, bug | regression-of-closed #465 |
| 68 | UXT-075 | P2 | Customers cannot order ahead for a time, and a closed shop takes no pre-orders | ux-persona-test, P2, enhancement | none |
| 69 | UXT-076 | P2 | No 'order again' and no remembered address: a weekly order takes 10 taps and 85 keystrokes | ux-persona-test, P2, enhancement | none |
| 70 | UXT-077 | P2 | Customers have no way to leave a review; the only path is an unauthenticated API call with an email in the URL | ux-persona-test, P2, enhancement | none |
| 71 | UXT-078 | P2 | Password-reset links expire after 5 minutes | ux-persona-test, P2, bug | none |
| 72 | UXT-079 | P2 | Keycloak sign-in and registration fall below the storefront's accessibility bar and hide password rules until failure | ux-persona-test, P2, bug, accessibility | related #545 |
| 73 | UXT-080 | P2 | At large text sizes the basket, tracker and shop header truncate or overflow | ux-persona-test, P2, bug, accessibility | none |
| 74 | UXT-081 | P2 | Tracking pages are silent and unlabelled for screen readers (status changes, current step, copy button, lookup result, field name, contrast) | ux-persona-test, P2, bug, accessibility | none |
| 75 | UXT-082 | P2 | Shop cards on the kitchen list have no accessible name ('link, link, link') | ux-persona-test, P2, bug, accessibility | none |
| 76 | UXT-083 | P2 | Pressing Add/Remove drops focus to the page body and the basket announcement doubles the count without naming the item | ux-persona-test, P2, bug, accessibility | related #272 |
| 77 | UXT-084 | P2 | Basket, checkout, confirmation and tracking all share the title 'J'Toye — Discover Local Vendors' | ux-persona-test, P2, bug, accessibility | none |
| 78 | UXT-085 | P2 | On mobile the cookie banner covers content and keyboard focus and is the 32nd tab stop | ux-persona-test, P2, bug, accessibility | none |
| 79 | UXT-086 | P2 | A delivery customer's tracking page says 'Ready for collection' | ux-persona-test, P2, bug | related #502 |
| 80 | UXT-087 | P2 | A kitchen hand sees far more customer personal data than needed to cook | ux-persona-test, P2, bug, compliance | none |
| 81 | UXT-088 | P2 | A vendor cannot show who prepared an order or when: no timeline, no export | ux-persona-test, P2, enhancement | none |
| 82 | UXT-089 | P2 | 86ing an item or changing a price takes four taps and ~30 s in a long form | ux-persona-test, P2, enhancement | none |
| 83 | UXT-090 | P2 | Nothing asserts at deploy time that customer email verification is on; dev runs with it off | ux-persona-test, P2, tech-debt | related #462 |
| 84 | UXT-091 | P2 | The basket shows no allergens and checkout's combined set has no per-item attribution | ux-persona-test, P2, enhancement, compliance | none |
| 85 | UXT-092 | P2 | No 'may contain' field exists, label use-by is computed at download time, and records use US date format | ux-persona-test, P2, enhancement, compliance | related #427, #82 |
| 86 | UXT-093 | P2 | There is no allergen filter, and searching 'peanut' or 'gluten free' returns nothing | ux-persona-test, P2, enhancement | none |
| 87 | UXT-095 | P2 | The MCP server cannot be added to hosted ChatGPT/Claude connectors (no OAuth discovery, 5-minute tokens) | ux-persona-test, P2, enhancement | related #203 |
| 88 | UXT-096 | P2 | Order webhooks carry only ids and status, with no items, totals or customer | ux-persona-test, P2, enhancement | related #205 |
| 89 | UXT-097 | P2 | Vendor API and MCP orders accept products marked unavailable | ux-persona-test, P2, bug | none |
| 90 | UXT-099 | P3 | With the API unreachable but the socket up, the board says 'Live' for ~50 s while dropping an order | ux-persona-test, P3, bug | related #106 |
| 91 | UXT-100 | P3 | After access is removed the kitchen keeps showing that shop's tickets (with PII and live buttons) and blames the connection | ux-persona-test, P3, bug | related #627 |
| 92 | UXT-102 | P3 | A customer cannot cancel an order | ux-persona-test, P3, enhancement | none |
| 93 | UXT-106 | P3 | The basket is device-local: not restored on sign-in and not shared across devices | ux-persona-test, P3, enhancement | related #459 |
| 94 | UXT-108 | P3 | Menu items cannot be customised (no modifiers) | ux-persona-test, P3, enhancement | none |
| 95 | UXT-113 | P3 | There is no marketing-consent choice or preferences page, and /unsubscribe says 'contact the vendor' | ux-persona-test, P3, enhancement | related #592 |
| 96 | UXT-118 | P3 | Another shop's order returns 403 not 404, and public image URLs embed the tenant UUID | ux-persona-test, P3, bug, security | none |
| 97 | UXT-120 | P3 | No email campaigns, loyalty or vouchers | ux-persona-test, P3, enhancement | none |
| 98 | UXT-121 | P3 | No custom storefront domain (SUSPECTED) | ux-persona-test, P3, enhancement | none |
| 99 | UXT-098 + UXT-101 + UXT-115 + UXT-117 | P3 | Polish bundle: dashboard-polish (4 clusters) | ux-persona-test, P3, bug, security | related #762; related #699 |
| 100 | UXT-103 + UXT-104 + UXT-105 | P3 | Polish bundle: checkout-polish (3 clusters) | ux-persona-test, P3, bug | none |
| 101 | UXT-107 + UXT-109 + UXT-110 + UXT-111 + UXT-112 + UXT-116 | P3 | Polish bundle: storefront-polish (6 clusters) | ux-persona-test, P3, bug, enhancement | related #546; related #619, #460 |
| 102 | UXT-114 + UXT-119 | P3 | Polish bundle: public-content (2 clusters) | ux-persona-test, P3, compliance | none |
| 103 | UXT-122 + UXT-123 | P3 | Polish bundle: integrator-polish (2 clusters) | ux-persona-test, P3, bug | none |

### Issue bodies

#### [UXT-001] DSAR erasure is marked completed while nothing is erased for storefront customers

Title: `DSAR erasure is marked completed while nothing is erased for storefront customers` · Labels: ux-persona-test, P0, bug, compliance

**UXT-001 · P0 · blocker · CONFIRMED** — Data-protection failure: Article 17 requests report success while every storefront customer's PII and public review survive.

**Steps**
1. Register a storefront customer and place a cash order at Mama Ade's Kitchen (tenant A)
2. Optionally leave a review for the completed order
3. POST /api/v1/public/gdpr/dsar {email, requestType:ERASURE}
4. Open the verification link from Mailhog (host rewritten 8080->9090)
5. Wait for the next fan-out sweep (5 min) and read core-java logs
6. Read the order via the vendor API and GET /api/v1/public/shops/mama-ades-kitchen/reviews
**Expected:** Customer PII on orders anonymised, review anonymised, completion confirmed to the subject
**Actual:** Log: event=dsar_fanout_completed tenantsErased=0 tenantsScanned=2. Order still holds customerName, customerEmail, customerPhone and notes ('I am slow to the door'); public review still shows 'Grace Persona14'; customer can still sign in and see both orders; no completion email. Cause (from code): GdprService.eraseSubjectByDigest iterates only `customers` rows, and storefront orders create none (tenant A: 7 customers, none for grace/jordan/sam/priya)
**Real-world impact:** Every storefront customer's Article 17 request is reported satisfied while nothing is erased: an ICO-reportable compliance failure, and the request queue shows 'completed', so nobody notices
**Found by:** P2-GRA-01 (Grace(P2))
**Suspected location (verified in source):** core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java:449-458 (eraseSubjectByDigest iterates customerRepository.findIdAndEmailByTenantId only)
**Regression of closed #84:** #84 closed with 'erasure removes guest-order PII'; the 31-09 DSAR fan-out path scans only `customers` rows, which storefront orders never create. Related: #764, #771 (same GdprService, closed 2026-09-30).
**Evidence:** `.planning/ux-persona-test-20261003-pass2/14-regular-grace/43-my-orders-after-erasure.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/s19-after-erasure.mjs`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/dsar-lodge.txt`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/review-post.json`
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-002] A verified DSAR access request is never fulfilled (ACCESS delivery not implemented)

Title: `A verified DSAR access request is never fulfilled (ACCESS delivery not implemented)` · Labels: ux-persona-test, P0, bug, compliance

**UXT-002 · P0 · major · CONFIRMED** — Data-protection failure: the statutory one-month Article 15 deadline is missed for every requester after they were told it will be actioned.

**Steps**
1. Lodge an ACCESS request for the customer email
2. Open the verification link (status verified)
3. Watch core-java logs over several 5-minute sweeps
**Expected:** A copy of the data is delivered within one month
**Actual:** Every sweep logs 'event=dsar_access_requests_outstanding count=1 — ACCESS delivery is not implemented in plan 31-09'. No email or download is ever produced
**Real-world impact:** Statutory one-month Article 15 deadline missed for every requester; the subject was told 'your request is confirmed and will be actioned'
**Found by:** P2-GRA-02 (Grace(P2))
**Suspected location (cited by tester, not verified):** DsarFanoutWorker logs 'ACCESS delivery is not implemented in plan 31-09' (member cites the log line)
**Evidence:** `.planning/ux-persona-test-20261003-pass2/14-regular-grace/dsar-lodge.txt`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/42-dsar-verify-ACCESS.png`
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-003] Revoking a manager's last shop grant silently makes him tenant-wide Group admin

Title: `Revoking a manager's last shop grant silently makes him tenant-wide Group admin` · Labels: ux-persona-test, P0, bug, security

**UXT-003 · P0 · blocker · CONFIRMED** — Privilege escalation: an owner's revoke produces MORE access (all shops, staff management), while the Staff page shows no row at all.

**Steps**
1. As admin-user-b, grant yourself Group admin (All shops) on /dashboard/staff so the auto row can be removed
2. Revoke tenant-b-user's 'Auto-granted on first sign-in / All shops / Group admin' row; grant tenant-b-user Shop manager on ONE shop
3. Confirm GET /api/v1/staff/me as tenant-b-user -> groupAdmin:false, grantedShopIds:[that shop]
4. Revoke that one shop grant (UI Revoke button)
5. GET /api/v1/staff/me as tenant-b-user; GET /api/v1/orders?shopId=<other shop>; GET /api/v1/staff
**Expected:** Removing the last grant leaves the person with no access to any shop
**Actual:** staff/me returns groupAdmin:true, grantedShopIds:null. Orders for the other shop return 200 and GET /api/v1/staff returns 200 (staff management). On his next write a new 'JIT GROUP_ADMIN' row appears. The Staff page meanwhile lists NO row for him, so the owner believes he has no access. Reproduced twice (API revoke and UI revoke). With his kitchen open, a reload switched him to the other shop's tickets.
**Real-world impact:** An owner who removes a sacked or moved manager gives that person full access to all sites, all orders and staff management. The UI tells her the opposite. Cause: strict-scoping is OFF, so an ungranted tenant user is an implicit tenant-wide Group admin.
**Found by:** P2-KEM-01 (Kemi(P2))
**Suspected location (verified in source):** core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:49-62,104-106,178 (strict-scoping OFF: an ungranted tenant user is an implicit tenant-wide GROUP_ADMIN)
**Related to #285, #499:** #285 (bulk revoke of JIT rows) and #499 (grant upsert) touch the same rows; neither describes the escalation.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/s13-revoke-live.txt`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/37-staff-after-revoke.txt`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/40-revoke-bayo-last-grant-ui.txt`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/40-revoke-bayo-last-grant-ui-after.png` (+2 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

#### [UXT-004] Every staff login is a tenant-wide Group admin by default (JIT provisioning, strict-scoping off)

Title: `Every staff login is a tenant-wide Group admin by default (JIT provisioning, strict-scoping off)` · Labels: ux-persona-test, P0, bug, security

**UXT-004 · P0 · major · CONFIRMED** — Privilege escalation by default: a kitchen login can reprice the menu, add an outbound webhook streaming all orders, grant staff and edit customers.

**Steps**
1. Get tenant-a-user's access token
2. GET /api/v1/staff/me
3. POST /api/v1/products (TUN-TEST-1), PUT pricePennies=1, DELETE
4. POST /api/v1/webhooks {targetUrl:'https://example.com/...',eventTypes:['ORDER_STATE_CHANGED']} then revoke
5. POST /api/v1/staff/grant (self, STAFF) then DELETE
6. POST then DELETE /api/v1/customers; GET /api/v1/customers
**Expected:** A kitchen hand can only read and bump tickets for his shop
**Actual:** staff/me → groupAdmin:true (JIT grant, strict-scoping off by default). Product create 201 / reprice 200 / delete 204; webhook create 201 and a delivery was already attempted (consecutiveFailures:1) before revoke; staff grant 201 / revoke 204; customer create 201 / delete 204; all customers with email+phone readable. By code, DELETE /orders/{id} (SHOP_MANAGER) is also allowed — not executed. Refund, finance, GDPR export, onboarding approvals correctly 403.
**Real-world impact:** Any staff login the owner hands out can reprice the menu, stream all orders to an outside server, add staff, or delete orders — the owner has to understand JIT grants to prevent it.
**Found by:** ADE-13 (Ade(P1)), P2-TUN-05 (Tunde(P2)), P2-KEM-02 (Kemi(P2))
**Suspected location (verified in source):** core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:59-62,104-106,178 (D-04 JIT auto-provision; jtoye.access.strict-scoping defaults false)
**Related to #285:** #285 is UX for cleaning up JIT rows; the default itself is untracked.
**Evidence:** `.planning/ux-persona-test-20261003/01-new-vendor-ade/ (refs: s10, 15)`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/s2-api-probe-read.out`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/s7-api-mutations.out`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/26-grant-bayo-peckham.txt` (+5 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

#### [UXT-005] A buyer of one shop can publish a 5-star review on a different shop of the same tenant

Title: `A buyer of one shop can publish a 5-star review on a different shop of the same tenant` · Labels: ux-persona-test, P0, bug, compliance

**UXT-005 · P0 · major · CONFIRMED** — Cross-shop fake reviews feed public ratings and JSON-LD aggregateRating (CPR/DMCC fake-review exposure).

**Steps**
1. Place a guest order at mama-ades-kitchen
2. Vendor completes it
3. POST /api/v1/public/shops/brixton-village-grill/reviews?email=<buyer email> with {orderId:<Mama Ade's order id>, foodRating:5}
4. Open /shop/brixton-village-grill
**Expected:** 400: order does not belong to this shop
**Actual:** HTTP 201; Brixton shows '5 (1)' and 'Customer reviews (1)', and its JSON-LD aggregateRating is ratingValue 5, reviewCount 1. ReviewService.createReview checks email, COMPLETED and tenant, but never order.shopId == shop.id
**Real-world impact:** A multi-shop owner (or one cheap order) can seed 'verified' ratings on sibling shops. This is a fake-review exposure under DMCC Act 2024 Sch 20 for the platform.
**Found by:** P2-REG-01 (Bola(P2))
**Suspected location (verified in source):** core-java/src/main/java/uk/jtoye/core/review/ReviewService.java:70-95 (checks email, COMPLETED, duplicate; never order.shopId == shop.id)
**Evidence:** `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/review-probe-crossshop.txt`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/reviews-brixton-after.json`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/shots/41-brixton-review-viewport.png`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/shots/40-brixton-with-crossshop-review.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-D Abuse resistance

#### [UXT-006] The public rate limiter trusts any X-Forwarded-For value, so rotating it defeats the limit

Title: `The public rate limiter trusts any X-Forwarded-For value, so rotating it defeats the limit` · Labels: ux-persona-test, P0, bug, security

**UXT-006 · P0 · major · CONFIRMED** — Abuse control bypass: the only throttle on fake guest orders and review spam can be reset per request.

**Steps**
1. GET /public/shops/mama-ades-kitchen/config with no header -> note X-RateLimit-Remaining
2. Repeat with header 'X-Forwarded-For: 203.0.113.7', then '...8'
3. Observe remaining jumps back to 719 (a fresh bucket) for each spoofed value
**Expected:** The IP-keyed public limiter (issue #88) should throttle a single abusive client regardless of client-supplied headers
**Actual:** ClientIpResolver.resolveClientIp() returns the first X-Forwarded-For hop unconditionally, with no trusted-proxy allow-list. A hostile guest sets a new XFF per request and gets a fresh bucket every time, so the only throttle on fake orders is bypassable. (In prod this depends on the ingress overwriting XFF; the application trusts it blind.)
**Real-world impact:** Removes the single brake on KYL-01. With header rotation the 10-order burst becomes an unbounded flood of fake orders against any shop, from one machine.
**Found by:** P2-KYL-02 (Kyle(P2))
**Suspected location (verified in source):** core-java/src/main/java/uk/jtoye/core/security/ClientIpResolver.java:32,47 (first X-Forwarded-For hop, no trusted-proxy allow-list)
**Regression of closed #88:** #88 closed by adding an IP-keyed limiter for /public/**; the IP is taken from the first client-controlled XFF hop, so the fix is bypassable.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/16-prankster-kyle/xff-probe.txt`, `.planning/ux-persona-test-20261003-pass2/16-prankster-kyle/ClientIpResolver.java (lines 44-54)`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-D Abuse resistance

#### [UXT-007] Checkout never re-validates the stored basket: stale prices, removed and sold-out items surface only as a charge or a bare error

Title: `Checkout never re-validates the stored basket: stale prices, removed and sold-out items surface only as a charge or a bare error` · Labels: ux-persona-test, P0, bug

**UXT-007 · P0 · major · CONFIRMED** — Price charged differs from price shown (£11.50 shown, £12.00 charged; £188.50 shown, £198.50 charged) with no notice.

**Steps**
1. Add a product to the basket and open checkout (the basket shows the price at the time it was added)
2. As the vendor, raise that product's price (PUT /api/v1/products/{id})
3. Without reloading, tap 'Place order · £X'
4. Compare the button amount with the confirmation total
**Expected:** Either the checkout refreshes prices and says 'a price changed: £9.00 -> £14.00, please confirm', or the order is refused until she confirms the new total
**Actual:** Run 1 (laptop): the button said 'Place order · £188.50' and the confirmation said £198.50 (+£10, two Suya at £14 instead of £9). Run 2 (phone): the button said £19.00 and the confirmation said £21.00. The cart page, the checkout lines and the button all use the price stored in localStorage when the item was added; the menu already shows the new price. The server always charges the live price (by design: 'never trust client'), but no surface says that the price changed. The confirmation email carries no total either.
**Real-world impact:** On a cash order the driver asks for more than the app promised. That means an argument at the office door, a customer who feels cheated, and possibly a refused delivery of 35 items of food. For an office lunch collected from 8 colleagues in cash, the amount she collected no longer matches.
**Found by:** P2-FUN-03 (Funmi(P2)), P2-CHA-01 (Nkechi(P2)), P2-FUN-09 (Funmi(P2)), P2-CHA-03 (Nkechi(P2)), P2-CHA-04 (Nkechi(P2)), P2-CHA-07 (Nkechi(P2)), F-10 (Sam(P1))
**Suspected location (cited by tester, not verified):** frontend cart/checkout use the price stored in localStorage at add time (member P2-CHA-01 cites cart page, checkout lines and button); server reprices silently
**Evidence:** `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/s4b.log`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/c24-checkout-total-before-place.png`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/c25-price-change-confirmation.png`, `.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/04a-checkout-before-vendor-changes.png` (+25 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-008] An advertised '20% OFF' promotion is displayed on the storefront but never applied to the order

Title: `An advertised '20% OFF' promotion is displayed on the storefront but never applied to the order` · Labels: ux-persona-test, P0, bug, compliance

**UXT-008 · P0 · major · CONFIRMED** — Price charged differs from the price advertised (misleading pricing under CPR 2008 / DMCC 2024).

**Steps**
1. Open /shop/mama-ades-kitchen (banner '20% off selected dishes', chip 'E2E 20% OFF')
2. Add Jollof Rice and go through to checkout
**Expected:** Discounted dishes are identified and the discount is applied, or the banner is not shown
**Actual:** No dish is marked; Jollof costs £8.99 in the basket, at checkout and in the order record (unitPricePennies 899). Order pricing has no promotion logic
**Real-world impact:** A misleading price claim (CPUTR / DMCC Act 2024 Part 4). Customers pay more than advertised.
**Found by:** P2-KYL-07 (Kyle(P2)), P2-REG-05 (Bola(P2))
**Suspected location (verified in source):** core-java/.../storefront/PublicStorefrontService.java:231,246 read promotions for display only; order/OrderService.java has no promotion reference (rg rc=1)
**Evidence:** `.planning/ux-persona-test-20261003-pass2/16-prankster-kyle/01-shop.png`, `.planning/ux-persona-test-20261003-pass2/16-prankster-kyle/k1-net.json`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/shots/02-shop-mama-ades-kitchen.png`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/txt_12-basket.txt` (+2 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-009] The customer's allergen acknowledgement is never sent to or stored by the server

Title: `The customer's allergen acknowledgement is never sent to or stored by the server` · Labels: ux-persona-test, P0, bug, compliance

**UXT-009 · P0 · major · CONFIRMED** — Legal/safety: there is no evidence of what the customer acknowledged, and API/MCP orders bypass the client-only gate.

**Steps**
1. Tick 'I have read the allergen information for this order.' at checkout and place the order
2. Inspect the POST body
3. Open the order detail as the vendor
**Expected:** The order record holds acknowledged=true, a timestamp and the allergen set shown
**Actual:** The POST body has no acknowledgement field; no ack field exists in the order DTO or the backend. The gate is client-only, so orders placed through the API or MCP skip it
**Real-world impact:** After an allergic reaction the vendor cannot prove the customer was warned, which weakens a due-diligence defence.
**Found by:** P2-REG-02 (Bola(P2))
**Suspected location (verified in source):** core-java/src/main/java/uk/jtoye/core/storefront/dto/GuestOrderRequest.java has no acknowledgement field (rg -i acknowledg rc=1; control: 11 fields matched)
**Related to #427:** #427 (allergen evidence chain epic) does not cover the acknowledgement record.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/order-post-log.json`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/order-detail-after.json`
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-010] The customer never sees the allergen set recorded on their order, and it can differ from what they acknowledged

Title: `The customer never sees the allergen set recorded on their order, and it can differ from what they acknowledged` · Labels: ux-persona-test, P0, bug, compliance

**UXT-010 · P0 · major · CONFIRMED** — User safety + legal: the order (and kitchen ticket) records a different allergen set from the one acknowledged, and no confirmation, email or tracking page shows it.

**Steps**
1. Open checkout. The allergen panel lists the basket's declared allergens
2. Tick 'I have read the allergen information for this order'
3. As the vendor, add allergens to a product in the basket (PUT /api/v1/products/{id}, allergenMask)
4. Without reloading, place the order
5. Read GET /api/v1/orders/{id}/detail and the kitchen ticket
**Expected:** The order records the set the customer actually acknowledged, or the server refuses the submit when the declared set has changed since the panel was rendered, and the customer re-acknowledges
**Actual:** Run 1: she acknowledged Gluten, Fish, Peanuts; the snapshot and the kitchen banner say Gluten, Eggs, Fish, Peanuts, Milk. Run 2: she acknowledged Gluten, Eggs, Milk; the snapshot says Gluten, Eggs, Milk, Sesame. The panel is fetched once on mount and never re-checked, and the submit carries no record of what was acknowledged. The system therefore holds a record that implies she accepted allergens she was never shown. No customer surface (confirmation, email, tracking) shows the snapshotted set.
**Real-world impact:** In an office order for 8 people, a colleague with a milk or sesame allergy relies on what Nkechi saw. The kitchen labels correctly, but the customer-side record says she acknowledged the allergen. This is the 'customer acknowledges A, kitchen sees B' gap that V63 was meant to close; it has moved to the window between acknowledging and submitting.
**Found by:** P05-2 (Priya(P1)), P2-REG-04 (Bola(P2)), P2-CHA-02 (Nkechi(P2)), P05-9 (Priya(P1))
**Suspected location (cited by tester, not verified):** checkout allergen panel fetched once on mount and never re-checked (member P2-CHA-02); confirmation/emails/track carry no allergen fields
**Related to #427:** #427 is the allergen evidence-chain epic; it does not cover the customer-facing record.
**Evidence:** `.planning/ux-persona-test-20261003/05-allergy-customer-priya/ (refs: 10, mailhog.json, 11, 12, 14)`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/mail-test-purchase-after.json`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/shots/16-confirmation.png`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/shots/17-customer-track-completed.png` (+8 more in catalogue.json)
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-011] The kitchen screen ticket hides the customer's note (e.g. 'severe peanut allergy') and the fulfilment type

Title: `The kitchen screen ticket hides the customer's note (e.g. 'severe peanut allergy') and the fulfilment type` · Labels: ux-persona-test, P0, bug

**UXT-011 · P0 · major · CONFIRMED** — User safety: a written allergy instruction never reaches the cook on screen (only the printed ticket has it).

**Steps**
1. Place a guest order with notes 'TUN-test: no sesame, ring bell twice'
2. Confirm it
3. Read the ticket on the kitchen board
**Expected:** Notes are prominent on the kitchen card
**Actual:** Card shows allergen banner, name, items only; notes are absent from the screen card (page.tsx renders no order.notes) although GET /api/v1/orders/kitchen returns them and the PRINTED ticket (kitchen-ticket.tsx) includes them.
**Real-world impact:** Customers write allergies and special requests in the notes field; a kitchen working from the screen never sees them — allergic-reaction risk and remakes.
**Found by:** P2-TUN-03 (Tunde(P2)), P2-FUN-02 (Funmi(P2)), P2-TUN-11 (Tunde(P2))
**Suspected location (verified in source):** frontend/app/dashboard/kitchen/page.tsx renders no order.notes (rg -uu 'notes' rc=1 on the file and the directory; control 'Kitchen' = 12 hits); kitchen-ticket.tsx (print) does
**Evidence:** `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/06-kitchen-1024-tickets.png`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/s4-confirm-and-board.mjs`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/k08-o2-card-confirmed.png`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/k04-o1-card-confirmed.png` (+3 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

#### [UXT-012] A product whose ingredients name an allergen (e.g. 'butter (MILK)') with no box ticked saves and shows as 'No allergens'

Title: `A product whose ingredients name an allergen (e.g. 'butter (MILK)') with no box ticked saves and shows as 'No allergens'` · Labels: ux-persona-test, P0, bug, compliance

**UXT-012 · P0 · major · CONFIRMED** — User safety: the platform publishes 'No allergens' for a dish whose own ingredient text names MILK, with no warning to the vendor.

**Steps**
1. Sign in as a vendor, Products → Add product
2. Ingredients: 'rice, butter (MILK), pepper'; tick no allergen
3. Save, then open the dish on the storefront
**Expected:** Save warns that MILK is named in the ingredients but not declared; the storefront never says 'No allergens' for it
**Actual:** Saves silently; product shows 'No allergens'
**Real-world impact:** A milk-allergic customer is told a dish containing butter is allergen-free.
**Found by:** ADE-09 (Ade(P1))
**Suspected location (cited by tester, not verified):** 31-04 OrderAllergenAggregator reconciles at ORDER time only; nothing reconciles at product save (not verified in code)
**Related to #427:** #427 (ingredient/allergen evidence chain) is the long-term home; the save-time warning is not tracked.
**Evidence:** `.planning/ux-persona-test-20261003/01-new-vendor-ade/ (refs: s18, s27)`
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-013] Shops go live with a failed FSA match, self-approved by the vendor, while the site claims 'UK food-hygiene verified'

Title: `Shops go live with a failed FSA match, self-approved by the vendor, while the site claims 'UK food-hygiene verified'` · Labels: ux-persona-test, P0, compliance

**UXT-013 · P0 · major · CONFIRMED** — Legal breach (misleading claim to consumers) and safety: 'verified' is shown for shops whose hygiene check failed, and no FHRS rating is displayed.

**Steps**
1. As admin-user open /dashboard/onboarding
2. Compare with the published /shop list
**Expected:** Not published until the check passes, as the page itself states
**Actual:** 'No FSA establishment matched the shop name/address — Manual review', yet the shop is live. No FHRS rating or registration is shown publicly
**Real-world impact:** The landing trust strip promises 'UK food-hygiene verified' while shops whose FSA match failed are live, approved only by their own admin; the platform may be listing an unregistered food business and shows no FHRS rating.
**Found by:** ADE-08 (Ade(P1)), C-07 (Claire(P1)), P2-REG-09 (Bola(P2)), ADE-14 (Ade(P1))
**Related to #453:** #453 records that MANUAL_REVIEW has no adjudicator; this cluster is the public claim and the live-while-unverified state.
**Evidence:** `.planning/ux-persona-test-20261003/01-new-vendor-ade/ (refs: 14c, 27)`, `.planning/ux-persona-test-20261003/08-sceptical-evaluator-claire/ (refs: dtxt_dashboard_onboarding*.txt)`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/txt_23-vendor-onboarding.txt`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/shots/23-vendor-onboarding.png` (+1 more in catalogue.json)
**Proposed home:** Phase 33 – The Consumer Product (CUST-02/CUST-04 still open)

#### [UXT-014] Customers are never given the seller's legal identity or any way to contact the shop

Title: `Customers are never given the seller's legal identity or any way to contact the shop` · Labels: ux-persona-test, P0, compliance

**UXT-014 · P0 · major · CONFIRMED** — Legal breach (E-Commerce Regs 2002 reg 6 / CCR 2013 trader identity, address, cancellation info) and safety: every 'ask the kitchen' message is a dead end because all shops publish phone:null, email:null.

**Steps**
1. Read a shop page and checkout
2. GET /api/v1/public/shops (phone and email are null for all 3 shops)
3. Read the order emails
**Expected:** Trader identity, geographic address, phone or email, and a no-cancellation statement before purchase and on a durable-medium confirmation (CCR 2013 Sch 2 and reg 16)
**Actual:** Trading name and premises only. No legal entity, company number or VAT-number fields exist. Emails come from noreply@jtoye.uk, are signed '— J'Toye', name no shop and say 'we'll deliver it'. Nothing is said about cancellation
**Real-world impact:** After a reaction the consumer can't reach the vendor, and believes J'Toye sold the meal. Advice, then an enforcement order, for both vendor and platform.
**Found by:** P2-REG-06 (Bola(P2)), P05-1 (Priya(P1))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/txt_02-shop-mama-ades-kitchen.txt`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/txt_15-checkout-acked.txt`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/mail-test-purchase-after.json`, `.planning/ux-persona-test-20261003/05-allergy-customer-priya/ (refs: public shops API; 02)`
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-015] Stripe JS and fraud cookies load on a cash-only checkout, contradicting the cookie policy

Title: `Stripe JS and fraud cookies load on a cash-only checkout, contradicting the cookie policy` · Labels: ux-persona-test, P0, bug, compliance

**UXT-015 · P0 · major · CONFIRMED** — Legal breach (PECR): non-essential third-party cookies (__stripe_mid, 1 year) are set on a page that takes no payment.

**Steps**
1. Fresh browser, open any shop, add an item, go to /checkout
2. Inspect network and cookies
**Expected:** No third-party requests or cookies on a cash-only checkout
**Actual:** js.stripe.com and m.stripe.network load, m.stripe.com/6 is POSTed, __stripe_mid (1 year), __stripe_sid and m cookies are set; the page says 'No payment is taken online'
**Real-world impact:** Every cash customer is tracked by Stripe without consent, contrary to the published cookie policy.
**Found by:** F-01 (Sam(P1)), P2-REG-13 (Bola(P2))
**Suspected location (verified in source):** frontend/app/shop/[slug]/checkout/page.tsx:7 imports from '@stripe/stripe-js' (default entry injects js.stripe.com on import; the KEY guard at :107-108 cannot stop it)
**Evidence:** `.planning/ux-persona-test-20261003/06-impatient-privacy-sam/ (refs: s12, s13, 24)`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/state-guest.json`
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-016] Updating a product without quantityInStock silently turns stock tracking off

Title: `Updating a product without quantityInStock silently turns stock tracking off` · Labels: ux-persona-test, P0, bug

**UXT-016 · P0 · major · CONFIRMED** — Silent data corruption: stock becomes unlimited on a routine PUT, so sold-out items can be oversold.

**Steps**
1. Create a product with quantityInStock=40
2. PUT the product with the required fields + available=false, omitting quantityInStock
3. GET the product
**Expected:** Omitted fields keep their values (as every other optional field does), or a PATCH endpoint exists
**Actual:** quantityInStock 40 → null (null = unlimited/untracked) while description, category, shopId, imageUrl, featured and displayOrder are all preserved. PUT {available:false} alone → 400, because SKU, title, ingredients, allergen mask and price are required. PATCH → 405. The spec does not document the special case.
**Real-world impact:** A nightly EPOS or bot job that only flips availability wipes stock counts. When the item comes back it is unlimited and oversells. Every availability flip also has to resend the Natasha's-Law allergen fields, so a stale EPOS copy can overwrite the vendor's allergen data.
**Found by:** P2-RAV-03 (Ravi(P2))
**Suspected location (cited by tester, not verified):** PUT /api/v1/products/{id} maps a missing quantityInStock to null (=untracked); no PATCH (member cites)
**Evidence:** `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/prod-put.json`, `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/prod-get-after-put.json`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

#### [UXT-018] A completed, paid order can be deleted, leaving ledger rows that point at nothing

Title: `A completed, paid order can be deleted, leaving ledger rows that point at nothing` · Labels: ux-persona-test, P0, bug

**UXT-018 · P0 · major · CONFIRMED** — Silent data corruption of financial records: the ledger lists sales whose orders return 404.

**Steps**
1. Complete an order (it creates a ledger row 'Order ORD-…')
2. DELETE /api/v1/orders/{id} as admin-user-b -> 204
3. GET /api/v1/orders/{id} -> 404; GET /api/v1/financial-transactions still lists the sale
**Expected:** A completed order cannot be deleted; at most it is refunded or voided with an audit trail
**Actual:** 204 on 3 COMPLETED orders. The ledger still holds £48.00 against order numbers that return 404. The code requires only SHOP_MANAGER on the order's shop, so a site manager could also do this (manager role not exercised: SUSPECTED for that part).
**Real-world impact:** Takings and the books stop matching, and a manager could take cash, complete the order and delete it. This is an audit and theft risk for a cash-only business.
**Found by:** P2-KEM-07 (Kemi(P2))
**Suspected location (verified in source):** core-java/src/main/java/uk/jtoye/core/order/OrderService.java:599-608 (deleteOrder checks only SHOP_MANAGER; no status guard)
**Evidence:** `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/51-cleanup-2.txt`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/52-ledger-after-order-delete.txt`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

#### [UXT-019] The platform's own registered office is not published anywhere on the site

Title: `The platform's own registered office is not published anywhere on the site` · Labels: ux-persona-test, P0, compliance

**UXT-019 · P0 · minor · CONFIRMED** — Legal breach: the Companies (Trading Disclosures) Regulations require the registered office on the website; /legal/accessibility even says it is not published.

**Steps**
1. Open /legal, /legal/privacy and /legal/accessibility
**Expected:** Company name, number, registered office (and ICO registration reference) shown
**Actual:** Name and number only; 'Registered office address not published'; no ICO number
**Real-world impact:** A regulator or B2B buyer finds a statutory disclosure missing; procurement questionnaires fail.
**Found by:** C-11 (Claire(P1))
**Suspected location (verified in source):** frontend/Dockerfile:81 declares ARG NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE (31-08); it is set in no runtime (possibly config-only)
**Evidence:** `.planning/ux-persona-test-20261003/08-sceptical-evaluator-claire/ (refs: txt_legal*.txt)`
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-020] A new order makes no sound and never reaches the kitchen screen until someone confirms it on another page

Title: `A new order makes no sound and never reaches the kitchen screen until someone confirms it on another page` · Labels: ux-persona-test, P1, bug

**UXT-020 · P1 · blocker · CONFIRMED** — Blocks the core vendor journey: orders sit unseen as PENDING while the board says 'No active orders'.

**Steps**
1. Log in as admin-user; open /dashboard/kitchen (Brixton Village Grill) on a tablet and tap the page once (audio gesture); open /dashboard/orders on a phone
2. Place a cash order at /shop/brixton-village-grill as a guest
3. Watch both screens for 40 s without reloading; instrument AudioContext/Oscillator, Notification, navigator.vibrate and document.title
**Expected:** A new order announces itself loudly (repeating until acknowledged) on the kitchen screen and the counter phone, and appears on the kitchen board as NEW
**Actual:** 7/7 orders: phone list showed the row in 1.1-5.1 s but with 0 beeps/notifications/vibrations and no title change; the kitchen board never showed any of them (it lists only CONFIRMED/PREPARING/READY). The only kitchen beep fires when an order is CONFIRMED (kitchen/page.tsx:496-499), i.e. in reaction to the vendor's own tap - 9 beeps during the test were other users' confirmations
**Real-world impact:** On a busy night an order nobody happens to see sits in Pending indefinitely; the customer was told 'the shop will confirm it shortly' and waits, then phones or goes to Just Eat. Worse than the WhatsApp buzz it replaces
**Found by:** P2-FUN-01 (Funmi(P2)), P2-TUN-01 (Tunde(P2))
**Suspected location (cited by tester, not verified):** frontend/app/dashboard/kitchen/page.tsx lists only CONFIRMED/PREPARING/READY; the only beep fires on CONFIRMED (:496-499, member cites)
**Evidence:** `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/s2c.log`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/s2.log`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/k03-tablet-after-7-orders.png`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/v04-phone-orders-after-7.png` (+3 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

#### [UXT-021] When the all-day kitchen tablet's session lapses the board silently becomes a sign-in page

Title: `When the all-day kitchen tablet's session lapses the board silently becomes a sign-in page` · Labels: ux-persona-test, P1, bug

**UXT-021 · P1 · major · CONFIRMED** — Blocks the core vendor journey: every order after the 5-minute token expiry is never seen.

**Steps**
1. Sign in on a tablet, open Kitchen
2. End that browser's own Keycloak session (OIDC end-session with its id_token_hint — what ssoSessionMaxLifespan does)
3. Keep placing+confirming orders every ~100 s and watch
**Expected:** A loud, unmissable 'signed out — board stopped' state (sound, red screen) and a return to Kitchen after re-login; ideally a session long enough for a shift
**Actual:** Board worked until the 5-min access token expired, then the 60-s poll got 401 and the page navigated to the generic 'Vendor sign in' page with no sound or explanation; order ORD-…7E355E89 placed afterwards was never seen (4+ min on sign-in page). Re-login lands on /dashboard, not Kitchen. Realm template sets ssoSessionMaxLifespan=7200 (2 h) — live value not read (SUSPECTED). It does NOT freeze while showing 'Live' (good).
**Real-world impact:** Mid-service the kitchen screen goes quiet; nobody in a loud kitchen notices a login page; orders pile up until a customer complains.
**Found by:** P2-TUN-06 (Tunde(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/s6-session-lapse.out`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/09-lapse-t0.png`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/09-lapse-final.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

#### [UXT-022] A vendor cannot pause or stop taking orders: no pause switch, no holiday closure, and free-text hours fail open and cannot be cleared

Title: `A vendor cannot pause or stop taking orders: no pause switch, no holiday closure, and free-text hours fail open and cannot be cleared` · Labels: ux-persona-test, P1, enhancement

**UXT-022 · P1 · major · CONFIRMED** — Blocks the core vendor journey: an overwhelmed or closed kitchen keeps receiving orders.

**Steps**
1. Open /dashboard/shops; look for pause/busy/accepting-orders control
2. Open Edit Shop for Brixton Village Grill
3. Read ShopService.updateShop slug handling
**Expected:** A one-tap 'Pause new orders for 20/40 min' that the storefront and checkout respect, with a customer-facing message
**Actual:** No switch anywhere; Edit Shop has only free-text opening hours per day. Saving Edit Shop regenerates the slug (ShopService.java:269 generateSlug when request slug blank; the form never sends slug). Not saved on the shared shop to protect other testers
**Real-world impact:** Funmi cannot stop the flood without breaking every shared link/QR code; she will ignore orders instead, which is worse for customers
**Found by:** P2-FUN-06 (Funmi(P2)), P2-CHA-10 (Nkechi(P2)), P2-KEM-11 (Kemi(P2))
**Suspected location (cited by tester, not verified):** PublicStorefrontService.validateShopIsOpen treats unparseable/empty hours as open (member P2-KEM-11, SUSPECTED); update mapper ignores null hours (P2-CHA-10)
**Evidence:** `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/v15-shop-edit-top.png`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/v16-shop-edit-hours.png`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/s5.log`, `.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/s05-chaos-shop.mjs` (+3 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

#### [UXT-023] Vendors cannot set a delivery fee, free-delivery threshold or collection-only: orders are charged £0 delivery and collection-only shops take deliveries

Title: `Vendors cannot set a delivery fee, free-delivery threshold or collection-only: orders are charged £0 delivery and collection-only shops take deliveries` · Labels: ux-persona-test, P1, enhancement

**UXT-023 · P1 · major · CONFIRMED** — Blocks the core vendor journey: every new-site delivery loses its fee and collection-only kitchens receive orders they cannot fulfil.

**Steps**
1. Add a shop: fields are name, address, description, publish, tags, phone, email, Delivery Info (free text), Minimum Order, 7 hour boxes
2. Peckham Delivery Info = 'Delivery within 2 miles, £2.99, free over £25'; Lewisham = 'COLLECTION ONLY'
3. Create a DELIVERY order at each shop
**Expected:** Delivery is charged at the shop's fee; a collection-only shop refuses delivery
**Actual:** Both delivery orders are accepted with deliveryFeePennies 0. ShopDto and CreateShopRequest have no deliveryFeePennies/freeDeliveryThreshold field, although the storefront charges from the entity (default 0).
**Real-world impact:** Every delivery from a new site loses £2.99, and a collection-only kitchen receives delivery orders it cannot fulfil.
**Found by:** P2-KEM-10 (Kemi(P2)), P2-CHA-08 (Nkechi(P2)), ADE-16 (Ade(P1))
**Suspected location (cited by tester, not verified):** ShopDto / CreateShopRequest have no deliveryFeePennies or freeDeliveryThreshold (member P2-KEM-10)
**Evidence:** `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/02-addshop-kemi-p2-peckham.png`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/04-shops-api.txt`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/21-orders-create.txt`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/s01-create-shops.txt` (+3 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-G Catalogue & shop-admin correctness

#### [UXT-026] CSV import ignores the selected shop and hides imported items from every storefront; a menu cannot be copied to another site

Title: `CSV import ignores the selected shop and hides imported items from every storefront; a menu cannot be copied to another site` · Labels: ux-persona-test, P1, bug

**UXT-026 · P1 · major · CONFIRMED** — Blocks multi-site setup: imported items appear on no storefront, silently.

**Steps**
1. Products: row actions are only label / edit / delete (no duplicate, no copy-to-shop, no menu export)
2. Set the header switcher to kemi-p2-lewisham, open Bulk Import, upload a 2-row CSV copied from the Peckham menu (shop_id blank, as the page lists no shop_id column)
3. View Products with switcher = kemi-p2-lewisham
**Expected:** Either a 'copy menu to…' action, or the import lands in the selected shop
**Actual:** Import immediately creates both rows with shopId null ('All Shops'). With Lewisham selected the list says '1 product in kemi-p2-lewisham': the copies are not there. Per the storefront code (getShopProducts filters shop_id = this shop), 'All Shops' items show on NO storefront. The CSV template has a shop_id column needing a UUID that the UI never displays, and the import page does not mention it.
**Real-world impact:** Kemi retypes every dish and allergen set for each site, which is slow and error-prone for allergens. Or she imports and her menu silently vanishes.
**Found by:** P2-KEM-09 (Kemi(P2)), C-10 (Claire(P1))
**Suspected location (cited by tester, not verified):** BulkImport creates shopId null; storefront getShopProducts filters shop_id = this shop (member P2-KEM-09)
**Evidence:** `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/s04-import.txt`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/11-clone-peckham-to-lewisham.csv`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/s05-import-clone.txt`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/13-import-result.png` (+4 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

#### [UXT-027] Per-shop dashboard and finance show the whole business's takings, and site managers get 'No financial data yet' instead of their shop's numbers

Title: `Per-shop dashboard and finance show the whole business's takings, and site managers get 'No financial data yet' instead of their shop's numbers` · Labels: ux-persona-test, P1, bug

**UXT-027 · P1 · major · CONFIRMED** — Blocks the multi-site owner's core journey: per-site takings are wrong while the banner says they are scoped.

**Steps**
1. Complete 2 Peckham orders (£22.50 + £9.50) and 1 Lewisham order (£16.00)
2. Set the shop switcher to kemi-p2-lewisham and open /dashboard and /dashboard/finance
3. GET /api/v1/financial-transactions/summary?shopId=<peckham>&from=2026-10-01
**Expected:** Lewisham shows £16.00 revenue / £2.66 VAT; Peckham shows £32.00 / £5.33
**Actual:** Dashboard banner: 'Viewing kemi-p2-lewisham — order activity below is scoped to this shop', but 'Revenue by VAT Category: £48.00 revenue, £7.99 VAT' (the whole tenant). /finance shows £48.00 and all 3 transactions for every switcher value. The API ignores shopId and from. Ledger rows have no shop.
**Real-world impact:** Kemi reads one site as having taken three sites' money. Per-site profit, staffing and VAT decisions are made on wrong numbers.
**Found by:** P2-KEM-05 (Kemi(P2)), P2-KEM-08 (Kemi(P2))
**Suspected location (cited by tester, not verified):** GET /api/v1/financial-transactions/summary ignores shopId and from; ledger rows carry no shop (member P2-KEM-05)
**Evidence:** `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/24-home-kemi_p2_lewisham.png`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/24-finance-kemi_p2_lewisham.png`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/24-finance-kemi_p2_peckham.png`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/s09-finance-tour.txt` (+5 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

#### [UXT-028] Every shop update regenerates the public URL slug, so shared links and QR codes break

Title: `Every shop update regenerates the public URL slug, so shared links and QR codes break` · Labels: ux-persona-test, P1, bug

**UXT-028 · P1 · major · CONFIRMED** — Blocks the customer's route to the shop: any edit (even opening hours) changes the public link.

**Steps**
1. Dashboard → Shops → Edit an existing shop
2. Change only the opening hours or description, Save
3. Compare the slug before and after
**Expected:** Slug unchanged unless the vendor edits it
**Actual:** New slug each save (a823cee3 → 7cd132a5 → 85a6253f); old link 404 (SUSPECTED once published)
**Real-world impact:** Printed QR codes, Instagram links and search results go dead after any edit.
**Found by:** ADE-03 (Ade(P1))
**Suspected location (verified in source):** core-java/src/main/java/uk/jtoye/core/shop/ShopService.java:268-270 (regenerates slug whenever request slug is blank; the form never sends slug)
**Evidence:** `.planning/ux-persona-test-20261003/01-new-vendor-ade/ (refs: s12, s14, s16)`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-G Catalogue & shop-admin correctness

#### [UXT-029] The order confirmation is rendered in place at /checkout: off-screen, unannounced, and lost on refresh

Title: `The order confirmation is rendered in place at /checkout: off-screen, unannounced, and lost on refresh` · Labels: ux-persona-test, P1, bug

**UXT-029 · P1 · major · CONFIRMED** — Blocks the core customer journey: the customer cannot tell the order succeeded and may re-order.

**Steps**
1. Add 2 items to a basket, open /shop/brixton-village-grill/checkout
2. Fill all fields with the keyboard, tick the allergen checkbox with Space
3. Focus "Place order · £25.00" and press Enter
4. Record live-region output, document.activeElement and document.title
**Expected:** Focus moves to the "Order confirmed!" heading, or a status message says "Order confirmed, order number ORD-…"; the title changes to the confirmation
**Actual:** Focus drops to <body>. The only live-region output is "0 items in basket" (the header basket link, aria-live=polite). The URL stays /checkout and the title stays "J'Toye — Discover Local Vendors". Reproduced twice: desktop delivery (ORD-…50F52C87) and iPhone 13 collection (ORD-…F82D641A).
**Real-world impact:** A blind customer cannot tell whether the order went through or the basket was simply cleared, so they phone the shop or re-order, and a duplicate cash order reaches the kitchen.
**Found by:** F-03 (Sam(P1)), P2-GRA-04 (Grace(P2)), P2-MAR-01 (Marcus(P2))
**Related to #409:** #409 fixed the confirmation never appearing; it still has no route of its own.
**Evidence:** `.planning/ux-persona-test-20261003/06-impatient-privacy-sam/ (refs: 17, 18)`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/13-confirmation.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/20-week2-confirmation-as-seen.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/s08-week2-reorder.mjs` (+5 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-031] A prospective vendor has no way in: 'Start your application' dead-ends at a login, and no sales or support contact exists

Title: `A prospective vendor has no way in: 'Start your application' dead-ends at a login, and no sales or support contact exists` · Labels: ux-persona-test, P1, enhancement

**UXT-031 · P1 · blocker · CONFIRMED** — Blocks vendor acquisition: both prospects gave up at this step.

**Steps**
1. Open / or /for-operators on a phone
2. Tap 'Start your application'
3. Look for a phone, sales email or demo booking anywhere
**Expected:** An application form or a contact route that reaches a person
**Actual:** Keycloak 'Sign in to your account' with no register or back link; the only email on the site is privacy@ on a non-brand domain
**Real-world impact:** Vendors who want to sign up cannot; evaluators walk away citing 'nobody to ring at 8pm Saturday'.
**Found by:** ADE-01 (Ade(P1)), C-05 (Claire(P1))
**Related to #102:** #102 covers the tenant lifecycle backend; the public application/contact route is untracked.
**Evidence:** `.planning/ux-persona-test-20261003/01-new-vendor-ade/ (refs: 07–10; s3, s4)`, `.planning/ux-persona-test-20261003/08-sceptical-evaluator-claire/ (refs: txt_*.txt)`
**Proposed home:** Phase 32 – Production Cutover + First Tenant

#### [UXT-032] No merchant terms, pricing page, VAT basis or card-fee figure: /legal/terms, /pricing, /contact and /about all 404

Title: `No merchant terms, pricing page, VAT basis or card-fee figure: /legal/terms, /pricing, /contact and /about all 404` · Labels: ux-persona-test, P1, compliance

**UXT-032 · P1 · major · CONFIRMED** — Blocks vendor signing: there is no contract to sign or price basis to agree to.

**Steps**
1. Request /legal/terms, /pricing, /contact, /about
2. Read the terms card on /for-operators
**Expected:** Merchant terms, a pricing page with VAT basis, term and card fees
**Actual:** All four return 404; prices labelled 'PRICING TEST', no VAT basis, 'card fees shown transparently' with no figure
**Real-world impact:** A buyer cannot sign; procurement stops.
**Found by:** C-04 (Claire(P1)), C-09 (Claire(P1))
**Evidence:** `.planning/ux-persona-test-20261003/08-sceptical-evaluator-claire/ (refs: shots/pub_legal_terms.png)`, `.planning/ux-persona-test-20261003/08-sceptical-evaluator-claire/ (refs: txt_for-operators.txt)`
**Proposed home:** Phase 32 – Production Cutover + First Tenant

#### [UXT-035] A vendor has no way to give a developer API credentials; the only path is the owner's password plus the confidential core-api client secret

Title: `A vendor has no way to give a developer API credentials; the only path is the owner's password plus the confidential core-api client secret` · Labels: ux-persona-test, P1, enhancement

**UXT-035 · P1 · blocker · CONFIRMED** — Blocks the integrator journey and forces credential sharing of a full admin token.

**Steps**
1. Log in as admin-user and inspect the dashboard nav
2. Probe /dashboard/developers, /dashboard/api, /dashboard/settings, /dashboard/integrations, /developers, /docs
3. Read docs/security-scopes.md and decode tokens of integration-catalog-ro / integration-orders-rw
**Expected:** A Developers/API page where the owner creates a scoped, revocable client for their own tenant
**Actual:** No developer surface (all 6 probes 404). The only machine clients come from a realm import, hard-wired to tenant A's UUID, and the vendor can't see or rotate them. The integrator must use the owner's admin password through a password grant, which also needs the confidential core-api client secret. The result is a full admin token (catalog:write, orders:write, customers:write) with a 300 s lifetime.
**Real-world impact:** Vendors hand their admin password to freelancers or don't integrate at all. Nothing can be revoked per developer, and least privilege can't be achieved without a platform operator, which this platform does not have.
**Found by:** P2-RAV-01 (Ravi(P2))
**Related to #206:** #206 delivered scoped client scopes; there is still no per-tenant issuance or rotation surface.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/w01-dashboard-nav.png`, `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/webhooks-ui-log.txt`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

#### [UXT-036] An order taken by an AI agent through MCP stays DRAFT and the kitchen never sees it

Title: `An order taken by an AI agent through MCP stays DRAFT and the kitchen never sees it` · Labels: ux-persona-test, P1, bug

**UXT-036 · P1 · major · CONFIRMED** — Blocks the agent ordering journey: the customer is told the order exists, the kitchen never cooks it.

**Steps**
1. MCP tools/call create_order with a valid shop/product/idempotencyKey
2. Observe status
3. Check the MCP tool list for a submit/confirm tool and the kitchen endpoint contract
**Expected:** The agent's order reaches the kitchen (PENDING/CONFIRMED), or the tool clearly says a human must submit it
**Actual:** status DRAFT. No MCP tool can submit or confirm it. The kitchen endpoint returns only CONFIRMED/PREPARING/READY. A DRAFT emits no webhook event. The tool description does not mention DRAFT.
**Real-world impact:** ChatGPT/Claude tells the customer 'order placed', nothing is cooked, and the customer arrives to collect food that doesn't exist.
**Found by:** P2-RAV-04 (Ravi(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/mcp-order1.json`, `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/api-docs.json`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

#### [UXT-037] /sync/batch has no stock or availability field and silently skips unknown items while reporting SUCCESS

Title: `/sync/batch has no stock or availability field and silently skips unknown items while reporting SUCCESS` · Labels: ux-persona-test, P1, enhancement

**UXT-037 · P1 · major · CONFIRMED** — Blocks EPOS integration; dropped items vanish without trace.

**Steps**
1. POST 3 product items + 1 item {type:'stock',sku,quantity:0}
**Expected:** Stock and availability can be synced. Rejected or unknown items are reported per item (or the batch fails typed).
**Actual:** 202 {status:SUCCESS, processed_count:3}. The 4th item was dropped without a trace. SyncItem fields are type, sku, title, ingredientsText, allergenMask, pricePennies, name and address only.
**Real-world impact:** A nightly EPOS job looks green while doing nothing for stock, and the vendor finds out from customers.
**Found by:** P2-RAV-06 (Ravi(P2))
**Related to #727:** Same endpoint as #727; the silent drop is a separate defect.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/sync-body.json`, `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/sync-resp1.json`, `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/edge-openapi.json`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

#### [UXT-039] Anonymous traffic can exhaust the edge gateway's single process-wide rate limit and block every vendor's sync; its 429 is untyped

Title: `Anonymous traffic can exhaust the edge gateway's single process-wide rate limit and block every vendor's sync; its 429 is untyped` · Labels: ux-persona-test, P1, bug

**UXT-039 · P1 · major · CONFIRMED** — Blocks every vendor's integrations at once (cross-tenant denial of service).

**Steps**
1. Send 70 parallel unauthenticated POSTs to :8089/api/v1/sync/batch
2. Inspect a 429 response
**Expected:** Per-tenant limiting after auth; a typed RFC 7807 429 with Retry-After and rate headers (as Core does)
**Actual:** 41×401, 29×429. The body is {"error":"rate limit exceeded"} with no Retry-After and no X-RateLimit headers. It is one process-wide bucket (20 rps, burst 40) applied before auth, and the edge spec does not list 429.
**Real-world impact:** Anyone flooding the edge without a token makes every vendor's nightly sync fail, and clients can't back off properly.
**Found by:** P2-RAV-12 (Ravi(P2))
**Related to #413:** #413 typed Core's 429; the edge 429 is still hand-rolled and the bucket is shared before auth.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/edge-burst.txt`, `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/edge-429-sample.txt`, `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/edge-openapi.json`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-D Abuse resistance

#### [UXT-040] Asked for one shop's menu, an AI agent gets every product in the business with allergens only as an integer

Title: `Asked for one shop's menu, an AI agent gets every product in the business with allergens only as an integer` · Labels: ux-persona-test, P1, bug

**UXT-040 · P1 · major · CONFIRMED** — Blocks correct agent ordering: unavailable, other-shop and orphan items are offered, and allergens are undecoded.

**Steps**
1. tools/call list_products {shopId:<Mama Ade's>}
2. tools/call list_products {size:100}
**Expected:** A shopId/search filter (Core supports shopId), only orderable items, and allergen names
**Actual:** shopId is silently ignored. The call returns all 30 tenant products, including the unpublished shop, other personas' test items, unavailable items and shop-less orphans. Allergens come back as an integer bitmask with no decoding.
**Real-world impact:** The agent lists dishes the shop doesn't sell and can't safely answer 'does it contain nuts?'.
**Found by:** P2-RAV-14 (Ravi(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/mcp-list-products-100.json`, `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/mcp-tools-list.txt`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

#### [UXT-041] Anyone can place many fake cash orders in seconds with throwaway contact details

Title: `Anyone can place many fake cash orders in seconds with throwaway contact details` · Labels: ux-persona-test, P1, enhancement

**UXT-041 · P1 · major · CONFIRMED** — Blocks normal trading: a flood of fake orders lands on the kitchen looking real.

**Steps**
1. Script a loop of 10 POSTs to /public/shops/mama-ades-kitchen/orders
2. Each with a random throwaway email and a junk phone ('call me', 'no', 'x', '0', '1')
3. fulfilmentType COLLECTION, one real product id, no auth, no browser
**Expected:** Something slows a burst of guest orders down: a per-email / per-phone / per-device cap, email verification before the kitchen sees it, a captcha, or a hold queue
**Actual:** All 10 returned 201 PENDING in 311 ms total. No verification, no captcha, no per-identity cap. They appear in the vendor's Orders list indistinguishable from real orders.
**Real-world impact:** A small vendor prepping to a cash-on-collection screen cooks food for orders nobody collects. One teenager with a for-loop can bury a real Friday rush under junk and burn an evening of ingredients and prep time. The only cost to the attacker is typing.
**Found by:** P2-KYL-01 (Kyle(P2))
**Related to #461:** #461 (payment links before production) removes the zero-cost order; until then nothing bounds it.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/16-prankster-kyle/k2-bulk.mjs`, `.planning/ux-persona-test-20261003-pass2/16-prankster-kyle/k2-bulk.json`, `.planning/ux-persona-test-20261003-pass2/16-prankster-kyle/05-dashboard-orders.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-D Abuse resistance

#### [UXT-042] Item quantity has no upper bound: a £19 billion order is accepted and shown on the dashboard

Title: `Item quantity has no upper bound: a £19 billion order is accepted and shown on the dashboard` · Labels: ux-persona-test, P1, bug

**UXT-042 · P1 · major · CONFIRMED** — Absurd orders reach the kitchen and pollute revenue figures.

**Steps**
1. POST a guest order with items:[{productId: <jollof>, quantity: 999}] -> 201, total £8,981.01
2. POST again with quantity: 2147483647 -> 201, total £19,305,877,986.53
3. Both appear as PENDING in the vendor Orders list
**Expected:** A sane per-line quantity ceiling (and a basket-total sanity cap) rejecting obvious garbage
**Actual:** Quantity is only validated as >= 1 (negatives, 0 and empty baskets are correctly rejected 400). There is no maximum, so absurd quantities create real orders and real line totals.
**Real-world impact:** Pollutes the kitchen queue and the finance ledger with impossible numbers; a vendor glancing at 'Total' sees £8,981 or £19bn against their shop. Confuses takings, breaks any revenue chart, and wastes time working out it is a prank.
**Found by:** P2-KYL-03 (Kyle(P2))
**Suspected location (verified in source):** core-java/src/main/java/uk/jtoye/core/storefront/dto/GuestOrderItemRequest.java:13 (@Min(1) only, no @Max)
**Evidence:** `.planning/ux-persona-test-20261003-pass2/16-prankster-kyle/k3-absurd.mjs`, `.planning/ux-persona-test-20261003-pass2/16-prankster-kyle/k3-absurd.json`, `.planning/ux-persona-test-20261003-pass2/16-prankster-kyle/05-dashboard-orders.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-D Abuse resistance

#### [UXT-043] An allergy request is a generic free-text note: no alert to the vendor, no acknowledgement, never echoed to the customer

Title: `An allergy request is a generic free-text note: no alert to the vendor, no acknowledgement, never echoed to the customer` · Labels: ux-persona-test, P1, enhancement, compliance

**UXT-043 · P1 · minor · CONFIRMED** — Blocks the allergic customer's journey: a request to confirm goes to COMPLETED with no flag or reply.

**Steps**
1. Order with notes 'My child is allergic to peanuts and sesame - please confirm.'
2. Vendor confirms and completes it
**Expected:** An allergy note is flagged, needs a vendor response, and the response is recorded
**Actual:** Shown as plain NOTES; nothing records that anyone read it
**Real-world impact:** An allergy request can be missed with no trace.
**Found by:** P05-8 (Priya(P1)), P2-REG-10 (Bola(P2))
**Evidence:** `.planning/ux-persona-test-20261003/05-allergy-customer-priya/ (refs: 14)`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/order-detail-after.json`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/shots/21-vendor-order-detail.png`
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-044] Reviews publish the reviewer's full checkout name with no notice, policy or moderation

Title: `Reviews publish the reviewer's full checkout name with no notice, policy or moderation` · Labels: ux-persona-test, P1, bug, compliance

**UXT-044 · P1 · major · CONFIRMED** — Data-protection risk: full names are published (and in the public API) without transparency; reachable today via the API.

**Steps**
1. Create a review for a completed order
2. Open the shop page signed out
**Expected:** First name or initial, or a stated choice
**Actual:** 'Grace Persona14' (first + last name from the order) shown publicly and in the public API, with no notice
**Real-world impact:** Exposes customers' full names to the internet; vulnerable or older customers may be identifiable alongside their locality
**Found by:** P2-GRA-07 (Grace(P2)), P2-REG-12 (Bola(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/14-regular-grace/27b-review-on-shop-page.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/review-post.json`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/shots/41-brixton-review-viewport.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-045] The local compose stack can never exercise the card-payment path (Stripe env-var names and build-time key mismatch)

Title: `The local compose stack can never exercise the card-payment path (Stripe env-var names and build-time key mismatch)` · Labels: ux-persona-test, P1, tech-debt

**UXT-045 · P1 · major · CONFIRMED** — Blocks testing of the core money journey: every local run, persona test and E2E is cash-only.

**Steps**
1. Start the stack with docker compose -f docker-compose.full-stack.yml up with the repo .env
2. docker exec the core-java container and print STRIPE_API_KEY
3. grep the frontend container's /app/.next/static for pk_test
4. Open any shop's checkout
**Expected:** With test-mode keys in .env, core can create PaymentIntents and the browser bundle carries the publishable key, so the card path can be exercised locally
**Actual:** core-java reads STRIPE_API_KEY (docker-compose.full-stack.yml:368) but .env provides STRIPE_SECRET_KEY, so it is empty in the running container. NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY is inlined at BUILD time (frontend/app/shop/[slug]/checkout/page.tsx:107-108) but frontend/Dockerfile declares no ARG for it, so the runtime environment value at compose line 528 can never reach the browser; no pk_test in /app/.next/static. Every shop is cash-only.
**Real-world impact:** Every persona test and local E2E run is cash-only; the money path (capture, 3DS, decline, refund) is unexercisable locally, so card defects can only surface in staging or production.
**Found by:** COORD-01 (Coord)
**Suspected location (verified in source):** docker-compose.full-stack.yml:368 (STRIPE_API_KEY) vs .env STRIPE_SECRET_KEY; frontend/Dockerfile has no ARG NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY; checkout/page.tsx:107-108 inlines it at build
**Related to #461, #538, #61:** #461/#538/#61 all assume keys can be supplied; none records that compose drops them.
**Evidence:** `docker-compose.full-stack.yml:368`, `docker-compose.full-stack.yml:528`, `.env:91-92 (STRIPE_PUBLISHABLE_KEY / STRIPE_SECRET_KEY names)`, `frontend/Dockerfile (no ARG NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY)` (+1 more in catalogue.json)
**Proposed home:** Phase 30 – The Money Path, Executed

#### [UXT-046] Products have no VAT-rate choice; everything is booked as Standard 20%

Title: `Products have no VAT-rate choice; everything is booked as Standard 20%` · Labels: ux-persona-test, P1, bug

**UXT-046 · P1 · minor · CONFIRMED** — Every zero-rated (cold takeaway) item is booked at 20% VAT in the vendor's records.

**Steps**
1. Open Add Product and list the fields
**Expected:** Choose zero-rated vs standard (cold takeaway items, some drinks)
**Actual:** No VAT field; all created products are vatRate STANDARD.
**Real-world impact:** Over-declared VAT on zero-rated cold items, which the brother can only fix outside the system.
**Found by:** P2-KEM-15 (Kemi(P2))
**Related to #81:** #81 fixed the hardcoded STANDARD in the ledger code; the vendor still has no way to set a product's rate, so the outcome persists.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/s02-products-explore.txt`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/s03-add-products.txt`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

#### [UXT-047] 'VAT (incl. 20%)' is shown for every vendor, with no VAT-registration status or number captured

Title: `'VAT (incl. 20%)' is shown for every vendor, with no VAT-registration status or number captured` · Labels: ux-persona-test, P1, compliance

**UXT-047 · P1 · major · CONFIRMED** — Likely legal exposure for non-VAT-registered vendors (pending legal confirmation; P0 if confirmed).

**Steps**
1. Check out at Mama Ade's (onboarding says 'no company number — sole trader')
**Expected:** VAT shown only for VAT-registered vendors, with a VAT number
**Actual:** 'VAT (incl. 20%) £3.83' is shown; no VAT number and no field for one. The legal effect needs legal confirmation
**Real-world impact:** An unregistered sole trader appears to charge VAT (HMRC risk).
**Found by:** P2-REG-08 (Bola(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/txt_15-checkout-acked.txt`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/txt_23-vendor-onboarding.txt`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

#### [UXT-048] Menu cards show allergens as an unnamed count, 'Add' works without seeing them, and 'none declared' is never stated

Title: `Menu cards show allergens as an unnamed count, 'Add' works without seeing them, and 'none declared' is never stated` · Labels: ux-persona-test, P1, bug, compliance

**UXT-048 · P1 · major · CONFIRMED** — Blocks the allergic customer's journey: she must open every dish, and a missing section reads as reassurance.

**Steps**
1. Open /shop/brixton-village-grill
2. Read a card's allergen badge (also with a screen reader)
3. Tap 'Add' on a card; open Peri Peri Chicken's detail; repeat at 200% zoom
**Expected:** Named allergens on the card with an accessible name; an explicit 'No allergens declared' for empty items; allergens visible before Add
**Actual:** Card shows '△2' read as 'Halal Spicy £8.50 2'; Tab reaches Add before details; no allergen section for nothing-declared items; at 200% the box is below the modal fold
**Real-world impact:** An allergic customer adds dishes without seeing what is in them.
**Found by:** P05-4 (Priya(P1)), P2-MAR-11 (Marcus(P2)), P05-5 (Priya(P1)), P05-11 (Priya(P1))
**Evidence:** `.planning/ux-persona-test-20261003/05-allergy-customer-priya/ (refs: 02, ARIA snapshot)`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/aria-03-menu.yaml`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s14.out`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s03.out` (+2 more in catalogue.json)
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-049] A double-tap on 'Start Preparing' jumps the order to READY and emails the customer 'Ready!' with no undo

Title: `A double-tap on 'Start Preparing' jumps the order to READY and emails the customer 'Ready!' with no undo` · Labels: ux-persona-test, P2, bug

**UXT-049 · P2 · major · CONFIRMED** — Major trust friction: customers walk over for food that is not ready.

**Steps**
1. Have a CONFIRMED order on the board
2. Tap 'Start Preparing' twice ~250-300 ms apart (touch)
3. Check the order status via API and the customer's Mailhog inbox
**Expected:** The second tap is ignored (debounce/disabled while in flight) or an undo is offered
**Actual:** Server status READY (3/3: tablet 1024 and phone 360). The button under the finger becomes 'Mark Ready' optimistically and takes the second tap. Customer received 'Being Prepared' and 'Ready! ... please pick it up' emails at 22:12:53 in the same second. No undo control exists.
**Real-world impact:** Customer is told food is ready before it is cooked, turns up, waits, complains; with no undo the ticket state stays wrong.
**Found by:** P2-TUN-02 (Tunde(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/07-after-double-tap.png`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/22-phone-after-doubletap.png`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/mail-doubletap-order1.json`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/s5-bump.mjs` (+1 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

#### [UXT-050] Kitchen mute survives sign-out and the next person's icon shows sound on while new orders are silent

Title: `Kitchen mute survives sign-out and the next person's icon shows sound on while new orders are silent` · Labels: ux-persona-test, P2, bug

**UXT-050 · P2 · major · CONFIRMED** — Major friction: the next shift misses audible alerts.

**Steps**
1. User 1 taps the speaker to mute, signs out
2. User 2 signs in on the same tablet, opens Kitchen
3. Confirm a new order
4. Tap the speaker once
**Expected:** Mute cleared on sign-out, or at least the icon reflects the real state
**Actual:** kds-muted=true survives sign-out. Button title 'Mute alerts' with volume-2 icon (sound on) but no AudioContext is created for the new order (silent). One tap leaves the icon unchanged and sets storage to false (unmutes). Control arm (kds-muted=false) beeps and toggles correctly. Cause consistent with useState(localStorage) initializer vs SSR markup (hydration attribute mismatch).
**Real-world impact:** The next shift believes alerts are on, hears nothing, and misses orders; tapping to 'fix' it appears to do nothing.
**Found by:** P2-TUN-07 (Tunde(P2))
**Suspected location (cited by tester, not verified):** useState initialised from localStorage 'kds-muted' (member cites); kds-muted is listed in /legal/cookies
**Evidence:** `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/s10-signout-handover.out`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/s11-mute-truth.out`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/s11b-mute-repeat.out`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/20-mute-pretrue.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

#### [UXT-051] Cancelling an order is one unconfirmed tap with no reason, next to Confirm on small phone buttons

Title: `Cancelling an order is one unconfirmed tap with no reason, next to Confirm on small phone buttons` · Labels: ux-persona-test, P2, bug

**UXT-051 · P2 · major · CONFIRMED** — Major friction: accidental cancels, and the customer is told nothing useful.

**Steps**
1. On the phone Orders list tap Cancel on a Pending row
2. Observe: no confirmation dialog, no reason field
3. Open the customer's tracking page and Mailhog
**Expected:** Confirm step (Cancel sits next to Confirm), a reason (customer asked / out of stock / closing), and the reason relayed to the customer with the shop's phone
**Actual:** 3/3 cancels: immediate, no dialog, no reason. Customer sees 'This order was cancelled. If this was unexpected, please contact the shop.' Email: 'has been cancelled. Previous status: PENDING'. Shop has no phone on the storefront. Refusal and customer-requested cancel are indistinguishable
**Real-world impact:** A greasy thumb cancels a paying order irreversibly; a refused customer doesn't know why and can't reach the shop
**Found by:** P2-FUN-05 (Funmi(P2)), P2-FUN-12 (Funmi(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/s5.log`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/c33-track-after-cancel.png`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/c34-track-after-cancel.png`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/v10-o3-after-cancel.png` (+2 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

#### [UXT-052] Unanswered orders stay Pending forever and the customer is never told

Title: `Unanswered orders stay Pending forever and the customer is never told` · Labels: ux-persona-test, P2, enhancement

**UXT-052 · P2 · minor · CONFIRMED** — Major trust friction: customers wait indefinitely for orders nobody accepted.

**Steps**
1. Open /dashboard/orders
**Expected:** Pending orders time out (auto-reject with a message) after N minutes
**Actual:** Orders from 2026-09-22 and 2026-09-13 still 'Pending' with Confirm buttons
**Real-world impact:** Combined with P2-FUN-01, customers wait indefinitely for food that is never made
**Found by:** P2-FUN-14 (Funmi(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/v02-phone-orders-full.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

#### [UXT-053] Orders and Customers have no search, so an order cannot be found by customer name at the counter

Title: `Orders and Customers have no search, so an order cannot be found by customer name at the counter` · Labels: ux-persona-test, P2, enhancement

**UXT-053 · P2 · major · CONFIRMED** — Major friction at the counter during a rush.

**Steps**
1. Customer at the counter: 'I ordered, name is Bisi'
2. On the phone open /dashboard/orders - look for search
3. Open /dashboard/customers - look for search
**Expected:** A search box (name / last 4 of order number / phone) at the top of Orders
**Actual:** 0 search inputs on either page; only a status filter and 20-row pages. In the All shops view a 13-minute-old order was already off page 1 (126 orders, 7 pages). At 393 px the 30-char order number wraps to 4 lines and the status/action columns are cut off the right edge
**Real-world impact:** Queue at the counter grows while she scrolls; wrong order handed over
**Found by:** P2-FUN-07 (Funmi(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/s3.log`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/s5.log`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/v05-O1-row.png`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/v02-phone-orders-full.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

#### [UXT-054] Every kitchen ticket cuts the order number to 'ORD-…', so tickets look identical

Title: `Every kitchen ticket cuts the order number to 'ORD-…', so tickets look identical` · Labels: ux-persona-test, P2, bug

**UXT-054 · P2 · major · CONFIRMED** — Major friction: staff cannot match tickets to bags or customers.

**Steps**
1. Open the board with tickets at 360, 768 or 1024 px width
2. Read the ticket heading
**Expected:** A short readable order number/suffix (e.g. 70311A26) the customer can quote
**Actual:** Heading text 'ORD-00000000-20261003-4805B9F3' (318px) is clipped to 56-64px, rendering 'ORD-...' on every ticket
**Real-world impact:** Staff can't match a customer's quoted number to a ticket or call orders out; wrong bags handed over.
**Found by:** P2-TUN-04 (Tunde(P2)), P2-KEM-12 (Kemi(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/06-kitchen-1024-tickets.png`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/21-tablet-768-with-tickets.png`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/10-phone-360-kitchen.png`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/s12-tablet-layout.out` (+3 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

#### [UXT-055] The kitchen board is not built for a wall tablet: wasted space, small text, newest-first ordering and very tall tickets

Title: `The kitchen board is not built for a wall tablet: wasted space, small text, newest-first ordering and very tall tickets` · Labels: ux-persona-test, P2, bug

**UXT-055 · P2 · minor · CONFIRMED** — Major friction: the longest-waiting ticket sinks below the fold.

**Steps**
1. Have several confirmed tickets
2. Look at the board at 1024x768 and 768x1024
**Expected:** Oldest (most urgent) first, or a clear age ordering
**Actual:** Sorted by createdAt desc; at 1024x768 zero tickets fully above the fold (first bump button y=716), at 768 portrait 2 of 9
**Real-world impact:** The order that has waited longest is the one nobody sees; long waits, cold food.
**Found by:** P2-FUN-13 (Funmi(P2)), P2-TUN-12 (Tunde(P2)), P2-TUN-08 (Tunde(P2)), P2-CHA-11 (Nkechi(P2))
**Related to #699:** #699 (tablet gets the desktop sidebar).
**Evidence:** `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/k04-tablet-o1-confirmed.png`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/k03-tablet-after-7-orders.png`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/21-tablet-768-with-tickets.png`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/06-kitchen-1024-tickets.png` (+6 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

#### [UXT-056] No ready-by or delivery time exists anywhere: tickets, confirmation, tracking or emails

Title: `No ready-by or delivery time exists anywhere: tickets, confirmation, tracking or emails` · Labels: ux-persona-test, P2, enhancement

**UXT-056 · P2 · major · CONFIRMED** — Major trust gap: customers do not know when to walk over.

**Steps**
1. Confirm an order
2. Read the ticket and the customer's tracking page
**Expected:** Ticket shows due time / order of cooking; customer sees an estimated ready time
**Actual:** Ticket shows only '20m ago' counted from placement (not confirmation); tracking and emails give no time
**Real-world impact:** Kitchen cooks in the wrong order; customers turn up too early and crowd the counter
**Found by:** J-03 (Jordan(P1)), P2-FUN-10 (Funmi(P2)), P2-GRA-17 (Grace(P2))
**Related to #458:** #458 asks for profile-based, auto-populating tracking; ETA is not tracked.
**Evidence:** `.planning/ux-persona-test-20261003/04-hungry-customer-jordan/ (refs: 14, 15, mail1.json)`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/k04-o1-card-confirmed.png`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/c13-o1-ready.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/21-track-no-number.png` (+2 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-057] Order confirmation and every status email omit the shop, items, total and address, and come from inconsistent senders

Title: `Order confirmation and every status email omit the shop, items, total and address, and come from inconsistent senders` · Labels: ux-persona-test, P2, bug

**UXT-057 · P2 · minor · CONFIRMED** — Major trust gap: the customer has no written record of price or contents, and the privacy notice's 'shop is named on your confirmation' is false.

**Steps**
1. Place an order
2. Read the email
**Expected:** The email repeats the items, the total and the allergens
**Actual:** Four plain lines and a tracking link; no total, items or allergens. Together with P2-CHA-01 and -02, this leaves no customer-side record of either the price or the allergen set.
**Real-world impact:** In a dispute at the door, the customer has no paper trail.
**Found by:** J-06 (Jordan(P1)), J-07 (Jordan(P1)), P2-FUN-15 (Funmi(P2)), P2-CHA-17 (Nkechi(P2)), P2-GRA-14 (Grace(P2)), P2-REG-07 (Bola(P2)), F-11 (Sam(P1)), P2-GRA-19 (Grace(P2))
**Related to #649:** #649 (olajay.co.uk expiry) touches the two-domain sender/contact split.
**Evidence:** `.planning/ux-persona-test-20261003/04-hungry-customer-jordan/ (refs: mail1.json)`, `.planning/ux-persona-test-20261003/04-hungry-customer-jordan/ (refs: 14, 15, 19)`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/s3c.log`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/s5.log` (+9 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-058] Finance has no 'today', no date range, no export and no cash/card split

Title: `Finance has no 'today', no date range, no export and no cash/card split` · Labels: ux-persona-test, P2, enhancement

**UXT-058 · P2 · major · CONFIRMED** — Major friction: end-of-night cash-up and the accountant's month-end cannot be done.

**Steps**
1. Open /dashboard/finance as admin-user-b
2. Look for export/download, a date filter, a payment-method split
3. Inspect completed cash orders via GET /api/v1/orders
**Expected:** Download a VAT summary and a transaction list for a period, by site, split by cash and card
**Actual:** Finance has no buttons or links except the footer; no date filter; no downloads were triggered. Completed cash orders have paymentMethod null and paymentStatus NONE, so cash and card cannot be separated. The financial-transactions API takes no parameters.
**Real-world impact:** Kemi's brother has to retype every order into a spreadsheet each quarter for VAT (MTD) and cannot reconcile the till's cash.
**Found by:** P2-FUN-08 (Funmi(P2)), P2-KEM-06 (Kemi(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/v17-finance.png`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/v17-finance-full.png`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/v06-phone-o2-detail.png`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/24-finance-All_shops.png` (+3 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

#### [UXT-059] A completed cash order stays 'Payment status NONE / Unpaid' while counted as revenue

Title: `A completed cash order stays 'Payment status NONE / Unpaid' while counted as revenue` · Labels: ux-persona-test, P2, bug

**UXT-059 · P2 · minor · CONFIRMED** — Major trust gap in the vendor's records.

**Steps**
1. Complete a cash-on-delivery order
2. Open its detail
**Expected:** Marked paid (cash) on completion
**Actual:** PAYMENT STATUS NONE, METHOD Unpaid
**Real-world impact:** The records contradict what happened.
**Found by:** P2-REG-11 (Bola(P2))
**Related to #461:** #461 removes pay-on-collection; until then the record is wrong.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/order-detail-after.json`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/txt_21-vendor-order-detail.txt`
**Proposed home:** Phase 30 – The Money Path, Executed

#### [UXT-060] Cash-only is revealed only at the bottom of checkout, and the Place order button shows a card icon

Title: `Cash-only is revealed only at the bottom of checkout, and the Place order button shows a card icon` · Labels: ux-persona-test, P2, bug

**UXT-060 · P2 · major · CONFIRMED** — Major friction: customers expecting card pay abandon at the last step.

**Steps**
1. Browse /shop, a shop menu and the basket
2. Go to checkout and scroll to the bottom
**Expected:** Payment method stated on the shop card/menu/basket; a cash-appropriate button
**Actual:** Nothing until 'Pay on collection — cash to the shop' at the bottom; card icon on 'Place order'
**Real-world impact:** Card-expecting customers abandon at the final step.
**Found by:** J-05 (Jordan(P1)), P2-FUN-16 (Funmi(P2)), P05-12 (Priya(P1))
**Related to #461:** #461 replaces pay-on-collection with payment links.
**Evidence:** `.planning/ux-persona-test-20261003/04-hungry-customer-jordan/ (refs: 11, 12)`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/c21-checkout-86-place-result.png`, `.planning/ux-persona-test-20261003/05-allergy-customer-priya/ (refs: 23, 08)`
**Proposed home:** Phase 30 – The Money Path, Executed

#### [UXT-061] Menu 'Add' buttons are visible but ignore taps until hydration (4.2 s on 4G, 14 s on Slow 3G)

Title: `Menu 'Add' buttons are visible but ignore taps until hydration (4.2 s on 4G, 14 s on Slow 3G)` · Labels: ux-persona-test, P2, bug

**UXT-061 · P2 · major · CONFIRMED** — Major friction: first taps are silently lost on mobile.

**Steps**
1. iPhone 13 profile, 4G throttle (and Slow 3G)
2. Open a shop link fresh and tap Add as soon as it shows
**Expected:** First tap adds (or the button is visibly inactive)
**Actual:** Taps ignored until 4.2 s (4G) / 14.1 s and 7 taps (Slow 3G)
**Real-world impact:** Impatient mobile customers think the site is broken.
**Found by:** J-02 (Jordan(P1))
**Related to #507:** #507 (client pages fetch on mount).
**Evidence:** `.planning/ux-persona-test-20261003/04-hungry-customer-jordan/ (refs: s17, 36-*)`
**Proposed home:** Phase 34 – Rendering + Test Truthfulness (complete: #507 remainder)

#### [UXT-062] Saving a shop overwrites its banner with the logo URL

Title: `Saving a shop overwrites its banner with the logo URL` · Labels: ux-persona-test, P2, bug

**UXT-062 · P2 · major · CONFIRMED** — Major trust friction: the storefront banner is silently replaced (recoverable by re-upload).

**Steps**
1. Shop with a logo: Edit shop → upload a banner
2. Click Update Shop
3. Read the shop's bannerUrl
**Expected:** bannerUrl = the uploaded banner
**Actual:** bannerUrl = logoUrl (reproduced twice)
**Real-world impact:** The vendor's banner disappears unnoticed until a customer sees it.
**Found by:** ADE-02 (Ade(P1))
**Suspected location (verified in source):** frontend/components/ui/image-uploader.tsx:397-399 (product.imageUrl || product.logoUrl || product.bannerUrl picks logoUrl from the ShopDto)
**Evidence:** `.planning/ux-persona-test-20261003/01-new-vendor-ade/ (refs: s14, s16; 21)`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-G Catalogue & shop-admin correctness

#### [UXT-063] Products cannot be deleted once they have a photo or any order (even cancelled): misleading 409

Title: `Products cannot be deleted once they have a photo or any order (even cancelled): misleading 409` · Labels: ux-persona-test, P2, bug

**UXT-063 · P2 · major · CONFIRMED** — Major friction: test and retired items stay in the catalogue forever; the error claims a false order reference.

**Steps**
1. Order product X (the order can be cancelled)
2. As the vendor, DELETE /api/v1/products/{id}
**Expected:** Delete (archive) works; history keeps the snapshotted name (order_items.product_name already exists)
**Actual:** 409 "Product cannot be deleted: it is still referenced by an existing order" for 11 of my 13 test products, all of whose orders were CANCELLED. The only option is available=false, so they stay in the vendor catalogue for good.
**Real-world impact:** A vendor's product list fills with dead seasonal items; the vendor cannot tidy the catalogue.
**Found by:** ADE-04 (Ade(P1)), P2-CHA-19 (Nkechi(P2))
**Suspected location (cited by tester, not verified):** 409 raised on product_media_product_id_fkey and order_items FK, mapped to 'referenced by an existing order' (member ADE-04)
**Evidence:** `.planning/ux-persona-test-20261003/01-new-vendor-ade/ (refs: 33, 35; s23, s24, s26)`, `.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/cleanup.out`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-G Catalogue & shop-admin correctness

#### [UXT-064] Deleting a shop orphans its products, and editing an orphan silently moves it to 'All Shops'

Title: `Deleting a shop orphans its products, and editing an orphan silently moves it to 'All Shops'` · Labels: ux-persona-test, P2, bug

**UXT-064 · P2 · major · CONFIRMED** — Major friction and data drift in the catalogue.

**Steps**
1. Create a shop with products
2. Delete the shop (204)
3. List products; GET one; edit one
**Expected:** Delete refused or products archived with the shop
**Actual:** Products still listed, GET returns 404 'Shop not found', editing moves them to All Shops
**Real-world impact:** Ghost products accumulate and can reappear on the wrong scope.
**Found by:** ADE-05 (Ade(P1))
**Related to #727:** Same null-shop product semantics as #727.
**Evidence:** `.planning/ux-persona-test-20261003/01-new-vendor-ade/ (refs: s25, s27, s28)`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-G Catalogue & shop-admin correctness

#### [UXT-065] The image dialog stays on 'Processing…' although the server has the image ACTIVE within ~6 s

Title: `The image dialog stays on 'Processing…' although the server has the image ACTIVE within ~6 s` · Labels: ux-persona-test, P2, bug

**UXT-065 · P2 · major · CONFIRMED** — Major friction: the upload looks broken.

**Steps**
1. Add product → upload a photo
2. Wait in the dialog
**Expected:** Image appears when ACTIVE
**Actual:** 'Processing…' for 30 s, 30 s and >120 s; only reopening shows it
**Real-world impact:** Vendors re-upload or give up on photos.
**Found by:** ADE-06 (Ade(P1))
**Evidence:** `.planning/ux-persona-test-20261003/01-new-vendor-ade/ (refs: 24, 26; s19, s20)`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-G Catalogue & shop-admin correctness

#### [UXT-066] The 'Publish to storefront' checkbox is ignored, or throws away the whole shop edit with a raw developer error

Title: `The 'Publish to storefront' checkbox is ignored, or throws away the whole shop edit with a raw developer error` · Labels: ux-persona-test, P2, bug

**UXT-066 · P2 · minor · CONFIRMED** — Major friction: edits are lost and the error tells the vendor to call a non-existent support line.

**Steps**
1. Edit kemi-p2-peckham, change Sunday hours to 12:00-20:00 and the description, tick 'Publish to storefront', click Update Shop
**Expected:** Either the checkbox is not offered, or it explains how to publish in plain words; other edits are saved
**Actual:** 409. The dialog shows 'Shop.published is written only by the onboarding state machine… use POST /api/v1/onboarding/go-live (APPROVED -> LIVE)… contact support'. The hours and description were NOT saved (API read-back still shows sun 12:00-21:00). There is no support contact anywhere (pass-1 C-05).
**Real-world impact:** The owner thinks the system is broken and her opening-hours change is lost unless she spots the need to untick and save again.
**Found by:** ADE-11 (Ade(P1)), P2-KEM-04 (Kemi(P2))
**Evidence:** `.planning/ux-persona-test-20261003/01-new-vendor-ade/ (refs: 18, 19)`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/20-publish-tick-error.png`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/s08-publish-tick.txt`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/s07-onboarding.txt`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-G Catalogue & shop-admin correctness

#### [UXT-067] Internal strategy pages are public, expose a repo path, and contradict the landing page (incl. a 'payouts: Full' claim)

Title: `Internal strategy pages are public, expose a repo path, and contradict the landing page (incl. a 'payouts: Full' claim)` · Labels: ux-persona-test, P2, compliance

**UXT-067 · P2 · major · CONFIRMED** — Major trust damage with prospects: both evaluators' trust fell sharply here.

**Steps**
1. Open /business-model-guide and /competitive from the footer
2. Compare with / and /for-operators
**Expected:** No internal strategy on public pages; consistent claims
**Actual:** 'Do not claim: … self-service tenancy', 'RAISED £0', repo path DOCS/ANALYSIS/…; 'payouts: Full' vs 'not production settlement'; /competitive absent from sitemap
**Real-world impact:** Prospects read the platform's own 'do not claim' list and walk away.
**Found by:** ADE-07 (Ade(P1)), C-06 (Claire(P1)), C-01 (Claire(P1)), C-15 (Claire(P1))
**Evidence:** `.planning/ux-persona-test-20261003/01-new-vendor-ade/ (refs: 04, 05)`, `.planning/ux-persona-test-20261003/08-sceptical-evaluator-claire/ (refs: txt_business-model-guide.txt, txt_competitive.txt)`, `.planning/ux-persona-test-20261003/08-sceptical-evaluator-claire/ (refs: txt_competitive.txt, txt_for-operators.txt, txt_business-model-guide.txt)`, `.planning/ux-persona-test-20261003/08-sceptical-evaluator-claire/ (refs: curl output)`
**Proposed home:** Phase 32 – Production Cutover + First Tenant

#### [UXT-068] Test and demo data is visible to customers and vendors (E2E 20% OFF promo, weeks-old test orders, a real person's name and email)

Title: `Test and demo data is visible to customers and vendors (E2E 20% OFF promo, weeks-old test orders, a real person's name and email)` · Labels: ux-persona-test, P2, tech-debt

**UXT-068 · P2 · minor · CONFIRMED** — Major trust damage; a real person's PII in demo data is a data-protection concern.

**Steps**
1. Open /shop/mama-ades-kitchen
2. Sign in as admin-user and open the dashboard overview
**Expected:** No test artefacts
**Actual:** 'E2E launch offer — E2E 20% OFF' banner; Pending test orders 11–27 days old; a real name + Hotmail address; a '<b>x</b>' customer
**Real-world impact:** Evaluators read the product as unfinished; real PII is exposed in demos.
**Found by:** C-12 (Claire(P1))
**Evidence:** `.planning/ux-persona-test-20261003/08-sceptical-evaluator-claire/ (refs: shots/d_dashboard.png, shots/pub_shop_mama-ades-kitchen.png)`
**Proposed home:** Phase 32 – Production Cutover + First Tenant

#### [UXT-069] There is no account page: data access and erasure are mailto-only although a backend intake exists

Title: `There is no account page: data access and erasure are mailto-only although a backend intake exists` · Labels: ux-persona-test, P2, enhancement, compliance

**UXT-069 · P2 · major · CONFIRMED** — Major friction: exercising data rights is far harder than giving the data.

**Steps**
1. Sign in as a customer at 360px
2. Open the menu: only 'Shops' and 'My Orders'
3. Try /account (404) and /shop/account (soft 'Shop not found')
4. Read the privacy notice: write to privacy@olajay.co.uk
5. Keycloak realm jtoye-customers: delete_account required action is disabled
**Expected:** Account settings with 'Delete my account' and 'Download my data'
**Actual:** mailto to a non-brand domain is the only route
**Real-world impact:** Older customers without a configured mail app cannot exercise their rights; the backend intake exists but has no front door
**Found by:** F-02 (Sam(P1)), P2-GRA-03 (Grace(P2))
**Evidence:** `.planning/ux-persona-test-20261003/06-impatient-privacy-sam/ (refs: 38, 37)`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/39-menu-signed-in.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/16-week2-menu.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/privacy-notice.txt`
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-070] The DSAR confirmation link does not open on compose and shows raw JSON when it does

Title: `The DSAR confirmation link does not open on compose and shows raw JSON when it does` · Labels: ux-persona-test, P2, bug, compliance

**UXT-070 · P2 · minor · CONFIRMED** — Major friction: a non-technical requester cannot confirm, and on the canonical dev runtime no DSAR can be verified.

**Steps**
1. Lodge a DSAR on the compose stack
2. Open the emailed link
**Expected:** A friendly confirmation page on the web app
**Actual:** Link host localhost:8080: ERR_CONNECTION_REFUSED (DSAR_VERIFY_BASE_URL unset in compose; set in k8s, so prod is SUSPECTED fine). With the host corrected, the response is raw application/json {"status":"verified",…}
**Real-world impact:** On the canonical dev/E2E runtime no erasure can ever be confirmed; in prod a non-technical user sees JSON
**Found by:** P2-GRA-13 (Grace(P2))
**Suspected location (cited by tester, not verified):** DSAR_VERIFY_BASE_URL unset in compose (set in k8s, member cites)
**Evidence:** `.planning/ux-persona-test-20261003-pass2/14-regular-grace/41-dsar-link-as-emailed.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/42-dsar-verify-ERASURE.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/42-dsar-verify-ACCESS.png`
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-071] The cookie policy omits keys that hold the customer's email and id and survive sign-out

Title: `The cookie policy omits keys that hold the customer's email and id and survive sign-out` · Labels: ux-persona-test, P2, compliance

**UXT-071 · P2 · minor · CONFIRMED** — PECR transparency gap: stored personal data is undisclosed.

**Steps**
1. Place an order signed in, sign out
2. Inspect localStorage and compare with /legal/cookies
**Expected:** Every stored key disclosed; personal keys cleared on sign-out
**Actual:** jtoye-guest-orders (order no. + email) written for signed-in orders and survives sign-out; jtoye-customer-last-signin (customer UUID) not listed
**Real-world impact:** A shared device keeps a previous customer's email; the policy misstates storage.
**Found by:** F-05 (Sam(P1))
**Evidence:** `.planning/ux-persona-test-20261003/06-impatient-privacy-sam/ (refs: s5/s19)`
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-072] Order contact details are barely validated, and API/MCP orders need no customer contact at all

Title: `Order contact details are barely validated, and API/MCP orders need no customer contact at all` · Labels: ux-persona-test, P2, bug

**UXT-072 · P2 · minor · CONFIRMED** — Major friction: vendors cannot reach customers, and junk orders look real.

**Steps**
1. Place orders with customerPhone values 'call me', 'no', 'x', '0', '1' and junk names
2. All accepted 201
**Expected:** At least a plausible-phone / plausible-name check on an order the vendor may need to call back about
**Actual:** GuestOrderRequest only length-caps these fields; any non-empty string passes. (Reconfirms pass-1 F-07 for email/phone, extended to clearly fake values.)
**Real-world impact:** A no-show prank order gives the vendor no way to chase the customer: the phone is 'x'. Combined with KYL-01 this makes every fake order a dead end.
**Found by:** F-07 (Sam(P1)), P2-KYL-06 (Kyle(P2)), P2-RAV-16 (Ravi(P2))
**Evidence:** `.planning/ux-persona-test-20261003/06-impatient-privacy-sam/ (refs: 13)`, `.planning/ux-persona-test-20261003-pass2/16-prankster-kyle/k2-bulk.json`, `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/mcp-edge-cases.txt`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-D Abuse resistance

#### [UXT-073] Vendors have no bulk-reject and no fraud signal for junk orders

Title: `Vendors have no bulk-reject and no fraud signal for junk orders` · Labels: ux-persona-test, P2, enhancement

**UXT-073 · P2 · major · CONFIRMED** — Major friction under attack: 25 fake orders = 25 manual cancels.

**Steps**
1. View the vendor Orders page with 15 kyle-p2 fake orders present
2. Each row has its own Confirm / Cancel button; there is no select-all, no bulk action, no 'suspected spam' flag
**Expected:** A way to triage a wave of suspicious orders: bulk-reject, a risk/new-email flag, or a hold-for-review state
**Actual:** Orders are confirmed/cancelled individually. A throwaway-email order with phone 'x' looks identical to a genuine one. 25 fake orders = 25 manual cancels.
**Real-world impact:** Even once the vendor realises they are being trolled, clearing the mess is slow manual work during service. The product gives them no help distinguishing or dispatching the junk.
**Found by:** P2-KYL-04 (Kyle(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/16-prankster-kyle/05-dashboard-orders.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-D Abuse resistance

#### [UXT-074] On a slow network, returning after 5 minutes signs the customer out (parallel refreshes burn the single-use token)

Title: `On a slow network, returning after 5 minutes signs the customer out (parallel refreshes burn the single-use token)` · Labels: ux-persona-test, P2, bug

**UXT-074 · P2 · major · CONFIRMED** — Major friction: customers are signed out mid-checkout on poor connections.

**Steps**
1. Sign in as a customer
2. Wait more than 300 s (accessTokenLifespan), so only the refresh and id cookies remain
3. Throttle to Slow 3G (2000 ms RTT) with 20% packet loss
4. Open /shop/{slug}/checkout
5. Watch the /api/customer-auth/session responses
**Expected:** One renewal; she stays signed in
**Actual:** Seen 3 out of 3 times (phone at 23:28, laptop at 23:29 and 23:36). The nav session store probes on mount and then every second for 5 s, and checkout fires its own probe. Under latency they all leave carrying the same refresh token. The first returns authenticated:true and new cookies. The next three return authenticated:false with Set-Cookie clearing all three customer cookies, and she ends up signed out ('Not you? Sign out | Sign in'). Unthrottled, the same flow renewed cleanly (s10 at 23:22). The basket survives (the owner stamp is kept). One tap on 'Sign in' restores the session silently while the Keycloak SSO cookie lives.
**Real-world impact:** Office wifi or a train on 3G: she comes back to a half-built order and finds herself signed out. Anything she places now is a guest order, and My Orders shows 'Sign in to continue'. The site seems to have lost her, which costs trust.
**Found by:** P2-CHA-05 (Nkechi(P2))
**Regression of closed #465:** #465 closed the 300-s session cliff; under latency the parallel probes reproduce it (3/3).
**Evidence:** `.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/s11-phone-slow-return.out`, `.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/s11-laptop-slow-return.out`, `.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/s11b-laptop-slow-return-with-basket.out`, `.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/10-phone-return-checkout.png` (+2 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-075] Customers cannot order ahead for a time, and a closed shop takes no pre-orders

Title: `Customers cannot order ahead for a time, and a closed shop takes no pre-orders` · Labels: ux-persona-test, P2, enhancement

**UXT-075 · P2 · major · CONFIRMED** — Major gap versus competitors (lunch pre-orders).

**Steps**
1. Open checkout and look for a time or date field
2. As the vendor, set the shop's hours to end before now
3. Try to order
**Expected:** An office lunch for 8 is ordered in the morning for a set time; a closed shop accepts orders for its next opening
**Actual:** There is no time field (GuestOrderRequest has none). While the shop is closed, the menu shows 'Closed' and hides Add for new items, but it keeps the steppers and the 'View basket £188.50' bar. The submit is refused with 'Peckham Jollof Co. is currently closed. Opening hours today: 10:00 - 22:00. Please try again later.' The refusal itself is correct and clear.
**Real-world impact:** Office lunches are the biggest baskets and are always scheduled. Without pre-orders she orders at 12:00 and hopes, and the vendor loses planned, high-value orders.
**Found by:** P2-CHA-09 (Nkechi(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/05d-checkout-before-hours-change.png`, `.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/05e-submit-after-closing-time.png`, `.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/05f-menu-shop-closed.png`, `.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/05g-discovery-shop-closed.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-076] No 'order again' and no remembered address: a weekly order takes 10 taps and 85 keystrokes

Title: `No 'order again' and no remembered address: a weekly order takes 10 taps and 85 keystrokes` · Labels: ux-persona-test, P2, enhancement

**UXT-076 · P2 · major · CONFIRMED** — Major retention friction for regulars.

**Steps**
1. Week 2, fresh browser, open the shop link on Slow 3G
2. Sign in, add the same two items, check out
**Expected:** Reorder from My Orders, with saved address and phone
**Actual:** No reorder or favourites on My Orders or tracking; checkout pre-fills only name and email; address, postcode and phone blank. Total 10 taps, 85 chars, 83s
**Real-world impact:** Regulars, the most valuable customers, get no repeat-order advantage over aggregators with one-tap reorder
**Found by:** P2-GRA-05 (Grace(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/14-regular-grace/17-my-orders.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/18-order-detail.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/19-week2-checkout-prefill.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/s08-week2-reorder.mjs`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-077] Customers have no way to leave a review; the only path is an unauthenticated API call with an email in the URL

Title: `Customers have no way to leave a review; the only path is an unauthenticated API call with an email in the URL` · Labels: ux-persona-test, P2, enhancement

**UXT-077 · P2 · major · CONFIRMED** — Major gap: reviews exist publicly but cannot be left legitimately.

**Steps**
1. Have the vendor complete the order
2. Check My Orders, the tracking page and the 'Completed' email for a review entry point
**Expected:** A 'Rate your order' entry point
**Actual:** None. The only path is POST /api/v1/public/shops/{slug}/reviews?email= with the order UUID, which no customer surface exposes (the tracking payload has no id)
**Real-world impact:** The shop's reviews section (which exists) stays empty; vendors lose social proof
**Found by:** P2-GRA-06 (Grace(P2)), P2-GRA-18 (Grace(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/14-regular-grace/25-my-orders-with-completed.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/26-track-completed.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/track-api-order1.json`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/review-post.json`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-078] Password-reset links expire after 5 minutes

Title: `Password-reset links expire after 5 minutes` · Labels: ux-persona-test, P2, bug

**UXT-078 · P2 · major · CONFIRMED** — Major friction for less-confident users.

**Steps**
1. Forgot Password? and submit the email
2. Open the Mailhog link after 6 minutes
**Expected:** A link valid for 30-60 minutes
**Actual:** 'Action expired. Please start again.' (realm actionTokenGeneratedByUserLifespan = 300s; the email says 5 minutes)
**Real-world impact:** Customers who read email late, or through someone else, are locked out and abandon
**Found by:** P2-GRA-08 (Grace(P2))
**Suspected location (cited by tester, not verified):** Keycloak jtoye-customers actionTokenGeneratedByUserLifespan = 300 (member cites)
**Evidence:** `.planning/ux-persona-test-20261003-pass2/14-regular-grace/36-reset-link-after-6min.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/s15-out.txt`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/mail-reset.txt`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-079] Keycloak sign-in and registration fall below the storefront's accessibility bar and hide password rules until failure

Title: `Keycloak sign-in and registration fall below the storefront's accessibility bar and hide password rules until failure` · Labels: ux-persona-test, P2, bug, accessibility

**UXT-079 · P2 · major · CONFIRMED** — Accessibility failure on the account journey (WCAG A/AA).

**Steps**
1. From /shop/signin press "Create an account"
2. Press Enter on "Register" with every field empty
3. Read the AX tree, focus and aria-describedby
**Expected:** Errors are associated with their fields, focus moves to the first error, required fields are exposed, the page has lang and a main landmark, and the title says "Register"
**Actual:** Focus lands on <body> after the reload. Email, Password, First and Last name are aria-invalid but have no aria-describedby, so NVDA says "Email, edit, invalid entry" without the reason. Required is shown only as an asterisk (required=false). axe reports html-has-lang (serious), landmark-one-main, region and tabindex>0 on 7 controls (login). The register page is titled "Sign in to J'Toye". An empty login says "Invalid username or password."
**Real-world impact:** Account creation is the step where a blind user is most likely to stall. It is a J'Toye-branded theme, so these templates are within the project's control even though the accessibility statement lists the pages as third-party.
**Found by:** P2-MAR-06 (Marcus(P2)), P2-GRA-16 (Grace(P2))
**Related to #545:** #545 (stock Keycloak theme on both realms).
**Evidence:** `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s10.out`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/10-kc-login.png`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/10b-kc-login-empty.png`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/10c-kc-register.png` (+6 more in catalogue.json)
**Proposed home:** Phase 33 – The Consumer Product (CUST-02/CUST-04 still open)

#### [UXT-080] At large text sizes the basket, tracker and shop header truncate or overflow

Title: `At large text sizes the basket, tracker and shop header truncate or overflow` · Labels: ux-persona-test, P2, bug, accessibility

**UXT-080 · P2 · major · CONFIRMED** — Accessibility failure (WCAG 1.4.4/1.4.10) for older customers.

**Steps**
1. 360px, root font 130%
2. Add Jollof Rice and Fried Plantain and open the basket
**Expected:** Names wrap
**Actual:** h3 truncated to 3 letters with an ellipsis
**Real-world impact:** Low-vision users cannot check their order before paying; wrong orders and complaints
**Found by:** P2-GRA-09 (Grace(P2)), P2-GRA-10 (Grace(P2)), P2-GRA-11 (Grace(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/14-regular-grace/09-basket.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/18-order-detail.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/24-track-preparing.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/02-arrive-loaded.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-F Accessibility

#### [UXT-081] Tracking pages are silent and unlabelled for screen readers (status changes, current step, copy button, lookup result, field name, contrast)

Title: `Tracking pages are silent and unlabelled for screen readers (status changes, current step, copy button, lookup result, field name, contrast)` · Labels: ux-persona-test, P2, bug, accessibility

**UXT-081 · P2 · major · CONFIRMED** — Accessibility failure on the post-order journey (WCAG A/AA).

**Steps**
1. Place an order and open the "Track your order" link
2. As the vendor, POST /api/v1/orders/{id}/confirm, wait 20 s
3. POST /api/v1/orders/{id}/start-preparation, wait 20 s
4. Record live-region output
**Expected:** Each change is announced through a polite status region, e.g. "Order confirmed by the shop"
**Actual:** The 15-second poll updates the DOM (the timestamp moves to the new step) but nothing is announced: 0 live-region events across both transitions. The only live region on the page is the basket count.
**Real-world impact:** A screen-reader user waiting for a collection order never learns that it is ready. They phone the shop or turn up early or late.
**Found by:** P2-MAR-02 (Marcus(P2)), P2-MAR-03 (Marcus(P2)), P2-MAR-08 (Marcus(P2)), P2-MAR-13 (Marcus(P2)), P2-MAR-14 (Marcus(P2)), P2-MAR-17 (Marcus(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s09.out`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/09-track-before.png`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/09-track-after.png`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/aria-09-track-before.yaml` (+14 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-F Accessibility

#### [UXT-082] Shop cards on the kitchen list have no accessible name ('link, link, link')

Title: `Shop cards on the kitchen list have no accessible name ('link, link, link')` · Labels: ux-persona-test, P2, bug, accessibility

**UXT-082 · P2 · major · CONFIRMED** — Accessibility failure (WCAG 4.1.2) that axe cannot see.

**Steps**
1. Open /shop?q=SW9%208PR
2. Tab past the suggested-search chips to the results
3. Read Chrome's computed AX tree (CDP Accessibility.getFullAXTree)
**Expected:** Each card link is named after the shop, e.g. "Brixton Village Grill, open, 0.0 miles"
**Actual:** All 3 card links have an empty name in Chrome's own tree. The <a> wraps an <article>, which stops name-from-content. Tab announces only "link". axe-core reports 0 violations on the page, so the automated gate is blind to it. The same happens on /shop with no query.
**Real-world impact:** NVDA and VoiceOver users tabbing the results hear three identical "link"s and must switch to heading navigation to find a kitchen. Many will give up on the first screen.
**Found by:** P2-MAR-04 (Marcus(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s02b.log`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s12.out`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/aria-02-shop-results.yaml`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/axe-02-shop-results.json`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-F Accessibility

#### [UXT-083] Pressing Add/Remove drops focus to the page body and the basket announcement doubles the count without naming the item

Title: `Pressing Add/Remove drops focus to the page body and the basket announcement doubles the count without naming the item` · Labels: ux-persona-test, P2, bug, accessibility

**UXT-083 · P2 · major · CONFIRMED** — Accessibility failure on the core add-to-basket action.

**Steps**
1. Open /shop/brixton-village-grill
2. Focus "Add Mango Lassi to basket" and press Enter
3. Check document.activeElement
**Expected:** Focus moves to the new stepper control (e.g. "Increase quantity of Mango Lassi")
**Actual:** The Add button is replaced by the stepper and activeElement becomes <body>. The same happens when "Remove … from basket" takes the quantity back to 0. Seen 4 times (Mango Lassi, Sobo Punch add and remove, Chapman, Jollof Rice on iPhone 13). Chrome's next Tab happens to recover; a screen reader's virtual cursor and VoiceOver focus do not.
**Real-world impact:** After every add, a screen-reader user loses their place in the menu and must find the dish again. Building a basket of several items becomes tiring.
**Found by:** P2-MAR-05 (Marcus(P2)), P2-MAR-07 (Marcus(P2))
**Related to #272:** #272 fixed '1 items'; the live region now reads '1 1 item in basket'.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s04.out`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s05.out`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s13.out`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s04.out` (+1 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-F Accessibility

#### [UXT-084] Basket, checkout, confirmation and tracking all share the title 'J'Toye — Discover Local Vendors'

Title: `Basket, checkout, confirmation and tracking all share the title 'J'Toye — Discover Local Vendors'` · Labels: ux-persona-test, P2, bug, accessibility

**UXT-084 · P2 · minor · CONFIRMED** — Accessibility failure (WCAG 2.4.2).

**Steps**
1. Navigate basket → checkout → place order → track
2. Read document.title at each step
**Expected:** Unique titles such as "Your basket — Brixton Village Grill" and "Order confirmed — …"
**Actual:** All four pages use the generic site title, so the Next.js route announcer reads "J'Toye — Discover Local Vendors" at every step (WCAG 2.4.2).
**Real-world impact:** Page changes do not tell the user where they are. This compounds P2-MAR-01.
**Found by:** P2-MAR-09 (Marcus(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s05.out`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s06.out`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s08.out`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s09.out`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-F Accessibility

#### [UXT-085] On mobile the cookie banner covers content and keyboard focus and is the 32nd tab stop

Title: `On mobile the cookie banner covers content and keyboard focus and is the 32nd tab stop` · Labels: ux-persona-test, P2, bug, accessibility

**UXT-085 · P2 · minor · CONFIRMED** — Accessibility failure (WCAG 2.4.11 focus not obscured).

**Steps**
1. Open / on an iPhone 13 profile
2. Tab through the page
3. Measure the overlap between the focused element and the fixed cookie banner
**Expected:** A focused element is never hidden by the banner (WCAG 2.2 SC 2.4.11), and the banner is reachable early
**Actual:** The banner covers y=482–664 of a 664 px viewport. 19 of the first 30 focus stops are overlapped, many 100% (Desserts, Use my location, See all kitchens, footer links). "Got it" needs 32 Tabs because the banner is last in the DOM. After dismissal, focus returns to the top of the document. The banner does not trap focus.
**Real-world impact:** Sighted keyboard and switch users lose the focus indicator under the banner. Screen-reader users hear the cookie notice only at the very end.
**Found by:** ADE-15 (Ade(P1)), J-15 (Jordan(P1)), P2-MAR-12 (Marcus(P2))
**Evidence:** `.planning/ux-persona-test-20261003/01-new-vendor-ade/ (refs: 01)`, `.planning/ux-persona-test-20261003/04-hungry-customer-jordan/ (refs: 01)`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/s12.out`, `.planning/ux-persona-test-20261003-pass2/15-screenreader-marcus/12-mobile-landing.png` (+1 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-F Accessibility

#### [UXT-086] A delivery customer's tracking page says 'Ready for collection'

Title: `A delivery customer's tracking page says 'Ready for collection'` · Labels: ux-persona-test, P2, bug

**UXT-086 · P2 · major · CONFIRMED** — Major trust friction: delivery customers are told to come in.

**Steps**
1. Place a DELIVERY order
2. Confirm, Start Preparing, Mark Ready on the vendor side
3. Open the customer's tracking page
**Expected:** Ready step says 'Out for delivery' / 'On its way' for delivery orders (as the email does)
**Actual:** Ready step reads 'Ready for collection · 23:34' for delivery order ORD-...-8EAF908A, while the Ready email says 'There's no need to come to the shop'
**Real-world impact:** Delivery customers walk to the shop or phone to ask; the two channels contradict each other
**Found by:** P2-FUN-04 (Funmi(P2))
**Related to #502:** #502 fixed the READY email; the tracking timeline still uses collection copy.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/c15-o2-ready-delivery.png`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/s3c.log`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-087] A kitchen hand sees far more customer personal data than needed to cook

Title: `A kitchen hand sees far more customer personal data than needed to cook` · Labels: ux-persona-test, P2, bug, compliance

**UXT-087 · P2 · minor · CONFIRMED** — Data-minimisation gap: kitchen payload and landing page carry email, phone, address.

**Steps**
1. Sign in as tenant-a-user
2. Read the Dashboard landing, Customers, Orders
3. GET /api/v1/orders/{id}/detail
**Expected:** Kitchen role sees first name, items, allergens, notes, fulfilment type
**Actual:** Landing page lists recent orders with customer names and emails; Customers page lists all customers with emails/phones; Orders shows all shops; kitchen API payload carries email, phone, delivery address, totals, payment method
**Real-world impact:** Data-minimisation (UK GDPR Art 5(1)(c)) gap; a casual worker can photograph a customer list.
**Found by:** P2-TUN-10 (Tunde(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/01-first-landing-768.png`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/11-phone-customers.png`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/s7-api-mutations.out`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

#### [UXT-088] A vendor cannot show who prepared an order or when: no timeline, no export

Title: `A vendor cannot show who prepared an order or when: no timeline, no export` · Labels: ux-persona-test, P2, enhancement

**UXT-088 · P2 · major · CONFIRMED** — Food-safety due-diligence gap for an EHO visit.

**Steps**
1. As admin-user, confirm, start preparation, mark ready and complete an order
2. Compare the detail before and after
3. Look for an export or history control in Orders
**Expected:** A timestamped status history with the acting staff user, exportable for a records request
**Actual:** Only status and updatedAt change; createdAt is the only time shown. No actor fields, no export. The Envers audit (revinfo.user_id) is 'kept indefinitely' per /legal/retention, but AuditService is used nowhere
**Real-world impact:** A records request from an EHO cannot be answered without a database administrator.
**Found by:** P2-REG-03 (Bola(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/order-detail-before.json`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/order-detail-after.json`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/status-progression.txt`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/shots/21-vendor-order-detail.png` (+1 more in catalogue.json)
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

#### [UXT-089] 86ing an item or changing a price takes four taps and ~30 s in a long form

Title: `86ing an item or changing a price takes four taps and ~30 s in a long form` · Labels: ux-persona-test, P2, enhancement

**UXT-089 · P2 · minor · CONFIRMED** — Major friction during service.

**Steps**
1. Products > Edit (32x32 icon) > scroll to 16x16 'Available' checkbox > Update Product
**Expected:** One-tap sold-out toggle on the product row
**Actual:** 4 taps, ~30 s per change on a throttled phone (s4.log save timings); no quick toggle
**Real-world impact:** Items stay on sale after they run out during the rush
**Found by:** P2-FUN-11 (Funmi(P2))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/s4.log`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/v08-phone-edit-available.png`, `.planning/ux-persona-test-20261003-pass2/11-friday-rush-funmi/shots/v09-phone-edit-price.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

#### [UXT-090] Nothing asserts at deploy time that customer email verification is on; dev runs with it off

Title: `Nothing asserts at deploy time that customer email verification is on; dev runs with it off` · Labels: ux-persona-test, P2, tech-debt

**UXT-090 · P2 · minor · CONFIRMED** — A misconfigured environment would expose another person's order history via My Orders.

**Steps**
1. On a dev-profile stack, register a customer with someone else's email (no verification)
2. Open My Orders
**Expected:** Registration requires a verified email; a deploy-time check fails if require-verified-email=false outside dev
**Actual:** Another person's guest orders listed (dev profile only; prod default true)
**Real-world impact:** One wrong env var in any deployed environment exposes order histories.
**Found by:** J-04 (Jordan(P1))
**Suspected location (cited by tester, not verified):** core-java/src/main/resources/application.yml:281 (default true) vs application-dev.yml:36 (false) — coordinator-verified in pass 1
**Related to #462:** #462 (no verified contact channel).
**Evidence:** `.planning/ux-persona-test-20261003/04-hungry-customer-jordan/ (refs: 27, realm-export-customers.json, application.yml:281, application-dev.yml:36)`
**Proposed home:** Phase 29 – Deployable Staging, With Its Own Monitoring

#### [UXT-091] The basket shows no allergens and checkout's combined set has no per-item attribution

Title: `The basket shows no allergens and checkout's combined set has no per-item attribution` · Labels: ux-persona-test, P2, enhancement, compliance

**UXT-091 · P2 · minor · CONFIRMED** — Major friction for allergic customers deciding which item to drop.

**Steps**
1. Add two dishes with different allergens
2. Open the basket, then checkout
**Expected:** Allergens per line in basket and checkout
**Actual:** Basket: none; checkout: one combined set
**Real-world impact:** A parent cannot tell which dish to remove to make the order safe.
**Found by:** P05-6 (Priya(P1)), P05-10 (Priya(P1))
**Evidence:** `.planning/ux-persona-test-20261003/05-allergy-customer-priya/ (refs: 05, 07)`, `.planning/ux-persona-test-20261003/05-allergy-customer-priya/ (refs: 08)`
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-092] No 'may contain' field exists, label use-by is computed at download time, and records use US date format

Title: `No 'may contain' field exists, label use-by is computed at download time, and records use US date format` · Labels: ux-persona-test, P2, enhancement, compliance

**UXT-092 · P2 · minor · CONFIRMED** — Weak evidence and missing cross-contact information.

**Steps**
1. Open an order detail
2. Download a product label
**Expected:** UK date format; use-by from the production date; a cross-contact field
**Actual:** 'Oct 3, 2026, 11:10:25 PM'; 'Use by: 5 Oct 2026' computed when downloaded; no may-contain field
**Real-world impact:** Ambiguity in evidence and in labels.
**Found by:** P05-7 (Priya(P1)), P2-REG-14 (Bola(P2))
**Related to #427, #82:** #427 epic; #82 (PPDS label format, closed).
**Evidence:** `.planning/ux-persona-test-20261003/05-allergy-customer-priya/ (refs: payload keys)`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/txt_21-vendor-order-detail.txt`, `.planning/ux-persona-test-20261003-pass2/17-regulator-bola/shots/30-label-egusi-1.png`
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-093] There is no allergen filter, and searching 'peanut' or 'gluten free' returns nothing

Title: `There is no allergen filter, and searching 'peanut' or 'gluten free' returns nothing` · Labels: ux-persona-test, P2, enhancement

**UXT-093 · P2 · major · CONFIRMED** — Major friction for allergic customers.

**Steps**
1. Open /shop
2. Search 'peanut', 'gluten free', 'sesame'
**Expected:** Filter or exclude by allergen
**Actual:** Only cuisine chips; 'No kitchens found'
**Real-world impact:** Allergic customers must open every dish of every shop.
**Found by:** P05-3 (Priya(P1))
**Evidence:** `.planning/ux-persona-test-20261003/05-allergy-customer-priya/ (refs: 16)`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-095] The MCP server cannot be added to hosted ChatGPT/Claude connectors (no OAuth discovery, 5-minute tokens)

Title: `The MCP server cannot be added to hosted ChatGPT/Claude connectors (no OAuth discovery, 5-minute tokens)` · Labels: ux-persona-test, P2, enhancement

**UXT-095 · P2 · major · CONFIRMED** — Major gap for the agent channel.

**Steps**
1. POST /mcp without Authorization
2. GET /.well-known/oauth-protected-resource and /.well-known/oauth-authorization-server
3. Decode an admin-user token: exp - iat
**Expected:** MCP auth discovery (protected-resource metadata + WWW-Authenticate) so an assistant can run OAuth, or long-lived scoped tokens
**Actual:** 401 {error:missing_bearer_token} with no WWW-Authenticate header; both /.well-known paths 404; token lifetime 300 s
**Real-world impact:** Brief item 1 (ChatGPT/Claude takes orders) needs a custom token-refreshing proxy that the vendor must host.
**Found by:** P2-RAV-07 (Ravi(P2))
**Related to #203:** #203 built the MCP server.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/mcp-initialize.txt`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

#### [UXT-096] Order webhooks carry only ids and status, with no items, totals or customer

Title: `Order webhooks carry only ids and status, with no items, totals or customer` · Labels: ux-persona-test, P2, enhancement

**UXT-096 · P2 · major · CONFIRMED** — Major friction: receivers must call back for everything.

**Steps**
1. Read docs/webhooks.md test vector and OrderEventPublisher.OrderStateChangeEvent
2. Verify the vector with openssl
**Expected:** The order summary (lines, total, fulfilment, customer name) in the event, or a documented thin-event + fetch pattern with credentials the vendor can issue
**Actual:** data = orderId, tenantId, orderNumber, previousStatus, newStatus, timestamp, shopId
**Real-world impact:** Every row needs a call back to the API, which needs credentials (P2-RAV-01) and spends the shared rate budget.
**Found by:** P2-RAV-08 (Ravi(P2))
**Related to #205:** #205 built outbound webhooks.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/vec-body.json`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

#### [UXT-097] Vendor API and MCP orders accept products marked unavailable

Title: `Vendor API and MCP orders accept products marked unavailable` · Labels: ux-persona-test, P2, bug

**UXT-097 · P2 · major · CONFIRMED** — Major friction: the kitchen receives orders for sold-out items.

**Steps**
1. Create a product, then PUT it with available=false
2. POST /api/v1/orders with that productId (or MCP create_order)
3. POST /api/v1/orders/{id}/submit
**Expected:** A 409/422 typed error saying the product is unavailable (as on the storefront path)
**Actual:** 201 DRAFT order, then submit 200 → PENDING. OrderService.createOrder checks shop membership and stock count but never 'available'; PublicStorefrontService does.
**Real-world impact:** The vendor marks an item out of stock and the WhatsApp/AI bot keeps selling it. They end up with refunds, angry customers and cancelled orders at rush hour.
**Found by:** P2-RAV-02 (Ravi(P2))
**Suspected location (cited by tester, not verified):** OrderService.createOrder checks shop membership and stock but never 'available' (member cites)
**Evidence:** `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/rest-unavailable-order.json`, `.planning/ux-persona-test-20261003-pass2/18-integrator-ravi/mcp-edge-cases.txt`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

#### [UXT-099] With the API unreachable but the socket up, the board says 'Live' for ~50 s while dropping an order

Title: `With the API unreachable but the socket up, the board says 'Live' for ~50 s while dropping an order` · Labels: ux-persona-test, P3, bug

**UXT-099 · P3 · minor · CONFIRMED** — Minor: a narrow failure window.

**Steps**
1. Kitchen open; block HTTP to :9090 (socket untouched, navigator.onLine true)
2. Place+confirm an order
3. Watch the pill and board for 100 s, then restore
**Expected:** A frame announcing an order that can't be loaded flips the board to a warning at once
**Actual:** Socket frame advanced 'Last updated' and the pill stayed 'Live' while the /detail fetch failed silently; 'Orders are not refreshing' appeared only at ~58 s; order showed 8 s after the API returned
**Real-world impact:** On a flaky router the board looks healthy for a minute while an order is missing.
**Found by:** P2-TUN-09 (Tunde(P2))
**Related to #106:** #106 (KDS offline UX) handled hard offline only.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/s9-offline.out`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/18-api-down-100s.png`, `.planning/ux-persona-test-20261003-pass2/13-kitchen-staff-tunde/shots/19-api-back.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

#### [UXT-100] After access is removed the kitchen keeps showing that shop's tickets (with PII and live buttons) and blames the connection

Title: `After access is removed the kitchen keeps showing that shop's tickets (with PII and live buttons) and blames the connection` · Labels: ux-persona-test, P3, bug

**UXT-100 · P3 · minor · CONFIRMED** — Minor: stale PII stays on screen after revoke.

**Steps**
1. Manager has grants on Peckham and Lewisham, with the Peckham kitchen open
2. Owner revokes the Peckham grant
3. Owner creates a new Peckham order; manager clicks Start Preparing
**Expected:** The board clears and says access to this shop was removed
**Actual:** Access stops at once (new order detail 403, bump 403, refresh 403: good). But the 8 old tickets with customer names and allergens stay on screen with active buttons. The banner says 'Orders are not refreshing' / 'Could not fetch kitchen orders', and the indicator flips back to 'Live' at +78s.
**Real-world impact:** A moved manager keeps seeing customer data and assumes the Wi-Fi is down. Nobody tells him he no longer works that site.
**Found by:** P2-KEM-13 (Kemi(P2))
**Related to #627:** #627 (STOMP grant checked only at SUBSCRIBE); here the API correctly 403s but the screen is not cleared.
**Evidence:** `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/s15-revoke-live-scoped.txt`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/46-kitchen-bump-after-revoke-scoped.png`, `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/45-kitchen-30s-after-revoke-scoped.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

#### [UXT-102] A customer cannot cancel an order

Title: `A customer cannot cancel an order` · Labels: ux-persona-test, P3, enhancement

**UXT-102 · P3 · minor · CONFIRMED** — Minor gap.

**Steps**
1. Place an order, open My Orders / tracking
**Expected:** Cancel while PENDING
**Actual:** No cancel control
**Real-world impact:** Customers phone (and cannot, see seller contact) or no-show.
**Found by:** F-04 (Sam(P1))
**Evidence:** `.planning/ux-persona-test-20261003/06-impatient-privacy-sam/ (refs: 30, 34)`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-106] The basket is device-local: not restored on sign-in and not shared across devices

Title: `The basket is device-local: not restored on sign-in and not shared across devices` · Labels: ux-persona-test, P3, enhancement

**UXT-106 · P3 · minor · CONFIRMED** — Minor friction.

**Steps**
1. Signed in on the laptop, build a 40-item basket
2. Sign in to the same account on a phone
3. Open the same shop and the cart
**Expected:** Signed-in baskets sync across devices, or at least the phone says 'You have a basket on another device'
**Actual:** The phone shows 'Your basket is empty'. The basket lives only in the laptop's localStorage (jtoye-cart-<slug>, owner stamp = customer id).
**Real-world impact:** She rebuilds 20 lines on the phone, or walks back to her desk. This is an expectation gap, not a defect; most delivery apps sync signed-in baskets.
**Found by:** J-09 (Jordan(P1)), P2-CHA-06 (Nkechi(P2))
**Related to #459:** #459 (basket survives sign-out) fixed the opposite direction.
**Evidence:** `.planning/ux-persona-test-20261003/04-hungry-customer-jordan/ (refs: s16)`, `.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/02c-cart-20-lines.png`, `.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/03a-phone-shop-after-signin.png`, `.planning/ux-persona-test-20261003-pass2/19-real-life-chaos-nkechi/03b-phone-cart.png`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-108] Menu items cannot be customised (no modifiers)

Title: `Menu items cannot be customised (no modifiers)` · Labels: ux-persona-test, P3, enhancement

**UXT-108 · P3 · minor · CONFIRMED** — Minor gap versus aggregators.

**Steps**
1. Open any dish
**Expected:** Options/modifiers (spice, sides)
**Actual:** None
**Real-world impact:** Customers use notes or go elsewhere.
**Found by:** J-11 (Jordan(P1))
**Evidence:** `.planning/ux-persona-test-20261003/04-hungry-customer-jordan/ (refs: 07)`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

#### [UXT-113] There is no marketing-consent choice or preferences page, and /unsubscribe says 'contact the vendor'

Title: `There is no marketing-consent choice or preferences page, and /unsubscribe says 'contact the vendor'` · Labels: ux-persona-test, P3, enhancement

**UXT-113 · P3 · minor · CONFIRMED** — Minor transparency gap (no marketing is sent).

**Steps**
1. Register and order
2. Look for email preferences
3. Read the privacy notice
4. Open /unsubscribe
**Expected:** A clear statement (e.g. 'we only email about your orders') plus a preferences link
**Actual:** No consent question anywhere; no preferences page; the privacy notice has no marketing section; /unsubscribe says 'Contact the vendor', and shops publish no contact. No marketing was received (good)
**Real-world impact:** When marketing goes live, there is no customer-facing opt-in or opt-out surface
**Found by:** P2-GRA-15 (Grace(P2))
**Related to #592:** #592 (one-click unsubscribe unwired in k8s).
**Evidence:** `.planning/ux-persona-test-20261003-pass2/14-regular-grace/35-unsubscribe-page.png`, `.planning/ux-persona-test-20261003-pass2/14-regular-grace/privacy-notice.txt`
**Proposed home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

#### [UXT-118] Another shop's order returns 403 not 404, and public image URLs embed the tenant UUID

Title: `Another shop's order returns 403 not 404, and public image URLs embed the tenant UUID` · Labels: ux-persona-test, P3, bug, security

**UXT-118 · P3 · minor · CONFIRMED** — Minor information disclosure (existence/ownership mapping).

**Steps**
1. As tenant-b-user scoped to Peckham, GET a Lewisham order id
**Expected:** 404, as cross-tenant reads do
**Actual:** 403 'Shop Access Denied', which confirms the id exists (within the same tenant only).
**Real-world impact:** Low: tells a scoped manager which order ids exist at other sites.
**Found by:** P2-KEM-19 (Kemi(P2)), F5 (Dele(P1))
**Evidence:** `.planning/ux-persona-test-20261003-pass2/12-multishop-owner-kemi/30-bayo-bola-probe.txt`, `.planning/ux-persona-test-20261003/07-curious-rival-tenant-dele/ (refs: probe_public.py output)`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-D Abuse resistance

#### [UXT-120] No email campaigns, loyalty or vouchers

Title: `No email campaigns, loyalty or vouchers` · Labels: ux-persona-test, P3, enhancement

**UXT-120 · P3 · polish · CONFIRMED** — Minor gap versus competitors.

**Steps**
1. Open /dashboard/marketing
**Expected:** Campaigns, loyalty, vouchers
**Actual:** Promotions and announcements only
**Real-world impact:** Weaker retention offer.
**Found by:** C-13 (Claire(P1))
**Evidence:** `.planning/ux-persona-test-20261003/08-sceptical-evaluator-claire/ (refs: dtxt_dashboard_marketing.txt)`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings)

#### [UXT-121] No custom storefront domain (SUSPECTED)

Title: `No custom storefront domain (SUSPECTED)` · Labels: ux-persona-test, P3, enhancement

**UXT-121 · P3 · polish · SUSPECTED** — Minor gap.

**Steps**
1. Look for a domain setting in shop edit
**Expected:** Custom domain
**Actual:** Only /shop/slug
**Real-world impact:** Brand-conscious vendors hesitate.
**Found by:** C-14 (Claire(P1))
**Evidence:** `.planning/ux-persona-test-20261003/08-sceptical-evaluator-claire/ (refs: dtxt_dashboard_shops.txt)`
**Proposed home:** NEW: Phase 37 – Real-world operations readiness (persona findings)

## C. P3 polish bundles (one issue per surface)

#### Polish bundle: dashboard-polish

Title: `Persona polish (dashboard-polish): 4 small defects` · Labels: ux-persona-test, P3, bug, security

- [ ] **UXT-098** Scoped users see nav items they cannot use, and a 403 renders as 'No endpoints yet' / 'storefront is live' — All nav items are shown. Webhooks gets a 403 but renders 'No webhook endpoints yet'. Go live tells the manager 'Your storefront is live' for a Draft shop. (P2-KEM-16) · related #762
- [ ] **UXT-101** The orders table is clipped on tablet and the phone's first screen is an explainer, not orders — 768px: the sidebar takes about a third of the width and Total/Actions are cut off (inner horizontal scroll). 390px: the full first screen is the 'Order Status Flow' card. The table is 773px wide in a 308px container. No … (P2-KEM-17) · related #699
- [ ] **UXT-115** SKU is required when adding a product (jargon for a stall holder) — Required (ADE-12)
- [ ] **UXT-117** Staff list masks emails so same-domain staff are indistinguishable; products table has no Shop column — Two staff on the same email domain would be indistinguishable apart from display name. The products list has no shop column, so with 'All shops' selected there is no way to tell sites apart. (P2-KEM-18)

#### Polish bundle: checkout-polish

Title: `Persona polish (checkout-polish): 3 small defects` · Labels: ux-persona-test, P3, bug

- [ ] **UXT-103** A network failure at Place order says only 'Failed to place order. Please try again.' — Generic failure text (F-06)
- [ ] **UXT-104** Back then Forward wipes the checkout address, phone and notes — Basket kept, address/phone/notes wiped (F-08)
- [ ] **UXT-105** Empty checkout submit skips the address and takes two rounds, with no error summary — Round 1: the native browser bubble "Please fill out this field." on Full name, because only name, email and phone carry `required`. Round 2: focus moves to Address line 1 (aria-invalid, with an aria-describedby error, wh… (P2-MAR-10)

#### Polish bundle: storefront-polish

Title: `Persona polish (storefront-polish): 6 small defects` · Labels: ux-persona-test, P3, bug, enhancement

- [ ] **UXT-107** Sign-out is a tiny unlabelled one-tap icon next to a person icon that does nothing — Decorative icon that looks like an account button; 18px unlabelled one-tap sign-out (J-10, P2-GRA-12)
- [ ] **UXT-109** The shop list uses gradients and initials instead of food photos — Gradient + initials (J-12) · related #546
- [ ] **UXT-110** Storefront copy noise: duplicated dishes and alt text, descriptions repeating names, 'Co..', 'Draft' filter, status naming mismatch — Product images use alt equal to the dish name right next to the same H3, so it is read twice. The dialog image is "Beef Suya Wrap - image 1". Popular dishes repeat under their category. The basket goes H1 → H3 (axe headi… (J-13, P2-MAR-16, F-09)
- [ ] **UXT-111** React hydration error #418 on /shop/orders and /track, and 'Auto-refreshing' on finished orders — Minified React error #418; 'Auto-refreshing' shown for COMPLETED (J-14, P2-GRA-20)
- [ ] **UXT-112** Postcode search cannot tell invalid, out-of-area and no-kitchens apart, and its result count may not be announced — The <p aria-live=polite> "3 kitchens within 3.1 miles of SW9 8PR …" is added to the DOM already containing its text. Many screen readers ignore that. Not verified with real NVDA. (J-08, P2-MAR-18) · related #619, #460
- [ ] **UXT-116** An unknown shop link returns a soft 404 (HTTP 200 'Shop not found') — HTTP 200 'Shop not found' (ADE-17)

#### Polish bundle: public-content

Title: `Persona polish (public-content): 2 small defects` · Labels: ux-persona-test, P3, compliance

- [ ] **UXT-114** The accessibility statement is stale (claims no skip link, cites WCAG 2.1, excludes basket/confirmation/tracking) — It lists "No skip to content link on the vendor and checkout pages", but a working skip link exists on /, /shop, the menu and checkout. The scope lists checkout but not /cart, the confirmation or /shop/{slug}/orders and … (P2-MAR-15)
- [ ] **UXT-119** Public pages: no vendor-to-vendor confidentiality statement; positioning excludes non-London, non-West-African operators — None; 'ONE LONDON CLUSTER… Nigerian and West African' (F6, C-08)

#### Polish bundle: integrator-polish

Title: `Persona polish (integrator-polish): 2 small defects` · Labels: ux-persona-test, P3, bug

- [ ] **UXT-122** Webhooks have no test event, no request/response body, no delete, and do not follow redirects (Apps Script fails) — No test button (row actions: View, Pause, menu). The log shows type, status, HTTP code and attempts only. DELETE → 405, so REVOKED rows accumulate (another persona's revoked endpoint is also listed). (P2-RAV-11, P2-RAV-09)
- [ ] **UXT-123** API/MCP contract polish: MCP drops typed error details, OpenAPI hygiene problems, replay indistinguishable from fresh — tokenUrl http://keycloak:8080/… (internal host); 117/140 ops declare '*/*'; 429 documented on 1/140; ApplicationContext/BeanFactory/ServletContext/Environment schemas; 'pageable' object query param; the edge returns proc… (P2-RAV-13, P2-RAV-15, P2-RAV-17)

## D. Epic tracking issue

Title: `[UXT-EPIC] Persona user-testing 2026-10-03: real-world operations readiness (123 clusters)` · Labels: ux-persona-test, enhancement

Body:

```markdown
Two passes of persona user-testing (pass 1: 6 personas; pass 2: 9 personas) produced 250 findings (76 pass-1 rows, 173 pass-2 entries, 1 coordinator observation). Deduplicated into 123 clusters plus 40 positives kept as regression guards. Full catalogue: `.planning/ux-persona-test-20261003-pass2/consolidated/CATALOGUE.md`.

Counts: P0 19 · P1 29 · P2 49 · P3 26. Proposed home for most: NEW Phase 37 – Real-world operations readiness (sub-themes 37-A..37-G); legal/allergen/privacy gaps reopen Phase 31 as gap-closure; money items go to Phase 30.

### P0 (fix before any real tenant)
- [ ] UXT-001 DSAR erasure is marked completed while nothing is erased for storefront customers — #TBD
- [ ] UXT-002 A verified DSAR access request is never fulfilled (ACCESS delivery not implemented) — #TBD
- [ ] UXT-003 Revoking a manager's last shop grant silently makes him tenant-wide Group admin — #TBD
- [ ] UXT-004 Every staff login is a tenant-wide Group admin by default (JIT provisioning, strict-scoping off) — #TBD
- [ ] UXT-005 A buyer of one shop can publish a 5-star review on a different shop of the same tenant — #TBD
- [ ] UXT-006 The public rate limiter trusts any X-Forwarded-For value, so rotating it defeats the limit — #TBD
- [ ] UXT-007 Checkout never re-validates the stored basket: stale prices, removed and sold-out items surface only as a charge or a bare error — #TBD
- [ ] UXT-008 An advertised '20% OFF' promotion is displayed on the storefront but never applied to the order — #TBD
- [ ] UXT-009 The customer's allergen acknowledgement is never sent to or stored by the server — #TBD
- [ ] UXT-010 The customer never sees the allergen set recorded on their order, and it can differ from what they acknowledged — #TBD
- [ ] UXT-011 The kitchen screen ticket hides the customer's note (e.g. 'severe peanut allergy') and the fulfilment type — #TBD
- [ ] UXT-012 A product whose ingredients name an allergen (e.g. 'butter (MILK)') with no box ticked saves and shows as 'No allergens' — #TBD
- [ ] UXT-013 Shops go live with a failed FSA match, self-approved by the vendor, while the site claims 'UK food-hygiene verified' — #TBD
- [ ] UXT-014 Customers are never given the seller's legal identity or any way to contact the shop — #TBD
- [ ] UXT-015 Stripe JS and fraud cookies load on a cash-only checkout, contradicting the cookie policy — #TBD
- [ ] UXT-016 Updating a product without quantityInStock silently turns stock tracking off — #TBD
- [ ] UXT-017 Products created by /sync/batch belong to no shop, are orderable at any shop, and record the wrong allergens — #727 (comment)
- [ ] UXT-018 A completed, paid order can be deleted, leaving ledger rows that point at nothing — #TBD
- [ ] UXT-019 The platform's own registered office is not published anywhere on the site — #TBD
### P1
- [ ] UXT-020 A new order makes no sound and never reaches the kitchen screen until someone confirms it on another page — #TBD
- [ ] UXT-021 When the all-day kitchen tablet's session lapses the board silently becomes a sign-in page — #TBD
- [ ] UXT-022 A vendor cannot pause or stop taking orders: no pause switch, no holiday closure, and free-text hours fail open and cannot be cleared — #TBD
- [ ] UXT-023 Vendors cannot set a delivery fee, free-delivery threshold or collection-only: orders are charged £0 delivery and collection-only shops take deliveries — #TBD
- [ ] UXT-024 A second or third shop can never go live, while onboarding says 'Your storefront is live' — #452 (comment)
- [ ] UXT-025 There is no way to invite a staff member: they must self-register, then auto-become Group admin — #452 (comment)
- [ ] UXT-026 CSV import ignores the selected shop and hides imported items from every storefront; a menu cannot be copied to another site — #TBD
- [ ] UXT-027 Per-shop dashboard and finance show the whole business's takings, and site managers get 'No financial data yet' instead of their shop's numbers — #TBD
- [ ] UXT-028 Every shop update regenerates the public URL slug, so shared links and QR codes break — #TBD
- [ ] UXT-029 The order confirmation is rendered in place at /checkout: off-screen, unannounced, and lost on refresh — #TBD
- [ ] UXT-030 Delivery is accepted to any UK postcode with no radius check at checkout — #460 (comment)
- [ ] UXT-031 A prospective vendor has no way in: 'Start your application' dead-ends at a login, and no sales or support contact exists — #TBD
- [ ] UXT-032 No merchant terms, pricing page, VAT basis or card-fee figure: /legal/terms, /pricing, /contact and /about all 404 — #TBD
- [ ] UXT-033 The published £39/location subscription has no billing built and no billing UI — #102 (comment)
- [ ] UXT-034 Vendors have no payments or payouts surface (/dashboard/payments returns 404) — #102 (comment)
- [ ] UXT-035 A vendor has no way to give a developer API credentials; the only path is the owner's password plus the confidential core-api client secret — #TBD
- [ ] UXT-036 An order taken by an AI agent through MCP stays DRAFT and the kitchen never sees it — #TBD
- [ ] UXT-037 /sync/batch has no stock or availability field and silently skips unknown items while reporting SUCCESS — #TBD
- [ ] UXT-038 Webhook endpoints auto-pause after ~46 s of failures and retries stop after 5 attempts, with nobody told — #587 (comment)
- [ ] UXT-039 Anonymous traffic can exhaust the edge gateway's single process-wide rate limit and block every vendor's sync; its 429 is untyped — #TBD
- [ ] UXT-040 Asked for one shop's menu, an AI agent gets every product in the business with allergens only as an integer — #TBD
- [ ] UXT-041 Anyone can place many fake cash orders in seconds with throwaway contact details — #TBD
- [ ] UXT-042 Item quantity has no upper bound: a £19 billion order is accepted and shown on the dashboard — #TBD
- [ ] UXT-043 An allergy request is a generic free-text note: no alert to the vendor, no acknowledgement, never echoed to the customer — #TBD
- [ ] UXT-044 Reviews publish the reviewer's full checkout name with no notice, policy or moderation — #TBD
- [ ] UXT-045 The local compose stack can never exercise the card-payment path (Stripe env-var names and build-time key mismatch) — #TBD
- [ ] UXT-046 Products have no VAT-rate choice; everything is booked as Standard 20% — #TBD
- [ ] UXT-047 'VAT (incl. 20%)' is shown for every vendor, with no VAT-registration status or number captured — #TBD
- [ ] UXT-048 Menu cards show allergens as an unnamed count, 'Add' works without seeing them, and 'none declared' is never stated — #TBD
### P2
- [ ] UXT-049 A double-tap on 'Start Preparing' jumps the order to READY and emails the customer 'Ready!' with no undo — #TBD
- [ ] UXT-050 Kitchen mute survives sign-out and the next person's icon shows sound on while new orders are silent — #TBD
- [ ] UXT-051 Cancelling an order is one unconfirmed tap with no reason, next to Confirm on small phone buttons — #TBD
- [ ] UXT-052 Unanswered orders stay Pending forever and the customer is never told — #TBD
- [ ] UXT-053 Orders and Customers have no search, so an order cannot be found by customer name at the counter — #TBD
- [ ] UXT-054 Every kitchen ticket cuts the order number to 'ORD-…', so tickets look identical — #TBD
- [ ] UXT-055 The kitchen board is not built for a wall tablet: wasted space, small text, newest-first ordering and very tall tickets — #TBD
- [ ] UXT-056 No ready-by or delivery time exists anywhere: tickets, confirmation, tracking or emails — #TBD
- [ ] UXT-057 Order confirmation and every status email omit the shop, items, total and address, and come from inconsistent senders — #TBD
- [ ] UXT-058 Finance has no 'today', no date range, no export and no cash/card split — #TBD
- [ ] UXT-059 A completed cash order stays 'Payment status NONE / Unpaid' while counted as revenue — #TBD
- [ ] UXT-060 Cash-only is revealed only at the bottom of checkout, and the Place order button shows a card icon — #TBD
- [ ] UXT-061 Menu 'Add' buttons are visible but ignore taps until hydration (4.2 s on 4G, 14 s on Slow 3G) — #TBD
- [ ] UXT-062 Saving a shop overwrites its banner with the logo URL — #TBD
- [ ] UXT-063 Products cannot be deleted once they have a photo or any order (even cancelled): misleading 409 — #TBD
- [ ] UXT-064 Deleting a shop orphans its products, and editing an orphan silently moves it to 'All Shops' — #TBD
- [ ] UXT-065 The image dialog stays on 'Processing…' although the server has the image ACTIVE within ~6 s — #TBD
- [ ] UXT-066 The 'Publish to storefront' checkbox is ignored, or throws away the whole shop edit with a raw developer error — #TBD
- [ ] UXT-067 Internal strategy pages are public, expose a repo path, and contradict the landing page (incl. a 'payouts: Full' claim) — #TBD
- [ ] UXT-068 Test and demo data is visible to customers and vendors (E2E 20% OFF promo, weeks-old test orders, a real person's name and email) — #TBD
- [ ] UXT-069 There is no account page: data access and erasure are mailto-only although a backend intake exists — #TBD
- [ ] UXT-070 The DSAR confirmation link does not open on compose and shows raw JSON when it does — #TBD
- [ ] UXT-071 The cookie policy omits keys that hold the customer's email and id and survive sign-out — #TBD
- [ ] UXT-072 Order contact details are barely validated, and API/MCP orders need no customer contact at all — #TBD
- [ ] UXT-073 Vendors have no bulk-reject and no fraud signal for junk orders — #TBD
- [ ] UXT-074 On a slow network, returning after 5 minutes signs the customer out (parallel refreshes burn the single-use token) — #TBD
- [ ] UXT-075 Customers cannot order ahead for a time, and a closed shop takes no pre-orders — #TBD
- [ ] UXT-076 No 'order again' and no remembered address: a weekly order takes 10 taps and 85 keystrokes — #TBD
- [ ] UXT-077 Customers have no way to leave a review; the only path is an unauthenticated API call with an email in the URL — #TBD
- [ ] UXT-078 Password-reset links expire after 5 minutes — #TBD
- [ ] UXT-079 Keycloak sign-in and registration fall below the storefront's accessibility bar and hide password rules until failure — #TBD
- [ ] UXT-080 At large text sizes the basket, tracker and shop header truncate or overflow — #TBD
- [ ] UXT-081 Tracking pages are silent and unlabelled for screen readers (status changes, current step, copy button, lookup result, field name, contrast) — #TBD
- [ ] UXT-082 Shop cards on the kitchen list have no accessible name ('link, link, link') — #TBD
- [ ] UXT-083 Pressing Add/Remove drops focus to the page body and the basket announcement doubles the count without naming the item — #TBD
- [ ] UXT-084 Basket, checkout, confirmation and tracking all share the title 'J'Toye — Discover Local Vendors' — #TBD
- [ ] UXT-085 On mobile the cookie banner covers content and keyboard focus and is the 32nd tab stop — #TBD
- [ ] UXT-086 A delivery customer's tracking page says 'Ready for collection' — #TBD
- [ ] UXT-087 A kitchen hand sees far more customer personal data than needed to cook — #TBD
- [ ] UXT-088 A vendor cannot show who prepared an order or when: no timeline, no export — #TBD
- [ ] UXT-089 86ing an item or changing a price takes four taps and ~30 s in a long form — #TBD
- [ ] UXT-090 Nothing asserts at deploy time that customer email verification is on; dev runs with it off — #TBD
- [ ] UXT-091 The basket shows no allergens and checkout's combined set has no per-item attribution — #TBD
- [ ] UXT-092 No 'may contain' field exists, label use-by is computed at download time, and records use US date format — #TBD
- [ ] UXT-093 There is no allergen filter, and searching 'peanut' or 'gluten free' returns nothing — #TBD
- [ ] UXT-094 A vendor cannot connect their own WhatsApp; WhatsApp ordering is one platform-wide setting — #208 (comment)
- [ ] UXT-095 The MCP server cannot be added to hosted ChatGPT/Claude connectors (no OAuth discovery, 5-minute tokens) — #TBD
- [ ] UXT-096 Order webhooks carry only ids and status, with no items, totals or customer — #TBD
- [ ] UXT-097 Vendor API and MCP orders accept products marked unavailable — #TBD
### P3
- [ ] UXT-099 With the API unreachable but the socket up, the board says 'Live' for ~50 s while dropping an order — #TBD
- [ ] UXT-100 After access is removed the kitchen keeps showing that shop's tickets (with PII and live buttons) and blames the connection — #TBD
- [ ] UXT-102 A customer cannot cancel an order — #TBD
- [ ] UXT-106 The basket is device-local: not restored on sign-in and not shared across devices — #TBD
- [ ] UXT-108 Menu items cannot be customised (no modifiers) — #TBD
- [ ] UXT-113 There is no marketing-consent choice or preferences page, and /unsubscribe says 'contact the vendor' — #TBD
- [ ] UXT-118 Another shop's order returns 403 not 404, and public image URLs embed the tenant UUID — #TBD
- [ ] UXT-120 No email campaigns, loyalty or vouchers — #TBD
- [ ] UXT-121 No custom storefront domain (SUSPECTED) — #TBD
- [ ] Polish bundle dashboard-polish: UXT-098, UXT-101, UXT-115, UXT-117 — #TBD
- [ ] Polish bundle checkout-polish: UXT-103, UXT-104, UXT-105 — #TBD
- [ ] Polish bundle storefront-polish: UXT-107, UXT-109, UXT-110, UXT-111, UXT-112, UXT-116 — #TBD
- [ ] Polish bundle public-content: UXT-114, UXT-119 — #TBD
- [ ] Polish bundle integrator-polish: UXT-122, UXT-123 — #TBD

### Systemic themes (root causes behind many clusters)
- The browser's copy of the order is trusted at the moment of commitment (UXT-007, UXT-010, UXT-009, UXT-008, UXT-029)
- 'Say ≠ data': the UI asserts things the data does not back (UXT-013, UXT-024, UXT-027, UXT-098, UXT-023, UXT-086, UXT-057, UXT-015, UXT-071, UXT-114, UXT-099, UXT-003, UXT-001, UXT-067, UXT-063)
- Implicit tenant-wide admin is the default identity (UXT-004, UXT-003, UXT-025, UXT-087, UXT-098, UXT-117)
- No operator, no human channel (UXT-013, UXT-031, UXT-014, UXT-002, UXT-035, UXT-094, UXT-077, UXT-068, UXT-032)
- The order lifecycle has no PENDING→kitchen bridge and no time model (UXT-020, UXT-036, UXT-052, UXT-056, UXT-075, UXT-022, UXT-021, UXT-050, UXT-049)
- Validation lives in one entry point, not in the domain (UXT-097, UXT-072, UXT-042, UXT-017, UXT-037, UXT-005, UXT-026, UXT-064, UXT-016, UXT-009)
- The customer leaves with no durable record (UXT-057, UXT-010, UXT-029, UXT-056, UXT-014, UXT-069)
- Local and dev settings hide production defects (UXT-045, UXT-070, UXT-090, UXT-068)

### Comments posted on existing issues
- #102: UXT-033, UXT-034
- #208: UXT-094
- #452: UXT-024, UXT-025
- #460: UXT-030
- #587: UXT-038
- #727: UXT-017
```

## Coordinator adjustments

Applied 2026-10-04 before filing; also recorded per cluster as `coordinatorAdjustment` in `catalogue.json`. These supersede the priorities and labels in sections B and D above.

- **UXT-008 P0 → P1.** The '20% OFF' promotion the testers saw is E2E seed data; the real defect is that promotions are display-only and never applied to an order.
- **UXT-015 P0 → P1.** A PECR cookie-consent issue (Stripe JS and fraud cookies on a cash-only checkout), not direct harm to a user.
- **UXT-019 P0 → P1.** A trading-disclosure gap for the platform company, possibly fixable by configuration (`NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` is declared but set in no runtime).
- **#727 (UXT-017) P3 → P0.** Comment posted AND the priority label changed from `P3` to `P0`: pass 2 reproduced the sync orphan live and showed a wrong-allergen safety consequence (an order line for the orphan records allergenMask 0 where the real dish declares 256). It is no longer a latent data-hygiene defect.
- **Other "same" matches (#102, #208, #452, #460, #587):** the `ux-persona-test` label is added and the comment posted; their other labels are unchanged.
- **Type labels:** `bug` for bug, `enhancement` for gap/enhancement and env/tooling, `compliance` for content/legal. `tech-debt` (proposed in section B for UXT-045, UXT-068, UXT-090) is replaced by `enhancement`.
- **Regression-of-closed (UXT-001 → #84, UXT-006 → #88, UXT-074 → #465):** filed as new issues titled "Regression of #N: …"; the closed issue gets a one-line pointer comment and is not reopened.
- **Epic title:** "[UXT-EPIC] Persona user-testing 2026-10-03: real-world readiness findings (pass 1 + 2)".
- **Resulting counts:** P0 16 · P1 32 · P2 49 · P3 26 (was 19 / 29 / 49 / 26).
- **Filing ledger:** `filed-issues.json` (clusterIds, title, number, url) is the record of what was filed.
