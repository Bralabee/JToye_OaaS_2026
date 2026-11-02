# Brief — public landing page (`/`)

**Status: CONFIRMED by Sanmi, 2026-11-01 (design-run step 0).** The imagery source is still open (below).

Elevate or redesign: elevate (Sanmi, 2026-11-01) — keep the current landing, replace emoji with real food pictures, remove the tells, palette from the brand (round 2 verdict; go-ahead "2").

Surface type: **page** (read and scrolled). Scope for rulings and checks: `page`.

## Subject

J'Toye is a direct-ordering platform for independent UK food businesses: home and batch kitchens,
weekend and seasonal cooks, small takeaways — many of them cooking West African and Caribbean food
(the demo kitchens in the repo: Mama Ade's, Peckham Jollof, Brixton Grill). A kitchen gets its
own storefront, QR and web ordering, a kitchen display, fulfilment slots, Natasha's Law labels.
Customers find kitchens near them at `/shop` and order from the kitchen's own storefront, with no
app to download.

The subject's world, where distinctive choices should come from: the pass and the order ticket,
foil trays and batch-cook days, market stalls and a WhatsApp pre-order list, a handwritten
"sold out" on a menu board, jollof, suya, jerk smoke, the hygiene-rating sticker in the window.

## Audience

1. **Hungry customers** near a kitchen: on a phone, often arriving from a link the kitchen shared.
   They want to know what's cooking, whether it's trustworthy, and how to order now.
2. **Operators**: people who already run a food business with recurring orders, and are tired of
   DMs, cash and spreadsheets. They want proof it fits the service they already run.

## The page's one job

Send each person through their door within one screen: customers to a kitchen near them,
operators to "Run your food business". Everything else supports that.

## Real constraints

- Mobile-first; most customers arrive on a phone.
- Honest claims only: card payments are in Stripe test mode until the first production tenant;
  the kitchen display is online-only; WhatsApp is a generated link, not a channel. The page must
  not imply more.
- Trust facts that are real and can be shown: UK food-hygiene (FHRS) verification, allergen
  information on every item, no app to download.
- Accessibility: WCAG AA contrast; `prefers-reduced-motion` respected; the wordmark is a named link.
- Rulings in `rulings.tsv` (no gradients, no glass, content present at load, at most one
  deliberate motion moment, hover feedback only on clickable cards).

## Imagery that truly exists

- 3 demo vendor logos (`frontend/public/brand/`).
- 4 product screenshots for operators (`frontend/public/marketing/operators/`: cook sheet, PPDS
  label, slot picker, storefront menu).
- **Seven BeesLens food photographs, the owner's own (D16, 2026-11-02)**, in
  `frontend/public/marketing/landing/beeslens-*.webp`: spaghetti with chicken and plantain, a jollof
  meal box, rice/beans/plantain with pepper stew, plantain and yam meal-prep boxes, puff-puff, a
  catering buffet soup, a fruit-punch dispenser. Credited "Photography: BeesLens".
- Four credited open-collection pictures where no own photograph exists yet (suya, Jamaican rice and
  peas, mutton biryani, chana masala; `credits.json` beside them).
- History: until 2026-11-02 there was no food photography. Emoji stand-ins are no longer allowed
  (lifted 2026-11-01), so a run had to choose a source before it built: the kitchens' own photos, a
  commissioned shoot, licensed stock, or open-collection pictures (taste-skill `assets.mjs`, credited).
  **RULED by Sanmi 2026-11-01 (D10): open-collection pictures now, credited; each kitchen's own photos
  replace them as tenants upload through the Phase 24 media pipeline.** D16 put the owner's own
  photography first.
