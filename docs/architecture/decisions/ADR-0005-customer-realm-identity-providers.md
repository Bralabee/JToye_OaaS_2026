# ADR-0005: Customer-realm identity providers — record the decision, ship the groundwork disabled

**Status:** Accepted (2026-08-08)
**Refs:** #432 (`jtoye-customers` has `identityProviders: 0`), CUST-03, Phase 33 plan `33-04`,
`33-CONTROL-ARMS.md` Q-3 (owner decision, 2026-08-08), `.planning/phases/33-the-consumer-product/RESEARCH.md`
§#432 and Assumptions Log A2, ADR-0001 D-1 (the commercial-decision class)

## Context

The `jtoye-customers` Keycloak realm — the B2C identity realm behind the storefront, separate from the
staff realm `jtoye-dev` — has **no `identityProviders` key at all**. Not present-and-empty: absent.
Consumer sign-up therefore has exactly one route in, email + password, and a consumer who abandons at
the password field has no second door.

CUST-03 has **two** legitimate limbs, and D-3 says so explicitly: populate the identity providers, **or**
record a dated deliberate decision. This ADR is the second limb. The owner answered Q-3 as `q3-record`
on 2026-08-08.

### What is blocked, and by what

Google is the only candidate that costs nothing and needs no review, and it is blocked on a single
mechanical requirement: **a Google OAuth client's production redirect URI must be HTTPS on a resolving
host.** Google exempts `localhost` from that rule, so the local demo is legal —

```
http://localhost:8085/realms/jtoye-customers/broker/google/endpoint
```

— and a production integration is not. That asymmetry is the whole decision: enabling Google today
ships a sign-in button that works on a developer's laptop and nowhere else.

The state of the production domain was **re-measured at decision time**, and the premise everybody had
written down turned out to be stale while the conclusion survived. The old note read *"`jtoye.co.uk`
does not resolve."* It does:

```
getent hosts jtoye.co.uk      -> 162.255.119.30                     rc=0
dig +short jtoye.co.uk NS     -> dns1.registrar-servers.com.        (Namecheap)
                                 dns2.registrar-servers.com.
curl https://jtoye.co.uk      -> rc=28, timed out after 12005 ms, http_code 000
curl http://jtoye.co.uk       -> 302, remote_ip 162.255.119.30      (parking redirect)

CONTROL, negative: getent hosts olajay.co.uk           -> rc=2, does NOT resolve
CONTROL, positive: curl https://www.ordnancesurvey.co.uk -> 200
```

The domain resolves to a **registrar parking page whose HTTPS does not answer**. Google's requirement
is HTTPS *on a resolving host*, and the HTTPS half is unmet. Both controls are recorded because a DNS
or `curl` check that can only ever report one direction is not a check: the negative control proves the
resolver can say "no", the positive control proves the local HTTPS machinery works.

**Consequence for anyone reading this later:** a successful `getent hosts jtoye.co.uk` is **not**
evidence the domain is live. Anyone about to flip `DEPLOY_*_ENABLED` on the strength of a resolving
hostname would be acting on a parking page.

### Why not Apple, why not Meta

- **Apple** — Sign in with Apple requires an **Apple Developer Program membership**, which is paid and
  annually renewed.
- **Meta / Facebook** — requires a developer account **plus app review** before the login can be used
  publicly.

Both are exactly the class of commitment ADR-0001's D-1 exists to avoid: a sixth standing commercial
decision taken as a side effect of a technical task. Neither is rejected on merit; both are deferred
because taking them is a business decision, not an engineering one.

## Options

1. **Populate the identity providers now** (`q3-populate`). Free for Google, and it works — on
   localhost only, for the reason above. Ships a button that fails for every real user.
2. **Record a dated deliberate decision and ship the groundwork disabled** (`q3-record`).
3. **Do nothing.** Leaves `identityProviders` absent by omission, with no record of whether that was a
   choice or an oversight — which is the state #432 was filed against.

## Decision

**Option 2.** Record this dated decision, and commit the Google groundwork **inert**: present,
reviewed, and `enabled: false`, with **no client secret committed**.

Consumer sign-up remains single-route (email + password) until a resolving HTTPS host exists for
Keycloak.

### What ships instead — the checklist for whoever turns it on

Each item is already in the tree. Enabling the provider is meant to be a one-variable switch plus a
realm replacement, not a reopening of this work.

| Artefact | What is already there | What the enabler does |
|---|---|---|
| `infra/keycloak/realm-export-customers.template.json` | An `identityProviders` entry, `alias`/`providerId` `google`, `enabled: false`, `trustEmail: true`, `defaultScope: "openid email profile"`, `syncMode: IMPORT`, credentials driven from `${GOOGLE_CLIENT_ID}` / `${GOOGLE_CLIENT_SECRET}` | Flip `enabled` to `true` |
| `docker-compose.full-stack.yml` (customer `envsubst` allow-list, the **second** invocation) | `$$GOOGLE_CLIENT_ID` and `$$GOOGLE_CLIENT_SECRET` named in the allow-list, and both passed into the render sidecar's environment | Nothing |
| `.env.example` | Both keys documented, empty, pointing here | Set both in the real `.env` |
| `.env` | **Zero** `GOOGLE` variables, deliberately | Add the two values (never committed) |
| `scripts/verify-env.sh` | Requires both keys **only when the IdP is enabled**, and treats the `VAR=  # comment` shape as unset | Nothing — the guard starts demanding them automatically |
| `infra/keycloak/README.md` | The realm-replacement procedure (see below) | Follow it; a template edit alone does **not** reach a running realm |
| Google Cloud console | — | Register the redirect URI `{keycloak-public-url}/realms/jtoye-customers/broker/google/endpoint`. Google forbids wildcards and raw IPs, so the exact value is not guessable and must be registered by hand |

