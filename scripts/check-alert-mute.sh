#!/usr/bin/env bash
# check-alert-mute.sh — the alert-MUTE gate.
#
# WHY THIS EXISTS
#
#   A notification mute is the one piece of monitoring config whose failure mode is
#   SILENCE. Every other gate in this directory asks "can the monitoring see and
#   tell?"; a mute deliberately makes it not tell, so nothing those gates measure
#   goes red when a mute is wrong, too broad, or has quietly reached production.
#   check-alert-rules.sh reads alerts.yml and would not notice — the rule is
#   untouched. check-alert-metrics.sh queries series existence — unaffected.
#   check-alert-liveness.sh WOULD notice, but only by turning L-3 red and blaming
#   "a DISPATCH fault (routing, grouping, an active silence, an inhibit rule)",
#   which reads as a transport problem rather than as this config.
#
#   So the mute needs its own gate, and it needs a FUNCTIONAL assertion, because a
#   structurally perfect mute block can sit in the config while muting nothing (a
#   receiver name typo, a route ordered after a `continue: true` sibling that
#   already consumed the alert) or while muting everything.
#
# BOUNDARY AGAINST THE OTHER THREE GATES — none subsumes this:
#   check-alert-rules.sh     STATIC.  alerts.yml syntax, labels, runbook coverage.
#   check-alert-metrics.sh   LIVE.    per-rule series-selector existence.
#   check-alert-liveness.sh  LIVE.    scrape health, subject correctness, delivery.
#   check-alert-mute.sh (this) LIVE.  what the RENDERED config withholds, and proof
#                                     that it withholds exactly that and no more.
#
# THE SIX ASSERTIONS
#   M-1  Every mute matcher in the RENDERED config keys on `alertname`.
#   M-2  No mute matcher references severity / component / service. This is not
#        stylistic: scripts/check-alert-liveness.sh posts its L-3 transport probe
#        with severity="info", service="platform". A severity-keyed mute swallows
#        that probe, so the liveness gate would go red for a reason that looks like
#        a transport fault. M-2 pins the constraint independently of that gate.
#   M-3  Every muted alertname is on MUTE_ALLOWLIST below, with a written reason.
#   M-4  Every muted alertname still exists as a rule in alerts.yml. A mute naming
#        a deleted rule is stale config that will silently outlive its subject.
#   M-5  No file under k8s/ sets ALERTMANAGER_MUTE_ALERTNAMES. The mute is a local
#        development affordance; NoOrdersCreated is a real production signal.
#   M-6  FUNCTIONAL. A synthetic MUTED alert produces NO email notification
#        attempt, and a synthetic UNMUTED alert of the SAME severity produces one.
#        Both arms are required — the second is what distinguishes "the mute works"
#        from "notifications are broken entirely", which M-1..M-5 cannot tell apart.
#
# WHY THIS GATE IS NOT IN CI
#
#   Same reasoning as check-runtime-freshness.sh and check-alert-liveness.sh: a CI
#   runner has no Alertmanager, so this could only ever exit 2 there, and a
#   permanently-VOID job trains people to add `|| true`. M-5 is the exception — it
#   is pure static file inspection — and it is deliberately ALSO reachable on its
#   own via `MODE=static` so CI can enforce the production-safety half without a
#   running stack.
#
# READING THE CONFIG: `docker exec`, never `docker cp`, never the host .tmpl.
#   The rendered alertmanager.yml exists ONLY inside the container — compose mounts
#   the .tmpl and entrypoint.sh as individual read-only files and the entrypoint
#   writes the render into the container's own /etc/alertmanager. Reading the host
#   template would prove nothing about what Alertmanager actually loaded, which is
#   the entire question this gate asks.
#
# EXIT CODES:  0 = pass · 1 = violation · 2 = VOID (could not evaluate)
#   A VOID IS NEVER A PASS. Missing tooling, a stopped container, or empty output
#   all exit 2 rather than reporting a clean run over nothing.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RULES_FILE="${RULES_FILE:-$REPO_ROOT/infra/monitoring/prometheus/alerts.yml}"
K8S_DIR="${K8S_DIR:-$REPO_ROOT/k8s}"
AM_CONTAINER="${AM_CONTAINER:-jtoye-alertmanager}"
ALERTMANAGER_URL="${ALERTMANAGER_URL:-http://localhost:9093}"
MODE="${MODE:-full}"          # full | static
M6_TIMEOUT="${M6_TIMEOUT:-90}"
M6_INTERVAL="${M6_INTERVAL:-5}"

