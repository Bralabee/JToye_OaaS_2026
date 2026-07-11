#!/usr/bin/env bash
# check-connection-math.sh — regression gate for issue #94 (P2-3).
#
# Guarantees the platform can never again promise Postgres more connections
# than it has. Two failure modes are guarded:
#
#   1. POOL MATH: HPA maxReplicas × Hikari pool size (+ Keycloak + pg-backup
#      + postgres-exporter + superuser-reserved slots) must fit inside the
#      explicit Postgres max_connections budget with >= 20% headroom.
#      The budget constant (200) is read from docker-compose.full-stack.yml,
#      which is the single in-repo place max_connections is set; the
#      jtoye-infrastructure Postgres in k8s MUST be run with the same value
#      (documented in k8s/DEPLOYMENT.md "Database Connection Budget").
#
#   2. HPA MEMORY PINNING: the core-java HPA must scale on CPU only. The JVM
#      commits ~75% of the container memory limit as heap at startup
#      (MaxRAMPercentage=75, core-java/Dockerfile), so a memory-utilization
#      target is a constant that once pinned the HPA at maxReplicas — which
#      is exactly what multiplied the pool math to exhaustion.
#
# All inputs are PARSED FROM THE REAL FILES so this fails when any side of
# the equation drifts (pool default, k8s env override, maxReplicas, budget).
#
# Requires: bash, grep, awk (no cluster access, no kubectl).
# Exit codes: 0 = math holds, 1 = violation, 2 = parse/tooling failure.
#
# Usage: ./k8s/scripts/check-connection-math.sh
#   (run from anywhere; paths resolve relative to the repo root)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

COMPOSE="$REPO_ROOT/docker-compose.full-stack.yml"
DEPLOYMENT="$REPO_ROOT/k8s/base/core-java-deployment.yaml"
APP_BASE="$REPO_ROOT/core-java/src/main/resources/application.yml"
APP_PROD="$REPO_ROOT/core-java/src/main/resources/application-prod.yml"
APP_STAGING="$REPO_ROOT/core-java/src/main/resources/application-staging.yml"

fail() { echo "FAIL: $*" >&2; exit 1; }
parse_fail() { echo "PARSE ERROR: $*" >&2; exit 2; }

extract_number() {
    # extract_number <file> <grep-pattern> <sed-capture> <description>
    local file="$1" pattern="$2" capture="$3" desc="$4" value
    value="$(grep -Eo "$pattern" "$file" | head -1 | sed -E "s/$pattern/$capture/")" || true
    [[ "$value" =~ ^[0-9]+$ ]] || parse_fail "could not extract $desc from $file (pattern: $pattern)"
    echo "$value"
}

# ---------------------------------------------------------------------------
# Inputs parsed from the real files
# ---------------------------------------------------------------------------
MAX_CONNECTIONS=$(extract_number "$COMPOSE" \
    'max_connections=([0-9]+)' '\1' 'Postgres max_connections')

KEYCLOAK_POOL=$(extract_number "$COMPOSE" \
    'KC_DB_POOL_MAX_SIZE: "([0-9]+)"' '\1' 'Keycloak KC_DB_POOL_MAX_SIZE')

POOL_BASE=$(extract_number "$APP_BASE" \
    '\$\{DB_POOL_SIZE:([0-9]+)\}' '\1' 'base (dev) Hikari maximum-pool-size default')

POOL_PROD=$(extract_number "$APP_PROD" \
    '\$\{DB_POOL_SIZE:([0-9]+)\}' '\1' 'prod Hikari maximum-pool-size default')

POOL_STAGING=$(extract_number "$APP_STAGING" \
    '\$\{DB_POOL_SIZE:([0-9]+)\}' '\1' 'staging Hikari maximum-pool-size default')

MAX_REPLICAS=$(extract_number "$DEPLOYMENT" \
    'maxReplicas: ([0-9]+)' '\1' 'HPA maxReplicas')

# The explicit DB_POOL_SIZE env the k8s Deployment injects (the value that
# actually wins at runtime in the cluster).
POOL_K8S="$(awk '/name: DB_POOL_SIZE/{getline; if ($1=="value:") {gsub(/"/,"",$2); print $2}}' "$DEPLOYMENT")"
[[ "$POOL_K8S" =~ ^[0-9]+$ ]] || parse_fail "could not extract DB_POOL_SIZE env value from $DEPLOYMENT"

