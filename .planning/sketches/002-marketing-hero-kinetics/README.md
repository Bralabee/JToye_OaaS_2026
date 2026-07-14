---
sketch: 002
name: marketing-hero-kinetics
question: "How should the landing page (/) front door arrive and move as you scroll — and is GSAP worth a second engine over Motion?"
winner: "B"
tags: [marketing, motion, gsap, hero, landing]
---

# Sketch 002: Marketing Hero Kinetics

## Design Question
The visual identity is already locked (sketch 001 winner D — Market-Heat device,
orange/emerald/slate, sans-only). This sketch answers a **motion** question, not a
look question: how does the landing hero arrive, and how does "How it works" behave
on scroll? And — since Motion (`framer-motion`) is already installed while GSAP is
not — **is GSAP's ceiling worth a second animation engine?**

## How to View
```
open .planning/sketches/002-marketing-hero-kinetics/index.html
```
Toggle **A / B** in the top bar and **scroll**. Variant B needs internet (GSAP
loads from CDN); offline it shows a resting state with a notice — never blank.

## Variants
- **A: Motion (installed)** — `IntersectionObserver` reveal-on-enter (exactly what
  Motion's `whileInView` compiles to), spring-ish persona doors, a step-rail that
  grows once when the section enters. No pinning, no scrubbing. The honest ~70%.
- **B: GSAP full-award ★** — real GSAP + ScrollTrigger: split-type headline word
  stagger, a **parallax heat-wash** that drifts on scroll, persona doors dealing in,
  and a **scroll-scrubbed step-rail** that draws left→right and activates each step
  as you pass. This is the award-site ceiling.

## What to Look For
- **The headline entrance** — does word-by-word split-type (B) feel worth it vs a
  clean fade-up (A)?
- **The step-rail** — B's rail is *scrubbed* (tied to scroll position, scrubs both
  ways); A's just plays once. Feel the difference in control.
- **Parallax heat-wash** — subtle brand warmth (B) vs flat (A). Too much?
- **Mobile discipline** — resize to 375px (📱 in the toolbar): B must degrade to
  A-style fades, not heavy parallax (jank + Core Web Vitals risk).

## Decision
**B is the direction for desktop**, per the "full award-site" calibration. **A is
not a loser** — it is the documented **mobile / `prefers-reduced-motion` floor**:
the real implementation ships B on desktop and degrades to A's reveal-only behavior
below `md` and under reduced motion. Both are honored in `prefers-reduced-motion`.
