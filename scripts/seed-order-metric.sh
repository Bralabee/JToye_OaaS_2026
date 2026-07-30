#!/usr/bin/env bash
# ---------------------------------------------------------------------------------
# seed-order-metric.sh — materialise the counter that NoOrdersCreated alerts on.
#
# WHY THIS EXISTS
#   `NoOrdersCreated` fires on
#     increase(http_server_requests_seconds_count{uri=~".../orders",method="POST",status="201"}[30m]) < 1
#   and `increase()` needs the series to EXIST. That series is a Micrometer *request*
#   counter: created the first time such a request is served, destroyed when core-java
#   restarts. It is NOT a database fact — seeding an order row does not create it, and
#   no read endpoint does either. Measured 2026-07-30: the series was present
#   10:00:10–11:35:10Z, vanished when core-java was rebuilt at ~11:38Z, and one
#   GET /api/v1/shops then took the total series count 3 -> 4, confirming that counters
#   materialise only on the first matching request.
#
#   So on a stack that has been rebuilt — which this project mandates after any code
#   change — the alert that would tell you orders have stopped cannot fire at all, and
#   check-alert-metrics.sh M-1 correctly goes red. That gate's header says the right fix
#   is to place an order rather than re-add an exemption. This script is that fix in
#   committed, repeatable form: the ritual was previously retyped by hand, which is
#   exactly how the inline merge guard came to be wrong twelve times unnoticed.
#
# WHAT IT DOES NOT DO
#   It does not touch the alert rule and it does not weaken any gate. It places one real
#   guest order through the public storefront endpoint — the same path a customer uses.
#
# CONFIGURATION (GLOBAL_RULE_6 — nothing environment-varying is hardcoded; slugs,
# product ids and the shop's minimum order value are all DISCOVERED at run time)
#   CORE_URL        default http://localhost:9090   core-java base URL
#   PROM_URL        default http://localhost:9091   Prometheus base URL
#   SHOP_SLUG       default: discovered             override to target a specific shop
#   VERIFY          default 1                       0 = skip the Prometheus confirmation
#   SERIES_TIMEOUT  default 90                      seconds to wait for the scrape
#   MAX_QTY         default 50                      refuse absurd orders (see below)
#
# EXIT CODES — uniform with this repo's other gates
#   0 = the counter series exists (already, or because this run created it)
#   1 = the order was accepted but the series did not appear within the timeout
#   2 = VOID, could not evaluate: missing tooling, unreachable core/Prometheus, no
#       published shop, no purchasable product, or a non-201 from the order endpoint.
#       "Could not tell" is never reported as success.
# ---------------------------------------------------------------------------------
set -uo pipefail

CORE_URL="${CORE_URL:-http://localhost:9090}"
PROM_URL="${PROM_URL:-http://localhost:9091}"
VERIFY="${VERIFY:-1}"
SERIES_TIMEOUT="${SERIES_TIMEOUT:-90}"
MAX_QTY="${MAX_QTY:-50}"
RULES_FILE="${RULES_FILE:-infra/monitoring/prometheus/alerts.yml}"

# Kept character-identical to the matcher in the alert rule. If they drift, this script
# would happily create a series the rule does not watch — so §0 below asserts it.
SELECTOR='http_server_requests_seconds_count{uri=~"/api/v[0-9]+/orders|/public/shops/[^/]+/orders",method="POST",status="201"}'

void()      { echo "VOID: $*" >&2; exit 2; }
violation() { echo "FAIL: $*" >&2; exit 1; }

echo "seed-order-metric  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  core       : $CORE_URL"
echo "  prometheus : $PROM_URL"

for t in curl jq; do
  command -v "$t" >/dev/null 2>&1 || void "missing required tool: $t"
done

# --- 0. the selector must still match the rule -------------------------------------
# Silent drift here would make every later assertion measure the wrong series.
if [ -f "$RULES_FILE" ]; then
  if ! grep -q 'uri=~"/api/v\[0-9\]+/orders|/public/shops/\[^/\]+/orders"' "$RULES_FILE"; then
    void "cannot find the NoOrdersCreated uri matcher in $RULES_FILE — the rule moved or changed. Update SELECTOR in this script before trusting it."
  fi
else
  echo "  NOTE: $RULES_FILE not found — selector/rule agreement NOT checked."
fi

# --- 1. is the series already there? ------------------------------------------------
series_count() {
  local out rc
  out=$(curl -sfG --max-time 15 "$PROM_URL/api/v1/query" --data-urlencode "query=$SELECTOR" 2>/dev/null); rc=$?
  [ "$rc" -ne 0 ] && return 1
  # Here-string, never `| jq`: under pipefail a pipe into an early-exiting reader
  # inverts the status (SIGPIPE -> 141). Same reason the sibling gates do it.
  jq -e -r 'select(.status=="success") | .data.result | length' <<< "$out" 2>/dev/null
}

before=$(series_count) || void "cannot query Prometheus at $PROM_URL — an unreachable or unparseable Prometheus is a VOID, not a pass"
echo "  series before : $before"
if [ "$before" -gt 0 ]; then
  echo "PASS: the counter already exists ($before series) — nothing to seed."
  exit 0
