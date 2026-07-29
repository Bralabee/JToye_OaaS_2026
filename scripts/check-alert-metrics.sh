#!/usr/bin/env bash
# check-alert-metrics.sh — the LIVE half of the alert-quality gate.
#
# WHY THIS EXISTS — the defect it pins is `StompBrokerLag`, finding F-3 (plan 27-03)
#
#   That rule selected
#       rabbitmq_queue_messages_ready{queue=~"stomp-subscription.*|amq[.]gen-.*"}
#   against a series family emitted by RabbitMQ's AGGREGATED /metrics endpoint, which
#   carries NO `queue` label at all. Measured 2026-07-29:
#       query=rabbitmq_queue_messages_ready
#         -> ONE series, value 9, metric keys [__name__ component instance job service]
#   The metric existed. The value was non-zero. The LABEL did not exist, so the selector
#   matched nothing, the rule could never fire, and nine real dead messages sat
#   unreported on webhook.deliveries.dlq for eleven days. `promtool check rules` passed
#   the file throughout.
#
#   THE ASSERTION IS ON THE SELECTOR, NOT THE EXPRESSION, AND THAT IS THE WHOLE POINT.
#   `X > 100` legitimately returns empty on a healthy system; querying the whole
#   expression would therefore be noise. `X{queue="…"}` returning empty means the rule
#   is structurally incapable of firing, whatever the system is doing. And checking the
#   LABEL SET, not just the metric name, is what catches the real defect — the metric
#   name alone was always fine.
#
# WHY THIS GATE IS NOT WIRED INTO CI — identical reasoning to check-runtime-freshness.sh
#   and check-alert-liveness.sh: a CI runner has no Prometheus, so this could only ever
#   exit 2 (VOID) there, and a permanently-VOID job trains people to add `|| true`, which
#   is worse than no job. Its enforcement is the same as check-alert-liveness.sh's — the
#   exit code and an ISO-8601 timestamp are a required field in the phase SUMMARY.
#   (check-alert-rules.sh, the STATIC half, IS CI-wireable and is wired by plan 27-06.)
#
# BOUNDARY AGAINST check-alert-liveness.sh (27-00 Task 4) — NEITHER SUBSUMES THE OTHER
#   check-alert-liveness.sh asserts scrape-TARGET health, exporter self-report, subject
#   correctness and ALERTMANAGER DELIVERY. This script asserts PER-RULE SERIES-SELECTOR
#   EXISTENCE and, inverted, that a DORMANT rule still has no data. A target can be up
#   while a rule is blind; a rule can have data while nothing is delivered. Run both.
#
# THE TWO HALVES
#   M-1  Every LIVE rule's bare series selectors match >= 1 series.
#        Exceptions live in KNOWN_DATALESS, with a written reason and owner each.
#   M-2  Every DORMANT_RULES selector matches ZERO series. INVERTED on purpose: a
#        dormant rule that starts having data must go RED saying "re-enable it". The red
#        IS the instruction. This is why there is no EXPECT_EMPTY allowlist — dormancy is
#        expressed by commenting the rule out and listing it here, which is strictly
#        stronger because it also fails when the situation reverses and a disabled rule
#        would otherwise rot into permanent silence.
#
# EXIT CODES — uniform across this plan's gates
#   0 = clean · 1 = violation · 2 = VOID (could not evaluate)
#   VOID on: missing jq or curl, unreachable Prometheus, an unparseable /api/v1/query
#   response, or ZERO extracted live selectors. "Found nothing" is never "clean".
#
# PIPEFAIL NOTE — every match here is a here-string (`grep -q X <<<"$out"`), never
#   `cmd | grep -q X`. Under `set -o pipefail` the pipe form INVERTS on match: grep exits
#   early, the writer takes SIGPIPE, and pipefail promotes 141. That exact bug has
#   already made one guard in this repo fail OPEN.
#
# USAGE
#   bash scripts/check-alert-metrics.sh [path/to/alerts.yml]
#   PROM_URL=http://host:9090 bash scripts/check-alert-metrics.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RULES_FILE="${1:-$REPO_ROOT/infra/monitoring/prometheus/alerts.yml}"
PROM_URL="${PROM_URL:-http://localhost:9091}"

VIOLATIONS=0
violation() { echo "FAIL: $*" >&2; VIOLATIONS=$((VIOLATIONS + 1)); }
void()      { echo "VOID: $*" >&2; exit 2; }

