#!/usr/bin/env bash
# ---------------------------------------------------------------------------------
# seed-order-metric.sh — place one real order through the public storefront path and
# confirm the order counter NoOrdersCreated reads moved.
#
# WHAT CHANGED IN #973 — READ THIS BEFORE USING IT AS A DEPLOY STEP
#   `NoOrdersCreated` now fires on
#     sum(increase(jtoye_orders_total[30m])) < 1
#   (summed across pods: the counter is per pod and a competing consumer, so one pod counts
#   each order and the others stay at 0).
#   `jtoye_orders_total` is the `jtoye.orders.created` counter, registered in the
#   BusinessMetricsService constructor, so it exists at 0.0 from core-java's first scrape.
#   (The exporter strips the reserved `_created` suffix; there is no
#   jtoye_orders_created_total.) The rule used to read a Micrometer *request* counter
#   (http_server_requests_seconds_count, POST 201) that was created by the first matching
#   request and destroyed on restart, so a rebuilt stack had no series, the alert was
#   blind and check-alert-metrics.sh M-1 went red until this script placed an order.
#   That is no longer true: M-1 is green on a fresh stack with zero orders, and this
#   script is NOT needed after a deploy.
#
#   It is kept because it proves the ORDER PATH end to end (storefront POST -> outbox ->
#   AMQP consumer -> counter -> scrape), and because FORCE=1 is still the way to clear a
#   FIRING NoOrdersCreated. The counter moves only when the order reaches PENDING through
#   the outbox and the consumer, so the confirmation below also fails if that path stalls.
#   Each FORCE=1 run writes a real order row, which on staging is a write the owner approves.
#
# IT ONLY WORKS ON A CASH (COD) STACK. When Stripe is configured (acceptsCardPayments is
#   true on the public shop, which staging with test keys is) a storefront order gets a
#   clientSecret and stays DRAFT until the Stripe webhook confirms payment; it publishes
#   no PENDING event, so the counter never moves and the probe cannot succeed. The script
#   reads acceptsCardPayments from the public shop BEFORE writing anything and exits VOID
#   (2) when it is true; if the flag is absent or wrong and the 201 body carries a
#   clientSecret it exits VOID too, and says that a DRAFT order was left behind.
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
#   FORCE           default 0                       1 = place an order even if the series exists
#   ALERT_WINDOW    default 30m                     must match NoOrdersCreated's increase() range
#   POLL_INTERVAL   default 5                       seconds between Prometheus polls
#
# TWO JOBS, AND THEY ARE NOT THE SAME — this distinction has already produced a wrong
# claim, so it is stated here rather than left to be rediscovered:
#
#   default (FORCE=0)  asks only whether the series EXISTS (the old check-alert-metrics
#                      M-1 question) and never writes. The series is registered at core-java
#                      startup, so if it is ABSENT the cause is the scrape (target down, wrong
#                      job, relabelling), not a missing order: this exits VOID with that
#                      diagnosis and places nothing. Only FORCE=1 places an order.
#   FORCE=1            clears a FIRING `NoOrdersCreated`, which is a different condition:
#                      `sum(increase(...[30m])) < 1` needs a RECENT order. FORCE=1 asserts
#                      the ALERT's own aggregated condition, `sum(increase(...)) >= 1`, not
#                      that some single pod's series moved.
#
# SELFTEST
#   bash scripts/seed-order-metric.sh --selftest   runs this script against a stubbed curl
#   (cash shop, card shop, card detected only in the 201 body, absent series, FORCE=0, and
#   a stub where only the per-pod query would pass) and needs no stack. Wired into CI.
#
# EXIT CODES — uniform with this repo's other gates
#   0 = FORCE=0: the counter series exists. FORCE=1: an order was placed and the alert's
#       own condition (the summed increase over ALERT_WINDOW) is now met.
#   1 = the order was accepted but the counter did not move within the timeout
#   2 = VOID, could not evaluate: missing tooling, unreachable core/Prometheus, the series
#       absent (scrape problem), a card-payment (Stripe) stack, no published shop, no
#       purchasable product, or a non-201 from the order endpoint.
#       "Could not tell" is never reported as success.
# ---------------------------------------------------------------------------------
set -uo pipefail

