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

---

## Build into the app — 2026-11-01 (design-run steps 6–7, D14)

Owner rulings recorded first as **D14** (`design/decisions.md`); `identity.md` rewritten for the
adopted palette (D13 "Market day" superseded). Built by the frontend agent on
`design/landing-elevate-20261101`. No [OWNER] step was taken.

### What was built

| Area | Change |
|---|---|
| Tokens | `brand` (oxblood 100/700/800/900), `gold` ramp (DEFAULT kept = `#E8B04B`, 200/400/500), `warm` (100/200/300/600/700/900) in `tailwind.config.ts`. **No token retired:** `cream`, `amber-ink` are used by `/shop`, storefront, cart, checkout, track; `oxblood-deep` is mirrored by the Keycloak theme CSS and the k8s golden, outside this write scope. |
| Photos | The sketch's 8 credited WebPs copied to `frontend/public/marketing/landing/` with `credits.json` (paths updated, `copiedFrom` kept). `components/marketing/landing-photos.ts` holds sizes, alt text, the visible credit line and the cuisine → example-photo assignment (first recognised tag, slug order, so re-ordering by distance never moves a picture; no match → no picture). All through `next/image`. |
| Hero | Flat `warm-100` field; radial wash and its GSAP parallax removed; eyebrow removed; h1 800, -0.03em, 34/48/56px; lede 16/20px warm-900; HeroSearch flat (white field, gold-400 pill, no glow, no lift); chips white pills with 32px dish thumbnails; doors are flat gold-400 (with the jollof-and-plantain strip, eager, `fetchPriority=high`) and brand-900; hover one ramp step, gated `(hover:hover) and (pointer:fine)`; press `scale(0.98)`; desktop collage = 4 photos with the old static tilts. |
| Kitchens | White section; cards flat `warm-100`, no border/shadow, hover `warm-200`; own banner via `SafeImage` when present, else the example photo captioned "<Cuisine> — example photo"; the placeholder logo is no longer shown (D12); badges Open emerald-700 / Paused gold-200 / Closed warm-700; **card peek**: cards are 80% wide below 640px, the old `grow basis-260` rule above; `DishScroller` edge fade (gradient) removed, `data-can-*` flags kept for the arrows; arrows flat (no outline, shadow or backdrop blur). "See all kitchens" loses the "→". |
| How it works | warm-100; step circles brand-900; rail warm-300 track with brand-700 GSAP fill; Track-live copy per D14 item 3. |
| Trust strip + credits | Kept (the sketch did not draw it; README above says "UK" lives there): flat warm-100 pills, emerald-700 ticks. The credit line sits in the same band so the page keeps its pinned four Marketing bands. |
| Kept | No client directive on `page.tsx`, both doors and routes, crawlable chip links, `NearYouRow` and its three states, "Dishes cooking near you" byte-identical, JSON-LD, metadata, Marketing tier on all four bands, every remaining `data-hero-*` hook. |
| Not changed (other routes) | `PublicShell`/header/footer/cookie notice are shared chrome: untouched, so the header rule, the footer caps labels and the shell's `bg-cream` ground remain (see gate). |

**Motion, read in code (not from screenshots):** nothing added. Removed: the heat-wash parallax.
State transitions only: `background-color`/`transform` 150 ms `cubic-bezier(0.23,1,0.32,1)`, press
0.97–0.98, arrow nudge 3px on fine-pointer hover, `motion-reduce:transition-none` on the doors and
search button. The GSAP scene (word split + entrance, doors deal-in, title/step reveals, rail scrub,
trust-chip pop) is unchanged — note it still includes per-section entrances that `rulings.tsv`
`section-entrance avoid` would not choose today; untouched here because it is the existing contract.

### CLS fix found while proving (commit `1fee9b9d`)

The first build regressed throttled CLS: 375px **0.2025** (baseline 0.1793, tolerance 0.02) and
1440px **0.157** (control 0.1316). Cause, measured by blocking the web font: the size-adjusted
fallback sets the h1 on fewer lines than Work Sans (2 vs 3 at 360–413px and from 1024px), so the swap
pushed the first screen down a line. Fix: `min-height: 3.12em` on the h1 in exactly those ranges.
After: 375px **0.1608**, 1440px **0.0093** (before build: 0.1795 / 0.0403).

### Gate (step 7) — app, stack-free

Before: `c82cc0e7` built in a scratch copy, `next start -p 3202`. After: this branch, `-p 3201`. Both
against a fixture API on 127.0.0.1:19090 serving the three `DemoDataSeeder` shops (all
`bannerUrl: null`; Brixton paused; real-distance `lat/lon/radiusKm` filtering). Compose and its DB
untouched; the DB-writing near-you-row block excluded (`--grep-invert "disclosed, not dropped"`).

