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

## Motion Direction (sketches 002–003, decided 2026-07-14)
Locked calibration from the design lead: **"full award-site"** intensity + **GSAP on
marketing routes** (dynamic-import, 0kb on app routes — now free incl. all plugins).
The GSAP scroll scenes (split-type, parallax, **pinned builds**, **horizontal scrub**,
scrubbed counters) are **desktop-only enhancements**; every sketch ships a Motion-class
reveal (variant A) as the **mandatory mobile / `prefers-reduced-motion` floor**. This is
the Phase-D input the motion-uplift research parked pending a sketch (PRs #220/#221 shipped
Phases A–C). Identity stays sketch-001 winner D (Market-Heat, orange/emerald/slate, sans-only).
