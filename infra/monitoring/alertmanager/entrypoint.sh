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

# Where the template is read and the render written. The defaults are compose's mounts;
# Kubernetes mounts the template from a ConfigMap elsewhere and renders into an emptyDir
# (plan 29-24). ALERTMANAGER_RENDER_ONLY=1 renders, checks and exits 0 before exec — what
# scripts/check-alertmanager-render.sh runs inside the pinned image.
: "${ALERTMANAGER_TEMPLATE:=/etc/alertmanager/alertmanager.yml.tmpl}"
: "${ALERTMANAGER_RENDERED:=/etc/alertmanager/alertmanager.yml}"
: "${ALERTMANAGER_RENDER_ONLY:=0}"

# The URL every notification links back to (--web.external-url). REQUIRED, with no default in
# this file: compose passes the loopback URL on port 9093 that this file used to hard-code
# (docker-compose.monitoring.yml, so compose's exec arguments are unchanged), and Kubernetes passes the
# in-cluster Service URL. A default here would put a loopback literal into the staging render,
# because this file IS in that render (a byte copy served by ConfigMap), and INV-4 of
# k8s/scripts/check-render-invariants.sh refuses one there for good reason (DEF-6).
: "${ALERTMANAGER_WEB_EXTERNAL_URL:=}"

# fatal <message...> — one shape for every operator error below. Never prints a value that
# could be a credential.
fatal() {
  echo "FATAL: $*" >&2
  exit 1
}

# A value interpolated into a single-quoted YAML scalar must not end the quote or the line.
yaml_safe() { # yaml_safe <VARNAME> <value>
  case "$2" in
    *"'"*) fatal "$1 contains a single quote, which would end the YAML scalar it is rendered into." ;;
  esac
  if [ "$(printf '%s' "$2" | wc -l)" -ne 0 ]; then
    fatal "$1 contains a newline."
  fi
}

case "${ALERTMANAGER_WEB_EXTERNAL_URL}" in
  http://?*|https://?*)
    case "${ALERTMANAGER_WEB_EXTERNAL_URL}" in
      *[[:space:]]*) fatal "ALERTMANAGER_WEB_EXTERNAL_URL contains whitespace." ;;
    esac ;;
  "") fatal "ALERTMANAGER_WEB_EXTERNAL_URL is not set (the URL notifications link back to; compose sets it in docker-compose.monitoring.yml)." ;;
  *) fatal "ALERTMANAGER_WEB_EXTERNAL_URL '${ALERTMANAGER_WEB_EXTERNAL_URL}' is not an http(s) URL." ;;
esac

# __SMTP_AUTH_BLOCK__ (plan 29-24) — SMTP AUTH for the primary email_config, ADDITIVE.
#
# Rendered on email_configs[0] ITSELF, never as global smtp_auth_*: Alertmanager copies the
# global auth fields into EVERY email_config that does not set its own (config.go, v0.34.1),
# and an empty per-config value cannot opt out. A global credential would therefore also be
# offered to the extra sink below whenever that server advertised AUTH. Scoped here, it
# reaches the one smarthost it belongs to.
#
# CREDENTIALS ARE ONLY RENDERED WITH ALERTMANAGER_SMTP_REQUIRE_TLS=true. Alertmanager
# authenticates whenever the server advertises AUTH, and its LOGIN mechanism sends the
# password in clear over a plain connection. This repo's own .env carries a USERNAME and a
# PASSWORD while compose's smarthost defaults to Mailhog with no TLS, so rendering them there
# would hand the real password to a capture server in clear. Instead the block renders
# nothing and the reason is printed, which leaves the compose render byte-identical
# (scripts/check-alertmanager-render.sh, arm compose-credentials-without-tls).
#
# The password reaches Alertmanager only as a FILE (auth_password_file): either the file
# named by ALERTMANAGER_SMTP_PASSWORD_FILE (Kubernetes: the CSI mount), or
# ALERTMANAGER_SMTP_PASSWORD written to a mode-600 file under /tmp. It is never inlined into
# the config, and the variable is unset before exec, so it does not stay in the process
# environment.
: "${ALERTMANAGER_SMTP_USERNAME:=}"
: "${ALERTMANAGER_SMTP_PASSWORD:=}"
: "${ALERTMANAGER_SMTP_PASSWORD_FILE:=}"

