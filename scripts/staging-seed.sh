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
#   scripts/staging-seed.sh --context NAME --stage list-drafts|vendor-order [--dry-run]
#   scripts/staging-seed.sh --context NAME --stage order-transition --orders ORD-x
#                           --event submit|confirm|start-preparation [--dry-run]
#   scripts/staging-seed.sh --context NAME --stage cancel-drafts --orders ORD-a,ORD-b [--dry-run]
#   scripts/staging-seed.sh --context NAME --stage list-orphan-intents [--dry-run]
#   scripts/staging-seed.sh --context NAME --stage cancel-orphan-intents --intents pi_a,pi_b [--dry-run]
#   scripts/staging-seed.sh --context NAME --stage stripe-get --path /v1/<object>/<id>
#                           [--account acct_…] [--dry-run]
#   scripts/staging-seed.sh --context NAME --stage pay-link --link-out FILE [--dry-run]
#   scripts/staging-seed.sh --context NAME --stage billing|billing-sync-check [--dry-run]
#   scripts/staging-seed.sh --context NAME --stage connect-onboard --link-out FILE [--dry-run]
#   scripts/staging-seed.sh --context NAME --stage settlement-mode [--set MODE] [--dry-run]
#   scripts/staging-seed.sh --context NAME --stage money-loop --mode destination|direct
#                           [--refund-pennies N] [--run-id ID] [--evidence FILE] [--dry-run]
#   scripts/staging-seed.sh --context NAME --stage payout --account acct_…
#                           [--amount-pennies N] [--run-id ID] [--evidence FILE] [--dry-run]
#   scripts/staging-seed.sh --context NAME --stage payout-read --account acct_… --payout po_…
#                           [--evidence FILE] [--dry-run]
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
#   The hand tools (plan 30-07; issue #958; D-01, D-02). Each signs in as the
#   staging test vendor (or reads the Stripe TEST key) after the same guards, and
#   prints no customer name, email, phone, address or note:
#     --stage list-drafts      READ-ONLY: the tenant's DRAFT orders, one line each,
#                              DRAFT-CANDIDATE order=… created=… payment_status=…
#                              intent=present|none total=…p, then
#                              LIST-DRAFTS count=… card=… at=<ISO>.
#     --stage vendor-order     ONE vendor order at staging-test-kitchen, as the
#                              dashboard makes it (no customer field, submit
#                              unset): VENDOR-ORDER order=… status=DRAFT
#                              payment_status=NONE, refused unless it is exactly
#                              that (the D-01 control for plan 30-08).
#     --stage order-transition --orders ORD-x --event submit|confirm|start-preparation
#                              one POST to exactly that order; prints
#                              TRANSITION order=… event=… http=… type=… detail=….
#     --stage cancel-drafts --orders ORD-a,ORD-b
#                              D-02, the owner-confirmed list ONLY: per order, the
#                              intent cancelled in Stripe TEST and read back
#                              canceled, then the order cancelled and read back
#                              CANCELLED (SEED-CANCEL …); a paid or paying intent
#                              is refused [paid]; ends CANCEL-DRAFTS requested=…
#                              cancelled=… refused=…, rc 1 when anything was refused.
#   The orphan tools (plan 30-09, issue #1038: the stale-DRAFT cleanup deleted
#   card DRAFTs and left their intents payable, so cancel-drafts cannot reach them):
#     --stage list-orphan-intents
#                              READ-ONLY: every TEST intent still cancelable, of the
#                              vendor's tenant (metadata.tenant_id), whose
#                              metadata.order_number names no order the vendor can
#                              see in ANY status. Platform-account intents only (a
#                              direct-charge intent on a connected account is not
#                              listed). VENDOR-ORDERS total=… (the control:
#                              VOID rc 2 when it is 0, since an empty read can be a
#                              grant failure), ORPHAN-INTENT intent=… order_number=…
#                              status=… created=… amount=…p, then LIST-ORPHANS
#                              count=… open=… at=<ISO>.
#     --stage cancel-orphan-intents --intents pi_a,pi_b
#                              D-02 for the order-less: the owner-confirmed ids ONLY.
#                              Per intent: read (livemode false), refused [paid] if
#                              succeeded|processing|requires_capture, refused
#                              [other-tenant] unless metadata.tenant_id is the
#                              vendor's, refused [order-exists] if its order is visible again (that
#                              order's route is cancel-drafts), cancelled
#                              (Idempotency-Key d02-orphan-cancel-<pi>) and read back
#                              canceled (SEED-ORPHAN-CANCEL …); ends CANCEL-ORPHANS
#                              requested=… cancelled=… refused=…, rc 1 when anything
#                              was refused.
#     --stage stripe-get --path /v1/<object>[/<id>…][?query] [--account acct_…]
#                              one sanitised TEST-mode GET of webhook_endpoints,
#                              payment_intents, charges, refunds, transfers,
#                              payouts, accounts, subscriptions, invoices, events
#                              or balance (any other path is REFUSED [stripe-path]);
#                              --account sends Stripe-Account. Client secrets and
#                              personal fields are removed before printing.
#     --dry-run          run every guard (they only read), then print the ordered
#                        request plan; no Azure, Keycloak or API call is made.
#     --list-kv-objects  the Key Vault object names the stages read, one per
#                        line, C-locale sorted (offline; plan 29-13 writes them).
#     --stage pay-link --link-out FILE
#                        one real payable payment link (plan 30-19, PAY-04/D-03):
#                        the public phone challenge (refused [otp-hidden] unless
#                        staging returns the OTP), a vendor order with that phone,
#                        the public verify, then the vendor's payment link. Prints
#                        PAY-LINK order=… link=… expires=…; the URL goes ONLY to
#                        FILE, mode 600, never to stdout, stderr or evidence.
#     --stage billing    PAY-02 / D-04 (plan 30-26): subscribes the staging tenant
#                        to SAAS_HYBRID_STANDARD through POST /api/v1/billing/
#                        subscription with a TEST payment method made from
#                        tok_visa (an ACTIVE subscription is read back, never
#                        re-created), then reads the subscription and its latest
#                        invoice from Stripe BY ID. Ends on BILLING sub=… quantity=…
#                        locations=… invoice=… amount_paid=… status=paid
#                        livemode=false at=<ISO>; FAIL unless quantity equals the
#                        locations (D-04 all-shops) and amount_paid equals
#                        3900 x quantity.
#     --stage billing-sync-check
#                        D-04 (plan 30-26): with an ACTIVE subscription
#                        (else REFUSED [no-subscription], no shop written), a
#                        probe shop is created, Stripe's quantity polled to n+1,
#                        the probe deleted, the quantity polled back to n (every
#                        read printed, deadline 90 s each). Ends on BILLING-SYNC
#                        before=n after-add=n+1 after-remove=n livemode=false; the
#                        probe is deleted on EVERY exit path.
#   PAY-03, the staging money loop (plan 30-28; D-06 TEST mode only):
#     --stage connect-onboard --link-out FILE
#                        start or resume the staging tenant's Stripe Express
#                        onboarding through POST /api/v1/admin/tenants/{id}/stripe/
#                        connect (a per-run realm-admin operator, deleted on every
#                        exit path). An ENABLED account is printed and nothing is
#                        called. The single-use onboarding URL goes ONLY to FILE,
#                        mode 600. Ends CONNECT-ONBOARD tenant=… account=acct_…
#                        status=PENDING|ENABLED.
#     --stage settlement-mode [--set DESTINATION_CHARGE_PLATFORM_MOR|DIRECT_CHARGE_VENDOR_MOR]
#                        read (or set, then read back) staging-test-kitchen's
#                        settlementPolicyMode: GET, then PUT of the same body with
#                        that one field changed. Ends SETTLEMENT-MODE shop=… mode=…
#                        [previous=…].
#     --stage money-loop --mode destination [--refund-pennies N] [--evidence FILE]
#                        the loop, destination mode, every Stripe object read by id:
#                        preflight (an ENABLED account and a destination-routed shop,
#                        else REFUSED [connect-status] / [mode-mismatch] before any
#                        write), one storefront order, pm_card_bypassPending,
#                        CAPTURED, then LOOP-ORDER, LOOP-CAPTURE and LOOP-TRANSFER. A
#                        platform charge with no destination is REFUSED [plain-charge].
#                        Then the vendor accepts (LOOP-ACCEPT) and refunds N pennies
#                        (default 100; a usage error unless 0 < N < the total, checked
#                        before any write): the refund read by id with its
#                        transfer_reversal, its charge for livemode, the local row
#                        polled to Stripe's status (LOOP-REFUND). --evidence FILE adds
#                        .modes.destination (ids, amounts, statuses, livemode false;
#                        jq -S, mode 600) and keeps the rest of the file.
#     --stage money-loop --mode direct [--refund-pennies N] [--evidence FILE]
#                        plan 30-29: the same loop for DIRECT_CHARGE_VENDOR_MOR, where
#                        the intent, its charge and its refund live on the connected
#                        account and EVERY Stripe call carries Stripe-Account. REFUSED
#                        [mode-mismatch] unless the shop is DIRECT and the order's 201
#                        names the tenant's account; the intent must carry no
#                        transfer_data, an application_fee_amount > 0 and
#                        metadata.settlement_mode DIRECT_CHARGE_VENDOR_MOR. Verdicts
#                        LOOP-ORDER/-CAPTURE/-ACCEPT/-REFUND mode=direct; no transfer
#                        and no reversal exist to read. --evidence adds .modes.direct
#                        (modes sort destination, direct; each entry's steps array is
#                        in the order the loop ran).
#     --run-id ID        money-loop and payout: the run every Idempotency-Key derives
#                        from (default loop-<UTC stamp>). A run interrupted and re-run
#                        with the same ID writes nothing twice: the order POST replays
#                        its key, a refund row carrying <ID>-refund and a payout
#                        carrying metadata.loop_run_id ID are found and read, not made.
#     --stage payout --account acct_… [--amount-pennies N]
#                        plan 30-29: money from the connected account's balance to its
#                        bank. The account must be the staging tenant's ENABLED one
#                        (else REFUSED [other-account] / [connect-status], no Stripe
#                        call). Sets the payout schedule to manual (platform-level),
#                        reads the available GBP balance (REFUSED [no-available-balance]
#                        at 0), pays out min(N, available) (default: all of it; never
#                        more) with Stripe-Account and <run id>-payout-<acct>, and reads
#                        it until paid (deadline 180 s, every state printed). Ends
#                        PAYOUT account=… payout=po_… amount=… status=… livemode=false
#                        with Stripe's status verbatim: in_transit or pending at the
#                        deadline is rc 0 with a payout-read hint. --evidence adds
#                        .payouts[] (ids, amounts, statuses; no bank detail).
#     --stage payout-read --account acct_… --payout po_…
#                        one read of the payout on the account; PAYOUT line with its
#                        current status; --evidence updates that entry's status only.
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
  # The billing-sync-check probe shop (plan 30-26, T-30-102) never outlives the run.
  if [ -n "${PROBE_SHOP_ID:-}" ]; then
    if ! delete_probe_shop; then
      [ "$rc" -ne 0 ] || rc=1
    fi
  fi
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
  case "$STAGE" in list-drafts|vendor-order|order-transition|cancel-drafts|list-orphan-intents|cancel-orphan-intents|stripe-get|pay-link|billing|billing-sync-check|connect-onboard|settlement-mode|money-loop|payout|payout-read) print_tool_plan ;; esac
}
print_tool_plan() {
  echo "PLAN --stage ${STAGE} (dry-run: nothing below is called)"
  if [ "$STAGE" = stripe-get ]; then
    echo "  target        Stripe ${STRIPE_API} (TEST only)"
    echo "  01 az_read    keyvault secret show --vault-name ${KEY_VAULT_NAME}: ${STRIPE_KV_OBJECT} (refused unless sk_test_; never printed, never argv)"
    echo "  02 GET        ${STRIPE_API}${STRIPE_PATH}${STRIPE_ACCOUNT:+   Stripe-Account ${STRIPE_ACCOUNT} (in a per-call copy of the mode-600 config)}"
    echo "  03 check      livemode false (refund and account objects carry no livemode field: printed with LIVEMODE n/a)"
    echo "  04 print      the object without client_secret, secret, billing_details, receipt_email, email, name, phone, address, shipping"
    return 0
  fi
  if [ "$STAGE" = payout ] || [ "$STAGE" = payout-read ]; then
    echo "  target        Stripe ${STRIPE_API} (TEST only); connected account ${STRIPE_ACCOUNT}; tenant \"${TENANT_NAME}\""
    echo "  01 az_read    keyvault secret show --vault-name ${KEY_VAULT_NAME}: keycloak-credentials--admin-username keycloak-credentials--admin-password keycloak-credentials--frontend-client-secret (values never printed)"
    echo "  02 operator   port-forward + seed-operator-<UTC>: GET /api/v1/admin/tenants; REFUSED [other-account] unless ${STRIPE_ACCOUNT} is the tenant's ENABLED account; the operator is deleted HERE"
    echo "  03 az_read    ${STRIPE_KV_OBJECT} (refused unless sk_test_; never printed, never argv)"
    if [ "$STAGE" = payout-read ]; then
      echo "  04 GET        ${STRIPE_API}/v1/payouts/${PAYOUT_ID}   Stripe-Account ${STRIPE_ACCOUNT}; livemode false"
      echo "  05 print      PAYOUT account=${STRIPE_ACCOUNT} payout=${PAYOUT_ID} amount=… status=<Stripe's>${LOOP_EVIDENCE:+; the status of its .payouts entry updated in ${LOOP_EVIDENCE}}"
      return 0
    fi
    echo "  04 GET        ${STRIPE_API}/v1/payouts?limit=100   Stripe-Account; this run's payout (metadata.loop_run_id ${RUN_ID:-loop-<UTC>}): found = steps 05-07 skipped"
    echo "  05 POST       ${STRIPE_API}/v1/accounts/${STRIPE_ACCOUNT}   platform-level (no Stripe-Account): settings[payouts][schedule][interval]=manual, Idempotency-Key ${RUN_ID:-loop-<UTC>}-schedule-${STRIPE_ACCOUNT}"
    echo "  06 GET        ${STRIPE_API}/v1/balance   Stripe-Account; available gbp; REFUSED [no-available-balance] at 0"
    echo "  07 POST       ${STRIPE_API}/v1/payouts   Stripe-Account; amount=min(${PAYOUT_PENNIES:-available}, available) currency=gbp metadata[loop_run_id], Idempotency-Key ${RUN_ID:-loop-<UTC>}-payout-${STRIPE_ACCOUNT}"
    echo "  08 GET        ${STRIPE_API}/v1/payouts/{id}   Stripe-Account; every ${POLL_SECONDS}s until paid (deadline ${PAYOUT_DEADLINE_SECONDS}s), each state printed"
    echo "  09 print      PAYOUT account=${STRIPE_ACCOUNT} payout=po_… amount=… status=<paid|in_transit|pending> livemode=false${LOOP_EVIDENCE:+; .payouts[] in ${LOOP_EVIDENCE} (jq -S, mode 600)}"
    return 0
  fi
  if [ "$STAGE" = connect-onboard ]; then
    echo "  target        api ${API_BASE}; tenant \"${TENANT_NAME}\"; a per-run realm-admin operator"
    echo "  01 az_read    keyvault secret show --vault-name ${KEY_VAULT_NAME}: keycloak-credentials--admin-username keycloak-credentials--admin-password keycloak-credentials--frontend-client-secret (values never printed)"
    echo "  02 operator   port-forward svc/keycloak ${PF_PORTS[*]}; POST /admin/realms/${REALM}/users seed-operator-<UTC> + realm role admin (deleted on EVERY exit path)"
    echo "  03 GET        /api/v1/admin/tenants   the tenant named \"${TENANT_NAME}\": stripeConnectStatus, stripeAccountId; ENABLED: steps 04-05 are skipped"
    echo "  04 POST       /api/v1/admin/tenants/{id}/stripe/connect   start or resume Express onboarding; the operator is deleted next"
    echo "  05 write      the onboardingUrl to ${LINK_OUT} (mode 600; single-use; never printed)"
    echo "  06 print      CONNECT-ONBOARD tenant=… account=acct_… status=…"
    return 0
  fi
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
    pay-link)
      echo "  03 GET        /api/v1/shops?size=100 (the ${SHOP_SLUG} id), /api/v1/public/shops/${SHOP_SLUG}/products (first available dish)"
      echo "  04 POST       /api/v1/public/shops/${SHOP_SLUG}/phone-verification/send   {phone: ${PAY_LINK_PHONE}, channel: SMS}; REFUSED [otp-hidden] if no testPreviewOtp (nothing written)"
      echo "  05 POST       /api/v1/orders   {shopId, customerName, customerPhone, customerEmail, COLLECTION, 1 item}; Idempotency-Key pay-link-<UTC stamp>"
      echo "  06 POST       /api/v1/public/shops/${SHOP_SLUG}/phone-verification/verify   {phone, otp} (the OTP is never printed)"
      echo "  07 POST       /api/v1/orders/{id}/payment-links   {purpose: UPFRONT}"
      echo "  08 write      the paymentUrl to ${LINK_OUT} (mode 600; never printed)"
      echo "  09 print      PAY-LINK order=… link=… expires=…" ;;
    billing)
      echo "  03 az_read    ${STRIPE_KV_OBJECT} (refused unless sk_test_; never printed, never argv)"
      echo "  04 GET        /api/v1/billing/subscription   ACTIVE with a subscription id: steps 05-06 are skipped (read back, never re-created)"
      echo "  05 POST       ${STRIPE_API}/v1/payment_methods   type=card card[token]=tok_visa; livemode false; only the pm_ id is kept"
      echo "  06 POST       /api/v1/billing/subscription   {planMode: ${BILLING_PLAN_MODE}, paymentMethodId}"
      echo "  07 GET        ${STRIPE_API}/v1/subscriptions/{id}   livemode false; status active; one item; unit_amount ${BILLING_UNIT_PENNIES}"
      echo "  08 GET        ${STRIPE_API}/v1/invoices/{latest_invoice}   livemode false; amount_paid, status"
      echo "  09 GET        /api/v1/shops?size=100   locations under the D-04 ${BILLING_LOCATION_RULE} rule"
      echo "  10 print      BILLING sub=… quantity=… locations=… invoice=… amount_paid=… status=paid livemode=false at=… (FAIL unless quantity == locations and amount_paid == ${BILLING_UNIT_PENNIES} x quantity)" ;;
    billing-sync-check)
      echo "  03 az_read    ${STRIPE_KV_OBJECT} (refused unless sk_test_; never printed, never argv)"
      echo "  04 GET        /api/v1/billing/subscription   REFUSED [no-subscription] unless ACTIVE with a subscription id (no shop written)"
      echo "  05 GET        ${STRIPE_API}/v1/subscriptions/{id}   quantity n; livemode false (rule ${BILLING_LOCATION_RULE}; published-shops: not-runnable, exit 0)"
      echo "  06 POST       /api/v1/shops   {name: Staging Billing Probe <UTC>}; its id registered for the EXIT-path DELETE"
      echo "  07 GET        ${STRIPE_API}/v1/subscriptions/{id}   every ${POLL_SECONDS}s until quantity n+1 (deadline ${BILLING_SYNC_DEADLINE_SECONDS}s), each read printed"
      echo "  08 DELETE     /api/v1/shops/{probe}   then poll until quantity n (deadline ${BILLING_SYNC_DEADLINE_SECONDS}s)"
      echo "  09 print      BILLING-SYNC before=n after-add=n+1 after-remove=n livemode=false" ;;
    settlement-mode)
      echo "  03 GET        /api/v1/shops?size=100 (the ${SHOP_SLUG} id), /api/v1/shops/{id}   settlementPolicyMode"
      if [ -n "$SET_MODE" ]; then
        echo "  04 PUT        /api/v1/shops/{id}   the GET body with settlementPolicyMode ${SET_MODE}, no other field changed"
        echo "  05 GET        /api/v1/shops/{id}   read back; FAIL unless ${SET_MODE}"
        echo "  06 print      SETTLEMENT-MODE shop=${SHOP_SLUG} mode=${SET_MODE} previous=…"
      else
        echo "  04 print      SETTLEMENT-MODE shop=${SHOP_SLUG} mode=…   READ-ONLY, nothing is written"
      fi ;;
    money-loop)
      if [ "$LOOP_MODE" = direct ]; then
        echo "  mode          direct (DIRECT_CHARGE_VENDOR_MOR): the intent, its charge and its refund live on the tenant's connected account; EVERY Stripe call below carries Stripe-Account <the tenant's acct_> (per-call copy of the mode-600 config, never argv)"
        echo "  00 GET        /api/v1/public/shops/${SHOP_SLUG}{,/products}   anonymous, FIRST: the first available dish; USAGE rc 2 unless --refund-pennies ${LOOP_REFUND_PENNIES} < the total (nothing written)"
        echo "  03 az_read    ${STRIPE_KV_OBJECT} (refused unless sk_test_), keycloak-credentials--admin-username/-password (values never printed)"
        echo "  04 operator   port-forward + seed-operator-<UTC>: GET /api/v1/admin/tenants; REFUSED [connect-status] unless ENABLED with an acct_; the operator is deleted HERE"
        echo "  05 GET        /api/v1/shops/{id}   REFUSED [mode-mismatch] unless settlementPolicyMode is DIRECT_CHARGE_VENDOR_MOR"
        echo "  06 POST       /api/v1/public/shops/${SHOP_SLUG}/orders   Idempotency-Key loop-<UTC>-order; REFUSED [mode-mismatch] unless the 201 names the tenant's account"
        echo "  07 POST       ${STRIPE_API}/v1/payment_intents/{id}/confirm   Stripe-Account; payment_method=pm_card_bypassPending, Idempotency-Key loop-<UTC>-confirm"
        echo "  08 GET        /api/v1/public/orders/{orderNumber}?email=…   until CAPTURED (deadline ${PAID_DEADLINE_SECONDS}s; the direct payment_intent.succeeded arrives only through the Connect endpoint)"
        echo "  09 GET        ${STRIPE_API}/v1/payment_intents/{id}   Stripe-Account; succeeded, livemode false, no transfer_data, application_fee_amount > 0, metadata.settlement_mode DIRECT_CHARGE_VENDOR_MOR"
        echo "  10 GET        ${STRIPE_API}/v1/charges/{id}   Stripe-Account; captured, livemode false"
        echo "  11 POST       /api/v1/orders/{id}/confirm   the vendor accepts the paid order"
        echo "  12 POST       /api/v1/orders/{id}/refund   {amountPennies: ${LOOP_REFUND_PENNIES}, reason: ${LOOP_REFUND_REASON}}, Idempotency-Key loop-<UTC>-refund"
        echo "  13 GET        ${STRIPE_API}/v1/refunds?payment_intent=…, ${STRIPE_API}/v1/refunds/{id}   Stripe-Account; exactly one for this run; succeeded (deadline ${REFUND_DEADLINE_SECONDS}s)"
        echo "  14 GET        ${STRIPE_API}/v1/charges/{refund.charge}   Stripe-Account; livemode false (a Refund has no livemode field)"
        echo "  15 GET        /api/v1/orders/{id}/detail   until the local refund status equals Stripe's (deadline ${REFUND_DEADLINE_SECONDS}s; Connect-scope refund.* events)"
        echo "  16 print      LOOP-ORDER, LOOP-CAPTURE, LOOP-ACCEPT, LOOP-REFUND (each mode=direct)${LOOP_EVIDENCE:+; .modes.direct written to ${LOOP_EVIDENCE} (jq -S, mode 600)}"
        return 0
      fi
      echo "  00 GET        /api/v1/public/shops/${SHOP_SLUG}{,/products}   anonymous, FIRST: the first available dish; USAGE rc 2 unless --refund-pennies ${LOOP_REFUND_PENNIES} < the total (nothing written)"
      echo "  03 az_read    ${STRIPE_KV_OBJECT} (refused unless sk_test_), keycloak-credentials--admin-username/-password (values never printed)"
      echo "  04 operator   port-forward + seed-operator-<UTC>: GET /api/v1/admin/tenants; REFUSED [connect-status] unless ENABLED with an acct_; the operator is deleted HERE"
      echo "  05 GET        /api/v1/shops/{id}   REFUSED [mode-mismatch] unless settlementPolicyMode is null or DESTINATION_CHARGE_PLATFORM_MOR"
      echo "  06 POST       /api/v1/public/shops/${SHOP_SLUG}/orders   Idempotency-Key loop-<UTC>-order"
      echo "  07 POST       ${STRIPE_API}/v1/payment_intents/{id}/confirm   payment_method=pm_card_bypassPending, Idempotency-Key loop-<UTC>-confirm"
      echo "  08 GET        /api/v1/public/orders/{orderNumber}?email=…   until CAPTURED (deadline ${PAID_DEADLINE_SECONDS}s), every read printed"
      echo "  09 GET        ${STRIPE_API}/v1/payment_intents/{id}   succeeded, livemode false, transfer_data.destination = the account (else REFUSED [plain-charge]), application_fee_amount > 0, metadata.settlement_mode"
      echo "  10 GET        ${STRIPE_API}/v1/charges/{id}, ${STRIPE_API}/v1/transfers/{id}   captured; the transfer to the account for amount minus fee"
      echo "  11 POST       /api/v1/orders/{id}/confirm   the vendor accepts the paid order"
      echo "  12 POST       /api/v1/orders/{id}/refund   {amountPennies: ${LOOP_REFUND_PENNIES}, reason: ${LOOP_REFUND_REASON}}, Idempotency-Key loop-<UTC>-refund"
      echo "  13 GET        ${STRIPE_API}/v1/refunds?payment_intent=…, ${STRIPE_API}/v1/refunds/{id}   exactly one for this run; succeeded (deadline ${REFUND_DEADLINE_SECONDS}s); a transfer_reversal"
      echo "  14 GET        ${STRIPE_API}/v1/charges/{refund.charge} (livemode false: a Refund has no livemode field), ${STRIPE_API}/v1/transfers/{id} (the reversal)"
      echo "  15 GET        /api/v1/orders/{id}/detail   until the local refund status equals Stripe's (deadline ${REFUND_DEADLINE_SECONDS}s)"
      echo "  16 print      LOOP-ORDER, LOOP-CAPTURE, LOOP-TRANSFER, LOOP-ACCEPT, LOOP-REFUND${LOOP_EVIDENCE:+; .modes.destination written to ${LOOP_EVIDENCE} (jq -S, mode 600)}" ;;
    order-transition)
      echo "  03 GET        /api/v1/orders/status/{DRAFT,PENDING,CONFIRMED}   the id of ${ORDERS}"
      echo "  04 POST       /api/v1/orders/{id}/${EVENT}   exactly the named order"
      echo "  05 print      TRANSITION order=${ORDERS} event=${EVENT} http=… type=… detail=…" ;;
    cancel-drafts)
      echo "  03 az_read    ${STRIPE_KV_OBJECT} (refused unless sk_test_; never printed, never argv)"
      echo "  04 GET        /api/v1/orders/status/DRAFT   the ids of exactly: ${ORDERS//,/ }"
      echo "  per order     GET /api/v1/orders/{id}/detail   refused [not-draft] unless DRAFT, paymentStatus PENDING|FAILED, a pi_ reference"
      echo "                GET ${STRIPE_API}/v1/payment_intents/{pi}   livemode false; refused [paid] if succeeded|processing|requires_capture"
      echo "                POST ${STRIPE_API}/v1/payment_intents/{pi}/cancel   cancellation_reason=abandoned, Idempotency-Key d02-cancel-{pi}; skipped if already canceled"
      echo "                GET ${STRIPE_API}/v1/payment_intents/{pi}   must read canceled BEFORE the order is touched"
      echo "                POST /api/v1/orders/{id}/cancel, then GET /api/v1/orders/{id}/detail   must read CANCELLED"
      echo "                print SEED-CANCEL order=… intent=… intent_status=canceled order_status=CANCELLED livemode=false at=…"
      echo "  last          CANCEL-DRAFTS requested=… cancelled=… refused=… (rc 1 when anything was refused)" ;;
    list-orphan-intents)
      echo "  03 az_read    ${STRIPE_KV_OBJECT} (refused unless sk_test_; never printed, never argv)"
      echo "  04 GET        /api/v1/orders/status/{${ALL_ORDER_STATUSES// /,}}   every page; VOID if the vendor sees no order at all"
      echo "  05 GET        ${STRIPE_API}/v1/payment_intents?limit=100[&starting_after=…]   every page; livemode false"
      echo "  06 print      VENDOR-ORDERS total=…, ORPHAN-INTENT intent=… order_number=… status=… created=… amount=…p, LIST-ORPHANS count=… open=… at=…; READ-ONLY" ;;
    cancel-orphan-intents)
      echo "  03 az_read    ${STRIPE_KV_OBJECT} (refused unless sk_test_; never printed, never argv)"
      echo "  04 GET        /api/v1/orders/status/{${ALL_ORDER_STATUSES// /,}}   VOID if the vendor sees no order at all"
      echo "  per intent    GET ${STRIPE_API}/v1/payment_intents/{pi} of exactly: ${INTENTS//,/ }   livemode false; refused [paid] if succeeded|processing|requires_capture"
      echo "                GET /api/v1/orders/status/{every status}   refused [order-exists] if metadata.order_number is visible"
      echo "                POST ${STRIPE_API}/v1/payment_intents/{pi}/cancel   cancellation_reason=abandoned, Idempotency-Key d02-orphan-cancel-{pi}; skipped if already canceled"
      echo "                GET ${STRIPE_API}/v1/payment_intents/{pi}   must read canceled; print SEED-ORPHAN-CANCEL intent=… order_number=… intent_status=canceled livemode=false at=…"
      echo "  last          CANCEL-ORPHANS requested=… cancelled=… refused=… (rc 1 when anything was refused)" ;;
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

# operator_up — the Keycloak port-forward (PID captured at start, never killed by a
# pattern) and the per-run realm-admin operator seed-operator-<UTC>, with a random
# in-memory password, then its token. Needs kv-admin-username, kv-admin-password and
# kv-client-secret in WORKDIR. The EXIT trap deletes the operator (OP_CREATED=1 is set
# before the create is sent, so a create whose answer is lost is still looked up).
operator_up() {
  local code t0
  # The port-forward.
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

  # The operator.
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
}

# operator_down — the operator deleted now and the port-forward stopped, for a stage
# whose admin read is done before it writes anything (plan 30-28): no realm admin
# stands while an order, a refund or a link exists. Returns 1 when the operator may
# still exist (the EXIT trap then tries again and fails the run).
operator_down() {
  if [ "$OP_CREATED" -eq 1 ]; then delete_operator || return 1; fi
  if [ -n "${PF_PID:-}" ]; then kill "$PF_PID" 2>/dev/null || true; wait "$PF_PID" 2>/dev/null || true; PF_PID=""; fi
  return 0
}

# read_kv NAME... — the named KV_MAP rows from Key Vault into their mode-600 files in
# WORKDIR (the same files vendor_stage and vendor_login write). A file already present
# is not read again. A value is never printed and never in argv.
read_kv() {
  local want row name obj v rc file n=0 found
  set +x
  for want in "$@"; do
    found=0
    for row in "${KV_MAP[@]}"; do
      name="${row%%=*}"; obj="${row#*=}"
      [ "$name" = "$want" ] || continue
      found=1
      case "$name" in
        VENDOR_PASSWORD)        file=vendor-password ;;
        KC_ADMIN_USERNAME)      file=kv-admin-username ;;
        KC_ADMIN_PASSWORD)      file=kv-admin-password ;;
        CORE_API_CLIENT_SECRET) file=kv-client-secret ;;
        *) void "read_kv: no file for $name" ;;
      esac
      [ ! -s "$WORKDIR/$file" ] || continue
      rc=0; v=$(az_read keyvault secret show --vault-name "$KEY_VAULT_NAME" -n "$obj" --query value -o tsv) || rc=$?
      if [ "$rc" -ne 0 ] || [ -z "$v" ]; then
        refuse key-vault "could not read $obj from $KEY_VAULT_NAME (az rc=$rc); plan 29-13 generates it"
      fi
      (umask 077 && printf '%s' "$v" > "$WORKDIR/$file")
      n=$((n + 1))
    done
    [ "$found" -eq 1 ] || void "read_kv: $want is not a KV_MAP name"
  done
  v=""
  echo "OK: read $n more Key Vault object(s) from $KEY_VAULT_NAME (values not shown)"
}

# operator_login — the subscription guard, the operator's Key Vault objects, then
# operator_up (plan 30-28: connect-onboard and the money loop's preflight).
operator_login() {
  azure_guard_once
  need_tool curl; need_tool openssl; need_tool base64
  ensure_workdir
  read_kv KC_ADMIN_USERNAME KC_ADMIN_PASSWORD CORE_API_CLIENT_SECRET
  operator_up
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

  # 3-4. The port-forward and the per-run realm-admin operator (operator_up).
  operator_up

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
# STRIPE_EXTRA_HEADER, when set, is sent as one more header (an Idempotency-Key;
# never a secret).
STRIPE_EXTRA_HEADER=""
# STRIPE_CURLRC, when set, is the config file used instead of stripe.curlrc (a
# per-call copy carrying a Stripe-Account header; mode 600, in WORKDIR).
STRIPE_CURLRC=""
stripe_api() {
  local method="$1" path="$2" out="$3" code rc=0 f
  shift 3
  local -a form=() xh=()
  for f in "$@"; do form+=(--data-urlencode "$f"); done
  [ -z "$STRIPE_EXTRA_HEADER" ] || xh=(-H "$STRIPE_EXTRA_HEADER")
  code=$(curl -sS -K "${STRIPE_CURLRC:-$WORKDIR/stripe.curlrc}" -X "$method" ${xh[@]+"${xh[@]}"} ${form[@]+"${form[@]}"} \
    -o "$out" -w '%{http_code}' "${STRIPE_API}${path}") || rc=$?
  if [ "$rc" -ne 0 ]; then echo "000"; return 0; fi
  echo "$code"
}

# stripe_acct ACCT METHOD PATH OUT [FORM-FIELD...] — stripe_api on the connected
# account ACCT (plan 30-29): the Stripe-Account header rides in a per-account copy of
# the mode-600 config (the plan 30-07 mechanism), never in argv. The caller has
# validated ACCT as acct_… before the first call.
stripe_acct() {
  local acct="$1" rcf
  shift
  rcf="$WORKDIR/stripe-acct-$acct.curlrc"
  if [ ! -s "$rcf" ]; then
    (umask 077 && { cat "$WORKDIR/stripe.curlrc"; printf 'header = "Stripe-Account: %s"\n' "$acct"; } > "$rcf")
  fi
  STRIPE_CURLRC="$rcf" stripe_api "$@"
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

# read_stripe_key — the subscription guard, then the Stripe TEST key from Key
# Vault into the mode-600 config file. A key that is not sk_test_ is refused
# before any API call. Shared by the order, cancel-drafts and stripe-get stages.
read_stripe_key() {
  local key rc
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
}

order_stage() {
  local code

  # 1. The Stripe TEST key (read_stripe_key: the subscription guard, the
  #    sk_test_ refusal, the mode-600 config file).
  read_stripe_key

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

# --stage cancel-drafts --orders ORD-a,ORD-b — D-02, by hand, an explicit list
# only. For each named order, in the order given: the order must be a card DRAFT
# awaiting payment; its intent is read (livemode false), refused if paid or
# paying, cancelled in Stripe (cancellation_reason abandoned, Idempotency-Key
# d02-cancel-<pi>) and read back canceled; only then is the order cancelled
# through the vendor API and read back CANCELLED. A refusal or failure ends that
# order only (each runs in its own errexit subshell), the next named order is
# processed, and the run exits 1. No order is cancelled whose intent is not
# canceled first, so a later payment cannot land on a cancelled order.
cancel_one() {
  local id="$1" n="$2" code ost ops pref pist lm
  code=$(api vendor GET "/api/v1/orders/$id/detail" "$WORKDIR/c-detail.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/orders/{id}/detail ($n)" "$code" "$WORKDIR/c-detail.json"
  ost=$(jq -r '.status // ""' "$WORKDIR/c-detail.json")
  ops=$(jq -r '.paymentStatus // ""' "$WORKDIR/c-detail.json")
  pref=$(jq -r '.paymentReference // ""' "$WORKDIR/c-detail.json")
  rm -f "$WORKDIR/c-detail.json"
  [ "$ost" = DRAFT ] || refuse not-draft "$n is $ost, not DRAFT; only an abandoned card DRAFT is cancelled here"
  case "$ops" in PENDING|FAILED) ;; *) refuse not-draft "$n has paymentStatus $ops, not PENDING or FAILED: not a card order awaiting payment" ;; esac
  [[ "$pref" =~ ^pi_[A-Za-z0-9]+$ ]] || refuse not-draft "$n carries no PaymentIntent reference (pi_…)"

  code=$(stripe_api GET "/v1/payment_intents/$pref" "$WORKDIR/c-pi.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/payment_intents/$pref" "$code" "$WORKDIR/c-pi.json"
  assert_test_mode "$WORKDIR/c-pi.json" "PaymentIntent $pref"
  if [ "$(jq -r '.metadata.order_number // ""' "$WORKDIR/c-pi.json")" != "$n" ]; then
    refuse intent-mismatch "PaymentIntent $pref names order '$(jq -r '.metadata.order_number // ""' "$WORKDIR/c-pi.json")', not $n"
  fi
  pist=$(jq -r '.status // ""' "$WORKDIR/c-pi.json")
  case "$pist" in
    succeeded|processing|requires_capture)
      refuse paid "$n: PaymentIntent $pref is $pist; a paid or paying order is never cancelled here (D-02)" ;;
    canceled)
      echo "SKIP: PaymentIntent $pref is already canceled; no Stripe write" ;;
    requires_payment_method|requires_confirmation|requires_action)
      STRIPE_EXTRA_HEADER="Idempotency-Key: d02-cancel-$pref"
      code=$(stripe_api POST "/v1/payment_intents/$pref/cancel" "$WORKDIR/c-pi-cancel.json" "cancellation_reason=abandoned")
      STRIPE_EXTRA_HEADER=""
      [ "$code" = 200 ] || stripe_fail "POST /v1/payment_intents/$pref/cancel" "$code" "$WORKDIR/c-pi-cancel.json"
      assert_test_mode "$WORKDIR/c-pi-cancel.json" "the cancelled PaymentIntent $pref"
      echo "OK: Stripe accepted the cancel of $pref (Idempotency-Key d02-cancel-$pref)" ;;
    *)
      refuse intent-status "$n: PaymentIntent $pref is '$pist', a status this tool does not cancel" ;;
  esac
  code=$(stripe_api GET "/v1/payment_intents/$pref" "$WORKDIR/c-pi.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/payment_intents/$pref (read-back)" "$code" "$WORKDIR/c-pi.json"
  assert_test_mode "$WORKDIR/c-pi.json" "PaymentIntent $pref"
  pist=$(jq -r '.status // ""' "$WORKDIR/c-pi.json"); lm=$(jq -r '.livemode' "$WORKDIR/c-pi.json")
  [ "$pist" = canceled ] || { echo "FAIL: PaymentIntent $pref reads $pist after the cancel, not canceled; $n is left untouched" >&2; exit 1; }

  code=$(api vendor POST "/api/v1/orders/$id/cancel" "$WORKDIR/c-cancel.json")
  rm -f "$WORKDIR/c-cancel.json"
  [ "$code" = 200 ] || { echo "FAIL: POST /api/v1/orders/{id}/cancel ($n) returned HTTP $code; its intent $pref IS canceled" >&2; exit 1; }
  code=$(api vendor GET "/api/v1/orders/$id/detail" "$WORKDIR/c-detail.json")
  ost=$(jq -r '.status // ""' "$WORKDIR/c-detail.json" 2>/dev/null || true)
  rm -f "$WORKDIR/c-detail.json"
  [ "$code" = 200 ] && [ "$ost" = CANCELLED ] || { echo "FAIL: $n reads HTTP $code status '$ost' after the cancel, not CANCELLED" >&2; exit 1; }
  echo "SEED-CANCEL order=$n intent=$pref intent_status=canceled order_status=CANCELLED livemode=$lm at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

cancel_drafts_stage() {
  local n id rc requested=0 cancelled=0 refused=0
  local -a list=()
  vendor_login
  read_stripe_key
  orders_in_status DRAFT "$WORKDIR/drafts.json"
  IFS=, read -r -a list <<<"$ORDERS"
  for n in "${list[@]}"; do
    requested=$((requested + 1))
    id=$(jq -r --arg n "$n" '[.[] | select(.orderNumber == $n)] | if length == 1 then .[0].id else empty end' "$WORKDIR/drafts.json")
    if [ -z "$id" ]; then
      echo "REFUSED [not-draft]: $n is not one of the tenant's DRAFT orders; nothing was called for it" >&2
      refused=$((refused + 1)); continue
    fi
    set +e
    ( set -e; cancel_one "$id" "$n" ); rc=$?
    set -e
    if [ "$rc" -eq 0 ]; then cancelled=$((cancelled + 1))
    else refused=$((refused + 1)); echo "NOT-CANCELLED: $n (rc=$rc); going on to the next named order" >&2; fi
  done
  echo "CANCEL-DRAFTS requested=$requested cancelled=$cancelled refused=$refused"
  [ "$refused" -eq 0 ] || exit 1
}

# The order statuses a vendor can list (uk.jtoye.core.order.OrderStatus).
ALL_ORDER_STATUSES="DRAFT PENDING CONFIRMED PREPARING READY COMPLETED CANCELLED REFUNDED"

# all_order_numbers OUT — every order number the vendor can see, in every status,
# as one JSON array. An empty answer VOIDs: getOrdersByStatus returns an empty
# page to a caller without shop grants, so "no orders" cannot be told from "could
# not see them", and every intent would then read as an orphan.
all_order_numbers() {
  local out="$1" st
  printf '[]' | body "$out"
  for st in $ALL_ORDER_STATUSES; do
    orders_in_status "$st" "$WORKDIR/all-$st.json"
    jq -s '.[0] + [.[1][].orderNumber]' "$out" "$WORKDIR/all-$st.json" | body "$out.next"
    cp "$out.next" "$out"; rm -f "$out.next" "$WORKDIR/all-$st.json"
  done
  [ "$(jq 'length' "$out")" -gt 0 ] \
    || void "the vendor sees no order in any status; an empty read may be a grant failure, so nothing is called an orphan"
}

# vendor_tenant — sets VENDOR_TENANT from the signed-in vendor's token. One
# platform Stripe account serves every staging tenant, so an intent is only this
# tenant's to list or cancel when its metadata.tenant_id says so (PR #1041 round 1):
# without it another tenant's live checkout would read as an orphan here.
VENDOR_TENANT=""
vendor_tenant() {
  VENDOR_TENANT=$(jwt_claim vendor tenant_id)
  [[ "$VENDOR_TENANT" =~ ^[0-9a-f-]{36}$ ]] \
    || void "the vendor's token carries no tenant_id claim; an intent cannot be attributed to this tenant"
}

# open_intents OUT — every TEST intent still cancelable (requires_payment_method,
# requires_confirmation, requires_action) that carries metadata.order_number and
# this tenant's metadata.tenant_id (VENDOR_TENANT), all pages, projected to id, order_number, status, created, amount (no customer field).
open_intents() {
  local out="$1" code after="" pages=0
  printf '[]' | body "$out"
  while :; do
    code=$(stripe_api GET "/v1/payment_intents?limit=100${after:+&starting_after=$after}" "$WORKDIR/pi-page.json")
    [ "$code" = 200 ] || stripe_fail "GET /v1/payment_intents" "$code" "$WORKDIR/pi-page.json"
    assert_test_mode "$WORKDIR/pi-page.json" "GET /v1/payment_intents"
    jq -s --arg t "$VENDOR_TENANT" '.[0] + [.[1].data[]
            | select(.status == "requires_payment_method" or .status == "requires_confirmation" or .status == "requires_action")
            | select((.metadata.order_number // "") != "")
            | select((.metadata.tenant_id // "") == $t)
            | {id, order_number: .metadata.order_number, status, created, amount}]' "$out" "$WORKDIR/pi-page.json" | body "$out.next"
    cp "$out.next" "$out"; rm -f "$out.next"
    [ "$(jq -r '.has_more // false' "$WORKDIR/pi-page.json")" = true ] || break
    after=$(jq -r '.data[-1].id // ""' "$WORKDIR/pi-page.json")
    [ -n "$after" ] || break
    pages=$((pages + 1))
    [ "$pages" -lt 50 ] || { echo "FAIL: more than 50 pages of PaymentIntents; refusing to guess" >&2; exit 1; }
  done
  rm -f "$WORKDIR/pi-page.json"
}

# --stage list-orphan-intents — READ-ONLY (issue #1038). The vendor's orders in
# every status first (the control), then the open intents whose order is absent.
list_orphan_intents_stage() {
  local count open
  vendor_login
  vendor_tenant
  read_stripe_key
  all_order_numbers "$WORKDIR/known.json"
  echo "VENDOR-ORDERS total=$(jq 'length' "$WORKDIR/known.json")"
  open_intents "$WORKDIR/open.json"
  jq -r --slurpfile k "$WORKDIR/known.json" '.[] | . as $i | select(any($k[0][]; . == $i.order_number) | not)
      | "ORPHAN-INTENT intent=\(.id) order_number=\(.order_number) status=\(.status) created=\(.created | todate) amount=\(.amount)p"' \
      "$WORKDIR/open.json"
  count=$(jq --slurpfile k "$WORKDIR/known.json" '[.[] | . as $i | select(any($k[0][]; . == $i.order_number) | not)] | length' "$WORKDIR/open.json")
  open=$(jq 'length' "$WORKDIR/open.json")
  echo "LIST-ORPHANS count=$count open=$open at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

# --stage cancel-orphan-intents --intents pi_a,pi_b — D-02 for an intent whose
# order is gone. Each named intent in its own errexit subshell: read, refuse if
# paid or paying, refuse if its order is visible again (re-read at this moment,
# so an order that reappeared is cancelled with its intent by cancel-drafts),
# cancel, read back canceled. A refusal ends that intent only; the run exits 1.
cancel_orphan_one() {
  local pi="$1" code on pist lm
  code=$(stripe_api GET "/v1/payment_intents/$pi" "$WORKDIR/o-pi.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/payment_intents/$pi" "$code" "$WORKDIR/o-pi.json"
  assert_test_mode "$WORKDIR/o-pi.json" "PaymentIntent $pi"
  on=$(jq -r '.metadata.order_number // ""' "$WORKDIR/o-pi.json")
  [ -n "$on" ] || refuse not-orphan-candidate "$pi carries no metadata.order_number; only an intent a storefront order made is cancelled here"
  [ "$(jq -r '.metadata.tenant_id // ""' "$WORKDIR/o-pi.json")" = "$VENDOR_TENANT" ] \
    || refuse other-tenant "$pi ($on) names tenant '$(jq -r '.metadata.tenant_id // ""' "$WORKDIR/o-pi.json")', not this vendor's; its orders are invisible here, so it is never called an orphan"
  pist=$(jq -r '.status // ""' "$WORKDIR/o-pi.json")
  case "$pist" in
    succeeded|processing|requires_capture)
      refuse paid "$pi ($on) is $pist; a paid or paying intent is never cancelled here (D-02)" ;;
    canceled|requires_payment_method|requires_confirmation|requires_action) ;;
    *) refuse intent-status "$pi ($on) is '$pist', a status this tool does not cancel" ;;
  esac
  all_order_numbers "$WORKDIR/o-known.json"
  if jq -e --arg o "$on" 'any(.[]; . == $o)' "$WORKDIR/o-known.json" >/dev/null; then
    refuse order-exists "$pi names $on, which the vendor can see; cancel it with --stage cancel-drafts so the order and its intent end together"
  fi
  if [ "$pist" = canceled ]; then
    echo "SKIP: PaymentIntent $pi is already canceled; no Stripe write"
  else
    STRIPE_EXTRA_HEADER="Idempotency-Key: d02-orphan-cancel-$pi"
    code=$(stripe_api POST "/v1/payment_intents/$pi/cancel" "$WORKDIR/o-pi-cancel.json" "cancellation_reason=abandoned")
    STRIPE_EXTRA_HEADER=""
    [ "$code" = 200 ] || stripe_fail "POST /v1/payment_intents/$pi/cancel" "$code" "$WORKDIR/o-pi-cancel.json"
    assert_test_mode "$WORKDIR/o-pi-cancel.json" "the cancelled PaymentIntent $pi"
    echo "OK: Stripe accepted the cancel of $pi (Idempotency-Key d02-orphan-cancel-$pi)"
  fi
  code=$(stripe_api GET "/v1/payment_intents/$pi" "$WORKDIR/o-pi.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/payment_intents/$pi (read-back)" "$code" "$WORKDIR/o-pi.json"
  assert_test_mode "$WORKDIR/o-pi.json" "PaymentIntent $pi"
  pist=$(jq -r '.status // ""' "$WORKDIR/o-pi.json"); lm=$(jq -r '.livemode' "$WORKDIR/o-pi.json")
  [ "$pist" = canceled ] || { echo "FAIL: PaymentIntent $pi reads $pist after the cancel, not canceled" >&2; exit 1; }
  echo "SEED-ORPHAN-CANCEL intent=$pi order_number=$on intent_status=canceled livemode=$lm at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

cancel_orphan_intents_stage() {
  local pi rc requested=0 cancelled=0 refused=0
  local -a list=()
  vendor_login
  vendor_tenant
  read_stripe_key
  all_order_numbers "$WORKDIR/known.json"
  IFS=, read -r -a list <<<"$INTENTS"
  for pi in "${list[@]}"; do
    requested=$((requested + 1))
    set +e
    ( set -e; cancel_orphan_one "$pi" ); rc=$?
    set -e
    if [ "$rc" -eq 0 ]; then cancelled=$((cancelled + 1))
    else refused=$((refused + 1)); echo "NOT-CANCELLED: $pi (rc=$rc); going on to the next named intent" >&2; fi
  done
  echo "CANCEL-ORPHANS requested=$requested cancelled=$cancelled refused=$refused"
  [ "$refused" -eq 0 ] || exit 1
}

# --stage stripe-get --path P [--account acct_…] — one GET, TEST mode only, for
# evidence. The path was matched against the allowlist before any guard ran.
# livemode must read false. Two object types Stripe defines without a livemode
# field (refund, account; stripe-java 34.0.0 has none on Refund or Account) are
# printed with a LIVEMODE n/a line, since the sk_test_ key is what makes them
# TEST; any other object without the field is refused, never assumed TEST.
stripe_get_stage() {
  local code verdict obj
  read_stripe_key
  if [ -n "$STRIPE_ACCOUNT" ]; then
    (umask 077 && { cat "$WORKDIR/stripe.curlrc"; printf 'header = "Stripe-Account: %s"\n' "$STRIPE_ACCOUNT"; } > "$WORKDIR/stripe-acct.curlrc")
    STRIPE_CURLRC="$WORKDIR/stripe-acct.curlrc"
  fi
  code=$(stripe_api GET "$STRIPE_PATH" "$WORKDIR/sg.json")
  STRIPE_CURLRC=""
  rm -f "$WORKDIR/stripe-acct.curlrc"
  [ "$code" = 200 ] || stripe_fail "GET $STRIPE_PATH" "$code" "$WORKDIR/sg.json"
  verdict=$(jq -r '
    def lm: if type != "object" then "unparseable"
            elif has("livemode") then (if .livemode == false then "false" else "live" end)
            elif (.object == "refund" or .object == "account") then "absent-known"
            else "absent" end;
    if type == "object" and .object == "list" then
      ([.data[]? | lm] | if any(. == "live") then "live" elif any(. == "absent" or . == "unparseable") then "absent"
                         elif length == 0 then "empty" elif all(. == "false") then "false" else "absent-known" end)
    else lm end' "$WORKDIR/sg.json" 2>/dev/null || echo unparseable)
  obj=$(jq -r 'if type == "object" then (.object // "unknown") else "unknown" end' "$WORKDIR/sg.json" 2>/dev/null || echo unknown)
  case "$verdict" in
    false|empty) ;;
    absent-known) echo "LIVEMODE n/a object=$obj (Stripe defines no livemode field on this object; TEST mode rests on the sk_test_ key)" ;;
    live) rm -f "$WORKDIR/sg.json"; refuse stripe-mode "GET $STRIPE_PATH returned a live object; a live object must never exist in staging (D-28)" ;;
    *) rm -f "$WORKDIR/sg.json"; refuse stripe-mode "GET $STRIPE_PATH returned a $obj that does not read livemode false ($verdict); refused rather than assumed TEST" ;;
  esac
  jq 'walk(if type == "object" then del(.client_secret, .secret, .billing_details, .receipt_email, .email, .name, .phone, .address, .shipping) else . end)' \
    "$WORKDIR/sg.json"
  rm -f "$WORKDIR/sg.json"
}

# ===========================================================================
# --stage pay-link --link-out FILE — plan 30-19 (PAY-04 / D-03, T-30-78, T-30-79).
# A real, payable payment link on staging, made the way a vendor and a customer
# make one, so the staging /pay proof (frontend/e2e/staging-pay-link.spec.ts)
# starts from a link nobody fabricated:
#   1. the customer's phone challenge, through the PUBLIC storefront endpoint.
#      Staging returns the OTP in testPreviewOtp only under the owner's
#      expose-on-staging decision (30-16 Task 2; core-java refuses that switch
#      outside local/test/staging). No OTP -> REFUSED [otp-hidden], and nothing
#      has been written: the send is the probe, so it runs BEFORE the order.
#   2. a vendor order carrying that phone (Idempotency-Key pay-link-<UTC stamp>),
#      one line of the first available dish, COLLECTION, a DRAFT;
#   3. the public verify with the OTP, which marks the tenant's open orders with
#      that phone verified;
#   4. the vendor's POST /api/v1/orders/{id}/payment-links {purpose: UPFRONT}.
# The issued URL is a bearer credential (its token pays the order): it is written
# ONLY to FILE, chmod 600 before a byte of it lands there, and never to stdout,
# stderr or evidence. The OTP, the token and the client secret are read with jq
# into variables that are never printed; the response files are removed at once.
PAY_LINK_PHONE="+447700900123"
PAY_LINK_NAME="Staging Pay Link"
PAY_LINK_EMAIL="guest.staging+paylink@olajay.co.uk"
pay_link_stage() {
  local code shop_id pid stamp otp oid order_no ost link_id link_order expires url
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

  # 1. The challenge. The answer holds the OTP: read, then removed.
  jq -n --arg ph "$PAY_LINK_PHONE" '{phone: $ph, channel: "SMS"}' | body "$WORKDIR/pl-send.json"
  code=$(pub_api POST "/api/v1/public/shops/$SHOP_SLUG/phone-verification/send" "$WORKDIR/pl-send-out.json" "$WORKDIR/pl-send.json")
  if [ "$code" != 200 ]; then
    jq '{type, title, detail}' "$WORKDIR/pl-send-out.json" > "$WORKDIR/pl-send-problem.json" 2>/dev/null || printf '{}' > "$WORKDIR/pl-send-problem.json"
    rm -f "$WORKDIR/pl-send-out.json"
    fail_http "POST /api/v1/public/shops/$SHOP_SLUG/phone-verification/send" "$code" "$WORKDIR/pl-send-problem.json"
  fi
  otp=$(jq -r '.testPreviewOtp // empty' "$WORKDIR/pl-send-out.json" 2>/dev/null || true)
  rm -f "$WORKDIR/pl-send-out.json"
  if [ -z "$otp" ]; then
    refuse otp-hidden "staging did not return the OTP (testPreviewOtp is absent): jtoye.phone-verification.expose-otp-for-test is off, the owner's keep-hidden posture. No order and no link were made; the staging /pay proof is not met on staging"
  fi
  [[ "$otp" =~ ^[0-9]{6}$ ]] || { otp=""; echo "FAIL: the send answered an OTP that is not 6 digits (not shown)" >&2; exit 1; }
  echo "OK: phone challenge sent for $PAY_LINK_PHONE (the OTP was returned by the staging seam; not shown)"

  # 2. The vendor order with that phone.
  jq -n --arg s "$shop_id" --arg p "$pid" --arg n "$PAY_LINK_NAME" --arg ph "$PAY_LINK_PHONE" --arg e "$PAY_LINK_EMAIL" \
    '{shopId: $s, customerName: $n, customerPhone: $ph, customerEmail: $e, fulfilmentType: "COLLECTION",
      items: [{productId: $p, quantity: 1}]}' | body "$WORKDIR/pl-order.json"
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  API_EXTRA_HEADER="Idempotency-Key: pay-link-$stamp"
  code=$(api vendor POST /api/v1/orders "$WORKDIR/pl-order-out.json" "$WORKDIR/pl-order.json")
  API_EXTRA_HEADER=""
  [ "$code" = 201 ] || fail_http "POST /api/v1/orders (pay-link order)" "$code" "$WORKDIR/pl-order-out.json"
  oid=$(jq -r '.id // empty' "$WORKDIR/pl-order-out.json"); order_no=$(jq -r '.orderNumber // empty' "$WORKDIR/pl-order-out.json")
  ost=$(jq -r '.status // empty' "$WORKDIR/pl-order-out.json")
  rm -f "$WORKDIR/pl-order-out.json"
  [ -n "$oid" ] && [ -n "$order_no" ] || { echo "FAIL: the order 201 carried no id or orderNumber" >&2; exit 1; }
  echo "OK: vendor order $order_no status $ost (Idempotency-Key pay-link-$stamp)"

  # 3. The customer's verification with the OTP. The request file holds it: removed after.
  jq -n --arg ph "$PAY_LINK_PHONE" --arg o "$otp" '{phone: $ph, otp: $o}' | body "$WORKDIR/pl-verify.json"
  otp=""
  code=$(pub_api POST "/api/v1/public/shops/$SHOP_SLUG/phone-verification/verify" "$WORKDIR/pl-verify-out.json" "$WORKDIR/pl-verify.json")
  rm -f "$WORKDIR/pl-verify.json"
  [ "$code" = 200 ] || fail_http "POST /api/v1/public/shops/$SHOP_SLUG/phone-verification/verify" "$code" "$WORKDIR/pl-verify-out.json"
  [ "$(jq -r '.verified' "$WORKDIR/pl-verify-out.json")" = true ] \
    || { echo "FAIL: the verify answered 200 without verified=true" >&2; exit 1; }
  echo "OK: phone $PAY_LINK_PHONE verified for the tenant's open orders"

  # 4. The vendor's link. The answer holds the URL, the token and the client secret.
  jq -n '{purpose: "UPFRONT"}' | body "$WORKDIR/pl-issue.json"
  code=$(api vendor POST "/api/v1/orders/$oid/payment-links" "$WORKDIR/pl-issue-out.json" "$WORKDIR/pl-issue.json")
  if [ "$code" != 201 ]; then
    jq '{type, title, detail}' "$WORKDIR/pl-issue-out.json" > "$WORKDIR/pl-issue-problem.json" 2>/dev/null || printf '{}' > "$WORKDIR/pl-issue-problem.json"
    rm -f "$WORKDIR/pl-issue-out.json"
    fail_http "POST /api/v1/orders/$oid/payment-links" "$code" "$WORKDIR/pl-issue-problem.json"
  fi
  url=$(jq -r '.paymentUrl // empty' "$WORKDIR/pl-issue-out.json")
  link_id=$(jq -r '.id // empty' "$WORKDIR/pl-issue-out.json")
  link_order=$(jq -r '.orderNumber // empty' "$WORKDIR/pl-issue-out.json")
  expires=$(jq -r '.expiresAt // empty' "$WORKDIR/pl-issue-out.json")
  rm -f "$WORKDIR/pl-issue-out.json"
  [ -n "$url" ] && [ -n "$link_id" ] && [ -n "$expires" ] || { url=""; echo "FAIL: the link 201 lacked paymentUrl, id or expiresAt" >&2; exit 1; }
  [ "$link_order" = "$order_no" ] || { url=""; echo "FAIL: the link names order '$link_order', not $order_no" >&2; exit 1; }
  # The fragment form (owner decision 30-16 Task 1): a path token means the
  # running core-java predates 30-16, and its URL would leak through access logs.
  case "$url" in
    https://*/pay\#token=?*) ;;
    *) url=""; echo "FAIL: the issued URL is not https://<host>/pay#token=… (shape only; not shown)" >&2; exit 1 ;;
  esac
  (umask 077 && : > "$LINK_OUT") || { url=""; echo "FAIL: could not create --link-out $LINK_OUT" >&2; exit 1; }
  chmod 600 "$LINK_OUT" || { url=""; echo "FAIL: could not chmod 600 $LINK_OUT" >&2; exit 1; }
  printf '%s\n' "$url" > "$LINK_OUT"
  url=""
  echo "OK: the link URL is in $LINK_OUT (mode 600; not shown)"
  echo "PAY-LINK order=$order_no link=$link_id expires=$expires"
}

# ===========================================================================
# --stage billing — plan 30-26 (PAY-02 / D-04, T-30-101, T-30-103).
# PAY-02's falsifier is "a test-mode subscription invoice retrieved from Stripe by
# id"; D-04 adds "whose quantity matches" the tenant's locations. So the staging
# tenant is subscribed the way a vendor subscribes, through the product's own
# billing API, with a TEST card payment method made from tok_visa, and the
# evidence is then read from Stripe BY ID with the staging key, never from an
# application log:
#   1. GET /api/v1/billing/subscription. ACTIVE with a subscription id: read back
#      only, no payment method and no subscribe (a re-run never adds a second
#      price or subscription, T-30-101). Otherwise:
#   2. POST /v1/payment_methods type=card card[token]=tok_visa (only its id is
#      kept), then POST /api/v1/billing/subscription {planMode, paymentMethodId};
#   3. GET /v1/subscriptions/{id} and GET /v1/invoices/{latest_invoice}, each
#      livemode false;
#   4. the locations: GET /api/v1/shops under the owner's D-04 rule
#      (evidence/30-25-d04-decision.txt: all-shops, every shop row of the tenant);
#   5. quantity == locations, the price's unit amount == BILLING_UNIT_PENNIES,
#      amount_paid == BILLING_UNIT_PENNIES x quantity, status paid, or FAIL.
# BILLING_UNIT_PENNIES is StripeProperties.Billing's hybridMonthlyPennies default
# (STRIPE_BILLING_HYBRID_MONTHLY_PENNIES:3900). A running config that bills
# another amount is a FAIL, never a recomputation.
# Who calls: the staging test vendor. BillingController calls
# ShopAccessService.requireGroupAdmin(), and the vendor holds the tenant-wide
# GROUP_ADMIN grant the vendor stage made (no realm-admin operator is created).
# Printed: ids, counts, amounts and statuses only. The Stripe key stays in the
# mode-600 curl config; the payment method, subscription, invoice and billing
# responses are projected with jq to named fields and removed, so no customer id,
# email, name, card detail or client secret reaches stdout or stderr.
BILLING_UNIT_PENNIES=3900
BILLING_PLAN_MODE="SAAS_HYBRID_STANDARD"
BILLING_LOCATION_RULE="all-shops"

# read_billing_state — GET /api/v1/billing/subscription as the vendor. Sets
# BL_TENANT BL_MODE BL_STATUS BL_SUB BL_INVOICE BL_UNIT from named fields only.
BL_TENANT=""; BL_MODE=""; BL_STATUS=""; BL_SUB=""; BL_INVOICE=""; BL_UNIT=""
read_billing_state() {
  local code
  code=$(api vendor GET /api/v1/billing/subscription "$WORKDIR/bl-state.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/billing/subscription" "$code" "$WORKDIR/bl-state.json"
  billing_dto_fields "$WORKDIR/bl-state.json" "GET /api/v1/billing/subscription"
}
# billing_dto_fields FILE WHAT — the named fields of a BillingSubscriptionDto,
# then the file is removed (it carries stripeCustomerId, which is never printed).
billing_dto_fields() {
  local row
  row=$(jq -r '[.tenantId // "", .billingPlanMode // "", .subscriptionStatus // "", .stripeSubscriptionId // "",
                .latestStripeInvoiceId // "", (.monthlyAmountPennies // "" | tostring)] | join("\u001f")' "$1" 2>/dev/null) \
    || { rm -f "$1"; echo "FAIL: $2 did not return a billing subscription object" >&2; exit 1; }
  rm -f "$1"
  IFS=$'\x1f' read -r BL_TENANT BL_MODE BL_STATUS BL_SUB BL_INVOICE BL_UNIT <<<"$row" || true
  [[ "$BL_TENANT" =~ ^[0-9a-f-]{36}$ ]] || { echo "FAIL: $2 carried no tenantId" >&2; exit 1; }
}

# read_stripe_subscription SUB — GET /v1/subscriptions/SUB, livemode false. Sets
# SUB_STATUS SUB_ITEMS SUB_QTY SUB_UNIT SUB_INVOICE. Called directly, never in $(…).
SUB_STATUS=""; SUB_ITEMS=""; SUB_QTY=""; SUB_UNIT=""; SUB_INVOICE=""
read_stripe_subscription() {
  local code row
  code=$(stripe_api GET "/v1/subscriptions/$1" "$WORKDIR/bl-sub-read.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/subscriptions/$1" "$code" "$WORKDIR/bl-sub-read.json"
  assert_test_mode "$WORKDIR/bl-sub-read.json" "GET /v1/subscriptions/$1"
  row=$(jq -r '[.status // "", ((.items.data // []) | length | tostring), (.items.data[0].quantity // "" | tostring),
                (.items.data[0].price.unit_amount // "" | tostring),
                (if (.latest_invoice | type) == "object" then .latest_invoice.id else .latest_invoice end // "")] | join("\u001f")' \
          "$WORKDIR/bl-sub-read.json" 2>/dev/null) || row=""
  rm -f "$WORKDIR/bl-sub-read.json"
  [ -n "$row" ] || { echo "FAIL: GET /v1/subscriptions/$1 could not be read" >&2; exit 1; }
  IFS=$'\x1f' read -r SUB_STATUS SUB_ITEMS SUB_QTY SUB_UNIT SUB_INVOICE <<<"$row" || true
  [ "$SUB_ITEMS" = 1 ] || { echo "FAIL: subscription $1 has $SUB_ITEMS item(s); one per-location price is expected" >&2; exit 1; }
  [[ "$SUB_QTY" =~ ^[0-9]+$ ]] || { echo "FAIL: subscription $1 carries no integer quantity ('$SUB_QTY')" >&2; exit 1; }
}

# count_locations TENANT — LOC_COUNT under BILLING_LOCATION_RULE, from the
# vendor's GET /api/v1/shops. A shop of another tenant, or more shops than one
# page holds, is a FAIL rather than a guess.
LOC_COUNT=""
count_locations() {
  local code verdict
  code=$(api vendor GET "/api/v1/shops?size=100" "$WORKDIR/bl-shops.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/shops" "$code" "$WORKDIR/bl-shops.json"
  verdict=$(jq -r --arg t "$1" --arg r "$BILLING_LOCATION_RULE" '
    if (.content | type) != "array" then "no-content"
    elif (.totalElements // (.content | length)) != (.content | length) then "paged \(.totalElements)"
    elif any(.content[]; .tenantId != $t) then "foreign"
    elif $r == "published-shops" then ([.content[] | select(.published == true)] | length | tostring)
    else (.content | length | tostring) end' "$WORKDIR/bl-shops.json" 2>/dev/null || echo unparseable)
  rm -f "$WORKDIR/bl-shops.json"
  case "$verdict" in
    no-content|unparseable) echo "FAIL: GET /api/v1/shops did not return a page with a content array" >&2; exit 1 ;;
    paged*) echo "FAIL: the tenant has ${verdict#paged } shops, more than one page of 100; refusing to guess the location count" >&2; exit 1 ;;
    foreign) echo "FAIL: GET /api/v1/shops returned a shop whose tenantId is not the billing tenant's; refusing to count it" >&2; exit 1 ;;
  esac
  LOC_COUNT="$verdict"
}

billing_stage() {
  local code pm sub api_inv="" inv row paid ist inv_sub want
  case "$BILLING_LOCATION_RULE" in all-shops|published-shops) ;; *) void "BILLING_LOCATION_RULE '$BILLING_LOCATION_RULE' is not a D-04 rule" ;; esac
  vendor_login
  read_stripe_key
  read_billing_state
  if [ "$BL_STATUS" = ACTIVE ] && [ -n "$BL_SUB" ]; then
    [ "$BL_MODE" = "$BILLING_PLAN_MODE" ] \
      || { echo "FAIL: the ACTIVE subscription $BL_SUB is on plan $BL_MODE, not $BILLING_PLAN_MODE; this stage never switches a plan" >&2; exit 1; }
    echo "OK: an ACTIVE $BL_MODE subscription $BL_SUB is in place; reading it back (no payment method, no subscribe)"
  else
    echo "OK: subscription status ${BL_STATUS:-none}; subscribing the tenant to $BILLING_PLAN_MODE"
    code=$(stripe_api POST /v1/payment_methods "$WORKDIR/bl-pm.json" type=card "card[token]=tok_visa")
    [ "$code" = 200 ] || stripe_fail "POST /v1/payment_methods" "$code" "$WORKDIR/bl-pm.json"
    assert_test_mode "$WORKDIR/bl-pm.json" "POST /v1/payment_methods"
    pm=$(jq -r '.id // empty' "$WORKDIR/bl-pm.json" 2>/dev/null || true)
    rm -f "$WORKDIR/bl-pm.json"
    [[ "$pm" =~ ^pm_[A-Za-z0-9]+$ ]] || { echo "FAIL: POST /v1/payment_methods returned no pm_ id" >&2; exit 1; }
    echo "OK: TEST payment method $pm created from tok_visa (only its id is kept)"
    jq -n --arg m "$BILLING_PLAN_MODE" --arg p "$pm" '{planMode: $m, paymentMethodId: $p}' | body "$WORKDIR/bl-subscribe.json"
    code=$(api vendor POST /api/v1/billing/subscription "$WORKDIR/bl-subscribe-out.json" "$WORKDIR/bl-subscribe.json")
    rm -f "$WORKDIR/bl-subscribe.json"
    [ "$code" = 200 ] || fail_http "POST /api/v1/billing/subscription" "$code" "$WORKDIR/bl-subscribe-out.json"
    billing_dto_fields "$WORKDIR/bl-subscribe-out.json" "POST /api/v1/billing/subscription"
    [ "$BL_STATUS" = ACTIVE ] && [ "$BL_MODE" = "$BILLING_PLAN_MODE" ] \
      || { echo "FAIL: the subscribe answered status '$BL_STATUS' plan '$BL_MODE' (want ACTIVE $BILLING_PLAN_MODE)" >&2; exit 1; }
    api_inv="$BL_INVOICE"
    echo "OK: subscribed through POST /api/v1/billing/subscription: subscription ${BL_SUB:-none}"
  fi
  sub="$BL_SUB"
  [[ "$sub" =~ ^sub_[A-Za-z0-9]+$ ]] || { echo "FAIL: the billing API names no sub_ subscription ('$sub')" >&2; exit 1; }
  [ "$BL_UNIT" = "$BILLING_UNIT_PENNIES" ] \
    || { echo "FAIL: the running config bills ${BL_UNIT:-?} per location on $BILLING_PLAN_MODE, not the stage's BILLING_UNIT_PENNIES=$BILLING_UNIT_PENNIES (not recomputed)" >&2; exit 1; }

  read_stripe_subscription "$sub"
  [ "$SUB_STATUS" = active ] || { echo "FAIL: Stripe reads subscription $sub status '$SUB_STATUS' (want active)" >&2; exit 1; }
  [ "$SUB_UNIT" = "$BILLING_UNIT_PENNIES" ] \
    || { echo "FAIL: subscription $sub's price is ${SUB_UNIT:-?} per location, not BILLING_UNIT_PENNIES=$BILLING_UNIT_PENNIES (not recomputed)" >&2; exit 1; }
  inv="$SUB_INVOICE"
  [[ "$inv" =~ ^in_[A-Za-z0-9]+$ ]] || { echo "FAIL: subscription $sub names no in_ latest_invoice ('$inv')" >&2; exit 1; }
  [ -z "$api_inv" ] || [ "$api_inv" = "$inv" ] \
    || { echo "FAIL: the billing API recorded invoice $api_inv but Stripe's latest_invoice is $inv" >&2; exit 1; }

  code=$(stripe_api GET "/v1/invoices/$inv" "$WORKDIR/bl-inv.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/invoices/$inv" "$code" "$WORKDIR/bl-inv.json"
  assert_test_mode "$WORKDIR/bl-inv.json" "GET /v1/invoices/$inv"
  row=$(jq -r '[(.amount_paid // "" | tostring), .status // "",
                (.subscription // .parent.subscription_details.subscription // "")] | join("\u001f")' "$WORKDIR/bl-inv.json" 2>/dev/null) || row=""
  rm -f "$WORKDIR/bl-inv.json"
  IFS=$'\x1f' read -r paid ist inv_sub <<<"$row" || true
  [ -z "$inv_sub" ] || [ "$inv_sub" = "$sub" ] || { echo "FAIL: invoice $inv belongs to subscription $inv_sub, not $sub" >&2; exit 1; }
  [[ "$paid" =~ ^[0-9]+$ ]] || { echo "FAIL: invoice $inv carries no integer amount_paid ('$paid')" >&2; exit 1; }

  count_locations "$BL_TENANT"
  if [ "$SUB_QTY" != "$LOC_COUNT" ]; then
    echo "FAIL: the subscription quantity is $SUB_QTY but the tenant has $LOC_COUNT location(s) under the $BILLING_LOCATION_RULE rule (D-04)" >&2; exit 1
  fi
  want=$((BILLING_UNIT_PENNIES * SUB_QTY))
  if [ "$paid" != "$want" ]; then
    echo "FAIL: invoice $inv amount_paid $paid is not $want ($BILLING_UNIT_PENNIES x quantity $SUB_QTY)" >&2; exit 1
  fi
  [ "$ist" = paid ] || { echo "FAIL: invoice $inv status is '$ist' (want paid)" >&2; exit 1; }
  echo "BILLING sub=$sub quantity=$SUB_QTY locations=$LOC_COUNT invoice=$inv amount_paid=$paid status=$ist livemode=false at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

# ===========================================================================
# --stage billing-sync-check — plan 30-26 (D-04, T-30-102). The after-commit
# quantity sync (30-25 BillingLocationSync) watched happening on staging, under
# the owner's D-04 rule:
#   all-shops: read the subscription quantity n from Stripe; create one probe
#     shop "Staging Billing Probe <UTC>" through POST /api/v1/shops (its id is
#     registered for the EXIT-path delete the moment it is known); poll Stripe
#     until the quantity reads n+1; DELETE the probe; poll until it reads n.
#     Every read is printed; each wait has a deadline
#     (STAGING_SEED_BILLING_SYNC_DEADLINE_SECONDS, default 90). Ends on
#     BILLING-SYNC before=n after-add=n+1 after-remove=n livemode=false.
#   published-shops: not runnable today (a second shop cannot be published: only
#     the onboarding shop goes live), so it prints
#     BILLING-SYNC not-runnable reason=no-second-publishable-shop and exits 0,
#     a recorded limitation and NOT a pass of the sync.
# No ACTIVE subscription: REFUSED [no-subscription] before any shop is written.
# The probe never outlives the run: cleanup() deletes it on every exit path, and
# a delete that fails turns the run into a failure that names the shop.
BILLING_SYNC_DEADLINE_SECONDS="${STAGING_SEED_BILLING_SYNC_DEADLINE_SECONDS:-90}"
PROBE_SHOP_ID=""

# delete_probe_shop — called by the stage and from the EXIT trap. Never exits;
# returns 1 when the probe may still exist.
delete_probe_shop() {
  local code
  code=$(api vendor DELETE "/api/v1/shops/$PROBE_SHOP_ID" "$WORKDIR/probe-delete.json")
  rm -f "$WORKDIR/probe-delete.json"
  case "$code" in
    204|404) echo "PROBE-SHOP-DELETED: $PROBE_SHOP_ID HTTP $code"; PROBE_SHOP_ID=""; return 0 ;;
    *) echo "FAIL: the probe shop $PROBE_SHOP_ID was NOT deleted (HTTP $code); it is a billable location: delete it by hand now (DELETE /api/v1/shops/$PROBE_SHOP_ID as $VENDOR_USERNAME)" >&2; return 1 ;;
  esac
}

# probe_lookup NAME — when the create's answer gave no id, the shop may exist
# anyway: find it by its unique name so the EXIT path can delete it.
probe_lookup() {
  local code hits
  code=$(api vendor GET "/api/v1/shops?size=100" "$WORKDIR/probe-find.json")
  if [ "$code" = 200 ]; then
    hits=$(jq -r --arg n "$1" '[.content[]? | select(.name == $n) | .id] | if length == 1 then .[0] else "" end' "$WORKDIR/probe-find.json" 2>/dev/null || true)
    [[ "$hits" =~ ^[0-9a-f-]{36}$ ]] && PROBE_SHOP_ID="$hits"
  fi
  rm -f "$WORKDIR/probe-find.json"
  [ -n "$PROBE_SHOP_ID" ] || echo "FAIL: a probe shop named '$1' may exist and could not be found (HTTP $code); delete it by hand" >&2
}

# wait_quantity PHASE WANT — poll the subscription until its quantity is WANT.
wait_quantity() {
  local phase="$1" want="$2" start=$SECONDS
  while :; do
    read_stripe_subscription "$BL_SUB"
    echo "BILLING-SYNC-READ phase=$phase quantity=$SUB_QTY want=$want elapsed=$((SECONDS - start))s"
    [ "$SUB_QTY" != "$want" ] || return 0
    if [ $((SECONDS - start)) -ge "$BILLING_SYNC_DEADLINE_SECONDS" ]; then
      echo "FAIL: Stripe's subscription $BL_SUB quantity did not reach $want within ${BILLING_SYNC_DEADLINE_SECONDS}s ($phase; last read $SUB_QTY)" >&2
      exit 1
    fi
    sleep "$POLL_SECONDS"
  done
}

billing_sync_check_stage() {
  local n name code
  case "$BILLING_LOCATION_RULE" in all-shops|published-shops) ;; *) void "BILLING_LOCATION_RULE '$BILLING_LOCATION_RULE' is not a D-04 rule" ;; esac
  vendor_login
  read_stripe_key
  read_billing_state
  if ! { [ "$BL_STATUS" = ACTIVE ] && [[ "$BL_SUB" =~ ^sub_[A-Za-z0-9]+$ ]]; }; then
    refuse no-subscription "the tenant has no ACTIVE subscription (status ${BL_STATUS:-none}); run --stage billing first. No shop was written"
  fi
  read_stripe_subscription "$BL_SUB"
  n="$SUB_QTY"
  echo "BILLING-SYNC-READ phase=before quantity=$n at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [ "$BILLING_LOCATION_RULE" = published-shops ]; then
    echo "BILLING-SYNC not-runnable reason=no-second-publishable-shop"
    return 0
  fi

  name="Staging Billing Probe $(date -u +%Y%m%dT%H%M%SZ)"
  jq -n --arg n "$name" '{name: $n}' | body "$WORKDIR/probe.json"
  code=$(api vendor POST /api/v1/shops "$WORKDIR/probe-out.json" "$WORKDIR/probe.json")
  rm -f "$WORKDIR/probe.json"
  PROBE_SHOP_ID=$(jq -r '.id // empty' "$WORKDIR/probe-out.json" 2>/dev/null || true)
  if ! [[ "$PROBE_SHOP_ID" =~ ^[0-9a-f-]{36}$ ]]; then
    # A 4xx wrote nothing; a 201 without an id, a 5xx or no answer may have.
    PROBE_SHOP_ID=""
    case "$code" in 4??) ;; *) probe_lookup "$name" ;; esac
  fi
  [ "$code" = 201 ] || fail_http "POST /api/v1/shops (the probe location)" "$code" "$WORKDIR/probe-out.json"
  rm -f "$WORKDIR/probe-out.json"
  [ -n "$PROBE_SHOP_ID" ] || { echo "FAIL: the probe create answered 201 without an id" >&2; exit 1; }
  echo "OK: probe shop $PROBE_SHOP_ID created ('$name'); deleted on every exit path"

  wait_quantity after-add $((n + 1))
  delete_probe_shop || exit 1
  wait_quantity after-remove "$n"
  echo "BILLING-SYNC before=$n after-add=$((n + 1)) after-remove=$n livemode=false"
}

# ===========================================================================
# PAY-03 (plan 30-28; D-06 TEST mode only; T-30-107..T-30-111): the staging money
# loop, destination mode, and the two stages that set it up. PAY-03 needs the loop
# onboard -> order -> capture -> money to the connected account -> refund, each
# step read from Stripe's API by id. Both modes run on ONE tenant and ONE shop in
# sequence (onboarding publishes one shop per tenant), so the shop's mode is
# switched between runs with --stage settlement-mode, and each mode's order is
# refunded before the switch: refund routing is resolved from the shop's CURRENT
# mode at refund time (RefundService -> resolveSettlementRouting).
#   --stage connect-onboard --link-out FILE
#       a per-run realm-admin operator reads the tenant from GET /api/v1/admin/tenants;
#       an ENABLED account is printed and nothing else is called; otherwise POST
#       /api/v1/admin/tenants/{id}/stripe/connect starts or resumes Express
#       onboarding, the operator is deleted, and the single-use onboarding URL (a
#       bearer link to the vendor's Stripe onboarding, T-30-108) goes ONLY to FILE,
#       mode 600. Prints CONNECT-ONBOARD tenant=… account=acct_… status=….
#   --stage settlement-mode [--set DESTINATION_CHARGE_PLATFORM_MOR|DIRECT_CHARGE_VENDOR_MOR]
#       the vendor reads staging-test-kitchen (GET /api/v1/shops/{id}); with --set,
#       the SAME body goes back through PUT with settlementPolicyMode alone changed
#       (absent fields keep their stored values) and is read back. Prints
#       SETTLEMENT-MODE shop=… mode=…[ previous=…].
#   --stage money-loop --mode destination
#       REFUSED [connect-status] unless the tenant's account is ENABLED, REFUSED
#       [mode-mismatch] unless the shop routes as a destination charge, both before
#       any write and with the operator already deleted; then one storefront order,
#       its intent confirmed with pm_card_bypassPending (funds straight to the
#       available balance), the tracking read until CAPTURED (every read printed),
#       and the intent, its charge and its transfer read by id. A platform charge
#       with no transfer_data is REFUSED [plain-charge] (Pitfall 6): it is never
#       recorded as a destination-charge proof. Then the vendor accepts the order, a
#       PARTIAL refund (--refund-pennies, 0 < N < the total, checked against the
#       public menu before anything is read from Key Vault or written) goes through
#       the vendor API, Stripe's refund is read by id (succeeded, a
#       transfer_reversal), its charge for livemode (a Refund has none), the
#       transfer's reversal, and the local row is polled to Stripe's status.
#       --evidence FILE adds .modes.destination. Every Idempotency-Key derives from
#       the run id loop-<UTC stamp>.
# ===========================================================================

# admin_tenant_row — the operator's GET /api/v1/admin/tenants: the one tenant named
# TENANT_NAME. Sets AT_ID AT_STATUS (stripeConnectStatus, NONE when absent) and
# AT_ACCOUNT (stripeAccountId). Called directly, never inside $(…).
AT_ID=""; AT_STATUS=""; AT_ACCOUNT=""
admin_tenant_row() {
  local code n row
  code=$(api operator GET /api/v1/admin/tenants "$WORKDIR/at-tenants.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/admin/tenants" "$code" "$WORKDIR/at-tenants.json"
  n=$(jq -r --arg n "$TENANT_NAME" 'if type == "array" then ([.[] | select(.name == $n)] | length | tostring) else "not-a-list" end' \
        "$WORKDIR/at-tenants.json" 2>/dev/null || echo unparseable)
  if [ "$n" != 1 ]; then
    rm -f "$WORKDIR/at-tenants.json"
    echo "FAIL: GET /api/v1/admin/tenants lists $n tenant(s) named \"$TENANT_NAME\" (want exactly 1)" >&2; exit 1
  fi
  row=$(jq -r --arg n "$TENANT_NAME" '.[] | select(.name == $n) | [.id // "", .stripeConnectStatus // "NONE", .stripeAccountId // ""] | join("\u001f")' \
          "$WORKDIR/at-tenants.json")
  rm -f "$WORKDIR/at-tenants.json"
  IFS=$'\x1f' read -r AT_ID AT_STATUS AT_ACCOUNT <<<"$row" || true
  [[ "$AT_ID" =~ ^[0-9a-f-]{36}$ ]] || { echo "FAIL: the admin tenant row carries no tenant id" >&2; exit 1; }
}

# shop_by_slug — SHOP_ID of staging-test-kitchen from the vendor's shop list.
# read_shop OUT — GET /api/v1/shops/SHOP_ID into OUT; SHOP_MODE is its
# settlementPolicyMode ("" when null). Both called directly, never inside $(…).
SHOP_ID=""; SHOP_MODE=""
shop_by_slug() {
  local code
  code=$(api vendor GET "/api/v1/shops?size=100" "$WORKDIR/sb-shops.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/shops" "$code" "$WORKDIR/sb-shops.json"
  SHOP_ID=$(jq -r --arg s "$SHOP_SLUG" '[.content[]? | select(.slug == $s)] | if length == 1 then .[0].id else "" end' \
              "$WORKDIR/sb-shops.json" 2>/dev/null || true)
  rm -f "$WORKDIR/sb-shops.json"
  [[ "$SHOP_ID" =~ ^[0-9a-f-]{36}$ ]] || { echo "FAIL: the vendor does not hold exactly one shop $SHOP_SLUG" >&2; exit 1; }
}
read_shop() {
  local code
  code=$(api vendor GET "/api/v1/shops/$SHOP_ID" "$1")
  [ "$code" = 200 ] || fail_http "GET /api/v1/shops/$SHOP_ID" "$code" "$1"
  jq -e --arg id "$SHOP_ID" 'type == "object" and .id == $id' "$1" >/dev/null 2>&1 \
    || { echo "FAIL: GET /api/v1/shops/$SHOP_ID did not return that shop" >&2; exit 1; }
  SHOP_MODE=$(jq -r '.settlementPolicyMode // empty' "$1")
}

# --stage connect-onboard --link-out FILE
connect_onboard_stage() {
  local code acct cst url
  operator_login
  admin_tenant_row
  echo "OK: tenant \"$TENANT_NAME\" ($AT_ID): connect status $AT_STATUS, account ${AT_ACCOUNT:-none}"
  if [ "$AT_STATUS" = ENABLED ] && [[ "$AT_ACCOUNT" =~ ^acct_[A-Za-z0-9]+$ ]]; then
    operator_down || exit 1
    echo "SKIP: the connected account is ENABLED; no onboarding link was requested and $LINK_OUT was not written"
    echo "CONNECT-ONBOARD tenant=$AT_ID account=$AT_ACCOUNT status=ENABLED"
    return 0
  fi
  code=$(api operator POST "/api/v1/admin/tenants/$AT_ID/stripe/connect" "$WORKDIR/connect-out.json")
  if [ "$code" != 200 ]; then
    jq '{type, title, detail}' "$WORKDIR/connect-out.json" > "$WORKDIR/connect-problem.json" 2>/dev/null || printf '{}' > "$WORKDIR/connect-problem.json"
    rm -f "$WORKDIR/connect-out.json"
    fail_http "POST /api/v1/admin/tenants/$AT_ID/stripe/connect" "$code" "$WORKDIR/connect-problem.json"
  fi
  url=$(jq -r '.onboardingUrl // empty' "$WORKDIR/connect-out.json" 2>/dev/null || true)
  acct=$(jq -r '.stripeAccountId // empty' "$WORKDIR/connect-out.json" 2>/dev/null || true)
  cst=$(jq -r '.connectStatus // empty' "$WORKDIR/connect-out.json" 2>/dev/null || true)
  rm -f "$WORKDIR/connect-out.json"
  # No realm admin stands while the link exists.
  operator_down || { url=""; exit 1; }
  [[ "$acct" =~ ^acct_[A-Za-z0-9]+$ ]] || { url=""; echo "FAIL: the connect answer names no acct_ account" >&2; exit 1; }
  [ -z "$AT_ACCOUNT" ] || [ "$AT_ACCOUNT" = "$acct" ] \
    || { url=""; echo "FAIL: the tenant was linked to $AT_ACCOUNT but the connect answer names $acct" >&2; exit 1; }
  case "$cst" in PENDING|ENABLED|DISABLED) ;; *) url=""; echo "FAIL: the connect answer carries status '$cst'" >&2; exit 1 ;; esac
  case "$url" in
    https://?*) ;;
    *) url=""; echo "FAIL: the onboarding link is not an https URL (shape only; not shown)" >&2; exit 1 ;;
  esac
  (umask 077 && : > "$LINK_OUT") || { url=""; echo "FAIL: could not create --link-out $LINK_OUT" >&2; exit 1; }
  chmod 600 "$LINK_OUT" || { url=""; echo "FAIL: could not chmod 600 $LINK_OUT" >&2; exit 1; }
  printf '%s\n' "$url" > "$LINK_OUT"
  url=""
  echo "OK: the single-use onboarding link is in $LINK_OUT (mode 600; not shown)"
  echo "CONNECT-ONBOARD tenant=$AT_ID account=$acct status=$cst"
}

# --stage settlement-mode [--set MODE]
settlement_mode_stage() {
  local code before after
  vendor_login
  shop_by_slug
  read_shop "$WORKDIR/sm-shop.json"
  before="$SHOP_MODE"
  if [ -z "$SET_MODE" ]; then
    rm -f "$WORKDIR/sm-shop.json"
    echo "SETTLEMENT-MODE shop=$SHOP_SLUG mode=${before:-null}"
    return 0
  fi
  if [ "$before" = "$SET_MODE" ]; then
    rm -f "$WORKDIR/sm-shop.json"
    echo "SKIP: $SHOP_SLUG is already $SET_MODE; nothing was written"
    echo "SETTLEMENT-MODE shop=$SHOP_SLUG mode=$SET_MODE previous=$before"
    return 0
  fi
  # The GET body with one field changed: CreateShopRequest keeps every absent field,
  # and every present one is the value just read.
  jq --arg m "$SET_MODE" '.settlementPolicyMode = $m' "$WORKDIR/sm-shop.json" | body "$WORKDIR/sm-put.json"
  rm -f "$WORKDIR/sm-shop.json"
  code=$(api vendor PUT "/api/v1/shops/$SHOP_ID" "$WORKDIR/sm-put-out.json" "$WORKDIR/sm-put.json")
  rm -f "$WORKDIR/sm-put.json"
  [ "$code" = 200 ] || fail_http "PUT /api/v1/shops/$SHOP_ID (settlementPolicyMode $SET_MODE)" "$code" "$WORKDIR/sm-put-out.json"
  rm -f "$WORKDIR/sm-put-out.json"
  read_shop "$WORKDIR/sm-shop-after.json"
  rm -f "$WORKDIR/sm-shop-after.json"
  after="$SHOP_MODE"
  [ "$after" = "$SET_MODE" ] || { echo "FAIL: $SHOP_SLUG's settlementPolicyMode read back as '${after:-null}' after the PUT, not $SET_MODE" >&2; exit 1; }
  echo "SETTLEMENT-MODE shop=$SHOP_SLUG mode=$after previous=${before:-null}"
}

# --stage money-loop --mode destination. The steps share these globals; every
# helper is called directly, never inside $(…), so a refusal exits the script.
LP_RUN=""; LP_ACCOUNT=""; LP_ORDER=""; LP_INTENT=""; LP_TOTAL=""; LP_AMOUNT=""; LP_FEE=""
LP_CHARGE=""; LP_CAPTURED=""; LP_TRANSFER=""; LP_TRANSFER_AMOUNT=""
# Plan 30-29: --mode direct puts the intent, its charge and its refund on the connected
# account, so every loop Stripe call carries Stripe-Account LP_STRIPE_ACCT (empty in
# destination mode: those objects are the platform's). LP_STEPS records the steps in
# the order they ran, for the evidence entry. LP_VM prefixes a direct verdict.
LP_STRIPE_ACCT=""; LP_STEPS=(); LP_VM=""

# loop_stripe METHOD PATH OUT [FORM-FIELD...] — a loop Stripe call on the account the
# mode's objects live on. lp_on is the " on acct_…" suffix for failure lines.
loop_stripe() {
  if [ -n "$LP_STRIPE_ACCT" ]; then stripe_acct "$LP_STRIPE_ACCT" "$@"; else stripe_api "$@"; fi
}
lp_on() { [ -z "$LP_STRIPE_ACCT" ] || printf ' on %s (Stripe-Account)' "$LP_STRIPE_ACCT"; }

# loop_preflight — the tenant's connected account (read by a per-run operator that
# is deleted before anything is written) and the shop's mode. Refusals write nothing.
loop_preflight() {
  vendor_login
  vendor_tenant
  read_stripe_key
  need_tool openssl; need_tool base64
  read_kv KC_ADMIN_USERNAME KC_ADMIN_PASSWORD
  operator_up
  admin_tenant_row
  operator_down || exit 1
  [ "$AT_ID" = "$VENDOR_TENANT" ] \
    || { echo "FAIL: the vendor's tenant $VENDOR_TENANT is not \"$TENANT_NAME\" ($AT_ID)" >&2; exit 1; }
  if [ "$AT_STATUS" != ENABLED ] || ! [[ "$AT_ACCOUNT" =~ ^acct_[A-Za-z0-9]+$ ]]; then
    refuse connect-status "tenant $AT_ID's connected account is ${AT_ACCOUNT:-none} with status $AT_STATUS, not ENABLED: an order now would be a plain platform charge, never a settlement-mode proof (finish --stage connect-onboard first). Nothing was written"
  fi
  LP_ACCOUNT="$AT_ACCOUNT"
  shop_by_slug
  read_shop "$WORKDIR/ml-shop.json"
  rm -f "$WORKDIR/ml-shop.json"
  case "$LOOP_MODE:${SHOP_MODE:-null}" in
    destination:null|destination:DESTINATION_CHARGE_PLATFORM_MOR) ;;
    direct:DIRECT_CHARGE_VENDOR_MOR) ;;
    destination:*) refuse mode-mismatch "$SHOP_SLUG routes as ${SHOP_MODE:-null}, but --mode $LOOP_MODE needs DESTINATION_CHARGE_PLATFORM_MOR (or null under the MARKETPLACE onboarding); set it with --stage settlement-mode first. Nothing was written" ;;
    *) refuse mode-mismatch "$SHOP_SLUG routes as ${SHOP_MODE:-null}, but --mode direct needs DIRECT_CHARGE_VENDOR_MOR (null routes as a destination charge under the MARKETPLACE onboarding); set it with --stage settlement-mode --set DIRECT_CHARGE_VENDOR_MOR first. Nothing was written" ;;
  esac
  if [ "$LOOP_MODE" = direct ]; then
    LP_STRIPE_ACCT="$LP_ACCOUNT"; LP_VM="mode=direct "
    echo "OK: preflight: tenant $AT_ID account $LP_ACCOUNT is ENABLED; $SHOP_SLUG mode $SHOP_MODE routes as a direct charge on $LP_ACCOUNT (every Stripe call carries Stripe-Account)"
  else
    echo "OK: preflight: tenant $AT_ID account $LP_ACCOUNT is ENABLED; $SHOP_SLUG mode ${SHOP_MODE:-null} routes as a destination charge"
  fi
}

# loop_evidence_check — a non-empty --evidence file must already be a JSON object; it
# is checked before anything is read or written, and again when it is written.
loop_evidence_check() {
  [ -n "$LOOP_EVIDENCE" ] && [ -s "$LOOP_EVIDENCE" ] || return 0
  jq -e 'type == "object"' "$LOOP_EVIDENCE" >/dev/null 2>&1 \
    || { echo "FAIL: --evidence $LOOP_EVIDENCE is not a JSON object; it was left untouched and nothing was written" >&2; exit 1; }
}

# loop_menu — the public menu, read anonymously FIRST (no Key Vault read, no write):
# the first available dish and the order total, which --refund-pennies must stay
# strictly below (PAY-03 adjacency edge: a full refund is never the loop's proof).
LP_FULFILMENT=""; LP_PID=""; LP_PRICE=""; LP_MASK=""
loop_menu() {
  local code fulfilment fee pick pid price mask title
  need_tool curl
  ensure_workdir
  code=$(pub_api GET "/api/v1/public/shops/$SHOP_SLUG" "$WORKDIR/ml-pub-shop.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/public/shops/$SHOP_SLUG" "$code" "$WORKDIR/ml-pub-shop.json"
  if [ "$(jq -r '.offersCollection' "$WORKDIR/ml-pub-shop.json")" = true ]; then
    fulfilment=COLLECTION; fee=0
  elif [ "$(jq -r '.offersDelivery' "$WORKDIR/ml-pub-shop.json")" = true ]; then
    fulfilment=DELIVERY; fee=$(jq -r '.deliveryFeePennies // 0' "$WORKDIR/ml-pub-shop.json")
  else
    echo "FAIL: $SHOP_SLUG offers neither collection nor delivery" >&2; exit 1
  fi
  rm -f "$WORKDIR/ml-pub-shop.json"
  code=$(pub_api GET "/api/v1/public/shops/$SHOP_SLUG/products" "$WORKDIR/ml-pub-products.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/public/shops/$SHOP_SLUG/products" "$code" "$WORKDIR/ml-pub-products.json"
  pick=$(jq -c '[(if type == "object" and has("content") then .content[] elif type == "object" then .[][] else .[] end)
                 | select(.inStock == true)][0] // empty' "$WORKDIR/ml-pub-products.json")
  rm -f "$WORKDIR/ml-pub-products.json"
  [ -n "$pick" ] || { echo "FAIL: $SHOP_SLUG has no available product on its public menu" >&2; exit 1; }
  pid=$(jq -r '.id' <<<"$pick"); price=$(jq -r '.pricePennies' <<<"$pick")
  mask=$(jq -r '.allergenMask // 0' <<<"$pick"); title=$(jq -r '.title // .name // "?"' <<<"$pick")
  [[ "$price" =~ ^[0-9]+$ ]] && [[ "$mask" =~ ^[0-9]+$ ]] || { echo "FAIL: the menu entry for $pid has no integer price or allergen mask" >&2; exit 1; }
  LP_TOTAL=$((price + fee))
  echo "OK: basket = 1 x \"$title\" ($pid) at ${price}p, allergen mask $mask, $fulfilment, expected total ${LP_TOTAL}p"
  LP_FULFILMENT="$fulfilment"; LP_PID="$pid"; LP_PRICE="$price"; LP_MASK="$mask"
  if [ "$LOOP_REFUND_PENNIES" -ge "$LP_TOTAL" ]; then
    usage_error "--refund-pennies $LOOP_REFUND_PENNIES is not less than the order total ${LP_TOTAL}p: the loop proves a PARTIAL refund only. Nothing was read from Key Vault or written"
  fi
}

# loop_order — one storefront order for the basket loop_menu chose.
loop_order() {
  local code cs ost acct_cs fulfilment="$LP_FULFILMENT" pid="$LP_PID" price="$LP_PRICE" mask="$LP_MASK"
  jq -n --arg n "$GUEST_NAME" --arg e "$GUEST_EMAIL" --arg ph "$GUEST_PHONE" --arg f "$fulfilment" \
        --arg p "$pid" --argjson pr "$price" --argjson m "$mask" --argjson t "$LP_TOTAL" '
      {customerName: $n, customerEmail: $e, customerPhone: $ph, fulfilmentType: $f,
       items: [{productId: $p, quantity: 1, expectedUnitPricePennies: $pr}],
       acknowledgedAllergenMask: $m, expectedTotalPennies: $t}
      + (if $f == "DELIVERY" then {addressLine1: "1 Staging Test Street", addressCity: "Birmingham", addressPostcode: "B1 1AA"} else {} end)' \
    | body "$WORKDIR/ml-order.json"
  code=$(pub_api POST "/api/v1/public/shops/$SHOP_SLUG/orders" "$WORKDIR/ml-order-out.json" "$WORKDIR/ml-order.json" \
    "Idempotency-Key: $LP_RUN-order")
  rm -f "$WORKDIR/ml-order.json"
  [ "$code" = 201 ] || fail_http "POST /api/v1/public/shops/$SHOP_SLUG/orders" "$code" "$WORKDIR/ml-order-out.json"
  LP_ORDER=$(jq -r '.orderNumber // empty' "$WORKDIR/ml-order-out.json")
  ost=$(jq -r '.status // empty' "$WORKDIR/ml-order-out.json")
  cs=$(jq -r '.clientSecret // empty' "$WORKDIR/ml-order-out.json")
  acct_cs=$(jq -r '.stripeAccountId // empty' "$WORKDIR/ml-order-out.json")
  rm -f "$WORKDIR/ml-order-out.json"
  [ -n "$LP_ORDER" ] || { cs=""; echo "FAIL: the 201 carried no orderNumber" >&2; exit 1; }
  if [ "$LOOP_MODE" = direct ]; then
    # A direct charge's intent lives on the connected account, and the 201 names it.
    if [ -z "$acct_cs" ]; then
      cs=""; refuse mode-mismatch "order $LP_ORDER's intent was created on the platform account (the 201 names no connected account), not as a direct charge on $LP_ACCOUNT; it is not confirmed or refunded by the loop"
    fi
    [ "$acct_cs" = "$LP_ACCOUNT" ] \
      || { cs=""; echo "FAIL: order $LP_ORDER's intent is on connected account $acct_cs, not the tenant's account $LP_ACCOUNT" >&2; exit 1; }
  elif [ -n "$acct_cs" ]; then
    cs=""; refuse mode-mismatch "order $LP_ORDER's intent was created on connected account $acct_cs (a direct charge), not as a destination charge on the platform"
  fi
  [ -n "$cs" ] || { echo "FAIL: order $LP_ORDER came back with no client secret: a cash order, which the loop must never place (is the Stripe key configured on core-java?)" >&2; exit 1; }
  LP_INTENT="${cs%%_secret_*}"
  cs=""
  [[ "$LP_INTENT" =~ ^pi_[A-Za-z0-9]+$ ]] || { echo "FAIL: the client secret does not carry a PaymentIntent id" >&2; exit 1; }
  echo "OK: order $LP_ORDER status $ost (Idempotency-Key $LP_RUN-order; client secret not shown)"
  LP_STEPS+=(order)
}

# loop_confirm — the intent confirmed with pm_card_bypassPending, unless it succeeded.
loop_confirm() {
  local code pstatus
  code=$(loop_stripe GET "/v1/payment_intents/$LP_INTENT" "$WORKDIR/ml-pi.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/payment_intents/$LP_INTENT$(lp_on)" "$code" "$WORKDIR/ml-pi.json"
  assert_test_mode "$WORKDIR/ml-pi.json" "PaymentIntent $LP_INTENT"
  if [ "$(jq -r '.metadata.order_number // empty' "$WORKDIR/ml-pi.json")" != "$LP_ORDER" ]; then
    echo "FAIL: PaymentIntent $LP_INTENT names order '$(jq -r '.metadata.order_number // ""' "$WORKDIR/ml-pi.json")', not $LP_ORDER" >&2; exit 1
  fi
  pstatus=$(jq -r '.status' "$WORKDIR/ml-pi.json")
  rm -f "$WORKDIR/ml-pi.json"
  case "$pstatus" in
    succeeded)
      echo "SKIP: PaymentIntent $LP_INTENT already succeeded; not confirmed again" ;;
    requires_payment_method|requires_confirmation)
      STRIPE_EXTRA_HEADER="Idempotency-Key: $LP_RUN-confirm"
      code=$(loop_stripe POST "/v1/payment_intents/$LP_INTENT/confirm" "$WORKDIR/ml-pi-confirm.json" \
        "payment_method=pm_card_bypassPending" "return_url=$ORDER_RETURN_URL")
      STRIPE_EXTRA_HEADER=""
      [ "$code" = 200 ] || stripe_fail "POST /v1/payment_intents/$LP_INTENT/confirm$(lp_on)" "$code" "$WORKDIR/ml-pi-confirm.json"
      assert_test_mode "$WORKDIR/ml-pi-confirm.json" "the confirmed PaymentIntent $LP_INTENT"
      echo "CONFIRMED: PaymentIntent $LP_INTENT with pm_card_bypassPending (status $(jq -r '.status' "$WORKDIR/ml-pi-confirm.json"); Idempotency-Key $LP_RUN-confirm)"
      rm -f "$WORKDIR/ml-pi-confirm.json" ;;
    *)
      echo "FAIL: PaymentIntent $LP_INTENT is $pstatus, which the loop cannot settle" >&2; exit 1 ;;
  esac
  LP_STEPS+=(confirm)
}

# loop_wait_captured — the public tracking read until paymentStatus CAPTURED (the
# D-28 webhook settled it); every read printed; deadline PAID_DEADLINE_SECONDS.
loop_wait_captured() {
  local code q t0 track tstatus tpay
  q=$(jq -rn --arg e "$GUEST_EMAIL" '$e | @uri')
  t0=$(date +%s)
  while :; do
    tpay=""
    code=$(pub_api GET "/api/v1/public/orders/$LP_ORDER?email=$q" "$WORKDIR/ml-track.json")
    if [ "$code" = 200 ]; then
      tstatus=$(jq -r '.status // ""' "$WORKDIR/ml-track.json"); tpay=$(jq -r '.paymentStatus // ""' "$WORKDIR/ml-track.json")
      track="status=$tstatus payment_status=$tpay"
    else
      track="http=$code"
    fi
    rm -f "$WORKDIR/ml-track.json"
    echo "LOOP-WAIT order=$LP_ORDER $track elapsed=$(( $(date +%s) - t0 ))s"
    [ "$tpay" != CAPTURED ] || break
    if [ $(( $(date +%s) - t0 )) -ge "$PAID_DEADLINE_SECONDS" ]; then
      echo "FAIL: order $LP_ORDER did not read CAPTURED within ${PAID_DEADLINE_SECONDS}s (deadline); last tracking read: $track" >&2
      # A direct charge's payment_intent.succeeded is an event on the connected account:
      # it reaches core-java only through the Connect webhook endpoint (plan 30-12).
      [ "$LOOP_MODE" != direct ] || echo "HINT: a direct charge settles through the Connect-scope webhook endpoint; check it is registered and delivering for $LP_ACCOUNT" >&2
      exit 1
    fi
    sleep "$POLL_SECONDS"
  done
  LP_STEPS+=(captured)
}

# loop_read_destination — the intent, its charge and its transfer, each by id. The
# intent must be a destination charge to the tenant's account (Pitfall 6).
loop_read_destination() {
  local code row st tdtype dest fee smode on amt ch cap cpi ctr tdest tamt tsrc
  code=$(stripe_api GET "/v1/payment_intents/$LP_INTENT" "$WORKDIR/ml-pi.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/payment_intents/$LP_INTENT" "$code" "$WORKDIR/ml-pi.json"
  assert_test_mode "$WORKDIR/ml-pi.json" "PaymentIntent $LP_INTENT"
  row=$(jq -r '[.status // "", (.transfer_data | type), (.transfer_data.destination // ""), (.application_fee_amount // "" | tostring),
                .metadata.settlement_mode // "", .metadata.order_number // "", (.amount // "" | tostring),
                (if (.latest_charge | type) == "object" then .latest_charge.id else (.latest_charge // "") end)] | join("\u001f")' \
          "$WORKDIR/ml-pi.json" 2>/dev/null) || row=""
  rm -f "$WORKDIR/ml-pi.json"
  [ -n "$row" ] || { echo "FAIL: PaymentIntent $LP_INTENT could not be read" >&2; exit 1; }
  IFS=$'\x1f' read -r st tdtype dest fee smode on amt ch <<<"$row" || true
  [ "$on" = "$LP_ORDER" ] || { echo "FAIL: PaymentIntent $LP_INTENT names order '$on', not $LP_ORDER" >&2; exit 1; }
  [ "$st" = succeeded ] || { echo "FAIL: PaymentIntent $LP_INTENT is '$st' after CAPTURED, not succeeded" >&2; exit 1; }
  if [ "$tdtype" != object ] || [ -z "$dest" ]; then
    refuse plain-charge "PaymentIntent $LP_INTENT has no transfer_data.destination: a plain platform charge, never recorded as a destination-charge proof (Pitfall 6); the order is not accepted or refunded by the loop"
  fi
  [ "$dest" = "$LP_ACCOUNT" ] || { echo "FAIL: PaymentIntent $LP_INTENT transfer_data.destination is $dest, not the tenant's account $LP_ACCOUNT" >&2; exit 1; }
  [[ "$fee" =~ ^[1-9][0-9]*$ ]] || { echo "FAIL: PaymentIntent $LP_INTENT application_fee_amount is '${fee:-absent}' (want > 0 on a destination charge)" >&2; exit 1; }
  [ "$smode" = DESTINATION_CHARGE_PLATFORM_MOR ] \
    || { echo "FAIL: PaymentIntent $LP_INTENT metadata.settlement_mode is '${smode:-absent}', not DESTINATION_CHARGE_PLATFORM_MOR" >&2; exit 1; }
  [ "$amt" = "$LP_TOTAL" ] || { echo "FAIL: PaymentIntent $LP_INTENT amount is $amt, not the order total $LP_TOTAL" >&2; exit 1; }
  [[ "$ch" =~ ^ch_[A-Za-z0-9]+$ ]] || { echo "FAIL: PaymentIntent $LP_INTENT names no ch_ latest_charge ('$ch')" >&2; exit 1; }
  LP_AMOUNT="$amt"; LP_FEE="$fee"; LP_CHARGE="$ch"
  echo "LOOP-ORDER mode=destination order=$LP_ORDER intent=$LP_INTENT amount=$amt application_fee=$fee"
  LP_STEPS+=(intent)

  code=$(stripe_api GET "/v1/charges/$ch" "$WORKDIR/ml-ch.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/charges/$ch" "$code" "$WORKDIR/ml-ch.json"
  assert_test_mode "$WORKDIR/ml-ch.json" "Charge $ch"
  row=$(jq -r '[(.captured | tostring), (.amount_captured // "" | tostring), (.payment_intent // ""),
                (if (.transfer | type) == "object" then .transfer.id else (.transfer // "") end)] | join("\u001f")' \
          "$WORKDIR/ml-ch.json" 2>/dev/null) || row=""
  rm -f "$WORKDIR/ml-ch.json"
  IFS=$'\x1f' read -r cap LP_CAPTURED cpi ctr <<<"$row" || true
  [ "$cap" = true ] || { echo "FAIL: Charge $ch reads captured '$cap', not true" >&2; exit 1; }
  [ "$cpi" = "$LP_INTENT" ] || { echo "FAIL: Charge $ch belongs to '$cpi', not $LP_INTENT" >&2; exit 1; }
  [ "$LP_CAPTURED" = "$amt" ] || { echo "FAIL: Charge $ch amount_captured is '$LP_CAPTURED', not $amt" >&2; exit 1; }
  [[ "$ctr" =~ ^tr_[A-Za-z0-9]+$ ]] || { echo "FAIL: Charge $ch carries no tr_ transfer ('$ctr'); a destination charge creates one" >&2; exit 1; }
  echo "LOOP-CAPTURE charge=$ch captured=true amount=$LP_CAPTURED"
  LP_STEPS+=(charge)

  code=$(stripe_api GET "/v1/transfers/$ctr" "$WORKDIR/ml-tr.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/transfers/$ctr" "$code" "$WORKDIR/ml-tr.json"
  assert_test_mode "$WORKDIR/ml-tr.json" "Transfer $ctr"
  row=$(jq -r '[(if (.destination | type) == "object" then .destination.id else (.destination // "") end), (.amount // "" | tostring),
                (if (.source_transaction | type) == "object" then .source_transaction.id else (.source_transaction // "") end)] | join("\u001f")' \
          "$WORKDIR/ml-tr.json" 2>/dev/null) || row=""
  rm -f "$WORKDIR/ml-tr.json"
  IFS=$'\x1f' read -r tdest tamt tsrc <<<"$row" || true
  [ "$tdest" = "$LP_ACCOUNT" ] || { echo "FAIL: Transfer $ctr destination is '$tdest', not the tenant's account $LP_ACCOUNT" >&2; exit 1; }
  [ "$tamt" = "$((amt - fee))" ] || { echo "FAIL: Transfer $ctr amount is '$tamt', not amount $amt minus application_fee_amount $fee" >&2; exit 1; }
  [ -z "$tsrc" ] || [ "$tsrc" = "$ch" ] || { echo "FAIL: Transfer $ctr source_transaction is $tsrc, not $ch" >&2; exit 1; }
  LP_TRANSFER="$ctr"; LP_TRANSFER_AMOUNT="$tamt"
  echo "LOOP-TRANSFER transfer=$ctr destination=$tdest amount=$tamt"
  LP_STEPS+=(transfer)
}

# loop_read_direct — plan 30-29: the intent and its charge, each by id ON THE CONNECTED
# ACCOUNT (Stripe-Account). A direct charge carries no transfer_data (the money is the
# vendor's from the start): the platform's share is application_fee_amount, and the
# routing is named by metadata.settlement_mode. There is no transfer to read.
loop_read_direct() {
  local code row st tdtype fee smode on amt ch cap cpi cfee
  code=$(loop_stripe GET "/v1/payment_intents/$LP_INTENT" "$WORKDIR/ml-pi.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/payment_intents/$LP_INTENT$(lp_on)" "$code" "$WORKDIR/ml-pi.json"
  assert_test_mode "$WORKDIR/ml-pi.json" "PaymentIntent $LP_INTENT"
  row=$(jq -r '[.status // "", (.transfer_data | type), (.application_fee_amount // "" | tostring),
                .metadata.settlement_mode // "", .metadata.order_number // "", (.amount // "" | tostring),
                (if (.latest_charge | type) == "object" then .latest_charge.id else (.latest_charge // "") end)] | join("\u001f")' \
          "$WORKDIR/ml-pi.json" 2>/dev/null) || row=""
  rm -f "$WORKDIR/ml-pi.json"
  [ -n "$row" ] || { echo "FAIL: PaymentIntent $LP_INTENT could not be read" >&2; exit 1; }
  IFS=$'\x1f' read -r st tdtype fee smode on amt ch <<<"$row" || true
  [ "$on" = "$LP_ORDER" ] || { echo "FAIL: PaymentIntent $LP_INTENT names order '$on', not $LP_ORDER" >&2; exit 1; }
  [ "$st" = succeeded ] || { echo "FAIL: PaymentIntent $LP_INTENT is '$st' after CAPTURED, not succeeded" >&2; exit 1; }
  [ "$tdtype" = null ] \
    || { echo "FAIL: PaymentIntent $LP_INTENT on $LP_ACCOUNT carries transfer_data: a destination charge, not a direct charge" >&2; exit 1; }
  [[ "$fee" =~ ^[1-9][0-9]*$ ]] || { echo "FAIL: PaymentIntent $LP_INTENT application_fee_amount is '${fee:-absent}' (want > 0 on a direct charge)" >&2; exit 1; }
  [ "$smode" = DIRECT_CHARGE_VENDOR_MOR ] \
    || { echo "FAIL: PaymentIntent $LP_INTENT metadata.settlement_mode is '${smode:-absent}', not DIRECT_CHARGE_VENDOR_MOR" >&2; exit 1; }
  [ "$amt" = "$LP_TOTAL" ] || { echo "FAIL: PaymentIntent $LP_INTENT amount is $amt, not the order total $LP_TOTAL" >&2; exit 1; }
  [[ "$ch" =~ ^ch_[A-Za-z0-9]+$ ]] || { echo "FAIL: PaymentIntent $LP_INTENT names no ch_ latest_charge ('$ch')" >&2; exit 1; }
  LP_AMOUNT="$amt"; LP_FEE="$fee"; LP_CHARGE="$ch"
  echo "LOOP-ORDER mode=direct order=$LP_ORDER intent=$LP_INTENT amount=$amt application_fee=$fee account=$LP_ACCOUNT"
  LP_STEPS+=(intent)

  code=$(loop_stripe GET "/v1/charges/$ch" "$WORKDIR/ml-ch.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/charges/$ch$(lp_on)" "$code" "$WORKDIR/ml-ch.json"
  assert_test_mode "$WORKDIR/ml-ch.json" "Charge $ch"
  row=$(jq -r '[(.captured | tostring), (.amount_captured // "" | tostring), (.payment_intent // ""),
                (.application_fee_amount // "" | tostring)] | join("\u001f")' "$WORKDIR/ml-ch.json" 2>/dev/null) || row=""
  rm -f "$WORKDIR/ml-ch.json"
  IFS=$'\x1f' read -r cap LP_CAPTURED cpi cfee <<<"$row" || true
  [ "$cap" = true ] || { echo "FAIL: Charge $ch reads captured '$cap', not true" >&2; exit 1; }
  [ "$cpi" = "$LP_INTENT" ] || { echo "FAIL: Charge $ch belongs to '$cpi', not $LP_INTENT" >&2; exit 1; }
  [ "$LP_CAPTURED" = "$amt" ] || { echo "FAIL: Charge $ch amount_captured is '$LP_CAPTURED', not $amt" >&2; exit 1; }
  [ -z "$cfee" ] || [ "$cfee" = "$fee" ] || { echo "FAIL: Charge $ch application_fee_amount is $cfee, not the intent's $fee" >&2; exit 1; }
  echo "LOOP-CAPTURE mode=direct charge=$ch captured=true amount=$LP_CAPTURED account=$LP_ACCOUNT"
  LP_STEPS+=(charge)
}

# The refund half (Task 2). REFUND_DEADLINE_SECONDS bounds both the Stripe refund read
# and the local refund row's catch-up.
REFUND_DEADLINE_SECONDS="${STAGING_SEED_REFUND_DEADLINE_SECONDS:-60}"
LOOP_REFUND_REASON="REQUESTED_BY_CUSTOMER"
LP_ORDER_ID=""; LP_LOCAL_REFUND=""; LP_REFUND=""; LP_REFUND_AMOUNT=""; LP_REFUND_STATUS=""; LP_LOCAL_STATUS=""
LP_REVERSAL=""; LP_REVERSAL_AMOUNT=""

# loop_accept — the vendor accepts the paid order (a refund needs CONFIRMED or later).
loop_accept() {
  local code st
  find_order_id "$LP_ORDER" PENDING CONFIRMED \
    || { echo "FAIL: order $LP_ORDER is neither PENDING nor CONFIRMED in the vendor's view" >&2; exit 1; }
  LP_ORDER_ID="$FOUND_ID"
  if [ "$FOUND_STATUS" = CONFIRMED ]; then
    echo "SKIP: $LP_ORDER is already CONFIRMED; not accepted again"
  else
    code=$(api vendor POST "/api/v1/orders/$LP_ORDER_ID/confirm" "$WORKDIR/ml-accept.json")
    [ "$code" = 200 ] || fail_http "POST /api/v1/orders/{id}/confirm ($LP_ORDER)" "$code" "$WORKDIR/ml-accept.json"
    st=$(jq -r '.status // empty' "$WORKDIR/ml-accept.json" 2>/dev/null || true)
    rm -f "$WORKDIR/ml-accept.json"
    [ "$st" = CONFIRMED ] || { echo "FAIL: the accept of $LP_ORDER answered status '$st', not CONFIRMED" >&2; exit 1; }
  fi
  echo "LOOP-ACCEPT ${LP_VM}order=$LP_ORDER status=CONFIRMED"
  LP_STEPS+=(accept)
}

# loop_refund — a PARTIAL refund through the vendor API, then Stripe's refund read by
# id (status succeeded, a transfer_reversal: RefundService sets reverse_transfer on a
# destination charge), its charge read for livemode (a Refund object carries no
# livemode field, docs.stripe.com/api/refunds/object), the transfer's reversal, and
# the local refund row polled until it equals Stripe's status.
loop_refund() {
  local code row n lamt sre k sst ramt rch rpi trr t0 lst rvamt
  [ "$LOOP_REFUND_PENNIES" -lt "$LP_CAPTURED" ] \
    || { echo "FAIL: --refund-pennies $LOOP_REFUND_PENNIES is not less than the captured ${LP_CAPTURED}p; no refund was issued (partial only)" >&2; exit 1; }
  # Plan 30-29 (T-30-112): a re-run with the same --run-id finds the refund it already
  # issued (RefundService stores the client's Idempotency-Key on the row, and the order
  # detail lists it) and requests nothing again.
  code=$(api vendor GET "/api/v1/orders/$LP_ORDER_ID/detail" "$WORKDIR/ml-detail0.json")
  [ "$code" = 200 ] || fail_http "GET /api/v1/orders/{id}/detail ($LP_ORDER)" "$code" "$WORKDIR/ml-detail0.json"
  row=$(jq -r --arg k "$LP_RUN-refund" '[.refunds[]? | select(.idempotencyKey == $k)]
          | if length == 0 then "" elif length == 1 then ([.[0].id // "", (.[0].amountPennies // "" | tostring), .[0].stripeRefundId // ""] | join("\u001f"))
            else "many" end' "$WORKDIR/ml-detail0.json" 2>/dev/null) || row="unparseable"
  rm -f "$WORKDIR/ml-detail0.json"
  case "$row" in
    many|unparseable) echo "FAIL: order $LP_ORDER's refunds could not be matched to run $LP_RUN ($row)" >&2; exit 1 ;;
  esac
  if [ -n "$row" ]; then
    IFS=$'\x1f' read -r LP_LOCAL_REFUND lamt sre <<<"$row" || true
    [[ "$LP_LOCAL_REFUND" =~ ^[0-9a-f-]{36}$ ]] || { echo "FAIL: run $LP_RUN's refund row carries no refund id" >&2; exit 1; }
    [ "$lamt" = "$LOOP_REFUND_PENNIES" ] \
      || { echo "FAIL: run $LP_RUN already refunded ${lamt}p of $LP_ORDER, not ${LOOP_REFUND_PENNIES}p; re-run it with the same --refund-pennies" >&2; exit 1; }
    echo "SKIP: run $LP_RUN already requested refund $LP_LOCAL_REFUND (${lamt}p, Idempotency-Key $LP_RUN-refund); not requested again"
    LP_STEPS+=(refund-found)
  else
    jq -n --argjson a "$LOOP_REFUND_PENNIES" --arg r "$LOOP_REFUND_REASON" --arg n "PAY-03 staging money loop $LP_RUN: a partial refund" \
      '{amountPennies: $a, reason: $r, note: $n}' | body "$WORKDIR/ml-refund.json"
    API_EXTRA_HEADER="Idempotency-Key: $LP_RUN-refund"
    code=$(api vendor POST "/api/v1/orders/$LP_ORDER_ID/refund" "$WORKDIR/ml-refund-out.json" "$WORKDIR/ml-refund.json")
    API_EXTRA_HEADER=""
    rm -f "$WORKDIR/ml-refund.json"
    [ "$code" = 201 ] || fail_http "POST /api/v1/orders/{id}/refund ($LP_ORDER, ${LOOP_REFUND_PENNIES}p)" "$code" "$WORKDIR/ml-refund-out.json"
    row=$(jq -r '[.id // "", (.amountPennies // "" | tostring), .stripeRefundId // ""] | join("\u001f")' "$WORKDIR/ml-refund-out.json" 2>/dev/null) || row=""
    rm -f "$WORKDIR/ml-refund-out.json"
    IFS=$'\x1f' read -r LP_LOCAL_REFUND lamt sre <<<"$row" || true
    [[ "$LP_LOCAL_REFUND" =~ ^[0-9a-f-]{36}$ ]] || { echo "FAIL: the refund 201 carried no refund id" >&2; exit 1; }
    [ "$lamt" = "$LOOP_REFUND_PENNIES" ] || { echo "FAIL: the refund row is for '$lamt'p, not ${LOOP_REFUND_PENNIES}p" >&2; exit 1; }
    echo "OK: refund $LP_LOCAL_REFUND requested through the vendor API for ${lamt}p (Idempotency-Key $LP_RUN-refund)"
    LP_STEPS+=(refund)
  fi

  # This run's refund among the intent's: RefundService stamps metadata.refund_id. In
  # direct mode the refund is the connected account's (RefundService sends Stripe-Account),
  # so it is listed and read there.
  code=$(loop_stripe GET "/v1/refunds?payment_intent=$LP_INTENT&limit=100" "$WORKDIR/ml-refunds.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/refunds?payment_intent=$LP_INTENT$(lp_on)" "$code" "$WORKDIR/ml-refunds.json"
  n=$(jq -r --arg r "$LP_LOCAL_REFUND" 'if .object == "list" then ([.data[]? | select(.metadata.refund_id == $r)] | length | tostring) else "not-a-list" end' \
        "$WORKDIR/ml-refunds.json" 2>/dev/null || echo unparseable)
  k=$(jq -r --arg r "$LP_LOCAL_REFUND" '[.data[]? | select(.metadata.refund_id == $r) | .id][0] // ""' "$WORKDIR/ml-refunds.json" 2>/dev/null || true)
  rm -f "$WORKDIR/ml-refunds.json"
  [ "$n" = 1 ] || { echo "FAIL: $n refunds of $LP_INTENT carry metadata.refund_id $LP_LOCAL_REFUND (want exactly 1 for this run)" >&2; exit 1; }
  [[ "$k" =~ ^re_[A-Za-z0-9]+$ ]] || { echo "FAIL: this run's refund carries no re_ id" >&2; exit 1; }
  [ -z "$sre" ] || [ "$sre" = "$k" ] || { echo "FAIL: the local refund row names $sre but Stripe's refund for it is $k" >&2; exit 1; }
  LP_REFUND="$k"

  t0=$(date +%s)
  while :; do
    code=$(loop_stripe GET "/v1/refunds/$LP_REFUND" "$WORKDIR/ml-re.json")
    [ "$code" = 200 ] || stripe_fail "GET /v1/refunds/$LP_REFUND$(lp_on)" "$code" "$WORKDIR/ml-re.json"
    row=$(jq -r 'def id: if type == "object" then .id else (. // "") end;
                 [.status // "", (.amount // "" | tostring), (.charge | id), (.payment_intent | id), (.transfer_reversal | id)] | join("\u001f")' \
            "$WORKDIR/ml-re.json" 2>/dev/null) || row=""
    rm -f "$WORKDIR/ml-re.json"
    IFS=$'\x1f' read -r sst ramt rch rpi trr <<<"$row" || true
    echo "LOOP-REFUND-READ refund=$LP_REFUND status=${sst:-unreadable} elapsed=$(( $(date +%s) - t0 ))s"
    case "$sst" in
      succeeded) break ;;
      failed|canceled) echo "FAIL: Refund $LP_REFUND is $sst" >&2; exit 1 ;;
    esac
    if [ $(( $(date +%s) - t0 )) -ge "$REFUND_DEADLINE_SECONDS" ]; then
      echo "FAIL: Refund $LP_REFUND did not read succeeded within ${REFUND_DEADLINE_SECONDS}s (deadline; last '$sst')" >&2; exit 1
    fi
    sleep "$POLL_SECONDS"
  done
  [ "$rpi" = "$LP_INTENT" ] || { echo "FAIL: Refund $LP_REFUND belongs to '$rpi', not $LP_INTENT" >&2; exit 1; }
  [ "$ramt" = "$LOOP_REFUND_PENNIES" ] || { echo "FAIL: Refund $LP_REFUND is for '$ramt', not $LOOP_REFUND_PENNIES" >&2; exit 1; }
  [ "$ramt" -lt "$LP_CAPTURED" ] || { echo "FAIL: Refund $LP_REFUND ($ramt) is not less than the captured $LP_CAPTURED: not a partial refund" >&2; exit 1; }
  if [ "$LOOP_MODE" = destination ]; then
    [[ "$trr" =~ ^trr_[A-Za-z0-9]+$ ]] \
      || { echo "FAIL: Refund $LP_REFUND carries no transfer_reversal ('$trr'): a destination-charge refund must reverse the connected account's transfer (reverse_transfer)" >&2; exit 1; }
  fi
  [ "$rch" = "$LP_CHARGE" ] || { echo "FAIL: Refund $LP_REFUND is on charge '$rch', not $LP_CHARGE" >&2; exit 1; }
  LP_REFUND_STATUS="$sst"; LP_REFUND_AMOUNT="$ramt"; LP_REVERSAL="$trr"
  LP_STEPS+=(refund-read)

  code=$(loop_stripe GET "/v1/charges/$rch" "$WORKDIR/ml-rch.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/charges/$rch$(lp_on)" "$code" "$WORKDIR/ml-rch.json"
  assert_test_mode "$WORKDIR/ml-rch.json" "Charge $rch (refund $LP_REFUND's: a Refund carries no livemode field)"
  rm -f "$WORKDIR/ml-rch.json"
  LP_STEPS+=(refund-charge)

  # A direct charge has no transfer, so there is no reversal to read.
  [ "$LOOP_MODE" = destination ] || { loop_refund_local; return 0; }
  code=$(stripe_api GET "/v1/transfers/$LP_TRANSFER" "$WORKDIR/ml-tr2.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/transfers/$LP_TRANSFER" "$code" "$WORKDIR/ml-tr2.json"
  assert_test_mode "$WORKDIR/ml-tr2.json" "Transfer $LP_TRANSFER"
  rvamt=$(jq -r --arg v "$trr" '[.reversals.data[]? | select(.id == $v) | .amount] | if length == 1 then (.[0] | tostring) else "" end' \
            "$WORKDIR/ml-tr2.json" 2>/dev/null || true)
  rm -f "$WORKDIR/ml-tr2.json"
  [[ "$rvamt" =~ ^[1-9][0-9]*$ ]] && [ "$rvamt" -le "$ramt" ] \
    || { echo "FAIL: Transfer $LP_TRANSFER lists no reversal $trr of 1..${ramt}p (read '$rvamt')" >&2; exit 1; }
  LP_REVERSAL_AMOUNT="$rvamt"
  echo "OK: transfer $LP_TRANSFER carries reversal $trr of ${rvamt}p (proportional to the refund)"
  LP_STEPS+=(transfer-reversal)
  loop_refund_local
}

# loop_refund_local — the local refund row polled until it equals Stripe's status (the
# refund.* events reached the app: Connect-scope ones in direct mode), then LOOP-REFUND.
loop_refund_local() {
  local code t0 lst
  t0=$(date +%s)
  while :; do
    lst=""
    code=$(api vendor GET "/api/v1/orders/$LP_ORDER_ID/detail" "$WORKDIR/ml-detail.json")
    if [ "$code" = 200 ]; then
      lst=$(jq -r --arg r "$LP_LOCAL_REFUND" '[.refunds[]? | select(.id == $r) | .status][0] // ""' "$WORKDIR/ml-detail.json" 2>/dev/null || true)
    fi
    rm -f "$WORKDIR/ml-detail.json"
    echo "LOOP-REFUND-LOCAL refund=$LP_LOCAL_REFUND local_status=${lst:-none} http=$code elapsed=$(( $(date +%s) - t0 ))s"
    [ "$lst" != "$LP_REFUND_STATUS" ] || break
    if [ $(( $(date +%s) - t0 )) -ge "$REFUND_DEADLINE_SECONDS" ]; then
      echo "FAIL: the local refund row $LP_LOCAL_REFUND reads '${lst:-none}' after ${REFUND_DEADLINE_SECONDS}s while Stripe's refund $LP_REFUND reads '$LP_REFUND_STATUS' (deadline)" >&2
      exit 1
    fi
    sleep "$POLL_SECONDS"
  done
  LP_LOCAL_STATUS="$lst"
  LP_STEPS+=(refund-local)
  if [ "$LOOP_MODE" = direct ]; then
    echo "LOOP-REFUND mode=direct refund=$LP_REFUND amount=$LP_REFUND_AMOUNT status=$LP_REFUND_STATUS account=$LP_ACCOUNT local_status=$LP_LOCAL_STATUS"
  else
    echo "LOOP-REFUND refund=$LP_REFUND amount=$LP_REFUND_AMOUNT status=$LP_REFUND_STATUS transfer_reversal=$LP_REVERSAL local_status=$LP_LOCAL_STATUS"
  fi
}

# loop_evidence — with --evidence, .modes.destination = ids, amounts and statuses only
# (T-30-111): built with jq from named values, refused if it carries a key, secret, URL
# or e-mail shape, written jq -S, mode 600; the file's other content is kept.
loop_evidence() {
  local src entry tmp steps at
  [ -n "$LOOP_EVIDENCE" ] || return 0
  loop_evidence_check
  if [ -s "$LOOP_EVIDENCE" ]; then src="$LOOP_EVIDENCE"; else src="$WORKDIR/ml-evidence-empty.json"; printf '{}' > "$src"; fi
  # The steps in the order they ran (an array: jq -S sorts keys, never array items).
  steps=$(printf '%s\n' "${LP_STEPS[@]}" | jq -Rsc 'split("\n") | map(select(length > 0))')
  at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  if [ "$LOOP_MODE" = direct ]; then
    entry=$(jq -cn --arg run "$LP_RUN" --arg o "$LP_ORDER" --arg pi "$LP_INTENT" --arg ch "$LP_CHARGE" \
              --arg re "$LP_REFUND" --arg a "$LP_ACCOUNT" --argjson cap "$LP_CAPTURED" --argjson fee "$LP_FEE" \
              --argjson ramt "$LP_REFUND_AMOUNT" --arg rs "$LP_REFUND_STATUS" --arg ls "$LP_LOCAL_STATUS" \
              --argjson steps "$steps" --arg at "$at" '
        {run_id: $run, order: $o, intent: $pi, charge: $ch, refund: $re, account: $a, settlement_mode: "DIRECT_CHARGE_VENDOR_MOR",
         amounts: {captured: $cap, application_fee: $fee, refund: $ramt},
         statuses: {intent: "succeeded", charge_captured: true, order: "CONFIRMED", refund: $rs, local_refund: $ls},
         steps: $steps, livemode: false, at: $at}')
  else
    entry=$(jq -cn --arg run "$LP_RUN" --arg o "$LP_ORDER" --arg pi "$LP_INTENT" --arg ch "$LP_CHARGE" --arg tr "$LP_TRANSFER" \
              --arg re "$LP_REFUND" --arg trr "$LP_REVERSAL" --arg a "$LP_ACCOUNT" \
              --argjson cap "$LP_CAPTURED" --argjson fee "$LP_FEE" --argjson tamt "$LP_TRANSFER_AMOUNT" \
              --argjson ramt "$LP_REFUND_AMOUNT" --argjson rvamt "$LP_REVERSAL_AMOUNT" \
              --arg rs "$LP_REFUND_STATUS" --arg ls "$LP_LOCAL_STATUS" --argjson steps "$steps" --arg at "$at" '
        {run_id: $run, order: $o, intent: $pi, charge: $ch, transfer: $tr, refund: $re, transfer_reversal: $trr, account: $a,
         amounts: {captured: $cap, application_fee: $fee, transfer: $tamt, refund: $ramt, transfer_reversal: $rvamt},
         statuses: {intent: "succeeded", charge_captured: true, order: "CONFIRMED", refund: $rs, local_refund: $ls},
         steps: $steps, livemode: false, at: $at}')
  fi
  if command grep -qiE 'sk_|rk_|whsec_|client_secret|_secret_|https?://|@' <<<"$entry"; then
    echo "FAIL: the evidence entry carries a key, secret, URL or e-mail shape; $LOOP_EVIDENCE was not written" >&2; exit 1
  fi
  tmp="$WORKDIR/ml-evidence.next.json"
  (umask 077 && jq -S --arg m "$LOOP_MODE" --argjson e "$entry" '.modes = ((.modes // {}) + {($m): $e})' "$src" > "$tmp") \
    || { echo "FAIL: the evidence could not be composed; $LOOP_EVIDENCE was not written" >&2; exit 1; }
  if [ -e "$LOOP_EVIDENCE" ]; then chmod 600 "$LOOP_EVIDENCE" || { echo "FAIL: could not chmod 600 $LOOP_EVIDENCE" >&2; exit 1; }; fi
  (umask 077 && cp "$tmp" "$LOOP_EVIDENCE") || { echo "FAIL: could not write $LOOP_EVIDENCE" >&2; exit 1; }
  chmod 600 "$LOOP_EVIDENCE"
  rm -f "$tmp"
  echo "LOOP-EVIDENCE file=$LOOP_EVIDENCE mode=$LOOP_MODE"
}

money_loop_stage() {
  LP_RUN="${RUN_ID:-loop-$(date -u +%Y%m%dT%H%M%SZ)}"
  echo "OK: money loop --mode $LOOP_MODE, run id $LP_RUN (every Idempotency-Key derives from it); refund ${LOOP_REFUND_PENNIES}p"
  loop_evidence_check
  loop_menu
  loop_preflight
  loop_order
  loop_confirm
  loop_wait_captured
  case "$LOOP_MODE" in
    destination) loop_read_destination ;;
    direct)      loop_read_direct ;;
  esac
  loop_accept
  loop_refund
  loop_evidence
}

# ===========================================================================
# PAY-03, the payout (plan 30-29; D-06 TEST mode only; T-30-112..T-30-115). Money
# leaves the platform for the vendor's bank as a payout FROM THE CONNECTED ACCOUNT's
# balance. 30-RESEARCH A4: a platform may set a manual payout schedule and create
# payouts for an Express account it controls; the stage verifies that by doing it.
#   --stage payout --account acct_… [--amount-pennies N] [--run-id ID] [--evidence FILE]
#       the account must be the staging tenant's own, ENABLED (read by a per-run
#       operator that is deleted before any Stripe call, else REFUSED [other-account] /
#       [connect-status]). This run's payout is looked up first (metadata.loop_run_id):
#       a re-run with the same --run-id reads it again and writes nothing. Otherwise:
#       POST /v1/accounts/{acct} settings[payouts][schedule][interval]=manual
#       (platform-level, no Stripe-Account; Idempotency-Key <run id>-schedule-<acct>),
#       GET /v1/balance on the account (REFUSED [no-available-balance] at 0), POST
#       /v1/payouts amount=min(N, available) (default N = available; a payout above the
#       available balance is never requested) currency=gbp with Stripe-Account and
#       Idempotency-Key <run id>-payout-<acct>, then GET /v1/payouts/{id} every
#       POLL_SECONDS until paid or the deadline (PAYOUT_DEADLINE_SECONDS, 180), every
#       state printed. Stripe's status is printed VERBATIM: a payout still in_transit or
#       pending at the deadline is rc 0 with that status and a payout-read hint, never
#       "paid". Ends PAYOUT account=… payout=po_… amount=… status=… livemode=false.
#   --stage payout-read --account acct_… --payout po_… [--evidence FILE]
#       one read of the payout by id on the account; PAYOUT line with Stripe's current
#       status; with --evidence, that payout's .payouts[] entry gets the status (the
#       only way a deferred payout becomes paid in the evidence).
# Only ids, amounts, statuses and the destination object's id are ever extracted: the
# account and payout objects carry bank details, which are never printed (T-30-115).
# ===========================================================================
PAYOUT_DEADLINE_SECONDS="${STAGING_SEED_PAYOUT_DEADLINE_SECONDS:-180}"
PO_RUN=""; PO_ID=""; PO_AMOUNT=""; PO_STATUS=""; PO_DEST=""; PO_REQUESTED=""; PO_AVAILABLE=""; PO_WHOLE=""; PO_INTERVAL=""

# payout_account_check — T-30-114: --account must be the staging tenant's connected
# account, ENABLED. The operator is deleted before the Stripe key is read.
payout_account_check() {
  operator_login
  admin_tenant_row
  operator_down || exit 1
  if [ "$AT_STATUS" != ENABLED ] || ! [[ "$AT_ACCOUNT" =~ ^acct_[A-Za-z0-9]+$ ]]; then
    refuse connect-status "tenant $AT_ID's connected account is ${AT_ACCOUNT:-none} with status $AT_STATUS, not ENABLED; no Stripe call was made"
  fi
  [ "$STRIPE_ACCOUNT" = "$AT_ACCOUNT" ] \
    || refuse other-account "--account $STRIPE_ACCOUNT is not the staging tenant's connected account ($AT_ACCOUNT); no Stripe call was made on it"
  echo "OK: $STRIPE_ACCOUNT is tenant $AT_ID's connected account (ENABLED)"
  read_stripe_key
}

# payout_fields FILE — PO_ID PO_AMOUNT PO_STATUS PO_DEST from a payout object, after its
# livemode is asserted false. Only ids, the amount and the status are extracted.
payout_fields() {
  local row cur
  assert_test_mode "$1" "Payout"
  row=$(jq -r '[.id // "", (.amount // "" | tostring), .status // "", (.currency // ""),
                (if (.destination | type) == "object" then .destination.id else (.destination // "") end)] | join("\u001f")' \
          "$1" 2>/dev/null) || row=""
  IFS=$'\x1f' read -r PO_ID PO_AMOUNT PO_STATUS cur PO_DEST <<<"$row" || true
  [[ "$PO_ID" =~ ^po_[A-Za-z0-9]+$ ]] || { echo "FAIL: the payout object carries no po_ id" >&2; exit 1; }
  [[ "$PO_AMOUNT" =~ ^[0-9]+$ ]] || { echo "FAIL: payout $PO_ID carries no integer amount" >&2; exit 1; }
  [ "$cur" = gbp ] || { echo "FAIL: payout $PO_ID is in '$cur', not gbp" >&2; exit 1; }
  [[ "$PO_DEST" =~ ^(ba|card)_[A-Za-z0-9]+$ ]] || PO_DEST=""
}

# payout_read_once ID — one GET /v1/payouts/ID on the account; the id must match.
payout_read_once() {
  local code
  code=$(stripe_acct "$STRIPE_ACCOUNT" GET "/v1/payouts/$1" "$WORKDIR/po-get.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/payouts/$1 on $STRIPE_ACCOUNT (Stripe-Account)" "$code" "$WORKDIR/po-get.json"
  payout_fields "$WORKDIR/po-get.json"
  rm -f "$WORKDIR/po-get.json"
  [ "$PO_ID" = "$1" ] || { echo "FAIL: GET /v1/payouts/$1 returned payout $PO_ID" >&2; exit 1; }
}

# payout_poll — the payout read by id until paid or the deadline; every state printed.
# failed or canceled is a failure, printed as Stripe states it.
payout_poll() {
  local t0
  t0=$(date +%s)
  while :; do
    payout_read_once "$PO_ID"
    echo "PAYOUT-WAIT payout=$PO_ID status=$PO_STATUS elapsed=$(( $(date +%s) - t0 ))s"
    case "$PO_STATUS" in
      paid) return 0 ;;
      failed|canceled) echo "FAIL: payout $PO_ID is $PO_STATUS (Stripe's status)" >&2; exit 1 ;;
    esac
    [ $(( $(date +%s) - t0 )) -lt "$PAYOUT_DEADLINE_SECONDS" ] || return 0
    sleep "$POLL_SECONDS"
  done
}

# payout_evidence MODE — with --evidence: MODE upsert adds (or replaces, by payout id) this
# payout's .payouts[] entry; MODE status changes only the status (and read_at) of the
# entry already there, else fails leaving the file untouched. jq -S, mode 600.
payout_evidence() {
  local src entry tmp at
  [ -n "$LOOP_EVIDENCE" ] || return 0
  loop_evidence_check
  if [ -s "$LOOP_EVIDENCE" ]; then src="$LOOP_EVIDENCE"; else src="$WORKDIR/po-evidence-empty.json"; printf '{}' > "$src"; fi
  at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  tmp="$WORKDIR/po-evidence.next.json"
  if [ "$1" = status ]; then
    jq -e --arg p "$PO_ID" --arg a "$STRIPE_ACCOUNT" '[.payouts[]? | select(.payout == $p and .account == $a)] | length == 1' "$src" >/dev/null 2>&1 \
      || { echo "FAIL: $LOOP_EVIDENCE has no .payouts entry for $PO_ID on $STRIPE_ACCOUNT; it was left untouched" >&2; exit 1; }
    (umask 077 && jq -S --arg p "$PO_ID" --arg s "$PO_STATUS" --arg at "$at" \
        '.payouts |= map(if .payout == $p then (.status = $s | .read_at = $at) else . end)' "$src" > "$tmp") \
      || { echo "FAIL: the evidence could not be composed; $LOOP_EVIDENCE was not written" >&2; exit 1; }
  else
    entry=$(jq -cn --arg run "$PO_RUN" --arg a "$STRIPE_ACCOUNT" --arg p "$PO_ID" --argjson amt "$PO_AMOUNT" \
              --argjson req "${PO_REQUESTED:-null}" --argjson av "${PO_AVAILABLE:-null}" --argjson whole "${PO_WHOLE:-null}" \
              --arg iv "$PO_INTERVAL" --arg s "$PO_STATUS" --arg d "$PO_DEST" --arg at "$at" '
        {run_id: $run, account: $a, payout: $p, amount: $amt, requested: $req, available: $av, whole_balance: $whole,
         schedule_interval: (if $iv == "" then null else $iv end), status: $s, destination: (if $d == "" then null else $d end),
         livemode: false, at: $at}')
    if command grep -qiE 'sk_|rk_|whsec_|client_secret|_secret_|https?://|@' <<<"$entry"; then
      echo "FAIL: the payout evidence entry carries a key, secret, URL or e-mail shape; $LOOP_EVIDENCE was not written" >&2; exit 1
    fi
    (umask 077 && jq -S --argjson e "$entry" '
        if any(.payouts[]?; .payout == $e.payout) then .payouts |= map(if .payout == $e.payout then $e else . end)
        else .payouts = ((.payouts // []) + [$e]) end' "$src" > "$tmp") \
      || { echo "FAIL: the evidence could not be composed; $LOOP_EVIDENCE was not written" >&2; exit 1; }
  fi
  if [ -e "$LOOP_EVIDENCE" ]; then chmod 600 "$LOOP_EVIDENCE" || { echo "FAIL: could not chmod 600 $LOOP_EVIDENCE" >&2; exit 1; }; fi
  (umask 077 && cp "$tmp" "$LOOP_EVIDENCE") || { echo "FAIL: could not write $LOOP_EVIDENCE" >&2; exit 1; }
  chmod 600 "$LOOP_EVIDENCE"
  rm -f "$tmp"
  echo "PAYOUT-EVIDENCE file=$LOOP_EVIDENCE payout=$PO_ID status=$PO_STATUS"
}

# payout_verdict — the hint for a payout Stripe has not reported paid, then the line.
payout_verdict() {
  if [ "$PO_STATUS" != paid ]; then
    echo "HINT: payout $PO_ID is $PO_STATUS, as Stripe reports it; read it again later with --stage payout-read --account $STRIPE_ACCOUNT --payout $PO_ID${LOOP_EVIDENCE:+ --evidence $LOOP_EVIDENCE}"
  fi
  echo "PAYOUT account=$STRIPE_ACCOUNT payout=$PO_ID amount=$PO_AMOUNT status=$PO_STATUS livemode=false"
}

# --stage payout
payout_stage() {
  local code n verdict acct="$STRIPE_ACCOUNT" pend
  PO_RUN="${RUN_ID:-loop-$(date -u +%Y%m%dT%H%M%SZ)}"
  echo "OK: payout on $acct, run id $PO_RUN (every Idempotency-Key derives from it)"
  loop_evidence_check
  payout_account_check

  # This run's payout, if a run with the same id already made it (T-30-112).
  code=$(stripe_acct "$acct" GET "/v1/payouts?limit=100" "$WORKDIR/po-list.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/payouts on $acct (Stripe-Account)" "$code" "$WORKDIR/po-list.json"
  assert_test_mode "$WORKDIR/po-list.json" "GET /v1/payouts on $acct"
  n=$(jq -r --arg r "$PO_RUN" 'if .object == "list" then ([.data[]? | select(.metadata.loop_run_id == $r)] | length | tostring) else "not-a-list" end' \
        "$WORKDIR/po-list.json" 2>/dev/null || echo unparseable)
  case "$n" in
    0) ;;
    1)
      PO_ID=$(jq -r --arg r "$PO_RUN" '[.data[] | select(.metadata.loop_run_id == $r)][0].id // ""' "$WORKDIR/po-list.json")
      rm -f "$WORKDIR/po-list.json"
      echo "SKIP: run $PO_RUN already made payout $PO_ID on $acct; nothing is written again (it is read by id)"
      payout_poll
      if [ -n "$LOOP_EVIDENCE" ] && [ -s "$LOOP_EVIDENCE" ] \
         && jq -e --arg p "$PO_ID" '[.payouts[]? | select(.payout == $p)] | length == 1' "$LOOP_EVIDENCE" >/dev/null 2>&1; then
        payout_evidence status
      else
        payout_evidence upsert
      fi
      payout_verdict
      return 0 ;;
    *) rm -f "$WORKDIR/po-list.json"; echo "FAIL: $n payouts on $acct carry metadata.loop_run_id $PO_RUN (want 0 or 1)" >&2; exit 1 ;;
  esac
  rm -f "$WORKDIR/po-list.json"

  # The schedule: platform-level, so NO Stripe-Account header.
  STRIPE_EXTRA_HEADER="Idempotency-Key: $PO_RUN-schedule-$acct"
  code=$(stripe_api POST "/v1/accounts/$acct" "$WORKDIR/po-acct.json" "settings[payouts][schedule][interval]=manual")
  STRIPE_EXTRA_HEADER=""
  [ "$code" = 200 ] || stripe_fail "POST /v1/accounts/$acct (platform-level, settings[payouts][schedule][interval]=manual)" "$code" "$WORKDIR/po-acct.json"
  verdict=$(jq -r 'if type != "object" then "unparseable" elif has("livemode") then (if .livemode == false then "false" else "live" end) else "absent" end' \
              "$WORKDIR/po-acct.json" 2>/dev/null || echo unparseable)
  case "$verdict" in
    false) ;;
    absent) echo "LIVEMODE n/a object=account (Stripe defines no livemode field on an Account; TEST mode rests on the sk_test_ key)" ;;
    live) rm -f "$WORKDIR/po-acct.json"; refuse stripe-mode "POST /v1/accounts/$acct returned a live account (D-28)" ;;
    *) rm -f "$WORKDIR/po-acct.json"; echo "FAIL: POST /v1/accounts/$acct returned no account object" >&2; exit 1 ;;
  esac
  [ "$(jq -r '.id // ""' "$WORKDIR/po-acct.json")" = "$acct" ] \
    || { rm -f "$WORKDIR/po-acct.json"; echo "FAIL: POST /v1/accounts/$acct returned another account" >&2; exit 1; }
  PO_INTERVAL=$(jq -r '.settings.payouts.schedule.interval // ""' "$WORKDIR/po-acct.json")
  rm -f "$WORKDIR/po-acct.json"
  [ "$PO_INTERVAL" = manual ] || { echo "FAIL: $acct's payout schedule reads '$PO_INTERVAL' after the update, not manual" >&2; exit 1; }
  echo "OK: $acct payout schedule interval=manual (platform-level update; Idempotency-Key $PO_RUN-schedule-$acct)"

  # The available GBP balance on the account.
  code=$(stripe_acct "$acct" GET "/v1/balance" "$WORKDIR/po-bal.json")
  [ "$code" = 200 ] || stripe_fail "GET /v1/balance on $acct (Stripe-Account)" "$code" "$WORKDIR/po-bal.json"
  assert_test_mode "$WORKDIR/po-bal.json" "Balance of $acct"
  PO_AVAILABLE=$(jq -r '[.available[]? | select(.currency == "gbp") | .amount] | add // 0 | tostring' "$WORKDIR/po-bal.json" 2>/dev/null || echo "")
  pend=$(jq -r '[.pending[]? | select(.currency == "gbp") | .amount] | add // 0 | tostring' "$WORKDIR/po-bal.json" 2>/dev/null || echo "?")
  rm -f "$WORKDIR/po-bal.json"
  [[ "$PO_AVAILABLE" =~ ^-?[0-9]+$ ]] || { echo "FAIL: the balance of $acct carries no integer available gbp amount" >&2; exit 1; }
  echo "PAYOUT-BALANCE account=$acct available=${PO_AVAILABLE}p pending=${pend}p currency=gbp"
  [ "$PO_AVAILABLE" -gt 0 ] \
    || refuse no-available-balance "$acct has ${PO_AVAILABLE}p available in gbp (pending ${pend}p): there is nothing to pay out; no payout was requested"

  # min(requested, available): a payout above the available balance is never requested.
  PO_REQUESTED="${PAYOUT_PENNIES:-$PO_AVAILABLE}"
  if [ "$PO_REQUESTED" -ge "$PO_AVAILABLE" ]; then
    PO_AMOUNT="$PO_AVAILABLE"; PO_WHOLE=true
    echo "OK: the payout is the whole available balance, ${PO_AVAILABLE}p (requested ${PO_REQUESTED}p; never more than is available)"
  else
    PO_AMOUNT="$PO_REQUESTED"; PO_WHOLE=false
  fi
  local want="$PO_AMOUNT"
  STRIPE_EXTRA_HEADER="Idempotency-Key: $PO_RUN-payout-$acct"
  code=$(stripe_acct "$acct" POST "/v1/payouts" "$WORKDIR/po-create.json" "amount=$want" "currency=gbp" "metadata[loop_run_id]=$PO_RUN")
  STRIPE_EXTRA_HEADER=""
  [ "$code" = 200 ] || stripe_fail "POST /v1/payouts on $acct (Stripe-Account, ${want}p)" "$code" "$WORKDIR/po-create.json"
  payout_fields "$WORKDIR/po-create.json"
  rm -f "$WORKDIR/po-create.json"
  [ "$PO_AMOUNT" = "$want" ] || { echo "FAIL: payout $PO_ID is for ${PO_AMOUNT}p, not the ${want}p requested" >&2; exit 1; }
  echo "OK: payout $PO_ID of ${want}p created on $acct (status $PO_STATUS; Idempotency-Key $PO_RUN-payout-$acct)"
  payout_poll
  [ "$PO_AMOUNT" = "$want" ] || { echo "FAIL: payout $PO_ID reads ${PO_AMOUNT}p, not the ${want}p requested" >&2; exit 1; }
  payout_evidence upsert
  payout_verdict
}

# --stage payout-read
payout_read_stage() {
  loop_evidence_check
  payout_account_check
  payout_read_once "$PAYOUT_ID"
  payout_evidence status
  payout_verdict
}

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
out=""; wfmt=""; hdr=""; method=""; url=""; data=0; bodyfile=""; acct_hdr=""; idem=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    -w) wfmt="$2"; shift 2 ;;
    -D) hdr="$2"; shift 2 ;;
    -X) method="$2"; shift 2 ;;
    --data-binary) data=1; case "$2" in @*) bodyfile="${2#@}"; jq -c . "${2#@}" >> "$STUB_LOG_DIR/bodies.log" 2>/dev/null || true ;; esac; shift 2 ;;
    --data-urlencode|-d) data=1; shift 2 ;;
    -K) acct_hdr=$(command grep -ho 'Stripe-Account: [^"]*' "$2" 2>/dev/null || true)
        [ -z "$acct_hdr" ] || printf '%s\n' "$acct_hdr" >> "$STUB_LOG_DIR/stripe-account.log"
        acct_hdr="${acct_hdr##*Stripe-Account: }"; shift 2 ;;
    -H) case "$2" in "Idempotency-Key: "*) idem="${2#Idempotency-Key: }" ;; esac; shift 2 ;;
    http://*|https://*) url="$1"; shift ;;
    *) shift ;;
  esac
done
[ -n "$method" ] || { if [ "$data" -eq 1 ]; then method=POST; else method=GET; fi; }
rest="${url#*://}"; path="/${rest#*/}"; base="${path%%\?*}"; query=""
case "$path" in *\?*) query="${path#*\?}" ;; esac
key=""; code=404; location=""; respfile=""
# PAY-03 (plan 30-29): every Stripe call is logged with the Stripe-Account header it
# carried and its Idempotency-Key ("METHOD PATH acct=… key=…"). With STUB_CONNECT_ACCOUNT
# set, the objects live on that connected account: a call without its header is answered
# 404 resource_missing, as Stripe answers it, and the one platform-level call (POST
# /v1/accounts/{id}) must carry no header. STUB_STRIP_ACCOUNT_ON plants a call that lost
# its header. A refused call has no side effect (no route runs).
denied=0
case "$url" in
  https://api.stripe.com/*)
    if [ -n "${STUB_STRIP_ACCOUNT_ON:-}" ] && [[ "$method $base" =~ $STUB_STRIP_ACCOUNT_ON ]]; then acct_hdr=""; fi
    printf '%s %s acct=%s key=%s\n' "$method" "$base" "${acct_hdr:-none}" "${idem:-none}" >> "$STUB_LOG_DIR/stripe-acct-flow.log"
    if [ -n "${STUB_CONNECT_ACCOUNT:-}" ]; then
      case "$method $base" in
        "POST /v1/accounts/"*)
          if [ -n "$acct_hdr" ]; then
            denied=1; code=400; respfile="$STUB_LOG_DIR/stripe-error.json"
            jq -n '{error: {type: "invalid_request_error", message: "a platform-level account update was sent with Stripe-Account"}}' > "$respfile"
          fi ;;
        *)
          if [ "$acct_hdr" != "$STUB_CONNECT_ACCOUNT" ]; then
            denied=1; code=404; respfile="$STUB_LOG_DIR/stripe-error.json"
            jq -n --arg p "$base" '{error: {type: "invalid_request_error", code: "resource_missing",
                message: ("No such object on the account this request was made on: " + $p)}}' > "$respfile"
          fi ;;
      esac
    fi ;;
esac
[ "$denied" -eq 1 ] || case "$method $base" in
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
  # A PUT of the shop is echoed back by the next GET (plan 30-28's read-back), unless
  # STUB_SHOP_PUT_IGNORED says the server dropped it.
  "GET /api/v1/shops/"*)
    key=shop; code=200
    if [ -z "${STUB_SHOP_PUT_IGNORED:-}" ] && [ -f "$STUB_LOG_DIR/shop-put-body.json" ]; then respfile="$STUB_LOG_DIR/shop-put-body.json"; fi ;;
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
  "GET /v1/payment_intents/"*)
    pid="${base#/v1/payment_intents/}"
    if [ -f "$STUB_RESP_DIR/pi-$pid.json" ]; then
      if [ -f "$STUB_LOG_DIR/pi-canceled-$pid" ]; then key="pi-$pid-canceled"; else key="pi-$pid"; fi
    elif [ -f "$STUB_LOG_DIR/confirmed" ]; then key=pi-after; else key=pi-before; fi; code=200 ;;
  "POST /v1/payment_intents/"*"/cancel")
    pid="${base#/v1/payment_intents/}"; pid="${pid%/cancel}"; key="pi-$pid-canceled"; code=200; : > "$STUB_LOG_DIR/pi-canceled-$pid" ;;
  "GET /v1/events") key=events; code=200 ;;
  # The hand tools (plan 30-07): the vendor's order lists and transitions.
  "GET /api/v1/orders/status/"*) key="orders-status-${base##*/}"; code=200 ;;
  "POST /api/v1/orders") key=vendor-order-out; code=201 ;;
  # pay-link (plan 30-19): the public phone verification, then the vendor's link.
  "POST /api/v1/public/shops/staging-test-kitchen/phone-verification/send") key=pay-send; code=200 ;;
  "POST /api/v1/public/shops/staging-test-kitchen/phone-verification/verify") key=pay-verify; code="${STUB_VERIFY_CODE:-200}" ;;
  "POST /api/v1/orders/"*"/payment-links") key=pay-link-issue; code=201 ;;
  "GET /api/v1/orders/"*"/detail")
    oid="${base#/api/v1/orders/}"; oid="${oid%/detail}"
    if [ -f "$STUB_LOG_DIR/order-cancelled-$oid" ]; then key="detail-$oid-after"; else key="detail-$oid"; fi; code=200 ;;
  "POST /api/v1/orders/"*"/cancel")
    oid="${base#/api/v1/orders/}"; oid="${oid%/cancel}"; key=empty; code=200; : > "$STUB_LOG_DIR/order-cancelled-$oid" ;;
  "POST /api/v1/orders/"*"/submit"|"POST /api/v1/orders/"*"/confirm"|"POST /api/v1/orders/"*"/start-preparation")
    key=transition; code="${STUB_TRANSITION_CODE:-200}" ;;
  # billing (plan 30-26, PAY-02 / D-04): the product's billing API, a TEST payment
  # method, then the subscription and its invoice read from Stripe by id.
  "GET /api/v1/billing/subscription") key=billing-get; code=200 ;;
  "POST /api/v1/billing/subscription") key=billing-post; code=200 ;;
  "POST /v1/payment_methods") key=pm-create; code=200 ;;
  "GET /v1/subscriptions/"*)
    subid="${base#/v1/subscriptions/}"; key="sub-$subid"
    if [ -f "$STUB_LOG_DIR/shop-created" ] && [ ! -f "$STUB_LOG_DIR/shop-deleted" ] && [ -f "$STUB_RESP_DIR/sub-$subid-added.json" ]; then
      key="sub-$subid-added"
    fi; code=200 ;;
  # billing-sync-check (plan 30-26): the probe location's create and delete.
  "POST /api/v1/shops") key=shop-create-out; code=201; : > "$STUB_LOG_DIR/shop-created" ;;
  "DELETE /api/v1/shops/"*) key=empty; code=204; : > "$STUB_LOG_DIR/shop-deleted" ;;
  "GET /v1/invoices/"*) key="inv-${base#/v1/invoices/}"; code=200 ;;
  # PAY-03 (plan 30-28): Connect onboarding, the shop's settlement mode, and the
  # destination-charge loop's charge and transfer, each read by id.
  "POST /api/v1/admin/tenants/"*"/stripe/connect") key=connect-out; code=200 ;;
  "PUT /api/v1/shops/"*)
    key=empty; code=200
    if [ -n "$bodyfile" ]; then cp "$bodyfile" "$STUB_LOG_DIR/shop-put-body.json"; respfile="$STUB_LOG_DIR/shop-put-body.json"; fi ;;
  # Each by-id read answers its own fixture when the loop arms wrote one, else the
  # generic stripe-get fixture (the stripe-get arms read refunds by id too).
  "GET /v1/charges/"*)
    key="ch-${base#/v1/charges/}"; [ -f "$STUB_RESP_DIR/$key.json" ] || key=stripe-get; code=200 ;;
  "GET /v1/transfers/"*)
    trid="${base#/v1/transfers/}"; key="tr-$trid"
    if [ -f "$STUB_LOG_DIR/refunded" ] && [ -f "$STUB_RESP_DIR/tr-$trid-after.json" ]; then key="tr-$trid-after"; fi
    [ -f "$STUB_RESP_DIR/$key.json" ] || key=stripe-get; code=200 ;;
  # The vendor refund (it marks the transfer reversed), then the refund read from Stripe.
  "POST /api/v1/orders/"*"/refund") key=refund-out; code=201; : > "$STUB_LOG_DIR/refunded" ;;
  "GET /v1/refunds")
    key=refund-list; [ -f "$STUB_RESP_DIR/$key.json" ] || key=stripe-get; code=200 ;;
  "GET /v1/refunds/"*)
    key="re-${base#/v1/refunds/}"; [ -f "$STUB_RESP_DIR/$key.json" ] || key=stripe-get; code=200 ;;
  # The payout stages (plan 30-29): the platform-level schedule update, then the connected
  # account's balance and payouts. A by-id payout read walks po-<id>-<n> fixtures in order
  # when an arm wrote them, then po-<id>, else the generic fixture.
  "POST /v1/accounts/"*) key=po-account; code=200 ;;
  "GET /v1/balance") key=po-balance; [ -f "$STUB_RESP_DIR/$key.json" ] || key=stripe-get; code=200 ;;
  "GET /v1/payouts") key=po-list; [ -f "$STUB_RESP_DIR/$key.json" ] || key=stripe-get; code=200 ;;
  "POST /v1/payouts") key=po-create; code=200; : > "$STUB_LOG_DIR/payout-created" ;;
  "GET /v1/payouts/"*)
    poid="${base#/v1/payouts/}"; pon=$(( $(cat "$STUB_LOG_DIR/po-reads-$poid" 2>/dev/null || echo 0) + 1 ))
    echo "$pon" > "$STUB_LOG_DIR/po-reads-$poid"; key="po-$poid-$pon"
    [ -f "$STUB_RESP_DIR/$key.json" ] || key="po-$poid"; [ -f "$STUB_RESP_DIR/$key.json" ] || key=stripe-get; code=200 ;;
  # stripe-get: any other Stripe read answers the one generic fixture.
  "GET /v1/"*) key=stripe-get; code=200 ;;
esac
if [ -n "${STUB_FAIL_ON:-}" ] && [[ "$method $base" =~ $STUB_FAIL_ON ]]; then
  key=problem; code=500; respfile=""; rm -f "$STUB_LOG_DIR/pi-canceled-${pid:-}" "$STUB_LOG_DIR/order-cancelled-${oid:-}"
fi
printf '%s %s -> %s\n' "$method" "$url" "$code" >> "$STUB_LOG_DIR/curl-flow.log"
if [ -n "$out" ]; then
  if [ -n "$respfile" ] && [ -f "$respfile" ]; then cat "$respfile" > "$out"
  elif [ -n "$key" ] && [ -f "$STUB_RESP_DIR/$key.json" ]; then cat "$STUB_RESP_DIR/$key.json" > "$out"; else printf '{}' > "$out"; fi
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
    rm -f "$ast/logs/"order-cancelled-* "$ast/logs/"pi-canceled-*; : > "$ast/logs/stripe-account.log"
    rm -f "$ast/logs/shop-created" "$ast/logs/shop-deleted" "$ast/logs/shop-put-body.json" "$ast/logs/refunded"
    : > "$ast/logs/stripe-acct-flow.log"; rm -f "$ast/logs/stripe-error.json" "$ast/logs/"po-reads-* "$ast/logs/payout-created"
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
  # ===== cancel-drafts (D-02): an explicit list, intent first, both read back ===
  # _st_cancel_fx ID NUMBER PI(or "") ORDER_STATUS PAYMENT_STATUS PI_STATUS LIVEMODE
  _st_cancel_fx() {
    jq -n --arg id "$1" --arg n "$2" --arg pi "$3" --arg st "$4" --arg ps "$5" '{id: $id, orderNumber: $n, status: $st,
        paymentStatus: $ps, paymentReference: (if $pi == "" then null else $pi end), totalAmountPennies: 950,
        customerName: "Pii PIIZQ7 Name", customerEmail: "piizq7@example.invalid", customerPhone: "07700 900777",
        addressLine1: "1 PIIZQ7 Road", allergyNote: "PIIZQ7 peanuts", items: []}' > "$ast/resp/detail-$1.json"
    jq '.status = "CANCELLED"' "$ast/resp/detail-$1.json" > "$ast/resp/detail-$1-after.json"
    if [ -n "$3" ]; then
      jq -n --arg id "$3" --arg st "$6" --argjson lm "$7" --arg n "$2" --arg cs "${3}_secret_STUBCS77" '{id: $id,
          object: "payment_intent", status: $st, livemode: $lm, amount: 950, currency: "gbp", client_secret: $cs,
          receipt_email: "piizq7@example.invalid", metadata: {order_number: $n}}' > "$ast/resp/pi-$3.json"
      jq '.status = "canceled" | .cancellation_reason = "abandoned"' "$ast/resp/pi-$3.json" > "$ast/resp/pi-$3-canceled.json"
    fi
  }
  _st_flow() { command grep -cE "$1" "$ast/logs/curl-flow.log" || true; }
  _st_line() { command grep -nE "$1" "$ast/logs/curl-flow.log" | head -n 1 | cut -d: -f1 || true; }
  local re_stripe='^[A-Z]+ https://api\.stripe\.com/' re_scancel='^POST https://api\.stripe\.com/v1/payment_intents/[^ ]+/cancel -> ' \
        re_vcancel='^POST https://api-staging\.olajay\.co\.uk/api/v1/orders/[^ ]+/cancel -> ' \
        cancel_re='^SEED-CANCEL order=ORD-STUB-D001 intent=pi_drafta intent_status=canceled order_status=CANCELLED livemode=false at=[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z$'
  local cd_args=("${tool_args[@]}" --stage cancel-drafts)

  # ===== pay-link (plan 30-19, PAY-04 / D-03): a real payable link, made as a vendor
  # and a customer would make it. The OTP, the token, the URL and the client secret
  # each carry a marker (583961, STUBTOKpl77, STUBCSpl77), so a print is detectable.
  local pl_out_dir="$ast/link-out" pl_oid="cccccccc-0000-4000-8000-000000000001" pl_lid="dddddddd-0000-4000-8000-000000000001"
  local pl_url="https://app-staging.olajay.co.uk/pay#token=STUBTOKpl77"
  local pl_leak_re='583961|STUBTOKpl77|STUBCSpl77|pay#token'
  local pl_args=("${tool_args[@]}" --stage pay-link)
  mkdir -p "$pl_out_dir"
  _st_pay_fx() {   # OTP(or "") — the send answer; verify and issue are fixed
    jq -n --arg o "$1" '{challengeId: "eeeeeeee-0000-4000-8000-000000000001", phoneNormalized: "+447700900123",
        channel: "SMS", expiresAt: "2026-11-01T11:10:00Z", testPreviewOtp: (if $o == "" then null else $o end)}' \
      > "$ast/resp/pay-send.json"
    jq -n '{verified: true, phoneNormalized: "+447700900123", verifiedAt: "2026-11-01T11:01:00Z"}' > "$ast/resp/pay-verify.json"
    jq -n --arg id "$pl_lid" --arg oid "$pl_oid" --arg u "$pl_url" '{id: $id, orderId: $oid, orderNumber: "ORD-STUB-V001",
        purpose: "UPFRONT", amountPennies: 950, paymentUrl: $u, token: "STUBTOKpl77", stripePaymentIntentId: "pi_paylink",
        clientSecret: "pi_paylink_secret_STUBCSpl77", expiresAt: "2026-11-01T12:00:00Z"}' > "$ast/resp/pay-link-issue.json"
    _st_vendor_order DRAFT NONE
  }
  _st_pl_leaks() { command grep -cE "$pl_leak_re" <<<"$ST_OUT$ST_ERR$(cat "$ast/logs/curl.log")" || true; }

  # --- pay-link-ok: send, order, verify, issue; the URL reaches only the mode-600 file
  _st_pay_fx 583961
  rm -f "$pl_out_dir/ok.url"; printf 'stale\n' > "$pl_out_dir/ok.url"; chmod 644 "$pl_out_dir/ok.url"
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${pl_args[@]}" --link-out "$pl_out_dir/ok.url"
  why=""
  local pl_body pl_vbody pl_mode l_send l_ord l_ver l_iss
  pl_body=$(command grep -F '"customerPhone"' "$ast/logs/bodies.log" | tail -n 1 || true)
  pl_vbody=$(command grep -F '"otp"' "$ast/logs/bodies.log" | tail -n 1 || true)
  pl_mode=$(stat -c %a "$pl_out_dir/ok.url" 2>/dev/null || echo none)
  l_send=$(_st_line '^POST https://api-staging\.olajay\.co\.uk/api/v1/public/shops/staging-test-kitchen/phone-verification/send -> 200$')
  l_ord=$(_st_line '^POST https://api-staging\.olajay\.co\.uk/api/v1/orders -> 201$')
  l_ver=$(_st_line '^POST https://api-staging\.olajay\.co\.uk/api/v1/public/shops/staging-test-kitchen/phone-verification/verify -> 200$')
  l_iss=$(_st_line "^POST https://api-staging\.olajay\.co\.uk/api/v1/orders/$pl_oid/payment-links -> 201$")
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(tail -n 1 <<<"$ST_OUT")" != "PAY-LINK order=ORD-STUB-V001 link=$pl_lid expires=2026-11-01T12:00:00Z" ]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  elif [ -z "$l_send" ] || [ -z "$l_ord" ] || [ -z "$l_ver" ] || [ -z "$l_iss" ]; then why="a call is missing: send=$l_send order=$l_ord verify=$l_ver issue=$l_iss"
  elif ! { [ "$l_send" -lt "$l_ord" ] && [ "$l_ord" -lt "$l_ver" ] && [ "$l_ver" -lt "$l_iss" ]; }; then why="call order is not send<order<verify<issue: $l_send $l_ord $l_ver $l_iss"
  elif [ "$(jq -cS 'del(.items)' <<<"${pl_body:-null}" 2>/dev/null)" != "$(jq -cnS --arg s "$sid" '{shopId: $s, customerName: "Staging Pay Link",
        customerPhone: "+447700900123", customerEmail: "guest.staging+paylink@olajay.co.uk", fulfilmentType: "COLLECTION"}')" ]; then
    why="the order body is not the pay-link customer: ${pl_body:-<none>}"
  elif [ "$(jq -c '.items' <<<"$pl_body")" != "$(jq -cn --arg p "$p1" '[{productId: $p, quantity: 1}]')" ]; then why="the order is not one line of the first available dish"
  elif ! command grep -qE 'Idempotency-Key: pay-link-[0-9]{8}T[0-9]{6}Z( |$)' "$ast/logs/curl.log"; then why="the order POST carries no Idempotency-Key pay-link-<UTC stamp>"
  elif [ "$(jq -cS . <<<"${pl_vbody:-null}" 2>/dev/null)" != '{"otp":"583961","phone":"+447700900123"}' ]; then why="the verify body is not {phone, the OTP send returned}: ${pl_vbody:-<none>}"
  elif [ "$(command grep -cF '"purpose":"UPFRONT"' "$ast/logs/bodies.log" || true)" -ne 1 ]; then why="the issue body is not {purpose: UPFRONT}"
  elif [ "$pl_mode" != 600 ]; then why="the --link-out file is mode $pl_mode (want 600)"
  elif [ "$(cat "$pl_out_dir/ok.url")" != "$pl_url" ]; then why="the --link-out file does not hold exactly the issued URL"
  elif [ "$(_st_pl_leaks)" -ne 0 ]; then why="the OTP, token, URL or client secret was printed or put in argv ($(_st_pl_leaks) hit(s))"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="a secret marker was printed or put in argv"
  fi
  if [ -z "$why" ]; then _st_result "PASS pay-link-ok rc=0 order=send<order<verify<issue file_mode=600 printed_otp_token_url_cs=0"
  else _st_result "FAIL pay-link-ok $why"; fails=$((fails + 1)); fi

  # --- pay-link-otp-hidden: staging keeps the OTP hidden (owner keep-hidden) -> refuse
  _st_pay_fx ""
  rm -f "$pl_out_dir/hidden.url"
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${pl_args[@]}" --link-out "$pl_out_dir/hidden.url"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qF 'REFUSED [otp-hidden]' <<<"$ST_ERR"; then why="stderr lacks 'REFUSED [otp-hidden]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow '^POST https://api-staging\.olajay\.co\.uk/api/v1/public/shops/staging-test-kitchen/phone-verification/send ')" -ne 1 ]; then why="the send was not called exactly once (the refusal was not reached through it)"
  elif [ "$(_st_flow '^POST https://api-staging\.olajay\.co\.uk/api/v1/orders')" -ne 0 ]; then why="an order or a link was written before the refusal: $(command grep -E '/api/v1/orders' "$ast/logs/curl-flow.log" | tr '\n' '|')"
  elif [ "$(_st_flow '/phone-verification/verify ')" -ne 0 ]; then why="verify was called"
  elif [ -e "$pl_out_dir/hidden.url" ]; then why="the --link-out file was created"
  elif command grep -q '^PAY-LINK ' <<<"$ST_OUT"; then why="a PAY-LINK line was printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS pay-link-otp-hidden rc=1 tag=otp-hidden order_posts=0 issue_calls=0"
  else _st_result "FAIL pay-link-otp-hidden $why"; fails=$((fails + 1)); fi

  # --- pay-link-no-out: no --link-out -> usage error before any guard or call ------
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${pl_args[@]}"
  if why=$(_st_refusal 2 "USAGE ERROR: --stage pay-link needs --link-out" 0 0); then _st_result "PASS pay-link-no-out rc=2 az_calls=0 curl_calls=0"
  else _st_result "FAIL pay-link-no-out $why"; fails=$((fails + 1)); fi

  # --- pay-link-unverified: the verify answers 422 -> rc 1, no link is issued -------
  _st_pay_fx 583961
  jq -n '{type: "about:blank", title: "Unprocessable Entity", status: 422, detail: "Invalid verification code."}' > "$ast/resp/pay-verify.json"
  rm -f "$pl_out_dir/unverified.url"
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" STUB_VERIFY_CODE=422 -- "${pl_args[@]}" --link-out "$pl_out_dir/unverified.url"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow '/phone-verification/verify -> 422$')" -ne 1 ]; then why="the verify was not answered 422 exactly once"
  elif [ "$(_st_flow '/payment-links -> ')" -ne 0 ]; then why="a payment link was requested after a failed verification"
  elif [ -e "$pl_out_dir/unverified.url" ]; then why="the --link-out file was created"
  elif command grep -q '^PAY-LINK ' <<<"$ST_OUT"; then why="a PAY-LINK line was printed"
  elif [ "$(_st_pl_leaks)" -ne 0 ]; then why="the OTP was printed or put in argv"
  fi
  if [ -z "$why" ]; then _st_result "PASS pay-link-unverified rc=1 verify=422 issue_calls=0"
  else _st_result "FAIL pay-link-unverified $why"; fails=$((fails + 1)); fi


  # --- cancel-one: GET order, GET intent, cancel intent, read back, cancel order, read back
  _st_cancel_fx "$oA" ORD-STUB-D001 pi_drafta DRAFT PENDING requires_payment_method false
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${cd_args[@]}" --orders ORD-STUB-D001
  why=""
  local l_sc l_pi2 l_vc l_det2
  l_sc=$(_st_line '^POST https://api\.stripe\.com/v1/payment_intents/pi_drafta/cancel -> 200$')
  l_pi2=$(command grep -nE '^GET https://api\.stripe\.com/v1/payment_intents/pi_drafta -> 200$' "$ast/logs/curl-flow.log" | tail -n 1 | cut -d: -f1 || true)
  l_vc=$(_st_line "^POST https://api-staging\.olajay\.co\.uk/api/v1/orders/$oA/cancel -> 200$")
  l_det2=$(command grep -nE "^GET https://api-staging\.olajay\.co\.uk/api/v1/orders/$oA/detail -> 200$" "$ast/logs/curl-flow.log" | tail -n 1 | cut -d: -f1 || true)
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! [[ "$(command grep -E '^SEED-CANCEL ' <<<"$ST_OUT" || true)" =~ $cancel_re ]]; then why="no SEED-CANCEL verdict: $(command grep -E '^SEED-CANCEL ' <<<"$ST_OUT" || echo none)"
  elif [ "$(tail -n 1 <<<"$ST_OUT")" != "CANCEL-DRAFTS requested=1 cancelled=1 refused=0" ]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  elif [ -z "$l_sc" ] || [ -z "$l_pi2" ] || [ -z "$l_vc" ] || [ -z "$l_det2" ]; then why="a step is missing: stripe_cancel=${l_sc:-none} intent_readback=${l_pi2:-none} order_cancel=${l_vc:-none} order_readback=${l_det2:-none}"
  elif ! { [ "$l_sc" -lt "$l_pi2" ] && [ "$l_pi2" -lt "$l_vc" ] && [ "$l_vc" -lt "$l_det2" ]; }; then why="out of order: stripe_cancel=$l_sc intent_readback=$l_pi2 order_cancel=$l_vc order_readback=$l_det2"
  elif ! command grep -qF 'Idempotency-Key: d02-cancel-pi_drafta' "$ast/logs/curl.log" || ! command grep -qF 'cancellation_reason=abandoned' "$ast/logs/curl.log"; then
    why="the Stripe cancel lacks Idempotency-Key d02-cancel-pi_drafta or cancellation_reason=abandoned"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="a key or client secret was printed or put in argv"
  elif [ "$(_st_pii)" -ne 0 ]; then why="a customer field was printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS cancel-one rc=0 order=stripe_cancel:$l_sc<intent_readback:$l_pi2<order_cancel:$l_vc<order_readback:$l_det2 verdict=SEED-CANCEL"
  else _st_result "FAIL cancel-one $why"; fails=$((fails + 1)); fi

  # --- cancel-not-draft: a PENDING order, a vendor (NONE) DRAFT, an unlisted number
  local sub
  why=""
  for sub in pending none unlisted; do
    case "$sub" in
      pending)  _st_cancel_fx "$oB" ORD-STUB-D002 pi_draftb PENDING PENDING requires_payment_method false; set -- ORD-STUB-D002 ;;
      none)     _st_cancel_fx "$oC" ORD-STUB-D003 "" DRAFT NONE x false; set -- ORD-STUB-D003 ;;
      unlisted) set -- ORD-STUB-D999 ;;
    esac
    _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${cd_args[@]}" --orders "$1"
    if [ "$ST_RC" -ne 1 ]; then why="$sub: rc=$ST_RC (want 1)"
    elif ! command grep -qF 'REFUSED [not-draft]' <<<"$ST_ERR"; then why="$sub: stderr lacks 'REFUSED [not-draft]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(_st_flow "$re_stripe")" -ne 0 ]; then why="$sub: Stripe calls=$(_st_flow "$re_stripe") (want 0)"
    elif [ "$(_st_flow "$re_vcancel")" -ne 0 ]; then why="$sub: vendor cancel calls=$(_st_flow "$re_vcancel") (want 0)"
    elif [ "$(tail -n 1 <<<"$ST_OUT")" != "CANCEL-DRAFTS requested=1 cancelled=0 refused=1" ]; then why="$sub: the last line is '$(tail -n 1 <<<"$ST_OUT")'"
    fi
    [ -z "$why" ] || break
  done
  set --
  if [ -z "$why" ]; then _st_result "PASS cancel-not-draft rc=1 pending+none+unlisted=refused stripe_calls=0 vendor_cancels=0"
  else _st_result "FAIL cancel-not-draft $why"; fails=$((fails + 1)); fi

  # --- cancel-paid: succeeded, processing and requires_capture are never cancelled
  why=""
  for sub in succeeded processing requires_capture; do
    _st_cancel_fx "$oA" ORD-STUB-D001 pi_drafta DRAFT PENDING "$sub" false
    _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${cd_args[@]}" --orders ORD-STUB-D001
    if [ "$ST_RC" -ne 1 ]; then why="$sub: rc=$ST_RC (want 1)"
    elif ! command grep -qF 'REFUSED [paid]' <<<"$ST_ERR"; then why="$sub: stderr lacks 'REFUSED [paid]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(_st_flow '^GET https://api\.stripe\.com/v1/payment_intents/pi_drafta -> 200$')" -lt 1 ]; then why="$sub: the intent was never read (the refusal cannot come from it)"
    elif [ "$(_st_flow "$re_scancel")" -ne 0 ]; then why="$sub: Stripe cancel calls=$(_st_flow "$re_scancel") (want 0)"
    elif [ "$(_st_flow "$re_vcancel")" -ne 0 ]; then why="$sub: vendor cancel calls=$(_st_flow "$re_vcancel") (want 0)"
    fi
    [ -z "$why" ] || break
  done
  if [ -z "$why" ]; then _st_result "PASS cancel-paid rc=1 succeeded+processing+requires_capture=refused stripe_cancels=0 vendor_cancels=0"
  else _st_result "FAIL cancel-paid $why"; fails=$((fails + 1)); fi

  # --- cancel-live: an intent that reads livemode true is refused ---------------
  _st_cancel_fx "$oA" ORD-STUB-D001 pi_drafta DRAFT PENDING requires_payment_method true
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${cd_args[@]}" --orders ORD-STUB-D001
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1)"
  elif ! command grep -qF 'REFUSED [stripe-mode]' <<<"$ST_ERR"; then why="stderr lacks 'REFUSED [stripe-mode]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow "$re_scancel")" -ne 0 ] || [ "$(_st_flow "$re_vcancel")" -ne 0 ]; then why="cancel calls: stripe=$(_st_flow "$re_scancel") vendor=$(_st_flow "$re_vcancel") (want 0, 0)"
  fi
  if [ -z "$why" ]; then _st_result "PASS cancel-live rc=1 stripe_cancels=0 vendor_cancels=0"
  else _st_result "FAIL cancel-live $why"; fails=$((fails + 1)); fi

  # --- cancel-stripe-fails: D001's Stripe cancel answers 500; D002 still goes ---
  _st_cancel_fx "$oA" ORD-STUB-D001 pi_drafta DRAFT PENDING requires_payment_method false
  _st_cancel_fx "$oB" ORD-STUB-D002 pi_draftb DRAFT FAILED requires_payment_method false
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" STUB_FAIL_ON='^POST /v1/payment_intents/pi_drafta/cancel$' \
    -- "${cd_args[@]}" --orders ORD-STUB-D001,ORD-STUB-D002
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow '^POST https://api\.stripe\.com/v1/payment_intents/pi_drafta/cancel -> 500$')" -ne 1 ]; then why="the failing Stripe cancel was not observed"
  elif [ "$(_st_flow "^POST https://api-staging\.olajay\.co\.uk/api/v1/orders/$oA/cancel ")" -ne 0 ]; then why="the order whose intent did not cancel was cancelled anyway"
  elif [ "$(_st_flow "^POST https://api-staging\.olajay\.co\.uk/api/v1/orders/$oB/cancel -> 200$")" -ne 1 ]; then why="the next named order (D002) was not cancelled: the loop stopped at the failure"
  elif [ "$(tail -n 1 <<<"$ST_OUT")" != "CANCEL-DRAFTS requested=2 cancelled=1 refused=1" ]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  fi
  if [ -z "$why" ]; then _st_result "PASS cancel-stripe-fails rc=1 D001_vendor_cancels=0 D002_cancelled=1 summary=2/1/1"
  else _st_result "FAIL cancel-stripe-fails $why"; fails=$((fails + 1)); fi

  # --- cancel-already-canceled: no Stripe write, the order is still cancelled ---
  _st_cancel_fx "$oA" ORD-STUB-D001 pi_drafta DRAFT PENDING canceled false
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${cd_args[@]}" --orders ORD-STUB-D001
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow '^POST https://api\.stripe\.com/')" -ne 0 ]; then why="Stripe POSTs=$(_st_flow '^POST https://api\.stripe\.com/') on an already-canceled intent (want 0)"
  elif [ "$(_st_flow "$re_vcancel")" -ne 1 ]; then why="vendor cancel calls=$(_st_flow "$re_vcancel") (want 1)"
  elif ! [[ "$(command grep -E '^SEED-CANCEL ' <<<"$ST_OUT" || true)" =~ $cancel_re ]]; then why="no SEED-CANCEL verdict"
  fi
  if [ -z "$why" ]; then _st_result "PASS cancel-already-canceled rc=0 stripe_posts=0 vendor_cancels=1"
  else _st_result "FAIL cancel-already-canceled $why"; fails=$((fails + 1)); fi

  # --- cancel-only-listed: three cancellable DRAFTs, one named, one touched -----
  _st_cancel_fx "$oA" ORD-STUB-D001 pi_drafta DRAFT PENDING requires_payment_method false
  _st_cancel_fx "$oB" ORD-STUB-D002 pi_draftb DRAFT FAILED requires_payment_method false
  _st_cancel_fx "$oC" ORD-STUB-D003 pi_draftc DRAFT PENDING requires_payment_method false
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${cd_args[@]}" --orders ORD-STUB-D001
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow "$re_vcancel")" -ne 1 ]; then why="vendor cancel calls=$(_st_flow "$re_vcancel") (want exactly 1)"
  elif [ "$(_st_flow "/api/v1/orders/$oA/cancel -> 200$")" -ne 1 ]; then why="the one cancel was not the named order's"
  elif [ "$(_st_flow "$oB|$oC|pi_draftb|pi_draftc")" -ne 0 ]; then why="an unnamed order or its intent was touched: $(command grep -E "$oB|$oC|pi_draftb|pi_draftc" "$ast/logs/curl-flow.log" | head -n 2 | tr '\n' '|')"
  fi
  if [ -z "$why" ]; then _st_result "PASS cancel-only-listed rc=0 vendor_cancels=1 unnamed_touched=0"
  else _st_result "FAIL cancel-only-listed $why"; fails=$((fails + 1)); fi

  # --- cancel-empty-list: no list, an empty list, or a malformed entry ----------
  why=""
  for sub in absent empty malformed; do
    case "$sub" in
      absent)    _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${cd_args[@]}" ;;
      empty)     _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${cd_args[@]}" --orders "" ;;
      malformed) _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${cd_args[@]}" --orders 'ORD-STUB-D001,*' ;;
    esac
    why=$(_st_refusal 2 "USAGE ERROR: --stage cancel-drafts needs --orders" 0 0) || { why="$sub: $why"; break; }
    why=""
  done
  if [ -z "$why" ]; then _st_result "PASS cancel-empty-list rc=2 absent+empty+malformed=usage-error az_calls=0 curl_calls=0"
  else _st_result "FAIL cancel-empty-list $why"; fails=$((fails + 1)); fi
  # ===== orphan intents (plan 30-09, issue #1038): an intent whose order is gone =
  # _st_orders_fx NUMBER... — the vendor sees exactly these orders, all CANCELLED
  # (a status cancel-drafts' DRAFT lookup never reads), every other status empty.
  _st_orders_fx() {
    local st
    for st in DRAFT PENDING CONFIRMED PREPARING READY COMPLETED CANCELLED REFUNDED; do
      jq -n '{content: [], totalElements: 0, totalPages: 1, number: 0, last: true}' > "$ast/resp/orders-status-$st.json"
    done
    printf '%s\n' "$@" | jq -R 'select(. != "")' | jq -s '{content: [.[] | {id: ("oid-" + .), orderNumber: ., status: "CANCELLED",
        customerEmail: "piizq7@example.invalid"}], totalPages: 1, number: 0, last: true}' > "$ast/resp/orders-status-CANCELLED.json"
  }
  # _st_opi ID STATUS LIVEMODE ORDER_NUMBER(or "") [TENANT] — one intent fixture (the
  # vendor's tenant unless named), and its canceled twin
  _st_opi() {
    jq -n --arg id "$1" --arg st "$2" --argjson lm "$3" --arg n "$4" --arg t "${5:-$tid}" --arg cs "${1}_secret_STUBCS77" '{id: $id,
        object: "payment_intent", status: $st, livemode: $lm, amount: 950, currency: "gbp", created: 1760000000,
        client_secret: $cs, receipt_email: "piizq7@example.invalid", metadata: ((if $n == "" then {} else {order_number: $n} end) + {tenant_id: $t})}' \
      > "$ast/resp/pi-$1.json"
    jq '.status = "canceled" | .cancellation_reason = "abandoned"' "$ast/resp/pi-$1.json" > "$ast/resp/pi-$1-canceled.json"
  }
  local lo_args=("${tool_args[@]}" --stage list-orphan-intents) co_args=("${tool_args[@]}" --stage cancel-orphan-intents)
  local orphan_pi=pi_orphana
  local orphan_re='^SEED-ORPHAN-CANCEL intent=pi_orphana order_number=ORD-STUB-G001 intent_status=canceled livemode=false at=[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z$'

  # --- orphan-list: only the open intent whose order the vendor cannot see ---
  _st_orders_fx ORD-STUB-K001
  _st_opi pi_orphana requires_payment_method false ORD-STUB-G001
  _st_opi pi_known requires_payment_method false ORD-STUB-K001
  _st_opi pi_paidx succeeded false ORD-STUB-G002
  _st_opi pi_nometa requires_payment_method false ""
  _st_opi pi_othert requires_payment_method false ORD-STUB-T001 99999999-9999-9999-9999-999999999999
  jq -s '{object: "list", has_more: false, data: .}' "$ast/resp/pi-pi_orphana.json" "$ast/resp/pi-pi_known.json" \
    "$ast/resp/pi-pi_paidx.json" "$ast/resp/pi-pi_nometa.json" "$ast/resp/pi-pi_othert.json" > "$ast/resp/pi-list.json"
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${lo_args[@]}"
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(command grep -c '^ORPHAN-INTENT ' <<<"$ST_OUT" || true)" -ne 1 ]; then why="ORPHAN-INTENT lines=$(command grep -c '^ORPHAN-INTENT ' <<<"$ST_OUT" || true) (want exactly 1: pi_orphana)"
  elif ! command grep -qE '^ORPHAN-INTENT intent=pi_orphana order_number=ORD-STUB-G001 status=requires_payment_method created=[0-9T:-]+Z amount=950p$' <<<"$ST_OUT"; then why="the orphan line is not pi_orphana's: $(command grep '^ORPHAN-INTENT ' <<<"$ST_OUT" | head -c 200)"
  elif ! command grep -qE '^VENDOR-ORDERS total=1$' <<<"$ST_OUT"; then why="no VENDOR-ORDERS total=1 control line"
  elif ! command grep -qE '^LIST-ORPHANS count=1 open=2 at=' <<<"$(tail -n 1 <<<"$ST_OUT")"; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")' (want LIST-ORPHANS count=1 open=2)"
  elif [ "$(_st_flow '^GET https://api-staging\.olajay\.co\.uk/api/v1/orders/status/')" -ne 8 ]; then why="order status reads=$(_st_flow '^GET https://api-staging\.olajay\.co\.uk/api/v1/orders/status/') (want 8: every status)"
  elif [ "$(_st_non_get)" -ne 0 ]; then why="non-GET calls=$(_st_non_get) on a read-only stage"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="a key or client secret was printed or put in argv"
  elif [ "$(_st_pii)" -ne 0 ]; then why="a customer field was printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS orphan-list rc=0 orphans=1 known+paid+no-metadata+other-tenant=excluded status_reads=8 writes=0"
  else _st_result "FAIL orphan-list $why"; fails=$((fails + 1)); fi

  # --- orphan-list-blind: a vendor that sees no order at all VOIDs, lists nothing
  _st_orders_fx
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${lo_args[@]}"
  why=""
  if [ "$ST_RC" -ne 2 ]; then why="rc=$ST_RC (want 2)"
  elif ! command grep -qF 'VOID: the vendor sees no order in any status' <<<"$ST_ERR"; then why="stderr lacks the VOID: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif command grep -q '^ORPHAN-INTENT ' <<<"$ST_OUT"; then why="an orphan was listed from a blind read"
  fi
  if [ -z "$why" ]; then _st_result "PASS orphan-list-blind rc=2 void orphans=0"
  else _st_result "FAIL orphan-list-blind $why"; fails=$((fails + 1)); fi

  # --- orphan-cancel-one: read, re-check the order is absent, cancel, read back ---
  _st_orders_fx ORD-STUB-K001
  _st_opi pi_orphana requires_payment_method false ORD-STUB-G001
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${co_args[@]}" --intents pi_orphana
  local l_get l_last_status l_post l_back
  l_get=$(_st_line '^GET https://api\.stripe\.com/v1/payment_intents/pi_orphana -> 200$')
  l_last_status=$(command grep -nE '^GET https://api-staging\.olajay\.co\.uk/api/v1/orders/status/' "$ast/logs/curl-flow.log" | tail -n 1 | cut -d: -f1 || true)
  l_post=$(_st_line '^POST https://api\.stripe\.com/v1/payment_intents/pi_orphana/cancel -> 200$')
  l_back=$(command grep -nE '^GET https://api\.stripe\.com/v1/payment_intents/pi_orphana -> 200$' "$ast/logs/curl-flow.log" | tail -n 1 | cut -d: -f1 || true)
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow "$re_scancel")" -ne 1 ]; then why="Stripe cancel calls=$(_st_flow "$re_scancel") (want 1)"
  elif [ "$(_st_flow '^GET https://api-staging\.olajay\.co\.uk/api/v1/orders/status/')" -ne 16 ]; then why="order status reads=$(_st_flow '^GET https://api-staging\.olajay\.co\.uk/api/v1/orders/status/') (want 16: the stage control, then the per-intent re-check)"
  elif ! { [ -n "$l_get" ] && [ -n "$l_last_status" ] && [ -n "$l_post" ] && [ -n "$l_back" ] && [ "$l_get" -lt "$l_last_status" ] && [ "$l_last_status" -lt "$l_post" ] && [ "$l_post" -lt "$l_back" ]; }; then
    why="order is not intent-read < order re-check < cancel < read-back (lines $l_get/$l_last_status/$l_post/$l_back)"
  elif ! command grep -qF "Idempotency-Key: d02-orphan-cancel-${orphan_pi}" "$ast/logs/curl.log" || ! command grep -qF 'cancellation_reason=abandoned' "$ast/logs/curl.log"; then
    why="the Stripe cancel lacks Idempotency-Key d02-orphan-cancel-pi_orphana or cancellation_reason=abandoned"
  elif [ "$(_st_flow "$re_vcancel")" -ne 0 ]; then why="a vendor order cancel was called for an order-less intent"
  elif ! [[ "$(command grep -E '^SEED-ORPHAN-CANCEL ' <<<"$ST_OUT" || true)" =~ $orphan_re ]]; then why="no SEED-ORPHAN-CANCEL verdict: $(command grep -E '^SEED-ORPHAN' <<<"$ST_OUT" | head -c 200)"
  elif [ "$(tail -n 1 <<<"$ST_OUT")" != "CANCEL-ORPHANS requested=1 cancelled=1 refused=0" ]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="a key or client secret was printed or put in argv"
  elif [ "$(_st_pii)" -ne 0 ]; then why="a customer field was printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS orphan-cancel-one rc=0 read<recheck<cancel<readback stripe_cancels=1 vendor_cancels=0 verdict=SEED-ORPHAN-CANCEL"
  else _st_result "FAIL orphan-cancel-one $why"; fails=$((fails + 1)); fi

  # --- orphan-cancel-refusals: order visible, paid, no metadata; the next still goes
  why=""
  for sub in order-exists paid no-metadata other-tenant; do
    _st_orders_fx ORD-STUB-K001
    _st_opi pi_orphana requires_payment_method false ORD-STUB-G001
    case "$sub" in
      order-exists) _st_opi pi_refx requires_payment_method false ORD-STUB-K001; tag='order-exists' ;;
      paid)         _st_opi pi_refx succeeded false ORD-STUB-G002; tag='paid' ;;
      no-metadata)  _st_opi pi_refx requires_payment_method false ""; tag='not-orphan-candidate' ;;
      other-tenant) _st_opi pi_refx requires_payment_method false ORD-STUB-T001 99999999-9999-9999-9999-999999999999; tag='other-tenant' ;;
    esac
    _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${co_args[@]}" --intents pi_refx,pi_orphana
    if [ "$ST_RC" -ne 1 ]; then why="$sub: rc=$ST_RC (want 1)"
    elif ! command grep -qF "REFUSED [$tag]" <<<"$ST_ERR"; then why="$sub: stderr lacks 'REFUSED [$tag]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(_st_flow '^POST https://api\.stripe\.com/v1/payment_intents/pi_refx/cancel')" -ne 0 ]; then why="$sub: the refused intent was cancelled"
    elif [ "$(_st_flow '^POST https://api\.stripe\.com/v1/payment_intents/pi_orphana/cancel -> 200$')" -ne 1 ]; then why="$sub: the next named intent was not cancelled: the loop stopped at the refusal"
    elif [ "$(tail -n 1 <<<"$ST_OUT")" != "CANCEL-ORPHANS requested=2 cancelled=1 refused=1" ]; then why="$sub: the last line is '$(tail -n 1 <<<"$ST_OUT")'"
    fi
    [ -z "$why" ] || break
  done
  if [ -z "$why" ]; then _st_result "PASS orphan-cancel-refusals rc=1 order-exists+paid+no-metadata+other-tenant=refused refused_cancels=0 next_cancelled=1"
  else _st_result "FAIL orphan-cancel-refusals $why"; fails=$((fails + 1)); fi

  # --- orphan-cancel-already-canceled: no Stripe write, still a verdict --------
  _st_orders_fx ORD-STUB-K001
  _st_opi pi_orphana canceled false ORD-STUB-G001
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${co_args[@]}" --intents pi_orphana
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow '^POST https://api\.stripe\.com/')" -ne 0 ]; then why="Stripe POSTs=$(_st_flow '^POST https://api\.stripe\.com/') on an already-canceled intent (want 0)"
  elif ! [[ "$(command grep -E '^SEED-ORPHAN-CANCEL ' <<<"$ST_OUT" || true)" =~ $orphan_re ]]; then why="no SEED-ORPHAN-CANCEL verdict"
  fi
  if [ -z "$why" ]; then _st_result "PASS orphan-cancel-already-canceled rc=0 stripe_posts=0"
  else _st_result "FAIL orphan-cancel-already-canceled $why"; fails=$((fails + 1)); fi

  # --- orphan-cancel-args: no list, a malformed or repeated id, --intents elsewhere
  why=""
  for sub in absent malformed twice elsewhere; do
    case "$sub" in
      absent)    _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${co_args[@]}"; want="USAGE ERROR: --stage cancel-orphan-intents needs --intents" ;;
      malformed) _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${co_args[@]}" --intents 'pi_orphana,ORD-STUB-G001'; want="USAGE ERROR: --stage cancel-orphan-intents needs --intents" ;;
      twice)     _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${co_args[@]}" --intents 'pi_orphana,pi_orphana'; want="USAGE ERROR: --stage cancel-orphan-intents needs --intents" ;;
      elsewhere) _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${lo_args[@]}" --intents pi_orphana; want="USAGE ERROR: --intents is taken by --stage cancel-orphan-intents only" ;;
    esac
    why=$(_st_refusal 2 "$want" 0 0) || { why="$sub: $why"; break; }
    why=""
  done
  if [ -z "$why" ]; then _st_result "PASS orphan-cancel-args rc=2 absent+malformed+twice+elsewhere=usage-error az_calls=0 curl_calls=0"
  else _st_result "FAIL orphan-cancel-args $why"; fails=$((fails + 1)); fi

  # ===== stripe-get: a sanitised, allowlisted, TEST-only Stripe read ==========
  local sg_args=("${tool_args[@]}" --stage stripe-get)
  # The client secret is composed at run time, so no secret-shaped literal sits
  # here for a scanner to flag (the 29-18 rule); it carries the STUBCS marker.
  local sg_cs="pi_sg1"
  sg_cs="${sg_cs}_secret_STUBCSsg1"
  jq -n --arg cs "$sg_cs" '{id: "pi_sg1", object: "payment_intent", livemode: false, status: "requires_payment_method", amount: 950,
      currency: "gbp", client_secret: $cs, receipt_email: "piizq7@example.invalid",
      metadata: {order_number: "ORD-STUB-D001"},
      shipping: {name: "Pii PIIZQ7", phone: "07700 900777", address: {line1: "1 PIIZQ7 Road"}},
      latest_charge: {id: "ch_sg1", object: "charge", livemode: false, receipt_email: "piizq7@example.invalid",
        billing_details: {name: "Pii PIIZQ7", email: "piizq7@example.invalid", phone: "07700 900777", address: {line1: "1 PIIZQ7 Road"}}}}' \
    > "$ast/resp/pi-pi_sg1.json"
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${sg_args[@]}" --path /v1/payment_intents/pi_sg1
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qF '"pi_sg1"' <<<"$ST_OUT" || ! command grep -qF '"ORD-STUB-D001"' <<<"$ST_OUT" || ! command grep -qF '"ch_sg1"' <<<"$ST_OUT"; then
    why="the object was not printed (nothing to inspect): $(head -c 200 <<<"$ST_OUT" | tr '\n' ' ')"
  elif command grep -qE '_secret_|"client_secret"|"receipt_email"|"billing_details"|"shipping"' <<<"$ST_OUT$ST_ERR"; then
    why="a client secret or personal field survived: $(command grep -E '_secret_|client_secret|receipt_email|billing_details|shipping' <<<"$ST_OUT" | head -n 2 | tr '\n' '|')"
  elif [ "$(_st_pii)" -ne 0 ] || command grep -qF '07700 900777' <<<"$ST_OUT$ST_ERR"; then why="personal data was printed"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="a key or client secret marker was printed or put in argv"
  elif [ "$(_st_flow '^GET https://api\.stripe\.com/v1/payment_intents/pi_sg1 -> 200$')" -ne 1 ] || [ "$(_st_flow '^(POST|DELETE|PUT|PATCH) ')" -ne 0 ]; then
    why="calls: want exactly one Stripe GET and no write: $(tr '\n' '|' < "$ast/logs/curl-flow.log")"
  fi
  if [ -z "$why" ]; then _st_result "PASS stripe-get-sanitized rc=0 printed=pi_sg1+ch_sg1 secrets=0 pii=0"
  else _st_result "FAIL stripe-get-sanitized $why"; fails=$((fails + 1)); fi

  # --- stripe-get-path-refused: a path off the allowlist, or a traversal --------
  why=""
  for sub in /v1/customers/cus_1 /v1/payment_intents/pi_1/../../customers /v1/payment_methods/pm_1; do
    _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${sg_args[@]}" --path "$sub"
    why=$(_st_refusal 1 "REFUSED [stripe-path]" 0 0) || { why="$sub: $why"; break; }
    why=""
  done
  if [ -z "$why" ]; then _st_result "PASS stripe-get-path-refused rc=1 customers+traversal+payment_methods=refused curl_calls=0 az_calls=0"
  else _st_result "FAIL stripe-get-path-refused $why"; fails=$((fails + 1)); fi

  # --- stripe-get-live: a live object is refused and not printed ----------------
  jq -n '{id: "pi_sg2", object: "payment_intent", livemode: true, status: "succeeded", amount: 950}' > "$ast/resp/pi-pi_sg2.json"
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${sg_args[@]}" --path /v1/payment_intents/pi_sg2
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1)"
  elif ! command grep -qF 'REFUSED [stripe-mode]' <<<"$ST_ERR"; then why="stderr lacks 'REFUSED [stripe-mode]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif command grep -qF '"pi_sg2"' <<<"$ST_OUT"; then why="the live object was printed"
  elif [ "$(_st_flow '^GET https://api\.stripe\.com/v1/payment_intents/pi_sg2 -> 200$')" -ne 1 ]; then why="the refusal did not come from the read object"
  fi
  if [ -z "$why" ]; then _st_result "PASS stripe-get-live rc=1 printed=none"
  else _st_result "FAIL stripe-get-live $why"; fails=$((fails + 1)); fi

  # --- stripe-get-account-header: Stripe-Account only when --account is given ---
  jq -n '{object: "balance", livemode: false, available: [{amount: 0, currency: "gbp"}], pending: []}' > "$ast/resp/stripe-get.json"
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${sg_args[@]}" --path /v1/balance --account acct_STUB1
  why=""
  local acct_with acct_without
  acct_with=$(cat "$ast/logs/stripe-account.log")
  if [ "$ST_RC" -ne 0 ]; then why="with --account: rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$acct_with" != "Stripe-Account: acct_STUB1" ]; then why="with --account the stub saw '$(tr '\n' '|' <<<"$acct_with")' (want exactly Stripe-Account: acct_STUB1)"
  elif command grep -qF 'Stripe-Account' "$ast/logs/curl.log"; then why="the Stripe-Account header travelled in argv, not the config file"
  else
    _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${sg_args[@]}" --path /v1/balance
    acct_without=$(cat "$ast/logs/stripe-account.log")
    if [ "$ST_RC" -ne 0 ]; then why="without --account: rc=$ST_RC (want 0)"
    elif [ "$(_st_flow '^GET https://api\.stripe\.com/v1/balance -> 200$')" -ne 1 ]; then why="without --account: the balance read was not made (the empty log would prove nothing)"
    elif [ -n "$acct_without" ]; then why="without --account the stub saw '$acct_without'"
    fi
  fi
  if [ -z "$why" ]; then _st_result "PASS stripe-get-account-header with=acct_STUB1 without=none argv=none"
  else _st_result "FAIL stripe-get-account-header $why"; fails=$((fails + 1)); fi

  # --- stripe-get-livemode-absent: refund and account objects carry no livemode
  #     field (stripe-java 34.0.0 Refund, Account); any other object without one
  #     is refused, never assumed TEST.
  jq -n '{id: "re_sg3", object: "refund", amount: 950, status: "succeeded", payment_intent: "pi_sg1"}' > "$ast/resp/stripe-get.json"
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${sg_args[@]}" --path /v1/refunds/re_sg3
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="refund: rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qF '"re_sg3"' <<<"$ST_OUT"; then why="refund: the object was not printed"
  elif ! command grep -qE '^LIVEMODE n/a object=refund ' <<<"$ST_OUT"; then why="refund: no LIVEMODE n/a line"
  else
    jq -n '{id: "pi_sg4", object: "payment_intent", status: "succeeded", amount: 950}' > "$ast/resp/pi-pi_sg4.json"
    _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${sg_args[@]}" --path /v1/payment_intents/pi_sg4
    if [ "$ST_RC" -ne 1 ]; then why="intent without livemode: rc=$ST_RC (want 1)"
    elif ! command grep -qF 'REFUSED [stripe-mode]' <<<"$ST_ERR"; then why="intent without livemode: stderr lacks 'REFUSED [stripe-mode]'"
    elif command grep -qF '"pi_sg4"' <<<"$ST_OUT"; then why="intent without livemode: it was printed"
    fi
  fi
  if [ -z "$why" ]; then _st_result "PASS stripe-get-livemode-absent refund=printed+n/a intent=refused"
  else _st_result "FAIL stripe-get-livemode-absent $why"; fails=$((fails + 1)); fi

  # ===== billing (plan 30-26, PAY-02 / D-04): the staging tenant's subscription and
  # its latest invoice, read from Stripe BY ID and checked against the tenant's
  # locations. The customer id, the card fingerprint and the invoice's customer
  # fields carry markers (STUBCUSbl1, STUBFPbl1, piizq7), so a print is detectable.
  local bl_args=("${tool_args[@]}" --stage billing) bl_leak_re='STUBCUSbl1|STUBFPbl1|piizq7|PIIZQ7'
  local bl_line_re='^BILLING sub=sub_bl1 quantity=1 locations=1 invoice=in_bl1 amount_paid=3900 status=paid livemode=false at=[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z$'
  cp "$ast/resp/shops.json" "$ast/resp/shops-before-billing.json"
  _st_bl_fx() {   # STATUS(NONE|ACTIVE) QUANTITY LOCATIONS AMOUNT_PAID INVOICE_LIVEMODE(true|false)
    local active=false; [ "$1" = ACTIVE ] && active=true
    jq -n --arg t "$tid" --argjson a "$active" --arg st "$1" '{tenantId: $t, billingPlanMode: "SAAS_HYBRID_STANDARD", subscriptionStatus: $st,
        stripeCustomerId: (if $a then "cus_STUBCUSbl1" else null end), stripeSubscriptionId: (if $a then "sub_bl1" else null end),
        latestStripeInvoiceId: (if $a then "in_bl1" else null end), monthlyAmountPennies: 3900, effectiveFeeBps: 50,
        monthlyFeeCapPennies: 0, monthlyFeeChargedPennies: 0, walkSetupCompleted: false, cancelAtPeriodEnd: false, currentPeriodEnd: null}' \
      > "$ast/resp/billing-get.json"
    jq -n --arg t "$tid" '{tenantId: $t, billingPlanMode: "SAAS_HYBRID_STANDARD", subscriptionStatus: "ACTIVE",
        stripeCustomerId: "cus_STUBCUSbl1", stripeSubscriptionId: "sub_bl1", latestStripeInvoiceId: "in_bl1",
        monthlyAmountPennies: 3900, effectiveFeeBps: 50, monthlyFeeCapPennies: 0, monthlyFeeChargedPennies: 0,
        walkSetupCompleted: false, cancelAtPeriodEnd: false, currentPeriodEnd: null}' > "$ast/resp/billing-post.json"
    jq -n '{id: "pm_bl1", object: "payment_method", livemode: false, type: "card",
        card: {brand: "visa", last4: "4242", fingerprint: "STUBFPbl1"},
        billing_details: {email: "piizq7@example.invalid", name: "Pii PIIZQ7"}}' > "$ast/resp/pm-create.json"
    jq -n --argjson q "$2" '{id: "sub_bl1", object: "subscription", livemode: false, status: "active",
        customer: "cus_STUBCUSbl1", latest_invoice: "in_bl1",
        items: {object: "list", data: [{id: "si_bl1", object: "subscription_item", quantity: $q,
          price: {id: "price_bl1", object: "price", unit_amount: 3900, currency: "gbp", recurring: {interval: "month"}}}]}}' \
      > "$ast/resp/sub-sub_bl1.json"
    jq -n --argjson p "$4" --argjson lm "$5" '{id: "in_bl1", object: "invoice", livemode: $lm, status: "paid", amount_paid: $p,
        customer: "cus_STUBCUSbl1", customer_email: "piizq7@example.invalid", customer_name: "Pii PIIZQ7",
        parent: {subscription_details: {subscription: "sub_bl1"}}}' > "$ast/resp/inv-in_bl1.json"
    jq -n --arg t "$tid" --arg s "$sid" --argjson n "$3" '{content: [range(0; $n) as $i
        | {id: (if $i == 0 then $s else "77777777-0000-4000-8000-00000000000\($i)" end), tenantId: $t,
           slug: (if $i == 0 then "staging-test-kitchen" else "staging-second-\($i)" end), published: ($i == 0)}],
        totalElements: $n, totalPages: 1}' > "$ast/resp/shops.json"
  }

  # --- billing-ok: no subscription yet -> a TEST pm from tok_visa, the product's own
  #     subscribe, then the subscription and its invoice read from Stripe by id
  _st_bl_fx NONE 1 1 3900 false
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${bl_args[@]}"
  local bl_ok_out="$ST_OUT" bl_ok_err="$ST_ERR" bl_ok_rc="$ST_RC" bl_ok_curl bl_body l_bget l_pm l_bpost l_sub l_inv l_shops
  bl_ok_curl=$(cat "$ast/logs/curl.log")
  bl_body=$(command grep -F '"planMode"' "$ast/logs/bodies.log" | tail -n 1 || true)
  l_bget=$(_st_line '^GET https://api-staging\.olajay\.co\.uk/api/v1/billing/subscription -> 200$')
  l_pm=$(_st_line '^POST https://api\.stripe\.com/v1/payment_methods -> 200$')
  l_bpost=$(_st_line '^POST https://api-staging\.olajay\.co\.uk/api/v1/billing/subscription -> 200$')
  l_sub=$(_st_line '^GET https://api\.stripe\.com/v1/subscriptions/sub_bl1 -> 200$')
  l_inv=$(_st_line '^GET https://api\.stripe\.com/v1/invoices/in_bl1 -> 200$')
  l_shops=$(_st_line '^GET https://api-staging\.olajay\.co\.uk/api/v1/shops\?size=100 -> 200$')
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! [[ "$(tail -n 1 <<<"$ST_OUT")" =~ $bl_line_re ]]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  elif [ -z "$l_bget" ] || [ -z "$l_pm" ] || [ -z "$l_bpost" ] || [ -z "$l_sub" ] || [ -z "$l_inv" ] || [ -z "$l_shops" ]; then
    why="a call is missing: billing_get=$l_bget pm=$l_pm subscribe=$l_bpost sub=$l_sub invoice=$l_inv shops=$l_shops"
  elif ! { [ "$l_bget" -lt "$l_pm" ] && [ "$l_pm" -lt "$l_bpost" ] && [ "$l_bpost" -lt "$l_sub" ] && [ "$l_sub" -lt "$l_inv" ]; }; then
    why="call order is not billing_get<pm<subscribe<sub<invoice: $l_bget $l_pm $l_bpost $l_sub $l_inv"
  elif [ "$(_st_flow '^POST https://api\.stripe\.com/v1/payment_methods ')" -ne 1 ] || [ "$(_st_flow '^POST https://api-staging\.olajay\.co\.uk/api/v1/billing/subscription ')" -ne 1 ]; then
    why="want exactly one pm creation and one subscribe: $(tr '\n' '|' < "$ast/logs/curl-flow.log")"
  elif [ "$(command grep -E '^(POST|DELETE|PUT|PATCH) https://api\.stripe\.com/' "$ast/logs/curl-flow.log" | command grep -vcE '^POST https://api\.stripe\.com/v1/payment_methods -> ' || true)" -ne 0 ]; then
    why="a Stripe write other than the pm creation: $(tr '\n' '|' < "$ast/logs/curl-flow.log")"
  elif ! command grep -qF -- '--data-urlencode type=card' <<<"$bl_ok_curl" || ! command grep -qF -- '--data-urlencode card[token]=tok_visa' <<<"$bl_ok_curl"; then
    why="the pm was not created as type=card from card[token]=tok_visa"
  elif [ "$(jq -cS . <<<"${bl_body:-null}" 2>/dev/null)" != '{"paymentMethodId":"pm_bl1","planMode":"SAAS_HYBRID_STANDARD"}' ]; then
    why="the subscribe body is not {planMode: SAAS_HYBRID_STANDARD, paymentMethodId: the pm}: ${bl_body:-<none>}"
  fi
  if [ -z "$why" ]; then _st_result "PASS billing-ok rc=0 order=billing_get<pm<subscribe<sub<invoice quantity=1 locations=1 amount_paid=3900"
  else _st_result "FAIL billing-ok $why"; fails=$((fails + 1)); fi

  # --- billing-quantity-mismatch: Stripe bills 1 location, the tenant has 2 --------
  _st_bl_fx NONE 1 2 3900 false
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${bl_args[@]}"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qE 'quantity is 1 but the tenant has 2 location' <<<"$ST_ERR"; then why="stderr does not name the quantity mismatch: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif command grep -q '^BILLING ' <<<"$ST_OUT"; then why="a BILLING line was printed"
  elif [ "$(_st_flow '^GET https://api\.stripe\.com/v1/subscriptions/sub_bl1 -> 200$')" -ne 1 ]; then why="the subscription was not read (the refusal did not come from Stripe's quantity)"
  fi
  if [ -z "$why" ]; then _st_result "PASS billing-quantity-mismatch rc=1 quantity=1 locations=2"
  else _st_result "FAIL billing-quantity-mismatch $why"; fails=$((fails + 1)); fi

  # --- billing-amount-mismatch: quantity 2 and 2 locations, but the invoice paid 3900
  _st_bl_fx NONE 2 2 3900 false
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${bl_args[@]}"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qE 'amount_paid 3900 is not 7800' <<<"$ST_ERR"; then why="stderr does not name the amount mismatch: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif command grep -q '^BILLING ' <<<"$ST_OUT"; then why="a BILLING line was printed"
  elif [ "$(_st_flow '^GET https://api\.stripe\.com/v1/invoices/in_bl1 -> 200$')" -ne 1 ]; then why="the invoice was not read by id"
  fi
  if [ -z "$why" ]; then _st_result "PASS billing-amount-mismatch rc=1 quantity=2 amount_paid=3900 want=7800"
  else _st_result "FAIL billing-amount-mismatch $why"; fails=$((fails + 1)); fi

  # --- billing-live: the invoice reads livemode true -> refused, nothing printed ---
  _st_bl_fx NONE 1 1 3900 true
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${bl_args[@]}"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qF 'REFUSED [stripe-mode]' <<<"$ST_ERR"; then why="stderr lacks 'REFUSED [stripe-mode]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif command grep -q '^BILLING ' <<<"$ST_OUT"; then why="a BILLING line was printed"
  elif [ "$(_st_flow '^GET https://api\.stripe\.com/v1/invoices/in_bl1 -> 200$')" -ne 1 ]; then why="the refusal did not come from the invoice read by id"
  fi
  if [ -z "$why" ]; then _st_result "PASS billing-live rc=1 tag=stripe-mode printed=none"
  else _st_result "FAIL billing-live $why"; fails=$((fails + 1)); fi

  # --- billing-replay: an ACTIVE subscription is read back, never re-created -------
  _st_bl_fx ACTIVE 1 1 3900 false
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${bl_args[@]}"
  local bl_rp_out="$ST_OUT" bl_rp_err="$ST_ERR"
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow '^GET https://api-staging\.olajay\.co\.uk/api/v1/billing/subscription -> 200$')" -ne 1 ]; then why="the subscription state was not read from the billing API"
  elif [ "$(_st_flow '^POST https://api-staging\.olajay\.co\.uk/api/v1/billing/subscription ')" -ne 0 ]; then why="the replay subscribed again"
  elif [ "$(_st_flow '^POST https://api\.stripe\.com/v1/payment_methods ')" -ne 0 ]; then why="the replay created a payment method"
  elif [ "$(_st_flow '^(POST|DELETE|PUT|PATCH) https://api\.stripe\.com/')" -ne 0 ]; then why="the replay wrote to Stripe"
  elif ! [[ "$(tail -n 1 <<<"$ST_OUT")" =~ $bl_line_re ]]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  fi
  if [ -z "$why" ]; then _st_result "PASS billing-replay rc=0 subscribe_calls=0 pm_calls=0 verdict=BILLING"
  else _st_result "FAIL billing-replay $why"; fails=$((fails + 1)); fi

  # --- billing-no-secret: the ok and replay runs printed no key, no pm body, no
  #     customer field (the ok run must have printed its BILLING line: non-vacuity)
  why=""
  local bl_hits
  bl_hits=$(command grep -cE "$bl_leak_re" <<<"$bl_ok_out$bl_ok_err$bl_rp_out$bl_rp_err" || true)
  if [ "$bl_ok_rc" -ne 0 ] || ! command grep -qE '^BILLING ' <<<"$bl_ok_out"; then why="the ok run did not finish (nothing to inspect; the arm would pass vacuously)"
  elif [ "$bl_hits" -ne 0 ]; then why="$bl_hits line(s) carry the customer id, the card fingerprint or a customer field"
  elif command grep -qE '"payment_method"|"billing_details"|"fingerprint"|"customer_email"' <<<"$bl_ok_out$bl_ok_err"; then why="a Stripe response body was printed"
  elif command grep -qE 'STUB-SECRET-|STUBSECRET' <<<"$bl_ok_out$bl_ok_err$bl_ok_curl$bl_rp_out$bl_rp_err"; then why="a key or secret marker was printed or put in argv"
  fi
  if [ -z "$why" ]; then _st_result "PASS billing-no-secret printed_key=0 printed_pm_body=0 printed_customer=0"
  else _st_result "FAIL billing-no-secret $why"; fails=$((fails + 1)); fi
  cp "$ast/resp/shops-before-billing.json" "$ast/resp/shops.json"

  # ===== billing-sync-check (plan 30-26, D-04): the quantity sync watched around a
  # probe location. While the probe exists the stub's subscription read answers the
  # -added fixture (when one is present), so the sync is observable; the probe's
  # DELETE must happen on every exit path.
  local bs_args=("${tool_args[@]}" --stage billing-sync-check) bs_probe="99999999-0000-4000-8000-000000000001"
  local bs_env=(STUB_PODS_FILE="$ast/pods-prod.json" STAGING_SEED_BILLING_SYNC_DEADLINE_SECONDS=3 STAGING_SEED_POLL_SECONDS=1)
  _st_bs_fx() {   # STATUS(NONE|ACTIVE) ADDED_QUANTITY(or "" for "Stripe never moves")
    _st_bl_fx "$1" 1 1 3900 false
    rm -f "$ast/resp/sub-sub_bl1-added.json"
    [ -z "$2" ] || jq --argjson q "$2" '.items.data[0].quantity = $q' "$ast/resp/sub-sub_bl1.json" > "$ast/resp/sub-sub_bl1-added.json"
    jq -n --arg id "$bs_probe" --arg t "$tid" '{id: $id, tenantId: $t, name: "Staging Billing Probe", slug: "staging-billing-probe", published: false}' \
      > "$ast/resp/shop-create-out.json"
  }

  # --- sync-ok (all-shops): 1 -> 2 -> 1 around the probe's create and delete -------
  _st_bs_fx ACTIVE 2
  _st_run "${bs_env[@]}" -- "${bs_args[@]}"
  why=""
  local bs_name l_first l_post l_del l_last
  bs_name=$(command grep -F '"Staging Billing Probe' "$ast/logs/bodies.log" | tail -n 1 | jq -r '.name // empty' 2>/dev/null || true)
  l_first=$(_st_line '^GET https://api\.stripe\.com/v1/subscriptions/sub_bl1 -> 200$')
  l_post=$(_st_line '^POST https://api-staging\.olajay\.co\.uk/api/v1/shops -> 201$')
  l_del=$(_st_line "^DELETE https://api-staging\.olajay\.co\.uk/api/v1/shops/$bs_probe -> 204$")
  l_last=$(command grep -nE '^GET https://api\.stripe\.com/v1/subscriptions/sub_bl1 -> 200$' "$ast/logs/curl-flow.log" | tail -n 1 | cut -d: -f1 || true)
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(tail -n 1 <<<"$ST_OUT")" != "BILLING-SYNC before=1 after-add=2 after-remove=1 livemode=false" ]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  elif [ "$(_st_flow '^POST https://api-staging\.olajay\.co\.uk/api/v1/shops -> ')" -ne 1 ] || [ "$(_st_flow '^DELETE https://api-staging\.olajay\.co\.uk/api/v1/shops/')" -ne 1 ]; then
    why="want exactly one probe create and one probe delete: $(command grep -E '/api/v1/shops' "$ast/logs/curl-flow.log" | tr '\n' '|')"
  elif [ -z "$l_first" ] || [ -z "$l_post" ] || [ -z "$l_del" ] || ! { [ "$l_first" -lt "$l_post" ] && [ "$l_post" -lt "$l_del" ] && [ "$l_del" -lt "$l_last" ]; }; then
    why="call order is not read<create<delete<read: first=$l_first create=$l_post delete=$l_del last=$l_last"
  elif ! [[ "$bs_name" =~ ^Staging\ Billing\ Probe\ [0-9]{8}T[0-9]{6}Z$ ]]; then why="the probe is not named 'Staging Billing Probe <UTC>': '${bs_name:-<none>}'"
  elif [ "$(command grep -cE '^BILLING-SYNC-READ phase=(before|after-add|after-remove) quantity=[0-9]+ ' <<<"$ST_OUT" || true)" -lt 3 ]; then why="the reads were not printed (want one line per read)"
  elif [ "$(_st_flow '^(POST|DELETE|PUT|PATCH) https://api\.stripe\.com/')" -ne 0 ]; then why="a Stripe write was made"
  elif [ "$(_st_leaks)" -ne 0 ] || [ "$(command grep -cE "$bl_leak_re" <<<"$ST_OUT$ST_ERR" || true)" -ne 0 ]; then why="a secret or customer marker was printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS sync-ok rc=0 quantity=1>2>1 order=read<create<delete<read probe_deleted=1"
  else _st_result "FAIL sync-ok $why"; fails=$((fails + 1)); fi

  # --- sync-no-subscription: no ACTIVE subscription -> refused, no shop written ----
  _st_bs_fx NONE 2
  _st_run "${bs_env[@]}" -- "${bs_args[@]}"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qF 'REFUSED [no-subscription]' <<<"$ST_ERR"; then why="stderr lacks 'REFUSED [no-subscription]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow '^GET https://api-staging\.olajay\.co\.uk/api/v1/billing/subscription -> 200$')" -ne 1 ]; then why="the refusal was not reached through the billing API read"
  elif [ "$(_st_flow '^(POST|DELETE|PUT|PATCH) https://api-staging\.olajay\.co\.uk/api/v1/shops')" -ne 0 ]; then why="a shop was written: $(command grep -E '/api/v1/shops' "$ast/logs/curl-flow.log" | tr '\n' '|')"
  elif command grep -q '^BILLING-SYNC ' <<<"$ST_OUT"; then why="a BILLING-SYNC line was printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS sync-no-subscription rc=1 tag=no-subscription shop_writes=0"
  else _st_result "FAIL sync-no-subscription $why"; fails=$((fails + 1)); fi

  # --- sync-cleanup-on-fail: Stripe never reaches n+1 -> rc 1, the probe still deleted
  _st_bs_fx ACTIVE ""
  _st_run "${bs_env[@]}" -- "${bs_args[@]}"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qE 'did not reach 2' <<<"$ST_ERR"; then why="stderr does not name the missed quantity: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow '^POST https://api-staging\.olajay\.co\.uk/api/v1/shops -> 201$')" -ne 1 ]; then why="the probe was not created (nothing to clean up; the arm would pass vacuously)"
  elif [ "$(_st_flow "^DELETE https://api-staging\.olajay\.co\.uk/api/v1/shops/$bs_probe -> 204$")" -ne 1 ]; then why="the probe shop was not deleted exactly once: $(command grep -E '/api/v1/shops' "$ast/logs/curl-flow.log" | tr '\n' '|')"
  elif ! command grep -qE "^PROBE-SHOP-DELETED: $bs_probe " <<<"$ST_OUT"; then why="no PROBE-SHOP-DELETED line"
  elif command grep -q '^BILLING-SYNC before=' <<<"$ST_OUT"; then why="a BILLING-SYNC summary was printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS sync-cleanup-on-fail rc=1 probe_deleted=1 summary=none"
  else _st_result "FAIL sync-cleanup-on-fail $why"; fails=$((fails + 1)); fi
  rm -f "$ast/resp/sub-sub_bl1-added.json"
  cp "$ast/resp/shops-before-billing.json" "$ast/resp/shops.json"

  # ===== PAY-03 (plan 30-28): Connect onboarding, the shop's settlement mode, and the
  # destination-charge money loop, every Stripe object read by id. The onboarding URL
  # (STUBOBlp1), the client secret (STUBCSlp1) and the customer fields (piizq7) carry
  # markers, so a print of any of them is detectable. The URL and the client secret
  # are composed at run time (the 29-18 rule): no key-shaped literal sits here.
  local lp_acct="acct_loop1" lp_oid="eeeeeeee-1111-4111-8111-000000000001" lp_cs lp_url lp_f
  local lp_leak_re='STUBOBlp1|STUBCSlp1|piizq7|PIIZQ7|connect\.stripe\.com'
  local ob_dir="$ast/onboard-out" ob_args=("${tool_args[@]}" --stage connect-onboard)
  local sm_args=("${tool_args[@]}" --stage settlement-mode) ml_args=("${tool_args[@]}" --stage money-loop --mode destination)
  local lp_env=(STUB_PODS_FILE="$ast/pods-prod.json" STAGING_SEED_POLL_SECONDS=0 STAGING_SEED_READY_DEADLINE_SECONDS=5)
  lp_cs="pi_loop1"; lp_cs="${lp_cs}_secret_STUBCSlp1"
  lp_url="https://connect.stripe.com/setup/e/${lp_acct}/STUBOBlp1"
  mkdir -p "$ob_dir"
  for lp_f in tenants shop pub-shop pub-products guest-order tracking orders-status-PENDING orders-status-CONFIRMED transition "detail-$lp_oid"; do
    rm -f "$ast/resp/$lp_f.pre-loop"; [ ! -f "$ast/resp/$lp_f.json" ] || cp "$ast/resp/$lp_f.json" "$ast/resp/$lp_f.pre-loop"
  done
  # _st_loop_pi STATUS KIND LIVEMODE — the order's intent; KIND dest (a destination
  # charge), plain (no transfer_data, no fee, no settlement_mode: Pitfall 6), wrongdest,
  # nofee or wrongmeta (one destination field wrong each).
  _st_loop_pi() {
    jq -n --arg st "$1" --arg k "$2" --argjson lm "$3" --arg t "$tid" '{id: "pi_loop1", object: "payment_intent", status: $st,
        livemode: $lm, amount: 950, currency: "gbp", latest_charge: (if $st == "succeeded" then "ch_loop1" else null end),
        application_fee_amount: (if $k == "plain" then null elif $k == "nofee" then 0 else 48 end),
        transfer_data: (if $k == "plain" then null elif $k == "wrongdest" then {destination: "acct_other9"} else {destination: "acct_loop1"} end),
        metadata: ({order_number: "ORD-STUB-L001", tenant_id: $t}
          + (if $k == "plain" then {} elif $k == "wrongmeta" then {settlement_mode: "SPLIT_DEPOSIT_VENDOR_MOR"}
             else {settlement_mode: "DESTINATION_CHARGE_PLATFORM_MOR"} end)),
        receipt_email: "piizq7@example.invalid"}'
  }
  # _st_loop_refund_fx LOCAL_STATUS REVERSAL(yes|no) — the vendor refund's 201, the
  # intent's refund list (this run's refund and another run's), the refund by id, the
  # transfer after the reversal, and the order detail whose refund row the loop polls.
  local lp_rid="ffffffff-1111-4111-8111-000000000001"
  _st_loop_refund_fx() {
    jq -n --arg r "$lp_rid" --arg o "$lp_oid" '{id: $r, orderId: $o, stripeRefundId: "re_loop1", amountPennies: 100,
        currency: "gbp", reason: "REQUESTED_BY_CUSTOMER", status: "pending"}' > "$ast/resp/refund-out.json"
    jq -n --arg r "$lp_rid" --arg rv "$2" '{object: "list", has_more: false, data: [
        {id: "re_other1", object: "refund", amount: 50, status: "succeeded", charge: "ch_loop1", payment_intent: "pi_loop1",
         metadata: {refund_id: "00000000-0000-4000-8000-000000000000"}},
        {id: "re_loop1", object: "refund", amount: 100, status: "succeeded", charge: "ch_loop1", payment_intent: "pi_loop1",
         transfer_reversal: (if $rv == "yes" then "trr_loop1" else null end), metadata: {refund_id: $r}}]}' > "$ast/resp/refund-list.json"
    jq -n --arg r "$lp_rid" --arg rv "$2" '{id: "re_loop1", object: "refund", amount: 100, currency: "gbp", status: "succeeded",
        charge: "ch_loop1", payment_intent: "pi_loop1", transfer_reversal: (if $rv == "yes" then "trr_loop1" else null end),
        metadata: {refund_id: $r}}' > "$ast/resp/re-re_loop1.json"
    jq '.amount_reversed = 95 | .reversals.data = [{id: "trr_loop1", object: "transfer_reversal", amount: 95,
        source_refund: "re_loop1", transfer: "tr_loop1"}]' "$ast/resp/tr-tr_loop1.json" > "$ast/resp/tr-tr_loop1-after.json"
    jq -n --arg o "$lp_oid" --arg r "$lp_rid" --arg ls "$1" '{id: $o, orderNumber: "ORD-STUB-L001", status: "CONFIRMED",
        paymentStatus: "CAPTURED", totalAmountPennies: 950, customerEmail: "piizq7@example.invalid", customerName: "Pii PIIZQ7",
        refunds: [{id: $r, orderId: $o, stripeRefundId: "re_loop1", amountPennies: 100, status: $ls}]}' > "$ast/resp/detail-$lp_oid.json"
  }
  # _st_loop_fx CONNECT_STATUS SHOP_MODE(or null) KIND LIVE(none|pi|charge|transfer)
  _st_loop_fx() {
    local lm_pi=false lm_ch=false lm_tr=false
    case "$4" in pi) lm_pi=true ;; charge) lm_ch=true ;; transfer) lm_tr=true ;; esac
    jq -n --arg t "$tid" --arg n "$TENANT_NAME" --arg c "$1" --arg a "$lp_acct" '[{id: $t, name: $n, status: "ACTIVE",
        contactEmail: "piizq7@example.invalid", stripeConnectStatus: $c, stripeAccountId: (if $c == "NONE" then null else $a end)}]' \
      > "$ast/resp/tenants.json"
    jq -n --arg s "$sid" --arg t "$tid" --arg slug "$SHOP_SLUG" --arg m "$2" '{id: $s, tenantId: $t, name: "Staging Test Kitchen",
        slug: $slug, email: "piizq7@example.invalid", published: true, offersDelivery: true, offersCollection: true,
        deliveryFeePennies: 250, freeDeliveryThresholdPennies: null, fulfilmentTimingMode: "IMMEDIATE_ON_DEMAND", depositBps: 10000,
        settlementPolicyMode: (if $m == "null" then null else $m end), openingSchedule: {mon: [{open: "00:00", close: "23:59"}]}}' \
      > "$ast/resp/shop.json"
    jq -n --arg a "$lp_acct" --arg u "$lp_url" '{stripeAccountId: $a, connectStatus: "PENDING", onboardingUrl: $u}' > "$ast/resp/connect-out.json"
    jq -n --arg slug "$SHOP_SLUG" '{slug: $slug, offersCollection: true, offersDelivery: true, deliveryFeePennies: 250, openNow: true}' \
      > "$ast/resp/pub-shop.json"
    jq -n --arg b "$p1" '{Mains: [{id: "aaaaaaaa-0000-4000-8000-000000000000", title: "Sold Out Stew", pricePennies: 700, inStock: false, allergenMask: 4},
        {id: $b, title: "Pelau", pricePennies: 950, inStock: true, allergenMask: 9}]}' > "$ast/resp/pub-products.json"
    jq -n --arg cs "$lp_cs" '{orderNumber: "ORD-STUB-L001", status: "PENDING", totalAmountPennies: 950, clientSecret: $cs,
        stripeAccountId: null}' > "$ast/resp/guest-order.json"
    jq -n '{orderNumber: "ORD-STUB-L001", status: "PENDING", paymentStatus: "CAPTURED"}' > "$ast/resp/tracking.json"
    _st_loop_pi requires_payment_method "$3" "$lm_pi" > "$ast/resp/pi-before.json"
    _st_loop_pi succeeded "$3" "$lm_pi" > "$ast/resp/pi-after.json"
    cp "$ast/resp/pi-after.json" "$ast/resp/pi-confirm.json"
    jq -n --argjson lm "$lm_ch" --arg k "$3" '{id: "ch_loop1", object: "charge", livemode: $lm, captured: true, paid: true,
        status: "succeeded", amount: 950, amount_captured: 950, amount_refunded: 0, payment_intent: "pi_loop1",
        transfer: (if $k == "plain" then null else "tr_loop1" end),
        transfer_data: (if $k == "plain" then null else {destination: "acct_loop1"} end),
        billing_details: {email: "piizq7@example.invalid", name: "Pii PIIZQ7"}, receipt_email: "piizq7@example.invalid"}' \
      > "$ast/resp/ch-ch_loop1.json"
    jq -n --argjson lm "$lm_tr" '{id: "tr_loop1", object: "transfer", livemode: $lm, amount: 902, currency: "gbp",
        destination: "acct_loop1", source_transaction: "ch_loop1", amount_reversed: 0, reversed: false,
        reversals: {object: "list", data: [], has_more: false}}' > "$ast/resp/tr-tr_loop1.json"
    jq -n --arg o "$lp_oid" --arg s "$sid" '{content: [{id: $o, shopId: $s, orderNumber: "ORD-STUB-L001", status: "PENDING",
        paymentStatus: "CAPTURED", paymentReference: "pi_loop1", totalAmountPennies: 950, customerEmail: "piizq7@example.invalid"}],
        totalElements: 1, totalPages: 1, number: 0, last: true}' > "$ast/resp/orders-status-PENDING.json"
    jq -n --arg o "$lp_oid" '{id: $o, orderNumber: "ORD-STUB-L001", status: "CONFIRMED", paymentStatus: "CAPTURED"}' > "$ast/resp/transition.json"
    _st_loop_refund_fx succeeded yes
  }
  _st_lp_leaks() { command grep -cE "$lp_leak_re" <<<"$ST_OUT$ST_ERR$(cat "$ast/logs/curl.log")" || true; }
  _st_order_posts() { _st_flow '^POST https://api-staging\.olajay\.co\.uk/api/v1/public/shops/staging-test-kitchen/orders '; }
  _st_refund_posts() { _st_flow '^POST https://api-staging\.olajay\.co\.uk/api/v1/orders/[^ ]+/refund '; }
  _st_accept_posts() { _st_flow '^POST https://api-staging\.olajay\.co\.uk/api/v1/orders/[^ ]+/confirm '; }
  _st_stripe_writes() { _st_flow '^(POST|DELETE|PUT|PATCH) https://api\.stripe\.com/'; }
  local re_opdel='^DELETE http://127\.0\.0\.1:18080/admin/realms/jtoye-staging/users/op-0001 -> 204$'
  local re_loop_any='^LOOP-(ORDER|CAPTURE|TRANSFER|REFUND) '

  # --- onboard-new: no account yet -> POST connect; the URL reaches only the mode-600 file
  _st_loop_fx NONE DESTINATION_CHARGE_PLATFORM_MOR dest none
  rm -f "$ob_dir/new.url"
  _st_run "${lp_env[@]}" -- "${ob_args[@]}" --link-out "$ob_dir/new.url"
  why=""
  local ob_post ob_del ob_mode
  ob_post=$(_st_line "^POST https://api-staging\.olajay\.co\.uk/api/v1/admin/tenants/$tid/stripe/connect -> 200$")
  ob_del=$(_st_line "$re_opdel")
  ob_mode=$(stat -c %a "$ob_dir/new.url" 2>/dev/null || echo none)
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(tail -n 1 <<<"$ST_OUT")" != "CONNECT-ONBOARD tenant=$tid account=$lp_acct status=PENDING" ]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  elif [ "$(_st_flow '/stripe/connect -> ')" -ne 1 ] || [ -z "$ob_post" ]; then why="the connect POST was not made exactly once to this tenant: $(command grep -F '/stripe/connect' "$ast/logs/curl-flow.log" | tr '\n' '|')"
  elif [ -z "$ob_del" ] || [ "$ob_del" -le "$ob_post" ]; then why="the operator was not deleted after the connect POST (post=$ob_post delete=$ob_del)"
  elif ! command grep -qF 'OPERATOR-DELETED: seed-operator-' <<<"$ST_OUT"; then why="the operator deletion was not printed"
  elif [ "$ob_mode" != 600 ]; then why="the --link-out file is mode $ob_mode (want 600)"
  elif [ "$(cat "$ob_dir/new.url")" != "$lp_url" ]; then why="the --link-out file does not hold exactly the onboarding URL"
  elif [ "$(_st_lp_leaks)" -ne 0 ]; then why="the onboarding URL or a customer field was printed or put in argv ($(_st_lp_leaks) hit(s))"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="a secret marker was printed or put in argv"
  fi
  if [ -z "$why" ]; then _st_result "PASS onboard-new rc=0 connect_posts=1 operator_deleted_after=1 file_mode=600 printed_url=0"
  else _st_result "FAIL onboard-new $why"; fails=$((fails + 1)); fi

  # --- onboard-enabled: the account is ENABLED -> no POST, no link, status printed ---
  _st_loop_fx ENABLED DESTINATION_CHARGE_PLATFORM_MOR dest none
  rm -f "$ob_dir/enabled.url"
  _st_run "${lp_env[@]}" -- "${ob_args[@]}" --link-out "$ob_dir/enabled.url"
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(tail -n 1 <<<"$ST_OUT")" != "CONNECT-ONBOARD tenant=$tid account=$lp_acct status=ENABLED" ]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  elif [ "$(_st_flow '^GET https://api-staging\.olajay\.co\.uk/api/v1/admin/tenants -> 200$')" -ne 1 ]; then why="the status was not read from the admin tenant list (non-vacuity)"
  elif [ "$(_st_flow '/stripe/connect -> ')" -ne 0 ]; then why="a connect POST was made for an ENABLED account"
  elif [ -e "$ob_dir/enabled.url" ]; then why="the --link-out file was created"
  elif [ "$(_st_flow "$re_opdel")" -ne 1 ]; then why="the operator was not deleted exactly once"
  elif [ "$(_st_lp_leaks)" -ne 0 ] || [ "$(_st_leaks)" -ne 0 ]; then why="a marker was printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS onboard-enabled rc=0 connect_posts=0 link_file=absent status=ENABLED"
  else _st_result "FAIL onboard-enabled $why"; fails=$((fails + 1)); fi

  # --- onboard-no-out: no --link-out -> usage error before any guard or call ------
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${ob_args[@]}"
  if why=$(_st_refusal 2 "USAGE ERROR: --stage connect-onboard needs --link-out" 0 0); then _st_result "PASS onboard-no-out rc=2 az_calls=0 curl_calls=0"
  else _st_result "FAIL onboard-no-out $why"; fails=$((fails + 1)); fi

  # --- mode-read: no --set -> one shop read, nothing written, the mode printed ------
  _st_loop_fx ENABLED DESTINATION_CHARGE_PLATFORM_MOR dest none
  _st_run "${lp_env[@]}" -- "${sm_args[@]}"
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(tail -n 1 <<<"$ST_OUT")" != "SETTLEMENT-MODE shop=staging-test-kitchen mode=DESTINATION_CHARGE_PLATFORM_MOR" ]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  elif [ "$(_st_flow "^GET https://api-staging\.olajay\.co\.uk/api/v1/shops/$sid -> 200$")" -ne 1 ]; then why="the shop was not read exactly once by id"
  elif [ "$(_st_non_get)" -ne 0 ]; then why="a write was made: $(command grep -vE '^GET ' "$ast/logs/curl-flow.log" | tr '\n' '|')"
  else
    _st_loop_fx ENABLED null dest none
    _st_run "${lp_env[@]}" -- "${sm_args[@]}"
    if [ "$ST_RC" -ne 0 ]; then why="null mode: rc=$ST_RC (want 0)"
    elif [ "$(tail -n 1 <<<"$ST_OUT")" != "SETTLEMENT-MODE shop=staging-test-kitchen mode=null" ]; then why="null mode: the last line is '$(tail -n 1 <<<"$ST_OUT")'"
    elif [ "$(_st_non_get)" -ne 0 ]; then why="null mode: a write was made"
    fi
  fi
  if [ -z "$why" ]; then _st_result "PASS mode-read rc=0 shop_gets=1 writes=0 modes=DESTINATION,null"
  else _st_result "FAIL mode-read $why"; fails=$((fails + 1)); fi

  # --- mode-set: GET the shop, PUT the same body with ONLY settlementPolicyMode changed,
  #     read it back; a server that drops the change is a failure, not a pass
  _st_loop_fx ENABLED DESTINATION_CHARGE_PLATFORM_MOR dest none
  _st_run "${lp_env[@]}" -- "${sm_args[@]}" --set DIRECT_CHARGE_VENDOR_MOR
  why=""
  local sm_put sm_g1 sm_p sm_g2
  sm_put=$(command grep -F '"settlementPolicyMode"' "$ast/logs/bodies.log" | tail -n 1 || true)
  sm_g1=$(_st_line "^GET https://api-staging\.olajay\.co\.uk/api/v1/shops/$sid -> 200$")
  sm_p=$(_st_line "^PUT https://api-staging\.olajay\.co\.uk/api/v1/shops/$sid -> 200$")
  sm_g2=$(command grep -nE "^GET https://api-staging\.olajay\.co\.uk/api/v1/shops/$sid -> 200$" "$ast/logs/curl-flow.log" | tail -n 1 | cut -d: -f1 || true)
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(tail -n 1 <<<"$ST_OUT")" != "SETTLEMENT-MODE shop=staging-test-kitchen mode=DIRECT_CHARGE_VENDOR_MOR previous=DESTINATION_CHARGE_PLATFORM_MOR" ]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  elif [ "$(_st_flow '^PUT ')" -ne 1 ] || [ "$(_st_non_get)" -ne 1 ]; then why="want exactly one write, the shop PUT: $(command grep -vE '^GET ' "$ast/logs/curl-flow.log" | tr '\n' '|')"
  elif [ -z "$sm_g1" ] || [ -z "$sm_p" ] || ! { [ "$sm_g1" -lt "$sm_p" ] && [ "$sm_p" -lt "$sm_g2" ]; }; then why="call order is not GET<PUT<GET (read-back): $sm_g1 $sm_p $sm_g2"
  elif [ "$(jq -r '.settlementPolicyMode' <<<"${sm_put:-null}" 2>/dev/null)" != DIRECT_CHARGE_VENDOR_MOR ]; then why="the PUT does not set DIRECT_CHARGE_VENDOR_MOR: ${sm_put:-<none>}"
  elif [ "$(jq -cS 'del(.settlementPolicyMode)' <<<"$sm_put")" != "$(jq -cS 'del(.settlementPolicyMode)' "$ast/resp/shop.json")" ]; then why="the PUT body is not the GET body with one field changed"
  else
    _st_run "${lp_env[@]}" STUB_SHOP_PUT_IGNORED=1 -- "${sm_args[@]}" --set DIRECT_CHARGE_VENDOR_MOR
    if [ "$ST_RC" -ne 1 ]; then why="dropped PUT: rc=$ST_RC (want 1)"
    elif ! command grep -qF 'read back' <<<"$ST_ERR"; then why="dropped PUT: the failure does not name the read-back: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif command grep -q '^SETTLEMENT-MODE ' <<<"$ST_OUT"; then why="dropped PUT: a SETTLEMENT-MODE line was printed"
    fi
  fi
  if [ -z "$why" ]; then _st_result "PASS mode-set rc=0 order=GET<PUT<GET put_body=get_body+mode dropped_put=rc1"
  else _st_result "FAIL mode-set $why"; fails=$((fails + 1)); fi

  # --- mode-set-invalid: a third mode, or --set on another stage -> usage error ------
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${sm_args[@]}" --set SPLIT_DEPOSIT_VENDOR_MOR
  if why=$(_st_refusal 2 "USAGE ERROR: --set must be DESTINATION_CHARGE_PLATFORM_MOR or DIRECT_CHARGE_VENDOR_MOR" 0 0); then
    _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${ml_args[@]}" --set DIRECT_CHARGE_VENDOR_MOR
    why=$(_st_refusal 2 "USAGE ERROR: --set is taken by --stage settlement-mode only" 0 0) || true
  fi
  if [ -z "$why" ]; then _st_result "PASS mode-set-invalid rc=2 split_deposit=refused set_elsewhere=refused curl_calls=0"
  else _st_result "FAIL mode-set-invalid $why"; fails=$((fails + 1)); fi

  # --- loop-not-enabled: the tenant's account is PENDING -> refused before any write ---
  _st_loop_fx PENDING DESTINATION_CHARGE_PLATFORM_MOR dest none
  _st_run "${lp_env[@]}" -- "${ml_args[@]}"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qF 'REFUSED [connect-status]' <<<"$ST_ERR"; then why="stderr lacks 'REFUSED [connect-status]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow '^GET https://api-staging\.olajay\.co\.uk/api/v1/admin/tenants -> 200$')" -ne 1 ]; then why="the refusal was not reached through the admin tenant read"
  elif [ "$(_st_order_posts)" -ne 0 ]; then why="an order was placed: $(_st_order_posts)"
  elif [ "$(_st_stripe_writes)" -ne 0 ]; then why="a Stripe write was made"
  elif [ "$(_st_flow "$re_opdel")" -ne 1 ]; then why="the operator was not deleted exactly once"
  elif command grep -qE "$re_loop_any" <<<"$ST_OUT"; then why="a LOOP verdict was printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS loop-not-enabled rc=1 tag=connect-status order_posts=0 stripe_writes=0"
  else _st_result "FAIL loop-not-enabled $why"; fails=$((fails + 1)); fi

  # --- loop-mode-mismatch: the shop is DIRECT, the loop asked for destination -> refused
  _st_loop_fx ENABLED DIRECT_CHARGE_VENDOR_MOR dest none
  _st_run "${lp_env[@]}" -- "${ml_args[@]}"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qF 'REFUSED [mode-mismatch]' <<<"$ST_ERR"; then why="stderr lacks 'REFUSED [mode-mismatch]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow "^GET https://api-staging\.olajay\.co\.uk/api/v1/shops/$sid -> 200$")" -lt 1 ]; then why="the refusal was not reached through the shop read"
  elif [ "$(_st_order_posts)" -ne 0 ]; then why="an order was placed"
  elif [ "$(_st_stripe_writes)" -ne 0 ]; then why="a Stripe write was made"
  fi
  if [ -z "$why" ]; then _st_result "PASS loop-mode-mismatch rc=1 tag=mode-mismatch order_posts=0"
  else _st_result "FAIL loop-mode-mismatch $why"; fails=$((fails + 1)); fi

  # --- loop-plain-charge: the intent has no transfer_data -> refused, nothing accepted
  #     or refunded, no verdict claims a destination charge (Pitfall 6)
  _st_loop_fx ENABLED DESTINATION_CHARGE_PLATFORM_MOR plain none
  _st_run "${lp_env[@]}" -- "${ml_args[@]}"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qF 'REFUSED [plain-charge]' <<<"$ST_ERR"; then why="stderr lacks 'REFUSED [plain-charge]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_order_posts)" -ne 1 ]; then why="order posts=$(_st_order_posts) (want 1: the refusal must come from the paid intent)"
  elif [ "$(_st_refund_posts)" -ne 0 ] || [ "$(_st_accept_posts)" -ne 0 ]; then why="the order was accepted or refunded after a plain charge"
  elif command grep -qE "$re_loop_any" <<<"$ST_OUT"; then why="a LOOP verdict was printed: $(command grep -E "$re_loop_any" <<<"$ST_OUT" | head -n 1)"
  fi
  if [ -z "$why" ]; then _st_result "PASS loop-plain-charge rc=1 tag=plain-charge refund_posts=0 verdicts=0"
  else _st_result "FAIL loop-plain-charge $why"; fails=$((fails + 1)); fi

  # --- loop-intent-fields: a wrong destination, no fee, the wrong settlement_mode ------
  why=""
  local lk lk_want
  for lk in wrongdest nofee wrongmeta; do
    case "$lk" in wrongdest) lk_want='transfer_data.destination' ;; nofee) lk_want='application_fee_amount' ;; wrongmeta) lk_want='metadata.settlement_mode' ;; esac
    _st_loop_fx ENABLED DESTINATION_CHARGE_PLATFORM_MOR "$lk" none
    _st_run "${lp_env[@]}" -- "${ml_args[@]}"
    if [ "$ST_RC" -ne 1 ]; then why="$lk: rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif ! command grep -qF "$lk_want" <<<"$ST_ERR"; then why="$lk: the failure does not name $lk_want: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(_st_refund_posts)" -ne 0 ] || command grep -qE "$re_loop_any" <<<"$ST_OUT"; then why="$lk: the loop went on"
    fi
    [ -z "$why" ] || break
  done
  if [ -z "$why" ]; then _st_result "PASS loop-intent-fields rc=1 named=transfer_data.destination,application_fee_amount,metadata.settlement_mode"
  else _st_result "FAIL loop-intent-fields $why"; fails=$((fails + 1)); fi

  # --- loop-live: the intent, the charge or the transfer reads livemode true -> refused
  why=""
  local lo
  for lo in pi charge transfer; do
    _st_loop_fx ENABLED DESTINATION_CHARGE_PLATFORM_MOR dest "$lo"
    _st_run "${lp_env[@]}" -- "${ml_args[@]}"
    if [ "$ST_RC" -ne 1 ]; then why="$lo: rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif ! command grep -qF 'REFUSED [stripe-mode]' <<<"$ST_ERR"; then why="$lo: stderr lacks 'REFUSED [stripe-mode]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(_st_refund_posts)" -ne 0 ] || command grep -q '^LOOP-TRANSFER ' <<<"$ST_OUT"; then why="$lo: the loop went on past a live object"
    elif [ "$lo" = pi ] && [ "$(_st_confirms)" -ne 0 ]; then why="pi: a live intent was confirmed"
    fi
    [ -z "$why" ] || break
  done
  if [ -z "$why" ]; then _st_result "PASS loop-live rc=1 tag=stripe-mode objects=intent,charge,transfer"
  else _st_result "FAIL loop-live $why"; fails=$((fails + 1)); fi

  # --- loop-destination-ok: preflight, order, bypassPending confirm, CAPTURED, then the
  #     intent, its charge and its transfer read by id; every key from one run id
  _st_loop_fx ENABLED DESTINATION_CHARGE_PLATFORM_MOR dest none
  _st_run "${lp_env[@]}" -- "${ml_args[@]}"
  why=""
  local ml_body ml_keys ml_stamp l_opdel l_order l_conf l_pi l_ch l_tr
  ml_body=$(command grep -F '"customerEmail"' "$ast/logs/bodies.log" | tail -n 1 || true)
  ml_keys=$(command grep -oE 'Idempotency-Key: loop-[0-9]{8}T[0-9]{6}Z-[a-z]+' "$ast/logs/curl.log" | LC_ALL=C sort -u || true)
  ml_stamp=$(head -n 1 <<<"$ml_keys" | command sed -E 's/^Idempotency-Key: (loop-[0-9]{8}T[0-9]{6}Z)-.*/\1/')
  l_opdel=$(_st_line "$re_opdel")
  l_order=$(_st_line '^POST https://api-staging\.olajay\.co\.uk/api/v1/public/shops/staging-test-kitchen/orders -> 201$')
  l_conf=$(_st_line '^POST https://api\.stripe\.com/v1/payment_intents/pi_loop1/confirm -> 200$')
  l_pi=$(command grep -nE '^GET https://api\.stripe\.com/v1/payment_intents/pi_loop1 -> 200$' "$ast/logs/curl-flow.log" | tail -n 1 | cut -d: -f1 || true)
  l_ch=$(_st_line '^GET https://api\.stripe\.com/v1/charges/ch_loop1 -> 200$')
  l_tr=$(_st_line '^GET https://api\.stripe\.com/v1/transfers/tr_loop1 -> 200$')
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qxF 'LOOP-ORDER mode=destination order=ORD-STUB-L001 intent=pi_loop1 amount=950 application_fee=48' <<<"$ST_OUT"; then why="no LOOP-ORDER verdict: $(command grep '^LOOP-' <<<"$ST_OUT" | tr '\n' '|')"
  elif ! command grep -qxF 'LOOP-CAPTURE charge=ch_loop1 captured=true amount=950' <<<"$ST_OUT"; then why="no LOOP-CAPTURE verdict: $(command grep '^LOOP-' <<<"$ST_OUT" | tr '\n' '|')"
  elif ! command grep -qxF "LOOP-TRANSFER transfer=tr_loop1 destination=$lp_acct amount=902" <<<"$ST_OUT"; then why="no LOOP-TRANSFER verdict: $(command grep '^LOOP-' <<<"$ST_OUT" | tr '\n' '|')"
  elif [ -z "$l_opdel" ] || [ -z "$l_order" ] || [ "$l_opdel" -ge "$l_order" ]; then why="the operator was not deleted before the order (delete=$l_opdel order=$l_order)"
  elif [ -z "$l_conf" ] || [ -z "$l_pi" ] || [ -z "$l_ch" ] || [ -z "$l_tr" ] \
       || ! { [ "$l_order" -lt "$l_conf" ] && [ "$l_conf" -lt "$l_pi" ] && [ "$l_pi" -lt "$l_ch" ] && [ "$l_ch" -lt "$l_tr" ]; }; then
    why="call order is not order<confirm<intent<charge<transfer: $l_order $l_conf $l_pi $l_ch $l_tr"
  elif [ "$(_st_confirms)" -ne 1 ] || ! command grep -qF 'payment_method=pm_card_bypassPending' "$ast/logs/curl.log" \
       || ! command grep -qF 'return_url=https://app-staging.olajay.co.uk/order-complete' "$ast/logs/curl.log"; then
    why="the confirm is not one call with payment_method=pm_card_bypassPending and the return_url"
  elif [ "$(_st_stripe_writes)" -ne 1 ]; then why="Stripe writes=$(_st_stripe_writes) (want 1, the confirm)"
  elif [ "$(command grep -cxE "Idempotency-Key: ${ml_stamp:-none}-(order|confirm)" <<<"$ml_keys" || true)" -ne 2 ]; then why="the order and the confirm do not carry <run id>-order and <run id>-confirm of one run id: $(tr '\n' '|' <<<"$ml_keys")"
  elif [ "$(jq -c '{f: .fulfilmentType, i: .items, a: .acknowledgedAllergenMask, t: .expectedTotalPennies}' <<<"${ml_body:-null}" 2>/dev/null)" \
       != "$(jq -cn --arg p "$p1" '{f: "COLLECTION", i: [{productId: $p, quantity: 1, expectedUnitPricePennies: 950}], a: 9, t: 950}')" ]; then
    why="the order body is not the first available dish at its shown price: ${ml_body:-<none>}"
  elif [ "$(_st_lp_leaks)" -ne 0 ] || [ "$(_st_leaks)" -ne 0 ]; then why="the client secret, a key or a customer field was printed or put in argv"
  fi
  # The refund half (Task 2): the order accepted, a PARTIAL refund through the vendor
  # API with <run id>-refund, Stripe's refund read by id with its transfer_reversal, its
  # charge read for livemode, the transfer's reversal, and the local row equal to Stripe's.
  local ml_rbody l_acc l_rf l_rl l_rid l_ch2 l_tr2 l_det
  ml_rbody=$(command grep -F '"amountPennies"' "$ast/logs/bodies.log" | tail -n 1 || true)
  l_acc=$(_st_line "^POST https://api-staging\.olajay\.co\.uk/api/v1/orders/$lp_oid/confirm -> 200$")
  l_rf=$(_st_line "^POST https://api-staging\.olajay\.co\.uk/api/v1/orders/$lp_oid/refund -> 201$")
  l_rl=$(_st_line '^GET https://api\.stripe\.com/v1/refunds\?payment_intent=pi_loop1(&[^ ]*)? -> 200$')
  l_rid=$(_st_line '^GET https://api\.stripe\.com/v1/refunds/re_loop1 -> 200$')
  l_ch2=$(command grep -nE '^GET https://api\.stripe\.com/v1/charges/ch_loop1 -> 200$' "$ast/logs/curl-flow.log" | tail -n 1 | cut -d: -f1 || true)
  l_tr2=$(command grep -nE '^GET https://api\.stripe\.com/v1/transfers/tr_loop1 -> 200$' "$ast/logs/curl-flow.log" | tail -n 1 | cut -d: -f1 || true)
  l_det=$(command grep -nE "^GET https://api-staging\.olajay\.co\.uk/api/v1/orders/$lp_oid/detail -> 200$" "$ast/logs/curl-flow.log" | tail -n 1 | cut -d: -f1 || true)
  if [ -n "$why" ]; then :
  elif ! command grep -qxF 'LOOP-ACCEPT order=ORD-STUB-L001 status=CONFIRMED' <<<"$ST_OUT"; then why="no LOOP-ACCEPT verdict: $(command grep '^LOOP-' <<<"$ST_OUT" | tr '\n' '|')"
  elif ! command grep -qxF 'LOOP-REFUND refund=re_loop1 amount=100 status=succeeded transfer_reversal=trr_loop1 local_status=succeeded' <<<"$ST_OUT"; then
    why="no LOOP-REFUND verdict: $(command grep '^LOOP-' <<<"$ST_OUT" | tr '\n' '|')"
  elif [ "$(_st_accept_posts)" -ne 1 ] || [ "$(_st_refund_posts)" -ne 1 ]; then why="want one accept and one refund: accept=$(_st_accept_posts) refund=$(_st_refund_posts)"
  elif [ -z "$l_acc" ] || [ -z "$l_rf" ] || [ -z "$l_rl" ] || [ -z "$l_rid" ] || [ -z "$l_det" ] \
       || ! { [ "$l_tr" -lt "$l_acc" ] && [ "$l_acc" -lt "$l_rf" ] && [ "$l_rf" -lt "$l_rl" ] && [ "$l_rl" -lt "$l_rid" ] \
              && [ "$l_rid" -lt "$l_ch2" ] && [ "$l_ch2" -lt "$l_tr2" ] && [ "$l_tr2" -lt "$l_det" ]; }; then
    why="call order is not transfer<accept<refund<list<refund-by-id<charge<transfer<detail: $l_tr $l_acc $l_rf $l_rl $l_rid $l_ch2 $l_tr2 $l_det"
  elif [ "$(jq -cS . <<<"${ml_rbody:-null}" 2>/dev/null | jq -c '{amountPennies, reason}' 2>/dev/null)" != '{"amountPennies":100,"reason":"REQUESTED_BY_CUSTOMER"}' ]; then
    why="the refund body is not {amountPennies: 100, reason: REQUESTED_BY_CUSTOMER}: ${ml_rbody:-<none>}"
  elif [ "$(command grep -cxE "Idempotency-Key: ${ml_stamp:-none}-(order|confirm|refund)" <<<"$ml_keys" || true)" -ne 3 ]; then
    why="the order, confirm and refund do not carry one run id's keys: $(tr '\n' '|' <<<"$ml_keys")"
  elif [ "$(_st_stripe_writes)" -ne 1 ]; then why="Stripe writes=$(_st_stripe_writes) (want 1, the confirm: the refund goes through the vendor API)"
  else
    # A refund that did not reverse the transfer is a failure, never a pass.
    _st_loop_fx ENABLED DESTINATION_CHARGE_PLATFORM_MOR dest none
    _st_loop_refund_fx succeeded no
    _st_run "${lp_env[@]}" -- "${ml_args[@]}"
    if [ "$ST_RC" -ne 1 ]; then why="no reversal: rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif ! command grep -qF 'transfer_reversal' <<<"$ST_ERR"; then why="no reversal: the failure does not name transfer_reversal: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif command grep -q '^LOOP-REFUND ' <<<"$ST_OUT"; then why="no reversal: a LOOP-REFUND verdict was printed"
    fi
  fi
  if [ -z "$why" ]; then _st_result "PASS loop-destination-ok rc=0 order=preflight<order<confirm<intent<charge<transfer<accept<refund<reversal<local run_id=one no_reversal=rc1"
  else _st_result "FAIL loop-destination-ok $why"; fails=$((fails + 1)); fi

  # --- loop-refund-full: a refund that is not strictly partial -> usage error before
  #     any write (the full-refund edge case can never pass for the loop's proof)
  why=""
  local rfa
  for rfa in 950 2000; do
    _st_loop_fx ENABLED DESTINATION_CHARGE_PLATFORM_MOR dest none
    _st_run "${lp_env[@]}" -- "${ml_args[@]}" --refund-pennies "$rfa"
    if [ "$ST_RC" -ne 2 ]; then why="$rfa: rc=$ST_RC (want 2): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif ! command grep -qF "USAGE ERROR: --refund-pennies $rfa is not less than the order total 950p" <<<"$ST_ERR"; then why="$rfa: stderr does not name the bound: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(_st_flow '/api/v1/public/shops/staging-test-kitchen/products -> 200$')" -ne 1 ]; then why="$rfa: the bound was not taken from the menu read (non-vacuity)"
    elif [ "$(_st_non_get)" -ne 0 ] || [ "$(_st_lines az)" -ne 0 ]; then why="$rfa: something was written or read from Key Vault before the refusal: $(command grep -vE '^GET ' "$ast/logs/curl-flow.log" | tr '\n' '|')"
    fi
    [ -z "$why" ] || break
  done
  if [ -z "$why" ]; then
    _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${ml_args[@]}" --refund-pennies 0
    why=$(_st_refusal 2 "USAGE ERROR: --refund-pennies must be a positive whole number of pennies" 0 0) || true
  fi
  if [ -z "$why" ]; then _st_result "PASS loop-refund-full rc=2 equal=refused above=refused zero=refused writes=0"
  else _st_result "FAIL loop-refund-full $why"; fails=$((fails + 1)); fi

  # --- loop-refund-mismatch: Stripe says succeeded, the local row stays pending past the
  #     deadline -> rc 1 naming both statuses, no LOOP-REFUND verdict
  _st_loop_fx ENABLED DESTINATION_CHARGE_PLATFORM_MOR dest none
  _st_loop_refund_fx pending yes
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" STAGING_SEED_POLL_SECONDS=1 STAGING_SEED_READY_DEADLINE_SECONDS=5 \
    STAGING_SEED_REFUND_DEADLINE_SECONDS=2 -- "${ml_args[@]}"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qF "'pending'" <<<"$ST_ERR" || ! command grep -qF "'succeeded'" <<<"$ST_ERR"; then why="the failure does not name both statuses: $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow "^GET https://api-staging\.olajay\.co\.uk/api/v1/orders/$lp_oid/detail -> 200$")" -lt 2 ]; then why="the local row was not polled (detail reads=$(_st_flow "/orders/$lp_oid/detail -> "))"
  elif command grep -q '^LOOP-REFUND ' <<<"$ST_OUT"; then why="a LOOP-REFUND verdict was printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS loop-refund-mismatch rc=1 local=pending stripe=succeeded verdict=none"
  else _st_result "FAIL loop-refund-mismatch $why"; fails=$((fails + 1)); fi

  # --- loop-evidence-clean: --evidence keeps what the file held, adds .modes.destination
  #     (ids, amounts, statuses, livemode false; jq -S, mode 600) and nothing secret or
  #     personal; a file that is not a JSON object is refused and left untouched
  local ev_f="$ob_dir/evidence.json" ev_bad="$ob_dir/evidence-bad.json" ev_hits ev_mode
  jq -n '{kept: {note: "an earlier entry"}}' > "$ev_f"
  _st_loop_fx ENABLED DESTINATION_CHARGE_PLATFORM_MOR dest none
  _st_run "${lp_env[@]}" -- "${ml_args[@]}" --evidence "$ev_f"
  why=""
  ev_hits=$(command grep -ciE 'sk_|rk_|whsec_|client_secret|_secret_|https?://|@|STUBCS|STUBOB|piizq7' "$ev_f" || true)
  ev_mode=$(stat -c %a "$ev_f" 2>/dev/null || echo none)
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! jq -e '.modes.destination' "$ev_f" >/dev/null 2>&1; then why="no .modes.destination entry was written (the arm would pass vacuously)"
  elif [ "$(jq -c '.kept' "$ev_f")" != '{"note":"an earlier entry"}' ]; then why="the file's earlier content was not kept"
  elif [ "$(jq -c '.modes.destination | {order, intent, charge, transfer, refund, transfer_reversal, account, livemode}' "$ev_f")" \
       != '{"order":"ORD-STUB-L001","intent":"pi_loop1","charge":"ch_loop1","transfer":"tr_loop1","refund":"re_loop1","transfer_reversal":"trr_loop1","account":"acct_loop1","livemode":false}' ]; then
    why="the entry's ids are not the loop's: $(jq -c '.modes.destination' "$ev_f" | head -c 300)"
  elif [ "$(jq -c '.modes.destination.amounts' "$ev_f")" != "$(jq -cS -n '{captured: 950, application_fee: 48, transfer: 902, refund: 100, transfer_reversal: 95}')" ] \
       || [ "$(jq -r '.modes.destination.statuses.refund + "/" + .modes.destination.statuses.local_refund' "$ev_f")" != succeeded/succeeded ]; then
    why="the amounts or statuses are not the loop's: $(jq -c '.modes.destination | {amounts, statuses}' "$ev_f")"
  elif [ "$ev_hits" -ne 0 ]; then why="the evidence holds $ev_hits key, secret, URL, e-mail or marker line(s)"
  elif [ "$(jq -S . "$ev_f")" != "$(cat "$ev_f")" ]; then why="the file is not written with jq -S"
  elif [ "$ev_mode" != 600 ]; then why="the evidence file is mode $ev_mode (want 600)"
  elif ! command grep -qxF "LOOP-EVIDENCE file=$ev_f mode=destination" <<<"$ST_OUT"; then why="no LOOP-EVIDENCE line"
  else
    printf '[1, 2]\n' > "$ev_bad"
    _st_loop_fx ENABLED DESTINATION_CHARGE_PLATFORM_MOR dest none
    _st_run "${lp_env[@]}" -- "${ml_args[@]}" --evidence "$ev_bad"
    if [ "$ST_RC" -ne 1 ]; then why="non-object evidence: rc=$ST_RC (want 1)"
    elif [ "$(cat "$ev_bad")" != '[1, 2]' ]; then why="non-object evidence: the file was overwritten"
    elif ! command grep -qF 'not a JSON object' <<<"$ST_ERR"; then why="non-object evidence: the failure does not say why: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    fi
  fi
  if [ -z "$why" ]; then _st_result "PASS loop-evidence-clean rc=0 entry=modes.destination kept=1 secret_url_email_hits=0 sorted=1 mode=600 non_object=refused"
  else _st_result "FAIL loop-evidence-clean $why"; fails=$((fails + 1)); fi

  # PAY-03 direct mode (plan 30-29): the order's intent, its charge and its refund live
  # on the connected account, so every Stripe call must carry Stripe-Account. With
  # STUB_CONNECT_ACCOUNT the stub answers resource_missing (Stripe's own answer) to any
  # call without it and logs each call's header in stripe-acct-flow.log. The direct
  # client secret is composed at run time and carries the STUBCSlp1 marker.
  local md_args=("${tool_args[@]}" --stage money-loop --mode direct) md_cs
  local md_env=("${lp_env[@]}" STUB_CONNECT_ACCOUNT="$lp_acct")
  md_cs="pi_loopd1"; md_cs="${md_cs}_secret_STUBCSlp1d"
  # _st_loopd_pi STATUS — the direct-charge intent: no transfer_data, the fee and the
  # settlement mode on the intent itself.
  _st_loopd_pi() {
    jq -n --arg st "$1" --arg t "$tid" '{id: "pi_loopd1", object: "payment_intent", status: $st, livemode: false,
        amount: 950, currency: "gbp", latest_charge: (if $st == "succeeded" then "ch_loopd1" else null end),
        application_fee_amount: 48, transfer_data: null,
        metadata: {order_number: "ORD-STUB-L001", tenant_id: $t, settlement_mode: "DIRECT_CHARGE_VENDOR_MOR"},
        receipt_email: "piizq7@example.invalid"}'
  }
  # _st_loopd_fx LOCAL_STATUS [SHOP_MODE] [ORDER_ACCOUNT] — the direct loop's fixtures
  # (ORDER_ACCOUNT "" = the 201 names no connected account: a platform intent).
  _st_loopd_fx() {
    _st_loop_fx ENABLED "${2:-DIRECT_CHARGE_VENDOR_MOR}" dest none
    jq -n --arg cs "$md_cs" --arg a "${3-$lp_acct}" '{orderNumber: "ORD-STUB-L001", status: "PENDING", totalAmountPennies: 950,
        clientSecret: $cs, stripeAccountId: (if $a == "" then null else $a end)}' > "$ast/resp/guest-order.json"
    _st_loopd_pi requires_payment_method > "$ast/resp/pi-before.json"
    _st_loopd_pi succeeded > "$ast/resp/pi-after.json"
    cp "$ast/resp/pi-after.json" "$ast/resp/pi-confirm.json"
    jq -n '{id: "ch_loopd1", object: "charge", livemode: false, captured: true, paid: true, status: "succeeded", amount: 950,
        amount_captured: 950, amount_refunded: 0, payment_intent: "pi_loopd1", application_fee_amount: 48, transfer: null,
        billing_details: {email: "piizq7@example.invalid", name: "Pii PIIZQ7"}, receipt_email: "piizq7@example.invalid"}' \
      > "$ast/resp/ch-ch_loopd1.json"
    jq '.content[0].paymentReference = "pi_loopd1"' "$ast/resp/orders-status-PENDING.json" > "$ast/resp/orders-status-PENDING.next"
    mv "$ast/resp/orders-status-PENDING.next" "$ast/resp/orders-status-PENDING.json"
    jq -n --arg r "$lp_rid" --arg o "$lp_oid" '{id: $r, orderId: $o, stripeRefundId: "re_loopd1", amountPennies: 100,
        currency: "gbp", reason: "REQUESTED_BY_CUSTOMER", status: "pending"}' > "$ast/resp/refund-out.json"
    jq -n --arg r "$lp_rid" '{object: "list", has_more: false, data: [
        {id: "re_otherd1", object: "refund", amount: 50, status: "succeeded", charge: "ch_loopd1", payment_intent: "pi_loopd1",
         metadata: {refund_id: "00000000-0000-4000-8000-000000000000"}},
        {id: "re_loopd1", object: "refund", amount: 100, status: "succeeded", charge: "ch_loopd1", payment_intent: "pi_loopd1",
         transfer_reversal: null, metadata: {refund_id: $r}}]}' > "$ast/resp/refund-list.json"
    jq -n --arg r "$lp_rid" '{id: "re_loopd1", object: "refund", amount: 100, currency: "gbp", status: "succeeded",
        charge: "ch_loopd1", payment_intent: "pi_loopd1", transfer_reversal: null, metadata: {refund_id: $r}}' > "$ast/resp/re-re_loopd1.json"
    jq -n --arg o "$lp_oid" --arg r "$lp_rid" --arg ls "$1" '{id: $o, orderNumber: "ORD-STUB-L001", status: "CONFIRMED",
        paymentStatus: "CAPTURED", totalAmountPennies: 950, customerEmail: "piizq7@example.invalid", customerName: "Pii PIIZQ7",
        refunds: [{id: $r, orderId: $o, stripeRefundId: "re_loopd1", amountPennies: 100, status: $ls}]}' > "$ast/resp/detail-$lp_oid.json"
  }
  _st_acct_flow() { if [ -s "$ast/logs/stripe-acct-flow.log" ]; then command grep -c '' "$ast/logs/stripe-acct-flow.log"; else echo 0; fi; }
  _st_acct_bad() { if [ -s "$ast/logs/stripe-acct-flow.log" ]; then command grep -vcE " acct=$lp_acct key=" "$ast/logs/stripe-acct-flow.log" || true; else echo 0; fi; }
  _st_last() { command grep -nE "$1" "$ast/logs/curl-flow.log" | tail -n 1 | cut -d: -f1 || true; }
  local lp_steps_dest='["order","confirm","captured","intent","charge","transfer","accept","refund","refund-read","refund-charge","transfer-reversal","refund-local"]'
  local lp_steps_direct='["order","confirm","captured","intent","charge","accept","refund","refund-read","refund-charge","refund-local"]'

  # --- direct-mode-mismatch: --mode direct on a DESTINATION or null shop -> refused before
  #     any write or Stripe call; a direct shop whose order comes back as a platform intent
  #     -> refused after the one order, never confirmed
  why=""
  local mm
  for mm in DESTINATION_CHARGE_PLATFORM_MOR null; do
    _st_loopd_fx succeeded "$mm"
    _st_run "${md_env[@]}" -- "${md_args[@]}"
    if [ "$ST_RC" -ne 1 ]; then why="$mm: rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif ! command grep -qF 'REFUSED [mode-mismatch]' <<<"$ST_ERR"; then why="$mm: stderr lacks 'REFUSED [mode-mismatch]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif ! command grep -qF 'needs DIRECT_CHARGE_VENDOR_MOR' <<<"$ST_ERR"; then why="$mm: the refusal does not name the mode --mode direct needs"
    elif [ "$(_st_flow "^GET https://api-staging\.olajay\.co\.uk/api/v1/shops/$sid -> 200$")" -lt 1 ]; then why="$mm: the refusal was not reached through the shop read"
    elif [ "$(_st_order_posts)" -ne 0 ]; then why="$mm: an order was placed"
    elif [ "$(_st_acct_flow)" -ne 0 ]; then why="$mm: a Stripe call was made: $(tr '\n' '|' < "$ast/logs/stripe-acct-flow.log")"
    fi
    [ -z "$why" ] || break
  done
  if [ -z "$why" ]; then
    _st_loopd_fx succeeded DIRECT_CHARGE_VENDOR_MOR ""
    _st_run "${md_env[@]}" -- "${md_args[@]}"
    if [ "$ST_RC" -ne 1 ]; then why="platform intent: rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif ! command grep -qF 'REFUSED [mode-mismatch]' <<<"$ST_ERR"; then why="platform intent: stderr lacks 'REFUSED [mode-mismatch]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(_st_order_posts)" -ne 1 ]; then why="platform intent: order posts=$(_st_order_posts) (want 1)"
    elif [ "$(_st_confirms)" -ne 0 ] || [ "$(_st_refund_posts)" -ne 0 ]; then why="platform intent: the intent was confirmed or refunded"
    elif command grep -qE "$re_loop_any" <<<"$ST_OUT"; then why="platform intent: a LOOP verdict was printed"
    fi
  fi
  if [ -z "$why" ]; then _st_result "PASS direct-mode-mismatch rc=1 tag=mode-mismatch shop_modes=DESTINATION,null order_posts=0 platform_intent=refused"
  else _st_result "FAIL direct-mode-mismatch $why"; fails=$((fails + 1)); fi

  # --- direct-ok: preflight (ENABLED, DIRECT), the order on the account, bypassPending
  #     confirm, CAPTURED, the intent and its charge read by id, accept, a partial refund,
  #     the refund and its charge read by id, the local row: EVERY Stripe call carries
  #     Stripe-Account (the stub refuses one that does not); .modes.direct is added to an
  #     evidence file that already holds .modes.destination, which is kept as it was
  local evd="$ob_dir/evidence-direct.json" evd_dest="" evd_hits
  rm -f "$evd"
  _st_loop_fx ENABLED DESTINATION_CHARGE_PLATFORM_MOR dest none
  _st_run "${lp_env[@]}" -- "${ml_args[@]}" --evidence "$evd"
  [ "$ST_RC" -ne 0 ] || evd_dest=$(jq -c '.modes.destination' "$evd" 2>/dev/null || true)
  _st_loopd_fx succeeded
  _st_run "${md_env[@]}" -- "${md_args[@]}" --evidence "$evd"
  why=""
  local md_keys md_stamp d_order d_conf d_pi d_ch d_acc d_rf d_rl d_rid d_ch2 d_det
  md_keys=$(command grep -oE 'Idempotency-Key: loop-[0-9]{8}T[0-9]{6}Z-[a-z]+' "$ast/logs/curl.log" | LC_ALL=C sort -u || true)
  md_stamp=$(head -n 1 <<<"$md_keys" | command sed -E 's/^Idempotency-Key: (loop-[0-9]{8}T[0-9]{6}Z)-.*/\1/')
  d_order=$(_st_line '^POST https://api-staging\.olajay\.co\.uk/api/v1/public/shops/staging-test-kitchen/orders -> 201$')
  d_conf=$(_st_line '^POST https://api\.stripe\.com/v1/payment_intents/pi_loopd1/confirm -> 200$')
  d_pi=$(_st_last '^GET https://api\.stripe\.com/v1/payment_intents/pi_loopd1 -> 200$')
  d_ch=$(_st_line '^GET https://api\.stripe\.com/v1/charges/ch_loopd1 -> 200$')
  d_acc=$(_st_line "^POST https://api-staging\.olajay\.co\.uk/api/v1/orders/$lp_oid/confirm -> 200$")
  d_rf=$(_st_line "^POST https://api-staging\.olajay\.co\.uk/api/v1/orders/$lp_oid/refund -> 201$")
  d_rl=$(_st_line '^GET https://api\.stripe\.com/v1/refunds\?payment_intent=pi_loopd1(&[^ ]*)? -> 200$')
  d_rid=$(_st_line '^GET https://api\.stripe\.com/v1/refunds/re_loopd1 -> 200$')
  d_ch2=$(_st_last '^GET https://api\.stripe\.com/v1/charges/ch_loopd1 -> 200$')
  d_det=$(_st_last "^GET https://api-staging\.olajay\.co\.uk/api/v1/orders/$lp_oid/detail -> 200$")
  evd_hits=$(command grep -ciE 'sk_|rk_|whsec_|client_secret|_secret_|https?://|@|STUBCS|STUBOB|piizq7' "$evd" 2>/dev/null || true)
  if [ -z "$evd_dest" ] || [ "$evd_dest" = null ]; then why="the destination run wrote no .modes.destination first (the kept-entry check would be vacuous)"
  elif [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qxF "LOOP-ORDER mode=direct order=ORD-STUB-L001 intent=pi_loopd1 amount=950 application_fee=48 account=$lp_acct" <<<"$ST_OUT"; then
    why="no direct LOOP-ORDER verdict: $(command grep '^LOOP-' <<<"$ST_OUT" | tr '\n' '|')"
  elif ! command grep -qxF "LOOP-CAPTURE mode=direct charge=ch_loopd1 captured=true amount=950 account=$lp_acct" <<<"$ST_OUT"; then
    why="no direct LOOP-CAPTURE verdict: $(command grep '^LOOP-' <<<"$ST_OUT" | tr '\n' '|')"
  elif ! command grep -qxF 'LOOP-ACCEPT mode=direct order=ORD-STUB-L001 status=CONFIRMED' <<<"$ST_OUT"; then
    why="no direct LOOP-ACCEPT verdict: $(command grep '^LOOP-' <<<"$ST_OUT" | tr '\n' '|')"
  elif ! command grep -qxF "LOOP-REFUND mode=direct refund=re_loopd1 amount=100 status=succeeded account=$lp_acct local_status=succeeded" <<<"$ST_OUT"; then
    why="no direct LOOP-REFUND verdict: $(command grep '^LOOP-' <<<"$ST_OUT" | tr '\n' '|')"
  elif command grep -q '^LOOP-TRANSFER ' <<<"$ST_OUT"; then why="a direct charge printed a LOOP-TRANSFER verdict"
  elif [ "$(_st_acct_flow)" -lt 7 ]; then why="only $(_st_acct_flow) Stripe call(s) logged (want >= 7: intent, confirm, intent, charge, refund list, refund, charge)"
  elif [ "$(_st_acct_bad)" -ne 0 ]; then why="Stripe call(s) without Stripe-Account $lp_acct: $(command grep -vE " acct=$lp_acct key=" "$ast/logs/stripe-acct-flow.log" | tr '\n' '|')"
  elif [ "$(_st_flow ' -> 404$')" -ne 0 ]; then why="a call was answered 404: $(command grep -E ' -> 404$' "$ast/logs/curl-flow.log" | tr '\n' '|')"
  elif [ "$(_st_flow '^GET https://api\.stripe\.com/v1/transfers/')" -ne 0 ]; then why="a transfer was read for a direct charge"
  elif command grep -qF 'Stripe-Account' "$ast/logs/curl.log"; then why="the Stripe-Account header travelled in argv, not the config file"
  elif [ -z "$d_order" ] || [ -z "$d_conf" ] || [ -z "$d_pi" ] || [ -z "$d_ch" ] || [ -z "$d_acc" ] || [ -z "$d_rf" ] || [ -z "$d_rl" ] \
       || [ -z "$d_rid" ] || [ -z "$d_det" ] \
       || ! { [ "$d_order" -lt "$d_conf" ] && [ "$d_conf" -lt "$d_pi" ] && [ "$d_pi" -lt "$d_ch" ] && [ "$d_ch" -lt "$d_acc" ] \
              && [ "$d_acc" -lt "$d_rf" ] && [ "$d_rf" -lt "$d_rl" ] && [ "$d_rl" -lt "$d_rid" ] && [ "$d_rid" -lt "$d_ch2" ] && [ "$d_ch2" -lt "$d_det" ]; }; then
    why="call order is not order<confirm<intent<charge<accept<refund<list<refund-by-id<charge<detail: $d_order $d_conf $d_pi $d_ch $d_acc $d_rf $d_rl $d_rid $d_ch2 $d_det"
  elif [ "$(command grep -cxE "Idempotency-Key: ${md_stamp:-none}-(order|confirm|refund)" <<<"$md_keys" || true)" -ne 3 ]; then
    why="the order, confirm and refund do not carry one run id's keys: $(tr '\n' '|' <<<"$md_keys")"
  elif [ "$(_st_stripe_writes)" -ne 1 ]; then why="Stripe writes=$(_st_stripe_writes) (want 1, the confirm)"
  elif [ "$(_st_lp_leaks)" -ne 0 ] || [ "$(_st_leaks)" -ne 0 ]; then why="the client secret, a key or a customer field was printed or put in argv"
  elif ! command grep -qxF "LOOP-EVIDENCE file=$evd mode=direct" <<<"$ST_OUT"; then why="no LOOP-EVIDENCE line for mode=direct"
  elif [ "$(jq -c '.modes.direct | {order, intent, charge, refund, account, settlement_mode, livemode}' "$evd" 2>/dev/null)" \
       != '{"order":"ORD-STUB-L001","intent":"pi_loopd1","charge":"ch_loopd1","refund":"re_loopd1","account":"acct_loop1","settlement_mode":"DIRECT_CHARGE_VENDOR_MOR","livemode":false}' ]; then
    why="the direct entry's ids are not the loop's: $(jq -c '.modes.direct' "$evd" 2>/dev/null | head -c 300)"
  elif [ "$(jq -c '.modes.direct.amounts' "$evd")" != "$(jq -cS -n '{captured: 950, application_fee: 48, refund: 100}')" ] \
       || [ "$(jq -r '.modes.direct.statuses.refund + "/" + .modes.direct.statuses.local_refund' "$evd")" != succeeded/succeeded ]; then
    why="the direct amounts or statuses are not the loop's: $(jq -c '.modes.direct | {amounts, statuses}' "$evd")"
  elif [ "$(jq -c '.modes.destination' "$evd")" != "$evd_dest" ]; then why="the .modes.destination entry was changed by the direct run"
  elif [ "$(jq -c '.modes | keys_unsorted' "$evd")" != '["destination","direct"]' ]; then why="modes are not listed destination, direct: $(jq -c '.modes | keys_unsorted' "$evd")"
  elif [ "$evd_hits" -ne 0 ]; then why="the evidence holds $evd_hits key, secret, URL, e-mail or marker line(s)"
  fi
  if [ -z "$why" ]; then _st_result "PASS direct-ok rc=0 stripe_calls=$(_st_acct_flow) without_header=0 order=order<confirm<intent<charge<accept<refund<refund-by-id<charge<local evidence=destination,direct"
  else _st_result "FAIL direct-ok $why"; fails=$((fails + 1)); fi

  # --- direct-missing-header: a planted call without Stripe-Account (the charge read, then
  #     the first intent read) is answered resource_missing -> FAIL rc 1 naming the call;
  #     nothing is accepted, refunded or confirmed after it
  why=""
  local mh mh_re mh_call
  for mh in charge intent; do
    case "$mh" in
      charge) mh_re='^GET /v1/charges/'; mh_call='GET /v1/charges/ch_loopd1' ;;
      intent) mh_re='^GET /v1/payment_intents/'; mh_call='GET /v1/payment_intents/pi_loopd1' ;;
    esac
    _st_loopd_fx succeeded
    _st_run "${md_env[@]}" STUB_STRIP_ACCOUNT_ON="$mh_re" -- "${md_args[@]}"
    if [ "$ST_RC" -ne 1 ]; then why="$mh: rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(command grep -cxF "$mh_call acct=none key=none" "$ast/logs/stripe-acct-flow.log" || true)" -lt 1 ]; then
      why="$mh: the planted header-less call was never made, so the guard could not fire (non-vacuity): $(tr '\n' '|' < "$ast/logs/stripe-acct-flow.log")"
    elif ! command grep -qF "$mh_call" <<<"$ST_ERR"; then why="$mh: the failure does not name the call '$mh_call': $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
    elif ! command grep -qF 'resource_missing' <<<"$ST_ERR"; then why="$mh: the failure does not carry Stripe's resource_missing: $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(_st_accept_posts)" -ne 0 ] || [ "$(_st_refund_posts)" -ne 0 ]; then why="$mh: the order was accepted or refunded after the failed read"
    elif command grep -q '^LOOP-CAPTURE ' <<<"$ST_OUT"; then why="$mh: a LOOP-CAPTURE verdict was printed"
    elif [ "$mh" = intent ] && [ "$(_st_confirms)" -ne 0 ]; then why="intent: the intent was confirmed after its read failed"
    fi
    [ -z "$why" ] || break
  done
  if [ -z "$why" ]; then _st_result "PASS direct-missing-header rc=1 planted=charge,intent answer=resource_missing named=1 accept_posts=0 refund_posts=0"
  else _st_result "FAIL direct-missing-header $why"; fails=$((fails + 1)); fi

  # --- direct-local-refund-lag: Stripe's refund on the account succeeded, the local row
  #     stays pending past the deadline -> rc 1 naming both, no LOOP-REFUND verdict
  _st_loopd_fx pending
  _st_run "${md_env[@]}" STAGING_SEED_POLL_SECONDS=1 STAGING_SEED_REFUND_DEADLINE_SECONDS=2 -- "${md_args[@]}"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qF "'pending'" <<<"$ST_ERR" || ! command grep -qF "'succeeded'" <<<"$ST_ERR"; then why="the failure does not name both statuses: $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_flow '^GET https://api\.stripe\.com/v1/refunds/re_loopd1 -> 200$')" -lt 1 ]; then why="Stripe's refund was not read on the account first (non-vacuity)"
  elif [ "$(_st_flow "^GET https://api-staging\.olajay\.co\.uk/api/v1/orders/$lp_oid/detail -> 200$")" -lt 2 ]; then why="the local row was not polled (detail reads=$(_st_flow "/orders/$lp_oid/detail -> "))"
  elif command grep -q '^LOOP-REFUND ' <<<"$ST_OUT"; then why="a LOOP-REFUND verdict was printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS direct-local-refund-lag rc=1 local=pending stripe=succeeded verdict=none"
  else _st_result "FAIL direct-local-refund-lag $why"; fails=$((fails + 1)); fi

  # --- evidence-order: the DIRECT run first, then the destination run, into one file: the
  #     file is jq -S (sorted keys), modes read destination then direct, each entry's steps
  #     are in loop order, and the first run's entry is not touched by the second
  local evo="$ob_dir/evidence-order.json" evo_rc1 evo_direct=""
  rm -f "$evo"
  _st_loopd_fx succeeded
  _st_run "${md_env[@]}" -- "${md_args[@]}" --evidence "$evo"
  evo_rc1="$ST_RC"
  [ "$evo_rc1" -ne 0 ] || evo_direct=$(jq -c '.modes.direct' "$evo" 2>/dev/null || true)
  _st_loop_fx ENABLED DESTINATION_CHARGE_PLATFORM_MOR dest none
  _st_run "${lp_env[@]}" -- "${ml_args[@]}" --evidence "$evo"
  why=""
  if [ "$evo_rc1" -ne 0 ]; then why="the direct run: rc=$evo_rc1 (want 0)"
  elif [ -z "$evo_direct" ] || [ "$evo_direct" = null ]; then why="the direct run wrote no .modes.direct"
  elif [ "$ST_RC" -ne 0 ]; then why="the destination run: rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(jq -c '.modes | keys_unsorted' "$evo")" != '["destination","direct"]' ]; then why="modes are listed $(jq -c '.modes | keys_unsorted' "$evo"), not destination then direct"
  elif [ "$(jq -S . "$evo")" != "$(cat "$evo")" ]; then why="the file is not written with jq -S"
  elif [ "$(jq -c '.modes.direct.steps' "$evo")" != "$lp_steps_direct" ]; then why="the direct steps are not in loop order: $(jq -c '.modes.direct.steps' "$evo")"
  elif [ "$(jq -c '.modes.destination.steps' "$evo")" != "$lp_steps_dest" ]; then why="the destination steps are not in loop order: $(jq -c '.modes.destination.steps' "$evo")"
  elif [ "$(jq -c '.modes.direct' "$evo")" != "$evo_direct" ]; then why="the destination run changed the direct entry"
  fi
  if [ -z "$why" ]; then _st_result "PASS evidence-order rc=0 sorted=1 modes=destination,direct steps=loop-order earlier_entry=kept"
  else _st_result "FAIL evidence-order $why"; fails=$((fails + 1)); fi

  # PAY-03 payout (plan 30-29): the platform sets the connected account's payout
  # schedule to manual (platform-level: no Stripe-Account), reads its available GBP
  # balance, pays out min(requested, available) with Stripe-Account and
  # <run id>-payout-<acct>, and reads the payout by id until paid or the deadline.
  # The account object carries bank details and a name (STUBBANKpo1, piizq7), which
  # must never be printed. STUB_CONNECT_ACCOUNT stays on: every balance and payout
  # call must carry the header, and the account update must not.
  local po_args=("${tool_args[@]}" --stage payout --account "$lp_acct") pr_args=("${tool_args[@]}" --stage payout-read --account "$lp_acct")
  local po_leak_re='STUBBANKpo1|piizq7|PIIZQ7'
  # _st_po_obj STATUS AMOUNT RUN_ID [ID] — one payout object.
  _st_po_obj() {
    jq -n --arg st "$1" --argjson a "$2" --arg r "$3" --arg id "${4:-po_loop1}" '{id: $id, object: "payout", livemode: false, amount: $a,
        currency: "gbp", status: $st, destination: "ba_po1", method: "standard", arrival_date: 1793577600,
        metadata: (if $r == "" then {} else {loop_run_id: $r} end)}'
  }
  # _st_po_fx RUN_ID AVAILABLE CREATE_AMOUNT [READ_STATUS...] — the account update's
  # answer, the balance, the payout list (another run's payout only), the create answer,
  # and the by-id reads in order (the last one repeats).
  _st_po_fx() {
    local run="$1" avail="$2" camt="$3" i=0 st
    shift 3
    _st_loop_fx ENABLED DIRECT_CHARGE_VENDOR_MOR dest none
    rm -f "$ast/resp/"po-po_*.json "$ast/resp/po-list.json"
    jq -n --arg a "$lp_acct" '{id: $a, object: "account", type: "express", payouts_enabled: true, email: "piizq7@example.invalid",
        settings: {payouts: {schedule: {interval: "manual", delay_days: 7}}},
        external_accounts: {object: "list", data: [{id: "ba_po1", object: "bank_account", bank_name: "STUBBANKpo1", last4: "STUBBANKpo1",
          routing_number: "STUBBANKpo1", account_holder_name: "Pii PIIZQ7"}]}}' > "$ast/resp/po-account.json"
    jq -n --argjson av "$avail" '{object: "balance", livemode: false, available: [{amount: $av, currency: "gbp"}, {amount: 777, currency: "eur"}],
        pending: [{amount: 120, currency: "gbp"}]}' > "$ast/resp/po-balance.json"
    jq -n '{object: "list", has_more: false, url: "/v1/payouts", data: [{id: "po_other1", object: "payout", livemode: false, amount: 300,
        currency: "gbp", status: "paid", metadata: {loop_run_id: "someone-elses-run"}}]}' > "$ast/resp/po-list.json"
    _st_po_obj pending "$camt" "$run" > "$ast/resp/po-create.json"
    for st in "$@"; do i=$((i + 1)); _st_po_obj "$st" "$camt" "$run" > "$ast/resp/po-po_loop1-$i.json"; done
    [ "$i" -eq 0 ] || cp "$ast/resp/po-po_loop1-$i.json" "$ast/resp/po-po_loop1.json"
  }
  _st_po_posts() { _st_flow '^POST https://api\.stripe\.com/v1/payouts -> '; }
  _st_po_leaks() { command grep -cE "$po_leak_re" <<<"$ST_OUT$ST_ERR$(cat "$ast/logs/curl.log")" || true; }
  _st_af() { if [ -s "$ast/logs/stripe-acct-flow.log" ]; then command grep -cxF "$1" "$ast/logs/stripe-acct-flow.log" || true; else echo 0; fi; }

  # --- payout-ok: 950 available, 500 requested -> one payout of 500, polled in_transit
  #     then paid, every state printed; the schedule update is platform-level, the
  #     balance, the payout and its reads carry Stripe-Account; .payouts[] written
  local pev="$ob_dir/evidence-payout.json" pev_hits
  rm -f "$pev"
  _st_po_fx po-run-1 950 500 in_transit paid
  _st_run "${md_env[@]}" -- "${po_args[@]}" --amount-pennies 500 --run-id po-run-1 --evidence "$pev"
  why=""
  local p_del p_list p_acc p_bal p_post p_get
  p_del=$(_st_line "$re_opdel")
  p_list=$(_st_line '^GET https://api\.stripe\.com/v1/payouts\?limit=100 -> 200$')
  p_acc=$(_st_line "^POST https://api\.stripe\.com/v1/accounts/$lp_acct -> 200$")
  p_bal=$(_st_line '^GET https://api\.stripe\.com/v1/balance -> 200$')
  p_post=$(_st_line '^POST https://api\.stripe\.com/v1/payouts -> 200$')
  p_get=$(_st_last '^GET https://api\.stripe\.com/v1/payouts/po_loop1 -> 200$')
  pev_hits=$(command grep -ciE 'sk_|rk_|whsec_|client_secret|_secret_|https?://|@|STUBBANK|piizq7' "$pev" 2>/dev/null || true)
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(tail -n 1 <<<"$ST_OUT")" != "PAYOUT account=$lp_acct payout=po_loop1 amount=500 status=paid livemode=false" ]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  elif ! command grep -qE '^PAYOUT-WAIT payout=po_loop1 status=in_transit ' <<<"$ST_OUT" || ! command grep -qE '^PAYOUT-WAIT payout=po_loop1 status=paid ' <<<"$ST_OUT"; then
    why="every state was not printed: $(command grep '^PAYOUT' <<<"$ST_OUT" | tr '\n' '|')"
  elif [ "$(_st_po_posts)" -ne 1 ]; then why="payout posts=$(_st_po_posts) (want 1)"
  elif [ "$(_st_af "POST /v1/payouts acct=$lp_acct key=po-run-1-payout-$lp_acct")" -ne 1 ]; then
    why="the payout POST does not carry Stripe-Account and Idempotency-Key <run id>-payout-<acct>: $(command grep -F 'POST /v1/payouts' "$ast/logs/stripe-acct-flow.log" | tr '\n' '|')"
  elif [ "$(_st_af "POST /v1/accounts/$lp_acct acct=none key=po-run-1-schedule-$lp_acct")" -ne 1 ]; then
    why="the schedule update is not one platform-level call with <run id>-schedule-<acct>: $(command grep -F '/v1/accounts' "$ast/logs/stripe-acct-flow.log" | tr '\n' '|')"
  elif [ "$(_st_acct_bad)" -ne 1 ]; then why="Stripe calls without Stripe-Account $lp_acct other than the account update: $(command grep -vE " acct=$lp_acct key=" "$ast/logs/stripe-acct-flow.log" | tr '\n' '|')"
  elif ! command grep -qF 'settings[payouts][schedule][interval]=manual' "$ast/logs/curl.log"; then why="the schedule update does not set interval=manual"
  elif ! command grep -qE 'amount=500( |$)' "$ast/logs/curl.log" || ! command grep -qE 'currency=gbp( |$)' "$ast/logs/curl.log" \
       || ! command grep -qF 'metadata[loop_run_id]=po-run-1' "$ast/logs/curl.log"; then why="the payout POST is not amount=500 currency=gbp metadata[loop_run_id]=po-run-1"
  elif [ -z "$p_del" ] || [ -z "$p_list" ] || [ -z "$p_acc" ] || [ -z "$p_bal" ] || [ -z "$p_post" ] || [ -z "$p_get" ] \
       || ! { [ "$p_del" -lt "$p_list" ] && [ "$p_list" -lt "$p_acc" ] && [ "$p_acc" -lt "$p_bal" ] && [ "$p_bal" -lt "$p_post" ] && [ "$p_post" -lt "$p_get" ]; }; then
    why="call order is not operator-deleted<payout list<schedule<balance<payout<read: $p_del $p_list $p_acc $p_bal $p_post $p_get"
  elif [ "$(_st_flow ' -> 404$')" -ne 0 ]; then why="a call was answered 404: $(command grep -E ' -> 404$' "$ast/logs/curl-flow.log" | tr '\n' '|')"
  elif [ "$(_st_po_leaks)" -ne 0 ] || [ "$(_st_leaks)" -ne 0 ]; then why="a bank detail, a name, an e-mail or a key was printed or put in argv ($(_st_po_leaks) hit(s))"
  elif [ "$(jq -c '.payouts | length' "$pev" 2>/dev/null)" != 1 ]; then why="the evidence does not hold exactly one payout: $(head -c 300 "$pev" 2>/dev/null)"
  elif [ "$(jq -c '.payouts[0] | {account, payout, amount, requested, available, whole_balance, schedule_interval, status, destination, run_id, livemode}' "$pev")" \
       != '{"account":"acct_loop1","payout":"po_loop1","amount":500,"requested":500,"available":950,"whole_balance":false,"schedule_interval":"manual","status":"paid","destination":"ba_po1","run_id":"po-run-1","livemode":false}' ]; then
    why="the payout entry is not the run's: $(jq -c '.payouts[0]' "$pev")"
  elif [ "$pev_hits" -ne 0 ]; then why="the evidence holds $pev_hits key, secret, URL, e-mail or bank-detail line(s)"
  elif [ "$(jq -S . "$pev")" != "$(cat "$pev")" ] || [ "$(stat -c %a "$pev")" != 600 ]; then why="the evidence is not jq -S, mode 600"
  fi
  if [ -z "$why" ]; then _st_result "PASS payout-ok rc=0 payout=500 of 950 states=in_transit,paid schedule=manual,platform-level header=balance,payout,reads key=<run>-payout-<acct>"
  else _st_result "FAIL payout-ok $why"; fails=$((fails + 1)); fi

  # --- payout-all-available: 2000 requested (and none), 950 available -> a payout of the
  #     whole 950, recorded as the whole balance; 2000 is never requested
  why=""
  local paa pav="$ob_dir/evidence-payout-all.json"
  for paa in 2000 default; do
    rm -f "$pav"
    _st_po_fx po-run-2 950 950 paid
    if [ "$paa" = default ]; then _st_run "${md_env[@]}" -- "${po_args[@]}" --run-id po-run-2 --evidence "$pav"
    else _st_run "${md_env[@]}" -- "${po_args[@]}" --amount-pennies 2000 --run-id po-run-2 --evidence "$pav"; fi
    if [ "$ST_RC" -ne 0 ]; then why="$paa: rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(tail -n 1 <<<"$ST_OUT")" != "PAYOUT account=$lp_acct payout=po_loop1 amount=950 status=paid livemode=false" ]; then why="$paa: the last line is '$(tail -n 1 <<<"$ST_OUT")'"
    elif ! command grep -qE 'amount=950( |$)' "$ast/logs/curl.log"; then why="$paa: no payout of amount=950 was requested"
    elif command grep -qE 'amount=2000( |$)' "$ast/logs/curl.log"; then why="$paa: a payout above the available balance (2000) was requested"
    elif ! command grep -qF 'the whole available balance' <<<"$ST_OUT"; then why="$paa: the payout is not said to be the whole available balance"
    elif [ "$(jq -c '.payouts[0] | {amount, available, whole_balance}' "$pav" 2>/dev/null)" != '{"amount":950,"available":950,"whole_balance":true}' ] \
         || [ "$(jq -r '.payouts[0].requested' "$pav")" != "$([ "$paa" = default ] && echo 950 || echo 2000)" ]; then
      why="$paa: the entry does not record the whole balance: $(jq -c '.payouts[0]' "$pav" 2>/dev/null)"
    fi
    [ -z "$why" ] || break
  done
  if [ -z "$why" ]; then _st_result "PASS payout-all-available rc=0 requested=2000,default available=950 payout=950 whole_balance=true above_available_requested=0"
  else _st_result "FAIL payout-all-available $why"; fails=$((fails + 1)); fi

  # --- payout-no-balance: 0 available -> REFUSED [no-available-balance], no payout POST
  _st_po_fx po-run-3 0 0
  _st_run "${md_env[@]}" -- "${po_args[@]}" --run-id po-run-3
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif ! command grep -qF 'REFUSED [no-available-balance]' <<<"$ST_ERR"; then why="stderr lacks 'REFUSED [no-available-balance]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_af "GET /v1/balance acct=$lp_acct key=none")" -ne 1 ]; then why="the refusal was not reached through the account's balance read (non-vacuity)"
  elif [ "$(_st_po_posts)" -ne 0 ]; then why="a payout was requested: $(_st_po_posts)"
  elif command grep -q '^PAYOUT ' <<<"$ST_OUT"; then why="a PAYOUT line was printed"
  fi
  if [ -z "$why" ]; then _st_result "PASS payout-no-balance rc=1 tag=no-available-balance balance_reads=1 payout_posts=0"
  else _st_result "FAIL payout-no-balance $why"; fails=$((fails + 1)); fi

  # --- payout-pending: the payout stays in_transit past the deadline -> rc 0, Stripe's
  #     status verbatim (in_transit, never paid), a payout-read hint, the entry in_transit
  local ppe="$ob_dir/evidence-payout-pending.json"
  rm -f "$ppe"
  _st_po_fx po-run-4 950 500 in_transit
  _st_run "${md_env[@]}" STAGING_SEED_POLL_SECONDS=1 STAGING_SEED_PAYOUT_DEADLINE_SECONDS=2 -- "${po_args[@]}" --amount-pennies 500 --run-id po-run-4 --evidence "$ppe"
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(tail -n 1 <<<"$ST_OUT")" != "PAYOUT account=$lp_acct payout=po_loop1 amount=500 status=in_transit livemode=false" ]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  elif command grep -qF 'status=paid' <<<"$ST_OUT"; then why="a payout Stripe never reported paid was printed as paid"
  elif [ "$(_st_flow '^GET https://api\.stripe\.com/v1/payouts/po_loop1 -> 200$')" -lt 2 ]; then why="the payout was not polled (reads=$(_st_flow '/v1/payouts/po_loop1 -> '))"
  elif ! command grep -qF -- "--stage payout-read --account $lp_acct --payout po_loop1" <<<"$ST_OUT"; then why="no payout-read hint: $(command grep -E '^(HINT|PAYOUT)' <<<"$ST_OUT" | tr '\n' '|')"
  elif [ "$(jq -r '.payouts[0].status' "$ppe" 2>/dev/null)" != in_transit ]; then why="the entry's status is '$(jq -r '.payouts[0].status' "$ppe" 2>/dev/null)', not in_transit"
  fi
  if [ -z "$why" ]; then _st_result "PASS payout-pending rc=0 status=in_transit printed_paid=0 hint=payout-read entry=in_transit"
  else _st_result "FAIL payout-pending $why"; fails=$((fails + 1)); fi

  # --- payout-read: the pending payout read again by id after Stripe reports it paid ->
  #     PAYOUT status=paid, the entry's status updated (nothing else), no write to
  #     Stripe; a payout the evidence does not hold leaves the file untouched
  _st_po_fx po-run-4 950 500 paid
  _st_run "${md_env[@]}" -- "${pr_args[@]}" --payout po_loop1 --evidence "$ppe"
  why=""
  local ppe_sum
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(tail -n 1 <<<"$ST_OUT")" != "PAYOUT account=$lp_acct payout=po_loop1 amount=500 status=paid livemode=false" ]; then why="the last line is '$(tail -n 1 <<<"$ST_OUT")'"
  elif [ "$(_st_af "GET /v1/payouts/po_loop1 acct=$lp_acct key=none")" -ne 1 ]; then why="the payout was not read exactly once by id with Stripe-Account"
  elif [ "$(_st_stripe_writes)" -ne 0 ]; then why="payout-read wrote to Stripe: $(_st_stripe_writes)"
  elif [ "$(jq -c '.payouts | length' "$ppe")" != 1 ] \
       || [ "$(jq -c '.payouts[0] | {payout, amount, requested, status}' "$ppe")" != '{"payout":"po_loop1","amount":500,"requested":500,"status":"paid"}' ] \
       || ! jq -e '.payouts[0].read_at | type == "string"' "$ppe" >/dev/null 2>&1; then
    why="the entry was not updated to paid in place: $(jq -c '.payouts' "$ppe")"
  else
    ppe_sum=$(sha256sum "$ppe" | cut -d' ' -f1)
    _st_po_obj paid 400 "" po_unknown9 > "$ast/resp/po-po_unknown9.json"
    _st_run "${md_env[@]}" -- "${pr_args[@]}" --payout po_unknown9 --evidence "$ppe"
    if [ "$ST_RC" -ne 1 ]; then why="unknown payout: rc=$ST_RC (want 1)"
    elif ! command grep -qF 'no .payouts entry for po_unknown9' <<<"$ST_ERR"; then why="unknown payout: the failure does not say why: $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(sha256sum "$ppe" | cut -d' ' -f1)" != "$ppe_sum" ]; then why="unknown payout: the evidence file was changed"
    else
      _st_run "${md_env[@]}" -- "${pr_args[@]}" --payout po_loop1
      [ "$ST_RC" -eq 0 ] && [ "$(tail -n 1 <<<"$ST_OUT")" = "PAYOUT account=$lp_acct payout=po_loop1 amount=500 status=paid livemode=false" ] \
        || why="without --evidence: rc=$ST_RC, last line '$(tail -n 1 <<<"$ST_OUT")'"
    fi
  fi
  if [ -z "$why" ]; then _st_result "PASS payout-read rc=0 status=in_transit->paid entry=updated stripe_writes=0 unknown_payout=refused,file_untouched"
  else _st_result "FAIL payout-read $why"; fails=$((fails + 1)); fi

  # --- payout-other-account: an account that is not the staging tenant's (T-30-114) ->
  #     REFUSED [other-account] before any Stripe call, for payout and payout-read
  why=""
  local poa
  for poa in payout payout-read; do
    _st_po_fx po-run-5 950 500 paid
    if [ "$poa" = payout ]; then _st_run "${md_env[@]}" -- "${tool_args[@]}" --stage payout --account acct_other9 --run-id po-run-5
    else _st_run "${md_env[@]}" -- "${tool_args[@]}" --stage payout-read --account acct_other9 --payout po_loop1; fi
    if [ "$ST_RC" -ne 1 ]; then why="$poa: rc=$ST_RC (want 1): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif ! command grep -qF 'REFUSED [other-account]' <<<"$ST_ERR"; then why="$poa: stderr lacks 'REFUSED [other-account]': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(_st_flow '^GET https://api-staging\.olajay\.co\.uk/api/v1/admin/tenants -> 200$')" -ne 1 ]; then why="$poa: the refusal was not reached through the admin tenant read"
    elif [ "$(_st_acct_flow)" -ne 0 ]; then why="$poa: a Stripe call was made: $(tr '\n' '|' < "$ast/logs/stripe-acct-flow.log")"
    elif [ "$(_st_flow "$re_opdel")" -ne 1 ]; then why="$poa: the operator was not deleted exactly once"
    fi
    [ -z "$why" ] || break
  done
  if [ -z "$why" ]; then _st_result "PASS payout-other-account rc=1 tag=other-account stages=payout,payout-read stripe_calls=0"
  else _st_result "FAIL payout-other-account $why"; fails=$((fails + 1)); fi

  # --- payout-args: every payout flag is checked before any guard or call ------------
  why=""
  local pa_n pa_want
  for pa_n in 1 2 3 4 5 6 7; do
    case "$pa_n" in
      1) _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${tool_args[@]}" --stage payout; pa_want="USAGE ERROR: --stage payout needs --account" ;;
      2) _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${po_args[@]}" --amount-pennies 0; pa_want="USAGE ERROR: --amount-pennies must be a positive whole number" ;;
      3) _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${po_args[@]}" --payout po_loop1; pa_want="USAGE ERROR: --payout is taken by --stage payout-read only" ;;
      4) _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${pr_args[@]}"; pa_want="USAGE ERROR: --stage payout-read needs --payout" ;;
      5) _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${po_args[@]}" --run-id 'bad id!'; pa_want="USAGE ERROR: --run-id must be" ;;
      6) _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${ml_args[@]}" --amount-pennies 500; pa_want="USAGE ERROR: --amount-pennies is taken by --stage payout only" ;;
      7) _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${pr_args[@]}" --payout po_loop1 --run-id po-run-6; pa_want="USAGE ERROR: --run-id is taken by --stage money-loop and payout only" ;;
    esac
    why=$(_st_refusal 2 "$pa_want" 0 0) || { why="case $pa_n: $why"; break; }
  done
  if [ -z "$why" ]; then _st_result "PASS payout-args rc=2 cases=7 az_calls=0 curl_calls=0"
  else _st_result "FAIL payout-args $why"; fails=$((fails + 1)); fi

  # --- replay-run-id (T-30-112): the same --run-id twice -> the order POST replays its
  #     key, nothing else is written again (no confirm, accept, refund or payout POST);
  #     another run id -> its own order key and its own refund
  why=""
  local rr_ev="$ob_dir/evidence-replay.json" rr_key1
  rm -f "$rr_ev"
  _st_loopd_fx succeeded
  _st_run "${md_env[@]}" -- "${md_args[@]}" --run-id loop-replay-1 --evidence "$rr_ev"
  rr_key1=$(command grep -oE 'Idempotency-Key: loop-replay-1-order' "$ast/logs/curl.log" | head -n 1 || true)
  if [ "$ST_RC" -ne 0 ]; then why="run 1: rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ -z "$rr_key1" ] || ! command grep -qF 'Idempotency-Key: loop-replay-1-confirm' "$ast/logs/curl.log" \
       || ! command grep -qF 'Idempotency-Key: loop-replay-1-refund' "$ast/logs/curl.log"; then why="run 1: the keys do not derive from --run-id loop-replay-1"
  elif [ "$(_st_refund_posts)" -ne 1 ]; then why="run 1: refund posts=$(_st_refund_posts) (want 1)"
  else
    # What run 1 left behind: the intent succeeded, the order CONFIRMED, its refund row
    # carrying this run's key (RefundService stores the client's Idempotency-Key).
    cp "$ast/resp/pi-after.json" "$ast/resp/pi-before.json"
    jq -n '{content: [], totalElements: 0, totalPages: 1, number: 0, last: true}' > "$ast/resp/orders-status-PENDING.json"
    jq -n --arg o "$lp_oid" --arg s "$sid" '{content: [{id: $o, shopId: $s, orderNumber: "ORD-STUB-L001", status: "CONFIRMED",
        paymentStatus: "CAPTURED", paymentReference: "pi_loopd1", totalAmountPennies: 950}], totalElements: 1, totalPages: 1, number: 0, last: true}' \
      > "$ast/resp/orders-status-CONFIRMED.json"
    jq --arg k "loop-replay-1-refund" '.refunds[0].idempotencyKey = $k | .refunds[0].status = "succeeded"' "$ast/resp/detail-$lp_oid.json" \
      > "$ast/resp/detail-$lp_oid.next" && mv "$ast/resp/detail-$lp_oid.next" "$ast/resp/detail-$lp_oid.json"
    _st_run "${md_env[@]}" -- "${md_args[@]}" --run-id loop-replay-1 --evidence "$rr_ev"
    if [ "$ST_RC" -ne 0 ]; then why="run 2: rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(_st_order_posts)" -ne 1 ] || [ "$(command grep -oE 'Idempotency-Key: [^ ]+-order' "$ast/logs/curl.log" | head -n 1)" != "$rr_key1" ]; then
      why="run 2: the order POST did not replay run 1's key ($rr_key1): $(command grep -oE 'Idempotency-Key: [^ ]+' "$ast/logs/curl.log" | tr '\n' '|')"
    elif [ "$(_st_confirms)" -ne 0 ] || [ "$(_st_accept_posts)" -ne 0 ] || [ "$(_st_refund_posts)" -ne 0 ]; then
      why="run 2 wrote again: confirms=$(_st_confirms) accepts=$(_st_accept_posts) refunds=$(_st_refund_posts) (want 0 each)"
    elif ! command grep -qxF "LOOP-REFUND mode=direct refund=re_loopd1 amount=100 status=succeeded account=$lp_acct local_status=succeeded" <<<"$ST_OUT"; then
      why="run 2: the replay is not fully evidenced: $(command grep '^LOOP-' <<<"$ST_OUT" | tr '\n' '|')"
    elif [ "$(jq -r '.modes.direct.run_id' "$rr_ev" 2>/dev/null)" != loop-replay-1 ]; then why="run 2: the evidence does not name run loop-replay-1"
    else
      _st_run "${md_env[@]}" -- "${md_args[@]}" --run-id loop-replay-2 --evidence "$rr_ev"
      if [ "$ST_RC" -ne 0 ]; then why="other run: rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
      elif ! command grep -qF 'Idempotency-Key: loop-replay-2-order' "$ast/logs/curl.log"; then why="other run: its order does not carry its own key"
      elif [ "$(_st_refund_posts)" -ne 1 ] || ! command grep -qF 'Idempotency-Key: loop-replay-2-refund' "$ast/logs/curl.log"; then
        why="other run: it did not issue its own refund with loop-replay-2-refund (refund posts=$(_st_refund_posts))"
      elif [ "$(jq -r '.modes.direct.run_id' "$rr_ev" 2>/dev/null)" != loop-replay-2 ]; then why="other run: the evidence does not name run loop-replay-2"
      fi
    fi
  fi
  if [ -z "$why" ]; then
    _st_po_fx po-replay-1 950 500 paid
    _st_run "${md_env[@]}" -- "${po_args[@]}" --amount-pennies 500 --run-id po-replay-1
    if [ "$ST_RC" -ne 0 ]; then why="payout run 1: rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(_st_po_posts)" -ne 1 ] || [ "$(_st_af "POST /v1/payouts acct=$lp_acct key=po-replay-1-payout-$lp_acct")" -ne 1 ]; then
      why="payout run 1: not one payout POST with Idempotency-Key po-replay-1-payout-$lp_acct: $(command grep -F 'POST /v1/payouts' "$ast/logs/stripe-acct-flow.log" | tr '\n' '|')"
    else
      # Stripe now lists run 1's payout, stamped with its run id.
      jq --argjson p "$(_st_po_obj paid 500 po-replay-1)" '.data += [$p]' "$ast/resp/po-list.json" > "$ast/resp/po-list.next" \
        && mv "$ast/resp/po-list.next" "$ast/resp/po-list.json"
      _st_run "${md_env[@]}" -- "${po_args[@]}" --amount-pennies 500 --run-id po-replay-1
      if [ "$ST_RC" -ne 0 ]; then why="payout run 2: rc=$ST_RC (want 0): $(head -c 240 <<<"$ST_ERR" | tr '\n' ' ')"
      elif [ "$(_st_stripe_writes)" -ne 0 ]; then why="payout run 2 wrote again: $(command grep -E '^(POST|DELETE) https://api\.stripe\.com/' "$ast/logs/curl-flow.log" | tr '\n' '|')"
      elif [ "$(tail -n 1 <<<"$ST_OUT")" != "PAYOUT account=$lp_acct payout=po_loop1 amount=500 status=paid livemode=false" ]; then why="payout run 2: the last line is '$(tail -n 1 <<<"$ST_OUT")'"
      fi
    fi
  fi
  if [ -z "$why" ]; then _st_result "PASS replay-run-id rc=0 order_key=replayed confirm_posts=0 accept_posts=0 refund_posts=0 payout_posts=0 other_run=own_keys,own_refund"
  else _st_result "FAIL replay-run-id $why"; fails=$((fails + 1)); fi
  rm -f "$ast/resp/"po-*.json
  rm -f "$ast/resp/ch-ch_loopd1.json" "$ast/resp/re-re_loopd1.json"

  for lp_f in tenants shop pub-shop pub-products guest-order tracking orders-status-PENDING orders-status-CONFIRMED transition "detail-$lp_oid"; do
    if [ -f "$ast/resp/$lp_f.pre-loop" ]; then mv "$ast/resp/$lp_f.pre-loop" "$ast/resp/$lp_f.json"; else rm -f "$ast/resp/$lp_f.json"; fi
  done

  # --- dry-run-tools: the new stages' plans; guards run, nothing is called ------
  why=""
  for sub in list-drafts vendor-order order-transition cancel-drafts list-orphan-intents cancel-orphan-intents stripe-get pay-link billing billing-sync-check connect-onboard settlement-mode money-loop payout payout-read; do
    case "$sub" in
      order-transition) set -- --orders ORD-STUB-D001 --event submit ;;
      cancel-drafts)    set -- --orders ORD-STUB-D001,ORD-STUB-D002 ;;
      cancel-orphan-intents) set -- --intents pi_orphana,pi_orphanb ;;
      stripe-get)       set -- --path /v1/balance ;;
      pay-link)         set -- --link-out "$ast/link-out/dry.url" ;;
      connect-onboard)  set -- --link-out "$ast/link-out/dry-onboard.url" ;;
      money-loop)       set -- --mode destination ;;
      payout)           set -- --account acct_STUB1 --amount-pennies 500 --run-id dry-run-1 ;;
      payout-read)      set -- --account acct_STUB1 --payout po_STUB1 ;;
      *)                set -- ;;
    esac
    _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${tool_args[@]}" --stage "$sub" "$@" --dry-run
    if [ "$ST_RC" -ne 0 ]; then why="$sub: rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
    elif [ "$(_st_lines az)" -ne 0 ] || [ "$(_st_lines curl)" -ne 0 ]; then why="$sub: az=$(_st_lines az) curl=$(_st_lines curl) (want 0, 0)"
    elif [ "$(_st_pod_reads)" -ne 1 ]; then why="$sub: the guards did not run (pod reads=$(_st_pod_reads))"
    elif ! command grep -qF "PLAN --stage $sub (dry-run" <<<"$ST_OUT"; then why="$sub: no plan printed"
    fi
    [ -z "$why" ] || break
  done
  set --
  # --mode direct prints its own plan: every Stripe call on the connected account.
  if [ -z "$why" ]; then
    _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${tool_args[@]}" --stage money-loop --mode direct --dry-run
    if [ "$ST_RC" -ne 0 ] || [ "$(_st_lines az)" -ne 0 ] || [ "$(_st_lines curl)" -ne 0 ]; then why="money-loop direct: rc=$ST_RC az=$(_st_lines az) curl=$(_st_lines curl)"
    elif ! command grep -qF 'mode          direct (DIRECT_CHARGE_VENDOR_MOR)' <<<"$ST_OUT" \
         || [ "$(command grep -cF 'Stripe-Account' <<<"$ST_OUT")" -lt 5 ]; then why="money-loop direct: the plan does not show Stripe-Account on the Stripe calls"
    fi
  fi
  if [ -z "$why" ]; then _st_result "PASS dry-run-tools rc=0 stages=15 direct_plan=1 az_calls=0 curl_calls=0"
  else _st_result "FAIL dry-run-tools $why"; fails=$((fails + 1)); fi

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
INTENTS=""
EVENT=""
STRIPE_PATH=""
STRIPE_ACCOUNT=""
LINK_OUT=""
SET_MODE=""
LOOP_MODE=""
LOOP_EVIDENCE=""
LOOP_REFUND_PENNIES=""
RUN_ID=""
PAYOUT_PENNIES=""
PAYOUT_ID=""
STRIPE_PATH_RE='^/v1/(webhook_endpoints|payment_intents|charges|refunds|transfers|payouts|accounts|subscriptions|invoices|events|balance)(/[A-Za-z0-9_]+)*(\?[]A-Za-z0-9_.=&[-]*)?$'

while [ "$#" -gt 0 ]; do
  case "$1" in
    --context)         shift; [ "$#" -gt 0 ] || usage_error "--context needs a name"; KUBE_CONTEXT="$1" ;;
    --identity-file)   shift; [ "$#" -gt 0 ] || usage_error "--identity-file needs a path"; IDENTITY_FILE="$1" ;;
    --api)             shift; [ "$#" -gt 0 ] || usage_error "--api needs a URL"; API_BASE="$1" ;;
    --stage)           shift; [ "$#" -gt 0 ] || usage_error "--stage needs a stage name"; STAGE="$1" ;;
    --orders)          shift; [ "$#" -gt 0 ] || usage_error "--orders needs a comma list of order numbers"; ORDERS="$1" ;;
    --intents)         shift; [ "$#" -gt 0 ] || usage_error "--intents needs a comma list of PaymentIntent ids"; INTENTS="$1" ;;
    --event)           shift; [ "$#" -gt 0 ] || usage_error "--event needs submit|confirm|start-preparation"; EVENT="$1" ;;
    --path)            shift; [ "$#" -gt 0 ] || usage_error "--path needs a Stripe path (/v1/…)"; STRIPE_PATH="$1" ;;
    --account)         shift; [ "$#" -gt 0 ] || usage_error "--account needs a connected account id (acct_…)"; STRIPE_ACCOUNT="$1" ;;
    --link-out)        shift; [ "$#" -gt 0 ] || usage_error "--link-out needs a file path"; LINK_OUT="$1" ;;
    --set)             shift; [ "$#" -gt 0 ] || usage_error "--set needs a settlement mode"; SET_MODE="$1" ;;
    --mode)            shift; [ "$#" -gt 0 ] || usage_error "--mode needs destination or direct"; LOOP_MODE="$1" ;;
    --evidence)        shift; [ "$#" -gt 0 ] || usage_error "--evidence needs a file path"; LOOP_EVIDENCE="$1" ;;
    --refund-pennies)  shift; [ "$#" -gt 0 ] || usage_error "--refund-pennies needs a whole number of pennies"; LOOP_REFUND_PENNIES="$1" ;;
    --run-id)          shift; [ "$#" -gt 0 ] || usage_error "--run-id needs an id"; RUN_ID="$1" ;;
    --amount-pennies)  shift; [ "$#" -gt 0 ] || usage_error "--amount-pennies needs a whole number of pennies"; PAYOUT_PENNIES="$1" ;;
    --payout)          shift; [ "$#" -gt 0 ] || usage_error "--payout needs a payout id (po_…)"; PAYOUT_ID="$1" ;;
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
  vendor|order|all|list-drafts|vendor-order|order-transition|cancel-drafts|list-orphan-intents|cancel-orphan-intents|stripe-get|pay-link|billing|billing-sync-check|connect-onboard|settlement-mode|money-loop|payout|payout-read) ;;
  "") usage_error "--stage is required (vendor|order|all|list-drafts|vendor-order|order-transition|cancel-drafts|list-orphan-intents|cancel-orphan-intents|stripe-get|pay-link|billing|billing-sync-check|connect-onboard|settlement-mode|money-loop|payout|payout-read)" ;;
  *) usage_error "--stage must be vendor, order, all, list-drafts, vendor-order, order-transition, cancel-drafts, list-orphan-intents, cancel-orphan-intents, stripe-get, pay-link, billing, billing-sync-check, connect-onboard, settlement-mode, money-loop, payout or payout-read" ;;
esac
if [ "$STAGE" != cancel-orphan-intents ]; then
  [ -z "$INTENTS" ] || usage_error "--intents is taken by --stage cancel-orphan-intents only"
fi
case "$STAGE" in
  pay-link|connect-onboard) ;;
  *) [ -z "$LINK_OUT" ] || usage_error "--link-out is taken by --stage pay-link and --stage connect-onboard only" ;;
esac
if [ "$STAGE" != settlement-mode ]; then
  [ -z "$SET_MODE" ] || usage_error "--set is taken by --stage settlement-mode only"
fi
if [ "$STAGE" != money-loop ]; then
  [ -z "$LOOP_MODE" ] || usage_error "--mode is taken by --stage money-loop only"
  [ -z "$LOOP_REFUND_PENNIES" ] || usage_error "--refund-pennies is taken by --stage money-loop only"
fi
case "$STAGE" in
  money-loop|payout|payout-read) ;;
  *) [ -z "$LOOP_EVIDENCE" ] || usage_error "--evidence is taken by --stage money-loop, payout and payout-read only" ;;
esac
# --run-id (plan 30-29) names the run every Idempotency-Key derives from, so only the
# stages that write take it; a re-run with the same id replays instead of writing again.
case "$STAGE" in
  money-loop|payout) ;;
  *) [ -z "$RUN_ID" ] || usage_error "--run-id is taken by --stage money-loop and payout only (the stages that write)" ;;
esac
if [ -n "$RUN_ID" ] && ! [[ "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9.-]{0,63}$ ]]; then
  usage_error "--run-id must be 1-64 letters, digits, '.' or '-', starting with a letter or digit (got '$RUN_ID'); it becomes every Idempotency-Key"
fi
[ "$STAGE" = payout ] || [ -z "$PAYOUT_PENNIES" ] || usage_error "--amount-pennies is taken by --stage payout only"
[ "$STAGE" = payout-read ] || [ -z "$PAYOUT_ID" ] || usage_error "--payout is taken by --stage payout-read only"
if [ "$STAGE" != stripe-get ]; then
  [ -z "$STRIPE_PATH" ] || usage_error "--path is taken by --stage stripe-get only"
fi
case "$STAGE" in
  stripe-get|payout|payout-read) ;;
  *) [ -z "$STRIPE_ACCOUNT" ] || usage_error "--account is taken by --stage stripe-get, payout and payout-read only" ;;
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
  cancel-drafts)
    [ -z "$EVENT" ] || usage_error "--event is taken by --stage order-transition only"
    _cd_why=""
    if [ -z "$ORDERS" ]; then _cd_why="no list was given"
    else
      _cd_seen=" "
      IFS=, read -r -a _cd_list <<<"$ORDERS"
      [ "${#_cd_list[@]}" -gt 0 ] || _cd_why="the list is empty"
      [[ "$ORDERS" != *, && "$ORDERS" != ,* && "$ORDERS" != *,,* ]] || _cd_why="the list has an empty entry"
      for _cd_n in "${_cd_list[@]}"; do
        [[ "$_cd_n" =~ ^ORD-[A-Za-z0-9-]+$ ]] || { _cd_why="'$_cd_n' is not an order number"; break; }
        case "$_cd_seen" in *" $_cd_n "*) _cd_why="$_cd_n is named twice"; break ;; esac
        _cd_seen="$_cd_seen$_cd_n "
      done
    fi
    [ -z "$_cd_why" ] || usage_error "--stage cancel-drafts needs --orders ORD-a,ORD-b (the owner-confirmed list, D-02): $_cd_why" ;;
  cancel-orphan-intents)
    [ -z "$ORDERS" ] || usage_error "--orders is not taken by --stage cancel-orphan-intents"
    [ -z "$EVENT" ] || usage_error "--event is taken by --stage order-transition only"
    _ci_why=""
    if [ -z "$INTENTS" ]; then _ci_why="no list was given"
    else
      _ci_seen=" "
      IFS=, read -r -a _ci_list <<<"$INTENTS"
      [ "${#_ci_list[@]}" -gt 0 ] || _ci_why="the list is empty"
      [[ "$INTENTS" != *, && "$INTENTS" != ,* && "$INTENTS" != *,,* ]] || _ci_why="the list has an empty entry"
      for _ci_p in "${_ci_list[@]}"; do
        [[ "$_ci_p" =~ ^pi_[A-Za-z0-9]+$ ]] || { _ci_why="'$_ci_p' is not a PaymentIntent id"; break; }
        case "$_ci_seen" in *" $_ci_p "*) _ci_why="$_ci_p is named twice"; break ;; esac
        _ci_seen="$_ci_seen$_ci_p "
      done
    fi
    [ -z "$_ci_why" ] || usage_error "--stage cancel-orphan-intents needs --intents pi_a,pi_b (the owner-confirmed list, D-02): $_ci_why" ;;
  stripe-get)
    [ -z "$ORDERS" ] || usage_error "--orders is not taken by --stage stripe-get"
    [ -z "$EVENT" ] || usage_error "--event is taken by --stage order-transition only"
    [ -n "$STRIPE_PATH" ] || usage_error "--stage stripe-get needs --path /v1/<object>/<id>"
    [[ "$STRIPE_PATH" =~ $STRIPE_PATH_RE ]] \
      || refuse stripe-path "'$STRIPE_PATH' is not an allowlisted evidence path (webhook_endpoints, payment_intents, charges, refunds, transfers, payouts, accounts, subscriptions, invoices, events, balance); nothing was called"
    [ -z "$STRIPE_ACCOUNT" ] || [[ "$STRIPE_ACCOUNT" =~ ^acct_[A-Za-z0-9]+$ ]] \
      || usage_error "--account must be a connected account id (acct_ followed by letters and digits)" ;;
  connect-onboard)
    [ -z "$ORDERS" ] || usage_error "--orders is not taken by --stage connect-onboard"
    [ -z "$EVENT" ] || usage_error "--event is taken by --stage order-transition only"
    [ -n "$LINK_OUT" ] || usage_error "--stage connect-onboard needs --link-out FILE (the onboarding link is a single-use bearer URL and is written only there, mode 600)"
    [ ! -d "$LINK_OUT" ] || usage_error "--link-out '$LINK_OUT' is a directory; name a file"
    [ -d "$(dirname "$LINK_OUT")" ] || usage_error "--link-out: the directory of '$LINK_OUT' does not exist" ;;
  settlement-mode)
    [ -z "$ORDERS" ] || usage_error "--orders is not taken by --stage settlement-mode"
    [ -z "$EVENT" ] || usage_error "--event is taken by --stage order-transition only"
    case "$SET_MODE" in
      ""|DESTINATION_CHARGE_PLATFORM_MOR|DIRECT_CHARGE_VENDOR_MOR) ;;
      *) usage_error "--set must be DESTINATION_CHARGE_PLATFORM_MOR or DIRECT_CHARGE_VENDOR_MOR (got '$SET_MODE'); the money loop proves those two modes only" ;;
    esac ;;
  money-loop)
    [ -z "$ORDERS" ] || usage_error "--orders is not taken by --stage money-loop"
    [ -z "$EVENT" ] || usage_error "--event is taken by --stage order-transition only"
    case "$LOOP_MODE" in
      destination|direct) ;;
      "") usage_error "--stage money-loop needs --mode destination|direct" ;;
      *) usage_error "--mode must be destination or direct (got '$LOOP_MODE')" ;;
    esac
    LOOP_REFUND_PENNIES="${LOOP_REFUND_PENNIES:-100}"
    [[ "$LOOP_REFUND_PENNIES" =~ ^[1-9][0-9]{0,8}$ ]] \
      || usage_error "--refund-pennies must be a positive whole number of pennies (got '$LOOP_REFUND_PENNIES'): 0 < N < the captured amount, a partial refund"
    if [ -n "$LOOP_EVIDENCE" ]; then
      [ ! -d "$LOOP_EVIDENCE" ] || usage_error "--evidence '$LOOP_EVIDENCE' is a directory; name a file"
      [ -d "$(dirname "$LOOP_EVIDENCE")" ] || usage_error "--evidence: the directory of '$LOOP_EVIDENCE' does not exist"
    fi ;;
  payout|payout-read)
    [ -z "$ORDERS" ] || usage_error "--orders is not taken by --stage $STAGE"
    [ -z "$EVENT" ] || usage_error "--event is taken by --stage order-transition only"
    [ -n "$STRIPE_ACCOUNT" ] || usage_error "--stage $STAGE needs --account acct_… (the staging tenant's connected account)"
    [[ "$STRIPE_ACCOUNT" =~ ^acct_[A-Za-z0-9]+$ ]] \
      || usage_error "--account must be a connected account id (acct_ followed by letters and digits)"
    if [ "$STAGE" = payout ]; then
      [ -z "$PAYOUT_PENNIES" ] || [[ "$PAYOUT_PENNIES" =~ ^[1-9][0-9]{0,8}$ ]] \
        || usage_error "--amount-pennies must be a positive whole number of pennies (got '$PAYOUT_PENNIES'); the payout is min(N, the available balance)"
    else
      [[ "$PAYOUT_ID" =~ ^po_[A-Za-z0-9]+$ ]] || usage_error "--stage payout-read needs --payout po_… (the payout id a --stage payout run printed)"
    fi
    if [ -n "$LOOP_EVIDENCE" ]; then
      [ ! -d "$LOOP_EVIDENCE" ] || usage_error "--evidence '$LOOP_EVIDENCE' is a directory; name a file"
      [ -d "$(dirname "$LOOP_EVIDENCE")" ] || usage_error "--evidence: the directory of '$LOOP_EVIDENCE' does not exist"
    fi ;;
  pay-link)
    [ -z "$ORDERS" ] || usage_error "--orders is not taken by --stage pay-link"
    [ -z "$EVENT" ] || usage_error "--event is taken by --stage order-transition only"
    [ -n "$LINK_OUT" ] || usage_error "--stage pay-link needs --link-out FILE (the payment-link URL is a bearer credential and is written only there, mode 600)"
    [ ! -d "$LINK_OUT" ] || usage_error "--link-out '$LINK_OUT' is a directory; name a file"
    [ -d "$(dirname "$LINK_OUT")" ] || usage_error "--link-out: the directory of '$LINK_OUT' does not exist" ;;
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
  cancel-drafts)    cancel_drafts_stage ;;
  list-orphan-intents)   list_orphan_intents_stage ;;
  cancel-orphan-intents) cancel_orphan_intents_stage ;;
  stripe-get)       stripe_get_stage ;;
  pay-link)         pay_link_stage ;;
  billing)          billing_stage ;;
  billing-sync-check) billing_sync_check_stage ;;
  connect-onboard)  connect_onboard_stage ;;
  settlement-mode)  settlement_mode_stage ;;
  money-loop)       money_loop_stage ;;
  payout)           payout_stage ;;
  payout-read)      payout_read_stage ;;
  all)
    vendor_stage
    # The realm-admin operator exists only for the vendor stage: deleted here,
    # before the order, not left standing until exit.
    if [ "$OP_CREATED" -eq 1 ]; then delete_operator || exit 1; fi
    order_stage ;;
esac
