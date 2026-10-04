# Persona 08 — Claire, sceptical evaluator (persisted by coordinator from the agent's returned report)

> The subagent's own Write was refused by the harness; this is its returned report, verbatim in substance.
> Evidence files (scripts, shots/*.png, txt_*/dtxt_* captures, template.csv, vendor-pack.pdf, API logs) are in this folder.

**Verdict:** WALK AWAY for now; revisit in 6 months for a 1-site pilot. "Most honest pitch we've had — but they can't pay us, there's no contract, and nobody answers at 8pm Saturday." Would change her mind: a signed merchant agreement + working Stripe Connect payouts end-to-end + a support phone line.

**Scores:** ease 6 · trust 3 · speed 9 (storefront LCP ≈0.94 s throttled mobile) · mobile 8 · overall 4.

## Persona
Claire, 45, co-owner of a 3-site Caribbean takeaway in Birmingham. Just Eat + Deliveroo (~30% all-in) + Square POS. Spreadsheet-minded; pitched by Flipdish, Slerp, Square Online. Desktop 1440px + phone.

## Journey
1. **Landing `/`** (`shots/pub_home.png`): polished, appetising; trust strip "UK food-hygiene verified · Allergen info on every item"; footer company no. 16471464. No Pricing link anywhere.
2. **`/for-operators`** (`shots/pub_for-operators.png`):
   - The hero says "ONE LONDON CLUSTER… Nigerian and West African".
   - The terms card lists £39/location/month, setup from £99, a "PRICING TEST" of 0.5% of direct sales or £79–£119 fixed, and £149–£199 for high volume.
   - Nothing on VAT, contract term or cancellation.
   - "Card fees are shown transparently", but no figure appears anywhere.
   - "Start your application" goes to a login. "Check your pilot fit" is local only ("Nothing is sent anywhere"). The CTA says to return the pack "to the person who shared it". There is no phone, sales email or demo booking, and the vendor-pack PDF is the same page with no contact.
3. **`/business-model-guide`:** an internal memo:
   - "Stop criterion", "CAC… below £375", "Recruit 30–40 prospects / 10–12 paid pilots";
   - a contribution calculator showing 79% margin;
   - "Do not claim: production readiness… payouts… offline KDS or ticket printing… self-service tenancy".
4. **`/competitive`:**
   - Flipdish only.
   - Claims "Marketplace/multi-vendor payouts: Full" and "Native marketplace payments: Stripe Connect destination charges", which contradict 2–3.
   - Admits "no SaaS subscription billing built" and "RAISED £0 · solo/small build · pre-market".
   - Shows a repo path, `DOCS/ANALYSIS/FLIPDISH-VS-JTOYE-TEARDOWN.MD`.
   - Not in the sitemap.
5. **Legal:**
   - `/legal` gives the company name, number and E&W; privacy@olajay.co.uk is the only contact.
   - `/legal/terms`, `/pricing`, `/contact` and `/about` all return 404.
   - No ICO registration number.
   - The accessibility statement says "Registered office address not published".
   - The privacy notice is excellent.
6. **Companies House** (fetched 2026-10-03): J'TOYE DIGITAL LTD:
   - Active, incorporated 23 May 2025;
   - registered office Michelmersh, Romsey SO51 0NT;
   - SIC 62020 (IT consultancy);
   - 1 officer, director Sanmi Oluwafemi Ibitoye, identity verified.
7. **Storefront `/shop/mama-ades-kitchen`:**
   - Excellent SEO: unique title and meta, canonical, OG, and JSON-LD `Restaurant` with address, geo, cuisine, priceRange and a full `hasMenu`/MenuItem/Offer tree.
   - Throttled iPhone 13: menu visible at 939 ms, LCP 940 ms.
   - Problems: a public "E2E launch offer — E2E 20% OFF" banner, no food-hygiene rating shown, shop phone and email null in the API, and no custom domain (`/shop/slug`).
