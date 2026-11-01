# DIRECTION SHEET — Photographs at uneven sizes
seed 1250599100 · drawn 2026-11-01 · sector restaurant
Brief: J'Toye: order from independent UK kitchens near you (jollof, suya, jerk; home and batch cooks) from the kitchen's own storefront, no app; or run your own food business on it

| | |
| --- | --- |
| Direction | Photographs at uneven sizes |
| Hero | Stepped title, two photographs |
| Type | EB Garamond |
| Colour | Rose grey, green |
| Sections | list (Statement, table pushed right) → how-it-works (Photographs down a diagonal) → proof (Plain names in columns) → showcase (Frame run, one lifted) → cta (A plain sentence, one action) → footer (Photograph half, menu half) |
| Motion | Masked headline |
| Photographs | the "Interior and table" set, as atmosphere |

This is the direction for this build. Adopt it. Do not blend it with the layout you would have made anyway,
and do not redraw because it is unfamiliar: unfamiliar is the point. Redraw a slot only when it conflicts
with a stated brand constraint or needs an asset that does not exist, and say why in your report.

The parts of this sheet were written separately and will disagree in small ways. Settle every disagreement in this order:
**house rules** (including type no smaller than 13px and body text of 16px or more) → **the direction** (the idea, the composition habits, where the
one accent goes) → **the type system and the palette** (which face does which job, the five size steps, which colour is the ground and where the
accent goes) → **the hero blueprint** → **the section blueprints**. Under each blueprint below, "Adjusted for this page" lists where it disagrees.
In practice: take geometry from blueprints (columns, proportions, what sits where) and take everything else from above them. Map every size a
blueprint names onto one of your five type steps instead of adding a sixth. Use one button shape, one corner radius and one pair of page margins on
the whole page, whatever individual blueprints say. If a blueprint names a kind of typeface (a light serif, a heavy grotesk), use the dealt face.

## 1. The idea
A calm page where the pictures are placed like prints on a gallery wall: each at its own size and position, with text set small and light between them. Nothing lines up in the obvious way, yet everything sits on a grid you can feel. The unevenness is what makes it read as art-directed by a person: a small portrait far left, a wide landscape running off the right edge, a square that overlaps the next section. It lets photographs lead without ever becoming a slideshow.

Before anything else, write one sentence in your notes: "On this page, <the subject's own material> becomes <the device>."
Fill the first blank from your inventory of what only this client has (its name and what it means, its street, its numbers, its
constraint), then put the nearest competitor in the sentence: if it still works, the idea is the category's, not the client's.
Then name the one live thing the visitor can do on the page (SKILL.md step 4).

## 2. Composition
A 12-column grid with wide margins (5–7vw). The hero is a single line of large light type (6–9vw) across the top third, and below it one photograph placed off-centre (columns 6–12, 16:10), its top edge overlapping the baseline zone of the headline by a few pixels of deliberate tension. Each following section places two or three photographs with different spans and vertical offsets: for example a 3:4 portrait in columns 2–5 starting 120px lower than a 16:10 landscape in columns 7–12; then a single small 1:1 in columns 9–11 beside a paragraph in columns 2–6. Paragraphs are short (max 40ch) and sit in the gaps. No two consecutive sections use the same arrangement. Vertical spacing is large and irregular on purpose (120–260px).

## 3. Type — EB Garamond
A humanist Garamond in roman and italic at two modest sizes, with no capitals and no bold. Suits set designers, antique dealers, wine, publishers and portfolios that whisper.
```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=EB+Garamond:ital,wght@0,400;0,500;1,400;1,500&display=swap" rel="stylesheet">
```
- Display: **EB Garamond** — weight 400, italic for the title · tracking -0.01em · leading 1.1 · sentence case or all lowercase; the largest size stays near 40px
- Text: **EB Garamond** — 400 for body, 500 for emphasis · leading 1.5 · tracking 0
```css
:root {
  --step-small: clamp(0.8125rem, 0.79rem + 0.1vw, 0.875rem);
  --step-body: clamp(1.125rem, 1.05rem + 0.3vw, 1.3125rem);
  --step-lead: clamp(1.25rem, 1.1rem + 0.7vw, 1.625rem);
  --step-head: clamp(2rem, 1.3rem + 3.2vw, 4rem);
}
```
A light or regular weight at large size for the few headings; small, quiet text. Serif or a slender sans. Everything left-aligned to grid lines.
The direction decides sizes and how many of them; the type system decides how this face is set. Where the two give different tracking or leading, use the values of the type system: they are tuned to this face.
The display size itself comes from the hero blueprint below.

