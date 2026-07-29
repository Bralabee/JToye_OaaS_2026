#!/usr/bin/env bash
# check-alert-rules.sh — the STATIC half of the alert-quality gate.
#
# WHY THIS EXISTS — finding F-8 (phase 27, plan 27-03)
#
#   Measured on this repo before this script:
#     grep -rn "promtool\|alerts.yml\|amtool" .github/workflows/ scripts/   ->  ZERO hits.
#   There was NO validation of infra/monitoring/prometheus/alerts.yml anywhere. Not in
#   CI, not in a script, not in a hook. The file could be syntactically broken, could
#   reference a metric that does not exist, could carry a rule with no runbook entry, and
#   nothing would say so. This script closes F-8's static half.
#
#   The specific defect it pins: `StompBrokerLag` selected
#   `rabbitmq_queue_messages_ready{queue=~"stomp-subscription.*|amq[.]gen-.*"}` against a
#   series family that carries NO `queue` label at all, so it could never fire — while
#   nine real dead messages sat unreported on webhook.deliveries.dlq. `promtool check
#   rules` PASSED that file the entire time. Syntax validity is exactly the assurance
#   that is worthless here, which is why this script asserts four more things on top of
#   it.
#
# CI OWNER: plan 27-06 (wave 4).
#   This script is PRODUCED by 27-03 and WIRED INTO CI BY 27-06, which adds three static
#   gates (check-dependency-horizons.sh, check-terminal-states.sh, and this one) and
#   asserts its step count is 3. 27-03 deliberately touches no file under
#   .github/workflows/. This line exists because the handover was dropped once already:
#   27-03's draft asserted sixteen times that "27-00 Task 7 wires it" while 27-00
#   mentions this script ZERO times and its own AC-7.3 (`grep -c 'chmod +x'` == 2)
#   actively forbids a third step. A dropped handover must be visible in the ARTIFACT,
#   not only in a plan document nobody re-reads.
#
# BOUNDARY AGAINST THE OTHER TWO GATES — none subsumes another:
#   check-alert-rules.sh    (this)  STATIC.  Syntax + label/annotation completeness +
#                                   runbook coverage. Needs no running Prometheus.
#   check-alert-metrics.sh          LIVE.    Per-rule SERIES-SELECTOR existence, plus the
#                                   inverted dormant-rule wake-up guard.
#   check-alert-liveness.sh (27-00) LIVE.    Scrape-target health, exporter self-report,
#                                   subject correctness, and Alertmanager DELIVERY.
#
# THE FIVE ASSERTIONS
#   S-1  `promtool check rules` succeeds.
#   S-2  Every LIVE rule carries severity + component + service labels
#        (`service` exempt only via LABEL_EXEMPT, with a written reason each).
#   S-3  Every LIVE rule carries `summary` AND `description` annotations.
#   S-4  Every LIVE rule has a `## <AlertName>` heading in docs/runbooks/alerts.md.
#   S-5  Every DORMANT_RULES entry ALSO has such a heading — that section is where the
#        re-enable trigger lives, so a dormant rule with no section is a rule nobody can
#        wake up.
#
# WHAT "LIVE" MEANS (the comment rule — get this wrong and the gate's own count lies)
#   A line declares an alert ONLY if it matches
#       ^[[:space:]]*-[[:space:]]*alert:[[:space:]]*
#   A `#` anywhere before the `-` disqualifies it. Measured on this file: 19 live and 3
#   commented (DiskSpaceLow, DiskSpaceCritical, StompBrokerLag), so a comment-blind
#   extractor would demand 22 runbook headings instead of 19.
#
# EXIT CODES — uniform across this plan's gates
#   0 = clean · 1 = violation · 2 = VOID (could not evaluate)
#   VOID on: missing docker (promtool is NOT installed on the host, so no docker means
#   the check did not run), an unreadable rules or runbook file, or ZERO live rules
#   extracted. "Found nothing" is never "clean".
#
# PIPEFAIL NOTE — every match here is a here-string, never a pipe into grep -q. Under
#   `set -o pipefail` the pipe form INVERTS on match: grep exits at the first hit, the
#   writer takes SIGPIPE, and pipefail promotes 141. That exact bug has already made one
#   guard in this repo fail OPEN. (Note the assertion that checks this cannot be a bare
#   count of the forbidden token, because THIS COMMENT contains it — the check must be
#   scoped to non-comment lines or it fires on its own definition.)
#
# USAGE
#   bash scripts/check-alert-rules.sh [path/to/alerts.yml] [path/to/runbook.md]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RULES_FILE="${1:-$REPO_ROOT/infra/monitoring/prometheus/alerts.yml}"
RUNBOOK="${2:-$REPO_ROOT/docs/runbooks/alerts.md}"
PROM_IMAGE="${PROM_IMAGE:-prom/prometheus:v2.48.0}"