8. **Dashboard as admin-user** (read-only; clean run after the port fix):
   - **Overview:** Recent Orders are all test junk, Pending for 11–27 days, and a real person's name and Hotmail address top the list.
   - **Products → Bulk Import:** a CSV template (`template.csv`) with an `allergen_mask` integer bitmask and a `shop_id` column not in the on-screen list. "Photo Scan (AI)" works one dish at a time and creates drafts. No aggregator or Square import.
   - **Marketing:** promotions and announcements only; no email, loyalty or vouchers.
   - **Finance:** a VAT ledger; no payouts or fees.
   - **Kitchen:** per-shop board with an honest scope banner and "Print all".
   - **Onboarding:** "In review" for 27 days with "no separate J'Toye reviewer: an administrator on your own account resolves them". Mama Ade's hygiene check is in "Manual review" yet the shop is published.
   - **Staff:** clear shop-scoped roles; no invites.
   - **Webhooks:** HMAC-signed.
   - **Payments:** no nav item; `/dashboard/payments(/connect)` returns 404. `frontend/app/dashboard/payments/connect/connect-outcome.tsx` says "The platform has no vendor-facing read… When the vendor payments/payouts surface is built…". Connect is reachable only via `TenantAdminController POST /{tenantId}/stripe/connect`.

## Expectations vs reality
| Expected | Got | Felt |
|---|---|---|
| Price on one screen | Four options (two are ranges) labelled "pricing test"; no VAT basis or term | Hypotheses, not a price |
| Who pays card fees | "Shown transparently", never shown | Unanswered |
| Contract / lock-in | No terms (404) | Can't sign |
| Payouts | No screen; pages contradict | Red flag |
| Import 120-item menu | CSV import + per-dish AI photo; no JE/Square import; bitmask allergens | Half-yes |
| Square POS | None | Two systems at the counter |
| Marketing / SEO | Customer list + promos; no email or loyalty; SEO excellent | SEO better than expected |
| 8pm Saturday support | None; privacy inbox, "one working week" | Walk-away on its own |
| Real company | Active Ltd, one director, Romsey; no address or ICO on site | Real but tiny |

## Findings
| ID | Sev | Status | Finding | Evidence |
|---|---|---|---|---|
| C-01 | major | CONFIRMED | Payouts claim contradicts across public pages | `txt_competitive.txt`, `txt_for-operators.txt`, `txt_business-model-guide.txt` |
| C-02 | major | CONFIRMED | £39 subscription has no billing built; no billing UI | same + `dtxt_dashboard.txt` |
| C-03 | major | CONFIRMED | No vendor payments/payouts surface (404s); card flow not demonstrable in this env (Stripe unconfigured locally) | `dtxt_dashboard_payments*.txt` |
| C-04 | major | CONFIRMED | No Terms or merchant agreement; /legal/terms, /pricing, /contact, /about all 404 | `shots/pub_legal_terms.png` |
| C-05 | major | CONFIRMED | No sales or support contact, no SLA; only a non-brand-domain privacy inbox | `txt_*.txt` |
| C-06 | major | CONFIRMED | Internal strategy docs served as public pages, incl. a repo path | `txt_business-model-guide.txt`, `txt_competitive.txt` |
| C-07 | major | CONFIRMED | Self-approved compliance vs "UK food-hygiene verified"; published shop with unresolved hygiene check; no rating on storefront | `dtxt_dashboard_onboarding*.txt` |
| C-08 | minor | CONFIRMED | Positioning excludes non-London, non-West-African operators | `shots/pub_for-operators.png` |
| C-09 | minor | CONFIRMED | No VAT basis or card-fee figure on pricing | `txt_for-operators.txt` |
| C-10 | minor | CONFIRMED | Import gaps (no aggregator/Square import, bitmask allergens, undocumented `shop_id`) | `template.csv`, `dtxt_import_photo.txt` |
| C-11 | minor | CONFIRMED | No ICO number or registered office on site | `txt_legal*.txt` |
| C-12 | minor | CONFIRMED | Demo data visible: E2E promo on public shop, stale test orders, a real person's name and email, a `<b>x</b>` test customer | `shots/d_dashboard.png`, `shots/pub_shop_mama-ades-kitchen.png` |
| C-13 | polish | CONFIRMED | No email campaigns, loyalty or vouchers | `dtxt_dashboard_marketing.txt` |
| C-14 | polish | SUSPECTED | No custom storefront domain (not every shop-edit form opened) | `dtxt_dashboard_shops.txt` |
| C-15 | polish | CONFIRMED | `/competitive` missing from the sitemap | curl output |

