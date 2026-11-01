#!/usr/bin/env bash
# check-connection-math.sh — regression gate for issue #94 (P2-3).
#
# Guarantees the platform can never again promise Postgres more connections
# than it has. Two failure modes are guarded:
#
#   1. POOL MATH: HPA maxReplicas × Hikari pool size (+ Keycloak + pg-backup
#      + postgres-exporter) must fit inside EACH TARGET's Postgres budget —
#      80% of (max_connections - reserved) — with >= 20% headroom.
#      max_connections and the reserved count come PER TARGET (compose,
#      k8s-staging, k8s-production) from k8s/scripts/connection-budget.conf
#      (override: CONNECTION_BUDGET_CONF), D-25: a self-hosted Postgres
#      reserves 3 (superuser_reserved_connections), Azure Flexible Server
#      reserves 15. A row may say `compose` for max_connections, meaning the
#      value parsed from docker-compose.full-stack.yml. A missing,
#      non-numeric, duplicated or absent target row is a PARSE ERROR (exit 2),
#      never a default. Every run also prints the counterfactual of the
#      production row at the Azure reserve (a Phase 32 sizing input).
#      Documented in k8s/DEPLOYMENT.md "Database Connection Budget".
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
# Usage: ./k8s/scripts/check-connection-math.sh [--selftest]
#   (run from anywhere; paths resolve relative to the repo root)
#   --selftest proves the gate can fail: arms d25, missing, non-numeric,
#   absent-target (exit 0 when every arm behaves, 1 otherwise).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

COMPOSE="$REPO_ROOT/docker-compose.full-stack.yml"
DEPLOYMENT="$REPO_ROOT/k8s/base/core-java-deployment.yaml"
KEYCLOAK_COMPONENT="${KEYCLOAK_COMPONENT_FILE:-$REPO_ROOT/k8s/components/keycloak/keycloak.yaml}"
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
# --selftest: proves the per-target reserve (D-25) can FAIL, and that a
# missing, non-numeric or absent target row is a PARSE ERROR, never a default.
# Each arm writes a synthetic budget config, re-invokes this gate with
# CONNECTION_BUDGET_CONF pointing at it, and judges the inner rc and output.
# One verdict line per arm: `ARM <name> ok` or `ARM <name> WRONG <detail>`;
# inner gate output is echoed only on WRONG, indented so no line starts `ARM`.
# CONNECTION_MATH_SELFTEST_FORMAT=tap prints the same verdicts as TAP 13.
# ---------------------------------------------------------------------------
SELF="$SCRIPT_DIR/$(basename "${BASH_SOURCE[0]}")"

selftest_conf() {
    # selftest_conf <file> <k8s-staging row, or "" to leave the row out>
    {
        echo "# check-connection-math.sh --selftest fixture"
        echo "compose          compose  3   selftest: self-hosted Postgres default reserve"
        if [[ -n "$2" ]]; then echo "$2"; fi
        echo "k8s-production   compose  3   selftest: unchanged production row"
    } > "$1"
}

run_gate() {
    # run_gate <config file>; sets GATE_OUT (stdout+stderr) and GATE_RC
    GATE_RC=0
    GATE_OUT="$(CONNECTION_BUDGET_CONF="$1" bash "$SELF" 2>&1)" || GATE_RC=$?
}