VIOLATIONS=0
violation() { echo "FAIL: $*" >&2; VIOLATIONS=$((VIOLATIONS + 1)); }
void()      { echo "VOID: $*" >&2; exit 2; }

# ---------------------------------------------------------------------------------
# MUTE_ALLOWLIST — alertnames this repo permits muting, with a reason each.
#
# Format: AlertName|reason
#
# An entry with an empty reason FAILS and a duplicate FAILS, same hygiene as
# check-alert-rules.sh's LABEL_EXEMPT. Adding a name here is the deliberate act
# that makes muting it possible; the env var alone is not enough.
#
# DO NOT add a critical- or security-severity alert. There is no reason to withhold
# one locally that is not better served by fixing the underlying condition.
# ---------------------------------------------------------------------------------
MUTE_ALLOWLIST="
NoOrdersCreated|info-severity business signal. Fires ~30 min after the last order, so a quiet local stack pages forever. The only alternative remedy (FORCE=1 scripts/seed-order-metric.sh) buys silence by writing a real order row into the dev database on every run. Correct and WANTED in production.
"

echo "check-alert-mute: mode=$MODE container=$AM_CONTAINER"

# =================================================================================
# M-5 (static, runs in every mode) — the mute must not exist in k8s manifests.
# =================================================================================
if [ ! -d "$K8S_DIR" ]; then
  void "k8s directory not found at $K8S_DIR — cannot prove the mute is absent from deploy manifests (an unverifiable absence is not an absence)"
fi

# `grep -r` returns 1 on no-match, which is the PASS case here, so `|| true` is
# correct — but the count is captured on its OWN statement. Reading $? after an
# echo or inside a printf's $( ) reports that command's status instead, which is
# 0 essentially always and would make this assertion incapable of failing.
K8S_HITS="$(grep -rIl 'ALERTMANAGER_MUTE_ALERTNAMES' "$K8S_DIR" 2>/dev/null || true)"
if [ -n "$K8S_HITS" ]; then
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    violation "M-5 $f sets ALERTMANAGER_MUTE_ALERTNAMES. The mute is a LOCAL development affordance; these alerts are real production signals."
  done <<< "$K8S_HITS"
else
  echo "  M-5   no k8s manifest sets ALERTMANAGER_MUTE_ALERTNAMES"
fi

if [ "$MODE" = "static" ]; then
  if [ "$VIOLATIONS" -gt 0 ]; then
    echo "check-alert-mute: $VIOLATIONS violation(s) [static mode]" >&2
    exit 1
  fi
  echo "check-alert-mute: PASS (static mode — M-5 only; M-1..M-4/M-6 need a running Alertmanager)"
  exit 0
fi

# =================================================================================
# Preconditions for the live assertions
# =================================================================================
command -v docker >/dev/null 2>&1 || void "docker not found — cannot read the rendered config"
command -v jq     >/dev/null 2>&1 || void "jq not found — cannot parse the Alertmanager API"
command -v curl   >/dev/null 2>&1 || void "curl not found"
[ -f "$RULES_FILE" ] || void "rules file not found at $RULES_FILE"

STATE="$(docker inspect -f '{{.State.Status}}' "$AM_CONTAINER" 2>/dev/null || true)"
[ -n "$STATE" ] || void "container $AM_CONTAINER does not exist — bring the stack up and re-run"
[ "$STATE" = "running" ] || void "container $AM_CONTAINER is '$STATE', not running"

RENDERED="$(docker exec "$AM_CONTAINER" cat /etc/alertmanager/alertmanager.yml 2>/dev/null || true)"
[ -n "$RENDERED" ] || void "rendered config read from $AM_CONTAINER was EMPTY — an empty read is not a clean config"

# =================================================================================
# M-1 / M-2 / M-3 / M-4 — what the rendered config actually mutes
# =================================================================================

