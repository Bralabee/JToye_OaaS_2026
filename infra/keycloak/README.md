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
"displayNameHtml" : "<span style=\"color:#3A0B0D;font-weight:700;letter-spacing:0.01em\">J'Toye</span>",
"loginTheme"      : "jtoye"
```

`displayName` supplies the page title, `displayNameHtml` the login-page header. The header value
is raw HTML rendered into the page, so it is deliberately a fixed operator-controlled literal
containing only a `<span>` and a style attribute — no script, no interpolation, and nothing
derived from request data. Do not build this value from user input. The realm's own
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
  resources/css/jtoye.css
  resources/fonts/work-sans-latin.woff2
```

**No FTL template overrides, deliberately.** Forking a template would pin us to that Keycloak
version's markup and silently rot at the next upgrade. Every rule reaches the parent theme's
existing class and id hooks instead, so an upgrade changes the markup underneath us and the
branding follows.

**`styles` must repeat the parent's stylesheet.** In `theme.properties`, `styles` *replaces* the
inherited value rather than appending to it, so it reads `css/login.css css/jtoye.css`. Dropping
the first entry renders the page unstyled — worse than the stock theme, and it fails silently.
The parent's value was read out of the shipped themes jar, not guessed. `stylesCommon` is
deliberately not restated: it is inherited, and it is what supplies PatternFly.

**Brand values are copied, not invented.** The palette comes from `frontend/tailwind.config.ts`
(oxblood, cream, gold) and the primary and radius from the CSS custom properties in
`frontend/app/globals.css`, so the login pages track the same tokens as the app. Each one is
named in a comment at the top of the stylesheet with the file it came from.

**Work Sans is self-hosted**, copied into `resources/fonts/` and declared with `@font-face`,
rather than pulled from a font CDN: a login page is precisely the surface that should not make a
third-party request, and self-hosting keeps the theme working with no outbound network and no
extra CSP or CORS surface.

**Coupled values to keep in step.** The `#kc-info` negative margins make the footer strip span the
card edge to edge, so each one must cancel the corresponding `.card-pf` padding — at **both**
breakpoints, since the padding differs between them.

This is an invariant the overlay **chooses**; it is not inherited from upstream. Measured in the
shipped themes jar, stock is `#kc-info { margin: 20px -40px -30px }` against
`.card-pf { padding: 0 20px }` — those do not cancel, so there is no stock precedent to appeal to.
(An earlier version of this note claimed stock was `-40px` against `40px` padding. That was wrong,
and it mattered: a maintainer checking the premise would have "corrected" the margin back.)

Two traps, both of which have actually fired here:

- **Use the shorthand, not longhands.** Stock's is a three-value shorthand carrying
  `margin-bottom: -30px`. Overriding only top/left/right leaves that `-30px` alive, which pulled
  the strip below the card's border box at every width — visible as a cream lip under the rounded
  corner, since nothing clips it.
- **Match the stock theme's breakpoint, which is 767px, not 480px.** Stock's phone block sets
  `max-width: none; margin-left: 0; margin-right: 0` on the card. Scoping the overlay's gutter to
  480px left 481–767px rendering the card full-bleed with rounded corners and shadow clipped flush
  at both viewport edges — a whole device class, invisible to a 390px screenshot.

**Mounting.** The theme is bind-mounted read-only at `/opt/keycloak/themes`. In the Quarkus
distribution that directory ships only a README — the built-in themes live inside a jar — so the
mount shadows nothing. All three compose files that define a Keycloak service mount it, because
the realms name `loginTheme=jtoye`; on a stack without the mount the theme name dangles and
Keycloak silently falls back.

Adding or changing the mount needs a recreate, not a restart:

```bash
docker compose -f docker-compose.full-stack.yml up -d --force-recreate --no-deps keycloak
```

Realm state survives that (it is in Postgres), but the admin CLI's cached login does not —
re-run `kcadm.sh config credentials` afterwards.

**Iterating on the CSS.** This stack runs Keycloak in `start-dev`, which does not cache themes,
so a stylesheet edit is served on the next request with no restart. Under `start` the theme cache
is on and each change needs a restart.

**Verify the theme is actually applied, by content.** A screenshot cannot distinguish a served
page from a cached one:

```bash
# Port 8085 is docker-compose.full-stack.yml and infra/docker-compose.yml.
# infra/docker-compose.hostnet.yml starts Keycloak with --http-port=8081 — use that instead there.
KC=http://localhost:8085

# the page must link the theme stylesheet ...
curl -s "<login-page-url>" | grep -o 'href="[^"]*jtoye\.css"'
# ... and that stylesheet must serve, carrying a brand value
curl -s -o /tmp/t.css -w '%{http_code}\n' "$KC/resources/<v>/login/jtoye/css/jtoye.css"
```