selftest() {
    local fmt="${CONNECTION_MATH_SELFTEST_FORMAT:-text}" tmp n=0 wrong=0
    SELFTEST_TMP="$(mktemp -d)"
    trap 'rm -rf "${SELFTEST_TMP:-}"' EXIT
    tmp="$SELFTEST_TMP"
    [[ "$fmt" == tap ]] && { echo "TAP version 13"; echo "1..5"; }

    verdict() {
        # verdict <arm> <ok|WRONG> [detail] — inner output comes from GATE_OUT
        local arm="$1" status="$2" detail="${3:-}" msg
        n=$(( n + 1 ))
        if [[ "$fmt" == tap ]]; then
            if [[ "$status" == ok ]]; then echo "ok $n - $arm"; return; fi
            msg="$detail :: $(tr '\n' ' ' <<<"$GATE_OUT")"
            echo "not ok $n - $arm"
            echo "  ---"
            echo "  message: '${msg//\'/\'\'}'"
            echo "  ..."
        else
            if [[ "$status" == ok ]]; then echo "ARM $arm ok"; return; fi
            echo "ARM $arm WRONG $detail"
            sed 's/^/    /' <<<"$GATE_OUT"
        fi
        wrong=$(( wrong + 1 ))
    }

    # Arm d25: max_connections 200 for k8s-staging. Under the OLD fixed reserve
    # of 3 the staging demand fits (157 line); under Azure Flexible Server's 15
    # it must not (148 line). The demand is read from the reserve-3 run and the
    # premise (old line >= demand > new line) is asserted, so the arm cannot
    # pass vacuously if the pool or maxReplicas later moves.
    local old_line=$(( (200 - 3) * 80 / 100 )) new_line=$(( (200 - 15) * 80 / 100 )) demand
    selftest_conf "$tmp/d25-old.conf" "k8s-staging      200      3   selftest: the old fixed reserve"
    run_gate "$tmp/d25-old.conf"
    demand="$(sed -nE '/^k8s[- ]staging .*= [0-9]+ /{s/.*= ([0-9]+) .*/\1/p;q}' <<<"$GATE_OUT")"
    if (( GATE_RC != 0 )); then
        verdict d25 WRONG "reserved=3 at max_connections=200 should PASS (rc 0), got rc=$GATE_RC"
    elif ! [[ "$demand" =~ ^[0-9]+$ ]] || (( demand > old_line || demand <= new_line )); then
        verdict d25 WRONG "premise lost: k8s-staging demand '${demand:-unreadable}' must be in ($new_line, $old_line]"
    else
        selftest_conf "$tmp/d25-new.conf" "k8s-staging      200      15  selftest: Azure Flexible Server reserve"
        run_gate "$tmp/d25-new.conf"
        if (( GATE_RC != 1 )); then
            verdict d25 WRONG "reserved=15 at max_connections=200 should FAIL rc 1 ($demand > $new_line), got rc=$GATE_RC"
        elif ! grep -q 'k8s-staging' <<<"$GATE_OUT" || ! grep -qF "$demand > $new_line" <<<"$GATE_OUT"; then
            verdict d25 WRONG "rc 1 but the output does not name k8s-staging and '$demand > $new_line'"
        else
            verdict d25 ok
        fi
    fi

    # Parse arms: each must exit 2 with a PARSE ERROR naming k8s-staging.
    local arm row
    for arm in missing non-numeric absent-target; do
        case "$arm" in
            missing)       row="k8s-staging      429" ;;
            non-numeric)   row="k8s-staging      429      fifteen  selftest: a word, not a count" ;;
            absent-target) row="" ;;
        esac
        selftest_conf "$tmp/$arm.conf" "$row"
        run_gate "$tmp/$arm.conf"
        if (( GATE_RC != 2 )); then
            verdict "$arm" WRONG "expected PARSE ERROR rc 2, got rc=$GATE_RC"
        elif ! grep -qE '^PARSE ERROR: .*k8s-staging' <<<"$GATE_OUT"; then
            verdict "$arm" WRONG "rc 2 but no PARSE ERROR line names k8s-staging"
        else
            verdict "$arm" ok
        fi
    done

    # Arm keycloak-pool-drift (#979): k8s/components/keycloak/keycloak.yaml
    # KC_DB_POOL_MAX_SIZE disagreeing with docker-compose.full-stack.yml must
    # fail rc 1 naming KC_DB_POOL_MAX_SIZE (using 19 so budget math still fits
    # and the drift guard itself is what fires).
    sed -E '/name: KC_DB_POOL_MAX_SIZE/{n;s/value: "[0-9]+"/value: "19"/;}' "$KEYCLOAK_COMPONENT" > "$tmp/keycloak-drift.yaml"
    GATE_RC=0
    GATE_OUT="$(KEYCLOAK_COMPONENT_FILE="$tmp/keycloak-drift.yaml" bash "$SELF" 2>&1)" || GATE_RC=$?
    if (( GATE_RC != 1 )); then
        verdict keycloak-pool-drift WRONG "expected drift FAIL rc 1 when k8s KC_DB_POOL_MAX_SIZE=19, got rc=$GATE_RC"
    elif ! grep -q 'KC_DB_POOL_MAX_SIZE' <<<"$GATE_OUT"; then
        verdict keycloak-pool-drift WRONG "rc 1 but output does not name KC_DB_POOL_MAX_SIZE"
    else
        verdict keycloak-pool-drift ok
    fi

    (( wrong == 0 ))
}

if [[ "${1:-}" == "--selftest" ]]; then
    if selftest; then exit 0; else exit 1; fi
fi

# ---------------------------------------------------------------------------
# Inputs parsed from the real files
# ---------------------------------------------------------------------------
COMPOSE_MAX_CONNECTIONS=$(extract_number "$COMPOSE" \
    'max_connections=([0-9]+)' '\1' 'Postgres max_connections')

KEYCLOAK_POOL=$(extract_number "$COMPOSE" \
    'KC_DB_POOL_MAX_SIZE: "([0-9]+)"' '\1' 'Keycloak KC_DB_POOL_MAX_SIZE')

