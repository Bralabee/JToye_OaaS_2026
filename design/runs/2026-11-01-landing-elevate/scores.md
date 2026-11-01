# Step 8 — independent scores, landing ELEVATE run (2026-11-01)

Scorer: a separate agent that did not build the page. Scored from the screenshots only
(`inspect-app-after/` = NEW, `inspect-app-before/` = CURRENT), against taste-skill
`references/critique.md` and `design/rubric.md` R1–R13. The builder's results section in this run's
README was not read before these scores were written. Phone (390) judged first, then tablet (768),
then desktop (1280). The inspector's text output (`inspect.txt`) was read for its measured FAILs,
because critique.md §3 caps on them.

Capture caveat that affects both pages equally: the cookie notice is open in every frame, and on the
phone it covers the bottom ~180px of each screen, including the Order-food door on the first screen.

## Verdict on scope

**NEW is a faithful ELEVATION of CURRENT, not a redesign.** Every section is in the same order with
the same job and the same copy: header, three-line h1 and the same sentence, search plus six cuisine
chips, the two doors (customer left, operator right, oxblood operator field unchanged), "Kitchens on
J'Toye" with Use my location / See all kitchens and three cards, How it works (three steps, rail on
wide screens), the three trust chips, the same four-column oxblood footer. What changed is exactly
what the brief asked: emoji became dish photographs (hero tiles, chip thumbnails, Order-food door,
every kitchen card), the eyebrow, gradients, shadows and outlined cards are gone, and the orange
became the brand's gold on a warm-stone ground. The only additions are a photo-credit line and the
"— example photo" captions, both honesty items. A returning visitor would recognise the page at once.

## ROUND 1 — NEW (elevate build)

```
Seen in one second: phone — the oxblood headline; desktop — the headline, then the four food tiles.
Idea (one sentence): the familiar two-door landing, with real West African dishes doing the work the emoji did.
Live thing: the search field and Use my location (same as CURRENT); nothing differs per visit.
Layout words by section: hero=split (phone: stack) / doors=pair / kitchens=row / how-it-works=steps / trust=chips / footer=columns
Scores: design 6.5 · usability 7 · creativity 6 · content 7.5 → overall 6.7, capped to 6.0
Caps applied: inspect FAILs remain (6) → overall ≤ 6.0; 3-line headline, footer micro-labels and
  hairlines → overall ≤ 6.5; pass 3 fails (layout could be any delivery app) → creativity ≤ 6;
  no live thing → creativity ≤ 7.5.
Top three problems (most visible first):
1. Phone first screen has no food at a size you can taste: the only pictures are six ~30px chip
   thumbnails, the first dish photo starts at y≈575 and sits under the cookie notice, and the
   desktop four-tile collage is dropped entirely at 390 and 768 (mobile-fold.jpg, tablet-fold.jpg).
   Fix: give the phone hero one dish photograph of its own crop between the sentence and the search,
   or move the Order-food door's photo up and enlarge it.
2. A small photo pool, visibly reused: the jollof-and-plantain shot is a hero tile, the Order-food
   door and the Peckham Jollof card; the egusi plate is a hero tile and Mama Ade's card; the suya is
   a hero tile and Brixton Village Grill (desktop-page-01.jpg sheet 1 vs sheet 2). Fix: one picture,
   one place; the credits already list eight photographers, so the pool exists.
3. Holes and leftovers off the phone: tablet operator door is a ~90px empty oxblood slab beside the
   photo-topped customer door (tablet-fold.jpg); desktop fold has no dominant element (inspector
   focal-point FAIL) and the doors are cut by the fold; desktop sheet 4 opens on an empty stone band.
   Fix: match the door heights by content, not by stretching; let one tile or the headline lead.
```

## ROUND 1 — CURRENT (the old landing)

```
Seen in one second: the black-oxblood headline; on desktop, then four pastel emoji cards.
Idea (one sentence): a friendly two-door marketplace landing; the food is drawn as emoji.
Live thing: the search field and Use my location; nothing differs per visit.
Layout words by section: hero=split (phone: stack) / doors=pair / kitchens=row / how-it-works=steps / trust=chips / footer=columns
Scores: design 5 · usability 6 · creativity 5 · content 5 → overall 5.3
Caps applied: 13 inspect FAILs → overall ≤ 6.0 (not binding); eyebrow, gradients, shadows,
  hairlines → overall ≤ 6.5 (not binding); fewer than four real pictures (3) → design ≤ 6.5;
  "How it works" heading 100% covered at 390 → usability ≤ 6; letter-tile kitchen images
  ("MA", "P", "BV") read as placeholders → content ≤ 5; pass 3 fails → creativity ≤ 6.
```

## critique.md criteria, NEW vs CURRENT (phone first, one line of evidence each)

