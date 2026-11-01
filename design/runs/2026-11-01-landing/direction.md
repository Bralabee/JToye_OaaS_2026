# Landing run 2026-11-01 — direction (design-run steps 3 and 5)

Surface: `/` (page). Branch `design/landing-run-20261101`. Brief, references, decisions and rulings:
`design/`. Inventory, argument and copy deck: `notes.md`. Pictures: `assets/` (credits in
`assets/credits.json`, D10).

## Step 3 — dealt, sketched, picked

Dealt by `taste-skill/scripts/draw.mjs --sector restaurant --sections list,how-it-works,proof,showcase,cta,footer --have product-ui --options 3`
(full sheets with seeds in `draw/`). The Pass entered as the fourth candidate under D7. Four separate
agents each built one sketch from the same copy deck and pictures (`sketches/<id>/`), with full-page
captures at 390, 768, 1280 and 1920 and the taste-skill gate run (`--allow still-page`).

| | candidate | seed | lint FAIL/WARN | inspect FAIL/WARN (final) | residual WARN |
|---|---|---|---|---|---|
| A | **The Pass** (007 under the rulings) | n/a (006-A/007) | 0 / 0 | 0 / 1 | no-footage |
| B | page-is-the-object | 1794208771 | 0 / 0 | 0 / 1 | no-footage |
| C | dark-room | 998514087 | 0 / 0 | 0 / 1 | no-footage |
| D | uneven-photographs | 1250599100 | 0 / 0 | 0 / 2 | no-footage, side-scroll (the sheet's own phone strip) |

**Sanmi picked A, The Pass (2026-11-01).** Claude's recommendation was A, for rubric R1: it is the one
first screen that says food ordering at a glance (oxblood slab, the jollof plate breaking its edge, the
search under the headline), and it keeps the structure Sanmi chose in sketch 006 (D7). The others were
not rejected as wrong; they were not chosen. Notes for the record:

- B set the page on a pink docket pad over burgundy: the strongest object, but stationery before food.
- C lit a Nigerian street (the suya seller) under a giant lowercase wordmark: calm and striking, but the
  place is not a UK high street and the wordmark formula depended on the viewport.
- D hung the pictures like prints on rose-grey felt with a Garamond headline: the closest to the
  publication look rejected in April (rubric R1), and the operator screens shrank to thumbnails.

## The idea and the one live thing

On this page, **the order ticket on the pass** (what a kitchen actually works from) becomes the page's
recurring object: the headline interlocked with the plate on the oxblood slab, how-it-works as three
perforated tickets, the operator section carrying a real kitchen-display ticket. The competitor test
holds: an aggregator's page is a search box over stock food; this one is a kitchen's own pass.

The live thing is the postcode-or-dish search, a real `GET /shop?q=` form on the first screen (today's
`HeroSearch`), with the six cuisine links as crawlable `<a href>`.

## The one motion moment (D3; no tool measures the cap)

The hero plate settles from -16° to -6° over 0.9 s on load, CSS only, inside
`prefers-reduced-motion: no-preference`; the still page is the finished page. Nothing else on the page
performs: no entrance, no scroll-reveal, no marquee. The sketch's code was read for other motion:
only hover and focus transitions on links and buttons remain, which the `card-hover` ruling requires.
At the build (step 6) the same reading is repeated on the shipped components, including
`components/marketing/hero-scene.tsx` (GSAP), which must either become this one moment or go.

## Step 5 — skill conflicts

```
$ ~/.claude/skills/design-run/scripts/rule-check.sh conflicts --scope page --rulings design/rulings.tsv
conflicts: none unresolved   (rc 0, 2026-11-01)
```

Gate exceptions for step 7, from `rule-check.sh allow --scope page`:

```
== scope: page
  rule  section-entrance — ruled avoid (2026-11-01, Sanmi) -> still-page
  SHARED  still-page also measures ambient-motion (taste-skill: require): inspect stops reporting
          still-page, and a taste-skill-ignore still-page line hides it in lint — check ambient-motion
          there by reading the code; no other id measures it
  inspect.mjs --allow still-page
  lint.mjs has no --allow: mark each line it flags with one of these ids with
    a taste-skill-ignore comment citing design/rulings.tsv and the rule_id
```

## Goods the build must carry (Incremental Betterment; `app/page.tsx` header lists them)

Server-rendered real shops in the first HTML (#507/#544) through `NearYouRow` and its three
Open/Paused/Closed states; JSON-LD via `shopListStructuredData`; the `HeroSearch` form to `/shop?q=`;
crawlable cuisine links; the marketing width tier (1280, phase 35); the wordmark linking to `/` (D6);
the aria-label "Dishes cooking near you" on the scroll region (selector at
`marketing-dish-scroller.spec.ts:19`); no client directive on the page (the #89 CSP nonce cascade,
guarded by `app/__tests__/landing.test.tsx`). `e2e/marketing-motion.spec.ts` asserts the GSAP
`data-hero-*` hooks: under the motion rulings that spec changes with the page, in the same PR, and the
change is recorded here.

## Steps 6–7: built and gated (2026-11-01); steps 8–9 open

Built on `design/landing-run-20261101` by the oaas-frontend agent; every figure below was produced by a
command run in this session. Stack-free throughout: `next start -p 3201` (after) and `-p 3202` (the
before tree, `git archive 55db5872`), both against a fixture API on `127.0.0.1:19090` that serves
the three `DemoDataSeeder` shops, so the real kitchen row renders without Compose. (The step-7
before inspect ran before the fixture existed, so the old page was measured without its row.)

### What was built (HANDOFF items 1–6, D12, the honest copy)

Tokens in `frontend/tailwind.config.ts` (`stone`, `stone-deep`, `oxblood-deep` = `#1C0405`, replacing
an unused violet, `amber` `#F0A322`, `ink`, `chalk`, status and KDS colours). The page is
`frontend/app/page.tsx` plus `frontend/components/marketing/landing-pass.tsx`; `NearYouRow`, `ShopCard`,
`DishScroller` and `HeroSearch` are restyled in place with their logic unchanged. The seven pictures sit in
`frontend/public/marketing/landing/` with `credits.json` (WebP derivatives, recorded); the credit line in
the close is generated from that file. Copy is the deck's, except the R6 line: "Take orders and manage
your kitchen. A person reviews every application." (door and operator section), and the metadata says
"Apply to take your kitchen online with an assisted setup". No "go live in a day" anywhere (unit test).

Removed under the rulings: the radial wash, the amber→orange door, the emoji tiles and chips (D9); the
DishScroller's gradient edge fade (gradient ban; the cut ticket at the phone edge is the affordance now,
and the arrows are flat); `HeroScene` (GSAP) and `Reveal` (scroll-reveal), deleted with their tests
because nothing else used them. Kept: no client directive on `page.tsx`, server-fetched shops, JSON-LD,
the Marketing tier on every band (`Band`), the shared header and footer, the byte-identical
"Dishes cooking near you" region.

Decisions forced by records, not taken here:
- **The kitchen ticket** shows the name, cuisine, the server's state and the delivery terms the old card
  already showed (kept as an existing good). No logo (D12).
- **The close links only pages that exist.** The deck named Privacy · Terms · Allergen policy · Data
  request; `/terms` and `/allergen-policy` do not exist and `/data-request` has no page (only
  `confirm/` and `download/`), so the close links "Privacy notice" and "Legal & company information"
  under their canonical labels (R6).
- **The h1 and #544.** The owner-ruled h1 "Order from kitchens near you" is a heading containing "near
  you"; the #544 guards forbade any such heading without a coordinate. The deck applied #544 to the row
  (its heading is "Kitchens on J'Toye") and set the title as the offer, so the guards now exclude the
  page title only, each with a control that the title is still there (`landing.test.tsx`,
  `storefront-ssr-seo`, `near-you-row`, `landing-webperf`). **Owner to confirm** (see Open).

### Phone first (HANDOFF "Mobile first", criteria 1–8; rubric R7)

New `frontend/e2e/landing-phone-first.spec.ts` (17 tests). Each assertion was shown failing: against the
before build, or (for checks the old page already met) against an injected broken input.

| criterion | measure (after) | before |
|---|---|---|
| 2 first screen 390×844 and 360×780: h1, sentence, field, button with no scroll; field ≥ 48px; cuisine links ≥ 44px, 8px row gap | all hold; field 56px; button top in the lower half (585 of 844) | fails (no field labelled "Postcode or dish") |
| 2 keyboard | `inputmode` and `enterkeyhint` "search", autocomplete off (it also takes a dish) | absent |
| 3 hover only under `(hover: hover)` on a touch device; press feedback on controls | 0 ungated hover rules; `active:` on every control | n/a |
| 4 throttled phone (Fast 3G, 4× CPU, 390, 3 runs) | LCP 1076–1096 ms on the plate `img`; CLS 0.0018; slowest interaction 32 ms | LCP 956–964 ms on a `p`; CLS 0.0692; 32 ms |
| 5 next/image, phone files ≤ 800px, first screen ≤ 500 KB | every file ≤ 750px on iPhone 14 (3×) and Pixel 7 (2.625×); 433 KB | 459 KB |
| 6 no sideways scroll at 360/390/414/768/1024 | `scrollWidth === clientWidth` at all five | also none (fail arm: a 420px element → 420 vs 360) |
| 7 motion | the plate only; none under reduced motion | GSAP scene |
| 8 thumb reach | the search button sits in the lower half; no sticky bar needed | — |

Criterion 5's trade, recorded: next/image picks the smallest candidate ≥ sizes × DPR from 640, 750,
828…, so a ≤ 800px file needs sizes × DPR ≤ 750. Pictures narrow on the phone get their true width; the
four that run the full phone width (fifth strip card, proof picture, product screens) declare 250px
there, i.e. a 750px file shown ~340–360px wide on a 3× phone (~2.1× density). LCP rose ~120 ms because the
LCP element is now a 750px picture rather than a paragraph; it stays well inside 2.5 s on this profile
and inside the repo's declared budget (`e2e/perf-budgets.ts`, 8 s). Criterion 9 belongs to step 8;
criterion 10: at 768 the page follows the sketch, whose two-column layout starts at 900px.

Phone finding on shared chrome: on a FIRST visit at 360×780 the cookie notice (top 598px) overlaps the
search (566–622px) until it is acknowledged; at 390×844 it clears it (notice from 662px, search ends
641px).

### Motion, read from the code (still-page is allowed and SHARED with ambient-motion; no tool measures it)

Every motion-capable token in `app/page.tsx` and the five landing components was listed and read.
One load moment: the plate (`motion-safe:animate-plate-settle`, 0.9 s, `rotate`/`translate` only,
nothing under reduced motion). Everything else is feedback on a control: hover and focus colour
transitions (gated to hover-capable pointers), press scale or opacity, the scroll arrows' nudge (instant
under reduced motion) and the location spinner (only while locating). No entrance, no scroll-linked
motion, no ambient loop. **Finding:** the plate's first keyframe animated `transform`, which composes
with Tailwind v4's `rotate` property, so the plate would have settled at −12°; the keyframe animates
`rotate`/`translate` and the e2e asserts the resting −6°. **Outside the page:** the shared cookie notice
(`bottom-notice-shell.tsx`) has its own 200 ms framer-motion entrance, reduced-motion aware via
`MotionConfig`, on every public route; it is shared chrome, not this page's moment, and the motion
e2e is scoped to `<main>` for that reason.

