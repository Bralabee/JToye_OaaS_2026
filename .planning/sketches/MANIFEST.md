# Sketch Manifest

## Design Direction
Punch up the customer storefront: it must attract, make a brand statement, and feel professional/enterprise-grade — while staying unmistakably food-delivery. Hard constraints from prior decisions: keep the orange/emerald/slate palette, sans-serif display type only (the "Warm Editorial" serif direction in PR #49 was rejected as "newspaper, not food delivery" and reverted in PR #52), mobile-first, and every vendor/dish surface needs a designed no-photo fallback because real photography will be sparse.

## Reference Points
Current live storefront at localhost:3000 (screenshots taken 2026-07-12 — the "before" state). Trust-layer data available in the backend: FHRS hygiene ratings (vendor onboarding), reviews, Natasha's Law allergen labelling, delivery fees/minimums.

## Sketches

| # | Name | Design Question | Winner | Tags |
|---|------|----------------|--------|------|
| 001 | storefront-theme-punch | Which art direction gives punch + branding + enterprise credibility without photography? | D (B+A synthesis) | storefront, theme, branding |
| 002 | marketing-hero-kinetics | How should the landing (/) hero arrive + scroll — and is GSAP worth a second engine over Motion? | B (GSAP full-award; A = mobile/reduced-motion floor) | marketing, motion, gsap, hero |
| 003 | operator-scroll-story | How does /for-operators carry the reader — Motion reveal or GSAP pinned+horizontal signature scene? | B (GSAP full-award; A = mobile/reduced-motion floor) | marketing, motion, gsap, scroll-story |
| 004 | landing-brand-refresh | Which landing direction makes the homepage appetizing AND on-brand (oxblood #3A0B0D + Work Sans, matched to jtoyedigital.co.uk)? | D (A+C hybrid: A's appetite hero + dish row + C's search/chips) | landing, marketing, branding, oxblood, appetite |
| 005 | landing-appetite-uplift | Owner judged shipped 004-D "bland, rigid, unenticing" (2026-08-31) — which appetite direction fixes it: photographic (A), hand-made market (B), night-market (C), or live-motion (D)? | NONE — all four rejected same-day as re-skins of the same skeleton; superseded by 006 | landing, marketing, appetite, photography, motion |
| 006 | landing-broken-grid | Which STRUCTURAL break fixes the landing: composed type×plate scene (A), typographic menu board (B), free collage (C)? | A ("The Pass") — after a full rebuild onto a 1280-capped 12-col grid; round 1's absolute-vh build was malformed off-mobile | landing, marketing, broken-grid, structure |
| 007 | the-pass-full-page | Does 006-A's language sustain a full landing with real content depth (order-ticket motif, kitchen showcase, quote slab, operator KDS)? | PARKED 2026-08-31 — owner ruling: the shipped 004-D landing STAYS as-is for now; 006-A/007 are kept for later, not rejected. Do not implement without a fresh owner go-ahead; on revival start from 007 + its README's production notes | landing, marketing, the-pass, refinement, parked |

## Brand thread update (sketch 004, 2026-07-15)
User directive: match the parent site **jtoyedigital.co.uk** — confirmed live palette **oxblood
`#3A0B0D`** primary + **Work Sans** (deep `#1F0F28`, text `#3D3D4E`, on white). This **extends**
the sketch-001 "Market-Heat" identity rather than replacing it: oxblood becomes the brand anchor
(header/footer/deep accents), **amber/orange stays the appetite accent** (bridging the locked
"keep orange" constraint). Sans-only + no-editorial constraint unchanged. Goal: kill the "bland
SaaS" landing — appetite-forward, food photography (emoji as mockup stand-in), mobile-first.

## Motion Direction (sketches 002–003, decided 2026-07-14)
Locked calibration from the design lead: **"full award-site"** intensity + **GSAP on
marketing routes** (dynamic-import, 0kb on app routes — now free incl. all plugins).
The GSAP scroll scenes (split-type, parallax, **pinned builds**, **horizontal scrub**,
scrubbed counters) are **desktop-only enhancements**; every sketch ships a Motion-class
reveal (variant A) as the **mandatory mobile / `prefers-reduced-motion` floor**. This is
the Phase-D input the motion-uplift research parked pending a sketch (PRs #220/#221 shipped
Phases A–C). Identity stays sketch-001 winner D (Market-Heat, orange/emerald/slate, sans-only).
