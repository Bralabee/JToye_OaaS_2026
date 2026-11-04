# Quick 261104-auth: persona close-out, lane `auth` (sign-in, sessions, Keycloak realm and theme)

Branch `fix/persona-auth`, from origin/main d1a04035. Issues: #843, #847, #848, plus swarm
SW-JRD-06, SW-MRC-12, SW-SAM-10.

## #843 (regression of #465): a slow network signs the customer out after five minutes

**Re-checked on the branch, not taken from the triage.** The realm rotates refresh tokens
(`revokeRefreshToken=true`, `refreshTokenMaxReuse=0`). `lib/customer-token-refresh.ts` had a
single-flight guard that collapsed probes overlapping IN TIME and released as soon as the
redemption settled; `app/api/customer-auth/session/route.ts` clears all three cookies when a
redemption fails. So a probe that left the browser with the old cookie but reached the server
after the first had settled redeemed a spent token, was refused, and cleared the cookies the first
probe had just issued.

Fix, two layers:
1. Server: a 60 s memo of SUCCESSFUL redemptions keyed by SHA-256 of the spent token; a late
   probe presenting that token gets the same renewed session. Successes only, capped at 1000,
   expires, never answers a different token.
2. Browser: `getCustomerSession()` shares one request between overlapping callers (the nav store
   probes on mount and each second for five seconds, checkout and the order pages add their own).
   Nothing is cached after it settles.

Not done: the Playwright throttled-network acceptance in the issue. The jest fake-timer tests
reproduce the mechanism; a throttled-browser run needs the full stack and was not done.
Across frontend replicas the memo is per instance (the IdP stays the arbiter); the client single
flight is what covers that gap for one browser.

Tests (RED first): `app/api/customer-auth/__tests__/session-refresh-late-probe.test.ts` (5, two RED
on the unfixed code, three pin the boundaries), `lib/__tests__/customer-session-single-flight.test.ts`
(3, one RED). Break arms on the memo: unbounded window and a constant key each turn their named
test RED.

Threat notes (ASVS V3): the memo extends the validity of a spent refresh token by at most 60 s,
only for a request that presents that exact token (the same proof the IdP asked for), only in this
process. Tokens are never logged or returned in a body. A stolen spent token is no more useful than
it was a moment earlier.

## #847 + SW-JRD-06: reset and verification links expire in 5 minutes

Realm templates: `actionTokenGeneratedByUserLifespan` 3600 in both, plus
`actionTokenGeneratedByUserLifespan.verify-email` 86400 under `attributes` in the customer
template. `staging-realm.jq` derives from the templates, so staging renders carry them;
`staging-realm-import.sh` assertions refuse a realm below the floors (rendered and live), with a
selftest arm. Runbook `docs/runbooks/keycloak-action-token-lifespan.md` for the owner's kcadm step.
Proof: the real emails' link `exp` before and after, on throwaway 26.8.1 containers.

Not done (SW-JRD-06 second half): the emails are plain text and unbranded. That needs a new `email`
theme type and an `emailTheme` setting, a separate product-visible surface; skipped, listed in the
report.

## #848 + SW-MRC-12 + SW-SAM-10: Keycloak pages below the accessibility bar

The recorded reason for "no FTL overrides" is a maintenance argument (a fork rots silently at the
next upgrade), not a prohibition; the 261102-7lu plan itself names a one-file `template.ftl`
override as the sanctioned exception route. Three overrides (template, login, register), derived by
an exact-match script from the 26.8.1 base so every difference is a marked `JTOYE` block, plus two
messages and CSS. The rot risk the reason names is closed by `scripts/check-keycloak-theme-ftl.sh`
(CI): marker must equal the pinned image, a11y properties must survive, policy text must agree with
the realm, lifespans must stay above their floors, k8s must mount every override.
k8s component and goldens updated; copy-parity pairs added.
Plus a privacy line on `/shop/signin` (code-only half of SW-SAM-10).
Proof: `frontend/e2e/keycloak-login-a11y.verify.mjs` (axe + structural) against a throwaway 26.8.1.

Not done: `user-profile-commons.ftl` (profile fields' `required` and error `aria-describedby`),
`login-update-password.ftl` (the reset flow's rules), the other stock templates' positive tabindex.
None is reachable on this realm's flows except update-password; recorded in the report.

## Gates

`npx jest` (touched, then full), `npm run lint`, `npm run build`, the new gate and its selftest,
`check-k8s-copy-parity.sh`, `k8s/scripts/render-golden.sh` (goldens regenerated, diff reviewed),
`k8s/scripts/check-render-invariants.sh`, `staging-realm-import.sh --selftest`,
`check-gate-enforcement.sh`, `docs-freshness.sh`, `check-doc-metrics.sh`,
`check-branch-behind-base.sh`.
