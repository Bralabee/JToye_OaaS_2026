#!/usr/bin/env bash
# staging-seed.sh — the staging seed, through the product's own APIs (Phase 29,
# plans 29-06 and 29-18; DPLY-01; decisions D-26, D-27, D-28, D-31; threats
# T-29-17..T-29-20, T-29-65..T-29-68).
#
# WHAT IT IS FOR
#   DPLY-01 needs real seeded rows, and D-26 needs every one of them made the way a
#   first tenant's would be: the seed doubles as a smoke test of the onboarding
#   paths Phase 32 needs. D-27: the DPLY-01 login is a staging-only test vendor
#   whose password lives only in Key Vault. This script contains no SQL and no
#   database client; every row is written by an HTTP call a vendor or an admin
#   could make.
#
# USAGE
#   scripts/staging-seed.sh --context NAME --stage vendor|order|all
#                           [--identity-file PATH] [--api URL] [--dry-run]
#   scripts/staging-seed.sh --list-kv-objects
#   scripts/staging-seed.sh --selftest
#
#     --context NAME     the kube context (required for every stage; no default).
#     --identity-file P  default k8s/staging/azure-identity.yaml (plan 29-09).
#     --api URL          default https://api-staging.olajay.co.uk, and it must be
#                        exactly that: any other value is REFUSED [api-base].
#     --stage vendor     the tenant, the vendor, the shop, its dishes, onboarding
#                        to LIVE (plan 29-06).
#     --stage order      one storefront guest order paid by a Stripe TEST
#                        PaymentIntent confirmed with pm_card_visa, settled by
#                        the webhook (plan 29-18, D-31). Ends on one line:
#                        SEED-ORDER order=… intent=pi_… status=… payment_status=…
#                        livemode=… at=<ISO-8601 UTC>.
#     --stage all        vendor, then order (the vendor stage's operator is
#                        deleted before the order is placed).
#     --dry-run          run every guard (they only read), then print the ordered
#                        request plan; no Azure, Keycloak or API call is made.
#     --list-kv-objects  the Key Vault object names the stages read, one per
#                        line, C-locale sorted (offline; plan 29-13 writes them).
#     --selftest         one line per arm, PASS <arm> or FAIL <arm> <reason>;
#                        STAGING_SEED_SELFTEST_FORMAT=tap renders TAP 13.
#
# EXIT CODES
#   0 done, 1 refused or failed, 2 usage error or VOID (could not evaluate).

set -euo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(dirname "$SCRIPT_PATH")"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

STAGING_API="https://api-staging.olajay.co.uk"
NAMESPACE="jtoye-staging"
REALM="jtoye-staging"
KEY_VAULT_NAME="jtoye-stg-kv"
KV_MAP=(
  "VENDOR_PASSWORD=staging-seed--test-vendor-password"
  "KC_ADMIN_USERNAME=keycloak-credentials--admin-username"
  "KC_ADMIN_PASSWORD=keycloak-credentials--admin-password"
  "CORE_API_CLIENT_SECRET=keycloak-credentials--frontend-client-secret"
)

# The staging test data. The vendor's mail is captured by Mailpit, never
# delivered (D-13, D-20).
TENANT_NAME="J'Toye Staging Test Kitchen"
VENDOR_USERNAME="vendor.staging@olajay.co.uk"
SHOP_SLUG="staging-test-kitchen"
# Sole trader (no company number): COMPANIES_HOUSE_API_KEY is not wired in k8s,
# and the BUSINESS_VERIFIED gate waives itself for a sole trader.
ONBOARDING_MODEL="MARKETPLACE"
GATE_REASON="staging seed, scripted — D-26"
# Gates the GO_LIVE guard requires PASSED on the facts; a human WAIVE never
# substitutes for them, so the seed never resolves them (it fixes the data).
NEVER_RESOLVED_GATES="TRADER_IDENTITY ALLERGEN_DATA_COMPLETE"

# The order stage (plan 29-18). The guest's mail is captured by Mailpit, never
# delivered (29-22); the phone is in Ofcom's drama range, never a real line.
STRIPE_API="https://api.stripe.com"
STRIPE_KV_OBJECT="stripe-credentials--api-key"
ORDER_IDEMPOTENCY_KEY="seed-order-staging-test-kitchen-v1"
GUEST_NAME="Staging Test Guest"
GUEST_EMAIL="guest.staging@olajay.co.uk"
GUEST_PHONE="07700 900123"
ORDER_RETURN_URL="https://app-staging.olajay.co.uk/order-complete"
PAID_DEADLINE_SECONDS="${STAGING_SEED_PAID_DEADLINE_SECONDS:-120}"

# The public issuer core-java validates; the admin API only through the
# port-forward (T-29-14), never the public auth host.
TOKEN_ENDPOINT="https://auth-staging.olajay.co.uk/realms/${REALM}/protocol/openid-connect/token"
ADMIN_BASE="http://127.0.0.1:18080"
MGMT_BASE="http://127.0.0.1:19000"
PF_PORTS=("18080:8080" "19000:9000")
READY_DEADLINE_SECONDS="${STAGING_SEED_READY_DEADLINE_SECONDS:-300}"
GATE_DEADLINE_SECONDS="${STAGING_SEED_GATE_DEADLINE_SECONDS:-180}"
POLL_SECONDS="${STAGING_SEED_POLL_SECONDS:-2}"

WORKDIR=""
PF_PID=""
OP_USERNAME=""
OP_ID=""
OP_CREATED=0

# The temporary realm-admin operator is deleted on EVERY exit path (D-27,
# T-29-17), before the port-forward it needs is killed. An operator that cannot
# be deleted turns any status into a failure: a standing admin is never "done".
# The EXIT trap's incoming status is captured first and re-asserted last, so a
# VOID (2) is not turned into 1 by the trap's own last command.
cleanup() {
  local rc=$?
  set +e
  if [ "$OP_CREATED" -eq 1 ]; then
    if ! delete_operator; then
      [ "$rc" -ne 0 ] || rc=1
    fi
  fi
  if [ -n "${PF_PID:-}" ]; then
    kill "$PF_PID" 2>/dev/null
    wait "$PF_PID" 2>/dev/null
  fi
  if [ -n "${WORKDIR:-}" ] && [ -d "${WORKDIR:-}" ]; then rm -rf "$WORKDIR"; fi
  exit "$rc"
}
trap cleanup EXIT

void()        { echo "VOID: $*" >&2; exit 2; }
usage_error() { echo "USAGE ERROR: $*" >&2; exit 2; }
refuse()      { local tag="$1"; shift; echo "REFUSED [$tag]: $*" >&2; exit 1; }
need_tool()   { command -v "$1" >/dev/null 2>&1 || void "$1 not on PATH"; }

ensure_workdir() {
  if [ -z "$WORKDIR" ]; then
    WORKDIR=$(umask 077 && mktemp -d) || void "mktemp -d failed"
    chmod 700 "$WORKDIR"
  fi
}

list_kv_objects() {
  local row
  { for row in "${KV_MAP[@]}"; do printf '%s\n' "${row#*=}"; done; printf '%s\n' "$STRIPE_KV_OBJECT"; } | LC_ALL=C sort
}

