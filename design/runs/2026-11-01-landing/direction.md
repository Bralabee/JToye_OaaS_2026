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

## Steps 6–9: open

Build, gate (prove it can fail against the current landing first), look (separate scorer, then blind
A/B old vs new), learn.
