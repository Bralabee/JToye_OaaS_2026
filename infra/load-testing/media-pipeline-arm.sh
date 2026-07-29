#!/usr/bin/env bash
# media-pipeline-arm.sh — the media-pipeline measurement arm (27-04 T2).
#
# ADDITIVE to 27-00's baseline.sh. It does NOT replace it and does not duplicate it.
# Hard-depends on 27-00 Task 6's artifacts and exits 2 (VOID) if they are absent.
#
# ---------------------------------------------------------------------------
# WHAT THIS MEASURES THAT 27-00's ARM B CANNOT
# ---------------------------------------------------------------------------
# 27-00 arm B publishes SYNTHETIC MediaProcessingEvent messages with a random assetId.
# baseline.sh documents exactly why that is safe — the worker re-reads the asset by id,
# finds nothing, logs `asset_not_visible` and ACKs. That is precisely the limitation:
# the synthetic path never touches quarantine, never sniffs magic bytes, never decodes,
# and never runs EITHER WebP encode. It measures AMQP delivery, not the media pipeline.
#
# This arm differs in four ways, each of which 27-00's arm B structurally cannot supply:
#
#   1. REAL HTTP uploads of REAL JPEGs through POST /api/v1/products/{id}/image, so the
#      full pipeline runs: quarantine -> magic-byte sniff -> bomb guard -> decode-verify ->
#      EXIF strip -> resize -> WebP derivative -> WebP thumbnail -> MinIO -> CoW placement.
#   2. The container is PINNED TO 1 CPU, because the k8s pod limit is 1000m
#      (k8s/base/core-java-deployment.yaml) and scrimage-webp forks a native cwebp per
#      encode. An unpinned workstation number does not transfer to that pod (finding A6).
#   3. It samples PER-CONSUMER prefetch_count and unacked distribution — the evidence for
#      the A5 unfair-distribution defect, which a queue-depth-only sample cannot show.
#   4. It reads the jtoye.media.process Timer that 27-04 T1 adds. Per-message service time
#      exists nowhere else: media_asset has created_at but no updated_at (V53), so it
#      cannot be derived from the database.
#
# ---------------------------------------------------------------------------
# THE RATE LIMITER IS PART OF THE MEASUREMENT, NOT AN OBSTACLE TO IT
# ---------------------------------------------------------------------------
# This platform rate-limits at RATE_LIMIT_PER_MINUTE (100) per tenant with a burst of
# RATE_LIMIT_BURST (20) — application.yml:431-434. baseline.sh measured that a 500-request
# run returns [200]=120 [429]=380, so an unpaced driver here would measure Bucket4j and
# report it as media throughput.
#
# It is left ENABLED and the driver paces INSIDE it. That is defensible rather than merely
# convenient: measured service time is ~0.9 s/message at concurrency 1 (probe, unpinned),
# i.e. ~1.1 msg/s, while the limiter admits 1.67/s. Arrival still exceeds service, so the
# queue genuinely backs up and the fairness/prefetch behaviour is observable — without
# disabling a production control and without scoring 429s as uploads.
#
# EVERY non-202 upload is counted and reported, and a run whose 202 count is below
# MIN_ACCEPTED is VOID. A harness that does not assert the accept status scores rejection
# as throughput — the exact vacuity 27-00 arm A exists to prevent.
#
# ---------------------------------------------------------------------------
# Usage:  ./infra/load-testing/media-pipeline-arm.sh <arm-label> [uploads]
#   e.g.  ./infra/load-testing/media-pipeline-arm.sh A-baseline 200
#
# Env overrides: API_BASE_URL KEYCLOAK_URL REALM CLIENT_ID TEST_USER
#                RABBITMQ_USER RABBITMQ_PASSWORD MGMT_URL PIN_CPUS
# Credentials come from .env exactly as baseline.sh does — never a literal here.
#
# Exit codes: 0 = arm completed and recorded, 1 = a hard assertion failed,
#             2 = VOID (tooling, prerequisites, unreachable API, empty series).
set -euo pipefail

ARM_LABEL="${1:-unlabelled}"
UPLOADS="${2:-200}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

void() { echo "VOID: $*" >&2; exit 2; }
fail() { echo "FAIL: $*" >&2; exit 1; }