# ---------------------------------------------------------------------------------
# KNOWN_DATALESS — live rules whose selector matches zero series TODAY, each with a
# written reason and an owner.
#
# Format: AlertName|reason|owner
#
# THIS LIST IS NOT A WEAKENING, AND ITS HYGIENE IS WHAT MAKES THAT TRUE:
#   - an entry naming a rule that is not live   -> FAIL as STALE
#   - an entry whose selectors NOW match series -> FAIL as STALE ("remove this entry")
#   - an empty reason or owner                  -> FAIL
#   - a duplicate                               -> FAIL
# So the red is always an instruction and an entry cannot outlive its reason. A NEW rule
# with a dataless selector is a hard violation — which is the assertion this gate exists
# for.
#
# These entries are PRE-EXISTING defects of the same class as StompBrokerLag, found by
# this gate on its first run, in rules plan 27-03 was explicitly forbidden to edit ("do
# not touch any other rule in the file"). They are recorded here rather than silently
# exempted, and are logged in the phase's deferred-items.md.
#
# TWO WERE REMOVED ON 2026-07-29, and the gate is what said so. Both went STALE the
# moment the thing they described stopped being true, and the red carried the
# instruction:
#
#   HighResponseTime  PR #343 enabled management.metrics.distribution.percentiles-
#                     histogram, so http_server_requests_seconds_bucket went 0 -> 74
#                     series. #343 fixed the data and left the exemption, so this gate
#                     was red on main from the moment it merged. It is NOT in CI (it
#                     needs a live Prometheus), so nothing in the pipeline noticed.
#   NoOrdersCreated   #343 corrected the selector to include /public/shops/{slug}/orders
#                     — the reason text above still quoted the OLD selector, which no
#                     longer existed in the file — and a guest order was then placed
#                     (ORD-00000000-20260729-63EB83BC), so the series is live.
#
# This is the documented lifecycle, not an exception to it: the HighErrorRate entry
# below states it outright — "Remove this entry the first time a 5xx is served: the rule
# then has data and needs no exemption." HighErrorRate stays because no 5xx has been
# served yet.
#
# CONSEQUENCE, RECORDED RATHER THAN HIDDEN: NoOrdersCreated is now covered by M-1 on its
# own merits, so a stack on which NO order has ever been created will fail M-1 for it.
# That is the intended trade — this gate grades a LIVE runtime, and "no order has ever
# been placed here" is a fact about that runtime worth surfacing rather than exempting
# in perpetuity. Re-adding an entry is the wrong fix; placing an order is the right one.
# ---------------------------------------------------------------------------------
KNOWN_DATALESS=(
  "HighErrorRate|http_server_requests_seconds_count{status=~\"5..\"} matches zero series because no 5xx has been recorded on this stack yet. Unlike the removed HighResponseTime entry the metric family and the status label BOTH exist, so this is a condition that has not occurred rather than a structural defect — but a selector matching nothing still cannot fire, so it is recorded rather than waved through. Remove this entry the first time a 5xx is served: the rule then has data and needs no exemption.|Phase 27 deferred-items.md — pre-existing."
)

