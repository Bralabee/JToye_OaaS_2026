# The Pass — 007 revived under the 2026-11-01 rulings (candidate D7)

Source: `.planning/sketches/007-the-pass-full-page/index.html` (006-A's winning language as a full page).
Built against `notes.md` (argument + copy deck, verbatim), `rulings.tsv`, `references/README.md`, D4/D7/D10 in
`decisions.md`, and the taste-skill house rules. One file: `index.html`. Pictures by `../../assets/<file>`.

## Kept from 007 (structure)

- The diagonal oxblood slab hero with the type and the plate interlocked, on the 1280-capped 12-column grid.
  The plate still breaks the slab edge; the headline now sits OVER the plate's edge instead of under it (nothing
  may lie on top of a headline).
- The strip of varied-size, slightly rotated picture cards breaking the scene's bottom edge.
- How-it-works as three order tickets with perforated tops, stepping down left to right.
- The asymmetric showcase (8/5 then 5/8 grid), now holding the four real product screens.
- The operator section on the angled two-tone oxblood ground, with the kitchen-display ticket (a `data-ui`
  fragment; order reference `AK-1044`, lines priced from the real storefront menu, total adds up to £32.00).
- The typographic doors and a footer where the name returns at full width.
- The oxblood + Work Sans identity (D4 lifted the lock; kept because it serves the page). Amber kept only as the
  action colour.

## Removed, and under which ruling