# ===========================================================================
# Guards. Every one runs before any network call.
# ===========================================================================
#
# 1. staging_kube_guard (scripts/lib/staging-kube-guard.sh, plan 29-03): the ONE
#    implementation of the context checks. No copy of them lives here.
# 2. The API base, byte for byte.
# 3. The profile guard (D-26): DemoDataSeeder is @Profile("dev")
#    (core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java:112), so the
#    seed refuses unless EVERY Ready core-java pod's own container env says
#    SPRING_PROFILES_ACTIVE=prod. The pods are read, not the Deployment template:
#    during a rollout the template describes pods that may not be serving yet,
#    while the old ones still answer. The env is a literal value
#    (k8s/base/core-java-deployment.yaml), so a pod's spec is what its process
#    runs with. Zero Ready pods is a refusal: an unverifiable profile is refused,
#    never assumed. Anything else that could add a profile (an env value or an
#    argument mentioning spring.profiles, or SPRING_PROFILES_INCLUDE) is refused
#    too, because it would make the literal check a half-truth.
run_guards() {
  # shellcheck source=lib/staging-kube-guard.sh
  source "$SCRIPT_DIR/lib/staging-kube-guard.sh"
  staging_kube_guard "$KUBE_CONTEXT" "$IDENTITY_FILE" || exit $?
  echo "OK: context '${KUBE_CONTEXT}' passed staging_kube_guard"

  if [ "$API_BASE" != "$STAGING_API" ]; then
    refuse api-base "--api '${API_BASE}' is not ${STAGING_API}; the seed writes staging test data and nothing else"
  fi

  need_tool jq
  local pods rc=0 verdict n bad
  pods=$(kubectl --context "$KUBE_CONTEXT" -n "$NAMESPACE" get pods -l app=core-java -o json 2>/dev/null) || rc=$?
  if [ "$rc" -ne 0 ] || [ -z "$pods" ]; then
    refuse profile "could not read the core-java pods in ${NAMESPACE} (kubectl rc=${rc}); an unverifiable profile is refused"
  fi
  rc=0
  verdict=$(jq -r '
    [.items[]? | select(any(.status.conditions[]?; .type == "Ready" and .status == "True"))] as $ready
    | [ $ready[] | (.metadata.name // "<unnamed>") as $n
        | [.spec.containers[]? | select(.name == "core-java")] as $c
        | if ($c | length) != 1 then "\($n): \($c | length) core-java containers"
          else
            ([$c[0].env[]? | select(.name == "SPRING_PROFILES_ACTIVE")]) as $e
            | ([$c[0].env[]? | select(.name == "SPRING_PROFILES_INCLUDE")] | length) as $inc
            | ([($c[0].env[]? | .value // empty), ($c[0].args[]?), ($c[0].command[]?)]
                | map(select(ascii_downcase | contains("spring.profiles"))) | length) as $ovr
            | if ($e | length) != 1 then "\($n): \($e | length) SPRING_PROFILES_ACTIVE entries"
              elif $e[0].valueFrom != null then "\($n): SPRING_PROFILES_ACTIVE is not a literal value"
              elif $e[0].value != "prod" then "\($n): SPRING_PROFILES_ACTIVE=\($e[0].value // "")"
              elif $inc > 0 then "\($n): SPRING_PROFILES_INCLUDE is set"
              elif $ovr > 0 then "\($n): an env value or argument sets spring.profiles"
              else empty end
          end ] as $bad
    | "\($ready | length)\t\($bad | join("; "))"' <<<"$pods") || rc=$?
  if [ "$rc" -ne 0 ] || [ -z "$verdict" ]; then
    refuse profile "the core-java pod list could not be parsed (jq rc=${rc}); an unverifiable profile is refused"
  fi
  n="${verdict%%$'\t'*}"
  bad="${verdict#*$'\t'}"
  if [ "$n" = "0" ]; then
    refuse profile "no Ready core-java pod in ${NAMESPACE}, so the running profile cannot be verified"
  fi
  if [ -n "$bad" ]; then
    refuse profile "${bad} (DemoDataSeeder is @Profile(\"dev\") and must never run against staging, D-26)"
  fi
  echo "OK: all ${n} Ready core-java pod(s) run SPRING_PROFILES_ACTIVE=prod"
}

# ===========================================================================
# --dry-run: the ordered request plan. Names only, never a value.
# ===========================================================================
print_plan() {
  case "$STAGE" in vendor|all) print_vendor_plan ;; esac
  case "$STAGE" in order|all) print_order_plan ;; esac
  case "$STAGE" in list-drafts|vendor-order|order-transition) print_tool_plan ;; esac
}
print_tool_plan() {
  echo "PLAN --stage ${STAGE} (dry-run: nothing below is called)"
  echo "  target        api ${API_BASE}; shop ${SHOP_SLUG}; signed in as ${VENDOR_USERNAME}"
  echo "  01 az_read    keyvault secret show --vault-name ${KEY_VAULT_NAME}: staging-seed--test-vendor-password keycloak-credentials--frontend-client-secret (values never printed)"
  echo "  02 POST       ${TOKEN_ENDPOINT}   vendor password grant on core-api"
  case "$STAGE" in
    list-drafts)
      echo "  03 GET        /api/v1/orders/status/DRAFT?size=100&page=N   every page; READ-ONLY, nothing is written"
      echo "  04 print      DRAFT-CANDIDATE order=… created=… payment_status=… intent=present|none total=…p (no customer field), then LIST-DRAFTS count=… card=… at=…" ;;
    vendor-order)
      echo "  03 GET        /api/v1/shops?size=100 (the ${SHOP_SLUG} id), /api/v1/public/shops/${SHOP_SLUG}/products (first available dish)"
      echo "  04 POST       /api/v1/orders   {shopId, items: [{productId, quantity: 1}]}, no customer field, submit unset; Idempotency-Key vendor-order-<UTC stamp>"
      echo "  05 print      VENDOR-ORDER order=… status=DRAFT payment_status=NONE (refused unless the 201 is a DRAFT with paymentStatus NONE)" ;;
    order-transition)
      echo "  03 GET        /api/v1/orders/status/{DRAFT,PENDING,CONFIRMED}   the id of ${ORDERS}"
      echo "  04 POST       /api/v1/orders/{id}/${EVENT}   exactly the named order"
      echo "  05 print      TRANSITION order=${ORDERS} event=${EVENT} http=… type=… detail=…" ;;
  esac
}
print_order_plan() {
  cat <<PLAN
PLAN --stage order (dry-run: nothing below is called)
  target        api ${API_BASE}; Stripe ${STRIPE_API} (TEST only); shop ${SHOP_SLUG}
  01 az_read    keyvault secret show --vault-name ${KEY_VAULT_NAME}: ${STRIPE_KV_OBJECT} (refused unless sk_test_; never printed, never argv)
  02 GET        /api/v1/public/shops/${SHOP_SLUG}, /api/v1/public/shops/${SHOP_SLUG}/products   anonymous; first available dish
  03 POST       /api/v1/public/shops/${SHOP_SLUG}/orders   Idempotency-Key ${ORDER_IDEMPOTENCY_KEY}; ${GUEST_EMAIL}; expected prices + allergen acknowledgement
  04 GET        ${STRIPE_API}/v1/payment_intents/{id}   the intent from the client secret (or, on a replay, by order_number metadata); livemode false
  05 POST       ${STRIPE_API}/v1/payment_intents/{id}/confirm   payment_method=pm_card_visa, return_url ${ORDER_RETURN_URL}; skipped if already succeeded
  06 GET        ${STRIPE_API}/v1/payment_intents?limit=100   exactly one intent names the order
  07 GET        /api/v1/public/orders/{orderNumber}?email=…   until paymentStatus CAPTURED (deadline ${PAID_DEADLINE_SECONDS}s)
  08 GET        ${STRIPE_API}/v1/events?type=payment_intent.succeeded   the delivered event's id
  09 print      SEED-ORDER order=… intent=… status=… payment_status=… livemode=… at=…
PLAN
}
print_vendor_plan() {
  cat <<PLAN
PLAN --stage vendor (dry-run: nothing below is called)
  target        api ${API_BASE}; context ${KUBE_CONTEXT}; namespace ${NAMESPACE}; realm ${REALM}
  01 az_read    keyvault secret show --vault-name ${KEY_VAULT_NAME}: $(list_kv_objects | tr '\n' ' ')(values never printed)
  02 kubectl    port-forward svc/keycloak ${PF_PORTS[*]} (PID captured; killed by PID on exit)   check: GET ${MGMT_BASE}/health/ready
  03 POST       ${ADMIN_BASE}/realms/master/protocol/openid-connect/token   master admin, through the port-forward only
  04 POST       /admin/realms/${REALM}/users seed-operator-<UTC> + realm role admin   deleted on EVERY exit path
  05 POST       ${TOKEN_ENDPOINT}   operator password grant on core-api (the public issuer)
  06 GET/POST   /api/v1/admin/tenants   check: reuse by name "${TENANT_NAME}"
  07 PUT        /admin/realms/${REALM}/users/<operator>   tenant_id attribute, read back; operator token re-issued
  08 GET/POST   /admin/realms/${REALM}/users   check: reuse ${VENDOR_USERNAME}; tenant_id read back by content (partialImport fallback)
  09 POST       ${TOKEN_ENDPOINT}   vendor password grant; the token's tenant_id claim must equal the tenant
  10 GET/POST   /api/v1/staff/me, /api/v1/staff/grant   check: vendor already groupAdmin (strict scoping: no JIT grant, D-06)
  11 GET/POST   /api/v1/shops   check: reuse slug ${SHOP_SLUG}
  12 GET/POST   /api/v1/products/culinary-packs, /api/v1/products/culinary-packs/seed   check: the shop has zero products
  13 GET/PUT    /api/v1/trader-identity   check: the same identity already on file
  14 GET/POST   /api/v1/onboarding/me, /api/v1/onboarding (${ONBOARDING_MODEL}, sole trader), /api/v1/onboarding/submit   check: status
  15 POST       /api/v1/onboarding/admin/{id}/gates/{gate}/resolve   WAIVE open gates only, never ${NEVER_RESOLVED_GATES// /, }; reason "${GATE_REASON}"
  16 POST       /api/v1/onboarding/admin/{id}/approve   check: status PENDING_APPROVAL
  17 POST       /api/v1/onboarding/go-live   check: status APPROVED (LIVE is skipped)
  18 GET        read-back: tenant, shop + published, product count, onboarding status, vendor tenant_id
  19 DELETE     /admin/realms/${REALM}/users/<operator>   on every exit, success or failure
PLAN
}

# ===========================================================================
# --stage vendor (live)
# ===========================================================================
#
# Secrets: every Key Vault value goes to a mode-600 file in WORKDIR and travels
# to curl as `name@file` or inside a body file, never in argv and never printed.
# Bearer tokens travel in curl config files (-K), never in argv.
#
# Who does what. J'Toye has no platform-operator identity (recorded
# architecture), and under strict scoping (access.strict-scoping "true", D-06) a
# new user gets no access by itself: ungranted means no access, nothing is
# provisioned just in time. So, exactly as a first tenant would be set up, a
# realm admin creates the tenant, grants its owner GROUP_ADMIN through the staff
# API, and later resolves the gates and approves; the owner does everything
# else. That realm admin is seed-operator-<UTC>, created for this run only, with
# a random in-memory password, and deleted by the EXIT trap (T-29-17).

# kc_api METHOD PATH OUT [BODY] — Keycloak admin API through the port-forward.
# Prints the HTTP status ("000" when curl could not connect). A 401 refreshes the
# short-lived (60 s) master token once and retries.
kc_api() {
  local method="$1" path="$2" out="$3" body="${4:-}" code
  code=$(_kc_call "$method" "$path" "$out" "$body")
  if [ "$code" = 401 ]; then
    admin_token >/dev/null 2>&1 || true
    code=$(_kc_call "$method" "$path" "$out" "$body")
  fi
  echo "$code"
}
_kc_call() {
  local method="$1" path="$2" out="$3" body="${4:-}" code rc=0
  if [ -n "$body" ]; then
    code=$(curl -sS -K "$WORKDIR/kc-auth.curlrc" -X "$method" -H 'Content-Type: application/json' \
      --data-binary "@$body" -D "$WORKDIR/kc-headers" -o "$out" -w '%{http_code}' "${ADMIN_BASE}${path}") || rc=$?
  else
    code=$(curl -sS -K "$WORKDIR/kc-auth.curlrc" -X "$method" -D "$WORKDIR/kc-headers" \
      -o "$out" -w '%{http_code}' "${ADMIN_BASE}${path}") || rc=$?
  fi
  if [ "$rc" -ne 0 ]; then echo "000"; return 0; fi
  echo "$code"
}

# admin_token — a master-realm admin token into kc-auth.curlrc. Username and
# password are posted from mode-600 files, never argv.
admin_token() {
  local code rc=0 token
  code=$(curl -sS -X POST -o "$WORKDIR/kc-token.json" -w '%{http_code}' \
    --data-urlencode "grant_type=password" --data-urlencode "client_id=admin-cli" \
    --data-urlencode "username@$WORKDIR/kv-admin-username" --data-urlencode "password@$WORKDIR/kv-admin-password" \
    "${ADMIN_BASE}/realms/master/protocol/openid-connect/token") || rc=$?
  if [ "$rc" -ne 0 ] || [ "$code" != 200 ]; then
    rm -f "$WORKDIR/kc-token.json"
    echo "FAIL: master admin token request returned HTTP ${code:-000} (curl rc=$rc)" >&2
    return 1
  fi
  token=$(jq -r '.access_token // empty' "$WORKDIR/kc-token.json")
  rm -f "$WORKDIR/kc-token.json"
  [ -n "$token" ] || { echo "FAIL: the master token response carried no access_token" >&2; return 1; }
  (umask 077 && printf 'header = "Authorization: Bearer %s"\n' "$token" > "$WORKDIR/kc-auth.curlrc")
}

# user_token WHO — a password grant on core-api at the public issuer, the token
# core-java validates. WHO is operator or vendor; the username, password and
# client secret come from mode-600 files. Writes WHO-auth.curlrc and WHO-token.
user_token() {
  local who="$1" code rc=0 token
  code=$(curl -sS -X POST -o "$WORKDIR/$who-token.json" -w '%{http_code}' \
    --data-urlencode "grant_type=password" --data-urlencode "client_id=core-api" \
    --data-urlencode "client_secret@$WORKDIR/kv-client-secret" --data-urlencode "scope=openid" \
    --data-urlencode "username@$WORKDIR/$who-username" --data-urlencode "password@$WORKDIR/$who-password" \
    "$TOKEN_ENDPOINT") || rc=$?
  if [ "$rc" -ne 0 ] || [ "$code" != 200 ]; then
    rm -f "$WORKDIR/$who-token.json"
    echo "FAIL: the $who token request to $TOKEN_ENDPOINT returned HTTP ${code:-000} (curl rc=$rc)" >&2
    return 1
  fi
  token=$(jq -r '.access_token // empty' "$WORKDIR/$who-token.json")
  rm -f "$WORKDIR/$who-token.json"
  [ -n "$token" ] || { echo "FAIL: the $who token response carried no access_token" >&2; return 1; }
  (umask 077 && printf '%s' "$token" > "$WORKDIR/$who-token" \
    && printf 'header = "Authorization: Bearer %s"\n' "$token" > "$WORKDIR/$who-auth.curlrc")
}

# jwt_claim WHO CLAIM — read one claim out of WHO's access token (payload only;
# the signature is core-java's to check, this is a read-back of what it will see).
jwt_claim() {
  local seg pad
  seg=$(cut -d. -f2 < "$WORKDIR/$1-token" | tr '_-' '/+')
  pad=$(( (4 - ${#seg} % 4) % 4 ))
  while [ "$pad" -gt 0 ]; do seg="${seg}="; pad=$((pad - 1)); done
  base64 -d <<<"$seg" 2>/dev/null | jq -r --arg c "$2" '.[$c] // empty' 2>/dev/null || true
}

# api WHO METHOD PATH OUT [BODY] — the public API as WHO. Prints the HTTP status;
# a 401 re-issues WHO's token once and retries.
api() {
  local who="$1" method="$2" path="$3" out="$4" body="${5:-}" code
  code=$(_api_call "$who" "$method" "$path" "$out" "$body")
  if [ "$code" = 401 ]; then
    user_token "$who" >/dev/null 2>&1 || true
    code=$(_api_call "$who" "$method" "$path" "$out" "$body")
  fi
  echo "$code"
}
# API_EXTRA_HEADER, when set, is sent as one more header (never a secret).
API_EXTRA_HEADER=""
_api_call() {
  local who="$1" method="$2" path="$3" out="$4" body="${5:-}" code rc=0
  local -a xh=()
  [ -z "$API_EXTRA_HEADER" ] || xh=(-H "$API_EXTRA_HEADER")
  if [ -n "$body" ]; then
    code=$(curl -sS -K "$WORKDIR/$who-auth.curlrc" -X "$method" -H 'Accept: application/json' ${xh[@]+"${xh[@]}"} \
      -H 'Content-Type: application/json' --data-binary "@$body" -o "$out" -w '%{http_code}' "${API_BASE}${path}") || rc=$?
  else
    code=$(curl -sS -K "$WORKDIR/$who-auth.curlrc" -X "$method" -H 'Accept: application/json' ${xh[@]+"${xh[@]}"} \
      -o "$out" -w '%{http_code}' "${API_BASE}${path}") || rc=$?
  fi
  if [ "$rc" -ne 0 ]; then echo "000"; return 0; fi
  echo "$code"
}

# fail_http WHAT CODE OUT — one failure line: the call, its status, and the
# problem type/title/detail of the response (API data, never a secret).
fail_http() {
  local what="$1" code="$2" out="$3" why=""
  if [ -s "$out" ]; then
    why=$(jq -r '[.type, .title, .detail] | map(select(. != null)) | join(" | ")' "$out" 2>/dev/null | head -c 300 || true)
  fi
  echo "FAIL: $what returned HTTP $code${why:+: $why}" >&2
  exit 1
}

# body FILE — the JSON on stdin, written mode 600.
body() { (umask 077 && cat > "$1"); }

# delete_operator — called from the EXIT trap. Never exits; returns 1 when the
# operator may still exist, so the trap can make the run a failure.
delete_operator() {
  local code
  if [ -z "$OP_ID" ]; then
    code=$(kc_api GET "/admin/realms/$REALM/users?username=$OP_USERNAME&exact=true" "$WORKDIR/op-find.json")
    if [ "$code" = 200 ]; then OP_ID=$(jq -r --arg u "$OP_USERNAME" '[.[] | select(.username == $u)][0].id // empty' "$WORKDIR/op-find.json" 2>/dev/null || true); fi
    if [ -z "$OP_ID" ]; then
      if [ "$code" = 200 ]; then
        echo "OPERATOR-DELETED: ${OP_USERNAME} does not exist (it was never created)"
        return 0
      fi
      echo "FAIL: could not look up the seed operator ${OP_USERNAME} (HTTP $code); it may still exist: delete it by hand in realm $REALM" >&2
      return 1
    fi
  fi
  code=$(kc_api DELETE "/admin/realms/$REALM/users/$OP_ID" "$WORKDIR/op-delete.json")
  case "$code" in
    204|404) echo "OPERATOR-DELETED: ${OP_USERNAME} (${OP_ID}) HTTP $code"; OP_CREATED=0; return 0 ;;
    *) echo "FAIL: the seed operator ${OP_USERNAME} (${OP_ID}) was NOT deleted (HTTP $code); it holds realm admin: delete it by hand in realm $REALM now" >&2; return 1 ;;
  esac
}

vendor_stage() {
  local row name obj v rc code t0

  # 1. The subscription guard. JTOYE_AZURE_SUBSCRIPTION_ID is the caller's; this
  #    script never sets it. A refusal exits before any Key Vault read.
  azure_guard_once
  need_tool curl; need_tool openssl; need_tool base64
  ensure_workdir

  # 2. Key Vault reads into mode-600 files.
  set +x
  for row in "${KV_MAP[@]}"; do
    name="${row%%=*}"; obj="${row#*=}"
    rc=0; v=$(az_read keyvault secret show --vault-name "$KEY_VAULT_NAME" -n "$obj" --query value -o tsv) || rc=$?
    if [ "$rc" -ne 0 ] || [ -z "$v" ]; then
      refuse key-vault "could not read $obj from $KEY_VAULT_NAME (az rc=$rc); plan 29-13 generates it"
    fi
    case "$name" in
      VENDOR_PASSWORD)        (umask 077 && printf '%s' "$v" > "$WORKDIR/vendor-password") ;;
      KC_ADMIN_USERNAME)      (umask 077 && printf '%s' "$v" > "$WORKDIR/kv-admin-username") ;;
      KC_ADMIN_PASSWORD)      (umask 077 && printf '%s' "$v" > "$WORKDIR/kv-admin-password") ;;
      CORE_API_CLIENT_SECRET) (umask 077 && printf '%s' "$v" > "$WORKDIR/kv-client-secret") ;;
    esac
  done
  v=""
  (umask 077 && printf '%s' "$VENDOR_USERNAME" > "$WORKDIR/vendor-username")
  echo "OK: read ${#KV_MAP[@]} Key Vault objects from $KEY_VAULT_NAME (values not shown)"

  # 3. The port-forward, PID captured at start (never killed by a pattern).
  kubectl --context "$KUBE_CONTEXT" -n "$NAMESPACE" port-forward svc/keycloak "${PF_PORTS[@]}" > "$WORKDIR/port-forward.log" 2>&1 &
  PF_PID=$!
  t0=$(date +%s)
  while :; do
    if ! kill -0 "$PF_PID" 2>/dev/null; then
      PF_PID=""
      echo "FAIL: the port-forward exited before Keycloak answered: $(head -c 300 "$WORKDIR/port-forward.log" | tr '\n' ' ')" >&2
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

  # 4. The per-run realm-admin operator.
  admin_token || exit 1
  OP_USERNAME="seed-operator-$(date -u +%Y%m%dt%H%M%Sz)"
  (umask 077 && printf '%s' "$OP_USERNAME" > "$WORKDIR/operator-username")
  (umask 077 && openssl rand -base64 32 | tr -d '\n' > "$WORKDIR/operator-password") \
    || void "openssl rand failed"
  [ -s "$WORKDIR/operator-password" ] || void "openssl rand produced no password"
  jq -n --arg u "$OP_USERNAME" --rawfile p "$WORKDIR/operator-password" '{
      username: $u, enabled: true, emailVerified: true, firstName: "Seed", lastName: "Operator",
      email: ($u + "@seed.invalid"), requiredActions: [],
      credentials: [{type: "password", value: $p, temporary: false}] }' | body "$WORKDIR/op-create.json"
  OP_CREATED=1
  code=$(kc_api POST "/admin/realms/$REALM/users" "$WORKDIR/op-created.json" "$WORKDIR/op-create.json")
  rm -f "$WORKDIR/op-create.json"
  [ "$code" = 201 ] || fail_http "POST /admin/realms/$REALM/users ($OP_USERNAME)" "$code" "$WORKDIR/op-created.json"
  OP_ID=$(command grep -i '^location:' "$WORKDIR/kc-headers" | tr -d '\r' | command sed 's#.*/##' | head -1 || true)
  [ -n "$OP_ID" ] || { echo "FAIL: the operator create returned no Location" >&2; exit 1; }
  code=$(kc_api GET "/admin/realms/$REALM/roles/admin" "$WORKDIR/role-admin.json")
  [ "$code" = 200 ] || fail_http "GET /admin/realms/$REALM/roles/admin" "$code" "$WORKDIR/role-admin.json"
  jq '[.]' "$WORKDIR/role-admin.json" | body "$WORKDIR/role-map.json"
  code=$(kc_api POST "/admin/realms/$REALM/users/$OP_ID/role-mappings/realm" "$WORKDIR/role-mapped.json" "$WORKDIR/role-map.json")
  [ "$code" = 204 ] || fail_http "POST role-mappings/realm admin for $OP_USERNAME" "$code" "$WORKDIR/role-mapped.json"
  echo "OK: created the per-run operator $OP_USERNAME ($OP_ID) with realm role admin; it is deleted on exit"
  user_token operator || exit 1

  # 5. The tenant, by name.
  local tenant_id n
  code=$(api operator GET /api/v1/admin/tenants "$WORKDIR/tenants.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/admin/tenants" "$code" "$WORKDIR/tenants.json"
  n=$(jq --arg n "$TENANT_NAME" '[.[] | select(.name == $n)] | length' "$WORKDIR/tenants.json")
  if [ "$n" -gt 1 ]; then refuse tenant "$n tenants are named \"$TENANT_NAME\"; the seed cannot tell which is its own"; fi
  if [ "$n" -eq 1 ]; then
    tenant_id=$(jq -r --arg n "$TENANT_NAME" '.[] | select(.name == $n) | .id' "$WORKDIR/tenants.json")
    echo "SKIP: tenant \"$TENANT_NAME\" exists ($tenant_id)"
  else
    jq -n --arg n "$TENANT_NAME" --arg e "$VENDOR_USERNAME" \
      '{name: $n, contactName: "Staging Test Vendor", contactEmail: $e}' | body "$WORKDIR/tenant-create.json"
    code=$(api operator POST /api/v1/admin/tenants "$WORKDIR/tenant.json" "$WORKDIR/tenant-create.json")
    [ "$code" = 201 ] || fail_http "POST /api/v1/admin/tenants" "$code" "$WORKDIR/tenant.json"
    tenant_id=$(jq -r '.id // empty' "$WORKDIR/tenant.json")
    echo "CREATED: tenant \"$TENANT_NAME\" ($tenant_id)"
  fi
  [[ "$tenant_id" =~ ^[0-9a-f-]{36}$ ]] || { echo "FAIL: the tenant id '$tenant_id' is not a UUID" >&2; exit 1; }

  # 6. The operator acts inside that tenant from here on: core-java in prod reads
  #    the tenant from the JWT only (TenantFilter is not loaded), so the operator
  #    carries the tenant_id attribute, read back by content, and a fresh token.
  set_tenant_attribute "$OP_ID" "$tenant_id" operator
  user_token operator || exit 1

  # 7. The vendor user: reused by username, else created with the Key Vault
  #    password (non-temporary) and the tenant_id attribute.
  local vendor_id existing
  code=$(kc_api GET "/admin/realms/$REALM/users?username=$VENDOR_USERNAME&exact=true" "$WORKDIR/vendor-find.json")
  [ "$code" = 200 ] || fail_http "GET users?username=$VENDOR_USERNAME" "$code" "$WORKDIR/vendor-find.json"
  vendor_id=$(jq -r --arg u "$VENDOR_USERNAME" '[.[] | select(.username == $u)][0].id // empty' "$WORKDIR/vendor-find.json")
  if [ -n "$vendor_id" ]; then
    code=$(kc_api GET "/admin/realms/$REALM/users/$vendor_id" "$WORKDIR/vendor.json")
    [ "$code" = 200 ] || fail_http "GET users/$vendor_id" "$code" "$WORKDIR/vendor.json"
    existing=$(jq -r '.attributes.tenant_id[0] // empty' "$WORKDIR/vendor.json")
    if [ -n "$existing" ] && [ "$existing" != "$tenant_id" ]; then
      refuse vendor-tenant "$VENDOR_USERNAME already belongs to tenant $existing, not $tenant_id; the seed does not re-home a user"
    fi
    echo "SKIP: vendor user $VENDOR_USERNAME exists ($vendor_id)"
    [ -n "$existing" ] || set_tenant_attribute "$vendor_id" "$tenant_id" vendor
  else
    jq -n --arg u "$VENDOR_USERNAME" --arg t "$tenant_id" --rawfile p "$WORKDIR/vendor-password" '{
        username: $u, email: $u, enabled: true, emailVerified: true, firstName: "Staging", lastName: "Vendor",
        requiredActions: [], attributes: {tenant_id: [$t]},
        credentials: [{type: "password", value: $p, temporary: false}] }' | body "$WORKDIR/vendor-create.json"
    code=$(kc_api POST "/admin/realms/$REALM/users" "$WORKDIR/vendor-created.json" "$WORKDIR/vendor-create.json")
    rm -f "$WORKDIR/vendor-create.json"
    [ "$code" = 201 ] || fail_http "POST /admin/realms/$REALM/users ($VENDOR_USERNAME)" "$code" "$WORKDIR/vendor-created.json"
    vendor_id=$(command grep -i '^location:' "$WORKDIR/kc-headers" | tr -d '\r' | command sed 's#.*/##' | head -1 || true)
    [ -n "$vendor_id" ] || { echo "FAIL: the vendor create returned no Location" >&2; exit 1; }
    echo "CREATED: vendor user $VENDOR_USERNAME ($vendor_id)"
    # The Keycloak user-profile trap (docs/security-scopes.md section 5, measured
    # in 38-17): an unmanaged attribute is silently stripped on an admin-API
    # create. Read it back by content.
    code=$(kc_api GET "/admin/realms/$REALM/users/$vendor_id" "$WORKDIR/vendor.json")
    [ "$code" = 200 ] || fail_http "GET users/$vendor_id" "$code" "$WORKDIR/vendor.json"
    if [ "$(jq -r '.attributes.tenant_id[0] // empty' "$WORKDIR/vendor.json")" != "$tenant_id" ]; then
      echo "NOTICE: tenant_id did not survive the admin-API create; re-writing the user through partialImport (imported users keep it)"
      partial_import_vendor "$vendor_id" "$tenant_id"
    fi
  fi
  code=$(kc_api GET "/admin/realms/$REALM/users/$vendor_id" "$WORKDIR/vendor.json")
  [ "$code" = 200 ] || fail_http "GET users/$vendor_id" "$code" "$WORKDIR/vendor.json"
  if [ "$(jq -r '.attributes.tenant_id[0] // empty' "$WORKDIR/vendor.json")" != "$tenant_id" ]; then
    refuse tenant-attribute "$VENDOR_USERNAME has no tenant_id $tenant_id after the create and the partialImport fallback; every API call would be tenant-less"
  fi
  echo "OK: $VENDOR_USERNAME tenant_id reads back as $tenant_id"

  # 8. The vendor's token carries the tenant, as core-java will read it.
  user_token vendor || { echo "FAIL: the vendor could not sign in; if the user predates this run, its password may not be the Key Vault one" >&2; exit 1; }
  if [ "$(jwt_claim vendor tenant_id)" != "$tenant_id" ]; then
    refuse tenant-claim "the vendor's access token carries tenant_id '$(jwt_claim vendor tenant_id)', not $tenant_id"
  fi

  # 9. GROUP_ADMIN for the vendor (strict scoping: ungranted means no access).
  #    The first call also records the vendor in user_directory, which a grant
  #    requires (StaffManagementService.grant).
  local vendor_sub
  code=$(api vendor GET /api/v1/staff/me "$WORKDIR/staff-me.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/staff/me (vendor)" "$code" "$WORKDIR/staff-me.json"
  vendor_sub=$(jq -r '.userId // empty' "$WORKDIR/staff-me.json")
  if [ "$(jq -r '.groupAdmin' "$WORKDIR/staff-me.json")" = true ]; then
    echo "SKIP: the vendor is already GROUP_ADMIN"
  else
    jq -n --arg u "$vendor_sub" '{userId: $u, shopId: null, role: "GROUP_ADMIN"}' | body "$WORKDIR/grant.json"
    code=$(api operator POST /api/v1/staff/grant "$WORKDIR/granted.json" "$WORKDIR/grant.json")
    case "$code" in 200|201) ;; *) fail_http "POST /api/v1/staff/grant (vendor GROUP_ADMIN)" "$code" "$WORKDIR/granted.json" ;; esac
    code=$(api vendor GET /api/v1/staff/me "$WORKDIR/staff-me.json")
    if [ "$code" != 200 ] || [ "$(jq -r '.groupAdmin' "$WORKDIR/staff-me.json")" != true ]; then
      echo "FAIL: the vendor is not GROUP_ADMIN after the grant (HTTP $code)" >&2; exit 1
    fi
    echo "CREATED: GROUP_ADMIN grant for the vendor ($vendor_sub)"
  fi

  # 10. The shop, by slug. Open every day: a shop saved without hours takes no
  #     storefront orders (37-20), and plan 29-18 places one.
  local shop_id
  code=$(api vendor GET "/api/v1/shops?size=100" "$WORKDIR/shops.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/shops" "$code" "$WORKDIR/shops.json"
  shop_id=$(jq -r --arg s "$SHOP_SLUG" '[.content[]? | select(.slug == $s)][0].id // empty' "$WORKDIR/shops.json")
  if [ -n "$shop_id" ]; then
    echo "SKIP: shop $SHOP_SLUG exists ($shop_id)"
  else
    jq -n --arg n "$TENANT_NAME" --arg s "$SHOP_SLUG" --arg e "$VENDOR_USERNAME" '
      ([{open: "00:00", close: "23:59"}]) as $w | {
        name: $n, slug: $s, email: $e,
        address: "1 Staging Test Street, Birmingham B1 1AA",
        description: "Staging test kitchen, seeded by scripts/staging-seed.sh (D-26). Not a real business.",
        openingSchedule: {mon: $w, tue: $w, wed: $w, thu: $w, fri: $w, sat: $w, sun: $w} }' | body "$WORKDIR/shop-create.json"
    code=$(api vendor POST /api/v1/shops "$WORKDIR/shop.json" "$WORKDIR/shop-create.json")
    [ "$code" = 201 ] || fail_http "POST /api/v1/shops" "$code" "$WORKDIR/shop.json"
    shop_id=$(jq -r '.id // empty' "$WORKDIR/shop.json")
    echo "CREATED: shop $SHOP_SLUG ($shop_id)"
  fi

  # 11. One culinary pack, before submit: the ALLERGEN_DATA_COMPLETE gate is
  #     evaluated at submit and fails on a shop with no products.
  local count key
  code=$(api vendor GET "/api/v1/products?shopId=$shop_id&size=1" "$WORKDIR/products.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/products?shopId=$shop_id" "$code" "$WORKDIR/products.json"
  count=$(jq -r '.totalElements // 0' "$WORKDIR/products.json")
  if [ "$count" -gt 0 ]; then
    echo "SKIP: shop $SHOP_SLUG already has $count product(s)"
  else
    code=$(api vendor GET /api/v1/products/culinary-packs "$WORKDIR/packs.json")
    [ "$code" = 200 ] || fail_http "GET /api/v1/products/culinary-packs" "$code" "$WORKDIR/packs.json"
    key=$(jq -r '.[0].cuisineKey // empty' "$WORKDIR/packs.json")
    [ -n "$key" ] || { echo "FAIL: GET /api/v1/products/culinary-packs listed no pack" >&2; exit 1; }
    jq -n --arg s "$shop_id" --arg k "$key" '{shopId: $s, cuisineKeys: [$k]}' | body "$WORKDIR/seed.json"
    code=$(api vendor POST /api/v1/products/culinary-packs/seed "$WORKDIR/seeded.json" "$WORKDIR/seed.json")
    [ "$code" = 200 ] || fail_http "POST /api/v1/products/culinary-packs/seed ($key)" "$code" "$WORKDIR/seeded.json"
    if [ "$(jq -r '.failed // 1' "$WORKDIR/seeded.json")" -ne 0 ] || [ "$(jq -r '.created // 0' "$WORKDIR/seeded.json")" -lt 1 ]; then
      echo "FAIL: the culinary pack seed created $(jq -r '.created' "$WORKDIR/seeded.json") and failed $(jq -r '.failed' "$WORKDIR/seeded.json")" >&2; exit 1
    fi
    echo "CREATED: culinary pack $key: $(jq -r '.created' "$WORKDIR/seeded.json") product(s)"
  fi

  # 12. The trader identity (V71): a sole trader with a geographic UK address.
  jq -n --arg n "$TENANT_NAME" '{legalName: $n, entityType: "SOLE_TRADER", addressLine1: "1 Staging Test Street",
      addressLine2: null, addressCity: "Birmingham", addressPostcode: "B1 1AA", vatNumber: null}' | body "$WORKDIR/identity.json"
  code=$(api vendor GET /api/v1/trader-identity "$WORKDIR/identity-now.json")
  if [ "$code" = 200 ] && [ "$(jq -S '{legalName, entityType, addressLine1, addressLine2, addressCity, addressPostcode, vatNumber}' "$WORKDIR/identity-now.json")" = "$(jq -S . "$WORKDIR/identity.json")" ]; then
    echo "SKIP: the trader identity is already on file"
  else
    case "$code" in 200|404) ;; *) fail_http "GET /api/v1/trader-identity" "$code" "$WORKDIR/identity-now.json" ;; esac
    code=$(api vendor PUT /api/v1/trader-identity "$WORKDIR/identity-put.json" "$WORKDIR/identity.json")
    case "$code" in 200|201) ;; *) fail_http "PUT /api/v1/trader-identity" "$code" "$WORKDIR/identity-put.json" ;; esac
    echo "WRITTEN: trader identity (SOLE_TRADER, no VAT number)"
  fi

  # 13. Onboarding to LIVE.
  onboard_to_live "$shop_id"

  # 14. The read-back.
  readback "$tenant_id" "$shop_id" "$vendor_id"
}

