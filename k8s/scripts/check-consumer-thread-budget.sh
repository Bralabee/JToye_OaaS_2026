#!/usr/bin/env bash
# check-consumer-thread-budget.sh — INTRA-POD consumer-vs-HTTP contention budget (27-04, D-12).
#
# Asserts, per Spring profile:
#
#     Σ(concurrency over every @RabbitListener endpoint)  +  httpReserve  ≤  maximum-pool-size
#
# ---------------------------------------------------------------------------
# WHY THIS IS NOT A TERM IN check-connection-math.sh
# ---------------------------------------------------------------------------
# check-connection-math.sh budgets the CLUSTER-WIDE Postgres max_connections:
# replicas × pool + fixed extras ≤ 80% of (max_connections − reserved). Adding
# consumer threads to THAT equation is wrong twice over:
#
#   1. It double-counts. A pod physically CANNOT open more connections to
#      Postgres than its Hikari maximum-pool-size — the pool IS the cap.
#      Consumer threads contend FOR pool connections; they do not create new
#      ones. Budgeting them cluster-wide asserts an exhaustion that cannot occur.
#   2. It would not fire. That gate reports `64 -> OK (<= 157)` on compose dev
#      today; adding even 27 more threads gives 91, still inside 157. A criterion
#      written there would have been inert.
#
# The real hazard is INSIDE one pod: N consumer threads holding pool connections
# starve HTTP request handling. That is a different equation against a different
# denominator, so it lives in a different file. check-connection-math.sh is
# deliberately left BYTE-UNCHANGED.
#
# ---------------------------------------------------------------------------
# THE httpReserve CONSTANT AND ITS BASIS
# ---------------------------------------------------------------------------
# httpReserve = 2. Basis: at least two concurrent HTTP requests must be able to
# obtain a connection while every consumer thread is busy. Below that, one
# in-flight vendor request plus any second DB-touching request exhausts the pool
# and the API returns connection-timeout 500s.
#
# What makes this load-bearing rather than cosmetic: the pod does NOT drop out of
# service when it happens. k8s/base/core-java-deployment.yaml probes
# /actuator/health/readiness, and no readiness GROUP is configured in any
# application*.yml — so Spring's default applies and the group contains
# readinessState ONLY, never the `db` indicator. The probe therefore keeps
# returning 200 against a pool with zero free connections, Kubernetes keeps
# routing traffic to the pod, and the failure is invisible to the orchestrator.
#
# 2 is deliberately modest — it is a floor, not a target. It was chosen before
# the arithmetic below was run, and it is NOT to be tuned to make this gate pass
# (see the current-tree note in 27-04 AC-12).
#
# ---------------------------------------------------------------------------
# All inputs are PARSED FROM THE REAL FILES, so this fails when any side of the
# equation drifts: a new listener, a concurrency change, or a pool-size change.
#
# Requires: bash, grep, awk. No cluster access, no kubectl, no running stack.
# Exit codes: 0 = budget holds, 1 = violation, 2 = parse/tooling failure (VOID).
#
# Usage: ./k8s/scripts/check-consumer-thread-budget.sh
#   (run from anywhere; paths resolve relative to the repo root)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

MAIN_JAVA="$REPO_ROOT/core-java/src/main/java"
APP_BASE="$REPO_ROOT/core-java/src/main/resources/application.yml"
APP_PROD="$REPO_ROOT/core-java/src/main/resources/application-prod.yml"
APP_STAGING="$REPO_ROOT/core-java/src/main/resources/application-staging.yml"

# httpReserve — see the basis block above.
HTTP_RESERVE=2

# The container's own default when no concurrency property is bound.
# SimpleMessageListenerContainer starts ONE consumer per queue
# (AbstractMessageListenerContainer, bytecode-verified in 27-04 finding A3).
CONTAINER_DEFAULT_CONCURRENCY=1

MEDIA_FACTORY_BEAN="mediaRabbitListenerContainerFactory"

fail()       { echo "FAIL: $*" >&2; exit 1; }
parse_fail() { echo "PARSE ERROR (VOID): $*" >&2; exit 2; }

for tool in grep awk; do
    command -v "$tool" >/dev/null 2>&1 || parse_fail "required tool '$tool' not found"
done
[ -d "$MAIN_JAVA" ] || parse_fail "source tree not found at $MAIN_JAVA"
for f in "$APP_BASE" "$APP_PROD" "$APP_STAGING"; do
    [ -f "$f" ] || parse_fail "config file not found: $f"
done

# ---------------------------------------------------------------------------
# 1. Endpoint inventory — counted from ANNOTATION SITES, never from a constant.
#
# The leading-whitespace anchor is what separates a real annotation from the
# javadoc/comment mentions of the same token: javadoc lines begin with `*` and
# comments with `//`. Unanchored the same scan returns 16; anchored it returns 9.
# A new listener therefore CHANGES THE VERDICT instead of silently widening the
# budget — which is the whole point of parsing rather than hardcoding 9.
# ---------------------------------------------------------------------------
# `|| true` is load-bearing: grep exits 1 when it matches NOTHING, and under
# `set -o pipefail` that failure propagates out of the pipeline and `set -e`
# kills the script SILENTLY — empty output, exit 1, indistinguishable from a
# real violation. Caught here by running the script before trusting it.
ENDPOINTS="$( { grep -rE '^[[:space:]]*@RabbitListener' "$MAIN_JAVA" --include='*.java' || true; } | wc -l)"
ENDPOINTS="${ENDPOINTS// /}"

