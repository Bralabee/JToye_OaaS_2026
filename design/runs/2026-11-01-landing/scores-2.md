# Landing run 2026-11-01: step 8 second look, independent scores

Scorer: a separate agent that did not build any of the three pages. It scored from the screenshots
only. It read `critique.md`, `rubric.md`, `identity.md` and `verdict.md`. It did not read the
"Rebuild (D13)" section of `direction.md`. Phone was judged first, then tablet and desktop.

- **NEW**: the rebuild, `inspect-2/`.
- **OLD**: the current landing, `inspect-before/`. It won the blind A/B.
- **PREV**: The Pass build that lost the A/B, `inspect/`.

The inspector's FAIL list comes from each `report.json`. These counts set the caps.

| | FAILs | which |
|---|---|---|
| NEW | 5 | micro-label, hairline, palette-claude (cream + terracotta), body-size 14px, covered-text at 390 ("What you can trust", "Run your food business", footer "J'Toye", each 100% hidden at some scroll position) |
| OLD | 12 | headline-lines (3 lines at 1440 and 390), eyebrow, micro-label, emoji, gradient, shadow ×20, hairline, body-size, few-pictures (0), small-text, focal-point |
| PREV | 3 | micro-label, hairline, covered-text at 768 |

All three hit the "any inspector FAIL" cap, so none can score above 6.0 overall. Most of NEW's
FAILs are in the shared chrome: the header rule, the footer labels and rules, and the cookie notice.
Step 8 already ruled that these go to a separate PR. They still count here, because a visitor sees them.

---

## 1. Critique rounds (critique.md §4 shape)

### NEW (`inspect-2/`)

```
ROUND 2 (rebuild)
Seen in one second: the plate of jollof and greens breaking a tomato-red band under a big black title.
Idea (one sentence): an order ticket on the pass. White tickets with a perforated top carry the steps
  and the kitchen's order, and a real plate breaks a tomato band.
Live thing: the postcode/dish search and the kitchen row with Open / Paused / Closed status.
Layout words by section: hero=split band / kitchens=card row / steps=card row / trust=split /
  operators=screen grid / run=split / footer=wordmark field
Scores: design 6.5 · usability 6 · creativity 7 · content 7 → overall 6.5, capped to 6.0
Caps applied: inspector FAILs (≤6.0); visible hairline + micro-labels in the footer (≤6.5);
  covered-text, the cookie notice over three headings on the phone (usability ≤6);
  the idea mostly survives a competitor's name, "Order from kitchens near you" could be
  any delivery app's title, and only the plate-and-ticket device is ours (creativity ≤7).
Top three problems:
1. A dark oxblood cookie card covers the bottom fifth of every phone screen and hides three headings
   (mobile-page-01, every frame). It is also the one dark slab left on a page ruled "no dark ground
   anywhere". Fix: a white or warm-grey bar with ink type, dismissed on first scroll, below the content.
2. The operator half becomes three to four phone screens of small product screenshots whose text you
   cannot read at 390 (cook sheet ~8px, storefront mock, slot picker in mobile-page-01 frames 5–7).
   Food leaves the page there. Fix: crop each screen to one readable detail at ≥2× the size, or pair
   each with the dish it serves.
3. The kitchen row is weak. Each card spends two lines on "Nigerian — example photo" before the
   name, the first card shown is "Paused", and the row is cut at the right edge on the phone
   (side-scroll WARN). On desktop the heading sits left and three narrow cards huddle right on
   warm grey (desktop-page-01 frame 2). Fix: name first, the photo caption as small credit text,
   an Open kitchen first, wrap or end the row inside the grid, and make the cards larger on desktop.
```

### OLD (`inspect-before/`)

```
Seen in one second: a three-line maroon headline on a peach wash; on desktop, four emoji tiles.
Idea: none beyond "marketplace with two doors".
Live thing: search with cuisine chips.
Layout words: hero=split / how-it-works=icon row / trust=pill row / footer=dark columns
Scores: design 5 · usability 7 · creativity 4 · content 5 → 5.4
Caps applied: inspector FAILs (≤6.0); eyebrow, 3-line headline, gradient, resting shadows (≤6.5);
  0 real pictures (design ≤6.5); pass 3 fails, could be any marketplace (creativity ≤6).
```

### PREV (`inspect/`)

```
Seen in one second: the jollof plate on a diagonal oxblood slab.
Idea: The Pass, with tickets, a plate and the street fronts of the markets.
Live thing: search plus the kitchen status row.
Layout words: hero=diagonal split + photo strip / kitchens=card row / steps=card row / trust=dark
  split / operators=screen grid / run=dark split / doors=list / footer=dark wordmark + credits + columns
Scores: design 6.5 · usability 6 · creativity 7 · content 6.5 → 6.45, capped to 6.0
Caps applied: inspector FAILs (≤6.0); hairline/micro-label (≤6.5); covered-text at 768 (usability ≤6).
```