# The mute route is identified by the receiver it names, not by position: a
# position-based read would silently follow the wrong route the moment another
# child route is added.
#
# Both the route boundary AND the matchers-key boundary are explicit. An earlier
# draft terminated only on a sibling `- receiver:` line, which never appears in a
# mute-only render — so the scan ran to end of file and reported the RECEIVERS
# section as mute matchers ("name: email-default does not key on alertname"). It
# failed loudly rather than silently, but only because it was actually run; the
# same shape with a laxer M-1 would have passed over the wrong lines entirely.
MUTE_MATCHERS="$(awk '
  /^[[:space:]]*- receiver: mute-null[[:space:]]*$/ { inmute = 1; inmatch = 0; next }
  inmute && /^[^[:space:]]/            { inmute = 0; inmatch = 0 }   # a top-level key ends the route list
  inmute && /^[[:space:]]*- receiver: / { inmute = 0; inmatch = 0 }   # a sibling route ends this one
  inmute && /^[[:space:]]*matchers:[[:space:]]*$/ { inmatch = 1; next }
  inmute && inmatch && /^[[:space:]]*-[[:space:]]/ { sub(/^[[:space:]]*-[[:space:]]*/, ""); print; next }
  inmute && inmatch && /^[[:space:]]*[A-Za-z_]+:/  { inmatch = 0 }    # another key ends the matchers list
' <<< "$RENDERED" || true)"

if [ -z "$MUTE_MATCHERS" ]; then
  # No mute configured at all is a legitimate, and in fact the DEFAULT, state.
  # Reporting it as a pass is correct; reporting it as "the mute is verified" is
  # not, so the wording distinguishes them.
  echo "  M-1..M-4  no mute route present in the rendered config (mute is UNSET — this is the default and is not a fault)"
  MUTED_NAMES=""
else
  # M-2 runs FIRST and over the WHOLE matcher set, deliberately.
  #
  # An earlier draft ran it per-matcher AFTER M-1, with a `continue` between them.
  # In Alertmanager's `matchers:` list form each entry holds exactly one matcher,
  # so a forbidden label is always its own entry — which M-1 rejects first, and the
  # `continue` then skipped M-2 entirely. M-2 was therefore INCAPABLE OF FIRING:
  # proven by the severity-keyed break arm, which reported only
  #   "M-1 mute matcher 'severity=\"info\"' does not key on alertname".
  # That is a true statement and the wrong diagnosis — it says nothing about the
  # L-3 probe the operator is about to break. Scanning the set first makes M-2
  # reachable and puts the explanation in front of the person who needs it.
  FORBIDDEN="$(grep -E '(^|[^a-z_])(severity|component|service)[=~!]' <<< "$MUTE_MATCHERS" || true)"
  if [ -n "$FORBIDDEN" ]; then
    while IFS= read -r bad; do
      [ -z "$bad" ] && continue
      violation "M-2 mute matcher '$bad' references severity/component/service. scripts/check-alert-liveness.sh posts its L-3 transport probe with severity=\"info\", service=\"platform\" — this mute would swallow that probe and turn L-3 red, where its own failure text blames \"an active silence, an inhibit rule\" and so reads as a transport fault rather than as this config. Mute by alertname only."
    done <<< "$FORBIDDEN"
  fi

  MUTED_NAMES=""
  while IFS= read -r m; do
    [ -z "$m" ] && continue
    # M-1: must key on alertname.
    if ! grep -qE '^alertname[=~!]' <<< "$m"; then
      violation "M-1 mute matcher '$m' does not key on alertname"
      continue
    fi
    # Extract the alternation body: alertname=~"^(A|B)$"  ->  A|B
    body="$(sed -E 's/^alertname=~"\^\((.*)\)\$"$/\1/' <<< "$m")"
    if [ "$body" = "$m" ]; then
      # Not the regex-alternation form; accept a plain equality matcher too.
      body="$(sed -E 's/^alertname="(.*)"$/\1/' <<< "$m")"
      if [ "$body" = "$m" ]; then
        violation "M-1 mute matcher '$m' is not a recognised alertname form"
        continue
      fi
    fi
    MUTED_NAMES="$MUTED_NAMES $(tr '|' ' ' <<< "$body")"
  done <<< "$MUTE_MATCHERS"

  # Allowlist hygiene, evaluated once.
  SEEN_ALLOW=""
  while IFS= read -r entry; do
    [ -z "$entry" ] && continue
    a_name="${entry%%|*}"
    a_reason="${entry#*|}"
    [ -n "$a_name" ] || continue
    if [ -z "$a_reason" ] || [ "$a_reason" = "$a_name" ]; then
      violation "M-3 MUTE_ALLOWLIST entry '$a_name' has no reason"
    fi
    if grep -qw -- "$a_name" <<< "$SEEN_ALLOW"; then
      violation "M-3 MUTE_ALLOWLIST entry '$a_name' is duplicated"
    fi
    SEEN_ALLOW="$SEEN_ALLOW $a_name"
  done <<< "$MUTE_ALLOWLIST"

  for n in $MUTED_NAMES; do
    if ! grep -qw -- "$n" <<< "$SEEN_ALLOW"; then
      violation "M-3 '$n' is muted but is not on MUTE_ALLOWLIST. Add it with a written reason, or remove it from ALERTMANAGER_MUTE_ALERTNAMES."
    fi
    if ! grep -qE "^[[:space:]]*-[[:space:]]*alert:[[:space:]]*$n[[:space:]]*$" "$RULES_FILE"; then
      violation "M-4 '$n' is muted but no such rule exists in $RULES_FILE — stale mute config that will outlive its subject."
    fi
  done

  [ "$VIOLATIONS" -eq 0 ] && echo "  M-1..M-4  muted:$MUTED_NAMES (alertname-only, allowlisted, rules present)"