# ---------------------------------------------------------------------------------
# DORMANT_RULES — commented-out rules whose selector must stay EMPTY.
#
# Format: AlertName|reason|wake-trigger|SELECTOR
#
# The SELECTOR is deliberately LAST. A PromQL regex alternation contains `|`
# (`queue=~"stomp-subscription.*|amq[.]gen-.*"`), and bash's `read` assigns everything
# after the final named field to that field WITH its delimiters intact — so putting the
# selector last is the only split that cannot corrupt it. A selector-first format
# silently truncated the StompBrokerLag entry at its own alternation while still
# querying successfully, i.e. it would have reported a PASS on half a selector.
#
# Keep in step with check-alert-rules.sh's DORMANT_RULES (which asserts each has a
# runbook section — that section is where the trigger is written down).
#
# NOT A FAIL-ON-SUCCESS TRAP, and the difference matters. An EXPECT_EMPTY-style entry on
# StompBrokerLag would go red the moment a kitchen screen connects — a normal user action
# with no operator meaning. This entry can only go red if someone sets
# STOMP_BROKER_MODE=relay, a deliberate configuration change whose correct response is
# precisely "re-enable the rule". Verified: the canonical runtime is in-memory, the broker
# has zero STOMP connections, and smoke-test-stomp-relay.sh sends no SUBSCRIBE frame.
#
# RESIDUAL, RECORDED RATHER THAN HIDDEN: the selector is a NAME PATTERN, so this also
# fires on any queue coincidentally named stomp-subscription*/amq.gen-*, and the only
# remedy for that is a MANUAL edit of this list. Track that red to closure like any
# other — an entry whose only fix is hand-editing a gate is exactly the kind someone
# silences instead.
# ---------------------------------------------------------------------------------
DORMANT_RULES=(
  'StompBrokerLag|The canonical local runtime is STOMP_BROKER_MODE=in-memory, so nothing is relayed to RabbitMQ and no such queue can exist; relay mode is k8s-only and k8s/ ships no Prometheus.|Issue #304, or any deliberate STOMP_BROKER_MODE=relay.|rabbitmq_detailed_queue_messages_ready{queue=~"stomp-subscription.*|amq[.]gen-.*"}'
  'DiskSpaceLow|node_filesystem_* is emitted only by node-exporter, which is not deployed.|Issue #98 — node-exporter scraping.|node_filesystem_avail_bytes{mountpoint="/",fstype!="tmpfs"}'
  'DiskSpaceCritical|node_filesystem_* is emitted only by node-exporter, which is not deployed.|Issue #98 — node-exporter scraping.|node_filesystem_size_bytes{mountpoint="/",fstype!="tmpfs"}'
)

# =================================================================================
# Preconditions — every one is VOID, never a silent pass
# =================================================================================
command -v jq   >/dev/null 2>&1 || void "jq not found — the /api/v1/query response cannot be parsed, so nothing was checked"
command -v curl >/dev/null 2>&1 || void "curl not found — Prometheus cannot be queried, so nothing was checked"
[ -r "$RULES_FILE" ] || void "rules file not readable: $RULES_FILE"

if ! curl -sf --max-time 10 "$PROM_URL/-/healthy" >/dev/null 2>&1; then
  curl -sf --max-time 10 "$PROM_URL/api/v1/status/config" >/dev/null 2>&1 \
    || void "Prometheus unreachable at $PROM_URL — this gate cannot evaluate and must not report clean"
fi

echo "check-alert-metrics  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  rules      : $RULES_FILE"
echo "  prometheus : $PROM_URL"

# ---------------------------------------------------------------------------------
# query_count <selector>  ->  prints the number of series, or VOIDs on an unparseable
# or errored response. An error must never read as "0 series" — that would turn a
# malformed selector into a silent violation report instead of a VOID.
# ---------------------------------------------------------------------------------
query_count() {
  local sel="$1" resp status
  resp="$(curl -s --max-time 20 -G "$PROM_URL/api/v1/query" --data-urlencode "query=$sel" 2>/dev/null)" \
    || void "query failed for selector: $sel"
  [ -n "$resp" ] || void "EMPTY response from $PROM_URL for selector: $sel"
  status="$(jq -r '.status // "MISSING"' <<<"$resp" 2>/dev/null)" \
    || void "unparseable JSON from $PROM_URL for selector: $sel"
  if [ "$status" != "success" ]; then
    void "Prometheus rejected the selector (status=$status, $(jq -r '.error // "no error field"' <<<"$resp")): $sel"
  fi
  jq -r '.data.result | length' <<<"$resp"
}