# ---------------------------------------------------------------- prerequisites (D-B)
# 27-00 Task 6 must have landed. Do NOT improvise a harness if it has not.
BUDGET="$REPO_ROOT/infra/load-testing/budget.yaml"
BASELINES="$REPO_ROOT/infra/load-testing/baselines"
[ -f "$BUDGET" ]   || void "27-00's infra/load-testing/budget.yaml is absent — this arm is additive to it, not a replacement"
[ -d "$BASELINES" ] || void "27-00's infra/load-testing/baselines/ is absent"

for tool in docker curl jq awk convert; do
    command -v "$tool" >/dev/null 2>&1 || void "required tool '$tool' not found (no install is attempted — T-27-SC)"
done

# ---------------------------------------------------------------- config
API_BASE_URL="${API_BASE_URL:-http://localhost:9090}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8085}"
REALM="${REALM:-jtoye-dev}"
CLIENT_ID="${CLIENT_ID:-core-api}"
TEST_USER="${TEST_USER:-tenant-a-user}"
MGMT_URL="${MGMT_URL:-http://localhost:15672}"
PIN_CPUS="${PIN_CPUS:-1}"
QUEUE="media.process"
DLQ="media.process.dlq"
DRAIN_BUDGET_S="${DRAIN_BUDGET_S:-180}"

# Bind credentials without printing them (T-27-07).
set -a; . "$REPO_ROOT/.env"; set +a
: "${KC_SEED_USER_PASSWORD:?KC_SEED_USER_PASSWORD must be set in .env}"
: "${KEYCLOAK_CLIENT_SECRET:?KEYCLOAK_CLIENT_SECRET must be set in .env}"
: "${RABBITMQ_USER:?RABBITMQ_USER must be set in .env}"
: "${RABBITMQ_PASSWORD:?RABBITMQ_PASSWORD must be set in .env}"

# ---------------------------------------------------------------- step 0: resolve container
# NEVER `docker exec jtoye-core-java` — docker-compose.full-stack.yml:170 removed
# container_name to support --scale, so that container does not exist (finding H).
# Hardcoding the project-prefixed name instead would embed the directory name and
# break under --scale, so it is DERIVED. An empty result is VOID, never a pass.
CORE_CID="$(docker compose -f docker-compose.full-stack.yml ps -q core-java | head -1)"
[ -n "$CORE_CID" ] || void "core-java container not running (derived id was empty)"
CORE_NAME="$(docker inspect -f '{{.Name}}' "$CORE_CID")"

# ---------------------------------------------------------------- step 1: CPU pin, trap FIRST
# D-06 / T-27-08. The trap is installed BEFORE the first `docker update`, because the
# failure mode is silent and delayed: an abandoned run leaves the pin in place and every
# later measurement in 27-01 and 27-03 is CPU-starved with no error anywhere.
#
# TWO CORRECTIONS TO THE PLAN'S AC-5, both MEASURED on Docker 29.6.2 (see the summary):
#
#   1. `docker update --cpus=0` does NOT release the pin. It exits 0 and changes nothing.
#      The release that works is `--cpu-quota=-1`.
#   2. `docker inspect -f '{{.HostConfig.NanoCpus}}'` — the instrument AC-5 names — is
#      STALE METADATA and cannot discriminate. Measured across three states:
#         released -> cpu.max "max 100000",    NanoCpus 1000000000
#         pinned   -> cpu.max "100000 100000", NanoCpus 1000000000
#         released -> cpu.max "max 100000",    NanoCpus 1000000000
#      NanoCpus is identical in all three. An AC-5 written on it can never reach its pass
#      direction, and "fixing" that by deleting the assertion is how the pin gets left on.
#
# The authoritative instrument is the container's OWN cgroup: `/sys/fs/cgroup/cpu.max`
# reads "<quota> <period>" when limited and "max <period>" when not. That is read from
# inside the delivered container, which is the same principle as reading application.yml
# out of the running jar rather than trusting the source tree.
cpu_max()      { docker exec "$CORE_CID" cat /sys/fs/cgroup/cpu.max 2>/dev/null || echo "unknown"; }
cpu_is_pinned() { [[ "$(cpu_max)" != max\ * ]]; }

trap 'docker update --cpu-quota=-1 "$CORE_CID" >/dev/null 2>&1 || true' EXIT INT TERM
docker update --cpus="$PIN_CPUS" "$CORE_CID" >/dev/null
CPUMAX_PINNED="$(cpu_max)"
cpu_is_pinned || void "CPU pin did not take effect (cpu.max=$CPUMAX_PINNED) — an unpinned number does not transfer to a 1000m pod (finding A6)"

