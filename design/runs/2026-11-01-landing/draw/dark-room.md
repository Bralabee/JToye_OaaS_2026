# DIRECTION SHEET — The dark room
seed 998514087 · drawn 2026-11-01 · sector restaurant
Brief: J'Toye: order from independent UK kitchens near you (jollof, suya, jerk; home and batch cooks) from the kitchen's own storefront, no app; or run your own food business on it

| | |
| --- | --- |
| Direction | The dark room |
| Hero | Inset photograph, crossing wordmark |
| Type | Zodiak / Hanken Grotesk |
| Colour | Charcoal and sage |
| Sections | list (Sticky photograph, active list) → how-it-works (Drag row of moments) → proof (Names as one sentence) → showcase (Band of unequal panels) → cta (First step, already open) → footer (Columns above, wordmark below) |
| Motion | Scaling wordmark |
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
One dark field from top to bottom, and in it, lit things. Photographs and objects appear like exhibits in a dim gallery: each with space around it, each the brightest thing on its screen. Type is light, small and sparse. The darkness is not a theme switch; it is a room, and the page is a slow walk through it. It suits subjects sold on atmosphere and material: an evening restaurant, a metal card, a record, a house at dusk.

Before anything else, write one sentence in your notes: "On this page, <the subject's own material> becomes <the device>."
Fill the first blank from your inventory of what only this client has (its name and what it means, its street, its numbers, its
constraint), then put the nearest competitor in the sentence: if it still works, the idea is the category's, not the client's.
Then name the one live thing the visitor can do on the page (SKILL.md step 4).

## 2. Composition
A single near-black or deep-toned ground runs the full page with no section bands. First screen: either the name at full width in a light weight along the bottom edge with one dim photograph above it, or one object or photograph centred at 40–50% of the viewport with a line of text beneath at 16–18px. Each later screen holds one or two lit items at a generous size (the main one 40–60% of the screen width) with its text set beside it at full reading size; the darkness frames the items, it does not replace content. Images vary in size but all share the same darkness at their edges so they sit in the ground without frames. Practical details come last in a simple two-column text block. The only solid light element on the page is the single primary action.

## 3. Type — Zodiak / Hanken Grotesk
Hairline serif capitals set large enough that the thin strokes read as drawing, with a small neutral sans for everything practical. Suits lodges, spas, architecture and interiors; keep it on flat pale or dark fields where the strokes have full contrast.
```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="preconnect" href="https://api.fontshare.com">
<link rel="preconnect" href="https://cdn.fontshare.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Hanken+Grotesk:wght@400;500&display=swap" rel="stylesheet">
<link href="https://api.fontshare.com/v2/css?f[]=zodiak@100,300&display=swap" rel="stylesheet">
```
- Display: **Zodiak** — weight 100 above 96px, 300 for 28–36px statements · tracking -0.01em · leading 1.0 · capitals for the headline, sentence case for statements; never bold
- Text: **Hanken Grotesk** — 400 for body, 500 for emphasis · leading 1.5 · tracking 0
```css
:root {
  --step-small: clamp(0.8125rem, 0.79rem + 0.1vw, 0.875rem);
  --step-body: clamp(1.125rem, 1.05rem + 0.3vw, 1.3125rem);
  --step-lead: clamp(1.25rem, 1.1rem + 0.7vw, 1.625rem);
  --step-head: clamp(2rem, 1.3rem + 3.2vw, 4rem);
}
```
Light or regular weights; they glow on dark. Avoid heavy bold, which looks cheap on black. Headings 4–7vw at most; text 16–18px with leading 1.6 and a slightly lifted ink (not pure white).
The direction decides sizes and how many of them; the type system decides how this face is set. Where the two give different tracking or leading, use the values of the type system: they are tuned to this face.
The display size itself comes from the hero blueprint below.

