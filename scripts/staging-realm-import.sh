#!/usr/bin/env bash
# staging-realm-import.sh — derive, check and import the two STAGING Keycloak
# realms from the ONE committed template each (Phase 29, plan 29-04; DPLY-01,
# DPLY-02 #299 / #296; decisions D-02, D-20, D-27; Blocker H).
#
# WHY A SCRIPT AND NOT --import-realm IN THE POD
#   1. The templates live in infra/keycloak/, outside k8s/. Kustomize's load
#      restrictor will not read them, so an in-pod import would need a SECOND
#      committed copy of the realm, which is exactly the drift Blocker H is about.
#   2. The customer template is not valid JSON until its fragment placeholders
#      are substituted (an unquoted boolean, two array bodies), and Keycloak's own
#      env-placeholder resolution says nothing about unset variables or about
#      coexisting with its i18n dollar-brace tokens. The compose stack protects
#      those tokens with an explicit envsubst list for the same reason.
#   3. The proof that matters is what Keycloak HOLDS. After an import this script
#      reads the realm back from the admin API and runs the SAME assertions on
#      that read-back.
#   Keycloak is Postgres-backed, so an imported realm persists across restarts.
#
# WHAT A STAGING REALM IS (infra/keycloak/staging-realm.jq does the transform)
#   vendor   realm jtoye-staging; no users but service-account-*; core-api
#            redirectUris exactly [https://app-staging.olajay.co.uk/*] and
#            webOrigins exactly [https://app-staging.olajay.co.uk]; no client
#            with webOrigins "*"; no string containing localhost, jtoye.local,
#            mailhog or jtoye-dev; SMTP to the in-cluster Mailpit with STARTTLS;
#            core-api serviceAccountsEnabled false (D-02); tenant_id a managed,
#            admin-only user-profile attribute.
#   customer realm jtoye-customers with the same SMTP, storefront-client URIs on
#            the staging app host, verifyEmail true (#859), the Google IdP
#            disabled with empty credentials (ADR-0005).
#
# SECRETS
#   The four vendor client-secret placeholders are replaced, as WHOLE strings,
#   from environment variables (never argv, never printed). An offline render
#   uses fixed TEST-ONLY values; the apply-mode assertions refuse any of them.
#   ${KC_SEED_USER_PASSWORD} appears only on the seed users, which the
#   transform removes; if it survives, the assertions refuse the file.
#
# USAGE
#   scripts/staging-realm-import.sh --render-only --kind vendor|customer --out FILE
#                                   [--secrets-from-env]
#   scripts/staging-realm-import.sh --assert FILE --kind vendor|customer
#                                   [--assert-mode offline|apply|live]
#   scripts/staging-realm-import.sh --list-kv-objects
#   scripts/staging-realm-import.sh --apply --context NAME [--identity-file PATH]
#                                   [--recreate]
#   scripts/staging-realm-import.sh --selftest
#
#     --list-kv-objects    the Key Vault object names --apply reads, one per line,
#                          C-locale sorted (plan 29-13's writer generates each).
#     --apply              import both realms into the in-cluster Keycloak:
#                          1. staging_kube_guard (scripts/lib/staging-kube-guard.sh,
#                             plan 29-03) on --context and the identity file
#                             (default k8s/staging/azure-identity.yaml), BEFORE the
#                             Azure guard library is even sourced;
#                          2. az_guard_init (scripts/lib/azure-guard.sh); the caller
#                             exports JTOYE_AZURE_SUBSCRIPTION_ID, this script never
#                             sets it; every Key Vault read goes through az_read;
#                          3. render and --assert (mode apply) both realms; a file
#                             that fails is never imported;
#                          4. kubectl port-forward svc/keycloak 18080:8080 and
#                             19000:9000 (KC 26 health is on the management port);
#                             the admin API is ONLY ever http://127.0.0.1:18080,
#                             never the public auth host (T-29-14);
#                          5. per realm: absent -> POST; present -> skipped with a
#                             notice; present with --recreate -> DELETE then POST;
#                          6. read the realm back from the admin API and run the
#                             SAME assertions on the reconstruction (mode live, or
#                             live-existing for a realm that was skipped).
#     --recreate           DELETES an existing realm first, and with it every user
#                          in it: the vendor realm loses the test vendor (plan
#                          29-06's seed recreates it), the customer realm loses
#                          every storefront registration.
#     --render-only       write the staging realm to FILE (mode 600). Default
#                          secret values are TEST-ONLY constants; with
#                          --secrets-from-env the four vendor secrets are read
#                          from KEYCLOAK_CLIENT_SECRET, EDGE_API_CLIENT_SECRET,
#                          INTEGRATION_CATALOG_RO_SECRET and
#                          INTEGRATION_ORDERS_RW_SECRET.
#     --assert FILE        run the staging assertions on a realm JSON document.
#                          offline (default): what a render must satisfy,
#                          including built-in token survival against the
#                          template; apply: offline plus no TEST-ONLY value;
#                          live: a document reconstructed from the admin API;
#                          live-existing: live, but users created after the
#                          import are allowed unless they are one of the
#                          template's seed users (a committed credential).
#     --selftest           one line per arm, PASS <arm> or FAIL <arm> <reason>;
#                          STAGING_REALM_SELFTEST_FORMAT=tap renders TAP 13.
#
# EXIT CODES: 0 = done / assertions pass, 1 = assertions failed or refused,
#             2 = usage / tooling / VOID (could not evaluate).

set -euo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(dirname "$SCRIPT_PATH")"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

VENDOR_TEMPLATE="$REPO_ROOT/infra/keycloak/realm-export.template.json"
CUSTOMER_TEMPLATE="$REPO_ROOT/infra/keycloak/realm-export-customers.template.json"
TRANSFORM="$REPO_ROOT/infra/keycloak/staging-realm.jq"

STAGING_HOST="app-staging.olajay.co.uk"
VENDOR_REALM="jtoye-staging"
CUSTOMER_REALM="jtoye-customers"
MAILPIT_HOST="mailpit.jtoye-staging.svc.cluster.local"
MAILPIT_PORT="1025"
TEST_ONLY_PREFIX="TEST-ONLY-NOT-A-SECRET-"

# Whole-string placeholders the vendor render substitutes from the environment.
VENDOR_SECRET_PLACEHOLDERS=(KEYCLOAK_CLIENT_SECRET EDGE_API_CLIENT_SECRET INTEGRATION_CATALOG_RO_SECRET INTEGRATION_ORDERS_RW_SECRET)
# Every name either template's substitution list carries. A surviving one is a
# refusal; KC_SEED_USER_PASSWORD is listed so a seed user that escaped the
# transform is named by its password token too.
SUBSTITUTION_LIST=(KEYCLOAK_CLIENT_SECRET EDGE_API_CLIENT_SECRET INTEGRATION_CATALOG_RO_SECRET INTEGRATION_ORDERS_RW_SECRET KC_SEED_USER_PASSWORD CUSTOMER_STOREFRONT_REDIRECT_URIS CUSTOMER_STOREFRONT_WEB_ORIGINS CUSTOMER_VERIFY_EMAIL GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET)