| Check | BEFORE (`inspect-app-before/`) | AFTER (`inspect-app-after/`) |
|---|---|---|
| `lint.mjs` (page, hero-search, near-you-row, shop-card, dish-scroller) | rc 1 · 29 FAIL (shadow, gradient, emoji, eyebrow, hairline…), 4 WARN | rc 0 · **0 FAIL**, 1 WARN `em-dash` (23, incl. comments and D13's caption wording); same with `landing-photos.ts` + `hero-scene.tsx` added |
| `inspect.mjs --widths 1280,768,390 --allow still-page --film` | rc 1 · 13 FAIL / 4 WARN | rc 1 · **6 FAIL / 6 WARN** |

AFTER's FAILs, each placed:
- `headline-lines` @1280 and @390, `focal-point` @1280 — **accepted by the owner (D14 item 2).** The
  catalogue has no rule_id for either, so no `rulings.tsv` row; recorded in D14.
- `micro-label` ("For customers", "Legal", "For operators") and `hairline` (header `border-b`, two
  footer `border-t`) — shared chrome, other routes; not restyled here.
- `body-size` 14px — measured by character count: inside `<main>` 16px 508 vs 14px 491 at 1280;
  outside `<main>` (footer, cookie notice) 14px 614 + 12px 255. The shared chrome decides it.
WARNs: `palette-cream` = `PublicShell`'s `bg-cream` root and the cookie button (shared);
`outlined-box` = the cookie notice; `measure` 203ch and `small-text` = footer OS-data lines and the
header "Sign in"; `side-scroll` = the kitchen scroller (deliberate); `no-footage` (out of scope).
Gone vs before: eyebrow, emoji, gradient, shadow, outlined-box ×4→1 (chrome), few-pictures,
covered-text, the landing's own hairlines.

### Tests

- `npx tsc --noEmit` rc 0. `npm run lint` rc 0 (0 errors, 26 warnings, none in a touched file).
- Jest, landing + `components/marketing` + touched gates (`contrast-literals`, `palette-discipline`,
  `csp-headers`, `reduced-motion-css`, `layout-widths-css`): **17 suites, 198 tests, rc 0**.
  Full suite: 292 suites, 3449/3450 — the one failure was `products-orders-shop-scope` timing out at
  5 s while inspect ran beside it; re-run alone 13/13.
- **Shown failing against the before source** (source files checked out from `c82cc0e7`, new tests
  kept, then restored and `git diff HEAD --quiet` rc 0): 12 of 52 fail — the 5 new "Landing elevate
  (D14)" tests, 4 new ShopCard picture tests, and the 3 restyled fee-row byte controls.
  `landing-photos.test.ts` size check shown failing by a 784→785 edit (reverted).
- Ledger: `contrast-literals` loses `page.tsx::text-emerald-600` and `hero-search.tsx::text-slate-500`
  (sites gone) and gains `page.tsx::text-brand-100` (door body on brand-900, 14.44:1).
- Playwright on 3201 (motion, dish-scroller, landing-webperf, near-you-row, public-layout,
  layout-width-contract, storefront-ssr-seo, mobile-instrument-contract, public-a11y), both projects:
  **149 passed, 9 skipped, 11 failed**. The 11 are all `storefront-ssr-seo` tests that open
  `/shop/{slug}` (menus, JSON-LD, sitemap), which the fixture cannot serve; the same 11 fail on the
  before build.
- **Changed e2e assertion shown failing:** `marketing-motion` "/ … has no heat-wash, scrubs the rail"
  FAILs on the before build (`the heat-wash is gone (D14)`: expected 0, received 1) and passes after.

### Throttled vitals (Fast-3G-ish, 4x CPU; landing-webperf spec + a probe)

| Profile | BEFORE | AFTER |
|---|---|---|
| 375×812 CLS | 0.1795 | **0.1608** |
| 375×812 LCP | 976–988 ms (lede text) | **964–984 ms** (the door photo) |
| 1440×900 CLS | 0.0403 | **0.0093** |
| 1440×900 LCP | 972–988 ms | **956–972 ms in 8 of 9 probe runs; 2968 ms in 1, and 3024 ms in the spec's own run** |
| post-grant shift (spec) | — | 0.0000 |

Phone images at 390px: DPR1 door `w=384`, cards `w=640`; DPR2 `w=640/750`; DPR3 requests `w=1080`
and is served the 800px original (no upscale; checked by WebP header). Chip thumbnails 32/64/96w.

## Unsettled after the build — for Sanmi

1. **Desktop LCP outlier.** The HeroScene word split (unconditional by its recorded decision) can be
   re-reported as a *larger* h1 LCP entry once Work Sans has swapped in, because the fallback face
   now paints the h1 smaller (2 lines) than Work Sans (3). The before page's fallback paint was the
   larger one, so it never happened there. A `deviceSizes` cap at 750w (to keep 3x phones ≤750px)
   made it happen in 9/9 probe runs, so it was reverted (`e31cd586`). Options: split only when the
   entrance plays (changes a recorded HeroScene decision), or match the fallback metrics in
   `app/layout.tsx` (all pages). Not decided here.
2. **3x phones get the 800px original**, not ≤750px, for the reason above.
3. Shared chrome keeps the header rule, footer caps labels and the shell's cream root — a separate
   PR across every public route if wanted (the step-8 ruling from the first landing run).
4. The step-8 look (separate scorer, blind A/B) was not run.
5. `scripts/check-doc-metrics.sh` is red until README/CLAUDE.md/AGENTS.md quote the new counts
   (jest 3450 / 292 files, total 6992); `docs/metrics.json` was regenerated (`--write`). Those three
   files are outside the frontend write scope.