CORE_URL="${CORE_URL:-http://localhost:9090}"
PROM_URL="${PROM_URL:-http://localhost:9091}"
VERIFY="${VERIFY:-1}"
SERIES_TIMEOUT="${SERIES_TIMEOUT:-90}"
MAX_QTY="${MAX_QTY:-50}"
FORCE="${FORCE:-0}"
ALERT_WINDOW="${ALERT_WINDOW:-30m}"   # must match NoOrdersCreated's increase() range
POLL_INTERVAL="${POLL_INTERVAL:-5}"
RULES_FILE="${RULES_FILE:-infra/monitoring/prometheus/alerts.yml}"

# Kept identical to the series the alert rule reads. If they drift, this script
# would happily create a series the rule does not watch — so §0 below asserts it.
SELECTOR='jtoye_orders_total'

void()      { echo "VOID: $*" >&2; exit 2; }
violation() { echo "FAIL: $*" >&2; exit 1; }

# =================================================================================
# --selftest — run THIS script against a stubbed curl. No stack, no Prometheus, no writes.
#
# The stub serves canned core-java and Prometheus responses from files and logs every call, so
# each arm can assert both the exit code AND whether an order was POSTed. It tells the per-pod
# query from the summed one by the leading `sum(`: the "per-pod-only" arm returns a rise for the
# un-summed expression and nothing for the summed one, so a script that asks the wrong question
# passes it and a script that asks the rule's question fails it. Every arm is an EXPECTED
# outcome; a harness that could not fail would show all PASS, so the arms deliberately mix
# rc 0, 1 and 2.
# =================================================================================
selftest() {
  local self d fails=0 n=0
  self="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  [ -n "${SEED_SELFTEST_SCRIPT:-}" ] && self="$SEED_SELFTEST_SCRIPT"
  d="$(mktemp -d)" || { echo "VOID: mktemp failed" >&2; return 2; }
  mkdir -p "$d/bin"
  cat > "$d/bin/curl" <<'STUB'
#!/usr/bin/env bash
# stub curl for seed-order-metric --selftest: STUB_DIR holds the canned responses.
url=""; method=GET; out=""; query=""
while [ $# -gt 0 ]; do
  case "$1" in
    -X) method="$2"; shift ;;
    -o) out="$2"; shift ;;
    --data-urlencode) query="${2#query=}"; shift ;;
    http*) url="$1" ;;
  esac
  shift
done
if [ "$method" = POST ]; then
  echo "POST $url" >> "$STUB_DIR/calls.log"
  [ -n "$out" ] && cp "$STUB_DIR/order-resp.json" "$out"
  cat "$STUB_DIR/order-code"; exit 0
fi
case "$url" in
  */api/v1/query)
    echo "QUERY $query" >> "$STUB_DIR/calls.log"
    case "$query" in sum\(*) f=prom-sum.json ;; *'>= 1'*|*'< 1'*) f=prom-cond.json ;; *) f=prom-series.json ;; esac
    [ -r "$STUB_DIR/$f" ] || exit 22; cat "$STUB_DIR/$f" ;;
  */products) cat "$STUB_DIR/products.json" ;;
  */public/shops/*) cat "$STUB_DIR/shop.json" ;;
  */public/shops) cat "$STUB_DIR/shops.json" ;;
  *) exit 22 ;;
