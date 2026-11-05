# Keycloak Configuration - JToye OaaS

## Overview

This directory contains the Keycloak realm configuration for the `jtoye-dev` realm used in development and testing.
The same templates are also the ONLY source of the staging realms (`jtoye-staging`, and `jtoye-customers` on the
staging cluster): `staging-realm.jq` derives them and `scripts/staging-realm-import.sh` imports them. See
["Staging realm (Kubernetes)"](#staging-realm-kubernetes).

> **Scoped machine credentials (#206 [AI-4]):** the `catalog:read`/`catalog:write` client
> scopes, the read-only `integration-catalog-ro` machine client, the per-tenant
> client-credentials recipe, and the realm re-import migration note are documented in
> [`docs/security-scopes.md`](../../docs/security-scopes.md).

## Files

- **realm-export.template.json** (tracked) → **realm-export.json** (rendered, gitignored): the vendor realm, including:
  - Clients (non-built-in): `core-api`, `edge-api`, `integration-catalog-ro`, `integration-orders-rw`.
    There is **no client named `frontend`**: the frontend app signs in through `core-api` (phase 29
    research, Blocker A — `k8s/base/configmap.yaml` still names `frontend` and the staging overlay
    does not override it yet; staging must patch it to `core-api`, as `k8s/local` already does).
  - Groups: `tenant-a`, `tenant-b` with tenant_id attributes
  - Users: Pre-configured test users with tenant assignments
  - Protocol mappers: tenant_id claim injection into JWT
  - Realm settings: Token lifespans, security policies
- **realm-export-customers.template.json** → **realm-export-customers.json**: the customer realm
  `jtoye-customers` (client `storefront-client`). Not valid JSON until its fragment placeholders are
  substituted.
- **staging-realm.jq**: the staging transform over both templates (plan 29-04).
- **configure-keycloak.sh**: a legacy admin-API helper for the dev realm.

## Realm Configuration Details

### Realm: `jtoye-dev`

**Clients:**
1. **core-api** — the client the frontend app (NextAuth) signs in through
   - Client ID: `core-api`
   - Client Secret: rendered at import from `${KEYCLOAK_CLIENT_SECRET}` (set in `.env`; the rendered `realm-export.json` is gitignored — never committed)
   - Access Type: confidential
   - Service Accounts Enabled: **false** (#947; D-02 requires it in every realm a cluster imports)
   - Valid Redirect URIs (dev): `http://localhost:8080/*`, `http://localhost:3000/*`,
     `http://localhost:3100/*`, `http://localhost:9090/*`, `http://app.jtoye.local/*`
   - Web Origins (dev): `*`

2. **edge-api** — confidential, service account enabled (the Go edge gateway).
3. **integration-catalog-ro**, **integration-orders-rw** — confidential machine clients for
   scoped integrations (`docs/security-scopes.md`).

Until plan 29-04 this README also described a public `frontend` client. No version of either
tracked realm file has ever held one (`git log -G'"clientId" *: *"frontend"'` over both finds no
commit); the deployed configmap still names `frontend` (Blocker A). The public client plan 28-05
removed (D-12, "Realm import provenance" below) was `test-client`.

### Session lifetimes (D-14, owner ruling 2026-10-10)

- **Vendor realm (`jtoye-dev`) SSO session max is 14 h** (`ssoSessionMaxLifespan` 50400, was 7200):
  a kitchen tablet signed in at the start of a shift stays signed in for the shift. The **30-minute
  idle timeout is unchanged** (`ssoSessionIdleTimeout` 1800), so a board that stops refreshing still
  lapses, and a revoked grant still stops the board on its next request (D-08). After 14 h the K2
  "Signed out" red screen appears, not after 2 h.
- **Refresh-token rotation is OFF and deferred** (`revokeRefreshToken` stays false). Turning it on
  makes each refresh token single-use, and the dashboard calls `getSession()` on every request, so
  parallel requests near a token's expiry would redeem one token several times and sign the user out.
  A per-process single-flight cannot prevent that across the 3 k8s frontend replicas or the
  middleware runtime; it needs a cross-replica single-flight (Redis-backed, keyed by refresh token, or
  proven sticky sessions) or a Keycloak `refreshTokenMaxReuse` policy, and then its own ruling.
- **The customers realm (`jtoye-customers`) is unchanged** (its template already has rotation on).
- **Reaching a running compose Keycloak:** editing the template is not enough, because
  `--import-realm` skips an existing realm (see "Replacing a realm that already exists"). Render with
  `docker compose -f docker-compose.full-stack.yml up --no-deps keycloak-realm-render`, then apply
  the one field through the Admin API (`PUT /admin/realms/jtoye-dev` with
  `{"ssoSessionMaxLifespan": 50400}`), which leaves runtime-created users and live sessions alone,
  and read it back from `GET /admin/realms/jtoye-dev`. A full `--override` import would delete every
  user the template does not carry.
- **Staging** carries the change by construction: `jtoye-staging` is derived from this template by
  `staging-realm.jq`, which leaves the session settings alone. **Production** vendor realms are not
  in this repository: an operator must make the same `ssoSessionMaxLifespan` 50400 change there
  (rotation stays off there too).

### Groups & Tenant Mapping

**tenant-a Group:**
- Attribute: `tenant_id` = `00000000-0000-0000-0000-000000000001`
- Members: `tenant-a-user`

**tenant-b Group:**
- Attribute: `tenant_id` = `00000000-0000-0000-0000-000000000002`
- Members: `tenant-b-user`

### Test Users

All seed users share the password supplied via `${KC_SEED_USER_PASSWORD}` (from `.env`;
rendered into the import at container start, never committed).

| Username | Password source | Group | Tenant ID |
|----------|-----------------|-------|-----------|
| `tenant-a-user` | `${KC_SEED_USER_PASSWORD}` | tenant-a | `00000000-0000-0000-0000-000000000001` |
| `tenant-b-user` | `${KC_SEED_USER_PASSWORD}` | tenant-b | `00000000-0000-0000-0000-000000000002` |
| `admin-user` | `${KC_SEED_USER_PASSWORD}` | (none) | (none) |

### Protocol Mappers

**tenant_id Mapper:**
- Mapper Type: User Attribute
- User Attribute: tenant_id
- Token Claim Name: tenant_id
- Claim JSON Type: String
- Add to ID token: true
- Add to access token: true
- Add to userinfo: true
- Multivalued: false
- Aggregate attribute values: false

This mapper extracts the `tenant_id` attribute from the user's groups and injects it into the JWT token claims.

### The `basic` client scope: on Keycloak 25+ the `sub` claim is a mapper

Since Keycloak 25 the `sub` and `auth_time` claims are no longer built into every token. They are
protocol mappers on a realm client scope named `basic`, which Keycloak adds to a realm's default
client scopes and to every existing client **when it migrates a database in place**. A realm file
imported fresh with `--import-realm` gets nothing it does not declare: both templates here were
exported from 24.0.5, so after the 26.8.1 upgrade (39-04) a fresh import issued access tokens with
**no `sub` claim at all**, and both templates now declare the scope themselves.

Why that is an outage and not a cosmetic gap: core-java's UUID-subject contract (23-16) reads the
subject on every vendor request. Without it `ShopAccessService.requireVendorUserId()` answers the
typed 403 (the Staff page shows "Only a Group admin can invite people" to the Group admin), and
`TenantChannelInterceptor` denies every kitchen `SUBSCRIBE` with "subscriber subject is not a UUID",
so the kitchen board sits on "Reconnecting". The shared compose runtime never showed it, because its
Keycloak volume was upgraded in place and the migration had added the scope — only a fresh volume
(the nightly E2E lane, red from 2026-10-16 to 2026-10-18; any new environment) imports the template.

**Measured on two throwaway `quay.io/keycloak/keycloak:26.8.1` containers importing the rendered
templates (2026-10-18), password grant for `admin-user` via `core-api`:**

| Template | `basic` on `core-api` | `sub` in the access token |
|---|---|---|
| Before this change (control) | absent | **absent** |
| This template | present | a UUID |

Both templates declare the scope (`sub` → `oidc-sub-mapper`, `auth_time` →
`oidc-usersessionmodel-note-mapper`, exactly as Keycloak 26 creates it), list it in
`defaultDefaultClientScopes`, and carry it in every client's `defaultClientScopes`. A new client
added to either template must list it too, or its tokens have no subject.

### User profile: `tenant_id` is a managed, admin-only attribute (Phase 37-08, D-07 / D-26)

Accepting a staff invitation creates the invited person's account in `jtoye-dev` **through the
admin API** (`KeycloakAdminClient.createUser`, called by core-java). Keycloak 24 **strips an
undeclared attribute on an admin-API create**, so until 37-08 such a user had no `tenant_id`, its
token had no `tenant_id` claim, and every request saw zero rows (`docs/security-scopes.md` §5).

The vendor realm template therefore declares a **user profile** (the component
`org.keycloak.userprofile.UserProfileProvider`, provider `declarative-user-profile`, whose
`kc.user.profile.config` is a JSON string):

- the four default attributes `username`, `email`, `firstName`, `lastName`, copied verbatim from
  `GET /admin/realms/jtoye-dev/users/profile` on Keycloak 24.0.5 (not written from memory);
- `tenant_id`, with `permissions.view = ["admin"]` and `permissions.edit = ["admin"]`, and a
  length validation of exactly 36 characters (a UUID);
- **no `unmanagedAttributePolicy`** (absent means disabled). Do **not** set it to `ENABLED`: that
  exposes unmanaged attributes in every context, including the user's own account console, and a
  vendor user could then set their own `tenant_id` and cross the tenant wall (T-37-19).

`tenant_id` is the only custom attribute any `jtoye-dev` user carries today. If you add another
user attribute, declare it in the same profile, or Keycloak will hide and drop it on the next update.

**Measured on a throwaway Keycloak 24.0.5 importing the rendered template (37-08):**

| Step | Pre-37-08 template (control) | This template |
|------|------------------------------|---------------|
| Admin-API create with `attributes.tenant_id` | 201, attribute **stripped** (`attributes: null`) | 201, `tenant_id` kept |
| Password-grant token for that user | no `tenant_id` claim | `tenant_id` claim present |
| The user POSTs a new `tenant_id` to `/realms/jtoye-dev/account` | 204 (nothing to protect: no attribute) | **400** `error-user-attribute-read-only`; admin re-read unchanged |
| `GET /realms/jtoye-dev/account` as the user | — | `attributes: null`: the user cannot even see it |

#### Applying it

- **Local (compose):** a template change reaches a running Keycloak only by re-import, because
  `start-dev --import-realm` creates absent realms only, and with a Postgres-backed Keycloak a volume
  drop is a no-op. Re-render the template (the `keycloak-realm-render` service), then run
  `docker exec -it jtoye-keycloak /opt/keycloak/bin/kc.sh import --file /opt/keycloak/data/import/realm-export.json --override true`
  and restart Keycloak (`docs/security-scopes.md` §4).
- **Staging:** `jtoye-staging` is derived from this template, so it carries this user profile; the
  staging transform keeps `tenant_id` admin-only, and the assertion suite of
  `scripts/staging-realm-import.sh` refuses a realm whose `tenant_id` is missing, user-editable, or
  beside `unmanagedAttributePolicy: ENABLED` — on the rendered file AND on the live read-back.
- **Production:** that realm is not in this repository. An operator must make the
  same change before staff invitations are used there: Keycloak admin console → realm → *Realm
  settings* → *User profile* → create attribute `tenant_id` (who can view: admin only; who can edit:
  admin only; not required), and leave *Unmanaged attributes* at **Disabled** (or *Admin can edit*,
  never *Enabled*). Alternatively `kc.sh import --override true` with this template. Recorded as
  37-08's `user_setup`.
- **The shared compose runtime is proven at the 37-15 gate**, after its rebuild and re-import:
  `GET /admin/realms/jtoye-dev/users/profile` shows `tenant_id` admin-only; an invitation accepted
  end to end creates a user whose admin representation holds `tenant_id`; that user's token carries
  `tenant_id`; and that user's account-console attempt to change it is refused.

## Email (SMTP) and login-page branding

Both realm templates carry an `smtpServer` block and three branding keys. They were added
together because they address the same audit finding: the customer "Forgot password" flow was a
dead end *and* an account-enumeration oracle, on pages headed with the raw realm id.

> **The enumeration oracle is suppressed, not fixed — know the difference.** Keycloak returns
> HTTP 500 "Failed to send email" when a send fails, and only for an address that *has* an
> account; an unknown address short-circuits to the generic 200 "check your email" page. Wiring
> SMTP removes the failing branch, so both responses become identical — but that identity is a
> **side effect of SMTP being reachable**, not an enforced property. Stop Mailhog, run one of the
> two `infra/` stacks that has none, or break the host, and the 500-vs-200 divergence returns
> silently with nothing to catch it. The 500-on-failure behaviour is upstream Keycloak's. There is
> no executable gate for this today; if you touch SMTP config, re-run the existing-vs-nonexistent
> comparison by hand.

### `smtpServer` — points at the dev stack's Mailhog

```json
"smtpServer" : {
  "host" : "mailhog",
  "port" : "1025",
  "from" : "no-reply@jtoye.local",
  "fromDisplayName" : "J'Toye",
  "auth" : "false",
  "ssl" : "false",
  "starttls" : "false"
}
```

Every value is a **string**, including the port and the three booleans — that is Keycloak's
format for this map, and a JSON number or bare boolean here is not accepted. `from` must be a
syntactically valid address or Keycloak refuses to send.

**`host` resolves on `docker-compose.full-stack.yml` ONLY, and that is deliberate.** There,
Keycloak and Mailhog are services on the same compose network, so the literal service name
`mailhog` resolves by service-name DNS from inside the Keycloak container. On the other two
stacks it does not resolve and the value is **inert by design**:

| Stack | Mailhog service | `smtpServer.host: "mailhog"` |
|---|---|---|
| `docker-compose.full-stack.yml` | yes, on the shared network | resolves — mail is delivered |
| `infra/docker-compose.yml` | **none** | dead — nothing to resolve |
| `infra/docker-compose.hostnet.yml` | **none**, and `network_mode: host` | dead — compose service-name DNS does not exist under host networking at all; even a Mailhog published on `127.0.0.1:1025` would need `localhost` |

No Mailhog service is being added to those two stacks — the boundary is recorded rather than
papered over. **This is latent, not a live outage:** both import only `realm-export.json`
(`jtoye-dev`), and that realm has `resetPasswordAllowed: false`, so no reachable path sends mail
there today. It becomes real the moment someone flips that flag, or triggers required-action mail
(`UPDATE_PASSWORD`, `VERIFY_EMAIL`) or an admin-initiated reset on one of those stacks. If you
need working mail there, add a Mailhog service **and** point the host at it — do not assume the
value already works because it works locally.

**Why these are literals and not `${ENVSUBST}` placeholders.** The realm JSONs are rendered at
three independent sites — the full-stack compose file (which renders both realms, each with its
own explicit allow-list) and the two composes under `infra/`. envsubst leaves any name that is
**not** in the invocation's allow-list completely untouched, so a placeholder missing from a
single one of those lists renders the literal `${NAME}` token into that realm's JSON, and
Keycloak then uses that token as the SMTP host. Beyond that risk, neither compose under `infra/`
has a Mailhog service at all and one of them uses host networking, so no single environment
value would be correct across all three sites — a placeholder would relocate the wrongness
rather than remove it. Reusing the existing `SMTP_HOST` name is worse still: the example env
file points it at a public example domain, so anyone who copied the example would render a dead
external host into their realm.

**Staging consumes these templates only through `staging-realm.jq`, which REPLACES this block.**
No k8s manifest mounts the rendered realm JSONs. The staging transform sets `smtpServer` to the
in-cluster Mailpit (`mailpit.jtoye-staging.svc.cluster.local:1025`, `starttls: "true"`,
`from: no-reply@olajay.co.uk`; D-20), and the staging assertion suite refuses any realm still
naming `mailhog`. **Production** does not consume these templates at all; an SMTP or branding
override there is a production-side change, and editing these files will not reach it.

**No `connectionTimeout` / `timeout` keys — Keycloak 24.0.5 does not read them.** A review raised
that an unbounded SMTP send could park a request thread on the public `reset-credentials`
endpoint. That concern does not apply to this version, verified in the shipped bytecode rather
than assumed. Disassembling `org.keycloak.email.DefaultEmailSenderProvider` out of
`org.keycloak.keycloak-services-24.0.5.jar` in the running image shows both JavaMail timeouts set
from a **constant**, not from the realm map:

```
ldc  #79   // String mail.smtp.timeout
ldc  #81   // String 10000
invokevirtual Properties.setProperty
ldc  #83   // String mail.smtp.connectiontimeout
ldc  #81   // String 10000
invokevirtual Properties.setProperty
```

Both are hardcoded to 10 s, and the class's constant pool contains no standalone `timeout` or
`connectionTimeout` string at all — so there is no realm key to set. Adding one would be dead
config that reads as protection while doing nothing. **Do not add these keys to the templates on
this version.** If Keycloak is upgraded, re-run the check before assuming either way.

**`from` uses `.local`, which is fine here and not promotable.** `.local` is reserved for
multicast DNS (RFC 6762). Mailhog accepts anything, and these templates are dev-only, but a real
relay will reject or bounce the address — change it before reusing this block anywhere real.

**`emailTheme` is deliberately unset.** The reset email itself therefore renders in Keycloak's
stock email theme, unbranded. `jtoye` ships a `login/` type only, so pointing `emailTheme` at it
would dangle. Branding the email is follow-up work, not an oversight.

### Branding keys

```json
"displayName"     : "J'Toye",
"displayNameHtml" : "<a href=\"${PUBLIC_APP_ORIGIN}\" style=\"color:#3A0B0D;font-weight:700;letter-spacing:0.01em;text-decoration:none\">J'Toye</a>",
"loginTheme"      : "jtoye"
```

`displayName` supplies the page title, `displayNameHtml` the login-page header. The header value
is raw HTML rendered into the page, so it is deliberately an operator-controlled value containing
only an `<a>` with an `href` and a style attribute — no script and nothing derived from request
data. Do not build this value from user input.

**The wordmark links home** (owner ruling 2026-11-02): a person on the email-verification,
register or reset page otherwise had no way back to the site. The `href` is the one per-environment
interpolation: `${PUBLIC_APP_ORIGIN}`, rendered by every compose render site's envsubst list
(compose default `http://localhost:3000`) and by `staging-realm.jq` on staging
(`https://app-staging.olajay.co.uk`). `scripts/verify-env.sh` refuses a value that is not one bare
origin (a path, trailing slash, quote or list would break out of, or misdirect, the attribute).
Keycloak's `kcSanitize` keeps the tag, `href` and `style` and adds `rel="nofollow"` — measured on
26.8.1, so no FTL override was needed. `scripts/check-keycloak-wordmark-home.sh` (CI) fails if
either template stops linking home or a render site drops the variable. A realm that already
exists keeps its old header until it is updated in place: `docs/runbooks/keycloak-wordmark-home.md`. The realm's own
Content-Security-Policy does not restrict `style-src`, so the inline style renders; it does keep
`object-src 'none'`.

`loginTheme` is `jtoye`, the custom theme in this repository — see the next section.

> **History.** These keys first shipped with `loginTheme` pinned to the built-in `keycloak`
> theme, on the reasoning that realm-level keys alone were a small enough lever to brand the
> pages. Review rejected that: with the stock theme's dark low-poly background, blue PatternFly
> buttons and uppercased header, a brand-coloured wordmark on an otherwise stock page does not
> read as J'Toye. The `jtoye` theme below replaced it. The original reason for pinning the value
> explicitly still holds and still applies — an unset `loginTheme` lets a Keycloak upgrade change
> the default out from under these pages.

**No `_note_*` keys at realm top level.** The free-form annotation trick used inside an identity
provider's `config` map works there because Keycloak models that as a plain string map it ignores.
Realm top-level keys are deserialised into a typed representation instead, so an unknown key risks
failing the import outright. Rationale for these keys lives here in the README, not in the JSON.

## The `jtoye` login theme

`infra/keycloak/themes/jtoye/login/` — a **CSS-only** overlay on the built-in `keycloak` login
theme. It brands every login-flow page (sign-in, register, reset-credentials, update-password,
info and error) from one stylesheet.

```
infra/keycloak/themes/jtoye/login/
  theme.properties
  template.ftl        } the four FTL overrides (#848), see "FTL overrides" below
  login.ftl           }
  register.ftl        }
  user-profile-commons.ftl }
  messages/messages_en.properties
  resources/css/jtoye.css
  resources/fonts/work-sans-latin.woff2
```

**Four FTL template overrides, and only four** (#848, UXT-079, SW-MRC-12, SW-SAM-10, 2026-11-04; the fourth, `user-profile-commons.ftl`, 2026-11-04 wave 2).
Until then there were none, deliberately: forking a template pins us to that Keycloak version's
markup and silently rots at the next upgrade, so every rule reached the parent theme's existing
class and id hooks instead. That stays the rule for everything that CAN be done there (branding,
copy through `messages_en.properties`, the wordmark through `displayNameHtml`). Three things
cannot: a `<main>` landmark, a `<title>` that names the page, positive `tabindex` removal, and the
password rules plus a privacy link on the register page. See the next section.

### FTL overrides

| File | Differs from Keycloak 26.8.1 `theme/base/login/<file>` by |
|---|---|
| `template.ftl` | `<title>` is the page heading plus the realm name (`Register - J'Toye`) instead of `Sign in to J'Toye` on every page; `#kc-header` is a `<header>`; the card is wrapped in `<main id="kc-main">`; the locale button loses `tabindex="1"` |
| `login.ftl` | all eight positive `tabindex` attributes removed |
| `user-profile-commons.ftl` | every profile field the register (and update-profile) page renders: the control carries `aria-required="true"` when the attribute is required and `aria-describedby` naming its error span and its helper-text spans (stock rendered both but tied neither to the field); the visible asterisk is `aria-hidden`, so a screen reader hears "required", not "star". Rendered markup is otherwise unchanged |
| `register.ftl` | the password field is `aria-required` and `aria-describedby` the password rules (`passwordPolicyHint`, shown BEFORE a failed submit); the confirm field is `aria-required` and described by its error; the privacy line (`registerPrivacyNotice`) with a link to `<app origin>/legal/privacy`, the origin taken from the storefront client's own `baseUrl` (the line is omitted when there is none) |

Every edited block is marked `JTOYE` and each file opens with `JTOYE-BASE-KEYCLOAK: <version>`.
Everything else in them is the stock file byte for byte.

**Keeping the forks from rotting** (the reason they were refused before). `scripts/check-keycloak-theme-ftl.sh`
(CI, with `--selftest`) fails when a marker differs from the Keycloak image pinned in
`docker-compose.full-stack.yml`, `infra/docker-compose*.yml` and the k8s component, so a Keycloak
bump cannot ship a stale fork. It also fails when a property the overrides exist for is dropped
(`<main>`, banner `<header>`, page-naming `<title>`, no positive tabindex, the rules, the privacy
link), when the rules sentence differs from the one DERIVED from the realms' `passwordPolicy` (every rule, not only the length; a term the sentence cannot express fails closed), when either realm
template sets a user-initiated action-token lifespan below 30 minutes, and when the k8s component
does not mount every override.

**Re-deriving after a Keycloak upgrade:**

```bash
# the new base files, straight from the image you are moving to (no stack needed)
id=$(docker create quay.io/keycloak/keycloak:<new>)
docker cp "$id:/opt/keycloak/lib/lib/main/org.keycloak.keycloak-themes-<new>.jar" /tmp/kc-themes.jar && docker rm "$id"
unzip -p /tmp/kc-themes.jar theme/base/login/template.ftl > /tmp/base-template.ftl
diff <(tail -n +9 infra/keycloak/themes/jtoye/login/template.ftl) /tmp/base-template.ftl   # only the JTOYE blocks should differ
```

Re-apply the marked blocks onto the new base, bump the marker, copy to
`k8s/components/keycloak/theme/` (`scripts/check-k8s-copy-parity.sh`), then prove it with
`frontend/e2e/keycloak-login-a11y.verify.mjs` against a throwaway container of the new image.

**Not overridden, so still the stock markup:** `login-username.ftl`, `login-password.ftl`,
`login-otp.ftl` and the rest of the stock templates carry positive `tabindex` too. None is
reachable in this realm's flows today (`login.ftl` serves the username-and-password form;
identifier-first and OTP are off). If a flow that uses one is enabled, override it the same way and
add it to `FTL` in the gate. `login-update-password.ftl` (the reset flow) also keeps the stock
markup: it has no rules sentence up front.

**Proof, and the gate that keeps it.** `frontend/e2e/keycloak-login-a11y.verify.mjs` fails on the
stock `user-profile-commons.ftl` (email, first name and last name not exposed as required, and not
naming their errors after an empty submit) and passes on the override. It runs every night in
`.github/workflows/e2e-nightly.yml` against the compose Keycloak (the only workflow with one); a
pull request that breaks the markup is caught by `scripts/check-keycloak-theme-ftl.sh`, which is
static and runs per PR. Run it by hand with `KC_BASE=http://localhost:<port>`: use the hostname the
server is configured with (`KC_HOSTNAME`), not `127.0.0.1`, or the session cookie is for another
host and the empty submit lands on "We are sorry..." instead of the form.
