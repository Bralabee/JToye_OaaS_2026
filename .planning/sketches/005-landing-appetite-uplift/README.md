---
sketch: 005
name: landing-appetite-uplift
question: "Which appetite direction un-blands the shipped sketch-004-D landing — photographic, hand-made market, night-market, or live-motion?"
winner: null
tags: [landing, marketing, appetite, oxblood, photography, motion]
---

# Sketch 005: Landing Appetite Uplift

## Design Question

The shipped landing (sketch 004 winner D) was judged by the owner as "bland, rigid,
unenticing, not appetizing" (2026-08-31). Which of four divergent appetite-first
directions fixes that — while keeping the locked brand thread (oxblood #3A0B0D +
Work Sans + amber/orange accent, --primary orange-700) and every load-bearing good
of the current page?

## How to View

open .planning/sketches/005-landing-appetite-uplift/index.html

Use the toolbar (bottom-right) to constrain to 390px — every variant was designed
mobile-first; judge the phone view first. Photos are Unsplash hotlinks used as
mockup stand-ins only (offline they degrade to gradient+emoji tiles — the current
landing's look, which is itself a useful A/B against any variant).

## Variants

- **A: Golden Hour** — full-bleed food-photography hero, warm oxblood scrim, search as
  the single focus, glass category chips over the image, price-tagged dish cards.
  Industry-standard excellence (Deliveroo-calibre); the path of least resistance.
- **B: Market Day** — street-market energy: oxblood sticker chips with hard offset
  shadows, rotated polaroid dish cards, hand-written Caveat annotations, marker-swash
  H1 underline, wavy dividers, ticker marquee. The aesthetic risk: a hand-drawn accent
  font layered on the locked Work Sans (accent only, never body).
- **C: Ember** — night-market premium: deep oxblood-to-black ground, dishes as glowing
  circular plates with ember shadows, gold accents, "open now" glow pills. Flips the
  brand anchor from chrome colour to atmosphere.
- **D: Market Heat Live** — motion-led: animated heat-wash gradient, live kitchen
  activity ticker, platform stats, a drifting dish-photo marquee band. Keeps the
  current page's light structure but makes the marketplace feel alive. Natural home
  for the existing GSAP HeroScene hooks and the parked Market-Heat motion arc E.

## What to Look For

- Which hero makes you *hungry* within one second at 390px?
- Does the variant still read as J'Toye (oxblood + amber), or as a template?
- B's hand-drawn accents: charming or off-brand for enterprise credibility?
- C: does dark premium fit a daytime lunch order, or only evenings?
- D's live ticker: energising, or a fabrication risk (real events only — the
  #544 rule against invented vendors extends to invented activity)?
- All four preserve, and the implementation must too: server-rendered real shops
  (#544/#507), NearYouRow location reorder, data-hero-* GSAP hooks, crawlable
  chips + HeroSearch, split-persona doors, trust markers, JSON-LD, 1280px
  marketing width tier (phase 35).

## Production notes (read before implementing any winner)

- A/C/D lean on real food photography. PublicShop has `logoUrl` only — a licensed
  hero/dish photography source is a prerequisite decision (options: vendor media
  via the Phase 24 pipeline, licensed stock for the marketing hero only, or a
  commissioned shoot). Emoji fallback tiles remain the no-photo floor.
- D's ticker must be fed by real events (order placed, shop went live) or not ship;
  the sketch's feed is invented placeholder copy.
- All marquee/heat animations in the sketch respect `prefers-reduced-motion`.
