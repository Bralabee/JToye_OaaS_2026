# Decisions — brand and identity

Each entry: date, decision, source, status, and a **Review when:** trigger. Carried from the sketch
record (`.planning/sketches/MANIFEST.md`) and project memory on 2026-11-01 rather than re-decided.
Status is one of KEEP, LIFTED, SUPERSEDED, or OPEN (waiting for Sanmi at design-run step 2).
D3, D4 and D7 were ruled by Sanmi at step 2 on 2026-11-01.

## D1 · 2026-04-19 · The editorial direction is rejected
"Warm Editorial" (Fraunces serif, warm paper, muted olive) shipped as PR #49 and was reverted in
PR #52: "looks more like a newspaper outlet than a food delivery business".
- **Status: KEEP the verdict, LIFTED the rule drawn from it.** The verdict stays as a rubric line
  (`rubric.md` R1). The blanket "sans-serif display only" constraint derived from it was lifted on
  2026-11-01: a serif is allowed if the page still reads as food commerce.
- **Review when:** a direction uses a display serif — check it against R1 before the owner sees it.

## D2 · 2026-07-12 · Sketch 001 "Market-Heat": orange / emerald / slate
- **Status: LIFTED 2026-11-01** (Sanmi: "Dont keep them"). Orange on cream reads as the assistant
  palette that taste-skill flags; the palette is chosen afresh at step 4.
- **Review when:** never automatically; a run may still choose orange on its merits.

## D3 · 2026-07-14 · Motion: "full award-site", GSAP on marketing routes (sketches 002–003)
Pinned builds, horizontal scrub, split-type, parallax on desktop; a Motion-class reveal as the
mandatory mobile and `prefers-reduced-motion` floor; GSAP dynamic-imported, 0 kb on app routes.
- **Status: SUPERSEDED 2026-11-01** (Sanmi, step 2): the `rulings.tsv` motion rulings win —
  content present at load, at most one deliberate motion moment. KEEP its engineering rules: GSAP
  only on marketing routes (0 kb on app routes), and a still floor for mobile and
  `prefers-reduced-motion`.
- **No tool measures the one-moment cap:** ruling `section-entrance` avoid lets inspect skip
  `still-page`, which also measured `ambient-motion` (`rule-check.sh allow` prints it as SHARED).
  Each run's `direction.md` names its one moment and records that the code was read for others.
- **Review when:** a dealt direction's one motion moment needs a scroll-driven scene.

## D4 · 2026-07-15 · Oxblood `#3A0B0D` + Work Sans, matched to jtoyedigital.co.uk (sketch 004, PR #232)
Oxblood as the brand anchor (header, footer, deep accents), amber as the appetite accent.
- **Status: LIFTED as a lock 2026-11-01** (Sanmi, step 2). Oxblood and Work Sans are one input at
  the identity step, not a requirement; the food product may have its own identity and nod to the
  parent. The 004-D landing built on them was judged "bland, rigid, unenticing" (D7).
- **Review when:** the identity step (4) of each run.

## D5 · 2026-07-15 · Emoji stand in for food photography
- **Status: LIFTED 2026-11-01.** A run chooses a real imagery source before it builds (`brief.md`).

## D6 · 2026-07-25 · The wordmark always links to `/` (PR #263)
A logo that lands somewhere different depending on the page reads as broken navigation.
- **Status: KEEP.** **Review when:** branded own-domain storefronts ship.

## D7 · 2026-08-31 · 004-D "bland, rigid, unenticing"; 005 all rejected; 006-A won; 007 parked
Sketch 005's four directions were all rejected the same day as re-skins of one skeleton. Sketch
006 asked for a structural break; 006-A "The Pass" won after a rebuild onto a 1280-capped 12-column
grid. 007 (The Pass as a full page) was PARKED: the shipped 004-D landing stays for now; do not
implement 006-A/007 without a fresh go-ahead.
- **Status: UNPARKED 2026-11-01** (Sanmi, step 2): this run is the go-ahead. The Pass enters
  step 3 as one candidate beside three script-dealt directions; start from 007 and its README's
  production notes. The 005 rejection stays as `rubric.md` R2.
- **Review when:** step 3 of the landing run.

## D8 · 2026-10-22 · `/shop` cards: one stretched link covering the whole card
Ruled on issue #1027 (option 1), shipped in PR #1053 on 2026-10-30.
- **Status: KEEP.** Matches the `card-hover` ruling: a clickable card is one control.
- **Review when:** the card component is redesigned.

