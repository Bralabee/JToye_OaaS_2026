#!/usr/bin/env bash
# baseline.sh — the minimum HONEST load baseline. Two arms, both status-asserted.
#
# WHY THIS EXISTS (Phase 27, plan 27-00 Task 6 — GAP 3 / issue #115 / backlog P3-13)
#
#   "No load-test baseline of any kind ... every peak-load claim is untested." Two facts,
#   both measured, explain why the file that was already here did not close it:
#
#     1. Baseline B-5: `hey`, `ab`, `k6`, `wrk`, `vegeta`, `siege` and `locust` were ALL
#        missing from this host. load-test.sh selects between `hey` and `ab` and exits 1 if
#        neither is present, so it had never produced a number here.
#     2. load-test.sh asserts NO status code. An unauthenticated `GET /api/v1/shops` returns
#        401 (measured — the live app's own metrics carry status="401",uri="/api/v1/shops"),
#        and a 401 flood produces EXCELLENT req/s. A harness without a status assertion
#        scores rejection as throughput. That is the canonical vacuous baseline, and it is
#        why arm A fails on any non-2xx no matter how good the numbers look.
#
#   load-test.sh is left FUNCTIONALLY UNCHANGED — it is working prior art (its
#   KC_SEED_USER_PASSWORD handling is reused here verbatim). This script extends it; it does
#   not replace it.
#
# THE TWO ARMS, AND THE ASSERTION THAT MAKES EACH ONE NON-VACUOUS
#
#   Arm A (HTTP)  `hey` against GET /api/v1/shops and /api/v1/products?page=0&size=20 with a
#                 real bearer token. FAILS if any response is not 2xx. Reports p95 per endpoint.
#   Arm B (AMQP)  publish N synthetic messages to each queue in $QUEUES, poll until drained,
#                 report wall-clock and messages/sec/consumer. FAILS if the queue's DLQ grew.
#
#   Arm B's DLQ assertion is the exact analogue of arm A's status assertion and is equally
#   load-bearing: a queue that reaches 0 because every message DIED is indistinguishable from
#   one that reached 0 because every message was PROCESSED, unless you watch the dead-letter
#   queue. Without it, this harness would score message destruction as throughput.
#
# WHY THE SYNTHETIC MEDIA PAYLOAD IS SAFE, AND WHAT IT REALLY MEASURES
#
#   MediaProcessingEvent is (UUID tenantId, UUID assetId) and MediaProcessingWorker re-reads
#   the asset by id, treating the DB as the source of truth. A random assetId therefore logs
#   `asset_not_visible` and ACKS — no dead-letter, no MinIO object, no DB write. So the
#   default payload exercises the REAL consumer path (AMQP delivery -> tenant GUC set_config
#   -> repository lookup -> ack) without creating state that 27-01 and 27-03 would then
#   measure against. What it does NOT measure is image transcoding; that is 27-04's arm, which
#   extends this one (D-B) rather than forking it.
#
# GUARDING THE SHARED STACK — READ THIS BEFORE CHANGING ARM B
#
#   Arm B publishes into the LIVE dev broker that sibling plans measure. Two protections:
#
#     * A queue whose pre-run depth is NOT ZERO is REFUSED, not drained. Real work in flight
#       would both corrupt the measurement and make the cleanup trap unsafe.
#     * Because of that refusal, everything in the queue at exit is ours, so the trap may
#       purge it. Pre-run and post-run depths of every touched queue are recorded in the
#       artifact and asserted equal.
#
#   This matters concretely: `webhook.deliveries.dlq` currently holds NINE real dead vendor
#   webhook events (finding F-2), and 27-03's proof counts exactly nine. An abandoned run that
#   left messages behind would silently corrupt that.
#
# NOT IN CI, DELIBERATELY (D-11). It needs a running compose stack and a real broker, and it
# publishes into shared queues. See README.md.
#
# EXIT CODES
#   0 = within budget · 1 = an assertion failed · 2 = VOID (could not measure)
#
#   Tool absence is VOID, never a skip and never a "fall back to something simpler" — that is
#   the "reports success while executing nothing" shape.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