If `css/login.css` disappears from the page's stylesheet list, `styles` in `theme.properties` has
been shortened and the base styling is gone.

### Verifying a change to either block

Read the value back **out of the running server**, not off the rendered file — the realm is
Postgres-backed, so a correct file and a stale server is exactly the state that fools you.

Use the full representation, not a projection:

```bash
docker exec jtoye-keycloak /opt/keycloak/bin/kcadm.sh get realms/jtoye-customers | jq '.smtpServer'
```

**Do not verify a nested map with `kcadm.sh get --fields`.** That projection renders any nested
object as an empty `{ }` regardless of its real contents — `smtpServer` and
`browserSecurityHeaders` both read as empty through it while the full representation shows them
correctly populated. A `--fields` read of those keys cannot distinguish a working config from an
empty one.

Then exercise the real path, because a config read is not a delivery proof: submit "Forgot
password" for a known account and confirm a message arrives in Mailhog at
`http://localhost:8025` addressed to that account.

## Importing the Realm

### Method 1: Docker Compose (Automatic)

The realm is automatically imported when using `docker-compose.full-stack.yml`:

```bash
docker-compose -f docker-compose.full-stack.yml up
```

The `realm-export.json` file is mounted as a volume and imported on startup.

### Method 2: Manual Import via Keycloak UI

1. Start Keycloak:
   ```bash
   cd infra && docker-compose up -d keycloak
   ```

2. Access Keycloak Admin Console:
   - URL: http://localhost:8085
   - Username: `admin`
   - Password: value of `${KC_ADMIN_PASSWORD}` from your `.env`

3. Import realm:
   - Click "Add realm"
   - Click "Select file"
   - Choose `realm-export.json`
   - Click "Create"

### Method 3: Manual Import via CLI

```bash
docker exec -it jtoye-keycloak /opt/keycloak/bin/kc.sh import \
  --file /opt/keycloak/data/import/realm-export.json
```

## Replacing a realm that already exists

**`--import-realm` SKIPS a realm that already exists.** This is the single most expensive thing to
learn the hard way here, because every symptom points somewhere else: you edit the template, the
render sidecar reports success, the rendered JSON on disk is visibly correct, Keycloak starts clean
with no warning — and the running realm is unchanged. Nothing in any log says a realm was skipped.

**Dropping the `keycloak_data` volume does not help.** Keycloak is Postgres-backed here
(`KC_DB: postgres`, `KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak`). Realm state lives in the
`keycloak` database, not in that volume, so removing it is a **no-op** for this purpose.

Two routes actually work.

> **Which route: if the realm has live users, use Route 2 (Admin API).** A full
> `kc.sh import --override true` replaces the realm *wholesale*. The customer realm's
> template ships an empty `users` array, so a full import of `jtoye-customers` **deletes every
> storefront self-registration** in the running realm — every real customer account. The Admin
> API is a GET-merge-PUT: it touches only the fields you name and leaves users, clients and
> rotated secrets alone. Reserve Route 1 for a fresh or empty stack, or for the vendor realm
> whose users are all seeded from the template anyway.
>
> After any realm write, re-read the customer user list from the Admin API and diff it against
> the list you took before. A shrunk list means something did a full import.

### Route 1 — `kc.sh import --override true`, server stopped

Import writes to the database directly, so the server must not be running.

```bash
# 1. Render the template (normally done by the keycloak-realm-render sidecar)
docker compose -f docker-compose.full-stack.yml up keycloak-realm-render

# 2. Stop Keycloak — kc.sh import needs exclusive access
docker compose -f docker-compose.full-stack.yml stop keycloak

# 3. Import with --override, which is the flag --import-realm does not have.
#    NOTE the path: the keycloak service mounts the rendered realms at
#    /opt/keycloak/data/import/. There is NO /keycloak directory on this service —
#    that is the render sidecar's mount point, and a --file pointing there fails.
docker compose -f docker-compose.full-stack.yml run --rm --no-deps --entrypoint /opt/keycloak/bin/kc.sh \
  keycloak import --file /opt/keycloak/data/import/realm-export-customers.json --override true

# 4. Start it again
docker compose -f docker-compose.full-stack.yml start keycloak
```

Verify **by content**, not by "it started". Read the value back out of the running server rather than
off the disk — the rendered file being right is exactly the state that fools you:

