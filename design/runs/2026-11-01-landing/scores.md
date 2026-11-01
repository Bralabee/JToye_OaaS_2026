# Step 8 — independent scores, landing run 2026-11-01

Scorer: a separate agent that did not build the page. Scored from the screenshots in `inspect/`
(new) and `inspect-before/` (old), plus the `inspect.mjs` findings in each `report.json` (needed for
the critique.md caps). The builder's own write-up (`direction.md` "Steps 6–7") was not read.
Scale: critique.md's 1–10 per criterion, weighted design 40 / usability 30 / creativity 20 /
content 10; rubric lines R1–R7 as PASS / PARTIAL / FAIL. Phone judged first (R7).

Note on the cookie banner: it is fixed, dark, about 170px tall, and covers the bottom 20–25% of every
phone frame in both runs (and a quarter of the tablet/desktop frame bottom-right). It is the cause of
the new page's `covered-text` FAIL. It is scored as seen, because it is what a first visit shows.

---

## NEW page (`inspect/`)

### critique.md, round 1 shape

```
ROUND 1 (independent)
Seen in one second: the plate of jollof and greens breaking the oxblood diagonal (phone, tablet, desktop).
Idea (one sentence): the kitchen's pass — a plate on the slab and white order tickets carry every step.
Live thing: the kitchen cards with Open / Paused / Closed and "Use my location"; the search.
Layout words by section: hero=split-slab / photo strip=scatter / kitchens=card-row / steps=ticket-stack /
  trust=split / operators=screenshot-grid / run-your-business=split / doors=list / footer=statement+credits+link-grid
Scores: design 6.5 · usability 6 · creativity 7 · content 8 → arithmetic 6.6 → overall 6.0 (capped)
Caps applied: inspect.mjs FAIL ×3 (micro-label, hairline, covered-text) → overall ≤ 6.0;
  micro-label + hairline visible → overall ≤ 6.5; still-page (allowed) → design ≤ 7;
  covered-text → usability ≤ 6; idea passes for the nearest competitor → creativity ≤ 7.
Uncapped estimate if the three FAILs are fixed: design 7 · usability 7.5 · creativity 7 · content 8 → 7.25.
```

### Criteria, phone → tablet → desktop

| Criterion | Score | Evidence (screenshot, region) |
|---|---|---|
| **Design (40%)** | **6.5** | Phone `mobile-fold.jpg`: a decisive 2-line headline, the plate cropped off the right edge over the slab's diagonal, and the amber search on oxblood — a real poster; but `mobile-page-01.jpg` frames 5–6 shrink the four product screens to 358px where nothing is legible and the slot-picker shot reads washed-out, and the page ends in two stacked footers (`mobile-page-02.jpg`). Tablet `tablet-fold.jpg` leaves the left half empty for ~430px between headline and sentence. Desktop `desktop-fold.jpg` is the strongest screen (headline "you" overlapping the plate, polaroid strip). Capped ≤ 7 by still-page; 12 font sizes measured. |
| **Usability (30%)** | **6** (cap; ~7.5 uncapped) | Phone first screen carries headline, one sentence and a real "Find kitchens" button above the fold (`mobile-fold.jpg`); kitchen cards row runs off the right edge at 390 (`mobile-page-01.jpg` frame 3, "Peckham Jollof Co." cut, `side-scroll` WARN); the cookie banner covers "Built around the service you already run" (`tablet-page-01.jpg` frame 4; inspector `covered-text` FAIL at 768 and 390). |
| **Creativity (20%)** | **7** | A statable idea carried through: tickets with perforated tops for the steps (`mobile-page-01.jpg` frame 3), the "Incoming AK-1044 / Mark ready" ticket (`desktop-page-02.jpg` frame 2), plate on slab. Pass 3 passes (named food, named streets), but swap in Slerp or Deliveroo and the plate-and-ticket still works → cap 7. |
| **Content (10%)** | **8** | Specific and true-sounding: jollof, suya, Rye Lane, Brixton Market, "Saturday 12:00–14:00, 20 orders", Natasha's Law label with real allergen emphasis (`tablet-page-01.jpg` frame 6). Loses a point for the 8-line Wikimedia credit paragraph set at running-text size in the footer (`mobile-page-02.jpg` frame 1, `desktop-page-02.jpg` frame 3). |
| **Overall** | **6.0** | 0.4·6.5 + 0.3·6 + 0.2·7 + 0.1·8 = 6.6, held to 6.0 by three inspector FAILs. |

### Fourteen passes (short)