# "Found no endpoints" is NOT "the budget holds" — a broken locator must be VOID.
[ "$ENDPOINTS" -gt 0 ] 2>/dev/null \
    || parse_fail "parsed 0 @RabbitListener endpoints from $MAIN_JAVA — a scan that finds nothing cannot prove a budget"

# Endpoints routed to the dedicated media container factory. 0 before 27-04 T3
# lands, at which point media.process moves onto its own factory and stops being
# governed by the default concurrency.
# Same `|| true` reason as above — and here a zero match is the EXPECTED state
# before T3 lands, so without it this gate could never run on its own baseline.
MEDIA_ENDPOINTS="$( { grep -rE "containerFactory[[:space:]]*=[[:space:]]*\"$MEDIA_FACTORY_BEAN\"" \
    "$MAIN_JAVA" --include='*.java' || true; } | wc -l)"
MEDIA_ENDPOINTS="${MEDIA_ENDPOINTS// /}"
[ "$MEDIA_ENDPOINTS" -le "$ENDPOINTS" ] \
    || parse_fail "media-factory endpoints ($MEDIA_ENDPOINTS) exceed total endpoints ($ENDPOINTS)"

DEFAULT_ENDPOINTS=$(( ENDPOINTS - MEDIA_ENDPOINTS ))

# ---------------------------------------------------------------------------
# 2. Concurrency values.
#
# Absent key => the container default (1). That is the CORRECT model of today's
# tree, not a fallback that hides a missing value: before 27-04 T4 adds the
# jtoye.rabbit.* block there is no property to read, and the effective
# concurrency really is 1 per queue.
# ---------------------------------------------------------------------------
extract_concurrency() {
    # extract_concurrency <file> <yaml-key> ; echoes the number, or "" if absent
    local file="$1" key="$2" value
    value="$(grep -Eo "${key}:[[:space:]]*\\\$\{[A-Z_]+:([0-9]+)\}" "$file" 2>/dev/null \
             | head -1 | grep -Eo ':([0-9]+)\}' | grep -Eo '[0-9]+')" || true
    if [ -z "$value" ]; then
        # Also accept a plain literal (no env indirection).
        value="$(grep -Eo "${key}:[[:space:]]*[0-9]+" "$file" 2>/dev/null \
                 | head -1 | grep -Eo '[0-9]+$')" || true
    fi
    echo "$value"
}

resolve_concurrency() {
    # resolve_concurrency <profile-file> <key> — profile override wins, else base, else container default
    local profile_file="$1" key="$2" v
    v="$(extract_concurrency "$profile_file" "$key")"
    [ -n "$v" ] || v="$(extract_concurrency "$APP_BASE" "$key")"
    [ -n "$v" ] || v="$CONTAINER_DEFAULT_CONCURRENCY"
    echo "$v"
}

extract_pool() {
    local file="$1" value
    value="$(grep -Eo '\$\{DB_POOL_SIZE:([0-9]+)\}' "$file" | head -1 | grep -Eo '[0-9]+')" || true
    [[ "$value" =~ ^[0-9]+$ ]] || parse_fail "could not extract maximum-pool-size from $file"
    echo "$value"
}

# ---------------------------------------------------------------------------
# 3. Per-profile verdict
# ---------------------------------------------------------------------------
echo "Consumer-thread budget (intra-pod): Σconcurrency + httpReserve <= maximum-pool-size"
echo "  @RabbitListener endpoints parsed : $ENDPOINTS  (media-factory: $MEDIA_ENDPOINTS, default-factory: $DEFAULT_ENDPOINTS)"
echo "  httpReserve                      : $HTTP_RESERVE"
echo ""

VIOLATIONS=0

check_profile() {
    local label="$1" profile_file="$2" pool default_c media_c total lhs status

    pool="$(extract_pool "$profile_file")"
    default_c="$(resolve_concurrency "$profile_file" 'default-concurrency')"

    # Worst case for a saturation budget is the container's MAXIMUM concurrency,
    # not its starting concurrency — a container that can scale to N will hold N.
    media_c="$(resolve_concurrency "$profile_file" 'media-max-concurrency')"
    if [ "$media_c" = "$CONTAINER_DEFAULT_CONCURRENCY" ]; then
        media_c="$(resolve_concurrency "$profile_file" 'media-concurrency')"
    fi

    total=$(( DEFAULT_ENDPOINTS * default_c + MEDIA_ENDPOINTS * media_c ))
    lhs=$(( total + HTTP_RESERVE ))

    if [ "$lhs" -le "$pool" ]; then
        status="OK"
    else
        status="VIOLATION"
        VIOLATIONS=$(( VIOLATIONS + 1 ))
    fi

    printf '  %-28s %2d threads (%d x %d default + %d x %d media) + %d reserve = %2d  vs pool %2d  -> %s\n' \
        "$label" "$total" "$DEFAULT_ENDPOINTS" "$default_c" "$MEDIA_ENDPOINTS" "$media_c" \
        "$HTTP_RESERVE" "$lhs" "$pool" "$status"
}

check_profile "base (dev/compose)" "$APP_BASE"
check_profile "prod"               "$APP_PROD"
check_profile "staging"            "$APP_STAGING"

echo ""
if [ "$VIOLATIONS" -gt 0 ]; then
    fail "$VIOLATIONS profile(s) breach the intra-pod consumer-thread budget. Consumer threads would starve HTTP request handling inside the pod, and because the readiness group is readinessState-only the pod keeps receiving traffic it cannot serve. Remedy is to raise DB_POOL_SIZE or bound the media concurrency — NOT to lower httpReserve."
fi

echo "PASS: every profile leaves at least $HTTP_RESERVE connection(s) for HTTP with all consumers saturated."
