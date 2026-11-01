# Sketch report: The page is the object (seed 1794208771)

## The idea

On this page, **the pink copy of a J'Toye kitchen's docket pad, the paper J'Toye prints for a service (the order ticket off the pass, the consolidated cook sheet, the PPDS label beside it)** becomes **the sheet the whole page is set on**: the first screen is the top of that slip with its lower third below the fold, the kitchens are its first printed table, the three steps are a tear-off receipt with a zigzag end, the trust facts a tilted card, the operator's screens a square plate, the invitation a reply card hanging off its section, and the footer the back of the card. The dealt palette is read as a two-colour print job: pink paper (`--paper`, the dealt ink colour) and burgundy ink on a burgundy counter.

Competitor test: Deliveroo's and Just Eat's paper is a generic thermal receipt. A consolidated cook sheet with per-dish totals and a Natasha's Law label printed from the product belongs to J'Toye's batch and home cooks; the sentence does not survive with a competitor in it.

The artefact chosen from the notes inventory: **the cook sheet / order docket** (inventory items 3 and 4), with the PPDS label and slot card as the other pieces of the same stationery in the showcase.

## The live thing

The postcode-or-dish search on the hero sheet: a real `<form action="/shop" method="get" role="search">` with a labelled `<input name="q">` (placeholder `SE15 or jollof`) and a `Find kitchens` submit button; the six cuisine links under it are plain `<a href="/shop?q=...">`. Works without script, keyboard-operable, thumb-sized on a phone (field and button stack full width).

## Deviations and redraws from the sheet, and why