# ---------------------------------------------------------------- broker helpers
rmq() {
    curl -sf -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" "$MGMT_URL/api/queues/%2F/$1" \
        || void "broker management API unreachable at $MGMT_URL (queue $1)"
}
queue_depth()   { rmq "$1" | jq -r '.messages // 0'; }
queue_unacked() { rmq "$1" | jq -r '.messages_unacknowledged // 0'; }
queue_consumers() { rmq "$1" | jq -r '.consumers // 0'; }
# Per-consumer prefetch — the A5 unfair-distribution evidence. One entry per consumer,
# so at concurrency N this is N comma-separated values and the spread between them is the
# fairness signal that a queue-depth-only sample cannot show.
consumer_prefetches() { rmq "$1" | jq -r '[.consumer_details[]?.prefetch_count] | join(",")'; }

# ---------------------------------------------------------------- auth
# The token MUST be refreshed during the run. Measured: a 200-upload arm outlives the
# access-token lifetime, and the tail of the run returned [401]=26 — uploads that never
# reached the media pipeline at all but would have been counted as "drive" by any harness
# that did not assert the status. Refreshed on an interval well inside the lifetime.
TOKEN=""
TOKEN_FETCHED_AT=0
TOKEN_REFRESH_S="${TOKEN_REFRESH_S:-60}"
fetch_token() {
    TOKEN="$(curl -s -X POST "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "grant_type=password" -d "client_id=$CLIENT_ID" \
        --data-urlencode "client_secret=$KEYCLOAK_CLIENT_SECRET" \
        -d "username=$TEST_USER" --data-urlencode "password=$KC_SEED_USER_PASSWORD" | jq -r '.access_token')"
    [ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || void "could not obtain a JWT (realm=$REALM user=$TEST_USER)"
    TOKEN_FETCHED_AT="$(date +%s)"
}
maybe_refresh_token() {
    local now; now="$(date +%s)"
    if [ $(( now - TOKEN_FETCHED_AT )) -ge "$TOKEN_REFRESH_S" ]; then fetch_token; fi
}
fetch_token

PRODUCT_ID="$(curl -s -H "Authorization: Bearer $TOKEN" "$API_BASE_URL/api/v1/products?page=0&size=1" | jq -r '.content[0].id // empty')"
[ -n "$PRODUCT_ID" ] || void "no product available to upload against"

# ---------------------------------------------------------------- payloads
# Mixed dimensions, all under the 40 MP decompression-bomb cap, ~1.5 MB each so the
# encode cost is representative rather than trivial.
WORK="$(mktemp -d)"
trap 'docker update --cpu-quota=-1 "$CORE_CID" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT INT TERM
for dims in 2400x1800 3000x2000 1920x1080 2560x1440; do
    convert -size "$dims" plasma:fractal -quality 92 "$WORK/img-$dims.jpg" 2>/dev/null \
        || void "could not generate a test JPEG at $dims"
done
IMGS=("$WORK"/img-*.jpg)
[ "${#IMGS[@]}" -ge 1 ] || void "no test payloads generated"