### Gate (step 7)

`rule-check.sh allow --scope page` → `inspect.mjs --allow still-page` (copied above, step 5).

| | before (`55db5872`) | after (`2a79b4cb`) |
|---|---|---|
| lint, HANDOFF glob (`page.tsx` + `components/marketing/*.tsx`) | 90 FAIL / 11 WARN: emoji, gradient, shadow, hairline, numbering | 61 FAIL / 8 WARN, none in a landing file: `business-model-guide`, `competitive-teardown`, `operator-pitch` (other routes) |
| lint, the landing's own files (`page.tsx`, `landing-pass`, `near-you-row`, `dish-scroller`, `shop-card`, `hero-search`, + `hero-scene`, `reveal` before) | 29 FAIL / 4 WARN | **0 FAIL** / 1 WARN (em-dash, comments included) |
| inspect `--film --allow still-page` | 12 FAIL / 5 WARN: headline-lines ×2, eyebrow, micro-label, emoji, gradient, shadow, hairline, body-size, few-pictures, small-text, focal-point | **3 FAIL** / 8 WARN |

The three remaining FAILs are not permitted by any `rulings.tsv` row, and all three are shared chrome
rendered on every public route, verified in the DOM:
- `hairline`: `public-header.tsx:95` (`border-b` on the sticky header) and `public-footer.tsx:292,310`;
- `micro-label`: the footer column headings `public-footer.tsx:160,214,262` (`text-xs uppercase
  tracking-[0.14em]`);
