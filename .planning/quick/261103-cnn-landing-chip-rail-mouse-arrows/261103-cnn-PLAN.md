---
quick_id: 261103-cnn
slug: landing-chip-rail-mouse-arrows
date: 2026-11-03
---
# Landing cuisine chip rail: mouse-only arrows (D18)

Owner ruling 2026-11-03 (design-run, AskUserQuestion): arrows, mouse only, reversing the
2026-11-02 "peek, no change". The second #1097 leftover (stale identity.md ground table) was
verified already fixed by #1105 against `frontend/app/page.tsx`; no change.

1. Extract the rail into a client `CuisineRail` (scroll + ResizeObserver measure, arrows only
   when that side overflows, gated by `(hover:hover) and (pointer:fine)`, tabindex -1, named).
2. Jest cases with a break arm; Chromium measurement at 1024/1152/1279/1280/1440 and 390 touch,
   main as the fail arm; reduced-motion layout parity (rail and door positions).
3. Record D18 in `design/decisions.md`, close HANDOFF item 4, CHANGELOG entry.