KEYCLOAK_K8S_POOL="$(awk '/name: KC_DB_POOL_MAX_SIZE/{getline; if ($1=="value:") {gsub(/"/,"",$2); print $2}}' "$KEYCLOAK_COMPONENT")"
[[ "$KEYCLOAK_K8S_POOL" =~ ^[0-9]+$ ]] || parse_fail "could not extract KC_DB_POOL_MAX_SIZE env value from $KEYCLOAK_COMPONENT"

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
# Per-target max_connections and reserve (D-25), from connection-budget.conf.
# Rows: <target> <max_connections|compose> <reserved> <provenance...>. Every
# target must have exactly one row; a missing, non-numeric, duplicated or
# unknown value is a PARSE ERROR (exit 2), never a default.
# ---------------------------------------------------------------------------
BUDGET_CONF="${CONNECTION_BUDGET_CONF:-$SCRIPT_DIR/connection-budget.conf}"
[[ -r "$BUDGET_CONF" && -f "$BUDGET_CONF" ]] || parse_fail "connection budget config not readable: $BUDGET_CONF"

TARGETS=(compose k8s-staging k8s-production)
declare -A T_MAX=() T_RESERVED=() T_AVAILABLE=() T_BUDGET=()
COUNT_RE='^(0|[1-9][0-9]*)$'   # no leading zero: bash arithmetic reads 015 as octal

while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    read -r t max reserved prov <<< "$line"
    case " ${TARGETS[*]} " in
        *" $t "*) ;;
        *) parse_fail "$BUDGET_CONF: unknown target '$t' (expected exactly: ${TARGETS[*]})" ;;
    esac
    [[ -z "${T_MAX[$t]:-}" ]] || parse_fail "$BUDGET_CONF: duplicate row for target $t"
    if [[ "$max" == compose ]]; then
        max="$COMPOSE_MAX_CONNECTIONS"
    elif [[ "$t" == compose ]]; then
        parse_fail "$BUDGET_CONF: target compose: max_connections must be the word 'compose' (parsed from $COMPOSE), got '${max:-<missing>}'"
    elif ! [[ "$max" =~ $COUNT_RE ]]; then
        parse_fail "$BUDGET_CONF: target $t: max_connections '${max:-<missing>}' is neither a count nor 'compose'"
    fi
    [[ "$reserved" =~ $COUNT_RE ]] \
        || parse_fail "$BUDGET_CONF: target $t: reserved '${reserved:-<missing>}' is not a count"
    [[ -n "$prov" ]] || parse_fail "$BUDGET_CONF: target $t: provenance column is missing"
    (( reserved < max )) \
        || parse_fail "$BUDGET_CONF: target $t: reserved ($reserved) must be below max_connections ($max)"
    T_MAX[$t]="$max"
    T_RESERVED[$t]="$reserved"
done < "$BUDGET_CONF"

for t in "${TARGETS[@]}"; do
    [[ -n "${T_MAX[$t]:-}" ]] || parse_fail "$BUDGET_CONF: no row for target $t (every target needs its own max_connections and reserved)"
done

# ---------------------------------------------------------------------------
# Fixed budget line items (documented in k8s/DEPLOYMENT.md)
# ---------------------------------------------------------------------------
PG_BACKUP=1          # pg-backup CronJob (pg_dump) while running
EXPORTER=2           # prometheus postgres-exporter
HEALTHCHECK=2        # compose healthcheck psql sessions (transient)
SURGE=1              # RollingUpdate maxSurge: 1 — one extra pod during deploys
COMPOSE_REPLICAS=2   # compose supports --scale core-java=2 (port range 9090-9091)

for t in "${TARGETS[@]}"; do
    T_AVAILABLE[$t]=$(( T_MAX[$t] - T_RESERVED[$t] ))
    T_BUDGET[$t]=$(( T_AVAILABLE[$t] * 80 / 100 ))   # keep >= 20% headroom on app-usable slots
    echo "Postgres budget [$t]: max_connections=${T_MAX[$t]}, reserved=${T_RESERVED[$t]}, app-usable=${T_AVAILABLE[$t]}, 80% budget line=${T_BUDGET[$t]}"
done

# k8s prod/staging: base HPA applies to BOTH overlays; each namespace points at
# its own Postgres, so each must independently fit ITS OWN budget.
K8S_REPLICAS=$(( MAX_REPLICAS + SURGE ))
K8S_EXTRAS=$(( KEYCLOAK_K8S_POOL + PG_BACKUP + EXPORTER ))
K8S_EXTRAS_DESC="keycloak($KEYCLOAK_K8S_POOL)+backup($PG_BACKUP)+exporter($EXPORTER)"