# Placeholder (or admin variable) -> Key Vault object. Plan 29-13's writer
# generates every object named here; KEYCLOAK_CLIENT_SECRET is the SAME object
# the frontend's SecretProviderClass reads (plan 29-12), so NextAuth and the
# realm always hold one value.
KEY_VAULT_NAME="jtoye-stg-kv"
KV_MAP=(
  "KEYCLOAK_CLIENT_SECRET=keycloak-credentials--frontend-client-secret"
  "EDGE_API_CLIENT_SECRET=keycloak-realm--edge-api-client-secret"
  "INTEGRATION_CATALOG_RO_SECRET=keycloak-realm--integration-catalog-ro-secret"
  "INTEGRATION_ORDERS_RW_SECRET=keycloak-realm--integration-orders-rw-secret"
  "KC_ADMIN_USERNAME=keycloak-credentials--admin-username"
  "KC_ADMIN_PASSWORD=keycloak-credentials--admin-password"
)

NAMESPACE="jtoye-staging"
# The admin API through the port-forward, and nowhere else (T-29-14).
ADMIN_BASE="http://127.0.0.1:18080"
MGMT_BASE="http://127.0.0.1:19000"
PF_PORTS=("18080:8080" "19000:9000")
READY_DEADLINE_SECONDS="${STAGING_REALM_READY_DEADLINE_SECONDS:-300}"
POLL_SECONDS="${STAGING_REALM_POLL_SECONDS:-2}"

WORKDIR=""
PF_PID=""
# An EXIT trap's own last status would otherwise become the script's status and
# turn a VOID (2) into 1: capture the incoming status first, re-assert it last.
# The port-forward is killed by the PID captured when it started, never by a
# pattern (a pattern would also match the shell running this script).
cleanup() {
  local rc=$?
  if [ -n "${PF_PID:-}" ]; then
    kill "$PF_PID" 2>/dev/null || true
    wait "$PF_PID" 2>/dev/null || true
  fi
  if [ -n "${WORKDIR:-}" ] && [ -d "${WORKDIR:-}" ]; then
    rm -rf "$WORKDIR"
  fi
  exit "$rc"
}
trap cleanup EXIT

void()   { echo "VOID: $*" >&2; exit 2; }
usage_error() { echo "USAGE ERROR: $*" >&2; exit 2; }

need_tool() { command -v "$1" >/dev/null 2>&1 || void "$1 not on PATH"; }

# One private temp dir per run, mode 700, removed by the EXIT trap.
ensure_workdir() {
  if [ -z "$WORKDIR" ]; then
    WORKDIR=$(umask 077 && mktemp -d) || void "mktemp -d failed"
    chmod 700 "$WORKDIR"
  fi
}

names_json() {
  local n out="["
  for n in "${SUBSTITUTION_LIST[@]}"; do out+="\"$n\","; done
  printf '%s' "${out%,}]"
}

# ---------------------------------------------------------------------------
# Render
# ---------------------------------------------------------------------------