**The realm-replacement step is not optional.** `--import-realm` *skips* a realm that already exists,
and Keycloak is Postgres-backed, so dropping the `keycloak_data` volume is a **no-op**. The two working
routes are `kc.sh import --override true` with the server stopped, or an Admin-API `POST` to
`/admin/realms/jtoye-customers/identity-provider/instances`.

### No CSP change was required, and this is why

An earlier draft of `33-04` planned to add the Google origin to `form-action` in
`frontend/lib/security-headers.ts`. That was wrong on two independent counts, and it is recorded here
because a future implementer who assumes otherwise will waste a session chasing a policy that never
applies.

1. **Whose document governs.** The browser navigates J'Toye → the Keycloak realm (governed by
   **J'Toye's** CSP), and then Keycloak → `accounts.google.com` (governed by **Keycloak's** CSP, on
   Keycloak's own document). J'Toye's policy never sees the Google origin.
2. **The hop that J'Toye does govern is already permitted.** `security-headers.ts:101` emits
   `form-action 'self' ${keycloakSources.join(" ")}`, and the realm sources are already emitted in
   **both** the bare and trailing-slash forms.

That trailing-slash detail is load-bearing and has bitten this project before: **a CSP source
expression carrying a path matches that path exactly unless it ends in `/`.** It has blocked customer
sign-in on this realm once already. Whoever adds any new source expression here must emit both forms.

Because the change was unnecessary, the acceptance criterion that would have "proved" it — *"sign-in
still works"* — **could not have fired**. It was removed rather than weakened. Sign-in still appears in
`33-04`'s human gate, but framed honestly: as a regression check on pre-existing behaviour, not as
validation of a change this work makes.

## Consequences

- **CUST-03 is satisfied on its recorded-decision limb only.** `identityProviders` remains
  unpopulated, and `.planning/REQUIREMENTS.md` says exactly that. Claiming the populate limb would be
  false.
- **Consumer sign-up stays single-route.** If storefront conversion data later shows password
  abandonment is material, that is a reason to prioritise the unblocking condition — not a reason to
  reopen this decision on its own terms.
- **No Google sign-in button is offered to users.** Shipping one that only works on a laptop is the
  precise outcome this ADR exists to prevent.
- **No secret enters version control.** The client secret travels the existing `envsubst` + `.env`
  path, and `33-04` asserts a zero count of `GOOGLE` entries in `.env`.

### The single condition that unblocks this

**A resolving host serving working HTTPS for Keycloak.** Nothing else is in the way for Google — no
payment, no review, no code. When that exists, work this ADR's checklist top to bottom.

### Preconditions to check *at enablement time*, not now

- **`trustEmail: true` is safe only for a provider that actually verifies email.** It is set for Google
  because Google verifies. Setting it for a provider that does not would let a brokered login claim an
  existing account's address by asserting it — an account-takeover primitive. Any future provider must
  be assessed on this before its entry is added, and the flag is **not** a default to copy.
  Countervailing note: without `trustEmail`, Keycloak re-verifies by email, and in local dev Mailhog
  swallows that mail, so the flow stalls with no obvious cause. Both directions are traps; the flag is
  a per-provider judgement, not a convenience.
- **Keycloak 24 strips unmanaged attributes on admin-API user create.** A brokered first login
  provisions a user, so any custom claim the storefront relies on must be declared **managed** in the
  realm's user profile first, or it is silently dropped. **Measured 2026-08-08:**
  `CustomerJwtVerifier` reads only `email` (`getClaimAsString("email")`) and `email_verified` — no
  `tenant_id`, no custom claim. Both are standard OIDC claims, so the KC24 strip does **not** bite the
  storefront customer path as it stands today. Re-measure before enabling: the risk returns the moment
  anything on that path starts reading a custom claim.
- **`CUSTOMER_VERIFY_EMAIL` interacts with brokering.** It is `false` in local dev for least-friction
  headless registration. A brokered Google login arrives already verified; a password registration does
  not. Decide deliberately which one the production realm requires rather than inheriting the dev
  default.

### Open question that survives this decision — recorded as unverified

**RESEARCH assumption A2 is UNVERIFIED:** whether Google OAuth scopes `openid email profile` alone are
non-sensitive enough that a *published external* app using only them escapes Google's app-verification
process. Google's documentation states verification is required for apps using *"scopes that permit
access to certain user data"* but does not, on that page, enumerate which scopes are non-sensitive.

This is recorded as unverified rather than resolved because resolving it does not change the decision,
and because stating it plainly is the difference between a decision and a guess. Note the direction it
points: **if A2 is wrong, a production Google IdP additionally needs a verification process — which
strengthens the case for deferring, not weakening it.** It must be resolved before any future work
takes the populate limb.

### Revisit triggers (any one is sufficient)

- A resolving host with working HTTPS is stood up for Keycloak (the unblocking condition).
- The owner takes the commercial decision to pay for an Apple Developer Program membership, or to put a
  Meta app through review.
- Storefront data shows single-route sign-up is materially costing conversions.
- Google changes its localhost exemption or its redirect-URI rules — in which case even the local demo
  route recorded above stops being true.