# Counterfactual, printed on EVERY run before any check can exit: the
# production row is unchanged until Phase 32, but the Flexible Server it will
# move to reserves what k8s-staging's row reserves. Report whether today's
# production demand fits there at today's production max_connections, and the
# smallest max_connections M with (M - reserve) * 80 / 100 >= demand.
CF_RESERVED="${T_RESERVED[k8s-staging]}"
CF_MAX="${T_MAX[k8s-production]}"
CF_DEMAND=$(( K8S_REPLICAS * POOL_K8S + K8S_EXTRAS ))
CF_BUDGET=$(( CF_MAX > CF_RESERVED ? (CF_MAX - CF_RESERVED) * 80 / 100 : 0 ))
CF_MIN_MAX=$(( CF_RESERVED + (CF_DEMAND * 100 + 79) / 80 ))
if (( CF_DEMAND > CF_BUDGET )); then
    CF_VERDICT="does not fit at max_connections=$CF_MAX; Phase 32 must size max_connections ≥ $CF_MIN_MAX or lower the pool"
else
    CF_VERDICT="fits at max_connections=$CF_MAX (the floor is max_connections ≥ $CF_MIN_MAX)"
fi
echo "INFO counterfactual: k8s-production at reserved $CF_RESERVED (the Azure Flexible Server reserve of the k8s-staging row) needs $CF_DEMAND, budget $CF_BUDGET — $CF_VERDICT"
echo

check_env() {
    # check_env <target> <label> <replicas> <pool> <extras> <extras-desc>
    local target="$1" name="$2" replicas="$3" pool="$4" extras="$5" extras_desc="$6"
    local total=$(( replicas * pool + extras )) budget="${T_BUDGET[$1]}"
    printf '%-31s %2d replicas x pool %-3d + %s = %d' "$name" "$replicas" "$pool" "$extras_desc" "$total"
    if (( total > budget )); then
        echo "  -> EXCEEDS budget $budget"
        fail "$target needs $total > $budget connections: the 80% line of ${T_AVAILABLE[$target]} usable (max_connections=${T_MAX[$target]} - reserved=${T_RESERVED[$target]}, $BUDGET_CONF). Shrink DB_POOL_SIZE, lower maxReplicas, or raise this target's max_connections."
    fi
    echo "  -> OK (<= $budget)"
}

check_env k8s-production "k8s-production (HPA max+surge)" "$K8S_REPLICAS" "$POOL_K8S"     "$K8S_EXTRAS" "$K8S_EXTRAS_DESC"
check_env k8s-staging    "k8s-staging (HPA max+surge)"    "$K8S_REPLICAS" "$POOL_STAGING" "$K8S_EXTRAS" "$K8S_EXTRAS_DESC"

# compose dev: everything shares the single jtoye-postgres.
DEV_EXTRAS=$(( KEYCLOAK_POOL + EXPORTER + HEALTHCHECK ))
DEV_EXTRAS_DESC="keycloak($KEYCLOAK_POOL)+exporter($EXPORTER)+healthcheck($HEALTHCHECK)"
check_env compose        "compose (--scale 2)"            "$COMPOSE_REPLICAS" "$POOL_BASE" "$DEV_EXTRAS" "$DEV_EXTRAS_DESC"

echo

# ---------------------------------------------------------------------------
# Drift guard: the k8s env override and the prod YAML default must agree, or
# "what runs in the cluster" and "what the profile documents" silently diverge.
# ---------------------------------------------------------------------------
if (( POOL_K8S != POOL_PROD )); then
    fail "k8s DB_POOL_SIZE env ($POOL_K8S) != application-prod.yml default ($POOL_PROD). Keep them identical so the manifest and the profile tell the same story."
fi
echo "Drift guard: k8s DB_POOL_SIZE ($POOL_K8S) == application-prod.yml default ($POOL_PROD) — OK"

if (( KEYCLOAK_K8S_POOL != KEYCLOAK_POOL )); then
    fail "k8s Keycloak KC_DB_POOL_MAX_SIZE ($KEYCLOAK_K8S_POOL in $KEYCLOAK_COMPONENT) != docker-compose.full-stack.yml ($KEYCLOAK_POOL). Keep them identical so the k8s component and compose share one connection-budget line item (#979)."
fi
echo "Drift guard: k8s KC_DB_POOL_MAX_SIZE ($KEYCLOAK_K8S_POOL) == compose KC_DB_POOL_MAX_SIZE ($KEYCLOAK_POOL) — OK"

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
SUMMARY=""
for t in "${TARGETS[@]}"; do SUMMARY+="${SUMMARY:+, }$t ${T_MAX[$t]}/${T_RESERVED[$t]}"; done
echo "PASS: connection-pool math fits every target's Postgres budget with >=20% headroom ($SUMMARY as max_connections/reserved)."
