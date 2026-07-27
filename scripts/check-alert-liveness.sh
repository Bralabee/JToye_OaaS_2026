#!/usr/bin/env bash
# check-alert-liveness.sh — proves the monitoring can actually SEE and TELL.
#
# WHY THIS EXISTS (Phase 27, plan 27-00 — findings F-1, F-3, F-3b, F-3c, F-4)
#
#   Measured on this repo on 2026-07-27, before any fix: 11 of 14 alert rules
#   were defective and every one of them reported health=ok. Syntax validity is
#   not liveness. The four ways a rule can be green and blind:
#
#     F-3   Six rules were dataless because the core-java scrape target had been
#           DOWN since the port moved. `up{job="core-java"} = 0`.
#     F-3b  DatabaseDown watches `up{job="postgres"}`, which was 1 because the
#           EXPORTER answered — while `pg_up` was 0 and not one PostgreSQL metric
#           was being collected. A target being UP is not evidence the thing
#           behind it is healthy.
#     F-3c  HighMemoryUsage and FrequentGarbageCollection carry
#           `service: core-java` but their selectors are unqualified, so they
#           bound to the only JVM Prometheus could see: KEYCLOAK's. These are
#           worse than dataless — a naive "selector matches >= 1 series" check
#           reports them GREEN while they watch the wrong process.
#     F-1   StompBrokerLag selects `{queue=~...}` against an aggregated broker
#           endpoint that emits no `queue` label at all. It matches 0 series,
#           can never fire, and reports health=ok.
#
#   Each assertion below pins one of those.
#
# THE FIVE ASSERTIONS
#
#   L-1   Every scrape target reports health == "up".
#   L-1b  For every exporter-backed job, its SELF-REPORTED upstream gauge is 1
#         AND that gauge name appears in at least one alerts.yml expression.
#         Both halves are required: a gauge at 1 that no rule reads is not
#         detection, and a rule reading a gauge that is 0 is the live defect.
#   L-2   Every rule's metric selector matches >= 1 live series.
#   L-2b  SUBJECT CORRECTNESS. A rule labelled `service: X` must match at least
#         one series from the JOB that X maps to. This is the assertion the
#         drafted gate lacked and the reason F-3c survived review.
#   L-3   TRANSPORT. A synthetic alert posted to Alertmanager actually arrives
#         at the configured destination.
#
#   WHAT L-3 PROVES AND DOES NOT PROVE: it proves the message left Alertmanager
#   and arrived at the configured sink; it does NOT prove a human reads that
#   sink, and today that sink is Mailhog at ops@jtoye.local with no human behind
#   it. A real ServiceDown fired here for over 46 hours and reached nobody.
#   Do not report a green L-3 as "operators are now notified".
#
# WHY THIS GATE IS NOT IN CI — and what replaces it
#
#   Same reasoning as check-runtime-freshness.sh: a CI runner has no Prometheus,
#   no Alertmanager and no Mailhog, so this could only ever exit 2 there. A
#   permanently-VOID job trains people to add `|| true`, which is worse than no
#   job. The enforcement instead: this script's EXIT CODE and an ISO-8601
#   timestamp are a REQUIRED FIELD in every phase SUMMARY and a line on the
#   phase-close checklist. A phase that cannot show a run has not shown its
#   monitoring works.
#
# EXIT CODES — uniform across this plan's gates
#   0 = clean · 1 = a live detection defect · 2 = VOID (could not evaluate)
#
#   VOID on: missing curl/jq/python3/docker, unreachable Prometheus or
#   Alertmanager, ZERO targets or ZERO rules discovered, an unparseable
#   expression, an exporter job with no gauge mapping, an unmapped `service:`
#   label, or a destination that cannot be inspected. "Found nothing" is NEVER
#   "clean", and an unmapped new thing must stop the gate rather than skip.
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

PROM_URL="${PROM_URL:-http://localhost:9091}"
ALERTMANAGER_URL="${ALERTMANAGER_URL:-http://localhost:9093}"
MAILHOG_URL="${MAILHOG_URL:-http://localhost:8025}"
ALERTS="${ALERTS:-infra/monitoring/prometheus/alerts.yml}"

void() { echo "VOID: $*" >&2; exit 2; }
VIOLATIONS=0
violation() { echo "FAIL: $*" >&2; VIOLATIONS=$((VIOLATIONS + 1)); }

# ---- data block: exporter job -> its self-reported upstream-health gauge ------
# Generalised deliberately. 27-00-PLAN.md's AC-4.2 names only postgres; register
# row TS-15 records why that is not enough — RedisDown has the IDENTICAL defect
# (it watches up{job="redis"}, while redis_up is live and referenced by no rule).
# Measured 2026-07-27: pg_up 0 rule refs, redis_up 0 rule refs. A gate that
# checked only postgres would ship blind to half the class it exists to catch.
# A new exporter job with no row here is VOID, so the mapping cannot fall behind.
EXPORTER_GAUGES=(
  "postgres|pg_up"
  "redis|redis_up"
)
# Jobs that are not exporter-fronted (they expose their own metrics directly).
DIRECT_JOBS=("prometheus" "core-java" "edge-go" "keycloak" "rabbitmq")