VIOLATIONS=0
violation() { echo "FAIL: $*" >&2; VIOLATIONS=$((VIOLATIONS + 1)); }
void()      { echo "VOID: $*" >&2; exit 2; }

# ---------------------------------------------------------------------------------
# LABEL_EXEMPT — rules deliberately missing ONE named label, with a reason each.
#
# Format: AlertName|label|reason
#
# Hygiene, identical to k8s/scripts/check-env-contract.sh: an empty reason FAILS, a
# duplicate FAILS, and an entry whose rule NOW carries the label FAILS as STALE. The
# stale arm is the load-bearing one — an exemption that outlives its reason is how a
# gate quietly stops gating.
#
# DO NOT widen S-2 to "service optional" instead. That would silently re-admit the
# wrong-JVM defect (a static rule label overrides the series' own) everywhere in the
# file, which is the opposite of what these two entries record.
# ---------------------------------------------------------------------------------
LABEL_EXEMPT=(
  "HighMemoryUsage|service|jvm_memory_used_bytes is emitted by EVERY scraped JVM (measured: service=keycloak AND service=core-api). A static rule label OVERRIDES the series' own label of the same name, so hard-coding service:core-java here made the alert name the wrong JVM in its own annotation. The label is deliberately absent so {{ \$labels.service }} reports what was measured. Phase 27 plan 27-03 D-11 / finding F-11."
  "FrequentGarbageCollection|service|Same defect, same fix as HighMemoryUsage: jvm_gc_pause_seconds_count is emitted by every scraped JVM, so a static service label would misattribute the alert. Phase 27 plan 27-03 D-11 / finding F-11."
)

# ---------------------------------------------------------------------------------
# DORMANT_RULES — rules deliberately commented out, which STILL need a runbook section.
#
# Format: AlertName|reason|wake-trigger
#
# The live gate (check-alert-metrics.sh) owns the inverted data assertion for these.
# This gate owns only the documentation half: a dormant rule with no runbook section is
# a rule nobody can wake up, because the section is where the trigger is written down.
# Keep the two lists in step.
# ---------------------------------------------------------------------------------
DORMANT_RULES=(
  "StompBrokerLag|The canonical local runtime is STOMP_BROKER_MODE=in-memory, so nothing is relayed to RabbitMQ and no stomp-subscription*/amq.gen-* queue can exist; relay mode is a k8s-base setting and k8s/ ships no Prometheus. Inapplicable in BOTH runtimes today.|Issue #304 (make stomp-relay.spec.ts establish a real SUBSCRIBE in relay mode), or any deliberate STOMP_BROKER_MODE=relay."
  "DiskSpaceLow|node_filesystem_* series are emitted only by node-exporter, which is not deployed.|Issue #98 — node-exporter scraping."
  "DiskSpaceCritical|node_filesystem_* series are emitted only by node-exporter, which is not deployed.|Issue #98 — node-exporter scraping."
)

# =================================================================================
# Preconditions — every one is VOID, never a silent pass
# =================================================================================
command -v docker >/dev/null 2>&1 \
  || void "docker not found. promtool is NOT installed on this host, so without docker S-1 cannot run at all — and a check that did not run is not a check that passed."
[ -r "$RULES_FILE" ] || void "rules file not readable: $RULES_FILE"
[ -r "$RUNBOOK" ]    || void "runbook not readable: $RUNBOOK"

echo "check-alert-rules  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  rules   : $RULES_FILE"
echo "  runbook : $RUNBOOK"

# =================================================================================
# S-1 — promtool
#
# INVOCATION MATTERS. `docker run … prom/prometheus promtool check rules …` FAILS with
# `prometheus: error: unexpected promtool` because the image ENTRYPOINT is
# /bin/prometheus. The working form overrides the entrypoint. The `docker exec` form
# below is the documented fallback for a host that cannot pull the image; it works
# because docker-compose.monitoring.yml bind-mounts the repo file read-only at that
# path, so it validates the working tree rather than a copy.
# =================================================================================
RULES_DIR="$(cd "$(dirname "$RULES_FILE")" && pwd)"
RULES_BASE="$(basename "$RULES_FILE")"