# set_tenant_attribute USER_ID TENANT_ID LABEL — GET the full representation, set
# attributes.tenant_id, PUT it back (a partial PUT under the declarative user
# profile can drop the required names), and read the attribute back by content.
set_tenant_attribute() {
  local id="$1" tenant="$2" label="$3" code
  code=$(kc_api GET "/admin/realms/$REALM/users/$id" "$WORKDIR/attr-user.json")
  [ "$code" = 200 ] || fail_http "GET users/$id ($label)" "$code" "$WORKDIR/attr-user.json"
  jq --arg t "$tenant" '.attributes = ((.attributes // {}) + {tenant_id: [$t]})' "$WORKDIR/attr-user.json" | body "$WORKDIR/attr-put.json"
  code=$(kc_api PUT "/admin/realms/$REALM/users/$id" "$WORKDIR/attr-done.json" "$WORKDIR/attr-put.json")
  [ "$code" = 204 ] || fail_http "PUT users/$id tenant_id ($label)" "$code" "$WORKDIR/attr-done.json"
  code=$(kc_api GET "/admin/realms/$REALM/users/$id" "$WORKDIR/attr-user.json")
  if [ "$code" != 200 ] || [ "$(jq -r '.attributes.tenant_id[0] // empty' "$WORKDIR/attr-user.json")" != "$tenant" ]; then
    if [ "$label" = vendor ]; then partial_import_vendor "$id" "$tenant"; return 0; fi
    refuse tenant-attribute "the $label user's tenant_id did not read back as $tenant after the PUT (HTTP $code)"
  fi
  echo "OK: $label tenant_id set to $tenant and read back"
}

# partial_import_vendor USER_ID TENANT_ID — the fallback for the user-profile
# trap: imported users keep their attributes, so the vendor is re-written
# through the realm partialImport (OVERWRITE, same id, same Key Vault password).
partial_import_vendor() {
  local id="$1" tenant="$2" code
  jq -n --arg id "$id" --arg u "$VENDOR_USERNAME" --arg t "$tenant" --rawfile p "$WORKDIR/vendor-password" '{
      ifResourceExists: "OVERWRITE",
      users: [{ id: $id, username: $u, email: $u, enabled: true, emailVerified: true,
                firstName: "Staging", lastName: "Vendor", requiredActions: [], attributes: {tenant_id: [$t]},
                credentials: [{type: "password", value: $p, temporary: false}] }] }' | body "$WORKDIR/partial.json"
  code=$(kc_api POST "/admin/realms/$REALM/partialImport" "$WORKDIR/partial-done.json" "$WORKDIR/partial.json")
  rm -f "$WORKDIR/partial.json"
  [ "$code" = 200 ] || fail_http "POST /admin/realms/$REALM/partialImport (vendor)" "$code" "$WORKDIR/partial-done.json"
}

# onboard_to_live SHOP_ID — create (sole trader), submit, let the gate chain run,
# WAIVE the gates a human reviews (never the two the go-live guard requires
# PASSED on the facts), approve, go live. Every step reads the status first, so a
# re-run resumes wherever the last one stopped.
onboard_to_live() {
  local shop_id="$1" code status onb_id t0 open gate g resubmitted=0 resolved=" "
  t0=$(date +%s)
  while :; do
    code=$(api vendor GET /api/v1/onboarding/me "$WORKDIR/onb.json")
    if [ "$code" = 404 ]; then
      jq -n --arg m "$ONBOARDING_MODEL" --arg s "$shop_id" '{model: $m, shopId: $s}' | body "$WORKDIR/onb-create.json"
      code=$(api vendor POST /api/v1/onboarding "$WORKDIR/onb.json" "$WORKDIR/onb-create.json")
      [ "$code" = 201 ] || fail_http "POST /api/v1/onboarding" "$code" "$WORKDIR/onb.json"
      echo "CREATED: onboarding ($ONBOARDING_MODEL, sole trader)"
      continue
    fi
    [ "$code" = 200 ] || fail_http "GET /api/v1/onboarding/me" "$code" "$WORKDIR/onb.json"
    status=$(jq -r '.status' "$WORKDIR/onb.json"); onb_id=$(jq -r '.id' "$WORKDIR/onb.json")
    if [ "$(jq -r '.shopId' "$WORKDIR/onb.json")" != "$shop_id" ]; then
      refuse onboarding "the tenant's onboarding gates shop $(jq -r '.shopId' "$WORKDIR/onb.json"), not $SHOP_SLUG ($shop_id)"
    fi
    case "$status" in
      LIVE)
        echo "OK: onboarding LIVE"
        return 0 ;;
      DRAFT)
        code=$(api vendor POST /api/v1/onboarding/submit "$WORKDIR/onb-step.json")
        [ "$code" = 200 ] || fail_http "POST /api/v1/onboarding/submit" "$code" "$WORKDIR/onb-step.json"
        echo "OK: onboarding submitted" ;;
      VERIFYING)
        if [ "$(jq '[.gates[] | select(.status == "PENDING")] | length' "$WORKDIR/onb.json")" -eq 0 ]; then
          open=$(jq -r '.gates[] | select(.status == "MANUAL_REVIEW" or .status == "FAILED") | .gateType' "$WORKDIR/onb.json")
          for gate in $open; do
            for g in $NEVER_RESOLVED_GATES; do
              if [ "$gate" = "$g" ]; then
                echo "FAIL: gate $gate is $(jq -r --arg g "$gate" '.gates[] | select(.gateType == $g) | "\(.status): \(.reason // "")"' "$WORKDIR/onb.json"); the go-live guard needs it PASSED on the facts, so the seed does not waive it" >&2
                exit 1
              fi
            done
            case "$resolved" in *" $gate "*) echo "FAIL: gate $gate is open again after it was resolved" >&2; exit 1 ;; esac
            jq -n --arg r "$GATE_REASON" '{decision: "WAIVE", reason: $r}' | body "$WORKDIR/resolve.json"
            code=$(api operator POST "/api/v1/onboarding/admin/$onb_id/gates/$gate/resolve" "$WORKDIR/resolved.json" "$WORKDIR/resolve.json")
            [ "$code" = 200 ] || fail_http "POST /api/v1/onboarding/admin/$onb_id/gates/$gate/resolve" "$code" "$WORKDIR/resolved.json"
            resolved="$resolved$gate "
            echo "RESOLVED: gate $gate WAIVED (\"$GATE_REASON\")"
          done
        fi ;;
      ACTION_REQUIRED)
        if [ "$resubmitted" -eq 1 ]; then
          echo "FAIL: onboarding is ACTION_REQUIRED again after a resubmit: $(jq -r '[.gates[] | select(.status == "FAILED") | "\(.gateType): \(.reason // "")"] | join("; ")' "$WORKDIR/onb.json")" >&2
          exit 1
        fi
        code=$(api vendor POST /api/v1/onboarding/resubmit "$WORKDIR/onb-step.json")
        [ "$code" = 200 ] || fail_http "POST /api/v1/onboarding/resubmit" "$code" "$WORKDIR/onb-step.json"
        resubmitted=1
        echo "OK: onboarding resubmitted after ACTION_REQUIRED" ;;
      PENDING_APPROVAL)
        code=$(api operator POST "/api/v1/onboarding/admin/$onb_id/approve" "$WORKDIR/onb-step.json")
        [ "$code" = 200 ] || fail_http "POST /api/v1/onboarding/admin/$onb_id/approve" "$code" "$WORKDIR/onb-step.json"
        echo "OK: onboarding approved" ;;
      APPROVED)
        code=$(api vendor POST /api/v1/onboarding/go-live "$WORKDIR/onb-step.json")
        [ "$code" = 200 ] || fail_http "POST /api/v1/onboarding/go-live" "$code" "$WORKDIR/onb-step.json"
        echo "OK: go-live accepted" ;;
      *)
        echo "FAIL: onboarding is $status, which the seed cannot advance" >&2
        exit 1 ;;
    esac
    if [ $(( $(date +%s) - t0 )) -ge "$GATE_DEADLINE_SECONDS" ]; then
      echo "FAIL: onboarding did not reach LIVE within ${GATE_DEADLINE_SECONDS}s (last status $status)" >&2
      exit 1
    fi
    sleep "$POLL_SECONDS"
  done
}

