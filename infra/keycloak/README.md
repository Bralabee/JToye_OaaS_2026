# Keycloak Configuration - JToye OaaS

## Overview

This directory contains the Keycloak realm configuration for the `jtoye-dev` realm used in development and testing.

> **Scoped machine credentials (#206 [AI-4]):** the `catalog:read`/`catalog:write` client
> scopes, the read-only `integration-catalog-ro` machine client, the per-tenant
> client-credentials recipe, and the realm re-import migration note are documented in
> [`docs/security-scopes.md`](../../docs/security-scopes.md).

## Files

- **realm-export.json**: Complete realm configuration including:
  - Clients: `core-api`, `frontend`
  - Groups: `tenant-a`, `tenant-b` with tenant_id attributes
  - Users: Pre-configured test users with tenant assignments
  - Protocol mappers: tenant_id claim injection into JWT
  - Realm settings: Token lifespans, security policies

## Realm Configuration Details

### Realm: `jtoye-dev`

**Clients:**
1. **core-api**
   - Client ID: `core-api`
   - Client Secret: rendered at import from `${KEYCLOAK_CLIENT_SECRET}` (set in `.env`; the rendered `realm-export.json` is gitignored — never committed)
   - Access Type: confidential
   - Service Accounts Enabled: true
   - Direct Access Grants: enabled
   - Valid Redirect URIs: `http://localhost:9090/*`

2. **frontend**
   - Client ID: `frontend`
   - Access Type: public
   - Direct Access Grants: enabled
   - Valid Redirect URIs:
     - `http://localhost:3000/*`
     - `http://localhost:3000/api/auth/callback/keycloak`
   - Web Origins: `http://localhost:3000`

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

## Email (SMTP) and login-page branding

Both realm templates carry an `smtpServer` block and three branding keys. They were added
together because they close the same audit finding: the customer "Forgot password" flow was a
dead end *and* an account-enumeration oracle, on pages headed with the raw realm id.

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
syntactically valid address or Keycloak refuses to send. `host` is the literal compose service
name because Keycloak and Mailhog share the same compose network, so service-name DNS resolves
from inside the Keycloak container.

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

**Staging and production do not consume these templates.** No k8s manifest mounts the rendered
realm JSONs; the overlays configure Keycloak on their own. Any SMTP or branding override for a
deployed environment is a k8s-side change, and editing these files will not reach it.

### Branding keys

```json
"displayName"     : "J'Toye",
"displayNameHtml" : "<span style=\"color:#3A0B0D;font-weight:700;letter-spacing:0.01em\">J'Toye</span>",
"loginTheme"      : "keycloak"
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

**Coupled values to keep in step.** The `#kc-info` negative margins exist to make the footer
strip span the card edge to edge, and they must equal the card's horizontal padding. The stock
theme's `-40px` matches the stock `40px` padding; this theme sets its own padding, so both the
desktop and the mobile rule restate the margin. Changing one without the other pushed the
document 21px wider than a 390px viewport.

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
# the page must link the theme stylesheet ...
curl -s "<login-page-url>" | grep -o 'href="[^"]*jtoye\.css"'
# ... and that stylesheet must serve, carrying a brand value
curl -s -o /tmp/t.css -w '%{http_code}\n' "http://localhost:8085/resources/<v>/login/jtoye/css/jtoye.css"
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

### Token Validation Fails

**Problem:** Backend rejects valid tokens
**Solution:**
1. Check issuer URI matches: `http://localhost:8085/realms/jtoye-dev`
2. Verify client ID matches (`core-api`)
3. Check clock sync between services
4. Verify token hasn't expired (check `exp` claim)

## Version History

- **v0.7.0** (2025-12-31): Initial realm export to version control
  - 2 clients configured (core-api, frontend)
  - 3 test users with tenant assignments
  - Protocol mappers for tenant_id injection
  - Development-ready configuration

## Related Documentation

- [Main Setup Guide](../../docs/setup/SETUP.md)
- [Credentials Document](../../docs/CREDENTIALS.md)
- [Docker Quick Start](../../docs/DOCKER_QUICK_START.md)
- [Keycloak Official Docs](https://www.keycloak.org/documentation)