SMTP_AUTH_BLOCK=""
if [ -n "${ALERTMANAGER_SMTP_USERNAME}${ALERTMANAGER_SMTP_PASSWORD}${ALERTMANAGER_SMTP_PASSWORD_FILE}" ]; then
  case "${ALERTMANAGER_SMTP_REQUIRE_TLS}" in
    true|True|TRUE)
      [ -n "${ALERTMANAGER_SMTP_USERNAME}" ] || fatal "an SMTP password is set but ALERTMANAGER_SMTP_USERNAME is empty."
      yaml_safe ALERTMANAGER_SMTP_USERNAME "${ALERTMANAGER_SMTP_USERNAME}"
      if [ -n "${ALERTMANAGER_SMTP_PASSWORD}" ] && [ -n "${ALERTMANAGER_SMTP_PASSWORD_FILE}" ]; then
        fatal "both ALERTMANAGER_SMTP_PASSWORD and ALERTMANAGER_SMTP_PASSWORD_FILE are set; set exactly one."
      fi
      if [ -n "${ALERTMANAGER_SMTP_PASSWORD}" ]; then
        ALERTMANAGER_SMTP_PASSWORD_FILE="/tmp/alertmanager-smtp-password"
        ( umask 077 && printf '%s' "${ALERTMANAGER_SMTP_PASSWORD}" > "${ALERTMANAGER_SMTP_PASSWORD_FILE}" ) \
          || fatal "cannot write the SMTP password file ${ALERTMANAGER_SMTP_PASSWORD_FILE}."
        chmod 600 "${ALERTMANAGER_SMTP_PASSWORD_FILE}" || fatal "cannot chmod 600 ${ALERTMANAGER_SMTP_PASSWORD_FILE}."
      fi
      [ -n "${ALERTMANAGER_SMTP_PASSWORD_FILE}" ] || fatal "ALERTMANAGER_SMTP_USERNAME is set but neither ALERTMANAGER_SMTP_PASSWORD nor ALERTMANAGER_SMTP_PASSWORD_FILE is."
      yaml_safe ALERTMANAGER_SMTP_PASSWORD_FILE "${ALERTMANAGER_SMTP_PASSWORD_FILE}"
      # Alertmanager reads the file at send time, and amtool does not open it at all, so an
      # absent or empty file would surface only as a failed notification. Refuse it here.
      [ -r "${ALERTMANAGER_SMTP_PASSWORD_FILE}" ] || fatal "the SMTP password file ${ALERTMANAGER_SMTP_PASSWORD_FILE} is not readable."
      [ -s "${ALERTMANAGER_SMTP_PASSWORD_FILE}" ] || fatal "the SMTP password file ${ALERTMANAGER_SMTP_PASSWORD_FILE} is empty."
      echo "alertmanager entrypoint: SMTP auth ENABLED for ${ALERTMANAGER_SMTP_SMARTHOST} (password from file ${ALERTMANAGER_SMTP_PASSWORD_FILE})"
      SMTP_AUTH_BLOCK="        auth_username: '${ALERTMANAGER_SMTP_USERNAME}'
        auth_password_file: '${ALERTMANAGER_SMTP_PASSWORD_FILE}'"
      ;;
    *)
      echo "alertmanager entrypoint: SMTP credentials are set but ALERTMANAGER_SMTP_REQUIRE_TLS=${ALERTMANAGER_SMTP_REQUIRE_TLS} -> auth NOT rendered (credentials are only ever sent over a TLS-required connection; smarthost ${ALERTMANAGER_SMTP_SMARTHOST})"
      ;;
  esac
fi
unset ALERTMANAGER_SMTP_PASSWORD

# __EXTRA_EMAIL_CONFIGS_BLOCK__ (plan 29-24) — a SECOND email_config in receiver
# email-default, ADDITIVE. Staging uses it for Mailpit, the inspectable sink on the SAME
# route as the real inbox: Alertmanager notifies every integration in a receiver, so a
# message read back from the sink proves the real route dispatched (L-3), while the first
# email_config proves the inbox itself. It always requires TLS (Mailpit in staging refuses
# mail without STARTTLS), trusts ALERTMANAGER_SINK_CA_FILE when given (the cluster CA), and
# carries NO auth (see the SMTP auth note above). Its `to` defaults to ALERTMANAGER_SMTP_TO,
# so the sink holds a copy of exactly what the inbox was sent. Alertmanager's default email
# body carries every label and annotation, so the probe id is searchable there.
: "${ALERTMANAGER_SINK_SMARTHOST:=}"
: "${ALERTMANAGER_SINK_TO:=}"
: "${ALERTMANAGER_SINK_CA_FILE:=}"

