# HANDOFF — landing design-run, steps 6–9 (build, gate, look, learn)

Written 2026-11-01 by the session that ran steps 0–5. Resume in a FRESH session (the previous one
reached its context budget). Load the `design-run` skill first, then `taste-skill` at steps 6–8 and
`emil-design-eng` only for the plate's settle timing.

## State

- Branch `design/landing-run-20261101`, worktree `/home/sanmi/IdeaProjects/JToye_OaaS_2026-design-run`,
  cut from `origin/main` at `049ff261` (#1078, the project layer). Commits so far: `d84a51ee`
  (references, D10, dealt sheets, notes), `4742f326` (four sketches + `compare.html`), then this one
  (direction, identity, D11, MANIFEST 008, this file). Pushed to origin. No PR yet.
- Steps DONE: 0 brief, 1 references (7), 2 decisions, 3 pick = **The Pass** (A), 4 identity
  (`design/identity.md`, D11), 5 rule-check (none unresolved).
- Owner rulings this run: imagery D10 (open-collection now, kitchens' own later); candidate A; identity
  approved as drafted.
- Local Compose stack: NOT touched by this session; HANDOFF.md at the repo root says it is stale
  against main (`scripts/sync-runtime.sh` before any local E2E). Antigravity worktrees untouched.

## What to build (step 6)

Source of truth, in this order: `design/identity.md` → `design/runs/2026-11-01-landing/direction.md`
→ the picked sketch `design/runs/2026-11-01-landing/sketches/the-pass/index.html` and its `REPORT.md`
(the geometry, tokens and what 007 lost under the rulings) → `notes.md` (copy deck, verbatim) →
`design/rulings.tsv`.

Target: `frontend/app/page.tsx` and `frontend/components/marketing/*`. Keep the stack (Next 16,
Tailwind v4 tokens, Work Sans already app-wide). Expected work:

1. Tokens: the six identity colours as Tailwind/CSS tokens (stone, deeper stone, oxblood, oxblood-deep,
   amber, ink; `amber-ink` exists). Remove the radial wash, the amber→orange door gradient and the
   emoji tiles from `app/page.tsx` (D9 names them).
2. Hero: oxblood diagonal slab (clip-path), headline "Order from kitchens near you" over the plate's
   edge, the real `HeroSearch` form, the six cuisine links as plain `<a>`; the plate is the jollof
   picture (`design/runs/2026-11-01-landing/assets/jollof-rice-with-vegetable.jpg`, copy the used
   pictures into `frontend/public/marketing/landing/` and keep `credits.json` beside them; the credit
   line goes in the footer, the sketch footer has the exact markup).
3. The strip of picture cards breaking the slab edge, then the REAL kitchen row: `NearYouRow` with its
   three states and the byte-identical aria-label "Dishes cooking near you" (spec selector
   `e2e/marketing-dish-scroller.spec.ts:19`), server-fetched in `page.tsx` as today.
4. How-it-works as three perforated tickets (SVG circle tile, never a gradient); proof as three plain
   facts; showcase with the four real `public/marketing/operators/*.webp`; operator section with the
   kitchen-display ticket as a `data-ui` fragment; typographic doors; footer with the name at full
   width, company no. 16471464, legal links, the credit line.
5. Motion: ONE moment, the plate settling (`@media (prefers-reduced-motion: no-preference)`, CSS
   only). `components/marketing/hero-scene.tsx` (GSAP split-type, railfill, step activation,
   scroll-reveal `Reveal`) contradicts `section-entrance avoid` and the one-moment cap: remove the
   scene from the landing (keep GSAP out of app routes as before) and change
   `e2e/marketing-motion.spec.ts` in the same PR; say so in `direction.md`. `/for-operators` keeps its
   own entrance scene; it is not this run's surface.
6. Keep: no client directive on `page.tsx` (#89; `app/__tests__/landing.test.tsx` greps for it);
   JSON-LD; the marketing width tier (`WIDTH_TIER_CLASS.marketing`, 1280); the wordmark to `/`;
   `PublicShell`'s header and footer (restyle only through tokens unless the footer becomes the
   designed close, which the sketch has).

## Gate (step 7), then look (8), then learn (9)

```bash
# from the design-run worktree root
node ~/.claude/skills/taste-skill/scripts/lint.mjs frontend/app/page.tsx frontend/components/marketing/*.tsx
# dev server: cd frontend && npm run dev (port 3000; the stack must be up for real shops)
node ~/.claude/skills/taste-skill/scripts/inspect.mjs http://localhost:3000/ --out design/runs/2026-11-01-landing/inspect --film --allow still-page
```

- PROVE THE GATE CAN FAIL FIRST: run inspect against the CURRENT landing (main's `page.tsx`, before any
  edit) into `inspect-before/` and record its FAILs in `direction.md` (gradient wash, emoji, eyebrow,
  shadows are all there). A gate observed only passing is not evidence.
- `still-page` is allowed by the `section-entrance` ruling and is SHARED with `ambient-motion`: read the
  shipped code for any second motion moment and write the finding in `direction.md`.
- Tests: `cd frontend && npm run build && npx jest app/__tests__/landing.test.tsx components/marketing`
  and the Playwright specs that name the landing (`marketing-motion.spec.ts`, `marketing-dish-scroller.spec.ts`,
  public-surfaces e2e). `scripts/docs-freshness.sh --write` if test counts move; the changelog
  contract needs an entry for the PR.
- Step 8: a SEPARATE agent (not the builder) scores `inspect/` screenshots against
  `~/.claude/skills/taste-skill/references/critique.md` and `design/rubric.md`, writes
  `design/runs/2026-11-01-landing/scores.md`; then show Sanmi old vs new unlabelled (randomise the
  order), record the verdict.
- Step 9: every overrule becomes a `rubric.md` line, a `decisions.md` entry or a `rulings.tsv` row.
- Then PR (`design/landing-run-20261101` → main), D3 review series from the repo checkout
  (`/code-review <PR> --comment`, then `/review-verify`), merge, and `scripts/check-runtime-freshness.sh`
  after the local rebuild.

## Known rough edges from the sketch (The Pass REPORT.md)

- The kitchen logos (`public/brand/logo-*.png`) are gradient fills inside the image and one reads
  "BV": honest to what exists, but the one gradient a visitor will see. Decide with Sanmi whether the
  row shows the logo or the name alone until vendors upload real ones.
- Body text at 17px: the app's base is 16px; keep 16px+ either way.
- `inspect.mjs` serves from cwd: run it from the worktree root or relative asset paths 404.

## Mobile first — owner's standing requirement (2026-11-01, added after the step-4 ruling)

Sanmi: "majority of users will interact on the web via phone tablet and similar devices ahead of
laptops/desktops … I just need to know that there's been adequate and robust consideration for
mobile friendly app like solution." What the record shows so far, honestly:

- DONE: the brief says mobile-first; every sketch has its own phone composition from its sheet and
  was captured at 390 and 768; inspect measured 390 (no overflow, headlines two lines or fewer);
  The Pass puts headline, plate, supporting line and the search inside the first 390×844 screen,
  with 44–56px tall controls.
- NOT DONE: the pick was recommended from the 1280 first screens; The Pass's hover styles are NOT
  gated to hover-capable pointers (0 `hover:hover` / `pointer:fine` queries), so they latch on tap;
  its media queries are desktop-first (`max-width`); nothing was measured on a throttled mobile
  profile or a real touch device.

Build acceptance, phone first (each must be shown FAILING once before it is trusted):

1. Build the 390 composition FIRST and keep it the primary artifact; desktop is the enlargement.
   Tailwind mobile-first (`sm:`/`lg:` up), never `max-width` overrides.
2. First screen at 390×844 (and 360×780): headline, one sentence, the search field and its button
   all visible without scrolling; the field's tap target ≥ 48px tall, `inputmode="search"`,
   `enterkeyhint="search"`, `autocomplete="postal-code"` only if the field is postcode-only (it is
   not; leave it off). The six cuisine links ≥ 44px tall with 8px gaps.
3. Hover feedback only under `@media (hover: hover) and (pointer: fine)` (the written-out gate the
   current page uses; Tailwind v4 `hover:` is already gated by default, verify in the built CSS).
   Press feedback (`active:`) on every control. "A viewport is not a device": check
   `matchMedia('(hover: hover)')` in the Playwright mobile project (`hasTouch: true`,
   `isMobile: true`), not a narrow desktop window.
4. Core Web Vitals on a throttled mobile profile (Lighthouse mobile, or Playwright with CPU 4×
   slowdown and "Slow 4G"): LCP ≤ 2.5 s with the plate as the LCP element served through
   `next/image` with `priority`, `sizes` and explicit width/height; CLS < 0.1 (every picture and
   the kitchen row reserve their box); INP < 200 ms. Record the numbers in `direction.md` beside
   the before numbers from the current landing. The repo's web-perf contract (CLAUDE.md) asks
   for a config-declared budget: use the existing one if present, else add one and cite it.
5. Images: WebP/AVIF through `next/image`, `sizes` tuned so a 390px phone downloads ≤ 800px wide
   files; total first-screen transfer ≤ 500 KB on the phone project; lazy-load everything below
   the strip.
6. No horizontal scroll at 360, 390, 414, 768, 1024 (assert `scrollWidth === clientWidth`);
   the diagonal slab and the plate must not create it.
7. Motion: the plate's settle only; `prefers-reduced-motion` still; no backdrop blur, no filters
   on scroll (glass ruling: low-end Android).
8. Thumb reach: the primary action on the first screen sits in the lower half of the phone
   viewport; consider a sticky bottom "Find kitchens" bar on the phone only if the field scrolls
   out, and only without covering content (the storefront already publishes
   `--jt-bottom-chrome`; reuse the pattern).
9. The step-8 blind A/B is shown to Sanmi on a PHONE-WIDTH capture first, desktop second.
10. Tablet (768) is its own look, not a stretched phone: two-column where the sheet says so.

Playwright projects for the gate: `Mobile Safari` (iPhone 14) and `Pixel 7` device descriptors
plus desktop Chrome; the public-surfaces e2e already runs a mobile project (check
`frontend/playwright.config.ts` and reuse its names).