---

## 2. critique.md criteria (one line of evidence each)

| criterion | NEW | OLD | PREV |
|---|---|---|---|
| Design (40%) | **6.5**: a strong hero poster (2-line 88px title, real plate breaking the band), but the operator half is small screenshots and the type spreads over 14 sizes | **5**: emoji tiles stand in for food; gradient cards, shadows, a 3-line headline | **6.5**: confident hero, but the oxblood slabs and street-front photos make it heavy and off-subject |
| Usability (30%) | **6**: title, sentence, search and "Order food" all sit on the phone's first screen, but the cookie card hides headings (cap) and body text is 14px | **7**: two plain doors and a search on the first screen, clear and friendly; small grey body text | **6**: the search is on the first screen, but the doors are pushed to the end of the page and text is covered at 768 |
| Creativity (20%) | **7**: the perforated ticket and the plate breaking a tomato band are a real device, carried to the steps and the kitchen display ticket | **4**: the category default, a SaaS hero with emoji | **7**: same device; the market street-fronts were an idea, but the wrong one (R10) |
| Content (10%) | **7**: named dishes (jollof, fish pepper soup, suya, moin moin), the three true facts, and real kitchen names; the "example photo" captions read as clutter | **5**: "connects hungry customers", "go live in a day"; no pictures | **6.5**: concrete and honest, but the pictures show streets, not food; the credits block is long |
| **Overall** | **6.5 → 6.0 (capped)** | **5.4** | **6.45 → 6.0 (capped)** |

The overall tie between NEW and PREV comes only from the caps. Uncapped, NEW and PREV score about
the same as layouts, and they differ on exactly the three things the owner named. Those are scored
in R8–R10 below.

### The fourteen passes on NEW (phone first)

1. **One second / five:** the plate. In five seconds you know it is food ordering, for you, and that you search or tap "Order food". Pass.
2. **Squint:** three masses on the phone (black title, red band + plate, yellow search and door). On desktop the mid page breaks into many medium masses.
3. **Cover the logo:** the hero could be a competitor's apart from the jollof-and-greens plate. The tickets and named dishes carry the idea into the steps, but not into the operator half or the footer.
4. **Edges:** two on the phone (16px gutter, card inset). Desktop is mostly one left edge plus the right column, with the step tickets rotated off-axis.
5. **Space and frame:** margins are equal. On desktop the kitchens section leaves the left half mostly empty under a short heading. The warm-grey band above "Run your food business" is a rest, not a hole.
6. **Type:** decisive jump (88 → 17). The inspector counts 14 sizes, against a ruled 5. Body is measured at 14px. No orphans in headlines.
7. **Colour:** white ground (most), warm grey (support), tomato (band and footer), yellow (actions and one door), leaf (status and trust bullets). The yellow appears on the button, the door and the operator CTA, which is disciplined. The off-palette surfaces are oxblood: the cookie card, the logo tile and the Sign in pill.
8. **Images:** 8 real food photographs (plate, 3 kitchen dishes, 3 step dishes, fish pepper soup), plus product screens. The grade is mixed, mostly phone snapshots, and the fish pepper soup on slats is cooler than the rest. No footage (WARN).
9. **Sameness:** two card rows in a row (kitchens, then steps). The steps / trust / operator-features run is the standard SaaS landing skeleton.
10. **Phone:** a composition of its own. The plate is cropped to the right and breaks the band, and the doors stack. The "Run your kitchen" door falls below the fold, under the cookie card.
11. **Fullness:** desktop screens are full. On the phone the operator screenshots read as filler.
12. **Substance:** the customer half delivers. The operator half's features are named but cannot be read at phone size.
13. **Collisions:** the cookie card covers headings (FAIL). The kitchen row runs off the phone edge.
14. **Competitor / live thing:** the live thing is strong (search plus status). With a competitor's name in place, only the plate and the ticket keep it ours.

---

## 3. Rubric R1–R10