## 4. Colour — Charcoal and sage
```css
:root {
  --bg: #1c1c1c;
  --surface: #262626;
  --ink: #f1f1ef;
  --muted: #9a9b96;
  --accent: #8fa68e;
  --accent-ink: #1c1c1c;
  --field-1: #c8c5ba;  --field-1-ink: #1c1c1c;
}
```
Charcoal holds the page with off-white text, and one section may flip to a pale concrete tone with dark text. Colour comes from the photographs; grey sage is used for the single button and the active state of a control.
A dark ground with a tint (blue-black, green-black, brown-black), a soft light ink, a dimmer ink for secondary text, and one warm or bright accent used once per screen at most.

## 5. Imagery
**The project has no photographs of its own yet.** First choice: **real pictures of the subject's world from the open collections**: its city and street in old postcards and photographs, an old map of the neighbourhood, its materials up close, its trade in engravings, museum objects of its kind (references/imagery.md §3). Start with `node /home/sanmi/.claude/skills/taste-skill/scripts/assets.mjs "restaurant"`, then queries from your inventory (its street, its materials, its trade's tools and history). Download what you use into the project with `--get` and credit every one on the page (`--credits` prints the footer line). They show the world around the client, never the client's own room, dish, product, person or team. Second, for what no honest picture exists of: draw the subject's own things in code, large, in one flat graphic language and the palette's real colours (its tiles, its windows, its objects; references/imagery.md §8). Third: the dealt photographs, only where an honestly generic picture fits (the sea, the weather, a material), never one a visitor could take for the thing itself and never an unrelated scene. One treatment for all of them. If this direction is built on photographs, plan the set before building (four to six pictures of one family) and reuse them at different crops and scales instead of finding or drawing a new picture for every slot; keep drawings as `<symbol>` + `<use>` or generate them with a small script so the page stays light. List every found picture (with its source and licence), every dealt photograph and every drawing that stands in for photography in your report.

Low-key photographs: dark backgrounds, one light source, subjects emerging from shadow. Their dark edges should blend into the page ground.

**Floor (the inspector checks it):** at least four real pictures on the page, usually six to twelve; SVG drawings do not count. One moving picture where an honest clip exists: `node <skill>/scripts/assets.mjs --video "<query>"`, used as a muted loop with a poster frame. Whatever this direction says about pictures, it is carried out with real ones.

Photographs dealt for this build, from the "Interior and table" set (rooms, windows, chairs, cups, food and tabletops)
Order of preference for imagery: (1) the project's own photographs and product shots; (2) images you generate, if an image tool is
available; (3) real pictures of the subject's world found in the open collections (assets.mjs, downloaded and credited on the page);
(4) drawings of the subject in code; (5) these. They are real photographs (Unsplash licence, served by picsum.photos) of places, light and
texture. They are atmosphere, never a stand-in for a specific product, dish, person or interface. List the ones you use in your report so
they can be replaced.
Treatment: If a photograph's background is lighter than the page, darken the image slightly (`filter: brightness(0.85) contrast(1.05)`) rather than adding a frame.
- `https://picsum.photos/id/490/1600/1000` — Dark overhead arrangement of a cutting board, pan and kitchen tools · dark tone · dark brown, black, tan · native landscape · photo: Todd Quackenbush
- `https://picsum.photos/id/1068/1600/1000` — Small plant in a vase on a white table against a blue wall · mid tone · bright blue, white, green · calm area: top · native landscape · photo: Padurariu Alexandru
- `https://picsum.photos/id/225/1600/1000` — Glass teapot of herbal tea beside yellow flowers on a table · mid tone · amber yellow, dark brown, white · native landscape · photo: Vee O
- `https://picsum.photos/id/208/1600/1000` — Multi-pane window in a dark room looking onto green trees · dark tone · black, leaf green · calm area: left · native landscape · photo: Martin Wessely
- `https://picsum.photos/id/635/1600/1000` — Enamel mugs and a teapot on a wooden table in warm light · mid tone · terracotta, warm brown, white · native landscape · photo: André Freitas
- `https://picsum.photos/id/834/1600/1000` — Three framed prints on a pale wall above a dark tufted bench · mid tone · cream, charcoal, pale blue · calm area: centre · native portrait · photo: The Anchor
- `https://picsum.photos/id/889/1600/1000` — Close-up of frosted frozen berries in purple and red · mid tone · frosted purple, berry red, pale lilac · calm area: top · native landscape · photo: Devin Rajaram
- `https://picsum.photos/id/311/1600/1000` — Wooden chair by a curtained window at dusk · dark tone · dark brown, dusty blue, cream · calm area: left · native landscape · photo: Logan Adermatt
The last two path numbers are width and height: request the exact crop you render (`/1200/1500` for a 4:5 portrait, `/2400/1350` for a hero) and
add `?grayscale` to desaturate. Use three to six of them, not all eight. Always set `width`, `height`, `alt` and `loading="lazy"` (not on the hero image).
Other sets that fit this brief: city-night, hands-and-making, plants-and-flowers, coast — `node draw.mjs --photos <set>` prints one in full; `--photos list` shows all sets. Taking photographs from another set is your call and is not a redraw; keep them to one light and one treatment.