esac
STUB
  chmod +x "$d/bin/curl"
  local one='{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[0,"1"]}]}}'
  local none='{"status":"success","data":{"resultType":"vector","result":[]}}'
  printf '%s\n' 'sum(increase(jtoye_orders_total[30m])) < 1' > "$d/rules.yml"
  printf '%s' '{"content":[{"slug":"shop-a"}]}' > "$d/shops.json"
  printf '%s' '{"all":[{"id":"p1","pricePennies":500,"allergenMask":0,"available":true}]}' > "$d/products.json"

  # arm NAME FORCE cards series sum cond order-body EXPECT-RC EXPECT-POST(0/1) EXPECT-TEXT
  arm() {
    local name="$1" force="$2" cards="$3" series="$4" sumr="$5" cond="$6" body="$7" erc="$8" epost="$9" etext="${10}"
    local out rc posted ok=1 why=""
    : > "$d/calls.log"
    printf '{"minimumOrderPennies":0,"acceptsCardPayments":%s}' "$cards" > "$d/shop.json"
    [ "$cards" = absent ] && printf '%s' '{"minimumOrderPennies":0}' > "$d/shop.json"
    printf '%s' "$series" > "$d/prom-series.json"; printf '%s' "$sumr" > "$d/prom-sum.json"; printf '%s' "$cond" > "$d/prom-cond.json"
    printf '%s' "$body" > "$d/order-resp.json"; printf '201' > "$d/order-code"
    out=$(STUB_DIR="$d" PATH="$d/bin:$PATH" CORE_URL=http://core PROM_URL=http://prom RULES_FILE="$d/rules.yml" \
          FORCE="$force" SERIES_TIMEOUT=1 POLL_INTERVAL=1 bash "$self" 2>&1); rc=$?
    posted=0; grep -q '^POST ' "$d/calls.log" && posted=1
    n=$((n+1))
    [ "$rc" = "$erc" ] || { ok=0; why="rc=$rc want $erc; "; }
    [ "$posted" = "$epost" ] || { ok=0; why="${why}order POSTed=$posted want $epost; "; }
    grep -qF -- "$etext" <<<"$out" || { ok=0; why="${why}output lacks '$etext'; "; }
    if [ "$ok" = 1 ]; then echo "  PASS  $name (rc=$rc, POSTed=$posted)"; else echo "  FAIL  $name — $why"; echo "$out" | sed 's/^/        | /' | head -12; fails=$((fails+1)); fi
  }
  echo "seed-order-metric --selftest  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
  arm cash-force-places-and-clears            1 false "$one"  "$one"  "$one"  '{"orderNumber":"ORD-1"}'                         0 1 "PASS: an order reached PENDING"
  arm card-shop-refused-before-ordering       1 true  "$one"  "$one"  "$one"  '{"orderNumber":"ORD-1"}'                         2 0 "acceptsCardPayments=true"
  arm card-seen-only-in-201-body-is-void      1 absent "$one" "$one"  "$one"  '{"orderNumber":"ORD-1","clientSecret":"pi_x"}'   2 1 "LEFT BEHIND"
  arm series-missing-force-is-scrape-void     1 false "$none" "$one"  "$one"  '{"orderNumber":"ORD-1"}'                         2 0 "scrape/target problem"
  arm series-missing-default-is-scrape-void   0 false "$none" "$one"  "$one"  '{"orderNumber":"ORD-1"}'                         2 0 "Nothing was ordered"
  arm default-never-writes                    0 false "$one"  "$one"  "$one"  '{"orderNumber":"ORD-1"}'                         0 0 "no order was placed"
  # the summed query returns nothing while the un-summed one would pass: only the rule's own question fails it
  arm per-pod-rise-but-aggregate-unmet-fails  1 false "$one"  "$none" "$one"  '{"orderNumber":"ORD-1"}'                         1 1 "still matches nothing"
  grep -q 'QUERY sum(increase(jtoye_orders_total\[30m\])) >= 1' "$d/calls.log" \
    || { echo "  FAIL  the verification did not ask the summed question"; fails=$((fails+1)); }
  rm -rf "$d"
  if [ "$fails" -gt 0 ]; then echo "FAILED: $fails of $n selftest arm(s)"; return 1; fi
  echo "PASS: $n/$n selftest arm(s)"
  return 0
}
if [ "${1:-}" = "--selftest" ]; then selftest; exit $?; fi

echo "seed-order-metric  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  core       : $CORE_URL"
echo "  prometheus : $PROM_URL"

for t in curl jq; do
  command -v "$t" >/dev/null 2>&1 || void "missing required tool: $t"
done

# --- 0. the selector must still match the rule -------------------------------------
# Silent drift here would make every later assertion measure the wrong series.
if [ -f "$RULES_FILE" ]; then
  if ! grep -qF 'sum(increase(jtoye_orders_total[' "$RULES_FILE"; then
    void "cannot find the summed NoOrdersCreated expression sum(increase(jtoye_orders_total[...])) in $RULES_FILE — the rule moved or changed. Update SELECTOR/ALERT_CLEARED in this script before trusting it."
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

# Counts samples for an arbitrary expression, so the FORCE path can assert the ALERT's
# condition rather than merely the series' existence.
expr_count() {
  local out rc
  out=$(curl -sfG --max-time 15 "$PROM_URL/api/v1/query" --data-urlencode "query=$1" 2>/dev/null); rc=$?
  [ "$rc" -ne 0 ] && return 1
  jq -e -r 'select(.status=="success") | .data.result | length' <<< "$out" 2>/dev/null
}

before=$(series_count) || void "cannot query Prometheus at $PROM_URL — an unreachable or unparseable Prometheus is a VOID, not a pass"
echo "  series before : $before"
if [ "$before" -eq 0 ]; then
  # jtoye_orders_total is registered in the BusinessMetricsService constructor, so core-java
  # exposes it at 0.0 from its first scrape. Absent means Prometheus is not getting core-java's
  # /actuator/prometheus, and an order cannot fix that (it would also write a business row
  # for nothing). Say what to look at and place nothing, in either mode.
  void "Prometheus has NO jtoye_orders_total series. core-java registers it at startup, so this is a scrape/target problem, not a missing order: check up{job=\"core-java\"}, the scrape config and relabelling, and 'curl <core-java>/actuator/prometheus' for jtoye_orders_total. Nothing was ordered."