| | NEW | OLD | PREV |
|---|---|---|---|
| R1 food commerce, not a publication | **Pass**: plate, "Find kitchens" and "Order food" on the first phone screen | **Pass, weakly**: reads as an ordering app, but by its words, since it has no food pictures | **Partial**: reads as food on the first screen, then as a local-paper feature (street photos) |
| R2 structure, not skin | **N/A** for one build; against PREV it keeps the same skeleton and changes the skin plus the imagery | N/A | N/A |
| R3 appetising, not bland | **Pass on the customer half**: the plate, the dish cards and the step tickets are at tasting size; the operator half is bland | **Fail**: emoji, no food | **Partial**: the hero plate, then streets |
| R4 real grid at every width | **Pass, one defect**: consistent at 390/768/1440; the kitchen row overflows the phone edge | **Partial**: 24/52px unequal margins at 768 | Pass, the same row overflow |
| R5 predictable nav | **Pass as far as pictures show**: the wordmark is in the header on every screen (that it links home cannot be seen in a screenshot) | Same | Same |
| R6 honest | **Pass**: the three true facts; "example photo" is labelled; "A person reviews every application" | **Partial**: "go live in a day", "order in minutes" | Pass |
| R7 phone first | **Pass with a caveat**: the 390 first screen carries the 2-line headline, one sentence, the search and the "Order food" door; the second door and every heading below sit under the cookie card | **Partial**: 3-line headline, search and chips on the first screen | **Partial**: title, plate, sentence and search on the first screen; no doors until the end |
| R8 warm and light | **Pass, with leftovers**: white ground, tomato band, yellow actions; the remaining dark is the oxblood cookie card, logo tile and Sign in pill | **Pass**: peach and cream, but a dark maroon footer | **Fail**: oxblood slabs in the hero, the trust section, the operator CTA and the footer |
| R9 clear before clever | **Pass**: one title, one search, two labelled doors (Order food / Run your kitchen) | **Pass**: the reference two doors | **Fail**: the doors were moved to the bottom |
| R10 food all the way down | **Partial**: the kitchen cards show dishes, the steps show dishes, and trust shows fish pepper soup; the operator half (3–4 phone screens) and the footer have no food photo | **Fail**: no food anywhere | **Fail**: streets and shop fronts after the hero, and cardless kitchens |

---

## 4. NEW: most visible weaknesses (most visible first)

1. **The oxblood cookie card.** It covers the lower fifth of every phone screen and hides three
   headings (covered-text FAIL). It is the largest dark surface on the page, which contradicts R8 and
   identity's "no dark ground anywhere". The logo tile and Sign in pill carry the same oxblood.
   (Shared chrome, already ruled to a follow-up PR. It is still the first thing a phone visitor sees.)
2. **The operator half goes to screenshots.** The storefront mock, the slot picker, the PPDS label and
   the cook sheet are shown at sizes where the text cannot be read on a phone. Food disappears for
   about three phone screens.
3. **The kitchen row.** On each card "Nigerian — example photo" comes before the kitchen name. The
   first card is Paused. The row is cut at the phone's edge, and on desktop the cards are small and
   pushed to the right half.
4. **The type system has drifted.** There are 14 font sizes against the 5 that identity rules. Body
   text is 14px (FAIL), a paragraph runs 187 characters wide, two text blocks are under 13px, and the
   footer has uppercase micro-labels and hairlines.
5. **The second door is recessive.** "Run your kitchen" is a warm-grey box beside a yellow
   "Order food". On the phone it is below the fold. On desktop the tomato band holds only the
   search, and a dead red strip sits between the search and the plate.

## 5. Remaining template / AI tells

- The "Three steps / What you can trust / Built around… / CTA band / giant-wordmark footer" run is
  the standard SaaS landing order. Only the ticket edges and the food photos disguise it.
- The step tickets are rotated a few degrees on desktop. That scrapbook tilt is a familiar generated
  move, and here it serves no purpose.
- The footer's giant wordmark is a current trend, and with the micro-labels, column links and
  hairline rules it reads as a kit footer.
- The trust bullets use small coloured squares. That is the checklist pattern again (OLD had
  pills with ticks).
- The inspector flags the palette as "cream + terracotta". On screen it reads as tomato and yellow on
  white rather than the assistant palette, but the footer's tomato field with white type is close.

## 6. Does NEW fix the three reasons PREV lost?

1. **Too dark / heavy: yes, largely.** Every oxblood field in the content is gone. The page is white,
   a tomato band, yellow actions and a tomato footer. What is still dark (the cookie card, logo tile
   and Sign in pill) is shared chrome, and it should go in the follow-up PR, because on the phone it
   is the biggest dark thing left.
2. **Less clear / friendly than old: yes on clarity, roughly level on friendliness.** NEW restores
   OLD's two doors and puts the search on the first screen, with a shorter 2-line title. Its first
   phone screen is clearer than OLD's. It is friendlier because of the real food, but the second door
   is weaker than OLD's dark card, and it falls below the phone fold.
3. **Food fading after the hero: half fixed.** The customer half now carries food all the way:
   dish-photo kitchen cards, a dish on every step ticket, and fish pepper soup in the trust section.
   The operator half (about 40% of the page) still has no food photograph. Its product screenshots
   show dish names only as small text.

**Verdict:** NEW beats PREV on R8 and R9 outright and goes most of the way on R10. On the rubric it
is ahead of OLD on everything except the strength of the second door. Its score is held to 6.0 by
shared-chrome FAILs and the cookie overlay, not by its design. Before a blind re-run against OLD,
fix weakness 1 (or ship the chrome PR first) and weakness 2.