# readback TENANT_ID SHOP_ID VENDOR_ID — what the API and Keycloak now hold.
readback() {
  local tenant_id="$1" shop_id="$2" vendor_id="$3" code
  code=$(api operator GET /api/v1/admin/tenants "$WORKDIR/rb-tenants.json")
  [ "$code" = 200 ] || fail_http "read-back GET /api/v1/admin/tenants" "$code" "$WORKDIR/rb-tenants.json"
  code=$(api vendor GET "/api/v1/shops/$shop_id" "$WORKDIR/rb-shop.json")
  [ "$code" = 200 ] || fail_http "read-back GET /api/v1/shops/$shop_id" "$code" "$WORKDIR/rb-shop.json"
  code=$(api vendor GET "/api/v1/products?shopId=$shop_id&size=1" "$WORKDIR/rb-products.json")
  [ "$code" = 200 ] || fail_http "read-back GET /api/v1/products" "$code" "$WORKDIR/rb-products.json"
  code=$(api vendor GET /api/v1/onboarding/me "$WORKDIR/rb-onb.json")
  [ "$code" = 200 ] || fail_http "read-back GET /api/v1/onboarding/me" "$code" "$WORKDIR/rb-onb.json"
  code=$(kc_api GET "/admin/realms/$REALM/users/$vendor_id" "$WORKDIR/rb-vendor.json")
  [ "$code" = 200 ] || fail_http "read-back GET users/$vendor_id" "$code" "$WORKDIR/rb-vendor.json"
  echo "RESULT at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "  tenants named \"$TENANT_NAME\": $(jq --arg n "$TENANT_NAME" '[.[] | select(.name == $n)] | length' "$WORKDIR/rb-tenants.json") (id $tenant_id)"
  echo "  shop: $(jq -r '"\(.id) slug \(.slug) published=\(.published)"' "$WORKDIR/rb-shop.json")"
  echo "  products in the shop: $(jq -r '.totalElements' "$WORKDIR/rb-products.json")"
  echo "  onboarding: $(jq -r '.status' "$WORKDIR/rb-onb.json")"
  echo "  vendor $VENDOR_USERNAME tenant_id: $(jq -r '.attributes.tenant_id[0] // "<absent>"' "$WORKDIR/rb-vendor.json")"
  if [ "$(jq -r '.published' "$WORKDIR/rb-shop.json")" != true ] || [ "$(jq -r '.status' "$WORKDIR/rb-onb.json")" != LIVE ]; then
    echo "FAIL: the read-back does not show a published shop with a LIVE onboarding" >&2
    exit 1
  fi
}

# ===========================================================================
# --stage order (live; plan 29-18, decisions D-28 and D-31)
# ===========================================================================
#
# One storefront guest order, placed exactly as a customer's would be: the
# anonymous public menu, the prices and the allergen set it shows, an
# Idempotency-Key, and a card payment. D-31: the order is paid by a Stripe TEST
# PaymentIntent, confirmed server-side with pm_card_visa, and settles through
# the D-28 webhook (payment_intent.succeeded -> paymentStatus CAPTURED). There
# is no cash order: an order that comes back without a client secret and has no
# intent is a COD order, and the stage fails on it.
#
# The Stripe TEST key is read from Key Vault in memory (az_read, the same guard
# as the vendor stage), refused unless it begins sk_test_, and travels to curl
# only inside a mode-600 config file; it is never printed and never in argv.
# The client secret is held in memory only to derive the intent id from it.
# Every Stripe object read must say livemode false.
#
# A re-run with the same key replays the original confirmation: a paid order
# comes back with no client secret, so the intent is found by its
# order_number metadata, never created, and never confirmed again.

# azure_guard_once — source the subscription guard and run az_guard_init in
# THIS shell, once (--stage all reaches it from both stages).
_AZ_GUARD_DONE=0
azure_guard_once() {
  local rc=0
  [ "$_AZ_GUARD_DONE" -eq 0 ] || return 0
  # shellcheck source=lib/azure-guard.sh
  source "$SCRIPT_DIR/lib/azure-guard.sh"
  az_guard_init >/dev/null || rc=$?
  [ "$rc" -eq 0 ] || exit "$rc"
  _AZ_GUARD_DONE=1
}

# pub_api METHOD PATH OUT [BODY] [HEADER...] — the public API, anonymously.
pub_api() {
  local method="$1" path="$2" out="$3" body="${4:-}" code rc=0
  shift 3; [ "$#" -eq 0 ] || shift
  local -a hdr=(-H 'Accept: application/json')
  local h
  for h in "$@"; do hdr+=(-H "$h"); done
  if [ -n "$body" ]; then
    code=$(curl -sS -X "$method" "${hdr[@]}" -H 'Content-Type: application/json' \
      --data-binary "@$body" -o "$out" -w '%{http_code}' "${API_BASE}${path}") || rc=$?
  else
    code=$(curl -sS -X "$method" "${hdr[@]}" -o "$out" -w '%{http_code}' "${API_BASE}${path}") || rc=$?
  fi
  if [ "$rc" -ne 0 ]; then echo "000"; return 0; fi
  echo "$code"
}

# stripe_api METHOD PATH OUT [FORM-FIELD...] — Stripe, the key from the mode-600
# config file. Form fields go through --data-urlencode; none is a secret.
stripe_api() {
  local method="$1" path="$2" out="$3" code rc=0 f
  shift 3
  local -a form=()
  for f in "$@"; do form+=(--data-urlencode "$f"); done
  code=$(curl -sS -K "$WORKDIR/stripe.curlrc" -X "$method" ${form[@]+"${form[@]}"} \
    -o "$out" -w '%{http_code}' "${STRIPE_API}${path}") || rc=$?
  if [ "$rc" -ne 0 ]; then echo "000"; return 0; fi
  echo "$code"
}

# stripe_fail WHAT CODE OUT — Stripe's error type and message (never a secret).
stripe_fail() {
  local what="$1" code="$2" out="$3" why=""
  [ -s "$out" ] && why=$(jq -r '[.error.type, .error.code, .error.message] | map(select(. != null)) | join(" | ")' "$out" 2>/dev/null | head -c 300 || true)
  echo "FAIL: Stripe $what returned HTTP $code${why:+: $why}" >&2
  exit 1
}

# assert_test_mode FILE WHAT — the object (or every object of a list) must read
# livemode false. Anything else, including a missing field, is a refusal.
assert_test_mode() {
  local verdict
  verdict=$(jq -r 'if .object == "list" then ([.data[]?.livemode] | if length == 0 then "empty" elif all(. == false) then "false" else "not-false" end)
                   else (if .livemode == false then "false" else "livemode=\(.livemode)" end) end' "$1" 2>/dev/null || echo unparseable)
  case "$verdict" in
    false|empty) return 0 ;;
    *) refuse stripe-mode "$2 does not read livemode false ($verdict); a live object must never exist in staging (D-28)" ;;
  esac
}

# intents_for ORDER_NUMBER — sets INTENT_IDS (one per line) and INTENT_COUNT:
# the TEST intents whose metadata names the order, from the newest 100 (the
# seed's order is the only one it places). Called directly, never inside $(…),
# so a refusal or a Stripe failure exits the script rather than a subshell.
INTENT_IDS=""
INTENT_COUNT=0
intents_for() {
  local code
  code=$(stripe_api GET "/v1/payment_intents?limit=100" "$WORKDIR/pi-list.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/payment_intents" "$code" "$WORKDIR/pi-list.json"
  assert_test_mode "$WORKDIR/pi-list.json" "GET /v1/payment_intents"
  INTENT_IDS=$(jq -r --arg o "$1" '.data[] | select(.metadata.order_number == $o) | .id' "$WORKDIR/pi-list.json")
  INTENT_COUNT=$(jq -r --arg o "$1" '[.data[] | select(.metadata.order_number == $o)] | length' "$WORKDIR/pi-list.json")
}

order_stage() {
  local key code rc

  # 1. The subscription guard, then the Stripe TEST key into a mode-600 config
  #    file. A key that is not sk_test_ is refused before any API call.
  azure_guard_once
  need_tool curl
  ensure_workdir
  set +x
  rc=0; key=$(az_read keyvault secret show --vault-name "$KEY_VAULT_NAME" -n "$STRIPE_KV_OBJECT" --query value -o tsv) || rc=$?
  key="${key//$'\r'/}"; key="${key//$'\n'/}"
  if [ "$rc" -ne 0 ] || [ -z "$key" ]; then
    refuse key-vault "could not read $STRIPE_KV_OBJECT from $KEY_VAULT_NAME (az rc=$rc); plan 29-13 writes it"
  fi
  case "$key" in
    sk_test_*) ;;
    *) key=""; refuse stripe-mode "$STRIPE_KV_OBJECT is not a TEST key (it must begin sk_test_); staging never holds a live key (D-28)" ;;
  esac
  if ! [[ "$key" =~ ^sk_test_[A-Za-z0-9]+$ ]]; then
    key=""; refuse stripe-mode "$STRIPE_KV_OBJECT has characters a Stripe key never carries"
  fi
  (umask 077 && printf 'user = "%s:"\n' "$key" > "$WORKDIR/stripe.curlrc")
  key=""
  echo "OK: read the Stripe TEST key from $KEY_VAULT_NAME/$STRIPE_KV_OBJECT (sk_test_; value not shown)"

  # 2. The public shop and menu, as a customer's browser reads them.
  local fulfilment fee
  code=$(pub_api GET "/api/v1/public/shops/$SHOP_SLUG" "$WORKDIR/pub-shop.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/public/shops/$SHOP_SLUG" "$code" "$WORKDIR/pub-shop.json"
  if [ "$(jq -r '.offersCollection' "$WORKDIR/pub-shop.json")" = true ]; then
    fulfilment=COLLECTION; fee=0
  elif [ "$(jq -r '.offersDelivery' "$WORKDIR/pub-shop.json")" = true ]; then
    fulfilment=DELIVERY; fee=$(jq -r '.deliveryFeePennies // 0' "$WORKDIR/pub-shop.json")
  else
    echo "FAIL: $SHOP_SLUG offers neither collection nor delivery" >&2; exit 1
  fi
  code=$(pub_api GET "/api/v1/public/shops/$SHOP_SLUG/products" "$WORKDIR/pub-products.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/public/shops/$SHOP_SLUG/products" "$code" "$WORKDIR/pub-products.json"
  local pick pid price mask title
  pick=$(jq -c '[(if type == "object" and has("content") then .content[] elif type == "object" then .[][] else .[] end)
                 | select(.inStock == true)][0] // empty' "$WORKDIR/pub-products.json")
  [ -n "$pick" ] || { echo "FAIL: $SHOP_SLUG has no available product on its public menu; publish one as the vendor first" >&2; exit 1; }
  pid=$(jq -r '.id' <<<"$pick"); price=$(jq -r '.pricePennies' <<<"$pick")
  mask=$(jq -r '.allergenMask // 0' <<<"$pick"); title=$(jq -r '.title // .name // "?"' <<<"$pick")
  [[ "$price" =~ ^[0-9]+$ ]] && [[ "$mask" =~ ^[0-9]+$ ]] || { echo "FAIL: the menu entry for $pid has no integer price or allergen mask" >&2; exit 1; }
  echo "OK: basket = 1 x \"$title\" ($pid) at ${price}p, allergen mask $mask, $fulfilment, expected total $((price + fee))p"

  # 3. The order, with the Idempotency-Key, the prices and the allergen set the
  #    menu showed (the basket's union: one line, so its own mask).
  jq -n --arg n "$GUEST_NAME" --arg e "$GUEST_EMAIL" --arg ph "$GUEST_PHONE" --arg f "$fulfilment" \
        --arg p "$pid" --argjson pr "$price" --argjson m "$mask" --argjson t "$((price + fee))" '
      {customerName: $n, customerEmail: $e, customerPhone: $ph, fulfilmentType: $f,
       items: [{productId: $p, quantity: 1, expectedUnitPricePennies: $pr}],
       acknowledgedAllergenMask: $m, expectedTotalPennies: $t}
      + (if $f == "DELIVERY" then {addressLine1: "1 Staging Test Street", addressCity: "Birmingham", addressPostcode: "B1 1AA"} else {} end)' \
    | body "$WORKDIR/guest-order.json"
  code=$(pub_api POST "/api/v1/public/shops/$SHOP_SLUG/orders" "$WORKDIR/guest-order-out.json" "$WORKDIR/guest-order.json" \
    "Idempotency-Key: $ORDER_IDEMPOTENCY_KEY")
  [ "$code" = 201 ] || fail_http "POST /api/v1/public/shops/$SHOP_SLUG/orders" "$code" "$WORKDIR/guest-order-out.json"
  local order_no ostatus cs intent
  order_no=$(jq -r '.orderNumber // empty' "$WORKDIR/guest-order-out.json")
  ostatus=$(jq -r '.status // empty' "$WORKDIR/guest-order-out.json")
  cs=$(jq -r '.clientSecret // empty' "$WORKDIR/guest-order-out.json")
  rm -f "$WORKDIR/guest-order-out.json"
  [ -n "$order_no" ] || { echo "FAIL: the 201 carried no orderNumber" >&2; exit 1; }
  echo "OK: order $order_no status $ostatus (Idempotency-Key $ORDER_IDEMPOTENCY_KEY; client secret $([ -n "$cs" ] && echo present || echo absent), not shown)"

  # 4. Its intent: from the client secret on a first run (pi_X_secret_Y), else
  #    by metadata. Exactly one intent may name the order.
  if [ -n "$cs" ]; then
    intent="${cs%%_secret_*}"
    cs=""
    [[ "$intent" =~ ^pi_[A-Za-z0-9]+$ ]] || { echo "FAIL: the client secret does not carry a PaymentIntent id" >&2; exit 1; }
  else
    intents_for "$order_no"
    intent=$(head -n 1 <<<"$INTENT_IDS")
    if [ -z "$intent" ]; then
      echo "FAIL: order $order_no came back with no client secret and no TEST PaymentIntent names it: a cash (COD) order, which the seed must never place (D-31; is the Stripe key configured on core-java?)" >&2
      exit 1
    fi
    echo "OK: replay: $order_no is $ostatus, so its intent was found by metadata ($intent); nothing is created"
  fi

  # 5. Confirm it with the TEST card, unless it already succeeded.
  local pstatus
  code=$(stripe_api GET "/v1/payment_intents/$intent" "$WORKDIR/pi.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/payment_intents/$intent" "$code" "$WORKDIR/pi.json"
  assert_test_mode "$WORKDIR/pi.json" "PaymentIntent $intent"
  if [ "$(jq -r '.metadata.order_number // empty' "$WORKDIR/pi.json")" != "$order_no" ]; then
    echo "FAIL: PaymentIntent $intent names order '$(jq -r '.metadata.order_number // ""' "$WORKDIR/pi.json")', not $order_no" >&2; exit 1
  fi
  pstatus=$(jq -r '.status' "$WORKDIR/pi.json")
  case "$pstatus" in
    succeeded)
      echo "SKIP: PaymentIntent $intent already succeeded; not confirmed again" ;;
    requires_payment_method|requires_confirmation)
      code=$(stripe_api POST "/v1/payment_intents/$intent/confirm" "$WORKDIR/pi-confirm.json" \
        "payment_method=pm_card_visa" "return_url=$ORDER_RETURN_URL")
      [ "$code" = 200 ] || stripe_fail "POST /v1/payment_intents/$intent/confirm" "$code" "$WORKDIR/pi-confirm.json"
      assert_test_mode "$WORKDIR/pi-confirm.json" "the confirmed PaymentIntent $intent"
      echo "CONFIRMED: PaymentIntent $intent with pm_card_visa (status $(jq -r '.status' "$WORKDIR/pi-confirm.json"))" ;;
    *)
      echo "FAIL: PaymentIntent $intent is $pstatus, which the seed cannot settle" >&2; exit 1 ;;
  esac
  code=$(stripe_api GET "/v1/payment_intents/$intent" "$WORKDIR/pi.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/payment_intents/$intent" "$code" "$WORKDIR/pi.json"
  assert_test_mode "$WORKDIR/pi.json" "PaymentIntent $intent"
  pstatus=$(jq -r '.status' "$WORKDIR/pi.json")
  [ "$pstatus" = succeeded ] || { echo "FAIL: PaymentIntent $intent is $pstatus after the confirm, not succeeded" >&2; exit 1; }
  echo "OK: PaymentIntent $intent succeeded, livemode false, amount $(jq -r '.amount' "$WORKDIR/pi.json") $(jq -r '.currency' "$WORKDIR/pi.json")"

  # 6. One intent for this order, no more.
  intents_for "$order_no"
  [ "$INTENT_COUNT" -eq 1 ] || { echo "FAIL: $INTENT_COUNT TEST PaymentIntents name order $order_no (want exactly 1)" >&2; exit 1; }
  echo "OK: exactly 1 TEST PaymentIntent names $order_no"

  # 7. The webhook settled it: the public tracking API reads CAPTURED.
  local t0 track tstatus tpay q
  q=$(jq -rn --arg e "$GUEST_EMAIL" '$e | @uri')
  t0=$(date +%s)
  while :; do
    code=$(pub_api GET "/api/v1/public/orders/$order_no?email=$q" "$WORKDIR/track.json")
    if [ "$code" = 200 ]; then
      tstatus=$(jq -r '.status // ""' "$WORKDIR/track.json"); tpay=$(jq -r '.paymentStatus // ""' "$WORKDIR/track.json")
      track="HTTP 200 status $tstatus paymentStatus $tpay"
      [ "$tpay" != CAPTURED ] || break
    else
      track="HTTP $code"
    fi
    if [ $(( $(date +%s) - t0 )) -ge "$PAID_DEADLINE_SECONDS" ]; then
      echo "FAIL: order $order_no did not read paid within ${PAID_DEADLINE_SECONDS}s (deadline); last tracking read: $track" >&2
      exit 1
    fi
    sleep "$POLL_SECONDS"
  done
  echo "OK: tracking reads $order_no $track after $(( $(date +%s) - t0 ))s"

  # 8. The event Stripe delivered, by id (for the webhook evidence).
  local evt
  code=$(stripe_api GET "/v1/events?type=payment_intent.succeeded&limit=100" "$WORKDIR/events.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/events" "$code" "$WORKDIR/events.json"
  assert_test_mode "$WORKDIR/events.json" "GET /v1/events"
  evt=$(jq -c --arg i "$intent" '[.data[] | select(.data.object.id == $i)][0] // empty' "$WORKDIR/events.json")
  [ -n "$evt" ] || { echo "FAIL: no payment_intent.succeeded event names $intent" >&2; exit 1; }
  echo "STRIPE-EVENT id=$(jq -r '.id' <<<"$evt") type=payment_intent.succeeded intent=$intent pending_webhooks=$(jq -r '.pending_webhooks' <<<"$evt") created=$(jq -r '.created' <<<"$evt")"

  # 9. The verdict, one machine-checkable line, last.
  echo "SEED-ORDER order=$order_no intent=$intent status=$tstatus payment_status=$tpay livemode=$(jq -r '.livemode' "$WORKDIR/pi.json") at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

# ===========================================================================
# The hand tools (plan 30-07; issue #958; decisions D-01 and D-02)
# ===========================================================================
#
# The 2026-10-19 swarm left unpaid card DRAFTs on staging, each with a live
# requires_payment_method PaymentIntent and the customer's details. D-02: there
# is no sweeper; they are cancelled by hand, one owner-confirmed order at a time.
# These stages are that hand: a read-only listing, a vendor order for the D-01
# control, a single transition whose answer is printed, the cancellation of an
# explicit list, and a sanitised Stripe read. Every one runs run_guards first.
# Nothing here prints a customer's name, email, phone, address or note: order
# fields reach stdout only through a jq projection that selects named keys.

# vendor_login — the vendor's password and the core-api client secret from Key
# Vault into mode-600 files (exactly as vendor_stage reads them), then a token.
vendor_login() {
  local row name obj v rc
  azure_guard_once
  need_tool curl
  ensure_workdir
  set +x
  for row in "${KV_MAP[@]}"; do
    name="${row%%=*}"; obj="${row#*=}"
    case "$name" in VENDOR_PASSWORD|CORE_API_CLIENT_SECRET) ;; *) continue ;; esac
    rc=0; v=$(az_read keyvault secret show --vault-name "$KEY_VAULT_NAME" -n "$obj" --query value -o tsv) || rc=$?
    if [ "$rc" -ne 0 ] || [ -z "$v" ]; then
      refuse key-vault "could not read $obj from $KEY_VAULT_NAME (az rc=$rc); plan 29-13 generates it"
    fi
    case "$name" in
      VENDOR_PASSWORD)        (umask 077 && printf '%s' "$v" > "$WORKDIR/vendor-password") ;;
      CORE_API_CLIENT_SECRET) (umask 077 && printf '%s' "$v" > "$WORKDIR/kv-client-secret") ;;
    esac
  done
  v=""
  (umask 077 && printf '%s' "$VENDOR_USERNAME" > "$WORKDIR/vendor-username")
  echo "OK: read 2 Key Vault objects from $KEY_VAULT_NAME (values not shown)"
  user_token vendor || { echo "FAIL: the vendor $VENDOR_USERNAME could not sign in" >&2; exit 1; }
  echo "OK: signed in as $VENDOR_USERNAME"
}