## 6. Hero — Inset photograph, crossing wordmark
One deep, dark flat field. A tall portrait photograph fills the left 47% of the viewport, inset 20px from the top, left and bottom edges, with a 12px radius and object-fit: cover; it is position: sticky for the length of the page while the right side scrolls. Five text links at 15px sit top-right on a 32px margin, the last of them the single action. There is no logo at the top. In the right half, at 44% of the height and centred on that half's own axis, one supporting sentence in the light ink at 24px on up to three lines, max 26ch. Along the bottom runs the name in lowercase at clamp(120px, 12vw, 200px), light or regular weight, tracking -0.04em, on one line. It starts over the photograph's lower-right corner, overlapping the image by about 10% of the viewport width (z-index above it; mix-blend-mode: difference keeps the letters legible on the picture), and ends flush with the right margin, its baseline 40px above the bottom edge.

On a phone: The photograph takes the top 58% of the screen, inset 12px, with the same radius. The name runs under it at 21vw on one line, its ascenders overlapping the photograph's bottom edge by 3vw. The sentence follows at 17px, left-aligned on a 20px margin. Links reduce to a menu word and the action on the top row, set over the photograph.
Headline: The name in lowercase on one line, up to 9 characters at this size; a longer name drops to 9vw.

Adjusted for this page:
- It names its own corners: use the one radius of this page (square unless the direction says otherwise) and one button shape.
- It blends text with `difference`: over anything but black, white or grey that gives random hues. Use a solid ink chosen for each ground instead.

## 7. Sections, in order
Each is a composition to rebuild with this project's content. Keep the geometry; change the subject. Re-order or drop a
section when the argument of the page needs it (see references/copy.md), never to fall back to a card grid.

### 1. list — Sticky photograph, active list (split)
Two columns. The left, columns 1–5, is `position: sticky; top: 12vh` and holds one photograph or object at 4:5, 34vw wide, unframed on the ground. The right, columns 7–11, holds a vertical list of five to nine names (treatments, rooms, courses, services) at clamp(20px, 1.6vw, 24px), each row 58px tall with the name at the left and a price or duration right-aligned at 15px in the soft ink. The active row is full ink; the others rest at 40% opacity. Hovering, focusing or scrolling a row to the middle of the viewport makes it active, crossfades the left photograph to that item's image over 300ms, and opens a three-line description at 15px, 34ch wide, directly under the name, pushing later rows down. A heading of two or three words at clamp(32px, 3.4vw, 48px) sits above the list, aligned to its left edge, 48px above the first row.
On a phone: The photograph becomes a 4:3 band pinned to the top of the screen at 38svh while the list scrolls beneath it; the heading sits above the first row. Rows are 56px tall and the description opens on tap. The image changes with the row nearest the band.

Adjusted for this page:
- It fades text: anything a visitor reads, inactive items of a list included, is `--ink` or `--muted` at full strength (contrast 4.5:1 or more). The active item is told apart by `--ink` against `--muted`, weight or the accent, not by fading the rest.