| 007 element | Gone because |
| --- | --- |
| Unsplash stand-ins, invented kitchens (Mama Efe's, Yard Vibes…), prices, "27 kitchens live", 4.8 stars, "12 min" stats, the customer quote and stars, "open now" glows, FHRS pills on photos | Nothing real can be shown (notes.md R6, D10); REMOVED, not reworded |
| Amber marquee band, `.grain` overlay, gradient image placeholders, perforation drawn with `radial-gradient` | `gradient-decoration` ban (the perforation is now an SVG circle tile) |
| Translucent `rgba` text box in the hero, `rgba` stamps | `glass` ban; on the phone the supporting copy sits on a solid oxblood field instead |
| Three shadow tokens on tickets, cards, plate, search | no shadows at rest (house rule); surfaces step by tone |
| Emoji icons, `.fb` emoji fallbacks, flag emoji | no emoji |
| Eyebrows ("How it works", "Tonight's kitchens"), tracked-caps `h4`s, `.stamp` micro-labels | no eyebrows, no tiny uppercase tracked labels |
| "TICKET 01/02/03", "No 0247…", "Incoming · No 0250" | no index numbering (the KDS ticket carries the product's own reference form, `AK-1044`) |
| Amber outlined accent word in every headline, cream/stroke third line | no styled accent word; one face, one colour |
| Caveat handwriting notes ("on the pass now", "easy as that") | second decorative typeface; invented claims |
| Dashed rail, dashed ticket lines, 1–2px door rules | no thin rules (doors are tone bands) |
| Hover lifts on non-link cards, plate hover, marquee animation, chip transitions | `card-hover`: feedback only on controls; `ambient-motion`: at most one moment |
| Orange-700 `#C2410C` buttons | reads as terracotta/rust (house palette rule); action is amber on oxblood |
| Pill radii, 9–16px soft radii | corners decided once: square (the plate circle excepted) |
| Viewport-switch sketch tool, `onerror` script | not part of a page |

## Under the new rules

- Content present at load; no entrance or scroll-reveal. The ONE motion moment: the hero plate settles
  from -16° to -6° over 0.9 s on load, CSS only, inside `prefers-reduced-motion: no-preference` (the still page is
  the finished page). Hover/focus feedback only on links and buttons (nav, kitchen rows, doors, CTA).
- The one live thing: the postcode-or-dish search is a real `<form action="/shop" method="get">` with `name="q"`,
  labelled "Postcode or dish", placeholder "SE15 or jollof"; the cuisine links are plain `<a href="/shop?q=…">`.
- Headlines two lines or fewer at 390/768/1280/1920 (measured by the shot script and by inspect: longest 2).
  Body 17px in full ink; captions/legal 15px. Nine font sizes in use including the footer name.
- Section grounds: stone `#F2F1EE` / `#E3DED6` (chosen under the inspector's yellow-cream threshold), oxblood
  `#3A0B0D`, `#1C0405`. Credit line from `assets.mjs --credits` in the footer at body size, links kept.

## Gate results

| | lint FAIL / WARN | inspect FAIL / WARN |
| --- | --- | --- |
| Round 0 (first build) | 0 / 0 | 6 / 3 |
| Round 1 (final) | 0 / 0 | 0 / 1 |

Round-0 inspect FAILs and what fixed them: `broken-image` + `few-pictures` (the inspector serves from cwd, so
`../../assets` 404'd when run from elsewhere; run it from the design-run checkout, then all 14 images load);
`icon-cards` (72px logos, not 64, so the kitchen rows no longer read as icon-topped cards); `contrast` on the
phone hero (the slab is a `::before` the probe cannot see; the supporting block now has its own solid oxblood
field); `edge-text` in the operator section (`padding` had zeroed the horizontal gutter; now `padding-block`);
`headline-lines` at 390 (kitchen names 20.8px on the phone so the whole row is not read as one 3-line title).
WARNs cleared: `palette-cream` (ground and cream text moved under threshold), `contrast` on "Mark ready"
(green darkened), `card-rows` (ticket columns 1.08/0.92/1fr). Remaining WARN `no-footage`: defended, no honest
clip of the subject exists. `--allow still-page` as instructed.

Visual checks (Read tool) at 390, 768, 1280, 1920 and the inspector's fold/page renders: zero horizontal
overflow at all four; the 768 ticket stack was one-sided and is now staggered; the showcase rows were auto-placed
out of order and are now pinned; the phone strip's fifth card now spans both columns.

## Pictures used (all credited in the footer, `assets/credits.json`)

| Where | File | Caption on the page |
| --- | --- | --- |
| Hero plate | `jollof-rice-with-vegetable.jpg` | none (alt only) |
| Strip | `suya-seller.jpg` | A suya seller at his grill |
| Strip | `jerk-chicken-as-served-july-05.jpg` | Jerk chicken, as served |
| Strip | `panoramio-3174.jpg` | Rye Lane Market, Peckham |
| Strip | `brixton-market-sw9-geograph-org-uk-2022036.jpg` | Brixton Market, SW9 |
| Strip | `food-store-and-caribbean-takeaway-woodville-road.jpg` | Woodville Road, Thornton Heath |
| Proof | `93-bruce-grove-scorpios-chicken-1413263680.jpg` | Scorpio's Chicken, Bruce Grove, Tottenham |
| Showcase | `storefront-menu.webp`, `slot-picker.webp`, `kitchen-cook-sheet.webp`, `ppds-label.webp` | the deck's four lines |
| List | `logo-peckham-jollof.png`, `logo-mama-ades.png`, `logo-brixton-grill.png` | kitchen names |

No found picture is captioned as a J'Toye kitchen's dish or shop. The three logo PNGs are the product's own
assets and contain gradients inside the image; kept as brand assets per the house rule, said here. Note the
Brixton Grill file reads "BV", which is what exists in `assets/`.

## Sample content (not from the copy deck; confirm or cut)

- The strip captions and the proof-picture caption (factual place/dish descriptions).
- The list sub-line "Open, paused or closed, as the kitchen sets it." and the three status words as examples of
  the server's three states (notes.md).
- The KDS ticket contents (AK-1044, 2 × Jollof rice with chicken £19.00, 1 × Beef suya skewers £8.50,
  1 × Moin moin £4.50, "Collection · Saturday lunch batch, 12:00–14:00", total £32.00).
- Door sub-lines ("Postcode or dish, then order from their own storefront"; the CTA line reused).
- Footer link targets `/privacy`, `/terms`, `/allergen-policy` (the deck names the labels, not the paths).

Shots: `shots/390.png`, `shots/768.png`, `shots/1280.png`, `shots/1920.png`. Inspector output in `inspect/`.
