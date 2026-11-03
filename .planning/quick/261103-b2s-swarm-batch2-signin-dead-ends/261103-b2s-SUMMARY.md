---
quick_id: 261103-b2s
status: complete
---
# Summary: 261103-b2s (PR #1110)

- Callback (round-0 shape, replaced in round 1): `hasPendingLogin()`; `app/shop/auth/callback/page.tsx`
  shows the other-tab message plus Sign in. Jest: 2 new callback tests red on main's page, green on
  the fix; helper tests 3/3; full Jest 300 suites / 3573 pass; tsc rc 0; eslint 0 errors.
- Real flow (local Keycloak 26.8.1, Playwright, "Forgot password?" then the Mailhog link in a new tab):
  main (:3000) shows "Authentication failed"; branch (:3007) shows the new message, and Sign in lands
  on /shop with the session marker true. A fully separate browser never reaches the app: Keycloak
  ends on "Account updated", now with a Back to Application link from the baseUrl.
- Verification link opened twice: before, stock text and no link; after, the new text and
  `<a href="http://localhost:3000/shop/signin">`.
- staging-realm-import.sh --selftest all PASS; break arm (jq baseUrl removed) FAILs the transformed
  arm naming `storefront-client baseUrl`. Copy parity PASS; render-golden red before regeneration,
  green after.
- Local stack: storefront-client baseUrl set live (the runbook's kcadm script, rc 0). Probe users
  deleted, the temporary :3007 redirect URI and web origin removed.

## Review round 1 (PR #1110)
- F1 admissible, reproduced on main's build: a tab holding its own stale verifier that receives the
  reset code shows "Authentication failed". Fixed with `exchangeCallback()` returning a typed outcome
  (no verifier OR state mismatch = not-this-tab; only a matching exchange can fail), which also
  answers F2 (one check, in the library); F6 `detail` is derived during render.
- Proof: `customer-auth-exchange-callback.test.ts` 4/4, break arm (guard narrowed to `!verifier`)
  1 failed; real flow on :3007 shows the other-tab message and Sign in lands on /shop signed in;
  plain sign-in and the no-verifier new tab unchanged. Full Jest 3574 / 300.
- F3 not reproduced (the older of two verification emails gave Keycloak's proceed page, not the
  stale text); F4, F5 recorded.