### 2. how-it-works — Drag row of moments (carousel)
A story told left to right. Top row: the heading, two to four words at 28–36px, at the left margin; at the right margin two solid square buttons, 48px each, for back and forward. Directly beneath, a solid bar 6px tall spans the full width in a soft tone, with a full-ink segment showing how far the row has been scrolled. Then the row itself: six to nine columns, each 280px wide with 32px gaps, in a horizontally scrolling flex container with scroll-snap that bleeds off both viewport edges. Every column reads top to bottom: a one-line caption at 18px bold that carries the order in words ('We met', 'Two winters on', 'The move'), three lines at 15px in the soft ink, then 80–140px of empty space that varies per column, then a 4:5 portrait photograph. Because the spaces vary, the photographs' top edges rise and fall along the row.
On a phone: Columns are 72vw wide with 16px gaps, so the next one always shows at the right edge. The buttons are dropped; the 6px progress bar stays under the heading. The empty space inside columns shrinks to 32–64px but still varies from column to column.

### 3. proof — Names as one sentence (statement)
Client, press or partner names are written out as one run-on sentence and set large: clamp(40px, 5vw, 76px), leading 1.0, flush left across all twelve columns, wrapping naturally over five to seven lines. Names are separated by commas and the list ends with a full stop. All names are set in a dimmed ink (still at least 3:1 against the ground), so the block reads as texture; one name at a time is lit in the full ink, and the lit name moves through the list every two seconds (a class toggled by a short script, with a 400ms colour transition). The sentence opens with two or three plain words in the full ink that act as the heading: the phrase that introduces the names. Nothing else is in the section: no logos, no columns, and 120px of space above and below.
On a phone: The same sentence at 9vw, wrapping over ten to fourteen lines; cut the list to the twelve strongest names so it stays within one and a half screens. The lit name still cycles. Margins are 20px and there are no other elements.

Adjusted for this page:
- It names a kind of typeface: use the dealt faces (display Zodiak, text Hanken Grotesk) and their settings; capitals only where the type system sets them.
- It sets a sentence large over several lines: jurors read that as a long headline. Either cut it to a two-line headline at display size, or keep it a statement at 36px or less on three lines (24px, four lines on a phone).

### 4. showcase — Band of unequal panels (strip)
A modest heading, then one wide band of pictures. The heading, two to four words at 36–44px, starts at column 3, left of centre, with 64px beneath it. The band spans the full viewport width edge to edge and is 68svh tall: four photographs standing side by side with no gaps and deliberately unequal widths, a flex row with flex-grow values of 5, 2, 3 and 2. Each panel is cropped with object-fit: cover to a tall slice, so the band reads as one folded view. The first panel carries a two-line title at 28px in paper colour at its top-left, 24px in, over a neutral scrim that covers only that corner. Hovering a panel raises its flex-grow to 6 over 500ms while the others give way; without hover the widths simply stay. One caption line at 16px sits under the band at the left margin.
On a phone: The heading sits on the margin at 28px. The band becomes a two-by-two block with no gaps, 84svh tall in total: the first row split 64/36 and the second 36/64, so the slices stay unequal. The title stays on the first panel. No hover behaviour.

### 5. cta — First step, already open (split)
The page ends with the product's first screen already open. A full-bleed field in the accent colour, 90svh tall. Right, in columns 7–11, vertically centred: the product's own create dialog at true size, 440px wide, root marked data-ui, with its real title ('Name your workspace'), one text field holding an editable, believable example, one two-option segmented choice and its primary button. Inside the fragment the product's conventions apply, including the shadow its dialog normally casts. The field accepts typing, the choice switches, and the primary button is the page's only action: it carries the typed value into sign-up. Left, in columns 1–5, anchored to the bottom with 40px margins: the closing line, two to four words at clamp(56px, 7vw, 112px), leading 0.92, on two lines, and under it one sentence at 17px saying what happens after the button. The upper-left stays empty.
On a phone: The closing line leads at 13vw on two lines, top-left, with its sentence beneath at 16px. The dialog follows at full width inside 16px margins, at true size, its primary button 52px tall and full width. The field is at least 84svh; the dialog sits in its lower half.

Adjusted for this page:
- It mentions a shadow: only on something open above the page (a menu, a dialog), never on a resting element.