## Goods to preserve
Storefront JSON-LD and meta; ≈0.94 s throttled LCP; candid boundary statements; privacy notice; CSV import with template + photo scan; shop-scoped staff roles; per-shop kitchen banner; VAT ledger.

## Comparison (prices as found 2026-10-03)
| | J'Toye (as published) | Flipdish | Slerp | Square Online | Just Eat |
|---|---|---|---|---|---|
| Monthly / site | £39 (or £79–£119, or £149–£199); VAT unstated; no billing built | Website £49 annual / £69 monthly; +app £79/£99; POS bundle £119/£139 ex VAT | Quote only (Capterra US "from $499") | £0 / £20 Plus / £64 Premium, annual, ex VAT | £0 |
| Per order | 0.5% option or Connect platform fee; Stripe 1.5% + 20p passed through (not shown) | Reportedly 2–7% + processing | "Small transaction cost", 0% commission | 1.4% + 25p (Premium 1.4% + 15p) | 14% + VAT self-delivery; ~30% + VAT JE-delivered |
| Payouts | Not built for vendors | Integrated | Integrated | Square balance | Weekly |
| POS | None | Own POS | Lightspeed partner | Native Square | Integrations |
| Loyalty / CRM | No | Yes | Yes | Basic | No |
| Support | None published | Free 7-day email/WhatsApp/phone | Included | Square support | Partner support |

**Rough monthly cost, 3 sites** (assumes £8k direct GTV/site, £25 average order, 320 orders/site):
- **J'Toye:** ≈£670/month, if the payments rail existed.
- **Square Online Premium:** ≈£672/month, and it already works with her Square POS.
- **Just Eat** at 14% + VAT: ≈£4,030/month.

Sources:
- [Flipdish online-ordering pricing](https://www.flipdish.com/gb/pricing-online-ordering)
- [Flipdish pricing](https://www.flipdish.com/gb/pricing)
- [Startups.co.uk](https://startups.co.uk/payment-processing/online-ordering-systems-takeaway-delivery/)
- [Slerp](https://www.slerp.com/)
- [Capterra Slerp](https://www.capterra.com/p/236624/Slerp/)
- [RestaurantTech 2026](https://restauranttech.co.uk/guides/best-online-ordering-systems-restaurants-uk-ireland-2026)
- [Square UK fees](https://squareup.com/gb/en/legal/general/fees)
- [Tablespark: Square Online UK](https://tablespark.uk/journal/square-online-restaurant-ordering-cost-uk)
- [MyFoodFast: Just Eat fees](https://myfoodfast.com/blogs/just-eat-fees-explained)
- [RestaurantHero: delivery commissions](https://restauranthero.co.uk/guides/delivery-app-commission-fees-uk/)
- [Stripe Connect pricing](https://stripe.com/connect/pricing)
- [MerchantHQ: Stripe UK fees](https://merchanthq.co.uk/fees/stripe/)
- [Companies House 16471464](https://find-and-update.company-information.service.gov.uk/company/16471464)
- [Companies House officers](https://find-and-update.company-information.service.gov.uk/company/16471464/officers)

## Decision memo (Claire's voice)
"Walk away for now; revisit in 6 months for a single-site pilot. It's the most honest pitch we've had, and the shop pages are quicker and better built for Google than Flipdish's. But:
1. They can't pay us yet. Their own pages say 'not production connected-account settlement', there's no payouts screen, and one page claims 'payouts: Full'.
2. There's no contract: no terms, no notice period, no VAT basis, and the prices are labelled 'pricing test'.
3. There's nobody to ring. It's a privacy inbox that replies within a week.
4. It isn't for us: it's pitched at West African kitchens in London, with no Square link and no loyalty.
5. It's one director's company, sixteen months old, registered as IT consultancy.

Square Online Premium costs about the same and works with our tills today. I'll trial it at one site first. What would change my mind: a signed merchant agreement with a real Connect payout I can watch land, plus a support line. Then I'd pilot one site for 90 days at £39."

## Environment notes
- :9091 port fault (#671) caused first-pass "Network Error" screens; every affected screen was re-run after the fix and all API calls returned 200.
- Stripe is unconfigured in the local stack (env-var name mismatch in `.env`), so the live card flow is "not demonstrable", not a product finding.