```bash
# expects the admin CLI to be authenticated; substitute your own admin credentials
docker exec jtoye-keycloak /opt/keycloak/bin/kcadm.sh get identity-provider/instances \
  -r jtoye-customers --fields alias,enabled
```

### Route 2 — Admin API, server running

For a single identity provider this is less disruptive than a whole-realm replacement, and it is the
route to prefer when the realm has live users.

```bash
TOKEN=$(curl -s -d "client_id=admin-cli" -d "username=${KEYCLOAK_ADMIN}" \
             -d "password=${KEYCLOAK_ADMIN_PASSWORD}" -d "grant_type=password" \
             http://localhost:8085/realms/master/protocol/openid-connect/token | jq -r .access_token)

curl -s -X POST http://localhost:8085/admin/realms/jtoye-customers/identity-provider/instances \
     -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
     -d @- <<'JSON'
{ "alias": "google", "providerId": "google", "enabled": true, "trustEmail": true,
  "config": { "clientId": "...", "clientSecret": "...",
              "defaultScope": "openid email profile", "syncMode": "IMPORT" } }
JSON
```

The endpoint returns **409** if the alias already exists — `PUT .../instances/google` updates it.

### Customer realm identity providers (`jtoye-customers`)

The customer realm carries a Google identity provider that is **`enabled: false` by decision, not by
oversight** — see
[`ADR-0005`](../../docs/architecture/decisions/ADR-0005-customer-realm-identity-providers.md). Google
requires HTTPS on a resolving host for any non-`localhost` redirect URI, and no such host exists yet.

To enable it, all four of these are required and none is sufficient alone:

1. Set `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` in `.env` (documented in `.env.example`, never
   committed). Put any note on its **own line** — `VAR=  # note` is read as empty by
   `scripts/verify-env.sh` but resolves to the comment **text** in Docker Compose, so the two
   disagree about the same line and compose is the one that renders the realm.
2. Flip `enabled` to `true` in `realm-export-customers.template.json`. Editing the **template** is
   correct; `realm-export-customers.json` is a gitignored build product.
3. Register the redirect URI in the Google Cloud console, exactly:
   `{keycloak-public-url}/realms/jtoye-customers/broker/google/endpoint`. Google forbids wildcards
   and raw IPs, so it cannot be guessed later.
4. **Replace the realm** using one of the two routes above. Without this the first three changes
   reach nothing.

`scripts/verify-env.sh` reads the enabled flag out of the template and starts requiring both
variables the moment step 2 lands, so a half-done enablement fails preflight rather than booting a
provider with an empty client id.

## Staging realm (Kubernetes)

