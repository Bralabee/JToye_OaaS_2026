# DIRECTION SHEET — The page is the object
seed 1794208771 · drawn 2026-11-01 · sector restaurant
Brief: J'Toye: order from independent UK kitchens near you (jollof, suya, jerk; home and batch cooks) from the kitchen's own storefront, no app; or run your own food business on it

| | |
| --- | --- |
| Direction | The page is the object |
| Hero | Invitation card, cropped |
| Type | Barlow Condensed / Barlow |
| Colour | Burgundy and bubblegum |
| Sections | list (The printed menu sheet) → how-it-works (Itinerary on a slip) → proof (The printed guest list) → showcase (Square plate, spare text) → cta (The reply card) → footer (The back of the card) |
| Motion | Curtain footer |
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
Find the printed or physical artefact that already exists in the subject's world and build the page as that thing. A wedding site is the invitation card. A restaurant is its menu. A conference is the ticket; a record is its sleeve; a shop is the receipt or the swing tag; an archive is a stack of tabbed folders; a course is the syllabus sheet. The artefact supplies everything a template would otherwise invent: its proportions, its typographic manners, its way of grouping information. Visitors recognise the object instantly, which is why these pages feel specific.

Before anything else, write one sentence in your notes: "On this page, <the subject's own material> becomes <the device>."
Fill the first blank from your inventory of what only this client has (its name and what it means, its street, its numbers, its
constraint), then put the nearest competitor in the sentence: if it still works, the idea is the category's, not the client's.
Then name the one live thing the visitor can do on the page (SKILL.md step 4).

## 2. Composition
The first screen shows the artefact as a single filled panel (paper colour on a contrasting ground) centred or offset, with believable proportions: a card at 5:7, a ticket at about 2.2:1 with a notch cut by `clip-path`, a menu as a tall sheet. All primary content lives on it, set the way that object is set: centred and symmetrical for an invitation, in tidy aligned columns for a menu or ticket. The ground behind is a flat colour or one full-bleed photograph. As the page continues, each section is another piece of the same stationery: a second card, a tear-off stub, a folded panel, always filled shapes with real edges (straight, notched, scalloped, perforated by a dotted `border` of round dots at 4px or more), never outlined boxes. The footer is the back of the object.

## 3. Type — Barlow Condensed / Barlow
Road-sign condensed type with squared curves, bold but not compressed into a wall, with its normal-width sibling for text. Suits logistics, motorsport, hardware and directories with many short titles.
```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Barlow+Condensed:wght@700&family=Barlow:wght@400;500&display=swap" rel="stylesheet">
```
- Display: **Barlow Condensed** — weight 700 · tracking -0.02em · leading 0.9 · uppercase for statements, mixed case for item titles
- Text: **Barlow** — 400 for body, 500 for emphasis · leading 1.5 · tracking 0
```css
:root {
  --step-small: clamp(0.8125rem, 0.79rem + 0.1vw, 0.875rem);
  --step-body: clamp(1.0625rem, 1rem + 0.25vw, 1.1875rem);
  --step-lead: clamp(1.25rem, 1.1rem + 0.7vw, 1.625rem);
  --step-head: clamp(2.5rem, 1.2rem + 5vw, 5.5rem);
}
```
Take the manners of the artefact: a bookish serif with small caps avoided, or a blunt condensed face for tickets and posters. Sizes stay within what print would use relative to the panel; the name is large on the panel, details are small and exact.
The direction decides sizes and how many of them; the type system decides how this face is set. Where the two give different tracking or leading, use the values of the type system: they are tuned to this face.
The display size itself comes from the hero blueprint below.

## 4. Colour — Burgundy and bubblegum
```css
:root {
  --bg: #3d0012;
  --surface: #4d0519;
  --ink: #ff99d3;
  --muted: #c77aa0;
  --accent: #ff99d3;
  --accent-ink: #3d0012;
}
```
Deep burgundy is the ground for the whole page and bubblegum pink sets every word on it. The button and the active state of a control are solid pink with burgundy type; product photographs supply any other colour.
Paper and ink plus the ground the paper sits on. Two or three flat colours in total, like a two-colour print job.

