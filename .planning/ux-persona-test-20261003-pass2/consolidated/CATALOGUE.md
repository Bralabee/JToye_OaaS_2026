# Persona user-testing — consolidated finding catalogue (passes 1 + 2, 2026-10-03)

Built 2026-10-04 by `consolidate.cjs` from the two passes' reports and findings files; machine-readable form in `catalogue.json`, pass-1 rows normalised in `pass1-findings.json`, positives in `goods-to-preserve.md`, filing plan in `issue-plan.md`. Issue tracker matched read-only against 217 issues (open + closed), fetched once.

## Reconciliation (every input lands exactly once)

| Input | Count |
|---|---:|
| pass-1 table rows — 01-new-vendor-ade | 17 |
| pass-1 table rows — 04-hungry-customer-jordan | 15 |
| pass-1 table rows — 05-allergy-customer-priya | 12 |
| pass-1 table rows — 06-impatient-privacy-sam | 11 |
| pass-1 table rows — 07-curious-rival-tenant-dele | 6 |
| pass-1 table rows — 08-sceptical-evaluator-claire | 15 |
| **pass-1 total** (personas 02, 03 have no report) | **76** |
| pass-2 findings.json — 11-friday-rush-funmi | 20 |
| pass-2 findings.json — 12-multishop-owner-kemi | 22 |
| pass-2 findings.json — 13-kitchen-staff-tunde | 15 |
| pass-2 findings.json — 14-regular-grace | 24 |
| pass-2 findings.json — 15-screenreader-marcus | 21 |
| pass-2 findings.json — 16-prankster-kyle | 12 |
| pass-2 findings.json — 17-regulator-bola | 18 |
| pass-2 findings.json — 18-integrator-ravi | 22 |
| pass-2 findings.json — 19-real-life-chaos-nkechi | 19 |
| **pass-2 total** | **173** |
| coordinator (COORD-01) | 1 |
| **All inputs** | **250** |

| Placed in | Count |
|---|---:|
| cluster memberships (123 clusters) | 210 |
| goods-to-preserve (positives: 4 pass-1 + 36 pass-2) | 40 |
| **Total placed** | **250** |

Check: 210 + 40 = 250 = 76 + 173 + 1. The build script fails (exit 1) if any input id is placed 0 or 2+ times, if a positive lands in a cluster, or if a non-positive lands in goods. Re-verify with: `jq '[.[].members|length]|add' catalogue.json` and `jq length pass1-findings.json`.

## Counts

**By priority:** P0 19 · P1 29 · P2 49 · P3 26 (clusters).

Priority rule: P0 = in production would cause legal breach, user-safety harm, privilege escalation, data-protection failure or silent data corruption (incl. price charged ≠ price shown); P1 = blocks a core vendor/customer journey; P2 = major friction/trust; P3 = minor/polish.

| Type | n |
|---|---:|
| bug | 76 |
| gap/enhancement | 35 |
| content/legal | 9 |
| env/tooling | 3 |

| Area | n |
|---|---:|
| orders | 12 |
| kitchen | 10 |
| checkout | 9 |
| allergens | 8 |
| catalogue | 8 |
| finance | 8 |
| privacy | 8 |
| accessibility | 7 |
| accounts | 6 |
| api-mcp | 6 |
| legal-compliance | 6 |
| content | 5 |
| vendor-onboarding | 5 |
| staff-scoping | 4 |
| storefront | 4 |
| discovery | 3 |
| tracking | 3 |
| webhooks | 3 |
| media | 2 |
| notifications | 2 |
| performance | 2 |
| security | 2 |