fi

# --- 2. find a published shop with a purchasable product ----------------------------
shops=$(curl -sf --max-time 15 "$CORE_URL/public/shops") \
  || void "cannot read $CORE_URL/public/shops — is core-java up?"

slugs=$(jq -r '.content[]?.slug // empty' <<< "$shops") \
  || void "could not parse the shop list from $CORE_URL/public/shops"
[ -n "${SHOP_SLUG:-}" ] && slugs="$SHOP_SLUG"
[ -z "$slugs" ] && void "no published shops returned — nothing to order, so this run would prove nothing"

slug=""; product=""; qty=0; minimum=0
while read -r s; do
  [ -z "$s" ] && continue
  shop=$(curl -sf --max-time 15 "$CORE_URL/public/shops/$s") || continue
  # A missing/null minimum means "no minimum", not "unknown" — the storefront treats it
  # as 0, so mirroring that is correct rather than defensive.
  min=$(jq -r '.minimumOrderPennies // 0' <<< "$shop"); [ "$min" = "null" ] && min=0

  prods=$(curl -sf --max-time 15 "$CORE_URL/public/shops/$s/products") || continue
  # Pick the DEAREST available product: it reaches the minimum in the fewest units, so
  # the synthetic order stays small. `available == false` is exclusion; a missing field
  # means the DTO does not project it, which is not the same as unavailable.
  line=$(jq -r '
      [ .[]?[]? | select(.available != false) | select(.id != null and .pricePennies != null and .pricePennies > 0) ]
      | sort_by(-.pricePennies) | .[0] | select(. != null) | "\(.id) \(.pricePennies)"
    ' <<< "$prods" 2>/dev/null)
  [ -z "$line" ] && continue

  pid=${line% *}; price=${line#* }
  # ceil(min / price), at least 1
  q=$(( (min + price - 1) / price )); [ "$q" -lt 1 ] && q=1
  if [ "$q" -gt "$MAX_QTY" ]; then
    echo "  skip $s: would need ${q} x ${price}p to clear a ${min}p minimum (> MAX_QTY=$MAX_QTY)"
    continue
  fi
  slug=$s; product=$pid; qty=$q; minimum=$min
  break
done <<< "$slugs"

[ -z "$slug" ] && void "no published shop had a purchasable product that can clear its minimum order value within MAX_QTY=$MAX_QTY — nothing was ordered, so this run proves nothing"

echo "  shop       : $slug (minimum ${minimum}p)"
echo "  product    : $product x $qty"

# --- 3. place one real guest order --------------------------------------------------
# COLLECTION deliberately: it needs no delivery address, so this is the smallest request
# that still exercises the real path and yields a 201.
key="metric-seed-$(date -u +%Y%m%d%H%M%S)-$$"
body=$(jq -n --arg k "$key" --arg p "$product" --argjson q "$qty" '{
  customerName:   "Metric Seed Probe",
  customerEmail:  "metric-seed@jtoye.local",
  customerPhone:  "+441234567890",
  notes:          "Synthetic order from scripts/seed-order-metric.sh to materialise the NoOrdersCreated counter. Safe to delete.",
  idempotencyKey: $k,
  fulfilmentType: "COLLECTION",
  items: [{productId: $p, quantity: $q}]
}') || void "could not build the request body"

tmp=$(mktemp)
code=$(curl -s -o "$tmp" -w '%{http_code}' --max-time 30 \
  -X POST "$CORE_URL/public/shops/$slug/orders" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $key" \
  --data "$body"); rc=$?
payload=$(cat "$tmp" 2>/dev/null); rm -f "$tmp"

[ "$rc" -ne 0 ] && void "the order POST itself failed (curl rc=$rc) — no verdict"
if [ "$code" != "201" ]; then
  void "order endpoint returned HTTP $code, not 201. The alert counts status=\"201\" only, so a non-201 seeds nothing. Body: $(head -c 400 <<< "$payload")"
fi

order_no=$(jq -r '.orderNumber // empty' <<< "$payload" 2>/dev/null)
echo "  placed     : HTTP 201  ${order_no:-<no orderNumber in body>}"

# --- 4. prove the counter actually appeared -----------------------------------------
# The 201 is not the deliverable; the SERIES is. Prometheus must scrape first, so wait.
if [ "$VERIFY" != "1" ]; then
  echo "NOTE: VERIFY=0 — order placed but the series was NOT confirmed. This is not a pass."
  exit 0
fi

deadline=$(( $(date +%s) + SERIES_TIMEOUT ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  now=$(series_count) || void "Prometheus became unreadable while waiting for the series"
  if [ "$now" -gt 0 ]; then
    echo "  series after  : $now"
    echo "PASS: the NoOrdersCreated counter now exists — the rule can fire."
    exit 0
  fi
  sleep 5
done

violation "order ${order_no:-<unknown>} was accepted (201) but the selector still matches ZERO series after ${SERIES_TIMEOUT}s. The order path works and the metric does not, so the alert stays blind — check Prometheus' scrape of core-java (target up, scrape_interval, metric_relabel_configs)."
