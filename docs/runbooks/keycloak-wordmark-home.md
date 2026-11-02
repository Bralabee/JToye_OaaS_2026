# Keycloak wordmark links home: rolling it out to an existing realm

Owner ruling 2026-11-02. On every Keycloak page (sign-in, register, reset, email
verification) the "J'Toye" wordmark in the header is now a link to the environment's public
app origin. Before this, a person on the "Email verification" page had no way back to the
site.

The header is each realm's `displayNameHtml`. The `jtoye` login theme is CSS-only and has no
FTL overrides (see `infra/keycloak/themes/jtoye/login/theme.properties`). Both templates now
carry:

```
<a href="${PUBLIC_APP_ORIGIN}" style="color:#3A0B0D;font-weight:700;letter-spacing:0.01em;text-decoration:none">J'Toye</a>
```

| Where | What fills `${PUBLIC_APP_ORIGIN}` |
|---|---|
| `docker-compose.full-stack.yml` (both realms), `infra/docker-compose.yml`, `infra/docker-compose.hostnet.yml` | the `PUBLIC_APP_ORIGIN` env var via the render sidecar's envsubst list; compose defaults it to `http://localhost:3000` |
| staging (`scripts/staging-realm-import.sh`) | `infra/keycloak/staging-realm.jq` writes `https://app-staging.olajay.co.uk` |

Keycloak's `kcSanitize` keeps the tag, the `href` and the `style`, and adds `rel="nofollow"`.
This was measured on `quay.io/keycloak/keycloak:26.8.1` on the login, register, reset and email
verification pages. The evidence is in
`.planning/quick/261102-7lu-fix-keycloak-wordmark-links-home/evidence/`.

## A changed template does not reach a running realm

Keycloak is Postgres-backed. `start-dev --import-realm` creates only realms that do not exist
yet; an existing realm logs "already exists. Import skipped". Dropping the `keycloak_data`
volume does not reset a realm either. So every environment whose realms already exist needs
the field updated in place.

**Use the admin CLI update below, not a full re-import.** `kcadm.sh update realms/<realm>`
writes only the field you name. A full `kc.sh import --override true` (or
`staging-realm-import.sh --apply --recreate`) replaces the realm wholesale. On
`jtoye-customers` that deletes every storefront self-registration, and on `jtoye-staging` it
deletes the staging test vendor. The update takes effect on the next page load; no restart is
needed.

The script below is the same on every environment. It signs in to the master realm with the
admin credentials the Keycloak container already holds in its environment, so no password is
typed or passed on the command line. Arguments: the origin, then the realms to update.

### Staging (owner or cluster session)

Context and namespace are the ones `scripts/staging-realm-import.sh` uses. Mint the kubelogin
token for the JToye subscription first (see the staging kubectl reference), never the
employer's default subscription.

```bash
kubectl --context jtoye-stg-aks -n jtoye-staging exec -i deploy/keycloak -- \
  bash -s -- https://app-staging.olajay.co.uk jtoye-staging jtoye-customers <<'KCADM'
set -euo pipefail
origin="$1"; shift
case "$origin" in http://*|https://*) ;; *) echo "origin must be http(s)://host[:port]" >&2; exit 2 ;; esac
cfg="/tmp/kcadm-wordmark.$$"
kc=/opt/keycloak/bin/kcadm.sh
trap 'rm -f "$cfg"' EXIT
"$kc" config credentials --config "$cfg" --server http://localhost:8080 --realm master \
  --user "${KC_BOOTSTRAP_ADMIN_USERNAME:-$KEYCLOAK_ADMIN}" \
  --password "${KC_BOOTSTRAP_ADMIN_PASSWORD:-$KEYCLOAK_ADMIN_PASSWORD}"
for realm in "$@"; do
  "$kc" update "realms/$realm" --config "$cfg" \
    -s "displayNameHtml=<a href=\"$origin\" style=\"color:#3A0B0D;font-weight:700;letter-spacing:0.01em;text-decoration:none\">J'Toye</a>"
  "$kc" get "realms/$realm" --config "$cfg" --fields displayNameHtml
done
KCADM
```

Each realm prints its new `displayNameHtml`. Then confirm it from the outside, on a page a
person actually sees:

```bash
curl -s 'https://auth-staging.olajay.co.uk/realms/jtoye-customers/protocol/openid-connect/auth?client_id=storefront-client&response_type=code&scope=openid&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256&redirect_uri=https%3A%2F%2Fapp-staging.olajay.co.uk%2F' \
  | tr '\n' ' ' | grep -oE '<div id="kc-header-wrapper"[^>]*>.{0,300}</a>'
```

The output should contain `<a href="https://app-staging.olajay.co.uk"`. The storefront client
requires PKCE, so the request carries a fixed S256 challenge. Without one, Keycloak redirects
away with `Missing parameter: code_challenge_method` and serves no page.

**Why this is required before the next `--apply`:** the assertion suite of
`scripts/staging-realm-import.sh` now refuses a realm whose `displayNameHtml` does not hold
`<a href="https://app-staging.olajay.co.uk"`. It checks the rendered file and the live
read-back. An `--apply` against realms imported before this change would therefore end
`FAIL: the LIVE ... realm fails its staging assertions` and name
`displayNameHtml does not link home`. That failure is correct: it reports a realm that is
still stale. Run the update above, then re-run `--apply`.

### Local compose stack

The same script, against the running `jtoye-keycloak` container. It uses the
`KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` pair that compose passes to the container. Save
the script body (the lines between the `KCADM` markers above) as
`/tmp/kcadm-wordmark.sh`, then:

```bash
docker exec -i jtoye-keycloak bash -s -- http://localhost:3000 jtoye-dev jtoye-customers < /tmp/kcadm-wordmark.sh
```

Re-rendering is still worth doing, so the gitignored `infra/keycloak/realm-export*.json` match
the templates for the next fresh database:
`docker compose -f docker-compose.full-stack.yml up keycloak-realm-render`. On its own it
changes nothing in a realm that already exists. A fresh stack (an empty `keycloak` database)
imports the rendered files and needs no update.

### Production

That realm is not in this repository. Run the same script against the production Keycloak with
the production app origin, from a production operator session.

## How this was proven

- The update script was run against a throwaway Keycloak 26.8.1 whose `jtoye-customers` realm
  had been imported with the OLD `<span>` header. Its users survived, and the login, register
  and email-verification pages then served `<a href="http://localhost:3000" ... rel="nofollow">`
  (`evidence/rollout-kcadm-on-existing-realm.txt`).
- The staging origin was set the same way and read back
  (`evidence/sanitiser-green-staging-origin-via-kcadm.txt`).
- **Not run on staging itself.** Staging needs an owner or cluster session. Whether `/tmp` is
  writable in the staging Keycloak pod, and whether the bootstrap admin still signs in there,
  were not checked from this repository. If `/tmp` is read-only, point `cfg` at any writable
  path.

## The gate

`scripts/check-keycloak-wordmark-home.sh` runs on every PR in `ci-cd.yaml`, together with its
`--selftest`. It fails when either template's `displayNameHtml` is not an anchor to
`${PUBLIC_APP_ORIGIN}`. It also fails when a compose envsubst that renders a realm omits the
variable, because the realm would then ship the literal token as its href.
