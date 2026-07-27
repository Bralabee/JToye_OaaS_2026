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

# Slack (phase 27) — ADDITIVE. Email stays the default route in every case.
#
# __SLACK_ROUTE_BLOCK__ and __SLACK_RECEIVER_BLOCK__ in the template are
# WHOLE-BLOCK placeholders, deliberately not per-field, and they are ONE ATOMIC
# UNIT: the configured? test below renders both or neither. Rendering one without
# the other yields a route pointing at a receiver that does not exist, which
# amtool rejects.
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

TEMPLATE="/etc/alertmanager/alertmanager.yml.tmpl"
RENDERED="/etc/alertmanager/alertmanager.yml"

if [ "${SLACK_CONFIGURED}" = "1" ]; then
  echo "alertmanager entrypoint: Slack receiver ENABLED on ${ALERTMANAGER_SLACK_CHANNEL} (email remains the default route)"
  # continue: true — the child route matches everything and falls THROUGH, so
  # email-default still receives. Without it Slack would displace email rather
  # than be added to it.
  SLACK_ROUTE_BLOCK="  routes:
    - receiver: slack-additive
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
| SLACK_ROUTE_BLOCK="${SLACK_ROUTE_BLOCK}" SLACK_RECEIVER_BLOCK="${SLACK_RECEIVER_BLOCK}" awk '
    /^__SLACK_ROUTE_BLOCK__$/    { if (ENVIRON["SLACK_ROUTE_BLOCK"]    != "") print ENVIRON["SLACK_ROUTE_BLOCK"];    next }
    /^__SLACK_RECEIVER_BLOCK__$/ { if (ENVIRON["SLACK_RECEIVER_BLOCK"] != "") print ENVIRON["SLACK_RECEIVER_BLOCK"]; next }
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