## D9 · 2026-04 · Gradient banners and tiles as decoration
Orange-rose shop-card banners were restored by the PR #52 revert; since sketch 004 the live
gradients are: the `/shop` card banner amber→oxblood (`app/shop/shop-discovery-client.tsx`), and on
the landing (`app/page.tsx`) the radial warm wash behind the hero, the amber→orange operator door
and the dish tiles.
- **Status: SUPERSEDED 2026-11-01** by `rulings.tsv` `gradient-decoration` ban, page and app. A run
  that touches one of these surfaces replaces its gradient; new work adds none, whatever the
  existing code does.

## D10 · 2026-11-01 · Imagery: open-collection pictures now, the kitchens' own later
No food photography exists (brief). Sanmi chose, at design-run step 1 of the landing run, real and
credited pictures of the subject's world from the open collections (taste-skill `assets.mjs`;
commercial-use licences only; credit line in the footer; `design/runs/<run>/assets/credits.json`
records each source) over licensed stock, a shoot, or type-and-colour only.
- **Status: KEEP.** A found picture shows the world around a kitchen and is never presented as a
  kitchen's own dish, room or person.
- **Review when:** a tenant's own photos exist through the Phase 24 media pipeline; then they
  replace the found pictures on the surfaces that show that kitchen.

## D11 · 2026-11-01 · Identity approved as drafted from The Pass (`identity.md`)
Sanmi picked The Pass at step 3 of the landing run (A of four; `runs/2026-11-01-landing/direction.md`)
and approved the identity drafted from it at step 4 without change: stone ground, oxblood, amber as
the one action colour, Work Sans alone, a plain voice, the order ticket on the pass as the device,
the plate settling on load as the only motion. Oxblood and Work Sans are kept on their merits, not
as the D4 lock.
- **Status: KEEP.**
- **Review when:** the first tenant's own photographs arrive (D10), or a storefront branding run
  (branded own-domain storefronts, D6's trigger) needs the storefront to carry a kitchen's identity
  rather than J'Toye's.

## D12 · 2026-11-01 · Kitchen cards on the landing show the name, not the placeholder logo
The seeded logos (`public/brand/logo-*.png`) are placeholders with gradient fills and one reads
"BV": the only gradient a visitor would see, against `gradient-decoration` ban.
- **Status: KEEP** (Sanmi, landing run step 6). The landing's kitchen row sets the kitchen's name in
  type, with its cuisine and Open/Paused/Closed state; no logo image.
- **Review when:** the first vendor uploads a real logo — then the row shows uploaded logos and
  keeps the name for kitchens without one.

## D13 · 2026-11-01 · Identity re-ruled light and warm: "Market day"
The Pass build lost the blind A/B to the old landing (`runs/2026-11-01-landing/verdict.md`): too
dark and heavy, less clear and friendly, food fading after the hero (rubric R8–R10).
- **Status: KEEP** (Sanmi). The Pass's structure stays; the identity changes (`identity.md`): white
  ground, jollof-tomato brand, palm-oil yellow action, leaf for open and trust; no oxblood field, no
  dark ground. The first screen carries the search and two plain doors. Supersedes D11's palette.
- **Kitchen cards (amends D12):** the kitchen's own banner when it exists, otherwise a credited
  open-collection photo of its cuisine captioned as an example ("Jollof — example photo"), so
  nothing implies it is that kitchen's dish.
- **Review when:** the rebuild's blind A/B (the run's second look).

## D14 · 2026-11-01 · Landing elevate approved: oxblood + gold palette, the sketch built into the app
Sanmi approved the elevate sketch (`runs/2026-11-01-landing-elevate/sketch/after.html`, commit
`c82cc0e7`) and ruled the four open items in its README, verbatim choices:
1. **Build the elevate sketch** (`sketch/after.html` + `palette.md`) into the app. The palette in
   `palette.md` is **ADOPTED** and supersedes D13's "Market day" (`identity.md` rewritten: oxblood
   ramp, gold accent, warm-neutral ramp, Work Sans).
2. **Headline "Order from local kitchens. Or run yours." KEPT on 3 lines.** `design-run`'s catalogue
   (`rules/catalogue.tsv`) has no rule_id for taste-skill's `headline-lines` or `focal-point`, so no
   `rulings.tsv` row can carry it. It is recorded here instead: both are **accepted inspect FAILs**
   on `/`, by the owner's ruling, the same cause (the h1 in the hero's half-width column at 56px).
   A gate run on the landing reports them and the run's `direction.md`/README names them as ruled.
3. **Track-live copy ACCEPTED:** "See your order's status change as the kitchen confirms it,
   prepares it and gets it ready." (the platform has no courier tracking; `/track` polls status).
4. **Kitchen row edge: CARD PEEK on phones** instead of the gradient edge fade.
- **Status: KEEP** (Sanmi). Supersedes D13's palette; D13's kitchen-card rule (own banner, else a
  credited cuisine photo captioned "<Cuisine> — example photo") stands.
- **Review when:** the built page's blind A/B (design-run step 8), or the h1 copy is next revisited.