# ---- data block: rule `service:` label -> Prometheus `job` --------------------
# RULE-LABEL -> JOB, never label -> label. prometheus.yml labels the core-java
# TARGET `service: core-api` while the RULE's own label says `core-java`; mapping
# label-to-label would silently never match. `*` means "not job-scoped".
SERVICE_JOB_MAP=(
  "core-java|core-java"
  "postgresql|postgres"
  "redis|redis"
  "keycloak|keycloak"
  "rabbitmq|rabbitmq"
  "platform|*"
)

lookup() { # lookup <key> <array-name...>  -> prints value or empty
  local key="$1"; shift
  local row
  for row in "$@"; do
    [ "${row%%|*}" = "$key" ] && { echo "${row##*|}"; return 0; }
  done
  return 1
}

for t in curl jq python3; do
  command -v "$t" >/dev/null 2>&1 || void "$t not on PATH"
done
[ -r "$ALERTS" ] || void "alerts file not readable: $ALERTS"

curl -sf --max-time 10 "$PROM_URL/-/healthy" >/dev/null 2>&1 \
  || void "Prometheus unreachable at $PROM_URL — a stopped stack is VOID, never clean"

# ================================================================== L-1
TARGETS=$(curl -sf --max-time 15 "$PROM_URL/api/v1/targets?state=any") \
  || void "cannot read $PROM_URL/api/v1/targets"
TARGET_N=$(jq -r '.data.activeTargets | length' <<<"$TARGETS")
[ "${TARGET_N:-0}" -gt 0 ] || void "ZERO scrape targets discovered — the API shape changed or Prometheus has no config"

L1_DOWN=0
while IFS=$'\t' read -r job health url err; do
  [ -z "$job" ] && continue
  if [ "$health" != "up" ]; then
    violation "L-1 target job='$job' health=$health url=$url lastError=${err:-<none>}"
    L1_DOWN=$((L1_DOWN + 1))
  fi
done < <(jq -r '.data.activeTargets[] | [.labels.job, .health, .scrapeUrl, (.lastError // "")] | @tsv' <<<"$TARGETS")

JOBS=$(jq -r '.data.activeTargets[].labels.job' <<<"$TARGETS" | sort -u)

# ================================================================== L-1b
promq() { # promq <expr> -> first sample value, or empty
  curl -sfG --max-time 15 "$PROM_URL/api/v1/query" --data-urlencode "query=$1" \
    | jq -r '.data.result[0].value[1] // ""'
}

L1B_BLIND=0
L1B_UNREAD=0
while IFS= read -r job; do
  [ -z "$job" ] && continue
  gauge=$(lookup "$job" "${EXPORTER_GAUGES[@]}") || gauge=""
  if [ -z "$gauge" ]; then
    # not an exporter job? it must be on the DIRECT list, else the mapping is stale
    direct=0
    for d in "${DIRECT_JOBS[@]}"; do [ "$d" = "$job" ] && direct=1; done
    [ "$direct" = "1" ] || void "L-1b job '$job' has no upstream-gauge mapping and is not on DIRECT_JOBS — add a row rather than letting a new exporter skip the check"
    continue
  fi
  v=$(promq "$gauge")
  if [ "$v" != "1" ]; then
    violation "L-1b exporter job='$job' is UP but BLIND: $gauge = ${v:-<no series>} (the target answers; the thing behind it is not being measured)"
    L1B_BLIND=$((L1B_BLIND + 1))
  fi
  refs=$(command grep -c "$gauge" "$ALERTS" || true)
  if [ "$refs" -eq 0 ]; then
    violation "L-1b gauge '$gauge' (job='$job') is referenced by NO rule in $ALERTS — a healthy gauge nobody reads is not detection"
    L1B_UNREAD=$((L1B_UNREAD + 1))
  fi
done <<<"$JOBS"

# ================================================================== L-2 / L-2b
RULES=$(curl -sf --max-time 15 "$PROM_URL/api/v1/rules") || void "cannot read $PROM_URL/api/v1/rules"
RULE_N=$(jq -r '[.data.groups[].rules[] | select(.type=="alerting")] | length' <<<"$RULES")
[ "${RULE_N:-0}" -gt 0 ] || void "ZERO alerting rules discovered — Prometheus loaded no rule files"