| Proposed home | n |
|---|---:|
| 37-C Checkout integrity & customer trust/retention | 23 |
| Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) | 17 |
| 37-A Kitchen & order operations | 15 |
| 37-E Integrator surface (API, MCP, webhooks, sync) | 13 |
| 37-B Multi-site, staff access & finance | 12 |
| 37-D Abuse resistance | 8 |
| 37-G Catalogue & shop-admin correctness | 8 |
| Phase 33 – The Consumer Product (CUST-02/CUST-04 still open) | 7 |
| 37-F Accessibility | 6 |
| Phase 30 – The Money Path, Executed | 5 |
| Phase 32 – Production Cutover + First Tenant | 5 |
| NEW: Phase 37 – Real-world operations readiness (persona findings) | 2 |
| Phase 29 – Deployable Staging, With Its Own Monitoring | 1 |
| Phase 34 – Rendering + Test Truthfulness (complete: #507 remainder) | 1 |

**Existing-issue matches:** same 8 · related 36 · regression-of-closed 3 · none 76.

## Cross-cutting themes (systemic root causes)

1. **The browser's copy of the order is trusted at the moment of commitment** — Price, availability and the allergen panel are captured into localStorage or on mount and never re-checked; the server then silently reprices, refuses one line at a time, or records a different allergen set, and the acknowledgement itself never leaves the browser. One server-quoted 'review your order' step (price, availability, allergens, minimum, promotion) with the quote id carried on submit fixes the family. Clusters: UXT-007, UXT-010, UXT-009, UXT-008, UXT-029.

2. **'Say ≠ data': the UI asserts things the data does not back** — 'UK food-hygiene verified', 'Your storefront is live', 'scoped to this shop', 'No financial data yet' (really 403), '£2.99 delivery' (charged £0), 'Ready for collection' (delivery), 'shop named on your confirmation', 'no cookies until payment', 'Live', 'no row' on Staff (really Group admin), DSAR 'completed' (nothing erased), 'payouts: Full', 'referenced by an existing order'. Each is a separate bug; the systemic fix is a review rule that every status claim reads from the same source the action uses, plus a test that asserts the data, not the rendered string. Clusters: UXT-013, UXT-024, UXT-027, UXT-098, UXT-023, UXT-086, UXT-057, UXT-015, UXT-071, UXT-114, UXT-099, UXT-003, UXT-001, UXT-067, UXT-063.

3. **Implicit tenant-wide admin is the default identity** — With strict-scoping off, any ungranted tenant user is GROUP_ADMIN; revoking the last grant re-creates that state; the only staff onboarding is self-registration. Flip the default (ungranted = no access, explicit invite carries the grant) and most staff-scoping clusters collapse. Clusters: UXT-004, UXT-003, UXT-025, UXT-087, UXT-098, UXT-117.

4. **No operator, no human channel** — The deliberate no-platform-operator decision leaves every two-actor step with nobody on the other side: FSA manual review is self-approved, prospects and customers have no one to contact, DSAR access has no fulfiller, API credentials and WhatsApp numbers exist only as realm-import or env config, test reviews and ledger rows cannot be removed. Either name an operator role (support/compliance) or design each step to be completable by the vendor with evidence. Clusters: UXT-013, UXT-031, UXT-014, UXT-002, UXT-035, UXT-094, UXT-077, UXT-068, UXT-032.

5. **The order lifecycle has no PENDING→kitchen bridge and no time model** — New orders wait as PENDING on a page the kitchen does not watch, with no alert; MCP orders stay DRAFT; nothing expires an unanswered order; there is no promised time, no scheduled time and no 'pause'. A single state-and-time model for the kitchen (arrival alert, accept-with-ETA, auto-reject timeout, pause) addresses the cluster. Clusters: UXT-020, UXT-036, UXT-052, UXT-056, UXT-075, UXT-022, UXT-021, UXT-050, UXT-049.

6. **Validation lives in one entry point, not in the domain** — The storefront path checks availability, contact details and the allergen gate; the vendor API, MCP, sync and CSV import do not, and 'no shop' products mean 'all shops' in the spec, 'no storefront' in the UI and 'any order' in the API. Move each invariant into the domain service every writer calls. Clusters: UXT-097, UXT-072, UXT-042, UXT-017, UXT-037, UXT-005, UXT-026, UXT-064, UXT-016, UXT-009.

7. **The customer leaves with no durable record** — Confirmation lives only in component state at /checkout; emails carry no shop, items, total or allergens; tracking has no items or time; there is no account page. After a silent price change or an allergen dispute the customer has nothing in writing. Clusters: UXT-057, UXT-010, UXT-029, UXT-056, UXT-014, UXT-069.

8. **Local and dev settings hide production defects** — Compose drops the Stripe keys (wrong var name, no build ARG), so every local and persona run is cash-only; DSAR verify links point at an unset base URL; dev disables email verification; seed/E2E data shows on public pages. The money path and DSAR confirmation have never been exercised locally. Clusters: UXT-045, UXT-070, UXT-090, UXT-068.

## All clusters

| ID | P | Sev | Title | Personas | Existing match | Proposed home |
|---|---|---|---|---|---|---|
| UXT-001 | P0 | blocker | DSAR erasure is marked completed while nothing is erased for storefront customers | Grace(P2) | regression-of-closed #84 | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-002 | P0 | major | A verified DSAR access request is never fulfilled (ACCESS delivery not implemented) | Grace(P2) | none | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-003 | P0 | blocker | Revoking a manager's last shop grant silently makes him tenant-wide Group admin | Kemi(P2) | related #285, #499 | 37-B Multi-site, staff access & finance |
| UXT-004 | P0 | major | Every staff login is a tenant-wide Group admin by default (JIT provisioning, strict-scoping off) | Ade(P1), Kemi(P2), Tunde(P2) | related #285 | 37-B Multi-site, staff access & finance |
| UXT-005 | P0 | major | A buyer of one shop can publish a 5-star review on a different shop of the same tenant | Bola(P2) | none | 37-D Abuse resistance |
| UXT-006 | P0 | major | The public rate limiter trusts any X-Forwarded-For value, so rotating it defeats the limit | Kyle(P2) | regression-of-closed #88 | 37-D Abuse resistance |
| UXT-007 | P0 | major | Checkout never re-validates the stored basket: stale prices, removed and sold-out items surface only as a charge or a bare error | Sam(P1), Funmi(P2), Nkechi(P2) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-008 | P0 | major | An advertised '20% OFF' promotion is displayed on the storefront but never applied to the order | Kyle(P2), Bola(P2) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-009 | P0 | major | The customer's allergen acknowledgement is never sent to or stored by the server | Bola(P2) | related #427 | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-010 | P0 | major | The customer never sees the allergen set recorded on their order, and it can differ from what they acknowledged | Priya(P1), Bola(P2), Nkechi(P2) | related #427 | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-011 | P0 | major | The kitchen screen ticket hides the customer's note (e.g. 'severe peanut allergy') and the fulfilment type | Funmi(P2), Tunde(P2) | none | 37-A Kitchen & order operations |
| UXT-012 | P0 | major | A product whose ingredients name an allergen (e.g. 'butter (MILK)') with no box ticked saves and shows as 'No allergens' | Ade(P1) | related #427 | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-013 | P0 | major | Shops go live with a failed FSA match, self-approved by the vendor, while the site claims 'UK food-hygiene verified' | Ade(P1), Claire(P1), Bola(P2) | related #453 | Phase 33 – The Consumer Product (CUST-02/CUST-04 still open) |
| UXT-014 | P0 | major | Customers are never given the seller's legal identity or any way to contact the shop | Priya(P1), Bola(P2) | none | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-015 | P0 | major | Stripe JS and fraud cookies load on a cash-only checkout, contradicting the cookie policy | Sam(P1), Bola(P2) | none | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-016 | P0 | major | Updating a product without quantityInStock silently turns stock tracking off | Ravi(P2) | none | 37-E Integrator surface (API, MCP, webhooks, sync) |
| UXT-017 | P0 | major | Products created by /sync/batch belong to no shop, are orderable at any shop, and record the wrong allergens | Ravi(P2) | same #727 | 37-E Integrator surface (API, MCP, webhooks, sync) |
| UXT-018 | P0 | major | A completed, paid order can be deleted, leaving ledger rows that point at nothing | Kemi(P2) | none | 37-B Multi-site, staff access & finance |
| UXT-019 | P0 | minor | The platform's own registered office is not published anywhere on the site | Claire(P1) | none | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-020 | P1 | blocker | A new order makes no sound and never reaches the kitchen screen until someone confirms it on another page | Funmi(P2), Tunde(P2) | none | 37-A Kitchen & order operations |
| UXT-021 | P1 | major | When the all-day kitchen tablet's session lapses the board silently becomes a sign-in page | Tunde(P2) | none | 37-A Kitchen & order operations |
| UXT-022 | P1 | major | A vendor cannot pause or stop taking orders: no pause switch, no holiday closure, and free-text hours fail open and cannot be cleared | Funmi(P2), Kemi(P2), Nkechi(P2) | none | 37-A Kitchen & order operations |
| UXT-023 | P1 | major | Vendors cannot set a delivery fee, free-delivery threshold or collection-only: orders are charged £0 delivery and collection-only shops take deliveries | Ade(P1), Kemi(P2), Nkechi(P2) | none | 37-G Catalogue & shop-admin correctness |
| UXT-024 | P1 | blocker | A second or third shop can never go live, while onboarding says 'Your storefront is live' | Ade(P1), Kemi(P2) | same #452 | Phase 33 – The Consumer Product (CUST-02/CUST-04 still open) |
| UXT-025 | P1 | major | There is no way to invite a staff member: they must self-register, then auto-become Group admin | Kemi(P2) | same #452 | Phase 33 – The Consumer Product (CUST-02/CUST-04 still open) |
| UXT-026 | P1 | major | CSV import ignores the selected shop and hides imported items from every storefront; a menu cannot be copied to another site | Claire(P1), Kemi(P2) | none | 37-B Multi-site, staff access & finance |
| UXT-027 | P1 | major | Per-shop dashboard and finance show the whole business's takings, and site managers get 'No financial data yet' instead of their shop's numbers | Kemi(P2) | none | 37-B Multi-site, staff access & finance |
| UXT-028 | P1 | major | Every shop update regenerates the public URL slug, so shared links and QR codes break | Ade(P1) | none | 37-G Catalogue & shop-admin correctness |
| UXT-029 | P1 | major | The order confirmation is rendered in place at /checkout: off-screen, unannounced, and lost on refresh | Sam(P1), Grace(P2), Marcus(P2) | related #409 | 37-C Checkout integrity & customer trust/retention |
| UXT-030 | P1 | major | Delivery is accepted to any UK postcode with no radius check at checkout | Jordan(P1), Kyle(P2) | same #460 | Phase 33 – The Consumer Product (CUST-02/CUST-04 still open) |
| UXT-031 | P1 | blocker | A prospective vendor has no way in: 'Start your application' dead-ends at a login, and no sales or support contact exists | Ade(P1), Claire(P1) | related #102 | Phase 32 – Production Cutover + First Tenant |
| UXT-032 | P1 | major | No merchant terms, pricing page, VAT basis or card-fee figure: /legal/terms, /pricing, /contact and /about all 404 | Claire(P1) | none | Phase 32 – Production Cutover + First Tenant |
| UXT-033 | P1 | major | The published £39/location subscription has no billing built and no billing UI | Claire(P1) | same #102 | Phase 30 – The Money Path, Executed |
| UXT-034 | P1 | major | Vendors have no payments or payouts surface (/dashboard/payments returns 404) | Claire(P1) | same #102 | Phase 30 – The Money Path, Executed |
| UXT-035 | P1 | blocker | A vendor has no way to give a developer API credentials; the only path is the owner's password plus the confidential core-api client secret | Ravi(P2) | related #206 | 37-E Integrator surface (API, MCP, webhooks, sync) |
| UXT-036 | P1 | major | An order taken by an AI agent through MCP stays DRAFT and the kitchen never sees it | Ravi(P2) | none | 37-E Integrator surface (API, MCP, webhooks, sync) |
| UXT-037 | P1 | major | /sync/batch has no stock or availability field and silently skips unknown items while reporting SUCCESS | Ravi(P2) | related #727 | 37-E Integrator surface (API, MCP, webhooks, sync) |
| UXT-038 | P1 | major | Webhook endpoints auto-pause after ~46 s of failures and retries stop after 5 attempts, with nobody told | Ravi(P2) | same #587 | 37-E Integrator surface (API, MCP, webhooks, sync) |
| UXT-039 | P1 | major | Anonymous traffic can exhaust the edge gateway's single process-wide rate limit and block every vendor's sync; its 429 is untyped | Ravi(P2) | related #413 | 37-D Abuse resistance |
| UXT-040 | P1 | major | Asked for one shop's menu, an AI agent gets every product in the business with allergens only as an integer | Ravi(P2) | none | 37-E Integrator surface (API, MCP, webhooks, sync) |
| UXT-041 | P1 | major | Anyone can place many fake cash orders in seconds with throwaway contact details | Kyle(P2) | related #461 | 37-D Abuse resistance |
| UXT-042 | P1 | major | Item quantity has no upper bound: a £19 billion order is accepted and shown on the dashboard | Kyle(P2) | none | 37-D Abuse resistance |
| UXT-043 | P1 | minor | An allergy request is a generic free-text note: no alert to the vendor, no acknowledgement, never echoed to the customer | Priya(P1), Bola(P2) | none | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-044 | P1 | major | Reviews publish the reviewer's full checkout name with no notice, policy or moderation | Grace(P2), Bola(P2) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-045 | P1 | major | The local compose stack can never exercise the card-payment path (Stripe env-var names and build-time key mismatch) | Coord | related #461, #538, #61 | Phase 30 – The Money Path, Executed |
| UXT-046 | P1 | minor | Products have no VAT-rate choice; everything is booked as Standard 20% | Kemi(P2) | related #81 | 37-B Multi-site, staff access & finance |
| UXT-047 | P1 | major | 'VAT (incl. 20%)' is shown for every vendor, with no VAT-registration status or number captured | Bola(P2) | none | 37-B Multi-site, staff access & finance |
| UXT-048 | P1 | major | Menu cards show allergens as an unnamed count, 'Add' works without seeing them, and 'none declared' is never stated | Priya(P1), Marcus(P2) | none | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-049 | P2 | major | A double-tap on 'Start Preparing' jumps the order to READY and emails the customer 'Ready!' with no undo | Tunde(P2) | none | 37-A Kitchen & order operations |
| UXT-050 | P2 | major | Kitchen mute survives sign-out and the next person's icon shows sound on while new orders are silent | Tunde(P2) | none | 37-A Kitchen & order operations |
| UXT-051 | P2 | major | Cancelling an order is one unconfirmed tap with no reason, next to Confirm on small phone buttons | Funmi(P2) | none | 37-A Kitchen & order operations |
| UXT-052 | P2 | minor | Unanswered orders stay Pending forever and the customer is never told | Funmi(P2) | none | 37-A Kitchen & order operations |
| UXT-053 | P2 | major | Orders and Customers have no search, so an order cannot be found by customer name at the counter | Funmi(P2) | none | 37-A Kitchen & order operations |
| UXT-054 | P2 | major | Every kitchen ticket cuts the order number to 'ORD-…', so tickets look identical | Kemi(P2), Tunde(P2) | none | 37-A Kitchen & order operations |
| UXT-055 | P2 | minor | The kitchen board is not built for a wall tablet: wasted space, small text, newest-first ordering and very tall tickets | Funmi(P2), Tunde(P2), Nkechi(P2) | related #699 | 37-A Kitchen & order operations |
| UXT-056 | P2 | major | No ready-by or delivery time exists anywhere: tickets, confirmation, tracking or emails | Jordan(P1), Funmi(P2), Grace(P2) | related #458 | 37-C Checkout integrity & customer trust/retention |
| UXT-057 | P2 | minor | Order confirmation and every status email omit the shop, items, total and address, and come from inconsistent senders | Jordan(P1), Sam(P1), Funmi(P2), Grace(P2), Bola(P2), Nkechi(P2) | related #649 | 37-C Checkout integrity & customer trust/retention |
| UXT-058 | P2 | major | Finance has no 'today', no date range, no export and no cash/card split | Funmi(P2), Kemi(P2) | none | 37-B Multi-site, staff access & finance |
| UXT-059 | P2 | minor | A completed cash order stays 'Payment status NONE / Unpaid' while counted as revenue | Bola(P2) | related #461 | Phase 30 – The Money Path, Executed |
| UXT-060 | P2 | major | Cash-only is revealed only at the bottom of checkout, and the Place order button shows a card icon | Jordan(P1), Priya(P1), Funmi(P2) | related #461 | Phase 30 – The Money Path, Executed |
| UXT-061 | P2 | major | Menu 'Add' buttons are visible but ignore taps until hydration (4.2 s on 4G, 14 s on Slow 3G) | Jordan(P1) | related #507 | Phase 34 – Rendering + Test Truthfulness (complete: #507 remainder) |
| UXT-062 | P2 | major | Saving a shop overwrites its banner with the logo URL | Ade(P1) | none | 37-G Catalogue & shop-admin correctness |
| UXT-063 | P2 | major | Products cannot be deleted once they have a photo or any order (even cancelled): misleading 409 | Ade(P1), Nkechi(P2) | none | 37-G Catalogue & shop-admin correctness |
| UXT-064 | P2 | major | Deleting a shop orphans its products, and editing an orphan silently moves it to 'All Shops' | Ade(P1) | related #727 | 37-G Catalogue & shop-admin correctness |
| UXT-065 | P2 | major | The image dialog stays on 'Processing…' although the server has the image ACTIVE within ~6 s | Ade(P1) | none | 37-G Catalogue & shop-admin correctness |
| UXT-066 | P2 | minor | The 'Publish to storefront' checkbox is ignored, or throws away the whole shop edit with a raw developer error | Ade(P1), Kemi(P2) | none | 37-G Catalogue & shop-admin correctness |
| UXT-067 | P2 | major | Internal strategy pages are public, expose a repo path, and contradict the landing page (incl. a 'payouts: Full' claim) | Ade(P1), Claire(P1) | none | Phase 32 – Production Cutover + First Tenant |
| UXT-068 | P2 | minor | Test and demo data is visible to customers and vendors (E2E 20% OFF promo, weeks-old test orders, a real person's name and email) | Claire(P1) | none | Phase 32 – Production Cutover + First Tenant |
| UXT-069 | P2 | major | There is no account page: data access and erasure are mailto-only although a backend intake exists | Sam(P1), Grace(P2) | none | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-070 | P2 | minor | The DSAR confirmation link does not open on compose and shows raw JSON when it does | Grace(P2) | none | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-071 | P2 | minor | The cookie policy omits keys that hold the customer's email and id and survive sign-out | Sam(P1) | none | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-072 | P2 | minor | Order contact details are barely validated, and API/MCP orders need no customer contact at all | Sam(P1), Kyle(P2), Ravi(P2) | none | 37-D Abuse resistance |
| UXT-073 | P2 | major | Vendors have no bulk-reject and no fraud signal for junk orders | Kyle(P2) | none | 37-D Abuse resistance |
| UXT-074 | P2 | major | On a slow network, returning after 5 minutes signs the customer out (parallel refreshes burn the single-use token) | Nkechi(P2) | regression-of-closed #465 | 37-C Checkout integrity & customer trust/retention |
| UXT-075 | P2 | major | Customers cannot order ahead for a time, and a closed shop takes no pre-orders | Nkechi(P2) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-076 | P2 | major | No 'order again' and no remembered address: a weekly order takes 10 taps and 85 keystrokes | Grace(P2) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-077 | P2 | major | Customers have no way to leave a review; the only path is an unauthenticated API call with an email in the URL | Grace(P2) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-078 | P2 | major | Password-reset links expire after 5 minutes | Grace(P2) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-079 | P2 | major | Keycloak sign-in and registration fall below the storefront's accessibility bar and hide password rules until failure | Grace(P2), Marcus(P2) | related #545 | Phase 33 – The Consumer Product (CUST-02/CUST-04 still open) |
| UXT-080 | P2 | major | At large text sizes the basket, tracker and shop header truncate or overflow | Grace(P2) | none | 37-F Accessibility |
| UXT-081 | P2 | major | Tracking pages are silent and unlabelled for screen readers (status changes, current step, copy button, lookup result, field name, contrast) | Marcus(P2) | none | 37-F Accessibility |
| UXT-082 | P2 | major | Shop cards on the kitchen list have no accessible name ('link, link, link') | Marcus(P2) | none | 37-F Accessibility |
| UXT-083 | P2 | major | Pressing Add/Remove drops focus to the page body and the basket announcement doubles the count without naming the item | Marcus(P2) | related #272 | 37-F Accessibility |
| UXT-084 | P2 | minor | Basket, checkout, confirmation and tracking all share the title 'J'Toye — Discover Local Vendors' | Marcus(P2) | none | 37-F Accessibility |
| UXT-085 | P2 | minor | On mobile the cookie banner covers content and keyboard focus and is the 32nd tab stop | Ade(P1), Jordan(P1), Marcus(P2) | none | 37-F Accessibility |
| UXT-086 | P2 | major | A delivery customer's tracking page says 'Ready for collection' | Funmi(P2) | related #502 | 37-C Checkout integrity & customer trust/retention |
| UXT-087 | P2 | minor | A kitchen hand sees far more customer personal data than needed to cook | Tunde(P2) | none | 37-B Multi-site, staff access & finance |
| UXT-088 | P2 | major | A vendor cannot show who prepared an order or when: no timeline, no export | Bola(P2) | none | 37-A Kitchen & order operations |
| UXT-089 | P2 | minor | 86ing an item or changing a price takes four taps and ~30 s in a long form | Funmi(P2) | none | 37-A Kitchen & order operations |
| UXT-090 | P2 | minor | Nothing asserts at deploy time that customer email verification is on; dev runs with it off | Jordan(P1) | related #462 | Phase 29 – Deployable Staging, With Its Own Monitoring |
| UXT-091 | P2 | minor | The basket shows no allergens and checkout's combined set has no per-item attribution | Priya(P1) | none | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-092 | P2 | minor | No 'may contain' field exists, label use-by is computed at download time, and records use US date format | Priya(P1), Bola(P2) | related #427, #82 | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-093 | P2 | major | There is no allergen filter, and searching 'peanut' or 'gluten free' returns nothing | Priya(P1) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-094 | P2 | major | A vendor cannot connect their own WhatsApp; WhatsApp ordering is one platform-wide setting | Ravi(P2) | same #208 | 37-E Integrator surface (API, MCP, webhooks, sync) |
| UXT-095 | P2 | major | The MCP server cannot be added to hosted ChatGPT/Claude connectors (no OAuth discovery, 5-minute tokens) | Ravi(P2) | related #203 | 37-E Integrator surface (API, MCP, webhooks, sync) |
| UXT-096 | P2 | major | Order webhooks carry only ids and status, with no items, totals or customer | Ravi(P2) | related #205 | 37-E Integrator surface (API, MCP, webhooks, sync) |
| UXT-097 | P2 | major | Vendor API and MCP orders accept products marked unavailable | Ravi(P2) | none | 37-E Integrator surface (API, MCP, webhooks, sync) |
| UXT-098 | P3 | minor | Scoped users see nav items they cannot use, and a 403 renders as 'No endpoints yet' / 'storefront is live' | Kemi(P2) | related #762 | 37-B Multi-site, staff access & finance |
| UXT-099 | P3 | minor | With the API unreachable but the socket up, the board says 'Live' for ~50 s while dropping an order | Tunde(P2) | related #106 | 37-A Kitchen & order operations |
| UXT-100 | P3 | minor | After access is removed the kitchen keeps showing that shop's tickets (with PII and live buttons) and blames the connection | Kemi(P2) | related #627 | 37-B Multi-site, staff access & finance |
| UXT-101 | P3 | minor | The orders table is clipped on tablet and the phone's first screen is an explainer, not orders | Kemi(P2) | related #699 | 37-A Kitchen & order operations |
| UXT-102 | P3 | minor | A customer cannot cancel an order | Sam(P1) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-103 | P3 | minor | A network failure at Place order says only 'Failed to place order. Please try again.' | Sam(P1) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-104 | P3 | minor | Back then Forward wipes the checkout address, phone and notes | Sam(P1) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-105 | P3 | minor | Empty checkout submit skips the address and takes two rounds, with no error summary | Marcus(P2) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-106 | P3 | minor | The basket is device-local: not restored on sign-in and not shared across devices | Jordan(P1), Nkechi(P2) | related #459 | 37-C Checkout integrity & customer trust/retention |
| UXT-107 | P3 | minor | Sign-out is a tiny unlabelled one-tap icon next to a person icon that does nothing | Jordan(P1), Grace(P2) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-108 | P3 | minor | Menu items cannot be customised (no modifiers) | Jordan(P1) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-109 | P3 | polish | The shop list uses gradients and initials instead of food photos | Jordan(P1) | related #546 | Phase 33 – The Consumer Product (CUST-02/CUST-04 still open) |
| UXT-110 | P3 | polish | Storefront copy noise: duplicated dishes and alt text, descriptions repeating names, 'Co..', 'Draft' filter, status naming mismatch | Jordan(P1), Sam(P1), Marcus(P2) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-111 | P3 | polish | React hydration error #418 on /shop/orders and /track, and 'Auto-refreshing' on finished orders | Jordan(P1), Grace(P2) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-112 | P3 | minor | Postcode search cannot tell invalid, out-of-area and no-kitchens apart, and its result count may not be announced | Jordan(P1), Marcus(P2) | related #619, #460 | Phase 33 – The Consumer Product (CUST-02/CUST-04 still open) |
| UXT-113 | P3 | minor | There is no marketing-consent choice or preferences page, and /unsubscribe says 'contact the vendor' | Grace(P2) | related #592 | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-114 | P3 | minor | The accessibility statement is stale (claims no skip link, cites WCAG 2.1, excludes basket/confirmation/tracking) | Marcus(P2) | none | Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1) |
| UXT-115 | P3 | minor | SKU is required when adding a product (jargon for a stall holder) | Ade(P1) | none | 37-G Catalogue & shop-admin correctness |
| UXT-116 | P3 | polish | An unknown shop link returns a soft 404 (HTTP 200 'Shop not found') | Ade(P1) | none | 37-C Checkout integrity & customer trust/retention |
| UXT-117 | P3 | polish | Staff list masks emails so same-domain staff are indistinguishable; products table has no Shop column | Kemi(P2) | none | 37-B Multi-site, staff access & finance |
| UXT-118 | P3 | minor | Another shop's order returns 403 not 404, and public image URLs embed the tenant UUID | Dele(P1), Kemi(P2) | none | 37-D Abuse resistance |
| UXT-119 | P3 | minor | Public pages: no vendor-to-vendor confidentiality statement; positioning excludes non-London, non-West-African operators | Dele(P1), Claire(P1) | none | Phase 32 – Production Cutover + First Tenant |
| UXT-120 | P3 | polish | No email campaigns, loyalty or vouchers | Claire(P1) | none | NEW: Phase 37 – Real-world operations readiness (persona findings) |
| UXT-121 | P3 | polish | No custom storefront domain (SUSPECTED) | Claire(P1) | none | NEW: Phase 37 – Real-world operations readiness (persona findings) |
| UXT-122 | P3 | major | Webhooks have no test event, no request/response body, no delete, and do not follow redirects (Apps Script fails) | Ravi(P2) | none | 37-E Integrator surface (API, MCP, webhooks, sync) |
| UXT-123 | P3 | minor | API/MCP contract polish: MCP drops typed error details, OpenAPI hygiene problems, replay indistinguishable from fresh | Ravi(P2) | none | 37-E Integrator surface (API, MCP, webhooks, sync) |

## P0 and P1 detail

### UXT-001 [P0] DSAR erasure is marked completed while nothing is erased for storefront customers

- **Why P0:** Data-protection failure: Article 17 requests report success while every storefront customer's PII and public review survive.
- **Members:** P2-GRA-01 (Grace(P2), blocker, CONFIRMED)
- **Repro:** Register a storefront customer and place a cash order at Mama Ade's Kitchen (tenant A) → Optionally leave a review for the completed order → POST /api/v1/public/gdpr/dsar {email, requestType:ERASURE} → Open the verification link from Mailhog (host rewritten 8080->9090) → Wait for the next fan-out sweep (5 min) and read core-java logs → Read the order via the vendor API and GET /api/v1/public/shops/mama-ades-kitchen/reviews
- **Expected:** Customer PII on orders anonymised, review anonymised, completion confirmed to the subject
- **Actual:** Log: event=dsar_fanout_completed tenantsErased=0 tenantsScanned=2. Order still holds customerName, customerEmail, customerPhone and notes ('I am slow to the door'); public review still shows 'Grace Persona14'; customer can still sign in and see both orders; no completion email. Cause (from code): GdprService.eraseSubjectByDigest iterates only `customers` rows, and storefront orders create none (tenant A: 7 customers, none for grace/jordan/sam/priya)
- **Impact:** Every storefront customer's Article 17 request is reported satisfied while nothing is erased: an ICO-reportable compliance failure, and the request queue shows 'completed', so nobody notices
- **Code (verified):** core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java:449-458 (eraseSubjectByDigest iterates customerRepository.findIdAndEmailByTenantId only)
- **Existing issue:** regression-of-closed #84 — #84 closed with 'erasure removes guest-order PII'; the 31-09 DSAR fan-out path scans only `customers` rows, which storefront orders never create. Related: #764, #771 (same GdprService, closed 2026-09-30).
- **Home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

### UXT-002 [P0] A verified DSAR access request is never fulfilled (ACCESS delivery not implemented)

- **Why P0:** Data-protection failure: the statutory one-month Article 15 deadline is missed for every requester after they were told it will be actioned.
- **Members:** P2-GRA-02 (Grace(P2), major, CONFIRMED)
- **Repro:** Lodge an ACCESS request for the customer email → Open the verification link (status verified) → Watch core-java logs over several 5-minute sweeps
- **Expected:** A copy of the data is delivered within one month
- **Actual:** Every sweep logs 'event=dsar_access_requests_outstanding count=1 — ACCESS delivery is not implemented in plan 31-09'. No email or download is ever produced
- **Impact:** Statutory one-month Article 15 deadline missed for every requester; the subject was told 'your request is confirmed and will be actioned'
- **Code (unverified):** DsarFanoutWorker logs 'ACCESS delivery is not implemented in plan 31-09' (member cites the log line)
- **Existing issue:** none
- **Home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

### UXT-003 [P0] Revoking a manager's last shop grant silently makes him tenant-wide Group admin

- **Why P0:** Privilege escalation: an owner's revoke produces MORE access (all shops, staff management), while the Staff page shows no row at all.
- **Members:** P2-KEM-01 (Kemi(P2), blocker, CONFIRMED)
- **Repro:** As admin-user-b, grant yourself Group admin (All shops) on /dashboard/staff so the auto row can be removed → Revoke tenant-b-user's 'Auto-granted on first sign-in / All shops / Group admin' row; grant tenant-b-user Shop manager on ONE shop → Confirm GET /api/v1/staff/me as tenant-b-user -> groupAdmin:false, grantedShopIds:[that shop] → Revoke that one shop grant (UI Revoke button) → GET /api/v1/staff/me as tenant-b-user; GET /api/v1/orders?shopId=<other shop>; GET /api/v1/staff
- **Expected:** Removing the last grant leaves the person with no access to any shop
- **Actual:** staff/me returns groupAdmin:true, grantedShopIds:null. Orders for the other shop return 200 and GET /api/v1/staff returns 200 (staff management). On his next write a new 'JIT GROUP_ADMIN' row appears. The Staff page meanwhile lists NO row for him, so the owner believes he has no access. Reproduced twice (API revoke and UI revoke). With his kitchen open, a reload switched him to the other shop's tickets.
- **Impact:** An owner who removes a sacked or moved manager gives that person full access to all sites, all orders and staff management. The UI tells her the opposite. Cause: strict-scoping is OFF, so an ungranted tenant user is an implicit tenant-wide Group admin.
- **Code (verified):** core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:49-62,104-106,178 (strict-scoping OFF: an ungranted tenant user is an implicit tenant-wide GROUP_ADMIN)
- **Existing issue:** related #285, #499 — #285 (bulk revoke of JIT rows) and #499 (grant upsert) touch the same rows; neither describes the escalation.
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

### UXT-004 [P0] Every staff login is a tenant-wide Group admin by default (JIT provisioning, strict-scoping off)

- **Why P0:** Privilege escalation by default: a kitchen login can reprice the menu, add an outbound webhook streaming all orders, grant staff and edit customers.
- **Members:** ADE-13 (Ade(P1), minor, CONFIRMED); P2-TUN-05 (Tunde(P2), major, CONFIRMED); P2-KEM-02 (Kemi(P2), major, CONFIRMED)
- **Repro:** Get tenant-a-user's access token → GET /api/v1/staff/me → POST /api/v1/products (TUN-TEST-1), PUT pricePennies=1, DELETE → POST /api/v1/webhooks {targetUrl:'https://example.com/...',eventTypes:['ORDER_STATE_CHANGED']} then revoke → POST /api/v1/staff/grant (self, STAFF) then DELETE → POST then DELETE /api/v1/customers; GET /api/v1/customers
- **Expected:** A kitchen hand can only read and bump tickets for his shop
- **Actual:** staff/me → groupAdmin:true (JIT grant, strict-scoping off by default). Product create 201 / reprice 200 / delete 204; webhook create 201 and a delivery was already attempted (consecutiveFailures:1) before revoke; staff grant 201 / revoke 204; customer create 201 / delete 204; all customers with email+phone readable. By code, DELETE /orders/{id} (SHOP_MANAGER) is also allowed — not executed. Refund, finance, GDPR export, onboarding approvals correctly 403.
- **Impact:** Any staff login the owner hands out can reprice the menu, stream all orders to an outside server, add staff, or delete orders — the owner has to understand JIT grants to prevent it.
- **Code (verified):** core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java:59-62,104-106,178 (D-04 JIT auto-provision; jtoye.access.strict-scoping defaults false)
- **Existing issue:** related #285 — #285 is UX for cleaning up JIT rows; the default itself is untracked.
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

### UXT-005 [P0] A buyer of one shop can publish a 5-star review on a different shop of the same tenant

- **Why P0:** Cross-shop fake reviews feed public ratings and JSON-LD aggregateRating (CPR/DMCC fake-review exposure).
- **Members:** P2-REG-01 (Bola(P2), major, CONFIRMED)
- **Repro:** Place a guest order at mama-ades-kitchen → Vendor completes it → POST /api/v1/public/shops/brixton-village-grill/reviews?email=<buyer email> with {orderId:<Mama Ade's order id>, foodRating:5} → Open /shop/brixton-village-grill
- **Expected:** 400: order does not belong to this shop
- **Actual:** HTTP 201; Brixton shows '5 (1)' and 'Customer reviews (1)', and its JSON-LD aggregateRating is ratingValue 5, reviewCount 1. ReviewService.createReview checks email, COMPLETED and tenant, but never order.shopId == shop.id
- **Impact:** A multi-shop owner (or one cheap order) can seed 'verified' ratings on sibling shops. This is a fake-review exposure under DMCC Act 2024 Sch 20 for the platform.
- **Code (verified):** core-java/src/main/java/uk/jtoye/core/review/ReviewService.java:70-95 (checks email, COMPLETED, duplicate; never order.shopId == shop.id)
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-D Abuse resistance

### UXT-006 [P0] The public rate limiter trusts any X-Forwarded-For value, so rotating it defeats the limit

- **Why P0:** Abuse control bypass: the only throttle on fake guest orders and review spam can be reset per request.
- **Members:** P2-KYL-02 (Kyle(P2), major, CONFIRMED)
- **Repro:** GET /public/shops/mama-ades-kitchen/config with no header -> note X-RateLimit-Remaining → Repeat with header 'X-Forwarded-For: 203.0.113.7', then '...8' → Observe remaining jumps back to 719 (a fresh bucket) for each spoofed value
- **Expected:** The IP-keyed public limiter (issue #88) should throttle a single abusive client regardless of client-supplied headers
- **Actual:** ClientIpResolver.resolveClientIp() returns the first X-Forwarded-For hop unconditionally, with no trusted-proxy allow-list. A hostile guest sets a new XFF per request and gets a fresh bucket every time, so the only throttle on fake orders is bypassable. (In prod this depends on the ingress overwriting XFF; the application trusts it blind.)
- **Impact:** Removes the single brake on KYL-01. With header rotation the 10-order burst becomes an unbounded flood of fake orders against any shop, from one machine.
- **Code (verified):** core-java/src/main/java/uk/jtoye/core/security/ClientIpResolver.java:32,47 (first X-Forwarded-For hop, no trusted-proxy allow-list)
- **Existing issue:** regression-of-closed #88 — #88 closed by adding an IP-keyed limiter for /public/**; the IP is taken from the first client-controlled XFF hop, so the fix is bypassable.
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-D Abuse resistance

### UXT-007 [P0] Checkout never re-validates the stored basket: stale prices, removed and sold-out items surface only as a charge or a bare error

- **Why P0:** Price charged differs from price shown (£11.50 shown, £12.00 charged; £188.50 shown, £198.50 charged) with no notice.
- **Members:** P2-FUN-03 (Funmi(P2), major, CONFIRMED); P2-CHA-01 (Nkechi(P2), major, CONFIRMED); P2-FUN-09 (Funmi(P2), minor, CONFIRMED); P2-CHA-03 (Nkechi(P2), major, CONFIRMED); P2-CHA-04 (Nkechi(P2), minor, CONFIRMED); P2-CHA-07 (Nkechi(P2), minor, CONFIRMED); F-10 (Sam(P1), minor, SUSPECTED)
- **Repro:** Add a product to the basket and open checkout (the basket shows the price at the time it was added) → As the vendor, raise that product's price (PUT /api/v1/products/{id}) → Without reloading, tap 'Place order · £X' → Compare the button amount with the confirmation total
- **Expected:** Either the checkout refreshes prices and says 'a price changed: £9.00 -> £14.00, please confirm', or the order is refused until she confirms the new total
- **Actual:** Run 1 (laptop): the button said 'Place order · £188.50' and the confirmation said £198.50 (+£10, two Suya at £14 instead of £9). Run 2 (phone): the button said £19.00 and the confirmation said £21.00. The cart page, the checkout lines and the button all use the price stored in localStorage when the item was added; the menu already shows the new price. The server always charges the live price (by design: 'never trust client'), but no surface says that the price changed. The confirmation email carries no total either.
- **Impact:** On a cash order the driver asks for more than the app promised. That means an argument at the office door, a customer who feels cheated, and possibly a refused delivery of 35 items of food. For an office lunch collected from 8 colleagues in cash, the amount she collected no longer matches.
- **Code (unverified):** frontend cart/checkout use the price stored in localStorage at add time (member P2-CHA-01 cites cart page, checkout lines and button); server reprices silently
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

### UXT-008 [P0] An advertised '20% OFF' promotion is displayed on the storefront but never applied to the order

- **Why P0:** Price charged differs from the price advertised (misleading pricing under CPR 2008 / DMCC 2024).
- **Members:** P2-KYL-07 (Kyle(P2), minor, CONFIRMED); P2-REG-05 (Bola(P2), major, CONFIRMED)
- **Repro:** Open /shop/mama-ades-kitchen (banner '20% off selected dishes', chip 'E2E 20% OFF') → Add Jollof Rice and go through to checkout
- **Expected:** Discounted dishes are identified and the discount is applied, or the banner is not shown
- **Actual:** No dish is marked; Jollof costs £8.99 in the basket, at checkout and in the order record (unitPricePennies 899). Order pricing has no promotion logic
- **Impact:** A misleading price claim (CPUTR / DMCC Act 2024 Part 4). Customers pay more than advertised.
- **Code (verified):** core-java/.../storefront/PublicStorefrontService.java:231,246 read promotions for display only; order/OrderService.java has no promotion reference (rg rc=1)
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

### UXT-009 [P0] The customer's allergen acknowledgement is never sent to or stored by the server

- **Why P0:** Legal/safety: there is no evidence of what the customer acknowledged, and API/MCP orders bypass the client-only gate.
- **Members:** P2-REG-02 (Bola(P2), major, CONFIRMED)
- **Repro:** Tick 'I have read the allergen information for this order.' at checkout and place the order → Inspect the POST body → Open the order detail as the vendor
- **Expected:** The order record holds acknowledged=true, a timestamp and the allergen set shown
- **Actual:** The POST body has no acknowledgement field; no ack field exists in the order DTO or the backend. The gate is client-only, so orders placed through the API or MCP skip it
- **Impact:** After an allergic reaction the vendor cannot prove the customer was warned, which weakens a due-diligence defence.
- **Code (verified):** core-java/src/main/java/uk/jtoye/core/storefront/dto/GuestOrderRequest.java has no acknowledgement field (rg -i acknowledg rc=1; control: 11 fields matched)
- **Existing issue:** related #427 — #427 (allergen evidence chain epic) does not cover the acknowledgement record.
- **Home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

### UXT-010 [P0] The customer never sees the allergen set recorded on their order, and it can differ from what they acknowledged

- **Why P0:** User safety + legal: the order (and kitchen ticket) records a different allergen set from the one acknowledged, and no confirmation, email or tracking page shows it.
- **Members:** P05-2 (Priya(P1), major, CONFIRMED); P2-REG-04 (Bola(P2), major, CONFIRMED); P2-CHA-02 (Nkechi(P2), major, CONFIRMED); P05-9 (Priya(P1), minor, SUSPECTED)
- **Repro:** Open checkout. The allergen panel lists the basket's declared allergens → Tick 'I have read the allergen information for this order' → As the vendor, add allergens to a product in the basket (PUT /api/v1/products/{id}, allergenMask) → Without reloading, place the order → Read GET /api/v1/orders/{id}/detail and the kitchen ticket
- **Expected:** The order records the set the customer actually acknowledged, or the server refuses the submit when the declared set has changed since the panel was rendered, and the customer re-acknowledges
- **Actual:** Run 1: she acknowledged Gluten, Fish, Peanuts; the snapshot and the kitchen banner say Gluten, Eggs, Fish, Peanuts, Milk. Run 2: she acknowledged Gluten, Eggs, Milk; the snapshot says Gluten, Eggs, Milk, Sesame. The panel is fetched once on mount and never re-checked, and the submit carries no record of what was acknowledged. The system therefore holds a record that implies she accepted allergens she was never shown. No customer surface (confirmation, email, tracking) shows the snapshotted set.
- **Impact:** In an office order for 8 people, a colleague with a milk or sesame allergy relies on what Nkechi saw. The kitchen labels correctly, but the customer-side record says she acknowledged the allergen. This is the 'customer acknowledges A, kitchen sees B' gap that V63 was meant to close; it has moved to the window between acknowledging and submitting.
- **Code (unverified):** checkout allergen panel fetched once on mount and never re-checked (member P2-CHA-02); confirmation/emails/track carry no allergen fields
- **Existing issue:** related #427 — #427 is the allergen evidence-chain epic; it does not cover the customer-facing record.
- **Home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

### UXT-011 [P0] The kitchen screen ticket hides the customer's note (e.g. 'severe peanut allergy') and the fulfilment type

- **Why P0:** User safety: a written allergy instruction never reaches the cook on screen (only the printed ticket has it).
- **Members:** P2-TUN-03 (Tunde(P2), major, CONFIRMED); P2-FUN-02 (Funmi(P2), major, CONFIRMED); P2-TUN-11 (Tunde(P2), minor, CONFIRMED)
- **Repro:** Place a guest order with notes 'TUN-test: no sesame, ring bell twice' → Confirm it → Read the ticket on the kitchen board
- **Expected:** Notes are prominent on the kitchen card
- **Actual:** Card shows allergen banner, name, items only; notes are absent from the screen card (page.tsx renders no order.notes) although GET /api/v1/orders/kitchen returns them and the PRINTED ticket (kitchen-ticket.tsx) includes them.
- **Impact:** Customers write allergies and special requests in the notes field; a kitchen working from the screen never sees them — allergic-reaction risk and remakes.
- **Code (verified):** frontend/app/dashboard/kitchen/page.tsx renders no order.notes (rg -uu 'notes' rc=1 on the file and the directory; control 'Kitchen' = 12 hits); kitchen-ticket.tsx (print) does
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

### UXT-012 [P0] A product whose ingredients name an allergen (e.g. 'butter (MILK)') with no box ticked saves and shows as 'No allergens'

- **Why P0:** User safety: the platform publishes 'No allergens' for a dish whose own ingredient text names MILK, with no warning to the vendor.
- **Members:** ADE-09 (Ade(P1), major, CONFIRMED)
- **Repro:** Sign in as a vendor, Products → Add product → Ingredients: 'rice, butter (MILK), pepper'; tick no allergen → Save, then open the dish on the storefront
- **Expected:** Save warns that MILK is named in the ingredients but not declared; the storefront never says 'No allergens' for it
- **Actual:** Saves silently; product shows 'No allergens'
- **Impact:** A milk-allergic customer is told a dish containing butter is allergen-free.
- **Code (unverified):** 31-04 OrderAllergenAggregator reconciles at ORDER time only; nothing reconciles at product save (not verified in code)
- **Existing issue:** related #427 — #427 (ingredient/allergen evidence chain) is the long-term home; the save-time warning is not tracked.
- **Home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

### UXT-013 [P0] Shops go live with a failed FSA match, self-approved by the vendor, while the site claims 'UK food-hygiene verified'

- **Why P0:** Legal breach (misleading claim to consumers) and safety: 'verified' is shown for shops whose hygiene check failed, and no FHRS rating is displayed.
- **Members:** ADE-08 (Ade(P1), major, CONFIRMED); C-07 (Claire(P1), major, CONFIRMED); P2-REG-09 (Bola(P2), major, CONFIRMED); ADE-14 (Ade(P1), minor, SUSPECTED)
- **Repro:** As admin-user open /dashboard/onboarding → Compare with the published /shop list
- **Expected:** Not published until the check passes, as the page itself states
- **Actual:** 'No FSA establishment matched the shop name/address — Manual review', yet the shop is live. No FHRS rating or registration is shown publicly
- **Impact:** The landing trust strip promises 'UK food-hygiene verified' while shops whose FSA match failed are live, approved only by their own admin; the platform may be listing an unregistered food business and shows no FHRS rating.
- **Existing issue:** related #453 — #453 records that MANUAL_REVIEW has no adjudicator; this cluster is the public claim and the live-while-unverified state.
- **Home:** Phase 33 – The Consumer Product (CUST-02/CUST-04 still open)

### UXT-014 [P0] Customers are never given the seller's legal identity or any way to contact the shop

- **Why P0:** Legal breach (E-Commerce Regs 2002 reg 6 / CCR 2013 trader identity, address, cancellation info) and safety: every 'ask the kitchen' message is a dead end because all shops publish phone:null, email:null.
- **Members:** P2-REG-06 (Bola(P2), major, CONFIRMED); P05-1 (Priya(P1), major, CONFIRMED)
- **Repro:** Read a shop page and checkout → GET /api/v1/public/shops (phone and email are null for all 3 shops) → Read the order emails
- **Expected:** Trader identity, geographic address, phone or email, and a no-cancellation statement before purchase and on a durable-medium confirmation (CCR 2013 Sch 2 and reg 16)
- **Actual:** Trading name and premises only. No legal entity, company number or VAT-number fields exist. Emails come from noreply@jtoye.uk, are signed '— J'Toye', name no shop and say 'we'll deliver it'. Nothing is said about cancellation
- **Impact:** After a reaction the consumer can't reach the vendor, and believes J'Toye sold the meal. Advice, then an enforcement order, for both vendor and platform.
- **Existing issue:** none
- **Home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

### UXT-015 [P0] Stripe JS and fraud cookies load on a cash-only checkout, contradicting the cookie policy

- **Why P0:** Legal breach (PECR): non-essential third-party cookies (__stripe_mid, 1 year) are set on a page that takes no payment.
- **Members:** F-01 (Sam(P1), major, CONFIRMED); P2-REG-13 (Bola(P2), minor, CONFIRMED)
- **Repro:** Fresh browser, open any shop, add an item, go to /checkout → Inspect network and cookies
- **Expected:** No third-party requests or cookies on a cash-only checkout
- **Actual:** js.stripe.com and m.stripe.network load, m.stripe.com/6 is POSTed, __stripe_mid (1 year), __stripe_sid and m cookies are set; the page says 'No payment is taken online'
- **Impact:** Every cash customer is tracked by Stripe without consent, contrary to the published cookie policy.
- **Code (verified):** frontend/app/shop/[slug]/checkout/page.tsx:7 imports from '@stripe/stripe-js' (default entry injects js.stripe.com on import; the KEY guard at :107-108 cannot stop it)
- **Existing issue:** none
- **Home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

### UXT-016 [P0] Updating a product without quantityInStock silently turns stock tracking off

- **Why P0:** Silent data corruption: stock becomes unlimited on a routine PUT, so sold-out items can be oversold.
- **Members:** P2-RAV-03 (Ravi(P2), major, CONFIRMED)
- **Repro:** Create a product with quantityInStock=40 → PUT the product with the required fields + available=false, omitting quantityInStock → GET the product
- **Expected:** Omitted fields keep their values (as every other optional field does), or a PATCH endpoint exists
- **Actual:** quantityInStock 40 → null (null = unlimited/untracked) while description, category, shopId, imageUrl, featured and displayOrder are all preserved. PUT {available:false} alone → 400, because SKU, title, ingredients, allergen mask and price are required. PATCH → 405. The spec does not document the special case.
- **Impact:** A nightly EPOS or bot job that only flips availability wipes stock counts. When the item comes back it is unlimited and oversells. Every availability flip also has to resend the Natasha's-Law allergen fields, so a stale EPOS copy can overwrite the vendor's allergen data.
- **Code (unverified):** PUT /api/v1/products/{id} maps a missing quantityInStock to null (=untracked); no PATCH (member cites)
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

### UXT-017 [P0] Products created by /sync/batch belong to no shop, are orderable at any shop, and record the wrong allergens

- **Why P0:** User safety: an order line records no allergens for a dish whose real declaration has them.
- **Members:** P2-RAV-05 (Ravi(P2), major, CONFIRMED)
- **Repro:** Sync a product sku RAVI-EPOS-2 titled 'Jollof Rice', allergenMask 0 → GET /api/v1/products → shopId null; not on any storefront → MCP list_products shows two 'Jollof Rice' items (masks 0 and 256) → MCP create_order at Mama Ade's Kitchen with the orphan id → MCP read_orders orderId → line allergenMask
- **Expected:** Sync requires a shop and rejects unbound products with a typed error; an order line can't carry an unbound duplicate
- **Actual:** The orphan is created (confirms issue #727 live), is hidden from storefronts even though the spec says null = 'available on all tenant shops', is listed to agents and is accepted on a Mama Ade's order. The line records allergenMask 0, but Mama Ade's real Jollof Rice declares 256.
- **Impact:** The kitchen sees 'Jollof Rice x2', cooks the real dish, and the order's allergen record says none. That is a Natasha's-Law and safety exposure.
- **Code (unverified):** core-java/src/main/java/uk/jtoye/core/sync/SyncService.java upsertProduct never sets shopId (per #727)
- **Existing issue:** same #727 — #727 calls this latent (no producer). Pass 2 reproduced it live and found the safety consequence: an order for a duplicate-titled orphan records allergenMask 0 where the real dish declares 256. Ask to re-prioritise.
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

### UXT-018 [P0] A completed, paid order can be deleted, leaving ledger rows that point at nothing

- **Why P0:** Silent data corruption of financial records: the ledger lists sales whose orders return 404.
- **Members:** P2-KEM-07 (Kemi(P2), major, CONFIRMED)
- **Repro:** Complete an order (it creates a ledger row 'Order ORD-…') → DELETE /api/v1/orders/{id} as admin-user-b -> 204 → GET /api/v1/orders/{id} -> 404; GET /api/v1/financial-transactions still lists the sale
- **Expected:** A completed order cannot be deleted; at most it is refunded or voided with an audit trail
- **Actual:** 204 on 3 COMPLETED orders. The ledger still holds £48.00 against order numbers that return 404. The code requires only SHOP_MANAGER on the order's shop, so a site manager could also do this (manager role not exercised: SUSPECTED for that part).
- **Impact:** Takings and the books stop matching, and a manager could take cash, complete the order and delete it. This is an audit and theft risk for a cash-only business.
- **Code (verified):** core-java/src/main/java/uk/jtoye/core/order/OrderService.java:599-608 (deleteOrder checks only SHOP_MANAGER; no status guard)
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

### UXT-019 [P0] The platform's own registered office is not published anywhere on the site

- **Why P0:** Legal breach: the Companies (Trading Disclosures) Regulations require the registered office on the website; /legal/accessibility even says it is not published.
- **Members:** C-11 (Claire(P1), minor, CONFIRMED)
- **Repro:** Open /legal, /legal/privacy and /legal/accessibility
- **Expected:** Company name, number, registered office (and ICO registration reference) shown
- **Actual:** Name and number only; 'Registered office address not published'; no ICO number
- **Impact:** A regulator or B2B buyer finds a statutory disclosure missing; procurement questionnaires fail.
- **Code (verified):** frontend/Dockerfile:81 declares ARG NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE (31-08); it is set in no runtime (possibly config-only)
- **Existing issue:** none
- **Home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

### UXT-020 [P1] A new order makes no sound and never reaches the kitchen screen until someone confirms it on another page

- **Why P1:** Blocks the core vendor journey: orders sit unseen as PENDING while the board says 'No active orders'.
- **Members:** P2-FUN-01 (Funmi(P2), blocker, CONFIRMED); P2-TUN-01 (Tunde(P2), major, CONFIRMED)
- **Repro:** Log in as admin-user; open /dashboard/kitchen (Brixton Village Grill) on a tablet and tap the page once (audio gesture); open /dashboard/orders on a phone → Place a cash order at /shop/brixton-village-grill as a guest → Watch both screens for 40 s without reloading; instrument AudioContext/Oscillator, Notification, navigator.vibrate and document.title
- **Expected:** A new order announces itself loudly (repeating until acknowledged) on the kitchen screen and the counter phone, and appears on the kitchen board as NEW
- **Actual:** 7/7 orders: phone list showed the row in 1.1-5.1 s but with 0 beeps/notifications/vibrations and no title change; the kitchen board never showed any of them (it lists only CONFIRMED/PREPARING/READY). The only kitchen beep fires when an order is CONFIRMED (kitchen/page.tsx:496-499), i.e. in reaction to the vendor's own tap - 9 beeps during the test were other users' confirmations
- **Impact:** On a busy night an order nobody happens to see sits in Pending indefinitely; the customer was told 'the shop will confirm it shortly' and waits, then phones or goes to Just Eat. Worse than the WhatsApp buzz it replaces
- **Code (unverified):** frontend/app/dashboard/kitchen/page.tsx lists only CONFIRMED/PREPARING/READY; the only beep fires on CONFIRMED (:496-499, member cites)
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

### UXT-021 [P1] When the all-day kitchen tablet's session lapses the board silently becomes a sign-in page

- **Why P1:** Blocks the core vendor journey: every order after the 5-minute token expiry is never seen.
- **Members:** P2-TUN-06 (Tunde(P2), major, CONFIRMED)
- **Repro:** Sign in on a tablet, open Kitchen → End that browser's own Keycloak session (OIDC end-session with its id_token_hint — what ssoSessionMaxLifespan does) → Keep placing+confirming orders every ~100 s and watch
- **Expected:** A loud, unmissable 'signed out — board stopped' state (sound, red screen) and a return to Kitchen after re-login; ideally a session long enough for a shift
- **Actual:** Board worked until the 5-min access token expired, then the 60-s poll got 401 and the page navigated to the generic 'Vendor sign in' page with no sound or explanation; order ORD-…7E355E89 placed afterwards was never seen (4+ min on sign-in page). Re-login lands on /dashboard, not Kitchen. Realm template sets ssoSessionMaxLifespan=7200 (2 h) — live value not read (SUSPECTED). It does NOT freeze while showing 'Live' (good).
- **Impact:** Mid-service the kitchen screen goes quiet; nobody in a loud kitchen notices a login page; orders pile up until a customer complains.
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

### UXT-022 [P1] A vendor cannot pause or stop taking orders: no pause switch, no holiday closure, and free-text hours fail open and cannot be cleared

- **Why P1:** Blocks the core vendor journey: an overwhelmed or closed kitchen keeps receiving orders.
- **Members:** P2-FUN-06 (Funmi(P2), major, CONFIRMED); P2-CHA-10 (Nkechi(P2), minor, CONFIRMED); P2-KEM-11 (Kemi(P2), minor, SUSPECTED)
- **Repro:** Open /dashboard/shops; look for pause/busy/accepting-orders control → Open Edit Shop for Brixton Village Grill → Read ShopService.updateShop slug handling
- **Expected:** A one-tap 'Pause new orders for 20/40 min' that the storefront and checkout respect, with a customer-facing message
- **Actual:** No switch anywhere; Edit Shop has only free-text opening hours per day. Saving Edit Shop regenerates the slug (ShopService.java:269 generateSlug when request slug blank; the form never sends slug). Not saved on the shared shop to protect other testers
- **Impact:** Funmi cannot stop the flood without breaking every shared link/QR code; she will ignore orders instead, which is worse for customers
- **Code (unverified):** PublicStorefrontService.validateShopIsOpen treats unparseable/empty hours as open (member P2-KEM-11, SUSPECTED); update mapper ignores null hours (P2-CHA-10)
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-A Kitchen & order operations

### UXT-023 [P1] Vendors cannot set a delivery fee, free-delivery threshold or collection-only: orders are charged £0 delivery and collection-only shops take deliveries

- **Why P1:** Blocks the core vendor journey: every new-site delivery loses its fee and collection-only kitchens receive orders they cannot fulfil.
- **Members:** P2-KEM-10 (Kemi(P2), major, CONFIRMED); P2-CHA-08 (Nkechi(P2), minor, CONFIRMED); ADE-16 (Ade(P1), polish, CONFIRMED)
- **Repro:** Add a shop: fields are name, address, description, publish, tags, phone, email, Delivery Info (free text), Minimum Order, 7 hour boxes → Peckham Delivery Info = 'Delivery within 2 miles, £2.99, free over £25'; Lewisham = 'COLLECTION ONLY' → Create a DELIVERY order at each shop
- **Expected:** Delivery is charged at the shop's fee; a collection-only shop refuses delivery
- **Actual:** Both delivery orders are accepted with deliveryFeePennies 0. ShopDto and CreateShopRequest have no deliveryFeePennies/freeDeliveryThreshold field, although the storefront charges from the entity (default 0).
- **Impact:** Every delivery from a new site loses £2.99, and a collection-only kitchen receives delivery orders it cannot fulfil.
- **Code (unverified):** ShopDto / CreateShopRequest have no deliveryFeePennies or freeDeliveryThreshold (member P2-KEM-10)
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-G Catalogue & shop-admin correctness

### UXT-024 [P1] A second or third shop can never go live, while onboarding says 'Your storefront is live'

- **Why P1:** Blocks the multi-site vendor journey entirely.
- **Members:** P2-KEM-03 (Kemi(P2), blocker, CONFIRMED); ADE-10 (Ade(P1), major, CONFIRMED)
- **Repro:** Create kemi-p2-peckham and kemi-p2-lewisham on /dashboard/shops (both Draft) → Open /dashboard/onboarding: no shop picker, no way to start onboarding for a shop → POST /api/v1/onboarding {model:MARKETPLACE, shopId:<peckham>} → POST /api/v1/onboarding/submit and /go-live → GET /api/v1/public/shops/tenant-b-probe (the onboarded shop)
- **Expected:** Each site can be onboarded and published, and the onboarding page names the shop it is about
- **Actual:** 409 'An onboarding already exists for this tenant'; submit/go-live 400 'cannot apply event … in state LIVE'. The onboarding page (for Kemi and for her manager) says 'Live — Your storefront is live and visible to customers' but all 4 shops are Draft, and the onboarded shop's public URL returns 404.
- **Impact:** Peckham and Lewisham can only take orders that staff type in from the phone. No customer can find or order from them. Kemi pays for three sites and gets one, and the dashboard tells her she is live when she is not.
- **Existing issue:** same #452 — #452 gap 1 (no 2nd-shop onboarding path). New: the onboarding page tells owner and manager 'Your storefront is live' while all shops are Draft, and the vendor has no draft preview (ADE-10).
- **Home:** Phase 33 – The Consumer Product (CUST-02/CUST-04 still open)

### UXT-025 [P1] There is no way to invite a staff member: they must self-register, then auto-become Group admin

- **Why P1:** Blocks the vendor's staffing journey.
- **Members:** P2-KEM-14 (Kemi(P2), major, CONFIRMED)
- **Repro:** Open /dashboard/staff as admin-user-b
- **Expected:** Invite by email with a chosen shop and role
- **Actual:** Copy: 'Team members appear here only after they have signed in once… this page cannot send them an invite.' Vendor signup does not exist (pass-1 ADE-01), and on first sign-in the person becomes an implicit tenant-wide Group admin (P2-KEM-01/02).
- **Impact:** Kemi cannot hire a manager without contacting J'Toye (no contact route), and that manager starts with access to every site.
- **Existing issue:** same #452 — #452 gap 2 (no staff invite). New: combined with the JIT default, the only onboarding route for staff grants full admin.
- **Home:** Phase 33 – The Consumer Product (CUST-02/CUST-04 still open)

### UXT-026 [P1] CSV import ignores the selected shop and hides imported items from every storefront; a menu cannot be copied to another site

- **Why P1:** Blocks multi-site setup: imported items appear on no storefront, silently.
- **Members:** P2-KEM-09 (Kemi(P2), major, CONFIRMED); C-10 (Claire(P1), minor, CONFIRMED)
- **Repro:** Products: row actions are only label / edit / delete (no duplicate, no copy-to-shop, no menu export) → Set the header switcher to kemi-p2-lewisham, open Bulk Import, upload a 2-row CSV copied from the Peckham menu (shop_id blank, as the page lists no shop_id column) → View Products with switcher = kemi-p2-lewisham
- **Expected:** Either a 'copy menu to…' action, or the import lands in the selected shop
- **Actual:** Import immediately creates both rows with shopId null ('All Shops'). With Lewisham selected the list says '1 product in kemi-p2-lewisham': the copies are not there. Per the storefront code (getShopProducts filters shop_id = this shop), 'All Shops' items show on NO storefront. The CSV template has a shop_id column needing a UUID that the UI never displays, and the import page does not mention it.
- **Impact:** Kemi retypes every dish and allergen set for each site, which is slow and error-prone for allergens. Or she imports and her menu silently vanishes.
- **Code (unverified):** BulkImport creates shopId null; storefront getShopProducts filters shop_id = this shop (member P2-KEM-09)
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

### UXT-027 [P1] Per-shop dashboard and finance show the whole business's takings, and site managers get 'No financial data yet' instead of their shop's numbers

- **Why P1:** Blocks the multi-site owner's core journey: per-site takings are wrong while the banner says they are scoped.
- **Members:** P2-KEM-05 (Kemi(P2), major, CONFIRMED); P2-KEM-08 (Kemi(P2), major, CONFIRMED)
- **Repro:** Complete 2 Peckham orders (£22.50 + £9.50) and 1 Lewisham order (£16.00) → Set the shop switcher to kemi-p2-lewisham and open /dashboard and /dashboard/finance → GET /api/v1/financial-transactions/summary?shopId=<peckham>&from=2026-10-01
- **Expected:** Lewisham shows £16.00 revenue / £2.66 VAT; Peckham shows £32.00 / £5.33
- **Actual:** Dashboard banner: 'Viewing kemi-p2-lewisham — order activity below is scoped to this shop', but 'Revenue by VAT Category: £48.00 revenue, £7.99 VAT' (the whole tenant). /finance shows £48.00 and all 3 transactions for every switcher value. The API ignores shopId and from. Ledger rows have no shop.
- **Impact:** Kemi reads one site as having taken three sites' money. Per-site profit, staffing and VAT decisions are made on wrong numbers.
- **Code (unverified):** GET /api/v1/financial-transactions/summary ignores shopId and from; ledger rows carry no shop (member P2-KEM-05)
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

### UXT-028 [P1] Every shop update regenerates the public URL slug, so shared links and QR codes break

- **Why P1:** Blocks the customer's route to the shop: any edit (even opening hours) changes the public link.
- **Members:** ADE-03 (Ade(P1), major, CONFIRMED)
- **Repro:** Dashboard → Shops → Edit an existing shop → Change only the opening hours or description, Save → Compare the slug before and after
- **Expected:** Slug unchanged unless the vendor edits it
- **Actual:** New slug each save (a823cee3 → 7cd132a5 → 85a6253f); old link 404 (SUSPECTED once published)
- **Impact:** Printed QR codes, Instagram links and search results go dead after any edit.
- **Code (verified):** core-java/src/main/java/uk/jtoye/core/shop/ShopService.java:268-270 (regenerates slug whenever request slug is blank; the form never sends slug)
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-G Catalogue & shop-admin correctness

### UXT-029 [P1] The order confirmation is rendered in place at /checkout: off-screen, unannounced, and lost on refresh

- **Why P1:** Blocks the core customer journey: the customer cannot tell the order succeeded and may re-order.
- **Members:** F-03 (Sam(P1), major, CONFIRMED); P2-GRA-04 (Grace(P2), major, CONFIRMED); P2-MAR-01 (Marcus(P2), major, CONFIRMED)
- **Repro:** Add 2 items to a basket, open /shop/brixton-village-grill/checkout → Fill all fields with the keyboard, tick the allergen checkbox with Space → Focus "Place order · £25.00" and press Enter → Record live-region output, document.activeElement and document.title
- **Expected:** Focus moves to the "Order confirmed!" heading, or a status message says "Order confirmed, order number ORD-…"; the title changes to the confirmation
- **Actual:** Focus drops to <body>. The only live-region output is "0 items in basket" (the header basket link, aria-live=polite). The URL stays /checkout and the title stays "J'Toye — Discover Local Vendors". Reproduced twice: desktop delivery (ORD-…50F52C87) and iPhone 13 collection (ORD-…F82D641A).
- **Impact:** A blind customer cannot tell whether the order went through or the basket was simply cleared, so they phone the shop or re-order, and a duplicate cash order reaches the kitchen.
- **Existing issue:** related #409 — #409 fixed the confirmation never appearing; it still has no route of its own.
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

### UXT-030 [P1] Delivery is accepted to any UK postcode with no radius check at checkout

- **Why P1:** Blocks fulfilment: orders are taken that the kitchen cannot deliver.
- **Members:** J-01 (Jordan(P1), major, CONFIRMED); P2-KYL-05 (Kyle(P2), minor, CONFIRMED)
- **Repro:** POST a DELIVERY order to Mama Ade's (Peckham) with addressPostcode 'EH2 2AN' (Edinburgh), 2x jollof to clear the £10 minimum → -> 201, deliveryFee £3.50, total £21.48, PENDING
- **Expected:** A serviceability / radius check rejecting an address the kitchen cannot deliver to
- **Actual:** Any valid-format UK postcode is accepted with the flat delivery fee; no distance gate at order time (discovery computes distance but checkout ignores it).
- **Impact:** A vendor could confirm and plan to fulfil an order that is physically undeliverable, or dispatch a driver on a hopeless trip. Also a griefing vector: order 'delivery' to the far end of the country.
- **Existing issue:** same #460 — #460 'no delivery radius'. Phase 33 added radius DISCOVERY; checkout still accepts Belfast/Manchester/300+ miles for a London shop.
- **Home:** Phase 33 – The Consumer Product (CUST-02/CUST-04 still open)

### UXT-031 [P1] A prospective vendor has no way in: 'Start your application' dead-ends at a login, and no sales or support contact exists

- **Why P1:** Blocks vendor acquisition: both prospects gave up at this step.
- **Members:** ADE-01 (Ade(P1), blocker, CONFIRMED); C-05 (Claire(P1), major, CONFIRMED)
- **Repro:** Open / or /for-operators on a phone → Tap 'Start your application' → Look for a phone, sales email or demo booking anywhere
- **Expected:** An application form or a contact route that reaches a person
- **Actual:** Keycloak 'Sign in to your account' with no register or back link; the only email on the site is privacy@ on a non-brand domain
- **Impact:** Vendors who want to sign up cannot; evaluators walk away citing 'nobody to ring at 8pm Saturday'.
- **Existing issue:** related #102 — #102 covers the tenant lifecycle backend; the public application/contact route is untracked.
- **Home:** Phase 32 – Production Cutover + First Tenant

### UXT-032 [P1] No merchant terms, pricing page, VAT basis or card-fee figure: /legal/terms, /pricing, /contact and /about all 404

- **Why P1:** Blocks vendor signing: there is no contract to sign or price basis to agree to.
- **Members:** C-04 (Claire(P1), major, CONFIRMED); C-09 (Claire(P1), minor, CONFIRMED)
- **Repro:** Request /legal/terms, /pricing, /contact, /about → Read the terms card on /for-operators
- **Expected:** Merchant terms, a pricing page with VAT basis, term and card fees
- **Actual:** All four return 404; prices labelled 'PRICING TEST', no VAT basis, 'card fees shown transparently' with no figure
- **Impact:** A buyer cannot sign; procurement stops.
- **Existing issue:** none
- **Home:** Phase 32 – Production Cutover + First Tenant

### UXT-033 [P1] The published £39/location subscription has no billing built and no billing UI

- **Why P1:** Blocks the first paying tenant: the platform cannot collect its own fee.
- **Members:** C-02 (Claire(P1), major, CONFIRMED)
- **Repro:** Read /for-operators pricing → Look for billing in the vendor dashboard
- **Expected:** A subscription the vendor can start
- **Actual:** None; /competitive admits 'no SaaS subscription billing built'
- **Impact:** No revenue can be collected from vendors.
- **Existing issue:** same #102 — #102 'cannot bill'; Phase 30 PAY-02 closes its remainder.
- **Home:** Phase 30 – The Money Path, Executed

### UXT-034 [P1] Vendors have no payments or payouts surface (/dashboard/payments returns 404)

- **Why P1:** Blocks the vendor's core money journey: a vendor cannot see or receive card takings.
- **Members:** C-03 (Claire(P1), major, CONFIRMED)
- **Repro:** As a vendor open /dashboard/payments and /dashboard/payments/connect
- **Expected:** Connect onboarding status and payouts
- **Actual:** 404; Connect reachable only via TenantAdminController POST
- **Impact:** Vendors cannot be paid for card orders.
- **Existing issue:** same #102 — #102 'cannot pay-out'; Phase 30 PAY-03.
- **Home:** Phase 30 – The Money Path, Executed

### UXT-035 [P1] A vendor has no way to give a developer API credentials; the only path is the owner's password plus the confidential core-api client secret

- **Why P1:** Blocks the integrator journey and forces credential sharing of a full admin token.
- **Members:** P2-RAV-01 (Ravi(P2), blocker, CONFIRMED)
- **Repro:** Log in as admin-user and inspect the dashboard nav → Probe /dashboard/developers, /dashboard/api, /dashboard/settings, /dashboard/integrations, /developers, /docs → Read docs/security-scopes.md and decode tokens of integration-catalog-ro / integration-orders-rw
- **Expected:** A Developers/API page where the owner creates a scoped, revocable client for their own tenant
- **Actual:** No developer surface (all 6 probes 404). The only machine clients come from a realm import, hard-wired to tenant A's UUID, and the vendor can't see or rotate them. The integrator must use the owner's admin password through a password grant, which also needs the confidential core-api client secret. The result is a full admin token (catalog:write, orders:write, customers:write) with a 300 s lifetime.
- **Impact:** Vendors hand their admin password to freelancers or don't integrate at all. Nothing can be revoked per developer, and least privilege can't be achieved without a platform operator, which this platform does not have.
- **Existing issue:** related #206 — #206 delivered scoped client scopes; there is still no per-tenant issuance or rotation surface.
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

### UXT-036 [P1] An order taken by an AI agent through MCP stays DRAFT and the kitchen never sees it

- **Why P1:** Blocks the agent ordering journey: the customer is told the order exists, the kitchen never cooks it.
- **Members:** P2-RAV-04 (Ravi(P2), major, CONFIRMED)
- **Repro:** MCP tools/call create_order with a valid shop/product/idempotencyKey → Observe status → Check the MCP tool list for a submit/confirm tool and the kitchen endpoint contract
- **Expected:** The agent's order reaches the kitchen (PENDING/CONFIRMED), or the tool clearly says a human must submit it
- **Actual:** status DRAFT. No MCP tool can submit or confirm it. The kitchen endpoint returns only CONFIRMED/PREPARING/READY. A DRAFT emits no webhook event. The tool description does not mention DRAFT.
- **Impact:** ChatGPT/Claude tells the customer 'order placed', nothing is cooked, and the customer arrives to collect food that doesn't exist.
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

### UXT-037 [P1] /sync/batch has no stock or availability field and silently skips unknown items while reporting SUCCESS

- **Why P1:** Blocks EPOS integration; dropped items vanish without trace.
- **Members:** P2-RAV-06 (Ravi(P2), major, CONFIRMED)
- **Repro:** POST 3 product items + 1 item {type:'stock',sku,quantity:0}
- **Expected:** Stock and availability can be synced. Rejected or unknown items are reported per item (or the batch fails typed).
- **Actual:** 202 {status:SUCCESS, processed_count:3}. The 4th item was dropped without a trace. SyncItem fields are type, sku, title, ingredientsText, allergenMask, pricePennies, name and address only.
- **Impact:** A nightly EPOS job looks green while doing nothing for stock, and the vendor finds out from customers.
- **Existing issue:** related #727 — Same endpoint as #727; the silent drop is a separate defect.
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

### UXT-038 [P1] Webhook endpoints auto-pause after ~46 s of failures and retries stop after 5 attempts, with nobody told

- **Why P1:** Integrations silently lose events after a brief receiver outage.
- **Members:** P2-RAV-10 (Ravi(P2), major, CONFIRMED)
- **Repro:** Subscribe https://example.com/ravi-gsheet-hook to Orders (it returns 405) → Let two order events occur → Read the delivery log and the subscription status; search Mailhog
- **Expected:** Each event gets its documented 8 attempts over about 2 min, and the owner is notified when the endpoint is paused
- **Actual:** Two events × 5 attempts = 10 consecutive failures → AUTO_PAUSED at 22:14:24 (first event 22:13:38); the remaining attempts were terminally FAILED with 'subscription not active: AUTO_PAUSED'. Mailhog: 0 messages.
- **Impact:** On a busy Friday a one-minute receiver blip stops all order pushes until someone happens to open the Webhooks page.
- **Existing issue:** same #587 — #587 (127 s window before events are lost). New: auto-pause trips at ~46 s, only 5 of 8 attempts run, and no email/notification is sent.
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

### UXT-039 [P1] Anonymous traffic can exhaust the edge gateway's single process-wide rate limit and block every vendor's sync; its 429 is untyped

- **Why P1:** Blocks every vendor's integrations at once (cross-tenant denial of service).
- **Members:** P2-RAV-12 (Ravi(P2), major, CONFIRMED)
- **Repro:** Send 70 parallel unauthenticated POSTs to :8089/api/v1/sync/batch → Inspect a 429 response
- **Expected:** Per-tenant limiting after auth; a typed RFC 7807 429 with Retry-After and rate headers (as Core does)
- **Actual:** 41×401, 29×429. The body is {"error":"rate limit exceeded"} with no Retry-After and no X-RateLimit headers. It is one process-wide bucket (20 rps, burst 40) applied before auth, and the edge spec does not list 429.
- **Impact:** Anyone flooding the edge without a token makes every vendor's nightly sync fail, and clients can't back off properly.
- **Existing issue:** related #413 — #413 typed Core's 429; the edge 429 is still hand-rolled and the bucket is shared before auth.
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-D Abuse resistance

### UXT-040 [P1] Asked for one shop's menu, an AI agent gets every product in the business with allergens only as an integer

- **Why P1:** Blocks correct agent ordering: unavailable, other-shop and orphan items are offered, and allergens are undecoded.
- **Members:** P2-RAV-14 (Ravi(P2), major, CONFIRMED)
- **Repro:** tools/call list_products {shopId:<Mama Ade's>} → tools/call list_products {size:100}
- **Expected:** A shopId/search filter (Core supports shopId), only orderable items, and allergen names
- **Actual:** shopId is silently ignored. The call returns all 30 tenant products, including the unpublished shop, other personas' test items, unavailable items and shop-less orphans. Allergens come back as an integer bitmask with no decoding.
- **Impact:** The agent lists dishes the shop doesn't sell and can't safely answer 'does it contain nuts?'.
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-E Integrator surface (API, MCP, webhooks, sync)

### UXT-041 [P1] Anyone can place many fake cash orders in seconds with throwaway contact details

- **Why P1:** Blocks normal trading: a flood of fake orders lands on the kitchen looking real.
- **Members:** P2-KYL-01 (Kyle(P2), major, CONFIRMED)
- **Repro:** Script a loop of 10 POSTs to /public/shops/mama-ades-kitchen/orders → Each with a random throwaway email and a junk phone ('call me', 'no', 'x', '0', '1') → fulfilmentType COLLECTION, one real product id, no auth, no browser
- **Expected:** Something slows a burst of guest orders down: a per-email / per-phone / per-device cap, email verification before the kitchen sees it, a captcha, or a hold queue
- **Actual:** All 10 returned 201 PENDING in 311 ms total. No verification, no captcha, no per-identity cap. They appear in the vendor's Orders list indistinguishable from real orders.
- **Impact:** A small vendor prepping to a cash-on-collection screen cooks food for orders nobody collects. One teenager with a for-loop can bury a real Friday rush under junk and burn an evening of ingredients and prep time. The only cost to the attacker is typing.
- **Existing issue:** related #461 — #461 (payment links before production) removes the zero-cost order; until then nothing bounds it.
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-D Abuse resistance

### UXT-042 [P1] Item quantity has no upper bound: a £19 billion order is accepted and shown on the dashboard

- **Why P1:** Absurd orders reach the kitchen and pollute revenue figures.
- **Members:** P2-KYL-03 (Kyle(P2), major, CONFIRMED)
- **Repro:** POST a guest order with items:[{productId: <jollof>, quantity: 999}] -> 201, total £8,981.01 → POST again with quantity: 2147483647 -> 201, total £19,305,877,986.53 → Both appear as PENDING in the vendor Orders list
- **Expected:** A sane per-line quantity ceiling (and a basket-total sanity cap) rejecting obvious garbage
- **Actual:** Quantity is only validated as >= 1 (negatives, 0 and empty baskets are correctly rejected 400). There is no maximum, so absurd quantities create real orders and real line totals.
- **Impact:** Pollutes the kitchen queue and the finance ledger with impossible numbers; a vendor glancing at 'Total' sees £8,981 or £19bn against their shop. Confuses takings, breaks any revenue chart, and wastes time working out it is a prank.
- **Code (verified):** core-java/src/main/java/uk/jtoye/core/storefront/dto/GuestOrderItemRequest.java:13 (@Min(1) only, no @Max)
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-D Abuse resistance

### UXT-043 [P1] An allergy request is a generic free-text note: no alert to the vendor, no acknowledgement, never echoed to the customer

- **Why P1:** Blocks the allergic customer's journey: a request to confirm goes to COMPLETED with no flag or reply.
- **Members:** P05-8 (Priya(P1), minor, CONFIRMED); P2-REG-10 (Bola(P2), minor, CONFIRMED)
- **Repro:** Order with notes 'My child is allergic to peanuts and sesame - please confirm.' → Vendor confirms and completes it
- **Expected:** An allergy note is flagged, needs a vendor response, and the response is recorded
- **Actual:** Shown as plain NOTES; nothing records that anyone read it
- **Impact:** An allergy request can be missed with no trace.
- **Existing issue:** none
- **Home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

### UXT-044 [P1] Reviews publish the reviewer's full checkout name with no notice, policy or moderation

- **Why P1:** Data-protection risk: full names are published (and in the public API) without transparency; reachable today via the API.
- **Members:** P2-GRA-07 (Grace(P2), major, CONFIRMED); P2-REG-12 (Bola(P2), minor, CONFIRMED)
- **Repro:** Create a review for a completed order → Open the shop page signed out
- **Expected:** First name or initial, or a stated choice
- **Actual:** 'Grace Persona14' (first + last name from the order) shown publicly and in the public API, with no notice
- **Impact:** Exposes customers' full names to the internet; vulnerable or older customers may be identifiable alongside their locality
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-C Checkout integrity & customer trust/retention

### UXT-045 [P1] The local compose stack can never exercise the card-payment path (Stripe env-var names and build-time key mismatch)

- **Why P1:** Blocks testing of the core money journey: every local run, persona test and E2E is cash-only.
- **Members:** COORD-01 (Coord, major, CONFIRMED)
- **Repro:** Start the stack with docker compose -f docker-compose.full-stack.yml up with the repo .env → docker exec the core-java container and print STRIPE_API_KEY → grep the frontend container's /app/.next/static for pk_test → Open any shop's checkout
- **Expected:** With test-mode keys in .env, core can create PaymentIntents and the browser bundle carries the publishable key, so the card path can be exercised locally
- **Actual:** core-java reads STRIPE_API_KEY (docker-compose.full-stack.yml:368) but .env provides STRIPE_SECRET_KEY, so it is empty in the running container. NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY is inlined at BUILD time (frontend/app/shop/[slug]/checkout/page.tsx:107-108) but frontend/Dockerfile declares no ARG for it, so the runtime environment value at compose line 528 can never reach the browser; no pk_test in /app/.next/static. Every shop is cash-only.
- **Impact:** Every persona test and local E2E run is cash-only; the money path (capture, 3DS, decline, refund) is unexercisable locally, so card defects can only surface in staging or production.
- **Code (verified):** docker-compose.full-stack.yml:368 (STRIPE_API_KEY) vs .env STRIPE_SECRET_KEY; frontend/Dockerfile has no ARG NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY; checkout/page.tsx:107-108 inlines it at build
- **Existing issue:** related #461, #538, #61 — #461/#538/#61 all assume keys can be supplied; none records that compose drops them.
- **Home:** Phase 30 – The Money Path, Executed

### UXT-046 [P1] Products have no VAT-rate choice; everything is booked as Standard 20%

- **Why P1:** Every zero-rated (cold takeaway) item is booked at 20% VAT in the vendor's records.
- **Members:** P2-KEM-15 (Kemi(P2), minor, CONFIRMED)
- **Repro:** Open Add Product and list the fields
- **Expected:** Choose zero-rated vs standard (cold takeaway items, some drinks)
- **Actual:** No VAT field; all created products are vatRate STANDARD.
- **Impact:** Over-declared VAT on zero-rated cold items, which the brother can only fix outside the system.
- **Existing issue:** related #81 — #81 fixed the hardcoded STANDARD in the ledger code; the vendor still has no way to set a product's rate, so the outcome persists.
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

### UXT-047 [P1] 'VAT (incl. 20%)' is shown for every vendor, with no VAT-registration status or number captured

- **Why P1:** Likely legal exposure for non-VAT-registered vendors (pending legal confirmation; P0 if confirmed).
- **Members:** P2-REG-08 (Bola(P2), major, CONFIRMED)
- **Repro:** Check out at Mama Ade's (onboarding says 'no company number — sole trader')
- **Expected:** VAT shown only for VAT-registered vendors, with a VAT number
- **Actual:** 'VAT (incl. 20%) £3.83' is shown; no VAT number and no field for one. The legal effect needs legal confirmation
- **Impact:** An unregistered sole trader appears to charge VAT (HMRC risk).
- **Existing issue:** none
- **Home:** NEW: Phase 37 – Real-world operations readiness (persona findings) · 37-B Multi-site, staff access & finance

### UXT-048 [P1] Menu cards show allergens as an unnamed count, 'Add' works without seeing them, and 'none declared' is never stated

- **Why P1:** Blocks the allergic customer's journey: she must open every dish, and a missing section reads as reassurance.
- **Members:** P05-4 (Priya(P1), major, CONFIRMED); P2-MAR-11 (Marcus(P2), minor, CONFIRMED); P05-5 (Priya(P1), major, CONFIRMED); P05-11 (Priya(P1), minor, CONFIRMED)
- **Repro:** Open /shop/brixton-village-grill → Read a card's allergen badge (also with a screen reader) → Tap 'Add' on a card; open Peri Peri Chicken's detail; repeat at 200% zoom
- **Expected:** Named allergens on the card with an accessible name; an explicit 'No allergens declared' for empty items; allergens visible before Add
- **Actual:** Card shows '△2' read as 'Halal Spicy £8.50 2'; Tab reaches Add before details; no allergen section for nothing-declared items; at 200% the box is below the modal fold
- **Impact:** An allergic customer adds dishes without seeing what is in them.
- **Existing issue:** none
- **Home:** Phase 31 – Consumer-Safety and Legal Floor (complete: reopen as gap-closure 31.1)

## Proposed Phase 37 — Real-world operations readiness (persona findings)

Clusters whose goal no existing phase genuinely covers. Grouped into sub-themes so each can be planned as a wave:

- **37-A Kitchen & order operations** (15): UXT-011, UXT-020, UXT-021, UXT-022, UXT-049, UXT-050, UXT-051, UXT-052, UXT-053, UXT-054, UXT-055, UXT-088, UXT-089, UXT-099, UXT-101
- **37-B Multi-site, staff access & finance** (12): UXT-003, UXT-004, UXT-018, UXT-026, UXT-027, UXT-046, UXT-047, UXT-058, UXT-087, UXT-098, UXT-100, UXT-117
- **37-C Checkout integrity & customer trust/retention** (23): UXT-007, UXT-008, UXT-029, UXT-044, UXT-056, UXT-057, UXT-074, UXT-075, UXT-076, UXT-077, UXT-078, UXT-086, UXT-093, UXT-102, UXT-103, UXT-104, UXT-105, UXT-106, UXT-107, UXT-108, UXT-110, UXT-111, UXT-116
- **37-D Abuse resistance** (8): UXT-005, UXT-006, UXT-039, UXT-041, UXT-042, UXT-072, UXT-073, UXT-118
- **37-E Integrator surface (API, MCP, webhooks, sync)** (13): UXT-016, UXT-017, UXT-035, UXT-036, UXT-037, UXT-038, UXT-040, UXT-094, UXT-095, UXT-096, UXT-097, UXT-122, UXT-123
- **37-F Accessibility** (6): UXT-080, UXT-081, UXT-082, UXT-083, UXT-084, UXT-085
- **37-G Catalogue & shop-admin correctness** (8): UXT-023, UXT-028, UXT-062, UXT-063, UXT-064, UXT-065, UXT-066, UXT-115
- **Unassigned to a sub-theme (feature gaps)** (2): UXT-120, UXT-121

Existing phases receiving clusters: Phase 30 (money path), Phase 31 (complete — reopen as gap-closure for legal/allergen/privacy), Phase 32 (GTM content, vendor way-in), Phase 33 (#452/#453/#460/#545), Phase 34 (#507), Phase 29 (deploy-time guard).

## Test data needing operator cleanup

**Pass 1 — Ade (01)**
- Tenant-A products c49a371c-c7f3-42a3-bb9e-5f901db98e3a (ADE-SUYA) and 0254c57e-f3b1-4664-aa8f-04a88e73a244 (ADE-JOL) + their media assets: available=false, shopId=null, cannot be deleted (UXT-063). Shop d46fe43f-… already deleted.

**Pass 1 — Jordan (04)**
- Pending cash orders ORD-00000000-20261003-3FD79CC4, -B090D3F3, -6CC30687 (customer cannot cancel).
- Customer jordan-60809@example.test.

**Pass 1 — Priya (05)**
- ORD-00000000-20261003-8839B69F at Brixton Village Grill, PENDING, guest priya-05-9250@example.test.

**Pass 1 — Sam (06)**
- Customer sam-cadcb314@example.test.
- Six cash orders at Mama Ade's: ORD-00000000-20261003-{BB9D0024, 57EB73A4, 27082946, 40C5FC61, 7BF67717, 888A1F58} — cancel from the vendor side. No DSAR lodged.

**Pass 1 — Claire (08)**
- Not created by the persona, but flagged: public 'E2E launch offer — E2E 20% OFF' promo on Mama Ade's, Pending test orders 11–27 days old, a real person's name + Hotmail address on the dashboard, a `<b>x</b>` test customer (UXT-068).

**Pass 1 — Dele (07)**
- None: his two webhook subscriptions were created and deleted.

**Pass 2 — Funmi (11)**
- None outstanding: 9 orders Completed/Cancelled; Sweet Potato Fries available again; Peri Peri Chicken back to £9.00 (verified on the public API).

**Pass 2 — Kemi (12)**
- 3 ledger rows that cannot be deleted (£48.00 revenue, £7.99 VAT) for deleted orders …85C353C4, …24C38E33, …AE561258 (no ledger delete).
- Bayo (tenant-b-user) is back to one auto-created JIT GROUP_ADMIN row (new row id).

**Pass 2 — Tunde (13)**
- None outstanding: 12 orders COMPLETED, TUN-TEST-1 deleted, webhook 41331306… revoked, test customer deleted, self-granted STAFF row revoked.

**Pass 2 — Grace (14)**
- Customer grace-p2-65308@example.test (jtoye-customers) still active — no self-serve close and the erasure did nothing.
- Public review b2e8cff0-3b9b-40b1-ba42-d7de4b218a2f on Mama Ade's ('…persona test P2-GRA') — no delete path; operator removal needed.
- Two dsar_request rows: ACCESS outstanding; ERASURE marked completed with nothing erased.
- Orders ORD-00000000-20261003-45B3ECD7 (COMPLETED) and -ECFB3041 (CANCELLED) remain.

**Pass 2 — Marcus (15)**
- Customer marcus-15-7731@example.test (no self-serve delete).
- Orders ORD-00000000-20261003-50F52C87 and -F82D641A cancelled via the vendor API.

**Pass 2 — Kyle (16)**
- 16 kyle-p2 orders remain as CANCELLED (Kyle reports no vendor delete in the UI; DELETE /api/v1/orders/{id} exists per P2-KEM-07 but was not used).

**Pass 2 — Bola (17)**
- Order ORD-00000000-20261003-94ED4703 (id 8dfc7130-570b-4dbc-aa7a-b6705a79886c), COMPLETED, 'TEST PURCHASE (persona 17)'.
- Public 5★ review e1ca2396-a94c-4094-8cdf-59a0f3c5ae86 on Brixton Village Grill ('bola-17 cross-shop probe') — gives Brixton a public rating and JSON-LD aggregateRating; operator removal needed.

**Pass 2 — Ravi (18)**
- Webhook subscription 156f721d-7f6f-426d-84a7-1ea248730d6f (https://example.com/ravi-gsheet-hook) REVOKED but listed (no delete endpoint) + its 2 FAILED delivery rows (30-day retention).
- Outbox/webhook events already emitted for his 8 deleted orders.

**Pass 2 — Nkechi (19)**
- 11 chaos-p2 products (CHAOS-P2-SUYA, -PUFF, -ZOBO, -X1..X8) set available=false; deletion refused (409, referenced by cancelled orders).
- Peckham Jollof Co. openingHours is now {} instead of null (same behaviour; restoring null needs a DB write).
- 6 orders CANCELLED (ORD-…-1E4A123A, -D2F46664, -DA806F54, -39B2CE9E, -DF8F5148, -25D31E04).
