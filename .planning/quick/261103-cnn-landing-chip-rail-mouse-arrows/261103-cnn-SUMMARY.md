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