## 4. Colour — Rose grey, green
```css
:root {
  --bg: #ebe5e3;
  --surface: #e1dad7;
  --ink: #2b2622;
  --muted: #6a615d;
  --accent: #0f7d45;
  --accent-ink: #ffffff;
}
```
A single rosy grey covers the page from top to bottom with brown-black text and grey secondary lines; images are kept greyscale. Green appears only on the current-page link and on one button in the footer.
A pale neutral ground with one deep ink taken from the photographs. No accent beyond a quiet link colour.

## 5. Imagery
**The project has no photographs of its own yet.** First choice: **real pictures of the subject's world from the open collections**: its city and street in old postcards and photographs, an old map of the neighbourhood, its materials up close, its trade in engravings, museum objects of its kind (references/imagery.md §3). Start with `node /home/sanmi/.claude/skills/taste-skill/scripts/assets.mjs "restaurant"`, then queries from your inventory (its street, its materials, its trade's tools and history). Download what you use into the project with `--get` and credit every one on the page (`--credits` prints the footer line). They show the world around the client, never the client's own room, dish, product, person or team. Second, for what no honest picture exists of: draw the subject's own things in code, large, in one flat graphic language and the palette's real colours (its tiles, its windows, its objects; references/imagery.md §8). Third: the dealt photographs, only where an honestly generic picture fits (the sea, the weather, a material), never one a visitor could take for the thing itself and never an unrelated scene. One treatment for all of them. If this direction is built on photographs, plan the set before building (four to six pictures of one family) and reuse them at different crops and scales instead of finding or drawing a new picture for every slot; keep drawings as `<symbol>` + `<use>` or generate them with a small script so the page stays light. List every found picture (with its source and licence), every dealt photograph and every drawing that stands in for photography in your report.

Six to nine photographs from one set, in a deliberate mix of ratios (3:4, 4:5, 1:1, 16:10, 21:9). Square corners. Captions, if any, are one short line under an image in the soft ink.

**Floor (the inspector checks it):** at least four real pictures on the page, usually six to twelve; SVG drawings do not count. One moving picture where an honest clip exists: `node <skill>/scripts/assets.mjs --video "<query>"`, used as a muted loop with a poster frame. Whatever this direction says about pictures, it is carried out with real ones.

Photographs dealt for this build, from the "Interior and table" set (rooms, windows, chairs, cups, food and tabletops)
Order of preference for imagery: (1) the project's own photographs and product shots; (2) images you generate, if an image tool is
available; (3) real pictures of the subject's world found in the open collections (assets.mjs, downloaded and credited on the page);
(4) drawings of the subject in code; (5) these. They are real photographs (Unsplash licence, served by picsum.photos) of places, light and
texture. They are atmosphere, never a stand-in for a specific product, dish, person or interface. List the ones you use in your report so
they can be replaced.
- `https://picsum.photos/id/490/1600/1000` — Dark overhead arrangement of a cutting board, pan and kitchen tools · dark tone · dark brown, black, tan · native landscape · photo: Todd Quackenbush
- `https://picsum.photos/id/1068/1600/1000` — Small plant in a vase on a white table against a blue wall · mid tone · bright blue, white, green · calm area: top · native landscape · photo: Padurariu Alexandru
- `https://picsum.photos/id/326/1600/1000` — Hand holding a white bowl with yellow slices on a pale surface · light tone · cream, pale yellow, warm beige · calm area: left · native landscape · photo: Dominik Martin
- `https://picsum.photos/id/625/1600/1000` — Wooden dining table with white moulded chairs beside large windows · light tone · white, warm wood, cream · calm area: left · native landscape · photo: Breather
- `https://picsum.photos/id/999/1600/1000` — Bowls, nuts and a pastry arranged on a dark wooden table from above · dark tone · charcoal, dark brown, cream · native landscape · photo: Annie Spratt
- `https://picsum.photos/id/889/1600/1000` — Close-up of frosted frozen berries in purple and red · mid tone · frosted purple, berry red, pale lilac · calm area: top · native landscape · photo: Devin Rajaram
- `https://picsum.photos/id/192/1600/1000` — Silhouetted people at cafe tables before large bright windows · mid tone · white, black, grey · black-and-white · calm area: top · native square · photo: Adam Przewoski
- `https://picsum.photos/id/312/1600/1000` — Honey dipper dripping into a white ceramic bowl of dark honey · light tone · white, amber brown · calm area: left · native landscape · photo: Sonja Langford
The last two path numbers are width and height: request the exact crop you render (`/1200/1500` for a 4:5 portrait, `/2400/1350` for a hero) and
add `?grayscale` to desaturate. Use three to six of them, not all eight. Always set `width`, `height`, `alt` and `loading="lazy"` (not on the hero image).
Other sets that fit this brief: coast, field-and-farm, city-night, hands-and-making — `node draw.mjs --photos <set>` prints one in full; `--photos list` shows all sets. Taking photographs from another set is your call and is not a redraw; keep them to one light and one treatment.

