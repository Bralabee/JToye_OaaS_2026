#!/usr/bin/env bash
# check-connection-math.sh — regression gate for issue #94 (P2-3).
#
# Guarantees the platform can never again promise Postgres more connections
# than it has. Two failure modes are guarded:
#
#   1. POOL MATH: HPA maxReplicas × Hikari pool size (+ Keycloak + pg-backup
#      + postgres-exporter + reserved slots) must fit inside that environment's
#      Postgres max_connections budget with >= 20% headroom.
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
# ---------------------------------------------------------------------------
# PLAN 29-04: THE BUDGET NOW COMES FROM THE SERVER THAT WILL ACTUALLY SERVE
#
# WHAT WAS WRONG. `max_connections` was read from docker-compose.full-stack.yml
# for EVERY environment — so the gate guarding the CLUSTER's budget took its
# ceiling from the LOCAL DEV STACK. That is fine while the two agree and
# silently wrong the moment they do not. Live run before this change:
#
#   Postgres budget: max_connections=200, reserved=3, app-usable=197, 80% budget line=157
#   k8s staging (HPA max+surge)  11 replicas x pool 12  + keycloak(20)+backup(1)+exporter(2) = 155  -> OK (<= 157)
#
# D-09 moves staging to an Azure Flexible Server. A B1ms server — the shape that
# fits under the £150 ceiling — offers **50** connections. The run above would
# have printed exactly the same green line, and every core-java replica would
# have CrashLooped on connection exhaustion. A gate that cannot fail is not a
# gate; this one provably could not.
#
# WHAT IT READS NOW. For each deployed k8s target, the RENDERED app-config
# `db.max-connections` — a render-time declaration in the same shape as `db.port`
# (k8s/base/configmap.yaml), patched per overlay. Rendering it (rather than
# grepping the overlay patch) is what makes base inheritance visible: production
# declares nothing and correctly inherits the base 200. Compose keeps reading its
# own `max_connections=200`, because for compose that IS the server.
#
# RESERVED IS ALSO PER TARGET, WITH ITS REASON ATTACHED. PostgreSQL's default
# `superuser_reserved_connections` is 3; Azure Flexible Server reserves **15**
# for replication and monitoring. Hardcoding 15 into the body would outlive the
# reason it was true, so both live in a declared table beside their citation, and
# a target with no declaration exits 2 rather than inheriting a number.
# ---------------------------------------------------------------------------
#
# Requires: bash, grep, awk, and `kubectl` (client-side `kubectl kustomize`
# only — no cluster access, no kube context is read). kubectl became a
# requirement in plan 29-04: the k8s budget is read out of the render, and a
# missing tool is exit 2 (VOID), never a pass. CI's k8s-validate job already
# installs kubectl for the sibling gates.
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

command -v kubectl > /dev/null \
    || parse_fail "kubectl not on PATH. The k8s connection budget is read from the kustomize RENDER (app-config db.max-connections) since plan 29-04, so this gate cannot evaluate the k8s targets without it. Missing tooling is VOID (exit 2), never a pass."

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------------------
# Which k8s targets carry a connection budget, and why the others do not.
#
# Declared rather than discovered-and-guessed, but CROSS-CHECKED against
# discovery below: a new overlay that appears in neither list fails the gate
# rather than being silently unbudgeted. Same hygiene as the INV-6 allowlist in
# check-render-invariants.sh.
# ---------------------------------------------------------------------------
K8S_BUDGET_TARGETS=(
  "k8s/staging"
  "k8s/production"
)
# '<target>|<reason>' — an excluded target must say why.
K8S_BUDGET_EXCLUDED=(
  "k8s/base|not a deploy target; it has no namespace of its own and is only ever consumed through an overlay."
  "k8s/local|points at the SAME compose Postgres the 'compose dev' row already budgets (db.port 5433 is the compose-published host port), and runs 1 replica with no HPA headroom. Budgeting it separately would double-count one server."
)