- `covered-text` @768/390: the cookie notice over the showcase heading at one scroll position; with the
  notice acknowledged the heading is uncovered at every probed point.
Lint marks two lines `taste-skill-ignore hairline`: both inside the `data-ui` kitchen-display fragment,
which taste-skill exempts but its line-based lint cannot see.

WARNs, defended: `no-footage` (no honest clip, as in the sketch); `side-scroll` @390 (the kitchen row is
a scroller by contract, `marketing-dish-scroller.spec.ts`); `card-rows` @768/390 (the tickets are the
identity's device); `size-count` 12 (the landing's own are 15/16/17/20/22/26/58/96/208, nine, as the
sketch REPORT; 12 and 14 are chrome; body inherits the app's 16px, HANDOFF "keep 16px+");
`measure`, `small-text`, `outlined-box`, `palette-cream` (the shell's cream ground and the footer's cream
text) are present at the same values on the before page and come from the shared shell.

Screenshots: `inspect-before/` and `inspect/` in this folder (desktop, tablet, phone, scroll film).

### Tests (HEAD `95ce5fa4`; its app code is what was built and gated at `2a79b4cb`)

- `npm run build` rc 0; `npx tsc --noEmit` rc 0; `npm run lint` rc 0 (0 errors, 26 warnings, the same
  26 as the before tree, none in a touched file).
- `npx jest` 3353 / 3353 passed in 282 suites (rc 0). `landing.test.tsx` 23 / 23, `components/marketing` 106 / 106 in 8 suites.
  All 23 changed or new unit assertions fail on the before tree (`jest` there: 23 failed, 24 passed).
- Playwright, 11 specs, both projects, stack-free (`next start -p 3201` + fixture), the DB-writing block
  excluded: **149 passed, 21 failed, 9 skipped** (rc 1) on `95ce5fa4`. Every test of `/` passes:
  `landing-phone-first` 17/17, `marketing-motion` 9/9, `marketing-dish-scroller` 5/5, `near-you-row` 10/10,
  `landing-webperf` 9/9 (throttled 375px LCP 1100 ms, CLS 0.0020; 1440px LCP 2388 ms, CLS 0.0051; client JS
  793,705 bytes), `layout-width-contract` 12 + 9 skipped, `cookie-notice-layout` 2/2,
  `mobile-instrument-contract` 4/4. The 21 failures (`public-a11y` 4, `public-layout` 6,
  `storefront-ssr-seo` 11) all open `/shop` or `/shop/{slug}`, which the fixture cannot serve and this
  branch did not change (`git diff 55db5872 -- frontend/app/shop` is empty), or read robots.txt /
  sitemap.xml, which need a configured public origin; the policy-reachability test passes its landing
  half and fails at its storefront half. They need the Compose stack, which this session did not use.
- Fail arms for the changed e2e assertions, against the before build on 3202: the motion pair, the #544
  title controls (served HTML and browser), near-you-row's no-coordinate test, the D12 row test, the
  band count (found 4, expected 9–10), the renamed cookie-notice anchor and the phone-first spec all
  FAILED there; the overflow, aspect and image instruments were shown failing on injected broken input.
- Page defects the specs caught: axe read the desktop hero copy as ink on oxblood because a slab ELEMENT
  sat behind it (axe ignores clip-path), so the slab is a `::before`; the strip's 16/9 phone box grew
  with its picture; and after the next/image switch the desktop plate collapsed to 0px (a grid item
  with `ml-auto` and an absolutely positioned picture), caught by the reduced-motion e2e and by
  inspect's "hero media none", fixed in `2a79b4cb` with a size assertion shown failing first.
- Instrument defects found and fixed (each re-proven both ways): the motion probe counted React's hidden
  streaming copy; `public-layout`'s aspect check measured the rotated hull (a 340×191 box read 344×198)
  and its image check read lazy, unrequested pictures as broken; the phone file-width probe read the
  density-corrected `naturalWidth` and passed over 960px files; the interaction probe counted a
  page-load `pointerover` (1040 ms) instead of the tap (32 ms).
- `scripts/docs-freshness.sh --write` (jest 3353/282, Playwright 204/52, total 6872) and the matching
  prose in README.md, CLAUDE.md and AGENTS.md; `check-doc-metrics.sh` rc 1 before the prose update,
  rc 0 after; `docs-freshness.sh` rc 0.
- **Not run against Compose.** One run of `near-you-row.spec.ts` (the first full run) included its
  "published shop with no coordinates" block, whose `beforeAll` runs `docker exec jtoye-postgres psql`
  to NULL and then restore `peckham-jollof-co`'s coordinates. Its `afterAll` restore assertions reported
  no failure. Every later run excluded that block (`--grep-invert "disclosed, not dropped"`, confirmed
  with `--list`).

### Open (owner)

1. The gate is not clean: three FAILs in the shared header, footer and cookie notice. Changing them
   changes every public route, so that needs its own run or ruling. Recommendation: a chrome pass (the
   header rule → a tone step; the footer headings → sentence case at body size), and a ruling on
   whether the consent notice counts as a permitted overlay for `covered-text`.
2. Confirm the h1 / #544 scoping above. If the title should not say "near you", the h1 changes and the
   four guards revert to "every heading".
3. Confirm the phone image trade (≈2.1× on 3× phones for the four full-width pictures).
4. Sample content still unconfirmed (from the sketch): the strip and proof captions, the list line "Open,
   paused or closed, as the kitchen sets it.", the kitchen-display ticket's lines, the door sub-lines.
5. The deck's Terms, Allergen policy and Data request links have no pages.

Step 8 (separate scorer, then blind A/B on a phone-width capture first) and step 9 are not started.

## Rebuild (D13): Market day, steps 6–7 again (2026-11-01)

The Pass lost the blind A/B (verdict.md; rubric R8–R10) and Sanmi re-ruled the identity (identity.md,
D13). Rebuilt on `design/landing-run-20261101` by the oaas-frontend agent, structure kept, every
decision taken from the records. Every figure below came from a command run in this session,
stack-free: `next start -p 3201` against the fixture API on `127.0.0.1:19090` (the three
`DemoDataSeeder` shops, all with `bannerUrl: null`); the previous build (`956848fa`) on 3202 for the
fail arms. Compose and its database were not touched; the DB-writing near-you-row block was excluded
from every run (`--grep-invert "disclosed, not dropped"`, confirmed with `--list`).

### What changed

| item | done |
|---|---|
| 1 palette | D11 tokens (stone, amber, ink, chalk, oxblood-deep) replaced by `step` #F4F1EC, `ink` #1E1A17, `tomato` #C7361F, `palm` #FFC531, `leaf` #1F7A4C; white ground; `status.open` = leaf. No oxblood or dark field in the page or its footer (unit test reads every section, band and the footer ground). |
| 2 first screen | white hero; h1; sentence; the plate breaking the top edge of a full-bleed tomato band; the real HeroSearch on the band; doors "Order food" (/shop, palm) and "Run your kitchen" (/for-operators, with the R6 line); cuisine links after. Measured at 390×844: search button top 532, doors end 772; at 360×780: doors end 772. The plate stays the LCP element. |
| 3 food | 7 dishes added from Wikimedia Commons (jollof and dodo, egusi with pounded yam, fish pepper soup, suya with pepper sauce, plantain with moin moin, puff-puff, Jamaican rice and peas); originals and credits in `assets/`, 800px WebP derivatives in `frontend/public/marketing/landing/` with `credits.json` (9 entries = 9 files, unit-tested). The 5 street-front/vendor pictures left the page and the folder. Cards (D13): `bannerUrl` via SafeImage when present, else a credited cuisine photo captioned "<Cuisine> — example photo" (`components/marketing/cuisine-photos.ts`: the first tag that names a known cuisine, spelled as the kitchen wrote it; none if no tag matches; slug order within a cuisine so two kitchens get two dishes and re-ordering by distance keeps each picture). Each step ticket carries a dish; the proof picture is fish pepper soup. |
| 4 scorer weaknesses | one footer: the shared PublicFooter in a new `tone="tomato"` with `lead` (the name at full width) and `credits` (collapsed "Picture credits", 15px); the designed close, the closing door band and the tilted strip are removed. Other routes pass nothing and render the oxblood footer unchanged (`public-footer-tone.test.tsx`; `check-geo-attribution.sh` rc 0). Product screens below 640px are crops of the region each proves, shown near 1:1 (`*-phone.webp`, `getImageProps` art direction). Tablet hero: the plate sits beside the sentence from 640px; no empty half. |
| 5 kept | one motion moment, no client directive on page.tsx, JSON-LD, the marketing tier on every band, wordmark to /, the "Dishes cooking near you" region, the #544 h1 scoping. Step two no longer says "card, or cash": "Delivery or collection, paid the way the kitchen takes it; allergens on every item." |

Choice made under item 4, recorded: the shared footer is oxblood, and identity.md says "no dark ground
anywhere" and "jollof tomato … the footer field", so keeping it unchanged on `/` would contradict D13,
and a second footer is what item 4 removes. The tone variant is the smallest change that satisfies
both: it touches `/` only.

### Gate (step 7)

`rule-check.sh allow --scope page` → `inspect.mjs --allow still-page` (unchanged from step 5).

- **lint**, the landing's own files (`page.tsx`, `landing-pass`, `near-you-row`, `dish-scroller`,
  `shop-card`, `hero-search`, `cuisine-photos`): **0 FAIL / 1 WARN** (`em-dash`, 12 including comments;
  the caption "<Cuisine> — example photo" is D13's wording). The touched shared chrome
  (`public-footer`, `public-shell`): 2 FAIL `hairline` (`border-t`, the same 2 as at `956848fa`) /
  2 WARN (`h-screen`, `em-dash`).
- **inspect** `--film --allow still-page` into `inspect-2/`, two rounds.
  Round 1: 6 FAIL / 6 WARN. Two were this build's: `small-text` (29 blocks under 13px: the collapsed
  credits at 12px, which Chromium lays out inside a closed `<details>`) and `body-size`. Fixed in
  `259e2973` (credits 15px; OGL lines 13px in the tomato tone only; the landing's running text at
  the identity's 17px).
  Round 2 (final): **5 FAIL / 7 WARN**:
  - `micro-label`, `hairline`: the shared header and footer (step-8 ruling: separate PR).
  - `covered-text` @390 ("What you can trust", "Run your food business", "J’Toye"): the cookie
    notice. Probed with and without the acknowledgement: acknowledged, nothing but the sticky header
    passes over a heading.
  - `body-size` 14px: the meter takes the size with the most characters in blocks of 60+ characters.
    Unacknowledged: 14px 292 (the notice's own copy and the footer's), 17px 225; acknowledged: 17px
    225, 13px 220 (licence lines), 14px 171. The landing's own body is 17px.
  - `palette-claude`: **this build's, and unsettled.** The meter classes #F4F1EC as cream (hue 36°,
    saturation 0.27 at lightness 0.94) and #C7361F as terracotta; both are D13's ruled values. The
    catalogue rule is `assistant-palette` (frontend-design avoid, taste-skill ban): no conflict, so no
    ruling exists to permit it. Open item 1.
  - WARN: `outlined-box` (the cookie notice), `size-count` 14 (12/13/14 are chrome), `no-footage`
    (no honest clip), `measure` ~187 ch (the footer's licence lines), `small-text` 2 (the footer's
    caps labels), `card-rows` @768 (the tickets are the identity's device), `side-scroll` @390 (the
    kitchen row is a scroller by contract).
