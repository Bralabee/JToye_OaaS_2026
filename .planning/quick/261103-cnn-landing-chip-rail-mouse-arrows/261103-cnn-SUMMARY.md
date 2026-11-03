---
quick_id: 261103-cnn
status: complete
---
# Summary: 261103-cnn

- `frontend/components/marketing/cuisine-rail.tsx` (new) + `frontend/app/page.tsx` wraps the chips in it.
- Jest `components/marketing/__tests__/cuisine-rail.test.tsx`: 5/5; break arm (canRight forced false) 3 failed / 2 passed; restored 5/5. eslint rc 0, tsc rc 0, taste-skill lint 0 FAIL.
- Chromium (next dev, this branch): 1024 overflow 117 -> next only; click -> scrollLeft 117, last chip inside (-4), prev only; prev -> 0. 1152 overflow 53, same. 1279/1280/1440 overflow 0, no arrows. 390 touch: overflow 211, no arrows.
- Fail arm (main's page.tsx on the same server): no arrow at 1024/1152.
- Reduced-motion parity main vs branch: rail 524-564 / 494-534 and door 836 / 784 / 759 / 759 at 1024 / 1152 / 1280 / 1440, identical.
- Records: D18 in design/decisions.md; HANDOFF item 4 closed; CHANGELOG entry.

## Review round 1 (PR #1108)

- Admissible 2, both reproduced on the running container and fixed: (1) chips widened 169px inside an unchanged rail showed no arrow (only the rail box was observed); (2) the right arrow covered 32px of a keyboard-focused "Vegan" at 1024 (Chrome does not scroll a partly visible focused chip).
- Fix: shared hook `use-scroll-edges.ts` (DishScroller + CuisineRail), children observed at border-box, resize + fonts.ready fallback, data attributes, arrows always mounted; fine pointer: scroll-px-10, snap-none, focus -> scrollIntoView nearest.
- After: widened rail shows its arrow (reverted: hides); keyboard overlap 0px on every chip at 1024 and 1152; arrows absent from 1279 up and on touch; rail/door positions identical (759 from 1280).
- Break arms: children not observed -> 1 red; no resize fallback -> 1 red; no onFocus -> 1 red. Jest full: 295 suites, 3511 tests green. docs metrics regenerated (7090).
