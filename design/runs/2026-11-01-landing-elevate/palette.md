# Palette proposal — landing elevate (2026-11-01)

**Status: PROPOSAL for Sanmi.** Nothing here is ruled. It answers round 2's verdict: "the red colour
from out of nowhere is cringe" and "the colours don't represent a theme that is aesthetically thought
through and cognisant of colour theories". Rubric R13 says every colour must trace to the brand or a
reference. This file does that, one colour at a time. The sketch that applies it is
`sketch/after.html`.

How the numbers were made: the reference hexes are 5×5-pixel averages read from
`design/references/img/*-fold.png` with Pillow. The ramps are computed in OKLCH: hue fixed, lightness
stepped, chroma kept moderate, then clipped to sRGB. The contrast ratios use the WCAG 2.1 relative-luminance
formula. The script is in the session scratchpad, not the repo, so every figure below is a
measured value, not an estimate. Phone captures sit under a cookie overlay, so their samples are dimmed.
For those, the desktop capture is the source.

## 1. What the brand already has, and whether each colour survives

| Token today (`frontend/tailwind.config.ts`, `app/globals.css`) | Hex | Verdict | Why |
|---|---|---|---|
| `oxblood` | `#3A0B0D` | **Survives, as brand-900** | The parent brand's anchor (D4, jtoyedigital.co.uk). It is the one hue J'Toye owns, the way teal is Deliveroo's and orange is Just Eat's. It is a deep red-brown, close to black, so it never reads as the bright red that "came from nowhere" in round 2. |
| `oxblood-700` | `#571417` | **Survives, renamed brand-800** | It sits on the computed ramp: OKLCH L 0.31 at the brand hue. |
| `oxblood-600` | `#6E1D21` | **Survives, renamed brand-700** | The same reason (L 0.36). The ramp's 700 is taken as this exact value, so no existing shade moves. |
| `oxblood-deep` | `#1F0F28` | **Retired** | OKLCH hue 312: it is a violet, not an oxblood. This is the taste-skill "purple" tell hiding inside the brand name. Nothing on the landing uses it. |
| `gold` | `#E8B04B` | **Survives, as the one action accent (gold-400)** | It is already the brand's appetite accent (the operator door's icon and link). Its hue is about 40° from oxblood on the HSL wheel (OKLCH 79°), so the two are analogous, and the oxblood-on-gold contrast is 8.71:1. |
| Tailwind `amber-500` / `amber-400` / `orange-500` | `#F59E0B` `#FBBF24` `#F97316` | **Retired from the landing** | Tailwind defaults, not brand colours. The search button, the order-door gradient and the scroller focus used them. They are the source of the orange heat in the current hero, and `orange-500` is the "assistant palette" orange. |
| `amber-ink` | `#3A2400` | **Retired** | Text on gold becomes brand-900, so one colour is removed from the system. |
| `cream` / `cream-100` | `#FBF6F0` `#F4EBE1` | **Retired** | HSL saturation 0.58 and 0.46 at lightness 0.96 and 0.92. taste-skill classes this as a "yellow cream" ground; `inspect.mjs` WARNs `palette-cream` on the current page (see `README.md`). The warm ground is kept, with the yellow taken out: neutral-100 below. |
| slate-500/600/700/900 (body text) | `#64748B` `#475569` `#334155` `#0F172A` | **Retired from the landing** | Cool blue-greys. They are the one cold note on a warm page, and they come from the Tailwind default rather than the brand. They are replaced by the warm neutral ramp. |
| `--trust` emerald-700 | `#047857` | **Survives, as "Open"** | It is already the app's trust and open token, and it carries white text at 5.48:1. |
| `--destructive` red-600 | `#DC2626` | **Survives, app only** | The error colour. It never appears on the landing; white on it is 4.83:1. |
| **Work Sans** | — | **Survives, the only family** | All four references use one sans family for everything: Chowdeck uses Plus Jakarta, Uber uses Uber Move, Deliveroo uses its own grotesque, Just Eat uses JET Sans. Work Sans is the parent brand's face and a plain grotesque of the same kind. The sketch self-hosts the variable file the Keycloak theme already ships (`infra/keycloak/themes/jtoye/login/resources/fonts/work-sans-latin.woff2`). |

## 2. The four references — their colour systems as captured