# orders_in_status STATUS OUT — every page of GET /api/v1/orders/status/STATUS,
# as one JSON array in OUT (the vendor's grant-scoped view). A response without a
# content array is a failure, never "no orders".
orders_in_status() {
  local st="$1" out="$2" page=0 pages code
  printf '[]' | body "$out"
  while :; do
    code=$(api vendor GET "/api/v1/orders/status/$st?size=100&page=$page&sort=createdAt,asc" "$WORKDIR/orders-page.json")
    [ "$code" = 200 ] || fail_http "GET /api/v1/orders/status/$st (page $page)" "$code" "$WORKDIR/orders-page.json"
    if ! jq -e '(.content | type) == "array"' "$WORKDIR/orders-page.json" >/dev/null 2>&1; then
      echo "FAIL: GET /api/v1/orders/status/$st (page $page) did not return a page with a content array" >&2; exit 1
    fi
    jq -s '.[0] + .[1].content' "$out" "$WORKDIR/orders-page.json" | body "$out.next"
    cp "$out.next" "$out"; rm -f "$out.next"
    pages=$(jq -r '.totalPages // .page.totalPages // 1' "$WORKDIR/orders-page.json")
    page=$((page + 1))
    [ "$page" -lt "$pages" ] || break
    [ "$page" -lt 50 ] || { echo "FAIL: more than 50 pages of $st orders; refusing to guess" >&2; exit 1; }
  done
  rm -f "$WORKDIR/orders-page.json"
}

# find_order_id NUMBER STATUS... — sets FOUND_ID to the id of the order numbered
# NUMBER, searching the listed statuses in order. Called directly, never in $(…).
FOUND_ID=""
FOUND_STATUS=""
find_order_id() {
  local n="$1" st hits
  shift
  FOUND_ID=""; FOUND_STATUS=""
  for st in "$@"; do
    orders_in_status "$st" "$WORKDIR/find-$st.json"
    hits=$(jq -r --arg n "$n" '[.[] | select(.orderNumber == $n) | .id] | length' "$WORKDIR/find-$st.json")
    if [ "$hits" -gt 1 ]; then echo "FAIL: $hits $st orders are numbered $n" >&2; exit 1; fi
    if [ "$hits" -eq 1 ]; then
      FOUND_ID=$(jq -r --arg n "$n" '.[] | select(.orderNumber == $n) | .id' "$WORKDIR/find-$st.json")
      FOUND_STATUS="$st"
      return 0
    fi
  done
  return 1
}

# --stage list-drafts — READ-ONLY. One line per DRAFT, then the count.
list_drafts_stage() {
  vendor_login
  orders_in_status DRAFT "$WORKDIR/drafts.json"
  jq -r 'map({orderNumber, createdAt, paymentStatus, paymentReference, totalAmountPennies})[]
    | "DRAFT-CANDIDATE order=\(.orderNumber) created=\(.createdAt) payment_status=\(.paymentStatus // "NONE") intent=\(if (.paymentReference // "") != "" then "present" else "none" end) total=\(.totalAmountPennies)p"' \
    "$WORKDIR/drafts.json"
  echo "LIST-DRAFTS count=$(jq 'length' "$WORKDIR/drafts.json") card=$(jq '[.[] | select((.paymentReference // "") != "")] | length' "$WORKDIR/drafts.json") at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

# --stage vendor-order — ONE vendor order at the staging test shop, made the way
# the dashboard makes it: no customer field, submit unset, so it stays a DRAFT
# with paymentStatus NONE (the D-01 control for plan 30-08's live #958 arms).
vendor_order_stage() {
  local code shop_id pid stamp out ost ops
  vendor_login
  code=$(api vendor GET "/api/v1/shops?size=100" "$WORKDIR/shops.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/shops" "$code" "$WORKDIR/shops.json"
  shop_id=$(jq -r --arg s "$SHOP_SLUG" '[.content[]? | select(.slug == $s)][0].id // empty' "$WORKDIR/shops.json")
  [ -n "$shop_id" ] || { echo "FAIL: the vendor holds no shop $SHOP_SLUG" >&2; exit 1; }
  code=$(pub_api GET "/api/v1/public/shops/$SHOP_SLUG/products" "$WORKDIR/pub-products.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/public/shops/$SHOP_SLUG/products" "$code" "$WORKDIR/pub-products.json"
  pid=$(jq -r '[(if type == "object" and has("content") then .content[] elif type == "object" then .[][] else .[] end)
                | select(.inStock == true)][0].id // empty' "$WORKDIR/pub-products.json")
  [ -n "$pid" ] || { echo "FAIL: $SHOP_SLUG has no available product on its public menu" >&2; exit 1; }
  jq -n --arg s "$shop_id" --arg p "$pid" '{shopId: $s, items: [{productId: $p, quantity: 1}]}' | body "$WORKDIR/vendor-order.json"
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  API_EXTRA_HEADER="Idempotency-Key: vendor-order-$stamp"
  code=$(api vendor POST /api/v1/orders "$WORKDIR/vendor-order-out.json" "$WORKDIR/vendor-order.json")
  API_EXTRA_HEADER=""
  [ "$code" = 201 ] || fail_http "POST /api/v1/orders (vendor order)" "$code" "$WORKDIR/vendor-order-out.json"
  out=$(jq -c '{orderNumber, status, paymentStatus}' "$WORKDIR/vendor-order-out.json")
  rm -f "$WORKDIR/vendor-order-out.json"
  ost=$(jq -r '.status // ""' <<<"$out"); ops=$(jq -r '.paymentStatus // ""' <<<"$out")
  if [ "$ost" != DRAFT ]; then
    echo "FAIL: the vendor order $(jq -r '.orderNumber' <<<"$out") came back with status $ost, not DRAFT; a control that is not a NONE DRAFT proves nothing about D-01" >&2; exit 1
  fi
  if [ "$ops" != NONE ]; then
    echo "FAIL: the vendor order $(jq -r '.orderNumber' <<<"$out") came back with paymentStatus $ops, not NONE; a control that is not a NONE DRAFT proves nothing about D-01" >&2; exit 1
  fi
  echo "VENDOR-ORDER order=$(jq -r '.orderNumber' <<<"$out") status=DRAFT payment_status=NONE"
}

# --stage order-transition — one POST to exactly the named order; the answer is
# printed, not judged (a 409 awaiting-payment is the expected #958 refusal).
# rc 0 when the API answered the transition (2xx, 400, 409, 422); rc 1 when the
# call did not reach a verdict (no connection, 401, 403, 404, 5xx).
order_transition_stage() {
  local code ptype pdetail
  vendor_login
  find_order_id "$ORDERS" DRAFT PENDING CONFIRMED \
    || { echo "FAIL: no DRAFT, PENDING or CONFIRMED order is numbered $ORDERS in the vendor's view" >&2; exit 1; }
  echo "OK: $ORDERS is $FOUND_STATUS ($FOUND_ID)"
  code=$(api vendor POST "/api/v1/orders/$FOUND_ID/$EVENT" "$WORKDIR/transition.json")
  ptype=none; pdetail=none
  case "$code" in
    4*|5*)
      ptype=$(jq -r 'if type == "object" and (.type | type) == "string" then .type else "none" end' "$WORKDIR/transition.json" 2>/dev/null || echo none)
      pdetail=$(jq -j 'if type == "object" and (.detail | type) == "string" then .detail else "none" end' "$WORKDIR/transition.json" 2>/dev/null | tr '\n' ' ' | head -c 300 || echo none) ;;
  esac
  rm -f "$WORKDIR/transition.json"
  echo "TRANSITION order=$ORDERS event=$EVENT http=$code type=$ptype detail=$pdetail"
  case "$code" in
    2??|400|409|422) return 0 ;;
    *) echo "FAIL: POST /api/v1/orders/{id}/$EVENT did not reach a verdict (HTTP $code)" >&2; exit 1 ;;
  esac
}

# ===========================================================================
# --selftest
# ===========================================================================
_ST_RESULTS=()
_st_result() { _ST_RESULTS+=("$1"); }
_st_render() {
  local i=0 r verdict rest
  if [ "${STAGING_SEED_SELFTEST_FORMAT:-plain}" = tap ]; then
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

# The stub core-java pod list, in the shape `kubectl get pods -o json` returns.
#   _st_pod NAME READY(True|False) PROFILE
_st_pod() {
  jq -n --arg n "$1" --arg r "$2" --arg p "$3" '{
    metadata: {name: $n, labels: {app: "core-java"}},
    spec: {containers: [{name: "core-java", env: [{name: "SPRING_PROFILES_ACTIVE", value: $p}, {name: "DB_HOST", value: "x"}]}]},
    status: {phase: "Running", conditions: [{type: "Ready", status: $r}]}
  }'
}
_st_pods() { jq -s '{apiVersion: "v1", kind: "List", items: .}'; }

selftest() {
  local tmp real_bash real_kubectl fails=0 why t p ast
  real_bash=$(type -P bash) || { echo "VOID: bash not found" >&2; return 2; }
  command -v jq >/dev/null 2>&1 || { echo "VOID: jq not on PATH" >&2; return 2; }
  if ! real_kubectl=$(type -P kubectl); then
    echo "VOID: the selftest needs a real kubectl for the guard's local config view read" >&2; return 2
  fi
  ensure_workdir
  tmp="$WORKDIR/selftest"
  ast="$tmp"
  mkdir -p "$ast/stub" "$ast/tools" "$ast/logs" "$ast/home" "$ast/resp"

  # A tools directory holds only what the script needs, so no real curl, az,
  # kubelogin or kubectl can stand in for a stub.
  for t in bash env sed awk grep cat mktemp rm mkdir date tr wc head tail cut sort comm dirname basename sleep cp chmod jq openssl base64; do
    p=$(type -P "$t") || { echo "VOID: selftest tool not found: $t" >&2; return 2; }
    ln -s "$p" "$ast/tools/$t"
  done
  # kubectl: argv logged; `config` (the guard's local kubeconfig read) goes to the
  # real binary; `get pods` answers from STUB_PODS_FILE; a port-forward stays up
  # until it is killed (by PID, as the script must); `get deployment` answers a
  # prod template, which a correct profile guard never asks for.
  cat > "$ast/stub/kubectl" <<STUB
#!$real_bash
printf '%s\n' "\$*" >> "\$STUB_LOG_DIR/kubectl.log"
case "\${1:-}" in config) exec "\$REAL_KUBECTL" "\$@" ;; esac
case " \$* " in
  *" get pods "*) cat "\${STUB_PODS_FILE:-/dev/null}"; exit 0 ;;
  *" get deployment"*|*" get deploy "*) printf '%s\n' '{"spec":{"template":{"spec":{"containers":[{"name":"core-java","env":[{"name":"SPRING_PROFILES_ACTIVE","value":"prod"}]}]}}}}'; exit 0 ;;
  *" port-forward "*) exec sleep 600 ;;
esac
exit 0
STUB
  printf '#!%s\nprintf "%%s\\n" "$*" >> "$STUB_LOG_DIR/kubelogin.log"\nexit 0\n' "$real_bash" > "$ast/stub/kubelogin"
  # curl: argv logged in full (so a secret in argv is detectable), one
  # "METHOD URL -> CODE" flow line per call, answered from STUB_RESP_DIR.
  cat > "$ast/stub/curl" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$STUB_LOG_DIR/curl.log"
out=""; wfmt=""; hdr=""; method=""; url=""; data=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    -w) wfmt="$2"; shift 2 ;;
    -D) hdr="$2"; shift 2 ;;
    -X) method="$2"; shift 2 ;;
    --data-binary) data=1; case "$2" in @*) jq -c . "${2#@}" >> "$STUB_LOG_DIR/bodies.log" 2>/dev/null || true ;; esac; shift 2 ;;
    --data-urlencode|-d) data=1; shift 2 ;;
    -K|-H) shift 2 ;;
    http://*|https://*) url="$1"; shift ;;
    *) shift ;;
  esac