# ---------------------------------------------------------------- configuration
API_BASE_URL="${API_BASE_URL:-http://localhost:9090}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8085}"
REALM="${REALM:-jtoye-dev}"
# MEASURED 2026-07-27 — a second reason load-test.sh could never have produced a number, and
# it took two steps to find:
#
#   1. load-test.sh requests a token with `client_id=core-api` and NO client secret. core-api
#      is a CONFIDENTIAL client, so Keycloak answers "Invalid client or Invalid client
#      credentials" for every user. No token, no load test.
#   2. Switching to `test-client` (the realm's public direct-access client) DOES yield a
#      token — and every request with it still returns 401, at 3395 req/s. The reason is that
#      core-java requires `aud: core-api` (jtoye.security.jwt.expected-audience) and only the
#      core-api client carries the `core-api-audience-mapper`; test-client has just the
#      tenant-id-mapper, so its tokens have `aud: null`.
#
# So the working path is core-api WITH its secret, injected from .env exactly as the broker
# credentials are. Point 2 is the whole argument for arm A's status assertion: a token that
# authenticates fine and authorizes nowhere produces a flawless-looking throughput number.
CLIENT_ID="${CLIENT_ID:-core-api}"
TEST_USER="${TEST_USER:-tenant-a-user}"
# NOTE: the seed-user password is resolved AFTER the tool check, not here. See the ORDERING
# note in the tooling section — a credential guard placed at this point makes the tool VOID
# unreachable, which is how AC-6.1 first failed.

# MEASURED 2026-07-27: the binding constraint on arm A is this platform's OWN rate limiter,
# not the application. Bucket4j is configured at 100 req/min per tenant with a burst of 20, so
# a 500-request run returns `[200]=120 [429]=380` — and because both endpoints share ONE
# per-tenant bucket, the second endpoint then returns 500x429 outright.
#
# The defaults below therefore sit UNDER the bucket, so arm A measures the application. That
# is a deliberate trade: this baseline answers "what is p95 under light concurrency", not
# "what is peak throughput". Measuring the latter means disabling the limiter
# (RATE_LIMIT_ENABLED=false) and REBUILDING core-java — at which point you are no longer
# measuring the deployed configuration, which is why it is not the default. Recorded in the
# artifact so the number is never read as a throughput ceiling.
TOTAL_REQUESTS="${TOTAL_REQUESTS:-100}"
CONCURRENT_USERS="${CONCURRENT_USERS:-5}"
# Seconds to wait between arm-A endpoints so the shared per-tenant token bucket refills.
# Set to 0 only when the limiter is disabled.
RATE_LIMIT_PAUSE="${RATE_LIMIT_PAUSE:-65}"

# Arm B is PARAMETERISED so 27-04 can extend it rather than fork it (D-B, AC-6.9).
QUEUES="${QUEUES:-media.process webhook.deliveries}"
AMQP_MESSAGES="${AMQP_MESSAGES:-200}"
DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-60}"
RABBIT_MGMT="${RABBIT_MGMT:-http://localhost:15672}"
RABBIT_CONTAINER="${RABBIT_CONTAINER:-jtoye-rabbitmq}"
BUDGET="${BUDGET:-infra/load-testing/budget.yaml}"
ARTIFACT_DIR="${ARTIFACT_DIR:-infra/load-testing/baselines}"

void() { echo "VOID: $*" >&2; exit 2; }

[ "${1:-}" = "--help" ] && { command sed -n '2,60p' "$0"; exit 0; }

# ---------------------------------------------------------------- tooling (VOID, exit 2)
if ! command -v hey >/dev/null 2>&1; then
  cat >&2 <<'MISSING'
VOID: `hey` is not installed, so arm A cannot generate load.

  This is exit 2 (VOID), NOT a skip and NOT a pass. A load baseline that reports success
  while executing nothing is worse than no baseline, because it reads as coverage.

  Install it (go 1.26.5 is present on this host and $GOPATH/bin is already populated):

      go install github.com/rakyll/hey@latest

  Then ensure "$(go env GOPATH)/bin" is on your PATH.
MISSING
  exit 2
fi

for t in curl jq docker git; do
  command -v "$t" >/dev/null 2>&1 || void "required tool not on PATH: $t"
done
[ -r "$BUDGET" ] || void "budget not readable: $BUDGET"