| Reference | Brand hue (sampled) | Neutrals | Where the accent appears | How photography carries colour | J'Toye takes |
|---|---|---|---|---|---|
| **Chowdeck** (`chowdeck-desktop-fold.png`) | Deep forest green `#0C513F` (logo pill, download buttons, cookie card). A brighter green `#019360` shows in the scalloped trim. | A white ground `#FFFFFF`. The phone hero sits on a pale butter `#FFF4D4`. | The green marks every action (Order now, Accept). The pastel panels (`#CCF3E5` mint, `#FFB3CE` pink, `#FFDBC9` peach, `#99C8FF` blue) are flat category fields, one per thing you can do. | It doesn't: the site is illustrated, so the flat fields stand in for food. | **One dark, owned brand colour that carries every action label and the chrome**, at near-black depth. For Chowdeck that is a deep green; for J'Toye it is a deep oxblood. **Flat fields with no gradient.** J'Toye does not take the pastel rainbow: four extra hues would be four colours with no J'Toye source. |
| **Uber Eats** (`ubereats-desktop-fold.png`, `-phone-`) | Uber green `#06C167` (phone app tile). On the desktop page the brand hue is nearly absent. | White and near-black `#111111`: the Find Food button, Sign up, the type. | The action is a black button, not a coloured one. | **Entirely.** The warm wood `#F0B466` and the red, yellow and gold of the food are the page's colour. The UI stays black and white. | **Let the dish photographs supply the reds and oranges, and keep the interface neutral around them.** That is how J'Toye gets appetite without a red UI colour. |
| **Deliveroo** (`deliveroo-desktop-fold.png`) | Teal `#00CCBC`, used as one flat diagonal slab. Button teal `#43DDC8`, darker text teal `#00665E`. | Grey ground `#F1F0F0` (HSL saturation 0.03, practically neutral), a white card, dark ink. | Teal on the Search and Log in buttons. The slab is the only big field. | The cut-out burger and groceries bring the warm colour against the cool teal. | **A near-neutral light grey ground, so the white controls separate by tone rather than by line or shadow.** The neutral ramp below works the same way, warmed slightly toward the brand. **One brand field used big, once:** the operator door. |
| **Just Eat** (`justeat-desktop-fold.png`) | Orange: logo `#FF8000`, hero field `#AC3A05`→`#D55705`. The hero is itself a gradient. | White header, charcoal Search button. | A charcoal button on the orange field; orange buttons on the dark cookie card. | One bowl photograph, set right of the copy. | **Personas as plain links in a white header** (already J'Toye's header). Its colour is the counter-example: orange on orange with a gradient field is the assistant-palette tell. J'Toye takes the warmth from gold and photographs, not from an orange field. |

