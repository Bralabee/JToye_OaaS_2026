---
status: resolved
trigger: "User report 2026-07-13: customer has to click track order before they can see the for operators page (mobile)"
created: 2026-07-13T13:30:00Z
updated: 2026-07-13T14:40:00Z
---

## Symptoms

DATA_START
- **Expected:** the "For operators" destination is discoverable from every public page on mobile — visible header link or a menu that contains it.
- **Actual (measured, Playwright 390x844 iPhone-13 UA, 2026-07-13):**
  - `/` — header "For operators" anchor `visible:false` (hidden behind hamburger, "Open menu" button present); only a mid-page card and a below-fold footer link.
  - `/shop` — **NO menu button at all** (`menu-buttons=[]`); the ONLY for-operators anchor is footer, `inViewportInitially:false`.
  - `/track` — header link hidden behind hamburger BUT an above-fold visible "Become a vendor" link exists — the only page where the destination is discoverable without scrolling/menu.
- **Errors:** none — pure nav-structure defect.
- **Timeline:** shipped in Phase 19 full-frontend overhaul (PR #181, 2026-07-11); every audit since ran desktop-viewport only.
- **Reproduction:** repro script `/tmp/claude-1000/-home-sanmi-IdeaProjects-JToye-OaaS-2026/5581c3e3-1926-4520-90ab-11439487cfb7/scratchpad/repro-ux2.js` (TEST D), run from `frontend/` with `NODE_PATH=./node_modules node <script>`; screenshots `D-mobile-*.png` in the same dir. Stack must be up (frontend :3000).
- **Suspected files:** `frontend/app/shop/layout.tsx` (own nav, no mobile menu), `frontend/app/page.tsx`, shared nav components under `frontend/components/`.
- **Constraints:** branch `feature/ux-mobile-nav-rsc-fixes` (off main; PR #213 separate). Frontend TS changes require `npm run build` (tsc gate), jest is not a type-check. Rebuild frontend container before any live-E2E claim. Verification must run the mandatory matrix: desktop + 390px mobile × fresh + stale cookies × real click-through with content-appearance timing.
DATA_END

## Current Focus

hypothesis: CONFIRMED — /shop uses its own layout (`app/shop/layout.tsx`) with `StorefrontNav`, which has NO mobile menu and NO "For operators" link at any breakpoint (footer-only). /, /track, /for-operators, /business-model-guide use `PublicShell` → `PublicHeader`, which has an "Open menu" hamburger Sheet containing "For operators" at <sm.
test: FIX APPLIED + VERIFIED live (14/14 matrix checks, rebuilt container, 2026-07-13T14:20Z) + HUMAN-CONFIRMED (2026-07-13T14:40Z, independent re-verification by session manager). See Resolution.
next_action: none — session RESOLVED and archived to .planning/debug/resolved/.

reasoning_checkpoint:
  hypothesis: "/shop is undiscoverable to /for-operators on mobile because StorefrontNav (the shop layout's only nav) contains no mobile menu control and no /for-operators link at any breakpoint; the only operators link on /shop is the below-fold footer."
  confirming_evidence:
    - "Live repro at 390px (2026-07-13T13:52Z): /shop menu-buttons=[], only for-operators anchor is footer with inViewportInitially:false, while / and /track show visible aria-label='Open menu' buttons (PublicHeader Sheet)."
    - "Static read: storefront-nav.tsx has no Menu/Sheet import and no href='/for-operators' anywhere; public-header.tsx has both (sm:hidden hamburger + Sheet with For operators)."
  falsification_test: "If after adding the hamburger Sheet + for-operators links to StorefrontNav and rebuilding the frontend container, TEST D still reports menu-buttons=[] on /shop, the missing-structure hypothesis is wrong (something would be stripping the control at runtime instead)."
  fix_rationale: "The root cause IS missing nav structure (never built into the storefront header in Phase 19), so the fix adds exactly that structure using the app's established PublicHeader mobile idiom — not a symptom patch. Desktop inline 'For operators' link restores destination parity across both public header systems."
  blind_spots: "(a) Sheet interaction on real touch devices untested (jsdom + headless Chromium only); (b) only 390px measured, not 320px ultra-narrow; (c) sheet z-index vs storefront sticky header assumed OK because PublicHeader uses identical z-50/Sheet stack — will confirm via post-fix screenshots."

## Evidence

- timestamp: 2026-07-13T13:45:00Z
  checked: grep for "For operators" + PublicHeader/PublicShell usages across frontend/app and frontend/components
  found: PublicShell (header+footer wrapper) is used by /, /track, /for-operators, /business-model-guide. /shop has its OWN layout at app/shop/layout.tsx which does NOT use PublicShell/PublicHeader.
  implication: two independent header systems shipped in Phase 19 — Surface B public pages vs storefront pages.

- timestamp: 2026-07-13T13:46:00Z
  checked: frontend/components/public/public-header.tsx (full read)
  found: At <sm, desktop nav is `hidden sm:flex`; a `sm:hidden` hamburger button (aria-label="Open menu") opens a shadcn Sheet containing Shops, For operators, Track order, Sign in. So / and /track DO expose "For operators" via a visible menu control on mobile.
  implication: / and /track satisfy "discoverable via visible menu" — the defect is isolated to /shop.

- timestamp: 2026-07-13T13:47:00Z
  checked: frontend/app/shop/layout.tsx + frontend/components/storefront/storefront-nav.tsx (full read)
  found: Shop layout renders wordmark + StorefrontNav in header; footer has the ONLY "For operators" link. StorefrontNav renders inline links Browse (/shop), Track (/track, label hidden <sm), My Orders (session-gated), and a Sign in/profile control. NO hamburger, NO Sheet, NO for-operators link at ANY breakpoint.
  implication: On /shop mobile (and desktop), the operators destination exists only in the below-fold footer — matches measured symptom exactly (menu-buttons=[], footer anchor inViewportInitially:false).

- timestamp: 2026-07-13T13:48:00Z
  checked: tests covering these components
  found: No tests exist for StorefrontNav or PublicHeader (only cart-provider.test.tsx under components/storefront/__tests__). Jest counts are CI-gated via docs/metrics.json (scripts/docs-freshness.sh --write regenerates).
  implication: New test for the fix requires metrics.json regeneration in the same commit.

- timestamp: 2026-07-13T14:00:00Z
  checked: e2e specs for selector collisions with new nav structure (storefront-flows.spec.ts lines 126, 421-422 assert nav "Browse"/"Sign in" visible)
  found: Spec uses Playwright default 1280x720 viewport; my change keeps Browse/Sign-in inline at >=sm, and Radix unmounts sheet content when closed (confirmed: baseline repro on / showed no sheet anchors), so no strict-mode duplicate "Browse" match.
  implication: No e2e regression expected from the nav restructure.

- timestamp: 2026-07-13T14:05:00Z
  checked: jest behavior of the new sheet test (initial assertion expected >=2 "For operators" links after opening)
  found: Radix modal sets aria-hidden on everything OUTSIDE the open portal, so the inline link leaves the accessibility tree while the sheet is open — getAllByRole returned exactly 1 (the sheet's link). Test rewritten to assert the sheet's own links (singular getByRole).
  implication: Sheet-open assertions must target the portal contents only; not a product bug.

- timestamp: 2026-07-13T14:20:00Z
  checked: full verification matrix against REBUILT frontend container on :3000 (scratchpad/verify-fix.js, 14 checks)
  found: ALL PASS — mobile 390 fresh: /shop "Open menu" visible → sheet exposes visible "For operators" → tap lands /for-operators (h1 in 1000ms); / and /track sheets expose it too; mobile 390 STALE cookies: same /shop click-through passes (994ms); desktop 1280: inline "For operators" visible above fold (y=18), hamburger hidden, click-through lands. TEST D re-run: /shop menu-buttons=[{"label":"Open menu","visible":true}]. Settled screenshot VERIFY-shop-sheet-settled.png confirms sheet panel renders over overlay (z-index blind spot closed). jest 33/33 suites green; npm run build (tsc) green.
  implication: Fix verified against original symptoms across the mandatory matrix; awaiting human confirmation.

## Eliminated

- hypothesis: Rendering/hydration bug hides an existing menu control on /shop
  evidence: Static code read shows StorefrontNav contains no menu button or for-operators link at all — nothing to hide; it was never built into the storefront header.
  timestamp: 2026-07-13T13:47:00Z

## Resolution

root_cause: Phase 19 (PR #181) shipped two disjoint public header systems. `PublicHeader` (used by /, /track, /for-operators, /business-model-guide via PublicShell) collapses nav into an "Open menu" hamburger Sheet at <sm that includes "For operators". But /shop's own layout (`app/shop/layout.tsx`) uses `StorefrontNav`, which has no mobile menu and no "For operators" link at any breakpoint — the only operators link on /shop is the below-fold footer. On a 390px viewport a customer on /shop has zero above-fold path to /for-operators; they only find it after navigating to /track (which shows an above-fold "Become a vendor" link), matching the user report.
fix: StorefrontNav now mirrors the PublicHeader nav idiom — (1) desktop (>=sm) inline links grouped in `hidden sm:flex` incl. NEW "For operators" → /for-operators; (2) NEW `sm:hidden` hamburger button (aria-label="Open menu") opening a shadcn Sheet containing Browse shops / For operators / Track order / My Orders (session-gated); session control (sign-in pill / profile chip) stays inline at all breakpoints. Regression test added (3 it-blocks) asserting inline link + menu control + sheet destinations; docs/metrics.json regenerated via docs-freshness --write (jest 231→234 blocks, 32→33 files, total 1208→1211).
verification: Container REBUILT and verified live on :3000 (2026-07-13T14:20Z). 14/14 checks pass across the mandatory matrix — mobile 390px (fresh + stale cookies): /shop "Open menu" visible → sheet exposes "For operators" → real tap lands /for-operators with h1 in ~1s; / and /track sheets expose it; desktop 1280px: inline link above fold (y=18), hamburger hidden, click-through lands. TEST D repro now shows /shop menu-buttons=[Open menu visible:true]. Settled screenshot confirms sheet renders correctly. jest 33/33 suites (229 tests) green, npm run build (tsc) green, docs-freshness metrics regenerated. HUMAN-CONFIRMED 2026-07-13: session manager independently re-verified against the live rebuilt container on :3000 — (1) TEST D repro re-run: /shop at 390px reports menu-buttons=[{"label":"Open menu","visible":true}] (previously []), and /, /shop, /track each expose the for-operators destination above-fold or via visible menu; (2) real-tap click-through at 390px iPhone-13 UA on all three routes: tapping "Open menu" revealed a visible "For operators" link landing on /for-operators with content in <1s (964ms, 997ms, 968ms); (3) stale-cookie TEST E clean (content in ~110-150ms, no slow/error responses). Fix commit: e05e634.
files_changed: [frontend/components/storefront/storefront-nav.tsx, frontend/components/storefront/__tests__/storefront-nav.test.tsx, docs/metrics.json]