## 5. Imagery
**The project has no photographs of its own yet.** First choice: **real pictures of the subject's world from the open collections**: its city and street in old postcards and photographs, an old map of the neighbourhood, its materials up close, its trade in engravings, museum objects of its kind (references/imagery.md §3). Start with `node /home/sanmi/.claude/skills/taste-skill/scripts/assets.mjs "restaurant"`, then queries from your inventory (its street, its materials, its trade's tools and history). Download what you use into the project with `--get` and credit every one on the page (`--credits` prints the footer line). They show the world around the client, never the client's own room, dish, product, person or team. Second, for what no honest picture exists of: draw the subject's own things in code, large, in one flat graphic language and the palette's real colours (its tiles, its windows, its objects; references/imagery.md §8). Third: the dealt photographs, only where an honestly generic picture fits (the sea, the weather, a material), never one a visitor could take for the thing itself and never an unrelated scene. One treatment for all of them. If this direction is built on photographs, plan the set before building (four to six pictures of one family) and reuse them at different crops and scales instead of finding or drawing a new picture for every slot; keep drawings as `<symbol>` + `<use>` or generate them with a small script so the page stays light. List every found picture (with its source and licence), every dealt photograph and every drawing that stands in for photography in your report.

Optional. One photograph behind the object, or small photographs placed on it like tipped-in plates. The object itself is the image.

**Floor (the inspector checks it):** at least four real pictures on the page, usually six to twelve; SVG drawings do not count. One moving picture where an honest clip exists: `node <skill>/scripts/assets.mjs --video "<query>"`, used as a muted loop with a poster frame. Whatever this direction says about pictures, it is carried out with real ones.

Photographs dealt for this build, from the "Interior and table" set (rooms, windows, chairs, cups, food and tabletops)
Order of preference for imagery: (1) the project's own photographs and product shots; (2) images you generate, if an image tool is
available; (3) real pictures of the subject's world found in the open collections (assets.mjs, downloaded and credited on the page);
(4) drawings of the subject in code; (5) these. They are real photographs (Unsplash licence, served by picsum.photos) of places, light and
texture. They are atmosphere, never a stand-in for a specific product, dish, person or interface. List the ones you use in your report so
they can be replaced.
- `https://picsum.photos/id/490/1600/1000` — Dark overhead arrangement of a cutting board, pan and kitchen tools · dark tone · dark brown, black, tan · native landscape · photo: Todd Quackenbush
- `https://picsum.photos/id/326/1600/1000` — Hand holding a white bowl with yellow slices on a pale surface · light tone · cream, pale yellow, warm beige · calm area: left · native landscape · photo: Dominik Martin
- `https://picsum.photos/id/1068/1600/1000` — Small plant in a vase on a white table against a blue wall · mid tone · bright blue, white, green · calm area: top · native landscape · photo: Padurariu Alexandru
- `https://picsum.photos/id/625/1600/1000` — Wooden dining table with white moulded chairs beside large windows · light tone · white, warm wood, cream · calm area: left · native landscape · photo: Breather
- `https://picsum.photos/id/889/1600/1000` — Close-up of frosted frozen berries in purple and red · mid tone · frosted purple, berry red, pale lilac · calm area: top · native landscape · photo: Devin Rajaram
- `https://picsum.photos/id/429/1600/1000` — White cup filled with fresh raspberries on a pale wooden table · light tone · raspberry red, white, pale wood · calm area: left · native landscape · photo: Glen Carrie
- `https://picsum.photos/id/102/1600/1000` — Raspberries on a white ledge with warm golden blurred background · light tone · golden yellow, raspberry red, white · calm area: top · native landscape · photo: Ben Moore
- `https://picsum.photos/id/208/1600/1000` — Multi-pane window in a dark room looking onto green trees · dark tone · black, leaf green · calm area: left · native landscape · photo: Martin Wessely
The last two path numbers are width and height: request the exact crop you render (`/1200/1500` for a 4:5 portrait, `/2400/1350` for a hero) and
add `?grayscale` to desaturate. Use three to six of them, not all eight. Always set `width`, `height`, `alt` and `loading="lazy"` (not on the hero image).
Other sets that fit this brief: coast, field-and-farm, city-night, hands-and-making — `node draw.mjs --photos <set>` prints one in full; `--photos list` shows all sets. Taking photographs from another set is your call and is not a redraw; keep them to one light and one treatment.