### 6. footer — Columns above, wordmark below (stack)
The footer inverts ground and ink and fills a whole viewport (min-height 100svh), arranged as three rows in a grid (auto 1fr auto). Top row, 40px from the edge: four small columns on the 12-column grid (address, navigation, social, hours or contact), each a plain list at 15–16px with its first line in the full ink and the rest in the softer ink. Middle row: empty. Bottom row: the wordmark alone, sized to run from the left margin to the right margin on one line. Set it as inline SVG text with a `viewBox` fitted to the word and `width: 100%`, or tune `font-size` in `vw` to the letter count, so it never wraps; its baseline sits 20px above a single line of legal text at 13px. With `position: sticky; bottom: 0` and a lower `z-index` than the page, the last section slides away to uncover it.
On a phone: Columns become two by two at 15px with 32px between rows, at the top. A gap of about 20svh follows. The wordmark spans the full width at the bottom; a name longer than ten letters stacks on two lines, each fitted to the width. Legal line beneath at 12px.

Adjusted for this page:
- It sets text at 12px: use your small step (13px or more) instead.

## 8. Motion — Scaling wordmark
The giant name responds to the scroll: as the first screen leaves, the wordmark scales down a few percent (or grows from 0.94 to 1 as the footer arrives) on a scroll-linked timeline, so the monument feels heavy. Travel stays under 8%. Static fallback where scroll timelines are unsupported. Recipe: 'Scroll-linked progress' in references/motion.md, animating `scale`.
This is the signature move, the one loud moment. Around it the page still needs the rest of the motion layer (SKILL.md step 9): an entrance for the parts of every section, feedback on every control, and one moving picture. The inspector fails a page that barely moves. Recipes: references/motion.md.

## 10. Where the richness comes from
Light against dark, and the slow pacing.

## 11. How this direction goes wrong
Grey text on black fails contrast quickly: check it. No glow effects, no neon accent, no coloured light blobs. A dark page with a bright busy photograph is not this direction; choose low-key images. Dark does not mean empty: every screen still carries its content at a readable size, and running text is a light ink at 7:1 or more, not a dim grey.

## 12. Real references
Studied sites that work this way. Take the principle, never the specifics: no names, copy, images or signature assets of theirs.
- **Moto Finance** (Awwwards Site of the Day, studied score 7.3) `aww-moto-finance`
  Idea: A metal card presented like a museum piece: lit by a single overhead light box in a black room, then orbiting through dim architectural stills.
  Hero: Black full-bleed 3D scene. A pale silver-lavender light panel, a trapezoid in perspective like a ceiling light box, occupies the top 20% and centre 55% of the viewport. The two-line uppercase headline, centred at 4.4vw in medium grotesk with tight leading, straddles the panel's lower edge so its first line sits half on light, half on black. In the exact centre a small metal card stands on a low plinth, only about 150px wide, surrounded by darkness. A light-grey pill button (about 250 by 52px) is centred near the bottom; the logo is top-left and a dark pill button top-right.
  Worth taking: Let a headline straddle the edge of a light shape so one line is half on light and half on dark.
- **Cipher** (Awwwards Site of the Day, studied score 7) `aww-cipher`
  Idea: The showreel as a slowly turning wreath: a dozen film stills overlap in an elliptical ring around a tiny emblem on a black screen, with almost no text.
  Hero: The first capture is the preloader: a black screen textured with a fine grid of grey dots that thins out towards the right, the wordmark top-left and a tiny copyright glyph dead centre. Once loaded, the black screen holds a ring of about fourteen landscape film stills, each roughly 10% of the viewport wide, overlapping one another around an ellipse about 35% of the width; the centre is empty except for a 20px white emblem. The wordmark, about 11% wide, stays top-left. Four 9px uppercase links are split: one at the horizontal centre, three at the far right. A partner mark sits bottom-right. There is no headline.
  Worth taking: Arrange work thumbnails as an overlapping elliptical ring with an empty centre, and bind scroll to rotation instead of vertical travel.
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

Saved to /home/sanmi/IdeaProjects/JToye_OaaS_2026-design-run/design/runs/2026-11-01-landing/draw/dark-room/direction.json · reprint with `node draw.mjs --show`