1. **Hero h1 size.** The blueprint's `clamp(56px, 5.6vw, 88px)` assumes a normal-width face; the sheet says to fill the measure instead. Set from the card's width: `calc(5.9vw - 16px)` on desktop (59px at 1280, 69px at 1440, 97px at 1920), `calc(9.8vw - 18px)` on a tablet, `calc(11.8vw - 10px)` on a phone (36px at 390). Two lines at every width, measured.
2. **Hero card padding 48px, not 56px**, to give the two-line headline its measure at 1280.
3. **Hero ground is flat burgundy, not a full-bleed photograph;** the photograph is tipped onto the card and fills the card's remaining height, so the picture is the part cut by the fold. The sheet allows either.
4. **List sheet holds three kitchens, not 12-24 items.** The copy deck has three published demo kitchens and three server states; the "price" column carries the state word (Open / Paused / Closed), the "description" line the street. No prices, no hours (none in the deck); the sheet ends with the `Find kitchens` link instead of opening hours.
5. **Itinerary slip right-hand column.** The blueprint wants a duration or time per row; none is honest. Each deck clause is split across the leader instead ("Search ... by postcode or dish", "Card ... or cash on collection", "Cash ... where the kitchen allows it", "An account ... not needed"). The final inverted bar carries what the reader holds at the end, "A tracking page from the kitchen to your door", without a total time. The intro paragraph is assembled from deck words only: "Find, order and pay, follow it. Nothing to download."
6. **Proof card is not held to 5:7 and is capped at 760px wide;** its blank tail (120px of paper) is what the section crops. At 56vw with a forced 5:7 the card had 300-500px of empty paper at 1280-1920.
7. **Showcase tabs are real CSS-only tabs** (four radio inputs, labels as the plain-text tab row, `:checked` swaps the square picture and the title/description). Default: Storefront. Keyboard: arrow keys on the radio group; focus ring drawn on the label. Instant switch, no motion. The inactive tab labels are `--muted` at full opacity (5.6:1), per "Adjusted for this page".
8. **Showcase text column is set ragged-left against the right page margin** (heading, tab row, panel). The inspector measures the two page margins from text edges, and on this page every left-aligned block sits on the left margin while nothing ended on the right one (the only right-reaching text was a padded button label), which read as a 28px / 129px frame. Right-aligning this column (and the CTA aside, below) is the honest fix; it also faces the square picture.
9. **CTA section mirrored:** the reply card hangs at the left (columns 1-7, rotated -2 degrees) and the paragraph sits right-aligned in columns 9-12, bottom-aligned. Reason: the blueprint's arrangement repeated the text-left / paper-right frame of the itinerary section directly above it, and the right-aligned paragraph closes the right margin (see 8). Reply card capped at 700px wide.
10. **Footer sign-off at 23px, not 32px.** The curtain footer (the sheet's signature move) is kept exactly as described: a sticky footer under a `main` with its own ground and higher stacking order, no JavaScript, `position: static` under `prefers-reduced-motion`. The inspector's covered-text rule fails any text of 24px or more that the page slides over, so the sign-off is the one step under that line. The company line is the printer's imprint along the bottom edge (small step, 14px, not 12px).
11. **Postcard scallops:** 12 semicircles on desktop and 6 on a phone, drawn as an SVG `mask`, not a `radial-gradient` (which the lint and the rulings ban even inside a mask declaration). Same for the menu sheet's shallow scalloped top and bottom edges and the slip's zigzag.
12. **Dotted leaders** are a 4px SVG dot tile as a background, not a dotted border (the inspector warns on dotted borders).
13. **Showcase pictures** are 960x640 / 1280x800 screenshots cropped square by `object-fit: cover` with a tuned `object-position` for the slot picker and the cook sheet.
14. **Footer links:** Privacy -> `/legal/privacy` (exists). Terms and Allergen policy -> `/legal` (the legal index; there is no terms or allergen-policy page in `frontend/app/legal/` yet). Data request -> `/data-request` (exists).
15. **Tablet (761-1099px)** has no blueprint notes; the hero card widens to columns 2-11, the itinerary stacks heading over slip, the showcase stacks picture over text, the postcard runs full width.
16. **Full-page screenshots** were taken with the footer forced `position: static` for the capture only: Playwright's `fullPage` keeps a sticky footer at its mid-scroll position under `main`, which left the page's last 500px blank in the first capture. The shots therefore show the footer in its revealed end state; the curtain itself cannot be shown in a still.

Not deviated: type system (Barlow Condensed 700 / Barlow 400-500, Google Fonts link as given), the five type steps (14 / 19 / 26 / 48 / head), the palette tokens verbatim, section order and section geometry everywhere else, scalloped inner corners on the hero card (four corner circles in the field colour), one button shape (square), no outlines, no shadows, no gradients, no glass, no entrance motion, no monospace, no eyebrows, no numbering.

## Gate

| | before (round 0) | after (final) |
| --- | --- | --- |
| `lint.mjs` | 0 FAIL, 0 WARN | 0 FAIL, 0 WARN |
| `inspect.mjs --allow still-page` | 8 FAIL, 4 WARN | **0 FAIL, 1 WARN** |

Round-0 FAILs and what fixed them: `broken-image` x4 and `few-pictures` (the inspector served from the sketch folder, so `../../assets` escaped its root; run from the RUN directory, 11 images are seen); `off-centre` (the hero card was 840px wide instead of 612 at 1280, because `aspect-ratio` on a grid item stops it stretching and the width was derived from the content height; `width: 100%` fixed it, and the remaining text-edge imbalance was fixed by items 8-9); `overflow` at 768 (same card bug); `headline-lines` x3 ("Built around the service you already run" on three lines; a 14ch max-width removed, and 7.3vw on a phone); `covered-text` (item 10). Round-0 WARNs `pinned-over-picture` and `long-pin` (the footer title under the curtain) went with item 10; `palette-cream` (a #f7f2ea letterbox colour behind the screenshots) was removed.

Remaining WARN: `no-footage`. `RUN/assets/` holds no clip and the brief restricts pictures to that folder; no honest moving picture exists for this subject yet.

Inspector output: `inspect/report.json`, `inspect/desktop-*.jpg`, `inspect/tablet-*.jpg`, `inspect/mobile-*.jpg`. Screenshots at 390, 768, 1280 and 1920 px: `shots/<width>.png` (every one opened and read after the last change).

Measured at the end: h1 two lines at 390 / 768 / 1280 / 1440 / 1920 (36 / 57 / 60 / 69 / 97 px); no horizontal overflow at any of the four widths; body 19px in full ink; three ground colours (#3d0012, #ff99d3, #4d0519); Barlow and Barlow Condensed both loaded.

## Pictures used

Found pictures (world around a kitchen; none captioned as a J'Toye kitchen's own):

- `93-bruce-grove-scorpios-chicken-1413263680.jpg`: tipped onto the hero card. "93 Bruce Grove - Scorpios Chicken (1413263680)" by Alan Stanton, CC BY-SA 2.0, via Wikimedia Commons.
- `brixton-market-sw9-geograph-org-uk-2022036.jpg`: full-bleed ground behind the kitchens sheet (an `<img>`, with alt). "Brixton Market, SW9 - geograph.org.uk - 2022036" by Phillip Perry, CC BY-SA 2.0, via Wikimedia Commons. Note: 1024px source, upscaled at 1920.
- `jollof-rice-with-vegetable.jpg`: tipped into the proof card. "Jollof rice with vegetable" by Segun Famisa, CC BY-SA 4.0, via Wikimedia Commons.
- `jerk-chicken-as-served-july-05.jpg`: the stamp on the postcard. "Jerk chicken as served july 05", CC BY-SA 3.0, via Wikimedia Commons.

The footer credit line names exactly these four (the `--credits` output trimmed to what the page uses; `credits.json` left untouched because the other sketches share it). Not used: `suya-seller.jpg`, `panoramio-3174.jpg`, `food-store-and-caribbean-takeaway-woodville-road.jpg`.

Real product (J'Toye's own, captioned as such): `storefront-menu.webp`, `slot-picker.webp`, `kitchen-cook-sheet.webp`, `ppds-label.webp` in the showcase tabs; one visible at a time.

Logos: `logo-peckham-jollof.png`, `logo-mama-ades.png`, `logo-brixton-grill.png` at 48px on the kitchens sheet. They are gradient tiles with initials (brand assets as supplied, kept as-is per the skill's existing-brand rule); the Brixton Grill tile reads "BV", which does not match the name and is worth checking at source.

No dealt picsum photographs, no SVG drawings standing in for photography, no video.

## Sample content to confirm or swap before launch

- The three kitchen rows (Peckham Jollof / Rye Lane, SE15 / Open; Mama Ade's / Peckham / Paused; Brixton Grill / Brixton Market, SW9 / Closed) are the deck's examples of the three server states, not live data; the real row comes from the server.
- `SE15 or jollof` is the deck's placeholder.
- "Saturday 12:00-14:00, 20 orders" in the slots panel is the deck's example string.
- The showcase screenshots show the demo kitchen "Adaeze's Kitchen".
- Terms and Allergen policy link to the legal index until those pages exist.
- The footer repeats the hero's supporting line as the postcard's message (the deck has no separate footer message).
