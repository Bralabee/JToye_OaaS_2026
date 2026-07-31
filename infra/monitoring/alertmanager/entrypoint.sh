#!/bin/sh
# Alertmanager entrypoint wrapper (phase 9)
#
# Alertmanager has no native env-var substitution, so we render the template
# at container start using sed. SMTP credentials (if any) are sensitive —
# NEVER use `set -x` here (it would leak secrets into container logs).
#
# Ordering:
#   1. Apply safe defaults (dev-friendly: Mailhog, no auth, no TLS)
#   2. Render template via sed (pipe delimiter because paths may contain `/`)
#   3. Run `amtool check-config` BEFORE exec (fail fast on malformed YAML)
#   4. Exec alertmanager with the rendered config

set -eu

: "${ALERTMANAGER_SMTP_SMARTHOST:=mailhog:1025}"
: "${ALERTMANAGER_SMTP_FROM:=alerts@jtoye.local}"
: "${ALERTMANAGER_SMTP_TO:=ops@jtoye.local}"
: "${ALERTMANAGER_SMTP_REQUIRE_TLS:=false}"

# __CHILD_ROUTES_BLOCK__ and __EXTRA_RECEIVERS_BLOCK__ are COMPOSED placeholders.
#
# `routes:` is a SINGLE YAML mapping key, so every feature that wants a child route
# has to contribute to one emission — emitting the key twice is a duplicate-key
# error that amtool rejects. Two features contribute today (the mute below, and
# Slack) and they are assembled in ROUTE ORDER, which is load-bearing:
#
#   1. mute-null   — matches first, NO `continue`, so a muted alert stops here and
#                    reaches neither email nor Slack.
#   2. slack-additive — `continue: true`, falls through to the email default.
#
# Each feature is still ATOMIC in itself: a route and the receiver it names are
# rendered together or not at all, because a route pointing at a receiver that does
# not exist is rejected.
#
# This prose lives HERE and not in the template because the template's comments are
# copied verbatim into the rendered alertmanager.yml, and adding comment lines there
# breaks the byte-identity criterion below. That was measured once already.
#
# Slack (phase 27) — ADDITIVE. Email stays the default route in every case.
#
# When unconfigured each placeholder LINE IS DROPPED, not blanked, so the render
# is byte-identical to the pre-phase-27 output — an acceptance criterion
# (AC-4.8), not a nicety: email must be exactly as it was for anyone who never
# sets a Slack URL. This documentation lives HERE rather than in the template
# header precisely because the template's comments are copied into the rendered
# alertmanager.yml, and adding twelve comment lines there broke that byte
# identity. Measured: the delta was comment-only, but the criterion says
# byte-identical and the fix was to move the prose, not to weaken the check.
: "${ALERTMANAGER_SLACK_WEBHOOK_URL:=}"
: "${ALERTMANAGER_SLACK_CHANNEL:=#jtoye-alerts}"

# CONFIGURED? is deliberately NOT "non-empty". This repo's own .env ships
#   ALERTMANAGER_SLACK_WEBHOOK_URL=https://hooks.slack.com/services/PLACEHOLDER/PLACEHOLDER/PLACEHOLDER
# A non-empty test would therefore render a receiver on this machine pointing at
# a dead URL, and every Slack notification would fail — worse than none, because
# the config would read as configured. A placeholder-SHAPED value counts as
# UNSET. Checked case-insensitively against the three shapes this repo actually
# uses for "not filled in yet".
SLACK_CONFIGURED=0
if [ -n "${ALERTMANAGER_SLACK_WEBHOOK_URL}" ]; then
  if echo "${ALERTMANAGER_SLACK_WEBHOOK_URL}" | grep -qiE 'PLACEHOLDER|CHANGE_ME|example\.com'; then
    echo "alertmanager entrypoint: Slack webhook is placeholder-shaped -> treated as UNSET (email only)"
  else
    SLACK_CONFIGURED=1
  fi
fi