## 6. Hero — Stepped title, two photographs
A pale neutral ground with 6vw side margins on a 12-column grid. Navigation is three small items on the top edge at 15px: one link hard left, the wordmark centred, the single action hard right. The headline is two words or short phrases on two lines in a thin serif at clamp(72px, 8.3vw, 128px), leading 1.0, stepping diagonally across the middle third of the screen: the first line starts at column 2 with its baseline at 44% of the height; the second is indented to start at column 6 and ends near column 12. Two photographs are placed against the step, both with square corners. A small 16:10 landscape in columns 10-12 hangs from 10% of the height and stops about 20px above the first line. A 4:5 photograph in columns 2-5 starts 24px under the first line's baseline, to the left of the indented second line, and is cropped by the bottom edge of the viewport. Under the second line, starting on column 6, one sentence at 20px in the soft ink, max 36ch. Columns 6-12 of the lower third stay empty.

On a phone: The step survives: the first line at 15vw flush to the 20px left margin, the second line indented by 22vw. The small landscape sits above the title at 46% of the width, pushed to the right margin. The tall photograph follows the title at 72% of the width on the left margin, cropped by the bottom of the screen; the sentence at 16px moves beneath it.
Headline: Two words or short phrases, one per line, at most 9 characters each so the indented second line fits a phone.

Adjusted for this page:
- It names a kind of typeface: use the dealt faces (display EB Garamond, text EB Garamond) and their settings; capitals only where the type system sets them.

## 7. Sections, in order
Each is a composition to rebuild with this project's content. Keep the geometry; change the subject. Re-order or drop a
section when the argument of the page needs it (see references/copy.md), never to fall back to a card grid.

### 1. list — Statement, table pushed right (table)
A heading of four to six words spans the full width on two lines at clamp(40px, 4.5vw, 68px), left-aligned with its first line indented 3vw and leading 0.98. 96px below, the table is pushed into the right-hand five columns (8–12) and the left stays open. One plain word naming the table (offices, hours, rates) hangs at 14px in column 7, top-aligned with the first row. Each row is a three-part line at 15–16px with 26px between rows: the kind or day in the soft ink at the left of the measure, the place or service at 30%, and the address, time or price right-aligned, with a small arrow where the row links. Rows are separated by space only; every fourth row has 44px above it to form groups. In columns 1–4, bottom-aligned with the last row, one 4:5 photograph 22vw wide may sit alone. Holds 5–14 rows.
On a phone: Heading at 9.5vw without the indent. The table takes the full width at 16px; each row stacks the kind above the place, with the value right-aligned on the first line. The naming word is dropped because the heading already says it. The photograph follows at 60% width on the right margin.

### 2. how-it-works — Photographs down a diagonal (stack)
Three stages, three photographs of different sizes placed along a falling diagonal on the 12-column grid. Stage one: a 3:4 portrait in columns 1–4. Stage two: a 1:1 square in columns 6–8, its top edge 220px lower than the first photograph's top. Stage three: a 16:10 landscape in columns 8–12, starting 180px below the bottom of the second. Each stage's text sits in the gap the stagger leaves: a paragraph of up to 36ch at 17px, top-aligned with its photograph and placed in the columns to its right (to its left for the third). Each paragraph opens with a bold run-in phrase that carries the order: 'On the first day', 'A week later', 'By the end'. Square corners; no arrows or connecting marks. The heading, two to four words at clamp(36px, 4vw, 64px) in a light weight, sits alone in columns 7–12 at the very top, level with the first photograph's top edge.
On a phone: The diagonal becomes alternating widths: the first photograph at 100%, the second at 62% flush right, the third at 78% flush left. Each paragraph follows its photograph on that photograph's left edge at 16px. 56px between stages. The heading sits above at 30px.

