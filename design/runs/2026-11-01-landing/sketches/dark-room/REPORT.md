# Sketch: The dark room (seed 998514087)

One static HTML file, `index.html`, built to `draw/dark-room.md` with the copy deck in `notes.md` verbatim.
No script on the page: the active list is a radio group styled with `:has()`, the one motion moment is a CSS
scroll-driven animation, the search is a plain form.

## The idea sentence

**On this page, the lit pass of a kitchen at night, the one bright place in a dark room where the finished
plates wait under a single light for the ticket to be called, becomes the gallery lighting of the dark room:
one charcoal field from top to bottom, and in it each picture lit like a plate on the pass, the brightest
thing on its screen.**

Of the two inventory items offered, I chose **the kitchen at night and the lit pass** (inventory 3, 4), not
the shopfront at dusk. Reason: the only dusk picture in `assets/` (Woodville Road, Thornton Heath) shows a
row of takeaways with their shutters down, which says "closed" on a page whose first claim is that kitchens
are taking orders. The pass reading is carried by what the assets do hold: a cook's table of grilled meat
under one light (the hero), the four product screens lit as plates in the showcase band, and the kitchen
screen's "New orders, waiting for you to accept" strip as the ticket arriving on the pass (the cta).
Competitor test: Deliveroo or Just Eat put no kitchen's pass and no named street on their page; their
kitchens are "partners". The pass with its ticket states (Incoming, Mark ready) is this product's own artefact.

## The live thing

The postcode-or-dish search in the hero: `<form action="/shop" method="get" role="search">` with the field
`name="q"` labelled "Postcode or dish", placeholder "SE15 or jollof", button "Find kitchens". It is a real
GET to `/shop?q=…`; the six category links under it are crawlable plain links to the same route. Works from
the keyboard, with a thumb, and without script. Nothing else on the page is interactive beyond links, the
radio list and the controls' own feedback.

## Deviations from the sheet, and why

Settled in the sheet's own order (house rules → direction → type and palette → hero → sections); project
rulings in `design/rulings.tsv` and the owner's brief for this sketch override the sheet.

Hero
- **The photograph is sticky within the hero only**, not "for the length of the page": the showcase band,
  the cta field and the footer are full-bleed by their own blueprints and a page-long sticky picture would
  sit across all three. With the hero at one screen the stickiness has no travel; the list section's own
  sticky photograph then takes the left wall.
- **The h1 is the copy deck's line, not the name.** House rule: the first screen's h1 says what this is.
  The name runs along the bottom as the blueprint describes (lowercase, Zodiak 100, tracking -0.04em, one
  line) but as a `<p>` holding the home link, not the h1. The h1 "Order from kitchens near you" is set in
  capitals per the type system ("capitals for the headline"), at `--step-head`, in the right half.
- **Wordmark size**: the blueprint's `clamp(120px, 12vw, 200px)` and its "starts over the photograph's
  corner and ends flush with the right margin" agree only for a nine-letter name; "j'toye" at 200px is
  460px wide and would float short of both. I kept the crossing and the full width and let the size follow:
  `font-size: min(calc((60vw - margin - 48px) / 2.25), calc(100svh - 520px))`, 2.25em being the measured
  advance of "j'toye" in Zodiak 100 at -0.04em. The `svh` cap keeps the whole first screen (nav, h1,
  sentence, search, name) inside one 1280x800 screen; on such a short screen the name ends a little short
  of the right margin instead of overrunning the fold. The 10vw overlap became 7vw for the same reason.
