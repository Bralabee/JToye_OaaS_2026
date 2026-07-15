---
sketch: 003
name: operator-scroll-story
question: "How does the /for-operators pitch carry the reader on scroll — sectional reveal (Motion) or a pinned, scrubbed, horizontal signature scene (GSAP)?"
winner: "B"
tags: [marketing, motion, gsap, scroll-story, for-operators, pin, horizontal-scrub]
---

# Sketch 003: Operator Scroll Story

## Design Question
`/for-operators` is a narrative sell (leak → service rail → pilot → terms). Does it
read better as Motion-class **reveal-on-enter**, or as a GSAP **signature scene**
with a pinned build and a horizontal rail? This is the "award centerpiece" and the
biggest mobile-jank risk, so it's where the GSAP-vs-Motion decision actually bites.
Reflects the page's real brutalist device: border-4 slate, mono labels, and the
offset hard-shadow (`box-shadow: 10px 10px 0 orange`) already on the live Service-rail card.

## How to View
```
open .planning/sketches/003-operator-scroll-story/index.html
```
Toggle **A / B** and **scroll slowly**. Variant B needs internet (GSAP CDN); offline
it falls back to a visible resting state with a notice.

## Variants
- **A: Motion (installed)** — sectional reveal-on-enter; the 4-step pilot is a normal
  responsive grid that reveals; the terms band **counts up** when it enters. Calm,
  maintainable, no pin/scrub. The ~70% ceiling.
- **B: GSAP full-award ★** — the showpiece:
  1. **Pinned Service-rail scene** — the hero pins and the three rail items *build
     in one-by-one as you scrub*, so the "storefront → kitchen display → your
     decisions" story is told by the scroll itself.
  2. **Horizontal pilot rail** — the section pins and the four pilot steps translate
     sideways as you scroll vertically (the classic Awwwards horizontal-scrub).
  3. Split-type headline + scrubbed count-up on the terms band.

## What to Look For
- **The pinned build** — does the scrubbed reveal of the three rail items *land the
  argument* better than seeing the card all at once (A)?
- **Horizontal scroll** — does the sideways pilot rail feel premium, or disorienting?
  This is the highest-risk interaction; judge it honestly, especially back-scroll.
- **Pacing** — B holds you in each scene (pin) vs A's continuous flow. Which respects
  the reader more?
- **Mobile (📱 375px)** — pins and horizontal scroll are the #1 mobile-jank source.
  B must fall back to A-style stacked reveals below `md`; the real build gates the
  ScrollTrigger scenes behind a `min-width` + reduced-motion check.

## Decision
**B for desktop** (matches the "full award-site" call); **A is the mandated mobile /
reduced-motion floor** — the pinned + horizontal scenes are desktop-only enhancements,
never shipped to phones. This is what keeps "award-winning" from becoming "janky on a
phone."
