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
#   scripts/staging-realm-import.sh --selftest
#
#     --render-only        write the staging realm to FILE (mode 600). Default
#                          secret values are TEST-ONLY constants; with
#                          --secrets-from-env the four vendor secrets are read
#                          from KEYCLOAK_CLIENT_SECRET, EDGE_API_CLIENT_SECRET,
#                          INTEGRATION_CATALOG_RO_SECRET and
#                          INTEGRATION_ORDERS_RW_SECRET.
#     --assert FILE        run the staging assertions on a realm JSON document.
#                          offline (default): what a render must satisfy,
#                          including built-in token survival against the
#                          template; apply: offline plus no TEST-ONLY value;
#                          live: a document reconstructed from the admin API.
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

WORKDIR=""
# An EXIT trap's own last status would otherwise become the script's status and
# turn a VOID (2) into 1: capture the incoming status first, re-assert it last.
cleanup() {
  local rc=$?
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
  ([.users[]? | (.username // "<no username>") | select(startswith("service-account-") | not)] as $u
    | if ($u | length) > 0 then "\($u | length) non-service users: \($u | join(", "))" else empty end),

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
               | if ((if $mode == "live" then ["", "**********"] else [""] end) | any(. == $sec)) then empty
                 else "google clientSecret is set; ADR-0005 ships it empty" end)
         end)
   else empty end)
] | .[]
'

# builtin_tokens FILE — the lower-case dollar-brace tokens (Keycloak built-ins),
# one per line, sorted. Text-level, so it works on the customer template too,
# which is not JSON until rendered.
builtin_tokens() { command grep -oE '\$\{[a-z][^}]*\}' "$1" | LC_ALL=C sort || true; }

template_for() { case "$1" in vendor) echo "$VENDOR_TEMPLATE" ;; customer) echo "$CUSTOMER_TEMPLATE" ;; esac; }
realm_for()    { case "$1" in vendor) echo "$VENDOR_REALM" ;; customer) echo "$CUSTOMER_REALM" ;; esac; }

# assert_realm FILE KIND MODE — prints one DEFECT line per failed check on
# stdout; returns 0 (pass), 1 (defects) or 2 (could not evaluate).
assert_realm() {
  local file="$1" kind="$2" mode="$3" defects rc=0 n tmpl want got
  case "$kind" in vendor|customer) ;; *) echo "USAGE ERROR: --kind must be vendor or customer" >&2; return 2 ;; esac
  case "$mode" in offline|apply|live) ;; *) echo "USAGE ERROR: --assert-mode must be offline, apply or live" >&2; return 2 ;; esac
  command -v jq >/dev/null 2>&1 || { echo "VOID: jq not on PATH" >&2; return 2; }
  [ -f "$file" ] && [ -r "$file" ] || { echo "VOID: $file is not a readable file" >&2; return 2; }
  if ! jq -e 'type == "object"' "$file" >/dev/null 2>&1; then
    echo "DEFECT: $file is not a JSON object (an unrendered template, or not a realm)"
    echo "REFUSED: $file fails the $kind staging assertions (1 defect)" >&2
    return 1
  fi
  defects=$(jq -r --arg kind "$kind" --arg host "$STAGING_HOST" --arg realm "$(realm_for "$kind")" --arg mode "$mode" \
    --arg mailpit_host "$MAILPIT_HOST" --arg mailpit_port "$MAILPIT_PORT" --arg test_prefix "$TEST_ONLY_PREFIX" \
    --argjson names "$(names_json)" "$ASSERT_PROGRAM" "$file") || rc=$?
  if [ "$rc" -ne 0 ]; then echo "VOID: the assertion program failed on $file (jq rc=$rc)" >&2; return 2; fi

  # Built-in token survival: the same multiset as the template. Not on a live
  # read-back, which is a reconstruction (realm, clients, users, profile) that
  # omits the roles and client scopes carrying most of these tokens.
  if [ "$mode" != live ]; then
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
  assert)
    [ -n "$KIND" ] || usage_error "--assert needs --kind vendor|customer"
    set +e; assert_realm "$ASSERT_FILE" "$KIND" "$ASSERT_MODE"; rc=$?; exit "$rc"
    ;;
  "")
    usage >&2; exit 2
    ;;
esac
