# Sketch report — "Photographs at uneven sizes" (seed 1250599100)

Candidate 1 of 4 for the landing run 2026-11-01. One static file: `index.html` (opens from the file system; pictures by relative path `../../assets/<file>`).

## The idea sentence

> On this page, **the high street a J'Toye kitchen cooks on — Scorpio's Chicken on Bruce Grove, the Rye Lane Market entrance, Brixton Market, the Woodville Road takeaways — and the plate it sends out (jollof, jerk, suya)** become **the prints on the gallery wall, each at its own size on a rose-grey felt grid, with the stepped headline and the search set large between them.**

Filled from inventory items 11 (the food) and 12 (the high street) in `notes.md`. Competitor test: put Deliveroo or Just Eat in the sentence and it fails — their first screen is a stock burger or ramen bowl on a brand slab; neither has a Peckham or Tottenham shopfront as its material.

## The live thing

The postcode-or-dish search in the hero: a real `<form action="/shop" method="get" role="search">` with one field `name="q"` (label "Postcode or dish", placeholder "SE15 or jollof") and a submit button "Find kitchens". Under it, six crawlable plain links `/shop?q=…` (Grill, Jollof, Caribbean, South Asian, Vegan, Desserts). It works without script, from the keyboard, and with a thumb (56px field, 44px+ button). The three kitchen rows in "Kitchens on J'Toye" are also real links (`/shop?q=<name>`).

## Deviations and redraws from the sheet, with reasons

Settled in the sheet's own order (house rules → direction → type/palette → hero blueprint → section blueprints), with the project rulings in `design/rulings.tsv` and the brief's rubric R1 above all of them.