### 3. proof — Plain names in columns (list)
A typographic list with no logos. The heading, two or three words at 28–32px, sits in columns 1–3 and stays top-aligned with the first row of names. Columns 5–12 hold the names in three or four text columns (`columns: 4; column-gap: 32px`), each name on its own line at 20–22px with leading 1.5, sentence case, in the full ink. Where names fall into kinds (clients, press, partners), each kind starts a new column and its first line names the kind in the softer ink at the same size, so no small labels are needed. Columns are of unequal length and are not balanced; ragged bottoms are intended. Twelve to thirty names work best. There are no boxes, bullets or separators: the column edges and the shared baseline do the organising. Leave 120px above and below.
On a phone: The heading leads at 26px. Names follow in two columns at 17px with a 20px gap; each kind begins after a 32px break with its name in the softer ink on the first line. Long names wrap inside their column rather than shrinking.

### 4. showcase — Frame run, one lifted (strip)
Columns 1–3 are a nearly empty column: the heading (two to four words at 24–28px) at the top, a link under it, and one or two short notes at 15px much further down, each level with the frame it describes. Columns 4–12 hold a run of six to eight frames of the same subject at one fixed height of 30vh, with natural widths and 6px gaps: the whole view, a tight crop of the same thing, a detail, the reverse side. The run is allowed to leave the right edge. One frame is lifted out of the row: shown at 2.2 times the row height with its bottom aligned to the row's bottom (flex row, align-items: end), so it stands above the others. Under each frame, at 15px: a name on the left and a single fact on the right. Square corners, no captions on the images.
On a phone: The heading and link sit on top. The lifted frame comes first at full width. The rest of the run scrolls sideways beneath it at a fixed height of 34vw with 4px gaps and scroll-snap, bleeding off the right edge. Notes are listed under the run at 15px.

### 5. cta — A plain sentence, one action (stack)
The page ends on an almost empty screen. On the page ground, with no band and no panel, one sentence of eight to fourteen words is set at 18–22px in the regular weight, in the full ink, no wider than 34ch, on the same left edge as the page's text column (or centred only if the whole page is centred). 24px beneath it sits one small filled button, about 40px tall with 18px side padding, and optionally one text link beside it on the same baseline. That is all. Above the sentence leave at least 30svh of empty ground and below it at least 20svh before the footer line, so the emptiness reads as decided. The sentence is written as something a person would say, and it names the action plainly. No headline-sized type appears anywhere in this section.
On a phone: Identical in spirit: the sentence at 18px with 20px margins, the button beneath at its natural width (not full width), 44px tall. Keep 20svh of empty ground above the sentence and 14svh below the button, before the footer line.

### 6. footer — Photograph half, menu half (split)
A split footer one viewport tall. The left half is a single photograph filling columns 1–6 edge to edge, cropped hard by the left and bottom viewport edges: a close crop of the page's main subject, larger than it appeared anywhere above. The right half, on a flat field, is the site's menu set large: five or six section names stacked as links at about 2.6vw bold, 28px apart, starting at column 8 and 80px from the top. Rows are separated by space only; on hover a row's label shifts right by 12px and the others drop to the softer ink. Under the menu, 64px lower, two short blocks at 15px side by side: contact and social. The legal line runs along the very bottom of the right half at 12px. The wordmark sits small at the top of the right half, on the menu's left edge.
On a phone: The photograph leads at full width and 50svh, cropped tight. The menu follows on the flat field at 8vw per item with 20px between items, then contact and social stacked at 15px, then the legal line at 12px. The wordmark sits small above the menu.

Adjusted for this page:
- It sets text at 12px: use your small step (13px or more) instead.