- `mix-blend-mode: difference` dropped as the sheet's own adjustment says; solid `--ink` on a photograph
  darkened with `filter: brightness(0.78) contrast(1.05)` (the sheet's treatment, no scrim, no gradient).
- Five links at top-right became two links and the action: the deck has three nav items and "Find kitchens"
  is both a link and the action, so it appears once, as the action. Nothing invented.
- Radius 0 everywhere (the sheet's adjustment: one radius for the page; the direction frames nothing).
- Phone: the photograph is 54svh, not 58%, so the search button ends at 835px on a 390x844 screen (at 58%
  it ended at 860px, below the first screen). The photograph sits on the page's 20px margin rather than
  inset 12px, for one pair of margins (inspect flagged 12 against 20 as off-centre). The "menu word" is a
  real link to the footer's navigation list (`#site-nav`), the plain fallback for a menu.

List (sticky photograph, active list)
- Three rows, not five to nine: the deck names three kitchens and nothing may be invented. No three-line
  descriptions (none exist). The row's right-hand slot carries the status word and the locality.
- No fading: inactive names are `--muted` (6.1:1), the active name `--ink`, its status word in the accent,
  its logo at full colour; others' logos are desaturated. Active state by radio (`:checked`), so hover,
  focus and tap all work and the keyboard moves through rows with the arrow keys. "Scrolling a row to the
  middle makes it active" needs script and a scroll-triggered change; dropped under the project rulings.
- Names at the blueprint's own `clamp(20px, 1.6vw, 24px)` in Zodiak 300 (my first pass used `--step-lead`,
  which the inspector read as headlines and wrapped to three lines on the phone).
- The left picture is captioned under it ("Rye Lane Market entrance, Peckham, 2011" and so on) so no found
  picture reads as a kitchen's own shop or dish. The jollof plate is captioned as the plate it is.
- Phone: the 4:3 band is `position: sticky; top: 0; height: 38svh`; with three rows there is nothing to
  scroll beneath it, so it simply leads the section.

How it works (drag row of moments)
- Three columns, not six to nine, filling the width on desktop; a three-item row cannot bleed off both
  edges. The two 48px buttons and the 6px progress bar are dropped: nothing scrolls on desktop, and on the
  phone the sideways row was measured by the inspector as text cut at the screen edge (FAIL edge-text) and
  as a side-scroller, so **on phone and tablet the three moments stack**, each a band (text, varied air,
  4:5 picture; at 600-900px text left, picture right). This is the one blueprint I could not keep whole.
- Captions are Hanken 500, never bold (type system: "never bold"); "bold" mapped to the emphasis weight.
- The dash in the deck between caption and text is the deck's separator, not copy; captions and texts
  are set as two elements, the text's first letter capitalised.

Proof (names as one sentence)
- No client, press or partner names exist (rubric R6), so the three true facts are the statements. Set as
  three `<p>` at `--step-lead` (24-36px), not one run-on sentence at 40-76px: the sheet's own adjustment
  says a large sentence over several lines reads as a long headline. The heading "What you can trust" is
  the plain words that open the block.
- The cycling lit name (a timed class toggle every two seconds) is a second motion moment: dropped under
  the ambient-motion ruling. All statements are `--ink`.

Showcase (band of unequal panels)
- Four panels at flex 5, 2, 3, 2 with the four real product screenshots as tall slices, as drawn. **Each
  panel carries its own title** over a flat scrim at its top-left (the deck has four named items; one title
  on the first panel would drop three), and **four caption lines** under the band instead of one, for the
  same reason. The scrim is a flat `rgba(28,28,28,.82)` box behind the title, the one allowed exception.
- No hover growth: the panels are not links (card-hover ruling: feedback on clickable cards only).

Cta (first step, already open)
- **No create dialog.** The deck gives no dialog, field label or example value, and a "Name your workspace"
  fragment would be invented product UI. The slot holds the real kitchen screen instead:
  `kitchen-cook-sheet.webp` cropped to its lower strip (the last cook-sheet lines and "New orders: NEW,
  waiting for you to accept"), 440px wide, which is literally the first step already open for an operator.
  The button "See how it works for kitchens" sits under the closing line on the left, where the deck's copy is.
- **The field is the pale concrete `--field-1`, not the accent**: the palette (above the blueprint in the
  settling order) reserves the sage for the button and active states and allows one section to flip to pale
  concrete with dark text. Text on the field is `--field-1-ink` (9.9:1); the muted ink is not used there.
- The blueprint's dialog shadow does not arise (no dialog).

Footer (columns above, wordmark below)
- **Not inverted**: the direction ("one dark field from top to bottom") is above the blueprint; the pale
  cta sliding off a dark footer is the curtain. Sticky `bottom: 0` under `main` as drawn.
- Four columns are the company line, navigation, legal and the picture credits (no social or hours exist).
  The credits sit in the fourth column at 16px, full ink, links underlined. Legal line at the small step
  (13-14px, the sheet's adjustment of its 12px).
- The wordmark is inline SVG text, viewBox fitted from the measured advance, `textLength` with
  `lengthAdjust="spacing"` so it never wraps.

Motion
- One moment: the hero wordmark scales 1 to 0.94 on a CSS view timeline as the first screen leaves
  (`@supports (animation-timeline: view())`, inside `prefers-reduced-motion: no-preference`; static
  otherwise). Controls have 120-300ms colour, underline and press feedback. No entrances, no reveals, no
  moving picture, no video: the rulings cap motion at the one moment, so the inspector's `still-page` is
  allowed and its `no-footage` WARN is defended by the same ruling.

Type sizes: the sheet's four steps plus the display step for the closing line (`clamp(44px, 5.2vw, 100px)`;
the blueprint's 7vw put "Run your food business" on three lines in columns 1-5) and the hero wordmark's own
size. The inspector counts 8 sizes (14/16/21/23/36/58/75/348), under its 9 ceiling.

## Gate results

| Round | lint | inspect (1440 / 768 / 390, `--allow still-page`) |
| --- | --- | --- |
| Before fixes (first full render) | 0 FAIL, 0 WARN | 2 FAIL, 7 WARN |
| After round 2 (final) | 0 FAIL, 0 WARN | 0 FAIL, 1 WARN |

Round-1 FAILs: `edge-text` (the phone drag row's second column cut at the right edge; fixed by stacking) and
`headline-lines` ("Mama Ade's Paused Peckham", the list row read as a 24px headline on the phone; fixed by
the blueprint's 20-24px name size). Round-1 WARNs cleared: `side-scroll`, `empty-middle` (both the drag
row), `mixed-font-headline` (the rows), `off-centre` at 390 (12px photo inset against the 20px margin),
`palette-cream` (a near-white fill behind the cta crop, removed), `measure` (the credits paragraph, capped
at 62ch). Remaining WARN: `no-footage`, defended above.

Fixes made from looking at my own screenshots, before the gate: the h1 wrapping to three lines at 1280 and
1920 (a 34rem measure; removed, and `--step-head` set to `0.95rem + 3vw`), the phone page scrolling
sideways by 61px (the search row's intrinsic width in a `1fr` grid track; `minmax(0,1fr)` and `flex-basis:
0`), the phone name placed after the copy instead of under the photograph (flex order), the hero
overrunning an 800px screen (the `svh` cap), the cta crop showing the wrong region (a `top` percentage
resolves against height, not width; `-420/380`), and the wordmark overrunning the right margin at 1920 by
44px (the right column's 48px inner padding missing from the width formula).

Full-page screenshots: `shots/390.png`, `shots/768.png`, `shots/1280.png`, `shots/1920.png`. For these flat
captures only, the footer was unstuck (`position: static` injected at capture time): Chromium paints a
`position: sticky; bottom: 0` footer at its stuck position under `main`, so a full-page image of the real
page shows an empty dark band where the footer is. The inspector's own screen-by-screen captures are in
`inspect/` and show the page as it is.

## Pictures used (all from `../../assets/`, each with width, height and a real alt)

Found pictures (the world around a kitchen; captioned as what they show, never as a J'Toye kitchen's own):
- `suya-seller.jpg`: hero, left 47%, darkened. "Suya Seller" by FischerFotos, CC BY-SA 4.0.
- `panoramio-3174.jpg`: list, Peckham Jollof's row (Rye Lane Market entrance). "- panoramio (3174)" by
  L Folan, CC BY 3.0.
- `jollof-rice-with-vegetable.jpg`: list, Mama Ade's row. By Segun Famisa, CC BY-SA 4.0.
- `brixton-market-sw9-geograph-org-uk-2022036.jpg`: list, Brixton Grill's row. By Phillip Perry,
  CC BY-SA 2.0.
- `93-bruce-grove-scorpios-chicken-1413263680.jpg`: how it works, "Find a kitchen". By Alan Stanton,
  CC BY-SA 2.0.
- `jerk-chicken-as-served-july-05.jpg`: how it works, "Follow it". CC BY-SA 3.0.
- Not used: `food-store-and-caribbean-takeaway-woodville-road.jpg` (shutters down; see the idea). Its credit
  is therefore not on the page; `assets/credits.json` is shared by the four sketches and was left as is.

Real product screenshots:
- `storefront-menu.webp`: how it works "Order and pay" (4:5 crop) and showcase panel 1 (tall slice).
- `slot-picker.webp`: showcase panel 2.
- `kitchen-cook-sheet.webp`: showcase panel 3 and the cta crop ("New orders" strip).
- `ppds-label.webp`: showcase panel 4.

Logos: `logo-peckham-jollof.png`, `logo-mama-ades.png`, `logo-brixton-grill.png` at 44px in the list rows
(the real demo-shop assets; they carry their own gradient fills, a brand-asset exception, not a CSS
gradient).

No dealt picsum photographs and no SVG drawings stand in for photography. The credit line in the footer is
the `assets.mjs --credits` output cut to the six pictures used.

## Sample content

- The three kitchens' status words (Open, Paused, Closed) and localities are the deck's examples of the
  server's three states, not live data; the built page reads them from `NearYouRow`.
- Nothing else is sample: every sentence is the copy deck; no stats, quotes, ratings or customer logos.
- Routes `/privacy`, `/terms`, `/allergen-policy` are the deck's link names as paths; confirm the real
  routes. `/shop`, `/shop?q=`, `/for-operators`, `/data-request` are the deck's.
