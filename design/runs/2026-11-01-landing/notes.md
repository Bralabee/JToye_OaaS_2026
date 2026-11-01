# Landing run 2026-11-01 — notes (taste-skill steps 1, 2, 5)

Surface: `/` (page). Brief: `design/brief.md`. References: `design/references/README.md`.
Rulings: `design/rulings.tsv` (page scope: no gradients, no glass, no per-section entrance, at most
one deliberate motion moment, hover and focus feedback on clickable cards only). Imagery: D10.

## 1. Inventory — what belongs to J'Toye and to no competitor

1. **The name.** J'Toye; the company is J'Toye Digital Ltd, company no. 16471464.
2. **The demo kitchens and their streets.** Peckham Jollof (Rye Lane), Mama Ade's, Brixton Grill
   (Brixton Market). Real logos exist (`assets/logo-*.png`).
3. **The pass and the order ticket.** Where kitchen tickets hang; the kitchen display shows "Incoming",
   "Mark ready".
4. **The cook sheet.** A consolidated printed sheet for a service: every order's lines totalled by
   dish (`assets/kitchen-cook-sheet.webp`, real screenshot).
5. **The PPDS label.** A Natasha's Law label with the 14 allergens emphasised, printed from the
   product (`assets/ppds-label.webp`, real).
6. **Fulfilment slots.** "Saturday 12:00–14:00 · 20 orders" with a cutoff; the slot picker is real
   (`assets/slot-picker.webp`).
7. **The storefront menu** on a phone (`assets/storefront-menu.webp`, real).
8. **The FHRS sticker in the window.** UK food-hygiene rating, verified at onboarding.
9. **No app to download.** The storefront is a web page reached by link or QR on the counter.
10. **Batch days and foil trays.** Weekend and seasonal cooks; a WhatsApp pre-order list is what
    J'Toye replaces.
11. **The food.** Jollof, suya, jerk, plantain; the smoke from the grill.
12. **The high street.** Rye Lane market entrance, Brixton Market, a Caribbean takeaway shopfront in
    Thornton Heath, Scorpio's Chicken on Bruce Grove (all in `assets/`, credited).

## 2. The argument — claims and their proof, in page order

```
hero          Independent kitchens near you take orders here, directly.   → a particular kitchen's world, large; a postcode/dish search that runs a real query (/shop?q=)
list          These are the kitchens, and whether they are cooking now.    → the real published shops: name, logo, Open / Paused / Closed from the server (NearYouRow)
how-it-works  Three steps and no app: find, order and pay, follow it.     → the three steps as the subject's own artefact (ticket, slot, sheet)
proof         You can trust what you are buying.                         → the three true facts: FHRS verified, allergen info on every item, no app to download
showcase      If you cook, this runs the service you already run.        → the four real product screenshots: storefront menu, slot picker, cook sheet, PPDS label
cta           Run your food business on it.                              → one door to /for-operators
footer        Who runs this, and where the pictures came from.           → J'Toye Digital Ltd 16471464, legal links, the credit line from assets/credits.json
```

No stats, no quotes, no logos of customers, no ratings: none exist that can be shown honestly (rubric R6).

## 5. Copy deck (plain text; headlines two lines or fewer at every width)

**Nav.** Wordmark "J'Toye" (links to /). Links: Find kitchens (/shop) · How it works (#how) ·
For kitchens (/for-operators). One action: Find kitchens.

**Hero.**
- h1: `Order from kitchens near you` (28 characters)
- supporting line: `Independent cooks in your area, jollof to jerk, from their own storefront. Nothing to download.`
- control: a text field labelled `Postcode or dish` with placeholder `SE15 or jollof`, button `Find kitchens` (form GET /shop, name=q)
- under it, crawlable links as plain text: Grill · Jollof · Caribbean · South Asian · Vegan · Desserts (each /shop?q=…)

**List — Cooking near you.** (heading: `Kitchens on J'Toye`; the real row makes no locality claim without a coordinate)
- Peckham Jollof · Rye Lane, SE15 · Open
- Mama Ade's · Peckham · Paused
- Brixton Grill · Brixton Market, SW9 · Closed
(logos in assets/; status words are the server's three states; in the sketch they are examples of the three states, not claims)

**How it works.** heading `Three steps, no app`
1. Find a kitchen — search by postcode or dish; every kitchen shows its hygiene rating and hours.
2. Order and pay — delivery or collection; card, or cash on collection where the kitchen allows it; allergens on every item.
3. Follow it — a tracking page from the kitchen to your door; no account needed.

**Proof.** heading `What you can trust`
- UK food-hygiene rating, verified when the kitchen joins.
- Allergen information on every item, with "may contain" where the kitchen says so.
- No app to download. A web page, reached by link or QR.

**Showcase (operators).** heading `Built around the service you already run`
- Your own storefront — your menu, your prices, your link and QR. (storefront-menu.webp)
- Slots with a cap — "Saturday 12:00–14:00, 20 orders", closed at the cutoff. (slot-picker.webp)
- One cook sheet per service — every order's lines totalled by dish. (kitchen-cook-sheet.webp)
- Natasha's Law labels — printed from the product, 14 allergens emphasised. (ppds-label.webp)

**CTA.** heading `Run your food business` · line `Take orders, run the kitchen, go live in a day.` · button `See how it works for kitchens` (/for-operators)

**Footer.** `J'Toye` · `J'Toye Digital Ltd, company no. 16471464` · Privacy · Terms · Allergen policy · Data request (/data-request) · then the credit line (`node assets.mjs --credits --to design/runs/2026-11-01-landing/assets`).

## Pictures (all in `assets/`, credits in `assets/credits.json`)

jollof-rice-with-vegetable.jpg · suya-seller.jpg · jerk-chicken-as-served-july-05.jpg ·
panoramio-3174.jpg (Rye Lane market entrance, Peckham) · brixton-market-sw9-….jpg ·
food-store-and-caribbean-takeaway-woodville-road.jpg (Thornton Heath shopfront) ·
93-bruce-grove-scorpios-chicken-….jpg (Caribbean restaurant and jerk bar, Tottenham).
A found picture shows the world around a kitchen; it is never captioned as a J'Toye kitchen's own dish or shop.
Real product: storefront-menu.webp, slot-picker.webp, kitchen-cook-sheet.webp, ppds-label.webp. Logos: logo-*.png.