# ---------------------------------------------------------------------------
# RESERVED connections, per target, WITH THE REASON ATTACHED.
#
# Not a single constant: the number is a property of the SERVER each environment
# talks to, and the two servers differ by 5x in the direction that matters.
# ---------------------------------------------------------------------------
declare -A RESERVED_BY_TARGET=(
  ["k8s/staging"]=15
  ["k8s/production"]=3
  ["compose"]=3
)
declare -A RESERVED_REASON=(
  ["k8s/staging"]="Azure Database for PostgreSQL Flexible Server reserves 15 connections for replication/monitoring (29-OPERATOR-DECISIONS.md §5, citing learn.microsoft.com/azure/postgresql/configure-maintain/concepts-limits). D-09 puts staging on a managed server."
  ["k8s/production"]="still an in-cluster PostgreSQL, so PostgreSQL's own superuser_reserved_connections default of 3 applies. This must move to 15 in the SAME change that moves production onto a managed server (Phase 32) — it is not a default to inherit."
  ["compose"]="PostgreSQL default superuser_reserved_connections; docker-compose.full-stack.yml starts postgres with no override."
)

# Which Hikari pool default each k8s target runs with.
declare -A POOL_SOURCE_BY_TARGET=(
  ["k8s/staging"]="application-staging.yml"
  ["k8s/production"]="k8s Deployment DB_POOL_SIZE env"
)