# Environment-scoped notification mute. Comma-separated ALERTNAMES, default EMPTY.
#
# WHAT THIS IS: a NOTIFICATION mute, not a rule change. The Prometheus rule keeps
# evaluating and the alert still shows as firing in Prometheus and in the
# Alertmanager UI — only the notification is withheld. Nothing here can hide an
# alert from someone looking at it.
#
# WHY IT EXISTS: NoOrdersCreated fires ~30 minutes after the last order. On a quiet
# local stack that is permanent noise, and the alternative remedy
# (FORCE=1 scripts/seed-order-metric.sh) buys silence by writing a REAL ORDER ROW
# into the dev database every time. The alert is correct and wanted in production.
#
# THE MATCHER KEYS ON alertname AND NOTHING ELSE. scripts/check-alert-liveness.sh
# posts its L-3 transport probe with severity="info", service="platform"; a mute
# matching on severity would swallow that probe and turn L-3 red — and L-3's own
# failure text names "an active silence, an inhibit rule" as the cause, so it would
# read as a transport fault rather than as this config. scripts/check-alert-mute.sh
# asserts the label-name restriction independently of this comment.
#
# MALFORMED INPUT IS FATAL, not ignored. This file already exits 1 on an unrendered
# placeholder and on a failed amtool check-config; a mute value that does not parse
# is the same class of operator error, made seconds ago, and visible immediately in
# `docker compose up`. Silently dropping it would make a configured .env reach
# nothing — the precise failure recorded in the Slack comment above.
: "${ALERTMANAGER_MUTE_ALERTNAMES:=}"