# ---------------------------------------------------------------- credentials (VOID, exit 2)
#
# ORDERING, AND WHY IT IS NOT COSMETIC. load-test.sh:28 resolves the password at config time
# with `${KC_SEED_USER_PASSWORD:?...}`, which aborts the shell with exit 1. Placed before the
# tool check, that made AC-6.1 unobservable: on a host with no `hey` AND no exported password,
# the script exited 1 on the credential instead of 2 on the missing tool — measured, this is
# exactly how the criterion first failed. Resolution therefore happens HERE, after tooling.
#
# The mechanism from load-test.sh is preserved (env var or .env, never a literal); the exit
# code is deliberately CHANGED to 2, because "we could not measure" must never share an exit
# code with "we measured and it failed".
if [ -z "${TEST_PASSWORD:-}" ] && [ -z "${KC_SEED_USER_PASSWORD:-}" ] && [ -r "$REPO_ROOT/.env" ]; then
  KC_SEED_USER_PASSWORD=$(command grep -E '^KC_SEED_USER_PASSWORD=' "$REPO_ROOT/.env" | command cut -d= -f2- | command tr -d '"' || true)
fi
TEST_PASSWORD="${TEST_PASSWORD:-${KC_SEED_USER_PASSWORD:-}}"
[ -n "$TEST_PASSWORD" ] || void "KC_SEED_USER_PASSWORD or TEST_PASSWORD must be set (or present in .env) — cannot authenticate, so nothing can be measured"

if [ -z "${CLIENT_SECRET:-}" ] && [ -r "$REPO_ROOT/.env" ]; then
  CLIENT_SECRET=$(command grep -E '^KEYCLOAK_CLIENT_SECRET=' "$REPO_ROOT/.env" | command cut -d= -f2- | command tr -d '"' || true)
fi
[ -n "${CLIENT_SECRET:-}" ] || void "KEYCLOAK_CLIENT_SECRET not resolvable from .env — core-api is a confidential client and cannot mint a token without it"

# Broker credentials come from the repo .env, never from a literal here.
if [ -r "$REPO_ROOT/.env" ]; then
  RABBIT_USER="${RABBIT_USER:-$(command grep -E '^RABBITMQ_USER=' "$REPO_ROOT/.env" | command cut -d= -f2- | command tr -d '"' || true)}"
  RABBIT_PASS="${RABBIT_PASS:-$(command grep -E '^RABBITMQ_PASSWORD=' "$REPO_ROOT/.env" | command cut -d= -f2- | command tr -d '"' || true)}"
fi
[ -n "${RABBIT_USER:-}" ] && [ -n "${RABBIT_PASS:-}" ] || void "RABBITMQ_USER / RABBITMQ_PASSWORD not resolvable from .env"

TS_START=$(date -u +%Y-%m-%dT%H:%M:%SZ)
GIT_SHA=$(git rev-parse --short HEAD)
ARTIFACT="$ARTIFACT_DIR/$(date +%F)-$GIT_SHA.md"
mkdir -p "$ARTIFACT_DIR"

FAILURES=0
fail() { echo "FAIL: $*"; FAILURES=$((FAILURES+1)); }

# ---------------------------------------------------------------- queue helpers
qdepth() {
  docker exec "$RABBIT_CONTAINER" rabbitmqctl list_queues name messages 2>/dev/null \
    | command awk -v q="$1" '$1==q {print $2; found=1} END{ if(!found) print "ABSENT" }'
}

publish_n() {
  # $1 queue, $2 count. Routed through amq.default with routing_key=<queue>, which delivers
  # straight to the queue by name — generic across queues, so no queue is hardcoded.
  local queue="$1" n="$2" i payload body
  for ((i=0; i<n; i++)); do
    payload=$(printf '{"tenantId":"%s","assetId":"%s"}' "$(cat /proc/sys/kernel/random/uuid)" "$(cat /proc/sys/kernel/random/uuid)")
    payload="${PAYLOAD:-$payload}"
    body=$(jq -nc --arg rk "$queue" --arg p "$payload" \
      '{properties:{content_type:"application/json"},routing_key:$rk,payload:$p,payload_encoding:"string"}')
    curl -s -u "$RABBIT_USER:$RABBIT_PASS" -X POST \
      "$RABBIT_MGMT/api/exchanges/%2F/amq.default/publish" \
      -H 'content-type: application/json' -d "$body" >/dev/null || return 1
  done
}

