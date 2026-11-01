# Identity — J'Toye public surfaces

**Status: RE-RULED by Sanmi 2026-11-01 after the blind A/B (decision D13; the first ruling was D11).** Every later change is logged in `decisions.md`.

## Palette — "Market day" (six named colours, flat; no gradients, D9)

| role | name | hex | where |
|---|---|---|---|
| ground | white | `#FFFFFF` | the page, tickets, the search field |
| ground step | warm grey | `#F4F1EC` | a section a tone down; replaces every rule and shadow |
| ink | ink | `#1E1A17` | text and headings |
| brand | jollof tomato | `#C7361F` | the hero band the plate breaks, headline accents, the footer field |
| action | palm-oil yellow | `#FFC531` | the one accent: buttons and the active state, ink type on it |
| leaf | leaf | `#1F7A4C` | "Open", the three trust facts |

Contrast (WCAG): white on tomato 5.28, ink on yellow 10.93, white on leaf 5.32, ink on white 17.28,
ink on warm grey 15.34, tomato on warm grey 4.69. Status words: open = leaf, paused `#D98A12` with
ink, closed `#8A8078`. Food photography supplies every other colour. Not used: oxblood as a field
(D13), a dark ground anywhere, the cream-and-orange wash, violet.

## Type (one family)

**Work Sans** does everything. Display 800, tracking -0.03em, leading 0.95, `text-wrap: balance`;
text 400 with 500 for emphasis, 17px body, leading 1.55, tabular figures. Five steps on a page:
small 15px · body 17px · lead 20px · heading `clamp(1.75rem, 4.6vw, 3.6rem)` · the hero and the footer
name larger. Corners decided once: square, the plate's circle excepted. Buttons: flat palm-oil yellow with
ink type, square, 52px tall; hover inverts to ink with yellow type.

## Voice

Plain, in the kitchen's own words, and only what the platform does today (rubric R6). Headlines are
titles of four to six words ("Order from kitchens near you"). No slogans, no "delicious", no "seamless",
no exclamation marks, no claims of scale. The food is named (jollof, suya, jerk), the street is named
(Rye Lane, Brixton Market), the facts are the three true ones (hygiene rating verified, allergens on
every item, nothing to download).

## The one memorable device

**The order ticket on the pass.** A white ticket with a perforated top edge (an SVG circle tile, never a
gradient) carries each step, each kitchen row, the operator's kitchen display. The hero is white: the plate breaks
a jollof-tomato band, and the search and two plain doors (Order food / Run your kitchen) sit under
the headline in the first screen (R9). One motion moment per
page at most: the plate settling on load, CSS only, reduced-motion still.

## Imagery (D10)

Real, credited pictures of the kitchens' world until each kitchen's own photos exist; the four real
product screens for operators; never a stock burger, never an emoji, never a drawn dish.
