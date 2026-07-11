# Phase 19 — Deferred / Out-of-Scope Items

Discoveries logged during execution that are outside the current plan's scope
(pre-existing, unrelated files). Do NOT fix inside the plan that found them.

## 19-03

- **Raw `tsc --noEmit` flags jest-dom matchers (`toBeInTheDocument`, `toHaveClass`,
  `toHaveAttribute`) as type errors across ~9 pre-existing test files**
  (`app/auth/signin/__tests__`, `app/dashboard/__tests__`,
  `app/dashboard/kitchen/__tests__`, `app/dashboard/onboarding/__tests__`,
  `app/dashboard/products/__tests__`, etc.).
  - **Pre-existing:** the same usages exist at base commit `8b13745`; not
    introduced by 19-03. None of the erroring files are touched by this plan.
  - **Root cause:** `tsconfig.json` has no `compilerOptions.types` entry and no
    ambient reference to `@testing-library/jest-dom`; the matcher augmentation
    is only imported at runtime via `jest.setup.js`, so a raw `tsc` run does not
    see it. Jest runs green; the project's actual gate (`next build`) is
    unaffected (CI on `main` is green).
  - **Scope:** repo-wide test-tooling config, not a Phase 19 concern. If desired,
    add `"types": ["jest", "@testing-library/jest-dom"]` (or a
    `types/jest-dom.d.ts` with `/// <reference types="@testing-library/jest-dom" />`)
    in a dedicated tooling change. All 19-03 source + test files are type-clean.

## 19-09 (closure — live E2E triage)

- **Customer B2C self-registration fails at Keycloak (`storefront-client`).**
  The `after login, nav shows profile and My Orders appears` storefront E2E is
  RED on both projects: Keycloak logs `type="REGISTER_ERROR" ... error="invalid_request"`
  for `storefront-client` when the spec registers a fresh customer via the
  hosted registration form. The auth *redirect* works (the "Sign in button
  redirects to Keycloak" test is green), but the registration round-trip never
  establishes a customer session, so the nav keeps showing "Sign in".
  - **Pre-existing + out of scope:** this is the Phase-18 **customer-realm split**
    (`jtoye-customers` / `storefront-client` PKCE) B2C SSO flow — untouched by
    Phase 19, which is a UIX (landing/shell/dashboard/checkout/menus/palette)
    overhaul. It is NOT one of the six UIX-01..06 success criteria. UIX-04
    checkout is fully verified via the GUEST (COD) path, which is green.
  - **Likely cause:** the hosted "Register" link off the login page drops the
    PKCE `code_challenge`/`code_challenge_method` (the client is configured to
    require S256), so Keycloak rejects the registration authorization request as
    `invalid_request`. Needs a Keycloak client/realm registration-flow fix +
    a callback PKCE audit — a customer-auth (Phase 18) task, not this closure.
  - **Action:** track for a customer-auth follow-up. All other storefront E2E
    (rendering, per-shop menus, images/fallback, guest checkout + fee-before-pay,
    Mailhog email, guest `/track`) are green.

- **Latent NPE in the dashboard finance chart.** `app/dashboard/page.tsx:184`
  does `financialSummary?.vatBreakdown.map(...)` — if the `/financial-transactions/summary`
  response lacks `vatBreakdown` (or is malformed), this throws during render and
  the route error boundary shows "Dashboard error". Never triggers in prod (the
  real summary always carries `vatBreakdown`); it only surfaced under a malformed
  Playwright stub, now fixed in the spec. A one-line defensive `?.map(...) || []`
  would harden it. Pre-existing; not caused by Phase 19.

- **9 legacy `order_items` rows carry `product_name = 'Unknown Product'`.** These
  reference products the V45 backfill could not resolve; they are genuine legacy
  data on the shared dev volume, invisible to the (fully-stubbed) KDS E2E. UIX-03
  ("no Unknown Product for a real product") is satisfied for all seeded/real
  products; these 9 are unresolvable legacy artefacts. Not a code defect.