# Extract metric selectors from a PromQL expression. python3 rather than a bash
# regex because getting this wrong SILENTLY under-reports, which is the failure
# mode this gate exists to prevent. PromQL keywords and function names are
# excluded by an explicit list; an expression yielding zero selectors is VOID,
# never a skip.
selectors_of() {
  python3 -c '
import re, sys
expr = sys.argv[1]
# Strip range/offset selectors FIRST: "[5m]" otherwise yields a bogus metric "m",
# and every rule using rate() would report a phantom 0-series selector. Then
# strip aggregation label lists: "by (service, le)" otherwise yields "service"
# and "le" as metrics. Both were observed as false positives on the real
# alerts.yml before this was added.
#
# The duration pattern is DELIBERATELY narrow. A generic r"\[[^\]]*\]" also eats
# character classes inside quoted label regexes — measured, it turned
# uri=~"/api/v[0-9]+/orders" into uri=~"/api/v+/orders" and
# queue=~"amq[.]gen-.*" into queue=~"amqgen-.*", silently querying a DIFFERENT
# selector than the rule uses and reporting that answer as if it were the rule
# result. Only a PromQL duration ([5m], [1h30m]) is stripped; "[0-9]" and "[.]"
# do not match. NB: no apostrophes in this block — it lives inside a
# single-quoted python3 -c string and one would terminate it.
expr = re.sub(r"\[\s*(?:[0-9]+(?:\.[0-9]+)?[smhdwy])+\s*\]", "", expr)
expr = re.sub(r"\b(by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)", " ", expr)
KW = {"by","without","on","ignoring","group_left","group_right","offset","bool","and","or","unless",
      "rate","irate","increase","sum","avg","min","max","count","count_values","stddev","stdvar",
      "topk","bottomk","quantile","histogram_quantile","delta","idelta","deriv","predict_linear",
      "abs","ceil","floor","round","clamp_max","clamp_min","exp","ln","log2","log10","sqrt",
      "time","timestamp","vector","scalar","absent","absent_over_time","changes","resets",
      "sum_over_time","avg_over_time","min_over_time","max_over_time","count_over_time",
      "label_replace","label_join","le","inf","nan"}
out, seen = [], set()
for m in re.finditer(r"([a-zA-Z_:][a-zA-Z0-9_:]*)\s*(\{[^}]*\})?", expr):
    name, labels = m.group(1), m.group(2) or ""
    if name in KW or name.isdigit(): continue
    sel = name + labels
    if sel not in seen:
        seen.add(sel); out.append(sel)
print("\n".join(out))
' "$1"
}

L2_EMPTY=0
L2B_WRONG=0
while IFS=$'\t' read -r name service query; do
  [ -z "$name" ] && continue
  mapped=$(lookup "$service" "${SERVICE_JOB_MAP[@]}") \
    || void "L-2b rule '$name' carries service='$service', which has no job mapping — add a row rather than letting a new label silently skip the check"

  sels=$(selectors_of "$query")
  [ -n "$sels" ] || void "L-2 could not extract any metric selector from rule '$name' expr: $query"

  rule_has_right_job=0
  while IFS= read -r sel; do
    [ -z "$sel" ] && continue
    n=$(curl -sfG --max-time 15 "$PROM_URL/api/v1/series" --data-urlencode "match[]=$sel" | jq -r '.data | length')
    bare="${sel%%\{*}"
    if [ "${n:-0}" -eq 0 ]; then
      nb=$(curl -sfG --max-time 15 "$PROM_URL/api/v1/series" --data-urlencode "match[]=$bare" | jq -r '.data | length')
      violation "L-2 rule '$name' selector '$sel' matches 0 series; without labels it matches ${nb:-0}"
      L2_EMPTY=$((L2_EMPTY + 1))
    elif [ "$mapped" != "*" ]; then
      hit=$(curl -sfG --max-time 15 "$PROM_URL/api/v1/series" --data-urlencode "match[]=$sel" \
            | jq -r --arg j "$mapped" '[.data[] | select(.job==$j)] | length')
      [ "${hit:-0}" -gt 0 ] && rule_has_right_job=1
    fi
  done <<<"$sels"

  if [ "$mapped" != "*" ] && [ "$rule_has_right_job" -eq 0 ]; then
    jobs_seen=$(while IFS= read -r sel; do
                  [ -z "$sel" ] && continue
                  curl -sfG --max-time 15 "$PROM_URL/api/v1/series" --data-urlencode "match[]=$sel" \
                    | jq -r '.data[].job // empty'
                done <<<"$sels" | sort -u | paste -sd, - )
    violation "L-2b rule '$name' claims service=$service (job=$mapped) but its selector matches only job=${jobs_seen:-<none>}"
    L2B_WRONG=$((L2B_WRONG + 1))
  fi
done < <(jq -r '.data.groups[].rules[] | select(.type=="alerting") | [.name, (.labels.service // "platform"), .query] | @tsv' <<<"$RULES")