**What the four agree on (the system J'Toye adopts):** one owned brand hue; a white ground with one
light neutral step; dark, nearly black type; an action colour used only on actions; and photographs
of food doing the appetite work. None of the four puts a bright red in its interface. Uber's food
reds sit in the photograph.

## 3. The proposal

### 3.1 Brand — oxblood ramp (OKLCH hue 22.6, anchored on `#3A0B0D`)

| Step | Hex | Use on the landing |
|---|---|---|
| 50 | `#FCF5F4` | — (reserved) |
| 100 | `#F8E9E8` | Body text on the operator door |
| 200 | `#EED1CF` | — |
| 300 | `#DFACA9` | — |
| 400 | `#CA827F` | — |
| 500 | `#AF5B59` | — |
| 600 | `#903C3C` | — |
| 700 | `#6E1D21` (= today's `oxblood-600`) | Links, the focus ring, the How-it-works rail fill, the search icon |
| 800 | `#571417` (= today's `oxblood-700`) | Hover for the operator door |
| 900 | `#3A0B0D` (= `oxblood`) | Headings, the wordmark, Sign in, the operator door field, the step circles, text on gold |

Chroma peaks at 0.115 at step 600, below Tailwind red-600's 0.215. Even the mid steps stay a
claret or rosewood, never a signal red. The landing uses only 100, 700, 800 and 900; 500 and below
are there for the app.

### 3.2 Neutrals — warm stone (OKLCH hue 55; chroma 0.002 at the light end, 0.012 at the dark end)

| Step | Hex | Use on the landing |
|---|---|---|
| white | `#FFFFFF` | Header, kitchen section, controls on stone |
| 50 | `#FAFAF9` | — (set by hand: the computed `#FBFAF9` read as cream to the classifier) |
| 100 | `#F4F2F0` | The second ground: hero, How it works, kitchen cards |
| 200 | `#E8E5E4` | Hover step for chips, cards and the location button |
| 300 | `#D7D3D0` | The rail track (decorative) |
| 400 | `#A9A3A0` | — |
| 500 | `#827C78` | — |
| 600 | `#655F5B` | Placeholder text |
| 700 | `#4D4642` | Secondary text: step bodies, card tags, captions, the Closed badge |
| 800 | `#332C28` | — |
| 900 | `#1E1814` | Body text |

This is Deliveroo's device: a grey a hair off white (HSL saturation 0.03). Here it is warmed toward
the oxblood–gold axis, so the ground belongs to the brand without turning cream. At the light end
the HSL saturation stays at or under 0.154, below the 0.16 at which taste-skill calls a ground cream.
A first try at hue 40 with more chroma (`#F6F1F0`) read faintly pink in the screenshots, so it was
pulled to hue 55 with chroma 0.003.

### 3.3 Action accent — gold (OKLCH hue 79, anchored on `#E8B04B`)

| Step | Hex | Use |
|---|---|---|
| 200 | `#FCD89E` | The Paused badge ground |
| **400** | **`#E8B04B`** (= today's `gold`) | **The search button and the Order-food door: the action, and nothing else** |
| 500 | `#D1992D` | Hover on both |
| 50 / 100 / 300 / 600–900 | `#FFF5E7` `#FFEAC9` `#F6C573` `#AC7C1C` `#876115` `#674A12` `#4F390E` | Not on the landing. 50 and 100 read as cream to the classifier: never use them as a ground. |

### 3.4 Semantic

| State | Ground | Text | Ratio | Source |
|---|---|---|---|---|
| Open | `#047857` | white | 5.48 | the existing `--trust` token. Green means available at both Chowdeck and Uber. |
| Paused | gold-200 `#FCD89E` | brand-900 | 12.53 | the accent ramp at low strength: a pause is a caution, not an error |
| Closed | neutral-700 `#4D4642` | white | 9.25 | the neutral ramp: closed is an absence, so it gets no hue |
| Error (app only) | `#DC2626` | white | 4.83 | the existing `--destructive` token; never on the landing |

### 3.5 The 60-30-10 on the landing

- **60 — neutrals.** White (header, kitchen section) and neutral-100 (hero, How it works, card
  bodies), with neutral-900 and neutral-700 type on them.
- **30 — oxblood and photographs.** Oxblood takes every heading, the wordmark and Sign in, the
  operator door as one full brand field, and the step circles. The footer, out of this sketch, is
  the second oxblood field. The dish photographs fill the rest of the 30.
- **10 — gold.** The search button and the Order-food door, the two ways in, and nothing else.
- **The photographs carry the reds, oranges and greens of the food** (Uber's rule). Round 2 put
  that red into the interface instead.

**Measured on the fold screenshots.** The method counts pixels within ±10 per channel of each token,
or ±3 for neutral-100 and ±2 for white, so anti-aliased edges are left out and every figure is a
lower bound.

| Fold | neutral-100 + white | oxblood `#3A0B0D` | gold `#E8B04B` | the rest (photographs, edges) |
|---|---|---|---|---|
| `after-1280-fold.png` | 52.8 + 10.3 = 63.1% | 6.8% | 1.9% | ≈28% |
| `after-390-fold.png` | 46.7 + 14.8 = 61.5% | 2.4% | **15.9%** | ≈20% |
| `before-1280-fold.png` (for scale) | 0.0 + 9.7%; the rest of its ground is cream or the wash | 6.9% | 0.1% (its accent was amber/orange) | — |

So on screen the split works out as about **60 neutral / 30 photographs plus oxblood / 10 gold**.
Oxblood alone is well under 30%. The photographs take the share the round-2 palette gave to red.
**One overrun to rule on:** on the phone fold, gold reaches 15.9%, because the Order-food door's
gold body fills the bottom of the first screen. The alternative is a white door body with a gold
"Browse shops" pill. That would bring gold under 5%, but the door would lose its strong colour,
which the old page's two-door clarity (R9) depended on. Recommendation: keep the gold door, because
it is the action, and the accent is meant to sit on the action.

### 3.6 Contrast — every text/background pairing the sketch uses

| Use | Foreground | Background | Ratio | WCAG |
|---|---|---|---|---|
| Body text | n900 `#1E1814` | white | 17.56 | AAA |
| Body text | n900 `#1E1814` | n100 `#F4F2F0` | 15.72 | AAA |
| Secondary text | n700 `#4D4642` | white | 9.25 | AAA |
| Secondary text, card tags, captions | n700 `#4D4642` | n100 `#F4F2F0` | 8.28 | AAA |
| Secondary text on hover | n700 `#4D4642` | n200 `#E8E5E4` | 7.38 | AAA |
| Placeholder | n600 `#655F5B` | white | 6.28 | AA |
| Headings | b900 `#3A0B0D` | white | 17.02 | AAA |
| Headings, card fee | b900 `#3A0B0D` | n100 `#F4F2F0` | 15.24 | AAA |
| Links ("See all kitchens") | b700 `#6E1D21` | white | 11.29 | AAA |
| Search button, Order-food door | b900 `#3A0B0D` | gold-400 `#E8B04B` | 8.71 | AAA |
| The same, on hover | b900 `#3A0B0D` | gold-500 `#D1992D` | 6.73 | AA |
| Operator door title, Sign in | white | b900 `#3A0B0D` | 17.02 | AAA |
| Operator door body | b100 `#F8E9E8` | b900 `#3A0B0D` | 14.44 | AAA |
| Operator door link and icon | gold-400 `#E8B04B` | b900 `#3A0B0D` | 8.71 | AAA |
| Operator door on hover | white / b100 / gold-400 | b800 `#571417` | 13.84 / 11.74 / 7.08 | AAA |
| Open badge | white | `#047857` | **5.48** | AA (lowest text pairing) |
| Paused badge | b900 | gold-200 `#FCD89E` | 12.53 | AAA |
| Closed badge | white | n700 `#4D4642` | 9.25 | AAA |
| Focus ring (non-text, 3:1) | b700 `#6E1D21` | n100 | 10.11 | passes |
| Rail fill (non-text, `aria-hidden`) | b700 `#6E1D21` | n100 | 10.11 | passes |
| Rail track (decoration, `aria-hidden`) | n300 `#D7D3D0` | n100 | 1.33 | decoration only, carries no information |

**The lowest-contrast text pairing is the Open badge, white on `#047857`, at 5.48:1.** That clears AA
for its 14px bold text. Text over a photograph: the Open and Paused badges are solid pills, and no
other text sits on a picture.

### 3.7 Palette check (taste-skill), and how it was run

1. `inspect.mjs` runs taste-skill's colour-family classifier (`scripts/lib/rules.mjs` `colourFamily`:
   cream, terracotta, ai-purple) over the rendered page. It reports **no palette finding on
   `sketch/after.html`**. **The fail arm:** the same tool on `sketch/before.html` WARNs
   `palette-cream` (section `#FBF6F0`).
2. The same classifier was run over every hex in this file. Ramps 3.1 and 3.2 and every colour the
   landing uses classify clean. Only gold-50 and gold-100 classify as cream, and §3.3 bars them as
   grounds. Round 2's tomato `#C7361F` classifies as **terracotta** under the same function. The
   check would have caught the red that came from nowhere.
3. `lint.mjs` checks the source for the Claude cream and terracotta hexes and for violet and indigo.
   It finds none in `after.html`.
4. **No gradient.** `lint.mjs` and `inspect.mjs` report zero gradient fills on the AFTER page. The
   BEFORE page has 6 (D9).

## 4. Colour-theory reasoning

The scheme is analogous and warm: oxblood (HSL ≈357°) and gold (HSL ≈39°) sit about 40° apart in
the red-to-yellow arc. Together they read as one family: the colours of cooked food, char, palm oil,
caramelised plantain. Neither one is a primary. The neutrals carry a trace of the same arc, so the
whole interface is a single warm key. One cool complement, the green, is held back for "Open" alone,
where its job is to be noticed. The appetite comes from the photographs. With the interface held to
a deep oxblood and a muted gold, the jollof's orange and the pepper sauce's red are the most
saturated things on the page, which is the Uber Eats arrangement. Round 2 did the opposite: it gave
the interface a bright tomato red that the brand never had, so the red competed with the food
instead of framing it. Here red appears only where the brand already had it, at 900 depth, and in
the dishes themselves.

## 5. For the owner

- **Ruling needed:** adopt this system as `identity.md`'s palette, replacing D13's "Market day" (tomato,
  palm-oil yellow, leaf). It would be logged as a `decisions.md` entry.
- If it is adopted, the app keeps `--primary` orange-700 for now. The dashboard's colours are a
  separate run, and changing them here would be scope creep.