TOUCHED_QUEUES=()
cleanup() {
  # Only queues this run PUBLISHED into are touched, and each was proven empty beforehand, so
  # anything left is ours. Never purge a DLQ — those hold real evidence.
  local q
  for q in "${TOUCHED_QUEUES[@]:-}"; do
    [ -n "$q" ] || continue
    local d; d=$(qdepth "$q")
    if [ "$d" != "ABSENT" ] && [ "${d:-0}" -gt 0 ] 2>/dev/null; then
      echo "cleanup: purging $d leftover synthetic message(s) from $q"
      docker exec "$RABBIT_CONTAINER" rabbitmqctl purge_queue "$q" >/dev/null 2>&1 || true
    fi
  done
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------- token
# TOKEN is overridable so AC-6.2 can be performed: the arm needs to run arm A with a
# deliberately invalid token and observe that an excellent req/s still FAILS. A break that
# cannot be performed is not evidence.
if [ -n "${TOKEN:-}" ]; then
  echo "note: using a caller-supplied TOKEN (override active — this is the AC-6.2 path)"
else
TOKEN=$(curl -s -X POST "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" -d "client_id=$CLIENT_ID" \
  --data-urlencode "client_secret=$CLIENT_SECRET" \
  -d "username=$TEST_USER" --data-urlencode "password=$TEST_PASSWORD" | jq -r '.access_token')
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || void "could not obtain a JWT from $KEYCLOAK_URL (realm=$REALM user=$TEST_USER)"
fi

# ---------------------------------------------------------------- arm A
ARM_A_RAW=""
ARM_A_ROWS=""
ENDPOINTS=("/api/v1/shops" "/api/v1/products?page=0&size=20")

for ep in "${ENDPOINTS[@]}"; do
  out=$(hey -n "$TOTAL_REQUESTS" -c "$CONCURRENT_USERS" -H "Authorization: Bearer $TOKEN" "$API_BASE_URL$ep" 2>&1)
  ARM_A_RAW="$ARM_A_RAW"$'\n'"### $ep"$'\n'"$out"

  # MEASURED: hey 0.1.5 emits a LITERAL double percent in its own latency table —
  # `  95%% in 0.0056 secs` (confirmed with cat -A). A `/95% in/` pattern therefore matches
  # nothing and the p95 silently reads 0.0, which is exactly the "extract the value, do not
  # count the label" failure AC-6.5 exists to catch: `grep -c 'p95'` would still have passed.
  # `%%?` tolerates one or two percent signs so a future hey that fixes it keeps working.
  p95=$(printf '%s\n' "$out" | command awk '/95%%? in/ {print $3}' | command head -1)
  rps=$(printf '%s\n' "$out" | command awk '/Requests\/sec:/ {print $2}' | command head -1)
  # Status distribution: every `[NNN] N responses` line.
  dist=$(printf '%s\n' "$out" | command awk '/^  \[[0-9]+\]/ {gsub(/[][]/,"",$1); printf "%s=%s ", $1, $2}')
  [ -n "$dist" ] || { fail "arm A $ep: hey produced NO status-code distribution — refusing to report a throughput number that was never classified"; dist="<none>"; }

  nonhex=0
  for pair in $dist; do
    code="${pair%%=*}"; n="${pair##*=}"
    case "$code" in
      2*) ;;
      \<none\>) ;;
      *) nonhex=$((nonhex+n));;
    esac
  done
  p95_ms=$(command awk -v s="${p95:-0}" 'BEGIN{printf "%.1f", s*1000}')
  ARM_A_ROWS="$ARM_A_ROWS| \`$ep\` | ${p95_ms} | ${rps:-0} | $dist |"$'\n'

  if [ "$nonhex" -gt 0 ]; then
    hint=""
    case "$dist" in
      *401=*) hint=" 401 means the token authenticated but did not authorize — check aud=core-api." ;;
      *429=*) hint=" 429 is THIS platform's rate limiter (100 req/min/tenant, burst 20), shared across endpoints. Lower TOTAL_REQUESTS, raise RATE_LIMIT_PAUSE, or measure with the limiter disabled and say so." ;;
    esac
    fail "arm A $ep: $nonhex non-2xx response(s) [$dist] — at ${rps:-0} req/s. Speed is not success.$hint"
  fi

  # Let the shared per-tenant bucket refill before the next endpoint.
  if [ "$ep" != "${ENDPOINTS[-1]}" ] && [ "${RATE_LIMIT_PAUSE:-0}" -gt 0 ] 2>/dev/null; then
    echo "  (pausing ${RATE_LIMIT_PAUSE}s for the per-tenant rate-limit bucket to refill)"
    sleep "$RATE_LIMIT_PAUSE"
  fi
done