## 6. Hero — Invitation card, cropped
A flat field of one soft colour fills the viewport. Centred on it is a single portrait card in paper colour, 5:7, on columns 4–9, its top edge 190px down and its lower third cut off by the bottom of the viewport, so the page visibly continues as the rest of the card. The card is a filled shape with scalloped inner corners, made with `clip-path` or with four corner circles in the field colour, and no outline. Inside, with 56px padding, everything is centred and symmetrical like letterpress stationery. The two names, or the title, are set in a large italic serif at clamp(56px, 5.6vw, 88px) on two lines, staggered so line one sits left of centre and line two right. Between and beneath them, date, time and place appear as short lines at 15px in sentence case, separated by space and by one solid 10px diamond. The top row sits on the field above the card at 28px margins: a small monogram top-left, three text links centred, and the single action, a solid rectangular button in the ink colour, top-right.

On a phone: The card becomes full width minus 12px margins, starts 96px down and is still cropped by the bottom edge, with its scalloped top corners kept. The names stay on two lines at 12vw, centred without the stagger, and the details follow at 15px. The monogram sits top-left and the action button top-right on the field; the three links move onto the card's lower part, below the fold.
Headline: The two names or the title on two lines, up to about 12 characters per line, set as they would be printed on the card.

Adjusted for this page:
- It sets text at 10px: use your small step (13px or more) instead.
- It names a kind of typeface: use the dealt faces (display Barlow Condensed, text Barlow) and their settings; capitals only where the type system sets them.
- Its vw sizes assume a face of normal width: with this condensed display face, size the words to fill the measure the blueprint gives them instead of copying the vw value.

## 7. Sections, in order
Each is a composition to rebuild with this project's content. Keep the geometry; change the subject. Re-order or drop a
section when the argument of the page needs it (see references/copy.md), never to fall back to a card grid.

### 1. list — The printed menu sheet (band)
The list is one tall sheet of paper. A filled paper-colour panel 640px wide sits centred, or offset to columns 7–12, on a contrasting flat ground or a full-bleed photograph, with square corners and its top and bottom edges cut into shallow scallops by `clip-path` or an SVG `mask`. Inside, 64px padding and a centred, symmetrical setting like a printed card: the venue or section name at 40–48px, a one-line note at 15px, then three or four groups 56px apart. Each group opens with its name at 22px, centred. Items follow at 17px with 16px between them: the dish or service at the left of a 420px measure, the price at the right with tabular figures, and a one-line description at 14px in the soft ink under the name. Alignment alone links name and price; there are no leaders. The sheet ends with the opening hours as two centred lines. Holds 12–24 items.
On a phone: The sheet spans the screen with 10px of ground showing at each side and 28px padding; the scalloped edges stay at top and bottom. The measure is the full inner width and prices stay right-aligned. Group names at 20px, items at 16px.

### 2. how-it-works — Itinerary on a slip (table)
The process is printed as an itinerary or receipt. One paper-coloured slip 420–480px wide stands on a contrasting flat ground at columns 7–11; its bottom edge is a zigzag cut with clip-path and its top edge is straight. The heading, two to four words at clamp(40px, 4.4vw, 68px), sits in columns 1–5 with a 30ch paragraph, top-aligned with the slip. On the slip, in the text face at 16px with tabular figures: stages grouped under time headings that carry the order ('Monday', 'Midweek', 'Friday'), each heading in bold with 28px above it. Under a heading, two to four rows; each row has what happens on the left, a duration or time on the right, and a leader of round 4px dots between. The final row is inverted, paper-coloured text on a full-width ink bar 44px tall, and states the result: what the reader holds at the end and the total time.
On a phone: The heading and paragraph come first. The slip follows at full width minus 16px margins, keeping its zigzag bottom edge. Rows keep left text, dots and right value on one line; long descriptions wrap under the left text. The inverted final row stays full width.

Adjusted for this page:
- Its vw sizes assume a face of normal width: with this condensed display face, size the words to fill the measure the blueprint gives them instead of copying the vw value.