set +e
PROMTOOL_OUT="$(docker run --rm --entrypoint=promtool \
  -v "$RULES_DIR:/rules:ro" "$PROM_IMAGE" check rules "/rules/$RULES_BASE" 2>&1)"
PROMTOOL_RC=$?
set -e

if [ "$PROMTOOL_RC" -ne 0 ] && grep -q 'Unable to find image\|Cannot connect to the Docker daemon\|manifest unknown' <<<"$PROMTOOL_OUT"; then
  echo "  note: could not run promtool from $PROM_IMAGE, trying the docker exec fallback"
  set +e
  PROMTOOL_OUT="$(docker exec jtoye-prometheus promtool check rules /etc/prometheus/alerts.yml 2>&1)"
  PROMTOOL_RC=$?
  set -e
  [ "$PROMTOOL_RC" -ne 0 ] && grep -q 'No such container' <<<"$PROMTOOL_OUT" \
    && void "neither the image nor the running container could run promtool: $PROMTOOL_OUT"
fi

if [ "$PROMTOOL_RC" -ne 0 ]; then
  violation "S-1 promtool check rules failed:"$'\n'"$PROMTOOL_OUT"
fi
PROMTOOL_COUNT="$(sed -n 's/.*SUCCESS: \([0-9]*\) rules found.*/\1/p' <<<"$PROMTOOL_OUT" | head -1)"
PROMTOOL_COUNT="${PROMTOOL_COUNT:-0}"

# =================================================================================
# Extraction — one record per LIVE rule:  name<TAB>hasSeverity hasComponent hasService
#                                              hasSummary hasDescription
#
# State machine rather than grep, because `service:` also legitimately appears inside a
# description string and inside this file's own comments. Only keys at LABEL/ANNOTATION
# indentation, inside the correct block, are counted.
# =================================================================================
EXTRACT="$(awk '
  # A line declares an alert ONLY in this exact shape. A leading # disqualifies it.
  /^[[:space:]]*-[[:space:]]*alert:[[:space:]]*/ {
      if (name != "") emit()
      name = $0
      sub(/^[[:space:]]*-[[:space:]]*alert:[[:space:]]*/, "", name)
      sub(/[[:space:]]*(#.*)?$/, "", name)
      sev=0; comp=0; svc=0; summ=0; desc=0; block=""
      next
  }
  # A new rules: group ends the current rule.
  /^[[:space:]]*-[[:space:]]*name:[[:space:]]*/ { if (name != "") { emit(); name="" } next }
  {
      if (name == "") next
      if ($0 ~ /^[[:space:]]*#/) next                       # a comment inside a rule body
      if ($0 ~ /^[[:space:]]+labels:[[:space:]]*$/)      { block="labels";      next }
      if ($0 ~ /^[[:space:]]+annotations:[[:space:]]*$/) { block="annotations"; next }
      if (block == "labels") {
          if ($0 ~ /^[[:space:]]+severity:/)  sev=1
          if ($0 ~ /^[[:space:]]+component:/) comp=1
          if ($0 ~ /^[[:space:]]+service:/)   svc=1
      } else if (block == "annotations") {
          if ($0 ~ /^[[:space:]]+summary:/)     summ=1
          if ($0 ~ /^[[:space:]]+description:/) desc=1
      }
  }
  END { if (name != "") emit() }
  function emit() { printf "%s\t%d %d %d %d %d\n", name, sev, comp, svc, summ, desc }
' "$RULES_FILE")"

LIVE_COUNT="$(printf '%s\n' "$EXTRACT" | grep -c . || true)"
[ "$LIVE_COUNT" -gt 0 ] || void "extracted ZERO live alert rules from $RULES_FILE — the extractor or the file is wrong, and 'no rules to check' must never report clean"

COMMENTED_COUNT="$(grep -cE '^[[:space:]]*#[[:space:]]*-[[:space:]]*alert:[[:space:]]*' "$RULES_FILE" || true)"

# =================================================================================
# LABEL_EXEMPT hygiene — checked BEFORE the exemptions are honoured
# =================================================================================
declare -A EXEMPT_MAP=()
for entry in "${LABEL_EXEMPT[@]}"; do
  IFS='|' read -r ex_name ex_label ex_reason <<<"$entry"
  [ -n "${ex_name:-}" ] && [ -n "${ex_label:-}" ] \
    || { violation "LABEL_EXEMPT entry is malformed: '$entry'"; continue; }
  [ -n "${ex_reason:-}" ] \
    || violation "LABEL_EXEMPT '$ex_name|$ex_label' has an EMPTY reason — an exemption with no written reason is indistinguishable from an oversight"
  key="$ex_name|$ex_label"
  [ -n "${EXEMPT_MAP[$key]:-}" ] && violation "LABEL_EXEMPT has a DUPLICATE entry: $key"
  EXEMPT_MAP[$key]=1

  rec="$(printf '%s\n' "$EXTRACT" | awk -F'\t' -v n="$ex_name" '$1==n {print $2}')"
  if [ -z "$rec" ]; then
    violation "LABEL_EXEMPT names '$ex_name', which is not a live rule in $RULES_FILE — STALE exemption, remove it"
  else
    read -r h_sev h_comp h_svc _ _ <<<"$rec"
    case "$ex_label" in
      severity)  present="$h_sev"  ;;
      component) present="$h_comp" ;;
      service)   present="$h_svc"  ;;
      *)         present=0 ;;
    esac
    [ "$present" = "1" ] && violation "LABEL_EXEMPT '$ex_name' is exempt from '$ex_label' but the rule NOW CARRIES that label — STALE exemption. Either remove the label or remove the exemption; do not leave both."
  fi