# The vendor substitution: a string EXACTLY equal to ${NAME} for a name in the
# secret list becomes the environment's value of NAME. Never a text substitution,
# so a built-in like ${role_offline-access} cannot be touched by construction.
SUBST_PROGRAM='walk(if type == "string" then
    (. as $s | reduce $names[] as $n ($s; if . == ("${" + $n + "}") then ($ENV[$n] // error("environment variable \($n) is unset")) else . end))
  else . end)'

render_vendor_jq() {
  local out="$1" names
  names=$(printf '%s\n' "${VENDOR_SECRET_PLACEHOLDERS[@]}" | jq -R . | jq -sc .)
  jq --arg kind vendor --arg host "$STAGING_HOST" -f "$TRANSFORM" "$VENDOR_TEMPLATE" > "$WORKDIR/vendor-stage1.json" \
    || { echo "FAIL: the transform failed on $VENDOR_TEMPLATE" >&2; return 1; }
  (umask 077 && jq --argjson names "$names" "$SUBST_PROGRAM" "$WORKDIR/vendor-stage1.json" > "$out") \
    || { echo "FAIL: placeholder substitution failed" >&2; return 1; }
  rm -f "$WORKDIR/vendor-stage1.json"
}

# render_realm KIND OUT SOURCE — SOURCE is offline (TEST-ONLY values) or env.
render_realm() {
  local kind="$1" out="$2" source="$3" n v text ru wo
  need_tool jq
  [ -f "$TRANSFORM" ] || void "transform missing: $TRANSFORM"
  ensure_workdir
  case "$kind" in
    vendor)
      [ -f "$VENDOR_TEMPLATE" ] || void "template missing: $VENDOR_TEMPLATE"
      if [ "$source" = offline ]; then
        # A subshell, so the TEST-ONLY values never reach this shell's
        # environment (and never overwrite real values the caller holds).
        ( for n in "${VENDOR_SECRET_PLACEHOLDERS[@]}"; do export "$n=${TEST_ONLY_PREFIX}${n}"; done
          render_vendor_jq "$out" ) || return 1
      else
        set +x
        for n in "${VENDOR_SECRET_PLACEHOLDERS[@]}"; do
          v="${!n-}"
          if [ -z "$v" ]; then echo "REFUSED [secret-missing]: $n is unset or empty" >&2; return 1; fi
          case "$v" in "$TEST_ONLY_PREFIX"*) echo "REFUSED [test-only-secret]: $n holds a TEST-ONLY value" >&2; return 1 ;; esac
          export "${n?}"
        done
        render_vendor_jq "$out" || return 1
      fi
      ;;
    customer)
      [ -f "$CUSTOMER_TEMPLATE" ] || void "template missing: $CUSTOMER_TEMPLATE"
      # The five fragment placeholders, text-substituted with fixed staging
      # constants (the customer realm holds no secret). The jq transform then
      # sets the same fields again, so it is the authority either way.
      text=$(cat "$CUSTOMER_TEMPLATE")
      ru="\"https://${STAGING_HOST}/*\""
      wo="\"https://${STAGING_HOST}\""
      text=${text//'${CUSTOMER_STOREFRONT_REDIRECT_URIS}'/"$ru"}
      text=${text//'${CUSTOMER_STOREFRONT_WEB_ORIGINS}'/"$wo"}
      text=${text//'${CUSTOMER_VERIFY_EMAIL}'/"true"}
      text=${text//'${GOOGLE_CLIENT_ID}'/}
      text=${text//'${GOOGLE_CLIENT_SECRET}'/}
      printf '%s\n' "$text" > "$WORKDIR/customer-fragments.json"
      (umask 077 && jq --arg kind customer --arg host "$STAGING_HOST" -f "$TRANSFORM" "$WORKDIR/customer-fragments.json" > "$out") \
        || { echo "FAIL: the transform failed on $CUSTOMER_TEMPLATE" >&2; return 1; }
      rm -f "$WORKDIR/customer-fragments.json"
      ;;
    *) usage_error "--kind must be vendor or customer" ;;
  esac
  chmod 600 "$out" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# Assert
# ---------------------------------------------------------------------------

ASSERT_PROGRAM='
def client(id): [.clients[]? | select(.clientId == id)];
def jt: tojson;
. as $r
| [
  # realm name
  (if .realm != $realm then "realm is \(.realm // "<absent>"), expected \($realm)" else empty end),

  # D-27: no user a committed credential could sign in as
  # (live-existing: a realm this run did not import may hold users made since,
  #  such as the plan 29-06 test vendor; a seed user from the template never.)
  ([.users[]? | (.username // "<no username>") | select(startswith("service-account-") | not)] as $u
    | if $mode == "live-existing" then
        ([$u[] | . as $n | select($seed_users | any(. == $n))] as $s
          | if ($s | length) > 0 then "\($s | length) seed user(s) from the committed template present: \($s | join(", "))" else empty end)
      elif ($u | length) > 0 then "\($u | length) non-service users: \($u | join(", "))" else empty end),

  # T-29-13: no wildcard origin, no redirect off the staging host
  ([.clients[]? | select((.webOrigins // []) | index("*")) | .clientId] as $w
    | if ($w | length) > 0 then "webOrigins \"*\" on client(s): \($w | join(", "))" else empty end),
  ([.clients[]? | .clientId as $id | (.redirectUris // [])[]
      | select((startswith("/") or startswith("https://" + $host + "/")) | not) | "\($id) \(.)"] as $x
    | if ($x | length) > 0 then "redirect URI(s) outside https://\($host)/: \($x | join(", "))" else empty end),

  # dev-shaped strings anywhere in the document (case-insensitive)
  (["localhost", "jtoye.local", "mailhog", "jtoye-dev"][] as $p
    | [$r | paths(type == "string" and (ascii_downcase | contains($p)))] as $ps
    | if ($ps | length) > 0 then "\($ps | length) string(s) contain \"\($p)\" (first at \($ps[0] | map(tostring) | join(".")))" else empty end),

  # SMTP: the in-cluster Mailpit, STARTTLS (D-20)
  ((.smtpServer // {}) as $s
    | (if (($s.host // "") | ascii_downcase | contains("mailhog")) then "mailhog in smtpServer (host \($s.host))" else empty end),
      (if ($s.host // "") != $mailpit_host then "smtpServer host is \($s.host // "<absent>"), expected \($mailpit_host)" else empty end),
      (if (($s.port // "") | tostring) != $mailpit_port then "smtpServer port is \($s.port // "<absent>"), expected \($mailpit_port)" else empty end),
      (if (($s.starttls // "") | tostring) != "true" then "smtpServer starttls is \($s.starttls // "<absent>"), expected true (D-20)" else empty end),
      (if (($s.ssl // "false") | tostring) != "false" then "smtpServer ssl is \($s.ssl), expected false (STARTTLS on the plain port)" else empty end)),

  # every remaining dollar-brace token must be a Keycloak built-in
  ([$r | .. | strings | scan("\\$\\{[^}]*\\}")] | map(select(test("^\\$\\{[a-z]") | not)) | unique[] as $t
    | if ($names | any(. == $t[2:-1])) then "substitution-list placeholder survived: \($t)" else "non-builtin dollar-brace token: \($t)" end),

  # an offline render can never be imported
  (if $mode != "offline" then
     ([$r | paths(type == "string" and startswith($test_prefix))] as $ps
       | if ($ps | length) > 0 then "\($ps | length) TEST-ONLY render value(s) present (first at \($ps[0] | map(tostring) | join("."))); render with --secrets-from-env" else empty end)
   else empty end),

  # ---- vendor realm ----
  (if $kind == "vendor" then
     (client("core-api") as $c
       | if ($c | length) != 1 then "core-api client: \($c | length) found, expected 1"
         else $c[0] as $k
           | (if (($k.redirectUris // []) | map(ascii_downcase | contains("localhost")) | any) then "localhost in core-api redirectUris" else empty end),
             (if $k.redirectUris != ["https://\($host)/*"] then "core-api redirectUris are \($k.redirectUris | jt), expected [\"https://\($host)/*\"]" else empty end),
             (if $k.webOrigins != ["https://\($host)"] then "core-api webOrigins are \($k.webOrigins | jt), expected [\"https://\($host)\"]" else empty end),
             (if (($k.attributes // {})["post.logout.redirect.uris"] // null) != "https://\($host)/*" then "core-api post.logout.redirect.uris is \(($k.attributes // {})["post.logout.redirect.uris"] | jt), expected \"https://\($host)/*\"" else empty end),
             (if $k.serviceAccountsEnabled != false then "core-api serviceAccountsEnabled is \($k.serviceAccountsEnabled | jt), expected false (D-02)" else empty end)
         end),
     (["edge-api", "integration-catalog-ro", "integration-orders-rw"][] as $id
       | client($id) as $c
       | if ($c | length) != 1 then "\($id) client: \($c | length) found, expected 1"
         else $c[0] as $k
           | (if ($k.redirectUris // []) != [] then "\($id) redirectUris are \($k.redirectUris | jt), expected []" else empty end),
             (if ($k.webOrigins // []) != [] then "\($id) webOrigins are \($k.webOrigins | jt), expected []" else empty end),
             (if ($id != "edge-api") and ($k.enabled != false) then "\($id) is enabled; staging has no consumer for it (#301), expected enabled false" else empty end)
         end),
     ((.components["org.keycloak.userprofile.UserProfileProvider"] // []) as $up
       | if ($up | length) != 1 then "user profile: \($up | length) declarative-user-profile component(s), expected 1 (tenant_id must be a managed attribute)"
         else (try ($up[0].config["kc.user.profile.config"][0] | fromjson) catch null) as $cfg
           | if $cfg == null then "user profile config is absent or not parseable JSON"
             else ([$cfg.attributes[]? | select(.name == "tenant_id")]) as $t
               | if ($t | length) != 1 then "tenant_id is not a managed user-profile attribute (\($t | length) declarations)"
                 elif (($t[0].permissions.edit // []) | sort) != ["admin"] then "tenant_id edit permission is \($t[0].permissions.edit | jt), expected [\"admin\"] (T-37-19)"
                 elif ((($t[0].permissions.view // []) | index("admin")) == null) then "tenant_id is not admin-viewable"
                 elif ($cfg.unmanagedAttributePolicy // "") == "ENABLED" then "unmanagedAttributePolicy is ENABLED (T-37-19)"
                 else empty end
             end
         end)
   else empty end),

  # ---- customer realm ----
  (if $kind == "customer" then
     (client("storefront-client") as $c
       | if ($c | length) != 1 then "storefront-client: \($c | length) found, expected 1"
         else $c[0] as $k
           | (if $k.redirectUris != ["https://\($host)/*"] then "storefront-client redirectUris are \($k.redirectUris | jt), expected [\"https://\($host)/*\"]" else empty end),
             (if $k.webOrigins != ["https://\($host)"] then "storefront-client webOrigins are \($k.webOrigins | jt), expected [\"https://\($host)\"]" else empty end)
         end),
     (if .verifyEmail != true then "verifyEmail is \(.verifyEmail | jt), expected true (#859)" else empty end),
     ([.identityProviders[]? | select(.alias == "google")] as $g
       | if ($g | length) == 0 then empty
         else $g[0] as $i
           | (if $i.enabled != false then "google identity provider is enabled; ADR-0005 ships it disabled" else empty end),
             (if ($i.config.clientId // "") != "" then "google clientId is set; ADR-0005 ships it empty" else empty end),
             (($i.config.clientSecret // "") as $sec
               | if ((if ($mode | startswith("live")) then ["", "**********"] else [""] end) | any(. == $sec)) then empty
                 else "google clientSecret is set; ADR-0005 ships it empty" end)
         end)
   else empty end)
] | .[]
'

# builtin_tokens FILE — the lower-case dollar-brace tokens (Keycloak built-ins),
# one per line, sorted. Text-level, so it works on the customer template too,
# which is not JSON until rendered.
builtin_tokens() { command grep -oE '\$\{[a-z][^}]*\}' "$1" | LC_ALL=C sort || true; }

# The template's non-service usernames: the users a committed credential
# (KC_SEED_USER_PASSWORD) signs in as. The customer template is not JSON until
# rendered and ships no users, so it contributes none.
seed_users_json() {
  jq -c '[.users[]? | (.username // "") | select(startswith("service-account-") | not)]' "$VENDOR_TEMPLATE" 2>/dev/null || echo '[]'
}

template_for() { case "$1" in vendor) echo "$VENDOR_TEMPLATE" ;; customer) echo "$CUSTOMER_TEMPLATE" ;; esac; }
realm_for()    { case "$1" in vendor) echo "$VENDOR_REALM" ;; customer) echo "$CUSTOMER_REALM" ;; esac; }

# assert_realm FILE KIND MODE — prints one DEFECT line per failed check on
# stdout; returns 0 (pass), 1 (defects) or 2 (could not evaluate).
assert_realm() {
  local file="$1" kind="$2" mode="$3" defects rc=0 n tmpl want got
  case "$kind" in vendor|customer) ;; *) echo "USAGE ERROR: --kind must be vendor or customer" >&2; return 2 ;; esac
  case "$mode" in offline|apply|live|live-existing) ;; *) echo "USAGE ERROR: --assert-mode must be offline, apply, live or live-existing" >&2; return 2 ;; esac
  command -v jq >/dev/null 2>&1 || { echo "VOID: jq not on PATH" >&2; return 2; }
  [ -f "$file" ] && [ -r "$file" ] || { echo "VOID: $file is not a readable file" >&2; return 2; }
  if ! jq -e 'type == "object"' "$file" >/dev/null 2>&1; then
    echo "DEFECT: $file is not a JSON object (an unrendered template, or not a realm)"
    echo "REFUSED: $file fails the $kind staging assertions (1 defect)" >&2
    return 1
  fi
  defects=$(jq -r --arg kind "$kind" --arg host "$STAGING_HOST" --arg realm "$(realm_for "$kind")" --arg mode "$mode" \
    --arg mailpit_host "$MAILPIT_HOST" --arg mailpit_port "$MAILPIT_PORT" --arg test_prefix "$TEST_ONLY_PREFIX" \
    --argjson names "$(names_json)" --argjson seed_users "$(seed_users_json)" "$ASSERT_PROGRAM" "$file") || rc=$?
  if [ "$rc" -ne 0 ]; then echo "VOID: the assertion program failed on $file (jq rc=$rc)" >&2; return 2; fi

  # Built-in token survival: the same multiset as the template. Not on a live
  # read-back, which is a reconstruction (realm, clients, users, profile) that
  # omits the roles and client scopes carrying most of these tokens.
  if [ "${mode#live}" = "$mode" ]; then
    tmpl=$(template_for "$kind")
    want=$(builtin_tokens "$tmpl"); got=$(builtin_tokens "$file")
    if [ "$want" != "$got" ]; then
      n=$(LC_ALL=C comm -23 <(printf '%s\n' "$want") <(printf '%s\n' "$got") | command grep -c . || true)
      defects+="${defects:+$'\n'}built-in token count $(command grep -c . <<<"$got" || true), template $(command grep -c . <<<"$want" || true); ${n} missing (first: $(LC_ALL=C comm -23 <(printf '%s\n' "$want") <(printf '%s\n' "$got") | head -1))"
    fi
  else
    echo "NOTE: built-in token survival is checked on the rendered file; a live read-back omits roles and client scopes" >&2
  fi

  if [ -n "$defects" ]; then
    n=$(command grep -c . <<<"$defects")
    while IFS= read -r line; do echo "DEFECT: $line"; done <<<"$defects"
    echo "REFUSED: $file fails the $kind staging assertions ($n defect(s), mode $mode)" >&2
    return 1
  fi
  echo "OK: $file passes the $kind staging assertions (mode $mode)"
  return 0
}

# ===========================================================================
# --list-kv-objects / --apply
# ===========================================================================

list_kv_objects() {
  local row
  for row in "${KV_MAP[@]}"; do printf '%s\n' "${row#*=}"; done | LC_ALL=C sort
}

# kc_api METHOD PATH OUTFILE [BODYFILE] — one admin-API call through the
# port-forward. The bearer token travels in a curl config file (mode 600), never
# in argv; the body in a file, never in argv. Prints the HTTP status.
kc_api() {
  local method="$1" path="$2" out="$3" body="${4:-}" code rc=0
  if [ -n "$body" ]; then
    code=$(curl -sS -K "$WORKDIR/auth.curlrc" -X "$method" -H 'Content-Type: application/json' \
      --data-binary "@$body" -o "$out" -w '%{http_code}' "${ADMIN_BASE}${path}") || rc=$?
  else
    code=$(curl -sS -K "$WORKDIR/auth.curlrc" -X "$method" -o "$out" -w '%{http_code}' "${ADMIN_BASE}${path}") || rc=$?
  fi
  if [ "$rc" -ne 0 ]; then echo "000"; return 0; fi
  echo "$code"
}

# A fresh master-realm admin token before each realm: admin-cli tokens live 60 s
# by default, and an import can outlast one. Username and password are posted
# from mode-600 files (curl --data-urlencode name@file), never argv.
admin_token() {
  local code rc=0 token
  code=$(curl -sS -X POST -o "$WORKDIR/token.json" -w '%{http_code}' \
    --data-urlencode "grant_type=password" --data-urlencode "client_id=admin-cli" \
    --data-urlencode "username@$WORKDIR/admin-username" --data-urlencode "password@$WORKDIR/admin-password" \
    "${ADMIN_BASE}/realms/master/protocol/openid-connect/token") || rc=$?
  if [ "$rc" -ne 0 ] || [ "$code" != 200 ]; then
    rm -f "$WORKDIR/token.json"
    echo "FAIL: master admin token request returned HTTP ${code:-000} (curl rc=$rc)" >&2
    return 1
  fi
  token=$(jq -r '.access_token // empty' "$WORKDIR/token.json")
  rm -f "$WORKDIR/token.json"
  if [ -z "$token" ]; then echo "FAIL: the token response carried no access_token" >&2; return 1; fi
  (umask 077 && printf 'header = "Authorization: Bearer %s"\n' "$token" > "$WORKDIR/auth.curlrc")
}

# readback KIND REALM OUT — reconstruct a realm document from the admin API in
# the template's shape, so the SAME assertion program reads it.
readback() {
  local kind="$1" realm="$2" out="$3" code
  admin_token || return 1
  code=$(kc_api GET "/admin/realms/$realm" "$WORKDIR/rb-realm.json")
  [ "$code" = 200 ] || { echo "FAIL: read-back GET /admin/realms/$realm returned HTTP $code" >&2; return 1; }
  code=$(kc_api GET "/admin/realms/$realm/clients" "$WORKDIR/rb-clients.json")
  [ "$code" = 200 ] || { echo "FAIL: read-back GET clients returned HTTP $code" >&2; return 1; }
  code=$(kc_api GET "/admin/realms/$realm/users?max=10000&briefRepresentation=true" "$WORKDIR/rb-users.json")
  [ "$code" = 200 ] || { echo "FAIL: read-back GET users returned HTTP $code" >&2; return 1; }
  if [ "$(jq 'length' "$WORKDIR/rb-users.json")" -ge 10000 ]; then
    echo "VOID: the user read-back hit its 10000 cap, so it cannot prove what the realm holds" >&2; return 2
  fi
  if [ "$kind" = vendor ]; then
    code=$(kc_api GET "/admin/realms/$realm/users/profile" "$WORKDIR/rb-profile.json")
    [ "$code" = 200 ] || { echo "FAIL: read-back GET users/profile returned HTTP $code" >&2; return 1; }
  else
    echo '{}' > "$WORKDIR/rb-profile.json"
  fi
  if [ "$kind" = customer ]; then
    code=$(kc_api GET "/admin/realms/$realm/identity-provider/instances" "$WORKDIR/rb-idps.json")
    [ "$code" = 200 ] || { echo "FAIL: read-back GET identity-provider/instances returned HTTP $code" >&2; return 1; }
  else
    echo '[]' > "$WORKDIR/rb-idps.json"
  fi
  (umask 077 && jq -n --arg kind "$kind" \
      --slurpfile r "$WORKDIR/rb-realm.json" --slurpfile c "$WORKDIR/rb-clients.json" \
      --slurpfile u "$WORKDIR/rb-users.json" --slurpfile p "$WORKDIR/rb-profile.json" \
      --slurpfile i "$WORKDIR/rb-idps.json" '
    $r[0]
    | .clients = $c[0]
    | .users = $u[0]
    | .identityProviders = $i[0]
    | if $kind == "vendor" then
        .components = { "org.keycloak.userprofile.UserProfileProvider":
          [ { providerId: "declarative-user-profile", config: { "kc.user.profile.config": [ ($p[0] | tojson) ] } } ] }
      else . end' > "$out")
  rm -f "$WORKDIR"/rb-*.json
}

# import_one KIND REALM FILE — returns 0 imported, 3 skipped (present, no
# --recreate), 1 failed.
import_one() {
  local kind="$1" realm="$2" file="$3" code
  admin_token || return 1
  code=$(kc_api GET "/admin/realms/$realm" "$WORKDIR/probe.json")
  rm -f "$WORKDIR/probe.json"
  case "$code" in
    404) ;;
    200)
      if [ "$RECREATE" -ne 1 ]; then
        echo "NOTICE: realm $realm already exists; not replaced (re-run with --recreate to delete and re-import it). The live read-back below still asserts it."
        return 3
      fi
      if [ "$kind" = vendor ]; then
        echo "WARNING: --recreate DELETES realm $realm and every user in it, including the staging test vendor; plan 29-06's seed recreates it."
      else
        echo "WARNING: --recreate DELETES realm $realm and every user in it: every storefront registration on staging is lost."
      fi
      code=$(kc_api DELETE "/admin/realms/$realm" "$WORKDIR/delete.json")
      rm -f "$WORKDIR/delete.json"
      [ "$code" = 204 ] || { echo "FAIL: DELETE /admin/realms/$realm returned HTTP $code" >&2; return 1; }
      echo "OK: deleted realm $realm"
      ;;
    *) echo "FAIL: GET /admin/realms/$realm returned HTTP $code (expected 200 or 404)" >&2; return 1 ;;
  esac
  code=$(kc_api POST "/admin/realms" "$WORKDIR/post.json" "$file")
  rm -f "$WORKDIR/post.json"
  [ "$code" = 201 ] || { echo "FAIL: POST /admin/realms ($realm) returned HTTP $code" >&2; return 1; }
  echo "OK: imported realm $realm"
  return 0
}

apply_realms() {
  local row name obj v rc kind realm file mode t0 code

  # 1. The kube-context guard, FIRST: before the Azure library is sourced and
  #    before any network call. No copy of the check lives in this script.
  # shellcheck source=lib/staging-kube-guard.sh
  source "$SCRIPT_DIR/lib/staging-kube-guard.sh"
  staging_kube_guard "$KUBE_CONTEXT" "$IDENTITY_FILE" || exit $?
  echo "OK: context '${KUBE_CONTEXT}' passed staging_kube_guard"

  # 2. The subscription guard. JTOYE_AZURE_SUBSCRIPTION_ID is the caller's.
  echo "STEP: sourcing scripts/lib/azure-guard.sh" >&2
  # shellcheck source=lib/azure-guard.sh
  source "$SCRIPT_DIR/lib/azure-guard.sh"
  rc=0; az_guard_init >/dev/null || rc=$?
  [ "$rc" -eq 0 ] || exit "$rc"
  need_tool jq; need_tool curl
  ensure_workdir

  # 3. Key Vault reads. Values go to variables and mode-600 files only.
  set +x
  for row in "${KV_MAP[@]}"; do
    name="${row%%=*}"; obj="${row#*=}"
    rc=0; v=$(az_read keyvault secret show --vault-name "$KEY_VAULT_NAME" -n "$obj" --query value -o tsv) || rc=$?
    if [ "$rc" -ne 0 ] || [ -z "$v" ]; then
      echo "REFUSED [key-vault]: could not read $obj from $KEY_VAULT_NAME (az rc=$rc); plan 29-13 generates it" >&2
      exit 1
    fi
    case "$name" in
      KC_ADMIN_USERNAME) (umask 077 && printf '%s' "$v" > "$WORKDIR/admin-username") ;;
      KC_ADMIN_PASSWORD) (umask 077 && printf '%s' "$v" > "$WORKDIR/admin-password") ;;
      *) printf -v "$name" '%s' "$v"; export "${name?}" ;;
    esac
  done
  v=""
  echo "OK: read ${#KV_MAP[@]} Key Vault objects from $KEY_VAULT_NAME (values not shown)"

  # 4. Render and assert both realms BEFORE touching the cluster.
  for kind in vendor customer; do
    file="$WORKDIR/$kind-realm.json"
    render_realm "$kind" "$file" env || exit 1
    rc=0; assert_realm "$file" "$kind" apply || rc=$?
    [ "$rc" -eq 0 ] || { echo "REFUSED [assertions]: the rendered $kind realm fails its staging assertions; nothing was imported" >&2; exit "$rc"; }
  done

  # 5. The port-forward, PID captured at start.
  kubectl --context "$KUBE_CONTEXT" -n "$NAMESPACE" port-forward svc/keycloak "${PF_PORTS[@]}" > "$WORKDIR/port-forward.log" 2>&1 &
  PF_PID=$!
  t0=$(date +%s)
  while :; do
    if ! kill -0 "$PF_PID" 2>/dev/null; then
      echo "FAIL: the port-forward exited before Keycloak answered: $(head -c 300 "$WORKDIR/port-forward.log" | tr '\n' ' ')" >&2
      PF_PID=""
      exit 1
    fi
    code=$(curl -sS -o /dev/null -w '%{http_code}' "${MGMT_BASE}/health/ready" 2>/dev/null || true)
    [ "$code" = 200 ] && break
    if [ $(( $(date +%s) - t0 )) -ge "$READY_DEADLINE_SECONDS" ]; then
      echo "FAIL: Keycloak was not ready within ${READY_DEADLINE_SECONDS}s (last ${MGMT_BASE}/health/ready status ${code:-none})" >&2
      exit 1
    fi
    sleep "$POLL_SECONDS"
  done
  echo "OK: Keycloak ready through the port-forward (pid $PF_PID)"

  # 6. Import, then the proof: the live realm, read back and asserted.
  local failed=0
  for kind in vendor customer; do
    realm=$(realm_for "$kind"); file="$WORKDIR/$kind-realm.json"
    rc=0; import_one "$kind" "$realm" "$file" || rc=$?
    case "$rc" in
      0) mode=live ;;
      3) mode=live-existing ;;
      *) failed=1; continue ;;
    esac
    rc=0; readback "$kind" "$realm" "$WORKDIR/$kind-live.json" || rc=$?
    if [ "$rc" -ne 0 ]; then failed=1; continue; fi
    rc=0; assert_realm "$WORKDIR/$kind-live.json" "$kind" "$mode" || rc=$?
    if [ "$rc" -ne 0 ]; then
      echo "FAIL: the LIVE $realm realm fails its staging assertions (mode $mode)" >&2
      failed=1
    fi
  done
  [ "$failed" -eq 0 ] || exit 1
  echo "OK: both staging realms are imported and their live read-backs pass"
}

# ===========================================================================
# --selftest
# ===========================================================================
_ST_RESULTS=()
_st_result() { _ST_RESULTS+=("$1"); }
_st_render() {
  local i=0 r verdict rest
  if [ "${STAGING_REALM_SELFTEST_FORMAT:-plain}" = tap ]; then
    echo "TAP version 13"
    echo "1..${#_ST_RESULTS[@]}"
    for r in "${_ST_RESULTS[@]}"; do
      i=$((i + 1)); verdict="${r%% *}"; rest="${r#* }"
      if [ "$verdict" = PASS ]; then
        echo "ok $i - ${rest%% *}"
      else
        echo "not ok $i - ${rest%% *}"
        echo "  ---"
        echo "  message: '$(printf '%s' "${rest#* }" | tr "'" '"' | tr '\n' ' ')'"
        echo "  ..."
      fi
    done
  else
    for r in "${_ST_RESULTS[@]}"; do printf '%s\n' "$r"; done
  fi
}

selftest() {
  local tmp fails=0 why out rc real_bash want got
  real_bash=$(type -P bash) || { echo "VOID: bash not found" >&2; return 2; }
  command -v jq >/dev/null 2>&1 || { echo "VOID: jq not on PATH" >&2; return 2; }
  ensure_workdir
  tmp="$WORKDIR/selftest"
  mkdir -p "$tmp"

  # _st_child ARGS... — this script as a child, with none of the four secret
  # variables in its environment: the offline paths must not need them.
  _st_child() {
    ST_OUT=$(command env -u KEYCLOAK_CLIENT_SECRET -u EDGE_API_CLIENT_SECRET -u INTEGRATION_CATALOG_RO_SECRET \
      -u INTEGRATION_ORDERS_RW_SECRET -u KC_SEED_USER_PASSWORD "$real_bash" "$SCRIPT_PATH" "$@" 2>&1); ST_RC=$?
  }

  # --- raw-template: the dev template must be REFUSED, naming its defects ----
  _st_child --assert "$VENDOR_TEMPLATE" --kind vendor
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1)"
  else
    local d
    for d in "realm is jtoye-dev" "4 non-service users" "localhost in core-api redirectUris" 'webOrigins "*"' "mailhog in smtpServer"; do
      command grep -qF -- "$d" <<<"$ST_OUT" || { why="defect not named: $d"; break; }
    done
  fi
  if [ -z "$why" ]; then _st_result "PASS raw-template rc=1 defects=$(command grep -c '^DEFECT:' <<<"$ST_OUT")"
  else _st_result "FAIL raw-template $why"; fails=$((fails + 1)); fi

  # --- raw-customer-template: the customer template rendered the compose way
  #     (dev fragment values, no transform) must be REFUSED too -------------
  local ctext
  ctext=$(cat "$CUSTOMER_TEMPLATE")
  ctext=${ctext//'${CUSTOMER_STOREFRONT_REDIRECT_URIS}'/'"http://localhost:3000/*"'}
  ctext=${ctext//'${CUSTOMER_STOREFRONT_WEB_ORIGINS}'/'"http://localhost:3000"'}
  ctext=${ctext//'${CUSTOMER_VERIFY_EMAIL}'/false}
  ctext=${ctext//'${GOOGLE_CLIENT_ID}'/}
  ctext=${ctext//'${GOOGLE_CLIENT_SECRET}'/}
  printf '%s\n' "$ctext" > "$tmp/customer-dev.json"
  _st_child --assert "$tmp/customer-dev.json" --kind customer
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1)"
  else
    for d in "mailhog in smtpServer" "verifyEmail is false" "storefront-client redirectUris"; do
      command grep -qF -- "$d" <<<"$ST_OUT" || { why="defect not named: $d"; break; }
    done
  fi
  if [ -z "$why" ]; then _st_result "PASS raw-customer-template rc=1 defects=$(command grep -c '^DEFECT:' <<<"$ST_OUT")"
  else _st_result "FAIL raw-customer-template $why"; fails=$((fails + 1)); fi

  # --- transformed: both renders pass, offline ------------------------------
  why=""
  local k
  # Render both first, so a failing vendor assert cannot leave the later arms
  # without a customer render.
  for k in vendor customer; do
    _st_child --render-only --kind "$k" --out "$tmp/$k.json"
    if [ "$ST_RC" -ne 0 ]; then why="($k render) rc=$ST_RC: $(head -c 200 <<<"$ST_OUT" | tr '\n' ' ')"; rm -f "$tmp/$k.json"; fi
  done
  for k in vendor customer; do
    [ -z "$why" ] || break
    _st_child --assert "$tmp/$k.json" --kind "$k"
    if [ "$ST_RC" -ne 0 ]; then why="($k assert) rc=$ST_RC: $(command grep '^DEFECT:' <<<"$ST_OUT" | head -6 | tr '\n' ' ')"; break; fi
  done
  if [ -z "$why" ]; then
    _st_result "PASS transformed vendor+customer rc=0 users=$(jq -c '[.users[].username]' "$tmp/vendor.json")"
  else _st_result "FAIL transformed $why"; fails=$((fails + 1)); fi

  # --- survived-placeholder: one ${KEYCLOAK_CLIENT_SECRET} left in place ----
  why=""
  if [ -f "$tmp/vendor.json" ]; then
    jq '(.clients[] | select(.clientId == "core-api") | .secret) = "${KEYCLOAK_CLIENT_SECRET}"' "$tmp/vendor.json" > "$tmp/vendor-survived.json"
    _st_child --assert "$tmp/vendor-survived.json" --kind vendor
    if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1)"
    elif ! command grep -qF 'substitution-list placeholder survived: ${KEYCLOAK_CLIENT_SECRET}' <<<"$ST_OUT"; then why="the surviving placeholder was not named"
    fi
  else why="no transformed vendor render to plant into"; fi
  if [ -z "$why" ]; then _st_result "PASS survived-placeholder rc=1"
  else _st_result "FAIL survived-placeholder $why"; fails=$((fails + 1)); fi

  # --- builtin-count: the template's built-ins all survive, by multiset -----
  why=""
  local counts=""
  for k in vendor customer; do
    if [ ! -f "$tmp/$k.json" ]; then why="($k) no render"; break; fi
    want=$(builtin_tokens "$(template_for "$k")"); got=$(builtin_tokens "$tmp/$k.json")
    local nw ng
    nw=$(command grep -c . <<<"$want" || true); ng=$(command grep -c . <<<"$got" || true)
    counts+=" $k=$nw/$ng"
    if [ "$nw" -eq 0 ]; then why="($k) the template holds no built-in token: the count cannot fail"; break; fi
    if [ "$want" != "$got" ]; then why="($k) template $nw, render $ng"; break; fi
    # Negative control: drop one built-in from the render; the suite must see it.
    jq --arg t "$(head -1 <<<"$want")" 'walk(if type == "string" and . == $t then "dropped" else . end)' "$tmp/$k.json" > "$tmp/$k-dropped.json"
    _st_child --assert "$tmp/$k-dropped.json" --kind "$k"
    if [ "$ST_RC" -ne 1 ] || ! command grep -qF 'built-in token count' <<<"$ST_OUT"; then
      why="($k) dropping one built-in was not detected (rc=$ST_RC)"; break
    fi
  done
  if [ -z "$why" ]; then _st_result "PASS builtin-count template/render${counts} drop-one-control=refused"
  else _st_result "FAIL builtin-count $why"; fails=$((fails + 1)); fi

  # ---- the --apply arms, against stubs -------------------------------------
  # Stub kubectl, kubelogin and curl log their argv; the az stub is the guard
  # library's own (az_guard_stub). A tools directory holds only what the script
  # needs, so no real curl, az, kubelogin or kubectl can stand in for a stub. The
  # kubectl stub hands ONLY `config` (the guard's local kubeconfig read) to the
  # real binary.
  local real_kubectl ast t p
  if ! real_kubectl=$(type -P kubectl); then
    for t in apply-no-context apply-employer-context apply-stale-server apply-kv-read-control; do
      _st_result "FAIL $t VOID: the selftest needs a real kubectl for the guard's local config view read"
    done
    _st_render
    return 2
  fi
  ast="$tmp/apply"
  mkdir -p "$ast/stub" "$ast/tools" "$ast/logs" "$ast/home"
  for t in bash env sed awk grep cat mktemp rm mkdir date tr wc head cut sort comm dirname basename sleep cp chmod jq; do
    p=$(type -P "$t") || { _st_result "FAIL apply-no-context VOID: selftest tool not found: $t"; _st_render; return 2; }
    ln -s "$p" "$ast/tools/$t"
  done
  printf '#!%s\nprintf "%%s\\n" "$*" >> "$STUB_LOG_DIR/kubectl.log"\ncase "${1:-}" in config) exec "$REAL_KUBECTL" "$@" ;; esac\nexit 0\n' "$real_bash" > "$ast/stub/kubectl"
  printf '#!%s\nprintf "%%s\\n" "$*" >> "$STUB_LOG_DIR/kubelogin.log"\nexit 0\n' "$real_bash" > "$ast/stub/kubelogin"
  # curl: logged, and it never connects (7 = could not connect).
  printf '#!%s\nprintf "%%s\\n" "$*" >> "$STUB_LOG_DIR/curl.log"\nexit 7\n' "$real_bash" > "$ast/stub/curl"
  chmod +x "$ast/stub/kubectl" "$ast/stub/kubelogin" "$ast/stub/curl"
  ( source "$SCRIPT_DIR/lib/azure-guard.sh" && az_guard_stub "$ast/stub" ) \
    || { _st_result "FAIL apply-no-context VOID: az_guard_stub failed"; _st_render; return 2; }

  local fqdn="jtoye-stg-aks-selftest.hcp.uksouth.azmk8s.io"
  cat > "$ast/kubeconfig" <<KCFG
apiVersion: v1
kind: Config
clusters:
- name: good
  cluster:
    server: https://${fqdn}:443
- name: stale
  cluster:
    server: https://stale.example:443
contexts:
- name: jtoye-stg-aks
  context: {cluster: good, user: none}
- name: jtoye-staging
  context: {cluster: stale, user: none}
- name: sipbihs2aks
  context: {cluster: good, user: none}
users:
- name: none
  user: {}
KCFG
  cat > "$ast/identity.yaml" <<IDF
apiVersion: v1
kind: ConfigMap
metadata:
  name: azure-identity
data:
  aks_api_server_fqdn: "${fqdn}"
IDF
  # Distinct, secret-looking Key Vault answers, so the control arm can prove no
  # value reaches stdout or stderr.
  : > "$ast/answers.tsv"
  local row obj
  for row in "${KV_MAP[@]}"; do
    obj="${row#*=}"
    printf '%s\t0\t%s\n' "-n ${obj} --query" "kvval-${obj}-7f3a91" >> "$ast/answers.tsv"
  done

  # _st_apply_run [VAR=value ...] -- ARGS... — the script as a scrubbed child.
  _st_apply_run() {
    local extra=()
    while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do extra+=("$1"); shift; done
    shift
    : > "$ast/logs/kubectl.log"; : > "$ast/logs/kubelogin.log"; : > "$ast/logs/curl.log"; : > "$ast/logs/az.log"
    ST_OUT=$(command env -i PATH="$ast/stub:$ast/tools" HOME="$ast/home" KUBECONFIG="$ast/kubeconfig" \
      STUB_LOG_DIR="$ast/logs" REAL_KUBECTL="$real_kubectl" AZ_STUB_LOG="$ast/logs/az.log" \
      ${extra[@]+"${extra[@]}"} "$real_bash" "$SCRIPT_PATH" "$@" 2>"$ast/err"); ST_RC=$?
    ST_ERR=$(cat "$ast/err")
  }
  _st_log_lines() { local f="$ast/logs/$1.log"; if [ -s "$f" ]; then command grep -c '' "$f"; else echo 0; fi; }
  _st_kubectl_beyond_config() { if [ -s "$ast/logs/kubectl.log" ]; then command grep -vc '^config view ' "$ast/logs/kubectl.log" || true; else echo 0; fi; }

  # _st_refusal ARM WANT_RC WANT_TEXT MAX_KUBECTL — rc, stderr tag, then the
  # logs: no az call, no curl call, no kubectl call beyond the local config read
  # (none at all when MAX_KUBECTL is 0), and the Azure library never sourced.
  _st_refusal() {
    local arm="$1" want_rc="$2" want_text="$3" max_kubectl="$4"
    if [ "$ST_RC" -ne "$want_rc" ]; then echo "rc=$ST_RC (want $want_rc): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"; return 1; fi
    if ! command grep -qF -- "$want_text" <<<"$ST_ERR"; then echo "stderr lacks '$want_text': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"; return 1; fi
    if command grep -qF 'STEP: sourcing scripts/lib/azure-guard.sh' <<<"$ST_ERR"; then echo "the Azure guard library was sourced before the refusal"; return 1; fi
    if [ "$(_st_log_lines az)" -ne 0 ]; then echo "az calls: $(_st_log_lines az)"; return 1; fi
    if [ "$(_st_log_lines curl)" -ne 0 ]; then echo "curl calls: $(_st_log_lines curl)"; return 1; fi
    if [ "$(_st_kubectl_beyond_config)" -ne 0 ]; then echo "kubectl calls beyond config view: $(_st_kubectl_beyond_config)"; return 1; fi
    if [ "$(_st_log_lines kubectl)" -gt "$max_kubectl" ]; then echo "kubectl calls=$(_st_log_lines kubectl) (max $max_kubectl)"; return 1; fi
    return 0
  }

  _st_apply_run -- --apply --identity-file "$ast/identity.yaml"
  if why=$(_st_refusal apply-no-context 2 "USAGE ERROR" 0); then
    _st_result "PASS apply-no-context rc=$ST_RC az_calls=0 curl_calls=0 kubectl_calls=0"
  else _st_result "FAIL apply-no-context $why"; fails=$((fails + 1)); fi

  _st_apply_run -- --apply --context sipbihs2aks --identity-file "$ast/identity.yaml"
  if why=$(_st_refusal apply-employer-context 1 "REFUSED [employer-context]" 0); then
    _st_result "PASS apply-employer-context rc=$ST_RC az_calls=0 curl_calls=0 kubectl_calls=0 azure_guard_sourced=no"
  else _st_result "FAIL apply-employer-context $why"; fails=$((fails + 1)); fi

  _st_apply_run -- --apply --context jtoye-staging --identity-file "$ast/identity.yaml"
  if why=$(_st_refusal apply-stale-server 1 "REFUSED [server-url]" 1); then
    _st_result "PASS apply-stale-server rc=$ST_RC az_calls=0 curl_calls=0 kubectl_calls=$(_st_log_lines kubectl)(config view) azure_guard_sourced=no"
  else _st_result "FAIL apply-stale-server $why"; fails=$((fails + 1)); fi

  # Positive control: with the guard satisfied, the SAME harness sees the Azure
  # library sourced, the pinned Key Vault reads and the port-forward, so the three
  # zeros above are not the logs failing to record. The stub port-forward exits at
  # once, so the run fails before any admin-API call; no value may be printed.
  _st_apply_run JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94 AZ_STUB_ANSWERS="$ast/answers.tsv" \
    STAGING_REALM_POLL_SECONDS=0 STAGING_REALM_READY_DEADLINE_SECONDS=5 \
    -- --apply --context jtoye-stg-aks --identity-file "$ast/identity.yaml"
  why=""
  local kv_reads pinned leaked
  kv_reads=$(command grep -c '^keyvault secret show --vault-name jtoye-stg-kv ' "$ast/logs/az.log" || true)
  pinned=$(command grep -c -- '--subscription c483d353-5f61-4587-a790-addb9ab5fb94$' "$ast/logs/az.log" || true)
  leaked=$(command grep -c 'kvval-' <<<"$ST_OUT$ST_ERR" || true)
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qF 'STEP: sourcing scripts/lib/azure-guard.sh' <<<"$ST_ERR"; then why="the Azure guard library was not reached"
  elif [ "$kv_reads" -ne "${#KV_MAP[@]}" ]; then why="Key Vault reads=$kv_reads (want ${#KV_MAP[@]})"
  elif [ "$pinned" -ne "$(_st_log_lines az)" ]; then why="$(_st_log_lines az) az calls, $pinned ending in the pin"
  elif ! command grep -qF -- '--context jtoye-stg-aks -n jtoye-staging port-forward svc/keycloak 18080:8080 19000:9000' "$ast/logs/kubectl.log"; then why="no port-forward through the named context"
  elif ! command grep -qF 'port-forward exited' <<<"$ST_ERR"; then why="the dead port-forward was not reported: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif command grep -qE '/admin/|/realms/' "$ast/logs/curl.log"; then why="an admin-API or realm call was made: $(command grep -E '/admin/|/realms/' "$ast/logs/curl.log" | head -1)"
  elif [ "$leaked" -ne 0 ]; then why="$leaked Key Vault value(s) printed"
  fi
  if [ -z "$why" ]; then
    _st_result "PASS apply-kv-read-control rc=1 az_calls=$(_st_log_lines az) kv_reads=$kv_reads pinned=$pinned port_forward=1 admin_calls=0 values_printed=0"
  else _st_result "FAIL apply-kv-read-control $why"; fails=$((fails + 1)); fi

  _st_render
  [ "$fails" -eq 0 ]
}

# ===========================================================================
# Flags. Parsed FIRST; exactly one action.
# ===========================================================================
ACTION=""
KIND=""
OUT=""
ASSERT_FILE=""
ASSERT_MODE="offline"
SECRET_SOURCE="offline"
KUBE_CONTEXT=""
IDENTITY_FILE="$REPO_ROOT/k8s/staging/azure-identity.yaml"
RECREATE=0

set_action() {
  if [ -n "$ACTION" ] && [ "$ACTION" != "$1" ]; then usage_error "--$ACTION and --$1 cannot be combined"; fi
  ACTION="$1"
}
usage() { command sed -n '/^# USAGE/,/^# EXIT CODES/p' "$SCRIPT_PATH" | command sed 's/^# \{0,1\}//'; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --render-only)      set_action render-only ;;
    --assert)           set_action assert; shift; [ "$#" -gt 0 ] || usage_error "--assert needs a file"; ASSERT_FILE="$1" ;;
    --assert-mode)      shift; [ "$#" -gt 0 ] || usage_error "--assert-mode needs a value"; ASSERT_MODE="$1" ;;
    --kind)             shift; [ "$#" -gt 0 ] || usage_error "--kind needs a value"; KIND="$1" ;;
    --out)              shift; [ "$#" -gt 0 ] || usage_error "--out needs a path"; OUT="$1" ;;
    --secrets-from-env) SECRET_SOURCE="env" ;;
    --selftest)         set_action selftest ;;
    --list-kv-objects)  set_action list-kv-objects ;;
    --apply)            set_action apply ;;
    --context)          shift; [ "$#" -gt 0 ] || usage_error "--context needs a name"; KUBE_CONTEXT="$1" ;;
    --identity-file)    shift; [ "$#" -gt 0 ] || usage_error "--identity-file needs a path"; IDENTITY_FILE="$1" ;;
    --recreate)         RECREATE=1 ;;
    -h|--help)          usage; exit 0 ;;
    *)                  echo "USAGE ERROR: unknown flag '$1'" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

case "$ACTION" in
  selftest)
    set +e; selftest; rc=$?; exit "$rc"
    ;;
  render-only)
    case "$KIND" in vendor|customer) ;; *) usage_error "--render-only needs --kind vendor|customer" ;; esac
    [ -n "$OUT" ] || usage_error "--render-only needs --out FILE"
    render_realm "$KIND" "$OUT" "$SECRET_SOURCE" || exit 1
    if [ "$SECRET_SOURCE" = offline ]; then
      echo "OK: rendered the $KIND staging realm to $OUT (TEST-ONLY secret values: assertion mode apply refuses this file)"
    else
      echo "OK: rendered the $KIND staging realm to $OUT (secrets from the environment; never commit this file)"
    fi
    ;;
  list-kv-objects)
    list_kv_objects
    ;;
  apply)
    apply_realms
    ;;
  assert)
    [ -n "$KIND" ] || usage_error "--assert needs --kind vendor|customer"
    set +e; assert_realm "$ASSERT_FILE" "$KIND" "$ASSERT_MODE"; rc=$?; exit "$rc"
    ;;
  "")
    usage >&2; exit 2
    ;;
esac