# ---------------------------------------------------------------- baselines before the run
DLQ_BEFORE="$(queue_depth "$DLQ")"
scrape_timer() {
    # count and sum for outcome=$1, from the RUNNING app's scrape endpoint.
    local outcome="$1" body
    body="$(curl -sf "$API_BASE_URL/actuator/prometheus")" || void "actuator scrape unreachable"
    [ -n "$body" ] || void "actuator scrape returned an EMPTY body — unparseable is VOID, not zero"
    local c s
    c="$(awk -v o="$outcome" '$0 ~ "^jtoye_media_process_seconds_count\\{outcome=\""o"\"\\}" {print $2}' <<< "$body")"
    s="$(awk -v o="$outcome" '$0 ~ "^jtoye_media_process_seconds_sum\\{outcome=\""o"\"\\}"   {print $2}' <<< "$body")"
    [ -n "$c" ] || void "jtoye_media_process timer not found in the scrape — 27-04 T1 must be in the RUNNING image"
    echo "${c:-0} ${s:-0}"
}
read -r ACTIVE_C_BEFORE ACTIVE_S_BEFORE <<< "$(scrape_timer active)"
read -r FAILED_C_BEFORE FAILED_S_BEFORE <<< "$(scrape_timer failed)"

ARTIFACT="$BASELINES/$(date +%Y-%m-%d)-media-$ARM_LABEL.md"
SERIES="$WORK/series.tsv"
printf 'elapsed_s\tmessages\tunacked\tconsumers\tprefetch_counts\tcpu_pct\n' > "$SERIES"

echo "=== media-pipeline arm: $ARM_LABEL ==="
echo "container : $CORE_NAME ($CORE_CID)"
echo "cpu.max   : $CPUMAX_PINNED (pinned to ${PIN_CPUS} CPU; read from the container's own cgroup)"
echo "uploads   : $UPLOADS  product: $PRODUCT_ID"
echo "consumers before: $(queue_consumers "$QUEUE")   prefetch: $(consumer_prefetches "$QUEUE")"
echo ""

# ---------------------------------------------------------------- sampler (1 Hz)
START_EPOCH="$(date +%s)"
sample_once() {
    local now elapsed cpu
    now="$(date +%s)"; elapsed=$(( now - START_EPOCH ))
    cpu="$(docker stats --no-stream --format '{{.CPUPerc}}' "$CORE_CID" 2>/dev/null | tr -d '%' || echo "")"
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$elapsed" "$(queue_depth "$QUEUE")" "$(queue_unacked "$QUEUE")" \
        "$(queue_consumers "$QUEUE")" "$(consumer_prefetches "$QUEUE")" "${cpu:-NA}" >> "$SERIES"
}

# ---------------------------------------------------------------- step 2: drive
# Paced INSIDE the platform rate limit (see the header). Every status code is counted.
declare -A CODES=()
ACCEPTED=0
PACE_S="${PACE_S:-0.62}"     # ~1.6/s, just inside RATE_LIMIT_PER_MINUTE=100
BURST="${BURST:-20}"         # = RATE_LIMIT_BURST (application.yml:434)

# WHY THERE IS A BURST PHASE, AND WHY IT IS NOT CHEATING THE LIMITER.
# Measured at N=4: paced arrival (1.61/s) barely exceeds service (~1.5/s at 1 CPU), so the
# queue never reaches a depth at which prefetch or fairness is observable — peak depth 0.
# That is a real property worth stating (one tenant cannot saturate the pipeline at its own
# rate limit), but it makes the A5 evidence unobtainable.
#
# Bucket4j's configured BURST CAPACITY is 20, so 20 back-to-back uploads are what the
# platform genuinely permits — this consumes the real allowance rather than bypassing it.
# It builds the backlog, then the run settles into the sustained paced rate. Both phases are
# recorded separately so neither is mistaken for the other.
upload_one() {
    local i="$1" img code
    img="${IMGS[$(( i % ${#IMGS[@]} ))]}"
    code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API_BASE_URL/api/v1/products/$PRODUCT_ID/image" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Idempotency-Key: ${ARM_LABEL}-${START_EPOCH}-${i}" \
        -F "file=@${img}" || echo "000")"
    CODES[$code]=$(( ${CODES[$code]:-0} + 1 ))
    # An `if`, NOT `[ ... ] && ACCEPTED=...`. The && form returns 1 whenever the code is
    # not 202, and as the function's LAST command that return value becomes the function's,
    # which `set -e` turns into a silent abort. It survived two smoke runs because every
    # upload there was 202; the first 429 of the real run killed the harness with no message.
    if [ "$code" = "202" ]; then
        ACCEPTED=$(( ACCEPTED + 1 ))
    fi
}

# The burst is driven CONCURRENTLY. Measured with a sequential driver: peak depth 0 and
# peak unacked 0 across 200 uploads — because a single client posting 1.5 MB multipart
# bodies is itself slower than the consumer (612 ms/msg), so the queue drains as fast as it
# is fed and the A5 fairness behaviour is unobservable. That is a real property worth
# recording (one sequential client cannot saturate the pipeline), but it is not the
# condition 27-04 needs to measure. Concurrent clients are also the realistic shape:
# production has many vendors uploading at once, and the per-tenant limiter does not
# serialise them.
BURST_CONC="${BURST_CONC:-8}"
BURST_ACTUAL=$(( BURST < UPLOADS ? BURST : UPLOADS ))
BURST_CODES="$WORK/burst-codes.txt"
: > "$BURST_CODES"
for i in $(seq 1 "$BURST_ACTUAL"); do
    (
        img="${IMGS[$(( i % ${#IMGS[@]} ))]}"
        c="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API_BASE_URL/api/v1/products/$PRODUCT_ID/image" \
            -H "Authorization: Bearer $TOKEN" \
            -H "Idempotency-Key: ${ARM_LABEL}-${START_EPOCH}-b${i}" \
            -F "file=@${img}" || echo "000")"
        echo "$c" >> "$BURST_CODES"
    ) &
    # Bound in-flight uploads so the driver does not become the bottleneck it is measuring.
    while [ "$(jobs -rp | wc -l)" -ge "$BURST_CONC" ]; do sleep 0.05; done