done
[ -n "$method" ] || { if [ "$data" -eq 1 ]; then method=POST; else method=GET; fi; }
rest="${url#*://}"; path="/${rest#*/}"; base="${path%%\?*}"; query=""
case "$path" in *\?*) query="${path#*\?}" ;; esac
key=""; code=404; location=""
case "$method $base" in
  "GET /health/ready") key=empty; code=200 ;;
  "POST /realms/master/protocol/openid-connect/token") key=admin-token; code=200 ;;
  "POST /realms/jtoye-staging/protocol/openid-connect/token") key=user-token; code=200 ;;
  "POST /admin/realms/jtoye-staging/users") key=empty; code=201; location="http://127.0.0.1:18080/admin/realms/jtoye-staging/users/op-0001" ;;
  "GET /admin/realms/jtoye-staging/users")
    case "$query" in *vendor.staging*) key=vendor-list ;; *) key=empty-list ;; esac; code=200 ;;
  "GET /admin/realms/jtoye-staging/roles/admin") key=role-admin; code=200 ;;
  "POST /admin/realms/jtoye-staging/users/"*"/role-mappings/realm") key=empty; code=204 ;;
  "GET /admin/realms/jtoye-staging/users/op-0001") key=operator; code=200 ;;
  "GET /admin/realms/jtoye-staging/users/vendor-0001") key=vendor; code=200 ;;
  "PUT /admin/realms/jtoye-staging/users/"*) key=empty; code=204 ;;
  "DELETE /admin/realms/jtoye-staging/users/"*) key=empty; code=204 ;;
  "GET /api/v1/admin/tenants") key=tenants; code=200 ;;
  "GET /api/v1/staff/me") key=staff-me; code=200 ;;
  "GET /api/v1/shops") key=shops; code=200 ;;
  "GET /api/v1/products") key=products; code=200 ;;
  "GET /api/v1/products/culinary-packs") key=packs; code=200 ;;
  "POST /api/v1/products/culinary-packs/seed") key=seed; code=200 ;;
  "GET /api/v1/shops/"*) key=shop; code=200 ;;
  "GET /api/v1/trader-identity") key=identity; code=200 ;;
  "GET /api/v1/onboarding/me") key=onboarding; code=200 ;;
  # The order stage (plan 29-18): the anonymous storefront, then Stripe TEST.
  # A confirm leaves a marker, so the intent reads succeeded only after it.
  "GET /api/v1/public/shops/staging-test-kitchen") key=pub-shop; code=200 ;;
  "GET /api/v1/public/shops/staging-test-kitchen/products") key=pub-products; code=200 ;;
  "POST /api/v1/public/shops/staging-test-kitchen/orders") key=guest-order; code=201 ;;
  "GET /api/v1/public/orders/"*) key=tracking; code=200 ;;
  "POST /v1/payment_intents/"*"/confirm") key=pi-confirm; code=200; : > "$STUB_LOG_DIR/confirmed" ;;
  "GET /v1/payment_intents") key=pi-list; code=200 ;;
  "GET /v1/payment_intents/"*) if [ -f "$STUB_LOG_DIR/confirmed" ]; then key=pi-after; else key=pi-before; fi; code=200 ;;
  "GET /v1/events") key=events; code=200 ;;
  # The hand tools (plan 30-07): the vendor's order lists and transitions.
  "GET /api/v1/orders/status/"*) key="orders-status-${base##*/}"; code=200 ;;
  "POST /api/v1/orders") key=vendor-order-out; code=201 ;;
  "POST /api/v1/orders/"*"/submit"|"POST /api/v1/orders/"*"/confirm"|"POST /api/v1/orders/"*"/start-preparation")
    key=transition; code="${STUB_TRANSITION_CODE:-200}" ;;
esac
if [ -n "${STUB_FAIL_ON:-}" ] && [[ "$method $base" =~ $STUB_FAIL_ON ]]; then key=problem; code=500; fi
printf '%s %s -> %s\n' "$method" "$url" "$code" >> "$STUB_LOG_DIR/curl-flow.log"
if [ -n "$out" ]; then
  if [ -n "$key" ] && [ -f "$STUB_RESP_DIR/$key.json" ]; then cat "$STUB_RESP_DIR/$key.json" > "$out"; else printf '{}' > "$out"; fi
fi
if [ -n "$hdr" ]; then
  { printf 'HTTP/1.1 %s\r\n' "$code"; [ -z "$location" ] || printf 'Location: %s\r\n' "$location"; printf '\r\n'; } > "$hdr"
