# Persona 17: Bola, Environmental Health / Trading Standards officer (pass 2)

## Persona
- **Who:** Bola, 50. A local-authority officer with 20 years in food standards and fair trading.
- **Context:** She is following up a complaint. A child had an allergic reaction after a takeaway ordered "through some platform". She needs three answers:
  1. What was the customer told before buying?
  2. What arrived with the food?
  3. Can the vendor produce records?
- **Device:** office desktop, 1280–1366 px, Chrome.
- **Method:**
  - She checked the three published storefronts.
  - She made an anonymous test purchase, a cash delivery order from Mama Ade's Kitchen.
  - She then sat with the vendor (admin-user, tenant A) and asked for records.
- **Scope of "law" below:** what she would raise in practice. Anything she is unsure of is marked **needs legal confirmation**. Nothing here is a formal legal opinion.

## Journey narrative
1. **Shop list** (`shots/01-shop-list.png`, `txt_01-shop-list.txt`). Each card shows the shop name, premises address, minimum order, delivery fee and the free-delivery threshold. The footer reads "© 2026 J'Toye Digital Ltd · Registered in England & Wales · company no. 16471464" and "Allergen info available on all products". There's no hygiene rating on any card.
2. **Each shop page** (`shots/02-shop-*.png`, `txt_02-shop-*.txt`). Each page gives a trading name, premises address, fees and per-dish dietary chips. For all three shops she found:
   - no phone or email (the public API returns `"phone":null,"email":null`);
   - no proprietor or legal entity;
   - no FHRS rating;
   - no VAT number;
   - no terms of sale.

   Mama Ade's shows a banner: "E2E launch offer — New this week — 20% off selected dishes", with a chip "E2E 20% OFF". No dish is marked as discounted.
3. **Dish detail** (`shots/10-dish-detail-egusi.png`). The ingredients have the allergens in bold, with named chips. Fried Plantain declares nothing, and its detail shows no "none declared" statement (`shots/10b-dish-detail-plantain-none-declared.png`; RECONFIRMS P05-5).
4. **Basket** (`shots/12-basket.png`). Jollof Rice £8.99 and Egusi Soup £10.50, subtotal £19.49. "Delivery from £3.50: Added at checkout, where collection is free and delivery is free over £25.00." No discount was applied.
5. **Checkout** (`shots/15-checkout-acked.png`, `txt_15-checkout-acked.txt`):
   - Subtotal £19.49, Delivery £3.50, "VAT (incl. 20%) £3.83", Total £22.99.
   - "Pay on delivery — cash to the driver. No payment is taken online."
   - "Allergens in this order — These items are prepared by Mama Ade's Kitchen. Based on what the kitchen has declared, this order contains: Crustaceans, Fish, Celery."
   - A required tick: "I have read the allergen information for this order."
   - No cancellation statement, no terms link, no trader phone or email.
   - She typed an allergy note: "My child is allergic to peanuts and sesame - please confirm."
6. **Confirmation** (`shots/16-confirmation.png`). "Order confirmed! Order ORD-00000000-20261003-94ED4703 · Pay on delivery", plus the totals. It shows **no shop name**, no items and no allergens.
7. **Network capture** (`order-post-log.json`). The order POST carried the items, contact details and notes, but **no acknowledgement field**. The 201 response included `shopName` and `allergenWarnings: []`; the page rendered neither.
8. **Emails** (`mail-test-purchase-after.json`). Five plain-text emails arrived: Received, Confirmed, Being Prepared, Ready, Completed.
   - All are from `noreply@jtoye.uk` and signed "— J'Toye".
   - None names the shop, the items or the allergens.
   - "Ready" says "There's no need to come to the shop — we'll deliver it to the address on your order."
9. **Vendor records** (admin-user). Order detail is at `shots/21-vendor-order-detail.png`; the API data is in `order-detail-before.json` and `order-detail-after.json`.
   - The vendor sees "ALLERGENS Crustaceans, Fish, Celery", a per-line snapshot (Jollof Rice → Celery; Egusi Soup → Crustaceans, Fish) and the note verbatim.
   - The only time shown is "Created Oct 3, 2026, 11:10:25 PM", in US date format.
   - After she walked the order through confirm → start preparation → mark ready → complete (`status-progression.txt`), the only fields that changed were `status` and `updatedAt`. There is no record of who did each step or when.
   - The completed cash order still says "PAYMENT STATUS NONE / METHOD Unpaid".
   - There is no export button anywhere in Orders, and guest orders create no customer record, so the per-customer GDPR export can't reach them either.
