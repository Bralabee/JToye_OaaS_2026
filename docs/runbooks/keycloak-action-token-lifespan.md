# Keycloak reset and verification links: rolling the new lifespans (and the FTL theme) out to staging

Issues #847 (reset and verification links expired after 5 minutes) and #848 (Keycloak sign-in and
register pages below the accessibility bar). **An owner or cluster session applies this; nothing
here has been run against staging.**

Two different things change, and they reach staging by different routes:

| Change | Where it lives | How it reaches staging |
|---|---|---|
| Three FTL theme overrides (`template.ftl`, `login.ftl`, `register.ftl`), the two new messages, the CSS | `infra/keycloak/themes/jtoye/login/`, copied to `k8s/components/keycloak/theme/` | The `keycloak-theme` ConfigMap. Its name carries a content hash, so applying the staging manifests rolls the Keycloak pod onto the new theme. No realm change, no kcadm. |
| `actionTokenGeneratedByUserLifespan` = 3600, `actionTokenGeneratedByUserLifespan.verify-email` = 86400 | the realm templates | **Not** by a deploy. Keycloak is Postgres-backed: an existing realm keeps its old values. Apply with the admin CLI below. |

## What the realm values mean

- `actionTokenGeneratedByUserLifespan` is the lifetime of a link a USER asked for ("Forgot
  password?"). It was Keycloak's default, 300 s (the vendor template carried an explicit 300). New
  value 3600 (one hour).
- `actionTokenGeneratedByUserLifespan.verify-email` is the per-action override for the
  verification link emailed after registration. New value 86400 (24 hours).
- Left alone on purpose: `accessCodeLifespanUserAction` (the five minutes a person has to finish
  the form after clicking the link) and `actionTokenGeneratedByAdminLifespan` (already 12 h).

## Apply to an existing realm

Use `kcadm.sh update`, not a re-import: a full re-import (`staging-realm-import.sh --apply
--recreate`) replaces the realm wholesale, which on `jtoye-customers` deletes every storefront
self-registration and on `jtoye-staging` deletes the staging test vendor. The update writes only
the named fields and takes effect on the next request; no restart.

The script signs in to the master realm with the admin credentials the Keycloak container
already holds, so no password is typed or passed on a command line. Arguments: the realms to
update (staging has `jtoye-staging` and `jtoye-customers`).

Mint the kubelogin token for the JToye subscription first (see the staging kubectl reference),
never the employer's default subscription. Context and namespace are the ones
`scripts/staging-realm-import.sh` uses.

```bash
kubectl --context jtoye-stg-aks -n jtoye-staging exec -i deploy/keycloak -- \
  bash -s -- jtoye-staging jtoye-customers <<'KCADM'
set -euo pipefail
cfg="/tmp/kcadm-lifespan.$$"
kc=/opt/keycloak/bin/kcadm.sh
trap 'rm -f "$cfg"' EXIT
"$kc" config credentials --config "$cfg" --server http://localhost:8080 --realm master \
  --user "${KC_BOOTSTRAP_ADMIN_USERNAME:-$KEYCLOAK_ADMIN}" \
  --password "${KC_BOOTSTRAP_ADMIN_PASSWORD:-$KEYCLOAK_ADMIN_PASSWORD}"
for realm in "$@"; do
  "$kc" update "realms/$realm" --config "$cfg" -s actionTokenGeneratedByUserLifespan=3600
  if [ "$realm" = "jtoye-customers" ]; then
    # The customer realm verifies email by link: 24 h. The dotted key needs the inner quotes.
    "$kc" update "realms/$realm" --config "$cfg" -s 'attributes."actionTokenGeneratedByUserLifespan.verify-email"=86400'
  fi
  # Read back from the realm itself (kcadm --fields cannot filter the attributes map).
  rep=$("$kc" get "realms/$realm" --config "$cfg")
  echo "== $realm"
  grep -E '"actionTokenGeneratedByUserLifespan' <<<"$rep"
  grep -qE '"actionTokenGeneratedByUserLifespan" : 3600' <<<"$rep" || { echo "FAIL: $realm lifespan not 3600" >&2; exit 1; }
  if [ "$realm" = "jtoye-customers" ]; then
    grep -qE '"actionTokenGeneratedByUserLifespan.verify-email" : "86400"' <<<"$rep" || { echo "FAIL: $realm verify-email not 86400" >&2; exit 1; }
  fi
done
KCADM
```

Each realm prints its lifespan lines and the script exits non-zero if the read-back is not what it
set. Do not use `-s 'attributes={...}'` for the override: that form replaces the whole attributes
map. The quoted-dotted form above adds one key and keeps the rest (proven below).

### Local compose stack

Save the lines between the `KCADM` markers as `/tmp/kcadm-lifespan.sh`, then:

```bash
docker exec -i jtoye-keycloak bash -s -- jtoye-dev jtoye-customers < /tmp/kcadm-lifespan.sh
```

A fresh stack (empty `keycloak` database) imports the rendered templates and needs no update.
Re-render so the gitignored `infra/keycloak/realm-export*.json` match the templates:
`docker compose -f docker-compose.full-stack.yml up keycloak-realm-render`.

### Production

Not in this repository. Run the same script against the production Keycloak from a production
operator session.

## Prove it from the outside

After the update and after the pod has rolled onto the new theme:

1. Ask for a reset link for a throwaway customer and open the email. It should say the link
   expires within 1 hour, not 5 minutes; the `key=` query value is a JWT whose `exp` is about
   an hour after the message's send time.
2. Register a throwaway customer; the verification email should say 1 day.
3. The pages: `KC_BASE=https://auth-staging.olajay.co.uk APP_ORIGIN=https://app-staging.olajay.co.uk
   node frontend/e2e/keycloak-login-a11y.verify.mjs` from a checkout with `npm ci` done in
   `frontend/`. Exit 0 means sign-in, register (including after an empty submit) and
   forgot-password each have `lang`, one `<main>`, no positive tabindex, zero axe violations at
   WCAG 2.2 AA plus best-practice, and the register page shows the password rules and a privacy
   link to `https://app-staging.olajay.co.uk/legal/privacy`. It does not create an account.

**Why this is required before the next `--apply`:** the assertion suite of
`scripts/staging-realm-import.sh` now refuses a realm whose `actionTokenGeneratedByUserLifespan`
is below 1800 s or, for the customer realm, whose verify-email override is below 43200 s, on the
rendered file and on the live read-back. An `--apply` against realms imported before this change
ends `FAIL: the LIVE ... realm fails its staging assertions` and names the field. That failure is
correct: it reports a realm that is still stale. Run the update above, then re-run `--apply`.

## How this was proven (and what was not)

All on throwaway `quay.io/keycloak/keycloak:26.8.1` containers (plain `docker run`, ephemeral
loopback ports, a throwaway Mailhog), evidence under
`.planning/quick/261104-auth/evidence/`:

- **Lifespans, from the real emails.** The link inside each email was decoded and its `exp`
  compared with the message time. Template and theme from `origin/main`: reset 5 min, verify
  5 min, both emails say "5 minutes". The branch: reset 60 min, verify 1440 min, the emails say
  "1 hour" and "1 day" (`847-mail-lifetime-red.txt`, `847-mail-lifetime-green.txt`). That the
  per-action attribute key is spelled right is proven by the 1440, not by a read-back.
- **This script, on an existing realm.** Run against a realm imported with the OLD template: it
  ended `3600` and `"86400"`, read back again through the admin REST API, and a pre-set custom
  attribute survived (`847-kcadm-on-existing-realm.txt`).
- **The pages.** `keycloak-login-a11y.verify.mjs` failed 12 checks on the old theme and passes
  on the new one (`848-axe-red.txt`, `848-axe-green.txt`).
- **Not run on staging itself.** Whether `/tmp` is writable in the staging Keycloak pod, and
  whether the bootstrap admin still signs in there, were not checked from this repository (the
  same caveat as `keycloak-wordmark-home.md`). If `/tmp` is read-only, point `cfg` at any writable
  path.
