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

Fix, REVISED after review round 1 (the first design, a 60 s server memo of the renewed tokens, was
withdrawn: it re-issued a live refresh token to a holder of the spent one, outlived sign-out, and is
per pod while staging scales the frontend to 10):

MEASURED (evidence/reuse-detection-measured.txt): on the real realm a replayed spent token is refused
AND revokes the session; the successor then fails "Session doesn't have required client". So the
replay must never be SENT, and no stateless server rule can rescue one that was.

1. Server, stateless: no token is kept; the in-flight collapse is keyed by digest and holds only while
   the redemption runs. A refusal (4xx other than 408/429) answers `authenticated:false` +
   `refreshRefused:true` and clears nothing; with the `?retry=1` query parameter (not a header: INV-24 refuses a browser-sent custom header the Ingress CORS list omits) it clears the three
   cookies. A transient failure (5xx, 429, network, unreadable body) never clears anything.
2. Browser: `getCustomerSession()` (a) joins an in-flight probe only within the same identity
   generation (bumped by every setMarker/clearMarker) and a stale probe's answer writes no marker;
   (b) serialises probes across tabs with a Web Lock (bounded wait, then probes anyway; no Web Locks,
   as before); (c) retries once with the marker after a `refreshRefused` answer. The retry fires only
   on `refreshRefused` (not on every anonymous answer, which every public page view produces).

Honest limit: a reload during a refresh loses the response that carries the new cookie, and the next
page replays the spent one. Only server-side state would cover it, which review rejected.

Not done: the Playwright throttled-network acceptance (needs the full stack).

Tests (RED first): `session-refresh-stateless.test.ts` (10, 9 RED against the memo code, with a fake
realm that revokes on replay), `customer-session-transition.test.ts` (8, 6 RED against the first
client), `customer-session-single-flight.test.ts` (3). Break arms in `evidence/r1-2-break-arms.txt`.

Threat notes (ASVS V3): nothing token-bearing is retained server-side; a request that raced a
sign-out can only be refused, never answered with a session. Web Locks name is a constant
(`jtoye-customer-session-probe`), carries no data.

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
