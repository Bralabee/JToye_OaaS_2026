---
quick_id: 260710-s6d
title: "Enforce CSP by default + drop script-src 'unsafe-inline' via nonce middleware (#89)"
closes_issue: 89
status: complete
branch: feature/89-csp-enforce-nonce
---

# Quick Task 260710-s6d — CSP enforce + nonce (#89 / P1-7) Summary

Replaced the Report-Only, `'unsafe-inline'`-carrying CSP with an **enforcing,
per-request nonce** policy. The app is now hardened against inline-script XSS while
still functioning end-to-end (verified live with Playwright + a rendered screenshot).

## What changed
- **`frontend/lib/security-headers.ts` (new):** pure, unit-testable `buildCsp()`. Emits
  `script-src 'self' 'nonce-<n>' 'strict-dynamic' https://js.stripe.com …` — **no
  `'unsafe-inline'`**. `style-src 'unsafe-inline'` deliberately kept (AC scopes to
  script-src). `upgrade-insecure-requests` gated behind `CSP_UPGRADE_INSECURE_REQUESTS`
  (off by default so the http local stack + MinIO images keep working; prod enables it).
- **`frontend/middleware.ts`:** rewritten to the canonical Next nonce recipe — generate a
  per-request nonce, set it on the request (`x-nonce` + CSP header, so Next stamps its own
  scripts) and on the response. Enforcing by default; `CSP_REPORT_ONLY=true` opts back to
  observe-only. Composed with NextAuth `auth` and broadened matcher to all routes (safe:
  `/dashboard` is gated server-side in its layout; there is no `authorized` callback).
- **`frontend/next.config.mjs`:** CSP removed from the static `headers()` (a static header
  can't carry a per-request nonce); the 3 constant headers (nosniff / referrer / permissions)
  stay.
- **`frontend/app/layout.tsx`:** `export const dynamic = "force-dynamic"`. **Required** —
  statically-prerendered pages can't receive a per-request nonce, so their inline/bootstrap
  scripts were blocked by the enforcing CSP (caught by the live E2E). The app is already
  predominantly dynamic (dashboard + storefront); only auth/utility/redirect pages were
  static, so the tradeoff is small. **This is the one notable architectural side-effect.**
- **Tests reworked:** `__tests__/csp-headers.test.ts` now tests `buildCsp()` (no unsafe-inline
  in script-src, nonce + strict-dynamic, Stripe/Keycloak allowlists) + next.config's remaining
  static headers + a guard that CSP is no longer emitted statically. `header-snapshot.test.ts`
  (+ regenerated `.snap`) snapshots the static headers and a fixed-nonce CSP string.
- **Docs:** `docs/metrics.json` regenerated (jest_blocks 105→109, total 771→775);
  CLAUDE.md test-count prose updated to match.

## Verification (proof)
- **Jest:** full suite green — 19 suites, 108 tests. `docs-freshness` check passes (775).
- **Build:** `npm run build` compiles; all 27 routes now `ƒ (Dynamic)`; `ƒ Proxy (Middleware)`.
- **Live header (curl on :3100):** enforcing `content-security-policy` served with
  `script-src 'self' 'nonce-…' 'strict-dynamic' …`, no `'unsafe-inline'`.
- **Live Playwright `e2e/csp-no-violations.spec.ts` (:3100):**
  - First run (single-step, strict-dynamic on static pages) **FAILED** — homepage + dashboard
    blocked Next's inline/chunk scripts. This caught the nonce-vs-static-rendering defect.
  - After `force-dynamic`: **6/6 pass** (homepage, storefront, dashboard × mobile+desktop),
    zero CSP violations.
- **Rendered screenshot** of the storefront: status 200, 6 headings, 11 images with
  naturalWidth>0, 0 CSP violations — full layout renders (products, images, badges, Add buttons).

## Acceptance criteria
- [x] Enforcing CSP header shipped by default (Report-Only is now an opt-out).
- [x] No `'unsafe-inline'` in script-src; app functions (storefront rendered; Stripe hosts +
  3DS frame-src preserved; NextAuth/Keycloak form-action + connect-src preserved; dashboard
  reachable). Note: a full logged-in Stripe 3DS payment was not driven E2E (no seeded card
  session in this pass) — the Stripe allowlists are unchanged and present.
- [x] Header snapshot test updated (+ unit test reworked to the nonce policy).

## Caveats / follow-ups
- **`force-dynamic` app-wide** disables static generation. Fine for this mostly-dynamic app;
  if specific pages later need static/ISR, they'd need per-page CSP handling (out of scope).
- **Stripe 3DS** verified only by config (allowlists intact), not a live card payment.
- `e2e/csp-no-violations.spec.ts` is intentionally NOT in CI (per its header); the CI gate is
  the Jest unit + snapshot suite, which is updated and green.
