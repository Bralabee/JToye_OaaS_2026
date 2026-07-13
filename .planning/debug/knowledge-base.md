# GSD Debug Knowledge Base

Resolved debug sessions. Used by `gsd-debugger` to surface known-pattern hypotheses at the start of new investigations.

---

## mobile-nav-operators-hidden — "For operators" undiscoverable from /shop on mobile (no hamburger menu in storefront nav)
- **Date:** 2026-07-13
- **Error patterns:** mobile nav, hamburger, Open menu, menu-buttons empty, For operators, /shop, StorefrontNav, PublicHeader, footer-only link, 390px viewport, hidden behind hamburger, above-fold, discoverability
- **Root cause:** Phase 19 (PR #181) shipped two disjoint public header systems. PublicHeader (used by /, /track, /for-operators, /business-model-guide via PublicShell) collapses nav into an "Open menu" hamburger Sheet at <sm that includes "For operators". But /shop's own layout (app/shop/layout.tsx) uses StorefrontNav, which had no mobile menu and no "For operators" link at any breakpoint — the only operators link on /shop was the below-fold footer. On a 390px viewport a customer on /shop had zero above-fold path to /for-operators.
- **Fix:** StorefrontNav now mirrors the PublicHeader nav idiom — desktop (>=sm) inline links in `hidden sm:flex` incl. new "For operators" → /for-operators, plus a new `sm:hidden` hamburger button (aria-label="Open menu") opening a shadcn Sheet with Browse shops / For operators / Track order / My Orders (session-gated); session control stays inline at all breakpoints. Regression test added (3 it-blocks); docs/metrics.json regenerated. Fix commit e05e634.
- **Files changed:** frontend/components/storefront/storefront-nav.tsx, frontend/components/storefront/__tests__/storefront-nav.test.tsx, docs/metrics.json
---