| Pass | Result |
|---|---|
| 1 one second / five | Plate; "Order from kitchens near you" + search answers what/who/next on phone. PASS |
| 2 squint | Phone fold: headline, plate, oxblood slab — three masses. PASS. Middle of the phone page (operators) goes to many small grey masses. |
| 3 cover the logo | Named food and streets; could not be a generic SaaS. PASS |
| 4 edges | Phone: 16px edge held, except the Scorpio's photo and caption inset to ~78px (`mobile-page-01.jpg` frame 5). Desktop: one left edge at 112px reused. Mostly PASS |
| 5 space and frame | Rests, not holes, except tablet hero left half (`tablet-fold.jpg`) and the empty oxblood + stone bands around the Scorpio's photo on phone (frame 5, ~190px combined) and above "Built around…" on desktop (`desktop-page-01.jpg` frame 4). None over half a screen. |
| 6 type | Display→body jump decisive; no 3-line headline; 12 sizes measured (WARN); footer caps labels (FAIL). |
| 7 colour | Oxblood/stone dominant, amber on search button and CTA — but amber also on the trust bullets, the door arrows and the footer labels, so the accent is in ~5 places. |
| 8 images | 7 real photographs (jollof, suya seller, jerk chicken, Rye Lane, Brixton Market, Woodville Road, Scorpio's) + 4 product screens. Grades mix (warm food vs. grey-sky Geograph streets). No footage. |
| 9 sameness | "split" three times (hero, trust, run-your-business); two card rows (inspector WARN). |
| 10 phone | Its own crop (plate off-edge, headline not over the plate as on desktop). PASS for the fold; the operator block is the desktop grid stacked. |
| 11 fullness | Desktop screens are full; no wireframe screens. |
| 12 substance | All asked sections present; primary action is a real button on screen 1. |
| 13 collisions | Cookie banner over a headline (FAIL); kitchen row clipped at 390. |
| 14 competitor / live | Idea passes for a competitor (cap 7); live thing present (status cards, location). |

### Rubric R1–R7 (phone first)

| Line | Verdict | Evidence |
|---|---|---|
| R7 Phone first | **PARTIAL** | `mobile-fold.jpg` carries headline, one sentence and the search in the first screen, with its own crop — the composition passes. Not proven: no throttled-mobile CWV in these artifacts, hover-latch not visible in stills; and the 390 run has the `covered-text` FAIL and a sideways-scrolling kitchen row. |
| R1 Food commerce, not publication | **PASS** | Phone and desktop folds: a plate, "Order from kitchens near you", a search with "Find kitchens". Nobody would call this a newspaper. |
| R2 Structure, not skin | **PASS (vs old)** | Only one candidate is in these captures, so it can only be judged against the old page: a different skeleton entirely (slab hero, photo strip, ticket steps, operator screenshots, ticket CTA) vs. old's hero + two door cards + icon steps + chips. |
| R3 Appetising | **PARTIAL** | The hero plate is food at a size you can taste on every width. After it, food appears in only two small polaroids (suya seller, jerk chicken); the rest of the imagery is grey street façades and product screens (`mobile-page-01.jpg` frames 2, 5). |
| R4 Real grid at every width | **PASS (minor)** | Desktop keeps one 112px edge and a capped grid; tablet steps stagger deliberately. Off-grid: the Scorpio's inset on phone and the empty left half of the tablet hero. |
| R5 Wordmark goes home | **PASS (visual)** | Wordmark present in a sticky header in every frame at every width; the link target is not visible in a screenshot. |
| R6 Honest | **PASS, one flag** | Claims match the brief's three facts; "A person reviews every application" and "cash on collection where the kitchen allows it" are careful. Flag: "Order and pay — card, or cash…" (`tablet-page-01.jpg` frame 3) states card payment plainly while the brief says cards are in Stripe test mode until the first production tenant. |

---

## OLD page (`inspect-before/`)

```
ROUND 0 (baseline)
Seen in one second: phone — a 3-line headline and a row of emoji chips; desktop — four emoji tiles.
Idea (one sentence): none stated by the page; a generic two-audience marketplace hero.
Live thing: the search only.
Layout words: hero=split / doors=card-pair / how-it-works=icon-row / trust=chip-row / footer=link-grid
Scores: design 4.5 · usability 6.5 · creativity 4 · content 4 → overall 5.0 (4.95)
Caps applied: inspect FAIL ×13 → ≤ 6.0 (not binding); 3-line headline, eyebrow, micro-label, gradient,
  shadows, hairline → ≤ 6.5 (not binding); 0 pictures → design ≤ 6.5; pass 3 fails → creativity ≤ 6;
  emoji stand-ins as placeholders → content ≤ 5.
```

| Criterion | Score | Evidence |
|---|---|---|
| **Design (40%)** | **4.5** | Phone `mobile-fold.jpg`: 3-line headline under a "🇬🇧 INDEPENDENT UK KITCHENS" eyebrow, then 14px grey body; orange-gradient door card; no photograph anywhere; desktop `desktop-fold.jpg` fills the right half with four pastel emoji tiles; tablet `tablet-fold.jpg` ends in a ~200px empty band with a hairline. |
| **Usability (30%)** | **6.5** | Search and two door cards make the next step clear on the phone fold, but the focal-point FAIL (no dominant element), 3-line headline at 390 and 14px body text slow the read. |
| **Creativity (20%)** | **4** | The category default: eyebrow + headline + pill search + emoji chips + gradient card + icon steps. Cover the logo and it could be any marketplace. |
| **Content (10%)** | **4** | Emoji in place of food; "order in minutes… go live in a day" is a slogan; the trust facts are reduced to three chips. |
| **Overall** | **5.0** | 0.4·4.5 + 0.3·6.5 + 0.2·4 + 0.1·4 = 4.95. |

| Line | Verdict | Evidence |
|---|---|---|
| R7 Phone first | **PARTIAL** | Phone fold has headline, sentence, search, but the headline is 3 lines and the sentence 4 lines; the composition is the desktop stack squeezed. |
| R1 Food commerce | **PARTIAL** | Reads as food ordering only through the emoji chips; no food. |
| R2 Structure | n/a | Baseline. |
| R3 Appetising | **FAIL** | No food photography at all; emoji stand-ins (`desktop-fold.jpg` right half). |
| R4 Grid | **PARTIAL** | Off-centre margins at 768 (24px left / 52px right, inspector WARN); tablet trailing hole. |
| R5 Wordmark | PASS (visual) | Header wordmark on every frame. |
| R6 Honest | **PARTIAL** | "Order in minutes", "go live in a day", "Track live … in real time" are speed/live claims beyond what the brief lets the page say. |

---

## Side by side

| | Design | Usability | Creativity | Content | Overall |
|---|---|---|---|---|---|
| Old | 4.5 | 6.5 | 4 | 4 | **5.0** |
| New | 6.5 | 6 (cap) | 7 | 8 | **6.0** (cap; 6.6 arithmetic, ~7.25 with FAILs fixed) |

The new page is a clear step up on every criterion except usability, where it loses only to a cap
caused by the cookie banner. Still under critique.md's 7.5 bar.

---

## Most visible remaining weaknesses (NEW), most serious first

1. **The cookie banner covers a headline and a quarter of the phone screen** — `mobile-page-01.jpg`
   every frame (bottom ~170px), `tablet-page-01.jpg` frame 4 ("Built around the s…" cut). Inspector
   `covered-text` FAIL at 768 and 390; caps usability at 6 and overall at 6.0 on its own. Shrink it to
   a one-line bar or give the page a bottom padding equal to it.
2. **The operator block dies on the phone** — `mobile-page-01.jpg` frames 5–6: the storefront, slot
   and cook-sheet screenshots are 358px wide with unreadable text, and the slot-picker shot looks
   washed/blurred (also `tablet-page-01.jpg` frame 5). On the primary viewport these are grey rectangles
   with captions. Crop each screen to the one readable detail it proves (the "20 orders" slot, one cook
   sheet row) instead of shrinking the whole UI.
3. **Two footers and a credit wall** — `mobile-page-02.jpg` and `desktop-page-02.jpg` frames 3–4: a
   giant "J'Toye" statement footer, then an 8-line Wikimedia credit paragraph at body size, then a
   second complete link-grid footer with its own wordmark, uppercase amber labels (`micro-label` FAIL)
   and divider rules (`hairline` FAIL). On the phone this is about two full screens of footer. Merge to
   one footer; set credits small and collapsed.
4. **Food leaves after the hero (R3)** — `mobile-page-01.jpg` frames 2 and 5: below the plate, five of
   seven photos are grey-sky Geograph street façades in a cooler, flatter grade than the food. The
   appetite the hero builds is not repaid; the "Kitchens on J'Toye" cards have no picture at all.
5. **Holes at tablet and between sections** — `tablet-fold.jpg`: left half empty for ~430px between
   headline and sentence while the plate sits right; `mobile-page-01.jpg` frame 5 and
   `desktop-page-01.jpg` frame 4: an empty oxblood band then an empty stone band (~190px) around the
   trust/operators seam. Minor individually; together they make the middle feel unplanned.

---

## Template / AI-made tells still present (NEW)

Yes, fewer than before, mostly below the fold:

- **Uppercase letter-spaced amber footer labels** ("FOR CUSTOMERS", "LEGAL", "FOR OPERATORS") and
  **divider hairlines** in the footer — both inspector FAILs, both critique.md cap items.
- **The stock four-column link footer** appended under the brand footer — the kit footer survived the
  redesign alongside the new one.
- **"Three steps, no app"** — the three-step how-it-works block is a SaaS-template staple; the
  ticket skin disguises it but the skeleton is the same.
- **"What you can trust" three-item square-bullet list** beside a photo — the trust-badge section of
  every template, in a split that repeats the hero's split.
- **Scattered tilted polaroids** — a recognisable generated-landing-page trope; here it is earned
  in part by real, captioned places, but the tilt-and-white-frame treatment is the tell.
- **Two rows of identical boxed cards** (kitchens, steps) — inspector `card-rows` WARN.
- **Closing "Find kitchens → / For kitchens →" door band** that repeats the hero's two doors.

Gone since the old page: emoji, gradients, resting shadows on controls, the eyebrow kicker, pill
search, 3-line headline, 14px body, the emoji-tile hero.