# ---------------------------------------------------------------- arm B
ARM_B_ROWS=""
ARM_B_NOTES=""

for q in $QUEUES; do
  dlq="$q.dlq"
  pre=$(qdepth "$q"); pre_dlq=$(qdepth "$dlq")

  if [ "$pre" = "ABSENT" ]; then
    fail "arm B $q: queue does not exist on the broker"
    ARM_B_ROWS="$ARM_B_ROWS| \`$q\` | ABSENT | - | - | - | - |"$'\n'
    continue
  fi
  if [ "${pre:-0}" -ne 0 ]; then
    fail "arm B $q: pre-run depth is $pre, not 0 — REFUSING to publish. Real work is in flight; measuring through it would be wrong and the cleanup trap could not safely purge."
    ARM_B_ROWS="$ARM_B_ROWS| \`$q\` | $pre | - | - | - | refused |"$'\n'
    continue
  fi

  TOUCHED_QUEUES+=("$q")
  t0=$(date +%s.%N)
  publish_n "$q" "$AMQP_MESSAGES" || { fail "arm B $q: publish failed"; continue; }

  drained=0
  depth_at_timeout=""
  end=$(( $(date +%s) + DRAIN_TIMEOUT ))
  while [ "$(date +%s)" -lt "$end" ]; do
    d=$(qdepth "$q")
    if [ "$d" = "0" ]; then drained=1; break; fi
    depth_at_timeout="$d"
    sleep 0.5
  done
  t1=$(date +%s.%N)
  wall=$(command awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.2f", b-a}')

  consumers=$(docker exec "$RABBIT_CONTAINER" rabbitmqctl list_queues name consumers 2>/dev/null \
              | command awk -v qq="$q" '$1==qq {print $2}')
  consumers="${consumers:-1}"; [ "$consumers" -gt 0 ] 2>/dev/null || consumers=1
  rate=$(command awk -v n="$AMQP_MESSAGES" -v w="$wall" -v c="$consumers" 'BEGIN{ if(w>0) printf "%.2f", n/w/c; else print "0" }')

  post=$(qdepth "$q"); post_dlq=$(qdepth "$dlq")

  if [ "$drained" -ne 1 ]; then
    # Report the depth AT THE TIMEOUT, not the depth now. Spring AMQP retries a failing
    # message before dead-lettering it, and those retries hold it as UNACKED — which
    # `list_queues messages` still counts. So a poisoned batch can sit at full depth past the
    # timeout and then land in the DLQ moments later, making a "still $post" message read as
    # the self-contradictory "did not drain (depth still 0)". Measured during AC-6.3.
    fail "arm B $q: did not drain within ${DRAIN_TIMEOUT}s (depth at timeout: ${depth_at_timeout:-unknown}; depth now: $post — if these differ, messages were being retried and then dead-lettered)"
  fi
  if [ "$post_dlq" != "$pre_dlq" ]; then
    fail "arm B $q: DLQ '$dlq' grew from $pre_dlq to $post_dlq — the queue reached 0 by DEAD-LETTERING, not by processing. That is message destruction scored as throughput."
  fi
  if [ "$post" != "$pre" ]; then
    fail "arm B $q: post-run depth $post != pre-run depth $pre — the shared stack was not left as found"
  fi

  ARM_B_ROWS="$ARM_B_ROWS| \`$q\` | $pre -> $post | $pre_dlq -> $post_dlq | ${wall}s | $consumers | ${rate} |"$'\n'
  ARM_B_NOTES="$ARM_B_NOTES  messages/sec/consumer for $q: $rate"$'\n'
done

# ---------------------------------------------------------------- budget comparison
BUDGET_ROWS=""
read_p95_budget=$(command awk '/key: http.read.p95_ms/{f=1} f&&/value:/{print $2; exit}' "$BUDGET")
[ -n "$read_p95_budget" ] || void "could not read http.read.p95_ms out of $BUDGET — refusing to report a verdict against a budget that was not parsed"

# Each row is labelled with its endpoint. Two rows reading `http.read.p95_ms | 200 | ... ` with
# no subject cannot be acted on.
while IFS='|' read -r _ ep_col p95_col _rest; do
  [ -n "${ep_col// /}" ] || continue
  ep_name="${ep_col// /}"; p95_val="${p95_col// /}"
  case "$ep_name" in ''|endpoint|---*) continue;; esac
  [ -n "$p95_val" ] || continue
  within=$(command awk -v v="$p95_val" -v b="$read_p95_budget" 'BEGIN{print (v+0 > 0 && v+0 <= b+0) ? "PASS" : "FAIL"}')
  BUDGET_ROWS="$BUDGET_ROWS| http.read.p95_ms | $read_p95_budget | $ep_name = $p95_val | $within |"$'\n'
  if [ "$within" = "FAIL" ]; then
    if command awk -v v="$p95_val" 'BEGIN{exit !(v+0 == 0)}'; then
      fail "budget http.read.p95_ms: $ep_name measured p95 of ZERO — a zero p95 is an extraction failure, not a fast response"
    else
      fail "budget http.read.p95_ms: $ep_name measured ${p95_val}ms, exceeding ${read_p95_budget}ms"
    fi
  fi
