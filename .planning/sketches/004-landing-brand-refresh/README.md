---
sketch: 004
name: landing-brand-refresh
question: "Which landing direction makes J'Toye appetizing AND on-brand (oxblood #3A0B0D + Work Sans, matched to jtoyedigital.co.uk)?"
winner: "D"
tags: [landing, marketing, branding, oxblood, appetite, work-sans]
---

# Sketch 004: Landing brand refresh

## Design Question
The current landing (`frontend/app/page.tsx`) reads as a flat SaaS page — white + a 10%-opacity
orange wash, zero food imagery ("bland as tasteless food"). This sketch answers: **which
direction makes the homepage appetizing while threading the parent brand** — oxblood `#3A0B0D`
+ Work Sans, confirmed from the live `jtoyedigital.co.uk` render — and keeping the working
split-persona structure (Order / Run) + how-it-works + trust markers?

## Brand inputs (confirmed, not assumed)
- Parent site `jtoyedigital.co.uk` live palette: **oxblood `#3A0B0D`** primary, deep `#1F0F28`,
  text `#3D3D4E`, on white; typeface **Work Sans**.
- Appetite accent: **amber/orange** retained from the food-delivery palette (bridges the old
  MANIFEST "keep orange" constraint with the new oxblood brand thread).
- Hard constraint (memory `feedback_design_direction`): **no serif / no "editorial-newspaper"**
  feel — the April 2026 "Warm Editorial" direction was rejected. This is food delivery.
- Emoji stand in for real food photography (none shippable in a throwaway mockup); the real
  build swaps them for photographed dishes / vendor imagery.

## How to View
open .planning/sketches/004-landing-brand-refresh/index.html
(tabs top-left switch A/B/C; 📱/🖥 toggles a phone-width preview)

## Variants
- **A: Appetite Table** — warm cream hero, oxblood headline + amber "Or run yours", a rotated
  food-tile collage, and a "cooking near you now" dish-card row. Warmest / most food-forward.
- **B: Oxblood Premium** — full deep-oxblood hero (`#3A0B0D → #1F0F28`), cream headline, gold
  accents, glass persona doors; drops to cream for content. Premium / restaurant-grade / most
  brand-forward (closest to the parent site's confidence).
- **C: Warm Marketplace** — compact hero + search bar + category chips + a live grid of vendor
  cards (rating · FHRS · delivery). Discovery-forward — "there's food here right now."
- **D: A+C Hybrid ★ (winner, chosen 2026-07-15)** — A's warm appetite hero + food-tile collage +
  "cooking near you" dish row, with C's search bar + category chips grafted under the intro.
  Appetite + fast discovery. This is what gets built into `frontend/app/page.tsx`.

## What to Look For
- Which hero makes you *hungry* in the first second (appetite is the whole point)?
- Does the oxblood read as premium-warm (good) or heavy/dark (risk — B is the stress-test)?
- Amber vs gold as the appetite accent against oxblood.
- Does the split-persona (customer + operator) still read clearly, or does appetite bury the
  operator door?
- Mobile: check the 📱 390 preview — the doors stack, the dish row scrolls, the grid reflows.