fi
if [ "$FORCE" != "1" ]; then
  # DEFAULT PATH — the series exists, so check-alert-metrics M-1 is satisfied. This does NOT
  # silence a firing NoOrdersCreated: that needs a RECENT order (summed increase over 30m >= 1),
  # not merely an existing series. Use FORCE=1 for that (cash stacks only).
  echo "PASS: the counter series exists ($before series) — nothing to seed; no order was placed."
  echo "NOTE: this does NOT clear a firing NoOrdersCreated. That alert needs a RECENT order"
  echo "      (summed increase over $ALERT_WINDOW >= 1). To place one:  FORCE=1 bash scripts/seed-order-metric.sh"
  exit 0
fi
echo "  FORCE=1       : placing an order (target: clear a firing NoOrdersCreated)"

# --- 2. find a published shop with a purchasable product ----------------------------
shops=$(curl -sf --max-time 15 "$CORE_URL/public/shops") \
  || void "cannot read $CORE_URL/public/shops — is core-java up?"

slugs=$(jq -r '.content[]?.slug // empty' <<< "$shops") \
  || void "could not parse the shop list from $CORE_URL/public/shops"
[ -n "${SHOP_SLUG:-}" ] && slugs="$SHOP_SLUG"
[ -z "$slugs" ] && void "no published shops returned — nothing to order, so this run would prove nothing"