## 8. Motion — Masked headline
On load, each line of the headline rises from behind its own baseline, about 900ms with a strong ease-out, the second line 110ms after the first; the supporting sentence and the action then fade up together. Nothing else on the page performs. Because headlines are two lines at most, you author the line breaks and wrap each line yourself. Recipe: 'Masked line reveal' in references/motion.md.
This is the signature move, the one loud moment. Around it the page still needs the rest of the motion layer (SKILL.md step 9): an entrance for the parts of every section, feedback on every control, and one moving picture. The inspector fails a page that barely moves. Recipes: references/motion.md.

## 10. Where the richness comes from
Placement. The differing sizes and offsets create movement on a still page.

## 11. How this direction goes wrong
Uneven is not random: every image edge lands on a column line, and offsets come from a small set of values. On a phone, keep the unevenness with alternating widths (100%, 72% left, 60% right) rather than stacking everything full width.

## 12. Real references
Studied sites that work this way. Take the principle, never the specifics: no names, copy, images or signature assets of theirs.
- **Sobha Privy Collection** (Awwwards Site of the Day, studied score 7.1) `aww-sobha-privy-collection`
  Idea: Luxury homes sold by restraint: a black sculptural render and a script-over-capitals title, then white pages where interior images grow and overlap huge serif capitals as you scroll.
  Hero: Full-bleed near-black video of a glossy black sculptural form, wide curved bands catching thin highlights, filling the frame at very low contrast. A centred two-line title about 46% of the width: a formal script phrase at about 6.9vw, then a line of light roman serif capitals at about 6vw directly beneath, wider than the script. Nothing else in the middle. Corners only: a two-line hamburger with a menu label top-left, a small serif wordmark with a micro subtitle top-centre, a text link top-right, a circled down-arrow bottom-left, and bottom-right a greyscale 3D-map thumbnail about 17% wide with a numeral and a two-line label.
  Worth taking: Pair one short phrase in formal script with a line of light serif capitals beneath it, letting the script overlap the capitals or run into an adjacent image.
- **Tengile Malamala Collection** (Awwwards Site of the Day, studied score 7.6) `aww-tengile-malamala-collection`
  Idea: Let the land and animals carry everything: one staggered hairline-serif headline over aerial footage, then cream pages of olive-green serif text with photographs placed at uneven sizes.
  Hero: Aerial river footage fills the viewport, slightly darkened. Three small white caps items sit on the top edge: an enquiry link hard left, a stacked three-line wordmark centred, 'menu' hard right. The headline is two words on two lines in thin serif capitals about 8.3vw tall: the first line starts 10% from the left, the second is indented to 40% and ends near 90%, so the pair steps diagonally across the middle third. Below, starting at the 50% line, a three-line serif sentence at about 20px and a small white square-cornered button with a separate arrow square beside it. The lower third is left to the water.
  Worth taking: Stagger a two-line headline: first line from the left margin, second indented by 30% so the pair steps diagonally across the image.
- **Le Petit Bleu** (One Page Love, studied score 7.8) `opl-le-petit-bleu`
  Idea: The cafe's teal-and-white stripes become the whole system: one teal ink on cream, everything boxed in thin double outlines like printed menu cards, beside a permanently visible photo of the room.
  Hero: Viewport split 50/50 with 12px gaps. Left: a cream panel with a thin pale-teal outline and rounded corners. Inside, a second outlined box is the nav bar: two-line hamburger left, teal brush-script logo about 300px wide centred, a caps order link right. Below, a two-line light serif headline at about 3.5vw, centred, in teal, then two centred stacks, each a bold caps label, a serif line and a solid teal button wrapped in a second thin outline. Right: a full-height rounded photo of a striped booth, with an outlined hours bar across its top, three slide dots bottom-left and a white address card bottom-right.
  Worth taking: Print everything in one brand ink on cream, including body text, rules and buttons, and let photographs supply the other colours.
Full notes on any of them: `node draw.mjs --site <id>`.

## Always
Headlines two lines or fewer at every width. No eyebrow labels, no monospace, no 01/02/03, no thin rules, no gradients, no shadows at rest,
no cream-and-terracotta, no violet. The frame: one h1 on the first screen that says what this is, equal outer margins, no
section huddled on one side with the other half empty, no blank stretch over half a screen, a heading on every section.
`inspect.mjs` measures all of it.

Saved to /home/sanmi/IdeaProjects/JToye_OaaS_2026-design-run/design/runs/2026-11-01-landing/draw/uneven-photographs/direction.json · reprint with `node draw.mjs --show`