EXTRA_EMAIL_CONFIGS_BLOCK=""
if [ -n "${ALERTMANAGER_SINK_SMARTHOST}" ]; then
  : "${ALERTMANAGER_SINK_TO:=${ALERTMANAGER_SMTP_TO}}"
  yaml_safe ALERTMANAGER_SINK_SMARTHOST "${ALERTMANAGER_SINK_SMARTHOST}"
  yaml_safe ALERTMANAGER_SINK_TO "${ALERTMANAGER_SINK_TO}"
  EXTRA_EMAIL_CONFIGS_BLOCK="      - to: '${ALERTMANAGER_SINK_TO}'
        smarthost: '${ALERTMANAGER_SINK_SMARTHOST}'
        require_tls: true
        send_resolved: true"
  if [ -n "${ALERTMANAGER_SINK_CA_FILE}" ]; then
    yaml_safe ALERTMANAGER_SINK_CA_FILE "${ALERTMANAGER_SINK_CA_FILE}"
    # amtool does not open ca_file either; an unreadable CA would fail every sink delivery.
    [ -r "${ALERTMANAGER_SINK_CA_FILE}" ] || fatal "the sink CA file ${ALERTMANAGER_SINK_CA_FILE} is not readable."
    EXTRA_EMAIL_CONFIGS_BLOCK="${EXTRA_EMAIL_CONFIGS_BLOCK}
        tls_config:
          ca_file: '${ALERTMANAGER_SINK_CA_FILE}'"
  fi
  echo "alertmanager entrypoint: extra email_config ENABLED to ${ALERTMANAGER_SINK_SMARTHOST} (require_tls${ALERTMANAGER_SINK_CA_FILE:+, ca_file ${ALERTMANAGER_SINK_CA_FILE}}; email-default notifies both)"
elif [ -n "${ALERTMANAGER_SINK_TO}${ALERTMANAGER_SINK_CA_FILE}" ]; then
  fatal "ALERTMANAGER_SINK_TO or ALERTMANAGER_SINK_CA_FILE is set but ALERTMANAGER_SINK_SMARTHOST is empty, so it would reach nothing."
fi

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

TEMPLATE="${ALERTMANAGER_TEMPLATE}"
RENDERED="${ALERTMANAGER_RENDERED}"

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

# Two passes. sed does the scalar SMTP fields; awk does the four whole blocks,
# because a block carries newlines and sed's s/// cannot substitute those
# portably. ENVIRON is used rather than awk -v: -v applies backslash-escape
# processing to the value, which would corrupt a webhook URL, a Go template or a
# credential path. When a block is empty its placeholder LINE IS DROPPED, not
# blanked, so the unconfigured render is byte-identical to the pre-phase-27
# output (AC-4.8) and to the pre-29-24 output (compose-render.golden.yml).
sed \
  -e "s|__SMTP_SMARTHOST__|${ALERTMANAGER_SMTP_SMARTHOST}|g" \
  -e "s|__SMTP_FROM__|${ALERTMANAGER_SMTP_FROM}|g" \
  -e "s|__SMTP_TO__|${ALERTMANAGER_SMTP_TO}|g" \
  -e "s|__SMTP_REQUIRE_TLS__|${ALERTMANAGER_SMTP_REQUIRE_TLS}|g" \
  "${TEMPLATE}" \
| CHILD_ROUTES_BLOCK="${CHILD_ROUTES_BLOCK}" EXTRA_RECEIVERS_BLOCK="${EXTRA_RECEIVERS_BLOCK}" \
  SMTP_AUTH_BLOCK="${SMTP_AUTH_BLOCK}" EXTRA_EMAIL_CONFIGS_BLOCK="${EXTRA_EMAIL_CONFIGS_BLOCK}" awk '
    /^__CHILD_ROUTES_BLOCK__$/        { if (ENVIRON["CHILD_ROUTES_BLOCK"]        != "") print ENVIRON["CHILD_ROUTES_BLOCK"];        next }
    /^__EXTRA_RECEIVERS_BLOCK__$/     { if (ENVIRON["EXTRA_RECEIVERS_BLOCK"]     != "") print ENVIRON["EXTRA_RECEIVERS_BLOCK"];     next }
    /^__SMTP_AUTH_BLOCK__$/           { if (ENVIRON["SMTP_AUTH_BLOCK"]           != "") print ENVIRON["SMTP_AUTH_BLOCK"];           next }
    /^__EXTRA_EMAIL_CONFIGS_BLOCK__$/ { if (ENVIRON["EXTRA_EMAIL_CONFIGS_BLOCK"] != "") print ENVIRON["EXTRA_EMAIL_CONFIGS_BLOCK"]; next }
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

if [ "${ALERTMANAGER_RENDER_ONLY}" = "1" ]; then
  echo "alertmanager entrypoint: RENDER_ONLY: rendered and checked ${RENDERED}; not starting alertmanager"
  exit 0
fi

exec /bin/alertmanager \
  --config.file="${RENDERED}" \
  --storage.path=/alertmanager \
  --web.external-url="${ALERTMANAGER_WEB_EXTERNAL_URL}"