# ---------------------------------------------------------------------------
# Fixed budget line items (documented in k8s/DEPLOYMENT.md)
# ---------------------------------------------------------------------------
RESERVED=3           # superuser_reserved_connections (PG default) — not usable by apps
PG_BACKUP=1          # pg-backup CronJob (pg_dump) while running
EXPORTER=2           # prometheus postgres-exporter
HEALTHCHECK=2        # compose healthcheck psql sessions (transient)
SURGE=1              # RollingUpdate maxSurge: 1 — one extra pod during deploys
COMPOSE_REPLICAS=2   # compose supports --scale core-java=2 (port range 9090-9091)

AVAILABLE=$(( MAX_CONNECTIONS - RESERVED ))
BUDGET=$(( AVAILABLE * 80 / 100 ))   # keep >= 20% headroom on app-usable slots

echo "Postgres budget: max_connections=$MAX_CONNECTIONS, reserved=$RESERVED, app-usable=$AVAILABLE, 80% budget line=$BUDGET"
echo

check_env() {
    local name="$1" replicas="$2" pool="$3" extras="$4" extras_desc="$5"
    local total=$(( replicas * pool + extras ))
    printf '%-28s %2d replicas x pool %-3d + %s = %d' "$name" "$replicas" "$pool" "$extras_desc" "$total"
    if (( total > BUDGET )); then
        echo "  -> EXCEEDS budget $BUDGET"
        fail "$name needs $total connections; budget (80% of $AVAILABLE usable) is $BUDGET. Shrink DB_POOL_SIZE, lower maxReplicas, or raise max_connections everywhere it is set."
    fi
    echo "  -> OK (<= $BUDGET)"
}

# k8s prod/staging: base HPA applies to BOTH overlays; each namespace points at
# its own Postgres, so each must independently fit the budget.
K8S_EXTRAS=$(( KEYCLOAK_POOL + PG_BACKUP + EXPORTER ))
K8S_EXTRAS_DESC="keycloak($KEYCLOAK_POOL)+backup($PG_BACKUP)+exporter($EXPORTER)"
check_env "k8s prod (HPA max+surge)"    $(( MAX_REPLICAS + SURGE )) "$POOL_K8S"    "$K8S_EXTRAS" "$K8S_EXTRAS_DESC"
check_env "k8s staging (HPA max+surge)" $(( MAX_REPLICAS + SURGE )) "$POOL_STAGING" "$K8S_EXTRAS" "$K8S_EXTRAS_DESC"

# compose dev: everything shares the single jtoye-postgres.
DEV_EXTRAS=$(( KEYCLOAK_POOL + EXPORTER + HEALTHCHECK ))
DEV_EXTRAS_DESC="keycloak($KEYCLOAK_POOL)+exporter($EXPORTER)+healthcheck($HEALTHCHECK)"
check_env "compose dev (--scale 2)"     "$COMPOSE_REPLICAS" "$POOL_BASE" "$DEV_EXTRAS" "$DEV_EXTRAS_DESC"

echo

# ---------------------------------------------------------------------------
# Drift guard: the k8s env override and the prod YAML default must agree, or
# "what runs in the cluster" and "what the profile documents" silently diverge.
# ---------------------------------------------------------------------------
if (( POOL_K8S != POOL_PROD )); then
    fail "k8s DB_POOL_SIZE env ($POOL_K8S) != application-prod.yml default ($POOL_PROD). Keep them identical so the manifest and the profile tell the same story."
fi
echo "Drift guard: k8s DB_POOL_SIZE ($POOL_K8S) == application-prod.yml default ($POOL_PROD) — OK"

# ---------------------------------------------------------------------------
# HPA memory-pinning guard: the core-java HPA document must contain no memory
# resource metric (JVM RSS is a constant ~75% of limit, not a load signal).
# ---------------------------------------------------------------------------
HPA_DOC="$(awk 'BEGIN{RS="\n---"} /kind: HorizontalPodAutoscaler/{print; exit}' "$DEPLOYMENT")"
[[ -n "$HPA_DOC" ]] || parse_fail "could not locate HorizontalPodAutoscaler document in $DEPLOYMENT"
if grep -Eq '^\s*name: memory\s*$' <<< "$HPA_DOC"; then
    fail "core-java HPA scales on memory again. JVM memory sits at ~75% of the limit regardless of load (MaxRAMPercentage=75) — a memory target pins the HPA at maxReplicas and re-creates issue #94. Scale on CPU (or a real load metric) only."
fi
echo "HPA guard: core-java HPA has no memory metric — OK"

echo
echo "PASS: connection-pool math fits Postgres max_connections=$MAX_CONNECTIONS with >=20% headroom."