done

# =================================================================================
# S-2 / S-3 / S-4 — per live rule
# =================================================================================
while IFS=$'\t' read -r name flags; do
  [ -n "$name" ] || continue
  read -r h_sev h_comp h_svc h_summ h_desc <<<"$flags"

  [ "$h_sev"  = "1" ] || violation "S-2 rule '$name' has no 'severity' label"
  [ "$h_comp" = "1" ] || violation "S-2 rule '$name' has no 'component' label"
  if [ "$h_svc" != "1" ] && [ -z "${EXEMPT_MAP["$name|service"]:-}" ]; then
    violation "S-2 rule '$name' has no 'service' label and is not in LABEL_EXEMPT"
  fi
  [ "$h_summ" = "1" ] || violation "S-3 rule '$name' has no 'summary' annotation"
  [ "$h_desc" = "1" ] || violation "S-3 rule '$name' has no 'description' annotation"

  grep -qxF "## $name" "$RUNBOOK" \
    || violation "S-4 live rule '$name' has no '## $name' heading in $RUNBOOK — an alert an operator cannot look up is an alert they will learn to ignore"
done <<<"$EXTRACT"

# =================================================================================
# S-5 — dormant rules need a section too
# =================================================================================
declare -A DORMANT_SEEN=()
for entry in "${DORMANT_RULES[@]}"; do
  IFS='|' read -r d_name d_reason d_trigger <<<"$entry"
  [ -n "${d_name:-}" ] || { violation "DORMANT_RULES entry is malformed: '$entry'"; continue; }
  [ -n "${d_reason:-}" ]  || violation "DORMANT_RULES '$d_name' has an EMPTY reason"
  [ -n "${d_trigger:-}" ] || violation "DORMANT_RULES '$d_name' has an EMPTY wake-trigger — a dormant rule with no trigger never wakes"
  [ -n "${DORMANT_SEEN[$d_name]:-}" ] && violation "DORMANT_RULES has a DUPLICATE entry: $d_name"
  DORMANT_SEEN[$d_name]=1

  if printf '%s\n' "$EXTRACT" | awk -F'\t' -v n="$d_name" '$1==n {found=1} END {exit !found}'; then
    violation "DORMANT_RULES names '$d_name' but it is LIVE in $RULES_FILE — STALE entry, remove it (and remove it from check-alert-metrics.sh's DORMANT_RULES too)"
  fi
  grep -qxF "## $d_name" "$RUNBOOK" \
    || violation "S-5 dormant rule '$d_name' has no '## $d_name' heading in $RUNBOOK — that section is where its re-enable trigger lives"
done

# =================================================================================
echo "  promtool: rc=$PROMTOOL_RC, $PROMTOOL_COUNT rules found"
echo "  extracted: $LIVE_COUNT live rule(s), $COMMENTED_COUNT commented-out '- alert:' block(s) skipped by the comment rule"
echo "  exemptions: ${#LABEL_EXEMPT[@]} LABEL_EXEMPT, ${#DORMANT_RULES[@]} DORMANT_RULES"

if [ "$VIOLATIONS" -gt 0 ]; then
  echo "FAILED: $VIOLATIONS static alert-rule violation(s)." >&2
  exit 1
fi
echo "PASS: $LIVE_COUNT live rule(s) valid, labelled, annotated and documented; ${#DORMANT_RULES[@]} dormant rule(s) documented; $COMMENTED_COUNT commented block(s) correctly skipped."
exit 0