done
# Sample WHILE the burst is still in flight — the peak is transient and a sample taken
# after `wait` would miss it entirely.
for _ in $(seq 1 15); do sample_once; sleep 0.4; done
wait
while read -r c; do
    CODES[$c]=$(( ${CODES[$c]:-0} + 1 ))
    if [ "$c" = "202" ]; then ACCEPTED=$(( ACCEPTED + 1 )); fi
done < "$BURST_CODES"
BURST_PEAK_DEPTH="$(queue_depth "$QUEUE")"
BURST_PEAK_UNACKED="$(queue_unacked "$QUEUE")"
echo "burst phase: $BURST_ACTUAL uploads -> depth=$BURST_PEAK_DEPTH unacked=$BURST_PEAK_UNACKED prefetch=[$(consumer_prefetches "$QUEUE")]"

for i in $(seq $(( BURST_ACTUAL + 1 )) "$UPLOADS"); do
    maybe_refresh_token
    upload_one "$i"
    sample_once
    sleep "$PACE_S"
done

echo "upload status distribution:"
for c in "${!CODES[@]}"; do echo "  [$c] = ${CODES[$c]}"; done

# A run that never got messages in cannot say anything about consumer behaviour.
MIN_ACCEPTED="${MIN_ACCEPTED:-$(( UPLOADS / 2 ))}"
[ "$ACCEPTED" -ge "$MIN_ACCEPTED" ] \
    || void "only $ACCEPTED/$UPLOADS uploads were accepted (202), below MIN_ACCEPTED=$MIN_ACCEPTED — this measures the rejection path, not the media pipeline"

# ---------------------------------------------------------------- step 3: drain
DRAIN_START="$(date +%s)"
DRAINED_AT=""
while :; do
    depth="$(queue_depth "$QUEUE")"
    sample_once
    now="$(date +%s)"; elapsed=$(( now - DRAIN_START ))
    if [ "$depth" -eq 0 ]; then DRAINED_AT="$elapsed"; break; fi
    if [ "$elapsed" -ge "$DRAIN_BUDGET_S" ]; then break; fi
    sleep 1
done

# ---------------------------------------------------------------- step 6: DLQ assertion
# 27-00 arm B's rule, inherited and load-bearing: a queue that reaches 0 because every
# message DIED is indistinguishable from one that reached 0 because every message was
# PROCESSED, unless the dead-letter queue is watched.
DLQ_AFTER="$(queue_depth "$DLQ")"
if [ "$DLQ_AFTER" -gt "$DLQ_BEFORE" ]; then
    fail "$DLQ grew ${DLQ_BEFORE} -> ${DLQ_AFTER} during the run — the drain was messages DYING, not being processed"
fi

# ---------------------------------------------------------------- step 4/5: read results
read -r ACTIVE_C_AFTER ACTIVE_S_AFTER <<< "$(scrape_timer active)"
read -r FAILED_C_AFTER FAILED_S_AFTER <<< "$(scrape_timer failed)"

PROCESSED=$(awk -v a="$ACTIVE_C_AFTER" -v b="$ACTIVE_C_BEFORE" 'BEGIN{printf "%d", a-b}')
SECONDS_SPENT=$(awk -v a="$ACTIVE_S_AFTER" -v b="$ACTIVE_S_BEFORE" 'BEGIN{printf "%.6f", a-b}')
MEAN_MS=$(awk -v s="$SECONDS_SPENT" -v n="$PROCESSED" 'BEGIN{ if (n>0) printf "%.1f", (s/n)*1000; else print "NA" }')
THROUGHPUT=$(awk -v n="$PROCESSED" -v s="$SECONDS_SPENT" 'BEGIN{ if (s>0) printf "%.3f", n/s; else print "NA" }')

[ "$PROCESSED" -gt 0 ] || void "the timer recorded 0 processed messages — an empty series is VOID, never a pass"