- The gate was shown to fail on this project at the first build (`inspect-before/`, 12 FAIL).

### Motion, read from the code (still-page allowed, SHARED with ambient-motion)

Every motion token in the landing's files was listed (`animate-`, `transition-`, `duration-`,
`motion-safe:`, `useEffect`, `scrollBy`) and read. One load moment: the plate
(`motion-safe:animate-plate-settle`, unchanged; the `landing-pass.tsx` and second `page.tsx` hits
are comments). Everything else is feedback on a control (hover, press, focus; the doors and the
footer links added here are the same kind), the scroller's arrow `scrollBy` (smooth only without
reduced motion) and the location spinner while locating. No second moment. The cookie notice's
framer-motion entrance is shared chrome, as before. emil-design-eng was not loaded: the settle's
timing did not change.

### Tests (final tree `259e2973` + e2e `test(e2e)` commit)

- `npm run build` rc 0 (fixture origin baked as before, `NEXT_PUBLIC_API_URL=http://127.0.0.1:19090`;
  a first build with an `/api/v1` path broke the located and order-mock tests, 35 failures, because
  a CSP source with a path matches that path only; rebuilt). `npx tsc --noEmit` rc 0. `npm run lint`
  rc 0 (0 errors, 26 warnings, none in a touched file).
- `npx jest` **3366 / 3366 in 284 suites** (rc 0). `landing.test.tsx` 26/26, `components/marketing`
  114 in 9 suites, `components/public` 94 in 9 suites.