### 3. proof — The printed guest list (stack)
Names are printed on a card, the way a programme lists its patrons or an invitation its hosts. On a contrasting flat ground, a paper-coloured panel about 56% of the viewport wide with 5:7 proportions sits centred and rotated by one or two degrees; the section is shorter than the card and clips it with `overflow: hidden`, so the card is cut by the bottom edge rather than framed whole. Inside, 56px padding. At the top, a centred heading of two to four words at 28px. Below it the names run as centred lines at 20–22px with leading 1.7, several names to a line, separated by middots with a non-breaking space either side. One photograph may be tipped in between the heading and the names: 16:9, the full inner width, square corners. The card is a plain filled shape with no outline.
On a phone: The card becomes 92% of the screen wide, keeps a one-degree tilt and is still cut off by the section's bottom edge. Heading at 22px, names at 17px with two names to a line. Padding drops to 28px, and the tipped-in photograph keeps the full inner width.

### 4. showcase — Square plate, spare text (split)
One flat field split 50/50, at least 88svh tall. Left half: a single square photograph filling the half's width minus 40px margins, vertically centred, square corners. A large simple shape may be laid over it to turn the photograph into the object it stands for: a ring 86% of its width with a 6px stroke and a small solid dot at the centre makes a record; a notch cut by clip-path makes a ticket stub. Right half, a flex column with space-between: at the top a row of three plain text tabs at 18px (editions, years, variants), the active one in full ink and the others at 40%; in the middle the title, two to four words at 32–40px bold, with a 34ch description 48px below; along the bottom two filled rectangular cells side by side, each half the column wide and 72px tall, acting as the two actions, one in ink and one a tone off the field.
On a phone: The square photograph comes first at full width with 16px margins. The tabs sit under it as one row at 17px. Then the title at 28px, the description, and the two action cells stacked, each full width and 60px tall.

Adjusted for this page:
- It fades text: anything a visitor reads, inactive items of a list included, is `--ink` or `--muted` at full strength (contrast 4.5:1 or more). The active item is told apart by `--ink` against `--muted`, weight or the accent, not by fading the rest.

### 5. cta — The reply card (split)
The invitation to act is a printed card lying on the page. On a dark or strongly coloured flat field about 80svh tall, a paper-coloured panel 44vw wide with 7:5 proportions sits in columns 6–12, rotated two degrees, with its lower edge hanging 60px past the section's bottom so the section crops it. On the card, with 48px padding, everything is centred the way stationery is: a heading of two to four words at 32px, one or two lines at 17px, then the action as a solid bar across the card's full inner width, 56px tall, in the ink colour with paper-coloured text. Columns 1–4 hold only a short paragraph at 20–24px in the field's light ink, bottom-aligned 64px above the edge. The card is a plain filled rectangle with square corners and no outline; its rotation and crop make it an object.
On a phone: The paragraph comes first at 20px. The card follows at 90% width, tilted one degree, centred, its bottom edge still cropped by the section. Heading at 24px, lines at 16px, and the action bar 52px tall across the full inner width of the card.

Adjusted for this page:
- Its vw sizes assume a face of normal width: with this condensed display face, size the words to fill the measure the blueprint gives them instead of copying the vw value.

### 6. footer — The back of the card (stack)
The footer is the reverse side of the page's artefact, laid out as the back of a postcard. A paper-coloured panel spans columns 2–11 on the contrasting ground, about 420px tall, its top edge cut into a row of twelve semicircular scallops (an inline SVG path or `clip-path`, each scallop about 100px wide). The panel is divided by space into two halves. Left half: a short sign-off of two to four words at 32px, then two or three lines at 16px, written like a message. Right half: the address block, four left-aligned lines at 18px with 12px between them, each line a link (email, telephone, street, social); above it in the top-right corner, a stamp: one small photograph, 3:4 and 88px wide, with a 6px paper-coloured margin. Along the panel's bottom edge, a single centred line at 12px gives the copyright like a printer's imprint.
On a phone: The panel runs full width with six scallops across its top edge. The stamp sits top-right at 64px wide; the sign-off and message come first, then the address lines at 17px beneath them, then the imprint line centred at 12px.

Adjusted for this page:
- It sets text at 12px: use your small step (13px or more) instead.