fi

# =================================================================================
# M-6 — FUNCTIONAL. Does the mute withhold exactly what it claims, and nothing else?
# =================================================================================
#
# Two arms are mandatory. The muted arm alone cannot distinguish "the mute works"
# from "email notifications are broken entirely" — and a gate that reports PASS
# while all notification is dead is worse than no gate.
#
# UNIQUE alertnames per run, per arm. route.group_by is ['alertname','service'] and
# group_interval is 5m, so a CONSTANT alertname lands in the same aggregation group
# on every run and is only notified on a group_interval tick. That was measured on
# this stack for check-alert-liveness.sh L-3 (constant name: run 1 delivered, run 2
# not; unique name: both delivered) and the same mechanism applies here — a
# constant name would make the CONTROL arm flaky and read as a mute regression.

am_counter() {
  curl -sf --max-time 10 "$ALERTMANAGER_URL/metrics" \
    | awk '/^alertmanager_notifications_total\{.*integration="email"/ { total += $2 } END { printf "%d", total+0 }'
}

post_alert() {
  # $1 = alertname, $2 = endsAt (optional)
  local name="$1" ends="${2:-}"
  local payload
  if [ -n "$ends" ]; then
    payload="[{\"labels\":{\"alertname\":\"$name\",\"severity\":\"info\",\"service\":\"platform\"},\"annotations\":{\"summary\":\"check-alert-mute synthetic $name\",\"description\":\"posted by scripts/check-alert-mute.sh — not a real alert\"},\"endsAt\":\"$ends\"}]"
  else
    payload="[{\"labels\":{\"alertname\":\"$name\",\"severity\":\"info\",\"service\":\"platform\"},\"annotations\":{\"summary\":\"check-alert-mute synthetic $name\",\"description\":\"posted by scripts/check-alert-mute.sh — not a real alert\"}}]"
  fi
  curl -sf --max-time 10 -X POST "$ALERTMANAGER_URL/api/v2/alerts" \
    -H 'Content-Type: application/json' -d "$payload" >/dev/null
}

if [ -z "$MUTED_NAMES" ]; then
  echo "  M-6   skipped — nothing is muted, so there is no suppression to prove"