- Fail arms (unit): against `956848fa`, 12 changed or new assertions fail and the new
  `cuisine-photos` suite cannot load; three that the old tree already met were failed on injected
  breaks in a scratch copy (an uncredited file in the folder; the default footer tone flipped; a bare
  logo on the card). That last arm caught a blind test: an `alt=""` logo has no img role, so the
  "no picture" test now counts `<img>` elements.
- Playwright, 11 specs, both projects: **149 passed, 21 failed, 9 skipped** (rc 1). The 21 are the
  same 21 as the previous build (storefront, robots.txt, sitemap.xml; they need the Compose stack):
  `diff` of the two failure lists is empty. Every test of `/` passes: `landing-phone-first` 17,
  `marketing-motion` 9, `marketing-dish-scroller` 5, `near-you-row` 10, `landing-webperf` 9,
  `layout-width-contract` 12 + 9 skipped, `cookie-notice-layout` 2, `mobile-instrument-contract` 4.
  Throttled phone: LCP 1048 ms on the plate, CLS 0.0015, interaction ≤ 24 ms, first screen 448,271 B;
  desktop 1440 LCP 2236 ms, CLS 0.0085.
- Fail arms (e2e) against `956848fa` on 3202: the doors-in-first-screen tests at 390 and 360 and
  the door press test (timed out: no "Start here" doors), the D13 row-picture mechanism (both
  projects: "the kitchen row must carry pictures"), the `/` band count at 1440/1920/2560 — all failed.
- `docs-freshness.sh --write` (jest 3366/284, total 6885); `check-doc-metrics.sh` failed on 10 prose
  claims until README.md, CLAUDE.md and AGENTS.md were updated, then rc 0.

### Open (owner)

1. **`palette-claude`.** D13's warm grey and tomato trip taste-skill's cream-plus-terracotta tell.
   Recommendation: keep tomato and move `step` to a lower-chroma warm grey (e.g. #F2F1EF, saturation
   0.10: under the meter's 0.16) rather than rule an exception to a tell both skills flag; that is
   an identity change, so it is yours.
2. **First visit on a phone:** the shared cookie notice, a dark oxblood panel, covers the lower door
   ("Run your kitchen") at 390×844 until "Got it" (the doors pass the first-screen test once it is
   acknowledged). For the shared-chrome PR.
3. Copy to confirm: step two's "paid the way the kitchen takes it"; the doors' navigation name
   "Start here"; "Order food" carries no sub-line (only the operator line was specified); the
   caption "Fish pepper soup".
4. The footer shows the name twice (the full-width lead and the shared footer's own small wordmark).
   For the shared-chrome PR.

Step 8 (separate scorer, blind A/B, phone first) for this rebuild is not started.