done <<<"$ARM_A_ROWS"

# ---------------------------------------------------------------- artifact
HOST_CPU=$(command grep -c '^processor' /proc/cpuinfo || echo "?")
HOST_MEM=$(command awk '/MemTotal/{printf "%.1f GiB", $2/1048576}' /proc/meminfo || echo "?")
LOADAVG=$(command cut -d' ' -f1-3 /proc/loadavg || echo "?")
# hey 0.1.5 has no --version flag (bare `hey` prints usage), so read the module version out of
# the binary itself rather than recording the usage banner as a "version".
HEY_VER=$(go version -m "$(command -v hey)" 2>/dev/null | command awk '$1=="mod" {print $2" "$3; exit}')
HEY_VER="${HEY_VER:-unknown (go version -m unavailable)}"

digests=""
for svc in jtoye_oaas_2026-core-java-1 jtoye-postgres jtoye-rabbitmq; do
  d=$(docker inspect --format '{{.Image}}' "$svc" 2>/dev/null || echo "absent")
  digests="$digests  $svc: $d"$'\n'
done

{
  echo "# Load baseline — $(date +%F) @ $GIT_SHA"
  echo
  echo "Generated by \`infra/load-testing/baseline.sh\` at $TS_START."
  echo "**This artifact contains no credentials.** The bearer token and broker password are"
  echo "never echoed; AC-6.10 asserts that with a working control."
  echo
  echo "## Command"
  echo '```'
  echo "TOTAL_REQUESTS=$TOTAL_REQUESTS CONCURRENT_USERS=$CONCURRENT_USERS \\"
  echo "AMQP_MESSAGES=$AMQP_MESSAGES QUEUES=\"$QUEUES\" \\"
  echo "  bash infra/load-testing/baseline.sh"
  echo '```'
  echo
  echo "## Host and runtime identity"
  echo '```'
  echo "cpu cores : $HOST_CPU"
  echo "memory    : $HOST_MEM"
  echo "loadavg   : $LOADAVG"
  echo "hey       : $HEY_VER"
  echo "image ids :"
  printf '%s' "$digests"
  echo '```'
  echo
  echo "## Arm A — HTTP (status-asserted)"
  echo
  echo "| endpoint | p95 (ms) | req/s | status distribution |"
  echo "|---|---|---|---|"
  printf '%s' "$ARM_A_ROWS"
  echo
  echo "## Arm B — AMQP consumer drain (DLQ-asserted)"
  echo
  echo "| queue | depth pre -> post | DLQ pre -> post | wall | consumers | msg/s/consumer |"
  echo "|---|---|---|---|---|---|"
  printf '%s' "$ARM_B_ROWS"
  echo
  printf '%s' "$ARM_B_NOTES"
  echo
  echo "## Budget"
  echo
  echo "| key | budget | measured | verdict |"
  echo "|---|---|---|---|"
  printf '%s' "$BUDGET_ROWS"
  echo
  echo "## Verdict"
  echo
  if [ "$FAILURES" -eq 0 ]; then echo "**PASS** — every arm asserted and within budget."
  else echo "**FAIL** — $FAILURES assertion(s) failed. See the arm tables above."; fi
  echo
  echo "## Raw arm-A output"
  echo '```'
  printf '%s\n' "$ARM_A_RAW"
  echo '```'
} > "$ARTIFACT"

echo
echo "artifact: $ARTIFACT"
printf '%s' "$ARM_B_NOTES"

if [ "$FAILURES" -gt 0 ]; then
  echo "FAILED: $FAILURES assertion(s)."
  exit 1
fi
echo "OK: both arms asserted, within budget."
exit 0