fi
[ -z "$wfmt" ] || printf '%s' "$code"
exit 0
STUB
  chmod +x "$ast/stub/kubectl" "$ast/stub/kubelogin" "$ast/stub/curl"
  ( source "$SCRIPT_DIR/lib/azure-guard.sh" && az_guard_stub "$ast/stub" ) \
    || { echo "VOID: az_guard_stub failed" >&2; return 2; }

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

  # Pod lists, one per profile arm.
  _st_pod core-java-a True prod | _st_pods > "$ast/pods-prod.json"
  _st_pod core-java-a True dev | _st_pods > "$ast/pods-dev.json"
  { _st_pod core-java-new True prod; _st_pod core-java-old True dev; } | _st_pods > "$ast/pods-mixed.json"
  { _st_pod core-java-a False prod; _st_pod core-java-b False prod; } | _st_pods > "$ast/pods-none-ready.json"

  # Every Key Vault read answers a value beginning STUB-SECRET-, so an echo of a
  # secret anywhere in this selftest's output or in any curl argv is detectable.
  local row obj
  : > "$ast/answers.tsv"
  for row in "${KV_MAP[@]}"; do
    obj="${row#*=}"
    printf '%s\t0\t%s\n' "-n ${obj} --query" "STUB-SECRET-${obj}-7f3a91" >> "$ast/answers.tsv"
  done
  # The Stripe key must look like a TEST key to pass the order stage's guard; a
  # second answers file holds a LIVE-looking one for the refusal arm. Both are
  # composed at run time, so no key-shaped literal sits in this file for a
  # secret scanner to flag (they are synthetic and reach no Stripe account).
  local stub_sk="sk"
  cp "$ast/answers.tsv" "$ast/answers-live.tsv"
  printf '%s\t0\t%s_test_%s\n' "-n stripe-credentials--api-key --query" "$stub_sk" "STUBSECRETstripe7f3a91" >> "$ast/answers.tsv"
  printf '%s\t0\t%s_live_%s\n' "-n stripe-credentials--api-key --query" "$stub_sk" "STUBSECRETstripe7f3a91" >> "$ast/answers-live.tsv"

  # _st_run [VAR=value ...] -- ARGS... — the script as a scrubbed child.
  _st_run() {
    local extra=()
    while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do extra+=("$1"); shift; done
    shift
    : > "$ast/logs/kubectl.log"; : > "$ast/logs/kubelogin.log"; : > "$ast/logs/curl.log"
    : > "$ast/logs/curl-flow.log"; : > "$ast/logs/az.log"; : > "$ast/logs/bodies.log"; rm -f "$ast/logs/confirmed"
    ST_OUT=$(command env -i PATH="$ast/stub:$ast/tools" HOME="$ast/home" KUBECONFIG="$ast/kubeconfig" \
      STUB_LOG_DIR="$ast/logs" STUB_RESP_DIR="$ast/resp" REAL_KUBECTL="$real_kubectl" AZ_STUB_LOG="$ast/logs/az.log" \
      JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94 AZ_STUB_ANSWERS="$ast/answers.tsv" \
      ${extra[@]+"${extra[@]}"} "$real_bash" "$SCRIPT_PATH" "$@" 2>"$ast/err"); ST_RC=$?
    ST_ERR=$(cat "$ast/err")
  }
  _st_lines() { local f="$ast/logs/$1.log"; if [ -s "$f" ]; then command grep -c '' "$f"; else echo 0; fi; }
  _st_kubectl_other() {
    # kubectl calls that are neither the guard's local `config view` nor the pod read
    if [ -s "$ast/logs/kubectl.log" ]; then
      command grep -vE '^config view |^--context [^ ]+ -n jtoye-staging get pods -l app=core-java -o json$' "$ast/logs/kubectl.log" | command grep -c '' || true
    else echo 0; fi
  }
  _st_pod_reads() { if [ -s "$ast/logs/kubectl.log" ]; then command grep -c ' get pods ' "$ast/logs/kubectl.log" || true; else echo 0; fi; }
  _st_config_reads() { if [ -s "$ast/logs/kubectl.log" ]; then command grep -c '^config view ' "$ast/logs/kubectl.log" || true; else echo 0; fi; }
  _st_leaks() { command grep -cE 'STUB-SECRET-|STUBSECRET|STUBCS' <<<"$ST_OUT$ST_ERR$(cat "$ast/logs/curl.log")" || true; }

  # _st_refusal WANT_RC WANT_TEXT MAX_CONFIG MAX_PODS — rc, the stderr tag, then
  # the logs: no az, no curl, no kubectl beyond the config read and the pod read.
  _st_refusal() {
    local want_rc="$1" want_text="$2" max_config="$3" max_pods="$4"
    if [ "$ST_RC" -ne "$want_rc" ]; then echo "rc=$ST_RC (want $want_rc): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"; return 1; fi
    if ! command grep -qF -- "$want_text" <<<"$ST_ERR"; then echo "stderr lacks '$want_text': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"; return 1; fi
    if [ "$(_st_lines az)" -ne 0 ]; then echo "az calls: $(_st_lines az)"; return 1; fi
    if [ "$(_st_lines curl)" -ne 0 ]; then echo "curl calls: $(_st_lines curl)"; return 1; fi
    if [ "$(_st_kubectl_other)" -ne 0 ]; then echo "kubectl calls beyond config view and the pod read: $(_st_kubectl_other)"; return 1; fi
    if [ "$(_st_config_reads)" -gt "$max_config" ]; then echo "config reads=$(_st_config_reads) (max $max_config)"; return 1; fi
    if [ "$(_st_pod_reads)" -gt "$max_pods" ]; then echo "pod reads=$(_st_pod_reads) (max $max_pods)"; return 1; fi
    if [ "$(_st_leaks)" -ne 0 ]; then echo "secret marker printed"; return 1; fi
    return 0
  }
  local good=(--context jtoye-stg-aks --identity-file "$ast/identity.yaml" --stage vendor)

  # --- prod-api / local-api: any API base but the staging one ----------------
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${good[@]}" --api https://api.olajay.co.uk
  if why=$(_st_refusal 1 "REFUSED [api-base]" 1 0); then _st_result "PASS prod-api rc=$ST_RC az_calls=0 curl_calls=0 pod_reads=0"
  else _st_result "FAIL prod-api $why"; fails=$((fails + 1)); fi
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${good[@]}" --api http://localhost:9090
  if why=$(_st_refusal 1 "REFUSED [api-base]" 1 0); then _st_result "PASS local-api rc=$ST_RC az_calls=0 curl_calls=0 pod_reads=0"
  else _st_result "FAIL local-api $why"; fails=$((fails + 1)); fi

  # --- no-context ------------------------------------------------------------
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- --identity-file "$ast/identity.yaml" --stage vendor
  if why=$(_st_refusal 2 "USAGE ERROR" 0 0); then _st_result "PASS no-context rc=$ST_RC kubectl_calls=$(_st_lines kubectl)"
  else _st_result "FAIL no-context $why"; fails=$((fails + 1)); fi

  # --- stale-server: the shared staging_kube_guard is wired, not re-implemented
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- --context jtoye-staging --identity-file "$ast/identity.yaml" --stage vendor
  if why=$(_st_refusal 1 "REFUSED [server-url]" 1 0); then _st_result "PASS stale-server rc=$ST_RC kubectl_calls=$(_st_lines kubectl)(config view)"
  else _st_result "FAIL stale-server $why"; fails=$((fails + 1)); fi

  # --- the profile arms: read from the Ready pods, never the template --------
  local arm file
  for arm in dev-profile rollout-mixed no-ready-pod; do
    case "$arm" in
      dev-profile) file="$ast/pods-dev.json" ;;
      rollout-mixed) file="$ast/pods-mixed.json" ;;
      no-ready-pod) file="$ast/pods-none-ready.json" ;;
    esac
    _st_run STUB_PODS_FILE="$file" -- "${good[@]}"
    if why=$(_st_refusal 1 "REFUSED [profile]" 1 1); then
      if [ "$(_st_pod_reads)" -ne 1 ]; then why="pod reads=$(_st_pod_reads) (want 1: the refusal must come from the pods)"; fi
    fi
    if [ -z "$why" ]; then _st_result "PASS $arm rc=$ST_RC pod_reads=1 deployment_reads=0 az_calls=0 curl_calls=0"
    else _st_result "FAIL $arm $why"; fails=$((fails + 1)); fi
    why=""
  done

  # --- dry-run-plan: guards pass, the plan names every step, nothing is called
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${good[@]}" --dry-run
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_lines az)" -ne 0 ] || [ "$(_st_lines curl)" -ne 0 ]; then why="az=$(_st_lines az) curl=$(_st_lines curl) (want 0, 0)"
  elif [ "$(_st_kubectl_other)" -ne 0 ]; then why="kubectl calls beyond config view and the pod read: $(_st_kubectl_other)"
  elif [ "$(_st_config_reads)" -lt 1 ] || [ "$(_st_pod_reads)" -ne 1 ]; then why="guards did not run: config reads=$(_st_config_reads) pod reads=$(_st_pod_reads) (want >=1, 1)"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="secret marker printed"
  else
    local step
    for step in /api/v1/admin/tenants /api/v1/onboarding /api/v1/trader-identity /api/v1/products/culinary-packs/seed /api/v1/onboarding/go-live; do
      command grep -qF -- "$step" <<<"$ST_OUT" || { why="the plan does not name $step"; break; }
    done
  fi
  if [ -z "$why" ]; then _st_result "PASS dry-run-plan rc=0 steps=$(command grep -cE '^  [0-9]{2} ' <<<"$ST_OUT") az_calls=0 curl_calls=0"
  else _st_result "FAIL dry-run-plan $why"; fails=$((fails + 1)); fi

  # --- list-kv-objects: offline, exactly the four names ----------------------
  _st_run -- --list-kv-objects
  local want_kv
  want_kv=$(printf '%s\n' keycloak-credentials--admin-password keycloak-credentials--admin-username \
    keycloak-credentials--frontend-client-secret staging-seed--test-vendor-password stripe-credentials--api-key)
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0)"
  elif [ "$ST_OUT" != "$want_kv" ]; then why="listed '$(tr '\n' ' ' <<<"$ST_OUT")'"
  elif [ "$(_st_lines kubectl)" -ne 0 ] || [ "$(_st_lines az)" -ne 0 ] || [ "$(_st_lines curl)" -ne 0 ]; then why="a tool was called"
  fi
  if [ -z "$why" ]; then _st_result "PASS list-kv-objects rc=0 objects=5"
  else _st_result "FAIL list-kv-objects $why"; fails=$((fails + 1)); fi

  # --- operator-deleted-on-failure: the whole vendor stage against the stubs,
  #     with the culinary-pack seed answering 500. The stage must exit non-zero,
  #     and the stub log must show the DELETE of the operator AFTER that 500.
  local tid="11111111-2222-4333-8444-555555555555" sid="66666666-7777-4888-9999-000000000000" jwt
  jwt="eyJhbGciOiJub25lIn0.$(jq -cn --arg t "$tid" '{sub: "vendor-0001", tenant_id: $t, azp: "core-api"}' \
    | base64 | tr -d '\n=' | tr '/+' '_-').stub"
  printf '{}' > "$ast/resp/empty.json"
  printf '[]' > "$ast/resp/empty-list.json"
  printf '{"access_token":"stub-admin-token","expires_in":60}' > "$ast/resp/admin-token.json"
  jq -n --arg j "$jwt" '{access_token: $j, token_type: "Bearer"}' > "$ast/resp/user-token.json"
  printf '{"id":"role-admin","name":"admin"}' > "$ast/resp/role-admin.json"
  jq -n --arg t "$tid" '{id: "op-0001", username: "seed-operator-x", firstName: "Seed", lastName: "Operator", attributes: {tenant_id: [$t]}}' > "$ast/resp/operator.json"
  jq -n --arg t "$tid" --arg u "$VENDOR_USERNAME" '{id: "vendor-0001", username: $u, attributes: {tenant_id: [$t]}}' > "$ast/resp/vendor.json"
  jq -n --arg t "$tid" --arg u "$VENDOR_USERNAME" '[{id: "vendor-0001", username: $u, attributes: {tenant_id: [$t]}}]' > "$ast/resp/vendor-list.json"
  jq -n --arg t "$tid" --arg n "$TENANT_NAME" '[{id: $t, name: $n, status: "ACTIVE"}]' > "$ast/resp/tenants.json"
  printf '{"userId":"vendor-0001","groupAdmin":true}' > "$ast/resp/staff-me.json"
  jq -n --arg s "$sid" --arg slug "$SHOP_SLUG" '{content: [{id: $s, slug: $slug, published: false}], totalElements: 1}' > "$ast/resp/shops.json"
  printf '{"content":[],"totalElements":0}' > "$ast/resp/products.json"
  printf '[{"cuisineKey":"nigerian","cuisineLabel":"Nigerian","dishCount":6,"dishes":[]}]' > "$ast/resp/packs.json"
  printf '{"type":"https://api.jtoye.co.uk/errors/internal","title":"Internal Server Error","status":500}' > "$ast/resp/problem.json"

  _st_run STUB_PODS_FILE="$ast/pods-prod.json" STUB_FAIL_ON='^POST /api/v1/products/culinary-packs/seed$' \
    STAGING_SEED_POLL_SECONDS=0 STAGING_SEED_READY_DEADLINE_SECONDS=5 -- "${good[@]}"
  why=""
  local n500 ndel kv_reads pinned argv_leaks creates
  n500=$(command grep -nE '^POST https://api-staging\.olajay\.co\.uk/api/v1/products/culinary-packs/seed -> 500$' "$ast/logs/curl-flow.log" | head -1 | cut -d: -f1 || true)
  ndel=$(command grep -nE '^DELETE http://127\.0\.0\.1:18080/admin/realms/jtoye-staging/users/op-0001 -> 204$' "$ast/logs/curl-flow.log" | head -1 | cut -d: -f1 || true)
  kv_reads=$(command grep -c '^keyvault secret show --vault-name jtoye-stg-kv ' "$ast/logs/az.log" || true)
  pinned=$(command grep -c -- '--subscription c483d353-5f61-4587-a790-addb9ab5fb94$' "$ast/logs/az.log" || true)
  argv_leaks=$(command grep -cF 'STUB-SECRET-' "$ast/logs/curl.log" || true)
  creates=$(command grep -cE '^POST http://127\.0\.0\.1:18080/admin/realms/jtoye-staging/users -> 201$' "$ast/logs/curl-flow.log" || true)
  if [ "$ST_RC" -eq 0 ]; then why="rc=0: the stage succeeded although the culinary-pack seed answered 500"
  elif [ -z "$n500" ]; then why="no 500 on the culinary-pack seed in the stub log: $(tail -n 3 "$ast/logs/curl-flow.log" | tr '\n' '|') $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ -z "$ndel" ]; then why="the operator was never DELETEd: $(tail -n 3 "$ast/logs/curl-flow.log" | tr '\n' '|')"
  elif [ "$ndel" -le "$n500" ]; then why="the DELETE (line $ndel) does not follow the 500 (line $n500)"
  elif [ "$creates" -ne 1 ]; then why="operator creates=$creates (want 1)"
  elif ! command grep -qF 'OPERATOR-DELETED: seed-operator-' <<<"$ST_OUT"; then why="the deletion status was not printed"
  elif [ "$kv_reads" -ne "${#KV_MAP[@]}" ]; then why="Key Vault reads=$kv_reads (want ${#KV_MAP[@]})"
  elif [ "$pinned" -ne "$(_st_lines az)" ]; then why="$(_st_lines az) az calls, $pinned ending in the pin"
  elif [ "$argv_leaks" -ne 0 ]; then why="$argv_leaks curl argv line(s) carry a Key Vault value"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="secret marker printed"
  elif command grep -qE '/api/v1/onboarding|/api/v1/trader-identity' "$ast/logs/curl-flow.log"; then why="the stage went on past the failed seed"
  fi
  if [ -z "$why" ]; then
    _st_result "PASS operator-deleted-on-failure rc=$ST_RC seed_500_line=$n500 operator_delete_line=$ndel kv_reads=$kv_reads pinned=$pinned argv_secrets=0"
  else _st_result "FAIL operator-deleted-on-failure $why"; fails=$((fails + 1)); fi
  if [ "${STAGING_SEED_SELFTEST_KEEP_LOG:-}" != "" ]; then cp "$ast/logs/curl-flow.log" "$STAGING_SEED_SELFTEST_KEEP_LOG"; fi

  # --- operator-deleted-on-success: a re-run that finds everything in place
  #     (onboarding already LIVE) seeds the empty shop, reads back, exits 0, and
  #     still deletes the operator, last.
  printf '{"created":6,"skipped":0,"failed":0,"items":[]}' > "$ast/resp/seed.json"
  jq -n --arg s "$sid" --arg slug "$SHOP_SLUG" '{id: $s, slug: $slug, published: true}' > "$ast/resp/shop.json"
  jq -n --arg n "$TENANT_NAME" '{id: "ti-1", legalName: $n, entityType: "SOLE_TRADER", addressLine1: "1 Staging Test Street",
      addressLine2: null, addressCity: "Birmingham", addressPostcode: "B1 1AA", vatNumber: null, companyNumber: null}' > "$ast/resp/identity.json"
  jq -n --arg s "$sid" '{id: "onb-1", status: "LIVE", model: "MARKETPLACE", shopId: $s, gates: []}' > "$ast/resp/onboarding.json"
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" STAGING_SEED_POLL_SECONDS=0 STAGING_SEED_READY_DEADLINE_SECONDS=5 -- "${good[@]}"
  why=""
  local last
  last=$(tail -n 1 "$ast/logs/curl-flow.log" 2>/dev/null || true)
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$last" != "DELETE http://127.0.0.1:18080/admin/realms/jtoye-staging/users/op-0001 -> 204" ]; then why="the last call is not the operator DELETE: '$last'"
  elif ! command grep -qF 'OPERATOR-DELETED: seed-operator-' <<<"$ST_OUT"; then why="the deletion status was not printed"
  elif ! command grep -qE '^  onboarding: LIVE$' <<<"$ST_OUT" || ! command grep -qE '^RESULT at [0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z$' <<<"$ST_OUT"; then why="no timestamped read-back: $(command grep -A6 '^RESULT' <<<"$ST_OUT" | tr '\n' '|')"
  elif command grep -qE '^(POST|PUT) .*/(trader-identity|onboarding)' "$ast/logs/curl-flow.log"; then why="a re-run wrote what already existed: $(command grep -E '^(POST|PUT) .*/(trader-identity|onboarding)' "$ast/logs/curl-flow.log" | head -1)"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="secret marker printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS operator-deleted-on-success rc=0 last_call=DELETE-operator readback=LIVE"
  else _st_result "FAIL operator-deleted-on-success $why"; fails=$((fails + 1)); fi

  # ===== The order stage (plan 29-18; D-28, D-31) ==============================
  # Fixtures: a shop that offers both types, a sold-out first dish (mask 4) and an
  # orderable second one (mask 9), so "first available" and "the basket's union"
  # are both observable; one intent for this order and one for another.
  local p0="aaaaaaaa-0000-4000-8000-000000000000" p1="aaaaaaaa-1111-4111-8111-111111111111" order_args
  order_args=(--context jtoye-stg-aks --identity-file "$ast/identity.yaml" --stage order)
  jq -n --arg slug "$SHOP_SLUG" '{slug: $slug, offersCollection: true, offersDelivery: true,
      deliveryFeePennies: 250, freeDeliveryThresholdPennies: null, openNow: true}' > "$ast/resp/pub-shop.json"
  jq -n --arg a "$p0" --arg b "$p1" '{Mains: [
      {id: $a, title: "Sold Out Stew", pricePennies: 700, inStock: false, allergenMask: 4},
      {id: $b, title: "Pelau", pricePennies: 950, inStock: true, allergenMask: 9}]}' > "$ast/resp/pub-products.json"
  _st_pi() { jq -n --arg id "$1" --arg st "$2" --argjson lm "$3" --arg on "$4" \
      '{id: $id, object: "payment_intent", status: $st, livemode: $lm, amount: 950, currency: "gbp", metadata: {order_number: $on}}'; }
  _st_order_fixtures() {   # STATUS CLIENT_SECRET(or "") INTENTS_FOR_THIS_ORDER PI_LIVEMODE PAYMENT_STATUS [PI_STATUS_BEFORE_CONFIRM]
    jq -n --arg st "$1" --arg cs "$2" '{orderNumber: "ORD-STUB-0001", status: $st, totalAmountPennies: 950,
        clientSecret: (if $cs == "" then null else $cs end)}' > "$ast/resp/guest-order.json"
    _st_pi pi_stub0001 "${6:-requires_payment_method}" "$4" ORD-STUB-0001 > "$ast/resp/pi-before.json"
    _st_pi pi_stub0001 succeeded "$4" ORD-STUB-0001 > "$ast/resp/pi-after.json"
    _st_pi pi_stub0001 succeeded "$4" ORD-STUB-0001 > "$ast/resp/pi-confirm.json"
    { local i=0; while [ "$i" -lt "$3" ]; do _st_pi "pi_stub000$((i + 1))" succeeded false ORD-STUB-0001; i=$((i + 1)); done
      _st_pi pi_other succeeded false ORD-OTHER; } | jq -s '{object: "list", has_more: false, data: .}' > "$ast/resp/pi-list.json"
    jq -n '{object: "list", has_more: false, data: [
        {id: "evt_other", object: "event", type: "payment_intent.succeeded", livemode: false, pending_webhooks: 0, data: {object: {id: "pi_other"}}},
        {id: "evt_stub0001", object: "event", type: "payment_intent.succeeded", livemode: false, pending_webhooks: 0, data: {object: {id: "pi_stub0001"}}}]}' \
      > "$ast/resp/events.json"
    jq -n --arg ps "$5" '{orderNumber: "ORD-STUB-0001", status: "PENDING", paymentStatus: $ps}' > "$ast/resp/tracking.json"
  }
  _st_confirms() { command grep -cE '^POST https://api\.stripe\.com/v1/payment_intents/[^ ]+/confirm -> ' "$ast/logs/curl-flow.log" || true; }
  local seed_re='^SEED-ORDER order=ORD-STUB-0001 intent=pi_stub0001 status=PENDING payment_status=CAPTURED livemode=false at=[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z$'
  local olast obody

  # --- order-live-key: a LIVE Stripe key is refused before any API call. The
  #     prefix can only be seen once the key is read, so exactly one Key Vault
  #     read (of the Stripe object) happens, and no curl at all.
  _st_order_fixtures DRAFT pi_stub0001_secret_STUBCS9c2e 1 false CAPTURED
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" AZ_STUB_ANSWERS="$ast/answers-live.tsv" -- "${order_args[@]}"
  why=""
  kv_reads=$(command grep -c '^keyvault secret show ' "$ast/logs/az.log" || true)
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qF 'REFUSED [stripe-mode]' <<<"$ST_ERR"; then why="stderr lacks 'REFUSED [stripe-mode]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$kv_reads" -ne 1 ] || ! command grep -qE '^keyvault secret show .*-n stripe-credentials--api-key ' "$ast/logs/az.log"; then why="Key Vault reads=$kv_reads (want exactly 1, of stripe-credentials--api-key)"
  elif [ "$(_st_lines curl)" -ne 0 ]; then why="curl calls: $(_st_lines curl) (want 0)"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="the key was printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS order-live-key rc=1 kv_reads=1 curl_calls=0"
  else _st_result "FAIL order-live-key $why"; fails=$((fails + 1)); fi

  # --- order-first-run: one order, confirmed once with pm_card_visa, paid ------
  _st_order_fixtures DRAFT pi_stub0001_secret_STUBCS9c2e 1 false CAPTURED
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" STAGING_SEED_POLL_SECONDS=0 -- "${order_args[@]}"
  why=""
  olast=$(tail -n 1 <<<"$ST_OUT")
  obody=$(command grep -F '"customerEmail"' "$ast/logs/bodies.log" | tail -n 1 || true)
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! [[ "$olast" =~ $seed_re ]]; then why="the last line is not the SEED-ORDER verdict: '$olast'"
  elif [ "$(_st_confirms)" -ne 1 ]; then why="confirm calls=$(_st_confirms) (want 1)"
  elif ! command grep -qE 'payment_intents/pi_stub0001/confirm' "$ast/logs/curl.log" \
       || ! command grep -qF 'payment_method=pm_card_visa' "$ast/logs/curl.log" \
       || ! command grep -qF "return_url=https://app-staging.olajay.co.uk/order-complete" "$ast/logs/curl.log"; then
    why="the confirm does not carry payment_method=pm_card_visa and the return_url"
  elif ! command grep -qF 'Idempotency-Key: seed-order-staging-test-kitchen-v1' "$ast/logs/curl.log"; then why="the order POST carries no Idempotency-Key header"
  elif [ "$(jq -c '{e: .customerEmail, f: .fulfilmentType, i: .items, a: .acknowledgedAllergenMask, t: .expectedTotalPennies}' <<<"${obody:-null}" 2>/dev/null)" \
       != "$(jq -cn --arg p "$p1" '{e: "guest.staging@olajay.co.uk", f: "COLLECTION", i: [{productId: $p, quantity: 1, expectedUnitPricePennies: 950}], a: 9, t: 950}')" ]; then
    why="the order body is not the first AVAILABLE dish at its shown price with the basket's mask: ${obody:-<none>}"
  elif ! command grep -qE '^STRIPE-EVENT id=evt_stub0001 ' <<<"$ST_OUT"; then why="the payment_intent.succeeded event id was not reported"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="a Stripe key or client secret was printed or put in argv"
  fi
  if [ -z "$why" ]; then _st_result "PASS order-first-run rc=0 confirms=1 verdict=SEED-ORDER event=evt_stub0001 secrets_printed=0"
  else _st_result "FAIL order-first-run $why"; fails=$((fails + 1)); fi

  # --- order-replay: the key replays a paid order; nothing is confirmed again --
  _st_order_fixtures PENDING "" 1 false CAPTURED succeeded
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" STAGING_SEED_POLL_SECONDS=0 -- "${order_args[@]}"
  why=""
  olast=$(tail -n 1 <<<"$ST_OUT")
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! [[ "$olast" =~ $seed_re ]]; then why="the last line is not the SEED-ORDER verdict: '$olast'"
  elif [ "$(_st_confirms)" -ne 0 ]; then why="confirm calls=$(_st_confirms) on a replay (want 0)"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="a secret was printed or put in argv"
  fi
  if [ -z "$why" ]; then _st_result "PASS order-replay rc=0 confirms=0 verdict=SEED-ORDER"
  else _st_result "FAIL order-replay $why"; fails=$((fails + 1)); fi

  # --- order-cash-refused: no client secret and no intent = a cash order (D-31)
  _st_order_fixtures PENDING "" 0 false NONE
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" STAGING_SEED_POLL_SECONDS=0 -- "${order_args[@]}"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1)"
  elif ! command grep -qF 'D-31' <<<"$ST_ERR"; then why="the failure does not name D-31: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_confirms)" -ne 0 ]; then why="confirm calls=$(_st_confirms) (want 0)"
  elif command grep -q '^SEED-ORDER ' <<<"$ST_OUT"; then why="a SEED-ORDER verdict was printed for a cash order"
  fi
  if [ -z "$why" ]; then _st_result "PASS order-cash-refused rc=1 confirms=0 verdict=none"
  else _st_result "FAIL order-cash-refused $why"; fails=$((fails + 1)); fi

  # --- order-two-intents: a second intent for the same order is a failure ------
  _st_order_fixtures DRAFT pi_stub0001_secret_STUBCS9c2e 2 false CAPTURED
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" STAGING_SEED_POLL_SECONDS=0 -- "${order_args[@]}"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1)"
  elif command grep -q '^SEED-ORDER ' <<<"$ST_OUT"; then why="a SEED-ORDER verdict was printed with two intents for one order"
  fi
  if [ -z "$why" ]; then _st_result "PASS order-two-intents rc=1 verdict=none"
  else _st_result "FAIL order-two-intents $why"; fails=$((fails + 1)); fi

  # --- order-livemode-true: an intent that reads livemode true is refused ------
  _st_order_fixtures DRAFT pi_stub0001_secret_STUBCS9c2e 1 true CAPTURED
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" STAGING_SEED_POLL_SECONDS=0 -- "${order_args[@]}"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1)"
  elif ! command grep -qF 'REFUSED [stripe-mode]' <<<"$ST_ERR"; then why="stderr lacks 'REFUSED [stripe-mode]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_confirms)" -ne 0 ]; then why="confirm calls=$(_st_confirms) on a live intent (want 0)"
  fi
  if [ -z "$why" ]; then _st_result "PASS order-livemode-true rc=1 confirms=0"
  else _st_result "FAIL order-livemode-true $why"; fails=$((fails + 1)); fi

  # --- order-unpaid-deadline: the order never reads CAPTURED -> fail, last state
  _st_order_fixtures DRAFT pi_stub0001_secret_STUBCS9c2e 1 false PENDING
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" STAGING_SEED_POLL_SECONDS=1 STAGING_SEED_PAID_DEADLINE_SECONDS=2 -- "${order_args[@]}"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1)"
  elif ! command grep -qE 'deadline|within 2s' <<<"$ST_ERR" || ! command grep -qF 'paymentStatus PENDING' <<<"$ST_ERR"; then
    why="the failure does not name the deadline and the last state: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif command grep -q '^SEED-ORDER ' <<<"$ST_OUT"; then why="a SEED-ORDER verdict was printed for an unpaid order"
  fi
  if [ -z "$why" ]; then _st_result "PASS order-unpaid-deadline rc=1 last_state=PENDING"
  else _st_result "FAIL order-unpaid-deadline $why"; fails=$((fails + 1)); fi

  # --- dry-run-order: the plan names the order calls; nothing is called --------
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${order_args[@]}" --dry-run
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_lines az)" -ne 0 ] || [ "$(_st_lines curl)" -ne 0 ]; then why="az=$(_st_lines az) curl=$(_st_lines curl) (want 0, 0)"
  else
    for step in "/api/v1/public/shops/${SHOP_SLUG}/orders" /v1/payment_intents/ pm_card_visa /api/v1/public/orders/ /v1/events; do
      command grep -qF -- "$step" <<<"$ST_OUT" || { why="the plan does not name $step"; break; }
    done
  fi
  if [ -z "$why" ]; then _st_result "PASS dry-run-order rc=0 az_calls=0 curl_calls=0"
  else _st_result "FAIL dry-run-order $why"; fails=$((fails + 1)); fi

  # --- stage-all: vendor, the operator deleted, THEN the order -----------------
  _st_order_fixtures DRAFT pi_stub0001_secret_STUBCS9c2e 1 false CAPTURED
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" STAGING_SEED_POLL_SECONDS=0 STAGING_SEED_READY_DEADLINE_SECONDS=5 \
    -- --context jtoye-stg-aks --identity-file "$ast/identity.yaml" --stage all
  why=""
  olast=$(tail -n 1 <<<"$ST_OUT")
  ndel=$(command grep -nE '^DELETE http://127\.0\.0\.1:18080/admin/realms/jtoye-staging/users/op-0001 -> 204$' "$ast/logs/curl-flow.log" | cut -d: -f1 | tr '\n' ' ' || true)
  n500=$(command grep -nE '^POST https://api-staging\.olajay\.co\.uk/api/v1/public/shops/staging-test-kitchen/orders -> 201$' "$ast/logs/curl-flow.log" | head -1 | cut -d: -f1 || true)
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! [[ "$olast" =~ $seed_re ]]; then why="the last line is not the SEED-ORDER verdict: '$olast'"
  elif [ -z "$n500" ]; then why="no order POST"
  elif [ "$(wc -w <<<"$ndel")" -ne 1 ]; then why="operator DELETEs at lines '$ndel' (want exactly one)"
  elif [ "${ndel% }" -ge "$n500" ]; then why="the operator DELETE (line ${ndel% }) does not precede the order POST (line $n500)"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="a secret was printed or put in argv"
  fi
  if [ -z "$why" ]; then _st_result "PASS stage-all rc=0 operator_delete_line=${ndel% } order_post_line=$n500 verdict=SEED-ORDER"
  else _st_result "FAIL stage-all $why"; fails=$((fails + 1)); fi

  # ===== The hand tools (plan 30-07; D-01, D-02) ===============================
  # Three staging DRAFTs: two card orders awaiting payment (PENDING, FAILED) and
  # one vendor order (NONE). Every one carries customer fields holding the marker
  # PIIZQ7, so a print of any of them is detectable.
  local oA="bbbbbbbb-0000-4000-8000-00000000000a" oB="bbbbbbbb-0000-4000-8000-00000000000b" \
        oC="bbbbbbbb-0000-4000-8000-00000000000c" tool_args pii_re='piizq7'
  tool_args=(--context jtoye-stg-aks --identity-file "$ast/identity.yaml")
  _st_draft() {   # ID NUMBER PAYMENT_STATUS PAYMENT_REFERENCE(or "") TOTAL CREATED
    jq -n --arg id "$1" --arg n "$2" --arg ps "$3" --arg pr "$4" --argjson t "$5" --arg c "$6" --arg s "$sid" '{
      id: $id, shopId: $s, orderNumber: $n, status: "DRAFT", paymentStatus: $ps,
      paymentReference: (if $pr == "" then null else $pr end), totalAmountPennies: $t, createdAt: $c,
      customerName: "Pii PIIZQ7 Name", customerEmail: "piizq7@example.invalid", customerPhone: "07700 900777",
      notes: "PIIZQ7 ring the bell", addressLine1: "1 PIIZQ7 Road", allergyNote: "PIIZQ7 peanuts"}'
  }
  { _st_draft "$oA" ORD-STUB-D001 PENDING pi_drafta 950 2026-10-19T10:00:00Z
    _st_draft "$oB" ORD-STUB-D002 FAILED pi_draftb 1200 2026-10-19T11:00:00Z
    _st_draft "$oC" ORD-STUB-D003 NONE "" 700 2026-10-19T12:00:00Z
  } | jq -s '{content: ., totalElements: (. | length), totalPages: 1, number: 0, last: true}' > "$ast/resp/orders-status-DRAFT.json"
  _st_non_get() {   # curl calls that are not GETs, the token grant aside
    if [ -s "$ast/logs/curl-flow.log" ]; then
      command grep -vE '^GET |^POST https://auth-staging\.olajay\.co\.uk/realms/jtoye-staging/protocol/openid-connect/token -> ' \
        "$ast/logs/curl-flow.log" | command grep -c '' || true
    else echo 0; fi
  }
  _st_pii() { command grep -ciE "$pii_re" <<<"$ST_OUT$ST_ERR" || true; }

  # --- list-drafts: one run, three arms -----------------------------------------
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${tool_args[@]}" --stage list-drafts
  local ld_out="$ST_OUT" ld_err="$ST_ERR" ld_rc="$ST_RC" ld_last ld_want
  ld_last=$(tail -n 1 <<<"$ld_out")
  ld_want=$(printf '%s\n' \
    'DRAFT-CANDIDATE order=ORD-STUB-D001 created=2026-10-19T10:00:00Z payment_status=PENDING intent=present total=950p' \
    'DRAFT-CANDIDATE order=ORD-STUB-D002 created=2026-10-19T11:00:00Z payment_status=FAILED intent=present total=1200p' \
    'DRAFT-CANDIDATE order=ORD-STUB-D003 created=2026-10-19T12:00:00Z payment_status=NONE intent=none total=700p')
  why=""
  if [ "$ld_rc" -ne 0 ]; then why="rc=$ld_rc (want 0): $(head -c 200 <<<"$ld_err" | tr '\n' ' ')"
  elif [ "$(command grep -E '^DRAFT-CANDIDATE ' <<<"$ld_out" || true)" != "$ld_want" ]; then
    why="the candidate lines are not the three projections: $(command grep -E '^DRAFT-CANDIDATE ' <<<"$ld_out" | tr '\n' '|' || true)"
  elif ! [[ "$ld_last" =~ ^LIST-DRAFTS\ count=3\ card=2\ at=[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z$ ]]; then why="the last line is not the LIST-DRAFTS summary: '$ld_last'"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="a secret marker was printed or put in argv"
  fi
  if [ -z "$why" ]; then _st_result "PASS list-drafts-ok rc=0 candidates=3 card=2"
  else _st_result "FAIL list-drafts-ok $why"; fails=$((fails + 1)); fi
  why=""
  local ld_gets
  ld_gets=$(command grep -cE '^GET https://api-staging\.olajay\.co\.uk/api/v1/orders/status/DRAFT' "$ast/logs/curl-flow.log" || true)
  if [ "$ld_rc" -ne 0 ]; then why="rc=$ld_rc (want 0)"
  elif [ "$ld_gets" -lt 1 ]; then why="no GET of the DRAFT list (the read could not be observed)"
  elif [ "$(_st_non_get)" -ne 0 ]; then why="non-GET calls: $(command grep -vE '^GET ' "$ast/logs/curl-flow.log" | tr '\n' '|')"
  fi
  if [ -z "$why" ]; then _st_result "PASS list-drafts-readonly rc=0 draft_list_gets=$ld_gets non_get_calls=0"
  else _st_result "FAIL list-drafts-readonly $why"; fails=$((fails + 1)); fi
  why=""
  local ld_pii
  ld_pii=$(command grep -ciE "$pii_re|07700 900777" <<<"$ld_out$ld_err" || true)
  if [ "$ld_rc" -ne 0 ]; then why="rc=$ld_rc (want 0)"
  elif [ "$(command grep -c '^DRAFT-CANDIDATE ' <<<"$ld_out" || true)" -ne 3 ]; then why="no candidate lines to inspect (the arm would pass vacuously)"
  elif [ "$ld_pii" -ne 0 ]; then why="$ld_pii line(s) carry a customer name, email, phone, address or note"
  fi
  if [ -z "$why" ]; then _st_result "PASS list-drafts-no-pii rc=0 pii_lines=0 candidates=3"
  else _st_result "FAIL list-drafts-no-pii $why"; fails=$((fails + 1)); fi

  # --- transition-refused: the live #958 answer is read, not judged --------------
  jq -n '{type: "https://jtoye.uk/errors/awaiting-payment", title: "Awaiting payment", status: 409,
      detail: "Order ORD-STUB-D001 is awaiting payment. It can not be submitted, accepted or prepared until the customer payment completes."}' \
    > "$ast/resp/transition.json"
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" STUB_TRANSITION_CODE=409 -- "${tool_args[@]}" --stage order-transition \
    --orders ORD-STUB-D001 --event submit
  why=""
  local tr_line
  tr_line=$(command grep -E '^TRANSITION ' <<<"$ST_OUT" || true)
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$tr_line" != "TRANSITION order=ORD-STUB-D001 event=submit http=409 type=https://jtoye.uk/errors/awaiting-payment detail=Order ORD-STUB-D001 is awaiting payment. It can not be submitted, accepted or prepared until the customer payment completes." ]; then
    why="the TRANSITION line is '$tr_line'"
  elif [ "$(command grep -cE "^POST https://api-staging\.olajay\.co\.uk/api/v1/orders/$oA/submit -> 409$" "$ast/logs/curl-flow.log" || true)" -ne 1 ]; then
    why="the named order's submit was not called exactly once: $(command grep -E '^POST ' "$ast/logs/curl-flow.log" | tr '\n' '|')"
  elif [ "$(_st_non_get)" -ne 1 ]; then why="non-GET calls=$(_st_non_get) (want exactly the one transition)"
  elif [ "$(_st_pii)" -ne 0 ]; then why="a customer field was printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS transition-refused rc=0 http=409 type=awaiting-payment transition_posts=1"
  else _st_result "FAIL transition-refused $why"; fails=$((fails + 1)); fi

  # --- transition-event-refused: cancel never goes through order-transition -----
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${tool_args[@]}" --stage order-transition --orders ORD-STUB-D001 --event cancel
  if why=$(_st_refusal 2 "USAGE ERROR: --event must be submit, confirm or start-preparation" 0 0); then _st_result "PASS transition-event-refused rc=2 az_calls=0 curl_calls=0"
  else _st_result "FAIL transition-event-refused $why"; fails=$((fails + 1)); fi

  # --- vendor-order: the D-01 control, made the way the dashboard makes one ------
  _st_vendor_order() {   # STATUS PAYMENT_STATUS
    jq -n --arg st "$1" --arg ps "$2" --arg s "$sid" '{id: "cccccccc-0000-4000-8000-000000000001", shopId: $s,
        orderNumber: "ORD-STUB-V001", status: $st, paymentStatus: $ps, paymentReference: null, totalAmountPennies: 950,
        customerName: null, customerEmail: null}' > "$ast/resp/vendor-order-out.json"
  }
  _st_vendor_order DRAFT NONE
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${tool_args[@]}" --stage vendor-order
  why=""
  local vo_body
  vo_body=$(command grep -F '"shopId"' "$ast/logs/bodies.log" | tail -n 1 || true)
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(tail -n 1 <<<"$ST_OUT")" != "VENDOR-ORDER order=ORD-STUB-V001 status=DRAFT payment_status=NONE" ]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  elif [ "$(jq -cS . <<<"${vo_body:-null}" 2>/dev/null)" != "$(jq -cnS --arg s "$sid" --arg p "$p1" '{shopId: $s, items: [{productId: $p, quantity: 1}]}')" ]; then
    why="the order body is not {shopId, one item} alone: ${vo_body:-<none>}"
  elif [ "$(command grep -cE '^POST https://api-staging\.olajay\.co\.uk/api/v1/orders -> 201$' "$ast/logs/curl-flow.log" || true)" -ne 1 ]; then why="POST /api/v1/orders was not called exactly once"
  elif ! command grep -qE 'Idempotency-Key: vendor-order-[0-9]{8}T[0-9]{6}Z( |$)' "$ast/logs/curl.log"; then why="the order POST carries no Idempotency-Key vendor-order-<UTC stamp>"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="a secret marker was printed or put in argv"
  fi
  if [ -z "$why" ]; then _st_result "PASS vendor-order-ok rc=0 body=shopId+1item idempotency_key=vendor-order-<stamp>"
  else _st_result "FAIL vendor-order-ok $why"; fails=$((fails + 1)); fi

  _st_vendor_order DRAFT PENDING
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${tool_args[@]}" --stage vendor-order
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1)"
  elif ! command grep -qF 'paymentStatus' <<<"$ST_ERR"; then why="the failure does not name paymentStatus: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif command grep -q '^VENDOR-ORDER ' <<<"$ST_OUT"; then why="a VENDOR-ORDER line was printed for a non-NONE order"
  fi
  if [ -n "$why" ]; then _st_result "FAIL vendor-order-not-none $why"; fails=$((fails + 1))
  else
    _st_vendor_order PENDING NONE
    _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${tool_args[@]}" --stage vendor-order
    if [ "$ST_RC" -ne 1 ]; then why="status PENDING: rc=$ST_RC (want 1)"
    elif ! command grep -qE '(^|[^A-Za-z])status' <<<"$ST_ERR"; then why="status PENDING: the failure does not name status: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif command grep -q '^VENDOR-ORDER ' <<<"$ST_OUT"; then why="status PENDING: a VENDOR-ORDER line was printed"
    fi
    if [ -z "$why" ]; then _st_result "PASS vendor-order-not-none rc=1 payment_status_PENDING=refused status_PENDING=refused"
    else _st_result "FAIL vendor-order-not-none $why"; fails=$((fails + 1)); fi
  fi

  _st_render
  [ "$fails" -eq 0 ]
}