10. **Labels** (`label-*.pdf`, `shots/30-label-egusi-1.png`, `shots/31-label-jollof-1.png`). A per-product label shows the name, the SKU, the price, the ingredients with allergens in **bold** ("dried fish, crayfish"; "chicken stock (celery)"), "Use by: 5 Oct 2026", and "Mama Ade's Kitchen, 48 Rye Lane, Peckham, London SE15 5BS". It is a good prepacked-for-direct-sale (PPDS) format. But it is per product, not per order, and nothing in the order flow prompts the vendor to print it. The kitchen ticket carries allergens too, but it is a prep ticket for the rail, not a customer copy.
11. **Onboarding** (`txt_23-vendor-onboarding.txt`):
    - "Every mandatory check must pass before your storefront can go live."
    - Food hygiene rating: "No FSA establishment matched the shop name/address — Manual review".
    - Business verification: "no company number — sole trader — Not applicable".
    - The shop is live regardless.
12. **Reviews.** No shop had any reviews. Probes are in `review-probes-pending.txt` and `review-probe-crossshop.txt`.
    - A review on a pending order was refused ("Can only review completed orders").
    - A wrong email was refused ("You can only review your own orders").
    - **After completion, her Mama Ade's order was accepted as a 5-star review of Brixton Village Grill**, a shop she never bought from (HTTP 201). Brixton now shows "★ 5 (1)" and "Customer reviews (1) — Bola Regulator-Test — bola-17 cross-shop probe", and its JSON-LD carries `aggregateRating ratingValue 5, reviewCount 1` (`shots/41-brixton-review-viewport.png`, `shots/40-brixton-with-crossshop-review.png`, `reviews-brixton-after.json`).
    - A second review for the same order was refused ("You have already reviewed this order").
    - There is no review form anywhere in the customer UI. Reviews can only arrive through the raw API.
13. **Legal pages** (`txt_03-legal_*`):
    - `/legal` names J'Toye Digital Ltd and its company number, with only `privacy@olajay.co.uk` as a contact. There's no registered-office address and no VAT number. It says vendor shops "remain responsible for their own trading disclosures", but the platform gives them no field to make those disclosures.
    - `/legal/terms`, `/about` and `/contact` return 404.
    - The privacy notice says the shop is named "on the shop page and again on your order confirmation". The confirmation page does not name it.
    - The retention schedule is candid: "Audit history … used to answer who changed what and when … Kept indefinitely". Order records are kept "For as long as the law requires", with no figure given.
14. **Cookies** (`state-guest.json`). The cash-only checkout set `__stripe_mid`, `__stripe_sid` and `m` (m.stripe.com). RECONFIRMS F-01.

## First impressions (first 10 seconds)
It is clean and calm. Fees are up front, and the platform's own company number is in the footer, which is better than most. Then she looked for whom she would actually write to about a meal. She found a trading name and a market-stall address, with no person, no phone and no email.

## Trust, clarity, speed, mobile
- **Trust:** mixed.
  - The allergen gate at checkout and the vendor's per-line snapshot are better than she usually sees.
  - Everything after "Place order" reads as if J'Toye is the seller, and the record cannot prove the most important fact: that the customer saw and accepted the allergen warning.
- **Clarity:** the price is very clear. Who the seller is, is unclear.
- **Speed:** quick on desktop. It isn't her concern.
- **Mobile:** not assessed; this was desk work.

## Expectations vs reality
| Expected (as a regulator) | Got | How it felt |
|---|---|---|
| Seller's identity, address and contact before purchase (CCR 2013 Sch 2; E-Commerce Regs 2002 reg 6) | Trading name and premises address only. No phone, no email, no legal entity | "Who do I write to?" |
| Allergen info before purchase (FIC Art 14 / FIR 2014) | Per dish in the detail view, plus the aggregate at checkout with a required tick | Good |
| Allergen info at delivery | Nothing customer-facing: 5 emails, confirmation and tracking say nothing | The gap I'd write about |
| Proof the customer acknowledged the allergens | Not sent to the server and not stored | The vendor loses their due-diligence evidence |
| Who prepared it, and when | Only Created and Updated. No actor, no timeline, no export | Can't answer my question |
| Advertised discount honoured | "20% off" banner, but full price charged | Misleading action |
| Reviews only from real buyers of that shop | Buyer of shop A can review shop B | Fake-review vector |
| Total price incl. delivery before commitment, no drip | Yes, consistently | Good |
| No-cancellation statement for perishable food (CCR reg 28 / Sch 2) | Nothing said about cancellation | Advice letter |
| FHRS shown (voluntary in England) | Not shown; registration match failed but the shop is live | Advice, plus a question about FBO registration |