# Read the RENDERED app-config db.max-connections for a target.
#
# Rendered, not grepped out of the overlay patch, because inheritance is the
# interesting case: production declares nothing and must be shown to resolve to
# the base value rather than to nothing at all. Exactly one occurrence is
# required — zero means the key vanished (the budget would have no source and
# the arithmetic would be invented), more than one means the render carries two
# app-configs and the value is ambiguous. Both are exit 2.
render_db_max_connections() {
    local target="$1" render hits value
    render="$TMP/${target//\//_}.yaml"
    if ! kubectl kustomize "$REPO_ROOT/$target" > "$render" 2> "$TMP/kustomize.err"; then
        cat "$TMP/kustomize.err" >&2
        parse_fail "'kubectl kustomize $target' failed, so that target's connection budget has no source."
    fi
    hits=$(awk '/^  db\.max-connections:/ { n++ } END { print n+0 }' "$render")
    (( hits == 1 )) || parse_fail "found $hits occurrence(s) of app-config 'db.max-connections' in the $target render; exactly 1 is required. Zero means the render-time declaration is gone and this gate would be budgeting against a number it invented. Restore the key, do not delete the assertion."
    value=$(awk '/^  db\.max-connections:/ { v = $2; gsub(/^["\047]|["\047]$/, "", v); print v; exit }' "$render")
    [[ "$value" =~ ^[0-9]+$ ]] || parse_fail "app-config 'db.max-connections' in the $target render is '$value', which is not a bare integer."
    echo "$value"
}

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
# The COMPOSE server's own ceiling. It is no longer the ceiling for the k8s
# targets — those read their own rendered declaration below — but for compose it
# is the real value of the real server, parsed from where it is set.
COMPOSE_MAX_CONNECTIONS=$(extract_number "$COMPOSE" \
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
PG_BACKUP=1          # pg-backup CronJob (pg_dump) while running
EXPORTER=2           # prometheus postgres-exporter
HEALTHCHECK=2        # compose healthcheck psql sessions (transient)
SURGE=1              # RollingUpdate maxSurge: 1 — one extra pod during deploys
COMPOSE_REPLICAS=2   # compose supports --scale core-java=2 (port range 9090-9091)

# ---------------------------------------------------------------------------
# Target-coverage cross-check: every k8s overlay on disk must be either budgeted
# or excluded WITH A REASON. A new overlay that is in neither list is not
# silently unbudgeted — it exits 2. This is what stops the connection budget
# quietly ceasing to cover the environment it was written for.
# ---------------------------------------------------------------------------
declare -A BUDGET_EXCLUDED_REASON=()
for entry in "${K8S_BUDGET_EXCLUDED[@]}"; do
    t="${entry%%|*}"; r="${entry#*|}"
    [[ -n "$t" && "$t" != "$entry" ]] || parse_fail "K8S_BUDGET_EXCLUDED entry '$entry' is malformed; the shape is '<target>|<reason>'."
    [[ -n "${r//[[:space:]]/}" ]] || parse_fail "K8S_BUDGET_EXCLUDED entry '$t' has a blank reason. An unexplained exclusion is indistinguishable from a forgotten environment."
    BUDGET_EXCLUDED_REASON["$t"]="$r"
done

mapfile -t DISCOVERED < <(find "$REPO_ROOT/k8s" -maxdepth 2 -name 'kustomization.yaml' -printf '%h\n' | sort)
(( ${#DISCOVERED[@]} > 0 )) || parse_fail "no kustomization.yaml found under $REPO_ROOT/k8s — discovery returned nothing, so the coverage cross-check would pass vacuously."
for dir in "${DISCOVERED[@]}"; do
    rel="${dir#"$REPO_ROOT"/}"
    covered=0
    for t in "${K8S_BUDGET_TARGETS[@]}"; do
        [[ "$rel" == "$t" ]] && covered=1
    done
    [[ -n "${BUDGET_EXCLUDED_REASON[$rel]:-}" ]] && covered=1
    (( covered == 1 )) || parse_fail "kustomize target '$rel' exists on disk but is neither in K8S_BUDGET_TARGETS nor in K8S_BUDGET_EXCLUDED. A new environment with no connection budget is exactly the gap this gate exists to close — budget it, or exclude it with a reason."
done

check_env() {
    local name="$1" replicas="$2" pool="$3" extras="$4" extras_desc="$5" \
          maxconn="$6" reserved="$7"
    local available=$(( maxconn - reserved ))
    local budget=$(( available * 80 / 100 ))
    local total=$(( replicas * pool + extras ))
    printf '%-28s max_conn=%-5d reserved=%-3d usable=%-5d budget=%-5d | %2d replicas x pool %-3d + %s = %d' \
        "$name" "$maxconn" "$reserved" "$available" "$budget" \
        "$replicas" "$pool" "$extras_desc" "$total"
    if (( total > budget )); then
        echo "  -> EXCEEDS budget $budget"
        fail "$name needs $total connections; budget (80% of $available usable, from max_connections=$maxconn less $reserved reserved) is $budget. Shrink DB_POOL_SIZE, lower maxReplicas, or raise that environment's declared max_connections — and raise it on the SERVER too, not only in app-config."
    fi
    echo "  -> OK (<= $budget)"
}

# k8s: the base HPA applies to every overlay, but each namespace points at its
# OWN Postgres, so each reads its own rendered ceiling and its own reserved
# count and must independently fit.
K8S_EXTRAS=$(( KEYCLOAK_POOL + PG_BACKUP + EXPORTER ))
K8S_EXTRAS_DESC="keycloak($KEYCLOAK_POOL)+backup($PG_BACKUP)+exporter($EXPORTER)"

for target in "${K8S_BUDGET_TARGETS[@]}"; do
    reserved="${RESERVED_BY_TARGET[$target]:-}"
    [[ "$reserved" =~ ^[0-9]+$ ]] || parse_fail "no RESERVED_BY_TARGET declaration for '$target'. The reserved-connection count is a property of the SERVER that environment talks to (3 for stock PostgreSQL, 15 for Azure Flexible Server) — inheriting someone else's number is how a budget silently stops describing the server it guards."
    [[ -n "${RESERVED_REASON[$target]:-}" ]] || parse_fail "RESERVED_BY_TARGET['$target'] has no RESERVED_REASON. A reserved count with no reason outlives the reason."
    case "$target" in
        "k8s/staging")    pool="$POOL_STAGING" ;;
        "k8s/production") pool="$POOL_K8S" ;;
        *) parse_fail "no Hikari pool source declared for budgeted target '$target'." ;;
    esac
    maxconn="$(render_db_max_connections "$target")"
    echo "  reserved=$reserved for $target — ${RESERVED_REASON[$target]}"
    check_env "$target (HPA max+surge)" $(( MAX_REPLICAS + SURGE )) "$pool" \
        "$K8S_EXTRAS" "$K8S_EXTRAS_DESC" "$maxconn" "$reserved"
    echo
done

# compose dev: everything shares the single jtoye-postgres, and for compose the
# compose file IS the server.
DEV_EXTRAS=$(( KEYCLOAK_POOL + EXPORTER + HEALTHCHECK ))
DEV_EXTRAS_DESC="keycloak($KEYCLOAK_POOL)+exporter($EXPORTER)+healthcheck($HEALTHCHECK)"
echo "  reserved=${RESERVED_BY_TARGET[compose]} for compose — ${RESERVED_REASON[compose]}"
check_env "compose dev (--scale 2)" "$COMPOSE_REPLICAS" "$POOL_BASE" \
    "$DEV_EXTRAS" "$DEV_EXTRAS_DESC" "$COMPOSE_MAX_CONNECTIONS" "${RESERVED_BY_TARGET[compose]}"

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
echo "PASS: connection-pool math fits each environment's own Postgres max_connections with >=20% headroom (${#K8S_BUDGET_TARGETS[@]} k8s target(s) read from the render + compose at $COMPOSE_MAX_CONNECTIONS)."
