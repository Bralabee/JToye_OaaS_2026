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