Plan 29-04 (Blocker H, D-02, D-20, D-27; #299, #859, #296). The staging Keycloak runs in-cluster
(namespace `jtoye-staging`, Service `keycloak:8080`, management port 9000) and serves a PUBLIC host,
so the dev-shaped template cannot be imported as it is: it names realm `jtoye-dev`, gives `core-api`
localhost redirect URIs and `webOrigins: ["*"]`, seeds four users that share one templated
password, and sends mail to Mailhog without TLS.

**One template, one transform, no second realm file.** `staging-realm.jq` derives both staging
realms from the templates in this directory:

| | Vendor (`jtoye-staging`) | Customer (`jtoye-customers`) |
|---|---|---|
| Users | service-account-* only (D-27: no committed credential signs in to a public host) | none (the template ships none) |
| URIs | `core-api` redirect `https://app-staging.olajay.co.uk/*`, origin `https://app-staging.olajay.co.uk`, post-logout the same; `edge-api` and both `integration-*` none | `storefront-client` the same host |
| Clients | `integration-catalog-ro` / `-orders-rw` disabled, not deleted (no consumer: #301) | — |
| SMTP | Mailpit `mailpit.jtoye-staging.svc.cluster.local:1025`, `starttls: "true"` (D-20) | the same |
| Other | `core-api` service account off (D-02); `tenant_id` a managed admin-only attribute | `verifyEmail: true` (#859); Google IdP disabled, empty credentials, dev `_note_*` prose not copied (ADR-0005) |

It rewrites only the named realm paths (`/realms/jtoye-dev/`, `/admin/jtoye-dev/`) and the default
role name; any OTHER dev-shaped string is left for the assertion suite to refuse, never silently
renamed. It touches no dollar-brace token: the lower-case ones are Keycloak built-ins and must
survive byte for byte.

**Imported by `scripts/staging-realm-import.sh`, never by `--import-realm`.** The templates live
outside `k8s/`, where kustomize's load restrictor cannot read them, so an in-pod import would need a
second committed copy; the customer template is not JSON until rendered; and the proof that matters
is what Keycloak holds. The script:

```bash
scripts/staging-realm-import.sh --render-only --kind vendor --out /tmp/x.json   # TEST-ONLY secrets
scripts/staging-realm-import.sh --assert /tmp/x.json --kind vendor              # the assertion suite
scripts/staging-realm-import.sh --list-kv-objects                               # what 29-13 must generate
scripts/staging-realm-import.sh --selftest                                      # every arm, PASS/FAIL
# the live import (plan 29-14), from a shell that exports JTOYE_AZURE_SUBSCRIPTION_ID:
scripts/staging-realm-import.sh --apply --context <the staging context>
```

`--apply` runs `staging_kube_guard` (`scripts/lib/staging-kube-guard.sh`) BEFORE it sources the Azure
guard library: no context, the employer's `sipbihs2aks`, an unreadable identity file
(`k8s/staging/azure-identity.yaml`) or a context whose API server is not
`https://<aks_api_server_fqdn>:443` is refused before any network call. It then reads the four client
secrets and the master admin credentials from Key Vault `jtoye-stg-kv` through `az_read` (values in
variables and mode-600 files only, never argv, never printed), renders and asserts both realms, and
only then opens `kubectl port-forward svc/keycloak 18080:8080 19000:9000`.

**The admin API is reached only through that port-forward** (`http://127.0.0.1:18080`), never the
public auth host. The public ingress (plan 29-10, not yet built) is to route only `/realms/jtoye-staging`,
`/realms/jtoye-customers` and `/resources/`; the master realm, and with it the admin token
endpoint and the admin console, is not exposed (#296, T-29-14).

**The assertion suite** (`--assert`) is the same program for a rendered file and for the live
realm. It refuses: the wrong realm name; any non-service user; `webOrigins` `*` on any client; any
redirect URI off `https://app-staging.olajay.co.uk/`; any string containing `localhost`,
`jtoye.local`, `mailhog` or `jtoye-dev`; SMTP that is not Mailpit with STARTTLS; a surviving
substitution-list placeholder or any other non-built-in dollar-brace token; a changed set of
built-in tokens; `core-api` with a service account; a missing or user-editable `tenant_id`; and,
for the customer realm, `verifyEmail` false or an enabled or credentialed Google IdP. In mode
`apply` it also refuses the TEST-ONLY values an offline render carries, so an offline file can
never be imported. After the import, `--apply` reads the realm back (`GET /admin/realms/NAME`,
`/clients`, `/users`, `/users/profile`, `/identity-provider/instances`) and runs the SAME suite on
that reconstruction: a realm is proven by what Keycloak returns, not by the file it was sent.

**An existing realm is skipped, not replaced.** A skipped realm is still read back and asserted, in
mode `live-existing`, where users created after the import (the 29-06 test vendor, storefront
registrations) are allowed and the template's seed users never are. **`--recreate` DELETES the
realm first, and every user in it**: on the vendor realm that includes the staging test vendor
(re-created by plan 29-06's seed), on the customer realm every storefront registration.

## Exporting Realm Configuration

To export the current realm configuration (e.g., after making changes via UI):

```bash
# Export from running Keycloak container
docker exec jtoye-keycloak /opt/keycloak/bin/kc.sh export \
  --realm jtoye-dev \
  --file /tmp/realm-export.json

# Copy from container to host
docker cp jtoye-keycloak:/tmp/realm-export.json ./infra/keycloak/realm-export.json
```

**Important:** Always export after making configuration changes to keep this file up-to-date.

## Security Notes

### Development vs Production

**This configuration is for DEVELOPMENT ONLY:**
- ❌ Weak passwords — never use values like `password123` or `admin123`; supply strong rotated secrets via `.env`
- ❌ SSL not required (set to `external`)
- ❌ Client secrets in version control
- ❌ Permissive CORS settings
- ❌ Long token lifespans (3600s access tokens)

**For PRODUCTION, you MUST:**
1. ✅ Use strong, unique passwords
2. ✅ Enable SSL/TLS (`sslRequired: all`)
3. ✅ Store client secrets in secrets manager (Vault, AWS Secrets Manager)
4. ✅ Configure strict CORS policies
5. ✅ Reduce token lifespans (recommend 300s for access tokens)
6. ✅ Enable MFA/2FA for admin accounts
7. ✅ Configure proper redirect URI whitelist
8. ✅ Enable rate limiting and brute force detection
9. ✅ Regular security audits and updates

### Client Secrets

**Client secrets are never committed.** They are rendered into the gitignored
`realm-export.json` at container start from environment variables in `.env`:
- `core-api`: `${KEYCLOAK_CLIENT_SECRET}`
- `edge-api`: `${EDGE_API_CLIENT_SECRET}`
- `integration-catalog-ro`: `${INTEGRATION_CATALOG_RO_SECRET}`
- `integration-orders-rw`: `${INTEGRATION_ORDERS_RW_SECRET}`

Generate fresh strong values (e.g. `openssl rand -hex 32`) per environment and store them securely.

The full rotation procedure — including the superseded-fails/current-succeeds acceptance arm
each secret must pass on the RUNNING realm — is `docs/runbooks/credential-rotation.md`.

### Realm import provenance

**2026-08-10 — plan 28-10 (SEC-04 / #552 / D-02 + #551 / D-12).** A single
`kc.sh import --file .../realm-export.json --override true` (server stopped, then restarted)
carried **two payloads in one import event**:

1. **D-02 rotation** — the four rotated `jtoye-dev` client secrets above, re-rendered from `.env`.
2. **D-12 audience decision** — the unused public client staged out by plan 28-05
   (`realm-export.template.json`); the import took the running realm from 11 clients to 10.

The running realm's client secrets therefore differ from any value read before 2026-08-10.
Because the realm is Postgres-backed (`KC_DB: postgres`), **editing the template alone changes
nothing** — the change reaches the running realm only through that import plus a restart, and is
verified by a token request per client, never by reading the rendered file. See
`docs/runbooks/credential-rotation.md` §3.

## Token Claims

JWT tokens issued by this realm include:

```json
{
  "exp": 1234567890,
  "iat": 1234567890,
  "jti": "uuid",
  "iss": "http://localhost:8085/realms/jtoye-dev",
  "aud": "account",
  "sub": "user-uuid",
  "typ": "Bearer",
  "azp": "core-api",
  "tenant_id": "00000000-0000-0000-0000-000000000001",
  "preferred_username": "tenant-a-user",
  "email_verified": false,
  "email": "user@example.com"
}
```

The `tenant_id` claim is used by the backend for multi-tenant data isolation via RLS.

## Troubleshooting

### Realm Import Fails

**Problem:** Keycloak won't import realm
**Solution:**
1. Check file is valid JSON: `jq . realm-export.json`
2. Ensure Keycloak version compatibility (export/import on same version)
3. Check Keycloak logs: `docker logs jtoye-keycloak`

### User Can't Login

**Problem:** Authentication fails for test users
**Solution:**
1. Verify user exists in Keycloak UI
2. Check the password matches `${KC_SEED_USER_PASSWORD}` from your `.env`
3. Ensure user is enabled (not disabled)
4. Check group membership for tenant users

### tenant_id Not in JWT

**Problem:** JWT token doesn't contain tenant_id claim
**Solution:**
1. Verify user is member of tenant group (tenant-a or tenant-b)
2. Check group has tenant_id attribute set
3. Verify protocol mapper is configured correctly
4. Check mapper is enabled for the client

### Token has no `sub` claim (403 on every vendor write, kitchen stuck on "Reconnecting")

**Problem:** `jwt.getSubject()` is null; the Staff page tells a Group admin only a Group admin can
invite; core-java logs `subscriber subject is not a UUID`.
**Cause:** the realm was imported fresh from a template without the `basic` client scope (see
"The `basic` client scope" above). Keycloak 25+ only adds it on an in-place database migration.
**Solution:** re-import the current template (it declares the scope), or in the admin console add
the `basic` scope to the realm's default client scopes and to each client's default scopes.

### Token Validation Fails

**Problem:** Backend rejects valid tokens
**Solution:**
1. Check issuer URI matches: `http://localhost:8085/realms/jtoye-dev`
2. Verify client ID matches (`core-api`)
3. Check clock sync between services
4. Verify token hasn't expired (check `exp` claim)

## Version History

- **v0.7.0** (2025-12-31): Initial realm export to version control
  - clients configured (this entry once read "core-api, frontend"; no tracked realm file has ever
    held a `frontend` client, see "Realm Configuration Details")
  - 3 test users with tenant assignments
  - Protocol mappers for tenant_id injection
  - Development-ready configuration

## Related Documentation

- [Main Setup Guide](../../docs/setup/SETUP.md)
- [Credentials Document](../../docs/CREDENTIALS.md)
- [Docker Quick Start](../../docs/guides/DOCKER_QUICK_START.md)
- [Keycloak Official Docs](https://www.keycloak.org/documentation)