| # | Pass | NEW | CURRENT |
|---|---|---|---|
| 1 | One second / five | Pass: h1 + sentence + search say "order food here" on the 390 fold. | Pass: same, but the eyebrow and emoji chips add noise. |
| 2 | Squint | Phone: headline block, search pill, chip cloud — three masses, fine; desktop: headline vs collage, neither leads. | Same masses; the orange gradient door shouts louder than the headline. |
| 3 | Cover the logo | Fails: the composition is the category default; only the dishes (egusi, jollof, suya) say "West African kitchens". | Fails harder: emoji ramen/paella/salad/cake say "any food app". |
| 4 | Edges | Two left edges (16/32px gutter and card inset) reused down the page. | Same two, plus chips and cards with outline offsets. |
| 5 | Space and frame | Equal margins; holes: tablet operator door slab, desktop stone band before trust chips. | Equal margins; large empty cream band above the hero on every width. |
| 6 | Type | One family, 56/34px h1 vs 16px lead, but body measured 14px and three blocks <13px (credits, captions). | 60/36px h1, body 14px, seven blocks <13px; lead set in grey. |
| 7 | Colour | ≈60 stone/white, 30 oxblood (door, footer, steps), gold on Search and the Order door only — disciplined. | Cream + orange gradient + oxblood + green/pink/red letter tiles; accent everywhere. |
| 8 | Images | 14 images, ~7 distinct dish photos, one grade (warm, overhead-ish), but reused across sections; no footage. | 3 raster images; food is emoji; kitchen tiles are letters on colour. |
| 9 | Sameness | split/pair/row/steps/chips/columns — no word repeats twice in a row. | Identical sequence. |
| 10 | Phone | Own stack and line breaks, but the hero picture is dropped instead of re-cropped. | Same stack; emoji chips wrap well. |
| 11 | Fullness | Phone screens are full; desktop How-it-works and trust bands are thin rows on wide stone. | Same thinness, plus emoji tiles as filler. |
| 12 | Substance | Every brief section delivers; kitchens show name, cuisine, delivery fee, minimum, status. | Same text substance; kitchen picture is a monogram. |
| 13 | Collisions | Cookie notice covers the Order door on the phone fold (capture artefact, but it is what a visitor sees); kitchen row side-scrolls cut at 390/768. | "How it works" 100% covered at 390 (inspector FAIL); kitchen row cut at 390. |
| 14 | Competitor / live thing | Swap in Just Eat and the layout still works; only the dishes resist. No per-visit live thing. | Swap in anyone; emoji make it generic. |

## Rubric R1–R13

| R | NEW | CURRENT |
|---|---|---|
| R1 food commerce | PASS — search + chips + "Order food near you" read as ordering in five seconds. | PASS — same structure. |
| R2 structure not skin | N/A — one elevation by ruling, not competing directions. | N/A. |
| R3 appetising | PARTIAL — desktop collage and kitchen cards are appetising; the phone fold shows food only as 30px chips. | FAIL — emoji and letter tiles. |
| R4 real grid | PASS — composition holds at 390/768/1280; one side-scroll row. | PASS — same grid. |
| R5 wordmark home | Wordmark present top-left on every capture; that it links home cannot be seen in a screenshot (not verified here). | Same. |
| R6 honest | PASS — "example photo" captions and credits line; no new claims; "order in minutes" carried from old. | PASS on claims; emoji imply nothing false. |
| R7 phone first | PASS on content (headline, one sentence, search on the 390 fold); FAIL on appetite at 390; CWV not in these artifacts. | PASS on content; covered heading at 390. |
| R8 warm and light | PASS — stone ground, gold action; oxblood held to one door, step dots and footer. | PASS — warm cream, though the gradient reads louder. |
| R9 clear before clever | PASS on tablet/desktop (both doors on the fold); on phone the doors sit below the fold, as before. | Same. |
| R10 food all the way down | PARTIAL — kitchen cards now carry food; How it works, trust and footer carry none. | FAIL — food stops at the emoji chips; cards are monograms. |
| R11 elevate first | PASS — same page, same order, same copy; changes are exactly the brief's list. | (baseline) |
| R12 references drive | PARTIAL — takes food photography and "one control first" from Uber Eats/Just Eat; does not take their one large hero photograph on the phone. | FAIL — predates the references. |
| R13 palette with a source | PASS on screen — oxblood + gold + warm stone match identity.md's system; inspector still finds a cream `#fbf6f0` section/text that identity.md says is not used. | FAIL — orange gradient and arbitrary green/pink/red tiles. |

## The 3–5 most visible weaknesses of NEW

1. **Phone hero is the old hero minus emoji, with no picture.** At 390 the food is six 30px chip
   thumbnails (one, Vegan, is an unreadable pale blob); the four-tile collage exists only at 1280.
2. **The same three photographs appear in two or three places each** (hero tile, door, kitchen card),
   which reads as a small stock pool rather than three kitchens.
3. **Tablet operator door is half empty** (oxblood slab under two lines of text) beside the
   photo-topped customer door; the doors no longer read as a matched pair at 768.
4. **Small grey text survived the elevation:** body measured at 14px, "Nigerian — example photo"
   captions, the credits line and the OS copyright under 13px; footer labels still tiny tracked
   uppercase gold.
5. **Below the kitchens the page goes back to kit parts:** icon-in-circle steps, check-icon trust
   pills, a long oxblood footer (≈1.5 phone screens), no food.

## Remaining template / AI tells in NEW

- Tilted, rounded photo tiles in a 2×2 collage (the "scattered polaroids" hero device), desktop only.
- Icon-in-circle step row with a connecting rail (How it works).
- Line icon at the top of each door card (fork-and-knife, shopfront): the feature-card pattern.
- Check-icon pill chips for trust facts.
- Footer micro-labels in tracked uppercase ("FOR CUSTOMERS", "LEGAL", "FOR OPERATORS") and the
  header/footer hairlines (inspector FAILs).
- "Learn more →" / "Browse shops →" arrow links; 23 em dashes across the touched files (lint WARN).
- Removed and no longer present: eyebrow, emoji, gradients, resting shadows, outlined cards.

## Bottom line

NEW 6.0 (capped; 6.7 uncapped) vs CURRENT 5.3. NEW is better on every axis that the owner's round-2
verdict named (images instead of emoji, brand palette with a source, same page), and it is a faithful
elevation. It does not reach 7.5: the phone, which R7 makes the primary artifact, gained the least,
and the inspector's six FAILs (three-line h1 kept by D14, micro-labels, hairlines, 14px body, focal
point) cap the overall at 6.0.