## 8. Motion — Curtain footer
The page content slides up like a sheet to uncover the footer waiting beneath it, so the last screen is revealed rather than scrolled to. Build with a sticky footer at the bottom and a main element with its own background and a higher stacking order. No JavaScript.
This is the signature move, the one loud moment. Around it the page still needs the rest of the motion layer (SKILL.md step 9): an entrance for the parts of every section, feedback on every control, and one moving picture. The inspector fails a page that barely moves. Recipes: references/motion.md.

## 10. Where the richness comes from
Recognition. The shaped edges, the print-like alignment and the limited inks make a familiar object out of plain HTML.

## 11. How this direction goes wrong
Choose an artefact that truly belongs to the subject, or it becomes costume. Shape the edges with fills and `clip-path`; an outlined rectangle with a thin border is not an object. Keep text sizes readable on a phone, where the object becomes full width with its shaped edge kept at top and bottom.

## 12. Real references
Studied sites that work this way. Take the principle, never the specifics: no names, copy, images or signature assets of theirs.
- **WeekSync** (One Page Love, studied score 7.1) `opl-weeksync`
  Idea: The page is a paper coupon: a dotted cut line with a scissors icon frames the viewport, and on scroll the navigation rail is cut free while the rest of the sheet tilts and falls away.
  Hero: Taupe-grey field wrapped by a black dotted rounded-rectangle border inset 16px from every edge. A left rail about 26% wide holds, centred on its own axis, a small black logo glyph at the top, a two-line mono tagline and a text link in braces; four nav labels, each under a small empty circle, stack at the bottom of the rail. A scissors icon sits on the bottom dotted line where rail meets main area. The main area carries one four-line statement in medium grotesk at 3vw, flush left and flush top, ending in the first quarter of the height. The remaining three quarters are empty.
  Worth taking: Give the page a physical metaphor that also does layout work: a dotted cut line frames the viewport, then separates the nav rail on scroll.
- **Mosby’s Files** (Awwwards Site of the Day, studied score 8) `aww-mosbys-files`
  Idea: An archive of architects presented as a physical stack of coloured index-tab file folders that you flip through.
  Hero: Charcoal background. A slim top bar over a hairline holds the condensed wordmark dead centre and one mono link far right. The headline is one centred line of heavy condensed uppercase at 10vw, about 71% of the width, in the upper quarter. Beneath it, a three-line centred serif paragraph in mid-grey, about 42% wide. From 51% down sits a stack of file folders 86% of the viewport wide: each is a flat colour band with one to three trapezoid tabs rising from its top-left edge, each tab a different colour holding a serif name. Mono category labels sit right-aligned. The front red folder is open, running off the bottom.
  Worth taking: Build navigation as stacked full-width colour bands with trapezoid tabs, each tab coloured to clash with the folder behind it.
- **Madie** (One Page Love, studied score 7.2) `opl-madie`
  Idea: A burgundy-and-cream bakery brand in which the madeleines themselves, cut out like specimens, become the menu.
  Hero: A dark, warm photograph (iced coffee with a logo sticker and a glazed madeleine on a wooden counter, burgundy tiles behind) fills a rounded-corner frame inset 20px from every edge on a cream page; the subject sits right of centre. In the left column, starting 42% down: a small star-rating line, then a three-line uppercase headline at 5vw in a heavy geometric sans, the first two lines outline-only and the third solid cream, a three-line paragraph, and a row with a pink pill button and an underlined text link. Top-left: menu icon and script logo. Top-right: a three-language pill toggle. Bottom-right: a circular text badge.
  Worth taking: Set a headline with its first lines as outline strokes and the last line solid, so emphasis falls on the final phrase without a size change.
Full notes on any of them: `node draw.mjs --site <id>`.

## Always
Headlines two lines or fewer at every width. No eyebrow labels, no monospace, no 01/02/03, no thin rules, no gradients, no shadows at rest,
no cream-and-terracotta, no violet. The frame: one h1 on the first screen that says what this is, equal outer margins, no
section huddled on one side with the other half empty, no blank stretch over half a screen, a heading on every section.
`inspect.mjs` measures all of it.

Saved to /home/sanmi/IdeaProjects/JToye_OaaS_2026-design-run/design/runs/2026-11-01-landing/draw/page-is-the-object/direction.json · reprint with `node draw.mjs --show`