# =================================================================================
# Extraction — LIVE rule name + its bare series selectors, one per output line as
#   <AlertName>\t<selector>
#
# The stripper walks the expression character by character rather than grepping,
# because a grep cannot distinguish `service` the METRIC from `service` inside a
# `by (service, le)` grouping clause, and a rule like HighResponseTime contains both
# shapes. Removed along the way: aggregation and function calls (any identifier
# immediately followed by `(`), grouping clauses and their argument lists, range
# selectors ([5m]), string literals, numbers, operators and boolean joins. What is left
# is `metric_name` or `metric_name{label="v",label2=~"re"}` — exactly the thing whose
# emptiness proves a rule cannot fire.
# =================================================================================
EXTRACT="$(awk '
  function flush(   e) {
      if (name == "" ) return
      e = expr
      gsub(/\[[0-9]+[smhdwy]\]/, "", e)          # range selectors
      strip(name, e)
      name = ""; expr = ""
  }
  function strip(rule, s,   i, n, c, tok, j, nxt, sel, k, ch, depth) {
      n = length(s); i = 1
      while (i <= n) {
          c = substr(s, i, 1)
          if (c == "\"") {                        # skip a string literal wholesale
              i++
              while (i <= n) { ch = substr(s, i, 1); if (ch == "\\") { i += 2; continue }; if (ch == "\"") break; i++ }
              i++; continue
          }
          if (c !~ /[A-Za-z_:]/) { i++; continue }
          tok = ""
          while (i <= n && substr(s, i, 1) ~ /[A-Za-z0-9_:]/) { tok = tok substr(s, i, 1); i++ }
          j = i
          while (j <= n && substr(s, j, 1) == " ") j++
          nxt = substr(s, j, 1)
          # A GROUPING clause — by (service, le) — must have its whole argument list
          # skipped, not merely its keyword. Those identifiers are LABEL names, and
          # emitting them as metric selectors makes the gate query `service` and `le`,
          # which match nothing and produce false violations on correct rules. Measured:
          # HighErrorRate and HighResponseTime each leaked one, and they were invisible
          # only because both rules happened to be on the exemption list.
          if (tok ~ /^(by|without|on|ignoring|group_left|group_right)$/ && nxt == "(") {
              depth = 0; k = j
              while (k <= n) {
                  ch = substr(s, k, 1)
                  if (ch == "(") depth++
                  else if (ch == ")") { depth--; if (depth == 0) break }
                  k++
              }
              i = k + 1; continue
          }
          if (nxt == "(") { i = j + 1; continue }              # a function/aggregation call
          if (tok ~ /^(offset|bool|and|or|unless|group_left|group_right)$/) { i = j; continue }
          sel = tok
          if (nxt == "{") {
              depth = 0; k = j
              while (k <= n) {
                  ch = substr(s, k, 1); sel = sel ch
                  if (ch == "{") depth++
                  else if (ch == "}") { depth--; if (depth == 0) break }
                  k++
              }
              i = k + 1
          } else { i = j }
          printf "%s\t%s\n", rule, sel
      }
  }
  /^[[:space:]]*-[[:space:]]*alert:[[:space:]]*/ {
      flush()
      name = $0
      sub(/^[[:space:]]*-[[:space:]]*alert:[[:space:]]*/, "", name)
      sub(/[[:space:]]*(#.*)?$/, "", name)
      inexpr = 0; expr = ""
      next
  }
  /^[[:space:]]*-[[:space:]]*name:[[:space:]]*/ { flush(); next }
  {
      if (name == "") next
      if ($0 ~ /^[[:space:]]*#/) next
      if ($0 ~ /^[[:space:]]+expr:[[:space:]]*\|[[:space:]]*$/) { inexpr = 1; next }   # block scalar
      if ($0 ~ /^[[:space:]]+expr:[[:space:]]*/) {
          line = $0; sub(/^[[:space:]]+expr:[[:space:]]*/, "", line)
          expr = expr " " line; inexpr = 0; next
      }
      if (inexpr) {
          if ($0 ~ /^[[:space:]]+(for|labels|annotations|keep_firing_for):/) { inexpr = 0 }
          else { expr = expr " " $0; next }
      }
  }
  END { flush() }
' "$RULES_FILE")"

LIVE_RULES="$(printf '%s\n' "$EXTRACT" | cut -f1 | sort -u | grep -c . || true)"
SELECTORS="$(printf '%s\n' "$EXTRACT" | grep -c . || true)"

# An auditable extractor is the difference between a gate and a claim: a stripper that
# silently drops the one selector that matters would report a confident PASS. Dump it.
if [ -n "${DEBUG_SELECTORS:-}" ]; then
  echo "--- extracted rule -> selector ---"
  printf '%s\n' "$EXTRACT" | sed 's/^/    /'
  echo "--- end ---"
fi
[ "$SELECTORS" -gt 0 ] \
  || void "extracted ZERO series selectors from $RULES_FILE — the stripper or the file is wrong, and 'nothing to check' must never report clean"

# =================================================================================
# KNOWN_DATALESS hygiene, evaluated BEFORE the exemptions are honoured
# =================================================================================
declare -A DATALESS_MAP=()
for entry in "${KNOWN_DATALESS[@]}"; do
  IFS='|' read -r kd_name kd_reason kd_owner <<<"$entry"
  [ -n "${kd_name:-}" ] || { violation "KNOWN_DATALESS entry is malformed: '$entry'"; continue; }
  [ -n "${kd_reason:-}" ] || violation "KNOWN_DATALESS '$kd_name' has an EMPTY reason"
  [ -n "${kd_owner:-}" ]  || violation "KNOWN_DATALESS '$kd_name' has an EMPTY owner — an exemption nobody owns is never removed"
  [ -n "${DATALESS_MAP[$kd_name]:-}" ] && violation "KNOWN_DATALESS has a DUPLICATE entry: $kd_name"
  DATALESS_MAP[$kd_name]=1
  printf '%s\n' "$EXTRACT" | awk -F'\t' -v n="$kd_name" '$1==n {f=1} END {exit !f}' \
    || violation "KNOWN_DATALESS names '$kd_name', which is not a live rule in $RULES_FILE — STALE entry, remove it"
done

# =================================================================================
# M-1 — every live rule's selectors must match >= 1 series
# =================================================================================
declare -A RULE_EMPTY=()
CHECKED=0
while IFS=$'\t' read -r rule sel; do
  [ -n "$rule" ] || continue
  CHECKED=$((CHECKED + 1))
  count="$(query_count "$sel")"
  if [ "$count" -lt 1 ]; then
    RULE_EMPTY["$rule"]="${RULE_EMPTY["$rule"]:-}${sel}"$'\n'
    if [ -z "${DATALESS_MAP[$rule]:-}" ]; then
      violation "M-1 rule '$rule' selector matches ZERO series — this rule can never fire: $sel"
    fi
  fi
done <<<"$EXTRACT"

# STALE arm: an exemption whose rule now has data everywhere must be removed.
for name in "${!DATALESS_MAP[@]}"; do
  [ -n "${RULE_EMPTY[$name]:-}" ] \
    || violation "KNOWN_DATALESS '$name' now matches >= 1 series on EVERY selector — STALE exemption, remove it (the rule is covered by M-1 on its own merits now)"
done

# =================================================================================
# M-2 — the inverted dormant-rule wake-up guard
# =================================================================================
declare -A DORMANT_SEEN=()
for entry in "${DORMANT_RULES[@]}"; do
  # Selector LAST: `read` puts everything after the third '|' into d_sel with its own
  # delimiters intact, so a regex alternation inside the selector survives the split.
  IFS='|' read -r d_name d_reason d_trigger d_sel <<<"$entry"

  [ -n "${d_name:-}" ] || { violation "DORMANT_RULES entry is malformed: '$entry'"; continue; }
  [ -n "${d_sel:-}" ]  || { violation "DORMANT_RULES '$d_name' has an EMPTY selector"; continue; }
  [ -n "${d_reason:-}" ]  || violation "DORMANT_RULES '$d_name' has an EMPTY reason"
  [ -n "${d_trigger:-}" ] || violation "DORMANT_RULES '$d_name' has an EMPTY wake-trigger"
  [ -n "${DORMANT_SEEN[$d_name]:-}" ] && violation "DORMANT_RULES has a DUPLICATE entry: $d_name"
  DORMANT_SEEN[$d_name]=1

  if printf '%s\n' "$EXTRACT" | awk -F'\t' -v n="$d_name" '$1==n {f=1} END {exit !f}'; then
    violation "DORMANT_RULES names '$d_name' but it is LIVE in $RULES_FILE — STALE entry, remove it"
  fi

  count="$(query_count "$d_sel")"
  CHECKED=$((CHECKED + 1))
  if [ "$count" -ge 1 ]; then
    violation "M-2 DORMANT rule '$d_name' NOW HAS DATA ($count series) — re-enable it. Wake trigger: $d_trigger. Selector: $d_sel"
  fi
done

# =================================================================================
echo "  live rules : $LIVE_RULES"
echo "  selectors  : $SELECTORS extracted, $CHECKED queried (incl. ${#DORMANT_RULES[@]} dormant)"
echo "  exemptions : ${#KNOWN_DATALESS[@]} KNOWN_DATALESS, ${#DORMANT_RULES[@]} DORMANT_RULES"

if [ "$VIOLATIONS" -gt 0 ]; then
  echo "FAILED: $VIOLATIONS live alert-metric violation(s)." >&2
  exit 1
fi
echo "PASS: $LIVE_RULES live rule(s) / $SELECTORS selector(s) all match >= 1 live series (${#KNOWN_DATALESS[@]} reasoned exemption(s)); ${#DORMANT_RULES[@]} dormant rule(s) still correctly have no data."
exit 0