# ================================================================== L-3
curl -sf --max-time 10 "$ALERTMANAGER_URL/-/healthy" >/dev/null 2>&1 \
  || void "Alertmanager unreachable at $ALERTMANAGER_URL"
curl -sf --max-time 10 "$MAILHOG_URL/api/v2/messages?limit=1" >/dev/null 2>&1 \
  || void "destination (Mailhog at $MAILHOG_URL) is not inspectable — an unverifiable transport is VOID, never clean"

amq() { curl -sf --max-time 10 "$ALERTMANAGER_URL/metrics" | command grep -E "^alertmanager_notifications_failed_total\{integration=\"email\"" | awk '{s+=$2} END {print s+0}'; }
FAILED_BEFORE=$(amq)

PROBE="SyntheticDeliveryProbe"
PROBE_ID="probe-$(jq -rn 'now|floor|tostring')-$$"

# The search MUST key on the per-run PROBE_ID, not on the alertname. Searching
# for the constant name matches probe emails from EVERY PREVIOUS RUN still
# sitting in the sink, so once any probe has ever been delivered L-3 passes
# forever regardless of whether this one arrived. Measured: with SMTP pointed at
# an unroutable host (192.0.2.1) the name-based search still reported
# delivered=1. A permanently-green transport check is exactly the defect class
# this gate exists to catch, and it was in the gate.
#
# The id goes in the ANNOTATIONS, not only in a label: the email template renders
# .Annotations.summary / .Annotations.description but not arbitrary labels, so a
# label-only id would never appear in the delivered message and could not be
# searched for.
curl -sf --max-time 10 -X POST "$ALERTMANAGER_URL/api/v2/alerts" -H 'Content-Type: application/json' \
  -d "[{\"labels\":{\"alertname\":\"$PROBE\",\"severity\":\"info\",\"service\":\"platform\",\"probe_id\":\"$PROBE_ID\"},\"annotations\":{\"summary\":\"synthetic transport probe $PROBE_ID — not a real alert\",\"description\":\"posted by scripts/check-alert-liveness.sh run $PROBE_ID\"}}]" \
  >/dev/null || void "cannot POST the synthetic alert to $ALERTMANAGER_URL"

FOUND=0
for _ in $(seq 1 40); do
  hits=$(curl -sfG --max-time 10 "$MAILHOG_URL/api/v2/search" --data-urlencode "kind=containing" --data-urlencode "query=$PROBE_ID" \
         | jq -r '.total // 0')
  [ "${hits:-0}" -gt 0 ] && { FOUND=1; break; }
  sleep 3
done

FAILED_AFTER=$(amq)
if [ "$FOUND" -ne 1 ]; then
  violation "L-3 synthetic alert '$PROBE' never reached the destination within the timeout (notifications_failed_total email: $FAILED_BEFORE -> $FAILED_AFTER)"
elif [ "$FAILED_AFTER" != "$FAILED_BEFORE" ]; then
  violation "L-3 delivery reported but alertmanager_notifications_failed_total{email} increased $FAILED_BEFORE -> $FAILED_AFTER"
fi

# expire the probe so it does not linger in the UI
curl -sf --max-time 10 -X POST "$ALERTMANAGER_URL/api/v2/alerts" -H 'Content-Type: application/json' \
  -d "[{\"labels\":{\"alertname\":\"$PROBE\",\"severity\":\"info\",\"service\":\"platform\",\"probe_id\":\"$PROBE_ID\"},\"endsAt\":\"$(date -u -d '-1 minute' +%Y-%m-%dT%H:%M:%S.000Z)\"}]" \
  >/dev/null 2>&1 || true

# ================================================================== summary
echo "alert-liveness summary  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  L-1   targets=$TARGET_N  down=$L1_DOWN"
echo "  L-1b  exporter-jobs=${#EXPORTER_GAUGES[@]}  blind=$L1B_BLIND  gauge-read-by-no-rule=$L1B_UNREAD"
echo "  L-2   rules=$RULE_N  selectors-matching-0-series=$L2_EMPTY"
echo "  L-2b  wrong-subject=$L2B_WRONG"
echo "  L-3   probe delivered=$FOUND  notifications_failed_total{email} $FAILED_BEFORE -> $FAILED_AFTER"
echo "  NOTE  L-3 proves the message left Alertmanager and arrived at the configured sink."
echo "        It does NOT prove a human reads that sink. Today that sink is Mailhog with no human behind it."

if [ "$VIOLATIONS" -gt 0 ]; then
  echo "FAILED: $VIOLATIONS live detection defect(s)." >&2
  exit 1
fi
echo "PASS: $TARGET_N targets up, ${#EXPORTER_GAUGES[@]} exporter gauge(s) healthy and rule-referenced, $RULE_N rules match live series of the job they claim, transport verified end-to-end."