PEAK_DEPTH=$(awk -F'\t' 'NR>1 && $2+0>m {m=$2+0} END{print m+0}' "$SERIES")
PEAK_UNACKED=$(awk -F'\t' 'NR>1 && $3+0>m {m=$3+0} END{print m+0}' "$SERIES")
MAX_CONSUMERS=$(awk -F'\t' 'NR>1 && $4+0>m {m=$4+0} END{print m+0}' "$SERIES")
PEAK_CPU=$(awk -F'\t' 'NR>1 && $6!="NA" && $6+0>m {m=$6+0} END{printf "%.1f", m+0}' "$SERIES")
SAMPLES=$(( $(wc -l < "$SERIES") - 1 ))
[ "$SAMPLES" -gt 0 ] || void "empty sample series"

# ---------------------------------------------------------------- artifact
{
  echo "# Media pipeline arm — $ARM_LABEL"
  echo ""
  echo "- date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- commit: $(git rev-parse --short HEAD)"
  echo "- container: $CORE_NAME"
  echo "- cgroup cpu.max during run: \`$CPUMAX_PINNED\` (pin = ${PIN_CPUS} CPU)"
  echo "  - NOTE: \`docker inspect .HostConfig.NanoCpus\` is NOT used — measured stale on"
  echo "    Docker 29.6.2 (identical in pinned and released states, so it cannot discriminate)."
  echo "- uploads attempted: $UPLOADS  accepted(202): $ACCEPTED  pace: ${PACE_S}s"
  echo ""
  echo "## Consumer view (read from the RUNNING broker, never from the yml)"
  echo ""
  echo "| metric | value |"
  echo "|---|---|"
  echo "| consumers (max observed) | $MAX_CONSUMERS |"
  echo "| per-consumer prefetch_count | $(consumer_prefetches "$QUEUE") |"
  echo "| peak queue depth | $PEAK_DEPTH |"
  echo "| peak unacked | $PEAK_UNACKED |"
  echo "| depth after burst phase ($BURST_ACTUAL uploads) | $BURST_PEAK_DEPTH |"
  echo "| unacked after burst phase | $BURST_PEAK_UNACKED |"
  echo "| drain to 0 | ${DRAINED_AT:-NOT DRAINED within ${DRAIN_BUDGET_S}s} |"
  echo "| $DLQ before -> after | $DLQ_BEFORE -> $DLQ_AFTER |"
  echo ""
  echo "## Service time (jtoye.media.process, 27-04 T1)"
  echo ""
  echo "| metric | value |"
  echo "|---|---|"
  echo "| messages processed (outcome=active) | $PROCESSED |"
  echo "| total seconds in-worker | $SECONDS_SPENT |"
  echo "| mean per message | ${MEAN_MS} ms |"
  echo "| messages/sec/consumer | $THROUGHPUT |"
  echo "| outcome=failed delta | $(awk -v a="$FAILED_C_AFTER" -v b="$FAILED_C_BEFORE" 'BEGIN{printf "%d", a-b}') |"
  echo "| peak container CPU% | $PEAK_CPU |"
  echo ""
  echo "## Upload status distribution"
  echo ""
  for c in "${!CODES[@]}"; do echo "- [$c] = ${CODES[$c]}"; done
  echo ""
  echo "## Raw series ($SAMPLES samples, 1 Hz)"
  echo ""
  echo '```'
  cat "$SERIES"
  echo '```'
} > "$ARTIFACT"

echo ""
echo "processed=$PROCESSED  mean=${MEAN_MS}ms  msg/s/consumer=$THROUGHPUT"
echo "peak depth=$PEAK_DEPTH  peak unacked=$PEAK_UNACKED  consumers=$MAX_CONSUMERS  peak CPU=${PEAK_CPU}%"
echo "artifact: $ARTIFACT"

# ---------------------------------------------------------------- release + prove (AC-5)
docker update --cpu-quota=-1 "$CORE_CID" >/dev/null
CPUMAX_RELEASED="$(cpu_max)"
echo "cgroup cpu.max released: $CPUMAX_RELEASED (must start with 'max')"
cpu_is_pinned && fail "CPU pin was NOT released (cpu.max=$CPUMAX_RELEASED) — every later measurement in 27-01/27-03 would be silently CPU-starved"
echo "AC-5 satisfied: pinned='$CPUMAX_PINNED' -> released='$CPUMAX_RELEASED'"