# ===========================================================================
# Flags. Parsed FIRST.
# ===========================================================================
ACTION="stage"
KUBE_CONTEXT=""
IDENTITY_FILE="$REPO_ROOT/k8s/staging/azure-identity.yaml"
API_BASE="$STAGING_API"
STAGE=""
DRY_RUN=0
ORDERS=""
EVENT=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --context)         shift; [ "$#" -gt 0 ] || usage_error "--context needs a name"; KUBE_CONTEXT="$1" ;;
    --identity-file)   shift; [ "$#" -gt 0 ] || usage_error "--identity-file needs a path"; IDENTITY_FILE="$1" ;;
    --api)             shift; [ "$#" -gt 0 ] || usage_error "--api needs a URL"; API_BASE="$1" ;;
    --stage)           shift; [ "$#" -gt 0 ] || usage_error "--stage needs a stage name"; STAGE="$1" ;;
    --orders)          shift; [ "$#" -gt 0 ] || usage_error "--orders needs a comma list of order numbers"; ORDERS="$1" ;;
    --event)           shift; [ "$#" -gt 0 ] || usage_error "--event needs submit|confirm|start-preparation"; EVENT="$1" ;;
    --dry-run)         DRY_RUN=1 ;;
    --list-kv-objects) ACTION="list-kv-objects" ;;
    --selftest)        ACTION="selftest" ;;
    -h|--help)         command sed -n '/^# USAGE/,/^# EXIT CODES/p' "$SCRIPT_PATH" | command sed 's/^# \{0,1\}//'; exit 0 ;;
    *)                 usage_error "unknown flag '$1'" ;;
  esac
  shift
done

case "$ACTION" in
  selftest)
    set +e; selftest; rc=$?; exit "$rc" ;;
  list-kv-objects)
    list_kv_objects; exit 0 ;;
esac

case "$STAGE" in
  vendor|order|all|list-drafts|vendor-order|order-transition) ;;
  "") usage_error "--stage is required (vendor|order|all|list-drafts|vendor-order|order-transition)" ;;
  *) usage_error "--stage must be vendor, order, all, list-drafts, vendor-order or order-transition" ;;
esac
# Stage-specific flags are checked before any guard or call. A flag a stage
# does not take is an error, never ignored: an order list must not be mistaken
# for a filter by a stage that would not honour it.
case "$STAGE" in
  order-transition)
    case "$EVENT" in
      submit|confirm|start-preparation) ;;
      *) usage_error "--event must be submit, confirm or start-preparation (got '${EVENT}'); cancellation goes through --stage cancel-drafts only" ;;
    esac
    [[ "$ORDERS" =~ ^ORD-[A-Za-z0-9-]+$ ]] || usage_error "--stage order-transition needs --orders with exactly one order number (ORD-…)" ;;
  *)
    [ -z "$ORDERS" ] || usage_error "--orders is not taken by --stage $STAGE"
    [ -z "$EVENT" ] || usage_error "--event is taken by --stage order-transition only" ;;
esac

run_guards
if [ "$DRY_RUN" -eq 1 ]; then
  print_plan
  exit 0
fi
case "$STAGE" in
  vendor) vendor_stage ;;
  order)  order_stage ;;
  list-drafts)      list_drafts_stage ;;
  vendor-order)     vendor_order_stage ;;
  order-transition) order_transition_stage ;;
  all)
    vendor_stage
    # The realm-admin operator exists only for the vendor stage: deleted here,
    # before the order, not left standing until exit.
    if [ "$OP_CREATED" -eq 1 ]; then delete_operator || exit 1; fi
    order_stage ;;
esac
