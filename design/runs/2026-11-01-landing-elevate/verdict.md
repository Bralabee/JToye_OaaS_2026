# Landing elevate run 2026-11-01 — step 8 verdict

**Blind A/B (Sanmi, 2026-11-01): B won. B was the elevated build (`inspect-app-after/`); A was the
current landing (`inspect-app-before/`).** Order randomised; key revealed after the pick. Phone first.
The first build to beat the current page, after two redesigns lost (`../2026-11-01-landing/verdict.md`).

Separate scorer (`scores.md`, did not build it): new 6.0 (6.7 before the cap from 6 inspect FAILs),
current 5.3. "A faithful elevation, not a redesign."

## Open after the verdict (the scorer's weaknesses; next polish, owner to prioritise)
1. Phone first screen has no food at a size you can taste: only ~30px chip thumbnails; the four-tile
   photo cluster is hidden below desktop (R7, R10).
2. The same three photos repeat in two or three places (hero tile, door, kitchen cards).
3. Tablet: the operator door is a half-empty oxblood block; desktop first screen has no single lead.
4. Small grey text: 14px body, <13px captions and credits; uppercase footer labels (shared chrome).
5. Below the kitchens, no food: icon-in-circle steps, check-icon pills, a long footer.
6. The cookie notice covers the Order-food door on the phone's first screen (shared chrome, both versions).
Also from the build: a desktop LCP outlier (~3 s in 2 of 10 throttled runs, the GSAP headline split
after the font swap), 3x phones get 800px originals, and the shared header/footer/cookie FAILs
(separate PR, owner ruling 2026-11-01).
