# Identity — J'Toye public surfaces

**Status: RE-RULED by Sanmi 2026-11-01 (decision D14), adopting `runs/2026-11-01-landing-elevate/palette.md`.
D13's "Market day" palette (tomato, palm-oil yellow, leaf) is SUPERSEDED; D11 was the first ruling.** Every
later change is logged in `decisions.md`.

## Palette — oxblood, gold and warm stone (flat; no gradients, D9)

The full system, sources and contrast table: `runs/2026-11-01-landing-elevate/palette.md`. Tailwind
tokens: `brand-*`, `gold-*`, `warm-*` (`frontend/tailwind.config.ts`).

| role | name | hex | where |
|---|---|---|---|
| ground | white | `#FFFFFF` | header, kitchen section, controls sitting on stone |
| ground step | warm-100 | `#F4F2F0` | hero, How it works, kitchen cards; a tone step replaces every rule and shadow |
| ink | warm-900 | `#1E1814` | body text; warm-700 `#4D4642` for secondary text |
| brand | oxblood brand-900 | `#3A0B0D` | headings, the wordmark, the operator door field, step circles, text on gold |
| brand mid | brand-700 | `#6E1D21` | links, the focus ring, the How-it-works rail fill, the search icon |
| action | gold-400 | `#E8B04B` | the one accent: the search button and the Order-food door, brand-900 type on it |

Ramps: oxblood brand-100 `#F8E9E8` · 700 `#6E1D21` · 800 `#571417` (hover) · 900 `#3A0B0D`; gold 200
`#FCD89E` (Paused badge) · 400 `#E8B04B` · 500 `#D1992D` (hover); warm 100 `#F4F2F0` · 200 `#E8E5E4`
(hover) · 300 `#D7D3D0` (rail track) · 600 `#655F5B` (placeholder) · 700 `#4D4642` · 900 `#1E1814`.
States: Open `#047857` white text (5.48:1, the lowest text pairing); Paused gold-200 with brand-900;
Closed warm-700 with white. Gold-50/100 read as cream: never a ground. Not used: tomato red in the
interface (the photographs carry the food's reds), Tailwind amber/orange on the landing, cream grounds,
violet (`oxblood-deep`). Split on screen ≈ 60 neutral / 30 oxblood + photographs / 10 gold.

## Type (one family)

**Work Sans** does everything. Display 800, tracking -0.03em, leading 1.04, `text-wrap: balance`;
text 400/500/700, 16px body, leading 1.55, tabular figures. Five sizes on the landing (D14):
14 · 16 · 20 · 28 · 56px (h1 34px on a phone). Corners: pills for controls and chips, 12-16px on
cards and doors (kept from the old page, which the owner chose as friendlier). Buttons: flat gold-400
with brand-900 type, pill, 52px tall; hover gold-500.

## Voice

Plain, in the kitchen's own words, and only what the platform does today (rubric R6). Headlines are
titles of four to six words; the landing h1 "Order from local kitchens. Or run yours." is kept on three
lines by the owner (D14). No slogans, no "delicious", no "seamless",
no exclamation marks, no claims of scale. The food is named (jollof, suya, jerk), the street is named
(Rye Lane, Brixton Market), the facts are the three true ones (hygiene rating verified, allergens on
every item, nothing to download).

## The one memorable device

**Under D14 the landing keeps its existing structure (rubric R11, elevate not redesign):** the
two-column hero, search and dish chips, the two doors as two flat fields (gold for ordering, oxblood
for operators), real kitchens, How it works with its rail. The memorable thing is the food itself:
credited dish photographs in the tiles, the chips, the Order-food door and every kitchen card, set
against a quiet warm-stone interface. The order ticket on the pass (D11/D13's device) is not used on
the elevated landing. Motion: the existing GSAP hero scene, less the heat-wash parallax (the wash is
gone); reduced-motion still.

## Imagery (D10)

Real, credited pictures of the kitchens' world until each kitchen's own photos exist; the four real
product screens for operators; never a stock burger, never an emoji, never a drawn dish.