MUTE_ROUTE_BLOCK=""
MUTE_RECEIVER_BLOCK=""
if [ -n "${ALERTMANAGER_MUTE_ALERTNAMES}" ]; then
  MUTE_ALTERNATION=""
  # POSIX field split on commas. Deliberately `for ... in $VAR` and NOT
  # `set -- $VAR`: the latter clobbers the positional parameters, which would
  # silently break this file the day someone adds a `command:` to the compose
  # service and forwards it with "$@" (the sibling prometheus/entrypoint.sh
  # already does exactly that). The loop is not a subshell, so a FATAL below
  # exits the script rather than only a pipeline.
  OLD_IFS="$IFS"
  IFS=','
  for raw in ${ALERTMANAGER_MUTE_ALERTNAMES}; do
    IFS="$OLD_IFS"
    # strip surrounding whitespace
    name=$(printf '%s' "$raw" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
    [ -z "$name" ] && continue
    # An alertname is a Prometheus identifier. Anything else — a label matcher, a
    # regex metacharacter, a quote — is rejected rather than interpolated, so this
    # variable cannot be used to widen the mute beyond alertname equality.
    if ! printf '%s' "$name" | grep -qE '^[A-Za-z][A-Za-z0-9_]*$'; then
      echo "FATAL: ALERTMANAGER_MUTE_ALERTNAMES contains '$name', which is not a valid alertname (^[A-Za-z][A-Za-z0-9_]*\$)." >&2
      echo "       Expected a comma-separated list of alert names, e.g. 'NoOrdersCreated'." >&2
      exit 1
    fi
    if [ -z "$MUTE_ALTERNATION" ]; then
      MUTE_ALTERNATION="$name"
    else
      MUTE_ALTERNATION="${MUTE_ALTERNATION}|${name}"
    fi
  done
  # Restore unconditionally: an all-whitespace value never enters the loop body,
  # so the in-loop restore above would not have run.
  IFS="$OLD_IFS"

  if [ -n "$MUTE_ALTERNATION" ]; then
    echo "alertmanager entrypoint: notification mute ACTIVE for alertname=~^(${MUTE_ALTERNATION})\$ — rules still evaluate and still show as firing; only the notification is withheld"
    # No `continue:` — a matched alert stops at mute-null and reaches neither the
    # email default nor Slack. This route MUST be emitted before the Slack one.
    MUTE_ROUTE_BLOCK="    - receiver: mute-null
      matchers:
        - alertname=~\"^(${MUTE_ALTERNATION})\$\""
    # A receiver with a name and no integrations is Alertmanager's null sink.
    MUTE_RECEIVER_BLOCK="  - name: mute-null"
  fi
fi

TEMPLATE="/etc/alertmanager/alertmanager.yml.tmpl"
RENDERED="/etc/alertmanager/alertmanager.yml"

if [ "${SLACK_CONFIGURED}" = "1" ]; then
  echo "alertmanager entrypoint: Slack receiver ENABLED on ${ALERTMANAGER_SLACK_CHANNEL} (email remains the default route)"
  # continue: true — the child route matches everything and falls THROUGH, so
  # email-default still receives. Without it Slack would displace email rather
  # than be added to it.
  SLACK_ROUTE_BLOCK="    - receiver: slack-additive
      continue: true"
  SLACK_RECEIVER_BLOCK="  - name: slack-additive
    slack_configs:
      - api_url: '${ALERTMANAGER_SLACK_WEBHOOK_URL}'
        channel: '${ALERTMANAGER_SLACK_CHANNEL}'
        send_resolved: true
        title: '[{{ .Status | toUpper }}] {{ .CommonLabels.alertname }} ({{ .CommonLabels.service }}/{{ .CommonLabels.severity }})'
        text: '{{ range .Alerts }}{{ .Annotations.summary }} — {{ .Annotations.description }}
{{ end }}'"
else
  SLACK_ROUTE_BLOCK=""
  SLACK_RECEIVER_BLOCK=""
fi

# Compose the two placeholders. ROUTE ORDER IS LOAD-BEARING: mute first (it stops
# there), Slack second (it falls through to email). The `routes:` key is emitted
# ONCE, and only when at least one child exists — an empty `routes:` is invalid,
# and two `routes:` keys are a duplicate-key error.
CHILD_ROUTES_BLOCK=""
for block in "${MUTE_ROUTE_BLOCK}" "${SLACK_ROUTE_BLOCK}"; do
  [ -z "$block" ] && continue
  if [ -z "$CHILD_ROUTES_BLOCK" ]; then
    CHILD_ROUTES_BLOCK="  routes:
${block}"
  else
    CHILD_ROUTES_BLOCK="${CHILD_ROUTES_BLOCK}
${block}"
  fi
done

# Receivers are order-insensitive, but each is emitted only alongside its route —
# a route naming a receiver that does not exist is rejected, and an orphan
# receiver is dead config.
EXTRA_RECEIVERS_BLOCK=""
for block in "${MUTE_RECEIVER_BLOCK}" "${SLACK_RECEIVER_BLOCK}"; do
  [ -z "$block" ] && continue
  if [ -z "$EXTRA_RECEIVERS_BLOCK" ]; then
    EXTRA_RECEIVERS_BLOCK="${block}"
  else
    EXTRA_RECEIVERS_BLOCK="${EXTRA_RECEIVERS_BLOCK}
${block}"
  fi
done

# Two passes. sed does the scalar SMTP fields; awk does the two whole blocks,
# because a block carries newlines and sed's s/// cannot substitute those
# portably. ENVIRON is used rather than awk -v: -v applies backslash-escape
# processing to the value, which would corrupt a webhook URL or a Go template.
# When a block is empty its placeholder LINE IS DROPPED, not blanked, so the
# unconfigured render is byte-identical to the pre-phase-27 output (AC-4.8).
sed \
  -e "s|__SMTP_SMARTHOST__|${ALERTMANAGER_SMTP_SMARTHOST}|g" \
  -e "s|__SMTP_FROM__|${ALERTMANAGER_SMTP_FROM}|g" \
  -e "s|__SMTP_TO__|${ALERTMANAGER_SMTP_TO}|g" \
  -e "s|__SMTP_REQUIRE_TLS__|${ALERTMANAGER_SMTP_REQUIRE_TLS}|g" \
  "${TEMPLATE}" \
| CHILD_ROUTES_BLOCK="${CHILD_ROUTES_BLOCK}" EXTRA_RECEIVERS_BLOCK="${EXTRA_RECEIVERS_BLOCK}" awk '
    /^__CHILD_ROUTES_BLOCK__$/    { if (ENVIRON["CHILD_ROUTES_BLOCK"]    != "") print ENVIRON["CHILD_ROUTES_BLOCK"];    next }
    /^__EXTRA_RECEIVERS_BLOCK__$/ { if (ENVIRON["EXTRA_RECEIVERS_BLOCK"] != "") print ENVIRON["EXTRA_RECEIVERS_BLOCK"]; next }
    { print }
  ' > "${RENDERED}"

# No placeholder may survive. Catches a renamed placeholder the substitution
# above was not updated for, which would otherwise start cleanly and email a
# literal __SMTP_TO__.
if grep -q '__[A-Z0-9_]\{1,\}__' "${RENDERED}"; then
  echo "FATAL: unrendered placeholder(s) remain in ${RENDERED}:" >&2
  grep -o '__[A-Z0-9_]\{1,\}__' "${RENDERED}" | sort -u >&2
  exit 1
fi

/bin/amtool check-config "${RENDERED}"

exec /bin/alertmanager \
  --config.file="${RENDERED}" \
  --storage.path=/alertmanager \
  --web.external-url=http://localhost:9093