slug=""; product=""; qty=0; minimum=0; allergen_mask=""
while read -r s; do
  [ -z "$s" ] && continue
  shop=$(curl -sf --max-time 15 "$CORE_URL/public/shops/$s") || continue
  # A missing/null minimum means "no minimum", not "unknown" — the storefront treats it
  # as 0, so mirroring that is correct rather than defensive.
  min=$(jq -r '.minimumOrderPennies // 0' <<< "$shop"); [ "$min" = "null" ] && min=0
  # CARD STACK: refuse BEFORE writing anything. With Stripe configured the order stays DRAFT and
  # publishes no PENDING event, so the counter this probe waits for can never move.
  cards=$(jq -r '.acceptsCardPayments // "unknown"' <<< "$shop" 2>/dev/null)
  if [ "$cards" = "true" ]; then
    void "shop $s reports acceptsCardPayments=true: Stripe is configured, so a storefront order would get a clientSecret and stay DRAFT until the payment webhook, publishing no PENDING event. jtoye_orders_total would not move and an unpaid DRAFT order would be left behind. This probe only works on a cash (COD) stack. Nothing was ordered."
  fi
  [ "$cards" = "unknown" ] && echo "  NOTE: shop $s does not report acceptsCardPayments — the 201 body is checked for a clientSecret instead."

  prods=$(curl -sf --max-time 15 "$CORE_URL/public/shops/$s/products") || continue
  # Pick the DEAREST available product: it reaches the minimum in the fewest units, so
  # the synthetic order stays small. `available == false` is exclusion; a missing field
  # means the DTO does not project it, which is not the same as unavailable.
  # 31.1-03 (#784): the order must also acknowledge the product's DECLARED allergen set, so
  # only a product whose allergenMask is a number on this menu can be ordered; the mask is
  # read from the same menu JSON the storefront renders, never assumed.
  line=$(jq -r '
      [ .[]?[]? | select(.available != false) | select(.id != null and .pricePennies != null and .pricePennies > 0)
        | select((.allergenMask | type) == "number") ]
      | sort_by(-.pricePennies) | .[0] | select(. != null) | "\(.id) \(.pricePennies) \(.allergenMask)"
    ' <<< "$prods" 2>/dev/null)
  [ -z "$line" ] && continue

  read -r pid price pmask <<< "$line"
  # ceil(min / price), at least 1
  q=$(( (min + price - 1) / price )); [ "$q" -lt 1 ] && q=1
  if [ "$q" -gt "$MAX_QTY" ]; then
    echo "  skip $s: would need ${q} x ${price}p to clear a ${min}p minimum (> MAX_QTY=$MAX_QTY)"
    continue
  fi
  slug=$s; product=$pid; qty=$q; minimum=$min; allergen_mask=$pmask
  break
done <<< "$slugs"

[ -z "$slug" ] && void "no published shop had a purchasable product that can clear its minimum order value within MAX_QTY=$MAX_QTY — nothing was ordered, so this run proves nothing"

echo "  shop       : $slug (minimum ${minimum}p)"
echo "  product    : $product x $qty (declared allergen mask $allergen_mask)"

# --- 3. place one real guest order --------------------------------------------------
# COLLECTION deliberately: it needs no delivery address, so this is the smallest request
# that still exercises the real path and yields a 201.
# acknowledgedAllergenMask (31.1-03, #784): a one-product basket's declared set IS that
# product's mask. Missing, the server answers 422; stale (the vendor edited it since the
# menu was read), 409 — both are a non-201 and VOID below with the server's own body.
# expectedUnitPricePennies / expectedTotalPennies (37-32, D-16): the prices this script SAW on
# the same menu JSON — the line's pricePennies, and the total that line makes with COLLECTION's
# £0 delivery fee. Read from the menu, never assumed: a vendor reprice between the menu read and
# the POST is a 409 basket-changed (37-33), again a non-201 and a VOID with the server's body.
key="metric-seed-$(date -u +%Y%m%d%H%M%S)-$$"
body=$(jq -n --arg k "$key" --arg p "$product" --argjson q "$qty" --argjson m "$allergen_mask" --argjson price "$price" '{
  customerName:   "Metric Seed Probe",
  customerEmail:  "metric-seed@jtoye.local",
  customerPhone:  "+441234567890",
  notes:          "Synthetic order from scripts/seed-order-metric.sh to probe the order path and the NoOrdersCreated counter. Safe to delete.",
  idempotencyKey: $k,
  fulfilmentType: "COLLECTION",
  acknowledgedAllergenMask: $m,
  expectedTotalPennies: ($price * $q),
  items: [{productId: $p, quantity: $q, expectedUnitPricePennies: $price}]
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
  void "order endpoint returned HTTP $code, not 201, so no order was placed and nothing was probed. Body: $(head -c 400 <<< "$payload")"
fi

order_no=$(jq -r '.orderNumber // empty' <<< "$payload" 2>/dev/null)
echo "  placed     : HTTP 201  ${order_no:-<no orderNumber in body>}"
# The shop did not say it takes cards but the server answered with a payment intent: this is the
# card branch after all. The order is a DRAFT awaiting payment and the counter will not move.
if [ -n "$(jq -r '.clientSecret // empty' <<< "$payload" 2>/dev/null)" ]; then
  void "order ${order_no:-<unknown>} was accepted with a clientSecret: this is a card-payment (Stripe) stack, so it is a DRAFT awaiting payment and publishes no PENDING event; jtoye_orders_total will not move and this probe cannot succeed. The DRAFT order was LEFT BEHIND — cancel or delete it."
fi

# --- 4. prove the counter actually moved --------------------------------------------
# The 201 is not the deliverable; the ALERT's own condition is. The rule is aggregated across
# pods (one pod counts each order, the others stay at 0), so assert the AGGREGATE, the same
# expression the rule evaluates, not that some single series moved. Prometheus must scrape first.
if [ "$VERIFY" != "1" ]; then
  echo "NOTE: VERIFY=0 — order placed but the counter was NOT confirmed. This is not a pass."
  exit 0
fi

ALERT_CLEARED="sum(increase($SELECTOR[$ALERT_WINDOW])) >= 1"

deadline=$(( $(date +%s) + SERIES_TIMEOUT ))
while :; do
  cleared=$(expr_count "$ALERT_CLEARED") || void "Prometheus became unreadable while waiting for the alert condition"
  if [ "$cleared" -gt 0 ]; then
    echo "  $ALERT_CLEARED : met"
    echo "PASS: an order reached PENDING and was counted inside the alert window; NoOrdersCreated will resolve."
    exit 0
  fi
  [ "$(date +%s)" -ge "$deadline" ] && break
  sleep "$POLL_INTERVAL"
done

violation "order ${order_no:-<unknown>} was accepted (201) but '$ALERT_CLEARED' still matches nothing after ${SERIES_TIMEOUT}s, so NoOrdersCreated would keep firing. The counter moves only after the outbox and the AMQP consumer have processed the PENDING event, and increase() needs two scrapes inside the window to show a rise — allow a scrape interval, then check the outbox flusher and the order-event consumer. A DRAFT order left by a card path would explain it too (that path is refused up front when the shop reports acceptsCardPayments)."