## Findings
| ID | Severity | Status | Finding | Who's exposed / seriousness | Evidence |
|---|---|---|---|---|---|
| P2-REG-01 | major | CONFIRMED | A completed order at one shop can be used to post a 5★ review on a **different** shop of the same tenant. It is public and feeds Google rich results. `ReviewService.createReview` checks the email, COMPLETED status and tenant, but never `order.shopId == shop.id` | Platform primarily. Vendor if exploited. DMCC Act 2024 Sch 20 (fake/misleading reviews; reasonable-steps duty). CMA direct-enforcement risk | `review-probe-crossshop.txt`, `reviews-brixton-after.json`, `shots/41-*`, `shots/40-*` |
| P2-REG-02 | major | CONFIRMED | The allergen acknowledgement is client-only: not in the order POST, not stored, absent from the order record and the backend. Orders through the API or MCP skip it entirely | Vendor (loses the Food Safety Act 1990 s.21 due-diligence evidence) and platform. Advice; it weakens the defence in any prosecution | `order-post-log.json`, `order-detail-after.json` |
| P2-REG-03 | major | CONFIRMED | Records request fails: no status timeline, no "who" for confirm/prepare/ready/complete, no order export. The audit history (Envers `revinfo.user_id`) exists and is "kept indefinitely", but has no UI or API (`AuditService` is used nowhere) | Vendor (can't produce records). Platform (holds them but offers no route out). Advice / records request unanswerable | `order-detail-*.json`, `status-progression.txt`, `shots/21-*`, `txt_03-legal_legal_retention.txt` |
| P2-REG-04 | major | CONFIRMED | No allergen info reaches the customer at or after delivery: none of the 5 lifecycle emails, the confirmation or the tracking page. Per-product labels exist but aren't tied to orders. RECONFIRMS P05-2; new: all 5 emails checked, including "Ready / we'll deliver" | Vendor (FBO duty: distance-selling allergen info at delivery, FIC Art 14 / FIR 2014; needs legal confirmation on exact regulation numbers). **Improvement notice** territory for the vendor. Platform is contributory | `mail-test-purchase-after.json`, `shots/16-*`, `shots/17-*` |
| P2-REG-05 | major | CONFIRMED | "20% off selected dishes" is advertised, but no dish is marked and the full price is charged (Jollof £8.99 in the basket, at checkout and in the order record). Order pricing has no promotion logic; promotions are display-only. The promo is E2E seed data, but the mechanism is real | Vendor (who set it) and platform (renders an offer the system can't honour). CPUTR 2008 reg 5 / DMCC Act 2024 Part 4 Ch 1, misleading action. Advice or warning first | `shots/02-shop-mama-ades-kitchen.png`, `txt_12-basket.txt`, `txt_15-checkout-acked.txt`, `order-detail-after.json` |
| P2-REG-06 | major | CONFIRMED | The seller's identity is never given in law-usable form. No phone or email for any shop. No field exists for a legal name, company number or VAT number. Emails come from J'Toye, are signed "— J'Toye", name no trader and say "we'll deliver". No cancellation statement. RECONFIRMS P05-1, J-06, F-11, C-11; new: regulatory framing and the missing data fields | Both. CCR 2013 Sch 2 and reg 16 (durable-medium confirmation); E-Commerce Regs 2002 reg 6 (needs legal confirmation on who the "service provider" is for a shop page); CPUTR material information in an invitation to purchase. Civil, so advice then an enforcement order; not prosecution | `txt_02-*`, `txt_15-*`, `mail-test-purchase-after.json`, public shops API |
| P2-REG-07 | minor | CONFIRMED | The privacy notice says the shop is named "again on your order confirmation". The confirmation page names no shop | Platform. A transparency inaccuracy (UK GDPR Art 12/13). Advice | `txt_16-confirmation.txt`, `txt_03-legal_legal_privacy.txt` |
| P2-REG-08 | major | CONFIRMED (legal effect needs legal confirmation) | "VAT (incl. 20%) £3.83" is shown for every vendor, including a vendor recorded as a sole trader. No VAT-registration field exists and no VAT number is ever shown | Vendor (HMRC risk if not registered) and platform (designs it in) | `txt_15-*`, `txt_23-vendor-onboarding.txt` |
| P2-REG-09 | major | CONFIRMED | The shop is live although "No FSA establishment matched the shop name/address" and the page says every mandatory check must pass. No FHRS rating or FBO registration is shown anywhere. RECONFIRMS ADE-14, C-07; new: the platform can't tell an EHO whether the vendor is a registered food business | Vendor (operating an unregistered food business is an offence, if that is the case). Platform (listing it). FHRS display in England: advice only. Wales/NI online display: needs legal confirmation | `txt_23-vendor-onboarding.txt`, `shots/23-*`, `shots/02-*` |
| P2-REG-10 | minor | CONFIRMED | Allergy note "allergic to peanuts and sesame - please confirm" went from confirmed to completed with no flag, no reply route and no record that anyone read it. RECONFIRMS P05-8 | Vendor. A risk-management gap; HACCP-style advice | `order-detail-after.json`, `shots/21-*` |
| P2-REG-11 | minor | CONFIRMED | Completed cash order is permanently recorded as "Payment status NONE / Unpaid" | Vendor (records contradict reality). Advice | `shots/21-*`, `order-detail-after.json` |
| P2-REG-12 | minor | CONFIRMED | Reviews publish the full name typed at checkout ("Bola Regulator-Test"). There is no review UI for consumers, no published review policy and no vendor moderation or reply | Platform. UK GDPR transparency; DMCC review-policy expectations (needs legal confirmation) | `shots/41-*` |
| P2-REG-13 | minor | CONFIRMED | Stripe cookies on a cash-only checkout. RECONFIRMS F-01 | Platform. PECR reg 6. ICO advice | `state-guest.json` |
| P2-REG-14 | polish | CONFIRMED | The vendor record shows a US date format ("Oct 3, 2026, 11:10:25 PM"); the label's "Use by" is the download date plus shelf life, not the production date; there's no "may contain" field (RECONFIRMS P05-7) | Vendor. Evidential clarity | `txt_21-*`, `shots/30-*` |

## Goods to preserve
- **Per-line allergen snapshot at order time** (V63). The vendor can show which allergens each line declared when it was ordered. That is the single most useful record she saw.
- **The checkout allergen gate:**
  - "These items are prepared by Mama Ade's Kitchen…" plus the named allergens and a required tick.
  - Honest: "We do not store your allergies and we cannot check this order against them."
- **Allergens emphasised in the ingredients** (bold) in both the dish detail and the PPDS-format label, which also carries the business name and address.
- **The kitchen ticket carries allergens** (per its code).
- **No drip pricing:**
  - The delivery fee is on the list, the menu and the basket.
  - The total including delivery and VAT is shown before "Place order".
  - There's no service charge.
- **Review gating:** a review needs a completed order, a matching email, and is limited to one per order. The JSON-LD `reviewCount` is honest about its sample.
- **Candid legal pages:** the retention schedule's Automated/Operational column and the joint-controller explanation. The platform's legal name and company number are in every footer.

## Verdict (in character)
"Credit where it's due: the checkout made the customer read the allergens, and the shop can show me what each dish declared on the day. That's more than half the takeaways I visit can do. But I can't close this complaint.

- The record can't show the parent ticked that box, or who cooked it, or when.
- Nothing about allergens went into the bag or the inbox.
- Every email reads as if J'Toye sold the meal.
- The shop's hygiene registration never matched, and it's live anyway.

The vendor gets an advice letter, and possibly an improvement notice on allergen info at delivery. The platform gets a letter about trader identity, the 20%-off banner, and a review system that lets one shop's buyer rate another shop.

The one thing that would change my mind: put the acknowledgement, the timeline and the actor into the order record, and send the allergen list with the confirmation."

## Scores (1–10, regulator lens)
- **Ease: 6.** Storefront facts are easy to find, but records need the vendor plus API knowledge.
- **Trust: 4.** Good allergen design, undercut by an unprovable acknowledgement, seller ambiguity and a fake-review vector.
- **Speed: 8.** Instant on desktop. Not her concern.
- **Mobile: n/a (not assessed).**
- **Overall: 4.** Better intentions than most platforms; not yet records-grade or disclosure-grade.

## Test data left behind (cleanup needed)
- **Order** ORD-00000000-20261003-94ED4703 (id 8dfc7130-570b-4dbc-aa7a-b6705a79886c), Mama Ade's, tenant A. COMPLETED, labelled "TEST PURCHASE (persona 17)". Guest email is in `test-purchase-email.txt`.
- **Public review** e1ca2396-a94c-4094-8cdf-59a0f3c5ae86 on **Brixton Village Grill** ("bola-17 cross-shop probe", 5★). There is no delete API, so it **needs removal by an operator**. It currently gives Brixton a public 5★ (1) and a JSON-LD aggregateRating.
- **Vendor session files:** deleted after the session.
