---
phase: motion-D-gsap-marketing
plan: 01
subsystem: ui
tags: [gsap, scrolltrigger, framer-motion, next16, marketing, csp, react19, animation]

# Dependency graph
requires:
  - phase: motion-uplift Phases A–C (PR #220/#221)
    provides: framer-motion LazyMotion foundation (MotionProvider, lib/motion.ts vocabulary)
provides:
  - Bundled GSAP 3.15 + @gsap/react 2.1 as a second, purpose-scoped animation engine on two marketing routes only
  - lib/gsap.ts single-point plugin registration (browser-guarded) + lib/gsap-gate.ts pure jsdom-safe gate util
  - Reveal floor primitive (framer-motion) — mobile / reduced-motion degradation, desktop-inert
  - HeroScene enhancer for / (split-type, parallax heat-wash, scrubbed step-rail)
  - useOperatorScrollScene enhancer for /for-operators (pinned rail build-on-scrub, horizontal pilot rail, scrubbed count-up)
  - marketing-motion.spec.ts + extended csp-no-violations.spec.ts (/for-operators)
affects: [marketing, storefront-theme, motion-uplift, csp, docs-freshness]

# Tech tracking
tech-stack:
  added: [gsap@^3.15.0, "@gsap/react@^2.1.2"]
  patterns:
    - "Server page + 'use client' enhancer seam — RSC renders visible children, GSAP enhances via useGSAP({ scope })"
    - "Pure gate module (no gsap import) for jsdom-testable breakpoint/reduced-motion/split logic"
    - "Hidden 'from' states set ONLY inside gsap.matchMedia(DESKTOP_MOTION_QUERY) — no-FOUC across SSR"
    - "Reveal floor gated via useSyncExternalStore (React 19), not useEffect+setState"
    - "Horizontal-track layout gated by Tailwind motion-safe:md:* so mobile/reduced-motion wrap and stay reachable"

key-files:
  created:
    - frontend/lib/gsap.ts
    - frontend/lib/gsap-gate.ts
    - frontend/components/marketing/reveal.tsx
    - frontend/components/marketing/hero-scene.tsx
    - frontend/components/marketing/operator-scroll-scene.tsx
    - frontend/lib/__tests__/gsap-gate.test.ts
    - frontend/components/marketing/__tests__/reveal.test.tsx
    - frontend/components/marketing/__tests__/hero-scene.test.tsx
    - frontend/components/marketing/__tests__/operator-scroll-scene.test.tsx
    - frontend/e2e/marketing-motion.spec.ts
  modified:
    - frontend/app/page.tsx
    - frontend/components/marketing/operator-pitch.tsx
    - frontend/e2e/csp-no-violations.spec.ts
    - frontend/package.json
    - frontend/package-lock.json
    - docs/metrics.json

key-decisions:
  - "Guard gsap.registerPlugin behind a browser matchMedia check — ScrollTrigger.register() calls window.matchMedia on enable, which is undefined on the SSR Node pass and in jsdom; skipping registration there is safe because scenes are gated on the same canEnhance() condition"
  - "Reveal renders plain + visible unless the client resolves a NON-desktop-motion (floor) context; server snapshot is false so SSR markup never contains opacity:0 — genuine no-FOUC even with JS disabled"
  - "Terms count-up numbers wrapped in copy-preserving spans (renderCountable) — visible text is byte-identical, span keeps the real value so the gate-off/no-JS state shows the correct number"
  - "Pilot horizontal track uses Tailwind motion-safe:md:* for the flex-nowrap/overflow layout so reduced-motion/mobile keep the four steps wrapped and fully visible (no clipped content)"

patterns-established:
  - "Two animation engines coexist on disjoint DOM subtrees: framer-motion floor (mobile/reduced-motion) + GSAP scenes (desktop/motion), never on the same element"
  - "E2E determinism via DOM signals: .gsap-word, [data-motion-active='desktop'], .pin-spacer"

requirements-completed: [MOTION-D-HERO, MOTION-D-OPERATOR, MOTION-D-FLOOR, MOTION-D-CSP]

# Metrics
duration: ~55min
completed: 2026-07-14
---

# Phase motion-D Plan 01: GSAP Marketing Scroll Animation Summary

**Bundled GSAP 3.15 + ScrollTrigger as a desktop-only, non-reduced-motion scroll-choreography layer on `/` and `/for-operators`, over the existing framer-motion reveal floor — server-rendered visible, no-FOUC, CSP-clean, both pages still Server Components.**

## Performance

- **Duration:** ~55 min
- **Completed:** 2026-07-14
- **Tasks:** 4 of 4 (Task 1 human checkpoint pre-cleared by orchestrator)
- **Files created:** 10 · **Files modified:** 6

## Accomplishments
- Shipped the locked variant-B GSAP desktop choreography (sketch 002-B hero + 003-B operator story) as a progressive enhancement, with the mandated framer-motion variant-A floor for mobile / `prefers-reduced-motion`.
- Kept `app/page.tsx` and `app/for-operators/page.tsx` as Server Components (the #89 force-dynamic CSP-nonce cascade is intact); GSAP is imported only by the two enhancers, so it code-splits into the marketing route chunks and never leaks into app/storefront bundles.
- Held the hard no-FOUC contract: every hidden "from" state lives inside `gsap.matchMedia(DESKTOP_MOTION_QUERY)`; no opacity:0/hidden exists in CSS or server markup.
- All deterministic gates green: `npm run build`, `eslint .` (0 errors), full `npx jest` (282 passed), `scripts/docs-freshness.sh` (1300). The pre-existing `operator-pitch.test.tsx` and the app-wide `palette-discipline` gate stay green.

## Task Commits

Each task was committed atomically (no trailers, per user policy):

1. **Task 2: Install GSAP + plumbing (lib/gsap.ts, lib/gsap-gate.ts) + Reveal floor + unit tests** — `b46e3ed` (feat)
2. **Task 3: Landing / hero kinetics enhancer (hero-scene.tsx) + wire into app/page.tsx** — `4e0ee3e` (feat)
3. **Task 4: /for-operators pinned + horizontal enhancer + wire into operator-pitch.tsx** — `b0845e7` (feat)
4. **Task 5: Playwright scene/floor spec + /for-operators CSP spec extension + metrics** — `420f616` (test)

_Task 1 was a `checkpoint:human-verify` (gate="blocking-human") for GSAP package legitimacy; the orchestrator cleared it with live npm-registry evidence (official GreenSock org, no postinstall) before spawning this executor. Install resolved to gsap 3.15.0 / @gsap/react 2.1.2 with no postinstall scripts._

## Files Created/Modified

**Created**
- `frontend/lib/gsap-gate.ts` — pure, jsdom-safe gate: `DESKTOP_MOTION_QUERY`, `prefersDesktopMotion`, `canEnhance`, hand-split `splitWords` (textContent/createElement only, idempotent, preserves nested elements).
- `frontend/lib/gsap.ts` — the single place ScrollTrigger + useGSAP are registered (browser-guarded); re-exports `gsap`, `ScrollTrigger`, `useGSAP`.
- `frontend/components/marketing/reveal.tsx` — framer-motion mobile/reduced-motion floor; desktop-inert via `useSyncExternalStore`; children render plain+visible on server/pre-hydration/desktop-motion.
- `frontend/components/marketing/hero-scene.tsx` — `/` enhancer: split-type headline stagger, persona door deal-in, parallax heat-wash, scrubbed step-rail draw + step activation.
- `frontend/components/marketing/operator-scroll-scene.tsx` — `useOperatorScrollScene(scopeRef)`: pinned Service-rail build-on-scrub, horizontal pilot rail (function-based x/end + invalidateOnRefresh), scrubbed terms count-up, headline split.
- Unit tests: `lib/__tests__/gsap-gate.test.ts`, `components/marketing/__tests__/{reveal,hero-scene,operator-scroll-scene}.test.tsx`.
- `frontend/e2e/marketing-motion.spec.ts` — Playwright scene/floor proof (prod-build tool, not CI-wired).

**Modified**
- `frontend/app/page.tsx` — wraps hero + how-it-works + trust in `<HeroScene>`; presentational data hooks only; trust chips wrapped in `<Reveal>`. Stays a Server Component.
- `frontend/components/marketing/operator-pitch.tsx` — `rootRef` + `useOperatorScrollScene`; data hooks (`data-op-pin`, `data-op-headline`, `data-rail-item`, `data-pilot-track/step`, `data-op-terms`); pilot `<ol>` becomes a `motion-safe:md:*` horizontal track (list semantics + copy preserved); terms numbers wrapped in copy-preserving count-up spans. Fit-check untouched, zero hex added.
- `frontend/e2e/csp-no-violations.spec.ts` — added a `/for-operators` zero-violation case.
- `frontend/package.json` / `package-lock.json` — gsap + @gsap/react.
- `docs/metrics.json` — regenerated (jest 287 blocks / 45 files; playwright 33 blocks / 7 specs; total 1300).

## Metrics Delta
- `total_logical_invocations`: 1276 → **1300**
- `jest_blocks`: 268 → **287** (+19: 10 gate + 3 reveal + 2 hero-scene + 4 operator-scroll-scene)
- `jest_files`: 41 → **45** (+4 new test files)
- `playwright_blocks`: 28 → **33** (+4 marketing-motion + 1 csp)
- `playwright_specs`: 6 → **7**

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Browser-guarded GSAP plugin registration**
- **Found during:** Task 3 (hero-scene jest run)
- **Issue:** `ScrollTrigger.register()` calls `window.matchMedia` on enable; jsdom (and the SSR Node pass) leaves it undefined → `TypeError: _win.matchMedia is not a function` at `lib/gsap.ts` module load, crashing any test/SSR that imports an enhancer.
- **Fix:** Wrapped `gsap.registerPlugin(ScrollTrigger, useGSAP)` in `if (typeof window !== "undefined" && typeof window.matchMedia === "function")`. Safe because scenes are gated on the same `canEnhance()` condition, so registration is never needed where matchMedia is absent. Strictly better: also prevents any SSR crash.
- **Files modified:** `frontend/lib/gsap.ts`
- **Commit:** `4e0ee3e`

**2. [Rule 1 - Bug] Test `<a href="/shop">` tripped `no-html-link-for-pages`**
- **Found during:** Task 3 (eslint)
- **Issue:** hero-scene test used `<a href="/shop">` as a representative child; Next's lint errors on internal-page `<a>`.
- **Fix:** changed the test href to `#shop` (non-page).
- **Files modified:** `frontend/components/marketing/__tests__/hero-scene.test.tsx`
- **Commit:** `4e0ee3e`

**3. [Rule 1 - Bug] `#221` PR ref read as hex by the app-wide palette gate**
- **Found during:** Task 5 (full `npx jest` — `__tests__/palette-discipline.test.ts` scans `components/marketing` for `#[0-9a-fA-F]{3,8}`)
- **Issue:** a code comment in `reveal.tsx` referenced "PR #221"; `221` is valid hex → gate failed.
- **Fix:** reworded to "PR 221". Also reworded a `test()` literal in a `marketing-motion.spec.ts` comment that inflated the docs-freshness playwright count by one (kept the metric honest at 4 real blocks).
- **Files modified:** `frontend/components/marketing/reveal.tsx`, `frontend/e2e/marketing-motion.spec.ts`
- **Commit:** `420f616`

## Threat Surface
No new network endpoints, auth paths, or trust-boundary surface. `splitWords` uses `textContent`/`createElement` only (STRIDE T-motion-D-03 mitigated). GSAP bundled via npm, no CDN, no `unsafe-eval`/`unsafe-inline` added — CSP regression-guarded by the extended `csp-no-violations.spec.ts`. No `threat_flag` findings.

## Known Stubs
None. The count-up mechanism is real and wired; it animates `[data-count-to]` hooks added to the terms band (copy preserved). Because the real terms values are prose/ranges rather than sketch-style single proof numbers, only the leading integer of each value counts up (a subtle desktop flourish) — intentional, not a stub.

## Pending Human/Orchestrator Check (Task 5 `<human-check>`)
The live scroll behaviour was NOT run in this execution environment (no prod stack; the running Docker stack must not be touched per constraints). The deterministic gates (build/tsc typecheck of the specs, eslint, full jest, docs-freshness) are green. The following must be run by the orchestrator/developer against a PRODUCTION build before merge:

```
PLAYWRIGHT_BASE_URL=http://localhost:3100 \
  npx playwright test e2e/marketing-motion.spec.ts e2e/csp-no-violations.spec.ts
```

Expected (both projects): on `/` the headline splits and the heat-wash parallaxes; on `/for-operators` the Service-rail pins/builds and the pilot rail scrolls horizontally; 375px + reduced-motion show fully-visible static content with no pin/scrub/parallax; both routes report zero CSP violations. **The phase's live scroll behaviour is therefore NOT yet verified — only the deterministic gates are.**

## Self-Check: PASSED
- All 10 created files present on disk (verified).
- All 4 task commits present in git history (`b46e3ed`, `4e0ee3e`, `b0845e7`, `420f616`).
- gsap@^3.15.0 + @gsap/react@^2.1.2 in package.json.
- Full suite: `npm run build` ✓ · `eslint .` 0 errors ✓ · `npx jest` 282 passed ✓ · `docs-freshness.sh` OK (1300) ✓.
