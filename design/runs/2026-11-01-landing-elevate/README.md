# Landing elevate run — 2026-11-01 (review material, no app code)

Scope, per rubric R11: **elevate the existing landing, do not redesign it.** The structure, layout,
copy and both doors stay. Prepared by an agent as a subagent, so no [OWNER] step was taken. Every
choice below is a proposal for Sanmi.

| File | What |
|---|---|
| `palette.md` | The palette system, each colour traced to the brand or a reference, with a contrast table and the 60-30-10 split measured on the screenshots |
| `sketch/index.html` | BEFORE and AFTER side by side: first screen at 390 and 1280, then the kitchens and How-it-works sections at 390 and 1280 |
| `sketch/before.html` | A static copy of the current landing (origin/main `1fb15a8b`, PR #1080 merged): page.tsx, hero-search, near-you-row, shop-card, public-header, with the Tailwind classes written out |
| `sketch/after.html` | The same markup, elevated |
| `sketch/shots/*.png` | `{before,after}-{390,1280}-{fold,full}.png` and `compare-{390,1280}.png`, captured with headless Chrome |
| `sketch/credits.json`, `sketch/img/` | The 8 dish photographs used, with sources and licences: 6 reused from the design-run worktree's credited set, plus biryani and chana masala fetched for the South Asian and Vegan chips, which had no picture |
| `inspect-after/`, `inspect-before/` | taste-skill inspect reports and screenshots at 1280, 768 and 390 |

## What changed, BEFORE → AFTER

**Emoji → photographs.** The 4 hero tiles become egusi, suya, jollof and puff-puff. The 6 category
chips each gain a round dish thumbnail. The Order-food door gains a jollof-and-plantain photo strip,
which puts food in the phone's first screen: the tiles are desktop-only, as before. The kitchen cards
show a cuisine photo captioned "Nigerian — example photo" / "Grill — example photo" (D13 wording)
in place of the placeholder gradient logos (D12).

**Tells removed** (each one had nothing good attached to it):
- the radial cream wash (D9);
- the amber→orange gradient door and the four gradient emoji tiles (D9);
- the tracked-caps eyebrow "🇬🇧 INDEPENDENT UK KITCHENS". The word *independent* is already in the
  lede, and *UK* is in the trust strip;
- every resting shadow (21 measured on BEFORE), including the search button's orange glow;
- the 1px header and section rules, and the 1px outlined chips, cards and location button. These
  surfaces are now separated by a tone step: white against stone;
- the yellow cream ground;
- the appended "→" on "See all kitchens" (the doors keep their arrow icons);
- the edge-fade gradient on the kitchen scroller. A deliberate peek replaces it: phone cards are 80%
  wide, so the next card shows. **See the unsettled list.**

**Goods kept:** the two-column hero; h1, lede, search, chips, doors in that order; the doors' copy,
icons and arrows; the crawlable chip links; the scroller and its accessible name, "Dishes cooking
near you", byte-identical to the Playwright selector; the Open/Paused badges and the fee and minimum
lines; How it works with its three steps, icons and the rail (the GSAP moment, recoloured
oxblood-700); the friendly rounded shapes. Pills and 12–16px card corners are kept on purpose,
because the owner chose the old page as "friendlier", even though taste-skill prefers square or pill.
Tap feedback (`active:scale`) and hover gated to `(hover:hover) and (pointer:fine)` are kept.

**Type:** one family (Work Sans, self-hosted variable file) on five sizes, as measured by inspect:
14 · 16 · 20 · 28 · 56 px. The h1 drops from 900 to 800 weight and takes -0.03em tracking. Body
moves from 14px slate to 16px warm ink.

**Copy:** unchanged except one promise. PR #1080 has merged on origin/main (`e2e7774c`), and its
wording is already in BEFORE: "assisted setup" and "A person reviews every application". The only
copy edit is How it works → Track live, which drops "from the kitchen to your door in real time".
The platform has no courier tracking: `/track` polls the order status every 15 seconds
(`app/track/page.tsx`), and the statuses run PENDING → CONFIRMED → PREPARING → READY → COMPLETED.
New text: "See your order's status change as the kitchen confirms it, prepares it and gets it ready."

## Gate (run from the worktree root, 2026-11-01)

`rule-check.sh allow --scope page --rulings design/rulings.tsv` returned `--allow still-page`. Its
SHARED note says to check ambient motion by reading the code. Code read: `after.html` has no
animation and no keyframes, only 120–160ms state transitions on press and hover, plus a
reduced-motion override.

| Check | BEFORE (fail arm) | AFTER |
|---|---|---|
| `lint.mjs` | rc 1 · FAIL shadow ×10, hairline ×2, gradient ×7, eyebrow ×2, emoji ×7 | rc 0 · **0 FAIL**, 2 WARN: `cta-copy` ("Learn more", the current copy, kept) and `em-dash` (6, all in the current copy) |
| `inspect.mjs --widths 1280,768,390 --allow still-page` | rc 1 · **12 FAIL**, 4 WARN (headline-lines ×2, eyebrow, emoji, gradient, shadow, button-fx, hairline, outlined-box, body-size, few-pictures, small-text; WARN micro-label, **palette-cream**, no-footage, side-scroll) | rc 1 · **3 FAIL**, 2 WARN |

The first AFTER run also failed `body-size` (14px) and `measure`. Both were fixed by moving body copy
to 16px and capping the credit line. A stray FAIL from the word "shadow" in a CSS comment was fixed
by rewording the comment, not by suppressing the check.

**AFTER's remaining FAILs are not fixable inside the elevate scope, so they go to the owner:**
1. `headline-lines` at 1280 and at 390: "Order from local kitchens. Or run yours." sets in 3 lines at
   56px in the hero's half-width column, and at 34px on the phone. BEFORE does the same: 3 lines at
   60px and 36px. Two lines need either fewer words or an h1 spanning both columns, which is a layout
   change.
2. `focal-point` at 1280: the largest type is 4.4vw, and the 2×2 photo grid does not count as one
   dominant picture.

**AFTER's WARNs, defended:** `side-scroll` is the kitchen scroller, a deliberate existing
affordance. `no-footage`: video is outside the elevate scope.

**Palette check:** inspect's colour-family rules (cream, terracotta, purple) report nothing on AFTER.
On BEFORE they report `palette-cream` (`#FBF6F0`). The same classifier was run by script over every
token in `palette.md`: only gold-50 and gold-100 class as cream, and both are barred from grounds.
Round 2's tomato `#C7361F` classes as terracotta.

**Not run:** the separate scorer, the owner's blind A/B (design-run step 8), a throttled-mobile CWV
measurement, and a real touch device. Hover is gated in CSS, but no `matchMedia` check was made on a
device profile.

## Unsettled — for Sanmi

1. **Adopt the palette** (`palette.md` §5): it replaces D13's "Market day".
2. **The h1 versus the two-line rule.** Either keep the current h1 and record a `headline-lines`
   ruling, or shorten it (round 1 accepted "Order from kitchens near you"). Recommendation: keep it
   for this elevate, which is what the A/B winner had, and record the ruling.
3. **Gold on the phone fold is 15.9%,** because the Order-food door is a gold field. Recommendation:
   keep it (`palette.md` §3.5).
4. **The scroller's edge fade** is a functional gradient, not decoration. Keep it as an exception to
   `gradient-decoration`, or adopt the peek the sketch shows. Recommendation: the peek.
5. **The Track-live copy edit,** above.
6. **Positioning** stays an open owner decision, as page.tsx records it: UK-wide on the landing
   versus the West African London pilot on `/for-operators`. The eyebrow that said "UK" is gone, so
   this sketch claims neither.
7. **Two new photographs** were added to the reused set: biryani (CC BY-SA 4.0) and chana masala
   (CC0). The Vegan chip's chana masala plate also has samosas beside it, and their filling is not
   stated.
