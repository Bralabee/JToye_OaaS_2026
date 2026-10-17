# Phase 39 Plan 07: Tailwind CSS 3.4.1 -> 4.3.3 (@tailwindcss/postcss) Migration Summary

**Completed:** 2026-10-16
**Plan:** `39-07-PLAN.md` (Wave 4, Track `39-D` Part 2 — `LANE=cheap`)

## What Was Done

1. **Upgraded `tailwindcss` from `^3.4.1` to `^4.3.3` + `@tailwindcss/postcss: "^4.3.3"` (`frontend/package.json` & `frontend/postcss.config.mjs`):**
   - Replaced `tailwindcss: {}` in `frontend/postcss.config.mjs` with `"@tailwindcss/postcss": {}`.
   - Replaced `@tailwind base; @tailwind components; @tailwind utilities;` in `frontend/app/globals.css` with `@import "tailwindcss" source(none);` + `@config "../tailwind.config.ts";` so Tailwind v4 scans only the paths declared in `tailwind.config.ts`.
   - Added explicit v4 compatibility declarations in `@layer base` (`*, ::after, ::before, ::backdrop, ::file-selector-button { @apply border-border; }` and `button:not(:disabled), [role="button"]:not(:disabled) { cursor: pointer; }`) so default borders and button cursors retain exact v3 parity.

2. **Enforced `.container` Retirement in Tailwind v4 & Closed Phase `37-58` Test-Content Exclusion (`frontend/tailwind.config.ts` & `scripts/check-layout-width-contract.sh`):**
   - Discovered and proved that Tailwind v4 ignores `corePlugins: { container: false }` in `tailwind.config.ts` and requires `blocklist: ["container"]` (which adds `"container"` to `designSystem.invalidCandidates`). Kept both `blocklist: ["container"]` and `corePlugins: { container: false }` and updated `scripts/check-layout-width-contract.sh` (`G-3b`) and `frontend/lib/__tests__/layout-widths-css.test.ts` to assert both halves.
   - Excluded `"!**/__tests__/**"` and `"!**/*.{test,spec}.{js,ts,jsx,tsx}"` in `tailwind.config.ts` `content` globs (closing the Phase `37-58` deferred finding in `deferred-items.md` lines 900–913).

3. **Adapted CSS & Contrast Test Harnesses for Tailwind v4:**
   - Updated `frontend/lib/__tests__/layout-widths-css.test.ts` to compile via `compile()` from `tailwindcss` in `@jest-environment node` and parse the emitted CSS AST with `postcss.parse()`.
   - Updated `frontend/__tests__/contrast-literals.test.ts`, `frontend/__tests__/dashboard-pending-badge-contrast.test.ts`, `frontend/__tests__/cluster-g-contrast-sites.test.ts`, and `frontend/app/dashboard/__tests__/staff-page.test.tsx` to convert CSS Color 4 `oklch(L% C H)` strings exported by `tailwindcss/colors` in v4 into sRGB `[0..255]` tuples for WCAG 2.1 contrast calculation.
   - Updated `frontend/__tests__/reduced-motion-css.test.ts` to check cascade order after `@import "tailwindcss"`.
   - Updated `frontend/e2e/mobile-instrument-contract.spec.ts` (`hoverRuleAudit`) to recurse into `CSSLayerBlockRule` (`@layer utilities`) when inspecting `@media (hover: hover)` rules in the browser CSSOM.

4. **Verification & 4-Viewport Parity Proof (`.planning/phases/39-platform-runtime-upgrades/evidence/39-07-tailwind4-parity.txt`):**
   - `npx tsc --noEmit` (`rc=0`), `npm run lint` (`rc=0`), `npm test -- --watchAll=false` (`250/250` suites, `2962/2962` tests, `rc=0`), `npm run build` (`rc=0`).
   - `bash scripts/check-layout-width-contract.sh` (`112/112` claims, `rc=0`) and `bash scripts/check-playwright-mobile-contract.sh` (`rc=0`).
   - Playwright browser E2E against production build: `e2e/mobile-instrument-contract.spec.ts` (`4/4`, `rc=0`), `e2e/layout-width-contract.spec.ts --grep "@stack-free"` (`12/12`, `rc=0`), `e2e/public-layout.spec.ts` + `e2e/public-a11y.spec.ts` (`80/80`, `rc=0`), and 4-viewport (`390 / 768 / 1280 / 1920`) probe (`rc=0`).