1. **Photographs in full colour, not greyscale** (palette §4 says "images are kept greyscale"). R1 says the page must read as food commerce in five seconds; greyscale jollof reads as a publication. The `gradient-decoration` ruling records that "colour comes from food photography and one brand colour". Ground, ink and the single green are kept exactly as dealt.
2. **The one green goes on the hero's "Find kitchens" button**, not on a footer button (palette §4: "green appears only on the current-page link and on one button in the footer"). The page's one action is the search (skill law: the accent appears on the action); the CTA button and footer carry no green, and the wordmark's `aria-current` link stays in ink. The dealt footer blueprint has no button anyway.
3. **Hero headline is 28 characters, not "two words of at most 9 characters each"** (hero §6). The copy deck fixes `Order from kitchens near you`. Lines are authored as "Order from" / "kitchens near you". Display size is `clamp(3.5rem, 7vw, 8rem)` rather than `clamp(72px, 8.3vw, 128px)`: at 8.3vw the indented second line ran past the right margin at 1280 and 768. Measured: one line each at 390 (43px), 768 (84px), 1280 (101px), 1920 (128px). On the phone the second line's indent is 6vw, not 22vw, for the same reason.
4. **Headline leading 1.1 (type system), not 1.0 (hero blueprint)** — the sheet says the type system wins on leading. Whole title in italic 400 as the type system sets it; no mid-line style change.
5. **Hero supporting sentence in the full ink, not the soft ink** (hero §6) — house rule "running text in the full ink". The sentence and the action do not fade up (motion §8 has them "fade up together"): the project ruling `section-entrance avoid` wants content present at load and `ambient-motion allow` caps the page at ONE deliberate moment, so only the two headline lines move (masked line reveal, CSS only, 900ms, second line +110ms, inside `prefers-reduced-motion: no-preference` and gated on `html.js`; the text is in the HTML and painted without script).
6. **Nav carries four items, not three** (hero §6): the copy deck's links need to be crawlable. Left: How it works · For kitchens; centre: wordmark; right: Find kitchens. Set at the body step (the sheet's 15px is below the body step; the small step, 13–14px, is too small for navigation).
7. **Phone hero order**: sentence and search come BEFORE the tall photograph (the sheet puts the sentence beneath the photo), because the house rule wants the first phone screen to hold headline, sentence and action — with the sheet's order the button fell below the fold (inspector `first-action` at 768 in round 0). The tall photo still follows at 72% width on the left margin.
8. **Kitchens list has three rows, not 5–14** (list §1): only three demo kitchens exist and nothing is invented. The 4:5 photograph is 22vw wide as the blueprint says (round-2 fix; at four columns it towered over three rows). Rows set at the lead step rather than 15–16px. **The three `logo-*.png` tiles are NOT used**: they are gradient-filled initials tiles, which the `gradient-decoration` ban covers and which read as generated avatars; the rows are plain names. The status words are the server's three states shown once each as examples (sample content).
9. **"What you can trust" holds three statements, not 12–30 names** (proof §3 "Plain names in columns"). Geometry kept — heading in columns 1–3, the list in columns 5–12 as three text columns, each opening with its kind in the softer ink (Hygiene / Allergens / No app) — with the three true facts instead of a roll of names. No `--redraw` was run, because that writes into `draw/`, outside the sketch folder.
10. **Showcase run**: four frames (the four real product screenshots), not 6–8; fixed height `8.6vw` instead of 30vh, because at 30vh the lifted frame plus three natural-width frames left only one and a half frames inside the viewport and pushed caption text to the screen edge (inspector `edge-text` FAIL in round 0). The run now ends inside the right margin on desktop. Captions stack name over fact (name-left / fact-right did not fit a 170px frame). The side column's "notes much further down" are dropped on desktop because every frame already carries its fact; on the phone they are listed under the strip. The phone strip scrolls sideways with snap and bleeds off the right edge as the blueprint says (inspector `side-scroll` WARN at 768/390, defended by the blueprint).
11. **CTA has a small 1:1 photograph in columns 9–11** overlapping the footer by 120px (cta §5 says "that is all"). The house rule against a section huddled on one side with the other half empty wins; the direction's own habit ("a square that overlaps the next section") supplies the device. Top space is 22svh, not 30svh, so the gap before the sentence stays under the inspector's blank-stretch threshold. CTA button is ink-filled (see 2).
12. **Footer photograph sits inside the left page margin**, not "cropped hard by the left viewport edge" (footer §6): a picture touching the left edge sets the inspector's left margin to 0 and fails the equal-margins frame rule. The surface field covers the whole footer rather than the right half only. Menu at the head step, weight 500 (the type system has no bold), 28px apart, with the dealt hover (hovered row shifts 12px, the others drop to the softer ink). Legal line and credits at the body step, not 12px: the credits are the longest running text on the page, so at 14px they became the page's "body size" (inspector `body-size` and `grey-body` in round 0).
13. **`--muted` darkened from `#6a615d` to `#5f5652`**: the dealt value was 4.4:1 on the surface tone (inspector `contrast` WARN at 768/390).
14. **Em dashes in the copy are kept verbatim** (seven, in How it works and the showcase captions); lint did not flag them.
15. **No video**: no honest clip was dealt or searched for (the sheet's floor is pictures) and the project caps motion at one moment. Inspector `no-footage` WARN, defended.

## Gate results

| Round | lint FAIL / WARN | inspect FAIL / WARN |
| --- | --- | --- |
| 0 (first build) | 0 / 0 | 4 / 6 |
| 1 | 0 / 0 | 0 / 2 |
| 2 (final) | 0 / 0 | 0 / 2 |

Round-0 FAILs: `body-size` (14px credits were the dominant running text), `late-title` + `no-title` (the h1 was `display: contents`, so it had no box), `edge-text` (showcase caption at the right edge). Round-0 WARNs: `grey-body`, `no-footage`, `contrast`, `size-count` (10 sizes at 768 from px overrides in the phone styles; now the five steps plus the display), `first-action` (768), `side-scroll`.

Final inspect WARNs: `no-footage` @1440 and `side-scroll` @768/390, both defended above. Inspector run with `--allow still-page` (the project allows one motion moment; the inspector's motion floor is eight moving elements). Inspector screenshots in `inspect/`; my own full-page screenshots in `shots/390.png`, `shots/768.png`, `shots/1280.png`, `shots/1920.png`, taken with Playwright 1.64 (file:// URL, scrolled to the bottom first, viewport heights 844 / 1024 / 720 / 1080); the temporary script under `frontend/` was deleted afterwards.

Measured with that script: both headline lines on one line each at all four widths; every h2 at two lines or fewer (inspector: "headings 9 · longest 2 lines"); no horizontal overflow at any width (`scrollWidth` = viewport); EB Garamond loaded.

## Pictures used (all in `../../assets/`, credits in `credits.json`, credit line in the footer)

| File | Where | Shown as |
| --- | --- | --- |
| `jollof-rice-with-vegetable.jpg` (Segun Famisa, CC BY-SA 4.0) | hero 4:5 portrait, columns 2–5; footer left half, close crop | a plate of jollof |
| `93-bruce-grove-scorpios-chicken-1413263680.jpg` (Alan Stanton, CC BY-SA 2.0) | hero small 16:10, columns 10–12 | Scorpio's Chicken shopfront, Tottenham |
| `panoramio-3174.jpg` (L Folan, CC BY 3.0) | Kitchens list, 4:5 at 22vw | Rye Lane Market entrance, Peckham |
| `brixton-market-sw9-geograph-org-uk-2022036.jpg` (Phillip Perry, CC BY-SA 2.0) | How it works stage 1, 3:4 | Brixton Market street |
| `jerk-chicken-as-served-july-05.jpg` (CC BY-SA 3.0) | How it works stage 2, 1:1 | jerk chicken in a tray |
| `food-store-and-caribbean-takeaway-woodville-road.jpg` (Christopher Hilton, CC BY-SA 2.0) | How it works stage 3, 16:10 | Woodville Road shopfronts, Thornton Heath |
| `suya-seller.jpg` (FischerFotos, CC BY-SA 4.0) | CTA, 1:1 columns 9–11 | a suya seller at his table |
| `storefront-menu.webp` | showcase, lifted frame | real product screenshot |
| `slot-picker.webp` | showcase | real product screenshot |
| `kitchen-cook-sheet.webp` | showcase | real product screenshot |
| `ppds-label.webp` | showcase | real product screenshot |

Every found picture's alt text says what it shows (a shopfront, a street, a plate) and none is captioned as a J'Toye kitchen's own dish or shop. The dealt picsum "Interior and table" set is not used. The three `logo-*.png` files are not used (deviation 8). Eleven real pictures on the page (seven photographs, four interface screenshots); no SVG stand-ins. Five photographs are share-alike: shown as-is (cropped by `object-fit`, not recoloured or combined).

## Sample content to confirm before anything ships

- The three kitchen rows (Peckham Jollof · Rye Lane, SE15 · Open; Mama Ade's · Peckham · Paused; Brixton Grill · Brixton Market, SW9 · Closed) are the demo tenants with the three server states shown once each; on the real page they come from `NearYouRow`.
- The row links go to `/shop?q=<kitchen name>`, not to a shop slug (slugs were not in the notes).
- Footer legal links `/privacy`, `/terms`, `/allergen-policy` are assumed routes; `/data-request` and `/for-operators` come from the copy deck.
- The quick-search terms are the copy deck's six.
- Nothing else is invented: no stats, quotes, ratings or customer logos.