else
  RUN_TOKEN="$(date -u +%s)$$"
  MUTED_NAME="$(awk '{print $1}' <<< "$MUTED_NAMES")"
  CONTROL_NAME="CheckAlertMuteControl${RUN_TOKEN}"

  # The muted arm must use a name the mute regex actually matches. The configured
  # matcher is an exact-anchored alternation, so the probe posts the real alertname
  # — which is safe: it is synthetic, carries its own annotations, and is expired
  # at the end of this function.
  BEFORE="$(am_counter)" || void "cannot read $ALERTMANAGER_URL/metrics"
  [ -n "$BEFORE" ] || void "alertmanager_notifications_total{integration=email} was unreadable — cannot evaluate M-6"

  post_alert "$MUTED_NAME"   || void "cannot POST the muted synthetic alert to $ALERTMANAGER_URL"
  post_alert "$CONTROL_NAME" || void "cannot POST the control synthetic alert to $ALERTMANAGER_URL"

  # Wait for the CONTROL arm to be delivered. group_wait is 30s, so the counter
  # cannot move before then; polling until it does (or the deadline) is what makes
  # the muted arm's flat counter meaningful rather than merely early.
  DEADLINE=$(( $(date -u +%s) + M6_TIMEOUT ))
  AFTER="$BEFORE"
  while [ "$(date -u +%s)" -lt "$DEADLINE" ]; do
    sleep "$M6_INTERVAL"
    AFTER="$(am_counter)"
    [ "${AFTER:-0}" -gt "${BEFORE:-0}" ] && break
  done

  # Which alerts did Alertmanager actually accept? Read back rather than assume.
  ACTIVE="$(curl -sf --max-time 10 "$ALERTMANAGER_URL/api/v2/alerts" | jq -r '.[].labels.alertname' 2>/dev/null || true)"
  [ -n "$ACTIVE" ] || void "Alertmanager reported no active alerts at all — both synthetic posts vanished, so M-6 measured nothing"

  MUTED_PRESENT=0;  grep -qx -- "$MUTED_NAME"   <<< "$ACTIVE" && MUTED_PRESENT=1
  CONTROL_PRESENT=0; grep -qx -- "$CONTROL_NAME" <<< "$ACTIVE" && CONTROL_PRESENT=1

  if [ "$MUTED_PRESENT" -ne 1 ] || [ "$CONTROL_PRESENT" -ne 1 ]; then
    void "M-6 setup failed: Alertmanager did not register both probes (muted=$MUTED_PRESENT control=$CONTROL_PRESENT). A missing probe would make the muted arm pass for the wrong reason."
  fi

  # The control arm moved the counter, so notification is demonstrably alive.
  if [ "${AFTER:-0}" -le "${BEFORE:-0}" ]; then
    violation "M-6 CONTROL arm '$CONTROL_NAME' produced NO notification attempt within ${M6_TIMEOUT}s (notifications_total{email} $BEFORE -> $AFTER). Email notification is broken independently of the mute — do NOT read the muted arm as evidence the mute works."
  else
    # Now, and only now, is a flat counter for the muted name meaningful. Ask
    # Alertmanager which receiver each alert was routed to.
    MUTED_RECEIVERS="$(curl -sf --max-time 10 "$ALERTMANAGER_URL/api/v2/alerts?filter=alertname%3D%22${MUTED_NAME}%22" \
                       | jq -r '.[].receivers[].name' 2>/dev/null | sort -u || true)"
    CONTROL_RECEIVERS="$(curl -sf --max-time 10 "$ALERTMANAGER_URL/api/v2/alerts?filter=alertname%3D%22${CONTROL_NAME}%22" \
                         | jq -r '.[].receivers[].name' 2>/dev/null | sort -u || true)"

    [ -n "$MUTED_RECEIVERS" ] || void "could not read the receiver set for the muted probe — M-6 cannot conclude"
    [ -n "$CONTROL_RECEIVERS" ] || void "could not read the receiver set for the control probe — M-6 cannot conclude"

    if grep -qx 'email-default' <<< "$MUTED_RECEIVERS"; then
      violation "M-6 muted alert '$MUTED_NAME' routed to email-default (receivers: $(tr '\n' ' ' <<< "$MUTED_RECEIVERS")). The mute route is present but is not consuming the alert — check that it precedes any 'continue: true' sibling."
    fi
    if ! grep -qx 'mute-null' <<< "$MUTED_RECEIVERS"; then
      violation "M-6 muted alert '$MUTED_NAME' did not route to mute-null (receivers: $(tr '\n' ' ' <<< "$MUTED_RECEIVERS"))"
    fi
    if ! grep -qx 'email-default' <<< "$CONTROL_RECEIVERS"; then
      violation "M-6 CONTROL alert '$CONTROL_NAME' did not route to email-default (receivers: $(tr '\n' ' ' <<< "$CONTROL_RECEIVERS")). The mute is over-broad — it is catching alerts it was not configured for."
    fi

    [ "$VIOLATIONS" -eq 0 ] && echo "  M-6   muted '$MUTED_NAME' -> mute-null (no email) · control '$CONTROL_NAME' -> email-default · notifications_total{email} $BEFORE -> $AFTER"
  fi

  # Expire both probes so they do not linger in the UI.
  EXPIRED_AT="$(date -u -d '-1 minute' +%Y-%m-%dT%H:%M:%S.000Z)"
  post_alert "$MUTED_NAME"   "$EXPIRED_AT" || true
  post_alert "$CONTROL_NAME" "$EXPIRED_AT" || true
fi

# =================================================================================
if [ "$VIOLATIONS" -gt 0 ]; then
  echo "check-alert-mute: $VIOLATIONS violation(s)" >&2
  exit 1
fi
echo "check-alert-mute: PASS"
